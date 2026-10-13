package echo.music.domain.autodj.validator

import echo.music.domain.autodj.model.DjPreset
import echo.music.domain.autodj.model.FallbackReason
import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionPlanResult
import echo.music.domain.autodj.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransitionPlanValidatorTest {

  private lateinit var validator: TransitionPlanValidator

  @Before
  fun setup() {
    validator = TransitionPlanValidator()
  }

  @Test
  fun testGate1MissingProfile() {
    val plan = TransitionPlan(
      outgoingSongId = "out",
      incomingSongId = "in",
      triggerTimeMs = 150_000L,
      incomingStartMs = 0L,
      overlapDurationMs = 8000L,
      generationId = 42L,
    )

    // Null outgoing profile
    val resultNullOut = validator.validate(current = null, incoming = TrackProfile("in", 120f, 0.9f, 0L), plan = plan)
    assertTrue(resultNullOut is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resultNullOut as TransitionPlanResult.Fallback).reason)
    assertEquals(42L, resultNullOut.plan.generationId)

    // Null incoming profile
    val resultNullIn = validator.validate(current = TrackProfile("out", 120f, 0.9f, 0L), incoming = null, plan = plan)
    assertTrue(resultNullIn is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resultNullIn as TransitionPlanResult.Fallback).reason)

    // Invalid analysis (bpm <= 0)
    val invalidProfile = TrackProfile("out", 0f, 0.9f, 0L)
    val validProfile = TrackProfile("in", 120f, 0.9f, 0L)
    val resultInvalidBpm = validator.validate(current = invalidProfile, incoming = validProfile, plan = plan)
    assertTrue(resultInvalidBpm is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resultInvalidBpm as TransitionPlanResult.Fallback).reason)
  }

  @Test
  fun testGate2LowConfidence() {
    val plan = TransitionPlan(
      outgoingSongId = "out",
      incomingSongId = "in",
      triggerTimeMs = 150_000L,
      incomingStartMs = 0L,
      overlapDurationMs = 8000L,
      generationId = 101L,
    )

    // Confidence 0.25 < SEAMLESS threshold (0.35)
    val lowConfOut = TrackProfile("out", 120f, 0.25f, 0L, durationMs = 200_000L)
    val highConfIn = TrackProfile("in", 120f, 0.90f, 0L, durationMs = 200_000L)

    val result = validator.validate(current = lowConfOut, incoming = highConfIn, plan = plan, preset = DjPreset.SEAMLESS)
    assertTrue(result is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.LOW_CONFIDENCE, (result as TransitionPlanResult.Fallback).reason)
    assertEquals(101L, result.plan.generationId)
  }

  @Test
  fun testGate3TempoDeltaOutOfBounds() {
    val plan = TransitionPlan(
      outgoingSongId = "out",
      incomingSongId = "in",
      triggerTimeMs = 150_000L,
      incomingStartMs = 0L,
      overlapDurationMs = 8000L,
      generationId = 77L,
    )

    // 128 BPM to 95 BPM: ratio = 128/95 = 1.347 -> outside [0.92, 1.08]
    val out128 = TrackProfile("out", 128f, 0.90f, 0L, durationMs = 200_000L)
    val in95 = TrackProfile("in", 95f, 0.90f, 0L, durationMs = 200_000L)

    val result = validator.validate(current = out128, incoming = in95, plan = plan)
    assertTrue(result is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.TEMPO_OUT_OF_BOUNDS, (result as TransitionPlanResult.Fallback).reason)
    assertEquals(77L, result.plan.generationId)
  }

  @Test
  fun testGate4InsufficientHeadroom() {
    // Track duration 180_000ms with trigger at 178_500ms (1500ms room < 3000ms)
    val outProfile = TrackProfile("out", 120f, 0.90f, 0L, durationMs = 180_000L)
    val inProfile = TrackProfile("in", 120f, 0.90f, 0L, durationMs = 200_000L)

    val plan = TransitionPlan(
      outgoingSongId = "out",
      incomingSongId = "in",
      triggerTimeMs = 178_500L,
      incomingStartMs = 0L,
      overlapDurationMs = 8000L,
      generationId = 999L,
    )

    val result = validator.validate(current = outProfile, incoming = inProfile, plan = plan)
    assertTrue(result is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.INSUFFICIENT_HEADROOM, (result as TransitionPlanResult.Fallback).reason)
    assertEquals(999L, result.plan.generationId)
  }

  @Test
  fun testAllGatesPassValidPlan() {
    val outProfile = TrackProfile("out", 120f, 0.90f, 0L, durationMs = 200_000L)
    val inProfile = TrackProfile("in", 122f, 0.85f, 0L, durationMs = 200_000L)

    val plan = TransitionPlan(
      outgoingSongId = "out",
      incomingSongId = "in",
      transitionType = TransitionType.PHRASE_ALIGNED_BLEND,
      triggerTimeMs = 180_000L,
      incomingStartMs = 0L,
      overlapDurationMs = 8000L,
      tempoRatio = 120f / 122f,
      confidence = 0.875f,
      generationId = 55L,
    )

    val result = validator.validate(current = outProfile, incoming = inProfile, plan = plan)
    assertTrue("Plan should be Valid", result is TransitionPlanResult.Valid)
    assertEquals(55L, result.plan.generationId)
    assertEquals(TransitionType.PHRASE_ALIGNED_BLEND, result.plan.transitionType)
  }
}
