package fi.tyovuorolukija.data

import android.content.Context
import fi.tyovuorolukija.parser.EmployerSummary
import fi.tyovuorolukija.parser.FreeDay
import fi.tyovuorolukija.parser.Shift
import fi.tyovuorolukija.parser.stats.ShiftRhythm
import fi.tyovuorolukija.parser.tes.ComparisonResult
import fi.tyovuorolukija.parser.tes.PayBreakdown
import fi.tyovuorolukija.parser.tes.SupplementHours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Vuosikohtainen yhteenveto historiasta. */
data class YearSummary(
    val year: Int,
    val periods: Int,
    val totalMinutes: Long,
    val eveningMinutes: Long,
    val nightMinutes: Long,
    val saturdayMinutes: Long,
    val sundayMinutes: Long,
    val grossCents: Long?,
    val netCents: Long?,
)

class HistoryRepository(context: Context) {

    private val dao = ShiftDatabase.get(context).scannedPeriods()

    suspend fun all(): List<ScannedPeriod> = withContext(Dispatchers.IO) { dao.all() }

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { dao.count() == 0 }

    /**
     * Tallentaa jakson historiaan. Sama päiväväli korvaa aiemman rivin, jotta
     * uudelleenskannaus päivittää historian eikä kahdenna sitä.
     */
    suspend fun record(
        batchId: Long,
        range: ClosedRange<LocalDate>,
        shifts: List<Shift>,
        freeDays: List<FreeDay>,
        employer: EmployerSummary,
        comparison: ComparisonResult,
        pay: PayBreakdown?,
        scannedAt: Long,
    ) = withContext(Dispatchers.IO) {
        val supplements = SupplementHours.of(shifts)
        val rhythm = ShiftRhythm.of(shifts, freeDays)

        dao.upsert(
            ScannedPeriod(
                batchId = batchId,
                scannedAt = scannedAt,
                rangeStart = range.start.toString(),
                rangeEnd = range.endInclusive.toString(),

                totalMinutes = supplements.total,
                eveningMinutes = supplements.evening,
                nightMinutes = supplements.night,
                saturdayMinutes = supplements.saturday,
                sundayMinutes = supplements.sunday,

                employerTotalMinutes = employer.totalMinutes,
                employerMatched = if (comparison.comparedRows.isEmpty()) null
                else comparison.allMatch,

                shiftCount = rhythm.shiftCount,
                nightShiftCount = rhythm.nightShiftCount,
                weekendShiftCount = rhythm.weekendShiftCount,
                longestWorkStreakDays = rhythm.longestWorkStreakDays,
                shortestRestMinutes = rhythm.shortestRestMinutes,
                shortRestCount = rhythm.shortRestCount,
                freeDayCount = rhythm.freeDayCount,

                monthlySalaryCents = pay?.monthlySalary?.cents(),
                supplementsCents = pay?.supplementsTotal?.cents(),
                grossCents = pay?.gross?.cents(),
                netCents = pay?.net?.cents(),
            )
        )
    }

    /** Jaksot ryhmiteltynä alkupäivän vuoden mukaan. */
    fun yearSummaries(periods: List<ScannedPeriod>): List<YearSummary> =
        periods.groupBy { LocalDate.parse(it.rangeStart).year }
            .map { (year, list) ->
                YearSummary(
                    year = year,
                    periods = list.size,
                    totalMinutes = list.sumOf { it.totalMinutes },
                    eveningMinutes = list.sumOf { it.eveningMinutes },
                    nightMinutes = list.sumOf { it.nightMinutes },
                    saturdayMinutes = list.sumOf { it.saturdayMinutes },
                    sundayMinutes = list.sumOf { it.sundayMinutes },
                    grossCents = list.mapNotNull { it.grossCents }.takeIf { it.isNotEmpty() }?.sum(),
                    netCents = list.mapNotNull { it.netCents }.takeIf { it.isNotEmpty() }?.sum(),
                )
            }
            .sortedByDescending { it.year }

    private fun BigDecimal.cents(): Long =
        multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
}

fun Long.centsToEuros(): String = "%.2f".format(this / 100.0)
