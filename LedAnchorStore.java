import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

// Haelt die von Hand gesetzten LED-Positionen: globaler LED-Index -> (x, y)
// in Metern, Ursprung senkrecht unter der Netzmitte.
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt - dasselbe Muster wie
// NodeCrossingStore, NodeSelection und ArtNetOutput.
//
// Der Schluessel ist der globale LED-Index, NICHT die Knoten-Nummer. Damit
// bleiben alle Positionen gueltig, wenn nodeCrossings.txt sich aendert: eine
// physische LED wandert nicht, wenn eine Kreuzung nachgetragen oder
// korrigiert wird. Bei Knoten-Nummern wuerde NodeCrossingStore.removeAt()
// alle folgenden Positionen verschieben.
class LedAnchorStore {

  // Um so viel darf die Luftlinie zwischen zwei Ankern die Weglaenge entlang
  // des Stripes ueberschreiten, bevor gewarnt wird. Absolut, nicht
  // prozentual: die Warnung soll den Fehlklick auf die falsche Netzseite
  // fangen, und ein Prozentwert waere bei kurzen Abstaenden unbrauchbar
  // streng.
  static final float WARN_SLACK_M = 0.5f;

  private final int numStripes;
  private final int numLedsPerStripe;
  private final float halfX;
  private final float halfY;
  private final float ledPitchM;

  private final TreeMap<Integer, float[]> anchors = new TreeMap<Integer, float[]>();
  // Menge statt Grenzindex: eine TreeMap hat keine sinnvolle
  // Einfuegereihenfolge, und die Falle aus NodeCrossingStore.removeAt() - der
  // verschobene loadedCount - wird so strukturell unmoeglich statt behandelt.
  private final Set<Integer> loadedKeys = new HashSet<Integer>();

  private String message = "";
  private boolean warned = false;

  LedAnchorStore(int numStripes, int numLedsPerStripe,
                 float footprintX, float footprintY, float ledPitchM) {
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.halfX = footprintX / 2f;
    this.halfY = footprintY / 2f;
    this.ledPitchM = ledPitchM;
  }

  int size() { return anchors.size(); }
  int loadedCount() { return loadedKeys.size(); }
  int sessionCount() { return anchors.size() - loadedKeys.size(); }
  String lastMessage() { return message; }
  boolean lastWasWarning() { return warned; }
  boolean wasLoaded(int ledIndex) { return loadedKeys.contains(Integer.valueOf(ledIndex)); }

  boolean has(int ledIndex) { return anchors.containsKey(Integer.valueOf(ledIndex)); }

  float x(int ledIndex) {
    float[] p = anchors.get(Integer.valueOf(ledIndex));
    return p == null ? 0f : p[0];
  }

  float y(int ledIndex) {
    float[] p = anchors.get(Integer.valueOf(ledIndex));
    return p == null ? 0f : p[1];
  }

  SortedMap<Integer, float[]> all() { return anchors; }

  // Globale Indizes der Anker dieses Stripes, aufsteigend. Weil der globale
  // Index innerhalb eines Stripes monoton mit dem Index im Stripe waechst,
  // ist die Sortierung dieselbe.
  SortedSet<Integer> anchorsOnStripe(int stripeIndex) {
    if (stripeIndex < 0 || stripeIndex >= numStripes) {
      return new TreeSet<Integer>();
    }
    int from = stripeIndex * numLedsPerStripe;
    int to = from + numLedsPerStripe;
    return new TreeSet<Integer>(
        anchors.subMap(Integer.valueOf(from), Integer.valueOf(to)).keySet());
  }

  private boolean inRange(int ledIndex) {
    return ledIndex >= 0 && ledIndex < numStripes * numLedsPerStripe;
  }

  // Setzt die Position. Liegt ledIndex in einer Kreuzung, gilt sie fuer ALLE
  // LEDs dieser Kreuzung - eine Kreuzung ist ein physischer Punkt, das ist
  // keine Schaetzung sondern eine Tatsache der Geometrie.
  //
  // Die Kreuzungsliste wird hereingegeben statt gehalten, damit der Store
  // ohne eigene Kenntnis der Topologie auskommt und Aenderungen an
  // nodeCrossings.txt zur Laufzeit mitbekommt.
  boolean set(int ledIndex, float x, float y, List<TreeSet<Integer>> crossings) {
    warned = false;
    if (!inRange(ledIndex)) {
      message = "LED-Index ausserhalb des Bereichs: " + ledIndex;
      return false;
    }
    if (x < -halfX || x > halfX || y < -halfY || y > halfY) {
      message = "Position ausserhalb der Grundflaeche (" + (2 * halfX) + " x "
          + (2 * halfY) + " m): " + x + " / " + y;
      return false;
    }

    TreeSet<Integer> touched = new TreeSet<Integer>();
    touched.add(Integer.valueOf(ledIndex));
    if (crossings != null) {
      for (TreeSet<Integer> cluster : crossings) {
        if (cluster.contains(Integer.valueOf(ledIndex))) {
          for (Integer idx : cluster) {
            if (inRange(idx.intValue())) {
              touched.add(idx);
            }
          }
        }
      }
    }

    for (Integer idx : touched) {
      anchors.put(idx, new float[] { x, y });
      // Ein bearbeiteter Anker ist ein Sitzungseintrag, auch wenn er aus der
      // Datei kam - er ist in dieser Sitzung angefasst worden.
      loadedKeys.remove(idx);
    }

    String warning = arcLengthWarning(touched);
    if (warning != null) {
      warned = true;
      message = warning;
    } else {
      message = "Anker gesetzt: " + touched.size() + " LED(s) auf "
          + fmt(x) + " / " + fmt(y) + " m";
    }
    return true;
  }

  // Die Luftlinie zwischen zwei Ankern desselben Stripes kann physikalisch
  // nie laenger sein als der Weg entlang des Stripes. Geprueft wird nur gegen
  // die unmittelbaren Nachbaranker, nicht gegen alle.
  //
  // Dies WARNT und lehnt nicht ab, anders als die Kreuzungsvalidierung in
  // NodeCrossingStore: die Regel haengt an zwei Annahmen - LED-Abstand und
  // durchgehender Strang. Stimmt eine davon vor Ort nicht, waere ein hartes
  // Nein ein Werkzeug, das sich mitten in der Arbeit selbst blockiert.
  private String arcLengthWarning(TreeSet<Integer> touched) {
    for (Integer idxObj : touched) {
      int idx = idxObj.intValue();
      SortedSet<Integer> onStripe = anchorsOnStripe(idx / numLedsPerStripe);
      SortedSet<Integer> below = onStripe.headSet(idxObj);
      SortedSet<Integer> above = onStripe.tailSet(Integer.valueOf(idx + 1));
      String w = null;
      if (!below.isEmpty()) {
        w = checkPair(below.last().intValue(), idx);
      }
      if (w == null && !above.isEmpty()) {
        w = checkPair(idx, above.first().intValue());
      }
      if (w != null) {
        return w;
      }
    }
    return null;
  }

  private String checkPair(int a, int b) {
    float dx = x(b) - x(a);
    float dy = y(b) - y(a);
    double straight = Math.sqrt(dx * dx + dy * dy);
    double along = Math.abs(b - a) * (double) ledPitchM;
    if (straight > along + WARN_SLACK_M) {
      return "Warnung: Luftlinie " + fmt((float) straight) + " m zwischen LED " + a
          + " und " + b + ", der Stripe gibt aber nur " + fmt((float) along)
          + " m her - falsche Netzseite angeklickt? Position ist trotzdem gesetzt.";
    }
    return null;
  }

  boolean remove(int ledIndex) {
    warned = false;
    Integer key = Integer.valueOf(ledIndex);
    if (!anchors.containsKey(key)) {
      message = "Kein Anker bei LED " + ledIndex;
      return false;
    }
    anchors.remove(key);
    loadedKeys.remove(key);
    message = "Anker bei LED " + ledIndex + " geloescht";
    return true;
  }

  // Verwirft alles, auch die geladenen Anker - fuer den Fall, dass eine
  // Aufnahme aus einer anderen Geometrie stammt.
  void clearAll() {
    warned = false;
    int n = anchors.size();
    anchors.clear();
    loadedKeys.clear();
    message = n + " Positionen verworfen (auch geladene)";
  }

  // Drei Dezimalstellen mit Punkt, unabhaengig von der Locale des Rechners -
  // String.format wuerde in einer deutschen Locale ein Komma schreiben und
  // die Datei damit unlesbar machen.
  private static String fmt(float v) {
    return String.format(java.util.Locale.US, "%.3f", Float.valueOf(v));
  }
}
