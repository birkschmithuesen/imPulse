import java.io.DataOutputStream;

import oscP5.OscMessage;

// Die fernsteuerbaren Parameter der Song-Struktur-Ebene und der Empfaenger des
// manuellen Levelwechsels.
//
// Klebeschicht: die pruefbare Logik liegt in SongStructureDirector und
// EnergyLevelStore, diese Klasse importiert oscP5 und ist deshalb NICHT Teil
// von test/run.sh - dieselbe Aufteilung wie PresetManager gegenueber
// PresetStore/PresetScheduler.
//
// Alle 25 Parameter sind von jedem Preset ausgeschlossen (siehe
// PresetStore.EXCLUDED_PREFIXES): sie sind Transport, nicht Inhalt.
class SongStructureParams implements OscMessageSink {

  // Manueller Levelwechsel. Ein KOMMANDO, kein Parameter - dieselbe
  // Konstruktion wie /net/activateNode: es feuert beim Eintreffen, taucht
  // wegen des leeren writeToStream() nicht in remoteSettings.txt auf und kann
  // per Konstruktion nicht in ein Preset geraten, weil diese Klasse
  // PresetTarget nicht implementiert.
  //
  // Argument ist 1..4 (ruhig..dramatisch), nicht 0..3: 0 waere im Web-UI und
  // in einer OSC-Zeile nicht von "kein Argument" zu unterscheiden.
  static final String GOTO_ADDRESS = "/songStructure/goto";

  final RemoteControlledIntParameter enabled;
  final RemoteControlledFloatParameter[][] matrix =
      new RemoteControlledFloatParameter[EnergyLevelStore.LEVEL_COUNT][EnergyLevelStore.LEVEL_COUNT];
  final RemoteControlledFloatParameter[] dwellMin =
      new RemoteControlledFloatParameter[EnergyLevelStore.LEVEL_COUNT];
  final RemoteControlledFloatParameter[] dwellMax =
      new RemoteControlledFloatParameter[EnergyLevelStore.LEVEL_COUNT];

  // Wiederverwendet statt in jedem Frame neu angelegt: update() laeuft mit
  // 40 Hz, und der Director liest den Halter nur, er merkt ihn sich nicht.
  private final SongStructureConfig config = new SongStructureConfig();

  // Aus digestMessage() gesetzt, aus takePendingLevel() abgeholt. -1 = kein
  // Wunsch. digestMessage() laeuft im Draw-Thread (distributeMessages() wird
  // aus draw() gerufen), eine Synchronisierung braucht es also nicht.
  private int pendingLevel = -1;

  // Verweildauer-Bereich der Regler. 0,5 Minuten unten, weil das Birks
  // kuerzeste vorgesehene Dauer ist (dramatisch); 60 Minuten oben, damit sich
  // auch die urspruenglich im Konzept vorgeschlagenen 15-35 Minuten fuer
  // ruhige Passagen wieder einstellen lassen, ohne den Code anzufassen.
  private static final float DWELL_MIN_MINUTES = 0.5f;
  private static final float DWELL_MAX_MINUTES = 60f;

  SongStructureParams() {
    SongStructureConfig defaults = SongStructureConfig.withDefaults();

    // Default 0: die neue Schicht darf eine laufende Show nicht ohne Zutun
    // uebernehmen. Dieselbe Ueberlegung wie bei /preset/scheduler/enabled.
    enabled = new RemoteControlledIntParameter("/songStructure/enabled", 0, 0, 1);

    for (int from = 0; from < EnergyLevelStore.LEVEL_COUNT; from++) {
      for (int to = 0; to < EnergyLevelStore.LEVEL_COUNT; to++) {
        // Klartextnamen statt Zahlen in der Adresse: "/songStructure/matrix/
        // dramatisch/ruhig" ist in einer OSC-Zeile und in remoteSettings.txt
        // ohne Nachschlagen lesbar, "/songStructure/matrix/3/0" nicht.
        String address = "/songStructure/matrix/" + EnergyLevelStore.LEVEL_NAMES[from]
            + "/" + EnergyLevelStore.LEVEL_NAMES[to];
        // 0..100 und keine Summenbedingung: normalisiert wird beim Ziehen
        // (WeightedChoice), damit ein Operator einen Regler drehen kann, ohne
        // die anderen drei nachzurechnen. Dieselbe Konvention wie
        // /net/impulse/speedQuantize/weight/*.
        matrix[from][to] = new RemoteControlledFloatParameter(address,
            defaults.matrix[from][to], 0f, 100f);
      }
    }

    for (int level = 0; level < EnergyLevelStore.LEVEL_COUNT; level++) {
      String base = "/songStructure/dwell/" + EnergyLevelStore.LEVEL_NAMES[level] + "/";
      dwellMin[level] = new RemoteControlledFloatParameter(base + "min",
          defaults.dwellMinMinutes[level], DWELL_MIN_MINUTES, DWELL_MAX_MINUTES);
      dwellMax[level] = new RemoteControlledFloatParameter(base + "max",
          defaults.dwellMaxMinutes[level], DWELL_MIN_MINUTES, DWELL_MAX_MINUTES);
    }

    config.matrix = new float[EnergyLevelStore.LEVEL_COUNT][EnergyLevelStore.LEVEL_COUNT];
    config.dwellMinMinutes = new float[EnergyLevelStore.LEVEL_COUNT];
    config.dwellMaxMinutes = new float[EnergyLevelStore.LEVEL_COUNT];

    OscMessageDistributor.registerAdress(GOTO_ADDRESS, this);
  }

  public void digestMessage(OscMessage newMessage) {
    if (!newMessage.checkAddrPattern(GOTO_ADDRESS) || newMessage.arguments().length == 0) {
      return;
    }
    // Int und Float werden beide angenommen: manche Fernsteuerungen schicken
    // grundsaetzlich Floats. Anders als bei den Parametern wird hier NICHT von
    // 0..1 auf einen Bereich gestreckt - das hier ist eine Levelnummer, keine
    // Reglerstellung.
    int wanted;
    try {
      wanted = newMessage.get(0).intValue();
    } catch (RuntimeException e) {
      System.out.println("Song-Struktur: " + GOTO_ADDRESS
          + " braucht eine Levelnummer 1.." + EnergyLevelStore.LEVEL_COUNT);
      return;
    }
    if (wanted < 1 || wanted > EnergyLevelStore.LEVEL_COUNT) {
      System.out.println("Song-Struktur: Levelnummer " + wanted
          + " liegt ausserhalb 1.." + EnergyLevelStore.LEVEL_COUNT
          + " und wird verworfen");
      return;
    }
    pendingLevel = wanted - 1;
  }

  // Dieser Sink haelt keine eigenen Adressen fuer den Dump: die 25 Parameter
  // schreiben sich selbst. Ohne das leere writeToStream bekaeme
  // remoteSettings.txt eine Kommando-Zeile fuer /songStructure/goto dazu, und
  // das Web-UI machte daraus einen Regler - genau der Fehler, den
  // docs/webui-parameter-review-2026-07-30.md fuer /net/activate* beschreibt.
  public void writeToStream(DataOutputStream outStream) {
  }

  // Der Levelwunsch, oder -1. Verfaellt beim Abholen: ein dauerhafter Zwang
  // waere ein zweiter Schalter neben /songStructure/enabled, der dasselbe
  // abschaltet.
  int takePendingLevel() {
    int wanted = pendingLevel;
    pendingLevel = -1;
    return wanted;
  }

  // Der aus den Parametern gefuellte Wertehalter. Wird in jedem Frame neu
  // gefuellt, damit eine Aenderung per OSC sofort wirkt - dasselbe Muster wie
  // TrackConfig im Sequencer.
  SongStructureConfig config() {
    config.enabled = enabled.getValue() != 0;
    for (int from = 0; from < EnergyLevelStore.LEVEL_COUNT; from++) {
      for (int to = 0; to < EnergyLevelStore.LEVEL_COUNT; to++) {
        config.matrix[from][to] = matrix[from][to].getValue();
      }
    }
    for (int level = 0; level < EnergyLevelStore.LEVEL_COUNT; level++) {
      config.dwellMinMinutes[level] = dwellMin[level].getValue();
      config.dwellMaxMinutes[level] = dwellMax[level].getValue();
    }
    return config;
  }
}
