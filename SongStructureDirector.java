import java.util.ArrayList;
import java.util.List;

// Reiner Wertbehaelter fuer die Einstellungen der Song-Struktur-Ebene. Der
// Director haelt ihn NICHT - SongStructureParams fuellt ihn aus seinen
// RemoteControlled*Parametern und reicht ihn herein. So kennt der Director
// oscP5 nicht und bleibt pruefbar. Dasselbe Muster wie TrackConfig beim
// Sequencer.
class SongStructureConfig {

  boolean enabled;

  // [von][nach], Gewichte 0..100. Die Zeilensumme muss NICHT 100 sein und
  // wird beim Ziehen normalisiert (WeightedChoice) - dieselbe Konvention wie
  // /net/impulse/speedQuantize/weight/*. Ein Operator dreht einen Regler,
  // ohne die anderen drei nachzurechnen.
  float[][] matrix;

  // Verweildauer je Level, in MINUTEN. Minuten und nicht Sekunden, weil die
  // Regler im Web-UI in Minuten beschriftet sind und ein Bereich von
  // 0,5 bis 60 auf einem Sekunden-Regler unbedienbar waere.
  float[] dwellMinMinutes;
  float[] dwellMaxMinutes;

  // Auslieferungszustand: die Uebergangsmatrix aus dem Konzeptdokument, die
  // Verweildauern in Birks verkuerzter Fassung vom 2026-08-01 (kurze Zyklen
  // zum Ausprobieren; das Konzept schlug 15-35 / 10-20 / 6-14 / 3-8 Minuten
  // vor). Alle acht Zeitwerte sind live per OSC verstellbar, die Zahlen hier
  // sind nur der Startpunkt.
  static SongStructureConfig withDefaults() {
    SongStructureConfig cfg = new SongStructureConfig();
    // Kein Selbstlaeufer: enabled bleibt AUS. Die neue Schicht darf eine
    // laufende Show nicht ohne Zutun uebernehmen.
    cfg.enabled = false;
    cfg.matrix = new float[][] {
      //  ruhig mittel dynamisch dramatisch
      {  20f,  40f,  30f,  10f },   // von ruhig
      {  25f,  30f,  30f,  15f },   // von mittel
      {  35f,  30f,  20f,  15f },   // von dynamisch
      {  60f,  25f,  10f,   5f },   // von dramatisch
    };
    // Der Spannungsbogen steckt in diesen Zahlen: ruhige Passagen duerfen
    // sich Zeit nehmen, ein Hoehepunkt bleibt kurz.
    cfg.dwellMinMinutes = new float[] { 3f,   2f,  1f,  0.5f };
    cfg.dwellMaxMinutes = new float[] { 5f,   3f,  2f,  1f   };
    return cfg;
  }
}

// Die Dramaturgie ueber eine ganze Nacht: eine Markov-Kette ueber vier
// Energie-Level, die bei jedem faelligen Wechsel zuerst das naechste LEVEL
// wuerfelt und danach ein Preset innerhalb dieses Levels waehlt.
//
// Sitzt OBERHALB von PresetScheduler und ersetzt nicht dessen Zeitlogik,
// sondern die NAMENSQUELLE, die er bedient: der alphabetische Umlauf ueber
// data/presets/ mit einem festen Intervall ist genau der vorhersehbare Loop,
// den diese Schicht abloest. PresetScheduler.java bleibt unveraendert.
//
// Ohne Processing, ohne oscP5 und ohne eigene Wanduhr: Zeit, Konfiguration
// und Namensliste werden hereingegeben, damit die Klasse ohne Sketch-Laufzeit
// pruefbar ist. Dasselbe Muster wie PresetScheduler, OriginSequencer und
// ImpulseOscThrottle. Der Zufall kommt ueber RandomSource herein.
//
// Es gibt bewusst KEINE Tageszeit-Kopplung (Birk, 2026-08-01): die
// Dramaturgie soll auch tagsueber und bei einer kurzen Session funktionieren,
// ohne Sondercode fuer die Uhrzeit.
class SongStructureDirector {

  private final EnergyLevelStore tagging;
  private final RandomSource rand;

  // Startlevel ist fest ruhig (Birk, 2026-08-01), nicht gewuerfelt: die
  // Installation faehrt sanft hoch, und ein Neustart mitten in der Nacht
  // beginnt nicht mit einem Drop.
  private static final int START_LEVEL = 0;

  private int level = START_LEVEL;
  private long levelStart = 0L;
  private long dwell = 0L;
  private boolean started = false;

  // Manueller Sprung, gesetzt aus /songStructure/goto. -1 = keiner. Er wirkt
  // EINMAL und verfaellt danach: ein dauerhafter Zwang waere ein zweiter
  // Schalter neben /songStructure/enabled, der dasselbe abschaltet.
  private int pendingLevel = -1;

  // Zuletzt in diesem Level gespieltes Preset, je Level. Beim naechsten
  // Besuch desselben Levels wird es uebersprungen, solange es Alternativen
  // gibt - bei zwei bis vier Presets je Level faellt eine unmittelbare
  // Wiederholung sonst sofort auf.
  private final String[] lastInLevel = new String[EnergyLevelStore.LEVEL_COUNT];

  private String message = "";

  SongStructureDirector(EnergyLevelStore tagging_, RandomSource rand_) {
    tagging = tagging_;
    rand = rand_;
  }

  int currentLevel() {
    return level;
  }

  String currentLevelName() {
    return EnergyLevelStore.nameOf(level);
  }

  long dwellMillis() {
    return dwell;
  }

  long levelStartMillis() {
    return levelStart;
  }

  String lastMessage() {
    return message;
  }

  // Setzt das Startlevel und zieht die erste Verweildauer. Wird auch vom
  // ersten isDue()-Aufruf gerufen, damit ein vergessener Aufruf nicht in
  // einem sofortigen Sprung endet.
  void start(long nowMillis, SongStructureConfig cfg) {
    level = START_LEVEL;
    levelStart = nowMillis;
    started = true;
    pendingLevel = -1;
    dwell = drawDwell(cfg, level);
  }

  // Setzt das Level direkt und zieht eine passende Verweildauer. Gedacht fuer
  // Pruefungen und Diagnose; der Betriebsweg ist requestLevel() bzw.
  // nextPreset().
  void forceLevel(int newLevel) {
    if (newLevel < 0 || newLevel >= EnergyLevelStore.LEVEL_COUNT) {
      return;
    }
    level = newLevel;
    dwell = drawDwell(null, level);
  }

  // Wunsch des Operators, jetzt zu einem bestimmten Level zu wechseln.
  // Ungueltige Werte werden VERWORFEN und nicht geklemmt: ein verirrter Wert
  // soll nicht als "dann eben ruhig" durchgehen.
  void requestLevel(int wanted) {
    if (wanted < 0 || wanted >= EnergyLevelStore.LEVEL_COUNT) {
      message = "Levelwunsch " + wanted + " liegt ausserhalb 0.."
          + (EnergyLevelStore.LEVEL_COUNT - 1) + " und wird verworfen";
      return;
    }
    pendingLevel = wanted;
  }

  // true, wenn jetzt gewechselt werden soll. Veraendert das Level nicht - der
  // Aufrufer holt danach die Namensliste und ruft nextPreset().
  boolean isDue(long nowMillis, SongStructureConfig cfg) {
    if (cfg == null || !cfg.enabled) {
      // Timer mitziehen: sonst waere nach einer langen Aus-Phase beim
      // Einschalten sofort eine Verweildauer verstrichen und es wuerde mitten
      // in der laufenden Szene hart umgeschaltet. Dieselbe Regel wie
      // PresetScheduler.isDue().
      levelStart = nowMillis;
      return false;
    }
    if (!started) {
      start(nowMillis, cfg);
      return false;
    }
    if (pendingLevel >= 0) {
      return true;
    }
    return nowMillis - levelStart >= clampedDwell(cfg);
  }

  // Der Name des naechsten Presets, oder null wenn es keines gibt.
  //
  // Reihenfolge: erst das Level wuerfeln (gewichtet nach der Zeile des
  // aktuellen Levels), dann innerhalb des Levels ein Preset. Genau diese zwei
  // Stufen sind der Unterschied zum alten Loop - ein flacher Zufall ueber
  // alle Presets kennt keinen Spannungsbogen.
  String nextPreset(long nowMillis, SongStructureConfig cfg, List<String> allNames) {
    if (allNames == null || allNames.isEmpty()) {
      message = "keine Presets vorhanden, kein Wechsel moeglich";
      return null;
    }
    int nextLevel;
    if (pendingLevel >= 0) {
      nextLevel = pendingLevel;
      pendingLevel = -1;
      message = "Levelwechsel von Hand nach " + EnergyLevelStore.nameOf(nextLevel);
    } else {
      float[] row = rowOf(cfg, level);
      nextLevel = WeightedChoice.pick(row, EnergyLevelStore.LEVEL_COUNT,
          rand.next(), EnergyLevelStore.FALLBACK_LEVEL);
      message = "";
    }

    List<String> pool = tagging.presetsForLevel(nextLevel, allNames);
    if (pool.isEmpty()) {
      // Ein Level ohne Presets darf die Show nicht anhalten. Das aktuelle
      // Level bleibt stehen, statt in einen Zustand zu wechseln, aus dem
      // heraus nichts zu spielen ist.
      String wanted = EnergyLevelStore.nameOf(nextLevel);
      pool = tagging.presetsForLevel(level, allNames);
      if (pool.isEmpty()) {
        // Auch das aktuelle Level ist leer: irgendetwas ist besser als
        // Stillstand, und die Meldung nennt beide Level.
        message = "Level " + wanted + " und " + currentLevelName()
            + " haben kein Preset - es wird aus allen gewaehlt";
        pool = new ArrayList<String>(allNames);
      } else {
        message = "Level " + wanted + " hat kein Preset - bleibe bei "
            + currentLevelName();
        nextLevel = level;
      }
    }

    String chosen = pickWithinLevel(pool, lastInLevel[nextLevel]);
    level = nextLevel;
    lastInLevel[nextLevel] = chosen;
    levelStart = nowMillis;
    dwell = drawDwell(cfg, nextLevel);
    started = true;
    return chosen;
  }

  // Vom Start-Preset und von jedem erfolgreichen /preset/load zu rufen.
  //
  // Ein manueller Eingriff soll stehen bleiben: ohne diese Synchronisierung
  // liefe die Verweildauer des alten Levels weiter und der naechste faellige
  // Wechsel ueberschriebe den Eingriff womoeglich Sekunden spaeter. Das
  // geladene Preset gilt ausserdem als "zuletzt in diesem Level gespielt",
  // damit der naechste automatische Besuch dieses Levels es nicht sofort
  // wiederholt.
  void noteLoaded(String name, long nowMillis, SongStructureConfig cfg) {
    if (name == null) {
      return;
    }
    level = tagging.levelOf(name);
    lastInLevel[level] = name;
    levelStart = nowMillis;
    dwell = drawDwell(cfg, level);
    started = true;
  }

  // Ein Preset aus dem Pool, moeglichst nicht dasselbe wie beim letzten Mal
  // in diesem Level. Bleibt danach nichts uebrig (genau ein Preset im Level),
  // wird es wiederholt - verstummen waere die schlechtere Antwort.
  private String pickWithinLevel(List<String> pool, String avoid) {
    List<String> candidates = pool;
    if (avoid != null && pool.size() > 1) {
      candidates = new ArrayList<String>(pool.size());
      for (int i = 0; i < pool.size(); i++) {
        if (!avoid.equals(pool.get(i))) {
          candidates.add(pool.get(i));
        }
      }
      if (candidates.isEmpty()) {
        candidates = pool;
      }
    }
    int index = (int) (clamp01(rand.next())*candidates.size());
    if (index >= candidates.size()) {
      index = candidates.size() - 1;   // random01 == 1.0
    }
    return candidates.get(index);
  }

  // Die schon gezogene Verweildauer, geklemmt auf die AKTUELLE Spanne.
  //
  // Gezogen wird beim Levelstart, sonst zoege jeder Frame eine neue Zahl und
  // der Wechsel kaeme, sobald einmal eine kleine gezogen wird. Ohne die
  // Klemmung haette ein Operator, der die Spanne waehrend eines laufenden
  // Abschnitts von 30 auf 3 Minuten verengt, aber trotzdem noch 30 Minuten
  // zu warten - ohne Fehler, ohne Symptom, nur Stillstand.
  private long clampedDwell(SongStructureConfig cfg) {
    long low = minutesToMillis(minOf(cfg, level));
    long high = minutesToMillis(maxOf(cfg, level));
    if (low > high) {
      long swap = low; low = high; high = swap;
    }
    if (dwell < low) {
      return low;
    }
    if (dwell > high) {
      return high;
    }
    return dwell;
  }

  // Gleichverteilt innerhalb der Spanne des Levels. Eine Glockenkurve waere
  // "musikalischer", ist aber eine Verfeinerung, die sich spaeter ohne
  // Strukturaenderung nachruesten laesst - fuer die erste Kalibrierung am
  // Geraet ist "Level X dauert irgendwo zwischen a und b Minuten" die
  // leichter pruefbare Aussage.
  private long drawDwell(SongStructureConfig cfg, int forLevel) {
    float lo = minOf(cfg, forLevel);
    float hi = maxOf(cfg, forLevel);
    if (lo > hi) {
      float swap = lo; lo = hi; hi = swap;   // Fehlbedienung im UI, kein Absturz
    }
    return minutesToMillis(lo + (hi - lo)*(float) clamp01(rand.next()));
  }

  private static long minutesToMillis(float minutes) {
    long millis = (long) (minutes*60000f);
    // Eine Verweildauer von 0 hiesse: in jedem Frame ein neues Preset. Das
    // Laden liest eine Datei, das Netz stuende in Sekunden.
    return millis < 1000L ? 1000L : millis;
  }

  private static double clamp01(double value) {
    if (Double.isNaN(value)) {
      return 0.5;
    }
    if (value < 0.0) {
      return 0.0;
    }
    if (value > 1.0) {
      return 1.0;
    }
    return value;
  }

  // Zugriffe auf die Konfiguration gehen alle hierdurch: eine unvollstaendig
  // gefuellte Konfiguration (etwa aus einem Test oder aus forceLevel) darf
  // keine NullPointerException geben, sondern faellt auf die
  // Auslieferungswerte zurueck.
  private static final SongStructureConfig DEFAULTS = SongStructureConfig.withDefaults();

  private static float[] rowOf(SongStructureConfig cfg, int from) {
    if (cfg == null || cfg.matrix == null || from < 0 || from >= cfg.matrix.length
        || cfg.matrix[from] == null) {
      return DEFAULTS.matrix[from < 0 || from >= EnergyLevelStore.LEVEL_COUNT ? 0 : from];
    }
    return cfg.matrix[from];
  }

  private static float minOf(SongStructureConfig cfg, int forLevel) {
    int i = (forLevel < 0 || forLevel >= EnergyLevelStore.LEVEL_COUNT) ? 0 : forLevel;
    if (cfg == null || cfg.dwellMinMinutes == null
        || i >= cfg.dwellMinMinutes.length) {
      return DEFAULTS.dwellMinMinutes[i];
    }
    return cfg.dwellMinMinutes[i];
  }

  private static float maxOf(SongStructureConfig cfg, int forLevel) {
    int i = (forLevel < 0 || forLevel >= EnergyLevelStore.LEVEL_COUNT) ? 0 : forLevel;
    if (cfg == null || cfg.dwellMaxMinutes == null
        || i >= cfg.dwellMaxMinutes.length) {
      return DEFAULTS.dwellMaxMinutes[i];
    }
    return cfg.dwellMaxMinutes[i];
  }
}
