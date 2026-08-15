# Työvuorolukija

Android-sovellus, joka lukee valokuvasta Titania/Monetra-työvuorolistan ja lisää
vuorot puhelimen kalenteriin.

- Tekstintunnistus tapahtuu **laitteella** (ML Kit) — työvuorotiedot eivät lähde mihinkään.
- Kalenteriin kirjoitetaan **vasta kun käyttäjä on tarkistanut ja hyväksynyt** vuorot.
- Saman jakson voi skannata uudestaan: tapahtumat päivittyvät, eivät duplikoidu.

## Käyttö

1. Ota kuva koko tulosteesta (tai valitse valmis kuva galleriasta).
2. Tarkista tunnistetut vuorot. Punaisella merkityt vaativat huomiota — joko
   suunnitelma ja toteutunut poikkesivat, tai vuorokoodin merkitystä ei ole varmistettu.
   Aikoja voi muokata suoraan (muoto `pp.kk.vvvv tt:mm`), ja rivin voi jättää pois.
3. Katso palkkaosio: sovellus laskee ilta-, yö-, lauantai- ja sunnuntaityötunnit
   itsenäisesti TES:n säännöistä ja vertaa niitä tulosteen omaan erittelyyn.
   Syöttämällä kuukausipalkan (ja veroprosentin) saat brutto- ja nettopalkan lisineen.
4. Valitse kalenteri ja tallenna. Tallennuksen voi kumota — painike näkyy sekä heti
   tallennuksen jälkeen että aloitusnäkymän alalaidassa.
5. Tallennetut jaksot kertyvät **historiaan** (aloitusnäkymä → "Historia ja tilastot"):
   työtunnit ja lisätunnit jaksoittain, arvioitu palkka, yövuorojen määrä, lyhimmät
   lepoajat ja vuosikertymä. Kumottu tallennus poistuu myös historiasta.

Vapaapäiviä ei kirjoiteta kalenteriin, mutta ne huomioidaan: jos päivä on aiemmin
ollut työvuoro ja muuttuu vapaaksi, tapahtuma poistetaan.

Palkkalaskelma on suuntaa-antava eikä huomioi luontoisetuja, lomarahaa, kertaeriä
eikä verokortin tulorajaa. Työaikakorvausten perusteet ja lähteet:
ks. [CLAUDE.md](CLAUDE.md#työehtosopimus).

## Kääntäminen

Vaatii JDK 17:n ja Android SDK:n (API 35). Tällä koneella ne ovat
kansiossa `C:\Users\juhos\tools`, ja `local.properties` osoittaa SDK:hon.

```bash
export JAVA_HOME=/c/Users/juhos/tools/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :parser:test         # parserin unit-testit (nopea, ei emulaattoria)
./gradlew :app:assembleDebug   # APK -> app/build/outputs/apk/debug/app-debug.apk
```

Asennus puhelimeen USB-kaapelilla:

```bash
/c/Users/juhos/tools/android-sdk/platform-tools/adb.exe install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

## Rakenne

| Moduuli | Sisältö |
|---|---|
| `:parser` | Puhdas JVM-Kotlin: `TitaniaShiftParser`, `ShiftCodes`, `ShiftTimes`. Ei Android-riippuvuuksia. |
| `:app` | Compose-käyttöliittymä, CameraX, ML Kit, Room, CalendarContract. |

Tulosteen formaatin erikoisuudet, arkkitehtuuriperustelut ja avoimet kysymykset:
ks. [CLAUDE.md](CLAUDE.md).
