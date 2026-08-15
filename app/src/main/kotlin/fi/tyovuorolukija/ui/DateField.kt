package fi.tyovuorolukija.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Päivämääräkenttä, jota napauttamalla avautuu kalenterivalitsin.
 *
 * Kenttä on vain luku -tilassa, jotta napautus ei avaa näppäimistöä valitsimen
 * sijaan. Käsin kirjoittaminen onnistuu silti: Materialin valitsimessa on oma
 * näppäimistötila, josta päivän voi näppäillä.
 *
 * Valitsin toimii UTC-milleillä, joten muunnos tehdään [ZoneOffset.UTC]:n kautta.
 * Paikallisen aikavyöhykkeen käyttö siirtäisi päivää vuorokauden rajoilla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalDate.parse(value.trim(), DATE_FORMAT) }.getOrNull()

    if (pickerOpen) {
        val initialMillis = (parsed ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC).toLocalDate()
                            onValueChange(picked.format(DATE_FORMAT))
                        }
                        pickerOpen = false
                    },
                ) { Text("Valitse") }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Peruuta") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = isError,
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                Icon(Icons.Default.CalendarMonth, contentDescription = "Valitse päivämäärä")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Läpinäkyvä pinta kentän päällä: vain luku -tilainen tekstikenttä ei
        // muuten välitä napautuksia eteenpäin.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { pickerOpen = true }
        )
    }
}
