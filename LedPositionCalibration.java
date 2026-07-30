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

  private int stepIndex = 1;               // Start bei 0.05 m
  private boolean clearAllPending = false;
  private long clearAllArmedAt = 0;
  // Die Map wird nicht bei jedem Frame neu gerechnet, sondern nur wenn sich
  // ein Anker geaendert hat. 18 000 LEDs mal mehrere Baumsuchen bei 40 Hz
  // waere Verschwendung, und die Rueckmeldung im Netz soll trotzdem sofort
  // nach jedem Klick stimmen.
  private boolean mapDirty = true;

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
    // Alle LEDs des aktuellen Eintrags merken, nicht nur die kleinste: eine
    // neu aufgenommene Kreuzung kann die bisherige LED mit einer KLEINEREN
    // zusammenlegen. Dann ist der Punkt ueber seine frueher kleinste LED nicht
    // mehr zu finden, und der Zeiger sprang auf den Listenanfang - mitten in
    // der Arbeit am Netz.
    int[] keepLeds = entries.isEmpty() ? new int[0] : ledsOfEntry(current);

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
    int found = indexOfEntryContaining(keepLeds);
    current = found >= 0 ? found : 0;
  }

  // Sucht den Eintrag, der eine dieser LEDs traegt. -1, wenn keiner.
  private int indexOfEntryContaining(int[] leds) {
    for (int i = 0; i < entries.size(); i++) {
      for (int led : entries.get(i)) {
        for (int wanted : leds) {
          if (led == wanted) {
            return i;
          }
        }
      }
    }
    return -1;
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

  // Pixel -> Meter. Liefert false, wenn der Punkt ausserhalb des Rechtecks
  // liegt, damit ein Klick ins HUD keine Position setzt; out2 bleibt dann
  // unberuehrt. Die Raender gehoeren dazu, sonst waere die aeusserste Ecke
  // der Grundflaeche nicht anklickbar.
  boolean paneToWorld(int px, int py, float[] out2) {
    if (px < paneX || px > paneX + paneW || py < paneY || py > paneY + paneH) {
      return false;
    }
    float fx = (float) (px - paneX) / (float) paneW;
    float fy = (float) (py - paneY) / (float) paneH;
    out2[0] = fx * footprintX - footprintX / 2f;
    // Y zeigt nach vorn und auf dem Schirm nach oben - hier kippt das
    // Vorzeichen, und nur hier.
    out2[1] = footprintY / 2f - fy * footprintY;
    return true;
  }

  // Meter -> Pixel, als float. imPulse.pde zeichnet damit ohne
  // Rundungssprung, und der Rundlauf mit paneToWorld bleibt pixelgenau.
  void worldToPane(float x, float y, float[] out2) {
    out2[0] = paneX + (x + footprintX / 2f) / footprintX * paneW;
    out2[1] = paneY + (footprintY / 2f - y) / footprintY * paneH;
  }

  float step() { return STEP_SIZES_M[stepIndex]; }

  void cycleStep() {
    stepIndex = (stepIndex + 1) % STEP_SIZES_M.length;
    message = "Schrittweite " + step() + " m";
  }

  boolean mapNeedsApply() { return mapDirty; }

  // Der Anker, wenn dieser Eintrag gesetzt ist, sonst der Vorschlag aus der
  // Map. Beides kommt aus LedPositionMap.positionOf - es gibt keinen zweiten
  // Rechenweg fuer "geschaetzte" Positionen.
  boolean displayPosition(float[] out2) {
    if (entries.isEmpty()) {
      return false;
    }
    return map.positionOf(store, entries.get(current)[0], out2);
  }

  // Setzt die Position des aktuellen Eintrags. set() verteilt sie innerhalb
  // eines Knotens auf alle beteiligten LEDs, ein Klick genuegt also fuer
  // beide Seiten einer Kreuzung.
  boolean setCurrent(float x, float y) {
    if (entries.isEmpty()) {
      message = "Keine Eintraege";
      return false;
    }
    boolean ok = store.set(entries.get(current)[0], x, y, crossingStore.crossings());
    message = store.lastMessage();
    // Die HUD-Zeile kann vor Ort unlesbar sein - Ablehnung und Warnung
    // deshalb zusaetzlich auf die Konsole, wie es NodeCalibration bei ENTER
    // auch tut.
    if (!ok || store.lastWasWarning()) {
      System.out.println("Position: " + message);
    }
    if (ok) {
      mapDirty = true;
    }
    return ok;
  }

  boolean acceptProposal() {
    float[] out = new float[2];
    if (!displayPosition(out)) {
      message = "Kein Vorschlag moeglich - dieser Stripe hat noch keinen Anker";
      System.out.println("Position: " + message);
      return false;
    }
    return setCurrent(out[0], out[1]);
  }

  // Nimmt die Anker ALLER LEDs des Eintrags weg, nicht nur die der ersten -
  // sonst blieb bei einer Kreuzung die halbe Position stehen.
  boolean clearCurrent() {
    if (entries.isEmpty()) {
      message = "Keine Eintraege";
      return false;
    }
    boolean any = false;
    for (int led : entries.get(current)) {
      if (store.remove(led)) {
        any = true;
      }
    }
    message = any ? store.lastMessage() : "Dieser Eintrag hat keinen Anker";
    if (any) {
      mapDirty = true;
    }
    return any;
  }

  // Verschiebt die Anzeigeposition um Schrittweiten. Steht der Eintrag noch
  // auf einem Vorschlag, wird er dadurch zum Anker - genau das will man,
  // wenn man einen Vorschlag nur ein Stueck nachbessern muss.
  boolean nudge(int dxSteps, int dySteps) {
    float[] out = new float[2];
    if (!displayPosition(out)) {
      message = "Kein Vorschlag moeglich - dieser Stripe hat noch keinen Anker";
      return false;
    }
    return setCurrent(out[0] + dxSteps * step(), out[1] + dySteps * step());
  }

  boolean save() {
    try {
      store.save(filePath);
      message = store.lastMessage();
      return true;
    } catch (java.io.IOException e) {
      message = "Speichern fehlgeschlagen: " + e;
      System.out.println("Position: " + message);
      return false;
    }
  }

  // Rechnet Map und Knotenpositionen neu und uebernimmt sie in die laufende
  // Simulation, ohne Neustart. Baut ausserdem die Arbeitsliste neu auf, damit
  // im Kalibriermodus aufgenommene Kreuzungen auftauchen.
  void reapply() {
    rebuildWorklist();
    map.apply(store);
    LedNetworkNode.applyPositions(map, nodes);
    mapDirty = false;
    message = entries.size() + " Eintraege, " + openCount() + " offen; "
        + map.coverageReport(store);
  }

  String coverageReport() {
    if (mapDirty) {
      map.apply(store);
      LedNetworkNode.applyPositions(map, nodes);
      mapDirty = false;
    }
    String rep = map.coverageReport(store);
    message = rep;
    System.out.println("Abdeckung: " + rep);
    return rep;
  }

  // Verwerfen ALLER Anker, auch der geladenen. Erster Druck kuendigt an, ein
  // zweiter zwischen 300 ms und 5 s danach fuehrt aus. Die Untergrenze wehrt
  // Tastenwiederholung bei gehaltenem L ab; die angekuendigte Bestaetigung
  // wird dabei NICHT erneuert, sonst haelt ein gedruecktes L das Fenster
  // endlos offen.
  boolean requestClearAll(long nowMillis) {
    long sinceArmed = nowMillis - clearAllArmedAt;
    if (clearAllPending && sinceArmed < CLEAR_ALL_CONFIRM_MIN_MILLIS) {
      return false;
    }
    if (clearAllPending && sinceArmed <= CLEAR_ALL_CONFIRM_MAX_MILLIS) {
      store.clearAll();
      message = store.lastMessage();
      System.out.println("Position: " + message);
      clearAllPending = false;
      mapDirty = true;
      return true;
    }
    clearAllPending = true;
    clearAllArmedAt = nowMillis;
    message = "Achtung: " + store.size() + " Positionen werden verworfen (auch geladene) - "
        + "L erneut druecken zum Bestaetigen";
    System.out.println("Position: " + message);
    return false;
  }

  void abortClearAll() { clearAllPending = false; }

  String hudText() {
    float[] out = new float[2];
    boolean known = displayPosition(out);
    String pos = known
        ? String.format(java.util.Locale.US, "x %+6.2f  y %+6.2f m", Float.valueOf(out[0]),
            Float.valueOf(out[1]))
        : "keine Position";
    return String.format(java.util.Locale.US,
        "Eintrag %d/%d  %s  %s  %s%n"
        + "Positionen: %d geladen + %d neu    offen: %d    Schritt: %.2f m%n"
        + "%s%n"
        + "Maus setzen  ENTER Vorschlag  BACKSPACE loeschen  Pfeile feinjustieren  F Schritt%n"
        + ", . blaettern  o naechster offener  S schreiben  R uebernehmen  T Abdeckung  "
        + "L alles verwerfen  P beenden",
        Integer.valueOf(current + 1), Integer.valueOf(entries.size()),
        entryIsCrossing(current) ? "Kreuzung" : "Stripe-Ende",
        entryIsSet(current) ? "gesetzt" : "offen",
        pos,
        Integer.valueOf(store.loadedCount()), Integer.valueOf(store.sessionCount()),
        Integer.valueOf(openCount()), Float.valueOf(step()),
        message);
  }
}
