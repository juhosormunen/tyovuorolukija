package fi.tyovuorolukija.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.data.PayForm
import fi.tyovuorolukija.parser.tes.TesRates

private const val SOURCE_URL =
    "https://www.kt.fi/sopimukset/sote/2025-2028/tyoaika/" +
        "saannollisen-tyoajan-ylittaminen-ja-tyoaikakorvaukset"

/**
 * Lukunäkymä siitä, mihin laskenta perustuu.
 *
 * Prosentit näytetään käyttäjän **nykyisillä** asetuksilla, ja jos ne poikkeavat
 * sopimuksen oletuksesta, ero näytetään erikseen. Muuten näkymä valehtelisi
 * sen jälkeen kun asetuksia on muutettu.
 */
@Composable
fun TesScreen(form: PayForm, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val rates = form.rates
    val defaults = TesRates.SOTE_JAKSOTYO

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "SOTE-sopimus 2025–2028, III luku. Voimassa 1.5.2025–29.2.2028.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Section("Työaikakorvaukset") {
                RuleRow("Sunnuntaityö", "su, juhlapyhät, la klo 18–24",
                    rates.sundayPercent, defaults.sundayPercent, "18 § 1 mom")
                RuleRow("Lauantaityö", "arkilauantai klo 06–18",
                    rates.saturdayPercent, defaults.saturdayPercent, "18 § 2 mom")
                RuleRow("Aattokorvaus", "pääsiäisla, juhannus- ja jouluaatto klo 00–18",
                    rates.evePercent, defaults.evePercent, "18 § 3 mom")
                RuleRow("Iltatyö", "klo 18–22",
                    rates.eveningPercent, defaults.eveningPercent, "19 § 1 mom")
                RuleRow("Yötyö", "klo 22–07 · jaksotyössä 40 %, muutoin 30 %",
                    rates.nightPercent, defaults.nightPercent, "19 § 2 mom")
            }
        }

        item {
            Section("Tuntipalkka") {
                Text(
                    "Varsinainen palkka jaettuna jakajalla ${rates.monthlyDivisor}. " +
                        "163 = jaksotyö ja yleistyöaika, 152 = toimistotyöaika (23 § 1 mom).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Osa-aikaisella jakaja kerrotaan työaikaosuudella, joten " +
                        "osa-aikapalkasta saadaan sama tuntipalkka kuin kokoaikaisella " +
                        "(23 § 3 mom). Nykyinen työaikaprosentti: ${form.partTime}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Section("Sairausloma ja vuosiloma") {
                Bullet(
                    "Poissaolon ajalta maksetaan \"varsinainen palkka\": tasopalkka, " +
                        "henkilökohtainen lisä, työkokemuslisä ja vastaavat. " +
                        "Työaikakorvaukset eivät kuulu siihen (KVTES palkkausluku 5 §) — " +
                        "siksi sovellus ei laske sairaus- tai lomapäivältä ilta-, yö-, " +
                        "lauantai- eikä sunnuntaikorvausta."
                )
                Bullet(
                    "Sairausloma: varsinainen palkka 60 kalenteripäivältä, sen jälkeen " +
                        "kaksi kolmasosaa seuraavilta 120 päivältä (KVTES V luku 2 §). " +
                        "Jos palvelussuhde on kestänyt alle 60 kalenteripäivää, " +
                        "palkallinen jakso on 14 kalenteripäivää."
                )
                Bullet(
                    "Vuosiloma: varsinainen kuukausipalkka (vuosilomaluku 13 § 1 mom)."
                )
                Bullet(
                    "Molempiin tulee korotus, jota sovellus EI laske. Se on edellisen " +
                        "lomanmääräytymisvuoden (1.4.–31.3.) sunnuntai-, ilta- ja " +
                        "yötyökorvausten osuus saman vuoden varsinaisesta palkasta, " +
                        "enintään 35 % (vuosilomaluku 13 § 3 mom). Sairausajan palkassa " +
                        "huomioidaan vain sunnuntaityön osuus. Laskeminen vaatisi " +
                        "kokonaisen vuoden tiedot, joita sovelluksella ei ole."
                )
                Bullet(
                    "Lauantaityökorvaus ei ole korotuksen laskennassa mukana — " +
                        "vain sunnuntai-, ilta- ja yötyö."
                )
                Bullet(
                    "Merkinnän tekee käyttäjä itse tarkistusnäkymässä (Työvuoro / " +
                        "Sairaus / Loma). Tuloste ei kerro poissaoloja sovellukselle."
                )
            }
        }

        item {
            Section("Mitä laskenta ei kata") {
                Bullet(
                    "Raha vai vapaa. Korvaukset voi ottaa myös vapaana (yötyössä 24 min " +
                        "tunnilta). Sovellus lukee tulosteesta tehdyt tunnit, ei " +
                        "\"maksuun\"-saraketta, joten jos osa on otettu vapaana, brutto " +
                        "näkyy liian suurena."
                )
                Bullet(
                    "Lisä- ja ylityö (13 §, 16 §). Sovellus näkee tulosteesta onko niitä, " +
                        "mutta ei laske korvausta. Jaksotyössä lisätyötä syntyy vain " +
                        "osa-aikaiselle."
                )
                Bullet(
                    "Vuorotyölisä (19 § 4 mom). Se on ilta- ja yökorvauksen kanssa " +
                        "vaihtoehtoinen, ei päällekkäinen."
                )
                Bullet(
                    "Lomaraha (4–6 % heinäkuun varsinaisesta kuukausipalkasta kultakin " +
                        "täydeltä lomanmääräytymiskuukaudelta) sekä poissaoloajan palkan " +
                        "korotus — ks. edellinen osio."
                )
                Bullet(
                    "Luontoisedut, kertaerät ja verokortin tuloraja."
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Lähde", style = MaterialTheme.typography.titleSmall)
                    Text(
                        SOURCE_URL,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Sairaus- ja vuosilomamääräykset: KVTES 2025–2028, " +
                            "IV ja V luku (kt.fi).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Sopimuskausi vaihtuu 29.2.2028. Tarkista prosentit silloin " +
                            "asetuksista — sovellus ei päivitä niitä itse.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { TextButton(onClick = onBack) { Text("Takaisin") } }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun RuleRow(
    label: String,
    condition: String,
    value: Double,
    default: Double,
    reference: String,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$condition  ·  $reference",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Text(
                pct(value),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            if (value != default) {
                Text(
                    "oletus ${pct(default)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("•  ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun pct(v: Double): String =
    if (v == v.toLong().toDouble()) "${v.toLong()} %" else "$v %"
