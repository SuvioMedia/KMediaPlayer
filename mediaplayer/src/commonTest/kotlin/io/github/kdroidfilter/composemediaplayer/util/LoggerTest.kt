package io.github.kdroidfilter.composemediaplayer.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerTest {
    @Test
    fun disabledLoggingDoesNotEvaluateMessage() =
        withLoggerState {
            allowComposeMediaPlayerLogging = false
            var evaluated = false

            errorln {
                evaluated = true
                "expensive message"
            }

            assertEquals(false, evaluated)
        }

    @Test
    fun loggingLevelFiltersMessagesBeforeEvaluation() =
        withLoggerState {
            allowComposeMediaPlayerLogging = true
            composeMediaPlayerLoggingLevel = ComposeMediaPlayerLoggingLevel.WARN
            val lines = mutableListOf<String>()
            composeMediaPlayerLogSink = lines::add
            var debugEvaluated = false

            debugln {
                debugEvaluated = true
                "debug"
            }
            warnln { "warn" }
            errorln { "error" }

            assertEquals(false, debugEvaluated)
            assertEquals(2, lines.size)
            assertTrue(lines[0].endsWith("W: warn"))
            assertTrue(lines[1].endsWith("E: error"))
        }

    private fun withLoggerState(block: () -> Unit) {
        val previousAllow = allowComposeMediaPlayerLogging
        val previousLevel = composeMediaPlayerLoggingLevel
        val previousSink = composeMediaPlayerLogSink
        try {
            block()
        } finally {
            allowComposeMediaPlayerLogging = previousAllow
            composeMediaPlayerLoggingLevel = previousLevel
            composeMediaPlayerLogSink = previousSink
        }
    }
}
