// Prueft das Sicherheitsventil der Testbilder.
//
// Warum es diese Suite gibt: bis 2026-07-31 sass der Pegel der Testbilder am
// Master (CALIBRATION_MASTER_LEVEL in imPulse.pde) und galt dadurch pauschal
// fuer alles, was im Kalibriermodus lief - ein neues Muster war automatisch
// mit abgesichert. Seit der Pegel in den Mustern selbst steht
// (TestPatterns.PATTERN_LEVEL), waere er ohne diese Suite nur noch eine
// Konvention, an die sich ein spaeter ergaenztes Muster erinnern muesste.
// Genau das haelt maxChannel() hier nach: kein Muster darf einen Kanal ueber
// PATTERN_LEVEL ausgeben.
public class TestPatternsTest {

  static final int STRIPES = 4;
  static final int LEDS = 12;
  static final float TOL = 1e-6f;

  public static void main(String[] args) {
    levelCap();
    pattern5IsGreen();
    pattern1LightsOneStripe();
    pattern3LightsOnlyStripeEnds();
    System.exit(Check.report("TestPatternsTest"));
  }

  // Der hellste Fall jedes Musters gegen die Obergrenze. Bei Muster 4 ist das
  // die Weiss-Phase direkt nach reset() - alle drei Kanaele voll, heller wird
  // die Folge danach nicht mehr.
  static void levelCap() {
    TestPatterns tp = new TestPatterns();

    LedColor[] buf = fresh();
    tp.reset();
    tp.pattern1(buf, STRIPES, LEDS);
    Check.that("Muster 1 bleibt auf oder unter PATTERN_LEVEL",
        maxChannel(buf) <= TestPatterns.PATTERN_LEVEL + TOL);

    buf = fresh();
    tp.reset();
    tp.pattern2(buf, 0, LEDS);
    Check.that("Muster 2 bleibt auf oder unter PATTERN_LEVEL",
        maxChannel(buf) <= TestPatterns.PATTERN_LEVEL + TOL);

    buf = fresh();
    TestPatterns.pattern3(buf, STRIPES, LEDS);
    Check.that("Muster 3 bleibt auf oder unter PATTERN_LEVEL",
        maxChannel(buf) <= TestPatterns.PATTERN_LEVEL + TOL);

    buf = fresh();
    tp.reset();
    tp.pattern4(buf);
    Check.eq("Muster 4 steht nach reset() in der Weiss-Phase", "Weiss", tp.currentColorName());
    Check.that("Muster 4 bleibt in der Weiss-Phase auf oder unter PATTERN_LEVEL",
        maxChannel(buf) <= TestPatterns.PATTERN_LEVEL + TOL);

    buf = fresh();
    TestPatterns.pattern5(buf);
    Check.that("Muster 5 bleibt auf oder unter PATTERN_LEVEL",
        maxChannel(buf) <= TestPatterns.PATTERN_LEVEL + TOL);

    // Die Obergrenze soll auch tatsaechlich ausgereizt werden - ein Muster,
    // das versehentlich schwarz bleibt, wuerde die Pruefungen oben sonst
    // stillschweigend bestehen.
    Check.near("Muster 5 reizt PATTERN_LEVEL aus", TestPatterns.PATTERN_LEVEL,
        maxChannel(buf), TOL);
  }

  // Seit 2026-07-31 gruen statt weiss: das Bild, mit dem am Aufbau geprueft
  // wird, ob alle Stripes durchgehend leuchten.
  static void pattern5IsGreen() {
    LedColor[] buf = fresh();
    String label = TestPatterns.pattern5(buf);
    Check.that("Muster 5 nennt sich flaechig gruen", label.contains("gruen"));
    boolean allGreen = true;
    for (int i = 0; i < buf.length; i++) {
      if (Math.abs(buf[i].x) > TOL
          || Math.abs(buf[i].y - TestPatterns.PATTERN_LEVEL) > TOL
          || Math.abs(buf[i].z) > TOL) {
        allGreen = false;
      }
    }
    Check.that("jede LED steht auf gruen mit PATTERN_LEVEL, rot und blau auf 0", allGreen);
  }

  static void pattern1LightsOneStripe() {
    LedColor[] buf = fresh();
    TestPatterns tp = new TestPatterns();
    tp.reset();
    tp.pattern1(buf, STRIPES, LEDS);
    int lit = tp.currentStripe();
    boolean onlyThatStripe = true;
    for (int s = 0; s < STRIPES; s++) {
      for (int i = 0; i < LEDS; i++) {
        boolean on = buf[s * LEDS + i].y > TOL;
        if (on != (s == lit)) {
          onlyThatStripe = false;
        }
      }
    }
    Check.that("Muster 1 laesst genau einen Stripe leuchten", onlyThatStripe);
  }

  static void pattern3LightsOnlyStripeEnds() {
    LedColor[] buf = fresh();
    TestPatterns.pattern3(buf, STRIPES, LEDS);
    boolean correct = true;
    for (int s = 0; s < STRIPES; s++) {
      for (int i = 0; i < LEDS; i++) {
        boolean on = buf[s * LEDS + i].y > TOL;
        if (on != (i >= LEDS - 4)) {
          correct = false;
        }
      }
    }
    Check.that("Muster 3 laesst genau die letzten vier LEDs jedes Stripes leuchten", correct);
  }

  static LedColor[] fresh() {
    return LedColor.createColorArray(STRIPES * LEDS);
  }

  static float maxChannel(LedColor[] buf) {
    float max = 0f;
    for (int i = 0; i < buf.length; i++) {
      max = Math.max(max, Math.max(buf[i].x, Math.max(buf[i].y, buf[i].z)));
    }
    return max;
  }
}
