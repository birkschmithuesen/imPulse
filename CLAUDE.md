# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projekt

Processing-Sketch (Java-Modus) für die audiovisuelle Installation *imPulse*: LED-Stripes bilden ein chaotisches Netz, Kontaktmikrofone an Metallrohren erzeugen Lichtimpulse, die entlang der Stripes wandern und sich an Kreuzungen (Nodes) aufspalten, wobei sie Töne triggern.

Signalkette: `Max/MSP --OSC:8001--> Processing --Syphon--> MadMapper --ArtNet--> APA102` und zurück `Processing --OSC:8002--> Max/MSP`. Alternativ sendet Processing direkt per ArtNet (aktuell aktiv, siehe „Ausgabepfade").

## Ausführen

Kein Build-System für den Sketch selbst — reines Processing-Projekt. Für die netz- und processingunabhängigen Teile (`ArtNetOutput`, `NodeCrossingStore`, `LedStripeNetworks`, `TestPatterns`) gibt es aber eine eigene Testsuite, siehe „Tests" unten.

- **IDE**: `imPulse.pde` in Processing 3 öffnen, Play. Der Sketch-Ordner **muss** `imPulse` heissen (Processing-Konvention: Ordnername == Name der Haupt-`.pde`).
- **CLI**: `processing-java --sketch=/Users/macbook/Projekte/_gitHub/imPulse --run`

### Bibliotheken

`oscP5` (bringt auch `netP5` mit) und `Syphon` liegen als Kopie in `libraries/`. Für den **Betrieb in der Processing-IDE** löst Processing Bibliotheken **aus dem Sketchbook** (`~/Documents/Processing/libraries`) auf, nicht aus dem Sketch-Ordner — dort müssen sie per Contribution Manager installiert sein.

Für die **Übersetzungsprüfung** (`test/build.sh`) gilt das nicht mehr: das Skript übersetzt eine Kopie des Sketches in `TMPDIR` und legt die Jars aus `libraries/*/library/*.jar` in einen `code/`-Unterordner. Einen `code/`-Ordner nimmt Processing immer in den Klassenpfad, ohne Sketchbook. Das war nötig, weil `~/Documents/Processing/libraries` auf dem Entwicklungsrechner unter der macOS-Datenschutzsperre für den Documents-Ordner nicht lesbar ist und jeder Aufruf mit „No library found for netP5 / oscP5 / codeanticode.syphon" endete — auch mit abgeschalteter Sandbox. Nebenwirkung: die Prüfung hängt nicht mehr an einer Installation ausserhalb des Repos, nur noch an Processing selbst (Pfad über `IMPULSE_PROCESSING_JAVA` überschreibbar).

`artnet4j` (`ch.bildspur.artnet`) wird **nicht mehr gebraucht** — der aktive Ausgabepfad ist die selbstgeschriebene `ArtNetOutput`-Klasse, die bewusst nur an `LedColor` und der Java-Standardbibliothek hängt. `controlP5` wird ebenfalls nicht mehr gebraucht — das Dropdown-basierte Kalibrier-UI ist der Zwei-Cursor-Kalibrierung (`NodeCalibration`) gewichen, die direkt über Tastatur und das HUD im Sketch-Fenster läuft.

### Tests

`test/run.sh` übersetzt die processing- und netzunabhängigen Klassen (`LedColor`, `ArtNetOutput`, `NodeCrossingStore`, `NodeSelection`, `LedStripeNetworks`, `TestPatterns`) zusammen mit `test/*.java` gegen `core.jar` von Processing und führt sie aus. Ohne Argumente startet es alle fünf Suiten:

- `ArtNetOutputTest` — Adressrechnung und byte-genauer Paketbau, inklusive der Sicherheitsanforderung an den Master-Pegel (Auslieferungswert 0.1, Klemmung auf 0..1)
- `ArtNetDecoderTest` — Gegenprobe: ein unabhängiger Decoder setzt den LED-Puffer aus den gebauten Paketen zurück zusammen
- `NodeCrossingStoreTest` — Validierung, Undo, Laden/Speichern der Kreuzungsdatei, inklusive `clearAll()` und `removeAt()` (auch die Verschiebung von `loadedCount`, wenn ein geladener Eintrag gelöscht wird)
- `ApplyCrossingsTest` — `LedInNetInfo.applyCrossings` baut die Node-Zuordnung korrekt neu auf, auch beim wiederholten Aufruf mit weniger Kreuzungen
- `NodeSelectionTest` — der Auswahlzeiger der Kalibrierung: Blättern, Aufheben am Anfang, Klemmen bei schrumpfender Liste

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

### OSC-Parametersystem (AbstractParameter.java)

Jeder `RemoteControlled{Float,Int,Color}Parameter` registriert sich im Konstruktor selbst beim statischen `OscMessageDistributor`. Es gibt **keine** Adress-Zuordnung: der Distributor schickt jede Nachricht an *alle* Sinks, jeder Sink filtert selbst per `newMessage.checkAddrPattern(...)`.

Threading: `oscEvent()` läuft im oscP5-Thread und ruft nur `queueMessage()` (synchronized). Ausgewertet wird die Queue am Anfang von `draw()` über `distributeMessages()`. Neue Parameter also niemals direkt aus dem OSC-Callback verändern.

`data/remoteSettings.txt` wird bei **jedem Start** aus den registrierten Parametern neu geschrieben (`dumpParameterInfo`) und dient als Konfiguration der Remote-Oberfläche. Ein neuer `RemoteControlled*Parameter` taucht dort automatisch auf.

Eingehende OSC-Adressen: `/tube/trigger` (int Stripe, 1-basiert; optional float Energie), `/net/activateNode` (int), `/net/activateStripe` (int) sowie alle in `remoteSettings.txt` gelisteten Parameteradressen.
Ausgehend: `/net/hitNode` (int nodeId, float energy) an Port 8002.

### Netz-Topologie (LedStripeNetworks.java, NodeCrossingStore.java)

`data/nodeCrossings.txt`: eine Zeile pro Node, darin leerzeichengetrennte **globale LED-Indizes**, die sich physisch kreuzen. `NodeCrossingStore.load()` liest und validiert die Datei (Validierung, Undo, Datei-I/O — bewusst ohne Processing- und Netzabhängigkeit, siehe `test/NodeCrossingStoreTest.java`). `LedInNetInfo.applyCrossings(...)` baut daraus die `LedNetworkNode`-Objekte und setzt bei jeder beteiligten LED `LedInNetInfo.partOfNode` — **in-place** auf derselben Liste/denselben `LedInNetInfo`-Objekten, weil `LedNetworkTransportEffect` und `LedNetworkNodeEffects` dieselbe Instanz halten. Über `partOfNode` erkennt der Transport-Effekt einen Node-Treffer in O(1). `applyCrossings` wird sowohl beim Start (`setup()`) als auch zur Laufzeit aus der Kalibrierung (Taste `R`, siehe unten) aufgerufen. Ältere Aufnahmen anderer Geometrien liegen als `data/nodeCrossings_16x720.txt` und `data/nodeCrossings_35C3.txt` daneben (siehe „Node-Kalibrierung"), werden aber nicht geladen.

### Impuls-Simulation (LedNetworkTransportEffect.java)

Jeder Impuls ist eine `TravellingActivation` (Position als float, Stripe, Geschwindigkeit inkl. Vorzeichen = Richtung, Energie). Pro Frame wird der Zeitschritt aus `System.currentTimeMillis()` gebildet — die Simulation hängt an der Wanduhr, nicht am Framecount.

Zwei Mechanismen, die man beim Ändern kennen muss:
- **Filler**: Bei hoher Geschwindigkeit überspringt ein Impuls LEDs zwischen zwei Frames. Für die übersprungenen Positionen werden `TravellingActivationFiller` erzeugt, gezeichnet und am Ende desselben Frames wieder entfernt.
- **nodeDeadTime**: Ein Node feuert erst wieder nach `/net/impulse/nodeDeadTime` Sekunden. Ohne diese Totzeit würde ein Impuls denselben Node in aufeinanderfolgenden Frames endlos neu triggern.

Bei einem Node-Treffer erhält jeder Zweig aktuell die **volle** Energie des Elternimpulses (`childEnergy = curActivation.energy`) — ein bewusster Quick-Fix, jede Aufspaltung vervielfacht also die Gesamtenergie. Die auskommentierte Zeile darüber zeigt die energieerhaltende Variante.

**Ambient/idle Random-Spawns** (`spawnRandomImpulses()`, aufgerufen aus `drawMe()`):
unabhängig von `/tube/trigger` und Node-Kettenreaktionen spawnt der Effekt in
regelmäßigen (oder verjitterten) Abständen zufällige Impulse am Anfang
zufällig gewählter Stripes — Standard-Zustand ist **aus** (`enabled=0`), ein
Operator schaltet es live per OSC ein. Alle Parameter live tunbar, folgen dem
üblichen `RemoteControlled*Parameter`-Muster, tauchen also automatisch in
`remoteSettings.txt` auf:
- `/net/randomSpawn/enabled` (int 0/1) — ganz abschaltbar ohne Neustart
- `/net/randomSpawn/count` (int, 1..nStripes) — Anzahl Stripes pro Spawn-Event (Ziehen ohne Zurücklegen)
- `/net/randomSpawn/interval` (float, 0.05..10s) — Sekunden zwischen Spawn-Events
- `/net/randomSpawn/energy` (float, 0..1) — Energie je gespawntem Impuls
- `/net/randomSpawn/directionBias` (float, 0..1, default 0.5) — Wahrscheinlichkeit für "vorwärts"; rückwärts spawnt bewusst am anderen Stripe-Ende, sonst fällt der Impuls sofort aus den Bounds
- `/net/randomSpawn/jitter` (float, 0..1, default 0) — 0 = exakt periodisch, 1 = Intervall stark verjittert (0..2× `interval`)

Geschwindigkeit kommt bewusst von `impulseSpeed` (kein eigener Speed-Parameter),
damit random gespawnte und tube-getriggerte Impulse gleich schnell wirken.
Gespawnte Impulse sind normale `TravellingActivation`-Objekte und laufen
durch dieselbe Node-Kollisions-/Energie-Decay-/Render-Pipeline wie alle
anderen Impulse.

### Ausgabepfade

Beide Pfade sind in `setup()`/`draw()` per Kommentar umschaltbar:
1. **ArtNet direkt** (aktiv): `ArtNetOutput` sendet unicast an `2.2.2.<octet>` für jeden Eintrag in `controllerOctets` (15 Controller, je zwei Outputs, Konvention `octet*100` als Start-Universum). Jede LED belegt 4 Byte, macht 128 LEDs je Universum (512/4) und bei 600 LEDs je Output 5 Universen (das letzte nur zu 88 LEDs gefüllt, der Rest genullt). Die Reihenfolge dieser 4 Byte ist R, G, B, 0 — Byte 0 steuert Rot, Byte 1 Grün, Byte 2 Blau, Byte 3 bleibt ungenutzt. Gemessen am 2026-07-30 an der echten Installation, mit Startmarke in Weiss und einer einmaligen Farbfolge (eine saubere Zeitbasis). Eine Ableitung aus der Firmware führt hier in die Irre: Liest man die vier DMX-Bytes als little-endian `uint32` und übergibt sie an `CRGB(word)`, legt das B, G, R nahe — zwei unabhängige Analysen kamen so zu einem falschen Ergebnis. Massgeblich ist die Messung, nicht die Herleitung. Siehe `R_OFFSET`/`G_OFFSET`/`B_OFFSET` in `ArtNetOutput.java`. Nach den Universen eines Controllers folgt zwingend ein **ArtSync**-Paket (OpSync) — ohne das würde die Firmware jedes Universum sofort beim Empfang anzeigen und es käme zum Reissen/Flackern zwischen den Outputs. Der Versand läuft in einem eigenen 40-Hz-Sender-Thread mit Dreifachpufferung (`buildBuf`/`readyBuf`/`sendBuf`, siehe `ArtNetOutput.publish()`/`start()`), damit `draw()` nie auf das Netz wartet. `describeMapping()` gibt beim Start eine Zuordnungstabelle auf der Konsole aus.
2. **Syphon/Spout** (auskommentiert): `canvas` ist ein `PGraphics` mit Breite = LEDs pro Stripe, Höhe = Anzahl Stripes; jedes Pixel ist eine LED. Wird als Textur an MadMapper geschickt (`congress19.mad` ist das zugehörige MadMapper-Projekt, Syphon-Server-Name `Lightstrument`). Windows nutzt Spout, macOS Syphon — die jeweils andere Zeile ist auskommentiert.

`canvas` wird unabhängig davon immer befüllt und als Preview ins Sketch-Fenster gezeichnet — die Vorschau zeigt allerdings volle Helligkeit, der Master-Pegel (siehe unten) wirkt nur auf die Hardware-Ausgabe.

`dispose()` in `imPulse.pde` wird von Processing beim Beenden aufgerufen: veröffentlicht einen komplett schwarzen Frame, wartet kurz, damit der Sender-Thread ihn noch verschickt, und ruft danach `artNetOutput.stop()`. Ohne das blieben die Stripes im letzten gesendeten Bild stehen, weil die Empfänger-Firmware nicht von selbst blankt.

### Master-Pegel: Show-Fader (0..1) vs. Testbild-Fixpegel

`masterLevel` (`/master/level`, `RemoteControlledFloatParameter`) ist seit
2026-07-30 der **Show-Fader**, Bereich **0..1** (`new
RemoteControlledFloatParameter("/master/level", 0.1f, 0f, 1f)` in
`imPulse.pde`) — die Installation fährt die Stripes im Betrieb bewusst nie auf
Vollweiss, das Hardware-Risiko (Spannungsabfall bei Weiss auf 10 m Länge laut
Handbuch der Stripes) betraf nur die Kalibrier-Testbilder, nicht den
Show-Content. `TestPatterns` 3/5 senden bewusst `(1,1,1)` (Vollweiss) — dafür
gibt es seither einen eigenen, vom Fader unabhängigen Fixpegel
`CALIBRATION_MASTER_LEVEL = 0.1f` (Konstante in `imPulse.pde`, nicht per OSC
erreichbar, gleiche Begründung wie `test/PatternProbe.java`s `MASTER_LEVEL`),
angewandt in `draw()` via `calibrationMode ? CALIBRATION_MASTER_LEVEL :
masterLevel.getValue()`. `setMasterLevel()` selbst klemmt weiterhin defensiv
auf **0..1** (siehe `test/ArtNetOutputTest.java`) — ein zweites Sicherheitsnetz
für den Fall, dass es je von anderer Stelle mit einem rohen Wert aufgerufen
wird.

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
- **0–5**: Testbilder umschalten — `0` ist die Kalibrieransicht selbst, `1`–`5` die fünf Abnahme-Testbilder aus `TestPatterns.java` (dieselbe Logik wie `test/PatternProbe.java`, keine zweite Implementierung). `imPulse.pde` ruft `nodeCalibration.setPattern(0)` bei **jedem** Umschalten des Kalibriermodus mit `C` — egal ob rein oder raus —, sonst würde ein Wiedereintritt das zuletzt gewählte Testbild statt der Kalibrierung zeigen
- **L**: **alle** Kreuzungen verwerfen, auch die geladenen — für den Fall, dass eine Kalibrierung aus einer anderen Geometrie stammt und `BACKSPACE` sie (bewusst) nicht anfasst. Erfordert eine ausdrückliche Bestätigung: erster Druck kündigt die Anzahl an, ein zweiter Druck zwischen 300 ms und 5 s danach führt `NodeCrossingStore.clearAll()` aus. Jede andere Taste — Pfeiltasten und das Umschalten des Kalibriermodus mit `C` eingeschlossen — verwirft eine angekündigte Bestätigung wieder, die Untergrenze von 300 ms wehrt ausserdem Tastenwiederholung bei gehaltenem `L` ab

`data/nodeCrossings_16x720.txt` ist die Topologie der vorigen 16×720-Geometrie (aufgehoben als Beleg, nicht geladen), `data/nodeCrossings_35C3.txt` die der 35C3-Installation davor.

## Konventionen und Fallstricke

- **Klassenname ≠ Dateiname**: Alle `.java`-Dateien liegen flach im Sketch-Ordner, Processing kompiliert sie ins Default-Package. Die meisten Klassen sind package-private, mehrere pro Datei (z. B. `LedNetworkNode` + `LedInNetInfo` in `LedStripeNetworks.java`). Beim Suchen nach einer Klasse also nicht auf den Dateinamen verlassen.
- **Nicht initialisierte `PApplet`-Felder**: `Mixer.papplet`, `TemplateEffect.papplet` usw. werden nie zugewiesen und sind `null`. Über sie werden ausschliesslich *statische* `PApplet`-Helfer aufgerufen (`ceil`, `constrain`, `map`, `str`) — in Java erlaubt. Ein Aufruf einer Instanzmethode über diese Felder wirft sofort eine NPE.
- **Hardware-Konstanten** (`controllerOctets`, `numLedsPerStripe`, OSC-Ports, Master-Pegel-Obergrenze) stehen als Felder oben in `imPulse.pde` und sind installationsspezifisch — nicht ändern, ohne dass es um eine konkrete Installation geht.
- **Fenstergrösse in `size()`**: Processing erlaubt dort nur Literale, keine Variablen. Die Höhe muss von Hand zur Stripe-Zahl passen — Vorschau braucht `numStripes*10` Pixel, darunter das mehrzeilige Kalibrier-HUD (siehe Kommentar direkt bei `size(...)` in `imPulse.pde`).
- **Farbwerte 0..1** durchgängig; Werte > 1 sind erlaubt und werden erst am Output geclampt (`LedColor.clamp()` wird im Mixer bewusst nicht aufgerufen).
- Bekannte offene Punkte stehen als To-Do-Block am Kopf von `imPulse.pde`.
