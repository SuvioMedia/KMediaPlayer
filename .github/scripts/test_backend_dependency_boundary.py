#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

from __future__ import annotations

import re
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

    def test_backend_client_versions_are_sourced_from_the_catalog(self) -> None:
        catalog = (ROOT / "gradle/libs.versions.toml").read_text()
        mpv_match = re.search(
            r'^kmediaMpv = "([^"]+)"$',
            catalog,
            re.MULTILINE,
        )
        bridge_match = re.search(
            r'^kmediaBridge = "([^"]+)"$',
            catalog,
            re.MULTILINE,
        )
        self.assertIsNotNone(mpv_match)
        self.assertIsNotNone(bridge_match)
        mpv_version = mpv_match.group(1)
        bridge_version = bridge_match.group(1)

        self.assertIn(
            f"spec.dependency 'KMediaMpv', '{mpv_version}'",
            (ROOT / "mediaplayer-mpv/ComposeMediaPlayerMpv.podspec").read_text(),
        )
        self.assertIn(
            f'KMEDIA_MPV_VERSION:-{mpv_version}',
            (ROOT / ".github/scripts/verify_apple_mpv_payload.sh").read_text(),
        )
        self.assertIn(
            f"MPV_VERSION: {mpv_version}",
            (ROOT / ".github/workflows/build-natives.yml").read_text(),
        )
        pom_verifier = (ROOT / ".github/scripts/verify_maven_poms.py").read_text()
        self.assertEqual(2, pom_verifier.count('_catalog_version("kmediaMpv")'))
        self.assertEqual(2, pom_verifier.count('_catalog_version("kmediaBridge")'))
        self.assertNotIn(f'"{mpv_version}"', pom_verifier)
        self.assertNotIn(f'"{bridge_version}"', pom_verifier)

    def test_apple_ass_tests_link_the_exact_shared_runtime_payload(self) -> None:
        source = (ROOT / "mediaplayer-ass/build.gradle.kts").read_text()
        self.assertIn('.resolve("Frameworks")', source)
        self.assertIn("target.binaries.all", source)
        self.assertIn('linkerOpts("-F$runtimeFrameworkDirectory")', source)
        self.assertIn("getTest(NativeBuildType.DEBUG).linkerOpts", source)
        self.assertIn('"-rpath"', source)

    def test_mpv_adapter_scopes_auxiliary_legal_resources(self) -> None:
        resource_root = ROOT / "mediaplayer-mpv/src/commonMain/resources/META-INF"
        legal_root = resource_root / "kmediaplayer/mpv/legal"
        notice = legal_root / "THIRD_PARTY_NOTICES.md"
        license_file = legal_root / "LICENSES/mpv-client-api-ISC.txt"

        self.assertTrue(notice.is_file())
        self.assertTrue(license_file.is_file())
        self.assertIn(
            "META-INF/kmediaplayer/mpv/legal/LICENSES/mpv-client-api-ISC.txt",
            notice.read_text(),
        )
        self.assertFalse((resource_root / "THIRD_PARTY_NOTICES.md").exists())
        self.assertFalse((resource_root / "LICENSES/mpv-client-api-ISC.txt").exists())


if __name__ == "__main__":
    unittest.main()
