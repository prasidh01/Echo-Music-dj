package echo.music.dsp.autodj.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class Radix2FftTest {

  @Test
  fun testImpulseResponseFlatSpectrum() {
    val size = 64
    val re = FloatArray(size) { if (it == 0) 1f else 0f }
    val im = FloatArray(size)

    Radix2Fft.transform(re, im)

    for (i in 0 until size) {
      val mag = sqrt(re[i] * re[i] + im[i] * im[i])
      assertEquals(1.0f, mag, 1e-4f)
    }
  }

  @Test
  fun testSinusoidalPeakBinDetection() {
    val size = 256
    val sampleRate = 44100
    val freqHz = 1720.0f
    val re = FloatArray(size)
    val im = FloatArray(size)
    val window = Radix2Fft.hanningWindow(size)

    for (i in 0 until size) {
      re[i] = (sin(2.0 * PI * freqHz * i / sampleRate) * window[i]).toFloat()
    }

    Radix2Fft.transform(re, im)

    var maxMag = 0f
    var peakBin = 0
    for (i in 0 until size / 2) {
      val mag = sqrt(re[i] * re[i] + im[i] * im[i])
      if (mag > maxMag) {
        maxMag = mag
        peakBin = i
      }
    }

    val expectedBin = (freqHz * size / sampleRate).toInt()
    assertTrue("Peak bin $peakBin should be near expected bin $expectedBin", kotlin.math.abs(peakBin - expectedBin) <= 1)
  }

  @Test
  fun testNextPowerOfTwo() {
    assertEquals(1, Radix2Fft.nextPowerOfTwo(1))
    assertEquals(2, Radix2Fft.nextPowerOfTwo(2))
    assertEquals(16, Radix2Fft.nextPowerOfTwo(15))
    assertEquals(1024, Radix2Fft.nextPowerOfTwo(1000))
  }
}
