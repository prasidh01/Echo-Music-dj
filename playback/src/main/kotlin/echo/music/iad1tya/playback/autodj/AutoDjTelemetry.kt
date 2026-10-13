package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.FallbackReason
import echo.music.domain.autodj.model.TransitionType

/**
 * Snapshot of real-time Auto DJ transition and harmonic telemetry.
 */
data class AutoDjTelemetry(
  val state: String = "Idle",
  val generationId: Long = 0L,
  val outgoingBpm: Float? = null,
  val incomingBpm: Float? = null,
  val tempoRatio: Float = 1.0f,
  val outgoingCamelotKey: String? = null,
  val incomingCamelotKey: String? = null,
  val stepDelta: Int = 0,
  val harmonicDistance: Int? = null,
  val harmonicScore: Float? = null,
  val transitionType: TransitionType? = null,
  val progress: Float = 0.0f,
  val fallbackReason: FallbackReason = FallbackReason.NONE,
  val confidence: Float = 1.0f,
  val autoDjState: AutoDjState? = null,
) {
  val isMixing: Boolean get() = state.equals("Mixing", ignoreCase = true) || autoDjState is AutoDjState.Mixing
  val isFallback: Boolean get() = fallbackReason != FallbackReason.NONE
}
