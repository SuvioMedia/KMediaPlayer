package io.github.kdroidfilter.composemediaplayer

internal data class AndroidVideoTrackCandidate(
    val index: Int,
    val dynamicRange: VideoDynamicRange,
    val isSupported: Boolean,
    val dolbyVisionProfile: Int? = null,
    val pixelCount: Long = 0,
    val bitrate: Int = 0,
)

internal data class AndroidVideoTrackGroupCandidate(
    val index: Int,
    val isSelected: Boolean,
    val tracks: List<AndroidVideoTrackCandidate>,
)

internal data class AndroidDynamicRangeTrackSelection(
    val groupIndex: Int,
    val trackIndices: List<Int>,
    val dynamicRange: VideoDynamicRange,
)

internal data class AndroidPreDecoderProfile7Selection(
    val groupIndex: Int,
    val trackIndex: Int,
)

/**
 * Finds a Profile 7 format that must be sent through the source bridge before Media3 can create a
 * decoder. AUTO only intervenes when Media3 has no supported video track; otherwise its native
 * profile-aware selection remains authoritative.
 */
internal fun selectAndroidPreDecoderProfile7Track(
    dolbyVisionPolicy: DolbyVisionPolicy,
    groups: List<AndroidVideoTrackGroupCandidate>,
): AndroidPreDecoderProfile7Selection? {
    if (
        dolbyVisionPolicy != DolbyVisionPolicy.AUTO &&
        dolbyVisionPolicy != DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1
    ) {
        return null
    }
    val explicitConversion = dolbyVisionPolicy == DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1
    if (!explicitConversion && groups.any { group -> group.tracks.any(AndroidVideoTrackCandidate::isSupported) }) {
        return null
    }
    return groups
        .flatMap { group ->
            group.tracks.mapNotNull { track ->
                track
                    .takeIf {
                        it.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                            it.dolbyVisionProfile == DOLBY_VISION_PROFILE_7 &&
                            (explicitConversion || !it.isSupported)
                    }?.let { AndroidProfile7TrackCandidate(group, track) }
            }
        }.maxWithOrNull(
            compareBy<AndroidProfile7TrackCandidate>(
                { it.group.isSelected },
                { it.track.pixelCount },
                { it.track.bitrate },
                { -it.group.index },
                { -it.track.index },
            ),
        )?.let { candidate ->
            AndroidPreDecoderProfile7Selection(
                groupIndex = candidate.group.index,
                trackIndex = candidate.track.index,
            )
        }
}

/**
 * Keeps adaptive playback within one color-signal class while retaining every bitrate in that
 * class. Media3 may otherwise adapt between SDR, HDR10 and Dolby Vision variants in one master
 * playlist, which makes an explicit dynamic-range policy impossible to honor consistently.
 */
internal fun selectAndroidDynamicRangeTracks(
    dynamicRangePolicy: DynamicRangePolicy,
    dolbyVisionPolicy: DolbyVisionPolicy,
    hdrOutputSourceRanges: Set<VideoDynamicRange>,
    groups: List<AndroidVideoTrackGroupCandidate>,
    currentDynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
): AndroidDynamicRangeTrackSelection? {
    val allTracks = groups.flatMap(AndroidVideoTrackGroupCandidate::tracks)
    val advertisedKnownRanges =
        allTracks
            .map(AndroidVideoTrackCandidate::dynamicRange)
            .filterNot { it == VideoDynamicRange.UNKNOWN }
            .toSet()
    if (advertisedKnownRanges.size < 2) return null

    val supportedTracks = allTracks.filter(AndroidVideoTrackCandidate::isSupported)
    val supportedRanges = supportedTracks.map(AndroidVideoTrackCandidate::dynamicRange).toSet()
    // With AUTO, let Media3 make the first profile/decoder decision when Dolby Vision is present.
    // Once the decoder exposes its real input format, keep that HDR class stable. This avoids
    // replacing Media3's profile-aware choice with an arbitrary DV/HEVC track override.
    val isUnresolvedAutoDolbyVision =
        dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR &&
            dolbyVisionPolicy == DolbyVisionPolicy.AUTO &&
            currentDynamicRange == VideoDynamicRange.UNKNOWN &&
            VideoDynamicRange.DOLBY_VISION in supportedRanges
    if (isUnresolvedAutoDolbyVision && hdrOutputSourceRanges.isNotEmpty()) {
        return null
    }
    val hdrPreference =
        dolbyVisionPolicy.hdrTrackPreference(
            currentDynamicRange = currentDynamicRange,
        )
    val compatibleHdrRange = hdrPreference.firstOrNull { it in supportedRanges && it in hdrOutputSourceRanges }
    val supportedHdrRange = hdrPreference.firstOrNull { it in supportedRanges }
    val targetRange =
        when (dynamicRangePolicy) {
            DynamicRangePolicy.AUTO ->
                compatibleHdrRange
                    ?: VideoDynamicRange.SDR.takeIf { it in supportedRanges }
                    ?: supportedHdrRange

            DynamicRangePolicy.PREFER_HDR,
            DynamicRangePolicy.REQUIRE_HDR,
            -> compatibleHdrRange ?: supportedHdrRange

            DynamicRangePolicy.FORCE_SDR ->
                VideoDynamicRange.SDR.takeIf { it in supportedRanges }
                    ?: supportedHdrRange
        } ?: return null

    val selectedGroup =
        groups
            .mapNotNull { group ->
                val indices =
                    group.tracks
                        .filter { track -> track.isSupported && track.dynamicRange == targetRange }
                        .map(AndroidVideoTrackCandidate::index)
                if (indices.isEmpty()) null else AndroidTrackGroupSelectionCandidate(group, indices)
            }.maxWithOrNull(
                compareBy<AndroidTrackGroupSelectionCandidate>(
                    { it.group.isSelected },
                    { it.indices.size },
                    { it.maximumPixelCount },
                    { it.maximumBitrate },
                    { -it.group.index },
                ),
            ) ?: return null

    return AndroidDynamicRangeTrackSelection(
        groupIndex = selectedGroup.group.index,
        trackIndices = selectedGroup.indices,
        dynamicRange = targetRange,
    )
}

private data class AndroidTrackGroupSelectionCandidate(
    val group: AndroidVideoTrackGroupCandidate,
    val indices: List<Int>,
) {
    private val selectedTracks: List<AndroidVideoTrackCandidate>
        get() = group.tracks.filter { it.index in indices }

    val maximumPixelCount: Long
        get() = selectedTracks.maxOfOrNull(AndroidVideoTrackCandidate::pixelCount) ?: 0

    val maximumBitrate: Int
        get() = selectedTracks.maxOfOrNull(AndroidVideoTrackCandidate::bitrate) ?: 0
}

private data class AndroidProfile7TrackCandidate(
    val group: AndroidVideoTrackGroupCandidate,
    val track: AndroidVideoTrackCandidate,
)

private fun DolbyVisionPolicy.hdrTrackPreference(currentDynamicRange: VideoDynamicRange): List<VideoDynamicRange> =
    when (this) {
        DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER ->
            listOf(
                VideoDynamicRange.HDR10_PLUS,
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HLG,
                VideoDynamicRange.DOLBY_VISION,
            )

        DolbyVisionPolicy.REQUIRE_NATIVE -> listOf(VideoDynamicRange.DOLBY_VISION)
        DolbyVisionPolicy.AUTO ->
            buildList {
                if (currentDynamicRange.isHdrSourceRange()) add(currentDynamicRange)
                add(VideoDynamicRange.HDR10_PLUS)
                add(VideoDynamicRange.HDR10)
                add(VideoDynamicRange.HLG)
                add(VideoDynamicRange.DOLBY_VISION)
            }.distinct()

        DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1 ->
            listOf(
                VideoDynamicRange.DOLBY_VISION,
                VideoDynamicRange.HDR10_PLUS,
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HLG,
            )
    }

private fun VideoDynamicRange.isHdrSourceRange(): Boolean =
    this == VideoDynamicRange.HDR10 ||
        this == VideoDynamicRange.HDR10_PLUS ||
        this == VideoDynamicRange.HLG ||
        this == VideoDynamicRange.DOLBY_VISION

private const val DOLBY_VISION_PROFILE_7 = 7
