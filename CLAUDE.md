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

Esimerkkituloste on litteroituna `parser/src/test/kotlin/.../Fixtures.kt`:ssä
(`EXAMPLE_PRINTOUT`, jakso 24.08.–13.09., työaikaprosentti 80). Se tuottaa
**10 vuoroa ja 9 vapaapäivää**, nolla varoitusta — tämä on testattu.

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
      `V` vapaa, `E` pitkä vuoro (aamusta iltaan). **`U` ei ole vuorotyyppi**
      vaan sisäinen merkintä siitä mitä vuoron aikana tehdään; käsin tehdyissä
      kalenterimerkinnöissä nimellä "U-päivä". Kirjainkoolla ei ole merkitystä
      eikä siitä varoiteta. Tuntemattomista koodeista varoitetaan yhä —
      tulosteissa on nähty ainakin `R` ja `D`, joiden merkitys on auki.
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
- [ ] **Työntekijän vähennysprosentit** (työeläke, työttömyysvakuutus) ovat oletuksia ja
      muuttuvat vuosittain; työeläkemaksu on korkeampi 53–62-vuotiaalla. Ne ovat
      muokattavissa asetuksista, mutta automaattinen päivitys puuttuu.
- [ ] **Sopimuskauden vaihtuminen.** Oletusprosentit on sidottu SOTE-sopimukseen
      2025–2028. Kun kausi vaihtuu, päivitä `TesRates`-oletukset ja tämän tiedoston
      taulukko.

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

Allekirjoitus luetaan `keystore.properties`-tiedostosta (gitignoroitu). Avain on
`C:\Users\juhos\keystore\tyovuorolukija.jks`. **Jos avain katoaa, päivityksiä ei voi
enää julkaista** — vastaanottajien pitäisi poistaa sovellus ja menettää historiansa.
Ota siitä varmuuskopio.

Sama avain tarkoittaa, että uuden APK:n voi asentaa vanhan päälle: data säilyy
eikä poistoa tarvita.

## Moduulit

- `:parser` — puhdas JVM-Kotlin, ei Android-riippuvuuksia.
  - `TitaniaShiftParser`, `ShiftCodes`, `ShiftTimes`
  - `tes/` — `TesRates`, `FinnishHolidays`, `SupplementHours`, `PayCalculator`
  - `stats/` — `ShiftRhythm` (kuormituksen tunnusluvut)
  - Testit: 46 kpl, kaikki läpi.
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
