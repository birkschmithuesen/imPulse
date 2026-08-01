import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class MelodyGraphTest {

  static final int PITCH = 600;

  // Kreuzung aus zwei (Stripe, Position)-Paaren, in globalen LED-Indizes.
  static TreeSet<Integer> cross(int s1, int p1, int s2, int p2) {
    TreeSet<Integer> t = new TreeSet<Integer>();
    t.add(Integer.valueOf(s1 * PITCH + p1));
    t.add(Integer.valueOf(s2 * PITCH + p2));
    return t;
  }

  static boolean adjacent(MelodyGraph g, int a, int b) {
    int[] n = g.neighbors(a);
    for (int i = 0; i < n.length; i++) {
      if (n[i] == b) {
        return true;
      }
    }
    return false;
  }

  public static void main(String[] args) throws Exception {
    // ---- Leerer Graph ----
    MelodyGraph empty = MelodyGraph.fromCrossings(
        new ArrayList<TreeSet<Integer>>(), PITCH);
    Check.eq("leerer Graph hat keine Knoten", 0, empty.nodeCount());
    Check.eq("leerer Graph hat keine Kanten", 0, empty.edgeCount());
    Check.eq("leerer Graph hat keinen Startknoten", -1, empty.defaultStartNode());
    Check.that("leerer Graph meldet das", empty.report().contains("keine Kreuzungen"));
    Check.eq("Nachbarn eines Knotens ausserhalb", 0, empty.neighbors(3).length);
    Check.eq("hubThreshold bleibt mindestens 1", 1, empty.hubThreshold(0.75f));

    // ---- Eine Kette auf einer Stripe ----
    // Drei Kreuzungen auf Stripe 0 bei Position 100, 200, 300; die jeweils
    // zweite LED liegt auf verschiedenen anderen Stripes, damit die Kreuzungen
    // nur ueber Stripe 0 zusammenhaengen.
    List<TreeSet<Integer>> chain = new ArrayList<TreeSet<Integer>>();
    chain.add(cross(0, 100, 10, 50));   // 0
    chain.add(cross(0, 200, 11, 50));   // 1
    chain.add(cross(0, 300, 12, 50));   // 2
    MelodyGraph g = MelodyGraph.fromCrossings(chain, PITCH);
    Check.eq("drei Knoten", 3, g.nodeCount());
    Check.that("0 und 1 sind benachbart", adjacent(g, 0, 1));
    Check.that("1 und 2 sind benachbart", adjacent(g, 1, 2));
    // Der Kern der Ableitung: 0 und 2 liegen auf derselben Stripe, aber 1
    // liegt dazwischen. Ein Impuls kann also nicht direkt von 0 nach 2.
    Check.that("0 und 2 sind NICHT benachbart, 1 liegt dazwischen",
        !adjacent(g, 0, 2));
    Check.eq("zwei Kanten", 2, g.edgeCount());
    Check.eq("Grad des mittleren Knotens", 2, g.degree(1));
    Check.eq("Grad des ersten Knotens", 1, g.degree(0));

    // ---- Nachbarn sind aufsteigend und symmetrisch ----
    for (int i = 0; i < g.nodeCount(); i++) {
      int[] nb = g.neighbors(i);
      for (int k = 1; k < nb.length; k++) {
        Check.that("Nachbarn aufsteigend", nb[k] > nb[k - 1]);
      }
      for (int k = 0; k < nb.length; k++) {
        Check.that("Adjazenz ist symmetrisch", adjacent(g, nb[k], i));
        Check.that("kein Knoten ist sein eigener Nachbar", nb[k] != i);
      }
    }

    // ---- Ein Knoten an mehreren Stripes bekommt Nachbarn aus beiden ----
    // Node 0 sitzt auf Stripe 0 (Pos 100) und Stripe 1 (Pos 100).
    // Node 1 sitzt auf Stripe 0 (Pos 200), Node 2 auf Stripe 1 (Pos 200).
    List<TreeSet<Integer>> twoStripes = new ArrayList<TreeSet<Integer>>();
    twoStripes.add(cross(0, 100, 1, 100));  // 0
    twoStripes.add(cross(0, 200, 10, 50));  // 1
    twoStripes.add(cross(1, 200, 11, 50));  // 2
    MelodyGraph g2 = MelodyGraph.fromCrossings(twoStripes, PITCH);
    Check.eq("Knoten an zwei Stripes hat Grad 2", 2, g2.degree(0));
    Check.that("Nachbar ueber die erste Stripe", adjacent(g2, 0, 1));
    Check.that("Nachbar ueber die zweite Stripe", adjacent(g2, 0, 2));
    Check.that("die zwei Endknoten sind nicht direkt verbunden",
        !adjacent(g2, 1, 2));

    // ---- Mehrfachkante zaehlt einmal ----
    // Zwei Nodes, die sich auf ZWEI Stripes beruehren, ohne dass ein dritter
    // dazwischen liegt: die Kante darf nicht doppelt in der Nachbarliste
    // stehen, sonst zaehlte degree() sie zweimal und die Hub-Schwelle waere
    // verfaelscht.
    List<TreeSet<Integer>> doubled = new ArrayList<TreeSet<Integer>>();
    doubled.add(cross(0, 100, 1, 100));  // 0
    doubled.add(cross(0, 200, 1, 200));  // 1
    MelodyGraph g3 = MelodyGraph.fromCrossings(doubled, PITCH);
    Check.eq("Mehrfachkante ist eine Kante", 1, g3.edgeCount());
    Check.eq("Mehrfachkante zaehlt einmal im Grad", 1, g3.degree(0));

    // ---- Dreieck-Zyklus (das Beispiel aus dem Konzept, Abschnitt 6/7) ----
    // 90-70-71 als echtes Dreieck: drei Knoten, paarweise verbunden, jedes
    // Paar ueber eine eigene Stripe.
    List<TreeSet<Integer>> triangle = new ArrayList<TreeSet<Integer>>();
    triangle.add(cross(0, 100, 1, 100));  // 0: Stripe 0 und 1
    triangle.add(cross(1, 200, 2, 100));  // 1: Stripe 1 und 2
    triangle.add(cross(2, 200, 0, 200));  // 2: Stripe 2 und 0
    MelodyGraph tri = MelodyGraph.fromCrossings(triangle, PITCH);
    Check.eq("Dreieck hat drei Kanten", 3, tri.edgeCount());
    Check.that("Dreieck 0-1", adjacent(tri, 0, 1));
    Check.that("Dreieck 1-2", adjacent(tri, 1, 2));
    Check.that("Dreieck 2-0", adjacent(tri, 2, 0));

    // ---- Kreuzung mit beiden LEDs auf derselben Stripe ----
    // Erzeugt keine Schleife auf sich selbst.
    List<TreeSet<Integer>> selfStripe = new ArrayList<TreeSet<Integer>>();
    selfStripe.add(cross(0, 100, 0, 300));  // beide LEDs auf Stripe 0
    selfStripe.add(cross(0, 200, 5, 100));  // liegt DAZWISCHEN
    MelodyGraph g4 = MelodyGraph.fromCrossings(selfStripe, PITCH);
    Check.that("keine Schleife auf sich selbst", !adjacent(g4, 0, 0));
    // Node 1 liegt zwischen den zwei LEDs von Node 0 - also zweimal benachbart,
    // aber nur eine Kante.
    Check.eq("nur eine Kante trotz zweier Beruehrungen", 1, g4.edgeCount());

    // ---- minLedIndex ----
    Check.eq("kleinster LED-Index von Node 0", 0 * PITCH + 100, g.minLedIndex(0));
    // Node 2 ist cross(0, 300, 12, 50): der kleinere der beiden Indizes ist
    // der auf Stripe 0, nicht der auf Stripe 12.
    Check.eq("kleinster LED-Index von Node 2", 0 * PITCH + 300, g.minLedIndex(2));
    Check.eq("minLedIndex ausserhalb", -1, g.minLedIndex(99));

    // ---- defaultStartNode: hoechster Grad, bei Gleichstand kleinste Id ----
    Check.eq("hoechster Grad gewinnt", 1, g.defaultStartNode());
    // Bei Gleichstand die kleinere Id: in der Doppelkante haben beide Grad 1.
    Check.eq("bei Gleichstand die kleinste nodeId", 0, g3.defaultStartNode());

    // ---- hubThreshold ----
    // Grade in der Kette: 1, 2, 1 -> sortiert 1,1,2. 75. Perzentil,
    // Nearest-Rank: ceil(0.75*3)-1 = 2 -> Grad 2.
    Check.eq("75. Perzentil der Kette", 2, g.hubThreshold(0.75f));
    Check.eq("0. Perzentil klemmt auf mindestens 1", 1, g.hubThreshold(0f));
    Check.eq("100. Perzentil ist der hoechste Grad", 2, g.hubThreshold(1f));
    Check.eq("Perzentil ueber 1 wird geklemmt", 2, g.hubThreshold(5f));
    Check.eq("Perzentil unter 0 wird geklemmt", 1, g.hubThreshold(-2f));

    // ---- Gegenprobe an der echten Kreuzungsdatei ----
    // Nur Invarianten, KEINE absoluten Zahlen: data/nodeCrossings.txt waechst
    // waehrend der Kalibrierung, jede eingetragene Knotenzahl ist am naechsten
    // Tag falsch (CLAUDE.md, Konventionen).
    File real = new File("data/nodeCrossings.txt");
    if (real.isFile()) {
      NodeCrossingStore store = new NodeCrossingStore(30, PITCH);
      store.load(real.getPath());
      MelodyGraph rg = MelodyGraph.fromCrossings(store.crossings(), PITCH);
      Check.eq("Graph hat so viele Knoten wie die Datei Zeilen",
          store.size(), rg.nodeCount());
      Check.that("die echte Datei ergibt Kanten", rg.edgeCount() > 0);
      int sumDegrees = 0;
      for (int i = 0; i < rg.nodeCount(); i++) {
        int[] nb = rg.neighbors(i);
        sumDegrees += nb.length;
        Check.eq("degree passt zur Nachbarliste", nb.length, rg.degree(i));
        for (int k = 0; k < nb.length; k++) {
          Check.that("Adjazenz symmetrisch (echte Daten)", adjacent(rg, nb[k], i));
          Check.that("kein Selbstnachbar (echte Daten)", nb[k] != i);
          if (k > 0) {
            Check.that("Nachbarn aufsteigend (echte Daten)", nb[k] > nb[k - 1]);
          }
        }
        Check.that("jeder Knoten hat einen LED-Index", rg.minLedIndex(i) >= 0);
      }
      Check.eq("Gradsumme ist doppelte Kantenzahl", 2 * rg.edgeCount(), sumDegrees);
      Check.that("es gibt einen Startknoten", rg.defaultStartNode() >= 0);
      Check.that("Hub-Schwelle mindestens 1", rg.hubThreshold(0.75f) >= 1);
    } else {
      System.out.println("  Hinweis: data/nodeCrossings.txt fehlt, "
          + "Gegenprobe an echten Daten uebersprungen");
    }

    System.exit(Check.report("MelodyGraphTest"));
  }
}
