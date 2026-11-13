package io.openwaters.slackwater.android.map

import androidx.compose.material3.Text
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.mutableStateOf
import io.openwaters.slackwater.android.BuildConfig
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import io.openwaters.slackwater.android.core.premium.FreeDayRecord
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.planning.allows
import io.openwaters.slackwater.android.premium.entitlementClock
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class FreeDayControlTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Settings reads the device's own purchase record, so each case sets it and puts back what was there.
    private fun withEntitlement(entitlement: ReminderEntitlement, check: () -> Unit) {
        val access = ReminderAccessStore.get(context)
        val previous = runBlocking { access.current() }
        try {
            runBlocking { access.update(entitlement) }
            check()
        } finally {
            runBlocking { access.update(previous) }
        }
    }

    @Test fun settingsPremiumEntryMatchesBuild() = withEntitlement(ReminderEntitlement.Free) {
        val support = context.getString(R.string.schedule_support_title)
        val sheet = context.getString(R.string.premium_support)
        // The card names and controls Premium in every build; only a build that sells it offers the sheet.
        if (!BuildConfig.PREMIUM_ENABLED) {
            // A loaded free record is when an enabled build shows the button, so check that state, not mid-load.
            compose.setContent { PremiumSettingsCard(showSky = true, purchased = false, onSky = {}, onSupport = {}) }
            compose.onNodeWithText(context.getString(R.string.premium_sky_controls)).assertExists()
            compose.onNodeWithText(support).assertDoesNotExist()
            return@withEntitlement
        }
        compose.setContent { MapSettings(replay = null, close = {}) }
        compose.onNodeWithText(context.getString(R.string.premium_sky_controls)).performScrollTo().assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(support).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(sheet).assertDoesNotExist()
        compose.onNodeWithText(support).performScrollTo().performClick()
        compose.onNodeWithText(sheet).assertExists()
    }

    @Test fun noOfferOrThanksBeforeThePurchaseRecordLoads() {
        compose.setContent { PremiumSettingsCard(showSky = true, purchased = null, onSky = {}, onSupport = {}) }
        compose.onNodeWithText(context.getString(R.string.premium_sky_controls)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_sky_footnote)).assertExists()
        compose.onNodeWithText(context.getString(R.string.schedule_support_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.premium_purchased)).assertDoesNotExist()
    }

    @Test fun aStoredPurchaseThanksTheSupporterWhoCanStillOpenTheSheet() {
        assumeTrue(BuildConfig.PREMIUM_ENABLED)
        withEntitlement(ReminderEntitlement.Purchased) {
            compose.setContent { MapSettings(replay = null, close = {}) }
            val thanks = context.getString(R.string.premium_purchased)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(thanks).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.schedule_support_title)).assertDoesNotExist()
            compose.onNodeWithText(thanks).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.premium_support)).assertExists()
        }
    }

    @Test fun aFreeUserTurningTheSkyOffIsOfferedPremiumAndKeepsTheSky() = withEntitlement(ReminderEntitlement.Free) {
        val reading = ReadingPreferencesStore.get(context)
        val previous = runBlocking { reading.preferences.first().getOrThrow().showSky }
        try {
            runBlocking { reading.changeSkyVisibility(true) { false } }
            compose.setContent { MapSettings(replay = null, close = {}) }
            val sky = compose.onNode(hasText(context.getString(R.string.detail_show_sky)) and isToggleable())
            sky.performScrollTo().assertIsOn().performClick()
            val offer = context.getString(if (BuildConfig.PREMIUM_ENABLED) R.string.premium_support else R.string.reminder_premium_required)
            // The refusal reads the purchase record off the main thread before it offers Premium.
            compose.waitUntil(10_000) { compose.onAllNodesWithText(offer).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(runBlocking { reading.preferences.first().getOrThrow().showSky })
        } finally {
            runBlocking { reading.changeSkyVisibility(previous) { true } }
        }
    }

    @Test fun unchangedTrialRecomposesAtItsStartAndEnd() {
        val access = mutableStateOf<ReminderEntitlement>(ReminderEntitlement.Free)
        compose.setContent {
            val now = entitlementClock(access.value)
            Text(if (access.value.allows(now)) "active" else "locked")
        }
        compose.onNodeWithText("locked").assertExists()
        compose.runOnIdle {
            val start = Instant.now().plusSeconds(1)
            access.value = ReminderEntitlement.FreeDay(start.plusSeconds(2), start)
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithText("active").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(8_000) { compose.onAllNodesWithText("locked").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun claimingReplacesButtonWithExpiryAndPurchaseHidesTrial() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val record = mutableStateOf(FreeDayRecord(false, null))
        val offer = context.getString(R.string.premium_try_free_day)
        val expiry = context.getString(R.string.premium_free_day_until, "").trim()
        compose.setContent {
            FreeDayControl(record.value) { record.value = FreeDayRecord(false, Instant.now()) }
        }
        compose.onNodeWithText(offer).performClick()
        compose.onNodeWithText(expiry, substring = true).assertExists()
        compose.onNodeWithText(offer).assertDoesNotExist()
        compose.runOnIdle { record.value = record.value.copy(purchased = true) }
        compose.onNodeWithText(context.getString(R.string.premium_purchased)).assertExists()
        compose.onNodeWithText(expiry, substring = true).assertDoesNotExist()
        compose.onNodeWithText(offer).assertDoesNotExist()
    }
}
