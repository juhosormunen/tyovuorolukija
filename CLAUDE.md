# Työvuorolukija — projektikonteksti

Android-sovellus, joka lukee valokuvasta sairaanhoitajan työvuorolistan ja lisää
vuorot kalenteriin.

Putki:

```
CameraX / galleria
  ↓ kuva
ML Kit Text Recognition v2 (on-device, ilmainen, offline)
  ↓ Text-objekti (rivit + bounding boxit)
ShiftListRecognizer — rivien uudelleenkokoaminen + käsinkirjoitetun sarakkeen pudotus
  ↓ List<String>
TitaniaShiftParser (:parser, puhdas JVM)
  ↓ ParseResult(shifts, freeDays, warnings, ignoredLines)
ReviewScreen (Compose) — käyttäjä korjaa ja hyväksyy
  ↓
CalendarRepository → CalendarContract (+ Room-kirjanpito idempotenssia ja undoa varten)
```

Rinnalla kulkee palkkahaara:

```
ParseResult.shifts ──→ SupplementHours (TES:n kellonaikarajat + juhlapyhät)
                          ↓ omat lisätunnit
ParseResult.employerSummary ──→ PayCalculator.compare()  → poikkeamat näkyviin
                          ↓
                       PayCalculator.calculate() → brutto / netto
```

## Lähdeaineisto

Tuloste on **Titania / Monetra** -työvuorosuunnittelujärjestelmästä
(`https://pirha-titania.monetra.fi/titania/faces/summarylistprintpage.xhtml`),
Pirkanmaan hyvinvointialue (Pirha).

Teksti on **monospace-konekirjoitusta**, mikä on OCR:lle helppoa. Oikeassa reunassa
oli esimerkkikuvassa **käsinkirjoitettu sarake** ("Hoito", lapsen hoitoajat) — se ei
kuulu dataan ja suodatetaan pois.

Litteroidut tulosteet ovat `parser/src/test/kotlin/.../Fixtures.kt`:ssä
(tunnistetiedot korvattu, repo on julkinen):

| Vakio | Jakso | Tulos |
|---|---|---|
| `EXAMPLE_PRINTOUT` | 24.08.–13.09.2026 | 10 vuoroa, 9 vapaapäivää, 0 varoitusta |
| `REAL_OCR` | sama, raaka ML Kit -tuloste | OCR-virheiden korjaus |
| `OCTOBER_PRINTOUT` | 05.10.–25.10.2026 | 10 vuoroa (06.10 koottu kolmesta osasta), 10 vapaapäivää, 1 varoitus (koodi R) |

Molemmat jaksot täsmäävät työnantajan erittelyyn minuutilleen.

## Formaatin ansat

Kaikki nämä on ratkaistu ja katettu testeillä. Muista ne, jos formaatti muuttuu:

1. **Yövuoro on jaettu kahdelle riville.** `24.08 y 2100-2400` + `25.08 0000-0712`
   = **yksi** vuoro.
2. **Jatkoriveillä ei ole päiväystä.** Päiväyksetön rivi kuuluu edelliseen
   päiväysriviin. Huom: 25.08:n kohdalla on ensin edellisen yön *häntä* (0000-0712)
   ja vasta sitten uuden yön *alku* (2100-2400) omalla rivillään.
3. **Päiväyksessä ei ole vuotta.** Vuosi päätellään viitepäivästä
   (`TitaniaShiftParser.inferYear`) ja kasvatetaan kun kuukausi pienenee (12 → 01).
4. **`2400` = seuraavan vuorokauden 00:00**, ei virhe.
5. **Kaksi saraketta**: suunnitelma ja toteutunut. Luetaan toteutunut, ja jos ne
   eroavat → `Confidence.REVIEW`. Tulevaisuuden jaksoissa on vain suunnitelma.
   Jos rivillä on yli 2 aikaväliä, sarakesuodatus on vuotanut → varoitus.
6. **`V` = vapaapäivä**, ei kellonaikaa. Vapaapäiviä ei kirjoiteta kalenteriin,
   mutta ne määrittävät jakson päivävälin (ks. idempotenssi).
7. **Vuorojen luku loppuu riviin `tunnit yhteensä`.** Yhteenvedon luvut (91:48,
   114:45, 22:05…) näyttävät kellonajoilta ja sotkisivat vuoroparserin. Loppuosa
   luetaan kuitenkin erikseen `EmployerSummary`-rakenteeksi — se on palkkatarkistuksen
   perusta. Riveillä on useita sarakkeita (tämän jakson tunnit, siirrot, aika-hyvitys,
   maksuun); otetaan **ensimmäinen** luku eli tämän jakson tunnit.
8. **Käsinkirjoitettu sarake oikealla** suodatetaan bounding boxien x-koordinaatilla.
   Raja etsitään otsikon "selite" oikeasta reunasta; varasuunnitelmana levein rivi
   jossa on nelinumeroinen aikaväli. Käsiala ("8-16") ei matchaa parserin regexiä
   vaikka pääsisi läpi — tämäkin on testattu.
9. **Päivä voi koostua peräkkäisistä osista eri koodeilla.** `R 1100-1200`,
   `K 1200-1430`, `R 1430-2130` on **yksi** työpäivä (keskellä koulutus) ja siitä
   tulee yksi kalenteritapahtuma. Sääntö: kun osa alkaa täsmälleen siitä mihin
   edellinen päättyi, ne yhdistetään (`mergeAdjacent`). Pääkoodi on se, jolla on
   pisin yhteiskesto; muut menevät `Shift.extraCodes`iin ja näkyvät otsikossa
   (`Vuoro (R) + Koulutus (K)`). Käyttäjä nimenomaan halusi tämän: aiempi versio
   teki kolme tapahtumaa.
10. **Päiväyksen numerotkin voivat olla kirjaimia.** `e7.10 ke` = 07.10. Ilman
    korjausta rivi ei tunnistunut päiväysriviksi, vaan liittyi edelliseen päivään
    jatkorivinä, ja vuoro siirtyi hiljaa päivää aiemmaksi. Päiväyksessä e/o → 0 ja
    l/I → 1; turvallista, koska viikonpäivä tarkistaa tuloksen.

### Rakenteelliset tarkistukset

Yksittäiset OCR-korjaukset (kohdat 8 ja 10, `repairOcrDigits`) kattavat vain jo
nähdyt virheet. Siksi parseri tarkistaa lisäksi tulosteen **rakenteen**, joka ei
riipu siitä miten OCR rivin rikkoi. Rikkomus ei korjaa mitään, mutta merkitsee
vuorot tarkistettaviksi ja kertoo syyn:

| Invariantti | Mitä paljastaa |
|---|---|
| Jokaisella jakson päivällä on oma päiväysrivinsä | Lukukelvoton päiväys → vuoro liitetty edelliseen päivään |
| Vuorot eivät mene päällekkäin | Väärään päivään liitetty rivi, väärin luettu kellonaika |
| Päiväys täsmää viikonpäivään | Väärin luettu päivä tai kuukausi |
| Omat tunnit täsmäävät työnantajan erittelyyn | Väärä kellonaika, puuttuva vuoro |

Uutta virhetyyppiä korjattaessa kannattaa ensin kysyä, olisiko jokin invariantti
paljastanut sen. Jos ei, lisää invariantti; täsmäkorjaus on toissijainen.

## Arkkitehtuuripäätökset

- **ML Kit, ei pilvi-OCR/LLM.** Teksti on monospacea → helppo tapaus. On-device
  tarkoittaa että työvuorotiedot eivät lähde laitteelta mihinkään. Ei API-avaimia,
  ei kustannuksia, toimii offline.
- **CalendarContract, ei Google Calendar API.** Ei OAuthia eikä Google Cloud
  -projektia. Käyttäjä valitsee kalenterin; jos se on Google-tili, tapahtumat
  synkkaavat itsestään. Luvat: `WRITE_CALENDAR` + `READ_CALENDAR`.
- **Parseri on puhdasta JVM-Kotlinia** omassa `:parser`-moduulissaan → unit-testattavissa
  ilman emulaattoria. **Pidä tämä erillisenä** — älä tuo Android-riippuvuuksia sinne.
  Myös aikavyöhykemuunnos (`ShiftTimes`) on siellä, jotta DST-tapaukset saa testattua.
- **Ei koskaan suoraa kirjoitusta kalenteriin.** Aina esikatselu. Väärä vuoro
  kalenterissa on pahempi kuin puuttuva vuoro.
- **Idempotenssi Roomilla.** `SyncedShift` mäppää (kalenteri, päivä, alkuaika) →
  kalenteritapahtuman ID. Uudelleenskannaus päivittää ja poistaa, ei duplikoi.
  Poisto rajataan jakson päivävälille, jotta muiden jaksojen tapahtumat säilyvät.
  (Tapahtuman omaan `SYNC_DATA`-kenttään ei voi kirjoittaa ilman sync adapter
  -oikeuksia — siksi paikallinen kanta.)
- **Aikavyöhyke `Europe/Helsinki` eksplisiittisesti.** DST muuttaa yövuoron todellista
  kestoa: 24.–25.10.2026 yövuoro on 11 h 12 min vaikka kellonajoista laskien 10 h 12 min.
- **Undo on journaali, ei "poista mitä lisäsit".** `SyncBatch` + `SyncAction` tallentavat
  jokaisen muutoksen ja sitä edeltäneen tilan. Uudelleenskannaus myös *poistaa* vuoroja,
  joten kumouksen pitää osata luoda ne takaisin. Prev-arvot luetaan kalenterista
  muutoshetkellä, ei omasta kirjanpidosta — käyttäjä on voinut muokata tapahtumaa itse.
- **Palkkalaskenta perustuu työnantajan omiin tunteihin, tarkistus omiin.** Maksettava
  palkka lasketaan `EmployerSummary`n tunneista (se on mitä oikeasti maksetaan), mutta
  samat tunnit lasketaan myös itsenäisesti vuoroista TES:n sääntöjen mukaan ja verrataan.
  Poikkeama tarkoittaa että joko vuoro on tunnistettu väärin tai työnantajan laskelma
  heittää — kumpikin on tietämisen arvoista.

## Työehtosopimus

Lähde: **SOTE-sopimus 2025–2028** (voimassa 1.5.2025–29.2.2028), III luku.
<https://www.kt.fi/sopimukset/sote/2025-2028/tyoaika/saannollisen-tyoajan-ylittaminen-ja-tyoaikakorvaukset>

| Korvaus | Ehto | Määrä | Pykälä |
|---|---|---|---|
| Sunnuntaityö | su, juhlapyhät, la klo 18–24, juhlapyhän aatto klo 18–24 | 100 % | 18 § 1 mom |
| Lauantaityö | arkilauantai klo 06–18 | 20 % | 18 § 2 mom |
| Aattokorvaus | pääsiäislauantai, juhannusaatto, jouluaatto klo 00–18 | 100 % | 18 § 3 mom |
| Iltatyö | klo 18–22 | 15 % | 19 § 1 mom |
| Yötyö | klo 22–07 | 30 %, **jaksotyössä 40 %** | 19 § 2 mom |
| Tuntipalkka | varsinainen palkka ÷ jakaja | 163 (jaksotyö/yleistyöaika), 152 (toimistotyöaika) | 23 § 1 mom |
| Tuntipalkka, osa-aika | osa-aikapalkka ÷ (jakaja × työaikaosuus) | | 23 § 3 mom |

Huomioitavaa:

- **Lajit eivät ole toisensa poissulkevia.** Lauantai klo 18–22 on yhtä aikaa iltatyötä
  (19 §) ja sunnuntaityötä (18 §), ja molemmat maksetaan. Tämä on varmistettu
  työnantajan omaa erittelyä vasten.
- **Juhannus- ja pyhäinpäivä osuvat aina lauantaille** eivätkä siksi ole "arkilauantaita" —
  niiltä maksetaan sunnuntaikorvaus koko vuorokaudelta. Sama koskee pääsiäislauantaita ja
  lauantaiksi sattuvaa jouluaattoa, jotka sopimus rajaa erikseen ulos.
- Ainoa poissulkeva sääntö: **vuorotyölisää ja ilta-/yötyökorvausta ei makseta
  samanaikaisesti** (19 § soveltamisohje). Vuorotyölisää ei ole toteutettu.
- **Lisä- ja ylityö on toteuttamatta.** Tulosteesta näkee onko niitä (`tunnit yhteensä`
  vs. `lisätyöraja` / `ylityöraja`, ks. `EmployerSummary.hasAdditionalWork`), mutta
  korvausta ei lasketa. Esimerkkijaksossa tunnit == lisätyöraja, joten kumpaakaan ei ollut.
- **Raha vai vapaa — tunnettu puute.** Kaikki 18 §:n ja 19 §:n korvaukset voi ottaa
  rahana **tai vapaana** (yötyössä 24 min/tunti). Tulosteessa on tätä varten sarakkeet
  "aika-hyvitys" ja "maksuun", mutta `parseSummary` lukee vain ensimmäisen sarakkeen
  (tehdyt tunnit). Esimerkkitulosteessa sarakkeet olivat samat, joten virhe ei näy.
  Jaksossa jossa osa korvauksista siirtyy vapaaksi, **brutto näkyy liian suurena**.
  Korjaus vaatii sarakkeiden erottelun x-koordinaatin perusteella, kuten
  käsinkirjoitetun sarakkeen suodatuksessa.

### Poissaolot (sairausloma, vuosiloma)

Lähde: **KVTES 2025–2028**, IV luku (vuosiloma) ja V luku (virka-/työvapaa).
SOTE-sopimuksessa ei ole näitä lukuja lainkaan.

**Merkintä tehdään aina jälkikäteen, ei skannauksen yhteydessä.** Tämä on koko
toteutuksen muoto: loma-ajalle ei suunnitella vuoroja, joten lomapäiviä ei ole
missään jaksossa — ja sairausloman saa tietää vasta kun jakso on jo skannattu.
Skannaushetkellä merkitseminen ei siis toimisi kummassakaan tapauksessa.

Kaksi reittiä:

| Reitti | Käyttö |
|---|---|
| Historian kalenteriruudukon napautus | Yksittäinen päivä, tyypillisesti sairausloma |
| Aloitusnäkymä → Poissaolot (`AbsenceScreen`) | Aikaväli, myös päivät joille ei ole vuoroa |

Tuloste ei kerro poissaoloja — Titania näyttää suunnitellun vuoron.

- **Varsinainen palkka** (palkkausluku 5 §) = tasopalkka/tasolisä, henkilökohtainen lisä,
  työkokemus-/määrävuosilisä, syrjäseutu-, kieli- ja rekrytointilisä sekä
  luottamusedustajan korvaus. **Työaikakorvaukset eivät kuulu siihen.** Tästä seuraa
  koko toteutus: poissaolopäivä ei kerrytä ilta-, yö-, lauantai- eikä sunnuntaituntia
  (`Review.workedShifts` suodattaa ne pois ennen `SupplementHours`ia ja `PayCalculator`ia).
- **Sairausloma** (V luku 2 §): varsinainen palkka 60 kalenteripäivältä, sitten 2/3
  seuraavilta 120:ltä, harkinnanvaraisesti 2/3 enintään 185 päivään. Alle 60 päivän
  palvelussuhteessa palkallinen jakso on 14 kalenteripäivää.
- **Vuosiloma** (13 § 1 mom): varsinainen kuukausipalkka.
- **Korotusprosentti, jota sovellus ei laske** (13 § 3 mom): edellisen
  lomanmääräytymisvuoden (1.4.–31.3.) **sunnuntai-, ilta- ja yötyön** rahakorvausten
  osuus saman vuoden varsinaisesta palkasta, **enintään 35 %**. Sairausajan palkassa
  huomioidaan vain sunnuntaityön osuus. **Lauantaityökorvaus ei ole listalla.**
  Laskeminen vaatisi kokonaisen lomanmääräytymisvuoden tiedot; sovellus näkee vain
  skannatut jaksot, joten se kertoo puutteesta eikä arvaa.
- **Lomarahaa** (6 / 5 / 4 % heinäkuun varsinaisesta kuukausipalkasta täydeltä
  lomanmääräytymiskuukaudelta) ei lasketa.

#### Miten merkintä on toteutettu

`AbsenceDay` on **oma taulunsa eikä `ScannedDay`n kenttä**, koska poissaolo ei aina osu
skannattuun vuoroon. Se on päivätason kerros jaksojen päällä: avain on päivä, ja
merkinnän puuttuminen tarkoittaa työpäivää (`DayType.WORK` ei koskaan tallennu).

Merkintä tekee kolme asiaa yhdessä (`MainViewModel.applyAbsence`), jotta ne eivät voi
joutua eri tahtiin:

1. **Kanta.** `absence_days`-rivi.
2. **Kalenteri.** Jos päivälle on vuoro, **se jää kalenteriin sellaisenaan** —
   kellonajat, kesto ja vuorotyyppi säilyvät — ja otsikkoon lisätään vain etuliite:
   `SAIRAS · Yö (y)`. Otsikon korvaaminen kokonaan hävittäisi tiedon siitä mikä vuoro
   päivälle oli suunniteltu, ja juuri sitä tarvitaan merkintää purettaessa ja palkkaa
   tarkistettaessa. Alkuperäinen otetaan talteen (`AbsenceDay.prevTitle`) **kalenterista
   luettuna**, ei omasta kirjanpidosta, koska käyttäjä on voinut nimetä tapahtuman itse.
   `DayType.stripPrefix` estää etuliitteiden kasautumisen kun laji vaihdetaan.
   Jos vuoroa ei ole, luodaan koko päivän tapahtuma ja `eventCreated` merkitään, jotta
   purku poistaa sen eikä yritä palauttaa otsikkoa jota ei ollut.
3. **Uudelleenlaskenta.** `HistoryRepository.recompute` laskee kosketettujen jaksojen
   luvut uudestaan ilman poissaolopäiviä. Ilman tätä merkintä näkyisi tilastossa muttei
   palkassa.

Uudelleenlaskenta vaatii vuoron kellonajat, joten `scanned_days` sai skeemaversiossa 5
kentät `startMillis` / `endMillis`. Vanhoille riveille ne **täytetään takautuvasti**
migraatiossa `synced_shifts`-taulusta — juuri vanhoihin jaksoihin merkintöjä tehdään.
Jos aikoja ei löydy, sovellus kertoo montako päivää jäi laskematta eikä vaikene siitä.

Uudelleenlaskenta ei käytä työnantajan erittelyä (`EmployerSummary()` tyhjänä), koska
erittely koskee suunniteltua jaksoa eikä poissaolon jälkeistä todellisuutta. Vain
koskettuja jaksoja lasketaan uudestaan; muihin ei kosketa, jottei laskutavan vaihto
siirtäisi vanhoja lukuja ilman syytä.

Uudelleenskannaus säilyttää merkinnät: `save()` lisää voimassa olevien poissaolojen
otsikot (`Shift.titleOverride`) ennen kalenterikirjoitusta ja ajaa `recompute`n
`record()`:n jälkeen. Ilman tätä skannaus palauttaisi vuorotyypin otsikoksi ja
poissaolo katoaisi huomaamatta.

**Merkintä muuttaa tarkistuksen tuloksen.** Työnantajan erittely sisältää poissaolopäivät
omalla logiikallaan, joten poikkeama on odotettu — se on tieto, ei vika. Siksi
`employerMatched` nollataan jaksoilta joissa on poissaoloja.

### Peruspalkka jaksolta

Jakso on kolme viikkoa, ei kuukausi. Bruttoon lasketaan siksi peruspalkasta vain
jakson osuus (`PayCalculator.periodBasePay`): jokaiselta kalenteripäivältä
kuukausipalkka ÷ **sen kuukauden** kalenteripäivät (kalenteripäiväpalkka, kuten
vajaan kuukauden palkassa). Kuun vaihteen ylittävässä jaksossa elokuun päivät
jaetaan 31:llä ja syyskuun 30:llä. Tuntipalkka lasketaan yhä koko kuukausipalkasta
(23 §).

Ennen versiota 0.19 bruttoon lisättiin koko kuukausipalkka. Skeemaversion 6
migraatio korjaa vanhat historiarivit (`LegacyPayFix`, testattu). Sarake on yhä
nimeltään `monthlySalaryCents`, mutta kenttä on `ScannedPeriod.basePayCents`.

### Validointi

`PayCalculatorTest` ajaa esimerkkitulosteen läpi ja vaatii että itsenäisesti lasketut
tunnit **täsmäävät minuutilleen** työnantajan erittelyyn:

| | Tuloste | Laskettu |
|---|---|---|
| sunnuntaityö | 22:05 | 22:05 |
| iltatyö | 17:40 | 17:40 |
| yötyö | 36:00 | 36:00 |
| lauantaityö | 15:00 | 15:00 |
| yhteensä | 91:48 | 91:48 |

Tämä on samalla vahvin olemassa oleva testi vuoroparserille: jos vuoroaika olisi väärin,
tunnit eivät täsmäisi.

## Vielä ratkaisematta

- [x] **Vuorokoodien merkitykset — selvitetty.** `A` aamu, `I` ilta, `Y` yö,
      `V` vapaa, `E` pitkä vuoro (aamusta iltaan), `K` koulutus (selitteestä).
      **`U` ei ole vuorotyyppi** vaan sisäinen merkintä siitä mitä vuoron aikana
      tehdään; käsin tehdyissä kalenterimerkinnöissä nimellä "U-päivä".
      Kirjainkoolla ei ole merkitystä eikä siitä varoiteta. `R` ja `D` ovat
      yhä auki (käyttäjäkään ei tiedä). Tuntemattomasta koodista tulee **yksi
      varoitus per koodi**, mutta vuoroa ei merkitä tarkistettavaksi (punaiseksi):
      kellonajat luetaan koodista riippumatta, ja aiheettomat punaiset merkinnät
      opettavat ohittamaan oikeat.
- [ ] **Testiaineisto.** Kerää 5–10 valokuvaa eri jaksoista. Litteroi jokainen
      `Fixtures.kt`:iin ja kirjoita sille testi. Formaatti todennäköisesti vaihtelee
      enemmän kuin uskoisi. Kuvat kansioon `kuvat-testi/` (gitignoroitu).
- [ ] **Monisivuiset tulosteet** (esimerkki oli `1/1`).
- [ ] **Vinossa/huonossa valaistuksessa otetut kuvat** — tarvitaanko perspektiivikorjaus?
      Kokeile ensin ilman: ML Kit sietää kohtuullisesti.
- [ ] **Vapaapäivät kalenteriin** valinnaisena (koko päivän tapahtumina).
- [ ] Sarakesuodatuksen manuaalinen säätö käyttöliittymästä, jos automatiikka pettää.
- [ ] **Lisä- ja ylityökorvaukset** (13 §, 16 §). Jaksotyössä lisätyötä syntyy vain
      osa-aikaiselle. Ylityö: 50 % ensimmäisiltä (12/18/24 tuntia jakson pituuden mukaan),
      sitten 100 %.
- [ ] **Vuorotyölisä** (19 § 4 mom) — sulkee pois ilta-/yökorvauksen, joten se pitää
      toteuttaa vaihtoehtona eikä lisänä.
- [ ] **Poissaoloajan palkan korotus** (vuosilomaluku 13 § 3 mom, enintään 35 %) ja
      **lomaraha**. Molemmat vaativat lomanmääräytymisvuoden kertymän. Kun historiaa on
      vuosi, `YearSummary` voisi periaatteessa antaa osoittajan (sunnuntai + ilta + yö)
      ja nimittäjän (varsinainen palkka) — mutta vain skannatuista jaksoista, joten luku
      olisi liian pieni jos jaksoja puuttuu. Vaatii vähintään varoituksen kattavuudesta.
- [ ] **Työntekijän vähennysprosentit** (työeläke, työttömyysvakuutus) ovat oletuksia ja
      muuttuvat vuosittain; työeläkemaksu on korkeampi 53–62-vuotiaalla. Ne ovat
      muokattavissa asetuksista, mutta automaattinen päivitys puuttuu.
- [ ] **Sopimuskauden vaihtuminen.** Oletusprosentit on sidottu SOTE-sopimukseen
      2025–2028. Kun kausi vaihtuu, päivitä `TesRates`-oletukset ja tämän tiedoston
      taulukko.

## Kehitystiedon säilytys

Keskusteluhistoria Claude Coden kanssa **ei säily** (vanhat istunnot poistuvat noin
kuukaudessa). Kaikki mitä myöhemmin tarvitaan kirjataan näihin:

| Mihin | Mitä |
|---|---|
| Tämä tiedosto (`CLAUDE.md`) | Päätökset perusteluineen, formaatin ansat, avoimet asiat. Luetaan jokaisen istunnon alussa. |
| Git-commitit (GitHub) | Mitä muuttui ja miksi, versio viestissä. |
| `Fixtures.kt` + testit | Jokainen uusi tuloste litteroituna ja testattuna. |
| Claude Coden muisti (`~/.claude/projects/.../memory/`) | Vain tämän koneen asiat (työkalut, verkko), ei projektitietoa. |

Debug-aineisto (valokuvat, kuvakaappaukset) on kansiossa
`D:\Dropbox\Apps\Työvuorolukija debug\<päivä>` — **ei** repossa, koska kuvissa on
nimiä ja henkilötunnuksia.

## Kehitysympäristö

Työkalut on asennettu käsin kansioon `C:\Users\juhos\tools` (ei järjestelmänlaajuisesti):

| | |
|---|---|
| JDK | `C:\Users\juhos\tools\jdk-17` (Temurin 17) |
| Gradle | wrapper hoitaa; erillinen `C:\Users\juhos\tools\gradle-8.11.1` |
| Android SDK | `C:\Users\juhos\tools\android-sdk` (API 35, build-tools 35.0.0) |

`local.properties` osoittaa SDK:hon; se on gitignoroitu.

Käännös ja testit (Git Bash):

```bash
export JAVA_HOME=/c/Users/juhos/tools/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :parser:test          # parserin unit-testit, nopea
./gradlew :app:assembleRelease  # allekirjoitettu APK jakoon
```

**Käännöshakemisto on projektin ulkopuolella.** Dropbox pitää käännöksen
väliaikaistiedostoja auki ja Gradle kaatuu satunnaisesti virheeseen
"Could not delete …" — kansion merkitseminen synkronoinnista ohitettavaksi ei
riittänyt. Polku tulee `~/.gradle/gradle.properties`-tiedoston asetuksesta:

```properties
buildDirRoot=D:/build/Tyovuorolukija
```

Sen **pitää olla samalla asemalla kuin lähdekoodi** — KSP kaatuu virheeseen
"this and base files have different roots" jos build-hakemisto on eri asemalla.
Ilman asetusta käännös menee normaaliin `build/`-hakemistoon.

Käännöksen tulos: `D:/build/Tyovuorolukija/app/outputs/apk/release/app-release.apk`.

## Jakelu

Sovellusta jaetaan APK-tiedostona, ei kaupan kautta. Jaettava kopio on
`jakelu/Tyovuorolukija-<versio>.apk` (Dropboxissa, synkronoituu; `*.apk` on
gitignoroitu).

**Nosta `versionCode` ja `versionName` jokaisella jaettavalla käännöksellä**
(`app/build.gradle.kts`). versionCode ratkaisee päivittyykö sovellus laitteella,
versionName näkyy käyttäjälle aloitusnäkymässä ja Tietoa sovelluksesta -näkymässä.
Ilman näkyvää versiota kukaan ei tiedä mikä käännös kenelläkin on.

**Julkaisu GitHubiin.** Käyttäjät asentavat ja päivittävät linkistä
`releases/latest/download/Tyovuorolukija.apk` (README), joten jokainen jaettava
versio pitää julkaista GitHub Releaseksi: tagi `v<versio>`, liitteenä APK
**nimellä `Tyovuorolukija.apk`** (ilman versionumeroa, muuten pysyvä linkki
hajoaa). Pelkkä `git push` ei riitä — koodi päivittyy, mutta käyttäjät näkevät
edellisen julkaisun. Sovelluksen "Tarkista päivitykset" -painike avaa
`releases/latest`-sivun selaimessa (sovelluksella ei ole verkkolupaa).

Allekirjoitus luetaan `keystore.properties`-tiedostosta (gitignoroitu). Avain on
`keystore/tyovuorolukija.jks` projektin juuressa — siis Dropboxissa, jolloin se
varmuuskopioituu itsestään. Kansio on gitignoroitu, joten avain ei päädy
versionhallintaan. Polku ratkaistaan `rootProject.file()`:llä; pelkkä `file()`
osoittaisi `app`-moduuliin.

**Jos avain katoaa, päivityksiä ei voi enää julkaista.** Android hyväksyy
päivityksen vain samalla avaimella allekirjoitettuna; uusi avain tarkoittaisi, että
jokaisen vastaanottajan pitäisi poistaa sovellus ja menettää historiansa.

Avain oli aluksi Dropboxin ulkopuolella ajatuksella "allekirjoitusavain ei kuulu
pilveen". Se oli väärä painotus: salasana on joka tapauksessa
`keystore.properties`-tiedostossa projektin juuressa eli Dropboxissa, joten
erillään pitäminen ei suojannut miltään — se vain jätti avaimen ilman
varmuuskopiota. Jos repo joskus viedään julkiseen versionhallintaan, tarkista että
`keystore/` ja `keystore.properties` pysyvät gitignoressa.

Sama avain tarkoittaa, että uuden APK:n voi asentaa vanhan päälle: data säilyy
eikä poistoa tarvita.

## Moduulit

- `:parser` — puhdas JVM-Kotlin, ei Android-riippuvuuksia.
  - `TitaniaShiftParser`, `ShiftCodes`, `ShiftTimes`
  - `tes/` — `TesRates`, `FinnishHolidays`, `SupplementHours`, `PayCalculator`
  - `stats/` — `ShiftRhythm` (kuormituksen tunnusluvut)
  - Testit: 70 kpl, kaikki läpi.
- `:app` — Compose-käyttöliittymä, CameraX, ML Kit, Room, CalendarContract,
  `PaySettingsStore` (SharedPreferences), `HistoryRepository`, graafit
  (`ui/charts/`).

### Näkymät

Tilakone on `UiState` (`ui/MainViewModel.kt`); kaikki navigointi kulkee sen kautta.
Aloitusnäkymää lukuun ottamatta jokaisesta pääsee takaisin palkin nuolesta ja
laitteen takaisin-eleellä (`BackHandler`).

| Tila | Näkymä | Sisältö |
|---|---|---|
| `Home` | `HomeScreen` | Valikko + kumoa-painike + versio + infopainike palkissa |
| `Scanning` | `CaptureScreen` | CameraX tai galleriavalinta |
| `Review` | `ReviewScreen` | Vuorojen tarkistus, `PaySection` (vertailu + palkka) |
| `History` | `HistoryScreen` | Kalenteriruudukko, palkkakertymä, graafit, taulukko, jaksojen poisto |
| `Settings` | `SettingsScreen` | Palkka, TES-prosentit, vähennykset |
| `Tes` | `TesScreen` | Mihin laskenta perustuu, pykälineen |
| `Cleanup` | `CleanupScreen` | Sovelluksen luomien tapahtumien poisto aikaväliltä |
| `Absence` | `AbsenceScreen` | Sairaus- ja lomamerkinnät aikaväliltä, myös vuorottomille päiville |

Historian ja kalenterin poistot on **kytketty ristiin valintaruudulla** molempiin
suuntiin, mutta ne ovat silti eri asioita: historia on tilastokirjanpitoa,
kalenteri on kalenteri. Oletuksena poisto koskee vain sitä mistä se aloitettiin,
ja vahvistusteksti muuttuu valinnan mukaan. Historian poisto ei koskaan koske
`synced_shifts`-mäppäykseen, joten idempotenssi säilyy.
| `About` | `AboutScreen` | Versio, tietosuoja, luvat, rajoitukset |

### Room-skeema

| Versio | Muutos |
|---|---|
| 1 | `synced_shifts` — idempotenssi |
| 2 | `sync_batches`, `sync_actions` — undo-journaali |
| 3 | `scanned_periods` — jaksohistoria |
| 4 | `scanned_days` — päiväkohtainen data kalenterinäkymään; `taxCents`, `contributionsCents` |
| 5 | `absence_days` — sairaus- ja lomamerkinnät; `scanned_days.startMillis`/`endMillis` (täytetään takautuvasti `synced_shifts`ista) |
| 6 | Ei skeemamuutosta: vanhojen jaksojen peruspalkka, brutto, vero, maksut ja netto korjataan jakson osuudeksi (`LegacyPayFix`). **Ei vielä testattu laitteella.** |

Migraatiot on kirjoitettu käsin (`exportSchema = false`) ja **testattu oikealla
laitteella**, ei vain kääntämällä: Room validoi skeeman kannan avautuessa, joten
väärä SQL kaataisi sovelluksen ensimmäisellä tietokantakutsulla.

### Visualisoinnit

Väripaletti on validoitu `validate_palette.js`-työkalulla sovelluksen omia
pintavärejä vasten (M3 `#FEF7FF` / `#141218`), molemmat teemat, kaikki tarkistukset
läpi. Käytössä on vain **kaksi** kategorista väriä; useampi sarja jaetaan erillisiksi
graafeiksi sen sijaan että keksittäisiin lisää värejä.

Tärkeä sääntö: **työaikakorvausten lajeja ei saa pinota samaan pylvääseen.** Sama
tunti voi olla yhtä aikaa ilta- ja sunnuntaityötä, joten pinottu pylväs väittäisi
kokonaisuudesta jotain mikä ei pidä paikkaansa. Ne piirretään erillisinä graafeina.
Palkassa pinoaminen on oikein (peruspalkka ja lisät eivät mene päällekkäin).
