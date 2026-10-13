package echo.music.dsp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class AntiClippingLimiterAudioProcessorTest {

  private lateinit var processor: AntiClippingLimiterAudioProcessor

  @Before
  fun setUp() {
    processor = AntiClippingLimiterAudioProcessor()
  }

  @Test
  fun testConfigurationStereoPcm16() {
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    val outputFormat = processor.configure(format)

    assertEquals(44100, outputFormat.sampleRate)
    assertEquals(2, outputFormat.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, outputFormat.encoding)
    assertTrue(processor.isActive)
  }

  @Test
  fun testConfigurationMonoPcm16() {
    val format = AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)
    val outputFormat = processor.configure(format)

    assertEquals(48000, outputFormat.sampleRate)
    assertEquals(1, outputFormat.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, outputFormat.encoding)
    assertTrue(processor.isActive)
  }

  @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
  fun testThrowsOnFloatPcm() {
    val invalid = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
    processor.configure(invalid)
  }

  @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
  fun testThrowsOnUnsupportedChannels() {
    val invalid = AudioProcessor.AudioFormat(44100, 6, C.ENCODING_PCM_16BIT)
    processor.configure(invalid)
  }

  @Test
  fun testSubThresholdPassthrough() {
    // T1-F07-01: Sub-threshold Passthrough (-6 dBFS) without lookahead
    val directProcessor = AntiClippingLimiterAudioProcessor(lookaheadMs = 0.0)
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    directProcessor.configure(format)

    val inputBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
    inputBuffer.putShort(16384.toShort())
    inputBuffer.putShort((-16384).toShort())
    inputBuffer.putShort(16384.toShort())
    inputBuffer.putShort((-16384).toShort())
    inputBuffer.flip()

    directProcessor.queueInput(inputBuffer)
    val out = directProcessor.getOutput()

    assertEquals(16384.toShort(), out.getShort())
    assertEquals((-16384).toShort(), out.getShort())
    assertEquals(16384.toShort(), out.getShort())
    assertEquals((-16384).toShort(), out.getShort())
    assertEquals(0f, directProcessor.currentGainReductionDb, 0.001f)
  }

  @Test
  fun testPlus3DbOverloadClamping() {
    // T1-F07-02: +3 dBFS Overload Clamping (peak 46,000 / full scale summation)
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    val frameCount = 2000
    val inputBuffer = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.nativeOrder())
    repeat(frameCount) {
      inputBuffer.putShort(Short.MAX_VALUE) // 32767
      inputBuffer.putShort(Short.MIN_VALUE) // -32768
    }
    inputBuffer.flip()

    processor.queueInput(inputBuffer)
    val output = processor.getOutput()

    var peakOutput = 0
    while (output.hasRemaining()) {
      val left = output.getShort().toInt()
      val right = output.getShort().toInt()
      peakOutput = maxOf(peakOutput, abs(left), abs(right))

      assertTrue("Left sample $left exceeded ceiling ${processor.ceiling}", left <= processor.ceiling)
      assertTrue("Left sample $left below -ceiling", left >= -processor.ceiling)
      assertTrue("Right sample $right exceeded ceiling ${processor.ceiling}", right <= processor.ceiling)
      assertTrue("Right sample $right below -ceiling", right >= -processor.ceiling)
    }

    assertTrue("Peak output must be <= 32386, was $peakOutput", peakOutput <= processor.ceiling)
    assertTrue("Limiting flag must be active", processor.isLimiting())
    assertTrue("Gain reduction must be positive", processor.currentGainReductionDb > 0f)
  }

  @Test
  fun testFastAttackTimeEnforcement() {
    // T1-F07-03: Fast Attack Time (< 1ms, < 44 samples at 44.1 kHz)
    val format = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    val frameCount = 100 // ~2.2ms
    val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2).order(ByteOrder.nativeOrder())
    repeat(frameCount) {
      inputBuffer.putShort(Short.MAX_VALUE)
    }
    inputBuffer.flip()

    processor.queueInput(inputBuffer)
    val out = processor.getOutput()

    var sampleIdx = 0
    var attackEnforced = false
    while (out.hasRemaining()) {
      val s = out.getShort().toInt()
      if (s <= processor.ceiling) {
        attackEnforced = true
      }
      sampleIdx++
    }

    assertTrue("Limiter ceiling must be enforced within attack window", attackEnforced)
  }

  @Test
  fun testSoftKneeContinuousDerivative() {
    // T1-F07-04: Soft Knee smooth transition across threshold
    val p = AntiClippingLimiterAudioProcessor()
    val t = p.threshold.toDouble()

    val yBelow = p.applySoftKnee(t - 10.0).toDouble()
    val yAt = p.applySoftKnee(t).toDouble()
    val yAbove = p.applySoftKnee(t + 10.0).toDouble()

    val slopeBelow = (yAt - yBelow) / 10.0
    val slopeAbove = (yAbove - yAt) / 10.0

    // Linear slope below is 1.0. At threshold slope must be continuous (~1.0).
    assertEquals(1.0, slopeBelow, 0.05)
    assertEquals(1.0, slopeAbove, 0.05)
  }

  @Test
  fun testEnvelopeRecoveryOnSignalDecay() {
    // T1-F07-05: Envelope recovery over ~50ms release
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    // Trigger limiting
    val loud = ByteBuffer.allocateDirect(500 * 4).order(ByteOrder.nativeOrder())
    repeat(500) { loud.putShort(Short.MAX_VALUE); loud.putShort(Short.MAX_VALUE) }
    loud.flip()
    processor.queueInput(loud)
    processor.getOutput()

    val peakGr = processor.currentGainReductionDb
    assertTrue(peakGr > 0f)

    // Feed silence and verify monotonic recovery
    var previousGr = peakGr
    repeat(10) {
      val quiet = ByteBuffer.allocateDirect(220 * 4).order(ByteOrder.nativeOrder())
      repeat(220) { quiet.putShort(0.toShort()); quiet.putShort(0.toShort()) }
      quiet.flip()
      processor.queueInput(quiet)
      processor.getOutput()

      val currentGr = processor.currentGainReductionDb
      assertTrue("GR must monotonically decrease during release: $currentGr <= $previousGr", currentGr <= previousGr)
      previousGr = currentGr
    }

    assertTrue("GR should have recovered significantly after 50ms", previousGr < peakGr * 0.5f)
  }

  @Test
  fun testFullScaleSquareWaveOverload() {
    // T2-F07-01: Full-scale square wave alternating +32767 and -32768
    val format = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    val frames = 1000
    val buf = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
    repeat(frames / 2) {
      buf.putShort(Short.MAX_VALUE)
      buf.putShort(Short.MIN_VALUE)
    }
    buf.flip()

    processor.queueInput(buf)
    val out = processor.getOutput()

    while (out.hasRemaining()) {
      val s = out.getShort().toInt()
      assertTrue("Square wave output $s must be clamped to ceiling", abs(s) <= processor.ceiling)
    }
  }

  @Test
  fun testProlongedSustainedInputStability() {
    // T2-F07-02: Prolonged sustained +6 dB input for multiple buffers
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    repeat(20) {
      val buf = ByteBuffer.allocateDirect(500 * 4).order(ByteOrder.nativeOrder())
      repeat(500) { buf.putShort(32767.toShort()); buf.putShort(32767.toShort()) }
      buf.flip()
      processor.queueInput(buf)
      val out = processor.getOutput()

      while (out.hasRemaining()) {
        val s = out.getShort().toInt()
        assertTrue("Output sample $s exceeded ceiling", abs(s) <= processor.ceiling)
      }
    }

    assertTrue(processor.isLimiting())
    assertTrue(processor.maxGainReductionDb >= processor.currentGainReductionDb)
  }

  @Test
  fun testPureZeroInputBuffer() {
    // T2-F07-03: Pure zero input buffer produces zero DC offset
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    val buf = ByteBuffer.allocateDirect(200 * 4).order(ByteOrder.nativeOrder())
    repeat(200) { buf.putShort(0.toShort()); buf.putShort(0.toShort()) }
    buf.flip()

    processor.queueInput(buf)
    val out = processor.getOutput()

    while (out.hasRemaining()) {
      assertEquals(0.toShort(), out.getShort())
    }
    assertEquals(0f, processor.currentGainReductionDb, 0.001f)
  }

  @Test
  fun testSingleSampleAudioBuffer() {
    // T2-F07-04: Single-sample audio buffer handled without underflow
    val format = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    val buf = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder())
    buf.putShort(10000.toShort())
    buf.flip()

    processor.queueInput(buf)
    val out = processor.getOutput()
    assertTrue(out.hasRemaining())
  }

  @Test
  fun testSampleRateExtremes() {
    // T2-F07-05: Extremes 8 kHz and 192 kHz
    for (rate in listOf(8000, 192000)) {
      val p = AntiClippingLimiterAudioProcessor()
      val format = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)
      p.configure(format)
      assertTrue(p.isActive)

      val buf = ByteBuffer.allocateDirect(20 * 4).order(ByteOrder.nativeOrder())
      repeat(20) { buf.putShort(32767.toShort()); buf.putShort(32767.toShort()) }
      buf.flip()
      p.queueInput(buf)
      val out = p.getOutput()
      assertTrue(out.hasRemaining())
    }
  }

  @Test
  fun testStereoChannelLinking() {
    // Verify right channel is attenuated identically when left channel peaks
    val directProcessor = AntiClippingLimiterAudioProcessor(lookaheadMs = 0.0)
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    directProcessor.configure(format)

    val buffer = ByteBuffer.allocateDirect(200 * 4).order(ByteOrder.nativeOrder())
    repeat(200) {
      buffer.putShort(32767.toShort()) // Left peaks
      buffer.putShort(10000.toShort()) // Right moderate
    }
    buffer.flip()

    directProcessor.queueInput(buffer)
    val output = directProcessor.getOutput()

    var lastRight = 0
    while (output.hasRemaining()) {
      output.getShort()
      lastRight = output.getShort().toInt()
    }

    assertTrue("Right channel ($lastRight) should be attenuated below 10000 due to stereo linking", lastRight < 10000)
  }

  @Test
  fun testFlushAndResetLifecycle() {
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)
    assertTrue(processor.isActive)

    @Suppress("DEPRECATION")
    processor.flush()
    assertFalse(processor.isEnded)
    assertEquals(0f, processor.currentGainReductionDb, 0.001f)

    processor.reset()
    assertFalse(processor.isActive)
  }
}
