package io.github.kdroidfilter.composemediaplayer.ads

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class AdPlanValidationTest {
    @Test
    fun opaqueIdentifiersNeverRenderTheirValues() {
        val secretLikeValue = "signed-provider-resource?token=must-not-leak"
        val values =
            listOf(
                AdSessionId(secretLikeValue),
                AdBreakId(secretLikeValue),
                AdId(secretLikeValue),
                AdResourceRef(secretLikeValue),
                AdActionRef(secretLikeValue),
                AdVerificationParametersRef(secretLikeValue),
            )

        values.forEach { value ->
            assertContains(value.toString(), "[redacted]")
            assertFalse(secretLikeValue in value.toString())
        }
    }

    @Test
    fun vodPlanRejectsLiveInstantBreak() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                AdPlan(
                    sessionId = AdSessionId("session"),
                    revision = 1L,
                    contentKind = AdContentKind.VOD,
                    failureMode = AdFailureMode.CONTINUE_CONTENT,
                    breaks =
                        listOf(
                            AdBreak(
                                id = AdBreakId("live"),
                                trigger = AdBreakTrigger.LiveInstant(1L),
                                ads = listOf(linearAd()),
                            ),
                        ),
                )
            }

        assertContains(error.message.orEmpty(), "VOD")
    }

    @Test
    fun skipOffsetCannotExceedCreativeDuration() {
        assertFailsWith<IllegalArgumentException> {
            AdPrimaryCreative.Linear(
                mediaCandidates = listOf(mediaCandidate()),
                duration = 5.seconds,
                skipOffset = 6.seconds,
            )
        }
    }

    private fun linearAd(): Ad =
        Ad(
            id = AdId("ad"),
            sequence = 1,
            primaryCreative =
                AdPrimaryCreative.Linear(
                    mediaCandidates = listOf(mediaCandidate()),
                    duration = 5.seconds,
                ),
        )

    private fun mediaCandidate(): AdMediaCandidate =
        AdMediaCandidate(
            resource =
                AdResourceDescriptor(
                    ref = AdResourceRef("resource"),
                    kind = AdResourceKind.VIDEO,
                    mimeType = "video/mp4",
                ),
            delivery = AdMediaDelivery.PROGRESSIVE,
        )
}
