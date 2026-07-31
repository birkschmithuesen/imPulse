# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projekt

Processing-Sketch (Java-Modus) für die audiovisuelle Installation *imPulse*: LED-Stripes bilden ein chaotisches Netz, Kontaktmikrofone an Metallrohren erzeugen Lichtimpulse, die entlang der Stripes wandern und sich an Kreuzungen (Nodes) aufspalten, wobei sie Töne triggern.

Signalkette: `Max/MSP --OSC:8001--> Processing --Syphon--> MadMapper --ArtNet--> APA102` und zurück `Processing --OSC:8002--> Max/MSP`. Alternativ sendet Processing direkt per ArtNet (aktuell aktiv, siehe „Ausgabepfade").

## Branch-Konvention (verbindlich, seit 2026-07-30)

- **`master`** = **Working State**. Muss jederzeit exakt das abbilden, was
  gerade live auf der Installation läuft (Windows-Laptop, Java/Processing-
  Code UND SuperCollider-Sound-Patch inkl. aller Live-Tuning-Werte wie
  `masterLevel`, Amplituden-Ranges). Ziel: ein Neustart der Installation mit
  dem `master`-Stand reproduziert exakt den aktuellen Show-Zustand, ohne
  manuelles Nachsenden von OSC-Werten. Kein Feature-Entwicklungscode hier,
  keine Werkstatt-Experimente — nur das, was gerade tatsächlich läuft.
  Divergiert `master` vom Live-Stand (z. B. weil ein Live-Tuning-Wert nur per
  OSC gesetzt wurde), gehört der Wert in den Code-Default nachgezogen, nicht
  nur im laufenden Prozess belassen.
- **`grabicz26`** = **Entwicklung**. Alle neuen Features (Ambisonics/
  Spatialisierung, ArtNet-Kalibrierung, ArtNet-Ausgang, Preset-System-
  Vorarbeiten, WebUI usw.) laufen hier bzw. auf davon abgeleiteten
  Feature-Branches/Worktrees (z. B. `feature/preset-system`,
  `feature/webui-sound-master`). Wird NICHT automatisch nach `master`
  gemergt — ein Merge/Cherry-Pick nach `master` ist eine bewusste,
  einzelne Entscheidung (typischerweise: „dieses Feature ist jetzt Teil
  des Live-Betriebs").
- **Bei Unklarheit, was gerade auf `grabicz26` vs. wo sonst passiert:**
  zuerst `git status --short` + `git log -1 --oneline` im jeweiligen
  Checkout/Worktree prüfen, dann `git worktree list` für einen Überblick
  über alle aktiven Arbeitsverzeichnisse. Mehrere parallele Sessions
  können gleichzeitig an `grabicz26` oder eigenen Feature-Branches
  arbeiten — vor jedem Merge/Force-Push den tatsächlichen IST-Zustand
  auf dem Laptop per SSH verifizieren (`git log -1 --oneline` im
  `imPulse`-Checkout dort), nicht raten.
- **Force-Push auf `master`** ist grundsätzlich möglich (Working-State-
  Branch, keine lineare Feature-Historie nötig), aber nur nach expliziter
  Bestätigung durch Birk — nie automatisch/eigenmächtig.

## SuperCollider-Sound-Datei (verbindlich, seit 2026-07-31)

**Es gibt genau EINE SC-Sound-Datei:** `supercollider/klangnetz_bells.scd`.
Der Windows-Task `KlangnetzBells` startet sie direkt aus diesem Repo-Pfad
(`C:\Users\birk\Documents\imPulse\supercollider\klangnetz_bells.scd`) — **keine
lose Kopie im Home-Verzeichnis mehr** (`C:\Users\birk\klangnetz_bells_zoom.scd`
existierte bis 2026-07-31 parallel und führte zu einer echten Verwechslung:
eine Analyse der Repo-Datei ergab falsche Tonwerte, weil der Live-Task eine
andere, abweichende Kopie lud). Details/Änderungshistorie:
`supercollider/klangnetz_bells.README.md`.

Bei jeder Änderung an der Sound-Logik: **immer nur diese eine Datei anfassen**,
committen, pushen, auf dem Laptop `git pull` + Task-Neustart (`schtasks /End`
+ `/Run /TN KlangnetzBells`) — kein manuelles Kopieren in ein anderes
Verzeichnis, das schafft sofort wieder zwei Wahrheiten.

## Ausführen

Kein Build-System für den Sketch selbst — reines Processing-Projekt. Für die netz- und processingunabhängigen Teile (`ArtNetOutput`, `NodeCrossingStore`, `LedStripeNetworks`, `TestPatterns`, `LedAnchorStore`, `LedPositionMap`, `LedPositionCalibration`, `ImpulseOscThrottle`) gibt es aber eine eigene Testsuite, siehe „Tests" unten.

- **IDE**: `imPulse.pde` in Processing 3 öffnen, Play. Der Sketch-Ordner **muss** `imPulse` heissen (Processing-Konvention: Ordnername == Name der Haupt-`.pde`).
- **CLI**: `processing-java --sketch=/Users/macbook/Projekte/_gitHub/imPulse --run`

### Bibliotheken

`oscP5` (bringt auch `netP5` mit) und `Syphon` liegen als Kopie in `libraries/`. Für den **Betrieb in der Processing-IDE** löst Processing Bibliotheken **aus dem Sketchbook** (`~/Documents/Processing/libraries`) auf, nicht aus dem Sketch-Ordner — dort müssen sie per Contribution Manager installiert sein.

Für die **Übersetzungsprüfung** (`test/build.sh`) gilt das nicht mehr: das Skript übersetzt eine Kopie des Sketches in `TMPDIR` und legt die Jars aus `libraries/*/library/*.jar` in einen `code/`-Unterordner. Einen `code/`-Ordner nimmt Processing immer in den Klassenpfad, ohne Sketchbook. Das war nötig, weil `~/Documents/Processing/libraries` auf dem Entwicklungsrechner unter der macOS-Datenschutzsperre für den Documents-Ordner nicht lesbar ist und jeder Aufruf mit „No library found for netP5 / oscP5 / codeanticode.syphon" endete — auch mit abgeschalteter Sandbox. Nebenwirkung: die Prüfung hängt nicht mehr an einer Installation ausserhalb des Repos, nur noch an Processing selbst (Pfad über `IMPULSE_PROCESSING_JAVA` überschreibbar).

`artnet4j` (`ch.bildspur.artnet`) wird **nicht mehr gebraucht** — der aktive Ausgabepfad ist die selbstgeschriebene `ArtNetOutput`-Klasse, die bewusst nur an `LedColor` und der Java-Standardbibliothek hängt. `controlP5` wird ebenfalls nicht mehr gebraucht — das Dropdown-basierte Kalibrier-UI ist der Zwei-Cursor-Kalibrierung (`NodeCalibration`) gewichen, die direkt über Tastatur und das HUD im Sketch-Fenster läuft.

### Tests

`test/run.sh` übersetzt die processing- und netzunabhängigen Klassen (`LedColor`, `ArtNetOutput`, `NodeCrossingStore`, `NodeSelection`, `LedStripeNetworks`, `TestPatterns`, `LedAnchorStore`, `LedPositionMap`, `LedPositionCalibration`, `ImpulseOscThrottle`, `ParameterOscillator`, `PresetStore`, `PresetScheduler`) zusammen mit `test/*.java` gegen `core.jar` von Processing und führt sie aus. Ohne Argumente startet es alle Suiten der Default-Liste im Skript — die vier ersten immer, die übrigen nur, wenn ihre Quelldatei vorhanden ist (ein Fehlen wird gemeldet, nicht stillschweigend übergangen):

- `ArtNetOutputTest` — Adressrechnung und byte-genauer Paketbau, inklusive der Sicherheitsanforderung an den Master-Pegel (Auslieferungswert 0.1, Klemmung auf 0..1)
- `ArtNetDecoderTest` — Gegenprobe: ein unabhängiger Decoder setzt den LED-Puffer aus den gebauten Paketen zurück zusammen
- `NodeCrossingStoreTest` — Validierung, Undo, Laden/Speichern der Kreuzungsdatei, inklusive `clearAll()` und `removeAt()` (auch die Verschiebung von `loadedCount`, wenn ein geladener Eintrag gelöscht wird)
- `ApplyCrossingsTest` — `LedInNetInfo.applyCrossings` baut die Node-Zuordnung korrekt neu auf, auch beim wiederholten Aufruf mit weniger Kreuzungen
- `NodeSelectionTest` — der Auswahlzeiger der Kalibrierung: Blättern, Aufheben am Anfang, Klemmen bei schrumpfender Liste
- `LedAnchorStoreTest` — Anker setzen und löschen, Bereichs- und Grundflächenprüfung, Verteilung einer Position auf alle LEDs einer Kreuzung, die Bogenlängen-Warnung, Laden/Speichern von `data/ledPositions.txt`
- `LedPositionMapTest` — Interpolation zwischen zwei Ankern, Fortsetzung des Vektors darüber hinaus, Klemmung auf die Grundfläche, `coverageReport`
- `LedPositionCalibrationTest` — Arbeitsliste, Zeiger, Umrechnung Pixel↔Meter, Vorschlag, Feinjustierung, `L`-Zeitfenster, Rückmeldung im Netz samt Leuchtabschnitt um den aktuellen Eintrag
- `ImpulseOscThrottleTest` — Sendetakt (inklusive `rateHz = 0` und NaN) und die Auswahl der energiereichsten Impulse samt Tie-Break
- `TestPatternsTest` — das Sicherheitsventil der Testbilder: kein Muster gibt einen Kanal über `TestPatterns.PATTERN_LEVEL` aus (siehe „Master-Pegel")
- `ParameterOscillatorTest` — die Sinus-Formel der Speed-/Lifetime-Randomizer: Phasenlage, Periodizität, Einhalten von min/max, entartete Perioden, Zurücksetzen der Phase beim Wiedereinschalten
- `PresetStoreTest` — Format, Datei, Snapshot und Anwenden eines Presets, inklusive der ausgeschlossenen und still übergangenen Adressen
- `PresetSchedulerTest` — die Zeitlogik des Preset-Wechslers: Einschalten springt nicht sofort, Reihenfolge, Intervall

Daneben liegen im selben Ordner drei Sonden, die **echte Hardware ansprechen** und deshalb nicht Teil der Default-Suite sind: `TimingProbe` (misst den 40-Hz-Sendetakt am echten Netz), `PollProbe` (fragt die Controller per ArtPoll nach ihrem Befinden ab) und `PatternProbe` (speist die fünf Testbilder direkt über `ArtNetOutput` ein, ohne Processing-Laufzeit). Diese drei gezielt einzeln aufrufen, z. B. `test/run.sh TimingProbe`, niemals ungefragt gegen die Installation.

## Architektur

### Globaler LED-Index

Alles rechnet auf einem flachen Array über alle Stripes: `ledIndex = stripeIndex * numLedsPerStripe + indexInStripe`. Konfiguration steht als Feld in `imPulse.pde` (aktuell `numLedsPerStripe = 600`, `numStripes` ergibt sich aus `controllerOctets.length * ArtNetOutput.OUTPUTS_PER_CONTROLLER` = 15 Controller × 2 Outputs = 30 Stripes, macht 18 000 LEDs insgesamt). Jeder Effekt hält einen eigenen `LedColor[numLeds]`-Puffer und gibt ihn aus `drawMe()` zurück.

`LedColor` (LedColor.java) erbt von `PVector`; `x/y/z` sind `r/g/b` im Bereich **0..1**. Die Skalierung auf 0..255 (genauer: auf 0..255 je Kanal mal Master-Pegel) passiert erst beim Output (`ArtNetOutput.buildFrame`/`level()`, `drawLedColorsToCanvas`).

### Effekt-Pipeline

`runnableLedEffect` (mixer.java) ist das Interface: `drawMe()` + `getName()`. Der `Mixer` iteriert über alle registrierten Effekte, mischt additiv (`LedAlphaMode.ADD`) mit einer per OSC steuerbaren Opacity pro Effekt und wendet vorab `Master/trace` als Feedback-/Nachleuchtfaktor auf den Ausgabepuffer an.

Neuen Effekt hinzufügen: `TemplateEffect.java` als Vorlage kopieren, in `setup()` instanziieren und mit `mixer.addEffect(...)` registrieren.

Bestehende Effekte:
- `LedNetworkTransportEffect` — die wandernden Impulse (Kern der Installation)
- `LedNetworkNodeEffects` — Darstellung der Nodes (Zustände: firing → inactive → waiting, mit Pulsmodulation)
- `NodeCalibration` — **Kalibrier-Modus**, kein gestalterischer Effekt (siehe unten). Implementiert zwar `runnableLedEffect`, ist aber **nicht** über `mixer.addEffect(...)` registriert; `draw()` in `imPulse.pde` ruft `nodeCalibration.drawMe()` stattdessen direkt auf, wenn `calibrationMode` an ist, und ersetzt damit komplett die Mixer-Ausgabe für diesen Frame.
- `LedPositionCalibration` — **Positions-Modus**, ebenfalls kein gestalterischer Effekt (siehe „LED-Positionen und Spatialisierung"). Wird genauso direkt aus `draw()` gerufen, wenn `positionMode` an ist, nennt das Interface aber bewusst gar nicht erst (siehe „Konventionen").

### OSC-Parametersystem (AbstractParameter.java)

Jeder `RemoteControlled{Float,Int,Color}Parameter` registriert sich im Konstruktor selbst beim statischen `OscMessageDistributor`. Es gibt **keine** Adress-Zuordnung: der Distributor schickt jede Nachricht an *alle* Sinks, jeder Sink filtert selbst per `newMessage.checkAddrPattern(...)`.

Threading: `oscEvent()` läuft im oscP5-Thread und ruft nur `queueMessage()` (synchronized). Ausgewertet wird die Queue am Anfang von `draw()` über `distributeMessages()`. Neue Parameter also niemals direkt aus dem OSC-Callback verändern.

`data/remoteSettings.txt` wird bei **jedem Start** aus den registrierten Parametern neu geschrieben (`dumpParameterInfo`) und dient als Konfiguration der Remote-Oberfläche. Ein neuer `RemoteControlled*Parameter` taucht dort automatisch auf.

Eingehende OSC-Adressen: `/tube/trigger` (int Stripe, 1-basiert; optional float Energie), `/net/activateNode` (int), `/net/activateStripe` (int) sowie alle in `remoteSettings.txt` gelisteten Parameteradressen.

Ausgehend an Port 8002 (`oscOutput` in `imPulse.pde`, Auslieferungsziel `127.0.0.1`), beide aus `LedNetworkTransportEffect.java`:

- `/net/hitNode <nodeId:int> <energy:float> <x:float> <y:float>` — ein Node hat gefeuert. `x`/`y` sind die Draufsicht-Position des Knotens in Metern (`LedNetworkNode.posX/posY`, gesetzt von `applyPositions`), rein **angehängt**: ein Empfänger, der nur die ersten zwei Argumente liest, bleibt unberührt.
- `/net/impulse <impulseId:int> <x:float> <y:float> <energy:float>` — gedrosselter Positionsstrom der reisenden Impulse (`sendImpulseStream()`, Takt und Auswahl aus `ImpulseOscThrottle`). **Ein Datagramm je Impuls**, kein Anzahl-Feld, kein Bündel, keine Ende-Markierung — die Empfangsseite kann also nicht feststellen, wieviele Meldungen zu einem Takt gehören und braucht einen Timeout. Filler werden ausdrücklich übersprungen (sie tragen die ID ihres Elternimpulses). Es gibt **kein Todes-Signal**: ein Impuls kann aus der Auswahl der energiereichsten fallen, ohne zu sterben, deshalb deckt derselbe Timeout beides ab (`klangnetz_bells.scd`: 0,4 s, geprüft alle 0,1 s, Obergrenze 32 Drohnen).

Die zwei zugehörigen Parameter, beide in `LedNetworkTransportEffect`:

- `/net/impulse/oscRate` (float, Auslieferungswert 10, Bereich 0..40 Hz) — Sendetakt des Positionsstroms. **0 schaltet ihn ab**: kein Objekt, keine Liste, kein Datagramm. Der Not-Aus, wenn Netz oder Klangrechner während der Show nicht mitkommen; `/net/hitNode` läuft davon unberührt weiter. Beim Wiedereinschalten kommt sofort ein Takt (`lastSend` wird auf `-Infinity` zurückgesetzt), nicht erst nach einem Intervall — und es wird nichts nachgeholt: nach einer langen Pause gibt es **einen** Takt, keinen Schwall.
- `/net/impulse/oscMaxCount` (int, Auslieferungswert 32, Bereich 0..256) — höchstens so viele Impulse je Takt, ausgewählt nach Energie absteigend. 0 sendet ebenfalls nichts, verbraucht aber den Takt und allokiert vier Arrays (`energies`, `flat`, den `Arrays.copyOf(...)` und `select()`s `new int[0]`) — der teurere der beiden Schalter.

### Web-UI (webui/)

`webui/` ist eine kleine Flask-Anwendung (Python, kein Node/npm, kein Build-Schritt), die **auf derselben Maschine wie imPulse** läuft und alle OSC-Parameter im Browser regelbar macht — über Tailscale (`http://100.94.47.6:8080`) und im lokalen LAN. Sie fasst den Sketch nicht an, sondern spricht ausschliesslich über OSC an `127.0.0.1:8001` mit ihm.

Die Parameterliste ist **nicht** verdrahtet: `webui/server.py` liest bei jedem Seitenaufruf `data/remoteSettings.txt` und baut das UI daraus (Regler mit Bereich aus der Datei, Schalter für 0/1-Ints, Farbwähler für die `<basis>/Hue|Sat|Bright`-Tripel eines `RemoteControlledColorParameter`, Gruppierung nach Adress-Präfix). Ein neuer `RemoteControlled*Parameter` im Sketch taucht nach einem imPulse-Neustart also von selbst auf — die Datei wird ja bei jedem Start neu geschrieben.

Zwei Dinge, die man beim Ändern kennen muss:
- **Normalisierung**: Float-Parameter werden auf 0..1 normalisiert gesendet, weil `RemoteControlledFloatParameter.digestMessage` selbst `PApplet.map(value, 0, 1, min, max)` anwendet. Int-Parameter gehen dagegen unverändert als Ganzzahl raus — die Float-Variante von `RemoteControlledIntParameter.digestMessage` ruft `intValue()` auf dem Float auf und verstümmelt den Wert. Ausserdem sind `Master/trace` und `Master/0/opacity/0.Impulse` in `mixer.java` **ohne** führenden Schrägstrich registriert; `python-osc` lehnt solche Adressen ab, deshalb hat `server.py` für diese einen eigenen minimalen OSC-Encoder.
- **Speed-Kopplung**: Eine Änderung an `/net/impulse/speed` zieht `lifetime` (proportional) sowie `nodeDeadTime` und `/net/randomSpawn/interval` (invers) mit, damit die Impulse bei geänderter Geschwindigkeit weder reissen noch das Netz verstopfen (dieselbe Rechnung wie im Kommentar bei den Defaults in `LedNetworkTransportEffect.java`). Referenzpunkt und Formel stehen als `SPEED_REFERENCE`/`SPEED_COUPLED` oben in `server.py`, im UI ist die Kopplung per Checkbox abschaltbar.

Über den Reglern sitzt die Sektion **Presets** (Dropdown + Laden, Textfeld + Speichern). Sie ist der dritte Ladeweg neben OSC von Hand und dem Scheduler, benutzt aber dieselben Kommandos: `/preset/load <name>` und `/preset/save <name>` als OSC-String an 8001. Drei Dinge daran sind nicht offensichtlich:
- **Die Liste kommt vom Dateisystem, nicht per OSC.** `server.py` läuft auf derselben Maschine wie imPulse und liest `data/presets/` direkt (`--presets`, Vorgabe `presets/` neben `--settings`). Ein OSC-Rückkanal wäre neu zu bauen — die einzige Ausgangsadresse des Sketches ist 8002. Geschrieben wird der Ordner weiterhin ausschliesslich von imPulse; deshalb gibt es im UI auch kein Löschen.
- **Nach dem Laden zieht der Server die Regleranzeige nach**, indem er dieselbe Preset-Datei mit `parse_settings()` liest — das geht, weil Preset- und `remoteSettings.txt`-Format identisch sind. Die Werte gehen in der HTTP-Antwort zurück und werden im Browser *still* gesetzt (kein zweites OSC); geklemmt wird auf die Range aus `remoteSettings.txt`, nicht auf die aus der Preset-Datei — dieselbe Regel wie `PresetStore.applyPreset()`. Adressen, die der Dump nicht kennt, und Werte ausserhalb der verengten UI-Range (`UI_RANGE_OVERRIDES`) nennt die Statuszeile, statt sie zu verschlucken.
- **Speichern wartet auf die Datei.** `/preset/save` ist asynchron (imPulse schreibt erst im nächsten `draw()`), also pollt der Endpoint bis zu 1 s auf eine geänderte `mtime` und antwortet sonst mit 504 statt Erfolg zu behaupten — der häufigste Fehlerfall ist „Web-UI läuft, imPulse nicht". `valid_preset_name()` in `server.py` und die Regex im JS spiegeln `PresetStore.isValidName()`; Autorität bleibt Java, dort geht es um Pfad-Traversal.

Start (Details, Windows-Scheduled-Task `WebUiRun` und Firewall-Hinweise in `webui/README.md`):

```bash
cd webui && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/python server.py
```

Für die processing-unabhängige Logik (Parser, Normalisierung, Gruppierung, Kopplung, OSC-Encoder) gibt es eine eigene Suite ohne Fremdabhängigkeiten: `python3 webui/test_webui.py`.

### Netz-Topologie (LedStripeNetworks.java, NodeCrossingStore.java)

`data/nodeCrossings.txt`: eine Zeile pro Node, darin leerzeichengetrennte **globale LED-Indizes**, die sich physisch kreuzen. `NodeCrossingStore.load()` liest und validiert die Datei (Validierung, Undo, Datei-I/O — bewusst ohne Processing- und Netzabhängigkeit, siehe `test/NodeCrossingStoreTest.java`). `LedInNetInfo.applyCrossings(...)` baut daraus die `LedNetworkNode`-Objekte und setzt bei jeder beteiligten LED `LedInNetInfo.partOfNode` — **in-place** auf derselben Liste/denselben `LedInNetInfo`-Objekten, weil `LedNetworkTransportEffect` und `LedNetworkNodeEffects` dieselbe Instanz halten. Über `partOfNode` erkennt der Transport-Effekt einen Node-Treffer in O(1). `applyCrossings` wird sowohl beim Start (`setup()`) als auch zur Laufzeit aus der Kalibrierung (Taste `R`, siehe unten) aufgerufen. Ältere Aufnahmen anderer Geometrien liegen als `data/nodeCrossings_16x720.txt` und `data/nodeCrossings_35C3.txt` daneben (siehe „Node-Kalibrierung"), werden aber nicht geladen.

### Impuls-Simulation (LedNetworkTransportEffect.java)

Jeder Impuls ist eine `TravellingActivation` (Position als float, Stripe, Geschwindigkeit inkl. Vorzeichen = Richtung, Energie). Pro Frame wird der Zeitschritt aus `System.currentTimeMillis()` gebildet — die Simulation hängt an der Wanduhr, nicht am Framecount.

Jede `TravellingActivation` trägt ausserdem eine fortlaufende `id` (`final`, vergeben aus `nextImpulseId++` im äusseren Objekt) — die Kennung, unter der der Impuls im Positionsstrom `/net/impulse` auftaucht. Vergeben wird sie an **genau einer** Stelle, dem delegierenden Konstruktor, damit keine der acht Konstruktionsstellen sie vergessen kann; alle laufen auf dem Animationsthread (`digestMessage` wird nur aus `distributeMessages()` am Anfang von `draw()` gerufen), der Zähler braucht also keine Synchronisierung. Ein Überlauf nach 2^31 Impulsen ist hingenommen.

Zwei Mechanismen, die man beim Ändern kennen muss:
- **Filler**: Bei hoher Geschwindigkeit überspringt ein Impuls LEDs zwischen zwei Frames. Für die übersprungenen Positionen werden `TravellingActivationFiller` erzeugt, gezeichnet und am Ende desselben Frames wieder entfernt. Ein Filler übernimmt die `id` seines Elternimpulses, statt eine neue zu verbrauchen — strukturell erzwungen, weil die Filler-Klasse nur den Konstruktor mit ausdrücklicher ID anbietet. Im Positionsstrom werden Filler zusätzlich explizit übersprungen, sonst sähe die Klangseite einen einzigen Impuls, der im selben Takt zwischen mehreren Positionen hin- und herspringt.
- **nodeDeadTime**: Ein Node feuert erst wieder nach `/net/impulse/nodeDeadTime` Sekunden. Ohne diese Totzeit würde ein Impuls denselben Node in aufeinanderfolgenden Frames endlos neu triggern.

Bei einem Node-Treffer erhält jeder Zweig aktuell die **volle** Energie des Elternimpulses (`childEnergy = curActivation.energy`) — ein bewusster Quick-Fix, jede Aufspaltung vervielfacht also die Gesamtenergie. Die auskommentierte Zeile darüber zeigt die energieerhaltende Variante.

**Ambient/idle Random-Spawns** (`spawnRandomImpulses()`, aufgerufen aus `drawMe()`):
unabhängig von `/tube/trigger` und Node-Kettenreaktionen spawnt der Effekt in
regelmäßigen (oder verjitterten) Abständen zufällige Impulse am Anfang
zufällig gewählter Stripes — Standard-Zustand ist seit 2026-07-30 **an**
(`enabled=1`, Klangnetz ist eine nicht-interaktive Installation), ein
Operator schaltet es live per OSC ab. Alle Parameter live tunbar, folgen dem
üblichen `RemoteControlled*Parameter`-Muster, tauchen also automatisch in
`remoteSettings.txt` auf:
- `/net/randomSpawn/enabled` (int 0/1) — ganz abschaltbar ohne Neustart
- `/net/randomSpawn/count` (int, 1..nStripes) — Anzahl Stripes pro Spawn-Event (Ziehen ohne Zurücklegen)
- `/net/randomSpawn/interval` (float, 0.05..40s, default 30) — Sekunden zwischen Spawn-Events
- `/net/randomSpawn/energy` (float, 0..1, default 0.6) — Energie je gespawntem Impuls
- `/net/randomSpawn/directionBias` (float, 0..1, default 1) — Wahrscheinlichkeit für "vorwärts"; rückwärts spawnt bewusst am anderen Stripe-Ende, sonst fällt der Impuls sofort aus den Bounds
- `/net/randomSpawn/jitter` (float, 0..1, default 0) — 0 = exakt periodisch, 1 = Intervall stark verjittert (0..2× `interval`)

Geschwindigkeit kommt bewusst von `impulseSpeed` (kein eigener Speed-Parameter),
damit random gespawnte und tube-getriggerte Impulse gleich schnell wirken.
Gespawnte Impulse sind normale `TravellingActivation`-Objekte und laufen
durch dieselbe Node-Kollisions-/Energie-Decay-/Render-Pipeline wie alle
anderen Impulse.

**Sinus-Randomizer für Speed und Lifetime** (`ParameterOscillator.java`,
angewandt in `applyRandomizers()` am Anfang von `drawMe()`): `/net/impulse/speed`
und `/net/impulse/lifetime` können ihren Wert automatisch zwischen einem Min und
Max hin- und herschwingen lassen, statt fest zu stehen — damit sich die Optik bei
vielen gleichzeitig aktiven Impulsen von selbst leicht verändert, ohne zu
springen (Birk, 2026-07-31). Beide Randomizer sind **unabhängig**, es gibt keinen
gemeinsamen Takt; je vier Parameter, Auslieferungszustand **aus**:

- `/net/impulse/speed/randomize/enabled` (int 0/1, default 0), `.../min` (int,
  1..1500, default 16), `.../max` (int, 1..1500, default 160), `.../period`
  (float Sekunden, 1..300, default 30)
- `/net/impulse/lifetime/randomize/enabled` (int 0/1, default 0), `.../min`
  (float, 0.0001..1, default 0.005), `.../max` (float, 0.0001..1, default 0.05),
  `.../period` (float Sekunden, 1..300, default 20)

Formel (`ParameterOscillator.sineOscillate`, reine Mathe, deshalb in
`test/run.sh` geprüft — `ParameterOscillatorTest`):
`wert = min + (max-min) * (0.5 + 0.5*sin(2*PI*t/period))`. `period` ist die Dauer
eines vollen Auf-Ab-Zyklus in **Sekunden**, nicht Hz. `t` läuft **ab dem
Einschalten**, nicht ab Sketch-Start: jeder Randomizer beginnt damit
reproduzierbar in der Mitte seines Bereichs und steigt: Ausschalten setzt die
Phase zurück (`reset()`).

Drei Dinge, die man beim Ändern kennen muss:
- Bei `enabled=1` wird der **zugrundeliegende Parameter selbst** per
  `setValue()` überschrieben, nicht ein Schattenwert nur für den Frame. Nur so
  steht der gerade gefahrene Wert auch in `remoteSettings.txt` und in einem
  gespeicherten Preset. Ein manuelles Nachjustieren während `enabled=1` wird im
  nächsten Frame wieder überschrieben — gewollt. Der **Web-UI-Regler folgt
  trotzdem nicht live**: es gibt keinen OSC-Rückkanal zum UI, die Anzeige zeigt
  weiter den zuletzt gesendeten Wert. Sichtbar wird der oszillierende Wert erst
  im nächsten Dump/Preset.
- Die Ranges von `min`/`max` sind bewusst identisch mit denen des jeweiligen
  Zielparameters, deshalb kann der Oszillator dort nichts Ungültiges setzen.
  `sineOscillate` liefert bei unbrauchbarer Periode (0, negativ, NaN) die Mitte
  zwischen min und max statt NaN.
- Bei `enabled=0` ändert sich am bisherigen Verhalten nichts.

Im Web-UI bilden die acht Adressen eine eigene Sektion „Impuls-Randomizer
(Sinus)" zwischen `/net/impulse` und Impuls-Farbe. Das ist ein Eintrag in
`SPLIT_GROUP_PREFIXES` (`server.py`) und **kein** Selbstläufer: die generische
Präfix-Regel schneidet nach zwei Segmenten ab, ohne den Sonderfall stünden alle
acht Regler zwischen den vier Reglern, die sie steuern.

### Preset-System (PresetStore.java, PresetScheduler.java, PresetManager.java)

Ein Preset ist ein **kompletter** Wertesatz aller 48 fernsteuerbaren
Parameter-Adressen, abgelegt als `data/presets/<name>.txt` im **gleichen
Format wie `remoteSettings.txt`** (sechs Tab-Spalten: typ, adresse,
beschreibung, wert, min, max). Deshalb liessen sich die beiden
live verifizierten Szenen-Snapshots aus `scenes/` per Kopie zu Presets machen.

Drei Ladewege:
1. OSC auf Port 8001: `/preset/load <name>`, `/preset/save <name>`,
   `/preset/next`. Kein `/preset/list` — `ls data/presets/` beantwortet das
   von aussen, und ein OSC-Rückkanal wäre neu zu bauen (die einzige
   Ausgangsadresse ist 8002, also SuperCollider). Meldungen gehen per
   `println` auf die Konsole.
2. Beim Start: Sketch-Argument, sonst Umgebungsvariable `IMPULSE_PRESET`.
   Geladen wird in `setup()` **nach** dem Anlegen der Effekte und **vor** dem
   Schreiben von `remoteSettings.txt` — diese Datei zeigt danach den wirklich
   gefahrenen Stand statt der Code-Defaults.
3. Scheduler: `/preset/scheduler/enabled` (int 0/1, Default **0**) und
   `/preset/scheduler/interval` (float Sekunden, Default 600). Reihenfolge
   alphabetisch nach Dateiname, Liste bei jedem Wechsel frisch gelesen.
   Einschalten springt **nicht** sofort — der Timer läuft ab jetzt.

**Was nicht in ein Preset gehört und warum:**
- `/net/activateNode` und `/net/activateStripe` sind **Kommandos**, keine
  Parameter: `LedNetworkTransportEffect` registriert sie selbst als
  `OscMessageSink` und feuert sofort beim Eintreffen, schreibt sie aber über
  sein eigenes `writeToStream()` mit in `remoteSettings.txt`. Der Ausschluss
  ist **strukturell**: nur die drei `RemoteControlled*Parameter`-Klassen
  implementieren `PresetTarget`. Beim Laden werden die zwei Adressen zusätzlich
  still übergangen (`PresetStore.SILENTLY_IGNORED`), damit eine handkopierte
  `remoteSettings.txt` nicht bei jedem Laden zwei Warnungen erzeugt.
- Die zwei Scheduler-Parameter (`PresetStore.EXCLUDED`) — sie sind Transport,
  nicht Inhalt. Sonst könnte ein mit `enabled=0` gespeichertes Preset die
  Installation einfrieren.
- Die Netz-Topologie (`nodeCrossings.txt`) — das ist Kalibrierung.

**Die Falle, die den Entwurf bestimmt:** eingehende Float-OSC-Werte werden von
`0..1` auf `min..max` gestreckt (`AbstractParameter.java`, `digestMessage`).
Ein gespeicherter Absolutwert lässt sich deshalb **nicht** als OSC
zurückschicken — `/net/impulse/nodeDeadTime` (0..10) landete bei
gespeichertem `1.0` als `10.0`. Presets gehen stattdessen über
`PresetTarget.applyPreset(address, value)`, das absolut setzt und auf die
Grenzen **aus dem Code** klemmt, nicht auf die aus der Datei. Die
Threading-Regel bleibt gewahrt: der Befehl läuft weiter durch die Queue und
wird dort nur vermerkt, gelesen und angewendet wird in `draw()`.

**Aufteilung:** `PresetStore` (Format, Datei, Snapshot, Anwenden) und
`PresetScheduler` (Zeitlogik) sind frei von Processing und OSC und deshalb in
`test/run.sh` geprüft (`PresetStoreTest`, `PresetSchedulerTest`).
`PresetManager` kennt oscP5 und darf **nicht** in `test/run.sh` aufgenommen
werden — die Suite hat nur `core.jar`.

**Sound:** imPulse ist Master. Bei jedem Wechsel geht zusätzlich
`/sc/preset/load <name>` an `127.0.0.1:8002` — derselbe Port, auf dem
SuperCollider schon `/net/hitNode` empfängt, nur eine neue Adresse. Es gibt
genau einen Scheduler, deshalb können Licht und Klang nicht auseinanderlaufen.
Fire-and-forget: läuft sclang nicht, läuft die Visual-Show weiter.

### Ausgabepfade

Beide Pfade sind in `setup()`/`draw()` per Kommentar umschaltbar:
1. **ArtNet direkt** (aktiv): `ArtNetOutput` sendet unicast an `2.2.2.<octet>` für jeden Eintrag in `controllerOctets` (15 Controller, je zwei Outputs, Konvention `octet*100` als Start-Universum). Jede LED belegt 4 Byte, macht 128 LEDs je Universum (512/4) und bei 600 LEDs je Output 5 Universen (das letzte nur zu 88 LEDs gefüllt, der Rest genullt). Die Reihenfolge dieser 4 Byte ist R, G, B, 0 — Byte 0 steuert Rot, Byte 1 Grün, Byte 2 Blau, Byte 3 bleibt ungenutzt. Gemessen am 2026-07-30 an der echten Installation, mit Startmarke in Weiss und einer einmaligen Farbfolge (eine saubere Zeitbasis). Eine Ableitung aus der Firmware führt hier in die Irre: Liest man die vier DMX-Bytes als little-endian `uint32` und übergibt sie an `CRGB(word)`, legt das B, G, R nahe — zwei unabhängige Analysen kamen so zu einem falschen Ergebnis. Massgeblich ist die Messung, nicht die Herleitung. Siehe `R_OFFSET`/`G_OFFSET`/`B_OFFSET` in `ArtNetOutput.java`. Nach den Universen eines Controllers folgt zwingend ein **ArtSync**-Paket (OpSync) — ohne das würde die Firmware jedes Universum sofort beim Empfang anzeigen und es käme zum Reissen/Flackern zwischen den Outputs. Der Versand läuft in einem eigenen 40-Hz-Sender-Thread mit Dreifachpufferung (`buildBuf`/`readyBuf`/`sendBuf`, siehe `ArtNetOutput.publish()`/`start()`), damit `draw()` nie auf das Netz wartet. `describeMapping()` gibt beim Start eine Zuordnungstabelle auf der Konsole aus.
2. **Syphon/Spout** (auskommentiert): `canvas` ist ein `PGraphics` mit Breite = LEDs pro Stripe, Höhe = Anzahl Stripes; jedes Pixel ist eine LED. Wird als Textur an MadMapper geschickt (`congress19.mad` ist das zugehörige MadMapper-Projekt, Syphon-Server-Name `Lightstrument`). Windows nutzt Spout, macOS Syphon — die jeweils andere Zeile ist auskommentiert.

`canvas` wird unabhängig davon immer befüllt und als Preview ins Sketch-Fenster gezeichnet — die Vorschau zeigt allerdings volle Helligkeit, der Master-Pegel (siehe unten) wirkt nur auf die Hardware-Ausgabe.

`dispose()` in `imPulse.pde` wird von Processing beim Beenden aufgerufen: veröffentlicht einen komplett schwarzen Frame, wartet kurz, damit der Sender-Thread ihn noch verschickt, und ruft danach `artNetOutput.stop()`. Ohne das blieben die Stripes im letzten gesendeten Bild stehen, weil die Empfänger-Firmware nicht von selbst blankt.

### Master-Pegel: ein Show-Fader (0..1), Testbilder dämpfen sich selbst

`masterLevel` (`/master/level`, `RemoteControlledFloatParameter`) ist seit
2026-07-30 der **Show-Fader**, Bereich **0..1** (`new
RemoteControlledFloatParameter("/master/level", 0.1f, 0f, 1f)` in
`imPulse.pde`) — die Installation fährt die Stripes im Betrieb bewusst nie auf
Vollweiss, das Hardware-Risiko (Spannungsabfall bei Weiss auf 10 m Länge laut
Handbuch der Stripes) betraf nur die Kalibrier-Testbilder, nicht den
Show-Content.

Seit 2026-07-31 gilt der Fader **ungefiltert für jede Betriebsart** — Show,
Kalibrierung, Positionsmodus. `draw()` ruft schlicht
`artNetOutput.setMasterLevel(masterLevel.getValue())`; das frühere
`calibrationMode ? CALIBRATION_MASTER_LEVEL : ...` ist weg, ebenso die
Konstante. Grund: die Kalibrieransicht zeigt nur einzelne Punkte und zwei mit
6 % eingefärbte Cursor-Stripes und soll bei der Aufnahme hell sein dürfen; ein
Modus-Pegel kann sie nicht von den flächigen Testbildern trennen, die im
selben Modus liegen.

Das Sicherheitsventil sitzt stattdessen dort, wo die Helligkeit entsteht:
`TestPatterns.PATTERN_LEVEL = 0.1f`, angewandt in der privaten Hilfsmethode
`lit(r, g, b)`, durch die **jede** Farbe jedes Musters geht. Kein Muster baut
sein `LedColor` selbst — sonst wäre der Pegel nur eine Konvention, an die ein
später ergänztes Muster sich erinnern müsste. `test/TestPatternsTest.java`
hält genau das nach (kein Kanal über `PATTERN_LEVEL`, und Muster 5 reizt ihn
tatsächlich aus — ein versehentlich schwarzes Muster soll die Prüfung nicht
bestehen).

Die Sicherheitseigenschaft wird dadurch **strenger**, nicht schwächer: der
Master multipliziert weiterhin obendrauf und ist auf 0..1 geklemmt
(`setMasterLevel()`, siehe `test/ArtNetOutputTest.java`), ein Testbild kommt
also nie über 10 % heraus — der Fader kann es nur noch dunkler machen.

Folge für `test/PatternProbe.java`: dessen `MASTER_LEVEL` steht jetzt auf
**1.0**, nicht mehr auf 0.1. Zwei Dämpfungen hintereinander ergäben 0.01, also
2 von 255 — die Testbilder wären am Aufbau praktisch unsichtbar.

### Node-Kalibrierung

**Handlungsanleitung für die Aufnahme steht in `docs/kalibrierung-anleitung.md`** — Vorgehen Schritt für Schritt, Gegenprüfen ohne Neustart, Korrigieren, Fallstricke. Der Abschnitt hier beschreibt das Werkzeug, die Anleitung den Ablauf.

`NodeCalibration` ist das Werkzeug, um `data/nodeCrossings.txt` von Hand aufzunehmen — zwei Cursor (A/B) statt des früheren Dropdown-Menüs mit sieben Modi. Ein/Aus mit `c`/`C` (`calibrationMode` in `imPulse.pde`); solange aktiv, überschreibt `nodeCalibration.drawMe()` komplett die Mixer-Ausgabe. Die Vorschau zeigt beide Cursor-Stripes schwach eingefärbt (Cursor A grün, Cursor B rot), darüber je ein heller Punkt an der aktuellen LED-Position; ein HUD unterhalb der Vorschau (`hudText()`, Fensterhöhe extra dafür vorgesehen, siehe „Konventionen") zeigt Cursorstände, geladene/neue Node-Zahl, Schrittweite und die letzte Meldung.

Tastenbelegung (nur wirksam im Kalibriermodus, ausser `c`/`C` selbst):
- **Pfeiltasten**: aktiven Cursor bewegen (links/rechts = LED-Index, hoch/runter = Stripe)
- **TAB**: zwischen Cursor A und B umschalten
- **ENTER**: aktuelles Cursorpaar als Kreuzung übernehmen. `NodeCrossingStore.add()` validiert (Bereich, identische LED, Mindestabstand bei gleichem Stripe, Duplikate) und lehnt sonst mit Begründung ab — die Ablehnung erscheint sowohl im HUD als auch **zusätzlich auf der Konsole**, falls das HUD gerade nicht lesbar ist
- **BACKSPACE**: die zuletzt in dieser Sitzung hinzugefügte Kreuzung zurücknehmen. Schützt bewusst die beim Start **geladenen** Einträge — die sollen sich nicht versehentlich wegklicken lassen. `S` sperrt das Zurücknehmen **nicht**: `save()` lässt `loadedCount` unangetastet, die Sitzungseinträge bleiben also auch nach dem Speichern zurücknehmbar (festgehalten in `test/NodeCrossingStoreTest.java`)
- **`,` / `.`**: durch die Kreuzungsliste blättern (`NodeSelection`, siehe `test/NodeSelectionTest.java`). Der ausgewählte Eintrag blinkt im Netz **weiss**, unabhängig von `N`; das HUD nennt Position und ob er geladen oder neu ist. `.` bleibt am Listenende stehen (kein Umlauf), `,` hebt die Auswahl am Listenanfang auf
- **X**: den ausgewählten Eintrag löschen — anders als BACKSPACE **auch einen geladenen**, und an beliebiger Stelle der Liste. Setzt vorher beide Cursor auf dessen zwei LEDs, sodass aus dem Löschen ein Korrigieren wird: nachfahren, ENTER, fertig. Bewusst **ohne** Doppelbestätigung (anders als `L`), weil ein einzelner ausgewählter und sichtbar blinkender Eintrag absichtsvoll genug getroffen wird und die Datei sich erst beim nächsten `S` ändert. `NodeCrossingStore.removeAt()` verschiebt dabei `loadedCount`, sonst zählte ein Sitzungseintrag als geladen und BACKSPACE käme nicht mehr an ihn heran
- **F**: Schrittweite der Pfeiltasten durchschalten (1/10/100)
- **S**: komplette Liste nach `data/nodeCrossings.txt` schreiben (atomar über Temp-Datei + Rename, **kein** Anhängen — mehrfaches Speichern verdoppelt nichts)
- **R**: `LedInNetInfo.applyCrossings(...)` zur Laufzeit neu anwenden, ohne Neustart des Sketches
- **N**: geladene (magenta) und neue (cyan) Kreuzungen einblenden/ausblenden
- **0–5**: Testbilder umschalten — `0` ist die Kalibrieransicht selbst, `1`–`5` die fünf Abnahme-Testbilder aus `TestPatterns.java` (dieselbe Logik wie `test/PatternProbe.java`, keine zweite Implementierung): 1 ein Stripe nach dem anderen, 2 Lauflicht über den Cursor-Stripe, 3 nur die letzten vier LEDs jedes Stripes, 4 die einmalige Farbfolge Weiss/Rot/Grün/Blau, 5 alle Stripes flächig **grün** (seit 2026-07-31, vorher Vollweiss — grün zieht nur einen Kanal auf, also ein Drittel der Last auf denselben 18 000 LEDs). Alle fünf dämpfen sich selbst auf `PATTERN_LEVEL`, siehe „Master-Pegel". `imPulse.pde` ruft `nodeCalibration.setPattern(0)` bei **jedem** Umschalten des Kalibriermodus mit `C` — egal ob rein oder raus —, sonst würde ein Wiedereintritt das zuletzt gewählte Testbild statt der Kalibrierung zeigen
- **L**: **alle** Kreuzungen verwerfen, auch die geladenen — für den Fall, dass eine Kalibrierung aus einer anderen Geometrie stammt und `BACKSPACE` sie (bewusst) nicht anfasst. Erfordert eine ausdrückliche Bestätigung: erster Druck kündigt die Anzahl an, ein zweiter Druck zwischen 300 ms und 5 s danach führt `NodeCrossingStore.clearAll()` aus. Jede andere Taste — Pfeiltasten und das Umschalten des Kalibriermodus mit `C` eingeschlossen — verwirft eine angekündigte Bestätigung wieder, die Untergrenze von 300 ms wehrt ausserdem Tastenwiederholung bei gehaltenem `L` ab

`data/nodeCrossings_16x720.txt` ist die Topologie der vorigen 16×720-Geometrie (aufgehoben als Beleg, nicht geladen), `data/nodeCrossings_35C3.txt` die der 35C3-Installation davor.

### LED-Positionen und Spatialisierung

**Handlungsanleitung für die Aufnahme steht in `docs/positionen-anleitung.md`** — Vorgehen, Gegenprüfen ohne Neustart, Korrigieren, Fallstricke. Der Abschnitt hier beschreibt das Werkzeug und die Rechnung dahinter.

Jede LED bekommt eine Position in der **Draufsicht**, in Metern. Ursprung ist der Punkt senkrecht unter der Netzmitte, X zeigt nach rechts, Y nach vorn; die Grundfläche ist `footprintX = 14f` × `footprintY = 8f` Meter (Felder in `imPulse.pde`, installationsspezifisch wie `controllerOctets`). Kein z: das Netz hängt über Kopf, vier Lautsprecher in einer Ebene können die Höhe ohnehin nicht darstellen. `stripeLengthM = 10f` (2 × 5 m je Output) ergibt zusammen mit `numLedsPerStripe` den LED-Abstand `ledPitchM`.

**Anker** heisst eine von Hand gesetzte Position. Nur sie steht in der Datei; alles andere wird gerechnet.

`data/ledPositions.txt`: eine Zeile je Anker, `ledIndex x y` — globaler LED-Index, dann zwei Meterwerte mit **Dezimalpunkt** (`String.format` mit `Locale.US`, sonst schriebe eine deutsche Locale ein Komma und machte die Datei unlesbar). `#` leitet einen Kommentar ein, der Kopf wird beim Speichern neu geschrieben. Fehlerhafte Zeilen und Positionen ausserhalb der Grundfläche werden gemeldet und übersprungen, nicht als Absturz weitergereicht — dasselbe Verhalten wie bei `nodeCrossings.txt`.

**Der Schlüssel ist der LED-Index, nicht die Knoten-Nummer.** Damit bleiben alle Positionen gültig, wenn sich die Kreuzungsliste ändert: eine physische LED wandert nicht, wenn eine Kreuzung nachgetragen oder korrigiert wird. Bei Knoten-Nummern würde `NodeCrossingStore.removeAt()` alle folgenden Positionen verschieben. Beide LEDs einer Kreuzung stehen deshalb als zwei Zeilen mit derselben Position in der Datei.

Vier Klassen, alle vier **ohne Processing-, oscP5- und netP5-Abhängigkeit** und damit über `test/run.sh` prüfbar — dasselbe Muster, aus dem in diesem Projekt schon `ArtNetOutput`, `NodeCrossingStore` und `NodeSelection` herausgezogen sind:

- `LedAnchorStore` — hält die Anker, validiert Bereich und Grundfläche, verteilt eine gesetzte Position auf **alle** LEDs der betroffenen Kreuzung (eine Kreuzung ist ein physischer Punkt, das ist keine Schätzung), liest und schreibt die Datei atomar. Die Kreuzungsliste wird hereingegeben statt gehalten, damit der Store ohne eigene Kenntnis der Topologie auskommt. Statt eines `loadedCount`-Grenzindex hält er eine **Menge** geladener Schlüssel — die Falle aus `NodeCrossingStore.removeAt()` wird so strukturell unmöglich statt behandelt.
- `LedPositionMap` — rechnet daraus die Position jeder einzelnen LED: zwischen zwei Ankern interpoliert, jenseits des äussersten der Vektor der äussersten **zwei** fortgesetzt (nicht der vom ersten zum letzten), geklemmt auf die Grundfläche. Ein einzelner Anker legt den ganzen Stripe auf diesen Punkt; ohne jeden Anker liefert `positionOf` `false`. `apply()` rechnet einmal alles vor, der heisse Pfad im Transport-Effekt liest nur noch die Arrays. `coverageReport()` nennt undefinierte und nur extrapolierte LEDs sowie die Stripes ohne jeden Anker.
- `LedPositionCalibration` — das Erfassungswerkzeug: Arbeitsliste, Zeiger, Umrechnung Pixel↔Meter, Befehle, Rückmeldung im Netz. Ein **Eintrag** der Arbeitsliste ist ein physischer Punkt, nicht eine LED: jede Kreuzung ein Eintrag (mit zwei oder mehr LEDs), dazu Anfang und Ende jedes Stripes, sofern die nicht schon zu einer Kreuzung gehören. Sortiert nach kleinstem LED-Index — eine Kreuzung steht damit im Abschnitt des Stripes mit der **niedrigeren** Nummer, im anderen taucht sie nicht noch einmal auf.
- `ImpulseOscThrottle` — Sendetakt und Auswahl für `/net/impulse`, siehe „OSC-Parametersystem".

Der **Vorschlag**, den das Werkzeug anzeigt, *ist* das Ergebnis von `LedPositionMap.positionOf` — es gibt bewusst keinen zweiten Rechenweg für „geschätzte" Positionen und damit keine zweite Wahrheit, die auseinanderlaufen könnte.

Ein/Aus mit `p`/`P` (`positionMode` in `imPulse.pde`); solange aktiv, ersetzt `ledPositionCalibration.drawMe()` komplett die Mixer-Ausgabe. `p` und `c` **schliessen sich gegenseitig aus** — beide belegen `,` `.` `S` `R` `F` `L`, der zuletzt eingeschaltete gewinnt. Beim Eintritt in den Positionsmodus läuft `reapply()`, weil sich die Kreuzungsliste im Kalibriermodus geändert haben kann. Wie jede Betriebsart läuft er auf dem Show-Fader `masterLevel` (siehe „Master-Pegel") — das Netz zeigt hier nur schwache Farbflächen und den blinkenden Abschnitt des aktuellen Eintrags, für die Aufnahme darf der Fader also hoch.

Der aktuelle Eintrag blinkt nicht als einzelne LED, sondern als Abschnitt von `2 * MARK_RADIUS + 1 = 25` LEDs (rund 42 cm) um sie herum — `markSpan()` in `LedPositionCalibration.java`. Eine einzelne LED ist am Netz aus ein paar Metern nicht zu finden. Der Abschnitt hat immer dieselbe Länge und wird am Stripe-Ende nach innen **geschoben** statt abgeschnitten, damit die Markierung dort genauso gross ist; der Anker sitzt dann an seinem Rand statt in der Mitte. Er endet zwingend am eigenen Stripe — liefe er in den Nachbarn, markierte er dort einen Punkt, den es nicht gibt, und das sähe beim Einmessen aus wie ein echter Treffer.

Das Fenster zeigt links die **Draufsicht-Fläche** bei `(paneX, paneY, paneW, paneH) = (0, 0, 525, 300)` px — 525:300 entspricht 14:8 genau, es gibt also keine Verzerrung, ein Pixel sind 2,67 cm. Gezeichnet werden 1-m-Raster, die vier Lautsprecher, alle gesetzten Anker, der Verlauf des aktuellen Stripes und der aktuelle Eintrag (gefüllt = gesetzt, hohl = nur Vorschlag). Rechts daneben sitzt die verkleinerte LED-Vorschau (`image(canvas, 560, 0, 600, 120)`). Das HUD sitzt **darunter**, aber unterhalb der Draufsicht-Fläche links im Fenster (`text(..., 10, paneY + paneH + 20)`), nicht unter der Vorschau. Gezeichnet und angeklickt wird über dieselbe Umrechnung (`worldToPane`/`paneToWorld`), Klicks ausserhalb der Fläche werden verworfen.

Tastenbelegung (nur wirksam im Positionsmodus, ausser `p`/`P` selbst):
- **Maus** (Klick oder Ziehen): Position des aktuellen Eintrags setzen — bei einer Kreuzung beide LEDs auf einmal
- **ENTER**: den angezeigten Vorschlag als Anker übernehmen
- **Pfeiltasten**: die angezeigte Position um eine Schrittweite verschieben. Steht der Eintrag noch auf einem Vorschlag, wird er dadurch zum Anker — genau das will man, wenn ein Vorschlag nur ein Stück nachzubessern ist
- **F**: Schrittweite durchschalten (`STEP_SIZES_M = { 0.01f, 0.05f, 0.25f }`, Start bei 0,05 m)
- **BACKSPACE**: die Anker **aller** LEDs des aktuellen Eintrags löschen (nicht nur die der ersten, sonst bliebe bei einer Kreuzung die halbe Position stehen). Die Anzeige fällt danach auf den Vorschlag zurück
- **`,` / `.`**: durch die Arbeitsliste blättern
- **o**: zum nächsten noch **offenen** Eintrag hinter dem aktuellen springen. Bleibt am Ende stehen und meldet die Gesamtzahl der offenen Einträge
- **S**: `data/ledPositions.txt` schreiben (atomar über Temp-Datei + Rename, kein Anhängen)
- **R**: Positionskarte und Knotenpositionen neu rechnen und übernehmen, **und** die Arbeitsliste neu aufbauen (damit im Kalibriermodus aufgenommene Kreuzungen auftauchen). Der Zeiger bleibt dabei auf demselben physischen Punkt — gemerkt werden **alle** LEDs des Eintrags, nicht nur die kleinste, weil eine neu aufgenommene Kreuzung ihn mit einer kleineren zusammenlegen kann
- **T**: Abdeckungsbericht auf die Konsole und ins HUD
- **L**: **alle** Anker verwerfen, auch die geladenen — Bestätigung wie in der Node-Kalibrierung: erster Druck kündigt an, ein zweiter zwischen 300 ms und 5 s danach führt aus, jede andere Taste bricht ab

Rückmeldung im Netz (`drawMe()`, spätere Regel überschreibt frühere): jede LED zeigt den Zustand der Karte — **dunkel** = dieser Stripe hat keinen Anker, **rot** = nur extrapoliert, **blau** = zwischen zwei Ankern; die Stripes der beteiligten LEDs des aktuellen Eintrags glimmen **grün** (einer bei einem Stripe-Ende, zwei bei einer Kreuzung); die LEDs des aktuellen Eintrags **blinken weiss** (400 ms an/aus). Die Karte wird nur nach einer Änderung neu gerechnet (`mapDirty`), nicht in jedem Frame.

**`R` in der Node-Kalibrierung zieht die Positionen mit nach.** `LedInNetInfo.applyCrossings` baut die `LedNetworkNode`-Objekte komplett neu auf, die frischen Knoten haben also `posX/posY = 0`. Deshalb ruft `imPulse.pde` direkt danach `ledPositionCalibration.reapply()`. Ohne diese Zeile meldete `/net/hitNode` ab diesem Moment für **jeden** Knoten (0,0) — die Netzmitte, alle Stimmen auf einem Punkt — und zwar ohne Fehler, ohne sichtbares Symptom und bis zum nächsten Neustart. Nicht wegkürzen.

`LedAnchorStore` **warnt**, wenn die Luftlinie zwischen zwei benachbarten Ankern desselben Stripes den Weg entlang des Stripes um mehr als `WARN_SLACK_M = 0.5f` m überschreitet — das ist physikalisch unmöglich und heisst fast immer: falsche Netzseite angeklickt. Es **lehnt nicht ab**, anders als die Kreuzungsvalidierung: die Regel hängt an zwei Annahmen (LED-Abstand, durchgehender Strang), und stimmt eine davon vor Ort nicht, wäre ein hartes Nein ein Werkzeug, das sich mitten in der Arbeit selbst blockiert.

Fehlen beim Start Positionen, meldet `setup()` eine `WARNUNG`-Zeile mit dem Abdeckungsbericht. Die Show läuft dann wie bisher, nur ohne Raumbezug: jede betroffene Koordinate ist (0,0).

#### Klangseite (supercollider/klangnetz_bells.scd)

Vierkanal-Kette: Ambisonics 2D erster Ordnung mit Kern-UGens (`PanB2` als Encoder, **ein** `DecodeB2` am Ende), Glocken an den Knoten (`/net/hitNode`) und leise Drohnen, die den reisenden Impulsen folgen (`/net/impulse`). Lautsprecher auf den **Seitenmitten** (0,+4), (+7,0), (0,−4), (−7,0), nicht in den Ecken.

Der Klang selbst ist der vor Ort getunte Stand: Phrygisch ab A2 (`~scaleSteps`), `~minAmp`/`~maxAmp` aus dem +6-dB-Live-Abgleich, Sägezahn-Layer für den Hang-Drum-Attack, `~maxPolyphony = 24` als Voice-Stealing-Deckel gegen „command FIFO full" (der Ausfallmodus dabei ist **Stille ohne Absturz**). Der Hall sitzt **hinter** dem Decoder (`\masterReverb`, je Hardware-Kanal getrennt) und nicht in der Glocke — vor dem Encoder würde er selbst räumlich codiert und wieder zu einer Punktquelle verschmiert; `~reverbMix` steht deshalb auf 0.35 statt auf dem früheren Wert 0.15.

Zwei Dinge, die man kennen muss, bevor man dort etwas anfasst:

- **`~azimuthSign` und `~azimuthOffset` sind ausdrücklich UNGEMESSEN.** Sie brauchen vier angeschlossene Boxen und ein Paar Ohren; ein falsches Vorzeichen spiegelt das Klangbild, ein falscher Offset dreht es, und beides geht ohne Fehlermeldung durch. Die Datei bringt dafür `~testChannels.()`, `~testAzimuth.()` und `~testSweep.()` mit und beschreibt den Ablauf in ihrem Kopfblock. **Die Installation darf nicht öffnen, bevor diese Messung gemacht und mit Datum eingetragen ist.** Die Messsitzung ist interaktiv (IDE oder `sclang`-REPL) — headless unter `sclang -D` gibt es nichts, woraus man die Funktionen aufruft.
- **`DecodeB2` ignoriert sein `orientation`-Argument** auf SC 3.11.2. Gemessen am 2026-07-30 (scsynth im NRT-Modus, `DC.ar(1)`): bytegleiche Ausgabe für 0 / 0.25 / 0.5 / 1.0, als Graphkonstante wie als Synth-Control, während `PanAz` im selben Aufbau sofort reagiert. Der Decoder setzt seine Boxen damit fest auf ±45°/±135° — gegen unsere Seitenmitten also 45° daneben, und keine Umverkabelung bildet eine Drehung ab. Deshalb wird im **Encoder** gedreht, über `~azimuthOffset`. Derselbe Fall wie bei der ArtNet-Bytefolge: massgeblich ist die Messung, nicht die Herleitung. Die Messung stammt von einer anderen Maschine und ist auf dem Show-Rechner zu wiederholen (`~setOrientation.()` ist genau dafür noch da, und für nichts anderes mehr).

## Konventionen und Fallstricke

- **Klassenname ≠ Dateiname**: Alle `.java`-Dateien liegen flach im Sketch-Ordner, Processing kompiliert sie ins Default-Package. Die meisten Klassen sind package-private, mehrere pro Datei (z. B. `LedNetworkNode` + `LedInNetInfo` in `LedStripeNetworks.java`). Beim Suchen nach einer Klasse also nicht auf den Dateinamen verlassen.
- **Nicht initialisierte `PApplet`-Felder**: `Mixer.papplet`, `TemplateEffect.papplet` usw. werden nie zugewiesen und sind `null`. Über sie werden ausschliesslich *statische* `PApplet`-Helfer aufgerufen (`ceil`, `constrain`, `map`, `str`) — in Java erlaubt. Ein Aufruf einer Instanzmethode über diese Felder wirft sofort eine NPE.
- **Hardware-Konstanten** (`controllerOctets`, `numLedsPerStripe`, OSC-Ports, Master-Pegel-Obergrenze) stehen als Felder oben in `imPulse.pde` und sind installationsspezifisch — nicht ändern, ohne dass es um eine konkrete Installation geht.
- **Fenstergrösse in `size()`**: Processing erlaubt dort nur Literale, keine Variablen. Die Höhe muss von Hand zur Stripe-Zahl passen — Vorschau braucht `numStripes*10` Pixel, darunter das mehrzeilige Kalibrier-HUD (siehe Kommentar direkt bei `size(...)` in `imPulse.pde`). Der Kommentar dort rechnet nur mit den vier Zeilen des Kalibrier-HUDs; das Positions-HUD hat fünf und sitzt unter einer 525 × 300 px grossen Draufsicht-Fläche. Wer die Fensterhöhe neu herleitet, muss beides prüfen.
- **Farbwerte 0..1** durchgängig; Werte > 1 sind erlaubt und werden erst am Output geclampt (`LedColor.clamp()` wird im Mixer bewusst nicht aufgerufen).
- **SuperCollider-Presets** liegen in `supercollider/presets/<name>.txt`, im
  selben Tab-Format wie die visuellen Presets, erweitert um den Typ `ints` für
  die Tonleiter (kommagetrennt in der Wertspalte). Vorgesehen sind
  `/sc/scale/steps|rootMidi|octaves`, `/sc/amp/min|max` und die zwei globalen
  Klangregler `/sc/bell/decayScale` (streckt alle Teilton-Decays) und
  `/sc/bell/tilt` (Exponent auf die Teilton-Amps: >1 dumpfer, <1 brillanter).
  **Der empfangende Teil fehlt derzeit in `klangnetz_bells.scd`**: er stand in
  der Repo-Fassung (Commit `e50cd38`) und ging verloren, als am 2026-07-31 die
  Laptop-Kopie zur kanonischen Datei wurde. `PresetManager` schickt weiterhin
  `/sc/preset/load` an 8002, dort hört aber niemand darauf — die
  Preset-Dateien unter `supercollider/presets/` sind also aktuell tot. Was
  live steuerbar ist, ist stattdessen der Sound-Parameter-Layer unter
  `/klangnetz/param/<name>` (Adressliste im Kopf der `.scd`). Wer den
  Preset-Empfang zurückholt, holt ihn aus `e50cd38` und passt ihn an die
  Ambisonics-Fassung an (`~scaleSteps` statt `~pentatonicSteps`,
  `decayScale`/`tilt` gibt es in der SynthDef derzeit nicht).
  Die `#[...]`-Teilton-Literale in der SynthDef bleiben stehen — kein Rebuild
  beim Preset-Wechsel. Alles liegt weiter in **einem** `(...)`-Block: mehrere
  Top-Level-Blöcke hängen `sclang -D` auf. Für den SC-Teil gibt es **kein**
  Testgerüst im Repo, dort gilt manuelle Prüfung am Gerät.
- **`LedPositionCalibration` nennt bewusst kein `implements runnableLedEffect`.** Das Interface steht in `mixer.java`, das über `RemoteControlledFloatParameter` an `oscP5` hängt — eine Klasse, die es nennt, lässt sich von `test/run.sh` nicht mehr übersetzen. `imPulse.pde` ruft `drawMe()` ohnehin direkt auf und geht nie über den Mixer (genau wie bei `NodeCalibration`); das Interface wäre also nur ein Etikett, das die Prüfbarkeit kostet. Dieselbe Überlegung ist der Grund, warum `ImpulseOscThrottle` eine eigene Klasse ist statt einer Methode in `LedNetworkTransportEffect`.
- **Keine von der Kreuzungszahl abgeleitete Zahl als Literal** in Code oder Test: `data/nodeCrossings.txt` wächst während der Kalibrierung, jede fest eingetragene Anzahl von Knoten, Einträgen oder Ankern ist also am nächsten Tag falsch. Testaufbauten bauen sich ihre Kreuzungsliste selbst und rechnen ihre Erwartungen daraus.
- **`RemoteControlledIntParameter` und normalisierte Float-Fader**: der Float-Zweig von `digestMessage` (`AbstractParameter.java`) hat lange `.intValue()` **vor** `PApplet.map(...)` gerufen — ein Fader, der 0..1 als Float schickt, landete damit für jeden Wert unter 1.0 auf `minValue`; bei `/net/impulse/oscMaxCount` war das Ergebnis **Stille, die wie funktionierende Software aussieht**. Seit dem Aufräumen des Energiezerfalls nimmt der Zweig `.floatValue()` und spreizt korrekt auf min..max (Kommentar an Ort und Stelle). Der Int-Zweig (`'i'`) setzt weiterhin absolut, ohne Abbildung — eine Fernsteuerung darf für Int-Parameter also entweder echte Ganzzahlen senden (`webui/` tut das) oder normalisierte Floats, aber die zwei Wege bedeuten Verschiedenes.
- Bekannte offene Punkte stehen als To-Do-Block am Kopf von `imPulse.pde`.
