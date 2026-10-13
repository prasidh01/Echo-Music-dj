package echo.music.dsp.autodj.analysis

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectralFluxCalculatorTest {

  @Test
  fun testSilenceProducesZeroFlux() {
    val silence = SyntheticWaveformFactory.createSilence(2.0f)
    val flux = SpectralFluxCalculator.calculateFlux(silence)
    assertTrue(flux.isNotEmpty())
    for (f in flux) {
      assertEquals(0.0f, f, 1e-5f)
    }
  }

  @Test
  fun testPeriodicPulsesProduceActiveFluxEnvelope() {
    val pulses = SyntheticWaveformFactory.createPulseTrain(120.0f, 4.0f)
    val flux = SpectralFluxCalculator.calculateFlux(pulses)
    assertTrue(flux.isNotEmpty())

    var maxFlux = 0f
    for (f in flux) {
      if (f > maxFlux) maxFlux = f
    }
    assertTrue("Periodic pulses should produce positive flux spikes", maxFlux > 0.1f)
  }
}
