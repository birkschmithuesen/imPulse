// Entscheidet an einer Kreuzung, WIEVIELE der moeglichen Zweige einen
// Kind-Impuls bekommen und WELCHE.
//
// Bis hierher nahm jede Aufspaltung immer alle moeglichen Zweige, alle im
// selben Frame. Das Netz laeuft dadurch mit jeder Kreuzung dichter voll und
// jede Aufspaltung klingt gleich. Mit einer gezogenen Zweigzahl wird die
// Aufspaltung selbst zu einem gestaltbaren Ereignis: mal traegt sie den
// Impuls in alle Richtungen weiter, mal knickt sie ihn nur ab.
//
// Die drei Kategorien sind RELATIV zur Zahl der moeglichen Zweige beschrieben,
// nicht absolut ("2 Zweige"). Ein Knoten hat je nach Rand des Stripes und
// Richtung des Elternimpulses mal drei, mal weniger moegliche Zweige; eine
// absolute Zahl bedeutete an jedem Knoten etwas anderes und waere an manchen
// gar nicht erfuellbar. Bei zwei moeglichen Zweigen fallen "einer weniger"
// und "genau einer" zusammen - das ist die richtige Antwort, kein Fehler.
//
// Ohne Processing und ohne oscP5, damit test/run.sh die Rechnung uebersetzen
// kann - dasselbe Muster wie SplitVariance, SpeedQuantizer und OriginSequencer.
// Der Zufall wird hereingereicht.
class SplitFanout {

  // Index 0 = alle moeglichen Zweige, 1 = einer weniger, 2 = genau einer.
  static final int CATEGORY_COUNT = 3;

  // Der entartete Fall: alle Zweige. Das ist das Verhalten von vor diesem
  // Feature - eine unbrauchbare Gewichtstabelle darf die Installation nicht
  // leiser machen, sondern soll sie lassen, wie sie war.
  static final int NEUTRAL_INDEX = 0;

  // Zahl der Zweige, die tatsaechlich spawnen. Zwischen 1 und candidates,
  // ausser candidates ist selbst 0.
  //
  // Nie 0 bei vorhandenen Kandidaten: ein Impuls, der an einer Kreuzung
  // spurlos verschwindet, waere ein Loch im Netz ohne Fehlermeldung - und die
  // nodeDeadTime des Knotens waere trotzdem verbraucht.
  static int branchCount(float[] weights, int candidates, double random01) {
    if (candidates <= 0) {
      return 0;
    }
    int category = WeightedChoice.pick(weights, CATEGORY_COUNT, random01, NEUTRAL_INDEX);
    int take;
    if (category == 2) {
      take = 1;
    } else if (category == 1) {
      take = candidates - 1;
    } else {
      take = candidates;
    }
    if (take < 1) {
      take = 1;
    }
    if (take > candidates) {
      take = candidates;
    }
    return take;
  }

  // Welche Zweige, in welcher Reihenfolge. Liefert `take` verschiedene Indizes
  // aus 0..candidates-1 ueber einen partiellen Fisher-Yates-Shuffle -
  // dasselbe Ziehen ohne Zuruecklegen wie pickDistinctStripes() im Effekt.
  //
  // Die REIHENFOLGE ist Teil des Ergebnisses, nicht nur die Menge: sie
  // bestimmt, welcher Zweig sofort startet und welcher um einen Notenwert
  // versetzt (siehe SplitStagger). Waere sie die Kandidatenreihenfolge, laege
  // der erste Schlag immer auf demselben Stripe - ein stiller Bias, den
  // niemand am Regler sieht.
  static int[] chooseOrder(int candidates, int take, RandomSource rnd) {
    if (candidates <= 0 || take <= 0) {
      return new int[0];
    }
    int n = take > candidates ? candidates : take;
    int[] pool = new int[candidates];
    for (int i = 0; i < candidates; i++) {
      pool[i] = i;
    }
    for (int i = 0; i < n; i++) {
      double r = (rnd == null) ? 0.0 : rnd.next();
      // Ein Zufallswert ausserhalb 0..1 darf keinen Index ausserhalb des
      // Feldes erzeugen: ein IndexOutOfBounds mitten in drawMe() nimmt die
      // ganze Show mit.
      if (Double.isNaN(r) || r < 0.0) {
        r = 0.0;
      }
      if (r >= 1.0) {
        r = 0.999999;
      }
      int j = i + (int) (r*(candidates - i));
      if (j >= candidates) {
        j = candidates - 1;
      }
      int tmp = pool[i];
      pool[i] = pool[j];
      pool[j] = tmp;
    }
    int[] result = new int[n];
    System.arraycopy(pool, 0, result, 0, n);
    return result;
  }
}
