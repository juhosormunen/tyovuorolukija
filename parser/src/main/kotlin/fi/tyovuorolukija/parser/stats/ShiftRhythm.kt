package fi.tyovuorolukija.parser.stats

import fi.tyovuorolukija.parser.FreeDay
import fi.tyovuorolukija.parser.Shift
import java.time.DayOfWeek
import java.time.temporal.ChronoUnit

/**
 * Kuormitusta kuvaavat tunnusluvut yhdestä jaksosta.
 *
 * Nämä eivät ole TES-laskentaa vaan seurantaa: onko jakso raskas, kasautuvatko
 * yövuorot, jääkö vuorojen väliin riittävästi lepoa.
 */
data class RhythmStats(
    val totalMinutes: Long = 0,
    val shiftCount: Int = 0,
    /** Vuoro joka sisältää työtä klo 22–07. */
    val nightShiftCount: Int = 0,
    /** Vuoro joka alkaa lauantaina tai sunnuntaina. */
    val weekendShiftCount: Int = 0,
    /** Pisin putki peräkkäisiä päiviä joina vuoro alkaa. */
    val longestWorkStreakDays: Int = 0,
    /** Lyhin lepo peräkkäisten vuorojen välillä, null jos vuoroja on alle kaksi. */
    val shortestRestMinutes: Long? = null,
    /** Montako kertaa lepo jäi alle 11 tunnin. */
    val shortRestCount: Int = 0,
    val freeDayCount: Int = 0,
) {
    val averageShiftMinutes: Long
        get() = if (shiftCount == 0) 0 else totalMinutes / shiftCount
}

object ShiftRhythm {

    /**
     * Työaikalain vuorokausilepo on pääsääntöisesti 11 tuntia. Jaksotyössä siitä
     * voidaan poiketa (9 h), joten alle 11 tunnin lepo ei automaattisesti tarkoita
     * rikkomusta — se on huomion arvoinen, ei syytös. Lukua näytetään sellaisenaan.
     */
    const val REST_THRESHOLD_MINUTES = 11 * 60L

    fun of(shifts: List<Shift>, freeDays: List<FreeDay> = emptyList()): RhythmStats {
        val sorted = shifts.sortedBy { it.start }

        var nights = 0
        var weekend = 0
        for (s in sorted) {
            if (containsNightWork(s)) nights++
            if (s.start.dayOfWeek == DayOfWeek.SATURDAY ||
                s.start.dayOfWeek == DayOfWeek.SUNDAY
            ) weekend++
        }

        var shortestRest: Long? = null
        var shortRests = 0
        for (i in 1 until sorted.size) {
            val rest = ChronoUnit.MINUTES.between(sorted[i - 1].end, sorted[i].start)
            if (rest < 0) continue // päällekkäiset vuorot — ei mielekästä lepoa
            if (shortestRest == null || rest < shortestRest) shortestRest = rest
            if (rest < REST_THRESHOLD_MINUTES) shortRests++
        }

        return RhythmStats(
            totalMinutes = sorted.sumOf { ChronoUnit.MINUTES.between(it.start, it.end) },
            shiftCount = sorted.size,
            nightShiftCount = nights,
            weekendShiftCount = weekend,
            longestWorkStreakDays = longestStreak(sorted),
            shortestRestMinutes = shortestRest,
            shortRestCount = shortRests,
            freeDayCount = freeDays.size,
        )
    }

    /**
     * Putki lasketaan vuoron **alkamispäivistä**. Yövuoro venyy seuraavan päivän
     * puolelle, mutta se ei tee seuraavasta päivästä työpäivää — muuten jokainen
     * yövuoro kasvattaisi putkea keinotekoisesti.
     */
    private fun longestStreak(sorted: List<Shift>): Int {
        val days = sorted.map { it.start.toLocalDate() }.distinct().sorted()
        if (days.isEmpty()) return 0

        var longest = 1
        var current = 1
        for (i in 1 until days.size) {
            current = if (days[i - 1].plusDays(1) == days[i]) current + 1 else 1
            if (current > longest) longest = current
        }
        return longest
    }

    private fun containsNightWork(shift: Shift): Boolean {
        var t = shift.start
        while (t.isBefore(shift.end)) {
            if (t.hour >= 22 || t.hour < 7) return true
            t = t.plusMinutes(30)
        }
        return false
    }
}
