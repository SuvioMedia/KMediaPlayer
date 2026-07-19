@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MkvFontAttachmentTest {
    @Test
    fun fontAttachmentIsCopiedPublishedAndCleared() {
        val video = document.createElement("video") as HTMLVideoElement
        val source = "https://example.test/video.mkv"
        val abort = prepareMkvFontExtraction(video, source)
        val file = createMkvFontFile("Brand.TTF", "application/octet-stream", size = 3, seed = 7)

        assertEquals("", storeMkvFontAttachment(video, file, source, abort))
        mutateMkvFontFirstByte(file, 99)

        assertEquals(1, storedMkvFontCount(video))
        assertEquals(7, storedMkvFontFirstByte(video, 0))
        assertEquals(3, storedMkvFontTotalBytes(video))
        assertEquals(1, mkvFontNotificationCount(video))

        video.destroyMkvSidecarTracks()

        assertEquals(0, storedMkvFontCount(video))
        assertEquals(0, storedMkvFontTotalBytes(video))
        assertEquals(2, mkvFontNotificationCount(video))
    }

    @Test
    fun deduplicationComparesBytesWhenMetadataCollides() {
        val video = document.createElement("video") as HTMLVideoElement
        val source = "https://example.test/video.mkv"
        val abort = prepareMkvFontExtraction(video, source)

        assertEquals(
            "",
            storeMkvFontAttachment(
                video,
                createMkvFontFile("same.woff2", "font/woff2", size = 4, seed = 1),
                source,
                abort,
            ),
        )
        assertEquals(
            "",
            storeMkvFontAttachment(
                video,
                createMkvFontFile("same.woff2", "font/woff2", size = 4, seed = 1),
                source,
                abort,
            ),
        )
        assertEquals(1, storedMkvFontCount(video))

        assertEquals(
            "",
            storeMkvFontAttachment(
                video,
                createMkvFontFile("same.woff2", "font/woff2", size = 4, seed = 2),
                source,
                abort,
            ),
        )
        assertEquals(2, storedMkvFontCount(video))
    }

    @Test
    fun staleNonFontAndOversizedAttachmentsAreNotStored() {
        val video = document.createElement("video") as HTMLVideoElement
        val source = "https://example.test/video.mkv"
        val abort = prepareMkvFontExtraction(video, source)

        assertEquals(
            "",
            storeMkvFontAttachment(
                video,
                createMkvFontFile("notes.txt", "text/plain", size = 3, seed = 1),
                source,
                abort,
            ),
        )
        assertEquals(0, storedMkvFontCount(video))

        val oversized =
            storeMkvFontAttachment(
                video,
                createMkvFontFile(
                    filename = "huge.otf",
                    mimetype = "font/otf",
                    size = 16 * 1024 * 1024 + 1,
                    seed = 1,
                ),
                source,
                abort,
            )
        assertTrue(oversized.contains("16 MiB"))
        assertEquals(0, storedMkvFontCount(video))

        seedMkvFontLimits(video, count = 0, totalBytes = 32 * 1024 * 1024)
        val totalLimit =
            storeMkvFontAttachment(
                video,
                createMkvFontFile("total.ttf", "font/ttf", size = 1, seed = 1),
                source,
                abort,
            )
        assertTrue(totalLimit.contains("32 MiB"))

        seedMkvFontLimits(video, count = 64, totalBytes = 64)
        val countLimit =
            storeMkvFontAttachment(
                video,
                createMkvFontFile("count.ttf", "font/ttf", size = 1, seed = 1),
                source,
                abort,
            )
        assertTrue(countLimit.contains("64-font"))

        seedMkvFontLimits(video, count = 0, totalBytes = 0)
        val staleAbort = createAbortController()
        assertEquals(
            "",
            storeMkvFontAttachment(
                video,
                createMkvFontFile("stale.ttf", "font/ttf", size = 3, seed = 1),
                source,
                staleAbort,
            ),
        )
        assertEquals(0, storedMkvFontCount(video))
    }
}

@Suppress("UNUSED_PARAMETER")
private fun prepareMkvFontExtraction(
    video: HTMLVideoElement,
    sourceUri: String,
): JsAny =
    js(
        """
        (function() {
            const abort = new AbortController();
            video.__composeMediaPlayerMkvSourceUri = sourceUri;
            video.__composeMediaPlayerMkvExtractAbort = abort;
            video.__composeMediaPlayerMkvFontFiles = [];
            video.__composeMediaPlayerMkvFontFileMetadata = [];
            video.__composeMediaPlayerMkvFontFileKeys = new Set();
            video.__composeMediaPlayerMkvFontTotalBytes = 0;
            video.__composeMediaPlayerMkvFontNotificationCount = 0;
            video.addEventListener("composemediaplayer:mkv-fonts-changed", function() {
                video.__composeMediaPlayerMkvFontNotificationCount += 1;
            });
            return abort;
        })()
        """,
    )

private fun createAbortController(): JsAny = js("new AbortController()")

@Suppress("UNUSED_PARAMETER")
private fun createMkvFontFile(
    filename: String,
    mimetype: String,
    size: Int,
    seed: Int,
): JsAny =
    js(
        """
        (function() {
            const data = new Uint8Array(size);
            if (size > 0) data[0] = seed;
            return { filename: filename, mimetype: mimetype, data: data };
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun mutateMkvFontFirstByte(
    file: JsAny,
    value: Int,
): Unit = js("file && file.data && file.data.length ? (file.data[0] = value) : undefined")

@Suppress("UNUSED_PARAMETER")
private fun seedMkvFontLimits(
    video: HTMLVideoElement,
    count: Int,
    totalBytes: Int,
): Unit =
    js(
        """
        {
            video.__composeMediaPlayerMkvFontFiles =
                Array.from({ length: count }, function() { return new Uint8Array([0]); });
            video.__composeMediaPlayerMkvFontFileMetadata = [];
            video.__composeMediaPlayerMkvFontFileKeys = new Set();
            video.__composeMediaPlayerMkvFontTotalBytes = totalBytes;
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun storedMkvFontCount(video: HTMLVideoElement): Int =
    js("Array.isArray(video.__composeMediaPlayerMkvFontFiles) ? video.__composeMediaPlayerMkvFontFiles.length : 0")

@Suppress("UNUSED_PARAMETER")
private fun storedMkvFontFirstByte(
    video: HTMLVideoElement,
    index: Int,
): Int = js("Number(video.__composeMediaPlayerMkvFontFiles[index][0])")

@Suppress("UNUSED_PARAMETER")
private fun storedMkvFontTotalBytes(video: HTMLVideoElement): Int =
    js("Number(video.__composeMediaPlayerMkvFontTotalBytes || 0)")

@Suppress("UNUSED_PARAMETER")
private fun mkvFontNotificationCount(video: HTMLVideoElement): Int =
    js("Number(video.__composeMediaPlayerMkvFontNotificationCount || 0)")
