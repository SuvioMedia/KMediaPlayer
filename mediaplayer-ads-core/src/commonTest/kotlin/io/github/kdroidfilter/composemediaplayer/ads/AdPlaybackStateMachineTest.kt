package io.github.kdroidfilter.composemediaplayer.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AdPlaybackStateMachineTest {
    @Test
    fun prerollEmitsOrderedLifecycleAndQuartilesExactlyOnce() {
        val machine = AdPlaybackStateMachine(plan(adBreak("pre", AdBreakTrigger.PreRoll)))

        val due = machine.observeContent(DurationZero, contentEnded = false, nowEpochMillis = 1L)
        preparing(due.state)
        assertEquals(listOf(AdEventType.BREAK_STARTED), due.events.map(AdEvent::type))

        val started = machine.startCurrentAd(nowEpochMillis = 2L)
        assertEquals(
            listOf(AdEventType.IMPRESSION, AdEventType.STARTED),
            started.events.map(AdEvent::type),
        )

        val midpoint = machine.updateAdPosition(position = 6.seconds, nowEpochMillis = 3L)
        assertEquals(
            listOf(AdEventType.FIRST_QUARTILE, AdEventType.MIDPOINT),
            midpoint.events.map(AdEvent::type),
        )
        assertTrue(midpoint.state.blocksContent)

        val completed = machine.updateAdPosition(position = 10.seconds, nowEpochMillis = 4L)
        assertEquals(
            listOf(
                AdEventType.THIRD_QUARTILE,
                AdEventType.COMPLETED,
                AdEventType.BREAK_COMPLETED,
            ),
            completed.events.map(AdEvent::type),
        )
        awaiting(completed.state)
        val allEvents = due.events + started.events + midpoint.events + completed.events
        assertEquals(
            (1L..8L).toList(),
            allEvents.map(AdEvent::sequence),
        )
    }

    @Test
    fun forwardSeekStartsFirstUnplayedMidrollAndNeverMutatesContentClock() {
        val machine =
            AdPlaybackStateMachine(
                plan(
                    adBreak("mid-10", AdBreakTrigger.ContentPosition(10.seconds)),
                    adBreak("mid-20", AdBreakTrigger.ContentPosition(20.seconds)),
                ),
            )

        val update = machine.seekContent(from = 5.seconds, to = 25.seconds, nowEpochMillis = 10L)

        val preparing = preparing(update.state)
        assertEquals(AdBreakId("mid-10"), preparing.adBreak.id)
        assertEquals(25.seconds, update.events.single().contentPosition)

        machine.startCurrentAd(nowEpochMillis = 11L)
        machine.completeCurrentAd(nowEpochMillis = 12L)
        val second = machine.observeContent(25.seconds, contentEnded = false, nowEpochMillis = 13L)
        assertEquals(AdBreakId("mid-20"), preparing(second.state).adBreak.id)
    }

    @Test
    fun replayAfterSeekBackRearmsOnlyOptedInBreaks() {
        val replaying =
            adBreak(
                id = "replay",
                trigger = AdBreakTrigger.ContentPosition(10.seconds),
                replayPolicy = AdReplayPolicy.REPLAY_AFTER_SEEK_BACK,
            )
        val once = adBreak("once", AdBreakTrigger.ContentPosition(20.seconds))
        val machine = AdPlaybackStateMachine(plan(replaying, once))

        machine.seekContent(DurationZero, 10.seconds, 1L)
        machine.startCurrentAd(2L)
        machine.completeCurrentAd(3L)
        machine.seekContent(10.seconds, 20.seconds, 4L)
        machine.startCurrentAd(5L)
        machine.completeCurrentAd(6L)

        machine.seekContent(25.seconds, 5.seconds, 7L)
        val replay = machine.seekContent(5.seconds, 25.seconds, 8L)

        assertEquals(AdBreakId("replay"), preparing(replay.state).adBreak.id)
    }

    @Test
    fun skipBecomesAvailableAtOffsetAndAdvancesPod() {
        val first = ad("first", sequence = 1, skipOffset = 3.seconds)
        val second = ad("second", sequence = 2)
        val machine = AdPlaybackStateMachine(plan(adBreak("pod", AdBreakTrigger.PreRoll, first, second)))

        machine.observeContent(DurationZero, false, 1L)
        machine.startCurrentAd(2L)
        assertTrue(machine.skipCurrentAd(3L).events.isEmpty())

        val atOffset = machine.updateAdPosition(3.seconds, 4L)
        assertEquals(
            listOf(AdEventType.SKIP_AVAILABLE, AdEventType.FIRST_QUARTILE),
            atOffset.events.map(AdEvent::type),
        )
        val skipped = machine.skipCurrentAd(5L)

        assertEquals(AdEventType.SKIPPED, skipped.events.single().type)
        val preparing = preparing(skipped.state)
        assertEquals(AdId("second"), preparing.ad.id)
        assertEquals(1, preparing.adIndex)
        assertEquals(2, preparing.adCount)
    }

    @Test
    fun failOpenContinuesPodWhileFailClosedBlocksContent() {
        val openMachine =
            AdPlaybackStateMachine(
                plan(
                    adBreak("pre", AdBreakTrigger.PreRoll),
                    failureMode = AdFailureMode.CONTINUE_CONTENT,
                ),
            )
        openMachine.observeContent(DurationZero, false, 1L)
        val openFailure = openMachine.failCurrentAd(AdPlaybackErrorCode.MEDIA_LOAD_FAILED, 2L)
        assertFalse(openFailure.state.blocksContent)
        assertEquals(
            listOf(AdEventType.ERROR, AdEventType.BREAK_COMPLETED),
            openFailure.events.map(AdEvent::type),
        )

        val closedMachine =
            AdPlaybackStateMachine(
                plan(
                    adBreak("pre", AdBreakTrigger.PreRoll),
                    failureMode = AdFailureMode.BLOCK_CONTENT,
                ),
            )
        closedMachine.observeContent(DurationZero, false, 1L)
        val closedFailure = closedMachine.failCurrentAd(AdPlaybackErrorCode.MEDIA_LOAD_FAILED, 2L)
        assertTrue(failed(closedFailure.state).blocksContent)
        assertEquals(listOf(AdEventType.ERROR), closedFailure.events.map(AdEvent::type))
    }

    @Test
    fun latePlanRevisionSkipsOnlyNewBreaksWithSkipPolicy() {
        val machine = AdPlaybackStateMachine(plan(revision = 1L))
        machine.observeContent(30.seconds, false, 100L)
        val lateBreak =
            adBreak(
                id = "late",
                trigger = AdBreakTrigger.ContentPosition(10.seconds),
                latePolicy = AdLateBreakPolicy.SKIP,
            )

        val update = machine.updatePlan(plan(lateBreak, revision = 2L), nowEpochMillis = 101L)

        assertEquals(listOf(AdEventType.BREAK_SKIPPED), update.events.map(AdEvent::type))
        assertEquals(setOf(AdBreakId("late")), machine.completedBreakIds)
        assertEquals(2L, awaiting(update.state).planRevision)
    }

    @Test
    fun initialLateBreakWithSkipPolicyIsSkippedInsteadOfPlayed() {
        val machine =
            AdPlaybackStateMachine(
                plan(
                    adBreak(
                        id = "late",
                        trigger = AdBreakTrigger.ContentPosition(10.seconds),
                        latePolicy = AdLateBreakPolicy.SKIP,
                    ),
                ),
            )

        val update = machine.observeContent(30.seconds, contentEnded = false, nowEpochMillis = 100L)

        assertEquals(listOf(AdEventType.BREAK_SKIPPED), update.events.map(AdEvent::type))
        assertEquals(setOf(AdBreakId("late")), machine.completedBreakIds)
        awaiting(update.state)
    }

    @Test
    fun forwardSeekSkipsLateBreakAndStartsNextPlayableBreakChronologically() {
        val machine =
            AdPlaybackStateMachine(
                plan(
                    adBreak(
                        id = "play-20",
                        trigger = AdBreakTrigger.ContentPosition(20.seconds),
                    ),
                    adBreak(
                        id = "skip-10",
                        trigger = AdBreakTrigger.ContentPosition(10.seconds),
                        latePolicy = AdLateBreakPolicy.SKIP,
                    ),
                ),
            )

        val update = machine.seekContent(5.seconds, 25.seconds, nowEpochMillis = 100L)

        assertEquals(
            listOf(AdEventType.BREAK_SKIPPED, AdEventType.BREAK_STARTED),
            update.events.map(AdEvent::type),
        )
        assertEquals(AdBreakId("play-20"), preparing(update.state).adBreak.id)
        assertEquals(setOf(AdBreakId("skip-10")), machine.completedBreakIds)
    }

    @Test
    fun directPlaybackEndEmitsEveryMissingQuartileBeforeCompletion() {
        val machine = AdPlaybackStateMachine(plan(adBreak("pre", AdBreakTrigger.PreRoll)))
        machine.observeContent(DurationZero, contentEnded = false, nowEpochMillis = 1L)
        machine.startCurrentAd(nowEpochMillis = 2L)

        val completed = machine.completeCurrentAd(nowEpochMillis = 3L)

        assertEquals(
            listOf(
                AdEventType.FIRST_QUARTILE,
                AdEventType.MIDPOINT,
                AdEventType.THIRD_QUARTILE,
                AdEventType.COMPLETED,
                AdEventType.BREAK_COMPLETED,
            ),
            completed.events.map(AdEvent::type),
        )
        assertEquals(10.seconds, completed.events.first().adPosition)
    }

    @Test
    fun linearCloseCanBeRecordedBeforeSessionTeardownWithoutAdvancingPlayback() {
        val machine = AdPlaybackStateMachine(plan(adBreak("pre", AdBreakTrigger.PreRoll)))
        machine.observeContent(DurationZero, contentEnded = false, nowEpochMillis = 1L)
        machine.startCurrentAd(nowEpochMillis = 2L)

        val closed = machine.recordCurrentAdEvent(AdEventType.CLOSED, nowEpochMillis = 3L)

        assertEquals(listOf(AdEventType.CLOSED), closed.events.map(AdEvent::type))
        playing(closed.state)
    }

    @Test
    fun postrollFinishesSessionAndLiveBreakUsesWallClock() {
        val postroll = AdPlaybackStateMachine(plan(adBreak("post", AdBreakTrigger.PostRoll)))
        val duePostroll = postroll.observeContent(100.seconds, contentEnded = true, nowEpochMillis = 1L)
        assertEquals(AdBreakId("post"), preparing(duePostroll.state).adBreak.id)
        postroll.startCurrentAd(2L)
        assertTrue(postroll.completeCurrentAd(3L).state is AdPlaybackState.Finished)

        val live =
            AdPlaybackStateMachine(
                plan(
                    adBreak("live", AdBreakTrigger.LiveInstant(epochMillis = 500L)),
                    contentKind = AdContentKind.LIVE,
                ),
            )
        awaiting(live.observeContent(DurationZero, false, 499L).state)
        preparing(live.observeContent(DurationZero, false, 500L).state)
    }

    @Test
    fun nonLinearCreativeDoesNotBlockContentAndCanBeClosed() {
        val nonLinear =
            Ad(
                id = AdId("overlay"),
                sequence = 1,
                primaryCreative =
                    AdPrimaryCreative.NonLinear(
                        resource = imageResource("overlay-resource"),
                        widthPx = 640,
                        heightPx = 100,
                    ),
            )
        val machine = AdPlaybackStateMachine(plan(adBreak("overlay-break", AdBreakTrigger.PreRoll, nonLinear)))

        val preparing = machine.observeContent(DurationZero, false, 1L)
        assertFalse(preparing.state.blocksContent)
        machine.startCurrentAd(2L)
        val closed = machine.closeCurrentAd(3L)

        assertEquals(
            listOf(AdEventType.CLOSED, AdEventType.BREAK_COMPLETED),
            closed.events.map(AdEvent::type),
        )
        assertFalse(closed.state.blocksContent)
    }

    @Test
    fun contentSeekAndRestartKeepActiveNonLinearStateConsistent() {
        val replayingMidroll =
            adBreak(
                id = "mid",
                trigger = AdBreakTrigger.ContentPosition(10.seconds),
                replayPolicy = AdReplayPolicy.REPLAY_AFTER_CONTENT_RESTART,
            )
        val overlay =
            Ad(
                id = AdId("overlay"),
                sequence = 1,
                primaryCreative =
                    AdPrimaryCreative.NonLinear(
                        resource = imageResource("overlay-resource"),
                        widthPx = 640,
                        heightPx = 100,
                    ),
            )
        val machine =
            AdPlaybackStateMachine(
                plan(
                    replayingMidroll,
                    adBreak("overlay", AdBreakTrigger.ContentPosition(20.seconds), overlay),
                ),
            )
        machine.seekContent(DurationZero, 10.seconds, 1L)
        machine.startCurrentAd(2L)
        machine.completeCurrentAd(3L)
        machine.seekContent(10.seconds, 20.seconds, 4L)
        machine.startCurrentAd(5L)

        playing(machine.seekContent(20.seconds, 5.seconds, 6L).state)
        playing(machine.restartContent(7L).state)

        machine.closeCurrentAd(8L)
        val replay = machine.observeContent(10.seconds, contentEnded = false, nowEpochMillis = 9L)
        assertEquals(AdBreakId("mid"), preparing(replay.state).adBreak.id)
    }

    private companion object {
        val DurationZero = 0.milliseconds

        fun preparing(state: AdPlaybackState): AdPlaybackState.Preparing {
            assertTrue(state is AdPlaybackState.Preparing)
            return state
        }

        fun awaiting(state: AdPlaybackState): AdPlaybackState.AwaitingBreak {
            assertTrue(state is AdPlaybackState.AwaitingBreak)
            return state
        }

        fun playing(state: AdPlaybackState): AdPlaybackState.Playing {
            assertTrue(state is AdPlaybackState.Playing)
            return state
        }

        fun failed(state: AdPlaybackState): AdPlaybackState.Failed {
            assertTrue(state is AdPlaybackState.Failed)
            return state
        }

        fun plan(
            vararg breaks: AdBreak,
            revision: Long = 1L,
            failureMode: AdFailureMode = AdFailureMode.CONTINUE_CONTENT,
            contentKind: AdContentKind = AdContentKind.VOD,
        ): AdPlan =
            AdPlan(
                sessionId = AdSessionId("session"),
                revision = revision,
                contentKind = contentKind,
                failureMode = failureMode,
                breaks = breaks.toList(),
            )

        fun adBreak(
            id: String,
            trigger: AdBreakTrigger,
            vararg ads: Ad,
            replayPolicy: AdReplayPolicy = AdReplayPolicy.ONCE_PER_SESSION,
            latePolicy: AdLateBreakPolicy = AdLateBreakPolicy.PLAY_IMMEDIATELY,
        ): AdBreak =
            AdBreak(
                id = AdBreakId(id),
                trigger = trigger,
                ads = ads.toList().ifEmpty { listOf(ad("$id-ad")) },
                replayPolicy = replayPolicy,
                latePolicy = latePolicy,
            )

        fun ad(
            id: String,
            sequence: Int = 1,
            skipOffset: kotlin.time.Duration? = null,
        ): Ad =
            Ad(
                id = AdId(id),
                sequence = sequence,
                primaryCreative =
                    AdPrimaryCreative.Linear(
                        mediaCandidates = listOf(mediaCandidate("$id-media")),
                        duration = 10.seconds,
                        skipOffset = skipOffset,
                    ),
            )

        fun mediaCandidate(id: String): AdMediaCandidate =
            AdMediaCandidate(
                resource =
                    AdResourceDescriptor(
                        ref = AdResourceRef(id),
                        kind = AdResourceKind.VIDEO,
                        mimeType = "video/mp4",
                    ),
                delivery = AdMediaDelivery.PROGRESSIVE,
            )

        fun imageResource(id: String): AdResourceDescriptor =
            AdResourceDescriptor(
                ref = AdResourceRef(id),
                kind = AdResourceKind.IMAGE,
                mimeType = "image/png",
            )
    }
}
