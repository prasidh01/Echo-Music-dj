package echo.music.iad1tya.db

import androidx.room.Room
import echo.music.iad1tya.db.entities.TrackProfileEntity
import echo.music.iad1tya.db.entities.toDomain
import echo.music.iad1tya.db.entities.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackProfileDaoStressTest {
  private lateinit var database: InternalDatabase
  private lateinit var dao: TrackProfileDao

  @Before
  fun setup() {
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      InternalDatabase::class.java,
    ).allowMainThreadQueries().build()
    dao = database.trackProfileDao
  }

  @After
  fun teardown() {
    database.close()
  }

  @Test
  fun testUpsertCollisionsAndSequentialOverwrites() = runBlocking {
    val songId = "sequential_collision"

    // Initial insert
    val initial = TrackProfileEntity(
      songId = songId,
      bpm = 120.0f,
      confidence = 0.5f,
      firstBeatOffsetMs = 100L,
      downbeatIntervalMs = 2000L,
      phraseBeatCount = 32,
      camelotKey = "1A",
      pitchClass = 8,
      isMinor = true,
      mixOutPointMs = null,
      mixInPointMs = null,
      analyzedAt = 1000L,
    )
    dao.upsert(initial)
    assertEquals(1, dao.count())
    assertEquals(120.0f, dao.getTrackProfileDirect(songId)?.bpm)

    // Sequential overwrites
    for (i in 1..20) {
      val updated = initial.copy(
        bpm = 120.0f + i,
        confidence = 0.5f + (i * 0.02f),
        camelotKey = "${(i % 12) + 1}B",
        analyzedAt = 1000L + i,
      )
      dao.upsert(updated)
      assertEquals(1, dao.count())
      val queried = dao.getTrackProfileDirect(songId)
      assertNotNull(queried)
      assertEquals(120.0f + i, queried!!.bpm, 0.001f)
      assertEquals(1000L + i, queried.analyzedAt)
      assertEquals("${(i % 12) + 1}B", queried.camelotKey)
    }
  }

  @Test
  fun testConcurrentUpsertsSameEntity() = runBlocking {
    val songId = "concurrent_single_song"
    val concurrency = 40

    // Concurrently upsert to the SAME entity
    val jobs = (1..concurrency).map { index ->
      async(Dispatchers.Default) {
        val entity = TrackProfileEntity(
          songId = songId,
          bpm = 100.0f + index,
          confidence = 0.9f,
          firstBeatOffsetMs = 200L,
          downbeatIntervalMs = 2000L,
          phraseBeatCount = 32,
          camelotKey = "${(index % 12) + 1}A",
          pitchClass = index % 12,
          isMinor = true,
          analyzedAt = 1700000000L + index,
        )
        dao.upsert(entity)
      }
    }

    jobs.awaitAll()

    // Exactly 1 row must exist in the database
    assertEquals(1, dao.count())
    val result = dao.getTrackProfileDirect(songId)
    assertNotNull(result)
    assertTrue(result!!.bpm in 101.0f..(100.0f + concurrency))
  }

  @Test
  fun testConcurrentWritesDistinctEntities() = runBlocking {
    val count = 100

    val jobs = (1..count).map { index ->
      async(Dispatchers.Default) {
        val entity = TrackProfileEntity(
          songId = "concurrent_song_$index",
          bpm = 128.0f,
          confidence = 0.88f,
          firstBeatOffsetMs = (index * 10).toLong(),
          downbeatIntervalMs = 1875L,
          phraseBeatCount = 32,
          camelotKey = "8A",
          pitchClass = 9,
          isMinor = true,
          analyzedAt = 1700000000L + index,
        )
        dao.upsert(entity)
      }
    }

    jobs.awaitAll()

    assertEquals(count, dao.count())

    // Verify all can be retrieved
    val allIds = (1..count).map { "concurrent_song_$it" }
    val retrieved = dao.getTrackProfiles(allIds)
    assertEquals(count, retrieved.size)
  }

  @Test
  fun testLargeBatchUpsertAndDuplicateKeysInBatch() = runBlocking {
    val batchSize = 1500
    val entities = (1..batchSize).map { index ->
      TrackProfileEntity(
        songId = "batch_song_$index",
        bpm = 120.0f + (index % 40),
        confidence = 0.95f,
        firstBeatOffsetMs = 100L,
        downbeatIntervalMs = 2000L,
        phraseBeatCount = 32,
        camelotKey = "11B",
        pitchClass = 4,
        isMinor = false,
        analyzedAt = 1710000000L + index,
      )
    }

    dao.upsertAll(entities)
    assertEquals(batchSize, dao.count())

    // Test in-batch primary key collisions
    val collidingEntities = listOf(
      TrackProfileEntity(
        songId = "in_batch_collision",
        bpm = 100.0f,
        confidence = 0.5f,
        firstBeatOffsetMs = 0L,
        downbeatIntervalMs = 2400L,
        phraseBeatCount = 32,
      ),
      TrackProfileEntity(
        songId = "in_batch_collision",
        bpm = 140.0f,
        confidence = 0.99f,
        firstBeatOffsetMs = 50L,
        downbeatIntervalMs = 1714L,
        phraseBeatCount = 16,
      ),
    )

    dao.upsertAll(collidingEntities)
    assertEquals(batchSize + 1, dao.count())
    val collided = dao.getTrackProfileDirect("in_batch_collision")
    assertNotNull(collided)
    assertEquals(140.0f, collided!!.bpm, 0.01f)
    assertEquals(16, collided.phraseBeatCount)
  }

  @Test
  fun testParameterLimitQueryStress() = runBlocking {
    val count = 1200
    val entities = (1..count).map { index ->
      TrackProfileEntity(
        songId = "param_stress_$index",
        bpm = 125.0f,
        confidence = 0.8f,
        firstBeatOffsetMs = 150L,
        downbeatIntervalMs = 1920L,
        phraseBeatCount = 32,
      )
    }
    dao.upsertAll(entities)
    assertEquals(count, dao.count())

    // 1. Safe query within SQLite standard 999 variable limit succeeds
    val safeIds = (1..999).map { "param_stress_$it" }
    val results = dao.getTrackProfiles(safeIds)
    assertEquals(999, results.size)

    val safeFlow = dao.observeTrackProfiles(safeIds).first()
    assertEquals(999, safeFlow.size)

    // 2. Empty list queries succeed
    val emptyResult = dao.getTrackProfiles(emptyList())
    assertEquals(0, emptyResult.size)

    val emptyFlow = dao.observeTrackProfiles(emptyList()).first()
    assertEquals(0, emptyFlow.size)

    // 3. Documented SQLite vulnerability: unchunked IN (:songIds) with > 999 variables throws SQLiteException
    val excessiveIds = (1..1000).map { "param_stress_$it" }
    try {
      dao.getTrackProfiles(excessiveIds)
      org.junit.Assert.fail("Expected SQLiteException due to SQLITE_MAX_VARIABLE_NUMBER (999) limit")
    } catch (e: android.database.sqlite.SQLiteException) {
      assertTrue(e.message?.contains("too many SQL variables") == true)
    }
  }

  @Test
  fun testBoundaryValuesAndExtremeFloats() = runBlocking {
    val extremeEntity = TrackProfileEntity(
      songId = "extreme_values_🎵_#&",
      bpm = Float.MAX_VALUE,
      confidence = Float.MIN_VALUE,
      firstBeatOffsetMs = Long.MAX_VALUE,
      downbeatIntervalMs = Long.MIN_VALUE,
      phraseBeatCount = Int.MAX_VALUE,
      camelotKey = "12B",
      pitchClass = 11,
      isMinor = false,
      mixOutPointMs = Long.MAX_VALUE,
      mixInPointMs = 0L,
      analyzedAt = Long.MAX_VALUE,
    )

    dao.upsert(extremeEntity)

    val retrieved = dao.getTrackProfileDirect(extremeEntity.songId)
    assertNotNull(retrieved)
    assertEquals(Float.MAX_VALUE, retrieved!!.bpm)
    assertEquals(Float.MIN_VALUE, retrieved.confidence)
    assertEquals(Long.MAX_VALUE, retrieved.firstBeatOffsetMs)
    assertEquals(Long.MIN_VALUE, retrieved.downbeatIntervalMs)
    assertEquals(Int.MAX_VALUE, retrieved.phraseBeatCount)
    assertEquals("12B", retrieved.camelotKey)
    assertEquals(11, retrieved.pitchClass)
    assertEquals(false, retrieved.isMinor)
    assertEquals(Long.MAX_VALUE, retrieved.mixOutPointMs)
    assertEquals(0L, retrieved.mixInPointMs)

    // Domain mapper roundtrip
    val domain = retrieved.toDomain()
    val mappedBack = domain.toEntity(analyzedAt = retrieved.analyzedAt)
    assertEquals(retrieved, mappedBack)
  }

  @Test
  fun testDeletionAndClearAllEdgeCases() = runBlocking {
    // Delete non-existent ID
    dao.delete("non_existent_id")
    assertEquals(0, dao.count())

    // Clear empty table
    dao.clearAll()
    assertEquals(0, dao.count())

    // Upsert items, delete one, clear all
    dao.upsert(
      TrackProfileEntity(
        songId = "item_1",
        bpm = 120f,
        confidence = 0.8f,
        firstBeatOffsetMs = 0L,
        downbeatIntervalMs = 2000L,
        phraseBeatCount = 32,
      )
    )
    dao.upsert(
      TrackProfileEntity(
        songId = "item_2",
        bpm = 130f,
        confidence = 0.8f,
        firstBeatOffsetMs = 0L,
        downbeatIntervalMs = 1846L,
        phraseBeatCount = 32,
      )
    )
    assertEquals(2, dao.count())

    dao.delete("item_1")
    assertEquals(1, dao.count())
    assertNull(dao.getTrackProfileDirect("item_1"))
    assertNotNull(dao.getTrackProfileDirect("item_2"))

    dao.clearAll()
    assertEquals(0, dao.count())
  }
}
