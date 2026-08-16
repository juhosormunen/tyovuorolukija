package fi.tyovuorolukija.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.calendar.FoundEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EVENT_FORMAT = DateTimeFormatter.ofPattern("EEE d.M.yyyy HH:mm")

/**
 * Kalenterin siivous: poistaa **vain tämän sovelluksen luomat** tapahtumat
 * valitulta aikaväliltä.
 *
 * Tarpeen kahdesta syystä. Ensinnäkin sovelluksen uudelleenasennus hävittää
 * paikallisen kirjanpidon, jolloin aiemmin luodut tapahtumat jäävät kalenteriin
 * "orvoiksi" ja uusi skannaus kahdentaisi ne. Toiseksi jakson voi haluta pyyhkiä
 * pois kokonaan.
 *
 * Poistettavat näytetään aina listana ennen poistoa, ja poisto vaatii vahvistuksen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    state: UiState.Cleanup,
    onRangeChange: (String, String) -> Unit,
    onSelectCalendar: (Long) -> Unit,
    onSearch: () -> Unit,
    onDelete: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var calendarMenuOpen by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    var alsoDeleteHistory by remember { mutableStateOf(false) }

    val selected = state.calendars.firstOrNull { it.id == state.selectedCalendarId }
    val zone = ZoneId.systemDefault()

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Poistetaanko ${state.found.size} tapahtumaa?") },
            text = {
                Text(
                    buildString {
                        append("Poisto koskee vain Työvuorolukijan luomia tapahtumia ")
                        append("välillä ${state.from} – ${state.to}. ")
                        append("Muut kalenterimerkinnät säilyvät. ")
                        if (alsoDeleteHistory) {
                            append("Myös näiden jaksojen tilastot poistetaan historiasta. ")
                        }
                        append("Tätä ei voi kumota.")
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmOpen = false; onDelete(alsoDeleteHistory) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Poista") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Peruuta") }
            },
        )
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Poistaa vain Työvuorolukijan luomat tapahtumat. Muut " +
                    "kalenterimerkinnät eivät voi hävitä tässä.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(
                    value = state.from,
                    onValueChange = { onRangeChange(it, state.to) },
                    label = "Alkaen",
                    isError = state.fromError,
                    modifier = Modifier.weight(1f),
                )
                DateField(
                    value = state.to,
                    onValueChange = { onRangeChange(state.from, it) },
                    label = "Päättyen",
                    isError = state.toError,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.toError && !state.fromError) {
                Text(
                    "Loppupäivä ei voi olla ennen alkupäivää.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            ExposedDropdownMenuBox(
                expanded = calendarMenuOpen,
                onExpandedChange = { calendarMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = selected?.label
                        ?: "Valitse kalenteri",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Kalenteri") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(calendarMenuOpen)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = calendarMenuOpen,
                    onDismissRequest = { calendarMenuOpen = false },
                ) {
                    state.calendars.forEach { cal ->
                        DropdownMenuItem(
                            text = { Text(cal.label) },
                            onClick = { onSelectCalendar(cal.id); calendarMenuOpen = false },
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = onSearch,
                enabled = !state.busy && !state.fromError && !state.toError &&
                    state.selectedCalendarId != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text("Haetaan…")
                } else {
                    Text("Etsi tapahtumat")
                }
            }
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (state.searched) {
            item {
                HorizontalDivider()
                Text(
                    if (state.found.isEmpty()) {
                        "Ei löytynyt yhtään Työvuorolukijan luomaa tapahtumaa tältä väliltä."
                    } else {
                        "Löytyi ${state.found.size} tapahtumaa:"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(state.found.size) { index ->
                val event: FoundEvent = state.found[index]
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            event.title.ifBlank { "(nimetön)" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            Instant.ofEpochMilli(event.startMillis)
                                .atZone(zone).format(EVENT_FORMAT),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            if (state.found.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            alsoDeleteHistory = !alsoDeleteHistory
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = alsoDeleteHistory,
                            onCheckedChange = { alsoDeleteHistory = it },
                        )
                        Text(
                            "Poista myös näiden jaksojen tilastot historiasta",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = { confirmOpen = true },
                        enabled = !state.busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Poista ${state.found.size} tapahtumaa") }
                }
            }
        }

        item { OutlinedButton(onClick = onBack) { Text("Takaisin") } }
    }
}
