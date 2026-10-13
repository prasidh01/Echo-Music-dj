package echo.music.domain.autodj.reorder

import echo.music.domain.autodj.model.TrackProfile
import echo.music.dsp.autodj.CamelotHarmonics
import echo.music.dsp.autodj.TempoMatcher

/**
 * Pure Kotlin service calculating pairwise transition compatibility scores
 * and chaining tracks to maximize harmonic and tempo continuity.
 */
object AutoRemixOrderingEngine {
  const val DEFAULT_TEMPO_WEIGHT = 0.45f
  const val DEFAULT_HARMONIC_WEIGHT = 0.55f

  /**
   * Evaluates pairwise transition compatibility score in [0.0, 1.0].
   */
  fun scoreTransition(
    outgoing: TrackProfile?,
    incoming: TrackProfile?,
    tempoWeight: Float = DEFAULT_TEMPO_WEIGHT,
    harmonicWeight: Float = DEFAULT_HARMONIC_WEIGHT,
  ): Float {
    if (outgoing == null || incoming == null) return 0.50f
    if (outgoing.bpm <= 0f || incoming.bpm <= 0f) return 0.50f

    val tempoMatch = TempoMatcher.calculateRatio(outgoing.bpm, incoming.bpm)
    val tempoScore = (1.0f - (tempoMatch.delta / 0.16f)).coerceIn(0.0f, 1.0f)

    val outKey = outgoing.camelotKey
    val inKey = incoming.camelotKey
    val harmonicScore = if (!outKey.isNullOrBlank() && !inKey.isNullOrBlank()) {
      CamelotHarmonics.calculateHarmonics(outKey, inKey).score
    } else {
      0.50f
    }

    val totalWeight = tempoWeight + harmonicWeight
    return if (totalWeight > 0f) {
      (tempoScore * tempoWeight + harmonicScore * harmonicWeight) / totalWeight
    } else {
      0.50f
    }
  }

  fun scoreTransition(
    outBpm: Float,
    outKey: String?,
    inBpm: Float,
    inKey: String?,
    tempoWeight: Float = DEFAULT_TEMPO_WEIGHT,
    harmonicWeight: Float = DEFAULT_HARMONIC_WEIGHT,
  ): Float {
    val outProfile = TrackProfile(songId = "out", bpm = outBpm, confidence = 1.0f, firstBeatOffsetMs = 0L, camelotKey = outKey)
    val inProfile = TrackProfile(songId = "in", bpm = inBpm, confidence = 1.0f, firstBeatOffsetMs = 0L, camelotKey = inKey)
    return scoreTransition(outProfile, inProfile, tempoWeight, harmonicWeight)
  }

  /**
   * Greedy nearest-neighbor chaining algorithm: orders candidates relative to
   * anchor track to maximize pairwise transition continuity.
   * Guarantees stable tie-breaking: identical scores preserve original input order.
   */
  fun <T> chainTracks(
    anchor: TrackProfile?,
    candidates: List<T>,
    profileProvider: (T) -> TrackProfile?,
    lookaheadLimit: Int = Int.MAX_VALUE,
  ): List<T> {
    if (candidates.size <= 1) return candidates

    val windowSize = minOf(candidates.size, lookaheadLimit)
    val window = candidates.subList(0, windowSize).toMutableList()
    val remainder = candidates.subList(windowSize, candidates.size)

    val chained = ArrayList<T>(windowSize)
    var currentProfile = anchor

    while (window.isNotEmpty()) {
      var bestIndex = 0
      var bestScore = -1.0f

      for (i in window.indices) {
        val candProfile = profileProvider(window[i])
        val score = scoreTransition(currentProfile, candProfile)
        if (score > bestScore + 1e-4f) {
          bestScore = score
          bestIndex = i
        }
      }

      val chosen = window.removeAt(bestIndex)
      chained.add(chosen)
      currentProfile = profileProvider(chosen)
    }

    return chained + remainder
  }

  /**
   * Ranks candidates by transition score from anchor in descending order.
   * Original index is used as stable tie-breaker.
   */
  fun <T> rankCandidates(
    anchor: TrackProfile?,
    candidates: List<T>,
    profileProvider: (T) -> TrackProfile?,
  ): List<T> {
    if (candidates.size <= 1) return candidates
    return candidates
      .mapIndexed { index, item ->
        Triple(item, scoreTransition(anchor, profileProvider(item)), index)
      }
      .sortedWith(
        compareByDescending<Triple<T, Float, Int>> { it.second }
          .thenBy { it.third }
      )
      .map { it.first }
  }
}

/**
 * Compatible alias for AutoRemixScorer referenced in test infrastructure specifications.
 */
object AutoRemixScorer {
  fun scoreTransition(
    outgoing: TrackProfile?,
    incoming: TrackProfile?,
  ): Float = AutoRemixOrderingEngine.scoreTransition(outgoing, incoming)

  fun scoreTransition(
    outBpm: Float,
    outKey: String?,
    inBpm: Float,
    inKey: String?,
  ): Float = AutoRemixOrderingEngine.scoreTransition(outBpm, outKey, inBpm, inKey)

  fun <T> rankCandidates(
    anchor: TrackProfile?,
    candidates: List<T>,
    profileProvider: (T) -> TrackProfile?,
  ): List<T> = AutoRemixOrderingEngine.rankCandidates(anchor, candidates, profileProvider)

  fun <T> chainTracks(
    anchor: TrackProfile?,
    candidates: List<T>,
    profileProvider: (T) -> TrackProfile?,
  ): List<T> = AutoRemixOrderingEngine.chainTracks(anchor, candidates, profileProvider)
}
