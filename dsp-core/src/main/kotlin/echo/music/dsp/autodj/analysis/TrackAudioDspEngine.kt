package echo.music.dsp.autodj.analysis

import kotlin.math.max

data class TrackDspAnalysisResult(
  val bpm: Float,
  val firstBeatOffsetMs: Long,
  val downbeatIntervalMs: Long,
  val phraseBeatCount: Int = 32,
  val confidence: Float,
  val pitchClass: Int?,
  val isMinor: Boolean?,
  val camelotKey: String?,
  val mixInPointMs: Long?,
  val mixOutPointMs: Long?,
  val energyLevel: Float?,
  val durationMs: Long,
)

/**
 * Pure JVM audio analysis engine coordinating FFT, spectral flux, autocorrelation,
 * multi-scale comb filters, chroma key detection, and energy envelope profiling.
 */
object TrackAudioDspEngine {

  private const val FFT_SIZE = 1024
  private const val HOP_SIZE = 512

  fun analyzePcm(
    samples: FloatArray,
    sampleRate: Int,
    durationMs: Long = 0L,
    headSamples: FloatArray? = null,
    tailSamples: FloatArray? = null,
    tailActualStartMs: Long = 0L,
    windowStartUs: Long = 0L,
  ): TrackDspAnalysisResult {
    val effectiveDurationMs = if (durationMs > 0) {
      durationMs
    } else if (sampleRate > 0) {
      (samples.size.toLong() * 1000L / sampleRate)
    } else {
      0L
    }

    // Guard against silence or empty buffer
    val overallEnergy = EnergyEnvelopeDetector.calculateOverallEnergy(samples)
    if (samples.size < FFT_SIZE * 4 || sampleRate <= 0 || overallEnergy < 1e-4f) {
      return TrackDspAnalysisResult(
        bpm = 0f,
        firstBeatOffsetMs = 0L,
        downbeatIntervalMs = 2000L,
        phraseBeatCount = 32,
        confidence = 0f,
        pitchClass = null,
        isMinor = null,
        camelotKey = null,
        mixInPointMs = null,
        mixOutPointMs = null,
        energyLevel = overallEnergy,
        durationMs = effectiveDurationMs,
      )
    }

    // 1. Key Estimation
    val keyResult = ChromaKeyDetector.estimateKey(samples, sampleRate)

    // 2. Spectral Flux
    val flux = SpectralFluxCalculator.calculateFlux(samples, FFT_SIZE, HOP_SIZE)
    val frameRate = sampleRate.toFloat() / HOP_SIZE

    // 3. Tempo Estimation
    val tempoResult = AutocorrelationTempoEstimator.estimateTempo(flux, frameRate)
    if (tempoResult == null || tempoResult.bpm <= 0f) {
      return TrackDspAnalysisResult(
        bpm = 0f,
        firstBeatOffsetMs = 0L,
        downbeatIntervalMs = 2000L,
        phraseBeatCount = 32,
        confidence = 0f,
        pitchClass = keyResult?.pitchClass,
        isMinor = keyResult?.isMinor,
        camelotKey = keyResult?.camelotKey,
        mixInPointMs = null,
        mixOutPointMs = null,
        energyLevel = overallEnergy,
        durationMs = effectiveDurationMs,
      )
    }

    // 4. Beat Grid & 4-Beat Downbeat Phase Estimation
    val windowStartMs = windowStartUs / 1000L
    val gridResult = BeatGridEstimator.estimateGrid(
      flux = flux,
      bpm = tempoResult.bpm,
      periodFrames = tempoResult.periodFrames,
      frameRate = frameRate,
      windowStartMs = windowStartMs,
      phraseBeatCount = 32,
    )

    // 5. Dynamic Mix-In / Mix-Out Detection
    val periodMs = tempoResult.periodMs
    var mixInPointMs: Long? = null
    if (headSamples != null && headSamples.size >= sampleRate) {
      val headEnv = EnergyEnvelopeDetector.energyEnvelope(headSamples, sampleRate)
      mixInPointMs = EnergyEnvelopeDetector.detectMixIn(headEnv, gridResult.firstBeatOffsetMs, periodMs)
    }

    var mixOutPointMs: Long? = null
    if (tailSamples != null && tailSamples.size >= sampleRate) {
      val tailEnv = EnergyEnvelopeDetector.energyEnvelope(tailSamples, sampleRate)
      mixOutPointMs = EnergyEnvelopeDetector.detectMixOut(tailEnv, tailActualStartMs, effectiveDurationMs)
    }

    return TrackDspAnalysisResult(
      bpm = tempoResult.bpm,
      firstBeatOffsetMs = gridResult.firstBeatOffsetMs,
      downbeatIntervalMs = gridResult.downbeatIntervalMs,
      phraseBeatCount = gridResult.phraseBeatCount,
      confidence = tempoResult.confidence,
      pitchClass = keyResult?.pitchClass,
      isMinor = keyResult?.isMinor,
      camelotKey = keyResult?.camelotKey,
      mixInPointMs = mixInPointMs,
      mixOutPointMs = mixOutPointMs,
      energyLevel = overallEnergy,
      durationMs = effectiveDurationMs,
    )
  }
}
