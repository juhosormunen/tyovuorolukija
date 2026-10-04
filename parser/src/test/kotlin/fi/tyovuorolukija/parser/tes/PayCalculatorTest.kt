package fi.tyovuorolukija.parser.tes

import fi.tyovuorolukija.parser.Fixtures
import fi.tyovuorolukija.parser.TitaniaShiftParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class PayCalculatorTest {

    private val parsed = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.EXAMPLE_PRINTOUT)

    @Test
    fun `tyonantajan erittely luetaan tulosteesta`() {
        val s = parsed.employerSummary

        assertEquals(91 * 60L + 48, s.totalMinutes, "tunnit yhteensä")
        assertEquals(91 * 60L + 48, s.periodMinutes, "jakson tunnit")
        assertEquals(91 * 60L + 48, s.additionalWorkLimit, "lisätyöraja")
        assertEquals(114 * 60L + 45, s.overtimeLimit, "ylityöraja")
        assertEquals(22 * 60L + 5, s.sunday, "sunnuntaityö")
        assertEquals(17 * 60L + 40, s.evening, "iltatyö")
        assertEquals(36 * 60L, s.night, "yötyö")
        assertEquals(15 * 60L, s.saturday, "lauantaityö")
    }

    /**
     * Tämä on koko vertailuominaisuuden ydin: TES:n säännöistä itsenäisesti laskettujen
     * tuntien pitää tuottaa täsmälleen samat luvut kuin työnantajan oma erittely.
     */
    @Test
    fun `oma laskelma tasmaa tyonantajan erittelyyn minuutilleen`() {
        val cmp = PayCalculator.compare(parsed.shifts, parsed.employerSummary)

        cmp.rows.forEach { row ->
            assertNotNull(row.employerMinutes, "${row.label}: tulosteesta puuttui luku")
            assertEquals(
                row.employerMinutes, row.calculatedMinutes,
                "${row.label} (${row.reference}): tuloste ${row.employerMinutes?.toHoursMinutes()}, " +
                    "laskettu ${row.calculatedMinutes.toHoursMinutes()}",
            )
        }
        assertTrue(cmp.allMatch)
        assertEquals(91 * 60L + 48, cmp.totalCalculatedMinutes, "kokonaistunnit")
        assertEquals(true, cmp.totalMatches)
    }

    /** Sama vaatimus toiselle jaksolle, jossa on koulutuspäivä ja R-vuoroja. */
    @Test
    fun `lokakuun jakso tasmaa tyonantajan erittelyyn minuutilleen`() {
        val october = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.OCTOBER_PRINTOUT)
        val cmp = PayCalculator.compare(october.shifts, october.employerSummary)
        cmp.rows.forEach { row ->
            assertEquals(row.employerMinutes, row.calculatedMinutes, row.label)
        }
        assertTrue(cmp.allMatch)
        assertEquals(true, cmp.totalMatches)
    }

    @Test
    fun `tyoaikaprosentti luetaan otsikkotiedoista`() {
        assertEquals(80.0, parsed.partTimePercent)
    }

    @Test
    fun `jaksossa ei ole lisa- eika ylityota`() {
        val s = parsed.employerSummary
        assertTrue(!s.hasAdditionalWork, "tunnit == lisätyöraja, ei lisätyötä")
        assertTrue(!s.hasOvertime)
    }

    @Test
    fun `tuntipalkka osa-aikaisella`() {
        // 23 § 3 mom: osa-aikapalkka / (163 x 0,80).
        val input = PayInput(
            monthlySalary = BigDecimal("2608.00"),
            partTimePercent = 80.0,
        )
        // 2608 / 130,4 = 20,00
        assertEquals(BigDecimal("20.0000"), PayCalculator.hourlyRate(input))
    }

    @Test
    fun `tuntipalkka on sama kokoaikaisella ja osa-aikaisella`() {
        // 23 § 3 mom: jakaja skaalataan työaikaosuudella, joten osa-aikapalkasta
        // tulee sama tuntipalkka. Kokoaikainen 3260 € ja 80-prosenttinen 2608 €
        // ovat sama tehtäväkohtainen palkka.
        val kokoaikainen = PayInput(BigDecimal("3260.00"), partTimePercent = 100.0)
        val osaAikainen = PayInput(BigDecimal("2608.00"), partTimePercent = 80.0)

        assertEquals(BigDecimal("20.0000"), PayCalculator.hourlyRate(kokoaikainen))
        assertEquals(BigDecimal("20.0000"), PayCalculator.hourlyRate(osaAikainen))

        // Vertaillaan arvoa, ei esitysmuotoa: BigDecimalin equals huomioi desimaalit.
        assertEquals(163.0, PayCalculator.effectiveDivisor(kokoaikainen).toDouble(), 0.001)
        assertEquals(130.4, PayCalculator.effectiveDivisor(osaAikainen).toDouble(), 0.001)
    }

    @Test
    fun `puuttuva tyoaikaprosentti aliarvioi tuntipalkan`() {
        // Tämä on se virhe jonka takia työaikaprosentti pitää saada oikein:
        // osa-aikapalkka jaettuna kokoaikaisen jakajalla antaa liian pienen
        // tuntipalkan, ja sitä kautta liian pienet lisät.
        val vaarin = PayInput(BigDecimal("2608.00"), partTimePercent = 100.0)
        val oikein = PayInput(BigDecimal("2608.00"), partTimePercent = 80.0)

        assertEquals(BigDecimal("16.0000"), PayCalculator.hourlyRate(vaarin))
        assertEquals(BigDecimal("20.0000"), PayCalculator.hourlyRate(oikein))
    }

    @Test
    fun `epakelpo tyoaikaprosentti rajataan`() {
        // "8" kirjoitusvirheenä "80":sta viisinkertaistaisi tuntipalkan, jos
        // arvoa ei rajattaisi. Rajaus ei korjaa lukua oikeaksi — se estää
        // absurdin lopputuloksen, ja käyttöliittymä varoittaa erikseen.
        assertEquals(1.0, PayCalculator.normalizePartTime(0.0))
        assertEquals(1.0, PayCalculator.normalizePartTime(-50.0))
        assertEquals(100.0, PayCalculator.normalizePartTime(150.0))
        assertEquals(80.0, PayCalculator.normalizePartTime(80.0))

        assertTrue(PayCalculator.isPartTimeOutOfRange(0.0))
        assertTrue(PayCalculator.isPartTimeOutOfRange(120.0))
        assertTrue(!PayCalculator.isPartTimeOutOfRange(80.0))
    }

    @Test
    fun `tyoaikaprosentti ei vaikuta lisatunteihin vaan vain tuntipalkkaan`() {
        // Lisätunnit tulevat tehdyistä tunneista, eivät sopimuksen työajasta.
        // Vain euromäärä muuttuu prosentin mukana.
        fun lisat(percent: Double) = PayCalculator.calculate(
            parsed.shifts, parsed.employerSummary,
            PayInput(BigDecimal("2608.00"), partTimePercent = percent),
        )

        val a = lisat(80.0)
        val b = lisat(100.0)

        assertEquals(a.lines.map { it.minutes }, b.lines.map { it.minutes }, "tunnit samat")
        assertTrue(a.supplementsTotal > b.supplementsTotal, "euromäärä seuraa tuntipalkkaa")
    }

    @Test
    fun `lisat lasketaan tunneista ja prosenteista`() {
        val input = PayInput(
            monthlySalary = BigDecimal("2608.00"),
            partTimePercent = 80.0,
            taxPercent = 20.0,
        )
        val pay = PayCalculator.calculate(
            parsed.shifts, parsed.employerSummary, input, parsed.dateRange,
        )

        assertEquals(BigDecimal("20.00"), pay.hourlyRate)

        fun amount(label: String) = pay.lines.first { it.label == label }.amount

        // sunnuntaityö 22:05 = 22,0833 h x 20 € x 100 % = 441,67
        assertEquals(BigDecimal("441.67"), amount("Sunnuntaityö"))
        // iltatyö 17:40 = 17,6667 h x 20 € x 15 % = 53,00
        assertEquals(BigDecimal("53.00"), amount("Iltatyö"))
        // yötyö 36:00 = 36 h x 20 € x 40 % = 288,00
        assertEquals(BigDecimal("288.00"), amount("Yötyö"))
        // lauantaityö 15:00 = 15 h x 20 € x 20 % = 60,00
        assertEquals(BigDecimal("60.00"), amount("Lauantaityö"))

        assertEquals(BigDecimal("842.67"), pay.supplementsTotal)
        // Peruspalkka vain jakson päiviltä: 24.–31.8. = 8 × 2608/31,
        // 1.–13.9. = 13 × 2608/30 → 673,03 + 1130,13 = 1803,17
        assertEquals(21, pay.periodDays)
        assertEquals(BigDecimal("1803.17"), pay.basePay)
        assertEquals(BigDecimal("2645.84"), pay.gross)

        // vero 20 % bruttosta
        assertEquals(BigDecimal("529.17"), pay.tax)
        // TyEL 7,15 % + tvm 0,59 % = 7,74 %
        assertEquals(BigDecimal("204.79"), pay.contributions)
        assertEquals(BigDecimal("1911.88"), pay.net)
    }

    @Test
    fun `peruspalkka jakson kalenteripaivilta`() {
        val salary = BigDecimal("3100.00")
        // Koko kalenterikuukausi = koko kuukausipalkka.
        assertEquals(
            BigDecimal("3100.00"),
            PayCalculator.periodBasePay(salary, LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31)),
        )
        // Kolme viikkoa lokakuussa: 21/31.
        assertEquals(
            BigDecimal("2100.00"),
            PayCalculator.periodBasePay(salary, LocalDate.of(2026, 10, 5)..LocalDate.of(2026, 10, 25)),
        )
        // Helmikuun päivä on arvokkaampi kuin maaliskuun: 2/28 + 1/31.
        assertEquals(
            BigDecimal("311.06"),
            PayCalculator.periodBasePay(BigDecimal("3000.00"),
                LocalDate.of(2027, 2, 27)..LocalDate.of(2027, 3, 1)),
        )
    }

    @Test
    fun `yotyoprosentti on eri jaksotyossa ja yleistyoajassa`() {
        assertEquals(40.0, TesRates.SOTE_JAKSOTYO.nightPercent)
        assertEquals(30.0, TesRates.SOTE_YLEISTYOAIKA.nightPercent)
        assertEquals(163, TesRates.SOTE_JAKSOTYO.monthlyDivisor)
        assertEquals(152, TesRates.SOTE_TOIMISTOTYOAIKA.monthlyDivisor)
    }

    @Test
    fun `ilman veroprosenttia nettoa ei lasketa`() {
        val pay = PayCalculator.calculate(
            parsed.shifts, parsed.employerSummary,
            PayInput(monthlySalary = BigDecimal("2608.00"), partTimePercent = 80.0),
        )
        assertEquals(null, pay.tax)
        assertEquals(null, pay.net)
    }

    @Test
    fun `poikkeama tyonantajan erittelysta havaitaan`() {
        val vaarennetty = parsed.employerSummary.copy(night = 30 * 60L) // pitäisi olla 36:00
        val cmp = PayCalculator.compare(parsed.shifts, vaarennetty)

        assertTrue(!cmp.allMatch)
        val poikkeama = cmp.mismatches.single()
        assertEquals("Yötyö", poikkeama.label)
        assertEquals(6 * 60L, poikkeama.differenceMinutes, "laskettu 36:00 - ilmoitettu 30:00")
    }

    @Test
    fun `puuttuva rivi jatetaan vertailun ulkopuolelle`() {
        val vajaa = parsed.employerSummary.copy(saturday = null)
        val cmp = PayCalculator.compare(parsed.shifts, vajaa)

        assertEquals(3, cmp.comparedRows.size)
        assertTrue(cmp.allMatch)
    }
}

class FinnishHolidaysTest {

    @Test
    fun `paasiaissunnuntai`() {
        assertEquals(LocalDate.parse("2026-04-05"), FinnishHolidays.easterSunday(2026))
        assertEquals(LocalDate.parse("2027-03-28"), FinnishHolidays.easterSunday(2027))
        assertEquals(LocalDate.parse("2024-03-31"), FinnishHolidays.easterSunday(2024))
    }

    @Test
    fun `juhannus- ja pyhainpaiva ovat aina lauantaita`() {
        for (year in 2024..2030) {
            assertEquals(
                java.time.DayOfWeek.SATURDAY, FinnishHolidays.midsummerDay(year).dayOfWeek,
                "juhannuspäivä $year",
            )
            assertEquals(
                java.time.DayOfWeek.SATURDAY, FinnishHolidays.allSaintsDay(year).dayOfWeek,
                "pyhäinpäivä $year",
            )
        }
        assertEquals(LocalDate.parse("2026-06-20"), FinnishHolidays.midsummerDay(2026))
        assertEquals(LocalDate.parse("2026-10-31"), FinnishHolidays.allSaintsDay(2026))
    }

    @Test
    fun `juhannus- ja pyhainpaiva eivat ole arkilauantaita`() {
        // Näiltä maksetaan sunnuntaikorvaus koko vuorokaudelta, ei lauantaikorvaus.
        assertTrue(!FinnishHolidays.isOrdinarySaturday(FinnishHolidays.midsummerDay(2026)))
        assertTrue(!FinnishHolidays.isOrdinarySaturday(FinnishHolidays.allSaintsDay(2026)))
        assertTrue(FinnishHolidays.isSundayRateDay(FinnishHolidays.midsummerDay(2026)))

        // Tavallinen elokuun lauantai on arkilauantai.
        assertTrue(FinnishHolidays.isOrdinarySaturday(LocalDate.parse("2026-08-29")))
    }

    @Test
    fun `paasiaislauantai ei ole arkilauantai vaan aattopaiva`() {
        val paasiaislauantai = FinnishHolidays.easterSunday(2026).minusDays(1)
        assertTrue(!FinnishHolidays.isOrdinarySaturday(paasiaislauantai))
        assertTrue(FinnishHolidays.isEveDay(paasiaislauantai))
    }

    @Test
    fun `sunnuntaikorvauspaivat`() {
        assertTrue(FinnishHolidays.isSundayRateDay(LocalDate.parse("2026-12-06"))) // itsenäisyys
        assertTrue(FinnishHolidays.isSundayRateDay(LocalDate.parse("2026-05-01"))) // vappu
        assertTrue(FinnishHolidays.isSundayRateDay(LocalDate.parse("2026-01-06"))) // loppiainen
        assertTrue(!FinnishHolidays.isSundayRateDay(LocalDate.parse("2026-08-28"))) // perjantai
    }
}

class SupplementHoursTest {

    private fun shift(start: String, end: String) = fi.tyovuorolukija.parser.Shift(
        code = null,
        start = java.time.LocalDateTime.parse(start),
        end = java.time.LocalDateTime.parse(end),
        confidence = fi.tyovuorolukija.parser.Confidence.OK,
        source = "",
    )

    @Test
    fun `lauantai-ilta on seka iltatyota etta sunnuntaityota`() {
        // La 29.8.2026 klo 18-22: 18 § 1 mom (su-korvaus la 18-24) JA 19 § 1 mom (iltatyö).
        val m = SupplementHours.of(shift("2026-08-29T18:00", "2026-08-29T22:00"))
        assertEquals(240, m.evening)
        assertEquals(240, m.sunday)
        assertEquals(0, m.saturday, "klo 18 jälkeen ei enää lauantaikorvausta")
    }

    @Test
    fun `lauantaipaiva jakautuu lauantai- ja sunnuntaikorvaukseen kello 18`() {
        val m = SupplementHours.of(shift("2026-08-29T06:00", "2026-08-30T00:00"))
        assertEquals(12 * 60L, m.saturday, "06-18")
        assertEquals(6 * 60L, m.sunday, "18-24")
    }

    @Test
    fun `yovuoro laskee vain kello 22-07 valisen ajan yotyoksi`() {
        val m = SupplementHours.of(shift("2026-08-24T21:00", "2026-08-25T07:12"))
        assertEquals(9 * 60L, m.night)
        assertEquals(60, m.evening, "21-22")
        assertEquals(612, m.total)
    }

    @Test
    fun `jouluaatto tuottaa aattokorvauksen kello 18 asti`() {
        // 24.12.2026 on torstai.
        val m = SupplementHours.of(shift("2026-12-24T08:00", "2026-12-24T20:00"))
        assertEquals(10 * 60L, m.eve, "08-18")
        assertEquals(2 * 60L, m.evening, "18-20")
        assertEquals(2 * 60L, m.sunday, "joulupäivän aatto klo 18-24")
    }
}
