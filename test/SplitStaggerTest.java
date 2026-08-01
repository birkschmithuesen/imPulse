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

  // Wie oft jede Notenwert-Klasse gezogen wird, wenn der Zufallswert
  // gleichverteilt von 0 bis 1 durchgefahren wird. Der Index ist der der
  // Klasse (0 = Ganze .. 4 = Sechzehntel), nicht der Notenwert selbst.
  static int[] countDraws(float[] weights, int draws) {
    int[] counts = new int[SplitStagger.NOTE_COUNT];
    int unknown = 0;
    for (int i = 0; i < draws; i++) {
      int noteValue = SplitStagger.pickNoteValue(weights, (i + 0.5)/draws);
      int index = -1;
      for (int k = 0; k < OriginSequencer.NOTE_VALUES.length; k++) {
        if (OriginSequencer.NOTE_VALUES[k] == noteValue) {
          index = k;
        }
      }
      // Einmal am Ende gemeldet statt je Ziehung: eine Pruefung je Ziehung
      // blaehte den Bericht auf 100 000 Zeilen auf und verdeckte damit die
      // Aussagen, um die es geht.
      if (index < 0) {
        unknown++;
      } else {
        counts[index]++;
      }
    }
    Check.eq("jede Ziehung ist eine bekannte Notenwert-Klasse", 0, unknown);
    return counts;
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

    // ---- pickNoteValue: welchen Notenwert diese Aufspaltung bekommt ----
    // Gezogen wird je Split-Ereignis, nicht je Kind: alle Zweige derselben
    // Aufspaltung stehen damit auf demselben Raster. Das prueft der Aufrufer
    // (LedNetworkTransportEffect, haengt an oscP5); hier steht die Ziehung.
    float[] onlyEighth = new float[SplitStagger.NOTE_COUNT];
    onlyEighth[3] = 100f;
    Check.eq("ein einziges Gewicht zieht immer sich selbst",
        8, SplitStagger.pickNoteValue(onlyEighth, 0.0));
    Check.eq("auch am oberen Rand des Zufallswerts",
        8, SplitStagger.pickNoteValue(onlyEighth, 1.0));

    // Verteilung ueber viele Ziehungen, wie in SplitFanoutTest. Der
    // Zufallswert wird gleichverteilt durchgefahren statt gewuerfelt - der
    // Test soll die Gewichtsrechnung pruefen, nicht Math.random().
    float[] mix = new float[SplitStagger.NOTE_COUNT];
    mix[2] = 20f;  // Viertel
    mix[3] = 30f;  // Achtel
    mix[4] = 50f;  // Sechzehntel
    int draws = 100000;
    int[] counts = countDraws(mix, draws);
    Check.eq("Ganze hat Gewicht 0 und kommt nie", 0, counts[0]);
    Check.eq("Halbe hat Gewicht 0 und kommt nie", 0, counts[1]);
    Check.near("Viertel bei 20 %", 0.20, counts[2]/(double) draws, 0.01);
    Check.near("Achtel bei 30 %", 0.30, counts[3]/(double) draws, 0.01);
    Check.near("Sechzehntel bei 50 %", 0.50, counts[4]/(double) draws, 0.01);

    // Die Summe muss nicht 100 sein - ein Operator dreht einzelne Regler,
    // ohne den Rest nachzurechnen.
    float[] raw = new float[SplitStagger.NOTE_COUNT];
    raw[3] = 3f;
    raw[4] = 1f;
    int[] rawCounts = countDraws(raw, draws);
    Check.near("3:1 macht 75 % Achtel", 0.75, rawCounts[3]/(double) draws, 0.01);
    Check.near("und 25 % Sechzehntel", 0.25, rawCounts[4]/(double) draws, 0.01);

    // Der entartete Fall faellt auf Sechzehntel: den kuerzesten Versatz und
    // den Auslieferungswert des einen festen Notenwert-Reglers, den diese
    // Gewichte 2026-08-01 abgeloest haben. Eine unbrauchbare Gewichtstabelle
    // soll die Aufspaltung lassen, wie sie war, statt sie ueber einen ganzen
    // Takt auseinanderzuziehen - dieselbe Regel wie SplitFanout.NEUTRAL_INDEX
    // ("alle Zweige") und SpeedQuantizer.NEUTRAL_INDEX (1x).
    Check.eq("alle Gewichte 0 gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(new float[SplitStagger.NOTE_COUNT], 0.5));
    Check.eq("null als Gewichtstabelle gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(null, 0.5));
    Check.eq("zu kurze Gewichtstabelle gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(new float[2], 0.5));
    Check.eq("NaN als Zufallswert gibt Sechzehntel",
        16, SplitStagger.pickNoteValue(mix, Double.NaN));
    float[] negative = new float[SplitStagger.NOTE_COUNT];
    negative[0] = -5f;
    negative[1] = Float.NaN;
    Check.eq("negative und NaN-Gewichte zaehlen als 0",
        16, SplitStagger.pickNoteValue(negative, 0.5));
    // Ein Zufallswert ausserhalb 0..1 darf keinen Notenwert erzeugen, den
    // delayBeats() nicht kennt.
    Check.eq("Zufallswert unter 0 zieht die erste Klasse mit Gewicht",
        4, SplitStagger.pickNoteValue(mix, -3.0));
    Check.eq("Zufallswert ueber 1 zieht die letzte Klasse mit Gewicht",
        16, SplitStagger.pickNoteValue(mix, 7.0));

    // Was herauskommt, ist immer ein Notenwert, den die Rasterung unveraendert
    // laesst - sonst rastete delayBeats() ihn still auf einen anderen.
    float[] alle = new float[SplitStagger.NOTE_COUNT];
    for (int i = 0; i < alle.length; i++) {
      alle[i] = 1f;
    }
    int[] alleCounts = countDraws(alle, draws);
    for (int i = 0; i < alle.length; i++) {
      Check.that("jede Klasse kommt bei gleichen Gewichten vor", alleCounts[i] > 0);
    }
    for (int i = 0; i <= 20; i++) {
      int nv = SplitStagger.pickNoteValue(alle, i/20.0);
      Check.eq("gezogener Notenwert ueberlebt die Rasterung",
          nv, OriginSequencer.quantizeNoteValue(nv));
      Check.that("und ergibt einen Versatz groesser 0",
          SplitStagger.delayBeats(nv, 1) > 0.0);
    }

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
