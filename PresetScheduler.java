import java.util.List;

// Zeitlogik des Preset-Wechslers. Ohne Processing, ohne Thread und ohne
// eigene Wanduhr: die Zeit wird hineingegeben, damit die Klasse ohne
// Sketch-Laufzeit pruefbar ist. Gerufen wird sie aus draw().
//
// isDue() und advance() sind getrennt, weil draw() mit 40 Hz laeuft: wuerde
// der Scheduler die Namensliste selbst holen, listete er den Preset-Ordner
// 40-mal pro Sekunde auf.
class PresetScheduler {

  // Position wird ueber den NAMEN gefuehrt, nicht ueber einen Index. Kommt
  // eine Preset-Datei dazu oder faellt eine weg, verrutscht die Reihenfolge
  // sonst mitten in der Show.
  private String current = null;
  private long lastSwitchMillis = 0L;
  private boolean started = false;

  String current() {
    return current;
  }

  // Vom Start-Preset und von jedem erfolgreichen /preset/load zu rufen, damit
  // der Scheduler weiss, wo er steht und ab wann sein Intervall laeuft.
  void noteLoaded(String name, long nowMillis) {
    current = name;
    lastSwitchMillis = nowMillis;
    started = true;
  }

  // true, wenn jetzt gewechselt werden soll. Veraendert current nicht - der
  // Aufrufer holt danach die Namensliste und ruft advance().
  boolean isDue(long nowMillis, boolean enabled, float intervalSeconds) {
    if (!enabled) {
      // Timer mitziehen: sonst waere nach einer langen Aus-Phase beim
      // Einschalten sofort ein Intervall verstrichen und es wuerde mitten in
      // der laufenden Szene hart umgeschaltet.
      lastSwitchMillis = nowMillis;
      return false;
    }
    if (!started) {
      // Erstes Einschalten: Timer ab jetzt, kein Sprung.
      started = true;
      lastSwitchMillis = nowMillis;
      return false;
    }
    long intervalMillis = (long) (intervalSeconds * 1000f);
    return nowMillis - lastSwitchMillis >= intervalMillis;
  }

  // Schaltet auf das naechste Preset und gibt dessen Namen zurueck, oder null
  // wenn es keines gibt. Setzt den Timer in jedem Fall zurueck, auch bei
  // leerer Liste - sonst meldete isDue() in jedem Frame erneut "faellig".
  //
  // current wird gesetzt, BEVOR der Aufrufer zu laden versucht. Scheitert das
  // Laden, steht die Position auf dem defekten Eintrag und der naechste
  // Ablauf geht zum folgenden weiter. Der Scheduler haengt nicht fest.
  String advance(long nowMillis, List<String> names) {
    started = true;
    lastSwitchMillis = nowMillis;
    if (names == null || names.isEmpty()) {
      return null;
    }
    int index = (current == null) ? -1 : names.indexOf(current);
    current = names.get((index + 1) % names.size());
    return current;
  }
}
