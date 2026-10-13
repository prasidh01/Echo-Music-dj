package echo.music.domain.autodj.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamelotKeyTest {

  @Test
  fun testParsingValidAndInvalidCodes() {
    // Valid 1A..12A and 1B..12B
    for (num in 1..12) {
      val keyA = CamelotKey.parse("${num}A")
      assertNotNull(keyA)
      assertEquals(num, keyA!!.number)
      assertEquals(CamelotMode.A, keyA.mode)

      val keyB = CamelotKey.parse("${num}b") // Case-insensitive
      assertNotNull(keyB)
      assertEquals(num, keyB!!.number)
      assertEquals(CamelotMode.B, keyB.mode)
    }

    // Invalid inputs
    assertNull(CamelotKey.parse("0A"))
    assertNull(CamelotKey.parse("13B"))
    assertNull(CamelotKey.parse("8C"))
    assertNull(CamelotKey.parse(""))
    assertNull(CamelotKey.parse("invalid"))
    assertNull(CamelotKey.parse(null))
  }

  @Test
  fun testPitchClassMappingAll24Keys() {
    // Golden mapping for Minor:
    // 0=C->5A, 1=C#->12A, 2=D->7A, 3=Eb->2A, 4=E->9A, 5=F->4A,
    // 6=F#->11A, 7=G->6A, 8=Ab->1A, 9=A->8A, 10=Bb->3A, 11=B->10A
    val expectedMinor = mapOf(
      0 to "5A", 1 to "12A", 2 to "7A", 3 to "2A", 4 to "9A", 5 to "4A",
      6 to "11A", 7 to "6A", 8 to "1A", 9 to "8A", 10 to "3A", 11 to "10A"
    )

    // Golden mapping for Major:
    // 0=C->8B, 1=C#->3B, 2=D->10B, 3=Eb->5B, 4=E->12B, 5=F->7B,
    // 6=F#->2B, 7=G->9B, 8=Ab->4B, 9=A->11B, 10=Bb->6B, 11=B->1B
    val expectedMajor = mapOf(
      0 to "8B", 1 to "3B", 2 to "10B", 3 to "5B", 4 to "12B", 5 to "7B",
      6 to "2B", 7 to "9B", 8 to "4B", 9 to "11B", 10 to "6B", 11 to "1B"
    )

    for ((pc, code) in expectedMinor) {
      val key = CamelotKey.fromPitchClass(pc, isMinor = true)
      assertEquals("Pitch class $pc minor should map to $code", code, key.code)
      // Invertibility check
      assertEquals("Invertibility: $code should map back to pitch class $pc", pc, key.toPitchClass())
      assertTrue(key.toIsMinor())
    }

    for ((pc, code) in expectedMajor) {
      val key = CamelotKey.fromPitchClass(pc, isMinor = false)
      assertEquals("Pitch class $pc major should map to $code", code, key.code)
      // Invertibility check
      assertEquals("Invertibility: $code should map back to pitch class $pc", pc, key.toPitchClass())
      assertFalse(key.toIsMinor())
    }
  }

  @Test
  fun testCircularDistanceAndModulo12Wrapping() {
    val key12A = CamelotKey(12, CamelotMode.A)
    val key1A = CamelotKey(1, CamelotMode.A)
    val key7A = CamelotKey(7, CamelotMode.A)

    // Wraparound across 12 -> 1
    assertEquals(1, key12A.circularDistance(key1A))
    assertEquals(1, key1A.circularDistance(key12A))

    // Antipodal distance
    assertEquals(6, key1A.circularDistance(key7A))
    assertEquals(6, key7A.circularDistance(key1A))

    // Same key
    assertEquals(0, key1A.circularDistance(key1A))
  }

  @Test
  fun testHarmonicScoringRules() {
    val key8A = CamelotKey(8, CamelotMode.A)
    val key8B = CamelotKey(8, CamelotMode.B)
    val key9A = CamelotKey(9, CamelotMode.A)
    val key7A = CamelotKey(7, CamelotMode.A)
    val key9B = CamelotKey(9, CamelotMode.B)
    val key3A = CamelotKey(3, CamelotMode.A) // +7 forward steps
    val key10A = CamelotKey(10, CamelotMode.A) // +2 forward steps
    val key2A = CamelotKey(2, CamelotMode.A) // 6 steps away (tritone clash)

    // Identical key (1.0)
    assertEquals(1.0f, key8A.harmonicCompatibilityScore(key8A), 0.001f)

    // Relative major/minor (1.0)
    assertEquals(1.0f, key8A.harmonicCompatibilityScore(key8B), 0.001f)
    assertEquals(1.0f, key8B.harmonicCompatibilityScore(key8A), 0.001f)

    // Adjacent fifth (+/- 1 step on wheel, same mode) (0.9)
    assertEquals(0.9f, key8A.harmonicCompatibilityScore(key9A), 0.001f)
    assertEquals(0.9f, key8A.harmonicCompatibilityScore(key7A), 0.001f)

    // Diagonal modulation (+/- 1 step, different mode) (0.75)
    assertEquals(0.75f, key8A.harmonicCompatibilityScore(key9B), 0.001f)

    // Energy boost (+7 steps on wheel = +1 semitone) (0.8)
    assertEquals(0.8f, key8A.harmonicCompatibilityScore(key3A), 0.001f)

    // Energy boost (+2 steps on wheel = +2 semitones) (0.6)
    assertEquals(0.6f, key8A.harmonicCompatibilityScore(key10A), 0.001f)

    // Distant clash (0.2)
    assertEquals(0.2f, key8A.harmonicCompatibilityScore(key2A), 0.001f)
  }
}
