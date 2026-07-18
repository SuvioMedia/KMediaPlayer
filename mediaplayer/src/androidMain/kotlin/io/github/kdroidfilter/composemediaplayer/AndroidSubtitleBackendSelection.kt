package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal fun VideoPlaybackOptions.createAndroidSubtitleBackend(context: Context): AndroidSubtitleBackend? {
    extensions
        .filterIsInstance<AndroidSubtitlePipelineExtension>()
        .filter { extension -> extension.availability.canContribute }
        .forEach { extension ->
            val backend =
                runCatching { extension.createAndroidSubtitleBackend(context) }
                    .onFailure { throwable ->
                        androidVideoLogger.e {
                            "Subtitle extension '${extension.id}' failed to initialize: " +
                                (throwable.message ?: throwable::class.simpleName)
                        }
                    }.getOrNull()
                    ?: return@forEach
            if (backend.isAvailable) return backend
            backend.release()
        }
    return null
}
