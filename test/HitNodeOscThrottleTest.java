import java.util.List;

// Der Sendetakt von /net/hitNode: Warteschlange statt Verwerfen.
//
// Der Aufbau spiegelt ImpulseOscThrottleTest, die Zusage ist aber die
// umgekehrte: dort wird ausgewaehlt und verworfen, hier wird alles
// ausgeliefert, nur eben zeitlich gestreckt. Was hier gepruefte Zusage ist:
// kein Verlust unterhalb der Obergrenze, chronologische Reihenfolge, und ein
// entarteter Ratenwert fuehrt weder zu einem ungedrosselten Schwall noch zu
// Stille.
public class HitNodeOscThrottleTest {

  // Ein Treffer mit erkennbarer Nummer - PendingSpawn traegt sie in nodeId,
  // die Reihenfolge der Auslieferung ist damit nachpruefbar.
  private static PendingSpawn hit(int nr) {
    PendingSpawn p = new PendingSpawn();
    p.nodeId = nr;
    return p;
  }

  // Ein Frame des Sketches: 40 Hz, wie drawMe() tatsaechlich laeuft.
  private static final double FRAME = 0.025;

  public static void main(String[] args) throws Exception {

    // ---- Normalbetrieb: nichts wird verzoegert, nichts geht verloren ----
    //
    // Wenige Treffer je Sekunde, weit unter rateHz. Jeder Treffer geht in dem
    // Frame raus, in dem er eingereiht wurde - der Mechanismus ist eine
    // Notbremse und darf im Alltag nicht spuerbar sein.
    HitNodeOscThrottle normal = new HitNodeOscThrottle();
    double t = 1000.0;
    for (int frame = 0; frame < 40; frame++) {
      normal.enqueue(hit(frame));
      List<PendingSpawn> out = normal.due(t, 100f);
      Check.eq("Frame " + frame + ": genau ein Treffer geht raus", 1, out.size());
      Check.eq("und zwar der eingereihte", frame, out.get(0).nodeId);
      t += FRAME;
    }
    Check.eq("nichts bleibt liegen", 0, normal.pendingCount());
    Check.eq("nichts wurde verworfen", 0, normal.droppedCount());

    // Der allererste Treffer ueberhaupt geht sofort raus, nicht erst nach
    // einem Intervall - sonst haette ein stiller Abend eine hoerbare
    // Anfangslatenz.
    HitNodeOscThrottle first = new HitNodeOscThrottle();
    first.enqueue(hit(7));
    List<PendingSpawn> firstOut = first.due(500.0, 100f);
    Check.eq("der erste Treffer geht im selben Frame raus", 1, firstOut.size());
    Check.eq("und es ist der richtige", 7, firstOut.get(0).nodeId);

    // Ein leerer Takt liefert nichts und wirft nicht
    HitNodeOscThrottle idle = new HitNodeOscThrottle();
    Check.eq("leere Schlange liefert nichts", 0, idle.due(10.0, 100f).size());
    Check.eq("und bleibt leer", 0, idle.pendingCount());

    // ---- Burst: alles kommt, nur ueber mehrere Frames verteilt ----
    //
    // 60 Treffer in einem einzigen Frame, bei 100 Hz Senderate. Genau der
    // Fall, der scsynth die Befehls-FIFO ueberlaufen liess: ohne Drossel
    // gingen alle 60 Datagramme in derselben Millisekunde raus.
    HitNodeOscThrottle burst = new HitNodeOscThrottle();
    final int BURST = 60;
    for (int i = 0; i < BURST; i++) {
      burst.enqueue(hit(i));
    }
    Check.eq("alle 60 stehen in der Schlange", BURST, burst.pendingCount());
    double bt = 2000.0;
    List<PendingSpawn> firstFrame = burst.due(bt, 100f);
    Check.that("der erste Frame sendet NICHT alles auf einmal",
        firstFrame.size() < BURST);
    Check.that("aber auch nicht nichts", firstFrame.size() > 0);

    int[] order = new int[BURST];
    int got = firstFrame.size();
    for (int i = 0; i < firstFrame.size(); i++) {
      order[i] = firstFrame.get(i).nodeId;
    }
    // Ueber die naechsten Frames muss der Rest herauskommen. 60 Treffer bei
    // 100 Hz brauchen 0,6 s, also rund 24 Frames - 200 sind reichlich Luft
    // und wuerden eine haengende Schlange trotzdem als Fehler zeigen.
    for (int frame = 0; frame < 200 && got < BURST; frame++) {
      bt += FRAME;
      List<PendingSpawn> out = burst.due(bt, 100f);
      for (int i = 0; i < out.size(); i++) {
        order[got++] = out.get(i).nodeId;
      }
    }
    Check.eq("am Ende sind alle 60 gesendet", BURST, got);
    Check.eq("und keiner blieb liegen", 0, burst.pendingCount());
    Check.eq("verworfen wurde nichts", 0, burst.droppedCount());
    boolean chronological = true;
    for (int i = 0; i < BURST; i++) {
      if (order[i] != i) {
        chronological = false;
      }
    }
    Check.that("die Reihenfolge bleibt chronologisch (FIFO)", chronological);

    // Die Streckung trifft die eingestellte Rate: 60 Treffer bei 100 Hz
    // brauchen mindestens 0,59 s (der erste geht sofort). Ohne diese Pruefung
    // waere auch eine Drossel gruen, die alles im zweiten Frame nachschiebt.
    HitNodeOscThrottle paced = new HitNodeOscThrottle();
    for (int i = 0; i < BURST; i++) {
      paced.enqueue(hit(i));
    }
    double pt = 3000.0;
    int frames = 0;
    while (paced.pendingCount() > 0 && frames < 500) {
      paced.due(pt, 100f);
      pt += FRAME;
      frames++;
    }
    double elapsed = pt - 3000.0;
    Check.that("60 Treffer bei 100 Hz dauern mindestens ~0,55 s, hier "
        + elapsed, elapsed >= 0.55);
    Check.that("und hoechstens ~0,8 s - die Drossel bremst nicht mehr als "
        + "noetig, hier " + elapsed, elapsed <= 0.8);

    // Eine hoehere Rate liefert denselben Burst schneller aus. Belegt, dass
    // der Regler ueberhaupt wirkt.
    HitNodeOscThrottle fast = new HitNodeOscThrottle();
    for (int i = 0; i < BURST; i++) {
      fast.enqueue(hit(i));
    }
    double ft = 4000.0;
    int fastFrames = 0;
    while (fast.pendingCount() > 0 && fastFrames < 500) {
      fast.due(ft, 200f);
      ft += FRAME;
      fastFrames++;
    }
    Check.that("bei 200 Hz ist derselbe Burst frueher durch",
        (ft - 4000.0) < elapsed);

    // Mehr als ein Treffer je Frame ist ausdruecklich moeglich: drawMe()
    // laeuft mit 40 Hz, rateHz steht darueber. Ginge je Frame nur einer raus,
    // waere die tatsaechliche Rate bei 40 Hz gedeckelt statt bei rateHz.
    HitNodeOscThrottle multi = new HitNodeOscThrottle();
    for (int i = 0; i < 20; i++) {
      multi.enqueue(hit(i));
    }
    multi.due(5000.0, 100f);                     // Startguthaben verbrauchen
    List<PendingSpawn> secondFrame = multi.due(5000.0 + FRAME, 100f);
    Check.that("bei 100 Hz gehen in einem 25-ms-Frame mehrere raus, hier "
        + secondFrame.size(), secondFrame.size() >= 2);

    // ---- Dauerueberlast: der aelteste faellt, die Schlange bleibt gedeckelt ----
    //
    // Nur hier wird ueberhaupt etwas verworfen, und dann der aelteste: ein
    // Treffer, dessen Licht der Zuschauer laengst gesehen hat, ist am
    // weitesten von "jetzt" entfernt.
    HitNodeOscThrottle flood = new HitNodeOscThrottle();
    int over = HitNodeOscThrottle.MAX_PENDING + 10;
    for (int i = 0; i < over; i++) {
      flood.enqueue(hit(i));
    }
    Check.eq("die Schlange bleibt gedeckelt",
        HitNodeOscThrottle.MAX_PENDING, flood.pendingCount());
    Check.eq("und zaehlt die verworfenen", 10, flood.droppedCount());
    // Neue Treffer werden weiter angenommen - die Drossel macht nicht dicht
    flood.enqueue(hit(9999));
    Check.eq("ein neuer Treffer wird trotzdem angenommen",
        HitNodeOscThrottle.MAX_PENDING, flood.pendingCount());
    Check.eq("und der Zaehler steigt", 11, flood.droppedCount());

    double dt = 6000.0;
    int drainedFirst = -1;
    int drainedLast = -1;
    int drainedCount = 0;
    boolean stillChronological = true;
    int prev = Integer.MIN_VALUE;
    for (int frame = 0; frame < 2000 && flood.pendingCount() > 0; frame++) {
      List<PendingSpawn> out = flood.due(dt, 100f);
      for (int i = 0; i < out.size(); i++) {
        int nr = out.get(i).nodeId;
        if (drainedFirst < 0) {
          drainedFirst = nr;
        }
        if (nr <= prev) {
          stillChronological = false;
        }
        prev = nr;
        drainedLast = nr;
        drainedCount++;
      }
      dt += FRAME;
    }
    Check.eq("es kommen genau so viele heraus, wie in der Schlange standen",
        HitNodeOscThrottle.MAX_PENDING, drainedCount);
    Check.eq("der aelteste ueberlebende ist der elfte eingereihte",
        11, drainedFirst);
    Check.eq("der zuletzt eingereihte ist noch da", 9999, drainedLast);
    Check.that("auch nach dem Verwerfen bleibt die Reihenfolge chronologisch",
        stillChronological);

    // ---- Entartete Rate: weder Schwall noch Stille ----
    //
    // rateHz <= 0 und NaN werden mit derselben Bedingung abgefangen wie in
    // ImpulseOscThrottle.due() - !(rateHz > 0f), weil jeder direkte Vergleich
    // mit NaN false ist und die Rate sonst durchrutschte.
    //
    // Die FOLGE ist hier aber bewusst eine andere als dort: bei /net/impulse
    // heisst Rate 0 "Strom aus", das ist der Not-Aus. Ein /net/hitNode-Strom
    // "aus" waere dagegen eine stumme Installation. Deshalb faellt die Drossel
    // auf NEUTRAL_RATE_HZ zurueck, den Auslieferungswert des Reglers.
    HitNodeOscThrottle nan = new HitNodeOscThrottle();
    for (int i = 0; i < BURST; i++) {
      nan.enqueue(hit(i));
    }
    List<PendingSpawn> nanFirst = nan.due(7000.0, Float.NaN);
    Check.that("NaN-Rate liefert nicht alles auf einmal",
        nanFirst.size() < BURST);
    Check.that("NaN-Rate liefert aber etwas - Stille waere schlimmer",
        nanFirst.size() > 0);
    double nt = 7000.0;
    int nanGot = nanFirst.size();
    for (int frame = 0; frame < 500 && nanGot < BURST; frame++) {
      nt += FRAME;
      nanGot += nan.due(nt, Float.NaN).size();
    }
    Check.eq("und am Ende sind alle da", BURST, nanGot);
    Check.eq("verworfen wurde nichts", 0, nan.droppedCount());

    HitNodeOscThrottle zero = new HitNodeOscThrottle();
    for (int i = 0; i < BURST; i++) {
      zero.enqueue(hit(i));
    }
    List<PendingSpawn> zeroFirst = zero.due(8000.0, 0f);
    Check.that("Rate 0 liefert nicht alles auf einmal", zeroFirst.size() < BURST);
    Check.that("Rate 0 verstummt aber auch nicht", zeroFirst.size() > 0);

    HitNodeOscThrottle negative = new HitNodeOscThrottle();
    negative.enqueue(hit(1));
    Check.eq("negative Rate sendet trotzdem", 1, negative.due(9000.0, -5f).size());

    // Der Rueckfall ist wirklich NEUTRAL_RATE_HZ und nicht "ungedrosselt":
    // derselbe Burst braucht mit NaN genauso lange wie mit der Neutralrate.
    HitNodeOscThrottle nanPace = new HitNodeOscThrottle();
    HitNodeOscThrottle refPace = new HitNodeOscThrottle();
    for (int i = 0; i < BURST; i++) {
      nanPace.enqueue(hit(i));
      refPace.enqueue(hit(i));
    }
    double npt = 100.0;
    int nanFrames = 0;
    while (nanPace.pendingCount() > 0 && nanFrames < 2000) {
      nanPace.due(npt + nanFrames*FRAME, Float.NaN);
      nanFrames++;
    }
    int refFrames = 0;
    while (refPace.pendingCount() > 0 && refFrames < 2000) {
      refPace.due(npt + refFrames*FRAME, HitNodeOscThrottle.NEUTRAL_RATE_HZ);
      refFrames++;
    }
    Check.eq("NaN braucht exakt so viele Frames wie die Neutralrate",
        refFrames, nanFrames);

    // ---- Wanduhr ----
    //
    // Kein Nachholen nach einer langen Pause: das angesparte Sendeguthaben ist
    // gedeckelt. Ohne diesen Deckel haette eine ruhige Minute ein Guthaben von
    // 6000 Nachrichten angesammelt, und der naechste Burst waere ungedrosselt
    // rausgegangen - genau der Fall, gegen den die Drossel gebaut ist.
    HitNodeOscThrottle pause = new HitNodeOscThrottle();
    pause.due(0.0, 100f);           // Uhr anlaufen lassen
    pause.due(60.0, 100f);          // eine Minute Stille
    for (int i = 0; i < BURST; i++) {
      pause.enqueue(hit(i));
    }
    List<PendingSpawn> afterPause = pause.due(60.0 + FRAME, 100f);
    Check.that("nach einer Minute Stille kommt kein Schwall, hier "
        + afterPause.size(), afterPause.size() < BURST);

    // Eine unbrauchbare Zeitbasis liefert nichts und verwirft nichts -
    // dieselbe Regel wie SplitStagger.due(NaN).
    HitNodeOscThrottle nanTime = new HitNodeOscThrottle();
    nanTime.enqueue(hit(1));
    Check.eq("NaN-Zeit liefert nichts", 0, nanTime.due(Double.NaN, 100f).size());
    Check.eq("und verwirft nichts", 1, nanTime.pendingCount());
    Check.eq("danach geht es normal weiter", 1, nanTime.due(1.0, 100f).size());

    // Ein Rueckwaertssprung der Wanduhr (Zeitumstellung, NTP) darf nichts
    // verlieren und kein Guthaben aufstauen.
    HitNodeOscThrottle backwards = new HitNodeOscThrottle();
    backwards.enqueue(hit(1));
    Check.eq("erster Treffer geht raus", 1, backwards.due(100.0, 100f).size());
    for (int i = 0; i < BURST; i++) {
      backwards.enqueue(hit(i));
    }
    List<PendingSpawn> jumped = backwards.due(50.0, 100f);
    Check.that("nach einem Ruecksprung kommt kein Schwall",
        jumped.size() < BURST);
    Check.that("es geht aber weiter", jumped.size() > 0);
    Check.eq("und nichts wurde verworfen", 0, backwards.droppedCount());

    // ---- Kleinkram ----
    HitNodeOscThrottle nulls = new HitNodeOscThrottle();
    nulls.enqueue(null);
    Check.eq("null wird nicht eingereiht", 0, nulls.pendingCount());
    Check.eq("und zaehlt nicht als verworfen", 0, nulls.droppedCount());

    HitNodeOscThrottle cleared = new HitNodeOscThrottle();
    cleared.enqueue(hit(1));
    cleared.enqueue(hit(2));
    cleared.clear();
    Check.eq("clear() leert die Schlange", 0, cleared.pendingCount());

    System.exit(Check.report("HitNodeOscThrottleTest"));
  }
}
