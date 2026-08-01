import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

// Liest und schreibt die persistierte Melodie-Zuordnung
// data/nodeMelody_<modus>.txt.
//
// Warum ueberhaupt eine Datei (Konzept, Abschnitt 8): die gewichteten
// Ziehungen der BFS sind der einzige Zufall im Verfahren, und ein Neustart
// der Installation soll exakt den Klang reproduzieren, der vorher lief. Das
// Ergebnis ist ein Wurf - aber ein festgehaltener. Berechnet wird deshalb nur
// auf ausdrueckliche Aufforderung, beim Start wird nur gelesen.
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt - dasselbe Muster wie StripeTreeStore,
// an dem sich auch das Format orientiert.
class NodeMelodyStore {

  static final String FILE_PREFIX = "nodeMelody_";
  static final String FILE_SUFFIX = ".txt";

  static String fileNameFor(String modeKey) {
    return FILE_PREFIX + modeKey + FILE_SUFFIX;
  }

  // scaleIndex je nodeId, -1 = keine Zeile fuer diesen Knoten. Die Liste
  // waechst nach Bedarf: die Datei kann mehr Knoten enthalten als der
  // laufende Sketch hat (eine Kalibriersitzung hat eine Kreuzung geloescht)
  // oder weniger (eine ist dazugekommen).
  private final List<Integer> scaleIndices = new ArrayList<Integer>();

  private String modeKey = "";
  private int rootMidi = -1;
  private int octaves = -1;
  private int startNode = -1;
  private int rejected = 0;
  private int overridden = 0;
  private String message = "noch nicht geladen";

  int size() { return scaleIndices.size(); }
  String modeKey() { return modeKey; }
  int rootMidiNote() { return rootMidi; }
  int numOctaves() { return octaves; }
  int startNode() { return startNode; }
  int rejectedCount() { return rejected; }
  int overriddenCount() { return overridden; }
  String lastMessage() { return message; }

  // -1 heisst "kein Eintrag", nicht "Stufe 0". Der Aufrufer faellt dann auf
  // die alte nodeId-Zuordnung zurueck, statt alle unbekannten Knoten auf die
  // Tonika zu legen - ein stiller Unisono waere schlechter als der bekannte
  // Zufallsklang.
  int scaleIndexOf(int nodeId) {
    if (nodeId < 0 || nodeId >= scaleIndices.size()) {
      return -1;
    }
    return scaleIndices.get(nodeId).intValue();
  }

  // Schreibt die vollstaendige Zuordnung in eine Nebendatei und benennt sie
  // anschliessend um - kein Anhaengen, also verdoppelt mehrfaches Speichern
  // nichts und ein Absturz hinterlaesst keinen halben Stand. Dasselbe
  // Verfahren wie NodeCrossingStore.save() und LedAnchorStore.save().
  //
  // stamp wird hereingegeben statt hier gebildet, damit der Aufrufer die Uhr
  // stellt und ein Test eine feste Zeile erwarten kann.
  static boolean write(String path, MelodyMode mode, MelodyAssignment a,
      int hubThreshold, String stamp) {
    File target = new File(path);
    File tmp = new File(path + ".tmp");
    PrintWriter writer = null;
    try {
      File parent = target.getParentFile();
      if (parent != null && !parent.isDirectory()) {
        parent.mkdirs();
      }
      writer = new PrintWriter(new OutputStreamWriter(
          new java.io.FileOutputStream(tmp), "UTF-8"));
      writer.print("# Melodie-Zuordnung, erzeugt von MelodyAssigner "
          + "(topologiebasierte Melodiekomposition).\n");
      writer.print("# Automatisch erzeugt, von Hand nachkorrigierbar - eine "
          + "Neuberechnung ueberschreibt die Datei.\n");
      writer.print("#\n");
      writer.print("# Modus: " + mode.key + " (" + mode.name + ")\n");
      writer.print("# Startknoten: " + a.startNode + "\n");
      writer.print("# hubThreshold: " + hubThreshold + "\n");
      writer.print("# rootMidiNote: " + a.rootMidiNote + "\n");
      writer.print("# numOctaves: " + a.numOctaves + "\n");
      writer.print("# notesPerOctaveSet: " + a.notesPerOctaveSet + "\n");
      StringBuilder steps = new StringBuilder();
      for (int i = 0; i < mode.scale.length; i++) {
        if (i > 0) {
          steps.append(' ');
        }
        steps.append(mode.scale[i]);
      }
      writer.print("# scaleSteps: " + steps + "\n");
      writer.print("# Rueckwaertskanten: " + a.backEdges
          + "   (ohne Intervall-Garantie, siehe Konzept Abschnitt 6)\n");
      writer.print("# Umbruchkanten: " + a.wrapEdges
          + "   (Oktavfaltung, siehe Konzept Schritt 3d)\n");
      if (a.unreachedCount > 0) {
        writer.print("# Nicht erreichbar: " + a.unreachedCount
            + "   (bekommen die Tonika, Tiefe -1)\n");
      }
      writer.print("# erzeugt: " + stamp + "\n");
      writer.print("#\n");
      writer.print("# Massgeblich ist scaleIndex. midiNote ist eine abgeleitete\n");
      writer.print("# Kontrollspalte zum Mitlesen, tiefe ist reine Diagnose -\n");
      writer.print("# beide werden beim Laden nicht ausgewertet.\n");
      writer.print("# Bei doppeltem nodeId gewinnt die LETZTE Zeile.\n");
      writer.print("#\n");
      writer.print("# nodeId\tscaleIndex\tmidiNote\ttiefe\n");
      for (int i = 0; i < a.nodeCount; i++) {
        writer.print(i + "\t" + a.scaleIndex[i] + "\t" + a.midiNote[i]
            + "\t" + a.depth[i] + "\n");
      }
    } catch (IOException e) {
      return false;
    } finally {
      if (writer != null) {
        writer.close();
      }
    }
    try {
      java.nio.file.Files.move(tmp.toPath(), target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      return false;
    }
    return true;
  }

  // Liest die Datei. Fehlt sie oder ist sie nicht lesbar, wird das gemeldet
  // und false geliefert - der Aufrufer faellt dann auf die alte Zuordnung
  // zurueck. Eine fehlende Melodie-Datei ist kein Betriebshindernis.
  boolean load(String path) {
    scaleIndices.clear();
    modeKey = "";
    rootMidi = -1;
    octaves = -1;
    startNode = -1;
    rejected = 0;
    overridden = 0;

    File file = new File(path);
    if (!file.isFile()) {
      message = "Melodie-Zuordnung nicht gefunden: " + path;
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
        if (trimmed.length() == 0) {
          continue;
        }
        if (trimmed.charAt(0) == '#') {
          readHeader(trimmed);
          continue;
        }
        String[] fields = trimmed.split("[\\t ]+");
        if (fields.length < 2) {
          rejected++;
          problems.add("Zeile " + lineNo + ": weniger als zwei Felder");
          continue;
        }
        int nodeId;
        int scaleIndex;
        try {
          nodeId = Integer.parseInt(fields[0]);
          scaleIndex = Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
          rejected++;
          problems.add("Zeile " + lineNo + ": \"" + fields[0] + "\"/\""
              + fields[1] + "\" sind kein Zahlenpaar");
          continue;
        }
        if (nodeId < 0) {
          rejected++;
          problems.add("Zeile " + lineNo + ": nodeId " + nodeId + " ist negativ");
          continue;
        }
        if (scaleIndex < 0) {
          rejected++;
          problems.add("Zeile " + lineNo + ": scaleIndex " + scaleIndex
              + " ist negativ - die Faltung liefert nie negative Werte");
          continue;
        }
        while (scaleIndices.size() <= nodeId) {
          scaleIndices.add(Integer.valueOf(-1));
        }
        // Die LETZTE Zeile gewinnt, gleiche Begruendung wie in
        // StripeTreeStore: die Datei ist automatisch erzeugt und wird von
        // Hand nachkorrigiert, und die natuerliche Handkorrektur ist eine
        // angehaengte Zeile am Ende. "Erste gewinnt" wuerde sie still
        // verschlucken.
        if (scaleIndices.get(nodeId).intValue() >= 0) {
          overridden++;
          problems.add("Zeile " + lineNo + ": Knoten " + nodeId + " war schon "
              + scaleIndices.get(nodeId) + ", wird jetzt " + scaleIndex);
        }
        scaleIndices.set(nodeId, Integer.valueOf(scaleIndex));
      }
    } catch (IOException e) {
      message = "Melodie-Zuordnung nicht lesbar: " + e;
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

  // Kopfzeilen "# Schluessel: Wert". Unbekannte Kommentare werden ignoriert -
  // der Kopf ist erweiterbar, ohne dass ein aelterer Leser daran scheitert.
  private void readHeader(String line) {
    String body = line.substring(1).trim();
    int colon = body.indexOf(':');
    if (colon <= 0) {
      return;
    }
    String key = body.substring(0, colon).trim();
    String value = body.substring(colon + 1).trim();
    // Nur das erste Wort: hinter dem Wert stehen im Kopf teils Klammerzusaetze
    // ("phrygisch (Phrygisch)", "37   (ohne Intervall-Garantie...)").
    int space = value.indexOf(' ');
    if (space > 0) {
      value = value.substring(0, space);
    }
    if (key.equals("Modus")) {
      modeKey = value;
    } else if (key.equals("rootMidiNote")) {
      rootMidi = parseOrMinusOne(value);
    } else if (key.equals("numOctaves")) {
      octaves = parseOrMinusOne(value);
    } else if (key.equals("Startknoten")) {
      startNode = parseOrMinusOne(value);
    }
  }

  private static int parseOrMinusOne(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  String report() {
    int known = 0;
    for (int i = 0; i < scaleIndices.size(); i++) {
      if (scaleIndices.get(i).intValue() >= 0) {
        known++;
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Melodie-Zuordnung: ").append(known).append(" Knoten");
    if (modeKey.length() > 0) {
      sb.append(", Modus ").append(modeKey);
    }
    if (rootMidi >= 0) {
      sb.append(", Grundton MIDI ").append(rootMidi);
    }
    if (octaves >= 0) {
      sb.append(", ").append(octaves).append(" Oktaven");
    }
    if (startNode >= 0) {
      sb.append(", Startknoten ").append(startNode);
    }
    if (rejected > 0) {
      sb.append(", ").append(rejected).append(" Zeilen abgelehnt");
    }
    if (overridden > 0) {
      sb.append(", ").append(overridden).append(" durch eine spaetere Zeile ersetzt");
    }
    return sb.toString();
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
