package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.FallbackReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoDjTelemetryFormatterAdversarialTest {

  @Test
  fun testOutOfBoundsProgressClamping() {
    // Negative progress values clamped to 0%
    assertEquals("Mixing: 0%", AutoDjTelemetryFormatter.format(-100.0f))
    assertEquals("Mixing: 0%", AutoDjTelemetryFormatter.format(-0.01f))
    assertEquals("Mixing: 0%", AutoDjTelemetryFormatter.format(Float.NEGATIVE_INFINITY))

    // Exceeded progress values clamped to 100%
    assertEquals("Mixing: 100%", AutoDjTelemetryFormatter.format(1.01f))
    assertEquals("Mixing: 100%", AutoDjTelemetryFormatter.format(50.0f))
    assertEquals("Mixing: 100%", AutoDjTelemetryFormatter.format(Float.POSITIVE_INFINITY))
  }

  @Test
  fun testProgressNaNCauseCrashDefect() {
    // EMPIRICAL DEFECT VERIFICATION:
    // When progress is Float.NaN, (progress * 100f).roundToInt() throws IllegalArgumentException("Cannot round NaN value.")
    // In production, this causes a fatal crash when AutoDjTelemetryFormatter.format(telemetry.progress)
    // is called by Player.kt if progress computation produces NaN.
    try {
      AutoDjTelemetryFormatter.format(Float.NaN)
      fail("Expected IllegalArgumentException when progress is Float.NaN")
    } catch (e: IllegalArgumentException) {
      assertEquals("Cannot round NaN value.", e.message)
    }
  }

  @Test
  fun testExtremeStepDeltas() {
    // Large positive delta
    val maxResult = AutoDjTelemetryFormatter.format("8A", "9A", Int.MAX_VALUE)
    assertEquals("8A -> 9A (+2147483647 steps)", maxResult)

    // Large negative delta (including integer overflow boundary Int.MIN_VALUE)
    val minResult = AutoDjTelemetryFormatter.format("8A", "9A", Int.MIN_VALUE)
    assertEquals("8A -> 9A (-2147483648 steps)", minResult)

    // Singular vs plural steps
    assertEquals("8A -> 9A (+1 step)", AutoDjTelemetryFormatter.format("8A", "9A", 1))
    assertEquals("9A -> 8A (-1 step)", AutoDjTelemetryFormatter.format("9A", "8A", -1))
    assertEquals("8A -> 8A (+0 steps)", AutoDjTelemetryFormatter.format("8A", "8A", 0))
    assertEquals("8A -> 10A (+2 steps)", AutoDjTelemetryFormatter.format("8A", "10A", 2))
    assertEquals("10A -> 8A (-2 steps)", AutoDjTelemetryFormatter.format("10A", "8A", -2))
  }

  @Test
  fun testUnknownAndBlankKeys() {
    // Null, empty, and whitespace keys fallback to placeholder
    assertEquals("BPM: -- | Key: --", AutoDjTelemetryFormatter.format(null, "9A", 1))
    assertEquals("BPM: -- | Key: --", AutoDjTelemetryFormatter.format("8A", null, 1))
    assertEquals("BPM: -- | Key: --", AutoDjTelemetryFormatter.format("", "9A", 1))
    assertEquals("BPM: -- | Key: --", AutoDjTelemetryFormatter.format("8A", "   ", 1))
    assertEquals("BPM: -- | Key: --", AutoDjTelemetryFormatter.format(" \t ", "", 0))

    // Non-Camelot raw text keys are formatted verbatim without throwing exceptions
    val rawResult = AutoDjTelemetryFormatter.format("CustomKeyA", "CustomKeyB", 3)
    assertEquals("CustomKeyA -> CustomKeyB (+3 steps)", rawResult)
  }

  @Test
  fun testFallbackReasonExhaustiveCoverage() {
    for (reason in FallbackReason.entries) {
      val formatted = AutoDjTelemetryFormatter.format(reason, 0.42f)
      assertTrue("Formatted fallback must start with 'Fallback: '", formatted.startsWith("Fallback: "))
      assertTrue("Formatted fallback must contain confidence '(0.42)'", formatted.contains("(0.42)"))

      val shortFormat = AutoDjTelemetryFormatter.format(reason)
      assertTrue("Short format must start with 'Fallback: '", shortFormat.startsWith("Fallback: "))
    }

    // Extreme confidence numbers
    val nanConf = AutoDjTelemetryFormatter.format(FallbackReason.LOW_CONFIDENCE, Float.NaN)
    assertEquals("Fallback: Low Confidence (NaN)", nanConf)

    val negConf = AutoDjTelemetryFormatter.format(FallbackReason.LOW_CONFIDENCE, -0.5f)
    assertEquals("Fallback: Low Confidence (-0.50)", negConf)
  }
}
