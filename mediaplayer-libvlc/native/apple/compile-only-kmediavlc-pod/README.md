# Compile-only KMediaVlc pod

This local pod intentionally contains no KMediaVlc or libVLC runtime. It lets
ordinary iOS compilation and unit tests verify that the adapter reports a
controlled missing-runtime result. It must not be distributed as KMediaVlc or
used as evidence that an iOS native payload is present.

Use the `kmediaVlcPodDirectory` Gradle property to select an audited local
candidate for integration testing.
