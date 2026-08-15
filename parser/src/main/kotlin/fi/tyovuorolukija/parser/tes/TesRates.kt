package fi.tyovuorolukija.parser.tes

/**
 * SOTE-sopimuksen työaikakorvausten suuruudet.
 *
 * Oletukset ovat SOTE-sopimuksesta 2025–2028 (voimassa 1.5.2025–29.2.2028),
 * III luku 18 § ja 19 §. Lähde:
 * https://www.kt.fi/sopimukset/sote/2025-2028/tyoaika/saannollisen-tyoajan-ylittaminen-ja-tyoaikakorvaukset
 *
 * Kaikki arvot ovat muokattavissa käyttöliittymästä, koska sopimuskaudet vaihtuvat
 * eikä sovellus saa jäädä kiinni yhteen sopimusversioon.
 *
 * Kellonaikarajat ovat sopimuksessa kiinteät eivätkä siksi ole säädettävissä:
 * iltatyö 18–22, yötyö 22–07, lauantaityö 06–18, aattokorvaus 00–18.
 */
data class TesRates(
    /** 19 § 1 mom: iltatyö klo 18–22, 15 % korottamattomasta tuntipalkasta. */
    val eveningPercent: Double = 15.0,

    /**
     * 19 § 2 mom, sanatarkasti:
     *
     * > "Yötyöllä tarkoitetaan kello 22.00–07.00 tehtyä työtä. Yötyöstä maksetaan
     * > rahakorvauksena 30 % korottamattomasta tuntipalkasta tai annetaan vastaava
     * > vapaa-aika. Jaksotyössä vapaa-aikakorvaus on 24 minuuttia yötyötunnilta tai
     * > rahakorvaus 40 % korottamattomasta tuntipalkasta."
     *
     * Oletus 40 %, koska tuloste on jaksotyöstä. Prosentin valinta itsessään on
     * suoraviivainen: 30 % on muun työaikamuodon luku.
     *
     * **Se mikä EI ole varmaa** on kuinka moni yötyötunti ylipäätään maksetaan rahana.
     * Sopimus antaa vaihtoehdoksi vapaa-ajan (24 min/tunti), ja tulosteessa on tätä
     * varten omat sarakkeensa "aika-hyvitys" ja "maksuun". Tämä laskenta olettaa että
     * kaikki tunnit maksetaan rahana — ks. [fi.tyovuorolukija.parser.EmployerSummary].
     */
    val nightPercent: Double = 40.0,

    /** 18 § 2 mom: arkilauantai klo 06–18, 20 %. */
    val saturdayPercent: Double = 20.0,

    /**
     * 18 § 1 mom: sunnuntai, juhlapyhät ja la klo 18–24 → varsinaisen palkan
     * lisäksi korottamaton tuntipalkka, eli 100 %.
     */
    val sundayPercent: Double = 100.0,

    /** 18 § 3 mom: pääsiäislauantai, juhannusaatto, jouluaatto klo 00–18 → 100 %. */
    val evePercent: Double = 100.0,

    /**
     * 23 § 1 mom: kuukausipalkan jakaja tuntipalkkaa laskettaessa.
     * 163 = yleistyöaika (7 §) ja jaksotyö (9 §); 152 = toimistotyöaika (8 §).
     */
    val monthlyDivisor: Int = 163,
) {
    companion object {
        /** Jaksotyö, SOTE — tämän sovelluksen oletustapaus. */
        val SOTE_JAKSOTYO = TesRates()

        /** Muu kuin jaksotyö: yötyökorvaus on 30 %. */
        val SOTE_YLEISTYOAIKA = TesRates(nightPercent = 30.0)

        /** Toimistotyöaika: eri jakaja. */
        val SOTE_TOIMISTOTYOAIKA = TesRates(nightPercent = 30.0, monthlyDivisor = 152)
    }
}

/**
 * Työntekijältä perittävät maksut, jotka vähennetään bruttopalkasta ennen veroa.
 *
 * HUOM: nämä prosentit muuttuvat vuosittain ja riippuvat iästä (työeläkemaksu on
 * korkeampi 53–62-vuotiailla). Oletukset ovat suuntaa-antavia — tarkista omalta
 * palkkalaskelmaltasi ja korjaa asetuksista.
 */
data class EmployeeContributions(
    /** Työeläkemaksu (TyEL), alle 53-v. oletus. */
    val pensionPercent: Double = 7.15,
    /** Työttömyysvakuutusmaksu. */
    val unemploymentPercent: Double = 0.59,
    /** Mahdollinen ammattiliiton jäsenmaksu, oletuksena pois. */
    val unionPercent: Double = 0.0,
) {
    val totalPercent: Double get() = pensionPercent + unemploymentPercent + unionPercent
}
