package fi.tyovuorolukija.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.tyovuorolukija.calendar.CalendarInfo
import fi.tyovuorolukija.calendar.CalendarRepository
import fi.tyovuorolukija.calendar.CloudSyncState
import fi.tyovuorolukija.calendar.FoundEvent
import fi.tyovuorolukija.calendar.SyncSummary
import fi.tyovuorolukija.calendar.UndoSummary
import fi.tyovuorolukija.data.AbsenceDay
import fi.tyovuorolukija.data.DayType
import fi.tyovuorolukija.data.HistoryRepository
import fi.tyovuorolukija.data.PayForm
import fi.tyovuorolukija.data.PaySettingsStore
import fi.tyovuorolukija.data.PayTotals
import fi.tyovuorolukija.data.ScannedDay
import fi.tyovuorolukija.data.ScannedPeriod
import fi.tyovuorolukija.data.YearSummary
import fi.tyovuorolukija.ocr.ShiftListRecognizer
import fi.tyovuorolukija.parser.Confidence
import fi.tyovuorolukija.parser.EmployerSummary
import fi.tyovuorolukija.parser.FreeDay
import fi.tyovuorolukija.parser.Shift
import fi.tyovuorolukija.parser.ShiftCodes
import fi.tyovuorolukija.parser.ShiftTimes
import fi.tyovuorolukija.parser.TitaniaShiftParser
import fi.tyovuorolukija.parser.tes.ComparisonResult
import fi.tyovuorolukija.parser.tes.PayBreakdown
import fi.tyovuorolukija.parser.tes.PayCalculator
import fi.tyovuorolukija.parser.tes.PayInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Muokattava rivi vahvistusnäkymässä. Ajat ovat tekstiä, jotta käyttäjä voi korjata OCR:n. */
data class ShiftRow(
    val key: Int,
    val code: String,
    val startText: String,
    val endText: String,
    val include: Boolean,
    val flagged: Boolean,
    val source: String,
) {
    val startError: Boolean get() = parseOrNull(startText) == null
    val endError: Boolean get() = parseOrNull(endText) == null

    /** Kelvollinen ja käyttäjän hyväksymä rivi. */
    fun toShift(): Shift? {
        if (!include) return null
        val start = parseOrNull(startText) ?: return null
        val end = parseOrNull(endText) ?: return null
        if (!end.isAfter(start)) return null
        return Shift(
            code = code.ifBlank { null },
            start = start,
            end = end,
            confidence = if (flagged) Confidence.REVIEW else Confidence.OK,
            source = source,
        )
    }

    companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

        fun parseOrNull(text: String): LocalDateTime? =
            runCatching { LocalDateTime.parse(text.trim(), FORMAT) }.getOrNull()

        fun from(index: Int, shift: Shift) = ShiftRow(
            key = index,
            code = shift.code.orEmpty(),
            startText = shift.start.format(FORMAT),
            endText = shift.end.format(FORMAT),
            include = true,
            flagged = shift.confidence == Confidence.REVIEW || ShiftCodes.isUnknown(shift.code),
            source = shift.source,
        )
    }
}

private val RANGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

/** Kumottavissa oleva tallennuskerta, näytetään aloitusnäkymässä. */
data class UndoableBatch(val id: Long, val calendarName: String)

sealed interface UiState {
    data class Home(
        val undoable: UndoableBatch? = null,
        val hasHistory: Boolean = false,
    ) : UiState

    /** Kameranäkymä. Avautuu vasta valikosta, ei sovelluksen käynnistyessä. */
    data object Scanning : UiState

    data class Settings(val payForm: PayForm) : UiState
    data class Tes(val payForm: PayForm) : UiState
    data object About : UiState

    data class Cleanup(
        val from: String = "",
        val to: String = "",
        val calendars: List<CalendarInfo> = emptyList(),
        val selectedCalendarId: Long? = null,
        val found: List<FoundEvent> = emptyList(),
        val searched: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null,
    ) : UiState {
        val fromDate: LocalDate? get() = parseDate(from)
        val toDate: LocalDate? get() = parseDate(to)
        val fromError: Boolean get() = fromDate == null
        val toError: Boolean get() = toDate == null || (fromDate?.isAfter(toDate) == true)

        companion object {
            val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            fun parseDate(text: String): LocalDate? =
                runCatching { LocalDate.parse(text.trim(), DATE_FORMAT) }.getOrNull()
        }
    }

    data class Working(val step: String) : UiState

    data class History(
        val periods: List<ScannedPeriod>,
        val years: List<YearSummary>,
        val days: List<ScannedDay>,
        val totals: PayTotals,
        val absences: List<AbsenceDay> = emptyList(),
        /** Päivä jota käyttäjä napautti ruudukossa — avaa merkintävalinnan. */
        val editingDay: LocalDate? = null,
        val busy: Boolean = false,
        val message: String? = null,
    ) : UiState {
        val absenceByDate: Map<String, AbsenceDay> get() = absences.associateBy { it.date }
        fun dayFor(date: LocalDate): ScannedDay? =
            days.firstOrNull { it.date == date.toString() }
    }

    /**
     * Poissaolojen merkintä aikaväliltä. Erillinen näkymä, koska lomaa ei voi merkitä
     * skannauksen yhteydessä: loma-ajalle ei suunnitella vuoroja, joten päiviä ei ole
     * missään jaksossa. Sairausloma taas selviää vasta jälkikäteen.
     */
    data class Absence(
        val from: String = "",
        val to: String = "",
        /**
         * Oletuksena loma: sairausloman voi merkitä nopeammin napauttamalla päivää
         * historiassa, mutta lomapäiviä ei ole missään jaksossa eikä siis myöskään
         * ruudukossa — ne pääsee merkitsemään vain täältä.
         */
        val type: DayType = DayType.VACATION,
        val writeToCalendar: Boolean = true,
        val calendars: List<CalendarInfo> = emptyList(),
        val selectedCalendarId: Long? = null,
        val existing: List<AbsenceDay> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
    ) : UiState {
        val fromDate: LocalDate? get() = Cleanup.parseDate(from)
        val toDate: LocalDate? get() = Cleanup.parseDate(to)
        val fromError: Boolean get() = fromDate == null
        val toError: Boolean get() = toDate == null || (fromDate?.isAfter(toDate) == true)
        val dayCount: Int
            get() {
                val a = fromDate ?: return 0
                val b = toDate ?: return 0
                if (a.isAfter(b)) return 0
                return (java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1).toInt()
            }
    }

    data class Review(
        val rows: List<ShiftRow>,
        val freeDays: List<FreeDay>,
        val warnings: List<String>,
        val ignoredLines: List<String>,
        val rawLines: List<String>,
        val employerSummary: EmployerSummary = EmployerSummary(),
        val payForm: PayForm = PayForm(),
        /** Tulosteesta luettu työaikaprosentti, vertailua varten. */
        val printoutPartTime: Double? = null,
        val calendars: List<CalendarInfo> = emptyList(),
        val selectedCalendarId: Long? = null,
        val saving: Boolean = false,
        val error: String? = null,
    ) : UiState {
        val validShifts: List<Shift> get() = rows.mapNotNull { it.toShift() }

        /**
         * Vertailu lasketaan käyttäjän muokkaamista riveistä, ei alkuperäisestä
         * tunnistuksesta — silloin vuoroajan korjaus näkyy heti tarkistuksessa.
         */
        val comparison: ComparisonResult
            get() = PayCalculator.compare(validShifts, employerSummary)

        val payBreakdown: PayBreakdown?
            get() {
                val salary = payForm.effectiveMonthlySalary ?: return null
                return PayCalculator.calculate(
                    validShifts,
                    employerSummary,
                    PayInput(
                        monthlySalary = salary,
                        partTimePercent = payForm.partTime,
                        taxPercent = payForm.tax,
                        rates = payForm.rates,
                        contributions = payForm.contributions,
                    ),
                )
            }
        /**
         * Koko tunnistuksen tila tekstinä, leikepöydälle kopioitavaksi.
         *
         * Etätuen työkalu: kun sovellus on jonkun toisen puhelimessa, vian selvitys
         * kuvakaappauksista on arvailua — tästä näkee mitä OCR luki ja mitä parseri
         * siitä teki. Ei sisällä palkkatietoja.
         */
        fun debugReport(): String = buildString {
            appendLine("== Työvuorolukija: tunnistustiedot ==")
            appendLine("Vuoroja: ${rows.size}, vapaapäiviä: ${freeDays.size}")
            dateRange?.let { appendLine("Jakso: ${it.start} – ${it.endInclusive}") }
            printoutPartTime?.let { appendLine("Työaikaprosentti tulosteesta: $it") }
            appendLine()
            appendLine("-- Työnantajan erittely --")
            appendLine("tunnit yhteensä: ${employerSummary.totalMinutes}")
            appendLine("sunnuntaityö: ${employerSummary.sunday}")
            appendLine("iltatyö: ${employerSummary.evening}")
            appendLine("yötyö: ${employerSummary.night}")
            appendLine("lauantaityö: ${employerSummary.saturday}")
            appendLine()
            appendLine("-- Kalenterit --")
            calendars.forEach { c ->
                appendLine(
                    "${if (c.id == selectedCalendarId) "* " else "  "}" +
                        "id=${c.id}	${c.displayName}	tili=${c.accountName}" +
                        "	tyyppi=${c.accountType}	sync=${c.syncEvents}	local=${c.isLocal}"
                )
            }
            appendLine()
            appendLine("-- Tunnistetut vuorot --")
            rows.forEach { appendLine("${it.code}\t${it.startText}\t${it.endText}\t${it.source}") }
            appendLine()
            appendLine("-- Vapaapäivät --")
            freeDays.forEach { appendLine("${it.date}\t${it.source}") }
            appendLine()
            appendLine("-- Varoitukset --")
            warnings.forEach { appendLine(it) }
            appendLine()
            appendLine("-- Ohitetut rivit --")
            ignoredLines.forEach { appendLine(it) }
            appendLine()
            appendLine("-- OCR-teksti sellaisenaan --")
            rawLines.forEach { appendLine(it) }
        }

        val hasErrors: Boolean get() = rows.any { it.include && it.toShift() == null }
        val flaggedCount: Int get() = rows.count { it.include && it.flagged }

        val dateRange: ClosedRange<LocalDate>?
            get() {
                val dates = rows.mapNotNull { ShiftRow.parseOrNull(it.startText)?.toLocalDate() } +
                    freeDays.map { it.date }
                val min = dates.minOrNull() ?: return null
                return min..dates.max()
            }
    }

    data class Done(
        val summary: SyncSummary,
        val calendarName: String,
        /** Jakson päivävälit tekstinä, esim. "24.8.2026 – 13.9.2026". */
        val rangeText: String? = null,
        /** Ensimmäisen vuoron alku — kalenterin avaamiseen oikeaan kohtaan. */
        val firstShiftMillis: Long? = null,
        /** True jos kohdekalenteri on laitteen sisäinen eikä näy Google Kalenterissa. */
        val calendarIsLocal: Boolean = false,
        /** Kohdekalenteri ja jakso uudelleentarkistusta varten. */
        val calendarId: Long? = null,
        val range: ClosedRange<LocalDate>? = null,
        /** Pilvisynkronoinnin tila. Null = ei vielä tarkistettu tai ei saatu selville. */
        val cloudSync: CloudSyncState? = null,
        val checkingSync: Boolean = false,
        val undoing: Boolean = false,
        val undone: UndoSummary? = null,
    ) : UiState

    data class Failed(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val recognizer = ShiftListRecognizer()
    private val calendars = CalendarRepository(app)
    private val paySettings = PaySettingsStore(app)
    private val history = HistoryRepository(app)

    private val _state = MutableStateFlow<UiState>(UiState.Home())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        reset()
    }

    fun reset() {
        _state.value = UiState.Home()
        viewModelScope.launch {
            val batch = runCatching { calendars.lastBatch() }.getOrNull()
            val hasHistory = runCatching { !history.isEmpty() }.getOrDefault(false)
            _state.update { current ->
                if (current !is UiState.Home) current
                else current.copy(
                    undoable = batch?.let { UndoableBatch(it.id, it.calendarName) },
                    hasHistory = hasHistory,
                )
            }
        }
    }

    fun openScan() {
        _state.value = UiState.Scanning
    }

    fun openSettings() {
        _state.value = UiState.Settings(paySettings.load())
    }

    fun openTes() {
        _state.value = UiState.Tes(paySettings.load())
    }

    fun openAbout() {
        _state.value = UiState.About
    }

    /**
     * Tarkistaa onko tallennetut vuorot viety pilveen.
     *
     * Kalenteriin kirjoittaminen onnistuu paikallisesti aina; pilveen vieminen on
     * Googlen synkronoinnin vastuulla ja voi olla jumissa kuukausia ilman että
     * mikään kertoo siitä. Tämä on se kohta jossa se paljastuu.
     */
    fun checkCloudSync(initialDelayMillis: Long = 0) {
        viewModelScope.launch {
            val done = _state.value as? UiState.Done ?: return@launch
            val calendarId = done.calendarId ?: return@launch
            val range = done.range ?: return@launch
            if (done.calendarIsLocal) return@launch // paikallinen ei synkronoidu koskaan

            updateDone { it.copy(checkingSync = true) }
            if (initialDelayMillis > 0) kotlinx.coroutines.delay(initialDelayMillis)

            val state = runCatching {
                calendars.cloudSyncState(calendarId, range.start, range.endInclusive)
            }.getOrNull()

            updateDone { it.copy(checkingSync = false, cloudSync = state) }
        }
    }

    private fun updateDone(transform: (UiState.Done) -> UiState.Done) {
        _state.update { if (it is UiState.Done) transform(it) else it }
    }

    /**
     * Poistaa jakson historiasta. Kalenteritapahtumiin ei kosketa — ne poistetaan
     * erikseen siivousnäkymästä, ja käyttäjälle kerrotaan tämä ennen poistoa.
     */
    fun deleteHistoryPeriod(period: ScannedPeriod, alsoCalendar: Boolean) {
        viewModelScope.launch {
            runCatching { history.deletePeriod(period) }
            if (alsoCalendar) {
                runCatching {
                    calendars.deleteAppEventsInRange(
                        LocalDate.parse(period.rangeStart),
                        LocalDate.parse(period.rangeEnd),
                    )
                }
            }
            reloadHistory()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { history.deleteAll() }
            reloadHistory()
        }
    }

    private suspend fun reloadHistory() {
        val periods = runCatching { history.all() }.getOrDefault(emptyList())
        val days = runCatching { history.days() }.getOrDefault(emptyList())
        val absences = runCatching { history.absences() }.getOrDefault(emptyList())
        _state.update { current ->
            if (current !is UiState.History) current
            else current.copy(
                periods = periods,
                years = history.yearSummaries(periods),
                days = days,
                totals = history.totals(periods),
                absences = absences,
                editingDay = null,
            )
        }
    }

    fun openCleanup() {
        // Oletusväliksi kuluva kuukausi ja seuraava — kattaa tyypillisen jakson.
        val today = LocalDate.now()
        _state.value = UiState.Cleanup(
            from = today.withDayOfMonth(1).format(UiState.Cleanup.DATE_FORMAT),
            to = today.plusMonths(2).withDayOfMonth(1).minusDays(1)
                .format(UiState.Cleanup.DATE_FORMAT),
        )
        viewModelScope.launch {
            val list = runCatching { calendars.writableCalendars() }.getOrDefault(emptyList())
            updateCleanup {
                it.copy(
                    calendars = list,
                    // Sama muistettu valinta kuin tallennusnäkymässä: käyttäjä on
                    // valinnut kalenterin kerran, eikä sitä pidä kysyä uudelleen
                    // toisessa näkymässä.
                    selectedCalendarId = paySettings.lastCalendarId()?.takeIf { saved ->
                        list.any { c -> c.id == saved }
                    }
                        ?: list.firstOrNull { c -> c.isPreferred }?.id
                        ?: list.firstOrNull()?.id,
                )
            }
        }
    }

    fun updateCleanupRange(from: String, to: String) = updateCleanup {
        it.copy(from = from, to = to, searched = false, found = emptyList(), message = null)
    }

    fun selectCleanupCalendar(id: Long) {
        // Valinta on sama koko sovelluksessa, tehtiin se kummassa näkymässä tahansa.
        paySettings.saveLastCalendarId(id)
        updateCleanup { it.copy(selectedCalendarId = id, searched = false, found = emptyList()) }
    }

    fun searchCleanup() {
        val s = _state.value as? UiState.Cleanup ?: return
        val from = s.fromDate ?: return
        val to = s.toDate ?: return
        val calendarId = s.selectedCalendarId ?: return

        viewModelScope.launch {
            updateCleanup { it.copy(busy = true, message = null) }
            val result = runCatching { calendars.findAppEvents(calendarId, from, to) }
            result.fold(
                onSuccess = { events ->
                    updateCleanup {
                        it.copy(busy = false, searched = true, found = events)
                    }
                },
                onFailure = { t ->
                    updateCleanup {
                        it.copy(busy = false, message = "Haku epäonnistui: ${t.message}")
                    }
                },
            )
        }
    }

    fun deleteCleanup(alsoHistory: Boolean) {
        val s = _state.value as? UiState.Cleanup ?: return
        val ids = s.found.map { it.id }
        if (ids.isEmpty()) return
        val from = s.fromDate
        val to = s.toDate

        viewModelScope.launch {
            updateCleanup { it.copy(busy = true, message = null) }
            val result = runCatching { calendars.deleteEvents(ids) }
            val removedPeriods = if (alsoHistory && from != null && to != null) {
                runCatching { history.deleteOverlapping(from, to) }.getOrDefault(0)
            } else 0
            result.fold(
                onSuccess = { count ->
                    updateCleanup {
                        it.copy(
                            busy = false,
                            found = emptyList(),
                            message = buildString {
                                append("Poistettu $count tapahtumaa.")
                                if (alsoHistory) {
                                    append(" Historiasta poistettiin $removedPeriods jaksoa.")
                                }
                            },
                        )
                    }
                },
                onFailure = { t ->
                    updateCleanup {
                        it.copy(busy = false, message = "Poisto epäonnistui: ${t.message}")
                    }
                },
            )
        }
    }

    private fun updateCleanup(transform: (UiState.Cleanup) -> UiState.Cleanup) {
        _state.update { if (it is UiState.Cleanup) transform(it) else it }
    }

    fun openHistory() {
        viewModelScope.launch {
            val periods = runCatching { history.all() }.getOrDefault(emptyList())
            val days = runCatching { history.days() }.getOrDefault(emptyList())
            val absences = runCatching { history.absences() }.getOrDefault(emptyList())
            _state.value = UiState.History(
                periods = periods,
                years = history.yearSummaries(periods),
                days = days,
                totals = history.totals(periods),
                absences = absences,
            )
        }
    }

    // ---- Poissaolot ---------------------------------------------------------------

    /** Napautus kalenteriruudukossa avaa merkintävalinnan. */
    fun editDay(date: LocalDate?) {
        _state.update { if (it is UiState.History) it.copy(editingDay = date) else it }
    }

    /**
     * Merkitsee tai purkaa yhden päivän poissaolon historianäkymästä.
     *
     * [type] `WORK` purkaa merkinnän. Kalenteri päivitetään samalla, ja jakson luvut
     * lasketaan uudestaan — muuten merkintä näkyisi tilastossa muttei palkassa.
     */
    fun setDayType(date: LocalDate, type: DayType) {
        viewModelScope.launch {
            _state.update {
                if (it is UiState.History) it.copy(busy = true, editingDay = null) else it
            }
            val result = runCatching { applyAbsence(listOf(date), type) }
            reloadHistory()
            _state.update { current ->
                if (current !is UiState.History) current
                else current.copy(
                    busy = false,
                    message = result.getOrElse { "Merkintä epäonnistui: ${it.message}" },
                )
            }
        }
    }

    fun clearHistoryMessage() {
        _state.update { if (it is UiState.History) it.copy(message = null) else it }
    }

    fun openAbsence() {
        val today = LocalDate.now()
        _state.value = UiState.Absence(
            from = today.format(UiState.Cleanup.DATE_FORMAT),
            to = today.format(UiState.Cleanup.DATE_FORMAT),
        )
        viewModelScope.launch {
            val list = runCatching { calendars.writableCalendars() }.getOrDefault(emptyList())
            val remembered = paySettings.lastCalendarId()
            updateAbsence { current ->
                current.copy(
                    calendars = list,
                    selectedCalendarId = remembered?.takeIf { id -> list.any { it.id == id } }
                        ?: list.firstOrNull()?.id,
                )
            }
            refreshAbsenceList()
        }
    }

    fun updateAbsenceForm(transform: (UiState.Absence) -> UiState.Absence) {
        updateAbsence(transform)
        viewModelScope.launch { refreshAbsenceList() }
    }

    private fun updateAbsence(transform: (UiState.Absence) -> UiState.Absence) {
        _state.update { if (it is UiState.Absence) transform(it) else it }
    }

    private suspend fun refreshAbsenceList() {
        val current = _state.value as? UiState.Absence ?: return
        val from = current.fromDate ?: return
        val to = current.toDate ?: return
        if (from.isAfter(to)) return
        val hits = runCatching { history.absencesInRange(from, to) }.getOrDefault(emptyList())
        updateAbsence { it.copy(existing = hits) }
    }

    /** Merkitsee valitun aikavälin poissaoloksi tai purkaa merkinnät siltä. */
    fun applyAbsenceRange(clear: Boolean) {
        val current = _state.value as? UiState.Absence ?: return
        val from = current.fromDate ?: return
        val to = current.toDate ?: return
        if (from.isAfter(to)) return

        viewModelScope.launch {
            updateAbsence { it.copy(busy = true, message = null) }
            val dates = generateSequence(from) { d ->
                d.plusDays(1).takeIf { !it.isAfter(to) }
            }.toList()
            val message = runCatching {
                applyAbsence(
                    dates = dates,
                    type = if (clear) DayType.WORK else current.type,
                    calendarId = current.selectedCalendarId.takeIf { current.writeToCalendar },
                )
            }.getOrElse { "Merkintä epäonnistui: ${it.message}" }
            refreshAbsenceList()
            updateAbsence { it.copy(busy = false, message = message) }
        }
    }

    /**
     * Poissaolomerkinnän varsinainen toteutus: kanta, kalenteri ja jaksojen
     * uudelleenlaskenta samassa paikassa, jotta ne eivät voi joutua eri tahtiin.
     *
     * Kalenteriin kirjoitetaan vain jos [calendarId] on annettu. Historiamerkintä
     * tehdään aina — poissaolo vaikuttaa palkkaan riippumatta siitä näkyykö se
     * kalenterissa.
     */
    private suspend fun applyAbsence(
        dates: List<LocalDate>,
        type: DayType,
        calendarId: Long? = paySettings.lastCalendarId(),
    ): String {
        var marked = 0
        var cleared = 0
        var calendarWrites = 0
        val storedDays = runCatching { history.days() }.getOrDefault(emptyList())

        for (date in dates) {
            val existing = history.absenceOn(date)
            if (type.countsAsWork) {
                if (existing == null) continue
                val day = storedDays.firstOrNull { it.date == date.toString() }
                val restored = calendars.unmarkAbsence(
                    calendarId = existing.calendarId,
                    date = date,
                    eventId = existing.eventId,
                    created = existing.eventCreated,
                    prevTitle = existing.prevTitle,
                    fallbackTitle = day?.let { ShiftCodes.title(it.code) },
                )
                if (restored) calendarWrites++
                history.clearAbsence(date)
                cleared++
            } else {
                if (existing?.dayType == type) continue
                // Vanha merkintä puretaan kalenterista ensin, jotta tyypin vaihto
                // (sairausloma → loma) ei jätä väärää otsikkoa roikkumaan.
                existing?.let {
                    calendars.unmarkAbsence(
                        calendarId = it.calendarId, date = date, eventId = it.eventId,
                        created = it.eventCreated, prevTitle = it.prevTitle,
                        fallbackTitle = null,
                    )
                }
                val mark = calendarId?.let { calendars.markAbsence(it, date, type) }
                if (mark?.eventId != null) calendarWrites++
                history.saveAbsence(
                    AbsenceDay(
                        date = date.toString(),
                        type = type.name,
                        markedAt = System.currentTimeMillis(),
                        calendarId = calendarId,
                        eventId = mark?.eventId,
                        eventCreated = mark?.created ?: false,
                        prevTitle = mark?.prevTitle ?: existing?.prevTitle,
                    )
                )
                marked++
            }
        }

        val recompute = runCatching { history.recompute(dates, paySettings.load()) }.getOrNull()

        return buildString {
            when {
                marked > 0 -> append("Merkitty $marked päivää.")
                cleared > 0 -> append("Poistettu merkintä $cleared päivältä.")
                else -> append("Ei muutettavaa.")
            }
            if (calendarWrites > 0) append(" Kalenteriin päivitetty $calendarWrites tapahtumaa.")
            recompute?.let {
                if (it.periods > 0) append(" Laskettu ${it.periods} jakson luvut uudelleen.")
                if (it.incompleteDays > 0) {
                    append(
                        " Huom: ${it.incompleteDays} päivältä puuttuvat kellonajat, joten " +
                            "niiden lisätunnit eivät päivittyneet — skannaa jakso uudelleen."
                    )
                }
            }
        }
    }

    /**
     * Kumoaa viimeisimmän tallennuksen. Käytettävissä sekä heti tallennuksen jälkeen
     * että myöhemmin aloitusnäkymästä — virheen huomaa usein vasta kalenterista.
     */
    fun undo() {
        viewModelScope.launch {
            val before = _state.value
            if (before is UiState.Done) {
                _state.value = before.copy(undoing = true)
            } else {
                _state.value = UiState.Working("Kumotaan…")
            }

            val result = runCatching { calendars.undoLastBatch() }
            result.fold(
                onSuccess = { undo ->
                    _state.value = when (before) {
                        is UiState.Done -> before.copy(undoing = false, undone = undo)
                        else -> UiState.Done(
                            summary = SyncSummary(0, 0, 0, 0, emptyList()),
                            calendarName = "",
                            undone = undo,
                        )
                    }
                },
                onFailure = { t ->
                    _state.value = UiState.Failed("Kumoaminen epäonnistui: ${t.message}")
                },
            )
        }
    }

    fun scan(uri: Uri) {
        viewModelScope.launch {
            _state.value = UiState.Working("Tunnistetaan tekstiä…")
            try {
                val page = withContext(Dispatchers.Default) {
                    recognizer.recognize(getApplication(), uri)
                }
                if (page.lines.isEmpty()) {
                    _state.value = UiState.Failed(
                        "Kuvasta ei löytynyt tekstiä. Kokeile parempaa valaistusta ja " +
                            "suoraan ylhäältä otettua kuvaa."
                    )
                    return@launch
                }

                _state.value = UiState.Working("Tulkitaan vuoroja…")
                val result = withContext(Dispatchers.Default) {
                    TitaniaShiftParser(referenceDate = LocalDate.now()).parse(page.lines)
                }

                if (result.shifts.isEmpty() && result.freeDays.isEmpty()) {
                    _state.value = UiState.Failed(
                        "Tekstiä löytyi, mutta yhtään vuoroa ei tunnistettu. " +
                            "Näkyykö kuvassa koko taulukko otsikkoriviä myöten?"
                    )
                    return@launch
                }

                // Työaikaprosentti luetaan tulosteesta, mutta käyttäjän oma
                // asetus voittaa jos sellainen on tallennettu.
                val stored = paySettings.load()
                val form = if (stored.partTimePercent.isBlank() && result.partTimePercent != null) {
                    stored.copy(partTimePercent = formatPercent(result.partTimePercent!!))
                } else {
                    stored
                }

                _state.value = UiState.Review(
                    rows = result.shifts.mapIndexed(ShiftRow::from),
                    freeDays = result.freeDays,
                    warnings = result.warnings,
                    ignoredLines = result.ignoredLines,
                    rawLines = page.rawLines,
                    employerSummary = result.employerSummary,
                    payForm = form,
                    printoutPartTime = result.partTimePercent,
                )
                loadCalendars()
            } catch (t: Throwable) {
                _state.value = UiState.Failed("Tunnistus epäonnistui: ${t.message}")
            }
        }
    }

    fun loadCalendars() {
        viewModelScope.launch {
            val list = runCatching { calendars.writableCalendars() }.getOrDefault(emptyList())
            _state.update { current ->
                if (current !is UiState.Review) current
                else current.copy(
                    calendars = list,
                    // Järjestys: nykyinen valinta, sitten muistettu valinta, sitten
                    // ensimmäinen oikean tilin synkronoituva kalenteri. Laitteen
                    // sisäinen kalenteri kelpaa vasta viimeisenä oljenkortena —
                    // Google Kalenteri ei näytä sen tapahtumia lainkaan.
                    selectedCalendarId = current.selectedCalendarId
                        ?: paySettings.lastCalendarId()?.takeIf { saved ->
                            list.any { it.id == saved }
                        }
                        ?: list.firstOrNull { it.isPreferred }?.id
                        ?: list.firstOrNull()?.id,
                )
            }
        }
    }

    fun selectCalendar(id: Long) {
        paySettings.saveLastCalendarId(id)
        updateReview { it.copy(selectedCalendarId = id) }
    }

    fun updatePayForm(form: PayForm) {
        paySettings.save(form)
        _state.update { current ->
            when (current) {
                is UiState.Review -> current.copy(payForm = form)
                is UiState.Settings -> current.copy(payForm = form)
                else -> current
            }
        }
    }

    private fun formatPercent(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    fun updateRow(key: Int, transform: (ShiftRow) -> ShiftRow) = updateReview { review ->
        review.copy(rows = review.rows.map { if (it.key == key) transform(it) else it })
    }

    fun save() {
        val review = _state.value as? UiState.Review ?: return
        val calendarId = review.selectedCalendarId ?: run {
            updateReview { it.copy(error = "Valitse kalenteri ensin.") }
            return
        }
        val range = review.dateRange ?: run {
            updateReview { it.copy(error = "Jakson päivämääriä ei saatu selville.") }
            return
        }

        val calendarName = review.calendars.firstOrNull { it.id == calendarId }
            ?.displayName.orEmpty()

        viewModelScope.launch {
            updateReview { it.copy(saving = true, error = null) }
            // Aiemmin merkityt poissaolot pysyvät voimassa myös uudelleenskannauksessa:
            // ilman tätä kalenteritapahtuman otsikko palautuisi vuorotyypiksi ja
            // sairausloma katoaisi kalenterista huomaamatta.
            val absent = runCatching { history.absences() }.getOrDefault(emptyList())
                .associateBy { it.date }
            val shiftsToWrite = review.validShifts.map { shift ->
                val type = absent[shift.date.toString()]?.dayType ?: DayType.WORK
                if (type.countsAsWork) shift else shift.copy(titleOverride = type.annotate(shift.title))
            }

            val result = runCatching {
                calendars.syncShifts(calendarId, calendarName, shiftsToWrite, range)
            }
            result.fold(
                onSuccess = { summary ->
                    // Historia kirjataan vasta onnistuneen kalenterikirjoituksen jälkeen,
                    // jotta se kuvaa vahvistettuja jaksoja eikä skannausyrityksiä.
                    runCatching {
                        history.record(
                            batchId = summary.batchId,
                            range = range,
                            shifts = review.validShifts,
                            freeDays = review.freeDays,
                            employer = review.employerSummary,
                            comparison = review.comparison,
                            pay = review.payBreakdown,
                            scannedAt = System.currentTimeMillis(),
                        )
                        // record() laskee luvut kaikista vuoroista. Jos jaksossa on
                        // poissaoloja, ne pitää vielä vähentää — muuten merkintä
                        // katoaisi tilastoista uudelleenskannauksessa.
                        val absentDates = absent.values
                            .map { it.localDate }
                            .filter { it >= range.start && it <= range.endInclusive }
                        history.recompute(absentDates, review.payForm)
                    }
                    val first = review.validShifts.minByOrNull { it.start }
                    _state.value = UiState.Done(
                        summary = summary,
                        calendarName = calendarName,
                        rangeText = "${range.start.format(RANGE_FORMAT)} – " +
                            range.endInclusive.format(RANGE_FORMAT),
                        firstShiftMillis = first?.let { ShiftTimes.startMillis(it) },
                        calendarIsLocal = review.calendars
                            .firstOrNull { it.id == calendarId }?.isLocal == true,
                        calendarId = calendarId,
                        range = range,
                    )
                    // Kirjoitus onnistui, mutta se ei vielä tarkoita että vuorot ovat
                    // pilvessä. Synkronointi vie tyypillisesti muutaman sekunnin —
                    // ja jos se on jumissa, tämä on ainoa paikka jossa se näkyy.
                    checkCloudSync(initialDelayMillis = 5_000)
                },
                onFailure = { t ->
                    updateReview {
                        it.copy(saving = false, error = "Tallennus epäonnistui: ${t.message}")
                    }
                },
            )
        }
    }

    private fun updateReview(transform: (UiState.Review) -> UiState.Review) {
        _state.update { if (it is UiState.Review) transform(it) else it }
    }
}
