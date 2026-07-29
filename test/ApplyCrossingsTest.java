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

    System.exit(Check.report("ApplyCrossingsTest"));
  }

  static TreeSet<Integer> pair(int a, int b) {
    TreeSet<Integer> s = new TreeSet<Integer>();
    s.add(a); s.add(b);
    return s;
  }
}
