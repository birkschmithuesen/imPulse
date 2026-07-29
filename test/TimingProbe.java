// Test 1c - misst, ob der Sender den 25-ms-Takt haelt. Sendet echte Pakete
// ans Netz; die Controller nehmen sie an, auch ohne angeschlossene Stripes.
public class TimingProbe {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  public static void main(String[] args) throws Exception {
    int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;

    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);
    out.setMasterLevel(0.1f);
    out.start();

    int numLeds = out.numStripes() * LEDS_PER_STRIPE;
    LedColor[] colors = LedColor.createColorArray(numLeds);
    for (int i = 0; i < numLeds; i++) { colors[i].x = 0.5f; colors[i].y = 0.5f; colors[i].z = 0.5f; }

    // eine Sekunde einschwingen lassen, dann messen
    long warmupEnd = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < warmupEnd) { out.publish(colors); Thread.sleep(25); }
    out.resetIntervalStats();

    long end = System.currentTimeMillis() + seconds * 1000L;
    while (System.currentTimeMillis() < end) { out.publish(colors); Thread.sleep(25); }

    long[] s = out.intervalStatsNanos();
    out.stop();

    System.out.printf("Intervalle: %d%n", s[0]);
    System.out.printf("Mittelwert: %.3f ms  (Soll 25.000)%n", s[1] / 1e6);
    System.out.printf("Minimum:    %.3f ms%n", s[2] / 1e6);
    System.out.printf("Maximum:    %.3f ms%n", s[3] / 1e6);
    System.out.printf("Streuung:   %.3f ms%n", s[4] / 1e6);

    Check.that("Mittelwert innerhalb 24.5-25.5 ms", s[1] > 24_500_000L && s[1] < 25_500_000L);
    Check.that("Maximum unter 40 ms", s[3] < 40_000_000L);
    Check.that("Streuung unter 3 ms", s[4] < 3_000_000L);
    System.exit(Check.report("TimingProbe"));
  }
}
