package echo.music.dsp.autodj

import kotlin.math.abs

data class TempoMatchResult(
  val ratio: Float,
  val foldFactor: Int, // -1 (halve incoming), 0 (direct), +1 (double incoming)
  val isCompatible: Boolean,
  val delta: Float,
)

object TempoMatcher {
  const val DEFAULT_TOLERANCE = 0.08f // +/- 8%

  fun calculateRatio(
    outgoingBpm: Float,
    incomingBpm: Float,
    tolerance: Float = DEFAULT_TOLERANCE,
  ): TempoMatchResult {
    if (outgoingBpm <= 0f || incomingBpm <= 0f) {
      return TempoMatchResult(
        ratio = 1.0f,
        foldFactor = 0,
        isCompatible = false,
        delta = 1.0f,
      )
    }

    val rawRatio = incomingBpm / outgoingBpm
    val candidateFactors = listOf(0, 1, -1)
    var bestFactor = 0
    var bestDelta = Float.MAX_VALUE
    var bestRatio = rawRatio

    for (k in candidateFactors) {
      val candidateRatio = when (k) {
        1 -> rawRatio * 2f
        -1 -> rawRatio * 0.5f
        else -> rawRatio
      }
      val delta = abs(candidateRatio - 1.0f)
      if (delta < bestDelta) {
        bestDelta = delta
        bestFactor = k
        bestRatio = candidateRatio
      }
    }

    val isCompatible = bestDelta <= (tolerance + 1e-5f)
    return TempoMatchResult(
      ratio = bestRatio,
      foldFactor = bestFactor,
      isCompatible = isCompatible,
      delta = bestDelta,
    )
  }
}
