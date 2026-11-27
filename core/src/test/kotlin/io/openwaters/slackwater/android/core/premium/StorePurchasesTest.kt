package io.openwaters.slackwater.android.core.premium

import org.junit.Assert.assertEquals
import org.junit.Test

class StorePurchasesTest {
    private fun purchase(product: String, paid: Boolean = true, acknowledged: Boolean = true) =
        StorePurchase(listOf(product), paid, acknowledged, "token-$product")

    private val yearly = purchase(PremiumProducts.YEARLY)
    private val lifetime = purchase(PremiumProducts.LIFETIME)

    @Test fun eitherProductGrantsPremium() {
        assertEquals(true, premiumOwned(listOf(yearly), emptyList()))
        assertEquals(true, premiumOwned(emptyList(), listOf(lifetime)))
        assertEquals(true, premiumOwned(listOf(yearly), listOf(lifetime)))
    }

    @Test fun aPendingPaymentAndAnUnknownProductGrantNothing() {
        val pending = purchase(PremiumProducts.LIFETIME, paid = false)
        val other = purchase("io.openwaters.slackwater.other")
        assertEquals(false, premiumOwned(emptyList(), listOf(pending)))
        assertEquals(false, premiumOwned(listOf(other), listOf(other)))
    }

    @Test fun aLapsedSubscriptionEndsPremiumOnlyWhenBothListsAnswered() {
        assertEquals(false, premiumOwned(emptyList(), emptyList()))
        assertEquals(null, premiumOwned(null, emptyList()))
        assertEquals(null, premiumOwned(emptyList(), null))
        assertEquals(null, premiumOwned(null, null))
    }

    @Test fun aPurchaseInOneListCountsThoughTheOtherQueryFailed() {
        assertEquals(true, premiumOwned(null, listOf(lifetime)))
        assertEquals(true, premiumOwned(listOf(yearly), null))
    }

    @Test fun onlyPaidUnacknowledgedPremiumPurchasesAwaitAcknowledgement() {
        val fresh = purchase(PremiumProducts.YEARLY, acknowledged = false)
        val pending = purchase(PremiumProducts.LIFETIME, paid = false, acknowledged = false)
        val other = purchase("io.openwaters.slackwater.other", acknowledged = false)
        assertEquals(listOf(fresh), awaitingAcknowledgement(listOf(fresh, pending, other, lifetime)))
    }
}
