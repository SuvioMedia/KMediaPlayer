# Apple ASS client build

The Apple build compiles only the renderer bridge and RGBA compositor into
`libcomposemediaplayer_ass.a`. All libass text-stack symbols remain unresolved
until the final application links the exact `KMediaAssRuntime` pod.

`build.sh` accepts an ARM64 iOS target, an output directory, and the matching
KMediaAssRuntime target output. Intel Apple slices and private static copies
of libass, FreeType, FriBidi, and HarfBuzz are not supported.
