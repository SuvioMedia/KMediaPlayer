package io.github.kdroidfilter.composemediaplayer.util

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Formats a given time into either "HH:MM:SS" (if hours > 0) or "MM:SS".
 */
internal fun formatTime(value: Duration): String {
    val duration = if (value < Duration.ZERO) Duration.ZERO else value

    return duration.toComponents { hours, minutes, seconds, _ ->
        val paddedMinutes = minutes.toString().padStart(2, '0')
        val paddedSeconds = seconds.toString().padStart(2, '0')

        if (hours > 0) {
            "${hours.toString().padStart(2, '0')}:$paddedMinutes:$paddedSeconds"
        } else {
            "$paddedMinutes:$paddedSeconds"
        }
    }
}

internal fun Double.secondsAsDuration(): Duration =
    if (isFinite() && this > 0.0) {
        toDuration(DurationUnit.SECONDS)
    } else {
        Duration.ZERO
    }

internal fun Float.secondsAsDuration(): Duration = toDouble().secondsAsDuration()

internal fun Long.millisecondsAsDuration(): Duration =
    if (this > 0L) {
        toDuration(DurationUnit.MILLISECONDS)
    } else {
        Duration.ZERO
    }

internal fun Long.nanosecondsAsDuration(): Duration =
    if (this > 0L) {
        toDuration(DurationUnit.NANOSECONDS)
    } else {
        Duration.ZERO
    }

internal fun Long.hundredNanosecondsAsDuration(): Duration =
    if (this > 0L) {
        (this * 100L).toDuration(DurationUnit.NANOSECONDS)
    } else {
        Duration.ZERO
    }

internal fun Duration.inWhole100NanosecondTicks(): Long = inWholeNanoseconds / 100L

internal fun Duration.toSecondsDouble(): Double = toDouble(DurationUnit.SECONDS)
