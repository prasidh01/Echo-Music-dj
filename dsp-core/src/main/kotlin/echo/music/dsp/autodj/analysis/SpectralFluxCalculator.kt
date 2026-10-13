package echo.music.dsp.autodj.analysis

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Calculates log-magnitude spectral flux onset envelopes with moving-average detrending.
 */
object SpectralFluxCalculator {

  const val DEFAULT_FFT_SIZE = 1024
  const val DEFAULT_HOP_SIZE = 512

  /**
   * Computes half-wave-rectified, log-compressed spectral flux across frames, then
   * subtracts local moving average so autocorrelation identifies sharp onsets.
   */
  fun calculateFlux(
    samples: FloatArray,
    fftSize: Int = DEFAULT_FFT_SIZE,
    hopSize: Int = DEFAULT_HOP_SIZE,
  ): FloatArray {
    if (samples.size < fftSize + hopSize) return FloatArray(0)

    val window = Radix2Fft.hanningWindow(fftSize)
    val numFrames = (samples.size - fftSize) / hopSize
    val bins = fftSize / 2
    val flux = FloatArray(numFrames)
    val prevMag = FloatArray(bins)
    val re = FloatArray(fftSize)
    val im = FloatArray(fftSize)

    for (frame in 0 until numFrames) {
      val offset = frame * hopSize
      for (i in 0 until fftSize) {
        re[i] = samples[offset + i] * window[i]
        im[i] = 0f
      }
      Radix2Fft.transform(re, im)
      var sum = 0f
      for (b in 0 until bins) {
        val magnitude = sqrt(re[b] * re[b] + im[b] * im[b])
        val mag = ln(1f + 10f * magnitude)
        val diff = mag - prevMag[b]
        if (diff > 0f) sum += diff
        prevMag[b] = mag
      }
      flux[frame] = sum
    }

    // Local mean subtraction (moving window of ~8 frames) to remove slow dynamics
    val meanWindow = (0.5f * fftSize / hopSize * 8).roundToInt().coerceAtLeast(8)
    val detrended = FloatArray(numFrames)
    for (i in 0 until numFrames) {
      val lo = max(0, i - meanWindow)
      val hi = min(numFrames - 1, i + meanWindow)
      var mean = 0f
      for (j in lo..hi) mean += flux[j]
      mean /= (hi - lo + 1)
      detrended[i] = max(0f, flux[i] - mean)
    }
    return detrended
  }
}
