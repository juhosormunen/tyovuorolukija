package fi.tyovuorolukija.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.tyovuorolukija.calendar.CalendarInfo
import fi.tyovuorolukija.calendar.CalendarRepository
import fi.tyovuorolukija.calendar.SyncSummary
import fi.tyovuorolukija.calendar.UndoSummary
import fi.tyovuorolukija.data.HistoryRepository
import fi.tyovuorolukija.data.PayForm
import fi.tyovuorolukija.data.PaySettingsStore
import fi.tyovuorolukija.data.ScannedPeriod
import fi.tyovuorolukija.data.YearSummary
import fi.tyovuorolukija.ocr.ShiftListRecognizer
import fi.tyovuorolukija.parser.Confidence
import fi.tyovuorolukija.parser.EmployerSummary
import fi.tyovuorolukija.parser.FreeDay
import fi.tyovuorolukija.parser.Shift
import fi.tyovuorolukija.parser.ShiftCodes
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
            flagged = shift.confidence == Confidence.REVIEW || ShiftCodes.isUnconfirmed(shift.code),
            source = shift.source,
        )
    }
}

/** Kumottavissa oleva tallennuskerta, näytetään aloitusnäkymässä. */
data class UndoableBatch(val id: Long, val calendarName: String)

sealed interface UiState {
    data class Idle(
        val undoable: UndoableBatch? = null,
        val hasHistory: Boolean = false,
    ) : UiState

    data class Working(val step: String) : UiState

    data class History(
        val periods: List<ScannedPeriod>,
        val years: List<YearSummary>,
    ) : UiState

    data class Review(
        val rows: List<ShiftRow>,
        val freeDays: List<FreeDay>,
        val warnings: List<String>,
        val ignoredLines: List<String>,
        val rawLines: List<String>,
        val employerSummary: EmployerSummary = EmployerSummary(),
        val payForm: PayForm = PayForm(),
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

    private val _state = MutableStateFlow<UiState>(UiState.Idle())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        reset()
    }

    fun reset() {
        _state.value = UiState.Idle()
        viewModelScope.launch {
            val batch = runCatching { calendars.lastBatch() }.getOrNull()
            val hasHistory = runCatching { !history.isEmpty() }.getOrDefault(false)
            _state.update { current ->
                if (current !is UiState.Idle) current
                else current.copy(
                    undoable = batch?.let { UndoableBatch(it.id, it.calendarName) },
                    hasHistory = hasHistory,
                )
            }
        }
    }

    fun openHistory() {
        viewModelScope.launch {
            val periods = runCatching { history.all() }.getOrDefault(emptyList())
            _state.value = UiState.History(periods, history.yearSummaries(periods))
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
        updateReview { it.copy(payForm = form) }
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
                    _state.value = UiState.Done(summary, calendarName)
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
