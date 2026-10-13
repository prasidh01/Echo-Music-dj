package echo.music.dsp.autodj.analysis

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChromaKeyDetectorTest {

  private val sampleRate = 44100

  @Test
  fun testDetectsAMinor8A() {
    // A minor chord: A3 (57), A4 (69), C5 (72), E5 (76), A5 (81)
    val chord = SyntheticWaveformFactory.createPolyphonicChord(listOf(57, 69, 72, 76, 81), 3.0f, sampleRate)
    val result = ChromaKeyDetector.estimateKey(chord, sampleRate)

    assertNotNull(result)
    assertEquals(9, result!!.pitchClass)
    assertEquals(true, result.isMinor)
    assertEquals("8A", result.camelotKey)
  }

  @Test
  fun testDetectsCMajor8B() {
    // C major chord: C3 (48), C4 (60), E4 (64), G4 (67), C5 (72)
    val chord = SyntheticWaveformFactory.createPolyphonicChord(listOf(48, 60, 64, 67, 72), 3.0f, sampleRate)
    val result = ChromaKeyDetector.estimateKey(chord, sampleRate)

    assertNotNull(result)
    assertEquals(0, result!!.pitchClass)
    assertEquals(false, result.isMinor)
    assertEquals("8B", result.camelotKey)
  }

  @Test
  fun testDetectsGMajor9B() {
    // G major chord: G3 (55), G4 (67), B4 (71), D5 (74), G5 (79)
    val chord = SyntheticWaveformFactory.createPolyphonicChord(listOf(55, 67, 71, 74, 79), 3.0f, sampleRate)
    val result = ChromaKeyDetector.estimateKey(chord, sampleRate)

    assertNotNull(result)
    assertEquals(7, result!!.pitchClass)
    assertEquals(false, result.isMinor)
    assertEquals("9B", result.camelotKey)
  }

  @Test
  fun testDetectsEMinor9A() {
    // E minor chord: E3 (52), E4 (64), G4 (67), B4 (71), E5 (76)
    val chord = SyntheticWaveformFactory.createPolyphonicChord(listOf(52, 64, 67, 71, 76), 3.0f, sampleRate)
    val result = ChromaKeyDetector.estimateKey(chord, sampleRate)

    assertNotNull(result)
    assertEquals(4, result!!.pitchClass)
    assertEquals(true, result.isMinor)
    assertEquals("9A", result.camelotKey)
  }

  @Test
  fun testSilenceReturnsNull() {
    val silence = SyntheticWaveformFactory.createSilence(2.0f, sampleRate)
    val result = ChromaKeyDetector.estimateKey(silence, sampleRate)
    assertNull(result)
  }
}
