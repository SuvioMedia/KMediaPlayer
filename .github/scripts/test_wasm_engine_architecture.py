#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WASM_MAIN = ROOT / "mediaplayer/src/wasmJsMain"


def active_wasm_source() -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(WASM_MAIN.rglob("*.kt"))
    )


class WasmEngineArchitectureTest(unittest.TestCase):
    def test_kmedia_does_not_create_or_control_a_playback_element(self) -> None:
        source = active_wasm_source()
        forbidden_patterns = {
            "video element construction": r"document\.createElement\(\s*['\"]video['\"]\s*\)",
            "canvas render-target construction": r"document\.createElement\(\s*['\"]canvas['\"]\s*\)",
            "direct media play/pause": r"\b(?:video|videoElement|mediaElement)\.(?:play|pause)\s*\(",
            "direct media source/clock mutation": r"\b(?:video|videoElement|mediaElement)\.(?:src|currentTime)\s*=",
        }
        for responsibility, pattern in forbidden_patterns.items():
            self.assertIsNone(
                re.search(pattern, source),
                f"KMediaPlayer reclaimed the engine's {responsibility} responsibility.",
            )

    def test_legacy_web_routes_and_adaptive_engines_are_absent(self) -> None:
        source = active_wasm_source()
        build = (ROOT / "mediaplayer/build.gradle.kts").read_text(encoding="utf-8")
        for legacy_name in (
            "LegacyWebVideoPlayerSurface",
            "WebPlaybackRoute",
            "WebPlaybackRouting",
            "WebPlaybackEngine",
        ):
            self.assertNotIn(legacy_name, source)

        for browser_router in ("shaka-player", "hls.js", "dash.js"):
            self.assertNotIn(browser_router, build.lower())

    def test_public_abi_does_not_leak_engine_or_retired_types(self) -> None:
        api_text = "\n".join(
            path.read_text(encoding="utf-8")
            for module in ("mediaplayer", "mediaplayer-extension-api", "mediaplayer-ass")
            for path in sorted((ROOT / module / "api").rglob("*.api"))
        )
        self.assertNotIn("WebPlaybackEngine", api_text)
        self.assertNotIn("webPlaybackEngine", api_text)
        self.assertNotIn("io.github.shusek.moviplayer", api_text)
        self.assertNotIn("createMovi", api_text)
        self.assertIn("WebMediaAdvancedControls", api_text)

    def test_surface_only_mounts_elements_returned_by_the_engine(self) -> None:
        surface = (
            ROOT
            / "mediaplayer/src/wasmJsMain/kotlin/io/github/kdroidfilter/composemediaplayer/VideoPlayerSurface.wasm.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("is PlayerSurface.Canvas", surface)
        self.assertIn("is PlayerSurface.NativeVideo", surface)
        self.assertIn("container.appendChild(surface.element)", surface)
        self.assertNotIn("HTMLVideoElement.play", surface)
        self.assertNotIn("HTMLVideoElement.pause", surface)


if __name__ == "__main__":
    unittest.main()
