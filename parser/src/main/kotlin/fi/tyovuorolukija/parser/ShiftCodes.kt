package fi.tyovuorolukija.parser

/**
 * Titania-tulosteen vuorokoodit.
 *
 * HUOM: koodien merkitykset ovat yksikkökohtaisia. Varmat: A/a = aamu, I/i = ilta,
 * Y/y = yö, V = vapaa. Epävarmat (havaittu esimerkkitulosteesta, merkitys kysyttävä
 * työpaikalta): U (0700-1330), E (0700-2125).
 *
 * Isot ja pienet kirjaimet käsitellään toistaiseksi samana. Jos selviää, että ne
 * tarkoittavat eri asiaa (esim. eri osasto), poista [uppercase]-kutsu ja lisää
 * erilliset rivit.
 */
object ShiftCodes {

    /** Koodit joiden merkitys on varmistettu. */
    private val KNOWN = mapOf(
        "A" to "Aamuvuoro",
        "I" to "Iltavuoro",
        "Y" to "Yövuoro",
        "V" to "Vapaa",
    )

    /** Koodit jotka on nähty tulosteessa mutta joiden merkitystä ei ole varmistettu. */
    private val UNCONFIRMED = mapOf(
        "U" to "Vuoro U",
        "E" to "Vuoro E",
    )

    fun title(code: String?): String {
        if (code == null) return "Työvuoro"
        val key = code.uppercase()
        return KNOWN[key] ?: UNCONFIRMED[key] ?: "Vuoro $code"
    }

    /** True jos koodin merkitystä ei ole varmistettu -> kannattaa näyttää käyttäjälle. */
    fun isUnconfirmed(code: String?): Boolean {
        if (code == null) return false
        return !KNOWN.containsKey(code.uppercase())
    }
}
