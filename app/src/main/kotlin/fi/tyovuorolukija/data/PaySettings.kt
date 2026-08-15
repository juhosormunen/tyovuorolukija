package fi.tyovuorolukija.data

import android.content.Context
import fi.tyovuorolukija.parser.tes.EmployeeContributions
import fi.tyovuorolukija.parser.tes.TesRates

/**
 * Palkkalaskennan asetukset. Nämä ovat käyttäjän omia lukuja (palkka, veroprosentti)
 * ja TES:n prosentteja, jotka pitää voida päivittää kun sopimuskausi vaihtuu.
 *
 * Kentät ovat merkkijonoja, koska ne tulevat suoraan tekstikentistä ja
 * puolivalmis syöte ("2 6 0") ei saa kaataa laskentaa.
 */
data class PayForm(
    val monthlySalary: String = "",
    val partTimePercent: String = "",
    val taxPercent: String = "",
    val eveningPercent: String = "15",
    val nightPercent: String = "40",
    val saturdayPercent: String = "20",
    val sundayPercent: String = "100",
    val divisor: String = "163",
    val pensionPercent: String = "7.15",
    val unemploymentPercent: String = "0.59",
) {
    private fun String.num(): Double? = trim().replace(',', '.').toDoubleOrNull()

    val salary: java.math.BigDecimal?
        get() = monthlySalary.num()?.takeIf { it > 0 }?.let { java.math.BigDecimal.valueOf(it) }

    val partTime: Double get() = partTimePercent.num()?.takeIf { it > 0 } ?: 100.0
    val tax: Double? get() = taxPercent.num()?.takeIf { it >= 0 }

    val rates: TesRates
        get() = TesRates(
            eveningPercent = eveningPercent.num() ?: 15.0,
            nightPercent = nightPercent.num() ?: 40.0,
            saturdayPercent = saturdayPercent.num() ?: 20.0,
            sundayPercent = sundayPercent.num() ?: 100.0,
            monthlyDivisor = divisor.num()?.toInt()?.takeIf { it > 0 } ?: 163,
        )

    val contributions: EmployeeContributions
        get() = EmployeeContributions(
            pensionPercent = pensionPercent.num() ?: 7.15,
            unemploymentPercent = unemploymentPercent.num() ?: 0.59,
        )

    /** Palkkalaskelma voidaan näyttää vasta kun palkka on syötetty. */
    val isComplete: Boolean get() = salary != null
}

/** Yksinkertainen SharedPreferences-tallennus — asetuksia on vähän eikä niitä kysellä usein. */
class PaySettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("palkka-asetukset", Context.MODE_PRIVATE)

    fun load(): PayForm {
        val d = PayForm()
        return PayForm(
            monthlySalary = prefs.getString(KEY_SALARY, d.monthlySalary)!!,
            partTimePercent = prefs.getString(KEY_PART_TIME, d.partTimePercent)!!,
            taxPercent = prefs.getString(KEY_TAX, d.taxPercent)!!,
            eveningPercent = prefs.getString(KEY_EVENING, d.eveningPercent)!!,
            nightPercent = prefs.getString(KEY_NIGHT, d.nightPercent)!!,
            saturdayPercent = prefs.getString(KEY_SATURDAY, d.saturdayPercent)!!,
            sundayPercent = prefs.getString(KEY_SUNDAY, d.sundayPercent)!!,
            divisor = prefs.getString(KEY_DIVISOR, d.divisor)!!,
            pensionPercent = prefs.getString(KEY_PENSION, d.pensionPercent)!!,
            unemploymentPercent = prefs.getString(KEY_UNEMPLOYMENT, d.unemploymentPercent)!!,
        )
    }

    fun save(form: PayForm) {
        prefs.edit()
            .putString(KEY_SALARY, form.monthlySalary)
            .putString(KEY_PART_TIME, form.partTimePercent)
            .putString(KEY_TAX, form.taxPercent)
            .putString(KEY_EVENING, form.eveningPercent)
            .putString(KEY_NIGHT, form.nightPercent)
            .putString(KEY_SATURDAY, form.saturdayPercent)
            .putString(KEY_SUNDAY, form.sundayPercent)
            .putString(KEY_DIVISOR, form.divisor)
            .putString(KEY_PENSION, form.pensionPercent)
            .putString(KEY_UNEMPLOYMENT, form.unemploymentPercent)
            .apply()
    }

    private companion object {
        const val KEY_SALARY = "kuukausipalkka"
        const val KEY_PART_TIME = "tyoaikaprosentti"
        const val KEY_TAX = "veroprosentti"
        const val KEY_EVENING = "iltalisa"
        const val KEY_NIGHT = "yolisa"
        const val KEY_SATURDAY = "lauantailisa"
        const val KEY_SUNDAY = "sunnuntailisa"
        const val KEY_DIVISOR = "jakaja"
        const val KEY_PENSION = "tyoelakemaksu"
        const val KEY_UNEMPLOYMENT = "tyottomyysvakuutus"
    }
}
