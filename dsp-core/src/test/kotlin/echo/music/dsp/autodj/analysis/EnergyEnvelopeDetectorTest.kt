package echo.music.dsp.autodj.analysis

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyEnvelopeDetectorTest {

  private val sampleRate = 44100

  @Test
  fun testOverallEnergySilenceVsLoud() {
    val silence = SyntheticWaveformFactory.createSilence(2.0f, sampleRate)
    val loud = SyntheticWaveformFactory.createFourOnTheFloor(120.0f, 2.0f, sampleRate)

    val energySilence = EnergyEnvelopeDetector.calculateOverallEnergy(silence)
    val energyLoud = EnergyEnvelopeDetector.calculateOverallEnergy(loud)

    assertEquals(0.0f, energySilence, 1e-6f)
    assertTrue("Loud audio should have energy > 0.1, was $energyLoud", energyLoud > 0.1f)
  }

  @Test
  fun testDetectMixInAndMixOut() {
    val (head, tail) = SyntheticWaveformFactory.createDynamicTrack(
      bpm = 120.0f,
      introSec = 4.0f,
      bodySec = 10.0f,
      outroSec = 4.0f,
      sampleRate = sampleRate,
    )

    val headEnv = EnergyEnvelopeDetector.energyEnvelope(head, sampleRate)
    val mixIn = EnergyEnvelopeDetector.detectMixIn(headEnv, firstBeatOffsetMs = 0L, periodMs = 500f)
    assertNotNull(mixIn)
    // Intro was 4s, so mixIn should be around 3500-4500ms
    assertTrue("Mix-in should be >= 2000ms, was $mixIn", mixIn!! >= 2000L)

    val tailEnv = EnergyEnvelopeDetector.energyEnvelope(tail, sampleRate)
    val mixOut = EnergyEnvelopeDetector.detectMixOut(tailEnv, tailActualStartMs = 60_000L, durationMs = 69_000L)
    assertNotNull(mixOut)
    assertTrue("Mix-out should be <= durationMs, was $mixOut", mixOut!! <= 69_000L)
  }
}
