package io.github.kdroidfilter.composemediaplayer

import platform.Foundation.NSLock

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual class PlatformLock actual constructor() {
    private val delegate = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        delegate.lock()
        return try {
            block()
        } finally {
            delegate.unlock()
        }
    }
}
