package fi.tyovuorolukija.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Datavisualisoinnin väripaletti.
 *
 * Slotit 1 ja 2 validoidusta paletista. Molemmat teemat on tarkistettu ajamalla
 * `validate_palette.js` sovelluksen omia pintavärejä vasten (M3 #FEF7FF / #141218):
 * kaikki kuusi tarkistusta menivät läpi, huonoin CVD-erotus ΔE 24.7 (vaalea) ja
 * 26.8 (tumma) — selvästi yli 8:n rajan.
 *
 * Kolmatta väriä ei oteta käyttöön. Jos sarjoja tarvitaan lisää, jaetaan graafi
 * useaksi pieneksi graafiksi, joissa kussakin on yksi sarja.
 */
object VizColors {
    private val Blue = Color(0xFF2A78D6)
    private val BlueDark = Color(0xFF3987E5)
    private val Orange = Color(0xFFEB6834)
    private val OrangeDark = Color(0xFFD95926)

    @Composable fun series1(): Color = if (isSystemInDarkTheme()) BlueDark else Blue
    @Composable fun series2(): Color = if (isSystemInDarkTheme()) OrangeDark else Orange

    @Composable fun grid(): Color =
        if (isSystemInDarkTheme()) Color(0xFF2C2C2A) else Color(0xFFE1E0D9)

    @Composable fun baseline(): Color =
        if (isSystemInDarkTheme()) Color(0xFF383835) else Color(0xFFC3C2B7)
}

/** Yksi pylväs. [segments] on 1 tai 2 osaa; kaksi osaa pinotaan. */
data class Bar(
    val label: String,
    val segments: List<Float>,
    val valueLabel: String,
)

/**
 * Pylväsgraafi. Tarkoituksella riisuttu: ohuet merkit, vaimea ruudukko,
 * pyöristetty yläpää ja 2 dp:n rako pinottujen osien väliin.
 *
 * Pinoaminen on sallittua vain kun osat eivät ole päällekkäisiä (esim. peruspalkka
 * + lisät). Työaikakorvausten lajit menevät päällekkäin — sama tunti voi olla sekä
 * ilta- että sunnuntaityötä — joten niitä ei saa pinota, vaan ne piirretään
 * erillisinä graafeina.
 */
@Composable
fun BarChart(
    bars: List<Bar>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    maxValue: Float? = null,
) {
    if (bars.isEmpty()) return

    val gridColor = VizColors.grid()
    val baselineColor = VizColors.baseline()
    val top = maxValue ?: bars.maxOf { it.segments.sum() }.coerceAtLeast(1f)

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            drawGrid(gridColor, baselineColor)
            drawBars(bars, colors, top)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            bars.forEach { bar ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        bar.valueLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        bar.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(gridColor: Color, baselineColor: Color) {
    // Kolme vaimeaa apuviivaa; arvot luetaan pylvään päältä, joten akselia ei tarvita.
    for (i in 1..3) {
        val y = size.height * (1f - i / 4f)
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
    drawLine(
        baselineColor,
        Offset(0f, size.height),
        Offset(size.width, size.height),
        strokeWidth = 2f,
    )
}

private fun DrawScope.drawBars(bars: List<Bar>, colors: List<Color>, top: Float) {
    val slot = size.width / bars.size
    val barWidth = (slot * 0.52f).coerceAtMost(36.dp.toPx())
    val gap = 2.dp.toPx()
    val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())

    bars.forEachIndexed { index, bar ->
        val centerX = slot * index + slot / 2f
        var bottom = size.height

        bar.segments.forEachIndexed { segIndex, value ->
            if (value <= 0f) return@forEachIndexed
            val h = (value / top) * size.height
            val isTopSegment = segIndex == bar.segments.lastIndex ||
                bar.segments.drop(segIndex + 1).sum() <= 0f
            val segTop = bottom - h

            drawRoundRect(
                color = colors.getOrElse(segIndex) { colors.last() },
                topLeft = Offset(centerX - barWidth / 2f, segTop),
                size = Size(barWidth, h.coerceAtLeast(2f)),
                cornerRadius = if (isTopSegment) radius else CornerRadius.Zero,
            )
            // Pyöristys vain yläpäähän: peitetään alareunan kaarre suorakulmalla.
            if (isTopSegment && h > radius.y) {
                drawRect(
                    color = colors.getOrElse(segIndex) { colors.last() },
                    topLeft = Offset(centerX - barWidth / 2f, segTop + radius.y),
                    size = Size(barWidth, h - radius.y),
                )
            }
            bottom = segTop - gap
        }
    }
}

/** Selite. Näytetään aina kun sarjoja on vähintään kaksi. */
@Composable
fun ChartLegend(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { (label, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color),
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
