package fi.tyovuorolukija.parser

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/*
 * TitaniaShiftParser
 * ------------------
 * Puhdas JVM/Kotlin-parseri (ei Android-riippuvuuksia -> helppo unit-testata),
 * joka muuntaa Titania/Monetra-työvuorolistan OCR-tekstin kalenteritapahtumiksi.
 *
 * Sijoitus putkessa:
 *   CameraX -> ML Kit Text Recognition -> sarakesuodatus -> [TitaniaShiftParser]
 *   -> vahvistusnäkymä -> CalendarContract
 *
 * Formaatin erikoisuudet joita tämä käsittelee:
 *   - päiväyksessä ei ole vuotta (24.08) -> vuosi päätellään, vaihtuu kun kk pienenee
 *   - yövuoro on jaettu kahdelle riville: "y 2100-2400" ja seuraavana päivänä "0000-0712"
 *   - jatkoriveillä ei ole päiväystä lainkaan -> kuuluvat edelliseen päiväysriviin
 *   - 2400 tarkoittaa vuorokauden vaihtumista (= seuraavan päivän 00:00)
 *   - rivillä on sekä suunnitelma että toteutunut -> käytetään toteutunutta,
 *     ja jos ne eroavat, tapahtuma merkitään tarkistettavaksi
 *   - V = vapaapäivä
 *   - yhteenveto-osio ("tunnit yhteensä" ->) jätetään huomiotta
 */

enum class Confidence { OK, REVIEW }

data class Shift(
    val code: String?,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val confidence: Confidence,
    val source: String,
) {
    val title: String get() = ShiftCodes.title(code)
    val date: LocalDate get() = start.toLocalDate()

    /** Kesto minuutteina paikallisessa ajassa (ei huomioi DST:tä — ks. ShiftTimes.kt). */
    val localMinutes: Long get() = ChronoUnit.MINUTES.between(start, end)
}

data class FreeDay(val date: LocalDate, val source: String)

/**
 * Tulosteen loppuosan yhteenveto: työnantajan oma laskelma jakson tunneista.
 *
 * Nämä ovat Titanian itsensä luokittelemat tunnit — järjestelmä on jo tehnyt
 * vaikean osan (arkipyhät, rajatapaukset). Sovellus laskee samat luvut itsenäisesti
 * TES:n säännöistä ja vertaa, jolloin virheet kummassa tahansa tulevat näkyviin.
 *
 * Kaikki arvot ovat minuutteja, ja null tarkoittaa ettei riviä löytynyt kuvasta.
 *
 * **Rajoitus, joka vaikuttaa palkkalaskelmaan.** Yhteenvetorivillä on useita sarakkeita:
 * "tämän jakson tunnit", "siirto edell. jaksolta", "siirto seuraav. jaksolle",
 * "aika-hyvitys" ja "maksuun". Tässä luetaan **ensimmäinen** eli tehdyt tunnit.
 * Palkanlaskennan kannalta oikea sarake olisi "maksuun", koska osa korvauksista voidaan
 * ottaa vapaana rahan sijaan (19 § 2 mom: yötyöstä 24 min/tunti vapaata, tai 40 % rahaa).
 * Esimerkkitulosteessa sarakkeet olivat samat, joten ero ei näy — mutta jaksossa jossa
 * osa tunneista siirtyy aika-hyvitykseen, laskelma näyttää liian suurta bruttoa.
 * Sarakkeiden erottelu vaatii bounding boxeihin perustuvan sarakejaon; ks. CLAUDE.md.
 */
data class EmployerSummary(
    val totalMinutes: Long? = null,
    val periodMinutes: Long? = null,
    val additionalWorkLimit: Long? = null,
    val overtimeLimit: Long? = null,
    val sunday: Long? = null,
    val evening: Long? = null,
    val night: Long? = null,
    val saturday: Long? = null,
) {
    val hasSupplements: Boolean
        get() = listOfNotNull(sunday, evening, night, saturday).isNotEmpty()

    /** Tunnit ylittävät lisätyörajan → jaksossa on lisätyötä. */
    val hasAdditionalWork: Boolean
        get() = totalMinutes != null && additionalWorkLimit != null &&
            totalMinutes > additionalWorkLimit

    /** Tunnit ylittävät ylityörajan → jaksossa on ylityötä. */
    val hasOvertime: Boolean
        get() = totalMinutes != null && overtimeLimit != null && totalMinutes > overtimeLimit
}

data class ParseResult(
    val shifts: List<Shift>,
    val freeDays: List<FreeDay>,
    val warnings: List<String>,
    /** Taulukon sisällä olleet rivit joita ei tunnistettu — hyödyllinen debuggaukseen. */
    val ignoredLines: List<String> = emptyList(),
    val employerSummary: EmployerSummary = EmployerSummary(),
    /** Tulosteen ylälaidan "työaikaprosentti", esim. 80.0. Null jos ei löytynyt. */
    val partTimePercent: Double? = null,
) {
    val needsReview: Boolean get() = shifts.any { it.confidence == Confidence.REVIEW }

    /** Skannatun jakson päivävälit, tyhjä jos mitään ei tunnistettu. */
    val dateRange: ClosedRange<LocalDate>?
        get() {
            val dates = shifts.map { it.date } + freeDays.map { it.date }
            val min = dates.minOrNull() ?: return null
            return min..dates.max()
        }
}

private val DATE_RE =
    Regex("""^\s*(\d{1,2})\.(\d{1,2})\.?\s+(ma|ti|ke|to|pe|la|su)\b""", RegexOption.IGNORE_CASE)

/** Valinnainen vuorokoodi + kellonaikaväli, esim. "y 2100-2400" tai "0000-0712". */
private val TIME_RE =
    Regex("""(?:\b([A-Za-zÄÖäö])\s+)?(\d{2})[.:]?(\d{2})\s*[-–—]\s*(\d{2})[.:]?(\d{2})""")

private val TABLE_START_RE = Regex("""suunnitelma""", RegexOption.IGNORE_CASE)
private val TABLE_END_RE =
    Regex("""^\s*(tunnit\s+yhteens|jakson\s+tunnit|suunnitteluraja|lisätyöraja|ylityöraja)""",
        RegexOption.IGNORE_CASE)
private val FREE_RE = Regex("""(^|\s)[Vv](\s|$)|vapaap""")

/**
 * Kellonaikaa muistuttava tunnus, jossa voi olla OCR:n sekoittamia merkkejä.
 * Käytetään vain korjaukseen — varsinainen tunnistus tekee [TIME_RE].
 */
private val TIME_TOKEN_RE = Regex("""[0-9eEoO]{3,4}\s*[-–—]\s*[0-9eEoO]{3,4}""")

/**
 * Kolminumeroinen luku rivillä, jolta ei saatu kellonaikaa. Käsinkirjoitettu
 * sarake ("8-16") ei täytä tätä, mutta epäonnistunut aikarivi ("700-1330") täyttää.
 */
private val LOOKS_LIKE_TIME_RE = Regex("""\d{3}""")

/**
 * Korjaa OCR:n tyypilliset numerosekaannukset **vain kellonaikojen sisällä**.
 *
 * Havaittu oikeasta valokuvasta: `e000-e712` (= 0000-0712) ja `U e700-1330`
 * (= U 0700-1330). Nolla luetaan e:nä tai o:na. Näiden takia kokonaisia vuoroja
 * jäi tunnistumatta — 13 h 42 min yhdestä jaksosta.
 *
 * Korjaus rajataan tunnuksiin, joissa on viiva ja vähintään kaksi oikeaa numeroa.
 * Koko rivin korjaaminen olisi vaarallista: `E` ja `I` ovat oikeita vuorokoodeja,
 * eikä niitä saa muuttaa nolliksi tai ykkösiksi.
 *
 * Alkuperäinen rivi säilyy `source`-kentässä, joten käyttäjä näkee mitä kuvassa luki.
 */
internal fun repairOcrDigits(line: String): String =
    TIME_TOKEN_RE.replace(line) { match ->
        val token = match.value
        if (token.count { it.isDigit() } < 2) token
        else token.map { if (it in "eEoO") '0' else it }.joinToString("")
    }

/** Yhteenvedon tuntimäärä, esim. "91:48" tai "114:45". */
private val SUMMARY_TIME_RE = Regex("""(\d{1,3})[.:](\d{2})""")

/** Otsikkotietojen "työaikaprosentti: 80,00". */
private val PART_TIME_RE =
    Regex("""työaikaprosentti\s*:?\s*(\d{1,3})(?:[,.](\d{1,2}))?""", RegexOption.IGNORE_CASE)

private data class Entry(
    val date: LocalDate,
    val code: String?,
    val start: LocalTime,
    val endHour: Int,      // 24 = seuraavan vrk 00:00
    val endMinute: Int,
    val confidence: Confidence,
    val source: String,
)

/**
 * @param firstYear tulosteen ensimmäisen päivän vuosi. Jos null, päätellään
 *   [referenceDate]:n perusteella (lähin osuma ±1 vuoden sisällä).
 * @param referenceDate "tämä päivä" vuoden päättelyä varten.
 */
class TitaniaShiftParser(
    private val firstYear: Int? = null,
    private val referenceDate: LocalDate = LocalDate.now(),
) {

    fun parse(ocrLines: List<String>): ParseResult {
        val warnings = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        val entries = mutableListOf<Entry>()
        val freeDays = mutableListOf<FreeDay>()

        var inTable = false
        var inSummary = false
        var partTimePercent: Double? = null
        val summaryLines = mutableListOf<String>()
        var currentDate: LocalDate? = null
        var year: Int? = firstYear
        var prevMonth = -1

        for (raw in ocrLines) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue

            if (inSummary) {
                summaryLines += line
                continue
            }
            if (!inTable) {
                // Otsikkotiedot ennen taulukkoa: työaikaprosentti tarvitaan
                // osa-aikaisen tuntipalkan laskentaan (23 § 3 mom).
                if (partTimePercent == null) {
                    PART_TIME_RE.find(line)?.let { m ->
                        val whole = m.groupValues[1]
                        val frac = m.groupValues[2].ifBlank { "0" }
                        partTimePercent = "$whole.$frac".toDoubleOrNull()
                    }
                }
                if (TABLE_START_RE.containsMatchIn(line)) inTable = true
                continue
            }
            // Yhteenveto-osio alkaa: vuoroja ei enää lueta, mutta työnantajan
            // oma laskelma otetaan talteen vertailua varten.
            if (TABLE_END_RE.containsMatchIn(line)) {
                inSummary = true
                summaryLines += line
                continue
            }
            if (line.trim().all { it == '-' || it == '=' || it == '_' || it == '—' }) continue

            val dateMatch = DATE_RE.find(line)
            var rest = line

            if (dateMatch != null) {
                val day = dateMatch.groupValues[1].toInt()
                val month = dateMatch.groupValues[2].toInt()
                if (year == null) year = inferYear(day, month, referenceDate)
                if (prevMonth != -1 && month < prevMonth) year = year!! + 1
                prevMonth = month
                currentDate = runCatching { LocalDate.of(year!!, month, day) }.getOrElse {
                    warnings += "Virheellinen päiväys rivillä: $line"
                    null
                }
                rest = line.removeRange(dateMatch.range)
            }

            val date = currentDate ?: run {
                warnings += "Rivi ilman päiväystä ennen ensimmäistä päivää: $line"
                null
            } ?: continue

            // Tunnistus tehdään korjatusta tekstistä, mutta käyttäjälle näytetään
            // alkuperäinen rivi.
            val repaired = repairOcrDigits(rest)
            val times = TIME_RE.findAll(repaired).toList()

            if (times.isEmpty()) {
                if (FREE_RE.containsMatchIn(rest)) {
                    if (freeDays.none { it.date == date }) freeDays += FreeDay(date, line)
                } else {
                    ignored += line
                    // Rivi, jossa on kolminumeroinen luku mutta ei tunnistettua
                    // kellonaikaa, on lähes varmasti epäonnistunut aikarivi eikä
                    // käsinkirjoitetun sarakkeen roskaa. Se on syytä sanoa ääneen:
                    // hiljaa kadonnut vuoro on pahin mahdollinen virhe.
                    if (LOOKS_LIKE_TIME_RE.containsMatchIn(rest)) {
                        warnings += "Riviltä ei saatu luettua kellonaikaa, " +
                            "vuoro voi puuttua: $line"
                    }
                }
                continue
            }

            // Sarakkeet: suunnitelma (vasen) ja toteutunut (oikea).
            // Toteutunut voittaa. Jos osumia on yli kaksi, oikean reunan suodatus on
            // todennäköisesti päästänyt käsinkirjoitetun sarakkeen läpi.
            val chosen = when {
                times.size == 1 -> times[0]
                times.size == 2 -> times[1]
                else -> {
                    warnings += "Rivillä ${times.size} aikaväliä (odotettu 1–2), " +
                        "tarkista sarakesuodatus: $line"
                    times[1]
                }
            }
            val differs = times.size >= 2 && normalize(times[0].value) != normalize(times[1].value)
            if (differs) {
                warnings += "Suunniteltu ja toteutunut poikkeavat: $line"
            }

            val code = chosen.groupValues[1].ifBlank { null }
            val sh = chosen.groupValues[2].toInt()
            val sm = chosen.groupValues[3].toInt()
            val eh = chosen.groupValues[4].toInt()
            val em = chosen.groupValues[5].toInt()

            if (sh > 24 || eh > 24 || sm > 59 || em > 59 || (sh == 24 && sm > 0)) {
                warnings += "Epäkelpo kellonaika, tarkista: $line"
                continue
            }

            entries += Entry(
                date = date,
                code = code,
                start = LocalTime.of(sh % 24, sm),
                endHour = eh,
                endMinute = em,
                confidence = if (differs) Confidence.REVIEW else Confidence.OK,
                source = line,
            )
        }

        if (!inTable) {
            warnings += "Taulukon otsikkoriviä (\"suunnitelma\") ei löytynyt — " +
                "onko kuvassa koko tuloste?"
        }

        // Järjestetään ajan mukaan ennen yhdistämistä. ML Kit ei takaa rivien
        // järjestystä: jos päivän kaksi riviä ("0000-0712" ja "y 2100-2400")
        // tulevat väärin päin, yövuoron jatko jäisi löytymättä ja päätyisi
        // erilliseksi vuoroksi. Tämä on havaittu oikealla valokuvalla.
        val ordered = entries.sortedWith(compareBy({ it.date }, { it.start }))
        val shifts = mergeNightShifts(ordered, warnings)
        return ParseResult(
            shifts, freeDays, warnings, ignored, parseSummary(summaryLines), partTimePercent,
        )
    }

    /**
     * Lukee tulosteen loppuosan yhteenvedon. Riveillä on tyypillisesti useampi
     * sarake (tämän jakson tunnit, siirrot, aika-hyvitys, maksuun) — otetaan
     * ensimmäinen, joka on tämän jakson tuntimäärä.
     */
    private fun parseSummary(lines: List<String>): EmployerSummary {
        var summary = EmployerSummary()

        for (line in lines) {
            val lower = line.lowercase()
            val minutes = SUMMARY_TIME_RE.find(line)?.let {
                it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()
            } ?: continue

            summary = when {
                // Tarkemmat osumat ensin: "lisätyöraja" ja "ylityöraja" sisältävät "työ".
                lower.contains("lisätyöraja") || lower.contains("lisatyoraja") ->
                    summary.copy(additionalWorkLimit = summary.additionalWorkLimit ?: minutes)
                lower.contains("ylityöraja") || lower.contains("ylityoraja") ->
                    summary.copy(overtimeLimit = summary.overtimeLimit ?: minutes)
                lower.contains("sunnuntaityö") || lower.contains("sunnuntaityo") ->
                    summary.copy(sunday = summary.sunday ?: minutes)
                lower.contains("lauantaityö") || lower.contains("lauantaityo") ->
                    summary.copy(saturday = summary.saturday ?: minutes)
                lower.contains("iltatyö") || lower.contains("iltatyo") ->
                    summary.copy(evening = summary.evening ?: minutes)
                lower.contains("yötyö") || lower.contains("yotyo") ->
                    summary.copy(night = summary.night ?: minutes)
                lower.contains("jakson tunnit") ->
                    summary.copy(periodMinutes = summary.periodMinutes ?: minutes)
                lower.contains("tunnit yhteens") ->
                    summary.copy(totalMinutes = summary.totalMinutes ?: minutes)
                else -> summary
            }
        }
        return summary
    }

    /** Yhdistää "2100-2400" + seuraavan päivän "0000-0712" yhdeksi vuoroksi. */
    private fun mergeNightShifts(entries: List<Entry>, warnings: MutableList<String>): List<Shift> {
        val out = mutableListOf<Shift>()
        var pending: Entry? = null

        /** Kirjoittaa vuoron ulos ilman jatkoriviä. */
        fun flush(e: Entry) {
            if (e.endHour == 24) {
                // Yövuoro jonka jatkoa ei näy tulosteessa (esim. jakson viimeinen päivä).
                // Loppuaikaa ei tiedetä -> katkaistaan keskiyöhön ja pyydetään tarkistus.
                warnings += "Yövuoron jatkoa ei löytynyt seuraavalta päivältä, " +
                    "loppuaika arvattu keskiyöksi: ${e.source}"
                out += Shift(
                    code = e.code,
                    start = e.date.atTime(e.start),
                    end = e.date.plusDays(1).atStartOfDay(),
                    confidence = Confidence.REVIEW,
                    source = e.source,
                )
                return
            }
            var end = e.date.atTime(e.endHour, e.endMinute)
            if (!end.isAfter(e.date.atTime(e.start))) end = end.plusDays(1) // varmuuden vuoksi
            out += Shift(e.code, e.date.atTime(e.start), end, e.confidence, e.source)
        }

        for (e in entries) {
            val isContinuation = e.start == LocalTime.MIDNIGHT
            val p = pending

            if (isContinuation && p != null && p.date.plusDays(1) == e.date) {
                out += Shift(
                    code = p.code ?: e.code,
                    start = p.date.atTime(p.start),
                    end = e.date.atTime(e.endHour % 24, e.endMinute),
                    confidence = if (p.confidence == Confidence.OK && e.confidence == Confidence.OK)
                        Confidence.OK else Confidence.REVIEW,
                    source = p.source + " | " + e.source,
                )
                pending = null
                continue
            }

            if (p != null) {
                flush(p)
                pending = null
            }

            when {
                isContinuation -> {
                    warnings += "Klo 00:00 alkava jakso ilman edeltävää yövuoroa: ${e.source}"
                    out += Shift(
                        e.code,
                        e.date.atTime(e.start),
                        e.date.atTime(e.endHour % 24, e.endMinute),
                        Confidence.REVIEW,
                        e.source,
                    )
                }
                e.endHour == 24 -> pending = e // odota mahdollista jatkoa seuraavalta päivältä
                else -> flush(e)
            }
        }
        pending?.let { flush(it) }
        return out.sortedBy { it.start }
    }

    private fun normalize(timeMatch: String) = timeMatch.replace(Regex("""\s+"""), "")

    companion object {
        /**
         * Päättelee vuoden päivälle jossa sitä ei ole. Valitsee vuoden [referenceDate] ±1
         * väliltä siten että päivä osuu lähimmäksi viitepäivää.
         */
        fun inferYear(day: Int, month: Int, referenceDate: LocalDate): Int =
            listOf(referenceDate.year - 1, referenceDate.year, referenceDate.year + 1)
                .minBy { y ->
                    val safeDay = minOf(day, YearMonth.of(y, month).lengthOfMonth())
                    abs(ChronoUnit.DAYS.between(referenceDate, LocalDate.of(y, month, safeDay)))
                }
    }
}
