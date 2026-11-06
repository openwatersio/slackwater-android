package io.openwaters.slackwater.android.data

import kotlinx.coroutines.sync.Mutex

/** Rule, threshold, model and entitlement writes serialize with the final delivery decision and publication. */
internal val alertInputLock = Mutex()
