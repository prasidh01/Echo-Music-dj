package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutoDjStateMachineTest {

  private lateinit var stateMachine: AutoDjStateMachine

  private val outgoingProfile = TrackProfile(
    songId = "song_out",
    bpm = 120.0f,
    confidence = 0.90f,
    firstBeatOffsetMs = 0L,
    downbeatIntervalMs = 2000L,
    phraseBeatCount = 8,
    camelotKey = "8B",
  )

  private val incomingProfile = TrackProfile(
    songId = "song_in",
    bpm = 122.0f,
    confidence = 0.88f,
    firstBeatOffsetMs = 50L,
    downbeatIntervalMs = 1967L,
    phraseBeatCount = 8,
    camelotKey = "9B",
  )

  @Before
  fun setUp() {
    stateMachine = AutoDjStateMachine(initialGenerationId = 0L)
  }

  // T1-F10-01: Nominal Lifecycle State Flow
  @Test
  fun testNominalLifecycleStateFlow() {
    assertTrue(stateMachine.currentState is AutoDjState.Idle)

    // 1. Start (TrackChanged) -> Analyzing
    val state1 = stateMachine.dispatchEvent(AutoDjEvent.TrackChanged("song_out", "song_in"))
    assertTrue("Should be Analyzing", state1 is AutoDjState.Analyzing)
    val gen = stateMachine.generationId

    // 2. Profiles -> Preparing
    val state2 = stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile))
    assertTrue("Should be Preparing", state2 is AutoDjState.Preparing)

    // 3. Player buffer ready -> Planning
    val state3 = stateMachine.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
    assertTrue("Should be Planning", state3 is AutoDjState.Planning)

    // 4. Ready -> Scheduled
    val plan = TransitionPlan(
      outgoingSongId = "song_out",
      incomingSongId = "song_in",
      transitionType = TransitionType.PHRASE_ALIGNED_BLEND,
      triggerTimeMs = 180_000L,
      incomingStartMs = 0L,
      overlapDurationMs = 8_000L,
      generationId = gen,
    )
    val state4 = stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    assertTrue("Should be Scheduled", state4 is AutoDjState.Scheduled)

    // 5. Mix -> Mixing
    val state5 = stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue("Should be Mixing", state5 is AutoDjState.Mixing)

    // 5b. Mix progress updates
    val stateProgress = stateMachine.dispatchEvent(AutoDjEvent.MixProgress(gen, 0.5f))
    assertTrue("Should still be Mixing", stateProgress is AutoDjState.Mixing)
    assertEquals(0.5f, (stateProgress as AutoDjState.Mixing).progress, 0.001f)

    // 6. End -> Handoff
    val state6 = stateMachine.dispatchEvent(AutoDjEvent.MixCompleted(gen))
    assertTrue("Should be Handoff", state6 is AutoDjState.Handoff)

    // 7. Cleanup -> Idle
    val state7 = stateMachine.dispatchEvent(AutoDjEvent.Reset)
    assertTrue("Should be Idle", state7 is AutoDjState.Idle)
  }

  // T1-F10-02: Generation ID Increment on New Transition
  @Test
  fun testGenerationIdIncrementOnNewTransition() {
    val sm = AutoDjStateMachine(initialGenerationId = 10L)
    assertEquals(10L, sm.generationId)

    val nextState = sm.startTransition("track_1", "track_2")
    assertEquals(11L, sm.generationId)
    assertEquals(11L, nextState.generationId)
    assertTrue(nextState is AutoDjState.Analyzing)
  }

  // T1-F10-03: Stale Event Discard by Generation ID
  @Test
  fun testStaleEventDiscardByGenerationId() {
    val sm = AutoDjStateMachine(initialGenerationId = 10L)
    sm.startTransition("track_1", "track_2") // increments gen to 11
    val currentGen = sm.generationId
    assertEquals(11L, currentGen)

    val stalePlan = TransitionPlan(
      outgoingSongId = "track_1",
      incomingSongId = "track_2",
      triggerTimeMs = 100_000L,
      incomingStartMs = 0L,
      overlapDurationMs = 5000L,
      generationId = 10L, // Stale!
    )

    val stateAfterStale = sm.onPlanReady(stalePlan)
    assertTrue("State should remain Analyzing when stale plan arrived", stateAfterStale is AutoDjState.Analyzing)
    assertEquals(11L, stateAfterStale.generationId)
  }

  // T1-F10-04: Immutable StateFlow Emission
  @Test
  fun testImmutableStateFlowEmission() {
    assertEquals(AutoDjState.Idle(0L), stateMachine.state.value)

    stateMachine.startTransition("song_a", "song_b")
    assertEquals(stateMachine.currentState, stateMachine.state.value)
    assertTrue(stateMachine.state.value is AutoDjState.Analyzing)
  }

  // T1-F10-05: Generation Counter Monotonicity Invariant
  @Test
  fun testGenerationCounterMonotonicityInvariant() {
    var previousGen = stateMachine.generationId
    for (i in 1..20) {
      val nextGen = stateMachine.nextGeneration()
      assertTrue("Generation must strictly increase: $nextGen > $previousGen", nextGen > previousGen)
      previousGen = nextGen
    }
  }

  // T1-F11-01: Skip During Mixing Cancels Transition
  @Test
  fun testSkipDuringMixingCancelsTransition() {
    stateMachine.startTransition("song_out", "song_in")
    val gen = stateMachine.generationId
    stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile))
    stateMachine.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
    val plan = TransitionPlan("song_out", "song_in", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue(stateMachine.currentState is AutoDjState.Mixing)

    val cancelState = stateMachine.cancel(CancelReason.USER_SKIP)
    assertTrue(cancelState is AutoDjState.Canceling)
    assertEquals(CancelReason.USER_SKIP, (cancelState as AutoDjState.Canceling).reason)
    assertTrue(cancelState.generationId > gen)
  }

  // T1-F11-02: Seek During Scheduled Aborts Transition
  @Test
  fun testSeekDuringScheduledAbortsTransition() {
    stateMachine.startTransition("song_out", "song_in")
    val gen = stateMachine.generationId
    stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile))
    stateMachine.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
    val plan = TransitionPlan("song_out", "song_in", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    assertTrue(stateMachine.currentState is AutoDjState.Scheduled)

    val cancelState = stateMachine.cancel(CancelReason.USER_SEEK)
    assertTrue(cancelState is AutoDjState.Canceling)
    assertEquals(CancelReason.USER_SEEK, (cancelState as AutoDjState.Canceling).reason)

    val staleMix = stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue("Stale mix must not move from Canceling to Mixing", staleMix is AutoDjState.Canceling)
  }

  // T1-F11-03: Pause During Mixing Cancels Transition
  @Test
  fun testPauseDuringMixingCancelsTransition() {
    stateMachine.startTransition("song_out", "song_in")
    val gen = stateMachine.generationId
    stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile))
    stateMachine.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
    val plan = TransitionPlan("song_out", "song_in", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue(stateMachine.currentState is AutoDjState.Mixing)

    val cancelState = stateMachine.cancel(CancelReason.PAUSE)
    assertTrue(cancelState is AutoDjState.Canceling)
    assertEquals(CancelReason.PAUSE, (cancelState as AutoDjState.Canceling).reason)
  }

  // T1-F11-04: Queue Item Removal Aborts Preparation
  @Test
  fun testQueueItemRemovalAbortsPreparation() {
    stateMachine.startTransition("song_out", "song_in")
    val gen = stateMachine.generationId
    stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile))
    assertTrue(stateMachine.currentState is AutoDjState.Preparing)

    val cancelState = stateMachine.cancel(CancelReason.QUEUE_MUTATION)
    assertTrue(cancelState is AutoDjState.Canceling)
    assertEquals(CancelReason.QUEUE_MUTATION, (cancelState as AutoDjState.Canceling).reason)
  }

  // T1-F11-05: High-Frequency Rapid Skip Stress Run
  @Test
  fun testHighFrequencyRapidSkipStressRun() {
    val initialGen = stateMachine.generationId
    for (i in 1..50) {
      stateMachine.startTransition("track_$i", "track_${i + 1}")
      stateMachine.cancel(CancelReason.USER_SKIP)
      stateMachine.reset()
    }
    // Each cycle calls startTransition (+1 gen) and cancel (+1 gen)
    assertTrue("Generation counter must advance on all skips", stateMachine.generationId >= initialGen + 100L)
    assertTrue("Final state must be Idle", stateMachine.currentState is AutoDjState.Idle)
  }

  @Test
  fun testMixProgressClamping() {
    stateMachine.startTransition("song_out", "song_in")
    val gen = stateMachine.generationId
    stateMachine.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile))
    stateMachine.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
    val plan = TransitionPlan("song_out", "song_in", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    stateMachine.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    stateMachine.dispatchEvent(AutoDjEvent.MixTriggered(gen))

    val clampedLow = stateMachine.onMixProgress(-0.5f) as AutoDjState.Mixing
    assertEquals(0.0f, clampedLow.progress, 0.0001f)

    val clampedHigh = stateMachine.onMixProgress(1.8f) as AutoDjState.Mixing
    assertEquals(1.0f, clampedHigh.progress, 0.0001f)
  }

  @Test
  fun testConcurrentStressDispatches() {
    val threadCount = 8
    val iterationsPerThread = 100
    val latch = CountDownLatch(threadCount)
    val exceptionCount = AtomicInteger(0)

    for (t in 0 until threadCount) {
      Thread {
        try {
          for (i in 0 until iterationsPerThread) {
            stateMachine.startTransition("s1", "s2")
            stateMachine.cancel(CancelReason.USER_SKIP)
            stateMachine.reset()
          }
        } catch (e: Throwable) {
          exceptionCount.incrementAndGet()
        } finally {
          latch.countDown()
        }
      }.start()
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals(0, exceptionCount.get())
    assertTrue(stateMachine.generationId > (threadCount * iterationsPerThread))
  }
}
