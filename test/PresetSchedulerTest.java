import java.util.ArrayList;
import java.util.List;

public class PresetSchedulerTest {

  static final float INTERVAL = 600f;          // Sekunden
  static final long INTERVAL_MS = 600L * 1000L;

  public static void main(String[] args) {
    List<String> names = list("ambient", "hang_drum_slow", "standby");

    // ---- Aus heisst kein Wechsel ----
    PresetScheduler off = new PresetScheduler();
    Check.that("aus: nicht faellig bei t=0", !off.isDue(0L, false, INTERVAL));
    Check.that("aus: nicht faellig weit nach dem Intervall",
        !off.isDue(INTERVAL_MS * 10, false, INTERVAL));

    // Ausgeschaltet zieht der Timer mit: ein spaeteres Einschalten darf nicht
    // sofort umschalten, weil waehrend der Aus-Zeit ein Intervall verstrich.
    PresetScheduler late = new PresetScheduler();
    Check.that("aus, spaeter Zeitpunkt", !late.isDue(INTERVAL_MS * 5, false, INTERVAL));
    Check.that("direkt nach dem Einschalten nicht faellig",
        !late.isDue(INTERVAL_MS * 5, true, INTERVAL));
    Check.that("kurz danach noch nicht faellig",
        !late.isDue(INTERVAL_MS * 5 + 1000L, true, INTERVAL));
    Check.that("erst nach einem vollen Intervall faellig",
        late.isDue(INTERVAL_MS * 6, true, INTERVAL));

    // ---- Einschalten springt nicht sofort ----
    PresetScheduler s = new PresetScheduler();
    Check.that("erster Aufruf mit an: nicht faellig", !s.isDue(1000L, true, INTERVAL));
    Check.that("kurz danach: nicht faellig", !s.isDue(2000L, true, INTERVAL));
    Check.that("genau nach dem Intervall: faellig",
        s.isDue(1000L + INTERVAL_MS, true, INTERVAL));

    // ---- Weiterschalten in alphabetischer Reihenfolge mit Umlauf ----
    PresetScheduler r = new PresetScheduler();
    Check.that("kein aktuelles Preset am Anfang", r.current() == null);
    Check.eq("erster Wechsel nimmt das erste Preset", "ambient",
        r.advance(0L, names));
    Check.eq("current wird mitgefuehrt", "ambient", r.current());
    Check.eq("zweiter Wechsel", "hang_drum_slow", r.advance(1000L, names));
    Check.eq("dritter Wechsel", "standby", r.advance(2000L, names));
    Check.eq("Umlauf zurueck auf den ersten", "ambient", r.advance(3000L, names));

    // advance setzt den Timer zurueck
    Check.that("nach advance nicht sofort wieder faellig",
        !r.isDue(3000L + 1000L, true, INTERVAL));
    Check.that("nach einem Intervall wieder faellig",
        r.isDue(3000L + INTERVAL_MS, true, INTERVAL));

    // ---- Position wird ueber den Namen gefuehrt ----
    PresetScheduler byName = new PresetScheduler();
    byName.noteLoaded("hang_drum_slow", 0L);
    // "aaa_neu" kommt alphabetisch VOR dem aktuellen Preset dazu. Wuerde die
    // Position ueber einen Index laufen, sprang der Scheduler jetzt zurueck.
    List<String> grown = list("aaa_neu", "ambient", "hang_drum_slow", "standby");
    Check.eq("neue Datei davor verschiebt die Position nicht", "standby",
        byName.advance(1000L, grown));

    PresetScheduler shrunk = new PresetScheduler();
    shrunk.noteLoaded("hang_drum_slow", 0L);
    // Das aktuelle Preset ist verschwunden: kein Absturz, wieder von vorn.
    Check.eq("verschwundenes Preset faengt wieder vorn an", "ambient",
        shrunk.advance(1000L, list("ambient", "standby")));

    // ---- Leere Liste ----
    PresetScheduler empty = new PresetScheduler();
    Check.that("leere Liste ergibt null", empty.advance(0L, new ArrayList<String>()) == null);
    Check.that("null-Liste ergibt null", empty.advance(0L, null) == null);
    // Auch bei leerer Liste muss der Timer zurueckgesetzt werden, sonst
    // meldet isDue in jedem Frame erneut "faellig".
    Check.that("Timer trotzdem zurueckgesetzt", !empty.isDue(1000L, true, INTERVAL));

    // ---- Defektes Preset haengt nicht fest ----
    // advance setzt current auf den zurueckgegebenen Namen, BEVOR der Aufrufer
    // zu laden versucht. Scheitert das Laden, geht der naechste Ablauf weiter.
    PresetScheduler broken = new PresetScheduler();
    Check.eq("erster Versuch", "ambient", broken.advance(0L, names));
    Check.eq("naechster Ablauf geht weiter, obwohl nie geladen wurde",
        "hang_drum_slow", broken.advance(INTERVAL_MS, names));

    // ---- noteLoaded setzt Position und Timer ----
    PresetScheduler noted = new PresetScheduler();
    noted.noteLoaded("standby", 5000L);
    Check.eq("noteLoaded setzt current", "standby", noted.current());
    Check.that("noteLoaded setzt den Timer",
        !noted.isDue(5000L + 1000L, true, INTERVAL));
    Check.that("und nach einem Intervall ist wieder faellig",
        noted.isDue(5000L + INTERVAL_MS, true, INTERVAL));

    // ---- kurzes Intervall ----
    PresetScheduler quick = new PresetScheduler();
    quick.noteLoaded("ambient", 0L);
    Check.that("5-Sekunden-Intervall: bei 4.9s nicht faellig",
        !quick.isDue(4900L, true, 5f));
    Check.that("5-Sekunden-Intervall: bei 5.0s faellig",
        quick.isDue(5000L, true, 5f));

    System.exit(Check.report("PresetSchedulerTest"));
  }

  static List<String> list(String... items) {
    List<String> result = new ArrayList<String>();
    for (int i = 0; i < items.length; i++) {
      result.add(items[i]);
    }
    return result;
  }
}
