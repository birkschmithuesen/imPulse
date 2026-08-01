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
  // muss nicht 100 sein und wird normalisiert - ein Operator dreht einzelne
  // Regler, ohne den Rest nachzurechnen.
  //
  // Die Ziehung selbst steht in WeightedChoice, weil SplitFanout dieselbe
  // braucht (siehe /net/impulse/split/weight/*). Alle Regeln dort:
  // Gewicht 0 zieht nie, NaN und negative Werte gelten als 0, der entartete
  // Fall faellt auf NEUTRAL_INDEX - ein Impuls mit der Referenzgeschwindigkeit
  // ist immer ein gueltiger Impuls.
  static int pick(float[] weights, double random01) {
    return WeightedChoice.pick(weights, MULTIPLIERS.length, NEUTRAL_INDEX, random01);
  }
}
