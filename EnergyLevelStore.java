import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Haelt die Zuordnung Preset -> Energie-Level und beantwortet daraus die
// Frage "welche Presets gehoeren zu Level X".
//
// Das Level ist eine KUENSTLERISCHE Einschaetzung, keine Messung: rund fuenfzig
// Parameter tragen sehr unterschiedlich und nicht linear zur wahrgenommenen
// Energie bei, und die Farben tragen ebenfalls dazu bei, ohne sich in einer
// Formel fassen zu lassen. Eine Gewichtungsformel waere eine Blackbox, die
// oefter korrigiert als bestaetigt wuerde. Deshalb steht das Level in einer
// Datei, die von Hand gepflegt wird - vergeben in dem Moment, in dem jemand
// das Preset am Geraet hoert und sieht.
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt - dasselbe Muster wie StripeTreeStore,
// NodeCrossingStore und PresetStore.
//
// Datei: data/energyLevels.txt, eine Zeile je Preset:
//   presetName <TAB> level
// '#' leitet einen Kommentar ein, Leerzeilen werden uebersprungen.
class EnergyLevelStore {

  // Reihenfolge ist die Indexreihenfolge ueberall: in der Uebergangsmatrix,
  // in den Verweildauern, in /songStructure/goto und im Web-UI. Wer hier
  // umsortiert, verschiebt alle drei mit.
  static final String[] LEVEL_NAMES = { "ruhig", "mittel", "dynamisch", "dramatisch" };

  static final int LEVEL_COUNT = LEVEL_NAMES.length;

  // Fehlt ein Preset in der Datei, gilt "mittel". Nicht "ruhig" (das daempfte
  // einen unklassifizierten dramatischen Moment faelschlich) und nicht
  // "dramatisch" (das verschaerfte ihn faelschlich).
  static final int FALLBACK_LEVEL = 1;

  static int levelIndexOf(String name) {
    if (name == null) {
      return -1;
    }
    for (int i = 0; i < LEVEL_COUNT; i++) {
      if (LEVEL_NAMES[i].equalsIgnoreCase(name)) {
        return i;
      }
    }
    return -1;
  }

  // Name eines Level-Index. Ein Index ausserhalb des Bereichs gibt den
  // Rueckfall statt zu werfen: diese Methode wird auch aus Meldungen gerufen,
  // und eine Diagnoseausgabe darf nie der Grund fuer einen Absturz sein.
  static String nameOf(int level) {
    if (level < 0 || level >= LEVEL_COUNT) {
      return LEVEL_NAMES[FALLBACK_LEVEL];
    }
    return LEVEL_NAMES[level];
  }

  private final Map<String, Integer> levelOfPreset = new HashMap<String, Integer>();

  private int assigned = 0;
  private int rejected = 0;
  private int overridden = 0;
  private String message = "noch nicht geladen";

  int assignedCount() {
    return assigned;
  }

  int rejectedCount() {
    return rejected;
  }

  // Wie oft eine spaetere Zeile eine fruehere fuer dasselbe Preset ersetzt
  // hat. Siehe load() fuer die Begruendung, warum die LETZTE gewinnt.
  int overriddenCount() {
    return overridden;
  }

  String lastMessage() {
    return message;
  }

  // Level eines Presets, oder FALLBACK_LEVEL. Es gibt bewusst kein "-1" fuer
  // "nicht getaggt": jedes Preset muss in genau einem Level liegen, sonst
  // koennte es der Director nie waehlen und es verschwaende still aus der
  // Show. Wer wissen will, ob getaggt wurde, fragt untaggedCount().
  int levelOf(String presetName) {
    if (presetName == null) {
      return FALLBACK_LEVEL;
    }
    Integer level = levelOfPreset.get(presetName);
    return (level == null) ? FALLBACK_LEVEL : level.intValue();
  }

  // Die Presets dieses Levels, in der Reihenfolge von allNames.
  //
  // Gefiltert wird ausdruecklich GEGEN allNames und nicht aus der Datei
  // heraus: ein Eintrag, dessen Preset-Datei geloescht wurde, waere sonst ein
  // Name, den der Director zu laden versucht und der jedes Mal scheitert.
  //
  // Ein leeres Level liefert eine LEERE LISTE, nicht null. Anders als bei
  // StripeTreeStore.stripesFor() ist "keine Presets" hier keine Aussage
  // ueber einen abgeschalteten Filter, sondern ein Zustand, den der Aufrufer
  // wirklich behandeln muss - SongStructureDirector faellt dann auf ein
  // anderes Level zurueck.
  List<String> presetsForLevel(int level, List<String> allNames) {
    List<String> result = new ArrayList<String>();
    if (allNames == null || level < 0 || level >= LEVEL_COUNT) {
      return result;
    }
    for (int i = 0; i < allNames.size(); i++) {
      String name = allNames.get(i);
      if (levelOf(name) == level) {
        result.add(name);
      }
    }
    return result;
  }

  // Wie viele der uebergebenen Presets keinen eigenen Eintrag haben und
  // deshalb auf dem Rueckfall liegen.
  int untaggedCount(List<String> allNames) {
    if (allNames == null) {
      return 0;
    }
    int count = 0;
    for (int i = 0; i < allNames.size(); i++) {
      if (!levelOfPreset.containsKey(allNames.get(i))) {
        count++;
      }
    }
    return count;
  }

  // Einzeiler fuer die Konsole beim Start.
  String report() {
    int[] perLevel = new int[LEVEL_COUNT];
    for (Integer level : levelOfPreset.values()) {
      perLevel[level.intValue()]++;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Energie-Level: ").append(assigned).append(" Presets (");
    for (int i = 0; i < LEVEL_COUNT; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(LEVEL_NAMES[i]).append(' ').append(perLevel[i]);
    }
    sb.append(')');
    if (rejected > 0) {
      sb.append(", ").append(rejected).append(" Zeilen abgelehnt");
    }
    if (overridden > 0) {
      sb.append(", ").append(overridden).append(" durch eine spaetere Zeile ersetzt");
    }
    return sb.toString();
  }

  // Liest die Datei und ersetzt den bisherigen Stand komplett. Liefert false,
  // wenn die Datei fehlt oder nicht lesbar ist - der Aufrufer meldet das und
  // laeuft weiter: dann gilt jedes Preset als "mittel", die Show bleibt
  // vollstaendig, nur die Dramaturgie flach.
  boolean load(String path) {
    levelOfPreset.clear();
    assigned = 0;
    rejected = 0;
    overridden = 0;

    File file = new File(path);
    if (!file.isFile()) {
      message = "Energie-Level-Datei nicht gefunden: " + path;
      return false;
    }
    List<String> problems = new ArrayList<String>();
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(
          new InputStreamReader(new FileInputStream(file), "UTF-8"));
      String line;
      int lineNo = 0;
      while ((line = reader.readLine()) != null) {
        lineNo++;
        String trimmed = line.trim();
        if (trimmed.length() == 0 || trimmed.charAt(0) == '#') {
          continue;
        }
        // Tab ist das Trennzeichen des Formats; \s+ faengt zusaetzlich eine
        // von Hand mit Leerzeichen ausgerichtete Zeile ab.
        String[] fields = trimmed.split("[\\t ]+");
        if (fields.length < 2) {
          rejected++;
          problems.add("Zeile " + lineNo + ": weniger als zwei Felder");
          continue;
        }
        int level = levelIndexOf(fields[1]);
        if (level < 0) {
          rejected++;
          problems.add("Zeile " + lineNo + ": unbekanntes Level \"" + fields[1]
              + "\" - erlaubt sind ruhig, mittel, dynamisch, dramatisch");
          continue;
        }
        String name = fields[0];
        // Die LETZTE Zeile gewinnt: die Datei wird von Hand korrigiert, und
        // die natuerliche Handkorrektur ist eine angehaengte Zeile am Ende.
        // "Erste gewinnt" wuerde sie still verschlucken.
        Integer previous = levelOfPreset.get(name);
        if (previous != null) {
          overridden++;
          problems.add("Zeile " + lineNo + ": " + name + " war schon "
              + nameOf(previous.intValue()) + ", wird jetzt " + nameOf(level));
        } else {
          assigned++;
        }
        levelOfPreset.put(name, Integer.valueOf(level));
      }
    } catch (IOException e) {
      message = "Energie-Level-Datei nicht lesbar: " + e;
      return false;
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignored) {
          // Schliessen darf das Laden nicht scheitern lassen.
        }
      }
    }

    message = report();
    if (!problems.isEmpty()) {
      message = message + " | " + join(problems);
    }
    return true;
  }

  private static String join(List<String> parts) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) {
        sb.append("; ");
      }
      sb.append(parts.get(i));
    }
    return sb.toString();
  }
}
