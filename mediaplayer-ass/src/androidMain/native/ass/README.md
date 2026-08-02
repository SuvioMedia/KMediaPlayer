# Android ASS native client

The Android module keeps the existing JNI renderer API but dynamically links
to `libkmediaffmpeg_ass.so` supplied by
`io.github.shusek:kmedia-ass-runtime-android:0.1.0-rc.5`.

The adapter AAR contains one client library per supported ARM ABI:

```text
libkmediaass.so -> libkmediaffmpeg_ass.so -> shared FreeType/FriBidi/HarfBuzz
```

No private libass or FriBidi copy is distributed by `composemediaplayer-ass`.
