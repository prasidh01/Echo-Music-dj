package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.FallbackReason
import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutoDjTelemetryRapidDispatchStressTest {

  private lateinit var stateMachine: AutoDjStateMachine
  private lateinit var telemetryFlow: MutableStateFlow<AutoDjTelemetry>
  private lateinit var testScope: CoroutineScope
  private lateinit var collectionJob: Job

  private val outProfile = TrackProfile(
    songId = "out_track_1",
    bpm = 126.0f,
    confidence = 0.92f,
    firstBeatOffsetMs = 100L,
    downbeatIntervalMs = 1904L,
    phraseBeatCount = 32,
    camelotKey = "8A",
  )

  private val inProfile = TrackProfile(
    songId = "in_track_2",
    bpm = 128.0f,
    confidence = 0.89f,
    firstBeatOffsetMs = 200L,
    downbeatIntervalMs = 1875L,
    phraseBeatCount = 32,
    camelotKey = "9A",
  )

  @Before
  fun setUp() {
    stateMachine = AutoDjStateMachine(initialGenerationId = 0L)
    telemetryFlow = MutableStateFlow(AutoDjTelemetry())
    testScope = CoroutineScope(Dispatchers.Default + Job())

    // Mirror MusicService telemetry collector logic exactly
    collectionJob = stateMachine.state.onEach { st ->
      val current = telemetryFlow.value
      telemetryFlow.value = when (st) {
        is AutoDjState.Idle -> AutoDjTelemetry(
          state = "Idle",
          generationId = st.generationId,
          autoDjState = st,
        )
        is AutoDjState.Analyzing -> current.copy(
          state = "Analyzing",
          generationId = st.generationId,
          autoDjState = st,
        )
        is AutoDjState.Preparing -> current.copy(
          state = "Preparing",
          generationId = st.generationId,
          outgoingBpm = st.outgoingProfile.bpm,
          outgoingCamelotKey = st.outgoingProfile.camelotKey,
          incomingBpm = st.incomingProfile?.bpm,
          incomingCamelotKey = st.incomingProfile?.camelotKey,
          autoDjState = st,
        )
        is AutoDjState.Planning -> current.copy(
          state = "Planning",
          generationId = st.generationId,
          outgoingBpm = st.outgoingProfile.bpm,
          outgoingCamelotKey = st.outgoingProfile.camelotKey,
          incomingBpm = st.incomingProfile.bpm,
          incomingCamelotKey = st.incomingProfile.camelotKey,
          autoDjState = st,
        )
        is AutoDjState.Scheduled -> current.copy(
          state = "Scheduled",
          generationId = st.generationId,
          tempoRatio = st.plan.tempoRatio,
          harmonicScore = st.plan.harmonicScore,
          transitionType = st.plan.transitionType,
          fallbackReason = st.plan.fallbackReason,
          confidence = st.plan.confidence,
          autoDjState = st,
        )
        is AutoDjState.Mixing -> current.copy(
          state = "Mixing",
          generationId = st.generationId,
          progress = st.progress,
          tempoRatio = st.plan.tempoRatio,
          harmonicScore = st.plan.harmonicScore,
          transitionType = st.plan.transitionType,
          fallbackReason = st.plan.fallbackReason,
          confidence = st.plan.confidence,
          autoDjState = st,
        )
        is AutoDjState.Handoff -> current.copy(
          state = "Handoff",
          generationId = st.generationId,
          progress = 1.0f,
          autoDjState = st,
        )
        is AutoDjState.Canceling -> current.copy(
          state = "Canceling",
          generationId = st.generationId,
          autoDjState = st,
        )
      }
    }.launchIn(testScope)
  }

  @After
  fun tearDown() {
    testScope.cancel()
  }

  // 1. Rapid Full-Lifecycle Sequential Dispatch Storm
  @Test
  fun testRapidLifecycleSequentialTelemetryTransitions() {
    val cycleCount = 100
    for (i in 1..cycleCount) {
      // 1. Start -> Analyzing
      val s1 = stateMachine.startTransition("out_$i", "in_$i")
      assertTrue("State must be Analyzing", s1 is AutoDjState.Analyzing)
      val gen = s1.generationId

      // 2. Profiles -> Preparing
      val s2 = stateMachine.onProfilesAvailable(outProfile, inProfile)
      assertTrue("State must be Preparing", s2 is AutoDjState.Preparing)

      // 3. Prepared -> Planning
      val s3 = stateMachine.onPlayerPrepared()
      assertTrue("State must be Planning", s3 is AutoDjState.Planning)

      // 4. Plan -> Scheduled
      val plan = TransitionPlan(
        outgoingSongId = "out_$i",
        incomingSongId = "in_$i",
        transitionType = TransitionType.PHRASE_ALIGNED_BLEND,
        triggerTimeMs = 120_000L,
        incomingStartMs = 0L,
        overlapDurationMs = 8_000L,
        generationId = gen,
        tempoRatio = 1.015f,
        harmonicScore = 0.95f,
        confidence = 0.90f,
      )
      val s4 = stateMachine.onPlanReady(plan)
      assertTrue("State must be Scheduled", s4 is AutoDjState.Scheduled)

      // 5. Mix triggered -> Mixing
      val s5 = stateMachine.onMixTriggered()
      assertTrue("State must be Mixing", s5 is AutoDjState.Mixing)

      // 6. Progress increments
      for (p in listOf(0.1f, 0.3f, 0.5f, 0.8f, 1.0f)) {
        val sp = stateMachine.onMixProgress(p)
        assertTrue("Must be Mixing", sp is AutoDjState.Mixing)
        assertEquals(p, (sp as AutoDjState.Mixing).progress, 0.001f)
      }

      // 7. Completed -> Handoff
      val s6 = stateMachine.onMixCompleted()
      assertTrue("State must be Handoff", s6 is AutoDjState.Handoff)

      // 8. Reset -> Idle
      val s7 = stateMachine.reset()
      assertTrue("State must be Idle", s7 is AutoDjState.Idle)
    }

    // Verify final state
    val finalState = stateMachine.currentState
    assertTrue("Final state must be Idle", finalState is AutoDjState.Idle)
    assertTrue("Generation counter must have advanced by at least 100", stateMachine.generationId >= 100L)
  }

  // 2. Rapid Concurrent User Skips & Discontinuity Storm
  @Test
  fun testRapidConcurrentSkipsUnderActiveTransitions() = runBlocking {
    val threadCount = 10
    val cyclesPerThread = 50
    val startGate = CountDownLatch(1)
    val doneLatch = CountDownLatch(threadCount)
    val cancelCount = AtomicInteger(0)

    for (t in 0 until threadCount) {
      testScope.launch(Dispatchers.Default) {
        startGate.await()
        try {
          for (c in 0 until cyclesPerThread) {
            val s1 = stateMachine.startTransition("song_${t}_$c")
            val currentGen = s1.generationId

            stateMachine.onProfilesAvailable(outProfile, inProfile)
            stateMachine.onPlayerPrepared()

            // Interleaved cancellation
            if (c % 2 == 0) {
              val cancelState = stateMachine.cancel(CancelReason.USER_SKIP)
              if (cancelState is AutoDjState.Canceling) {
                cancelCount.incrementAndGet()
              }
            } else {
              val seekCancel = stateMachine.cancel(CancelReason.USER_SEEK)
              if (seekCancel is AutoDjState.Canceling) {
                cancelCount.incrementAndGet()
              }
            }

            // Stale event dispatched with old generation ID must be safely dropped
            val stalePlan = TransitionPlan(
              outgoingSongId = "stale_out",
              incomingSongId = "stale_in",
              generationId = currentGen - 1,
            )
            stateMachine.onPlanReady(stalePlan)
          }
        } finally {
          doneLatch.countDown()
        }
      }
    }

    startGate.countDown()
    assertTrue("All concurrent dispatchers must finish within 10s", doneLatch.await(10, TimeUnit.SECONDS))
    assertTrue("At least some transitions must have been cancelled", cancelCount.get() > 0)

    // State machine must remain operable and consistent
    val resetState = stateMachine.reset()
    assertEquals("Must reset safely to Idle", AutoDjState.Idle(stateMachine.generationId), resetState)
  }

  // 3. Stale Generation Event Quarantine Invariant
  @Test
  fun testStaleGenerationQuarantineInvariant() {
    val s1 = stateMachine.startTransition("out_1", "in_1")
    val validGen = s1.generationId

    // Move to Preparing
    stateMachine.onProfilesAvailable(outProfile, inProfile)

    // Stale ProfileAvailable from older generation (validGen - 1)
    val stateBeforeStale = stateMachine.currentState
    val staleResult = stateMachine.dispatchEvent(
      AutoDjEvent.ProfileAvailable(validGen - 1, outProfile, inProfile)
    )
    assertEquals("Stale event must not alter state", stateBeforeStale, staleResult)

    // Move to Planning
    stateMachine.onPlayerPrepared()

    // Stale PlayerPrepared from older generation
    val stalePlayerResult = stateMachine.dispatchEvent(
      AutoDjEvent.PlayerPrepared(validGen - 1)
    )
    assertEquals("Stale PlayerPrepared must not alter state", stateMachine.currentState, stalePlayerResult)

    // Schedule valid plan
    val validPlan = TransitionPlan(
      outgoingSongId = "out_1",
      incomingSongId = "in_1",
      generationId = validGen,
    )
    stateMachine.onPlanReady(validPlan)
    assertTrue("Should be Scheduled", stateMachine.currentState is AutoDjState.Scheduled)

    // Stale PlanReady from older generation
    val stalePlan = TransitionPlan(
      outgoingSongId = "stale_out",
      incomingSongId = "stale_in",
      generationId = validGen - 1,
    )
    val stalePlanResult = stateMachine.dispatchEvent(AutoDjEvent.PlanReady(validGen - 1, stalePlan))
    assertEquals("Stale PlanReady must not alter state", stateMachine.currentState, stalePlanResult)

    // Trigger Mix
    stateMachine.onMixTriggered()
    assertTrue("Should be Mixing", stateMachine.currentState is AutoDjState.Mixing)

    // Stale MixProgress from older generation
    val staleProgressResult = stateMachine.dispatchEvent(
      AutoDjEvent.MixProgress(validGen - 1, 0.99f)
    )
    assertEquals("Stale MixProgress must be dropped", 0.0f, (stateMachine.currentState as AutoDjState.Mixing).progress, 0.001f)

    // Stale MixCompleted from older generation
    val staleCompletedResult = stateMachine.dispatchEvent(
      AutoDjEvent.MixCompleted(validGen - 1)
    )
    assertTrue("Must remain Mixing, not Handoff", stateMachine.currentState is AutoDjState.Mixing)
  }

  // 4. High-Frequency MixProgress Clamping and Boundary Resilience
  @Test
  fun testMixProgressBoundaryAndClampingResilience() {
    stateMachine.startTransition("out", "in")
    val gen = stateMachine.generationId
    stateMachine.onProfilesAvailable(outProfile, inProfile)
    stateMachine.onPlayerPrepared()
    stateMachine.onPlanReady(TransitionPlan("out", "in", generationId = gen))
    stateMachine.onMixTriggered()

    // 1. Negative progress must clamp to 0.0f
    val stateNeg = stateMachine.onMixProgress(-1.5f)
    assertEquals(0.0f, (stateNeg as AutoDjState.Mixing).progress, 0.001f)

    // 2. Excessive progress must clamp to 1.0f
    val stateOver = stateMachine.onMixProgress(2.5f)
    assertEquals(1.0f, (stateOver as AutoDjState.Mixing).progress, 0.001f)

    // 3. Exact boundaries 0.0f and 1.0f
    val stateZero = stateMachine.onMixProgress(0.0f)
    assertEquals(0.0f, (stateZero as AutoDjState.Mixing).progress, 0.001f)

    val stateOne = stateMachine.onMixProgress(1.0f)
    assertEquals(1.0f, (stateOne as AutoDjState.Mixing).progress, 0.001f)
  }

  // 5. Cancellation Reason Extraction and Preserved Previous State
  @Test
  fun testCancellationPreservesPreviousStateAndReason() {
    val reasons = listOf(
      CancelReason.USER_SKIP,
      CancelReason.USER_SEEK,
      CancelReason.PAUSE,
      CancelReason.QUEUE_MUTATION,
      CancelReason.TIMEOUT,
      CancelReason.ERROR,
      CancelReason.RESET,
    )

    for (reason in reasons) {
      stateMachine.startTransition("out", "in")
      val gen = stateMachine.generationId
      stateMachine.onProfilesAvailable(outProfile, inProfile)
      stateMachine.onPlayerPrepared()
      stateMachine.onPlanReady(TransitionPlan("out", "in", generationId = gen))
      stateMachine.onMixTriggered()

      val beforeCancel = stateMachine.currentState
      assertTrue(beforeCancel is AutoDjState.Mixing)

      val cancelState = stateMachine.cancel(reason)
      assertTrue("State must be Canceling", cancelState is AutoDjState.Canceling)
      val canceling = cancelState as AutoDjState.Canceling
      assertEquals(reason, canceling.reason)
      assertEquals(beforeCancel, canceling.previousState)
      assertTrue("Generation ID must have incremented on cancel", canceling.generationId > gen)

      stateMachine.reset()
    }
  }

  // 6. Telemetry State Invariants & Formatter Integration
  @Test
  fun testTelemetryStateInvariantsAndFormatterSync() {
    stateMachine.startTransition("out", "in")
    val gen = stateMachine.generationId
    stateMachine.onProfilesAvailable(outProfile, inProfile)
    stateMachine.onPlayerPrepared()

    val plan = TransitionPlan(
      outgoingSongId = "out",
      incomingSongId = "in",
      transitionType = TransitionType.PHRASE_ALIGNED_BLEND,
      generationId = gen,
      tempoRatio = 1.016f,
      harmonicScore = 0.95f,
      confidence = 0.91f,
    )
    stateMachine.onPlanReady(plan)
    stateMachine.onMixTriggered()
    stateMachine.onMixProgress(0.65f)

    val current = stateMachine.currentState as AutoDjState.Mixing
    val progressFormatted = AutoDjTelemetryFormatter.format(current.progress)
    assertEquals("Mixing: 65%", progressFormatted)

    val keyFormatted = AutoDjTelemetryFormatter.format("8A", "9A", 1)
    assertEquals("8A -> 9A (+1 step)", keyFormatted)

    // Formatter handles nulls safely
    val nullKeyFormatted = AutoDjTelemetryFormatter.format(null, "9A", 1)
    assertEquals("BPM: -- | Key: --", nullKeyFormatted)
  }
}
