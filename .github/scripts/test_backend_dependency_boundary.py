#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class BackendDependencyBoundaryTest(unittest.TestCase):
    def test_base_player_has_no_optional_native_backend(self) -> None:
        source = (ROOT / "mediaplayer/build.gradle.kts").read_text()
        self.assertNotIn("kmedia-mpv", source)
        self.assertNotIn("kmedia-bridge", source)
        self.assertNotIn("kmedia-ffmpeg", source)
        self.assertNotIn("kmedia-ass-runtime", source)

    def test_each_adapter_exposes_its_client_transitively(self) -> None:
        mpv = (ROOT / "mediaplayer-mpv/build.gradle.kts").read_text()
        bridge = (ROOT / "mediaplayer-kmediabridge/build.gradle.kts").read_text()
        ass = (ROOT / "mediaplayer-ass/build.gradle.kts").read_text()
        self.assertIn('api("io.github.shusek:kmedia-mpv-runtime-android:', mpv)
        self.assertIn('api("io.github.shusek:kmedia-mpv-runtime-desktop:', mpv)
        self.assertIn('strictly(kmediaMpvVersion)', mpv)
        self.assertIn('name = "KMediaMpv"', mpv)
        self.assertIn('name = "KMediaFfmpegRuntime"', mpv)
        self.assertIn('name = "KMediaAssRuntime"', mpv)
        self.assertEqual(3, mpv.count("linkOnly = true"))
        self.assertIn('api("io.github.shusek:kmedia-bridge-ffmpeg:', bridge)
        self.assertIn('strictly(kmediaBridgeVersion)', bridge)
        self.assertNotIn("runtimeOnly", bridge)
        self.assertIn('api("io.github.shusek:kmedia-ass-runtime-android:', ass)
        self.assertIn('api("io.github.shusek:kmedia-ass-runtime-desktop:', ass)
        self.assertIn("strictly(assRuntimeVersion)", ass)

    def test_dual_backend_consumer_has_no_manual_native_coordinates(self) -> None:
        source = (ROOT / "androidApp/build.gradle.kts").read_text()
        self.assertIn('implementation(project(":mediaplayer-mpv"))', source)
        self.assertIn('implementation(project(":mediaplayer-kmediabridge"))', source)
        self.assertIn('implementation(project(":mediaplayer-ass"))', source)
        self.assertNotIn("kmedia-mpv-runtime", source)
        self.assertNotIn("kmedia-bridge-ffmpeg", source)
        self.assertNotIn("kmedia-ffmpeg-runtime", source)
        self.assertNotIn("kmedia-ass-runtime", source)


if __name__ == "__main__":
    unittest.main()
