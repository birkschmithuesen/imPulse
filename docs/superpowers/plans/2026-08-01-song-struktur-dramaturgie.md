# Song-Struktur-Dramaturgie — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine Dramaturgie-Schicht oberhalb des Preset-Schedulers, die über
eine ganze Nacht gewichtet-zufällig zwischen vier Energie-Leveln wandert, je
Level eine eigene Verweildauer würfelt und daraus das nächste Preset wählt.

**Architecture:** Drei neue, processing-/oscP5-freie Klassen (`WeightedChoice`,
`EnergyLevelStore`, `SongStructureDirector`) tragen die prüfbare Logik; eine
Klebeschicht in `PresetManager` reicht Zeit, Konfiguration und Namensliste
herein und lädt das Ergebnis über den bestehenden Weg (`PresetManager.load()`,
der `PresetScheduler.noteLoaded()` schon ruft). `PresetScheduler.java` wird
**nicht** geändert.

**Tech Stack:** Java 8 (Processing-Sketch, flaches Default-Package),
`test/run.sh` (javac + main-Methoden-Suiten, nur `core.jar` im Klassenpfad),
Flask/Vanilla-JS für das Web-UI, Python-Tests ohne Fremdabhängigkeiten.

## Global Constraints

- **Sprache:** Kommentare, Konsolenausgaben und UI-Texte auf Deutsch, **ohne
  Umlaute im Java-Quelltext** (bestehende Konvention: „ae/oe/ue/ss").
- **Keine Processing-/oscP5-Abhängigkeit** in `WeightedChoice.java`,
  `EnergyLevelStore.java`, `SongStructureDirector.java` — sie müssen mit nur
  `core.jar` im Klassenpfad übersetzbar bleiben.
- **Zufall wird hereingereicht** über das bestehende Interface `RandomSource`
  (deklariert in `OriginSequencer.java`), niemals `Math.random()` in der
  prüfbaren Schicht.
- **Keine von der Preset-Zahl abgeleitete Zahl als Literal** in Code oder Test.
  Tests bauen sich ihre Tagging-Tabelle selbst.
- **Verweildauern (Birk-Entscheidung 2026-08-01, kurze Testzyklen):**
  ruhig 3–5 min, mittel 2–3 min, dynamisch 1–2 min, dramatisch 0,5–1 min.
- **Übergangsmatrix (Konzept, unverändert, Zeilensumme 100):**
  | von \ nach | ruhig | mittel | dynamisch | dramatisch |
  |---|---|---|---|---|
  | ruhig | 20 | 40 | 30 | 10 |
  | mittel | 25 | 30 | 30 | 15 |
  | dynamisch | 35 | 30 | 20 | 15 |
  | dramatisch | 60 | 25 | 10 | 5 |
- **Startlevel ist fest `ruhig`** (Index 0). Keine Tageszeit-Kopplung.
- **Level-Reihenfolge/Indizes überall gleich:** 0 ruhig, 1 mittel,
  2 dynamisch, 3 dramatisch.
- **Toolchain:** `IMPULSE_CORE_JAR` ist gesetzt
  (`/home/birk/.hermes/impulse-toolchain/processing-3.5.4/core/library/core.jar`).
- **Kein Push, kein Deploy, kein Merge** nach `master`/`grabicz26`.

## Entscheidungen, die vom Konzeptdokument abweichen (mit Begründung)

1. **Tagging-Datei liegt in `data/energyLevels.txt`, nicht in
   `data/presets/`.** `PresetStore.list()` sammelt *jede* `*.txt` im
   Preset-Ordner ein; `energyLevels.txt` entgeht dem heute nur, weil
   `isValidName()` Grossbuchstaben ablehnt. Eine Umbenennung nach
   `energylevels.txt` machte daraus stillschweigend ein Preset, das der
   Scheduler zu laden versucht. `data/stripeTrees.txt` ist ohnehin das im
   Konzept genannte Stil-Vorbild — daneben gehört die Datei auch hin.
2. **Kein `advance(now, singletonList(name))`.** `PresetManager.load()` ruft
   `scheduler.noteLoaded()` bereits selbst; der Umweg über `advance()` wäre
   eine Verwendung, für die der Scheduler nicht gebaut ist, ohne Gewinn.
3. **Matrixgewichte sind 0..100 und werden beim Ziehen normalisiert**, nicht
   0..1 mit Summenzwang — dieselbe Konvention wie
   `/net/impulse/speedQuantize/weight/*`. Ein Operator dreht einen Regler,
   ohne die anderen drei nachrechnen zu müssen.
4. **`/songStructure/goto` ist ein Kommando, kein Parameter** (wie
   `/net/activateNode`): es feuert beim Eintreffen und kann per Konstruktion
   nicht in ein Preset geraten.
5. **Die Anzeige des aktuellen Levels läuft über eine Statusdatei**
   (`data/songStructureState.txt`), nicht über OSC — es gibt keinen Rückkanal
   zum Web-UI. Dasselbe Muster wie die Preset-Liste, die `server.py` direkt
   vom Dateisystem liest.

## File Structure

**Neu (Java, prüfbar):**
- `WeightedChoice.java` — gewichtetes Ziehen aus `float[]`. Ein Ort für die
  Formel; `SpeedQuantizer.pick()` delegiert künftig hierher.
- `EnergyLevelStore.java` — Level-Namen/Indizes, liest `data/energyLevels.txt`,
  beantwortet „welche Presets gehören zu Level X".
- `SongStructureDirector.java` — Zustand (aktuelles Level, Levelstart,
  gezogene Verweildauer, zuletzt gespieltes Preset je Level), Fälligkeit,
  Levelwurf, Preset-Wahl. Dazu die Wertehalter `SongStructureConfig`
  (dieselbe Rolle wie `TrackConfig`).

**Neu (Java, nicht prüfbar — oscP5):**
- `SongStructureParams.java` — die 25 `RemoteControlled*Parameter` plus der
  `OscMessageSink` für `/songStructure/goto`, füllt `SongStructureConfig`.

**Geändert:**
- `SpeedQuantizer.java` — `pick()` delegiert an `WeightedChoice`.
- `PresetStore.java` — `EXCLUDED_PREFIXES` für `/songStructure/`.
- `PresetManager.java` — Director-Tick, Statusdatei, Level-Sync bei manuellem
  Laden.
- `imPulse.pde` — `SongStructureParams` anlegen, an `PresetManager` reichen.
- `webui/server.py` — sechster Tab, `build_song_structure()`,
  `song_structure_addresses()`, `/api/songstructure`.
- `webui/static/app.js`, `webui/static/style.css` — Panel.
- `test/run.sh` — drei neue Quellen, drei neue Suiten.
- `CLAUDE.md` — Abschnitt „Song-Struktur-Dramaturgie".

**Neu (Daten):**
- `data/energyLevels.txt`
- `data/presets/nacht_*.txt` (8 Stück)

**Neu (Tests):**
- `test/WeightedChoiceTest.java`, `test/EnergyLevelStoreTest.java`,
  `test/SongStructureDirectorTest.java`
- Erweiterungen in `test/PresetStoreTest.java`, `webui/test_webui.py`

---

### Task 1: Branch umbenennen, `WeightedChoice` herausziehen

**Files:**
- Create: `WeightedChoice.java`
- Modify: `SpeedQuantizer.java:42-81`
- Test: `test/WeightedChoiceTest.java`, `test/run.sh`

**Interfaces:**
- Produces: `WeightedChoice.pick(float[] weights, int count, double random01, int fallbackIndex) -> int`

- [ ] **Step 1: Branch umbenennen**

```bash
git branch -m feature/song-structure-dramaturgie
git status --short
```

- [ ] **Step 2: Test schreiben** (`test/WeightedChoiceTest.java`) — Verteilung
  über 100 000 Ziehungen, Gewicht 0 wird nie gezogen, Normalisierung
  nicht-prozentualer Gewichte, alle 0 / negativ / NaN → `fallbackIndex`,
  `count` grösser als das Array → `fallbackIndex`, `random01` = 1.0 trifft die
  letzte Klasse **mit** Gewicht.

- [ ] **Step 3: Fehlschlag prüfen**

Run: `test/run.sh WeightedChoiceTest`
Expected: FAIL — `javac`-Fehler „cannot find symbol: class WeightedChoice"

- [ ] **Step 4: `WeightedChoice.java` schreiben** — die Schleife aus
  `SpeedQuantizer.pick()` wörtlich, mit `count` und `fallbackIndex` als
  Parametern.

- [ ] **Step 5: `SpeedQuantizer.pick()` delegieren lassen**

```java
  static int pick(float[] weights, double random01) {
    return WeightedChoice.pick(weights, MULTIPLIERS.length, random01, NEUTRAL_INDEX);
  }
```

- [ ] **Step 6: Beide Suiten grün**

Run: `test/run.sh WeightedChoiceTest SpeedQuantizerTest`
Expected: beide PASS — `SpeedQuantizerTest` ist hier die Regressionsprobe.

- [ ] **Step 7: Commit**

```bash
git add WeightedChoice.java SpeedQuantizer.java test/WeightedChoiceTest.java test/run.sh
git commit -m "WeightedChoice aus SpeedQuantizer herausgezogen"
```

---

### Task 2: Acht klassifizierte Presets + `data/energyLevels.txt`

**Files:**
- Create: `data/presets/nacht_ruhig_atem.txt`, `nacht_ruhig_tropfen.txt`,
  `nacht_mittel_puls.txt`, `nacht_mittel_wandern.txt`,
  `nacht_dynamisch_treiben.txt`, `nacht_dynamisch_schwarm.txt`,
  `nacht_dramatisch_sturm.txt`, `nacht_dramatisch_blitz.txt`
- Create: `data/energyLevels.txt`

**Interfaces:**
- Produces: das Dateiformat, das `EnergyLevelStore` in Task 3 liest.

- [ ] **Step 1: Presets aus `random1.txt` als Vorlage erzeugen.** `random1.txt`
  ist der vollständigste, live nachgezogene Satz. Ihm fehlen die sechs
  `/net/sequencer/track<N>/originTreeFilter`-Zeilen (nach seinem Snapshot
  hinzugekommen) — die kommen mit Wert 0 („alle Bäume") dazu, sonst meldete
  jedes Laden sechs fehlende Adressen. Sechs Tab-Spalten, `\n`, nach Adresse
  sortiert, Wertespalte im `remoteSettings.txt`-Format.

- [ ] **Step 2: Werte je Level setzen** (Begründung je Zeile im Bericht;
  Bereiche aus den bestehenden Presets, nicht frei erfunden):

| Adresse | ruhig | mittel | dynamisch | dramatisch |
|---|---|---|---|---|
| `/net/impulse/speed` | 8–11 | 16–19 | 30–40 | 60–80 |
| `/net/impulse/lifetime` | 0.006–0.010 | 0.018–0.025 | 0.035–0.045 | 0.060–0.080 |
| `/net/impulse/nodeDeadTime` | 6.0–7.5 | 3.0–4.0 | 1.2–1.8 | 0.4–0.8 |
| `/net/randomSpawn/enabled` | 1 | 1 | 1 | 1 |
| `/net/randomSpawn/interval` | 18–26 | 8–12 | 2.5–4.0 | 0.6–1.2 |
| `/net/randomSpawn/energy` | 0.20–0.30 | 0.45–0.55 | 0.65–0.75 | 0.90–1.0 |
| `/net/randomSpawn/count` | 1 | 1–2 | 2–3 | 4–6 |
| `/net/sequencer/enabled` | 0 | 1 | 1 | 1 |
| `/net/sequencer/bpm` | 30–36 | 48–60 | 84–100 | 130–150 |
| aktive Tracks | 0 | 2 | 3–4 | 5–6 |
| `/net/impulse/speedQuantize/enabled` | 0 | 1 | 1 | 1 |
| Gewichte 0x5/1x/2x/4x/8x | – | 10/70/20/0/0 | 0/40/40/15/5 | 0/10/30/40/20 |
| `Master/1/opacity/1.Nodes` | 0.15–0.25 | 0.35–0.45 | 0.55–0.65 | 0.85–1.0 |
| `/nodes/pulseFrequency` | 0.6–0.9 | 1.5–2.0 | 3.0–3.5 | 5.0–6.0 |

  Farben: ruhig kalt und dunkel (Hue um 0.6–0.7, Bright niedrig), mittel
  blaugrün, dynamisch warm (Hue 0.05–0.15), dramatisch rot/weiss und hell.
  Die zwei Presets eines Levels unterscheiden sich in Farbe und in der
  Verteilung innerhalb der Spannen, damit ein wiederbesuchtes Level nicht
  identisch aussieht.

- [ ] **Step 3: Gegenprobe, dass jede Datei ladbar ist** — sechs
  Tab-Spalten in jeder Zeile, gleiche Adressmenge in allen acht Dateien:

```bash
for f in data/presets/nacht_*.txt; do
  awk -F'\t' 'NF!=6 {print FILENAME": Zeile "NR" hat "NF" Spalten"; bad=1} END{exit bad}' "$f" || exit 1
done
cut -f2 data/presets/nacht_ruhig_atem.txt | sort > /tmp/ref.txt
for f in data/presets/nacht_*.txt; do
  cut -f2 "$f" | sort | diff -q /tmp/ref.txt - >/dev/null || echo "ABWEICHUNG: $f"
done
```
Expected: keine Ausgabe.

- [ ] **Step 4: `data/energyLevels.txt` schreiben** — die acht neuen plus die
  sechs bestehenden Presets, Tagging der bestehenden wie im Konzept
  vorgeschlagen (`standby`/`hang_drum_slow` ruhig, `hang_blue`/
  `nachvollziehbar` mittel, `hang_drum_fast`/`random1` dynamisch).

```
# data/energyLevels.txt -- Preset-Name -> Energie-Level
#
# Level: ruhig | mittel | dynamisch | dramatisch
# '#' leitet einen Kommentar ein. Fehlt ein Preset hier, gilt "mittel" und
# der Startbericht nennt es -- ein untagged Preset soll nicht still
# verschwinden, aber die Show auch nicht anhalten.
#
# Bei doppeltem Namen gewinnt die LETZTE Zeile: die natuerliche
# Handkorrektur ist eine angehaengte Zeile am Ende.

standby			ruhig
hang_drum_slow		ruhig
nacht_ruhig_atem	ruhig
nacht_ruhig_tropfen	ruhig

hang_blue		mittel
nachvollziehbar		mittel
nacht_mittel_puls	mittel
nacht_mittel_wandern	mittel

hang_drum_fast		dynamisch
random1			dynamisch
nacht_dynamisch_treiben	dynamisch
nacht_dynamisch_schwarm	dynamisch

nacht_dramatisch_sturm	dramatisch
nacht_dramatisch_blitz	dramatisch
```

- [ ] **Step 5: Commit**

```bash
git add data/presets/nacht_*.txt data/energyLevels.txt
git commit -m "Acht klassifizierte Presets fuer die vier Energie-Level"
```

---

### Task 3: `EnergyLevelStore`

**Files:**
- Create: `EnergyLevelStore.java`
- Test: `test/EnergyLevelStoreTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Consumes: `data/energyLevels.txt` aus Task 2.
- Produces:
  - `EnergyLevelStore.LEVEL_NAMES : String[]` = `{"ruhig","mittel","dynamisch","dramatisch"}`
  - `EnergyLevelStore.LEVEL_COUNT : int` = 4
  - `EnergyLevelStore.FALLBACK_LEVEL : int` = 1 (mittel)
  - `EnergyLevelStore.levelIndexOf(String name) -> int` (−1 unbekannt)
  - `new EnergyLevelStore()`
  - `boolean load(String path)`
  - `int levelOf(String presetName) -> int` (Fallback 1)
  - `List<String> presetsForLevel(int level, List<String> allNames)` — die
    Namen aus `allNames` mit diesem Level, Reihenfolge von `allNames`
  - `String lastMessage()`, `String report()`
  - `int untaggedCount(List<String> allNames)`

- [ ] **Step 1: Test schreiben** (`test/EnergyLevelStoreTest.java`), alles mit
  selbst geschriebenen Temp-Dateien:

```java
// 1 Parsen: Kommentare, Leerzeilen, Tab- und Leerzeichen-Trennung
// 2 Unbekannter Level-Name -> Zeile abgelehnt, gezaehlt, gemeldet
// 3 Doppelter Preset-Name -> LETZTE Zeile gewinnt, overriddenCount() == 1
// 4 levelOf() eines nicht getaggten Presets -> FALLBACK_LEVEL
// 5 presetsForLevel() liefert nur Namen aus allNames, in deren Reihenfolge
// 6 presetsForLevel() eines leeren Levels -> leere Liste (NICHT null:
//    hier ist "keine Presets" eine echte, vom Aufrufer zu behandelnde
//    Aussage, anders als bei StripeTreeStore.stripesFor())
// 7 Fehlende Datei -> load() == false, lastMessage() nennt den Pfad,
//    levelOf() liefert danach weiter FALLBACK_LEVEL statt zu werfen
// 8 untaggedCount() zaehlt Namen aus allNames ohne Eintrag
// 9 Gegenprobe an der echten data/energyLevels.txt und data/presets/:
//    jede Preset-Datei ist getaggt (untaggedCount() == 0) und jedes Level
//    hat mindestens zwei Presets. Die Erwartung wird aus dem Verzeichnis
//    gerechnet, keine feste Zahl im Test.
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `test/run.sh EnergyLevelStoreTest`
Expected: FAIL — „cannot find symbol: class EnergyLevelStore"

- [ ] **Step 3: `EnergyLevelStore.java` schreiben** — Aufbau wörtlich nach
  `StripeTreeStore.load()`: `BufferedReader`/UTF-8, `#`-Kommentar,
  `split("[\\t ]+")`, abgelehnte Zeilen sammeln statt werfen, „letzte Zeile
  gewinnt" mit `overridden`-Zähler, `report()` als Einzeiler für den
  Startbericht. Speicher ist eine `HashMap<String,Integer>`.

- [ ] **Step 4: `test/run.sh` erweitern** — `EnergyLevelStore.java` in
  `SOURCES`, `EnergyLevelStoreTest` in die optionale Liste.

- [ ] **Step 5: Suite grün**

Run: `test/run.sh EnergyLevelStoreTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add EnergyLevelStore.java test/EnergyLevelStoreTest.java test/run.sh
git commit -m "Energie-Level-Klassifikation aus data/energyLevels.txt"
```

---

### Task 4: `SongStructureDirector`

**Files:**
- Create: `SongStructureDirector.java` (enthält auch `SongStructureConfig`)
- Test: `test/SongStructureDirectorTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Consumes: `EnergyLevelStore` (Task 3), `WeightedChoice` (Task 1),
  `RandomSource` (aus `OriginSequencer.java`).
- Produces:
  - `class SongStructureConfig` mit `boolean enabled;`
    `float[][] matrix; // [von][nach], 0..100, wird beim Ziehen normalisiert`
    `float[] dwellMinMinutes; float[] dwellMaxMinutes;`
    und `static SongStructureConfig withDefaults()`, das Matrix und
    Verweildauern auf die in „Global Constraints" genannten Werte setzt.
  - `new SongStructureDirector(EnergyLevelStore tagging, RandomSource rand)`
  - `int currentLevel()`, `String currentLevelName()`
  - `long dwellMillis()`, `long levelStartMillis()`
  - `void start(long nowMillis, SongStructureConfig cfg)` — Startlevel
    **ruhig**, erste Verweildauer ziehen
  - `boolean isDue(long nowMillis, SongStructureConfig cfg)`
  - `String nextPreset(long nowMillis, SongStructureConfig cfg, List<String> allNames)`
  - `void noteLoaded(String name, long nowMillis, SongStructureConfig cfg)`
  - `void requestLevel(int level)` — manueller Sprung, wirkt beim nächsten
    `isDue()`-Aufruf sofort
  - `String lastMessage()`

- [ ] **Step 1: Test schreiben** (`test/SongStructureDirectorTest.java`), mit
  einer `RandomSource`-Attrappe aus einer festen Folge:

```java
// 1  start() setzt Level ruhig (Index 0), unabhaengig vom Zufallswert
// 2  isDue() ist unmittelbar nach start() false, und erst nach der
//    gezogenen Verweildauer true (Grenzfall: exakt bei dwellMillis true)
// 3  Verweildauer liegt fuer jedes Level in [min,max] der Konfiguration;
//    random01 = 0 trifft min, 1 trifft max
// 4  min > max (Fehlbedienung im UI) -> die Spanne wird getauscht statt
//    eine negative Dauer zu liefern
// 5  Aendert die Konfiguration die Spanne waehrend eines laufenden Levels,
//    wird die schon gezogene Dauer auf die neue Spanne geklemmt -- sonst
//    wartete ein Operator, der von 30 auf 3 Minuten dreht, noch 30 Minuten
// 6  Verteilung: 100 000 Uebergaenge aus jedem der vier Level, gemessene
//    Haeufigkeit gegen die Matrixzeile, Toleranz 1 Prozentpunkt
// 7  Ein Gewicht von 0 wird nie gezogen (Matrixzeile ruhig->dramatisch = 0)
// 8  Matrixzeile komplett 0 -> Fallback mittel, kein Absturz
// 9  Innerhalb eines Levels wird das zuletzt in DIESEM Level gespielte
//    Preset vermieden, solange das Level mindestens zwei hat; bei genau
//    einem wird es wiederholt statt zu verstummen
// 10 Leerer Pool des gewuerfelten Levels -> currentLevel bleibt stehen und
//    es wird aus dem bisherigen Level gewaehlt; ist auch das leer, aus
//    allNames. nextPreset() liefert nie null, solange allNames nicht leer
// 11 Leeres allNames -> null, lastMessage() nennt den Grund
// 12 requestLevel(3) -> der naechste Wechsel geht nach dramatisch, ohne
//    Wuerfelwurf, und der Wunsch verfaellt danach (nicht dauerhaft)
// 13 requestLevel() mit ungueltigem Index wird verworfen, nicht geklemmt
// 14 noteLoaded("hang_blue") setzt currentLevel auf dessen Level und
//    startet die Verweildauer neu -- ein manueller Eingriff soll nicht
//    Sekunden spaeter vom naechsten faelligen Wechsel ueberschrieben werden
// 15 enabled = false: isDue() ist immer false UND zieht den Levelstart mit,
//    damit das Wiedereinschalten nicht sofort umschaltet (dieselbe Regel
//    wie PresetScheduler.isDue())
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `test/run.sh SongStructureDirectorTest`
Expected: FAIL — „cannot find symbol: class SongStructureDirector"

- [ ] **Step 3: `SongStructureDirector.java` schreiben.** Kern:

```java
  boolean isDue(long nowMillis, SongStructureConfig cfg) {
    if (cfg == null || !cfg.enabled) {
      levelStart = nowMillis;   // Timer mitziehen, wie PresetScheduler
      return false;
    }
    if (!started) { start(nowMillis, cfg); return false; }
    if (pendingLevel >= 0) { return true; }   // manueller Sprung
    return nowMillis - levelStart >= clampedDwell(cfg);
  }

  private long clampedDwell(SongStructureConfig cfg) {
    float lo = minutesMin(cfg, level), hi = minutesMax(cfg, level);
    long low = (long)(Math.min(lo, hi)*60000f), high = (long)(Math.max(lo, hi)*60000f);
    return Math.max(low, Math.min(high, dwellMillis));
  }
```

  `nextPreset()`: Level bestimmen (`pendingLevel`, sonst
  `WeightedChoice.pick(cfg.matrix[level], LEVEL_COUNT, rand.next(), FALLBACK_LEVEL)`),
  Pool über `tagging.presetsForLevel()` holen, Pool-Rückfall wie in Test 10,
  zuletzt gespieltes ausschliessen, mit `rand.next()` ziehen, Zustand setzen,
  neue Verweildauer ziehen.

- [ ] **Step 4: `test/run.sh` erweitern** — `SongStructureDirector.java` in
  `SOURCES` (nach `EnergyLevelStore.java`), `SongStructureDirectorTest` in
  die optionale Liste.

- [ ] **Step 5: Suite grün**

Run: `test/run.sh SongStructureDirectorTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add SongStructureDirector.java test/SongStructureDirectorTest.java test/run.sh
git commit -m "SongStructureDirector: Markov-Kette ueber vier Energie-Level"
```

---

### Task 5: `/songStructure/*` aus den Presets ausschliessen

**Files:**
- Modify: `PresetStore.java:313-340`
- Test: `test/PresetStoreTest.java`

**Interfaces:**
- Produces: `PresetStore.EXCLUDED_PREFIXES : String[]`,
  `PresetStore.isExcluded(String address) -> boolean`

- [ ] **Step 1: Test ergänzen** in `test/PresetStoreTest.java`:

```java
// snapshot() laesst jede /songStructure/-Adresse weg
// apply() uebergeht sie still (kein "unbekannte Adresse" im Bericht) und
//   meldet sie auch NICHT als fehlend
// eine Adresse, die nur mit dem Praefix anfaengt, aber dazugehoert
//   ("/songStructureFoo") wird NICHT ausgeschlossen -- der Praefix endet
//   auf '/', das ist Absicht
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `test/run.sh PresetStoreTest`
Expected: FAIL — die `/songStructure/`-Adresse landet in `snapshot()`

- [ ] **Step 3: `PresetStore` erweitern**

```java
  // Wie EXCLUDED, aber fuer ganze Adressbaeume. Die Song-Struktur-Ebene ist
  // Transport, nicht Inhalt: ein Preset, das die Uebergangsmatrix mitbraechte,
  // koennte die Dramaturgie beim naechsten Wechsel umschreiben - und das
  // Preset, das sie geaendert hat, waere dann nicht mehr wiederfindbar.
  static final String[] EXCLUDED_PREFIXES = { "/songStructure/" };

  static boolean isExcluded(String address) {
    if (contains(EXCLUDED, address)) { return true; }
    for (int i = 0; i < EXCLUDED_PREFIXES.length; i++) {
      if (address.startsWith(EXCLUDED_PREFIXES[i])) { return true; }
    }
    return false;
  }
```
  In `snapshot()` und `apply()` die drei `contains(EXCLUDED, ...)`-Aufrufe
  durch `isExcluded(...)` ersetzen.

- [ ] **Step 4: Suite grün**

Run: `test/run.sh PresetStoreTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add PresetStore.java test/PresetStoreTest.java
git commit -m "Song-Struktur-Adressen von jedem Preset ausgeschlossen"
```

---

### Task 6: OSC-Parameter und Verdrahtung im Sketch

**Files:**
- Create: `SongStructureParams.java`
- Modify: `PresetManager.java`, `imPulse.pde:96-101,219-238,252-256`
- Test: `test/build.sh` (Übersetzungsprüfung, kein neuer Unit-Test — die
  Klasse hängt an oscP5 und darf nicht in `test/run.sh`)

**Interfaces:**
- Consumes: `SongStructureConfig`, `SongStructureDirector`, `EnergyLevelStore`.
- Produces:
  - `new SongStructureParams(EnergyLevelStore tagging)` — registriert 25
    Parameter und den Kommando-Sink
  - `SongStructureConfig config()` — der aus den Parametern gefüllte Halter
  - `int takePendingLevel()` — −1 oder der per `/songStructure/goto`
    gewünschte Level; verfällt beim Abholen
  - `PresetManager(String presetDirectory, OscP5, NetAddress, SongStructureDirector, SongStructureParams, String statePath)`
  - `PresetManager.update(long nowMillis, boolean schedulerEnabled, float schedulerIntervalSeconds)` — Signatur unverändert

- [ ] **Step 1: `SongStructureParams.java` schreiben.** 25 Adressen:

```
/songStructure/enabled                          int   0..1,   Default 0
/songStructure/matrix/<von>/<nach>              float 0..100, 16 Adressen
/songStructure/dwell/<level>/min                float 0.1..60 Minuten
/songStructure/dwell/<level>/max                float 0.1..60 Minuten
```
  `<von>`, `<nach>` und `<level>` sind die Klartextnamen aus
  `EnergyLevelStore.LEVEL_NAMES`. Defaults: Matrix und Verweildauern aus
  „Global Constraints". Dazu der Kommando-Sink:

```java
  // /songStructure/goto ist ein KOMMANDO, kein Parameter - dieselbe
  // Konstruktion wie /net/activateNode: es feuert beim Eintreffen und kann
  // per Konstruktion nicht in ein Preset geraten, weil diese Klasse
  // PresetTarget nicht implementiert.
  OscMessageDistributor.registerAdress("/songStructure/goto", this);
```
  `writeToStream()` bleibt leer (sonst stünde das Kommando in
  `remoteSettings.txt` und das Web-UI machte einen Regler daraus).
  `/songStructure/enabled` Default **0**: die neue Schicht darf eine laufende
  Show nicht ohne Zutun übernehmen.

- [ ] **Step 2: `PresetManager` erweitern**

```java
  void update(long nowMillis, boolean schedulerEnabled, float schedulerIntervalSeconds) {
    // ... pendingSave/pendingLoad/pendingNext wie bisher ...
    SongStructureConfig cfg = (params == null) ? null : params.config();
    if (cfg != null && cfg.enabled) {
      int wish = params.takePendingLevel();
      if (wish >= 0) { director.requestLevel(wish); }
      // Der alphabetische Scheduler zieht seinen Timer mit, uebernimmt aber
      // nicht: zwei Wechsler auf derselben Szene wuerden sich gegenseitig
      // die Presets wegnehmen, ohne dass einer davon einen Fehler meldet.
      scheduler.isDue(nowMillis, false, schedulerIntervalSeconds);
      if (director.isDue(nowMillis, cfg)) {
        String name = director.nextPreset(nowMillis, cfg, store.list());
        if (name == null) {
          System.out.println("Song-Struktur: " + director.lastMessage());
        } else {
          System.out.println("Song-Struktur: Level " + director.currentLevelName()
              + " fuer " + (director.dwellMillis()/1000) + " s, Preset \"" + name + "\"");
          load(name, nowMillis);
          writeState();
        }
      }
      return;
    }
    // ... heutiges Verhalten unveraendert ...
  }
```
  In `load()` nach `scheduler.noteLoaded(...)` zusätzlich
  `director.noteLoaded(name, nowMillis, cfg)` — damit ein manuelles
  `/preset/load` den Levelzustand mitzieht statt Sekunden später
  überschrieben zu werden.

- [ ] **Step 3: Statusdatei schreiben.** `writeState()` schreibt bei **jedem**
  Levelwechsel (also alle paar Minuten, nicht je Frame) atomar über
  Temp-Datei + Rename nach `data/songStructureState.txt`:

```
level	dynamisch
levelIndex	2
preset	nacht_dynamisch_schwarm
sinceMillis	1754049600000
dwellSeconds	95
```
  Das ist der einzige Weg, auf dem das Web-UI den Live-Zustand erfährt: es
  gibt keinen OSC-Rückkanal (imPulse sendet nur an 8002, dort hört
  SuperCollider). Dasselbe Muster wie die Preset-Liste, die `server.py`
  direkt vom Dateisystem liest. Schlägt das Schreiben fehl, wird das einmal
  gemeldet und die Show läuft weiter.

- [ ] **Step 4: `imPulse.pde` verdrahten** — nach dem Anlegen der Effekte und
  **vor** `loadBootPreset`:

```java
  energyLevelStore = new EnergyLevelStore();
  if (energyLevelStore.load(dataPath("energyLevels.txt"))) {
    System.out.println(energyLevelStore.report());
  } else {
    System.out.println("WARNUNG: " + energyLevelStore.lastMessage()
        + " - jedes Preset gilt als \"mittel\", die Song-Struktur wandert dann"
        + " nicht mehr zwischen den Leveln");
  }
  songStructureParams = new SongStructureParams(energyLevelStore);
  songStructureDirector = new SongStructureDirector(energyLevelStore, new RandomSource() {
    public double next() { return Math.random(); }
  });
  presetManager = new PresetManager(dataPath("presets"), oscP5, oscOutput,
      songStructureDirector, songStructureParams,
      dataPath("songStructureState.txt"));
```

- [ ] **Step 5: Übersetzungsprüfung**

Run: `test/build.sh`
Expected: übersetzt ohne Fehler.

Run: `test/run.sh`
Expected: alle Suiten PASS.

- [ ] **Step 6: Commit**

```bash
git add SongStructureParams.java PresetManager.java imPulse.pde
git commit -m "Song-Struktur-Ebene an Sketch und Preset-Manager angeschlossen"
```

---

### Task 7: Sechster Web-UI-Tab

**Files:**
- Modify: `webui/server.py`, `webui/static/app.js`, `webui/static/style.css`
- Test: `webui/test_webui.py`

**Interfaces:**
- Consumes: die `/songStructure/*`-Adressen aus `remoteSettings.txt`,
  `data/songStructureState.txt` aus Task 6.
- Produces:
  - `TAB_SONG = "song"`, Titel „Song-Struktur", als **sechster** Eintrag in
    `TAB_TITLES`
  - `build_song_structure(by_address) -> Optional[dict]` mit den Schlüsseln
    `enabled`, `levels` (Namen), `matrix` (`[von][nach]` Parameter-Dicts),
    `dwell` (je Level `{min, max}`), `gotoAddress`
  - `song_structure_addresses(section) -> Set[str]`
  - Route `/api/songstructure` → Inhalt der Statusdatei als JSON

- [ ] **Step 1: Tests ergänzen** in `webui/test_webui.py`:

```python
# 1 TAB_TITLES hat sechs Eintraege, TAB_SONG ist dabei
# 2 tab_for_address("/songStructure/enabled") == TAB_SONG
# 3 build_song_structure() liefert None fuer einen Dump ohne die Adressen
#   (aelterer imPulse-Stand faellt still auf generisches Rendering zurueck)
# 4 song_structure_addresses() deckt ALLE /songStructure/-Adressen des
#   Snapshots ab -- sonst stuende ein Regler zweimal auf der Seite
# 5 der vorhandene Test "jede Adresse in genau einem Tab" laeuft mit einem
#   Snapshot, der die neuen Adressen enthaelt, weiter gruen
# 6 parse der Statusdatei: gueltige Datei, fehlende Datei, kaputte Zeile
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `python3 webui/test_webui.py`
Expected: FAIL — `TAB_SONG` existiert nicht

- [ ] **Step 3: `server.py` erweitern.** `TAB_SONG` als sechster Tab,
  `("/songStructure/", TAB_SONG)` in `TAB_RULES` (Position beliebig, kein
  anderer Präfix überschneidet sich), `build_song_structure()` analog zu
  `build_sequencer()`, `song_structure_addresses()` in `snapshot()` mit in
  `taken` verrechnen, Sektion `"songStructure"` in
  `by_tab[TAB_SONG]["sections"]`, Route `/api/songstructure`.

- [ ] **Step 4: `app.js` erweitern** — `buildSongStructure(data, host)`:
  Not-Aus oben, darunter die 4×4-Matrix als Gitter (Zeile = aktuelles Level,
  Spalte = Ziel, je Zelle ein Mini-Regler 0..100 und der normierte
  Prozentwert daneben, damit „Zeilensumme muss nicht 100 sein" sichtbar
  bleibt), darunter je Level eine Verweildauer-Zeile mit min/max in Minuten,
  darunter vier „Jetzt zu … wechseln"-Knöpfe (`/songStructure/goto` mit
  Int 1..4 über `/api/set`… — nein: über eine eigene kleine
  `postJson('/api/goto')`-Route, weil `/api/set` nur Adressen aus
  `remoteSettings.txt` kennt und das Kommando dort bewusst nicht steht).
  Ganz oben eine Statuszeile, die `/api/songstructure` alle 5 s pollt und
  Level, Preset und die verbleibende Zeit zeigt.
  In `buildTabs()` `if (name === 'songStructure') { buildSongStructure(data, panel); }`
  ergänzen.

- [ ] **Step 5: Route für das Kommando** in `server.py`:

```python
    @app.route("/api/goto", methods=["POST"])
    def api_goto():
        """Manueller Levelwechsel. Eigene Route, weil /songStructure/goto ein
        KOMMANDO ist und bewusst nicht in remoteSettings.txt steht -- /api/set
        kennt nur Adressen aus dem Dump."""
        body = request.get_json(silent=True) or {}
        try:
            level = int(body.get("level"))
        except (TypeError, ValueError):
            return jsonify({"ok": False, "error": "Level ist keine Zahl"}), 400
        if not 1 <= level <= len(SONG_LEVEL_NAMES):
            return jsonify({"ok": False, "error": "Level ausserhalb 1..4"}), 400
        sender.send(SONG_GOTO_ADDRESS, level)
        return jsonify({"ok": True, "level": level,
                        "name": SONG_LEVEL_NAMES[level - 1]})
```

- [ ] **Step 6: Tests grün**

Run: `python3 webui/test_webui.py`
Expected: PASS (alle Suiten)

- [ ] **Step 7: Commit**

```bash
git add webui/server.py webui/static/app.js webui/static/style.css webui/test_webui.py
git commit -m "webui: sechster Tab fuer die Song-Struktur-Ebene"
```

---

### Task 8: Dokumentation und Gesamtprüfung

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-08-01-song-struktur-dramaturgie-konzept.md`
  (Kopfnotiz: umgesetzt, mit Verweis auf diesen Plan und die Abweichungen)

- [ ] **Step 1: `CLAUDE.md`-Abschnitt „Song-Struktur-Dramaturgie"** hinter
  dem Preset-System schreiben: die vier Level, die Matrix, die
  Verweildauern, `data/energyLevels.txt`, die Vorrangregel gegenüber dem
  alphabetischen Scheduler, warum die Statusdatei existiert, warum
  `/songStructure/goto` ein Kommando ist, warum die Adressen von jedem
  Preset ausgeschlossen sind. Dazu die neuen Suiten in der „Tests"-Liste
  und `data/energyLevels.txt` im Web-UI-Abschnitt.

- [ ] **Step 2: Gesamtprüfung**

Run: `test/run.sh`
Expected: alle Suiten PASS, keine „Hinweis: … fehlt"-Zeile für die drei neuen.

Run: `test/build.sh`
Expected: übersetzt ohne Fehler.

Run: `python3 webui/test_webui.py`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-08-01-song-struktur-dramaturgie-konzept.md
git commit -m "docs: Song-Struktur-Dramaturgie in CLAUDE.md"
```

---

## Self-Review

**Spec-Abdeckung:** Level-Klassifikation (Task 3, Datei in Task 2),
Übergangsmatrix mit den Konzeptwerten (Task 4/6), Verweildauern mit Birks
verkürzten Werten und live per OSC (Task 4/6), Startlevel ruhig (Task 4),
keine Tageszeit-Kopplung (nirgends gebaut), 8 klassifizierte Presets
(Task 2), eigener Web-UI-Tab inklusive Matrix, Verweildauern, Anzeige und
manuellem Override (Task 7), `PresetScheduler` unverändert (Abgrenzung im
Architektur-Absatz), Tests für alle drei Logikklassen (Task 1/3/4).

**Offene Konzeptfrage, hier entschieden:** Frage 2 („zuletzt gespieltes
vermeiden") — ja, umgesetzt, weil ab zwei Presets je Level billig und ohne
sie ein wiederbesuchtes Level oft identisch klänge. Frage 5 (manueller
Eingriff) — `noteLoaded()` synchronisiert den Director auf das geladene
Preset und startet die Verweildauer neu; der Eingriff bleibt damit stehen,
statt beim nächsten Tick überschrieben zu werden.
