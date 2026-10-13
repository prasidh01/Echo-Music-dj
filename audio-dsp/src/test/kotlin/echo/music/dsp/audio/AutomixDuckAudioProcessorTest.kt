package echo.music.dsp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class AutomixDuckAudioProcessorTest {

  private lateinit var processor: AutomixDuckAudioProcessor

  @Before
  fun setUp() {
    processor = AutomixDuckAudioProcessor(shelfFrequencyHz = 150.0, maxDuckDb = -10.0)
  }

  @Test
  fun testConfigurationStereoPcm16() {
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    val output = processor.configure(format)

    assertEquals(44100, output.sampleRate)
    assertEquals(2, output.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, output.encoding)
    assertTrue(processor.isActive)
  }

  @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
  fun testThrowsOnUnsupportedEncoding() {
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
    processor.configure(format)
  }

  @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
  fun testThrowsOnMoreThanTwoChannels() {
    val format = AudioProcessor.AudioFormat(44100, 4, C.ENCODING_PCM_16BIT)
    processor.configure(format)
  }

  @Test
  fun testDryPassthroughAtMixZero() {
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)
    processor.setMix(0.0f)

    val inputBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
    val inL: Short = 15000
    val inR: Short = -20000
    inputBuffer.putShort(inL)
    inputBuffer.putShort(inR)
    inputBuffer.putShort(inL)
    inputBuffer.putShort(inR)
    inputBuffer.flip()

    processor.queueInput(inputBuffer)
    val out = processor.getOutput()

    assertEquals(inL, out.getShort())
    assertEquals(inR, out.getShort())
    assertEquals(inL, out.getShort())
    assertEquals(inR, out.getShort())
  }

  @Test
  fun testLowShelfDuckingFrequencyResponse() {
    // T1-F08-03 and T1-F08-04: -10 dB at 80 Hz, 0 dB at 2 kHz
    val sampleRate = 44100
    val format = AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT)
    processor.configure(format)
    processor.setMix(1.0f) // full duck (-10 dB)

    // Warm up the processor so the 15ms mix ease fully converges to 1.0
    val warmupFrames = (sampleRate * 0.05).toInt()
    val warmupBuf = ByteBuffer.allocateDirect(warmupFrames * 2).order(ByteOrder.nativeOrder())
    repeat(warmupFrames) { warmupBuf.putShort(0.toShort()) }
    warmupBuf.flip()
    processor.queueInput(warmupBuf)
    processor.getOutput()

    // 1. Low frequency 60 Hz -> should be attenuated by ~ -10 dB (~0.316x)
    val lowFreqHz = 60.0
    val lowAmp = 10000.0
    val testFrames = 4000
    val lowBuf = ByteBuffer.allocateDirect(testFrames * 2).order(ByteOrder.nativeOrder())
    repeat(testFrames) { i ->
      val s = (lowAmp * sin(2.0 * PI * lowFreqHz * i / sampleRate)).toInt().toShort()
      lowBuf.putShort(s)
    }
    lowBuf.flip()
    processor.queueInput(lowBuf)
    val lowOut = processor.getOutput()

    var maxLowOut = 0
    var sampleCount = 0
    while (lowOut.hasRemaining()) {
      val s = abs(lowOut.getShort().toInt())
      // Check steady-state after initial transient (first 1000 samples)
      if (sampleCount > 1000) {
        maxLowOut = maxOf(maxLowOut, s)
      }
      sampleCount++
    }
    // Target: 10000 * 10^(-10/20) ~ 3162. Tolerance within [2800, 3600]
    assertTrue("Low freq output peak ($maxLowOut) should be attenuated by ~ -10 dB", maxLowOut in 2800..3600)

    // 2. High frequency 3000 Hz -> should pass through near unity gain (~10000)
    processor.flush()
    processor.setMix(1.0f)
    val highFreqHz = 3000.0
    val highAmp = 10000.0
    val highBuf = ByteBuffer.allocateDirect(testFrames * 2).order(ByteOrder.nativeOrder())
    repeat(testFrames) { i ->
      val s = (highAmp * sin(2.0 * PI * highFreqHz * i / sampleRate)).toInt().toShort()
      highBuf.putShort(s)
    }
    highBuf.flip()
    processor.queueInput(highBuf)
    val highOut = processor.getOutput()

    var maxHighOut = 0
    sampleCount = 0
    while (highOut.hasRemaining()) {
      val s = abs(highOut.getShort().toInt())
      // Check steady-state after initial transient
      if (sampleCount > 1000) {
        maxHighOut = maxOf(maxHighOut, s)
      }
      sampleCount++
    }
    // High frequency should pass through near 10000 (+/- 5%)
    assertTrue("High freq output peak ($maxHighOut) should pass through untouched", maxHighOut in 9500..10500)
  }

  @Test
  fun testMixEaseSmoothnessNoClicks() {
    val sampleRate = 44100
    val format = AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    val frames = 1000
    val buf1 = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
    repeat(frames) { buf1.putShort(20000.toShort()) }
    buf1.flip()
    processor.queueInput(buf1)
    processor.getOutput()

    // Step target mix from 0.0 to 1.0 abruptly
    processor.setMix(1.0f)

    val buf2 = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
    repeat(frames) { buf2.putShort(20000.toShort()) }
    buf2.flip()
    processor.queueInput(buf2)
    val out2 = processor.getOutput()

    var prev = out2.getShort().toInt()
    var maxDelta = 0
    while (out2.hasRemaining()) {
      val curr = out2.getShort().toInt()
      val delta = abs(curr - prev)
      if (delta > maxDelta) maxDelta = delta
      prev = curr
    }

    assertTrue("Max sample-to-sample delta was $maxDelta, which is too steep and may click", maxDelta < 100)
  }

  @Test
  fun testMixBoundsClamping() {
    processor.setMix(-0.5f)
    assertEquals(0.0f, processor.getTargetMix(), 0.001f)

    processor.setMix(1.5f)
    assertEquals(1.0f, processor.getTargetMix(), 0.001f)
  }

  @Test
  fun testStereoChannelIndependence() {
    val sampleRate = 44100
    val format = AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)
    processor.setMix(1.0f)

    val warmupFrames = 2000
    val warmupBuf = ByteBuffer.allocateDirect(warmupFrames * 4).order(ByteOrder.nativeOrder())
    repeat(warmupFrames) { warmupBuf.putShort(0); warmupBuf.putShort(0) }
    warmupBuf.flip()
    processor.queueInput(warmupBuf)
    processor.getOutput()

    // Left channel: 60 Hz bass (ducked). Right channel: 3000 Hz highs (untouched).
    val frames = 4000
    val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
    repeat(frames) { i ->
      val sLow = (10000.0 * sin(2.0 * PI * 60.0 * i / sampleRate)).toInt().toShort()
      val sHigh = (10000.0 * sin(2.0 * PI * 3000.0 * i / sampleRate)).toInt().toShort()
      buffer.putShort(sLow)
      buffer.putShort(sHigh)
    }
    buffer.flip()

    processor.queueInput(buffer)
    val out = processor.getOutput()

    var maxL = 0
    var maxR = 0
    var frameIdx = 0
    while (out.hasRemaining()) {
      val l = abs(out.getShort().toInt())
      val r = abs(out.getShort().toInt())
      if (frameIdx > 1000) {
        maxL = maxOf(maxL, l)
        maxR = maxOf(maxR, r)
      }
      frameIdx++
    }

    assertTrue("Left channel (bass) must be ducked to ~3162, was $maxL", maxL in 2800..3600)
    assertTrue("Right channel (highs) must remain near 10000, was $maxR", maxR in 9500..10500)
  }

  @Test
  fun testFlushAndResetLifecycle() {
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)
    processor.setMix(0.8f)
    assertTrue(processor.isActive)

    @Suppress("DEPRECATION")
    processor.flush()
    assertFalse(processor.isEnded)

    processor.reset()
    assertFalse(processor.isActive)
  }
}
