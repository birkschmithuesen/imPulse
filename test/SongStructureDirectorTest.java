import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SongStructureDirectorTest {

  // Zufallsquelle aus einer festen Folge. Ohne diese Naht haengt jede
  // Erwartung an einem echten Zufallsgenerator - dasselbe Muster wie in
  // OriginSequencerTest.
  static class Fixed implements RandomSource {
    private final double[] values;
    private int index = 0;
    int calls = 0;
    Fixed(double... v) { values = v; }
    public double next() {
      calls++;
      double v = values[index % values.length];
      index++;
      return v;
    }
  }

  static File temp(String content) throws Exception {
    File file = File.createTempFile("energyLevels", ".txt");
    file.deleteOnExit();
    PrintWriter writer = new PrintWriter(file, "UTF-8");
    writer.print(content);
    writer.close();
    return file;
  }

  static EnergyLevelStore tagging() throws Exception {
    EnergyLevelStore store = new EnergyLevelStore();
    store.load(temp(
        "r1\truhig\nr2\truhig\n"
        + "m1\tmittel\nm2\tmittel\n"
        + "d1\tdynamisch\nd2\tdynamisch\n"
        + "x1\tdramatisch\nx2\tdramatisch\n").getPath());
    return store;
  }

  static List<String> names(String... items) {
    return new ArrayList<String>(Arrays.asList(items));
  }

  static final List<String> ALL = names("r1", "r2", "m1", "m2", "d1", "d2", "x1", "x2");

  static SongStructureConfig on() {
    SongStructureConfig cfg = SongStructureConfig.withDefaults();
    cfg.enabled = true;
    return cfg;
  }

  public static void main(String[] args) throws Exception {
    EnergyLevelStore tags = tagging();

    // ---- Auslieferungswerte ----
    SongStructureConfig def = SongStructureConfig.withDefaults();
    Check.that("Auslieferungszustand ist AUS", !def.enabled);
    for (int from = 0; from < EnergyLevelStore.LEVEL_COUNT; from++) {
      float sum = 0;
      for (int to = 0; to < EnergyLevelStore.LEVEL_COUNT; to++) {
        sum += def.matrix[from][to];
      }
      Check.near("Matrixzeile " + EnergyLevelStore.nameOf(from)
          + " summiert sich auf 100", 100.0, sum, 1e-4);
    }
    Check.near("nach dramatisch ueberwiegt der Rueckgang nach ruhig",
        60.0, def.matrix[3][0], 1e-4);
    Check.that("dramatisch->dramatisch ist der seltenste Uebergang der Matrix",
        def.matrix[3][3] < def.matrix[0][3] && def.matrix[3][3] < def.matrix[1][3]
        && def.matrix[3][3] < def.matrix[2][3]);
    Check.near("ruhig dauert mindestens 3 Minuten", 3.0, def.dwellMinMinutes[0], 1e-4);
    Check.near("ruhig dauert hoechstens 5 Minuten", 5.0, def.dwellMaxMinutes[0], 1e-4);
    Check.near("dramatisch dauert mindestens 30 Sekunden",
        0.5, def.dwellMinMinutes[3], 1e-4);
    Check.near("dramatisch dauert hoechstens 1 Minute",
        1.0, def.dwellMaxMinutes[3], 1e-4);
    for (int i = 1; i < EnergyLevelStore.LEVEL_COUNT; i++) {
      // Der Spannungsbogen steckt in den Zeiten: je hoeher das Level, desto
      // kuerzer bleibt es stehen.
      Check.that("Level " + EnergyLevelStore.nameOf(i)
          + " bleibt kuerzer als das darunter",
          def.dwellMaxMinutes[i] < def.dwellMaxMinutes[i - 1]
          && def.dwellMinMinutes[i] < def.dwellMinMinutes[i - 1]);
    }

    // ---- 1: Startlevel ist fest ruhig ----
    for (int i = 0; i <= 10; i++) {
      SongStructureDirector d = new SongStructureDirector(tags, new Fixed(i/10.0));
      d.start(1000L, on());
      Check.eq("Startlevel ist immer ruhig, unabhaengig vom Zufall",
          0, d.currentLevel());
      Check.eq("currentLevelName nennt es auch so", "ruhig", d.currentLevelName());
    }

    // ---- 2: isDue erst nach der Verweildauer ----
    SongStructureDirector due = new SongStructureDirector(tags, new Fixed(0.0));
    SongStructureConfig cfg = on();
    due.start(1000L, cfg);
    long dwell = due.dwellMillis();
    Check.eq("random01 = 0 trifft die Untergrenze von ruhig",
        3*60000L, dwell);
    Check.that("unmittelbar nach start() nicht faellig", !due.isDue(1000L, cfg));
    Check.that("kurz vor Ablauf nicht faellig", !due.isDue(1000L + dwell - 1, cfg));
    Check.that("exakt bei Ablauf faellig", due.isDue(1000L + dwell, cfg));
    Check.that("danach weiter faellig", due.isDue(1000L + dwell + 5000L, cfg));

    // ---- 3: Verweildauer liegt in der Spanne des jeweiligen Levels ----
    Check.eq("random01 = 1 trifft die Obergrenze von ruhig",
        5*60000L, dwellFor(tags, 0, 1.0));
    Check.near("random01 = 0.5 trifft die Mitte von ruhig",
        4*60000.0, dwellFor(tags, 0, 0.5), 1.0);
    Check.eq("dramatisch faengt bei 30 Sekunden an",
        30000L, dwellFor(tags, 3, 0.0));
    Check.eq("dramatisch endet bei einer Minute",
        60000L, dwellFor(tags, 3, 1.0));

    // ---- 4: min > max wird getauscht, nicht negativ ----
    SongStructureConfig swapped = on();
    swapped.dwellMinMinutes[0] = 9f;
    swapped.dwellMaxMinutes[0] = 2f;
    SongStructureDirector sw = new SongStructureDirector(tags, new Fixed(0.0));
    sw.start(0L, swapped);
    Check.eq("vertauschte Spanne gibt trotzdem die kleinere Zahl",
        2*60000L, sw.dwellMillis());
    Check.that("und niemals eine negative Dauer", sw.dwellMillis() > 0);

    // ---- 5: eine live verengte Spanne klemmt die laufende Verweildauer ----
    // Sonst wartete ein Operator, der waehrend eines ruhigen Abschnitts von
    // 30 auf 3 Minuten dreht, noch die vollen 30 Minuten ab - ohne Fehler,
    // ohne Symptom, nur Stillstand.
    SongStructureConfig wide = on();
    wide.dwellMinMinutes[0] = 25f;
    wide.dwellMaxMinutes[0] = 35f;
    SongStructureDirector live = new SongStructureDirector(tags, new Fixed(1.0));
    live.start(0L, wide);
    Check.eq("zuerst die weite Spanne", 35*60000L, live.dwellMillis());
    SongStructureConfig narrow = on();
    narrow.dwellMinMinutes[0] = 3f;
    narrow.dwellMaxMinutes[0] = 5f;
    Check.that("nach dem Verengen ist der Wechsel sofort faellig",
        live.isDue(6*60000L, narrow));
    Check.that("aber nicht schon vor der neuen Obergrenze",
        !live.isDue(4*60000L, narrow));

    // ---- 6/7: die Verteilung stimmt, Gewicht 0 kommt nie vor ----
    // Verbrauchsreihenfolge der Zufallswerte: start() zieht eine
    // Verweildauer, forceLevel() eine weitere, dann zieht nextPreset()
    // Level, Preset und Verweildauer. Der dritte Wert ist also der, der das
    // Level bestimmt.
    SongStructureConfig dist = on();
    for (int from = 0; from < EnergyLevelStore.LEVEL_COUNT; from++) {
      int n = 100000;
      int[] counts = new int[EnergyLevelStore.LEVEL_COUNT];
      for (int i = 0; i < n; i++) {
        SongStructureDirector d = new SongStructureDirector(tags,
            new Fixed(0.0, 0.0, (i + 0.5)/n, 0.0, 0.0));
        d.start(0L, dist);
        d.forceLevel(from);
        d.nextPreset(0L, dist, ALL);
        counts[d.currentLevel()]++;
      }
      for (int to = 0; to < EnergyLevelStore.LEVEL_COUNT; to++) {
        Check.near("Uebergang " + EnergyLevelStore.nameOf(from) + "->"
            + EnergyLevelStore.nameOf(to),
            def.matrix[from][to]/100.0, counts[to]/(double) n, 0.01);
      }
    }
    SongStructureConfig noJump = on();
    noJump.matrix[0][3] = 0f;   // ruhig -> dramatisch abgeschaltet
    for (int i = 0; i <= 500; i++) {
      SongStructureDirector d = new SongStructureDirector(tags,
          new Fixed(0.0, i/500.0, 0.0, 0.0));
      d.start(0L, noJump);
      d.nextPreset(0L, noJump, ALL);
      Check.that("ein Uebergang mit Gewicht 0 kommt nie vor",
          d.currentLevel() != 3);
    }

    // ---- 8: eine Matrixzeile komplett 0 -> Rueckfall mittel ----
    SongStructureConfig dead = on();
    for (int to = 0; to < EnergyLevelStore.LEVEL_COUNT; to++) {
      dead.matrix[0][to] = 0f;
    }
    SongStructureDirector deadDir = new SongStructureDirector(tags,
        new Fixed(0.0, 0.5, 0.0, 0.0));
    deadDir.start(0L, dead);
    String name = deadDir.nextPreset(0L, dead, ALL);
    Check.eq("eine Nullzeile faellt auf mittel zurueck, statt zu haengen",
        EnergyLevelStore.FALLBACK_LEVEL, deadDir.currentLevel());
    Check.that("und liefert trotzdem ein Preset", name != null);

    // ---- 9: das zuletzt in DIESEM Level gespielte Preset wird vermieden ----
    SongStructureConfig stay = on();
    for (int from = 0; from < EnergyLevelStore.LEVEL_COUNT; from++) {
      for (int to = 0; to < EnergyLevelStore.LEVEL_COUNT; to++) {
        stay.matrix[from][to] = (to == 0) ? 100f : 0f;  // immer ruhig
      }
    }
    SongStructureDirector avoid = new SongStructureDirector(tags,
        new Fixed(0.0, 0.0, 0.0));
    avoid.start(0L, stay);
    String first = avoid.nextPreset(0L, stay, ALL);
    String second = avoid.nextPreset(0L, stay, ALL);
    String third = avoid.nextPreset(0L, stay, ALL);
    Check.that("beide Male dasselbe Level", avoid.currentLevel() == 0);
    Check.that("nicht zweimal hintereinander dasselbe Preset",
        !first.equals(second));
    Check.that("und beim dritten Mal wieder das erste (nur zwei im Pool)",
        third.equals(first));

    // Genau ein Preset im Level: es wird wiederholt, statt zu verstummen.
    EnergyLevelStore one = new EnergyLevelStore();
    one.load(temp("solo\truhig\nm1\tmittel\n").getPath());
    SongStructureDirector single = new SongStructureDirector(one,
        new Fixed(0.0, 0.0, 0.0));
    single.start(0L, stay);
    Check.eq("ein einziges Preset im Level wird wiederholt",
        "solo", single.nextPreset(0L, stay, names("solo", "m1")));
    Check.eq("auch beim zweiten Mal", "solo",
        single.nextPreset(0L, stay, names("solo", "m1")));

    // ---- 10: leerer Pool des gewuerfelten Levels ----
    // Der Director darf nie verstummen, nur weil ein Level ungetaggt blieb.
    EnergyLevelStore sparse = new EnergyLevelStore();
    sparse.load(temp("nurruhig\truhig\n").getPath());
    SongStructureConfig toDrama = on();
    for (int from = 0; from < EnergyLevelStore.LEVEL_COUNT; from++) {
      for (int to = 0; to < EnergyLevelStore.LEVEL_COUNT; to++) {
        toDrama.matrix[from][to] = (to == 3) ? 100f : 0f;
      }
    }
    SongStructureDirector empty = new SongStructureDirector(sparse,
        new Fixed(0.5, 0.0, 0.0));
    empty.start(0L, toDrama);
    String fallbackName = empty.nextPreset(0L, toDrama, names("nurruhig"));
    Check.eq("ein leeres Level nimmt das aktuelle statt zu verstummen",
        "nurruhig", fallbackName);
    Check.eq("und das aktuelle Level bleibt stehen", 0, empty.currentLevel());
    Check.that("der Grund steht in der Meldung",
        empty.lastMessage().indexOf("dramatisch") >= 0);

    // Auch das aktuelle Level leer -> irgendein Preset aus allNames.
    EnergyLevelStore odd = new EnergyLevelStore();
    odd.load(temp("# leer\n").getPath());   // alles faellt auf mittel
    SongStructureDirector oddDir = new SongStructureDirector(odd,
        new Fixed(0.5, 0.0, 0.0));
    oddDir.start(0L, toDrama);              // startet auf ruhig, dort ist nichts
    Check.that("auch bei zwei leeren Leveln kommt ein Preset heraus",
        oddDir.nextPreset(0L, toDrama, names("egal")) != null);

    // ---- 11: leere Namensliste ----
    SongStructureDirector nothing = new SongStructureDirector(tags, new Fixed(0.5));
    nothing.start(0L, on());
    Check.that("ohne Presets gibt es nichts zu laden",
        nothing.nextPreset(0L, on(), new ArrayList<String>()) == null);
    Check.that("und der Grund steht in der Meldung",
        nothing.lastMessage().length() > 0);
    Check.that("null als Namensliste stuerzt nicht ab",
        nothing.nextPreset(0L, on(), null) == null);

    // ---- 12/13: manueller Sprung ----
    SongStructureDirector manual = new SongStructureDirector(tags,
        new Fixed(0.0, 0.0, 0.0));
    manual.start(0L, on());
    Check.that("ohne Wunsch nicht sofort faellig", !manual.isDue(1000L, on()));
    manual.requestLevel(3);
    Check.that("ein Wunsch macht sofort faellig", manual.isDue(1000L, on()));
    manual.nextPreset(1000L, on(), ALL);
    Check.eq("und fuehrt ohne Wuerfelwurf zum gewuenschten Level",
        3, manual.currentLevel());
    Check.that("danach ist der Wunsch verbraucht, nicht dauerhaft",
        !manual.isDue(2000L, on()));
    manual.requestLevel(99);
    Check.that("ein ungueltiger Wunsch wird verworfen, nicht geklemmt",
        !manual.isDue(3000L, on()));
    manual.requestLevel(-1);
    Check.that("auch ein negativer", !manual.isDue(4000L, on()));

    // ---- 14: manuelles Laden zieht den Levelzustand mit ----
    // Sonst wuerde ein Eingriff des Operators Sekunden spaeter vom naechsten
    // faelligen Wechsel ueberschrieben - der Eingriff waere sinnlos.
    SongStructureDirector sync = new SongStructureDirector(tags, new Fixed(0.0));
    sync.start(0L, on());
    Check.eq("startet auf ruhig", 0, sync.currentLevel());
    sync.noteLoaded("x1", 10000L, on());
    Check.eq("nach dem Laden eines dramatischen Presets ist das Level dramatisch",
        3, sync.currentLevel());
    Check.eq("und die Verweildauer laeuft ab jetzt", 10000L, sync.levelStartMillis());
    Check.that("also nicht sofort wieder faellig", !sync.isDue(11000L, on()));
    sync.noteLoaded("gibtsnicht", 20000L, on());
    Check.eq("ein nicht getaggtes Preset landet auf dem Rueckfall",
        EnergyLevelStore.FALLBACK_LEVEL, sync.currentLevel());
    // Nach einem manuellen Laden soll das geladene Preset nicht als
    // "zuletzt in diesem Level" verlorengehen: der naechste automatische
    // Wechsel in dieses Level soll es nicht sofort wiederholen.
    SongStructureDirector memo = new SongStructureDirector(tags,
        new Fixed(0.0, 0.0, 0.0));
    memo.start(0L, stay);           // stay: immer ruhig
    memo.noteLoaded("r1", 0L, stay);
    Check.eq("das manuell geladene Preset gilt als zuletzt gespielt",
        "r2", memo.nextPreset(0L, stay, ALL));

    // ---- 15: ausgeschaltet ----
    SongStructureConfig off = SongStructureConfig.withDefaults();  // enabled = false
    SongStructureDirector paused = new SongStructureDirector(tags, new Fixed(0.0));
    paused.start(0L, on());
    Check.that("ausgeschaltet nie faellig", !paused.isDue(999999999L, off));
    // Der Timer wird mitgezogen: sonst waere nach einer langen Aus-Phase beim
    // Einschalten sofort eine Verweildauer verstrichen und es wuerde mitten in
    // der laufenden Szene hart umgeschaltet.
    Check.that("und das Wiedereinschalten schaltet nicht sofort um",
        !paused.isDue(999999999L + 1000L, on()));
    Check.that("null als Konfiguration ist wie ausgeschaltet",
        !paused.isDue(999999999L + 2000L, null));

    // isDue() vor start(): der erste Aufruf startet, springt aber nicht.
    SongStructureDirector cold = new SongStructureDirector(tags, new Fixed(0.0));
    Check.that("der erste isDue-Aufruf startet nur den Timer",
        !cold.isDue(500L, on()));
    Check.eq("und beginnt auf ruhig", 0, cold.currentLevel());
    Check.eq("die Verweildauer laeuft ab diesem Moment", 500L, cold.levelStartMillis());

    System.exit(Check.report("SongStructureDirectorTest"));
  }

  // Verweildauer, die ein Director fuer ein bestimmtes Level bei einem
  // bestimmten Zufallswert zieht.
  static long dwellFor(EnergyLevelStore tags, int level, double random01) {
    SongStructureConfig cfg = new SongStructureConfig();
    SongStructureConfig defaults = SongStructureConfig.withDefaults();
    cfg.enabled = true;
    cfg.matrix = defaults.matrix;
    cfg.dwellMinMinutes = defaults.dwellMinMinutes;
    cfg.dwellMaxMinutes = defaults.dwellMaxMinutes;
    SongStructureDirector d = new SongStructureDirector(tags, new Fixed(random01));
    d.start(0L, cfg);
    d.forceLevel(level);
    return d.dwellMillis();
  }
}
