package io.github.kdroidfilter.composemediaplayer.mac

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.MAC_FFMPEG_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.MAC_FFMPEG_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name

internal object MacAvFoundationContainerSupport {
    private val unsupportedExtensions = setOf("mkv", "mk3d", "mka", "mks", "webm")
    private val unsupportedContentTypes =
        setOf(
            "video/x-matroska",
            "audio/x-matroska",
            "application/x-matroska",
            "video/webm",
            "audio/webm",
        )

    suspend fun needsFfmpegFallback(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            val localFile = localFile(uri)
            if (localFile != null) {
                return@withContext hasUnsupportedExtension(localFile.name) || hasMatroskaSignature(localFile)
            }

            if (hasUnsupportedExtension(uriPath(uri))) {
                return@withContext true
            }

            if (!isHttpUrl(uri)) {
                return@withContext false
            }

            val headers = readRemoteHeaders(uri)
            if (headers != null) {
                val contentType = headers.contentType?.substringBefore(";")?.trim()?.lowercase()
                if (contentType in unsupportedContentTypes) {
                    return@withContext true
                }
                if (hasUnsupportedExtension(headers.contentDispositionFilename)) {
                    return@withContext true
                }
            }

            readRemotePrefix(uri)?.let(::hasMatroskaSignature)
                ?: false
        }

    private fun localFile(uri: String): File? {
        val scheme = runCatching { URI(uri).scheme?.lowercase() }.getOrNull()
        return when (scheme) {
            null, "" -> File(uri)
            "file" ->
                runCatching { File(URI(uri)) }
                    .getOrElse { File(uri.removePrefix("file://")) }
            else -> null
        }
    }

    private fun uriPath(uri: String): String? =
        runCatching { URI(uri).path }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun hasUnsupportedExtension(value: String?): Boolean {
        val path = value?.substringBefore("?")?.substringBefore("#") ?: return false
        val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in unsupportedExtensions
    }

    private fun hasMatroskaSignature(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(64)
                val read = input.read(bytes)
                read > 0 && hasMatroskaSignature(bytes.copyOf(read))
            }
        }.getOrDefault(false)
    }

    internal fun hasMatroskaSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val hasEbmlHeader =
            bytes[0] == 0x1A.toByte() &&
                bytes[1] == 0x45.toByte() &&
                bytes[2] == 0xDF.toByte() &&
                bytes[3] == 0xA3.toByte()
        if (!hasEbmlHeader) return false

        val ascii = String(bytes, Charsets.ISO_8859_1).lowercase()
        return "matroska" in ascii || "webm" in ascii
    }

    private fun isHttpUrl(uri: String): Boolean {
        val scheme = runCatching { URI(uri).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    private fun readRemoteHeaders(uri: String): RemoteHeaders? =
        runCatching {
            (URL(uri).openConnection() as? HttpURLConnection)?.run {
                instanceFollowRedirects = true
                requestMethod = "HEAD"
                connectTimeout = REMOTE_PROBE_TIMEOUT_MS
                readTimeout = REMOTE_PROBE_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                try {
                    RemoteHeaders(
                        contentType = contentType,
                        contentDispositionFilename = contentDispositionFilename(getHeaderField("Content-Disposition")),
                    )
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()

    private fun readRemotePrefix(uri: String): ByteArray? =
        runCatching {
            (URL(uri).openConnection() as? HttpURLConnection)?.run {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = REMOTE_PROBE_TIMEOUT_MS
                readTimeout = REMOTE_PROBE_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=0-63")
                try {
                    inputStream.use { input ->
                        val bytes = ByteArray(64)
                        val read = input.read(bytes)
                        if (read > 0) bytes.copyOf(read) else null
                    }
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()

    private fun contentDispositionFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val parts = contentDisposition.split(';').map { it.trim() }
        val filenamePart =
            parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
                ?: parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
                ?: return null
        return filenamePart
            .substringAfter('=')
            .substringAfter("''")
            .trim()
            .trim('"')
    }

    private data class RemoteHeaders(
        val contentType: String?,
        val contentDispositionFilename: String?,
    )

    private const val REMOTE_PROBE_TIMEOUT_MS = 3500
    private const val USER_AGENT = "ComposeMediaPlayer/1.0"
}

internal object MacFfmpegLocator {
    fun findFfmpeg(): String? {
        val configured = configuredFfmpeg()
        if (configured != null && isExecutable(configured)) {
            return configured
        }

        return (executableCandidates("ffmpeg") + homebrewFfmpegFullCandidates())
            .distinct()
            .firstOrNull(::isExecutable)
    }

    fun findFfmpegWithSubtitles(): String? {
        val configured = configuredFfmpeg()
        if (configured != null) {
            return configured.takeIf { isExecutable(it) && MacFfmpegHlsFallback.supportsSubtitlesFilter(it) }
        }

        return (homebrewFfmpegFullCandidates() + executableCandidates("ffmpeg"))
            .distinct()
            .firstOrNull { isExecutable(it) && MacFfmpegHlsFallback.supportsSubtitlesFilter(it) }
    }

    fun findFfprobe(ffmpegPath: String): String? {
        val ffmpegFile = File(ffmpegPath)
        val sibling = ffmpegFile.parentFile?.resolve("ffprobe")?.absolutePath
        if (sibling != null && isExecutable(sibling)) {
            return sibling
        }
        return searchExecutable("ffprobe")
    }

    fun findFfprobe(): String? = searchExecutable("ffprobe")

    private fun configuredFfmpeg(): String? =
        listOfNotNull(
            System.getProperty("composemediaplayer.macos.ffmpeg"),
            System.getenv("COMPOSE_MEDIA_PLAYER_FFMPEG"),
            System.getenv("FFMPEG_PATH"),
        ).firstOrNull { it.isNotBlank() }

    private fun searchExecutable(name: String): String? =
        executableCandidates(name).firstOrNull(::isExecutable)

    private fun executableCandidates(name: String): List<String> =
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.map { File(it, name).absolutePath }
            .orEmpty() +
            listOf(
                "/opt/homebrew/bin/$name",
                "/usr/local/bin/$name",
                "/opt/local/bin/$name",
            )

    private fun homebrewFfmpegFullCandidates(): List<String> =
        listOf(
            "/opt/homebrew/opt/ffmpeg-full/bin/ffmpeg",
            "/usr/local/opt/ffmpeg-full/bin/ffmpeg",
        )

    private fun isExecutable(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile && file.canExecute()
    }
}

internal class MacFfmpegHlsFallback(
    private val ffmpegPath: String,
) : Closeable {
    private var process: Process? = null
    private var outputDirectory: Path? = null
    private var httpServer: HttpServer? = null
    private var logReader: Thread? = null
    private var hasSubtitlesFilter: Boolean? = null
    private val recentOutput = StringBuilder()

    suspend fun start(
        uri: String,
        selectedAudioStreamIndex: Int? = null,
        selectedSubtitleStreamIndex: Int? = null,
        startTimeSeconds: Double = 0.0,
    ): HlsFallbackSource =
        withContext(Dispatchers.IO) {
            close()

            val trackInfo = probeTrackInfo(uri)
            val selectedAudioStream =
                selectedAudioStreamIndex
                    ?.let { requested -> trackInfo.audioStreams.firstOrNull { it.streamIndex == requested } }
                    ?: trackInfo.audioStreams.firstOrNull { it.track.isDefault }
                    ?: trackInfo.audioStreams.firstOrNull()
            val selectedSubtitleStream =
                selectedSubtitleStreamIndex
                    ?.let { requested -> trackInfo.subtitleStreams.firstOrNull { it.streamIndex == requested } }

            if (selectedSubtitleStream != null && !supportsSubtitlesFilter()) {
                throw UnsupportedOperationException(
                    "Full ASS/SSA rendering on macOS fallback requires an external ffmpeg build with libass " +
                        "and the subtitles filter enabled. The configured ffmpeg does not expose that filter.",
                )
            }

            val tempDirectory = Files.createTempDirectory("compose-media-player-macos-hls-")
            outputDirectory = tempDirectory
            val playlist = tempDirectory.resolve("stream.m3u8")
            val server = startHttpServer(tempDirectory)
            httpServer = server
            val playlistUrl = "http://127.0.0.1:${server.address.port}/stream.m3u8"
            val segmentPattern = tempDirectory.resolve("segment_%05d.ts").absolutePathString()

            val command =
                buildFfmpegCommand(
                    uri = uri,
                    segmentPattern = segmentPattern,
                    playlist = playlist,
                    selectedAudioStreamIndex = selectedAudioStream?.streamIndex,
                    selectedSubtitleStream = selectedSubtitleStream,
                    startTimeSeconds = startTimeSeconds,
                )
            val startedProcess =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            process = startedProcess
            startLogReader(startedProcess)

            try {
                waitForPlaylist(playlist, startedProcess)
                HlsFallbackSource(
                    playlistUrl = playlistUrl,
                    durationSeconds = trackInfo.durationSeconds,
                    playbackOffsetSeconds = startTimeSeconds.coerceAtLeast(0.0),
                    audioTracks = trackInfo.audioStreams.map { it.track },
                    selectedAudioStreamIndex = selectedAudioStream?.streamIndex,
                    subtitleTracks = trackInfo.subtitleStreams.map { it.track },
                    selectedSubtitleStreamIndex = selectedSubtitleStream?.streamIndex,
                )
            } catch (e: Exception) {
                close()
                throw e
            }
        }

    override fun close() {
        httpServer?.stop(0)
        httpServer = null

        val currentProcess = process
        process = null
        if (currentProcess != null) {
            stopProcess(currentProcess)
        }

        logReader = null
        outputDirectory?.let(::deleteRecursively)
        outputDirectory = null
    }

    private fun stopProcess(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(600, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun startHttpServer(root: Path): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            serveHlsFile(root, exchange)
        }
        server.start()
        return server
    }

    private fun serveHlsFile(
        root: Path,
        exchange: HttpExchange,
    ) {
        try {
            val rawPath = exchange.requestURI.path.removePrefix("/").ifBlank { "stream.m3u8" }
            val decodedPath = URLDecoder.decode(rawPath, Charsets.UTF_8.name())
            val file = root.resolve(decodedPath).normalize()
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1)
                return
            }

            exchange.responseHeaders.add("Content-Type", contentType(file))
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            val size = Files.size(file)
            exchange.sendResponseHeaders(200, size)
            Files.newInputStream(file).use { input ->
                exchange.responseBody.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
            runCatching { exchange.sendResponseHeaders(500, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun contentType(file: Path): String =
        when (file.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }

    private fun buildFfmpegCommand(
        uri: String,
        segmentPattern: String,
        playlist: Path,
        selectedAudioStreamIndex: Int?,
        selectedSubtitleStream: FfmpegSubtitleStream?,
        startTimeSeconds: Double,
    ): List<String> {
        val command = mutableListOf(
            ffmpegPath,
            "-hide_banner",
            "-loglevel",
            "warning",
            "-nostdin",
            "-y",
            "-re",
        )

        if (startTimeSeconds > 0.0) {
            command += listOf("-ss", formatSeekTime(startTimeSeconds))
        }

        command += listOf(
            "-i",
            uri,
            "-map",
            "0:v:0",
            "-map",
            selectedAudioStreamIndex?.let { "0:$it" } ?: "0:a:0?",
            "-sn",
            "-dn",
        )

        if (selectedSubtitleStream != null) {
            command += listOf(
                "-vf",
                buildSubtitlesFilter(uri, selectedSubtitleStream.subtitleOrdinal),
            )
        }

        command += listOf(
            "-c:v",
            "h264_videotoolbox",
            "-b:v",
            "5000k",
            "-maxrate",
            "7000k",
            "-bufsize",
            "10000k",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "160k",
            "-ac",
            "2",
        )

        command += listOf(
            "-f",
            "hls",
            "-hls_time",
            "4",
            "-hls_list_size",
            "8",
            "-hls_delete_threshold",
            "4",
            "-hls_flags",
            "delete_segments+independent_segments",
            "-hls_segment_filename",
            segmentPattern,
            playlist.absolutePathString(),
        )

        return command
    }

    private fun formatSeekTime(seconds: Double): String =
        "%.3f".format(java.util.Locale.US, seconds.coerceAtLeast(0.0))

    private fun probeTrackInfo(uri: String): ProbeTrackInfo {
        val ffprobe = MacFfmpegLocator.findFfprobe(ffmpegPath) ?: return ProbeTrackInfo()
        val process =
            ProcessBuilder(
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "stream=index,codec_type,codec_name,channels,sample_rate,bit_rate:stream_tags=language,title:" +
                    "stream_disposition=default:format=duration",
                "-of",
                "flat",
                uri,
            ).redirectErrorStream(true)
                .start()

        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ProbeTrackInfo()
        }

        if (process.exitValue() != 0) return ProbeTrackInfo()

        return parseProbeTrackInfo(process.inputStream.bufferedReader().readText())
    }

    private fun parseProbeTrackInfo(output: String): ProbeTrackInfo {
        val streamValues = linkedMapOf<Int, MutableMap<String, String>>()
        var durationSeconds: Double? = null

        for (line in output.lineSequence()) {
            val key = line.substringBefore('=', missingDelimiterValue = "").trim()
            val value = cleanProbeValue(line.substringAfter('=', missingDelimiterValue = "").trim())
            if (key == "format.duration") {
                durationSeconds = value.toFinitePositiveDoubleOrNull()
                continue
            }

            val match = STREAM_FIELD_REGEX.matchEntire(key) ?: continue
            val streamOrdinal = match.groupValues[1].toIntOrNull() ?: continue
            val field = match.groupValues[2]
            streamValues.getOrPut(streamOrdinal) { linkedMapOf() }[field] = value
        }

        val streams =
            streamValues.values
                .mapNotNull(::probeStreamFromValues)
                .sortedBy { it.index }

        val audioStreams =
            streams
                .filter { it.codecType == "audio" }
                .mapIndexed { ordinal, stream -> stream.toAudioStream(ordinal) }

        val subtitleStreams =
            streams
                .filter { it.codecType == "subtitle" }
                .mapIndexedNotNull { subtitleOrdinal, stream ->
                    val format = subtitleFormatForCodec(stream.codecName) ?: return@mapIndexedNotNull null
                    FfmpegSubtitleStream(
                        streamIndex = stream.index,
                        subtitleOrdinal = subtitleOrdinal,
                        track =
                            SubtitleTrack(
                                id = "$MAC_FFMPEG_SUBTITLE_TRACK_ID_PREFIX${stream.index}",
                                label = stream.displayLabel("Subtitles"),
                                language = stream.language,
                                src = "",
                                format = format,
                                isEmbedded = true,
                            ),
                    )
                }

        return ProbeTrackInfo(
            durationSeconds = durationSeconds,
            audioStreams = audioStreams,
            subtitleStreams = subtitleStreams,
        )
    }

    private fun probeStreamFromValues(values: Map<String, String>): ProbeStream? {
        val index = values["index"]?.toIntOrNull() ?: return null
        return ProbeStream(
            index = index,
            codecType = values["codec_type"]?.lowercase(),
            codecName = values["codec_name"]?.lowercase(),
            channels = values["channels"]?.toIntOrNull(),
            sampleRate = values["sample_rate"]?.toIntOrNull(),
            bitRate = values["bit_rate"]?.toIntOrNull(),
            language = values["tags.language"].orEmpty(),
            title = values["tags.title"].orEmpty(),
            isDefault = values["disposition.default"] == "1",
        )
    }

    private fun ProbeStream.toAudioStream(ordinal: Int): FfmpegAudioStream =
        FfmpegAudioStream(
            streamIndex = index,
            track =
                AudioTrack(
                    id = "$MAC_FFMPEG_AUDIO_TRACK_ID_PREFIX$index",
                    label = displayLabel("Audio ${ordinal + 1}"),
                    language = language,
                    channels = channels,
                    sampleRate = sampleRate,
                    bitrate = bitRate,
                    isDefault = isDefault,
                ),
        )

    private fun ProbeStream.displayLabel(fallbackBase: String): String =
        title.takeIf { it.isNotBlank() }
            ?: buildString {
                append(fallbackBase)
                if (language.isNotBlank()) append(" ($language)")
                codecName?.takeIf { it.isNotBlank() }?.let { append(" / $it") }
            }

    private fun cleanProbeValue(value: String): String {
        if (value == "N/A") return ""
        return value
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun String.toFinitePositiveDoubleOrNull(): Double? =
        toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    private fun subtitleFormatForCodec(codecName: String?): SubtitleFormat? =
        when (codecName?.lowercase()) {
            "ass" -> SubtitleFormat.ASS
            "ssa" -> SubtitleFormat.SSA
            "subrip" -> SubtitleFormat.SRT
            "webvtt" -> SubtitleFormat.WEBVTT
            else -> null
        }

    private fun supportsSubtitlesFilter(): Boolean {
        hasSubtitlesFilter?.let { return it }

        val supported = supportsSubtitlesFilter(ffmpegPath)
        hasSubtitlesFilter = supported
        return supported
    }

    private fun buildSubtitlesFilter(
        uri: String,
        subtitleOrdinal: Int,
    ): String =
        "subtitles=filename='${escapeFilterValue(uri)}':si=$subtitleOrdinal"

    private fun escapeFilterValue(value: String): String =
        buildString {
            value.forEach { char ->
                when (char) {
                    '\\', '\'', ':', ',', '[', ']' -> append('\\').append(char)
                    else -> append(char)
                }
            }
        }

    private fun startLogReader(startedProcess: Process) {
        val reader =
            Thread {
                runCatching {
                    startedProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach(::appendLogLine)
                    }
                }
            }.apply {
                isDaemon = true
                name = "compose-media-player-ffmpeg-log"
                start()
            }
        logReader = reader
    }

    private fun appendLogLine(line: String) {
        synchronized(recentOutput) {
            if (recentOutput.length > MAX_LOG_CHARS) {
                recentOutput.delete(0, recentOutput.length - MAX_LOG_CHARS)
            }
            recentOutput.appendLine(line)
        }
    }

    private suspend fun waitForPlaylist(
        playlist: Path,
        startedProcess: Process,
    ) {
        val deadline = System.currentTimeMillis() + PLAYLIST_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (playlist.exists() && runCatching { Files.readString(playlist) }.getOrDefault("").contains("#EXTINF")) {
                return
            }

            if (!startedProcess.isAlive) {
                throw IllegalStateException("ffmpeg exited before producing a playable HLS playlist. ${lastLogMessage()}")
            }

            delay(250)
        }

        throw IllegalStateException("Timed out waiting for ffmpeg to produce a playable HLS playlist. ${lastLogMessage()}")
    }

    private fun lastLogMessage(): String =
        synchronized(recentOutput) {
            recentOutput.toString().trim().takeLast(MAX_ERROR_CHARS)
        }.takeIf { it.isNotBlank() }
            ?.let { "Recent ffmpeg output: $it" }
            ?: "No ffmpeg diagnostics were captured."

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        runCatching {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        private const val PLAYLIST_WAIT_TIMEOUT_MS = 30_000L
        private const val MAX_LOG_CHARS = 8_000
        private const val MAX_ERROR_CHARS = 1_500
        private val STREAM_FIELD_REGEX = Regex("""streams\.stream\.(\d+)\.(.+)""")

        fun supportsSubtitlesFilter(ffmpegPath: String): Boolean =
            runCatching {
                val process =
                    ProcessBuilder(
                        ffmpegPath,
                        "-hide_banner",
                        "-filters",
                    ).redirectErrorStream(true)
                        .start()

                if (!process.waitFor(4, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching false
                }

                process.inputStream
                    .bufferedReader()
                    .readText()
                    .lineSequence()
                    .any { line -> Regex("""\bsubtitles\b""").containsMatchIn(line) }
            }.getOrDefault(false)
    }
}

internal data class HlsFallbackSource(
    val playlistUrl: String,
    val durationSeconds: Double?,
    val playbackOffsetSeconds: Double = 0.0,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioStreamIndex: Int? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleStreamIndex: Int? = null,
)

private data class ProbeTrackInfo(
    val durationSeconds: Double? = null,
    val audioStreams: List<FfmpegAudioStream> = emptyList(),
    val subtitleStreams: List<FfmpegSubtitleStream> = emptyList(),
)

private data class ProbeStream(
    val index: Int,
    val codecType: String?,
    val codecName: String?,
    val channels: Int?,
    val sampleRate: Int?,
    val bitRate: Int?,
    val language: String,
    val title: String,
    val isDefault: Boolean,
)

private data class FfmpegAudioStream(
    val streamIndex: Int,
    val track: AudioTrack,
)

private data class FfmpegSubtitleStream(
    val streamIndex: Int,
    val subtitleOrdinal: Int,
    val track: SubtitleTrack,
)
