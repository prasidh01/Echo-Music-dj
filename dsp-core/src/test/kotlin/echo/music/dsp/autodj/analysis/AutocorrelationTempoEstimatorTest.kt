package echo.music.dsp.autodj.analysis

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocorrelationTempoEstimatorTest {

  private val sampleRate = 44100
  private val frameRate = sampleRate.toFloat() / 512

  @Test
  fun testEstimateTempo120Bpm() {
    val audio = SyntheticWaveformFactory.createPulseTrain(120.0f, 10.0f, sampleRate)
    val flux = SpectralFluxCalculator.calculateFlux(audio)
    val result = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)

    assertNotNull(result)
    assertEquals(120.0f, result!!.bpm, 1.0f)
    assertTrue("Confidence should be high, was ${result.confidence}", result.confidence >= 0.70f)
  }

  @Test
  fun testEstimateTempo128Bpm() {
    val audio = SyntheticWaveformFactory.createPulseTrain(128.0f, 10.0f, sampleRate)
    val flux = SpectralFluxCalculator.calculateFlux(audio)
    val result = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)

    assertNotNull(result)
    assertEquals(128.0f, result!!.bpm, 1.0f)
    assertTrue("Confidence should be high, was ${result.confidence}", result.confidence >= 0.70f)
  }

  @Test
  fun testOctaveFoldingLowBpm() {
    // 60 BPM folds into [70, 140] by doubling to 120 BPM
    val audio = SyntheticWaveformFactory.createPulseTrain(60.0f, 12.0f, sampleRate)
    val flux = SpectralFluxCalculator.calculateFlux(audio)
    val result = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)

    assertNotNull(result)
    assertEquals(120.0f, result!!.bpm, 1.5f)
  }

  @Test
  fun testOctaveFoldingHighBpm() {
    // 170 BPM folds into [70, 140] by halving to 85 BPM
    val audio = SyntheticWaveformFactory.createPulseTrain(170.0f, 10.0f, sampleRate)
    val flux = SpectralFluxCalculator.calculateFlux(audio)
    val result = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)

    assertNotNull(result)
    assertEquals(85.0f, result!!.bpm, 1.5f)
  }

  @Test
  fun testJitteredAudioYieldsLowConfidence() {
    val audio = SyntheticWaveformFactory.createJitteredPulseTrain(120.0f, 10.0f, sampleRate, jitterMs = 200.0f)
    val flux = SpectralFluxCalculator.calculateFlux(audio)
    val result = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)

    if (result != null) {
      assertTrue("Jittered audio must yield confidence < 0.50, was ${result.confidence}", result.confidence < 0.50f)
    }
  }

  @Test
  fun testSilenceReturnsNull() {
    val audio = SyntheticWaveformFactory.createSilence(4.0f, sampleRate)
    val flux = SpectralFluxCalculator.calculateFlux(audio)
    val result = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)
    assertTrue(result == null || result.confidence == 0.0f)
  }
}
