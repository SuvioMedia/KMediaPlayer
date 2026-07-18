package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** RPU metadata tied to the presentation timestamp of the decoded picture it describes. */
data class TimedDolbyVisionRpu(
    val presentationTimeUs: Long,
    val nalUnit: ByteArray,
)

/** One demuxed fragment. Payload remains container-specific and is never decoded by this bridge. */
data class DolbyVisionMediaFragment(
    val sequence: Long,
    val startPresentationTimeUs: Long,
    val endPresentationTimeUs: Long,
    val precedingKeyframeTimeUs: Long,
    val payload: ByteArray,
    val rpus: List<TimedDolbyVisionRpu>,
) {
    init {
        require(sequence >= 0) { "sequence must be non-negative." }
        require(startPresentationTimeUs >= 0) { "startPresentationTimeUs must be non-negative." }
        require(endPresentationTimeUs >= startPresentationTimeUs) { "fragment end must not precede its start." }
        require(precedingKeyframeTimeUs in 0..startPresentationTimeUs) {
            "precedingKeyframeTimeUs must identify a keyframe at or before the fragment."
        }
    }
}

data class ConvertedDolbyVisionFragment(
    val fragment: DolbyVisionMediaFragment,
    val convertedRpus: List<TimedDolbyVisionRpu>,
    /** True only after the remuxer has preserved packet timing and non-video tracks. */
    val timestampsAndAudioPreserved: Boolean,
)

sealed interface DolbyVisionFragmentConversionResult {
    data class Success(
        val value: ConvertedDolbyVisionFragment,
    ) : DolbyVisionFragmentConversionResult

    data class Failure(
        val message: String,
    ) : DolbyVisionFragmentConversionResult
}

/** Container adapter that rewrites only RPU NAL units and emits CMAF/fMP4-compatible output. */
interface DolbyVisionFragmentRemuxer {
    val isAvailable: Boolean

    suspend fun remux(
        source: DolbyVisionMediaFragment,
        convertedRpus: List<TimedDolbyVisionRpu>,
    ): ConvertedDolbyVisionFragment
}

/**
 * Bounded, cancellation-friendly conversion coordinator. Demuxing and transport stay platform
 * specific; this class enforces ordering, timestamp association, keyframe restart and safe limits.
 */
class DolbyVisionStreamingBridge(
    private val request: DolbyVisionConversionRequest,
    private val converter: DolbyVisionRpuConverter,
    private val remuxer: DolbyVisionFragmentRemuxer,
) {
    private val buffer =
        BoundedDolbyVisionFragmentBuffer(
            maximumFragments = request.maximumBufferedFragments,
            maximumBytes = request.maximumBufferedBytes,
        )
    private val conversionMutex = Mutex()

    val plan: DolbyVisionConversionPlan
        get() =
            DolbyVisionConversionPlanner.plan(
                request = request,
                runtimeAvailable = converter.isAvailable && remuxer.isAvailable,
            )

    suspend fun convert(fragment: DolbyVisionMediaFragment): DolbyVisionFragmentConversionResult {
        conversionMutex.lock()
        try {
            return convertSerially(fragment)
        } finally {
            conversionMutex.unlock()
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    private suspend fun convertSerially(fragment: DolbyVisionMediaFragment): DolbyVisionFragmentConversionResult {
        val converterReady =
            try {
                converter.prepare()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return DolbyVisionFragmentConversionResult.Failure(
                    "Dolby Vision converter initialization failed: ${error.message ?: error::class.simpleName}",
                )
            }
        val activePlan =
            DolbyVisionConversionPlanner.plan(
                request = request,
                runtimeAvailable = converterReady && remuxer.isAvailable,
            )
        if (!activePlan.canConvert) {
            return DolbyVisionFragmentConversionResult.Failure(
                activePlan.detail ?: "Dolby Vision conversion is unavailable.",
            )
        }
        if (fragment.approximateByteSize > request.maximumBufferedBytes) {
            return DolbyVisionFragmentConversionResult.Failure(
                "The media fragment exceeds the configured bounded-buffer byte limit.",
            )
        }
        val previousSequence = buffer.lastSequence()
        if (previousSequence != null && fragment.sequence <= previousSequence) {
            return DolbyVisionFragmentConversionResult.Failure(
                "Fragment sequence numbers must increase; received ${fragment.sequence} after $previousSequence.",
            )
        }
        if (!fragment.rpus.isStrictlyTimestampedWithin(fragment)) {
            return DolbyVisionFragmentConversionResult.Failure(
                "RPU timestamps must be ordered and contained in their media fragment.",
            )
        }

        val converted = ArrayList<TimedDolbyVisionRpu>(fragment.rpus.size)
        for (timedRpu in fragment.rpus) {
            when (val result = converter.convertProfile7To81(timedRpu.nalUnit)) {
                is DolbyVisionRpuConversionResult.Success -> {
                    if (!result.rpuNalUnit.isUnspec62NalUnit()) {
                        return DolbyVisionFragmentConversionResult.Failure(
                            "libdovi returned an invalid HEVC UNSPEC-62 RPU NAL unit.",
                        )
                    }
                    converted += timedRpu.copy(nalUnit = result.rpuNalUnit)
                }
                is DolbyVisionRpuConversionResult.Invalid ->
                    return DolbyVisionFragmentConversionResult.Failure(result.message)
                is DolbyVisionRpuConversionResult.Unavailable ->
                    return DolbyVisionFragmentConversionResult.Failure(result.message)
            }
        }

        val output =
            try {
                remuxer.remux(fragment, converted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return DolbyVisionFragmentConversionResult.Failure(
                    "Dolby Vision remux failed: ${error.message ?: error::class.simpleName}",
                )
            }
        if (!output.timestampsAndAudioPreserved) {
            return DolbyVisionFragmentConversionResult.Failure(
                "The remuxer did not confirm preservation of PTS/DTS and audio tracks.",
            )
        }
        if (!output.preservesIdentityAndTimingOf(fragment, converted)) {
            return DolbyVisionFragmentConversionResult.Failure(
                "The remuxer changed fragment identity, timing, keyframe association or RPU timestamps.",
            )
        }
        if (!buffer.append(output)) {
            return DolbyVisionFragmentConversionResult.Failure(
                "The converted fragment exceeds the configured bounded-buffer byte limit.",
            )
        }
        return DolbyVisionFragmentConversionResult.Success(output)
    }

    /** Returns the preceding buffered keyframe from which a seek pipeline must restart. */
    fun restartTimeForSeek(targetPresentationTimeUs: Long): Long? = buffer.restartTimeForSeek(targetPresentationTimeUs)

    fun bufferedFragments(): List<ConvertedDolbyVisionFragment> = buffer.snapshot()

    fun reset() = buffer.clear()
}

internal class BoundedDolbyVisionFragmentBuffer(
    private val maximumFragments: Int,
    private val maximumBytes: Long,
) {
    private val fragments = ArrayDeque<ConvertedDolbyVisionFragment>()
    private var bufferedBytes = 0L

    init {
        require(maximumFragments > 0) { "maximumFragments must be positive." }
        require(maximumBytes > 0) { "maximumBytes must be positive." }
    }

    fun append(fragment: ConvertedDolbyVisionFragment): Boolean {
        val fragmentBytes = fragment.approximateByteSize
        if (fragmentBytes > maximumBytes) return false
        val previous = fragments.lastOrNull()?.fragment
        if (previous != null && fragment.fragment.sequence <= previous.sequence) return false
        fragments.addLast(fragment)
        bufferedBytes += fragmentBytes
        while (fragments.size > maximumFragments || bufferedBytes > maximumBytes) {
            bufferedBytes -= fragments.removeFirst().approximateByteSize
        }
        return true
    }

    fun lastSequence(): Long? = fragments.lastOrNull()?.fragment?.sequence

    fun restartTimeForSeek(targetPresentationTimeUs: Long): Long? =
        fragments
            .asSequence()
            .map(ConvertedDolbyVisionFragment::fragment)
            .filter {
                it.startPresentationTimeUs <= targetPresentationTimeUs &&
                    it.precedingKeyframeTimeUs <= targetPresentationTimeUs
            }.maxByOrNull(DolbyVisionMediaFragment::precedingKeyframeTimeUs)
            ?.precedingKeyframeTimeUs

    fun snapshot(): List<ConvertedDolbyVisionFragment> = fragments.toList()

    fun clear() {
        fragments.clear()
        bufferedBytes = 0
    }
}

private val DolbyVisionMediaFragment.approximateByteSize: Long
    get() = payload.size.toLong() + rpus.sumOf { it.nalUnit.size.toLong() }

private val ConvertedDolbyVisionFragment.approximateByteSize: Long
    get() = fragment.payload.size.toLong() + convertedRpus.sumOf { it.nalUnit.size.toLong() }

private fun ConvertedDolbyVisionFragment.preservesIdentityAndTimingOf(
    source: DolbyVisionMediaFragment,
    expectedRpus: List<TimedDolbyVisionRpu>,
): Boolean =
    fragment.sequence == source.sequence &&
        fragment.startPresentationTimeUs == source.startPresentationTimeUs &&
        fragment.endPresentationTimeUs == source.endPresentationTimeUs &&
        fragment.precedingKeyframeTimeUs == source.precedingKeyframeTimeUs &&
        convertedRpus.map(TimedDolbyVisionRpu::presentationTimeUs) ==
        expectedRpus.map(TimedDolbyVisionRpu::presentationTimeUs)

private fun ByteArray.isUnspec62NalUnit(): Boolean {
    val headerOffset =
        when {
            size >= 6 &&
                this[0] == 0.toByte() &&
                this[1] == 0.toByte() &&
                this[2] == 0.toByte() &&
                this[3] == 1.toByte() -> 4
            size >= 5 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte() -> 3
            else -> 0
        }
    if (size - headerOffset < 2) return false
    return (this[headerOffset].toInt() ushr 1) and HEVC_NAL_TYPE_MASK == HEVC_UNSPEC_62_NAL_TYPE
}

private fun List<TimedDolbyVisionRpu>.isStrictlyTimestampedWithin(fragment: DolbyVisionMediaFragment): Boolean {
    var previous = Long.MIN_VALUE
    return all { rpu ->
        val timestamp = rpu.presentationTimeUs
        val valid =
            timestamp in fragment.startPresentationTimeUs..fragment.endPresentationTimeUs &&
                timestamp > previous &&
                rpu.nalUnit.isNotEmpty()
        previous = timestamp
        valid
    }
}

private const val HEVC_NAL_TYPE_MASK = 0x3f
private const val HEVC_UNSPEC_62_NAL_TYPE = 62
