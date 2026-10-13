package echo.music.dsp.autodj.analysis

import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class BeatGridResult(
  val firstBeatOffsetMs: Long,
  val downbeatIntervalMs: Long,
  val phraseBeatCount: Int = 32,
  val beatPhaseFrames: Float,
  val downbeatBarIndex: Int,
)

/**
 * Multi-scale comb-filter beat grid estimator:
 * 1. 1-Beat phase alignment (finds periodic pulse phase 0..period).
 * 2. 4-Beat bar downbeat alignment (finds accented measure 1 of 4).
 * 3. 32-Beat structural phrase alignment.
 */
object BeatGridEstimator {

  /**
   * Comb filter: phase (in frames) maximizing summed flux at phase + k * period.
   */
  fun estimateBeatPhase(flux: FloatArray, periodFrames: Float): Float {
    val period = periodFrames.roundToInt().coerceAtLeast(1)
    var bestPhase = 0
    var bestSum = -1f

    for (phase in 0 until period) {
      var sum = 0f
      var i = phase
      while (i < flux.size) {
        sum += flux[i]
        i += period
      }
      if (sum > bestSum) {
        bestSum = sum
        bestPhase = phase
      }
    }
    return bestPhase.toFloat()
  }

  /**
   * 4-Beat bar comb filter: identifies which of the 4 beats is the accented downbeat (bar 1 of 4).
   */
  fun estimateDownbeatBar(flux: FloatArray, phaseFrames: Float, periodFrames: Float): Int {
    if (periodFrames <= 0f) return 0
    var bestBar = 0
    var bestSum = -1f

    for (bar in 0 until 4) {
      var sum = 0f
      var m = 0
      while (true) {
        val frameIdx = (phaseFrames + (4 * m + bar) * periodFrames).roundToInt()
        if (frameIdx >= flux.size) break
        if (frameIdx >= 0) {
          sum += flux[frameIdx]
        }
        m++
      }
      if (sum > bestSum) {
        bestSum = sum
        bestBar = bar
      }
    }
    return bestBar
  }

  /**
   * Estimates complete beat grid including downbeat offset and 4-beat bar interval.
   */
  fun estimateGrid(
    flux: FloatArray,
    bpm: Float,
    periodFrames: Float,
    frameRate: Float,
    windowStartMs: Long = 0L,
    phraseBeatCount: Int = 32,
  ): BeatGridResult {
    if (bpm <= 0f || frameRate <= 0f || periodFrames <= 0f) {
      return BeatGridResult(
        firstBeatOffsetMs = 0L,
        downbeatIntervalMs = 2000L,
        phraseBeatCount = phraseBeatCount,
        beatPhaseFrames = 0f,
        downbeatBarIndex = 0,
      )
    }

    val phaseFrames = estimateBeatPhase(flux, periodFrames)
    val downbeatBar = estimateDownbeatBar(flux, phaseFrames, periodFrames)

    val downbeatIntervalMs = ((60_000.0 / bpm) * 4.0).roundToLong()
    val anchorFrames = phaseFrames + downbeatBar * periodFrames
    val anchorMs = windowStartMs + (anchorFrames / frameRate * 1000f).roundToLong()

    val firstBeatOffsetMs = if (downbeatIntervalMs > 0) {
      ((anchorMs % downbeatIntervalMs) + downbeatIntervalMs) % downbeatIntervalMs
    } else {
      0L
    }

    return BeatGridResult(
      firstBeatOffsetMs = firstBeatOffsetMs,
      downbeatIntervalMs = downbeatIntervalMs,
      phraseBeatCount = phraseBeatCount,
      beatPhaseFrames = phaseFrames,
      downbeatBarIndex = downbeatBar,
    )
  }
}
