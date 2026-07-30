import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class LedAnchorStoreTest {

  // Kleine synthetische Geometrie. NICHT die echten 30 x 600 und NICHT
  // data/nodeCrossings.txt - die Kreuzungsdatei waechst waehrend der
  // Kalibrierung, ein Test dagegen waere beim naechsten S rot.
  static final int STRIPES = 4;
  static final int PER_STRIPE = 20;
  static final float FOOT_X = 14f;
  static final float FOOT_Y = 8f;
  // 20 LEDs auf 10 m. Grosszuegig, damit die Weglaengen-Warnung aus Aufgabe 3
  // in diesen Tests nie anspringt und nichts verschleiert.
  static final float PITCH = 0.5f;
  static final double TOL = 1e-4;

  static final List<TreeSet<Integer>> NO_CROSSINGS = new ArrayList<TreeSet<Integer>>();

  static LedAnchorStore store() {
    return new LedAnchorStore(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y, PITCH);
  }

  static List<TreeSet<Integer>> crossing(int a, int b) {
    List<TreeSet<Integer>> list = new ArrayList<TreeSet<Integer>>();
    TreeSet<Integer> pair = new TreeSet<Integer>();
    pair.add(Integer.valueOf(a));
    pair.add(Integer.valueOf(b));
    list.add(pair);
    return list;
  }

  public static void main(String[] args) throws Exception {
    // ---- Gueltiges Setzen ----
    LedAnchorStore s = store();
    Check.eq("leerer Store", 0, s.size());
    Check.that("nichts gesetzt", !s.has(0));
    Check.near("x einer ungesetzten LED ist 0", 0.0, s.x(0), TOL);
    Check.near("y einer ungesetzten LED ist 0", 0.0, s.y(0), TOL);

    Check.that("gueltiger Anker wird angenommen", s.set(5, -3.25f, 1.1f, NO_CROSSINGS));
    Check.eq("ein Anker", 1, s.size());
    Check.that("der Anker ist da", s.has(5));
    Check.near("x kam an", -3.25, s.x(5), TOL);
    Check.near("y kam an", 1.1, s.y(5), TOL);
    Check.that("keine Warnung bei einem einzelnen Anker", !s.lastWasWarning());
    Check.that("die Meldung sagt etwas", s.lastMessage().length() > 0);

    // ---- Ueberschreiben ----
    Check.that("derselbe Anker wird ueberschrieben", s.set(5, 2f, -2f, NO_CROSSINGS));
    Check.eq("weiterhin ein Anker", 1, s.size());
    Check.near("der neue x-Wert gilt", 2.0, s.x(5), TOL);
    Check.near("der neue y-Wert gilt", -2.0, s.y(5), TOL);

    // ---- Index ausserhalb des Bereichs ----
    LedAnchorStore r = store();
    Check.that("negativer Index wird abgewiesen", !r.set(-1, 0f, 0f, NO_CROSSINGS));
    Check.that("die Meldung nennt den Index",
        r.lastMessage().indexOf("Index") >= 0);
    Check.that("Index hinter dem Ende wird abgewiesen",
        !r.set(STRIPES * PER_STRIPE, 0f, 0f, NO_CROSSINGS));
    Check.eq("nichts davon wurde gespeichert", 0, r.size());
    Check.that("letzter gueltiger Index geht", r.set(STRIPES * PER_STRIPE - 1, 0f, 0f, NO_CROSSINGS));

    // ---- Position ausserhalb der Grundflaeche ----
    LedAnchorStore f = store();
    Check.that("zu weit rechts wird abgewiesen", !f.set(0, 7.01f, 0f, NO_CROSSINGS));
    Check.that("die Meldung nennt die Grundflaeche",
        f.lastMessage().indexOf("Grundflaeche") >= 0);
    Check.that("zu weit links wird abgewiesen", !f.set(0, -7.01f, 0f, NO_CROSSINGS));
    Check.that("zu weit vorn wird abgewiesen", !f.set(0, 0f, 4.01f, NO_CROSSINGS));
    Check.that("zu weit hinten wird abgewiesen", !f.set(0, 0f, -4.01f, NO_CROSSINGS));
    Check.eq("nichts davon wurde gespeichert", 0, f.size());

    // Genau auf dem Rand ist erlaubt, sonst waere die aeusserste Ecke der
    // Grundflaeche nicht setzbar
    Check.that("rechte vordere Ecke genau auf dem Rand", f.set(0, 7f, 4f, NO_CROSSINGS));
    Check.that("linke hintere Ecke genau auf dem Rand", f.set(1, -7f, -4f, NO_CROSSINGS));
    Check.eq("beide Randpunkte gespeichert", 2, f.size());

    // ---- Verteilung innerhalb eines Knotens ----
    // Kreuzung {10, 30}: LED 10 auf Stripe 0, LED 30 auf Stripe 1.
    List<TreeSet<Integer>> cross = crossing(10, PER_STRIPE + 10);
    LedAnchorStore n = store();
    Check.that("Kreuzungsanker gesetzt", n.set(10, 1.5f, -0.5f, cross));
    Check.eq("zwei LEDs wurden gesetzt", 2, n.size());
    Check.that("die Partner-LED ist da", n.has(PER_STRIPE + 10));
    Check.near("Partner hat dasselbe x", 1.5, n.x(PER_STRIPE + 10), TOL);
    Check.near("Partner hat dasselbe y", -0.5, n.y(PER_STRIPE + 10), TOL);
    Check.that("die Meldung nennt die Zahl der mitgesetzten LEDs",
        n.lastMessage().indexOf("2") >= 0);

    // Auch von der anderen Seite der Kreuzung aus
    LedAnchorStore n2 = store();
    Check.that("von der zweiten LED aus gesetzt",
        n2.set(PER_STRIPE + 10, -1f, 2f, cross));
    Check.eq("wieder zwei LEDs", 2, n2.size());
    Check.that("die erste LED ist mitgesetzt", n2.has(10));
    Check.near("und hat denselben Wert", -1.0, n2.x(10), TOL);

    // Eine LED, die in keiner Kreuzung steht, setzt genau eine
    LedAnchorStore n3 = store();
    Check.that("LED ohne Kreuzung gesetzt", n3.set(7, 0f, 0f, cross));
    Check.eq("genau eine LED", 1, n3.size());

    // ---- anchorsOnStripe ----
    LedAnchorStore a = store();
    a.set(14, 0f, 0f, NO_CROSSINGS);
    a.set(2, 0f, 0f, NO_CROSSINGS);
    a.set(9, 0f, 0f, NO_CROSSINGS);
    a.set(PER_STRIPE + 5, 0f, 0f, NO_CROSSINGS);
    Check.eq("drei Anker auf Stripe 0", 3, a.anchorsOnStripe(0).size());
    Check.eq("einer auf Stripe 1", 1, a.anchorsOnStripe(1).size());
    Check.eq("keiner auf Stripe 2", 0, a.anchorsOnStripe(2).size());
    Integer[] onZero = a.anchorsOnStripe(0).toArray(new Integer[0]);
    Check.eq("aufsteigend sortiert, erster", 2, onZero[0].intValue());
    Check.eq("aufsteigend sortiert, zweiter", 9, onZero[1].intValue());
    Check.eq("aufsteigend sortiert, dritter", 14, onZero[2].intValue());
    Check.eq("anchorsOnStripe liefert globale Indizes",
        PER_STRIPE + 5, a.anchorsOnStripe(1).first().intValue());
    Check.eq("Stripe ausserhalb liefert leer", 0, a.anchorsOnStripe(99).size());

    // ---- all() ----
    Check.eq("all() hat alle Anker", 4, a.all().size());
    Check.that("all() ist nach Index sortiert", a.all().firstKey().intValue() == 2);

    // ---- geladen und neu ----
    LedAnchorStore l = store();
    l.set(0, 1f, 1f, NO_CROSSINGS);
    l.set(1, 1f, 1f, NO_CROSSINGS);
    Check.eq("frisch gesetzte Anker sind nicht geladen", 0, l.loadedCount());
    Check.eq("alle sind Sitzungseintraege", 2, l.sessionCount());
    Check.that("wasLoaded ist false", !l.wasLoaded(0));

    // ---- Loeschen ----
    Check.that("Loeschen eines vorhandenen Ankers", l.remove(0));
    Check.eq("einer weg", 1, l.size());
    Check.that("er ist weg", !l.has(0));
    Check.that("Loeschen eines nicht vorhandenen tut nichts", !l.remove(0));
    Check.that("die Meldung sagt es", l.lastMessage().length() > 0);

    // Loeschen einer Kreuzungs-LED entfernt NUR diese, nicht den Partner -
    // das Zusammenfassen ist Sache des Werkzeugs, nicht des Stores
    LedAnchorStore d = store();
    d.set(10, 1f, 1f, cross);
    Check.eq("zwei gesetzt", 2, d.size());
    Check.that("eine geloescht", d.remove(10));
    Check.eq("nur eine weg", 1, d.size());
    Check.that("der Partner steht noch", d.has(PER_STRIPE + 10));

    // ---- clearAll ----
    LedAnchorStore c = store();
    c.set(0, 1f, 1f, NO_CROSSINGS);
    c.set(5, 1f, 1f, NO_CROSSINGS);
    c.clearAll();
    Check.eq("clearAll raeumt alles", 0, c.size());
    Check.eq("und setzt loadedCount zurueck", 0, c.loadedCount());
    Check.that("die Meldung nennt die Anzahl", c.lastMessage().length() > 0);
    Check.that("danach ist wieder Setzen moeglich", c.set(0, 1f, 1f, NO_CROSSINGS));

    System.exit(Check.report("LedAnchorStoreTest"));
  }
}
