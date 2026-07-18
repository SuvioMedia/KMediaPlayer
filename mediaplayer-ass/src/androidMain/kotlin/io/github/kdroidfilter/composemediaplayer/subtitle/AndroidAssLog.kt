package io.github.kdroidfilter.composemediaplayer.subtitle

import android.util.Log

internal inline fun logAndroidAssError(message: () -> String) {
    Log.e("KMediaPlayerAss", message())
}
