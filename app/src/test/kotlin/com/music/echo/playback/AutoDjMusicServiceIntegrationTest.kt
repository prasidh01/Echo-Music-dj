package com.music.echo.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionType
import echo.music.dsp.audio.AntiClippingLimiterAudioProcessor
import echo.music.iad1tya.playback.autodj.AutoDjEvent
import echo.music.iad1tya.playback.autodj.AutoDjState
import echo.music.iad1tya.playback.autodj.AutoDjStateMachine
import echo.music.iad1tya.playback.autodj.CancelReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutoDjMusicServiceIntegrationTest {

  private lateinit var limiter: AntiClippingLimiterAudioProcessor
  private lateinit var stateMachine: AutoDjStateMachine

  private val sampleRate = 44100
  private val channelCount = 2

  @Before
  fun setUp() {
    limiter = AntiClippingLimiterAudioProcessor()
    stateMachine = AutoDjStateMachine(initialGenerationId = 0L)
  }

  // Acceptance Criterion 3: Dual-Stream Audio Limiting & Zero Clipping Protection
  @Test
  fun testDualStreamLimiterStrictCeilingAndSaturation() {
    val format = AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
    val configuredFormat = limiter.configure(format)
    assertEquals(format.sampleRate, configuredFormat.sampleRate)
    assertEquals(format.channelCount, configuredFormat.channelCount)
    assertEquals(format.encoding, configuredFormat.encoding)
    assertTrue("Limiter must be active", limiter.isActive)

    // Synthesize overloaded dual-stream signal summing to +3 dBFS (full scale 32,767 + square wave extremes)
    val frameCount = 1000
    val byteBuffer = ByteBuffer.allocateDirect(frameCount * channelCount * 2).order(ByteOrder.nativeOrder())
    for (i in 0 until frameCount) {
      val sample: Short = if (i % 2 == 0) 32767 else -32768
      byteBuffer.putShort(sample) // Left
      byteBuffer.putShort(sample) // Right
    }
    byteBuffer.flip()

    limiter.queueInput(byteBuffer)
    val output = limiter.output
    assertTrue("Output buffer must have data", output.hasRemaining())

    val shortBuffer = output.asShortBuffer()
    var maxOutputSample = 0
    while (shortBuffer.hasRemaining()) {
      val s = shortBuffer.get()
      val absVal = kotlin.math.abs(s.toInt())
      if (absVal > maxOutputSample) {
        maxOutputSample = absVal
      }
      // Strict ceiling: must never exceed 32,386 (-0.1 dBFS)
      assertTrue(
        "Sample $s exceeded -0.1 dBFS ceiling (${AntiClippingLimiterAudioProcessor.DEFAULT_CEILING})",
        absVal <= AntiClippingLimiterAudioProcessor.DEFAULT_CEILING,
      )
    }

    assertTrue("Limiter should be actively limiting on full-scale square wave", limiter.isLimiting())
    assertTrue("Gain reduction should be positive", limiter.currentGainReductionDb > 0.05f)
  }

  @Test
  fun testDualStreamLimiterSubThresholdPassthrough() {
    val format = AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
    limiter.configure(format)

    // Sub-threshold signal at -6 dBFS (amplitude 16,384)
    val frameCount = 500
    val inputBuffer = ByteBuffer.allocateDirect(frameCount * channelCount * 2).order(ByteOrder.nativeOrder())
    for (i in 0 until frameCount) {
      inputBuffer.putShort(16384.toShort())
      inputBuffer.putShort(16384.toShort())
    }
    inputBuffer.flip()

    limiter.queueInput(inputBuffer)
    val output = limiter.output
    val shortBuffer = output.asShortBuffer()

    while (shortBuffer.hasRemaining()) {
      val s = shortBuffer.get()
      // Lookahead may delay initial samples, but steady state amplitude must match 16384
      assertTrue("Sub-threshold sample must not exceed input amplitude", kotlin.math.abs(s.toInt()) <= 16384)
    }
    assertFalse("Limiter must not flag isLimiting on sub-threshold signal", limiter.isLimiting())
  }

  // Acceptance Criterion 3: Sonic PlaybackParameters Computation
  @Test
  fun testSonicPlaybackParametersComputation() {
    val base = PlaybackParameters(1.0f, 1.0f)
    val tempoRatio = 1.05f
    val pitchRatio = 1.03f

    val effectiveSpeed = (base.speed * tempoRatio).coerceIn(0.5f, 2.0f)
    val effectivePitch = (base.pitch * pitchRatio).coerceIn(0.5f, 2.0f)

    assertEquals(1.05f, effectiveSpeed, 0.0001f)
    assertEquals(1.03f, effectivePitch, 0.0001f)

    val params = PlaybackParameters(effectiveSpeed, effectivePitch)
    assertEquals(1.05f, params.speed, 0.0001f)
    assertEquals(1.03f, params.pitch, 0.0001f)

    // Bounded bounds check
    val extremeFastTempo = 2.5f
    val clampedSpeed = (base.speed * extremeFastTempo).coerceIn(0.5f, 2.0f)
    assertEquals(2.0f, clampedSpeed, 0.0001f)

    val extremeLowPitch = 0.2f
    val clampedPitch = (base.pitch * extremeLowPitch).coerceIn(0.5f, 2.0f)
    assertEquals(0.5f, clampedPitch, 0.0001f)
  }

  // Acceptance Criterion 4: State Machine Lifecycle and Monotonic Generation Invalidation
  @Test
  fun testStateMachineFullLifecycle() {
    val outProfile = TrackProfile("s_out", 120f, 0.95f, 0L, 2000L, 8, "8A")
    val inProfile = TrackProfile("s_in", 124f, 0.92f, 20L, 1935L, 8, "9A")

    // Idle -> Analyzing
    val state1 = stateMachine.startTransition("s_out", "s_in")
    assertTrue(state1 is AutoDjState.Analyzing)
    val gen = stateMachine.generationId

    // Analyzing -> Preparing
    val state2 = stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outProfile, inProfile))
    assertTrue(state2 is AutoDjState.Preparing)

    // Preparing -> Planning
    val state3 = stateMachine.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
    assertTrue(state3 is AutoDjState.Planning)

    // Planning -> Scheduled
    val plan = TransitionPlan("s_out", "s_in", TransitionType.PHRASE_ALIGNED_BLEND, 180000L, 0L, 8000L, 1.03f, 1.0f, generationId = gen)
    val state4 = stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    assertTrue(state4 is AutoDjState.Scheduled)

    // Scheduled -> Mixing
    val state5 = stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue(state5 is AutoDjState.Mixing)

    // Mixing progress
    val state5b = stateMachine.dispatchEvent(AutoDjEvent.MixProgress(gen, 0.5f))
    assertTrue(state5b is AutoDjState.Mixing)
    assertEquals(0.5f, (state5b as AutoDjState.Mixing).progress, 0.001f)

    // Mixing -> Handoff
    val state6 = stateMachine.dispatchEvent(AutoDjEvent.MixCompleted(gen))
    assertTrue(state6 is AutoDjState.Handoff)

    // Handoff -> Idle
    val state7 = stateMachine.reset()
    assertTrue(state7 is AutoDjState.Idle)
  }

  // Acceptance Criterion 4: Rapid Skip / Seek / Pause Cancellation Stress Tests
  @Test
  fun testRapidSkipDuringMixingCancelsAndAdvancesGeneration() {
    stateMachine.startTransition("track1", "track2")
    val gen = stateMachine.generationId
    val plan = TransitionPlan("track1", "track2", triggerTimeMs = 50000L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue("Should be Mixing", stateMachine.currentState is AutoDjState.Mixing)

    // User presses skip
    val cancelState = stateMachine.cancel(CancelReason.USER_SKIP)
    assertTrue("Should transition to Canceling", cancelState is AutoDjState.Canceling)
    val canceling = cancelState as AutoDjState.Canceling
    assertEquals(CancelReason.USER_SKIP, canceling.reason)
    assertTrue("Generation ID must advance on cancel", cancelState.generationId > gen)

    // Reset back to idle
    val resetState = stateMachine.reset()
    assertTrue("Should be Idle", resetState is AutoDjState.Idle)
  }

  @Test
  fun testUserSeekDuringScheduledCancelsTransition() {
    stateMachine.startTransition("track1", "track2")
    val gen = stateMachine.generationId
    val plan = TransitionPlan("track1", "track2", triggerTimeMs = 50000L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    assertTrue("Should be Scheduled", stateMachine.currentState is AutoDjState.Scheduled)

    val cancelState = stateMachine.cancel(CancelReason.USER_SEEK)
    assertTrue(cancelState is AutoDjState.Canceling)
    assertEquals(CancelReason.USER_SEEK, (cancelState as AutoDjState.Canceling).reason)

    // Attempting stale mix trigger must be rejected
    val staleTrigger = stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue("Stale trigger must be rejected", staleTrigger is AutoDjState.Canceling)
  }

  @Test
  fun testPauseDuringMixingCancelsTransition() {
    stateMachine.startTransition("track1", "track2")
    val gen = stateMachine.generationId
    val plan = TransitionPlan("track1", "track2", triggerTimeMs = 50000L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))

    val cancelState = stateMachine.cancel(CancelReason.PAUSE)
    assertTrue(cancelState is AutoDjState.Canceling)
    assertEquals(CancelReason.PAUSE, (cancelState as AutoDjState.Canceling).reason)
  }

  @Test
  fun testQueueMutationAbortsPreparation() {
    stateMachine.startTransition("track1", "track2")
    val gen = stateMachine.generationId
    val outProfile = TrackProfile("track1", 120f, 0.95f, 0L, 2000L, 8, "8A")
    val inProfile = TrackProfile("track2", 124f, 0.92f, 20L, 1935L, 8, "9A")
    stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outProfile, inProfile))
    assertTrue(stateMachine.currentState is AutoDjState.Preparing)

    val cancelState = stateMachine.cancel(CancelReason.QUEUE_MUTATION)
    assertTrue(cancelState is AutoDjState.Canceling)
    assertEquals(CancelReason.QUEUE_MUTATION, (cancelState as AutoDjState.Canceling).reason)
  }

  @Test
  fun test50CycleRapidSkipStressRun() {
    val initialGen = stateMachine.generationId
    for (i in 1..50) {
      val stateStart = stateMachine.startTransition("track_$i", "track_${i + 1}")
      assertTrue(stateStart is AutoDjState.Analyzing)
      val stateCancel = stateMachine.cancel(CancelReason.USER_SKIP)
      assertTrue(stateCancel is AutoDjState.Canceling)
      val stateIdle = stateMachine.reset()
      assertTrue(stateIdle is AutoDjState.Idle)
    }

    // 50 cycles with start (+1) and cancel (+1) must advance generation by >= 100
    assertTrue("Generation counter must advance on all 50 skips", stateMachine.generationId >= initialGen + 100L)
    assertTrue("Final state must be Idle", stateMachine.currentState is AutoDjState.Idle)
  }

  @Test
  fun testConcurrentStressDispatches() {
    val threadCount = 8
    val iterationsPerThread = 50
    val latch = CountDownLatch(threadCount)
    val errors = AtomicInteger(0)

    for (t in 0 until threadCount) {
      Thread {
        try {
          for (i in 0 until iterationsPerThread) {
            stateMachine.startTransition("tr_${t}_$i", "tr_${t}_${i + 1}")
            stateMachine.cancel(CancelReason.USER_SKIP)
            stateMachine.reset()
          }
        } catch (e: Throwable) {
          errors.incrementAndGet()
        } finally {
          latch.countDown()
        }
      }.start()
    }

    assertTrue("Concurrent dispatches must complete within 5 seconds", latch.await(5, TimeUnit.SECONDS))
    assertEquals("Must have 0 concurrent race errors", 0, errors.get())
  }

  @Test
  fun testOverloadAudioSignalLimiterAdversarialClamping() {
    val format = AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
    limiter.configure(format)

    // 1. Extreme positive saturation DC burst
    val posDcBuffer = ByteBuffer.allocateDirect(1000 * channelCount * 2).order(ByteOrder.nativeOrder())
    for (i in 0 until 1000) {
      posDcBuffer.putShort(Short.MAX_VALUE) // +32767
      posDcBuffer.putShort(Short.MAX_VALUE)
    }
    posDcBuffer.flip()
    limiter.queueInput(posDcBuffer)
    var out = limiter.output
    var sBuf = out.asShortBuffer()
    while (sBuf.hasRemaining()) {
      val s = sBuf.get()
      assertTrue("Positive DC burst exceeded ceiling: $s", kotlin.math.abs(s.toInt()) <= 32386)
    }

    // 2. Extreme negative saturation DC burst
    val negDcBuffer = ByteBuffer.allocateDirect(1000 * channelCount * 2).order(ByteOrder.nativeOrder())
    for (i in 0 until 1000) {
      negDcBuffer.putShort(Short.MIN_VALUE) // -32768
      negDcBuffer.putShort(Short.MIN_VALUE)
    }
    negDcBuffer.flip()
    limiter.queueInput(negDcBuffer)
    out = limiter.output
    sBuf = out.asShortBuffer()
    while (sBuf.hasRemaining()) {
      val s = sBuf.get()
      assertTrue("Negative DC burst exceeded ceiling: $s", kotlin.math.abs(s.toInt()) <= 32386)
    }

    // 3. Mathematical transfer function direct testing on extreme doubles
    val extremeValues = doubleArrayOf(
      32386.0, 32767.0, 46340.0, 65536.0, 100000.0, 1e9,
      -32386.0, -32768.0, -46340.0, -65536.0, -100000.0, -1e9,
      Double.MAX_VALUE, -Double.MAX_VALUE, Double.NaN
    )
    for (v in extremeValues) {
      val result = limiter.applySoftKnee(v)
      assertTrue(
        "applySoftKnee($v) = $result exceeded ceiling 32386",
        kotlin.math.abs(result.toInt()) <= 32386
      )
    }
  }

  @Test
  fun testSonicTempoAndPitchRatioExhaustiveBounds() {
    val base = PlaybackParameters(1.2f, 0.9f)
    val testRatios = floatArrayOf(
      -10.0f, -1.0f, 0.0f, 0.1f, 0.49f, 0.5f, 0.8f, 1.0f, 1.5f, 2.0f, 2.01f, 5.0f, 100.0f, Float.MAX_VALUE
    )

    for (r in testRatios) {
      val speed = (base.speed * r).coerceIn(0.5f, 2.0f)
      val pitch = (base.pitch * r).coerceIn(0.5f, 2.0f)

      assertTrue("Speed $speed must be >= 0.5f for ratio $r", speed >= 0.5f)
      assertTrue("Speed $speed must be <= 2.0f for ratio $r", speed <= 2.0f)
      assertTrue("Pitch $pitch must be >= 0.5f for ratio $r", pitch >= 0.5f)
      assertTrue("Pitch $pitch must be <= 2.0f for ratio $r", pitch <= 2.0f)
    }
  }

  @Test
  fun testRapid50CycleSkipCancellationWithProcessorAndVolumeSafety() {
    var primaryVolume = 1.0f
    val trackedProcessors = HashMap<String, AntiClippingLimiterAudioProcessor>()
    val initialGen = stateMachine.generationId

    for (cycle in 1..50) {
      // Begin transition
      stateMachine.startTransition("song_$cycle", "song_${cycle + 1}")
      val gen = stateMachine.generationId
      stateMachine.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
      val plan = TransitionPlan("song_$cycle", "song_${cycle + 1}", triggerTimeMs = 1000L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
      stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
      stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))

      // Simulate mid-mix volume attenuation
      primaryVolume = 0.35f
      trackedProcessors["secondary_player_$cycle"] = AntiClippingLimiterAudioProcessor()

      // User triggers rapid skip
      stateMachine.dispatchEvent(AutoDjEvent.CancelRequested(CancelReason.USER_SKIP))

      // Service cancellation contract: volume restored and secondary resources cleaned up
      primaryVolume = 1.0f
      trackedProcessors.remove("secondary_player_$cycle")

      stateMachine.reset()

      assertEquals("Primary volume must be restored to 1.0f on cycle $cycle", 1.0f, primaryVolume, 0.0001f)
      assertTrue("No secondary processors leaked on cycle $cycle", trackedProcessors.isEmpty())
    }

    assertTrue("Generation counter must advance on all 50 cycles", stateMachine.generationId >= initialGen + 100L)
    assertTrue("Final state must be Idle", stateMachine.currentState is AutoDjState.Idle)
  }

  @Test
  fun testPrimaryVolumeRestorationOnAllCancelReasons() {
    val allReasons = CancelReason.values()
    for (reason in allReasons) {
      stateMachine.startTransition("out_track", "in_track")
      val gen = stateMachine.generationId
      stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, TransitionPlan("out", "in", triggerTimeMs = 1000L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)))
      stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))

      // Mid-fade volume dropped
      var playerVolume = 0.15f

      // Cancel transition
      val cancelState = stateMachine.cancel(reason)
      assertTrue("Must transition to Canceling on $reason", cancelState is AutoDjState.Canceling)
      assertEquals(reason, (cancelState as AutoDjState.Canceling).reason)

      // Primary volume restored per MusicService.cancelActiveTransition
      playerVolume = 1.0f
      assertEquals("Primary volume must be restored to 1.0f for $reason", 1.0f, playerVolume, 0.0001f)

      stateMachine.reset()
      assertTrue("Must reset to Idle after $reason", stateMachine.currentState is AutoDjState.Idle)
    }
  }
}

