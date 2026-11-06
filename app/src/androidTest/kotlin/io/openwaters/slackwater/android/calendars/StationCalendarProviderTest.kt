package io.openwaters.slackwater.android.calendars

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.alerts.AlertDestination
import io.openwaters.slackwater.android.core.planning.StationCalendarEvent
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StationCalendarProviderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val resolver = app.contentResolver
    private val provider = StationCalendarProvider(app)
    private val cleanup = mutableListOf<Long>()
    private val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    private val station = StationId("fixture/${UUID.randomUUID()}")
    private val title = "Same station name · Tides"
    private val zone = ZoneId.of("America/Vancouver")

    @Before fun grantAccess() {
        grantCalendarPermissions(app)
    }

    private fun sync(uri: Uri) = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, app.packageName)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    @After fun removeFixtures() {
        for (id in cleanup.distinct()) resolver.delete(sync(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
    }

    private fun entry(offset: Long, length: Long = 300) = StationCalendarEvent(station, "High tide", now.plusSeconds(offset),
        now.plusSeconds(offset + length), "Station-local note", zone)

    private fun rows(calendar: Long): Map<Long, String> =
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.TITLE), "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0",
            arrayOf(calendar.toString()), null)!!.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) }
        }

    @Test fun idempotentTimesTitlesAndReminderComparisonOnRealProvider() {
        val event = entry(3600)
        val id = provider.reconcile(station, null, title, listOf(event, event), now).also(cleanup::add)
        val original = rows(id).keys.single()
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, original)
        resolver.query(uri, arrayOf(Events.HAS_ALARM, Events.EVENT_TIMEZONE, Events.DESCRIPTION,
            Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
            assertEquals(zone.id, it.getString(1))
            assertEquals(event.notes, it.getString(2))
            assertEquals(app.packageName, it.getString(3))
            assertEquals(AlertDestination.intent(app, station, event.start).dataString, it.getString(4))
        }
        fun reconcile(wanted: StationCalendarEvent) = provider.reconcile(station, null, title, listOf(wanted), now)
        val jitter = event.copy(start = event.start.plusSeconds(90), end = event.end.minusSeconds(90),
            notes = "Under 0.6 kn", zone = ZoneId.of("America/Juneau"))
        assertEquals(id, reconcile(jitter))
        assertEquals(setOf(original), rows(id).keys)
        resolver.query(uri, arrayOf(Events.DESCRIPTION, Events.EVENT_TIMEZONE, Events.CUSTOM_APP_URI, Events.DTSTART, Events.DTEND),
            null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(jitter.notes, it.getString(0))
            assertEquals(jitter.zone.id, it.getString(1))
            assertEquals(AlertDestination.intent(app, station, jitter.start).dataString, it.getString(2))
            assertEquals(event.start.toEpochMilli(), it.getLong(3))
            assertEquals(event.end.toEpochMilli(), it.getLong(4))
        }
        reconcile(jitter.copy(end = event.end.minusSeconds(91)))
        val changedTime = rows(id).keys.single()
        assertNotEquals(original, changedTime)
        val renamed = jitter.copy(title = "Low tide")
        reconcile(renamed)
        val changedTitle = rows(id).keys.single()
        assertNotEquals(changedTime, changedTitle)
        resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, changedTitle)
            put(Reminders.MINUTES, 15)
            put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })
        reconcile(renamed)
        val silent = rows(id).keys.single()
        assertNotEquals(changedTitle, silent)
        resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders._ID), "${Reminders.EVENT_ID}=?",
            arrayOf(silent.toString()), null)!!.use { assertEquals(0, it.count) }
    }

    @Test fun personalSameNamedCalendarIsNeverAdoptedEvenWithItsIdStored() {
        val handMade = ContentUris.parseId(resolver.insert(sync(Calendars.CONTENT_URI), ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, app.packageName)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.OWNER_ACCOUNT, app.packageName)
            put(Calendars.NAME, "personal/${UUID.randomUUID()}")
            put(Calendars.CALENDAR_DISPLAY_NAME, title)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.CALENDAR_COLOR, 0xff000000.toInt())
        })!!).also(cleanup::add)
        provider.clearFuture(station, handMade, now, removeCalendar = true)
        val owned = provider.reconcile(station, handMade, title, listOf(entry(2700)), now).also(cleanup::add)
        assertNotEquals(handMade, owned)
        resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, handMade), arrayOf(Calendars._ID),
            null, null, null)!!.use { assertTrue(it.moveToFirst()) }
    }

    @Test fun expiryKeepsHistoryAndExplicitUnsubscribeRemovesCompletedEvents() {
        val events = listOf(entry(60, 60), entry(300, 0), entry(600, 60))
        val id = provider.reconcile(station, null, title, events, now).also(cleanup::add)
        val later = now.plusSeconds(300)
        provider.clearFuture(station, id, later)
        assertEquals(2, rows(id).size)
        assertEquals(2, provider.count(station, id, later))
        provider.clearFuture(station, id, later, removeCalendar = true)
        resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), arrayOf(Calendars._ID),
            null, null, null)!!.use { assertFalse(it.moveToFirst()) }
    }

    @Test fun staleIdsAndTitleCollisionsCannotCaptureAnotherCalendarOrRemovePersonalEvents() {
        val underway = entry(60, 3600)
        val future = entry(7200)
        val id = provider.reconcile(station, null, title, listOf(underway, future), now).also(cleanup::add)
        val other = StationId("fixture/${UUID.randomUUID()}")
        val sibling = provider.reconcile(other, id, title, listOf(future.copy(stationId = other)), now).also(cleanup::add)
        assertNotEquals(id, sibling)
        val personal = ContentUris.parseId(resolver.insert(Events.CONTENT_URI, ContentValues().apply {
            put(Events.CALENDAR_ID, id)
            put(Events.TITLE, future.title)
            put(Events.DTSTART, future.start.toEpochMilli())
            put(Events.DTEND, future.end.toEpochMilli())
            put(Events.EVENT_TIMEZONE, zone.id)
        })!!)
        val later = now.plusSeconds(120)
        val reopened = StationCalendarProvider(app)
        assertEquals(1, reopened.count(station, sibling, later))
        reopened.clearFuture(station, sibling, later, removeCalendar = true)
        assertEquals(0, reopened.count(station, null, later))
        assertEquals(2, rows(id).size)
        assertTrue(personal in rows(id))
        assertEquals(1, reopened.count(other, id, later))
        reopened.clearFuture(other, id, later, removeCalendar = true)
        resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, sibling), arrayOf(Calendars._ID),
            null, null, null)!!.use { assertEquals(0, it.count) }
    }
}
