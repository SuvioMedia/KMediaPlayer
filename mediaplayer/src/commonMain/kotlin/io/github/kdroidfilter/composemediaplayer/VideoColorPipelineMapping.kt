package io.github.kdroidfilter.composemediaplayer

@Suppress("ComplexCondition")
internal fun VideoColorInfo.profile7To81MappingOrNull(output: VideoColorInfo): DolbyVisionProfileMapping? {
    val inputDolbyVision = dolbyVision ?: return null
    val outputDolbyVision = output.dolbyVision ?: return null
    if (
        dynamicRange != VideoDynamicRange.DOLBY_VISION ||
        output.dynamicRange != VideoDynamicRange.DOLBY_VISION ||
        inputDolbyVision.profile != DOLBY_VISION_PROFILE_7 ||
        outputDolbyVision.profile != DOLBY_VISION_PROFILE_8 ||
        !outputDolbyVision.hasHdr10CompatibleBaseLayer
    ) {
        return null
    }
    return DolbyVisionProfileMapping(
        sourceProfile = inputDolbyVision.profile,
        outputProfile = DOLBY_VISION_PROFILE_8,
        outputHasHdr10CompatibleBaseLayer = true,
        enhancementLayerDiscarded =
            when (inputDolbyVision.enhancementLayer) {
                DolbyVisionEnhancementLayer.MEL,
                DolbyVisionEnhancementLayer.FEL,
                -> true
                DolbyVisionEnhancementLayer.NONE -> false
                DolbyVisionEnhancementLayer.UNKNOWN -> null
            },
        felMappingDiscarded =
            when (inputDolbyVision.enhancementLayer) {
                DolbyVisionEnhancementLayer.FEL -> true
                DolbyVisionEnhancementLayer.MEL,
                DolbyVisionEnhancementLayer.NONE,
                -> false
                DolbyVisionEnhancementLayer.UNKNOWN -> null
            },
    )
}

private const val DOLBY_VISION_PROFILE_7 = 7
private const val DOLBY_VISION_PROFILE_8 = 8
