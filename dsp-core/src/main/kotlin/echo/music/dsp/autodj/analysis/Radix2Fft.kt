package echo.music.dsp.autodj.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 Cooley-Tukey Fast Fourier Transform and spectral windowing utilities.
 */
object Radix2Fft {

  /**
   * Performs an in-place decimation-in-time Radix-2 FFT on real and imaginary arrays.
   * Both arrays must be of identical length, and that length must be a power of two.
   */
  fun transform(re: FloatArray, im: FloatArray) {
    require(re.size == im.size) { "Real and imaginary arrays must have matching length" }
    val n = re.size
    require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a positive power of two, was $n" }

    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j or bit
      if (i < j) {
        val tr = re[i]
        re[i] = re[j]
        re[j] = tr
        val ti = im[i]
        im[i] = im[j]
        im[j] = ti
      }
    }

    var len = 2
    while (len <= n) {
      val ang = -2.0 * PI / len
      val wRe = cos(ang).toFloat()
      val wIm = sin(ang).toFloat()
      var i = 0
      while (i < n) {
        var curRe = 1f
        var curIm = 0f
        val halfLen = len / 2
        for (k in 0 until halfLen) {
          val idx1 = i + k
          val idx2 = idx1 + halfLen
          val uRe = re[idx1]
          val uIm = im[idx1]
          val vRe = re[idx2] * curRe - im[idx2] * curIm
          val vIm = re[idx2] * curIm + im[idx2] * curRe
          re[idx1] = uRe + vRe
          im[idx1] = uIm + vIm
          re[idx2] = uRe - vRe
          im[idx2] = uIm - vIm
          val nRe = curRe * wRe - curIm * wIm
          curIm = curRe * wIm + curIm * wRe
          curRe = nRe
        }
        i += len
      }
      len = len shl 1
    }
  }

  fun hanningWindow(size: Int): FloatArray {
    val window = FloatArray(size)
    for (i in 0 until size) {
      window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / size)).toFloat()
    }
    return window
  }

  fun hammingWindow(size: Int): FloatArray {
    val window = FloatArray(size)
    for (i in 0 until size) {
      window[i] = (0.54 - 0.46 * cos(2.0 * PI * i / size)).toFloat()
    }
    return window
  }

  fun nextPowerOfTwo(n: Int): Int {
    var v = n - 1
    v = v or (v shr 1)
    v = v or (v shr 2)
    v = v or (v shr 4)
    v = v or (v shr 8)
    v = v or (v shr 16)
    return v + 1
  }
}
