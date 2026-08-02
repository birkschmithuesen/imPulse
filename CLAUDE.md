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

Kein Build-System für den Sketch selbst — reines Processing-Projekt. Für die netz- und processingunabhängigen Teile (`ArtNetOutput`, `NodeCrossingStore`, `LedStripeNetworks`, `TestPatterns`, `LedAnchorStore`, `LedPositionMap`, `LedPositionCalibration`, `ImpulseOscThrottle`, `MelodyGraph`, `MelodyAssigner`) gibt es aber eine eigene Testsuite, siehe „Tests" unten.

- **IDE**: `imPulse.pde` in Processing 3 öffnen, Play. Der Sketch-Ordner **muss** `imPulse` heissen (Processing-Konvention: Ordnername == Name der Haupt-`.pde`).
- **CLI**: `processing-java --sketch=/Users/macbook/Projekte/_gitHub/imPulse --run`

### Bibliotheken

`oscP5` (bringt auch `netP5` mit) und `Syphon` liegen als Kopie in `libraries/`. Für den **Betrieb in der Processing-IDE** löst Processing Bibliotheken **aus dem Sketchbook** (`~/Documents/Processing/libraries`) auf, nicht aus dem Sketch-Ordner — dort müssen sie per Contribution Manager installiert sein.

Der Ordnername der Übersetzungs-Kopie kommt aus der Haupt-`.pde`, nicht aus dem Quellordner — ein `git worktree` heisst zwangsläufig anders als der Hauptcheckout (`imPulse-split-feature` neben `imPulse`), und mit dem Ordnernamen brach `test/build.sh` dort mit „Not a valid sketch folder" ab, ausgerechnet im Arbeitsverzeichnis, in dem entwickelt wird.

Für die **Übersetzungsprüfung** (`test/build.sh`) gilt das nicht mehr: das Skript übersetzt eine Kopie des Sketches in `TMPDIR` und legt die Jars aus `libraries/*/library/*.jar` in einen `code/`-Unterordner. Einen `code/`-Ordner nimmt Processing immer in den Klassenpfad, ohne Sketchbook. Das war nötig, weil `~/Documents/Processing/libraries` auf dem Entwicklungsrechner unter der macOS-Datenschutzsperre für den Documents-Ordner nicht lesbar ist und jeder Aufruf mit „No library found for netP5 / oscP5 / codeanticode.syphon" endete — auch mit abgeschalteter Sandbox. Nebenwirkung: die Prüfung hängt nicht mehr an einer Installation ausserhalb des Repos, nur noch an Processing selbst (Pfad über `IMPULSE_PROCESSING_JAVA` überschreibbar).

`artnet4j` (`ch.bildspur.artnet`) wird **nicht mehr gebraucht** — der aktive Ausgabepfad ist die selbstgeschriebene `ArtNetOutput`-Klasse, die bewusst nur an `LedColor` und der Java-Standardbibliothek hängt. `controlP5` wird ebenfalls nicht mehr gebraucht — das Dropdown-basierte Kalibrier-UI ist der Zwei-Cursor-Kalibrierung (`NodeCalibration`) gewichen, die direkt über Tastatur und das HUD im Sketch-Fenster läuft.

### Tests

`test/run.sh` übersetzt die processing- und netzunabhängigen Klassen (`LedColor`, `ArtNetOutput`, `NodeCrossingStore`, `NodeSelection`, `LedStripeNetworks`, `TestPatterns`, `LedAnchorStore`, `LedPositionMap`, `LedPositionCalibration`, `ImpulseOscThrottle`, `ParameterOscillator`, `PresetStore`, `PresetScheduler`, `SplitVariance`, `MusicalClock`, `StripeColorDefaults`, `OriginSequencer`, `WeightedChoice`, `SpeedQuantizer`, `SplitFanout`, `SplitStagger`, `StripeTreeStore`, `EnergyLevelStore`, `SongStructureDirector`, `MelodyModes`, `MelodyGraph`, `MelodyAssigner`, `NodeMelodyStore`) zusammen mit `test/*.java` gegen `core.jar` von Processing und führt sie aus. Ohne Argumente startet es alle Suiten der Default-Liste im Skript — die vier ersten immer, die übrigen nur, wenn ihre Quelldatei vorhanden ist (ein Fehlen wird gemeldet, nicht stillschweigend übergangen):

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
- `SplitVarianceTest` — die Jitter-Formel der Split-Kinder: neutraler Auslieferungswert, Symmetrie um den Ausgangswert, die Untergrenze gegen unsterbliche Impulse, Vorzeichenerhalt bei rückwärts laufenden Kindern
- `MusicalClockTest` — die akkumulierende Beat-Phase: kein Sprung bei BPM-Wechsel, Notenwert-Intervalle, entartete BPM, Rücksprung der Wanduhr
- `OriginSequencerTest` — Feuertakt je Notenwert, `repeatCount` hält den Ursprung, `originStripeOverride`, kein Sofort-Feuern beim Wiedereinschalten, kein Nachholen nach einem Hänger, Rasterung der Notenwerte
- `StripeTreeStoreTest` — die Baum-Zuordnung: Parsen samt Kommentaren, unbekannter Baumname, Index ausserhalb des Bereichs, doppelter Stripe (letzte Zeile gewinnt), leerer Baum liefert `null`, fehlende Datei, Gegenprobe an der echten `data/stripeTrees.txt`
- `SplitFanoutTest` — die Zahl der Zweige einer Aufspaltung: der neutrale Auslieferungsfall (`weight/all=100` nimmt immer alle), die Verteilung über 100 000 Ziehungen, ein Gewicht von 0 wird nie gezogen, entartete Gewichte fallen auf „alle" zurück, Knoten mit einem/zwei/vier möglichen Zweigen (bei zwei fallen „einer weniger" und „genau einer" zusammen), nie 0 Zweige bei vorhandenen Kandidaten, und `chooseOrder` liefert verschiedene Indizes im Bereich, in wechselnder Reihenfolge, auch bei unbrauchbaren Zufallswerten
- `SplitStaggerTest` — die Warteschlange der zeitversetzten Kinder: Slot 0 hat exakt keinen Versatz, Notenwert-Intervalle samt Rasterung, fällig genau auf der Grenze, Reihenfolge nach Fälligkeit, ein Rückwärtssprung der Beat-Position verliert nichts, `MAX_PENDING` weist den neuen Eintrag ab statt einen wartenden zu verwerfen, und die Kindwerte kommen unverändert wieder heraus. Dazu die gewichtete Ziehung des Notenwerts (`pickNoteValue`): die Verteilung über 100 000 Ziehungen, ein Gewicht von 0 wird nie gezogen, nicht-prozentuale Gewichte werden normalisiert, der entartete Fall (alle 0, negativ, NaN, zu kurzes Array) fällt auf Sechzehntel zurück, und jeder gezogene Wert übersteht die Rasterung unverändert. Für die sechste Klasse „gleichzeitig" zusätzlich: `delayBeats(0, slot)` ist für **jeden** Slot 0 (Slot 0 allein wäre gerade nicht der Nachweis), sie zieht nachweislich, wenn sie gewichtet ist, kommt bei Gewicht 0 nie vor, und der Sonderwert läuft nie in die Rasterung
- `MelodyModesTest` — die acht Modi: Skalen aufsteigend und unter der Oktave, Schlüssel eindeutig und dateinamenstauglich, in jedem Modus ist der Sekundschritt das höchstgewichtete Intervall, die gewichtete Ziehung samt Verteilung über 100 000 Ziehungen, entartete Gewichte, `drawInterval` zieht beide Vorzeichen
- `MelodyGraphTest` — die Nachbarschaftsableitung: zwei Kreuzungen auf derselben Stripe mit einer dritten dazwischen sind **nicht** benachbart, ein Knoten an zwei Stripes bekommt Nachbarn aus beiden, Mehrfachkante zählt einmal, keine Schleife auf sich selbst, Dreieck-Zyklus, Hub-Schwelle und Default-Startknoten, Gegenprobe an der echten `data/nodeCrossings.txt` (nur Invarianten, keine absoluten Zahlen)
- `MelodyAssignerTest` — die BFS-Zuweisung: Startknoten in der mittleren Oktave, Determinismus, die Landmarken-Rotation nach `(tiefe+rang) mod 3` an einem konstruierten Graph nachgerechnet, kein Ton-Stapel unter Geschwistern, negative Rohwerte falten korrekt, Dreieck liefert genau eine Rückwärtskante, unerreichbare Knoten werden gezählt, `numOctaves = 1` und `0` brechen nicht, Gegenprobe über alle acht Modi
- `NodeMelodyStoreTest` — Format und Datei: Rundlauf schreiben→lesen, Kommentare und Leerzeilen, kaputte Zeilen gezählt statt geworfen, doppelter `nodeId` (letzte Zeile gewinnt), fehlende Datei, Kopfzeilen, wiederholtes Schreiben verdoppelt nichts
- `SpeedQuantizerTest` — die gewichtete Auswahl der Speed-Klasse: die Verteilung über 100 000 Ziehungen, ein Gewicht von 0 wird nie gezogen, Normalisierung nicht-prozentualer Gewichte, entartete Gewichte (alle 0, negativ, NaN)
- `PresetStoreTest` — Format, Datei, Snapshot und Anwenden eines Presets, inklusive der ausgeschlossenen und still übergangenen Adressen
- `PresetSchedulerTest` — die Zeitlogik des Preset-Wechslers: Einschalten springt nicht sofort, Reihenfolge, Intervall
- `WeightedChoiceTest` — die gewichtete Ziehung, aus `SpeedQuantizer` herausgezogen: Verteilung über 100 000 Ziehungen, Gewicht 0 wird nie gezogen, Normalisierung, Gewichte hinter `count` zählen nicht mit, entartete Eingaben (null, `count` zu gross, alle 0, negativ, NaN)
- `EnergyLevelStoreTest` — die Energie-Level-Klassifikation: Parsen samt Kommentaren, unbekanntes Level, doppelter Name (letzte Zeile gewinnt), Rückfall auf *mittel*, `presetsForLevel` liefert eine **leere Liste** statt `null`, fehlende Datei, Gegenprobe an der echten `data/energyLevels.txt` gegen `data/presets/` (jedes Preset getaggt, mindestens zwei je Level — aus dem Verzeichnis gerechnet, nicht als Zahl notiert)
- `StripeColorDefaultsTest` — die acht Auslieferungsfarben des Modus
  „Stripe-Farben": acht Slots, die 24 Kanalwerte **unabhängig abgetippt**
  (eine Prüfung, die dieselbe Tabelle gegen sich selbst hält, wäre keine),
  Umrechnung 0..255 → 0..1, und kein Slot ist schwarz — ein schwarzer Slot
  wäre ein Stripe, auf dem Impulse unsichtbar sind, ein Loch im Netz ohne
  Fehlermeldung
- `SongStructureDirectorTest` — die Dramaturgie-Ebene: Startlevel fest *ruhig*, Verweildauer in der Spanne des Levels, vertauschte min/max, live verengte Spanne klemmt die laufende Dauer, die Verteilung jeder Matrixzeile über 100 000 Übergänge, Gewicht 0 kommt nie vor, Nullzeile fällt auf *mittel* zurück, „zuletzt gespielt vermeiden", leerer Pool, manueller Sprung samt Verfall, `noteLoaded()`-Synchronisierung, ausgeschaltet zieht den Timer mit

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

- `/net/hitNode <nodeId:int> <energy:float> <x:float> <y:float>` — **ein tatsächlich gestartetes Split-Kind**, nicht ein Treffer-Ereignis (seit 2026-08-02, siehe „Ein `/net/hitNode` je Kind"). `x`/`y` sind die Draufsicht-Position des Knotens in Metern (`LedNetworkNode.posX/posY`, gesetzt von `applyPositions`), rein **angehängt**: ein Empfänger, der nur die ersten zwei Argumente liest, bleibt unberührt.
- `/net/impulse <impulseId:int> <x:float> <y:float> <energy:float> <speed:float>` — gedrosselter Positionsstrom der reisenden Impulse (`sendImpulseStream()`, Takt und Auswahl aus `ImpulseOscThrottle`). Das fünfte Argument ist der **Betrag** der Geschwindigkeit in LEDs/Sekunde und kam später dazu — rein angehängt, genau wie seinerzeit `x`/`y` bei `/net/hitNode`: ein Empfänger, der nur die ersten vier liest, bleibt unberührt. Die Klangseite koppelt daran die Filterfrequenz des Travel-Sounds; das Vorzeichen trägt die Richtung und ist für die Klangfarbe bedeutungslos. **Ein Datagramm je Impuls**, kein Anzahl-Feld, kein Bündel, keine Ende-Markierung — die Empfangsseite kann also nicht feststellen, wieviele Meldungen zu einem Takt gehören und braucht einen Timeout. Filler werden ausdrücklich übersprungen (sie tragen die ID ihres Elternimpulses). Es gibt **kein Todes-Signal**: ein Impuls kann aus der Auswahl der energiereichsten fallen, ohne zu sterben, deshalb deckt derselbe Timeout beides ab (`klangnetz_bells.scd`: 0,4 s, geprüft alle 0,1 s, Obergrenze 32 Drohnen).

Die zwei zugehörigen Parameter, beide in `LedNetworkTransportEffect`:

- `/net/impulse/oscRate` (float, Auslieferungswert 10, Bereich 0..40 Hz) — Sendetakt des Positionsstroms. **0 schaltet ihn ab**: kein Objekt, keine Liste, kein Datagramm. Der Not-Aus, wenn Netz oder Klangrechner während der Show nicht mitkommen; `/net/hitNode` läuft davon unberührt weiter. Beim Wiedereinschalten kommt sofort ein Takt (`lastSend` wird auf `-Infinity` zurückgesetzt), nicht erst nach einem Intervall — und es wird nichts nachgeholt: nach einer langen Pause gibt es **einen** Takt, keinen Schwall.
- `/net/impulse/oscMaxCount` (int, Auslieferungswert 32, Bereich 0..256) — höchstens so viele Impulse je Takt, ausgewählt nach Energie absteigend. 0 sendet ebenfalls nichts, verbraucht aber den Takt und allokiert vier Arrays (`energies`, `flat`, den `Arrays.copyOf(...)` und `select()`s `new int[0]`) — der teurere der beiden Schalter.

### Web-UI (webui/)

`webui/` ist eine kleine Flask-Anwendung (Python, kein Node/npm, kein Build-Schritt), die **auf derselben Maschine wie imPulse** läuft und alle OSC-Parameter im Browser regelbar macht — über Tailscale (`http://100.94.47.6:8080`) und im lokalen LAN. Sie fasst den Sketch nicht an, sondern spricht ausschliesslich über OSC an `127.0.0.1:8001` mit ihm.

Die Parameterliste ist **nicht** verdrahtet: `webui/server.py` liest bei jedem Seitenaufruf `data/remoteSettings.txt` und baut das UI daraus (Regler mit Bereich aus der Datei, Schalter für 0/1-Ints, Farbwähler für die `<basis>/Hue|Sat|Bright`-Tripel eines `RemoteControlledColorParameter`, Gruppierung nach Adress-Präfix). Ein neuer `RemoteControlled*Parameter` im Sketch taucht nach einem imPulse-Neustart also von selbst auf — die Datei wird ja bei jedem Start neu geschrieben.

Zwei Dinge, die man beim Ändern kennen muss:
- **Normalisierung**: Float-Parameter werden auf 0..1 normalisiert gesendet, weil `RemoteControlledFloatParameter.digestMessage` selbst `PApplet.map(value, 0, 1, min, max)` anwendet. Int-Parameter gehen dagegen unverändert als Ganzzahl raus — die Float-Variante von `RemoteControlledIntParameter.digestMessage` ruft `intValue()` auf dem Float auf und verstümmelt den Wert. Ausserdem sind `Master/trace` und `Master/0/opacity/0.Impulse` in `mixer.java` **ohne** führenden Schrägstrich registriert; `python-osc` lehnt solche Adressen ab, deshalb hat `server.py` für diese einen eigenen minimalen OSC-Encoder.
- **Speed-Kopplung**: Eine Änderung an `/net/impulse/speed` zieht `lifetime` (proportional) sowie `nodeDeadTime` und `/net/randomSpawn/interval` (invers) mit, damit die Impulse bei geänderter Geschwindigkeit weder reissen noch das Netz verstopfen (dieselbe Rechnung wie im Kommentar bei den Defaults in `LedNetworkTransportEffect.java`). Referenzpunkt und Formel stehen als `SPEED_REFERENCE`/`SPEED_COUPLED` oben in `server.py`, im UI ist die Kopplung per Checkbox abschaltbar.

**Beschriftet wird auf der Server-Seite** (`ADDRESS_LABELS` in `server.py`,
`label`-Feld je `SC_PARAMS`-Eintrag, `GROUP_TITLE_OVERRIDES` für die
Überschriften). Bis 2026-08-01 war der sichtbare Titel eines Reglers das
letzte Segment seiner OSC-Adresse — für einen Operator ohne Code-Kenntnis
keine Beschriftung, sondern ein Hinweis darauf, wo man nachschlagen müsste.
Jetzt steht dort Klartext („Totzeit pro Knoten"), darunter ein bis zwei Sätze
zur Wirkung, darunter die rohe Adresse als kleinste, gedimmte Zeile
(`.param-addr`). Fünf Dinge:

- **Die Zuordnung steht in `server.py`, nicht in `app.js`** — dasselbe
  Argument wie bei `TAB_RULES` und `TREE_HELP`: sie ist eine inhaltliche
  Aussage darüber, was ein Parameter im Sketch bewirkt, und nur hier ohne
  jsdom prüfbar. `test_webui.py` hält fest, dass **kein** Regler des fertigen
  Snapshots ohne Titel bleibt.
- **Drei Nachschlagestufen** (`label_for`): exakte Adresse, dann das
  Ziffern-Muster (`/net/sequencer/track0/energy` →
  `/net/sequencer/track#/energy`, sechs Tracks teilen sich einen Eintrag),
  dann das letzte Segment (`Hue` → „Farbton" deckt die 18 Farbkomponenten der
  sechs Knoten-Karten ab). Eine unbekannte Adresse fällt auf das Adresssegment
  zurück — ein neuer Regler ist dann technisch beschriftet statt gar nicht.
- **`None` als Erklärung heisst „selbsterklärend", ein fehlender Eintrag
  heisst „vergessen".** Deshalb stehen auch triviale Adressen
  (`/net/impulse/color/r`) mit `None` in der Tabelle; sonst könnte der
  Vollständigkeitstest die zwei Fälle nicht unterscheiden.
- **Die rohe Adresse bleibt sichtbar**, nicht nur im `title`-Attribut: zum
  Debuggen mit OSC von Hand wird sie gebraucht, und im Dunkeln neben der
  Installation findet niemand einen Hover-Text. Sie ist nur nicht mehr der
  Haupttext.
- **Die kompakten Panels behalten ihre kurzen Beschriftungen** („Wdh.",
  „Swing", „0,5x"): sechs Erklärungen mal sechs Track-Karten wären 36 Absätze
  und machten genau das Panel unbedienbar, das die flache Reglerliste ersetzt
  hat. Statt dessen steht **einmal** unter der Spurenreihe eine Legende
  (`track_field_legend()`), deren Texte aus `ADDRESS_LABELS` kommen — nicht
  aus einer zweiten Liste, die nachgezogen werden müsste.

Über den Reglern sitzt die Sektion **Presets** (Dropdown + Laden, Textfeld + Speichern). Sie ist der dritte Ladeweg neben OSC von Hand und dem Scheduler, benutzt aber dieselben Kommandos: `/preset/load <name>` und `/preset/save <name>` als OSC-String an 8001. Drei Dinge daran sind nicht offensichtlich:
- **Die Liste kommt vom Dateisystem, nicht per OSC.** `server.py` läuft auf derselben Maschine wie imPulse und liest `data/presets/` direkt (`--presets`, Vorgabe `presets/` neben `--settings`). Ein OSC-Rückkanal wäre neu zu bauen — die einzige Ausgangsadresse des Sketches ist 8002. Geschrieben wird der Ordner weiterhin ausschliesslich von imPulse; deshalb gibt es im UI auch kein Löschen.
- **Nach dem Laden zieht der Server die Regleranzeige nach**, indem er dieselbe Preset-Datei mit `parse_settings()` liest — das geht, weil Preset- und `remoteSettings.txt`-Format identisch sind. Die Werte gehen in der HTTP-Antwort zurück und werden im Browser *still* gesetzt (kein zweites OSC); geklemmt wird auf die Range aus `remoteSettings.txt`, nicht auf die aus der Preset-Datei — dieselbe Regel wie `PresetStore.applyPreset()`. Adressen, die der Dump nicht kennt, und Werte ausserhalb der verengten UI-Range (`UI_RANGE_OVERRIDES`) nennt die Statuszeile, statt sie zu verschlucken.
- **Speichern wartet auf die Datei.** `/preset/save` ist asynchron (imPulse schreibt erst im nächsten `draw()`), also pollt der Endpoint bis zu 1 s auf eine geänderte `mtime` und antwortet sonst mit 504 statt Erfolg zu behaupten — der häufigste Fehlerfall ist „Web-UI läuft, imPulse nicht". `valid_preset_name()` in `server.py` und die Regex im JS spiegeln `PresetStore.isValidName()`; Autorität bleibt Java, dort geht es um Pfad-Traversal.

**Sieben Themen-Tabs** (Mixer, Sound Design, Spawn-Verhalten, Noten-Verhalten,
Impuls-Verhalten, Farben, Song-Struktur) statt einer langen Liste. Welche
Adresse in welchen Tab gehört, entscheidet `TAB_RULES`/`TAB_PRIMARY` in
`server.py` — dort ist es
prüfbar, und `test_webui.py` stellt sicher, dass **jeder** Regler in genau
einem Tab landet und keiner doppelt. Oben je Tab die kuratierten Regler,
darunter ein eingeklapptes `<details>` mit dem Rest.

Die Reihenfolge der Regeln zählt: `/net/impulse/speedQuantize/` muss **vor**
`/net/impulse/` stehen, sonst zöge die Physik-Regel die Speed-Klassen an sich.
Dieselbe Falle bei den Farben: `/net/impulse/color/`,
`/net/impulse/fadeOut/` und `/nodes/colors/` stehen vor `/net/impulse/` bzw.
`/nodes/`.

**Eine Gruppe geht als GANZES in einen Tab**, bestimmt von der Adresse ihres
ersten Reglers. Eine Farbkarte trägt aber **keine eigene Adresse**, sondern
drei darunter (`<basis>/Hue|Sat|Bright`) — `build_tabs()` nimmt für sie
deshalb ausdrücklich die Hue-Adresse. Ohne diesen Zweig hat eine Gruppe aus
lauter Farbkarten gar keine Adresse und fällt aus **jedem** Tab heraus. Genau
das war seit dem Tab-Umbau mit `/nodes/colors` passiert: 18 Werte, sechs
Farbwähler, im UI schlicht nicht vorhanden, ohne Fehlermeldung. Ein Test hält
für jede Gruppe fest, dass sie in einem Tab ankommt.

Der Farben-Tab trägt als einziger `TAB_EXPANDED`: seine Gruppen stehen direkt
im Panel statt hinter „Erweitert". `TAB_PRIMARY` arbeitet auf Adressen und
greift bei Farbkarten deshalb nicht — ohne das Flag bestünde der ganze Tab
aus einem zugeklappten `<details>`.

**`buildTabs()` baut ALLE Panels und schaltet nur `hidden` um** — nicht erst
beim Öffnen eines Tabs. Die Regler tragen sich beim Bauen in die flache
`controls`-Map ein, und über genau die laufen Preset-Laden und der
`applied`/`echoed`-Rücklauf. Ein Regler auf einem nie geöffneten Tab stünde
sonst nicht in der Map und würde von einem Preset still nicht angezeigt.
Headless mit jsdom gegengeprüft: alle Regler in der Map (67 beim Fünf-Tab-
Umbau, 81 beim Farben-Tab), ein Regler auf einem inaktiven Tab lässt sich
setzen, das Umschalten baut nichts neu, und ein Swatch-Klick setzt genau eine
Farbkarte, ohne die Nachbarn anzufassen.

Kopfzeile, Statuszeile und Preset-Sektion stehen bewusst **über** der
Tab-Leiste: sie gelten für alle Tabs, und ein Preset-Feld, das nur auf einem
Tab sichtbar wäre, wäre eine Falle.

**Die Sektion „Melodie-Zuordnung"** steht wie die Presets **über** der
Tab-Leiste und ist die einzige Stelle im UI, an der das **Verstellen eines
Feldes nichts sendet**. Die vier Werte
`/net/melody/{mode,startNode,rootMidiNote,numOctaves}` beschreiben, wie die
Zuordnung beim nächsten Mal *gerechnet* wird; erst der Knopf setzt sie und
löst danach `/net/melody/recompute` aus. Vier Dinge dazu:

- **Die Reihenfolge im Endpunkt ist der Punkt.** `/api/melody/recompute` setzt
  erst alle vier Werte über denselben `apply_value()`-Weg wie jeder andere
  Regler, **dann** geht das Kommando raus — umgekehrt rechnete imPulse mit dem
  alten Stand.
- **Die Bestätigung ist Pflicht und gilt für einen Druck.** Der Knopf ist
  gesperrt, bis das Häkchen sitzt, und das Häkchen fällt danach wieder weg,
  auch nach einem Fehler. Dieselbe Überlegung wie bei `L` in den zwei
  Kalibriermodi: die Aktion überschreibt `data/nodeMelody_<modus>.txt` samt
  von Hand korrigierter Zeilen und lässt sich nicht zurücknehmen.
- **Es gibt keine Erfolgsmeldung von imPulse** (kein Rückkanal). Die
  Statuszeile sagt, was *gesendet* wurde und wo das Ergebnis steht, nicht
  „fertig".
- **`melody_addresses()` nimmt die vier Adressen aus dem generischen
  Rendering** — sonst stünde jeder zweimal auf der Seite, einmal mit
  Bestätigung und einmal als Schieber, der so aussieht, als täte er etwas.

`MELODY_MODE_NAMES` in `server.py` ist die dritte Kopie der Modusliste (neben
`MelodyModes.ALL` und `~melodyModes` in der `.scd`); ein Test vergleicht sie
mit der Java-Datei.

**Sieben Spezial-Sektionen** stehen neben dem generischen Rendering, weil eine
flache Liste aus 38 Sequencer-Reglern unbedienbar wäre. Der Server liefert
dafür Struktur statt einer Reglerliste (`build_sequencer`,
`build_speed_classes`, `build_split`, `build_song_structure`,
`sc_param_groups` in `server.py`), das Aussehen macht `app.js`. Dazu kommen die
zwei Farb-Sektionen des Farben-Tabs (`build_colors`, `build_fade`), die unter
„Farben werden ausschliesslich am Farbwähler eingestellt" beschrieben sind:

- **Sequencer** — BPM als große Ziffer, eigener Not-Aus, sechs Track-Karten
  mit je eigener Spurfarbe; Notenwerte als segmentierte Leiste mit **Symbol
  und Kürzel** (nicht jede Windows-Schrift hat U+1D15D..U+1D161, ein Symbol
  allein wäre dort ein leeres Kästchen). `originTreeFilter` ist ein
  **Auswahlbalken** mit fünf Klartext-Zuständen, siehe unten.
- **Speed-Klassen** — die fünf Gewichte plus ein Verteilungsbalken, der sie
  normiert zeigt; die Gewichte selbst summieren sich bewusst nicht auf 100.
- **Split-Verhalten** — zwei Verteilungen untereinander (Zweigzahl, Notenwert
  des Versatzes), im selben Tab wie die Speed-Klassen. Details unter
  „Split-Anzahl und Split-Versatz".
- **Song-Struktur** — Not-Aus, Live-Zustand, die Übergangsmatrix als
  **4×4-Gitter** und die vier Verweildauer-Spannen, dazu vier Knöpfe für den
  manuellen Levelsprung. Ein Gitter und keine Liste aus sechzehn Reglern:
  „Zeile = wo ich bin, Spalte = wo ich hinkönnte" ist nur so auf einen Blick
  zu sehen. Neben jedem Regler steht der **normierte** Anteil, der Regler
  selbst zeigt das rohe Gewicht — die zwei Zahlen unterscheiden sich, sobald
  eine Zeile sich nicht zu 100 summiert, und genau das ist erlaubt. Details
  unter „Song-Struktur-Dramaturgie".
- **Sound (SuperCollider)** — die `/klangnetz/param/*`-Adressen, die
  **nicht** durch `remoteSettings.txt` laufen.
- **Palette** — die Farbpalette im Farben-Tab, siehe unten.
- **Mitschnitt** — der Aufnahmeknopf ganz oben im Sound-Tab, siehe unten.

Palette und Mitschnitt brauchen als einzige keine Adresse aus
`remoteSettings.txt` und sind deshalb bedingungslos da; die anderen fünf lassen
sich ohne ihre Parameter nicht bauen.

**Der Baum-Filter je Track ist ein Auswahlbalken, kein 0..4-Schieber.** Fünf
Klartext-Zustände (`alle`/`vorn`/`hinten`/`rechts`/`links`), gleiche Bauform
wie die Notenwert-Leiste darüber. „0 = zufällig" ist an einem Zahlenregler
nicht zu erraten; ein Schalter-plus-Dropdown wären zwei Bedienelemente für
einen Parameter mit fünf gleichrangigen Zuständen plus ein verborgener
„zuletzt gewählter Baum", der nach einem Preset-Laden gegenüber dem Sketch
falsch stehen kann. Der OSC-Wertebereich bleibt unverändert `int 0..4`.

Dazu zwei Erklärungen mit unterschiedlicher Lebensdauer: `TREE_HELP`
(`server.py`) steht als feste Zeile unter der Spurenreihe — dort und nicht
sechsmal in `app.js`, weil sie eine Aussage über `OriginSequencer` trifft und
so prüfbar bleibt. Der Hinweis **je Track** erscheint dagegen nur, wenn
`originStripeOverride >= 0` den Filter gerade aushebelt: das ist der
Zustand, in dem ein eingestellter Filter wirkungslos ist, ohne Fehler und
ohne Symptom.

**Farben werden ausschliesslich am Farbwähler eingestellt** (Birk,
2026-08-01) — die einzelnen Kanalregler sind aus dem UI verschwunden. Drei
Sektionen im Farben-Tab, alle drei aus `build_colors()`/`build_fade()` in
`server.py`:

- **Impuls-Farbe** — der Moduswahlschalter `/net/impulse/color/useSpecificColor`
  (hiess bis 2026-08-01 `useRemoteCol`; „remote" beschrieb, *woher* der Wert
  kommt, und das tun beide Fälle) als Auswahlbalken mit zwei
  Klartext-Zuständen, darunter der Wähler für die eine Impulsfarbe und die
  **acht Stripe-Slots**. Die gerade unwirksame Quelle bleibt **sichtbar** und
  wird nur gedimmt: ausblenden liesse offen, ob das Feature fehlt oder nur
  gerade nicht dran ist, und wer die Slots einstellen will, *bevor* er
  umschaltet, käme gar nicht an sie heran.
- **Nachleuchten der Impulse** — siehe unten. Heisst nicht nur
  „Nachleuchten", weil `Master/trace` im Mixer-Tab auch so heisst und das
  ganze Bild betrifft statt nur die Impuls-Spur.
- **Knoten-Farben** — die sechs HSB-Karten, jetzt ohne ihre drei Schieber.

Zwei Dinge, die man beim Ändern kennen muss:

- **`headlessControl()` hält die Adressen in der `controls`-Map**, obwohl es
  ihre Regler nicht mehr gibt. Über genau diese Map laufen das Preset-Laden
  und der `applied`/`echoed`-Rücklauf — eine Adresse, die dort fehlt, wird von
  einem Preset still nicht angezeigt, und die Karte zeigt danach eine andere
  Farbe als die Installation. Dasselbe Argument wie bei „`buildTabs()` baut
  ALLE Panels".
- **`color_addresses()`/`fade_addresses()` nehmen die Adressen aus dem
  generischen Rendering**, genau wie `sequencer_addresses()`. Sonst stünde
  jeder Kanal zweimal auf der Seite. `/net/impulse/color/gamma` ist bewusst
  **nicht** dabei: eine Kurve ist keine Farbe, sie bleibt ein Regler (in der
  Gruppe „Impuls-Farbe: Feinheiten" — der Gruppentitel trägt den Zusatz, weil
  die Sektion daneben schon „Impuls-Farbe" heisst).

**Die Farbpalette** (`data/colorPalettes.txt`, `GET`/`POST /api/palette`) ist
eine Sammlung wiederverwendbarer Farben. Dieselbe Swatch-Reihe steht unter
**jeder** Farbwähler-Karte; ein Klick setzt Hue/Sat/Bright genau dieser Karte
über den ganz normalen `queueSendMany`-Weg, kein Sonderpfad. Bei den
RGB-Karten (Impulsfarbe, Stripe-Slots) wird beim Anwenden und beim Übernehmen
umgerechnet — sonst wäre die Palette ausgerechnet an den neun Karten nicht
verfügbar, die es seit dem Farbwähler-Umbau gibt. Fünf Dinge:

- **Format wie `data/stripeTrees.txt`** — Tab-Spalten
  `name hue sat bright` (0..1), `#` als Kommentar, von Hand editierbar, bei
  doppeltem Namen gewinnt die **letzte** Zeile (angehängte Handkorrektur).
- **Server-seitig, nicht im localStorage.** Sie soll einen Neustart
  überleben und auf jedem Gerät dieselbe sein — genau das meint „eine
  Palette, die von allen gewählt werden kann".
- **Voll-Liste-Semantik**: der POST trägt die komplette Palette, der Server
  ersetzt die Datei. Zwei Endpoints auf derselben Datei wären zwei Wege, die
  auseinanderlaufen können. Preis: zwei offene Browser überschreiben sich.
- **Kein OSC.** Die Palette ist reine UI-Sache, imPulse kennt sie nicht.
- **Kein Preset-Ersatz.** Sie hält nur Farbwerte, nicht welche Karte welche
  Farbe trägt — das können die Presets schon.

**Der Aufnahmeknopf** (Sektion „Mitschnitt (4 Kanaele)", ganz oben im
Sound-Tab) startet und stoppt den Vierkanal-Mitschnitt von SuperCollider
(siehe „Klangseite"). Vier Routen — `GET /api/record/status`, `POST
/api/record/{start,stop,toggle}` — schicken je **eine argumentlose**
OSC-Nachricht `/klangnetz/record/*` an sclang auf **8002**; dafür kann
`build_osc_message(addr, None)` seit diesem Feature auch den leeren Typtag
`,`. Geschrieben wird die WAV-Datei ausschliesslich von sclang. Fünf Dinge:

- **Der einzige Rückkanal im ganzen UI.** `RecordStatusListener` hört auf
  `127.0.0.1:8003` (`--record-status-port`, muss zu `~recordStatusPort` in der
  `.scd` passen) auf `/klangnetz/record/status`. Für einen Regler wäre
  fire-and-forget richtig — ein Aufnahmeknopf hat dagegen einen Zustand, den
  auch ein Skript, die IDE oder ein zweiter Browser ändern kann, und ein
  Knopf, der nur zeigt, was zuletzt geklickt wurde, wäre im Zweifel eine
  Aufnahme, die gar nicht läuft. Genau der Fehler, der beim Dreh erst in der
  Nachbearbeitung auffällt.
- **Der gemeldete Zustand kommt immer von sclang, nie vom Kommando.** Ein
  zweites `/start` wird dort ignoriert und meldet trotzdem „läuft".
- **Frisch oder alt entscheidet ein Zähler**, kein Wertvergleich: der Endpoint
  merkt sich den Stand vor dem Senden und wartet bis zu 400 ms auf eine
  Erhöhung. Zweimal „läuft nicht" hintereinander sähe sonst aus wie keine
  Antwort. Bleibt sie aus, steht `answered: false` in der Antwort und der Knopf
  sagt „kein Kontakt zu sclang" — statt Erfolg zu behaupten.
- **`known` ist nicht `recording`**: „noch nie gehört" und „läuft gerade nicht"
  werden verschieden angezeigt, und bei unbekanntem Zustand schickt ein Klick
  `toggle` statt zu raten.
- **Ein fehlgeschlagenes Bind auf 8003 verhindert den Start des UI nicht** —
  der Knopf sendet weiter und zeigt nur keinen Zustand mehr.

**Das Nachleuchten wird über Zielfarbe und Tempo bedient, nicht über die drei
Zerfallsraten.** `/net/impulse/fadeOut/{r,g,b}` sind **keine Farbe**: der
Effekt multipliziert den ganzen LED-Puffer in jedem Frame damit
(`LedColor.mult` in `drawMe()`). Ein Farbwähler direkt darauf wäre
irreführend — „heller im Wähler" hiesse dort „zerfällt *langsamer*". Bedient
wird deshalb, was ein Operator wirklich fragt: welche Farbe hat die Spur, kurz
bevor sie verschwindet, und wie schnell verschwindet sie.

```
w_c       = MIN_WEIGHT + (1 - MIN_WEIGHT) * (ziel_c / max(ziel))
fadeOut_c = baseDecay ** (1 / w_c)
```

Vier Dinge dazu:

- **Die Umrechnung steht in `server.py`** (`fade_from_target`,
  `fade_to_target`, `POST /api/fadeout`), nicht in `app.js`. Sie ist eine
  Aussage darüber, wie der Effekt den Puffer multipliziert, und nur dort ohne
  jsdom prüfbar; das UI kennt nur Zielfarbe und Tempo, also genau die zwei
  Dinge, die es anzeigt. Eine zweite Kopie im JS wären zwei Wahrheiten.
- **Die Potenz-Form ist kein Geschmack, sondern der Grenzfall `baseDecay = 1`:**
  `1**x == 1` für jedes `x`, also alle drei Kanäle 1, unabhängig von der
  Zielfarbe. Eine multiplikative Gewichtung hätte dort je Kanal etwas anderes
  ergeben — „kein Zerfall" wäre farbabhängig gewesen.
- **`MIN_WEIGHT = 0.05` ist zurückgerechnet, nicht geraten.** Der
  Auslieferungswert 0.97/0.96/0.56 (der warme Schweif der Installation) muss
  mit dem neuen Bedienelement einstellbar bleiben; er verlangt für Blau ein
  Gewicht von `ln(0.97)/ln(0.56) = 0.0525`. Bei einem grösseren Mindestgewicht
  wäre er unerreichbar. Hin- und Rückweg reproduzieren ihn exakt (Test).
- **Schwarze Zielfarbe fällt auf Gewicht 1 zurück**, statt durch null zu
  teilen: „welche Farbe bleibt übrig" hat bei Schwarz keine Antwort, also
  verfärbt sich nichts. Die Umkehrung liefert bei „zerfällt gar nichts" und
  „zerfällt alles sofort" **Weiss** — beides sind Fälle ohne eindeutige
  Zielfarbe, und Weiss behauptet keine Färbung, die es nicht gibt.

Sieben Dinge, die man beim Ändern kennen muss:

- **`sequencer_addresses()` und `song_structure_addresses()` nehmen die
  Adressen der Spezial-Sektionen aus dem generischen Rendering.** Ohne das
  stünde jeder Sequencer- und Matrix-Regler zweimal auf der Seite — zwei
  Bedienelemente für denselben Parameter, die auseinander laufen können.
  `test_webui.py` prüft das an einem echten Snapshot.
- **`build_song_structure()` liefert `None`, sobald auch nur EINE Matrixzelle
  fehlt** (älterer imPulse-Stand). Ein halbes Gitter wäre schlimmer als gar
  keins: der Operator sähe vier Zeilen und wüsste nicht, dass eine Zelle
  fehlt. Der Tab bleibt dann leer, statt eine Lücke als Null anzuzeigen.
- **Der Live-Zustand kommt aus `data/songStructureState.txt`, nicht per OSC.**
  Es gibt keinen Rückkanal ins UI — imPulse sendet nur an 8002, und dort hört
  SuperCollider. `server.py` läuft auf derselben Maschine und liest die Datei
  direkt (`/api/songstructure`, gepollt alle 5 s; die kürzeste Verweildauer
  sind 30 s). Dasselbe Muster wie die Preset-Liste. `state: null` ist der
  **Normalfall** vor dem ersten Levelwechsel, kein Fehler.
- **`/songStructure/goto` geht über `/api/goto`, nicht über `/api/set`.** Es
  ist ein Kommando und steht bewusst nicht in `remoteSettings.txt`; `/api/set`
  kennt nur Adressen aus dem Dump und würde es mit 400 ablehnen.
- **`SC_PARAMS` ist eine handgepflegte Kopie der `~registerParam`-Registry
  aus der `.scd`** und geht über einen **zweiten** `OscSender` auf Port
  **8002**. Zwei Tests vergleichen die Tabelle in beide Richtungen mit der
  Datei, damit sie nicht abdriftet. Für die **Werte** gibt es **keinen
  Rückkanal**: die angezeigten sind die `.scd`-Defaults, nicht der
  Live-Zustand — die Sektion sagt das selbst im Warnhinweis. (Der Rückkanal
  auf 8003 trägt ausschliesslich den Aufnahmezustand, keine Parameterwerte.)
- **Die Rasteranzeige je Track ist die Uhr des Browsers**, gerechnet aus BPM
  und Notenwert, **nicht** eine Rückmeldung aus dem Sketch: dafür gäbe es
  keinen Kanal, imPulse sendet nur an 8002 und dort hört SuperCollider. Sie
  heißt deshalb im UI „Raster" und behauptet nirgends „feuert jetzt". Ihre
  Phase kann gegenüber dem Sketch beliebig verschoben sein; was sie zeigt,
  ist der *Abstand* — welcher Track dicht und welcher dünn läuft.
- **Der Verteilungsbalken der Speed-Klassen und die Prozentanzeige der
  Song-Matrix hängen an der `set()`-Methode der Regler, nicht am
  `input`-Event.** Das Laden eines Presets ruft `control.set(wert, true)` und
  löst bewusst kein `input` aus; an einem reinen Event-Listener blieben beide
  danach auf der alten Verteilung stehen.

Für die UI-Schicht selbst gibt es **kein** Testgerüst im Repo — `webui/` soll
ohne Node/npm auskommen, und ein jsdom-Test würde genau das einführen. Geprüft
ist die Server-Seite (`webui/test_webui.py`, ohne Fremdabhängigkeiten); das
Rendering wurde einmalig headless mit jsdom gegengeprüft, ohne den Test
aufzunehmen. Eine Ausnahme geht ohne Fremdabhängigkeit und ist deshalb drin:
`MarkupWiringTest` liest `app.js` und `index.html` als Text und prüft, dass
jedes `getElementById()` ein `id=` im Markup findet — ein Handle, das `null`
ist, fällt im Browser sonst erst als stumme Seite auf, weil `app.js` seine
Listener beim Laden anhängt.

Start (Details, Windows-Scheduled-Task `WebUiRun` und Firewall-Hinweise in `webui/README.md`):

```bash
cd webui && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/python server.py
```

Für die processing-unabhängige Logik (Parser, Normalisierung, Gruppierung, Kopplung, OSC-Encoder) gibt es eine eigene Suite ohne Fremdabhängigkeiten: `python3 webui/test_webui.py`.

#### Automatische Sicherung der Live-Daten (webui/autocommit.py)

Ein Hintergrund-Thread des Web-UI-Servers macht alle **10 Minuten** einen
**lokalen** Git-Commit der live editierten Daten-Dateien — aber nur, wenn sich
tatsächlich etwas geändert hat. Anlass: Presets, Farbpaletten und
Kalibrierdateien wurden bisher nur von Hand nachgezogen („X-Preset vom
Live-Betrieb nachgezogen" in der Historie), und ein neu aufgesetzter Rechner
nimmt die Live-Arbeit mit.

Überwacht werden sieben Muster (`WATCHED_PATTERNS`): `data/presets/*.txt`,
`supercollider/presets/*.txt`, `data/colorPalettes.txt`,
`data/energyLevels.txt`, `data/stripeTrees.txt`, `data/nodeCrossings.txt`,
`data/ledPositions.txt`. Ein Muster, das in diesem Checkout auf nichts passt,
ist kein Fehler — Farbpaletten und Energie-Level kommen erst mit ihren
Feature-Branches.

Die **Sound-Presets stehen bewusst mit auf der Liste**: ein Preset ist *ein
Name, zwei Dateien* (siehe „Klangseite"). Nur die Licht-Hälfte zu sichern
hiesse, die Szene käme später optisch zurück und klanglich nicht — genau der
Fehlerfall, den `PresetManager.forwardToSound()` schon einmal verhindert.

Fünf Dinge, die man beim Ändern kennen muss:

- **Kein Push, an keiner Stelle.** Mehrere Checkouts arbeiten parallel am
  selben Remote (Live-Laptop, Test-Deploy, Worktrees); ein automatischer Push
  wäre eine Fernwirkung ohne Entscheidung. Dieselbe Regel wie oben bei Merges
  und Force-Pushes.
- **Kein pauschales Staging.** Gestaget werden ausschliesslich die konkreten
  Pfade aus der Porcelain-Ausgabe, und die **Pathspec steht auch am `commit`**
  (`git commit -m … -- <pfade>`). In dieser Form committet Git nur den
  Arbeitsbaum-Zustand dieser Pfade und lässt alles unberührt, was der Operator
  sonst gerade gestaged hat. Ein Grep-Test über alle `webui/*.py` hält
  `git add -A`, `git add .`, `git commit -a` und `git push` fern — mit
  Gegenprobe, dass die Muster wirklich anschlagen.
- **Die Trennlinie ist nicht „wer schreibt die Datei", sondern „wann ändert
  sie sich".** `data/remoteSettings.txt` (gitignored, Boot-Snapshot) und
  `data/songStructureState.txt` (bei jedem Levelwechsel neu) sind
  Laufzeitstatus und stehen deshalb nicht auf der Liste; sonst entstünde alle
  zehn Minuten ein Commit, also genau die feste Taktung unabhängig vom
  Zustand, die vermieden werden soll.
- **Übersprungen wird bei detached HEAD** und bei offenem
  Merge/Rebase/Cherry-Pick/Revert/Bisect. Ein Commit ohne Branch wäre nach dem
  nächsten Checkout nur noch im Reflog — ein Sicherungsnetz, das nicht hält,
  ist schlimmer als keins, weil die Statuszeile trotzdem „gesichert" behauptet.
- **Der Thread stirbt nie.** Jeder Git-Fehler wird zu einem `error`-Ergebnis,
  geloggt und in der UI-Zeile sichtbar; die nächste Runde läuft trotzdem.
  Gewartet wird über ein `Event`, nicht `sleep`, damit das Beenden nicht bis zu
  zehn Minuten hängt.

Abschaltbar mit `--no-autocommit` bzw. `IMPULSE_AUTOCOMMIT=0`, Takt über
`--autocommit-interval` / `IMPULSE_AUTOCOMMIT_INTERVAL` (Untergrenze 10 s).
Zustand im UI unter der Statusleiste und maschinenlesbar unter
`GET /api/autocommit` — nur lesend, es gibt bewusst keinen Knopf, der aus dem
Browser heraus Git-Zustand ändert. Eigene Testsuite:
`python3 webui/test_autocommit.py` (inklusive Integrationstest gegen ein
echtes temporäres Repository, übersprungen wenn `git` fehlt).

### Netz-Topologie (LedStripeNetworks.java, NodeCrossingStore.java)

`data/nodeCrossings.txt`: eine Zeile pro Node, darin leerzeichengetrennte **globale LED-Indizes**, die sich physisch kreuzen. `NodeCrossingStore.load()` liest und validiert die Datei (Validierung, Undo, Datei-I/O — bewusst ohne Processing- und Netzabhängigkeit, siehe `test/NodeCrossingStoreTest.java`). `LedInNetInfo.applyCrossings(...)` baut daraus die `LedNetworkNode`-Objekte und setzt bei jeder beteiligten LED `LedInNetInfo.partOfNode` — **in-place** auf derselben Liste/denselben `LedInNetInfo`-Objekten, weil `LedNetworkTransportEffect` und `LedNetworkNodeEffects` dieselbe Instanz halten. Über `partOfNode` erkennt der Transport-Effekt einen Node-Treffer in O(1). `applyCrossings` wird sowohl beim Start (`setup()`) als auch zur Laufzeit aus der Kalibrierung (Taste `R`, siehe unten) aufgerufen. Ältere Aufnahmen anderer Geometrien liegen als `data/nodeCrossings_16x720.txt` und `data/nodeCrossings_35C3.txt` daneben (siehe „Node-Kalibrierung"), werden aber nicht geladen.

### Impuls-Simulation (LedNetworkTransportEffect.java)

Jeder Impuls ist eine `TravellingActivation` (Position als float, Stripe, Geschwindigkeit inkl. Vorzeichen = Richtung, Energie). Pro Frame wird der Zeitschritt aus `System.currentTimeMillis()` gebildet — die Simulation hängt an der Wanduhr, nicht am Framecount.

Jede `TravellingActivation` trägt ausserdem eine fortlaufende `id` (`final`, vergeben aus `nextImpulseId++` im äusseren Objekt) — die Kennung, unter der der Impuls im Positionsstrom `/net/impulse` auftaucht. Vergeben wird sie an **genau einer** Stelle, dem delegierenden Konstruktor, damit keine der acht Konstruktionsstellen sie vergessen kann; alle laufen auf dem Animationsthread (`digestMessage` wird nur aus `distributeMessages()` am Anfang von `draw()` gerufen), der Zähler braucht also keine Synchronisierung. Ein Überlauf nach 2^31 Impulsen ist hingenommen.

Zwei Mechanismen, die man beim Ändern kennen muss:
- **Filler**: Bei hoher Geschwindigkeit überspringt ein Impuls LEDs zwischen zwei Frames. Für die übersprungenen Positionen werden `TravellingActivationFiller` erzeugt, gezeichnet und am Ende desselben Frames wieder entfernt. Ein Filler übernimmt die `id` seines Elternimpulses, statt eine neue zu verbrauchen — strukturell erzwungen, weil die Filler-Klasse nur den Konstruktor mit ausdrücklicher ID anbietet. Im Positionsstrom werden Filler zusätzlich explizit übersprungen, sonst sähe die Klangseite einen einzigen Impuls, der im selben Takt zwischen mehreren Positionen hin- und herspringt.
- **nodeDeadTime**: Ein Node feuert erst wieder nach `/net/impulse/nodeDeadTime` Sekunden. Ohne diese Totzeit würde ein Impuls denselben Node in aufeinanderfolgenden Frames endlos neu triggern.

**Sub-Pixel-Blending des Impulskopfes** (Zeichenblock am Ende von `drawMe()`,
Hilfsmethode `blendHead()`): `getLedIndex()` rundet die float-Position auf die
nächstliegende LED. Bei kleinem `/net/impulse/speed` steht ein Impuls dadurch
mehrere Frames auf derselben LED und springt dann hart eine weiter — die
Bewegung ruckelt sichtbar. Der Kopf wird deshalb linear auf die zwei LEDs
verteilt, zwischen denen er gerade steht:

```
lowerIdx = floor(ledIdxPos)
frac     = ledIdxPos - lowerIdx      // 0..1
lowerIdx bekommt Gewicht (1 - frac), lowerIdx+1 bekommt frac
```

Beide Farbquellen gehen hindurch (zentrale Impulsfarbe **und** Stripe-Farbe),
die Kopffarbe selbst — `fade`, Energie, Slot — ist unverändert. **Es gibt
keinen Regler dafür**: das ist Renderqualität, kein gestalterischer
Parameter, also immer an.

Vier Dinge, die man beim Ändern kennen muss:

- **Bei `frac == 0` bleibt das volle Gewicht 1.0 auf einer LED.** Die
  Spitzenhelligkeit ist unverändert, es ändert sich ausschliesslich, wie der
  Kopf von einer LED zur nächsten wandert.
- **Zusammengeführt wird je Kanal mit `max()`, nicht mit `set()`.** Ein
  Teilgewicht hart geschrieben würde den vorhandenen Pufferinhalt
  *unterschreiten* — den nachleuchtenden Schweif, den `LedColor.mult()` weiter
  oben im selben Frame gedämpft hat, ebenso wie den Kopf eines zweiten
  Impulses auf derselben LED (vorher galt „letzter gewinnt", der schwächere
  Beitrag verschwand). Beides wäre ein Flackern genau dort, wo das Blending
  glätten soll. `max()` ist ausserdem der Grund, warum der Kopf trotz der
  Aufteilung nicht dunkler *wirkt*, obwohl bei `frac == 0.5` rechnerisch nur
  die Hälfte auf jede der beiden LEDs fällt: die LED, die er gerade verlässt,
  hält ihren Wert aus dem Vorframe, gedämpft nur um den `fadeOut`-Faktor.
- **Das Vorzeichen von `speed` spielt keine Rolle.** Die zwei Nachbarn
  klammern die Position von beiden Seiten ein, egal in welche Richtung der
  Impuls läuft; welcher der vorauslaufende ist, entscheidet allein `frac`.
  Beide gehen durch dieselbe Randprüfung: innerhalb des globalen Arrays und
  **auf demselben Stripe** wie die Aktivierung (dieselbe Bedingung wie in
  `activationIsValid()`). Über den Stripe-Rand hinaus ist der globale Index
  zwar fortlaufend, physisch liegt die LED aber an einer ganz anderen Stelle
  im Netz — der Kopf liesse dort einen Punkt aufblitzen, an dem kein Impuls
  ist.
- **Filler bleiben beim harten Schreiben auf eine LED.** Sie lösen das
  umgekehrte Problem (hohe Geschwindigkeit, übersprungene LEDs) und sitzen per
  Konstruktion auf ganzzahligen Positionen, ein Bruchteil ist bei ihnen also
  immer 0.

**Zwei Farbquellen, ein Schalter** (`/net/impulse/color/useSpecificColor`, int
0/1, Default 1): bei 1 bekommt jeder Impuls die eine zentral gesetzte Farbe
(`impulseR/G/B`), bei 0 die Farbe seines Stripes. Der Schalter hiess bis
2026-08-01 `useRemoteCol` — „remote" beschrieb, *woher* der Wert kommt, und
das tun beide Fälle; die 14 Preset-Dateien sind mit umbenannt, sonst hätte
jedes Laden die Adresse als unbekannt gemeldet und den Modus stehen lassen.

Die acht Farben des Gegenfalls waren bis dahin ein Literal-Array
(`stripeColorMapping`) mitten im Effekt und weder per OSC noch im Web-UI
erreichbar. Sie sind jetzt 24 `RemoteControlledFloatParameter` unter
`/net/impulse/stripeColor/<0..7>/{r,g,b}`; Stripe → Slot geht per Modulo, bei
30 Stripes wiederholt sich das Muster also alle acht. Zwei Dinge:

- **Es gibt bewusst keine `data/stripeColors.txt`.** Stripe-Farben stünden
  damit an drei Orten (Code-Default, Datei, Preset) statt an zweien, und ein
  geladenes Preset widerspräche der Datei, ohne dass das auffällt. Als normale
  Parameter erben sie Preset-Persistenz, `remoteSettings.txt` und
  Live-Änderung geschenkt; die Rückwärtskompatibilität liefert der
  Compile-Time-Default.
- **Die Startwerte stehen in `StripeColorDefaults.java`**, allein und ohne
  oscP5 — nur so ist die Tabelle über `test/run.sh` prüfbar
  (`StripeColorDefaultsTest` hält die 24 Kanalwerte unabhängig abgetippt
  fest). Ein beim Umbau vertauschter Kanal fiele sonst erst an der
  Installation auf, und dann als „die Farben stimmen irgendwie nicht mehr".

Bei einem Node-Treffer erhält jeder Zweig aktuell die **volle** Energie des Elternimpulses (`childEnergy = curActivation.energy`) — ein bewusster Quick-Fix, jede Aufspaltung vervielfacht also die Gesamtenergie. Die auskommentierte Zeile darüber zeigt die energieerhaltende Variante.

**Split-Varianz** (`SplitVariance.java`, angewandt in `activationEncounteredNode()`):
jedes Kind einer Aufspaltung kann eine leicht abweichende Geschwindigkeit und
Lebensdauer bekommen, damit Geschwister nicht synchron sterben und identisch
wirken. Zwei unabhängige Parameter, beide Auslieferungswert **0** (= exakt das
vorherige Verhalten):

- `/net/impulse/splitSpeedJitter` (float 0..1) — `childSpeed = speed * (1 + jitter*(rand*2-1))`
- `/net/impulse/splitLifetimeJitter` (float 0..1) — streut den `decayScale` des Kindes

`decayScale` ist ein **Faktor auf** `/net/impulse/lifetime`, nicht dessen
Ersatz. Das ist der Punkt, an dem der Entwurf bewusst vom ursprünglichen
Auftrag abweicht: mit einem absoluten Zerfallswert je Impuls würde jeder
Impuls den Wert seiner Geburt einfrieren, der Sinus-Randomizer
(`/net/impulse/lifetime/randomize/*`) erreichte nur noch neu gespawnte Impulse
und ein Operator, der den Lifetime-Regler zieht, sähe die lebenden Impulse
unbeeindruckt weiterlaufen — beides ohne Fehlermeldung. Normale Spawns tragen
`decayScale = 1.0`, Filler erben den ihres Elternimpulses.

Gezogen wird **je Zweig und je Größe einzeln**: ein gemeinsamer Zufallswert
für alle Zweige eines Treffers würde die Geschwister wieder gleichschalten,
also genau das nicht lösen, worum es geht.

`SplitVariance.jitter()` klemmt den Faktor nach unten auf `MIN_FACTOR = 0.05`.
Bei voller Stärke und einem Zufallswert von 0 wäre er sonst exakt 0 — ein Kind
mit Speed 0 stünde für immer still, eines mit `decayScale` 0 verlöre nie
Energie und stürbe nie. Zwei unsterbliche Zustände, die das Netz über eine
Nacht volllaufen lassen.

**Split-Anzahl und Split-Versatz** (`SplitFanout.java`, `SplitStagger.java`,
angewandt in `activationEncounteredNode()` / `spawnSplitChildren()` /
`releasePendingSplits()`): eine Aufspaltung nahm bis 2026-08-01 immer **alle**
moeglichen Zweige, alle im selben Frame. Jetzt wird gewuerfelt, wieviele es
werden, und die gewaehlten koennen im BPM-Raster nacheinander starten.

- `/net/impulse/split/weight/{all,oneLess,single}` (float 0..100, Defaults
  **100/0/0**) — Gewichte der drei Kategorien, normalisiert in
  `SplitFanout.branchCount()` wie bei den Speed-Klassen
- `/net/impulse/split/staggerEnabled` (int 0/1, Default **0**)
- `/net/impulse/split/stagger/weight/{whole,half,quarter,eighth,sixteenth,simultaneous}`
  (float 0..100, Defaults **0/0/10/30/60/0**) — ein Gewicht je Klasse,
  Reihenfolge wie `OriginSequencer.NOTE_VALUES`, dahinter „gleichzeitig".
  Gezogen wird in `SplitStagger.pickNoteValue()`, normalisiert wie überall.

Zusammen ergeben die Auslieferungswerte **bitgleich** das vorherige Verhalten
(`staggerEnabled = 0` — die Gewichte werden dann gar nicht erst gelesen).

Acht Dinge, die man beim Ändern kennen muss:

- **Die Kategorien sind relativ, nicht absolut.** „Einer weniger" statt
  „2 Zweige": ein Knoten hat je nach Rand des Stripes und Richtung des
  Elternimpulses mal drei, mal weniger **mögliche** Zweige — `data/nodeCrossings.txt`
  führt zwar nur Kreuzungen aus zwei Stripes, die Zweigzahl ist aber trotzdem
  nicht fest. Eine absolute Zahl bedeutete an jedem Knoten etwas anderes und
  wäre an manchen gar nicht erfüllbar. Bei zwei Kandidaten fallen „einer
  weniger" und „genau einer" zusammen — die richtige Antwort, kein Fehler.
- **Nie 0 Zweige bei vorhandenen Kandidaten.** Ein Impuls, der an einer
  Kreuzung spurlos verschwindet, wäre ein Loch im Netz ohne Fehlermeldung, und
  die `nodeDeadTime` des Knotens wäre trotzdem verbraucht. Der entartete Fall
  (keine Gewichte, NaN) fällt auf „alle" zurück, also auf das Verhalten von
  vor dem Feature.
- **Ein Notenwert je Aufspaltung, nicht je Zweig.** Der Versatz nahm bis
  2026-08-01 einen einzigen fest eingestellten Notenwert — jeder Split im
  ganzen Betrieb klang gleich. Gezogen wird jetzt je **Split-Ereignis**: alle
  Kinder desselben Splits stehen auf demselben Raster, `delayBeats()` bleibt
  `slot * beatsPerNote(noteValue)`. Je Kind gezogen wären schon die Abstände
  *innerhalb* einer Aufspaltung ungleich — genau die Gleichmäßigkeit weg, an
  der ein Rhythmus überhaupt zu erkennen ist. Variieren soll die Aufspaltung
  als Ganzes: mal eine dichte Sechzehntel-Figur, mal ein weiter
  Viertel-Abstand. Der entartete Fall (alle Gewichte 0, NaN) fällt auf
  **Sechzehntel** zurück, den kürzesten Versatz und den früheren Default des
  ersetzten Reglers — dieselbe Regel wie „alle Zweige" bei `SplitFanout` und
  „1x" bei `SpeedQuantizer`. Bei `staggerEnabled = 0` wird nicht gezogen.
- **„Gleichzeitig" ist die sechste Klasse und kein Notenwert** (seit
  2026-08-02, Auslieferungsgewicht **0**): sie lässt **alle** Zweige einer
  Aufspaltung im selben Moment starten, nicht nur den ersten — der startet
  ohnehin immer sofort (`delayBeats`, `slot <= 0`), das ist also gerade nicht
  das Gesuchte. Vier Dinge:
  - **`SplitStagger.SIMULTANEOUS_NOTE_VALUE = 0` ist ein Sentinel**, kein
    Notenwert. 0 hat als Notenwert keine Bedeutung („null Noten je Ganzer")
    und taucht in keiner OSC- oder Preset-Datei als echter Wert auf. Er wird
    in `delayBeats()` **vor** der Rasterung abgefangen: liefe er weiter,
    machte `OriginSequencer.quantizeNoteValue(0)` daraus die **ganze** Note
    (kein erlaubter Notenwert ist ≤ 0, also gewinnt der erste der Liste) und
    aus „alle gleichzeitig" würde der weiteste Versatz der ganzen Tabelle.
    `MusicalClock.beatsPerNote()` sieht ihn deshalb nie.
  - **`NOTE_COUNT` (5) und `CLASS_COUNT` (6) sind zwei verschiedene Zahlen.**
    `NOTE_COUNT` bleibt `OriginSequencer.NOTE_VALUES.length` — die fünf echten
    Notenwerte sind weiter die mit dem Sequencer **geteilte** Quelle, und
    „gleichzeitig" ist ausdrücklich SplitStagger-exklusiv, keine Ergänzung des
    Sequencers. An `CLASS_COUNT` hängt dagegen alles Gezogene: Adressen,
    Scratch-Array, `WeightedChoice.pick()`.
  - **Der Index steht hinten** (`SIMULTANEOUS_INDEX = CLASS_COUNT - 1`). Ein
    eingeschobener Index verschöbe die Zuordnung Regler→Klasse und damit
    stillschweigend jedes gespeicherte Preset.
  - **Sie ist NICHT der neue Rückfall.** `NEUTRAL_NOTE_INDEX` bleibt
    Sechzehntel: der Rückfall soll das Verhalten von vor der jeweiligen
    Änderung bedeuten, und eine unbrauchbare Gewichtstabelle hat vor der
    sechsten Klasse Sechzehntel bedeutet. Ihn umzuhängen wäre eine
    Verhaltensänderung, die kein Regler zeigt.
- **Der Versatz zählt in Beats, nicht in Millisekunden.** Die Fälligkeit kommt
  aus `MusicalClock`, derselben Phase, auf der der Origin-Sequencer läuft — ein
  Tempowechsel ändert damit die Rate, nicht die Position. Die Uhr läuft
  unabhängig von `/net/sequencer/enabled` weiter, der Versatz braucht den
  Sequencer also nicht; er teilt nur seine Phase. Deshalb steht
  `releasePendingSplits()` in `drawMe()` **hinter** `tickSequencer()`.
- **Ein wartendes Kind verliert keine Energie.** Der Zerfall hängt an der
  Wanduhr und machte ein später startendes Geschwister systematisch dunkler als
  seinen Zwilling — der Versatz soll rhythmisch sein, nicht auch noch eine
  Dynamikstufe. Bei den üblichen Werten ginge es ohnehin um 0,005 Energie.
- **Ein nicht genommener Kandidat verbraucht keine Impuls-ID.** Kandidaten sind
  `PendingSpawn`-Objekte, keine `TravellingActivation`. Die IDs sind der
  Schlüssel des Positionsstroms `/net/impulse`; eine Lücke darin wäre auf der
  Klangseite eine Drohne, die nie kommt.
- **`SplitFanout.chooseOrder()` liefert die Zweige gemischt**, nicht nur als
  Menge: die Reihenfolge bestimmt, welcher Zweig sofort startet und welcher
  versetzt. Die Kandidatenreihenfolge wäre ein Vorrang für den kleinsten
  LED-Index — ein stiller Bias, den kein Regler zeigt. `SplitStagger.MAX_PENDING`
  (512) deckelt die Warteschlange gegen einen Fall, den ein Regler herstellen
  kann (langer Notenwert bei niedriger BPM); abgewiesen wird der **neue**
  Eintrag, nicht ein wartender.

**Ein `/net/hitNode` je Kind, nicht je Treffer** (Bugfix 2026-08-02): der
Aufruf stand in `activationEncounteredNode()`, also einmal pro Node-Treffer —
eine Stelle aus der Zeit, als eine Aufspaltung noch immer **alle** möglichen
Zweige im selben Frame nahm und „ein Treffer" und „ein Impuls" dasselbe
bedeuteten. Seit `SplitFanout` und `SplitStagger` werden daraus ein, zwei oder
mehr Kinder, die ausserdem zeitversetzt starten können; gemeldet wurde
trotzdem genau eines. Symptom: bei einer Aufspaltung in zwei Zweige erzeugte
nur der erste Impuls Klang, jeder weitere lief stumm durchs Netz. Gesendet
wird jetzt in `spawnSplitChildren()` (sofortige Kinder) und
`releasePendingSplits()` (zeitversetzte). Vier Dinge:

- **Erst beim Start, nicht beim Einreihen.** Ein zeitversetztes Kind meldet
  sich, wenn es losfährt — beim Scheduling gesendet klänge die ganze
  Aufspaltung wieder als ein einziger Schlag, also genau das, was der Versatz
  auflösen soll.
- **`sendOscMessage()` nimmt den `PendingSpawn`**, nicht die
  `TravellingActivation`: die entsteht am Ort des sofortigen Spawns erst
  danach, und beim zeitversetzten Kind trägt ohnehin nur der `PendingSpawn`
  den Knoten, an dem es entstanden ist. Ein Kandidat, der nicht genommen wird,
  kommt dort nie an und meldet also auch nichts.
- **`PendingSpawn` trägt `nodeId`/`nodePosX`/`nodePosY` als Werte**, nicht eine
  `LedNetworkNode`-Referenz. `LedStripeNetworks.java` hängt an
  `processing.core`, und `SplitStagger.java` soll ohne auskommen; ausserdem
  baut `applyCrossings` bei `R` in der Kalibrierung **alle** Knotenobjekte neu
  auf — eine gehaltene Referenz zeigte danach auf einen Knoten, den es so nicht
  mehr gibt, während die drei Werte den Stand des Treffers tragen.
- **Ein Treffer ohne einen einzigen möglichen Zweig bleibt jetzt stumm**
  (Knoten am äussersten LED-Index), wo er vorher eine Glocke auslöste. Das ist
  die Zusage dieses Verhaltens — kein Impuls, kein Ton —, kein übersehener
  Fall.

Die gewichtete Ziehung selbst steht in `WeightedChoice.java` und wird von
`SpeedQuantizer`, `SplitFanout` und `SplitStagger` geteilt. Zwei Kopien wären
zwei Regeln für dieselbe Sache: eine Nachbesserung an der einen ginge an der
anderen still vorbei. Aus demselben Grund liegt die Notenwert-Liste **einmal**
in `OriginSequencer.NOTE_VALUES` (package-privat, dazu `noteValueAt()`) und
wird von `SplitStagger` mitbenutzt — eine sechste Klasse dort ergänzt und hier
vergessen, und „Sechzehntel" hieße im Sketch an zwei Stellen etwas anderes.

Im Web-UI sitzt das Ganze als Sektion „Split-Verhalten" im Tab
**Noten-Verhalten** — der Versatz hängt am BPM-Raster wie die Speed-Klassen
daneben, und die zwei Hälften eines Features gehören auf denselben Tab. Die
Sektion zeigt **zwei** Verteilungen untereinander (Zweigzahl, Notenwert des
Versatzes), beide über dieselbe Hilfsfunktion `weightBank()` in `app.js` wie
die Speed-Klassen; die zweite bekommt einen Farbversatz, sonst läse man sie als
Fortsetzung der ersten statt als eigene Sache. Die
Regel in `TAB_RULES` trägt einen abschliessenden Schrägstrich
(`/net/impulse/split/`), sonst zöge sie die älteren `splitSpeedJitter`- und
`splitLifetimeJitter`-Adressen aus der Physik mit.

**Origin-Sequencer** (`MusicalClock.java`, `OriginSequencer.java`, getickt aus
`drawMe()` über `tickSequencer()`): der strukturierte Spawn-Layer neben dem
chaotischen `randomSpawn`. Beide laufen unabhängig und sind gleichzeitig
aktivierbar. Sechs Tracks feuern auf einem gemeinsamen BPM-Raster, jeder von
einem Ursprungs-Stripe, auf dem er `repeatCount` Zyklen stehen bleibt, bevor er
neu würfelt — von demselben Ursprung wiederholt spawnen erzeugt (fast) dieselbe
Melodie, das ist der Zweck.

- `/net/sequencer/enabled` (int 0/1, Default **0**), `/net/sequencer/bpm`
  (float, 20..200, Default 60)
- je Track N = 0..5 unter `/net/sequencer/track<N>/`: `enabled` (int 0/1,
  Default 1 für Track 0 und 1, sonst 0), `noteValue` (int 1..16, gerastet auf
  1/2/4/8/16), `repeatCount` (int 1..8, Default 3), `energy` (float 0..1,
  Default 0.6), `swingJitter` (float 0..1, Default **0**),
  `originTreeFilter` (int 0..4, Default **0**), `originStripeOverride`
  (int, -1 = zufällig)

**Baum-Origin-Filter** (`StripeTreeStore.java`, `data/stripeTrees.txt`): ein
Track kann seinen Ursprungs-Vorrat auf einen der vier physischen Bäume
einschränken. `originTreeFilter` 0 = alle Stripes, 1 = vorn, 2 = hinten,
3 = rechts, 4 = links (Reihenfolge = `StripeTreeStore.TREE_NAMES`, das Web-UI
zeigt Klartext).

Die Datei hat vier Tab-Spalten `stripeIndex baum confidence distanceMeters`,
`#` leitet einen Kommentar ein. Sie ist **automatisch erzeugter Best-Guess**
und wird von Birk von Hand korrigiert — daraus folgen drei Regeln:

- **Bei doppeltem Stripe gewinnt die LETZTE Zeile.** Die natürliche
  Handkorrektur ist eine angehängte Zeile am Ende; „erste gewinnt" würde sie
  still verschlucken. Die Überschreibung wird gemeldet.
- **Index und Baum genügen**, `confidence`/`distanceMeters` sind optional —
  beim Korrigieren soll niemand eine Distanz erfinden müssen.
- **`confidence` wird gelesen, aber nicht ausgewertet.** „unsicher" ist eine
  Notiz für die Handkorrektur, kein Laufzeitverhalten; die Anzahl steht im
  Startbericht, damit sie nicht in Vergessenheit gerät.

Drei Dinge, die man beim Ändern kennen muss:

- **Vorrangregel:** `originStripeOverride >= 0` schlägt den Baum-Filter. Der
  Filter wirkt nur bei `-1` und schränkt dann den Zufalls-Pool ein — auch
  beim Nachwürfeln nach Ablauf von `repeatCount`, weil `pickStripe()` der
  einzige Ort ist, an dem ein Ursprung entsteht.
- **Ein leerer Pool zählt wie kein Filter.** `stripesFor()` liefert für einen
  Baum ohne Stripes `null`, nicht ein leeres Array. Sonst verstummte der Track
  nach dem Einschalten eines Filters — ein Fehlerzustand ohne Symptom.
- **Der Sequencer kennt keine Bäume.** `TrackConfig.originPool` wird vom
  Effekt gefüllt (Referenz auf das im Store gecachte Array, keine Kopie —
  `tickSequencer()` läuft mit 40 Hz). Damit hängt die Prüfbarkeit des
  Sequencers nicht an einer Datei.

Vier Dinge, die man beim Ändern kennen muss:

- **`MusicalClock` akkumuliert**, statt `(now - t0)/beatDuration` zu rechnen.
  Naiv gerechnet springt die Phase bei jeder BPM-Änderung — die seit
  Sketch-Start verstrichene Beat-Zahl rechnet sich rückwirkend um und alle
  Tracks feuern schlagartig durcheinander. Akkumuliert ändert ein Tempowechsel
  nur die Rate, nie die Position.
- **Die Uhr läuft auch bei `enabled=0` weiter.** Sie ist die gemeinsame Phase;
  ein Stillstand während der Aus-Phase machte das Wiedereinschalten von der
  Dauer der Pause abhängig.
- **Es gibt kein `/net/sequencer/activeTracks`.** Zwei Schalter für dieselbe
  Sache erzeugen einen stillen Fehlerzustand: der Operator schaltet Track 4
  ein, es passiert nichts, weil `activeTracks=3` ihn abschneidet — kein Fehler,
  kein Symptom, nur Stille. `enabled` je Track ist außerdem ausdrucksstärker
  (jede Teilmenge statt nur ein Präfix), und der grobe Not-Aus existiert schon.
- **Keine Kopplung an den Preset-Scheduler.** Sequencer-Timing ist BPM und
  Notenwerte, Preset-Timing bleibt Sekunden und Minuten; die zwei Zeitsysteme
  wissen nichts voneinander.

`OriginSequencer` baut ausdrücklich **keine** `TravellingActivation` — das
bleibt im Effekt, der die Objekte, die Geschwindigkeit und die Stripe-Länge
kennt. Zufall und Beat-Position werden hereingereicht (`RandomSource`), damit
die Klasse ohne Sketch-Laufzeit prüfbar ist; dasselbe Muster wie
`ImpulseOscThrottle` und `PresetScheduler`.

**Quantisierte Spawn-Geschwindigkeit** (`SpeedQuantizer.java`, angewandt in
`spawnSpeed()`): nicht nur *wann* gespawnt wird ist rhythmisch, sondern auch
*wie schnell* der einzelne Impuls reist. Fünf Klassen im Verhältnis
1:2:4:8:16 — **0.5x, 1x, 2x, 4x, 8x** — dieselben Abstände wie die Notenwerte
des Sequencers, nur auf der Geschwindigkeit. Gezogen wird je Spawn nach
Gewichten, sodass ein 8x-Ausreißer selten und dadurch besonders ist.

- `/net/impulse/speedQuantize/enabled` (int 0/1, Default **0**) — bei 0
  bekommt jeder Spawn exakt `impulseSpeed` wie bisher, ohne Ziehung
- `/net/impulse/speedQuantize/weight/{0x5,1x,2x,4x,8x}` (float 0..100,
  Defaults 0/85/10/4/1) — die Summe muss **nicht** 100 sein, normalisiert wird
  in `SpeedQuantizer.pick()`
- `/net/impulse/speedQuantize/jitter` (float 0..1, Default **0**) — Swing auf
  der gezogenen Klasse, gleiche Formel wie überall (`SplitVariance.jitter`)

Vier Dinge, die man beim Ändern kennen muss:

- **Referenz der 1x-Klasse ist `/net/impulse/speed` selbst**, kein eigener
  Parameter. Ein zweiter Referenzregler daneben hieße zwei Zahlen, die beide
  „die Geschwindigkeit" heißen, und die Zeitbasis-Kopplung (`lifetime`,
  `nodeDeadTime`, `randomSpawn/interval` ziehen im Web-UI an `impulseSpeed`
  mit) hätte einen zweiten, unbeteiligten Bezugspunkt.
- **`spawnSpeed()` ist der einzige Ort, an dem eine Spawn-Geschwindigkeit
  entsteht.** Alle fünf Spawn-Pfade gehen hindurch: `/tube/trigger`,
  `/net/activateStripe`, `/net/activateNode`, `spawnRandomImpulses()`,
  `tickSequencer()`. Ein sechster Pfad, der `impulseSpeed` direkt liest, wäre
  ein Impuls, den die Klangseite falsch einordnet.
- **Split-Kinder ziehen nicht neu.** Sie erben die schon vervielfachte
  Geschwindigkeit ihres Elternimpulses und bekommen obendrauf
  `splitSpeedJitter` — ein 4x-Impuls bleibt beim Aufspalten ein 4x-Impuls.
- **Gezogen wird je Impuls, nicht je Ereignis** — außer bei
  `/net/activateNode`, wo die zwei Richtungen desselben Anstoßes
  zusammengehören und deshalb eine gemeinsame Ziehung teilen.

**Offen, bewusst nicht gebaut:** eine Selbstregulation, die die Spawn-Rate an
die aktuelle Netzauslastung koppelt (viele aktive Impulse → Sequencer- und
RandomSpawn-Rate dämpfen). Vorgeschlagen, aber nie bestätigt; bleibt ein
Vorschlag, kein deaktivierter Stub.

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

### Topologiebasierte Melodiekomposition (MelodyModes/MelodyGraph/MelodyAssigner/NodeMelodyStore/MelodyManager)

Die Note eines Knotens kam bis 2026-08-01 aus `nodeId % notesPerOctaveSet`.
Die `nodeId` ist die Zeilennummer in `nodeCrossings.txt` und hat **keinen
Bezug zur physischen Nachbarschaft** — Node 5 und Node 6 können an
entgegengesetzten Enden der Installation liegen. Ein Impuls, der von Knoten
zu Knoten wandert, traf deshalb auf eine Folge von Tönen mit rein zufälligem
Abstand: mal ein Halbton, mal ein Tritonus. Das klang nach Skalentönen in
zufälliger Reihenfolge, nicht nach einer Melodie.

Stattdessen wird die Note jetzt **einmalig aus der Graphstruktur** vergeben:
eine Breitensuche vom Startknoten aus, bei der jeder neu erreichte Knoten
seine Skalenstufe **relativ zu dem Nachbarn** bekommt, über den er zuerst
erreicht wurde. Damit ist jede Baumkante ein regelkonformes Intervall, und
jeder mögliche Impulsweg — egal wie lang — ist eine Kette solcher Intervalle.
Konzept: `docs/superpowers/specs/2026-08-01-topologiebasierte-melodiekomposition-konzept.md`,
Umsetzungsplan: `docs/superpowers/plans/2026-08-01-topologiebasierte-melodiekomposition-plan.md`.

Vier reine Klassen ohne Processing/oscP5 (in `test/run.sh`) plus eine dünne
Klebeschicht mit OSC:

- `MelodyModes` — die acht Modi als Tonvorrat **plus** Kantenregel. Zwei Modi
  mit gleicher Tonmenge, aber verschiedener Wegeregel sind zwei verschiedene
  Modi (Ajam gegen Ionisch). Reihenfolge A–H: Dorisch, Moll-Pentatonik, Maqam
  Hijaz, Harmonisch Moll, Phrygisch, Maqam ʿAjam, Maqam Nikriz, Maqam Saba.
- `MelodyGraph` — die Nachbarschaft aus `nodeCrossings.txt` +
  `numLedsPerStripe`: zwei Nodes sind benachbart, wenn sie auf **derselben**
  Stripe liegen und dort unmittelbar aufeinanderfolgen.
- `MelodyAssigner` — BFS, Landmarken-Rotation, Oktavfaltung.
- `NodeMelodyStore` — `data/nodeMelody_<modus>.txt` schreiben und lesen.
- `MelodyManager` — die vier OSC-Parameter und `/net/melody/recompute`. Kennt
  oscP5 und darf **nicht** in `test/run.sh`, genau wie `PresetManager`.

**Die zwei Regeln, ohne die das Verfahren nicht funktioniert** (beide erst in
der zweiten Fassung des Konzepts dazugekommen, weil im ersten Entwurf nicht
bedacht):

- **Landmarken-Rotation.** Eine Kante zu einem Hub-Knoten bekommt nicht immer
  die Quint, sondern rotiert über `STABLE_INTERVALS = { +4, -3, +2 }`
  Skalenstufen nach `(BFS-Tiefe + Rang in der Nachbarliste) mod 3`. „Immer
  Quint" macht zwei Dinge kaputt: alle Landmarken-Geschwister eines Knotens
  landen auf **exakt demselben Ton** (Verdopplung und Phasing statt Akkord),
  und jede Landmarken-Ebene addiert +4 Stufen — unbegrenzte Oktavdrift.
  `+4` und `-3` liegen auf **derselben** Skalenstufe in verschiedenen Oktaven,
  der Ankercharakter bleibt also erhalten; der Mittelwert sinkt von +4 auf +1
  und wechselt das Vorzeichen. **Beide Summanden werden gebraucht**: nur
  `tiefe` gäbe allen Landmarken einer Ebene dasselbe Intervall (Ton-Stapel
  verschoben statt aufgelöst), nur `rang` gäbe jedem ersten Nachbarn immer
  `+4` (Drift entlang eines langen Weges ungebremst).
- **Oktavfaltung als harte Grenze.** `((raw % n) + n) % n` mit
  `n = scale.length * numOctaves`. Die Rotation *dämpft* die Drift, sie
  beschränkt sie nicht — auch die vorzeichenbehaftete Ziehung auf den
  normalen Kanten hat zwar Mittelwert 0, jede einzelne Realisierung ist aber
  eine Irrfahrt. Der **doppelte Modulo** ist Pflicht: durch die `-3` kann der
  Rohwert negativ werden, und Javas `%` liefert dann ein negatives Ergebnis.
  Der **Startknoten sitzt in der mittleren Oktave**
  (`scale.length * (numOctaves / 2)`) — läge die Tonika bei 0, bräche jedes
  Abwärtsintervall sofort um und die Rotation wäre wirkungslos bis schädlich.

**Zwei Kantenklassen ohne Regel-Garantie, beide gezählt und im Dateikopf
genannt.** Der Graph ist nicht kreisfrei; **Rückwärtskanten** (Kanten auf
einen schon besuchten Knoten) werden nicht mehr bewertet, ihr Intervall ist
eine Restgröße. **Umbruchkanten** sind Baumkanten, bei denen die Faltung das
gewählte Intervall zerrissen hat. Es gibt bewusst **keine**
Nachbearbeitungsstufe, die sie glättet (Birk, 2026-08-01). Sie zu zählen ist
der Unterschied zwischen einem bekannten Kompromiss und einem stillen Fehler.
Am echten Datenstand: 92 Knoten, 154 Kanten, **63 Rückwärtskanten** (41 %,
deutlich mehr als die im Konzept geschätzten 10–20 %) und 4–10 Umbruchkanten
je nach Modus.

**Die Datei entsteht nur auf ausdrücklichen Trigger.** `data/nodeMelody_<modus>.txt`,
Format nach dem Vorbild von `stripeTrees.txt`: Kopfzeilen mit `#`, dann
`nodeId <TAB> scaleIndex <TAB> midiNote <TAB> tiefe`. **Maßgeblich ist
`scaleIndex`** — `midiNote` ist eine abgeleitete Kontrollspalte zum Mitlesen,
`tiefe` reine Diagnose, beide werden beim Laden nicht ausgewertet. Bei
doppeltem `nodeId` gewinnt die **letzte** Zeile (die Handkorrektur wird
angehängt). Beim Start wird nur **gelesen**: automatisches Neurechnen machte
die Datei zu einem Zwischenspeicher statt zu einer Quelle, und eine von Hand
korrigierte Zeile wäre beim nächsten Start weg, ohne Meldung.

**Vier Parameter, ein Trigger, ein atomarer Vorgang:**

- `/net/melody/mode` (int 0..7, Default **4** = Phrygisch — der einzige der
  acht, der den heute live laufenden Tonvorrat unverändert lässt, also der
  Modus für einen A/B-Vergleich, bei dem sich genau eine Sache ändert)
- `/net/melody/startNode` (int 0..`nodeCount-1`, Default = Knoten mit dem
  höchsten Grad). **Die Obergrenze kommt zur Laufzeit aus dem Graphen**, nicht
  als Literal (CLAUDE.md-Konvention: keine von der Kreuzungszahl abgeleitete
  Zahl als Literal).
- `/net/melody/rootMidiNote` (int 24..84, Default 45 = A2)
- `/net/melody/numOctaves` (int 1..6, Default 3)
- `/net/melody/recompute` — **Kommando**, kein Parameter. Erst dieses rechnet,
  schreibt die Datei und schickt `/net/melody/reload <modus>` an 8002.

**Warum ein gemeinsamer Trigger und nicht drei.** Die vier Werte sind für sich
harmlos; drei unabhängige Trigger rechneten beim Verstellen von dreien
dreimal, davon zweimal mit einem halb gesetzten Zustand. Und `numOctaves`
ändert den **Modulo-Teiler der Faltung selbst** — eine mit dem alten Wert
gerechnete Zuordnung ist danach nicht transponiert, sondern schlicht falsch.
Alle vier stehen in `PresetStore.EXCLUDED`: Transport, nicht Inhalt, und ein
Preset kann zwischen „alte Zuordnung" und „neue Zuordnung" nicht überblenden.

**`hubThreshold` ist bewusst kein OSC-Parameter** (`MelodyManager.HUB_PERCENTILE`,
75. Perzentil). Er löste wie `startNode` eine Neuberechnung aus und wäre ein
fünfter Regler, dessen Wirkung sich nur im Vergleich zweier kompletter Läufe
zeigt. Der konkrete Wert bleibt laut Konzept offene Geschmacksfrage — am
echten Datenstand gelten damit 46 der 92 Knoten als Landmarke.

**Unerreichbare Knoten** (andere Zusammenhangskomponente, etwa während einer
laufenden Kalibrierung) bekommen die Tonika und werden **gezählt**. Das
Konzept sagt dazu nichts; ein unbestimmter Wert wäre ein stiller Ausfall, ein
Abbruch hänge die Show an einer Kalibrierlücke auf.

**Der Graph wird beim Start gebaut und bei `R` in der Kalibrierung NICHT neu.**
Eine Zuordnung, die zur alten Topologie gehört, bleibt gültig, bis jemand
ausdrücklich neu rechnet; der Startbericht meldet eine abweichende Knotenzahl.

### Ursprungs-Baum am Impuls (Klangbias)

`TravellingActivation` trägt seit 2026-08-01 ein `final int originTree`
(0..3 nach `StripeTreeStore.TREE_NAMES`, -1 = unbekannt), neben `final int id`
und `final float decayScale` und aus demselben Grund: es ist eine Eigenschaft,
die der Impuls bei der Geburt bekommt und bis zum Tod behält.

- **Genau eine Stelle, an der der Wert entsteht:** `spawnTree(stripeIdx)`,
  analog `spawnSpeed()`. Alle fünf Spawn-Pfade gehen hindurch.
- **Vererbt an Split-Kinder und Filler**, die ziehen nicht neu. Ohne das wäre
  der Bias nach dem ersten Split weg, und zwar lautlos.
- **Der Konstruktor ohne den Wert wurde entfernt.** Damit weist der Compiler
  jede Konstruktionsstelle aus, die ihn vergäße — dieselbe strukturelle
  Absicherung wie bei `id`, statt einer Konvention, an die man sich erinnern
  müsste.
- **Fünftes Argument an `/net/hitNode`**, rein angehängt (wie seinerzeit `x`/`y`
  dort und `speed` an `/net/impulse`). Er kommt von `curActivation`, nicht von
  `hitNode`: er beschreibt den **Impuls**, nicht den getroffenen Knoten.

Auf der Klangseite ersetzt er den Zonen-Bias vollständig (siehe „Klangseite").
Der Grund ist nicht Geschmack, sondern **Intervallerhaltung**: der alte
Zonen-Offset wurde auf den Index des *getroffenen* Knotens addiert, zwei
benachbarte Knoten beiderseits einer Quadrantengrenze bekamen also
verschiedene Offsets — und genau die Intervalle, die die topologische
Zuordnung setzt, wären an jeder Grenze wieder zerlegt worden. Der
Ursprungs-Bias ist für einen gegebenen Impuls **konstant** und transponiert
seinen ganzen Weg gleichmäßig: vier Bäume ergeben vier Transpositionen
derselben Melodie statt vier Störungen darin.

**`/net/impulse` trägt den Baum nicht.** Die Drohnen laufen deshalb mit
neutralem Bias. Bewusst so, statt dort den alten Zonen-Bias stehen zu lassen:
sonst hätten Glocke und Drohne desselben Impulses zwei verschiedene
Herkunftsbegriffe, und der eine wechselte im Flug, der andere nicht. Ein halb
umgestellter Mechanismus ist schwerer zu hören als ein ganz abgeschalteter.

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
`/sc/preset/load <name>` an `127.0.0.1:8002` und bei jedem erfolgreichen
Speichern `/sc/preset/save <name>` — derselbe Port, auf dem SuperCollider
schon `/net/hitNode` empfängt, nur zwei neue Adressen. Es gibt genau einen
Scheduler, deshalb können Licht und Klang nicht auseinanderlaufen.
Fire-and-forget: läuft sclang nicht, läuft die Visual-Show weiter.

Das Speichern wird **nach** dem erfolgreichen eigenen Schreiben weitergereicht
— sonst legte ein fehlgeschlagenes Licht-Preset trotzdem ein Klang-Preset an,
und der Name stünde danach nur auf einer der zwei Seiten. Ohne die
Weiterleitung erfasste „Speichern" im Web-UI still nur das Licht: die Szene
käme später optisch zurück und klanglich nicht, ohne Fehlermeldung. Was
SuperCollider damit tut, steht unter „Klangseite".

### Song-Struktur-Dramaturgie (SongStructureDirector.java, EnergyLevelStore.java, SongStructureParams.java)

Die Dramaturgie über eine ganze Nacht: eine **Markov-Kette über vier
Energie-Level**, die bei jedem fälligen Wechsel zuerst das nächste *Level*
würfelt und danach ein Preset *innerhalb* dieses Levels wählt. Diese zwei
Stufen sind der ganze Unterschied zum alphabetischen Umlauf — ein flacher
Zufall über alle Presets kennt keinen Spannungsbogen. Konzept und Abwägungen:
`docs/superpowers/specs/2026-08-01-song-struktur-dramaturgie-konzept.md`,
Umsetzungsplan: `docs/superpowers/plans/2026-08-01-song-struktur-dramaturgie.md`.

**`PresetScheduler.java` ist dabei unverändert geblieben.** Die neue Schicht
sitzt **oberhalb** und ersetzt nicht dessen Zeitlogik, sondern die
**Namensquelle**, die er bedient. Geladen wird über den bestehenden Weg
(`PresetManager.load()`, das `scheduler.noteLoaded()` ohnehin ruft) — der im
Konzept angedachte Umweg über `advance(now, singletonList(name))` entfällt.

Die vier Level heissen `ruhig | mittel | dynamisch | dramatisch`
(`EnergyLevelStore.LEVEL_NAMES`). **Diese Reihenfolge ist die Indexreihenfolge
überall**: in der Matrix, in den Verweildauern, in `/songStructure/goto` und im
Web-UI. Sie steckt ausserdem in den Adressen selbst
(`/songStructure/matrix/<von>/<nach>`) — eine Umsortierung wäre also nicht nur
eine falsche Beschriftung.

**Die Klassifikation ist manuell** (`data/energyLevels.txt`, Format
`name <TAB> level`, `#` als Kommentar — Stil wie `data/stripeTrees.txt`). Rund
fünfzig Parameter tragen sehr unterschiedlich und nicht linear zur
wahrgenommenen Energie bei, die Farben tragen ebenfalls bei, ohne sich in
einer Formel fassen zu lassen; eine Gewichtungsformel wäre eine Blackbox, die
öfter korrigiert als bestätigt würde. Das Level ist eine **künstlerische**
Einschätzung und wird vergeben, wenn ein Preset am Gerät gehört und gesehen
wurde.

Drei Regeln der Datei, die man kennen muss:
- **Sie liegt in `data/`, NICHT in `data/presets/`** — anders als im Konzept
  vorgeschlagen. `PresetStore.list()` sammelt jede `*.txt` im Preset-Ordner
  ein; `energyLevels.txt` entginge dem heute nur, weil `isValidName()`
  Grossbuchstaben ablehnt. Eine Umbenennung nach `energylevels.txt` machte
  daraus stillschweigend ein Preset, das der Scheduler zu laden versucht.
- **Bei doppeltem Namen gewinnt die LETZTE Zeile**, wie bei
  `stripeTrees.txt`: die natürliche Handkorrektur ist eine angehängte Zeile.
- **Gefiltert wird gegen die Liste der vorhandenen Presets**, nicht aus der
  Datei heraus. Ein Eintrag, dessen Preset-Datei gelöscht wurde, wäre sonst
  ein Name, den der Director bei jedem Besuch dieses Levels vergeblich lädt.
  Fehlt ein Preset in der Datei, gilt **mittel** — nicht ruhig (das dämpfte
  einen unklassifizierten dramatischen Moment fälschlich) und nicht
  dramatisch (das verschärfte ihn fälschlich); die Zahl steht im Startbericht.

**Übergangsmatrix** (Auslieferungswerte, Zeile = aktuelles Level, Zeilensumme
100, aber nicht erzwungen):

| von \ nach | ruhig | mittel | dynamisch | dramatisch |
|---|---|---|---|---|
| **ruhig** | 20 | 40 | 30 | 10 |
| **mittel** | 25 | 30 | 30 | 15 |
| **dynamisch** | 35 | 30 | 20 | 15 |
| **dramatisch** | 60 | 25 | 10 | 5 |

Der Kern ist die Anti-Monotonie-Regel: nach `dramatisch` gehen 85 % zurück
nach ruhig/mittel, und ein sofortiges zweites `dramatisch` ist mit 5 % der
seltenste Übergang der ganzen Matrix — aber nicht unmöglich. Gezogen wird über
`WeightedChoice.pick()`, dieselbe Rechnung wie beim `SpeedQuantizer`
(normalisiert, Gewicht 0 wird **nie** gezogen, eine Nullzeile fällt auf
*mittel* zurück).

**Verweildauern** (Birk, 2026-08-01 — bewusst kurze Zyklen zum Ausprobieren;
das Konzept schlug 15–35 / 10–20 / 6–14 / 3–8 Minuten vor):

| Level | Verweildauer |
|---|---|
| ruhig | 3–5 min |
| mittel | 2–3 min |
| dynamisch | 1–2 min |
| dramatisch | 0,5–1 min |

Gleichverteilt gezogen, sobald ein Level beginnt. **Nicht** in jedem Frame neu
— sonst käme der Wechsel, sobald einmal eine kleine Zahl gezogen wird.

Sechs Dinge, die man beim Ändern kennen muss:

- **Die gezogene Dauer wird bei jedem `isDue()` auf die AKTUELLE Spanne
  geklemmt.** Ohne diese Klemmung hätte ein Operator, der während eines
  laufenden Abschnitts von 30 auf 3 Minuten verengt, trotzdem noch 30 Minuten
  zu warten — ohne Fehler, ohne Symptom, nur Stillstand.
- **Vorrang vor dem alphabetischen Wechsler.** Ist `/songStructure/enabled`
  an, übernimmt die Ebene, und `PresetScheduler` zieht nur seinen Timer mit
  (`isDue(now, false, …)`). Zwei Wechsler auf derselben Szene nähmen sich
  gegenseitig die Presets weg — ohne Fehlermeldung, nur mit einem Bild, das
  öfter springt als eingestellt. Der mitgezogene Timer sorgt dafür, dass ein
  späteres Abschalten der Song-Struktur nicht sofort einen Wechsel auslöst.
- **`noteLoaded()` synchronisiert bei jedem `/preset/load`** Level und Timer
  auf das geladene Preset, und merkt es als „zuletzt in diesem Level". Sonst
  überschriebe der nächste fällige Wechsel einen manuellen Eingriff womöglich
  Sekunden später — der Eingriff wäre sinnlos, ohne dass das jemand sähe.
- **Das zuletzt in einem Level gespielte Preset wird beim nächsten Besuch
  übersprungen**, solange es Alternativen gibt. Bei genau einem Preset im
  Level wird es wiederholt statt zu verstummen.
- **Ein leeres Level hält die Show nicht an**: das aktuelle Level bleibt
  stehen; ist auch das leer, wird aus allen Namen gewählt. Beides wird
  gemeldet. `nextPreset()` liefert nur `null`, wenn es überhaupt kein Preset
  gibt.
- **Startlevel ist fest `ruhig`**, nicht gewürfelt: die Installation fährt
  sanft hoch, und ein Neustart mitten in der Nacht beginnt nicht mit einem
  Drop. Es gibt **keine Tageszeit-Kopplung** (Birk, 2026-08-01) — die
  Dramaturgie soll auch tagsüber und bei einer kurzen Session funktionieren.

**25 Parameter, ein Kommando.** `/songStructure/enabled` (int 0/1, Default
**0** — die neue Schicht darf eine laufende Show nicht ohne Zutun übernehmen),
16 × `/songStructure/matrix/<von>/<nach>` (float 0..100) und 8 ×
`/songStructure/dwell/<level>/{min,max}` (float, **Minuten**, 0,5..60 — die
Obergrenze lässt die ursprünglichen Konzeptwerte wieder einstellen, ohne den
Code anzufassen). Die Gewichte sind 0..100 **ohne Summenbedingung**,
normalisiert wird beim Ziehen — dieselbe Konvention wie
`/net/impulse/speedQuantize/weight/*`.

`/songStructure/goto <1..4>` ist ein **Kommando**, kein Parameter: dieselbe
Konstruktion wie `/net/activateNode`. `SongStructureParams` implementiert
`PresetTarget` nicht und hat ein leeres `writeToStream()`, das Kommando kann
also per Konstruktion weder in ein Preset geraten noch im Web-UI zu einem
Regler werden. Argument 1..4 und nicht 0..3, weil 0 von „kein Argument" nicht
zu unterscheiden wäre. Der Wunsch wirkt **einmal** und verfällt danach.

**Alle `/songStructure/`-Adressen sind von jedem Preset ausgeschlossen**
(`PresetStore.EXCLUDED_PREFIXES`) — Transport, nicht Inhalt, genau wie die
zwei Scheduler-Parameter. Ein Preset, das die Matrix mitbrächte, könnte die
Dramaturgie beim nächsten Wechsel umschreiben, und das Preset, das sie
geändert hat, wäre danach nicht mehr wiederzufinden. Ein **Präfix** statt 25
Einzeladressen, damit ein später ergänzter Regler nicht still doch in die
Presets wandert; der Präfix endet auf `/`, ein Adressbaum, der nur so anfängt,
bleibt drin. Beim Laden werden sie zusätzlich still übergangen — eine von Hand
aus `remoteSettings.txt` kopierte Datei brächte sonst 25 Warnungen je
Ladevorgang.

**`data/songStructureState.txt`** wird bei jedem Levelwechsel atomar
geschrieben (Level, Index, Preset, Startzeit, gezogene Dauer). Das ist der
**einzige** Weg, auf dem das Web-UI den Live-Zustand erfährt: es gibt keinen
OSC-Rückkanal dorthin, imPulse sendet nur an 8002 und dort hört SuperCollider.
`server.py` läuft auf derselben Maschine und liest die Datei direkt — dasselbe
Muster wie die Preset-Liste. Alle paar Minuten eine Datei zu schreiben ist
vertretbar; in jedem Frame wäre es das nicht. Schlägt das Schreiben fehl, wird
das **einmal** gemeldet (eine Warnung alle paar Minuten über eine Nacht wäre
ein volles Log ohne neuen Inhalt) und die Show läuft weiter.

**Aufteilung:** `WeightedChoice`, `EnergyLevelStore` und
`SongStructureDirector` (samt `SongStructureConfig`) sind frei von Processing
und oscP5 und deshalb in `test/run.sh` geprüft. `SongStructureParams` kennt
oscP5 und darf dort **nicht** aufgenommen werden — dieselbe Trennung wie
`PresetManager` gegenüber `PresetStore`/`PresetScheduler`. Der Zufall wird als
`RandomSource` hereingereicht (das Interface aus `OriginSequencer.java`).

**Acht mitgelieferte Presets** (`data/presets/nacht_*.txt`), zwei je Level,
erzeugt aus `random1.txt` als Vorlage, damit die Adressmenge exakt der des
laufenden Sketches entspricht. Die zwei eines Levels unterscheiden sich in
Farbe und in der Verteilung innerhalb der Spannen — ein wiederbesuchtes Level
soll nicht identisch aussehen.

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

**Vier Lautstärken, nicht eine.** `masterVolume` ist der globale Show-Fader
(in `\masterReverb`, nach dem Panning, vor dem Limiter). Darunter sitzen drei
**Layer-Fader**, multiplikativ davor: `bellVolume` in `\glockenBell`,
`droneVolume` in `\impulseDrone` und `tailVolume` in den fünf Bell-Tails
(siehe unten). Damit lässt sich das Verhältnis von Node-Treffern zu reisenden
Impulsen zu Schweifen vor Ort einstellen, ohne `~maxAmp`, `~droneAmpScale`
bzw. `~tailAmpScale` im Code anzufassen. `droneVolume` wirkt sofort auf
laufende Drohnen (`onSet`-Callback plus `Lag`); `bellVolume` und `tailVolume`
erst auf den nächsten Ton und bewusst **ohne** `Lag` — Glocke und Tail sind
One-Shots, ein Lag würde den Anschlag weichzeichnen, der ihren Klang ausmacht.

Der Klang selbst ist der vor Ort getunte Stand: Phrygisch ab A2 (`~scaleSteps`), `~minAmp`/`~maxAmp` aus dem +6-dB-Live-Abgleich, Sägezahn-Layer für den Hang-Drum-Attack, `~maxPolyphony = 16` als Voice-Stealing-Deckel gegen „command FIFO full" (der Ausfallmodus dabei ist **Stille ohne Absturz**). Der Hall sitzt **hinter** dem Decoder (`\masterReverb`, je Hardware-Kanal getrennt) und nicht in der Glocke — vor dem Encoder würde er selbst räumlich codiert und wieder zu einer Punktquelle verschmiert; `~reverbMix` steht deshalb auf 0.35 statt auf dem früheren Wert 0.15.

**Topologiebasierte Notenzuordnung** (seit 2026-08-01): `~melodyLoad` liest
`data/nodeMelody_<modus>.txt` und füllt `~melodyScaleIndex` (nodeId →
gefalteter Skalenindex). Der `/net/hitNode`-Handler nimmt daraus den Zähler
vor dem Modulo — die Faltung auf drei Oktaven ist dieselbe wie vorher, nur
`nodeId` ist ersetzt. Berechnet wird die Datei ausschliesslich von imPulse
(`/net/melody/recompute`), hier wird sie nur gelesen; `/net/melody/reload
<modus>` schiebt sie nach.

Drei Dinge, die man beim Ändern kennen muss:

- **Fehlt die Datei, ist der Rückfall die ALTE `nodeId`-Formel, nicht die
  Tonika.** Eine fehlende Datei soll klingen wie früher (zufällig, aber
  lebendig), nicht wie 92 gleiche Töne. Die `WARNUNG` steht in `~melodyLoad`.
- **Beim Laden gewinnt der Kopf der Datei** über `melodyRootMidiNote` /
  `melodyNumOctaves` / `~scaleSteps`: die Oktavfaltung ist mit genau diesen
  Werten gerechnet worden, ein abweichendes `numOctaves` machte die Zuordnung
  nicht transponiert, sondern falsch. Eine Abweichung wird gemeldet, nicht
  verschluckt. Danach ist `melodyRootMidiNote` eine saubere
  Live-Transposition; `melodyNumOctaves` kann eine geladene Zuordnung nicht
  nachfalten und sagt das.
- **Es gibt kein `melodyStartNode`.** SuperCollider rechnet nichts neu, der
  Regler wäre hier wirkungslos und wanderte trotzdem in jedes Sound-Preset.
  Er liegt in imPulse (`/net/melody/startNode`); zur Diagnose steht er im Kopf
  der Datei und wird beim Laden ausgegeben.

`~melodyModes` ist eine **handgepflegte Spiegelung** von `MelodyModes.ALL` —
dieselbe Bauform wie `SC_PARAMS` im Web-UI, gleiche Reihenfolge, gleiche
Schlüssel.

**Klangbias nach Ursprungs-Baum** (`~treeBias`, Parameter
`/klangnetz/param/treeBiasAmount`, Default 0.6): vier Bäume, indiziert über
das **fünfte Argument von `/net/hitNode`**. Je Baum verschieben sich die
Notenwahl sowie `brightness` und `detune`.

Das **ersetzt** seit 2026-08-01 den früheren Zonen-Bias
(`~regionZone`/`~regionBias`/`regionBiasAmount`, vier Quadranten nach der
Geoposition des getroffenen Knotens) — nicht additiv daneben: zwei
Notenoffsets auf demselben Notenindex brächten den Intervallschaden
vollständig zurück. Form und Größe der drei Tabellen sind unverändert (vier
Einträge), getauscht wurde nur die **Indexquelle**. Der geografische Charakter
ist davon unberührt — die Lautsprecher-Ortung über `~toQuad` bleibt.

Ein **unbekannter Baum** (`-1`: Stripe ohne Zuordnung in `stripeTrees.txt`,
oder ein älterer Processing-Stand ohne das fünfte Argument) ergibt neutralen
Bias. Ihn auf Baum 0 zu legen hiesse, eine Lücke in der Zuordnungsdatei still
als „vorn" zu hören.

Der Notenoffset zählt in **Skalenstufen**, nicht Halbtönen: er geht auf den
Skalenindex, bevor `~scaleSteps` nachgeschlagen wird, sonst fielen Töne aus
der Skala heraus. Ein eigener `enabled`-Schalter entfällt — `amount = 0` ist
aus und klingt bitgleich wie ohne das Feature. `~tilt` und `~decayScale`, die
in älteren Notizen als Timbre-Regler auftauchen, gibt es in dieser Datei
nicht; die vorhandenen Äquivalente sind `brightness` und `detune`.

**Die Umbenennung `regionBiasAmount` → `treeBiasAmount` bricht vorhandene
SC-Presets an genau dieser einen Zeile** (sie melden dann „1 unbekannt") und
musste in `SC_PARAMS` im Web-UI nachgezogen werden. Bewusst in Kauf genommen:
ein Regler namens „region", der nach Bäumen biast, ist die schlechtere Falle.

**Prüfung der SC-Seite:** `test/melodyProbe.scd` ist **keine zweite
Sound-Datei**. Es liest `supercollider/klangnetz_bells.scd` und wertet deren
Kopfteil bis zum `s.waitForBoot` aus — also genau die Teile ohne Server —, und
prüft `~melodyModes`, `~melodyLoad`, `~treeBias` und die Parameter-Registry.
Dieselben Zeilen laufen, es kann also keine zweite Wahrheit entstehen.
Aufruf: `QT_QPA_PLATFORM=offscreen sclang -D test/melodyProbe.scd`. Klang
prüft es nicht — dafür gilt weiter manuelle Prüfung am Gerät.

**Bell-Tails: fünf additive Schweife je Node-Treffer** (`\tail1_shimmer`,
`\tail2_whoosh`, `\tail3_fmglide`, `\tail4_granular`, `\tail5_subglow`,
integriert 2026-08-01 aus einer eigenen Sound-Design-Session): der
Glockenanschlag bleibt unverändert, **zusätzlich** klingt bei jedem Treffer
ein digitaler Schweif — und zwar **alle fünf gleichzeitig**, nicht einer nach
Auswahl. Ein Treffer kostet damit sechs Synths statt einem.

Fünf Dinge, die man beim Ändern kennen muss:

- **One-Shot, kein Gate.** `/net/hitNode` ist ein Impuls ohne Ende-Ereignis;
  ein gehaltener Tail liefe entweder ewig oder an einem erfundenen Timeout.
  Die Freigabelogik, die `\impulseDrone` dafür hat, existiert nur, weil
  `/net/impulse` das *Ausbleiben* eines Impulses meldet. „Sustain" ist deshalb
  ein **Plateau-Pegel** im festen Envelope (`Env([0, 1, sustain, 0], [attack,
  decay, release])`), kein gehaltenes Segment. Defaults `decay = 0` und
  `sustain = 1` halten die Kurve formgleich mit dem abgenommenen Klang.
- **`~activeBells` hält seither je Treffer ein Array** `[glocke,
  tail1..tail5]`, nicht einen Synth. Beim Voice-Stealing wird der ganze
  Eintrag freigegeben — sonst überlebte ein Tail seine gestohlene Glocke und
  rotierte weiter, ohne dass ihn jemand zählt oder abräumt. `~maxPolyphony`
  steht deshalb auf **16** statt 24 (konservativ, die reale Last des
  Tail-Klangs ist noch nicht am Gerät gemessen).
- **Amp 0 heisst: der Synth entsteht gar nicht erst.** Die fünf `*Amp`-Regler
  und `tailVolume` sparen Stimmen, nicht nur Pegel — das ist der Grund, warum
  es keinen Auswahl-Parameter gibt.
- **Die fünf wurden einzeln abgenommen, nie gegeneinander.** `tailWhooshAmp`
  hat deshalb Default **3.0**: bandbegrenztes Rauschen trägt wenig Energie und
  liegt in der Mischung gemessen rund 10 dB unter den anderen vier (NRT
  2026-08-01, RMS 0.0006 gegen 0.0061). Die Zahl ist eine Messung, keine
  Schätzung.
- **Die Tails laufen trocken — der `FreeVerb` in Shimmer, FM-Glide und
  Sub-Glow ist beim Merge nach `master` entfernt** (Birk, 2026-08-01). Er kam
  aus der Hörprobe, stand quer zur Regel „Hall nach dem Panning" und kostete
  **gemessen zwei Drittel der Last dieser Schicht**: bei 10 Treffern/s 65 %
  Server-CPU mit, 23 % ohne die drei FreeVerbs — zum Vergleich 5 % ohne Tails
  (Linux-vServer ohne Echtzeitpriorität, die absoluten Zahlen gelten nicht für
  den Windows-Laptop, das Verhältnis schon). Dazu zwei Argumente aus dem
  Klang: bei einer *rotierenden* Quelle kreist der Eigenhall mit, statt stehen
  zu bleiben, und `\masterReverb` liefert ohnehin schon Hall je Hardware-Kanal.
  Es gibt **keinen Schalter** dafür — die drei Zeilen sind weg, ihre Werte
  stehen im Kommentar über den Tail-SynthDefs, falls der trockene Schweif am
  Netz zu dünn klingt. Der erste Versuch ist dann `~reverbMix`, nicht ein
  zweiter Hall.

**Tail-Orbit: Kreisbewegung um den Auslöseort** (`~tailOrbitOut`, Parameter
`tailOrbitRadius` 0.25, `tailOrbitSpeed` 0.5, `tailOrbitEnvExp` 1.0,
`tailOrbitDirLock` 0, `tailOrbitMinRadius` 0; Entwurf und Herleitung in
`docs/superpowers/specs/2026-08-01-tail-synth-orbit-design.md`): jeder Tail
wandert nach dem Trigger im Kreis um die Position seines Knotens, zufällig
rechts oder links herum, schnell am Anfang und langsamer zum Ausklingen hin.
Fünf **globale** Regler für alle fünf Tails — 5 × 5 wären 25 Adressen für ein
Feature, von dem noch niemand gehört hat, wie es sich im Raum anfühlt; eine
Pro-Tail-Variante bliebe möglich, ohne dass sich der Vertrag ändert.

Fünf Dinge, die man beim Ändern kennen muss:

- **Der Winkel ist das Integral der Hüllkurve** (`Sweep.kr`), nicht Zeit mal
  Zerfall. Ein Zerfall auf dem *Winkel* liesse die Bewegung gegen Ende
  zurücklaufen; das Integral einer Rate kann das strukturell nicht. `Sweep`
  nimmt die Rate pro **Sekunde** — die naheliegende Alternative `Phasor.kr`
  erwartet den Zuwachs pro Control-Sample, wer dort `ControlDur.ir` vergisst,
  bekommt eine um Faktor ~689 zu schnelle Rotation.
- **Es ist die Amplitudenhüllkurve, keine zweite Kurve.** Laut = schnell,
  leise = langsam, stumm = steht: die Rotation hört von selbst auf, wenn
  nichts mehr zu hören ist, ohne Sonderfall. Zwei Kurven könnten
  auseinanderlaufen — dieselbe Überlegung wie bei `splitLifetimeJitter`.
- **`tailOrbitSpeed` sind Umdrehungen/s bei voller Hüllkurve**, der
  Gesamtwinkel hängt am Flächeninhalt unter ihr. Eine volle Umdrehung über
  einen 4-Sekunden-Tail braucht rund **1.08**, mit dem Default 0.5 kommt
  weniger als eine halbe heraus. Das überrascht beim Einstellen, ist aber die
  Folge davon, dass die Bewegung mit dem Klang ausklingt.
- **Rauten-Clamping `|xn| + |yn| ≤ 1`, nicht Kreis.** `~toQuad` übergibt Pan4
  die 45-Grad-Rotation `(xw − yw, xw + yw)`; beide Argumente bleiben nur
  innerhalb der Raute in ±1. Beim Kreis stünde die Quelle viermal pro
  Umdrehung in einer Ecke still — genau da, wo Bewegung der Zweck ist. Das
  Clamping erfasst den Auslöseort mit: eine Ecke der Grundfläche liegt
  normiert bei 2.0, wird also geschlossen auf den Rand projiziert statt einen
  Sonderfall zu brauchen.
- **Der Radius wächst von 0 an**, der Tail startet exakt am Auslöseort. Als
  Rampe dient die **Attack-Flanke desselben Tails**, kein eigener Parameter —
  bei 20 ms Attack entfernt sie nur den Sprung im Einsatz, bei FM-Glide und
  Granular (0.7 s) ist sie eine hörbare Spiralbewegung. Der Radius ist
  **normiert** (1.0 = Boxabstand), nicht in Metern: ein Meter-Kreis wäre im
  Lautsprecherraum eine Ellipse und klänge nach einer schwankenden statt einer
  runden Bewegung.

Verifiziert per NRT/Jack-Dummy an der echten Datei (2026-08-01): ein Tail in
der Netzmitte überstreicht bei `dirLock = +1` einen **monoton** wachsenden
Winkel (30-ms-Fenster, kein Rücksprung), bei `tailOrbitRadius = 0` liegen alle
vier Kanäle exakt gleich und der Winkel steht — die Bewegung kommt also aus
dem Orbit und nicht aus dem Klang.

**Travel-Sound** (`/klangnetz/param/travelMix`, Default **0**, plus
`travelRq`, `travelGrainRatio`, `travelAmpScale`, `travelFreqBase`,
`travelSpeedRef`, `travelOctavesPerStep`, `travelSnap`, `travelFreqMin`,
`travelFreqMax`): eine **granulare** Wind-/Sandschicht **innerhalb** von
`\impulseDrone`, kein zweiter Synth je Impuls. `\impulseDrone` ist bereits eine Stimme pro
Impuls-ID mit Position, Hüllkurve, `~droneTimeout` und `~droneLimit`; ein
zweiter Synth bräuchte ein zweites Dictionary, einen zweiten Reaper und ein
zweites Limit und verdoppelte die Stimmenzahl — für ein Feature, dessen Ziel
es ist, Klangbrei zu vermeiden. Als Schicht erbt der Wind Position, Lag,
Hülle, Timeout und Deckel geschenkt.

Der geforderte Lebenszyklus („neue Stimme bei jedem Split") ergibt sich
dadurch ohne eine Zeile Code: ein Split-Kind ist eine neue
`TravellingActivation` mit neuer `id`, bekommt also einen neuen
Dictionary-Eintrag und einen neuen Synth, und die Stimme des Elternimpulses
läuft nach `~droneTimeout` aus.

Es gibt bewusst **keinen eigenen Throttle** für den Travel-Sound.
`/net/impulse/oscMaxCount` bestimmt, was überhaupt über den Draht geht; ein
zweiter, kleinerer Audio-Deckel wäre auf der Java-Seite wirkungslos (die
Klangseite kann eine Überzahl selbst ignorieren), ein größerer unerfüllbar
ohne mehr zu senden. Der Deckel, der wirklich gebraucht wird, sitzt dort, wo
die Rechenlast entsteht, und existiert schon: `~droneLimit`.

**Die Windschicht ist granular, nicht kontinuierlich** (umgebaut 2026-08-01
nach mehreren Hörproben): `Dust.ar` triggert kurze Rauschkörner
(`Decay2`-Hülle auf `WhiteNoise`), danach ein **Low-Cut** (`HPF`). Vorher war
es `BPF.ar(PinkNoise.ar, ...)` — ein durchgehender, eng gefilterter Rauschton.
Drei Entscheidungen dahinter:

- **Weiß statt rosa**: rosa fällt mit 3 dB/Oktave ab und klingt hinter einem
  Low-Cut dumpf; weiß ist der luftige, sandige Grundstoff.
- **Low-Cut statt Bandpass**: ein Bandpass macht einen engen, pfeifenden Ton —
  das Gegenteil von luftig.
- **`Dust` statt `Impulse`**: zufällig gestreute Körner klingen nach
  rieselndem Sand, periodische nach Motor.

`travelRq` heißt weiter so, steuert aber jetzt die **Körnerdauer** (Anteil von
20 ms) statt einer Filtergüte — den Bandpass gibt es nicht mehr. Name und
Registrierung bleiben, damit vorhandene Presets und `SC_PARAMS` gültig bleiben.

**Zwei Pegelkorrekturen, beide NRT-gemessen, keine geratenen Zahlen:**

1. **Dichteausgleich** `sqrt(100 / Körnerrate)`. Die Dichte hängt an
   `travelFreq`, also an der Speed-Klasse — ohne Ausgleich wäre die 8x-Klasse
   **viermal** so laut wie die 0.5x-Klasse (gemessen: RMS 0.134 gegen 0.501).
   Die Klasse soll sich in Farbe und Dichte zeigen, nicht in der Lautstärke,
   sonst regelt der Limiter genau die schnellen Impulse weg. Nach dem
   Ausgleich beträgt die Streuung Faktor **1.08**.
2. **Angleich an die Tonschicht**, Faktor 3, damit der Crossfade kein Sprung
   ist. Ein erster Versuch mit Faktor 6 **clippte** (Spitzenwert 1.0 in allen
   fünf Klassen) — deshalb steht hier eine gemessene und keine geschätzte
   Zahl.

**Die Speed→Frequenz-Abbildung ist oktavbasiert, nicht linear** — das ist es,
was die Speed-Klassen hörbar auseinanderhält. Sie steuert jetzt **zwei**
Merkmale: die Grenzfrequenz des Low-Cut **und** (über `travelGrainRatio`) die
Körnerdichte, also 25/50/100/200/400 Körner je Sekunde für 0.5x…8x. Zwei
gleichgerichtete Merkmale sind leichter zuzuordnen als eines:

```
octaves = log2(speed / travelSpeedRef)
freq    = travelFreqBase * 2^(octaves * travelOctavesPerStep)
```

Mit den Defaults (Basis 400 Hz bei Speed 16) ergibt das
**200 / 400 / 800 / 1600 / 3200 Hz** für 0.5x / 1x / 2x / 4x / 8x. Linear
gerechnet lägen 1x und 2x dicht beieinander — ein sanfter Gradient, an dem
sich keine Klasse zuordnen lässt. Genau das war die erste, verworfene Fassung.

`travelSnap` (Default **1**) rundet den Oktavabstand vor der Umrechnung auf
eine ganze Zahl, damit jeder Impuls einer Klasse auf **exakt derselben**
Tonhöhe zischt; ohne die Rasterung hinge die Zuordenbarkeit daran, wie hoch
`speedQuantize/jitter` gerade steht. Die Grenze davon: die Rundung greift auf
halbe Oktaven, ein Jitter über etwa **0,29** lässt einzelne Impulse in die
Nachbarklasse rutschen. Das ist kein Fehler — der Impuls reist dann wirklich
so schnell —, aber wer maximale Zuordenbarkeit will, bleibt darunter.

Hörbar ist die Klasse nur über die **Wind**schicht: die Tonschicht der Drohne
holt ihre Tonhöhe weiterhin aus der Impuls-ID (`~droneFreq`), nicht aus der
Geschwindigkeit. Bei `travelMix = 0` gibt es also keinen Speed-Klang.

Der Klangbias der **Herkunfts**-Region wird einmal beim Anlegen des Synths aus
der **ersten** gemeldeten Position gerechnet. Die kommt aus dem Takt direkt
nach der Entstehung des Impulses, liegt also an seinem Spawn- bzw. Splitpunkt
— das ist die Herkunftsregion, ohne ein weiteres OSC-Feld und ohne
Ursprungs-Buchführung auf der Processing-Seite. Der Whoosh behält seine
Klangidentität über seine ganze Lebensdauer, auch wenn er in eine andere
Region fliegt.

**Sound-Presets** (`~presetLoad`, `~presetSave`, Adressen `/sc/preset/load`
und `/sc/preset/save`, jeweils ein String-Argument): ein SC-Preset ist ein
Wertesatz für **genau die per `~registerParam` registrierten Parameter** —
aktuell 52, siehe Liste unten. Nichts sonst; Tonleiter, Grundton und die
Amplituden-Grenzen sind vor Ort getunte Konstanten und bleiben es.

- **Dateien:** `supercollider/presets/<name>.txt`, **gleiches Format** wie
  `remoteSettings.txt` und die visuellen Presets (sechs Tab-Spalten, nach
  Adresse sortiert, `\n` als Zeilenende). Die Adress-Spalte trägt die
  Live-Adresse `/klangnetz/param/<name>`, die Beschreibungsspalte bleibt leer.
- **Ein Name, zwei Dateien.** `hang_drum_slow` meint
  `data/presets/hang_drum_slow.txt` für das Licht **und**
  `supercollider/presets/hang_drum_slow.txt` für den Klang. imPulse ist
  Master: `PresetManager.forwardToSound()` schickt bei jedem Laden **und**
  jedem Speichern denselben Namen an 8002. Es gibt genau einen Scheduler, und
  der läuft in imPulse — SC hat keinen eigenen Zeitplan.
- **Ein Name ohne SC-Datei ist kein Fehler.** Dann wechselt das Licht und der
  Klang bleibt stehen; `~presetLoad` meldet das in einer Zeile. Das ist der
  häufige Fall, nicht der Ausnahmefall.
- **Geklemmt wird auf die Range aus der Registry, nicht auf die aus der
  Datei** — dieselbe Regel wie `PresetStore.applyPreset()` auf der Java-Seite,
  damit ein älteres Preset nach einer Bereichsänderung gültig bleibt.
- **`~applyParam` ist der einzige Ort, an dem ein Sound-Parameter seinen Wert
  bekommt.** OSC-Empfang und Preset-Laden gehen beide hindurch. Zwei Kopien
  dieser vier Zeilen wären zwei Wege, die auseinanderlaufen: ein neuer
  Parameter mit `onSet`-Callback würde per OSC wirken, per Preset aber nicht —
  ohne Fehlermeldung.
- **`~presetValidName` spiegelt `PresetStore.isValidName()`** (a–z, 0–9, `_`,
  `-`, höchstens 64 Zeichen). Autorität bleibt Java; hier geht es um
  Pfad-Traversal, ohne die Prüfung würde `/sc/preset/load ../../etc/passwd`
  eine beliebige Datei öffnen.
- **Geschrieben wird direkt, nicht atomar über Temp-Datei plus Umbenennen**
  wie auf der Java-Seite: sclang hat kein portables `rename`, und hier
  speichert ein Mensch von Hand statt eines Schedulers im Sekundentakt.
- **Die zwei Dateien, die schon in `supercollider/presets/` liegen
  (`standby.txt`, `hang_drum_slow.txt`), stammen aus dem alten Parametersatz**
  (`/sc/amp/*`, `/sc/bell/*`, `/sc/scale/*`) und enthalten **keine** heute
  gültige Adresse. Sie zu laden ändert nichts und meldet „0 übernommen, 7
  unbekannt" plus einen Hinweis auf genau diese Ursache. Ein
  `/sc/preset/save standby` überschreibt sie mit dem aktuellen Satz.

Die 55 Parameter, die ein Preset umfasst: `masterVolume`, **`bellVolume`**,
**`droneVolume`**, **`tailVolume`**, `reverbMix`, `reverbRoom`, `reverbDamp`,
`brightness`, `detune`, `droneLpfMult`, `panSharpness`, **`treeBiasAmount`**
(bis 2026-08-01 `regionBiasAmount`), **`melodyMode`**,
**`melodyRootMidiNote`**, **`melodyNumOctaves`**, `travelMix`, `travelRq`,
`travelGrainRatio`, `travelAmpScale`, `travelFreqBase`, `travelSpeedRef`,
`travelOctavesPerStep`, `travelSnap`, `travelFreqMin`, `travelFreqMax`, dazu
die **fünf Orbit-Regler** (`tailOrbitRadius`, `tailOrbitSpeed`,
`tailOrbitEnvExp`, `tailOrbitDirLock`, `tailOrbitMinRadius`) und **je Tail
fünf** Hüllkurven-/Pegelregler
(`tail<Shimmer|Whoosh|Fmglide|Granular|Subglow><Attack|Decay|Sustain|Release|Amp>`).
Wer einen `~registerParam`
ergänzt, bekommt ihn ohne weiteres Zutun in die Presets — die Liste wird aus
`~params` gelesen, nicht gepflegt. (Die handgepflegte Kopie im Web-UI,
`SC_PARAMS` in `webui/server.py`, muss dann allerdings nachgezogen werden;
zwei Tests halten das nach.)

**Vierkanal-Mitschnitt auf Platte** (seit 2026-08-01, für Videodrehs):
`/klangnetz/record/{start,stop,toggle,query}` auf `~oscListenPort` (**8002**,
derselbe Port wie die Sound-Parameter), alle vier **ohne Argument**.
Geschrieben wird `recordings/klangnetz_YYYY-MM-DD_HH-MM-SS.wav` im **Repo-Root**
(nicht neben der `.scd` wie `~presetDir` — Sound-Presets gehören zum Patch,
Mitschnitte nicht), WAV/int24, Ordner wird beim ersten Start angelegt, liegt in
`.gitignore`. Details und der Ablauf zum Verifizieren von Hand:
`supercollider/klangnetz_bells.README.md`.

Sechs Dinge:

- **Aufgenommen wird Bus 0, nicht `~quadBus`** — also das fertige Signal nach
  Panning, Hall, `masterVolume`, Limiter und Kanal-Permutation. Auf `~quadBus`
  stehen die Stimmen *vor* `\masterReverb`; ein Mitschnitt davon klänge anders
  als das, was im Raum zu hören ist.
- **Der Recorder muss hinter `\masterReverb` im Node-Baum hängen.**
  `s.record(path, 0, chans, s.defaultGroup)` hängt ihn an das Ende dieser
  Gruppe, in der `~voices` und `~masterReverbSyn` beide liegen. Davor gelesen
  bliebe die Datei still. Die Argumente stehen bewusst **positionell** statt
  als Keywords: einen falsch geschriebenen Keyword-Namen verschluckt sclang mit
  einer blossen Warnung und nimmt den Default — die Aufnahme hätte dann still
  zwei Kanäle statt vier, und das fiele erst beim Abhören auf.
- **Weder Samplerate noch Kanalzahl stehen als Literal im Code**: die Rate
  kommt vom laufenden Server, die Kanalzahl aus
  `s.options.numOutputBusChannels`.
- **`~recordActive` ist die einzige Quelle der Wahrheit.** `s.isRecording` wäre
  eine zweite und könnte davon abweichen (etwa nach `s.reboot`) — zwei
  Zustände, in denen Start und Stop sich gegenseitig blockieren, wären
  schlimmer als einer, der einmal falsch steht. Doppelter Start und Stop ohne
  laufende Aufnahme werden ignoriert und geloggt, nie ein zweiter Recorder.
- **Der Abräumblock in `waitForBoot` ruft `~recordStop` vor `~voices.free`.**
  Ein Re-Evaluieren der Datei schliesst eine laufende Aufnahme also sauber ab,
  mit dem letzten Klang darin. Aus demselben Grund legt der Zustandsblock
  `~recordActive` nur an, wenn es ihn noch nicht gibt (`?? { }`) — ein
  bedingungsloses `= false` würde die laufende Aufnahme dort vergessen und die
  WAV-Datei bliebe ohne fertigen Header liegen.
- **Status geht an einen dritten Port.** `/klangnetz/record/status <0|1:int>
  <pfad:string>` an `127.0.0.1:~recordStatusPort` (**8003**) — nicht 8001
  (imPulse), nicht 8002 (sclang selbst). Gesendet bei **jedem** Start, Stop,
  `query` und auch bei einem ignorierten Doppel-Start, damit eine Gegenstelle,
  die ihren Zustand verloren hat, die Wahrheit bekommt. Fire-and-forget: läuft
  das Web-UI nicht, geht das Datagramm ins Leere. Dort hängt der Aufnahmeknopf
  (siehe „Web-UI").

Zwei Dinge, die man kennen muss, bevor man dort etwas anfasst:

- **Es gibt kein Ambisonics mehr.** `~azimuthSign`, `~azimuthOffset` und `~decoderOrientation` sind mit dem Umbau auf `Pan4` am 2026-07-31 (Commit `cbb06d7`) **komplett entfallen** — ältere Notizen, die eine ausstehende Azimut-Messung dieser drei Werte fordern, sind überholt. Ambisonics erster Ordnung ist ein Diffusionsverfahren: eine Punktquelle streut bei voller Richtwirkung immer auf alle vier Kanäle (gemessen: dominanter Kanal nur ~43 % der Gesamtenergie, siehe `docs/ambisonics-sharper-panning-optionen.md`). Das lohnt sich nur bei flexiblem Lautsprecher-Layout; unseres ist fest. `Pan4` erreicht in der Boxecke 100 % auf dieser Box und in der Netzmitte exakt 25/25/25/25 %.
- **Die zwei Korrekturen dahinter sind gemessen, nicht hergeleitet, und gelten nur für dieses Interface.** Erstens die 45-Grad-Rotation in `~toQuad` (`xr = xn - yn`, `yr = xn + yn`, nach achsenweiser Normierung durch `~maxX`/`~maxY`): unsere Boxen stehen auf den Seitenmitten, `Pan4` erwartet sie in den Ecken. Zweitens die Kanal-Permutation in `\masterReverb` (`[sig[1], sig[2], sig[3], sig[0]]`): die Verkabelung des ZOOM AMS-24 ist eine echte Vertauschung, keine Rotation. Beide wurden nach dem Umbau per NRT neu verifiziert und **nicht** aus den alten Ambisonics-Werten übernommen — `Pan4` hat eine andere Kanalkonvention als `DecodeB2`. Bei anderem Interface oder anderer Verkabelung neu messen (`\channelTest`, `~testChannels.()`, und `/klangnetz/test/noise` für Ortungstests ohne REPL-Zugriff vor Ort). Derselbe Fall wie bei der ArtNet-Bytefolge: massgeblich ist die Messung.

## Konventionen und Fallstricke

- **Klassenname ≠ Dateiname**: Alle `.java`-Dateien liegen flach im Sketch-Ordner, Processing kompiliert sie ins Default-Package. Die meisten Klassen sind package-private, mehrere pro Datei (z. B. `LedNetworkNode` + `LedInNetInfo` in `LedStripeNetworks.java`). Beim Suchen nach einer Klasse also nicht auf den Dateinamen verlassen.
- **Nicht initialisierte `PApplet`-Felder**: `Mixer.papplet`, `TemplateEffect.papplet` usw. werden nie zugewiesen und sind `null`. Über sie werden ausschliesslich *statische* `PApplet`-Helfer aufgerufen (`ceil`, `constrain`, `map`, `str`) — in Java erlaubt. Ein Aufruf einer Instanzmethode über diese Felder wirft sofort eine NPE.
- **Hardware-Konstanten** (`controllerOctets`, `numLedsPerStripe`, OSC-Ports, Master-Pegel-Obergrenze) stehen als Felder oben in `imPulse.pde` und sind installationsspezifisch — nicht ändern, ohne dass es um eine konkrete Installation geht.
- **Fenstergrösse in `size()`**: Processing erlaubt dort nur Literale, keine Variablen. Die Höhe muss von Hand zur Stripe-Zahl passen — Vorschau braucht `numStripes*10` Pixel, darunter das mehrzeilige Kalibrier-HUD (siehe Kommentar direkt bei `size(...)` in `imPulse.pde`). Der Kommentar dort rechnet nur mit den vier Zeilen des Kalibrier-HUDs; das Positions-HUD hat fünf und sitzt unter einer 525 × 300 px grossen Draufsicht-Fläche. Wer die Fensterhöhe neu herleitet, muss beides prüfen.
- **Farbwerte 0..1** durchgängig; Werte > 1 sind erlaubt und werden erst am Output geclampt (`LedColor.clamp()` wird im Mixer bewusst nicht aufgerufen).
- **SuperCollider-Presets** liegen in `supercollider/presets/<name>.txt`, im
  selben Tab-Format wie die visuellen Presets. Der Empfänger
  (`/sc/preset/load`, `/sc/preset/save`) ist **seit 2026-07-31 gebaut** und
  steht in `klangnetz_bells.scd` — Details oben unter „Klangseite". Ein
  Preset umfasst genau die per `~registerParam` registrierten Parameter.
  **Nicht** zurückgeholt wurde der alte Empfänger aus Commit `e50cd38`: der
  bediente `/sc/scale/steps|rootMidi|octaves`, `/sc/amp/min|max`,
  `/sc/bell/decayScale` und `/sc/bell/tilt` — Adressen, die es im heutigen
  Sound-Design **nicht mehr gibt** (die vorhandenen Timbre-Regler heissen
  `brightness` und `detune`). Ein Merge von `feature/preset-system-v2` ist
  aus demselben Grund der falsche Weg: die dortige `.scd` ist der
  Ambisonics-Stand vor dem Pan4-Umbau. Der Typ `ints` für eine Tonleiter
  wird derzeit von keiner Seite geschrieben oder gelesen.
  Die `#[...]`-Teilton-Literale in der SynthDef bleiben stehen — kein Rebuild
  beim Preset-Wechsel. Alles liegt weiter in **einem** `(...)`-Block: mehrere
  Top-Level-Blöcke hängen `sclang -D` auf. Für den SC-Teil gibt es **kein**
  Testgerüst im Repo, dort gilt manuelle Prüfung am Gerät.
- **`LedPositionCalibration` nennt bewusst kein `implements runnableLedEffect`.** Das Interface steht in `mixer.java`, das über `RemoteControlledFloatParameter` an `oscP5` hängt — eine Klasse, die es nennt, lässt sich von `test/run.sh` nicht mehr übersetzen. `imPulse.pde` ruft `drawMe()` ohnehin direkt auf und geht nie über den Mixer (genau wie bei `NodeCalibration`); das Interface wäre also nur ein Etikett, das die Prüfbarkeit kostet. Dieselbe Überlegung ist der Grund, warum `ImpulseOscThrottle` eine eigene Klasse ist statt einer Methode in `LedNetworkTransportEffect`.
- **Keine von der Kreuzungszahl abgeleitete Zahl als Literal** in Code oder Test: `data/nodeCrossings.txt` wächst während der Kalibrierung, jede fest eingetragene Anzahl von Knoten, Einträgen oder Ankern ist also am nächsten Tag falsch. Testaufbauten bauen sich ihre Kreuzungsliste selbst und rechnen ihre Erwartungen daraus.
- **`RemoteControlledIntParameter` und normalisierte Float-Fader**: der Float-Zweig von `digestMessage` (`AbstractParameter.java`) hat lange `.intValue()` **vor** `PApplet.map(...)` gerufen — ein Fader, der 0..1 als Float schickt, landete damit für jeden Wert unter 1.0 auf `minValue`; bei `/net/impulse/oscMaxCount` war das Ergebnis **Stille, die wie funktionierende Software aussieht**. Seit dem Aufräumen des Energiezerfalls nimmt der Zweig `.floatValue()` und spreizt korrekt auf min..max (Kommentar an Ort und Stelle). Der Int-Zweig (`'i'`) setzt weiterhin absolut, ohne Abbildung — eine Fernsteuerung darf für Int-Parameter also entweder echte Ganzzahlen senden (`webui/` tut das) oder normalisierte Floats, aber die zwei Wege bedeuten Verschiedenes.
- Bekannte offene Punkte stehen als To-Do-Block am Kopf von `imPulse.pde`.
