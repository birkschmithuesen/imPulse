# Preset-System für imPulse und SuperCollider — Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Benannte Presets, die den kompletten Parametersatz von Visuals und Klang festhalten, speicherbar und ladbar über OSC, beim Start und über einen eingebauten Scheduler.

**Architecture:** Die prüfbare Logik liegt in zwei Processing- und OSC-freien Klassen (`PresetStore` für Format und Datei, `PresetScheduler` für die Zeitrechnung). Ein neues Interface `PresetTarget` lässt jeden `RemoteControlled*Parameter` seinen Wert absolut ausgeben und absolut übernehmen — der OSC-Weg wird für Werte bewusst nicht benutzt, weil er Floats von `0..1` auf `min..max` streckt. `PresetManager` klebt Befehle, Store und Parameter zusammen und leitet den Preset-Namen an SuperCollider weiter; imPulse ist Master, es gibt genau einen Scheduler.

**Tech Stack:** Java 8 (Processing 3 Sketch, `.java`-Dateien flach im Sketch-Ordner, Default-Package), oscP5/netP5, SuperCollider (sclang 3.x), Bash-Testtreiber ohne Testframework (`test/Check.java`).

**Spec:** `docs/superpowers/specs/2026-07-30-preset-system-design.md`

## Global Constraints

- **Wertänderungen nie über den OSC-Float-Weg.** `RemoteControlledFloatParameter.digestMessage()` mappt eingehende `f`-Werte von `0..1` auf `min..max` (`AbstractParameter.java:109`). Presets setzen Werte absolut über `applyPreset`.
- **Kein Preset-Eintrag für Kommandos.** `/net/activateNode` und `/net/activateStripe` feuern sofort beim Eintreffen. Sie dürfen weder gespeichert noch angewendet werden.
- **Threading:** Aus dem oscP5-Callback-Thread wird nichts verändert. `oscEvent()` ruft weiter nur `queueMessage()`. `digestMessage()` läuft im Draw-Thread, weil `distributeMessages()` aus `draw()` gerufen wird.
- **`PresetStore.java` und `PresetScheduler.java` importieren nichts aus `processing.*`, `oscP5.*` oder `netP5.*`.** Sie werden von `test/run.sh` übersetzt, das nur `core.jar` im Klassenpfad hat. `PresetManager.java` darf oscP5 kennen und kommt deshalb **nicht** in `test/run.sh`.
- **Dateiformat:** sechs tabgetrennte Spalten `typ`, `adresse`, `beschreibung`, `wert`, `min`, `max` — identisch zu `remoteSettings.txt`. Ein Eintrag ist im Code durchgehend ein `String[]` mit genau sechs Elementen in dieser Reihenfolge.
- **Beim Laden gelten die Grenzen aus dem Code**, nicht die aus den Spalten `min`/`max` der Datei.
- **Preset-Namen:** nur `[a-z0-9_-]`, Länge 1 bis 64. Der Name kommt über OSC von außen; ohne die Prüfung wäre `/preset/load ../../../etc/passwd` ein Dateizugriff nach Wunsch des Absenders.
- **Klassenname ≠ Dateiname.** Mehrere Klassen pro Datei sind hier Konvention (siehe `LedStripeNetworks.java`). `PresetTarget` und `PresetApplyReport` liegen mit in `PresetStore.java`.
- **Kein Merge nach `grabicz26`.** Alle Commits bleiben auf `feature/preset-system-v2` (diese Branch stammt direkt von `master`, dem Working-State-Branch — siehe `CLAUDE.md`, „Branch-Konvention"). Ein Merge nach `master` ist eine spätere, bewusste Entscheidung, kein Teil dieses Plans.
- **Nie gegen die Installation senden.** `test/TimingProbe`, `test/PollProbe`, `test/PatternProbe` sprechen echte Hardware an und werden in diesem Plan nicht aufgerufen.
- **Kommentare und Meldungen auf Deutsch, ohne Umlaute im Java-Quelltext** (bestehende Konvention: `Uebersetzung`, `naechste`). Markdown-Dateien dürfen Umlaute haben.

---

### Task 0: Werkzeugkette herstellen — ✅ bereits vorhanden (verifiziert 2026-07-30)

Auf dem Hermes-vServer bereits eingerichtet unter
`~/.hermes/impulse-toolchain/` (JDK 8u492 Temurin + Processing 3.5.4
core.jar). Vor jedem Arbeitsschritt exportieren:

```bash
export PATH="$HOME/.hermes/impulse-toolchain/jdk8u492-b09/bin:$PATH"
export IMPULSE_CORE_JAR="$HOME/.hermes/impulse-toolchain/processing-3.5.4/core/library/core.jar"
export IMPULSE_PROCESSING_JAVA="$HOME/.hermes/impulse-toolchain/processing-3.5.4"
```

Verifiziert: `test/run.sh` läuft komplett durch, alle Suiten grün (u.a.
`ArtNetOutputTest`, `LedPositionCalibrationTest`, `ImpulseOscThrottleTest`).
Die folgenden Steps 1–4 sind damit erledigt — bei Bedarf (neue Maschine,
Toolchain fehlt) als Fallback nutzen.

**Files:**
- Keine Repo-Änderung. Diese Aufgabe richtet nur die Umgebung ein.

**Interfaces:**
- Consumes: nichts
- Produces: ein lauffähiges `test/run.sh`. Alle folgenden Aufgaben verlassen sich darauf.

- [ ] **Step 1: Vorhandene Werkzeuge feststellen**

```bash
command -v javac java || echo "KEIN JDK"
echo "IMPULSE_CORE_JAR=${IMPULSE_CORE_JAR:-<nicht gesetzt>}"
ls -l "${IMPULSE_CORE_JAR:-/nonexistent}" 2>&1 | tail -1
```

Erwartet bei fehlender Kette: `KEIN JDK` und `No such file or directory`.

- [ ] **Step 2: JDK bereitstellen**

Mit root-Rechten:

```bash
sudo apt-get update && sudo apt-get install -y default-jdk
```

Ohne root-Rechte (Tarball ins Home, kein Systemeingriff):

```bash
mkdir -p ~/opt && cd ~/opt
curl -L -o jdk.tar.gz https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse
tar xf jdk.tar.gz
echo 'export PATH="$HOME/opt/'"$(ls -d ~/opt/jdk-* | head -1 | xargs basename)"'/bin:$PATH"' >> ~/.bashrc
```

Danach neue Shell öffnen oder `source ~/.bashrc`.

- [ ] **Step 3: core.jar besorgen**

Der Kommentar in `test/run.sh` nennt genau diesen Weg:

```bash
mkdir -p ~/lib
curl -o ~/lib/core.jar https://repo1.maven.org/maven2/org/processing/core/3.3.7/core-3.3.7.jar
export IMPULSE_CORE_JAR=~/lib/core.jar
echo 'export IMPULSE_CORE_JAR=$HOME/lib/core.jar' >> ~/.bashrc
```

- [ ] **Step 4: Bestehende Suite laufen lassen**

Run: `test/run.sh`
Expected: alle Suiten melden `alle bestanden`, Exit-Code 0. Damit ist die Kette belegt, **bevor** neuer Code dazukommt — ein Fehlschlag hier gehört nicht zu diesem Plan.

- [ ] **Step 5: Grenzen festhalten**

`test/build.sh` braucht zusätzlich eine Processing-3-Installation (`IMPULSE_PROCESSING_JAVA`). SuperCollider braucht `sclang`. Fehlt eines davon, sind die betroffenen Prüfschritte **nicht** ausführbar und dürfen nicht als bestanden gemeldet werden. Notiere, was auf diesem Rechner fehlt, und trage es beim Abschluss als offene Prüfung nach.

Kein Commit — diese Aufgabe ändert das Repo nicht.

---

### Task 1: PresetStore — Namensprüfung und Auflistung

**Files:**
- Create: `PresetStore.java`
- Create: `test/PresetStoreTest.java`
- Modify: `test/run.sh` (SOURCES und optionale Suitenliste)

**Interfaces:**
- Consumes: `test/Check.java` (`Check.eq`, `Check.that`, `Check.report`)
- Produces:
  - `class PresetStore`, Konstruktor `PresetStore(String directoryPath)`
  - `boolean isValidName(String name)`
  - `List<String> list()`
  - `String lastMessage()`
  - Konstanten `COL_TYPE=0`, `COL_ADDRESS=1`, `COL_DESCRIPTION=2`, `COL_VALUE=3`, `COL_MIN=4`, `COL_MAX=5`, `COLUMNS=6`, `MAX_NAME_LENGTH=64`

- [ ] **Step 1: Test schreiben**

Create `test/PresetStoreTest.java`:

```java
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.List;

public class PresetStoreTest {

  public static void main(String[] args) throws Exception {
    File dir = Files.createTempDirectory("presets").toFile();
    PresetStore store = new PresetStore(dir.getPath());

    // ---- Namensprueufung ----
    Check.that("einfacher Name", store.isValidName("standby"));
    Check.that("Unterstrich und Ziffern", store.isValidName("hang_drum_slow2"));
    Check.that("Bindestrich", store.isValidName("a-b"));

    Check.that("leerer Name abgewiesen", !store.isValidName(""));
    Check.that("null abgewiesen", !store.isValidName(null));
    Check.that("Grossbuchstabe abgewiesen", !store.isValidName("Standby"));
    Check.that("Punkt abgewiesen", !store.isValidName("stand.by"));
    Check.that("Schraegstrich abgewiesen", !store.isValidName("a/b"));
    Check.that("Pfadausbruch abgewiesen", !store.isValidName("../../etc/passwd"));
    Check.that("Leerzeichen abgewiesen", !store.isValidName("hang drum"));
    Check.that("Meldung nennt den Grund", store.lastMessage().length() > 0);

    StringBuilder tooLong = new StringBuilder();
    for (int i = 0; i < PresetStore.MAX_NAME_LENGTH + 1; i++) {
      tooLong.append('a');
    }
    Check.that("Ueberlaenge abgewiesen", !store.isValidName(tooLong.toString()));
    Check.that("Grenzlaenge angenommen",
        store.isValidName(tooLong.substring(0, PresetStore.MAX_NAME_LENGTH)));

    // ---- Auflistung ----
    Check.eq("leerer Ordner", 0, store.list().size());

    write(new File(dir, "standby.txt"), "float\t/master/level\t\t0.1\t0\t1\n");
    write(new File(dir, "ambient.txt"), "float\t/master/level\t\t0.2\t0\t1\n");
    write(new File(dir, "notizen.md"), "kein Preset\n");
    write(new File(dir, "Grossbuchstabe.txt"), "float\t/x\t\t0\t0\t1\n");

    List<String> names = store.list();
    Check.eq("nur die zwei gueltigen .txt-Dateien", 2, names.size());
    Check.eq("alphabetisch sortiert, erster", "ambient", names.get(0));
    Check.eq("alphabetisch sortiert, zweiter", "standby", names.get(1));

    PresetStore missing = new PresetStore(new File(dir, "gibtsnicht").getPath());
    Check.eq("fehlender Ordner ergibt leere Liste", 0, missing.list().size());
    Check.that("fehlender Ordner wird gemeldet", missing.lastMessage().length() > 0);

    System.exit(Check.report("PresetStoreTest"));
  }

  static void write(File file, String content) throws Exception {
    PrintWriter writer = new PrintWriter(file, "UTF-8");
    writer.print(content);
    writer.close();
  }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `test/run.sh PresetStoreTest`
Expected: FAIL beim Übersetzen mit `cannot find symbol   class PresetStore`.

- [ ] **Step 3: `PresetStore.java` anlegen**

Create `PresetStore.java`:

```java
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Format- und Dateischicht der Presets. Bewusst ohne Processing-, oscP5- und
// Netzabhaengigkeit, damit test/run.sh die Klasse mit nur core.jar im
// Klassenpfad uebersetzen kann - dieselbe Begruendung wie bei
// NodeCrossingStore und LedAnchorStore.
//
// Ein Eintrag ist durchgehend ein String[] mit genau sechs Elementen in der
// Reihenfolge der Datei. Es gibt keine zweite Darstellung.
class PresetStore {

  static final int COL_TYPE = 0;
  static final int COL_ADDRESS = 1;
  static final int COL_DESCRIPTION = 2;
  static final int COL_VALUE = 3;
  static final int COL_MIN = 4;
  static final int COL_MAX = 5;
  static final int COLUMNS = 6;

  static final int MAX_NAME_LENGTH = 64;

  private final File directory;
  private String message = "";

  PresetStore(String directoryPath) {
    directory = new File(directoryPath);
  }

  String lastMessage() { return message; }

  String directoryPath() { return directory.getPath(); }

  // Erlaubt nur dateisystemsichere Namen. Das ist keine Kosmetik: der Name
  // kommt ueber OSC von aussen, ohne diese Pruefung waere
  // "/preset/load ../../../etc/passwd" ein Dateizugriff nach Wunsch des
  // Absenders. Grossbuchstaben sind ausgeschlossen, damit sich zwei Presets
  // nicht nur durch Gross-/Kleinschreibung unterscheiden - auf Windows waere
  // das dieselbe Datei.
  boolean isValidName(String name) {
    if (name == null || name.length() == 0) {
      message = "Preset-Name ist leer";
      return false;
    }
    if (name.length() > MAX_NAME_LENGTH) {
      message = "Preset-Name laenger als " + MAX_NAME_LENGTH + " Zeichen";
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
      if (!allowed) {
        message = "Preset-Name enthaelt unzulaessiges Zeichen '" + c
            + "' - erlaubt sind a-z, 0-9, Unterstrich und Bindestrich";
        return false;
      }
    }
    return true;
  }

  // Namen aller Preset-Dateien, alphabetisch, ohne die Endung .txt. Dateien
  // mit unzulaessigem Namen werden uebergangen: sie liessen sich ohnehin nicht
  // laden, und der Scheduler wuerde sonst bei jedem Umlauf daran haengen.
  List<String> list() {
    List<String> names = new ArrayList<String>();
    File[] files = directory.listFiles();
    if (files == null) {
      message = "Preset-Ordner nicht lesbar: " + directory.getPath();
      return names;
    }
    for (int i = 0; i < files.length; i++) {
      File file = files[i];
      String fileName = file.getName();
      if (!file.isFile() || !fileName.endsWith(".txt")) {
        continue;
      }
      String bare = fileName.substring(0, fileName.length() - ".txt".length());
      if (quietlyValid(bare)) {
        names.add(bare);
      }
    }
    Collections.sort(names);
    message = names.size() + " Presets in " + directory.getPath();
    return names;
  }

  // Wie isValidName, aber ohne lastMessage() zu ueberschreiben - beim
  // Durchgehen eines Ordners soll die letzte uebergangene Datei nicht die
  // Meldung der Auflistung verdraengen.
  private boolean quietlyValid(String name) {
    String keep = message;
    boolean valid = isValidName(name);
    message = keep;
    return valid;
  }
}
```

- [ ] **Step 4: `PresetStore.java` in `test/run.sh` aufnehmen**

In `test/run.sh` nach der Zeile für `ImpulseOscThrottle.java` einfügen:

```bash
[ -f PresetStore.java ] && SOURCES="$SOURCES PresetStore.java"
```

Und in der Schleife der optionalen Suiten `PresetStoreTest` ergänzen, sodass die Liste lautet:

```bash
  for optional in NodeSelectionTest LedAnchorStoreTest LedPositionMapTest \
                  LedPositionCalibrationTest ImpulseOscThrottleTest \
                  PresetStoreTest; do
```

- [ ] **Step 5: Test laufen lassen**

Run: `test/run.sh PresetStoreTest`
Expected: `PresetStoreTest: <n> Pruefungen, alle bestanden`, Exit-Code 0. Die Zahl ist die, die die Suite meldet — entscheidend ist `alle bestanden` und Exit-Code 0.

- [ ] **Step 6: Gesamtsuite laufen lassen**

Run: `test/run.sh`
Expected: alle Suiten grün, `PresetStoreTest` in der Liste, keine `Hinweis: … fehlt`-Meldung für `PresetStoreTest`.

- [ ] **Step 7: Commit**

```bash
git add PresetStore.java test/PresetStoreTest.java test/run.sh
git commit -m "PresetStore: Namenspruefung und Auflistung des Preset-Ordners

Namen sind auf [a-z0-9_-] und 64 Zeichen begrenzt, weil sie ueber OSC von
aussen kommen - ohne die Pruefung waere ein Pfadausbruch moeglich."
```

---

### Task 2: PresetStore — Lesen und Schreiben

**Files:**
- Modify: `PresetStore.java`
- Modify: `test/PresetStoreTest.java`

**Interfaces:**
- Consumes: `PresetStore(String)`, `isValidName`, `lastMessage`, Spaltenkonstanten aus Task 1
- Produces:
  - `List<String[]> read(String name)` — Einträge oder `null` bei Fehler
  - `boolean write(String name, List<String[]> entries)`

- [ ] **Step 1: Test erweitern**

In `test/PresetStoreTest.java` vor der Zeile `System.exit(Check.report(...))` einfügen:

```java
    // ---- Lesen ----
    write(new File(dir, "gelesen.txt"),
        "float\t/master/level\tspace for descripiton\t0.42\t0.0\t1.0\n"
        + "int\t/net/impulse/speed\tspace for descripiton\t160\t1\t1500\n"
        + "\n"
        + "float\t/nodes/colors/central/fired/Hue\t\t1.0\t0\t1\n");

    List<String[]> entries = store.read("gelesen");
    Check.that("Datei gelesen", entries != null);
    Check.eq("Leerzeile uebergangen", 3, entries.size());
    Check.eq("Typ der ersten Zeile", "float", entries.get(0)[PresetStore.COL_TYPE]);
    Check.eq("Adresse der ersten Zeile", "/master/level", entries.get(0)[PresetStore.COL_ADDRESS]);
    Check.eq("Wert der ersten Zeile", "0.42", entries.get(0)[PresetStore.COL_VALUE]);
    Check.eq("leere Beschreibung bleibt leere Spalte", "",
        entries.get(2)[PresetStore.COL_DESCRIPTION]);
    Check.eq("jeder Eintrag hat sechs Spalten", PresetStore.COLUMNS, entries.get(1).length);

    Check.that("fehlende Datei ergibt null", store.read("gibtsnicht") == null);
    Check.that("fehlende Datei wird gemeldet",
        store.lastMessage().indexOf("nicht gefunden") >= 0);
    Check.that("unzulaessiger Name ergibt null", store.read("../geheim") == null);

    write(new File(dir, "kaputt.txt"), "float\t/master/level\t0.5\n");
    Check.that("zu wenige Spalten ergeben null", store.read("kaputt") == null);
    Check.that("Spaltenzahl wird gemeldet", store.lastMessage().indexOf("Spalten") >= 0);

    // ---- Schreiben ----
    List<String[]> unsorted = new ArrayList<String[]>();
    unsorted.add(new String[] { "float", "/zzz/last", "d", "1.0", "0", "1" });
    unsorted.add(new String[] { "int", "/aaa/first", "d", "7", "0", "10" });
    unsorted.add(new String[] { "float", "/mmm/middle", "d", "0.5", "0", "1" });

    Check.that("Schreiben gelingt", store.write("geschrieben", unsorted));
    List<String[]> readBack = store.read("geschrieben");
    Check.eq("alle drei Zeilen zurueck", 3, readBack.size());
    Check.eq("nach Adresse sortiert, erste", "/aaa/first", readBack.get(0)[PresetStore.COL_ADDRESS]);
    Check.eq("nach Adresse sortiert, zweite", "/mmm/middle", readBack.get(1)[PresetStore.COL_ADDRESS]);
    Check.eq("nach Adresse sortiert, dritte", "/zzz/last", readBack.get(2)[PresetStore.COL_ADDRESS]);
    Check.eq("Wert unveraendert", "7", readBack.get(0)[PresetStore.COL_VALUE]);

    // Zweimal speichern darf nichts anhaengen - dieselbe Anforderung wie bei
    // NodeCrossingStore, wo genau das der Fehler war.
    Check.that("zweites Schreiben gelingt", store.write("geschrieben", unsorted));
    Check.eq("zweimal speichern verdoppelt nichts", 3, store.read("geschrieben").size());

    Check.that("unzulaessiger Name wird nicht geschrieben", !store.write("../boese", unsorted));
    Check.that("null-Eintraege werden abgewiesen", !store.write("leer", null));

    // Schreiben in einen noch nicht existierenden Ordner legt ihn an.
    PresetStore fresh = new PresetStore(new File(dir, "neuerOrdner").getPath());
    Check.that("Ordner wird angelegt", fresh.write("erstes", unsorted));
    Check.eq("und ist danach lesbar", 1, fresh.list().size());

    // Nach dem Schreiben darf keine Temp-Datei zurueckbleiben.
    File[] leftovers = new File(dir, "neuerOrdner").listFiles();
    boolean tempLeft = false;
    for (int i = 0; i < leftovers.length; i++) {
      if (leftovers[i].getName().endsWith(".tmp")) {
        tempLeft = true;
      }
    }
    Check.that("keine Temp-Datei zurueckgeblieben", !tempLeft);
```

Am Kopf der Datei die Importe ergänzen:

```java
import java.util.ArrayList;
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `test/run.sh PresetStoreTest`
Expected: FAIL beim Übersetzen mit `cannot find symbol   method read(String)`.

- [ ] **Step 3: `read` und `write` implementieren**

In `PresetStore.java` die Importe ergänzen:

```java
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
```

Und vor der schliessenden Klammer der Klasse einfügen:

```java
  private File fileFor(String name) {
    return new File(directory, name + ".txt");
  }

  // Liest alle Eintraege einer Preset-Datei. null heisst Fehler, der Grund
  // steht in lastMessage(). Die Spalten Beschreibung, min und max werden
  // mitgefuehrt, beim Anwenden aber ignoriert - dort gelten die Grenzen aus
  // dem laufenden Code, damit aeltere Presets nach einer Bereichsaenderung
  // korrekt bleiben.
  List<String[]> read(String name) {
    if (!isValidName(name)) {
      return null;
    }
    File file = fileFor(name);
    if (!file.isFile()) {
      message = "Preset nicht gefunden: " + file.getPath();
      return null;
    }
    List<String[]> entries = new ArrayList<String[]>();
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new FileReader(file));
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.trim().length() == 0) {
          continue;
        }
        // -1 haelt leere Spalten in der Mitte, etwa eine leere Beschreibung
        String[] columns = line.split("\t", -1);
        if (columns.length < COLUMNS) {
          message = "Zeile " + lineNumber + " in " + file.getName() + " hat "
              + columns.length + " statt " + COLUMNS + " Spalten";
          return null;
        }
        String[] entry = new String[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) {
          entry[i] = columns[i];
        }
        entries.add(entry);
      }
    } catch (IOException e) {
      message = "Preset nicht lesbar: " + e;
      return null;
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignored) {
          // beim Lesen unerheblich
        }
      }
    }
    message = entries.size() + " Zeilen aus " + file.getName() + " gelesen";
    return entries;
  }

  // Schreibt die Eintraege atomar: erst in eine Temp-Datei, dann Rename.
  // Kein Anhaengen - mehrfaches Speichern verdoppelt nichts. Sortiert wird
  // nach Adresse, weil die Registry ein HashSet ist: ohne Sortierung sieht
  // jedes Speichern im git diff wie eine komplette Umschreibung aus.
  boolean write(String name, List<String[]> entries) {
    if (!isValidName(name)) {
      return false;
    }
    if (entries == null) {
      message = "Keine Eintraege zum Speichern";
      return false;
    }
    if (!directory.isDirectory() && !directory.mkdirs()) {
      message = "Preset-Ordner nicht anlegbar: " + directory.getPath();
      return false;
    }
    List<String[]> sorted = new ArrayList<String[]>(entries);
    Collections.sort(sorted, new Comparator<String[]>() {
      public int compare(String[] a, String[] b) {
        return a[COL_ADDRESS].compareTo(b[COL_ADDRESS]);
      }
    });
    File target = fileFor(name);
    File temp = new File(directory, name + ".txt.tmp");
    PrintWriter writer = null;
    try {
      writer = new PrintWriter(temp, "UTF-8");
      for (int i = 0; i < sorted.size(); i++) {
        String[] entry = sorted.get(i);
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < COLUMNS; c++) {
          if (c > 0) {
            line.append('\t');
          }
          line.append(c < entry.length ? entry[c] : "");
        }
        // Bewusst '\n' statt println: das Format soll auf Windows und macOS
        // byte-gleich sein, damit git diff nicht ganze Dateien zeigt.
        writer.print(line.toString());
        writer.print('\n');
      }
      writer.flush();
    } catch (IOException e) {
      message = "Preset nicht schreibbar: " + e;
      return false;
    } finally {
      if (writer != null) {
        writer.close();
      }
    }
    // renameTo ist auf Windows nur auf ein nicht existierendes Ziel
    // verlaesslich, deshalb vorher loeschen.
    if (target.exists() && !target.delete()) {
      message = "Altes Preset nicht ersetzbar: " + target.getPath();
      return false;
    }
    if (!temp.renameTo(target)) {
      message = "Umbenennen fehlgeschlagen: " + temp.getPath();
      return false;
    }
    message = sorted.size() + " Zeilen nach " + target.getName() + " geschrieben";
    return true;
  }
```

- [ ] **Step 4: Test laufen lassen**

Run: `test/run.sh PresetStoreTest`
Expected: `PresetStoreTest: <n> Pruefungen, alle bestanden`, Exit-Code 0.

- [ ] **Step 5: Commit**

```bash
git add PresetStore.java test/PresetStoreTest.java
git commit -m "PresetStore: Preset-Dateien lesen und atomar schreiben

Format ist das bestehende remoteSettings.txt-Format mit sechs Tab-Spalten.
Geschrieben wird nach Adresse sortiert, weil die Parameter-Registry ein
HashSet ist und eine unsortierte Ausgabe jedes Speichern im git diff wie
eine Umschreibung aussehen liesse."
```

---

### Task 3: PresetTarget, Snapshot, Anwenden, Bericht

**Files:**
- Modify: `PresetStore.java` (Interface `PresetTarget`, Klasse `PresetApplyReport`, statische Methoden)
- Modify: `test/PresetStoreTest.java`

**Interfaces:**
- Consumes: alles aus Task 1 und 2
- Produces:
  - `interface PresetTarget` mit `void presetEntries(List<String[]> out)` und `int applyPreset(String address, float value)`
  - Konstanten `PresetStore.PRESET_NOT_MINE = 0`, `PresetStore.PRESET_APPLIED = 1`, `PresetStore.PRESET_ADJUSTED = 2`
  - `class PresetApplyReport` mit `List<String> unknown`, `List<String> missing`, `List<String> unparsable`, `List<String> adjusted`, `int applied`, `boolean clean()`, `String summary()`
  - `static float PresetStore.clampToRange(float value, float min, float max)`
  - `static List<String[]> PresetStore.snapshot(List<PresetTarget> targets)`
  - `static PresetApplyReport PresetStore.apply(List<String[]> entries, List<PresetTarget> targets)`
  - Konstanten `PresetStore.SILENTLY_IGNORED`, `PresetStore.EXCLUDED`

Warum `applyPreset` einen `float` bekommt und keinen `String`: das Zerlegen des Textes gehört in die geprüfte Schicht. So wird ein unlesbarer Wert dort erkannt, wo er testbar ist, und die Parameterklassen bleiben frei von Textverarbeitung. Der SC-Typ `ints` (kommagetrennte Tonleiter) existiert deshalb nur in SuperCollider-Preset-Dateien und nie in `data/presets/`.

Warum `applyPreset` einen `int` zurückgibt und kein `boolean`: die Spec verlangt, dass ein Wert ausserhalb der Code-Grenzen nicht nur geklemmt, sondern auch **gemeldet** wird. Nur der Parameter selbst kennt seine Grenzen, also muss er den Unterschied melden können. Ein Statuswert kostet dafür nichts — die Alternative wäre eine dritte Interface-Methode, die die Grenzen nach draussen gibt.

Die Ausschlussliste liegt in `PresetStore` statt in `PresetManager` — abweichend von der Spec, aber aus gutem Grund: nur so ist sie von der Testsuite abgedeckt.

- [ ] **Step 1: Test erweitern**

In `test/PresetStoreTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- Klemmung ----
    Check.near("innerhalb bleibt unveraendert", 0.5, PresetStore.clampToRange(0.5f, 0f, 1f), 1e-6);
    Check.near("oberhalb wird geklemmt", 1.0, PresetStore.clampToRange(5.0f, 0f, 1f), 1e-6);
    Check.near("unterhalb wird geklemmt", 0.1, PresetStore.clampToRange(-3f, 0.1f, 1f), 1e-6);

    // ---- Snapshot ----
    List<PresetTarget> targets = new ArrayList<PresetTarget>();
    FakeParameter level = new FakeParameter("float", "/master/level", 0.1f, 0f, 1f);
    FakeParameter speed = new FakeParameter("int", "/net/impulse/speed", 160f, 1f, 1500f);
    FakeParameter schedulerOn =
        new FakeParameter("int", "/preset/scheduler/enabled", 1f, 0f, 1f);
    targets.add(level);
    targets.add(speed);
    targets.add(schedulerOn);

    List<String[]> snapshot = PresetStore.snapshot(targets);
    Check.eq("Scheduler-Parameter nicht im Snapshot", 2, snapshot.size());
    boolean schedulerFound = false;
    for (int i = 0; i < snapshot.size(); i++) {
      if (snapshot.get(i)[PresetStore.COL_ADDRESS].startsWith("/preset/scheduler/")) {
        schedulerFound = true;
      }
    }
    Check.that("keine Scheduler-Adresse im Snapshot", !schedulerFound);

    // ---- Anwenden ----
    List<String[]> toApply = new ArrayList<String[]>();
    toApply.add(new String[] { "float", "/master/level", "d", "0.7", "0", "1" });
    toApply.add(new String[] { "int", "/net/impulse/speed", "d", "42", "1", "1500" });

    PresetApplyReport report = PresetStore.apply(toApply, targets);
    Check.eq("zwei Parameter gesetzt", 2, report.applied);
    Check.near("Float uebernommen", 0.7, level.value, 1e-6);
    Check.near("Int uebernommen", 42.0, speed.value, 1e-6);
    Check.that("Bericht ohne Auffaelligkeiten", report.clean());

    // Klemmung auf die Grenzen aus dem Code, NICHT auf die Spalten der Datei.
    List<String[]> tooHigh = new ArrayList<String[]>();
    tooHigh.add(new String[] { "float", "/master/level", "d", "5.0", "0", "99" });
    PresetApplyReport clampReport = PresetStore.apply(tooHigh, targets);
    Check.near("Wert auf die Code-Grenze geklemmt, nicht auf die Dateigrenze",
        1.0, level.value, 1e-6);
    // Geklemmt UND gemeldet: eine stille Klemmung wuerde verbergen, dass ein
    // Preset Werte aus einem frueheren, weiteren Bereich mitbringt.
    Check.eq("Klemmung wird gemeldet", 1, clampReport.adjusted.size());
    Check.that("Meldung nennt die Adresse",
        clampReport.adjusted.get(0).indexOf("/master/level") >= 0);
    Check.eq("trotzdem als gesetzt gezaehlt", 1, clampReport.applied);
    Check.that("und nicht als unbekannt gemeldet", clampReport.unknown.isEmpty());
    Check.that("Bericht gilt nicht als sauber", !clampReport.clean());

    // Kommando-Adressen: still uebergehen, nicht melden.
    List<String[]> withCommands = new ArrayList<String[]>();
    withCommands.add(new String[] { "int", "/net/activateNode", "d", "3", "0", "76" });
    withCommands.add(new String[] { "int", "/net/activateStripe", "d", "5", "0", "29" });
    withCommands.add(new String[] { "float", "/master/level", "d", "0.3", "0", "1" });
    PresetApplyReport commandReport = PresetStore.apply(withCommands, targets);
    Check.eq("nur der echte Parameter gezaehlt", 1, commandReport.applied);
    Check.eq("Kommandos nicht als unbekannt gemeldet", 0, commandReport.unknown.size());

    // Unbekannte Adresse: melden, uebrige Zeilen trotzdem anwenden.
    List<String[]> withUnknown = new ArrayList<String[]>();
    withUnknown.add(new String[] { "float", "/gibt/es/nicht", "d", "0.5", "0", "1" });
    withUnknown.add(new String[] { "float", "/master/level", "d", "0.25", "0", "1" });
    PresetApplyReport unknownReport = PresetStore.apply(withUnknown, targets);
    Check.eq("eine unbekannte Adresse gemeldet", 1, unknownReport.unknown.size());
    Check.eq("Adresse benannt", "/gibt/es/nicht", unknownReport.unknown.get(0));
    Check.near("die andere Zeile wurde trotzdem angewendet", 0.25, level.value, 1e-6);
    Check.that("Bericht nennt die unbekannte Adresse",
        unknownReport.summary().indexOf("/gibt/es/nicht") >= 0);

    // Registrierte Adresse fehlt in der Datei.
    List<String[]> incomplete = new ArrayList<String[]>();
    incomplete.add(new String[] { "float", "/master/level", "d", "0.2", "0", "1" });
    PresetApplyReport incompleteReport = PresetStore.apply(incomplete, targets);
    Check.eq("eine fehlende Adresse gemeldet", 1, incompleteReport.missing.size());
    Check.eq("fehlende Adresse benannt", "/net/impulse/speed",
        incompleteReport.missing.get(0));
    Check.that("Scheduler-Adresse gilt nicht als fehlend",
        incompleteReport.missing.indexOf("/preset/scheduler/enabled") < 0);

    // Unlesbarer Wert.
    List<String[]> broken = new ArrayList<String[]>();
    broken.add(new String[] { "float", "/master/level", "d", "keine Zahl", "0", "1" });
    float before = level.value;
    PresetApplyReport brokenReport = PresetStore.apply(broken, targets);
    Check.eq("unlesbarer Wert gemeldet", 1, brokenReport.unparsable.size());
    Check.near("Wert unveraendert geblieben", before, level.value, 1e-6);
    Check.that("nicht zusaetzlich als unbekannt gemeldet", brokenReport.unknown.isEmpty());
```

Und am Ende der Datei, nach der `write`-Hilfsmethode, das Fake-Ziel:

```java
  // Steht fuer einen RemoteControlled*Parameter. Die echten Klassen liegen in
  // AbstractParameter.java und importieren oscP5, das der Testsuite nicht zur
  // Verfuegung steht (test/run.sh hat nur core.jar). Die Klemmung nutzt
  // absichtlich dieselbe Hilfsmethode wie die echten Klassen, damit hier
  // nicht eine zweite Rechnung geprueft wird.
  static class FakeParameter implements PresetTarget {
    final String type;
    final String address;
    final float min;
    final float max;
    float value;

    FakeParameter(String type, String address, float value, float min, float max) {
      this.type = type;
      this.address = address;
      this.value = value;
      this.min = min;
      this.max = max;
    }

    public void presetEntries(List<String[]> out) {
      out.add(new String[] { type, address, "space for descripiton",
          String.valueOf(value), String.valueOf(min), String.valueOf(max) });
    }

    public int applyPreset(String wantedAddress, float newValue) {
      if (!address.equals(wantedAddress)) {
        return PresetStore.PRESET_NOT_MINE;
      }
      value = PresetStore.clampToRange(newValue, min, max);
      return (value == newValue) ? PresetStore.PRESET_APPLIED : PresetStore.PRESET_ADJUSTED;
    }
  }
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `test/run.sh PresetStoreTest`
Expected: FAIL beim Übersetzen mit `cannot find symbol   class PresetTarget`.

- [ ] **Step 3: Interface, Bericht und statische Methoden implementieren**

In `PresetStore.java` die Importe ergänzen:

```java
import java.util.HashSet;
import java.util.Set;
```

Am Kopf der Datei, **vor** `class PresetStore`, einfügen:

```java
// Was ein Parameter koennen muss, um in einem Preset zu landen. Bewusst hier
// und nicht in AbstractParameter.java: diese Datei bleibt frei von oscP5 und
// Processing, damit die Testsuite sie uebersetzen kann. Die drei
// RemoteControlled*Parameter-Klassen implementieren das Interface.
//
// LedNetworkTransportEffect implementiert es ausdruecklich NICHT: seine zwei
// Adressen /net/activateNode und /net/activateStripe sind Kommandos, die beim
// Eintreffen sofort feuern. Sie koennen so per Konstruktion nicht in ein
// Preset geraten.
interface PresetTarget {

  // Fuegt je Adresse eine Zeile {typ, adresse, beschreibung, wert, min, max}
  // an. Ein Farbparameter liefert drei Zeilen (/Hue, /Sat, /Bright).
  void presetEntries(List<String[]> out);

  // Setzt den Wert absolut und geklemmt auf die Grenzen dieses Parameters.
  // Rueckgabe: PresetStore.PRESET_NOT_MINE, PRESET_APPLIED oder
  // PRESET_ADJUSTED - letzteres, wenn der uebergebene Wert dabei veraendert
  // werden musste (geklemmt oder gerundet). Nur der Parameter selbst kennt
  // seine Grenzen, also kann nur er das melden.
  //
  // Der Wert kommt als float und nicht als String, weil das Zerlegen des
  // Textes in die geprüfte Schicht gehoert. Und er wird absolut gesetzt und
  // nicht durch digestMessage geschickt: dort wird ein eingehender Float von
  // 0..1 auf min..max gestreckt, ein gespeicherter Absolutwert wuerde dabei
  // verfaelscht.
  int applyPreset(String address, float value);
}

// Was beim Anwenden eines Presets auffiel. Bewusst gesammelt statt je Zeile
// gemeldet: bei 49 Adressen waere Zeile-fuer-Zeile-Ausgabe unlesbar.
class PresetApplyReport {

  final List<String> unknown = new ArrayList<String>();
  final List<String> missing = new ArrayList<String>();
  final List<String> unparsable = new ArrayList<String>();
  final List<String> adjusted = new ArrayList<String>();
  int applied = 0;

  boolean clean() {
    return unknown.isEmpty() && missing.isEmpty() && unparsable.isEmpty() && adjusted.isEmpty();
  }

  String summary() {
    StringBuilder text = new StringBuilder();
    text.append(applied).append(" Parameter gesetzt");
    if (!unknown.isEmpty()) {
      text.append("; unbekannte Adressen: ").append(join(unknown));
    }
    if (!missing.isEmpty()) {
      text.append("; nicht im Preset enthalten: ").append(join(missing));
    }
    if (!unparsable.isEmpty()) {
      text.append("; unlesbare Werte: ").append(join(unparsable));
    }
    if (!adjusted.isEmpty()) {
      text.append("; auf den zulaessigen Bereich angepasst: ").append(join(adjusted));
    }
    return text.toString();
  }

  private static String join(List<String> items) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        text.append(", ");
      }
      text.append(items.get(i));
    }
    return text.toString();
  }
}
```

In `class PresetStore` vor der schliessenden Klammer einfügen:

```java
  // Kommandos, die in remoteSettings.txt stehen, aber keine Parameter sind:
  // LedNetworkTransportEffect feuert bei diesen Adressen sofort. Beim Laden
  // still uebergehen statt melden, damit eine handkopierte
  // remoteSettings.txt nicht bei jedem Laden zwei Warnungen erzeugt.
  static final String[] SILENTLY_IGNORED = {
      "/net/activateNode",
      "/net/activateStripe"
  };

  // Transport, nicht Inhalt. Diese beiden sind echte Parameter und wuerden
  // sonst mitwandern - ein versehentlich mit enabled=0 gespeichertes Preset
  // wuerde die Installation einfrieren.
  static final String[] EXCLUDED = {
      "/preset/scheduler/enabled",
      "/preset/scheduler/interval"
  };

  // Rueckgabewerte von PresetTarget.applyPreset
  static final int PRESET_NOT_MINE = 0;
  static final int PRESET_APPLIED = 1;
  static final int PRESET_ADJUSTED = 2;

  static float clampToRange(float value, float min, float max) {
    if (value < min) {
      return min;
    }
    if (value > max) {
      return max;
    }
    return value;
  }

  private static boolean contains(String[] list, String address) {
    for (int i = 0; i < list.length; i++) {
      if (list[i].equals(address)) {
        return true;
      }
    }
    return false;
  }

  // Kompletter Wertesatz aller Ziele, ohne die ausgeschlossenen Adressen.
  static List<String[]> snapshot(List<PresetTarget> targets) {
    List<String[]> all = new ArrayList<String[]>();
    for (int i = 0; i < targets.size(); i++) {
      targets.get(i).presetEntries(all);
    }
    List<String[]> kept = new ArrayList<String[]>();
    for (int i = 0; i < all.size(); i++) {
      if (!contains(EXCLUDED, all.get(i)[COL_ADDRESS])) {
        kept.add(all.get(i));
      }
    }
    return kept;
  }

  // Wendet die Eintraege auf die Ziele an. Verandert nur, was zugeordnet
  // werden kann; alles Auffaellige steht im Bericht. Ein defektes Preset darf
  // die Show nicht anhalten, deshalb bricht diese Methode nie ab.
  static PresetApplyReport apply(List<String[]> entries, List<PresetTarget> targets) {
    PresetApplyReport report = new PresetApplyReport();
    Set<String> seen = new HashSet<String>();
    for (int i = 0; i < entries.size(); i++) {
      String[] entry = entries.get(i);
      String address = entry[COL_ADDRESS];
      if (contains(SILENTLY_IGNORED, address) || contains(EXCLUDED, address)) {
        continue;
      }
      // Vor dem Zerlegen vermerken: eine Zeile mit unlesbarem Wert war da und
      // soll nicht zusaetzlich als fehlend gemeldet werden.
      seen.add(address);
      float value;
      try {
        value = Float.parseFloat(entry[COL_VALUE].trim());
      } catch (NumberFormatException e) {
        report.unparsable.add(address + " (\"" + entry[COL_VALUE] + "\")");
        continue;
      }
      int status = PRESET_NOT_MINE;
      for (int t = 0; t < targets.size() && status == PRESET_NOT_MINE; t++) {
        status = targets.get(t).applyPreset(address, value);
      }
      if (status == PRESET_NOT_MINE) {
        report.unknown.add(address);
      } else {
        report.applied++;
        if (status == PRESET_ADJUSTED) {
          report.adjusted.add(address + " (\"" + entry[COL_VALUE] + "\")");
        }
      }
    }
    List<String[]> current = new ArrayList<String[]>();
    for (int i = 0; i < targets.size(); i++) {
      targets.get(i).presetEntries(current);
    }
    for (int i = 0; i < current.size(); i++) {
      String address = current.get(i)[COL_ADDRESS];
      if (contains(EXCLUDED, address)) {
        continue;
      }
      if (!seen.contains(address) && report.missing.indexOf(address) < 0) {
        report.missing.add(address);
      }
    }
    Collections.sort(report.unknown);
    Collections.sort(report.missing);
    Collections.sort(report.unparsable);
    Collections.sort(report.adjusted);
    return report;
  }
```

- [ ] **Step 4: Test laufen lassen**

Run: `test/run.sh PresetStoreTest`
Expected: `PresetStoreTest: <n> Pruefungen, alle bestanden`, Exit-Code 0.

- [ ] **Step 5: Gesamtsuite laufen lassen**

Run: `test/run.sh`
Expected: alle Suiten grün.

- [ ] **Step 6: Commit**

```bash
git add PresetStore.java test/PresetStoreTest.java
git commit -m "PresetStore: Snapshot, Anwenden und Bericht ueber PresetTarget

Werte werden absolut gesetzt und auf die Grenzen aus dem Code geklemmt, nicht
auf die aus der Datei. Kommando-Adressen werden still uebergangen, die zwei
Scheduler-Parameter ganz ausgeschlossen - ein Preset soll den Auto-Changer
nicht abschalten koennen."
```

---

### Task 4: PresetScheduler

**Files:**
- Create: `PresetScheduler.java`
- Create: `test/PresetSchedulerTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Consumes: nichts aus früheren Tasks
- Produces:
  - `class PresetScheduler`, Konstruktor ohne Argumente
  - `String current()`
  - `void noteLoaded(String name, long nowMillis)`
  - `boolean isDue(long nowMillis, boolean enabled, float intervalSeconds)`
  - `String advance(long nowMillis, List<String> names)`

Warum `isDue` und `advance` getrennt sind: `draw()` läuft mit 40 Hz. Würde der Scheduler die Namensliste selbst holen, würde er den Preset-Ordner 40-mal pro Sekunde auflisten. So wird nur dann gelistet, wenn tatsächlich gewechselt wird.

- [ ] **Step 1: Test schreiben**

Create `test/PresetSchedulerTest.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class PresetSchedulerTest {

  static final float INTERVAL = 600f;          // Sekunden
  static final long INTERVAL_MS = 600L * 1000L;

  public static void main(String[] args) {
    List<String> names = list("ambient", "hang_drum_slow", "standby");

    // ---- Aus heisst kein Wechsel ----
    PresetScheduler off = new PresetScheduler();
    Check.that("aus: nicht faellig bei t=0", !off.isDue(0L, false, INTERVAL));
    Check.that("aus: nicht faellig weit nach dem Intervall",
        !off.isDue(INTERVAL_MS * 10, false, INTERVAL));

    // Ausgeschaltet zieht der Timer mit: ein spaeteres Einschalten darf nicht
    // sofort umschalten, weil waehrend der Aus-Zeit ein Intervall verstrich.
    PresetScheduler late = new PresetScheduler();
    Check.that("aus, spaeter Zeitpunkt", !late.isDue(INTERVAL_MS * 5, false, INTERVAL));
    Check.that("direkt nach dem Einschalten nicht faellig",
        !late.isDue(INTERVAL_MS * 5, true, INTERVAL));
    Check.that("kurz danach noch nicht faellig",
        !late.isDue(INTERVAL_MS * 5 + 1000L, true, INTERVAL));
    Check.that("erst nach einem vollen Intervall faellig",
        late.isDue(INTERVAL_MS * 6, true, INTERVAL));

    // ---- Einschalten springt nicht sofort ----
    PresetScheduler s = new PresetScheduler();
    Check.that("erster Aufruf mit an: nicht faellig", !s.isDue(1000L, true, INTERVAL));
    Check.that("kurz danach: nicht faellig", !s.isDue(2000L, true, INTERVAL));
    Check.that("genau nach dem Intervall: faellig",
        s.isDue(1000L + INTERVAL_MS, true, INTERVAL));

    // ---- Weiterschalten in alphabetischer Reihenfolge mit Umlauf ----
    PresetScheduler r = new PresetScheduler();
    Check.that("kein aktuelles Preset am Anfang", r.current() == null);
    Check.eq("erster Wechsel nimmt das erste Preset", "ambient",
        r.advance(0L, names));
    Check.eq("current wird mitgefuehrt", "ambient", r.current());
    Check.eq("zweiter Wechsel", "hang_drum_slow", r.advance(1000L, names));
    Check.eq("dritter Wechsel", "standby", r.advance(2000L, names));
    Check.eq("Umlauf zurueck auf den ersten", "ambient", r.advance(3000L, names));

    // advance setzt den Timer zurueck
    Check.that("nach advance nicht sofort wieder faellig",
        !r.isDue(3000L + 1000L, true, INTERVAL));
    Check.that("nach einem Intervall wieder faellig",
        r.isDue(3000L + INTERVAL_MS, true, INTERVAL));

    // ---- Position wird ueber den Namen gefuehrt ----
    PresetScheduler byName = new PresetScheduler();
    byName.noteLoaded("hang_drum_slow", 0L);
    // "aaa_neu" kommt alphabetisch VOR dem aktuellen Preset dazu. Wuerde die
    // Position ueber einen Index laufen, sprang der Scheduler jetzt zurueck.
    List<String> grown = list("aaa_neu", "ambient", "hang_drum_slow", "standby");
    Check.eq("neue Datei davor verschiebt die Position nicht", "standby",
        byName.advance(1000L, grown));

    PresetScheduler shrunk = new PresetScheduler();
    shrunk.noteLoaded("hang_drum_slow", 0L);
    // Das aktuelle Preset ist verschwunden: kein Absturz, wieder von vorn.
    Check.eq("verschwundenes Preset faengt wieder vorn an", "ambient",
        shrunk.advance(1000L, list("ambient", "standby")));

    // ---- Leere Liste ----
    PresetScheduler empty = new PresetScheduler();
    Check.that("leere Liste ergibt null", empty.advance(0L, new ArrayList<String>()) == null);
    Check.that("null-Liste ergibt null", empty.advance(0L, null) == null);
    // Auch bei leerer Liste muss der Timer zurueckgesetzt werden, sonst
    // meldet isDue in jedem Frame erneut "faellig".
    Check.that("Timer trotzdem zurueckgesetzt", !empty.isDue(1000L, true, INTERVAL));

    // ---- Defektes Preset haengt nicht fest ----
    // advance setzt current auf den zurueckgegebenen Namen, BEVOR der Aufrufer
    // zu laden versucht. Scheitert das Laden, geht der naechste Ablauf weiter.
    PresetScheduler broken = new PresetScheduler();
    Check.eq("erster Versuch", "ambient", broken.advance(0L, names));
    Check.eq("naechster Ablauf geht weiter, obwohl nie geladen wurde",
        "hang_drum_slow", broken.advance(INTERVAL_MS, names));

    // ---- noteLoaded setzt Position und Timer ----
    PresetScheduler noted = new PresetScheduler();
    noted.noteLoaded("standby", 5000L);
    Check.eq("noteLoaded setzt current", "standby", noted.current());
    Check.that("noteLoaded setzt den Timer",
        !noted.isDue(5000L + 1000L, true, INTERVAL));
    Check.that("und nach einem Intervall ist wieder faellig",
        noted.isDue(5000L + INTERVAL_MS, true, INTERVAL));

    // ---- kurzes Intervall ----
    PresetScheduler quick = new PresetScheduler();
    quick.noteLoaded("ambient", 0L);
    Check.that("5-Sekunden-Intervall: bei 4.9s nicht faellig",
        !quick.isDue(4900L, true, 5f));
    Check.that("5-Sekunden-Intervall: bei 5.0s faellig",
        quick.isDue(5000L, true, 5f));

    System.exit(Check.report("PresetSchedulerTest"));
  }

  static List<String> list(String... items) {
    List<String> result = new ArrayList<String>();
    for (int i = 0; i < items.length; i++) {
      result.add(items[i]);
    }
    return result;
  }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `test/run.sh PresetSchedulerTest`
Expected: FAIL beim Übersetzen mit `cannot find symbol   class PresetScheduler`.

- [ ] **Step 3: `PresetScheduler.java` anlegen**

Create `PresetScheduler.java`:

```java
import java.util.List;

// Zeitlogik des Preset-Wechslers. Ohne Processing, ohne Thread und ohne
// eigene Wanduhr: die Zeit wird hineingegeben, damit die Klasse ohne
// Sketch-Laufzeit prueufbar ist. Gerufen wird sie aus draw().
//
// isDue() und advance() sind getrennt, weil draw() mit 40 Hz laeuft: wuerde
// der Scheduler die Namensliste selbst holen, listete er den Preset-Ordner
// 40-mal pro Sekunde auf.
class PresetScheduler {

  // Position wird ueber den NAMEN gefuehrt, nicht ueber einen Index. Kommt
  // eine Preset-Datei dazu oder faellt eine weg, verrutscht die Reihenfolge
  // sonst mitten in der Show.
  private String current = null;
  private long lastSwitchMillis = 0L;
  private boolean started = false;

  String current() {
    return current;
  }

  // Vom Start-Preset und von jedem erfolgreichen /preset/load zu rufen, damit
  // der Scheduler weiss, wo er steht und ab wann sein Intervall laeuft.
  void noteLoaded(String name, long nowMillis) {
    current = name;
    lastSwitchMillis = nowMillis;
    started = true;
  }

  // true, wenn jetzt gewechselt werden soll. Veraendert current nicht - der
  // Aufrufer holt danach die Namensliste und ruft advance().
  boolean isDue(long nowMillis, boolean enabled, float intervalSeconds) {
    if (!enabled) {
      // Timer mitziehen: sonst waere nach einer langen Aus-Phase beim
      // Einschalten sofort ein Intervall verstrichen und es wuerde mitten in
      // der laufenden Szene hart umgeschaltet.
      lastSwitchMillis = nowMillis;
      return false;
    }
    if (!started) {
      // Erstes Einschalten: Timer ab jetzt, kein Sprung.
      started = true;
      lastSwitchMillis = nowMillis;
      return false;
    }
    long intervalMillis = (long) (intervalSeconds * 1000f);
    return nowMillis - lastSwitchMillis >= intervalMillis;
  }

  // Schaltet auf das naechste Preset und gibt dessen Namen zurueck, oder null
  // wenn es keines gibt. Setzt den Timer in jedem Fall zurueck, auch bei
  // leerer Liste - sonst meldete isDue() in jedem Frame erneut "faellig".
  //
  // current wird gesetzt, BEVOR der Aufrufer zu laden versucht. Scheitert das
  // Laden, steht die Position auf dem defekten Eintrag und der naechste
  // Ablauf geht zum folgenden weiter. Der Scheduler haengt nicht fest.
  String advance(long nowMillis, List<String> names) {
    started = true;
    lastSwitchMillis = nowMillis;
    if (names == null || names.isEmpty()) {
      return null;
    }
    int index = (current == null) ? -1 : names.indexOf(current);
    current = names.get((index + 1) % names.size());
    return current;
  }
}
```

- [ ] **Step 4: `PresetScheduler.java` in `test/run.sh` aufnehmen**

Nach der `PresetStore.java`-Zeile ergänzen:

```bash
[ -f PresetScheduler.java ] && SOURCES="$SOURCES PresetScheduler.java"
```

Und `PresetSchedulerTest` in die Liste der optionalen Suiten aufnehmen:

```bash
  for optional in NodeSelectionTest LedAnchorStoreTest LedPositionMapTest \
                  LedPositionCalibrationTest ImpulseOscThrottleTest \
                  PresetStoreTest PresetSchedulerTest; do
```

- [ ] **Step 5: Test laufen lassen**

Run: `test/run.sh PresetSchedulerTest`
Expected: `PresetSchedulerTest: <n> Pruefungen, alle bestanden`, Exit-Code 0.

- [ ] **Step 6: Gesamtsuite laufen lassen**

Run: `test/run.sh`
Expected: alle Suiten grün, beide neuen Suiten in der Liste.

- [ ] **Step 7: Commit**

```bash
git add PresetScheduler.java test/PresetSchedulerTest.java test/run.sh
git commit -m "PresetScheduler: Zeitlogik des Preset-Wechslers

Position laeuft ueber den Namen, nicht ueber einen Index - eine neu
dazugekommene Datei soll die Reihenfolge nicht mitten in der Show
verschieben. Einschalten springt nicht sofort, und ein defektes Preset
haengt den Wechsler nicht fest."
```

---

### Task 5: PresetTarget an den drei Parameterklassen

**Files:**
- Modify: `AbstractParameter.java`

**Interfaces:**
- Consumes: `PresetTarget`, `PresetStore.clampToRange` (Task 3)
- Produces:
  - `OscMessageDistributor.registerPresetTarget(PresetTarget)`
  - `OscMessageDistributor.presetTargets()` → `ArrayList<PresetTarget>`
  - `RemoteControlledFloatParameter`, `RemoteControlledIntParameter`, `RemoteControlledColorParameter` implementieren `PresetTarget`

Diese Aufgabe hat keinen Unit-Test: `AbstractParameter.java` importiert oscP5 und lässt sich in `test/run.sh` nicht übersetzen. Der Nachweis ist die Übersetzungsprüfung des ganzen Sketches.

- [ ] **Step 1: Registry in `OscMessageDistributor` ergänzen**

In `AbstractParameter.java` in `class OscMessageDistributor` nach dem `allInstances`-Feld einfügen:

```java
	// Reihenfolge-erhaltende Liste ausschliesslich fuer Presets. Fuer die
	// Verteilung der Nachrichten ist die Reihenfolge gleichgueltig, deshalb ist
	// allInstances ein HashSet. Fuer Preset-Dateien ist sie nicht gleichgueltig
	// - PresetStore.write() sortiert am Ende nach Adresse, aber eine
	// wiederholbare Aufzaehlung macht die Fehlersuche erheblich einfacher.
	//
	// Nur die drei RemoteControlled*Parameter-Klassen tragen sich hier ein.
	// LedNetworkTransportEffect ist ein OscMessageSink, aber kein
	// PresetTarget: seine Adressen /net/activateNode und /net/activateStripe
	// sind Kommandos und koennen so nicht in ein Preset geraten.
	static ArrayList<PresetTarget> presetTargets = new ArrayList<PresetTarget>();

	public static void registerPresetTarget(PresetTarget _target) {
		presetTargets.add(_target);
	}

	public static ArrayList<PresetTarget> presetTargets() {
		return presetTargets;
	}
```

- [ ] **Step 2: `RemoteControlledFloatParameter` erweitern**

Klassenkopf ändern von

```java
class RemoteControlledFloatParameter extends FloatParameter implements OscMessageSink { //
```

zu

```java
class RemoteControlledFloatParameter extends FloatParameter implements OscMessageSink, PresetTarget { //
```

Im Konstruktor nach `OscMessageDistributor.registerAdress(_oscAdress, this);` einfügen:

```java
		OscMessageDistributor.registerPresetTarget(this);
```

Und vor der schliessenden Klammer der Klasse einfügen:

```java
	// ---- Preset-Schnittstelle ----
	// Bewusst nicht ueber digestMessage: dort wird ein eingehender Float von
	// 0..1 auf min..max gestreckt (siehe oben). Ein gespeicherter Absolutwert
	// wuerde dabei verfaelscht - /net/impulse/nodeDeadTime (0..10) landete bei
	// gespeichertem 1.0 als 10.0.
	public void presetEntries(List<String[]> out) {
		out.add(new String[] { "float", oscAdress, "space for descripiton",
				String.valueOf(getValue()), String.valueOf(minValue), String.valueOf(maxValue) });
	}

	public int applyPreset(String address, float value) {
		if (!oscAdress.equals(address)) {
			return PresetStore.PRESET_NOT_MINE;
		}
		float clamped = PresetStore.clampToRange(value, minValue, maxValue);
		setValue(clamped);
		return (clamped == value) ? PresetStore.PRESET_APPLIED : PresetStore.PRESET_ADJUSTED;
	}
```

- [ ] **Step 3: `RemoteControlledIntParameter` erweitern**

Klassenkopf ändern zu

```java
class RemoteControlledIntParameter extends IntParameter implements OscMessageSink, PresetTarget { //
```

Im Konstruktor nach `OscMessageDistributor.registerAdress(_oscAdress, this);` einfügen:

```java
		OscMessageDistributor.registerPresetTarget(this);
```

Und vor der schliessenden Klammer der Klasse einfügen:

```java
	// ---- Preset-Schnittstelle ----
	public void presetEntries(List<String[]> out) {
		out.add(new String[] { "int", oscAdress, "space for descripiton",
				String.valueOf(getValue()), String.valueOf(minValue), String.valueOf(maxValue) });
	}

	public int applyPreset(String address, float value) {
		if (!oscAdress.equals(address)) {
			return PresetStore.PRESET_NOT_MINE;
		}
		// Runden statt abschneiden. Der OSC-Weg oben schneidet ab, was bei
		// exakt geschriebenen Preset-Werten gleichgueltig ist - bei einem von
		// Hand editierten 4.999 aber nicht.
		float rounded = Math.round(value);
		float clamped = PresetStore.clampToRange(rounded, minValue, maxValue);
		setValue((int) clamped);
		// Verglichen wird mit dem urspruenglichen Wert, nicht mit dem
		// gerundeten: auch eine Rundung ist eine Anpassung und soll auffallen.
		return (clamped == value) ? PresetStore.PRESET_APPLIED : PresetStore.PRESET_ADJUSTED;
	}
```

- [ ] **Step 4: `RemoteControlledColorParameter` erweitern**

Klassenkopf ändern zu

```java
class RemoteControlledColorParameter extends ColorParameter implements OscMessageSink, PresetTarget { //
```

Im Konstruktor nach den drei `registerAdress`-Aufrufen einfügen:

```java
		OscMessageDistributor.registerPresetTarget(this);
```

Und vor der schliessenden Klammer der Klasse einfügen:

```java
	// ---- Preset-Schnittstelle ----
	// Ein Farbparameter belegt drei Adressen und liefert deshalb drei Zeilen.
	// min/max stehen als "0" und "1" da, genau wie in writeToStream() - so
	// bleibt eine Preset-Datei zeilenweise mit remoteSettings.txt vergleichbar.
	public void presetEntries(List<String[]> out) {
		out.add(new String[] { "float", oscAdress + "/Hue", "space for descripiton",
				String.valueOf(currentHue), "0", "1" });
		out.add(new String[] { "float", oscAdress + "/Sat", "space for descripiton",
				String.valueOf(currentSaturation), "0", "1" });
		out.add(new String[] { "float", oscAdress + "/Bright", "space for descripiton",
				String.valueOf(currentBrightness), "0", "1" });
	}

	public int applyPreset(String address, float value) {
		float clamped = PresetStore.clampToRange(value, 0f, 1f);
		int status = (clamped == value) ? PresetStore.PRESET_APPLIED : PresetStore.PRESET_ADJUSTED;
		if (address.equals(oscAdress + "/Hue")) {
			setHue(clamped);
			return status;
		}
		if (address.equals(oscAdress + "/Sat")) {
			setSaturation(clamped);
			return status;
		}
		if (address.equals(oscAdress + "/Bright")) {
			setBrightness(clamped);
			return status;
		}
		return PresetStore.PRESET_NOT_MINE;
	}
```

- [ ] **Step 5: Übersetzung prüfen**

Run: `test/build.sh`
Expected: Exit-Code 0, keine Fehlermeldung. `AbstractParameter.java` importiert bereits `java.util.*`, `List` ist also verfügbar.

Falls `test/build.sh` auf diesem Rechner nicht läuft (fehlendes Processing), ersatzweise prüfen, dass die Suite weiter grün ist, und die fehlende Übersetzungsprüfung als offen notieren:

Run: `test/run.sh`
Expected: alle Suiten grün.

- [ ] **Step 6: Commit**

```bash
git add AbstractParameter.java
git commit -m "Parameter tragen die Preset-Schnittstelle

Die drei RemoteControlled*-Klassen geben ihre Werte absolut aus und nehmen
sie absolut an, geklemmt auf die Grenzen aus dem Code. Der OSC-Weg bleibt
dafuer unbenutzt, weil er Floats von 0..1 auf min..max streckt.

Eine geordnete Registry kommt neben das bestehende HashSet - nur die drei
Parameterklassen tragen sich ein, die Kommando-Adressen des Transport-Effekts
damit nicht."
```

---

### Task 6: PresetManager

**Files:**
- Create: `PresetManager.java`

**Interfaces:**
- Consumes: `PresetStore`, `PresetApplyReport`, `PresetScheduler`, `OscMessageDistributor.presetTargets()`, `OscMessageSink`
- Produces:
  - `PresetManager(String presetDirectory, OscP5 oscP5, NetAddress soundTarget)`
  - `void update(long nowMillis, boolean schedulerEnabled, float schedulerIntervalSeconds)`
  - `boolean load(String name, long nowMillis)`
  - `boolean save(String name)`
  - `void loadBootPreset(String name, long nowMillis)`

Diese Klasse kennt oscP5 und kommt **nicht** in `test/run.sh`. Würde sie dort aufgenommen, bräche die Suite mit `package oscP5 does not exist`.

- [ ] **Step 1: `PresetManager.java` anlegen**

Create `PresetManager.java`:

```java
import java.io.DataOutputStream;
import java.util.List;

import netP5.NetAddress;
import oscP5.OscMessage;
import oscP5.OscP5;

// Klebeschicht zwischen den OSC-Befehlen, dem PresetStore, den Parametern und
// SuperCollider. Die pruefbare Logik liegt bewusst nicht hier, sondern in
// PresetStore und PresetScheduler - diese Klasse importiert oscP5 und ist
// deshalb NICHT Teil von test/run.sh.
//
// imPulse ist Master: es gibt genau einen Scheduler, und der laeuft hier.
// Bei jedem Wechsel geht zusaetzlich /sc/preset/load an SuperCollider, damit
// Licht und Klang nicht auseinanderlaufen.
class PresetManager implements OscMessageSink {

	private final PresetStore store;
	private final PresetScheduler scheduler = new PresetScheduler();
	private final OscP5 oscP5;
	private final NetAddress soundTarget;

	// digestMessage() laeuft im Draw-Thread, weil distributeMessages() aus
	// draw() gerufen wird - eine Synchronisierung braucht es hier also nicht.
	// Gemerkt statt sofort ausgefuehrt wird trotzdem, damit das Lesen einer
	// Datei nicht mitten in der Verteilschleife passiert.
	private String pendingLoad = null;
	private String pendingSave = null;
	private boolean pendingNext = false;

	PresetManager(String presetDirectory, OscP5 _oscP5, NetAddress _soundTarget) {
		store = new PresetStore(presetDirectory);
		oscP5 = _oscP5;
		soundTarget = _soundTarget;
		OscMessageDistributor.registerAdress("/preset/load", this);
		OscMessageDistributor.registerAdress("/preset/save", this);
		OscMessageDistributor.registerAdress("/preset/next", this);
		System.out.println("Preset-Ordner: " + store.directoryPath());
	}

	public void digestMessage(OscMessage newMessage) {
		if (newMessage.checkAddrPattern("/preset/next")) {
			pendingNext = true;
			return;
		}
		if (newMessage.checkAddrPattern("/preset/load") && newMessage.arguments().length > 0) {
			pendingLoad = newMessage.get(0).stringValue();
			return;
		}
		if (newMessage.checkAddrPattern("/preset/save") && newMessage.arguments().length > 0) {
			pendingSave = newMessage.get(0).stringValue();
		}
	}

	// Dieser Sink haelt keine Parameter. Ohne das leere writeToStream bekaeme
	// remoteSettings.txt Kommando-Zeilen dazu - genau der Fehler, den das
	// Preset-System an anderer Stelle vermeidet.
	public void writeToStream(DataOutputStream outStream) {
	}

	// Aus draw() zu rufen, direkt nach OscMessageDistributor.distributeMessages().
	// Pro Frame wird nur der jeweils letzte Befehl ausgefuehrt: zwei Loads im
	// selben Frame sind ein Bedienfehler, kein Wunsch.
	void update(long nowMillis, boolean schedulerEnabled, float schedulerIntervalSeconds) {
		if (pendingSave != null) {
			String name = pendingSave;
			pendingSave = null;
			save(name);
		}
		if (pendingLoad != null) {
			String name = pendingLoad;
			pendingLoad = null;
			load(name, nowMillis);
		}
		if (pendingNext) {
			pendingNext = false;
			switchToNext(nowMillis);
		} else if (scheduler.isDue(nowMillis, schedulerEnabled, schedulerIntervalSeconds)) {
			switchToNext(nowMillis);
		}
	}

	// Aus setup() zu rufen, nachdem alle Effekte angelegt sind - vorher sind
	// die Parameter noch nicht registriert.
	void loadBootPreset(String name, long nowMillis) {
		if (name == null || name.trim().length() == 0) {
			return;
		}
		System.out.println("Start-Preset: " + name.trim());
		load(name.trim(), nowMillis);
	}

	boolean load(String name, long nowMillis) {
		List<String[]> entries = store.read(name);
		if (entries == null) {
			// Ein defektes Preset darf die Show nicht anhalten: alle Werte
			// bleiben stehen.
			System.out.println("Preset laden fehlgeschlagen: " + store.lastMessage());
			return false;
		}
		PresetApplyReport report = PresetStore.apply(entries, OscMessageDistributor.presetTargets());
		scheduler.noteLoaded(name, nowMillis);
		System.out.println("Preset \"" + name + "\" geladen: " + report.summary());
		forwardToSound(name);
		return true;
	}

	boolean save(String name) {
		List<String[]> entries = PresetStore.snapshot(OscMessageDistributor.presetTargets());
		if (!store.write(name, entries)) {
			System.out.println("Preset speichern fehlgeschlagen: " + store.lastMessage());
			return false;
		}
		System.out.println("Preset \"" + name + "\" gespeichert: " + store.lastMessage());
		return true;
	}

	private void switchToNext(long nowMillis) {
		String name = scheduler.advance(nowMillis, store.list());
		if (name == null) {
			System.out.println("Preset-Wechsel nicht moeglich: " + store.lastMessage());
			return;
		}
		load(name, nowMillis);
	}

	// Fire-and-forget: imPulse wartet auf keine Antwort. Laeuft sclang nicht,
	// laeuft die Visual-Show trotzdem weiter.
	private void forwardToSound(String name) {
		if (oscP5 == null || soundTarget == null) {
			return;
		}
		OscMessage message = new OscMessage("/sc/preset/load");
		message.add(name);
		oscP5.send(message, soundTarget);
	}
}
```

- [ ] **Step 2: Nachweisen, dass die Suite unberührt bleibt**

Run: `test/run.sh`
Expected: alle Suiten grün. `PresetManager.java` ist **nicht** in `SOURCES` und wird deshalb nicht übersetzt — das ist beabsichtigt.

- [ ] **Step 3: Prüfen, dass `PresetManager.java` nicht in `test/run.sh` steht**

Run: `grep -c PresetManager test/run.sh`
Expected: `0`

- [ ] **Step 4: Commit**

```bash
git add PresetManager.java
git commit -m "PresetManager: Befehle, Scheduler-Tick und Weiterleitung an SC

Der Befehl laeuft durch die bestehende Queue und wird dort nur vermerkt;
gelesen und angewendet wird in draw(). Ein fehlgeschlagenes Laden laesst alle
Werte stehen und haengt den Wechsler nicht fest.

Nicht in test/run.sh: die Klasse importiert oscP5, das der Suite nicht zur
Verfuegung steht."
```

---

### Task 7: imPulse.pde verdrahten

**Files:**
- Modify: `imPulse.pde`

**Interfaces:**
- Consumes: `PresetManager`, `RemoteControlledIntParameter`, `RemoteControlledFloatParameter`
- Produces: die zwei Scheduler-Parameter `/preset/scheduler/enabled` und `/preset/scheduler/interval` in `remoteSettings.txt`

- [ ] **Step 1: Felder ergänzen**

In `imPulse.pde` nach der Zeile

```java
NodeCalibration nodeCalibration;
boolean calibrationMode = false;
```

einfügen:

```java
// Preset-System. Der Scheduler laeuft in imPulse (Master) und leitet den
// Namen an SuperCollider weiter, damit Licht und Klang nicht auseinanderlaufen.
PresetManager presetManager;
// Transport, nicht Inhalt: diese zwei Parameter sind bewusst von jedem Preset
// ausgeschlossen (siehe PresetStore.EXCLUDED), sonst koennte ein Preset den
// Auto-Changer abschalten und die Installation einfrieren.
RemoteControlledIntParameter presetSchedulerEnabled;
RemoteControlledFloatParameter presetSchedulerInterval;
```

- [ ] **Step 2: In `setup()` anlegen und Start-Preset laden**

In `setup()` **nach** den beiden `mixer.addEffect(...)`-Zeilen und **vor** dem `try`-Block, der `remoteSettings.txt` schreibt, einfügen:

```java
  // Default 0: das Start-Preset (Weg 2) bestimmt die Szene, ein automatisch
  // mitlaufender Wechsler wuerde sie nach zehn Minuten wegnehmen.
  presetSchedulerEnabled = new RemoteControlledIntParameter("/preset/scheduler/enabled", 0, 0, 1);
  presetSchedulerInterval = new RemoteControlledFloatParameter("/preset/scheduler/interval", 600f, 5f, 3600f);
  presetManager = new PresetManager(dataPath("presets"), oscP5, oscOutput);

  // Start-Preset: Sketch-Argument hat Vorrang, sonst die Umgebungsvariable.
  // Beide Wege, weil processing-java die Weitergabe von Argumenten nicht
  // zusichert, eine Umgebungsvariable aus einer .bat dagegen immer geht.
  //
  // Der Aufruf steht bewusst hier: nach dem Anlegen aller Effekte, sonst sind
  // die Parameter noch nicht registriert - und vor dem Schreiben von
  // remoteSettings.txt, damit diese Datei danach den wirklich gefahrenen Stand
  // zeigt statt der Code-Defaults.
  String bootPreset = (args != null && args.length > 0) ? args[0] : System.getenv("IMPULSE_PRESET");
  presetManager.loadBootPreset(bootPreset, System.currentTimeMillis());
```

- [ ] **Step 3: In `draw()` ticken**

In `draw()` direkt nach

```java
  OscMessageDistributor.distributeMessages();
```

einfügen:

```java
  presetManager.update(System.currentTimeMillis(),
      presetSchedulerEnabled.getValue() != 0,
      presetSchedulerInterval.getValue());
```

- [ ] **Step 4: Übersetzung prüfen**

Run: `test/build.sh`
Expected: Exit-Code 0.

Falls Processing auf diesem Rechner fehlt, ersatzweise `test/run.sh` (muss grün bleiben) und die offene Übersetzungsprüfung notieren.

- [ ] **Step 5: Commit**

```bash
git add imPulse.pde
git commit -m "Preset-System in den Sketch einhaengen

Start-Preset aus Sketch-Argument oder IMPULSE_PRESET, geladen nach dem
Anlegen der Effekte und vor dem Schreiben von remoteSettings.txt - danach
zeigt die Datei den gefahrenen Stand statt der Code-Defaults.

Scheduler-Parameter starten auf aus: das Start-Preset bestimmt die Szene."
```

---

### Task 8: Startbestand in data/presets

**Files:**
- Create: `data/presets/hang_drum_slow.txt`
- Create: `data/presets/standby.txt`
- Modify: `scenes/hang_drum_slow/README.md`
- Modify: `scenes/standby/README.md`

**Interfaces:**
- Consumes: das Dateiformat aus Task 2
- Produces: zwei ladbare Presets, damit das System ab dem ersten Start nicht leer ist

- [ ] **Step 1: Snapshots kopieren, Kommandozeilen entfernen, nach Adresse sortieren**

```bash
mkdir -p data/presets
for scene in hang_drum_slow standby; do
  grep -v -e $'\t/net/activateNode\t' -e $'\t/net/activateStripe\t' \
    "scenes/$scene/remoteSettings.txt" \
    | sort -t$'\t' -k2,2 > "data/presets/$scene.txt"
done
```

- [ ] **Step 2: Ergebnis prüfen**

```bash
wc -l data/presets/*.txt
grep -c activate data/presets/*.txt || echo "keine Kommandozeilen - richtig"
cut -f2 data/presets/standby.txt | head -3
awk -F'\t' 'NF != 6 { print FILENAME": Zeile "NR" hat "NF" Spalten" }' data/presets/*.txt \
  || echo "alle Zeilen sechs Spalten"
```

Expected: je `49 data/presets/hang_drum_slow.txt` und `49 data/presets/standby.txt`; `keine Kommandozeilen - richtig`; die ersten Adressen beginnen mit `Master/`; keine Spaltenwarnung.

- [ ] **Step 3: Unterschied zwischen den zwei Presets belegen**

```bash
diff <(cut -f2,4 data/presets/hang_drum_slow.txt) <(cut -f2,4 data/presets/standby.txt)
```

Expected: genau fünf Unterschiede — `/net/randomSpawn/enabled` (1 gegen 0) und die vier Node-Hue-Adressen (rot gegen grün-zyan), wie in `scenes/standby/README.md` beschrieben. Weicht das ab, sind die Snapshots nicht die erwarteten und Task 8 stoppt hier.

- [ ] **Step 4: Reload-Hinweis in den zwei scenes-READMEs richtigstellen**

In `scenes/hang_drum_slow/README.md` den Absatz, der mit `**Hinweis:** Dies ist ein reiner Werte-Snapshot` beginnt, ersetzen durch:

```markdown
**Hinweis:** Dies war ursprünglich ein reiner Werte-Snapshot ohne Ladefunktion.
Seit dem Preset-System (2026-07-30) liegt derselbe Wertesatz als ladbares
Preset unter `data/presets/hang_drum_slow.txt` — abzurufen per
`/preset/load hang_drum_slow`, per `IMPULSE_PRESET=hang_drum_slow` beim Start
oder über den Scheduler. Die Datei hier bleibt als Herkunftsbeleg liegen: sie
ist die 1:1-Kopie des live gezogenen Standes, das Preset ist die daraus
abgeleitete, um die zwei Kommandozeilen bereinigte und nach Adresse sortierte
Fassung.
```

In `scenes/standby/README.md` den Absatz, der mit `**Reload-Hinweis:**` beginnt, ersetzen durch:

```markdown
**Reload-Hinweis:** wie bei Hang Drum Slow — derselbe Wertesatz liegt seit dem
Preset-System (2026-07-30) als ladbares Preset unter
`data/presets/standby.txt`. Reaktivieren also per `/preset/load standby`
statt per erneutem Senden aller Einzelwerte.
```

- [ ] **Step 5: Commit**

```bash
git add data/presets scenes/hang_drum_slow/README.md scenes/standby/README.md
git commit -m "Startbestand: die zwei Szenen-Snapshots als ladbare Presets

Abgeleitet aus scenes/*/remoteSettings.txt, um die zwei Kommandozeilen
bereinigt und nach Adresse sortiert. Damit ist das System ab dem ersten Start
nicht leer und die zwei live verifizierten Szenen sind erstmals wirklich
abrufbar statt nur dokumentiert."
```

---

### Task 9: SuperCollider — Klangparameter steuerbar machen

**Files:**
- Modify: `supercollider/klangnetz_bells.scd`

**Interfaces:**
- Consumes: nichts aus den Java-Tasks
- Produces: `~decayScale`, `~tilt`, `~recalcScale`, `~clip`, die SynthDef-Argumente `decayScale` und `tilt`, sowie die sieben Klang-OSC-Adressen

**Constraint für diese und die nächste Aufgabe:** Alles bleibt in dem **einen** bestehenden `(...)`-Block. Mehrere Top-Level-Blöcke in derselben Datei hängen `sclang -D` auf (beobachtet 2026-07-29, Kommentar am Kopf der Datei). Der bestehende `/net/hitNode`-`OSCFunc` wird nicht angefasst.

- [ ] **Step 1: Neue Konfigurationswerte ergänzen**

In `supercollider/klangnetz_bells.scd` nach

```supercollider
~minAmp = 0.08;
~maxAmp = 0.5;
```

einfügen:

```supercollider
// Zwei globale Klangregler, seit dem Preset-System (2026-07-30) per OSC
// steuerbar. Sie decken die Achse ab, die "hang drum slow" von "bells"
// unterscheidet, ohne die Teilton-Literale in der SynthDef antasten zu muessen:
//   decayScale streckt oder kuerzt ALLE Teilton-Decays gemeinsam
//   tilt ist ein Exponent auf die Teilton-Amps: >1 dumpfer, <1 brillanter
// Weil partialAmps[0] gleich 1.0 ist, bleibt der Grundton bei jedem tilt
// gleich laut - tilt regelt nur, wieviel von den oberen Teiltoenen uebrig
// bleibt.
~decayScale = 1.0;
~tilt = 1.0;

// Ordner der Preset-Dateien. nowExecutingPath ist nil, wenn der Block aus dem
// Editor heraus ohne gespeicherte Datei laeuft - dann relativ zum
// Arbeitsverzeichnis.
~presetDir = if (thisProcess.nowExecutingPath.notNil) {
    thisProcess.nowExecutingPath.dirname ++ "/presets"
} {
    "presets"
};

// Grenzen wie in imPulse: der Wert wird geklemmt, nicht abgewiesen.
~clip = { |value, low, high| value.max(low).min(high) };

// notesPerOctaveSet haengt von Tonleiter UND Oktavzahl ab und muss nach jeder
// Aenderung an beiden neu gerechnet werden - sonst greift die Node-auf-Ton-
// Abbildung ins Leere.
~recalcScale = { ~notesPerOctaveSet = ~pentatonicSteps.size * ~numOctaves; };
```

Und die bestehende Zeile

```supercollider
~notesPerOctaveSet = ~pentatonicSteps.size * ~numOctaves;
```

unverändert stehen lassen — sie bleibt die Erstberechnung.

- [ ] **Step 2: SynthDef um die zwei Argumente erweitern**

Die Signaturzeile ändern von

```supercollider
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0, pan = 0|
```

zu

```supercollider
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0, pan = 0,
                             decayScale = 1.0, tilt = 1.0|
```

Innerhalb der `Mix.fill`-Funktion die zwei Zuweisungen ändern von

```supercollider
            pAmp   = partialAmps[i];
            pDecay = partialDecays[i];
```

zu

```supercollider
            // Binaeroperator-Form, damit sclang die Zahl zum UGen befoerdert:
            // partialAmps[i] ist ein Float, tilt ein Control. Float.pow(einUGen)
            // ist dafuer nicht verlaesslich.
            pAmp   = partialAmps[i] ** tilt;
            pDecay = partialDecays[i] * decayScale;
```

- [ ] **Step 3: Die zwei Werte beim Ton mitgeben**

Die Zeile

```supercollider
        Synth(\glockenBell, [\freq, freq, \amp, amp, \pan, pan]);
```

ändern zu

```supercollider
        Synth(\glockenBell, [\freq, freq, \amp, amp, \pan, pan,
            \decayScale, ~decayScale, \tilt, ~tilt]);
```

- [ ] **Step 4: Die sieben Klang-Adressen als OSCFuncs ergänzen**

**Nach** dem bestehenden `~netHitNodeOscFunc`-Block und **vor** der abschliessenden `"KlangNetz Glockenspiel: …".postln;`-Zeile einfügen:

```supercollider
    // ---- Parameter-Steuerung per OSC ---------------------------------
    // Bewusst derselbe Port wie /net/hitNode: imPulse sendet schon dorthin
    // (oscOutput in imPulse.pde). Der bestehende Listener wird nicht
    // angefasst, es kommen nur neue Adressen dazu - und alles bleibt in
    // DIESEM einen (...)-Block, weil mehrere Top-Level-Bloecke sclang -D
    // aufhaengen.
    ~paramOscFuncs.do({ |func| func.free });   // Re-Evaluieren beim Live-Coding
    ~paramOscFuncs = [
        OSCFunc({ |msg|
            var steps = msg[1..].collect({ |value| value.asInteger });
            if (steps.size > 0) {
                ~pentatonicSteps = steps;
                ~recalcScale.value;
                ("scale/steps -> %".format(~pentatonicSteps)).postln;
            } {
                "scale/steps ohne Werte - ignoriert".postln;
            };
        }, '/sc/scale/steps', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~rootMidiNote = ~clip.value(msg[1].asInteger, 24, 96);
            ("scale/rootMidi -> %".format(~rootMidiNote)).postln;
        }, '/sc/scale/rootMidi', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~numOctaves = ~clip.value(msg[1].asInteger, 1, 6);
            ~recalcScale.value;
            ("scale/octaves -> %".format(~numOctaves)).postln;
        }, '/sc/scale/octaves', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~minAmp = ~clip.value(msg[1].asFloat, 0.0, 1.0);
            ("amp/min -> %".format(~minAmp)).postln;
        }, '/sc/amp/min', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~maxAmp = ~clip.value(msg[1].asFloat, 0.0, 1.0);
            ("amp/max -> %".format(~maxAmp)).postln;
        }, '/sc/amp/max', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~decayScale = ~clip.value(msg[1].asFloat, 0.1, 4.0);
            ("bell/decayScale -> %".format(~decayScale)).postln;
        }, '/sc/bell/decayScale', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~tilt = ~clip.value(msg[1].asFloat, 0.3, 3.0);
            ("bell/tilt -> %".format(~tilt)).postln;
        }, '/sc/bell/tilt', recvPort: ~oscListenPort)
    ];
```

- [ ] **Step 5: Am Gerät prüfen**

Diese Aufgabe hat keinen automatisierten Nachweis — im Repo gibt es kein SC-Testgerüst. Prüfung von Hand auf einem Rechner mit `sclang`:

```bash
sclang supercollider/klangnetz_bells.scd
```

Erwartet auf der Konsole: `KlangNetz Glockenspiel: Server gebootet, hoere auf /net/hitNode Port 8002 - bereit.` und **kein** Hängenbleiben des Parsers.

Danach in einem zweiten sclang oder per Python:

```bash
python3 - <<'PY'
import socket, struct
def msg(addr, *args):
    def pad(b): return b + b'\0' * (4 - len(b) % 4)
    out = pad(addr.encode() + b'\0')
    tags = ',' + ''.join('f' if isinstance(a, float) else 'i' for a in args)
    out += pad(tags.encode() + b'\0')
    for a in args:
        out += struct.pack('>f' if isinstance(a, float) else '>i', a)
    return out
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.sendto(msg('/sc/bell/decayScale', 3.5), ('127.0.0.1', 8002))
s.sendto(msg('/sc/bell/tilt', 2.0), ('127.0.0.1', 8002))
s.sendto(msg('/sc/scale/rootMidi', 48), ('127.0.0.1', 8002))
s.sendto(msg('/net/hitNode', 3, 1.0), ('127.0.0.1', 8002))
PY
```

Erwartet: die drei Parametermeldungen erscheinen, danach eine `node 3 -> … Hz`-Zeile, und der Ton klingt hörbar länger und dumpfer als vorher. Ausserdem: `/net/hitNode` funktioniert weiter — der bestehende Listener ist unberührt.

Ist auf diesem Rechner kein `sclang` vorhanden, wird die Prüfung als offen notiert und **nicht** als bestanden gemeldet.

- [ ] **Step 6: Commit**

```bash
git add supercollider/klangnetz_bells.scd
git commit -m "SuperCollider: Klangparameter per OSC steuerbar

Tonleiter, Grundton, Oktavzahl und Amp-Bereich sowie zwei neue globale
Klangregler decayScale und tilt. Die Teilton-Literale bleiben stehen, kein
SynthDef-Rebuild und keine Array-Argumente.

Neue Adressen auf dem bestehenden Port 8002, alles im selben (...)-Block -
der /net/hitNode-Listener bleibt unberuehrt und die Parser-Falle bei
sclang -D wird nicht beruehrt."
```

---

### Task 10: SuperCollider — Presets lesen, schreiben, entgegennehmen

**Files:**
- Modify: `supercollider/klangnetz_bells.scd`
- Create: `supercollider/presets/hang_drum_slow.txt`
- Create: `supercollider/presets/standby.txt`

**Interfaces:**
- Consumes: `~presetDir`, `~clip`, `~recalcScale`, `~decayScale`, `~tilt` (Task 9)
- Produces: `~presetNameValid`, `~presetValues`, `~presetApply`, `~presetSave`, `~presetLoad`, die zwei Adressen `/sc/preset/save` und `/sc/preset/load`

- [ ] **Step 1: Preset-Funktionen ergänzen**

In `supercollider/klangnetz_bells.scd` **vor** dem `~paramOscFuncs`-Block aus Task 9 einfügen:

```supercollider
    // ---- Preset-Dateien -----------------------------------------------
    // Format wie in imPulse: sechs tabgetrennte Spalten
    //   typ, adresse, beschreibung, wert, min, max
    // Ein Format ueber beide Haelften. Die Tonleiter ist ein Array und kein
    // Skalar - sie steht als Typ "ints" kommagetrennt in der Wertspalte, das
    // ist die einzige Formaterweiterung gegenueber imPulse.
    //
    // Reihenfolge alphabetisch nach Adresse, genau wie PresetStore.write() in
    // imPulse sortiert.
    ~presetNameValid = { |name|
        name.isString
            and: { name.size > 0 }
            and: { name.size <= 64 }
            and: { name.every({ |char| "abcdefghijklmnopqrstuvwxyz0123456789_-".includes(char) }) };
    };

    ~presetValues = {
        [
            ["float", "/sc/amp/max",         ~maxAmp.asString,               "0",   "1"],
            ["float", "/sc/amp/min",         ~minAmp.asString,               "0",   "1"],
            ["float", "/sc/bell/decayScale", ~decayScale.asString,           "0.1", "4.0"],
            ["float", "/sc/bell/tilt",       ~tilt.asString,                 "0.3", "3.0"],
            ["int",   "/sc/scale/octaves",   ~numOctaves.asString,           "1",   "6"],
            ["int",   "/sc/scale/rootMidi",  ~rootMidiNote.asString,         "24",  "96"],
            ["ints",  "/sc/scale/steps",     ~pentatonicSteps.join(","),     "0",   "11"]
        ];
    };

    // true, wenn die Adresse hierher gehoert. Wie in imPulse wird geklemmt,
    // nicht abgewiesen.
    ~presetApply = { |address, value|
        case
        { address == "/sc/scale/steps" } {
            var steps = value.split($,).collect({ |part| part.asInteger });
            if (steps.size > 0) {
                ~pentatonicSteps = steps;
                ~recalcScale.value;
            };
            true;
        }
        { address == "/sc/scale/rootMidi" } {
            ~rootMidiNote = ~clip.value(value.asInteger, 24, 96); true;
        }
        { address == "/sc/scale/octaves" } {
            ~numOctaves = ~clip.value(value.asInteger, 1, 6); ~recalcScale.value; true;
        }
        { address == "/sc/amp/min" } {
            ~minAmp = ~clip.value(value.asFloat, 0.0, 1.0); true;
        }
        { address == "/sc/amp/max" } {
            ~maxAmp = ~clip.value(value.asFloat, 0.0, 1.0); true;
        }
        { address == "/sc/bell/decayScale" } {
            ~decayScale = ~clip.value(value.asFloat, 0.1, 4.0); true;
        }
        { address == "/sc/bell/tilt" } {
            ~tilt = ~clip.value(value.asFloat, 0.3, 3.0); true;
        }
        { true } { false };
    };

    ~presetSave = { |name|
        var path, file;
        if (~presetNameValid.value(name).not) {
            ("SC-Preset-Name unzulaessig: %".format(name)).postln;
        } {
            File.mkdir(~presetDir);
            path = ~presetDir ++ "/" ++ name ++ ".txt";
            file = File(path, "w");
            if (file.isOpen) {
                ~presetValues.value.do({ |row|
                    file.write("%\t%\t\t%\t%\t%\n".format(
                        row[0], row[1], row[2], row[3], row[4]));
                });
                file.close;
                ("SC-Preset gespeichert: %".format(path)).postln;
            } {
                ("SC-Preset nicht schreibbar: %".format(path)).postln;
            };
        };
    };

    ~presetLoad = { |name|
        var path, applied = 0, unknown = [];
        if (~presetNameValid.value(name).not) {
            ("SC-Preset-Name unzulaessig: %".format(name)).postln;
        } {
            path = ~presetDir ++ "/" ++ name ++ ".txt";
            if (File.exists(path).not) {
                // Wie visuell: ein fehlendes Preset haelt nichts an, der Klang
                // bleibt wie er ist.
                ("SC-Preset nicht gefunden: % - Klang bleibt unveraendert".format(path)).postln;
            } {
                File.readAllString(path).split($\n).do({ |line|
                    var cols;
                    if (line.stripWhiteSpace.size > 0) {
                        cols = line.split($\t);
                        if (cols.size >= 4) {
                            if (~presetApply.value(cols[1], cols[3])) {
                                applied = applied + 1;
                            } {
                                unknown = unknown.add(cols[1]);
                            };
                        };
                    };
                });
                ("SC-Preset \"%\" geladen: % Werte gesetzt%".format(name, applied,
                    if (unknown.size > 0) { ", unbekannt: " ++ unknown.join(", ") } { "" })).postln;
            };
        };
    };
```

- [ ] **Step 2: Die zwei Preset-Adressen in `~paramOscFuncs` aufnehmen**

Am Ende des `~paramOscFuncs`-Arrays aus Task 9, nach dem `/sc/bell/tilt`-Eintrag, ein Komma setzen und ergänzen:

```supercollider
        // Zwei moegliche Absender: imPulse bei jedem Preset-Wechsel
        // (PresetManager.forwardToSound) und ein Bediener von aussen.
        OSCFunc({ |msg|
            ~presetLoad.value(msg[1].asString);
        }, '/sc/preset/load', recvPort: ~oscListenPort),

        OSCFunc({ |msg|
            ~presetSave.value(msg[1].asString);
        }, '/sc/preset/save', recvPort: ~oscListenPort)
```

- [ ] **Step 3: Die zwei Startpresets anlegen**

Create `supercollider/presets/hang_drum_slow.txt` — die Werte sind die heutigen Code-Defaults, die Spalte Beschreibung bleibt leer, getrennt wird mit echten Tabulatoren:

```
float	/sc/amp/max		0.5	0	1
float	/sc/amp/min		0.08	0	1
float	/sc/bell/decayScale		1.0	0.1	4.0
float	/sc/bell/tilt		1.0	0.3	3.0
int	/sc/scale/octaves		3	1	6
int	/sc/scale/rootMidi		60	24	96
ints	/sc/scale/steps		0,2,4,7,9	0	11
```

Create `supercollider/presets/standby.txt` mit **demselben Inhalt**. Das ist Absicht und keine Nachlässigkeit: `klangnetz_bells.scd` war in beiden Szenen-Snapshots identisch, die zwei Szenen unterschieden sich klanglich nie. Die Dateien sind der Ausgangspunkt zum Auseinanderentwickeln, nicht schon zwei Klänge.

Anlegen mit garantierten Tabulatoren:

```bash
mkdir -p supercollider/presets
printf 'float\t/sc/amp/max\t\t0.5\t0\t1\nfloat\t/sc/amp/min\t\t0.08\t0\t1\nfloat\t/sc/bell/decayScale\t\t1.0\t0.1\t4.0\nfloat\t/sc/bell/tilt\t\t1.0\t0.3\t3.0\nint\t/sc/scale/octaves\t\t3\t1\t6\nint\t/sc/scale/rootMidi\t\t60\t24\t96\nints\t/sc/scale/steps\t\t0,2,4,7,9\t0\t11\n' > supercollider/presets/hang_drum_slow.txt
cp supercollider/presets/hang_drum_slow.txt supercollider/presets/standby.txt
awk -F'\t' 'NF != 6 { print FILENAME": Zeile "NR" hat "NF" Spalten"; bad=1 } END { if (!bad) print "alle Zeilen sechs Spalten" }' supercollider/presets/*.txt
```

Expected: `alle Zeilen sechs Spalten` (zweimal, einmal je Datei).

- [ ] **Step 4: Am Gerät prüfen**

Wieder ohne automatisierten Nachweis. Mit laufendem `sclang supercollider/klangnetz_bells.scd`:

```bash
python3 - <<'PY'
import socket, struct, time
def msg(addr, *args):
    def pad(b): return b + b'\0' * (4 - len(b) % 4)
    out = pad(addr.encode() + b'\0')
    tags = ','
    for a in args:
        tags += 's' if isinstance(a, str) else ('f' if isinstance(a, float) else 'i')
    out += pad(tags.encode() + b'\0')
    for a in args:
        if isinstance(a, str):
            out += pad(a.encode() + b'\0')
        else:
            out += struct.pack('>f' if isinstance(a, float) else '>i', a)
    return out
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.sendto(msg('/sc/bell/decayScale', 3.5), ('127.0.0.1', 8002)); time.sleep(0.2)
s.sendto(msg('/sc/preset/save', 'testklang'), ('127.0.0.1', 8002)); time.sleep(0.4)
s.sendto(msg('/sc/preset/load', 'hang_drum_slow'), ('127.0.0.1', 8002)); time.sleep(0.4)
s.sendto(msg('/sc/preset/load', 'testklang'), ('127.0.0.1', 8002)); time.sleep(0.4)
s.sendto(msg('/sc/preset/load', 'gibtsnicht'), ('127.0.0.1', 8002)); time.sleep(0.2)
s.sendto(msg('/sc/preset/load', '../../etc/passwd'), ('127.0.0.1', 8002))
PY
```

Erwartet in dieser Reihenfolge:
1. `bell/decayScale -> 3.5`
2. `SC-Preset gespeichert: …/presets/testklang.txt`
3. `SC-Preset "hang_drum_slow" geladen: 7 Werte gesetzt`
4. `SC-Preset "testklang" geladen: 7 Werte gesetzt` — und `~decayScale.postln` ergibt wieder `3.5`
5. `SC-Preset nicht gefunden: …/gibtsnicht.txt - Klang bleibt unveraendert`
6. `SC-Preset-Name unzulaessig: ../../etc/passwd`

Danach `supercollider/presets/testklang.txt` löschen — es ist eine Prüfdatei und gehört nicht ins Repo.

Ohne `sclang` auf dem Rechner: als offene Prüfung notieren, nicht als bestanden melden.

- [ ] **Step 5: Commit**

```bash
rm -f supercollider/presets/testklang.txt
git add supercollider/klangnetz_bells.scd supercollider/presets
git commit -m "SuperCollider: Presets lesen, schreiben und von imPulse annehmen

Dasselbe Tab-Format wie in imPulse, nur um den Typ ints fuer die Tonleiter
erweitert. Namenspruefung wie visuell, weil der Name ueber OSC von aussen
kommt.

Die zwei Startpresets tragen bewusst identische Werte: klangnetz_bells.scd
war in beiden Szenen-Snapshots gleich, die Szenen unterschieden sich nie im
Klang. Sie sind der Ausgangspunkt zum Auseinanderentwickeln."
```

---

### Task 11: Dokumentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: alles Vorangehende
- Produces: nichts im Code

- [ ] **Step 1: Abschnitt in `CLAUDE.md` ergänzen**

Nach dem Abschnitt `### Impuls-Simulation (LedNetworkTransportEffect.java)` und vor `### Ausgabepfade` einfügen:

```markdown
### Preset-System (PresetStore.java, PresetScheduler.java, PresetManager.java)

Ein Preset ist ein **kompletter** Wertesatz aller 49 fernsteuerbaren
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
```

- [ ] **Step 2: SuperCollider-Abschnitt in `CLAUDE.md` ergänzen**

Am Ende des Abschnitts `## Konventionen und Fallstricke`, vor der Zeile über den To-Do-Block, einfügen:

```markdown
- **SuperCollider-Presets** liegen in `supercollider/presets/<name>.txt`, im
  selben Tab-Format wie die visuellen Presets, erweitert um den Typ `ints` für
  die Tonleiter (kommagetrennt in der Wertspalte). Steuerbar sind
  `/sc/scale/steps|rootMidi|octaves`, `/sc/amp/min|max` und die zwei globalen
  Klangregler `/sc/bell/decayScale` (streckt alle Teilton-Decays) und
  `/sc/bell/tilt` (Exponent auf die Teilton-Amps: >1 dumpfer, <1 brillanter).
  Die `#[...]`-Teilton-Literale in der SynthDef bleiben stehen — kein Rebuild
  beim Preset-Wechsel. Alles liegt weiter in **einem** `(...)`-Block: mehrere
  Top-Level-Blöcke hängen `sclang -D` auf. Für den SC-Teil gibt es **kein**
  Testgerüst im Repo, dort gilt manuelle Prüfung am Gerät.
```

- [ ] **Step 3: `README.md` ergänzen**

Einen Abschnitt „Presets" mit den drei Ladewegen, den OSC-Adressen und einem Beispielaufruf aufnehmen. Vorher die vorhandene Struktur lesen und dem Ton der Datei folgen:

Run: `grep -n '^#' README.md`

Danach den Abschnitt an der thematisch passenden Stelle einfügen, mit mindestens:

```markdown
## Presets

Ein Preset hält den kompletten Parametersatz von Visuals und Klang unter einem
Namen fest. Dateien: `data/presets/<name>.txt` (Visuals) und
`supercollider/presets/<name>.txt` (Klang).

Laden und speichern per OSC an Port 8001:

```
/preset/load hang_drum_slow
/preset/save mein_neues_preset
/preset/next
```

Beim Start:

```bash
IMPULSE_PRESET=standby processing-java --sketch=$PWD --run
```

Automatischer Wechsel alle zehn Minuten:

```
/preset/scheduler/interval 600
/preset/scheduler/enabled 1
```

imPulse leitet den Namen an SuperCollider weiter — es gibt nur einen
Scheduler, Licht und Klang bleiben zusammen.
```

- [ ] **Step 4: Gesamtprüfung**

Run: `test/run.sh`
Expected: alle Suiten grün, `PresetStoreTest` und `PresetSchedulerTest` dabei.

Run: `test/build.sh`
Expected: Exit-Code 0. Fehlt Processing, als offene Prüfung notieren.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "Preset-System dokumentieren

Warum die Werte nicht ueber den OSC-Weg gesetzt werden (Float-Streckung von
0..1 auf min..max), warum die zwei Kommando-Adressen strukturell
ausgeschlossen sind, und warum PresetManager nicht in test/run.sh gehoert."
```

---

## Abschluss

Nach Task 11 ist der Stand:

- `test/run.sh` grün mit zwei neuen Suiten
- `test/build.sh` grün (falls Processing vorhanden)
- Zwei ladbare Visual-Presets und zwei SC-Presets im Repo
- Alle Commits auf `feature/preset-system`, **kein** Merge nach `grabicz26`

**Offen und ausdrücklich nicht durch diesen Plan abgedeckt:**

1. **Verifikation am echten Aufbau.** Save/Load/Scheduler am Windows-Laptop mit laufender Installation und laufendem sclang. Das ist die Voraussetzung für einen Merge nach `grabicz26`.
2. **Der SC-Teil hat keinen automatisierten Nachweis.** Tasks 9 und 10 werden von Hand geprüft; ohne `sclang` auf dem Rechner bleiben sie offen und dürfen nicht als bestanden gemeldet werden.
3. **Klanggestaltung.** Die zwei SC-Presets tragen identische Werte. Der klangliche Unterschied zwischen `hang_drum_slow` und `standby` ist noch zu gestalten — das Preset-System liefert nur die Möglichkeit dazu.

**Bewusst nicht umgesetzt** (Begründung in der Spec): Crossfade zwischen Presets, explizite Reihenfolgen-Datei für den Scheduler, `/preset/list` mit OSC-Rückkanal, mehrere SynthDefs, Presets für die Netz-Topologie.
