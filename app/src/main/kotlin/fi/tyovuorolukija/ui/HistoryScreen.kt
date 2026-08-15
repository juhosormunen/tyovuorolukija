package fi.tyovuorolukija.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.data.ScannedPeriod
import fi.tyovuorolukija.data.YearSummary
import fi.tyovuorolukija.data.centsToEuros
import fi.tyovuorolukija.parser.tes.toHoursMinutes
import fi.tyovuorolukija.ui.charts.Bar
import fi.tyovuorolukija.ui.charts.BarChart
import fi.tyovuorolukija.ui.charts.ChartLegend
import fi.tyovuorolukija.ui.charts.PeriodCalendar
import fi.tyovuorolukija.ui.charts.ShiftLegend
import fi.tyovuorolukija.ui.charts.VizColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SHORT_DATE = DateTimeFormatter.ofPattern("d.M.")

/**
 * Historianäkymä. Rakenne: ensin tunnusluvut, sitten graafit, lopuksi taulukko.
 *
 * Työaikakorvausten lajit piirretään **erillisinä graafeina** eikä pinottuina —
 * sama tunti voi olla sekä ilta- että sunnuntaityötä, joten pinoaminen antaisi
 * väärän kuvan kokonaisuudesta.
 */
@Composable
fun HistoryScreen(
    periods: List<ScannedPeriod>,
    years: List<YearSummary>,
    days: List<fi.tyovuorolukija.data.ScannedDay>,
    totals: fi.tyovuorolukija.data.PayTotals,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tableOpen by remember { mutableStateOf(false) }

    if (periods.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ei vielä historiaa", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Jaksot tallentuvat tänne kun tallennat vuorot kalenteriin. " +
                    "Kumottu tallennus poistuu myös historiasta.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onBack) { Text("Takaisin") }
        }
        return
    }

    val labels = periods.map {
        LocalDate.parse(it.rangeStart).format(SHORT_DATE)
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                "${periods.size} jaksoa",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { StatRow(periods) }

        item {
            ChartBlock(
                title = "Vuororytmi",
                subtitle = "Jokainen jakso päivä päivältä. Väri kertoo vuorotyypin, " +
                    "kirjain saman tiedon ilman värejä.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ShiftLegend()
                    periods.forEach { period ->
                        val periodDays = days.filter { it.periodKey == period.key }
                        if (periodDays.isEmpty()) return@forEach
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${LocalDate.parse(period.rangeStart).format(SHORT_DATE)}–" +
                                    "${LocalDate.parse(period.rangeEnd).format(SHORT_DATE)}" +
                                    "  ·  ${hours(period.totalMinutes)} h",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            PeriodCalendar(
                                days = periodDays,
                                rangeStart = LocalDate.parse(period.rangeStart),
                                rangeEnd = LocalDate.parse(period.rangeEnd),
                            )
                        }
                    }
                    if (days.isEmpty()) {
                        Text(
                            "Päiväkohtainen näkymä täyttyy seuraavista tallennuksista — " +
                                "aiemmilta jaksoilta on tallessa vain yhteenvetoluvut.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (totals.grossCents != null) {
            item { PayTotalsBlock(totals) }
        }

        // Pylväsgraafi vertaa jaksoja toisiinsa. Yhdellä jaksolla ei ole mitään
        // verrattavaa, ja yksipylväinen kaavio näyttää tyhjänpäiväiseltä —
        // silloin sama tieto on luettavampana taulukkona.
        if (periods.size < 2) {
            item { SinglePeriodBreakdown(periods.first()) }
        } else {
            item {
                ChartBlock(
                    title = "Työtunnit jaksoittain",
                    subtitle = "Kokonaistunnit kultakin jaksolta.",
                ) {
                    BarChart(
                        bars = periods.mapIndexed { i, p ->
                            Bar(labels[i], listOf(p.totalMinutes / 60f), hours(p.totalMinutes))
                        },
                        colors = listOf(VizColors.series1()),
                    )
                }
            }

            item {
                ChartBlock(
                    title = "Työaikakorvausten tunnit",
                    subtitle = "Neljä lajia erikseen — sama tunti voi kuulua useaan " +
                        "lajiin, joten niitä ei lasketa yhteen.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SupplementChart("Yötyö", labels, periods.map { it.nightMinutes })
                        SupplementChart("Iltatyö", labels, periods.map { it.eveningMinutes })
                        SupplementChart("Sunnuntaityö", labels, periods.map { it.sundayMinutes })
                        SupplementChart("Lauantaityö", labels, periods.map { it.saturdayMinutes })
                    }
                }
            }
        }

        val withPay = if (periods.size < 2) emptyList() else periods.filter { it.grossCents != null }
        if (withPay.isNotEmpty()) {
            item {
                ChartBlock(
                    title = "Arvioitu palkka jaksoittain",
                    subtitle = "Peruspalkka ja työaikakorvaukset erikseen. " +
                        "Näyttää kuinka suuri osa ansioista tulee lisistä.",
                ) {
                    val payLabels = withPay.map {
                        LocalDate.parse(it.rangeStart).format(SHORT_DATE)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartLegend(
                            listOf(
                                "Peruspalkka" to VizColors.series1(),
                                "Lisät" to VizColors.series2(),
                            )
                        )
                        BarChart(
                            bars = withPay.mapIndexed { i, p ->
                                val base = (p.monthlySalaryCents ?: 0L) / 100f
                                val extra = (p.supplementsCents ?: 0L) / 100f
                                Bar(
                                    payLabels[i],
                                    listOf(base, extra),
                                    (p.grossCents ?: 0L).centsToEuros() + " €",
                                )
                            },
                            colors = listOf(VizColors.series1(), VizColors.series2()),
                            height = 148.dp,
                        )
                    }
                }
            }
        }

        if (periods.size >= 2) {
            item {
                ChartBlock(
                    title = "Yövuorot jaksoittain",
                    subtitle = "Kuormituksen kannalta olennaisin yksittäinen luku.",
                ) {
                    BarChart(
                        bars = periods.mapIndexed { i, p ->
                            Bar(labels[i], listOf(p.nightShiftCount.toFloat()),
                                p.nightShiftCount.toString())
                        },
                        colors = listOf(VizColors.series1()),
                        height = 100.dp,
                    )
                }
            }
        }

        if (years.isNotEmpty()) {
            item { YearBlock(years) }
        }

        item {
            HorizontalDivider()
            TextButton(onClick = { tableOpen = !tableOpen }) {
                Text(if (tableOpen) "Piilota taulukko" else "Näytä kaikki lukuina")
            }
            if (tableOpen) PeriodTable(periods)
        }

        item { TextButton(onClick = onBack) { Text("Takaisin") } }
    }
}

/**
 * Kertymä koko historiasta. Hero-luku on netto, koska se on se mikä tilille tulee;
 * muut ovat sen erittelyä.
 */
@Composable
private fun PayTotalsBlock(totals: fi.tyovuorolukija.data.PayTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Palkkakertymä", style = MaterialTheme.typography.titleMedium)
        Text(
            "Yhteensä ${totals.periods} jaksolta. Arvio, ei palkkalaskelma.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                totals.netCents?.let {
                    Text(
                        "${it.centsToEuros()} €",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Nettoa yhteensä",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                totals.grossCents?.let { ValueRow("Brutto", it.centsToEuros() + " €") }
                totals.supplementsCents?.let {
                    ValueRow("josta työaikakorvauksia", it.centsToEuros() + " €")
                }
                totals.supplementShare?.let {
                    ValueRow("lisien osuus bruttosta", "%.1f %%".format(it))
                }
                totals.taxCents?.let { ValueRow("− ennakonpidätys", it.centsToEuros() + " €") }
                totals.contributionsCents?.let {
                    ValueRow("− eläke- ja tv-maksut", it.centsToEuros() + " €")
                }
            }
        }
    }
}

/**
 * Yhden jakson erittely taulukkona. Graafit ilmestyvät vasta kun on jotain
 * mihin verrata.
 */
@Composable
private fun SinglePeriodBreakdown(period: ScannedPeriod) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Jakson erittely", style = MaterialTheme.typography.titleMedium)
        Text(
            "Vertailugraafit ilmestyvät kun jaksoja on vähintään kaksi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ValueRow("Työtunnit", hours(period.totalMinutes))
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ValueRow("Yötyö (22–07)", hours(period.nightMinutes))
                ValueRow("Iltatyö (18–22)", hours(period.eveningMinutes))
                ValueRow("Sunnuntaityö", hours(period.sundayMinutes))
                ValueRow("Lauantaityö", hours(period.saturdayMinutes))
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ValueRow("Vuoroja", period.shiftCount.toString())
                ValueRow("Vapaapäiviä", period.freeDayCount.toString())
                ValueRow("Pisin työputki", "${period.longestWorkStreakDays} pv")
                period.shortestRestMinutes?.let {
                    ValueRow("Lyhin lepo vuorojen välissä", hours(it))
                }
            }
        }
    }
}

@Composable
private fun SupplementChart(title: String, labels: List<String>, minutes: List<Long>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        BarChart(
            bars = labels.mapIndexed { i, label ->
                Bar(label, listOf(minutes[i] / 60f), hours(minutes[i]))
            },
            colors = listOf(VizColors.series1()),
            height = 84.dp,
        )
    }
}

@Composable
private fun StatRow(periods: List<ScannedPeriod>) {
    val totalHours = periods.sumOf { it.totalMinutes }
    val nightShifts = periods.sumOf { it.nightShiftCount }
    val shortRests = periods.sumOf { it.shortRestCount }
    val mismatches = periods.count { it.employerMatched == false }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Tunnit yhteensä", hours(totalHours), Modifier.weight(1f))
            StatTile("Yövuoroja", nightShifts.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                "Alle 11 h lepoja", shortRests.toString(), Modifier.weight(1f),
                note = "Jaksotyössä lepo voi sopimuksen mukaan olla lyhyempi — " +
                    "luku on tiedoksi, ei rikkomus.",
            )
            StatTile(
                "Erittelyn poikkeamat", mismatches.toString(), Modifier.weight(1f),
                warn = mismatches > 0,
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    warn: Boolean = false,
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ChartBlock(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun YearBlock(years: List<YearSummary>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Vuosikertymä", style = MaterialTheme.typography.titleMedium)
        years.forEach { y ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${y.year}  ·  ${y.periods} jaksoa",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    ValueRow("Työtunnit", hours(y.totalMinutes))
                    ValueRow("Yötyö", hours(y.nightMinutes))
                    ValueRow("Iltatyö", hours(y.eveningMinutes))
                    ValueRow("Sunnuntaityö", hours(y.sundayMinutes))
                    ValueRow("Lauantaityö", hours(y.saturdayMinutes))
                    y.grossCents?.let { ValueRow("Brutto (arvio)", it.centsToEuros() + " €") }
                    y.netCents?.let { ValueRow("Netto (arvio)", it.centsToEuros() + " €") }
                }
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Taulukkonäkymä — sama data lukuina, jotta mitään ei jää vain graafin varaan. */
@Composable
private fun PeriodTable(periods: List<ScannedPeriod>) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        TableRow(
            listOf("Jakso", "Tunnit", "Yö", "Ilta", "La", "Su", "Vuoroja", "Brutto"),
            bold = true,
        )
        periods.forEach { p ->
            TableRow(
                listOf(
                    "${LocalDate.parse(p.rangeStart).format(SHORT_DATE)}–" +
                        LocalDate.parse(p.rangeEnd).format(SHORT_DATE),
                    hours(p.totalMinutes),
                    hours(p.nightMinutes),
                    hours(p.eveningMinutes),
                    hours(p.saturdayMinutes),
                    hours(p.sundayMinutes),
                    p.shiftCount.toString(),
                    p.grossCents?.centsToEuros() ?: "–",
                )
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, bold: Boolean = false) {
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.forEach { cell ->
            Text(
                cell,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

private fun hours(minutes: Long): String = minutes.toHoursMinutes()
