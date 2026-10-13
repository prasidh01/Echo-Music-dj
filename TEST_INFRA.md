# Auto DJ End-to-End Test Infrastructure & Test Case Inventory

This document establishes the end-to-end (E2E) testing track, test harness architecture, execution instructions via Gradle, and comprehensive 4-tier test case inventory for the Auto DJ feature in Echo Music Android.

---

## 1. Test Architecture & Modular Strategy

Auto DJ spans multiple modules across the project architecture. Tests are partitioned to maximize headless execution speed, test isolation, and deterministic verification without requiring physical Android devices or emulators.

```
┌────────────────────────────────────────────────────────────────────────┐
│                              :app                                      │
│  - MusicService orchestration & ExoPlayer dual-stream coordination     │
│  - Compose UI Telemetry overlay & DataStore preferences                │
│  Harness: Headless Robolectric + Dynamic Proxy ExoPlayer + TestScope   │
└───────────────────▲────────────────────────────────▲───────────────────┘
                    │                                │
┌───────────────────┴───────────────┐  ┌─────────────┴───────────────────┐
│             :playback             │  │           :audio-dsp            │
│  - TrackAnalyzer (BPM/beat grid)  │  │  - AntiClippingLimiterProcessor │
│  - AutoDjStateMachine (gen IDs)   │  │  - AutomixDuckAudioProcessor    │
│  Harness: Kotlin Coroutines Test  │  │  Harness: Direct ByteBuffer     │
│           Synthetic PCM fixture   │  │           Media3 AudioFormat    │
└───────────────────▲───────────────┘  └─────────────▲───────────────────┘
                    │                                │
┌───────────────────┴───────────────┐  ┌─────────────┴───────────────────┐
│              :core                │  │            :dsp-core            │
│  - TrackProfileEntity & Room DAO  │  │  - Camelot wheel distance math  │
│  - MIGRATION_48_49 verification   │  │  - Octave folding algorithm     │
│  Harness: Room In-Memory Database │  │  - Biquad filter mathematics    │
│           MigrationTestHelper     │  │  Harness: Pure Kotlin JVM JUnit │
└───────────────────▲───────────────┘  └─────────────▲───────────────────┘
                    │                                │
                    └───────────────┬────────────────┘
                                    │
                    ┌───────────────┴────────────────┐
                    │            :domain             │
                    │  - TrackProfile & TransitionPlan│
                    │  - TransitionPlanner & Validator│
                    │  - DjPreset specifications     │
                    │  Harness: Pure Kotlin JVM JUnit │
                    └────────────────────────────────┘
```

### Module Test Profiles
| Module | Type | Dependencies | Test Framework | Execution Environment |
|---|---|---|---|---|
| `:domain` | Pure JVM | Kotlin Stdlib, Coroutines | JUnit 4, Kotlinx Coroutines Test | Headless JVM (`jvmToolchain(21)`) |
| `:dsp-core` | Pure JVM | Kotlin Stdlib, Serialization | JUnit 4 | Headless JVM (`jvmToolchain(21)`) |
| `:audio-dsp` | Android Library | Media3 Common, Timber | JUnit 4, Media3 test fixtures | Headless JVM with synthetic ByteBuffers |
| `:core` | Android Library | Room 2.8.5, SQLite | JUnit 4, Room Testing, Robolectric | Headless SQLite in-memory |
| `:playback` | Android Library | Media3 ExoPlayer, Coroutines | JUnit 4, Coroutines Test, Reflection Proxies | Headless JVM with ExoPlayer proxies |
| `:app` | Android App | Media3, Compose, Hilt, DataStore | JUnit 4, Robolectric, Coroutines Test | Headless Robolectric unit test runner |

---

## 2. Test Execution via Gradle

All tests run headlessly on the host JVM (Java 21) without requiring attached Android devices or running emulators.

### 2.1 Submodule Execution Commands
```powershell
# Pure Kotlin JVM domain models, planner, validator
.\gradlew.bat :domain:test

# DSP math, octave folding, Camelot distance, biquad filters
.\gradlew.bat :dsp-core:test

# AudioProcessor implementations (Limiter, Bass Ducking)
.\gradlew.bat :audio-dsp:testDebugUnitTest

# Room persistence, TrackProfileDao, Migration 48->49
.\gradlew.bat :core:testDebugUnitTest

# Playback state machine, TrackAnalyzer, AdaptivePreArmManager
.\gradlew.bat :playback:testDebugUnitTest

# Service orchestration, UI settings, Queue reordering
.\gradlew.bat :app:testUniversalGmsDebugUnitTest
```

### 2.2 Complete Suite Execution Commands
```powershell
# Run all unit and integration tests across all modules
.\gradlew.bat test

# Run tests with stacktrace on failure
.\gradlew.bat test --stacktrace

# Run specific test class
.\gradlew.bat :domain:test --tests "echo.music.domain.autodj.planner.TransitionPlannerTest"
```

---

## 3. Test Harness, Test Doubles & Verification Oracles

### 3.1 ExoPlayer Dynamic Proxy Pattern
To avoid heavy Android device runtimes and Native MediaCodec dependencies, `ExoPlayer` instances are mocked headlessly via Java dynamic reflection proxies:
```kotlin
val mockPlayer = Proxy.newProxyInstance(
    ExoPlayer::class.java.classLoader,
    arrayOf(ExoPlayer::class.java)
) { _, method, args ->
    when (method.name) {
        "getPlaybackState" -> Player.STATE_READY
        "getCurrentPosition" -> simulatedPositionMs
        "getDuration" -> simulatedDurationMs
        "setVolume" -> { appliedVolume = args[0] as Float; null }
        "getVolume" -> appliedVolume
        else -> null
    }
} as ExoPlayer
```

### 3.2 Synthetic PCM Audio Generator
For DSP and `AudioProcessor` verification, deterministic audio signals are generated programmatically:
- **Periodic Click / Beat Pulses**: Synthetic 16-bit 44.1 kHz PCM with energy impulses spaced at exact millisecond intervals ($T = 60000 / \text{BPM}$).
- **Overload / Clipping Signals**: 1 kHz sine waves at +3.0 dBFS (peak value 46,340 in 16-bit signed integer space) to verify limiter clamping.
- **Low-Frequency Sine Bursts**: 60 Hz and 100 Hz tones to verify -10 dB low-shelf bass ducking.

### 3.3 Authoritative Derivation Formulas (Oracles)
- **Octave Folding**: Given $R = \text{BPM}_{in} / \text{BPM}_{out}$, find $k \in \{-1, 0, 1\}$ minimizing $|R \cdot 2^k - 1.0|$. Accept if $|R \cdot 2^k - 1.0| \le \text{tolerance}$ (default $0.08$).
- **Camelot Distance**: Given keys $A = (n_A, m_A)$ and $B = (n_B, m_B)$ where $n \in \{1..12\}$ and $m \in \{A, B\}$:
  $$\Delta_{\text{circle}} = \min(|n_A - n_B|, 12 - |n_A - n_B|)$$
  $$\text{Distance} = \Delta_{\text{circle}} + (1 \text{ if } m_A \ne m_B \text{ else } 0)$$
- **Equal-Power Gains**: For normalized progress $t \in [0.0, 1.0]$:
  $$G_{out}(t) = \cos\left(\frac{\pi}{2} t\right), \quad G_{in}(t) = \sin\left(\frac{\pi}{2} t\right)$$
  Invariant: $G_{out}(t)^2 + G_{in}(t)^2 = 1.000 \pm 0.001$.
- **Peak Limiter Transfer Function**:
  $$y[n] = \begin{cases} x[n] & \text{if } |x[n]| \le T \\ \text{sgn}(x[n]) \cdot \left( T + (1 - e^{-\frac{|x[n]| - T}{C}}) \cdot (M - T) \right) & \text{if } |x[n]| > T \end{cases}$$
  Ceiling $M = -0.1\text{ dBFS} \approx 32,386$ in 16-bit signed space.

---

## 4. Comprehensive 4-Tier Test Case Inventory

### Tier 1: Feature Coverage (>=5 test cases per feature)

#### Feature 1: Track Analysis Engine
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F01-01` | Synthetic 120 BPM Beat Detection | `TrackAnalyzer.analyze()` | 30s 44.1kHz PCM with 500ms clicks | `bpm = 120.0f` (±0.5), `confidence >= 0.90f` | Synthetic pulse interval $60000 / 500 = 120.0$ |
| `T1-F01-02` | Downbeat Phase Alignment | `TrackAnalyzer.analyze()` | Periodic 4/4 clicks with accented 1st beat | `firstBeatOffsetMs` matches 1st accent (±5ms) | Known synthetic downbeat timestamp |
| `T1-F01-03` | Phrase Boundary Detection | `TrackAnalyzer.analyze()` | 32-bar synthetic progression with energy lift | `phraseBeatCount = 32`, boundary at bar 32 | Standard EDM 32-beat phrase periodicity |
| `T1-F01-04` | Dynamic Confidence Scoring | `TrackAnalyzer.analyze()` | Noisy irregular click track (jitter ±200ms) | `confidence < 0.40f` | R1 low confidence threshold specification |
| `T1-F01-05` | Pitch Class & Camelot Extraction | `TrackAnalyzer.analyze()` | Pure 440 Hz (A4) chord progression (A minor) | `camelotKey = "8A"`, `pitchClass = 9`, `isMinor = true` | Chromagram to Camelot standard table |

#### Feature 2: Profile Caching & Persistence
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F02-01` | Entity Roundtrip Upsert & Read | `TrackProfileDao.upsert()`, `getTrackProfileDirect()` | Valid `TrackProfileEntity` ("song_1", 128.0f, "8A") | Retrieved entity matches all fields exactly | SQLite CRUD equivalence |
| `T1-F02-02` | Non-blocking Flow Observation | `TrackProfileDao.getTrackProfile()` | Query Flow on background dispatcher | Flow emits entity immediately without blocking caller | Kotlinx Coroutines Flow semantics |
| `T1-F02-03` | Room Schema Migration v48 to v49 | `MigrationTestHelper.runMigrationsAndValidate()` | Apply `MIGRATION_48_49` on v48 SQLite DB | `track_profiles` table exists with primary key `songId` | Schema export JSON v49 |
| `T1-F02-04` | Profile Overwrite on Re-analysis | `TrackProfileDao.upsert()` | Upsert entity with updated BPM for same `songId` | Database holds updated BPM, row count remains 1 | SQLite `ON CONFLICT REPLACE` |
| `T1-F02-05` | Cache Miss Graceful Null Return | `TrackProfileDao.getTrackProfileDirect()` | Query non-existent ID "missing_song" | Returns `null` without throwing exception | Room nullable query contract |

#### Feature 3: Tempo Compatibility & Octave Folding
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F03-01` | Direct Matching Within ±8% | `TempoMatcher.calculateRatio()` | Outgoing: 124.0 BPM, Incoming: 128.0 BPM | Ratio = 1.0322, fold factor $k=0$, accepted | $|128/124 - 1.0| = 0.0322 \le 0.08$ |
| `T1-F03-02` | Half-Time Octave Folding | `TempoMatcher.calculateRatio()` | Outgoing: 140.0 BPM, Incoming: 70.0 BPM | Ratio = 1.000, fold factor $k=+1$ (double incoming) | $70 \times 2 / 140 = 1.000$ |
| `T1-F03-03` | Double-Time Octave Folding | `TempoMatcher.calculateRatio()` | Outgoing: 85.0 BPM, Incoming: 170.0 BPM | Ratio = 1.000, fold factor $k=-1$ (halve incoming) | $(170 / 2) / 85 = 1.000$ |
| `T1-F03-04` | Boundary Tempo Delta Acceptance | `TempoMatcher.calculateRatio()` | Outgoing: 120.0 BPM, Incoming: 129.6 BPM | Ratio = 1.080, accepted at maximum limit | $120.0 \times 1.08 = 129.6$ |
| `T1-F03-05` | Incompatible Tempo Rejection | `TempoMatcher.calculateRatio()` | Outgoing: 100.0 BPM, Incoming: 115.0 BPM | Rejection or fallback flag (delta = +15% > 8%) | Foldings (115, 57.5, 230) all $> 8\%$ delta |

#### Feature 4: Harmonic Compatibility & Camelot Key
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F04-01` | Exact Key Match Distance | `CamelotHarmonics.getDistance()` | Key A: "8A" (Am), Key B: "8A" (Am) | Distance = 0, Harmonic score = 1.00f | Same key distance formula |
| `T1-F04-02` | Relative Major/Minor Distance | `CamelotHarmonics.getDistance()` | Key A: "8A" (Am), Key B: "8B" (C maj) | Distance = 1, Harmonic score = 0.90f | Relative mode step formula |
| `T1-F04-03` | Adjacent Wheel Step Distance | `CamelotHarmonics.getDistance()` | Key A: "8A" (Am), Key B: "9A" (Em) | Distance = 1, Harmonic score = 0.85f | Adjacent step distance formula |
| `T1-F04-04` | Energy Boost Distance | `CamelotHarmonics.getDistance()` | Key A: "8A" (Am), Key B: "10A" (Bm) | Distance = 2, Boost flag = true, score = 0.65f | Two-step energy boost formula |
| `T1-F04-05` | Incompatible Key Clash Detection | `CamelotHarmonics.getDistance()` | Key A: "8A" (Am), Key B: "2A" (Ebm) | Distance = 6 (tritone clash), score < 0.20f | Opposite wheel position ($\Delta = 6$) |

#### Feature 5: Structural Mix Point Planning
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F05-01` | Downbeat-Aligned Mix Points | `TransitionPlanner.planTransition()` | Track A: mix-out bar 64; Track B: intro bar 1 | Alignment offset $\Delta t = 0$ms (beat phase aligned) | Phase lock downbeat alignment |
| `T1-F05-02` | 16-Bar Phrase Overlap Calculation | `TransitionPlanner.planTransition()` | 120 BPM tracks, 16 bars requested | Overlap duration = 32,000ms ($16 \times 4 \times 500\text{ms}$) | Mathematical bar duration product |
| `T1-F05-03` | Outro Fade Tail Detection | `TransitionPlanner.planTransition()` | Track A duration 240s, energy drop at 210s | `mixOutPointMs` scheduled at or before 210,000ms | Structural phrase boundary analysis |
| `T1-F05-04` | Intro Drop Alignment | `TransitionPlanner.planTransition()` | Track B intro buildup duration 16s | Transition completes exactly at drop (16,000ms) | Intro energy threshold anchor |
| `T1-F05-05` | Missing Structural Markers Fallback | `TransitionPlanner.planTransition()` | Profiles with null mix points | Overlap defaults to last 12s of A and start of B | Contract default fallback mix points |

#### Feature 6: Transition Validation & Fallback Gates
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F06-01` | High Confidence Plan Validation | `TransitionPlanValidator.validate()` | Conf = 0.85, Tempo delta = 2%, Distance = 0 | Returns `TransitionPlanResult.Valid(plan)` | Validation contract gate threshold |
| `T1-F06-02` | Low Confidence Fallback Downgrade | `TransitionPlanValidator.validate()` | Current track confidence = 0.35 (< 0.50) | Returns `TransitionPlanResult.Fallback(LOW_CONFIDENCE, defaultPlan)` | Gate confidence rule (< 0.50) |
| `T1-F06-03` | Excessive Tempo Delta Fallback | `TransitionPlanValidator.validate()` | Tempo delta = 12% (> 8%) | Returns `TransitionPlanResult.Fallback(TEMPO_INCOMPATIBLE, defaultPlan)` | Gate tempo delta rule (> 8%) |
| `T1-F06-04` | Insufficient Track Headroom Fallback | `TransitionPlanValidator.validate()` | Track duration 20s, overlap requested 30s | Returns `TransitionPlanResult.Fallback(INSUFFICIENT_HEADROOM, defaultPlan)` | Duration sanity rule |
| `T1-F06-05` | Default Fallback Plan Integrity | `TransitionPlanValidator.validate()` | Incompatible plan downgraded to fallback | Fallback plan has equal-power curve, 5s duration, 0 ducking | Specification for fallback plan |

#### Feature 7: Anti-Clipping Limiter Processor
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F07-01` | Sub-threshold Passthrough | `AntiClippingLimiterAudioProcessor.queueInput()` | Stereo PCM at -6 dBFS (amplitude 16384) | Output amplitude = 16384 (gain = 1.0, 0 dB change) | Sub-threshold linear unity gain |
| `T1-F07-02` | +3 dBFS Overload Clamping | `AntiClippingLimiterAudioProcessor.queueInput()` | Combined dual-stream peak amplitude 46000 | Peak output strictly $\le 32386$ (-0.1 dBFS ceiling) | Limiter ceiling specification |
| `T1-F07-03` | Fast Attack Time Enforcement | `AntiClippingLimiterAudioProcessor.queueInput()` | Instantaneous high-energy pulse | Attenuation applied within < 1ms (44 samples) | Fast attack limiter contract |
| `T1-F07-04` | Soft Knee Smooth Gain Reduction | `AntiClippingLimiterAudioProcessor.queueInput()` | Input ramp through threshold (-1.0 dBFS) | Gain curve second derivative is continuous (no hard clip) | Soft knee mathematical curve |
| `T1-F07-05` | Envelope Recovery on Signal Decay | `AntiClippingLimiterAudioProcessor.queueInput()` | Overload burst followed by silence | Gain smoothly recovers to 1.0 over 50ms release | Release time constant decay |

#### Feature 8: Ducking & Equal-Power Crossfade
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F08-01` | Equal-Power Gain Summation | `EqualPowerCurve.calculateGains()` | Steps $t \in [0.0, 0.25, 0.5, 0.75, 1.0]$ | $G_{out}^2 + G_{in}^2 = 1.000 \pm 0.001$ at every step | $\cos^2(\theta) + \sin^2(\theta) = 1$ identity |
| `T1-F08-02` | Midpoint Power Conservation | `EqualPowerCurve.calculateGains()` | Step $t = 0.50$ | $G_{out} = 0.7071$, $G_{in} = 0.7071$ (-3 dB each) | $\cos(\pi/4) = \sin(\pi/4) = \sqrt{2}/2$ |
| `T1-F08-03` | Low-Shelf Bass Ducking Attenuation | `AutomixDuckAudioProcessor.setMix()` | Mix = 1.0, input 80 Hz sine wave | Output attenuated by $-10.0\text{ dB} \pm 0.5\text{ dB}$ | R3 low-shelf biquad filter spec |
| `T1-F08-04` | High Frequency Preservation | `AutomixDuckAudioProcessor.setMix()` | Mix = 1.0, input 2 kHz sine wave | Output gain within $\pm 0.5\text{ dB}$ of 0 dB | Low-shelf frequency selectivity |
| `T1-F08-05` | Ducking Mix Automation Ramp | `AutomixDuckAudioProcessor.setMix()` | Linear sweep of mix from 0.0 to 1.0 | Attenuation scales monotonically from 0 dB to -10 dB | Gain automation monotonicity |

#### Feature 9: Dual-Stream Audio Pipeline Execution
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F09-01` | Sample-Accurate Gain Step Automation | `MusicService.crossfadeJob` step loop | 5s crossfade executed in 15ms intervals | Gains update synchronously across 333 discrete steps | Step counter $5000 / 15 = 333$ |
| `T1-F09-02` | Sonic Tempo Scaling without Pitch Shift | `SonicAudioProcessor.setSpeed()` | Set speed ratio = 1.05x | Playback tempo increases 5%, pitch ratio remains 1.00 | Sonic independent pitch/tempo control |
| `T1-F09-03` | Sonic Pitch Shift (Creative Preset) | `SonicAudioProcessor.setPitch()` | Set pitch shift +1 semitone ($1.05946$) | Pitch shifts +1 semitone, tempo speed remains 1.00 | 12-TET semitone ratio $2^{1/12}$ |
| `T1-F09-04` | Interleaved Stereo Buffer Integrity | Media3 AudioProcessor chain | Interleaved L/R stereo buffers queued | Left and right channels process without channel swap | Stereo byte-offset interleaving rules |
| `T1-F09-05` | Outgoing Stream Drain & EOS | Media3 sink pipeline | Outgoing player finishes crossfade | Buffer drains cleanly, dispatches EOS without click | Media3 AudioProcessor drain contract |

#### Feature 10: Generation-Guarded State Machine
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F10-01` | Nominal Lifecycle State Flow | `AutoDjStateMachine.dispatchEvent()` | Sequence: Start $\to$ Profiles $\to$ Ready $\to$ Mix $\to$ End | States: `Idle` $\to$ `Analyzing` $\to$ `Preparing` $\to$ `Planning` $\to$ `Scheduled` $\to$ `Mixing` $\to$ `Handoff` $\to$ `Idle` | R4 state transition diagram |
| `T1-F10-02` | Generation ID Increment on New Transition | `AutoDjStateMachine.startTransition()` | Initial `generationId = 10L` | Increments to `11L` on transition trigger | Monotonic counter specification |
| `T1-F10-03` | Stale Event Discard by Generation ID | `AutoDjStateMachine.onPlanReady()` | Event generation ID = 10, current = 11 | Event ignored, state remains unchanged | Monotonic generation guard contract |
| `T1-F10-04` | Immutable StateFlow Emission | `AutoDjStateMachine.state` | Observer collects states during transitions | StateFlow emits each unique state without missing updates | Kotlinx Coroutines StateFlow contract |
| `T1-F10-05` | Generation Counter Monotonicity Invariant | `AutoDjStateMachine.generationId` | Execute 20 random lifecycle cycles | Generation ID strictly monotonically increases | $\forall i, \text{gen}_{i+1} > \text{gen}_i$ |

#### Feature 11: Cancellation & Stress Safety
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F11-01` | Skip During Mixing Cancels Transition | `MusicService.seekToNext()` | Trigger skip while state is `Mixing` | Transitions to `Canceling`, crossfade job cancelled, volume restored to 1.0f | R4 cancellation safety contract |
| `T1-F11-02` | Seek During Scheduled Aborts Transition | `MusicService.seekTo()` | Trigger seek while state is `Scheduled` | Generation ID increments, scheduled plan aborted, resets to `Idle` | R4 discontinuity handling |
| `T1-F11-03` | Pause During Active Fade Pauses Both Streams | `MusicService.pause()` | Trigger pause while state is `Mixing` | Both primary and secondary players pause, jobs stop | Player lifecycle contract |
| `T1-F11-04` | Queue Item Removal Aborts Preparation | `MusicService.removeMediaItem()` | Remove incoming item while in `Preparing` | Pre-warming job cancelled, secondary player released | Resource cleanup specification |
| `T1-F11-05` | High-Frequency Rapid Skip Stress Run | `AutoDjStateMachine` / `MusicService` | 50 rapid sequential skips within 500ms | 0 crashes, 0 leaked players, generation counter increments by 50, final state clean | Stress testing acceptance criteria |

#### Feature 12: DJ Presets Configuration
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F12-01` | Preset Parameter Extraction | `DjPreset.entries` | Inspect SEAMLESS, CLUB, CREATIVE, CONSERVATIVE | Parameters match duration, tempo delta, and ducking spec | R5 DJ preset table |
| `T1-F12-02` | DataStore Preference Serialization | `DataStore.edit()`, `preferencesFlow` | Write `DjPreset.CLUB.name` to `DjPresetKey` | Flow emits `DjPreset.CLUB` | Android DataStore persistence contract |
| `T1-F12-03` | Seamless Preset Planner Constraints | `TransitionPlanner.planTransition()` | Preset = `DjPreset.SEAMLESS` | Overlap clamped 4-8 beats, tempo delta $\le 3\%$, ducking -4 dB | Seamless preset specification |
| `T1-F12-04` | Club Preset Planner Constraints | `TransitionPlanner.planTransition()` | Preset = `DjPreset.CLUB` | Overlap 16-32 beats, tempo delta $\le 8\%$, ducking -10 dB | Club preset specification |
| `T1-F12-05` | Conservative Preset Early Fallback | `TransitionPlanner.planTransition()` | Preset = `DjPreset.CONSERVATIVE`, Conf = 0.70 | Triggers fallback (threshold = 0.75), 4-beat standard fade | Conservative preset strict rule |

#### Feature 13: UI Telemetry & Debug Overlay
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F13-01` | Telemetry StateFlow Emission | `PlayerConnection.telemetryState` | State machine transitions `Mixing` $\to$ `Handoff` | Telemetry StateFlow emits updated state & progress | Flow observation contract |
| `T1-F13-02` | Debug Overlay Visibility Toggle | `PlayerSettings` / `Player.kt` | Toggle `AutomixDebugOverlayKey` (false / true) | Overlay composable omitted when false, composed when true | Compose Conditional UI rendering |
| `T1-F13-03` | Camelot Key Overlay String Formatting | `AutoDjTelemetryFormatter.format()` | Outgoing: "8A", Incoming: "9A", delta: +1 | Returns `"8A -> 9A (+1 step)"` | Formatter contract specification |
| `T1-F13-04` | Real-time Crossfade Progress Display | `AutoDjTelemetryFormatter.format()` | Crossfade progress $p = 0.65f$ | Displays `"Mixing: 65%"` | Progress normalization formula |
| `T1-F13-05` | Fallback Reason Telemetry Indication | `AutoDjTelemetryFormatter.format()` | Fallback triggered with `LOW_CONFIDENCE` | Displays `"Fallback: Low Confidence (0.32)"` | Diagnostic telemetry specification |

#### Feature 14: Auto-Remix Recommendation Ordering
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F14-01` | Pairwise Compatibility Scoring | `AutoRemixScorer.scoreTransition()` | Curr: (124, 8A). Cand A: (126, 8A), Cand B: (140, 3B) | Score(A) > Score(B) ($0.95 \text{ vs } 0.25$) | R5 compatibility scoring formula |
| `T1-F14-02` | Upcoming Queue Reordering | `QueueManager.reorderUpcoming()` | Current playing + 5 upcoming queue items | Item with highest score moved to index 1 (next track) | Queue reordering contract |
| `T1-F14-03` | Recommendation Pool Ranking | `RecommendationEngine.rankForAutoDj()` | Unsorted list of 10 recommendation tracks | Returned list sorted in descending order of compatibility score | Sorting invariant |
| `T1-F14-04` | AutoRemixOrdering Toggle Bypass | `QueueManager.reorderUpcoming()` | `AutoRemixOrderingKey = false` | Natural playlist queue order preserved untouched | Feature toggle bypass rule |
| `T1-F14-05` | Stable Tie-Breaker Ordering | `AutoRemixScorer.rankCandidates()` | Two candidates with identical score (0.85) | Original playlist order preserved as tie-breaker | Stable sort algorithm guarantee |

#### Feature 15: E2E Integration & Verification
| Test Case ID | Test Name | Target Class/Method | Test Input / Setup | Expected Observable Output | Derivation Oracle |
|---|---|---|---|---|---|
| `T1-F15-01` | Full Synthetic 2-Track Seamless Transition | End-to-end headless integration harness | 2 synthetic tracks with 124/126 BPM, 8A/9A | All states traverse, audio sums cleanly, handoff completes | Acceptance criteria R1-R4 |
| `T1-F15-02` | Continuous 3-Track DJ Set Progression | End-to-end headless integration harness | 3 consecutive compatible tracks | 2 consecutive seamless transitions execute without pause | Continuous DJ playback flow |
| `T1-F15-03` | Fallback Graceful Degradation Transition | End-to-end headless integration harness | Track A (good) to Track B (noisy/no beat) | Fallback crossfade activates, zero audible silence or crash | Graceful degradation contract |
| `T1-F15-04` | Gradle Headless Test Suite Execution | Gradle test task | Run `./gradlew test` | 100% tests pass with 0 failures | Acceptance criteria verification |
| `T1-F15-05` | Memory & Resource Leak Verification | End-to-end transition loop | Execute 20 consecutive transitions | Secondary players and audio buffers cleanly garbage collected | Zero object leak invariant |

---

### Tier 2: Boundary & Corner Cases (>=5 test cases per feature)

#### Feature 1: Track Analysis Engine (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F01-01` | Sub-threshold Low BPM | Audio with 30.0 BPM clicks | Division by zero or integer underflow | BPM clamped or flagged with confidence $< 0.30$ |
| `T2-F01-02` | Extreme High BPM | Audio with 320.0 BPM clicks | Buffer overflow in beat grid array | Folded to 160.0 BPM or bounded gracefully |
| `T2-F01-03` | Pure Zero-Amplitude Silence | 10s of all-zero 16-bit PCM (0.0 rms) | NaN / Infinite energy calculations | Returns `confidence = 0.0f`, `bpm = 0.0f`, empty grid |
| `T2-F01-04` | Ultra-short Audio Clip | 2.0-second audio track | IndexOutOfBounds on phrase analysis | Returns clamped profile with duration markers $\le 2000$ms |
| `T2-F01-05` | Variable Tempo / Rubato Audio | Audio with accelerating tempo (80 to 140 BPM) | False high confidence on unstable grid | High beat interval variance reduces confidence $< 0.40$ |

#### Feature 2: Profile Caching & Persistence (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F02-01` | Extreme `songId` String Length | 4,096 character UTF-8 string with emojis | SQLite syntax or encoding crash | Room persists and retrieves without corruption |
| `T2-F02-02` | Numeric Extreme Floating Points | `bpm = Float.MAX_VALUE`, `firstBeat = Long.MAX_VALUE` | Overflow / truncation in SQLite schema | Values preserved with exact numerical fidelity |
| `T2-F02-03` | Simulated SQLite Disk Full | Disk writes throw `SQLiteFullException` | Unhandled app crash on profile cache | Exception caught, returns transient in-memory profile |
| `T2-F02-04` | Concurrent Parallel Upserts | 10 coroutines writing profile for same `songId` | SQLite database table locking deadlock | Thread-safe transaction handles all writes cleanly |
| `T2-F02-05` | Corrupted Room Database File | Malformed SQLite database file header | Uncaught unrecoverable app start crash | Database catches corruption and rebuilds schema safely |

#### Feature 3: Tempo Compatibility & Octave Folding (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F03-01` | Exact +8.000% Boundary Delta | Outgoing: 120.0 BPM, Incoming: 129.600 BPM | Floating-point rounding rejecting valid edge | Accepted with ratio = 1.08000 |
| `T2-F03-02` | Exact +8.001% Boundary Exceeded | Outgoing: 120.0 BPM, Incoming: 129.610 BPM | Accepting delta exceeding strict boundary | Rejected / marked for fallback ($0.08008 > 0.08$) |
| `T2-F03-03` | Exact 2.000x Octave Threshold | Outgoing: 120.0 BPM, Incoming: 240.000 BPM | Failure to fold exact integer multiple | Folds with $k = -1$, effective ratio = 1.0000 |
| `T2-F03-04` | Boundary Octave Fold Edge | Outgoing: 120.0 BPM, Incoming: 259.200 BPM | Multi-stage rounding error on folded edge | Folds to 129.6 BPM (+8.0% boundary), accepted |
| `T2-F03-05` | Zero or Negative BPM Inputs | `bpm = 0.0f` or `bpm = -128.0f` | Negative tempo stretch in Sonic processor | Throws `IllegalArgumentException` / safe fallback |

#### Feature 4: Harmonic Compatibility & Camelot Key (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F04-01` | Circular Wheel Wrap-around ($12 \leftrightarrow 1$) | Key A: "12A", Key B: "1A" | Evaluating distance as $|12 - 1| = 11$ | Correctly calculates circular distance $\min(11, 1) = 1$ |
| `T2-F04-02` | Maximum Wheel Distance | Key A: "1A", Key B: "7A" | Distance calculation exceeding 6 | Distance capped at exactly 6 (opposite pole) |
| `T2-F04-03` | Identical Pitch Class Different Mode | Key A: "8A" (A min), Key B: "8B" (C maj) | Mode mismatch confusion | Recognizes relative major/minor swap (distance = 1) |
| `T2-F04-04` | Malformed Camelot Key String | `key = ""` or `"13A"` or `"0B"` or `"foo"` | Crash during key parsing | Safely defaults to unknown key with fallback distance 6 |
| `T2-F04-05` | Modular Pitch Class Arithmetic | `pitchClass = 11` (B) + 1 semitone | Arithmetic overflow past 11 | Modular arithmetic $(11 + 1) \bmod 12 = 0$ (C) |

#### Feature 5: Structural Mix Point Planning (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F05-01` | Mix-out Past Track Duration | Planned mix-out timestamp > duration | Seeking past end of track / playback freeze | Clamped strictly to `duration - 1000ms` |
| `T2-F05-02` | Mix-in Timestamp Past Midpoint | Planned intro mix-in > duration / 2 | Skipping over the body of incoming song | Clamped to start downbeat (0ms) |
| `T2-F05-03` | Overlap Exceeds Song Length | 30s overlap requested on 20s incoming song | Negative playback start position | Clamped to `incomingDuration / 2` (10s) |
| `T2-F05-04` | Downbeat at Exact Zero Milliseconds | `firstBeatOffsetMs = 0L` | Zero-offset division by zero in phase grid | Phase grid begins at 0ms without error |
| `T2-F05-05` | Identical Song Transition ($A \to A$) | Track transitioned to itself | Timeline conflict or player lockup | Valid transition loop created with distinct player instances |

#### Feature 6: Transition Validation & Fallback Gates (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F06-01` | Exact Confidence Threshold ($0.5000$) | Confidence = 0.5000 vs 0.4999 | Inconsistent boundary comparison | 0.5000 passes; 0.4999 triggers fallback |
| `T2-F06-02` | Zero Duration Transition Request | Duration = 0ms requested | Division by zero in step calculation | Clamped to minimum safe crossfade (500ms) |
| `T2-F06-03` | Multiple Simultaneous Violations | Low confidence + High tempo delta + Key clash | Non-deterministic fallback reason | Highest priority reason (`LOW_CONFIDENCE`) returned |
| `T2-F06-04` | Emergency Headroom Exhaustion | Playback already at 99% of track duration | Missing transition trigger time entirely | Triggers immediate 500ms emergency crossfade |
| `T2-F06-05` | Null Structural Markers in Profile | `mixOutPointMs = null`, `mixInPointMs = null` | NullPointerException during plan execution | Safe default markers (last 12s / start 0s) substituted |

#### Feature 7: Anti-Clipping Limiter Processor (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F07-01` | Full-Scale Square Wave Overload | Alternating +32767 and -32768 samples | Integer wraparound producing extreme pops | Output strictly clamped to $\pm 32386$, zero overflow |
| `T2-F07-02` | Prolonged Sustained +6 dB Input | 10 seconds of continuous 2.0x amplitude | Limiter memory leak or release instability | Stable gain reduction maintained, zero memory leak |
| `T2-F07-03` | Pure Zero Input Buffer | All zero samples (silence) | DC offset introduced by filter state | Outputs pure zeros (gain = 1.0, 0 DC offset) |
| `T2-F07-04` | Single-Sample Audio Buffer | 2-byte mono or 4-byte stereo buffer | Buffer underflow or crash on tiny slice | Processed correctly without buffer underflow |
| `T2-F07-05` | Sample Rate Extremes (8 kHz / 192 kHz) | Non-standard sample rates | Attack/release coefficient divergence | Coefficients compute safely, filter remains stable |

#### Feature 8: Ducking & Equal-Power Crossfade (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F08-01` | Exact Start Progress $t = 0.0$ | Normalized time $t = 0.0f$ | Outgoing gain $< 1.0$ or incoming gain $> 0.0$ | $G_{out} = 1.000$, $G_{in} = 0.000$ exactly |
| `T2-F08-02` | Exact End Progress $t = 1.0$ | Normalized time $t = 1.0f$ | Outgoing gain $> 0.0$ or incoming gain $< 1.0$ | $G_{out} = 0.000$, $G_{in} = 1.000$ exactly |
| `T2-F08-03` | Progress Out of Bounds ($t < 0$ or $t > 1$) | $t = -0.5f$ or $t = 1.5f$ | Negative gains or amplitude explosion | Clamped strictly to $[0.0, 1.0]$ interval |
| `T2-F08-04` | Cutoff Near Nyquist Frequency | Filter frequency set to $0.49 \times F_s$ | Biquad coefficient explosion / NaN | Cutoff clamped to safe upper limit ($0.45 \times F_s$) |
| `T2-F08-05` | Ultra-rapid Mix Parameter Oscillations | Mix swept 0 to 1 at 1 kHz frequency | Audible zipper noise / clicks in output | Internal parameter smoothing prevents audio clicks |

#### Feature 9: Dual-Stream Audio Pipeline Execution (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F09-01` | Secondary Player Buffer Underrun | Network stall on incoming track during fade | Primary player stalls or volume stays muted | Primary player holds current volume without stutter |
| `T2-F09-02` | Secondary Player Not Ready at Trigger | Trigger reached while secondary in `BUFFERING` | Premature playback of unbuffered audio | State machine delays transition until player is ready |
| `T2-F09-03` | Extreme Tempo Stretch Stress | Speed set to 0.5x and 2.0x via Sonic | Sonic internal buffer overflow | Audio renders without buffer corruption or desync |
| `T2-F09-04` | Channel Configuration Mismatch | Track A: Stereo, Track B: Mono | Crash during dual-stream audio summing | Media3 channel matrixer mixes mono to stereo cleanly |
| `T2-F09-05` | Sample Rate Mismatch | Track A: 44.1 kHz, Track B: 48.0 kHz | Audio pitch distortion or playback desync | Media3 resampler matches rates prior to crossfade |

#### Feature 10: Generation-Guarded State Machine (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F10-01` | Generation Counter Long.MAX_VALUE | Generation ID at `Long.MAX_VALUE - 1` | Overflow to negative number breaking comparison | Handles wrap-around or clamps safely |
| `T2-F10-02` | Re-entrant Event Dispatch in Callback | Dispatching event from inside state listener | Deadlock or `ConcurrentModificationException` | Mutex / atomic update protects state machine |
| `T2-F10-03` | Illegal State Transition Attempt | Attempting `Idle` $\to$ `Mixing` directly | Invalid state corruption | Rejected, state preserved, error logged |
| `T2-F10-04` | Duplicate Sequential Events | 5 consecutive identical `Preparing` events | Spawning 5 duplicate preparation coroutines | Idempotent handling; redundant calls ignored |
| `T2-F10-05` | State Machine Disposal During Fade | Dispose / cancel state machine mid-transition | Coroutine leak or zombie playback | Scope cancelled, resources released, state is `Idle` |

#### Feature 11: Cancellation & Stress Safety (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F11-01` | Cancellation at Exact Finish ($t = 0.999$) | Skip received 1ms before Handoff | Reverting back to outgoing player erroneously | Incoming player promoted cleanly, volume locked at 1.0f |
| `T2-F11-02` | Cancellation at Exact Start ($t = 0.001$) | Skip received 1ms after Mixing begins | Outgoing player muted or secondary leaking | Outgoing player restored to 1.0f, incoming stopped |
| `T2-F11-03` | 100 Multi-threaded Concurrent Events | Rapid play/pause/skip from 10 threads | Race conditions or deadlock in state controller | All events linearized, generation matches valid cancellations |
| `T2-F11-04` | MediaSession Destroy Mid-Transition | OS kills media session during active crossfade | Service crash / foreground notification leak | Jobs cancelled, players released, notification cleared |
| `T2-F11-05` | Transient Audio Focus Loss (Call) | `AUDIOFOCUS_LOSS_TRANSIENT` during crossfade | Desynchronized volume ramp on resume | Both streams paused, ducking state preserved for resume |

#### Feature 12: DJ Presets Configuration (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F12-01` | Corrupted Preset String in DataStore | DataStore contains `"INVALID_PRESET"` | `IllegalArgumentException` on enum parsing | Falls back safely to default `DjPreset.SEAMLESS` |
| `T2-F12-02` | Preset Switch Mid-Transition | User changes preset during `Mixing` state | Mid-flight plan mutation causing audio jumps | Current transition finishes on original plan; next uses new |
| `T2-F12-03` | Zero Percent Tempo Tolerance Override | Custom tolerance set to 0.0% | Tolerance check rejecting identical BPM | Identical BPM accepted; any fractional delta falls back |
| `T2-F12-04` | Deepest Ducking Depth (-30 dB) | Ducking depth set to -30 dB | Numerical underflow to negative infinity | Low shelf attenuates to -30 dB without audio distortion |
| `T2-F12-05` | Zero-Beat Overlap Cut Transition | Overlap configured to 0 beats | Division by zero in crossfade step calculator | Executes instant cut swap at downbeat |

#### Feature 13: UI Telemetry & Debug Overlay (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F13-01` | Extremely Small Screen Resolution | Small density display (320x480 dp) | Text clipping or layout overflow crash | Text scales or wraps without clipping critical data |
| `T2-F13-02` | 60 FPS Telemetry State Emission | Rapid high-frequency progress updates | Main UI thread starvation / stutter | StateFlow conflation drops intermediate frames smoothly |
| `T2-F13-03` | Null Profile Fields in UI | Telemetry with `bpm = null`, `key = null` | `NullPointerException` during string format | Renders placeholder `"BPM: -- | Key: --"` |
| `T2-F13-04` | Maximum Song Title Length | 500-character song title in debug text | Overlay expanding offscreen | Truncated with ellipsis (`...`) |
| `T2-F13-05` | Configuration Change / Screen Rotation | Activity recreated during active crossfade | Telemetry state lost, overlay resets | State preserved via ViewModel StateFlow |

#### Feature 14: Auto-Remix Recommendation Ordering (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F14-01` | Empty Upcoming Queue | Queue size = 0 upcoming tracks | IndexOutOfBoundsException | Reordering is safe no-op |
| `T2-F14-02` | Single Item Queue | Queue size = 1 upcoming track | Redundant swap or indexing error | Safe no-op, single item remains at position 1 |
| `T2-F14-03` | Massive Queue Size (1,000 items) | 1,000 items in upcoming playlist | UI freeze or ANR during matrix evaluation | Scoring limited to lookahead window (10 items) (<5ms) |
| `T2-F14-04` | All Candidates Identical Profile | 10 candidates with identical 128 BPM / 8A | Unstable sort causing continuous reshuffle | Original playlist order strictly preserved |
| `T2-F14-05` | All Candidates Unanalyzed (Null Profiles) | Candidates have 0 confidence / null data | Score crash on null data | Safe no-op, queue maintains natural order |

#### Feature 15: E2E Integration & Verification (Boundaries)
| Test Case ID | Boundary Condition | Input Vector | Failure Mode Checked | Expected Result / Recovery |
|---|---|---|---|---|
| `T2-F15-01` | Extreme Duration Disparity | 10s short clip transitioning to 15m track | Time ratio division overflow | Handled smoothly with clamped overlap |
| `T2-F15-02` | Infinite Loop Mode Enabled | Single track set to repeat mode (`REPEAT_MODE_ONE`) | Transition to self causing deadlock | Downbeat loop transition executes cleanly |
| `T2-F15-03` | Extreme Low Device Memory | Low memory simulation during buffer allocation | Unhandled `OutOfMemoryError` crash | Buffers allocated safely or transition downgraded |
| `T2-F15-04` | Audio Output Route Change Mid-Fade | Output route switches from 48 kHz to 44.1 kHz | Media3 sink pipeline deadlock | Audio sink flushes and reconfigures seamlessly |
| `T2-F15-05` | Transition at Very End of Queue | Last track in playlist reaching mix-out | Attempting transition to null next track | State machine finishes track cleanly and stops at end |

---

### Tier 3: Cross-Feature Combinations (Pairwise Interactions)

| Test Case ID | Interaction Pair | Preconditions | Trigger Event | Invariant / Expected System Behavior |
|---|---|---|---|---|
| `T3-01` | **F01 (Analysis) $\times$ F02 (Caching)** | Track analyzer running on background coroutine | Analysis finishes for new track while database query active | Cache receives upsert; observing Flow emits updated profile without thread contention. |
| `T3-02` | **F03 (Tempo) $\times$ F04 (Harmonic)** | Track A (70 BPM, 8A), Track B (140 BPM, 8B) | Planner evaluates compatibility | Octave folding identifies $2:1$ ratio and relative major/minor key; combined score > 0.85; transition approved. |
| `T3-03` | **F05 (Mix Points) $\times$ F06 (Validation Gates)** | Track A duration is 25s; planner requests 32s phrase overlap | Validation gate runs on proposed plan | Validator detects insufficient headroom ($32\text{s} > 25\text{s}$), downgrades to fallback 5s crossfade. |
| `T3-04` | **F07 (Limiter) $\times$ F08 (Ducking & Crossfade)** | Dual 0 dBFS tracks mixed with bass ducking active | Low frequencies ducked -10 dB, high mid peaks sum to +3 dB | Limiter clamps combined peak strictly to -0.1 dBFS; no digital clipping occurs. |
| `T3-05` | **F09 (Dual-Stream) $\times$ F10 (State Machine)** | State machine in `Mixing` state | Frame progress reaches 1.0 (end of crossfade ramp) | State machine transitions to `Handoff`, promotes incoming player, releases outgoing player. |
| `T3-06` | **F10 (State Machine) $\times$ F11 (Cancellation)** | State machine in `Mixing` state (progress 0.35) | User presses "Next" (skip) | Generation ID increments, crossfade job cancelled immediately, primary volume restored to 1.0f. |
| `T3-07` | **F12 (DJ Presets) $\times$ F06 (Validation Gates)** | Outgoing: 120 BPM, Incoming: 123 BPM (delta +2.5%) | Planner validates transition | Approved under *Club* preset (tolerance ±8%); downgraded to fallback under *Conservative* preset (tolerance ±1.5%). |
| `T3-08` | **F13 (UI Telemetry) $\times$ F10 (State Machine)** | Telemetry overlay observing state machine | State machine advances through all 8 states | Telemetry overlay renders each state in real time with matching generation ID and zero lag. |
| `T3-09` | **F14 (Auto-Remix) $\times$ F03/F04 (Tempo & Harmonic)** | Current track: (124 BPM, 8A); Queue has 5 tracks | Auto-Remix ordering runs | Pairwise scoring ranks (126 BPM, 8A) as #1 and moves it to index 1; lowest compatible track moved to end. |
| `T3-10` | **F09 (Pipeline) $\times$ F07 (Limiter) $\times$ F11 (Cancellation)** | Heavy limiter gain reduction active during crossfade | Immediate playback cancellation event | Audio buffers flushed, gain state reset to 0 dB, zero DC offset click in speaker output. |
| `T3-11` | **F02 (Room Cache) $\times$ F14 (Auto-Remix)** | Upcoming playlist of 20 unanalyzed tracks | Auto-Remix requests compatibility scores | Cache hit profiles evaluated immediately; missing profiles queried asynchronously without UI stall. |
| `T3-12` | **F12 (DJ Presets) $\times$ F08 (Bass Ducking)** | Transition planned under *Seamless* (-4 dB ducking) | User changes preset to *Club* (-10 dB ducking) | Active transition finishes at -4 dB; subsequent transition configures biquad filter for -10 dB shelf depth. |

---

### Tier 4: Real-World Application Scenarios

#### Scenario 1: The Continuous EDM Club Set Flow (`T4-01`)
- **Context**: User launches a 5-track high-energy electronic music playlist under the *Club* preset.
- **Track Profiles**: 124 BPM (8A) $\to$ 126 BPM (9A) $\to$ 128 BPM (9B) $\to$ 128 BPM (10B) $\to$ 126 BPM (10A). All tracks have confidence > 0.90.
- **Execution Flow**:
  1. Track 1 begins playback. Background coroutine pre-analyzes Track 2 and caches profile in Room DB.
  2. As Track 1 approaches outro (16 bars remaining), state machine enters `Preparing`. Secondary `ExoPlayer` pre-warms Track 2 and buffers into `STATE_READY`.
  3. `TransitionPlanner` computes 16-bar phrase overlap with 126/124 tempo alignment (Sonic processor at 1.016x) and downbeat phase lock.
  4. At mix-out downbeat, state advances to `Mixing`. `AutomixDuckAudioProcessor` engages -10 dB bass ducking at 150 Hz.
  5. Dual-stream crossfade executes equal-power curve. Peak limiter holds combined signal $\le -0.1$ dBFS.
  6. At progress 1.0, state machine transitions to `Handoff`. Track 2 becomes primary player. Track 1 is released.
  7. Process repeats seamlessly for Track 3, Track 4, and Track 5.
- **Pass Criteria**: Continuous uninterrupted playback across all 5 tracks; zero audible clipping; all 4 transitions beat-aligned.

#### Scenario 2: The Genre-Bending Crossover Flow (`T4-02`)
- **Context**: Transition from a 140 BPM Dubstep track to a 70 BPM Hip-Hop track under the *Creative* preset.
- **Track Profiles**: Track A: 140.0 BPM, Key 4A; Track B: 70.0 BPM, Key 5A.
- **Execution Flow**:
  1. `TempoMatcher` evaluates $70 / 140 = 0.50$. Octave folding applies $k = +1$ ($70 \times 2 = 140$ BPM), yielding effective tempo delta $0.0\%$.
  2. Camelot harmonic distance is 1 step ($4A \to 5A$).
  3. Planner produces a validated 8-beat transition plan aligning Track A downbeats with Track B half-time downbeats.
  4. Dual-stream audio pipeline executes transition without pitch distortion.
  5. Handoff completes cleanly into Track B at 70 BPM.
- **Pass Criteria**: Octave folding successfully resolves $2:1$ tempo ratio; transition executes without audio artifacts; state machine reaches `Idle`.

#### Scenario 3: The Noisy / Spoken Word Fallback Flow (`T4-03`)
- **Context**: Transition from a studio pop song into a live recording with spoken introduction and clapping.
- **Track Profiles**: Track A: 120.0 BPM (Conf 0.95); Track B: irregular rhythm, spoken word (Conf 0.28).
- **Execution Flow**:
  1. `TrackAnalyzer` analyzes Track B and calculates `confidence = 0.28f`.
  2. Proposed transition plan is submitted to `TransitionPlanValidator`.
  3. Gate check fails due to `confidence < 0.50f`. Validator returns `TransitionPlanResult.Fallback(LOW_CONFIDENCE, defaultPlan)`.
  4. System logs telemetry warning: `"Fallback: Low Confidence (0.28)"`.
  5. Media3 pipeline executes a safe standard 5-second equal-power crossfade without beat alignment or bass ducking.
  6. Transition completes into Track B smoothly without tempo jumping or beat clashes.
- **Pass Criteria**: System avoids clashing beat alignments; fallback crossfade executes cleanly; zero player crash or audio glitch.

#### Scenario 4: The Restless Listener Stress Flow (`T4-04`)
- **Context**: User aggressively seeks, skips, and pauses playback during an active crossfade.
- **Execution Flow**:
  1. Track A is actively crossfading into Track B at progress 0.40 in state `Mixing` (Generation ID #100).
  2. User drags scrubber back 30 seconds (`seekTo(position)`). State machine immediately increments generation ID to #101, transitions to `Canceling`, cancels `crossfadeJob`, and restores Track A volume to 1.0f.
  3. 200ms later, user presses "Next" (`seekToNext()`). Generation increments to #102, secondary player released, Track B starts directly as primary.
  4. 100ms later, user presses "Next" again. Generation increments to #103, Track C starts.
  5. 50ms later, user presses "Pause". Playback pauses immediately.
- **Pass Criteria**: Zero unhandled coroutine exceptions; zero stuck background loops mutating volume; zero leaked `ExoPlayer` instances; clean audio silence upon pause.

#### Scenario 5: Dynamic Auto-Remix Recommendation Flow (`T4-05`)
- **Context**: User enables Auto-Remix Ordering while playing an indie-rock track with an upcoming recommendation pool.
- **Execution Flow**:
  1. User toggles `AutoRemixOrderingKey = true` in Player Settings.
  2. Recommendation engine supplies 10 upcoming candidate tracks.
  3. `AutoRemixScorer` computes pairwise compatibility against current track (118 BPM, 7B).
  4. Best match candidate (120 BPM, 7B, harmonic score 1.0, tempo delta 1.7%) is reordered to queue index 1.
  5. Queue UI reflects updated order with animated reordering state.
  6. As track approaches outro, state machine plans and executes seamless transition into the reordered recommendation.
- **Pass Criteria**: Queue correctly reflects compatibility ordering; transition into top recommendation executes seamlessly; UI updates without stutter.

---

## 5. Defect Escalation & QA Protocol

### 5.1 Test Defect vs Implementation Bug Classification
- **Test Defect (Test Writer owns and fixes)**:
  - Test expectation contradicts `PROJECT.md` or `ORIGINAL_REQUEST.md` specifications.
  - Flaky test timing due to inadequate coroutine virtual time advancement (`advanceUntilIdle()`).
  - Fragile reflection dynamic proxy mocks in ExoPlayer test harness.
- **Implementation Bug (Escalate to implementing agent - DO NOT modify source code)**:
  - `TrackAnalyzer` produces negative BPM or division by zero on silent audio.
  - `TransitionPlanValidator` fails to downgrade low-confidence plans to fallback.
  - `AntiClippingLimiterAudioProcessor` allows peaks exceeding -0.1 dBFS.
  - State machine fails to cancel active transition jobs upon seek/skip/pause.
  - Race condition leading to leaked `ExoPlayer` instances under stress.

### 5.2 Verification Checklist Before Test Delivery
1. All pure JVM unit tests pass via `./gradlew.bat :domain:test` and `:dsp-core:test`.
2. Audio processor tests pass with synthetic ByteBuffers via `./gradlew.bat :audio-dsp:testDebugUnitTest`.
3. Room persistence tests pass with SQLite in-memory DB via `./gradlew.bat :core:testDebugUnitTest`.
4. State machine stress tests pass with 0 thread deadlocks or leaked coroutines.
5. Codebase complies with `AGENT.md` guidelines and zero implementation files modified.
