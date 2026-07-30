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

    // ---- Setzen, Vorschlag, Loeschen ----
    NodeCrossingStore cs2 = crossings(new int[][] { { 10, 30 } });
    LedAnchorStore stC = store();
    LedPositionCalibration cc = build(cs2, stC);
    float[] d = new float[2];

    // Eintraege: 0, 10(Kreuzung), 19, 20, 39, 40, 59, 60, 79 -> 9
    Check.eq("neun Eintraege", 2 * STRIPES + 1, cc.entryCount());
    Check.eq("Start bei Eintrag 0 (LED 0)", 0, cc.ledsOfEntry(cc.entryIndex())[0]);

    // Ohne jeden Anker auf dem Stripe gibt es keinen Vorschlag
    Check.that("ohne Anker keine Anzeigeposition", !cc.displayPosition(d));
    Check.that("ENTER lehnt ohne Vorschlag ab", !cc.acceptProposal());
    Check.that("und begruendet es", cc.lastMessage().length() > 0);

    // Erster Klick setzt LED 0
    Check.that("Klick setzt den ersten Punkt", cc.setCurrent(-6f, -3f));
    Check.that("jetzt gibt es eine Anzeigeposition", cc.displayPosition(d));
    Check.near("Anzeige ist der gesetzte Anker, x", -6.0, d[0], 1e-4);
    Check.near("Anzeige ist der gesetzte Anker, y", -3.0, d[1], 1e-4);
    Check.that("Eintrag 0 ist gesetzt", cc.entryIsSet(0));

    // Zweiter Punkt auf demselben Stripe: die Kreuzung bei LED 10
    cc.next();
    Check.eq("jetzt bei der Kreuzung", 10, cc.ledsOfEntry(cc.entryIndex())[0]);
    // Vorschlag ist der einzige Anker des Stripes, weil noch keine Richtung
    // bekannt ist
    Check.that("Vorschlag vorhanden", cc.displayPosition(d));
    Check.near("Vorschlag ist der einzige Anker, x", -6.0, d[0], 1e-4);
    Check.that("Klick setzt die Kreuzung", cc.setCurrent(-1f, -1f));
    Check.that("die Partner-LED auf Stripe 1 wurde mitgesetzt", stC.has(30));

    // Dritter Eintrag: LED 19. Jetzt gibt es eine Richtung, der Vorschlag
    // setzt den Vektor von LED 0 -> LED 10 fort.
    // d = ((-1 - -6)/10, (-1 - -3)/10) = (0.5, 0.2) je Index
    // LED 19 -> (-1 + 9*0.5, -1 + 9*0.2) = (3.5, 0.8)
    cc.next();
    Check.eq("jetzt bei LED 19", 19, cc.ledsOfEntry(cc.entryIndex())[0]);
    Check.that("Vorschlag vorhanden", cc.displayPosition(d));
    Check.near("Vorschlag setzt den Vektor fort, x", 3.5, d[0], 1e-3);
    Check.near("Vorschlag setzt den Vektor fort, y", 0.8, d[1], 1e-3);

    // ENTER uebernimmt genau diesen Vorschlag
    Check.that("ENTER uebernimmt den Vorschlag", cc.acceptProposal());
    Check.that("LED 19 ist jetzt ein Anker", stC.has(19));
    Check.near("und zwar auf dem Vorschlagswert", 3.5, stC.x(19), 1e-3);

    // BACKSPACE nimmt den Anker weg, die Anzeige faellt auf den Vorschlag
    Check.that("BACKSPACE loescht den Anker", cc.clearCurrent());
    Check.that("LED 19 ist kein Anker mehr", !stC.has(19));
    Check.that("Anzeige faellt auf den Vorschlag zurueck", cc.displayPosition(d));
    Check.near("Vorschlag ist unveraendert", 3.5, d[0], 1e-3);

    // BACKSPACE auf einer Kreuzung raeumt beide LEDs
    LedAnchorStore stX = store();
    LedPositionCalibration cx = build(cs2, stX);
    cx.next();
    Check.eq("bei der Kreuzung", 10, cx.ledsOfEntry(cx.entryIndex())[0]);
    Check.that("Kreuzung gesetzt", cx.setCurrent(0f, 0f));
    Check.that("beide LEDs gesetzt", stX.has(10) && stX.has(30));
    Check.that("BACKSPACE auf der Kreuzung", cx.clearCurrent());
    Check.that("beide LEDs geloescht", !stX.has(10) && !stX.has(30));

    // ---- Feinjustierung und Schrittweite ----
    Check.near("Startschrittweite", 0.05, cc.step(), 1e-6);
    cc.cycleStep();
    Check.near("F schaltet auf die naechste Schrittweite", 0.25, cc.step(), 1e-6);
    cc.cycleStep();
    Check.near("F laeuft um auf die kleinste", 0.01, cc.step(), 1e-6);
    cc.cycleStep();
    Check.near("und wieder zurueck", 0.05, cc.step(), 1e-6);

    LedAnchorStore stN = store();
    LedPositionCalibration cn = build(cs2, stN);
    Check.that("Punkt setzen", cn.setCurrent(0f, 0f));
    Check.that("nach rechts schieben", cn.nudge(1, 0));
    Check.near("x um eine Schrittweite groesser", 0.05, stN.x(0), 1e-5);
    Check.that("nach oben schieben", cn.nudge(0, 1));
    Check.near("y um eine Schrittweite groesser", 0.05, stN.y(0), 1e-5);
    Check.that("nach links und unten", cn.nudge(-1, -1));
    Check.near("x wieder null", 0.0, stN.x(0), 1e-5);
    Check.near("y wieder null", 0.0, stN.y(0), 1e-5);

    // Pfeiltaste auf einem offenen Eintrag mit Vorschlag setzt ihn dabei
    cn.next();
    cn.next();
    Check.that("dritter Eintrag ist offen", !cn.entryIsSet(cn.entryIndex()));
    boolean nudgedOpen = cn.nudge(1, 0);
    if (nudgedOpen) {
      Check.that("Pfeiltaste auf einem offenen Eintrag setzt ihn",
          cn.entryIsSet(cn.entryIndex()));
    } else {
      Check.that("ohne Vorschlag lehnt die Pfeiltaste ab und begruendet",
          cn.lastMessage().length() > 0);
    }

    // ---- L: zweimal druecken, mit Fenster ----
    LedAnchorStore stL = store();
    LedPositionCalibration cl = build(cs2, stL);
    Check.that("Punkt setzen", cl.setCurrent(1f, 1f));
    Check.eq("ein Anker", 1, stL.size());

    Check.that("erster Druck verwirft nichts", !cl.requestClearAll(10000L));
    Check.eq("Anker steht noch", 1, stL.size());
    Check.that("die Ankuendigung nennt die Anzahl",
        cl.lastMessage().indexOf("1") >= 0);

    Check.that("zweiter Druck nach 100 ms ist Tastenwiederholung",
        !cl.requestClearAll(10100L));
    Check.eq("Anker steht immer noch", 1, stL.size());

    Check.that("zweiter Druck nach 400 ms verwirft", cl.requestClearAll(10400L));
    Check.eq("Liste ist leer", 0, stL.size());

    // Abbruch durch eine andere Taste
    LedAnchorStore stL2 = store();
    LedPositionCalibration cl2 = build(cs2, stL2);
    Check.that("Punkt setzen", cl2.setCurrent(1f, 1f));
    Check.that("erster Druck", !cl2.requestClearAll(20000L));
    cl2.abortClearAll();
    Check.that("nach dem Abbruch ist der naechste Druck wieder der erste",
        !cl2.requestClearAll(20400L));
    Check.eq("nichts verworfen", 1, stL2.size());

    // Zu spaet ist wieder ein erster Druck
    LedAnchorStore stL3 = store();
    LedPositionCalibration cl3 = build(cs2, stL3);
    Check.that("Punkt setzen", cl3.setCurrent(1f, 1f));
    Check.that("erster Druck", !cl3.requestClearAll(30000L));
    Check.that("nach 6 s ist es wieder ein erster Druck",
        !cl3.requestClearAll(36000L));
    Check.eq("nichts verworfen", 1, stL3.size());

    // ---- Abdeckungsbericht und HUD ----
    LedAnchorStore stH = store();
    LedPositionCalibration ch = build(cs2, stH);
    String rep2 = ch.coverageReport();
    Check.that("Bericht nennt undefinierte LEDs", rep2.indexOf("ohne Position") >= 0);

    String hud = ch.hudText();
    Check.that("HUD nennt die Eintragszahl",
        hud.indexOf(String.valueOf(ch.entryCount())) >= 0);
    Check.that("HUD nennt die Schrittweite", hud.indexOf("Schritt") >= 0);
    Check.that("HUD nennt die Tastenbelegung", hud.indexOf("ENTER") >= 0);

    // ---- Speichern und wieder laden ----
    java.io.File posDir = java.nio.file.Files.createTempDirectory("ledpos").toFile();
    java.io.File posFile = new java.io.File(posDir, "ledPositions.txt");
    LedAnchorStore stS = store();
    LedPositionMap mS2 = new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
    ArrayList<LedNetworkNode> nS = new ArrayList<LedNetworkNode>();
    LedInNetInfo[] iS = LedInNetInfo.buildNetInfo(STRIPES, PER_STRIPE);
    LedInNetInfo.applyCrossings(cs2.crossings(), iS, nS);
    LedPositionCalibration csave = new LedPositionCalibration(stS, mS2, cs2, nS,
        STRIPES, PER_STRIPE, posFile.getAbsolutePath(),
        PANE_X, PANE_Y, PANE_W, PANE_H, FOOT_X, FOOT_Y);
    Check.that("Punkt setzen", csave.setCurrent(-2.5f, 1.25f));
    Check.that("S schreibt die Datei", csave.save());
    Check.that("die Datei existiert", posFile.exists());

    LedAnchorStore reread = store();
    reread.load(posFile.getAbsolutePath());
    Check.eq("ein Anker wieder geladen", 1, reread.size());
    Check.near("x kam unveraendert zurueck", -2.5, reread.x(0), 1e-3);
    Check.near("y kam unveraendert zurueck", 1.25, reread.y(0), 1e-3);

    // ---- R rechnet die Map neu ----
    LedAnchorStore stR = store();
    LedPositionMap mR = new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
    ArrayList<LedNetworkNode> nR = new ArrayList<LedNetworkNode>();
    LedInNetInfo[] iR = LedInNetInfo.buildNetInfo(STRIPES, PER_STRIPE);
    LedInNetInfo.applyCrossings(cs2.crossings(), iR, nR);
    LedPositionCalibration cr = new LedPositionCalibration(stR, mR, cs2, nR,
        STRIPES, PER_STRIPE, NO_FILE,
        PANE_X, PANE_Y, PANE_W, PANE_H, FOOT_X, FOOT_Y);
    Check.that("Punkt setzen", cr.setCurrent(2f, 2f));
    cr.reapply();
    Check.that("die Map kennt die Position jetzt", mR.isDefined(0));
    Check.near("und der Knoten seine", 2.0, nR.get(0).posX, 1.0);
    Check.that("nach reapply ist nichts mehr offen anzuwenden", !cr.mapNeedsApply());
    Check.that("ein neuer Klick macht die Map wieder schmutzig",
        cr.setCurrent(2.5f, 2.5f) && cr.mapNeedsApply());

    System.exit(Check.report("LedPositionCalibrationTest"));
  }
}
