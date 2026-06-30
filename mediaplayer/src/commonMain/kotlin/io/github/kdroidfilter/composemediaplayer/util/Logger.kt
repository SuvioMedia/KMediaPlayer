package io.github.kdroidfilter.composemediaplayer.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Logging level hierarchy for ComposeMediaPlayer internal logging.
 */
enum class ComposeMediaPlayerLoggingLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** Global switch — set to `true` to enable ComposeMediaPlayer internal logging. */
var allowComposeMediaPlayerLogging: Boolean = false

/** Minimum severity to emit. Messages below this level are discarded. */
var composeMediaPlayerLoggingLevel: ComposeMediaPlayerLoggingLevel =
    ComposeMediaPlayerLoggingLevel.VERBOSE

/** Receives formatted ComposeMediaPlayer log lines. Override to forward logs to an app logger. */
var composeMediaPlayerLogSink: (String) -> Unit = { line -> println(line) }

private fun getCurrentTimestamp(): String {
    val now =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${now.date} ${now.hour.pad()}:${now.minute.pad()}:${now.second.pad()}" +
        ".${(now.nanosecond / 1_000_000).pad(3)}"
}

private fun Int.pad(len: Int = 2): String = toString().padStart(len, '0')

// -- Tagged logger ----------------------------------------------------------

internal class TaggedLogger(
    private val tag: String,
) {
    fun v(message: () -> String) = verboseln { "[$tag] ${message()}" }

    fun d(message: () -> String) = debugln { "[$tag] ${message()}" }

    fun i(message: () -> String) = infoln { "[$tag] ${message()}" }

    fun w(message: () -> String) = warnln { "[$tag] ${message()}" }

    fun e(message: () -> String) = errorln { "[$tag] ${message()}" }
}

// -- Top-level logging functions --------------------------------------------

internal fun verboseln(message: () -> String) = logln(ComposeMediaPlayerLoggingLevel.VERBOSE, message)

internal fun debugln(message: () -> String) = logln(ComposeMediaPlayerLoggingLevel.DEBUG, message)

internal fun infoln(message: () -> String) = logln(ComposeMediaPlayerLoggingLevel.INFO, message)

internal fun warnln(message: () -> String) = logln(ComposeMediaPlayerLoggingLevel.WARN, message)

internal fun errorln(message: () -> String) = logln(ComposeMediaPlayerLoggingLevel.ERROR, message)

private fun logln(
    level: ComposeMediaPlayerLoggingLevel,
    message: () -> String,
) {
    if (!allowComposeMediaPlayerLogging || composeMediaPlayerLoggingLevel > level) return
    composeMediaPlayerLogSink("[${getCurrentTimestamp()}] ${level.marker}: ${message()}")
}

private val ComposeMediaPlayerLoggingLevel.marker: String
    get() =
        when (this) {
            ComposeMediaPlayerLoggingLevel.VERBOSE -> "V"
            ComposeMediaPlayerLoggingLevel.DEBUG -> "D"
            ComposeMediaPlayerLoggingLevel.INFO -> "I"
            ComposeMediaPlayerLoggingLevel.WARN -> "W"
            ComposeMediaPlayerLoggingLevel.ERROR -> "E"
        }
