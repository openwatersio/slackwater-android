package io.openwaters.slackwater.android.core.premium

/** The two Play products that grant Premium. The yearly ID matches iOS; the stores do not share purchases. */
object PremiumProducts {
    const val YEARLY = "io.openwaters.slackwater.premium.yearly"
    const val LIFETIME = "io.openwaters.slackwater.premium.lifetime"
    val all = setOf(YEARLY, LIFETIME)
}

/** One purchase as the store reports it, reduced to what entitlement needs. */
data class StorePurchase(
    val products: List<String>,
    val paid: Boolean,
    val acknowledged: Boolean,
    val token: String,
    /** The store is still waiting for the money, as with cash paid at a shop. It can arrive with the app closed. */
    val awaitingPayment: Boolean = false,
) {
    val grantsPremium get() = paid && products.any(PremiumProducts.all::contains)

    /** A purchase in neither state, which the store can also report, is not one to wait for. */
    val pendingPremium get() = awaitingPayment && !paid && products.any(PremiumProducts.all::contains)
}

/**
 * What the store's two purchase lists say about Premium. A null list is a query that failed.
 * Null means the store could not say, so the saved answer stands: an offline launch must not
 * end a purchase, and one failed query must not hide what the other would have found.
 */
fun premiumOwned(subscriptions: List<StorePurchase>?, oneTime: List<StorePurchase>?): Boolean? {
    val lists = listOf(subscriptions, oneTime)
    if (lists.any { list -> list.orEmpty().any { it.grantsPremium } }) return true
    return if (lists.all { it != null }) false else null
}

/** Play refunds a purchase that is not acknowledged within three days. A pending payment has nothing to acknowledge. */
fun awaitingAcknowledgement(purchases: List<StorePurchase>) = purchases.filter { it.grantsPremium && !it.acknowledged }
