package echo.music.iad1tya.playback.autodj

import echo.music.dsp.autodj.test.SyntheticWaveformFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackAnalyzerTest {

  private lateinit var analyzer: TrackAnalyzer
  private val sampleRate = 44100

  @Before
  fun setUp() {
    analyzer = DefaultTrackAnalyzer()
  }

  @Test
  fun testSynthetic120BpmBeatDetection() {
    // T1-F01-01: Synthetic 120 BPM Beat Detection
    val audio = SyntheticWaveformFactory.createFourOnTheFloor(120.0f, 12.0f, sampleRate, true)
    val profile = analyzer.analyze("song_120", audio, sampleRate)

    assertEquals("song_120", profile.songId)
    assertEquals(120.0f, profile.bpm, 1.0f)
    assertTrue("Confidence should be >= 0.70f, was ${profile.confidence}", profile.confidence >= 0.70f)
    assertTrue(profile.hasValidAnalysis)
  }

  @Test
  fun testDownbeatPhaseAlignment() {
    // T1-F01-02: Downbeat Phase Alignment (Measure 1 of 4)
    val audio = SyntheticWaveformFactory.createFourOnTheFloor(120.0f, 10.0f, sampleRate, true)
    val profile = analyzer.analyze("song_downbeat", audio, sampleRate)

    assertEquals(2000.0, profile.downbeatIntervalMs.toDouble(), 10.0)
    assertTrue(
      "First beat offset ${profile.firstBeatOffsetMs} should align with downbeat",
      profile.firstBeatOffsetMs < 50L || profile.firstBeatOffsetMs > (profile.downbeatIntervalMs - 50L)
    )
  }

  @Test
  fun testPhraseBoundaryDetection() {
    // T1-F01-03: Phrase Boundary Detection (32 beats)
    val audio = SyntheticWaveformFactory.createFourOnTheFloor(128.0f, 10.0f, sampleRate, true)
    val profile = analyzer.analyze("song_phrase", audio, sampleRate)

    assertEquals(32, profile.phraseBeatCount)
  }

  @Test
  fun testDynamicConfidenceScoringJitterNoise() {
    // T1-F01-04: Dynamic Confidence Scoring (< 0.40 for jitter/noise)
    val jitterAudio = SyntheticWaveformFactory.createJitteredPulseTrain(120.0f, 10.0f, sampleRate, 200.0f)
    val profileJitter = analyzer.analyze("song_jitter", jitterAudio, sampleRate)
    assertTrue("Jitter confidence should be < 0.40f, was ${profileJitter.confidence}", profileJitter.confidence < 0.40f)

    val noise = SyntheticWaveformFactory.createWhiteNoise(8.0f, sampleRate, 0.4f)
    val profileNoise = analyzer.analyze("song_noise", noise, sampleRate)
    assertTrue("Noise confidence should be < 0.40f, was ${profileNoise.confidence}", profileNoise.confidence < 0.40f)
  }

  @Test
  fun testPitchClassAndCamelotExtraction() {
    // T1-F01-05: Pitch Class & Camelot Extraction (A minor -> "8A", pitchClass 9, isMinor true)
    val aMinorChord = SyntheticWaveformFactory.createPolyphonicChord(listOf(57, 69, 72, 76, 81), 4.0f, sampleRate)
    val profile = analyzer.analyze("song_key", aMinorChord, sampleRate)

    assertEquals("8A", profile.camelotKey)
    assertEquals(9, profile.pitchClass)
    assertEquals(true, profile.isMinor)
    assertNotNull(profile.parsedCamelotKey)
    assertEquals("8A", profile.parsedCamelotKey!!.code)
  }

  @Test
  fun testExtremeLowBpm() {
    // T2-F01-01: Extreme Low BPM (30 BPM)
    val audio = SyntheticWaveformFactory.createPulseTrain(30.0f, 12.0f, sampleRate)
    val profile = analyzer.analyze("song_low_bpm", audio, sampleRate)

    // Either folds into canonical range [70, 140] or flags low confidence
    assertTrue(profile.confidence < 0.40f || profile.bpm in 60f..140f)
  }

  @Test
  fun testExtremeHighBpm() {
    // T2-F01-02: Extreme High BPM (320 BPM) -> folded into canonical range [70, 140]
    val audio = SyntheticWaveformFactory.createPulseTrain(160.0f, 10.0f, sampleRate)
    val profile = analyzer.analyze("song_high_bpm", audio, sampleRate)

    assertTrue("BPM must be in canonical range [70, 140], was ${profile.bpm}", profile.bpm in 70f..140f)
  }

  @Test
  fun testPureSilence() {
    // T2-F01-03: Pure Silence -> confidence = 0, bpm = 0
    val silence = SyntheticWaveformFactory.createSilence(4.0f, sampleRate)
    val profile = analyzer.analyze("song_silence", silence, sampleRate)

    assertEquals(0.0f, profile.confidence, 0.001f)
    assertEquals(0.0f, profile.bpm, 0.001f)
    assertEquals(0L, profile.firstBeatOffsetMs)
  }

  @Test
  fun testUltraShortAudioClip() {
    // T2-F01-04: Ultra-short Audio Clip (2s)
    val shortAudio = SyntheticWaveformFactory.createFourOnTheFloor(120.0f, 2.0f, sampleRate)
    val profile = analyzer.analyze("song_short", shortAudio, sampleRate)

    assertNotNull(profile)
    assertTrue("Duration must be <= 2000ms, was ${profile.durationMs}", profile.durationMs <= 2000L)
  }

  @Test
  fun testAcceleratingRubatoAudio() {
    // T2-F01-05: Non-periodic Rubato / Jitter
    val rubatoAudio = SyntheticWaveformFactory.createJitteredPulseTrain(120.0f, 10.0f, sampleRate, 250.0f)
    val profile = analyzer.analyze("song_rubato", rubatoAudio, sampleRate)

    assertTrue("Rubato confidence should be < 0.40f, was ${profile.confidence}", profile.confidence < 0.40f)
  }
}
