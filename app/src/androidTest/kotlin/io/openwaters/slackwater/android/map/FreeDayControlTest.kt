package io.openwaters.slackwater.android.map

import androidx.compose.material3.Text
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.mutableStateOf
import io.openwaters.slackwater.android.BuildConfig
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.premium.FreeDayRecord
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.planning.allows
import io.openwaters.slackwater.android.premium.entitlementClock
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class FreeDayControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsPremiumEntryMatchesBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MapSettings(replay = null, close = {}) }
        val premium = compose.onNodeWithText(context.getString(R.string.settings_premium))
        if (!BuildConfig.PREMIUM_ENABLED) {
            premium.assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.premium_title)).assertDoesNotExist()
            return
        }
        premium.performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.premium_title)).assertExists()
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
