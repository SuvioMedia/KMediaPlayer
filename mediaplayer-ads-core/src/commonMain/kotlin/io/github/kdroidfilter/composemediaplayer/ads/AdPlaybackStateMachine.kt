package io.github.kdroidfilter.composemediaplayer.ads

import kotlin.time.Duration

public class AdPlaybackStateMachine(
    initialPlan: AdPlan,
) {
    private var activeBreak: AdBreak? = null
    private var activeAdIndex: Int = -1
    private var activeDuration: Duration? = null
    private var eventSequence: Long = 0L
    private var lastContentPosition: Duration = Duration.ZERO
    private var contentHasEnded: Boolean = false
    private var suppressedExpiredRevision: Long? = null
    private val reachedQuartiles: MutableSet<AdEventType> = mutableSetOf()
    private val completedBreakIdsMutable: MutableSet<AdBreakId> = mutableSetOf()

    public var plan: AdPlan = initialPlan
        private set

    public var state: AdPlaybackState =
        AdPlaybackState.AwaitingBreak(
            planRevision = initialPlan.revision,
            nextBreakId = initialPlan.breaks.firstOrNull()?.id,
        )
        private set

    public val completedBreakIds: Set<AdBreakId>
        get() = completedBreakIdsMutable.toSet()

    public fun updatePlan(
        updatedPlan: AdPlan,
        nowEpochMillis: Long,
    ): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        require(updatedPlan.sessionId == plan.sessionId) {
            "An ad state machine cannot switch to a different ad session."
        }
        if (updatedPlan.revision <= plan.revision) return currentUpdate()

        val previousBreakIds = plan.breaks.mapTo(mutableSetOf(), AdBreak::id)
        val skippedEvents = mutableListOf<AdEvent>()
        plan = updatedPlan
        suppressedExpiredRevision = null

        updatedPlan.breaks
            .asSequence()
            .filterNot { it.id in previousBreakIds || it.id in completedBreakIdsMutable }
            .filter { it.latePolicy == AdLateBreakPolicy.SKIP }
            .filter { it.isAlreadyPast(lastContentPosition, nowEpochMillis, contentHasEnded) }
            .forEach { adBreak ->
                completedBreakIdsMutable += adBreak.id
                skippedEvents +=
                    newEvent(
                        type = AdEventType.BREAK_SKIPPED,
                        breakId = adBreak.id,
                        adId = null,
                        adPosition = null,
                        sampledAtEpochMillis = nowEpochMillis,
                    )
            }

        state = state.withPlanRevision(updatedPlan.revision)
        if (activeBreak == null) {
            state = waitingState()
        }
        return AdPlaybackUpdate(state = state, events = skippedEvents)
    }

    public fun observeContent(
        position: Duration,
        contentEnded: Boolean,
        nowEpochMillis: Long,
    ): AdPlaybackUpdate {
        requireValidPosition("Content position", position)
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        val contentWasAlreadyEnded = contentHasEnded
        lastContentPosition = position
        contentHasEnded = contentEnded

        if (activeBreak != null || state is AdPlaybackState.Failed) return currentUpdate()
        if (suppressedExpiredRevision == plan.revision) {
            state = if (contentEnded) finishedState() else waitingState(nextBreakId = null)
            return currentUpdate()
        }
        if (plan.expiresAtEpochMillis?.let { nowEpochMillis >= it } == true) {
            return handleExpiredPlan(nowEpochMillis)
        }

        val events = mutableListOf<AdEvent>()
        skipLatePendingBreaks(
            position = position,
            contentEnded = contentEnded,
            contentWasAlreadyEnded = contentWasAlreadyEnded,
            nowEpochMillis = nowEpochMillis,
            destination = events,
        )
        val dueBreak = findDueBreak(position, contentEnded, nowEpochMillis)
        if (dueBreak != null) {
            startBreak(dueBreak, nowEpochMillis, events)
        } else {
            state = if (contentEnded) finishedState() else waitingState()
        }
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun seekContent(
        from: Duration,
        to: Duration,
        nowEpochMillis: Long,
    ): AdPlaybackUpdate {
        requireValidPosition("Seek source position", from)
        requireValidPosition("Seek target position", to)
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        if (state.blocksContent) return currentUpdate()

        lastContentPosition = to
        contentHasEnded = false
        if (to < from) {
            plan.breaks
                .asSequence()
                .filter { it.replayPolicy == AdReplayPolicy.REPLAY_AFTER_SEEK_BACK }
                .filter { adBreak ->
                    val trigger = adBreak.trigger as? AdBreakTrigger.ContentPosition ?: return@filter false
                    trigger.position > to && trigger.position <= from
                }.forEach { completedBreakIdsMutable -= it.id }
            if (activeBreak == null) {
                state = waitingState()
            }
            return currentUpdate()
        }

        if (activeBreak != null) return currentUpdate()
        val crossedBreaks =
            plan.breaks
                .asSequence()
                .filterNot { adBreak -> adBreak.id in completedBreakIdsMutable }
                .mapNotNull { adBreak ->
                    val trigger = adBreak.trigger as? AdBreakTrigger.ContentPosition ?: return@mapNotNull null
                    adBreak.takeIf { trigger.position > from && trigger.position <= to }
                }.sortedBy { adBreak ->
                    (adBreak.trigger as AdBreakTrigger.ContentPosition).position
                }.toList()
        val events = mutableListOf<AdEvent>()
        crossedBreaks
            .filter { adBreak ->
                val trigger = adBreak.trigger as AdBreakTrigger.ContentPosition
                adBreak.latePolicy == AdLateBreakPolicy.SKIP && to > trigger.position
            }.forEach { adBreak -> skipBreak(adBreak, nowEpochMillis, events) }
        val crossedBreak = crossedBreaks.firstOrNull { adBreak -> adBreak.id !in completedBreakIdsMutable }
        if (crossedBreak == null) {
            state = waitingState()
            return AdPlaybackUpdate(state = state, events = events)
        }

        startBreak(crossedBreak, nowEpochMillis, events)
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun restartContent(nowEpochMillis: Long): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        if (state.blocksContent) return currentUpdate()

        plan.breaks
            .filter { it.replayPolicy == AdReplayPolicy.REPLAY_AFTER_CONTENT_RESTART }
            .forEach { completedBreakIdsMutable -= it.id }
        lastContentPosition = Duration.ZERO
        contentHasEnded = false
        suppressedExpiredRevision = null
        if (activeBreak != null) return currentUpdate()
        state = waitingState()
        return observeContent(
            position = Duration.ZERO,
            contentEnded = false,
            nowEpochMillis = nowEpochMillis,
        )
    }

    public fun startCurrentAd(
        nowEpochMillis: Long,
        resolvedDuration: Duration? = null,
    ): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        resolvedDuration?.let { requireValidPositiveDuration("Resolved ad duration", it) }
        val preparing = state as? AdPlaybackState.Preparing ?: return currentUpdate()
        activeDuration = resolvedDuration ?: preparing.ad.primaryCreative.duration
        reachedQuartiles.clear()

        val skipAvailable = preparing.ad.primaryCreative.skipOffset == Duration.ZERO
        state =
            AdPlaybackState.Playing(
                planRevision = plan.revision,
                adBreak = preparing.adBreak,
                ad = preparing.ad,
                adIndex = preparing.adIndex,
                adCount = preparing.adCount,
                position = Duration.ZERO,
                duration = activeDuration,
                skipAvailable = skipAvailable,
            )
        val events =
            buildList {
                add(currentAdEvent(AdEventType.IMPRESSION, nowEpochMillis, Duration.ZERO))
                add(currentAdEvent(AdEventType.STARTED, nowEpochMillis, Duration.ZERO))
                if (skipAvailable) {
                    add(currentAdEvent(AdEventType.SKIP_AVAILABLE, nowEpochMillis, Duration.ZERO))
                }
            }
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun updateAdPosition(
        position: Duration,
        nowEpochMillis: Long,
    ): AdPlaybackUpdate {
        requireValidPosition("Ad position", position)
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        val playing = state as? AdPlaybackState.Playing ?: return currentUpdate()
        val effectivePosition = activeDuration?.let { minOf(position, it) } ?: position
        val skipOffset = playing.ad.primaryCreative.skipOffset
        val skipAvailable = playing.skipAvailable || (skipOffset != null && effectivePosition >= skipOffset)
        state = playing.copy(position = effectivePosition, skipAvailable = skipAvailable)

        val events = mutableListOf<AdEvent>()
        if (!playing.skipAvailable && skipAvailable) {
            events += currentAdEvent(AdEventType.SKIP_AVAILABLE, nowEpochMillis, effectivePosition)
        }
        activeDuration?.let { duration ->
            emitReachedQuartiles(
                position = effectivePosition,
                duration = duration,
                nowEpochMillis = nowEpochMillis,
                destination = events,
            )
            if (effectivePosition >= duration) {
                val completion = completeCurrentAd(nowEpochMillis)
                events += completion.events
                return AdPlaybackUpdate(state = state, events = events)
            }
        }
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun completeCurrentAd(nowEpochMillis: Long): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        val playing = state as? AdPlaybackState.Playing ?: return currentUpdate()
        val completionPosition = activeDuration ?: playing.position
        state = playing.copy(position = completionPosition)
        val events = mutableListOf<AdEvent>()
        activeDuration?.let { duration ->
            emitReachedQuartiles(
                position = duration,
                duration = duration,
                nowEpochMillis = nowEpochMillis,
                destination = events,
            )
        }
        events +=
            currentAdEvent(
                type = AdEventType.COMPLETED,
                sampledAtEpochMillis = nowEpochMillis,
                adPosition = completionPosition,
            )
        advanceAfterCurrentAd(nowEpochMillis, events)
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun skipCurrentAd(nowEpochMillis: Long): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        val playing = state as? AdPlaybackState.Playing ?: return currentUpdate()
        if (!playing.skipAvailable) return currentUpdate()

        val events =
            mutableListOf(
                currentAdEvent(
                    type = AdEventType.SKIPPED,
                    sampledAtEpochMillis = nowEpochMillis,
                    adPosition = playing.position,
                ),
            )
        advanceAfterCurrentAd(nowEpochMillis, events)
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun closeCurrentAd(nowEpochMillis: Long): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        val playing = state as? AdPlaybackState.Playing ?: return currentUpdate()
        val nonLinear = playing.ad.primaryCreative as? AdPrimaryCreative.NonLinear ?: return currentUpdate()
        if (!nonLinear.closable) return currentUpdate()

        val events =
            mutableListOf(
                currentAdEvent(
                    type = AdEventType.CLOSED,
                    sampledAtEpochMillis = nowEpochMillis,
                    adPosition = playing.position,
                ),
            )
        advanceAfterCurrentAd(nowEpochMillis, events)
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun failCurrentAd(
        errorCode: AdPlaybackErrorCode,
        nowEpochMillis: Long,
    ): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        val currentBreak = activeBreak ?: return currentUpdate()
        val currentAd = currentAd() ?: return currentUpdate()
        val adPosition = (state as? AdPlaybackState.Playing)?.position
        val events =
            mutableListOf(
                newEvent(
                    type = AdEventType.ERROR,
                    breakId = currentBreak.id,
                    adId = currentAd.id,
                    adPosition = adPosition,
                    sampledAtEpochMillis = nowEpochMillis,
                    errorCode = errorCode,
                ),
            )
        if (plan.failureMode == AdFailureMode.BLOCK_CONTENT) {
            state =
                AdPlaybackState.Failed(
                    planRevision = plan.revision,
                    breakId = currentBreak.id,
                    adId = currentAd.id,
                    errorCode = errorCode,
                    failureMode = plan.failureMode,
                )
        } else {
            advanceAfterCurrentAd(nowEpochMillis, events)
        }
        return AdPlaybackUpdate(state = state, events = events)
    }

    public fun recordCurrentAdEvent(
        type: AdEventType,
        nowEpochMillis: Long,
    ): AdPlaybackUpdate {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative." }
        require(type in RECORDABLE_EVENT_TYPES) { "$type is controlled by the ad state machine." }
        val playing = state as? AdPlaybackState.Playing ?: return currentUpdate()
        if (type == AdEventType.CLICKED && playing.ad.primaryCreative.clickAction == null) return currentUpdate()
        return AdPlaybackUpdate(
            state = state,
            events = listOf(currentAdEvent(type, nowEpochMillis, playing.position)),
        )
    }

    private fun handleExpiredPlan(nowEpochMillis: Long): AdPlaybackUpdate {
        suppressedExpiredRevision = plan.revision
        val event =
            newEvent(
                type = AdEventType.ERROR,
                breakId = null,
                adId = null,
                adPosition = null,
                sampledAtEpochMillis = nowEpochMillis,
                errorCode = AdPlaybackErrorCode.PLAN_EXPIRED,
            )
        state =
            if (plan.failureMode == AdFailureMode.BLOCK_CONTENT) {
                AdPlaybackState.Failed(
                    planRevision = plan.revision,
                    breakId = null,
                    adId = null,
                    errorCode = AdPlaybackErrorCode.PLAN_EXPIRED,
                    failureMode = plan.failureMode,
                )
            } else if (contentHasEnded) {
                finishedState()
            } else {
                waitingState(nextBreakId = null)
            }
        return AdPlaybackUpdate(state = state, events = listOf(event))
    }

    private fun findDueBreak(
        position: Duration,
        contentEnded: Boolean,
        nowEpochMillis: Long,
    ): AdBreak? =
        plan.breaks.firstOrNull { adBreak ->
            adBreak.id !in completedBreakIdsMutable &&
                when (val trigger = adBreak.trigger) {
                    AdBreakTrigger.PreRoll -> true
                    is AdBreakTrigger.ContentPosition -> position >= trigger.position
                    AdBreakTrigger.PostRoll -> contentEnded
                    is AdBreakTrigger.LiveInstant -> nowEpochMillis >= trigger.epochMillis
                }
        }

    private fun skipLatePendingBreaks(
        position: Duration,
        contentEnded: Boolean,
        contentWasAlreadyEnded: Boolean,
        nowEpochMillis: Long,
        destination: MutableList<AdEvent>,
    ) {
        plan.breaks
            .asSequence()
            .filterNot { adBreak -> adBreak.id in completedBreakIdsMutable }
            .filter { adBreak -> adBreak.latePolicy == AdLateBreakPolicy.SKIP }
            .filter { adBreak ->
                when (val trigger = adBreak.trigger) {
                    AdBreakTrigger.PreRoll -> position > Duration.ZERO
                    is AdBreakTrigger.ContentPosition -> position > trigger.position
                    AdBreakTrigger.PostRoll -> contentEnded && contentWasAlreadyEnded
                    is AdBreakTrigger.LiveInstant -> nowEpochMillis > trigger.epochMillis
                }
            }.forEach { adBreak -> skipBreak(adBreak, nowEpochMillis, destination) }
    }

    private fun skipBreak(
        adBreak: AdBreak,
        nowEpochMillis: Long,
        destination: MutableList<AdEvent>,
    ) {
        if (!completedBreakIdsMutable.add(adBreak.id)) return
        destination +=
            newEvent(
                type = AdEventType.BREAK_SKIPPED,
                breakId = adBreak.id,
                adId = null,
                adPosition = null,
                sampledAtEpochMillis = nowEpochMillis,
            )
    }

    private fun startBreak(
        adBreak: AdBreak,
        nowEpochMillis: Long,
        events: MutableList<AdEvent>,
    ) {
        activeBreak = adBreak
        activeAdIndex = 0
        activeDuration = null
        reachedQuartiles.clear()
        val orderedAds = adBreak.orderedAds
        state =
            AdPlaybackState.Preparing(
                planRevision = plan.revision,
                adBreak = adBreak,
                ad = orderedAds.first(),
                adIndex = 0,
                adCount = orderedAds.size,
            )
        events +=
            newEvent(
                type = AdEventType.BREAK_STARTED,
                breakId = adBreak.id,
                adId = null,
                adPosition = null,
                sampledAtEpochMillis = nowEpochMillis,
            )
    }

    private fun advanceAfterCurrentAd(
        nowEpochMillis: Long,
        events: MutableList<AdEvent>,
    ) {
        val currentBreak = activeBreak ?: return
        val orderedAds = currentBreak.orderedAds
        val nextIndex = activeAdIndex + 1
        activeDuration = null
        reachedQuartiles.clear()
        if (nextIndex < orderedAds.size) {
            activeAdIndex = nextIndex
            state =
                AdPlaybackState.Preparing(
                    planRevision = plan.revision,
                    adBreak = currentBreak,
                    ad = orderedAds[nextIndex],
                    adIndex = nextIndex,
                    adCount = orderedAds.size,
                )
            return
        }

        completedBreakIdsMutable += currentBreak.id
        events +=
            newEvent(
                type = AdEventType.BREAK_COMPLETED,
                breakId = currentBreak.id,
                adId = null,
                adPosition = null,
                sampledAtEpochMillis = nowEpochMillis,
            )
        activeBreak = null
        activeAdIndex = -1
        state =
            if (contentHasEnded && nextPendingBreakId() == null) {
                finishedState()
            } else {
                waitingState()
            }
    }

    private fun emitReachedQuartiles(
        position: Duration,
        duration: Duration,
        nowEpochMillis: Long,
        destination: MutableList<AdEvent>,
    ) {
        QUARTILE_EVENTS.forEach { (fraction, type) ->
            if (type !in reachedQuartiles && position >= duration * fraction) {
                reachedQuartiles += type
                destination += currentAdEvent(type, nowEpochMillis, position)
            }
        }
    }

    private fun currentAdEvent(
        type: AdEventType,
        sampledAtEpochMillis: Long,
        adPosition: Duration,
    ): AdEvent {
        val currentBreak = checkNotNull(activeBreak) { "No active ad break." }
        val currentAd = checkNotNull(currentAd()) { "No active ad." }
        return newEvent(
            type = type,
            breakId = currentBreak.id,
            adId = currentAd.id,
            adPosition = adPosition,
            sampledAtEpochMillis = sampledAtEpochMillis,
        )
    }

    private fun newEvent(
        type: AdEventType,
        breakId: AdBreakId?,
        adId: AdId?,
        adPosition: Duration?,
        sampledAtEpochMillis: Long,
        errorCode: AdPlaybackErrorCode? = null,
    ): AdEvent =
        AdEvent(
            sequence = ++eventSequence,
            sessionId = plan.sessionId,
            breakId = breakId,
            adId = adId,
            type = type,
            contentPosition = lastContentPosition,
            adPosition = adPosition,
            sampledAtEpochMillis = sampledAtEpochMillis,
            errorCode = errorCode,
        )

    private fun currentAd(): Ad? = activeBreak?.orderedAds?.getOrNull(activeAdIndex)

    private fun nextPendingBreakId(): AdBreakId? = plan.breaks.firstOrNull { it.id !in completedBreakIdsMutable }?.id

    private fun waitingState(nextBreakId: AdBreakId? = nextPendingBreakId()): AdPlaybackState.AwaitingBreak =
        AdPlaybackState.AwaitingBreak(
            planRevision = plan.revision,
            nextBreakId = nextBreakId,
        )

    private fun finishedState(): AdPlaybackState.Finished = AdPlaybackState.Finished(planRevision = plan.revision)

    private fun currentUpdate(): AdPlaybackUpdate = AdPlaybackUpdate(state = state)

    private companion object {
        val QUARTILE_EVENTS: List<Pair<Double, AdEventType>> =
            listOf(
                0.25 to AdEventType.FIRST_QUARTILE,
                0.50 to AdEventType.MIDPOINT,
                0.75 to AdEventType.THIRD_QUARTILE,
            )

        val RECORDABLE_EVENT_TYPES: Set<AdEventType> =
            setOf(
                AdEventType.CLICKED,
                AdEventType.PAUSED,
                AdEventType.RESUMED,
                AdEventType.MUTED,
                AdEventType.UNMUTED,
                AdEventType.CLOSED,
                AdEventType.VIEWABLE_IMPRESSION,
                AdEventType.NOT_VIEWABLE,
            )
    }
}

private fun AdPlaybackState.withPlanRevision(revision: Long): AdPlaybackState =
    when (this) {
        is AdPlaybackState.AwaitingBreak -> copy(planRevision = revision)
        is AdPlaybackState.Preparing -> copy(planRevision = revision)
        is AdPlaybackState.Playing -> copy(planRevision = revision)
        is AdPlaybackState.Failed -> copy(planRevision = revision)
        is AdPlaybackState.Finished -> copy(planRevision = revision)
    }

private fun AdBreak.isAlreadyPast(
    contentPosition: Duration,
    nowEpochMillis: Long,
    contentEnded: Boolean,
): Boolean =
    when (val value = trigger) {
        AdBreakTrigger.PreRoll -> contentPosition > Duration.ZERO
        is AdBreakTrigger.ContentPosition -> contentPosition > value.position
        AdBreakTrigger.PostRoll -> contentEnded
        is AdBreakTrigger.LiveInstant -> nowEpochMillis > value.epochMillis
    }

private fun requireValidPosition(
    label: String,
    position: Duration,
) {
    require(position.isFinite()) { "$label must be finite." }
    require(position >= Duration.ZERO) { "$label must not be negative." }
}

private fun requireValidPositiveDuration(
    label: String,
    duration: Duration,
) {
    require(duration.isFinite()) { "$label must be finite." }
    require(duration > Duration.ZERO) { "$label must be positive." }
}
