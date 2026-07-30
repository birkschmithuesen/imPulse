import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// Erfassung der LED-Positionen mit der Maus. Haelt die Arbeitsliste der von
// Hand zu setzenden Punkte, den Zeiger darauf, die Umrechnung zwischen
// Draufsicht-Flaeche und Metern und die Rueckmeldung im Netz.
//
// Bewusst OHNE "implements runnableLedEffect": das Interface steht in
// mixer.java, das ueber RemoteControlledFloatParameter an oscP5 haengt und
// sich damit nicht von test/run.sh uebersetzen laesst. imPulse.pde ruft
// drawMe() direkt auf und geht nie ueber den Mixer - genau wie bei
// NodeCalibration -, das Interface waere also nur ein Etikett, das die
// Pruefbarkeit kostet.
//
// Ein EINTRAG der Arbeitsliste ist ein physischer Punkt, nicht eine LED. Eine
// Kreuzung ist damit ein Eintrag mit zwei LEDs; ein Klick setzt beide, weil
// LedAnchorStore.set() innerhalb eines Knotens verteilt.
class LedPositionCalibration {

  static final float[] STEP_SIZES_M = { 0.01f, 0.05f, 0.25f };
  static final long BLINK_MILLIS = 400;
  static final long CLEAR_ALL_CONFIRM_MIN_MILLIS = 300;
  static final long CLEAR_ALL_CONFIRM_MAX_MILLIS = 5000;
  static final float DIM = 0.06f;

  private final LedAnchorStore store;
  private final LedPositionMap map;
  private final NodeCrossingStore crossingStore;
  private final ArrayList<LedNetworkNode> nodes;
  private final int numStripes;
  private final int numLedsPerStripe;
  private final String filePath;
  private final int paneX;
  private final int paneY;
  private final int paneW;
  private final int paneH;
  private final float footprintX;
  private final float footprintY;

  private final List<int[]> entries = new ArrayList<int[]>();
  private int current = 0;
  private String message = "";

  LedPositionCalibration(LedAnchorStore store,
                         LedPositionMap map,
                         NodeCrossingStore crossingStore,
                         ArrayList<LedNetworkNode> nodes,
                         int numStripes, int numLedsPerStripe,
                         String filePath,
                         int paneX, int paneY, int paneW, int paneH,
                         float footprintX, float footprintY) {
    this.store = store;
    this.map = map;
    this.crossingStore = crossingStore;
    this.nodes = nodes;
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.filePath = filePath;
    this.paneX = paneX;
    this.paneY = paneY;
    this.paneW = paneW;
    this.paneH = paneH;
    this.footprintX = footprintX;
    this.footprintY = footprintY;
    rebuildWorklist();
  }

  String getName() { return "Positionen"; }

  String lastMessage() { return message; }

  // Baut die Liste der zu setzenden Punkte neu auf: jede Kreuzung ein
  // Eintrag, dazu Anfang und Ende jedes Stripes - ausser diese LED gehoert
  // schon zu einer Kreuzung, dann verschmelzen die beiden Eintraege.
  //
  // Wird aus dem Konstruktor und bei R gerufen, damit waehrend der Sitzung
  // aufgenommene Kreuzungen auftauchen.
  void rebuildWorklist() {
    int keepFirstLed = entries.isEmpty() ? -1 : entries.get(current)[0];

    List<int[]> built = new ArrayList<int[]>();
    Set<Integer> covered = new HashSet<Integer>();
    for (TreeSet<Integer> cluster : crossingStore.crossings()) {
      int[] leds = new int[cluster.size()];
      int k = 0;
      for (Integer idx : cluster) {
        leds[k] = idx.intValue();
        covered.add(idx);
        k++;
      }
      built.add(leds);
    }
    for (int st = 0; st < numStripes; st++) {
      addEndIfFree(built, covered, st * numLedsPerStripe);
      addEndIfFree(built, covered, st * numLedsPerStripe + numLedsPerStripe - 1);
    }
    Collections.sort(built, new Comparator<int[]>() {
      public int compare(int[] a, int[] b) {
        return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
      }
    });

    entries.clear();
    entries.addAll(built);

    // Nach einem Neuaufbau soll derselbe Punkt weiter unter dem Zeiger
    // stehen, nicht ein zufaellig anderer - sonst verliert man beim Druck auf
    // R die Stelle, an der man gerade arbeitet.
    current = 0;
    if (keepFirstLed >= 0) {
      for (int i = 0; i < entries.size(); i++) {
        if (entries.get(i)[0] == keepFirstLed) {
          current = i;
          break;
        }
      }
    }
  }

  private void addEndIfFree(List<int[]> built, Set<Integer> covered, int ledIndex) {
    if (covered.contains(Integer.valueOf(ledIndex))) {
      return;
    }
    built.add(new int[] { ledIndex });
    covered.add(Integer.valueOf(ledIndex));
  }

  int entryCount() { return entries.size(); }

  int entryIndex() { return entries.isEmpty() ? -1 : current; }

  // Kopie, damit ein Aufrufer die Liste nicht von aussen umschreiben kann.
  int[] ledsOfEntry(int entry) {
    if (entry < 0 || entry >= entries.size()) {
      return new int[0];
    }
    int[] src = entries.get(entry);
    int[] copy = new int[src.length];
    System.arraycopy(src, 0, copy, 0, src.length);
    return copy;
  }

  boolean entryIsCrossing(int entry) {
    return entry >= 0 && entry < entries.size() && entries.get(entry).length > 1;
  }

  // Gesetzt heisst: ALLE LEDs des Eintrags haben einen Anker. set() verteilt
  // innerhalb eines Knotens, im Normalfall sind also immer alle oder keine
  // gesetzt. Eine von Hand bearbeitete Positionsdatei kann aber die Haelfte
  // eines Knotens enthalten - der Eintrag gilt dann als offen, und ein Klick
  // setzt beide Seiten wieder zusammen.
  boolean entryIsSet(int entry) {
    if (entry < 0 || entry >= entries.size()) {
      return false;
    }
    for (int led : entries.get(entry)) {
      if (!store.has(led)) {
        return false;
      }
    }
    return true;
  }

  int openCount() {
    int open = 0;
    for (int i = 0; i < entries.size(); i++) {
      if (!entryIsSet(i)) {
        open++;
      }
    }
    return open;
  }

  void next() {
    if (current < entries.size() - 1) {
      current++;
    }
    message = describeCurrent();
  }

  void prev() {
    if (current > 0) {
      current--;
    }
    message = describeCurrent();
  }

  // Springt zum naechsten noch offenen Eintrag hinter dem aktuellen. Bleibt
  // stehen und meldet, wenn keiner mehr folgt.
  boolean nextOpen() {
    for (int i = current + 1; i < entries.size(); i++) {
      if (!entryIsSet(i)) {
        current = i;
        message = describeCurrent();
        return true;
      }
    }
    message = "Kein offener Eintrag hinter diesem - " + openCount()
        + " offen insgesamt, mit , zurueckblaettern";
    return false;
  }

  private String describeCurrent() {
    if (entries.isEmpty()) {
      return "Keine Eintraege";
    }
    int[] leds = entries.get(current);
    StringBuilder sb = new StringBuilder();
    sb.append("Eintrag ").append(current + 1).append('/').append(entries.size())
      .append(entryIsCrossing(current) ? " Kreuzung" : " Stripe-Ende").append(" LED");
    for (int led : leds) {
      sb.append(' ').append(led)
        .append(" (Stripe ").append(led / numLedsPerStripe)
        .append(':').append(led % numLedsPerStripe).append(')');
    }
    sb.append(entryIsSet(current) ? " - gesetzt" : " - offen");
    return sb.toString();
  }
}
