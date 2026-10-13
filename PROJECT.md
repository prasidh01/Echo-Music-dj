# Project: Auto DJ for Echo Music Android

## Architecture
Auto DJ provides seamless beat-aligned and harmonically compatible transitions between tracks.
The architecture is structured across Android modular boundaries:
- `:domain`: Pure Kotlin JVM models (`TrackProfile`, `TransitionPlan`, `CamelotKey`, `DjPreset`) and business logic (`TransitionPlanner`, `TransitionPlanValidator`). No Android dependencies.
- `:dsp-core`: Pure Kotlin JVM DSP algorithms (Camelot wheel distance math, tempo ratio octave folding, biquad EQ filters).
- `:core`: Room database persistence (`TrackProfileEntity`, `TrackProfileDao`, `MIGRATION_48_49`) and DataStore preferences.
- `:audio-dsp`: Media3 `AudioProcessor` implementations (`AutomixDuckAudioProcessor`, `AntiClippingLimiterAudioProcessor`, `CustomEqualizerAudioProcessor`).
- `:playback`: Audio decoding, feature extraction (`TrackAnalyzer`), and state machine (`AutoDjStateMachine`).
- `app`: `MusicService` playback orchestration, Media3 ExoPlayer integration, Compose UI settings (`PlayerSettings.kt`), telemetry overlay (`Player.kt`), and queue reordering (`Queue.kt`).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Track Analysis Engine | BPM, beat grid locations, downbeats, phrase boundaries, and confidence metrics | M2 | R1 |
| 2 | Profile Caching & Persistence | Room database `TrackProfileEntity`, `TrackProfileDao`, non-blocking cache lookup | M1 | R1 |
| 3 | Tempo Compatibility & Octave Folding | Tempo ratio calculation with octave folding to bound within ±8% | M1 | R2 |
| 4 | Harmonic Compatibility & Camelot Key | Camelot key estimation (e.g. 8A, 11B) and harmonic distance scoring | M1 | R2 |
| 5 | Structural Mix Point Planning | Determining outro mix-out and intro mix-in aligned to downbeats/phrases | M1 | R2 |
| 6 | Transition Validation & Fallback Gates | Validation gates for confidence, tempo delta, headroom; deterministic fallback | M1 | R2 |
| 7 | Anti-Clipping Limiter Processor | Peak limiter AudioProcessor in Media3 chain preventing +3 dB digital clipping | M2 | R3 |
| 8 | Ducking & Equal-Power Crossfade | Low-shelf bass ducking (-10 dB at 150 Hz) and sine/cosine equal-power crossfade | M2 | R3 |
| 9 | Dual-Stream Audio Pipeline Execution | Sample-accurate gain automation and SonicAudioProcessor tempo/pitch alignment | M3 | R3 |
| 10 | Generation-Guarded State Machine | Monotonic generation IDs across Idle, Analyzing, Preparing, Planning, Scheduled, Mixing, Handoff, Canceling | M3 | R4 |
| 11 | Cancellation & Stress Safety | Clean cancellation of pending transitions during rapid skip, seek, pause, or queue edit | M3 | R4 |
| 12 | DJ Presets Configuration | Seamless, Club, Creative, Conservative preset definitions and DataStore settings | M4 | R5 |
| 13 | UI Telemetry & Debug Overlay | Real-time display of Auto DJ state, generation ID, Camelot key, and transition metrics | M4 | R5 |
| 14 | Auto-Remix Recommendation Ordering | Pairwise transition compatibility scoring to reorder upcoming queue / recommendations | M4 | R5 |
| 15 | E2E Integration & Verification | 100% E2E test suite pass (Tiers 1-4) followed by Phase 2 adversarial coverage hardening | M5 | Acceptance Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Domain & Core Persistence | `:domain` & `:core` models, Room entity/DAO, migration, planner, validator | none | DONE |
| M2 | Analysis & DSP Engine | `:dsp-core`, `:audio-dsp`, and `:playback` analyzer & limiter processor | M1 | DONE |
| M3 | Playback State Machine & Execution | `:playback` state machine & `MusicService` dual-stream playback integration | M1, M2 | DONE |
| M4 | UI Integration, Presets & Queue | `app` UI (`PlayerSettings`, `Player`, `Queue`), DataStore presets, auto-remix | M1, M3 | READY |
| M5 | E2E Test Pass & Adversarial Hardening | Pass 100% E2E test suite (Tiers 1-4), then adversarial hardening (Tier 5) | M1, M2, M3, M4 | PLANNED |

## Interface Contracts

### `:domain` ↔ `:core`
- `TrackProfile` domain model maps to/from `TrackProfileEntity`:
  - `songId: String`
  - `bpm: Float`
  - `confidence: Float`
  - `firstBeatOffsetMs: Long`
  - `downbeatIntervalMs: Long`
  - `phraseBeatCount: Int`
  - `camelotKey: String` (e.g., "8A")
  - `pitchClass: Int` (0..11)
  - `isMinor: Boolean`
  - `mixOutPointMs: Long?`
  - `mixInPointMs: Long?`
- `TrackProfileDao`:
  - `fun getTrackProfile(songId: String): Flow<TrackProfileEntity?>`
  - `suspend fun getTrackProfileDirect(songId: String): TrackProfileEntity?`
  - `suspend fun upsert(entity: TrackProfileEntity)`

### `:domain` ↔ `:playback` & `app`
- `TransitionPlanner`:
  - `fun planTransition(current: TrackProfile, incoming: TrackProfile, preset: DjPreset): TransitionPlan`
- `TransitionPlanValidator`:
  - `fun validate(plan: TransitionPlan): TransitionPlanResult`
  - `sealed class TransitionPlanResult { data class Valid(val plan: TransitionPlan) : TransitionPlanResult(); data class Fallback(val reason: FallbackReason, val defaultPlan: TransitionPlan) : TransitionPlanResult() }`
- `AutoDjStateMachine`:
  - `fun dispatchEvent(event: AutoDjEvent): AutoDjState`
  - Current monotonic generation ID: `val generationId: Long`
  - States: `Idle`, `Analyzing`, `Preparing`, `Planning`, `Scheduled`, `Mixing`, `Handoff`, `Canceling`

### `:audio-dsp` ↔ `Media3`
- `AntiClippingLimiterAudioProcessor`:
  - Implements `androidx.media3.common.audio.AudioProcessor`
  - Enforces smooth ceiling limiting at -0.1 dBFS, fast attack, soft knee.
- `AutomixDuckAudioProcessor`:
  - Biquad low-shelf filter at 150 Hz, -10 dB ducking during transition overlap.

## Code Layout
- `domain/src/main/kotlin/echo/music/domain/autodj/`:
  - `model/TrackProfile.kt`
  - `model/CamelotKey.kt`
  - `model/TransitionPlan.kt`
  - `model/DjPreset.kt`
  - `planner/TransitionPlanner.kt`
  - `validator/TransitionPlanValidator.kt`
- `dsp-core/src/main/kotlin/echo/music/dsp/autodj/`:
  - `CamelotHarmonics.kt`
  - `TempoMatcher.kt`
- `core/src/main/kotlin/echo/music/iad1tya/db/entities/`:
  - `TrackProfileEntity.kt`
- `core/src/main/kotlin/echo/music/iad1tya/db/`:
  - `TrackProfileDao.kt`
  - `MusicDatabase.kt` (Room schema v49 migration)
- `audio-dsp/src/main/kotlin/echo/music/dsp/audio/`:
  - `AntiClippingLimiterAudioProcessor.kt`
  - `AutomixDuckAudioProcessor.kt`
- `playback/src/main/kotlin/echo/music/iad1tya/playback/autodj/`:
  - `TrackAnalyzer.kt`
  - `AutoDjStateMachine.kt`
- `app/src/main/kotlin/com/music/echo/playback/`:
  - `MusicService.kt`
- `app/src/main/kotlin/com/music/echo/ui/`:
  - `screens/settings/PlayerSettings.kt`
  - `screens/player/Player.kt`
  - `screens/queue/Queue.kt`
