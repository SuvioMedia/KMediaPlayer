// Serve deterministic adaptive-streaming fixtures through Karma so Shaka exercises its real XHR path.
// The media remains encoded in the Kotlin test fixture file; this middleware only decodes it in memory.
const fs = require("fs");
const path = require("path");

const fixtureSourcePath = path.resolve(
    config.basePath,
    "../../../../mediaplayer/src/wasmJsTest/kotlin/io/github/kdroidfilter/composemediaplayer/WasmEngineRealPackageFixtures.kt"
);
const fixtureSource = fs.readFileSync(fixtureSourcePath, "utf8");
const kmediaWasmRuntimeUrl =
    "/composeResources/io.github.shusek.mediaplayer.generated.resources/files/kmedia-wasm-runtime";
const kmediaWasmRuntimeDirectory = path.resolve(
    config.basePath,
    "kotlin/composeResources/io.github.shusek.mediaplayer.generated.resources/files/kmedia-wasm-runtime"
);

function readKotlinStringConstant(name) {
    const marker = `internal const val ${name} =`;
    const start = fixtureSource.indexOf(marker);
    if (start < 0) {
        throw new Error(`Missing WasmEngine fixture constant: ${name}`);
    }
    const next = fixtureSource.indexOf("\ninternal const val ", start + marker.length);
    const expression = fixtureSource.slice(start + marker.length, next < 0 ? fixtureSource.length : next);
    const quotedParts = expression.match(/"(?:\\.|[^"\\])*"/g) || [];
    return quotedParts.map((part) => JSON.parse(part)).join("");
}

const kmediaWasmFixtureRoutes = {
    [`${kmediaWasmRuntimeUrl}/kmedia-wasm-runtime.json`]: {
        body: fs.readFileSync(path.resolve(kmediaWasmRuntimeDirectory, "kmedia-wasm-runtime.json")),
        contentType: "application/json"
    },
    [`${kmediaWasmRuntimeUrl}/kmedia-wasm.js`]: {
        body: fs.readFileSync(path.resolve(kmediaWasmRuntimeDirectory, "kmedia-wasm.js")),
        contentType: "text/javascript"
    },
    [`${kmediaWasmRuntimeUrl}/kmedia-wasm.wasm`]: {
        body: fs.readFileSync(path.resolve(kmediaWasmRuntimeDirectory, "kmedia-wasm.wasm")),
        contentType: "application/wasm"
    },
    "/__kmp_kmedia_wasm__/hls/index.m3u8": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_HLS_MANIFEST_BASE64"), "base64"),
        contentType: "application/vnd.apple.mpegurl"
    },
    "/__kmp_kmedia_wasm__/hls/segment00.ts": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_HLS_SEGMENT_BASE64"), "base64"),
        contentType: "video/mp2t"
    },
    "/__kmp_kmedia_wasm__/dash/manifest.mpd": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_DASH_MANIFEST_BASE64"), "base64"),
        contentType: "application/dash+xml"
    },
    "/__kmp_kmedia_wasm__/dash/init-stream0.m4s": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_DASH_VIDEO_INIT_BASE64"), "base64"),
        contentType: "video/mp4"
    },
    "/__kmp_kmedia_wasm__/dash/init-stream1.m4s": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_DASH_AUDIO_INIT_BASE64"), "base64"),
        contentType: "audio/mp4"
    },
    "/__kmp_kmedia_wasm__/dash/chunk-stream0-00001.m4s": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_DASH_VIDEO_CHUNK_BASE64"), "base64"),
        contentType: "video/mp4"
    },
    "/__kmp_kmedia_wasm__/dash/chunk-stream1-00001.m4s": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_DASH_AUDIO_CHUNK_ONE_BASE64"), "base64"),
        contentType: "audio/mp4"
    },
    "/__kmp_kmedia_wasm__/dash/chunk-stream1-00002.m4s": {
        body: Buffer.from(readKotlinStringConstant("WASM_ENGINE_DASH_AUDIO_CHUNK_TWO_BASE64"), "base64"),
        contentType: "audio/mp4"
    }
};

const kmediaWasmFixtureMiddleware = ["factory", function() {
    return function(request, response, next) {
        const pathname = new URL(request.url, "http://karma.local").pathname;
        const route = kmediaWasmFixtureRoutes[pathname];
        if (!route) {
            next();
            return;
        }
        const fullBody = route.body;
        response.setHeader("Content-Type", route.contentType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "no-store");

        if (request.method === "HEAD") {
            response.statusCode = 200;
            response.setHeader("Content-Length", String(fullBody.length));
            response.end();
            return;
        }

        const range = /^bytes=(\d+)-(\d*)$/i.exec(request.headers.range || "");
        if (range) {
            const start = Number(range[1]);
            if (start >= fullBody.length) {
                response.statusCode = 416;
                response.setHeader("Content-Range", `bytes */${fullBody.length}`);
                response.end();
                return;
            }
            const requestedEnd = range[2] ? Number(range[2]) : fullBody.length - 1;
            const end = Math.min(requestedEnd, fullBody.length - 1);
            const body = fullBody.subarray(start, end + 1);
            response.statusCode = 206;
            response.setHeader("Content-Range", `bytes ${start}-${end}/${fullBody.length}`);
            response.setHeader("Content-Length", String(body.length));
            response.end(body);
            return;
        }

        response.statusCode = 200;
        response.setHeader("Content-Length", String(fullBody.length));
        response.end(fullBody);
    };
}];

config.plugins = config.plugins || [];
config.plugins.push({ "middleware:kmedia-wasm-fixtures": kmediaWasmFixtureMiddleware });
config.set({ middleware: ["kmedia-wasm-fixtures"] });
