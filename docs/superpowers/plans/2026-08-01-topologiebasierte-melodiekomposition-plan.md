# Topologiebasierte Melodiekomposition — Implementierungsplan

> **Für agentische Ausführung:** Dieser Plan wird in derselben Sitzung
> ausgeführt (Birk hat "kurze writing-plans-Runde, dann Umsetzung" gefordert),
> deshalb kompakt statt in 5-Schritt-TDD-Zyklen je Teilaufgabe. Die
> Test-zuerst-Reihenfolge gilt trotzdem je Task.

**Ziel:** Die Note eines Knotens entsteht aus der Graph-Topologie des
LED-Netzes (BFS vom Startknoten, Intervalle in Skalenstufen) statt aus
`nodeId % notesPerOctaveSet`; die Zuordnung wird persistiert und von
SuperCollider geladen. Zusätzlich ersetzt ein Bias nach Ursprungs-Baum den
bisherigen Zonen-Bias.

**Architektur:** Vier reine Java-Klassen ohne Processing/oscP5 (prüfbar über
`test/run.sh`) für Modi, Graph, Zuweisung und Datei; eine dünne
`MelodyManager`-Klasse mit oscP5-Anbindung (nicht in der Suite, Muster
`PresetManager`); `TravellingActivation` bekommt ein `final int originTree`;
die `.scd` lädt `data/nodeMelody_<modus>.txt` und indiziert die
Bias-Tabellen über den Baumindex aus `/net/hitNode`.

**Tech-Stack:** Java 8 (Processing-Sketch, flaches Default-Package),
SuperCollider (`sclang`), Python 3 ohne Fremdabhängigkeiten (Web-UI).

## Globale Randbedingungen

- **Keine von der Kreuzungszahl abgeleitete Zahl als Literal** in Code oder
  Test (CLAUDE.md). Die Obergrenze von `/net/melody/startNode` kommt zur
  Laufzeit aus `listOfNodes.size()`, Tests bauen sich ihre Kreuzungsliste
  selbst.
- **Genau EINE SC-Sound-Datei:** `supercollider/klangnetz_bells.scd`.
- Kein Push zum Laptop, kein Merge nach `master`/`grabicz26`.
- Reine Logikklassen dürfen `processing.core`, `oscP5`, `netP5` **nicht**
  nennen — sonst lassen sie sich von `test/run.sh` nicht übersetzen.
- Alle drei Neuberechnungs-Parameter (`startNode`, `rootMidiNote`,
  `numOctaves`) plus `mode` wirken als **ein atomarer Vorgang**: sie sind
  reine Werte, ausgelöst wird die Neuberechnung erst durch
  `/net/melody/recompute`.

---

## Dateiplan

| Datei | Rolle |
|---|---|
| `MelodyModes.java` (neu) | `MelodyMode` (Name, Dateischlüssel, Skala, Klassengewichte) + Tabelle der acht Modi |
| `MelodyGraph.java` (neu) | Nachbarschaft aus `nodeCrossings.txt` + `numLedsPerStripe`, Grade, Hub-Schwelle, Default-Startknoten |
| `MelodyAssigner.java` (neu) | Prioritäts-BFS, Landmarken-Rotation, Oktavfaltung; liefert `MelodyAssignment` |
| `NodeMelodyStore.java` (neu) | Schreiben/Lesen von `data/nodeMelody_<modus>.txt` |
| `MelodyManager.java` (neu) | OSC-Parameter, `/net/melody/recompute`, Weiterreichen an SC |
| `StripeTreeStore.java` | `treeOf(int stripeIndex)` ergänzen (Indexvariante zu `treeNameOf`) |
| `LedNetworkTransportEffect.java` | `originTree` am Impuls, `spawnTree()`, 5. Argument an `/net/hitNode` |
| `PresetStore.java` | vier Melodie-Adressen in `EXCLUDED` |
| `imPulse.pde` | `MelodyManager` anlegen, Bericht beim Start |
| `test/run.sh` | vier Quellen + vier Suiten aufnehmen |
| `test/Melody*Test.java` (neu) | vier Suiten |
| `supercollider/klangnetz_bells.scd` | Melodie-Datei laden, Baum-Bias, neue Parameter |
| `webui/server.py`, `app.js`, `index.html`, `test_webui.py` | Melodie-Sektion, `SC_PARAMS` nachziehen |

---

## Task 1 — `MelodyModes.java` + Test

**Files:** Create `MelodyModes.java`, `test/MelodyModesTest.java`.

**Produces:**
- `class MelodyMode { final String name; final String key; final int[] scale;
   final float[] classWeights; }`
- `MelodyModes.ALL` (Länge 8, Reihenfolge A..H), `MelodyModes.at(int)`,
  `MelodyModes.byKey(String)`, `MelodyModes.indexOfKey(String)`,
  `MelodyModes.CLASS_INTERVALS = {1, 2, 4}` (Skalenstufen),
  `MelodyModes.drawInterval(MelodyMode, double r0, double r1)` → vorzeichen-
  behaftetes Intervall in Skalenstufen.

Skalen und Gewichte wörtlich aus Abschnitt 4 des Konzepts:

| # | key | Name | scale | Gewichte (1 Stufe / 2 Stufen / Quint) |
|---|---|---|---|---|
| 0 | `dorisch` | Dorisch | 0 2 3 5 7 9 10 | 70/20/10 |
| 1 | `pentatonik` | Moll-Pentatonik | 0 3 5 7 10 | 50/35/15 |
| 2 | `hijaz` | Maqam Hijaz | 0 1 4 5 7 8 10 | 60/25/15 |
| 3 | `harmonischmoll` | Harmonisch Moll | 0 2 3 5 7 8 11 | 70/20/10 |
| 4 | `phrygisch` | Phrygisch | 0 1 3 5 7 8 10 | 75/15/10 |
| 5 | `ajam` | Maqam ʿAjam | 0 2 4 5 7 9 11 | 70/15/15 |
| 6 | `nikriz` | Maqam Nikriz | 0 2 3 6 7 9 10 | 65/20/15 |
| 7 | `saba` | Maqam Saba | 0 1 3 4 7 8 10 | 80/10/10 |

`drawInterval` zieht mit `r0` die Klasse (gewichtet, Normalisierung wie
`SpeedQuantizer.pick`) und mit `r1` das **Vorzeichen** (Konzept, Anmerkung zu
Schritt 3c: ein Sekundschritt ist `±1`, nicht `+1`).

**Tests:** jede Skala aufsteigend und < 12; Schlüssel eindeutig und
dateinamenstauglich (`PresetStore.isValidName`-Zeichenvorrat); Gewichte
summieren > 0; `drawInterval` liefert nur Werte aus `{±1, ±2, ±4}`; die
Verteilung über 100 000 Ziehungen trifft die Gewichte auf 1 % genau; ein
Gewicht von 0 wird nie gezogen; `byKey` unbekannt → `null`.

**Commit:** `Melodie: acht Modi als reine Datenklasse`

---

## Task 2 — `MelodyGraph.java` + Test

**Files:** Create `MelodyGraph.java`, `test/MelodyGraphTest.java`.

**Consumes:** nichts.
**Produces:**
- `static MelodyGraph fromCrossings(List<TreeSet<Integer>> crossings, int numLedsPerStripe)`
- `int nodeCount()`, `int[] neighbors(int node)` (aufsteigend nach nodeId),
  `int degree(int node)`, `int minLedIndex(int node)`, `int edgeCount()`,
  `int hubThreshold(float percentile)`, `int defaultStartNode()`,
  `String report()`

Ableitung nach Abschnitt 5, Schritt 1: je Kreuzung alle
`(stripeIndex, indexInStripe)` bilden, nach Stripe gruppieren, nach
`indexInStripe` sortieren, aufeinanderfolgende **verschiedene** Nodes
verbinden. Mehrfachkanten werden zu einer.

`defaultStartNode()`: höchster Grad, bei Gleichstand kleinste `nodeId`.
`hubThreshold(0.75f)`: 75. Perzentil der Gradverteilung (Nearest-Rank auf der
sortierten Gradliste), mindestens 1.

**Tests:** Dreieck-Zyklus aus drei selbstgebauten Kreuzungen wird erkannt;
zwei Nodes auf demselben Stripe mit einem dritten dazwischen sind **nicht**
benachbart; ein Node auf zwei Stripes bekommt Nachbarn aus beiden;
`edgeCount` zählt jede Kante einmal; leere Kreuzungsliste →
`nodeCount() == 0`, `defaultStartNode() == -1`; Gegenprobe an der echten
`data/nodeCrossings.txt` (nur Invarianten: Symmetrie der Adjazenz, kein Node
ist sein eigener Nachbar, `degree` == `neighbors().length` — **keine**
absoluten Zahlen).

**Commit:** `Melodie: Graph-Ableitung aus der Kreuzungsliste`

---

## Task 3 — `MelodyAssigner.java` + Test

**Files:** Create `MelodyAssigner.java`, `test/MelodyAssignerTest.java`.

**Consumes:** `MelodyGraph`, `MelodyMode`, `RandomSource` (aus
`OriginSequencer.java`).
**Produces:**
- `MelodyAssigner.STABLE_INTERVALS = {4, -3, 2}`
- `static MelodyAssignment assign(MelodyGraph g, MelodyMode mode, int startNode,
   int rootMidiNote, int numOctaves, int hubThreshold, RandomSource rnd)`
- `class MelodyAssignment { int[] scaleIndex; int[] midiNote; int[] depth;
   boolean[] reached; int backEdges; int wrapEdges; int unreachedCount;
   int notesPerOctaveSet; String report(); }`
  (`scaleIndex` ist der **gefaltete** Wert 0..notesPerOctaveSet-1.)

Ablauf exakt nach Schritt 3:
1. `raw[startNode] = scale.length * (numOctaves / 2)`, `depth = 0`.
2. BFS. Nachbarn eines Knotens werden nach Schritt 3a sortiert: erst
   Landmarken (`degree >= hubThreshold`), innerhalb der Gruppe nach
   `minLedIndex` aufsteigend. Der 0-basierte Rang in **dieser** Liste ist
   `rang(n)`.
3. Landmarke → `STABLE_INTERVALS[(depth(n) + rang(n)) % 3]`, sonst
   `MelodyModes.drawInterval(...)`.
4. `raw[n] = raw[current] + interval`.
5. Faltung `((raw % n) + n) % n`, `midiNote = rootMidiNote +
   scale[folded % L] + 12 * (folded / L)`.

Gezählt wird (Schritt 3d, "messbar statt überraschend"):
- `backEdges` — Kanten zwischen zwei besuchten Knoten, die keine Baumkante
  sind (je ungerichteter Kante einmal).
- `wrapEdges` — Baumkanten, bei denen `folded[kind] - folded[eltern]` vom
  gewählten Intervall abweicht, die Faltung also umgebrochen hat.
- `unreachedCount` — Knoten außerhalb der Zusammenhangskomponente des
  Startknotens. **Eigene Entscheidung** (das Konzept sagt dazu nichts): sie
  bekommen den Wert des Startknotens (Tonika) und werden gemeldet. Ein
  unbestimmter Wert wäre ein stiller Ausfall, ein Abbruch würde die Show an
  einer Kalibrierlücke aufhängen.

**Tests:**
- Startknoten liegt in der mittleren Oktave (`scale.length * (numOctaves/2)`).
- Determinismus: zweimal derselbe Lauf mit derselben Zufallsfolge → gleiche
  Arrays.
- Landmarken-Rotation: auf einem Sternchen-Graph mit vier Landmarken-Nachbarn
  bekommen die Geschwister **verschiedene** `scaleIndex` (das ist genau der
  Ton-Stapel, den Schritt 3c behebt) — geprüft ohne Zufall, weil alle vier
  Kanten Landmarken-Kanten sind.
- Rotation folgt `(tiefe+rang) mod 3` — an einem konstruierten Graph
  nachgerechnet.
- Alle `scaleIndex` liegen in `0..notesPerOctaveSet-1`, alle `midiNote` in
  `rootMidiNote .. rootMidiNote + scale[L-1] + 12*(numOctaves-1)`.
- Negative Rohwerte falten korrekt (Modus mit nur `-3`-Kanten erzwingen,
  indem `hubThreshold = 1` alle Knoten zu Landmarken macht und die Tiefe
  passend gewählt wird) — kein negativer `scaleIndex`.
- Dreieck-Zyklus liefert genau **eine** Rückwärtskante.
- Ein unerreichbarer Knoten wird gezählt und bekommt den Startwert.
- `numOctaves = 1` faltet auf `scale.length` und bricht nicht.
- Gegenprobe mit der echten `nodeCrossings.txt` über alle acht Modi: keine
  Ausnahme, jeder Knoten erreicht, `scaleIndex` im Bereich.

**Commit:** `Melodie: BFS-Zuweisung mit Landmarken-Rotation und Oktavfaltung`

---

## Task 4 — `NodeMelodyStore.java` + Test

**Files:** Create `NodeMelodyStore.java`, `test/NodeMelodyStoreTest.java`.

**Produces:**
- `static String fileNameFor(String modeKey)` → `nodeMelody_<key>.txt`
- `static boolean write(String path, MelodyMode mode, MelodyAssignment a,
   int startNode, int hubThreshold, int rootMidiNote, int numOctaves,
   String stamp)` — atomar über `.tmp` + Rename.
- `boolean load(String path)`, `int scaleIndexOf(int nodeId)` (−1 = unbekannt),
  `int size()`, `int rootMidiNote()`, `int numOctaves()`, `String modeKey()`,
  `String lastMessage()`, `int rejectedCount()`, `int overriddenCount()`

Format (Abschnitt 8):

```
# Modus: phrygisch (Phrygisch)
# Startknoten: 90
# hubThreshold: 4
# rootMidiNote: 45
# numOctaves: 3
# scaleSteps: 0 1 3 5 7 8 10
# Rueckwaertskanten: 37   (ohne Intervall-Garantie, siehe Konzept Abschnitt 6)
# Umbruchkanten: 4        (Oktavfaltung, siehe Konzept Schritt 3d)
# erzeugt: 2026-08-01T12:00:00
#
# nodeId	scaleIndex	midiNote	tiefe
# Massgeblich ist scaleIndex. midiNote ist eine abgeleitete Kontrollspalte.
0	9	60	2
```

Regeln wie `StripeTreeStore`: `#` ist Kommentar, Leerzeilen übersprungen,
kaputte Zeilen gemeldet und übersprungen, **letzte Zeile gewinnt** bei
doppeltem `nodeId` (die Handkorrektur wird angehängt).

**Tests:** Rundlauf schreiben→lesen ergibt dieselben `scaleIndex`; Kommentare
und Leerzeilen werden übersprungen; kaputte Zeile wird gezählt statt zu
werfen; doppelter `nodeId` — letzte gewinnt und wird gemeldet; fehlende
Datei → `false` plus Meldung, kein Wurf; Kopfzeilen werden gelesen
(`modeKey`, `rootMidiNote`, `numOctaves`); wiederholtes Schreiben verdoppelt
nichts; `Locale`-Unabhängigkeit (nur Ganzzahlen, aber `String.valueOf` statt
`format`).

**Commit:** `Melodie: Persistenz nodeMelody_<modus>.txt`

---

## Task 5 — Ursprungs-Baum am Impuls

**Files:** Modify `StripeTreeStore.java`, `LedNetworkTransportEffect.java`;
Modify `test/StripeTreeStoreTest.java`.

1. `StripeTreeStore.treeOf(int stripeIndex)` → `0..3`, sonst `-1` (Indexvariante
   zu `treeNameOf`; der Bias rechnet mit Zahlen, nicht mit Namen).
2. `TravellingActivation` bekommt `final int originTree`. **Alle** drei
   Konstruktoren werden umgebaut, der bisherige 4-Argument-Konstruktor
   **entfällt** — dadurch weist der Compiler jede der acht
   Konstruktionsstellen aus, die den Wert vergessen würde. Dieselbe
   strukturelle Absicherung wie bei `final int id`.
   - `(pos, stripe, speed, energy, originTree)` → neue id, `decayScale = 1`
   - `(pos, stripe, speed, energy, decayScale, originTree)` → neue id
   - `(pos, stripe, speed, energy, id, decayScale, originTree)` → kanonisch
   - `TravellingActivationFiller(..., parentId, decayScale, originTree)`
3. `private int spawnTree(int stripeIdx)` — der **einzige** Ort, an dem der
   Wert entsteht, analog `spawnSpeed()`. Alle fünf Spawn-Pfade gehen
   hindurch; Split-Kinder und Filler **erben** `curActivation.originTree`.
4. `sendOscMessage` hängt `curActivation.originTree` als fünftes Argument an
   `/net/hitNode` an (rein angehängt, wie seinerzeit `x`/`y`).

**Test:** `StripeTreeStoreTest` um `treeOf` erweitern (bekannter Stripe,
unbekannter Stripe → −1, Index außerhalb → −1). Der Effekt selbst hat keine
Suite (oscP5); geprüft wird er über `test/build.sh`.

**Commit:** `Impuls fuehrt seinen Ursprungs-Baum mit`

---

## Task 6 — `MelodyManager.java`, Verdrahtung, Preset-Ausschluss

**Files:** Create `MelodyManager.java`; Modify `PresetStore.java`,
`imPulse.pde`, `test/PresetStoreTest.java`.

Parameter (alle `RemoteControlledIntParameter`):

| Adresse | Bereich | Default |
|---|---|---|
| `/net/melody/mode` | 0..7 | 4 (Phrygisch — einziger Modus, der den Tonvorrat unverändert lässt) |
| `/net/melody/startNode` | 0..`nodeCount-1` (**Laufzeit**) | `graph.defaultStartNode()` |
| `/net/melody/rootMidiNote` | 24..84 | 45 |
| `/net/melody/numOctaves` | 1..6 | 3 |

Kommando `/net/melody/recompute` (eigener `OscMessageSink`, kein Parameter —
wie `/net/activateNode`): liest alle vier Werte, rechnet, schreibt die Datei
und schickt `/net/melody/reload <modeKey>` an 8002. Der Befehl läuft über die
Queue und wird in `draw()` ausgeführt (Threading-Regel).

`PresetStore.EXCLUDED` += die vier Adressen (Transport, nicht Inhalt —
Begründung Abschnitt 9). `PresetStoreTest` prüft, dass sie im Snapshot
fehlen.

`imPulse.pde`: `MelodyManager` nach `LedInNetInfo.applyCrossings(...)`
anlegen, Bericht beim Start (`Graph: N Knoten, M Kanten` und ob die Datei
zum aktuellen Modus existiert). `melodyManager.update()` aus `draw()`.

**Commit:** `Melodie: OSC-Parameter, Neuberechnung und Verdrahtung`

---

## Task 7 — `test/run.sh` und Gesamtlauf

**Files:** Modify `test/run.sh`.

Vier Quellen (`MelodyModes.java`, `MelodyGraph.java`, `MelodyAssigner.java`,
`NodeMelodyStore.java`) in `SOURCES` und vier Suiten in die Default-Liste,
beides nach dem vorhandenen `[ -f ... ]`-Muster.

Run: `test/run.sh` — alle Suiten grün.
Run: `test/build.sh` — der Sketch übersetzt.

**Commit:** `Tests: Melodie-Suiten in test/run.sh`

---

## Task 8 — SuperCollider

**Files:** Modify `supercollider/klangnetz_bells.scd` (die EINE Sound-Datei).

1. `~melodyModes` — Tabelle der acht Skalen, **handgepflegte Spiegelung** von
   `MelodyModes.java`, Reihenfolge identisch (dieselbe Bauform wie `SC_PARAMS`
   im Web-UI).
2. `~melodyDir` = `<scd-dirname>/../data`; `~melodyLoad = { |modeKey| ... }`
   liest `nodeMelody_<key>.txt`, füllt `~melodyScaleIndex` (Dictionary
   nodeId → scaleIndex) und übernimmt `rootMidiNote`/`numOctaves`/`scaleSteps`
   **aus dem Kopf der Datei** (die Datei ist die Wahrheit — die Faltung ist
   mit genau diesen Werten gerechnet worden). Weicht der Kopf von den
   aktuellen Werten ab, wird das gemeldet, nicht verschluckt.
3. Fehlt die Datei: `WARNUNG`-Zeile plus Rückfall auf die heutige
   `nodeId`-Zuordnung (Abschnitt 8) — kein Absturz.
4. Neue Parameter über `~registerParam`: `melodyMode` (0..7, Default 4, mit
   `onSet` → lädt die Datei des neuen Modus), `melodyRootMidiNote` (24..84),
   `melodyNumOctaves` (1..6), `treeBiasAmount` (0..1, Default 0.6 — ersetzt
   `regionBiasAmount`).
5. `OSCdef` auf `/net/melody/reload <modeKey:string>`, Port `~oscListenPort`.
   **Abweichung vom Auftrag, bewusst:** `melodyStartNode` wird auf der
   SC-Seite **nicht** registriert. SC rechnet nichts neu — der Startknoten
   wäre dort ein Regler ohne Wirkung, der zusätzlich in jedes SC-Preset und
   in die Web-UI-Sound-Sektion wandert und dort still nichts täte. Den Knopf
   gibt es weiterhin, nur auf der Seite, die ihn ausführen kann
   (`/net/melody/startNode` in Java). Der Startknoten steht zur Diagnose im
   Kopf der Melodie-Datei und wird beim Laden ausgegeben.
6. `~regionZone`/`~regionBias` **entfallen**; `~treeBias = { |treeIndex| ... }`
   indiziert `~treeNoteOffsets`/`~treeBrightness`/`~treeDetune` (vier Einträge,
   Form und Größe unverändert). Baumindex `-1` → neutral.
7. `/net/hitNode`-Handler: `tree = if (msg.size > 5) { msg[5].asInteger } { -1 }`;
   `noteIndex` kommt aus `~melodyScaleIndex[nodeId]` (Rückfall `nodeId`),
   plus `bias[\noteOffset]`, gefaltet auf `~notesPerOctaveSet`.
8. `/net/impulse`-Handler: der Herkunfts-Bias der Drohne läuft ebenfalls über
   den Baum. `/net/impulse` trägt den Baum **nicht** — deshalb wird dort der
   Bias auf neutral gesetzt und das im Kommentar benannt (eine Erweiterung
   des Impulsstroms um ein sechstes Argument wäre der nächste Schritt, ist
   aber nicht Teil dieses Auftrags).

**Prüfung:** `sclang -D supercollider/klangnetz_bells.scd` lädt fehlerfrei
und ein per `NetAddr.sendMsg("/net/hitNode", ...)` gesendeter Testknoten
bekommt eine plausible Note (NRT/syntaktisch, kein Audio-Rendering).

**Commit:** `SC: topologische Notenzuordnung und Baum-Klangbias`

---

## Task 9 — Web-UI

**Files:** Modify `webui/server.py`, `webui/static/app.js`,
`webui/templates/index.html` (Pfade beim Umsetzen prüfen),
`webui/test_webui.py`.

1. `SC_PARAMS`: `regionBiasAmount` → `treeBiasAmount` umbenennen, die vier
   neuen SC-Parameter ergänzen. **Pflicht**, sonst schlagen die zwei
   bestehenden Abgleichtests gegen die `.scd` fehl.
2. Eigene Sektion **„Melodie-Zuordnung"** über den Tabs (wie Presets): vier
   Zahlenfelder plus Knopf „Zuordnung neu berechnen" mit **ausdrücklicher
   Bestätigung**; der Hinweistext benennt, was passiert. Ein Reglerzug allein
   löst nichts aus.
3. Die vier `/net/melody/*`-Adressen aus dem generischen Rendering nehmen
   (analog `sequencer_addresses()`), sonst stünde jeder Regler zweimal.
4. `test_webui.py`: `SC_PARAMS`-Abgleich in beide Richtungen bleibt grün;
   neuer Test, dass keine `/net/melody/`-Adresse zusätzlich in einem Tab
   landet; jeder Regler weiterhin in genau einem Tab.

Run: `python3 webui/test_webui.py`

**Commit:** `webui: Melodie-Sektion und SC-Parameter nachgezogen`

---

## Task 10 — Dokumentation

**Files:** Modify `CLAUDE.md`.

Neuer Abschnitt „Topologiebasierte Melodiekomposition" unter „Architektur"
mit: Dateiformat, den vier OSC-Parametern samt Ausschluss aus Presets, der
Vorrangregel „Datei-Kopf schlägt SC-Parameter", der Ersetzung des Zonen-Bias
und den zwei gezählten Kantenklassen ohne Garantie. `Tests`-Abschnitt um die
vier Suiten erweitern.

**Commit:** `docs: Melodie-Zuordnung in CLAUDE.md`
