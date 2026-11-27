package io.openwaters.slackwater.android.premium

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Asks Play again without the buyer. Play refunds a purchase left unacknowledged for three days, and the buyer
 * may not open the app in that time: after an acknowledgement Play did not accept, or while a slow payment is
 * still pending and could complete with the app closed.
 */
class PremiumAcknowledgeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val reconciler = (PremiumStore.get(applicationContext) as? PlayPremiumStore)?.reconciler ?: return Result.success()
        if (PENDING in tags) {
            reconciler.reconcile()
            // Only a complete answer can say the payment is no longer waiting. A pass Play did not answer, in a
            // process that has just started, knows nothing, and stopping then would abandon the payment.
            if (watchIsOver(reconciler.pending, reconciler.unsettled)) WorkManager.getInstance(applicationContext).cancelUniqueWork(PENDING)
            return Result.success()
        }
        // Past Play's three days there is nothing left to save, and a device without Play would retry for ever.
        if (runAttemptCount > ATTEMPTS) {
            android.util.Log.w("Slackwater", "Stopped retrying a purchase acknowledgement")
            return Result.failure()
        }
        // An unanswered list may hide the purchase that is still owed, so only a complete answer ends the retries.
        reconciler.reconcile()
        return if (reconciler.unsettled) Result.retry() else Result.success()
    }

    companion object {
        /** The twice-daily watch ends once Play has answered in full and no payment is waiting. */
        internal fun watchIsOver(pending: Boolean, unsettled: Boolean) = !pending && !unsettled

        private const val RETRY = "premium-acknowledge"
        private const val PENDING = "premium-pending"
        // The waits double from a minute to the five-hour cap, so this many tries span about four days.
        private const val ATTEMPTS = 25
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun follow(context: Context, reason: Follow) {
            val work = WorkManager.getInstance(context)
            when (reason) {
                Follow.ACKNOWLEDGEMENT -> work.enqueueUniqueWork(
                    // The running retry must not be replaced by the reconcile it makes itself.
                    RETRY, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<PremiumAcknowledgeWorker>()
                        .setConstraints(online)
                        .setInitialDelay(1, TimeUnit.MINUTES)
                        // WorkManager caps the wait between tries at five hours.
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                        .build(),
                )
                // Twice a day finds a completed payment well inside the three days Play allows from then.
                Follow.PENDING_PAYMENT -> work.enqueueUniquePeriodicWork(
                    PENDING, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<PremiumAcknowledgeWorker>(12, TimeUnit.HOURS).setConstraints(online).addTag(PENDING).build(),
                )
            }
        }
    }
}
