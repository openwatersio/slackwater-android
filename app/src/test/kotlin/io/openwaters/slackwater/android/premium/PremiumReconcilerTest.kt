package io.openwaters.slackwater.android.premium

import io.openwaters.slackwater.android.core.premium.PremiumProducts
import io.openwaters.slackwater.android.core.premium.StorePurchase
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumReconcilerTest {
    private val events = mutableListOf<String>()
    private val follows = mutableListOf<Follow>()

    private inner class Ledger(
        val online: Boolean = true,
        val subscriptions: List<StorePurchase>? = emptyList(),
        val oneTime: List<StorePurchase>? = emptyList(),
        val accepts: Boolean = true,
    ) : PurchaseLedger {
        override suspend fun connect() = online
        override suspend fun subscriptions() = subscriptions
        override suspend fun oneTimePurchases() = oneTime
        override suspend fun acknowledge(token: String): Boolean {
            events += "acknowledge $token"
            return accepts
        }
    }

    private fun reconciler(ledger: Ledger, saveFails: Boolean = false) = PremiumReconciler(
        ledger,
        save = { owns ->
            if (saveFails) throw IOException("disk full")
            events += "save $owns"
        },
        follow = { follows += it },
    )

    private fun purchase(product: String, paid: Boolean = true, acknowledged: Boolean = true) =
        StorePurchase(listOf(product), paid, acknowledged, product.substringAfterLast('.'))

    private val yearly = purchase(PremiumProducts.YEARLY)
    private val newLifetime = purchase(PremiumProducts.LIFETIME, acknowledged = false)

    @Test fun aNewPurchaseIsAcknowledgedBeforeItIsSaved() = runBlocking {
        val reconciler = reconciler(Ledger(oneTime = listOf(newLifetime)))
        assertEquals(StoreAnswer.OWNED, reconciler.reconcile())
        assertEquals(listOf("acknowledge lifetime", "save true"), events)
        assertFalse(reconciler.unsettled)
        assertEquals(emptyList<Follow>(), follows)
    }

    @Test fun anAcknowledgedPurchaseIsSavedAndNotAcknowledgedAgain() = runBlocking {
        assertEquals(StoreAnswer.OWNED, reconciler(Ledger(subscriptions = listOf(yearly))).reconcile())
        assertEquals(listOf("save true"), events)
    }

    @Test fun aRefusedAcknowledgementStillGrantsPremiumAndAsksForARetry() = runBlocking {
        val reconciler = reconciler(Ledger(oneTime = listOf(newLifetime), accepts = false))
        assertEquals(StoreAnswer.OWNED, reconciler.reconcile())
        assertEquals(listOf("acknowledge lifetime", "save true"), events)
        assertTrue(reconciler.unsettled)
        assertEquals(listOf(Follow.ACKNOWLEDGEMENT), follows)
    }

    @Test fun aPurchaseIsAcknowledgedThoughTheSaveFails() = runBlocking {
        val reconciler = reconciler(Ledger(oneTime = listOf(newLifetime)), saveFails = true)
        assertEquals(StoreAnswer.UNKNOWN, reconciler.reconcile())
        assertEquals(listOf("acknowledge lifetime"), events)
        // Play has the acknowledgement, and the next pass saves the answer.
        assertFalse(reconciler.unsettled)
    }

    @Test fun aLapseOrRefundClearsPremiumWhenBothListsAnswer() = runBlocking {
        val reconciler = reconciler(Ledger())
        assertEquals(StoreAnswer.NOT_OWNED, reconciler.reconcile())
        assertEquals(listOf("save false"), events)
        assertFalse(reconciler.unsettled)
    }

    @Test fun aStoreThatCannotBeReachedChangesNothing() = runBlocking {
        val reconciler = reconciler(Ledger(online = false, subscriptions = listOf(yearly)))
        assertEquals(StoreAnswer.UNKNOWN, reconciler.reconcile())
        assertEquals(emptyList<String>(), events)
        assertTrue(reconciler.unsettled)
        // An offline launch is not a reason to schedule work; only a purchase just made is, and its caller decides.
        assertEquals(emptyList<Follow>(), follows)
    }

    @Test fun oneUnansweredListKeepsTheSavedAnswerAndStaysUnsettled() = runBlocking {
        val reconciler = reconciler(Ledger(subscriptions = null))
        assertEquals(StoreAnswer.UNKNOWN, reconciler.reconcile())
        assertEquals(emptyList<String>(), events)
        assertTrue(reconciler.unsettled)
    }

    @Test fun aPurchaseInTheListThatAnsweredCountsButTheOtherMayStillOweAnAcknowledgement() = runBlocking {
        val reconciler = reconciler(Ledger(subscriptions = null, oneTime = listOf(purchase(PremiumProducts.LIFETIME))))
        assertEquals(StoreAnswer.OWNED, reconciler.reconcile())
        assertEquals(listOf("save true"), events)
        assertTrue(reconciler.unsettled)
    }

    @Test fun aPendingPaymentGrantsNothingAndIsWatched() = runBlocking {
        val waiting = purchase(PremiumProducts.LIFETIME, paid = false, acknowledged = false).copy(awaitingPayment = true)
        val reconciler = reconciler(Ledger(oneTime = listOf(waiting)))
        assertEquals(StoreAnswer.NOT_OWNED, reconciler.reconcile())
        assertEquals(listOf("save false"), events)
        assertTrue(reconciler.pending)
        assertEquals(listOf(Follow.PENDING_PAYMENT), follows)
        assertFalse(PremiumAcknowledgeWorker.watchIsOver(reconciler.pending, reconciler.unsettled))
    }

    @Test fun aPurchaseInNeitherStateIsNotWaitedFor() = runBlocking {
        val unspecified = purchase(PremiumProducts.LIFETIME, paid = false, acknowledged = false)
        val reconciler = reconciler(Ledger(oneTime = listOf(unspecified)))
        assertEquals(StoreAnswer.NOT_OWNED, reconciler.reconcile())
        assertFalse(reconciler.pending)
        assertEquals(emptyList<Follow>(), follows)
    }

    @Test fun theWatchForAPendingPaymentOutlastsAPassPlayDidNotAnswer() = runBlocking {
        // A worker in a new process starts knowing nothing; an unanswered pass must not read as "nothing pending".
        val offline = reconciler(Ledger(online = false))
        offline.reconcile()
        assertFalse(offline.pending)
        assertFalse(PremiumAcknowledgeWorker.watchIsOver(offline.pending, offline.unsettled))

        val oneListMissing = reconciler(Ledger(oneTime = null))
        oneListMissing.reconcile()
        assertFalse(PremiumAcknowledgeWorker.watchIsOver(oneListMissing.pending, oneListMissing.unsettled))

        val answered = reconciler(Ledger())
        answered.reconcile()
        assertTrue(PremiumAcknowledgeWorker.watchIsOver(answered.pending, answered.unsettled))
    }

    @Test fun aPaymentThatCompletesIsAcknowledgedAndNoLongerWatched() = runBlocking {
        val completed = reconciler(Ledger(oneTime = listOf(newLifetime)))
        assertEquals(StoreAnswer.OWNED, completed.reconcile())
        assertFalse(completed.pending)
        assertEquals(listOf("acknowledge lifetime", "save true"), events)
    }

    @Test fun aSettledPassClearsWhatAnEarlierOneLeftOpen() = runBlocking {
        var online = false
        val ledger = object : PurchaseLedger {
            override suspend fun connect() = online
            override suspend fun subscriptions() = emptyList<StorePurchase>()
            override suspend fun oneTimePurchases() = emptyList<StorePurchase>()
            override suspend fun acknowledge(token: String) = true
        }
        val reconciler = PremiumReconciler(ledger, save = {}, follow = {})
        reconciler.reconcile()
        assertTrue(reconciler.unsettled)
        online = true
        reconciler.reconcile()
        assertFalse(reconciler.unsettled)
    }
}
