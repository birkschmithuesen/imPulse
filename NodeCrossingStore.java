import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

// Haelt die Liste der Kreuzungen und kuemmert sich um Validierung, Undo und
// die Datei. Bewusst ohne Processing- und Netzabhaengigkeit, damit die Logik
// pruefbar ist - hier lagen die Fehler der alten Kalibrierung.
class NodeCrossingStore {

  // Zwei Cursor auf demselben Stripe muessen mindestens so weit auseinander
  // liegen, sonst ist es keine Kreuzung sondern ein Zittern des Cursors.
  static final int MIN_SAME_STRIPE_DISTANCE = 3;

  private final int numStripes;
  private final int numLedsPerStripe;
  private final List<TreeSet<Integer>> crossings = new ArrayList<TreeSet<Integer>>();
  private int loadedCount = 0;
  private String message = "";

  NodeCrossingStore(int numStripes, int numLedsPerStripe) {
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
  }

  int size() { return crossings.size(); }
  int loadedCount() { return loadedCount; }
  int sessionCount() { return crossings.size() - loadedCount; }
  String lastMessage() { return message; }
  List<TreeSet<Integer>> crossings() { return crossings; }

  private int globalIndex(int stripe, int led) {
    return stripe * numLedsPerStripe + led;
  }

  // Nimmt das Paar auf, wenn es plausibel ist. Sonst false und der Grund
  // steht in lastMessage().
  boolean add(int stripeA, int ledA, int stripeB, int ledB) {
    if (stripeA < 0 || stripeA >= numStripes || stripeB < 0 || stripeB >= numStripes) {
      message = "Stripe ausserhalb des Bereichs";
      return false;
    }
    if (ledA < 0 || ledA >= numLedsPerStripe || ledB < 0 || ledB >= numLedsPerStripe) {
      message = "LED ausserhalb des Bereichs";
      return false;
    }
    if (stripeA == stripeB) {
      if (Math.abs(ledA - ledB) < MIN_SAME_STRIPE_DISTANCE) {
        message = "Auf demselben Stripe muessen mindestens "
            + MIN_SAME_STRIPE_DISTANCE + " LEDs dazwischen liegen";
        return false;
      }
    }
    int a = globalIndex(stripeA, ledA);
    int b = globalIndex(stripeB, ledB);
    if (a == b) {
      message = "Beide Cursor stehen auf derselben LED";
      return false;
    }
    TreeSet<Integer> pair = new TreeSet<Integer>();
    pair.add(a);
    pair.add(b);
    for (TreeSet<Integer> existing : crossings) {
      if (existing.equals(pair)) {
        message = "Dieses Paar steht schon in der Liste";
        return false;
      }
    }
    crossings.add(pair);
    message = "Node " + crossings.size() + " gesetzt: " + a + " + " + b;
    return true;
  }

  // Nimmt nur zurueck, was in dieser Sitzung dazugekommen ist. Eine bestehende
  // Kalibrierung soll sich nicht versehentlich abraeumen lassen.
  boolean undo() {
    if (crossings.size() <= loadedCount) {
      message = "Nichts zurueckzunehmen";
      return false;
    }
    TreeSet<Integer> removed = crossings.remove(crossings.size() - 1);
    message = "Zurueckgenommen: " + removed;
    return true;
  }

  // Liest die Datei. Fehlerhafte Zeilen werden gemeldet und uebersprungen,
  // nicht als Absturz beim naechsten Start weitergereicht.
  void load(String path) {
    crossings.clear();
    loadedCount = 0;
    int maxIndex = numStripes * numLedsPerStripe;
    File file = new File(path);
    if (!file.exists()) {
      message = "Keine Datei " + path + ", starte mit leerer Liste";
      return;
    }
    BufferedReader reader = null;
    int lineNo = 0;
    int skipped = 0;
    try {
      reader = new BufferedReader(new FileReader(file));
      String line = reader.readLine();
      while (line != null) {
        lineNo++;
        String trimmed = line.trim();
        if (trimmed.length() > 0) {
          TreeSet<Integer> pair = new TreeSet<Integer>();
          boolean ok = true;
          for (String token : trimmed.split("\\s+")) {
            int idx;
            try {
              idx = Integer.parseInt(token);
            } catch (NumberFormatException e) {
              System.out.println("Zeile " + lineNo + " uebersprungen: \"" + token + "\" ist keine Zahl");
              ok = false;
              break;
            }
            if (idx < 0 || idx >= maxIndex) {
              System.out.println("Zeile " + lineNo + " uebersprungen: Index " + idx
                  + " liegt ausserhalb von 0.." + (maxIndex - 1));
              ok = false;
              break;
            }
            pair.add(idx);
          }
          if (ok && pair.size() >= 2) {
            crossings.add(pair);
          } else {
            skipped++;
          }
        }
        line = reader.readLine();
      }
    } catch (IOException e) {
      System.out.println("Datei konnte nicht gelesen werden: " + e);
    } finally {
      if (reader != null) { try { reader.close(); } catch (IOException e) { } }
    }
    loadedCount = crossings.size();
    message = crossings.size() + " Nodes geladen"
        + (skipped > 0 ? ", " + skipped + " Zeilen uebersprungen" : "");
  }

  // Schreibt die vollstaendige Liste in eine Nebendatei und benennt sie
  // anschliessend um. Damit gibt es weder doppelte Eintraege durch Anhaengen
  // noch einen halb geschriebenen Stand bei Absturz.
  void save(String path) throws IOException {
    File target = new File(path);
    File tmp = new File(path + ".tmp");
    PrintWriter writer = new PrintWriter(tmp, "UTF-8");
    try {
      for (TreeSet<Integer> pair : crossings) {
        StringBuilder sb = new StringBuilder();
        for (Integer idx : pair) {
          if (sb.length() > 0) sb.append(' ');
          sb.append(idx);
        }
        writer.println(sb.toString());
      }
    } finally {
      writer.close();
    }
    java.nio.file.Files.move(tmp.toPath(), target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    loadedCount = crossings.size();
    message = crossings.size() + " Nodes nach " + target.getName() + " geschrieben";
  }
}
