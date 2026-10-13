package echo.music.dsp.autodj

import kotlin.math.abs
import kotlin.math.min

data class HarmonicResult(
  val distance: Int,
  val score: Float,
  val isEnergyBoost: Boolean,
)

object CamelotHarmonics {
  private val CODE_REGEX = Regex("""^(\d{1,2})([aAbB])$""")

  data class ParsedKey(val number: Int, val isMinor: Boolean)

  fun parse(key: String): ParsedKey? {
    val match = CODE_REGEX.matchEntire(key.trim()) ?: return null
    val number = match.groupValues[1].toIntOrNull() ?: return null
    if (number !in 1..12) return null
    val isMinor = match.groupValues[2].equals("A", ignoreCase = true)
    return ParsedKey(number, isMinor)
  }

  fun getDistance(keyA: String, keyB: String): Int {
    val a = parse(keyA) ?: return 6
    val b = parse(keyB) ?: return 6
    val circleDiff = abs(a.number - b.number)
    val deltaCircle = min(circleDiff, 12 - circleDiff)
    val modeDiff = if (a.isMinor != b.isMinor) 1 else 0
    return deltaCircle + modeDiff
  }

  fun calculateHarmonics(keyA: String, keyB: String): HarmonicResult {
    val a = parse(keyA)
    val b = parse(keyB)
    if (a == null || b == null) {
      return HarmonicResult(distance = 6, score = 0.0f, isEnergyBoost = false)
    }

    val circleDiff = abs(a.number - b.number)
    val deltaCircle = min(circleDiff, 12 - circleDiff)
    val sameMode = a.isMinor == b.isMinor
    val distance = deltaCircle + if (sameMode) 0 else 1

    val forwardStep = ((b.number - a.number) % 12 + 12) % 12
    val isEnergyBoost = sameMode && (forwardStep == 2 || forwardStep == 7)

    val score = when {
      deltaCircle == 0 && sameMode -> 1.00f   // Exact key (8A -> 8A)
      deltaCircle == 0 && !sameMode -> 0.90f  // Relative major/minor (8A <-> 8B)
      deltaCircle == 1 && sameMode -> 0.85f   // Adjacent wheel step (8A -> 9A)
      isEnergyBoost && forwardStep == 2 -> 0.65f // Energy boost +2
      isEnergyBoost && forwardStep == 7 -> 0.70f // Energy boost +7 (+1 semitone)
      deltaCircle == 1 && !sameMode -> 0.75f  // Diagonal shift
      deltaCircle >= 5 -> 0.15f               // Incompatible clash
      else -> (1.0f - distance * 0.15f).coerceIn(0.15f, 0.80f)
    }

    return HarmonicResult(
      distance = distance,
      score = score,
      isEnergyBoost = isEnergyBoost,
    )
  }
}
