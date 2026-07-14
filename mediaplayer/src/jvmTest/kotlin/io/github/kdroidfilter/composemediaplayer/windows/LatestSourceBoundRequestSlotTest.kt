package io.github.kdroidfilter.composemediaplayer.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatestSourceBoundRequestSlotTest {
    @Test
    fun olderConsumerFinallyCannotClearRequestPublishedAfterItObservedEmpty() {
        val slot = LatestSourceBoundRequestSlot<String>()
        val generation = 7L
        val olderPublication = slot.publish(generation, "older")

        assertEquals("older", slot.take(generation))
        assertNull(slot.take(generation)) // Older consumer decides to leave its drain loop.

        slot.publish(generation, "latest") // New seek arrives before the older consumer's finally.
        slot.clear(olderPublication)

        assertEquals("latest", slot.take(generation))
    }

    @Test
    fun staleGenerationConsumerCannotTakeOrClearNewGenerationRequest() {
        val slot = LatestSourceBoundRequestSlot<String>()
        val stalePublication = slot.publish(3L, "stale")

        assertEquals("stale", slot.take(3L))
        slot.publish(4L, "current")

        assertNull(slot.take(3L))
        slot.clear(stalePublication)
        assertEquals("current", slot.take(4L))
    }
}
