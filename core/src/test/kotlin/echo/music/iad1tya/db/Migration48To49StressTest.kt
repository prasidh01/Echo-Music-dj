package echo.music.iad1tya.db

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration48To49StressTest {
  private lateinit var db: SQLiteDatabase

  @Before
  fun setup() {
    db = SQLiteDatabase.create(null)
  }

  @After
  fun teardown() {
    db.close()
  }

  private fun createBeatInfoTable() {
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
  }

  @Test
  fun testMigrationWithEmptyBeatInfoTable() {
    createBeatInfoTable()

    // Execute migration with 0 rows
    MIGRATION_48_49.migrate(db.toSupportSQLiteDatabase())

    // Verify track_profile table exists and count is 0
    val cursor = db.rawQuery("SELECT COUNT(*) FROM track_profile", null)
    assertTrue(cursor.moveToFirst())
    assertEquals(0, cursor.getInt(0))
    cursor.close()
  }

  @Test
  fun testMigrationWhenBeatInfoTableDoesNotExist() {
    // Migration should not crash when beat_info table is absent (e.g. clean install or dropped)
    MIGRATION_48_49.migrate(db.toSupportSQLiteDatabase())

    // track_profile table should still be created
    val cursor = db.rawQuery("SELECT COUNT(*) FROM track_profile", null)
    assertTrue(cursor.moveToFirst())
    assertEquals(0, cursor.getInt(0))
    cursor.close()
  }

  @Test
  fun testMigrationWithNullFieldsAndBoundaryBpm() {
    createBeatInfoTable()

    val insertStmt = db.compileStatement(
      """
      INSERT INTO beat_info (songId, bpm, firstBeatOffsetMs, confidence, analyzedAt, mixInPointMs, mixOutPointMs, keyPitchClass, keyIsMinor)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    )

    // 1. All nullable fields null
    insertStmt.bindString(1, "all_nulls")
    insertStmt.bindDouble(2, 120.0)
    insertStmt.bindLong(3, 100L)
    insertStmt.bindDouble(4, 0.9)
    insertStmt.bindLong(5, 1700000000L)
    insertStmt.bindNull(6)
    insertStmt.bindNull(7)
    insertStmt.bindNull(8)
    insertStmt.bindNull(9)
    insertStmt.executeInsert()

    // 2. Zero BPM
    insertStmt.bindString(1, "zero_bpm")
    insertStmt.bindDouble(2, 0.0)
    insertStmt.bindLong(3, 0L)
    insertStmt.bindDouble(4, 0.1)
    insertStmt.bindLong(5, 1700000001L)
    insertStmt.bindNull(6)
    insertStmt.bindNull(7)
    insertStmt.bindNull(8)
    insertStmt.bindNull(9)
    insertStmt.executeInsert()

    // 3. Negative BPM
    insertStmt.bindString(1, "neg_bpm")
    insertStmt.bindDouble(2, -120.0)
    insertStmt.bindLong(3, 0L)
    insertStmt.bindDouble(4, 0.0)
    insertStmt.bindLong(5, 1700000002L)
    insertStmt.bindNull(6)
    insertStmt.bindNull(7)
    insertStmt.bindNull(8)
    insertStmt.bindNull(9)
    insertStmt.executeInsert()

    // 4. High BPM (300 BPM -> 240000 / 300 = 800)
    insertStmt.bindString(1, "high_bpm")
    insertStmt.bindDouble(2, 300.0)
    insertStmt.bindLong(3, 50L)
    insertStmt.bindDouble(4, 0.99)
    insertStmt.bindLong(5, 1700000003L)
    insertStmt.bindLong(6, 10000L)
    insertStmt.bindLong(7, 180000L)
    insertStmt.bindLong(8, 11L)
    insertStmt.bindLong(9, 0L)
    insertStmt.executeInsert()

    // 5. Special chars and unicode in songId
    val specialId = "song's quote / 🎵 #1"
    insertStmt.bindString(1, specialId)
    insertStmt.bindDouble(2, 128.0)
    insertStmt.bindLong(3, 200L)
    insertStmt.bindDouble(4, 0.85)
    insertStmt.bindLong(5, 1700000004L)
    insertStmt.bindNull(6)
    insertStmt.bindNull(7)
    insertStmt.bindNull(8)
    insertStmt.bindNull(9)
    insertStmt.executeInsert()

    // Execute migration
    MIGRATION_48_49.migrate(db.toSupportSQLiteDatabase())

    // Verify all_nulls
    db.rawQuery("SELECT * FROM track_profile WHERE songId = 'all_nulls'", null).use { c ->
      assertTrue(c.moveToFirst())
      assertTrue(c.isNull(c.getColumnIndexOrThrow("mixInPointMs")))
      assertTrue(c.isNull(c.getColumnIndexOrThrow("mixOutPointMs")))
      assertTrue(c.isNull(c.getColumnIndexOrThrow("pitchClass")))
      assertTrue(c.isNull(c.getColumnIndexOrThrow("isMinor")))
      assertTrue(c.isNull(c.getColumnIndexOrThrow("camelotKey")))
      assertEquals(2000L, c.getLong(c.getColumnIndexOrThrow("downbeatIntervalMs")))
      assertEquals(32, c.getInt(c.getColumnIndexOrThrow("phraseBeatCount")))
    }

    // Verify zero_bpm fallback downbeat interval
    db.rawQuery("SELECT * FROM track_profile WHERE songId = 'zero_bpm'", null).use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(2000L, c.getLong(c.getColumnIndexOrThrow("downbeatIntervalMs")))
    }

    // Verify neg_bpm fallback downbeat interval
    db.rawQuery("SELECT * FROM track_profile WHERE songId = 'neg_bpm'", null).use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(2000L, c.getLong(c.getColumnIndexOrThrow("downbeatIntervalMs")))
    }

    // Verify high_bpm calculation
    db.rawQuery("SELECT * FROM track_profile WHERE songId = 'high_bpm'", null).use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(800L, c.getLong(c.getColumnIndexOrThrow("downbeatIntervalMs")))
      assertEquals(10000L, c.getLong(c.getColumnIndexOrThrow("mixInPointMs")))
      assertEquals(180000L, c.getLong(c.getColumnIndexOrThrow("mixOutPointMs")))
      assertEquals(11, c.getInt(c.getColumnIndexOrThrow("pitchClass")))
      assertEquals(0, c.getInt(c.getColumnIndexOrThrow("isMinor")))
    }

    // Verify special ID
    db.rawQuery("SELECT * FROM track_profile WHERE songId = ?", arrayOf(specialId)).use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(specialId, c.getString(c.getColumnIndexOrThrow("songId")))
    }
  }

  @Test
  fun testMigrationConflictHandlingWithPreExistingTrackProfile() {
    createBeatInfoTable()

    // Pre-create track_profile table with existing record
    db.execSQL(
      """
      CREATE TABLE track_profile (
        songId TEXT NOT NULL PRIMARY KEY,
        bpm REAL NOT NULL,
        confidence REAL NOT NULL,
        firstBeatOffsetMs INTEGER NOT NULL,
        downbeatIntervalMs INTEGER NOT NULL,
        phraseBeatCount INTEGER NOT NULL,
        camelotKey TEXT,
        pitchClass INTEGER,
        isMinor INTEGER,
        mixOutPointMs INTEGER,
        mixInPointMs INTEGER,
        analyzedAt INTEGER NOT NULL
      )
      """.trimIndent(),
    )

    db.execSQL(
      """
      INSERT INTO track_profile (songId, bpm, confidence, firstBeatOffsetMs, downbeatIntervalMs, phraseBeatCount, camelotKey, pitchClass, isMinor, mixOutPointMs, mixInPointMs, analyzedAt)
      VALUES ('collision_track', 100.0, 0.99, 50, 2400, 32, '4A', 9, 1, 100000, 5000, 1800000000)
      """.trimIndent(),
    )

    // Also put collision_track in beat_info with differing data
    db.execSQL(
      """
      INSERT INTO beat_info (songId, bpm, firstBeatOffsetMs, confidence, analyzedAt, mixInPointMs, mixOutPointMs, keyPitchClass, keyIsMinor)
      VALUES ('collision_track', 140.0, 200, 0.50, 1600000000, NULL, NULL, NULL, NULL)
      """.trimIndent(),
    )

    db.execSQL(
      """
      INSERT INTO beat_info (songId, bpm, firstBeatOffsetMs, confidence, analyzedAt, mixInPointMs, mixOutPointMs, keyPitchClass, keyIsMinor)
      VALUES ('fresh_track', 125.0, 100, 0.90, 1700000000, NULL, NULL, NULL, NULL)
      """.trimIndent(),
    )

    // Execute migration
    MIGRATION_48_49.migrate(db.toSupportSQLiteDatabase())

    // Verify INSERT OR IGNORE preserved the pre-existing track_profile record
    db.rawQuery("SELECT * FROM track_profile WHERE songId = 'collision_track'", null).use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(100.0f, c.getFloat(c.getColumnIndexOrThrow("bpm")), 0.01f)
      assertEquals("4A", c.getString(c.getColumnIndexOrThrow("camelotKey")))
      assertEquals(1800000000L, c.getLong(c.getColumnIndexOrThrow("analyzedAt")))
    }

    // Verify fresh_track was migrated
    db.rawQuery("SELECT * FROM track_profile WHERE songId = 'fresh_track'", null).use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(125.0f, c.getFloat(c.getColumnIndexOrThrow("bpm")), 0.01f)
    }
  }

  @Test
  fun testMigrationLargeVolumeStress() {
    createBeatInfoTable()

    val count = 5_000
    db.beginTransaction()
    try {
      val stmt = db.compileStatement(
        """
        INSERT INTO beat_info (songId, bpm, firstBeatOffsetMs, confidence, analyzedAt, mixInPointMs, mixOutPointMs, keyPitchClass, keyIsMinor)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      )
      for (i in 1..count) {
        stmt.bindString(1, "stress_song_$i")
        stmt.bindDouble(2, 60.0 + (i % 120))
        stmt.bindLong(3, (i % 500).toLong())
        stmt.bindDouble(4, 0.8)
        stmt.bindLong(5, 1700000000L + i)
        if (i % 2 == 0) stmt.bindLong(6, 10000L) else stmt.bindNull(6)
        if (i % 2 == 0) stmt.bindLong(7, 180000L) else stmt.bindNull(7)
        stmt.bindLong(8, (i % 12).toLong())
        stmt.bindLong(9, (i % 2).toLong())
        stmt.executeInsert()
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }

    val startTime = System.currentTimeMillis()
    MIGRATION_48_49.migrate(db.toSupportSQLiteDatabase())
    val elapsed = System.currentTimeMillis() - startTime

    // Verify total count in track_profile
    db.rawQuery("SELECT COUNT(*) FROM track_profile", null).use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(count, c.getInt(0))
    }

    // Spot-check row 2500
    db.rawQuery("SELECT * FROM track_profile WHERE songId = 'stress_song_2500'", null).use { c ->
      assertTrue(c.moveToFirst())
      val expectedBpm = (60.0 + (2500 % 120)).toFloat()
      assertEquals(expectedBpm, c.getFloat(c.getColumnIndexOrThrow("bpm")), 0.01f)
      val expectedDownbeat = (240000.0 / expectedBpm).toLong()
      assertEquals(expectedDownbeat, c.getLong(c.getColumnIndexOrThrow("downbeatIntervalMs")))
      assertEquals(10000L, c.getLong(c.getColumnIndexOrThrow("mixInPointMs")))
      assertEquals(180000L, c.getLong(c.getColumnIndexOrThrow("mixOutPointMs")))
    }

    println("Migrated $count records in $elapsed ms")
  }
}
