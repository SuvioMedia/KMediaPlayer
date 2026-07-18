#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>

typedef int32_t (*GetBuffersDataSpace)(ANativeWindow *);

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_composemediaplayer_AndroidNativeSurfaceDataSpaceBridge_nativeReadDataSpace(
    JNIEnv *env,
    jclass clazz,
    jobject surface) {
    (void)clazz;
    if (surface == NULL) return -1;

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) return -1;

    void *libandroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (libandroid == NULL) {
        ANativeWindow_release(window);
        return -1;
    }

    GetBuffersDataSpace get_data_space =
        (GetBuffersDataSpace)dlsym(libandroid, "ANativeWindow_getBuffersDataSpace");
    if (get_data_space == NULL) {
        dlclose(libandroid);
        ANativeWindow_release(window);
        return -1;
    }

    int32_t result = get_data_space(window);

    dlclose(libandroid);
    ANativeWindow_release(window);
    return result;
}
