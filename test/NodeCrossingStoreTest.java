import java.io.File;
import java.nio.file.Files;

public class NodeCrossingStoreTest {
  static final int STRIPES = 30;
  static final int PER_STRIPE = 600;

  public static void main(String[] args) throws Exception {
    // ---- Validierung ----
    NodeCrossingStore s = new NodeCrossingStore(STRIPES, PER_STRIPE);

    Check.that("gueltiges Paar auf zwei Stripes", s.add(3, 412, 7, 158));
    Check.eq("nach erstem Paar", 1, s.size());

    Check.that("dieselbe LED wird abgewiesen", !s.add(3, 412, 3, 412));
    Check.that("Meldung nennt den Grund", s.lastMessage().length() > 0);
    String sameLedMessage = s.lastMessage();
    Check.that("Meldung nennt die identische LED, nicht den Abstand",
        sameLedMessage.indexOf("derselben LED") >= 0);

    Check.that("gleicher Stripe, zu nah, wird abgewiesen", !s.add(5, 100, 5, 102));
    String tooCloseMessage = s.lastMessage();
    Check.that("Meldung nennt den Abstand, nicht die identische LED",
        tooCloseMessage.indexOf("LEDs dazwischen liegen") >= 0);
    Check.that("die beiden Meldungen unterscheiden sich", !sameLedMessage.equals(tooCloseMessage));

    Check.that("gleicher Stripe, weit genug, wird angenommen", s.add(5, 100, 5, 103));
    Check.eq("nach dem gueltigen Paar auf einem Stripe", 2, s.size());

    Check.that("Duplikat wird abgewiesen", !s.add(3, 412, 7, 158));
    Check.that("Duplikat auch in umgekehrter Reihenfolge", !s.add(7, 158, 3, 412));
    Check.eq("Groesse nach Duplikaten unveraendert", 2, s.size());

    // ---- Undo ----
    Check.that("Undo nimmt das letzte Paar zurueck", s.undo());
    Check.eq("nach Undo", 1, s.size());
    Check.that("Undo bis zum Anfang", s.undo());
    Check.eq("Liste leer", 0, s.size());
    Check.that("Undo auf leerer Liste tut nichts", !s.undo());

    // ---- Datei-Rundlauf ----
    File dir = Files.createTempDirectory("crossings").toFile();
    File file = new File(dir, "nodeCrossings.txt");

    NodeCrossingStore w = new NodeCrossingStore(STRIPES, PER_STRIPE);
    w.add(0, 10, 1, 20);
    w.add(2, 30, 3, 40);
    w.save(file.getAbsolutePath());

    NodeCrossingStore r = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r.load(file.getAbsolutePath());
    Check.eq("geladene Anzahl", 2, r.size());
    Check.eq("als geladen gezaehlt", 2, r.loadedCount());
    Check.eq("keine neuen in dieser Sitzung", 0, r.sessionCount());

    // globale Indizes: Stripe 0 LED 10 -> 10, Stripe 1 LED 20 -> 620
    Check.that("erstes Paar enthaelt 10", r.crossings().get(0).contains(10));
    Check.that("erstes Paar enthaelt 620", r.crossings().get(0).contains(620));

    // Undo darf geladene Eintraege nicht anfassen
    Check.that("Undo greift nicht auf geladene Eintraege", !r.undo());
    Check.eq("geladene Eintraege unveraendert", 2, r.size());

    // Zweimal speichern verdoppelt nichts
    r.add(4, 50, 5, 60);
    r.save(file.getAbsolutePath());
    r.save(file.getAbsolutePath());
    NodeCrossingStore r2 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r2.load(file.getAbsolutePath());
    Check.eq("nach zweimal Speichern", 3, r2.size());

    // ---- fehlerhafte Datei ----
    File bad = new File(dir, "kaputt.txt");
    Files.write(bad.toPath(), "10 620\n-1 5\n99999999 3\nnichts\n30 640\n".getBytes("UTF-8"));
    NodeCrossingStore r3 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r3.load(bad.getAbsolutePath());
    Check.eq("nur die zwei gueltigen Zeilen", 2, r3.size());

    // ---- Laden: fehlende Datei ----
    File missing = new File(dir, "gibtsnicht.txt");
    NodeCrossingStore r4 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r4.load(missing.getAbsolutePath());
    Check.eq("fehlende Datei ergibt leere Liste", 0, r4.size());
    Check.that("Meldung bei fehlender Datei", r4.lastMessage().length() > 0);

    // ---- Laden: Leerzeilen zwischen Eintraegen ----
    File blanks = new File(dir, "leerzeilen.txt");
    Files.write(blanks.toPath(), "10 620\n\n\n30 640\n\n".getBytes("UTF-8"));
    NodeCrossingStore r5 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r5.load(blanks.getAbsolutePath());
    Check.eq("Leerzeilen werden uebergangen, gueltige Zeilen kommen an", 2, r5.size());

    // ---- Laden: Zeile mit nur einem Index ----
    File single = new File(dir, "einzelwert.txt");
    Files.write(single.toPath(), "10 620\n5\n30 640\n".getBytes("UTF-8"));
    NodeCrossingStore r6 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r6.load(single.getAbsolutePath());
    Check.eq("Zeile mit nur einem Index wird uebersprungen", 2, r6.size());

    // ---- Speichern darf Undo der Sitzung nicht sperren ----
    File sessionFile = new File(dir, "sitzung.txt");
    NodeCrossingStore base = new NodeCrossingStore(STRIPES, PER_STRIPE);
    base.add(0, 10, 1, 20);
    base.add(2, 30, 3, 40);
    base.save(sessionFile.getAbsolutePath());

    NodeCrossingStore u = new NodeCrossingStore(STRIPES, PER_STRIPE);
    u.load(sessionFile.getAbsolutePath());
    Check.eq("zwei geladene Eintraege vor der Sitzung", 2, u.size());
    u.add(4, 50, 5, 60);
    u.add(6, 70, 7, 80);
    Check.eq("vier Eintraege nach zwei neuen", 4, u.size());
    u.save(sessionFile.getAbsolutePath());
    Check.eq("loadedCount bleibt nach save() bei den geladenen Eintraegen", 2, u.loadedCount());

    Check.that("erstes Undo nach dem Speichern gelingt", u.undo());
    Check.eq("nach erstem Undo", 3, u.size());
    Check.that("zweites Undo nach dem Speichern gelingt", u.undo());
    Check.eq("zurueck auf die geladenen Eintraege", 2, u.size());
    Check.that("drittes Undo schlaegt fehl, geladene Eintraege sind geschuetzt", !u.undo());
    Check.eq("Groesse bleibt bei den geladenen Eintraegen", 2, u.size());

    u.save(sessionFile.getAbsolutePath());
    NodeCrossingStore u2 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    u2.load(sessionFile.getAbsolutePath());
    Check.eq("erneutes Speichern schreibt nur die zwei verbliebenen Eintraege", 2, u2.size());

    // ---- clearAll() verwirft auch geladene Eintraege ----
    File clearFile = new File(dir, "clearall.txt");
    NodeCrossingStore baseClear = new NodeCrossingStore(STRIPES, PER_STRIPE);
    baseClear.add(0, 10, 1, 20);
    baseClear.add(2, 30, 3, 40);
    baseClear.save(clearFile.getAbsolutePath());

    NodeCrossingStore clearStore = new NodeCrossingStore(STRIPES, PER_STRIPE);
    clearStore.load(clearFile.getAbsolutePath());
    Check.eq("vor clearAll: geladene Eintraege", 2, clearStore.loadedCount());
    clearStore.add(4, 50, 5, 60);
    Check.eq("vor clearAll: geladen + neu", 3, clearStore.size());

    clearStore.clearAll();
    Check.eq("nach clearAll: Liste leer", 0, clearStore.size());
    Check.eq("nach clearAll: loadedCount ist 0", 0, clearStore.loadedCount());
    Check.that("clearAll meldet etwas", clearStore.lastMessage().length() > 0);

    clearStore.save(clearFile.getAbsolutePath());
    NodeCrossingStore afterClear = new NodeCrossingStore(STRIPES, PER_STRIPE);
    afterClear.load(clearFile.getAbsolutePath());
    Check.eq("nach save() im Anschluss an clearAll: Datei ist leer", 0, afterClear.size());

    System.exit(Check.report("NodeCrossingStoreTest"));
  }
}
