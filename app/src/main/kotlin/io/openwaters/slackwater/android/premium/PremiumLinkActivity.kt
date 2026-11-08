package io.openwaters.slackwater.android.premium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.openwaters.slackwater.android.calendars.CalendarRepository
import io.openwaters.slackwater.android.data.AlertReconciliation
import io.openwaters.slackwater.android.map.PremiumSheet

class PremiumLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.data
        if (intent.action != Intent.ACTION_VIEW || link?.scheme != "slackwater" || link.authority != "premium" ||
            !(link.path.isNullOrEmpty() || link.path == "/")) {
            finish()
            return
        }
        // A cold link bypasses MainActivity. Its retained reminders and calendars still need to observe the claim.
        AlertReconciliation.get(this).foreground()
        CalendarRepository.get(this).foreground()
        setContent { MaterialTheme { PremiumSheet(onClose = { finish() }) } }
    }
}
