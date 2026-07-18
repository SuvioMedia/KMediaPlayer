@file:Suppress(
    "LongParameterList",
    "MagicNumber",
    "TooGenericExceptionCaught",
)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MatroskaDolbyVisionFragment(
    val index: Int,
    val startPresentationTimeUs: Long,
    val endPresentationTimeUs: Long,
    val startsWithRandomAccessPoint: Boolean,
)

sealed interface MatroskaDolbyVisionOpenResult {
    data class Success(
        val session: MatroskaDolbyVisionSession,
    ) : MatroskaDolbyVisionOpenResult

    data class Failure(
        val message: String,
    ) : MatroskaDolbyVisionOpenResult
}

sealed interface MatroskaDolbyVisionFragmentResult {
    data class Success(
        val payload: ByteArray,
        val fragment: MatroskaDolbyVisionFragment,
    ) : MatroskaDolbyVisionFragmentResult

    data class Failure(
        val message: String,
    ) : MatroskaDolbyVisionFragmentResult
}

/**
 * Lazily indexes an unencrypted Matroska VOD and remuxes selected HEVC/AAC/Opus samples to CMAF.
 * Picture and audio payloads are copied; only Profile 7 RPU NAL units are converted through
 * [DolbyVisionRpuConverter]. Unsupported audio is rejected instead of being silently dropped.
 */
object MatroskaDolbyVisionAdapter {
    @Suppress("ReturnCount")
    suspend fun open(
        source: DolbyVisionRandomAccessDataSource,
        converter: DolbyVisionRpuConverter,
        enhancementLayer: DolbyVisionEnhancementLayer,
        targetFragmentDurationUs: Long = DEFAULT_TARGET_FRAGMENT_DURATION_US,
        maximumMetadataBytes: Int = DEFAULT_MAXIMUM_METADATA_BYTES,
        maximumInitializationBytes: Int = DEFAULT_MAXIMUM_INITIALIZATION_BYTES,
        maximumFragmentBytes: Int = DEFAULT_MAXIMUM_FRAGMENT_BYTES,
        maximumSamples: Int = DEFAULT_MAXIMUM_SAMPLES,
        maximumBufferedFragments: Int = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_FRAGMENTS,
        maximumBufferedBytes: Long = DolbyVisionConversionRequest.DEFAULT_MAXIMUM_BUFFERED_BYTES,
    ): MatroskaDolbyVisionOpenResult {
        val byteAndCountLimits =
            listOf(maximumMetadataBytes, maximumInitializationBytes, maximumFragmentBytes, maximumSamples)
        if (targetFragmentDurationUs <= 0 || byteAndCountLimits.any { it <= 0 }) {
            return matroskaOpenFailure("Matroska duration and resource limits must be positive.")
        }
        val parsed =
            try {
                parseMatroska(source, targetFragmentDurationUs, maximumMetadataBytes, maximumSamples)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return matroskaOpenFailure(
                    "Unable to parse Matroska: ${error.message ?: error::class.simpleName}.",
                )
            }
        val movie =
            when (parsed) {
                is MatroskaParseResult.Success -> parsed.movie
                is MatroskaParseResult.Failure -> return matroskaOpenFailure(parsed.message)
            }
        val initialization =
            try {
                buildMatroskaCmafInitialization(movie, maximumInitializationBytes)
            } catch (error: Throwable) {
                return matroskaOpenFailure(
                    "Unable to build Matroska CMAF initialization: ${error.message ?: error::class.simpleName}.",
                )
            }
        if (initialization.size > maximumInitializationBytes) {
            return matroskaOpenFailure("The Matroska CMAF initialization exceeds its byte limit.")
        }
        val configuration =
            when (
                val prepared =
                    CmafDolbyVisionInitializationSegment.prepareProfile81(
                        initialization,
                        maximumInitializationBytes,
                    )
            ) {
                is CmafDolbyVisionInitializationResult.Success -> prepared.configuration
                is CmafDolbyVisionInitializationResult.Failure -> return matroskaOpenFailure(prepared.message)
            }
        val request =
            try {
                DolbyVisionConversionRequest(
                    container = DolbyVisionContainer.MATROSKA,
                    profile = configuration.sourceProfile,
                    hasRpu = true,
                    enhancementLayer = enhancementLayer,
                    maximumBufferedFragments = maximumBufferedFragments,
                    maximumBufferedBytes = maximumBufferedBytes,
                )
            } catch (error: IllegalArgumentException) {
                return matroskaOpenFailure(error.message ?: "Invalid Matroska buffer limits.")
            }
        return MatroskaDolbyVisionOpenResult.Success(
            MatroskaDolbyVisionSession(
                source = source,
                movie = movie,
                configuration = configuration,
                converter = converter,
                request = request,
                maximumFragmentBytes = maximumFragmentBytes,
            ),
        )
    }
}

class MatroskaDolbyVisionSession internal constructor(
    private val source: DolbyVisionRandomAccessDataSource,
    private val movie: ParsedMatroskaMovie,
    configuration: CmafDolbyVisionTrackConfiguration,
    converter: DolbyVisionRpuConverter,
    request: DolbyVisionConversionRequest,
    private val maximumFragmentBytes: Int,
) {
    val initializationSegment: ByteArray = configuration.rewrittenInitializationSegment.copyOf()
    val fragments: List<MatroskaDolbyVisionFragment> =
        movie.fragments.map { plan ->
            MatroskaDolbyVisionFragment(
                index = plan.index,
                startPresentationTimeUs = plan.startPresentationTimeUs,
                endPresentationTimeUs = plan.endPresentationTimeUs,
                startsWithRandomAccessPoint = plan.startsWithRandomAccessPoint,
            )
        }

    private val demuxer = CmafDolbyVisionFragmentAdapter(configuration, maximumFragmentBytes)
    private val bridge =
        DolbyVisionStreamingBridge(
            request = request,
            converter = converter,
            remuxer = CmafDolbyVisionFragmentRemuxer(configuration, maximumFragmentBytes),
        )
    private val mutex = Mutex()
    private var lastConvertedIndex: Int? = null

    suspend fun convertFragment(index: Int): MatroskaDolbyVisionFragmentResult =
        mutex.withLock {
            val plan =
                movie.fragments.getOrNull(index)
                    ?: return@withLock MatroskaDolbyVisionFragmentResult.Failure(
                        "Matroska fragment index $index is out of range.",
                    )
            if (lastConvertedIndex?.plus(1) != index) {
                demuxer.reset()
                bridge.reset()
            }
            val cmaf =
                try {
                    buildMatroskaCmafFragment(source, movie, plan, maximumFragmentBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withLock MatroskaDolbyVisionFragmentResult.Failure(
                        "Unable to read Matroska fragment $index: ${error.message ?: error::class.simpleName}.",
                    )
                }
            val demuxed =
                when (val result = demuxer.demux(cmaf)) {
                    is CmafDolbyVisionDemuxResult.Success -> result.fragment
                    is CmafDolbyVisionDemuxResult.Failure ->
                        return@withLock MatroskaDolbyVisionFragmentResult.Failure(result.message)
                }
            val converted =
                when (val result = bridge.convert(demuxed)) {
                    is DolbyVisionFragmentConversionResult.Success -> result.value.fragment.payload
                    is DolbyVisionFragmentConversionResult.Failure ->
                        return@withLock MatroskaDolbyVisionFragmentResult.Failure(result.message)
                }
            lastConvertedIndex = index
            MatroskaDolbyVisionFragmentResult.Success(converted, fragments[index])
        }

    fun restartFragmentIndexForSeek(targetPresentationTimeUs: Long): Int {
        require(targetPresentationTimeUs >= 0) { "targetPresentationTimeUs must be non-negative." }
        return fragments.indexOfLast { it.startPresentationTimeUs <= targetPresentationTimeUs }.coerceAtLeast(0)
    }

    suspend fun resetForSeek(targetPresentationTimeUs: Long): Int =
        mutex.withLock {
            val index = restartFragmentIndexForSeek(targetPresentationTimeUs)
            demuxer.reset()
            bridge.reset()
            lastConvertedIndex = null
            index
        }
}

private fun matroskaOpenFailure(message: String) = MatroskaDolbyVisionOpenResult.Failure(message)

private const val DEFAULT_TARGET_FRAGMENT_DURATION_US = 2_000_000L
private const val DEFAULT_MAXIMUM_METADATA_BYTES = 32 * 1024 * 1024
private const val DEFAULT_MAXIMUM_INITIALIZATION_BYTES = 32 * 1024 * 1024
private const val DEFAULT_MAXIMUM_FRAGMENT_BYTES = 64 * 1024 * 1024
private const val DEFAULT_MAXIMUM_SAMPLES = 2_000_000
