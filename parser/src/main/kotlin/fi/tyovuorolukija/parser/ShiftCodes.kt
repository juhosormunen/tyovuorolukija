package fi.tyovuorolukija.parser

/**
 * Titania-tulosteen vuorokoodit.
 *
 * **Kirjainkoolla ei ole merkitystä.** Tuloste käyttää sekaisin isoja ja pieniä
 * kirjaimia (`y` ja `Y`, `i` ja `I`), eikä eron merkitystä ole toistaiseksi
 * tarpeen selvittää — koodit tulkitaan samoiksi eikä kirjainkoosta varoiteta.
 * Alkuperäinen kirjoitusasu säilyy kalenteritapahtuman kuvauksessa.
 *
 * Koodien merkitykset on varmistettu käyttäjältä:
 * - `A` aamu, `I` ilta, `Y` yö, `V` vapaa
 * - `E` on pitkä vuoro (aamusta iltaan)
 * - `U` ei ole vuorotyyppi lainkaan vaan sisäinen merkintä siitä, mitä vuoron
 *   aikana tehdään. Käsin tehdyissä kalenterimerkinnöissä siitä on käytetty
 *   nimeä "U-päivä", joten sama nimi täällä.
 *
 * Tuntemattomista koodeista varoitetaan edelleen ([isUnknown]) — tulosteissa on
 * havaittu ainakin `R` ja `D`, joiden merkitystä ei tiedetä.
 */
object ShiftCodes {

    private val TITLES = mapOf(
        "A" to "Aamuvuoro",
        "I" to "Iltavuoro",
        "Y" to "Yövuoro",
        "V" to "Vapaa",
        "E" to "Pitkä vuoro",
        "U" to "U-päivä",
    )

    fun title(code: String?): String {
        if (code == null) return "Työvuoro"
        return TITLES[code.uppercase()] ?: "Vuoro $code"
    }

    /**
     * True jos koodia ei tunneta lainkaan. Tällainen vuoro merkitään
     * käyttöliittymässä tarkistettavaksi, koska sen tulkinta on arvaus.
     */
    fun isUnknown(code: String?): Boolean {
        if (code == null) return false
        return !TITLES.containsKey(code.uppercase())
    }
}
