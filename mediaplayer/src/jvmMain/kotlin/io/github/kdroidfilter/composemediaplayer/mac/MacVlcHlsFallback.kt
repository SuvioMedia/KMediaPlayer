package io.github.kdroidfilter.composemediaplayer.mac

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.MAC_VLC_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.MAC_VLC_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds

internal object MacVlcLocator {
    fun findVlc(): String? {
        val configured =
            listOfNotNull(
                System.getProperty("composemediaplayer.macos.vlc"),
                System.getenv("COMPOSE_MEDIA_PLAYER_VLC"),
                System.getenv("VLC_PATH"),
            ).firstOrNull { it.isNotBlank() }

        if (configured != null && isExecutable(configured)) {
            return configured
        }

        val pathCandidates =
            System.getenv("PATH")
                ?.split(File.pathSeparator)
                ?.flatMap { directory ->
                    listOf(
                        File(directory, "vlc").absolutePath,
                        File(directory, "cvlc").absolutePath,
                    )
                }
                .orEmpty()

        val appCandidates =
            listOf(
                "/Applications/VLC.app/Contents/MacOS/VLC",
                "${System.getProperty("user.home")}/Applications/VLC.app/Contents/MacOS/VLC",
            )

        return (pathCandidates + appCandidates)
            .firstOrNull(::isExecutable)
    }

    fun findLibVlc(): MacLibVlcInstallation? {
        val configuredLib =
            System.getProperty("composemediaplayer.macos.libvlc")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_LIBVLC")
        val configuredPlugins =
            System.getProperty("composemediaplayer.macos.libvlc.plugins")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_LIBVLC_PLUGINS")

        if (!configuredLib.isNullOrBlank() && !configuredPlugins.isNullOrBlank()) {
            return MacLibVlcInstallation(configuredLib, configuredPlugins)
                .takeIf { isLoadableDylib(it.libVlcPath) && File(it.pluginPath).isDirectory }
        }

        val vlcExecutable = findVlc()
        val vlcMacOsDirectory = vlcExecutable?.let(::File)?.parentFile
        val appCandidate =
            vlcMacOsDirectory?.let { macOs ->
                MacLibVlcInstallation(
                    libVlcPath = macOs.resolve("lib/libvlc.5.dylib").absolutePath,
                    pluginPath = macOs.resolve("plugins").absolutePath,
                )
            }

        val candidates =
            listOfNotNull(appCandidate) +
                listOf(
                    MacLibVlcInstallation(
                        libVlcPath = "/Applications/VLC.app/Contents/MacOS/lib/libvlc.5.dylib",
                        pluginPath = "/Applications/VLC.app/Contents/MacOS/plugins",
                    ),
                    MacLibVlcInstallation(
                        libVlcPath = "${System.getProperty("user.home")}/Applications/VLC.app/Contents/MacOS/lib/libvlc.5.dylib",
                        pluginPath = "${System.getProperty("user.home")}/Applications/VLC.app/Contents/MacOS/plugins",
                    ),
                )

        return candidates.firstOrNull { isLoadableDylib(it.libVlcPath) && File(it.pluginPath).isDirectory }
    }

    fun currentProcessArchitecture(): String? =
        when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x86_64"
            else -> null
        }

    private fun isExecutable(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile && file.canExecute()
    }

    private fun isLoadableDylib(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile && hasCompatibleMachOArchitecture(file)
    }

    private fun hasCompatibleMachOArchitecture(file: File): Boolean {
        val processArchitecture = currentProcessArchitecture() ?: return true
        val description =
            runCatching {
                ProcessBuilder("/usr/bin/file", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .let { process ->
                        val output = process.inputStream.bufferedReader().readText()
                        if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
                    }
            }.getOrNull() ?: return true

        return when (processArchitecture) {
            "arm64" -> description.contains("arm64") || description.contains("arm64e")
            "x86_64" -> description.contains("x86_64")
            else -> true
        }
    }
}

internal data class MacLibVlcInstallation(
    val libVlcPath: String,
    val pluginPath: String,
)

internal class MacVlcHlsFallback(
    private val vlcPath: String,
) : Closeable {
    private var process: Process? = null
    private var outputDirectory: Path? = null
    private var httpServer: HttpServer? = null
    private var logReader: Thread? = null
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
            val effectiveStartTimeSeconds =
                if (selectedSubtitleStream == null) {
                    startTimeSeconds.coerceAtLeast(0.0)
                } else {
                    0.0
                }

            val tempDirectory = Files.createTempDirectory("compose-media-player-macos-vlc-hls-")
            outputDirectory = tempDirectory
            val playlist = tempDirectory.resolve("stream.m3u8")
            val server = startHttpServer(tempDirectory)
            httpServer = server
            val playlistUrl = "http://127.0.0.1:${server.address.port}/stream.m3u8"
            val segmentPattern = tempDirectory.resolve("segment_########.ts").absolutePathString()

            val command =
                buildVlcCommand(
                    uri = uri,
                    segmentPattern = segmentPattern,
                    playlist = playlist,
                    selectedAudioStream = selectedAudioStream,
                    selectedSubtitleStream = selectedSubtitleStream,
                    startTimeSeconds = effectiveStartTimeSeconds,
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
                    playbackOffsetSeconds = effectiveStartTimeSeconds,
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
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun buildVlcCommand(
        uri: String,
        segmentPattern: String,
        playlist: Path,
        selectedAudioStream: VlcAudioStream?,
        selectedSubtitleStream: VlcSubtitleStream?,
        startTimeSeconds: Double,
    ): List<String> {
        val transcodeOptions =
            mutableListOf(
                "vcodec=h264",
                "vb=5000",
                "acodec=mp4a",
                "ab=160",
                "channels=2",
            )
        if (selectedSubtitleStream != null) {
            transcodeOptions += "soverlay"
        }

        val command =
            mutableListOf(
                vlcPath,
                "-I",
                "dummy",
                "--no-video-title-show",
                "--play-and-exit",
            )

        selectedAudioStream?.audioTrackNumber?.let { command += "--audio-track=$it" }
        if (selectedSubtitleStream != null) {
            command += "--sub-track=${selectedSubtitleStream.subtitleTrackNumber}"
            command += "--spu"
        } else {
            command += "--no-spu"
        }
        if (startTimeSeconds > 0.0) {
            command += "--start-time=${formatSeekTime(startTimeSeconds)}"
        }

        command += uri
        command += "--sout"
        command +=
            "#transcode{${transcodeOptions.joinToString(",")}}:" +
                "std{access=livehttp{seglen=4,delsegs=true,numsegs=8,index=${playlist.absolutePathString()}," +
                "index-url=${SEGMENT_FILE_PATTERN}},mux=ts{use-key-frames},dst=$segmentPattern}"
        command += "vlc://quit"

        return command
    }

    private fun formatSeekTime(seconds: Double): String =
        "%.3f".format(java.util.Locale.US, seconds.coerceAtLeast(0.0))

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

    private fun probeTrackInfo(uri: String): VlcProbeTrackInfo {
        val ffprobe = MacFfmpegLocator.findFfprobe() ?: return VlcProbeTrackInfo()
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
            return VlcProbeTrackInfo()
        }

        if (process.exitValue() != 0) return VlcProbeTrackInfo()

        return parseProbeTrackInfo(process.inputStream.bufferedReader().readText())
    }

    private fun parseProbeTrackInfo(output: String): VlcProbeTrackInfo {
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
                .mapIndexed { audioTrackNumber, stream -> stream.toAudioStream(audioTrackNumber) }

        val subtitleStreams =
            streams
                .filter { it.codecType == "subtitle" }
                .mapIndexedNotNull { subtitleTrackNumber, stream ->
                    val format = subtitleFormatForCodec(stream.codecName) ?: return@mapIndexedNotNull null
                    VlcSubtitleStream(
                        streamIndex = stream.index,
                        subtitleTrackNumber = subtitleTrackNumber,
                        track =
                            SubtitleTrack(
                                id = "$MAC_VLC_SUBTITLE_TRACK_ID_PREFIX${stream.index}",
                                label = stream.displayLabel("Subtitles"),
                                language = stream.language,
                                src = "",
                                format = format,
                                isEmbedded = true,
                            ),
                    )
                }

        return VlcProbeTrackInfo(
            durationSeconds = durationSeconds,
            audioStreams = audioStreams,
            subtitleStreams = subtitleStreams,
        )
    }

    private fun probeStreamFromValues(values: Map<String, String>): VlcProbeStream? {
        val index = values["index"]?.toIntOrNull() ?: return null
        return VlcProbeStream(
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

    private fun VlcProbeStream.toAudioStream(audioTrackNumber: Int): VlcAudioStream =
        VlcAudioStream(
            streamIndex = index,
            audioTrackNumber = audioTrackNumber,
            track =
                AudioTrack(
                    id = "$MAC_VLC_AUDIO_TRACK_ID_PREFIX$index",
                    label = displayLabel("Audio ${audioTrackNumber + 1}"),
                    language = language,
                    channels = channels,
                    sampleRate = sampleRate,
                    bitrate = bitRate,
                    isDefault = isDefault,
                ),
        )

    private fun VlcProbeStream.displayLabel(fallbackBase: String): String =
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
                name = "compose-media-player-vlc-log"
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
                throw IllegalStateException("VLC exited before producing a playable HLS playlist. ${lastLogMessage()}")
            }

            delay(250.milliseconds)
        }

        throw IllegalStateException("Timed out waiting for VLC to produce a playable HLS playlist. ${lastLogMessage()}")
    }

    private fun lastLogMessage(): String =
        synchronized(recentOutput) {
            recentOutput.toString().trim().takeLast(MAX_ERROR_CHARS)
        }.takeIf { it.isNotBlank() }
            ?.let { "Recent VLC output: $it" }
            ?: "No VLC diagnostics were captured."

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
        private const val SEGMENT_FILE_PATTERN = "segment_########.ts"
        private val STREAM_FIELD_REGEX = Regex("""streams\.stream\.(\d+)\.(.+)""")
    }
}

private data class VlcProbeTrackInfo(
    val durationSeconds: Double? = null,
    val audioStreams: List<VlcAudioStream> = emptyList(),
    val subtitleStreams: List<VlcSubtitleStream> = emptyList(),
)

private data class VlcProbeStream(
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

private data class VlcAudioStream(
    val streamIndex: Int,
    val audioTrackNumber: Int,
    val track: AudioTrack,
)

private data class VlcSubtitleStream(
    val streamIndex: Int,
    val subtitleTrackNumber: Int,
    val track: SubtitleTrack,
)
