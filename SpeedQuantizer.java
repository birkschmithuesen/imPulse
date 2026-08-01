// Waehlt die Geschwindigkeitsklasse eines neu gespawnten Impulses.
//
// Nicht nur WANN gespawnt wird ist rhythmisch (siehe OriginSequencer),
// sondern auch WIE SCHNELL der einzelne Impuls reist: seine Geschwindigkeit
// ist ein rhythmisches Vielfaches der Referenz - Spiegelbild der Notenwerte,
// nur auf der Geschwindigkeit statt auf dem Zeitraster.
//
// Referenz (1x) ist /net/impulse/speed selbst, nicht ein eigener Parameter.
// Sonst gaebe es zwei Regler, die beide "die Geschwindigkeit" heissen, und
// die Zeitbasis-Kopplung (lifetime/nodeDeadTime/randomSpawn-Interval haengen
// an impulseSpeed) haette einen zweiten, unbeteiligten Bezugspunkt.
//
// Warum eine eigene Klasse: dasselbe Muster wie SplitVariance,
// ImpulseOscThrottle und OriginSequencer - LedNetworkTransportEffect haengt
// an oscP5 und laesst sich von test/run.sh nicht uebersetzen, die Rechnung
// soll aber geprueft sein. Der Zufall wird hereingereicht.
class SpeedQuantizer {

  // Fuenf Stufen im Verhaeltnis 1:2:4:8:16 - genau die Abstaende der
  // Notenwerte in OriginSequencer, nur auf der Geschwindigkeit.
  static final float[] MULTIPLIERS = { 0.5f, 1f, 2f, 4f, 8f };

  // Die 1x-Klasse. Fallback bei unbrauchbaren Gewichten: ein Impuls mit der
  // Referenzgeschwindigkeit ist immer ein gueltiger Impuls.
  static final int NEUTRAL_INDEX = 1;

  static float multiplierAt(int index) {
    if (index < 0 || index >= MULTIPLIERS.length) {
      return MULTIPLIERS[NEUTRAL_INDEX];
    }
    return MULTIPLIERS[index];
  }

  // Index der gezogenen Klasse. weights hat ein Gewicht je Klasse; die Summe
  // muss nicht 100 sein und wird hier normalisiert - ein Operator dreht
  // einzelne Regler, ohne den Rest nachzurechnen.
  //
  // Ein Gewicht von 0 wird NIE gewaehlt (auch nicht bei random01 genau auf
  // seiner Grenze): ein ausgeschalteter Ausreisser darf nicht doch
  // gelegentlich zu hoeren sein. Negative Gewichte und NaN gelten als 0 statt
  // die Summe zu verfaelschen.
  static int pick(float[] weights, double random01) {
    if (weights == null || weights.length < MULTIPLIERS.length) {
      return NEUTRAL_INDEX;
    }
    if (Double.isNaN(random01)) {
      return NEUTRAL_INDEX;
    }
    double total = 0.0;
    for (int i = 0; i < MULTIPLIERS.length; i++) {
      double w = weights[i];
      if (w > 0.0) { // faengt NaN und negative Werte mit ab
        total += w;
      }
    }
    if (!(total > 0.0)) {
      return NEUTRAL_INDEX;
    }
    double r = random01;
    if (r < 0.0) {
      r = 0.0;
    }
    if (r > 1.0) {
      r = 1.0;
    }
    double target = r*total;
    double cumulative = 0.0;
    int last = NEUTRAL_INDEX;
    for (int i = 0; i < MULTIPLIERS.length; i++) {
      double w = weights[i];
      if (!(w > 0.0)) {
        continue;
      }
      last = i; // letzte Klasse MIT Gewicht, fuer r == 1.0
      cumulative += w;
      if (target < cumulative) {
        return i;
      }
    }
    return last;
  }
}
