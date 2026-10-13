package echo.music.domain.autodj.reorder

import echo.music.domain.autodj.model.DjPreset
import echo.music.domain.autodj.model.TrackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRemixOrderingEngineTest {

  data class SimpleTrack(
    val id: String,
    val bpm: Float,
    val key: String?,
  )

  private fun toProfile(track: SimpleTrack?): TrackProfile? =
    track?.let { TrackProfile(songId = it.id, bpm = it.bpm, camelotKey = it.key) }

  @Test
  fun testPairwiseCompatibilityScoring() {
    // T1-F14-01: Curr: (124, 8A). Cand A: (126, 8A), Cand B: (140, 3B) -> Score(A) > Score(B) (0.95 vs 0.25)
    val scoreA = AutoRemixScorer.scoreTransition(124f, "8A", 126f, "8A")
    val scoreB = AutoRemixScorer.scoreTransition(124f, "8A", 140f, "3B")

    assertTrue("Score(A) must be > 0.90", scoreA > 0.90f)
    assertTrue("Score(B) must be < 0.35", scoreB < 0.35f)
    assertTrue("Score(A) must exceed Score(B)", scoreA > scoreB)
  }

  @Test
  fun testUpcomingQueueReordering() {
    // T1-F14-02: Current playing + 5 upcoming queue items -> item with highest score moved to index 1 (next track)
    val current = SimpleTrack("curr", 124f, "8A")
    val upcoming = listOf(
      SimpleTrack("cand_bad", 145f, "2B"),
      SimpleTrack("cand_mediocre", 130f, "7A"),
      SimpleTrack("cand_best", 125f, "8A"), // High harmonic + tempo match
      SimpleTrack("cand_ok", 128f, "9A"),
      SimpleTrack("cand_bad2", 150f, "1B"),
    )

    val reordered = QueueManager.reorderUpcoming(
      currentTrack = current,
      upcomingQueue = upcoming,
      profileProvider = ::toProfile,
      autoRemixOrderingEnabled = true,
    )

    assertEquals("Highest compatibility candidate must be first in upcoming queue", "cand_best", reordered[0].id)
    assertEquals(5, reordered.size)
  }

  @Test
  fun testAutoRemixOrderingToggleBypass() {
    // T1-F14-04: AutoRemixOrderingKey = false -> Natural playlist queue order preserved untouched
    val current = SimpleTrack("curr", 124f, "8A")
    val upcoming = listOf(
      SimpleTrack("t1", 145f, "2B"),
      SimpleTrack("t2", 125f, "8A"),
      SimpleTrack("t3", 130f, "7A"),
    )

    val unchanged = QueueManager.reorderUpcoming(
      currentTrack = current,
      upcomingQueue = upcoming,
      profileProvider = ::toProfile,
      autoRemixOrderingEnabled = false,
    )

    assertEquals(listOf("t1", "t2", "t3"), unchanged.map { it.id })
  }

  @Test
  fun testStableTieBreakerOrdering() {
    // T1-F14-05: Two candidates with identical score -> Original playlist order preserved as tie-breaker
    val current = SimpleTrack("curr", 124f, "8A")
    val upcoming = listOf(
      SimpleTrack("first", 124f, "8A"),
      SimpleTrack("second", 124f, "8A"),
    )

    val ranked = AutoRemixOrderingEngine.rankCandidates(
      anchor = toProfile(current),
      candidates = upcoming,
      profileProvider = ::toProfile,
    )

    assertEquals("first", ranked[0].id)
    assertEquals("second", ranked[1].id)
  }

  @Test
  fun testRecommendationPoolRanking() {
    // T1-F14-03: Unsorted list of 10 recommendation tracks returned sorted in descending order of compatibility score
    val anchor = SimpleTrack("anchor", 124f, "8A")
    val candidates = listOf(
      SimpleTrack("c1", 140f, "1B"),
      SimpleTrack("c2", 124f, "8A"), // Perfect match
      SimpleTrack("c3", 125f, "9A"), // Adjacent fifth
      SimpleTrack("c4", 135f, "2B"),
    )

    val ranked = AutoRemixOrderingEngine.rankCandidates(
      anchor = toProfile(anchor),
      candidates = candidates,
      profileProvider = ::toProfile,
    )

    val scores = ranked.map { AutoRemixOrderingEngine.scoreTransition(toProfile(anchor), toProfile(it)) }
    for (i in 0 until scores.size - 1) {
      assertTrue("Scores must be descending: ${scores[i]} >= ${scores[i + 1]}", scores[i] >= scores[i + 1])
    }
  }

  @Test
  fun testBoundaryEmptyAndSingleItemQueue() {
    // T2-F14-01: Empty upcoming queue -> safe no-op
    val empty = QueueManager.reorderUpcoming<SimpleTrack>(
      currentTrack = null,
      upcomingQueue = emptyList(),
      profileProvider = ::toProfile,
    )
    assertTrue(empty.isEmpty())

    // T2-F14-02: Single item queue -> safe no-op
    val single = listOf(SimpleTrack("s1", 120f, "8A"))
    val singleResult = QueueManager.reorderUpcoming(
      currentTrack = null,
      upcomingQueue = single,
      profileProvider = ::toProfile,
    )
    assertEquals(1, singleResult.size)
    assertEquals("s1", singleResult[0].id)
  }

  @Test
  fun testBoundaryMassiveQueueBoundedLookahead() {
    // T2-F14-03: Massive queue size (1,000 items) -> scoring limited to lookahead window (10 items) (<5ms)
    val current = SimpleTrack("curr", 124f, "8A")
    val massive = (1..1000).map { i -> SimpleTrack("track_$i", 120f + (i % 20), "${(i % 12) + 1}A") }

    val start = System.currentTimeMillis()
    val reordered = QueueManager.reorderUpcoming(
      currentTrack = current,
      upcomingQueue = massive,
      profileProvider = ::toProfile,
      lookaheadWindow = 10,
    )
    val elapsed = System.currentTimeMillis() - start

    assertEquals(1000, reordered.size)
    assertTrue("Execution must complete fast (< 100ms), took ${elapsed}ms", elapsed < 100L)
    // Tracks beyond index 10 must match original sublist order
    assertEquals(massive.subList(10, 1000).map { it.id }, reordered.subList(10, 1000).map { it.id })
  }

  @Test
  fun testBoundaryIdenticalProfilesPreserveOrder() {
    // T2-F14-04: 10 candidates with identical 128 BPM / 8A -> original playlist order strictly preserved
    val current = SimpleTrack("curr", 128f, "8A")
    val identical = (1..10).map { i -> SimpleTrack("item_$i", 128f, "8A") }

    val reordered = QueueManager.reorderUpcoming(
      currentTrack = current,
      upcomingQueue = identical,
      profileProvider = ::toProfile,
    )

    assertEquals(identical.map { it.id }, reordered.map { it.id })
  }

  @Test
  fun testBoundaryUnanalyzedProfilesPreserveOrder() {
    // T2-F14-05: Candidates have null profiles -> safe no-op, maintains natural order
    val current = SimpleTrack("curr", 120f, "8A")
    val unanalyzed = (1..5).map { i -> SimpleTrack("un_$i", 0f, null) }

    val reordered = QueueManager.reorderUpcoming(
      currentTrack = current,
      upcomingQueue = unanalyzed,
      profileProvider = { null },
    )

    assertEquals(unanalyzed.map { it.id }, reordered.map { it.id })
  }

  @Test
  fun testPresetCorruptedStringFallback() {
    // T2-F12-01: DataStore contains "INVALID_PRESET" -> falls back safely to default DjPreset.SEAMLESS
    val parsed = DjPreset.fromName("INVALID_PRESET")
    assertEquals(DjPreset.SEAMLESS, parsed)

    val nullParsed = DjPreset.fromName(null)
    assertEquals(DjPreset.SEAMLESS, nullParsed)

    val customParsed = DjPreset.fromName("custom")
    assertEquals(DjPreset.CUSTOM, customParsed)

    val keyLockParsed = DjPreset.fromName("KeyLock")
    assertEquals(DjPreset.KEY_LOCK, keyLockParsed)
  }
}
