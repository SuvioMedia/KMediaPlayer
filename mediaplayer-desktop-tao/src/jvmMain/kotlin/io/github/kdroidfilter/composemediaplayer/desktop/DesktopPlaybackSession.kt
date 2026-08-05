package io.github.kdroidfilter.composemediaplayer.desktop

import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackendInfo
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewControlMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Observable lifecycle of one explicit desktop playback session. */
public sealed interface DesktopPlaybackSessionState {
    public data object Idle : DesktopPlaybackSessionState

    public data class Opening(
        public val backendId: String,
    ) : DesktopPlaybackSessionState

    public data class Ready(
        public val backend: VideoPlayerBackendInfo,
    ) : DesktopPlaybackSessionState

    public data class Switching(
        public val fromBackendId: String,
        public val toBackendId: String,
    ) : DesktopPlaybackSessionState

    public data class Failed(
        public val backendId: String?,
        public val message: String,
    ) : DesktopPlaybackSessionState

    public data object Closed : DesktopPlaybackSessionState
}

/**
 * Owns exactly one full-size desktop player and switches backend transactionally.
 *
 * A replacement is created and restored while paused. The previous backend is disposed only
 * after the replacement reports a usable media state; a failed replacement resumes the previous
 * player. Opening this session closes any other full-size session in the same process.
 */
public class DesktopPlaybackSession(
    backends: List<DesktopPlaybackBackend>,
    private val readyTimeout: Duration = DEFAULT_READY_TIMEOUT,
    private val seekableMediaDataSourceFactory: JvmSeekableMediaDataSourceFactory? = null,
    private val mediaCacheDirectory: Path? = null,
    private val maxMaterializedSourceBytes: Long = DEFAULT_MAX_MATERIALIZED_SOURCE_BYTES,
) : Closeable {
    private val backendsById: Map<String, DesktopPlaybackBackend>
    private val orderedBackends: List<DesktopPlaybackBackend>
    private val operationMutex: Mutex = Mutex()
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val generation: AtomicLong = AtomicLong(0L)
    private val mutablePlayerState: MutableStateFlow<VideoPlayerState?> = MutableStateFlow(null)
    private val mutableSessionState: MutableStateFlow<DesktopPlaybackSessionState> =
        MutableStateFlow(DesktopPlaybackSessionState.Idle)
    private val retiredPlayerLock: Any = Any()
    private val retiredPlayers: MutableList<VideoPlayerState> = mutableListOf()
    private val ownedSourceLock: Any = Any()
    private val ownedSources: IdentityHashMap<VideoPlayerState, OwnedMaterializedSource> = IdentityHashMap()
    private var activeBackend: DesktopPlaybackBackend? = null
    private var activeRequest: DesktopPlaybackRequest? = null

    init {
        require(backends.isNotEmpty()) { "At least one desktop playback backend is required." }
        val duplicateIds =
            backends
                .groupingBy { it.info.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicateIds.isEmpty()) { "Desktop backend ids must be unique: ${duplicateIds.sorted()}." }
        require(readyTimeout.isFinite() && readyTimeout.isPositive()) { "readyTimeout must be positive and finite." }
        require(maxMaterializedSourceBytes > 0L) { "maxMaterializedSourceBytes must be positive." }
        backendsById = backends.associateBy { it.info.id }
        orderedBackends = backends.sortedBy(DesktopPlaybackBackend::routingTier)
    }

    public val playerState: StateFlow<VideoPlayerState?> = mutablePlayerState.asStateFlow()
    public val state: StateFlow<DesktopPlaybackSessionState> = mutableSessionState.asStateFlow()
    public val backendIds: Set<String> = backendsById.keys

    /** Opens [request], selecting the first successful automatic route unless [backendId] is explicit. */
    public suspend fun open(
        request: DesktopPlaybackRequest,
        backendId: String? = null,
    ): VideoPlayerState =
        operationMutex.withLock {
            ensureOpen()
            val operationGeneration = generation.incrementAndGet()
            ActiveDesktopPlaybackSession.claim(this)
            val candidates =
                try {
                    candidates(request, backendId)
                } catch (failure: DesktopPlaybackOpenException) {
                    mutableSessionState.value =
                        DesktopPlaybackSessionState.Failed(
                            backendId = backendId,
                            message = failure.message.orEmpty(),
                        )
                    throw failure
                }
            val previous = mutablePlayerState.value
            val previousBackend = activeBackend
            val previousRequest = activeRequest
            val bookmark = previous?.captureBookmark()
            previous?.pause()

            for (backend in candidates) {
                mutableSessionState.value =
                    if (previousBackend == null) {
                        DesktopPlaybackSessionState.Opening(backend.info.id)
                    } else {
                        DesktopPlaybackSessionState.Switching(previousBackend.info.id, backend.info.id)
                    }
                val candidate =
                    try {
                        createPreparedPlayer(backend, request, bookmark)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                if (candidate != null) {
                    if (closed.get() || generation.get() != operationGeneration) {
                        releasePlayer(candidate)
                        throw IllegalStateException("The desktop playback operation was superseded or closed.")
                    }
                    mutablePlayerState.value = candidate
                    activeBackend = backend
                    activeRequest = request
                    previous?.takeUnless { it === candidate }?.let { retired ->
                        synchronized(retiredPlayerLock) {
                            retiredPlayers += retired
                        }
                    }
                    if (bookmark?.wasPlaying == true ||
                        (bookmark == null && request.initialPlayerState == InitialPlayerState.PLAY)
                    ) {
                        candidate.play()
                    }
                    mutableSessionState.value = DesktopPlaybackSessionState.Ready(backend.info)
                    return@withLock candidate
                }
            }

            activeBackend = previousBackend
            activeRequest = previousRequest
            mutablePlayerState.value = previous
            if (bookmark?.wasPlaying == true) previous.play()
            val failedBackendId = backendId ?: candidates.lastOrNull()?.info?.id
            mutableSessionState.value =
                DesktopPlaybackSessionState.Failed(
                    backendId = failedBackendId,
                    message = "No selected desktop backend could open the media source.",
                )
            throw DesktopPlaybackOpenException(failedBackendId)
        }

    /**
     * Switches the current source while preserving playback state. A `null` [backendId] reruns the
     * ordered automatic route; a non-null id forces one backend and rolls back if it cannot open.
     */
    public suspend fun switchBackend(backendId: String? = null): VideoPlayerState {
        val request = activeRequest ?: throw IllegalStateException("No desktop media source is open.")
        return open(request = request, backendId = backendId)
    }

    /**
     * Confirms that [player] has been composed in its new surface. Only then are paused renderers
     * retired by the preceding transaction released, which prevents native detach/dispose races.
     */
    public fun notifySurfaceAttached(player: VideoPlayerState) {
        if (mutablePlayerState.value !== player) return
        releaseRetiredPlayers()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        ActiveDesktopPlaybackSession.release(this)
        val player = mutablePlayerState.value
        mutablePlayerState.value = null
        activeBackend = null
        activeRequest = null
        runCatching { player?.pause() }
        player?.let(::releasePlayer)
        releaseRetiredPlayers()
        mutableSessionState.value = DesktopPlaybackSessionState.Closed
    }

    private fun candidates(
        request: DesktopPlaybackRequest,
        backendId: String?,
    ): List<DesktopPlaybackBackend> {
        val requested =
            backendId?.let { id ->
                listOf(backendsById[id] ?: throw IllegalArgumentException("Unknown desktop backend id: $id"))
            } ?: orderedBackends.filter(DesktopPlaybackBackend::automaticSelection)
        return requested
            .filter { backend ->
                backend.inspectAvailability() is DesktopBackendAvailability.Available &&
                    (
                        backend.probe(request) is DesktopBackendProbeResult.Supported ||
                            canMaterializeForMpv(backend, request)
                    )
            }.also { candidates ->
                if (candidates.isEmpty()) {
                    throw DesktopPlaybackOpenException(backendId)
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun createPreparedPlayer(
        backend: DesktopPlaybackBackend,
        request: DesktopPlaybackRequest,
        bookmark: DesktopPlaybackBookmark?,
    ): VideoPlayerState {
        val preparedRequest = prepareRequestForBackend(backend, request)
        val candidate = backend.createPlayerState()
        try {
            bookmark?.applyStaticState(candidate)
            candidate.openSource(
                source = preparedRequest.request.source,
                initializePlayerState = InitialPlayerState.PAUSE,
                requestHeaders = preparedRequest.request.requestHeaders,
            )
            awaitReady(candidate)
            bookmark?.restoreAfterOpen(candidate)
            preparedRequest.materializedSource?.let { owned ->
                synchronized(ownedSourceLock) {
                    ownedSources[candidate] = owned
                }
            }
            return candidate
        } catch (failure: Exception) {
            runCatching(candidate::releaseSource)
            runCatching(candidate::dispose)
            preparedRequest.materializedSource?.close()
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareRequestForBackend(
        backend: DesktopPlaybackBackend,
        request: DesktopPlaybackRequest,
    ): PreparedDesktopRequest {
        if (!canMaterializeForMpv(backend, request)) return PreparedDesktopRequest(request)
        val factory = checkNotNull(seekableMediaDataSourceFactory)
        val dataSource = factory.open(request)
        val suffix = request.source.uri.safeMediaSuffix()
        val path =
            withContext(Dispatchers.IO) {
                mediaCacheDirectory?.let(Files::createDirectories)
                if (mediaCacheDirectory == null) {
                    Files.createTempFile(MATERIALIZED_FILE_PREFIX, suffix)
                } else {
                    Files.createTempFile(mediaCacheDirectory, MATERIALIZED_FILE_PREFIX, suffix)
                }
            }
        val owned = OwnedMaterializedSource(dataSource, path)
        try {
            materializeSource(dataSource, path)
            return PreparedDesktopRequest(
                request =
                    request.copy(
                        source = request.source.copy(uri = path.toUri().toString()),
                        requestHeaders = emptyMap(),
                    ),
                materializedSource = owned,
            )
        } catch (failure: Exception) {
            owned.close()
            throw failure
        }
    }

    private suspend fun materializeSource(
        source: JvmSeekableMediaDataSource,
        path: Path,
    ) {
        source.length?.let { length ->
            require(length in 0..maxMaterializedSourceBytes) {
                "The credential-safe media source exceeds the configured cache bound."
            }
        }
        withContext(Dispatchers.IO) {
            Files
                .newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { output ->
                    val buffer = ByteBuffer.allocateDirect(MATERIALIZED_READ_BUFFER_BYTES)
                    var position = 0L
                    var emptyReads = 0
                    var reachedEnd = false
                    while (!reachedEnd) {
                        buffer.clear()
                        val read = source.read(position, buffer)
                        when {
                            read < 0 -> reachedEnd = true
                            read == 0 -> {
                                emptyReads++
                                check(emptyReads <= MAX_CONSECUTIVE_EMPTY_READS) {
                                    "The credential-safe media source stopped making progress."
                                }
                            }
                            else -> {
                                emptyReads = 0
                                check(read <= buffer.position()) { "The media source returned an invalid byte count." }
                                position += read
                                check(position <= maxMaterializedSourceBytes) {
                                    "The credential-safe media source exceeds the configured cache bound."
                                }
                                buffer.flip()
                                buffer.limit(read)
                                while (buffer.hasRemaining()) output.write(buffer)
                            }
                        }
                    }
                }
        }
    }

    private fun canMaterializeForMpv(
        backend: DesktopPlaybackBackend,
        request: DesktopPlaybackRequest,
    ): Boolean =
        backend.routingTier == DesktopBackendRoutingTier.MPV_NATIVE &&
            seekableMediaDataSourceFactory != null &&
            request.source.isProgressiveRemoteSource()

    private suspend fun awaitReady(player: VideoPlayerState) {
        val deadline = System.nanoTime() + readyTimeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            player.error?.let { throw IllegalStateException("The desktop backend rejected the media source.") }
            if (player.hasMedia && !player.isLoading) return
            delay(READY_POLL_INTERVAL)
        }
        throw IllegalStateException("The desktop backend did not become ready in time.")
    }

    private fun ensureOpen() {
        check(!closed.get()) { "The desktop playback session is closed." }
    }

    private fun releaseRetiredPlayers() {
        val retired =
            synchronized(retiredPlayerLock) {
                retiredPlayers.toList().also { retiredPlayers.clear() }
            }
        retired.forEach { player ->
            releasePlayer(player)
        }
    }

    private fun releasePlayer(player: VideoPlayerState) {
        runCatching(player::releaseSource)
        runCatching(player::dispose)
        val source =
            synchronized(ownedSourceLock) {
                ownedSources.remove(player)
            }
        source?.close()
    }

    public companion object {
        public val DEFAULT_READY_TIMEOUT: Duration = 15_000.milliseconds
        public const val DEFAULT_MAX_MATERIALIZED_SOURCE_BYTES: Long = 8L * 1024L * 1024L * 1024L
        private val READY_POLL_INTERVAL: Duration = 20.milliseconds
    }
}

private data class PreparedDesktopRequest(
    val request: DesktopPlaybackRequest,
    val materializedSource: OwnedMaterializedSource? = null,
)

private class OwnedMaterializedSource(
    private val source: JvmSeekableMediaDataSource,
    private val path: Path,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching(source::close)
        runCatching { Files.deleteIfExists(path) }
    }
}

private fun io.github.kdroidfilter.composemediaplayer.MediaSourceSpec.isProgressiveRemoteSource(): Boolean {
    val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val cleanUri = uri.substringBefore('?').substringBefore('#').lowercase()
    val remote = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)
    val adaptive =
        cleanUri.endsWith(".m3u8") ||
            cleanUri.endsWith(".mpd") ||
            normalizedMime == "application/vnd.apple.mpegurl" ||
            normalizedMime == "application/x-mpegurl" ||
            normalizedMime == "application/dash+xml"
    return remote && !adaptive
}

private fun String.safeMediaSuffix(): String {
    val cleanName = substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('\\')
    val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (extension.matches(Regex("[a-z0-9]{1,8}"))) ".$extension" else ".media"
}

private const val MATERIALIZED_FILE_PREFIX = "composemediaplayer-secure-"
private const val MATERIALIZED_READ_BUFFER_BYTES = 256 * 1024
private const val MAX_CONSECUTIVE_EMPTY_READS = 8

/** Failure intentionally omitting the source URI, request headers and native error text. */
public class DesktopPlaybackOpenException internal constructor(
    public val backendId: String?,
) : IllegalStateException(
        if (backendId == null) {
            "No available desktop backend could open the media source."
        } else {
            "Desktop backend '$backendId' could not open the media source."
        },
    )

private data class DesktopPlaybackBookmark(
    val position: Duration,
    val wasPlaying: Boolean,
    val volume: Float,
    val loop: Boolean,
    val speed: Float,
    val audioTrack: AudioTrack?,
    val subtitleTrack: SubtitleTrack?,
    val subtitlesEnabled: Boolean,
    val externalSubtitleTracks: List<SubtitleTrack>,
    val subtitleOffset: Duration,
    val projection: VideoProjectionSettings,
    val projectionView: VideoProjectionViewSettings,
    val projectionViewControlMode: VideoProjectionViewControlMode,
    val projectionTextureCrop: VideoTextureCrop,
) {
    fun applyStaticState(player: VideoPlayerState) {
        player.volume = volume
        player.loop = loop
        player.playbackSpeed = speed
        player.subtitleOffset = subtitleOffset
        player.projection = projection
        player.projectionView = projectionView
        player.projectionViewControlMode = projectionViewControlMode
        player.projectionTextureCrop = projectionTextureCrop
    }

    fun restoreAfterOpen(player: VideoPlayerState) {
        player.replaceExternalSubtitleTracks(externalSubtitleTracks)
        if (position > Duration.ZERO) player.seekTo(position)
        audioTrack?.let { previous ->
            player.availableAudioTracks.matching(previous)?.let(player::selectAudioTrack)
        }
        if (subtitlesEnabled) {
            subtitleTrack?.let { previous ->
                player.availableSubtitleTracks.matching(previous)?.let(player::selectSubtitleTrack)
            }
        } else {
            player.disableSubtitles()
        }
    }
}

private fun VideoPlayerState.captureBookmark(): DesktopPlaybackBookmark =
    DesktopPlaybackBookmark(
        position = preciseCurrentTime,
        wasPlaying = isPlaying,
        volume = volume,
        loop = loop,
        speed = playbackSpeed,
        audioTrack = currentAudioTrack,
        subtitleTrack = currentSubtitleTrack,
        subtitlesEnabled = subtitlesEnabled,
        externalSubtitleTracks = availableSubtitleTracks.filter(SubtitleTrack::isExternal),
        subtitleOffset = subtitleOffset,
        projection = projection,
        projectionView = projectionView,
        projectionViewControlMode = projectionViewControlMode,
        projectionTextureCrop = projectionTextureCrop,
    )

private fun List<AudioTrack>.matching(previous: AudioTrack): AudioTrack? =
    firstOrNull { it.id == previous.id }
        ?: firstOrNull { it.language == previous.language && it.label == previous.label }

private fun List<SubtitleTrack>.matching(previous: SubtitleTrack): SubtitleTrack? =
    firstOrNull { it.id == previous.id }
        ?: firstOrNull { it.language == previous.language && it.label == previous.label }

private object ActiveDesktopPlaybackSession {
    private var active: DesktopPlaybackSession? = null

    @Synchronized
    fun claim(session: DesktopPlaybackSession) {
        val previous = active
        if (previous === session) return
        active = session
        previous?.close()
    }

    @Synchronized
    fun release(session: DesktopPlaybackSession) {
        if (active === session) active = null
    }
}
