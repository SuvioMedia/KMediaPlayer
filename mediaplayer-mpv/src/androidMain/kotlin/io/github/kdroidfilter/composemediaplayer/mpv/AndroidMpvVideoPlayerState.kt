package io.github.kdroidfilter.composemediaplayer.mpv

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.shusek.kmediampv.runtime.android.MpvAndroidPlayer
import io.github.shusek.kmediampv.runtime.android.MpvAndroidDecodeMode as RuntimeMpvAndroidDecodeMode
import io.github.shusek.kmediampv.runtime.android.MpvAndroidTrackInfo
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Android adapter for the optional KMediaMpv runtime.
 *
 * The audited KMediaMpv runtime intentionally enables local files only. Network URLs and
 * request headers are rejected instead of silently crossing that boundary.
 */
@Stable
@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
internal class AndroidMpvVideoPlayerState(
    private val context: Context,
    private val subtitleFontsDirectory: File?,
    private val decodeMode: RuntimeMpvAndroidDecodeMode,
) : AbstractMpvVideoPlayerState(),
    VideoPlayerSurfaceProvider {
    private val disposed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownedTemporaryFiles = linkedSetOf<File>()
    private val activeMediaDescriptors = linkedSetOf<ParcelFileDescriptor>()

    private val player = createPlayer()
    private var handledEndOfFile = false

    private var lastTrackRefreshMs = 0L

    override val renderingInfo =
        VideoRenderingInfo(
            backend = "KMediaMpv",
            videoRenderer = "libmpv Android Surface",
            audioRenderer = "mpv AudioTrack",
            subtitleRenderer = "mpv/libass",
            notes = "Audited KMediaMpv local-file runtime; Android API 28+ on ARM only.",
        )
    override val capabilities =
        PlayerCapabilities(
            supportsMkv = true,
            supportedUriSchemes = setOf("file", "content"),
        )

    init {
        scope.launch { pollPlayback() }
    }

    @Composable
    override fun RenderVideoPlayerSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
    ) {
        AndroidMpvVideoPlayerSurface(
            playerState = this,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
    }

    override val preciseCurrentTime: Duration
        get() {
            if (disposed.get() || !_hasMedia) return _currentTime
            return runCatching { player.playbackSnapshot().timePositionSeconds.toSafeDuration() }
                .getOrNull() ?: _currentTime
        }

    override val backendDisposed: Boolean
        get() = disposed.get()

    override fun setBackendVolume(value: Float) {
        player.setVolume(value.toDouble())
    }

    override fun setBackendLoop(value: Boolean) {
        player.setLoop(value)
    }

    override fun setBackendPlaybackSpeed(value: Float) {
        player.setSpeed(value.toDouble())
    }

    override fun setBackendSubtitleOffset(value: Duration) {
        player.setSubtitleDelay(value.inWholeMilliseconds.toDouble() / 1_000.0)
    }

    override fun playBackend() {
        player.setPaused(false)
    }

    override fun pauseBackend() {
        player.setPaused(true)
    }

    override fun seekBackend(time: Duration) {
        player.seekTo(time.inWholeMilliseconds.toDouble() / 1_000.0)
    }

    override val seekFailureMessage: String
        get() = "KMediaMpv rejected the seek request."

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureOpen()
        require(requestHeaders.isEmpty()) {
            "The audited Android KMediaMpv runtime has networking disabled and does not accept request headers."
        }
        if (_hasMedia) runCatching { player.stop() }
        closeActiveMediaDescriptors()
        val localSource = resolveMediaSource(uri)
        beginSourcePreparation(uri, initializePlayerState)
        handledEndOfFile = false
        try {
            readMetadata(localSource)
            player.loadFile(localSource)
            player.setVolume(_volume.toDouble())
            player.setSpeed(_playbackSpeed.toDouble())
            player.setLoop(_loop)
            player.setSubtitleDelay(_subtitleOffset.inWholeMilliseconds.toDouble() / 1000.0)
            player.setPaused(initializePlayerState == InitialPlayerState.PAUSE)
            externalSubtitles.values.forEach { track ->
                player.addSubtitle(
                    requireLocalSource(track.src),
                    track.id == _currentSubtitleTrack?.id && _subtitlesEnabled,
                )
            }
            refreshTracks()
            sourceLoaded()
        } catch (_: RuntimeException) {
            _hasMedia = false
            closeActiveMediaDescriptors()
            publishError(VideoPlayerError.SourceError("KMediaMpv rejected the local media source."))
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        val location =
            when (val androidFile = file.androidFile) {
                is AndroidFile.FileWrapper -> androidFile.file.absolutePath
                is AndroidFile.UriWrapper -> androidFile.uri.toString()
            }
        openUri(location, initializePlayerState)
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        ensureOpen()
        require(fileName.isNotBlank() && !fileName.startsWith('/') && ".." !in fileName.split('/')) {
            "The asset path must be relative and must not contain parent traversal."
        }
        val suffix = fileName.substringAfterLast('.', missingDelimiterValue = "bin").take(16)
        val target = File.createTempFile("composemediaplayer-mpv-", ".$suffix", context.cacheDir)
        context.assets.open(fileName).use { input ->
            target.outputStream().use(input::copyTo)
        }
        ownedTemporaryFiles += target
        openUri(target.absolutePath, initializePlayerState)
    }

    override fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult =
        try {
            if (track == null) {
                player.selectAudioTrack(null)
                TrackSelectionResult.Auto
            } else {
                val mpvId = track.id.removePrefix(AUDIO_TRACK_PREFIX).toLongOrNull()
                if (mpvId == null) {
                    TrackSelectionResult.NotFound(track.id)
                } else {
                    player.selectAudioTrack(mpvId)
                    TrackSelectionResult.Selected(track.id)
                }
            }
        } catch (_: RuntimeException) {
            TrackSelectionResult.Failed("KMediaMpv rejected the audio-track selection.")
        }

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult {
        return try {
            val mpvId =
                if (track.isExternal) {
                    val existing = findRuntimeSubtitleTrack(track)
                    if (existing == null) {
                        player.addSubtitle(requireLocalSource(track.src), true)
                        refreshTracks()
                        findRuntimeSubtitleTrack(track)?.id
                    } else {
                        existing.id
                    }
                } else {
                    track.id.removePrefix(SUBTITLE_TRACK_PREFIX).toLongOrNull()
                }
            if (mpvId == null) {
                return TrackSelectionResult.Failed("KMediaMpv could not resolve the subtitle track id.")
            }
            player.selectSubtitleTrack(mpvId)
            TrackSelectionResult.Selected(track.id)
        } catch (_: RuntimeException) {
            TrackSelectionResult.Failed("KMediaMpv rejected the external subtitle track.")
        }
    }

    override fun validateExternalSubtitle(track: SubtitleTrack) {
        requireLocalSource(track.src)
    }

    override fun removeBackendExternalSubtitle(track: SubtitleTrack) {
        findRuntimeSubtitleTrack(track)?.id?.let { runtimeId ->
            runCatching { player.removeSubtitle(runtimeId) }
        }
    }

    override fun disableBackendSubtitles(): TrackSelectionResult =
        try {
            player.selectSubtitleTrack(null)
            TrackSelectionResult.Disabled
        } catch (_: RuntimeException) {
            TrackSelectionResult.Failed("KMediaMpv could not disable subtitles.")
        }

    override fun releaseSource() {
        ensureOpen()
        runCatching { player.stop() }
        closeActiveMediaDescriptors()
        resetSourceState()
    }

    internal fun attachSurface(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        ensureOpen()
        player.attachSurface(surface, width, height)
    }

    internal fun detachSurface() {
        if (disposed.get()) return
        runCatching { player.detachSurface() }
    }

    internal fun setCropMode(crop: Boolean) {
        ensureOpen()
        player.setPanscan(if (crop) 1.0 else 0.0)
    }

    private fun createPlayer(): MpvAndroidPlayer =
        MpvAndroidPlayer.create(context, subtitleFontsDirectory, decodeMode)

    private suspend fun pollPlayback() {
        try {
            while (scope.isActive && !disposed.get()) {
                if (_hasMedia) refreshSnapshot()
                delay(POLL_INTERVAL_MS)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            if (!disposed.get()) {
                publishError(VideoPlayerError.UnknownError("KMediaMpv playback monitoring failed."))
            }
        }
    }

    private fun refreshSnapshot() {
        val snapshot = player.playbackSnapshot()
        val position = snapshot.timePositionSeconds.toSafeDuration() ?: _currentTime
        val mediaDuration = snapshot.durationSeconds.toSafeDuration() ?: _duration
        val seekCompleted = _isSeeking && snapshot.isSeekingStateKnown && !snapshot.isSeeking
        updatePlaybackPosition(
            position = position,
            mediaDuration = mediaDuration,
            playing = snapshot.isPauseStateKnown.takeIf { it }?.let { !snapshot.isPaused },
            loading = snapshot.isBufferingStateKnown.takeIf { it }?.let { snapshot.isBuffering },
            seeking = snapshot.isSeekingStateKnown.takeIf { it }?.let { snapshot.isSeeking },
        )
        Snapshot.withMutableSnapshot {
            renderingInfo.videoRenderer =
                listOfNotNull(snapshot.currentVideoOutput, snapshot.currentGpuContext)
                    .joinToString(" / ")
                    .ifBlank { "libmpv Android Surface" }
            renderingInfo.audioRenderer = snapshot.currentAudioOutput ?: "mpv AudioTrack"
            metadata.duration = mediaDuration.takeIf { it > Duration.ZERO }
            metadata.width = snapshot.videoWidth.takeIf { it > 0 } ?: metadata.width
            metadata.height = snapshot.videoHeight.takeIf { it > 0 } ?: metadata.height
        }
        updateAspectRatio(metadata.width, metadata.height)
        if (seekCompleted) {
            emitSeekCompleted(position)
        }
        val now = System.currentTimeMillis()
        if (now - lastTrackRefreshMs >= TRACK_REFRESH_INTERVAL_MS) {
            refreshTracks()
            lastTrackRefreshMs = now
        }
        if (snapshot.isEndOfFileReached && !handledEndOfFile) {
            handledEndOfFile = true
            emitPlaybackEnded()
        } else if (!snapshot.isEndOfFileReached) {
            handledEndOfFile = false
        }
    }

    private fun refreshTracks() {
        val runtimeTracks = player.tracks()
        val discoveredAudio = mutableListOf<AudioTrack>()
        val discoveredSubtitles = mutableListOf<SubtitleTrack>()
        var selectedAudio: AudioTrack? = null
        var selectedSubtitle: SubtitleTrack? = null

        runtimeTracks.forEach { runtimeTrack ->
            when (runtimeTrack.type) {
                MpvAndroidTrackInfo.Type.AUDIO -> {
                    val track =
                        AudioTrack(
                            id = "$AUDIO_TRACK_PREFIX${runtimeTrack.id}",
                            label =
                                runtimeTrack.title.ifBlank {
                                    runtimeTrack.language.ifBlank { "Audio ${runtimeTrack.id}" }
                                },
                            language = runtimeTrack.language,
                            isDefault = runtimeTrack.isDefaultTrack,
                        )
                    discoveredAudio += track
                    if (runtimeTrack.isSelected) selectedAudio = track
                }
                MpvAndroidTrackInfo.Type.SUBTITLE -> {
                    val registered =
                        runtimeTrack.externalFilename
                            .takeIf(String::isNotBlank)
                            ?.let { filename ->
                                externalSubtitles.values.firstOrNull { external ->
                                    requireLocalSource(external.src) == filename
                                }
                            }
                    val track =
                        registered ?: SubtitleTrack(
                            id = "$SUBTITLE_TRACK_PREFIX${runtimeTrack.id}",
                            label =
                                runtimeTrack.title.ifBlank {
                                    runtimeTrack.language.ifBlank { "Subtitle ${runtimeTrack.id}" }
                                },
                            language = runtimeTrack.language,
                            src =
                                runtimeTrack.externalFilename.ifBlank {
                                    "mpv://subtitle/${runtimeTrack.id}"
                                },
                            isEmbedded = !runtimeTrack.isExternal,
                        )
                    discoveredSubtitles += track
                    if (runtimeTrack.isSelected) selectedSubtitle = track
                }
            }
        }

        replaceDiscoveredTracks(
            discoveredAudio = discoveredAudio,
            discoveredSubtitles = discoveredSubtitles,
            selectedAudio = selectedAudio,
            selectedSubtitle = selectedSubtitle,
        )
    }

    private fun findRuntimeSubtitleTrack(track: SubtitleTrack): MpvAndroidTrackInfo? {
        val expectedLocation = requireLocalSource(track.src)
        return player
            .tracks()
            .firstOrNull { runtimeTrack ->
                runtimeTrack.type == MpvAndroidTrackInfo.Type.SUBTITLE &&
                    runtimeTrack.externalFilename == expectedLocation
            }
    }

    private fun readMetadata(location: String) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = Uri.parse(location)
            if (uri.scheme.equals("file", ignoreCase = true)) {
                retriever.setDataSource(requireNotNull(uri.path))
            } else {
                retriever.setDataSource(location)
            }
            _duration =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?.milliseconds ?: Duration.ZERO
            metadata.duration = _duration.takeIf { it > Duration.ZERO }
            metadata.width =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
            metadata.height =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
            metadata.title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            updateAspectRatio(metadata.width, metadata.height)
        } catch (_: RuntimeException) {
            _duration = Duration.ZERO
            metadata.duration = null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun requireLocalSource(value: String): String {
        require(value.isNotBlank() && '\u0000' !in value) {
            "The KMediaMpv source must be a non-blank local path."
        }
        val scheme = Uri.parse(value).scheme
        require(scheme == null || scheme.equals("file", ignoreCase = true)) {
            "The audited Android KMediaMpv runtime accepts local paths and file: URIs only."
        }
        return if (scheme == null) {
            value
        } else {
            File(requireNotNull(Uri.parse(value).path)).absolutePath
        }
    }

    private fun resolveMediaSource(value: String): String {
        require(value.isNotBlank() && '\u0000' !in value) {
            "The KMediaMpv source must be a non-blank local URI or path."
        }
        val uri = Uri.parse(value)
        return when {
            uri.scheme == null -> value
            uri.scheme.equals("file", ignoreCase = true) ->
                File(requireNotNull(uri.path)).absolutePath
            uri.scheme.equals("content", ignoreCase = true) -> {
                val descriptor =
                    requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
                        "The Android content provider did not expose a readable media descriptor."
                    }
                activeMediaDescriptors += descriptor
                "/proc/self/fd/${descriptor.fd}"
            }
            else ->
                throw IllegalArgumentException(
                    "The audited Android KMediaMpv runtime accepts file: and content: sources only.",
                )
        }
    }

    private fun closeActiveMediaDescriptors() {
        activeMediaDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
        activeMediaDescriptors.clear()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        scope.cancel()
        runCatching { player.close() }
        closeActiveMediaDescriptors()
        ownedTemporaryFiles.forEach { file -> runCatching { file.delete() } }
        ownedTemporaryFiles.clear()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 100L
        private const val TRACK_REFRESH_INTERVAL_MS = 1_000L
        private const val AUDIO_TRACK_PREFIX = "mpv:audio:"
        private const val SUBTITLE_TRACK_PREFIX = "mpv:subtitle:"
    }
}

private fun Double.toSafeDuration(): Duration? = takeIf { it.isFinite() && it >= 0.0 }?.seconds
