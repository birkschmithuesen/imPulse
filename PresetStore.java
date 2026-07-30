import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
}
