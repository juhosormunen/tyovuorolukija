package fi.tyovuorolukija.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class TitaniaShiftParserTest {

    private fun parseExample() =
        TitaniaShiftParser(firstYear = 2026).parse(Fixtures.EXAMPLE_PRINTOUT)

    private fun dt(s: String) = LocalDateTime.parse(s)

    @Test
    fun `esimerkkituloste tuottaa taydellisen vuorolistan`() {
        val shifts = parseExample().shifts

        val expected = listOf(
            Triple("y", "2026-08-24T21:00", "2026-08-25T07:12"),
            Triple("y", "2026-08-25T21:00", "2026-08-26T07:10"),
            Triple("i", "2026-08-28T14:00", "2026-08-28T21:25"),
            Triple("i", "2026-08-29T14:00", "2026-08-29T21:25"),
            Triple("a", "2026-08-30T07:00", "2026-08-30T14:50"),
            Triple("U", "2026-09-04T07:00", "2026-09-04T13:30"),
            Triple("E", "2026-09-05T07:00", "2026-09-05T21:25"),
            Triple("I", "2026-09-06T14:00", "2026-09-06T21:25"),
            Triple("Y", "2026-09-07T21:00", "2026-09-08T07:13"),
            Triple("Y", "2026-09-08T21:00", "2026-09-09T07:13"),
        )

        assertEquals(expected.size, shifts.size, "vuorojen lukumäärä")
        expected.forEachIndexed { i, (code, start, end) ->
            assertEquals(code, shifts[i].code, "vuoro $i koodi")
            assertEquals(dt(start), shifts[i].start, "vuoro $i alku")
            assertEquals(dt(end), shifts[i].end, "vuoro $i loppu")
            assertEquals(Confidence.OK, shifts[i].confidence, "vuoro $i luottamus")
        }
    }

    @Test
    fun `esimerkkitulosteen vapaapaivat tunnistetaan`() {
        val free = parseExample().freeDays.map { it.date }

        val expected = listOf(
            "2026-08-27", "2026-08-31",
            "2026-09-01", "2026-09-02", "2026-09-03",
            "2026-09-10", "2026-09-11", "2026-09-12", "2026-09-13",
        ).map(LocalDate::parse)

        assertEquals(expected, free)
    }

    @Test
    fun `esimerkkituloste ei tuota varoituksia`() {
        val result = parseExample()
        assertEquals(emptyList<String>(), result.warnings)
        assertFalse(result.needsReview)
    }

    @Test
    fun `yhteenveto-osion kellonaikoja ei tulkita vuoroiksi`() {
        // "91:48", "114:45", "22:05" jne. eivät saa päätyä vuoroiksi.
        val result = parseExample()
        assertTrue(result.shifts.none { it.date.monthValue !in 8..9 })
        assertEquals(LocalDate.parse("2026-08-24")..LocalDate.parse("2026-09-13"), result.dateRange)
    }

    @Test
    fun `kasinkirjoitettu sarake ei sotke jos se paasee lapi`() {
        // Käsinkirjoitus on muotoa "8-16" — ei nelinumeroinen, ei saa matchata.
        val lines = """
                        suunnitelma  toteutunut  selite
            28.08 pe    i 1400-2125  i 1400-2125            8-16
            tunnit yhteensä
        """.trimIndent().lines()

        val result = TitaniaShiftParser(firstYear = 2026).parse(lines)
        assertEquals(1, result.shifts.size)
        assertEquals(dt("2026-08-28T21:25"), result.shifts[0].end)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun `yovuoro yhdistyy vaikka OCR palauttaisi rivit vaarassa jarjestyksessa`() {
        // Havaittu oikealla valokuvalla: ML Kit palautti päivän jatkorivin ja uuden
        // yön alkurivin väärin päin, jolloin "0000-0712" jäi erilliseksi vuoroksi.
        val sekaisin = """
                        suunnitelma  toteutunut  selite
            24.08 ma    y 2100-2400  y 2100-2400
            25.08 ti    y 2100-2400  y 2100-2400
                          0000-0712    0000-0712
            26.08 ke      0000-0710    0000-0710
            tunnit yhteensä
        """.trimIndent().lines()

        val result = TitaniaShiftParser(firstYear = 2026).parse(sekaisin)

        assertEquals(2, result.shifts.size, "kaksi yövuoroa, ei neljää palasta")
        assertEquals(dt("2026-08-24T21:00"), result.shifts[0].start)
        assertEquals(dt("2026-08-25T07:12"), result.shifts[0].end)
        assertEquals(dt("2026-08-25T21:00"), result.shifts[1].start)
        assertEquals(dt("2026-08-26T07:10"), result.shifts[1].end)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun `vuosi vaihtuu kun kuukausi pienenee`() {
        val result = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.YEAR_ROLLOVER)

        assertEquals(2, result.shifts.size)
        assertEquals(dt("2026-12-30T07:00"), result.shifts[0].start)
        assertEquals(dt("2026-12-31T21:00"), result.shifts[1].start)
        assertEquals(dt("2027-01-01T07:12"), result.shifts[1].end)
        assertEquals(listOf(LocalDate.parse("2027-01-02")), result.freeDays.map { it.date })
    }

    @Test
    fun `vuosi paatellaan viitepaivasta kun sita ei anneta`() {
        // Tuloste alkaa 24.08. Viitepäivä 2026-08-15 -> 2026, ei 2025 eikä 2027.
        val result = TitaniaShiftParser(referenceDate = LocalDate.parse("2026-08-15"))
            .parse(Fixtures.EXAMPLE_PRINTOUT)
        assertEquals(dt("2026-08-24T21:00"), result.shifts.first().start)

        // Tammikuun tuloste jouluna -> seuraava vuosi.
        assertEquals(2027, TitaniaShiftParser.inferYear(5, 1, LocalDate.parse("2026-12-20")))
        // Joulukuun tuloste tammikuussa -> edellinen vuosi.
        assertEquals(2025, TitaniaShiftParser.inferYear(28, 12, LocalDate.parse("2026-01-10")))
    }

    @Test
    fun `toteutunut voittaa suunnitelman ja ero merkitaan tarkistettavaksi`() {
        val lines = """
                        suunnitelma  toteutunut  selite
            28.08 pe    i 1400-2125  i 1500-2125
            tunnit yhteensä
        """.trimIndent().lines()

        val result = TitaniaShiftParser(firstYear = 2026).parse(lines)
        assertEquals(1, result.shifts.size)
        assertEquals(dt("2026-08-28T15:00"), result.shifts[0].start)
        assertEquals(Confidence.REVIEW, result.shifts[0].confidence)
        assertTrue(result.warnings.any { it.contains("poikkeavat") }, result.warnings.toString())
    }

    @Test
    fun `yovuoro jonka jatkoa ei nay merkitaan tarkistettavaksi`() {
        val lines = """
                        suunnitelma  toteutunut  selite
            13.09 SU    Y 2100-2400  Y 2100-2400
            tunnit yhteensä
        """.trimIndent().lines()

        val result = TitaniaShiftParser(firstYear = 2026).parse(lines)
        assertEquals(1, result.shifts.size)
        assertEquals(dt("2026-09-14T00:00"), result.shifts[0].end)
        assertEquals(Confidence.REVIEW, result.shifts[0].confidence)
        assertTrue(result.warnings.any { it.contains("jatkoa ei löytynyt") }, result.warnings.toString())
    }

    @Test
    fun `keskiyolla alkava jakso ilman edeltavaa yovuoroa varoittaa`() {
        val lines = """
                        suunnitelma  toteutunut  selite
            25.08 ti      0000-0712    0000-0712
            tunnit yhteensä
        """.trimIndent().lines()

        val result = TitaniaShiftParser(firstYear = 2026).parse(lines)
        assertEquals(1, result.shifts.size)
        assertEquals(Confidence.REVIEW, result.shifts[0].confidence)
        assertTrue(result.warnings.any { it.contains("ilman edeltävää") }, result.warnings.toString())
    }

    @Test
    fun `syksyn kesaajan paatos pidentaa yovuoron tunnilla`() {
        val result = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.DST_AUTUMN)
        val shift = result.shifts.single()

        assertEquals(dt("2026-10-24T21:00"), shift.start)
        assertEquals(dt("2026-10-25T07:12"), shift.end)
        // Kellonajoista laskien 10 h 12 min, todellisuudessa tunnin enemmän.
        assertEquals(Duration.ofMinutes(612), Duration.ofMinutes(shift.localMinutes))
        assertEquals(Duration.ofMinutes(672), ShiftTimes.actualDuration(shift))
    }

    @Test
    fun `kevaan kesaajan alku lyhentaa yovuoron tunnilla`() {
        val result = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.DST_SPRING)
        val shift = result.shifts.single()

        assertEquals(dt("2026-03-28T21:00"), shift.start)
        assertEquals(dt("2026-03-29T07:13"), shift.end)
        assertEquals(Duration.ofMinutes(613), Duration.ofMinutes(shift.localMinutes))
        assertEquals(Duration.ofMinutes(553), ShiftTimes.actualDuration(shift))
    }

    @Test
    fun `tyhja syote ei kaada parseria`() {
        val result = TitaniaShiftParser(firstYear = 2026).parse(emptyList())
        assertTrue(result.shifts.isEmpty())
        assertTrue(result.freeDays.isEmpty())
        assertTrue(result.warnings.any { it.contains("otsikkoriviä") })
        assertEquals(null, result.dateRange)
    }

    @Test
    fun `vuorokoodien otsikot`() {
        assertEquals("Yövuoro", ShiftCodes.title("y"))
        assertEquals("Aamuvuoro", ShiftCodes.title("A"))
        assertEquals("Vuoro U", ShiftCodes.title("U"))
        assertEquals("Työvuoro", ShiftCodes.title(null))
        assertTrue(ShiftCodes.isUnconfirmed("E"))
        assertFalse(ShiftCodes.isUnconfirmed("I"))
    }
}
