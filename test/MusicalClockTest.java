public class MusicalClockTest {

  public static void main(String[] args) throws Exception {
    // ---- Beat-Dauer ----
    Check.near("60 BPM ist ein Beat je Sekunde", 1.0, MusicalClock.beatDuration(60f), 1e-6);
    Check.near("120 BPM ist ein halber", 0.5, MusicalClock.beatDuration(120f), 1e-6);
    Check.near("20 BPM sind drei Sekunden", 3.0, MusicalClock.beatDuration(20f), 1e-6);

    // Entartete BPM duerfen keine Division durch 0 und kein NaN erzeugen -
    // das wuerde sich ueber beats() in jeden Track fortpflanzen
    Check.near("BPM 0 faellt auf den Vorgabewert zurueck",
        60.0/MusicalClock.DEFAULT_BPM, MusicalClock.beatDuration(0f), 1e-6);
    Check.near("negative BPM ebenfalls",
        60.0/MusicalClock.DEFAULT_BPM, MusicalClock.beatDuration(-30f), 1e-6);
    Check.near("NaN-BPM ebenfalls",
        60.0/MusicalClock.DEFAULT_BPM, MusicalClock.beatDuration(Float.NaN), 1e-6);

    // ---- Notenwerte in Beats ----
    Check.near("Ganze sind vier Beats", 4.0, MusicalClock.beatsPerNote(1), 1e-9);
    Check.near("Halbe sind zwei", 2.0, MusicalClock.beatsPerNote(2), 1e-9);
    Check.near("Viertel ist ein Beat", 1.0, MusicalClock.beatsPerNote(4), 1e-9);
    Check.near("Achtel ist ein halber", 0.5, MusicalClock.beatsPerNote(8), 1e-9);
    Check.near("Sechzehntel ein Viertel", 0.25, MusicalClock.beatsPerNote(16), 1e-9);
    // Notenwert 0 wuerde durch 0 teilen
    Check.near("Notenwert 0 gilt als Viertel", 1.0, MusicalClock.beatsPerNote(0), 1e-9);
    Check.near("negativer Notenwert gilt als Viertel", 1.0, MusicalClock.beatsPerNote(-4), 1e-9);

    // ---- Akkumulation ----
    MusicalClock c = new MusicalClock();
    Check.near("frisch angelegt steht die Uhr auf 0", 0.0, c.beats(), 1e-9);
    // Der erste advance() setzt nur den Nullpunkt und darf nicht springen:
    // die Zeitbasis ist System.currentTimeMillis()/1000, also gut 1.7e9 -
    // ohne diese Regel stuende die Uhr sofort bei 1.7 Milliarden Beats.
    c.advance(1000.0, 60f);
    Check.near("erster Aufruf setzt nur den Nullpunkt", 0.0, c.beats(), 1e-9);
    c.advance(1001.0, 60f);
    Check.near("nach einer Sekunde bei 60 BPM ein Beat", 1.0, c.beats(), 1e-6);
    c.advance(1003.0, 60f);
    Check.near("nach drei Sekunden drei Beats", 3.0, c.beats(), 1e-6);

    // ---- BPM-Wechsel aendert die Rate, nicht die Position ----
    // Der eigentliche Grund fuer die Akkumulation. Naiv gerechnet
    // ((now-t0)/beatDuration) wuerde ein Tempowechsel die Position
    // rueckwirkend umrechnen und alle Tracks schlagartig neu ausrichten.
    MusicalClock t = new MusicalClock();
    t.advance(0.0, 60f);
    t.advance(4.0, 60f);
    Check.near("vier Sekunden bei 60 BPM sind vier Beats", 4.0, t.beats(), 1e-6);
    double vorWechsel = t.beats();
    t.advance(4.0, 120f); // Tempowechsel ohne Zeitfortschritt
    Check.near("der Tempowechsel selbst verschiebt nichts", vorWechsel, t.beats(), 1e-9);
    t.advance(5.0, 120f);
    Check.near("danach laeuft es doppelt so schnell", 6.0, t.beats(), 1e-6);

    // ---- Zeit laeuft nie rueckwaerts ----
    MusicalClock b = new MusicalClock();
    b.advance(100.0, 60f);
    b.advance(102.0, 60f);
    double stand = b.beats();
    b.advance(101.0, 60f); // Uhr springt zurueck
    Check.that("ein Ruecksprung der Wanduhr zieht die Position nicht zurueck",
        b.beats() >= stand - 1e-9);

    // ---- Entartete BPM laufen weiter, statt die Uhr anzuhalten ----
    MusicalClock d = new MusicalClock();
    d.advance(0.0, Float.NaN);
    d.advance(1.0, Float.NaN);
    Check.near("NaN-BPM laeuft mit dem Vorgabetempo weiter", 1.0, d.beats(), 1e-6);
    Check.that("und liefert kein NaN", !Double.isNaN(d.beats()));

    System.exit(Check.report("MusicalClockTest"));
  }
}
