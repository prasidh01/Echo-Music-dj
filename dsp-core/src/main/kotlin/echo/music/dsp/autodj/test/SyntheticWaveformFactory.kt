package echo.music.dsp.autodj.test

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object SyntheticWaveformFactory {

  fun createPulseTrain(
    bpm: Float,
    durationSec: Float,
    sampleRate: Int = 44100,
    pulseFreqHz: Float = 120.0f,
    pulseWidthMs: Float = 20.0f,
  ): FloatArray {
    val totalSamples = (durationSec * sampleRate).toInt()
    val samples = FloatArray(totalSamples)
    val beatIntervalSec = 60.0 / bpm
    val pulseSamples = (pulseWidthMs * 0.001f * sampleRate).toInt().coerceAtLeast(1)

    var beatTime = 0.0
    while (beatTime < durationSec) {
      val startSample = (beatTime * sampleRate).toInt()
      for (i in 0 until pulseSamples) {
        val sIdx = startSample + i
        if (sIdx < totalSamples) {
          val window = 0.5 - 0.5 * cos(2.0 * PI * i / pulseSamples)
          val wave = sin(2.0 * PI * pulseFreqHz * i / sampleRate)
          samples[sIdx] = (window * wave).toFloat()
        }
      }
      beatTime += beatIntervalSec
    }
    return samples
  }

  fun createFourOnTheFloor(
    bpm: Float,
    durationSec: Float,
    sampleRate: Int = 44100,
    accentDownbeat: Boolean = true,
    firstBeatOffsetSec: Float = 0.0f,
  ): FloatArray {
    val totalSamples = (durationSec * sampleRate).toInt()
    val samples = FloatArray(totalSamples)
    val beatIntervalSec = 60.0 / bpm
    val pulseSamples = (0.025f * sampleRate).toInt()

    var beatIdx = 0
    var beatTime = firstBeatOffsetSec.toDouble()
    while (beatTime < durationSec) {
      val startSample = (beatTime * sampleRate).toInt()
      val isDownbeat = (beatIdx % 4 == 0)
      val amp = if (accentDownbeat && isDownbeat) 1.0f else 0.5f
      val freq = if (accentDownbeat && isDownbeat) 80.0f else 120.0f

      for (i in 0 until pulseSamples) {
        val sIdx = startSample + i
        if (sIdx in 0 until totalSamples) {
          val window = 0.5 - 0.5 * cos(2.0 * PI * i / pulseSamples)
          val wave = sin(2.0 * PI * freq * i / sampleRate)
          samples[sIdx] = (amp * window * wave).toFloat()
        }
      }
      beatIdx++
      beatTime += beatIntervalSec
    }
    return samples
  }

  fun createPolyphonicChord(
    midiNotes: List<Int>,
    durationSec: Float,
    sampleRate: Int = 44100,
  ): FloatArray {
    val totalSamples = (durationSec * sampleRate).toInt()
    val samples = FloatArray(totalSamples)
    if (midiNotes.isEmpty() || totalSamples <= 0) return samples

    val normFactor = 1.0f / midiNotes.size
    for (note in midiNotes) {
      val freq = 440.0 * 2.0.pow((note - 69).toDouble() / 12.0)
      for (i in 0 until totalSamples) {
        samples[i] += (normFactor * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
      }
    }
    return samples
  }

  fun createWhiteNoise(
    durationSec: Float,
    sampleRate: Int = 44100,
    amplitude: Float = 0.3f,
    seed: Long = 42L,
  ): FloatArray {
    val totalSamples = (durationSec * sampleRate).toInt()
    val samples = FloatArray(totalSamples)
    val random = Random(seed)
    for (i in 0 until totalSamples) {
      samples[i] = ((random.nextFloat() * 2f - 1f) * amplitude)
    }
    return samples
  }

  fun createSilence(
    durationSec: Float,
    sampleRate: Int = 44100,
  ): FloatArray {
    val totalSamples = (durationSec * sampleRate).toInt()
    return FloatArray(totalSamples)
  }

  fun createJitteredPulseTrain(
    bpm: Float,
    durationSec: Float,
    sampleRate: Int = 44100,
    jitterMs: Float = 200.0f,
    seed: Long = 123L,
  ): FloatArray {
    val totalSamples = (durationSec * sampleRate).toInt()
    val samples = FloatArray(totalSamples)
    val beatIntervalSec = 60.0 / bpm
    val pulseSamples = (0.02f * sampleRate).toInt()
    val random = Random(seed)

    var beatTime = 0.0
    while (beatTime < durationSec) {
      val jitter = (random.nextFloat() * 2f - 1f) * jitterMs * 0.001f
      val actualTime = (beatTime + jitter).coerceAtLeast(0.0)
      val startSample = (actualTime * sampleRate).toInt()

      for (i in 0 until pulseSamples) {
        val sIdx = startSample + i
        if (sIdx in 0 until totalSamples) {
          val window = 0.5 - 0.5 * cos(2.0 * PI * i / pulseSamples)
          val wave = sin(2.0 * PI * 120.0 * i / sampleRate)
          samples[sIdx] = (window * wave).toFloat()
        }
      }
      beatTime += beatIntervalSec
    }
    return samples
  }

  fun createDynamicTrack(
    bpm: Float,
    introSec: Float,
    bodySec: Float,
    outroSec: Float,
    sampleRate: Int = 44100,
  ): Pair<FloatArray, FloatArray> {
    val head = createFourOnTheFloor(bpm, introSec + 5.0f, sampleRate)
    val introSamples = (introSec * sampleRate).toInt().coerceAtMost(head.size)
    for (i in 0 until introSamples) {
      head[i] *= 0.1f
    }

    val tail = createFourOnTheFloor(bpm, outroSec + 5.0f, sampleRate)
    val outroStart = (5.0f * sampleRate).toInt().coerceAtMost(tail.size)
    for (i in outroStart until tail.size) {
      tail[i] *= 0.1f
    }
    return Pair(head, tail)
  }
}
