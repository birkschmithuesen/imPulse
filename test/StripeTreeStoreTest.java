import java.io.File;
import java.io.PrintWriter;

public class StripeTreeStoreTest {

  static File tempFile(String content) throws Exception {
    File f = File.createTempFile("stripeTrees", ".txt");
    f.deleteOnExit();
    PrintWriter w = new PrintWriter(f, "UTF-8");
    w.print(content);
    w.close();
    return f;
  }

  // Baut sich die Zuordnung selbst, statt eine Zahl aus data/stripeTrees.txt
  // zu erwarten: die Datei korrigiert Birk von Hand, jede fest eingetragene
  // Anzahl waere am naechsten Tag falsch (siehe CLAUDE.md, Konventionen).
  static String line(int stripe, String tree) {
    return stripe + "\t" + tree + "\tsicher\t0.42\n";
  }

  public static void main(String[] args) throws Exception {
    // ---- Die Baumnamen und ihre Nummern ----
    Check.eq("vier Baeume", 4, StripeTreeStore.TREE_NAMES.length);
    Check.eq("1 ist vorn", "vorn", StripeTreeStore.TREE_NAMES[0]);
    Check.eq("2 ist hinten", "hinten", StripeTreeStore.TREE_NAMES[1]);
    Check.eq("3 ist rechts", "rechts", StripeTreeStore.TREE_NAMES[2]);
    Check.eq("4 ist links", "links", StripeTreeStore.TREE_NAMES[3]);
    Check.eq("0 heisst kein Filter", 0, StripeTreeStore.NO_FILTER);

    // ---- Grundfall: parsen und filtern ----
    StripeTreeStore s = new StripeTreeStore(8);
    File f = tempFile(
        "# Kommentarkopf, muss uebersprungen werden\n"
        + "#\n"
        + "\n"
        + line(0, "vorn")
        + line(1, "rechts")
        + line(2, "vorn")
        + line(3, "links")
        + line(4, "hinten")
        + line(5, "vorn")
        + "   \n"                       // reine Leerraum-Zeile
        + line(6, "rechts")
        + line(7, "hinten"));
    Check.that("Laden meldet Erfolg", s.load(f.getPath()));
    Check.eq("acht Zuordnungen", 8, s.assignedCount());

    int[] vorn = s.stripesFor(1);
    Check.eq("vorn hat drei Stripes", 3, vorn.length);
    Check.eq("aufsteigend sortiert 0", 0, vorn[0]);
    Check.eq("aufsteigend sortiert 1", 2, vorn[1]);
    Check.eq("aufsteigend sortiert 2", 5, vorn[2]);
    Check.eq("hinten hat zwei", 2, s.stripesFor(2).length);
    Check.eq("rechts hat zwei", 2, s.stripesFor(3).length);
    Check.eq("links hat einen", 1, s.stripesFor(4).length);
    Check.eq("und zwar Stripe 3", 3, s.stripesFor(4)[0]);

    // ---- 0 und ungueltige Filterwerte heissen "kein Filter" ----
    // null statt eines leeren Arrays: der Aufrufer soll "alle Stripes"
    // bekommen, nicht "keine" - ein Track, der still nicht mehr feuert,
    // waere genau der Fehlerzustand ohne Symptom.
    Check.that("Filter 0 gibt null", s.stripesFor(0) == null);
    Check.that("negativer Filter gibt null", s.stripesFor(-1) == null);
    Check.that("Filter 5 gibt null", s.stripesFor(5) == null);
    Check.that("Filter 99 gibt null", s.stripesFor(99) == null);

    // ---- Ein Baum ohne Stripes gibt ebenfalls null ----
    StripeTreeStore leer = new StripeTreeStore(4);
    File fl = tempFile(line(0, "vorn") + line(1, "vorn"));
    Check.that("Laden ok", leer.load(fl.getPath()));
    Check.that("vorn hat Stripes", leer.stripesFor(1) != null);
    Check.that("hinten ist leer und gibt null", leer.stripesFor(2) == null);
    Check.that("rechts ist leer und gibt null", leer.stripesFor(3) == null);
    Check.that("links ist leer und gibt null", leer.stripesFor(4) == null);

    // ---- Dieselbe Anfrage liefert dasselbe Array (kein Allokieren) ----
    // stripesFor() wird aus tickSequencer() mit 40 Hz gerufen.
    Check.that("stripesFor gibt das gecachte Array zurueck",
        s.stripesFor(1) == s.stripesFor(1));

    // ---- Unbekannter Baumname wird gemeldet und uebersprungen ----
    StripeTreeStore bad = new StripeTreeStore(8);
    File fb = tempFile(line(0, "vorn") + line(1, "baum42") + line(2, "hinten"));
    Check.that("Laden gelingt trotzdem", bad.load(fb.getPath()));
    Check.eq("nur die zwei gueltigen Zeilen zaehlen", 2, bad.assignedCount());
    Check.eq("eine Zeile abgelehnt", 1, bad.rejectedCount());
    Check.that("die Meldung nennt den Baumnamen",
        bad.lastMessage().contains("baum42"));

    // ---- Index ausserhalb des Bereichs wird abgelehnt ----
    StripeTreeStore oob = new StripeTreeStore(4);
    File fo = tempFile(line(0, "vorn") + line(4, "vorn") + line(-1, "vorn")
        + line(3, "vorn"));
    oob.load(fo.getPath());
    Check.eq("nur die zwei Indizes im Bereich", 2, oob.assignedCount());
    Check.eq("zwei abgelehnt", 2, oob.rejectedCount());
    Check.eq("Stripe 0 dabei", 0, oob.stripesFor(1)[0]);
    Check.eq("Stripe 3 dabei", 3, oob.stripesFor(1)[1]);

    // ---- Doppelter Stripe: die LETZTE Zeile gewinnt ----
    // Birk korrigiert die Datei von Hand, und die natuerliche Handkorrektur
    // ist eine angehaengte Zeile am Ende. "Erste gewinnt" wuerde sie still
    // verschlucken.
    StripeTreeStore dup = new StripeTreeStore(4);
    File fd = tempFile(line(0, "vorn") + line(1, "vorn") + line(0, "links"));
    dup.load(fd.getPath());
    Check.eq("der Stripe zaehlt einmal", 2, dup.assignedCount());
    Check.eq("vorn behaelt nur Stripe 1", 1, dup.stripesFor(1).length);
    Check.eq("naemlich Stripe 1", 1, dup.stripesFor(1)[0]);
    Check.eq("links hat den korrigierten Stripe", 1, dup.stripesFor(4).length);
    Check.eq("naemlich Stripe 0", 0, dup.stripesFor(4)[0]);
    Check.eq("die Ueberschreibung wird gezaehlt", 1, dup.overriddenCount());

    // ---- confidence aendert am Ergebnis nichts ----
    // Der Brief ist da eindeutig: "unsicher" ist eine Doku-Markierung fuer
    // Birk, kein Laufzeitverhalten.
    StripeTreeStore conf = new StripeTreeStore(4);
    File fc = tempFile(
        "0\tvorn\tunsicher\t2.22\n"
        + "1\tvorn\tsicher\t0.21\n");
    conf.load(fc.getPath());
    Check.eq("beide Zeilen zaehlen", 2, conf.assignedCount());
    Check.eq("beide im selben Baum", 2, conf.stripesFor(1).length);
    Check.eq("die unsicheren werden trotzdem gezaehlt", 1, conf.uncertainCount());

    // ---- Kaputte Zeilen ----
    StripeTreeStore broken = new StripeTreeStore(8);
    File fbr = tempFile(
        line(0, "vorn")
        + "nur_ein_feld\n"
        + "xyz\tvorn\tsicher\t0.1\n"     // Index keine Zahl
        + line(3, "hinten"));
    broken.load(fbr.getPath());
    Check.eq("die zwei guten Zeilen zaehlen", 2, broken.assignedCount());
    Check.eq("zwei abgelehnt", 2, broken.rejectedCount());

    // ---- Index und Baum GENUEGEN, confidence/distance sind optional ----
    // Bewusst nachsichtig: Birk korrigiert die Datei von Hand, und dabei
    // soll er keine Distanz erfinden muessen, nur um eine Zuordnung zu
    // aendern. Die zwei hinteren Spalten sind ohnehin reine Dokumentation.
    StripeTreeStore kurz = new StripeTreeStore(8);
    File fk = tempFile("0\tvorn\n1\tlinks\n");
    kurz.load(fk.getPath());
    Check.eq("zweispaltige Zeilen werden angenommen", 2, kurz.assignedCount());
    Check.eq("keine abgelehnt", 0, kurz.rejectedCount());
    Check.eq("und richtig einsortiert", 0, kurz.stripesFor(1)[0]);
    Check.eq("auch der zweite", 1, kurz.stripesFor(4)[0]);
    Check.eq("ohne confidence-Spalte nichts als unsicher gezaehlt",
        0, kurz.uncertainCount());

    // ---- Fehlende Datei ist kein Absturz ----
    StripeTreeStore missing = new StripeTreeStore(8);
    Check.that("fehlende Datei meldet false",
        !missing.load("/gibt/es/nicht/stripeTrees.txt"));
    Check.eq("keine Zuordnung", 0, missing.assignedCount());
    Check.that("und jeder Filter gibt null", missing.stripesFor(1) == null);
    Check.that("die Meldung sagt warum", missing.lastMessage().length() > 0);

    // ---- Ohne Laden verhaelt sich der Store wie ohne Filter ----
    StripeTreeStore fresh = new StripeTreeStore(8);
    for (int i = 0; i <= 5; i++) {
      Check.that("frisch angelegt gibt jeder Filter null",
          fresh.stripesFor(i) == null);
    }

    // ---- treeNameOf fuer die Diagnose ----
    Check.eq("Stripe 0 gehoert zu vorn", "vorn", s.treeNameOf(0));
    Check.eq("Stripe 3 gehoert zu links", "links", s.treeNameOf(3));
    Check.eq("unbekannter Stripe hat keinen Baum", "-", s.treeNameOf(99));

    // ---- Bericht fuer die Konsole ----
    Check.that("der Bericht nennt alle vier Baeume",
        s.report().contains("vorn") && s.report().contains("hinten")
        && s.report().contains("rechts") && s.report().contains("links"));

    // ---- Gegenprobe an der ECHTEN Datei, falls vorhanden ----
    File real = new File("data/stripeTrees.txt");
    if (real.isFile()) {
      StripeTreeStore live = new StripeTreeStore(30);
      Check.that("die echte Datei laedt", live.load(real.getPath()));
      Check.that("sie ordnet ueberhaupt Stripes zu", live.assignedCount() > 0);
      Check.eq("keine Zeile abgelehnt", 0, live.rejectedCount());
      // Jeder zugeordnete Stripe taucht in genau einem Baum auf.
      int summe = 0;
      for (int t = 1; t <= 4; t++) {
        int[] pool = live.stripesFor(t);
        summe += (pool == null) ? 0 : pool.length;
      }
      Check.eq("die Baeume zusammen ergeben die Zuordnungszahl",
          live.assignedCount(), summe);
    } else {
      System.out.println("Hinweis: data/stripeTrees.txt fehlt, "
          + "Gegenprobe an der echten Datei uebersprungen");
    }

    System.exit(Check.report("StripeTreeStoreTest"));
  }
}
