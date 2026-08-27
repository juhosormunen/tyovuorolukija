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
enum class DayType(val label: String, val calendarTitle: String?) {
    WORK("Työvuoro", null),
    SICK("Sairausloma", "Sairausloma"),
    VACATION("Vuosiloma", "Vuosiloma");

    val countsAsWork: Boolean get() = this == WORK

    companion object {
        /** Tallennusmuodosta takaisin. Tuntematon arvo tulkitaan työpäiväksi. */
        fun fromStorage(value: String?): DayType =
            entries.firstOrNull { it.name == value } ?: WORK

        /** Merkittävissä olevat poissaolotyypit — [WORK] on niiden puuttuminen. */
        val absences: List<DayType> get() = listOf(SICK, VACATION)
    }
}
