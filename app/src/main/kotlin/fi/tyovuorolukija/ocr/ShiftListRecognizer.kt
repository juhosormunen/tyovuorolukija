package fi.tyovuorolukija.ocr

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Tunnistetun sivun välitulos. [lines] on valmis syöte parserille;
 * [rawLines] ja [cutoffX] ovat mukana debuggausnäkymää varten.
 */
data class RecognizedPage(
    val lines: List<String>,
    val rawLines: List<String>,
    val cutoffX: Int?,
)

/**
 * ML Kit Text Recognition v2 -kääre.
 *
 * Kaksi asiaa joita ML Kitin raakatulos ei tee itse:
 *
 * 1. **Rivien uudelleenkokoaminen.** ML Kit palauttaa `Text.Line`-olioita jotka voivat
 *    katkaista yhden tulosterivin useaan palaseen (sarakevälit ovat leveitä). Parseri
 *    olettaa yhden merkkijonon per tulosterivi, joten palaset ryhmitellään pystysuunnassa
 *    päällekkäisyyden perusteella ja liitetään vasemmalta oikealle.
 *
 * 2. **Käsinkirjoitetun sarakkeen pudotus.** Esimerkkikuvassa oikeassa reunassa oli
 *    käsin kirjoitettu "Hoito"-sarake. Se ei kuulu dataan ja OCR tulkitsee käsialan
 *    epäluotettavasti. Raja etsitään ensisijaisesti otsikkorivin "selite" oikeasta
 *    reunasta; jos otsikkoa ei löydy, käytetään leveintä aikaväliriviä.
 */
class ShiftListRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(context: Context, uri: Uri): RecognizedPage {
        // fromFilePath lukee EXIF-kierron ja palauttaa bounding boxit jo oikein päin.
        val image = InputImage.fromFilePath(context, uri)
        val text = recognizer.process(image).await()
        return toPage(text)
    }

    internal fun toPage(text: Text): RecognizedPage {
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { PositionedText(line.text, it) }
        }
        if (lines.isEmpty()) return RecognizedPage(emptyList(), emptyList(), null)

        val rawRows = groupIntoRows(lines).map { row -> row.joinToString(" ") { it.text } }

        val cutoff = findCutoffX(lines)
        val kept = if (cutoff == null) lines else lines.filter { it.box.left < cutoff }
        val rows = groupIntoRows(kept).map { row -> row.joinToString("  ") { it.text } }

        return RecognizedPage(lines = rows, rawLines = rawRows, cutoffX = cutoff)
    }

    internal data class PositionedText(val text: String, val box: Rect) {
        val centerY: Int get() = box.centerY()
    }

    /**
     * Ryhmittelee palaset tulosteriveiksi: sama rivi jos palasen pystykeskikohta osuu
     * ryhmän pystysuuntaiseen ulottuvuuteen (puolen rivinkorkeuden toleranssilla).
     */
    private fun groupIntoRows(items: List<PositionedText>): List<List<PositionedText>> {
        if (items.isEmpty()) return emptyList()

        val medianHeight = items.map { it.box.height() }.sorted()[items.size / 2]
        val tolerance = (medianHeight / 2).coerceAtLeast(4)

        val sorted = items.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<PositionedText>>()
        var rowCenter = sorted.first().centerY
        var current = mutableListOf<PositionedText>()

        for (item in sorted) {
            if (current.isEmpty() || kotlin.math.abs(item.centerY - rowCenter) <= tolerance) {
                current += item
                rowCenter = current.sumOf { it.centerY } / current.size
            } else {
                rows += current
                current = mutableListOf(item)
                rowCenter = item.centerY
            }
        }
        if (current.isNotEmpty()) rows += current

        return rows.map { row -> row.sortedBy { it.box.left } }
    }

    /**
     * Raja jonka oikealle puolelle jäävä teksti pudotetaan. Null = ei suodateta.
     */
    private fun findCutoffX(items: List<PositionedText>): Int? {
        val header = items.firstOrNull { it.text.contains("selite", ignoreCase = true) }
        if (header != null) {
            // "selite" on viimeinen painettu sarake -> hieman sen oikean reunan jälkeen.
            return header.box.right + header.box.height()
        }

        // Varasuunnitelma: painetut aikavälirivit ("1400-2125") ulottuvat aina
        // "toteutunut"-sarakkeen loppuun. Käsiala ei matchaa nelinumeroista muotoa.
        val printedRight = items
            .filter { PRINTED_TIME_RE.containsMatchIn(it.text) || it.text.contains("vapaap", true) }
            .maxOfOrNull { it.box.right }
            ?: return null

        val medianHeight = items.map { it.box.height() }.sorted()[items.size / 2]
        return printedRight + medianHeight * 2
    }

    private companion object {
        val PRINTED_TIME_RE = Regex("""\d{4}\s*[-–—]\s*\d{4}""")
    }
}
