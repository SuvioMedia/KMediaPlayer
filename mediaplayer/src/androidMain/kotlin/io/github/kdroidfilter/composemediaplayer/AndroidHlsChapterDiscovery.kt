@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("LoopWithTooManyJumpStatements")

package io.github.kdroidfilter.composemediaplayer

import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Coordinates Apple's HLS chapter sidecar with the media playlist timeline.
 *
 * A chapter JSON reference alone is not enough: the rows are published only after Media3 has
 * parsed a VOD playlist, an EVENT playlist, or a playlist carrying ENDLIST. This deliberately
 * excludes sliding-window live playlists.
 */
internal class AndroidHlsChapterDiscovery(
    private val dataSourceFactory: DataSource.Factory,
    private val scope: CoroutineScope,
    private val onRows: (List<RawMediaChapter>) -> Unit,
) {
    private val lock = Any()
    private var chapterJsonUri: Uri? = null
    private var variantPlaylistUris: Set<Uri> = emptySet()
    private var stableTimelineObserved = false
    private var loadJob: Job? = null
    private var cancelled = false

    fun observeMultivariantPlaylist(
        uri: Uri,
        manifestText: String,
        variableDefinitions: Map<String, String>,
        variants: Set<Uri>,
    ) {
        synchronized(lock) {
            if (cancelled) return
            chapterJsonUri =
                parseHlsChapterJsonUri(
                    masterPlaylistUri = uri,
                    manifestText = manifestText,
                    variableDefinitions = variableDefinitions,
                )
            variantPlaylistUris = variants
        }
        maybeLoad()
    }

    fun observeMediaPlaylist(
        uri: Uri,
        playlist: HlsMediaPlaylist,
    ) {
        val stable =
            playlist.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_VOD ||
                playlist.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_EVENT ||
                playlist.hasEndTag
        if (!stable) return
        synchronized(lock) {
            if (cancelled) return
            if (variantPlaylistUris.isNotEmpty() && uri !in variantPlaylistUris) return
            stableTimelineObserved = true
        }
        maybeLoad()
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            loadJob?.cancel()
            loadJob = null
        }
    }

    private fun maybeLoad() {
        val uri =
            synchronized(lock) {
                if (cancelled || !stableTimelineObserved || loadJob != null) return
                chapterJsonUri ?: return
            }
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val rows =
                    runCatching {
                        dataSourceFactory
                            .createDataSource()
                            .readBoundedText(uri, MAX_HLS_CHAPTER_JSON_BYTES)
                            ?.let { json ->
                                parseAndroidHlsChapterJson(
                                    json = json,
                                    preferredLanguages = preferredAndroidChapterLanguages(),
                                )
                            }.orEmpty()
                    }.getOrDefault(emptyList())
                withContext(Dispatchers.Main.immediate) {
                    onRows(rows)
                }
            }
        val shouldStart =
            synchronized(lock) {
                if (!cancelled && loadJob == null) {
                    loadJob = job
                    true
                } else {
                    job.cancel()
                    false
                }
            }
        if (shouldStart) job.start()
    }
}

internal fun parseAndroidHlsChapterJson(
    json: String,
    preferredLanguages: List<String>,
): List<RawMediaChapter> {
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val startSeconds = (entry.opt("start-time") as? Number)?.toDouble() ?: continue
            val startMs = startSeconds.toNonNegativeMillisecondsOrNull() ?: continue
            val durationSeconds = (entry.opt("duration") as? Number)?.toDouble()
            val endMs =
                durationSeconds
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { duration ->
                        (startSeconds + duration)
                            .toNonNegativeMillisecondsOrNull()
                            ?.takeIf { it > startMs }
                    }
            val labels =
                entry
                    .optJSONArray("titles")
                    ?.let { titles ->
                        buildList {
                            for (titleIndex in 0 until titles.length()) {
                                val title = titles.optJSONObject(titleIndex) ?: continue
                                val text = title.optString("title").trim()
                                if (text.isEmpty()) continue
                                add(
                                    MediaChapterLabel(
                                        text = text,
                                        language = title.optString("language").trim().ifEmpty { null },
                                    ),
                                )
                            }
                        }
                    }.orEmpty()
            val selectedLabel = selectPreferredChapterLabel(labels, preferredLanguages)
            add(
                RawMediaChapter(
                    startMs = startMs,
                    endMs = endMs,
                    title = selectedLabel?.text,
                    language = selectedLabel?.language,
                ),
            )
        }
    }
}

private fun DataSource.readBoundedText(
    uri: Uri,
    maximumBytes: Int,
): String? =
    DataSourceInputStream(this, DataSpec(uri)).use { input ->
        val bytes = input.readAtMost(maximumBytes + 1)
        if (bytes.size > maximumBytes) null else bytes.toString(Charsets.UTF_8)
    }

private fun InputStream.readAtMost(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, READ_BUFFER_BYTES))
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var remaining = maximumBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        if (read > 0) {
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
    return output.toByteArray()
}

private fun preferredAndroidChapterLanguages(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        LocaleList
            .getDefault()
            .let { locales -> (0 until locales.size()).map { index -> locales[index].toLanguageTag() } }
    } else {
        @Suppress("DEPRECATION")
        listOf(Locale.getDefault().toLanguageTag())
    }

private fun Double.toNonNegativeMillisecondsOrNull(): Long? {
    val milliseconds = this * MILLISECONDS_PER_SECOND
    if (!milliseconds.isFinite() || milliseconds < 0.0 || milliseconds > Long.MAX_VALUE.toDouble()) return null
    return milliseconds.roundToLong()
}

private const val MAX_HLS_CHAPTER_JSON_BYTES = 4 * 1024 * 1024
private const val READ_BUFFER_BYTES = 16 * 1024
private const val MILLISECONDS_PER_SECOND = 1_000.0
