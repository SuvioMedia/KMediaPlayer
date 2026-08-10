# KMediaVlc Apple adapter boundary

The iOS adapter links a small C dynamic-loader bridge into the Kotlin/Native
binary. The bridge accepts only explicit paths under the application's private
`Frameworks` directory and resolves KMediaVlc stable client ABI 2. It never
searches system paths or downloads native code.

Normal local compilation uses `compile-only-kmediavlc-pod`. That placeholder
contains no KMediaVlc or libVLC runtime and intentionally exercises the typed
`RUNTIME_DEPENDENCY_MISSING` path. To link against an audited local candidate,
pass its pod directory explicitly:

```shell
./gradlew :mediaplayer-libvlc:linkDebugTestIosSimulatorArm64 \
  -PkmediaVlcPodDirectory=/absolute/path/to/KMediaVlc-pod \
  -PkmediaVlcPodVersion=0.1.0 \
  --no-configuration-cache
```

The simulator integration harness packages that test executable with a flat
directory containing the candidate's simulator frameworks, signs the complete
application graph ad hoc, and requires an already booted simulator:

```shell
mediaplayer-libvlc/native/apple/run-ios-simulator-integration-test.sh \
  /absolute/path/to/test.kexe \
  /absolute/path/to/simulator-frameworks \
  /absolute/path/to/new-work-directory \
  BOOTED-SIMULATOR-UDID
```

Passing this simulator test does not satisfy physical-device, signing,
distribution, or legal release gates.
