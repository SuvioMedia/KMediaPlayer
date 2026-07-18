package io.github.kdroidfilter.composemediaplayer.consumer.extensions

import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionStatus
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import io.github.kdroidfilter.composemediaplayer.dolbyvision.DolbyVisionExtension

fun publishedCommonExtensionStatuses(): List<VideoPipelineExtensionStatus> =
    listOf(
        AssSubtitleExtension().status(),
        DolbyVisionExtension().status(),
    )
