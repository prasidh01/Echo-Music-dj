package echo.music.iad1tya.playback.autodj

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import echo.music.domain.autodj.model.TrackProfile
import echo.music.dsp.autodj.analysis.TrackAudioDspEngine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import timber.log.Timber

data class TrackProfileAnalysisResult(
  val profile: TrackProfile?,
  val isComplete: Boolean,
)

interface TrackAnalyzer {

  suspend fun analyzeLocal(
    context: Context,
    songId: String,
    uri: Uri,
    shouldCancel: () -> Boolean = { false },
  ): TrackProfile?

  @UnstableApi
  suspend fun analyzeStream(
    dataSourceFactory: DataSource.Factory,
    songId: String,
    tempDir: File,
    shouldCancel: () -> Boolean = { false },
  ): TrackProfileAnalysisResult?

  fun analyzePcm(
    songId: String,
    samples: FloatArray,
    sampleRate: Int,
    durationMs: Long = 0L,
    headSamples: FloatArray? = null,
    tailSamples: FloatArray? = null,
    tailActualStartMs: Long = 0L,
    shouldCancel: () -> Boolean = { false },
  ): TrackProfile?

  fun analyze(
    songId: String,
    samples: FloatArray,
    sampleRate: Int,
  ): TrackProfile

  companion object : TrackAnalyzer by DefaultTrackAnalyzer()
}

@Singleton
class DefaultTrackAnalyzer @Inject constructor() : TrackAnalyzer {

  companion object {
    private const val TAG = "DefaultTrackAnalyzer"
    private const val WINDOW_US = 18_000_000L
    private const val HEAD_WINDOW_US = 16_000_000L
    private const val TAIL_WINDOW_US = 24_000_000L
    private const val MAX_FETCH_BYTES = 5L * 1024 * 1024
  }

  override fun analyzePcm(
    songId: String,
    samples: FloatArray,
    sampleRate: Int,
    durationMs: Long,
    headSamples: FloatArray?,
    tailSamples: FloatArray?,
    tailActualStartMs: Long,
    shouldCancel: () -> Boolean,
  ): TrackProfile? {
    if (shouldCancel()) return null

    val dspResult = TrackAudioDspEngine.analyzePcm(
      samples = samples,
      sampleRate = sampleRate,
      durationMs = durationMs,
      headSamples = headSamples,
      tailSamples = tailSamples,
      tailActualStartMs = tailActualStartMs,
    )

    return TrackProfile(
      songId = songId,
      bpm = dspResult.bpm,
      confidence = dspResult.confidence,
      firstBeatOffsetMs = dspResult.firstBeatOffsetMs,
      downbeatIntervalMs = dspResult.downbeatIntervalMs,
      phraseBeatCount = dspResult.phraseBeatCount,
      camelotKey = dspResult.camelotKey,
      pitchClass = dspResult.pitchClass,
      isMinor = dspResult.isMinor,
      mixInPointMs = dspResult.mixInPointMs,
      mixOutPointMs = dspResult.mixOutPointMs,
      durationMs = dspResult.durationMs,
      energyLevel = dspResult.energyLevel,
    )
  }

  override fun analyze(
    songId: String,
    samples: FloatArray,
    sampleRate: Int,
  ): TrackProfile {
    return analyzePcm(songId, samples, sampleRate)
      ?: TrackProfile(
        songId = songId,
        bpm = 0f,
        confidence = 0f,
        firstBeatOffsetMs = 0L,
        downbeatIntervalMs = 2000L,
        phraseBeatCount = 32,
      )
  }

  override suspend fun analyzeLocal(
    context: Context,
    songId: String,
    uri: Uri,
    shouldCancel: () -> Boolean,
  ): TrackProfile? = extractAndAnalyze(songId, shouldCancel) { extractor ->
    extractor.setDataSource(context, uri, null)
  }

  @UnstableApi
  override suspend fun analyzeStream(
    dataSourceFactory: DataSource.Factory,
    songId: String,
    tempDir: File,
    shouldCancel: () -> Boolean,
  ): TrackProfileAnalysisResult? {
    val tempFile = File(tempDir, "autodj_${songId.hashCode()}.tmp")
    try {
      var copied = 0L
      var reachedEnd = false
      val source = dataSourceFactory.createDataSource()
      try {
        source.open(DataSpec.Builder().setUri(Uri.parse(songId)).setKey(songId).build())
        FileOutputStream(tempFile).use { out ->
          val buf = ByteArray(64 * 1024)
          while (copied < MAX_FETCH_BYTES) {
            if (shouldCancel()) return null
            val read = source.read(buf, 0, buf.size)
            if (read == C.RESULT_END_OF_INPUT) {
              reachedEnd = true
              break
            }
            if (shouldCancel()) return null
            out.write(buf, 0, read)
            copied += read
          }
        }
      } catch (e: Exception) {
        Timber.tag(TAG).d("Stream fetch for %s stopped at %d bytes (%s)", songId, copied, e.message)
      } finally {
        try {
          source.close()
        } catch (_: Exception) {}
      }

      if (copied < 256 * 1024) return null
      if (shouldCancel()) return null

      val profile = extractAndAnalyze(songId, shouldCancel) { extractor ->
        extractor.setDataSource(tempFile.absolutePath)
      }
      return TrackProfileAnalysisResult(profile, reachedEnd)
    } finally {
      tempFile.delete()
    }
  }

  private fun extractAndAnalyze(
    songId: String,
    shouldCancel: () -> Boolean,
    setSource: (MediaExtractor) -> Unit,
  ): TrackProfile? {
    val extractor = MediaExtractor()
    try {
      if (shouldCancel()) return null
      setSource(extractor)

      var trackIndex = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
          trackIndex = i
          format = f
          break
        }
      }
      if (trackIndex < 0 || format == null) return null

      extractor.selectTrack(trackIndex)
      val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
        format.getLong(MediaFormat.KEY_DURATION)
      } else {
        0L
      }
      val durationMs = durationUs / 1000L

      val windowStartUs = max(0L, durationUs / 2 - WINDOW_US / 2)
      extractor.seekTo(windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

      if (shouldCancel()) return null
      val middlePcm = decodeMono(extractor, format, WINDOW_US, shouldCancel) ?: return null
      if (middlePcm.samples.size < 1024 * 4) return null

      var headPcm: MonoPcm? = null
      if (durationUs > HEAD_WINDOW_US) {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        headPcm = decodeMono(extractor, format, HEAD_WINDOW_US, shouldCancel)
      }

      var tailPcm: MonoPcm? = null
      var tailActualStartMs = 0L
      if (durationUs > TAIL_WINDOW_US) {
        val tailStartUs = durationUs - TAIL_WINDOW_US
        extractor.seekTo(tailStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        tailActualStartMs = max(0L, extractor.sampleTime) / 1000L
        tailPcm = decodeMono(extractor, format, TAIL_WINDOW_US, shouldCancel)
      }

      return analyzePcm(
        songId = songId,
        samples = middlePcm.samples,
        sampleRate = middlePcm.sampleRate,
        durationMs = durationMs,
        headSamples = headPcm?.samples,
        tailSamples = tailPcm?.samples,
        tailActualStartMs = tailActualStartMs,
        shouldCancel = shouldCancel,
      )
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Track extraction and analysis failed")
      return null
    } finally {
      extractor.release()
    }
  }

  private class MonoPcm(val samples: FloatArray, val sampleRate: Int)

  private fun decodeMono(
    extractor: MediaExtractor,
    format: MediaFormat,
    maxDurationUs: Long,
    shouldCancel: () -> Boolean,
  ): MonoPcm? {
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
    val codec = MediaCodec.createDecoderByType(mime)
    val out = ArrayList<FloatArray>()
    var sampleRate = 0
    var decodedUs = 0L

    try {
      codec.configure(format, null, null, 0)
      codec.start()
      var inputDone = false
      var outputDone = false
      val info = MediaCodec.BufferInfo()

      while (!outputDone && decodedUs < maxDurationUs) {
        if (shouldCancel()) return null
        if (!inputDone) {
          val inIndex = codec.dequeueInputBuffer(10_000)
          if (inIndex >= 0) {
            val buffer = codec.getInputBuffer(inIndex)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
              codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }

        val outIndex = codec.dequeueOutputBuffer(info, 10_000)
        if (outIndex >= 0) {
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
          if (info.size > 0) {
            val outFormat = codec.outputFormat
            val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val buf = codec.getOutputBuffer(outIndex)!!
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val shorts = buf.asShortBuffer()
            val frames = shorts.remaining() / channels
            val mono = FloatArray(frames)
            for (f in 0 until frames) {
              var acc = 0f
              for (c in 0 until channels) {
                acc += shorts.get(f * channels + c) / 32768f
              }
              mono[f] = acc / channels
            }
            out.add(mono)
            decodedUs += frames * 1_000_000L / max(1, sampleRate)
          }
          codec.releaseOutputBuffer(outIndex, false)
        }
      }
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Decode failed")
      return null
    } finally {
      try {
        codec.stop()
      } catch (_: Exception) {}
      codec.release()
    }

    if (sampleRate == 0 || out.isEmpty()) return null

    val total = out.sumOf { it.size }
    val samples = FloatArray(total)
    var pos = 0
    for (chunk in out) {
      chunk.copyInto(samples, pos)
      pos += chunk.size
    }
    return MonoPcm(samples, sampleRate)
  }
}
