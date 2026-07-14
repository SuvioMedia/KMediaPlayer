package io.github.kdroidfilter.composemediaplayer

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual class PlatformLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = delegate.withLock(block)
}
