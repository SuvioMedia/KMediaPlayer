#include "KMediaAssRenderer.h"

#include <jni.h>
#include <stdint.h>

static jint native_version(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;
    return (jint) kmedia_ass_library_version();
}

static jlong native_create(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;
    return (jlong) (uintptr_t) kmedia_ass_renderer_create();
}

static jboolean native_add_font(JNIEnv *env, jclass clazz, jlong handle,
                                jstring name, jbyteArray data)
{
    (void) clazz;
    if (!handle || !name || !data)
        return JNI_FALSE;

    const char *font_name = (*env)->GetStringUTFChars(env, name, NULL);
    if (!font_name)
        return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = size > 0
        ? (*env)->GetByteArrayElements(env, data, NULL)
        : NULL;
    int result = bytes
        ? kmedia_ass_renderer_add_font(
              (KMediaAssRenderer *) (uintptr_t) handle,
              font_name,
              (const uint8_t *) bytes,
              (size_t) size)
        : 0;
    if (bytes)
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, font_name);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_set_track(JNIEnv *env, jclass clazz, jlong handle,
                                 jbyteArray data)
{
    (void) clazz;
    if (!handle || !data)
        return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = size > 0
        ? (*env)->GetByteArrayElements(env, data, NULL)
        : NULL;
    int result = bytes
        ? kmedia_ass_renderer_set_track(
              (KMediaAssRenderer *) (uintptr_t) handle,
              (const uint8_t *) bytes,
              (size_t) size)
        : 0;
    if (bytes)
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_blend_bgra(JNIEnv *env, jclass clazz, jlong handle,
                                  jobject pixels, jint row_bytes,
                                  jint width, jint height, jlong time_ms)
{
    (void) clazz;
    if (!handle || !pixels)
        return JNI_FALSE;
    uint8_t *address = (uint8_t *) (*env)->GetDirectBufferAddress(env, pixels);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, pixels);
    if (!address || capacity <= 0)
        return JNI_FALSE;
    return kmedia_ass_renderer_blend_bgra(
               (KMediaAssRenderer *) (uintptr_t) handle,
               address,
               (size_t) capacity,
               row_bytes,
               width,
               height,
               time_ms)
        ? JNI_TRUE
        : JNI_FALSE;
}

static void native_close(JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env;
    (void) clazz;
    kmedia_ass_renderer_destroy(
        (KMediaAssRenderer *) (uintptr_t) handle);
}

static const JNINativeMethod native_methods[] = {
    {"nativeVersion", "()I", (void *) native_version},
    {"nativeCreate", "()J", (void *) native_create},
    {"nativeAddFont", "(JLjava/lang/String;[B)Z", (void *) native_add_font},
    {"nativeSetTrack", "(J[B)Z", (void *) native_set_track},
    {"nativeBlendBgra", "(JLjava/nio/ByteBuffer;IIIJ)Z",
     (void *) native_blend_bgra},
    {"nativeClose", "(J)V", (void *) native_close},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    if (kmedia_ass_library_version() < 0x01705000U)
        return JNI_ERR;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    jclass bridge = (*env)->FindClass(
        env,
        "io/github/kdroidfilter/composemediaplayer/ass/AppleAssNativeBridge");
    if (!bridge)
        return JNI_ERR;
    jint method_count =
        (jint) (sizeof(native_methods) / sizeof(native_methods[0]));
    if ((*env)->RegisterNatives(env, bridge, native_methods, method_count) != JNI_OK) {
        (*env)->DeleteLocalRef(env, bridge);
        return JNI_ERR;
    }
    (*env)->DeleteLocalRef(env, bridge);
    return JNI_VERSION_1_6;
}
