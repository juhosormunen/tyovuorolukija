package fi.tyovuorolukija.parser.tes

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Korjaa ennen versiota 0.19 tallennetun jakson rahaluvut.
 *
 * Silloin jakson bruttoon laskettiin **koko kuukausipalkka**, vaikka jakso on kolme
 * viikkoa. Historian kertymät ja vuosiyhteenvedot paisuivat sen mukana: kolme jaksoa
 * kuukaudessa tarkoitti kolmea kuukausipalkkaa.
 *
 * Korjaus ei tarvitse alkuperäisiä asetuksia. Peruspalkan osuus vaihdetaan
 * [PayCalculator.periodBasePay]in tulokseen, ja vero ja maksut lasketaan uudesta
 * bruttosta samoilla prosenteilla, jotka saadaan tallennetuista luvuista.
 * Lisät eivät muutu.
 *
 * Kaikki summat sentteinä, kuten tietokannassa.
 */
object LegacyPayFix {

    data class Cents(
        val base: Long,
        val gross: Long,
        val tax: Long?,
        val contributions: Long?,
        val net: Long?,
    )

    fun fix(
        period: ClosedRange<LocalDate>,
        monthlySalary: Long,
        gross: Long,
        tax: Long?,
        contributions: Long?,
        net: Long?,
    ): Cents {
        val base = PayCalculator.periodBasePay(BigDecimal(monthlySalary).movePointLeft(2), period)
            .movePointRight(2).toLong()
        val newGross = gross - monthlySalary + base
        // Prosentti palautetaan tallennetuista luvuista kahden desimaalin tarkkuuteen
        // (asetuksissa ei ole tarkempia) ja summa lasketaan siitä uudelleen samalla
        // pyöristyksellä kuin PayCalculator. Pelkkä suhteellinen skaalaus jo
        // pyöristetystä summasta heittäisi senttejä.
        fun scale(v: Long?): Long? = v?.let {
            if (gross == 0L) return@let it
            val percent = BigDecimal(it).multiply(BigDecimal(100))
                .divide(BigDecimal(gross), 2, RoundingMode.HALF_UP)
            BigDecimal(newGross).multiply(percent)
                .divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()
        }
        val newTax = scale(tax)
        val newContributions = scale(contributions)
        val newNet = when {
            net == null -> null
            newTax != null && newContributions != null -> newGross - newTax - newContributions
            else -> scale(net)
        }
        return Cents(base, newGross, newTax, newContributions, newNet)
    }
}
