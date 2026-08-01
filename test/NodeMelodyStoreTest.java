import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class NodeMelodyStoreTest {

  static final int PITCH = 600;

  static TreeSet<Integer> cross(int s1, int p1, int s2, int p2) {
    TreeSet<Integer> t = new TreeSet<Integer>();
    t.add(Integer.valueOf(s1 * PITCH + p1));
    t.add(Integer.valueOf(s2 * PITCH + p2));
    return t;
  }

  static class ConstRandom implements RandomSource {
    private final double v;
    ConstRandom(double v_) { v = v_; }
    public double next() { return v; }
  }

  static void writeText(File f, String content) throws Exception {
    PrintWriter w = new PrintWriter(f, "UTF-8");
    w.print(content);
    w.close();
  }

  public static void main(String[] args) throws Exception {
    File dir = File.createTempFile("melodyStore", "");
    dir.delete();
    dir.mkdirs();

    // ---- Dateiname ----
    Check.eq("Dateiname aus dem Modus-Schluessel",
        "nodeMelody_phrygisch.txt", NodeMelodyStore.fileNameFor("phrygisch"));

    // ---- Rundlauf: schreiben und wieder lesen ----
    // Die Kreuzungsliste wird selbst gebaut, keine Zahl aus data/ als
    // Literal (CLAUDE.md, Konventionen).
    List<TreeSet<Integer>> crossings = new ArrayList<TreeSet<Integer>>();
    for (int i = 0; i < 12; i++) {
      crossings.add(cross(0, 20 + i * 20, 10 + i, 100));
    }
    MelodyGraph g = MelodyGraph.fromCrossings(crossings, PITCH);
    MelodyMode mode = MelodyModes.byKey("phrygisch");
    MelodyAssignment a = MelodyAssigner.assign(g, mode, g.defaultStartNode(),
        45, 3, g.hubThreshold(0.75f), new ConstRandom(0.3));

    File file = new File(dir, NodeMelodyStore.fileNameFor(mode.key));
    Check.that("Schreiben gelingt",
        NodeMelodyStore.write(file.getPath(), mode, a, g.hubThreshold(0.75f),
            "2026-08-01T12:00:00"));
    Check.that("die Datei existiert", file.isFile());

    NodeMelodyStore store = new NodeMelodyStore();
    Check.that("Lesen gelingt", store.load(file.getPath()));
    Check.eq("so viele Knoten wie geschrieben", a.nodeCount, store.size());
    for (int i = 0; i < a.nodeCount; i++) {
      Check.eq("Rundlauf erhaelt die Stufe von Knoten " + i,
          a.scaleIndex[i], store.scaleIndexOf(i));
    }
    Check.eq("keine Zeile abgelehnt", 0, store.rejectedCount());
    Check.eq("nichts ersetzt", 0, store.overriddenCount());

    // ---- Kopfzeilen ----
    Check.eq("Modus aus dem Kopf", "phrygisch", store.modeKey());
    Check.eq("Grundton aus dem Kopf", 45, store.rootMidiNote());
    Check.eq("Oktavzahl aus dem Kopf", 3, store.numOctaves());
    Check.eq("Startknoten aus dem Kopf", a.startNode, store.startNode());

    // ---- Wiederholtes Schreiben verdoppelt nichts ----
    NodeMelodyStore.write(file.getPath(), mode, a, g.hubThreshold(0.75f),
        "2026-08-01T12:00:01");
    NodeMelodyStore.write(file.getPath(), mode, a, g.hubThreshold(0.75f),
        "2026-08-01T12:00:02");
    NodeMelodyStore again = new NodeMelodyStore();
    again.load(file.getPath());
    Check.eq("dreimal geschrieben, immer noch dieselbe Knotenzahl",
        a.nodeCount, again.size());
    Check.eq("und nichts doppelt", 0, again.overriddenCount());
    Check.that("keine Temp-Datei bleibt liegen",
        !new File(file.getPath() + ".tmp").exists());

    // ---- Unbekannter Knoten ----
    Check.eq("Knoten hinter dem Ende ist unbekannt", -1, store.scaleIndexOf(999));
    Check.eq("negativer Knoten ist unbekannt", -1, store.scaleIndexOf(-1));

    // ---- Fehlende Datei ist kein Wurf ----
    NodeMelodyStore missing = new NodeMelodyStore();
    Check.that("fehlende Datei liefert false",
        !missing.load(new File(dir, "gibtsnicht.txt").getPath()));
    Check.that("und meldet es", missing.lastMessage().contains("nicht gefunden"));
    Check.eq("und liefert danach keine Stufen", -1, missing.scaleIndexOf(0));

    // ---- Kommentare, Leerzeilen, kaputte Zeilen ----
    File mixed = new File(dir, "mixed.txt");
    writeText(mixed,
        "# Modus: dorisch (Dorisch)\n"
        + "# Startknoten: 7\n"
        + "# rootMidiNote: 48\n"
        + "# numOctaves: 2\n"
        + "# nodeId\tscaleIndex\tmidiNote\ttiefe\n"
        + "\n"
        + "0\t9\t60\t2\n"
        + "   \n"
        + "1\t6\t55\t3\n"
        + "zwei\tdrei\n"
        + "3\n"
        + "4\t-1\t0\t0\n"
        + "5\t2\t50\t1\n");
    NodeMelodyStore mx = new NodeMelodyStore();
    Check.that("gemischte Datei laedt", mx.load(mixed.getPath()));
    Check.eq("Kopf: Modus", "dorisch", mx.modeKey());
    Check.eq("Kopf: Grundton", 48, mx.rootMidiNote());
    Check.eq("Kopf: Oktaven", 2, mx.numOctaves());
    Check.eq("Kopf: Startknoten", 7, mx.startNode());
    Check.eq("erste Datenzeile", 9, mx.scaleIndexOf(0));
    Check.eq("zweite Datenzeile", 6, mx.scaleIndexOf(1));
    Check.eq("uebersprungener Knoten bleibt unbekannt", -1, mx.scaleIndexOf(2));
    Check.eq("letzte Datenzeile", 2, mx.scaleIndexOf(5));
    // "zwei drei", "3" allein und der negative scaleIndex - drei Ablehnungen
    Check.eq("drei Zeilen abgelehnt", 3, mx.rejectedCount());
    Check.eq("Knoten mit negativem Wert bleibt unbekannt", -1, mx.scaleIndexOf(4));
    Check.that("die Ablehnungen stehen in der Meldung",
        mx.lastMessage().contains("abgelehnt"));

    // ---- Doppelter Knoten: die LETZTE Zeile gewinnt ----
    // Die natuerliche Handkorrektur ist eine angehaengte Zeile am Ende.
    File dup = new File(dir, "dup.txt");
    writeText(dup,
        "# Modus: saba (Maqam Saba)\n"
        + "0\t3\t48\t0\n"
        + "1\t5\t52\t1\n"
        + "# Handkorrektur, angehaengt:\n"
        + "1\t9\t60\t1\n");
    NodeMelodyStore dp = new NodeMelodyStore();
    dp.load(dup.getPath());
    Check.eq("die letzte Zeile gewinnt", 9, dp.scaleIndexOf(1));
    Check.eq("die Ersetzung wird gezaehlt", 1, dp.overriddenCount());
    Check.that("und gemeldet", dp.lastMessage().contains("ersetzt"));

    // ---- Leerzeichen statt Tabs (von Hand ausgerichtete Datei) ----
    File spaced = new File(dir, "spaced.txt");
    writeText(spaced, "0   4   50   0\n1   11   62   1\n");
    NodeMelodyStore sp = new NodeMelodyStore();
    sp.load(spaced.getPath());
    Check.eq("mit Leerzeichen getrennt: Knoten 0", 4, sp.scaleIndexOf(0));
    Check.eq("mit Leerzeichen getrennt: Knoten 1", 11, sp.scaleIndexOf(1));
    Check.eq("nichts abgelehnt", 0, sp.rejectedCount());

    // ---- Datei ohne Kopf ----
    File bare = new File(dir, "bare.txt");
    writeText(bare, "0\t1\t46\t0\n");
    NodeMelodyStore br = new NodeMelodyStore();
    br.load(bare.getPath());
    Check.eq("ohne Kopf: kein Modus", "", br.modeKey());
    Check.eq("ohne Kopf: kein Grundton", -1, br.rootMidiNote());
    Check.eq("ohne Kopf: kein numOctaves", -1, br.numOctaves());
    Check.eq("die Daten kommen trotzdem an", 1, br.scaleIndexOf(0));

    // ---- Alle acht Modi lassen sich schreiben und lesen ----
    for (int m = 0; m < MelodyModes.count(); m++) {
      MelodyMode mm = MelodyModes.at(m);
      MelodyAssignment ma = MelodyAssigner.assign(g, mm, g.defaultStartNode(),
          45, 3, g.hubThreshold(0.75f), new ConstRandom(0.6));
      File f = new File(dir, NodeMelodyStore.fileNameFor(mm.key));
      Check.that(mm.key + ": Schreiben gelingt",
          NodeMelodyStore.write(f.getPath(), mm, ma, g.hubThreshold(0.75f), "test"));
      NodeMelodyStore s = new NodeMelodyStore();
      Check.that(mm.key + ": Lesen gelingt", s.load(f.getPath()));
      Check.eq(mm.key + ": Modus im Kopf", mm.key, s.modeKey());
      for (int i = 0; i < ma.nodeCount; i++) {
        Check.eq(mm.key + ": Stufe von Knoten " + i,
            ma.scaleIndex[i], s.scaleIndexOf(i));
      }
    }

    // Aufraeumen
    File[] rest = dir.listFiles();
    if (rest != null) {
      for (int i = 0; i < rest.length; i++) {
        rest[i].delete();
      }
    }
    dir.delete();

    System.exit(Check.report("NodeMelodyStoreTest"));
  }
}
