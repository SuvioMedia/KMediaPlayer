package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.media3.common.MimeTypes
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack

internal val SubtitleTrack.usesAndroidLibass: Boolean
    get() = AndroidAssNativeBridge.isAvailable && isAndroidLibassCandidate

internal val SubtitleTrack.isAndroidLibassCandidate: Boolean
    get() =
        if (isEmbedded) {
            kind == MimeTypes.TEXT_SSA && format.isAssFamily
        } else {
            resolvedFormat().isAssFamily || kind == MimeTypes.TEXT_SSA
        }
