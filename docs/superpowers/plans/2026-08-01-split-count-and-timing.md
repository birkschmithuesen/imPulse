# Wahrscheinlichkeitsbasierte Split-Anzahl und rhythmischer Split-Versatz

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** An einer Kreuzung entscheidet ein Wuerfel, wieviele der moeglichen
Zweige tatsaechlich einen Kind-Impuls bekommen, und die gewaehlten Zweige
starten nicht mehr alle im selben Frame, sondern in einem an MusicalClock/BPM
gekoppelten Notenwert-Abstand.

**Architecture:** Zwei neue reine Klassen (`SplitFanout`, `SplitStagger`) plus
ein aus `SpeedQuantizer` herausgezogener gemeinsamer Ziehungs-Helfer
(`WeightedChoice`). `LedNetworkTransportEffect.activationEncounteredNode()`
sammelt die moeglichen Zweige erst in einer Liste, statt sie sofort zu
spawnen; danach wird die Anzahl gezogen, eine Teilmenge gemischt und jeder
Zweig entweder sofort gespawnt (Versatz 0) oder in eine Warteschlange mit
Beat-Faelligkeit gelegt, die `drawMe()` je Frame abarbeitet.

**Tech Stack:** Java 8 (Processing-Sketch, flaches Default-Package),
`test/run.sh` (javac + main-Methoden, keine Fremdbibliothek), Flask/Vanilla-JS
fuer das Web-UI.

## Global Constraints

- **Auslieferungszustand = heutiges Verhalten.** Birk hat den Split-Layer
  gerade live in Betrieb; ein Neustart mit diesem Stand muss bitgleich
  klingen. Also `weight/all=100`, Rest 0, und `staggerEnabled=0`.
- **Keine von der Kreuzungszahl abgeleitete Zahl als Literal** in Code oder
  Test (CLAUDE.md, Konventionen).
- Neue reine Klassen duerfen **kein** `processing.*`, `oscP5`, `netP5`
  nennen — sonst laufen sie nicht in `test/run.sh`.
- Kein Push, kein Deploy, kein Merge nach `master`/`grabicz26`.
- Alle Kommentare auf Deutsch, ohne Umlaute im Code-Kommentar-Stil des
  bestehenden Repos (ae/oe/ue), im Ton der vorhandenen Dateien: begruendet,
  nicht beschreibend.

---

### Task 1: `WeightedChoice` aus `SpeedQuantizer` herausziehen

**Files:**
- Create: `WeightedChoice.java`
- Modify: `SpeedQuantizer.java`
- Modify: `test/run.sh`
- Test: bestehende `test/SpeedQuantizerTest.java` ist das Sicherheitsnetz

**Interfaces:**
- Produces: `static int WeightedChoice.pick(float[] weights, int count, int fallbackIndex, double random01)`

- [ ] **Step 1:** `WeightedChoice.java` anlegen; der Rumpf ist wortgleich der
  heutige `SpeedQuantizer.pick()`, nur mit `count` statt
  `MULTIPLIERS.length` und `fallbackIndex` statt `NEUTRAL_INDEX`.
- [ ] **Step 2:** `SpeedQuantizer.pick()` auf einen Einzeiler-Delegat
  reduzieren.
- [ ] **Step 3:** `WeightedChoice.java` in `test/run.sh` aufnehmen.
- [ ] **Step 4:** `test/run.sh SpeedQuantizerTest` — muss unveraendert gruen
  sein. Das ist der Beweis, dass die Extraktion nichts verschoben hat.
- [ ] **Step 5:** Commit.

---

### Task 2: `SplitFanout` — wieviele Zweige, und welche

**Files:**
- Create: `SplitFanout.java`
- Create: `test/SplitFanoutTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Consumes: `WeightedChoice.pick`, `RandomSource` (aus `OriginSequencer.java`)
- Produces:
  - `static final int CATEGORY_COUNT = 3` (Index 0 = alle, 1 = einer weniger, 2 = genau einer)
  - `static final int NEUTRAL_INDEX = 0`
  - `static int branchCount(float[] weights, int candidates, double random01)`
  - `static int[] chooseOrder(int candidates, int take, RandomSource rnd)`

- [ ] **Step 1:** Testfaelle schreiben: neutraler Auslieferungsfall
  (`{100,0,0}` liefert immer `candidates`), Verteilung ueber viele Ziehungen,
  Gewicht 0 wird nie gezogen, entartete Gewichte, `candidates <= 1` liefert
  immer `candidates` (auch 0), Klemmung auf `1..candidates` (bei
  `candidates == 2` fallen "einer weniger" und "genau einer" zusammen),
  `chooseOrder` liefert `take` verschiedene Indizes im Bereich, ist bei
  `take == candidates` eine Permutation, und ist nicht auf eine feste
  Reihenfolge festgelegt.
- [ ] **Step 2:** `test/run.sh SplitFanoutTest` — muss fehlschlagen
  (`SplitFanout` existiert nicht).
- [ ] **Step 3:** `SplitFanout.java` implementieren.
- [ ] **Step 4:** `test/run.sh SplitFanoutTest` — gruen.
- [ ] **Step 5:** Commit.

---

### Task 3: `SplitStagger` — Beat-Faelligkeit der verzoegerten Zweige

**Files:**
- Create: `SplitStagger.java` (enthaelt auch die reine Datenklasse `PendingSpawn`)
- Create: `test/SplitStaggerTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Produces:
  - `class PendingSpawn { double dueBeats; float ledPos; int stripeIdx; float speed; float energy; float decayScale; }`
  - `static final int MAX_PENDING = 512`
  - `static double delayBeats(int noteValue, int slot)` — `slot * MusicalClock.beatsPerNote(quantizeNoteValue(noteValue))`
  - `void schedule(PendingSpawn p)`
  - `java.util.List<PendingSpawn> due(double beats)`
  - `int pendingCount()`
  - `void clear()`

- [ ] **Step 1:** Testfaelle schreiben: nichts faellig vor der Zeit; faellig
  genau auf der Grenze; Reihenfolge nach `dueBeats`; ein Rueckwaertssprung
  der Beat-Position liefert nichts und verliert nichts; `MAX_PENDING`
  begrenzt die Warteschlange und verwirft die NEUEN statt die wartenden;
  `delayBeats` rastet den Notenwert und liefert fuer Slot 0 exakt 0.
- [ ] **Step 2:** `test/run.sh SplitStaggerTest` — muss fehlschlagen.
- [ ] **Step 3:** `SplitStagger.java` implementieren.
- [ ] **Step 4:** `test/run.sh SplitStaggerTest` — gruen.
- [ ] **Step 5:** Commit.

---

### Task 4: Einbau in `LedNetworkTransportEffect`

**Files:**
- Modify: `LedNetworkTransportEffect.java`

**Interfaces:**
- Consumes: Task 1–3.

Neue Parameter (alle `RemoteControlled*`, tauchen dadurch von selbst in
`remoteSettings.txt` und im Web-UI auf):

| Adresse | Typ | Default | Range |
|---|---|---|---|
| `/net/impulse/split/weight/all` | float | 100 | 0..100 |
| `/net/impulse/split/weight/oneLess` | float | 0 | 0..100 |
| `/net/impulse/split/weight/single` | float | 0 | 0..100 |
| `/net/impulse/split/staggerEnabled` | int | 0 | 0..1 |
| `/net/impulse/split/staggerNoteValue` | int | 16 | 1..16 |

- [ ] **Step 1:** Felder + Konstruktor-Defaults ergaenzen, dazu ein
  `float[] splitWeightScratch` (wiederverwendet, wie `weightScratch`) und ein
  `final SplitStagger splitStagger`.
- [ ] **Step 2:** `activationEncounteredNode()` umbauen: erst alle moeglichen
  Zweige als `PendingSpawn` mit `dueBeats = 0` in eine wiederverwendete
  Sammelliste legen (dieselbe Bedingungslogik wie heute, unveraendert),
  dann `SplitFanout.branchCount(...)` + `chooseOrder(...)`, dann je gewaehltem
  Zweig entweder `newActivations.add(...)` (Slot 0 oder Stagger aus) oder
  `splitStagger.schedule(...)`.
- [ ] **Step 3:** `releasePendingSplits()` schreiben und in `drawMe()`
  **vor** der Iteration ueber `activations` aufrufen, nach `tickSequencer()`
  (das die Uhr fortschreibt).
- [ ] **Step 4:** `test/build.sh` — der Sketch muss uebersetzen.
- [ ] **Step 5:** `test/run.sh` — alle Suiten gruen.
- [ ] **Step 6:** Commit.

---

### Task 5: Web-UI

**Files:**
- Modify: `webui/server.py`
- Modify: `webui/static/app.js`
- Modify: `webui/test_webui.py`
- Modify: `webui/data/remoteSettings.txt`-Snapshot des Tests, falls vorhanden

**Entscheidung:** Tab **Noten-Verhalten**. Begruendung im Code-Kommentar:
der Versatz ist BPM-gekoppelt, und `/net/impulse/speedQuantize/` liegt aus
demselben Grund schon dort; die zwei Haelften eines Features gehoeren auf
denselben Tab.

- [ ] **Step 1:** `TAB_RULES`: `("/net/impulse/split/", TAB_NOTES)` **vor**
  `("/net/impulse/", TAB_PHYSICS)`. Der Schraegstrich am Ende schuetzt die
  bestehenden `/net/impulse/splitSpeedJitter`-Adressen.
- [ ] **Step 2:** `build_split()` analog `build_speed_classes()`, plus
  Aufnahme in `sequencer_addresses()`, plus `DESCRIPTIONS`-Eintraege.
- [ ] **Step 3:** `buildSplit()` in `app.js`, das `noteBar()` fuer den
  Notenwert wiederverwendet.
- [ ] **Step 4:** Tests in `webui/test_webui.py`: jede neue Adresse genau
  einem Tab, keine doppelt, Sektion nimmt ihre Adressen aus dem generischen
  Rendering.
- [ ] **Step 5:** `python3 webui/test_webui.py` — gruen.
- [ ] **Step 6:** Commit.

---

### Task 6: Dokumentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1:** Abschnitt unter „Impuls-Simulation" ergaenzen: die fuenf
  Adressen, die Kategorien-Semantik bei Grad != 3, warum Beat statt
  Millisekunden, warum die wartenden Kinder keine Energie verlieren, warum
  `MAX_PENDING`.
- [ ] **Step 2:** „Tests"-Abschnitt um `SplitFanoutTest` und
  `SplitStaggerTest` ergaenzen.
- [ ] **Step 3:** Commit.
