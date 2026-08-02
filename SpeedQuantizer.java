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
  // Die Schleife selbst steht in WeightedChoice, weil SplitFanout, SplitStagger
  // und die Song-Struktur-Ebene dieselbe brauchen (siehe
  // /net/impulse/split/weight/* und die Uebergangsmatrix ueber die vier
  // Energie-Level). Zwei Kopien davon waeren zwei Orte, an denen "Gewicht 0
  // wird nie gewaehlt" auseinanderlaufen kann. Der entartete Fall faellt dort
  // auf NEUTRAL_INDEX - ein Impuls mit der Referenzgeschwindigkeit ist immer
  // ein gueltiger Impuls.
  static int pick(float[] weights, double random01) {
    return WeightedChoice.pick(weights, MULTIPLIERS.length, NEUTRAL_INDEX, random01);
  }

  // Kopplung Speed-Klasse -> decayScale (Kettenreaktions-Fix, 2026-08-02):
  // derselbe Multiplikator M wie bei der Geschwindigkeit. energy -=
  // timeStep*impulseLifetime*decayScale ist zeitbasiert, nicht streckenbasiert
  // - ohne diese Kopplung legt ein M-fach schneller Impuls die M-fache
  // Strecke bis zum Energie-Aus zurueck, trifft M-mal mehr Kreuzungen und
  // splittet sich dort M-mal mehr, jede Generation eskaliert weiter. Mit
  // decayScale = M zerfaellt er auch M-mal schneller, die Grunddistanz bleibt
  // ueber alle Klassen gleich.
  //
  // Eigene Methode statt einfach multiplierAt() an der Aufrufstelle zu
  // benutzen: der Zusammenhang "Speed-Klasse und decayScale sind dieselbe
  // Zahl" ist eine bewusste Design-Entscheidung, keine zufaellige
  // Uebereinstimmung - eine eigene Methode macht das an der Aufrufstelle
  // lesbar und haelt die Kopplung an EINER Stelle aenderbar, falls sie sich
  // je von 1:1 loesen soll.
  static float decayScaleFor(int index) {
    return multiplierAt(index);
  }
}
