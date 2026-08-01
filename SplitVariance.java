// Streuung der Kindwerte bei einer Aufspaltung an einem Node.
//
// Warum eine eigene Klasse: LedNetworkTransportEffect haengt an oscP5 und
// laesst sich von test/run.sh nicht uebersetzen. Die Formel soll aber geprueft
// sein - deshalb liegt sie hier, ohne Abhaengigkeit auf processing, oscP5 oder
// netP5. Dasselbe Muster wie ImpulseOscThrottle und ParameterOscillator.
//
// Der Zufall wird HEREINGEREICHT statt hier gezogen. Nur so ist die Formel
// deterministisch pruefbar; der Aufrufer gibt Math.random() hinein.
class SplitVariance {

  // Kleinster erlaubter Faktor. Bei amount=1 und random01=0 waere das Ergebnis
  // sonst exakt 0 - ein Kind mit Speed 0 stuende fuer immer still, ein Kind mit
  // decayScale 0 verloere nie Energie und stuerbe nie. Zwei unsterbliche
  // Zustaende, die das Netz ueber eine Nacht volllaufen lassen, jeweils ohne
  // Fehlermeldung.
  static final float MIN_FACTOR = 0.05f;

  // base * (1 + amount*(random01*2-1)), geklemmt auf mindestens
  // base*MIN_FACTOR.
  //
  // amount ist auf 0..1 geklemmt, random01 wird als 0..1 erwartet. Entartete
  // Eingaben (NaN) liefern den unveraenderten Ausgangswert statt NaN - die
  // Parameter-Range im Sketch schliesst sie ohnehin aus, das hier ist das Netz
  // darunter.
  //
  // Das Vorzeichen von base bleibt erhalten: bei der Geschwindigkeit codiert es
  // die Richtung, ein rueckwaerts laufendes Kind darf durch die Streuung nicht
  // die Richtung wechseln. Deshalb wird der FAKTOR geklemmt, nicht das Ergebnis.
  static float jitter(float base, float amount, double random01) {
    if (Float.isNaN(amount) || Double.isNaN(random01)) {
      return base;
    }
    float a = amount;
    if (a < 0f) {
      a = 0f;
    }
    if (a > 1f) {
      a = 1f;
    }
    double r = random01;
    if (r < 0.0) {
      r = 0.0;
    }
    if (r > 1.0) {
      r = 1.0;
    }
    double factor = 1.0 + a*(r*2.0 - 1.0);
    if (factor < MIN_FACTOR) {
      factor = MIN_FACTOR;
    }
    return (float) (base*factor);
  }
}
