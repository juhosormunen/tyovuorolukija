package fi.tyovuorolukija.parser.tes

import fi.tyovuorolukija.parser.Fixtures
import fi.tyovuorolukija.parser.TitaniaShiftParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LegacyPayFixTest {

    /**
     * Vanha tallennus korjattuna antaa saman tuloksen kuin uusi laskenta
     * suoraan — muuten historiaan jäisi kahdella eri tavalla laskettuja jaksoja.
     */
    @Test
    fun `korjattu vanha rivi vastaa uutta laskentaa`() {
        val parsed = TitaniaShiftParser(firstYear = 2026).parse(Fixtures.EXAMPLE_PRINTOUT)
        val input = PayInput(BigDecimal("2608.00"), partTimePercent = 80.0, taxPercent = 20.0)
        val now = PayCalculator.calculate(
            parsed.shifts, parsed.employerSummary, input, parsed.dateRange,
        )

        // Vanha laskenta: brutto = koko kuukausipalkka + lisät (ks. aiempi testi: 3450,67).
        val fixed = LegacyPayFix.fix(
            period = parsed.dateRange!!,
            monthlySalary = 260800,
            gross = 345067,
            tax = 69013,
            contributions = 26708,
            net = 249346,
        )

        fun BigDecimal.cents() = movePointRight(2).toLong()
        assertEquals(now.basePay.cents(), fixed.base)
        assertEquals(now.gross.cents(), fixed.gross)
        assertEquals(now.tax!!.cents(), fixed.tax)
        assertEquals(now.contributions.cents(), fixed.contributions)
        assertEquals(now.net!!.cents(), fixed.net)
    }
}
