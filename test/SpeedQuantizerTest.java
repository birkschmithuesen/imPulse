public class SpeedQuantizerTest {

  public static void main(String[] args) throws Exception {
    // ---- Die Klassen selbst ----
    Check.eq("fuenf Klassen", 5, SpeedQuantizer.MULTIPLIERS.length);
    Check.near("Klasse 0 ist halbe Geschwindigkeit",
        0.5, SpeedQuantizer.multiplierAt(0), 1e-9);
    Check.near("Klasse 1 ist der Normalfall",
        1.0, SpeedQuantizer.multiplierAt(SpeedQuantizer.NEUTRAL_INDEX), 1e-9);
    Check.near("Klasse 4 ist achtfach", 8.0, SpeedQuantizer.multiplierAt(4), 1e-9);
    // Ein Index ausserhalb darf keinen Absturz geben, sondern den Normalfall
    Check.near("Index unter 0 gibt den Normalfall",
        1.0, SpeedQuantizer.multiplierAt(-1), 1e-9);
    Check.near("Index ueber der Liste gibt den Normalfall",
        1.0, SpeedQuantizer.multiplierAt(99), 1e-9);

    // ---- Auswahl ueber die kumulierte Verteilung ----
    // Gewichte 85/10/4/1 auf 1x/2x/4x/8x, 0.5x aus - der Auslieferungsfall.
    float[] w = { 0f, 85f, 10f, 4f, 1f };
    // Summe 100, die Grenzen liegen also bei 0.85 / 0.95 / 0.99 / 1.0
    Check.eq("ganz unten faellt in die erste Klasse mit Gewicht",
        1, SpeedQuantizer.pick(w, 0.0));
    Check.eq("knapp unter der ersten Grenze noch 1x",
        1, SpeedQuantizer.pick(w, 0.849));
    Check.eq("ab der ersten Grenze 2x", 2, SpeedQuantizer.pick(w, 0.851));
    Check.eq("knapp unter der zweiten Grenze noch 2x",
        2, SpeedQuantizer.pick(w, 0.949));
    Check.eq("ab der zweiten Grenze 4x", 3, SpeedQuantizer.pick(w, 0.951));
    Check.eq("ab der dritten Grenze 8x", 4, SpeedQuantizer.pick(w, 0.995));
    Check.eq("ganz oben die letzte Klasse mit Gewicht",
        4, SpeedQuantizer.pick(w, 0.9999));

    // Eine Klasse mit Gewicht 0 wird nie gewaehlt - sonst waere ein
    // ausgeschalteter Ausreisser doch gelegentlich zu hoeren
    for (int i = 0; i <= 1000; i++) {
      Check.that("Gewicht 0 wird nie gewaehlt",
          SpeedQuantizer.pick(w, i/1000.0) != 0);
    }

    // ---- Die Verteilung stimmt ueber viele Ziehungen ----
    // Gleichverteilte Eingaben muessen die Gewichte reproduzieren.
    int[] counts = new int[5];
    int n = 100000;
    for (int i = 0; i < n; i++) {
      counts[SpeedQuantizer.pick(w, (i + 0.5)/n)]++;
    }
    Check.eq("0.5x kommt nicht vor", 0, counts[0]);
    Check.near("1x trifft die 85 Prozent", 0.85, counts[1]/(double) n, 0.002);
    Check.near("2x trifft die 10 Prozent", 0.10, counts[2]/(double) n, 0.002);
    Check.near("4x trifft die 4 Prozent", 0.04, counts[3]/(double) n, 0.002);
    Check.near("8x trifft das 1 Prozent", 0.01, counts[4]/(double) n, 0.002);

    // ---- Gewichte muessen sich nicht zu 100 summieren ----
    // Der Operator dreht einzelne Regler, ohne den Rest nachzurechnen -
    // normalisiert wird hier, nicht von Hand.
    float[] w2 = { 0f, 3f, 1f, 0f, 0f }; // 75% / 25%
    int einsX = 0;
    for (int i = 0; i < 40000; i++) {
      if (SpeedQuantizer.pick(w2, (i + 0.5)/40000.0) == 1) {
        einsX++;
      }
    }
    Check.near("Gewichte werden normalisiert, nicht als Prozent gelesen",
        0.75, einsX/40000.0, 0.002);

    // ---- Entartete Gewichte ----
    // Alles 0: es muss trotzdem eine Klasse herauskommen, und zwar der
    // Normalfall - sonst stuenden alle Impulse still oder raesten.
    Check.eq("alle Gewichte 0 gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX,
        SpeedQuantizer.pick(new float[] { 0f, 0f, 0f, 0f, 0f }, 0.5));
    Check.eq("null-Array gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX, SpeedQuantizer.pick(null, 0.5));
    Check.eq("zu kurzes Array gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX, SpeedQuantizer.pick(new float[] { 1f }, 0.5));
    // Negative Gewichte gelten als 0, nicht als Abzug von der Summe
    Check.eq("negatives Gewicht wird nicht gewaehlt",
        1, SpeedQuantizer.pick(new float[] { -5f, 1f, 0f, 0f, 0f }, 0.5));
    // NaN darf sich nicht in die Summe fortpflanzen
    Check.eq("NaN-Gewicht gilt als 0",
        1, SpeedQuantizer.pick(new float[] { Float.NaN, 1f, 0f, 0f, 0f }, 0.5));
    Check.eq("NaN-Zufall gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX, SpeedQuantizer.pick(w, Double.NaN));

    // Zufall ausserhalb 0..1 wird geklemmt statt zu ueberlaufen
    Check.eq("Zufall unter 0 wird geklemmt", 1, SpeedQuantizer.pick(w, -1.0));
    Check.eq("Zufall ueber 1 wird geklemmt", 4, SpeedQuantizer.pick(w, 2.0));

    // Genau eine Klasse mit Gewicht: sie gewinnt immer
    float[] nurVier = { 0f, 0f, 0f, 1f, 0f };
    for (int i = 0; i <= 100; i++) {
      Check.eq("einzige gewichtete Klasse gewinnt immer",
          3, SpeedQuantizer.pick(nurVier, i/100.0));
    }

    // ---- decayScaleFor: Kopplung Speed-Klasse -> Zerfall (Kettenreaktions-Fix) ----
    // Dieselbe Zahl wie multiplierAt() fuer jede der fuenf Klassen - ein
    // M-fach schneller Impuls soll M-mal schneller zerfallen, damit die
    // zurueckgelegte Grunddistanz unabhaengig von der Speed-Klasse bleibt.
    for (int i = 0; i < SpeedQuantizer.MULTIPLIERS.length; i++) {
      Check.near("decayScaleFor(" + i + ") == multiplierAt(" + i + ")",
          SpeedQuantizer.multiplierAt(i), SpeedQuantizer.decayScaleFor(i), 1e-9);
    }
    Check.near("0.5x-Klasse zerfaellt halb so schnell",
        0.5, SpeedQuantizer.decayScaleFor(0), 1e-9);
    Check.near("8x-Klasse zerfaellt achtfach so schnell",
        8.0, SpeedQuantizer.decayScaleFor(4), 1e-9);
    // Entartete Indizes verhalten sich wie bei multiplierAt(): Normalfall
    Check.near("Index unter 0 gibt den Normalfall-Zerfall",
        1.0, SpeedQuantizer.decayScaleFor(-1), 1e-9);
    Check.near("Index ueber der Liste gibt den Normalfall-Zerfall",
        1.0, SpeedQuantizer.decayScaleFor(99), 1e-9);

    System.exit(Check.report("SpeedQuantizerTest"));
  }
}
