import java.util.SortedSet;

// Rechnet aus den von Hand gesetzten Ankern die Position jeder LED.
// Bewusst ohne Processing- und Netzabhaengigkeit, damit die Rechnung ueber
// test/run.sh pruefbar bleibt.
//
// Wichtig fuer das Verstaendnis: der Vorschlag, den das Erfassungswerkzeug
// anzeigt, IST das Ergebnis dieser Klasse. Es gibt keine zweite Rechnung fuer
// "geschaetzte" Positionen und damit keine zweite Wahrheit, die auseinander
// laufen koennte.
class LedPositionMap {

  private final int numStripes;
  private final int numLedsPerStripe;
  private final float halfX;
  private final float halfY;

  // Vorgerechnete Positionen. null, solange apply() nicht lief - der heisse
  // Pfad im Transport-Effekt liest ausschliesslich hier, nicht ueber
  // positionOf(), damit pro Impuls und Frame keine Suche im Store anfaellt.
  private float[] xs;
  private float[] ys;
  private boolean[] defined;
  private boolean[] interpolated;
  private int undefined;
  private int extrapolated;

  LedPositionMap(int numStripes, int numLedsPerStripe, float footprintX, float footprintY) {
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.halfX = footprintX / 2f;
    this.halfY = footprintY / 2f;
  }

  private boolean inRange(int ledIndex) {
    return ledIndex >= 0 && ledIndex < numStripes * numLedsPerStripe;
  }

  // Position einer einzelnen LED. Liefert false, wenn der Stripe dieser LED
  // keinen einzigen Anker hat; out2 bleibt dann unberuehrt, damit der Aufrufer
  // einen alten Wert nicht mit einer Null verwechselt.
  boolean positionOf(LedAnchorStore store, int ledIndex, float[] out2) {
    if (!inRange(ledIndex)) {
      return false;
    }
    int stripe = ledIndex / numLedsPerStripe;
    SortedSet<Integer> anchors = store.anchorsOnStripe(stripe);
    if (anchors.isEmpty()) {
      return false;
    }

    // Auf einem Anker selbst ist nichts zu rechnen.
    if (store.has(ledIndex)) {
      out2[0] = store.x(ledIndex);
      out2[1] = store.y(ledIndex);
      return true;
    }
    // Ein einzelner Anker gibt keine Richtung her - alle LEDs des Stripes
    // liegen auf diesem Punkt. Grob, aber nie undefiniert.
    if (anchors.size() == 1) {
      int only = anchors.first().intValue();
      out2[0] = store.x(only);
      out2[1] = store.y(only);
      return true;
    }

    // Die zwei Anker bestimmen, zwischen bzw. ab denen gerechnet wird.
    // headSet(i)   = alle Anker echt kleiner als i
    // tailSet(i)   = alle Anker groesser oder gleich i
    SortedSet<Integer> below = anchors.headSet(Integer.valueOf(ledIndex));
    SortedSet<Integer> above = anchors.tailSet(Integer.valueOf(ledIndex));
    int ia;
    int ib;
    if (!below.isEmpty() && !above.isEmpty()) {
      // dazwischen
      ia = below.last().intValue();
      ib = above.first().intValue();
    } else if (above.isEmpty()) {
      // hinter dem letzten Anker: Vektor der LETZTEN ZWEI fortsetzen, nicht
      // den des ersten zum letzten
      ib = anchors.last().intValue();
      ia = anchors.headSet(Integer.valueOf(ib)).last().intValue();
    } else {
      // vor dem ersten Anker: Vektor der ERSTEN ZWEI fortsetzen
      ia = anchors.first().intValue();
      ib = anchors.tailSet(Integer.valueOf(ia + 1)).first().intValue();
    }

    float ax = store.x(ia);
    float ay = store.y(ia);
    float bx = store.x(ib);
    float by = store.y(ib);
    // Ein und dieselbe Zeile fuer Interpolation und Extrapolation: t liegt
    // dazwischen in (0,1) und ausserhalb eben ausserhalb.
    float t = (float) (ledIndex - ia) / (float) (ib - ia);
    out2[0] = clamp(ax + (bx - ax) * t, halfX);
    out2[1] = clamp(ay + (by - ay) * t, halfY);
    return true;
  }

  // Zwischen zwei Ankern - oder genau auf einem - gilt die Position als
  // gestuetzt. Ausserhalb ist sie geraten, und genau das faerbt das
  // Erfassungswerkzeug rot.
  boolean isInterpolatedAt(LedAnchorStore store, int ledIndex) {
    if (!inRange(ledIndex)) {
      return false;
    }
    SortedSet<Integer> anchors = store.anchorsOnStripe(ledIndex / numLedsPerStripe);
    if (anchors.isEmpty()) {
      return false;
    }
    boolean atOrBelow = !anchors.headSet(Integer.valueOf(ledIndex + 1)).isEmpty();
    boolean atOrAbove = !anchors.tailSet(Integer.valueOf(ledIndex)).isEmpty();
    return atOrBelow && atOrAbove;
  }

  // Rechnet alle Positionen einmal durch. Beim Start aus setup() und bei
  // jedem R in den beiden Kalibrierwerkzeugen. Legt die Arrays jedes Mal neu
  // an, damit ein entfernter Anker keine alte Position stehen laesst.
  void apply(LedAnchorStore store) {
    int n = numStripes * numLedsPerStripe;
    xs = new float[n];
    ys = new float[n];
    defined = new boolean[n];
    interpolated = new boolean[n];
    undefined = 0;
    extrapolated = 0;
    float[] out = new float[2];
    for (int i = 0; i < n; i++) {
      if (positionOf(store, i, out)) {
        xs[i] = out[0];
        ys[i] = out[1];
        defined[i] = true;
        interpolated[i] = isInterpolatedAt(store, i);
        if (!interpolated[i]) {
          extrapolated++;
        }
      } else {
        undefined++;
      }
    }
  }

  float x(int ledIndex) {
    return xs == null || !inRange(ledIndex) ? 0f : xs[ledIndex];
  }

  float y(int ledIndex) {
    return ys == null || !inRange(ledIndex) ? 0f : ys[ledIndex];
  }

  boolean isDefined(int ledIndex) {
    return defined != null && inRange(ledIndex) && defined[ledIndex];
  }

  boolean isInterpolated(int ledIndex) {
    return interpolated != null && inRange(ledIndex) && interpolated[ledIndex];
  }

  int undefinedCount() { return undefined; }

  int extrapolatedCount() { return extrapolated; }

  // Fuer die Taste T im Erfassungswerkzeug und die Startmeldung in
  // imPulse.pde. Nennt die Stripes ohne jeden Anker beim Namen - das sind
  // die, an denen noch gar nicht gearbeitet wurde.
  String coverageReport(LedAnchorStore store) {
    StringBuilder sb = new StringBuilder();
    sb.append(undefined).append(" LEDs ohne Position, ")
      .append(extrapolated).append(" nur extrapoliert");
    StringBuilder without = new StringBuilder();
    for (int st = 0; st < numStripes; st++) {
      if (store.anchorsOnStripe(st).isEmpty()) {
        if (without.length() > 0) {
          without.append(' ');
        }
        without.append(st);
      }
    }
    if (without.length() > 0) {
      sb.append("; Stripes ohne Anker: ").append(without);
    }
    return sb.toString();
  }

  // Die Extrapolation kann weit aus der Halle hinaus zeigen, wenn zwei Anker
  // dicht beieinander liegen und ueber hunderte LEDs fortgesetzt werden.
  // Geklemmt wird auf den physisch moeglichen Bereich, damit keine absurden
  // Koordinaten in den Klang gehen.
  private static float clamp(float v, float half) {
    if (v < -half) {
      return -half;
    }
    if (v > half) {
      return half;
    }
    return v;
  }
}
