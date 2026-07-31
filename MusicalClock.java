// Gemeinsame Beat-Phase fuer den Origin-Sequencer.
//
// Ohne Processing, ohne oscP5 und ohne eigene Wanduhr: die Zeit wird
// hereingegeben, damit die Klasse ohne Sketch-Laufzeit pruefbar ist. Gerufen
// wird sie aus drawMe(). Dasselbe Muster wie PresetScheduler.
//
// Die Phase wird AKKUMULIERT, nicht als (now - t0)/beatDuration gerechnet.
// Naiv gerechnet springt die Position bei jeder BPM-Aenderung: verdoppelt der
// Operator das Tempo, verdoppelt sich rueckwirkend auch die seit Sketch-Start
// verstrichene Beat-Zahl, und alle Tracks feuern schlagartig durcheinander.
// Akkumuliert aendert ein Tempowechsel nur die RATE - ein sauberes
// Accelerando statt eines Sprungs.
//
// Kein Reset bei Preset-Wechsel: MusicalClock und PresetScheduler wissen
// bewusst nichts voneinander, Preset-Timing bleibt Sekunden/Minuten.
class MusicalClock {

  static final float DEFAULT_BPM = 60f;

  private double beats = 0.0;

  // NaN heisst "noch nie getickt". Der erste advance() setzt nur den
  // Nullpunkt: die Zeitbasis ist System.currentTimeMillis()/1000, also
  // Groessenordnung 1.7e9 - ohne diese Regel stuende die Uhr beim ersten
  // Frame bei Milliarden von Beats.
  private double lastSeconds = Double.NaN;

  void advance(double nowSeconds, float bpm) {
    if (Double.isNaN(nowSeconds) || Double.isInfinite(nowSeconds)) {
      return;
    }
    if (Double.isNaN(lastSeconds)) {
      lastSeconds = nowSeconds;
      return;
    }
    double elapsed = nowSeconds - lastSeconds;
    lastSeconds = nowSeconds;
    // Ein Ruecksprung der Wanduhr (Zeitumstellung, NTP-Korrektur) darf die
    // Position nicht zurueckziehen - die Tracks haetten dann ihr nextBeat in
    // der Zukunft und schwiegen, bis die Uhr wieder aufgeholt hat.
    if (elapsed <= 0.0) {
      return;
    }
    beats += elapsed/beatDuration(bpm);
  }

  double beats() {
    return beats;
  }

  // Sekunden je Beat. Eine unbrauchbare BPM (0, negativ, NaN) faellt auf
  // DEFAULT_BPM zurueck statt eine Division durch 0 zu erzeugen - die
  // Parameter-Range im Sketch (20..200) schliesst das ohnehin aus, das hier
  // ist das Netz darunter.
  static float beatDuration(float bpm) {
    if (!(bpm > 0f)) { // faengt NaN mit ab, anders als bpm <= 0
      return 60f/DEFAULT_BPM;
    }
    return 60f/bpm;
  }

  // Laenge eines Notenwerts in Beats. 1 = Ganze = vier Beats, 4 = Viertel =
  // ein Beat, 16 = Sechzehntel = ein Viertelbeat.
  static double beatsPerNote(int noteValue) {
    if (noteValue <= 0) {
      return 1.0; // Viertel, wie noteValue 4
    }
    return 4.0/noteValue;
  }
}
