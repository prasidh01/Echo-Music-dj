package echo.music.iad1tya.generate

import androidx.datastore.preferences.core.mutablePreferencesOf
import echo.music.iad1tya.constants.LastFMUsernameKey
import echo.music.iad1tya.db.entities.RecommendationExclusionEntity
import echo.music.iad1tya.db.entities.SongPlayStatsEntity
import kotlinx.coroutines.test.runTest
import com.music.innertube.models.SongItem
import echo.music.domain.autodj.model.CamelotKey
import echo.music.domain.autodj.model.TrackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecommendationEngineTest {
  private lateinit var fakeStatsDao: FakeSongPlayStatsDao
  private lateinit var fakeExclusionDao: FakeRecommendationExclusionDao
  private lateinit var fakeTasteDao: FakeTasteProfileDao
  private lateinit var fakeApi: FakeLastFmTasteApi
  private lateinit var fakeDataStore: FakePreferencesDataStore
  private lateinit var tasteProfileProvider: TasteProfileProvider
  private lateinit var engine: RecommendationEngine

  @Before
  fun setup() {
    fakeStatsDao = FakeSongPlayStatsDao()
    fakeExclusionDao = FakeRecommendationExclusionDao()
    fakeTasteDao = FakeTasteProfileDao()
    fakeApi = FakeLastFmTasteApi()
    fakeDataStore = FakePreferencesDataStore(mutablePreferencesOf(LastFMUsernameKey to "test_user"))
    tasteProfileProvider = TasteProfileProvider(fakeTasteDao, fakeApi, fakeDataStore, fakeStatsDao)
    engine = RecommendationEngine(
      songPlayStatsDao = fakeStatsDao,
      recommendationExclusionDao = fakeExclusionDao,
      tasteProfileProvider = tasteProfileProvider,
      lastFmTasteApi = fakeApi,
      database = null,
    ).apply {
      allowNullDatabaseForTesting = true
    }
  }

  @Test
  fun testNullDatabaseFailsWhenNotTestingAllowed() = runTest {
    fakeStatsDao.upsert(
      SongPlayStatsEntity(
        trackKey = "blinding lights|the weeknd",
        title = "Blinding Lights",
        artist = "The Weeknd",
        videoId = "seed_vid",
        totalPlayTimeMs = 300_000L,
        playCount = 10,
        skipCount = 0,
        lastPlayedAtMillis = System.currentTimeMillis(),
      )
    )

    val strictEngine = RecommendationEngine(
      songPlayStatsDao = fakeStatsDao,
      recommendationExclusionDao = fakeExclusionDao,
      tasteProfileProvider = tasteProfileProvider,
      lastFmTasteApi = fakeApi,
      database = null,
    )
    strictEngine.allowNullDatabaseForTesting = false

    val result = strictEngine.generate(10)
    assertTrue("Null database must fail when not explicitly allowed", result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("Database not available") == true)
  }

  @Test
  fun testEmptyHistoryFailsGracefully() = runTest {
    val result = engine.generate(20)
    assertTrue("Empty history should fail", result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("No listening history") == true)
  }

  @Test
  fun testAllExcludedFailsGracefully() = runTest {
    fakeStatsDao.upsert(
      SongPlayStatsEntity(
        trackKey = "bad song|bad artist",
        title = "Bad Song",
        artist = "Bad Artist",
        videoId = "vid1",
        totalPlayTimeMs = 120_000L,
        playCount = 5,
        skipCount = 0,
        lastPlayedAtMillis = System.currentTimeMillis(),
      )
    )
    fakeExclusionDao.upsert(
      RecommendationExclusionEntity(
        trackKey = "bad song|bad artist",
        excludedAtMillis = System.currentTimeMillis(),
        trackName = "Bad Song",
        artistName = "Bad Artist",
      )
    )

    val result = engine.generate(20)
    assertTrue("All excluded should fail", result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("All candidate tracks have been excluded") == true)
  }

  @Test
  fun testRecommendationSucceedsWithLocalSeedsAndLastFmSignals() = runTest {
    fakeStatsDao.upsert(
      SongPlayStatsEntity(
        trackKey = "blinding lights|the weeknd",
        title = "Blinding Lights",
        artist = "The Weeknd",
        videoId = "seed_vid",
        totalPlayTimeMs = 300_000L,
        playCount = 10,
        skipCount = 0,
        lastPlayedAtMillis = System.currentTimeMillis(),
      )
    )

    val result = engine.generate(10)
    assertTrue("Generation should succeed", result.isSuccess)
    val playlistId = result.getOrNull()
    assertTrue("PlaylistId should start with local_taste_", playlistId?.startsWith("local_taste_") == true)
  }

  @Test
  fun testRecommendationDegradesToLocalOnlyOnLastFmFailure() = runTest {
    fakeStatsDao.upsert(
      SongPlayStatsEntity(
        trackKey = "blinding lights|the weeknd",
        title = "Blinding Lights",
        artist = "The Weeknd",
        videoId = "seed_vid",
        totalPlayTimeMs = 300_000L,
        playCount = 10,
        skipCount = 0,
        lastPlayedAtMillis = System.currentTimeMillis(),
      )
    )

    fakeApi.shouldFail = true

    val result = engine.generate(10)
    assertTrue("Generation should succeed even when Last.fm fails", result.isSuccess)
    val playlistId = result.getOrNull()
    assertTrue("PlaylistId should start with local_taste_", playlistId?.startsWith("local_taste_") == true)
  }

  @Test
  fun testRankAndChainForAutoDj() {
    val track1 = SongItem(id = "track_1", title = "Track 1", artists = emptyList(), thumbnail = "")
    val track2 = SongItem(id = "track_2", title = "Track 2", artists = emptyList(), thumbnail = "")
    val track3 = SongItem(id = "track_3", title = "Track 3", artists = emptyList(), thumbnail = "")

    val profile1 = TrackProfile(songId = "track_1", bpm = 120f, camelotKey = "8B")
    val profile2 = TrackProfile(songId = "track_2", bpm = 160f, camelotKey = "2A")
    val profile3 = TrackProfile(songId = "track_3", bpm = 122f, camelotKey = "8B")

    val profiles = mapOf(
      "track_1" to profile1,
      "track_2" to profile2,
      "track_3" to profile3,
    )

    val ranked = engine.rankForAutoDj(listOf(track2, track3), anchor = profile1, profiles = profiles)
    assertEquals("track_3", ranked.first().id)

    val chained = engine.chainForAutoDj(listOf(track1, track2, track3), anchor = profile1, profiles = profiles)
    assertEquals(3, chained.size)
    assertEquals("track_1", chained[0].id)
    assertEquals("track_3", chained[1].id)
    assertEquals("track_2", chained[2].id)
  }
}
