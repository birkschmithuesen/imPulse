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
