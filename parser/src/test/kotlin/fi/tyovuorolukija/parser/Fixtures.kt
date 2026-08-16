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

    /**
     * Oikea ML Kit -tuloste toisen käyttäjän puhelimesta 16.8.2026, samasta
     * jaksosta kuin [EXAMPLE_PRINTOUT]. Kopioitu sovelluksen omasta
     * "Kopioi tunnistustiedot" -toiminnosta, ei siistitty.
     *
     * Sisältää oikeat OCR-virheet, joiden takia kaksi vuoroa katosi kokonaan:
     * - nolla luettuna e:nä (`e000-e712`, `U e700-1330`)
     * - numeroita pudonnut (`070-1450`, `210-2400`, `0000-713`)
     * - käsinkirjoitettu sarake vuotanut mukaan (`8-l6`, `5- (6`, `8- le`)
     * - roskaa rivin alussa (`!>>>>u`)
     *
     * Tämä on arvokkain testifixture: keksityllä syötteellä näitä ei olisi keksinyt.
     */
    val REAL_OCR: List<String> = """
        pirha-titania.monetra.fi/itania/faces/s s/summarylistprintpage.
        13.8.2026 klo 22. 17 xhtml?txtSummaryListToPrint=000000000000000000
        TYÖVUOROTAULUKKO 13.08.2026 12:47:56
        SAIRAALA 12/26 taulukon rivi 31
        T0000 OSASTO 1, VUODEOSASTO
        24.08. 26-13 .09. 26 toteutunut lista
        e1234-000A MEIKÄLÄINEN MAIJA
        00000 SAIRAANHOITAJA
        työaikamuoto : Jaksotyö, vuorotyöluonteinen, kuukausipalkkainen, SOTE
        työaikaprosentti: 80,00
        Suunnitelma toteutunut selite Hoito
        8-l6
        24.08 ma y 2100-2400 y 2100-2400
        25.08 ti e000-e712 e000-712 8-17
        y 210e-2400 y 2100-2400
        26.08 ke 0000-0710 0000-0710 8-o
        27.08 to vapaapäivä V
        28.08 pe i 1400-2125 i 1400-2125 5- (6
        29.08 la i 1400- 2125 i 1400-2125
        30.08 SU a 070-1450 a 0700-1450
        31.08 ma vapaapäivä
        01.09 ti vapaapäivä
        02.09 ke !>>>>u V vapaapäivä
        03.09 to V vapaapäivä 8- le
        04.09 pe U 700-1330 U e700-1330
        8-15
        05.09 la E 0700-2125 E 070-2125
        06.09 SU I 1400-2125 I 1400-2125
        07.09 ma Y 210-2400 Y 2100-2400
        08.09 ti 0000-0713 0000-0713
        Y 2100-2400 Y 2100-2400
        09.09 ke 0000-713 0000-0713
        10.09 to V vapaapäivä
        11.09 pe vapaapäivä
        12.09 la vapaapäivä
        13.09 SU V vapaapäivä
        tunnit yhteensä 91:48 91:48
        jakson tunnit 91:48 91:48
        suunnitteluraja 91:48
        lisätyöraja 91:48 91:48
        ylityöraja 114:45 114:45
        tämän siirto siirto
        jakson edell. seuraav. aika-
        tunnit jaksolta jaksolle hyvitys maksuun
        tunnit yhteensä 91:48 91:48
        sunnuntaityö 22:05 22:05
        iltatyö (18-22) 17:40 17:40
        yötyö (22-07) 36:00 36:00
        lauantaityö 15:00 15:00
        kertymä
        Vapaa-aikakorvaukset
        muu korvausvapaa 27:44
    """.trimIndent().lines()

    /**
     * Toinen oikea ML Kit -tuloste samasta jaksosta, eri valokuvasta.
     *
     * Tässä kellonajat luettiin oikein, mutta **päiväys meni rikki**: `94.09 pe`
     * (= 04.09). Päivä 94 ei kelpaa, joten koko rivi hylättiin ja U-vuoro katosi.
     *
     * Korjaus tulee rivin omasta redundanssista: viikonpäivä `pe` yhdistettynä
     * edelliseen päiväykseen (03.09) määrittää päivän yksikäsitteisesti.
     */
    val REAL_OCR_BROKEN_DATE: List<String> = """
        AAAN PUHAL
        13.8.2026 klo 22.17 pirha-titania.monetra.f/titania/faces/summarylistprintpage.xhtm1PtxtSummaryList ToPrint=000000000000000000
        SAIRAALA TYÖVUOROTAULUKKO 13.08.2026 12:47:56
        T0000 OSASTO 1, VUODEOSASTO 12/26 taulukon rivi 31
        e12340-000A MEIKÄLÄINEN MAIJA 24.08.26-13.09.26 toteutunut lista
        00000 SAIRAANHOITAJA
        työaikamuoto: Jaksotyö, vuorotyöluonteinen, kuukausipal kkainen, SOTE
        työaikaprosentti: 80,00
        suunnitelma toteutunut selite Hoito
        8-lb
        24.08 ma y 2100-2400 y 2100-2400
        25.08 ti 0000-0712 0000-0712 8-17
        y 2100- 2400 y 2100-2400
        26.08 ke 0000-0710 0000-710 8-o
        27.08 to vapaapäivä V
        28.08 pe i 1400-2125 i 1400-2125
        29.08 la i 1400-2125 i 1400-2125
        30.08 SU a 0700-1450 a 0700-1450
        31.08 ma vapaapäivä V
        01.09 ti V
        vapaapäivä
        02.09 ke << vapaapäivä
        03.09 to vapaapäivä 8- lle
        94.09 pe U 0700-1330 U 0700-1330
        05.09 la E 0700-2125 E 070-2125 8-15
        06.09 SU I 1400-2125 I 1400-2125
        07.09 ma Y 2100-2400 Y 2100-2400 8- lo
        08.09 ti 0000-0713 0000-0713
        Y 2100-2400 Y 2100-2400
        09.09 ke 0000-0713 0000-0713
        10.09 to vapaapäivä
        11.09 pe V vapaapäivä
        12.09 la V V vapaapäivä V
        13.09 SU V vapaapäivä
        tunnit yhteensä 91:48 91:48
        jakson tunnit 91:48 91:48
        suunnitteluraja 91:48
        lisätyöraja 91:48 91:48
        A
        ylityöraja 114:45 114:45
        tämän siirto siirto
        jakson edell. seuraav. aika-
        tunnit jaksolta jaksolle hyvitys maksuun
        tunnit yhteensä 91:48 91:48
        sunnuntaityö 22:05 22:05
        iltatyö (18-22) 17:40 17:40
        yötyö (22-07) 36:00 36:00
        lauantaityö 15:00 15:00
        kertymä
        Vapaa-aikakorvaukset
        muu korvausvapaa 27:44
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

    /**
     * Vuodenvaihde: kuukausi pienenee 12 -> 01.
     *
     * Viikonpäivät ovat 2026/2027 mukaiset. Ne olivat aiemmin keksittyjä, mikä
     * paljastui vasta kun parseri alkoi tarkistaa päiväyksen viikonpäivää vasten.
     */
    val YEAR_ROLLOVER: List<String> = """
                    suunnitelma  toteutunut  selite
        30.12 ke    a 0700-1450  a 0700-1450
        31.12 to    y 2100-2400  y 2100-2400
        01.01 pe      0000-0712    0000-0712
        02.01 la    V            V            vapaapäivä
        tunnit yhteensä    18:02
    """.trimIndent().lines()
}
