package echo.music.domain.autodj.validator

import echo.music.domain.autodj.model.DjPreset
import echo.music.domain.autodj.model.FallbackReason
import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionPlanResult
import echo.music.domain.autodj.model.TransitionType

class TransitionPlanValidator {

  /**
   * Evaluates the plan against the 4 deterministic fallback gates.
   */
  fun validate(
    current: TrackProfile?,
    incoming: TrackProfile?,
    plan: TransitionPlan,
    preset: DjPreset = DjPreset.SEAMLESS,
  ): TransitionPlanResult {
    // Gate 1: Profile Availability / Beat Data
    if (current == null || incoming == null || !current.hasValidAnalysis || !incoming.hasValidAnalysis) {
      return TransitionPlanResult.Fallback(
        reason = FallbackReason.MISSING_PROFILE,
        defaultPlan = createFallbackPlan(
          currentSongId = current?.songId ?: plan.outgoingSongId,
          incomingSongId = incoming?.songId ?: plan.incomingSongId,
          trackDurationMs = current?.durationMs ?: 0L,
          preset = preset,
          reason = FallbackReason.MISSING_PROFILE,
          generationId = plan.generationId,
        ),
      )
    }

    // Gate 2: Confidence Threshold
    if (current.confidence < preset.confidenceThreshold || incoming.confidence < preset.confidenceThreshold) {
      return TransitionPlanResult.Fallback(
        reason = FallbackReason.LOW_CONFIDENCE,
        defaultPlan = createFallbackPlan(
          currentSongId = current.songId,
          incomingSongId = incoming.songId,
          trackDurationMs = current.durationMs,
          preset = preset,
          reason = FallbackReason.LOW_CONFIDENCE,
          generationId = plan.generationId,
        ),
      )
    }

    // Gate 3: Tempo Delta Bounds (stay within ±8% [0.92, 1.08])
    var foldedRatio = current.bpm / incoming.bpm
    while (foldedRatio > 1.5f) {
      foldedRatio /= 2.0f
    }
    while (foldedRatio < 0.667f) {
      foldedRatio *= 2.0f
    }
    if (foldedRatio !in 0.92f..1.08f) {
      return TransitionPlanResult.Fallback(
        reason = FallbackReason.TEMPO_OUT_OF_BOUNDS,
        defaultPlan = createFallbackPlan(
          currentSongId = current.songId,
          incomingSongId = incoming.songId,
          trackDurationMs = current.durationMs,
          preset = preset,
          reason = FallbackReason.TEMPO_OUT_OF_BOUNDS,
          generationId = plan.generationId,
        ),
      )
    }

    // Gate 4: Insufficient Headroom / Room Buffer
    val roomMs = current.durationMs - plan.triggerTimeMs
    if (plan.overlapDurationMs < 3000L || roomMs < 3000L || plan.triggerTimeMs >= current.durationMs - 3000L) {
      return TransitionPlanResult.Fallback(
        reason = FallbackReason.INSUFFICIENT_HEADROOM,
        defaultPlan = createFallbackPlan(
          currentSongId = current.songId,
          incomingSongId = incoming.songId,
          trackDurationMs = current.durationMs,
          preset = preset,
          reason = FallbackReason.INSUFFICIENT_HEADROOM,
          generationId = plan.generationId,
        ),
      )
    }

    return TransitionPlanResult.Valid(plan)
  }

  /**
   * Convenience method evaluating plan directly when profiles are already embedded or for standalone plan validation.
   */
  fun validate(plan: TransitionPlan): TransitionPlanResult {
    if (plan.isFallback) {
      return TransitionPlanResult.Fallback(plan.fallbackReason, plan)
    }
    if (plan.confidence < 0.30f) {
      return TransitionPlanResult.Fallback(
        FallbackReason.LOW_CONFIDENCE,
        createFallbackPlan(plan.outgoingSongId, plan.incomingSongId, 0L, reason = FallbackReason.LOW_CONFIDENCE, generationId = plan.generationId),
      )
    }
    if (plan.tempoRatio !in 0.92f..1.08f) {
      return TransitionPlanResult.Fallback(
        FallbackReason.TEMPO_OUT_OF_BOUNDS,
        createFallbackPlan(plan.outgoingSongId, plan.incomingSongId, 0L, reason = FallbackReason.TEMPO_OUT_OF_BOUNDS, generationId = plan.generationId),
      )
    }
    if (plan.overlapDurationMs < 3000L) {
      return TransitionPlanResult.Fallback(
        FallbackReason.INSUFFICIENT_HEADROOM,
        createFallbackPlan(plan.outgoingSongId, plan.incomingSongId, 0L, reason = FallbackReason.INSUFFICIENT_HEADROOM, generationId = plan.generationId),
      )
    }
    return TransitionPlanResult.Valid(plan)
  }

  fun createFallbackPlan(
    currentSongId: String,
    incomingSongId: String,
    trackDurationMs: Long,
    preset: DjPreset = DjPreset.SEAMLESS,
    reason: FallbackReason = FallbackReason.NONE,
    generationId: Long = 0L,
  ): TransitionPlan {
    val overlapMs = preset.fallbackCrossfadeMs.coerceAtMost(
      if (trackDurationMs > 0) (trackDurationMs - 1000L).coerceAtLeast(1000L) else 5000L,
    )
    val triggerTimeMs = if (trackDurationMs > 0) (trackDurationMs - overlapMs).coerceAtLeast(0L) else 0L

    return TransitionPlan(
      outgoingSongId = currentSongId,
      incomingSongId = incomingSongId,
      transitionType = TransitionType.STANDARD_CROSSFADE,
      triggerTimeMs = triggerTimeMs,
      incomingStartMs = 0L,
      overlapDurationMs = overlapMs,
      tempoRatio = 1.0f,
      pitchRatio = 1.0f,
      pitchShiftSemitones = 0,
      lowShelfDuckDb = 0.0f,
      confidence = 0.0f,
      harmonicScore = 0.5f,
      fallbackReason = reason,
      generationId = generationId,
    )
  }
}
