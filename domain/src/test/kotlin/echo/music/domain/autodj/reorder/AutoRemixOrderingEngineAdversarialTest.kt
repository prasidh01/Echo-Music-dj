package echo.music.domain.autodj.reorder

import echo.music.domain.autodj.model.TrackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoRemixOrderingEngineAdversarialTest {

  data class TestTrack(
    val id: String,
    val bpm: Float,
    val key: String?,
  )

  private fun toProfile(track: TestTrack?): TrackProfile? =
    track?.let { TrackProfile(songId = it.id, bpm = it.bpm, camelotKey = it.key) }

  @Test
  fun testEmptyListHandling() {
    val empty = emptyList<TestTrack>()
    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = null,
      candidates = empty,
      profileProvider = ::toProfile,
    )
    assertTrue("Chaining empty list must return empty list", chained.isEmpty())

    val ranked = AutoRemixOrderingEngine.rankCandidates(
      anchor = null,
      candidates = empty,
      profileProvider = ::toProfile,
    )
    assertTrue("Ranking empty list must return empty list", ranked.isEmpty())

    val reordered = QueueManager.reorderUpcoming(
      currentTrack = null,
      upcomingQueue = empty,
      profileProvider = ::toProfile,
    )
    assertTrue("Queue reordering empty list must return empty list", reordered.isEmpty())
  }

  @Test
  fun testSingleItemHandling() {
    val single = listOf(TestTrack("single", 124f, "8A"))
    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = null,
      candidates = single,
      profileProvider = ::toProfile,
    )
    assertEquals(1, chained.size)
    assertEquals("single", chained[0].id)

    val ranked = AutoRemixOrderingEngine.rankCandidates(
      anchor = null,
      candidates = single,
      profileProvider = ::toProfile,
    )
    assertEquals(1, ranked.size)
    assertEquals("single", ranked[0].id)

    val reordered = QueueManager.reorderUpcoming(
      currentTrack = null,
      upcomingQueue = single,
      profileProvider = ::toProfile,
    )
    assertEquals(1, reordered.size)
    assertEquals("single", reordered[0].id)
  }

  @Test
  fun testUnanalyzedItemsGracefulDegradation() {
    val anchor = TestTrack("anchor", 120f, "8A")
    val candidates = listOf(
      TestTrack("zero_bpm", 0f, "8A"),
      TestTrack("neg_bpm", -120f, "8A"),
      TestTrack("nan_bpm", Float.NaN, "8A"),
      TestTrack("null_key", 120f, null),
      TestTrack("blank_key", 120f, "   "),
      TestTrack("invalid_key", 120f, "ZZZ"),
      TestTrack("all_null", 0f, null),
    )

    for (c in candidates) {
      val score = AutoRemixOrderingEngine.scoreTransition(toProfile(anchor), toProfile(c))
      assertTrue("Score for $c must be valid within [0.0, 1.0], was $score", score in 0.0f..1.0f)
    }

    // Both profiles null
    val scoreBothNull = AutoRemixOrderingEngine.scoreTransition(null, null)
    assertEquals(0.50f, scoreBothNull, 1e-4f)

    // Unanalyzed candidates with null anchor
    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = null,
      candidates = candidates,
      profileProvider = ::toProfile,
    )
    assertEquals(candidates.size, chained.size)
  }

  @Test
  fun testExtremeBpmValues() {
    val extremeCandidates = listOf(
      TestTrack("max_bpm", Float.MAX_VALUE, "8A"),
      TestTrack("tiny_bpm", 1e-6f, "8A"),
      TestTrack("huge_bpm", 99999f, "8A"),
      TestTrack("normal_bpm", 120f, "8A"),
    )
    val anchor = TestTrack("anchor", 120f, "8A")

    for (c in extremeCandidates) {
      val score = AutoRemixOrderingEngine.scoreTransition(toProfile(anchor), toProfile(c))
      assertTrue("Score for extreme BPM must be finite in [0.0, 1.0], got $score", !score.isNaN() && score in 0.0f..1.0f)
    }

    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = toProfile(anchor),
      candidates = extremeCandidates,
      profileProvider = ::toProfile,
    )
    assertEquals("All tracks must be retained without loss", extremeCandidates.size, chained.size)
    assertEquals("Normal BPM match must be chosen first", "normal_bpm", chained[0].id)
  }

  @Test
  fun testCircularCamelotWrapAround() {
    // 12A -> 1A is adjacent on the Camelot wheel (distance 1)
    val scoreWrapForward = AutoRemixOrderingEngine.scoreTransition(120f, "12A", 120f, "1A")
    val scoreWrapBackward = AutoRemixOrderingEngine.scoreTransition(120f, "1A", 120f, "12A")
    val scoreDirectNeighbor = AutoRemixOrderingEngine.scoreTransition(120f, "1A", 120f, "2A")

    assertEquals("Circular wrap 12A->1A must equal 1A->12A score", scoreWrapForward, scoreWrapBackward, 1e-4f)
    assertEquals("Circular wrap 12A->1A must equal direct adjacent 1A->2A score", scoreWrapForward, scoreDirectNeighbor, 1e-4f)

    // Wrap around in candidate selection
    val anchor12A = TestTrack("anchor", 120f, "12A")
    val candidates = listOf(
      TestTrack("clash_6A", 120f, "6A"), // opposite wheel
      TestTrack("wrap_1A", 120f, "1A"),   // adjacent wrap
      TestTrack("distant_4A", 120f, "4A"),
    )

    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = toProfile(anchor12A),
      candidates = candidates,
      profileProvider = ::toProfile,
    )
    assertEquals("wrap_1A must be chosen first from 12A", "wrap_1A", chained[0].id)
  }

  @Test
  fun testStableTieBreakingAndOriginalOrderPreserved() {
    val anchor = TestTrack("anchor", 128f, "8A")
    val identicalCandidates = (1..50).map { i -> TestTrack("id_$i", 128f, "8A") }

    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = toProfile(anchor),
      candidates = identicalCandidates,
      profileProvider = ::toProfile,
    )

    assertEquals("Output size must match input size", identicalCandidates.size, chained.size)
    for (i in identicalCandidates.indices) {
      assertEquals("Index $i must match original order due to tie-breaking stability", identicalCandidates[i].id, chained[i].id)
    }
  }

  @Test
  fun testTrackIntegrityNoLossOrDuplicationStress() {
    val random = Random(42)
    val camelotKeys = (1..12).flatMap { listOf("${it}A", "${it}B") } + listOf(null)

    val testItems = (1..100).map { i ->
      TestTrack(
        id = "track_$i",
        bpm = if (random.nextBoolean()) 70f + random.nextFloat() * 100f else 0f,
        key = camelotKeys[random.nextInt(camelotKeys.size)],
      )
    }

    val anchor = TestTrack("anchor", 124f, "8A")

    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = toProfile(anchor),
      candidates = testItems,
      profileProvider = ::toProfile,
      lookaheadLimit = 15,
    )

    assertEquals(100, chained.size)
    val inputIds = testItems.map { it.id }.toSet()
    val outputIds = chained.map { it.id }.toSet()
    assertEquals("Output set must exactly match input set (no lost or duplicated tracks)", inputIds, outputIds)
  }

  @Test
  fun testDuplicateTracksRetainedExactMultiplicity() {
    val t1 = TestTrack("dup_track", 124f, "8A")
    val t2 = TestTrack("other_track", 126f, "9A")
    val candidates = listOf(t1, t2, t1)

    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = toProfile(t1),
      candidates = candidates,
      profileProvider = ::toProfile,
    )

    assertEquals(3, chained.size)
    assertEquals(2, chained.count { it.id == "dup_track" })
    assertEquals(1, chained.count { it.id == "other_track" })
  }

  @Test
  fun testLookaheadLimitZeroBypassesReordering() {
    val candidates = (1..10).map { TestTrack("t_$it", 120f + it, "${(it % 12) + 1}A") }
    val chained = AutoRemixOrderingEngine.chainTracks(
      anchor = null,
      candidates = candidates,
      profileProvider = ::toProfile,
      lookaheadLimit = 0,
    )
    assertEquals(candidates.map { it.id }, chained.map { it.id })
  }
}

