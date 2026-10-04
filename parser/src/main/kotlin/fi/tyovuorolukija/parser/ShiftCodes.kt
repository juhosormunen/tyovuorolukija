package fi.tyovuorolukija.parser

/**
 * Titania-tulosteen vuorokoodit ja niistä muodostettavat nimet.
 *
 * Nimeämismuoto on **`Nimi (koodi)`**, esim. `Yö (y)`. Se on sama muoto jota
 * kalenterimerkinnöissä on käytetty käsin, ja koska sovelluksen ja käsin tehdyt
 * merkinnät päätyvät samaan kalenteriin, yhtenäinen muoto vähentää sekaannusta.
 * Koodi näkyy sulkeissa **alkuperäisessä kirjoitusasussaan**, joten tulosteen
 * `y` ja `Y` erottuvat toisistaan vaikka ne tulkitaan samaksi vuorotyypiksi.
 *
 * Poikkeus: `U` ei ole vuorotyyppi lainkaan vaan sisäinen merkintä siitä, mitä
 * vuoron aikana tehdään. Sille ei siis ole "vuoron nimeä" johon koodin voisi
 * liittää, vaan se on pelkkä `U-päivä`.
 *
 * **Kirjainkoolla ei ole merkitystä tulkinnassa** eikä siitä varoiteta. Sen sijaan
 * aidosti tuntemattomista koodeista varoitetaan ([isUnknown]) — tulosteissa on
 * nähty ainakin `R` ja `D`, joiden merkitys on selvittämättä. Varoitus on tieto,
 * ei tarkistuspyyntö: kellonajat luetaan koodista riippumatta, joten tuntematon
 * koodi vaikuttaa vain otsikkoon eikä vuoroa merkitä sen takia tarkistettavaksi.
 *
 * `K` = koulutus. Selvisi tulosteesta 05.10.–25.10.2026, jossa rivin selitteenä
 * luki "koulutus". Koulutus on tyypillisesti osa työpäivää (`R 1100-1200`,
 * `K 1200-1430`, `R 1430-2130`), ja osat yhdistetään yhdeksi vuoroksi.
 */
object ShiftCodes {

    /** Vuorotyypin nimi ilman koodia. */
    private val NAMES = mapOf(
        "A" to "Aamu",
        "I" to "Ilta",
        "Y" to "Yö",
        "V" to "Vapaa",
        "E" to "Pitkä",
        "K" to "Koulutus",
    )

    /** Koodit joilla ei ole vuorotyyppiä — nimi sellaisenaan, ilman sulkeita. */
    private val STANDALONE = mapOf(
        "U" to "U-päivä",
    )

    /** Nimi ilman koodia, esim. selitteisiin: "Yö". */
    fun name(code: String?): String {
        if (code == null) return "Työvuoro"
        val key = code.uppercase()
        return NAMES[key] ?: STANDALONE[key] ?: "Vuoro"
    }

    /** Kalenteritapahtuman ja listan otsikko, esim. "Yö (y)" tai "U-päivä". */
    fun title(code: String?): String {
        if (code == null) return "Työvuoro"
        val key = code.uppercase()
        STANDALONE[key]?.let { return it }
        return "${NAMES[key] ?: "Vuoro"} ($code)"
    }

    /**
     * Otsikko vuorolle, joka on koottu peräkkäisistä osista, esim.
     * "Vuoro (R) + Koulutus (K)". Pääkoodi ensin, muut sen perään.
     */
    fun title(code: String?, extraCodes: List<String>): String =
        (listOf(title(code)) + extraCodes.map { title(it) }).distinct().joinToString(" + ")

    /**
     * True jos koodia ei tunneta lainkaan. Tällainen vuoro merkitään
     * käyttöliittymässä tarkistettavaksi, koska sen tulkinta on arvaus.
     */
    fun isUnknown(code: String?): Boolean {
        if (code == null) return false
        val key = code.uppercase()
        return !NAMES.containsKey(key) && !STANDALONE.containsKey(key)
    }
}
