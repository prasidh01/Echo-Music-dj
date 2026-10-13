package echo.music.dsp.autodj.analysis

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Detects structural energy profile, track RMS energy, and dynamic intro/outro mix points.
 */
object EnergyEnvelopeDetector {

  const val ENERGY_BLOCK_MS = 500
  const val MAX_INTRO_SKIP_MS = 20_000L
  const val MAX_OUTRO_CUT_MS = 45_000L

  fun energyEnvelope(samples: FloatArray, sampleRate: Int): FloatArray {
    if (samples.isEmpty() || sampleRate <= 0) return FloatArray(0)
    val blockSize = (sampleRate * ENERGY_BLOCK_MS / 1000).coerceAtLeast(1)
    val numBlocks = samples.size / blockSize
    val env = FloatArray(numBlocks)
    for (b in 0 until numBlocks) {
      var sum = 0f
      val offset = b * blockSize
      for (i in 0 until blockSize) {
        val s = samples[offset + i]
        sum += s * s
      }
      env[b] = sqrt(sum / blockSize)
    }
    return env
  }

  fun calculateOverallEnergy(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) {
      sum += (s * s).toDouble()
    }
    val rms = sqrt(sum / samples.size).toFloat()
    return (rms * 2.0f).coerceIn(0f, 1f)
  }

  fun percentile(values: FloatArray, p: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val index = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
  }

  /**
   * First block where energy reaches and sustains near body level, snapped forward onto the beat grid.
   * Null when the track starts hot (no intro worth skipping).
   */
  fun detectMixIn(
    env: FloatArray,
    firstBeatOffsetMs: Long,
    periodMs: Float,
  ): Long? {
    if (env.size < 8 || periodMs <= 0f) return null
    val ref = percentile(env, 0.75f)
    if (ref <= 0f) return null

    var candidateBlock = -1
    for (i in 0 until env.size - 4) {
      if (
        env[i] >= 0.55f * ref &&
        env[i + 1] >= 0.40f * ref &&
        env[i + 2] >= 0.40f * ref &&
        env[i + 3] >= 0.40f * ref
      ) {
        candidateBlock = i
        break
      }
    }
    if (candidateBlock <= 0) return null // starts hot; keep default start

    val candidateMs = candidateBlock.toLong() * ENERGY_BLOCK_MS
    if (candidateMs > MAX_INTRO_SKIP_MS) return null

    // Snap forward to the next beat/downbeat
    val k = ceil((candidateMs - firstBeatOffsetMs) / periodMs.toDouble()).toLong()
    return (firstBeatOffsetMs + max(0L, k) * periodMs.toDouble()).roundToLong()
  }

  /**
   * Last moment the tail window is still at body loudness; everything after is outro.
   * Null when the track stays loud to the end.
   */
  fun detectMixOut(
    env: FloatArray,
    tailActualStartMs: Long,
    durationMs: Long,
  ): Long? {
    if (env.size < 8 || durationMs <= 0) return null
    val ref = percentile(env, 0.75f)
    if (ref <= 0f) return null

    var lastLoudBlock = -1
    for (i in env.indices.reversed()) {
      if (env[i] >= 0.50f * ref) {
        lastLoudBlock = i
        break
      }
    }
    if (lastLoudBlock < 0) return null

    val mixOutMs = tailActualStartMs + (lastLoudBlock + 1).toLong() * ENERGY_BLOCK_MS
    if (durationMs - mixOutMs < 3_000L) return null // Loud almost to the end: nothing to cut
    return max(mixOutMs, durationMs - MAX_OUTRO_CUT_MS)
  }
}
