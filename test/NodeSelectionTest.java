public class NodeSelectionTest {

  public static void main(String[] args) throws Exception {
    // ---- Ausgangszustand ----
    NodeSelection sel = new NodeSelection();
    Check.eq("ohne Auswahl", -1, sel.index());
    Check.that("ohne Auswahl ist nichts gewaehlt", !sel.hasSelection());

    // ---- Vorwaerts blaettern ----
    sel.next(3);
    Check.eq("erstes next() waehlt den ersten Eintrag", 0, sel.index());
    Check.that("jetzt ist etwas gewaehlt", sel.hasSelection());
    sel.next(3);
    sel.next(3);
    Check.eq("next() laeuft bis zum letzten", 2, sel.index());
    sel.next(3);
    Check.eq("am Ende bleibt die Auswahl stehen, kein Umlauf", 2, sel.index());

    // ---- Rueckwaerts blaettern ----
    sel.prev(3);
    Check.eq("prev() geht zurueck", 1, sel.index());
    sel.prev(3);
    Check.eq("prev() bis zum ersten", 0, sel.index());
    sel.prev(3);
    Check.eq("prev() hebt am Anfang die Auswahl auf", -1, sel.index());

    // ---- prev() aus dem Nichts greift den letzten ----
    NodeSelection fromEnd = new NodeSelection();
    fromEnd.prev(4);
    Check.eq("erstes prev() waehlt den letzten Eintrag", 3, fromEnd.index());

    // ---- Leere Liste ----
    NodeSelection onEmpty = new NodeSelection();
    onEmpty.next(0);
    Check.eq("next() auf leerer Liste waehlt nichts", -1, onEmpty.index());
    onEmpty.prev(0);
    Check.eq("prev() auf leerer Liste waehlt nichts", -1, onEmpty.index());

    // ---- Klemmen, wenn die Liste schrumpft ----
    NodeSelection shrink = new NodeSelection();
    shrink.next(5);
    shrink.next(5);
    shrink.next(5);
    Check.eq("Auswahl vor dem Schrumpfen", 2, shrink.index());
    shrink.clampTo(5);
    Check.eq("clampTo bei unveraenderter Groesse laesst die Auswahl stehen", 2, shrink.index());
    shrink.clampTo(2);
    Check.eq("clampTo zieht die Auswahl auf den letzten Eintrag", 1, shrink.index());
    shrink.clampTo(0);
    Check.eq("clampTo auf leere Liste hebt die Auswahl auf", -1, shrink.index());

    // ---- Auswahl von Hand aufheben ----
    NodeSelection cleared = new NodeSelection();
    cleared.next(3);
    cleared.clear();
    Check.eq("clear() hebt die Auswahl auf", -1, cleared.index());
    Check.that("nach clear() ist nichts gewaehlt", !cleared.hasSelection());

    System.exit(Check.report("NodeSelectionTest"));
  }
}
