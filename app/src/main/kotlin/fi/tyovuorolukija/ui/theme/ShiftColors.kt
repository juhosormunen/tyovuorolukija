package fi.tyovuorolukija.ui.theme

import androidx.compose.ui.graphics.Color
import fi.tyovuorolukija.parser.ShiftCodes

/**
 * Vuorotyyppien värit — **identiteettivärejä, eivät datavärejä.**
 *
 * Näitä käytetään siellä missä väri on tunniste ja teksti kulkee aina mukana:
 * sovelluksen ikoni, vuorolistan reunapalkki, kalenteritapahtuman väri. Silloin
 * väri nopeuttaa hahmottamista mutta ei kanna merkitystä yksin.
 *
 * **Näitä ei saa käyttää graafien sarjaväreinä.** Validaattori hylkää ne
 * datamerkkeinä: aamun keltainen on liian vaalea (OKLCH L 0.86, sallittu 0.43–0.77),
 * yön sininen liian harmaa (kroma 0.09, minimi 0.10), ja keltainen vs. korallinpunainen
 * erottuvat toisistaan vain ΔE 12 normaalinäöllä kun raja on 15. Sävyjen tummentaminen
 * ei auta: keltainen ja oranssi jäävät silloinkin alle rajan (ΔE 13.7 vaaleassa,
 * 10.6 tummassa). Graafit käyttävät [fi.tyovuorolukija.ui.charts.VizColors]-paletista
 * validoituja askelmia — jotka ovat samaa sinistä ja korallia, vain datalle stepattuina.
 */
object ShiftColors {

    val Morning = Color(0xFFFAC775)
    val Evening = Color(0xFFF0997B)
    val Night = Color(0xFF85B7EB)
    val Free = Color(0xFFB4B2A9)
    val Other = Color(0xFF7FCFC8)

    /** Brändin petroli — ikonin tausta ja sovelluksen primary. */
    val Brand = Color(0xFF0F6E56)

    fun forCode(code: String?): Color = when (code?.uppercase()) {
        "A" -> Morning
        "I" -> Evening
        "Y" -> Night
        "V" -> Free
        else -> Other
    }

    /** ARGB-kokonaisluku kalenteritapahtumaa varten. */
    fun argbForCode(code: String?): Int = forCode(code).value.toLong().let {
        (it shr 32).toInt()
    }

    /** Selite ikonin kehälle ja mahdolliselle värilegendalle. */
    val legend: List<Pair<String, Color>> = listOf(
        ShiftCodes.title("A") to Morning,
        ShiftCodes.title("I") to Evening,
        ShiftCodes.title("Y") to Night,
        ShiftCodes.title("V") to Free,
    )
}
