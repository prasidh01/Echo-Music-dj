package echo.music.domain.autodj.model

enum class TransitionType {
  PHRASE_ALIGNED_BLEND,
  BEAT_MATCHED_CROSSFADE,
  DROP_SWAP,
  STANDARD_CROSSFADE,
  CUT
}

enum class FallbackReason {
  NONE,
  MISSING_PROFILE,
  LOW_CONFIDENCE,
  TEMPO_OUT_OF_BOUNDS,
  TEMPO_DELTA_OUT_OF_BOUNDS,
  INSUFFICIENT_HEADROOM,
  INSUFFICIENT_ROOM,
  USER_OVERRIDE;

  companion object {
    val TEMPO_DELTA_EXCEEDED: FallbackReason get() = TEMPO_OUT_OF_BOUNDS
    val MISSING_BEAT_DATA: FallbackReason get() = MISSING_PROFILE
  }
}

data class TransitionPlan(
  val outgoingSongId: String,
  val incomingSongId: String,
  val transitionType: TransitionType = TransitionType.PHRASE_ALIGNED_BLEND,
  val triggerTimeMs: Long,
  val incomingStartMs: Long,
  val overlapDurationMs: Long,
  val tempoRatio: Float = 1.0f,
  val pitchRatio: Float = 1.0f,
  val pitchShiftSemitones: Int = 0,
  val lowShelfDuckDb: Float = -10.0f,
  val confidence: Float = 1.0f,
  val harmonicScore: Float = 1.0f,
  val fallbackReason: FallbackReason = FallbackReason.NONE,
  val generationId: Long = 0L,
) {
  val isFallback: Boolean get() = fallbackReason != FallbackReason.NONE
  val overlapMs: Long get() = overlapDurationMs
  val currentSongId: String get() = outgoingSongId
}

sealed class TransitionPlanResult {
  abstract val plan: TransitionPlan

  data class Valid(override val plan: TransitionPlan) : TransitionPlanResult()

  data class Fallback(
    val reason: FallbackReason,
    val defaultPlan: TransitionPlan,
  ) : TransitionPlanResult() {
    override val plan: TransitionPlan get() = defaultPlan
  }
}
