package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object MacLibAssLocator {
    fun findLibAss(): String? {
        configuredLibAss()?.let { configured ->
            if (File(configured).isFile) return configured
        }

        return listOf(
            "/opt/homebrew/opt/libass/lib/libass.dylib",
            "/opt/homebrew/lib/libass.dylib",
            "/usr/local/opt/libass/lib/libass.dylib",
            "/usr/local/lib/libass.dylib",
            "/opt/local/lib/libass.dylib",
        ).firstOrNull { File(it).isFile }
    }

    private fun configuredLibAss(): String? =
        listOfNotNull(
            System.getProperty("composemediaplayer.macos.libass"),
            System.getenv("COMPOSE_MEDIA_PLAYER_LIBASS"),
            System.getenv("LIBASS_PATH"),
        ).firstOrNull { it.isNotBlank() }
}

internal object MacEmbeddedAssExtractor {
    private val cache = ConcurrentHashMap<String, MacAssSubtitleData>()

    fun extract(
        uri: String,
        streamIndex: Int,
        playbackTimeMs: Long = 0L,
        requestHeaders: Map<String, String> = emptyMap(),
    ): MacAssSubtitleData {
        val headers = requestHeaders.sanitizedRequestHeaders()
        val cacheKey = cacheKey(uri, streamIndex, headers)
        cache[cacheKey]?.let { return it }

        MacMatroskaAssExtractor
            .extractPartial(
                uri = uri,
                streamIndex = streamIndex,
                playbackTimeMs = playbackTimeMs,
                requestHeaders = headers,
            )
            ?.let { return it }

        return extractComplete(uri = uri, streamIndex = streamIndex, requestHeaders = headers)
    }

    fun extractComplete(
        uri: String,
        streamIndex: Int,
        requestHeaders: Map<String, String> = emptyMap(),
    ): MacAssSubtitleData {
        val headers = requestHeaders.sanitizedRequestHeaders()
        val cacheKey = cacheKey(uri, streamIndex, headers)
        cache[cacheKey]?.let { return it }

        var builtInExtractorFailure: Throwable? = null
        try {
            MacMatroskaAssExtractor
                .extract(uri = uri, streamIndex = streamIndex, requestHeaders = headers)
                ?.let { data ->
                    cache[cacheKey] = data
                    return data
                }
        } catch (e: Throwable) {
            builtInExtractorFailure = e
        }

        val ffmpeg =
            MacFfmpegLocator.findFfmpeg()
                ?: throw UnsupportedOperationException(
                    "Embedded ASS subtitle extraction failed with the built-in Matroska extractor" +
                        builtInExtractorFailure?.message?.let { ": $it" }.orEmpty() +
                        ". Optional ffmpeg fallback was not found. ComposeMediaPlayer does not bundle or link ffmpeg.",
                )

        val outputDirectory = Files.createTempDirectory("compose-media-player-ass-")
        val outputFile = outputDirectory.resolve("subtitles.ass")
        try {
            val command =
                mutableListOf(
                    ffmpeg,
                    "-nostdin",
                    "-y",
                    "-v",
                    "error",
                    "-dump_attachment:t",
                    "",
                )
            headers.requestHeadersLineString().takeIf { it.isNotBlank() }?.let { headerLines ->
                command += listOf("-headers", headerLines)
            }
            command +=
                listOf(
                    "-i",
                    uri,
                    "-map",
                    "0:$streamIndex",
                    "-c:s",
                    "ass",
                    "-f",
                    "ass",
                    outputFile.fileName.toString(),
                )
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .directory(outputDirectory.toFile())
                    .start()

            if (!process.waitFor(300, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw UnsupportedOperationException(
                    "Timed out while extracting embedded ASS subtitle track with ffmpeg.",
                )
            }
            val diagnostics = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                throw UnsupportedOperationException(
                    "Failed to extract embedded ASS subtitle track with ffmpeg: " +
                        diagnostics.ifBlank { "exit ${process.exitValue()}" },
                )
            }

            val text = Files.readString(outputFile)
            if (!text.contains("[Events]", ignoreCase = true)) {
                throw UnsupportedOperationException(
                    "The selected embedded subtitle track did not produce ASS/SSA content.",
                )
            }
            val data =
                MacAssSubtitleData(
                    content = text,
                    fonts = readExtractedFonts(outputDirectory, outputFile),
                )
            cache[cacheKey] = data
            return data
        } finally {
            deleteRecursively(outputDirectory)
        }
    }

    private fun readExtractedFonts(
        outputDirectory: Path,
        subtitleFile: Path,
    ): List<MacAssFontAttachment> {
        val fonts = mutableListOf<MacAssFontAttachment>()
        Files
            .list(outputDirectory)
            .use { stream ->
                stream
                    .filter { it != subtitleFile }
                    .filter { Files.isRegularFile(it) }
                    .filter { path ->
                        val name = path.fileName.toString().lowercase()
                        name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc")
                    }.map { path ->
                        MacAssFontAttachment(
                            name = path.fileName.toString(),
                            data = Files.readAllBytes(path),
                        )
                    }.forEach(fonts::add)
            }
        return fonts
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files
            .walk(path)
            .use { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
    }

    private fun cacheKey(
        uri: String,
        streamIndex: Int,
        requestHeaders: Map<String, String>,
    ): String {
        val headerToken = requestHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).hashCode()
        return "$uri#$streamIndex#$headerToken"
    }
}

internal data class MacAssSubtitleData(
    val content: String,
    val fonts: List<MacAssFontAttachment> = emptyList(),
    val isPartial: Boolean = false,
)

internal data class MacAssFontAttachment(
    val name: String,
    val data: ByteArray,
)
