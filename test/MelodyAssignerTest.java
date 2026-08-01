import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class MelodyAssignerTest {

  static final int PITCH = 600;

  static TreeSet<Integer> cross(int s1, int p1, int s2, int p2) {
    TreeSet<Integer> t = new TreeSet<Integer>();
    t.add(Integer.valueOf(s1 * PITCH + p1));
    t.add(Integer.valueOf(s2 * PITCH + p2));
    return t;
  }

  // Zufall aus einer festen Folge - reproduzierbar, und ein Test kann damit
  // eine bestimmte Intervallklasse erzwingen.
  static class FixedRandom implements RandomSource {
    private final double[] values;
    private int i = 0;
    FixedRandom(double[] values_) { values = values_; }
    public double next() {
      double v = values[i % values.length];
      i++;
      return v;
    }
  }

  // Immer derselbe Wert - fuer Laeufe, in denen der Zufall keine Rolle
  // spielen soll.
  static class ConstRandom implements RandomSource {
    private final double v;
    ConstRandom(double v_) { v = v_; }
    public double next() { return v; }
  }

  public static void main(String[] args) throws Exception {
    MelodyMode phrygisch = MelodyModes.byKey("phrygisch");   // 7 Stufen
    MelodyMode pentatonik = MelodyModes.byKey("pentatonik"); // 5 Stufen

    // ---- fold(): die harte Hoerbarkeitsgrenze ----
    Check.eq("fold laesst kleine Werte in Ruhe", 5, MelodyAssigner.fold(5, 21));
    Check.eq("fold bricht oben um", 0, MelodyAssigner.fold(21, 21));
    Check.eq("fold bricht oben um (2)", 3, MelodyAssigner.fold(24, 21));
    // Der doppelte Modulo: Javas % liefert fuer negative Zahlen ein negatives
    // Ergebnis, und durch die -3 der Rotation KANN der Rohwert negativ werden.
    Check.eq("fold behandelt -1", 20, MelodyAssigner.fold(-1, 21));
    Check.eq("fold behandelt -21", 0, MelodyAssigner.fold(-21, 21));
    Check.eq("fold behandelt -22", 20, MelodyAssigner.fold(-22, 21));
    Check.eq("fold mit Teiler 0 stuerzt nicht ab", 0, MelodyAssigner.fold(7, 0));

    // ---- landmarkInterval(): die Rotation ----
    Check.eq("drei stabile Intervalle", 3, MelodyAssigner.STABLE_INTERVALS.length);
    Check.eq("Rotation 0 ist die Quint aufwaerts", 4, MelodyAssigner.landmarkInterval(0, 0));
    Check.eq("Rotation 1 ist die Quart abwaerts", -3, MelodyAssigner.landmarkInterval(1, 0));
    Check.eq("Rotation 2 ist die Terz aufwaerts", 2, MelodyAssigner.landmarkInterval(0, 2));
    Check.eq("Tiefe und Rang addieren sich", -3, MelodyAssigner.landmarkInterval(2, 2));
    // +4 und -3 liegen auf DERSELBEN Skalenstufe, nur in verschiedenen
    // Oktaven (4 - 7 = -3 bei siebentoeniger Skala). Genau das haelt den
    // Ankercharakter aufrecht, waehrend die Lage wechselt.
    Check.eq("Quint aufwaerts und Quart abwaerts sind dieselbe Stufe",
        7, MelodyAssigner.STABLE_INTERVALS[0] - MelodyAssigner.STABLE_INTERVALS[1]);
    // Mittelwert +1 statt +4, mit wechselndem Vorzeichen - das ist die
    // Daempfung der Drift.
    Check.eq("Mittelwert der Rotation ist +1 Stufe je Ebene", 3,
        MelodyAssigner.STABLE_INTERVALS[0] + MelodyAssigner.STABLE_INTERVALS[1]
        + MelodyAssigner.STABLE_INTERVALS[2]);

    // ---- Leerer Graph ----
    MelodyGraph empty = MelodyGraph.fromCrossings(new ArrayList<TreeSet<Integer>>(), PITCH);
    MelodyAssignment ea = MelodyAssigner.assign(empty, phrygisch, 0, 45, 3, 2,
        new ConstRandom(0.5));
    Check.eq("leerer Graph: keine Knoten", 0, ea.nodeCount);
    Check.eq("leerer Graph: keine Rueckwaertskanten", 0, ea.backEdges);

    // ---- Sternchen: ein Hub mit vier Landmarken-Nachbarn ----
    // Das ist genau der Fall aus Schritt 3c: vorher lagen alle vier Nachbarn
    // auf demselben scaleIndex (ein Ton-Stapel), die Rotation verteilt sie.
    //
    // Aufbau: Node 0 sitzt auf vier Stripes; je Stripe ein weiterer Node.
    // Die vier Aussenknoten bekommen ihrerseits genug Nachbarn, damit sie
    // die Hub-Schwelle erreichen.
    List<TreeSet<Integer>> star = new ArrayList<TreeSet<Integer>>();
    // Node 0: vier LEDs auf Stripe 0..3, jeweils Position 100
    TreeSet<Integer> hub = new TreeSet<Integer>();
    for (int s = 0; s < 4; s++) {
      hub.add(Integer.valueOf(s * PITCH + 100));
    }
    star.add(hub);                       // 0, Grad 4
    star.add(cross(0, 200, 10, 500));    // 1
    star.add(cross(1, 200, 11, 400));    // 2
    star.add(cross(2, 200, 12, 300));    // 3
    star.add(cross(3, 200, 13, 200));    // 4
    MelodyGraph sg = MelodyGraph.fromCrossings(star, PITCH);
    Check.eq("Sternchen: Hub hat Grad 4", 4, sg.degree(0));
    // hubThreshold 1 macht ALLE Knoten zu Landmarken - dann laeuft die
    // Zuweisung ausschliesslich ueber die Rotation, ohne jeden Zufall.
    MelodyAssignment sa = MelodyAssigner.assign(sg, phrygisch, 0, 45, 3, 1,
        new ConstRandom(0.0));
    Check.eq("Startknoten sitzt in der mittleren Oktave",
        7 * (3 / 2), sa.scaleIndex[0]);
    Check.eq("alle Knoten erreicht", 0, sa.unreachedCount);
    // Die vier Geschwister duerfen NICHT alle auf demselben Wert liegen -
    // das ist der Ton-Stapel, den die Rotation aufloest.
    boolean allSame = sa.scaleIndex[1] == sa.scaleIndex[2]
        && sa.scaleIndex[2] == sa.scaleIndex[3]
        && sa.scaleIndex[3] == sa.scaleIndex[4];
    Check.that("Landmarken-Geschwister stapeln sich nicht auf einem Ton", !allSame);
    // Nachgerechnet: Nachbarn von 0 sind 1,2,3,4, alle Landmarken (Schwelle 1),
    // sortiert nach kleinstem LED-Index: 1 (200), 2 (800), 3 (1400), 4 (2000)
    // -> rang 0..3, tiefe 1. Rotationsindizes (1+0..3) mod 3 = 1,2,0,1.
    Check.eq("Nachbar rang 0 bekommt -3", 7 - 3, sa.scaleIndex[1]);
    Check.eq("Nachbar rang 1 bekommt +2", 7 + 2, sa.scaleIndex[2]);
    Check.eq("Nachbar rang 2 bekommt +4", 7 + 4, sa.scaleIndex[3]);
    Check.eq("Nachbar rang 3 bekommt wieder -3", 7 - 3, sa.scaleIndex[4]);
    Check.that("Nachbarn sind als Landmarken markiert", sa.landmark[1] && sa.landmark[4]);
    Check.eq("Tiefe der Nachbarn", 1, sa.depth[1]);
    Check.eq("Tiefe des Startknotens", 0, sa.depth[0]);
    Check.eq("Sternchen hat keine Rueckwaertskante", 0, sa.backEdges);
    Check.eq("Sternchen hat keine Umbruchkante", 0, sa.wrapEdges);

    // ---- MIDI-Noten liegen in der garantierten Spanne ----
    // MIDI 45 (A2) bis 45 + scale[letzte] + 12*(numOctaves-1)
    int hi = 45 + phrygisch.scale[phrygisch.length() - 1] + 12 * (3 - 1);
    for (int i = 0; i < sa.nodeCount; i++) {
      Check.that("scaleIndex im gefalteten Bereich",
          sa.scaleIndex[i] >= 0 && sa.scaleIndex[i] < sa.notesPerOctaveSet);
      Check.that("midiNote in der garantierten Spanne",
          sa.midiNote[i] >= 45 && sa.midiNote[i] <= hi);
    }
    Check.eq("Startknoten klingt als Tonika der mittleren Oktave",
        45 + 12, sa.midiNote[0]);

    // ---- Determinismus ----
    MelodyAssignment r1 = MelodyAssigner.assign(sg, phrygisch, 0, 45, 3, 2,
        new FixedRandom(new double[] { 0.1, 0.9, 0.5, 0.2, 0.75, 0.3 }));
    MelodyAssignment r2 = MelodyAssigner.assign(sg, phrygisch, 0, 45, 3, 2,
        new FixedRandom(new double[] { 0.1, 0.9, 0.5, 0.2, 0.75, 0.3 }));
    for (int i = 0; i < r1.nodeCount; i++) {
      Check.eq("derselbe Lauf ergibt dieselbe Stufe", r1.scaleIndex[i], r2.scaleIndex[i]);
      Check.eq("derselbe Lauf ergibt dieselbe Tiefe", r1.depth[i], r2.depth[i]);
    }

    // ---- Ein anderer Startknoten ergibt eine andere Zuordnung ----
    MelodyAssignment fromOne = MelodyAssigner.assign(sg, phrygisch, 1, 45, 3, 1,
        new ConstRandom(0.0));
    Check.eq("neuer Startknoten ist die Tonika", 7, fromOne.scaleIndex[1]);
    Check.that("die Zuordnung ist eine andere", fromOne.scaleIndex[0] != sa.scaleIndex[0]);

    // ---- Startknoten ausserhalb wird gemeldet, nicht still hingenommen ----
    MelodyAssignment bad = MelodyAssigner.assign(sg, phrygisch, 999, 45, 3, 1,
        new ConstRandom(0.0));
    Check.that("Startknoten ausserhalb wird gemeldet", bad.startNodeAdjusted);
    Check.eq("und durch den Vorschlag ersetzt", sg.defaultStartNode(), bad.startNode);
    MelodyAssignment neg = MelodyAssigner.assign(sg, phrygisch, -1, 45, 3, 1,
        new ConstRandom(0.0));
    Check.that("negativer Startknoten wird ebenfalls gemeldet", neg.startNodeAdjusted);

    // ---- Dreieck: genau eine Rueckwaertskante ----
    List<TreeSet<Integer>> triangle = new ArrayList<TreeSet<Integer>>();
    triangle.add(cross(0, 100, 1, 100));  // 0
    triangle.add(cross(1, 200, 2, 100));  // 1
    triangle.add(cross(2, 200, 0, 200));  // 2
    MelodyGraph tri = MelodyGraph.fromCrossings(triangle, PITCH);
    MelodyAssignment ta = MelodyAssigner.assign(tri, phrygisch, 0, 45, 3, 1,
        new ConstRandom(0.0));
    Check.eq("Dreieck: drei Kanten", 3, tri.edgeCount());
    // Zwei Baumkanten (0-1, 0-2), die dritte (1-2) ist die Rueckwaertskante.
    Check.eq("Dreieck: genau eine Rueckwaertskante", 1, ta.backEdges);

    // ---- Negative Rohwerte falten korrekt ----
    // Eine lange Kette, alle Knoten Landmarken (Schwelle 1) und alle mit
    // rang 0 - also Rotation nur ueber die Tiefe: +4, -3, +2, +4, -3, ...
    // Der Rohwert bleibt in der Naehe der Mitte; um wirklich negativ zu
    // werden, braucht es eine Kette mit lauter Abwaertsschritten. Die
    // erzwingt ein Modus-Lauf mit ConstRandom(0.0): Klasse 0 (Sekundschritt),
    // Vorzeichen negativ (rSign < 0.5).
    List<TreeSet<Integer>> line = new ArrayList<TreeSet<Integer>>();
    for (int i = 0; i < 40; i++) {
      line.add(cross(0, 10 + i * 10, 20 + i, 100));
    }
    MelodyGraph lg = MelodyGraph.fromCrossings(line, PITCH);
    // Schwelle 3 macht auf dieser Kette (Grade 1 und 2) niemanden zur
    // Landmarke - jede Kante zieht also, und ConstRandom(0.0) zieht immer
    // den Sekundschritt ABWAERTS.
    MelodyAssignment la = MelodyAssigner.assign(lg, phrygisch, 0, 45, 3, 3,
        new ConstRandom(0.0));
    Check.eq("Kette: alle erreicht", 0, la.unreachedCount);
    boolean sawDownStep = false;
    for (int i = 0; i < la.nodeCount; i++) {
      Check.that("auch nach vielen Abwaertsschritten bleibt die Stufe im Bereich",
          la.scaleIndex[i] >= 0 && la.scaleIndex[i] < la.notesPerOctaveSet);
      Check.that("midiNote bleibt in der Spanne",
          la.midiNote[i] >= 45 && la.midiNote[i] <= hi);
      if (la.depth[i] > 0 && la.interval[i] == -1) {
        sawDownStep = true;
      }
    }
    Check.that("die Kette laeuft wirklich abwaerts", sawDownStep);
    // Bei 39 Abwaertsschritten ab Stufe 7 laeuft der Rohwert bis -32; die
    // Faltung muss also mindestens einmal umgebrochen haben.
    Check.that("die Faltung hat mindestens einmal umgebrochen", la.wrapEdges > 0);

    // ---- Ein unerreichbarer Knoten wird gezaehlt, nicht verschwiegen ----
    List<TreeSet<Integer>> split = new ArrayList<TreeSet<Integer>>();
    split.add(cross(0, 100, 1, 100));   // 0
    split.add(cross(0, 200, 2, 100));   // 1, mit 0 verbunden ueber Stripe 0
    split.add(cross(5, 100, 6, 100));   // 2, voellig getrennt
    split.add(cross(5, 200, 7, 100));   // 3, nur mit 2 verbunden
    MelodyGraph sp = MelodyGraph.fromCrossings(split, PITCH);
    MelodyAssignment spa = MelodyAssigner.assign(sp, phrygisch, 0, 45, 3, 1,
        new ConstRandom(0.0));
    Check.eq("zwei Knoten sind nicht erreichbar", 2, spa.unreachedCount);
    Check.eq("ein unerreichbarer Knoten bekommt die Tonika",
        spa.scaleIndex[0], spa.scaleIndex[2]);
    Check.eq("und die Tiefe -1 als Kennzeichen", -1, spa.depth[2]);
    Check.that("der Bericht nennt es", spa.report().contains("nicht erreichbar"));

    // ---- Pentatonik: andere Skalenlaenge, andere Faltungsbreite ----
    MelodyAssignment pa = MelodyAssigner.assign(sg, pentatonik, 0, 45, 3, 1,
        new ConstRandom(0.0));
    Check.eq("Pentatonik faltet auf 15 Stufen", 15, pa.notesPerOctaveSet);
    Check.eq("Startknoten in der mittleren Oktave (5 Stufen)", 5, pa.scaleIndex[0]);
    for (int i = 0; i < pa.nodeCount; i++) {
      Check.that("Pentatonik: Stufe im Bereich",
          pa.scaleIndex[i] >= 0 && pa.scaleIndex[i] < 15);
    }

    // ---- numOctaves 1: die Faltung wird eng, darf aber nicht brechen ----
    MelodyAssignment one = MelodyAssigner.assign(sg, phrygisch, 0, 45, 1, 1,
        new ConstRandom(0.0));
    Check.eq("eine Oktave faltet auf die Skalenlaenge", 7, one.notesPerOctaveSet);
    Check.eq("Startknoten liegt bei 1 Oktave auf Stufe 0", 0, one.scaleIndex[0]);
    for (int i = 0; i < one.nodeCount; i++) {
      Check.that("eine Oktave: Stufe im Bereich",
          one.scaleIndex[i] >= 0 && one.scaleIndex[i] < 7);
      Check.that("eine Oktave: midiNote im Bereich",
          one.midiNote[i] >= 45 && one.midiNote[i] <= 45 + 10);
    }
    // numOctaves 0 darf keine Division durch 0 ergeben
    MelodyAssignment zero = MelodyAssigner.assign(sg, phrygisch, 0, 45, 0, 1,
        new ConstRandom(0.0));
    Check.eq("numOctaves 0 zaehlt wie 1", 7, zero.notesPerOctaveSet);

    // ---- Gegenprobe an den echten Daten, ueber alle acht Modi ----
    File real = new File("data/nodeCrossings.txt");
    if (real.isFile()) {
      NodeCrossingStore store = new NodeCrossingStore(30, PITCH);
      store.load(real.getPath());
      MelodyGraph rg = MelodyGraph.fromCrossings(store.crossings(), PITCH);
      int start = rg.defaultStartNode();
      int threshold = rg.hubThreshold(0.75f);
      for (int m = 0; m < MelodyModes.count(); m++) {
        MelodyMode mode = MelodyModes.at(m);
        MelodyAssignment a = MelodyAssigner.assign(rg, mode, start, 45, 3,
            threshold, new FixedRandom(new double[] {
                0.13, 0.71, 0.42, 0.08, 0.95, 0.55, 0.27, 0.63, 0.81, 0.36 }));
        int max = 45 + mode.scale[mode.length() - 1] + 12 * 2;
        for (int i = 0; i < a.nodeCount; i++) {
          Check.that(mode.key + ": Stufe im gefalteten Bereich",
              a.scaleIndex[i] >= 0 && a.scaleIndex[i] < a.notesPerOctaveSet);
          Check.that(mode.key + ": midiNote in der Spanne",
              a.midiNote[i] >= 45 && a.midiNote[i] <= max);
        }
        Check.eq(mode.key + ": jeder Knoten wird erreicht", 0, a.unreachedCount);
        Check.that(mode.key + ": der Startknoten ist die Tonika",
            a.scaleIndex[start] == mode.length() * (3 / 2));
        // Rueckwaerts- plus Baumkanten ergeben zusammen alle Kanten. Die
        // Zaehlung darf also keine Kante doppelt oder gar nicht sehen.
        Check.eq(mode.key + ": Baumkanten plus Rueckwaertskanten ergeben alle Kanten",
            rg.edgeCount(), (a.nodeCount - 1 - a.unreachedCount) + a.backEdges);
        Check.that(mode.key + ": Umbruchkanten sind die Minderheit",
            a.wrapEdges <= rg.edgeCount());
      }
    } else {
      System.out.println("  Hinweis: data/nodeCrossings.txt fehlt, "
          + "Gegenprobe an echten Daten uebersprungen");
    }

    System.exit(Check.report("MelodyAssignerTest"));
  }
}
