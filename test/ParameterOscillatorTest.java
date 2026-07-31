public class ParameterOscillatorTest {

  public static void main(String[] args) throws Exception {
    // ---- die reine Formel ----
    // Ein voller Zyklus dauert period; Phase 0 ist die Mitte, ein Viertel
    // danach das Maximum, drei Viertel danach das Minimum.
    Check.near("Phase 0 steht in der Mitte",
        50.0, ParameterOscillator.sineOscillate(0.0, 20f, 0f, 100f), 1e-4);
    Check.near("nach einem Viertel das Maximum",
        100.0, ParameterOscillator.sineOscillate(5.0, 20f, 0f, 100f), 1e-3);
    Check.near("nach der Haelfte wieder die Mitte",
        50.0, ParameterOscillator.sineOscillate(10.0, 20f, 0f, 100f), 1e-3);
    Check.near("nach drei Vierteln das Minimum",
        0.0, ParameterOscillator.sineOscillate(15.0, 20f, 0f, 100f), 1e-3);
    Check.near("nach einer vollen Periode wieder die Mitte",
        50.0, ParameterOscillator.sineOscillate(20.0, 20f, 0f, 100f), 1e-3);
    Check.near("und ein Zyklus spaeter derselbe Wert",
        ParameterOscillator.sineOscillate(3.0, 20f, 0f, 100f),
        ParameterOscillator.sineOscillate(23.0, 20f, 0f, 100f), 1e-3);

    // Die Grenzen werden nie verlassen - der Wert geht so direkt in
    // impulseSpeed/impulseLifetime, ohne dort noch einmal geklemmt zu werden.
    for (int i = 0; i <= 1000; i++) {
      double t = i*0.037;
      float v = ParameterOscillator.sineOscillate(t, 7f, 0.005f, 0.05f);
      Check.that("Wert bleibt in min..max bei t=" + t, v >= 0.005f - 1e-6 && v <= 0.05f + 1e-6);
    }

    // Unbrauchbare Periode: Mitte statt NaN oder Division durch 0
    Check.near("Periode 0 liefert die Mitte",
        8.0, ParameterOscillator.sineOscillate(3.0, 0f, 6f, 10f), 1e-4);
    Check.near("negative Periode liefert die Mitte",
        8.0, ParameterOscillator.sineOscillate(3.0, -20f, 6f, 10f), 1e-4);
    Check.near("NaN-Periode liefert die Mitte",
        8.0, ParameterOscillator.sineOscillate(3.0, Float.NaN, 6f, 10f), 1e-4);
    Check.near("NaN-Zeit liefert die Mitte",
        8.0, ParameterOscillator.sineOscillate(Double.NaN, 20f, 6f, 10f), 1e-4);

    // Entartete Bereiche
    Check.near("min == max ist konstant",
        4.0, ParameterOscillator.sineOscillate(3.3, 20f, 4f, 4f), 1e-4);
    Check.near("min > max schwingt spiegelverkehrt, ein Viertel ergibt max",
        20.0, ParameterOscillator.sineOscillate(5.0, 20f, 100f, 20f), 1e-3);

    // ---- Zustand: Phase laeuft ab dem Einschalten ----
    ParameterOscillator osc = new ParameterOscillator();
    Check.that("frisch angelegt laeuft nichts", !osc.running());
    Check.near("erster Aufruf startet die Phase, also die Mitte",
        50.0, osc.value(1000.0, 20f, 0f, 100f), 1e-4);
    Check.that("danach laeuft der Zyklus", osc.running());
    Check.near("eine Viertelperiode spaeter das Maximum",
        100.0, osc.value(1005.0, 20f, 0f, 100f), 1e-3);
    Check.near("drei Viertel spaeter das Minimum",
        0.0, osc.value(1015.0, 20f, 0f, 100f), 1e-3);

    // Ausschalten und wieder einschalten faengt die Phase neu an - egal, wie
    // weit die Wanduhr inzwischen gelaufen ist
    osc.reset();
    Check.that("nach reset() laeuft nichts mehr", !osc.running());
    Check.near("nach dem Wiedereinschalten wieder die Mitte",
        50.0, osc.value(1234.5, 20f, 0f, 100f), 1e-4);
    Check.near("und von dort weiter aufwaerts",
        100.0, osc.value(1239.5, 20f, 0f, 100f), 1e-3);

    // Eine Periodenaenderung im laufenden Betrieb springt zwar, laeuft aber
    // weiter (der Phasenstart bleibt stehen) - kein Zuruecksetzen, kein NaN
    ParameterOscillator moving = new ParameterOscillator();
    moving.value(0.0, 20f, 0f, 100f);
    Check.near("halbierte Periode: nach 5 s eine halbe Periode, also Mitte",
        50.0, moving.value(5.0, 10f, 0f, 100f), 1e-3);

    System.exit(Check.report("ParameterOscillatorTest"));
  }
}
