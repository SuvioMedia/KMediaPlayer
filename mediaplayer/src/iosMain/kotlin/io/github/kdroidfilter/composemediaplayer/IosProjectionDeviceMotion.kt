@file:OptIn(ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import kotlin.math.PI

private val iosProjectionMotionLogger = TaggedLogger("iOSProjectionDeviceMotion")

@Composable
internal fun IosProjectionDeviceMotionEffect(
    playerState: VideoPlayerState,
    enabled: Boolean,
) {
    val projection = playerState.projection
    val useDeviceMotion = enabled && playerState.projectionViewControlMode.usesDeviceMotionFor(projection)

    DisposableEffect(playerState, projection, useDeviceMotion) {
        if (!useDeviceMotion) {
            return@DisposableEffect onDispose { }
        }

        val motionManager = CMMotionManager()
        if (!motionManager.deviceMotionAvailable) {
            iosProjectionMotionLogger.w {
                "Device motion projection control requested, but CoreMotion device motion is unavailable."
            }
            return@DisposableEffect onDispose { }
        }

        motionManager.deviceMotionUpdateInterval = IOS_PROJECTION_MOTION_UPDATE_INTERVAL_SECONDS
        var baseYawDegrees: Float? = null
        motionManager.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { motion, _ ->
            val attitude = motion?.attitude ?: return@startDeviceMotionUpdatesToQueue
            val rawYawDegrees = -attitude.yaw.toDegrees()
            val yawOrigin = baseYawDegrees ?: rawYawDegrees.also { baseYawDegrees = it }
            playerState.projectionView =
                playerState.projectionView.copy(
                    yawDegrees = rawYawDegrees - yawOrigin,
                    pitchDegrees = attitude.pitch.toDegrees(),
                    rollDegrees = -attitude.roll.toDegrees(),
                )
        }

        onDispose {
            motionManager.stopDeviceMotionUpdates()
        }
    }
}

private fun Double.toDegrees(): Float = (this * RADIANS_TO_DEGREES / PI).toFloat()

private const val IOS_PROJECTION_MOTION_UPDATE_INTERVAL_SECONDS = 1.0 / 60.0
private const val RADIANS_TO_DEGREES = 180.0
