package fi.tyovuorolukija.parser.tes

import fi.tyovuorolukija.parser.EmployerSummary
import fi.tyovuorolukija.parser.Shift
import java.math.BigDecimal
import java.math.RoundingMode

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
    val lines: List<PayLine>,
    val supplementsTotal: BigDecimal,
    val monthlySalary: BigDecimal,
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
     * Tuntipalkka. 23 § 1 mom: varsinainen palkka jaettuna jakajalla (jaksotyössä 163).
     * 23 § 3 mom: osa-aikaisella jakaja kerrotaan työaikaosuudella, jolloin
     * osa-aikapalkasta saadaan sama tuntipalkka kuin kokoaikaisella.
     */
    fun hourlyRate(input: PayInput): BigDecimal {
        val share = (input.partTimePercent / 100.0).coerceAtLeast(0.01)
        val divisor = BigDecimal(input.rates.monthlyDivisor * share)
        return input.monthlySalary.divide(divisor, 4, RoundingMode.HALF_UP)
    }

    /**
     * Laskee lisät ja palkan. Käyttää ensisijaisesti työnantajan ilmoittamia tunteja,
     * koska ne ovat se mikä oikeasti maksetaan; jos jokin rivi puuttuu tulosteesta,
     * käytetään omaa laskelmaa.
     */
    fun calculate(
        shifts: List<Shift>,
        employer: EmployerSummary,
        input: PayInput,
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
        val gross = (input.monthlySalary + supplements).setScale(2, RoundingMode.HALF_UP)

        val contributions = gross
            .multiply(BigDecimal(input.contributions.totalPercent / 100.0))
            .setScale(2, RoundingMode.HALF_UP)

        val tax = input.taxPercent?.let {
            gross.multiply(BigDecimal(it / 100.0)).setScale(2, RoundingMode.HALF_UP)
        }
        val net = tax?.let { (gross - it - contributions).setScale(2, RoundingMode.HALF_UP) }

        return PayBreakdown(
            hourlyRate = hourly.setScale(2, RoundingMode.HALF_UP),
            lines = lines,
            supplementsTotal = supplements.setScale(2, RoundingMode.HALF_UP),
            monthlySalary = input.monthlySalary.setScale(2, RoundingMode.HALF_UP),
            gross = gross,
            contributions = contributions,
            tax = tax,
            net = net,
        )
    }
}
