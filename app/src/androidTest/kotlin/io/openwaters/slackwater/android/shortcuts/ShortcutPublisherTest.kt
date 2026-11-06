package io.openwaters.slackwater.android.shortcuts

import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationId
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ShortcutPublisherTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publishesAvailableQueriesAndPreservesExplicitPlaceAndCivilDate() {
        ShortcutPublisher.publish(context)
        val manager = context.getSystemService(ShortcutManager::class.java)
        assertEquals(ShortcutKind.entries.take(manager.maxShortcutCountPerActivity).map { "query-" + it.name },
            manager.dynamicShortcuts.sortedBy { it.rank }.map { it.id })
        val request = ShortcutRequest(ShortcutKind.HIGH, "Oak Bay / Baie & Port", LocalDate.of(2027, 3, 14), StationId("chs/0123"))
        val intent = ShortcutPublisher.intent(context, request)
        assertEquals(QueryActivity::class.java.name, intent.component?.className)
        assertEquals(request, ShortcutPublisher.read(intent))
    }

    @Test fun invalidDateOrEmptyPlaceDoesNotTurnIntoANearbyQuery() {
        listOf("date=2027-02-29", "place=", "station=").forEach {
            assertNull(ShortcutPublisher.read(Intent(Intent.ACTION_VIEW, Uri.parse("slackwater-query://prediction/LOW?" + it))))
        }
    }
}
