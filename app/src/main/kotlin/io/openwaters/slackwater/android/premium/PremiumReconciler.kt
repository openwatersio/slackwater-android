package io.openwaters.slackwater.android.premium

import io.openwaters.slackwater.android.core.premium.StorePurchase
import io.openwaters.slackwater.android.core.premium.awaitingAcknowledgement
import io.openwaters.slackwater.android.core.premium.premiumOwned
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The store's record of what an account has bought, reduced to the three calls a reconcile makes. */
interface PurchaseLedger {
    suspend fun connect(): Boolean

    /** Null when the store did not answer. */
    suspend fun subscriptions(): List<StorePurchase>?

    /** Null when the store did not answer. */
    suspend fun oneTimePurchases(): List<StorePurchase>?

    /** False when the store did not accept the acknowledgement. */
    suspend fun acknowledge(token: String): Boolean
}

/** Why a reconcile wants another one to run without the buyer. */
enum class Follow { ACKNOWLEDGEMENT, PENDING_PAYMENT }

/**
 * Turns the store's purchases into the one saved answer, and acknowledges what was paid for.
 * It holds no Android or Play types, so its decisions are tested without a device.
 */
class PremiumReconciler(
    private val ledger: PurchaseLedger,
    private val save: suspend (Boolean) -> Unit,
    private val follow: (Follow) -> Unit,
) {
    private val lock = Mutex()

    /**
     * True after a pass that may have left a paid purchase unacknowledged: the store refused one, or could not be
     * asked about one of its lists.
     */
    @Volatile var unsettled = false
        private set

    /** True while the store is waiting for a payment that could complete with the app closed. */
    @Volatile var pending = false
        private set

    suspend fun reconcile(): StoreAnswer = lock.withLock {
        if (!ledger.connect()) {
            unsettled = true
            return@withLock StoreAnswer.UNKNOWN
        }
        val subscriptions = ledger.subscriptions()
        val oneTime = ledger.oneTimePurchases()
        val all = subscriptions.orEmpty() + oneTime.orEmpty()

        // Acknowledged first and whatever happens to the save below: the payment is real either way, and
        // the store refunds one left unacknowledged for three days.
        var refused = false
        for (waiting in awaitingAcknowledgement(all)) {
            if (!ledger.acknowledge(waiting.token)) refused = true
        }
        // A list the store did not return may hold a purchase that still needs acknowledging.
        unsettled = refused || subscriptions == null || oneTime == null
        pending = all.any { it.pendingPremium }
        // The buyer may not open the app again within three days, so neither retry can wait for them.
        if (refused) follow(Follow.ACKNOWLEDGEMENT)
        if (pending) follow(Follow.PENDING_PAYMENT)

        val owns = premiumOwned(subscriptions, oneTime) ?: return@withLock StoreAnswer.UNKNOWN
        try {
            save(owns)
        } catch (_: IOException) {
            return@withLock StoreAnswer.UNKNOWN
        }
        if (owns) StoreAnswer.OWNED else StoreAnswer.NOT_OWNED
    }
}
