#!/usr/bin/env bash

set -euo pipefail

readonly source_file="mediaplayer/src/wasmJsMain/kotlin/io/github/kdroidfilter/composemediaplayer/WebGpuProjectionRenderer.web.kt"

command -v naga >/dev/null 2>&1 || {
    echo "naga-cli is required to validate the embedded WebGPU shader." >&2
    exit 1
}

awk '
    /const shaderSource = `/{capture=1; next}
    capture && /^`;/ {found_end=1; exit}
    capture {print; found_body=1}
    END {if (!capture || !found_body || !found_end) exit 2}
' "$source_file" | naga --stdin-file-path WebGpuProjectionRenderer.wgsl
