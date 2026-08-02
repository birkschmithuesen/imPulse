// Wertbehaelter fuer die Pause-Gate-Einstellungen. Der Gate haelt ihn NICHT -
// der Effekt fuellt ihn in jedem Frame aus seinen RemoteControlled*Parametern
// und reicht ihn herein. Dasselbe Muster wie TrackConfig beim Sequencer und
// SongStructureConfig beim Director.
class PauseGateConfig {

  boolean enabled;

  // In TAKTEN (= 4 Beats), nicht Beats: dieselbe Einheit, in der ein
  // Operator ueber Notenwerte ("alle 8 Takte") denkt, nicht in der die Uhr
  // selbst rechnet.
  float checkIntervalBars;
  float probability;
  float lengthMinBars;
  float lengthMaxBars;

  // Welche der beiden bestehenden Spawn-Ebenen eine laufende Pause
  // stummschaltet. Beide unabhaengig schaltbar: ein Operator kann so nur den
  // Sequencer aussetzen lassen (strukturierte Stille, RandomSpawn laeuft als
  // Ambient-Rauschen weiter) oder beide zusammen fuer echte Totenstille.
  boolean affectsSequencer;
  boolean affectsRandomSpawn;

  static PauseGateConfig withDefaults() {
    PauseGateConfig cfg = new PauseGateConfig();
    // Kein Selbstlaeufer: enabled bleibt AUS, wie SongStructureConfig. Die
    // neue Ebene darf eine laufende Show nicht ohne Zutun stumm schalten.
    cfg.enabled = false;
    cfg.checkIntervalBars = 8f;
    cfg.probability = 0.25f;
    cfg.lengthMinBars = 2f;
    cfg.lengthMaxBars = 6f;
    cfg.affectsSequencer = true;
    cfg.affectsRandomSpawn = true;
    return cfg;
  }
}

// Periodische Ruhemomente, orthogonal zu Sequencer und RandomSpawn: der Gate
// baut selbst KEINE TravellingActivation und kennt keine Stripes - seine
// einzige Aufgabe ist, den beiden bestehenden Spawn-Ebenen gelegentlich den
// Mund zu verbieten. Schon fliegende Impulse laufen unbeeindruckt weiter,
// splitten, klingen aus - kein harter Stop, sondern: keine neuen Anfaenge.
//
// Tickt auf derselben Uhr (MusicalClock.beats()) wie der Sequencer, damit
// Pausen taktgenau sind statt sekundenbasiert. Ohne Processing, ohne oscP5,
// ohne eigene Wanduhr - Zeit, Konfiguration und Zufall werden hereingegeben,
// dasselbe Muster wie OriginSequencer und SongStructureDirector.
class PauseGate {

  // Kuerzestes erlaubtes Pruefintervall (1 Beat = 0.25 Takte) und kuerzeste
  // erlaubte Pausenlaenge (0.2 Beats = 0.05 Takte). Ohne diese Untergrenzen
  // wuerde ein Intervall/eine Laenge von 0 in JEDEM Frame neu wuerfeln bzw.
  // eine Pause im selben Frame wieder beenden, in dem sie beginnt - dieselbe
  // Vorsichtsmassnahme wie OriginSequencer.MIN_INTERVAL_BEATS.
  static final double MIN_CHECK_INTERVAL_BEATS = 1.0;
  static final double MIN_LENGTH_BEATS = 0.2;

  private double nextCheckBeat = 0.0;
  private double pauseEndBeat = Double.NEGATIVE_INFINITY;
  private boolean started = false;
  private boolean paused = false;
  private boolean affectsSequencer = true;
  private boolean affectsRandomSpawn = true;
  private String message = "";

  boolean isPaused() {
    return paused;
  }

  // Ob die jeweilige Spawn-Ebene JETZT stummgeschaltet ist - das ist die
  // einzige Schnittstelle, die die beiden Spawn-Pfade im Effekt brauchen.
  boolean blocksSequencer() {
    return paused && affectsSequencer;
  }

  boolean blocksRandomSpawn() {
    return paused && affectsRandomSpawn;
  }

  String lastMessage() {
    return message;
  }

  // Aus drawMe() zu rufen, VOR beiden Spawn-Ebenen, mit der schon fuer diesen
  // Frame fortgeschriebenen Beat-Position (MusicalClock.beats()).
  void tick(double beats, PauseGateConfig cfg, RandomSource rnd) {
    if (cfg == null || rnd == null || Double.isNaN(beats)) {
      paused = false;
      return;
    }
    affectsSequencer = cfg.affectsSequencer;
    affectsRandomSpawn = cfg.affectsRandomSpawn;

    if (!cfg.enabled) {
      // Not-Aus: eine laufende Pause endet sofort, kein Nachwirken.
      // nextCheckBeat bleibt synchron mit der Uhr, damit ein spaeteres
      // Wiedereinschalten nicht sofort einen waehrend der Aus-Phase
      // aufgelaufenen Wurf ausloest - dieselbe Regel wie
      // SongStructureDirector.isDue() bei enabled=false.
      nextCheckBeat = beats;
      pauseEndBeat = Double.NEGATIVE_INFINITY;
      paused = false;
      started = false;
      return;
    }
    if (!started) {
      // Der allererste Tick setzt nur den Nullpunkt - sonst kaeme beim
      // Einschalten sofort ein Wurf, bevor der Operator ueberhaupt seine
      // Werte gesetzt hat. Dieselbe Regel wie OriginSequencer.update().
      started = true;
      nextCheckBeat = beats + intervalBeats(cfg);
      pauseEndBeat = Double.NEGATIVE_INFINITY;
      paused = false;
      return;
    }

    boolean wasPaused = paused;
    paused = beats < pauseEndBeat;
    if (wasPaused && !paused) {
      // Eine Pause ist GERADE zu Ende gegangen: sofort neu wuerfeln, statt den
      // Rest des starren checkIntervalBars-Rasters abzuwarten. Ohne dieses
      // Vorziehen bezoege sich die probability nicht auf einen Zeitanteil,
      // sondern auf ein Raster: bei 32 Beats Pruefintervall und 8 Beats
      // Pausenlaenge lief der Sequencer nach jeder Pause 24 Beats lang ohne
      // jeden Bezug zur eingestellten Wahrscheinlichkeit weiter, der
      // tatsaechliche Stille-Anteil war also nur
      // probability * (Pausenlaenge / Pruefintervall).
      nextCheckBeat = beats;
    }
    if (beats < nextCheckBeat) {
      return;
    }
    nextCheckBeat = beats + intervalBeats(cfg);
    if (paused) {
      // Waehrend einer laufenden Pause wird nicht neu gewuerfelt - eine
      // zweite Pause kann keine laufende ueberschreiben oder verlaengern.
      return;
    }

    double roll = clamp01(rnd.next());
    if (roll < clamp01(cfg.probability)) {
      double length = drawLength(cfg, rnd);
      pauseEndBeat = beats + length;
      paused = true;
      message = "Pause gestartet, " + length + " Beats";
    } else {
      message = "";
    }
  }

  private double intervalBeats(PauseGateConfig cfg) {
    double beats4 = (double) cfg.checkIntervalBars*4.0;
    if (!(beats4 >= MIN_CHECK_INTERVAL_BEATS)) { // faengt NaN mit ab
      return MIN_CHECK_INTERVAL_BEATS;
    }
    return beats4;
  }

  // Gleichverteilt innerhalb der Spanne, in Beats. Vertauschte Grenzen (eine
  // Fehlbedienung im UI) werden getauscht statt abzustuerzen - dieselbe
  // Regel wie SongStructureDirector.drawDwell().
  private double drawLength(PauseGateConfig cfg, RandomSource rnd) {
    double lo = (double) cfg.lengthMinBars*4.0;
    double hi = (double) cfg.lengthMaxBars*4.0;
    if (lo > hi) {
      double swap = lo; lo = hi; hi = swap;
    }
    double length = lo + (hi-lo)*clamp01(rnd.next());
    if (!(length >= MIN_LENGTH_BEATS)) {
      return MIN_LENGTH_BEATS;
    }
    return length;
  }

  private static double clamp01(double v) {
    if (Double.isNaN(v)) {
      return 0.5;
    }
    if (v < 0.0) {
      return 0.0;
    }
    if (v > 1.0) {
      return 1.0;
    }
    return v;
  }
}
