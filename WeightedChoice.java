// Gewichtete Ziehung aus einer festen Zahl von Kategorien.
//
// Herausgezogen aus SpeedQuantizer, als SplitFanout dieselbe Rechnung brauchte
// (siehe /net/impulse/split/weight/*). Zwei Kopien waeren zwei Regeln fuer
// dieselbe Sache: eine Nachbesserung an der einen - etwa die Behandlung von
// NaN oder von random01 == 1.0 - ginge an der anderen still vorbei, und beide
// entscheiden, ob ein auf 0 gedrehter Regler wirklich nie zieht.
//
// Ohne Processing und ohne oscP5, damit test/run.sh die Rechnung uebersetzen
// kann - dasselbe Muster wie SplitVariance, MusicalClock und ImpulseOscThrottle.
class WeightedChoice {

  // Index der gezogenen Kategorie aus weights[0..count-1]. Die Summe muss
  // NICHT 100 sein und wird hier normalisiert - ein Operator dreht einzelne
  // Regler, ohne den Rest nachzurechnen.
  //
  // Ein Gewicht von 0 wird NIE gewaehlt (auch nicht bei random01 genau auf
  // seiner Grenze): ein ausgeschalteter Ausreisser darf nicht doch
  // gelegentlich vorkommen. Negative Gewichte und NaN gelten als 0 statt die
  // Summe zu verfaelschen.
  //
  // fallbackIndex ist die Kategorie fuer den entarteten Fall (kein Gewicht,
  // NaN-Zufall, zu kurzes Array) - der Aufrufer waehlt sie so, dass sie sein
  // bisheriges Verhalten bedeutet.
  static int pick(float[] weights, int count, int fallbackIndex, double random01) {
    if (weights == null || count <= 0 || weights.length < count) {
      return fallbackIndex;
    }
    if (Double.isNaN(random01)) {
      return fallbackIndex;
    }
    double total = 0.0;
    for (int i = 0; i < count; i++) {
      double w = weights[i];
      if (w > 0.0) { // faengt NaN und negative Werte mit ab
        total += w;
      }
    }
    if (!(total > 0.0)) {
      return fallbackIndex;
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
    int last = fallbackIndex;
    for (int i = 0; i < count; i++) {
      double w = weights[i];
      if (!(w > 0.0)) {
        continue;
      }
      last = i; // letzte Kategorie MIT Gewicht, fuer r == 1.0
      cumulative += w;
      if (target < cumulative) {
        return i;
      }
    }
    return last;
  }
}
