package echo.music.iad1tya.db

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration48To49Test {
  private lateinit var db: SQLiteDatabase

  @Before
  fun setup() {
    db = SQLiteDatabase.create(null)

    // Pre-populate v48 schema with beat_info table
    db.execSQL(
      """
      CREATE TABLE beat_info (
        songId TEXT NOT NULL PRIMARY KEY,
        bpm REAL NOT NULL,
        firstBeatOffsetMs INTEGER NOT NULL,
        confidence REAL NOT NULL,
        analyzedAt INTEGER NOT NULL,
        mixInPointMs INTEGER,
        mixOutPointMs INTEGER,
        keyPitchClass INTEGER,
        keyIsMinor INTEGER
      )
      """.trimIndent(),
    )

    // Insert sample v48 beat_info records
    db.execSQL(
      """
      INSERT INTO beat_info (songId, bpm, firstBeatOffsetMs, confidence, analyzedAt, mixInPointMs, mixOutPointMs, keyPitchClass, keyIsMinor)
      VALUES ('track_120', 120.0, 250, 0.88, 1700000000, 15000, 185000, 0, 1)
      """.trimIndent(),
    )

    db.execSQL(
      """
      INSERT INTO beat_info (songId, bpm, firstBeatOffsetMs, confidence, analyzedAt, mixInPointMs, mixOutPointMs, keyPitchClass, keyIsMinor)
      VALUES ('track_amb', 0.0, 0, 0.20, 1700000000, NULL, NULL, NULL, NULL)
      """.trimIndent(),
    )
  }

  @After
  fun teardown() {
    db.close()
  }

  @Test
  fun testMigrationCreatesTrackProfileAndCopiesBeatInfoData() {
    // Execute migration 48 -> 49
    MIGRATION_48_49.migrate(db.toSupportSQLiteDatabase())

    // 1. Verify track_120 migrated accurately
    val cursor120 = db.rawQuery("SELECT * FROM track_profile WHERE songId = 'track_120'", null)
    assertTrue("track_120 should exist in track_profile", cursor120.moveToFirst())
    assertEquals(120.0f, cursor120.getFloat(cursor120.getColumnIndexOrThrow("bpm")), 0.01f)
    assertEquals(0.88f, cursor120.getFloat(cursor120.getColumnIndexOrThrow("confidence")), 0.01f)
    assertEquals(250L, cursor120.getLong(cursor120.getColumnIndexOrThrow("firstBeatOffsetMs")))
    assertEquals(2000L, cursor120.getLong(cursor120.getColumnIndexOrThrow("downbeatIntervalMs"))) // 240000 / 120 = 2000
    assertEquals(32, cursor120.getInt(cursor120.getColumnIndexOrThrow("phraseBeatCount")))
    assertNull(cursor120.getString(cursor120.getColumnIndexOrThrow("camelotKey")))
    assertEquals(0, cursor120.getInt(cursor120.getColumnIndexOrThrow("pitchClass")))
    assertEquals(1, cursor120.getInt(cursor120.getColumnIndexOrThrow("isMinor")))
    assertEquals(15000L, cursor120.getLong(cursor120.getColumnIndexOrThrow("mixInPointMs")))
    assertEquals(185000L, cursor120.getLong(cursor120.getColumnIndexOrThrow("mixOutPointMs")))
    assertEquals(1700000000L, cursor120.getLong(cursor120.getColumnIndexOrThrow("analyzedAt")))
    cursor120.close()

    // 2. Verify track_amb migrated with fallback downbeat interval
    val cursorAmb = db.rawQuery("SELECT * FROM track_profile WHERE songId = 'track_amb'", null)
    assertTrue("track_amb should exist in track_profile", cursorAmb.moveToFirst())
    assertEquals(2000L, cursorAmb.getLong(cursorAmb.getColumnIndexOrThrow("downbeatIntervalMs")))
    assertTrue(cursorAmb.isNull(cursorAmb.getColumnIndexOrThrow("pitchClass")))
    assertTrue(cursorAmb.isNull(cursorAmb.getColumnIndexOrThrow("isMinor")))
    cursorAmb.close()

    // 3. Verify new record with camelotKey can be inserted into track_profile
    db.execSQL(
      """
      INSERT INTO track_profile (songId, bpm, confidence, firstBeatOffsetMs, downbeatIntervalMs, phraseBeatCount, camelotKey, pitchClass, isMinor, mixOutPointMs, mixInPointMs, analyzedAt)
      VALUES ('new_dj_track', 128.0, 0.95, 120, 1875, 32, '8A', 9, 1, 200000, 10000, 1710000000)
      """.trimIndent(),
    )

    val cursorNew = db.rawQuery("SELECT * FROM track_profile WHERE songId = 'new_dj_track'", null)
    assertTrue(cursorNew.moveToFirst())
    assertEquals("8A", cursorNew.getString(cursorNew.getColumnIndexOrThrow("camelotKey")))
    assertEquals(1875L, cursorNew.getLong(cursorNew.getColumnIndexOrThrow("downbeatIntervalMs")))
    cursorNew.close()
  }
}
