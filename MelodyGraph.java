import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

// Die Nachbarschaftsrelation zwischen den Kreuzungen des LED-Netzes.
//
// Zwei Nodes sind benachbart, wenn ein Impuls direkt von einem zum anderen
// wandern kann, ohne einen dritten zu passieren: sie liegen auf DERSELBEN
// Stripe und folgen dort - nach Position innerhalb der Stripe sortiert -
// unmittelbar aufeinander. Diese Relation ist im laufenden Sketch nirgends
// materialisiert (LedNetworkTransportEffect arbeitet LED-fuer-LED, nicht
// Node-fuer-Node), sie wird hier einmalig aus der Kreuzungsliste abgeleitet.
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt - dasselbe Muster wie NodeCrossingStore
// und StripeTreeStore. Die Kreuzungsliste wird hereingegeben statt gelesen,
// aus demselben Grund.
class MelodyGraph {

  // Nachbarn je Node, aufsteigend nach nodeId, jede Kante genau einmal je
  // Richtung. Mehrfachkanten (zwei Nodes, die sich auf zwei verschiedenen
  // Stripes beruehren) sind zu einer zusammengefasst - fuer die
  // Melodiezuordnung zaehlt "erreichbar", nicht "wie oft".
  private final int[][] adjacency;

  // Kleinster LED-Index je Node. Das ist der Sortierschluessel der
  // Nachbarn-Prioritaet (Konzept, Schritt 3a) und damit Teil der
  // Reproduzierbarkeit - er darf nicht aus der Kreuzungsreihenfolge kommen,
  // sondern muss an der Geometrie haengen.
  private final int[] minLedIndex;

  private final int edges;

  private MelodyGraph(int[][] adjacency_, int[] minLedIndex_, int edges_) {
    adjacency = adjacency_;
    minLedIndex = minLedIndex_;
    edges = edges_;
  }

  // Baut den Graphen aus derselben Liste, die NodeCrossingStore haelt und
  // LedInNetInfo.applyCrossings verarbeitet: eine Menge globaler LED-Indizes
  // je Kreuzung, der Listenindex ist die nodeId.
  static MelodyGraph fromCrossings(List<TreeSet<Integer>> crossings,
      int numLedsPerStripe) {
    int n = (crossings == null) ? 0 : crossings.size();
    int pitch = (numLedsPerStripe > 0) ? numLedsPerStripe : 1;

    int[] minLed = new int[n];
    // Alle (Stripe, Position, Node)-Tripel einsammeln. Ein Node kann an
    // mehreren Stripes beteiligt sein - LedNetworkNode.ledIndices ist ein
    // TreeSet und nicht auf zwei Elemente begrenzt, auch wenn der aktuelle
    // Datenstand nur Zweierkreuzungen enthaelt.
    List<int[]> points = new ArrayList<int[]>();
    for (int id = 0; id < n; id++) {
      TreeSet<Integer> leds = crossings.get(id);
      int smallest = Integer.MAX_VALUE;
      if (leds != null) {
        for (Integer led : leds) {
          int idx = led.intValue();
          if (idx < 0) {
            continue;
          }
          if (idx < smallest) {
            smallest = idx;
          }
          points.add(new int[] { idx / pitch, idx % pitch, id });
        }
      }
      minLed[id] = (smallest == Integer.MAX_VALUE) ? -1 : smallest;
    }

    // Nach Stripe, dann nach Position - eine einzige Sortierung reicht,
    // danach stehen die Punkte jeder Stripe zusammenhaengend und in der
    // richtigen Reihenfolge.
    int[][] sorted = points.toArray(new int[points.size()][]);
    Arrays.sort(sorted, new java.util.Comparator<int[]>() {
      public int compare(int[] a, int[] b) {
        if (a[0] != b[0]) {
          return a[0] - b[0];
        }
        if (a[1] != b[1]) {
          return a[1] - b[1];
        }
        return a[2] - b[2];
      }
    });

    List<TreeSet<Integer>> neighbourSets = new ArrayList<TreeSet<Integer>>(n);
    for (int i = 0; i < n; i++) {
      neighbourSets.add(new TreeSet<Integer>());
    }
    for (int i = 1; i < sorted.length; i++) {
      if (sorted[i][0] != sorted[i - 1][0]) {
        continue; // Stripewechsel - ueber das Stripe-Ende hinweg gibt es keine Kante
      }
      int a = sorted[i - 1][2];
      int b = sorted[i][2];
      if (a == b) {
        // Derselbe Node zweimal auf derselben Stripe (eine Kreuzung, deren
        // beide LEDs auf einer Stripe liegen). Keine Schleife eintragen - ein
        // Node ist nie sein eigener Nachbar, sonst haette die BFS eine Kante
        // ins Nichts.
        continue;
      }
      neighbourSets.get(a).add(Integer.valueOf(b));
      neighbourSets.get(b).add(Integer.valueOf(a));
    }

    int[][] adj = new int[n][];
    int edgeCount = 0;
    for (int i = 0; i < n; i++) {
      TreeSet<Integer> set = neighbourSets.get(i);
      int[] row = new int[set.size()];
      int k = 0;
      for (Integer v : set) {
        row[k++] = v.intValue();
      }
      adj[i] = row;
      edgeCount += row.length;
    }
    return new MelodyGraph(adj, minLed, edgeCount / 2);
  }

  int nodeCount() {
    return adjacency.length;
  }

  // Aufsteigend nach nodeId. Die Prioritaetsreihenfolge der BFS ist eine
  // ANDERE (Schritt 3a) und wird dort gebildet - hier bleibt es bei der
  // stabilen, naheliegenden Ordnung.
  int[] neighbors(int node) {
    if (node < 0 || node >= adjacency.length) {
      return new int[0];
    }
    return adjacency[node];
  }

  int degree(int node) {
    return neighbors(node).length;
  }

  int minLedIndex(int node) {
    if (node < 0 || node >= minLedIndex.length) {
      return -1;
    }
    return minLedIndex[node];
  }

  // Jede ungerichtete Kante einmal gezaehlt.
  int edgeCount() {
    return edges;
  }

  // Die Schwelle, ab der ein Node als Landmarke gilt (Konzept, Schritt 3:
  // "z.B. 75. Perzentil aller Grade"). Nearest-Rank auf der sortierten
  // Gradliste.
  //
  // Mindestens 1: bei einer Schwelle von 0 waere JEDER Node eine Landmarke,
  // auch die mit Grad 0, und die gewichtete Ziehung der normalen Kanten
  // fiele ersatzlos aus.
  int hubThreshold(float percentile) {
    if (adjacency.length == 0) {
      return 1;
    }
    int[] degrees = new int[adjacency.length];
    for (int i = 0; i < adjacency.length; i++) {
      degrees[i] = adjacency[i].length;
    }
    Arrays.sort(degrees);
    float p = percentile;
    if (p < 0f) { p = 0f; }
    if (p > 1f) { p = 1f; }
    int rank = (int) Math.ceil(p * degrees.length) - 1;
    if (rank < 0) { rank = 0; }
    if (rank >= degrees.length) { rank = degrees.length - 1; }
    int t = degrees[rank];
    return (t < 1) ? 1 : t;
  }

  // Der Vorschlag fuer /net/melody/startNode: hoechster Grad, bei Gleichstand
  // kleinste nodeId. Ein Hub hat die meisten direkten Nachbarn, die Tonika
  // steht damit von vornherein mit moeglichst vielen Knoten in direkter
  // Intervallbeziehung, und die BFS-Tiefe des Graphen wird kleiner - weniger
  // Tiefe heisst weniger akkumulierte Drift.
  //
  // -1 bei leerem Graph. Der Aufrufer muss das abfangen; eine 0
  // zurueckzugeben hiesse, einen Knoten zu benennen, den es nicht gibt.
  int defaultStartNode() {
    int best = -1;
    int bestDegree = -1;
    for (int i = 0; i < adjacency.length; i++) {
      if (adjacency[i].length > bestDegree) {
        bestDegree = adjacency[i].length;
        best = i;
      }
    }
    return best;
  }

  // Einzeiler fuer die Konsole beim Start.
  String report() {
    if (adjacency.length == 0) {
      return "Melodie-Graph: keine Kreuzungen, keine Zuordnung moeglich";
    }
    int isolated = 0;
    int maxDegree = 0;
    for (int i = 0; i < adjacency.length; i++) {
      if (adjacency[i].length == 0) {
        isolated++;
      }
      if (adjacency[i].length > maxDegree) {
        maxDegree = adjacency[i].length;
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Melodie-Graph: ").append(adjacency.length).append(" Knoten, ")
      .append(edges).append(" Kanten, hoechster Grad ").append(maxDegree)
      .append(", Hub-Schwelle (75%) ").append(hubThreshold(0.75f));
    if (isolated > 0) {
      sb.append(", ").append(isolated).append(" ohne Nachbarn");
    }
    return sb.toString();
  }
}
