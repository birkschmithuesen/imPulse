// Zieht einen Index aus einer Liste von Gewichten.
//
// Herausgezogen aus SpeedQuantizer.pick(), weil zwei weitere Stellen dieselbe
// Rechnung brauchen: SplitFanout zieht damit die Zahl der Zweige einer
// Aufspaltung (/net/impulse/split/weight/*), die Song-Struktur-Ebene eine
// Zeile ihrer Uebergangsmatrix (vier Gewichte). Drei Kopien derselben Schleife
// waeren drei Orte, an denen "Gewicht 0 wird nie gezogen" auseinanderlaufen
// kann - und genau diese Eigenschaft ist an allen dreien die, auf die man sich
// verlaesst.
//
// Ohne Processing und ohne oscP5, damit test/run.sh die Klasse uebersetzen
// kann - dasselbe Muster wie SplitVariance und ImpulseOscThrottle. Der Zufall
// wird als fertige Zahl hereingereicht, nicht hier gezogen.
class WeightedChoice {

  // Index aus weights[0..count-1], gezogen nach ihren Anteilen.
  //
  // Die Summe muss NICHT 1 oder 100 sein und wird hier normalisiert: ein
  // Operator dreht einen einzelnen Regler, ohne die uebrigen nachzurechnen.
  //
  // Ein Gewicht von 0 wird NIE gezogen, auch nicht bei random01 genau auf
  // seiner Grenze - ein ausgeschalteter Uebergang darf nicht doch gelegentlich
  // vorkommen. Negative Gewichte und NaN gelten als 0, statt die Summe zu
  // verfaelschen.
  //
  // fallbackIndex kommt heraus, wenn ueberhaupt nichts zu ziehen ist (kein
  // Array, count unbrauchbar, alle Gewichte 0, NaN als Zufallswert). Ein
  // Rueckgabewert wie -1 wuerde den Aufrufer zwingen, den Fehlerfall an jeder
  // Aufrufstelle erneut zu behandeln; die eine sinnvolle Antwort kennt er
  // ohnehin (die 1x-Klasse, "alle Zweige", das Level "mittel").
  static int pick(float[] weights, int count, double random01, int fallbackIndex) {
    if (weights == null || count <= 0 || count > weights.length) {
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
      last = i; // letzter Index MIT Gewicht, fuer r == 1.0
      cumulative += w;
      if (target < cumulative) {
        return i;
      }
    }
    return last;
  }
}
