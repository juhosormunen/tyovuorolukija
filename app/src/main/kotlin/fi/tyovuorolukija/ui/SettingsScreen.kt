package fi.tyovuorolukija.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.data.PayForm

/**
 * Asetukset. Samat kentät ovat myös vahvistusnäkymässä, jotta palkan voi syöttää
 * siinä hetkessä kun laskelmaa katsoo — molemmat kirjoittavat samaan tallennukseen.
 */
@Composable
fun SettingsScreen(
    form: PayForm,
    onFormChange: (PayForm) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "Tallentuvat heti. Palkkatiedot pysyvät laitteella eivätkä lähde mihinkään.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Palkka", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayNumberField(form.monthlySalary,
                        { onFormChange(form.copy(monthlySalary = it)) },
                        "Kuukausipalkka €", Modifier.weight(1.4f))
                    PayNumberField(form.partTimePercent,
                        { onFormChange(form.copy(partTimePercent = it)) },
                        "Työaika-%", Modifier.weight(1f))
                    PayNumberField(form.taxPercent,
                        { onFormChange(form.copy(taxPercent = it)) },
                        "Vero-%", Modifier.weight(1f))
                }
                // Sama valinta kuin vahvistusnäkymän palkkaosiossa. Kumpi luku
                // kenttään on syötetty ei ole pääteltävissä itse luvusta, joten se
                // kysytään molemmissa paikoissa eikä selitetä ohjetekstissä.
                Text(
                    "Mikä palkka kenttään on syötetty?",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !form.salaryIsFullTime,
                        onClick = { onFormChange(form.copy(salaryIsFullTime = false)) },
                        label = { Text("Oma palkkani") },
                    )
                    FilterChip(
                        selected = form.salaryIsFullTime,
                        onClick = { onFormChange(form.copy(salaryIsFullTime = true)) },
                        label = { Text("Kokoaikaisen palkka") },
                    )
                }
                Text(
                    if (form.salaryIsFullTime) {
                        "Kokoaikaisen (100 %) kuukauden bruttoperuspalkka. Sinun " +
                            "palkkasi lasketaan siitä työaikaprosentilla."
                    } else {
                        "Sinulle maksettava kuukauden bruttoperuspalkka, ei " +
                            "kokoaikaisen palkkaa (23 § 3 mom). Se lukee " +
                            "palkkalaskelmassa."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                // Johdettu summa omalla rivillään: näin käyttäjä näkee heti mitä
                // valinta tarkoittaa euroina eikä joudu luottamaan siihen.
                if (form.salaryIsFullTime) {
                    form.effectiveMonthlySalary?.let { own ->
                        Text(
                            "→ ${form.partTime} % syötetystä = ${own.toPlainString()} € kuussa",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    "Työaikaprosentti luetaan tulosteesta automaattisesti, jos " +
                        "jätät kentän tyhjäksi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Työaikakorvaukset", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Oletukset ovat SOTE-sopimuksesta 2025–2028. Muuta näitä vain jos " +
                        "sopimuksesi poikkeaa tai sopimuskausi on vaihtunut.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayNumberField(form.eveningPercent,
                        { onFormChange(form.copy(eveningPercent = it)) },
                        "Ilta-%", Modifier.weight(1f))
                    PayNumberField(form.nightPercent,
                        { onFormChange(form.copy(nightPercent = it)) },
                        "Yö-%", Modifier.weight(1f))
                    PayNumberField(form.saturdayPercent,
                        { onFormChange(form.copy(saturdayPercent = it)) },
                        "Lauantai-%", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayNumberField(form.sundayPercent,
                        { onFormChange(form.copy(sundayPercent = it)) },
                        "Sunnuntai-%", Modifier.weight(1f))
                    PayNumberField(form.divisor,
                        { onFormChange(form.copy(divisor = it)) },
                        "Jakaja", Modifier.weight(1f))
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vähennykset", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Työntekijän maksut, jotka vähennetään bruttosta ennen veroa. " +
                        "Nämä muuttuvat vuosittain ja työeläkemaksu on korkeampi " +
                        "53–62-vuotiaalla — tarkista palkkalaskelmastasi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayNumberField(form.pensionPercent,
                        { onFormChange(form.copy(pensionPercent = it)) },
                        "Työeläke-%", Modifier.weight(1f))
                    PayNumberField(form.unemploymentPercent,
                        { onFormChange(form.copy(unemploymentPercent = it)) },
                        "Työttömyysvak.-%", Modifier.weight(1f))
                }
            }
        }

        item { TextButton(onClick = onBack) { Text("Takaisin") } }
    }
}
