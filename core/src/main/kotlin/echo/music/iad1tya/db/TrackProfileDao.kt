package echo.music.iad1tya.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import echo.music.iad1tya.db.entities.TrackProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackProfileDao {
  @Upsert
  suspend fun upsert(entity: TrackProfileEntity)

  @Upsert
  suspend fun upsertAll(entities: List<TrackProfileEntity>)

  @Query("SELECT * FROM track_profile WHERE songId = :songId")
  fun getTrackProfile(songId: String): Flow<TrackProfileEntity?>

  @Query("SELECT * FROM track_profile WHERE songId = :songId LIMIT 1")
  suspend fun getTrackProfileDirect(songId: String): TrackProfileEntity?

  @Query("SELECT * FROM track_profile WHERE songId IN (:songIds)")
  suspend fun getTrackProfiles(songIds: List<String>): List<TrackProfileEntity>

  @Query("SELECT * FROM track_profile WHERE songId IN (:songIds)")
  fun observeTrackProfiles(songIds: List<String>): Flow<List<TrackProfileEntity>>

  @Query("DELETE FROM track_profile WHERE songId = :songId")
  suspend fun delete(songId: String)

  @Query("DELETE FROM track_profile")
  suspend fun clearAll()

  @Query("SELECT COUNT(*) FROM track_profile")
  suspend fun count(): Int
}
