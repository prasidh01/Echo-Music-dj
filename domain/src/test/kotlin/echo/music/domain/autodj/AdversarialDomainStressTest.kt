package echo.music.domain.autodj

import echo.music.domain.autodj.model.CamelotKey
import echo.music.domain.autodj.model.CamelotMode
import echo.music.domain.autodj.model.DjPreset
import echo.music.domain.autodj.model.FallbackReason
import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionPlanResult
import echo.music.domain.autodj.model.TransitionType
import echo.music.domain.autodj.planner.TransitionPlanner
import echo.music.domain.autodj.validator.TransitionPlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdversarialDomainStressTest {

  private lateinit var planner: TransitionPlanner
  private lateinit var validator: TransitionPlanValidator

  @Before
  fun setUp() {
    planner = TransitionPlanner.create()
    validator = TransitionPlanValidator()
  }

  // =========================================================================
  // 1. TEMPO FOLDING ACROSS EXTREME BPM RANGES (40 to 220 BPM, halving/doubling)
  // =========================================================================

  @Test
  fun testExtremeBpmOctaveFoldingTransitions() {
    // Halving and doubling combinations across extreme ranges
    val doublingPairs = listOf(
      40.0f to 80.0f,
      40.0f to 160.0f,
      45.0f to 90.0f,
      45.0f to 180.0f,
      50.0f to 100.0f,
      50.0f to 200.0f,
      55.0f to 110.0f,
      55.0f to 220.0f,
      70.0f to 140.0f,
      75.0f to 150.0f,
      80.0f to 160.0f,
      85.0f to 170.0f,
      90.0f to 180.0f,
      100.0f to 200.0f,
      110.0f to 220.0f,
    )

    for ((bpm1, bpm2) in doublingPairs) {
      // Forward transition
      val p1 = TrackProfile("s1", bpm1, 0.9f, 0L, durationMs = 240_000L)
      val p2 = TrackProfile("s2", bpm2, 0.9f, 0L, durationMs = 240_000L)
      val planFwd = planner.planTransition(p1, p2)
      assertEquals("Doubling ratio $bpm1 -> $bpm2 should fold to 1.0", 1.0f, planFwd.tempoRatio, 0.001f)
      val valFwd = validator.validate(p1, p2, planFwd)
      assertTrue("Doubled tempo $bpm1 -> $bpm2 must be Valid", valFwd is TransitionPlanResult.Valid)

      // Reverse transition
      val planRev = planner.planTransition(p2, p1)
      assertEquals("Halving ratio $bpm2 -> $bpm1 should fold to 1.0", 1.0f, planRev.tempoRatio, 0.001f)
      val valRev = validator.validate(p2, p1, planRev)
      assertTrue("Halved tempo $bpm2 -> $bpm1 must be Valid", valRev is TransitionPlanResult.Valid)
    }
  }

  @Test
  fun testExtremeBpmBoundaryAndFallbackTransitions() {
    // Extreme unbridgeable tempo disparities (e.g., 40 BPM to 220 BPM)
    val extremeOut = TrackProfile("s40", 40.0f, 0.9f, 0L, durationMs = 240_000L)
    val extremeIn = TrackProfile("s220", 220.0f, 0.9f, 0L, durationMs = 240_000L)

    // 40 / 220 = 0.1818 -> folded to 0.7272 (outside ±8%) -> neutral clamped 1.0 in planner
    val plan40To220 = planner.planTransition(extremeOut, extremeIn)
    assertEquals(1.0f, plan40To220.tempoRatio, 0.001f)
    val result40To220 = validator.validate(extremeOut, extremeIn, plan40To220)
    assertTrue("40 to 220 BPM must trigger Fallback", result40To220 is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.TEMPO_OUT_OF_BOUNDS, (result40To220 as TransitionPlanResult.Fallback).reason)

    // 220 / 40 = 5.5 -> folded to 1.375 (outside ±8%) -> neutral clamped 1.0 in planner
    val plan220To40 = planner.planTransition(extremeIn, extremeOut)
    assertEquals(1.0f, plan220To40.tempoRatio, 0.001f)
    val result220To40 = validator.validate(extremeIn, extremeOut, plan220To40)
    assertTrue("220 to 40 BPM must trigger Fallback", result220To40 is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.TEMPO_OUT_OF_BOUNDS, (result220To40 as TransitionPlanResult.Fallback).reason)
  }

  @Test
  fun testTempoToleranceBoundaryPrecision() {
    val base = TrackProfile("base", 100.0f, 0.9f, 0L, durationMs = 200_000L)

    // Exactly 1.08 ratio (incoming 92.59259f -> 100 / 92.59259 = 1.08)
    val in108 = TrackProfile("in108", 100.0f / 1.08f, 0.9f, 0L, durationMs = 200_000L)
    val plan108 = planner.planTransition(base, in108)
    assertEquals(1.08f, plan108.tempoRatio, 0.002f)
    val val108 = validator.validate(base, in108, plan108)
    assertTrue("1.08 ratio must pass Gate 3", val108 is TransitionPlanResult.Valid)

    // Slightly above 1.08 ratio (e.g. 1.085)
    val in1085 = TrackProfile("in1085", 100.0f / 1.085f, 0.9f, 0L, durationMs = 200_000L)
    val plan1085 = planner.planTransition(base, in1085)
    assertEquals(1.0f, plan1085.tempoRatio, 0.001f) // Clamped to 1.0f
    val val1085 = validator.validate(base, in1085, plan1085)
    assertTrue("1.085 ratio must fail Gate 3", val1085 is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.TEMPO_OUT_OF_BOUNDS, (val1085 as TransitionPlanResult.Fallback).reason)

    // Exactly 0.92 ratio (incoming 100 / 0.92 = 108.69565f)
    val in092 = TrackProfile("in092", 100.0f / 0.92f, 0.9f, 0L, durationMs = 200_000L)
    val plan092 = planner.planTransition(base, in092)
    assertEquals(0.92f, plan092.tempoRatio, 0.002f)
    val val092 = validator.validate(base, in092, plan092)
    assertTrue("0.92 ratio must pass Gate 3", val092 is TransitionPlanResult.Valid)

    // Slightly below 0.92 ratio (e.g. 0.915)
    val in0915 = TrackProfile("in0915", 100.0f / 0.915f, 0.9f, 0L, durationMs = 200_000L)
    val plan0915 = planner.planTransition(base, in0915)
    assertEquals(1.0f, plan0915.tempoRatio, 0.001f) // Clamped to 1.0f
    val val0915 = validator.validate(base, in0915, plan0915)
    assertTrue("0.915 ratio must fail Gate 3", val0915 is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.TEMPO_OUT_OF_BOUNDS, (val0915 as TransitionPlanResult.Fallback).reason)
  }

  // =========================================================================
  // 2. CAMELOT DISTANCE & HARMONIC MATH STRESS (576 combinations, wrapping, fuzz)
  // =========================================================================

  @Test
  fun testCamelotAll576PairsMetricProperties() {
    val allKeys = mutableListOf<CamelotKey>()
    for (num in 1..12) {
      allKeys.add(CamelotKey(num, CamelotMode.A))
      allKeys.add(CamelotKey(num, CamelotMode.B))
    }
    assertEquals(24, allKeys.size)

    for (k1 in allKeys) {
      for (k2 in allKeys) {
        val dist12 = k1.circularDistance(k2)
        val dist21 = k2.circularDistance(k1)

        // Symmetry
        assertEquals("Distance symmetry for $k1 and $k2", dist12, dist21)

        // Bounds: [0, 6]
        assertTrue("Distance between 0 and 6 for $k1 and $k2 was $dist12", dist12 in 0..6)

        // Identity
        if (k1.number == k2.number) {
          assertEquals("Identical number distance must be 0", 0, dist12)
        } else {
          assertTrue("Distinct number distance must be > 0", dist12 > 0)
        }

        // Harmonic score bounds [0.0, 1.0]
        val score = k1.harmonicCompatibilityScore(k2)
        assertTrue("Score for $k1 -> $k2 must be in [0.0, 1.0], was $score", score in 0.0f..1.0f)

        // Relative keys (same number, different mode)
        if (k1.number == k2.number && k1.mode != k2.mode) {
          assertEquals("Relative key compatibility must be 1.0", 1.0f, score, 0.001f)
          assertTrue(k1.isRelativeKey(k2))
          assertFalse(k1.isSameKey(k2))
        }

        // Same key
        if (k1 == k2) {
          assertEquals("Same key compatibility must be 1.0", 1.0f, score, 0.001f)
          assertTrue(k1.isSameKey(k2))
        }

        // Triangle inequality across all 3rd keys
        for (k3 in allKeys) {
          val d13 = k1.circularDistance(k3)
          val d23 = k2.circularDistance(k3)
          assertTrue("Triangle inequality violated for $k1, $k2, $k3", d13 <= dist12 + d23)
        }
      }
    }
  }

  @Test
  fun testCamelot12To1WrapAround() {
    val k12A = CamelotKey(12, CamelotMode.A)
    val k1A = CamelotKey(1, CamelotMode.A)
    val k12B = CamelotKey(12, CamelotMode.B)
    val k1B = CamelotKey(1, CamelotMode.B)

    // Adjacent across wrap-around
    assertEquals(1, k12A.circularDistance(k1A))
    assertEquals(1, k1A.circularDistance(k12A))
    assertEquals(1, k12B.circularDistance(k1B))
    assertEquals(1, k1B.circularDistance(k12B))
    assertTrue(k12A.isAdjacent(k1A))
    assertTrue(k1A.isAdjacent(k12A))

    // Harmonic scores across wrap-around
    assertEquals(0.9f, k12A.harmonicCompatibilityScore(k1A), 0.001f)
    assertEquals(0.9f, k1A.harmonicCompatibilityScore(k12A), 0.001f)

    // Diagonal shift across wrap-around (12A <-> 1B)
    assertEquals(0.75f, k12A.harmonicCompatibilityScore(k1B), 0.001f)
    assertEquals(0.75f, k1A.harmonicCompatibilityScore(k12B), 0.001f)

    // Energy boost wrapping across 12 (e.g. 11A -> 1A is +2 steps; 6A -> 1A is +7 steps)
    val k11A = CamelotKey(11, CamelotMode.A)
    val k6A = CamelotKey(6, CamelotMode.A)
    assertTrue("11A -> 1A is +2 energy boost", k11A.isEnergyBoost(k1A))
    assertEquals(0.6f, k11A.harmonicCompatibilityScore(k1A), 0.001f)

    assertTrue("6A -> 1A is +7 energy boost", k6A.isEnergyBoost(k1A))
    assertEquals(0.8f, k6A.harmonicCompatibilityScore(k1A), 0.001f)
  }

  @Test
  fun testCamelotParserFuzzingAndInvalidInputs() {
    val invalidStrings = listOf(
      "", " ", "   ", "\t", "\n",
      "0A", "13A", "99A", "-1A", "00A",
      "0B", "13B", "14B",
      "1C", "8Z", "8!", "8#", "8_",
      "A8", "B12", "8AA", "12BB",
      "8 A", " 8 A", "8\tA",
      "null", "undefined", "NaN",
    )

    for (inv in invalidStrings) {
      assertNull("Expected null parse for input '$inv'", CamelotKey.parse(inv))
    }

    assertNull(CamelotKey.parse(null))

    // Valid parsing with leading/trailing whitespace and case variations
    val validParsed = CamelotKey.parse(" 8a ")
    assertNotNull(validParsed)
    assertEquals(8, validParsed!!.number)
    assertEquals(CamelotMode.A, validParsed.mode)

    // Constructor validation
    try {
      CamelotKey(0, CamelotMode.A)
      org.junit.Assert.fail("CamelotKey with number 0 should throw")
    } catch (e: IllegalArgumentException) {
      // Expected
    }

    try {
      CamelotKey(13, CamelotMode.B)
      org.junit.Assert.fail("CamelotKey with number 13 should throw")
    } catch (e: IllegalArgumentException) {
      // Expected
    }

    // Pitch class range validation
    try {
      CamelotKey.fromPitchClass(-1, true)
      org.junit.Assert.fail("Pitch class -1 should throw")
    } catch (e: IllegalArgumentException) {
      // Expected
    }

    try {
      CamelotKey.fromPitchClass(12, false)
      org.junit.Assert.fail("Pitch class 12 should throw")
    } catch (e: IllegalArgumentException) {
      // Expected
    }
  }

  // =========================================================================
  // 3. PHRASE BOUNDARIES, MIX POINTS & FALLBACK GATES STRESS
  // =========================================================================

  @Test
  fun testPhraseBoundarySnappingWithNegativeAndExtremeOffsets() {
    // 120 BPM -> period = 500ms, 8 beats = 4000ms phrase
    // Outgoing with negative offset (-300ms) -> coerced to 0L
    val outNegativeOffset = TrackProfile(
      songId = "outNeg",
      bpm = 120.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = -300L,
      durationMs = 200_000L,
      mixOutPointMs = 180_000L,
    )
    val inNormal = TrackProfile(
      songId = "inNormal",
      bpm = 120.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 200_000L,
    )

    val plan = planner.planTransition(outNegativeOffset, inNormal)
    val phraseMs = 4000L
    assertEquals("Trigger must snap to 0-offset phrase boundary", 0L, plan.triggerTimeMs % phraseMs)
    assertTrue("Trigger must be <= mixOutPointMs", plan.triggerTimeMs <= 180_000L)
    assertTrue("Trigger must be within one phrase of 180_000L", 180_000L - plan.triggerTimeMs < phraseMs)

    // Incoming with negative offset (-500ms) and mixInPoint (14_500ms)
    val inNegativeOffset = TrackProfile(
      songId = "inNeg",
      bpm = 120.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = -500L,
      durationMs = 200_000L,
      mixInPointMs = 14_500L,
    )

    val planInNeg = planner.planTransition(outNegativeOffset, inNegativeOffset)
    assertEquals("Incoming start must snap to phrase boundary", 0L, planInNeg.incomingStartMs % phraseMs)
    assertTrue("Incoming start must be >= mixInPointMs", planInNeg.incomingStartMs >= 14_500L)
  }

  @Test
  fun testZeroAndNearZeroTrackDurationsTriggerHeadroomFallback() {
    val zeroDurOut = TrackProfile("zero", 120.0f, 0.9f, 0L, durationMs = 0L)
    val normalIn = TrackProfile("in", 120.0f, 0.9f, 0L, durationMs = 180_000L)

    val planZero = planner.planTransition(zeroDurOut, normalIn, generationId = 123L)
    val resultZero = validator.validate(zeroDurOut, normalIn, planZero)
    assertTrue("Zero duration must trigger Fallback", resultZero is TransitionPlanResult.Fallback)
    val fallbackZero = resultZero as TransitionPlanResult.Fallback
    assertEquals(FallbackReason.INSUFFICIENT_HEADROOM, fallbackZero.reason)
    assertEquals(123L, fallbackZero.plan.generationId)
    assertEquals(TransitionType.STANDARD_CROSSFADE, fallbackZero.plan.transitionType)

    // Track duration too short for required 3000ms headroom
    val shortDurOut = TrackProfile("short", 120.0f, 0.9f, 0L, durationMs = 2500L)
    val planShort = planner.planTransition(shortDurOut, normalIn, generationId = 456L)
    val resultShort = validator.validate(shortDurOut, normalIn, planShort)
    assertTrue("Short duration < 3000ms must trigger Fallback", resultShort is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.INSUFFICIENT_HEADROOM, (resultShort as TransitionPlanResult.Fallback).reason)
    assertEquals(456L, resultShort.plan.generationId)
  }

  @Test
  fun testConfidenceThresholdBoundaryNearGate2() {
    val presets = listOf(
      DjPreset.SEAMLESS to 0.35f,
      DjPreset.CLUB to 0.40f,
      DjPreset.CREATIVE to 0.30f,
      DjPreset.CONSERVATIVE to 0.45f,
    )

    val baseOut = TrackProfile("out", 120.0f, 0.9f, 0L, durationMs = 200_000L)

    for ((preset, threshold) in presets) {
      // Exactly at threshold -> Valid
      val inExact = TrackProfile("inExact", 120.0f, threshold, 0L, durationMs = 200_000L)
      val planExact = planner.planTransition(baseOut, inExact, preset = preset, generationId = 11L)
      val resExact = validator.validate(baseOut, inExact, planExact, preset = preset)
      assertTrue("Confidence exactly at threshold $threshold for ${preset.name} must be Valid", resExact is TransitionPlanResult.Valid)

      // Just below threshold (-0.001f) -> Fallback LOW_CONFIDENCE
      val inBelow = TrackProfile("inBelow", 120.0f, threshold - 0.001f, 0L, durationMs = 200_000L)
      val planBelow = planner.planTransition(baseOut, inBelow, preset = preset, generationId = 22L)
      val resBelow = validator.validate(baseOut, inBelow, planBelow, preset = preset)
      assertTrue("Confidence below threshold $threshold for ${preset.name} must trigger Fallback", resBelow is TransitionPlanResult.Fallback)
      assertEquals(FallbackReason.LOW_CONFIDENCE, (resBelow as TransitionPlanResult.Fallback).reason)
      assertEquals(22L, resBelow.plan.generationId)

      // Outgoing track below threshold -> Fallback LOW_CONFIDENCE
      val outBelow = TrackProfile("outBelow", 120.0f, threshold - 0.001f, 0L, durationMs = 200_000L)
      val planOutBelow = planner.planTransition(outBelow, baseOut, preset = preset, generationId = 33L)
      val resOutBelow = validator.validate(outBelow, baseOut, planOutBelow, preset = preset)
      assertTrue("Outgoing confidence below threshold must trigger Fallback", resOutBelow is TransitionPlanResult.Fallback)
      assertEquals(FallbackReason.LOW_CONFIDENCE, (resOutBelow as TransitionPlanResult.Fallback).reason)
      assertEquals(33L, resOutBelow.plan.generationId)
    }
  }

  @Test
  fun testGate1MissingProfileCombinations() {
    val validP = TrackProfile("v", 120.0f, 0.9f, 0L, durationMs = 200_000L)
    val plan = planner.planTransition(validP, validP, generationId = 99L)

    // Null outgoing
    val resNullOut = validator.validate(null, validP, plan)
    assertTrue(resNullOut is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resNullOut as TransitionPlanResult.Fallback).reason)
    assertEquals(99L, resNullOut.plan.generationId)

    // Null incoming
    val resNullIn = validator.validate(validP, null, plan)
    assertTrue(resNullIn is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resNullIn as TransitionPlanResult.Fallback).reason)

    // BPM <= 0.0f
    val zeroBpm = TrackProfile("zBpm", 0.0f, 0.9f, 0L, durationMs = 200_000L)
    val resZeroBpm = validator.validate(zeroBpm, validP, plan)
    assertTrue(resZeroBpm is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resZeroBpm as TransitionPlanResult.Fallback).reason)

    // Negative BPM
    val negBpm = TrackProfile("negBpm", -120.0f, 0.9f, 0L, durationMs = 200_000L)
    val resNegBpm = validator.validate(validP, negBpm, plan)
    assertTrue(resNegBpm is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resNegBpm as TransitionPlanResult.Fallback).reason)

    // Confidence <= 0.0f (hasValidAnalysis == false)
    val zeroConf = TrackProfile("zConf", 120.0f, 0.0f, 0L, durationMs = 200_000L)
    val resZeroConf = validator.validate(zeroConf, validP, plan)
    assertTrue(resZeroConf is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.MISSING_PROFILE, (resZeroConf as TransitionPlanResult.Fallback).reason)
  }

  @Test
  fun testPlaybackPositionNearEndOfTrackTriggersGate4() {
    val duration = 180_000L
    val out = TrackProfile("out", 120.0f, 0.9f, 0L, durationMs = duration)
    val inc = TrackProfile("in", 120.0f, 0.9f, 0L, durationMs = duration)

    // Current playback position at 178_000ms (only 2000ms left in track)
    val planLate = planner.planTransition(out, inc, currentPlaybackPositionMs = 178_000L, generationId = 777L)
    val resLate = validator.validate(out, inc, planLate)
    assertTrue("Playback near end of track must trigger Fallback", resLate is TransitionPlanResult.Fallback)
    assertEquals(FallbackReason.INSUFFICIENT_HEADROOM, (resLate as TransitionPlanResult.Fallback).reason)
    assertEquals(777L, resLate.plan.generationId)
  }

  @Test
  fun testMonotonicGenerationIdRetentionAcrossAllFallbacks() {
    val testGenId = 9876543210L

    val invalidProfile = TrackProfile("inv", 0f, 0f, 0L)
    val validProfile = TrackProfile("v", 120f, 0.9f, 0L, durationMs = 200_000L)
    val dummyPlan = TransitionPlan(
      outgoingSongId = "inv",
      incomingSongId = "v",
      triggerTimeMs = 0L,
      incomingStartMs = 0L,
      overlapDurationMs = 5000L,
      generationId = testGenId,
    )

    // Gate 1 fallback retention
    val res1 = validator.validate(invalidProfile, validProfile, dummyPlan)
    assertEquals(testGenId, res1.plan.generationId)

    // Gate 2 fallback retention
    val lowConfProfile = TrackProfile("lc", 120f, 0.1f, 0L, durationMs = 200_000L)
    val res2 = validator.validate(lowConfProfile, validProfile, dummyPlan)
    assertEquals(testGenId, res2.plan.generationId)

    // Gate 3 fallback retention
    val clashTempoProfile = TrackProfile("clash", 200f, 0.9f, 0L, durationMs = 200_000L)
    val res3 = validator.validate(clashTempoProfile, validProfile, dummyPlan)
    assertEquals(testGenId, res3.plan.generationId)

    // Gate 4 fallback retention
    val noRoomProfile = TrackProfile("noRoom", 120f, 0.9f, 0L, durationMs = 1000L)
    val res4 = validator.validate(noRoomProfile, validProfile, dummyPlan)
    assertEquals(testGenId, res4.plan.generationId)
  }
}
