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

/** Koko historian palkkakertymä. */
data class PayTotals(
    val periods: Int,
    val totalMinutes: Long,
    val supplementsCents: Long?,
    val grossCents: Long?,
    val taxCents: Long?,
    val contributionsCents: Long?,
    val netCents: Long?,
) {
    /** Lisien osuus bruttosta prosentteina — kertoo paljonko epämukava työaika tuo. */
    val supplementShare: Double?
        get() {
            val g = grossCents ?: return null
            val s = supplementsCents ?: return null
            return if (g == 0L) null else s * 100.0 / g
        }
}

/**
 * Vuorotyyppien lukumaarat. Harvinaisemmat koodit (E, U, R, D...) niputetaan
 * yhteen: neljä lukua on luettava, kymmenen ei.
 */
data class ShiftTypeCounts(
    val morning: Int = 0,
    val evening: Int = 0,
    val night: Int = 0,
    val other: Int = 0,
) {
    val total: Int get() = morning + evening + night + other

    companion object {
        fun from(days: List<ScannedDay>): ShiftTypeCounts {
            var m = 0; var e = 0; var n = 0; var o = 0
            days.filterNot { it.isFree }.forEach { day ->
                when (day.code?.uppercase()) {
                    "A" -> m++
                    "I" -> e++
                    "Y" -> n++
                    else -> o++
                }
            }
            return ShiftTypeCounts(m, e, n, o)
        }
    }
}

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

    private val db = ShiftDatabase.get(context)
    private val dao = db.scannedPeriods()
    private val dayDao = db.scannedDays()

    suspend fun all(): List<ScannedPeriod> = withContext(Dispatchers.IO) { dao.all() }

    suspend fun days(): List<ScannedDay> = withContext(Dispatchers.IO) { dayDao.all() }

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { dao.count() == 0 }

    /**
     * Poistaa yhden jakson historiasta.
     *
     * **Ei koske kalenteriin.** Historia on tilastokirjanpitoa; kalenteritapahtumat
     * poistetaan erikseen siivousnäkymästä. Ei myöskään kosketa idempotenssin
     * mäppäykseen (`synced_shifts`), joten saman jakson uudelleenskannaus löytää
     * yhä aiemmin luodut tapahtumat eikä kahdenna niitä.
     */
    suspend fun deletePeriod(period: ScannedPeriod) = withContext(Dispatchers.IO) {
        dayDao.deleteForPeriod(period.key)
        dao.deleteById(period.id)
    }

    /** Poistaa historiasta jaksot jotka osuvat annetulle aikavälille. */
    suspend fun deleteOverlapping(from: LocalDate, to: LocalDate): Int =
        withContext(Dispatchers.IO) {
            val hits = dao.overlapping(from.toString(), to.toString())
            hits.forEach { period ->
                dayDao.deleteForPeriod(period.key)
                dao.deleteById(period.id)
            }
            hits.size
        }

    /** Tyhjentää koko historian. Kalenteri ja idempotenssi säilyvät koskemattomina. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dayDao.deleteAll()
        dao.deleteAll()
    }

    /** Koko historian kertymä. Null-summat jätetään pois, ei nollata. */
    fun totals(periods: List<ScannedPeriod>) = PayTotals(
        periods = periods.size,
        totalMinutes = periods.sumOf { it.totalMinutes },
        supplementsCents = periods.mapNotNull { it.supplementsCents }.sumOrNull(),
        grossCents = periods.mapNotNull { it.grossCents }.sumOrNull(),
        taxCents = periods.mapNotNull { it.taxCents }.sumOrNull(),
        contributionsCents = periods.mapNotNull { it.contributionsCents }.sumOrNull(),
        netCents = periods.mapNotNull { it.netCents }.sumOrNull(),
    )

    private fun List<Long>.sumOrNull(): Long? = if (isEmpty()) null else sum()

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
        val periodKey = "${range.start}..${range.endInclusive}"

        // Päiväkohtainen data kalenterinäkymää varten. Kirjoitetaan uusiksi, jotta
        // uudelleenskannaus ei jätä vanhoja päiviä roikkumaan.
        dayDao.deleteForPeriod(periodKey)
        dayDao.insertAll(
            shifts.map {
                ScannedDay(
                    periodKey = periodKey,
                    date = it.date.toString(),
                    code = it.code,
                    isFree = false,
                    minutes = java.time.temporal.ChronoUnit.MINUTES.between(it.start, it.end),
                )
            } + freeDays.map {
                ScannedDay(
                    periodKey = periodKey,
                    date = it.date.toString(),
                    code = "V",
                    isFree = true,
                    minutes = 0,
                )
            }
        )

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
                taxCents = pay?.tax?.cents(),
                contributionsCents = pay?.contributions?.cents(),
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
