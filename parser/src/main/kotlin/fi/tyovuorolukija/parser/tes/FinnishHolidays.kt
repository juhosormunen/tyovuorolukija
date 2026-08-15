package fi.tyovuorolukija.parser.tes

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Ne juhlapäivät joilla on merkitystä SOTE-sopimuksen työaikakorvauksissa.
 *
 * Lähde: SOTE-sopimus 2025–2028, III luku 18 § 1 ja 3 mom.
 * (https://www.kt.fi/sopimukset/sote/2025-2028/tyoaika/saannollisen-tyoajan-ylittaminen-ja-tyoaikakorvaukset)
 *
 * Huom: juhannuspäivä ja pyhäinpäivä osuvat aina lauantaille. Ne EIVÄT siksi ole
 * "arkilauantaita" — niiltä maksetaan sunnuntaikorvaus koko vuorokaudelta, ei
 * lauantaikorvausta klo 06–18.
 */
object FinnishHolidays {

    /** Pääsiäissunnuntai (anonyymi gregoriaaninen algoritmi). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    /** Juhannuspäivä: kesäkuun 20.–26. päivän välinen lauantai. */
    fun midsummerDay(year: Int): LocalDate =
        (20..26).map { LocalDate.of(year, 6, it) }.first { it.dayOfWeek == DayOfWeek.SATURDAY }

    /** Pyhäinpäivä: 31.10.–6.11. välinen lauantai. */
    fun allSaintsDay(year: Int): LocalDate {
        var d = LocalDate.of(year, 10, 31)
        while (d.dayOfWeek != DayOfWeek.SATURDAY) d = d.plusDays(1)
        return d
    }

    /**
     * Päivät joilta maksetaan sunnuntaityökorvaus koko vuorokaudelta (18 § 1 mom).
     * Sunnuntait eivät ole listassa — ne tunnistetaan viikonpäivästä.
     */
    fun sundayRateHolidays(year: Int): Set<LocalDate> {
        val easter = easterSunday(year)
        return setOf(
            LocalDate.of(year, 1, 1),    // uudenvuodenpäivä
            LocalDate.of(year, 1, 6),    // loppiainen
            easter.minusDays(2),          // pitkäperjantai
            easter.plusDays(1),           // 2. pääsiäispäivä
            LocalDate.of(year, 5, 1),    // vapunpäivä
            easter.plusDays(39),          // helatorstai
            midsummerDay(year),
            allSaintsDay(year),
            LocalDate.of(year, 12, 6),   // itsenäisyyspäivä
            LocalDate.of(year, 12, 25),  // joulupäivä
            LocalDate.of(year, 12, 26),  // tapaninpäivä
        )
    }

    /**
     * Aattopäivät joilta maksetaan aattokorvaus klo 00–18 (18 § 3 mom):
     * pääsiäislauantai, juhannusaatto ja jouluaatto (jos ei osu sunnuntaiksi).
     */
    fun eveDays(year: Int): Set<LocalDate> {
        val christmasEve = LocalDate.of(year, 12, 24)
        return buildSet {
            add(easterSunday(year).minusDays(1))       // pääsiäislauantai
            add(midsummerDay(year).minusDays(1))       // juhannusaatto
            if (christmasEve.dayOfWeek != DayOfWeek.SUNDAY) add(christmasEve)
        }
    }

    /** True jos päivältä maksetaan sunnuntaityökorvaus koko vuorokaudelta. */
    fun isSundayRateDay(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SUNDAY || date in sundayRateHolidays(date.year)

    /**
     * True jos kyseessä on "arkilauantai" (18 § 2 mom). Pääsiäislauantai ja
     * lauantaiksi sattuva jouluaatto on rajattu ulos sopimuksessa erikseen,
     * juhannus- ja pyhäinpäivä sitä kautta että ne ovat sunnuntaikorvauspäiviä.
     */
    fun isOrdinarySaturday(date: LocalDate): Boolean {
        if (date.dayOfWeek != DayOfWeek.SATURDAY) return false
        if (isSundayRateDay(date)) return false
        if (date == easterSunday(date.year).minusDays(1)) return false
        if (date.monthValue == 12 && date.dayOfMonth == 24) return false
        return true
    }

    fun isEveDay(date: LocalDate): Boolean = date in eveDays(date.year)
}
