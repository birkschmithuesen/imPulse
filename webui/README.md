# imPulse Web-UI

Schlanke Weboberflaeche, um die OSC-Parameter des laufenden imPulse-Sketches
live zu aendern — im Browser, ueber Tailscale (`http://100.94.47.6:8080`) oder
im lokalen LAN.

Der Server laeuft **auf derselben Maschine wie imPulse** (Windows-Laptop),
bindet auf `0.0.0.0:8080` und schickt die Aenderungen per OSC an
`127.0.0.1:8001` — also genau dorthin, wo `oscP5` im Sketch lauscht.

```
Browser  --HTTP:8080-->  webui/server.py  --OSC:8001-->  imPulse (Processing)
```

## Was das UI anzeigt

Die Parameterliste ist **nicht** im Code verdrahtet, sondern wird bei jedem
Seitenaufruf aus `data/remoteSettings.txt` gelesen. Diese Datei schreibt
imPulse bei **jedem Start** aus den registrierten `RemoteControlled*Parameter`
neu (`OscMessageDistributor.dumpParameterInfo`). Ein neuer Parameter im Sketch
taucht damit ohne Codeaenderung hier auf; ein Klick auf **Neu laden** liest die
Datei nach einem imPulse-Neustart sofort neu ein.

- Float-Parameter: Regler + Zahlenfeld mit dem Bereich aus der Datei
- Int-Parameter: dito, ganzzahlig; `0/1`-Parameter (z. B.
  `/net/randomSpawn/enabled`) als Schalter
- Farbparameter: in `remoteSettings.txt` stehen sie als drei Float-Zeilen
  `<basis>/Hue`, `<basis>/Sat`, `<basis>/Bright` — das UI erkennt das Tripel
  und zeigt zusaetzlich einen Farbwaehler, der alle drei gemeinsam setzt
- Gruppierung nach Adress-Praefix (alles unter `/net/impulse` zusammen, alles
  unter `/net/randomSpawn` zusammen usw.)
- Trigger-Parameter (`/net/activateNode`, `/net/activateStripe` — Einmal-
  Aktionen, keine Regler-Werte) bekommen ein eigenes Widget: Zahlenfeld +
  **Ausloesen**-Knopf, statt eines Sliders, der einen falschen „gehaltenen
  Zustand" suggerieren wuerde
- `oscMaxCount` und `energyExponent` (Setup-/Sicherheitsparameter, keine
  Alltags-Regler) stehen in einer eigenen, per Default eingeklappten
  **Advanced**-Sektion, statt zwischen Farbe und Speed

Vollstaendige Range-/Sections-Analyse aller OSC-Parameter:
`docs/webui-parameter-review-2026-07-30.md`.

### Drei Spezial-Sektionen

Drei Gruppen bekommen ein handgebautes Bedienfeld statt generischer Regler —
38 Sequencer-Parameter als flache Liste waeren unbedienbar. Der Server
liefert dafuer Struktur (`build_sequencer`, `build_speed_classes`,
`sc_param_groups`), das Aussehen macht `static/app.js`.

- **Sequencer**: BPM als grosse Ziffer mit eigenem Not-Aus, darunter sechs
  Track-Karten mit je eigener Spurfarbe. Notenwerte als Knopfleiste mit
  Symbol **und** Kuerzel (`1/4`) — nicht jede Windows-Schrift hat
  U+1D15D..U+1D161, ein Symbol allein waere dort ein leeres Kaestchen.
  `originStripeOverride` zeigt `-1` als „zufall" statt als Zahl.
- **Speed-Klassen**: die fuenf Gewichte aus
  `/net/impulse/speedQuantize/weight/*` plus ein Verteilungsbalken, der sie
  normiert als Prozente zeigt. Die Gewichte selbst muessen sich nicht auf 100
  summieren — normalisiert wird auf der Java-Seite (`SpeedQuantizer.pick`).
- **Sound (SuperCollider)**: siehe unten, eigener Port.

Die Adressen dieser drei Sektionen nimmt `sequencer_addresses()` aus dem
generischen Rendering heraus. Ohne das stuende jeder Regler zweimal auf der
Seite — zwei Bedienelemente fuer denselben Parameter, die auseinanderlaufen
koennen.

**Die Rasteranzeige je Track ist die Uhr des Browsers.** Sie rechnet aus BPM
und Notenwert, in welchem *Abstand* ein Track feuert, und ist ausdruecklich
**keine** Rueckmeldung aus imPulse: dafuer gaebe es keinen Kanal, der Sketch
sendet nur an Port 8002 und dort hoert SuperCollider. Ihre Phase kann
gegenueber dem Sketch beliebig verschoben sein. Deshalb heisst sie „Raster"
und behauptet nirgends „feuert jetzt". Nuetzlich ist sie trotzdem: beim
Einrichten sieht man auf einen Blick, welcher Track dicht und welcher duenn
laeuft.

### Sound-Parameter gehen an einen zweiten Port (8002)

Die `/klangnetz/param/*`-Adressen laufen **nicht** durch
`remoteSettings.txt` — das ist die Parameterliste von imPulse. SuperCollider
hat seine eigene Registry (`~registerParam` in
`supercollider/klangnetz_bells.scd`) und hoert auf Port **8002**. Das UI hat
dafuer einen zweiten `OscSender` und den Endpoint `POST /api/sc`.

Zwei Dinge, die man dabei kennen muss:

- **`SC_PARAMS` in `server.py` ist eine handgepflegte Kopie der Registry.**
  Wer in der `.scd` einen Parameter ergaenzt, ergaenzt ihn auch dort. Zwei
  Tests in `test_webui.py` vergleichen die Tabelle in **beide** Richtungen
  mit der Datei, damit sie nicht still abdriftet. Die Alternative waere ein
  Parser fuer sclang-Syntax — der bei der naechsten Umformatierung
  unbemerkt das Falsche liefert.
- **Es gibt keinen Rueckkanal.** Die angezeigten Werte sind die Defaults aus
  der `.scd`, nicht der Live-Zustand von sclang; laeuft sclang nicht, bleibt
  eine Aenderung wirkungslos, ohne dass das UI es merkt. Die Sektion sagt das
  selbst in ihrem Warnhinweis.

Jede Aenderung geht **sofort** raus (erste Bewegung direkt, danach hoechstens
alle 150 ms eine Nachricht, der letzte Wert in jedem Fall) — kein Speichern-Knopf.

## Presets

Ganz oben, ueber den Reglern, sitzt die Sektion **Presets**: ein Dropdown mit
allen vorhandenen Presets plus **Laden**, darunter ein Textfeld plus
**Speichern**.

Ein Preset ist ein kompletter Wertesatz aller fernsteuerbaren Parameter, abgelegt
als `data/presets/<name>.txt` — **im selben Format wie `remoteSettings.txt`**.
Geschrieben wird der Ordner ausschliesslich von imPulse (`PresetStore.java`);
das Web-UI **liest** ihn nur.

- **Liste**: kommt vom Dateisystem, nicht per OSC. Der Server laeuft auf
  derselben Maschine wie imPulse und sieht den Ordner direkt — ein OSC-
  Rueckkanal waere neu zu bauen (die einzige Ausgangsadresse des Sketches ist
  Port 8002, also SuperCollider). Aktualisiert wird beim Seitenaufruf und nach
  jedem Speichern.
- **Laden** schickt `/preset/load <name>` und zieht zusaetzlich die Regler nach:
  der Server liest dieselbe Datei mit demselben Parser wie `remoteSettings.txt`
  und schickt die Werte in der Antwort zurueck. Die Regler werden still gesetzt,
  loesen also kein zweites OSC aus — das Anwenden macht imPulse selbst.
  Geklemmt wird dabei auf die Grenzen aus `remoteSettings.txt`, nicht auf die
  aus der Preset-Datei (dieselbe Regel wie `PresetStore.applyPreset()`).
- **Speichern** schickt `/preset/save <name>` und wartet bis zu **1 Sekunde**
  darauf, dass die Datei erscheint — der Sketch schreibt sie erst im naechsten
  `draw()`-Durchlauf. Bleibt sie aus, kommt eine klare Fehlermeldung
  („laeuft der Sketch?") statt eines stillen Erfolgs. Ein vorhandenes Preset
  wird **ohne Rueckfrage ueberschrieben**; die Statuszeile sagt es hinterher.
- **Namen**: nur `a-z`, `0-9`, Unterstrich und Bindestrich, 1 bis 64 Zeichen —
  wortgleich zu `PresetStore.isValidName()` in Java. Das UI und der Server
  pruefen das vorab, die Autoritaet bleibt Java (dort geht es um
  Pfad-Traversal). Grossbuchstaben sind ausgeschlossen, weil `Standby` und
  `standby` auf Windows dieselbe Datei waeren.

Zwei Dinge meldet die Statuszeile beim Laden, statt sie zu verschlucken:
Adressen aus dem Preset, die `remoteSettings.txt` gar nicht kennt (Preset und
Dump aus verschiedenen Codestaenden), und Werte ausserhalb der im UI verengten
Range (siehe `UI_RANGE_OVERRIDES`) — dort klemmt der Regler sichtbar und soll
nicht behaupten, der Sketch fahre den angezeigten Wert.

**Kein Loeschen**: ein Preset wird man los, indem man die Datei auf dem Laptop
von Hand loescht. Bewusst nicht im UI, weil sonst das Web-UI in einen Ordner
schreiben wuerde, der imPulse gehoert.

Der Knopf **Neu laden** oben rechts hat damit nichts zu tun — der liest
`remoteSettings.txt` neu ein, also die Parameter-*Definitionen*.

## Automatische Sicherung der Live-Daten

Presets, Farbpaletten und Kalibrierdateien werden im laufenden Betrieb
geaendert, aber niemand committet sie — nachgezogen wurde das bisher von Hand
(„X-Preset vom Live-Betrieb nachgezogen" in der Historie). Wird ein Rechner
neu aufgesetzt, bevor jemand daran denkt, ist die Live-Arbeit weg.

Der Server bringt dafuer einen Hintergrund-Thread mit (`webui/autocommit.py`):
alle **10 Minuten** prueft er die unten stehenden Pfade und macht einen
**lokalen Git-Commit** — aber nur, wenn sich tatsaechlich etwas geaendert hat.
Kein leerer Commit, keine feste Taktung unabhaengig vom Zustand.

**Gepusht wird nicht.** Der Commit bleibt im Checkout des jeweiligen Rechners;
uebertragen wird von Hand mit `git push`, wenn Birk das fuer richtig haelt.
Mehrere Checkouts arbeiten parallel am selben Remote (Live-Laptop,
Test-Deploy, Worktrees) — ein automatischer Push waere eine Fernwirkung ohne
Entscheidung. Dieselbe Regel, die CLAUDE.md fuer Merges und Force-Pushes
aufstellt.

Ueberwacht wird genau das:

| Pfad | warum |
|---|---|
| `data/presets/*.txt` | vom UI ausgeloest, vom Sketch geschrieben |
| `supercollider/presets/*.txt` | ein Preset ist **ein Name, zwei Dateien** — nur die Licht-Haelfte zu sichern hiesse, die Szene kaeme spaeter optisch zurueck und klanglich nicht |
| `data/colorPalettes.txt` | im UI editierbar |
| `data/energyLevels.txt` | im UI editierbar |
| `data/stripeTrees.txt` | Best-Guess, von Hand korrigiert |
| `data/nodeCrossings.txt` | Node-Kalibrierung, nur auf Tastendruck `S` geschrieben |
| `data/ledPositions.txt` | Positions-Kalibrierung, ebenfalls nur auf `S` |

Ein Muster, das in diesem Checkout auf nichts passt, ist kein Fehler — die
Farbpaletten und die Energie-Level kommen erst mit ihren Feature-Branches.

**Bewusst nicht dabei:** `data/remoteSettings.txt` (steht in `.gitignore`,
Boot-Snapshot bei jedem Sketch-Start) und `data/songStructureState.txt` (wird
vom Sketch bei jedem Levelwechsel neu geschrieben). Die Trennlinie ist nicht,
*wer* die Datei schreibt, sondern *wann sie sich aendert*: aendert sie sich nur,
wenn ein Mensch etwas entschieden hat, ist sie Konfiguration; aendert sie sich
von selbst waehrend der Show, ist sie Laufzeitstatus und gehoert nicht in einen
Commit.

**Uebersprungen wird**, ohne einzugreifen und ohne den Server zu stoeren:

- ein offener Merge, Rebase, Cherry-Pick, Revert oder Bisect (erkannt an
  `MERGE_HEAD` und Geschwistern im `.git`-Verzeichnis)
- ein Merge-Konflikt in einer der ueberwachten Dateien
- ein **detached HEAD** — ein Commit ohne Branch waere nach dem naechsten
  Checkout nur noch im Reflog, also ein Sicherungsnetz, das nicht haelt
- jeder Git-Fehler: wird geloggt und in der Statuszeile angezeigt, der
  naechste Versuch laeuft in 10 Minuten

Sichtbar ist der Zustand in der Zeile unter der Statusleiste („Automatische
Sicherung: alle 10 min lokal committet (kein Push) — zuletzt gesichert vor
3 Minuten"), maschinenlesbar unter `GET /api/autocommit`. Nur lesend: es gibt
bewusst keinen Knopf, der aus dem Browser heraus Git-Zustand aendert.

Abschalten fuer ein Entwickler-Checkout:

```bash
python3 server.py --no-autocommit
IMPULSE_AUTOCOMMIT=0 python3 server.py
python3 server.py --autocommit-interval 3600   # oder nur seltener
```

## Normalisierung (der Fallstrick)

`RemoteControlledFloatParameter.digestMessage` mappt eingehende Floats selbst:

```java
theValue = PApplet.constrain(PApplet.map(theValue, 0, 1, minValue, maxValue), minValue, maxValue);
```

Ein Float muss also **auf 0..1 normalisiert** gesendet werden
(`(wert - min) / (max - min)`), nicht als Rohwert — sonst landet z. B. eine
gesendete `5.0` fuer `/nodes/times/recover` (Bereich 0..10) geklemmt bei `10`.
Das erledigt `Parameter.normalize()` in `server.py`.

Int-Parameter gehen dagegen **unveraendert als Ganzzahl** raus: die
Float-Variante von `RemoteControlledIntParameter.digestMessage` ruft
`intValue()` auf dem Float auf und verstuemmelt den Wert dadurch, bevor
gemappt wird. Ein Float darf an einen Int-Parameter also nie geschickt werden.

Nebenbei: `Master/trace` und `Master/0/opacity/0.Impulse` sind in `mixer.java`
**ohne** fuehrenden Schraegstrich registriert. `python-osc` lehnt solche
Adressen ab, deshalb hat `server.py` einen eigenen, minimalen OSC-Encoder, der
fuer genau diese Adressen einspringt (byte-identisch fuer alle uebrigen, siehe
`test_webui.py`).

## Speed-Kopplung

Aendert man `/net/impulse/speed`, skaliert das UI drei weitere Parameter mit:

| Parameter                        | Referenz bei Speed 160 | Skalierung    |
|----------------------------------|------------------------|---------------|
| `/net/impulse/lifetime`          | 0.2                    | proportional  |
| `/net/impulse/nodeDeadTime`      | 1.0                    | invers        |
| `/net/randomSpawn/interval`      | 3.0                    | invers        |

Mit `faktor = neuer_speed / 160`: proportional heisst `referenz * faktor`,
invers `referenz / faktor`. Ergebnisse werden auf den in
`remoteSettings.txt` genannten Bereich geklemmt; das UI zeigt die tatsaechlich
gesendeten Werte an (die Regler springen sichtbar nach), es wird nichts blind
verschickt. Parameter, die in der Datei fehlen (z. B. `/net/randomSpawn/*` in
einem alten Dump), werden uebersprungen und in der Statuszeile genannt.

Die Kopplung laesst sich oben rechts per **Speed-Kopplung aktiv** abschalten,
wenn Speed mal isoliert geaendert werden soll (die Einstellung merkt sich der
Browser).

> Hinweis: Der Referenzpunkt oben stammt aus dem Brief bzw. `tune_speed.py`.
> Die Konstruktor-Defaults in `LedNetworkTransportEffect.java` stehen aktuell
> auf einem anderen Arbeitspunkt (Speed 16, `lifetime` 0.02,
> `nodeDeadTime` 5.0, `randomSpawn/interval` 30.0).
> `/net/impulse/lifetime` hiess bis 2026-07-31 `/net/impulse/energyDecayfactor`;
> das damalige zweite `/net/impulse/energyDecay` war im Sketch wirkungslos und
> ist ersatzlos entfallen.
> Wer stattdessen diesen Arbeitspunkt koppeln will, aendert nur den Block
> `SPEED_REFERENCE` / `SPEED_COUPLED` oben in `server.py`.

## Installation und Start

Voraussetzung: Python 3.8+ (Windows: „Add python.exe to PATH" beim Installieren
anhaken). Kein Node, kein npm, kein Build-Schritt.

### Windows

```bat
cd C:\Users\birk\imPulse\webui
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python server.py
```

### macOS / Linux

```bash
cd ~/github/imPulse/webui
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python server.py
```

Danach im Browser: `http://100.94.47.6:8080` (Tailscale) oder
`http://<LAN-IP>:8080`. Beim Start ausserhalb des Repos den Pfad zur
Parameterdatei mitgeben.

### Optionen

| Option / Umgebungsvariable                | Vorgabe                     |
|-------------------------------------------|-----------------------------|
| `--settings` / `IMPULSE_SETTINGS`         | `<repo>/data/remoteSettings.txt` |
| `--presets` / `IMPULSE_PRESETS`           | `presets/` neben `--settings`    |
| `--osc-host` / `IMPULSE_OSC_HOST`         | `127.0.0.1`                 |
| `--osc-port` / `IMPULSE_OSC_PORT`         | `8001`                      |
| `--host` / `IMPULSE_WEBUI_HOST`           | `0.0.0.0`                   |
| `--port` / `IMPULSE_WEBUI_PORT`           | `8080`                      |
| `--no-autocommit` / `IMPULSE_AUTOCOMMIT=0` | an (siehe „Automatische Sicherung") |
| `--autocommit-interval` / `IMPULSE_AUTOCOMMIT_INTERVAL` | `600` Sekunden |

Beispiel:

```bat
.venv\Scripts\python server.py --settings "C:\Users\birk\imPulse\data\remoteSettings.txt" --port 8080
```

### Windows-Firewall

Beim ersten Start fragt Windows nach der Freigabe von Python — fuer das
lokale LAN zulassen. Ueber Tailscale ist keine Freigabe noetig, weil der
Tailscale-Adapter als privates Netz gilt. Falls der Dialog weggeklickt wurde:

```bat
netsh advfirewall firewall add rule name="imPulse WebUI 8080" dir=in action=allow protocol=TCP localport=8080
```

## Als Scheduled Task registrieren

Analog zum bestehenden `ImpulseRun`-Task. `run_webui.bat` liegt neben
`server.py`, aktiviert die venv, startet `server.py` und schreibt alles nach
`webui_run.log` im selben Ordner.

```bat
schtasks /Create /TN WebUiRun /TR "C:\Users\birk\imPulse\webui\run_webui.bat" /SC ONCE /ST 00:00 /RU birk /IT /RL HIGHEST /F
```

Starten, Status pruefen, wieder beenden:

```bat
schtasks /Run /TN WebUiRun
schtasks /Query /TN WebUiRun
schtasks /End /TN WebUiRun
```

Den Pfad in `/TR` an den tatsaechlichen Ablageort des Repos anpassen. `/SC ONCE
/ST 00:00` heisst: der Task laeuft nicht von selbst los, sondern nur auf
`schtasks /Run` — genau wie bei `ImpulseRun`. Die Ausgabe steht danach in
`webui\webui_run.log`.

## Kein Auth

Bewusst ohne Anmeldung und ohne HTTPS: erreichbar ist der Server nur im Tailnet
und im lokalen LAN, beides vertraute Netze. Kein Port ist nach aussen
freigegeben. Wenn der Laptop je in einem fremden Netz haengt, sollte der Server
nicht laufen bzw. mit `--host 127.0.0.1` gestartet werden.

## Tests

`test_webui.py` prueft das, was schiefgehen kann, ohne Flask, ohne python-osc
und ohne laufende Installation: Parsen von `remoteSettings.txt`,
Normalisierung Float/Int, Gruppierung inkl. Farbtripel, die Speed-Kopplung
samt Klemmung und den OSC-Encoder byteweise (inklusive String-Argument fuer
die Preset-Kommandos).

Dazu die Preset-Logik: die Namensregel als Spiegel von
`PresetStore.isValidName()`, das Auflisten des Ordners (Sortierung, `.txt` ab,
ungueltige Namen uebergangen), die Uebernahme einer Preset-Datei in die
Regleranzeige (Klemmung auf die `remoteSettings.txt`-Range, unbekannte
Adressen, Werte ausserhalb der UI-Range) und das Warten auf die von imPulse
geschriebene Datei.

Dazu die drei Spezial-Sektionen: dass der Sequencer alle sechs Tracks mit
allen Feldern liefert, dass ein aelterer imPulse-Stand `None` statt einer
leeren Sektion ergibt, dass kein Parameter doppelt gerendert wird (an einem
echten Snapshot geprueft) und dass `SC_PARAMS` in beide Richtungen zur `.scd`
passt.

```bash
python3 webui/test_webui.py
```

Die automatische Sicherung hat eine **eigene** Suite, ebenfalls ohne
Fremdabhaengigkeiten:

```bash
python3 webui/test_autocommit.py
```

Sie prueft den Porcelain-Parser, das Pfad-Matching, die Commit-Message und die
Ablauflogik gegen einen eingesetzten Git-Runner (ohne echten Prozess) — und
dazu zwei Dinge, die nicht verhandelbar sind:

- **Ein Grep ueber alle `webui/*.py`**, dass nirgends `git add -A`,
  `git add .`, `git commit -a` oder ein `git push` steht. Mit Gegenprobe, dass
  die Muster tatsaechlich anschlagen — ein Test, der nie ausloest, prueft
  nichts.
- **Ein Integrationstest gegen ein echtes, temporaeres Repository**: eine
  geaenderte Preset-Datei *und* eine geaenderte, nicht ueberwachte Datei, die
  obendrein von Hand gestaged ist. Erwartet wird genau ein Commit, der nur die
  Preset-Datei enthaelt, waehrend die fremde Aenderung danach noch schmutzig
  ist. Der Grep prueft die Schreibweise, dieser das Verhalten.

Fehlt `git` auf dem Rechner, wird der Integrationsteil uebersprungen statt zu
scheitern.

**Fuer die UI-Schicht selbst gibt es kein Testgeruest im Repo.** `webui/` soll
ohne Node/npm auskommen (siehe oben), und ein jsdom-Test wuerde genau das
einfuehren. Das Rendering des Sequencer-Panels wurde einmalig headless mit
jsdom gegengeprueft — der Test ist bewusst **nicht** aufgenommen. Wer daran
etwas aendert, prueft im Browser nach.
