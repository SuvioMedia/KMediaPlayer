# Rebuilding the Android ASS client

`composemediaplayer-ass` contains only `libkmediaass.so`, a thin JNI renderer
bridge. It does not build or package libass, FreeType, FriBidi, or HarfBuzz.

Download the `KMediaAssRuntime 0.1.0-rc.6` SDKs for both Android ARM targets,
arrange the extracted target outputs below one directory, then run:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk \
  ./mediaplayer-ass/native/android/build-client.sh \
  /path/to/runtime-target-outputs \
  /path/to/android-ndk/29.0.14206865
```

The input directory must contain `android-arm64-v8a/` and
`android-armeabi-v7a/` target outputs from the exact runtime commit. The script
rewrites `CHECKSUMS.sha256`; the AAR verifier rejects every native file except
the two architecture-specific bridge libraries.
