import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

// Ein geplanter Kind-Impuls, der noch nicht losgelaufen ist.
//
// Reiner Wertbehaelter, ausdruecklich KEINE TravellingActivation: die ist eine
// innere Klasse von LedNetworkTransportEffect und haengt damit an oscP5 und
// Processing. Dieselbe Trennung wie TrackConfig zum OriginSequencer - der
// Effekt baut das Objekt, die Zeitlogik bleibt pruefbar.
class PendingSpawn {
  double dueBeats;   // Beat-Position, ab der das Kind spawnen darf
  float ledPos;      // globaler LED-Index als float, wie TravellingActivation
  int stripeIdx;
  float speed;       // Betrag und Richtung, wie beim sofort gespawnten Kind
  float energy;
  float decayScale;
  // Der Ursprungs-Baum des Elternimpulses (0..3, -1 = unbekannt). Ein
  // wartendes Kind erbt ihn wie jedes andere Split-Kind - ohne dieses Feld
  // waere der Klangbias ausgerechnet an den zeitversetzten Zweigen weg, und
  // zwar lautlos.
  int originTree;
  // Der Knoten, an dem dieses Kind entstanden ist - gebraucht, weil
  // /net/hitNode erst beim tatsaechlichen Start des Kindes rausgeht und nicht
  // schon beim Einreihen (siehe LedNetworkTransportEffect.sendOscMessage).
  //
  // Als drei Einzelwerte und ausdruecklich NICHT als LedNetworkNode-Referenz,
  // aus zwei Gruenden. Erstens haengt LedStripeNetworks.java an
  // processing.core; eine Referenz zoege Processing in diese Datei, die
  // bewusst ohne auskommt. Zweitens baut applyCrossings bei "R" in der
  // Kalibrierung ALLE Knotenobjekte neu auf - eine gehaltene Referenz zeigte
  // danach auf einen Knoten, den es so nicht mehr gibt, waehrend diese drei
  // Werte den Stand des Treffers tragen, also genau den Punkt, an dem das
  // Kind entstanden ist.
  int nodeId = -1;
  float nodePosX;
  float nodePosY;
}

// Warteschlange fuer die zeitversetzten Kinder einer Aufspaltung.
//
// Die Kinder eines Splits starteten bisher alle im selben Frame. Wenn statt
// aller nur zwei Zweige spawnen (siehe SplitFanout), ist ein Versatz zwischen
// ihnen hoerbar - aber nur, wenn er im Takt liegt. Deshalb zaehlt diese
// Schlange in BEATS, nicht in Millisekunden: die Faelligkeit kommt aus
// MusicalClock, derselben Phase, auf der auch der Origin-Sequencer laeuft.
// Ein Tempowechsel aendert damit die Rate, nicht die Position - dieselbe
// Zusage wie dort, und ein Millisekunden-Delay haette sie gebrochen.
//
// Ein wartendes Kind verliert KEINE Energie. Der Zerfall haengt an der
// Wanduhr und wuerde ein spaeter startendes Geschwister systematisch dunkler
// machen als sein Zwilling - der Versatz soll rhythmisch sein, nicht auch
// noch eine Dynamikstufe. Bei den ueblichen Werten (Sechzehntel bei 60 BPM =
// 0,25 s, lifetime 0,02/s) ginge es ohnehin um 0,005 Energie.
//
// Ohne Processing und ohne oscP5, damit test/run.sh sie uebersetzen kann.
class SplitStagger {

  // Obergrenze der Schlange. Ein Regler kann den Fall herstellen, der sie
  // braucht: ein langer Notenwert bei niedriger BPM haelt die Kinder
  // sekundenlang fest, waehrend an den Kreuzungen weiter gespaltet wird.
  // Ohne Deckel waechst die Liste in einer Nachtshow unbegrenzt.
  static final int MAX_PENDING = 512;

  private final List<PendingSpawn> pending = new ArrayList<PendingSpawn>();

  private static final Comparator<PendingSpawn> BY_DUE = new Comparator<PendingSpawn>() {
    public int compare(PendingSpawn a, PendingSpawn b) {
      return Double.compare(a.dueBeats, b.dueBeats);
    }
  };

  // Zahl der Notenwert-Klassen, aus denen der Versatz gezogen wird -
  // dieselben wie beim Sequencer und in derselben Reihenfolge
  // (Index 0 = Ganze ... Index 4 = Sechzehntel).
  static final int NOTE_COUNT = OriginSequencer.NOTE_VALUES.length;

  // Der Rueckfall fuer den entarteten Fall: Sechzehntel, der kuerzeste
  // Versatz. Bis 2026-08-01 war das der Auslieferungswert des einen festen
  // Notenwert-Reglers, den diese Gewichte abgeloest haben; eine unbrauchbare
  // Gewichtstabelle soll die Aufspaltung also lassen, wie sie war, statt sie
  // ueber einen ganzen Takt auseinanderzuziehen. Dieselbe Regel wie
  // SplitFanout.NEUTRAL_INDEX und SpeedQuantizer.NEUTRAL_INDEX.
  static final int NEUTRAL_NOTE_INDEX = NOTE_COUNT - 1;

  // Der Notenwert EINER Aufspaltung, gezogen nach Gewichten.
  //
  // Gezogen wird je Split-Ereignis, nicht je Kind: alle Zweige derselben
  // Aufspaltung stehen damit auf demselben Raster. Zoege jedes Kind fuer sich,
  // waeren schon die Abstaende innerhalb einer Aufspaltung ungleich - und
  // damit genau die Gleichmaessigkeit weg, an der ein Rhythmus ueberhaupt zu
  // erkennen ist. Was variieren soll, ist die Aufspaltung als Ganzes: mal
  // eine dichte Sechzehntel-Figur, mal ein weiter Viertel-Abstand.
  //
  // Die Ziehung selbst steht in WeightedChoice, geteilt mit SplitFanout und
  // SpeedQuantizer: ein Gewicht von 0 zieht nie, NaN und negative Werte
  // gelten als 0, die Summe muss nicht 100 sein.
  static int pickNoteValue(float[] weights, double random01) {
    return OriginSequencer.noteValueAt(
        WeightedChoice.pick(weights, NOTE_COUNT, NEUTRAL_NOTE_INDEX, random01));
  }

  // Versatz des slot-ten Kindes in Beats. Slot 0 ist der Zweig, der sofort
  // startet, und bekommt exakt 0 - sonst haenge auch er an der Uhr, und der
  // Split verschoebe sich als Ganzes gegenueber dem, was der Zuschauer
  // ankommen sah.
  //
  // Der Notenwert wird gerastet wie beim Sequencer (OriginSequencer.
  // quantizeNoteValue), damit "Sechzehntel" im ganzen Sketch dasselbe heisst -
  // RemoteControlledIntParameter kann keine Aufzaehlung, der Regler steht
  // zwischendurch also auf krummen Werten.
  static double delayBeats(int noteValue, int slot) {
    if (slot <= 0) {
      return 0.0;
    }
    return slot*MusicalClock.beatsPerNote(OriginSequencer.quantizeNoteValue(noteValue));
  }

  // Reiht ein Kind ein. Ueber der Obergrenze wird der NEUE abgewiesen, nicht
  // ein wartender verworfen: ein schon geplantes Kind ist die Fortsetzung
  // eines Impulses, den der Zuschauer gerade hat ankommen sehen. Es zugunsten
  // eines spaeteren wegzuwerfen risse genau die Bewegung ab, die sichtbar ist.
  void schedule(PendingSpawn p) {
    if (p == null || pending.size() >= MAX_PENDING) {
      return;
    }
    pending.add(p);
  }

  // Alle Kinder, die bei dieser Beat-Position starten duerfen, in der
  // Reihenfolge ihrer Faelligkeit. Sie sind danach aus der Schlange heraus.
  //
  // Faellig ist "dueBeats <= beats", nicht "<": die Beat-Position springt in
  // Frame-Schritten, ein echt-groesser liesse einen exakt getroffenen Beat um
  // einen ganzen Frame verrutschen.
  //
  // Eine unbrauchbare Beat-Position (NaN) liefert nichts und verwirft nichts.
  // MusicalClock laeuft zwar nie rueckwaerts, aber "nichts faellig" ist der
  // einzig richtige Ausfallmodus - ein Verwerfen waere ein stiller Verlust
  // von Impulsen.
  List<PendingSpawn> due(double beats) {
    if (pending.isEmpty() || Double.isNaN(beats)) {
      return Collections.emptyList();
    }
    List<PendingSpawn> out = null;
    for (int i = pending.size() - 1; i >= 0; i--) {
      PendingSpawn p = pending.get(i);
      if (p.dueBeats <= beats) {
        if (out == null) {
          out = new ArrayList<PendingSpawn>();
        }
        out.add(p);
        pending.remove(i);
      }
    }
    if (out == null) {
      return Collections.emptyList();
    }
    Collections.sort(out, BY_DUE);
    return out;
  }

  int pendingCount() {
    return pending.size();
  }

  void clear() {
    pending.clear();
  }
}
