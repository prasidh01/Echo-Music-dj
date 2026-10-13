package echo.music.iad1tya.playback.autodj

import echo.music.domain.autodj.model.DjPreset
import echo.music.domain.autodj.model.TrackProfile
import echo.music.domain.autodj.model.TransitionPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Cancellation reasons for Auto DJ transitions.
 */
enum class CancelReason {
  USER_SKIP,
  USER_SEEK,
  PAUSE,
  QUEUE_MUTATION,
  ERROR,
  TIMEOUT,
  STALE_GENERATION,
  RESET;

  companion object {
    fun fromString(value: String): CancelReason = when (value.lowercase()) {
      "seektonext", "seektoprevious", "userskip", "skip", "user_skip" -> USER_SKIP
      "seek", "userseek", "user_seek", "seekdiscontinuity" -> USER_SEEK
      "pause", "userpause", "user_pause" -> PAUSE
      "queuemutation", "queue_mutation", "queueremove", "queueclear", "skipqueue" -> QUEUE_MUTATION
      "timeout" -> TIMEOUT
      "stalegeneration", "stale_generation" -> STALE_GENERATION
      "reset" -> RESET
      else -> ERROR
    }
  }
}

/**
 * Formal state hierarchy for Auto DJ transitions.
 */
sealed interface AutoDjState {
  val generationId: Long

  data class Idle(
    override val generationId: Long = 0L,
  ) : AutoDjState

  data class Analyzing(
    override val generationId: Long,
    val outgoingSongId: String,
    val incomingSongId: String? = null,
  ) : AutoDjState

  data class Preparing(
    override val generationId: Long,
    val outgoingProfile: TrackProfile,
    val incomingSongId: String,
    val incomingProfile: TrackProfile? = null,
  ) : AutoDjState

  data class Planning(
    override val generationId: Long,
    val outgoingProfile: TrackProfile,
    val incomingProfile: TrackProfile,
    val preset: DjPreset = DjPreset.SEAMLESS,
  ) : AutoDjState

  data class Scheduled(
    override val generationId: Long,
    val plan: TransitionPlan,
  ) : AutoDjState

  data class Mixing(
    override val generationId: Long,
    val plan: TransitionPlan,
    val progress: Float = 0.0f,
  ) : AutoDjState

  data class Handoff(
    override val generationId: Long,
    val plan: TransitionPlan,
  ) : AutoDjState

  data class Canceling(
    override val generationId: Long,
    val reason: CancelReason,
    val previousState: AutoDjState,
  ) : AutoDjState
}

/**
 * Discrete events dispatched to [AutoDjStateMachine].
 */
sealed interface AutoDjEvent {
  data class TrackChanged(
    val outgoingSongId: String,
    val incomingSongId: String? = null,
  ) : AutoDjEvent

  data class ProfileAvailable(
    val generationId: Long,
    val outgoingProfile: TrackProfile,
    val incomingProfile: TrackProfile? = null,
  ) : AutoDjEvent

  data class PlayerPrepared(
    val generationId: Long,
  ) : AutoDjEvent

  data class PlanReady(
    val generationId: Long,
    val plan: TransitionPlan,
  ) : AutoDjEvent

  data class MixTriggered(
    val generationId: Long,
  ) : AutoDjEvent

  data class MixProgress(
    val generationId: Long,
    val progress: Float,
  ) : AutoDjEvent

  data class MixCompleted(
    val generationId: Long,
  ) : AutoDjEvent

  data class CancelRequested(
    val reason: CancelReason,
    val targetGenerationId: Long? = null,
  ) : AutoDjEvent {
    constructor(reasonString: String, targetGenerationId: Long? = null) : this(
      CancelReason.fromString(reasonString),
      targetGenerationId,
    )
  }

  data object Reset : AutoDjEvent
}

/**
 * Thread-safe, generation-guarded state machine for Auto DJ transitions.
 * Governs the lifecycle from track analysis to seamless audio handoff.
 */
class AutoDjStateMachine(
  initialGenerationId: Long = 0L,
) {
  private val _generationId = AtomicLong(initialGenerationId)
  val generationId: Long get() = _generationId.get()

  private val _state = MutableStateFlow<AutoDjState>(AutoDjState.Idle(initialGenerationId))
  val state: StateFlow<AutoDjState> = _state.asStateFlow()

  val currentState: AutoDjState get() = _state.value

  /**
   * Atomically samples the generation ID and current state under internal monitor synchronization.
   */
  fun snapshot(): Pair<Long, AutoDjState> = synchronized(this) { generationId to currentState }

  /**
   * Atomically increments and returns the next monotonic generation ID.
   */
  fun nextGeneration(): Long = _generationId.incrementAndGet()

  /**
   * Dispatches an event to the state machine with generation validation.
   */
  @Synchronized
  fun dispatchEvent(event: AutoDjEvent): AutoDjState {
    val current = _state.value
    val next = when (event) {
      is AutoDjEvent.TrackChanged -> {
        val nextGen = nextGeneration()
        AutoDjState.Analyzing(
          generationId = nextGen,
          outgoingSongId = event.outgoingSongId,
          incomingSongId = event.incomingSongId,
        )
      }

      is AutoDjEvent.ProfileAvailable -> {
        if (event.generationId != generationId) {
          Timber.w("AutoDjStateMachine: Stale ProfileAvailable dropped (event gen=${event.generationId}, current=$generationId)")
          current
        } else if (current is AutoDjState.Analyzing && current.generationId == event.generationId) {
          val incomingSongId = current.incomingSongId ?: event.incomingProfile?.songId ?: ""
          if (event.incomingProfile != null) {
            AutoDjState.Preparing(
              generationId = event.generationId,
              outgoingProfile = event.outgoingProfile,
              incomingSongId = incomingSongId,
              incomingProfile = event.incomingProfile,
            )
          } else {
            current
          }
        } else {
          current
        }
      }

      is AutoDjEvent.PlayerPrepared -> {
        if (event.generationId != generationId) {
          Timber.w("AutoDjStateMachine: Stale PlayerPrepared dropped (event gen=${event.generationId}, current=$generationId)")
          current
        } else if (current is AutoDjState.Preparing && current.generationId == event.generationId && current.incomingProfile != null) {
          AutoDjState.Planning(
            generationId = event.generationId,
            outgoingProfile = current.outgoingProfile,
            incomingProfile = current.incomingProfile,
          )
        } else {
          current
        }
      }

      is AutoDjEvent.PlanReady -> {
        if (event.generationId != generationId) {
          Timber.w("AutoDjStateMachine: Stale PlanReady dropped (event gen=${event.generationId}, current=$generationId)")
          current
        } else if ((current is AutoDjState.Planning || current is AutoDjState.Preparing || current is AutoDjState.Analyzing) && current.generationId == event.generationId) {
          AutoDjState.Scheduled(
            generationId = event.generationId,
            plan = event.plan,
          )
        } else {
          current
        }
      }

      is AutoDjEvent.MixTriggered -> {
        if (event.generationId != generationId) {
          Timber.w("AutoDjStateMachine: Stale MixTriggered dropped (event gen=${event.generationId}, current=$generationId)")
          current
        } else if (current is AutoDjState.Scheduled && current.generationId == event.generationId) {
          AutoDjState.Mixing(
            generationId = event.generationId,
            plan = current.plan,
            progress = 0.0f,
          )
        } else {
          current
        }
      }

      is AutoDjEvent.MixProgress -> {
        if (event.generationId != generationId) {
          current
        } else if (current is AutoDjState.Mixing && current.generationId == event.generationId) {
          current.copy(progress = event.progress.coerceIn(0.0f, 1.0f))
        } else {
          current
        }
      }

      is AutoDjEvent.MixCompleted -> {
        if (event.generationId != generationId) {
          Timber.w("AutoDjStateMachine: Stale MixCompleted dropped (event gen=${event.generationId}, current=$generationId)")
          current
        } else if (current is AutoDjState.Mixing && current.generationId == event.generationId) {
          AutoDjState.Handoff(
            generationId = event.generationId,
            plan = current.plan,
          )
        } else {
          current
        }
      }

      is AutoDjEvent.CancelRequested -> {
        val targetGen = event.targetGenerationId ?: generationId
        if (targetGen != generationId && current.generationId != targetGen) {
          current
        } else if (current is AutoDjState.Idle || current is AutoDjState.Canceling) {
          current
        } else {
          val cancelGen = nextGeneration()
          AutoDjState.Canceling(
            generationId = cancelGen,
            reason = event.reason,
            previousState = current,
          )
        }
      }

      is AutoDjEvent.Reset -> {
        AutoDjState.Idle(generationId = generationId)
      }
    }

    _state.value = next
    return next
  }

  fun startTransition(outgoingSongId: String, incomingSongId: String? = null): AutoDjState =
    dispatchEvent(AutoDjEvent.TrackChanged(outgoingSongId, incomingSongId))

  fun onProfilesAvailable(outgoingProfile: TrackProfile, incomingProfile: TrackProfile? = null): AutoDjState =
    dispatchEvent(AutoDjEvent.ProfileAvailable(generationId, outgoingProfile, incomingProfile))

  fun onPlayerPrepared(): AutoDjState =
    dispatchEvent(AutoDjEvent.PlayerPrepared(generationId))

  fun onPlanReady(plan: TransitionPlan): AutoDjState =
    dispatchEvent(AutoDjEvent.PlanReady(plan.generationId.takeIf { it != 0L } ?: generationId, plan))

  fun onMixTriggered(): AutoDjState =
    dispatchEvent(AutoDjEvent.MixTriggered(generationId))

  fun onMixProgress(progress: Float): AutoDjState =
    dispatchEvent(AutoDjEvent.MixProgress(generationId, progress))

  fun onMixCompleted(): AutoDjState =
    dispatchEvent(AutoDjEvent.MixCompleted(generationId))

  fun cancel(reason: CancelReason = CancelReason.USER_SKIP): AutoDjState =
    dispatchEvent(AutoDjEvent.CancelRequested(reason))

  fun cancel(reasonString: String): AutoDjState =
    dispatchEvent(AutoDjEvent.CancelRequested(CancelReason.fromString(reasonString)))

  fun reset(): AutoDjState =
    dispatchEvent(AutoDjEvent.Reset)
}
