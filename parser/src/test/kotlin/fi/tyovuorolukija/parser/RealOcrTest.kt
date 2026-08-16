package fi.tyovuorolukija.parser

import fi.tyovuorolukija.parser.tes.PayCalculator
import fi.tyovuorolukija.parser.tes.toHoursMinutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Oikea ML Kit -tuloste toisen käyttäjän puhelimesta, OCR-virheineen.
 *
 * Ennen korjausta tästä syötteestä tunnistui 9 vuoroa ja 78:06 tuntia — kaksi
 * vuoroa katosi hiljaa, yhteensä 13 h 42 min. Käyttäjä huomasi asian vain siksi,
 * että vertailu työnantajan erittelyyn näytti punaista.
 *
 * Näiden testien pitää tuottaa sama lopputulos kuin siististä litteraatiosta.
 */
class RealOcrTest {

    private val result = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.REAL_OCR)
    private val clean = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.EXAMPLE_PRINTOUT)

    private fun dt(s: String) = LocalDateTime.parse(s)

    @Test
    fun `oikea OCR tuottaa saman tuloksen kuin siisti litteraatio`() {
        assertEquals(
            clean.shifts.map { it.start to it.end },
            result.shifts.map { it.start to it.end },
            "vuorojen ajat",
        )
        assertEquals(clean.freeDays.map { it.date }, result.freeDays.map { it.date })
    }

    @Test
    fun `nolla e-kirjaimena ei enaa havita yovuoron jatkoa`() {
        // "25.08 ti e000-e712" oli aiemmin lukukelvoton, jolloin 24.08 yövuoro
        // katkesi keskiyöhön ja 7 h 12 min katosi.
        val night = result.shifts.first()
        assertEquals(dt("2026-08-24T21:00"), night.start)
        assertEquals(dt("2026-08-25T07:12"), night.end)
    }

    @Test
    fun `nolla e-kirjaimena ei enaa havita U-vuoroa`() {
        // "04.09 pe U 700-1330 U e700-1330": molemmat sarakkeet olivat rikki,
        // joten kahden sarakkeen redundanssi ei pelastanut. Menetys 6 h 30 min.
        val u = result.shifts.single { it.date.toString() == "2026-09-04" }
        assertEquals(dt("2026-09-04T07:00"), u.start)
        assertEquals(dt("2026-09-04T13:30"), u.end)
        assertEquals("U", u.code)
    }

    @Test
    fun `kokonaistunnit tasmaavat tyonantajan erittelyyn`() {
        val cmp = PayCalculator.compare(result.shifts, result.employerSummary)

        assertEquals(
            91 * 60L + 48, cmp.totalCalculatedMinutes,
            "laskettu ${cmp.totalCalculatedMinutes.toHoursMinutes()}, odotettu 91:48",
        )
        cmp.rows.forEach { row ->
            assertEquals(
                row.employerMinutes, row.calculatedMinutes,
                "${row.label}: tuloste ${row.employerMinutes?.toHoursMinutes()}, " +
                    "laskettu ${row.calculatedMinutes.toHoursMinutes()}",
            )
        }
        assertTrue(cmp.allMatch)
    }

    @Test
    fun `tyoaikaprosentti luetaan roskaisestakin otsikosta`() {
        assertEquals(80.0, result.partTimePercent)
    }

    @Test
    fun `kasinkirjoitettu sarake ei tuota vaaria vuoroja eika turhia varoituksia`() {
        // "8-l6", "5- (6", "8- le", "8-15" ovat käsialaa. Ne eivät saa päätyä
        // vuoroiksi eivätkä laukaista "kellonaikaa ei saatu luettua" -varoitusta.
        assertEquals(10, result.shifts.size)
        assertTrue(
            result.warnings.none { it.contains("ei saatu luettua") },
            "turhia varoituksia: ${result.warnings}",
        )
    }

    @Test
    fun `korjaus koskee vain kellonaikoja eika vuorokoodeja`() {
        // E ja I ovat oikeita vuorokoodeja — niitä ei saa muuttaa nolliksi.
        assertEquals("E", result.shifts.single { it.date.toString() == "2026-09-05" }.code)
        assertEquals("I", result.shifts.single { it.date.toString() == "2026-09-06" }.code)

        assertEquals("E 0700-2125", repairOcrDigits("E 0700-2125"))
        assertEquals("I 1400-2125", repairOcrDigits("I 1400-2125"))
        assertEquals("U 0700-1330", repairOcrDigits("U e700-1330"))
        assertEquals("0000-0712", repairOcrDigits("e000-e712"))
        // Käsiala jää rauhaan: alle kolme merkkiä tai alle kaksi numeroa.
        assertEquals("8-l6", repairOcrDigits("8-l6"))
    }

    @Test
    fun `rikkinainen paivays korjataan viikonpaivan perusteella`() {
        // "94.09 pe" = 04.09. Ilman korjausta koko rivi hylattiin ja U-vuoro katosi.
        val r = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.REAL_OCR_BROKEN_DATE)

        val u = r.shifts.single { it.code == "U" }
        assertEquals(dt("2026-09-04T07:00"), u.start)
        assertEquals(dt("2026-09-04T13:30"), u.end)

        assertEquals(10, r.shifts.size)
        assertEquals(9, r.freeDays.size)
        assertTrue(
            r.warnings.any { it.contains("korjattu viikonpäivän") },
            "korjauksesta pitää kertoa: ${r.warnings}",
        )
    }

    @Test
    fun `rikkinaisesta paivayksesta huolimatta tunnit tasmaavat`() {
        val r = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.REAL_OCR_BROKEN_DATE)
        val cmp = PayCalculator.compare(r.shifts, r.employerSummary)

        assertEquals(91 * 60L + 48, cmp.totalCalculatedMinutes)
        assertTrue(cmp.allMatch, cmp.mismatches.toString())
    }

    @Test
    fun `kelvollista paivaysta ei hylata vaikka viikonpaiva ei tasmaa`() {
        // OCR voi lukea väärin kumman tahansa. Päiväyksen hylkääminen hävittäisi
        // vuoron kokonaan, mikä on pahempi virhe kuin väärä viikonpäivä.
        val lines = """
                        suunnitelma  toteutunut  selite
            28.08 ma    i 1400-2125  i 1400-2125
            tunnit yhteensä
        """.trimIndent().lines()

        val r = TitaniaShiftParser(firstYear = 2026).parse(lines)
        assertEquals(1, r.shifts.size, "vuoro ei saa kadota")
        assertEquals(dt("2026-08-28T14:00"), r.shifts[0].start)
        assertTrue(r.warnings.any { it.contains("eivät täsmää") }, r.warnings.toString())
    }

    @Test
    fun `epaonnistunut aikarivi tuottaa varoituksen`() {
        // Varmistus siitä, että jos korjaus ei riitä, vuoro ei katoa hiljaa.
        val rikki = """
                        suunnitelma  toteutunut  selite
            04.09 pe    U 7OO-133O   U 7OO-133O
            tunnit yhteensä
        """.trimIndent().lines()

        val r = TitaniaShiftParser(firstYear = 2026).parse(rikki)
        assertTrue(
            r.warnings.any { it.contains("ei saatu luettua") },
            "varoitus puuttuu: ${r.warnings}",
        )
    }
}
