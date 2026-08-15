package fi.tyovuorolukija.calendar

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import fi.tyovuorolukija.data.ShiftDatabase
import fi.tyovuorolukija.data.SyncAction
import fi.tyovuorolukija.data.SyncBatch
import fi.tyovuorolukija.data.SyncedShift
import fi.tyovuorolukija.parser.Shift
import fi.tyovuorolukija.parser.ShiftTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val ownerAccount: String?,
)

data class SyncSummary(
    val batchId: Long,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val failed: List<String>,
) {
    val changeCount: Int get() = inserted + updated + deleted
}

data class UndoSummary(
    val removed: Int,
    val restored: Int,
    val failed: List<String>,
)

/** Kalenterissa oleva tila ennen muutosta. */
private data class EventSnapshot(
    val title: String?,
    val startMillis: Long?,
    val endMillis: Long?,
    val description: String?,
)

/**
 * Kalenterikirjoitus [CalendarContract]-provideria vastaan.
 *
 * Miksi ei Google Calendar API: CalendarContract ei vaadi OAuthia eikä Google Cloud
 * -projektia. Käyttäjä valitsee laitteelta kalenterin; jos se on Google-tili,
 * tapahtumat synkkaavat pilveen itsestään.
 *
 * Kirjoitus on aina idempotenttia: [syncShifts] päivittää aiemmin luodut tapahtumat
 * ja poistaa ne joita uudessa skannauksessa ei enää ole. Jokainen tallennuskerta
 * kirjataan eräksi, jonka [undoLastBatch] osaa kumota kokonaan.
 */
class CalendarRepository(private val context: Context) {

    private val db = ShiftDatabase.get(context)
    private val dao = db.syncedShifts()
    private val batches = db.syncBatches()

    @SuppressLint("MissingPermission") // luvat tarkistetaan UI-kerroksessa ennen kutsua
    suspend fun writableCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        // ACCESS_LEVEL >= CONTRIBUTOR riittää tapahtumien lisäämiseen.
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? " +
            "AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    displayName = c.getString(1) ?: "(nimetön)",
                    accountName = c.getString(2) ?: "",
                    ownerAccount = c.getString(3),
                )
            }
        }
        out
    }

    /** Viimeisin tallennuserä, jos sellainen on kumottavissa. */
    suspend fun lastBatch(): SyncBatch? = withContext(Dispatchers.IO) {
        batches.lastBatch()?.takeIf { batches.actionCount(it.id) > 0 }
    }

    /**
     * Kirjoittaa [shifts] kalenteriin niin, että lopputulos vastaa skannausta:
     * uudet lisätään, muuttuneet päivitetään, kadonneet poistetaan.
     *
     * @param range jakson päivävälit — poisto rajataan tähän, jotta muiden jaksojen
     *   tapahtumat eivät katoa.
     */
    @SuppressLint("MissingPermission")
    suspend fun syncShifts(
        calendarId: Long,
        calendarName: String,
        shifts: List<Shift>,
        range: ClosedRange<LocalDate>,
    ): SyncSummary = withContext(Dispatchers.IO) {
        val batchId = batches.insertBatch(
            SyncBatch(
                calendarId = calendarId,
                calendarName = calendarName,
                createdAt = System.currentTimeMillis(),
            )
        )

        val existing = dao.inRange(calendarId, range.start.toString(), range.endInclusive.toString())
            .associateBy { it.localDate to it.startMillis }
        val seen = mutableSetOf<Pair<String, Long>>()
        val failed = mutableListOf<String>()
        var inserted = 0
        var updated = 0

        for (shift in shifts) {
            val startMillis = ShiftTimes.startMillis(shift)
            val endMillis = ShiftTimes.endMillis(shift)
            val key = shift.date.toString() to startMillis
            seen += key

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, shift.title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, ShiftTimes.HELSINKI.id)
                put(CalendarContract.Events.DESCRIPTION, description(shift))
            }

            val prior = existing[key]
            val snapshot = prior?.let { readEvent(it.eventId) }

            if (prior != null && snapshot != null) {
                val uri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI, prior.eventId,
                )
                val rows = runCatching {
                    context.contentResolver.update(uri, values, null, null)
                }.getOrElse { 0 }
                if (rows > 0) {
                    updated++
                    dao.upsert(prior.copy(
                        endMillis = endMillis,
                        code = shift.code,
                        title = shift.title,
                    ))
                    batches.insertAction(
                        action(batchId, SyncAction.UPDATE, prior.eventId, calendarId,
                            shift, startMillis, endMillis, snapshot)
                    )
                } else {
                    failed += "${shift.date} ${shift.title}: päivitys epäonnistui"
                }
            } else {
                val uri = runCatching {
                    context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                }.getOrNull()
                val newId = uri?.lastPathSegment?.toLongOrNull()
                if (newId == null) {
                    failed += "${shift.date} ${shift.title}: lisäys epäonnistui"
                } else {
                    inserted++
                    prior?.let { dao.deleteById(it.id) }
                    dao.upsert(
                        SyncedShift(
                            calendarId = calendarId,
                            localDate = shift.date.toString(),
                            startMillis = startMillis,
                            endMillis = endMillis,
                            code = shift.code,
                            title = shift.title,
                            eventId = newId,
                        )
                    )
                    batches.insertAction(
                        action(batchId, SyncAction.INSERT, newId, calendarId,
                            shift, startMillis, endMillis, null)
                    )
                }
            }
        }

        // Jaksossa aiemmin olleet vuorot jotka eivät enää esiinny -> poista.
        var deleted = 0
        for ((key, stale) in existing) {
            if (key in seen) continue
            val snapshot = readEvent(stale.eventId)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, stale.eventId)
            runCatching { context.contentResolver.delete(uri, null, null) }
            dao.deleteById(stale.id)
            deleted++
            batches.insertAction(
                SyncAction(
                    batchId = batchId,
                    action = SyncAction.DELETE,
                    eventId = stale.eventId,
                    calendarId = calendarId,
                    localDate = stale.localDate,
                    startMillis = stale.startMillis,
                    endMillis = stale.endMillis,
                    code = stale.code,
                    title = stale.title,
                    prevTitle = snapshot?.title ?: stale.title,
                    prevStartMillis = snapshot?.startMillis ?: stale.startMillis,
                    prevEndMillis = snapshot?.endMillis ?: stale.endMillis,
                    prevDescription = snapshot?.description,
                )
            )
        }

        // Vain viimeisin erä on kumottavissa — vanhemmat vievät turhaan tilaa.
        batches.pruneActionsBefore(batchId)
        batches.pruneBatchesBefore(batchId)

        SyncSummary(batchId, inserted, updated, deleted, failed)
    }

    /**
     * Kumoaa viimeisimmän tallennuskerran: lisätyt poistetaan, päivitetyt palautetaan
     * entiselleen, poistetut luodaan uudelleen.
     *
     * Toimenpiteet käydään läpi käänteisessä järjestyksessä, jotta lopputulos on sama
     * kuin ennen tallennusta myös silloin kun samaan päivään osui useampi muutos.
     */
    @SuppressLint("MissingPermission")
    suspend fun undoLastBatch(): UndoSummary = withContext(Dispatchers.IO) {
        val batch = batches.lastBatch() ?: return@withContext UndoSummary(0, 0, emptyList())
        val actions = batches.actionsFor(batch.id)
        val failed = mutableListOf<String>()
        var removed = 0
        var restored = 0

        for (a in actions) {
            when (a.action) {
                SyncAction.INSERT -> {
                    val uri = ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI, a.eventId,
                    )
                    val ok = runCatching {
                        context.contentResolver.delete(uri, null, null)
                    }.getOrDefault(0) > 0
                    dao.deleteByEventId(a.eventId)
                    if (ok) removed++ else failed += "${a.localDate} ${a.title}: poisto epäonnistui"
                }

                SyncAction.UPDATE -> {
                    val uri = ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI, a.eventId,
                    )
                    val values = ContentValues().apply {
                        a.prevTitle?.let { put(CalendarContract.Events.TITLE, it) }
                        a.prevStartMillis?.let { put(CalendarContract.Events.DTSTART, it) }
                        a.prevEndMillis?.let { put(CalendarContract.Events.DTEND, it) }
                        put(CalendarContract.Events.DESCRIPTION, a.prevDescription)
                    }
                    val ok = runCatching {
                        context.contentResolver.update(uri, values, null, null)
                    }.getOrDefault(0) > 0
                    if (ok) {
                        restored++
                        dao.upsert(
                            SyncedShift(
                                calendarId = a.calendarId,
                                localDate = a.localDate,
                                startMillis = a.prevStartMillis ?: a.startMillis,
                                endMillis = a.prevEndMillis ?: a.endMillis,
                                code = a.code,
                                title = a.prevTitle ?: a.title,
                                eventId = a.eventId,
                            )
                        )
                    } else {
                        failed += "${a.localDate} ${a.title}: palautus epäonnistui"
                    }
                }

                SyncAction.DELETE -> {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, a.calendarId)
                        put(CalendarContract.Events.TITLE, a.prevTitle ?: a.title)
                        put(CalendarContract.Events.DTSTART, a.prevStartMillis ?: a.startMillis)
                        put(CalendarContract.Events.DTEND, a.prevEndMillis ?: a.endMillis)
                        put(CalendarContract.Events.EVENT_TIMEZONE, ShiftTimes.HELSINKI.id)
                        put(CalendarContract.Events.DESCRIPTION, a.prevDescription)
                    }
                    val newId = runCatching {
                        context.contentResolver
                            .insert(CalendarContract.Events.CONTENT_URI, values)
                            ?.lastPathSegment?.toLongOrNull()
                    }.getOrNull()
                    if (newId == null) {
                        failed += "${a.localDate} ${a.title}: uudelleenluonti epäonnistui"
                    } else {
                        restored++
                        dao.upsert(
                            SyncedShift(
                                calendarId = a.calendarId,
                                localDate = a.localDate,
                                startMillis = a.startMillis,
                                endMillis = a.endMillis,
                                code = a.code,
                                title = a.title,
                                eventId = newId,
                            )
                        )
                    }
                }
            }
        }

        // Erä on käytetty — samaa ei voi kumota kahdesti. Historiarivi lähtee mukana,
        // koska kumottua jaksoa ei ole tallennettu kalenteriin.
        db.scannedPeriods().deleteByBatch(batch.id)
        batches.deleteActions(batch.id)
        batches.deleteBatch(batch.id)

        UndoSummary(removed, restored, failed)
    }

    private fun action(
        batchId: Long,
        type: String,
        eventId: Long,
        calendarId: Long,
        shift: Shift,
        startMillis: Long,
        endMillis: Long,
        snapshot: EventSnapshot?,
    ) = SyncAction(
        batchId = batchId,
        action = type,
        eventId = eventId,
        calendarId = calendarId,
        localDate = shift.date.toString(),
        startMillis = startMillis,
        endMillis = endMillis,
        code = shift.code,
        title = shift.title,
        prevTitle = snapshot?.title,
        prevStartMillis = snapshot?.startMillis,
        prevEndMillis = snapshot?.endMillis,
        prevDescription = snapshot?.description,
    )

    /** Lukee tapahtuman nykytilan. Null jos tapahtumaa ei enää ole. */
    private fun readEvent(eventId: Long): EventSnapshot? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                EventSnapshot(
                    title = c.getString(0),
                    startMillis = if (c.isNull(1)) null else c.getLong(1),
                    endMillis = if (c.isNull(2)) null else c.getLong(2),
                    description = c.getString(3),
                )
            }
        }.getOrNull()
    }

    private fun description(shift: Shift): String = buildString {
        append("Lisätty Työvuorolukijalla.")
        shift.code?.let { append("\nKoodi: $it") }
        append("\nLähde: ${shift.source.trim()}")
    }
}
