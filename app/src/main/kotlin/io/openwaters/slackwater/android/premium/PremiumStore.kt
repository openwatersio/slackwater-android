package io.openwaters.slackwater.android.premium

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import io.openwaters.slackwater.android.BuildConfig
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.core.premium.PremiumProducts
import io.openwaters.slackwater.android.core.premium.StorePurchase
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A plan the store can sell now, with the price it charges this account. */
data class PremiumPlan(val id: String, val price: String)

/** What the store said about this account. UNKNOWN is a store that could not be asked, and changes nothing saved. */
enum class StoreAnswer { OWNED, NOT_OWNED, UNKNOWN }

/** Where Premium is bought. The Premium sheet talks to this, so tests can stand in for Google Play. */
interface PremiumStore {
    /** Null while the first load runs. A plan missing from a loaded list cannot be bought. */
    val plans: StateFlow<List<PremiumPlan>?>

    /** Counts purchases that failed for a reason other than the buyer backing out. */
    val failures: StateFlow<Int>

    suspend fun loadPlans()

    /** Opens the store's purchase screen. False when it could not open. The result arrives through [reconcile]. */
    fun buy(activity: Activity, planId: String): Boolean

    /** Asks the store what this account owns and saves the answer. Restore and every return to the app use it. */
    suspend fun reconcile(): StoreAnswer

    companion object {
        @VisibleForTesting @Volatile internal var override: PremiumStore? = null
        private var shared: PremiumStore? = null

        @Synchronized fun get(context: Context): PremiumStore = override ?: shared ?: PlayPremiumStore(
            context.applicationContext,
            ReminderAccessStore.get(context),
        ).also { shared = it }

        /**
         * A build that does not sell Premium connects to Play billing only for an account already saved as a buyer,
         * which a test build can leave behind. Without that check a refund or lapse would never reach it.
         */
        fun foreground(context: Context) {
            val app = context.applicationContext
            background.launch {
                if (!BuildConfig.PREMIUM_ENABLED && ReminderAccessStore.get(app).current() != ReminderEntitlement.Purchased) return@launch
                get(app).reconcile()
            }
        }

        // Nothing a store does on the way back to the app may take the app down with it.
        internal val quiet = CoroutineExceptionHandler { _, error -> android.util.Log.w("Slackwater", "Premium store check failed", error) }
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default + quiet)
    }
}

internal class PlayPremiumStore(private val context: Context, private val access: ReminderAccessStore) : PremiumStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + PremiumStore.quiet)
    private val connecting = Mutex()
    private val details = MutableStateFlow<Map<String, Offer>>(emptyMap())
    private val _plans = MutableStateFlow<List<PremiumPlan>?>(null)
    private val _failures = MutableStateFlow(0)
    override val plans: StateFlow<List<PremiumPlan>?> = _plans
    override val failures: StateFlow<Int> = _failures

    private val ledger: PurchaseLedger = object : PurchaseLedger {
        override suspend fun connect() = connected()
        override suspend fun subscriptions() = owned(ProductType.SUBS)
        override suspend fun oneTimePurchases() = owned(ProductType.INAPP)
        override suspend fun acknowledge(token: String): Boolean {
            val result = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build())
            if (result.responseCode != BillingResponseCode.OK) android.util.Log.w("Slackwater", "Play did not acknowledge a purchase: ${result.responseCode}")
            return result.responseCode == BillingResponseCode.OK
        }
    }

    /** [PremiumAcknowledgeWorker] reads what the last pass left open. */
    val reconciler: PremiumReconciler = PremiumReconciler(ledger, access::syncPurchase) { PremiumAcknowledgeWorker.follow(context, it) }

    private class Offer(val product: ProductDetails, val token: String?, val price: String)

    private val updates: PurchasesUpdatedListener = PurchasesUpdatedListener { result, _ ->
        when (result.responseCode) {
            // The purchases Play hands over here can be incomplete, so the saved answer always comes from a full query.
            BillingResponseCode.OK, BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch {
                reconcile()
                // Someone has just paid. If Play could not be asked now, the acknowledgement must not wait for their next visit.
                if (reconciler.unsettled) PremiumAcknowledgeWorker.follow(context, Follow.ACKNOWLEDGEMENT)
            }
            BillingResponseCode.USER_CANCELED -> Unit
            else -> _failures.update { it + 1 }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(updates)
        // Play requires the opt-in. A pending payment still grants nothing until it completes.
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun connected(): Boolean = connecting.withLock {
        if (client.isReady) return true
        // Play does not promise to call back, and a wait without an end would hold every later check behind it.
        withTimeoutOrNull(CONNECT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) continuation.resume(result.responseCode == BillingResponseCode.OK)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
            }
        } ?: false
    }

    override suspend fun loadPlans() {
        if (connected()) {
            // Play answers one product type per query.
            for ((type, id) in listOf(ProductType.SUBS to PremiumProducts.YEARLY, ProductType.INAPP to PremiumProducts.LIFETIME)) {
                when (val found = offer(type, id)) {
                    // A query that failed keeps what an earlier load found.
                    Lookup.Failed -> Unit
                    // Play answered and no longer sells it, so an old price must not stay for sale.
                    Lookup.Absent -> details.update { it - id }
                    is Lookup.Found -> details.update { it + (id to found.offer) }
                }
            }
        }
        _plans.value = PremiumProducts.all.mapNotNull { id -> details.value[id]?.let { PremiumPlan(id, it.price) } }
    }

    private sealed interface Lookup {
        data object Failed : Lookup
        data object Absent : Lookup
        class Found(val offer: Offer) : Lookup
    }

    private suspend fun offer(type: String, id: String): Lookup {
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()
        val result = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
        if (result.billingResult.responseCode != BillingResponseCode.OK) return Lookup.Failed
        val found = result.productDetailsList.orEmpty().firstOrNull { it.productId == id } ?: return Lookup.Absent
        if (type == ProductType.SUBS) {
            // A base plan is an offer with no offer ID, and its last phase is the price that recurs. The sheet says
            // "per year" and "renews every year", so only a plan that renews yearly is sold: a monthly or prepaid
            // plan added in Play Console must not take its place.
            val plan = found.subscriptionOfferDetails.orEmpty().firstOrNull {
                val recurring = it.pricingPhases.pricingPhaseList.lastOrNull()
                it.offerId == null && recurring?.billingPeriod == YEAR && recurring.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
            } ?: return Lookup.Absent
            return Lookup.Found(Offer(found, plan.offerToken, plan.pricingPhases.pricingPhaseList.last().formattedPrice))
        }
        val once = found.oneTimePurchaseOfferDetails ?: return Lookup.Absent
        return Lookup.Found(Offer(found, once.offerToken?.takeIf { it.isNotEmpty() }, once.formattedPrice))
    }

    override fun buy(activity: Activity, planId: String): Boolean {
        val offer = details.value[planId] ?: return false
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.product)
        offer.token?.let(product::setOfferToken)
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product.build())).build()
        return client.launchBillingFlow(activity, flow).responseCode == BillingResponseCode.OK
    }

    override suspend fun reconcile(): StoreAnswer = reconciler.reconcile()

    private suspend fun owned(type: String): List<StorePurchase>? {
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
        if (result.billingResult.responseCode != BillingResponseCode.OK) return null
        return result.purchasesList.map {
            StorePurchase(
                it.products, it.purchaseState == Purchase.PurchaseState.PURCHASED, it.isAcknowledged, it.purchaseToken,
                awaitingPayment = it.purchaseState == Purchase.PurchaseState.PENDING,
            )
        }
    }

    private companion object {
        const val CONNECT_MILLIS = 15_000L
        // Play gives billing periods as ISO 8601 durations.
        const val YEAR = "P1Y"
    }
}
