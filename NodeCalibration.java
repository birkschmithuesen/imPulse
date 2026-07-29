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

  private final int[] cursorStripe = { 0, 1 };
  private final int[] cursorLed = { 0, 0 };
  private int active = 0;
  private int stepIndex = 1;
  private boolean showNodes = false;

  private int heldLed = 0;      // -1, 0, +1
  private int heldStripe = 0;
  private long lastRepeat = 0;
  private String message = "";

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
      message = TestPatterns.pattern4(buffer);
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
    if (keyCode == KEY_LEFT) { heldLed = -1; }
    else if (keyCode == KEY_RIGHT) { heldLed = 1; }
    else if (keyCode == KEY_UP) { heldStripe = -1; }
    else if (keyCode == KEY_DOWN) { heldStripe = 1; }
  }

  void handleKeyReleased() {
    heldLed = 0;
    heldStripe = 0;
  }

  // Rueckgabe true, wenn die Taste verarbeitet wurde.
  boolean handleCommand(char key) {
    if (key == '\t') {
      active = 1 - active;
      message = "Cursor " + (active == 0 ? "A" : "B") + " aktiv";
      return true;
    }
    if (key == '\n' || key == '\r') {
      store.add(cursorStripe[0], cursorLed[0], cursorStripe[1], cursorLed[1]);
      message = store.lastMessage();
      active = 0;
      return true;
    }
    if (key == 8 || key == 127) {   // Backspace bzw. Delete
      store.undo();
      message = store.lastMessage();
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

  String hudText() {
    return String.format(
        "Stripe A [%2d] LED %3d%s    Stripe B [%2d] LED %3d%s    "
        + "Nodes: %d geladen + %d neu    Schritt: %d%n%s%n"
        + "TAB Cursor  ENTER speichern  BACKSPACE zurueck  F Schritt  "
        + "S schreiben  R uebernehmen  N Nodes  0-5 Testbild  C beenden",
        cursorStripe[0], cursorLed[0], active == 0 ? " <-" : "  ",
        cursorStripe[1], cursorLed[1], active == 1 ? " <-" : "  ",
        store.loadedCount(), store.sessionCount(), step(), message);
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
