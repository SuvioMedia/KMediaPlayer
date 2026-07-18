#include "AssRgbaCompositor.h"

#include <android/log.h>
#include <ass/ass.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define REQUIRED_LIBASS_VERSION 0x01705000
#define MAX_RGBA_FRAME_BYTES (64U * 1024U * 1024U)
#define MAX_INPUT_BYTES (64U * 1024U * 1024U)
#define MAX_ASS_IMAGES 65536U
#define MAX_FRAME_DIMENSION 32768
#define MAX_FONT_NAME_BYTES 4096U
#define MAX_FONT_CONFIG_BYTES 65536U
#define MAX_GLYPH_CACHE 1000000
#define MAX_BITMAP_CACHE_MIB 64
#define METADATA_LENGTH 6

#define RENDER_STATUS_ERROR (-1)
#define RENDER_STATUS_UNCHANGED 0
#define RENDER_STATUS_EMPTY 1
#define RENDER_STATUS_PIXELS 2

enum InputMode {
    INPUT_MODE_NONE = 0,
    INPUT_MODE_DATA = 1,
    INPUT_MODE_CHUNKS = 2,
};

enum CachedFrame {
    CACHED_FRAME_NONE = 0,
    CACHED_FRAME_EMPTY = 1,
    CACHED_FRAME_PIXELS = 2,
};

typedef struct Session {
    jlong handle;
    pthread_mutex_t mutex;
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    char *font_configuration;
    int32_t frame_width;
    int32_t frame_height;
    enum InputMode input_mode;
    enum CachedFrame cached_frame;
    AssRgbaBuffer rgba;
    AssRgbaImage *image_views;
    size_t image_view_capacity;
    struct Session *next;
} Session;

static pthread_mutex_t registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static Session *registry_head;
static uint64_t next_handle = 1;

static void libass_log_callback(int level, const char *format, va_list args,
                                void *opaque)
{
    (void) opaque;
    int priority = ANDROID_LOG_DEBUG;
    if (level <= 1)
        priority = ANDROID_LOG_ERROR;
    else if (level <= 3)
        priority = ANDROID_LOG_WARN;
    else if (level <= 5)
        priority = ANDROID_LOG_INFO;
    __android_log_vprint(priority, "KMediaAss", format, args);
}

static Session *find_session_locked(jlong handle)
{
    for (Session *session = registry_head; session; session = session->next) {
        if (session->handle == handle)
            return session;
    }
    return NULL;
}

static jlong allocate_handle_locked(void)
{
    for (;;) {
        if (next_handle == 0 || next_handle > (uint64_t) INT64_MAX)
            next_handle = 1;
        jlong candidate = (jlong) next_handle++;
        if (!find_session_locked(candidate))
            return candidate;
    }
}

/* Registry-first lock ordering prevents close-vs-call use-after-free. */
static Session *lock_session(jlong handle)
{
    if (handle <= 0)
        return NULL;
    if (pthread_mutex_lock(&registry_mutex) != 0)
        return NULL;
    Session *session = find_session_locked(handle);
    if (!session || pthread_mutex_lock(&session->mutex) != 0) {
        pthread_mutex_unlock(&registry_mutex);
        return NULL;
    }
    pthread_mutex_unlock(&registry_mutex);
    return session;
}

static void unlock_session(Session *session)
{
    pthread_mutex_unlock(&session->mutex);
}

static void invalidate_frame(Session *session)
{
    session->cached_frame = CACHED_FRAME_NONE;
}

static void destroy_session_resources(Session *session)
{
    if (session->track)
        ass_free_track(session->track);
    if (session->renderer)
        ass_renderer_done(session->renderer);
    if (session->library)
        ass_library_done(session->library);
    free(session->font_configuration);
    free(session->image_views);
    ass_rgba_buffer_release(&session->rgba);
}

static int byte_array_range_is_valid(JNIEnv *env, jbyteArray data,
                                     jint offset, jint length)
{
    if (!data || offset < 0 || length <= 0 ||
        (uint32_t) length > MAX_INPUT_BYTES)
        return 0;
    jsize array_length = (*env)->GetArrayLength(env, data);
    return offset <= array_length && length <= array_length - offset;
}

static char *copy_java_string(JNIEnv *env, jstring value, size_t max_bytes)
{
    if (!value)
        return NULL;
    jsize utf_length = (*env)->GetStringUTFLength(env, value);
    if (utf_length <= 0 || (size_t) utf_length > max_bytes)
        return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (!utf)
        return NULL;
    size_t length = (size_t) utf_length;
    char *copy = NULL;
    if (length != SIZE_MAX) {
        copy = (char *) malloc(length + 1U);
        if (copy)
            memcpy(copy, utf, length + 1U);
    }
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

static int metadata_is_valid(JNIEnv *env, jintArray metadata)
{
    return metadata && (*env)->GetArrayLength(env, metadata) >= METADATA_LENGTH;
}

static int write_metadata(JNIEnv *env, jintArray metadata, jint status,
                          jint x, jint y, jint width, jint height, jint stride)
{
    jint values[METADATA_LENGTH] = {status, x, y, width, height, stride};
    (*env)->SetIntArrayRegion(env, metadata, 0, METADATA_LENGTH, values);
    return !(*env)->ExceptionCheck(env);
}

static int ensure_image_view_capacity(Session *session, size_t count)
{
    if (count <= session->image_view_capacity)
        return 1;
    if (count > MAX_ASS_IMAGES || count > SIZE_MAX / sizeof(*session->image_views))
        return 0;
    AssRgbaImage *views = (AssRgbaImage *)
        realloc(session->image_views, count * sizeof(*views));
    if (!views)
        return 0;
    session->image_views = views;
    session->image_view_capacity = count;
    return 1;
}

static int copy_image_views(Session *session, const ASS_Image *head,
                            size_t *count_out)
{
    size_t count = 0;
    for (const ASS_Image *image = head; image; image = image->next) {
        if (count == MAX_ASS_IMAGES)
            return 0;
        ++count;
    }
    if (!ensure_image_view_capacity(session, count))
        return 0;

    size_t index = 0;
    for (const ASS_Image *image = head; image; image = image->next) {
        AssRgbaImage *view = &session->image_views[index++];
        view->width = image->w;
        view->height = image->h;
        view->stride = image->stride;
        view->bitmap = image->bitmap;
        view->color_rgba = image->color;
        view->dst_x = image->dst_x;
        view->dst_y = image->dst_y;
    }
    *count_out = count;
    return 1;
}

static jobject return_pixel_buffer(JNIEnv *env, jintArray metadata,
                                   Session *session)
{
    /*
     * The ByteBuffer is a borrowed view of Session memory. The Kotlin session
     * lock must remain held through the GPU upload: another render may realloc
     * this storage, and close releases it.
     */
    jobject result = (*env)->NewDirectByteBuffer(
        env, session->rgba.pixels, (jlong) session->rgba.size);
    if (!result)
        return NULL;
    if (!write_metadata(env, metadata, RENDER_STATUS_PIXELS,
                        session->rgba.x, session->rgba.y,
                        session->rgba.width, session->rgba.height,
                        session->rgba.stride)) {
        (*env)->DeleteLocalRef(env, result);
        return NULL;
    }
    return result;
}

static jint native_version(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;
    return (jint) ass_library_version();
}

static jlong native_create(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;
    if (ass_library_version() < REQUIRED_LIBASS_VERSION)
        return 0;

    Session *session = (Session *) calloc(1, sizeof(*session));
    if (!session)
        return 0;
    if (pthread_mutex_init(&session->mutex, NULL) != 0) {
        free(session);
        return 0;
    }

    session->library = ass_library_init();
    if (session->library)
        ass_set_message_cb(session->library, libass_log_callback, NULL);
    if (session->library)
        session->renderer = ass_renderer_init(session->library);
    if (session->library)
        session->track = ass_new_track(session->library);
    if (!session->library || !session->renderer || !session->track) {
        destroy_session_resources(session);
        pthread_mutex_destroy(&session->mutex);
        free(session);
        return 0;
    }
    ass_set_shaper(session->renderer, ASS_SHAPING_COMPLEX);
    /* Embedded fonts can be added before the app-private fontconfig file is ready. */
    ass_set_fonts(session->renderer, NULL, NULL,
                  ASS_FONTPROVIDER_NONE, NULL, 0);

    if (pthread_mutex_lock(&registry_mutex) != 0) {
        destroy_session_resources(session);
        pthread_mutex_destroy(&session->mutex);
        free(session);
        return 0;
    }
    session->handle = allocate_handle_locked();
    session->next = registry_head;
    registry_head = session;
    pthread_mutex_unlock(&registry_mutex);
    return session->handle;
}

static jboolean native_configure_fonts(JNIEnv *env, jclass clazz, jlong handle,
                                       jstring configuration_path)
{
    (void) clazz;
    Session *session = lock_session(handle);
    if (!session)
        return JNI_FALSE;
    char *path = copy_java_string(env, configuration_path,
                                  MAX_FONT_CONFIG_BYTES);
    struct stat path_status;
    size_t path_length = path ? strlen(path) : 0;
    if (!path || path_length == 0 ||
        stat(path, &path_status) != 0 || !S_ISREG(path_status.st_mode) ||
        path_status.st_size <= 0 || access(path, R_OK) != 0) {
        free(path);
        unlock_session(session);
        return JNI_FALSE;
    }

    ass_set_fonts(session->renderer, NULL, "sans-serif",
                  ASS_FONTPROVIDER_FONTCONFIG, path, 1);
    free(session->font_configuration);
    session->font_configuration = path;
    invalidate_frame(session);
    unlock_session(session);
    return JNI_TRUE;
}

static jboolean native_add_font(JNIEnv *env, jclass clazz, jlong handle,
                                jstring name, jbyteArray data)
{
    (void) clazz;
    if (!data || (*env)->GetArrayLength(env, data) <= 0 ||
        (uint32_t) (*env)->GetArrayLength(env, data) > MAX_INPUT_BYTES)
        return JNI_FALSE;
    Session *session = lock_session(handle);
    if (!session)
        return JNI_FALSE;

    char *font_name = copy_java_string(env, name, MAX_FONT_NAME_BYTES);
    jbyte *bytes = NULL;
    jsize length = (*env)->GetArrayLength(env, data);
    if (font_name && font_name[0] != '\0' &&
        strlen(font_name) <= MAX_FONT_NAME_BYTES)
        bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!font_name || font_name[0] == '\0' || !bytes) {
        free(font_name);
        unlock_session(session);
        return JNI_FALSE;
    }

    ass_add_font(session->library, font_name, (const char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    free(font_name);
    if (session->font_configuration) {
        ass_set_fonts(session->renderer, NULL, "sans-serif",
                      ASS_FONTPROVIDER_FONTCONFIG,
                      session->font_configuration, 0);
    } else {
        ass_set_fonts(session->renderer, NULL, NULL,
                      ASS_FONTPROVIDER_NONE, NULL, 0);
    }
    invalidate_frame(session);
    unlock_session(session);
    return JNI_TRUE;
}

static jboolean native_process_codec_private(JNIEnv *env, jclass clazz,
                                             jlong handle, jbyteArray data,
                                             jint offset, jint length)
{
    (void) clazz;
    if (!byte_array_range_is_valid(env, data, offset, length))
        return JNI_FALSE;
    Session *session = lock_session(handle);
    if (!session)
        return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        unlock_session(session);
        return JNI_FALSE;
    }
    ass_process_codec_private(session->track,
                              (const char *) bytes + offset, length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    invalidate_frame(session);
    unlock_session(session);
    return JNI_TRUE;
}

static jboolean native_process_data(JNIEnv *env, jclass clazz, jlong handle,
                                    jbyteArray data, jint offset, jint length)
{
    (void) clazz;
    if (!byte_array_range_is_valid(env, data, offset, length))
        return JNI_FALSE;
    Session *session = lock_session(handle);
    if (!session || session->input_mode == INPUT_MODE_CHUNKS) {
        if (session)
            unlock_session(session);
        return JNI_FALSE;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        unlock_session(session);
        return JNI_FALSE;
    }
    ass_process_data(session->track, (const char *) bytes + offset, length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    session->input_mode = INPUT_MODE_DATA;
    invalidate_frame(session);
    unlock_session(session);
    return JNI_TRUE;
}

static jboolean native_process_chunk(JNIEnv *env, jclass clazz, jlong handle,
                                     jlong start_ms, jlong duration_ms,
                                     jbyteArray data, jint offset, jint length)
{
    (void) clazz;
    if (duration_ms < 0 ||
        !byte_array_range_is_valid(env, data, offset, length))
        return JNI_FALSE;
    Session *session = lock_session(handle);
    if (!session || session->input_mode == INPUT_MODE_DATA) {
        if (session)
            unlock_session(session);
        return JNI_FALSE;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        unlock_session(session);
        return JNI_FALSE;
    }
    ass_process_chunk(session->track, (const char *) bytes + offset, length,
                      (long long) start_ms, (long long) duration_ms);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    session->input_mode = INPUT_MODE_CHUNKS;
    invalidate_frame(session);
    unlock_session(session);
    return JNI_TRUE;
}

static void native_set_cache_limits(JNIEnv *env, jclass clazz, jlong handle,
                                    jint glyph_count, jint bitmap_cache_mib)
{
    (void) env;
    (void) clazz;
    if (glyph_count < 0 || bitmap_cache_mib < 0)
        return;
    if (glyph_count > MAX_GLYPH_CACHE)
        glyph_count = MAX_GLYPH_CACHE;
    if (bitmap_cache_mib > MAX_BITMAP_CACHE_MIB)
        bitmap_cache_mib = MAX_BITMAP_CACHE_MIB;
    Session *session = lock_session(handle);
    if (!session)
        return;
    ass_set_cache_limits(session->renderer, glyph_count, bitmap_cache_mib);
    unlock_session(session);
}

static void native_set_storage_size(JNIEnv *env, jclass clazz, jlong handle,
                                    jint width, jint height)
{
    (void) env;
    (void) clazz;
    if (!((width == 0 && height == 0) ||
          (width > 0 && height > 0 &&
           width <= MAX_FRAME_DIMENSION && height <= MAX_FRAME_DIMENSION)))
        return;
    Session *session = lock_session(handle);
    if (!session)
        return;
    ass_set_storage_size(session->renderer, width, height);
    invalidate_frame(session);
    unlock_session(session);
}

static void native_set_frame_size(JNIEnv *env, jclass clazz, jlong handle,
                                  jint width, jint height)
{
    (void) env;
    (void) clazz;
    if (width <= 0 || height <= 0 ||
        width > MAX_FRAME_DIMENSION || height > MAX_FRAME_DIMENSION)
        return;
    Session *session = lock_session(handle);
    if (!session)
        return;
    ass_set_frame_size(session->renderer, width, height);
    session->frame_width = width;
    session->frame_height = height;
    invalidate_frame(session);
    unlock_session(session);
}

static jobject native_render(JNIEnv *env, jclass clazz, jlong handle,
                             jlong time_ms, jboolean force,
                             jintArray metadata)
{
    (void) clazz;
    if (!metadata_is_valid(env, metadata))
        return NULL;
    if (!write_metadata(env, metadata, RENDER_STATUS_ERROR, 0, 0, 0, 0, 0))
        return NULL;

    Session *session = lock_session(handle);
    if (!session)
        return NULL;
    if (session->frame_width <= 0 || session->frame_height <= 0) {
        unlock_session(session);
        return NULL;
    }

    int changed = 0;
    ASS_Image *images = ass_render_frame(session->renderer, session->track,
                                         (long long) time_ms, &changed);
    if (changed == 0 && force == JNI_FALSE) {
        write_metadata(env, metadata, RENDER_STATUS_UNCHANGED, 0, 0, 0, 0, 0);
        unlock_session(session);
        return NULL;
    }
    if (changed == 0 && force != JNI_FALSE) {
        if (session->cached_frame == CACHED_FRAME_EMPTY) {
            write_metadata(env, metadata, RENDER_STATUS_EMPTY, 0, 0, 0, 0, 0);
            unlock_session(session);
            return NULL;
        }
        if (session->cached_frame == CACHED_FRAME_PIXELS) {
            jobject result = return_pixel_buffer(env, metadata, session);
            unlock_session(session);
            return result;
        }
    }

    size_t image_count = 0;
    if (!copy_image_views(session, images, &image_count)) {
        session->cached_frame = CACHED_FRAME_NONE;
        unlock_session(session);
        return NULL;
    }
    AssRgbaResult composite = ass_rgba_composite(
        session->image_views, image_count,
        session->frame_width, session->frame_height,
        MAX_RGBA_FRAME_BYTES, &session->rgba);
    if (composite == ASS_RGBA_ERROR) {
        session->cached_frame = CACHED_FRAME_NONE;
        unlock_session(session);
        return NULL;
    }
    if (composite == ASS_RGBA_EMPTY) {
        session->cached_frame = CACHED_FRAME_EMPTY;
        write_metadata(env, metadata, RENDER_STATUS_EMPTY, 0, 0, 0, 0, 0);
        unlock_session(session);
        return NULL;
    }

    session->cached_frame = CACHED_FRAME_PIXELS;
    jobject result = return_pixel_buffer(env, metadata, session);
    unlock_session(session);
    return result;
}

static void native_close(JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env;
    (void) clazz;
    if (handle <= 0 || pthread_mutex_lock(&registry_mutex) != 0)
        return;

    Session **link = &registry_head;
    while (*link && (*link)->handle != handle)
        link = &(*link)->next;
    Session *session = *link;
    if (!session) {
        pthread_mutex_unlock(&registry_mutex);
        return;
    }
    *link = session->next;
    if (pthread_mutex_lock(&session->mutex) != 0) {
        pthread_mutex_unlock(&registry_mutex);
        return;
    }
    pthread_mutex_unlock(&registry_mutex);

    destroy_session_resources(session);
    pthread_mutex_unlock(&session->mutex);
    pthread_mutex_destroy(&session->mutex);
    free(session);
}

static const JNINativeMethod native_methods[] = {
    {"nativeVersion", "()I", native_version},
    {"nativeCreate", "()J", native_create},
    {"nativeConfigureFonts", "(JLjava/lang/String;)Z", native_configure_fonts},
    {"nativeAddFont", "(JLjava/lang/String;[B)Z", native_add_font},
    {"nativeProcessCodecPrivate", "(J[BII)Z", native_process_codec_private},
    {"nativeProcessData", "(J[BII)Z", native_process_data},
    {"nativeProcessChunk", "(JJJ[BII)Z", native_process_chunk},
    {"nativeSetCacheLimits", "(JII)V", native_set_cache_limits},
    {"nativeSetStorageSize", "(JII)V", native_set_storage_size},
    {"nativeSetFrameSize", "(JII)V", native_set_frame_size},
    {"nativeRender", "(JJZ[I)Ljava/nio/ByteBuffer;", native_render},
    {"nativeClose", "(J)V", native_close},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    if (ass_library_version() < REQUIRED_LIBASS_VERSION)
        return JNI_ERR;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    jclass bridge = (*env)->FindClass(
        env,
        "io/github/kdroidfilter/composemediaplayer/subtitle/AndroidAssNativeBridge");
    if (!bridge)
        return JNI_ERR;
    jint method_count = (jint) (sizeof(native_methods) / sizeof(native_methods[0]));
    if ((*env)->RegisterNatives(env, bridge, native_methods, method_count) != JNI_OK) {
        (*env)->DeleteLocalRef(env, bridge);
        return JNI_ERR;
    }
    (*env)->DeleteLocalRef(env, bridge);
    return JNI_VERSION_1_6;
}
