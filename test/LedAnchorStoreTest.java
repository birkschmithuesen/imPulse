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

    // ---- Weglaengen-Warnung ----
    // Zwei Anker auf Stripe 0 mit Index-Abstand 4. Bei PITCH 0.5 ist die
    // Weglaenge 2,0 m, die Warnschwelle also 2,5 m Luftlinie.

    // Knapp darunter: keine Warnung
    LedAnchorStore w1 = store();
    Check.that("erster Anker", w1.set(0, 0f, 0f, NO_CROSSINGS));
    Check.that("zweiter Anker bei 2,4 m", w1.set(4, 2.4f, 0f, NO_CROSSINGS));
    Check.that("2,4 m liegen unter der Schwelle von 2,5", !w1.lastWasWarning());
    Check.that("die Position ist gesetzt", w1.has(4));

    // Knapp darueber: Warnung, aber gesetzt
    LedAnchorStore w2 = store();
    Check.that("erster Anker", w2.set(0, 0f, 0f, NO_CROSSINGS));
    Check.that("zweiter Anker bei 2,6 m wird trotzdem angenommen",
        w2.set(4, 2.6f, 0f, NO_CROSSINGS));
    Check.that("2,6 m loesen die Warnung aus", w2.lastWasWarning());
    Check.that("die Meldung nennt die Luftlinie",
        w2.lastMessage().indexOf("Luftlinie") >= 0);
    Check.that("die Position ist trotz Warnung gesetzt", w2.has(4));
    Check.near("und zwar mit dem angegebenen Wert", 2.6, w2.x(4), TOL);

    // Genau auf der Schwelle: keine Warnung (verglichen wird mit >)
    LedAnchorStore w3 = store();
    w3.set(0, 0f, 0f, NO_CROSSINGS);
    Check.that("genau 2,5 m", w3.set(4, 2.5f, 0f, NO_CROSSINGS));
    Check.that("genau auf der Schwelle warnt nicht", !w3.lastWasWarning());

    // Diagonal gerechnet, nicht nur in x
    LedAnchorStore w4 = store();
    w4.set(0, 0f, 0f, NO_CROSSINGS);
    // 3-4-5-Dreieck: Luftlinie 5 m, Weglaenge 2 m -> Warnung
    Check.that("diagonal weit entfernt wird angenommen", w4.set(4, 3f, 4f, NO_CROSSINGS));
    Check.that("und warnt", w4.lastWasWarning());

    // Anker auf verschiedenen Stripes haben keine Weglaengen-Beziehung
    LedAnchorStore w5 = store();
    w5.set(0, -7f, -4f, NO_CROSSINGS);
    Check.that("Anker auf einem anderen Stripe", w5.set(PER_STRIPE, 7f, 4f, NO_CROSSINGS));
    Check.that("ueber Stripe-Grenzen wird nicht gewarnt", !w5.lastWasWarning());

    // Ein einzelner Anker auf dem Stripe hat keinen Nachbarn
    LedAnchorStore w6 = store();
    Check.that("einzelner Anker", w6.set(10, 7f, 4f, NO_CROSSINGS));
    Check.that("ohne Nachbarn keine Warnung", !w6.lastWasWarning());

    // Nur die UNMITTELBAREN Nachbarn werden geprueft: 0 -> 4 -> 8 ist je
    // Schritt zulaessig, 0 -> 8 waere es nicht (4,8 m gegen 4,0 + 0,5).
    LedAnchorStore w7 = store();
    w7.set(0, 0f, 0f, NO_CROSSINGS);
    w7.set(4, 2.4f, 0f, NO_CROSSINGS);
    Check.that("dritter Anker", w7.set(8, 4.8f, 0f, NO_CROSSINGS));
    Check.that("nur die unmittelbaren Nachbarn zaehlen", !w7.lastWasWarning());

    // Ein sauberes set() danach loescht die Warnung wieder
    LedAnchorStore w8 = store();
    w8.set(0, 0f, 0f, NO_CROSSINGS);
    w8.set(4, 3f, 4f, NO_CROSSINGS);
    Check.that("Warnung steht", w8.lastWasWarning());
    Check.that("sauberer Anker auf einem anderen Stripe",
        w8.set(2 * PER_STRIPE, 0f, 0f, NO_CROSSINGS));
    Check.that("die Warnung ist zurueckgesetzt", !w8.lastWasWarning());

    // Auch eine Ablehnung setzt die Warnung zurueck
    LedAnchorStore w9 = store();
    w9.set(0, 0f, 0f, NO_CROSSINGS);
    w9.set(4, 3f, 4f, NO_CROSSINGS);
    Check.that("Warnung steht", w9.lastWasWarning());
    Check.that("abgelehnt wegen Grundflaeche", !w9.set(6, 99f, 0f, NO_CROSSINGS));
    Check.that("und die Warnung ist weg", !w9.lastWasWarning());

    // ---- Datei-Rundlauf ----
    java.io.File dir = java.nio.file.Files.createTempDirectory("ledpos").toFile();
    java.io.File file = new java.io.File(dir, "ledPositions.txt");

    LedAnchorStore wr = store();
    wr.set(5, -3.25f, 1.1f, NO_CROSSINGS);
    wr.set(PER_STRIPE + 3, 2.9f, -0.45f, NO_CROSSINGS);
    wr.set(2 * PER_STRIPE, 0f, 0f, NO_CROSSINGS);
    Check.eq("drei Anker vor dem Schreiben", 3, wr.size());
    wr.save(file.getAbsolutePath());
    Check.that("die Datei existiert", file.exists());
    Check.eq("save() laesst loadedCount unveraendert", 0, wr.loadedCount());
    Check.eq("und sessionCount auch", 3, wr.sessionCount());

    LedAnchorStore rd = store();
    rd.load(file.getAbsolutePath());
    Check.eq("drei Anker geladen", 3, rd.size());
    Check.near("x kam zurueck", -3.25, rd.x(5), 1e-3);
    Check.near("y kam zurueck", 1.1, rd.y(5), 1e-3);
    Check.near("negatives y kam zurueck", -0.45, rd.y(PER_STRIPE + 3), 1e-3);
    Check.eq("alle gelten als geladen", 3, rd.loadedCount());
    Check.eq("keine Sitzungseintraege", 0, rd.sessionCount());
    Check.that("wasLoaded ist true", rd.wasLoaded(5));

    // Ein geladener Anker, der ueberschrieben wird, ist danach ein
    // Sitzungseintrag
    Check.that("geladenen Anker ueberschreiben", rd.set(5, 1f, 1f, NO_CROSSINGS));
    Check.eq("einer weniger geladen", 2, rd.loadedCount());
    Check.eq("einer mehr in der Sitzung", 1, rd.sessionCount());

    // Loeschen eines geladenen Ankers zieht loadedCount mit
    LedAnchorStore rd2 = store();
    rd2.load(file.getAbsolutePath());
    Check.eq("drei geladen", 3, rd2.loadedCount());
    Check.that("einen geladenen loeschen", rd2.remove(5));
    Check.eq("loadedCount sinkt mit", 2, rd2.loadedCount());
    Check.eq("sessionCount bleibt bei null", 0, rd2.sessionCount());

    // ---- Punkt als Dezimaltrennzeichen, unabhaengig von der Locale ----
    String written = new String(
        java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    Check.that("die Datei enthaelt kein Komma", written.indexOf(',') < 0);
    Check.that("die Datei enthaelt einen Punkt", written.indexOf('.') >= 0);

    // ---- Mehrfaches Speichern verdoppelt nichts ----
    wr.save(file.getAbsolutePath());
    wr.save(file.getAbsolutePath());
    LedAnchorStore rd3 = store();
    rd3.load(file.getAbsolutePath());
    Check.eq("nach dreimal speichern immer noch drei Anker", 3, rd3.size());

    // ---- Fehlende Datei ----
    LedAnchorStore missing = store();
    missing.load(new java.io.File(dir, "gibtsnicht.txt").getAbsolutePath());
    Check.eq("fehlende Datei ergibt eine leere Liste", 0, missing.size());
    Check.that("und eine Meldung", missing.lastMessage().length() > 0);

    // ---- Kaputte Zeilen werden uebersprungen, gute geladen ----
    java.io.File bad = new java.io.File(dir, "kaputt.txt");
    java.io.PrintWriter pw = new java.io.PrintWriter(bad, "UTF-8");
    pw.println("# Kommentarzeile, wird ignoriert");
    pw.println("");
    pw.println("   ");
    pw.println("3 1.500 -2.250");          // gueltig
    pw.println("nichts 1.0 2.0");          // Index keine Zahl
    pw.println("4 keinezahl 2.0");          // x keine Zahl
    pw.println("5 1.0");                    // zu wenig Felder
    pw.println("6 1.0 2.0 3.0");            // zu viele Felder
    pw.println("-1 1.0 2.0");               // Index ausserhalb
    pw.println((STRIPES * PER_STRIPE) + " 1.0 2.0");  // Index ausserhalb
    pw.println("7 99.0 2.0");               // Position ausserhalb der Grundflaeche
    pw.println("8 -6.000 3.000");           // gueltig
    pw.close();

    LedAnchorStore badStore = store();
    badStore.load(bad.getAbsolutePath());
    Check.eq("nur die zwei gueltigen Zeilen wurden geladen", 2, badStore.size());
    Check.that("die erste gueltige Zeile", badStore.has(3));
    Check.near("mit ihrem x", 1.5, badStore.x(3), 1e-3);
    Check.near("mit ihrem y", -2.25, badStore.y(3), 1e-3);
    Check.that("die zweite gueltige Zeile", badStore.has(8));
    Check.that("die kaputten nicht", !badStore.has(5) && !badStore.has(6));
    Check.that("und die ausserhalb der Grundflaeche auch nicht", !badStore.has(7));
    Check.that("die Meldung nennt die uebersprungenen Zeilen",
        badStore.lastMessage().length() > 0);

    // ---- load() ersetzt, haengt nicht an ----
    LedAnchorStore twice = store();
    twice.load(file.getAbsolutePath());
    int firstLoad = twice.size();
    twice.load(file.getAbsolutePath());
    Check.eq("zweites load() ersetzt statt anzuhaengen", firstLoad, twice.size());

    System.exit(Check.report("LedAnchorStoreTest"));
  }
}
