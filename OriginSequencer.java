// Zufallsquelle, die sich im Test durch eine feste Folge ersetzen laesst.
// Im Betrieb ist es Math.random(); ohne diese Naht haengt jede Erwartung
// eines Tests an einem echten Zufallsgenerator.
interface RandomSource {
  double next(); // 0..1
}

// Reiner Wertbehaelter fuer die Einstellungen eines Tracks. Der Sequencer
// haelt ihn NICHT - der Effekt fuellt ihn in jedem Frame aus seinen
// RemoteControlled*Parametern und reicht ihn herein. So kennt der Sequencer
// oscP5 nicht und bleibt pruefbar.
class TrackConfig {
  boolean enabled;
  int noteValue;            // 1/2/4/8/16, wird beim Lesen gerastet
  int repeatCount;          // Zyklen auf demselben Ursprung
  float energy;             // Spawn-Energie, vom Sequencer nur durchgereicht
  float swingJitter;        // 0 = exakt periodisch
  int originStripeOverride; // -1 = zufaellig, sonst fixer Stripe

  // Erlaubter Vorrat an Ursprungs-Stripes, oder null = alle.
  //
  // Gefuellt vom Effekt aus StripeTreeStore (Baum-Filter, siehe
  // /net/sequencer/track<N>/originTreeFilter). Der Sequencer kennt keine
  // Baeume -- er zieht nur aus dem, was er bekommt. Damit haengt seine
  // Pruefbarkeit nicht an einer Datei, und der Store bleibt fuer sich
  // pruefbar.
  //
  // Eine REFERENZ auf das im Store gecachte Array, keine Kopie: das hier
  // wird in jedem Frame gesetzt.
  int[] originPool;
}

// Entscheidet, welche Tracks in diesem Frame feuern und von welchem Stripe.
//
// Ohne Processing, ohne oscP5 und ohne eigene Wanduhr: die Beat-Position wird
// hereingegeben (aus MusicalClock), damit die Klasse ohne Sketch-Laufzeit
// pruefbar ist. Dasselbe Muster wie PresetScheduler und ImpulseOscThrottle.
//
// Baut ausdruecklich KEINE TravellingActivation - das bleibt im Effekt, der
// die Objekte, die Geschwindigkeit und die Stripe-Laenge kennt. Hier steht nur
// die Zeit- und Auswahllogik.
class OriginSequencer {

  static final int TRACK_COUNT = 6;

  // Die erlaubten Notenwerte. RemoteControlledIntParameter kann keine
  // Aufzaehlung, deshalb rastet quantizeNoteValue() beim Lesen.
  private static final int[] NOTE_VALUES = { 1, 2, 4, 8, 16 };

  // Kuerzestes Intervall, das ein Track haben kann. Bei swingJitter = 1 und
  // einem Zufallswert von 0 waere der Faktor exakt 0 - der Track wuerde in
  // JEDEM Frame feuern und das Netz in Sekunden fluten. Dieselbe
  // Vorsichtsmassnahme wie der Mindestabstand in spawnRandomImpulses().
  static final double MIN_INTERVAL_BEATS = 0.05;

  private final int nStripes;
  private final double[] nextBeat = new double[TRACK_COUNT];
  private final int[] repeatsLeft = new int[TRACK_COUNT];
  private final int[] origin = new int[TRACK_COUNT];
  private boolean started = false;

  OriginSequencer(int nStripes_) {
    nStripes = nStripes_ > 0 ? nStripes_ : 1;
  }

  int originOf(int track) {
    if (track < 0 || track >= TRACK_COUNT) {
      return 0;
    }
    return origin[track];
  }

  // Rastet einen beliebigen Reglerwert auf den naechstniedrigeren erlaubten
  // Notenwert. Ein Regler, der auf 5 stehen bleibt, verhaelt sich damit wie
  // 4, statt ein krummes Intervall zu erzeugen.
  static int quantizeNoteValue(int raw) {
    int best = NOTE_VALUES[0];
    for (int i = 0; i < NOTE_VALUES.length; i++) {
      if (NOTE_VALUES[i] <= raw) {
        best = NOTE_VALUES[i];
      }
    }
    return best;
  }

  // Liefert die Indizes der Tracks, die bei dieser Beat-Position feuern,
  // aufsteigend. Leeres Array, wenn keiner faellig ist.
  int[] update(double beats, TrackConfig[] cfg, RandomSource rnd) {
    if (cfg == null || rnd == null || Double.isNaN(beats)) {
      return new int[0];
    }
    // Der allererste Aufruf setzt nur den Nullpunkt. Sonst kaeme beim
    // Einschalten des Sequencers ein Schlag aus dem Nichts, bevor der
    // Operator ueberhaupt seine Trackparameter gesetzt hat.
    if (!started) {
      started = true;
      for (int i = 0; i < TRACK_COUNT; i++) {
        nextBeat[i] = beats + intervalOf(cfg, i, rnd, false);
      }
      return new int[0];
    }

    int[] scratch = new int[TRACK_COUNT];
    int found = 0;
    for (int i = 0; i < TRACK_COUNT && i < cfg.length; i++) {
      TrackConfig c = cfg[i];
      if (c == null || !c.enabled) {
        // Timer mitziehen, damit das Wiedereinschalten nicht sofort feuert -
        // dieselbe Regel wie PresetScheduler.isDue().
        nextBeat[i] = beats + intervalOf(cfg, i, rnd, false);
        continue;
      }
      if (beats < nextBeat[i]) {
        continue;
      }
      double interval = intervalOf(cfg, i, rnd, true);
      // Kein Nachholen: liegt nextBeat nach einem Haenger mehr als ein
      // Intervall zurueck, gibt es EINEN Treffer, keinen Schwall. Ein im
      // Hintergrund geparktes Fenster darf beim Zurueckkommen das Netz nicht
      // fluten - dieselbe Regel, die ImpulseOscThrottle durchsetzt.
      if (beats - nextBeat[i] > interval) {
        nextBeat[i] = beats + interval;
      } else {
        nextBeat[i] += interval;
      }
      advanceOrigin(i, c, rnd);
      scratch[found] = i;
      found++;
    }
    int[] result = new int[found];
    System.arraycopy(scratch, 0, result, 0, found);
    return result;
  }

  // Zieht bei Bedarf einen neuen Ursprung. Ein Override gewinnt immer und
  // ueberspringt die repeatCount-Buchfuehrung - er soll ja gerade fest stehen.
  private void advanceOrigin(int track, TrackConfig c, RandomSource rnd) {
    if (c.originStripeOverride >= 0) {
      int o = c.originStripeOverride;
      if (o >= nStripes) {
        o = nStripes - 1;
      }
      origin[track] = o;
      return;
    }
    if (repeatsLeft[track] <= 0) {
      origin[track] = pickStripe(rnd, c.originPool);
      int rc = c.repeatCount;
      if (rc < 1) {
        rc = 1;
      }
      repeatsLeft[track] = rc;
    }
    repeatsLeft[track]--;
  }

  // Zieht einen Ursprungs-Stripe. Ist ein Pool gesetzt, wird daraus gezogen,
  // sonst aus allen Stripes.
  //
  // Ein LEERER Pool zaehlt bewusst wie kein Pool: der Track soll dann wie
  // ohne Filter feuern, statt still zu verstummen. StripeTreeStore liefert
  // fuer einen leeren Baum zwar schon null, aber die Regel steht hier
  // nochmal - ein Aufrufer, der den Pool anders fuellt, soll denselben
  // Ausfallschutz bekommen.
  private int pickStripe(RandomSource rnd, int[] pool) {
    double r = rnd.next();
    if (Double.isNaN(r) || r < 0.0) {
      r = 0.0;
    }
    if (r >= 1.0) {
      r = 0.999999;
    }
    if (pool != null && pool.length > 0) {
      int idx = (int) (r*pool.length);
      if (idx < 0) {
        idx = 0;
      }
      if (idx >= pool.length) {
        idx = pool.length - 1;
      }
      int s = pool[idx];
      // Ein Pool-Eintrag ausserhalb des Netzes waere ein Datenfehler; hier
      // geklemmt statt einen Index-Fehler bis in den LED-Puffer zu tragen.
      if (s < 0) {
        s = 0;
      }
      if (s >= nStripes) {
        s = nStripes - 1;
      }
      return s;
    }
    int s = (int) (r*nStripes);
    if (s < 0) {
      s = 0;
    }
    if (s >= nStripes) {
      s = nStripes - 1;
    }
    return s;
  }

  // Intervall dieses Tracks in Beats, gegebenenfalls verjittert.
  //
  // withJitter=false fuer den Nullpunkt und fuer ausgeschaltete Tracks: dort
  // soll kein Zufallswert verbraucht werden, sonst haengt die Ursprungsfolge
  // eines laufenden Tracks davon ab, wieviele Tracks daneben aus sind.
  private double intervalOf(TrackConfig[] cfg, int i, RandomSource rnd, boolean withJitter) {
    TrackConfig c = (i < cfg.length) ? cfg[i] : null;
    int noteValue = (c == null) ? 4 : quantizeNoteValue(c.noteValue);
    double interval = MusicalClock.beatsPerNote(noteValue);
    if (withJitter && c != null && c.swingJitter > 0f) {
      interval = SplitVariance.jitter((float) interval, c.swingJitter, rnd.next());
    }
    if (!(interval >= MIN_INTERVAL_BEATS)) { // faengt NaN mit ab
      interval = MIN_INTERVAL_BEATS;
    }
    return interval;
  }
}
