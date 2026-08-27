package fi.tyovuorolukija.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.parser.ShiftCodes
import fi.tyovuorolukija.ui.theme.ShiftColors
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE dd.MM.")

/**
 * Vahvistusnäkymä. Mitään ei kirjoiteta kalenteriin ennen kuin käyttäjä painaa
 * tallennusnappia — väärä vuoro kalenterissa on pahempi kuin puuttuva vuoro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    state: UiState.Review,
    onRowChange: (Int, (ShiftRow) -> ShiftRow) -> Unit,
    onPayFormChange: (fi.tyovuorolukija.data.PayForm) -> Unit,
    onSelectCalendar: (Long) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var calendarMenuOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }

    val selectedCalendar = state.calendars.firstOrNull { it.id == state.selectedCalendarId }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val range = state.dateRange
                Text(
                    buildString {
                        append("${state.rows.count { it.include }} vuoroa")
                        if (state.freeDays.isNotEmpty()) {
                            append(", ${state.freeDays.size} vapaapäivää")
                        }
                        if (range != null) append("  •  ${range.start} – ${range.endInclusive}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.flaggedCount > 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Text(
                                "${state.flaggedCount} vuoroa vaatii tarkistuksen " +
                                    "(suunnitelma ja toteutunut poikkesivat, tai " +
                                    "vuorokoodia ei tunnisteta).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            items(state.rows, key = { it.key }) { row ->
                ShiftRowCard(row) { transform -> onRowChange(row.key, transform) }
            }

            if (state.freeDays.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        "Vapaapäivät (ei kirjoiteta kalenteriin): " +
                            state.freeDays.joinToString(", ") { it.date.format(DAY_FORMAT) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                PaySection(
                    comparison = state.comparison,
                    pay = state.payBreakdown,
                    form = state.payForm,
                    printoutPartTime = state.printoutPartTime,
                    onFormChange = onPayFormChange,
                    absenceCount = state.absenceCount,
                )
            }

            item {
                DebugSection(
                    state = state,
                    expanded = debugOpen,
                    onToggle = { debugOpen = !debugOpen },
                )
            }
        }

        HorizontalDivider()

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.calendars.isEmpty()) {
                Text(
                    "Kirjoitettavia kalentereita ei löytynyt. Tarkista kalenterilupa " +
                        "ja että laitteella on synkronoituva kalenteri.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = calendarMenuOpen,
                    onExpandedChange = { calendarMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = selectedCalendar?.label
                            ?: "Valitse kalenteri",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kalenteri") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(calendarMenuOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = calendarMenuOpen,
                        onDismissRequest = { calendarMenuOpen = false },
                    ) {
                        state.calendars.forEach { cal ->
                            DropdownMenuItem(
                                text = { Text(cal.label) },
                                onClick = {
                                    onSelectCalendar(cal.id)
                                    calendarMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            // Laitteen sisäiseen kalenteriin kirjoitetut tapahtumat eivät näy
            // Google Kalenterissa lainkaan. Se on niin harhaanjohtavaa — sovellus
            // ilmoittaa onnistumisesta eikä kalenterissa näy mitään — että siitä
            // varoitetaan korostetusti eikä pikkutekstillä.
            selectedCalendar?.takeIf { !it.isPreferred }?.let { cal ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Text(
                            if (cal.isLocal) {
                                "\"${cal.displayName}\" on laitteen sisäinen kalenteri. " +
                                    "Google Kalenteri ei näytä sen tapahtumia lainkaan — " +
                                    "vuorot tallentuvat, mutta jäävät näkymättömiin. " +
                                    "Valitse Google-tilin kalenteri, jos sellainen on."
                            } else {
                                "Kalenterin \"${cal.displayName}\" synkronointi on pois " +
                                    "päältä. Vuorot jäävät vain tälle laitteelle."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.hasErrors) {
                Text(
                    "Osassa riveistä on virheellinen aika (muoto pp.kk.vvvv tt:mm).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Peruuta")
                }
                Button(
                    onClick = onSave,
                    enabled = !state.saving && !state.hasErrors &&
                        state.selectedCalendarId != null && state.validShifts.isNotEmpty(),
                    modifier = Modifier.weight(2f),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("Tallennetaan…")
                    } else {
                        Text("Lisää kalenteriin (${state.validShifts.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftRowCard(row: ShiftRow, onChange: ((ShiftRow) -> ShiftRow) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (row.flagged && row.include) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row {
            // Reunapalkki vuorotyypin värillä: tekee vuororytmin näkyväksi listaa
            // selatessa. Väri on tunniste, ei tieto — otsikko kertoo saman.
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(ShiftColors.forCode(row.code.ifBlank { null }))
            )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = row.include,
                    onCheckedChange = { checked -> onChange { it.copy(include = checked) } },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (row.dayType.countsAsWork) {
                            ShiftCodes.title(row.code.ifBlank { null })
                        } else {
                            // Poissaolopäivällä vuorotyyppi on menettänyt merkityksensä:
                            // vuoro oli suunniteltu, mutta sitä ei tehty.
                            row.dayType.calendarTitle.orEmpty()
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        row.source,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Kiinteä leveys on pakollinen: OutlinedTextFieldin oletusminimi on
                // 280 dp, ja rajoittamattomana se puristaa yllä olevan otsikkosarakkeen
                // nollaan leveydeltään. Kapeammalla näytöllä tai isommalla fonttikoolla
                // otsikko ja lähderivi katosivat kokonaan.
                OutlinedTextField(
                    value = row.code,
                    onValueChange = { code -> onChange { it.copy(code = code.take(2)) } },
                    label = { Text("Koodi", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp).width(84.dp),
                )
            }

            if (row.include) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pienempi tekstityyli, jotta "24.08.2026 21:00" mahtuu puolikkaaseen
                    // leveyteen katkeamatta.
                    OutlinedTextField(
                        value = row.startText,
                        onValueChange = { v -> onChange { it.copy(startText = v) } },
                        label = { Text("Alkaa", style = MaterialTheme.typography.labelSmall) },
                        isError = row.startError,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = row.endText,
                        onValueChange = { v -> onChange { it.copy(endText = v) } },
                        label = { Text("Päättyy", style = MaterialTheme.typography.labelSmall) },
                        isError = row.endError,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Poissaolomerkintä. Päivä pysyy kalenterissa (vuoro oli olemassa),
                // mutta se ei kerrytä työaikakorvauksia — ks. DayType.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DayType.entries.forEach { type ->
                        FilterChip(
                            selected = row.dayType == type,
                            onClick = { onChange { r -> r.copy(dayType = type) } },
                            label = {
                                Text(type.label, style = MaterialTheme.typography.labelMedium)
                            },
                        )
                    }
                }
                if (!row.dayType.countsAsWork) {
                    Text(
                        "Ei ilta-, yö-, lauantai- eikä sunnuntaikorvausta. " +
                            "Peruspalkka juoksee normaalisti.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun DebugSection(state: UiState.Review, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tunnistuksen tiedot" +
                    if (state.warnings.isEmpty()) "" else " (${state.warnings.size} huomautusta)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Etätuki: kun sovellus on jonkun toisen puhelimessa, vian selvitys
                // ilman raakatekstiä on arvailua. Tällä sen saa lähetettyä eteenpäin.
                val clipboard = LocalClipboardManager.current
                var copied by remember { mutableStateOf(false) }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(state.debugReport()))
                        copied = true
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text(if (copied) "  Kopioitu leikepöydälle" else "  Kopioi tunnistustiedot")
                }
                if (state.warnings.isNotEmpty()) {
                    Text("Huomautukset:", style = MaterialTheme.typography.labelMedium)
                    state.warnings.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.ignoredLines.isNotEmpty()) {
                    Text("Ohitetut rivit:", style = MaterialTheme.typography.labelMedium)
                    state.ignoredLines.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Text("OCR-teksti sellaisenaan:", style = MaterialTheme.typography.labelMedium)
                state.rawLines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
