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
  // Ein Gewicht von 0 wird NIE gewaehlt (auch nicht bei random01 genau auf
  // seiner Grenze): ein ausgeschalteter Ausreisser darf nicht doch
  // gelegentlich zu hoeren sein. Negative Gewichte und NaN gelten als 0 statt
  // die Summe zu verfaelschen.
  //
  // Die Schleife selbst steht in WeightedChoice: SplitFanout zieht die Zahl
  // der Zweige und die Song-Struktur-Ebene ihre Uebergaenge nach genau
  // derselben Regel, und drei Kopien davon waeren drei Orte, an denen
  // "Gewicht 0 wird nie gewaehlt" auseinanderlaufen kann.
  static int pick(float[] weights, double random01) {
    return WeightedChoice.pick(weights, MULTIPLIERS.length, random01, NEUTRAL_INDEX);
  }
}
