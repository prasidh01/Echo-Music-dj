package echo.music.dsp.autodj.analysis

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatGridEstimatorTest {

  private val sampleRate = 44100
  private val frameRate = sampleRate.toFloat() / 512

  @Test
  fun testEstimateGridDownbeatAlignment() {
    val bpm = 120.0f
    val audio = SyntheticWaveformFactory.createFourOnTheFloor(
      bpm = bpm,
      durationSec = 10.0f,
      sampleRate = sampleRate,
      accentDownbeat = true,
      firstBeatOffsetSec = 0.0f,
    )
    val flux = SpectralFluxCalculator.calculateFlux(audio)
    val tempo = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)
    val grid = BeatGridEstimator.estimateGrid(
      flux = flux,
      bpm = tempo!!.bpm,
      periodFrames = tempo.periodFrames,
      frameRate = frameRate,
      phraseBeatCount = 32,
    )

    assertEquals(32, grid.phraseBeatCount)
    // At ~120 BPM, downbeat interval (4 beats) is ~2000ms
    assertEquals(2000.0, grid.downbeatIntervalMs.toDouble(), 10.0)
    // First beat offset should be near 0ms (+/- 50ms due to hop framing resolution)
    assertTrue("First beat offset ${grid.firstBeatOffsetMs} should be near 0 or downbeat interval",
      grid.firstBeatOffsetMs < 50L || grid.firstBeatOffsetMs > (grid.downbeatIntervalMs - 50L))
  }
}
