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

  // Obergrenze fuer decayScale ueber mehrere Split-Generationen hinweg
  // (Kettenreaktions-Fix, 2026-08-02).
  //
  // Ein MAX_FACTOR analog zu MIN_FACTOR in jitter() selbst haette das
  // eigentliche Problem NICHT geloest: der Faktor je Aufruf ist durch
  // amount<=1 schon auf hoechstens 2.0 begrenzt (amount=1, random01=1 ->
  // factor=2), das ist keine Kettenreaktion, sondern eine einzelne Streuung.
  // Das Risiko entsteht erst durch WIEDERHOLTES Anwenden: seit Aenderung 2
  // erben Split-Kinder den decayScale des Elternimpulses als Jitter-BASIS
  // (curActivation.decayScale statt 1f) - bei mehreren Split-Generationen in
  // Folge kann jede einzelne bis zu verdoppeln, macht ueber vier
  // Generationen theoretisch das 16-fache. Die Klemmung gehoert deshalb an
  // den AUFRUFER (den Wert selbst, nach jitter()), nicht in die Formel.
  //
  // Obergrenze 32: die hoechste Speed-Klasse (SpeedQuantizer, 8x) bekommt per
  // Kopplung decayScale 8. Ein einzelner Split-Jitter-Schritt kann das auf
  // bis zu 16 verdoppeln - 32 laesst also noch einen zweiten vollen
  // Verdopplungsschritt zu, deckelt aber ein unbegrenztes Aufschaukeln ueber
  // viele Generationen. Bei Auslieferungswerten (splitLifetimeJitter=0)
  // greift die Klemmung nie - jitter() liefert dann exakt die Jitter-Basis
  // zurueck.
  static final float MAX_DECAY_SCALE = 32f;

  static float clampDecayScale(float value) {
    if (Float.isNaN(value)) {
      return value;
    }
    if (value > MAX_DECAY_SCALE) {
      return MAX_DECAY_SCALE;
    }
    if (value < -MAX_DECAY_SCALE) {
      return -MAX_DECAY_SCALE;
    }
    return value;
  }
}
