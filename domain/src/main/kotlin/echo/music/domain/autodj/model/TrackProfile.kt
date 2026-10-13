package echo.music.domain.autodj.model

data class TrackProfile(
  val songId: String,
  val bpm: Float,
  val confidence: Float = 1.0f,
  val firstBeatOffsetMs: Long = 0L,
  val downbeatIntervalMs: Long = if (bpm > 0f) ((60_000.0 / bpm) * 4.0).toLong() else 2000L,
  val phraseBeatCount: Int = 8,
  val camelotKey: String? = null,
  val pitchClass: Int? = null,
  val isMinor: Boolean? = null,
  val mixOutPointMs: Long? = null,
  val mixInPointMs: Long? = null,
  val durationMs: Long = 0L,
  val energyLevel: Float? = null,
) {
  constructor(
    songId: String,
    bpm: Float,
    confidence: Float = 1.0f,
    firstBeatOffsetMs: Long = 0L,
    downbeatIntervalMs: Long = if (bpm > 0f) ((60_000.0 / bpm) * 4.0).toLong() else 2000L,
    phraseBeatCount: Int = 8,
    camelotKey: CamelotKey?,
    pitchClass: Int? = camelotKey?.toPitchClass(),
    isMinor: Boolean? = camelotKey?.toIsMinor(),
    mixOutPointMs: Long? = null,
    mixInPointMs: Long? = null,
    durationMs: Long = 0L,
    energyLevel: Float? = null,
  ) : this(
    songId = songId,
    bpm = bpm,
    confidence = confidence,
    firstBeatOffsetMs = firstBeatOffsetMs,
    downbeatIntervalMs = downbeatIntervalMs,
    phraseBeatCount = phraseBeatCount,
    camelotKey = camelotKey?.code,
    pitchClass = pitchClass,
    isMinor = isMinor,
    mixOutPointMs = mixOutPointMs,
    mixInPointMs = mixInPointMs,
    durationMs = durationMs,
    energyLevel = energyLevel,
  )

  val parsedCamelotKey: CamelotKey?
    get() = camelotKey?.let { CamelotKey.parse(it) }
      ?: if (pitchClass != null && isMinor != null) CamelotKey.fromPitchClass(pitchClass, isMinor) else null

  val beatIntervalMs: Double get() = if (bpm > 0f) 60_000.0 / bpm else 0.0

  val hasValidAnalysis: Boolean get() = bpm > 0f && confidence > 0f
}
