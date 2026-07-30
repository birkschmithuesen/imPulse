import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;

// Manuelles Aufnehmen der Kreuzungen. Zwei Cursor, die abwechselnd bewegt
// werden - die alte Fassung hatte dafuer sieben Modi in einem Dropdown,
// obwohl es immer nur diese zwei Cursor waren.
//
// Beide Cursor-Stripes werden auf ganzer Laenge schwach beleuchtet, damit
// erkennbar ist welcher Stripe gemeint ist; darauf sitzt je ein heller Punkt.
public class NodeCalibration implements runnableLedEffect {

  private static final int[] STEP_SIZES = { 1, 10, 100 };
  private static final long REPEAT_MILLIS = 30;
  private static final float DIM = 0.06f;

  private final NodeCrossingStore store;
  private final LedInNetInfo[] ledNetInfo;
  private final ArrayList<LedNetworkNode> nodes;
  private final int numStripes;
  private final int numLedsPerStripe;
  private final String filePath;
  private final LedColor[] buffer;

  private final int[] cursorStripe = { 0, 0 };
  private final int[] cursorLed = { 0, 0 };
  private int active = 0;
  private int stepIndex = 1;
  private boolean showNodes = false;

  // Zeiger auf einen einzelnen Eintrag der Liste, um eine falsch gesetzte
  // Kreuzung gezielt zu loeschen - BACKSPACE traegt nur das Ende des Stapels
  // ab und schuetzt ausserdem die geladenen Eintraege.
  private final NodeSelection selection = new NodeSelection();
  private static final long SELECTION_BLINK_MILLIS = 400;

  private int heldLed = 0;      // -1, 0, +1
  private int heldStripe = 0;
  private long lastRepeat = 0;
  private String message = "";

  // Taste zum vollstaendigen Verwerfen aller Kreuzungen, auch der geladenen -
  // fuer den Fall, dass die Kalibrierung aus einer anderen Geometrie stammt.
  // Erfordert eine ausdrueckliche Bestaetigung: erster Druck kuendigt an, ein
  // zweiter Druck zwischen CLEAR_ALL_CONFIRM_MIN_MILLIS und
  // CLEAR_ALL_CONFIRM_MAX_MILLIS danach fuehrt aus. Die Untergrenze wehrt
  // Tastenwiederholung ab (ein gehaltenes L darf nicht beide Schritte in
  // Millisekunden ausloesen). Jede andere Taste - auch Pfeiltasten und das
  // Umschalten des Kalibriermodus mit C - verwirft die Ankuendigung
  // stillschweigend, siehe handleCommand/handleKeyPressed/handleKeyReleased.
  private static final long CLEAR_ALL_CONFIRM_MIN_MILLIS = 300;
  private static final long CLEAR_ALL_CONFIRM_MAX_MILLIS = 5000;
  private boolean clearAllPending = false;
  private long clearAllArmedAt = 0;

  // Testbilder fuer die Abnahme am Aufbau. 0 = Kalibrierung, 1..5 siehe
  // Abschnitt 6 der Spezifikation. Alle laufen mit dem Master-Pegel des
  // Senders, die Stripes vertragen keine volle Helligkeit. Die eigentliche
  // Musterlogik steht in TestPatterns, damit test/PatternProbe.java exakt
  // dieselben fuenf Muster zeigt statt einer zweiten, moeglicherweise
  // abweichenden Fassung.
  private int pattern = 0;
  private final TestPatterns testPatterns = new TestPatterns();

  void setPattern(int p) {
    pattern = p;
    testPatterns.reset();
    message = pattern == 0 ? "Kalibrierung" : "Testbild " + pattern;
  }

  int pattern() { return pattern; }

  private boolean drawPattern() {
    if (pattern == 0) return false;
    LedColor.set(buffer, new LedColor(0, 0, 0));

    if (pattern == 1) {
      message = testPatterns.pattern1(buffer, numStripes, numLedsPerStripe);
    } else if (pattern == 2) {
      // welcher Stripe gezeigt wird, bestimmt der Kalibrier-Cursor
      message = testPatterns.pattern2(buffer, cursorStripe[0], numLedsPerStripe);
    } else if (pattern == 3) {
      message = TestPatterns.pattern3(buffer, numStripes, numLedsPerStripe);
    } else if (pattern == 4) {
      message = testPatterns.pattern4(buffer);
    } else if (pattern == 5) {
      message = TestPatterns.pattern5(buffer);
    }
    return true;
  }

  NodeCalibration(NodeCrossingStore store, LedInNetInfo[] ledNetInfo,
                  ArrayList<LedNetworkNode> nodes, int numStripes,
                  int numLedsPerStripe, String filePath) {
    this.store = store;
    this.ledNetInfo = ledNetInfo;
    this.nodes = nodes;
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.filePath = filePath;
    this.buffer = LedColor.createColorArray(numStripes * numLedsPerStripe);
    if (numStripes > 1) cursorStripe[1] = 1;
  }

  public String getName() { return "Kalibrierung"; }

  private int step() { return STEP_SIZES[stepIndex]; }

  private int globalIndex(int cursor) {
    return cursorStripe[cursor] * numLedsPerStripe + cursorLed[cursor];
  }

  // Aus draw() aufgerufen, solange eine Pfeiltaste gehalten wird.
  void update() {
    if (heldLed == 0 && heldStripe == 0) return;
    long now = System.currentTimeMillis();
    if (now - lastRepeat < REPEAT_MILLIS) return;
    lastRepeat = now;

    if (heldLed != 0) {
      int v = cursorLed[active] + heldLed * step();
      if (v < 0) v = 0;
      if (v > numLedsPerStripe - 1) v = numLedsPerStripe - 1;
      cursorLed[active] = v;
    }
    if (heldStripe != 0) {
      int v = cursorStripe[active] + heldStripe;
      if (v < 0) v = 0;
      if (v > numStripes - 1) v = numStripes - 1;
      cursorStripe[active] = v;
    }
  }

  // Pfeiltasten. Entspricht PConstants.LEFT/UP/RIGHT/DOWN, hier benannt,
  // weil diese Klasse nicht von PApplet erbt.
  private static final int KEY_LEFT = 37;
  private static final int KEY_UP = 38;
  private static final int KEY_RIGHT = 39;
  private static final int KEY_DOWN = 40;

  void handleKeyPressed(int keyCode, char key) {
    // Pfeiltasten laufen nicht ueber handleCommand() - eine angekuendigte
    // Verwerfen-Bestaetigung muss trotzdem verfallen
    clearAllPending = false;
    if (keyCode == KEY_LEFT) { heldLed = -1; }
    else if (keyCode == KEY_RIGHT) { heldLed = 1; }
    else if (keyCode == KEY_UP) { heldStripe = -1; }
    else if (keyCode == KEY_DOWN) { heldStripe = 1; }
  }

  void handleKeyReleased() {
    // wird auch beim Umschalten des Kalibriermodus mit C gerufen (vor
    // handleCommand()) - deshalb hier zusaetzlich zuruecksetzen, nicht nur
    // in handleCommand()
    clearAllPending = false;
    heldLed = 0;
    heldStripe = 0;
  }

  // Rueckgabe true, wenn die Taste verarbeitet wurde.
  boolean handleCommand(char key) {
    // eine angekuendigte Verwerfen-Bestaetigung verfaellt stillschweigend,
    // sobald irgendeine andere Taste dazwischenkommt
    if (clearAllPending && key != 'l' && key != 'L') {
      clearAllPending = false;
    }
    if (key == '\t') {
      active = 1 - active;
      message = "Cursor " + (active == 0 ? "A" : "B") + " aktiv";
      return true;
    }
    if (key == '\n' || key == '\r') {
      boolean accepted = store.add(cursorStripe[0], cursorLed[0], cursorStripe[1], cursorLed[1]);
      message = store.lastMessage();
      if (!accepted) {
        // die HUD-Zeile kann vor Ort unlesbar sein (siehe Fenstergroesse) -
        // die Ablehnung deshalb zusaetzlich auf der Konsole ausgeben
        System.out.println("ENTER abgelehnt: " + message);
      }
      active = 0;
      return true;
    }
    if (key == 8 || key == 127) {   // Backspace bzw. Delete
      store.undo();
      selection.clampTo(store.size());
      message = store.lastMessage();
      return true;
    }
    // Einen einzelnen Eintrag heraussuchen und loeschen. Ohne das liesse sich
    // eine falsch gesetzte Kreuzung nur ueber den Texteditor und einen Neustart
    // korrigieren, sobald ein paar weitere Knoten darauf liegen.
    if (key == ',' || key == '.') {
      if (key == '.') selection.next(store.size());
      else selection.prev(store.size());
      message = describeSelection();
      return true;
    }
    if (key == 'x' || key == 'X') {
      if (!selection.hasSelection()) {
        message = "Kein Node ausgewaehlt - mit , und . durchblaettern";
        return true;
      }
      int index = selection.index();
      // die Cursor vor dem Loeschen auf den Eintrag stellen, danach ist er weg.
      // So wird aus "loeschen" ein "korrigieren": anfahren, ENTER, fertig.
      moveCursorsTo(store.crossings().get(index));
      store.removeAt(index);
      selection.clampTo(store.size());
      message = store.lastMessage() + " - Cursor stehen darauf, ENTER setzt neu";
      System.out.println(message);
      return true;
    }
    if (key == 'l' || key == 'L') {
      selection.clear();
      long now = System.currentTimeMillis();
      long sinceArmed = now - clearAllArmedAt;
      if (clearAllPending && sinceArmed < CLEAR_ALL_CONFIRM_MIN_MILLIS) {
        // Tastenwiederholung eines gehaltenen L - weder bestaetigen noch
        // die Ankuendigung erneuern, einfach ignorieren
        return true;
      }
      if (clearAllPending && sinceArmed <= CLEAR_ALL_CONFIRM_MAX_MILLIS) {
        store.clearAll();
        message = store.lastMessage();
        System.out.println(message);
        clearAllPending = false;
      } else {
        clearAllPending = true;
        clearAllArmedAt = now;
        message = "Achtung: " + store.size() + " Kreuzungen werden verworfen (auch geladene) - "
            + "L erneut druecken zum Bestaetigen";
        System.out.println(message);
      }
      return true;
    }
    if (key == 'f' || key == 'F') {
      stepIndex = (stepIndex + 1) % STEP_SIZES.length;
      message = "Schrittweite " + step();
      return true;
    }
    if (key == 's' || key == 'S') {
      try {
        store.save(filePath);
        message = store.lastMessage();
      } catch (IOException e) {
        message = "Speichern fehlgeschlagen: " + e;
      }
      return true;
    }
    if (key == 'r' || key == 'R') {
      LedInNetInfo.applyCrossings(store.crossings(), ledNetInfo, nodes);
      message = nodes.size() + " Nodes uebernommen, ohne Neustart";
      return true;
    }
    if (key == 'n' || key == 'N') {
      showNodes = !showNodes;
      message = showNodes ? "Nodes eingeblendet" : "Nodes ausgeblendet";
      return true;
    }
    if (key >= '0' && key <= '5') {
      setPattern(key - '0');
      return true;
    }
    return false;
  }

  // Setzt beide Cursor auf die ersten zwei LEDs eines Eintrags. Mehr als zwei
  // Indizes sind erlaubt (drei Stripes an einer Stelle), dann bleiben die
  // weiteren unberuecksichtigt - von Hand nachfahren.
  private void moveCursorsTo(TreeSet<Integer> pair) {
    int cursor = 0;
    for (Integer idx : pair) {
      if (cursor > 1) break;
      cursorStripe[cursor] = idx / numLedsPerStripe;
      cursorLed[cursor] = idx % numLedsPerStripe;
      cursor++;
    }
    active = 0;
  }

  private String describeSelection() {
    if (!selection.hasSelection()) return "Auswahl aufgehoben";
    int index = selection.index();
    return "Node " + (index + 1) + "/" + store.size() + " ausgewaehlt: "
        + store.crossings().get(index)
        + (index < store.loadedCount() ? " (geladen)" : " (neu)");
  }

  String hudText() {
    return String.format(
        "Stripe A [%2d] LED %3d%s    Stripe B [%2d] LED %3d%s    "
        + "Nodes: %d geladen + %d neu    Auswahl: %s    Schritt: %d%n%s%n"
        // Tastenbelegung auf zwei Zeilen, damit sie in der Fensterbreite bleibt
        + "TAB Cursor  ENTER speichern  BACKSPACE zurueck  F Schritt  S schreiben  R uebernehmen%n"
        + ", . Auswahl blaettern  X Auswahl loeschen  N Nodes  0-5 Testbild  L alles verwerfen  C beenden",
        cursorStripe[0], cursorLed[0], active == 0 ? " <-" : "  ",
        cursorStripe[1], cursorLed[1], active == 1 ? " <-" : "  ",
        store.loadedCount(), store.sessionCount(),
        selection.hasSelection() ? (selection.index() + 1) + "/" + store.size() : "-",
        step(), message);
  }

  public LedColor[] drawMe() {
    if (drawPattern()) {
      return buffer;
    }
    LedColor.set(buffer, new LedColor(0, 0, 0));

    // beide Cursor-Stripes schwach beleuchten, damit sie im Netz auffindbar sind
    dimStripe(cursorStripe[0], 0f, DIM, 0f);
    dimStripe(cursorStripe[1], DIM, 0f, 0f);

    if (showNodes) {
      java.util.List<TreeSet<Integer>> all = store.crossings();
      for (int i = 0; i < all.size(); i++) {
        boolean fromSession = i >= store.loadedCount();
        for (Integer idx : all.get(i)) {
          if (idx >= 0 && idx < buffer.length) {
            if (fromSession) buffer[idx].set(new LedColor(0, 1, 1));   // neu: cyan
            else buffer[idx].set(new LedColor(1, 0, 1));               // geladen: magenta
          }
        }
      }
    }

    // der ausgewaehlte Eintrag blinkt weiss, unabhaengig von N - sonst waere
    // nicht zu sehen, was X gleich loescht
    selection.clampTo(store.size());
    if (selection.hasSelection()
        && System.currentTimeMillis() % (2 * SELECTION_BLINK_MILLIS) < SELECTION_BLINK_MILLIS) {
      for (Integer idx : store.crossings().get(selection.index())) {
        if (idx >= 0 && idx < buffer.length) buffer[idx].set(new LedColor(1, 1, 1));
      }
    }

    // die Cursor zuletzt, damit sie nichts verdeckt
    buffer[globalIndex(1)].set(new LedColor(1, 0, 0));
    buffer[globalIndex(0)].set(new LedColor(0, 1, 0));
    return buffer;
  }

  private void dimStripe(int stripe, float r, float g, float b) {
    int base = stripe * numLedsPerStripe;
    for (int i = 0; i < numLedsPerStripe; i++) {
      buffer[base + i].set(new LedColor(r, g, b));
    }
  }
}
