package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.FallbackReason
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object AutoDjTelemetryFormatter {

  /**
   * Formats harmonic Camelot key transition string:
   * e.g. format("8A", "9A", 1) -> "8A -> 9A (+1 step)"
   */
  fun format(outKey: String?, inKey: String?, stepDelta: Int): String {
    if (outKey.isNullOrBlank() || inKey.isNullOrBlank()) {
      return "BPM: -- | Key: --"
    }
    val stepSign = when {
      stepDelta > 0 -> "+$stepDelta"
      stepDelta < 0 -> "$stepDelta"
      else -> "+0"
    }
    val unit = if (abs(stepDelta) == 1) "step" else "steps"
    return "$outKey -> $inKey ($stepSign $unit)"
  }

  /**
   * Formats crossfade progress display:
   * e.g. format(0.65f) -> "Mixing: 65%"
   */
  fun format(progress: Float): String {
    val percent = (progress * 100f).roundToInt().coerceIn(0, 100)
    return "Mixing: $percent%"
  }

  /**
   * Formats fallback diagnostic string:
   * e.g. format(FallbackReason.LOW_CONFIDENCE, 0.32f) -> "Fallback: Low Confidence (0.32)"
   */
  fun format(reason: FallbackReason, confidence: Float): String {
    val confFormatted = String.format(Locale.US, "%.2f", confidence)
    val label = formatReasonLabel(reason)
    return "Fallback: $label ($confFormatted)"
  }

  fun format(reason: FallbackReason): String {
    return "Fallback: ${formatReasonLabel(reason)}"
  }

  private fun formatReasonLabel(reason: FallbackReason): String = when (reason) {
    FallbackReason.LOW_CONFIDENCE -> "Low Confidence"
    FallbackReason.MISSING_PROFILE -> "Missing Profile"
    FallbackReason.TEMPO_OUT_OF_BOUNDS, FallbackReason.TEMPO_DELTA_OUT_OF_BOUNDS -> "Tempo Out Of Bounds"
    FallbackReason.INSUFFICIENT_HEADROOM, FallbackReason.INSUFFICIENT_ROOM -> "Insufficient Headroom"
    FallbackReason.USER_OVERRIDE -> "User Override"
    FallbackReason.NONE -> "None"
  }
}
