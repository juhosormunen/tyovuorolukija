package fi.tyovuorolukija.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.tyovuorolukija.calendar.CalendarInfo
import fi.tyovuorolukija.calendar.CalendarRepository
import fi.tyovuorolukija.calendar.FoundEvent
import fi.tyovuorolukija.calendar.SyncSummary
import fi.tyovuorolukija.calendar.UndoSummary
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
    ) : UiState

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
                val salary = payForm.salary ?: return null
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
        _state.update { current ->
            if (current !is UiState.History) current
            else UiState.History(
                periods = periods,
                years = history.yearSummaries(periods),
                days = days,
                totals = history.totals(periods),
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
                it.copy(calendars = list, selectedCalendarId = list.firstOrNull()?.id)
            }
        }
    }

    fun updateCleanupRange(from: String, to: String) = updateCleanup {
        it.copy(from = from, to = to, searched = false, found = emptyList(), message = null)
    }

    fun selectCleanupCalendar(id: Long) = updateCleanup {
        it.copy(selectedCalendarId = id, searched = false, found = emptyList())
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
            _state.value = UiState.History(
                periods = periods,
                years = history.yearSummaries(periods),
                days = days,
                totals = history.totals(periods),
            )
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
                    selectedCalendarId = current.selectedCalendarId ?: list.firstOrNull()?.id,
                )
            }
        }
    }

    fun selectCalendar(id: Long) = updateReview { it.copy(selectedCalendarId = id) }

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
            val result = runCatching {
                calendars.syncShifts(calendarId, calendarName, review.validShifts, range)
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
                    }
                    val first = review.validShifts.minByOrNull { it.start }
                    _state.value = UiState.Done(
                        summary = summary,
                        calendarName = calendarName,
                        rangeText = "${range.start.format(RANGE_FORMAT)} – " +
                            range.endInclusive.format(RANGE_FORMAT),
                        firstShiftMillis = first?.let { ShiftTimes.startMillis(it) },
                    )
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
