import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

// Haelt die Zuordnung Stripe -> physischer Baum und beantwortet daraus die
// Frage "welche Stripes gehoeren zu Baum X".
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt - dasselbe Muster wie NodeCrossingStore,
// LedAnchorStore und ArtNetOutput.
//
// Datei: data/stripeTrees.txt, eine Zeile je Stripe:
//   stripeIndex <TAB> baum <TAB> confidence <TAB> distanceMeters
// '#' leitet einen Kommentar ein, Leerzeilen werden uebersprungen.
//
// confidence und distanceMeters werden GELESEN, aber nicht ausgewertet: die
// Markierung "unsicher" ist eine Notiz fuer die Handkorrektur, kein
// Laufzeitverhalten. Gezaehlt wird sie trotzdem (uncertainCount), damit sie
// beim Start sichtbar bleibt und nicht in Vergessenheit geraet.
class StripeTreeStore {

  // Reihenfolge ist die OSC-Nummerierung: 1 = vorn, 2 = hinten, 3 = rechts,
  // 4 = links. Der Parameter traegt eine Zahl, weil
  // RemoteControlledIntParameter keine Aufzaehlung kann - dasselbe wie bei
  // noteValue im Sequencer. Im Code und im Web-UI steht der Name.
  static final String[] TREE_NAMES = { "vorn", "hinten", "rechts", "links" };

  // Filterwert 0: der Track zieht aus allen Stripes, wie ohne dieses Feature.
  static final int NO_FILTER = 0;

  private final int nStripes;

  // Baumindex (0..3) je Stripe, -1 = nicht zugeordnet.
  private final int[] treeOfStripe;

  // Vorgerechnete Pools je Baum. stripesFor() wird aus tickSequencer() mit
  // 40 Hz gerufen - dort darf nichts gesucht oder allokiert werden.
  private final int[][] pools = new int[TREE_NAMES.length][];

  private int assigned = 0;
  private int rejected = 0;
  private int overridden = 0;
  private int uncertain = 0;
  private String message = "noch nicht geladen";

  StripeTreeStore(int nStripes_) {
    nStripes = nStripes_ > 0 ? nStripes_ : 1;
    treeOfStripe = new int[nStripes];
    for (int i = 0; i < nStripes; i++) {
      treeOfStripe[i] = -1;
    }
  }

  int assignedCount() {
    return assigned;
  }

  int rejectedCount() {
    return rejected;
  }

  // Wie oft eine spaetere Zeile eine fruehere fuer denselben Stripe ersetzt
  // hat. Siehe load() fuer die Begruendung, warum die LETZTE gewinnt.
  int overriddenCount() {
    return overridden;
  }

  int uncertainCount() {
    return uncertain;
  }

  String lastMessage() {
    return message;
  }

  // Die Stripe-Indizes des Baums, aufsteigend sortiert - oder null.
  //
  // null heisst ausdruecklich "kein Filter", nicht "keine Stripes". Das gilt
  // fuer den Filterwert 0, fuer ungueltige Werte UND fuer einen Baum, dem
  // keine Stripes zugeordnet sind (etwa nach einem Tippfehler in der Datei).
  // Ein leeres Array zurueckzugeben hiesse: der Track feuert nie mehr, ohne
  // Fehler und ohne Symptom - genau der stille Ausfall, den dieses Projekt an
  // mehreren Stellen vermeidet. Gemeldet wird das beim LADEN, nicht hier:
  // eine Warnung mit 40 Hz waere unlesbar.
  int[] stripesFor(int treeFilter) {
    if (treeFilter <= NO_FILTER || treeFilter > TREE_NAMES.length) {
      return null;
    }
    int[] pool = pools[treeFilter - 1];
    if (pool == null || pool.length == 0) {
      return null;
    }
    return pool;
  }

  // Name des Baums eines Stripes, oder "-". Nur fuer Diagnoseausgaben.
  String treeNameOf(int stripeIndex) {
    if (stripeIndex < 0 || stripeIndex >= nStripes) {
      return "-";
    }
    int tree = treeOfStripe[stripeIndex];
    return (tree < 0) ? "-" : TREE_NAMES[tree];
  }

  // Einzeiler fuer die Konsole beim Start.
  String report() {
    StringBuilder sb = new StringBuilder();
    sb.append("Baum-Zuordnung: ").append(assigned).append(" Stripes (");
    for (int t = 0; t < TREE_NAMES.length; t++) {
      if (t > 0) {
        sb.append(", ");
      }
      int[] pool = pools[t];
      sb.append(TREE_NAMES[t]).append(' ').append(pool == null ? 0 : pool.length);
    }
    sb.append(')');
    if (uncertain > 0) {
      sb.append(", davon ").append(uncertain).append(" als unsicher markiert");
    }
    if (rejected > 0) {
      sb.append(", ").append(rejected).append(" Zeilen abgelehnt");
    }
    if (overridden > 0) {
      sb.append(", ").append(overridden).append(" durch eine spaetere Zeile ersetzt");
    }
    return sb.toString();
  }

  // Liest die Datei und baut die Pools neu auf. Liefert false, wenn die Datei
  // fehlt oder nicht lesbar ist - der Aufrufer meldet das und laeuft ohne
  // Filter weiter, ein Baum-Filter ist Gestaltung und keine
  // Betriebsvoraussetzung.
  boolean load(String path) {
    for (int i = 0; i < nStripes; i++) {
      treeOfStripe[i] = -1;
    }
    for (int t = 0; t < pools.length; t++) {
      pools[t] = null;
    }
    assigned = 0;
    rejected = 0;
    overridden = 0;
    uncertain = 0;

    File file = new File(path);
    if (!file.isFile()) {
      message = "Baum-Zuordnung nicht gefunden: " + path;
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
        int stripe;
        try {
          stripe = Integer.parseInt(fields[0]);
        } catch (NumberFormatException e) {
          rejected++;
          problems.add("Zeile " + lineNo + ": \"" + fields[0]
              + "\" ist kein Stripe-Index");
          continue;
        }
        if (stripe < 0 || stripe >= nStripes) {
          rejected++;
          problems.add("Zeile " + lineNo + ": Stripe " + stripe
              + " liegt ausserhalb 0.." + (nStripes - 1));
          continue;
        }
        int tree = treeIndexOf(fields[2 - 1]);
        if (tree < 0) {
          rejected++;
          problems.add("Zeile " + lineNo + ": unbekannter Baum \"" + fields[1]
              + "\" - erlaubt sind vorn, hinten, rechts, links");
          continue;
        }
        // Die LETZTE Zeile gewinnt: die Datei wird von Hand korrigiert, und
        // die natuerliche Handkorrektur ist eine angehaengte Zeile am Ende.
        // "Erste gewinnt" wuerde sie still verschlucken.
        if (treeOfStripe[stripe] >= 0) {
          overridden++;
          problems.add("Zeile " + lineNo + ": Stripe " + stripe
              + " war schon " + TREE_NAMES[treeOfStripe[stripe]]
              + ", wird jetzt " + TREE_NAMES[tree]);
        } else {
          assigned++;
        }
        treeOfStripe[stripe] = tree;
        if (fields.length >= 3 && fields[2].equalsIgnoreCase("unsicher")) {
          uncertain++;
        }
      }
    } catch (IOException e) {
      message = "Baum-Zuordnung nicht lesbar: " + e;
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

    rebuildPools();
    message = report();
    if (!problems.isEmpty()) {
      message = message + " | " + join(problems);
    }
    return true;
  }

  private void rebuildPools() {
    for (int t = 0; t < pools.length; t++) {
      int count = 0;
      for (int i = 0; i < nStripes; i++) {
        if (treeOfStripe[i] == t) {
          count++;
        }
      }
      if (count == 0) {
        pools[t] = null;
        continue;
      }
      int[] pool = new int[count];
      int k = 0;
      for (int i = 0; i < nStripes; i++) {
        if (treeOfStripe[i] == t) {
          pool[k++] = i; // aufsteigend, weil i aufsteigend laeuft
        }
      }
      pools[t] = pool;
    }
  }

  private static int treeIndexOf(String name) {
    for (int t = 0; t < TREE_NAMES.length; t++) {
      if (TREE_NAMES[t].equalsIgnoreCase(name)) {
        return t;
      }
    }
    return -1;
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
