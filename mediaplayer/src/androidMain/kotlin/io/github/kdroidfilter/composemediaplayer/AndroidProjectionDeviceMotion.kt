package io.github.kdroidfilter.composemediaplayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI

@Composable
internal fun AndroidProjectionDeviceMotionEffect(playerState: VideoPlayerState) {
    val context = LocalContext.current
    val projection = playerState.projection
    val enabled = playerState.projectionViewControlMode.usesDeviceMotionFor(projection)

    DisposableEffect(context, playerState, projection, enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor =
            sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensorManager == null || sensor == null) {
            androidVideoLogger.w {
                "Device motion projection control requested, but no rotation vector sensor is available."
            }
            return@DisposableEffect onDispose { }
        }

        val rotationMatrix = FloatArray(ROTATION_MATRIX_SIZE)
        val displayAdjustedMatrix = FloatArray(ROTATION_MATRIX_SIZE)
        val orientation = FloatArray(ORIENTATION_VALUES_SIZE)
        var baseYawDegrees: Float? = null

        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    remapCoordinateSystemForDisplay(
                        inputMatrix = rotationMatrix,
                        outputMatrix = displayAdjustedMatrix,
                        rotation = context.displayRotation(),
                    )
                    SensorManager.getOrientation(displayAdjustedMatrix, orientation)

                    val rawYawDegrees = -orientation[AZIMUTH_INDEX].toDegrees()
                    val yawOrigin = baseYawDegrees ?: rawYawDegrees.also { baseYawDegrees = it }
                    playerState.projectionView =
                        playerState.projectionView.copy(
                            yawDegrees = rawYawDegrees - yawOrigin,
                            pitchDegrees = orientation[PITCH_INDEX].toDegrees(),
                            rollDegrees = -orientation[ROLL_INDEX].toDegrees(),
                        )
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
}

private fun remapCoordinateSystemForDisplay(
    inputMatrix: FloatArray,
    outputMatrix: FloatArray,
    rotation: Int,
) {
    val (axisX, axisY) =
        when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    SensorManager.remapCoordinateSystem(inputMatrix, axisX, axisY, outputMatrix)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Context.displayRotation(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display.rotation
    } else {
        @Suppress("DEPRECATION")
        findActivity()?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

private fun Float.toDegrees(): Float = this * RADIANS_TO_DEGREES / PI.toFloat()

private const val ROTATION_MATRIX_SIZE = 9
private const val ORIENTATION_VALUES_SIZE = 3
private const val AZIMUTH_INDEX = 0
private const val PITCH_INDEX = 1
private const val ROLL_INDEX = 2
private const val RADIANS_TO_DEGREES = 180f
