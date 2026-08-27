package fi.tyovuorolukija.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.data.DayType
import java.time.format.DateTimeFormatter

private val LIST_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

/**
 * Poissaolojen merkintä aikaväliltä.
 *
 * Oma näkymä eikä skannauksen osa, koska kumpikaan poissaolo ei ole tiedossa silloin
 * kun työvuorolista luetaan: loma-ajalle ei suunnitella vuoroja lainkaan, ja
 * sairausloman saa tietää vasta jälkikäteen. Merkintä on siis aina jälkikäteistä
 * korjausta jo tallennettuun jaksoon — tai päiviin joita ei ole missään jaksossa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbsenceScreen(
    state: UiState.Absence,
    onChange: ((UiState.Absence) -> UiState.Absence) -> Unit,
    onApply: (clear: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var calendarMenuOpen by remember { mutableStateOf(false) }
    val selected = state.calendars.firstOrNull { it.id == state.selectedCalendarId }
    val rangeValid = !state.fromError && !state.toError

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Merkitse sairausloma tai vuosiloma. Poissaolopäivistä ei lasketa " +
                    "ilta-, yö-, lauantai- eikä sunnuntaikorvausta, ja jaksojen luvut " +
                    "päivittyvät heti.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(
                    value = state.from,
                    onValueChange = { v -> onChange { it.copy(from = v) } },
                    label = "Alkaen",
                    isError = state.fromError,
                    modifier = Modifier.weight(1f),
                )
                DateField(
                    value = state.to,
                    onValueChange = { v -> onChange { it.copy(to = v) } },
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Poissaolon laji", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayType.absences.forEach { type ->
                        FilterChip(
                            selected = state.type == type,
                            onClick = { onChange { it.copy(type = type) } },
                            label = { Text(type.label) },
                        )
                    }
                }
                Text(
                    when (state.type) {
                        DayType.SICK ->
                            "Varsinainen palkka 60 kalenteripäivältä, sen jälkeen 2/3 " +
                                "seuraavilta 120 päivältä (KVTES V luku 2 §)."
                        else ->
                            "Varsinainen kuukausipalkka (KVTES vuosilomaluku 13 § 1 mom)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.writeToCalendar,
                        onCheckedChange = { v -> onChange { it.copy(writeToCalendar = v) } },
                    )
                    Text("Merkitse myös kalenteriin")
                }
                Text(
                    "Päivän vuoro nimetään uudelleen. Jos päivälle ei ole vuoroa — " +
                        "kuten lomalla yleensä — luodaan koko päivän tapahtuma.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.writeToCalendar) {
            item {
                ExposedDropdownMenuBox(
                    expanded = calendarMenuOpen,
                    onExpandedChange = { calendarMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = selected?.label ?: "Valitse kalenteri",
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
                                onClick = {
                                    onChange { it.copy(selectedCalendarId = cal.id) }
                                    calendarMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Nykyiset merkinnät välillä: kertoo mitä "poista merkinnät" koskisi ja
        // paljastaa jo tehdyn merkinnän ennen kuin sen tekee toiseen kertaan.
        if (state.existing.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Välillä on jo ${state.existing.size} merkintää",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        state.existing.take(12).forEach { day ->
                            Text(
                                "${day.localDate.format(LIST_FORMAT)} — ${day.dayType.label}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.existing.size > 12) {
                            Text(
                                "… ja ${state.existing.size - 12} muuta",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onApply(false) },
                    enabled = rangeValid && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (state.dayCount > 1) "Merkitse ${state.dayCount} päivää"
                        else "Merkitse"
                    )
                }
                OutlinedButton(
                    onClick = { onApply(true) },
                    enabled = rangeValid && !state.busy && state.existing.isNotEmpty(),
                ) { Text("Poista merkinnät") }
            }
            if (state.busy) {
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text("Käsitellään…", Modifier.padding(start = 8.dp))
                }
            }
        }

        state.message?.let { message ->
            item {
                Card {
                    Text(
                        message,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            Text(
                "Sovellus ei laske poissaoloajan palkan korotusta (vuosilomaluku " +
                    "13 § 3 mom, enintään 35 %) eikä lomarahaa. Ne perustuvat koko " +
                    "lomanmääräytymisvuoden kertymään, jota sovellus ei näe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { TextButton(onClick = onBack) { Text("Takaisin") } }
    }
}
