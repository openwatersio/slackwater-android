package io.openwaters.slackwater.android.calendars

import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import io.openwaters.slackwater.android.alerts.AlertDestination
import io.openwaters.slackwater.android.core.planning.StationCalendarEvent
import io.openwaters.slackwater.android.core.station.StationId
import java.io.IOException
import java.time.Instant
import kotlin.math.abs

private const val TOLERANCE_MS = 90_000L

/** Blocking Calendar Provider calls. All destructive writes are scoped to this local account and station. */
class StationCalendarProvider(context: Context) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val account = app.packageName
    private val local = CalendarContract.ACCOUNT_TYPE_LOCAL
    private val calendarOwner = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND " +
        "${Calendars.OWNER_ACCOUNT}=? AND ${Calendars.NAME}=? AND ${Calendars.DELETED}=0"
    private val eventOwner = "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=? AND ${Events.DELETED}=0"

    private fun sync(uri: Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, local).build()

    private fun query(uri: Uri, fields: Array<String>, where: String, args: Array<String>): Cursor =
        resolver.query(uri, fields, where, args, null) ?: throw IOException("Calendar Provider query failed")

    private fun owner(station: StationId) = arrayOf(account, local, account, "station/${station.value}")

    private fun ownedCalendar(station: StationId, savedId: Long?): Long? {
        if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Calendar read and write access required")
        }
        val ids = query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), calendarOwner, owner(station)).use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }
        return savedId?.takeIf(ids::contains) ?: ids.minOrNull()
    }

    private fun apply(operations: List<ContentProviderOperation>) {
        // A 90-day plan can exceed Binder's transaction size. Completed chunks are rediscovered on retry.
        for (chunk in operations.chunked(100)) resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(chunk))
    }

    fun reconcile(stationId: StationId, storedCalendarId: Long?, title: String,
                  desired: List<StationCalendarEvent>, now: Instant): Long {
        require(desired.all { it.stationId == stationId })
        val id = ownedCalendar(stationId, storedCalendarId) ?: create(stationId, title)
        resolver.update(sync(Calendars.CONTENT_URI), ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, title)
        }, "$calendarOwner AND ${Calendars._ID}=? AND ${Calendars.CALENDAR_DISPLAY_NAME} != ?", owner(stationId) + arrayOf(id.toString(), title))
        val millis = now.toEpochMilli()
        val args = arrayOf(id.toString(), stationId.value)
        val existing = query(Events.CONTENT_URI, arrayOf(Events._ID, Events.TITLE, Events.DTSTART,
            Events.DTEND, Events.HAS_ALARM, Events.DESCRIPTION, Events.EVENT_TIMEZONE, Events.CUSTOM_APP_PACKAGE,
            Events.CUSTOM_APP_URI), "$eventOwner AND ${Events.DTSTART}>=?", args + (millis - TOLERANCE_MS).toString()).use {
            buildList {
                while (it.moveToNext()) add(Existing(it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4) != 0,
                    Metadata(it.getString(5), it.getString(6), it.getString(7), it.getString(8))))
            }.toMutableList()
        }
        val changes = mutableListOf<ContentProviderOperation>()
        val wanted = desired.filter { it.start > now }.distinctBy { Triple(it.title, it.start, it.end) }
        for (entry in wanted) {
            val start = entry.start.toEpochMilli()
            val end = entry.end.toEpochMilli()
            val match = existing.indexOfFirst {
                !it.alarm && it.title == entry.title && abs(it.start - start) <= TOLERANCE_MS && abs(it.end - end) <= TOLERANCE_MS
            }
            val metadata = Metadata(entry.notes, entry.zone.id, account, AlertDestination.intent(app, stationId, entry.start).dataString)
            if (match >= 0) {
                val row = existing.removeAt(match)
                if (row.start > millis && row.metadata != metadata) {
                    changes += ContentProviderOperation.newUpdate(sync(Events.CONTENT_URI))
                        .withSelection("$eventOwner AND ${Events.DTSTART}>? AND ${Events._ID}=?",
                            args + arrayOf(millis.toString(), row.id.toString()))
                        .withValues(metadata.values()).build()
                }
                continue
            }
            changes += ContentProviderOperation.newInsert(sync(Events.CONTENT_URI)).withValues(metadata.values().apply {
                put(Events.CALENDAR_ID, id)
                put(Events.SYNC_DATA1, stationId.value)
                put(Events.TITLE, entry.title)
                put(Events.DTSTART, start)
                put(Events.DTEND, end)
                put(Events.HAS_ALARM, 0)
            }).build()
        }
        for (row in existing.filter { it.start > millis }) {
            changes += ContentProviderOperation.newDelete(sync(Events.CONTENT_URI))
                .withSelection("$eventOwner AND ${Events.DTSTART}>? AND ${Events._ID}=?", args + arrayOf(millis.toString(), row.id.toString())).build()
        }
        apply(changes)
        return id
    }

    private data class Existing(val id: Long, val title: String?, val start: Long, val end: Long, val alarm: Boolean,
                                val metadata: Metadata)

    private data class Metadata(val notes: String?, val zone: String?, val appPackage: String?, val link: String?) {
        fun values() = ContentValues().apply {
            put(Events.DESCRIPTION, notes)
            put(Events.EVENT_TIMEZONE, zone)
            put(Events.CUSTOM_APP_PACKAGE, appPackage)
            put(Events.CUSTOM_APP_URI, link)
        }
    }

    private fun create(station: StationId, title: String): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.ACCOUNT_TYPE, local)
            put(Calendars.OWNER_ACCOUNT, account)
            put(Calendars.NAME, "station/${station.value}")
            put(Calendars.CALENDAR_DISPLAY_NAME, title)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.CALENDAR_COLOR, 0xff177282.toInt())
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val result = resolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(
            ContentProviderOperation.newAssertQuery(Calendars.CONTENT_URI)
                .withSelection(calendarOwner, owner(station)).withExpectedCount(0).build(),
            ContentProviderOperation.newInsert(sync(Calendars.CONTENT_URI)).withValues(values).build(),
        ))
        return ContentUris.parseId(result[1].uri ?: throw IOException("Calendar creation returned no ID"))
    }

    private val outsideUnderway = "(${Events.DTSTART}>? OR ${Events.DTEND}<=?)"

    fun count(stationId: StationId, storedCalendarId: Long?, now: Instant): Int {
        val id = ownedCalendar(stationId, storedCalendarId) ?: return 0
        val time = now.toEpochMilli().toString()
        return query(Events.CONTENT_URI, arrayOf(Events._ID), "$eventOwner AND $outsideUnderway",
            arrayOf(id.toString(), stationId.value, time, time)).use { it.count }
    }

    fun clearFuture(stationId: StationId, storedCalendarId: Long?, now: Instant, removeCalendar: Boolean = false) {
        val id = ownedCalendar(stationId, storedCalendarId) ?: return
        val time = now.toEpochMilli().toString()
        if (!removeCalendar) {
            resolver.delete(sync(Events.CONTENT_URI), "$eventOwner AND ${Events.DTSTART}>?",
                arrayOf(id.toString(), stationId.value, time))
            return
        }
        resolver.delete(sync(Events.CONTENT_URI), "$eventOwner AND $outsideUnderway",
            arrayOf(id.toString(), stationId.value, time, time))
        val hasEvents = "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0"
        val args = arrayOf(id.toString())
        if (query(Events.CONTENT_URI, arrayOf(Events._ID), hasEvents, args).use { it.moveToFirst() }) return
        // A person can add an event between the read and delete. Check again in the provider transaction.
        apply(listOf(
            ContentProviderOperation.newAssertQuery(Events.CONTENT_URI).withSelection(hasEvents, args).withExpectedCount(0).build(),
            ContentProviderOperation.newDelete(sync(Calendars.CONTENT_URI))
                .withSelection("$calendarOwner AND ${Calendars._ID}=?", owner(stationId) + id.toString()).build(),
        ))
    }
}
