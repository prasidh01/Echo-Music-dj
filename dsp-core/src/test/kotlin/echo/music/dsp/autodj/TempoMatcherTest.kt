package echo.music.dsp.autodj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoMatcherTest {

  @Test
  fun testDirectMatchingWithinTolerance() {
    // T1-F03-01: Outgoing 124 BPM, Incoming 128 BPM -> Ratio 1.0322, k=0, accepted
    val result = TempoMatcher.calculateRatio(124.0f, 128.0f)
    assertEquals(128.0f / 124.0f, result.ratio, 0.001f)
    assertEquals(0, result.foldFactor)
    assertTrue(result.isCompatible)
  }

  @Test
  fun testHalfTimeOctaveFolding() {
    // T1-F03-02: Outgoing 140 BPM, Incoming 70 BPM -> Ratio 1.0, k=+1 (double incoming)
    val result = TempoMatcher.calculateRatio(140.0f, 70.0f)
    assertEquals(1.0f, result.ratio, 0.001f)
    assertEquals(1, result.foldFactor)
    assertTrue(result.isCompatible)
  }

  @Test
  fun testDoubleTimeOctaveFolding() {
    // T1-F03-03: Outgoing 85 BPM, Incoming 170 BPM -> Ratio 1.0, k=-1 (halve incoming)
    val result = TempoMatcher.calculateRatio(85.0f, 170.0f)
    assertEquals(1.0f, result.ratio, 0.001f)
    assertEquals(-1, result.foldFactor)
    assertTrue(result.isCompatible)
  }

  @Test
  fun testBoundaryTempoDeltaAcceptance() {
    // T1-F03-04: Outgoing 120 BPM, Incoming 129.6 BPM -> Delta 8%, accepted
    val result = TempoMatcher.calculateRatio(120.0f, 129.6f)
    assertEquals(1.080f, result.ratio, 0.001f)
    assertEquals(0, result.foldFactor)
    assertTrue(result.isCompatible)
  }

  @Test
  fun testIncompatibleTempoRejection() {
    // T1-F03-05: Outgoing 100 BPM, Incoming 115 BPM -> Delta 15% > 8%, rejected
    val result = TempoMatcher.calculateRatio(100.0f, 115.0f)
    assertFalse(result.isCompatible)
    assertTrue(result.delta > 0.08f)
  }
}
