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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.BuildConfig
import fi.tyovuorolukija.calendar.APP_MARKER

/** Uusimman julkaisun sivu: versio, muutokset ja APK-latauslinkki. */
const val RELEASES_URL = "https://github.com/juhosormunen/tyovuorolukija/releases/latest"

/**
 * Avaa uusimman julkaisun sivun selaimessa.
 *
 * Sovelluksella ei ole verkkoyhteyslupaa, joten se ei voi itse kysyä onko uudempaa
 * versiota. Selain voi: käyttäjä vertaa sivun versiota omaansa ja lataa APK:n sieltä.
 * Mitään ei lähde sovelluksesta — selain hakee sivun kuten minkä tahansa linkin.
 */
@Composable
fun UpdateCheckButton(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    TextButton(
        // Ilman selainta openUri heittää; silloin painike ei vain tee mitään.
        onClick = { runCatching { uriHandler.openUri(RELEASES_URL) } },
        modifier = modifier,
    ) {
        Text("Tarkista päivitykset")
    }
}

/**
 * Tietoa sovelluksesta.
 *
 * Versionumero on tässä ensisijaisesti siksi, että sovellusta jaetaan APK-tiedostona
 * eikä kaupan kautta: ilman näkyvää versiota kukaan ei tiedä mikä käännös kenelläkin
 * on käytössä, ja vikailmoituksista tulee arvailua.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Työvuorolukija", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Versio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    UpdateCheckButton()
                }
            }
        }

        item {
            Section("Mitä sovellus tekee") {
                Text(
                    "Lukee työvuorolistan valokuvasta, tulkitsee vuorot, näyttää ne " +
                        "tarkistettavaksi ja vie hyväksytyt kalenteriin. Lisäksi se laskee " +
                        "työaikakorvaukset työehtosopimuksen mukaan ja vertaa niitä " +
                        "tulosteen omaan erittelyyn.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Section("Tietosuoja") {
                Bullet(
                    "Tekstintunnistus tapahtuu laitteella (ML Kit). Valokuvat, " +
                        "työvuorot ja palkkatiedot eivät lähde laitteelta mihinkään."
                )
                Bullet(
                    "Sovelluksella ei ole verkkoyhteyttä omaan palvelimeen eikä " +
                        "käyttäjätiliä."
                )
                Bullet(
                    "Kalenteritapahtumat menevät valitsemaasi kalenteriin. Jos se on " +
                        "Google-kalenteri, ne synkronoituvat sen mukana — se on Googlen " +
                        "synkronointi, ei tämän sovelluksen."
                )
                Bullet(
                    "Palkka-asetukset ja historia ovat vain tällä laitteella."
                )
                Bullet(
                    "\"Tarkista päivitykset\" avaa julkaisusivun selaimessa. Sovellus " +
                        "ei itse ota yhteyttä mihinkään eikä lähetä tietoja."
                )
            }
        }

        item {
            Section("Luvat") {
                Bullet("Kamera — työvuorolistan kuvaamiseen.")
                Bullet(
                    "Kalenterin luku ja kirjoitus — kalenterien listaamiseen, " +
                        "tapahtumien luomiseen ja siivoustoimintoon."
                )
            }
        }

        item {
            Section("Rajoitukset") {
                Bullet(
                    "Tunnistus on rakennettu Titania/Monetra-tulosteen muotoon. " +
                        "Toisenlainen tuloste ei välttämättä toimi."
                )
                Bullet(
                    "Palkkalaskelma on suuntaa-antava arvio, ei palkkalaskelma. " +
                        "Se ei kata lisä- ja ylityötä, vuorotyölisää, lomarahaa, " +
                        "luontoisetuja eikä verokortin tulorajaa."
                )
                Bullet(
                    "Oletukset ovat SOTE-sopimuksesta 2025–2028. Muulla sopimuksella " +
                        "prosentit pitää vaihtaa asetuksista."
                )
            }
        }

        item {
            Section("Kalenteritapahtumien tunniste") {
                Text(
                    "Sovellus kirjoittaa jokaisen luomansa tapahtuman kuvaukseen tekstin:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    APP_MARKER,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    "Siivoustoiminto tunnistaa tapahtumat siitä. Muihin " +
                        "kalenterimerkintöihin se ei voi koskea.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { TextButton(onClick = onBack) { Text("Takaisin") } }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("•  ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
