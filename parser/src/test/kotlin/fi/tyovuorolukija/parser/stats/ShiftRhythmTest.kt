package fi.tyovuorolukija.parser.stats

import fi.tyovuorolukija.parser.Fixtures
import fi.tyovuorolukija.parser.TitaniaShiftParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShiftRhythmTest {

    private val parsed = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.EXAMPLE_PRINTOUT)
    private val stats = ShiftRhythm.of(parsed.shifts, parsed.freeDays)

    @Test
    fun `esimerkkijakson tunnusluvut`() {
        assertEquals(10, stats.shiftCount)
        assertEquals(9, stats.freeDayCount)
        assertEquals(91 * 60L + 48, stats.totalMinutes)
    }

    @Test
    fun `yovuorot tunnistetaan`() {
        // 24.8., 25.8., 7.9. ja 8.9. alkavat yövuorot.
        assertEquals(4, stats.nightShiftCount)
    }

    @Test
    fun `viikonloppuvuorot tunnistetaan`() {
        // la 29.8., su 30.8., la 5.9., su 6.9.
        assertEquals(4, stats.weekendShiftCount)
    }

    @Test
    fun `pisin tyoputki`() {
        // 28.8. pe, 29.8. la, 30.8. su = 3 peräkkäistä päivää.
        // 4.9.–8.9. = pe, la, su, ma, ti = 5 päivää.
        assertEquals(5, stats.longestWorkStreakDays)
    }

    @Test
    fun `lyhin lepo vuorojen valilla`() {
        // Iltavuoro la 29.8. päättyy 21:25, aamuvuoro su 30.8. alkaa 07:00
        // -> lepoa 9 h 35 min. Tämä on jakson ainoa alle 11 tunnin lepo.
        assertEquals(9 * 60L + 35, stats.shortestRestMinutes)
        assertEquals(1, stats.shortRestCount)
    }

    @Test
    fun `alle 11 tunnin lepo lasketaan`() {
        val tiukka = listOf(
            shift("2026-08-24T14:00", "2026-08-24T22:00"),
            shift("2026-08-25T07:00", "2026-08-25T15:00"), // lepo 9 h
        )
        val s = ShiftRhythm.of(tiukka)
        assertEquals(9 * 60L, s.shortestRestMinutes)
        assertEquals(1, s.shortRestCount)
    }

    @Test
    fun `yovuoro ei kasvata tyoputkea seuraavalle paivalle`() {
        // Kaksi yövuoroa peräkkäisinä öinä = 2 työpäivää, ei 3.
        val yot = listOf(
            shift("2026-08-24T21:00", "2026-08-25T07:00"),
            shift("2026-08-25T21:00", "2026-08-26T07:00"),
        )
        assertEquals(2, ShiftRhythm.of(yot).longestWorkStreakDays)
    }

    @Test
    fun `tyhja jakso ei kaada laskentaa`() {
        val s = ShiftRhythm.of(emptyList())
        assertEquals(0, s.shiftCount)
        assertEquals(0, s.longestWorkStreakDays)
        assertEquals(null, s.shortestRestMinutes)
        assertEquals(0, s.averageShiftMinutes)
    }

    private fun shift(start: String, end: String) = fi.tyovuorolukija.parser.Shift(
        code = null,
        start = java.time.LocalDateTime.parse(start),
        end = java.time.LocalDateTime.parse(end),
        confidence = fi.tyovuorolukija.parser.Confidence.OK,
        source = "",
    )
}
