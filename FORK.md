# KMediaPlayer integration fork

This distribution is a compatibility-focused fork of
[`mrujjwalg/movi-player`](https://github.com/mrujjwalg/movi-player).
It is not affiliated with or endorsed by the upstream maintainer.

The historical TypeScript fork and native C/C++ shim keep the upstream
Apache-2.0 license and preserve upstream copyright and attribution. Changes in
those open layers are maintained in small commits so that generally useful
pieces can be offered upstream without coupling upstream to KMediaPlayer.

The Kotlin/Wasm KLIB under `player/` is the proprietary Suvio player line
starting with `0.4.0-alpha.3`. It is distributed under the license embedded in
that module and is not part of the historical npm package.

The `0.3.5-kmp.3` release is based directly on upstream's `develop` branch at
commit `dfa30c95f59a8aa118b507639cff6ddb049878b8`. That history includes the
merged pluggable subtitle renderer from commit
`9ae8e31d90f94861af3fb18a62484756b8e27a85`.

## Fork additions

- stable headless/player contracts, typed errors, and redaction-safe failures;
- transactional track selection with definitive outcomes;
- explicit rendering surfaces and conservative rendering diagnostics;
- pluggable embedded ASS/SSA rendering with bounded font attachment delivery;
- host-renderer ownership of the letterbox-aware subtitle overlay;
- Matroska default-edition chapter metadata;
- code-split adaptive engines and FFmpeg/WebAssembly assets;
- a minimal `./engine` entrypoint for hosts that provide their own UI.

The canonical fork source is
[`Shusek/movi-player`](https://github.com/Shusek/movi-player). A published
package version is built from the Git tag with the same version number.
