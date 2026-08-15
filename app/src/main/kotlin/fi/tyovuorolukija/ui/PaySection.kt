package fi.tyovuorolukija.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.data.PayForm
import fi.tyovuorolukija.parser.tes.ComparisonResult
import fi.tyovuorolukija.parser.tes.PayBreakdown
import fi.tyovuorolukija.parser.tes.toHoursMinutes

/**
 * Palkkaosio: ensin tarkistus (työnantajan erittely vs. TES:stä laskettu), sitten
 * palkkalaskelma. Tarkistus on ensin tarkoituksella — se on hyödyllinen ilman
 * palkkatietojakin, ja väärä tuntimäärä tekee palkkalaskelmasta merkityksettömän.
 */
@Composable
fun PaySection(
    comparison: ComparisonResult?,
    pay: PayBreakdown?,
    form: PayForm,
    printoutPartTime: Double?,
    onFormChange: (PayForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ratesOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Palkka ja tarkistus", style = MaterialTheme.typography.titleMedium)

        if (comparison != null) ComparisonCard(comparison)

        Text(
            "Palkkalaskelma",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = form.monthlySalary,
                onValueChange = { onFormChange(form.copy(monthlySalary = it)) },
                label = "Kuukausipalkka €",
                modifier = Modifier.weight(1.4f),
            )
            NumberField(
                value = form.partTimePercent,
                onValueChange = { onFormChange(form.copy(partTimePercent = it)) },
                label = "Työaika-%",
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = form.taxPercent,
                onValueChange = { onFormChange(form.copy(taxPercent = it)) },
                label = "Vero-%",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Kuukausipalkka = varsinainen palkka. Osa-aikaisella oma osa-aikapalkkasi, " +
                "ei kokoaikaisen palkkaa (23 § 3 mom).",
            style = MaterialTheme.typography.bodySmall,
        )

        // Työaikaprosentti on tuntipalkan jakajassa, joten virhe siinä siirtyy
        // suoraan lisien euroihin. Siksi ristiriita tulosteen kanssa näytetään
        // sen sijaan että toinen arvo valittaisiin hiljaa.
        if (printoutPartTime != null &&
            kotlin.math.abs(form.partTime - printoutPartTime) > 0.01
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Text(
                            "Työaikaprosentti ei täsmää",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "Tulosteessa lukee ${printoutPartTime.pct()}, asetuksissa on " +
                            "${form.partTime.pct()}. Luku on tuntipalkan jakajassa, joten " +
                            "väärä arvo vääristää kaikkia lisiä samassa suhteessa.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            onFormChange(form.copy(partTimePercent = printoutPartTime.pctPlain()))
                        },
                    ) { Text("Käytä tulosteen arvoa ${printoutPartTime.pct()}") }
                }
            }
        }

        if (form.partTimeSuspicious) {
            Text(
                "Työaikaprosentin pitää olla 1–100. Laskennassa käytetään lähintä " +
                    "kelvollista arvoa, mutta tarkista luku.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (pay != null) PayCard(pay)

        // TES-prosentit piilossa oletuksena: ne ovat oikein valmiiksi, mutta
        // sopimuskauden vaihtuessa ne pitää päästä korjaamaan.
        Row(
            Modifier.fillMaxWidth().clickable { ratesOpen = !ratesOpen }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "TES-prosentit ja vähennykset",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (ratesOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        AnimatedVisibility(ratesOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Oletukset: SOTE-sopimus 2025–2028, III luku 18 § ja 19 §. " +
                        "Yötyö on jaksotyössä 40 % (muussa työaikamuodossa 30 %). " +
                        "Jakaja 163 = jaksotyö ja yleistyöaika, 152 = toimistotyöaika (23 § 1 mom).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(form.eveningPercent,
                        { onFormChange(form.copy(eveningPercent = it)) },
                        "Ilta-%", Modifier.weight(1f))
                    NumberField(form.nightPercent,
                        { onFormChange(form.copy(nightPercent = it)) },
                        "Yö-%", Modifier.weight(1f))
                    NumberField(form.saturdayPercent,
                        { onFormChange(form.copy(saturdayPercent = it)) },
                        "Lauantai-%", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(form.sundayPercent,
                        { onFormChange(form.copy(sundayPercent = it)) },
                        "Sunnuntai-%", Modifier.weight(1f))
                    NumberField(form.divisor,
                        { onFormChange(form.copy(divisor = it)) },
                        "Jakaja", Modifier.weight(1f))
                }
                Text(
                    "Työntekijän vähennykset. Nämä muuttuvat vuosittain ja työeläkemaksu " +
                        "on korkeampi 53–62-vuotiaalla — tarkista palkkalaskelmastasi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(form.pensionPercent,
                        { onFormChange(form.copy(pensionPercent = it)) },
                        "Työeläke-%", Modifier.weight(1f))
                    NumberField(form.unemploymentPercent,
                        { onFormChange(form.copy(unemploymentPercent = it)) },
                        "Työttömyysvak.-%", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ComparisonCard(cmp: ComparisonResult) {
    val ok = cmp.allMatch && cmp.totalMatches != false
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                )
                Text(
                    if (ok) "Työnantajan erittely täsmää"
                    else "Erittelyssä on poikkeamia",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (cmp.comparedRows.isEmpty()) {
                Text(
                    "Tulosteesta ei löytynyt työnantajan tuntierittelyä, joten vertailua " +
                        "ei voi tehdä. Näkyykö kuvassa myös tulosteen alaosa?",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Row {
                Text("", Modifier.weight(1.3f))
                Cell("tuloste", 1f, bold = true)
                Cell("laskettu", 1f, bold = true)
            }
            cmp.comparedRows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${row.label}  (${row.reference})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.3f),
                    )
                    Cell(row.employerMinutes?.toHoursMinutes() ?: "–", 1f)
                    Cell(
                        row.calculatedMinutes.toHoursMinutes() + if (row.matches) "" else "  ✗",
                        1f,
                    )
                }
            }
            cmp.totalEmployerMinutes?.let { total ->
                Row {
                    Text(
                        "Yhteensä",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.3f),
                    )
                    Cell(total.toHoursMinutes(), 1f)
                    Cell(cmp.totalCalculatedMinutes.toHoursMinutes(), 1f)
                }
            }

            if (!ok) {
                Text(
                    "Laskelma perustuu tunnistettuihin vuoroihin ja SOTE-sopimuksen " +
                        "kellonaikarajoihin. Poikkeama voi johtua joko väärin tunnistetusta " +
                        "vuorosta tai työnantajan laskelmasta — tarkista ensin että yllä " +
                        "olevat vuoroajat vastaavat paperia.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PayCard(pay: PayBreakdown) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MoneyRow("Tuntipalkka", pay.hourlyRate.toPlainString() + " €/h")
            // Laskukaava näkyviin: jakaja on muuten mystinen luku, ja tästä
            // näkee heti jos työaikaprosentti on väärin.
            Text(
                "${pay.monthlySalary.toPlainString()} € ÷ (${pay.effectiveDivisor.toPlainString()}" +
                    " = jakaja × ${pay.partTimePercent.pct()})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoneyRow("Kuukausipalkka", pay.monthlySalary.toPlainString() + " €")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            pay.lines.forEach { line ->
                MoneyRow(
                    "${line.label}  ${line.minutes.toHoursMinutes()} × ${line.percent.pct()}",
                    line.amount.toPlainString() + " €",
                )
            }
            MoneyRow("Lisät yhteensä", pay.supplementsTotal.toPlainString() + " €", bold = true)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MoneyRow("Bruttopalkka", pay.gross.toPlainString() + " €", bold = true)
            MoneyRow("− eläke- ja tv-maksut", pay.contributions.toPlainString() + " €")
            pay.tax?.let { MoneyRow("− ennakonpidätys", it.toPlainString() + " €") }
            pay.net?.let {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MoneyRow("Nettopalkka", it.toPlainString() + " €", bold = true)
            } ?: Text(
                "Syötä veroprosentti nähdäksesi nettopalkan.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Suuntaa-antava. Oletus on että kaikki korvaukset maksetaan rahana. " +
                    "Jos osa on otettu vapaana (tulosteen sarake \"aika-hyvitys\"), " +
                    "brutto näkyy tässä liian suurena. Ei huomioi luontoisetuja, " +
                    "lomarahaa, kertaeriä eikä verokortin tulorajaa.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MoneyRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    weight: Float,
    bold: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.weight(weight),
    )
}

/** Jaettu numerokenttä — käytössä sekä tässä että asetusnäkymässä. */
@Composable
fun PayNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) = NumberField(value, onValueChange, label, modifier)

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun Double.pct(): String =
    if (this == toLong().toDouble()) "${toLong()} %" else "$this %"

/** Sama luku ilman prosenttimerkkiä — menee suoraan tekstikenttään. */
private fun Double.pctPlain(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()
