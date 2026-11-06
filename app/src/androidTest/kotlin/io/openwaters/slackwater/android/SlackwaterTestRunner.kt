package io.openwaters.slackwater.android

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.openwaters.slackwater.android.data.ListPreferencesStore
import kotlinx.coroutines.runBlocking

/**
 * Records that the station list has already asked for location, so no test's activity sits behind the system
 * permission dialog the list raises once on a first launch. Tests of the ask itself drive the prompt directly.
 */
class SlackwaterTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runBlocking { ListPreferencesStore.get(targetContext).markLocationAsked() }
    }
}
