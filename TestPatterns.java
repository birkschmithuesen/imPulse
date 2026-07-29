// Testbild-Logik fuer die Abnahme am Aufbau (Testbilder 1-5, Test 2 der
// Spezifikation). Haengt bewusst nur an LedColor und der
// Java-Standardbibliothek - kein runnableLedEffect, kein oscP5, kein
// PApplet - damit sowohl NodeCalibration (im laufenden Sketch) als auch
// test/PatternProbe.java (eigenstaendig gegen ArtNetOutput, ohne
// Processing-Laufzeit) dieselbe Stelle aufrufen. Zwei Implementierungen
// derselben fuenf Muster wuerden sonst irgendwann auseinanderlaufen, und
// die Abnahme vor Ort wuerde etwas pruefen, das der Sketch im Betrieb gar
// nicht zeigt.
class TestPatterns {

  // Zustand, der zwischen Frames fortbesteht: der laufende Stripe bei
  // Muster 1, die Lauflicht-Position bei Muster 2, der Zeitstempel des
  // letzten Schritts (von beiden Mustern genutzt, da nie gleichzeitig
  // aktiv). Gehoert hierhin und nicht in die Aufrufer.
  private int patternStripe = 0;
  private int patternLed = 0;
  private long patternLastStep = 0;

  // Beim Wechsel auf ein neues Muster aufrufen. Ohne dieses Zuruecksetzen
  // stuende patternLastStep auf 0 und der erste Vergleich (now - 0 > ...)
  // schluege sofort zu: Muster 1 spraenge direkt von Stripe 0 auf 1,
  // Muster 2 wuerde LED-Index 0 ueberspringen.
  void reset() {
    patternStripe = 0;
    patternLed = 0;
    patternLastStep = System.currentTimeMillis();
  }

  // Muster 1: ein Stripe nach dem anderen, eine Sekunde je Stripe.
  String pattern1(LedColor[] buffer, int numStripes, int numLedsPerStripe) {
    long now = System.currentTimeMillis();
    if (now - patternLastStep > 1000) {
      patternLastStep = now;
      patternStripe = (patternStripe + 1) % numStripes;
    }
    dimStripe(buffer, patternStripe, numLedsPerStripe, 1f, 1f, 1f);
    return "Testbild 1 - Stripe " + patternStripe;
  }

  // Fuer Aufrufer, die den aktuell leuchtenden Stripe aus Muster 1 separat
  // brauchen (PatternProbe gibt ihn fortlaufend auf der Konsole aus).
  int currentStripe() { return patternStripe; }

  // Muster 2: Lauflicht ueber einen Stripe, deckt die Universumsgrenzen ab.
  // Schrittweite eins je 20 ms, damit keine Universumsgrenze uebersprungen
  // wird. Welcher Stripe gezeigt wird, bestimmt der Aufrufer (im Sketch der
  // Kalibrier-Cursor, in PatternProbe ein Kommandozeilenargument).
  String pattern2(LedColor[] buffer, int stripe, int numLedsPerStripe) {
    long now = System.currentTimeMillis();
    if (now - patternLastStep > 20) {
      patternLastStep = now;
      patternLed = (patternLed + 1) % numLedsPerStripe;
    }
    int base = stripe * numLedsPerStripe;
    for (int i = 0; i < 4 && patternLed + i < numLedsPerStripe; i++) {
      buffer[base + patternLed + i].set(new LedColor(1, 1, 1));
    }
    return "Testbild 2 - Stripe " + stripe + " LED " + patternLed
        + "  (Grenzen bei 128 256 384 512)";
  }

  // Muster 3: nur die letzten vier LEDs jedes Stripes - leuchtet dabei der
  // Anfang des naechsten Outputs, schlagen die Reserve-Slots durch. Kein
  // fortlaufender Zustand noetig, daher statisch.
  static String pattern3(LedColor[] buffer, int numStripes, int numLedsPerStripe) {
    for (int s = 0; s < numStripes; s++) {
      int base = s * numLedsPerStripe;
      for (int i = numLedsPerStripe - 4; i < numLedsPerStripe; i++) {
        buffer[base + i].set(new LedColor(1, 1, 1));
      }
    }
    return "Testbild 3 - nur LED " + (numLedsPerStripe - 4) + ".."
        + (numLedsPerStripe - 1) + ". Leuchtet sonst etwas, ist es Reserve-Durchschlag";
  }

  // Muster 4: Rot, Gruen, Blau im Wechsel, je zwei Sekunden. Die Phase
  // haengt nur an der Wanduhr, kein fortlaufender Zustand noetig.
  static String pattern4(LedColor[] buffer) {
    long now = System.currentTimeMillis();
    int phase = (int) ((now / 2000) % 3);
    LedColor c = phase == 0 ? new LedColor(1, 0, 0)
               : phase == 1 ? new LedColor(0, 1, 0) : new LedColor(0, 0, 1);
    LedColor.set(buffer, c);
    return "Testbild 4 - " + (phase == 0 ? "Rot" : phase == 1 ? "Gruen" : "Blau");
  }

  // Muster 5: flaechig weiss.
  static String pattern5(LedColor[] buffer) {
    LedColor.set(buffer, new LedColor(1, 1, 1));
    return "Testbild 5 - flaechig weiss";
  }

  private static void dimStripe(LedColor[] buffer, int stripe, int numLedsPerStripe,
      float r, float g, float b) {
    int base = stripe * numLedsPerStripe;
    for (int i = 0; i < numLedsPerStripe; i++) {
      buffer[base + i].set(new LedColor(r, g, b));
    }
  }
}
