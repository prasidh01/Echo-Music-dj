package echo.music.dsp.autodj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CamelotHarmonicsTest {

  @Test
  fun testExactKeyMatchDistance() {
    // T1-F04-01: Key A: "8A", Key B: "8A" -> Distance = 0, Harmonic score = 1.00f
    val distance = CamelotHarmonics.getDistance("8A", "8A")
    val result = CamelotHarmonics.calculateHarmonics("8A", "8A")

    assertEquals(0, distance)
    assertEquals(0, result.distance)
    assertEquals(1.00f, result.score, 0.001f)
    assertFalse(result.isEnergyBoost)
  }

  @Test
  fun testRelativeMajorMinorDistance() {
    // T1-F04-02: Key A: "8A", Key B: "8B" -> Distance = 1, Harmonic score = 0.90f
    val distance = CamelotHarmonics.getDistance("8A", "8B")
    val result = CamelotHarmonics.calculateHarmonics("8A", "8B")

    assertEquals(1, distance)
    assertEquals(1, result.distance)
    assertEquals(0.90f, result.score, 0.001f)
  }

  @Test
  fun testAdjacentWheelStepDistance() {
    // T1-F04-03: Key A: "8A", Key B: "9A" -> Distance = 1, Harmonic score = 0.85f
    val distance = CamelotHarmonics.getDistance("8A", "9A")
    val result = CamelotHarmonics.calculateHarmonics("8A", "9A")

    assertEquals(1, distance)
    assertEquals(1, result.distance)
    assertEquals(0.85f, result.score, 0.001f)
  }

  @Test
  fun testEnergyBoostDistance() {
    // T1-F04-04: Key A: "8A", Key B: "10A" -> Distance = 2, Boost flag = true, score = 0.65f
    val distance = CamelotHarmonics.getDistance("8A", "10A")
    val result = CamelotHarmonics.calculateHarmonics("8A", "10A")

    assertEquals(2, distance)
    assertEquals(2, result.distance)
    assertTrue(result.isEnergyBoost)
    assertEquals(0.65f, result.score, 0.001f)
  }

  @Test
  fun testIncompatibleKeyClashDetection() {
    // T1-F04-05: Key A: "8A", Key B: "2A" -> Distance = 6 (tritone clash), score < 0.20f
    val distance = CamelotHarmonics.getDistance("8A", "2A")
    val result = CamelotHarmonics.calculateHarmonics("8A", "2A")

    assertEquals(6, distance)
    assertEquals(6, result.distance)
    assertTrue("Score should be < 0.20f, was ${result.score}", result.score < 0.20f)
  }
}
