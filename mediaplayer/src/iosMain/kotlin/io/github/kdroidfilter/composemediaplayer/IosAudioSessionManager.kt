@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategorySoloAmbient
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSRecursiveLock

private val iosAudioSessionGlobalLock = NSRecursiveLock()

/**
 * Process-wide policy for AVAudioSession ownership.
 *
 * Set [automaticManagementEnabled] before creating a player when the host application owns
 * AVAudioSession configuration itself. Automatic management is enabled by default for backwards
 * compatibility.
 */
object IosAudioSessionPolicy {
    private val lock = iosAudioSessionGlobalLock
    private var enabled = true

    var automaticManagementEnabled: Boolean
        get() = lock.withLock { enabled }
        set(value) {
            lock.withLock { enabled = value }
        }
}

/**
 * Coordinates the process-global AVAudioSession across multiple player instances.
 *
 * AVAudioSession is not a per-player resource. A lease prevents one player from deactivating the
 * session while another player is still active. If active players request different policies, the
 * most restrictive interruption mode wins.
 */
@Suppress("TooGenericExceptionCaught")
internal object IosAudioSessionManager {
    private val lock = iosAudioSessionGlobalLock
    private val activeLeases = mutableMapOf<Long, AudioMode>()
    private var nextLeaseId = 1L

    fun acquire(
        existingLeaseId: Long?,
        audioMode: AudioMode,
    ): Long? =
        lock.withLock {
            if (!IosAudioSessionPolicy.automaticManagementEnabled) {
                releaseLocked(existingLeaseId)
                return@withLock null
            }

            val leaseId = existingLeaseId ?: nextLeaseId++
            activeLeases[leaseId] = audioMode
            applyEffectiveModeLocked()
            leaseId
        }

    fun release(leaseId: Long?) {
        if (leaseId == null) return
        lock.withLock { releaseLocked(leaseId) }
    }

    internal val activeLeaseCount: Int
        get() = lock.withLock { activeLeases.size }

    private fun releaseLocked(leaseId: Long?) {
        if (leaseId == null) return
        activeLeases.remove(leaseId)

        if (activeLeases.isEmpty()) {
            try {
                AVAudioSession.sharedInstance().setActive(false, error = null)
            } catch (e: Exception) {
                iosAudioSessionLogger.e { "Failed to deactivate audio session: ${e.message}" }
            }
        } else {
            applyEffectiveModeLocked()
        }
    }

    private fun applyEffectiveModeLocked() {
        val modes = activeLeases.values
        if (modes.isEmpty()) return

        val playsInSilentMode = modes.any(AudioMode::playsInSilentMode)
        val interruptionMode =
            when {
                modes.any { it.interruptionMode == InterruptionMode.DoNotMix } -> InterruptionMode.DoNotMix
                modes.any { it.interruptionMode == InterruptionMode.DuckOthers } -> InterruptionMode.DuckOthers
                else -> InterruptionMode.MixWithOthers
            }

        val category =
            if (playsInSilentMode) {
                AVAudioSessionCategoryPlayback
            } else if (interruptionMode == InterruptionMode.DoNotMix) {
                AVAudioSessionCategorySoloAmbient
            } else {
                AVAudioSessionCategoryAmbient
            }
        val mode = if (playsInSilentMode) AVAudioSessionModeMoviePlayback else AVAudioSessionModeDefault
        val options: ULong =
            when (interruptionMode) {
                InterruptionMode.DoNotMix -> 0u
                InterruptionMode.MixWithOthers -> AVAudioSessionCategoryOptionMixWithOthers
                InterruptionMode.DuckOthers ->
                    AVAudioSessionCategoryOptionMixWithOthers or AVAudioSessionCategoryOptionDuckOthers
            }

        try {
            AVAudioSession.sharedInstance().apply {
                setCategory(category, mode = mode, options = options, error = null)
                setActive(true, error = null)
            }
        } catch (e: Exception) {
            iosAudioSessionLogger.e { "Failed to configure audio session: ${e.message}" }
        }
    }
}

private inline fun <T> NSRecursiveLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}

private val iosAudioSessionLogger = TaggedLogger("IosAudioSessionManager")
