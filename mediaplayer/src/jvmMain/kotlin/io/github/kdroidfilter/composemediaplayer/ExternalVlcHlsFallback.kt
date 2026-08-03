@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")

package io.github.kdroidfilter.composemediaplayer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds

internal object ExternalVlcLocator {
    fun findVlc(): String? {
        val configured =
            listOfNotNull(
                System.getProperty("composemediaplayer.vlc"),
                System.getProperty("composemediaplayer.macos.vlc"),
                System.getenv("COMPOSE_MEDIA_PLAYER_VLC"),
                System.getenv("VLC_PATH"),
            ).firstOrNull { it.isNotBlank() }

        if (configured != null && isExecutable(configured)) {
            return configured
        }

        val pathCandidates =
            System
                .getenv("PATH")
                ?.split(File.pathSeparator)
                ?.flatMap { directory ->
                    vlcExecutableNames().map { executable -> File(directory, executable).absolutePath }
                }.orEmpty()

        val appCandidates =
            listOf(
                "/Applications/VLC.app/Contents/MacOS/VLC",
                "${System.getProperty("user.home")}/Applications/VLC.app/Contents/MacOS/VLC",
                "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe",
            )

        return (pathCandidates + appCandidates)
            .firstOrNull(::isExecutable)
    }

    fun findLibVlc(): JvmLibVlcInstallation? {
        val configuredLib =
            System.getProperty("composemediaplayer.libvlc")
                ?: System.getProperty("composemediaplayer.macos.libvlc")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_LIBVLC")
        val configuredPlugins =
            System.getProperty("composemediaplayer.libvlc.plugins")
                ?: System.getProperty("composemediaplayer.macos.libvlc.plugins")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_LIBVLC_PLUGINS")

        if (!configuredLib.isNullOrBlank() && !configuredPlugins.isNullOrBlank()) {
            return JvmLibVlcInstallation(configuredLib, configuredPlugins)
                .takeIf { isLoadableLibrary(it.libVlcPath) && File(it.pluginPath).isDirectory }
        }

        val vlcExecutable = findVlc()
        val vlcDirectory = vlcExecutable?.let(::File)?.parentFile

        val candidates =
            libVlcCandidatesFromVlcDirectory(vlcDirectory) +
                listOf(
                    JvmLibVlcInstallation(
                        libVlcPath = "/Applications/VLC.app/Contents/MacOS/lib/libvlc.5.dylib",
                        pluginPath = "/Applications/VLC.app/Contents/MacOS/plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "${System.getProperty(
                            "user.home",
                        )}/Applications/VLC.app/Contents/MacOS/lib/libvlc.5.dylib",
                        pluginPath = "${System.getProperty("user.home")}/Applications/VLC.app/Contents/MacOS/plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "C:\\Program Files\\VideoLAN\\VLC\\libvlc.dll",
                        pluginPath = "C:\\Program Files\\VideoLAN\\VLC\\plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "C:\\Program Files (x86)\\VideoLAN\\VLC\\libvlc.dll",
                        pluginPath = "C:\\Program Files (x86)\\VideoLAN\\VLC\\plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "/usr/lib/x86_64-linux-gnu/libvlc.so.5",
                        pluginPath = "/usr/lib/x86_64-linux-gnu/vlc/plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "/usr/lib/aarch64-linux-gnu/libvlc.so.5",
                        pluginPath = "/usr/lib/aarch64-linux-gnu/vlc/plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "/usr/lib64/libvlc.so.5",
                        pluginPath = "/usr/lib64/vlc/plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "/usr/lib/libvlc.so.5",
                        pluginPath = "/usr/lib/vlc/plugins",
                    ),
                    JvmLibVlcInstallation(
                        libVlcPath = "/usr/local/lib/libvlc.so.5",
                        pluginPath = "/usr/local/lib/vlc/plugins",
                    ),
                )

        return candidates.firstOrNull { isLoadableLibrary(it.libVlcPath) && File(it.pluginPath).isDirectory }
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

    private fun vlcExecutableNames(): List<String> =
        if (CurrentPlatform.os == CurrentPlatform.OS.WINDOWS) {
            listOf("vlc.exe", "cvlc.exe", "vlc", "cvlc")
        } else {
            listOf("vlc", "cvlc")
        }

    private fun libVlcCandidatesFromVlcDirectory(directory: File?): List<JvmLibVlcInstallation> {
        if (directory == null) return emptyList()
        return when (CurrentPlatform.os) {
            CurrentPlatform.OS.WINDOWS ->
                listOf(
                    JvmLibVlcInstallation(
                        libVlcPath = directory.resolve("libvlc.dll").absolutePath,
                        pluginPath = directory.resolve("plugins").absolutePath,
                    ),
                )
            CurrentPlatform.OS.MAC ->
                listOf(
                    JvmLibVlcInstallation(
                        libVlcPath = directory.resolve("lib/libvlc.5.dylib").absolutePath,
                        pluginPath = directory.resolve("plugins").absolutePath,
                    ),
                )
            CurrentPlatform.OS.LINUX -> emptyList()
        }
    }

    private fun isLoadableLibrary(path: String): Boolean {
        val file = File(path)
        return file.exists() &&
            file.isFile &&
            (CurrentPlatform.os != CurrentPlatform.OS.MAC || hasCompatibleMachOArchitecture(file))
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

internal data class JvmLibVlcInstallation(
    val libVlcPath: String,
    val pluginPath: String,
)

internal class ExternalVlcHlsFallback(
    private val vlcPath: String,
) : Closeable {
    @Volatile
    private var process: Process? = null
    private var outputDirectory: Path? = null
    private var httpServer: HttpServer? = null
    private var httpExecutor: ExecutorService? = null
    private var logReader: Thread? = null
    private var segmentCleaner: Thread? = null
    private val recentOutput = StringBuilder()

    suspend fun start(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
        selectedAudioStreamIndex: Int? = null,
        selectedSubtitleStreamIndex: Int? = null,
        startTimeSeconds: Double = 0.0,
    ): HlsFallbackSource =
        withContext(Dispatchers.IO) {
            close()

            val headers = requestHeaders.sanitizedRequestHeaders()
            val trackInfo = probeTrackInfo(uri, headers)
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

            val tempDirectory = Files.createTempDirectory("compose-media-player-vlc-hls-")
            outputDirectory = tempDirectory
            val playlist = tempDirectory.resolve("stream.m3u8")
            val server = startHttpServer(tempDirectory)
            httpServer = server
            val playlistUrl = "http://127.0.0.1:${server.address.port}/$MASTER_PLAYLIST_FILE"
            val segmentPattern = tempDirectory.resolve("segment_########.ts").absolutePathString()

            val command =
                buildVlcCommand(
                    uri = uri,
                    requestHeaders = headers,
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
            startSegmentCleaner(tempDirectory, startedProcess)

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
        httpExecutor?.shutdownNow()
        httpExecutor = null

        val currentCleaner = segmentCleaner
        segmentCleaner = null
        currentCleaner?.interrupt()
        if (currentCleaner != null && currentCleaner !== Thread.currentThread()) {
            runCatching { currentCleaner.join(SEGMENT_CLEANER_JOIN_TIMEOUT_MS) }
        }

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
        requestHeaders: Map<String, String>,
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
                "--sout-x264-keyint=$HLS_X264_KEYFRAME_INTERVAL",
                "--sout-x264-min-keyint=$HLS_X264_MINIMUM_KEYFRAME_INTERVAL",
                "--sout-x264-scenecut=0",
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
        requestHeaders.forEach { (name, value) ->
            when {
                name.equals("User-Agent", ignoreCase = true) -> command += "--http-user-agent=$value"
                name.equals("Referer", ignoreCase = true) || name.equals("Referrer", ignoreCase = true) ->
                    command += "--http-referrer=$value"
                name.equals("Cookie", ignoreCase = true) -> command += "--http-cookie=$value"
            }
        }

        command += uri
        command += "--sout"
        command +=
            "#transcode{${transcodeOptions.joinToString(",")}}:" +
            "std{access=livehttp{seglen=$HLS_SEGMENT_LENGTH_SECONDS,no-delsegs,ratecontrol," +
            "numsegs=$HLS_PLAYLIST_SEGMENTS,index=${playlist.absolutePathString()}," +
            "index-url=${SEGMENT_FILE_PATTERN}},mux=ts{use-key-frames},dst=$segmentPattern}"
        command += "vlc://quit"

        return command
    }

    private fun formatSeekTime(seconds: Double): String = "%.3f".format(java.util.Locale.US, seconds.coerceAtLeast(0.0))

    private fun startHttpServer(root: Path): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor =
            Executors.newFixedThreadPool(HLS_HTTP_THREADS) { task ->
                Thread(task, "compose-media-player-vlc-hls-http").apply { isDaemon = true }
            }
        server.executor = executor
        httpExecutor = executor
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
            val rawPath =
                exchange.requestURI.path
                    .removePrefix("/")
                    .ifBlank { MASTER_PLAYLIST_FILE }
            val decodedPath = URLDecoder.decode(rawPath, Charsets.UTF_8.name())
            if (decodedPath == MASTER_PLAYLIST_FILE) {
                val payload = MASTER_PLAYLIST.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.responseHeaders.add("Cache-Control", "no-cache")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
                return
            }
            val file = root.resolve(decodedPath).normalize()
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1)
                return
            }

            exchange.responseHeaders.add("Content-Type", contentType(file))
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            if (file.name.endsWith(".m3u8", ignoreCase = true)) {
                val playlist = playablePlaylistContent(file)
                if (playlist == null) {
                    exchange.sendResponseHeaders(503, -1)
                    return
                }
                val payload = playlist.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
                return
            }

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

    private fun probeTrackInfo(
        uri: String,
        requestHeaders: Map<String, String>,
    ): VlcProbeTrackInfo {
        val probe = JvmLibVlcMediaProbe.probe(uri, requestHeaders)
        return VlcProbeTrackInfo(
            durationSeconds = probe.durationSeconds,
            audioStreams =
                probe.audioStreams.map { stream ->
                    VlcAudioStream(
                        streamIndex = stream.streamIndex,
                        audioTrackNumber = stream.ordinal,
                        track =
                            stream.track.copy(
                                id = "$EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX${stream.streamIndex}",
                            ),
                    )
                },
            subtitleStreams =
                probe.subtitleStreams.map { stream ->
                    VlcSubtitleStream(
                        streamIndex = stream.streamIndex,
                        subtitleTrackNumber = stream.ordinal,
                        track =
                            stream.track.copy(
                                id = "$EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX${stream.streamIndex}",
                            ),
                    )
                },
        )
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

    private fun startSegmentCleaner(
        root: Path,
        startedProcess: Process,
    ) {
        val cleaner =
            Thread {
                while (!Thread.currentThread().isInterrupted && process === startedProcess) {
                    pruneOldSegments(root)
                    if (!startedProcess.isAlive) break
                    try {
                        Thread.sleep(SEGMENT_CLEANER_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                pruneOldSegments(root)
            }.apply {
                isDaemon = true
                name = "compose-media-player-vlc-segment-cleaner"
            }
        segmentCleaner = cleaner
        cleaner.start()
    }

    private fun pruneOldSegments(root: Path) {
        runCatching {
            val segments =
                Files.newDirectoryStream(root, SEGMENT_FILE_GLOB).use { paths ->
                    paths
                        .mapNotNull { path ->
                            val sequence =
                                SEGMENT_FILE_NAME
                                    .matchEntire(path.fileName.toString())
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toLongOrNull()
                            sequence?.let { it to path }
                        }.sortedBy { it.first }
                }
            segments
                .dropLast(MAX_RETAINED_SEGMENTS.coerceAtMost(segments.size))
                .forEach { (_, path) -> Files.deleteIfExists(path) }
        }
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
            if (playlist.exists() && playlistHasPlayableSegment(playlist)) {
                return
            }

            if (!startedProcess.isAlive) {
                throw IllegalStateException("VLC exited before producing a playable HLS playlist. ${lastLogMessage()}")
            }

            delay(250.milliseconds)
        }

        throw IllegalStateException("Timed out waiting for VLC to produce a playable HLS playlist. ${lastLogMessage()}")
    }

    private fun playlistHasPlayableSegment(playlist: Path): Boolean = playablePlaylistContent(playlist) != null

    /**
     * VLC's precise seek can emit one or more audio-only pre-roll segments before the first
     * decoded video keyframe, especially for ASF/WMV. Advertising that pre-roll makes
     * AVFoundation lock the replacement item to an audio-only rendition. Start the exposed HLS
     * window at the first segment that contains x264 parameter sets and advance the media sequence
     * to keep the playlist valid.
     */
    internal fun playablePlaylistContent(playlist: Path): String? {
        val content = runCatching { Files.readString(playlist) }.getOrNull() ?: return null
        if (!content.contains("#EXTINF")) return null
        val lines = content.lines()
        val segments = playlistSegments(playlist.parent, lines)
        val firstPlayableIndex = segments.indexOfFirst { it.path.containsH264ParameterSets() }
        if (firstPlayableIndex < 0) return null
        val playableSegmentCount = segments.size - firstPlayableIndex
        val streamComplete = lines.any { it.trim() == HLS_END_LIST_TAG }
        if (playableSegmentCount < MINIMUM_BUFFERED_HLS_SEGMENTS && !streamComplete) return null
        if (firstPlayableIndex == 0) return content

        val firstSegmentLine = segments.first().infoLineIndex
        val firstPlayableLine = segments[firstPlayableIndex].infoLineIndex
        val header =
            lines
                .subList(0, firstSegmentLine)
                .map { line -> advanceMediaSequence(line, firstPlayableIndex) }
        val body = lines.subList(firstPlayableLine, lines.size)
        return (header + body).joinToString(separator = "\n", postfix = "\n")
    }

    private fun playlistSegments(
        root: Path,
        lines: List<String>,
    ): List<HlsPlaylistSegment> {
        val segments = mutableListOf<HlsPlaylistSegment>()
        var infoLineIndex: Int? = null
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF:") -> infoLineIndex = index
                line.isNotEmpty() && !line.startsWith('#') -> {
                    val pendingInfoLine = infoLineIndex
                    infoLineIndex = null
                    if (pendingInfoLine != null) {
                        val path = root.resolve(line).normalize()
                        if (path.startsWith(root) && Files.isRegularFile(path)) {
                            segments += HlsPlaylistSegment(pendingInfoLine, path)
                        }
                    }
                }
            }
        }
        return segments
    }

    private fun advanceMediaSequence(
        line: String,
        discardedSegments: Int,
    ): String {
        if (!line.startsWith(HLS_MEDIA_SEQUENCE_PREFIX)) return line
        val sequence = line.substringAfter(HLS_MEDIA_SEQUENCE_PREFIX).trim().toLongOrNull() ?: return line
        return "$HLS_MEDIA_SEQUENCE_PREFIX${sequence + discardedSegments}"
    }

    private fun Path.containsH264ParameterSets(): Boolean {
        if (!Files.isRegularFile(this)) return false
        return runCatching {
            Files.newInputStream(this).use { input ->
                val buffer = ByteArray(HLS_VIDEO_PROBE_BUFFER_BYTES)
                var remaining = MAX_HLS_VIDEO_PROBE_BYTES
                var zeroBytes = 0
                var nextByteIsNalHeader = false
                var foundSequenceParameterSet = false
                var foundPictureParameterSet = false
                while (remaining > 0 && !(foundSequenceParameterSet && foundPictureParameterSet)) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    remaining -= read
                    for (index in 0 until read) {
                        val value = buffer[index].toInt() and 0xff
                        if (nextByteIsNalHeader) {
                            when (value and H264_NAL_UNIT_TYPE_MASK) {
                                H264_SEQUENCE_PARAMETER_SET -> foundSequenceParameterSet = true
                                H264_PICTURE_PARAMETER_SET -> foundPictureParameterSet = true
                            }
                            nextByteIsNalHeader = false
                        }
                        if (value == 0) {
                            zeroBytes++
                        } else {
                            if (value == 1 && zeroBytes >= H264_START_CODE_ZERO_BYTES) {
                                nextByteIsNalHeader = true
                            }
                            zeroBytes = 0
                        }
                    }
                }
                foundSequenceParameterSet && foundPictureParameterSet
            }
        }.getOrDefault(false)
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
                stream
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        private const val PLAYLIST_WAIT_TIMEOUT_MS = 30_000L
        private const val MAX_LOG_CHARS = 8_000
        private const val MAX_ERROR_CHARS = 1_500
        private const val HLS_SEGMENT_LENGTH_SECONDS = 2
        private const val HLS_PLAYLIST_SEGMENTS = 8
        private const val MINIMUM_BUFFERED_HLS_SEGMENTS = 3
        private const val HLS_SEGMENT_SAFETY_MARGIN = 4
        private const val MAX_RETAINED_SEGMENTS = HLS_PLAYLIST_SEGMENTS + HLS_SEGMENT_SAFETY_MARGIN
        private const val HLS_X264_KEYFRAME_INTERVAL = 30
        private const val HLS_X264_MINIMUM_KEYFRAME_INTERVAL = 15
        private const val SEGMENT_CLEANER_INTERVAL_MS = 1_000L
        private const val SEGMENT_CLEANER_JOIN_TIMEOUT_MS = 1_000L
        private const val HLS_HTTP_THREADS = 4
        private const val HLS_VIDEO_PROBE_BUFFER_BYTES = 16 * 1024
        private const val MAX_HLS_VIDEO_PROBE_BYTES = 1024 * 1024
        private const val H264_START_CODE_ZERO_BYTES = 2
        private const val H264_NAL_UNIT_TYPE_MASK = 0x1f
        private const val H264_SEQUENCE_PARAMETER_SET = 7
        private const val H264_PICTURE_PARAMETER_SET = 8
        private const val HLS_MEDIA_SEQUENCE_PREFIX = "#EXT-X-MEDIA-SEQUENCE:"
        private const val HLS_END_LIST_TAG = "#EXT-X-ENDLIST"
        private const val MASTER_PLAYLIST_FILE = "master.m3u8"
        private const val SEGMENT_FILE_PATTERN = "segment_########.ts"
        private const val SEGMENT_FILE_GLOB = "segment_*.ts"
        private val MASTER_PLAYLIST =
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-STREAM-INF:BANDWIDTH=5500000
            stream.m3u8
            """.trimIndent() + "\n"
        private val SEGMENT_FILE_NAME = Regex("segment_(\\d+)\\.ts")
    }
}

private data class HlsPlaylistSegment(
    val infoLineIndex: Int,
    val path: Path,
)

private data class VlcProbeTrackInfo(
    val durationSeconds: Double? = null,
    val audioStreams: List<VlcAudioStream> = emptyList(),
    val subtitleStreams: List<VlcSubtitleStream> = emptyList(),
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
