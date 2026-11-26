# Kiosk TV (Merk)

Z televízora TCL s Android TV robí kiosk s menu reštaurácie. Po zapnutí TV sa hneď otvorí appka
**Merk Kiosk**, ktorá ukáže stránku menu z WordPressu (`https://merk.bannet.sk`), zapamätá si
zvolenú prevádzku a obrazovku a sama sa obnoví, keď niekto stránku vo WordPresse uloží.

## Čo je v repozitári

| Priečinok / súbor | Čo to je |
|---|---|
| `app/` | Android appka Merk Kiosk (Java, bez knižníc). Balík `sk.merk.kiosk`. |
| `tv/installer.py` | Inštalátor s oknom (v prehliadači): nájde TV na sieti, nainštaluje alebo vráti späť. |
| `Merk Kiosk Installer.app` / `.command` | Dvojklik spustí inštalátor (appku vytvorí `tools/make-installer-app.sh`). |
| `tv/provision.sh` | Inštalácia na jednu TV: `./provision.sh <IP>`. |
| `tv/undo.sh` | Vrátenie TV: `./undo.sh <IP> --apps --remove-app` (reklamy a telemetria ostanú vypnuté). |
| `tv/helper.sh` | Zapnutie / vypnutie pomocníka z počítača: `./helper.sh on|off <IP>`. |
| `tv/debloat-list.txt` | Appky, ktoré sa na TV vypínajú (len vypnutie, nič sa nemaže). |
| `tv/tvbro-homepage.sh` | Nastaví v TV Bro domovskú stránku na landing page (klikaním podľa názvov, adresa cez schránku kiosku). |
| `release.sh` | Zostaví novú verziu, pripraví `dist/update.json` + APK a vydá ich na GitHube (TV si ich odtiaľ stiahnu). |
| `tools/cdp.mjs` | Ladenie: vypíše požiadavky a konzolu WebView (cez Chrome DevTools). |

## Ako appka funguje

- **Domovská obrazovka:** appka je HOME aktivita. TCL po zapnutí aj tak otvorí Google domov, preto
  appka obsahuje „pomocníka“ (`HomeWatch`, služba Prístupnosti), ktorý si všimne Google domov a vráti
  kiosk dopredu. Obsah obrazovky nečíta.
- **Výber obrazovky:** landing page `https://merk.bannet.sk/tv-menu/`. Prvá stránka webu otvorená z nej
  sa zapamätá. **3× Späť** = návrat na výber. **5× Späť** = vypne pomocníka (do ďalšieho otvorenia kiosku).
- **Auto-refresh:** raz za ~15 s (±20 %) jeden malý dotaz `wp-json/wp/v2/pages/<id>?_fields=modified_gmt`.
  Pri chybe servera sa interval predlžuje až na 5 min. Viac ako 20 načítaní za 30 s = 1 min pauza.
  Interval sa dá zmeniť bez novej appky: `"pollSeconds"` v `update.json` (15 až 600).
- **Rozloženie 1920:** appka vykreslí stránku v šírke 1920 CSS px a zmenší ju na obrazovku, takže funguje
  pri hustote 213 aj 106.
- **Aktualizácie:** každých 6 h (prvá 1 až 3 min po štarte) appka prečíta `update.json` z najnovšieho
  vydania na GitHube (`github.com/Seehank/Kiosk-TV/releases/latest/download/update.json`); ak je tam vyšší
  `versionCode`, stiahne APK, overí SHA-256 a nainštaluje (Android sa opýta „Inštalovať?“). Po aktualizácii
  si appka pomocníka zapne sama (povolenie `WRITE_SECURE_SETTINGS`, udeľuje ho `provision.sh`).
- **Schránka pre inštalátor:** `adb shell am broadcast -n sk.merk.kiosk/.Clip --es text '<text>'` vloží text
  do schránky TV (povel smie poslať len ADB z počítača). Inštalátor tak vkladá adresu do TV Bro jedným Paste.
- Každá požiadavka appky má User-Agent `MerkKiosk/<verzia>` a zapisuje sa do logu (`adb logcat | grep MerkKiosk`).

## Zostavenie a vydanie

Potrebné nástroje sú lokálne v `.toolchain/` (JDK 21, Android SDK, Gradle), nie v gite.
Podpisový kľúč je v `keystore/` a **nie je v gite**: bez neho sa nová verzia na TV nenainštaluje ako
aktualizácia. Zálohuj ho mimo počítača.

```bash
./release.sh 1.17      # zvýši versionCode, zostaví, pripraví dist/update.json + APK a vydá ich na GitHube
```

## Inštalácia na TV

Na TV raz ovládačom: Nastavenia → Zariadenie → Informácie → 7× *Zostava*, potom Pre vývojárov →
*Ladenie USB* zapnúť. Pri prvom pripojení na TV potvrdiť „Povoliť ladenie USB?“ (Vždy povoliť).
Potom dvojklik na `Merk Kiosk Installer.command`, vybrať TV a *Nainštalovať*.

Čo inštalácia urobí (`tv/provision.sh`), všetko vratné cez *Vrátiť späť*:
1. stiahne najnovšiu verziu kiosku z GitHubu (overí SHA-256),
2. skontroluje model (UnionTV, Android 9),
3. nainštaluje kiosk a TV Bro, v TV Bro nastaví domovskú stránku `https://merk.bannet.sk/tv-menu/` (~1 min),
4. vypne appky z `debloat-list.txt`,
5. hustota obrazovky 106 (šírka ~1920 px ako na ostatných TV), bez šetriča, bez automatického vypínania,
6. kiosk ako domovská obrazovka, povolenie pre pomocníka, zapne pomocníka, vypne Google domov, reštartuje TV.

ADB: inštalátor si pri štarte stiahne najnovšie oficiálne ADB od Googlu (`tools/get-adb.sh`, platform-tools do `tools/platform-tools`, stiahne ho len keď chýba, `--update` hľadá novšie) a používa ho. Záložne ide cez čisto pythonový klient `tools/tvadb.py` (prevzatý z projektu couch-tv, MIT licencia, `tools/LICENSE-couch-tv`) vo vlastnom prostredí `.venv` (vytvorí sa samo).

Mac appka s ikonou: `./tools/make-installer-app.sh` vytvorí `Merk Kiosk Installer.app` (dvojklik). Inštalátor kontroluje model (UnionTV, Android 9), ukazuje verziu kiosku, pomocníka a stránku na obrazovke, a vedie históriu inštalácií v `tv/state/installs.csv`.

## Pravidlá

- Na WordPress stránky TV obrazoviek sa nesiaha, len sa na ne odkazuje.
- Server chrániť: žiadne slučky, žiadne neštandardné požiadavky, pri chybe spomaliť.
- Všetko na TV je vratné (`undo.sh`).
