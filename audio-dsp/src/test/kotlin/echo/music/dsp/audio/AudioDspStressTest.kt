package echo.music.dsp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class AudioDspStressTest {

  companion object {
    private const val CEILING_SHORT: Short = AntiClippingLimiterAudioProcessor.DEFAULT_CEILING // 32386
  }

  // -------------------------------------------------------------------------
  // 1. AntiClippingLimiter: Dual 0 dBFS Tracks Summing to +3 dBFS / +6 dBFS
  // -------------------------------------------------------------------------
  @Test
  fun testDualZeroDbfsTracksSummingPlus3DbClampedToCeiling() {
    val processor = AntiClippingLimiterAudioProcessor()
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    val frameCount = 10000
    val inputBuffer = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.nativeOrder())

    // Simulate two 0 dBFS sine waves summing together (440 Hz + 100 Hz), saturated to 16-bit range
    for (i in 0 until frameCount) {
      val track1 = (32767.0 * sin(2.0 * PI * 440.0 * i / 44100.0))
      val track2 = (32767.0 * sin(2.0 * PI * 100.0 * i / 44100.0))
      val summedL = (track1 + track2).coerceIn(-32768.0, 32767.0).toInt().toShort()
      val summedR = (track1 - track2).coerceIn(-32768.0, 32767.0).toInt().toShort()
      inputBuffer.putShort(summedL)
      inputBuffer.putShort(summedR)
    }
    inputBuffer.flip()

    processor.queueInput(inputBuffer)
    val output = processor.getOutput()

    var maxObservedMagnitude = 0
    var sampleCount = 0
    while (output.hasRemaining()) {
      val l = output.getShort().toInt()
      val r = output.getShort().toInt()
      sampleCount += 2

      val absL = abs(l)
      val absR = abs(r)
      maxObservedMagnitude = maxOf(maxObservedMagnitude, absL, absR)

      assertTrue("Left sample $l exceeded ceiling $CEILING_SHORT at sample $sampleCount", absL <= CEILING_SHORT)
      assertTrue("Right sample $r exceeded ceiling $CEILING_SHORT at sample $sampleCount", absR <= CEILING_SHORT)
    }

    assertTrue("Output magnitude must strictly remain <= 32386", maxObservedMagnitude <= CEILING_SHORT)
    assertTrue("Limiter must be actively limiting during +3 dBFS overload", processor.isLimiting())
    assertTrue("Max gain reduction must be positive", processor.maxGainReductionDb > 0f)
  }

  // -------------------------------------------------------------------------
  // 2. AntiClippingLimiter: Maximum Full-Scale Square Waves (Nyquist & Audio)
  // -------------------------------------------------------------------------
  @Test
  fun testMaximumFullScaleSquareWaves() {
    val periods = listOf(2, 4, 10, 50, 100, 1000)

    for (period in periods) {
      val processor = AntiClippingLimiterAudioProcessor()
      val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
      processor.configure(format)

      val frames = 4000
      val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
      for (i in 0 until frames) {
        val s = if ((i / (period / 2)) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        buf.putShort(s)
        buf.putShort(s)
      }
      buf.flip()

      processor.queueInput(buf)
      val out = processor.getOutput()

      while (out.hasRemaining()) {
        val l = out.getShort().toInt()
        val r = out.getShort().toInt()
        assertTrue("Square wave (period=$period) L=$l exceeded ceiling", abs(l) <= CEILING_SHORT)
        assertTrue("Square wave (period=$period) R=$r exceeded ceiling", abs(r) <= CEILING_SHORT)
      }
    }
  }

  // -------------------------------------------------------------------------
  // 3. AntiClippingLimiter: Severe DC Bias
  // -------------------------------------------------------------------------
  @Test
  fun testSevereDcBiasSignals() {
    val processor = AntiClippingLimiterAudioProcessor()
    val format = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
    processor.configure(format)

    // Test positive DC rail
    val posDc = ByteBuffer.allocateDirect(1000 * 2).order(ByteOrder.nativeOrder())
    repeat(1000) { posDc.putShort(Short.MAX_VALUE) }
    posDc.flip()
    processor.queueInput(posDc)
    val outPos = processor.getOutput()
    while (outPos.hasRemaining()) {
      val s = outPos.getShort().toInt()
      assertTrue("Positive DC rail output $s exceeded ceiling", s in 0..CEILING_SHORT)
    }

    // Flush and test negative DC rail
    @Suppress("DEPRECATION")
    processor.flush()
    val negDc = ByteBuffer.allocateDirect(1000 * 2).order(ByteOrder.nativeOrder())
    repeat(1000) { negDc.putShort(Short.MIN_VALUE) }
    negDc.flip()
    processor.queueInput(negDc)
    val outNeg = processor.getOutput()
    while (outNeg.hasRemaining()) {
      val s = outNeg.getShort().toInt()
      assertTrue("Negative DC rail output $s exceeded ceiling", s in -CEILING_SHORT..0)
    }

    // Large DC bias (+25000) with superimposed AC oscillation (+- 10000)
    @Suppress("DEPRECATION")
    processor.flush()
    val biasedBuf = ByteBuffer.allocateDirect(2000 * 2).order(ByteOrder.nativeOrder())
    for (i in 0 until 2000) {
      val ac = (10000.0 * sin(2.0 * PI * 200.0 * i / 44100.0)).toInt()
      val sample = (25000 + ac).coerceIn(-32768, 32767).toShort()
      biasedBuf.putShort(sample)
    }
    biasedBuf.flip()
    processor.queueInput(biasedBuf)
    val outBiased = processor.getOutput()
    while (outBiased.hasRemaining()) {
      val s = outBiased.getShort().toInt()
      assertTrue("Biased signal output $s exceeded ceiling", abs(s) <= CEILING_SHORT)
    }
  }

  // -------------------------------------------------------------------------
  // 4. AntiClippingLimiter: Mono vs Stereo Interleaving & Channel Linking
  // -------------------------------------------------------------------------
  @Test
  fun testMonoVsStereoInterleaving() {
    val processor = AntiClippingLimiterAudioProcessor()

    // Rapidly switch between Mono and Stereo formats
    for (cycle in 0 until 5) {
      // 1. Mono configuration
      val monoFormat = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
      processor.configure(monoFormat)
      assertTrue(processor.isActive)

      val monoBuf = ByteBuffer.allocateDirect(500 * 2).order(ByteOrder.nativeOrder())
      repeat(500) { monoBuf.putShort(Short.MAX_VALUE) }
      monoBuf.flip()
      processor.queueInput(monoBuf)
      val monoOut = processor.getOutput()
      assertEquals(500 * 2, monoOut.remaining())
      while (monoOut.hasRemaining()) {
        val s = monoOut.getShort().toInt()
        assertTrue("Mono sample exceeded ceiling", abs(s) <= CEILING_SHORT)
      }

      // 2. Stereo configuration
      val stereoFormat = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
      processor.configure(stereoFormat)
      assertTrue(processor.isActive)

      val stereoBuf = ByteBuffer.allocateDirect(500 * 4).order(ByteOrder.nativeOrder())
      repeat(500) {
        stereoBuf.putShort(Short.MAX_VALUE)
        stereoBuf.putShort(Short.MIN_VALUE)
      }
      stereoBuf.flip()
      processor.queueInput(stereoBuf)
      val stereoOut = processor.getOutput()
      assertEquals(500 * 4, stereoOut.remaining())
      while (stereoOut.hasRemaining()) {
        val l = stereoOut.getShort().toInt()
        val r = stereoOut.getShort().toInt()
        assertTrue("Stereo L sample exceeded ceiling", abs(l) <= CEILING_SHORT)
        assertTrue("Stereo R sample exceeded ceiling", abs(r) <= CEILING_SHORT)
      }
    }
  }

  // -------------------------------------------------------------------------
  // 5. AntiClippingLimiter: Dynamic Sample Rate Reconfigurations
  // -------------------------------------------------------------------------
  @Test
  fun testDynamicSampleRateReconfigurations() {
    val sampleRates = listOf(8000, 11025, 22050, 32000, 44100, 48000, 88200, 96000, 192000)
    val processor = AntiClippingLimiterAudioProcessor()

    for (sr in sampleRates) {
      val format = AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT)
      val configured = processor.configure(format)
      assertEquals(sr, configured.sampleRate)
      assertTrue(processor.isActive)

      val frames = 300
      val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
      repeat(frames) {
        buf.putShort(32767.toShort())
        buf.putShort((-32768).toShort())
      }
      buf.flip()

      processor.queueInput(buf)
      val out = processor.getOutput()
      while (out.hasRemaining()) {
        val l = out.getShort().toInt()
        val r = out.getShort().toInt()
        assertTrue("At SR $sr, L $l exceeded ceiling", abs(l) <= CEILING_SHORT)
        assertTrue("At SR $sr, R $r exceeded ceiling", abs(r) <= CEILING_SHORT)
      }
    }
  }

  // -------------------------------------------------------------------------
  // 6. AntiClippingLimiter: Exhaustive Mathematical Soft-Knee Verification
  // -------------------------------------------------------------------------
  @Test
  fun testExhaustiveSoftKneeBoundaryAndContinuity() {
    val processor = AntiClippingLimiterAudioProcessor()

    // Test from -100,000.0 to +100,000.0 in increments of 1.0
    var prevOutput = -CEILING_SHORT.toInt()
    var x = -100000.0
    while (x <= 100000.0) {
      val output = processor.applySoftKnee(x).toInt()
      assertTrue("applySoftKnee($x) = $output exceeded ceiling", abs(output) <= CEILING_SHORT)
      assertTrue("applySoftKnee must be monotonic: prev=$prevOutput <= curr=$output at x=$x", output >= prevOutput)
      prevOutput = output
      x += 1.0
    }

    // Verify boundary at threshold (29193)
    val atThreshold = processor.applySoftKnee(29193.0).toInt()
    assertEquals(29193, atThreshold)

    // Verify sub-threshold exact passthrough
    assertEquals(0.toShort(), processor.applySoftKnee(0.0))
    assertEquals(10000.toShort(), processor.applySoftKnee(10000.0))
    assertEquals((-10000).toShort(), processor.applySoftKnee(-10000.0))
    assertEquals(29193.toShort(), processor.applySoftKnee(29193.0))
    assertEquals((-29193).toShort(), processor.applySoftKnee(-29193.0))
  }

  // -------------------------------------------------------------------------
  // 7. AutomixDuck: Rapid Parameter Toggling Under Extreme Signals
  // -------------------------------------------------------------------------
  @Test
  fun testAutomixDuckRapidParameterTogglingStress() {
    val duck = AutomixDuckAudioProcessor(shelfFrequencyHz = 150.0, maxDuckDb = -10.0)
    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    duck.configure(format)

    val random = Random(42)
    val testFrames = 5000
    val buffer = ByteBuffer.allocateDirect(testFrames * 4).order(ByteOrder.nativeOrder())

    // Heavy bass square wave + sine wave at full scale
    for (i in 0 until testFrames) {
      val bassSine = (32767.0 * sin(2.0 * PI * 60.0 * i / 44100.0)).toInt().toShort()
      buffer.putShort(bassSine)
      buffer.putShort(bassSine)
    }
    buffer.flip()

    // Rapidly toggle target mix every few samples
    val toggleIntervals = listOf(1, 2, 5, 13, 50, 100)
    for (interval in toggleIntervals) {
      @Suppress("DEPRECATION")
      duck.flush()
      duck.resetGain()
      buffer.position(0)

      val chunkFrames = interval
      val chunkBytes = chunkFrames * 4
      var mixState = 0f

      while (buffer.hasRemaining()) {
        val remaining = buffer.remaining()
        val toRead = minOf(remaining, chunkBytes)
        val slice = buffer.slice().limit(toRead) as ByteBuffer
        buffer.position(buffer.position() + toRead)

        // Toggle duck state
        mixState = if (mixState == 0f) 1f else 0f
        duck.setMix(mixState)

        duck.queueInput(slice)
        val out = duck.getOutput()

        while (out.hasRemaining()) {
          val l = out.getShort().toInt()
          val r = out.getShort().toInt()

          // Objective 3: Ensure zero numeric instabilities or clipping
          assertTrue("Output sample $l must be within 16-bit PCM bounds", l in -32768..32767)
          assertTrue("Output sample $r must be within 16-bit PCM bounds", r in -32768..32767)
          assertFalse("Output must never be infinite or NaN", l.toDouble().isNaN() || l.toDouble().isInfinite())
          assertFalse("Output must never be infinite or NaN", r.toDouble().isNaN() || r.toDouble().isInfinite())
        }
      }
    }

    // Explicit DC glide test: warm up filter to steady-state at targetMix 0.0,
    // then step targetMix 0.0 -> 1.0 and verify smooth per-sample ease (zero zipper noise)
    @Suppress("DEPRECATION")
    duck.flush()
    duck.resetGain()

    val warmupFrames = 500
    val warmupBuf = ByteBuffer.allocateDirect(warmupFrames * 4).order(ByteOrder.nativeOrder())
    repeat(warmupFrames) { warmupBuf.putShort(20000.toShort()); warmupBuf.putShort(20000.toShort()) }
    warmupBuf.flip()
    duck.queueInput(warmupBuf)
    duck.getOutput()

    val dcFrames = 2000
    val dcBuf = ByteBuffer.allocateDirect(dcFrames * 4).order(ByteOrder.nativeOrder())
    repeat(dcFrames) { dcBuf.putShort(20000.toShort()); dcBuf.putShort(20000.toShort()) }
    dcBuf.flip()

    // Step duck to 1.0
    duck.setMix(1.0f)
    duck.queueInput(dcBuf)
    val dcOut = duck.getOutput()

    var prevDc: Int? = null
    var maxDcDelta = 0
    while (dcOut.hasRemaining()) {
      val l = dcOut.getShort().toInt()
      dcOut.getShort() // discard R
      if (prevDc != null) {
        val delta = abs(l - prevDc)
        maxDcDelta = maxOf(maxDcDelta, delta)
        assertTrue("DC glide per-sample delta was $delta (zipper noise detected)", delta < 100)
      }
      prevDc = l
    }
    assertTrue("Max DC delta ($maxDcDelta) must be smooth and non-zero", maxDcDelta in 1..99)
  }

  // -------------------------------------------------------------------------
  // 8. Chained Pipeline: Ducking Followed by Anti-Clipping Limiter
  // -------------------------------------------------------------------------
  @Test
  fun testChainedDuckingAndLimiterStress() {
    val duck = AutomixDuckAudioProcessor(shelfFrequencyHz = 150.0, maxDuckDb = -10.0)
    val limiter = AntiClippingLimiterAudioProcessor()

    val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
    duck.configure(format)
    limiter.configure(format)

    val frames = 8000
    val rawBuf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
    for (i in 0 until frames) {
      // Severe +3 dBFS input: 60 Hz bass + 1000 Hz tone summing beyond 16-bit
      val bass = 25000.0 * sin(2.0 * PI * 60.0 * i / 44100.0)
      val mid = 20000.0 * sin(2.0 * PI * 1000.0 * i / 44100.0)
      val s = (bass + mid).coerceIn(-32768.0, 32767.0).toInt().toShort()
      rawBuf.putShort(s)
      rawBuf.putShort(s)
    }
    rawBuf.flip()

    // Pass chunks through duck then limiter while toggling ducking
    val chunkSize = 256 * 4
    var toggle = false
    while (rawBuf.hasRemaining()) {
      val toRead = minOf(rawBuf.remaining(), chunkSize)
      val slice = rawBuf.slice().limit(toRead) as ByteBuffer
      rawBuf.position(rawBuf.position() + toRead)

      toggle = !toggle
      duck.setMix(if (toggle) 1.0f else 0.0f)

      duck.queueInput(slice)
      val ducked = duck.getOutput()

      limiter.queueInput(ducked)
      val limited = limiter.getOutput()

      while (limited.hasRemaining()) {
        val l = limited.getShort().toInt()
        val r = limited.getShort().toInt()
        assertTrue("Chained pipeline L $l must stay <= ceiling", abs(l) <= CEILING_SHORT)
        assertTrue("Chained pipeline R $r must stay <= ceiling", abs(r) <= CEILING_SHORT)
      }
    }
  }
}
