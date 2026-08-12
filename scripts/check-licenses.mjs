#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("..", import.meta.url)));

async function text(path) {
  return readFile(resolve(root, path), "utf8");
}

function requireMatch(value, pattern, description) {
  if (!pattern.test(value)) {
    throw new Error(`License audit failed: ${description}`);
  }
}

const [
  license,
  notice,
  packageJsonText,
  packageLockText,
  dockerfile,
  buildScript,
  playerLicense,
  playerBuild,
  runtimeBuild,
  relinking,
] =
  await Promise.all([
    text("LICENSE"),
    text("NOTICE"),
    text("package.json"),
    text("package-lock.json"),
    text("docker/Dockerfile"),
    text("docker/build-ffmpeg.sh"),
    text("player/legal/META-INF/LICENSE"),
    text("player/build.gradle.kts"),
    text("runtime-assets/build.gradle.kts"),
    text("LGPL_RELINKING.md"),
  ]);
const packageJson = JSON.parse(packageJsonText);
const packageLock = JSON.parse(packageLockText);

if (packageJson.private !== true) {
  throw new Error(
    "License audit failed: the historical Apache npm package must not be publishable",
  );
}
if (packageJson.license !== "UNLICENSED") {
  throw new Error(
    "License audit failed: the private browser-player package must be marked UNLICENSED",
  );
}
if (packageLock.packages?.[""]?.license !== "UNLICENSED") {
  throw new Error(
    "License audit failed: package-lock metadata still licenses the private browser player",
  );
}

requireMatch(
  playerLicense,
  /Suvio Proprietary Component License/u,
  "the Kotlin/Wasm player is not marked proprietary",
);
requireMatch(
  playerBuild,
  /sourcesJar\s*=\s*SourcesJar\.Empty\(\)/u,
  "the proprietary KLIB publication does not use empty source JARs",
);
if (/name\.set\("Apache License 2\.0"\)/u.test(playerBuild)) {
  throw new Error(
    "License audit failed: the proprietary KLIB POM still advertises Apache-2.0",
  );
}
requireMatch(
  runtimeBuild,
  /GNU Lesser General Public License, version 2\.1 or later/u,
  "the native runtime POM omits FFmpeg's LGPL license",
);
requireMatch(
  relinking,
  /io\.github\.shusek:kmedia-wasm-engine-runtime-assets/u,
  "the relinking guide names a retired runtime artifact",
);
requireMatch(
  relinking,
  /kmedia-wasm-runtime\/kmedia-wasm\.wasm/u,
  "the relinking guide names a retired runtime path",
);

for (const heading of [
  "FFmpeg",
  "dav1d",
  "Emscripten",
  "musl libc",
  "Signalsmith Stretch",
  "zlib",
  "hls.js",
  "dash.js",
  "Shaka Player",
]) {
  if (!license.includes(heading)) {
    throw new Error(`License audit failed: LICENSE is missing ${heading}`);
  }
}

for (const requiredFile of [
  "LICENSE",
  "NOTICE",
  "FORK.md",
  "LGPL_RELINKING.md",
  "compose.yaml",
  "docker/Dockerfile",
  "docker/build-ffmpeg.sh",
  "wasm",
]) {
  if (!packageJson.files?.includes(requiredFile)) {
    throw new Error(
      `License audit failed: npm package omits ${requiredFile}`,
    );
  }
}

requireMatch(
  dockerfile,
  /ARG FFMPEG_VERSION=n[0-9.]+/u,
  "FFmpeg source version is not pinned",
);
requireMatch(
  dockerfile,
  /ARG DAV1D_VERSION=[0-9.]+/u,
  "dav1d source version is not pinned",
);
if (/--enable-(?:gpl|nonfree)\b/u.test(buildScript)) {
  throw new Error(
    "License audit failed: GPL or non-free FFmpeg components are enabled",
  );
}
requireMatch(
  buildScript,
  /--disable-all/u,
  "FFmpeg is not using an explicit component allowlist",
);
requireMatch(
  notice,
  /LGPL_RELINKING\.md/u,
  "NOTICE does not point to relinking instructions",
);

const expectedRuntimeLicenses = new Map([
  ["hls.js", /Apache-2\.0/iu],
  ["dashjs", /BSD-3-Clause/iu],
  ["shaka-player", /Apache-2\.0/iu],
]);
for (const [dependency, expected] of expectedRuntimeLicenses) {
  const dependencyPackage =
    packageLock.packages?.[`node_modules/${dependency}`];
  if (dependencyPackage == null) {
    throw new Error(
      `License audit failed: package-lock.json omits ${dependency}`,
    );
  }
  if (!expected.test(String(dependencyPackage.license ?? ""))) {
    throw new Error(
      `License audit failed: unexpected ${dependency} license metadata`,
    );
  }
}

console.log(
  "License audit passed: notices, source/relinking materials, FFmpeg flags, and runtime dependency metadata are present.",
);
