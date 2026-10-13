package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.FallbackReason
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDjTelemetryFormatterTest {

  @Test
  fun testCamelotKeyOverlayStringFormatting() {
    // T1-F13-03: Outgoing: "8A", Incoming: "9A", delta: +1 -> "8A -> 9A (+1 step)"
    val result = AutoDjTelemetryFormatter.format("8A", "9A", 1)
    assertEquals("8A -> 9A (+1 step)", result)
  }

  @Test
  fun testCamelotKeyNegativeAndZeroSteps() {
    val resultDown = AutoDjTelemetryFormatter.format("9A", "8A", -1)
    assertEquals("9A -> 8A (-1 step)", resultDown)

    val resultMulti = AutoDjTelemetryFormatter.format("8A", "10A", 2)
    assertEquals("8A -> 10A (+2 steps)", resultMulti)

    val resultZero = AutoDjTelemetryFormatter.format("8A", "8A", 0)
    assertEquals("8A -> 8A (+0 steps)", resultZero)
  }

  @Test
  fun testNullKeyFallback() {
    // T2-F13-03: Null profile fields in UI
    val resultNull = AutoDjTelemetryFormatter.format(null, "9A", 1)
    assertEquals("BPM: -- | Key: --", resultNull)

    val resultBothNull = AutoDjTelemetryFormatter.format(null, null, 0)
    assertEquals("BPM: -- | Key: --", resultBothNull)
  }

  @Test
  fun testRealtimeCrossfadeProgressDisplay() {
    // T1-F13-04: Crossfade progress p = 0.65f -> Displays "Mixing: 65%"
    val result65 = AutoDjTelemetryFormatter.format(0.65f)
    assertEquals("Mixing: 65%", result65)

    val result0 = AutoDjTelemetryFormatter.format(0.0f)
    assertEquals("Mixing: 0%", result0)

    val result100 = AutoDjTelemetryFormatter.format(1.0f)
    assertEquals("Mixing: 100%", result100)
  }

  @Test
  fun testFallbackReasonTelemetryIndication() {
    // T1-F13-05: Fallback triggered with LOW_CONFIDENCE and 0.32 confidence -> "Fallback: Low Confidence (0.32)"
    val result = AutoDjTelemetryFormatter.format(FallbackReason.LOW_CONFIDENCE, 0.32f)
    assertEquals("Fallback: Low Confidence (0.32)", result)

    val resultMissing = AutoDjTelemetryFormatter.format(FallbackReason.MISSING_PROFILE, 0.0f)
    assertEquals("Fallback: Missing Profile (0.00)", resultMissing)

    val resultTempo = AutoDjTelemetryFormatter.format(FallbackReason.TEMPO_OUT_OF_BOUNDS, 0.90f)
    assertEquals("Fallback: Tempo Out Of Bounds (0.90)", resultTempo)
  }
}
