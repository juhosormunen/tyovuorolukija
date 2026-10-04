package fi.tyovuorolukija.parser.tes

import fi.tyovuorolukija.parser.EmployerSummary
import fi.tyovuorolukija.parser.Shift
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Palkkalaskennan syötteet. Kaikki tulee käyttäjältä — sovellus ei tiedä palkkaa.
 *
 * @param monthlySalary varsinainen palkka kalenterikuukaudelta. Osa-aikaisella
 *   tämä on osa-aikapalkka, ei kokoaikaisen palkka.
 * @param partTimePercent työaikaprosentti (esim. 80.0). Kokoaikaisella 100.
 *   Näkyy tulosteen ylälaidassa kohdassa "työaikaprosentti".
 * @param taxPercent ennakonpidätysprosentti verokortista. Null = nettoa ei lasketa.
 */
data class PayInput(
    val monthlySalary: BigDecimal,
    val partTimePercent: Double = 100.0,
    val taxPercent: Double? = null,
    val rates: TesRates = TesRates.SOTE_JAKSOTYO,
    val contributions: EmployeeContributions = EmployeeContributions(),
)

/** Yksi lisärivi palkkaerittelyssä. */
data class PayLine(
    val label: String,
    val minutes: Long,
    val percent: Double,
    val amount: BigDecimal,
    val reference: String,
)

data class PayBreakdown(
    val hourlyRate: BigDecimal,
    /** Jakaja jolla tuntipalkka laskettiin, esim. 130,4 kun 163 × 80 %. */
    val effectiveDivisor: BigDecimal,
    val partTimePercent: Double,
    val lines: List<PayLine>,
    val supplementsTotal: BigDecimal,
    /** Syötetty kuukausipalkka. Vain tuntipalkan laskentaan ja näytettäväksi. */
    val monthlySalary: BigDecimal,
    /** Peruspalkan osuus jakson kalenteripäiviltä, ks. [PayCalculator.periodBasePay]. */
    val basePay: BigDecimal,
    /** Jakson kalenteripäivät, joilta [basePay] laskettiin. */
    val periodDays: Int,
    val gross: BigDecimal,
    val contributions: BigDecimal,
    val tax: BigDecimal?,
    val net: BigDecimal?,
)

/**
 * Yhden lisälajin vertailu: mitä työnantaja ilmoitti vs. mitä TES:n säännöistä seuraa.
 */
data class SupplementComparison(
    val label: String,
    val employerMinutes: Long?,
    val calculatedMinutes: Long,
    val reference: String,
) {
    val differenceMinutes: Long? get() = employerMinutes?.let { calculatedMinutes - it }
    val matches: Boolean get() = employerMinutes != null && employerMinutes == calculatedMinutes
    val comparable: Boolean get() = employerMinutes != null
}

data class ComparisonResult(
    val rows: List<SupplementComparison>,
    val totalEmployerMinutes: Long?,
    val totalCalculatedMinutes: Long,
) {
    val comparedRows: List<SupplementComparison> get() = rows.filter { it.comparable }
    val mismatches: List<SupplementComparison> get() = comparedRows.filterNot { it.matches }
    val allMatch: Boolean get() = comparedRows.isNotEmpty() && mismatches.isEmpty()
    val totalMatches: Boolean?
        get() = totalEmployerMinutes?.let { it == totalCalculatedMinutes }
}

/**
 * Laskee työaikakorvaukset ja palkan SOTE-sopimuksen mukaan.
 *
 * Tarkoitus on kahtalainen:
 * 1. kertoa mitä jaksosta pitäisi maksaa, ja
 * 2. **tarkistaa työnantajan oma erittely** laskemalla samat tunnit itsenäisesti
 *    vuoroista. Jos luvut eroavat, jompikumpi on väärässä ja se kannattaa selvittää.
 */
object PayCalculator {

    fun compare(shifts: List<Shift>, employer: EmployerSummary): ComparisonResult {
        val calc = SupplementHours.of(shifts)
        return ComparisonResult(
            rows = listOf(
                SupplementComparison("Sunnuntaityö", employer.sunday, calc.sunday, "18 § 1 mom"),
                SupplementComparison("Iltatyö", employer.evening, calc.evening, "19 § 1 mom"),
                SupplementComparison("Yötyö", employer.night, calc.night, "19 § 2 mom"),
                SupplementComparison("Lauantaityö", employer.saturday, calc.saturday, "18 § 2 mom"),
            ),
            totalEmployerMinutes = employer.totalMinutes,
            totalCalculatedMinutes = calc.total,
        )
    }

    /**
     * Työaikaprosentin sallittu väli. Alle 1 % tai yli 100 % ei ole mielekäs
     * työaikaprosentti, ja koska luku on jakajassa, pieni kirjoitusvirhe kertautuu:
     * "8" oikean "80":n sijaan viisinkertaistaisi tuntipalkan.
     */
    const val MIN_PART_TIME = 1.0
    const val MAX_PART_TIME = 100.0

    fun normalizePartTime(percent: Double): Double =
        percent.coerceIn(MIN_PART_TIME, MAX_PART_TIME)

    /** True jos annettu työaikaprosentti jouduttiin rajaamaan — arvo on epäilyttävä. */
    fun isPartTimeOutOfRange(percent: Double): Boolean =
        percent < MIN_PART_TIME || percent > MAX_PART_TIME

    /**
     * Tuntipalkka. 23 § 1 mom: varsinainen palkka jaettuna jakajalla (jaksotyössä 163).
     * 23 § 3 mom: osa-aikaisella jakaja kerrotaan työaikaosuudella, jolloin
     * osa-aikapalkasta saadaan **sama** tuntipalkka kuin kokoaikaisella.
     *
     * Jakaja 163 on siis kokoaikaisen luku eikä muutu; työaikaprosentti skaalaa sen.
     * Tästä seuraa myös se, että väärä työaikaprosentti vääristää tuntipalkkaa
     * suoraan samassa suhteessa — 80 %:n palkka jaettuna kokoaikaisen jakajalla
     * antaa 20 % liian pienen tuntipalkan ja siten liian pienet lisät.
     */
    fun hourlyRate(input: PayInput): BigDecimal =
        input.monthlySalary.divide(effectiveDivisor(input), 4, RoundingMode.HALF_UP)

    /** Todellisuudessa käytetty jakaja, esim. 163 × 80 % = 130,4. */
    fun effectiveDivisor(input: PayInput): BigDecimal {
        val share = normalizePartTime(input.partTimePercent) / 100.0
        return BigDecimal(input.rates.monthlyDivisor * share)
    }

    /**
     * Peruspalkka jakson ajalta: jokaiselta kalenteripäivältä kuukausipalkka jaettuna
     * **sen kuukauden** kalenteripäivillä. Sama periaate kuin vajaan kuukauden
     * palkanmaksussa (kalenteripäiväpalkka).
     *
     * Jakso on kolme viikkoa eikä koskaan täysi kuukausi, joten koko kuukausipalkan
     * lisääminen jakson lisiin antoi bruttoksi luvun, joka ei vastannut mitään.
     * Kuukauden rajan ylittävässä jaksossa (24.08.–13.09.) elokuun päivät jaetaan
     * 31:llä ja syyskuun 30:llä.
     *
     * Pyöristys vasta lopussa, jotta kuukausittaiset osat eivät kerrytä virhettä.
     */
    fun periodBasePay(monthlySalary: BigDecimal, period: ClosedRange<LocalDate>): BigDecimal {
        var total = BigDecimal.ZERO
        var day = period.start
        while (!day.isAfter(period.endInclusive)) {
            val month = YearMonth.from(day)
            val monthEnd = minOf(month.atEndOfMonth(), period.endInclusive)
            val days = java.time.temporal.ChronoUnit.DAYS.between(day, monthEnd) + 1
            total += monthlySalary.multiply(BigDecimal(days))
                .divide(BigDecimal(month.lengthOfMonth()), 10, RoundingMode.HALF_UP)
            day = monthEnd.plusDays(1)
        }
        return total.setScale(2, RoundingMode.HALF_UP)
    }

    /** Jakson pituus kalenteripäivinä. */
    fun periodDays(period: ClosedRange<LocalDate>): Int =
        (java.time.temporal.ChronoUnit.DAYS.between(period.start, period.endInclusive) + 1).toInt()

    /**
     * Laskee lisät ja palkan. Käyttää ensisijaisesti työnantajan ilmoittamia tunteja,
     * koska ne ovat se mikä oikeasti maksetaan; jos jokin rivi puuttuu tulosteesta,
     * käytetään omaa laskelmaa.
     *
     * @param period jakson päiväväli (vapaapäivät mukaan lukien). Peruspalkka lasketaan
     *   vain sen ajalta. Null = päätellään vuoroista, mikä voi jättää reunojen
     *   vapaapäivät pois — anna se aina kun se on tiedossa.
     */
    fun calculate(
        shifts: List<Shift>,
        employer: EmployerSummary,
        input: PayInput,
        period: ClosedRange<LocalDate>? = null,
    ): PayBreakdown {
        val calc = SupplementHours.of(shifts)
        val hourly = hourlyRate(input)
        val r = input.rates

        fun line(label: String, employerMinutes: Long?, own: Long, percent: Double, ref: String):
            PayLine {
            val minutes = employerMinutes ?: own
            val amount = hourly
                .multiply(BigDecimal(minutes)).divide(BigDecimal(60), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(percent / 100.0))
                .setScale(2, RoundingMode.HALF_UP)
            return PayLine(label, minutes, percent, amount, ref)
        }

        val lines = buildList {
            add(line("Sunnuntaityö", employer.sunday, calc.sunday, r.sundayPercent, "18 § 1 mom"))
            add(line("Iltatyö", employer.evening, calc.evening, r.eveningPercent, "19 § 1 mom"))
            add(line("Yötyö", employer.night, calc.night, r.nightPercent, "19 § 2 mom"))
            add(line("Lauantaityö", employer.saturday, calc.saturday, r.saturdayPercent,
                "18 § 2 mom"))
            if (calc.eve > 0) {
                add(line("Aattokorvaus", null, calc.eve, r.evePercent, "18 § 3 mom"))
            }
        }.filter { it.minutes > 0 }

        val supplements = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.amount }
        val range = period ?: shifts.takeIf { it.isNotEmpty() }
            ?.let { s -> s.minOf { it.date }..s.maxOf { it.date } }
        val base = range?.let { periodBasePay(input.monthlySalary, it) }
            ?: BigDecimal.ZERO.setScale(2)
        val gross = (base + supplements).setScale(2, RoundingMode.HALF_UP)

        val contributions = gross
            .multiply(BigDecimal(input.contributions.totalPercent / 100.0))
            .setScale(2, RoundingMode.HALF_UP)

        val tax = input.taxPercent?.let {
            gross.multiply(BigDecimal(it / 100.0)).setScale(2, RoundingMode.HALF_UP)
        }
        val net = tax?.let { (gross - it - contributions).setScale(2, RoundingMode.HALF_UP) }

        return PayBreakdown(
            hourlyRate = hourly.setScale(2, RoundingMode.HALF_UP),
            effectiveDivisor = effectiveDivisor(input).setScale(2, RoundingMode.HALF_UP),
            partTimePercent = normalizePartTime(input.partTimePercent),
            lines = lines,
            supplementsTotal = supplements.setScale(2, RoundingMode.HALF_UP),
            monthlySalary = input.monthlySalary.setScale(2, RoundingMode.HALF_UP),
            basePay = base,
            periodDays = range?.let { periodDays(it) } ?: 0,
            gross = gross,
            contributions = contributions,
            tax = tax,
            net = net,
        )
    }
}
