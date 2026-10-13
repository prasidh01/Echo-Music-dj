package echo.music.dsp.autodj.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class KeyAnalysisResult(
  val pitchClass: Int,
  val isMinor: Boolean,
  val camelotKey: String,
)

/**
 * Chroma key detector using Krumhansl-Schmuckler cognitive key profiles
 * across 12 tonics (Major and Minor), mapping to standard Camelot Wheel notation.
 */
object ChromaKeyDetector {

  private const val FFT_SIZE = 4096
  private const val HOP_SIZE = 2048

  // Krumhansl-Schmuckler key profiles: relative pull of each scale degree on the tonic.
  private val MAJOR_PROFILE = floatArrayOf(
    6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
  )
  private val MINOR_PROFILE = floatArrayOf(
    6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
  )

  fun estimateKey(samples: FloatArray, sampleRate: Int): KeyAnalysisResult? {
    if (samples.size < FFT_SIZE * 2 || sampleRate <= 0) return null

    val window = Radix2Fft.hanningWindow(FFT_SIZE)
    val numFrames = (samples.size - FFT_SIZE) / HOP_SIZE
    if (numFrames < 2) return null

    val chroma = FloatArray(12)
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)
    val binHz = sampleRate.toFloat() / FFT_SIZE
    val minBin = (65f / binHz).toInt().coerceAtLeast(1)
    val maxBin = (2000f / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)

    for (frame in 0 until numFrames) {
      val offset = frame * HOP_SIZE
      for (i in 0 until FFT_SIZE) {
        re[i] = samples[offset + i] * window[i]
        im[i] = 0f
      }
      Radix2Fft.transform(re, im)
      for (b in minBin..maxBin) {
        val mag = sqrt(re[b] * re[b] + im[b] * im[b])
        if (mag <= 0f) continue
        val freq = b * binHz
        val midi = 69.0 + 12.0 * (ln(freq / 440.0) / ln(2.0))
        val pitchClass = ((midi.roundToInt() % 12) + 12) % 12
        chroma[pitchClass] += mag
      }
    }

    val sum = chroma.sum()
    if (sum <= 1e-6f) return null
    for (i in chroma.indices) {
      chroma[i] /= sum
    }

    var bestScore = Float.NEGATIVE_INFINITY
    var bestPitchClass = 0
    var bestIsMinor = false

    for (tonic in 0 until 12) {
      val majorScore = correlateChroma(chroma, MAJOR_PROFILE, tonic)
      if (majorScore > bestScore) {
        bestScore = majorScore
        bestPitchClass = tonic
        bestIsMinor = false
      }
      val minorScore = correlateChroma(chroma, MINOR_PROFILE, tonic)
      if (minorScore > bestScore) {
        bestScore = minorScore
        bestPitchClass = tonic
        bestIsMinor = true
      }
    }

    val camelotCode = toCamelotKey(bestPitchClass, bestIsMinor)
    return KeyAnalysisResult(
      pitchClass = bestPitchClass,
      isMinor = bestIsMinor,
      camelotKey = camelotCode,
    )
  }

  private fun correlateChroma(chroma: FloatArray, profile: FloatArray, tonic: Int): Float {
    var sum = 0f
    for (degree in 0 until 12) {
      sum += chroma[(degree + tonic) % 12] * profile[degree]
    }
    return sum
  }

  fun toCamelotKey(pitchClass: Int, isMinor: Boolean): String {
    val effectiveMajor = if (isMinor) (pitchClass + 3) % 12 else pitchClass
    val raw = (effectiveMajor * 7 + 8) % 12
    val num = if (raw == 0) 12 else raw
    val mode = if (isMinor) "A" else "B"
    return "$num$mode"
  }
}
