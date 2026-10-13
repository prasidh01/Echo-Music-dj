package echo.music.domain.autodj.planner

import echo.music.domain.autodj.model.CamelotKey
import echo.music.domain.autodj.model.DjPreset
import echo.music.domain.autodj.model.FallbackReason
import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

interface TransitionPlanner {
  /**
   * Plans an optimal transition between the current outgoing track and the incoming track.
   *
   * @param current The profile of the outgoing playing track.
   * @param incoming The profile of the incoming next track.
   * @param preset The DJ preset defining blend boundaries and style.
   * @param currentPlaybackPositionMs Outgoing track's current playback position.
   * @param generationId Monotonic generation token to cancel stale plans.
   */
  fun planTransition(
    current: TrackProfile,
    incoming: TrackProfile,
    preset: DjPreset = DjPreset.SEAMLESS,
    currentPlaybackPositionMs: Long = 0L,
    generationId: Long = 0L,
  ): TransitionPlan

  companion object : TransitionPlanner by TransitionPlannerImpl() {
    fun create(): TransitionPlanner = TransitionPlannerImpl()
  }
}

class TransitionPlannerImpl : TransitionPlanner {

  override fun planTransition(
    current: TrackProfile,
    incoming: TrackProfile,
    preset: DjPreset,
    currentPlaybackPositionMs: Long,
    generationId: Long,
  ): TransitionPlan {
    // 1. Tempo Ratio Calculation with Octave Folding (bounded to ±8%)
    var foldedRatio = if (current.bpm > 0f && incoming.bpm > 0f) {
      current.bpm / incoming.bpm
    } else {
      1.0f
    }

    if (foldedRatio > 0f) {
      while (foldedRatio > 1.5f) {
        foldedRatio /= 2.0f
      }
      while (foldedRatio < 0.667f) {
        foldedRatio *= 2.0f
      }
    }

    val tempoRatio = if (foldedRatio in 0.92f..1.08f) foldedRatio else 1.0f

    // 2. Harmonic Compatibility Scoring & Pitch Correction
    val currentKey = current.parsedCamelotKey
    val incomingKey = incoming.parsedCamelotKey
    var harmonicScore = 0.5f
    var pitchShiftSemitones = 0
    var pitchRatio = 1.0f

    if (currentKey != null && incomingKey != null) {
      harmonicScore = currentKey.harmonicCompatibilityScore(incomingKey)
      if (harmonicScore < 0.75f && preset.allowPitchCorrection && preset.maxPitchShiftSemitones > 0) {
        val outPc = if (current.isMinor == true) {
          ((current.pitchClass ?: currentKey.toPitchClass()) + 3) % 12
        } else {
          (current.pitchClass ?: currentKey.toPitchClass()) % 12
        }
        val inPc = if (incoming.isMinor == true) {
          ((incoming.pitchClass ?: incomingKey.toPitchClass()) + 3) % 12
        } else {
          (incoming.pitchClass ?: incomingKey.toPitchClass()) % 12
        }
        val rawShift = ((outPc - inPc) % 12 + 12) % 12
        val shift = if (rawShift > 6) rawShift - 12 else rawShift

        if (abs(shift) <= preset.maxPitchShiftSemitones) {
          pitchShiftSemitones = shift
          pitchRatio = 2.0.pow(shift / 12.0).toFloat()
          harmonicScore = 0.85f
        }
      }
    }

    // 3. Structural Mix Points & Phrase Snapping
    val outBpm = if (current.bpm > 0f) current.bpm else 120.0f
    val periodMs = 60_000.0 / outBpm
    val phraseBeatCount = if (current.phraseBeatCount > 0) current.phraseBeatCount else 8
    val phraseMs = periodMs * phraseBeatCount

    val rawOverlapMs = (preset.defaultOverlapBeats * periodMs).toLong()
    val overlapMs = rawOverlapMs.coerceIn(preset.minOverlapMs, preset.maxOverlapMs)

    val latestTrigger = if (current.durationMs > 0L) {
      (current.durationMs - overlapMs - 500L).coerceAtLeast(0L)
    } else {
      0L
    }
    val mixOut = current.mixOutPointMs?.takeIf { it > 0L }
    val effectiveTrigger = mixOut?.coerceAtMost(latestTrigger) ?: latestTrigger

    val minTrigger = currentPlaybackPositionMs + 1000L
    val firstBeat = current.firstBeatOffsetMs.coerceAtLeast(0L)

    val triggerTimeMs = if (phraseMs > 0.0) {
      val k = kotlin.math.floor((effectiveTrigger - firstBeat) / phraseMs).toLong()
      var candidate = (firstBeat + k * phraseMs).toLong()
      while (candidate < minTrigger) {
        candidate = (candidate + phraseMs).toLong()
      }
      candidate
    } else {
      max(effectiveTrigger, minTrigger)
    }

    val effectiveOverlapMs = if (current.durationMs > 0L) {
      val roomMs = (current.durationMs - 500L - triggerTimeMs).coerceAtLeast(0L)
      overlapMs.coerceAtMost(roomMs)
    } else {
      overlapMs
    }

    // Incoming Intro Mix-In Alignment
    val inBpm = if (incoming.bpm > 0f) incoming.bpm else 120.0f
    val inPeriodMs = 60_000.0 / inBpm
    val inPhraseBeats = if (incoming.phraseBeatCount > 0) incoming.phraseBeatCount else 8
    val inPhraseMs = inPeriodMs * inPhraseBeats
    val inFirstBeat = incoming.firstBeatOffsetMs.coerceAtLeast(0L)
    val rawStart = incoming.mixInPointMs?.takeIf { it > 0L } ?: inFirstBeat

    val incomingStartMs = if (inPhraseMs > 0.0) {
      val inK = kotlin.math.ceil((rawStart - inFirstBeat) / inPhraseMs).toLong().coerceAtLeast(0L)
      (inFirstBeat + inK * inPhraseMs).toLong()
    } else {
      rawStart
    }

    val confidence = if (current.hasValidAnalysis && incoming.hasValidAnalysis) {
      (current.confidence + incoming.confidence) / 2.0f
    } else {
      0.0f
    }

    return TransitionPlan(
      outgoingSongId = current.songId,
      incomingSongId = incoming.songId,
      transitionType = TransitionType.PHRASE_ALIGNED_BLEND,
      triggerTimeMs = triggerTimeMs,
      incomingStartMs = incomingStartMs,
      overlapDurationMs = effectiveOverlapMs,
      tempoRatio = tempoRatio,
      pitchRatio = pitchRatio,
      pitchShiftSemitones = pitchShiftSemitones,
      lowShelfDuckDb = preset.bassDuckingDb,
      confidence = confidence,
      harmonicScore = harmonicScore,
      fallbackReason = FallbackReason.NONE,
      generationId = generationId,
    )
  }
}
