@file:Suppress("TooGenericExceptionCaught")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class JvmFfmpegDolbyVisionRequest(
    val input: String,
    val container: DolbyVisionContainer,
    val ffmpegExecutable: String = "ffmpeg",
    val requestHeaders: Map<String, String> = emptyMap(),
    val enhancementLayer: DolbyVisionEnhancementLayer,
    val targetFragmentDurationUs: Long = DEFAULT_FFMPEG_FRAGMENT_DURATION_US,
    val maximumInitializationBytes: Int = DEFAULT_FFMPEG_INITIALIZATION_BYTES,
    val maximumFragmentBytes: Int = DEFAULT_FFMPEG_FRAGMENT_BYTES,
    val maximumBufferedFragments: Int = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_FRAGMENTS,
    val maximumBufferedBytes: Long = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_BYTES,
) {
    init {
        require(input.isNotBlank()) { "input must not be blank." }
        require(ffmpegExecutable.isNotBlank()) { "ffmpegExecutable must not be blank." }
        require(container == DolbyVisionContainer.MATROSKA || container == DolbyVisionContainer.MP4) {
            "The JVM FFmpeg adapter accepts only Matroska or flat MP4 VOD input."
        }
        require(targetFragmentDurationUs > 0) { "targetFragmentDurationUs must be positive." }
        require(maximumInitializationBytes > 0) { "maximumInitializationBytes must be positive." }
        require(maximumFragmentBytes > 0) { "maximumFragmentBytes must be positive." }
        requestHeaders.forEach { (name, value) ->
            require(name.isNotBlank() && name.none { it == '\r' || it == '\n' || it == ':' }) {
                "Invalid HTTP header name."
            }
            require(value.none { it == '\r' || it == '\n' }) { "Invalid HTTP header value." }
        }
    }
}

sealed interface JvmFfmpegDolbyVisionOpenResult {
    data class Success(
        val session: JvmFfmpegDolbyVisionSession,
    ) : JvmFfmpegDolbyVisionOpenResult

    data class Failure(
        val message: String,
    ) : JvmFfmpegDolbyVisionOpenResult
}

sealed interface JvmFfmpegDolbyVisionFragmentResult {
    data class Success(
        val payload: ByteArray,
        val sequence: Long,
        val startPresentationTimeUs: Long,
        val endPresentationTimeUs: Long,
    ) : JvmFfmpegDolbyVisionFragmentResult

    data object EndOfStream : JvmFfmpegDolbyVisionFragmentResult

    data class Failure(
        val message: String,
    ) : JvmFfmpegDolbyVisionFragmentResult
}

/** Availability is an executable check only; it is not a decoder/HDR capability claim. */
object JvmFfmpegDolbyVisionAdapter {
    fun isAvailable(executable: String = "ffmpeg"): Boolean =
        runCatching {
            val process = ProcessBuilder(executable, "-version").redirectErrorStream(true).start()
            process.inputStream.use { it.readNBytes(MAXIMUM_VERSION_OUTPUT_BYTES) }
            process.waitFor(FFMPEG_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)

    suspend fun open(
        request: JvmFfmpegDolbyVisionRequest,
        converter: DolbyVisionRpuConverter,
    ): JvmFfmpegDolbyVisionOpenResult = openWithFactory(request, converter, SystemFfmpegProcessFactory)

    internal suspend fun openWithFactory(
        request: JvmFfmpegDolbyVisionRequest,
        converter: DolbyVisionRpuConverter,
        processFactory: FfmpegProcessFactory,
    ): JvmFfmpegDolbyVisionOpenResult =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    processFactory.start(request, seekTimeUs = 0)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withContext ffmpegOpenFailure(
                        "Unable to start FFmpeg: ${error.message ?: error::class.simpleName}.",
                    )
                }
            when (val opened = openProcessStream(request, converter, process, processFactory)) {
                is JvmFfmpegDolbyVisionOpenResult.Success -> opened
                is JvmFfmpegDolbyVisionOpenResult.Failure -> {
                    process.close()
                    opened
                }
            }
        }
}

class JvmFfmpegDolbyVisionSession internal constructor(
    private val request: JvmFfmpegDolbyVisionRequest,
    private val converter: DolbyVisionRpuConverter,
    private val processFactory: FfmpegProcessFactory,
    private var process: FfmpegProcess,
    private var boxReader: StreamingIsoBmffReader,
    private var configuration: CmafDolbyVisionTrackConfiguration,
) : Closeable {
    val initializationSegment: ByteArray
        get() = configuration.rewrittenInitializationSegment.copyOf()

    private val mutex = Mutex()
    private var demuxer = CmafDolbyVisionFragmentAdapter(configuration, request.maximumFragmentBytes)
    private var bridge = newBridge()
    private var closed = false

    suspend fun nextFragment(): JvmFfmpegDolbyVisionFragmentResult =
        mutex.withLock {
            if (closed) return@withLock JvmFfmpegDolbyVisionFragmentResult.Failure("The FFmpeg session is closed.")
            val payload =
                try {
                    boxReader.readMediaFragment()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withLock processFailure("Unable to read fragmented MP4", error)
                }
            if (payload == null) {
                val exitCode = process.awaitExit()
                return@withLock if (exitCode == 0) {
                    JvmFfmpegDolbyVisionFragmentResult.EndOfStream
                } else {
                    JvmFfmpegDolbyVisionFragmentResult.Failure(process.failureMessage("FFmpeg remux failed"))
                }
            }
            val source =
                when (val result = demuxer.demux(payload)) {
                    is CmafDolbyVisionDemuxResult.Success -> result.fragment
                    is CmafDolbyVisionDemuxResult.Failure ->
                        return@withLock JvmFfmpegDolbyVisionFragmentResult.Failure(result.message)
                }
            when (val converted = bridge.convert(source)) {
                is DolbyVisionFragmentConversionResult.Success ->
                    JvmFfmpegDolbyVisionFragmentResult.Success(
                        payload = converted.value.fragment.payload,
                        sequence = source.sequence,
                        startPresentationTimeUs = source.startPresentationTimeUs,
                        endPresentationTimeUs = source.endPresentationTimeUs,
                    )
                is DolbyVisionFragmentConversionResult.Failure ->
                    JvmFfmpegDolbyVisionFragmentResult.Failure(converted.message)
            }
        }

    /** Restarts FFmpeg input at the preceding demuxer keyframe and resets all timestamp/RPU association state. */
    suspend fun seekTo(targetPresentationTimeUs: Long): JvmFfmpegDolbyVisionOpenResult =
        mutex.withLock {
            require(targetPresentationTimeUs >= 0) { "targetPresentationTimeUs must be non-negative." }
            if (closed) return@withLock ffmpegOpenFailure("The FFmpeg session is closed.")
            val replacement =
                try {
                    withContext(Dispatchers.IO) { processFactory.start(request, targetPresentationTimeUs) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withLock ffmpegOpenFailure(
                        "Unable to restart FFmpeg for seek: ${error.message ?: error::class.simpleName}.",
                    )
                }
            val prefix =
                try {
                    StreamingIsoBmffReader(
                        replacement.output,
                        request.maximumInitializationBytes,
                        request.maximumFragmentBytes,
                    ).readInitialization()
                } catch (error: Throwable) {
                    replacement.close()
                    return@withLock ffmpegOpenFailure("Invalid FFmpeg output after seek: ${error.message}.")
                }
            val prepared =
                when (val result = CmafDolbyVisionInitializationSegment.prepareProfile81(prefix.initialization)) {
                    is CmafDolbyVisionInitializationResult.Success -> result.configuration
                    is CmafDolbyVisionInitializationResult.Failure -> {
                        replacement.close()
                        return@withLock ffmpegOpenFailure(result.message)
                    }
                }
            if (prepared.trackId != configuration.trackId || prepared.timescale != configuration.timescale) {
                replacement.close()
                return@withLock ffmpegOpenFailure("FFmpeg changed the Dolby Vision track identity during seek.")
            }
            process.close()
            process = replacement
            boxReader = prefix.reader
            configuration = prepared
            demuxer = CmafDolbyVisionFragmentAdapter(configuration, request.maximumFragmentBytes)
            bridge = newBridge()
            JvmFfmpegDolbyVisionOpenResult.Success(this@JvmFfmpegDolbyVisionSession)
        }

    override fun close() {
        if (closed) return
        closed = true
        process.close()
    }

    private fun newBridge() =
        DolbyVisionStreamingBridge(
            request =
                DolbyVisionConversionRequest(
                    container = request.container,
                    profile = configuration.sourceProfile,
                    hasRpu = true,
                    enhancementLayer = request.enhancementLayer,
                    maximumBufferedFragments = request.maximumBufferedFragments,
                    maximumBufferedBytes = request.maximumBufferedBytes,
                ),
            converter = converter,
            remuxer = CmafDolbyVisionFragmentRemuxer(configuration, request.maximumFragmentBytes),
        )

    private fun processFailure(
        prefix: String,
        error: Throwable,
    ) = JvmFfmpegDolbyVisionFragmentResult.Failure(
        process.failureMessage("$prefix: ${error.message ?: error::class.simpleName}"),
    )
}

internal interface FfmpegProcessFactory {
    fun start(
        request: JvmFfmpegDolbyVisionRequest,
        seekTimeUs: Long,
    ): FfmpegProcess
}

internal interface FfmpegProcess : Closeable {
    val output: InputStream

    fun awaitExit(): Int

    fun failureMessage(prefix: String): String
}

private object SystemFfmpegProcessFactory : FfmpegProcessFactory {
    override fun start(
        request: JvmFfmpegDolbyVisionRequest,
        seekTimeUs: Long,
    ): FfmpegProcess {
        val command = mutableListOf(request.ffmpegExecutable, "-hide_banner", "-loglevel", "error", "-nostdin")
        if (seekTimeUs > 0) {
            command += listOf("-ss", formatFfmpegSeconds(seekTimeUs))
        }
        if (request.requestHeaders.isNotEmpty()) {
            command +=
                listOf(
                    "-headers",
                    request.requestHeaders.entries.joinToString(
                        separator = "\r\n",
                        postfix = "\r\n",
                    ) { (name, value) -> "$name: $value" },
                )
        }
        command +=
            listOf(
                "-i",
                request.input,
                "-map",
                "0:v:0",
                "-map",
                "0:a?",
                "-sn",
                "-dn",
                "-map_metadata",
                "0",
                "-c",
                "copy",
                "-copyts",
                "-start_at_zero",
                "-avoid_negative_ts",
                "disabled",
                "-movflags",
                "+frag_keyframe+empty_moov+default_base_moof+omit_tfhd_offset",
                "-frag_duration",
                request.targetFragmentDurationUs.toString(),
                "-f",
                "mp4",
                "pipe:1",
            )
        return SystemFfmpegProcess(ProcessBuilder(command).start())
    }
}

private class SystemFfmpegProcess(
    private val delegate: Process,
) : FfmpegProcess {
    override val output: InputStream = delegate.inputStream
    private val stderr = BoundedErrorCollector(delegate.errorStream)

    override fun awaitExit(): Int {
        val result = delegate.waitFor()
        stderr.join()
        return result
    }

    override fun failureMessage(prefix: String): String {
        val detail = stderr.text().trim()
        return if (detail.isEmpty()) prefix else "$prefix: $detail"
    }

    override fun close() {
        output.close()
        if (delegate.isAlive) {
            delegate.destroy()
            if (!delegate.waitFor(PROCESS_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) delegate.destroyForcibly()
        }
        stderr.join()
    }
}

private class BoundedErrorCollector(
    input: InputStream,
) {
    private val buffer = ByteArrayOutputStream()
    private val thread =
        Thread({
            input.use { stream ->
                val chunk = ByteArray(4096)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    synchronized(buffer) {
                        val remaining = MAXIMUM_FFMPEG_ERROR_BYTES - buffer.size()
                        if (remaining > 0) buffer.write(chunk, 0, minOf(count, remaining))
                    }
                }
            }
        }, "kmediaplayer-ffmpeg-dovi-stderr").apply {
            isDaemon = true
            start()
        }

    fun text(): String = synchronized(buffer) { buffer.toByteArray().decodeToString() }

    fun join() {
        thread.join(ERROR_COLLECTOR_JOIN_TIMEOUT_MILLIS)
    }
}

internal data class InitializationPrefix(
    val initialization: ByteArray,
    val reader: StreamingIsoBmffReader,
)

internal class StreamingIsoBmffReader(
    private val input: InputStream,
    private val maximumInitializationBytes: Int,
    private val maximumFragmentBytes: Int,
) {
    private var pendingBox: ByteArray? = null

    @Suppress("ThrowsCount")
    fun readInitialization(): InitializationPrefix {
        val output = ByteArrayOutputStream()
        var foundMoov = false
        while (!foundMoov) {
            val box = readBox(maximumInitializationBytes) ?: throw EOFException("FFmpeg ended before the moov box.")
            val type = box.readFourCc(4)
            if (type == "moof" || type == "mdat") {
                throw IllegalArgumentException("FFmpeg emitted media before initialization.")
            }
            if (output.size().toLong() + box.size > maximumInitializationBytes) {
                throw IllegalArgumentException("FFmpeg initialization exceeds the byte limit.")
            }
            output.write(box)
            foundMoov = type == "moov"
        }
        return InitializationPrefix(output.toByteArray(), this)
    }

    fun readMediaFragment(): ByteArray? {
        val output = ByteArrayOutputStream()
        var foundMoof = false
        while (true) {
            val box = pendingBox?.also { pendingBox = null } ?: readBox(maximumFragmentBytes) ?: return null
            val type = box.readFourCc(4)
            if (!foundMoof) {
                if (type == "mfra" || type == "mfro") return null
                if (type == "moof") {
                    foundMoof = true
                } else if (type !in FRAGMENT_PREFIX_BOX_TYPES) {
                    continue
                }
            } else if (type == "moof") {
                pendingBox = box
                throw IllegalArgumentException("A moof box was not followed by mdat.")
            }
            if (output.size().toLong() + box.size > maximumFragmentBytes) {
                throw IllegalArgumentException("FFmpeg media fragment exceeds the byte limit.")
            }
            output.write(box)
            if (foundMoof && type == "mdat") return output.toByteArray()
        }
    }

    private fun readBox(maximumBytes: Int): ByteArray? {
        val header = input.readExactlyOrEof(8) ?: return null
        val shortSize = header.readUnsignedInt(0)
        val headerBytes: ByteArray
        val totalSize =
            when (shortSize) {
                0L -> throw IllegalArgumentException("Unbounded streamed ISO BMFF boxes are not supported.")
                1L -> {
                    val extended = input.readExactly(8)
                    headerBytes = header + extended
                    extended.readUnsignedLong(0)
                }
                else -> {
                    headerBytes = header
                    shortSize
                }
            }
        if (totalSize < headerBytes.size || totalSize > maximumBytes || totalSize > Int.MAX_VALUE) {
            throw IllegalArgumentException("Streamed ISO BMFF box exceeds its byte limit.")
        }
        return headerBytes + input.readExactly(totalSize.toInt() - headerBytes.size)
    }
}

private fun InputStream.readExactlyOrEof(length: Int): ByteArray? {
    val first = read()
    if (first < 0) return null
    val result = ByteArray(length)
    result[0] = first.toByte()
    var offset = 1
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) throw EOFException("Truncated FFmpeg ISO BMFF output.")
        offset += count
    }
    return result
}

private fun InputStream.readExactly(length: Int): ByteArray {
    if (length == 0) return ByteArray(0)
    return readExactlyOrEof(length) ?: throw EOFException("Truncated FFmpeg ISO BMFF output.")
}

private suspend fun openProcessStream(
    request: JvmFfmpegDolbyVisionRequest,
    converter: DolbyVisionRpuConverter,
    process: FfmpegProcess,
    processFactory: FfmpegProcessFactory,
): JvmFfmpegDolbyVisionOpenResult {
    val reader =
        StreamingIsoBmffReader(
            process.output,
            request.maximumInitializationBytes,
            request.maximumFragmentBytes,
        )
    val prefix =
        try {
            reader.readInitialization()
        } catch (error: Throwable) {
            return ffmpegOpenFailure(
                process.failureMessage("Invalid FFmpeg fragmented MP4 output: ${error.message}"),
            )
        }
    val configuration =
        when (val result = CmafDolbyVisionInitializationSegment.prepareProfile81(prefix.initialization)) {
            is CmafDolbyVisionInitializationResult.Success -> result.configuration
            is CmafDolbyVisionInitializationResult.Failure -> return ffmpegOpenFailure(result.message)
        }
    return JvmFfmpegDolbyVisionOpenResult.Success(
        JvmFfmpegDolbyVisionSession(
            request = request,
            converter = converter,
            processFactory = processFactory,
            process = process,
            boxReader = prefix.reader,
            configuration = configuration,
        ),
    )
}

private fun formatFfmpegSeconds(microseconds: Long): String {
    val whole = microseconds / MICROSECONDS_PER_SECOND
    val fraction = microseconds % MICROSECONDS_PER_SECOND
    return "$whole.${fraction.toString().padStart(FFMPEG_TIMESTAMP_FRACTION_DIGITS, '0')}"
}

private fun ffmpegOpenFailure(message: String) = JvmFfmpegDolbyVisionOpenResult.Failure(message)

private const val DEFAULT_FFMPEG_FRAGMENT_DURATION_US = 2_000_000L
private const val DEFAULT_FFMPEG_INITIALIZATION_BYTES = 32 * 1024 * 1024
private const val DEFAULT_FFMPEG_FRAGMENT_BYTES = 64 * 1024 * 1024
private const val MAXIMUM_VERSION_OUTPUT_BYTES = 4096
private const val MAXIMUM_FFMPEG_ERROR_BYTES = 64 * 1024
private const val FFMPEG_PROBE_TIMEOUT_SECONDS = 3L
private const val PROCESS_CLOSE_TIMEOUT_SECONDS = 2L
private const val ERROR_COLLECTOR_JOIN_TIMEOUT_MILLIS = 2_000L
private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val FFMPEG_TIMESTAMP_FRACTION_DIGITS = 6
private val FRAGMENT_PREFIX_BOX_TYPES = setOf("styp", "sidx", "emsg", "prft", "free", "skip")
