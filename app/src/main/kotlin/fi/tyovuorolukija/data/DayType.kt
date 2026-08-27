package fi.tyovuorolukija.data

/**
 * Päivän luonne palkanlaskennan kannalta.
 *
 * Poissaolon ajalta maksetaan "varsinainen palkka" (KVTES palkkausluku 5 §), joka
 * koostuu tasopalkasta, henkilökohtaisesta lisästä, työkokemuslisästä ja vastaavista.
 * **Työaikakorvaukset eivät kuulu siihen**, joten poissaolopäivä ei kerrytä ilta-,
 * yö-, lauantai- eikä sunnuntaituntia.
 *
 * [WORK] on oletus eikä sitä tallenneta mihinkään: poissaolo on merkintä, jonka
 * puuttuminen tarkoittaa työpäivää. Siksi vain [SICK] ja [VACATION] päätyvät
 * `absence_days`-tauluun.
 */
enum class DayType(
    val label: String,
    /**
     * Etuliite olemassa olevan vuoron otsikkoon. Vuoro **jää kalenteriin sellaisenaan**
     * kellonaikoineen ja vuorotyyppeineen — merkintä vain lisätään eteen. Alkuperäisen
     * otsikon korvaaminen hävittäisi tiedon siitä mikä vuoro päivälle oli suunniteltu,
     * ja juuri sitä tietoa tarvitaan kun merkintä puretaan tai palkkaa tarkistetaan.
     */
    val calendarPrefix: String?,
    /** Otsikko päivälle jolle ei ole vuoroa — silloin koko tapahtuma luodaan tästä. */
    val calendarTitle: String?,
) {
    WORK("Työvuoro", null, null),
    SICK("Sairausloma", "SAIRAS", "Sairausloma"),
    VACATION("Vuosiloma", "LOMA", "Vuosiloma");

    val countsAsWork: Boolean get() = this == WORK

    /** Vuoron otsikko merkittynä, esim. "SAIRAS · Yö (y)". */
    fun annotate(shiftTitle: String): String {
        val prefix = calendarPrefix ?: return stripPrefix(shiftTitle)
        return "$prefix $SEPARATOR ${stripPrefix(shiftTitle)}"
    }

    companion object {
        private const val SEPARATOR = "·"

        /** Tallennusmuodosta takaisin. Tuntematon arvo tulkitaan työpäiväksi. */
        fun fromStorage(value: String?): DayType =
            entries.firstOrNull { it.name == value } ?: WORK

        /** Merkittävissä olevat poissaolotyypit — [WORK] on niiden puuttuminen. */
        val absences: List<DayType> get() = listOf(SICK, VACATION)

        /**
         * Poistaa mahdollisen aiemman poissaoloetuliitteen. Estää kasautumisen
         * ("SAIRAS · LOMA · Yö (y)") kun merkinnän laji vaihdetaan.
         */
        fun stripPrefix(title: String): String {
            var result = title.trim()
            var changed = true
            while (changed) {
                changed = false
                for (type in absences) {
                    val prefix = "${type.calendarPrefix} $SEPARATOR "
                    if (result.startsWith(prefix)) {
                        result = result.removePrefix(prefix).trim()
                        changed = true
                    }
                }
            }
            return result
        }
    }
}
