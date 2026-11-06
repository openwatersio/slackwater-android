package io.openwaters.slackwater.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ReadingPreferencesStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun <T> withScope(block: suspend (CoroutineScope) -> T): T {
        val job = SupervisorJob()
        return try {
            block(CoroutineScope(job + Dispatchers.IO))
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun withFile(block: suspend (File) -> Unit) = runBlocking {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        try { block(file) } finally { file.delete() }
    }

    @Test fun defaultsFollowTheRegionUntilChosen() = withFile { file ->
        withScope { scope ->
            val preferences = ReadingPreferencesStore.open(file, scope).preferences.first().getOrThrow()
            assertEquals(ReadingPreferences(), preferences)
            assertEquals(HeightUnits.IMPERIAL, preferences.heightUnits(Locale.US))
            assertEquals(HeightUnits.METRIC, preferences.heightUnits(Locale.CANADA))
            assertEquals(HeightUnits.METRIC, preferences.heightUnits(Locale.ROOT))
            assertFalse(preferences.slackWindowOff)
        }
        assertFalse("reading the defaults writes nothing", file.exists())
    }

    @Test fun choicesSurviveReopen() = withFile { file ->
        withScope { scope ->
            val store = ReadingPreferencesStore.open(file, scope)
            store.setHeightUnits(HeightUnits.METRIC)
            store.setSpeedUnit(SpeedUnit.METRES_PER_SECOND)
            store.setSetFormat(SetFormat.DEGREES)
            store.setSlackWindowKnots(0.0)
        }
        withScope { scope ->
            val preferences = ReadingPreferencesStore.open(file, scope).preferences.first().getOrThrow()
            assertEquals(ReadingPreferences(HeightUnits.METRIC, SpeedUnit.METRES_PER_SECOND, SetFormat.DEGREES, 0.0), preferences)
            assertEquals("a saved choice wins over the region", HeightUnits.METRIC, preferences.heightUnits(Locale.US))
            assertTrue(preferences.slackWindowOff)
        }
    }

    @Test fun storesTheValuesIosAndTheWebUse() = withFile { file ->
        withScope { scope ->
            val raw = PreferenceDataStoreFactory.create(scope = scope) { file }
            raw.edit {
                it[stringPreferencesKey("heightUnits")] = "imperial"
                it[stringPreferencesKey("speedUnit")] = "kmh"
                it[stringPreferencesKey("setFormat")] = "degrees"
                it[doublePreferencesKey("slackWindowKnots")] = 1.2
            }
        }
        withScope { scope ->
            assertEquals(
                ReadingPreferences(HeightUnits.IMPERIAL, SpeedUnit.KILOMETRES_PER_HOUR, SetFormat.DEGREES, 1.2),
                ReadingPreferencesStore.open(file, scope).preferences.first().getOrThrow(),
            )
        }
    }

    @Test fun unreadableValuesReadAsDefaultsAndStayUntilReplaced() = withFile { file ->
        val unrelated = stringPreferencesKey("aNewerSetting")
        withScope { scope ->
            val raw = PreferenceDataStoreFactory.create(scope = scope) { file }
            raw.edit {
                it[stringPreferencesKey("heightUnits")] = "fathoms"
                it[intPreferencesKey("speedUnit")] = 3
                it[stringPreferencesKey("setFormat")] = ""
                it[doublePreferencesKey("slackWindowKnots")] = Double.NaN
                it[unrelated] = "preserve"
            }
        }
        withScope { scope ->
            val store = ReadingPreferencesStore.open(file, scope)
            assertEquals(ReadingPreferences(), store.preferences.first().getOrThrow())
            store.setSetFormat(SetFormat.DEGREES)
        }
        withScope { scope ->
            val raw = PreferenceDataStoreFactory.create(scope = scope) { file }.data.first()
            assertEquals("fathoms", raw[stringPreferencesKey("heightUnits")])
            assertEquals(3, raw[intPreferencesKey("speedUnit")])
            assertEquals("preserve", raw[unrelated])
            assertEquals("degrees", raw[stringPreferencesKey("setFormat")])
        }
    }

    @Test fun slackWindowIsRoundedToATenthWithinRange() {
        val cases = mapOf(
            0.3 - 0.1 - 0.1 - 0.1 to 0.0,
            0.04 to 0.0,
            0.05 to 0.1,
            -0.04 to 0.0,
            -0.05 to ReadingPreferences.DEFAULT_SLACK_WINDOW_KNOTS,
            -1.0 to ReadingPreferences.DEFAULT_SLACK_WINDOW_KNOTS,
            1.26 to 1.3,
            10.04 to 10.0,
            10.06 to ReadingPreferences.DEFAULT_SLACK_WINDOW_KNOTS,
            Double.POSITIVE_INFINITY to ReadingPreferences.DEFAULT_SLACK_WINDOW_KNOTS,
        )
        for ((input, expected) in cases) {
            val normalized = ReadingPreferences.normalizedSlackWindowKnots(input)
            assertEquals("$input", expected, normalized, 0.0)
            assertFalse("$input reads as -0.0", 1 / normalized < 0)
        }
    }

    @Test fun setterStoresTheNormalizedValue() = withFile { file ->
        withScope { scope ->
            val store = ReadingPreferencesStore.open(file, scope)
            store.setSlackWindowKnots(0.74)
            assertEquals(0.7, store.preferences.first().getOrThrow().slackWindowKnots, 0.0)
            // Stepping past either end keeps the nearest setting.
            store.setSlackWindowKnots(10.1)
            assertEquals(10.0, store.preferences.first().getOrThrow().slackWindowKnots, 0.0)
            store.setSlackWindowKnots(0.0 - 0.1)
            assertEquals(0.0, store.preferences.first().getOrThrow().slackWindowKnots, 0.0)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.setSlackWindowKnots(Double.NaN) } }
            assertEquals(0.0, store.preferences.first().getOrThrow().slackWindowKnots, 0.0)
        }
    }

    @Test fun anUnopenableFileEmitsAFailure() = withFile { file ->
        // A directory where the file belongs fails to read without counting as corruption.
        file.mkdirs()
        try {
            withScope { scope ->
                val result = ReadingPreferencesStore.open(file, scope).preferences.first()
                assertTrue(result.exceptionOrNull() is IOException)
            }
        } finally {
            file.deleteRecursively()
        }
    }

    @Test fun aCorruptFileIsReplacedSoSettingsStayWritable() = withFile { file ->
        file.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37))
        withScope { scope ->
            val store = ReadingPreferencesStore.open(file, scope)
            assertEquals(ReadingPreferences(), store.preferences.first().getOrThrow())
            store.setSpeedUnit(SpeedUnit.KILOMETRES_PER_HOUR)
            assertEquals(SpeedUnit.KILOMETRES_PER_HOUR, store.preferences.first().getOrThrow().speedUnit)
        }
    }
}
