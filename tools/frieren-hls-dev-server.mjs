#!/usr/bin/env node
import { createServer } from "node:http";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, statSync } from "node:fs";
import { extname, join, normalize, relative, resolve } from "node:path";

const upstreamUrl =
  process.env.FRIEREN_MKV_URL ||
  "https://nexus-094.weur.tb-cdn.st/dld/bd6391cb-34d8-44c0-abdc-f31186648eb9?token=98c6d3f2-648a-4f3e-9653-0cdfc0bd4b21";

const port = Number(process.env.FRIEREN_HLS_PORT || 8090);
const durationSeconds = process.env.FRIEREN_HLS_DURATION ? Number(process.env.FRIEREN_HLS_DURATION) : null;
const subtitleDurationSeconds = process.env.FRIEREN_SUBTITLE_DURATION ? Number(process.env.FRIEREN_SUBTITLE_DURATION) : null;
const cacheDir = resolve(process.env.FRIEREN_HLS_CACHE || ".cache/frieren-hls");
const subtitlesDir = join(cacheDir, "subtitles");
const metadataPath = join(cacheDir, "metadata.json");

function run(command, args) {
  const result = spawnSync(command, args, { stdio: "inherit" });
  if (result.status !== 0) {
    throw new Error(`${command} exited with ${result.status}`);
  }
}

function capture(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(`${command} exited with ${result.status}: ${result.stderr}`);
  }
  return result.stdout;
}

function safeName(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "") || "track";
}

function probeTracks() {
  const raw = capture("ffprobe", [
    "-v",
    "error",
    "-show_entries",
    "stream=index,codec_type,codec_name:stream_tags=language,title",
    "-of",
    "json",
    upstreamUrl,
  ]);
  const streams = JSON.parse(raw).streams || [];
  return streams
    .filter((stream) => stream.codec_type === "subtitle" && stream.codec_name === "ass")
    .map((stream) => {
      const language = stream.tags?.language || "und";
      const title = stream.tags?.title || "";
      const label = [language, title].filter(Boolean).join(" ");
      const fileName = `${String(stream.index).padStart(2, "0")}-${safeName(label)}.ass`;
      return {
        index: stream.index,
        language,
        title,
        label: title ? `${language.toUpperCase()} ${title}` : language.toUpperCase(),
        fileName,
      };
    });
}

function generateHls() {
  mkdirSync(subtitlesDir, { recursive: true });

  const expectedMetadata = JSON.stringify({
    upstreamUrl,
    durationSeconds,
    subtitleDurationSeconds,
    version: 2,
  });
  const currentMetadata = existsSync(metadataPath) ? readFileSync(metadataPath, "utf8") : "";
  if (currentMetadata !== expectedMetadata) {
    rmSync(cacheDir, { recursive: true, force: true });
    mkdirSync(subtitlesDir, { recursive: true });
  }

  if (!existsSync(join(cacheDir, "master.raw.m3u8"))) {
    const args = [
      "-y",
      "-hide_banner",
      "-loglevel",
      "warning",
    ];
    if (durationSeconds != null) {
      args.push("-t", String(durationSeconds));
    }
    args.push(
      "-i",
      upstreamUrl,
      "-map",
      "0:a:0",
      "-map",
      "0:a:1",
      "-map",
      "0:v:0",
      "-c",
      "copy",
      "-f",
      "hls",
      "-hls_time",
      "6",
      "-hls_playlist_type",
      "vod",
      "-hls_flags",
      "independent_segments",
      "-hls_segment_filename",
      join(cacheDir, "stream_%v_%03d.ts"),
      "-master_pl_name",
      "master.raw.m3u8",
      "-var_stream_map",
      "a:0,agroup:aud,default:yes,language:jpn,name:jpn a:1,agroup:aud,language:eng,name:eng v:0,agroup:aud,name:video",
      join(cacheDir, "stream_%v.m3u8"),
    );
    run("ffmpeg", args);
  }

  const subtitleTracks = probeTracks();
  const missingSubtitle = subtitleTracks.some((track) => !existsSync(join(subtitlesDir, track.fileName)));
  if (missingSubtitle && subtitleTracks.length > 0) {
    const args = ["-y", "-hide_banner", "-loglevel", "warning"];
    if (subtitleDurationSeconds != null) {
      args.push("-t", String(subtitleDurationSeconds));
    }
    args.push("-i", upstreamUrl);
    subtitleTracks.forEach((track) => {
      args.push("-map", `0:${track.index}`, "-c:s", "copy", join(subtitlesDir, track.fileName));
    });
    run("ffmpeg", args);
  }

  const rawMaster = readFileSync(join(cacheDir, "master.raw.m3u8"), "utf8")
    .replace('NAME="audio_0"', 'NAME="Japanese"')
    .replace('NAME="audio_1"', 'NAME="English"');
  const subtitleLines = subtitleTracks
    .map(
      (track) =>
        `#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="ass",NAME="${track.label}",LANGUAGE="${track.language}",FORMAT="ASS",URI="subtitles/${track.fileName}"`,
    )
    .join("\n");

  const lines = rawMaster.trimEnd().split(/\r?\n/);
  const insertAt = Math.min(2, lines.length);
  lines.splice(insertAt, 0, subtitleLines);
  writeFileSync(join(cacheDir, "master.m3u8"), `${lines.filter(Boolean).join("\n")}\n`);
  writeFileSync(metadataPath, expectedMetadata);
}

function contentType(path) {
  switch (extname(path).toLowerCase()) {
    case ".m3u8":
      return "application/vnd.apple.mpegurl";
    case ".ts":
      return "video/mp2t";
    case ".ass":
      return "text/plain; charset=utf-8";
    default:
      return "application/octet-stream";
  }
}

generateHls();

createServer((request, response) => {
  const url = new URL(request.url || "/", `http://localhost:${port}`);
  const pathname = url.pathname === "/" ? "/master.m3u8" : url.pathname;
  const filePath = normalize(join(cacheDir, decodeURIComponent(pathname)));
  if (!relative(cacheDir, filePath).startsWith("..") && existsSync(filePath) && statSync(filePath).isFile()) {
    response.writeHead(200, {
      "access-control-allow-origin": "*",
      "content-type": contentType(filePath),
      "cache-control": "no-store",
    });
    response.end(readFileSync(filePath));
  } else {
    response.writeHead(404, { "access-control-allow-origin": "*" });
    response.end("Not found");
  }
}).listen(port, () => {
  console.log(`Frieren HLS dev server: http://localhost:${port}/master.m3u8`);
  console.log(`Cache: ${cacheDir}`);
});
