package echo.music.iad1tya.constants

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import echo.music.domain.autodj.model.DjPreset
import echo.music.iad1tya.extensions.toEnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutoDjPreferencesAdversarialTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var testDataStoreFile: File

  @Before
  fun setUp() {
    testDataStoreFile = tempFolder.newFile("test_autodj_settings.preferences_pb")
  }

  @After
  fun tearDown() {
    testDataStoreFile.delete()
  }

  private fun createDataStore() = PreferenceDataStoreFactory.create(
    produceFile = { testDataStoreFile }
  )

  // T2-F12-01: Corrupted Preset String in DataStore Safe Fallback
  @Test
  fun testCorruptedPresetStringInPreferences_fallbackToDefault() = runBlocking {
    val dataStore = createDataStore()

    val corruptedStrings = listOf(
      "INVALID_PRESET",
      "",
      "   ",
      "12345",
      "null",
      "SEAMLESS_CORRUPTED",
      "DROP_MIX",
      "UNKNOWN_CUSTOM",
      "!@#$%^&*()",
      "CLUB_EXTRA_EXTENDED",
    )

    for (corrupted in corruptedStrings) {
      dataStore.edit { prefs ->
        prefs[AutoDjPresetKey] = corrupted
      }

      val storedRaw = dataStore.data.first()[AutoDjPresetKey]
      assertEquals(corrupted, storedRaw)

      // Fallback via toEnum extension
      val resolvedViaToEnum = storedRaw.toEnum(defaultValue = DjPreset.SEAMLESS)
      assertEquals(
        "Corrupted string '$corrupted' must fallback to SEAMLESS via toEnum",
        DjPreset.SEAMLESS,
        resolvedViaToEnum,
      )

      // Fallback via DjPreset.fromName companion
      val resolvedViaFromName = DjPreset.fromName(storedRaw, default = DjPreset.SEAMLESS)
      assertEquals(
        "Corrupted string '$corrupted' must fallback to SEAMLESS via fromName",
        DjPreset.SEAMLESS,
        resolvedViaFromName,
      )

      // Fallback via DjPreset.fromString companion
      val resolvedViaFromString = DjPreset.fromString(storedRaw)
      assertEquals(
        "Corrupted string '$corrupted' must fallback to SEAMLESS via fromString",
        DjPreset.SEAMLESS,
        resolvedViaFromString,
      )

      // Custom fallback check
      val resolvedWithCustomDefault = DjPreset.fromName(storedRaw, default = DjPreset.CLUB)
      assertEquals(
        "Corrupted string '$corrupted' must fallback to specified default CLUB",
        DjPreset.CLUB,
        resolvedWithCustomDefault,
      )
    }
  }

  // Roundtrip and alias verification for all valid presets
  @Test
  fun testValidPresetStringsInPreferences_roundtripAndAliases() = runBlocking {
    val dataStore = createDataStore()

    for (preset in DjPreset.entries) {
      dataStore.edit { prefs ->
        prefs[AutoDjPresetKey] = preset.name
      }

      val stored = dataStore.data.first()[AutoDjPresetKey]
      val parsed = stored.toEnum(defaultValue = DjPreset.SEAMLESS)
      assertEquals("Preset ${preset.name} must roundtrip cleanly", preset, parsed)
      assertEquals(preset, DjPreset.fromName(stored))
    }

    // Case insensitivity & special aliases
    assertEquals(DjPreset.SEAMLESS, DjPreset.fromName("seamless"))
    assertEquals(DjPreset.CLUB, DjPreset.fromName("Club"))
    assertEquals(DjPreset.CREATIVE, DjPreset.fromName("creative"))
    assertEquals(DjPreset.CONSERVATIVE, DjPreset.fromName("Conservative"))
    assertEquals(DjPreset.STANDARD, DjPreset.fromName("standard"))
    assertEquals(DjPreset.AGGRESSIVE, DjPreset.fromName("aggressive"))
    assertEquals(DjPreset.SMOOTH, DjPreset.fromName("smooth"))
    assertEquals(DjPreset.KEY_LOCK, DjPreset.fromName("key_lock"))
    assertEquals(DjPreset.KEY_LOCK, DjPreset.fromName("KEYLOCK"))
    assertEquals(DjPreset.KEY_LOCK, DjPreset.fromName("keylock"))
    assertEquals(DjPreset.CUSTOM, DjPreset.fromName("custom"))
  }

  // Boundary & Out-of-Range Max Tempo Adjustment
  @Test
  fun testMaxTempoAdjustment_boundariesAndClamping() = runBlocking {
    val dataStore = createDataStore()

    // 1. Default value when unset is 8.0f
    val defaultVal = dataStore.data.first()[AutoDjMaxTempoAdjustmentKey] ?: 8.0f
    assertEquals(8.0f, defaultVal, 0.001f)

    // 2. Setting zero tempo adjustment
    dataStore.edit { it[AutoDjMaxTempoAdjustmentKey] = 0.0f }
    val zeroVal = dataStore.data.first()[AutoDjMaxTempoAdjustmentKey]
    assertNotNull(zeroVal)
    assertEquals(0.0f, zeroVal!!, 0.001f)

    // Clamping to UI slider range 3f..15f
    val clampedZero = zeroVal.coerceIn(3.0f, 15.0f)
    assertEquals(3.0f, clampedZero, 0.001f)

    // 3. Setting negative tempo adjustment
    dataStore.edit { it[AutoDjMaxTempoAdjustmentKey] = -10.0f }
    val negVal = dataStore.data.first()[AutoDjMaxTempoAdjustmentKey]!!
    assertEquals(-10.0f, negVal, 0.001f)
    assertEquals(3.0f, negVal.coerceIn(3.0f, 15.0f), 0.001f)

    // 4. Setting extreme high tempo adjustment
    dataStore.edit { it[AutoDjMaxTempoAdjustmentKey] = 100.0f }
    val highVal = dataStore.data.first()[AutoDjMaxTempoAdjustmentKey]!!
    assertEquals(100.0f, highVal, 0.001f)
    assertEquals(15.0f, highVal.coerceIn(3.0f, 15.0f), 0.001f)

    // 5. Setting Float.NaN and Float.POSITIVE_INFINITY
    dataStore.edit { it[AutoDjMaxTempoAdjustmentKey] = Float.POSITIVE_INFINITY }
    val infVal = dataStore.data.first()[AutoDjMaxTempoAdjustmentKey]!!
    assertTrue(infVal.isInfinite())

    dataStore.edit { it[AutoDjMaxTempoAdjustmentKey] = Float.NaN }
    val nanVal = dataStore.data.first()[AutoDjMaxTempoAdjustmentKey]!!
    assertTrue(nanVal.isNaN())
    // Safe fallback when NaN: default to 8.0f
    val safeAdjustment = if (nanVal.isNaN()) 8.0f else nanVal.coerceIn(3.0f, 15.0f)
    assertEquals(8.0f, safeAdjustment, 0.001f)
  }

  // Preference Toggles: Master Switch & Legacy Fallback
  @Test
  fun testMasterSwitchToggleAndAutomixFallback() = runBlocking {
    val dataStore = createDataStore()

    // 1. Initially both unset -> default false
    val initialPrefs = dataStore.data.first()
    val initialEnabled = initialPrefs[AutoDjEnabledKey] ?: initialPrefs[AutomixCrossfadeKey] ?: false
    assertFalse(initialEnabled)

    // 2. AutomixCrossfadeKey = true, AutoDjEnabledKey unset -> effective true
    dataStore.edit { it[AutomixCrossfadeKey] = true }
    val legacyPrefs = dataStore.data.first()
    val legacyEffective = legacyPrefs[AutoDjEnabledKey] ?: legacyPrefs[AutomixCrossfadeKey] ?: false
    assertTrue("Should inherit true from AutomixCrossfadeKey when AutoDjEnabledKey is unset", legacyEffective)

    // 3. AutoDjEnabledKey = false explicitly overrides AutomixCrossfadeKey = true
    dataStore.edit { it[AutoDjEnabledKey] = false }
    val explicitFalsePrefs = dataStore.data.first()
    val explicitFalse = explicitFalsePrefs[AutoDjEnabledKey] ?: explicitFalsePrefs[AutomixCrossfadeKey] ?: false
    assertFalse("AutoDjEnabledKey = false must take precedence over legacy key", explicitFalse)

    // 4. AutoDjEnabledKey = true explicitly overrides AutomixCrossfadeKey = false
    dataStore.edit {
      it[AutoDjEnabledKey] = true
      it[AutomixCrossfadeKey] = false
    }
    val explicitTruePrefs = dataStore.data.first()
    val explicitTrue = explicitTruePrefs[AutoDjEnabledKey] ?: explicitTruePrefs[AutomixCrossfadeKey] ?: false
    assertTrue("AutoDjEnabledKey = true must take precedence over legacy key", explicitTrue)
  }

  // Preference Toggles: Debug Overlay & Legacy Fallback
  @Test
  fun testDebugOverlayToggleAndAutomixFallback() = runBlocking {
    val dataStore = createDataStore()

    // 1. Initially both unset -> default false
    val initialPrefs = dataStore.data.first()
    val initialDebug = initialPrefs[AutoDjDebugKey] ?: initialPrefs[AutomixDebugOverlayKey] ?: false
    assertFalse(initialDebug)

    // 2. Legacy AutomixDebugOverlayKey = true
    dataStore.edit { it[AutomixDebugOverlayKey] = true }
    val legacyPrefs = dataStore.data.first()
    val legacyDebug = legacyPrefs[AutoDjDebugKey] ?: legacyPrefs[AutomixDebugOverlayKey] ?: false
    assertTrue("Should inherit true from AutomixDebugOverlayKey when AutoDjDebugKey is unset", legacyDebug)

    // 3. AutoDjDebugKey = false explicitly overrides
    dataStore.edit { it[AutoDjDebugKey] = false }
    val explicitFalsePrefs = dataStore.data.first()
    val explicitFalse = explicitFalsePrefs[AutoDjDebugKey] ?: explicitFalsePrefs[AutomixDebugOverlayKey] ?: false
    assertFalse("AutoDjDebugKey = false must take precedence", explicitFalse)

    // 4. AutoDjDebugKey = true explicitly overrides
    dataStore.edit {
      it[AutoDjDebugKey] = true
      it[AutomixDebugOverlayKey] = false
    }
    val explicitTruePrefs = dataStore.data.first()
    val explicitTrue = explicitTruePrefs[AutoDjDebugKey] ?: explicitTruePrefs[AutomixDebugOverlayKey] ?: false
    assertTrue("AutoDjDebugKey = true must take precedence", explicitTrue)
  }

  // Preference Toggles: Auto-Remix Queue Ordering
  @Test
  fun testAutoRemixOrderingToggle() = runBlocking {
    val dataStore = createDataStore()

    // Default false
    val defaultVal = dataStore.data.first()[AutoDjAutoRemixOrderingKey] ?: false
    assertFalse(defaultVal)

    // Enable
    dataStore.edit { it[AutoDjAutoRemixOrderingKey] = true }
    assertTrue(dataStore.data.first()[AutoDjAutoRemixOrderingKey] ?: false)

    // Disable
    dataStore.edit { it[AutoDjAutoRemixOrderingKey] = false }
    assertFalse(dataStore.data.first()[AutoDjAutoRemixOrderingKey] ?: false)
  }

  // Multi-threaded Concurrent Preference Mutations Stress
  @Test
  fun testConcurrentPreferenceMutationsStress() = runBlocking {
    val dataStore = createDataStore()
    val coroutineCount = 20
    val iterationsPerCoroutine = 50

    val jobs = (0 until coroutineCount).map { id ->
      launch(Dispatchers.IO) {
        for (i in 0 until iterationsPerCoroutine) {
          val toggle = (id + i) % 2 == 0
          val preset = DjPreset.entries[(id + i) % DjPreset.entries.size]
          val tempo = 3.0f + ((id + i) % 13)

          dataStore.edit { prefs ->
            prefs[AutoDjEnabledKey] = toggle
            prefs[AutoDjPresetKey] = preset.name
            prefs[AutoDjMaxTempoAdjustmentKey] = tempo
            prefs[AutoDjDebugKey] = !toggle
            prefs[AutoDjAutoRemixOrderingKey] = toggle
          }
        }
      }
    }

    jobs.forEach { it.join() }

    // Final read must be consistent and non-null
    val finalPrefs = dataStore.data.first()
    assertNotNull(finalPrefs[AutoDjEnabledKey])
    assertNotNull(finalPrefs[AutoDjPresetKey])
    assertNotNull(finalPrefs[AutoDjMaxTempoAdjustmentKey])
    assertNotNull(finalPrefs[AutoDjDebugKey])
    assertNotNull(finalPrefs[AutoDjAutoRemixOrderingKey])

    // Preset must successfully parse into a valid DjPreset enum
    val parsedPreset = finalPrefs[AutoDjPresetKey].toEnum(defaultValue = DjPreset.SEAMLESS)
    assertTrue("Parsed preset must be a valid DjPreset enum", DjPreset.entries.contains(parsedPreset))
  }
}
