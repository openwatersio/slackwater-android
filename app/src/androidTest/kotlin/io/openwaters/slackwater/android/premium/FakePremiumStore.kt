package io.openwaters.slackwater.android.premium

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow

/** Stands in for Google Play. With no plans it is also the idle store every test runs against. */
class FakePremiumStore(
    private val available: List<PremiumPlan> = emptyList(),
    private val opens: Boolean = true,
    private val answer: StoreAnswer = StoreAnswer.NOT_OWNED,
) : PremiumStore {
    override val plans = MutableStateFlow<List<PremiumPlan>?>(null)
    override val failures = MutableStateFlow(0)
    val bought = mutableListOf<String>()
    var loads = 0
    var reconciles = 0

    override suspend fun loadPlans() {
        loads++
        plans.value = available
    }

    override fun buy(activity: Activity, planId: String): Boolean {
        bought += planId
        return opens
    }

    override suspend fun reconcile(): StoreAnswer {
        reconciles++
        return answer
    }
}
