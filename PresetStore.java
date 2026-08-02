import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
  // Textes in die gepruefte Schicht gehoert. Und er wird absolut gesetzt und
  // nicht durch digestMessage geschickt: dort wird ein eingehender Float von
  // 0..1 auf min..max gestreckt, ein gespeicherter Absolutwert wuerde dabei
  // verfaelscht.
  int applyPreset(String address, float value);
}

// Was beim Anwenden eines Presets auffiel. Bewusst gesammelt statt je Zeile
// gemeldet: bei 48 Adressen waere Zeile-fuer-Zeile-Ausgabe unlesbar.
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

  private static String join(List<String> parts) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(parts.get(i));
    }
    return sb.toString();
  }
}

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

  // Loescht eine Preset-Datei. Der einzige Weg, auf dem eine verschwindet:
  // auch das Web-UI schickt dafuer /preset/delete an imPulse, statt selbst zu
  // loeschen - "nur imPulse schreibt und loescht in data/presets/" bleibt so
  // eine Regel ohne Ausnahme, und die Namenspruefung liegt an einer Stelle.
  //
  // Eine fehlende Datei ist ausdruecklich ein Fehler und kein stiller Erfolg:
  // sonst meldete das Web-UI "geloescht" fuer ein Preset, das jemand anders
  // gerade umbenannt hat.
  //
  // Der Klang bleibt unberuehrt: supercollider/presets/<name>.txt wird NICHT
  // mitgeloescht. Loeschen ist nicht rueckgaengig zu machen, und eine
  // Fernwirkung auf einen zweiten Prozess ohne Rueckmeldung waere hier der
  // falsche Tausch - anders als beim Speichern, wo ein fehlendes Klang-Preset
  // die Szene halb wiederherstellen wuerde.
  boolean delete(String name) {
    if (!isValidName(name)) {
      return false;
    }
    File file = fileFor(name);
    if (!file.isFile()) {
      message = "Preset nicht gefunden: " + file.getPath();
      return false;
    }
    if (!file.delete()) {
      message = "Preset nicht loeschbar: " + file.getPath();
      return false;
    }
    message = "Preset \"" + name + "\" geloescht";
    return true;
  }

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
      "/preset/scheduler/interval",
      // Die vier Melodie-Parameter sind ebenfalls Transport, nicht Inhalt:
      // sie verstellen keinen Klangwert, sondern beschreiben, wie die
      // Zuordnung beim naechsten /net/melody/recompute gerechnet wird.
      //
      // Ein Preset kann sie nicht sanft ueberblenden - zwischen "alte
      // Zuordnung" und "neue Zuordnung" gibt es nichts. Ein Preset-Wechsel
      // mitten in der Show, der den Startknoten mitaenderte, saehe im Licht
      // nach nichts aus und setzte beim naechsten Neuberechnen die Tonhoehen
      // aller Glocken schlagartig neu.
      "/net/melody/mode",
      "/net/melody/startNode",
      "/net/melody/rootMidiNote",
      "/net/melody/numOctaves"
  };

  // Wie EXCLUDED, aber fuer ganze Adressbaeume. Die Song-Struktur-Ebene ist
  // ebenfalls Transport und nicht Inhalt: ein Preset, das die
  // Uebergangsmatrix oder die Verweildauern mitbraechte, koennte die
  // Dramaturgie beim naechsten Wechsel umschreiben - und das Preset, das sie
  // geaendert hat, waere danach nicht mehr wiederzufinden.
  //
  // Ein Praefix und keine Liste von 25 Einzeladressen, damit ein spaeter
  // ergaenzter Regler nicht still doch in die Presets wandert. Der Praefix
  // endet auf '/': ein eigener Adressbaum, der nur so anfaengt, bleibt drin.
  static final String[] EXCLUDED_PREFIXES = {
      "/songStructure/"
  };

  static boolean isExcluded(String address) {
    if (contains(EXCLUDED, address)) {
      return true;
    }
    for (int i = 0; i < EXCLUDED_PREFIXES.length; i++) {
      if (address.startsWith(EXCLUDED_PREFIXES[i])) {
        return true;
      }
    }
    return false;
  }

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
      if (!isExcluded(all.get(i)[COL_ADDRESS])) {
        kept.add(all.get(i));
      }
    }
    return kept;
  }

  // Wendet die Eintraege auf die Ziele an. Veraendert nur, was zugeordnet
  // werden kann; alles Auffaellige steht im Bericht. Ein defektes Preset darf
  // die Show nicht anhalten, deshalb bricht diese Methode nie ab.
  static PresetApplyReport apply(List<String[]> entries, List<PresetTarget> targets) {
    PresetApplyReport report = new PresetApplyReport();
    Set<String> seen = new HashSet<String>();
    for (int i = 0; i < entries.size(); i++) {
      String[] entry = entries.get(i);
      String address = entry[COL_ADDRESS];
      if (contains(SILENTLY_IGNORED, address) || isExcluded(address)) {
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
      if (isExcluded(address)) {
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

  // ---- Letztes Preset (data/lastPreset.txt) --------------------------
  //
  // Reiner Name, keine sechs Spalten wie ein Preset selbst - die Datei
  // ist kein Preset, sondern ein Zeiger auf eins. Zweck: imPulse soll nach
  // einem Prozess-Neustart ohne explizites Sketch-Argument/IMPULSE_PRESET
  // trotzdem dort weitermachen, wo die Show zuletzt stand (Birk, 2026-08-02).
  //
  // Statisch statt Instanzmethode auf PresetStore: die Datei liegt NICHT im
  // Preset-Ordner selbst (der wird beim Auflisten nach *.txt durchsucht,
  // eine zusaetzliche Datei dort waere ein falscher Eintrag in list()),
  // sondern eine Ebene hoeher in data/ - der Aufrufer (PresetManager)
  // uebergibt den vollen Pfad.

  // Ein Name ohne Datei ist der Normalfall (frischer Checkout, noch nie
  // gespeichert/geladen) und liefert null, keinen Fehler.
  static String readLastPresetName(String path) {
    File file = new File(path);
    if (!file.isFile()) {
      return null;
    }
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(
          new InputStreamReader(new FileInputStream(file), "UTF-8"));
      String line = reader.readLine();
      if (line == null) {
        return null;
      }
      String trimmed = line.trim();
      return (trimmed.length() == 0) ? null : trimmed;
    } catch (IOException e) {
      return null;
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignored) {
          // Schliessen darf das Lesen nicht scheitern lassen.
        }
      }
    }
  }

  // Atomar wie write(): erst Temp-Datei, dann Rename - ein abgebrochener
  // Schreibvorgang darf den vorherigen, gueltigen Namen nicht durch eine
  // halbe Zeile ersetzen.
  static boolean writeLastPresetName(String path, String name) {
    File target = new File(path);
    File parent = target.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
      return false;
    }
    File temp = new File(path + ".tmp");
    PrintWriter writer = null;
    try {
      writer = new PrintWriter(temp, "UTF-8");
      writer.print(name);
      writer.print('\n');
      writer.flush();
    } catch (IOException e) {
      return false;
    } finally {
      if (writer != null) {
        writer.close();
      }
    }
    if (target.exists() && !target.delete()) {
      return false;
    }
    return temp.renameTo(target);
  }
}
