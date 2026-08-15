package fi.tyovuorolukija.parser.tes

import fi.tyovuorolukija.parser.Shift
import java.time.LocalDateTime

/** Työaikakorvausten piiriin kuuluvat minuutit lajeittain. */
data class SupplementMinutes(
    val total: Long = 0,
    val evening: Long = 0,
    val night: Long = 0,
    val saturday: Long = 0,
    val sunday: Long = 0,
    val eve: Long = 0,
) {
    operator fun plus(o: SupplementMinutes) = SupplementMinutes(
        total + o.total, evening + o.evening, night + o.night,
        saturday + o.saturday, sunday + o.sunday, eve + o.eve,
    )
}

/**
 * Laskee työaikakorvausten tunnit vuoroista SOTE-sopimuksen sääntöjen mukaan.
 *
 * Lähde: SOTE-sopimus 2025–2028, III luku 18 § ja 19 §.
 * https://www.kt.fi/sopimukset/sote/2025-2028/tyoaika/saannollisen-tyoajan-ylittaminen-ja-tyoaikakorvaukset
 *
 * Laskenta etenee minuutti kerrallaan. Se on karkeaa mutta ilmeisen oikeaa, ja
 * kuuden viikon jakso on vain muutama tuhat minuuttia — nopeus ei ole ongelma.
 * Vaihtoehtoinen aikavälialgebra olisi nopeampi mutta helpompi saada väärin,
 * ja tässä oikeellisuus on ainoa mikä merkitsee: kyse on jonkun palkasta.
 *
 * Lajit eivät ole toisensa poissulkevia. Lauantai klo 18–22 on samaan aikaan
 * sekä iltatyötä (19 §) että sunnuntaityötä (18 §), ja molemmat maksetaan.
 * Tämä vastaa työnantajan omaa erittelyä — vertailu tarkistaa asian.
 */
object SupplementHours {

    private const val EVENING_START = 18
    private const val EVENING_END = 22
    private const val NIGHT_START = 22
    private const val NIGHT_END = 7
    private const val SATURDAY_START = 6
    private const val SATURDAY_END = 18
    private const val EVE_END = 18
    private const val SUNDAY_EVE_START = 18

    fun of(shifts: List<Shift>): SupplementMinutes =
        shifts.fold(SupplementMinutes()) { acc, shift -> acc + of(shift) }

    fun of(shift: Shift): SupplementMinutes {
        var total = 0L
        var evening = 0L
        var night = 0L
        var saturday = 0L
        var sunday = 0L
        var eve = 0L

        var t: LocalDateTime = shift.start
        while (t.isBefore(shift.end)) {
            total++
            val hour = t.hour
            val date = t.toLocalDate()

            if (hour in EVENING_START until EVENING_END) evening++
            if (hour >= NIGHT_START || hour < NIGHT_END) night++

            if (FinnishHolidays.isOrdinarySaturday(date) &&
                hour in SATURDAY_START until SATURDAY_END
            ) {
                saturday++
            }

            // Sunnuntaikorvaus: koko vuorokausi sunnuntailta ja juhlapyhiltä,
            // sekä klo 18–24 lauantailta ja juhlapyhän aatolta (18 § 1 mom).
            val sundayRate = FinnishHolidays.isSundayRateDay(date) ||
                (hour >= SUNDAY_EVE_START && isEveOfSundayRateDay(date))
            if (sundayRate) sunday++

            if (FinnishHolidays.isEveDay(date) && hour < EVE_END) eve++

            t = t.plusMinutes(1)
        }

        return SupplementMinutes(total, evening, night, saturday, sunday, eve)
    }

    /**
     * Lauantai-ilta ja juhlapyhän aatto klo 18 jälkeen. Lauantai on mukana aina,
     * myös silloin kun sunnuntai ei satu olemaan juhlapyhä — sopimus mainitsee
     * lauantain erikseen.
     */
    private fun isEveOfSundayRateDay(date: java.time.LocalDate): Boolean =
        date.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
            FinnishHolidays.isSundayRateDay(date.plusDays(1))
}

/** Muotoilee minuutit tulosteen tyyliin "36:00". */
fun Long.toHoursMinutes(): String = "%d:%02d".format(this / 60, this % 60)
