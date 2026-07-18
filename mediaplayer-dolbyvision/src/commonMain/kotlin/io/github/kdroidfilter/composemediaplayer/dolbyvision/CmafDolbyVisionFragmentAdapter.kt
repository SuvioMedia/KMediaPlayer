@file:Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "MaxLineLength",
)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.CancellationException

sealed interface CmafDolbyVisionDemuxResult {
    data class Success(
        val fragment: DolbyVisionMediaFragment,
    ) : CmafDolbyVisionDemuxResult

    data class Failure(
        val message: String,
    ) : CmafDolbyVisionDemuxResult
}

/**
 * Stateful CMAF demux adapter. It extracts Profile 7 RPU NAL units and associates them with the
 * exact composition timestamp of their HEVC access unit. Media and audio bytes remain untouched.
 */
class CmafDolbyVisionFragmentAdapter(
    private val configuration: CmafDolbyVisionTrackConfiguration,
    private val maximumFragmentBytes: Int = DEFAULT_MAXIMUM_CMAF_FRAGMENT_BYTES,
) {
    private var precedingKeyframeTimeUs = 0L
    private var lastSequence: Long? = null

    internal var lastFragmentTiming: CmafTrackFragmentTiming? = null
        private set

    init {
        require(maximumFragmentBytes > 0) { "maximumFragmentBytes must be positive." }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun demux(payload: ByteArray): CmafDolbyVisionDemuxResult {
        lastFragmentTiming = null
        if (payload.isEmpty()) return failure("The CMAF media fragment is empty.")
        if (payload.size > maximumFragmentBytes) return failure("The CMAF media fragment exceeds the byte limit.")
        val parsed =
            try {
                parseCmafFragment(payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return failure("Invalid CMAF media fragment: ${error.message ?: error::class.simpleName}.")
            }
        val structure =
            when (parsed) {
                is CmafStructureResult.Success -> parsed.value
                is CmafStructureResult.Failure -> return failure(parsed.message)
            }
        val previousSequence = lastSequence
        if (previousSequence != null && structure.sequence <= previousSequence) {
            return failure("CMAF fragment sequence numbers must increase.")
        }
        val videoSamples = structure.runs.filter { it.trackId == configuration.trackId }.flatMap(CmafRun::samples)
        if (videoSamples.isEmpty()) return failure("The CMAF fragment contains no configured Dolby Vision track.")

        val firstDecodeSample = videoSamples.minBy(CmafSample::decodeTime)
        val firstPresentationTimeUs =
            videoSamples.minOf(CmafSample::presentationTime).toMicroseconds(configuration.timescale)
                ?: return failure("A video sample has an invalid presentation timestamp.")
        val firstDecodeTimeUs =
            firstDecodeSample.decodeTime.toMicroseconds(configuration.timescale)
                ?: return failure("A video sample has an invalid decode timestamp.")
        lastFragmentTiming =
            CmafTrackFragmentTiming(
                firstDecodeTimeUs = firstDecodeTimeUs,
                firstPresentationTimeUs = firstPresentationTimeUs,
                startsWithSyncSample = firstDecodeSample.isSyncSample,
            )

        val rpus = mutableListOf<TimedDolbyVisionRpu>()
        val keyframeTimes = mutableListOf<Long>()
        var fragmentStart = Long.MAX_VALUE
        var fragmentEnd = Long.MIN_VALUE
        for (sample in videoSamples) {
            val presentationTimeUs =
                sample.presentationTime.toMicroseconds(configuration.timescale)
                    ?: return failure("A video sample has an invalid presentation timestamp.")
            val endTimeUs =
                (sample.presentationTime + sample.duration).toMicroseconds(configuration.timescale)
                    ?: return failure("A video sample has an invalid duration.")
            fragmentStart = minOf(fragmentStart, presentationTimeUs)
            fragmentEnd = maxOf(fragmentEnd, endTimeUs)
            if (sample.isSyncSample) keyframeTimes += presentationTimeUs
            val sampleBytes = payload.copyOfRange(sample.offset, sample.offset + sample.size)
            val sampleRpus =
                extractLengthPrefixedRpus(sampleBytes, configuration.nalLengthFieldBytes)
                    ?: return failure("A video sample contains malformed HEVC NAL lengths.")
            if (sampleRpus.size > 1) {
                return failure("More than one RPU in a single HEVC access unit is not supported.")
            }
            sampleRpus.singleOrNull()?.let { rpu -> rpus += TimedDolbyVisionRpu(presentationTimeUs, rpu) }
        }
        if (rpus.isEmpty()) return failure("The CMAF fragment contains no Dolby Vision RPU NAL units.")
        val restartTime = keyframeTimes.filter { it <= fragmentStart }.maxOrNull() ?: precedingKeyframeTimeUs
        keyframeTimes.maxOrNull()?.let { latest -> precedingKeyframeTimeUs = maxOf(precedingKeyframeTimeUs, latest) }
        lastSequence = structure.sequence
        return CmafDolbyVisionDemuxResult.Success(
            DolbyVisionMediaFragment(
                sequence = structure.sequence,
                startPresentationTimeUs = fragmentStart,
                endPresentationTimeUs = fragmentEnd,
                precedingKeyframeTimeUs = restartTime,
                payload = payload,
                rpus = rpus.sortedBy(TimedDolbyVisionRpu::presentationTimeUs),
            ),
        )
    }

    fun reset(precedingKeyframeTimeUs: Long = 0L) {
        require(precedingKeyframeTimeUs >= 0) { "precedingKeyframeTimeUs must be non-negative." }
        this.precedingKeyframeTimeUs = precedingKeyframeTimeUs
        lastSequence = null
        lastFragmentTiming = null
    }

    private fun failure(message: String) = CmafDolbyVisionDemuxResult.Failure(message)
}

/** Probes one unencrypted fMP4 track without decoding or retaining the media payload. */
internal fun ByteArray.readCmafTrackFragmentTiming(
    configuration: CmafTrackTimingConfiguration,
): CmafTrackFragmentTiming? {
    val structure = (parseCmafFragment(this) as? CmafStructureResult.Success)?.value ?: return null
    val samples = structure.runs.filter { it.trackId == configuration.trackId }.flatMap(CmafRun::samples)
    if (samples.isEmpty()) return null
    val firstDecodeSample = samples.minBy(CmafSample::decodeTime)
    val firstDecodeTimeUs = firstDecodeSample.decodeTime.toMicroseconds(configuration.timescale) ?: return null
    val firstPresentationTimeUs =
        samples.minOf(CmafSample::presentationTime).toMicroseconds(configuration.timescale) ?: return null
    return CmafTrackFragmentTiming(
        firstDecodeTimeUs = firstDecodeTimeUs,
        firstPresentationTimeUs = firstPresentationTimeUs,
        startsWithSyncSample = firstDecodeSample.isSyncSample,
    )
}

/** Rewrites a parsed CMAF fragment to Profile 8.1 while preserving every non-video byte and all timing fields. */
class CmafDolbyVisionFragmentRemuxer(
    private val configuration: CmafDolbyVisionTrackConfiguration,
    private val maximumOutputBytes: Int = DEFAULT_MAXIMUM_CMAF_FRAGMENT_BYTES,
) : DolbyVisionFragmentRemuxer {
    override val isAvailable: Boolean = true

    init {
        require(maximumOutputBytes > 0) { "maximumOutputBytes must be positive." }
    }

    @Suppress("ReturnCount")
    override suspend fun remux(
        source: DolbyVisionMediaFragment,
        convertedRpus: List<TimedDolbyVisionRpu>,
    ): ConvertedDolbyVisionFragment {
        val structure =
            when (val parsed = parseCmafFragment(source.payload)) {
                is CmafStructureResult.Success -> parsed.value
                is CmafStructureResult.Failure -> error(parsed.message)
            }
        if (structure.sequence != source.sequence) error("The CMAF mfhd sequence does not match the bridge fragment.")
        val replacementsByTime = convertedRpus.associateBy(TimedDolbyVisionRpu::presentationTimeUs)
        if (replacementsByTime.size != convertedRpus.size) error("Converted RPU timestamps must be unique.")
        val consumedTimes = mutableSetOf<Long>()
        val modifications = mutableListOf<SampleModification>()

        for (sample in structure.runs.filter { it.trackId == configuration.trackId }.flatMap(CmafRun::samples)) {
            val timeUs =
                sample.presentationTime.toMicroseconds(configuration.timescale)
                    ?: error("A video sample has an invalid presentation timestamp.")
            val replacement = replacementsByTime[timeUs]
            val convertedForSample = replacement?.let { listOf(it.nalUnit) }.orEmpty()
            val samplePayload = source.payload.copyOfRange(sample.offset, sample.offset + sample.size)
            val rewritten =
                when (
                    val result =
                        HevcDolbyVisionRpuRewriter.rewrite(
                            payload = samplePayload,
                            format = HevcNalUnitFormat.LENGTH_PREFIXED_4,
                            convertedRpus = convertedForSample,
                            discardEnhancementLayer = true,
                            maximumOutputBytes = maximumOutputBytes,
                        )
                ) {
                    is HevcRpuRewriteResult.Success -> result
                    is HevcRpuRewriteResult.Failure -> error(result.message)
                }
            if (rewritten.replacedRpus > 0) consumedTimes += timeUs
            if (!rewritten.payload.contentEquals(samplePayload)) {
                if (sample.sizeFieldOffset == null && rewritten.payload.size != sample.size) {
                    error("A changed HEVC sample needs an explicit trun sample_size field.")
                }
                modifications += SampleModification(sample, rewritten.payload)
            }
        }
        if (consumedTimes != replacementsByTime.keys) {
            error("Converted RPU timestamps do not match the CMAF video samples.")
        }
        val rewrittenPayload = rewriteCmafPayload(source.payload, structure, modifications, maximumOutputBytes)
        return ConvertedDolbyVisionFragment(
            fragment = source.copy(payload = rewrittenPayload),
            convertedRpus = convertedRpus,
            timestampsAndAudioPreserved = true,
        )
    }
}

private sealed interface CmafStructureResult {
    data class Success(
        val value: CmafStructure,
    ) : CmafStructureResult

    data class Failure(
        val message: String,
    ) : CmafStructureResult
}

private data class CmafStructure(
    val sequence: Long,
    val moof: IsoBmffBox,
    val mdat: IsoBmffBox,
    val sidx: IsoBmffBox?,
    val runs: List<CmafRun>,
)

private data class CmafRun(
    val trackId: Int,
    val dataOffsetFieldOffset: Int?,
    val originalDataOffset: Int?,
    val dataOffsetBase: Long,
    val samples: List<CmafSample>,
)

private data class CmafSample(
    val offset: Int,
    val size: Int,
    val duration: Long,
    val decodeTime: Long,
    val presentationTime: Long,
    val flags: Long,
    val sizeFieldOffset: Int?,
) {
    val isSyncSample: Boolean get() = flags and SAMPLE_IS_NON_SYNC_FLAG == 0L
}

private data class SampleModification(
    val sample: CmafSample,
    val payload: ByteArray,
) {
    val delta: Int get() = payload.size - sample.size
}

private data class TrackFragmentDefaults(
    val trackId: Int,
    val baseDataOffset: Long,
    val defaultDuration: Long?,
    val defaultSize: Int?,
    val defaultFlags: Long,
)

@Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
private fun parseCmafFragment(payload: ByteArray): CmafStructureResult {
    val top =
        when (val parsed = payload.parseIsoBmffBoxes()) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> return CmafStructureResult.Failure(parsed.message)
        }
    val moof =
        top.singleOrNull { it.type == BOX_MOOF }
            ?: return CmafStructureResult.Failure("A CMAF fragment must contain one moof box.")
    val mdat =
        top.singleOrNull { it.type == BOX_MDAT }
            ?: return CmafStructureResult.Failure("A CMAF fragment must contain one mdat box.")
    if (moof.endOffset > mdat.offset) return CmafStructureResult.Failure("The CMAF moof box must precede mdat.")
    if (top.any { it.type in ENCRYPTION_FRAGMENT_BOXES }) {
        return CmafStructureResult.Failure("Encrypted CMAF fragments are not supported.")
    }
    val moofChildren =
        when (val parsed = payload.parseIsoBmffBoxes(moof.contentOffset, moof.endOffset)) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> return CmafStructureResult.Failure(parsed.message)
        }
    val mfhd =
        moofChildren.singleOrNull { it.type == BOX_MFHD }
            ?: return CmafStructureResult.Failure("The CMAF moof box has no single mfhd.")
    if (mfhd.size - mfhd.headerSize < MFHD_MINIMUM_CONTENT_BYTES) {
        return CmafStructureResult.Failure("The CMAF mfhd box is truncated.")
    }
    val sequence = payload.readUnsignedInt(mfhd.contentOffset + ISO_FULL_BOX_HEADER_BYTES)
    val runs = mutableListOf<CmafRun>()
    for (traf in moofChildren.filter { it.type == BOX_TRAF }) {
        val trafChildren =
            when (val parsed = payload.parseIsoBmffBoxes(traf.contentOffset, traf.endOffset)) {
                is IsoBmffParseResult.Success -> parsed.boxes
                is IsoBmffParseResult.Failure -> return CmafStructureResult.Failure(parsed.message)
            }
        if (trafChildren.any { it.type in ENCRYPTION_FRAGMENT_BOXES }) {
            return CmafStructureResult.Failure("Encrypted CMAF track fragments are not supported.")
        }
        val tfhd =
            trafChildren.singleOrNull { it.type == BOX_TFHD }
                ?: return CmafStructureResult.Failure("A CMAF traf box has no single tfhd.")
        val tfdt =
            trafChildren.singleOrNull { it.type == BOX_TFDT }
                ?: return CmafStructureResult.Failure("A CMAF traf box has no single tfdt.")
        val defaults =
            parseTfhd(payload, tfhd, moof.offset)
                ?: return CmafStructureResult.Failure("The CMAF tfhd box is malformed.")
        var decodeTime =
            parseTfdt(payload, tfdt)
                ?: return CmafStructureResult.Failure("The CMAF tfdt box is malformed.")
        var previousRunEnd: Long? = null
        for (trun in trafChildren.filter { it.type == BOX_TRUN }) {
            val parsedRun =
                parseTrun(payload, trun, defaults, decodeTime, previousRunEnd)
                    ?: return CmafStructureResult.Failure(
                        "A CMAF trun box is malformed or lacks sample sizes/data offsets.",
                    )
            val runEnd = parsedRun.samples.lastOrNull()?.let { it.offset.toLong() + it.size }
            if (runEnd != null) previousRunEnd = runEnd
            decodeTime += parsedRun.samples.sumOf(CmafSample::duration)
            runs += parsedRun
        }
    }
    if (runs.isEmpty()) return CmafStructureResult.Failure("The CMAF fragment contains no track runs.")
    for (sample in runs.flatMap(CmafRun::samples)) {
        if (sample.offset < mdat.contentOffset || sample.offset > mdat.endOffset - sample.size) {
            return CmafStructureResult.Failure("A CMAF sample points outside the mdat payload.")
        }
    }
    return CmafStructureResult.Success(
        CmafStructure(
            sequence = sequence,
            moof = moof,
            mdat = mdat,
            sidx = top.singleOrNull { it.type == BOX_SIDX },
            runs = runs,
        ),
    )
}

@Suppress("ReturnCount")
private fun parseTfhd(
    bytes: ByteArray,
    box: IsoBmffBox,
    moofOffset: Int,
): TrackFragmentDefaults? {
    if (box.size - box.headerSize < TFHD_MINIMUM_CONTENT_BYTES) return null
    val flags = bytes.readUnsignedInt(box.contentOffset) and FULL_BOX_FLAGS_MASK
    val trackId =
        bytes
            .readUnsignedInt(box.contentOffset + ISO_FULL_BOX_HEADER_BYTES)
            .takeIf { it in 1..Int.MAX_VALUE }
            ?.toInt() ?: return null
    var cursor = box.contentOffset + TFHD_MINIMUM_CONTENT_BYTES
    val baseDataOffset =
        if (flags and TFHD_BASE_DATA_OFFSET_PRESENT != 0L) {
            if (cursor > box.endOffset - UINT64_BYTES) return null
            bytes.readUnsignedLong(cursor).also { cursor += UINT64_BYTES }
        } else {
            moofOffset.toLong()
        }
    if (flags and TFHD_SAMPLE_DESCRIPTION_INDEX_PRESENT != 0L) cursor += UINT32_BYTES
    val defaultDuration =
        if (flags and TFHD_DEFAULT_SAMPLE_DURATION_PRESENT != 0L) {
            if (cursor > box.endOffset - UINT32_BYTES) return null
            bytes.readUnsignedInt(cursor).also { cursor += UINT32_BYTES }
        } else {
            null
        }
    val defaultSize =
        if (flags and TFHD_DEFAULT_SAMPLE_SIZE_PRESENT != 0L) {
            if (cursor > box.endOffset - UINT32_BYTES) return null
            bytes
                .readUnsignedInt(cursor)
                .takeIf { it in 1..Int.MAX_VALUE }
                ?.toInt()
                .also { cursor += UINT32_BYTES }
                ?: return null
        } else {
            null
        }
    val defaultFlags =
        if (flags and TFHD_DEFAULT_SAMPLE_FLAGS_PRESENT != 0L) {
            if (cursor > box.endOffset - UINT32_BYTES) return null
            bytes.readUnsignedInt(cursor).also { cursor += UINT32_BYTES }
        } else {
            0L
        }
    if (cursor > box.endOffset) return null
    return TrackFragmentDefaults(trackId, baseDataOffset, defaultDuration, defaultSize, defaultFlags)
}

private fun parseTfdt(
    bytes: ByteArray,
    box: IsoBmffBox,
): Long? {
    if (box.size - box.headerSize < TFDT_VERSION_0_CONTENT_BYTES) return null
    val version = bytes[box.contentOffset].toInt() and BYTE_MASK
    return if (version == 1) {
        if (box.size - box.headerSize < TFDT_VERSION_1_CONTENT_BYTES) {
            null
        } else {
            bytes.readUnsignedLong(box.contentOffset + ISO_FULL_BOX_HEADER_BYTES)
        }
    } else {
        bytes.readUnsignedInt(box.contentOffset + ISO_FULL_BOX_HEADER_BYTES)
    }
}

@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun parseTrun(
    bytes: ByteArray,
    box: IsoBmffBox,
    defaults: TrackFragmentDefaults,
    baseDecodeTime: Long,
    previousRunEnd: Long?,
): CmafRun? {
    if (box.size - box.headerSize < TRUN_MINIMUM_CONTENT_BYTES) return null
    val version = bytes[box.contentOffset].toInt() and BYTE_MASK
    val flags = bytes.readUnsignedInt(box.contentOffset) and FULL_BOX_FLAGS_MASK
    val sampleCount =
        bytes
            .readUnsignedInt(box.contentOffset + ISO_FULL_BOX_HEADER_BYTES)
            .takeIf { it <= MAXIMUM_SAMPLES_PER_FRAGMENT }
            ?.toInt() ?: return null
    var cursor = box.contentOffset + TRUN_MINIMUM_CONTENT_BYTES
    val dataOffsetField =
        if (flags and TRUN_DATA_OFFSET_PRESENT != 0L) {
            if (cursor > box.endOffset - UINT32_BYTES) return null
            cursor.also { cursor += UINT32_BYTES }
        } else {
            null
        }
    val dataOffset = dataOffsetField?.let(bytes::readSignedInt)
    val firstSampleFlags =
        if (flags and TRUN_FIRST_SAMPLE_FLAGS_PRESENT != 0L) {
            if (cursor > box.endOffset - UINT32_BYTES) return null
            bytes.readUnsignedInt(cursor).also { cursor += UINT32_BYTES }
        } else {
            null
        }
    val runStart =
        if (dataOffset != null) {
            defaults.baseDataOffset + dataOffset
        } else {
            previousRunEnd ?: return null
        }
    if (runStart !in 0..Int.MAX_VALUE.toLong()) return null
    var sampleOffset = runStart.toInt()
    var decodeTime = baseDecodeTime
    val samples = ArrayList<CmafSample>(sampleCount)
    repeat(sampleCount) { sampleIndex ->
        val duration =
            if (flags and TRUN_SAMPLE_DURATION_PRESENT != 0L) {
                if (cursor > box.endOffset - UINT32_BYTES) return null
                bytes.readUnsignedInt(cursor).also { cursor += UINT32_BYTES }
            } else {
                defaults.defaultDuration ?: return null
            }
        val sizeField =
            if (flags and TRUN_SAMPLE_SIZE_PRESENT != 0L) {
                if (cursor > box.endOffset - UINT32_BYTES) return null
                cursor
            } else {
                null
            }
        val size =
            if (sizeField != null) {
                bytes
                    .readUnsignedInt(cursor)
                    .takeIf { it in 1..Int.MAX_VALUE }
                    ?.toInt()
                    .also { cursor += UINT32_BYTES }
                    ?: return null
            } else {
                defaults.defaultSize ?: return null
            }
        val sampleFlags =
            if (flags and TRUN_SAMPLE_FLAGS_PRESENT != 0L) {
                if (cursor > box.endOffset - UINT32_BYTES) return null
                bytes.readUnsignedInt(cursor).also { cursor += UINT32_BYTES }
            } else if (sampleIndex == 0 && firstSampleFlags != null) {
                firstSampleFlags
            } else {
                defaults.defaultFlags
            }
        val compositionOffset =
            if (flags and TRUN_SAMPLE_COMPOSITION_TIME_OFFSET_PRESENT != 0L) {
                if (cursor > box.endOffset - UINT32_BYTES) return null
                val value = if (version == 1) bytes.readSignedInt(cursor).toLong() else bytes.readUnsignedInt(cursor)
                cursor += UINT32_BYTES
                value
            } else {
                0L
            }
        val presentationTime = decodeTime + compositionOffset
        if (presentationTime < 0 || sampleOffset > Int.MAX_VALUE - size) return null
        samples += CmafSample(sampleOffset, size, duration, decodeTime, presentationTime, sampleFlags, sizeField)
        sampleOffset += size
        decodeTime += duration
    }
    if (cursor > box.endOffset) return null
    return CmafRun(
        trackId = defaults.trackId,
        dataOffsetFieldOffset = dataOffsetField,
        originalDataOffset = dataOffset,
        dataOffsetBase = defaults.baseDataOffset,
        samples = samples,
    )
}

@Suppress("ReturnCount")
private fun extractLengthPrefixedRpus(
    sample: ByteArray,
    nalLengthFieldBytes: Int,
): List<ByteArray>? {
    if (nalLengthFieldBytes != 4) return null
    val result = mutableListOf<ByteArray>()
    var cursor = 0
    while (cursor < sample.size) {
        if (cursor > sample.size - UINT32_BYTES) return null
        val nalSize = sample.readUnsignedInt(cursor)
        cursor += UINT32_BYTES
        if (nalSize < HEVC_NAL_HEADER_BYTES || nalSize > sample.size.toLong() - cursor) return null
        val end = cursor + nalSize.toInt()
        val nalType = ((sample[cursor].toInt() and BYTE_MASK) ushr 1) and HEVC_NAL_TYPE_MASK
        if (nalType == HEVC_UNSPEC_62_NAL_TYPE) result += sample.copyOfRange(cursor, end)
        cursor = end
    }
    return result
}

@Suppress("ReturnCount")
private fun rewriteCmafPayload(
    source: ByteArray,
    structure: CmafStructure,
    modifications: List<SampleModification>,
    maximumOutputBytes: Int,
): ByteArray {
    if (modifications.isEmpty()) return source.copyOf()
    val sorted = modifications.sortedBy { it.sample.offset }
    var previousEnd = structure.mdat.contentOffset
    var totalDelta = 0L
    for (modification in sorted) {
        if (modification.sample.offset < previousEnd ||
            modification.sample.offset + modification.sample.size > structure.mdat.endOffset
        ) {
            error("Overlapping or out-of-range CMAF sample modification.")
        }
        previousEnd = modification.sample.offset + modification.sample.size
        totalDelta += modification.delta
    }
    val outputSize = source.size.toLong() + totalDelta
    if (outputSize !in 1..maximumOutputBytes.toLong() || outputSize > Int.MAX_VALUE) {
        error("The rewritten CMAF fragment exceeds the configured byte limit.")
    }
    val output = ByteArray(outputSize.toInt())
    var sourceCursor = 0
    var outputCursor = 0
    for (modification in sorted) {
        source.copyInto(output, outputCursor, sourceCursor, modification.sample.offset)
        outputCursor += modification.sample.offset - sourceCursor
        modification.payload.copyInto(output, outputCursor)
        outputCursor += modification.payload.size
        sourceCursor = modification.sample.offset + modification.sample.size
    }
    source.copyInto(output, outputCursor, sourceCursor, source.size)

    fun deltaBefore(originalOffset: Int): Long =
        sorted.asSequence().filter { it.sample.offset < originalOffset }.sumOf { it.delta.toLong() }

    for (modification in sorted) {
        modification.sample.sizeFieldOffset?.let { field ->
            output.writeUnsignedInt(field, modification.payload.size.toLong())
        }
    }
    for (run in structure.runs) {
        val field = run.dataOffsetFieldOffset ?: continue
        val oldValue = run.originalDataOffset ?: continue
        val firstSample = run.samples.firstOrNull() ?: continue
        val newAbsolute = firstSample.offset.toLong() + deltaBefore(firstSample.offset)
        val newValue = newAbsolute - run.dataOffsetBase
        if (newValue !in
            Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        ) {
            error("The rewritten trun data_offset overflows.")
        }
        output.writeSignedInt(field, newValue.toInt())
        check(oldValue.toLong() + deltaBefore(firstSample.offset) == newValue) {
            "The CMAF data-offset base changed unexpectedly."
        }
    }
    val newMdatSize = structure.mdat.size.toLong() + totalDelta
    when (structure.mdat.headerSize) {
        ISO_BOX_HEADER_BYTES -> {
            if (source.readUnsignedInt(structure.mdat.offset) != 0L) {
                output.writeUnsignedInt(structure.mdat.offset, newMdatSize)
            }
        }
        ISO_EXTENDED_HEADER_BYTES -> output.writeUnsignedLong(structure.mdat.offset + ISO_BOX_HEADER_BYTES, newMdatSize)
        else -> error("Unsupported mdat header size.")
    }
    structure.sidx?.let { sidx -> patchSingleReferenceSidx(output, sidx, totalDelta) }
    return output
}

private fun patchSingleReferenceSidx(
    output: ByteArray,
    sidx: IsoBmffBox,
    delta: Long,
) {
    if (delta == 0L) return
    val content = sidx.contentOffset
    if (sidx.size - sidx.headerSize < SIDX_VERSION_0_MINIMUM_CONTENT_BYTES) error("The sidx box is truncated.")
    val version = output[content].toInt() and BYTE_MASK
    var cursor = content + SIDX_COMMON_FIELDS_BYTES
    cursor += if (version == 0) SIDX_TIME_FIELDS_V0_BYTES else SIDX_TIME_FIELDS_V1_BYTES
    if (cursor > sidx.endOffset - SIDX_RESERVED_AND_COUNT_BYTES) error("The sidx box is truncated.")
    val referenceCount =
        ((output[cursor + 2].toInt() and BYTE_MASK) shl 8) or (output[cursor + 3].toInt() and BYTE_MASK)
    cursor += SIDX_RESERVED_AND_COUNT_BYTES
    if (referenceCount != 1 || cursor > sidx.endOffset - SIDX_REFERENCE_BYTES) {
        error("A changed CMAF fragment requires a single-reference sidx box.")
    }
    val reference = output.readUnsignedInt(cursor)
    if (reference and SIDX_REFERENCE_TYPE_BIT != 0L) error("Hierarchical sidx references are not supported.")
    val oldSize = reference and SIDX_REFERENCED_SIZE_MASK
    val newSize = oldSize + delta
    if (newSize !in 0..SIDX_REFERENCED_SIZE_MASK) error("The rewritten sidx referenced_size overflows.")
    output.writeUnsignedInt(cursor, newSize)
}

private fun Long.toMicroseconds(timescale: Long): Long? {
    if (this < 0 || timescale <= 0) return null
    val seconds = this / timescale
    val remainder = this % timescale
    if (seconds > Long.MAX_VALUE / MICROSECONDS_PER_SECOND) return null
    return seconds * MICROSECONDS_PER_SECOND + remainder * MICROSECONDS_PER_SECOND / timescale
}

private const val DEFAULT_MAXIMUM_CMAF_FRAGMENT_BYTES = 64 * 1024 * 1024
private const val MAXIMUM_SAMPLES_PER_FRAGMENT = 1_000_000L
private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val BYTE_MASK = 0xff
private const val FULL_BOX_FLAGS_MASK = 0x00ff_ffffL
private const val MFHD_MINIMUM_CONTENT_BYTES = 8
private const val TFHD_MINIMUM_CONTENT_BYTES = 8
private const val TFDT_VERSION_0_CONTENT_BYTES = 8
private const val TFDT_VERSION_1_CONTENT_BYTES = 12
private const val TRUN_MINIMUM_CONTENT_BYTES = 8
private const val ISO_EXTENDED_HEADER_BYTES = 16
private const val HEVC_NAL_HEADER_BYTES = 2L
private const val HEVC_NAL_TYPE_MASK = 0x3f
private const val HEVC_UNSPEC_62_NAL_TYPE = 62
private const val SAMPLE_IS_NON_SYNC_FLAG = 0x0001_0000L
private const val TFHD_BASE_DATA_OFFSET_PRESENT = 0x000001L
private const val TFHD_SAMPLE_DESCRIPTION_INDEX_PRESENT = 0x000002L
private const val TFHD_DEFAULT_SAMPLE_DURATION_PRESENT = 0x000008L
private const val TFHD_DEFAULT_SAMPLE_SIZE_PRESENT = 0x000010L
private const val TFHD_DEFAULT_SAMPLE_FLAGS_PRESENT = 0x000020L
private const val TRUN_DATA_OFFSET_PRESENT = 0x000001L
private const val TRUN_FIRST_SAMPLE_FLAGS_PRESENT = 0x000004L
private const val TRUN_SAMPLE_DURATION_PRESENT = 0x000100L
private const val TRUN_SAMPLE_SIZE_PRESENT = 0x000200L
private const val TRUN_SAMPLE_FLAGS_PRESENT = 0x000400L
private const val TRUN_SAMPLE_COMPOSITION_TIME_OFFSET_PRESENT = 0x000800L
private const val SIDX_COMMON_FIELDS_BYTES = 12
private const val SIDX_TIME_FIELDS_V0_BYTES = 8
private const val SIDX_TIME_FIELDS_V1_BYTES = 16
private const val SIDX_RESERVED_AND_COUNT_BYTES = 4
private const val SIDX_REFERENCE_BYTES = 12
private const val SIDX_VERSION_0_MINIMUM_CONTENT_BYTES =
    SIDX_COMMON_FIELDS_BYTES + SIDX_TIME_FIELDS_V0_BYTES + SIDX_RESERVED_AND_COUNT_BYTES + SIDX_REFERENCE_BYTES
private const val SIDX_REFERENCE_TYPE_BIT = 0x8000_0000L
private const val SIDX_REFERENCED_SIZE_MASK = 0x7fff_ffffL
private const val BOX_MOOF = "moof"
private const val BOX_MDAT = "mdat"
private const val BOX_MFHD = "mfhd"
private const val BOX_TRAF = "traf"
private const val BOX_TFHD = "tfhd"
private const val BOX_TFDT = "tfdt"
private const val BOX_TRUN = "trun"
private const val BOX_SIDX = "sidx"
private val ENCRYPTION_FRAGMENT_BOXES = setOf("senc", "saiz", "saio", "uuid")
