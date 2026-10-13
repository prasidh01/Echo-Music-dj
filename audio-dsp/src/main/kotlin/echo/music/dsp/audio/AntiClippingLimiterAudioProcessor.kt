package echo.music.dsp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * High-performance peak limiting AudioProcessor in the Media3 pipeline.
 *
 * Enforces a strict ceiling threshold of -0.1 dBFS (32386 out of 32767 for 16-bit PCM),
 * preventing digital clipping during dual-track crossfades, EQ boosts, and spatial widening.
 *
 * Utilizes a fast-attack (< 1ms) and smooth-release (~50ms) envelope follower with a lookahead
 * delay buffer and a C1-continuous hyperbolic tangent soft-knee ceiling.
 */
@UnstableApi
class AntiClippingLimiterAudioProcessor(
  val ceiling: Short = DEFAULT_CEILING,
  val threshold: Short = DEFAULT_THRESHOLD,
  val attackMs: Double = DEFAULT_ATTACK_MS,
  val releaseMs: Double = DEFAULT_RELEASE_MS,
  val lookaheadMs: Double = DEFAULT_LOOKAHEAD_MS,
) : AudioProcessor {

  private var sampleRate: Int = 0
  private var channelCount: Int = 0
  private var encoding: Int = C.ENCODING_INVALID
  private var isActive: Boolean = false

  private var outputBuffer: ByteBuffer = EMPTY_BUFFER
  private var inputEnded: Boolean = false

  // Ballistics coefficients
  private var attackCoeff: Double = 0.0
  private var releaseCoeff: Double = 0.0
  private var currentGain: Double = 1.0

  // Soft-knee parameters
  private val ceilingDouble: Double = ceiling.toDouble()
  private val thresholdDouble: Double = threshold.toDouble()
  private val kneeWidth: Double = max(1.0, ceilingDouble - thresholdDouble)

  // Lookahead delay line
  private var lookaheadFrames: Int = 0
  private var delayBuffer: ShortArray = ShortArray(0)
  private var delayWriteIndex: Int = 0
  private var pendingLookaheadDrainFrames: Int = 0

  /** Current instantaneous gain reduction in decibels (>= 0.0 dB). */
  @Volatile
  var currentGainReductionDb: Float = 0f
    private set

  /** Peak gain reduction observed since last reset. */
  @Volatile
  var maxGainReductionDb: Float = 0f
    private set

  companion object {
    const val DEFAULT_CEILING: Short = 32386 // -0.1 dBFS
    const val DEFAULT_THRESHOLD: Short = 29193 // -1.0 dBFS
    const val DEFAULT_ATTACK_MS: Double = 1.0
    const val DEFAULT_RELEASE_MS: Double = 50.0
    const val DEFAULT_LOOKAHEAD_MS: Double = 1.0

    private val EMPTY_BUFFER: ByteBuffer =
      ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
  }

  fun resetMaxGainReduction() {
    maxGainReductionDb = 0f
  }

  fun isLimiting(): Boolean = currentGainReductionDb > 0.05f

  override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    sampleRate = inputAudioFormat.sampleRate
    channelCount = inputAudioFormat.channelCount
    encoding = inputAudioFormat.encoding

    val effectiveSr = sampleRate.coerceAtLeast(8000)
    attackCoeff = 1.0 - exp(-1.0 / (attackMs * 0.001 * effectiveSr))
    releaseCoeff = 1.0 - exp(-1.0 / (releaseMs * 0.001 * effectiveSr))

    lookaheadFrames = if (lookaheadMs > 0.0) {
      (lookaheadMs * 0.001 * effectiveSr).roundToInt().coerceAtLeast(1)
    } else {
      0
    }

    val totalDelaySamples = lookaheadFrames * channelCount
    delayBuffer = ShortArray(totalDelaySamples)
    delayWriteIndex = 0
    pendingLookaheadDrainFrames = 0
    currentGain = 1.0
    currentGainReductionDb = 0f
    maxGainReductionDb = 0f

    isActive = true
    return inputAudioFormat
  }

  override fun isActive(): Boolean = isActive

  override fun queueInput(inputBuffer: ByteBuffer) {
    val inputSize = inputBuffer.remaining()
    if (inputSize == 0) return

    val totalOutputCapacity = inputSize + (pendingLookaheadDrainFrames * channelCount * 2)
    if (outputBuffer.capacity() < totalOutputCapacity) {
      outputBuffer = ByteBuffer.allocateDirect(totalOutputCapacity).order(ByteOrder.nativeOrder())
    } else {
      outputBuffer.clear()
    }

    val frameCount = inputSize / (2 * channelCount)

    when (channelCount) {
      1 -> processMono(inputBuffer, frameCount)
      2 -> processStereo(inputBuffer, frameCount)
      else -> {
        repeat(inputSize / 2) { outputBuffer.putShort(inputBuffer.getShort()) }
      }
    }

    val grDb = if (currentGain < 0.9999) {
      (-20.0 * log10(currentGain.coerceIn(1e-5, 1.0))).toFloat()
    } else {
      0f
    }
    currentGainReductionDb = grDb
    if (grDb > maxGainReductionDb) {
      maxGainReductionDb = grDb
    }

    outputBuffer.flip()
  }

  private fun processMono(input: ByteBuffer, frameCount: Int) {
    val hasLookahead = lookaheadFrames > 0
    repeat(frameCount) {
      val inSample = input.getShort()
      val peak = abs(inSample.toInt())

      val targetGain = if (peak > thresholdDouble) {
        val limited = applySoftKnee(peak.toDouble())
        (limited.toDouble() / peak).coerceIn(0.0, 1.0)
      } else {
        1.0
      }

      if (targetGain < currentGain) {
        currentGain += attackCoeff * (targetGain - currentGain)
      } else {
        currentGain += releaseCoeff * (targetGain - currentGain)
      }

      val sampleToProcess: Short = if (hasLookahead) {
        val delayed = delayBuffer[delayWriteIndex]
        delayBuffer[delayWriteIndex] = inSample
        delayWriteIndex = (delayWriteIndex + 1) % lookaheadFrames
        delayed
      } else {
        inSample
      }

      val scaled = sampleToProcess.toDouble() * currentGain
      outputBuffer.putShort(applySoftKnee(scaled))
    }
  }

  private fun processStereo(input: ByteBuffer, frameCount: Int) {
    val hasLookahead = lookaheadFrames > 0
    val totalDelaySamples = lookaheadFrames * 2

    repeat(frameCount) {
      val inL = input.getShort()
      val inR = input.getShort()
      val peak = max(abs(inL.toInt()), abs(inR.toInt()))

      val targetGain = if (peak > thresholdDouble) {
        val limited = applySoftKnee(peak.toDouble())
        (limited.toDouble() / peak).coerceIn(0.0, 1.0)
      } else {
        1.0
      }

      if (targetGain < currentGain) {
        currentGain += attackCoeff * (targetGain - currentGain)
      } else {
        currentGain += releaseCoeff * (targetGain - currentGain)
      }

      val outSampleL: Short
      val outSampleR: Short

      if (hasLookahead) {
        val readIdx = delayWriteIndex
        outSampleL = delayBuffer[readIdx]
        outSampleR = delayBuffer[readIdx + 1]

        delayBuffer[readIdx] = inL
        delayBuffer[readIdx + 1] = inR

        delayWriteIndex = (readIdx + 2) % totalDelaySamples
      } else {
        outSampleL = inL
        outSampleR = inR
      }

      val scaledL = outSampleL.toDouble() * currentGain
      val scaledR = outSampleR.toDouble() * currentGain

      outputBuffer.putShort(applySoftKnee(scaledL))
      outputBuffer.putShort(applySoftKnee(scaledR))
    }
  }

  /**
   * C1-continuous soft-knee saturation transfer function:
   * Linear passthrough below threshold (-1.0 dBFS, 29193).
   * Hyperbolic tangent compression asymptotic to ceiling (-0.1 dBFS, 32386).
   */
  fun applySoftKnee(sample: Double): Short {
    if (sample.isNaN()) return 0
    val absSample = abs(sample)
    if (absSample <= thresholdDouble) {
      return sample.roundToInt().toShort()
    }
    val excess = absSample - thresholdDouble
    val compressed = thresholdDouble + kneeWidth * tanh(excess / kneeWidth)
    val limited = compressed.coerceAtMost(ceilingDouble)
    val finalVal = if (sample < 0.0) -limited else limited
    return finalVal.roundToInt().toShort()
  }

  override fun queueEndOfStream() {
    inputEnded = true
    if (lookaheadFrames > 0 && delayBuffer.isNotEmpty()) {
      pendingLookaheadDrainFrames = lookaheadFrames
      val drainBytes = lookaheadFrames * channelCount * 2
      if (outputBuffer.capacity() < outputBuffer.position() + drainBytes) {
        val newBuf = ByteBuffer.allocateDirect(outputBuffer.position() + drainBytes)
          .order(ByteOrder.nativeOrder())
        outputBuffer.flip()
        newBuf.put(outputBuffer)
        outputBuffer = newBuf
      }

      repeat(lookaheadFrames) {
        if (channelCount == 1) {
          val s = delayBuffer[delayWriteIndex]
          delayBuffer[delayWriteIndex] = 0
          delayWriteIndex = (delayWriteIndex + 1) % lookaheadFrames
          outputBuffer.putShort(applySoftKnee(s.toDouble() * currentGain))
        } else if (channelCount == 2) {
          val idx = delayWriteIndex
          val l = delayBuffer[idx]
          val r = delayBuffer[idx + 1]
          delayBuffer[idx] = 0
          delayBuffer[idx + 1] = 0
          delayWriteIndex = (idx + 2) % (lookaheadFrames * 2)
          outputBuffer.putShort(applySoftKnee(l.toDouble() * currentGain))
          outputBuffer.putShort(applySoftKnee(r.toDouble() * currentGain))
        }
      }
      pendingLookaheadDrainFrames = 0
      outputBuffer.flip()
    }
  }

  override fun getOutput(): ByteBuffer {
    val buffer = outputBuffer
    outputBuffer = EMPTY_BUFFER
    return buffer
  }

  override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

  @Deprecated("Deprecated in Java")
  override fun flush() {
    outputBuffer = EMPTY_BUFFER
    inputEnded = false
    currentGain = 1.0
    currentGainReductionDb = 0f
    delayBuffer.fill(0)
    delayWriteIndex = 0
    pendingLookaheadDrainFrames = 0
  }

  override fun reset() {
    @Suppress("DEPRECATION") flush()
    sampleRate = 0
    channelCount = 0
    encoding = C.ENCODING_INVALID
    isActive = false
    lookaheadFrames = 0
    delayBuffer = ShortArray(0)
    maxGainReductionDb = 0f
  }
}
