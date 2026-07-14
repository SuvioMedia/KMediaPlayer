@file:Suppress("MatchingDeclarationName")

package io.github.kdroidfilter.composemediaplayer

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual class PlatformLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = block()
}
