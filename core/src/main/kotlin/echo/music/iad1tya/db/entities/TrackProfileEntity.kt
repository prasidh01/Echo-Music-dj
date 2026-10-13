package echo.music.iad1tya.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import echo.music.domain.autodj.model.TrackProfile

@Entity(tableName = "track_profile")
data class TrackProfileEntity(
  @PrimaryKey val songId: String,
  val bpm: Float,
  val confidence: Float,
  val firstBeatOffsetMs: Long,
  val downbeatIntervalMs: Long,
  val phraseBeatCount: Int,
  val camelotKey: String? = null,
  val pitchClass: Int? = null,
  val isMinor: Boolean? = null,
  val mixOutPointMs: Long? = null,
  val mixInPointMs: Long? = null,
  val analyzedAt: Long = System.currentTimeMillis(),
)

fun TrackProfileEntity.toDomain(): TrackProfile =
  TrackProfile(
    songId = songId,
    bpm = bpm,
    confidence = confidence,
    firstBeatOffsetMs = firstBeatOffsetMs,
    downbeatIntervalMs = downbeatIntervalMs,
    phraseBeatCount = phraseBeatCount,
    camelotKey = camelotKey,
    pitchClass = pitchClass,
    isMinor = isMinor,
    mixOutPointMs = mixOutPointMs,
    mixInPointMs = mixInPointMs,
  )

fun TrackProfile.toEntity(analyzedAt: Long = System.currentTimeMillis()): TrackProfileEntity =
  TrackProfileEntity(
    songId = songId,
    bpm = bpm,
    confidence = confidence,
    firstBeatOffsetMs = firstBeatOffsetMs,
    downbeatIntervalMs = downbeatIntervalMs,
    phraseBeatCount = phraseBeatCount,
    camelotKey = camelotKey,
    pitchClass = pitchClass,
    isMinor = isMinor,
    mixOutPointMs = mixOutPointMs,
    mixInPointMs = mixInPointMs,
    analyzedAt = analyzedAt,
  )
