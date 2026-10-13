package echo.music.dsp.autodj.analysis

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class AnalysisEngineStressTest {

  private val sampleRate = 44100

  // -------------------------------------------------------------
  // 1. Pure Silence & Near Silence Stress Tests
  // -------------------------------------------------------------

  @Test
  fun testEmptySampleBuffer() {
    val result = TrackAudioDspEngine.analyzePcm(FloatArray(0), sampleRate)
    assertEquals(0f, result.bpm, 0.001f)
    assertEquals(0f, result.confidence, 0.001f)
    assertFalse(result.bpm.isNaN())
    assertFalse(result.confidence.isNaN())
    assertEquals(0L, result.durationMs)
  }

  @Test
  fun testPureSilenceVariableDurations() {
    val durations = listOf(0.1f, 0.5f, 1.0f, 3.0f, 10.0f, 30.0f)
    for (dur in durations) {
      val silence = SyntheticWaveformFactory.createSilence(dur, sampleRate)
      val result = TrackAudioDspEngine.analyzePcm(silence, sampleRate)
      assertEquals("BPM must be 0 for silence at ${dur}s", 0f, result.bpm, 0.001f)
      assertEquals("Confidence must be 0 for silence at ${dur}s", 0f, result.confidence, 0.001f)
      assertFalse(result.bpm.isNaN())
      assertFalse(result.confidence.isNaN())
      assertFalse(result.bpm.isInfinite())
      assertFalse(result.confidence.isInfinite())
    }
  }

  @Test
  fun testConstantDcOffsetAudio() {
    // Non-zero constant signal (DC offset without periodic onsets)
    val dcSamples = FloatArray(sampleRate * 4) { 0.4f }
    val result = TrackAudioDspEngine.analyzePcm(dcSamples, sampleRate)
    assertTrue("Constant DC signal must have confidence < 0.20, was ${result.confidence}", result.confidence < 0.20f)
    assertFalse(result.bpm.isNaN())
    assertFalse(result.confidence.isNaN())
  }

  @Test
  fun testNearSilenceSubThresholdNoise() {
    // Energy below 1e-4f threshold
    val tinyNoise = SyntheticWaveformFactory.createWhiteNoise(4.0f, sampleRate, amplitude = 0.00005f)
    val result = TrackAudioDspEngine.analyzePcm(tinyNoise, sampleRate)
    assertEquals(0f, result.bpm, 0.001f)
    assertEquals(0f, result.confidence, 0.001f)
  }

  // -------------------------------------------------------------
  // 2. Pure White Noise Stress Tests
  // -------------------------------------------------------------

  @Test
  fun testWhiteNoiseRejectionAcrossAmplitudesAndSeeds() {
    val amplitudes = listOf(0.05f, 0.2f, 0.5f, 0.9f)
    val seeds = listOf(11L, 42L, 999L, 123456L)

    for (amp in amplitudes) {
      for (seed in seeds) {
        val noise = SyntheticWaveformFactory.createWhiteNoise(8.0f, sampleRate, amplitude = amp, seed = seed)
        val result = TrackAudioDspEngine.analyzePcm(noise, sampleRate)
        assertTrue(
          "White noise (amp=$amp, seed=$seed) confidence must be < 0.40, was ${result.confidence}",
          result.confidence < 0.40f
        )
        assertFalse("BPM must not be NaN", result.bpm.isNaN())
        assertFalse("Confidence must not be NaN", result.confidence.isNaN())
        assertFalse("BPM must not be Infinite", result.bpm.isInfinite())
        assertFalse("Confidence must not be Infinite", result.confidence.isInfinite())
      }
    }
  }

  // -------------------------------------------------------------
  // 3. Micro-Duration Audio (< 1s) Stress Tests
  // -------------------------------------------------------------

  @Test
  fun testMicroDurationAudioHandling() {
    // Test various small buffer sizes below and around 1 second
    val sampleCounts = listOf(1, 10, 64, 512, 1024, 2048, 4095, 4096, 10000, 22050, 44099)
    for (n in sampleCounts) {
      val pulse = FloatArray(n) { sin(2.0 * PI * 440.0 * it / sampleRate).toFloat() }
      val result = TrackAudioDspEngine.analyzePcm(pulse, sampleRate)
      assertNotNull("Result must not be null for n=$n", result)
      assertFalse("BPM must not be NaN for n=$n", result.bpm.isNaN())
      assertFalse("Confidence must not be NaN for n=$n", result.confidence.isNaN())
      assertTrue("Confidence must be in [0, 1] for n=$n, was ${result.confidence}", result.confidence in 0f..1f)
      if (n < 4096) {
        assertEquals("BPM must be 0 for sub-FFT audio n=$n", 0f, result.bpm, 0.001f)
        assertEquals("Confidence must be 0 for sub-FFT audio n=$n", 0f, result.confidence, 0.001f)
      }
    }
  }

  // -------------------------------------------------------------
  // 4. Extreme Tempos: 40 BPM & 300 BPM
  // -------------------------------------------------------------

  @Test
  fun testExtremeLowTempo40Bpm() {
    // 40 BPM pulses: interval is 1.5s
    val audio40 = SyntheticWaveformFactory.createFourOnTheFloor(
      bpm = 40.0f,
      durationSec = 16.0f,
      sampleRate = sampleRate,
      accentDownbeat = true,
    )
    val result = TrackAudioDspEngine.analyzePcm(audio40, sampleRate)
    assertFalse("BPM must not be NaN", result.bpm.isNaN())
    assertFalse("Confidence must not be NaN", result.confidence.isNaN())
    assertTrue("Downbeat interval must be > 0, was ${result.downbeatIntervalMs}", result.downbeatIntervalMs > 0)
    // 40 BPM should either fold into [70, 140] (e.g. 80 BPM) or report low confidence
    if (result.confidence >= 0.40f) {
      assertTrue("Folded BPM must be in canonical range [70, 140], was ${result.bpm}", result.bpm in 70f..140f)
      // If detected as 80 BPM (2x 40 BPM), downbeat interval matches
      val expectedFoldedBpm = 80f
      assertEquals(expectedFoldedBpm, result.bpm, 3.0f)
    }
  }

  @Test
  fun testExtremeHighTempo300Bpm() {
    // 300 BPM pulses: interval is 200ms
    val audio300 = SyntheticWaveformFactory.createPulseTrain(
      bpm = 300.0f,
      durationSec = 10.0f,
      sampleRate = sampleRate,
      pulseFreqHz = 150.0f,
    )
    val result = TrackAudioDspEngine.analyzePcm(audio300, sampleRate)
    assertFalse("BPM must not be NaN", result.bpm.isNaN())
    assertFalse("Confidence must not be NaN", result.confidence.isNaN())
    assertTrue("Downbeat interval must be > 0, was ${result.downbeatIntervalMs}", result.downbeatIntervalMs > 0)
    // 300 BPM / 4 = 75 BPM, or 300 / 2 = 150 -> 75 BPM. Must fold into [70, 140]
    if (result.confidence >= 0.40f) {
      assertTrue("Folded BPM must be in canonical range [70, 140], was ${result.bpm}", result.bpm in 70f..140f)
      assertEquals(75.0f, result.bpm, 2.0f)
    }
  }

  @Test
  fun testBoundaryTempos() {
    val tempos = listOf(35f, 60f, 90f, 120f, 140f, 180f, 240f, 320f)
    for (tempo in tempos) {
      val audio = SyntheticWaveformFactory.createPulseTrain(
        bpm = tempo,
        durationSec = 12.0f,
        sampleRate = sampleRate,
      )
      val result = TrackAudioDspEngine.analyzePcm(audio, sampleRate)
      assertFalse("BPM must not be NaN for tempo=$tempo", result.bpm.isNaN())
      assertFalse("Confidence must not be NaN for tempo=$tempo", result.confidence.isNaN())
      if (result.bpm > 0f) {
        assertTrue("Detected BPM must be canonical [70, 140], was ${result.bpm} for input $tempo", result.bpm in 70f..140f)
      }
    }
  }

  // -------------------------------------------------------------
  // 5. Polyrhythmic Pulses Stress Tests
  // -------------------------------------------------------------

  @Test
  fun testPolyrhythm3Against2() {
    // 3 against 2: 120 BPM base vs 80 BPM counter-pulse
    val wave1 = SyntheticWaveformFactory.createPulseTrain(120.0f, 12.0f, sampleRate, pulseFreqHz = 100.0f)
    val wave2 = SyntheticWaveformFactory.createPulseTrain(80.0f, 12.0f, sampleRate, pulseFreqHz = 220.0f)
    val poly = FloatArray(wave1.size) { (wave1[it] + wave2[it]) * 0.5f }

    val result = TrackAudioDspEngine.analyzePcm(poly, sampleRate)
    assertFalse("BPM must not be NaN", result.bpm.isNaN())
    assertFalse("Confidence must not be NaN", result.confidence.isNaN())
    assertTrue("BPM must be positive or 0, was ${result.bpm}", result.bpm >= 0f)
    assertTrue("Downbeat interval must be > 0", result.downbeatIntervalMs > 0)
    assertTrue("First beat offset must be valid", result.firstBeatOffsetMs in 0 until result.downbeatIntervalMs)
  }

  @Test
  fun testPolyrhythm4Against3() {
    // 4 against 3: 120 BPM base vs 90 BPM counter-pulse
    val wave1 = SyntheticWaveformFactory.createPulseTrain(120.0f, 12.0f, sampleRate, pulseFreqHz = 80.0f)
    val wave2 = SyntheticWaveformFactory.createPulseTrain(90.0f, 12.0f, sampleRate, pulseFreqHz = 300.0f)
    val poly = FloatArray(wave1.size) { (wave1[it] + wave2[it]) * 0.5f }

    val result = TrackAudioDspEngine.analyzePcm(poly, sampleRate)
    assertFalse("BPM must not be NaN", result.bpm.isNaN())
    assertFalse("Confidence must not be NaN", result.confidence.isNaN())
    assertTrue("First beat offset must be within [0, downbeatIntervalMs)", result.firstBeatOffsetMs in 0 until result.downbeatIntervalMs)
  }

  @Test
  fun testAsymmetricMetricPattern7Over8() {
    // 7/8 rhythm: 3+2+2 grouping of eighth notes at 280 eighth-notes/min (40 measures/min)
    val eighthIntervalSec = 60.0 / 280.0
    val durationSec = 12.0f
    val totalSamples = (durationSec * sampleRate).toInt()
    val samples = FloatArray(totalSamples)
    val pulseSamples = (0.02f * sampleRate).toInt()

    var t = 0.0
    var eighthIndex = 0
    while (t < durationSec) {
      val barPos = eighthIndex % 7
      // Accents on 0, 3, 5 (3 + 2 + 2 grouping)
      val isAccent = (barPos == 0 || barPos == 3 || barPos == 5)
      val amp = if (barPos == 0) 1.0f else if (isAccent) 0.7f else 0.3f
      val startSample = (t * sampleRate).toInt()
      for (i in 0 until pulseSamples) {
        val sIdx = startSample + i
        if (sIdx in 0 until totalSamples) {
          val window = 0.5 - 0.5 * cos(2.0 * PI * i / pulseSamples)
          samples[sIdx] = (amp * window * sin(2.0 * PI * 100.0 * i / sampleRate)).toFloat()
        }
      }
      eighthIndex++
      t += eighthIntervalSec
    }

    val result = TrackAudioDspEngine.analyzePcm(samples, sampleRate)
    assertFalse("BPM must not be NaN", result.bpm.isNaN())
    assertFalse("Confidence must not be NaN", result.confidence.isNaN())
    assertTrue("Downbeat interval must be > 0", result.downbeatIntervalMs > 0)
    assertTrue("First beat offset within bounds", result.firstBeatOffsetMs in 0 until result.downbeatIntervalMs)
  }

  // -------------------------------------------------------------
  // 6. Beat Grid Alignment & 4-Beat Downbeat Consistency
  // -------------------------------------------------------------

  @Test
  fun test4BeatDownbeatAlignmentConsistency() {
    // Verify that shifting which beat is accented (measure 1, 2, 3, or 4)
    // consistently shifts downbeatBarIndex
    val bpm = 120.0f
    val beatIntervalSec = 60.0 / bpm
    val pulseSamples = (0.025f * sampleRate).toInt()
    val durationSec = 10.0f
    val totalSamples = (durationSec * sampleRate).toInt()

    for (targetBar in 0 until 4) {
      val samples = FloatArray(totalSamples)
      var beatIdx = 0
      var beatTime = 0.0
      while (beatTime < durationSec) {
        val startSample = (beatTime * sampleRate).toInt()
        val isDownbeat = (beatIdx % 4 == targetBar)
        val amp = if (isDownbeat) 1.0f else 0.3f
        val freq = 100.0f

        for (i in 0 until pulseSamples) {
          val sIdx = startSample + i
          if (sIdx in 0 until totalSamples) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / pulseSamples)
            samples[sIdx] = (amp * window * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
          }
        }
        beatIdx++
        beatTime += beatIntervalSec
      }

      val flux = SpectralFluxCalculator.calculateFlux(samples)
      val frameRate = sampleRate.toFloat() / SpectralFluxCalculator.DEFAULT_HOP_SIZE
      val tempo = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)
      assertNotNull("Tempo estimate must not be null", tempo)

      val grid = BeatGridEstimator.estimateGrid(
        flux = flux,
        bpm = tempo!!.bpm,
        periodFrames = tempo.periodFrames,
        frameRate = frameRate,
        phraseBeatCount = 32,
      )

      assertEquals(
        "Estimated downbeat bar must match accented targetBar $targetBar",
        targetBar,
        grid.downbeatBarIndex
      )
      assertEquals(2000.0, grid.downbeatIntervalMs.toDouble(), 10.0)
      assertEquals(32, grid.phraseBeatCount)
      assertTrue(
        "firstBeatOffsetMs must be within [0, downbeatIntervalMs)",
        grid.firstBeatOffsetMs in 0 until grid.downbeatIntervalMs
      )
    }
  }

  @Test
  fun testFirstBeatOffsetTrackingUnderTimeShift() {
    // Introduce fixed time shifts (0ms, 125ms, 250ms, 375ms) and verify offset tracking
    val bpm = 120.0f
    val shiftsSec = listOf(0.0f, 0.125f, 0.250f, 0.375f)

    for (shift in shiftsSec) {
      val audio = SyntheticWaveformFactory.createFourOnTheFloor(
        bpm = bpm,
        durationSec = 10.0f,
        sampleRate = sampleRate,
        accentDownbeat = true,
        firstBeatOffsetSec = shift,
      )
      val result = TrackAudioDspEngine.analyzePcm(audio, sampleRate)
      assertEquals(120.0f, result.bpm, 1.0f)
      assertTrue("firstBeatOffsetMs must be within [0, downbeatIntervalMs)", result.firstBeatOffsetMs in 0 until result.downbeatIntervalMs)

      val expectedOffsetMs = (shift * 1000f).toLong()
      val beatIntervalMs = (60_000.0 / result.bpm).toLong()
      // Verify beat grid phase alignment (modulo beat interval of ~500ms)
      val detectedBeatPhase = (result.firstBeatOffsetMs % beatIntervalMs)
      val expectedBeatPhase = (expectedOffsetMs % beatIntervalMs)
      val phaseDiff = kotlin.math.abs(detectedBeatPhase - expectedBeatPhase)
      assertTrue(
        "Beat grid phase $detectedBeatPhase ms must match expected $expectedBeatPhase ms within 30ms (diff $phaseDiff)",
        phaseDiff <= 30L || kotlin.math.abs(phaseDiff - beatIntervalMs) <= 30L
      )
    }
  }

  // -------------------------------------------------------------
  // 7. Numerical Edge Cases & Robustness
  // -------------------------------------------------------------

  @Test
  fun testExtremeValuesAndClipping() {
    // Audio with massive digital clipping (+10.0f, -10.0f)
    val clipped = FloatArray(sampleRate * 6) {
      if (it % 22050 < 2000) 10.0f else -10.0f
    }
    val result = TrackAudioDspEngine.analyzePcm(clipped, sampleRate)
    assertFalse("BPM must not be NaN under clipping", result.bpm.isNaN())
    assertFalse("Confidence must not be NaN under clipping", result.confidence.isNaN())
  }

  @Test
  fun testIllegalSampleRatesGracefullyHandled() {
    val audio = SyntheticWaveformFactory.createSilence(2.0f, sampleRate)
    val res0 = TrackAudioDspEngine.analyzePcm(audio, 0)
    assertEquals(0f, res0.bpm, 0.001f)
    assertEquals(0f, res0.confidence, 0.001f)

    val resNeg = TrackAudioDspEngine.analyzePcm(audio, -44100)
    assertEquals(0f, resNeg.bpm, 0.001f)
    assertEquals(0f, resNeg.confidence, 0.001f)
  }
}
