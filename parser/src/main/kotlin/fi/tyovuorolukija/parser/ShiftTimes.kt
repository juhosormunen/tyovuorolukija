package fi.tyovuorolukija.parser

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Paikallisen ajan muunnos absoluuttiseksi ajaksi.
 *
 * Tuloste on paikallista aikaa ilman aikavyöhykettä. Kalenteri tarvitsee epoch-millit,
 * ja juuri tässä kohtaa kesäaika ratkaisee: 25.10. yövuoro on tunnin pidempi ja
 * 29.03. yövuoro tunnin lyhyempi kuin kellonajoista laskien.
 *
 * Tämä on tarkoituksella parser-moduulissa (puhdasta java.timea), jotta DST-tapaukset
 * saa unit-testattua ilman emulaattoria.
 */
object ShiftTimes {

    val HELSINKI: ZoneId = ZoneId.of("Europe/Helsinki")

    /**
     * Huom: jos paikallista aikaa ei ole olemassa (kevään siirrossa klo 03:00–03:59),
     * java.time siirtää sen eteenpäin siirtymän verran. Sama sääntö kuin Androidin
     * kalenterisovelluksilla, joten tapahtuma menee samaan kohtaan kuin käsin lisättynä.
     */
    fun startAt(shift: Shift, zone: ZoneId = HELSINKI): ZonedDateTime =
        shift.start.atZone(zone)

    fun endAt(shift: Shift, zone: ZoneId = HELSINKI): ZonedDateTime =
        shift.end.atZone(zone)

    fun startMillis(shift: Shift, zone: ZoneId = HELSINKI): Long =
        startAt(shift, zone).toInstant().toEpochMilli()

    fun endMillis(shift: Shift, zone: ZoneId = HELSINKI): Long =
        endAt(shift, zone).toInstant().toEpochMilli()

    /** Todellinen kesto seinäkellosta riippumatta — DST huomioituna. */
    fun actualDuration(shift: Shift, zone: ZoneId = HELSINKI): Duration =
        Duration.between(startAt(shift, zone).toInstant(), endAt(shift, zone).toInstant())
}
