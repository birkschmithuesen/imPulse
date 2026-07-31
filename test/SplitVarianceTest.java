public class SplitVarianceTest {

  public static void main(String[] args) throws Exception {
    // amount 0 laesst den Ausgangswert exakt stehen - das ist der
    // Auslieferungszustand beider Split-Parameter, und er muss bitgleich
    // dem bisherigen Verhalten entsprechen, egal welchen Zufall er bekommt
    Check.near("amount 0, Zufall 0", 16.0, SplitVariance.jitter(16f, 0f, 0.0), 1e-6);
    Check.near("amount 0, Zufall 0.5", 16.0, SplitVariance.jitter(16f, 0f, 0.5), 1e-6);
    Check.near("amount 0, Zufall 1", 16.0, SplitVariance.jitter(16f, 0f, 1.0), 1e-6);

    // Zufall 0.5 ist die Mitte des Intervalls und damit neutral,
    // unabhaengig von der Staerke
    Check.near("Zufall 0.5 ist neutral", 16.0, SplitVariance.jitter(16f, 1f, 0.5), 1e-6);
    Check.near("Zufall 0.5 ist auch bei kleiner Staerke neutral",
        16.0, SplitVariance.jitter(16f, 0.2f, 0.5), 1e-6);

    // Die Enden des Zufallsbereichs spannen +-amount auf
    Check.near("Zufall 1 bei Staerke 0.5 gibt das 1.5-fache",
        24.0, SplitVariance.jitter(16f, 0.5f, 1.0), 1e-5);
    Check.near("Zufall 0 bei Staerke 0.5 gibt das 0.5-fache",
        8.0, SplitVariance.jitter(16f, 0.5f, 0.0), 1e-5);

    // Symmetrie um den Ausgangswert: die zwei Enden liegen gleich weit weg
    double hoch = SplitVariance.jitter(100f, 0.3f, 1.0);
    double tief = SplitVariance.jitter(100f, 0.3f, 0.0);
    Check.near("Symmetrie um den Ausgangswert", 100.0, (hoch + tief)/2.0, 1e-4);

    // Untergrenze: bei Staerke 1 und Zufall 0 waere der Faktor exakt 0.
    // Ein Kind mit Speed 0 stuende fuer immer still, eines mit decayScale 0
    // stuerbe nie - zwei unsterbliche Zustaende, die das Netz ueber eine
    // Nacht volllaufen lassen.
    float voll = SplitVariance.jitter(16f, 1f, 0.0);
    Check.that("volle Staerke wird nicht 0", voll > 0f);
    Check.near("sondern auf MIN_FACTOR geklemmt",
        16.0*SplitVariance.MIN_FACTOR, voll, 1e-5);

    // Auch knapp ueber der Grenze wird geklemmt
    Check.near("knapp unterhalb der Grenze ebenfalls geklemmt",
        16.0*SplitVariance.MIN_FACTOR, SplitVariance.jitter(16f, 1f, 0.01), 1e-4);

    // Entartete Eingaben duerfen sich nicht in den LED-Puffer fortpflanzen
    Check.near("NaN-Staerke faellt auf den Ausgangswert zurueck",
        16.0, SplitVariance.jitter(16f, Float.NaN, 0.3), 1e-6);
    Check.near("NaN-Zufall faellt auf den Ausgangswert zurueck",
        16.0, SplitVariance.jitter(16f, 0.5f, Double.NaN), 1e-6);
    Check.near("negative Staerke wirkt wie 0",
        16.0, SplitVariance.jitter(16f, -0.5f, 0.0), 1e-6);
    Check.near("Staerke ueber 1 wird auf 1 geklemmt",
        16.0*SplitVariance.MIN_FACTOR, SplitVariance.jitter(16f, 5f, 0.0), 1e-5);

    // Negative Ausgangswerte kommen vor: speed traegt die Richtung im
    // Vorzeichen, ein rueckwaerts laufendes Kind hat negative Speed.
    // Der Betrag muss genauso streuen, das Vorzeichen erhalten bleiben.
    Check.near("negative Speed behaelt ihr Vorzeichen",
        -24.0, SplitVariance.jitter(-16f, 0.5f, 1.0), 1e-5);
    Check.that("negative Speed wird nicht positiv",
        SplitVariance.jitter(-16f, 1f, 0.0) < 0f);

    // Ausgangswert 0 bleibt 0, ohne NaN
    Check.near("Ausgangswert 0 bleibt 0", 0.0, SplitVariance.jitter(0f, 0.5f, 0.9), 1e-9);

    System.exit(Check.report("SplitVarianceTest"));
  }
}
