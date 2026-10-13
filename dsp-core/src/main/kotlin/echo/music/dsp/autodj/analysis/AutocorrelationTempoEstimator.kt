package echo.music.dsp.autodj.analysis

import kotlin.math.roundToInt

data class TempoEstimateResult(
  val bpm: Float,
  val periodFrames: Float,
  val periodMs: Float,
  val confidence: Float,
)

/**
 * Autocorrelation tempo estimator with harmonic weighting, parabolic peak refinement,
 * median-prominence confidence scoring, and canonical octave folding.
 */
object AutocorrelationTempoEstimator {

  const val MIN_BPM = 60f
  const val MAX_BPM = 180f
  const val MIN_CANONICAL_BPM = 70f
  const val MAX_CANONICAL_BPM = 140f

  fun estimateTempo(
    flux: FloatArray,
    frameRate: Float,
    minBpm: Float = MIN_BPM,
    maxBpm: Float = MAX_BPM,
  ): TempoEstimateResult? {
    if (frameRate <= 0f) return null
    val minLag = (frameRate * 60f / maxBpm).roundToInt()
    val maxLag = (frameRate * 60f / minBpm).roundToInt()
    if (flux.size < maxLag * 2 || minLag >= maxLag) return null

    val ac = FloatArray(maxLag + 1)
    for (lag in minLag..maxLag) {
      var sum = 0f
      val count = flux.size - lag
      for (i in 0 until count) {
        sum += flux[i] * flux[i + lag]
      }
      ac[lag] = sum / count
    }

    var mean = 0f
    for (lag in minLag..maxLag) mean += ac[lag]
    mean /= (maxLag - minLag + 1)
    if (mean <= 0f) return null

    var bestLag = -1
    var bestScore = 0f
    for (lag in minLag..maxLag) {
      if (lag > minLag && lag < maxLag && (ac[lag] < ac[lag - 1] || ac[lag] < ac[lag + 1])) continue
      var score = ac[lag]
      val doubleLag = lag * 2
      if (doubleLag <= maxLag) {
        score += 0.5f * ac[doubleLag]
      }
      if (score > bestScore) {
        bestScore = score
        bestLag = lag
      }
    }
    if (bestLag < 0) return null

    // Parabolic interpolation around best peak
    val refinedLag = if (bestLag in minLag + 1 until maxLag) {
      val y0 = ac[bestLag - 1]
      val y1 = ac[bestLag]
      val y2 = ac[bestLag + 1]
      val denom = y0 - 2f * y1 + y2
      if (denom != 0f) bestLag + 0.5f * (y0 - y2) / denom else bestLag.toFloat()
    } else {
      bestLag.toFloat()
    }

    // Find runner-up local peak outside the neighborhood of bestLag and its harmonic/subharmonic
    var runnerUp = 0f
    for (lag in minLag..maxLag) {
      if (lag > minLag && lag < maxLag && (ac[lag] < ac[lag - 1] || ac[lag] < ac[lag + 1])) continue
      if (kotlin.math.abs(lag - bestLag) <= 3) continue
      if (kotlin.math.abs(lag - bestLag * 2) <= 3) continue
      if (bestLag % 2 == 0 && kotlin.math.abs(lag - bestLag / 2) <= 3) continue
      if (ac[lag] > runnerUp) {
        runnerUp = ac[lag]
      }
    }

    // Confidence: Peak-to-Sidelobe Ratio (PSLR) combined with peak prominence over median
    val sortedAc = ac.copyOfRange(minLag, maxLag + 1).sorted()
    val median = sortedAc[sortedAc.size / 2]
    val pslr = if (ac[bestLag] > 0f) (1.0f - runnerUp / ac[bestLag]).coerceIn(0f, 1f) else 0f
    val ptoM = if (ac[bestLag] > 0f) ((ac[bestLag] - median) / ac[bestLag]).coerceIn(0f, 1f) else 0f
    val confidence = (pslr * ptoM).coerceIn(0f, 1f)

    var periodFrames = refinedLag
    var periodMs = periodFrames / frameRate * 1000f
    var bpm = 60_000f / periodMs

    // Canonical octave folding into [70, 140] BPM
    while (bpm < MIN_CANONICAL_BPM) {
      bpm *= 2f
      periodMs /= 2f
      periodFrames /= 2f
    }
    while (bpm >= MAX_CANONICAL_BPM) {
      bpm /= 2f
      periodMs *= 2f
      periodFrames *= 2f
    }

    return TempoEstimateResult(
      bpm = bpm,
      periodFrames = periodFrames,
      periodMs = periodMs,
      confidence = confidence,
    )
  }
}
