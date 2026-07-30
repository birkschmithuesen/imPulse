// Sanfter Sinus-Oszillator fuer genau einen fernsteuerbaren Parameter.
//
// Formel (ein voller Auf-Ab-Zyklus dauert periodSeconds):
//   wert = min + (max-min) * (0.5 + 0.5*sin(2*PI*elapsedSeconds/periodSeconds))
// Bei elapsedSeconds=0 steht der Wert also in der Mitte zwischen min und max
// und steigt zunaechst; nach einer Viertelperiode ist max erreicht, nach drei
// Vierteln min.
//
// Warum eine eigene Klasse: LedNetworkTransportEffect haengt an oscP5 und
// laesst sich von test/run.sh nicht uebersetzen. Die Formel soll aber geprueft
// sein - deshalb liegt sie hier, ohne Abhaengigkeit auf processing, oscP5 oder
// netP5. Dasselbe Muster wie ImpulseOscThrottle.
class ParameterOscillator {

  // Zeitpunkt, an dem der laufende Zyklus begonnen hat. NaN heisst "derzeit
  // aus": der naechste value()-Aufruf setzt die Phase auf 0.
  //
  // Die verstrichene Zeit laeuft damit bewusst ab dem EINSCHALTEN, nicht ab
  // Sketch-Start - der Einschaltmoment ist der einzige Zeitpunkt, den ein
  // Operator vor sich hat, und so beginnt jeder Randomizer reproduzierbar in
  // der Mitte seines Bereichs statt an einer beliebigen Stelle der Kurve.
  private double phaseStart = Double.NaN;

  // Wert fuer den laufenden Frame. Der erste Aufruf nach reset() (bzw. nach
  // dem Anlegen) beginnt einen frischen Zyklus.
  float value(double nowSeconds, float periodSeconds, float min, float max) {
    if (Double.isNaN(phaseStart)) {
      phaseStart = nowSeconds;
    }
    return sineOscillate(nowSeconds - phaseStart, periodSeconds, min, max);
  }

  // Abgeschaltet - der naechste value()-Aufruf faengt wieder bei Phase 0 an.
  void reset() {
    phaseStart = Double.NaN;
  }

  boolean running() {
    return !Double.isNaN(phaseStart);
  }

  // Die reine Formel, ohne Zustand.
  //
  // Eine unbrauchbare Periode (0, negativ, NaN) liefert die Mitte zwischen min
  // und max, also genau den Wert bei Phase 0 - kein NaN und keine Division
  // durch 0, die sich bis in den LED-Puffer fortpflanzen wuerde. Die
  // Parameter-Range im Sketch schliesst solche Werte ohnehin aus, das hier ist
  // das Netz darunter.
  //
  // min > max ist erlaubt und laeuft schlicht spiegelverkehrt: der Wert
  // schwingt weiterhin zwischen den beiden Grenzen, nur beginnt er nach unten.
  static float sineOscillate(double elapsedSeconds, float periodSeconds, float min, float max) {
    double unit = 0.5;
    if (periodSeconds > 0f && !Double.isNaN(elapsedSeconds)
        && !Double.isInfinite(elapsedSeconds)) {
      unit = 0.5 + 0.5*Math.sin(2.0*Math.PI*elapsedSeconds/periodSeconds);
    }
    return (float) (min + (max - min)*unit);
  }
}
