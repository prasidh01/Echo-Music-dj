package echo.music.iad1tya.playback.autodj

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TrackAnalyzerStressTest {

  private lateinit var analyzer: TrackAnalyzer
  private val sampleRate = 44100

  @Before
  fun setUp() {
    analyzer = DefaultTrackAnalyzer()
  }

  @Test
  fun testSilenceAcrossDurationsProducesZeroConfidenceWithoutCrashing() {
    val durations = listOf(0.01f, 0.1f, 0.5f, 1.0f, 5.0f, 15.0f)
    for (dur in durations) {
      val silence = SyntheticWaveformFactory.createSilence(dur, sampleRate)
      val profile = analyzer.analyze("silence_$dur", silence, sampleRate)
      assertNotNull(profile)
      assertEquals("BPM should be 0 for silence", 0f, profile.bpm, 0.001f)
      assertEquals("Confidence should be 0 for silence", 0f, profile.confidence, 0.001f)
      assertFalse(profile.bpm.isNaN())
      assertFalse(profile.confidence.isNaN())
      assertFalse(profile.hasValidAnalysis)
    }
  }

  @Test
  fun testNoiseRejectionWithZeroDivisionGuard() {
    val noiseAmplitudes = listOf(0.001f, 0.1f, 0.5f, 1.0f)
    for (amp in noiseAmplitudes) {
      val noise = SyntheticWaveformFactory.createWhiteNoise(8.0f, sampleRate, amplitude = amp)
      val profile = analyzer.analyze("noise_$amp", noise, sampleRate)
      assertNotNull(profile)
      assertTrue("Confidence should be low (< 0.40) for noise, was ${profile.confidence}", profile.confidence < 0.40f)
      assertFalse("BPM must not be NaN", profile.bpm.isNaN())
      assertFalse("Confidence must not be NaN", profile.confidence.isNaN())
    }
  }

  @Test
  fun testMicroDurationAudioHandling() {
    val microSizes = listOf(0, 10, 100, 500, 1024, 2048, 4000, 4096, 10000, 22050, 44000)
    for (size in microSizes) {
      val samples = FloatArray(size) { (0.5f * sin(2.0 * PI * 200.0 * it / sampleRate)).toFloat() }
      val profile = analyzer.analyze("micro_$size", samples, sampleRate)
      assertNotNull("Profile should not be null", profile)
      assertFalse(profile.bpm.isNaN())
      assertFalse(profile.confidence.isNaN())
      assertTrue(profile.confidence in 0f..1f)
    }
  }

  @Test
  fun testExtremeTempos40And300Bpm() {
    // 40 BPM
    val audio40 = SyntheticWaveformFactory.createFourOnTheFloor(40.0f, 16.0f, sampleRate, true)
    val profile40 = analyzer.analyze("tempo_40", audio40, sampleRate)
    assertFalse(profile40.bpm.isNaN())
    assertFalse(profile40.confidence.isNaN())
    if (profile40.confidence >= 0.40f) {
      assertTrue("40 BPM should fold into [70, 140], was ${profile40.bpm}", profile40.bpm in 70f..140f)
    }

    // 300 BPM
    val audio300 = SyntheticWaveformFactory.createPulseTrain(300.0f, 10.0f, sampleRate)
    val profile300 = analyzer.analyze("tempo_300", audio300, sampleRate)
    assertFalse(profile300.bpm.isNaN())
    assertFalse(profile300.confidence.isNaN())
    if (profile300.confidence >= 0.40f) {
      assertTrue("300 BPM should fold into [70, 140], was ${profile300.bpm}", profile300.bpm in 70f..140f)
    }
  }

  @Test
  fun testPolyrhythmicPulses() {
    val w1 = SyntheticWaveformFactory.createPulseTrain(120.0f, 12.0f, sampleRate, pulseFreqHz = 90.0f)
    val w2 = SyntheticWaveformFactory.createPulseTrain(80.0f, 12.0f, sampleRate, pulseFreqHz = 250.0f)
    val poly = FloatArray(w1.size) { (w1[it] + w2[it]) * 0.5f }

    val profile = analyzer.analyze("poly_120_80", poly, sampleRate)
    assertNotNull(profile)
    assertFalse(profile.bpm.isNaN())
    assertFalse(profile.confidence.isNaN())
    assertTrue(profile.downbeatIntervalMs > 0)
    assertTrue(profile.firstBeatOffsetMs >= 0)
  }

  @Test
  fun testCancellationSignalReturnsNullImmediately() {
    val audio = SyntheticWaveformFactory.createFourOnTheFloor(120.0f, 10.0f, sampleRate, true)
    val profile = analyzer.analyzePcm(
      songId = "canceled",
      samples = audio,
      sampleRate = sampleRate,
      shouldCancel = { true }
    )
    assertNull("Analysis with active cancel signal should immediately return null", profile)
  }

  @Test
  fun testDownbeatGridIntegrity() {
    val audio = SyntheticWaveformFactory.createFourOnTheFloor(120.0f, 10.0f, sampleRate, true)
    val profile = analyzer.analyze("grid_test", audio, sampleRate)

    assertEquals(2000.0, profile.downbeatIntervalMs.toDouble(), 10.0)
    assertEquals(32, profile.phraseBeatCount)
    assertTrue("firstBeatOffsetMs must be non-negative", profile.firstBeatOffsetMs >= 0L)
    assertTrue(
      "firstBeatOffsetMs must be < downbeatIntervalMs",
      profile.firstBeatOffsetMs < profile.downbeatIntervalMs
    )
  }
}
