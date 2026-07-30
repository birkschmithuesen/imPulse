import java.util.ArrayList;
import java.util.TreeSet;

public class LedPositionCalibrationTest {

  // Kleine synthetische Geometrie. NICHT gegen data/nodeCrossings.txt
  // pruefen - die Datei waechst waehrend der Kalibrierung.
  static final int STRIPES = 4;
  static final int PER_STRIPE = 20;
  static final float FOOT_X = 14f;
  static final float FOOT_Y = 8f;
  static final float PITCH = 0.5f;
  static final String NO_FILE = "";

  // Pane-Rechteck wie im Sketch, nur mit derselben Rechnung
  static final int PANE_X = 0;
  static final int PANE_Y = 0;
  static final int PANE_W = 525;
  static final int PANE_H = 300;

  static NodeCrossingStore crossings(int[][] pairs) {
    NodeCrossingStore cs = new NodeCrossingStore(STRIPES, PER_STRIPE);
    for (int[] p : pairs) {
      // add() erwartet Stripe und LED getrennt
      cs.add(p[0] / PER_STRIPE, p[0] % PER_STRIPE, p[1] / PER_STRIPE, p[1] % PER_STRIPE);
    }
    return cs;
  }

  static LedPositionCalibration build(NodeCrossingStore cs, LedAnchorStore store) {
    LedPositionMap map = new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
    ArrayList<LedNetworkNode> nodes = new ArrayList<LedNetworkNode>();
    LedInNetInfo[] infos = LedInNetInfo.buildNetInfo(STRIPES, PER_STRIPE);
    LedInNetInfo.applyCrossings(cs.crossings(), infos, nodes);
    return new LedPositionCalibration(store, map, cs, nodes,
        STRIPES, PER_STRIPE, NO_FILE,
        PANE_X, PANE_Y, PANE_W, PANE_H, FOOT_X, FOOT_Y);
  }

  static LedAnchorStore store() {
    return new LedAnchorStore(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y, PITCH);
  }

  public static void main(String[] args) throws Exception {
    // Stripe 0 = 0..19, Stripe 1 = 20..39, Stripe 2 = 40..59, Stripe 3 = 60..79
    // Kreuzung A = {10, 30}, Kreuzung B = {5, 75}
    NodeCrossingStore cs = crossings(new int[][] { { 10, 30 }, { 5, 75 } });
    Check.eq("zwei Kreuzungen aufgenommen", 2, cs.size());

    LedAnchorStore st = store();
    LedPositionCalibration c = build(cs, st);

    // ---- Laenge: 2*Stripes + Kreuzungen = 8 + 2 = 10 ----
    Check.eq("Eintraege sind 2*Stripes + Kreuzungen", 2 * STRIPES + 2, c.entryCount());

    // ---- Reihenfolge nach kleinstem LED-Index ----
    // erwartet: 0, 5(B), 10(A), 19, 20, 39, 40, 59, 60, 79
    int[] expectedFirstLed = { 0, 5, 10, 19, 20, 39, 40, 59, 60, 79 };
    for (int i = 0; i < expectedFirstLed.length; i++) {
      Check.eq("Eintrag " + i + " beginnt bei LED " + expectedFirstLed[i],
          expectedFirstLed[i], c.ledsOfEntry(i)[0]);
    }

    // ---- Kreuzungseintraege tragen beide LEDs, Stripe-Enden eine ----
    Check.that("Eintrag 1 ist eine Kreuzung", c.entryIsCrossing(1));
    Check.eq("Kreuzung B hat zwei LEDs", 2, c.ledsOfEntry(1).length);
    Check.eq("zweite LED von Kreuzung B", 75, c.ledsOfEntry(1)[1]);
    Check.that("Eintrag 2 ist eine Kreuzung", c.entryIsCrossing(2));
    Check.eq("zweite LED von Kreuzung A", 30, c.ledsOfEntry(2)[1]);
    Check.that("Eintrag 0 ist keine Kreuzung", !c.entryIsCrossing(0));
    Check.eq("ein Stripe-Ende hat eine LED", 1, c.ledsOfEntry(0).length);

    // ---- ledsOfEntry gibt eine Kopie, kein Fenster in die Innerei ----
    int[] borrowed = c.ledsOfEntry(1);
    borrowed[0] = -999;
    Check.eq("Aendern der Kopie beruehrt die Liste nicht", 5, c.ledsOfEntry(1)[0]);

    // ---- Anfangszustand und offene Eintraege ----
    Check.eq("Start beim ersten Eintrag", 0, c.entryIndex());
    Check.eq("zu Beginn sind alle offen", 2 * STRIPES + 2, c.openCount());
    Check.that("Eintrag 0 ist offen", !c.entryIsSet(0));

    // ---- Blaettern klemmt an beiden Enden, kein Umlauf ----
    c.prev();
    Check.eq("prev am Anfang bleibt stehen", 0, c.entryIndex());
    c.next();
    Check.eq("next geht vor", 1, c.entryIndex());
    for (int i = 0; i < 50; i++) {
      c.next();
    }
    Check.eq("next klemmt am letzten Eintrag", c.entryCount() - 1, c.entryIndex());
    c.prev();
    Check.eq("prev geht zurueck", c.entryCount() - 2, c.entryIndex());

    // ---- Ein gesetzter Kreuzungsanker schliesst den ganzen Eintrag ----
    // Kreuzung B = {5, 75}: set(5, ...) verteilt auf 75 mit.
    Check.that("Kreuzungsanker gesetzt", st.set(5, 1f, 1f, cs.crossings()));
    Check.that("Partner-LED wurde mitgesetzt", st.has(75));
    Check.that("Eintrag 1 gilt jetzt als gesetzt", c.entryIsSet(1));
    Check.eq("ein Eintrag weniger offen", 2 * STRIPES + 1, c.openCount());

    // ---- Ein halb gesetzter Eintrag gilt als offen ----
    Check.that("Partner-LED einzeln entfernt", st.remove(75));
    Check.that("halb gesetzt gilt als offen", !c.entryIsSet(1));

    // ---- nextOpen ueberspringt gesetzte Eintraege ----
    LedAnchorStore st2 = store();
    LedPositionCalibration c2 = build(cs, st2);
    Check.that("Eintrag 0 setzen", st2.set(0, -6f, -3f, cs.crossings()));
    Check.that("Eintrag 1 setzen", st2.set(5, -5f, -2f, cs.crossings()));
    Check.that("nextOpen findet einen offenen", c2.nextOpen());
    Check.eq("nextOpen springt auf Eintrag 2", 2, c2.entryIndex());

    // alle setzen, dann findet nextOpen keinen mehr
    for (int i = 0; i < c2.entryCount(); i++) {
      st2.set(c2.ledsOfEntry(i)[0], 0f, 0f, cs.crossings());
    }
    Check.eq("nichts mehr offen", 0, c2.openCount());
    Check.that("nextOpen findet keinen offenen mehr", !c2.nextOpen());
    Check.that("die Meldung sagt das auch", c2.lastMessage().length() > 0);

    // ---- Kreuzung auf einem Stripe-Ende verschmilzt mit dessen Eintrag ----
    // Kreuzung {0, 25}: LED 0 ist gleichzeitig Anfang von Stripe 0.
    NodeCrossingStore csEnd = crossings(new int[][] { { 0, 25 } });
    LedPositionCalibration cEnd = build(csEnd, store());
    // 2*4 Enden = 8, davon faellt LED 0 mit der Kreuzung zusammen -> 7
    // eigene Endeintraege, plus 1 Kreuzung = 8
    Check.eq("Kreuzung auf einem Stripe-Ende erzeugt keinen doppelten Eintrag",
        8, cEnd.entryCount());
    Check.that("der Eintrag bei LED 0 ist die Kreuzung", cEnd.entryIsCrossing(0));
    Check.eq("und traegt beide LEDs", 2, cEnd.ledsOfEntry(0).length);

    // ---- Neu aufgenommene Kreuzung erscheint nach rebuildWorklist ----
    LedAnchorStore st3 = store();
    NodeCrossingStore csGrow = crossings(new int[][] { { 10, 30 } });
    LedPositionCalibration c3 = build(csGrow, st3);
    Check.eq("vor der neuen Kreuzung", 2 * STRIPES + 1, c3.entryCount());
    Check.that("neue Kreuzung aufgenommen", csGrow.add(0, 7, 2, 3));
    Check.eq("ohne rebuild unveraendert", 2 * STRIPES + 1, c3.entryCount());
    c3.rebuildWorklist();
    Check.eq("nach rebuild ein Eintrag mehr", 2 * STRIPES + 2, c3.entryCount());
    // LED 7 auf Stripe 0 sortiert zwischen 0 und 10
    Check.eq("die neue Kreuzung sitzt an der richtigen Stelle", 7, c3.ledsOfEntry(1)[0]);
    Check.that("und ist offen", !c3.entryIsSet(1));

    // ---- rebuildWorklist haelt den Zeiger auf demselben physischen Punkt ----
    // Ohne Zeigerrettung faellt current nach jedem Rebuild auf 0 - und genau
    // das passiert bei R, waehrend jemand am Netz arbeitet.
    NodeCrossingStore csKeep = crossings(new int[][] { { 10, 30 } });
    LedPositionCalibration ck = build(csKeep, store());
    ck.next();
    ck.next();
    ck.next();
    Check.eq("Zeiger vor dem Rebuild", 20, ck.ledsOfEntry(ck.entryIndex())[0]);
    ck.rebuildWorklist();
    Check.eq("nach dem Rebuild steht der Zeiger auf demselben Punkt",
        20, ck.ledsOfEntry(ck.entryIndex())[0]);

    // Der harte Fall: eine neue Kreuzung legt die LED des aktuellen Eintrags
    // mit einer KLEINEREN zusammen. Ueber die frueher kleinste LED ist der
    // Punkt danach nicht mehr zu finden.
    NodeCrossingStore csMerge = crossings(new int[][] { { 10, 30 } });
    LedPositionCalibration cm = build(csMerge, store());
    while (cm.ledsOfEntry(cm.entryIndex())[0] != 79) {
      cm.next();
    }
    Check.eq("Zeiger auf dem letzten Stripe-Ende", 79, cm.ledsOfEntry(cm.entryIndex())[0]);
    // Stripe 0 LED 5 und Stripe 3 LED 19 (global 79) - Minimum der Kreuzung ist 5
    Check.that("neue Kreuzung auf LED 79 aufgenommen", csMerge.add(0, 5, 3, 19));
    cm.rebuildWorklist();
    int[] afterMerge = cm.ledsOfEntry(cm.entryIndex());
    boolean holds79 = false;
    for (int led : afterMerge) {
      if (led == 79) {
        holds79 = true;
      }
    }
    Check.that("der Zeiger folgt dem Punkt in den zusammengelegten Eintrag", holds79);
    Check.eq("und der Eintrag traegt jetzt zwei LEDs", 2, afterMerge.length);

    // ---- Umrechnung Flaeche <-> Meter ----
    // Pane (0,0,525,300) fuer 14 x 8 m. 525:300 == 14:8, keine Verzerrung.
    LedPositionCalibration cP = build(cs, store());
    float[] w = new float[2];
    float[] p = new float[2];
    final double MTOL = 0.03;   // ein Pixel sind 2,67 cm

    // Ecken. Y zeigt nach vorn und auf dem Schirm nach oben:
    // Pixel-Y 0 ist also +4 m, Pixel-Y 300 ist -4 m.
    Check.that("linke obere Ecke ist innen", cP.paneToWorld(PANE_X, PANE_Y, w));
    Check.near("linke obere Ecke x", -7.0, w[0], MTOL);
    Check.near("linke obere Ecke y", 4.0, w[1], MTOL);

    Check.that("rechte untere Ecke ist innen",
        cP.paneToWorld(PANE_X + PANE_W, PANE_Y + PANE_H, w));
    Check.near("rechte untere Ecke x", 7.0, w[0], MTOL);
    Check.near("rechte untere Ecke y", -4.0, w[1], MTOL);

    Check.that("rechte obere Ecke ist innen",
        cP.paneToWorld(PANE_X + PANE_W, PANE_Y, w));
    Check.near("rechte obere Ecke x", 7.0, w[0], MTOL);
    Check.near("rechte obere Ecke y", 4.0, w[1], MTOL);

    Check.that("linke untere Ecke ist innen",
        cP.paneToWorld(PANE_X, PANE_Y + PANE_H, w));
    Check.near("linke untere Ecke x", -7.0, w[0], MTOL);
    Check.near("linke untere Ecke y", -4.0, w[1], MTOL);

    // Mitte
    Check.that("Mitte ist innen",
        cP.paneToWorld(PANE_X + PANE_W / 2, PANE_Y + PANE_H / 2, w));
    Check.near("Mitte x ist der Ursprung", 0.0, w[0], MTOL);
    Check.near("Mitte y ist der Ursprung", 0.0, w[1], MTOL);

    // Ausserhalb wird verworfen, out2 bleibt unberuehrt
    w[0] = 42f;
    w[1] = 42f;
    Check.that("links daneben wird verworfen", !cP.paneToWorld(PANE_X - 1, PANE_Y, w));
    Check.that("rechts daneben wird verworfen",
        !cP.paneToWorld(PANE_X + PANE_W + 1, PANE_Y, w));
    Check.that("darueber wird verworfen", !cP.paneToWorld(PANE_X, PANE_Y - 1, w));
    Check.that("darunter - im HUD - wird verworfen",
        !cP.paneToWorld(PANE_X, PANE_Y + PANE_H + 1, w));
    Check.near("out2 bleibt unberuehrt, x", 42.0, w[0], 1e-6);
    Check.near("out2 bleibt unberuehrt, y", 42.0, w[1], 1e-6);

    // Rundlauf in beiden Richtungen
    float[][] probes = { { 0f, 0f }, { -7f, 4f }, { 7f, -4f },
                         { -3.25f, 1.1f }, { 2.9f, -0.45f }, { 6.99f, 3.99f } };
    for (float[] q : probes) {
      cP.worldToPane(q[0], q[1], p);
      Check.that("Rundlauf: (" + q[0] + "," + q[1] + ") liegt innen",
          cP.paneToWorld((int) (p[0] + 0.5f), (int) (p[1] + 0.5f), w));
      Check.near("Rundlauf x bei " + q[0], q[0], w[0], MTOL);
      Check.near("Rundlauf y bei " + q[1], q[1], w[1], MTOL);
    }

    // worldToPane rechnet die Ecken auf die Rechteckecken
    cP.worldToPane(-7f, 4f, p);
    Check.near("worldToPane linke obere Ecke px", PANE_X, p[0], 0.5);
    Check.near("worldToPane linke obere Ecke py", PANE_Y, p[1], 0.5);
    cP.worldToPane(7f, -4f, p);
    Check.near("worldToPane rechte untere Ecke px", PANE_X + PANE_W, p[0], 0.5);
    Check.near("worldToPane rechte untere Ecke py", PANE_Y + PANE_H, p[1], 0.5);

    System.exit(Check.report("LedPositionCalibrationTest"));
  }
}
