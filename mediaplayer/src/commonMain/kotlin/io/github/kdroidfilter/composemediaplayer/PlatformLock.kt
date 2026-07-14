package io.github.kdroidfilter.composemediaplayer

/** Small non-suspending lock used only for short cross-platform state transitions. */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
