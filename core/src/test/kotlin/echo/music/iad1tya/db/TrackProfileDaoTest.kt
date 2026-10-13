package echo.music.iad1tya.db

import androidx.room.Room
import echo.music.iad1tya.db.entities.TrackProfileEntity
import echo.music.iad1tya.db.entities.toDomain
import echo.music.iad1tya.db.entities.toEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackProfileDaoTest {
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
  fun testUpsertAndQueries() = runBlocking {
    val entity1 = TrackProfileEntity(
      songId = "song1",
      bpm = 128.0f,
      confidence = 0.95f,
      firstBeatOffsetMs = 120L,
      downbeatIntervalMs = 1875L,
      phraseBeatCount = 32,
      camelotKey = "8A",
      pitchClass = 9,
      isMinor = true,
      mixOutPointMs = 190_000L,
      mixInPointMs = 12_000L,
      analyzedAt = 1710000000L,
    )

    dao.upsert(entity1)

    // Direct query
    val direct = dao.getTrackProfileDirect("song1")
    assertNotNull(direct)
    assertEquals("song1", direct!!.songId)
    assertEquals(128.0f, direct.bpm, 0.001f)
    assertEquals("8A", direct.camelotKey)

    // Flow query
    val fromFlow = dao.getTrackProfile("song1").first()
    assertEquals(direct, fromFlow)

    // Domain mapping roundtrip
    val domain = entity1.toDomain()
    assertEquals("song1", domain.songId)
    assertEquals(128.0f, domain.bpm, 0.001f)
    assertEquals("8A", domain.camelotKey)
    val mappedEntity = domain.toEntity(analyzedAt = entity1.analyzedAt)
    assertEquals(entity1, mappedEntity)

    // Batch upsert & queries
    val entity2 = TrackProfileEntity(
      songId = "song2",
      bpm = 120.0f,
      confidence = 0.85f,
      firstBeatOffsetMs = 250L,
      downbeatIntervalMs = 2000L,
      phraseBeatCount = 32,
      camelotKey = "9A",
      pitchClass = 4,
      isMinor = true,
      analyzedAt = 1710000001L,
    )
    dao.upsert(entity2)

    assertEquals(2, dao.count())

    val batch = dao.getTrackProfiles(listOf("song1", "song2"))
    assertEquals(2, batch.size)

    val batchFlow = dao.observeTrackProfiles(listOf("song1")).first()
    assertEquals(1, batchFlow.size)
    assertEquals("song1", batchFlow[0].songId)

    // Delete
    dao.delete("song1")
    assertNull(dao.getTrackProfileDirect("song1"))
    assertEquals(1, dao.count())

    // Clear all
    dao.clearAll()
    assertEquals(0, dao.count())
  }
}
