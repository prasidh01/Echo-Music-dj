package echo.music.domain.autodj.planner

import echo.music.domain.autodj.model.CamelotKey
import echo.music.domain.autodj.model.CamelotMode
import echo.music.domain.autodj.model.DjPreset
import echo.music.domain.autodj.model.TrackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransitionPlannerTest {

  private lateinit var planner: TransitionPlanner

  @Before
  fun setup() {
    planner = TransitionPlanner.create()
  }

  @Test
  fun testTempoOctaveFolding() {
    // 70 BPM -> 140 BPM (Half/Double time): ratio folds to 1.0
    val p70 = TrackProfile(
      songId = "s70",
      bpm = 70.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 180_000L,
    )
    val p140 = TrackProfile(
      songId = "s140",
      bpm = 140.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 180_000L,
    )
    val plan70To140 = planner.planTransition(p70, p140)
    assertEquals(1.0f, plan70To140.tempoRatio, 0.001f)

    val plan140To70 = planner.planTransition(p140, p70)
    assertEquals(1.0f, plan140To70.tempoRatio, 0.001f)

    // 175 BPM -> 87.5 BPM: folds to 1.0
    val p175 = TrackProfile(
      songId = "s175",
      bpm = 175.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 180_000L,
    )
    val p87_5 = TrackProfile(
      songId = "s87",
      bpm = 87.5f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 180_000L,
    )
    val plan175 = planner.planTransition(p175, p87_5)
    assertEquals(1.0f, plan175.tempoRatio, 0.001f)

    // 128 BPM -> 125 BPM: within ±8% -> 128/125 = 1.024
    val p128 = TrackProfile(
      songId = "s128",
      bpm = 128.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 180_000L,
    )
    val p125 = TrackProfile(
      songId = "s125",
      bpm = 125.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 180_000L,
    )
    val plan128To125 = planner.planTransition(p128, p125)
    assertEquals(1.024f, plan128To125.tempoRatio, 0.005f)

    // 128 BPM -> 90 BPM: ratio 1.422 exceeds ±8% -> clamped to 1.0f
    val p90 = TrackProfile(
      songId = "s90",
      bpm = 90.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      durationMs = 180_000L,
    )
    val plan128To90 = planner.planTransition(p128, p90)
    assertEquals(1.0f, plan128To90.tempoRatio, 0.001f)
  }

  @Test
  fun testPhraseBoundarySnappingAndAnchoring() {
    // 120 BPM -> period = 500ms. phraseBeatCount = 8 -> phrase = 4000ms. firstBeatOffset = 250ms.
    val outgoing = TrackProfile(
      songId = "out",
      bpm = 120.0f,
      confidence = 0.95f,
      firstBeatOffsetMs = 250L,
      phraseBeatCount = 8,
      durationMs = 200_000L,
      mixOutPointMs = 185_000L,
    )
    val incoming = TrackProfile(
      songId = "in",
      bpm = 120.0f,
      confidence = 0.95f,
      firstBeatOffsetMs = 100L,
      phraseBeatCount = 8,
      durationMs = 200_000L,
    )

    val plan = planner.planTransition(outgoing, incoming, preset = DjPreset.SEAMLESS, currentPlaybackPositionMs = 0L)

    val phraseMs = 4000L
    val firstBeat = 250L
    // Verify trigger time snaps strictly to (firstBeat + k * phraseMs)
    assertEquals(0L, (plan.triggerTimeMs - firstBeat) % phraseMs)
    // Snaps immediately preceding or at mixOutPointMs (185_000)
    assertTrue("Trigger should be <= mixOutPointMs", plan.triggerTimeMs <= 185_000L)
    assertTrue("Trigger should be close to mixOutPointMs", 185_000L - plan.triggerTimeMs < phraseMs)

    // Position guard: if current playback is 186_000ms, trigger must not be scheduled in the past
    val planLate = planner.planTransition(outgoing, incoming, preset = DjPreset.SEAMLESS, currentPlaybackPositionMs = 186_000L)
    assertTrue("Late trigger must be after current position + 1000", planLate.triggerTimeMs >= 187_000L)
    assertEquals(0L, (planLate.triggerTimeMs - firstBeat) % phraseMs)
  }

  @Test
  fun testMixInSkipAndSnapping() {
    val outgoing = TrackProfile(
      songId = "out",
      bpm = 120.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      phraseBeatCount = 8,
      durationMs = 200_000L,
    )
    // Incoming 120 BPM (phrase = 4000ms), firstBeat = 200ms, mixInPointMs = 15_000ms
    val incoming = TrackProfile(
      songId = "in",
      bpm = 120.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 200L,
      phraseBeatCount = 8,
      durationMs = 200_000L,
      mixInPointMs = 15_000L,
    )

    val plan = planner.planTransition(outgoing, incoming)

    val inPhraseMs = 4000L
    val inFirstBeat = 200L
    // Verify incoming starts on phrase boundary >= 15_000ms
    assertTrue("Incoming start should be >= 15_000ms", plan.incomingStartMs >= 15_000L)
    assertEquals(0L, (plan.incomingStartMs - inFirstBeat) % inPhraseMs)
    // First phrase boundary >= 15000: (15000 - 200) / 4000 = ceil(3.7) = 4 -> 200 + 4 * 4000 = 16200ms
    assertEquals(16_200L, plan.incomingStartMs)
  }

  @Test
  fun testPresetSpecialization() {
    val outgoing = TrackProfile(
      songId = "out",
      bpm = 120.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      phraseBeatCount = 8,
      durationMs = 200_000L,
      camelotKey = CamelotKey(8, CamelotMode.A),
    )
    val incoming = TrackProfile(
      songId = "in",
      bpm = 120.0f,
      confidence = 0.9f,
      firstBeatOffsetMs = 0L,
      phraseBeatCount = 8,
      durationMs = 200_000L,
      camelotKey = CamelotKey(2, CamelotMode.A), // 6 steps away (clash)
    )

    // SEAMLESS: Overlap [6000, 16000], ducking -10 dB, pitch shift enabled
    val planSeamless = planner.planTransition(outgoing, incoming, preset = DjPreset.SEAMLESS)
    assertTrue(planSeamless.overlapDurationMs in 6_000L..16_000L)
    assertEquals(-10.0f, planSeamless.lowShelfDuckDb, 0.001f)

    // CLUB: Overlap [4000, 10000], ducking -14 dB, pitch shift disabled
    val planClub = planner.planTransition(outgoing, incoming, preset = DjPreset.CLUB)
    assertTrue(planClub.overlapDurationMs in 4_000L..10_000L)
    assertEquals(-14.0f, planClub.lowShelfDuckDb, 0.001f)
    assertEquals(0, planClub.pitchShiftSemitones)
    assertEquals(1.0f, planClub.pitchRatio, 0.001f)

    // CONSERVATIVE: Overlap [3000, 6000], ducking -6 dB
    val planConservative = planner.planTransition(outgoing, incoming, preset = DjPreset.CONSERVATIVE)
    assertTrue(planConservative.overlapDurationMs in 3_000L..6_000L)
    assertEquals(-6.0f, planConservative.lowShelfDuckDb, 0.001f)
  }
}
