// Eigenstaendiges Programm fuer Test 2 (Testbilder zur Abnahme am Aufbau),
// unabhaengig vom Processing-Sketch.
//
// Auf diesem Rechner fehlen die Processing-Bibliotheken im Sketchbook und es
// gibt kein processing-java, der Sketch laesst sich hier also nicht starten.
// Damit waeren die Testbilder aus NodeCalibration nicht erreichbar. Dieses
// Programm speist dieselben fuenf Muster direkt ueber ArtNetOutput ein, ganz
// ohne Processing-Laufzeit.
//
// Die Musterlogik selbst steht nur einmal in TestPatterns.java (Repo-Wurzel)
// und wird von hier wie von NodeCalibration.drawPattern() aufgerufen - keine
// zweite Kopie, die auseinanderlaufen koennte.
public class PatternProbe {

  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  // Sicherheitsventil: die Stripes vertragen keine volle Helligkeit. Bewusst
  // eine Konstante statt eines Arguments, damit sie sich nicht versehentlich
  // hochschrauben laesst.
  static final float MASTER_LEVEL = 0.1f;

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.out.println("Aufruf: PatternProbe <Muster 1-5> [Laufzeit Sekunden, Vorgabe 60] "
          + "[Stripe fuer Muster 2, Vorgabe 0]");
      System.exit(1);
      return;
    }

    int pattern;
    try {
      pattern = Integer.parseInt(args[0]);
    } catch (NumberFormatException e) {
      System.out.println("Muster ist keine Zahl: " + args[0]);
      System.exit(1);
      return;
    }
    if (pattern < 1 || pattern > 5) {
      System.out.println("Muster muss zwischen 1 und 5 liegen, war " + pattern);
      System.exit(1);
      return;
    }
    int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
    int stripeForPattern2 = args.length > 2 ? Integer.parseInt(args[2]) : 0;

    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);
    out.setMasterLevel(MASTER_LEVEL);   // fest - siehe MASTER_LEVEL oben
    int numStripes = out.numStripes();

    printIntro(pattern, seconds, stripeForPattern2, numStripes);

    LedColor[] buffer = LedColor.createColorArray(numStripes * LEDS_PER_STRIPE);
    TestPatterns patterns = new TestPatterns();
    patterns.reset();
    out.start();

    int lastPrintedStripe = -1;
    String lastPrintedColor = null;
    long startTime = System.currentTimeMillis();

    long end = System.currentTimeMillis() + seconds * 1000L;
    while (System.currentTimeMillis() < end) {
      LedColor.set(buffer, new LedColor(0, 0, 0));

      if (pattern == 1) {
        patterns.pattern1(buffer, numStripes, LEDS_PER_STRIPE);
        int stripe = patterns.currentStripe();
        if (stripe != lastPrintedStripe) {
          lastPrintedStripe = stripe;
          System.out.println("  Stripe " + stripe + " leuchtet");
        }
      } else if (pattern == 2) {
        patterns.pattern2(buffer, stripeForPattern2, LEDS_PER_STRIPE);
      } else if (pattern == 3) {
        TestPatterns.pattern3(buffer, numStripes, LEDS_PER_STRIPE);
      } else if (pattern == 4) {
        patterns.pattern4(buffer);
        String color = patterns.currentColorName();
        if (!color.equals(lastPrintedColor)) {
          lastPrintedColor = color;
          long secs = (System.currentTimeMillis() - startTime) / 1000;
          System.out.println(String.format("[%2d s] senden: %s", secs, color));
        }
      } else if (pattern == 5) {
        TestPatterns.pattern5(buffer);
      }

      out.publish(buffer);
      Thread.sleep(25);
    }

    out.stop();
    System.out.println("Fertig, Ausgabe gestoppt.");
  }

  private static void printIntro(int pattern, int seconds, int stripeForPattern2, int numStripes) {
    System.out.println("Master-Pegel fest auf " + MASTER_LEVEL
        + " - laesst sich ueber Argumente nicht erhoehen.");
    System.out.println("Laufzeit " + seconds + " s, " + numStripes + " Stripes.");
    switch (pattern) {
      case 1:
        System.out.println("Testbild 1 - die Stripes leuchten nacheinander, eine Sekunde je Stripe.");
        System.out.println("Darauf achten: Reihenfolge entspricht der Controller-Liste "
            + "(15 Controller, je zwei Outputs, Oktette 2,4,6,7,8,10,12,13,14,16,17,18,19,20,21).");
        break;
      case 2:
        System.out.println("Testbild 2 - Lauflicht ueber Stripe " + stripeForPattern2 + ".");
        System.out.println("Darauf achten: keine Luecke im Lauflicht, besonders an den "
            + "Universumsgrenzen bei LED 128, 256, 384 und 512.");
        break;
      case 3:
        System.out.println("Testbild 3 - es leuchten nur die letzten vier LEDs jedes Stripes.");
        System.out.println("Darauf achten: leuchtet sonst noch etwas am Stripe-Anfang, schlagen "
            + "die Reserve-Slots des vorherigen Outputs durch.");
        break;
      case 4:
        System.out.println("Testbild 4 - Rot, Gruen, Blau im Wechsel, je zwei Sekunden.");
        System.out.println("Darauf achten: Rot ist rot, Gruen ist gruen, Blau ist blau. Kommen "
            + "die Farben vertauscht, in ArtNetOutput.java R_OFFSET und B_OFFSET tauschen.");
        break;
      case 5:
        System.out.println("Testbild 5 - flaechig weiss, Dauerlast.");
        System.out.println("Darauf achten: ueber die ganze Laufzeit stabil, kein Flackern, "
            + "kein Blackout.");
        break;
      default:
        break;
    }
  }
}
