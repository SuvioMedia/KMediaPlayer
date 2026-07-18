package io.github.kdroidfilter.composemediaplayer.subtitle

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Format
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * State-owned bridge between Media3, the external subtitle loader and the EGL overlay.
 * Native mutation happens on a worker; rasterization happens only on the overlay GL thread.
 */
internal class AndroidAssController(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val workerThread = HandlerThread("KMediaAssSession").apply(HandlerThread::start)
    private val worker = Handler(workerThread.looper)
    private val nativeLock = Any()
    private val stateLock = Any()
    private val sourceLock = Any()
    private val chunkQueueLock = Any()
    private val overlayLock = Any()
    private val nextToken = AtomicLong(0L)
    private val released = AtomicBoolean(false)
    private val forceNextFrame = AtomicBoolean(true)

    private var target = Target(token = 0L, mode = Mode.NONE)

    @Volatile
    private var overlay = WeakReference<AndroidAssTextureView>(null)

    @Volatile
    private var mediaPositionUs = 0L

    @Volatile
    private var timelineVisible = false

    @Volatile
    private var subtitleOffsetUs = 0L

    @Volatile
    private var storageWidth = 0

    @Volatile
    private var storageHeight = 0

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    private var activeSession: ActiveSession? = null
    private var overlaySurfaceToken: Any? = null

    private var sourceToken = 0L
    private val embeddedFonts = mutableListOf<AndroidAssFontAttachment>()
    private val embeddedFontKeys = mutableSetOf<FontKey>()
    private var embeddedFontBytes = 0
    private var failedEmbeddedSessionToken = INVALID_TOKEN
    private var queuedChunkCount = 0
    private var queuedChunkBytes = 0

    fun beginSource(token: Long) {
        synchronized(sourceLock) {
            sourceToken = token
            embeddedFonts.clear()
            embeddedFontKeys.clear()
            embeddedFontBytes = 0
        }
        storageWidth = 0
        storageHeight = 0
        mediaPositionUs = 0L
        timelineVisible = false
        requestRender()
    }

    fun createFontAttachmentSink(): (AndroidAssFontAttachment) -> Unit {
        val expectedSourceToken = synchronized(sourceLock) { sourceToken }
        return { attachment -> addFontAttachment(expectedSourceToken, attachment) }
    }

    fun activateEmbedded(format: Format): Long {
        if (released.get()) return INVALID_TOKEN
        val target = newTarget(Mode.EMBEDDED, format)
        worker.post {
            if (!target.isCurrent()) return@post
            failedEmbeddedSessionToken = INVALID_TOKEN
            synchronized(nativeLock) {
                activeSession?.session?.close()
                activeSession = null
            }
            requestRender()
        }
        return target.token
    }

    fun resetEmbedded(
        token: Long,
        format: Format,
    ): Long {
        if (!isCurrent(token, Mode.EMBEDDED)) return INVALID_TOKEN
        val target = newTarget(Mode.EMBEDDED, format)
        worker.post {
            if (!target.isCurrent()) return@post
            failedEmbeddedSessionToken = INVALID_TOKEN
            synchronized(nativeLock) {
                activeSession?.session?.close()
                activeSession = null
            }
            requestRender()
        }
        return target.token
    }

    fun appendEmbeddedChunk(
        token: Long,
        startMs: Long,
        chunk: AndroidAssChunk,
    ): Boolean {
        if (!isCurrent(token, Mode.EMBEDDED) || !reserveChunk(chunk.payload.size)) return false
        val expectedTarget =
            synchronized(stateLock) { target.takeIf { it.token == token && it.mode == Mode.EMBEDDED } }
                ?: run {
                    releaseChunk(chunk.payload.size)
                    return false
                }
        val accepted =
            worker.post {
                try {
                    if (!expectedTarget.isCurrent()) return@post
                    if (failedEmbeddedSessionToken == token) return@post
                    val format = expectedTarget.format ?: return@post
                    runCatching {
                        synchronized(nativeLock) {
                            if (!expectedTarget.isCurrent()) return@synchronized
                            val existing = activeSession
                            val session =
                                if (existing?.target == expectedTarget) {
                                    existing.session
                                } else {
                                    existing?.session?.close()
                                    AndroidAssNativeSession
                                        .embedded(applicationContext, format, snapshotEmbeddedFonts())
                                        .also { created -> activeSession = ActiveSession(expectedTarget, created) }
                                }
                            session.appendChunk(startMs, chunk)
                        }
                    }.onSuccess {
                        requestRender()
                    }.onFailure { throwable ->
                        failedEmbeddedSessionToken = token
                        synchronized(nativeLock) {
                            activeSession
                                ?.takeIf { it.target == expectedTarget }
                                ?.session
                                ?.close()
                            if (activeSession?.target == expectedTarget) activeSession = null
                        }
                        logAndroidAssError {
                            "Failed to initialize embedded ASS/SSA rendering: " +
                                (throwable.message ?: throwable::class.simpleName)
                        }
                        requestRender()
                    }
                } finally {
                    releaseChunk(chunk.payload.size)
                }
            }
        if (!accepted) releaseChunk(chunk.payload.size)
        return accepted
    }

    private fun reserveChunk(size: Int): Boolean =
        synchronized(chunkQueueLock) {
            if (queuedChunkCount >= MAX_QUEUED_CHUNKS || queuedChunkBytes > MAX_QUEUED_CHUNK_BYTES - size) {
                false
            } else {
                queuedChunkCount += 1
                queuedChunkBytes += size
                true
            }
        }

    private fun releaseChunk(size: Int) {
        synchronized(chunkQueueLock) {
            queuedChunkCount = (queuedChunkCount - 1).coerceAtLeast(0)
            queuedChunkBytes = (queuedChunkBytes - size).coerceAtLeast(0)
        }
    }

    fun activateExternal(script: ByteArray): Long {
        if (released.get()) return INVALID_TOKEN
        val target = newTarget(Mode.EXTERNAL)
        worker.post {
            if (!target.isCurrent()) return@post
            replaceSessionCatching(target, "external ASS/SSA") {
                AndroidAssNativeSession.external(applicationContext, script)
            }
        }
        return target.token
    }

    fun deactivateEmbedded(token: Long) {
        if (!isCurrent(token, Mode.EMBEDDED)) return
        deactivate()
    }

    fun deactivate() {
        if (released.get()) return
        val target = newTarget(Mode.NONE)
        worker.post {
            if (!target.isCurrent()) return@post
            synchronized(nativeLock) {
                activeSession?.session?.close()
                activeSession = null
            }
            requestRender()
        }
    }

    fun updateVideoSize(
        width: Int,
        height: Int,
    ) {
        val newWidth = width.coerceAtLeast(0)
        val newHeight = height.coerceAtLeast(0)
        if (storageWidth == newWidth && storageHeight == newHeight) return
        storageWidth = newWidth
        storageHeight = newHeight
        requestRender()
    }

    fun updateFrameSize(
        view: AndroidAssTextureView,
        surfaceToken: Any,
        width: Int,
        height: Int,
    ) {
        val newWidth = width.coerceAtLeast(0)
        val newHeight = height.coerceAtLeast(0)
        val changed =
            synchronized(overlayLock) {
                if (
                    overlay.get() !== view ||
                    overlaySurfaceToken !== surfaceToken ||
                    (
                        frameWidth == newWidth &&
                            frameHeight == newHeight
                    )
                ) {
                    false
                } else {
                    frameWidth = newWidth
                    frameHeight = newHeight
                    true
                }
            }
        if (!changed) return
        requestRender()
    }

    fun updateMediaPositionUs(positionUs: Long) {
        val newPositionUs = positionUs.coerceAtLeast(0L)
        if (timelineVisible && mediaPositionUs == newPositionUs) return
        mediaPositionUs = newPositionUs
        timelineVisible = true
        requestRender()
    }

    fun hideTimeline() {
        if (!timelineVisible) return
        timelineVisible = false
        requestRender()
    }

    fun updateSubtitleOffsetUs(offsetUs: Long) {
        if (subtitleOffsetUs == offsetUs) return
        subtitleOffsetUs = offsetUs
        requestRender()
    }

    fun attachOverlay(view: AndroidAssTextureView) {
        synchronized(overlayLock) {
            if (overlay.get() !== view) {
                overlay = WeakReference(view)
                overlaySurfaceToken = null
                frameWidth = 0
                frameHeight = 0
                forceNextFrame.set(true)
            }
        }
        view.requestRender(effectivePositionUs())
    }

    fun attachOverlaySurface(
        view: AndroidAssTextureView,
        surfaceToken: Any,
    ): Boolean =
        synchronized(overlayLock) {
            if (overlay.get() !== view || overlaySurfaceToken === surfaceToken) {
                false
            } else {
                overlaySurfaceToken = surfaceToken
                frameWidth = 0
                frameHeight = 0
                forceNextFrame.set(true)
                true
            }
        }

    fun detachOverlay(view: AndroidAssTextureView) {
        synchronized(overlayLock) {
            if (overlay.get() === view) {
                overlay = WeakReference(null)
                overlaySurfaceToken = null
                frameWidth = 0
                frameHeight = 0
            }
        }
    }

    fun invalidateRenderState() {
        forceNextFrame.set(true)
        requestRender()
    }

    fun <T> withRenderFrame(
        positionUs: Long,
        force: Boolean,
        consume: (AndroidAssRenderFrame) -> T,
    ): T {
        if (!timelineVisible || positionUs < 0L) return consume(AndroidAssRenderFrame.Empty)
        // JNI owns the direct RGBA buffer until the next render/reallocation/close, so the
        // consumer (the GL texture upload) must finish while nativeLock is still held.
        return synchronized(nativeLock) {
            val frame =
                activeSession?.takeIf { it.target.isCurrent() }?.let { active ->
                    val session = active.session
                    session.configure(
                        storageWidth = storageWidth.takeIf { it > 0 } ?: frameWidth,
                        storageHeight = storageHeight.takeIf { it > 0 } ?: frameHeight,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                    )
                    session.renderFrame(
                        positionMs = positionUs / MICROS_PER_MILLISECOND,
                        force = force || forceNextFrame.getAndSet(false),
                    )
                } ?: AndroidAssRenderFrame.Empty
            consume(frame)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(stateLock) {
            target = Target(nextToken.incrementAndGet(), Mode.NONE)
        }
        synchronized(overlayLock) {
            overlay.clear()
            overlaySurfaceToken = null
            frameWidth = 0
            frameHeight = 0
        }
        worker.removeCallbacksAndMessages(null)

        val cleanupPosted =
            worker.post {
                synchronized(nativeLock) {
                    activeSession?.session?.close()
                    activeSession = null
                }
                workerThread.quitSafely()
            }
        if (!cleanupPosted) {
            logAndroidAssError { "Could not dispatch Android libass cleanup to its worker thread." }
            synchronized(nativeLock) {
                activeSession?.session?.close()
                activeSession = null
            }
            workerThread.quitSafely()
        }
    }

    private fun replaceSessionCatching(
        expectedTarget: Target,
        description: String,
        create: () -> AndroidAssNativeSession,
    ) {
        runCatching(create)
            .onSuccess { newSession ->
                if (!expectedTarget.isCurrent()) {
                    newSession.close()
                    return@onSuccess
                }
                synchronized(nativeLock) {
                    if (!expectedTarget.isCurrent()) {
                        newSession.close()
                        return@synchronized
                    }
                    activeSession?.session?.close()
                    activeSession = ActiveSession(expectedTarget, newSession)
                }
                requestRender()
            }.onFailure { throwable ->
                if (!expectedTarget.isCurrent()) return@onFailure
                synchronized(nativeLock) {
                    activeSession
                        ?.takeIf { it.target == expectedTarget }
                        ?.session
                        ?.close()
                    if (activeSession?.target == expectedTarget) activeSession = null
                }
                logAndroidAssError {
                    "Failed to initialize $description rendering: ${throwable.message ?: throwable::class.simpleName}"
                }
                requestRender()
            }
    }

    private fun addFontAttachment(
        expectedSourceToken: Long,
        attachment: AndroidAssFontAttachment,
    ) {
        if (released.get()) return
        val accepted =
            synchronized(sourceLock) {
                if (!canAcceptFontAttachmentLocked(expectedSourceToken, attachment)) return@synchronized false
                val key =
                    FontKey(
                        name = attachment.name.lowercase(),
                        size = attachment.data.size,
                        contentHash = attachment.data.contentHashCode(),
                    )
                if (!embeddedFontKeys.add(key)) return@synchronized false
                embeddedFonts += attachment
                embeddedFontBytes += attachment.data.size
                true
            }
        if (!accepted) return

        worker.post {
            if (expectedSourceToken != synchronized(sourceLock) { sourceToken }) return@post
            synchronized(nativeLock) {
                activeSession
                    ?.takeIf { it.target.isCurrent() && it.target.mode == Mode.EMBEDDED }
                    ?.session
                    ?.apply {
                        addFont(attachment.name, attachment.data)
                    }
            }
            forceNextFrame.set(true)
            requestRender()
        }
    }

    private fun canAcceptFontAttachmentLocked(
        expectedSourceToken: Long,
        attachment: AndroidAssFontAttachment,
    ): Boolean {
        if (expectedSourceToken != sourceToken) return false
        val size = attachment.data.size
        if (size !in 1..MAX_EMBEDDED_FONT_BYTES) return false
        if (embeddedFonts.size >= MAX_EMBEDDED_FONT_COUNT) return false
        return embeddedFontBytes <= MAX_EMBEDDED_FONTS_TOTAL_BYTES - size
    }

    private fun snapshotEmbeddedFonts(): List<AndroidAssFontAttachment> =
        synchronized(sourceLock) { embeddedFonts.toList() }

    private fun newTarget(
        mode: Mode,
        format: Format? = null,
    ): Target {
        val newTarget =
            synchronized(stateLock) {
                Target(
                    token = nextToken.incrementAndGet(),
                    mode = mode,
                    format = format,
                ).also { target = it }
            }
        forceNextFrame.set(true)
        requestRender()
        return newTarget
    }

    private fun Target.isCurrent(): Boolean = !released.get() && synchronized(stateLock) { this == target }

    private fun isCurrent(
        token: Long,
        mode: Mode,
    ): Boolean = !released.get() && synchronized(stateLock) { target.token == token && target.mode == mode }

    private fun requestRender() {
        overlay.get()?.requestRender(effectivePositionUs())
    }

    private fun effectivePositionUs(): Long =
        when {
            subtitleOffsetUs > 0L && mediaPositionUs > Long.MAX_VALUE - subtitleOffsetUs -> Long.MAX_VALUE
            subtitleOffsetUs < 0L && mediaPositionUs < Long.MIN_VALUE - subtitleOffsetUs -> Long.MIN_VALUE
            else -> mediaPositionUs + subtitleOffsetUs
        }

    private enum class Mode {
        NONE,
        EMBEDDED,
        EXTERNAL,
    }

    private data class Target(
        val token: Long,
        val mode: Mode,
        val format: Format? = null,
    )

    private data class ActiveSession(
        val target: Target,
        val session: AndroidAssNativeSession,
    )

    private data class FontKey(
        val name: String,
        val size: Int,
        val contentHash: Int,
    )

    private companion object {
        const val INVALID_TOKEN = -1L
        const val MICROS_PER_MILLISECOND = 1_000L
        const val MAX_QUEUED_CHUNKS = 256
        const val MAX_QUEUED_CHUNK_BYTES = 8 * 1024 * 1024
    }
}
