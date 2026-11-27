package io.openwaters.slackwater.android.map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.premium.PremiumProducts
import io.openwaters.slackwater.android.premium.FakePremiumStore
import io.openwaters.slackwater.android.premium.PremiumPlan
import io.openwaters.slackwater.android.premium.StoreAnswer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PremiumPurchaseControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val yearly = PremiumPlan(PremiumProducts.YEARLY, "$19.99")
    private val lifetime = PremiumPlan(PremiumProducts.LIFETIME, "$49.99")
    private fun text(id: Int, vararg arguments: Any) = compose.activity.getString(id, *arguments)

    private fun show(store: FakePremiumStore) = compose.setContent { PremiumPurchaseControls(store) }

    @Test fun yearlyIsSelectedFirstAndStatesItsTermBesideItsPrice() {
        val store = FakePremiumStore(listOf(yearly, lifetime))
        show(store)
        compose.onNodeWithText(text(R.string.premium_plan_yearly)).assertIsSelected()
        compose.onNodeWithText(text(R.string.premium_plan_yearly_detail)).assertExists()
        compose.onNodeWithText(text(R.string.premium_price_per_year, "$19.99")).assertExists()
        compose.onNodeWithText(text(R.string.premium_get)).assertIsEnabled().performClick()
        assertEquals(listOf(PremiumProducts.YEARLY), store.bought)
    }

    @Test fun choosingLifetimeBuysLifetimeAtItsPlainPrice() {
        val store = FakePremiumStore(listOf(yearly, lifetime))
        show(store)
        compose.onNodeWithText(text(R.string.premium_plan_lifetime)).performClick()
        compose.onNodeWithText(text(R.string.premium_plan_lifetime)).assertIsSelected()
        compose.onAllNodesWithText(text(R.string.premium_price_per_year, "$19.99")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithText(text(R.string.premium_get)).performClick()
        assertEquals(listOf(PremiumProducts.LIFETIME), store.bought)
    }

    @Test fun aPlanTheStoreDidNotReturnCannotBeBoughtAndOffersARetry() {
        val store = FakePremiumStore(listOf(lifetime))
        show(store)
        compose.onNodeWithText(text(R.string.premium_unavailable)).assertExists()
        compose.onNodeWithText(text(R.string.premium_get)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.table_sheet_retry)).performClick()
        compose.runOnIdle { assertEquals(2, store.loads) }
        // The other plan loaded and is still for sale.
        compose.onNodeWithText(text(R.string.premium_plan_lifetime)).performClick()
        compose.onNodeWithText(text(R.string.premium_unavailable)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.premium_get)).assertIsEnabled()
    }

    @Test fun aFailedPurchaseSaysSoAndAnEarlierFailureDoesNot() {
        val store = FakePremiumStore(listOf(yearly, lifetime))
        store.failures.value = 3
        show(store)
        compose.onNodeWithText(text(R.string.premium_purchase_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.premium_get)).performClick()
        compose.runOnIdle { store.failures.value = 4 }
        compose.onNodeWithText(text(R.string.premium_purchase_failed)).assertExists()
        // Trying again clears the message until that attempt fails too.
        compose.onNodeWithText(text(R.string.premium_get)).performClick()
        compose.onNodeWithText(text(R.string.premium_purchase_failed)).assertDoesNotExist()
    }

    @Test fun aPurchaseScreenThatWillNotOpenIsAFailure() {
        show(FakePremiumStore(listOf(yearly), opens = false))
        compose.onNodeWithText(text(R.string.premium_get)).performClick()
        compose.onNodeWithText(text(R.string.premium_purchase_failed)).assertExists()
    }

    @Test fun restoreAsksTheStoreWhatTheAccountOwns() {
        val store = FakePremiumStore(listOf(yearly, lifetime))
        show(store)
        compose.onNodeWithText(text(R.string.premium_restore)).performClick()
        compose.runOnIdle { assertEquals(1, store.reconciles) }
        // The store answered, so nothing is reported as unavailable.
        compose.onNodeWithText(text(R.string.premium_unavailable)).assertDoesNotExist()
    }

    @Test fun aRestoreTheStoreCannotAnswerSaysPurchasesAreUnavailable() {
        show(FakePremiumStore(listOf(yearly, lifetime), answer = StoreAnswer.UNKNOWN))
        compose.onNodeWithText(text(R.string.premium_restore)).performClick()
        compose.onNodeWithText(text(R.string.premium_unavailable)).assertExists()
        // Both plans loaded, so the message is about the restore and there is no plan to retry.
        compose.onNodeWithText(text(R.string.table_sheet_retry)).assertDoesNotExist()
    }
}
