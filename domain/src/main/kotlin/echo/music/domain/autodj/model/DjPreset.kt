package echo.music.domain.autodj.model

enum class DjPreset(
  val displayName: String,
  val minOverlapMs: Long,
  val maxOverlapMs: Long,
  val defaultOverlapBeats: Int,
  val confidenceThreshold: Float,
  val allowPitchCorrection: Boolean,
  val maxPitchShiftSemitones: Int,
  val bassDuckingDb: Float,
  val fallbackCrossfadeMs: Long,
) {
  /**
   * Smooth, extended harmonic crossfade with 16-32 beat blend and low-shelf bass ducking.
   */
  SEAMLESS(
    displayName = "Seamless",
    minOverlapMs = 6_000L,
    maxOverlapMs = 16_000L,
    defaultOverlapBeats = 16,
    confidenceThreshold = 0.35f,
    allowPitchCorrection = true,
    maxPitchShiftSemitones = 2,
    bassDuckingDb = -10.0f,
    fallbackCrossfadeMs = 6_000L,
  ),

  /**
   * Fast, punchy blend (8-16 beats) focused on downbeat drop swaps and heavy bass clearing.
   */
  CLUB(
    displayName = "Club",
    minOverlapMs = 4_000L,
    maxOverlapMs = 10_000L,
    defaultOverlapBeats = 8,
    confidenceThreshold = 0.40f,
    allowPitchCorrection = false,
    maxPitchShiftSemitones = 0,
    bassDuckingDb = -14.0f,
    fallbackCrossfadeMs = 4_000L,
  ),

  /**
   * Allows adventurous transitions: energy boost key modulation (+1 semitone) and longer intros.
   */
  CREATIVE(
    displayName = "Creative",
    minOverlapMs = 8_000L,
    maxOverlapMs = 24_000L,
    defaultOverlapBeats = 32,
    confidenceThreshold = 0.30f,
    allowPitchCorrection = true,
    maxPitchShiftSemitones = 3,
    bassDuckingDb = -8.0f,
    fallbackCrossfadeMs = 8_000L,
  ),

  /**
   * Conservative safety mode: short blends (4-8 beats), strictly no pitch stretching, safe fallback.
   */
  CONSERVATIVE(
    displayName = "Conservative",
    minOverlapMs = 3_000L,
    maxOverlapMs = 6_000L,
    defaultOverlapBeats = 8,
    confidenceThreshold = 0.45f,
    allowPitchCorrection = false,
    maxPitchShiftSemitones = 0,
    bassDuckingDb = -6.0f,
    fallbackCrossfadeMs = 3_000L,
  ),

  /**
   * Standard balanced profile matching Seamless.
   */
  STANDARD(
    displayName = "Standard",
    minOverlapMs = 6_000L,
    maxOverlapMs = 16_000L,
    defaultOverlapBeats = 16,
    confidenceThreshold = 0.35f,
    allowPitchCorrection = true,
    maxPitchShiftSemitones = 2,
    bassDuckingDb = -10.0f,
    fallbackCrossfadeMs = 6_000L,
  ),

  /**
   * Aggressive drop swap matching Club.
   */
  AGGRESSIVE(
    displayName = "Aggressive",
    minOverlapMs = 4_000L,
    maxOverlapMs = 10_000L,
    defaultOverlapBeats = 8,
    confidenceThreshold = 0.40f,
    allowPitchCorrection = false,
    maxPitchShiftSemitones = 0,
    bassDuckingDb = -14.0f,
    fallbackCrossfadeMs = 4_000L,
  ),

  /**
   * Smooth blend matching Seamless.
   */
  SMOOTH(
    displayName = "Smooth",
    minOverlapMs = 6_000L,
    maxOverlapMs = 16_000L,
    defaultOverlapBeats = 16,
    confidenceThreshold = 0.35f,
    allowPitchCorrection = true,
    maxPitchShiftSemitones = 2,
    bassDuckingDb = -10.0f,
    fallbackCrossfadeMs = 6_000L,
  ),

  /**
   * KeyLock mode strictly preventing pitch alteration matching Conservative.
   */
  KEY_LOCK(
    displayName = "KeyLock",
    minOverlapMs = 3_000L,
    maxOverlapMs = 6_000L,
    defaultOverlapBeats = 8,
    confidenceThreshold = 0.45f,
    allowPitchCorrection = false,
    maxPitchShiftSemitones = 0,
    bassDuckingDb = -6.0f,
    fallbackCrossfadeMs = 3_000L,
  ),

  /**
   * Custom user-defined tolerance and parameters.
   */
  CUSTOM(
    displayName = "Custom",
    minOverlapMs = 4_000L,
    maxOverlapMs = 16_000L,
    defaultOverlapBeats = 16,
    confidenceThreshold = 0.35f,
    allowPitchCorrection = true,
    maxPitchShiftSemitones = 2,
    bassDuckingDb = -10.0f,
    fallbackCrossfadeMs = 5_000L,
  );

  companion object {
    fun fromName(name: String?, default: DjPreset = SEAMLESS): DjPreset {
      if (name.isNullOrBlank()) return default
      val normalized = name.trim().uppercase()
      return when (normalized) {
        "SEAMLESS" -> SEAMLESS
        "CLUB" -> CLUB
        "CREATIVE" -> CREATIVE
        "CONSERVATIVE" -> CONSERVATIVE
        "STANDARD" -> STANDARD
        "AGGRESSIVE" -> AGGRESSIVE
        "SMOOTH" -> SMOOTH
        "KEYLOCK", "KEY_LOCK" -> KEY_LOCK
        "CUSTOM" -> CUSTOM
        else -> try {
          valueOf(normalized)
        } catch (e: IllegalArgumentException) {
          default
        }
      }
    }

    fun fromString(name: String?): DjPreset = fromName(name)
  }
}
