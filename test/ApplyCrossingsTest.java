import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class ApplyCrossingsTest {
  public static void main(String[] args) {
    int stripes = 4;
    int perStripe = 100;
    LedInNetInfo[] info = LedInNetInfo.buildNetInfo(stripes, perStripe);
    ArrayList<LedNetworkNode> nodes = new ArrayList<LedNetworkNode>();

    List<TreeSet<Integer>> a = new ArrayList<TreeSet<Integer>>();
    a.add(pair(10, 150));
    a.add(pair(20, 250));
    LedInNetInfo.applyCrossings(a, info, nodes);

    Check.eq("zwei Nodes", 2, nodes.size());
    Check.eq("Node 0 hat id 0", 0, nodes.get(0).id);
    Check.eq("Node 1 hat id 1", 1, nodes.get(1).id);
    Check.that("LED 10 gehoert zu Node 0", info[10].partOfNode == nodes.get(0));
    Check.that("LED 150 gehoert zu Node 0", info[150].partOfNode == nodes.get(0));
    Check.that("LED 20 gehoert zu Node 1", info[20].partOfNode == nodes.get(1));
    Check.that("LED 11 gehoert zu keinem Node", info[11].partOfNode == null);

    // Erneutes Anwenden mit weniger Kreuzungen muss die alte Zuordnung loeschen
    ArrayList<LedNetworkNode> sameList = nodes;
    List<TreeSet<Integer>> b = new ArrayList<TreeSet<Integer>>();
    b.add(pair(30, 350));
    LedInNetInfo.applyCrossings(b, info, sameList);

    Check.eq("jetzt ein Node", 1, sameList.size());
    Check.that("dieselbe Listeninstanz", sameList == nodes);
    Check.that("LED 10 ist wieder frei", info[10].partOfNode == null);
    Check.that("LED 150 ist wieder frei", info[150].partOfNode == null);
    Check.that("LED 30 gehoert zum neuen Node", info[30].partOfNode == sameList.get(0));

    // ---- applyPositions: Mittelwert ueber die LEDs eines Knotens ----
    // Kleine synthetische Geometrie, wie in LedPositionMapTest.
    final int PSTRIPES = 4;
    final int PPER = 20;
    LedInNetInfo[] pInfos = LedInNetInfo.buildNetInfo(PSTRIPES, PPER);
    ArrayList<TreeSet<Integer>> pCross = new ArrayList<TreeSet<Integer>>();
    TreeSet<Integer> pair = new TreeSet<Integer>();
    pair.add(Integer.valueOf(10));            // Stripe 0, LED 10
    pair.add(Integer.valueOf(PPER + 10));     // Stripe 1, LED 10
    pCross.add(pair);
    ArrayList<LedNetworkNode> pNodes = new ArrayList<LedNetworkNode>();
    LedInNetInfo.applyCrossings(pCross, pInfos, pNodes);
    Check.eq("ein Knoten aufgebaut", 1, pNodes.size());

    LedAnchorStore pStore = new LedAnchorStore(PSTRIPES, PPER, 14f, 8f, 0.5f);
    LedPositionMap pMap = new LedPositionMap(PSTRIPES, PPER, 14f, 8f);

    // Noch kein Anker: der Knoten bleibt bei (0,0)
    pMap.apply(pStore);
    LedNetworkNode.applyPositions(pMap, pNodes);
    Check.near("ohne Anker bleibt posX null", 0.0, pNodes.get(0).posX, 1e-4);
    Check.near("ohne Anker bleibt posY null", 0.0, pNodes.get(0).posY, 1e-4);

    // Anker auf der Kreuzung gesetzt: set() verteilt ihn auf beide LEDs,
    // beide Positionen sind identisch, der Mittelwert ist genau der Anker.
    Check.that("Kreuzungsanker gesetzt", pStore.set(10, 2.5f, -1.5f, pCross));
    Check.that("die Partner-LED wurde mitgesetzt", pStore.has(PPER + 10));
    pMap.apply(pStore);
    LedNetworkNode.applyPositions(pMap, pNodes);
    Check.near("posX ist der Anker", 2.5, pNodes.get(0).posX, 1e-4);
    Check.near("posY ist der Anker", -1.5, pNodes.get(0).posY, 1e-4);

    // Weichen die beiden LEDs ab, wird gemittelt. Dafuer den Kreuzungsanker
    // entfernen und die zwei Stripes ueber ihre Enden unterschiedlich
    // aufspannen.
    Check.that("Kreuzungsanker auf Stripe 0 entfernt", pStore.remove(10));
    Check.that("Kreuzungsanker auf Stripe 1 entfernt", pStore.remove(PPER + 10));
    // Stripe 0: LED 0 -> (0,0), LED 20-1 -> (0,0) ... konstant 0
    Check.that("Stripe 0 Anfang", pStore.set(0, 0f, 0f, pCross));
    Check.that("Stripe 0 Ende", pStore.set(PPER - 1, 0f, 0f, pCross));
    // Stripe 1: LED 0 -> (4,2), LED 19 -> (4,2) ... konstant (4,2)
    Check.that("Stripe 1 Anfang", pStore.set(PPER + 0, 4f, 2f, pCross));
    Check.that("Stripe 1 Ende", pStore.set(PPER + PPER - 1, 4f, 2f, pCross));
    pMap.apply(pStore);
    LedNetworkNode.applyPositions(pMap, pNodes);
    Check.near("posX ist der Mittelwert beider Stripes", 2.0, pNodes.get(0).posX, 1e-4);
    Check.near("posY ist der Mittelwert beider Stripes", 1.0, pNodes.get(0).posY, 1e-4);

    System.exit(Check.report("ApplyCrossingsTest"));
  }

  static TreeSet<Integer> pair(int a, int b) {
    TreeSet<Integer> s = new TreeSet<Integer>();
    s.add(a); s.add(b);
    return s;
  }
}
