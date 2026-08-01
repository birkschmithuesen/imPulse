import java.io.DataOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import netP5.NetAddress;
import oscP5.OscMessage;
import oscP5.OscP5;

// Klebeschicht der topologiebasierten Melodiekomposition: haelt die vier
// OSC-Parameter, nimmt den Neuberechnungs-Befehl entgegen, schreibt die
// Zuordnungsdatei und sagt SuperCollider Bescheid.
//
// Die pruefbare Logik liegt bewusst NICHT hier, sondern in MelodyGraph,
// MelodyAssigner, MelodyModes und NodeMelodyStore - diese Klasse importiert
// oscP5 und ist deshalb kein Teil von test/run.sh, genau wie PresetManager.
//
// Warum der Startknoten kein Live-Regler ist (Konzept, Abschnitt 9): eine
// Aenderung verstellt keinen Wert, sondern loest eine Neuberechnung der
// GESAMTEN Zuordnung aus - alle Knoten bekommen neue Toene. Der Startknoten
// ist die Tonika, und von ihm aus waechst der ganze BFS-Baum; ein anderer
// Startknoten ist eine andere Komposition, kein anderer Klangregler.
class MelodyManager implements OscMessageSink {

  // Die Perzentil-Schwelle, ab der ein Knoten als Landmarke gilt. Das Konzept
  // nennt 75 % als Vorschlag und laesst den konkreten Wert ausdruecklich als
  // Geschmacksfrage offen (Abschnitt 11, verbleibend offen Punkt 1) - erst
  // mit der Landmarken-Rotation ist das ueberhaupt eine reine
  // Geschmacksfrage, vorher waere ein hoher Wert schaedlich gewesen.
  //
  // Bewusst KEIN OSC-Parameter: er wuerde wie startNode eine Neuberechnung
  // ausloesen und die Bedienoberflaeche um einen fuenften Regler erweitern,
  // dessen Wirkung sich nur im Vergleich zweier kompletter Laeufe zeigt. Wer
  // ihn aendern will, aendert hier eine Zahl und rechnet neu.
  static final float HUB_PERCENTILE = 0.75f;

  private final String dataDirectory;
  private final OscP5 oscP5;
  private final NetAddress soundTarget;
  private final MelodyGraph graph;

  RemoteControlledIntParameter mode;
  RemoteControlledIntParameter startNode;
  RemoteControlledIntParameter rootMidiNote;
  RemoteControlledIntParameter numOctaves;

  // digestMessage() laeuft im Draw-Thread (distributeMessages() wird aus
  // draw() gerufen), eine Synchronisierung braucht es also nicht. Gemerkt
  // statt sofort ausgefuehrt wird trotzdem: eine Neuberechnung liest den
  // Graph und schreibt eine Datei, das gehoert nicht mitten in die
  // Verteilschleife.
  private boolean pendingRecompute = false;

  private String message = "noch nichts berechnet";

  MelodyManager(String dataDirectory_, MelodyGraph graph_, OscP5 oscP5_,
      NetAddress soundTarget_) {
    dataDirectory = dataDirectory_;
    graph = graph_;
    oscP5 = oscP5_;
    soundTarget = soundTarget_;

    // Die Obergrenze kommt zur LAUFZEIT aus dem Graphen, nicht als Literal:
    // data/nodeCrossings.txt waechst waehrend der Kalibrierung, und eine fest
    // eingetragene 91 waere nach der naechsten Sitzung falsch (CLAUDE.md,
    // "Keine von der Kreuzungszahl abgeleitete Zahl als Literal").
    int maxNode = graph.nodeCount() - 1;
    if (maxNode < 0) {
      maxNode = 0;
    }
    int defaultStart = graph.defaultStartNode();
    if (defaultStart < 0) {
      defaultStart = 0;
    }

    // Default 4 = Phrygisch: der einzige der acht Modi, der den heute live
    // laufenden Tonvorrat unveraendert laesst. Damit aendert die Umstellung
    // genau eine Sache - die Verteilung der Toene, nicht den Vorrat - und
    // laesst sich hoerend gegen den alten Stand vergleichen.
    mode = new RemoteControlledIntParameter("/net/melody/mode", 4, 0,
        MelodyModes.count() - 1);
    // Default: der Knoten mit dem hoechsten Grad. Ein Hub hat die meisten
    // direkten Nachbarn, die Tonika steht damit von vornherein mit moeglichst
    // vielen Knoten in direkter Intervallbeziehung, und die BFS-Tiefe des
    // Graphen wird kleiner - weniger Tiefe heisst weniger akkumulierte Drift.
    startNode = new RemoteControlledIntParameter("/net/melody/startNode",
        defaultStart, 0, maxNode);
    // 24 (C1) bis 84 (C6) deckt den fuer Glockenklaenge sinnvollen Bereich ab.
    // Default 45 = A2, der heute gefahrene Grundton.
    rootMidiNote = new RemoteControlledIntParameter("/net/melody/rootMidiNote",
        45, 24, 84);
    // Nach oben auf 6 begrenzt: notesPerOctaveSet = scale.length * numOctaves
    // sprengte sonst bei den siebenstufigen Modi schnell den hoerbaren Bereich.
    numOctaves = new RemoteControlledIntParameter("/net/melody/numOctaves",
        3, 1, 6);

    // Ein Kommando, kein Parameter - dieselbe Bauform wie /net/activateNode
    // oder /preset/save. Genau deshalb ist die Neuberechnung EIN atomarer
    // Vorgang: die vier Werte sind fuer sich harmlos, ausgeloest wird erst
    // hier. Drei unabhaengige Trigger (je einer an startNode, rootMidiNote,
    // numOctaves) rechneten beim Verstellen aller drei dreimal, davon zweimal
    // mit einem halb gesetzten Zustand.
    OscMessageDistributor.registerAdress("/net/melody/recompute", this);
  }

  public void digestMessage(OscMessage newMessage) {
    if (newMessage.checkAddrPattern("/net/melody/recompute")) {
      pendingRecompute = true;
    }
  }

  // Dieser Sink haelt keine eigenen Parameter (die vier tragen sich selbst
  // ein). Ohne das leere writeToStream bekaeme remoteSettings.txt eine
  // Kommando-Zeile dazu.
  public void writeToStream(DataOutputStream outStream) {
  }

  // Aus draw() zu rufen, direkt nach
  // OscMessageDistributor.distributeMessages().
  void update() {
    if (!pendingRecompute) {
      return;
    }
    pendingRecompute = false;
    recompute();
  }

  String lastMessage() {
    return message;
  }

  MelodyMode currentMode() {
    return MelodyModes.at(mode.getValue());
  }

  String pathFor(MelodyMode m) {
    return new File(dataDirectory, NodeMelodyStore.fileNameFor(m.key)).getPath();
  }

  // Beim Start nur PRUEFEN, nicht rechnen (Konzept, Abschnitt 8): automatisches
  // Neurechnen machte die Datei zu einem Zwischenspeicher statt zu einer
  // Quelle, und eine von Hand nachkorrigierte Zeile waere beim naechsten Start
  // weg - ohne Meldung.
  String startupReport() {
    StringBuilder sb = new StringBuilder();
    sb.append(graph.report());
    MelodyMode m = currentMode();
    String path = pathFor(m);
    NodeMelodyStore store = new NodeMelodyStore();
    if (store.load(path)) {
      sb.append("\n").append(store.lastMessage());
      if (!m.key.equals(store.modeKey()) && store.modeKey().length() > 0) {
        sb.append("\nWARNUNG: die Datei nennt den Modus \"").append(store.modeKey())
          .append("\", erwartet war \"").append(m.key).append("\"");
      }
      if (store.size() != graph.nodeCount()) {
        // Kein Fehler, aber der haeufigste Grund fuer "ein Knoten klingt
        // falsch": zwischen Berechnung und heute hat eine Kalibriersitzung
        // die Kreuzungsliste geaendert.
        sb.append("\nWARNUNG: die Datei kennt ").append(store.size())
          .append(" Knoten, das Netz hat ").append(graph.nodeCount())
          .append(" - nach einer Kalibriersitzung neu berechnen "
              + "(/net/melody/recompute)");
      }
    } else {
      sb.append("\nWARNUNG: ").append(store.lastMessage())
        .append(" - die Klangseite faellt auf die alte nodeId-Zuordnung zurueck. "
            + "Mit /net/melody/recompute berechnen.");
    }
    return sb.toString();
  }

  // Neuberechnen UND persistieren sind derselbe Vorgang (Konzept, Abschnitt 9):
  // sonst gaebe es einen laufenden Zustand, den keine Datei beschreibt - genau
  // die Divergenz zwischen Live-Stand und master, die die Branch-Konvention
  // verhindern soll.
  boolean recompute() {
    if (graph.nodeCount() == 0) {
      message = "Melodie-Neuberechnung nicht moeglich: keine Kreuzungen geladen";
      System.out.println(message);
      return false;
    }
    MelodyMode m = currentMode();
    int threshold = graph.hubThreshold(HUB_PERCENTILE);
    // Math.random() ist hier richtig: die Ziehungen sind der einzige Zufall im
    // Verfahren, und genau ihn friert das Persistieren ein. Das Ergebnis ist
    // ein Wurf - aber ein festgehaltener.
    MelodyAssignment assignment = MelodyAssigner.assign(graph, m,
        startNode.getValue(), rootMidiNote.getValue(), numOctaves.getValue(),
        threshold, new RandomSource() {
          public double next() {
            return Math.random();
          }
        });

    String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .format(new Date());
    String path = pathFor(m);
    if (!NodeMelodyStore.write(path, m, assignment, threshold, stamp)) {
      message = "Melodie-Zuordnung nicht schreibbar: " + path;
      System.out.println(message);
      return false;
    }
    message = assignment.report() + " -> " + path;
    System.out.println(message);
    // Erst NACH dem erfolgreichen Schreiben: sonst laedt SuperCollider eine
    // Datei, die es nicht gibt oder die noch den alten Stand traegt. Dieselbe
    // Reihenfolge wie bei PresetManager.save().
    forwardToSound(m.key);
    return true;
  }

  // Fire-and-forget an denselben Port, auf dem SuperCollider schon
  // /net/hitNode und /net/impulse empfaengt. Laeuft sclang nicht, laeuft die
  // Visual-Show trotzdem weiter.
  private void forwardToSound(String modeKey) {
    if (oscP5 == null || soundTarget == null) {
      return;
    }
    OscMessage msg = new OscMessage("/net/melody/reload");
    msg.add(modeKey);
    oscP5.send(msg, soundTarget);
  }
}
