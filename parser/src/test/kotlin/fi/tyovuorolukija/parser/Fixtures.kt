package fi.tyovuorolukija.parser

/**
 * Testifixturet. [EXAMPLE_PRINTOUT] on litteroitu oikeasta valokuvasta
 * (Pirha / Titania, jakso 24.08.–13.09., työaikaprosentti 80).
 *
 * Kun keräät lisää valokuvia, litteroi jokainen tänne omaksi vakiokseen ja
 * kirjoita sille testi. Formaatti vaihtelee todennäköisesti enemmän kuin uskoisi.
 */
object Fixtures {

    val EXAMPLE_PRINTOUT: List<String> = """
        työaikamuoto:   Jaksotyö, vuorotyöluonteinen, kuukausipalkkainen, SOTE
        työaikaprosentti:    80,00

                    suunnitelma  toteutunut  selite
        ------------------------------------------------
        24.08 ma    y 2100-2400  y 2100-2400
        25.08 ti      0000-0712    0000-0712
                    y 2100-2400  y 2100-2400
        26.08 ke      0000-0710    0000-0710
        27.08 to    V            V            vapaapäivä
        28.08 pe    i 1400-2125  i 1400-2125
        29.08 la    i 1400-2125  i 1400-2125
        30.08 SU    a 0700-1450  a 0700-1450
        ------------------------------------------------
        31.08 ma    V            V
        01.09 ti    V            V            vapaapäivä
        02.09 ke    V            V            vapaapäivä
        03.09 to    V            V            vapaapäivä
        04.09 pe    U 0700-1330  U 0700-1330
        05.09 la    E 0700-2125  E 0700-2125
        06.09 SU    I 1400-2125  I 1400-2125
        ------------------------------------------------
        07.09 ma    Y 2100-2400  Y 2100-2400
        08.09 ti      0000-0713    0000-0713
                    Y 2100-2400  Y 2100-2400
        09.09 ke      0000-0713    0000-0713
        10.09 to    V            V            vapaapäivä
        11.09 pe    V            V            vapaapäivä
        12.09 la    V            V            vapaapäivä
        13.09 SU    V            V            vapaapäivä
        ------------------------------------------------
        tunnit yhteensä    91:48    91:48
        jakson tunnit      91:48    91:48
        suunnitteluraja    91:48
        lisätyöraja        91:48    91:48
        ylityöraja        114:45   114:45

        tunnit yhteensä
        sunnuntaityö       22:05
        iltatyö  (18-22)   17:40
        yötyö    (22-07)   36:00
        lauantaityö        15:00
    """.trimIndent().lines()

    /** Kesäajan päättyminen su 25.10.2026 klo 04:00 -> 03:00. */
    val DST_AUTUMN: List<String> = """
                    suunnitelma  toteutunut  selite
        24.10 la    y 2100-2400  y 2100-2400
        25.10 SU      0000-0712    0000-0712
        tunnit yhteensä    10:12
    """.trimIndent().lines()

    /** Kesäajan alkaminen su 29.3.2026 klo 03:00 -> 04:00. */
    val DST_SPRING: List<String> = """
                    suunnitelma  toteutunut  selite
        28.03 la    y 2100-2400  y 2100-2400
        29.03 SU      0000-0713    0000-0713
        tunnit yhteensä    10:13
    """.trimIndent().lines()

    /** Vuodenvaihde: kuukausi pienenee 12 -> 01. */
    val YEAR_ROLLOVER: List<String> = """
                    suunnitelma  toteutunut  selite
        30.12 ti    a 0700-1450  a 0700-1450
        31.12 ke    y 2100-2400  y 2100-2400
        01.01 to      0000-0712    0000-0712
        02.01 pe    V            V            vapaapäivä
        tunnit yhteensä    18:02
    """.trimIndent().lines()
}
