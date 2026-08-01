# Gewichteter Notenwert fuer den Split-Versatz — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der zeitliche Versatz zwischen den Zweigen einer Aufspaltung nutzt
nicht mehr einen festen Notenwert-Regler, sondern zieht den Notenwert **je
Aufspaltung** aus fuenf gewichteten Klassen (Ganze/Halbe/Viertel/Achtel/
Sechzehntel).

**Architecture:** `SplitStagger` bekommt eine statische `pickNoteValue()`, die
ueber das schon vorhandene `WeightedChoice` zieht — dasselbe Muster wie
`SplitFanout.branchCount()` und `SpeedQuantizer.pick()`. Die Notenwert-Liste
wird nicht kopiert, sondern aus `OriginSequencer` bezogen (dort steht sie
schon, `quantizeNoteValue()` rastet darauf). Der Effekt zieht **einmal je
Split-Ereignis** und reicht den gezogenen Notenwert unveraendert in die
bestehende `delayBeats(noteValue, slot)`.

**Tech Stack:** Processing/Java (flach im Sketch-Ordner, kein Build-System),
`test/run.sh` als Suite, Flask + Vanilla-JS im `webui/`.

## Global Constraints

- **Ein Notenwert je Split-Ereignis, nicht je Kind-Slot.** Alle Kinder
  desselben Splits teilen den gezogenen Notenwert; `delayBeats()` bleibt
  `slot * beatsPerNote(noteValue)`. Begruendung: der Versatz soll ein Raster
  sein, kein Zittern. Zoege jedes Kind einzeln, waeren die Abstaende
  *innerhalb* einer Aufspaltung ungleich — genau die Gleichmaessigkeit, an
  der man einen Rhythmus ueberhaupt erkennt, ginge verloren. Die Klasse
  variiert dann von Aufspaltung zu Aufspaltung, und das ist das Ziel.
- **Gewichte 0..100, nicht 0..1**, wie `/net/impulse/split/weight/*` und
  `/net/impulse/speedQuantize/weight/*`. Eine dritte Skala fuer dieselbe Sache
  waere ein Regler, dessen Zahl an der Nachbarsektion etwas anderes bedeutet.
- **Der entartete Fall (alle Gewichte 0, NaN) faellt auf Sechzehntel** —
  den bisherigen Default des ersetzten Reglers und den kuerzesten Versatz.
  Konsistent mit `SplitFanout` (faellt auf „alle Zweige" = Verhalten von
  vorher) und `SpeedQuantizer` (faellt auf 1x).
- **Keine Rueckwaertskompatibilitaet noetig.** `/net/impulse/split/staggerNoteValue`
  existiert nur auf diesem Branch, kein Preset in `data/presets/` und keine
  `scenes/`-Datei nennt ihn (verifiziert per grep), `data/remoteSettings.txt`
  ist nicht im Repo und wird bei jedem Start neu geschrieben.
- Keine von einer Kreuzungszahl abgeleiteten Literale in Tests.
- Kommentare und UI-Texte auf Deutsch ohne Umlaute im Code-Kommentar-Stil des
  Repos (ae/oe/ue), wie in den Nachbardateien.

---

### Task 1: `SplitStagger.pickNoteValue()` samt Notenwert-Tabelle

**Files:**
- Modify: `OriginSequencer.java` (NOTE_VALUES sichtbar machen + `noteValueAt`)
- Modify: `SplitStagger.java`
- Test: `test/SplitStaggerTest.java`

**Interfaces:**
- Consumes: `WeightedChoice.pick(float[] weights, int count, int fallbackIndex, double random01)`,
  `OriginSequencer.quantizeNoteValue(int)`, `MusicalClock.beatsPerNote(int)`
- Produces:
  - `OriginSequencer.NOTE_VALUES` (package-privat, `static final int[]`, `{1,2,4,8,16}`)
  - `OriginSequencer.noteValueAt(int index)` → `int`
  - `SplitStagger.NOTE_COUNT` → `int` (= 5)
  - `SplitStagger.NEUTRAL_NOTE_INDEX` → `int` (= 4, Sechzehntel)
  - `SplitStagger.pickNoteValue(float[] weights, double random01)` → `int`
    (einer aus 1/2/4/8/16)

- [ ] **Step 1: Test schreiben** (`test/SplitStaggerTest.java`, vor
  `System.exit(...)` eingefuegt)

```java
    // ---- pickNoteValue: welcher Notenwert dieser Split bekommt ----
    float[] onlyEighth = new float[SplitStagger.NOTE_COUNT];
    onlyEighth[3] = 100f;
    Check.eq("ein einziges Gewicht zieht immer sich selbst",
        8, SplitStagger.pickNoteValue(onlyEighth, 0.0));
    Check.eq("auch am oberen Rand des Zufallswerts",
        8, SplitStagger.pickNoteValue(onlyEighth, 1.0));

    // Verteilung ueber viele Ziehungen, wie in SplitFanoutTest.
    float[] mix = new float[SplitStagger.NOTE_COUNT];
    mix[2] = 20f;  // Viertel
    mix[3] = 30f;  // Achtel
    mix[4] = 50f;  // Sechzehntel
    int draws = 100000;
    java.util.HashMap<Integer, Integer> seen = new java.util.HashMap<Integer, Integer>();
    for (int i = 0; i < draws; i++) {
      int nv = SplitStagger.pickNoteValue(mix, (i + 0.5)/draws);
      Integer c = seen.get(nv);
      seen.put(nv, c == null ? 1 : c + 1);
    }
    Check.eq("kein Gewicht 0 wird gezogen", null, seen.get(1));
    Check.eq("kein Gewicht 0 wird gezogen", null, seen.get(2));
    Check.near("Viertel bei 20 %", 0.20, seen.get(4)/(double) draws, 0.01);
    Check.near("Achtel bei 30 %", 0.30, seen.get(8)/(double) draws, 0.01);
    Check.near("Sechzehntel bei 50 %", 0.50, seen.get(16)/(double) draws, 0.01);

    // Nicht-prozentuale Gewichte werden normalisiert.
    float[] raw = new float[SplitStagger.NOTE_COUNT];
    raw[3] = 3f;
    raw[4] = 1f;
    int eighths = 0;
    for (int i = 0; i < draws; i++) {
      if (SplitStagger.pickNoteValue(raw, (i + 0.5)/draws) == 8) {
        eighths++;
      }
    }
    Check.near("3:1 macht 75 % Achtel", 0.75, eighths/(double) draws, 0.01);

    // Der entartete Fall faellt auf Sechzehntel: den kuerzesten Versatz und
    // den bisherigen Default des ersetzten Reglers. Ein Split ohne
    // brauchbare Gewichte soll nicht sekundenlang auseinanderfallen.
    Check.eq("alle Gewichte 0 gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(new float[SplitStagger.NOTE_COUNT], 0.5));
    Check.eq("null als Gewichtstabelle gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(null, 0.5));
    Check.eq("zu kurze Gewichtstabelle gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(new float[2], 0.5));
    Check.eq("NaN als Zufallswert gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(mix, Double.NaN));
    float[] negative = new float[SplitStagger.NOTE_COUNT];
    negative[0] = -5f;
    negative[1] = Float.NaN;
    Check.eq("negative und NaN-Gewichte zaehlen als 0",
        16, SplitStagger.pickNoteValue(negative, 0.5));

    // Was herauskommt, ist immer ein Notenwert, den delayBeats() kennt -
    // sonst rastete quantizeNoteValue() ihn still auf etwas anderes.
    float[] alle = new float[SplitStagger.NOTE_COUNT];
    for (int i = 0; i < alle.length; i++) {
      alle[i] = 1f;
    }
    for (int i = 0; i <= 20; i++) {
      int nv = SplitStagger.pickNoteValue(alle, i/20.0);
      Check.eq("gezogener Notenwert ueberlebt die Rasterung",
          nv, OriginSequencer.quantizeNoteValue(nv));
    }
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestaetigen**

Run: `test/run.sh SplitStaggerTest`
Expected: Uebersetzungsfehler „cannot find symbol: NOTE_COUNT / pickNoteValue".

- [ ] **Step 3: `OriginSequencer.NOTE_VALUES` teilbar machen**

In `OriginSequencer.java` das `private` entfernen und einen Lesezugriff
ergaenzen:

```java
  // Die erlaubten Notenwerte. RemoteControlledIntParameter kann keine
  // Aufzaehlung, deshalb rastet quantizeNoteValue() beim Lesen.
  //
  // Package-privat statt private, weil SplitStagger dieselbe Liste braucht
  // (der Notenwert des Split-Versatzes wird aus denselben Klassen gezogen).
  // Eine zweite Kopie waeren zwei Aufzaehlungen fuer dieselbe Sache: eine
  // sechste Klasse hier ergaenzt, dort vergessen, und "Sechzehntel" hiesse
  // im Sketch an zwei Stellen etwas anderes.
  static final int[] NOTE_VALUES = { 1, 2, 4, 8, 16 };

  // Notenwert an einer Index-Position, wie SpeedQuantizer.multiplierAt().
  // Ein Index ausserhalb liefert Viertel - der neutrale Notenwert, mit dem
  // beatsPerNote() schon bei 0 antwortet.
  static int noteValueAt(int index) {
    if (index < 0 || index >= NOTE_VALUES.length) {
      return 4;
    }
    return NOTE_VALUES[index];
  }
```

- [ ] **Step 4: `SplitStagger.pickNoteValue()` schreiben**

In `SplitStagger.java` oberhalb von `delayBeats(...)` einfuegen:

```java
  // Zahl der Notenwert-Klassen, aus denen der Versatz gezogen wird -
  // dieselben wie beim Sequencer, in derselben Reihenfolge
  // (Index 0 = Ganze ... Index 4 = Sechzehntel).
  static final int NOTE_COUNT = OriginSequencer.NOTE_VALUES.length;

  // Der Rueckfall fuer den entarteten Fall: Sechzehntel, der kuerzeste
  // Versatz. Bis 2026-08-01 war das der Auslieferungswert des einen festen
  // Notenwert-Reglers; eine unbrauchbare Gewichtstabelle soll die
  // Aufspaltung also lassen, wie sie war, und sie nicht ueber einen ganzen
  // Takt auseinanderziehen. Dieselbe Regel wie SplitFanout.NEUTRAL_INDEX
  // und SpeedQuantizer.NEUTRAL_INDEX.
  static final int NEUTRAL_NOTE_INDEX = NOTE_COUNT - 1;

  // Der Notenwert EINER Aufspaltung, gezogen nach Gewichten.
  //
  // Gezogen wird je Split-Ereignis, nicht je Kind: alle Zweige derselben
  // Aufspaltung stehen damit auf demselben Raster. Zoege jedes Kind einzeln,
  // waeren schon die Abstaende innerhalb einer Aufspaltung ungleich - und
  // damit genau die Gleichmaessigkeit weg, an der ein Rhythmus zu erkennen
  // ist. Die Klasse wechselt von Aufspaltung zu Aufspaltung; das ist der
  // Zweck.
  //
  // Die Ziehung selbst steht in WeightedChoice, geteilt mit SplitFanout und
  // SpeedQuantizer: Gewicht 0 zieht nie, NaN und negative Werte gelten als 0,
  // die Summe muss nicht 100 sein.
  static int pickNoteValue(float[] weights, double random01) {
    return OriginSequencer.noteValueAt(
        WeightedChoice.pick(weights, NOTE_COUNT, NEUTRAL_NOTE_INDEX, random01));
  }
```

- [ ] **Step 5: Test laufen lassen**

Run: `test/run.sh SplitStaggerTest`
Expected: `SplitStaggerTest: N/N ok`

- [ ] **Step 6: Volle Suite**

Run: `test/run.sh`
Expected: alle Suiten gruen.

- [ ] **Step 7: Commit**

```bash
git add SplitStagger.java OriginSequencer.java test/SplitStaggerTest.java
git commit -m "SplitStagger: Notenwert des Versatzes gewichtet ziehen"
```

---

### Task 2: Fuenf Gewichts-Parameter im Transport-Effekt

**Files:**
- Modify: `LedNetworkTransportEffect.java` (Felder ~Zeile 82, Konstruktor
  ~Zeile 231, `spawnSplitChildren()` ~Zeile 637)

**Interfaces:**
- Consumes: `SplitStagger.NOTE_COUNT`, `SplitStagger.pickNoteValue(float[], double)`
- Produces: OSC-Adressen `/net/impulse/split/stagger/weight/{whole,half,quarter,eighth,sixteenth}`
  (float, 0..100, Defaults 0/0/10/30/60). `/net/impulse/split/staggerNoteValue`
  faellt weg. `/net/impulse/split/staggerEnabled` bleibt unveraendert.

- [ ] **Step 1: Feld ersetzen**

`RemoteControlledIntParameter splitStaggerNoteValue;` ersetzen durch:

```java
  // Ein Gewicht je Notenwert-Klasse, Reihenfolge wie
  // OriginSequencer.NOTE_VALUES (Ganze .. Sechzehntel). Gezogen wird je
  // Aufspaltung, nicht je Kind - siehe SplitStagger.pickNoteValue().
  //
  // Ein fester Notenwert stand hier bis 2026-08-01. Er machte jeden Versatz
  // im ganzen Betrieb gleich lang; mit den Gewichten wird die Laenge selbst
  // zum gestaltbaren Ereignis, ohne dass ein Operator sie live nachdrehen
  // muss.
  RemoteControlledFloatParameter[] splitStaggerNoteWeights =
      new RemoteControlledFloatParameter[SplitStagger.NOTE_COUNT];
```

Direkt neben `fanoutScratch` einen zweiten Kratzpuffer ergaenzen:

```java
  private final float[] staggerScratch = new float[SplitStagger.NOTE_COUNT];
```

- [ ] **Step 2: Konstruktor umbauen**

Die Zeile `splitStaggerNoteValue= new RemoteControlledIntParameter(...)`
samt ihrem Kommentar ersetzen durch:

```java
    // Defaults: Schwerpunkt auf den kurzen Notenwerten (Sechzehntel 60,
    // Achtel 30, Viertel 10). Der Versatz soll knapp sein - ein halber Takt
    // zwischen zwei Zweigen liest sich nicht mehr als eine Aufspaltung,
    // sondern als zwei getrennte Impulse. Halbe und Ganze stehen deshalb auf
    // 0 und sind da, wenn sie jemand haben will.
    //
    // Adressnamen ausgeschrieben statt "16": eine 16 in der Adresse liest
    // sich in remoteSettings.txt wie eine Anzahl, nicht wie ein Notenwert.
    String[] staggerNoteNames = { "whole", "half", "quarter", "eighth", "sixteenth" };
    float[] staggerNoteDefaults = { 0f, 0f, 10f, 30f, 60f };
    for (int i=0; i<SplitStagger.NOTE_COUNT; i++) {
      splitStaggerNoteWeights[i]= new RemoteControlledFloatParameter(
          "/net/impulse/split/stagger/weight/"+staggerNoteNames[i],
          staggerNoteDefaults[i], 0f, 100f);
    }
```

- [ ] **Step 3: `spawnSplitChildren()` umbauen**

```java
    boolean stagger=splitStaggerEnabled.getValue() == 1;
    // EINE Ziehung fuer die ganze Aufspaltung: alle Zweige stehen damit auf
    // demselben Raster. Je Kind gezogen waeren schon die Abstaende innerhalb
    // eines Splits ungleich - siehe SplitStagger.pickNoteValue().
    int noteValue=16;
    if (stagger) {
      for (int i=0; i<splitStaggerNoteWeights.length; i++) {
        staggerScratch[i]=splitStaggerNoteWeights[i].getValue();
      }
      noteValue=SplitStagger.pickNoteValue(staggerScratch, Math.random());
    }
```

Der Rest der Methode bleibt, wie er ist (`SplitStagger.delayBeats(noteValue, slot)`).

- [ ] **Step 4: Uebersetzung pruefen**

Run: `test/build.sh`
Expected: „OK" bzw. keine Fehlermeldung des Uebersetzers.

- [ ] **Step 5: Volle Suite**

Run: `test/run.sh`
Expected: alle Suiten gruen.

- [ ] **Step 6: Commit**

```bash
git add LedNetworkTransportEffect.java
git commit -m "Split-Versatz: Notenwert je Aufspaltung nach Gewichten ziehen"
```

---

### Task 3: Web-UI — Gewichte statt Notenwert-Leiste

**Files:**
- Modify: `webui/server.py` (`SPLIT_*`-Konstanten ~Zeile 208, `DESCRIPTIONS`
  ~Zeile 245, `build_split()` ~Zeile 319, `sequencer_addresses()` ~Zeile 367)
- Modify: `webui/static/app.js` (`buildSplit()` ~Zeile 1161)
- Test: `webui/test_webui.py`

**Interfaces:**
- Consumes: Snapshot-Feld `split` aus `server.py`
- Produces: `server.SPLIT_STAGGER_WEIGHT_PREFIX`,
  `server.SPLIT_STAGGER_NOTES: List[Tuple[str, int]]` (Suffix, Notenwert),
  Snapshot `split["staggerWeights"]` (Liste mit `label`, `symbol`, `address`, …);
  `split["staggerNoteValue"]` entfaellt.

- [ ] **Step 1: Tests anpassen/ergaenzen** (`webui/test_webui.py`)

Im SAMPLE die Zeile mit `staggerNoteValue` ersetzen durch fuenf Zeilen:

```python
            "float\t/net/impulse/split/stagger/weight/whole\tx\t0\t0\t100",
            "float\t/net/impulse/split/stagger/weight/half\tx\t0\t0\t100",
            "float\t/net/impulse/split/stagger/weight/quarter\tx\t10\t0\t100",
            "float\t/net/impulse/split/stagger/weight/eighth\tx\t30\t0\t100",
            "float\t/net/impulse/split/stagger/weight/sixteenth\tx\t60\t0\t100",
```

`test_special_addresses_are_removed_from_generic_groups` anpassen:

```python
        self.assertIn("/net/impulse/split/stagger/weight/sixteenth", taken)
        expected = (2
                    + server.SEQUENCER_TRACK_COUNT
                    * (1 + len(server.SEQUENCER_TRACK_FIELDS))
                    + 2 + len(server.SPEED_CLASSES)
                    + 1 + len(server.SPLIT_WEIGHTS)
                    + len(server.SPLIT_STAGGER_NOTES))
```

`test_split_carries_the_note_values_for_the_stagger` ersetzen durch:

```python
    def test_split_stagger_weights_follow_the_java_note_values(self):
        """Ein Gewicht je Notenwert-Klasse, in der Reihenfolge der Java-Seite
        (OriginSequencer.NOTE_VALUES: Ganze .. Sechzehntel)."""
        split = server.build_split(self._params())
        self.assertEqual([w["noteValue"] for w in split["staggerWeights"]],
                         [1, 2, 4, 8, 16])
        for weight in split["staggerWeights"]:
            # Das Symbol allein reicht nicht: nicht jede Windows-Schrift hat
            # U+1D15D..U+1D161, der Name ist der Rueckfall.
            self.assertTrue(weight["symbol"])
            self.assertTrue(weight["label"])
        self.assertIsNotNone(split["staggerEnabled"])
        self.assertNotIn("staggerNoteValue", split)
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestaetigen**

Run: `python3 webui/test_webui.py`
Expected: FAIL — `AttributeError: module 'server' has no attribute 'SPLIT_STAGGER_NOTES'`

- [ ] **Step 3: `server.py` — Konstanten**

Unter `SPLIT_WEIGHTS` ergaenzen:

```python
# Notenwert-Klassen des Split-Versatzes: Adress-Suffix und Notenwert. Die
# Reihenfolge spiegelt OriginSequencer.NOTE_VALUES (Ganze .. Sechzehntel);
# Symbol und Name kommen aus NOTE_VALUES oben, damit "Achtel" im ganzen UI
# gleich aussieht.
SPLIT_STAGGER_WEIGHT_PREFIX = SPLIT_PREFIX + "stagger/weight/"
SPLIT_STAGGER_NOTES: List[Tuple[str, int]] = [
    ("whole", 1),
    ("half", 2),
    ("quarter", 4),
    ("eighth", 8),
    ("sixteenth", 16),
]
```

- [ ] **Step 4: `server.py` — `DESCRIPTIONS`**

Den Eintrag `"/net/impulse/split/staggerNoteValue"` ersetzen durch:

```python
    "/net/impulse/split/stagger/weight/sixteenth":
        "Gewicht fuer einen Sechzehntel-Abstand zwischen den Zweigen. Der "
        "Notenwert wird je Aufspaltung neu gezogen, nicht je Zweig.",
```

- [ ] **Step 5: `server.py` — `build_split()`**

`note_value = by_address.get(SPLIT_PREFIX + "staggerNoteValue")` ersetzen durch
den Aufbau der Gewichtsliste, und den Rueckgabewert anpassen:

```python
    note_labels = {v: (s, n) for v, s, n in NOTE_VALUES}
    stagger_weights: List[Dict[str, Any]] = []
    for suffix, note_value in SPLIT_STAGGER_NOTES:
        param = by_address.get(SPLIT_STAGGER_WEIGHT_PREFIX + suffix)
        if param is None:
            continue
        symbol, name = note_labels[note_value]
        entry = param.as_dict()
        entry["noteValue"] = note_value
        entry["symbol"] = symbol
        entry["label"] = name
        stagger_weights.append(entry)
    if not weights and stagger_enabled is None and not stagger_weights:
        return None
    return {
        "weights": weights,
        "staggerEnabled": stagger_enabled.as_dict() if stagger_enabled is not None else None,
        "staggerWeights": stagger_weights,
    }
```

Der Docstring von `build_split()` bekommt einen Satz zum Ersatz der
Notenwert-Leiste durch die Gewichte.

- [ ] **Step 6: `server.py` — `sequencer_addresses()`**

```python
    if split:
        entry = split.get("staggerEnabled")
        if entry:
            taken.add(entry["address"])
        for weight in split["weights"]:
            taken.add(weight["address"])
        for weight in split.get("staggerWeights", []):
            taken.add(weight["address"])
```

- [ ] **Step 7: Tests laufen lassen**

Run: `python3 webui/test_webui.py`
Expected: `OK`

- [ ] **Step 8: `app.js` — `buildSplit()` umbauen**

Der `if (split.staggerNoteValue) { ... }`-Block wird durch eine zweite
Gewichts-Sektion mit eigenem Verteilungsbalken ersetzt (Beschriftung
`symbol + ' ' + label`, Balken haengt an `set()` **und** am `input`-Event,
Rueckfalltext bei Summe 0: „alle Gewichte 0 – es gilt Sechzehntel").
Der Erklaerungstext nennt weiter `/net/sequencer/bpm` als Raster und
ergaenzt: der Notenwert wird je Aufspaltung gezogen, alle Zweige einer
Aufspaltung teilen ihn.

- [ ] **Step 9: Server starten und Seite abrufen**

Run: `cd webui && python3 -c "import server; ..."` bzw. Snapshot-Endpunkt
gegen eine Beispiel-`remoteSettings.txt` pruefen; Ziel ist: `/api/state`
liefert `split.staggerWeights` mit fuenf Eintraegen und ohne
`staggerNoteValue`.

- [ ] **Step 10: Commit**

```bash
git add webui/server.py webui/static/app.js webui/test_webui.py
git commit -m "webui: Gewichte je Notenwert fuer den Split-Versatz"
```

---

### Task 4: Dokumentation

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Split-Anzahl und Split-Versatz")

- [ ] **Step 1: Parameterliste und Begruendung nachziehen**

`/net/impulse/split/staggerNoteValue` ersetzen durch
`/net/impulse/split/stagger/weight/{whole,half,quarter,eighth,sixteenth}`
(0..100, Defaults 0/0/10/30/60) und einen Punkt in der Liste der „Dinge, die
man beim Ändern kennen muss" ergaenzen: **ein Notenwert je Aufspaltung, nicht
je Zweig**, mit der Begruendung aus den Global Constraints. Ausserdem den Satz
zum Auslieferungszustand pruefen (weiterhin bitgleich, weil
`staggerEnabled = 0`).

- [ ] **Step 2: Test-Abschnitt nachziehen**

Der `SplitStaggerTest`-Eintrag in der Test-Liste bekommt die neue
Ziehung genannt (Verteilung, Gewicht 0, entarteter Fall → Sechzehntel).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: gewichteter Notenwert des Split-Versatzes"
```

---

## Self-Review

- **Spec coverage:** Java-Ziehung (Task 1), OSC-Parameter (Task 2), Web-UI
  (Task 3), Tests (Task 1 + Task 3), Doku (Task 4). Rueckwaertskompatibilitaet
  geprueft: nicht noetig (Global Constraints).
- **Placeholder scan:** Task 3 Step 8/9 beschreiben den JS-Umbau in Prosa statt
  als vollstaendigen Codeblock — bewusst, weil er eine fast woertliche Kopie
  der schon vorhandenen `buildSpeedClasses()`-Balkenlogik in derselben Datei
  ist und der ausformulierte Block den Plan verdoppelte, ohne eine Entscheidung
  zu tragen. Alle Entscheidungen (Beschriftung, Rueckfalltext, `set()` statt
  `input`) stehen ausgeschrieben.
- **Type consistency:** `SplitStagger.NOTE_COUNT`, `pickNoteValue`,
  `OriginSequencer.NOTE_VALUES`/`noteValueAt`, `SPLIT_STAGGER_NOTES`,
  `SPLIT_STAGGER_WEIGHT_PREFIX`, `split["staggerWeights"]` durchgaengig gleich
  benannt.
