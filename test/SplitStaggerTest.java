import java.util.List;

// Prueft die Warteschlange der zeitversetzten Split-Kinder: Faelligkeit auf
// der Beat-Achse, Reihenfolge, Verhalten bei Ueberlauf.
public class SplitStaggerTest {

  static PendingSpawn spawn(double dueBeats, int stripeIdx) {
    PendingSpawn p = new PendingSpawn();
    p.dueBeats = dueBeats;
    p.ledPos = stripeIdx*600 + 5;
    p.stripeIdx = stripeIdx;
    p.speed = 16f;
    p.energy = 0.6f;
    p.decayScale = 1f;
    return p;
  }

  public static void main(String[] args) throws Exception {
    // ---- delayBeats: der Abstand zwischen den Kindern ----
    // Slot 0 ist der Zweig, der sofort startet. Exakt 0, nicht "fast 0" -
    // sonst haenge auch der erste Zweig eines jeden Splits an der Uhr, und
    // ein ausgeschalteter Sequencer-Takt verschoebe das ganze Netz.
    Check.near("Slot 0 hat keinen Versatz", 0.0, SplitStagger.delayBeats(16, 0), 1e-12);
    // Sechzehntel = ein Viertelbeat, Achtel = ein halber, Viertel = einer.
    Check.near("Slot 1 auf Sechzehnteln", 0.25, SplitStagger.delayBeats(16, 1), 1e-12);
    Check.near("Slot 2 auf Sechzehnteln", 0.50, SplitStagger.delayBeats(16, 2), 1e-12);
    Check.near("Slot 1 auf Achteln", 0.5, SplitStagger.delayBeats(8, 1), 1e-12);
    Check.near("Slot 1 auf Vierteln", 1.0, SplitStagger.delayBeats(4, 1), 1e-12);
    Check.near("Slot 1 auf Halben", 2.0, SplitStagger.delayBeats(2, 1), 1e-12);
    Check.near("Slot 1 auf Ganzen", 4.0, SplitStagger.delayBeats(1, 1), 1e-12);
    // Ein Regler, der auf einem krummen Wert stehen bleibt, verhaelt sich wie
    // der naechstniedrigere erlaubte Notenwert - dieselbe Rasterung wie beim
    // Sequencer, damit "Sechzehntel" ueberall dasselbe heisst.
    Check.near("krummer Notenwert rastet nach unten",
        SplitStagger.delayBeats(8, 1), SplitStagger.delayBeats(9, 1), 1e-12);
    Check.near("krummer Notenwert rastet nach unten",
        SplitStagger.delayBeats(4, 1), SplitStagger.delayBeats(5, 1), 1e-12);
    Check.near("Notenwert 0 rastet auf den kleinsten erlaubten",
        SplitStagger.delayBeats(1, 1), SplitStagger.delayBeats(0, 1), 1e-12);
    Check.near("negativer Slot gibt keinen Versatz",
        0.0, SplitStagger.delayBeats(16, -2), 1e-12);

    // ---- Nichts faellig vor der Zeit ----
    SplitStagger s = new SplitStagger();
    Check.eq("frisch ist die Schlange leer", 0, s.pendingCount());
    Check.eq("leere Schlange liefert nichts", 0, s.due(10.0).size());

    s.schedule(spawn(5.0, 3));
    Check.eq("eingereiht", 1, s.pendingCount());
    Check.eq("vor der Zeit nichts", 0, s.due(4.999).size());
    Check.eq("und nichts verloren", 1, s.pendingCount());

    // Genau auf der Grenze faellig: die Beat-Position springt in Schritten
    // von einem Frame, ein "echt groesser" liesse einen exakt getroffenen
    // Beat um einen ganzen Frame verrutschen.
    List<PendingSpawn> out = s.due(5.0);
    Check.eq("auf der Grenze faellig", 1, out.size());
    Check.eq("richtiger Stripe", 3, out.get(0).stripeIdx);
    Check.eq("nach der Ausgabe wieder leer", 0, s.pendingCount());
    Check.eq("und nicht ein zweites Mal", 0, s.due(9.0).size());

    // ---- Reihenfolge nach Faelligkeit, nicht nach Einreihung ----
    SplitStagger o = new SplitStagger();
    o.schedule(spawn(3.0, 7));
    o.schedule(spawn(1.0, 4));
    o.schedule(spawn(2.0, 5));
    List<PendingSpawn> three = o.due(10.0);
    Check.eq("alle drei faellig", 3, three.size());
    Check.eq("frueheste zuerst", 4, three.get(0).stripeIdx);
    Check.eq("dann die mittlere", 5, three.get(1).stripeIdx);
    Check.eq("zuletzt die spaeteste", 7, three.get(2).stripeIdx);

    // ---- Nur das Faellige geht raus ----
    SplitStagger part = new SplitStagger();
    part.schedule(spawn(1.0, 1));
    part.schedule(spawn(2.0, 2));
    part.schedule(spawn(3.0, 3));
    Check.eq("zwei faellig", 2, part.due(2.0).size());
    Check.eq("eines bleibt", 1, part.pendingCount());
    Check.eq("und kommt spaeter", 1, part.due(3.0).size());

    // ---- Ein Rueckwaertssprung der Beat-Position verliert nichts ----
    // MusicalClock akkumuliert und laeuft nie rueckwaerts; die Regel steht
    // hier trotzdem, weil "nichts faellig" der einzig richtige Ausfallmodus
    // ist - ein Verwerfen waere ein stiller Verlust von Impulsen.
    SplitStagger back = new SplitStagger();
    back.schedule(spawn(5.0, 2));
    Check.eq("NaN als Beat-Position liefert nichts", 0, back.due(Double.NaN).size());
    Check.eq("und verliert nichts", 1, back.pendingCount());
    Check.eq("weit davor liefert nichts", 0, back.due(-100.0).size());
    Check.eq("und verliert nichts", 1, back.pendingCount());
    Check.eq("spaeter kommt es doch", 1, back.due(5.0).size());

    // ---- clear() ----
    SplitStagger c = new SplitStagger();
    c.schedule(spawn(9.0, 1));
    c.schedule(spawn(9.0, 2));
    c.clear();
    Check.eq("clear leert die Schlange", 0, c.pendingCount());

    // ---- Die Obergrenze ----
    // Der Deckel schuetzt gegen einen Fall, den ein Regler herstellen kann:
    // ein langer Notenwert bei niedriger BPM haelt die Kinder sekundenlang
    // fest, waehrend an den Kreuzungen weiter gespaltet wird.
    SplitStagger full = new SplitStagger();
    for (int i = 0; i < SplitStagger.MAX_PENDING + 50; i++) {
      full.schedule(spawn(100.0, i % 30));
    }
    Check.eq("die Schlange waechst nicht ueber die Grenze",
        SplitStagger.MAX_PENDING, full.pendingCount());

    // Verworfen werden die NEUEN, nicht die wartenden: ein schon geplantes
    // Kind ist die Fortsetzung eines Impulses, den der Zuschauer gerade
    // ankommen sah. Es zugunsten eines spaeteren wegzuwerfen risse genau die
    // Bewegung ab, die sichtbar ist.
    SplitStagger keep = new SplitStagger();
    for (int i = 0; i < SplitStagger.MAX_PENDING; i++) {
      keep.schedule(spawn(1.0, 11));
    }
    keep.schedule(spawn(1.0, 22)); // muss abgewiesen werden
    List<PendingSpawn> kept = keep.due(1.0);
    Check.eq("alle wartenden kommen heraus", SplitStagger.MAX_PENDING, kept.size());
    boolean sawNew = false;
    for (int i = 0; i < kept.size(); i++) {
      if (kept.get(i).stripeIdx == 22) {
        sawNew = true;
      }
    }
    Check.that("der abgewiesene Neuzugang ist nicht dabei", !sawNew);

    // Ein null-Eintrag darf die Schlange nicht vergiften
    SplitStagger nil = new SplitStagger();
    nil.schedule(null);
    Check.eq("null wird nicht eingereiht", 0, nil.pendingCount());

    // ---- Die Werte kommen unveraendert wieder heraus ----
    // Die Warteschlange ist ein Briefkasten, kein Rechner: sie soll die
    // Kindwerte nicht anfassen. Insbesondere verliert ein wartendes Kind
    // keine Energie - siehe Kommentar in SplitStagger.
    SplitStagger v = new SplitStagger();
    PendingSpawn p = spawn(2.0, 9);
    p.speed = -42.5f;
    p.energy = 0.375f;
    p.decayScale = 0.8f;
    p.ledPos = 1234.5f;
    v.schedule(p);
    PendingSpawn got = v.due(2.0).get(0);
    Check.near("ledPos unveraendert", 1234.5, got.ledPos, 1e-6);
    Check.eq("stripeIdx unveraendert", 9, got.stripeIdx);
    Check.near("speed samt Vorzeichen unveraendert", -42.5, got.speed, 1e-6);
    Check.near("energy unveraendert", 0.375, got.energy, 1e-6);
    Check.near("decayScale unveraendert", 0.8, got.decayScale, 1e-6);

    System.exit(Check.report("SplitStaggerTest"));
  }
}
