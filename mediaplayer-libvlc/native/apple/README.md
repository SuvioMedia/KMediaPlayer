# KMediaVlc Apple adapter boundary

The iOS adapter links a small C dynamic-loader bridge into the Kotlin/Native
binary. The bridge accepts only explicit paths under the application's private
`Frameworks` directory and resolves KMediaVlc stable client ABI 2. It never
searches system paths or downloads native code.

Normal compilation links only KMediaPlayer's small dynamic-loader bridge. It
does not link a placeholder or require a local KMediaVlc checkout. The real
simulator gate resolves the immutable iOS runtime ZIP from Maven Central,
validates all 87 framework hashes and the vendored ABI header, embeds and signs
the selected frameworks, boots an available simulator when needed, and decodes
a real H.264/MKV frame:

```shell
./gradlew :mediaplayer-libvlc:iosSimulatorArm64LibVlcIntegrationTest
```

Applications resolve `io.github.shusek:kmedia-vlc-runtime-ios:0.1.0-rc.7@zip`
from Maven Central during their Gradle/Xcode build, select the active device or
simulator slice, and embed and sign the resulting frameworks in the app's
private `Frameworks` directory. The lower-level harness remains available for
diagnostics with a pre-extracted runtime:

```shell
mediaplayer-libvlc/native/apple/run-ios-simulator-integration-test.sh \
  /absolute/path/to/test.kexe \
  /absolute/path/to/simulator-frameworks \
  /absolute/path/to/new-work-directory \
  BOOTED-SIMULATOR-UDID
```

Passing this simulator test does not satisfy physical-device or
application-distribution validation.
