package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.application.NucleusApplicationScope

/** Application scope used to create independent Tao player windows from deep UI code. */
public val LocalDesktopVideoApplicationScope: ProvidableCompositionLocal<NucleusApplicationScope?> =
    staticCompositionLocalOf<NucleusApplicationScope?> { null }

/** Makes this Nucleus application scope available to [DesktopVideoPlayerWindow]. */
@Composable
public fun NucleusApplicationScope.ProvideDesktopVideoApplicationScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDesktopVideoApplicationScope provides this, content = content)
}
