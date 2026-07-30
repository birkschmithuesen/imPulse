import java.util.ArrayList;
import java.util.TreeSet;

public class LedPositionMapTest {

  // Kleine synthetische Geometrie. NICHT die echten 30 x 600 und NICHT
  // data/nodeCrossings.txt - die Kreuzungsdatei waechst waehrend der
  // Kalibrierung, ein Test dagegen waere beim naechsten S rot.
  static final int STRIPES = 4;
  static final int PER_STRIPE = 20;
  static final float FOOT_X = 14f;
  static final float FOOT_Y = 8f;
  // 20 LEDs auf 10 m: grosszuegiger Abstand, damit die Weglaengen-Warnung
  // aus Aufgabe 3 in diesen Tests nie anspringt und nichts verschleiert.
  static final float PITCH = 0.5f;
  static final double TOL = 1e-4;

  static final ArrayList<TreeSet<Integer>> NO_CROSSINGS = new ArrayList<TreeSet<Integer>>();

  static LedAnchorStore store() {
    return new LedAnchorStore(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y, PITCH);
  }

  static LedPositionMap map() {
    return new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
  }

  public static void main(String[] args) throws Exception {
    float[] out = new float[2];

    // ---- Stripe 0: zwei Anker, LED 4 -> (-3,-1) und LED 14 -> (2,1) ----
    // Richtung je Index: (0.5, 0.2)
    LedAnchorStore s = store();
    LedPositionMap m = map();
    Check.that("Anker bei LED 4 gesetzt", s.set(4, -3f, -1f, NO_CROSSINGS));
    Check.that("Anker bei LED 14 gesetzt", s.set(14, 2f, 1f, NO_CROSSINGS));

    // Exakt auf einem Anker
    Check.that("Anker selbst ist definiert", m.positionOf(s, 4, out));
    Check.near("Anker x", -3.0, out[0], TOL);
    Check.near("Anker y", -1.0, out[1], TOL);
    Check.that("ein Anker gilt als interpoliert", m.isInterpolatedAt(s, 4));

    // Genau in der Mitte
    Check.that("Mitte ist definiert", m.positionOf(s, 9, out));
    Check.near("Mitte x liegt auf der Haelfte", -0.5, out[0], TOL);
    Check.near("Mitte y liegt auf der Haelfte", 0.0, out[1], TOL);
    Check.that("die Mitte ist interpoliert", m.isInterpolatedAt(s, 9));

    // Proportional zum Index, nicht zur Reihenfolge der Anker
    Check.that("LED 6 ist definiert", m.positionOf(s, 6, out));
    Check.near("LED 6 x nach Indexanteil 0.2", -2.0, out[0], TOL);
    Check.near("LED 6 y nach Indexanteil 0.2", -0.6, out[1], TOL);

    // Fortsetzung des Vektors hinter dem letzten Anker
    Check.that("LED 19 ist definiert", m.positionOf(s, 19, out));
    Check.near("hinter dem letzten Anker x", 4.5, out[0], TOL);
    Check.near("hinter dem letzten Anker y", 2.0, out[1], TOL);
    Check.that("hinter dem letzten Anker gilt nicht als interpoliert",
        !m.isInterpolatedAt(s, 19));

    // Fortsetzung des Vektors vor dem ersten Anker
    Check.that("LED 0 ist definiert", m.positionOf(s, 0, out));
    Check.near("vor dem ersten Anker x", -5.0, out[0], TOL);
    Check.near("vor dem ersten Anker y", -1.8, out[1], TOL);
    Check.that("vor dem ersten Anker gilt nicht als interpoliert",
        !m.isInterpolatedAt(s, 0));

    // ---- Drei Anker: extrapoliert wird mit den zwei am Rand, nicht mit
    // dem ersten und letzten ----
    LedAnchorStore three = store();
    LedPositionMap m3 = map();
    Check.that("Anker LED 2", three.set(2, -6f, 0f, NO_CROSSINGS));
    Check.that("Anker LED 10", three.set(10, 0f, 0f, NO_CROSSINGS));
    Check.that("Anker LED 12", three.set(12, 1f, 1f, NO_CROSSINGS));
    // Rand-Vektor sind LED 10 und 12: (0.5, 0.5) je Index.
    // LED 16 -> (1 + 4*0.5, 1 + 4*0.5) = (3, 3)
    Check.that("LED 16 ist definiert", m3.positionOf(three, 16, out));
    Check.near("Extrapolation nimmt die zwei Anker am Rand, x", 3.0, out[0], TOL);
    Check.near("Extrapolation nimmt die zwei Anker am Rand, y", 3.0, out[1], TOL);

    // ---- Klemmung auf die Grundflaeche ----
    // Stripe 1: LED 0 -> (0,0), LED 1 -> (1,0.5). Steiler Vektor, LED 19
    // landet weit ausserhalb und muss geklemmt werden.
    LedAnchorStore steep = store();
    LedPositionMap mS = map();
    int base1 = PER_STRIPE;
    Check.that("steiler Anker A", steep.set(base1 + 0, 0f, 0f, NO_CROSSINGS));
    Check.that("steiler Anker B", steep.set(base1 + 1, 1f, 0.5f, NO_CROSSINGS));
    Check.that("LED 19 des steilen Stripes ist definiert",
        mS.positionOf(steep, base1 + 19, out));
    Check.near("x wird auf die halbe Grundflaeche geklemmt", 7.0, out[0], TOL);
    Check.near("y wird auf die halbe Grundflaeche geklemmt", 4.0, out[1], TOL);

    // ---- Nur ein Anker auf dem Stripe ----
    LedAnchorStore one = store();
    LedPositionMap m1 = map();
    int base2 = 2 * PER_STRIPE;
    Check.that("einzelner Anker", one.set(base2 + 5, 1.5f, -2f, NO_CROSSINGS));
    Check.that("erste LED des Stripes ist definiert", m1.positionOf(one, base2 + 0, out));
    Check.near("alle LEDs liegen auf dem einzigen Anker, x", 1.5, out[0], TOL);
    Check.near("alle LEDs liegen auf dem einzigen Anker, y", -2.0, out[1], TOL);
    Check.that("letzte LED des Stripes ist definiert", m1.positionOf(one, base2 + 19, out));
    Check.near("auch am anderen Ende, x", 1.5, out[0], TOL);
    Check.that("beim einzigen Anker selbst gilt interpoliert",
        m1.isInterpolatedAt(one, base2 + 5));
    Check.that("daneben gilt nicht interpoliert",
        !m1.isInterpolatedAt(one, base2 + 6));

    // ---- Kein Anker auf dem Stripe ----
    LedAnchorStore none = store();
    LedPositionMap mN = map();
    int base3 = 3 * PER_STRIPE;
    out[0] = 99f;
    out[1] = 99f;
    Check.that("ohne Anker ist die LED nicht definiert", !mN.positionOf(none, base3, out));
    Check.near("out2 bleibt unberuehrt, x", 99.0, out[0], TOL);
    Check.near("out2 bleibt unberuehrt, y", 99.0, out[1], TOL);
    Check.that("ohne Anker gilt nicht interpoliert", !mN.isInterpolatedAt(none, base3));

    // ---- Stripes beeinflussen sich nicht ----
    LedAnchorStore sep = store();
    LedPositionMap mSep = map();
    Check.that("Anker auf Stripe 0", sep.set(0, -5f, -3f, NO_CROSSINGS));
    Check.that("Anker auf Stripe 1", sep.set(PER_STRIPE + 19, 5f, 3f, NO_CROSSINGS));
    Check.that("Stripe 0 hat nur seinen eigenen Anker",
        mSep.positionOf(sep, 10, out));
    Check.near("Stripe 0 bleibt bei seinem einzigen Anker, x", -5.0, out[0], TOL);
    Check.that("Stripe 2 bleibt undefiniert",
        !mSep.positionOf(sep, 2 * PER_STRIPE, out));

    // ---- Index ausserhalb ----
    Check.that("negativer Index ist nicht definiert", !m.positionOf(s, -1, out));
    Check.that("Index hinter dem Ende ist nicht definiert",
        !m.positionOf(s, STRIPES * PER_STRIPE, out));

    // ---- apply() stimmt mit positionOf() ueberein ----
    LedAnchorStore ap = store();
    LedPositionMap mAp = map();
    Check.that("Anker LED 4", ap.set(4, -3f, -1f, NO_CROSSINGS));
    Check.that("Anker LED 14", ap.set(14, 2f, 1f, NO_CROSSINGS));
    Check.that("Anker auf Stripe 2", ap.set(2 * PER_STRIPE + 3, 0f, 0f, NO_CROSSINGS));

    Check.eq("vor apply() ist x null", 0, (long) mAp.x(9));
    Check.that("vor apply() ist nichts definiert", !mAp.isDefined(9));

    mAp.apply(ap);

    float[] ref = new float[2];
    int mismatches = 0;
    int definedCount = 0;
    for (int i = 0; i < STRIPES * PER_STRIPE; i++) {
      boolean def = mAp.positionOf(ap, i, ref);
      if (def != mAp.isDefined(i)) {
        mismatches++;
      } else if (def) {
        definedCount++;
        if (Math.abs(ref[0] - mAp.x(i)) > TOL || Math.abs(ref[1] - mAp.y(i)) > TOL) {
          mismatches++;
        }
        if (mAp.isInterpolatedAt(ap, i) != mAp.isInterpolated(i)) {
          mismatches++;
        }
      }
    }
    Check.eq("apply() weicht nie von positionOf() ab", 0, mismatches);
    // Stripe 0 und Stripe 2 haben Anker, Stripe 1 und 3 nicht.
    Check.eq("definierte LEDs sind die zwei Stripes mit Ankern",
        2 * PER_STRIPE, definedCount);
    Check.eq("undefiniert sind die zwei Stripes ohne Anker",
        2 * PER_STRIPE, mAp.undefinedCount());

    // Stripe 0: extrapoliert sind LED 0..3 und 15..19, das sind 9.
    // Stripe 2: ein einzelner Anker, alles ausser der Ankerled selbst
    // gilt als extrapoliert, das sind 19.
    Check.eq("Zahl der nur extrapolierten LEDs", 9 + 19, mAp.extrapolatedCount());

    // ---- Abdeckungsbericht ----
    String rep = mAp.coverageReport(ap);
    Check.that("Bericht nennt die Zahl der undefinierten LEDs",
        rep.indexOf(String.valueOf(2 * PER_STRIPE)) >= 0);
    Check.that("Bericht nennt die Stripes ohne Anker",
        rep.indexOf("Stripes ohne Anker") >= 0);
    Check.that("Bericht nennt Stripe 1", rep.indexOf("1") >= 0);
    Check.that("Bericht nennt Stripe 3", rep.indexOf("3") >= 0);

    LedAnchorStore full = store();
    LedPositionMap mFull = map();
    for (int st = 0; st < STRIPES; st++) {
      Check.that("Anker am Anfang von Stripe " + st,
          full.set(st * PER_STRIPE, -1f, -1f, NO_CROSSINGS));
      Check.that("Anker am Ende von Stripe " + st,
          full.set(st * PER_STRIPE + PER_STRIPE - 1, 1f, 1f, NO_CROSSINGS));
    }
    mFull.apply(full);
    Check.eq("mit Ankern an allen Enden ist nichts undefiniert",
        0, mFull.undefinedCount());
    Check.eq("und nichts nur extrapoliert", 0, mFull.extrapolatedCount());
    Check.that("Bericht nennt dann keine Stripes ohne Anker",
        mFull.coverageReport(full).indexOf("Stripes ohne Anker") < 0);

    // ---- apply() ist wiederholbar, ohne Reste ----
    LedAnchorStore again = store();
    LedPositionMap mAgain = map();
    Check.that("Anker vor dem ersten apply", again.set(0, -2f, -2f, NO_CROSSINGS));
    mAgain.apply(again);
    Check.eq("ein Stripe mit Anker", 3 * PER_STRIPE, mAgain.undefinedCount());
    Check.that("Anker wieder entfernt", again.remove(0));
    mAgain.apply(again);
    Check.eq("nach dem Entfernen ist alles undefiniert",
        STRIPES * PER_STRIPE, mAgain.undefinedCount());
    Check.that("und keine Position bleibt haengen", !mAgain.isDefined(0));

    System.exit(Check.report("LedPositionMapTest"));
  }
}
