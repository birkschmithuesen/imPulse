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

```bash
python3 webui/test_webui.py
```
