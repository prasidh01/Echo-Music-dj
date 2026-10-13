package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import echo.music.domain.autodj.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class AutoDjStateMachineStressTest {

  private lateinit var sm: AutoDjStateMachine

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
    sm = AutoDjStateMachine(initialGenerationId = 0L)
  }

  // 1. Generation ID Monotonicity Stress under Heavy Concurrency
  @Test
  fun testGenerationIdMonotonicityUnderHeavyConcurrency() {
    val threadCount = 16
    val incrementsPerThread = 5000
    val totalIncrements = threadCount * incrementsPerThread
    val generatedIds = ConcurrentHashMap.newKeySet<Long>()
    val latch = CountDownLatch(threadCount)
    val startGate = CountDownLatch(1)

    for (t in 0 until threadCount) {
      Thread {
        startGate.await()
        try {
          for (i in 0 until incrementsPerThread) {
            val id = sm.nextGeneration()
            generatedIds.add(id)
          }
        } finally {
          latch.countDown()
        }
      }.start()
    }

    startGate.countDown()
    assertTrue("Concurrent increments must finish within 10s", latch.await(10, TimeUnit.SECONDS))
    assertEquals("All generated IDs must be unique (no duplicates)", totalIncrements, generatedIds.size)
    assertEquals("Final generation ID must equal total increments", totalIncrements.toLong(), sm.generationId)
  }

  // 2. Generation ID Wraparound & Overflow Resilience
  @Test
  fun testGenerationIdOverflowResilience() {
    val overflowSm = AutoDjStateMachine(initialGenerationId = Long.MAX_VALUE - 2)
    assertEquals(Long.MAX_VALUE - 2, overflowSm.generationId)

    // First transition: Long.MAX_VALUE - 1
    val s1 = overflowSm.startTransition("s1", "s2")
    assertEquals(Long.MAX_VALUE - 1, overflowSm.generationId)
    assertEquals(Long.MAX_VALUE - 1, s1.generationId)

    // Cancel: Long.MAX_VALUE
    val s2 = overflowSm.cancel(CancelReason.USER_SKIP)
    assertEquals(Long.MAX_VALUE, overflowSm.generationId)
    assertEquals(Long.MAX_VALUE, s2.generationId)

    // Next transition: Wraps around to Long.MIN_VALUE
    val s3 = overflowSm.startTransition("s3", "s4")
    assertEquals(Long.MIN_VALUE, overflowSm.generationId)
    assertEquals(Long.MIN_VALUE, s3.generationId)

    // Stale event with Long.MAX_VALUE must be dropped
    val stalePlan = TransitionPlan("s3", "s4", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = Long.MAX_VALUE)
    val stateAfterStale = overflowSm.onPlanReady(stalePlan)
    assertTrue("State must remain Analyzing when stale plan arrived across wraparound", stateAfterStale is AutoDjState.Analyzing)
    assertEquals(Long.MIN_VALUE, stateAfterStale.generationId)

    // Matching event with Long.MIN_VALUE must be accepted
    val validPlan = TransitionPlan("s3", "s4", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = Long.MIN_VALUE)
    val stateAfterValid = overflowSm.onPlanReady(validPlan)
    assertTrue("State must become Scheduled when valid plan with Long.MIN_VALUE arrives", stateAfterValid is AutoDjState.Scheduled)
    assertEquals(Long.MIN_VALUE, stateAfterValid.generationId)
  }

  // 3. Rapid Out-of-Order Event Dispatches
  @Test
  fun testRapidOutOfOrderEventDispatches() {
    // A. Reversed order dispatch on fresh Idle state machine
    val plan = TransitionPlan("song_out", "song_in", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = 0L)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixCompleted(0L)) is AutoDjState.Idle)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixProgress(0L, 0.5f)) is AutoDjState.Idle)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixTriggered(0L)) is AutoDjState.Idle)
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlanReady(0L, plan)) is AutoDjState.Idle)
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlayerPrepared(0L)) is AutoDjState.Idle)
    assertTrue(sm.dispatchEvent(AutoDjEvent.ProfileAvailable(0L, outgoingProfile, incomingProfile)) is AutoDjState.Idle)
    assertEquals("Should still be Idle with gen 0", AutoDjState.Idle(0L), sm.currentState)

    // B. Out-of-order events during Analyzing
    sm.startTransition("song_out", "song_in")
    val gen = sm.generationId
    assertEquals(1L, gen)
    assertTrue(sm.currentState is AutoDjState.Analyzing)

    // MixCompleted, MixProgress, MixTriggered, PlayerPrepared should all be rejected in Analyzing
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixCompleted(gen)) is AutoDjState.Analyzing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixProgress(gen, 0.5f)) is AutoDjState.Analyzing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixTriggered(gen)) is AutoDjState.Analyzing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlayerPrepared(gen)) is AutoDjState.Analyzing)
    assertEquals(1L, sm.currentState.generationId)

    // Move to Preparing
    sm.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile))
    assertTrue(sm.currentState is AutoDjState.Preparing)

    // C. Out-of-order events during Preparing
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixCompleted(gen)) is AutoDjState.Preparing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixProgress(gen, 0.5f)) is AutoDjState.Preparing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixTriggered(gen)) is AutoDjState.Preparing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile)) is AutoDjState.Preparing) // Duplicate

    // Move to Planning
    sm.dispatchEvent(AutoDjEvent.PlayerPrepared(gen))
    assertTrue(sm.currentState is AutoDjState.Planning)

    // D. Out-of-order events during Planning
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixCompleted(gen)) is AutoDjState.Planning)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixProgress(gen, 0.5f)) is AutoDjState.Planning)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixTriggered(gen)) is AutoDjState.Planning)
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlayerPrepared(gen)) is AutoDjState.Planning) // Duplicate

    // Move to Scheduled
    val validPlan = TransitionPlan("song_out", "song_in", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    sm.dispatchEvent(AutoDjEvent.PlanReady(gen, validPlan))
    assertTrue(sm.currentState is AutoDjState.Scheduled)

    // E. Out-of-order events during Scheduled
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixCompleted(gen)) is AutoDjState.Scheduled)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixProgress(gen, 0.5f)) is AutoDjState.Scheduled)
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlayerPrepared(gen)) is AutoDjState.Scheduled)
    assertTrue(sm.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile)) is AutoDjState.Scheduled)

    // Move to Mixing
    sm.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue(sm.currentState is AutoDjState.Mixing)

    // F. Out-of-order events during Mixing
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixTriggered(gen)) is AutoDjState.Mixing) // Duplicate
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlanReady(gen, validPlan)) is AutoDjState.Mixing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlayerPrepared(gen)) is AutoDjState.Mixing)
    assertTrue(sm.dispatchEvent(AutoDjEvent.ProfileAvailable(gen, outgoingProfile, incomingProfile)) is AutoDjState.Mixing)

    // Move to Handoff
    sm.dispatchEvent(AutoDjEvent.MixCompleted(gen))
    assertTrue(sm.currentState is AutoDjState.Handoff)

    // G. Out-of-order events during Handoff
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixCompleted(gen)) is AutoDjState.Handoff)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixTriggered(gen)) is AutoDjState.Handoff)
    assertTrue(sm.dispatchEvent(AutoDjEvent.MixProgress(gen, 0.8f)) is AutoDjState.Handoff)
    assertTrue(sm.dispatchEvent(AutoDjEvent.PlanReady(gen, validPlan)) is AutoDjState.Handoff)

    // Reset to Idle
    sm.dispatchEvent(AutoDjEvent.Reset)
    assertTrue(sm.currentState is AutoDjState.Idle)
  }

  // 4. Stale and Future Generation Rejections
  @Test
  fun testStaleAndFutureGenerationRejections() {
    sm.startTransition("s1", "s2")
    val currentGen = sm.generationId // 1L
    val plan = TransitionPlan("s1", "s2", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = currentGen)

    val testGens = listOf(
      0L, -1L, -100L, 2L, 5L, 100L, Long.MAX_VALUE, Long.MIN_VALUE
    )

    for (testGen in testGens) {
      val stateBefore = sm.currentState

      sm.dispatchEvent(AutoDjEvent.ProfileAvailable(testGen, outgoingProfile, incomingProfile))
      assertEquals("ProfileAvailable with mismatched gen $testGen must be dropped", stateBefore, sm.currentState)

      sm.dispatchEvent(AutoDjEvent.PlayerPrepared(testGen))
      assertEquals("PlayerPrepared with mismatched gen $testGen must be dropped", stateBefore, sm.currentState)

      sm.dispatchEvent(AutoDjEvent.PlanReady(testGen, plan.copy(generationId = testGen)))
      assertEquals("PlanReady with mismatched gen $testGen must be dropped", stateBefore, sm.currentState)

      sm.dispatchEvent(AutoDjEvent.MixTriggered(testGen))
      assertEquals("MixTriggered with mismatched gen $testGen must be dropped", stateBefore, sm.currentState)

      sm.dispatchEvent(AutoDjEvent.MixProgress(testGen, 0.5f))
      assertEquals("MixProgress with mismatched gen $testGen must be dropped", stateBefore, sm.currentState)

      sm.dispatchEvent(AutoDjEvent.MixCompleted(testGen))
      assertEquals("MixCompleted with mismatched gen $testGen must be dropped", stateBefore, sm.currentState)
    }
  }

  // 5. Rapid Cancellation Loops & Idempotency
  @Test
  fun testRapidCancellationLoopsIdempotency() {
    // In Idle: multiple cancel calls do not increment generation
    val gen0 = sm.generationId
    for (i in 1..50) {
      val state = sm.cancel(CancelReason.USER_SKIP)
      assertTrue("Cancel in Idle should return Idle", state is AutoDjState.Idle)
      assertEquals("Generation ID must remain unchanged when canceling Idle", gen0, sm.generationId)
    }

    // Start transition
    sm.startTransition("t1", "t2")
    val gen1 = sm.generationId
    assertEquals(gen0 + 1L, gen1)

    // First cancel increments gen and moves to Canceling
    val c1 = sm.cancel(CancelReason.USER_SKIP)
    assertTrue("First cancel moves to Canceling", c1 is AutoDjState.Canceling)
    val cancelGen = sm.generationId
    assertEquals(gen1 + 1L, cancelGen)

    // Subsequent cancels while already in Canceling are idempotent: do not advance gen
    for (i in 1..50) {
      val cNext = sm.cancel(CancelReason.PAUSE)
      assertTrue("Subsequent cancels stay in Canceling", cNext is AutoDjState.Canceling)
      assertEquals("Subsequent cancels must not advance generation", cancelGen, sm.generationId)
      assertEquals("Reason remains from first cancel", CancelReason.USER_SKIP, (cNext as AutoDjState.Canceling).reason)
    }

    // Reset moves to Idle with same generation
    val r1 = sm.reset()
    assertTrue(r1 is AutoDjState.Idle)
    assertEquals(cancelGen, sm.generationId)
  }

  // 6. Target Generation ID Specific Cancellation Guard
  @Test
  fun testTargetGenerationIdSpecificCancellationGuard() {
    sm.startTransition("song1", "song2")
    val activeGen = sm.generationId
    assertTrue(sm.currentState is AutoDjState.Analyzing)

    // Stale cancel targeting generation 0 must be ignored
    val staleCancel = sm.dispatchEvent(AutoDjEvent.CancelRequested(CancelReason.USER_SKIP, targetGenerationId = 0L))
    assertTrue("Stale cancel targeting gen 0 must be dropped", staleCancel is AutoDjState.Analyzing)
    assertEquals(activeGen, sm.generationId)

    // Future cancel targeting generation 999 must be ignored
    val futureCancel = sm.dispatchEvent(AutoDjEvent.CancelRequested(CancelReason.USER_SKIP, targetGenerationId = 999L))
    assertTrue("Future cancel targeting gen 999 must be dropped", futureCancel is AutoDjState.Analyzing)
    assertEquals(activeGen, sm.generationId)

    // Matching cancel targeting activeGen must succeed
    val matchingCancel = sm.dispatchEvent(AutoDjEvent.CancelRequested(CancelReason.USER_SEEK, targetGenerationId = activeGen))
    assertTrue("Matching cancel must transition to Canceling", matchingCancel is AutoDjState.Canceling)
    assertEquals(CancelReason.USER_SEEK, (matchingCancel as AutoDjState.Canceling).reason)
    assertTrue("Generation counter must advance on valid cancel", sm.generationId > activeGen)
  }

  // 7. Rapid Sequential Transition & Cancellation Cycles
  @Test
  fun test100RapidSequentialTransitionAndCancellationCycles() {
    val initialGen = sm.generationId
    for (i in 1..100) {
      val start = sm.startTransition("song_$i", "song_${i + 1}")
      assertTrue(start is AutoDjState.Analyzing)
      assertEquals(initialGen + (i * 2 - 1).toLong(), sm.generationId)

      val cancel = sm.cancel(CancelReason.USER_SKIP)
      assertTrue(cancel is AutoDjState.Canceling)
      assertEquals(initialGen + (i * 2).toLong(), sm.generationId)

      val reset = sm.reset()
      assertTrue(reset is AutoDjState.Idle)
      assertEquals(initialGen + (i * 2).toLong(), sm.generationId)
    }

    assertEquals(initialGen + 200L, sm.generationId)
    assertTrue(sm.currentState is AutoDjState.Idle)
  }

  // 8. Direct Skip Without Reset (Canceling -> Analyzing)
  @Test
  fun testDirectSkipWithoutReset() {
    for (i in 1..50) {
      sm.startTransition("s_$i", "s_${i + 1}")
      assertTrue(sm.currentState is AutoDjState.Analyzing)

      sm.cancel(CancelReason.USER_SKIP)
      assertTrue(sm.currentState is AutoDjState.Canceling)

      // Start new transition immediately WITHOUT calling reset()
      sm.startTransition("s_${i + 1}", "s_${i + 2}")
      assertTrue("Must transition from Canceling directly to Analyzing on new track", sm.currentState is AutoDjState.Analyzing)
    }
  }

  // 9. MixProgress Clamping and Extreme Values
  @Test
  fun testMixProgressClampingAndExtremeValues() {
    sm.startTransition("s1", "s2")
    val gen = sm.generationId
    val plan = TransitionPlan("s1", "s2", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = gen)
    sm.dispatchEvent(AutoDjEvent.PlanReady(gen, plan))
    sm.dispatchEvent(AutoDjEvent.MixTriggered(gen))
    assertTrue(sm.currentState is AutoDjState.Mixing)

    val extremes = listOf(
      -1000.0f to 0.0f,
      -0.0001f to 0.0f,
      0.0f to 0.0f,
      0.5f to 0.5f,
      1.0f to 1.0f,
      1.0001f to 1.0f,
      1000.0f to 1.0f,
      Float.MAX_VALUE to 1.0f,
      -Float.MAX_VALUE to 0.0f,
    )

    for ((input, expected) in extremes) {
      val state = sm.onMixProgress(input) as AutoDjState.Mixing
      assertEquals("Progress $input should clamp to $expected", expected, state.progress, 0.0001f)
    }
  }

  // 10. CancelReason String Parsing Completeness
  @Test
  fun testCancelReasonStringParsingCompleteness() {
    val mappings = mapOf(
      "userskip" to CancelReason.USER_SKIP,
      "USER_SKIP" to CancelReason.USER_SKIP,
      "skip" to CancelReason.USER_SKIP,
      "seektonext" to CancelReason.USER_SKIP,
      "seektoprevious" to CancelReason.USER_SKIP,
      "seek" to CancelReason.USER_SEEK,
      "user_seek" to CancelReason.USER_SEEK,
      "seekdiscontinuity" to CancelReason.USER_SEEK,
      "pause" to CancelReason.PAUSE,
      "user_pause" to CancelReason.PAUSE,
      "queuemutation" to CancelReason.QUEUE_MUTATION,
      "queue_mutation" to CancelReason.QUEUE_MUTATION,
      "queueremove" to CancelReason.QUEUE_MUTATION,
      "queueclear" to CancelReason.QUEUE_MUTATION,
      "skipqueue" to CancelReason.QUEUE_MUTATION,
      "timeout" to CancelReason.TIMEOUT,
      "TIMEOUT" to CancelReason.TIMEOUT,
      "stalegeneration" to CancelReason.STALE_GENERATION,
      "stale_generation" to CancelReason.STALE_GENERATION,
      "reset" to CancelReason.RESET,
      "RESET" to CancelReason.RESET,
      "unknown_random_reason" to CancelReason.ERROR,
      "" to CancelReason.ERROR,
    )

    for ((str, expectedReason) in mappings) {
      assertEquals("CancelReason for '$str' must match", expectedReason, CancelReason.fromString(str))
    }
  }

  // 11. Multi-Threaded Concurrent Torture Test
  @Test
  fun testMultiThreadedConcurrentTortureTest() {
    val threadCount = 16
    val stopFlag = AtomicBoolean(false)
    val operationCount = AtomicInteger(0)
    val errorCount = AtomicInteger(0)
    val latch = CountDownLatch(threadCount)

    val threads = (0 until threadCount).map { t ->
      Thread {
        val random = Random(t + 42)
        try {
          while (!stopFlag.get()) {
            val op = random.nextInt(10)
            val currentGen = sm.generationId
            val plan = TransitionPlan("s1", "s2", triggerTimeMs = 100L, incomingStartMs = 0L, overlapDurationMs = 5000L, generationId = currentGen)

            when (op) {
              0 -> sm.startTransition("t_$t", "t_${t + 1}")
              1 -> sm.onProfilesAvailable(outgoingProfile, incomingProfile)
              2 -> sm.onPlayerPrepared()
              3 -> sm.onPlanReady(plan)
              4 -> sm.onMixTriggered()
              5 -> sm.onMixProgress(random.nextFloat())
              6 -> sm.onMixCompleted()
              7 -> sm.cancel(CancelReason.USER_SKIP)
              8 -> sm.reset()
              9 -> sm.nextGeneration()
            }

            // Invariant check
            synchronized(sm) {
              val (snapshotGen, stateSnapshot) = sm.snapshot()
              val flowSnapshot = sm.state.value
              if (snapshotGen < stateSnapshot.generationId) {
                println("GENERATION INVARIANT VIOLATION: sm.gen=$snapshotGen < state.gen=${stateSnapshot.generationId}")
                errorCount.incrementAndGet()
              }
              if (stateSnapshot != flowSnapshot) {
                println("FLOW INVARIANT VIOLATION: stateSnapshot=$stateSnapshot != flowSnapshot=$flowSnapshot")
                errorCount.incrementAndGet()
              }
            }

            operationCount.incrementAndGet()
          }
        } catch (e: Throwable) {
          println("EXCEPTION IN TORTURE: ${e.message}")
          e.printStackTrace()
          errorCount.incrementAndGet()
        } finally {
          latch.countDown()
        }
      }
    }

    threads.forEach { it.start() }
    Thread.sleep(2000) // Torture for 2 seconds
    stopFlag.set(true)

    assertTrue("All threads must terminate cleanly", latch.await(5, TimeUnit.SECONDS))
    assertEquals("Must have 0 invariant violations or crashes under concurrent torture", 0, errorCount.get())
    assertTrue("Must have executed thousands of operations", operationCount.get() > 5000)
    assertTrue("State machine must be in a valid AutoDjState", sm.currentState is AutoDjState)
  }
}
