package io.openwaters.slackwater.android.detail

import android.content.res.Configuration
import android.text.format.DateFormat
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.stations.StationListModel
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ChartKeyboardChronologyTest(private val localeTag: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun locales() = listOf("en-US", "ar")
    }
    @get:Rule val compose = createComposeRule()

    @Test fun arrowsCrossLocalMidnightAndReturn() =
        checkArrows("2026-10-31T23:58:00-07:00", "2026-11-01T00:03:00-07:00")

    @Test fun arrowsCrossTheRepeatedHourAndReturn() =
        checkArrows("2026-11-01T01:58:00-07:00", "2026-11-01T01:03:00-08:00")

    private fun checkArrows(from: String, to: String) {
        val locale = Locale.forLanguageTag(localeTag)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        val context = base.createConfigurationContext(config)
        val direction = if (config.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
        val before = OffsetDateTime.parse(from)
        compose.setContent {
            val owner = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides context, LocalConfiguration provides config,
                LocalLayoutDirection provides direction) {
                StationDetailScreen(StationListModel.get(context), StationId("noaa/9449880"), before.toInstant(),
                    before.toInstant(), {}, {}, recordVisit = false)
            }
        }
        val chart = compose.onNodeWithTag("detailTimeline")
        val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
        val clockPattern = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        val clock = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, clockPattern), locale)
        val zone = DateTimeFormatter.ofPattern("z", locale).withZone(ZoneId.of("America/Los_Angeles"))
        fun expectReading(at: OffsetDateTime) {
            val ending = "${date.format(at)} · ${clock.format(at)}, ${zone.format(at.toInstant())}"
            compose.waitUntil("Friday Harbor chart reading ends with $ending ($localeTag)", 30_000) {
                compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.StateDescription)?.endsWith(ending) == true
            }
        }
        expectReading(before)
        assertEquals(if (localeTag == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            chart.fetchSemanticsNode().layoutInfo.layoutDirection)
        chart.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        chart.performKeyInput { pressKey(Key.DirectionRight) }
        expectReading(OffsetDateTime.parse(to))
        chart.performKeyInput { pressKey(Key.DirectionLeft) }
        expectReading(before)
        chart.assertIsFocused()
    }
}
