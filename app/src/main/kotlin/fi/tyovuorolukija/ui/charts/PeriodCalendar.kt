package fi.tyovuorolukija.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.data.DayType
import fi.tyovuorolukija.data.ScannedDay
import fi.tyovuorolukija.ui.theme.ShiftColors
import java.time.DayOfWeek
import java.time.LocalDate

private val WEEKDAYS = listOf("ma", "ti", "ke", "to", "pe", "la", "su")

/**
 * Jakson kalenteriruudukko: yksi ruutu per päivä, viikot riveinä maanantaista
 * sunnuntaihin.
 *
 * Tämä on paikka jossa vuorovärit tekevät oikeasti työtä — vuororytmin näkee
 * yhdellä silmäyksellä tavalla johon pylväsgraafi ei pysty. Väri ei kuitenkaan
 * kanna merkitystä yksin: jokaisessa ruudussa on vuorokoodi kirjaimena, ja
 * alla on selite.
 */
@Composable
fun PeriodCalendar(
    days: List<ScannedDay>,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    modifier: Modifier = Modifier,
    /** Poissaolomerkinnät päivämäärän mukaan (ISO-avain). */
    absences: Map<String, DayType> = emptyMap(),
    /** Ruudun napautus. Null tekee ruudukosta pelkän kuvan. */
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    if (days.isEmpty()) return

    val byDate = days.associateBy { LocalDate.parse(it.date) }

    // Täytetään alkuun ja loppuun tyhjät ruudut, jotta viikot alkavat maanantaista.
    val gridStart = rangeStart.minusDays(
        ((rangeStart.dayOfWeek.value - DayOfWeek.MONDAY.value) + 7) % 7L
    )
    val gridEnd = rangeEnd.plusDays(
        ((DayOfWeek.SUNDAY.value - rangeEnd.dayOfWeek.value) + 7) % 7L
    )

    val weeks = mutableListOf<List<LocalDate>>()
    var cursor = gridStart
    while (!cursor.isAfter(gridEnd)) {
        weeks += (0L..6L).map { cursor.plusDays(it) }
        cursor = cursor.plusDays(7)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        weeks.forEach { week ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { date ->
                    val inRange = !date.isBefore(rangeStart) && !date.isAfter(rangeEnd)
                    DayCell(
                        date = date,
                        day = byDate[date],
                        inRange = inRange,
                        absence = absences[date.toString()],
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (onDayClick != null && inRange) {
                                    Modifier.clickable { onDayClick(date) }
                                } else Modifier
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    day: ScannedDay?,
    inRange: Boolean,
    absence: DayType? = null,
    modifier: Modifier = Modifier,
) {
    // Poissaolopäivä ei ole vuoro eikä vapaapäivä: vuoro oli suunniteltu mutta jäi
    // tekemättä. Vaaleampi harmaa erottaa sen vapaapäivästä ilman uutta kategorista väriä.
    val background = when {
        absence != null -> ShiftColors.Free.copy(alpha = 0.22f)
        day == null -> Color.Transparent
        day.isFree -> ShiftColors.Free.copy(alpha = 0.45f)
        else -> ShiftColors.forCode(day.code)
    }

    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .then(
                if (day == null && absence == null && inRange) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(6.dp),
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!inRange) return@Box

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (day == null && absence == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else CellInk,
            )
            // Kirjaimena: väri ei saa olla ainoa tunniste. Poissaolo peittää
            // vuorokoodin, koska se on se mikä päivästä lopulta tuli.
            val label = when (absence) {
                DayType.SICK -> "S"
                DayType.VACATION -> "L"
                else -> day?.code
            }
            label?.let {
                Text(
                    it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = CellInk,
                )
            }
        }
    }
}

/** Tumma muste vaaleilla vuoroväreillä — sama sävy kaikissa, jotta ruudukko on rauhallinen. */
private val CellInk = Color(0xFF16302A)

/** Selite kalenterille. Näytetään aina — väri yksin ei kerro mitään. */
@Composable
fun ShiftLegend(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShiftColors.legend.forEach { (label, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
