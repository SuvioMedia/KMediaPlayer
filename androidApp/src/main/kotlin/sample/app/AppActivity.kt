package sample.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.util.UnstableApi
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionDetectionMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import io.github.kdroidfilter.composemediaplayer.defaultFovDegrees
import io.github.kdroidfilter.composemediaplayer.dolbyvision.DolbyVisionExtension
import io.github.kdroidfilter.composemediaplayer.kmediabridge.KMediaBridgeAndroidExtension
import io.github.kdroidfilter.composemediaplayer.util.ComposeMediaPlayerLoggingLevel
import io.github.kdroidfilter.composemediaplayer.util.allowComposeMediaPlayerLogging
import io.github.kdroidfilter.composemediaplayer.util.composeMediaPlayerLoggingLevel
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

@UnstableApi
class AppActivity : ComponentActivity() {
    private var kMediaBridgeSelfTest: AndroidKMediaBridgeSelfTest? = null
    private var colorPipelineSelfTest: AndroidColorPipelineSelfTest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowComposeMediaPlayerLogging = true
        composeMediaPlayerLoggingLevel = ComposeMediaPlayerLoggingLevel.DEBUG
        kMediaBridgeSelfTest = AndroidKMediaBridgeSelfTest(this)
        if (kMediaBridgeSelfTest?.startIfRequested() == true) return
        val initialVideoUrl = intent.getStringExtra(EXTRA_INITIAL_VIDEO_URL)
        val initialSubtitleUrl = intent.getStringExtra(EXTRA_INITIAL_SUBTITLE_URL)
        val demoSubtitleEnabled = intent.getBooleanExtra(EXTRA_DEMO_SUBTITLE_ENABLED, true)
        val dynamicRangePolicy =
            intent
                .getStringExtra(EXTRA_DYNAMIC_RANGE_POLICY)
                ?.let { value -> runCatching { DynamicRangePolicy.valueOf(value) }.getOrNull() }
                ?: DynamicRangePolicy.AUTO
        val dolbyVisionPolicy =
            intent
                .getStringExtra(EXTRA_DOLBY_VISION_POLICY)
                ?.let { value -> runCatching { DolbyVisionPolicy.valueOf(value) }.getOrNull() }
                ?: DolbyVisionPolicy.AUTO
        val projectionType =
            intent
                .getStringExtra(EXTRA_PROJECTION_TYPE)
                ?.let { value -> runCatching { VideoProjectionType.valueOf(value) }.getOrNull() }
        val playbackOptions =
            VideoPlaybackOptions(
                dynamicRangePolicy = dynamicRangePolicy,
                dolbyVisionPolicy = dolbyVisionPolicy,
                extensions =
                    listOf(
                        AssSubtitleExtension(),
                        DolbyVisionExtension(),
                        KMediaBridgeAndroidExtension(),
                    ),
                projection =
                    projectionType?.let { type ->
                        VideoProjectionSettings(
                            projectionType = type,
                            fovDegrees = type.defaultFovDegrees(),
                        )
                    } ?: VideoProjectionSettings(),
                projectionDetectionMode =
                    if (projectionType == null) {
                        VideoProjectionDetectionMode.AUTO
                    } else {
                        VideoProjectionDetectionMode.DISABLED
                    },
            )
        colorPipelineSelfTest = AndroidColorPipelineSelfTest(this)
        if (colorPipelineSelfTest?.startIfRequested(playbackOptions) == true) return
        FileKit.init(this)
        setContent {
            App(
                initialVideoUrl = initialVideoUrl,
                initialSubtitleUrl = initialSubtitleUrl,
                demoSubtitleEnabled = demoSubtitleEnabled,
                playbackOptions = playbackOptions,
            )
        }
    }

    override fun onDestroy() {
        kMediaBridgeSelfTest?.close()
        kMediaBridgeSelfTest = null
        super.onDestroy()
    }

    private companion object {
        const val EXTRA_INITIAL_VIDEO_URL = "sample.app.extra.INITIAL_VIDEO_URL"
        const val EXTRA_INITIAL_SUBTITLE_URL = "sample.app.extra.INITIAL_SUBTITLE_URL"
        const val EXTRA_DEMO_SUBTITLE_ENABLED = "sample.app.extra.DEMO_SUBTITLE_ENABLED"
        const val EXTRA_DYNAMIC_RANGE_POLICY = "sample.app.extra.DYNAMIC_RANGE_POLICY"
        const val EXTRA_DOLBY_VISION_POLICY = "sample.app.extra.DOLBY_VISION_POLICY"
        const val EXTRA_PROJECTION_TYPE = "sample.app.extra.PROJECTION_TYPE"
    }
}
