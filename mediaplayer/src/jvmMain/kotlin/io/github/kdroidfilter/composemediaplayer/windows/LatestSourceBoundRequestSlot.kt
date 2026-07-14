package io.github.kdroidfilter.composemediaplayer.windows

import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot latest-wins handoff for requests tied to a source generation.
 *
 * Every publication gets an identity token. A consumer may clear only its own publication, so an
 * older consumer finishing after a newer publish cannot erase the newer request. Taking a request
 * uses compare-and-set and leaves publications for other source generations untouched.
 */
internal class LatestSourceBoundRequestSlot<T> {
    internal class Publication<T> internal constructor(
        internal val sourceGeneration: Long,
        internal val value: T,
    )

    private val pending = AtomicReference<Publication<T>?>(null)

    fun publish(
        sourceGeneration: Long,
        value: T,
    ): Publication<T> = Publication(sourceGeneration, value).also(pending::set)

    fun take(sourceGeneration: Long): T? {
        while (true) {
            val publication = pending.get() ?: return null
            if (publication.sourceGeneration != sourceGeneration) return null
            if (pending.compareAndSet(publication, null)) return publication.value
        }
    }

    fun clear(publication: Publication<T>) {
        pending.compareAndSet(publication, null)
    }
}
