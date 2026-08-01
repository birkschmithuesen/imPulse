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
