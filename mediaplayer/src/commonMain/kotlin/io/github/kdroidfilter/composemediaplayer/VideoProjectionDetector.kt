@file:Suppress("ComplexCondition", "CyclomaticComplexMethod", "MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

data class VideoProjectionDetectionInput(
    val title: String,
    val url: String = "",
    val labels: List<String> = emptyList(),
    val metadata: List<String> = emptyList(),
    val videoSizes: List<VideoProjectionVideoSize> = emptyList(),
)

data class VideoProjectionVideoSize(
    val width: Int,
    val height: Int,
)

data class VideoProjectionDetection(
    val projection: VideoProjectionSettings,
    val tokens: Set<String>,
    val confidence: VideoProjectionDetectionConfidence,
)

enum class VideoProjectionDetectionConfidence {
    None,
    Low,
    High,
}

fun detectVideoProjection(input: VideoProjectionDetectionInput): VideoProjectionDetection {
    val text = input.searchText()
    val videoSizes = input.videoSizes.filter { size -> size.width > 0 && size.height > 0 }
    val rawTokens =
        VideoProjectionToken.entries
            .filter { token -> token.matches(text) }
            .map(VideoProjectionToken::label)
            .toSet()
    val projectionType = detectProjectionType(text)
    val stereoLayout = detectStereoLayout(text, projectionType, videoSizes)
    val projection =
        VideoProjectionSettings(
            projectionType = projectionType,
            stereoLayout = if (VideoProjectionToken.TwoD.matches(text)) VideoStereoLayout.Mono else stereoLayout,
            eyeOrder = detectEyeOrder(text),
            fovDegrees = projectionType.defaultFovDegrees(),
        )
    val inferredTokens = rawTokens + inferredStereoToken(text, projectionType, stereoLayout, videoSizes)
    val confidence = detectionConfidence(inferredTokens, projection)

    return VideoProjectionDetection(
        projection = projection,
        tokens = if (confidence == VideoProjectionDetectionConfidence.None) emptySet() else inferredTokens,
        confidence = confidence,
    )
}

private fun VideoProjectionDetectionInput.searchText(): String {
    val rawText = (listOf(title, url.urlDetectionHint()) + labels + metadata).joinToString(separator = " ")
    val decodedText = rawText.percentDecoded()
    return if (decodedText == rawText) {
        rawText.uppercase()
    } else {
        "$rawText $decodedText".uppercase()
    }
}

private fun String.urlDetectionHint(): String {
    val value = trim()
    return when {
        value.isBlank() -> ""

        !value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true) -> value

        else -> {
            val withoutFragment = value.substringBefore('#')
            val queryFilename =
                withoutFragment
                    .substringAfter('?', missingDelimiterValue = "")
                    .queryParameterValue("filename")
            val pathSegment =
                withoutFragment
                    .substringBefore('?')
                    .substringAfterLast('/')
            queryFilename?.takeIf(String::isNotBlank)
                ?: pathSegment.takeIf { segment -> '.' in segment }.orEmpty()
        }
    }
}

private fun String.queryParameterValue(name: String): String? =
    split('&')
        .firstNotNullOfOrNull { parameter ->
            val key = parameter.substringBefore('=', missingDelimiterValue = "")
            if (key.percentDecoded().equals(name, ignoreCase = true)) {
                parameter.substringAfter('=', missingDelimiterValue = "")
            } else {
                null
            }
        }?.takeIf(String::isNotBlank)

private fun String.percentDecoded(): String {
    if ('%' !in this) return this

    val decoded = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val high = this[index + 1].hexDigitToIntOrNull()
            val low = this[index + 2].hexDigitToIntOrNull()
            if (high != null && low != null) {
                decoded.append(((high shl 4) + low).toChar())
                index += 3
                continue
            }
        }
        decoded.append(char)
        index++
    }
    return decoded.toString()
}

private fun Char.hexDigitToIntOrNull(): Int? =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + HEX_DIGIT_OFFSET
        in 'A'..'F' -> this - 'A' + HEX_DIGIT_OFFSET
        else -> null
    }

private fun detectProjectionType(text: String): VideoProjectionType =
    when {
        VideoProjectionToken.Mkx200.matches(text) -> VideoProjectionType.Fisheye200

        VideoProjectionToken.Mkx22.matches(text) ||
            VideoProjectionToken.Vrca220.matches(text) -> VideoProjectionType.Fisheye220

        VideoProjectionToken.Fisheye.matches(text) && VideoProjectionToken.Numeric220.matches(text) -> {
            VideoProjectionType.Fisheye220
        }

        VideoProjectionToken.Fisheye.matches(text) && VideoProjectionToken.Numeric200.matches(text) -> {
            VideoProjectionType.Fisheye200
        }

        VideoProjectionToken.Fisheye190.matches(text) ||
            VideoProjectionToken.Rf52.matches(text) ||
            (VideoProjectionToken.Fisheye.matches(text) && VideoProjectionToken.Numeric190.matches(text)) -> {
            VideoProjectionType.Fisheye190
        }

        VideoProjectionToken.Fisheye.matches(text) ||
            VideoProjectionToken.F180.matches(text) ||
            VideoProjectionToken.F180Reverse.matches(text) -> VideoProjectionType.Fisheye180

        VideoProjectionToken.Eac.matches(text) ||
            VideoProjectionToken.Eac360.matches(text) ||
            VideoProjectionToken.Eac360Reverse.matches(text) -> VideoProjectionType.Eac360

        VideoProjectionToken.Equirect.matches(text) &&
            (VideoProjectionToken.Numeric360.matches(text) || VideoProjectionToken.Angle360.matches(text)) -> {
            VideoProjectionType.Equirect360
        }

        VideoProjectionToken.Vr360.matches(text) ||
            VideoProjectionToken.Vr360Split.matches(text) ||
            VideoProjectionToken.Angle360.matches(text) ||
            (VideoProjectionToken.Numeric360.matches(text) && hasNumericProjectionContext(text)) -> {
            VideoProjectionType.Equirect360
        }

        VideoProjectionToken.Equirect.matches(text) &&
            (VideoProjectionToken.Numeric180.matches(text) || VideoProjectionToken.Angle180.matches(text)) -> {
            VideoProjectionType.Equirect180
        }

        hasDelimitedFisheye180StereoToken(text) -> VideoProjectionType.Fisheye180

        VideoProjectionToken.Vr180.matches(text) ||
            VideoProjectionToken.Vr180Split.matches(text) ||
            VideoProjectionToken.ThreeD180.matches(text) ||
            VideoProjectionToken.Angle180.matches(text) ||
            (VideoProjectionToken.Numeric180.matches(text) && hasNumericProjectionContext(text)) -> {
            VideoProjectionType.Equirect180
        }

        else -> VideoProjectionType.Flat
    }

private fun detectStereoLayout(
    text: String,
    projectionType: VideoProjectionType,
    videoSizes: List<VideoProjectionVideoSize>,
): VideoStereoLayout =
    when {
        hasOverUnderToken(text) ||
            VideoProjectionToken.ThreeDVertical.matches(text) -> VideoStereoLayout.OverUnder

        hasSideBySideToken(text) ||
            VideoProjectionToken.ThreeDHorizontal.matches(text) -> VideoStereoLayout.SideBySide

        (VideoProjectionToken.Lr.matches(text) || VideoProjectionToken.Rl.matches(text)) &&
            (projectionType != VideoProjectionType.Flat || VideoProjectionToken.ThreeD.matches(text)) -> {
            VideoStereoLayout.SideBySide
        }

        VideoProjectionToken.ThreeD.matches(text) &&
            projectionType != VideoProjectionType.Flat -> VideoStereoLayout.SideBySide

        else -> inferStereoLayoutFromVideoSize(projectionType, videoSizes)
    }

private fun detectEyeOrder(text: String): VideoEyeOrder =
    if (
        VideoProjectionToken.Rl.matches(text) ||
        VideoProjectionToken.SbsRl.matches(text) ||
        VideoProjectionToken.Bt.matches(text) ||
        VideoProjectionToken.BottomTop.matches(text) ||
        VideoProjectionToken.RightLeft.matches(text)
    ) {
        VideoEyeOrder.RightLeft
    } else {
        VideoEyeOrder.LeftRight
    }

private fun hasNumericProjectionContext(text: String): Boolean =
    VideoProjectionToken.Vr.matches(text) ||
        VideoProjectionToken.ThreeD.matches(text) ||
        VideoProjectionToken.Passthrough.matches(text) ||
        VideoProjectionToken.MixedReality.matches(text) ||
        hasSideBySideToken(text) ||
        hasOverUnderToken(text) ||
        VideoProjectionToken.Lr.matches(text) ||
        VideoProjectionToken.Rl.matches(text) ||
        VideoProjectionToken.RightLeft.matches(text) ||
        VideoProjectionToken.LeftRight.matches(text)

private fun hasDelimitedFisheye180StereoToken(text: String): Boolean =
    !hasSideBySideToken(text) &&
        !hasOverUnderToken(text) &&
        FISHEYE_180_STEREO_TOKEN_REGEX.containsMatchIn(text)

private fun hasSideBySideToken(text: String): Boolean =
    VideoProjectionToken.Sbs.matches(text) ||
        VideoProjectionToken.SideBySide.matches(text) ||
        VideoProjectionToken.SbsLr.matches(text) ||
        VideoProjectionToken.SbsRl.matches(text) ||
        VideoProjectionToken.HalfSbs.matches(text) ||
        VideoProjectionToken.HalfSbsDelimited.matches(text) ||
        VideoProjectionToken.FullSbs.matches(text) ||
        VideoProjectionToken.FullSbsDelimited.matches(text)

private fun hasOverUnderToken(text: String): Boolean =
    VideoProjectionToken.Ou.matches(text) ||
        VideoProjectionToken.Tb.matches(text) ||
        VideoProjectionToken.Bt.matches(text) ||
        VideoProjectionToken.TopBottom.matches(text) ||
        VideoProjectionToken.BottomTop.matches(text) ||
        VideoProjectionToken.OverUnder.matches(text) ||
        VideoProjectionToken.Tab.matches(text) ||
        VideoProjectionToken.HalfTab.matches(text) ||
        VideoProjectionToken.FullTab.matches(text)

private fun inferStereoLayoutFromVideoSize(
    projectionType: VideoProjectionType,
    videoSizes: List<VideoProjectionVideoSize>,
): VideoStereoLayout {
    val size = videoSizes.firstOrNull() ?: return VideoStereoLayout.Mono
    val aspectRatio = size.width.toFloat() / size.height.toFloat()
    return when (projectionType) {
        VideoProjectionType.Equirect360,
        VideoProjectionType.Eac360,
        ->
            when {
                aspectRatio >= 3.2f -> VideoStereoLayout.SideBySide
                aspectRatio in 0.85f..1.25f -> VideoStereoLayout.OverUnder
                else -> VideoStereoLayout.Mono
            }

        VideoProjectionType.Equirect180,
        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        ->
            when {
                aspectRatio >= 1.55f -> VideoStereoLayout.SideBySide
                aspectRatio <= 0.7f -> VideoStereoLayout.OverUnder
                else -> VideoStereoLayout.Mono
            }

        VideoProjectionType.Flat -> VideoStereoLayout.Mono
    }
}

private fun inferredStereoToken(
    text: String,
    projectionType: VideoProjectionType,
    stereoLayout: VideoStereoLayout,
    videoSizes: List<VideoProjectionVideoSize>,
): Set<String> =
    if (
        stereoLayout != VideoStereoLayout.Mono &&
        !hasSideBySideToken(text) &&
        !hasOverUnderToken(text) &&
        videoSizes.isNotEmpty() &&
        projectionType != VideoProjectionType.Flat
    ) {
        setOf("${stereoLayout.name} from size")
    } else {
        emptySet()
    }

private fun detectionConfidence(
    tokens: Set<String>,
    projection: VideoProjectionSettings,
): VideoProjectionDetectionConfidence =
    when {
        tokens.isEmpty() -> VideoProjectionDetectionConfidence.None
        projection.projectionType != VideoProjectionType.Flat -> VideoProjectionDetectionConfidence.High
        projection.stereoLayout != VideoStereoLayout.Mono -> VideoProjectionDetectionConfidence.Low
        "2D" in tokens -> VideoProjectionDetectionConfidence.Low
        else -> VideoProjectionDetectionConfidence.None
    }

private enum class VideoProjectionToken(
    val label: String,
    private val regex: Regex,
) {
    Vr("VR", wordToken("VR")),
    Vr180("VR180", wordToken("VR180")),
    Vr180Split("VR 180", compoundWordToken("VR", "180")),
    Vr360("VR360", wordToken("VR360")),
    Vr360Split("VR 360", compoundWordToken("VR", "360")),
    ThreeD180("3D180", Regex("""(?<![A-Z0-9])3D[-_ ]?180(?![A-Z0-9])""")),
    Angle180("180 degrees", angleToken("180")),
    Numeric180("180", numericToken("180")),
    Numeric190("190", numericToken("190")),
    Numeric200("200", numericToken("200")),
    Numeric220("220", numericToken("220")),
    Angle360("360 degrees", angleToken("360")),
    Numeric360("360", numericToken("360")),
    Sbs("SBS", wordToken("SBS")),
    SideBySide("Side by side", compoundWordToken("SIDE", "BY", "SIDE")),
    SbsLr("SBS-LR", stereoOrderToken("SBS", "LR")),
    SbsRl("SBS-RL", stereoOrderToken("SBS", "RL")),
    HalfSbs("HSBS", wordToken("HSBS")),
    HalfSbsDelimited("H-SBS", compoundWordToken("H", "SBS")),
    FullSbs("FSBS", wordToken("FSBS")),
    FullSbsDelimited("F-SBS", compoundWordToken("F", "SBS")),
    Lr("LR", wordToken("LR")),
    Rl("RL", wordToken("RL")),
    LeftRight("LeftRight", compoundWordToken("LEFT", "RIGHT")),
    RightLeft("RightLeft", compoundWordToken("RIGHT", "LEFT")),
    Ou("OU", wordToken("OU")),
    Tb("TB", wordToken("TB")),
    Bt("BT", wordToken("BT")),
    TopBottom("TopBottom", compoundWordToken("TOP", "BOTTOM")),
    BottomTop("BottomTop", compoundWordToken("BOTTOM", "TOP")),
    OverUnder("OverUnder", compoundWordToken("OVER", "UNDER")),
    Tab("TAB", wordToken("TAB")),
    HalfTab("HTAB", wordToken("HTAB")),
    FullTab("FTAB", wordToken("FTAB")),
    ThreeDHorizontal("3DH", wordToken("3DH")),
    ThreeDVertical("3DV", wordToken("3DV")),
    ThreeD("3D", wordToken("3D")),
    TwoD("2D", wordToken("2D")),
    F180("F180", Regex("""(?<![A-Z0-9])F[-_ ]?180(?![A-Z0-9])""")),
    F180Reverse("180F", Regex("""(?<![A-Z0-9])180[-_ ]?F(?![A-Z0-9])""")),
    Fisheye("FISHEYE", wordToken("FISHEYE")),
    Fisheye190("FISHEYE190", wordToken("FISHEYE190")),
    Rf52("RF52", wordToken("RF52")),
    Equirect("Equirect", Regex("""(?<![A-Z0-9])E(?:QUI)?RECT(?:ANGULAR)?(?![A-Z0-9])""")),
    Mkx200("MKX200", wordToken("MKX200")),
    Mkx22("MKX22", wordToken("MKX22")),
    Vrca220("VRCA220", wordToken("VRCA220")),
    Eac("EAC", wordToken("EAC")),
    Eac360("EAC360", wordToken("EAC360")),
    Eac360Reverse("360EAC", wordToken("360EAC")),
    Passthrough("Passthrough", Regex("""(?<![A-Z0-9])PASS[-_ ]?THR(?:OUGH|U)(?![A-Z0-9])""")),
    MixedReality("MR", Regex("""(?<![A-Z0-9])(?:MR|MIXED[-_ ]?REALITY)(?![A-Z0-9])""")),
    ;

    fun matches(text: String): Boolean = regex.containsMatchIn(text)
}

private fun numericToken(value: String): Regex = Regex("""(?<![A-Z0-9])$value(?![A-Z0-9])""")

private fun angleToken(value: String): Regex =
    Regex("""(?<![A-Z0-9])$value(?:\u00B0|['\u2019]|[-_ ]?DEG(?:REE)?S?)(?![A-Z0-9])""")

private fun wordToken(value: String): Regex = Regex("""(?<![A-Z0-9])$value(?![A-Z0-9])""")

private fun stereoOrderToken(
    layout: String,
    order: String,
): Regex = Regex("""(?<![A-Z0-9])$layout[-_ ]?$order(?![A-Z0-9])""")

private fun compoundWordToken(vararg parts: String): Regex =
    Regex("""(?<![A-Z0-9])${parts.joinToString("[-_ ]?")}(?![A-Z0-9])""")

private val FISHEYE_180_STEREO_TOKEN_REGEX =
    Regex(
        """(?<![A-Z0-9])(?:LR|RL)[-_ ]+180(?![A-Z0-9])|(?<![A-Z0-9])180[-_ ]+(?:LR|RL)(?![A-Z0-9])""",
    )

private const val HEX_DIGIT_OFFSET = 10
