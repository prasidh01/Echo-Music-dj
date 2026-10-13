package echo.music.dsp.autodj.analysis

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAudioDspEngineTest {

  private val sampleRate = 44100

  @Test
  fun testSynthetic120BpmTrackAnalysis() {
    val audio = SyntheticWaveformFactory.createFourOnTheFloor(
      bpm = 120.0f,
      durationSec = 12.0f,
      sampleRate = sampleRate,
      accentDownbeat = true,
    )
    val result = TrackAudioDspEngine.analyzePcm(
      samples = audio,
      sampleRate = sampleRate,
      durationMs = 12_000L,
    )

    assertEquals(120.0f, result.bpm, 1.0f)
    assertTrue("Confidence should be >= 0.70, was ${result.confidence}", result.confidence >= 0.70f)
    assertEquals(2000.0, result.downbeatIntervalMs.toDouble(), 10.0)
    assertEquals(32, result.phraseBeatCount)
    assertEquals(12_000L, result.durationMs)
  }

  @Test
  fun testPureSilenceReturnsZeroConfidenceAndBpm() {
    val silence = SyntheticWaveformFactory.createSilence(4.0f, sampleRate)
    val result = TrackAudioDspEngine.analyzePcm(
      samples = silence,
      sampleRate = sampleRate,
      durationMs = 4_000L,
    )

    assertEquals(0.0f, result.bpm, 0.001f)
    assertEquals(0.0f, result.confidence, 0.001f)
    assertEquals(0L, result.firstBeatOffsetMs)
  }

  @Test
  fun testNoiseRejectionLowConfidence() {
    val noise = SyntheticWaveformFactory.createWhiteNoise(8.0f, sampleRate, amplitude = 0.5f)
    val result = TrackAudioDspEngine.analyzePcm(
      samples = noise,
      sampleRate = sampleRate,
      durationMs = 8_000L,
    )

    assertTrue("Noise should yield confidence < 0.40, was ${result.confidence}", result.confidence < 0.40f)
  }
}
