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

  // Sicherheitsventil der Testbilder. Die Muster ziehen bis zu alle 18 000
  // LEDs gleichzeitig auf, und bei 10 m Stripe-Laenge ist das laut Handbuch
  // ein Spannungsabfall-Risiko - deshalb geben sie hoechstens diesen Anteil
  // aus.
  //
  // Der Pegel sitzt seit 2026-07-31 HIER und nicht mehr am Master
  // (frueher CALIBRATION_MASTER_LEVEL in imPulse.pde, das den Fader waehrend
  // des Kalibriermodus pauschal ersetzte). Grund: die Kalibrieransicht selbst
  // zeigt nur einzelne Punkte und zwei schwach eingefaerbte Stripes, die darf
  // der Fader bis 1.0 hochziehen - nur die flaechigen Testbilder duerfen es
  // nicht. Ein Master, der beides zugleich bedient, kann das nicht trennen.
  //
  // Die Sicherheitseigenschaft bleibt dabei erhalten, sie wird sogar
  // strenger: der Master multipliziert weiter obendrauf und ist auf 0..1
  // geklemmt (ArtNetOutput.setMasterLevel), ein Testbild kommt also nie ueber
  // PATTERN_LEVEL heraus - der Fader kann es nur dunkler machen. Bewusst eine
  // Konstante statt eines OSC-Parameters, damit sie sich nicht versehentlich
  // hochschrauben laesst.
  static final float PATTERN_LEVEL = 0.1f;

  // Jede Farbe eines Testbildes geht durch diese Methode - kein Muster baut
  // sein LedColor selbst. Ohne diesen einen Durchgang waere PATTERN_LEVEL
  // eine Konvention, an die sich ein spaeter ergaenztes Muster erinnern
  // muesste; test/TestPatternsTest.java haelt das nach.
  private static LedColor lit(float r, float g, float b) {
    return new LedColor(r * PATTERN_LEVEL, g * PATTERN_LEVEL, b * PATTERN_LEVEL);
  }

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
      buffer[base + patternLed + i].set(lit(1, 1, 1));
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
        buffer[base + i].set(lit(1, 1, 1));
      }
    }
    return "Testbild 3 - nur LED " + (numLedsPerStripe - 4) + ".."
        + (numLedsPerStripe - 1) + ". Leuchtet sonst etwas, ist es Reserve-Durchschlag";
  }

  // Farbphase von Muster 4 - eigenes Feld statt Wiederverwendung von
  // patternStripe/patternLed, damit currentColorName() unabhaengig von den
  // anderen Mustern lesbar bleibt. 0 Weiss, 1 Rot, 2 Gruen, 3 Blau, 4 Ende
  // (schwarz, Folge bleibt hier stehen).
  private int patternColorPhase = 0;

  // Grenzen der einmaligen Folge, kumulierte Millisekunden seit reset():
  // 2 s Weiss (Startmarke, eindeutiger Beginn), dann je 3 s Rot/Gruen/Blau,
  // danach fuer immer schwarz - kein Neubeginn, keine Schleife. Weiss als
  // Startmarke und die genauen Sekundenwerte waren eine Nutzer-Vorgabe, nachdem
  // zwei Abnahmelaeufe wegen unklarem Folgenanfang widerspruechlich ausfielen.
  private static final long PHASE4_WHITE_END = 2000;
  private static final long PHASE4_RED_END = PHASE4_WHITE_END + 3000;
  private static final long PHASE4_GREEN_END = PHASE4_RED_END + 3000;
  private static final long PHASE4_BLUE_END = PHASE4_GREEN_END + 3000;

  // Muster 4: einmalige Folge Weiss/Rot/Gruen/Blau/Schwarz, siehe
  // PHASE4_*_END. Die Phase haengt an der Zeit seit dem letzten reset()
  // (patternLastStep), nicht an der Wanduhr - sonst waere die Startfarbe bei
  // jedem Lauf zufaellig, und genau das soll dieses Testbild ja klaeren.
  // patternLastStep wird hier nur gelesen, nicht veraendert - unschaedlich,
  // da nie gleichzeitig mit Muster 1/2 aktiv (reset() setzt den Stempel bei
  // jedem Musterwechsel neu, auch bei erneutem Druck auf "4").
  String pattern4(LedColor[] buffer) {
    long elapsed = System.currentTimeMillis() - patternLastStep;
    int phase = elapsed < PHASE4_WHITE_END ? 0
              : elapsed < PHASE4_RED_END ? 1
              : elapsed < PHASE4_GREEN_END ? 2
              : elapsed < PHASE4_BLUE_END ? 3
              : 4;
    patternColorPhase = phase;
    LedColor c = phase == 0 ? lit(1, 1, 1)
               : phase == 1 ? lit(1, 0, 0)
               : phase == 2 ? lit(0, 1, 0)
               : phase == 3 ? lit(0, 0, 1)
               : lit(0, 0, 0);
    LedColor.set(buffer, c);
    return "Testbild 4 - " + colorName(phase);
  }

  // Fuer Aufrufer, die die gerade gesendete Farbe separat brauchen
  // (PatternProbe gibt sie bei jedem Wechsel auf der Konsole aus).
  String currentColorName() { return colorName(patternColorPhase); }

  // Fuer Aufrufer, die zwischen "laeuft noch" und "Folge beendet"
  // unterscheiden muessen (PatternProbe formuliert die letzte Zeile anders).
  int currentColorPhase() { return patternColorPhase; }

  // Verstrichene Zeit seit reset(), dieselbe Zeitbasis wie pattern4() intern
  // verwendet. Frueher fuehrte eine zweite, unabhaengig in PatternProbe
  // erfasste Startzeit zu widerspruechlichen Sekundenangaben auf der Konsole:
  // zwischen reset() und der zweiten Zeitmessung lief noch Code (Verbindungs-
  // aufbau des ArtNet-Senders), wodurch die beiden Uhren auseinanderliefen.
  // Aufrufer sollen fuer Zeitangaben ausschliesslich diese Methode nutzen.
  long elapsedMillis() { return System.currentTimeMillis() - patternLastStep; }

  private static String colorName(int phase) {
    switch (phase) {
      case 0: return "Weiss";
      case 1: return "Rot";
      case 2: return "Gruen";
      case 3: return "Blau";
      default: return "Schwarz";
    }
  }

  // Muster 5: alle Stripes flaechig gruen.
  //
  // War bis 2026-07-31 flaechig weiss. Gruen auf Nutzer-Wunsch: das ist das
  // Bild, mit dem am Aufbau geprueft wird, ob alle Stripes durchgehend
  // leuchten, und es zieht dabei nur einen der drei Kanaele auf - also ein
  // Drittel der Last des frueheren Weiss auf denselben 18 000 LEDs. Die
  // Kanalreihenfolge prueft weiterhin Muster 4, das Weiss und die Grundfarben
  // nacheinander zeigt.
  static String pattern5(LedColor[] buffer) {
    LedColor.set(buffer, lit(0, 1, 0));
    return "Testbild 5 - alle Stripes flaechig gruen";
  }

  private static void dimStripe(LedColor[] buffer, int stripe, int numLedsPerStripe,
      float r, float g, float b) {
    int base = stripe * numLedsPerStripe;
    for (int i = 0; i < numLedsPerStripe; i++) {
      buffer[base + i].set(lit(r, g, b));
    }
  }
}
