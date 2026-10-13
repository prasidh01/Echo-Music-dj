package echo.music.domain.autodj.model

import kotlin.math.abs
import kotlin.math.min

enum class CamelotMode(val letter: Char) {
  A('A'),
  B('B');

  val isMinor: Boolean get() = this == A
  val isMajor: Boolean get() = this == B

  companion object {
    val MINOR: CamelotMode get() = A
    val MAJOR: CamelotMode get() = B

    fun fromLetter(letter: Char): CamelotMode? =
      entries.firstOrNull { it.letter.equals(letter, ignoreCase = true) }
  }
}

data class CamelotKey(
  val number: Int,
  val mode: CamelotMode,
) {
  init {
    require(number in 1..12) { "Camelot number must be in 1..12, was $number" }
  }

  val code: String get() = "$number${mode.letter}"

  override fun toString(): String = code

  /**
   * Shortest circular step distance on 12-hour Camelot wheel (0..6).
   */
  fun circularDistance(other: CamelotKey): Int {
    val diff = abs(this.number - other.number)
    return min(diff, 12 - diff)
  }

  fun wheelDistanceTo(other: CamelotKey): Int = circularDistance(other)

  fun isRelativeKey(other: CamelotKey): Boolean =
    number == other.number && mode != other.mode

  fun isSameKey(other: CamelotKey): Boolean =
    number == other.number && mode == other.mode

  fun isAdjacent(other: CamelotKey): Boolean =
    circularDistance(other) == 1 && mode == other.mode

  fun isEnergyBoost(other: CamelotKey): Boolean {
    if (mode != other.mode) return false
    val step = ((other.number - number) % 12 + 12) % 12
    return step == 2 || step == 7
  }

  /**
   * Evaluates harmonic compatibility score in range [0.0, 1.0].
   */
  fun harmonicCompatibilityScore(other: CamelotKey): Float {
    val deltaN = circularDistance(other)
    val sameMode = this.mode == other.mode
    val forwardStep = ((other.number - this.number) % 12 + 12) % 12

    return when {
      deltaN == 0 && sameMode -> 1.0f   // Identical key (8A -> 8A)
      deltaN == 0 && !sameMode -> 1.0f  // Relative major/minor (8A <-> 8B)
      deltaN == 1 && sameMode -> 0.9f   // Adjacent fifth (+/- 1 step on wheel, same mode)
      deltaN == 1 && !sameMode -> 0.75f // Diagonal shift (+/- 1 step on wheel, mode switch)
      sameMode && forwardStep == 7 -> 0.8f // Energy boost: +1 semitone modulation (+7 steps on wheel)
      sameMode && forwardStep == 2 -> 0.6f // Energy boost: +2 semitone modulation (+2 steps on wheel)
      else -> 0.2f                      // Harmonic clash
    }
  }

  fun toPitchClass(): Int {
    val majorPitch = (7 * number + 4) % 12
    return if (mode == CamelotMode.A) (majorPitch + 9) % 12 else majorPitch
  }

  fun toIsMinor(): Boolean = mode == CamelotMode.A

  companion object {
    private val CODE_REGEX = Regex("""^(\d{1,2})([aAbB])$""")

    fun parse(code: String?): CamelotKey? {
      if (code == null) return null
      val match = CODE_REGEX.matchEntire(code.trim()) ?: return null
      val number = match.groupValues[1].toIntOrNull() ?: return null
      if (number !in 1..12) return null
      val mode = CamelotMode.fromLetter(match.groupValues[2].first()) ?: return null
      return CamelotKey(number, mode)
    }

    /**
     * Maps pitch class (0=C, 1=C#, ..., 11=B) and mode to Camelot key.
     */
    fun fromPitchClass(pitchClass: Int, isMinor: Boolean): CamelotKey {
      require(pitchClass in 0..11) { "Pitch class must be 0..11, was $pitchClass" }
      val effectiveMajor = if (isMinor) (pitchClass + 3) % 12 else pitchClass
      val raw = (effectiveMajor * 7 + 8) % 12
      val num = if (raw == 0) 12 else raw
      val mode = if (isMinor) CamelotMode.A else CamelotMode.B
      return CamelotKey(num, mode)
    }
  }
}
