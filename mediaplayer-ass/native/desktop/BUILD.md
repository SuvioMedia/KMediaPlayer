# Reproducible Windows and Linux ASS native builds

The JVM artifact stores each payload below
`composemediaplayer/ass/native/<platform>-<architecture>/`. Every directory has
a `runtime.properties` manifest containing the main library, the declared
libass version, the complete runtime file list and a SHA-256 digest for every
file. The JVM loader verifies those digests before loading native code.

## Windows

Windows requires PowerShell, Git, Visual Studio 2022 build tools and the
Windows SDK. The script checks out the exact official vcpkg commit pinned in
`build-windows.ps1`, builds dynamic x64 and ARM64 packages, and copies libass
plus all of its runtime DLL dependencies:

```powershell
./native/desktop/build-windows.ps1 `
  -OutputRoot ./build/generated/desktopAssJvmResources
```

The pinned vcpkg release currently supplies libass 0.17.4. libass uses
DirectWrite for font discovery on Windows.

## Linux

Linux builds are native, one architecture per host. Install a C/C++ toolchain,
Meson, Ninja, pkg-config, curl, make, patchelf, Fontconfig development headers,
NASM for the x86_64 renderer, and ordinary archive tools. For example on
Debian or Ubuntu:

```bash
sudo apt-get install \
  build-essential meson nasm ninja-build pkg-config curl xz-utils \
  autoconf automake libtool patchelf libfontconfig1-dev
```

Then run the matching target:

```bash
./native/desktop/build-linux.sh \
  linux-x86-64 ./build/generated/desktopAssJvmResources

./native/desktop/build-linux.sh \
  linux-aarch64 ./build/generated/desktopAssJvmResources
```

The Linux script verifies and builds these pinned releases:

| Component | Version | SHA-256 |
| --- | ---: | --- |
| libass | 0.17.5 | `2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc` |
| FreeType | 2.14.3 | `36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f` |
| HarfBuzz | 14.2.1 | `a54a5d8e9380a41fbb762ce367bcbf7704792dfca0d93f1bbca86c5a57902e0e` |
| libunibreak | 7.0 | `8c9a6e121736cd0d5c890ae3ae96f3f4010a19aa040f1dbded833a62a87717d3` |
| FriBidi source | 1.0.16 | `9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a` |

FreeType, HarfBuzz and libunibreak are linked into the private libass shared
library. FriBidi remains replaceable. Fontconfig is intentionally resolved
from the Linux desktop runtime so font discovery follows the host's
configuration and installed fonts.
