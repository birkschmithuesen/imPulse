public class PauseGateTest {

  // Zufallsquelle mit fest vorgegebener Folge - laeuft die Folge aus, beginnt
  // sie von vorn. Dasselbe Muster wie OriginSequencerTest.FixedRandom.
  static class FixedRandom implements RandomSource {
    private final double[] values;
    private int pos = 0;
    FixedRandom(double... values) { this.values = values; }
    public double next() {
      double v = values[pos % values.length];
      pos++;
      return v;
    }
  }

  static PauseGateConfig off() {
    PauseGateConfig cfg = new PauseGateConfig();
    cfg.enabled = false;
    cfg.checkIntervalBars = 8f;
    cfg.probability = 0.25f;
    cfg.lengthMinBars = 2f;
    cfg.lengthMaxBars = 6f;
    cfg.affectsSequencer = true;
    cfg.affectsRandomSpawn = true;
    return cfg;
  }

  static PauseGateConfig on() {
    PauseGateConfig cfg = off();
    cfg.enabled = true;
    return cfg;
  }

  public static void main(String[] args) throws Exception {
    // ---- enabled=false: nie pausiert, egal was der Zufall sagt ----
    PauseGate g0 = new PauseGate();
    PauseGateConfig aus = off();
    for (int i = 0; i <= 200; i++) {
      g0.tick(i*0.5, aus, new FixedRandom(0.0)); // 0.0 traefe jede probability
      Check.that("bei enabled=false nie pausiert", !g0.isPaused());
      Check.that("blockiert auch keine Ebene", !g0.blocksSequencer() && !g0.blocksRandomSpawn());
    }

    // ---- Auslieferungswerte: withDefaults() ist aus ----
    Check.that("Auslieferungszustand ist enabled=false", !PauseGateConfig.withDefaults().enabled);

    // ---- Der allererste Tick setzt nur den Nullpunkt, pausiert nicht ----
    PauseGate g1 = new PauseGate();
    PauseGateConfig cfg1 = on();
    g1.tick(0.0, cfg1, new FixedRandom(0.0)); // 0.0 traefe probability 0.25 sofort
    Check.that("erster Tick pausiert nicht", !g1.isPaused());

    // ---- probability=1: bei Faelligkeit IMMER eine Pause ----
    PauseGate g2 = new PauseGate();
    PauseGateConfig immer = on();
    immer.probability = 1f;
    immer.checkIntervalBars = 8f; // 32 Beats
    immer.lengthMinBars = 2f;     // 8 Beats
    immer.lengthMaxBars = 2f;     // exakt 8 Beats, keine Streuung
    g2.tick(0.0, immer, new FixedRandom(0.99)); // Nullpunkt
    g2.tick(10.0, immer, new FixedRandom(0.99));
    Check.that("noch nicht faellig", !g2.isPaused());
    g2.tick(32.0, immer, new FixedRandom(0.99));
    Check.that("nach 32 Beats (8 Takte) faellig und pausiert", g2.isPaused());
    Check.that("blockiert Sequencer per Default", g2.blocksSequencer());
    Check.that("blockiert RandomSpawn per Default", g2.blocksRandomSpawn());
    g2.tick(39.9, immer, new FixedRandom(0.99));
    Check.that("kurz vor Pausenende noch pausiert", g2.isPaused());
    g2.tick(40.1, immer, new FixedRandom(0.99));
    Check.that("nach 8 Beats Pausenlaenge vorbei", !g2.isPaused());

    // ---- probability=0: nie eine Pause, egal wie oft geprueft wird ----
    PauseGate g3 = new PauseGate();
    PauseGateConfig nie = on();
    nie.probability = 0f;
    nie.checkIntervalBars = 1f; // 4 Beats, oft pruefen
    g3.tick(0.0, nie, new FixedRandom(0.0));
    for (int i = 1; i <= 200; i++) {
      g3.tick(i*4.0, nie, new FixedRandom(0.0)); // 0.0 waere < jede positive probability
      Check.that("probability 0 pausiert nie", !g3.isPaused());
    }

    // ---- affectsSequencer/affectsRandomSpawn unabhaengig schaltbar ----
    PauseGate g4 = new PauseGate();
    PauseGateConfig nurSeq = on();
    nurSeq.probability = 1f;
    nurSeq.affectsSequencer = true;
    nurSeq.affectsRandomSpawn = false;
    g4.tick(0.0, nurSeq, new FixedRandom(0.5));
    g4.tick(nurSeq.checkIntervalBars*4.0, nurSeq, new FixedRandom(0.5));
    Check.that("pausiert grundsaetzlich", g4.isPaused());
    Check.that("blockiert Sequencer", g4.blocksSequencer());
    Check.that("blockiert RandomSpawn NICHT", !g4.blocksRandomSpawn());

    PauseGate g5 = new PauseGate();
    PauseGateConfig nurRandom = on();
    nurRandom.probability = 1f;
    nurRandom.affectsSequencer = false;
    nurRandom.affectsRandomSpawn = true;
    g5.tick(0.0, nurRandom, new FixedRandom(0.5));
    g5.tick(nurRandom.checkIntervalBars*4.0, nurRandom, new FixedRandom(0.5));
    Check.that("blockiert Sequencer NICHT", !g5.blocksSequencer());
    Check.that("blockiert RandomSpawn", g5.blocksRandomSpawn());

    // ---- Waehrend einer laufenden Pause wird nicht neu gewuerfelt ----
    // checkIntervalBars klein genug, dass mehrere Pruefungen in eine
    // laufende Pause fallen wuerden, wenn die Klasse das nicht verhindert.
    PauseGate g6 = new PauseGate();
    PauseGateConfig kurzTakt = on();
    kurzTakt.probability = 1f;
    kurzTakt.checkIntervalBars = 1f;  // 4 Beats
    kurzTakt.lengthMinBars = 3.5f;    // 14 Beats, absichtlich NICHT auf dem Checkraster
    kurzTakt.lengthMaxBars = 3.5f;
    g6.tick(0.0, kurzTakt, new FixedRandom(0.0));
    g6.tick(4.0, kurzTakt, new FixedRandom(0.0)); // startet Pause bis Beat 18
    for (double b = 8.0; b < 18.0; b += 4.0) {
      g6.tick(b, kurzTakt, new FixedRandom(0.0));
      Check.that("bleibt waehrend der laufenden Pause pausiert", g6.isPaused());
    }
    // Abfrage AUSSERHALB des Checkrasters (naechster Check waere erst bei 20),
    // damit der Test nur das natuerliche Pausenende prueft, nicht einen neuen
    // Wurf, der bei probability=1 sofort wieder eine Pause startete.
    g6.tick(18.5, kurzTakt, new FixedRandom(0.0));
    Check.that("Pause endet wie urspruenglich gezogen, nicht verlaengert", !g6.isPaused());

    // ---- Vertauschte min/max-Laenge stuerzt nicht ab ----
    PauseGate g7 = new PauseGate();
    PauseGateConfig vertauscht = on();
    vertauscht.probability = 1f;
    vertauscht.lengthMinBars = 6f;
    vertauscht.lengthMaxBars = 2f; // max < min, Fehlbedienung im UI
    g7.tick(0.0, vertauscht, new FixedRandom(0.5));
    g7.tick(vertauscht.checkIntervalBars*4.0, vertauscht, new FixedRandom(0.5));
    Check.that("vertauschte Grenzen fuehren trotzdem zu einer Pause", g7.isPaused());

    // ---- Wiedereinschalten nach enabled=false feuert nicht sofort ----
    PauseGate g8 = new PauseGate();
    PauseGateConfig anAus = on();
    anAus.probability = 1f;
    g8.tick(0.0, anAus, new FixedRandom(0.5));
    PauseGateConfig aus8 = off();
    for (int i = 1; i <= 50; i++) {
      g8.tick(i*4.0, aus8, new FixedRandom(0.5));
      Check.that("waehrend Aus-Phase nie pausiert", !g8.isPaused());
    }
    g8.tick(200.5, anAus, new FixedRandom(0.5)); // erster Tick nach dem Wiedereinschalten
    Check.that("direkt nach Wiedereinschalten noch keine Pause", !g8.isPaused());

    // ---- NaN-Beat-Position stuerzt nicht ab, pausiert nicht ----
    PauseGate g9 = new PauseGate();
    g9.tick(Double.NaN, on(), new FixedRandom(0.0));
    Check.that("NaN-Beat pausiert nicht", !g9.isPaused());

    // ---- null-Config stuerzt nicht ab ----
    PauseGate g10 = new PauseGate();
    g10.tick(5.0, null, new FixedRandom(0.0));
    Check.that("null-Config pausiert nicht", !g10.isPaused());

    System.exit(Check.report("PauseGateTest"));
  }
}
