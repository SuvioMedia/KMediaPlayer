# KMediaPlayer Android ASS native notices

The Android ASS/SSA backend bundles the following upstream components:

| Component | Version/revision | License |
|---|---|---|
| libass | 0.17.5 / `4a05d8127f525943ebf45fdc6497c9e665947f0d` | ISC |
| FriBidi | 1.0.16 / `68162babff4f39c4e2dc164a5e825af93bda9983` | LGPL-2.1-or-later |
| fontconfig | 2.16.2 / `daa175d234b8a362eedd4c18c33537cc2d19cd98` | permissive/MIT-style notices |
| FreeType | 2.13.3 / `42608f77f20749dd6ddc9e0536788eaad70ea4b5` | FreeType License |
| HarfBuzz | 11.3.3 / `c3fcbffa651cea70400552f2a8bd695ad11023c1` | old MIT plus component notices |
| Expat | 2.7.1 / `f9a3eeb3e09fbea04b1c451ffc422ab2f1e45744` | MIT |
| libunibreak | 6.1 / `304585d8e2d63187507368d612c3d5fff1486368` | zlib |
| libass-android build wrapper | `07b447fabceee6a0811e58652a468bb4b5429163` | MIT |
| libass-cmake build wrapper | `d3f00a43ca66e42a2c34de964b1a7dbbfa9dbc8b` | MIT |

Complete license texts and notices are in `LICENSES/`.

FriBidi is shipped as the independently replaceable
`libkmediafribidi.so`; it is not statically included in the libass core or JNI
bridge. The exact complete FriBidi source archive, build packager, export map,
and replacement instructions are in `corresponding-source/`. See `SOURCE.md`.

The Android platform `libz.so` is dynamically used and is not bundled. The JNI
and RGBA compositor code in this distribution are part of KMediaPlayer.
