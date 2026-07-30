# LED-Positionen und Vierkanal-Spatialisierung — Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jede LED bekommt eine 2D-Position in Metern, jedes klangauslösende Ereignis schickt seine Koordinaten über OSC, und SuperCollider spatialisiert daraus auf vier Lautsprecher.

**Architecture:** Ein processing-freier Kern (`LedAnchorStore` hält von Hand gesetzte Anker, `LedPositionMap` interpoliert daraus alle 18 000 Positionen, `ImpulseOscThrottle` drosselt den Positionsstrom) wird von einem Maus-Werkzeug im Sketch-Fenster befüllt und vom Transport-Effekt gelesen. Der Kern ist über `test/run.sh` vollständig prüfbar; nur die Zeichenarbeit in `imPulse.pde` und die Klangkette in SuperCollider sind Handarbeit. Ausgang ist Ambisonics 2D erster Ordnung mit den Kern-UGens `PanB2`/`DecodeB2`, ohne Fremdabhängigkeit.

**Tech Stack:** Java 8 (Processing 3, `core.jar`), oscP5, SuperCollider (sclang/scsynth, Kern-UGens), bash-Testtreiber `test/run.sh` mit der handgeschriebenen Prüfhilfe `test/Check.java`.

**Spec:** `docs/superpowers/specs/2026-07-30-led-positionen-spatialisierung-design.md` (Stand `20e7d10`)

---

## Global Constraints

Diese Vorgaben gelten für **jede** Aufgabe, auch wenn sie dort nicht wiederholt werden.

- **Branch:** `grabicz26`. Nicht auf `master` committen, keinen neuen Branch anlegen.
- **Der Branch bewegt sich unter dir.** Am 2026-07-30 sind während der Planung `1e49346` (Random Impulse Spawner), `cb91024` (calibration complete, 23 → 77 Kreuzungen) und ein Merge von `origin/grabicz26` dazugekommen. **Vor jeder Aufgabe `git pull --ff-only` versuchen und die zu ändernde Datei frisch lesen.** Zeilennummern in diesem Plan sind Anhaltspunkte, der zitierte Quelltext ist der Anker — ändert sich etwas, nach dem zitierten Text suchen statt auf die Zeilennummer zu vertrauen.
- **Quellen sind reines ASCII.** Alle `.java` und `.pde` in diesem Projekt enthalten kein einziges Nicht-ASCII-Zeichen; deutsche Kommentare werden transliteriert: `ue oe ae ss` statt `ü ö ä ß`. Also „uebernommen", „geloescht", „waehrend". **Markdown-Dateien** (`docs/`, `CLAUDE.md`, `README.md`) verwenden dagegen echte Umlaute. Diese Trennung ist bestehende Konvention, keine Geschmacksfrage.
- **Einrückung** in neuen `.java`-Dateien: 2 Leerzeichen, wie `NodeCrossingStore.java`, `NodeSelection.java`, `NodeCalibration.java`. `LedStripeNetworks.java` benutzt **Tabs** — beim Ändern dort die vorhandene Einrückung fortsetzen, nicht umformatieren.
- **Keine neuen Fremdabhängigkeiten.** Die vier neuen Klassen `LedAnchorStore`, `LedPositionMap`, `LedPositionCalibration`, `ImpulseOscThrottle` dürfen **nichts** aus `oscP5.*` oder `netP5.*` benutzen, und kein `runnableLedEffect` nennen (das Interface steht in `mixer.java` und zieht über `RemoteControlledFloatParameter` oscP5 herein). `LedColor` und `processing.core.PVector` sind **erlaubt** — `LedColor` steht in der `SOURCES`-Liste von `test/run.sh` und `core.jar` liegt beim Übersetzen auf dem Klassenpfad. `PApplet.constrain`/`map` trotzdem nicht benutzen, von Hand rechnen.
- **Für diese vier Klassen ist Datei = Klasse.** (Im übrigen Projekt gilt das nicht, siehe `LedStripeNetworks.java`.)
- **Grüne Prüfung vor jedem Commit:** `test/run.sh && test/build.sh` muss mit Status 0 enden. Beide, nicht nur eines. Siehe „Prüfung" unten und den Projekt-Skill `impulse-verify`.
- **Niemals die Hardware-Sonden starten.** `test/TimingProbe.java`, `test/PollProbe.java`, `test/PatternProbe.java` sprechen die echte Installation an. Nicht aufrufen, auch nicht „zum Prüfen".
- **`masterLevel` nicht anfassen.** Seit `82487e7` ist es der Show-Fader mit Bereich **0..1** und Auslieferungswert 0.1; die frühere Obergrenze 0.3 betraf das Hardware-Risiko der Kalibrier-Testbilder, die Vollweiss senden, und dafür gibt es jetzt den vom Fader unabhängigen Fixpegel `CALIBRATION_MASTER_LEVEL = 0.1f`. Beides ist eine Entscheidung des Betreibers über seine eigene Hardware — keine Aufgabe dieses Plans fasst es an, in keine Richtung. Siehe den Abschnitt „Master-Pegel" in `CLAUDE.md`.
- **Farbwerte sind 0..1** und werden erst am Ausgang geklemmt.

### Zahlen, wörtlich aus der Spec

| Grösse | Wert |
|---|---|
| Grundfläche | `footprintX = 14f`, `footprintY = 8f` (Meter) |
| Ursprung | Netzmitte, X nach rechts, Y nach vorn |
| Stripe-Länge | `stripeLengthM = 10f`, durchgehend (kein Sprung bei LED 299/300) |
| LED-Abstand | `ledPitchM = stripeLengthM / numLedsPerStripe` = 0,0166667 m |
| Halbe Diagonale | `maxRadiusM = 8.062` |
| Lautsprecher | (0, +4), (+7, 0), (0, −4), (−7, 0) — **Seitenmitten**, nicht Ecken |
| Warnschwelle Weglänge | `WARN_SLACK_M = 0.5f`, absolut |
| Schrittweiten Pfeiltasten | 0.01 / 0.05 / 0.25 m, Start bei 0.05 |
| Blinktakt | 400 ms an, 400 ms aus |
| `L`-Bestätigungsfenster | 300 ms bis 5000 ms |
| Glimmhelligkeit | 0.06 (wie `NodeCalibration.DIM`) |
| Draufsicht-Fläche | Rechteck (0, 0, 525, 300) px — 525:300 == 14:8, keine Verzerrung, 2,67 cm je Pixel |
| LED-Vorschau im Positionsmodus | `image(canvas, 560, 0, 600, 120)` |
| Fortschrittstext | ab y = 140 |
| HUD | ab y = 300 |
| `/net/impulse/oscRate` | float, Auslieferung 10, Bereich 0–40 Hz; **0 schaltet den Strom ab** |
| `/net/impulse/oscMaxCount` | int, Auslieferung 32, Bereich 0–256 |
| Drohnen-Timeout | 0,4 s, geprüft alle 0,1 s |
| Drohnen-Obergrenze in sclang | 32 |
| Arbeitslisten-Einträge | `2*numStripes + Anzahl Kreuzungen` — **gerechnet, nie verdrahtet** |

### Keine abgeleitete Zahl als Konstante

`data/nodeCrossings.txt` **wächst während der Kalibrierung**: am 2026-07-30 standen um 02:18 dreiundzwanzig Kreuzungen darin, um 03:40 siebenundsiebzig (Commit `cb91024`).

**Kein Test darf gegen `data/nodeCrossings.txt` prüfen und keine abgeleitete Zahl als Literal erwarten.** Ein Test, der 137 oder 214 erwartet, ist beim nächsten `S` im Kalibriermodus rot — ohne dass jemand einen Fehler gemacht hätte. Die Suiten arbeiten deshalb ausschliesslich mit **kleinen synthetischen Vorgaben**, die der Test selbst aufbaut: 4 Stripes à 20 LEDs.

Es gilt mit `S` Stripes und `C` Kreuzungen (Spec 2.3):

```
LED-Anker gesamt        = 2*S + 2*C     (jede Kreuzung hat genau zwei LEDs)
Arbeitslisten-Eintraege = 2*S +   C     (eine Kreuzung ist EIN Eintrag)
Klicks                  = 2*S +   C     (identisch - ein Eintrag, ein Klick)
```

### Ein Eintrag ist ein physischer Punkt

Eine Kreuzung ist **ein** Eintrag mit zwei oder mehr LEDs, ein Stripe-Ende ein Eintrag mit einer. Zählte ein Eintrag je LED, stünde jede Kreuzung zweimal in der Liste, man müsste denselben physischen Punkt zweimal ansteuern, und der zweite Besuch könnte nur „bereits bekannt" melden. So entfällt dieser Zustand ganz, jeder Eintrag braucht genau einen Klick, und `drawMe()` lässt **alle** LEDs des Eintrags blinken — an einer Kreuzung markieren zwei LEDs auf zwei Stripes denselben Punkt.

Sortiert wird nach dem **kleinsten LED-Index** des Eintrags.

### Prüfe die Artefakte, nicht die eingesammelte Sicht darauf

Bei der Umsetzung von Aufgabe 4 sind **zwei Fehler in diesem Plan** aufgefallen, beide vom selben Typ, und beide hätten grüne Tests ohne Aussagekraft ergeben. Sie stehen hier, weil derselbe Fehler in mehreren der folgenden Aufgaben lauert:

1. **Eine Prüfung muss das prüfen, was sie behauptet, nicht das, was leicht greifbar ist.** Der Plan liess „mehrfaches Speichern verdoppelt nichts" gegen `size()` nach dem Wiedereinlesen prüfen. `anchors` ist aber eine `TreeMap`, und `put()` auf denselben Schlüssel kollabiert Duplikate — ein `save()`, das anhängt statt zu ersetzen, wäre unsichtbar geblieben. Richtig ist die **Datenzeilenzahl der Datei**. Gleiches Muster bei „`load()` ersetzt": mit derselben Ankermenge geladen, kann ein fehlendes `anchors.clear()` nicht auffallen — es braucht eine **andere** Ankermenge.
2. **Eine Prüfung darf nicht an Prosa hängen.** Der Plan liess die geschriebene Datei als Ganzes auf Kommafreiheit prüfen, obwohl der Kopfkommentar deutsche Prosa mit Kommas enthält. Gemeint war die Locale-Falle, also das **Dezimaltrennzeichen in den Datenzeilen**.

Daumenregel für alle folgenden Aufgaben: **frage bei jeder Prüfung, welcher konkrete Regress sie rot machen würde.** Fällt dir keiner ein, prüft sie nichts. Bei Aufgabe 4 wurde das am Ende empirisch gemacht — den Regress herstellen, rot sehen, zurückbauen. Das ist die verlässlichste Form und bei den Aufgaben 5, 6, 8 und 13 jeweils in wenigen Minuten machbar.

### Prüfung: zwei Befehle, beide verbindlich

```bash
test/run.sh && test/build.sh
```

| Befehl | Prüft | Fängt |
|---|---|---|
| `test/run.sh` | die processing- und netzfreien Klassen samt Suiten | Logikfehler in `LedAnchorStore`, `LedPositionMap`, `LedPositionCalibration`, `ImpulseOscThrottle`, `LedStripeNetworks` |
| `test/build.sh` | den **kompletten** Sketch headless, `imPulse.pde` eingeschlossen | Übersetzungsfehler in `.pde` und in allem, was an oscP5 oder Syphon hängt |

`test/build.sh` ruft `processing.mode.java.Commander` aus `pde.jar` direkt auf, weil `processing-java` auf diesem Rechner nicht installiert ist. Der Sketch wird nur **gelesen** und **nicht gestartet**; alles Erzeugte landet unter `TMPDIR`. Es geht nichts ans Netz.

Gegenprobe, dass die Prüfung Zähne hat — mit einem fehlenden Semikolon in `imPulse.pde` meldet sie `Syntax error, maybe a missing semicolon?` und endet mit Status 1.

**Beide Befehle müssen vor jedem Commit mit Status 0 durchlaufen.** Eine Übersetzung nie behaupten, ohne `test/build.sh` gelaufen zu haben.

Ohne automatische Prüfung bleiben genau zwei Dinge:

| Bereich | Prüfung |
|---|---|
| Aussehen der Draufsicht-Fläche und der LED-Rückmeldung | Sketch in Processing 3 starten und ansehen |
| `supercollider/klangnetz_bells.scd` | sclang mit vier Ausgängen, Testton je Kanal (Aufgabe 16 Step 5) |

---

## Dateistruktur

**Neu**

| Datei | Verantwortung |
|---|---|
| `LedAnchorStore.java` | hält die von Hand gesetzten Anker (LED-Index → x, y in Metern), validiert, verteilt innerhalb eines Knotens, liest und schreibt `data/ledPositions.txt` |
| `LedPositionMap.java` | rechnet aus den Ankern die Position **jeder** LED: Interpolation dazwischen, Vektor-Fortsetzung ausserhalb, Klemmung auf die Grundfläche |
| `LedPositionCalibration.java` | Arbeitsliste, Navigation, Tastenbefehle, Umrechnung Fläche ↔ Meter, LED-Rückmeldung, HUD-Text |
| `ImpulseOscThrottle.java` | entscheidet, wann ein Sendetakt fällig ist und welche Impulse in den Strom kommen |
| `test/LedAnchorStoreTest.java` | Suite zu `LedAnchorStore` |
| `test/LedPositionMapTest.java` | Suite zu `LedPositionMap` |
| `test/LedPositionCalibrationTest.java` | Suite zu `LedPositionCalibration` |
| `test/ImpulseOscThrottleTest.java` | Suite zu `ImpulseOscThrottle` |
| `data/ledPositions.txt` | anfangs nur Kopfkommentar |
| `docs/positionen-anleitung.md` | Handlungsanleitung für die Aufnahme |

**Geändert**

| Datei | Änderung |
|---|---|
| `test/Check.java` | `near()` für Fliesskommavergleiche — fehlt bisher, obwohl der Projekt-Skill es schon beschreibt |
| `test/run.sh` | vier Klassen in `SOURCES`, vier Suiten in der Default-Liste |
| `LedStripeNetworks.java` | `posX`/`posY` an `LedNetworkNode`, `applyPositions()` |
| `test/ApplyCrossingsTest.java` | Prüfungen für `applyPositions()` |
| `LedNetworkTransportEffect.java` | `id` an `TravellingActivation`, x/y an `/net/hitNode`, Strom `/net/impulse`, zwei Parameter |
| `imPulse.pde` | Konstanten, `positionMode`, Maus, Zeichnen der Fläche, Verdrahtung, Startmeldung |
| `supercollider/klangnetz_bells.scd` | Ambisonic-Kette, `\impulseDrone`, Drohnenverwaltung |
| `CLAUDE.md` | Abschnitt Positionen und Spatialisierung, OSC-Liste, Tests, Konventionen |

### Warum `ImpulseOscThrottle` eine eigene Klasse ist

Die Spec listet in Abschnitt 9 genau zwei Dinge als „nicht durch die Suite gedeckt": die Ambisonic-Kette und die Zeichenarbeit. Die Drosselung des Impulsstroms steht **nicht** darauf — sie soll geprüft sein. `LedNetworkTransportEffect` hängt aber an `oscP5` und lässt sich damit nicht über `test/run.sh` übersetzen.

Deshalb wird die Entscheidung — *ist ein Sendetakt fällig* und *welche Impulse gewinnen* — in eine processing- und netzfreie Klasse gezogen. Das ist keine Erfindung dieses Plans, sondern genau das Muster, das der Projekt-Skill beschreibt: „Damit eine Klasse hier prüfbar bleibt, darf sie nichts aus `processing.*`, `oscP5.*` oder `netP5.*` benutzen. Das ist der Grund, weshalb die Logik in diesem Projekt konsequent aus den Effekten herausgezogen ist." Der Effekt behält nur das Zusammenbauen und Absenden der OSC-Nachricht.

---

## Modellempfehlung je Aufgabe

Der Nutzer hat um Sonnet-Agenten gebeten, wo sie ausreichen. Kriterium ist nicht Schwierigkeit, sondern **ob ein Fehlgriff automatisch auffällt**: wo der Testcode wörtlich im Plan steht und `test/run.sh` ihn prüft, ist das Ergebnis nachweisbar. Wo nur ein Mensch am Aufbau oder am Lautsprecher merkt, ob es stimmt, geht die Aufgabe an Opus.

| Aufgabe | Modell | Grund |
|---|---|---|
| 1 Prüfhilfe und Testtreiber | Sonnet | rein mechanisch |
| 2–4 `LedAnchorStore` | Sonnet | Testcode vollständig vorgegeben |
| 5–6 `LedPositionMap` | Sonnet | Testcode vollständig vorgegeben |
| 7 `applyPositions` | Sonnet | klein, geprüft |
| 8–10 `LedPositionCalibration` Logik | Sonnet | Testcode vollständig vorgegeben |
| 11 `drawMe()` Rückmeldung | Sonnet | Farbvorrang ist geprüft |
| 12 `imPulse.pde` verdrahten | **Opus** | Übersetzung ist geprüft, Fensterlayout und Zeichenarbeit nicht — dort steckt das Ermessen |
| 13 `ImpulseOscThrottle` | Sonnet | Testcode vollständig vorgegeben |
| 14–15 OSC im Effekt | **Opus** | Eingriff in die laufende Simulation; die Nebenwirkungen auf Filler, Knotenlogik und die neuen Ambient-Spawns fängt kein Übersetzer |
| 16 SuperCollider | **Opus** | keine automatische Prüfung, zwei zu messende Unbekannte |
| 17 Dokumentation | **Opus** | Fliesstext, Konventionen von `CLAUDE.md` |

Sonnet für 12 von 17 Aufgaben.

---

# Stufe 1 — Positionskern

Danach lauffähig: Positionen können gelesen, geschrieben und für alle LEDs berechnet werden. Noch kein Werkzeug, noch kein Klang.

### Task 1: Prüfhilfe für Fliesskomma und Testtreiber vorbereiten

`test/Check.java` kennt nur `eq(long)`, `eq(String)` und `that(boolean)`. Für Positionen in Metern braucht es einen Vergleich mit Toleranz. Der Projekt-Skill `impulse-verify` **beschreibt `Check.near` bereits** — im Code fehlt es. Diese Aufgabe bringt beides in Übereinstimmung.

Ausserdem werden die vier neuen Quelldateien in `test/run.sh` eingetragen. Die `[ -f ... ]`-Wächter dort machen das gefahrlos, solange die Dateien noch fehlen.

**Files:**
- Modify: `test/Check.java`
- Modify: `test/run.sh:15-19`

**Interfaces:**
- Consumes: nichts
- Produces: `static void Check.near(String what, double expected, double actual, double tol)` — zählt eine Prüfung, meldet Fehler wenn `Math.abs(expected - actual) > tol` oder `actual` NaN ist. Wird ab Aufgabe 2 in jeder neuen Suite benutzt.

- [ ] **Step 1: Prüfhilfe ergänzen**

In `test/Check.java`, nach der zweiten `eq`-Überladung und vor `that(...)` einfügen:

```java
  // Fliesskommavergleich mit Toleranz. Positionen sind Meter, verglichen wird
  // deshalb nie auf Gleichheit - schon die Interpolation rundet, und die
  // Positionsdatei haelt nur drei Dezimalstellen.
  //
  // NaN gilt ausdruecklich als Fehler: eine Division durch einen
  // Index-Abstand von 0 wuerde sonst still durchgehen.
  static void near(String what, double expected, double actual, double tol) {
    checks++;
    if (Double.isNaN(actual) || Math.abs(expected - actual) > tol) {
      failures++;
      if (failures <= 20) {
        System.out.println("  FEHLER " + what + ": erwartet " + expected
            + " +-" + tol + ", war " + actual);
      }
    }
  }
```

- [ ] **Step 2: Quelldateien in den Testtreiber eintragen**

In `test/run.sh` nach der Zeile mit `TestPatterns.java` die vier neuen Wächter anfügen:

```bash
[ -f LedAnchorStore.java ] && SOURCES="$SOURCES LedAnchorStore.java"
[ -f LedPositionMap.java ] && SOURCES="$SOURCES LedPositionMap.java"
[ -f LedPositionCalibration.java ] && SOURCES="$SOURCES LedPositionCalibration.java"
[ -f ImpulseOscThrottle.java ] && SOURCES="$SOURCES ImpulseOscThrottle.java"
```

Die Default-Suitenliste bleibt in dieser Aufgabe **unverändert** — jede neue Suite wird erst in der Aufgabe eingetragen, die sie anlegt. Sonst schlägt `run.sh` fehl, weil `java` eine Klasse startet, die es noch nicht gibt.

- [ ] **Step 3: Testtreiber laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle bestehenden Suiten bestehen, `Finished.`, beide Status 0. Die neuen Wächter greifen nicht, weil die Dateien fehlen — das ist der erwünschte Zustand.

- [ ] **Step 4: Commit**

```bash
git add test/Check.java test/run.sh
git commit -m "Pruefhilfe fuer Fliesskomma und Testtreiber fuer die Positionsklassen"
```

---

### Task 2: `LedAnchorStore` — Anker setzen, lesen, löschen

Der Kern des Stores ohne Datei-Ein- und Ausgabe und ohne die Weglängen-Warnung. Validiert Bereich und Grundfläche und verteilt eine gesetzte Position auf alle LEDs desselben Knotens.

**Files:**
- Create: `LedAnchorStore.java`
- Create: `test/LedAnchorStoreTest.java`
- Modify: `test/run.sh` (Default-Suitenliste)

**Interfaces:**
- Consumes: `Check.near` aus Aufgabe 1
- Produces:

```java
class LedAnchorStore {
  static final float WARN_SLACK_M = 0.5f;

  LedAnchorStore(int numStripes, int numLedsPerStripe,
                 float footprintX, float footprintY, float ledPitchM);

  boolean set(int ledIndex, float x, float y,
              java.util.List<java.util.TreeSet<Integer>> crossings);
  boolean remove(int ledIndex);
  void    clearAll();

  boolean has(int ledIndex);
  float   x(int ledIndex);          // 0f wenn !has(ledIndex)
  float   y(int ledIndex);          // 0f wenn !has(ledIndex)
  java.util.SortedSet<Integer> anchorsOnStripe(int stripeIndex);  // globale Indizes
  java.util.SortedMap<Integer, float[]> all();                    // Index -> {x, y}

  int     size();
  int     loadedCount();
  int     sessionCount();           // == size() - loadedCount()
  boolean wasLoaded(int ledIndex);
  String  lastMessage();
  boolean lastWasWarning();
}
```

Spätere Aufgaben verlassen sich genau auf diese Namen.

**Zur Unterscheidung geladen/neu:** anders als `NodeCrossingStore` ist das hier **keine Liste mit Grenzindex**, sondern eine Menge geladener Schlüssel. Ein `TreeMap` hat keine sinnvolle Einfügereihenfolge, und die Falle aus `NodeCrossingStore.removeAt()` — der verschobene `loadedCount` — wird damit strukturell unmöglich statt behandelt. `set()` auf einen geladenen Anker macht ihn zu einem Sitzungseintrag: er ist in dieser Sitzung bearbeitet worden.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Neue Datei `test/LedAnchorStoreTest.java`:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class LedAnchorStoreTest {

  // Kleine synthetische Geometrie. NICHT die echten 30 x 600 und NICHT
  // data/nodeCrossings.txt - die Kreuzungsdatei waechst waehrend der
  // Kalibrierung, ein Test dagegen waere beim naechsten S rot.
  static final int STRIPES = 4;
  static final int PER_STRIPE = 20;
  static final float FOOT_X = 14f;
  static final float FOOT_Y = 8f;
  // 20 LEDs auf 10 m. Grosszuegig, damit die Weglaengen-Warnung aus Aufgabe 3
  // in diesen Tests nie anspringt und nichts verschleiert.
  static final float PITCH = 0.5f;
  static final double TOL = 1e-4;

  static final List<TreeSet<Integer>> NO_CROSSINGS = new ArrayList<TreeSet<Integer>>();

  static LedAnchorStore store() {
    return new LedAnchorStore(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y, PITCH);
  }

  static List<TreeSet<Integer>> crossing(int a, int b) {
    List<TreeSet<Integer>> list = new ArrayList<TreeSet<Integer>>();
    TreeSet<Integer> pair = new TreeSet<Integer>();
    pair.add(Integer.valueOf(a));
    pair.add(Integer.valueOf(b));
    list.add(pair);
    return list;
  }

  public static void main(String[] args) throws Exception {
    // ---- Gueltiges Setzen ----
    LedAnchorStore s = store();
    Check.eq("leerer Store", 0, s.size());
    Check.that("nichts gesetzt", !s.has(0));
    Check.near("x einer ungesetzten LED ist 0", 0.0, s.x(0), TOL);
    Check.near("y einer ungesetzten LED ist 0", 0.0, s.y(0), TOL);

    Check.that("gueltiger Anker wird angenommen", s.set(5, -3.25f, 1.1f, NO_CROSSINGS));
    Check.eq("ein Anker", 1, s.size());
    Check.that("der Anker ist da", s.has(5));
    Check.near("x kam an", -3.25, s.x(5), TOL);
    Check.near("y kam an", 1.1, s.y(5), TOL);
    Check.that("keine Warnung bei einem einzelnen Anker", !s.lastWasWarning());
    Check.that("die Meldung sagt etwas", s.lastMessage().length() > 0);

    // ---- Ueberschreiben ----
    Check.that("derselbe Anker wird ueberschrieben", s.set(5, 2f, -2f, NO_CROSSINGS));
    Check.eq("weiterhin ein Anker", 1, s.size());
    Check.near("der neue x-Wert gilt", 2.0, s.x(5), TOL);
    Check.near("der neue y-Wert gilt", -2.0, s.y(5), TOL);

    // ---- Index ausserhalb des Bereichs ----
    LedAnchorStore r = store();
    Check.that("negativer Index wird abgewiesen", !r.set(-1, 0f, 0f, NO_CROSSINGS));
    Check.that("die Meldung nennt den Index",
        r.lastMessage().indexOf("Index") >= 0);
    Check.that("Index hinter dem Ende wird abgewiesen",
        !r.set(STRIPES * PER_STRIPE, 0f, 0f, NO_CROSSINGS));
    Check.eq("nichts davon wurde gespeichert", 0, r.size());
    Check.that("letzter gueltiger Index geht", r.set(STRIPES * PER_STRIPE - 1, 0f, 0f, NO_CROSSINGS));

    // ---- Position ausserhalb der Grundflaeche ----
    LedAnchorStore f = store();
    Check.that("zu weit rechts wird abgewiesen", !f.set(0, 7.01f, 0f, NO_CROSSINGS));
    Check.that("die Meldung nennt die Grundflaeche",
        f.lastMessage().indexOf("Grundflaeche") >= 0);
    Check.that("zu weit links wird abgewiesen", !f.set(0, -7.01f, 0f, NO_CROSSINGS));
    Check.that("zu weit vorn wird abgewiesen", !f.set(0, 0f, 4.01f, NO_CROSSINGS));
    Check.that("zu weit hinten wird abgewiesen", !f.set(0, 0f, -4.01f, NO_CROSSINGS));
    Check.eq("nichts davon wurde gespeichert", 0, f.size());

    // Genau auf dem Rand ist erlaubt, sonst waere die aeusserste Ecke der
    // Grundflaeche nicht setzbar
    Check.that("rechte vordere Ecke genau auf dem Rand", f.set(0, 7f, 4f, NO_CROSSINGS));
    Check.that("linke hintere Ecke genau auf dem Rand", f.set(1, -7f, -4f, NO_CROSSINGS));
    Check.eq("beide Randpunkte gespeichert", 2, f.size());

    // ---- Verteilung innerhalb eines Knotens ----
    // Kreuzung {10, 30}: LED 10 auf Stripe 0, LED 30 auf Stripe 1.
    List<TreeSet<Integer>> cross = crossing(10, PER_STRIPE + 10);
    LedAnchorStore n = store();
    Check.that("Kreuzungsanker gesetzt", n.set(10, 1.5f, -0.5f, cross));
    Check.eq("zwei LEDs wurden gesetzt", 2, n.size());
    Check.that("die Partner-LED ist da", n.has(PER_STRIPE + 10));
    Check.near("Partner hat dasselbe x", 1.5, n.x(PER_STRIPE + 10), TOL);
    Check.near("Partner hat dasselbe y", -0.5, n.y(PER_STRIPE + 10), TOL);
    Check.that("die Meldung nennt die Zahl der mitgesetzten LEDs",
        n.lastMessage().indexOf("2") >= 0);

    // Auch von der anderen Seite der Kreuzung aus
    LedAnchorStore n2 = store();
    Check.that("von der zweiten LED aus gesetzt",
        n2.set(PER_STRIPE + 10, -1f, 2f, cross));
    Check.eq("wieder zwei LEDs", 2, n2.size());
    Check.that("die erste LED ist mitgesetzt", n2.has(10));
    Check.near("und hat denselben Wert", -1.0, n2.x(10), TOL);

    // Eine LED, die in keiner Kreuzung steht, setzt genau eine
    LedAnchorStore n3 = store();
    Check.that("LED ohne Kreuzung gesetzt", n3.set(7, 0f, 0f, cross));
    Check.eq("genau eine LED", 1, n3.size());

    // ---- anchorsOnStripe ----
    LedAnchorStore a = store();
    a.set(14, 0f, 0f, NO_CROSSINGS);
    a.set(2, 0f, 0f, NO_CROSSINGS);
    a.set(9, 0f, 0f, NO_CROSSINGS);
    a.set(PER_STRIPE + 5, 0f, 0f, NO_CROSSINGS);
    Check.eq("drei Anker auf Stripe 0", 3, a.anchorsOnStripe(0).size());
    Check.eq("einer auf Stripe 1", 1, a.anchorsOnStripe(1).size());
    Check.eq("keiner auf Stripe 2", 0, a.anchorsOnStripe(2).size());
    Integer[] onZero = a.anchorsOnStripe(0).toArray(new Integer[0]);
    Check.eq("aufsteigend sortiert, erster", 2, onZero[0].intValue());
    Check.eq("aufsteigend sortiert, zweiter", 9, onZero[1].intValue());
    Check.eq("aufsteigend sortiert, dritter", 14, onZero[2].intValue());
    Check.eq("anchorsOnStripe liefert globale Indizes",
        PER_STRIPE + 5, a.anchorsOnStripe(1).first().intValue());
    Check.eq("Stripe ausserhalb liefert leer", 0, a.anchorsOnStripe(99).size());

    // ---- all() ----
    Check.eq("all() hat alle Anker", 4, a.all().size());
    Check.that("all() ist nach Index sortiert", a.all().firstKey().intValue() == 2);

    // ---- geladen und neu ----
    LedAnchorStore l = store();
    l.set(0, 1f, 1f, NO_CROSSINGS);
    l.set(1, 1f, 1f, NO_CROSSINGS);
    Check.eq("frisch gesetzte Anker sind nicht geladen", 0, l.loadedCount());
    Check.eq("alle sind Sitzungseintraege", 2, l.sessionCount());
    Check.that("wasLoaded ist false", !l.wasLoaded(0));

    // ---- Loeschen ----
    Check.that("Loeschen eines vorhandenen Ankers", l.remove(0));
    Check.eq("einer weg", 1, l.size());
    Check.that("er ist weg", !l.has(0));
    Check.that("Loeschen eines nicht vorhandenen tut nichts", !l.remove(0));
    Check.that("die Meldung sagt es", l.lastMessage().length() > 0);

    // Loeschen einer Kreuzungs-LED entfernt NUR diese, nicht den Partner -
    // das Zusammenfassen ist Sache des Werkzeugs, nicht des Stores
    LedAnchorStore d = store();
    d.set(10, 1f, 1f, cross);
    Check.eq("zwei gesetzt", 2, d.size());
    Check.that("eine geloescht", d.remove(10));
    Check.eq("nur eine weg", 1, d.size());
    Check.that("der Partner steht noch", d.has(PER_STRIPE + 10));

    // ---- clearAll ----
    LedAnchorStore c = store();
    c.set(0, 1f, 1f, NO_CROSSINGS);
    c.set(5, 1f, 1f, NO_CROSSINGS);
    c.clearAll();
    Check.eq("clearAll raeumt alles", 0, c.size());
    Check.eq("und setzt loadedCount zurueck", 0, c.loadedCount());
    Check.that("die Meldung nennt die Anzahl", c.lastMessage().length() > 0);
    Check.that("danach ist wieder Setzen moeglich", c.set(0, 1f, 1f, NO_CROSSINGS));

    System.exit(Check.report("LedAnchorStoreTest"));
  }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedAnchorStoreTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: class LedAnchorStore`.

- [ ] **Step 3: `LedAnchorStore.java` anlegen**

```java
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

// Haelt die von Hand gesetzten LED-Positionen: globaler LED-Index -> (x, y)
// in Metern, Ursprung senkrecht unter der Netzmitte.
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt - dasselbe Muster wie
// NodeCrossingStore, NodeSelection und ArtNetOutput.
//
// Der Schluessel ist der globale LED-Index, NICHT die Knoten-Nummer. Damit
// bleiben alle Positionen gueltig, wenn nodeCrossings.txt sich aendert: eine
// physische LED wandert nicht, wenn eine Kreuzung nachgetragen oder
// korrigiert wird. Bei Knoten-Nummern wuerde NodeCrossingStore.removeAt()
// alle folgenden Positionen verschieben.
class LedAnchorStore {

  // Um so viel darf die Luftlinie zwischen zwei Ankern die Weglaenge entlang
  // des Stripes ueberschreiten, bevor gewarnt wird. Absolut, nicht
  // prozentual: die Warnung soll den Fehlklick auf die falsche Netzseite
  // fangen, und ein Prozentwert waere bei kurzen Abstaenden unbrauchbar
  // streng.
  static final float WARN_SLACK_M = 0.5f;

  private final int numStripes;
  private final int numLedsPerStripe;
  private final float halfX;
  private final float halfY;
  private final float ledPitchM;

  private final TreeMap<Integer, float[]> anchors = new TreeMap<Integer, float[]>();
  // Menge statt Grenzindex: eine TreeMap hat keine sinnvolle
  // Einfuegereihenfolge, und die Falle aus NodeCrossingStore.removeAt() - der
  // verschobene loadedCount - wird so strukturell unmoeglich statt behandelt.
  private final Set<Integer> loadedKeys = new HashSet<Integer>();

  private String message = "";
  private boolean warned = false;

  LedAnchorStore(int numStripes, int numLedsPerStripe,
                 float footprintX, float footprintY, float ledPitchM) {
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.halfX = footprintX / 2f;
    this.halfY = footprintY / 2f;
    this.ledPitchM = ledPitchM;
  }

  int size() { return anchors.size(); }
  int loadedCount() { return loadedKeys.size(); }
  int sessionCount() { return anchors.size() - loadedKeys.size(); }
  String lastMessage() { return message; }
  boolean lastWasWarning() { return warned; }
  boolean wasLoaded(int ledIndex) { return loadedKeys.contains(Integer.valueOf(ledIndex)); }

  boolean has(int ledIndex) { return anchors.containsKey(Integer.valueOf(ledIndex)); }

  float x(int ledIndex) {
    float[] p = anchors.get(Integer.valueOf(ledIndex));
    return p == null ? 0f : p[0];
  }

  float y(int ledIndex) {
    float[] p = anchors.get(Integer.valueOf(ledIndex));
    return p == null ? 0f : p[1];
  }

  SortedMap<Integer, float[]> all() { return anchors; }

  // Globale Indizes der Anker dieses Stripes, aufsteigend. Weil der globale
  // Index innerhalb eines Stripes monoton mit dem Index im Stripe waechst,
  // ist die Sortierung dieselbe.
  SortedSet<Integer> anchorsOnStripe(int stripeIndex) {
    if (stripeIndex < 0 || stripeIndex >= numStripes) {
      return new TreeSet<Integer>();
    }
    int from = stripeIndex * numLedsPerStripe;
    int to = from + numLedsPerStripe;
    return new TreeSet<Integer>(
        anchors.subMap(Integer.valueOf(from), Integer.valueOf(to)).keySet());
  }

  private boolean inRange(int ledIndex) {
    return ledIndex >= 0 && ledIndex < numStripes * numLedsPerStripe;
  }

  // Setzt die Position. Liegt ledIndex in einer Kreuzung, gilt sie fuer ALLE
  // LEDs dieser Kreuzung - eine Kreuzung ist ein physischer Punkt, das ist
  // keine Schaetzung sondern eine Tatsache der Geometrie.
  //
  // Die Kreuzungsliste wird hereingegeben statt gehalten, damit der Store
  // ohne eigene Kenntnis der Topologie auskommt und Aenderungen an
  // nodeCrossings.txt zur Laufzeit mitbekommt.
  boolean set(int ledIndex, float x, float y, List<TreeSet<Integer>> crossings) {
    warned = false;
    if (!inRange(ledIndex)) {
      message = "LED-Index ausserhalb des Bereichs: " + ledIndex;
      return false;
    }
    if (x < -halfX || x > halfX || y < -halfY || y > halfY) {
      message = "Position ausserhalb der Grundflaeche (" + (2 * halfX) + " x "
          + (2 * halfY) + " m): " + x + " / " + y;
      return false;
    }

    TreeSet<Integer> touched = new TreeSet<Integer>();
    touched.add(Integer.valueOf(ledIndex));
    if (crossings != null) {
      for (TreeSet<Integer> cluster : crossings) {
        if (cluster.contains(Integer.valueOf(ledIndex))) {
          for (Integer idx : cluster) {
            if (inRange(idx.intValue())) {
              touched.add(idx);
            }
          }
        }
      }
    }

    for (Integer idx : touched) {
      anchors.put(idx, new float[] { x, y });
      // Ein bearbeiteter Anker ist ein Sitzungseintrag, auch wenn er aus der
      // Datei kam - er ist in dieser Sitzung angefasst worden.
      loadedKeys.remove(idx);
    }

    String warning = arcLengthWarning(touched);
    if (warning != null) {
      warned = true;
      message = warning;
    } else {
      message = "Anker gesetzt: " + touched.size() + " LED(s) auf "
          + fmt(x) + " / " + fmt(y) + " m";
    }
    return true;
  }

  // Die Luftlinie zwischen zwei Ankern desselben Stripes kann physikalisch
  // nie laenger sein als der Weg entlang des Stripes. Geprueft wird nur gegen
  // die unmittelbaren Nachbaranker, nicht gegen alle.
  //
  // Dies WARNT und lehnt nicht ab, anders als die Kreuzungsvalidierung in
  // NodeCrossingStore: die Regel haengt an zwei Annahmen - LED-Abstand und
  // durchgehender Strang. Stimmt eine davon vor Ort nicht, waere ein hartes
  // Nein ein Werkzeug, das sich mitten in der Arbeit selbst blockiert.
  private String arcLengthWarning(TreeSet<Integer> touched) {
    for (Integer idxObj : touched) {
      int idx = idxObj.intValue();
      SortedSet<Integer> onStripe = anchorsOnStripe(idx / numLedsPerStripe);
      SortedSet<Integer> below = onStripe.headSet(idxObj);
      SortedSet<Integer> above = onStripe.tailSet(Integer.valueOf(idx + 1));
      String w = null;
      if (!below.isEmpty()) {
        w = checkPair(below.last().intValue(), idx);
      }
      if (w == null && !above.isEmpty()) {
        w = checkPair(idx, above.first().intValue());
      }
      if (w != null) {
        return w;
      }
    }
    return null;
  }

  private String checkPair(int a, int b) {
    float dx = x(b) - x(a);
    float dy = y(b) - y(a);
    double straight = Math.sqrt(dx * dx + dy * dy);
    double along = Math.abs(b - a) * (double) ledPitchM;
    if (straight > along + WARN_SLACK_M) {
      return "Warnung: Luftlinie " + fmt((float) straight) + " m zwischen LED " + a
          + " und " + b + ", der Stripe gibt aber nur " + fmt((float) along)
          + " m her - falsche Netzseite angeklickt? Position ist trotzdem gesetzt.";
    }
    return null;
  }

  boolean remove(int ledIndex) {
    warned = false;
    Integer key = Integer.valueOf(ledIndex);
    if (!anchors.containsKey(key)) {
      message = "Kein Anker bei LED " + ledIndex;
      return false;
    }
    anchors.remove(key);
    loadedKeys.remove(key);
    message = "Anker bei LED " + ledIndex + " geloescht";
    return true;
  }

  // Verwirft alles, auch die geladenen Anker - fuer den Fall, dass eine
  // Aufnahme aus einer anderen Geometrie stammt.
  void clearAll() {
    warned = false;
    int n = anchors.size();
    anchors.clear();
    loadedKeys.clear();
    message = n + " Positionen verworfen (auch geladene)";
  }

  // Drei Dezimalstellen mit Punkt, unabhaengig von der Locale des Rechners -
  // String.format wuerde in einer deutschen Locale ein Komma schreiben und
  // die Datei damit unlesbar machen.
  private static String fmt(float v) {
    return String.format(java.util.Locale.US, "%.3f", Float.valueOf(v));
  }
}
```

- [ ] **Step 4: Suite in die Default-Liste des Testtreibers eintragen**

In `test/run.sh` in der `set --`-Zeile `LedAnchorStoreTest` anfügen.

- [ ] **Step 5: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 6: Commit**

```bash
git add LedAnchorStore.java test/LedAnchorStoreTest.java test/run.sh
git commit -m "LedAnchorStore: Anker setzen, lesen, loeschen, innerhalb eines Knotens verteilen"
```

---

### Task 3: `LedAnchorStore` — Weglängen-Warnung

Die Prüfung ist in Aufgabe 2 schon eingebaut. Diese Aufgabe **prüft sie ab**: der Testcode aus Aufgabe 2 kommt gar nicht in ihre Nähe, weil `PITCH = 0.5` dort grosszügig ist.

**Files:**
- Modify: `test/LedAnchorStoreTest.java`
- Modify: `LedAnchorStore.java` (nur falls die Tests einen Fehler aufdecken)

**Interfaces:**
- Consumes: `set`, `lastWasWarning`, `lastMessage`, `anchorsOnStripe` aus Aufgabe 2
- Produces: keine neuen Methoden. `set()` gibt bei Überschreitung weiterhin `true` zurück, `lastWasWarning()` wird `true`, und `lastMessage()` nennt Luftlinie und Weglänge. Aufgabe 10 liest `lastWasWarning()`, um zusätzlich auf die Konsole zu schreiben.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/LedAnchorStoreTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- Weglaengen-Warnung ----
    // Zwei Anker auf Stripe 0 mit Index-Abstand 4. Bei PITCH 0.5 ist die
    // Weglaenge 2,0 m, die Warnschwelle also 2,5 m Luftlinie.

    // Knapp darunter: keine Warnung
    LedAnchorStore w1 = store();
    Check.that("erster Anker", w1.set(0, 0f, 0f, NO_CROSSINGS));
    Check.that("zweiter Anker bei 2,4 m", w1.set(4, 2.4f, 0f, NO_CROSSINGS));
    Check.that("2,4 m liegen unter der Schwelle von 2,5", !w1.lastWasWarning());
    Check.that("die Position ist gesetzt", w1.has(4));

    // Knapp darueber: Warnung, aber gesetzt
    LedAnchorStore w2 = store();
    Check.that("erster Anker", w2.set(0, 0f, 0f, NO_CROSSINGS));
    Check.that("zweiter Anker bei 2,6 m wird trotzdem angenommen",
        w2.set(4, 2.6f, 0f, NO_CROSSINGS));
    Check.that("2,6 m loesen die Warnung aus", w2.lastWasWarning());
    Check.that("die Meldung nennt die Luftlinie",
        w2.lastMessage().indexOf("Luftlinie") >= 0);
    Check.that("die Position ist trotz Warnung gesetzt", w2.has(4));
    Check.near("und zwar mit dem angegebenen Wert", 2.6, w2.x(4), TOL);

    // Genau auf der Schwelle: keine Warnung (verglichen wird mit >)
    LedAnchorStore w3 = store();
    w3.set(0, 0f, 0f, NO_CROSSINGS);
    Check.that("genau 2,5 m", w3.set(4, 2.5f, 0f, NO_CROSSINGS));
    Check.that("genau auf der Schwelle warnt nicht", !w3.lastWasWarning());

    // Diagonal gerechnet, nicht nur in x
    LedAnchorStore w4 = store();
    w4.set(0, 0f, 0f, NO_CROSSINGS);
    // 3-4-5-Dreieck: Luftlinie 5 m, Weglaenge 2 m -> Warnung
    Check.that("diagonal weit entfernt wird angenommen", w4.set(4, 3f, 4f, NO_CROSSINGS));
    Check.that("und warnt", w4.lastWasWarning());

    // Anker auf verschiedenen Stripes haben keine Weglaengen-Beziehung
    LedAnchorStore w5 = store();
    w5.set(0, -7f, -4f, NO_CROSSINGS);
    Check.that("Anker auf einem anderen Stripe", w5.set(PER_STRIPE, 7f, 4f, NO_CROSSINGS));
    Check.that("ueber Stripe-Grenzen wird nicht gewarnt", !w5.lastWasWarning());

    // Ein einzelner Anker auf dem Stripe hat keinen Nachbarn
    LedAnchorStore w6 = store();
    Check.that("einzelner Anker", w6.set(10, 7f, 4f, NO_CROSSINGS));
    Check.that("ohne Nachbarn keine Warnung", !w6.lastWasWarning());

    // Nur die UNMITTELBAREN Nachbarn werden geprueft: 0 -> 4 -> 8 ist je
    // Schritt zulaessig, 0 -> 8 waere es nicht (4,8 m gegen 4,0 + 0,5).
    LedAnchorStore w7 = store();
    w7.set(0, 0f, 0f, NO_CROSSINGS);
    w7.set(4, 2.4f, 0f, NO_CROSSINGS);
    Check.that("dritter Anker", w7.set(8, 4.8f, 0f, NO_CROSSINGS));
    Check.that("nur die unmittelbaren Nachbarn zaehlen", !w7.lastWasWarning());

    // Ein sauberes set() danach loescht die Warnung wieder
    LedAnchorStore w8 = store();
    w8.set(0, 0f, 0f, NO_CROSSINGS);
    w8.set(4, 3f, 4f, NO_CROSSINGS);
    Check.that("Warnung steht", w8.lastWasWarning());
    Check.that("sauberer Anker auf einem anderen Stripe",
        w8.set(2 * PER_STRIPE, 0f, 0f, NO_CROSSINGS));
    Check.that("die Warnung ist zurueckgesetzt", !w8.lastWasWarning());

    // Auch eine Ablehnung setzt die Warnung zurueck
    LedAnchorStore w9 = store();
    w9.set(0, 0f, 0f, NO_CROSSINGS);
    w9.set(4, 3f, 4f, NO_CROSSINGS);
    Check.that("Warnung steht", w9.lastWasWarning());
    Check.that("abgelehnt wegen Grundflaeche", !w9.set(6, 99f, 0f, NO_CROSSINGS));
    Check.that("und die Warnung ist weg", !w9.lastWasWarning());
```

- [ ] **Step 2: Tests laufen lassen**

Run: `test/run.sh LedAnchorStoreTest`
Expected: alle Prüfungen bestanden. Die Warnlogik ist in Aufgabe 2 mitgekommen — **wenn hier etwas rot ist, ist es ein echter Fehler in `arcLengthWarning`/`checkPair` und wird dort behoben**, nicht durch Anpassen des Tests.

Häufigster Fehler an dieser Stelle: `arcLengthWarning` läuft, **bevor** die neuen Anker in `anchors` stehen — dann findet `anchorsOnStripe` den neuen Anker nicht und es wird nie gewarnt. Die Reihenfolge in `set()` ist: erst `anchors.put(...)` für alle berührten LEDs, dann prüfen.

- [ ] **Step 3: Gegenprobe, dass die Prüfung Zähne hat**

`WARN_SLACK_M` versuchsweise auf `500f` setzen, `test/run.sh LedAnchorStoreTest` laufen lassen. Erwartung: die Prüfungen „2,6 m loesen die Warnung aus" und „und warnt" schlagen fehl. Danach den Wert **zurück auf `0.5f`** setzen und die Suite erneut grün sehen.

- [ ] **Step 4: Vollständige Prüfung**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Commit**

```bash
git add LedAnchorStore.java test/LedAnchorStoreTest.java
git commit -m "LedAnchorStore: Weglaengen-Warnung abgeprueft"
```

---

### Task 4: `LedAnchorStore` — Datei lesen und schreiben

Atomar über Temp-Datei und Rename, wie `NodeCrossingStore.save()`. Kaputte Zeilen werden gemeldet und übersprungen, nicht als Absturz beim nächsten Start weitergereicht.

**Files:**
- Modify: `LedAnchorStore.java`
- Modify: `test/LedAnchorStoreTest.java`

**Interfaces:**
- Consumes: alles aus Aufgabe 2 und 3
- Produces:

```java
  void load(String path);
  void save(String path) throws java.io.IOException;
```

Format: eine Zeile `ledIndex x y`, Felder durch Leerraum getrennt, `#` beginnt einen Kommentar. Geschrieben werden x und y mit drei Dezimalstellen und **Punkt** als Trennzeichen. Nach `load()` gelten alle gelesenen Anker als geladen (`wasLoaded` true), nach `save()` bleibt `loadedCount()` unverändert.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/LedAnchorStoreTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- Datei-Rundlauf ----
    java.io.File dir = java.nio.file.Files.createTempDirectory("ledpos").toFile();
    java.io.File file = new java.io.File(dir, "ledPositions.txt");

    LedAnchorStore wr = store();
    wr.set(5, -3.25f, 1.1f, NO_CROSSINGS);
    wr.set(PER_STRIPE + 3, 2.9f, -0.45f, NO_CROSSINGS);
    wr.set(2 * PER_STRIPE, 0f, 0f, NO_CROSSINGS);
    Check.eq("drei Anker vor dem Schreiben", 3, wr.size());
    wr.save(file.getAbsolutePath());
    Check.that("die Datei existiert", file.exists());
    Check.eq("save() laesst loadedCount unveraendert", 0, wr.loadedCount());
    Check.eq("und sessionCount auch", 3, wr.sessionCount());

    LedAnchorStore rd = store();
    rd.load(file.getAbsolutePath());
    Check.eq("drei Anker geladen", 3, rd.size());
    Check.near("x kam zurueck", -3.25, rd.x(5), 1e-3);
    Check.near("y kam zurueck", 1.1, rd.y(5), 1e-3);
    Check.near("negatives y kam zurueck", -0.45, rd.y(PER_STRIPE + 3), 1e-3);
    Check.eq("alle gelten als geladen", 3, rd.loadedCount());
    Check.eq("keine Sitzungseintraege", 0, rd.sessionCount());
    Check.that("wasLoaded ist true", rd.wasLoaded(5));

    // Ein geladener Anker, der ueberschrieben wird, ist danach ein
    // Sitzungseintrag
    Check.that("geladenen Anker ueberschreiben", rd.set(5, 1f, 1f, NO_CROSSINGS));
    Check.eq("einer weniger geladen", 2, rd.loadedCount());
    Check.eq("einer mehr in der Sitzung", 1, rd.sessionCount());

    // Loeschen eines geladenen Ankers zieht loadedCount mit
    LedAnchorStore rd2 = store();
    rd2.load(file.getAbsolutePath());
    Check.eq("drei geladen", 3, rd2.loadedCount());
    Check.that("einen geladenen loeschen", rd2.remove(5));
    Check.eq("loadedCount sinkt mit", 2, rd2.loadedCount());
    Check.eq("sessionCount bleibt bei null", 0, rd2.sessionCount());

    // ---- Punkt als Dezimaltrennzeichen, unabhaengig von der Locale ----
    String written = new String(
        java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    Check.that("die Datei enthaelt kein Komma", written.indexOf(',') < 0);
    Check.that("die Datei enthaelt einen Punkt", written.indexOf('.') >= 0);

    // ---- Mehrfaches Speichern verdoppelt nichts ----
    wr.save(file.getAbsolutePath());
    wr.save(file.getAbsolutePath());
    LedAnchorStore rd3 = store();
    rd3.load(file.getAbsolutePath());
    Check.eq("nach dreimal speichern immer noch drei Anker", 3, rd3.size());

    // ---- Fehlende Datei ----
    LedAnchorStore missing = store();
    missing.load(new java.io.File(dir, "gibtsnicht.txt").getAbsolutePath());
    Check.eq("fehlende Datei ergibt eine leere Liste", 0, missing.size());
    Check.that("und eine Meldung", missing.lastMessage().length() > 0);

    // ---- Kaputte Zeilen werden uebersprungen, gute geladen ----
    java.io.File bad = new java.io.File(dir, "kaputt.txt");
    java.io.PrintWriter pw = new java.io.PrintWriter(bad, "UTF-8");
    pw.println("# Kommentarzeile, wird ignoriert");
    pw.println("");
    pw.println("   ");
    pw.println("3 1.500 -2.250");          // gueltig
    pw.println("nichts 1.0 2.0");          // Index keine Zahl
    pw.println("4 keinezahl 2.0");          // x keine Zahl
    pw.println("5 1.0");                    // zu wenig Felder
    pw.println("6 1.0 2.0 3.0");            // zu viele Felder
    pw.println("-1 1.0 2.0");               // Index ausserhalb
    pw.println((STRIPES * PER_STRIPE) + " 1.0 2.0");  // Index ausserhalb
    pw.println("7 99.0 2.0");               // Position ausserhalb der Grundflaeche
    pw.println("8 -6.000 3.000");           // gueltig
    pw.close();

    LedAnchorStore badStore = store();
    badStore.load(bad.getAbsolutePath());
    Check.eq("nur die zwei gueltigen Zeilen wurden geladen", 2, badStore.size());
    Check.that("die erste gueltige Zeile", badStore.has(3));
    Check.near("mit ihrem x", 1.5, badStore.x(3), 1e-3);
    Check.near("mit ihrem y", -2.25, badStore.y(3), 1e-3);
    Check.that("die zweite gueltige Zeile", badStore.has(8));
    Check.that("die kaputten nicht", !badStore.has(5) && !badStore.has(6));
    Check.that("und die ausserhalb der Grundflaeche auch nicht", !badStore.has(7));
    Check.that("die Meldung nennt die uebersprungenen Zeilen",
        badStore.lastMessage().length() > 0);

    // ---- load() ersetzt, haengt nicht an ----
    LedAnchorStore twice = store();
    twice.load(file.getAbsolutePath());
    int firstLoad = twice.size();
    twice.load(file.getAbsolutePath());
    Check.eq("zweites load() ersetzt statt anzuhaengen", firstLoad, twice.size());
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedAnchorStoreTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: method load(String)`.

- [ ] **Step 3: `load()` und `save()` einbauen**

Am Ende von `LedAnchorStore.java`, vor `fmt`:

```java
  // Liest die Datei. Fehlerhafte Zeilen werden gemeldet und uebersprungen,
  // nicht als Absturz beim naechsten Start weitergereicht - dasselbe
  // Verhalten wie NodeCrossingStore.load().
  //
  // Ersetzt den Inhalt, haengt nicht an.
  void load(String path) {
    warned = false;
    anchors.clear();
    loadedKeys.clear();
    File f = new File(path);
    if (!f.exists()) {
      message = "Keine Datei " + path + ", starte ohne Positionen";
      return;
    }
    int lineNo = 0;
    int skipped = 0;
    java.io.BufferedReader reader = null;
    try {
      reader = new java.io.BufferedReader(new java.io.FileReader(f));
      String line = reader.readLine();
      while (line != null) {
        lineNo++;
        int hash = line.indexOf('#');
        String body = (hash >= 0 ? line.substring(0, hash) : line).trim();
        if (body.length() > 0) {
          if (!parseLine(body, lineNo)) {
            skipped++;
          }
        }
        line = reader.readLine();
      }
    } catch (java.io.IOException e) {
      System.out.println("Positionsdatei konnte nicht gelesen werden: " + e);
    } finally {
      if (reader != null) {
        try { reader.close(); } catch (java.io.IOException e) { }
      }
    }
    loadedKeys.addAll(anchors.keySet());
    message = anchors.size() + " Positionen geladen"
        + (skipped > 0 ? ", " + skipped + " Zeilen uebersprungen" : "");
  }

  private boolean parseLine(String body, int lineNo) {
    String[] parts = body.split("\\s+");
    if (parts.length != 3) {
      System.out.println("Zeile " + lineNo + " uebersprungen: " + parts.length
          + " Felder statt 3 (erwartet: ledIndex x y)");
      return false;
    }
    int idx;
    float px;
    float py;
    try {
      idx = Integer.parseInt(parts[0]);
      px = Float.parseFloat(parts[1]);
      py = Float.parseFloat(parts[2]);
    } catch (NumberFormatException e) {
      System.out.println("Zeile " + lineNo + " uebersprungen: keine Zahl in \""
          + body + "\"");
      return false;
    }
    if (!inRange(idx)) {
      System.out.println("Zeile " + lineNo + " uebersprungen: LED-Index " + idx
          + " liegt ausserhalb von 0.." + (numStripes * numLedsPerStripe - 1));
      return false;
    }
    if (px < -halfX || px > halfX || py < -halfY || py > halfY) {
      System.out.println("Zeile " + lineNo + " uebersprungen: Position " + px + " / "
          + py + " liegt ausserhalb der Grundflaeche");
      return false;
    }
    anchors.put(Integer.valueOf(idx), new float[] { px, py });
    return true;
  }

  // Schreibt die vollstaendige Liste in eine Nebendatei und benennt sie
  // anschliessend um. Damit gibt es weder doppelte Eintraege durch Anhaengen
  // noch einen halb geschriebenen Stand bei Absturz.
  //
  // loadedCount bleibt unberuehrt: Speichern soll die Unterscheidung
  // geladen/neu nicht verwischen, damit man nach dem Speichern weiter
  // erkennt, was in dieser Sitzung dazugekommen ist.
  void save(String path) throws java.io.IOException {
    File target = new File(path);
    File tmp = new File(path + ".tmp");
    java.io.PrintWriter writer = new java.io.PrintWriter(tmp, "UTF-8");
    try {
      writer.println("# LED-Positionen in der Draufsicht. Eine Zeile je Anker:");
      writer.println("#   ledIndex  x[m]  y[m]");
      writer.println("# Grundflaeche " + (2 * halfX) + " x " + (2 * halfY)
          + " m, Ursprung senkrecht unter der Netzmitte,");
      writer.println("# X nach rechts, Y nach vorn. Siehe docs/positionen-anleitung.md");
      for (java.util.Map.Entry<Integer, float[]> e : anchors.entrySet()) {
        writer.println(e.getKey() + " " + fmt(e.getValue()[0]) + " " + fmt(e.getValue()[1]));
      }
    } finally {
      writer.close();
    }
    java.nio.file.Files.move(tmp.toPath(), target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    message = anchors.size() + " Positionen nach " + target.getName() + " geschrieben";
  }
```

Die Locale-Falle in `fmt` ist echt: ohne `Locale.US` schreibt `String.format("%.3f", …)` auf einem deutsch eingestellten Rechner `-3,250`, und `Float.parseFloat` liest das beim nächsten Start nicht mehr. Der Test „die Datei enthaelt kein Komma" hält das fest.

- [ ] **Step 4: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Commit**

```bash
git add LedAnchorStore.java test/LedAnchorStoreTest.java
git commit -m "LedAnchorStore: Positionsdatei lesen und atomar schreiben"
```

---
### Task 5: `LedPositionMap` — Position jeder LED aus den Ankern

Die eigentliche Rechnung: Interpolation zwischen zwei Ankern, Fortsetzung des Vektors ausserhalb, Klemmung auf die Grundfläche. Alle drei Fälle laufen über **dieselbe** Zeile — nur der Faktor `t` liegt einmal zwischen 0 und 1 und einmal ausserhalb.

**Files:**
- Create: `LedPositionMap.java`
- Create: `test/LedPositionMapTest.java`
- Modify: `test/run.sh:27` (Default-Suitenliste)

**Interfaces:**
- Consumes: `LedAnchorStore.set`, `has`, `x`, `y`, `anchorsOnStripe` aus Aufgabe 2; `Check.near` aus Aufgabe 1
- Produces:

```java
class LedPositionMap {
  LedPositionMap(int numStripes, int numLedsPerStripe,
                 float footprintX, float footprintY);

  // true, wenn der Stripe der LED mindestens einen Anker hat. Bei false
  // bleibt out2 unberuehrt.
  boolean positionOf(LedAnchorStore store, int ledIndex, float[] out2);

  // true nur, wenn es auf demselben Stripe einen Anker bei oder unter UND
  // einen bei oder ueber dieser LED gibt.
  boolean isInterpolatedAt(LedAnchorStore store, int ledIndex);
}
```

Aufgabe 6 baut darauf `apply()` und die Arrays, Aufgabe 8 benutzt `positionOf` für den Vorschlag im Werkzeug.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Neue Datei `test/LedPositionMapTest.java`:

```java
import java.util.ArrayList;
import java.util.TreeSet;

public class LedPositionMapTest {

  // Kleine synthetische Geometrie. NICHT die echten 30 x 600 und NICHT
  // data/nodeCrossings.txt - die Kreuzungsdatei waechst waehrend der
  // Kalibrierung, ein Test dagegen waere beim naechsten S rot.
  static final int STRIPES = 4;
  static final int PER_STRIPE = 20;
  static final float FOOT_X = 14f;
  static final float FOOT_Y = 8f;
  // 20 LEDs auf 10 m: grosszuegiger Abstand, damit die Weglaengen-Warnung
  // aus Aufgabe 3 in diesen Tests nie anspringt und nichts verschleiert.
  static final float PITCH = 0.5f;
  static final double TOL = 1e-4;

  static final ArrayList<TreeSet<Integer>> NO_CROSSINGS = new ArrayList<TreeSet<Integer>>();

  static LedAnchorStore store() {
    return new LedAnchorStore(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y, PITCH);
  }

  static LedPositionMap map() {
    return new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
  }

  public static void main(String[] args) throws Exception {
    float[] out = new float[2];

    // ---- Stripe 0: zwei Anker, LED 4 -> (-3,-1) und LED 14 -> (2,1) ----
    // Richtung je Index: (0.5, 0.2)
    LedAnchorStore s = store();
    LedPositionMap m = map();
    Check.that("Anker bei LED 4 gesetzt", s.set(4, -3f, -1f, NO_CROSSINGS));
    Check.that("Anker bei LED 14 gesetzt", s.set(14, 2f, 1f, NO_CROSSINGS));

    // Exakt auf einem Anker
    Check.that("Anker selbst ist definiert", m.positionOf(s, 4, out));
    Check.near("Anker x", -3.0, out[0], TOL);
    Check.near("Anker y", -1.0, out[1], TOL);
    Check.that("ein Anker gilt als interpoliert", m.isInterpolatedAt(s, 4));

    // Genau in der Mitte
    Check.that("Mitte ist definiert", m.positionOf(s, 9, out));
    Check.near("Mitte x liegt auf der Haelfte", -0.5, out[0], TOL);
    Check.near("Mitte y liegt auf der Haelfte", 0.0, out[1], TOL);
    Check.that("die Mitte ist interpoliert", m.isInterpolatedAt(s, 9));

    // Proportional zum Index, nicht zur Reihenfolge der Anker
    Check.that("LED 6 ist definiert", m.positionOf(s, 6, out));
    Check.near("LED 6 x nach Indexanteil 0.2", -2.0, out[0], TOL);
    Check.near("LED 6 y nach Indexanteil 0.2", -0.6, out[1], TOL);

    // Fortsetzung des Vektors hinter dem letzten Anker
    Check.that("LED 19 ist definiert", m.positionOf(s, 19, out));
    Check.near("hinter dem letzten Anker x", 4.5, out[0], TOL);
    Check.near("hinter dem letzten Anker y", 2.0, out[1], TOL);
    Check.that("hinter dem letzten Anker gilt nicht als interpoliert",
        !m.isInterpolatedAt(s, 19));

    // Fortsetzung des Vektors vor dem ersten Anker
    Check.that("LED 0 ist definiert", m.positionOf(s, 0, out));
    Check.near("vor dem ersten Anker x", -5.0, out[0], TOL);
    Check.near("vor dem ersten Anker y", -1.8, out[1], TOL);
    Check.that("vor dem ersten Anker gilt nicht als interpoliert",
        !m.isInterpolatedAt(s, 0));

    // ---- Drei Anker: extrapoliert wird mit den zwei am Rand, nicht mit
    // dem ersten und letzten ----
    LedAnchorStore three = store();
    LedPositionMap m3 = map();
    Check.that("Anker LED 2", three.set(2, -6f, 0f, NO_CROSSINGS));
    Check.that("Anker LED 10", three.set(10, 0f, 0f, NO_CROSSINGS));
    Check.that("Anker LED 12", three.set(12, 1f, 1f, NO_CROSSINGS));
    // Rand-Vektor sind LED 10 und 12: (0.5, 0.5) je Index.
    // LED 16 -> (1 + 4*0.5, 1 + 4*0.5) = (3, 3)
    Check.that("LED 16 ist definiert", m3.positionOf(three, 16, out));
    Check.near("Extrapolation nimmt die zwei Anker am Rand, x", 3.0, out[0], TOL);
    Check.near("Extrapolation nimmt die zwei Anker am Rand, y", 3.0, out[1], TOL);

    // ---- Klemmung auf die Grundflaeche ----
    // Stripe 1: LED 0 -> (0,0), LED 1 -> (1,0.5). Steiler Vektor, LED 19
    // landet weit ausserhalb und muss geklemmt werden.
    LedAnchorStore steep = store();
    LedPositionMap mS = map();
    int base1 = PER_STRIPE;
    Check.that("steiler Anker A", steep.set(base1 + 0, 0f, 0f, NO_CROSSINGS));
    Check.that("steiler Anker B", steep.set(base1 + 1, 1f, 0.5f, NO_CROSSINGS));
    Check.that("LED 19 des steilen Stripes ist definiert",
        mS.positionOf(steep, base1 + 19, out));
    Check.near("x wird auf die halbe Grundflaeche geklemmt", 7.0, out[0], TOL);
    Check.near("y wird auf die halbe Grundflaeche geklemmt", 4.0, out[1], TOL);

    // ---- Nur ein Anker auf dem Stripe ----
    LedAnchorStore one = store();
    LedPositionMap m1 = map();
    int base2 = 2 * PER_STRIPE;
    Check.that("einzelner Anker", one.set(base2 + 5, 1.5f, -2f, NO_CROSSINGS));
    Check.that("erste LED des Stripes ist definiert", m1.positionOf(one, base2 + 0, out));
    Check.near("alle LEDs liegen auf dem einzigen Anker, x", 1.5, out[0], TOL);
    Check.near("alle LEDs liegen auf dem einzigen Anker, y", -2.0, out[1], TOL);
    Check.that("letzte LED des Stripes ist definiert", m1.positionOf(one, base2 + 19, out));
    Check.near("auch am anderen Ende, x", 1.5, out[0], TOL);
    Check.that("beim einzigen Anker selbst gilt interpoliert",
        m1.isInterpolatedAt(one, base2 + 5));
    Check.that("daneben gilt nicht interpoliert",
        !m1.isInterpolatedAt(one, base2 + 6));

    // ---- Kein Anker auf dem Stripe ----
    LedAnchorStore none = store();
    LedPositionMap mN = map();
    int base3 = 3 * PER_STRIPE;
    out[0] = 99f;
    out[1] = 99f;
    Check.that("ohne Anker ist die LED nicht definiert", !mN.positionOf(none, base3, out));
    Check.near("out2 bleibt unberuehrt, x", 99.0, out[0], TOL);
    Check.near("out2 bleibt unberuehrt, y", 99.0, out[1], TOL);
    Check.that("ohne Anker gilt nicht interpoliert", !mN.isInterpolatedAt(none, base3));

    // ---- Stripes beeinflussen sich nicht ----
    LedAnchorStore sep = store();
    LedPositionMap mSep = map();
    Check.that("Anker auf Stripe 0", sep.set(0, -5f, -3f, NO_CROSSINGS));
    Check.that("Anker auf Stripe 1", sep.set(PER_STRIPE + 19, 5f, 3f, NO_CROSSINGS));
    Check.that("Stripe 0 hat nur seinen eigenen Anker",
        mSep.positionOf(sep, 10, out));
    Check.near("Stripe 0 bleibt bei seinem einzigen Anker, x", -5.0, out[0], TOL);
    Check.that("Stripe 2 bleibt undefiniert",
        !mSep.positionOf(sep, 2 * PER_STRIPE, out));

    // ---- Index ausserhalb ----
    Check.that("negativer Index ist nicht definiert", !m.positionOf(s, -1, out));
    Check.that("Index hinter dem Ende ist nicht definiert",
        !m.positionOf(s, STRIPES * PER_STRIPE, out));

    System.exit(Check.report("LedPositionMapTest"));
  }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedPositionMapTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: class LedPositionMap`.

- [ ] **Step 3: `LedPositionMap.java` anlegen**

```java
import java.util.SortedSet;

// Rechnet aus den von Hand gesetzten Ankern die Position jeder LED.
// Bewusst ohne Processing- und Netzabhaengigkeit, damit die Rechnung ueber
// test/run.sh pruefbar bleibt.
//
// Wichtig fuer das Verstaendnis: der Vorschlag, den das Erfassungswerkzeug
// anzeigt, IST das Ergebnis dieser Klasse. Es gibt keine zweite Rechnung fuer
// "geschaetzte" Positionen und damit keine zweite Wahrheit, die auseinander
// laufen koennte.
class LedPositionMap {

  private final int numStripes;
  private final int numLedsPerStripe;
  private final float halfX;
  private final float halfY;

  LedPositionMap(int numStripes, int numLedsPerStripe, float footprintX, float footprintY) {
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.halfX = footprintX / 2f;
    this.halfY = footprintY / 2f;
  }

  private boolean inRange(int ledIndex) {
    return ledIndex >= 0 && ledIndex < numStripes * numLedsPerStripe;
  }

  // Position einer einzelnen LED. Liefert false, wenn der Stripe dieser LED
  // keinen einzigen Anker hat; out2 bleibt dann unberuehrt, damit der Aufrufer
  // einen alten Wert nicht mit einer Null verwechselt.
  boolean positionOf(LedAnchorStore store, int ledIndex, float[] out2) {
    if (!inRange(ledIndex)) {
		return false;
	}
    int stripe = ledIndex / numLedsPerStripe;
    SortedSet<Integer> anchors = store.anchorsOnStripe(stripe);
    if (anchors.isEmpty()) {
		return false;
	}

    // Auf einem Anker selbst ist nichts zu rechnen.
    if (store.has(ledIndex)) {
      out2[0] = store.x(ledIndex);
      out2[1] = store.y(ledIndex);
      return true;
    }
    // Ein einzelner Anker gibt keine Richtung her - alle LEDs des Stripes
    // liegen auf diesem Punkt. Grob, aber nie undefiniert.
    if (anchors.size() == 1) {
      int only = anchors.first().intValue();
      out2[0] = store.x(only);
      out2[1] = store.y(only);
      return true;
    }

    // Die zwei Anker bestimmen, zwischen bzw. ab denen gerechnet wird.
    // headSet(i)   = alle Anker echt kleiner als i
    // tailSet(i)   = alle Anker groesser oder gleich i
    SortedSet<Integer> below = anchors.headSet(Integer.valueOf(ledIndex));
    SortedSet<Integer> above = anchors.tailSet(Integer.valueOf(ledIndex));
    int ia;
    int ib;
    if (!below.isEmpty() && !above.isEmpty()) {
      // dazwischen
      ia = below.last().intValue();
      ib = above.first().intValue();
    } else if (above.isEmpty()) {
      // hinter dem letzten Anker: Vektor der LETZTEN ZWEI fortsetzen, nicht
      // den des ersten zum letzten
      ib = anchors.last().intValue();
      ia = anchors.headSet(Integer.valueOf(ib)).last().intValue();
    } else {
      // vor dem ersten Anker: Vektor der ERSTEN ZWEI fortsetzen
      ia = anchors.first().intValue();
      ib = anchors.tailSet(Integer.valueOf(ia + 1)).first().intValue();
    }

    float ax = store.x(ia);
    float ay = store.y(ia);
    float bx = store.x(ib);
    float by = store.y(ib);
    // Ein und dieselbe Zeile fuer Interpolation und Extrapolation: t liegt
    // dazwischen in (0,1) und ausserhalb eben ausserhalb.
    float t = (float) (ledIndex - ia) / (float) (ib - ia);
    out2[0] = clamp(ax + (bx - ax) * t, halfX);
    out2[1] = clamp(ay + (by - ay) * t, halfY);
    return true;
  }

  // Zwischen zwei Ankern - oder genau auf einem - gilt die Position als
  // gestuetzt. Ausserhalb ist sie geraten, und genau das faerbt das
  // Erfassungswerkzeug rot.
  boolean isInterpolatedAt(LedAnchorStore store, int ledIndex) {
    if (!inRange(ledIndex)) {
		return false;
	}
    SortedSet<Integer> anchors = store.anchorsOnStripe(ledIndex / numLedsPerStripe);
    if (anchors.isEmpty()) {
		return false;
	}
    boolean atOrBelow = !anchors.headSet(Integer.valueOf(ledIndex + 1)).isEmpty();
    boolean atOrAbove = !anchors.tailSet(Integer.valueOf(ledIndex)).isEmpty();
    return atOrBelow && atOrAbove;
  }

  // Die Extrapolation kann weit aus der Halle hinaus zeigen, wenn zwei Anker
  // dicht beieinander liegen und ueber hunderte LEDs fortgesetzt werden.
  // Geklemmt wird auf den physisch moeglichen Bereich, damit keine absurden
  // Koordinaten in den Klang gehen.
  private static float clamp(float v, float half) {
    if (v < -half) {
		return -half;
	}
    if (v > half) {
		return half;
	}
    return v;
  }
}
```

- [ ] **Step 4: Suite in die Default-Liste des Testtreibers eintragen**

In `test/run.sh` Zeile 27 `LedPositionMapTest` anfügen:

```bash
  set -- ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest ApplyCrossingsTest NodeSelectionTest LedAnchorStoreTest LedPositionMapTest
```

- [ ] **Step 5: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 6: Commit**

```bash
git add LedPositionMap.java test/LedPositionMapTest.java test/run.sh
git commit -m "LedPositionMap: Position jeder LED aus den Ankern interpolieren"
```

---

### Task 6: `LedPositionMap` — vorgerechnete Arrays und Abdeckungsbericht

`positionOf` sucht bei jedem Aufruf im Store. Der Transport-Effekt fragt pro Impuls und Frame — deshalb einmal alles in zwei `float[]` schreiben, wie es `LedInNetInfo.partOfNode` für die Knoten vormacht.

**Files:**
- Modify: `LedPositionMap.java`
- Modify: `test/LedPositionMapTest.java`

**Interfaces:**
- Consumes: alles aus Aufgabe 5
- Produces:

```java
  void    apply(LedAnchorStore store);   // muss vor x/y/isDefined laufen
  float   x(int ledIndex);               // 0f wenn apply() nie lief
  float   y(int ledIndex);
  boolean isDefined(int ledIndex);
  boolean isInterpolated(int ledIndex);
  int     undefinedCount();
  int     extrapolatedCount();
  String  coverageReport(LedAnchorStore store);
```

Aufgabe 7 (`applyPositions`) und Aufgabe 15 (`/net/impulse`) lesen ausschliesslich die Array-Fassung, nie `positionOf`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/LedPositionMapTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- apply() stimmt mit positionOf() ueberein ----
    LedAnchorStore ap = store();
    LedPositionMap mAp = map();
    Check.that("Anker LED 4", ap.set(4, -3f, -1f, NO_CROSSINGS));
    Check.that("Anker LED 14", ap.set(14, 2f, 1f, NO_CROSSINGS));
    Check.that("Anker auf Stripe 2", ap.set(2 * PER_STRIPE + 3, 0f, 0f, NO_CROSSINGS));

    Check.eq("vor apply() ist x null", 0, (long) mAp.x(9));
    Check.that("vor apply() ist nichts definiert", !mAp.isDefined(9));

    mAp.apply(ap);

    float[] ref = new float[2];
    int mismatches = 0;
    int definedCount = 0;
    for (int i = 0; i < STRIPES * PER_STRIPE; i++) {
      boolean def = mAp.positionOf(ap, i, ref);
      if (def != mAp.isDefined(i)) {
		mismatches++;
	  } else if (def) {
        definedCount++;
        if (Math.abs(ref[0] - mAp.x(i)) > TOL || Math.abs(ref[1] - mAp.y(i)) > TOL) {
			mismatches++;
		}
        if (mAp.isInterpolatedAt(ap, i) != mAp.isInterpolated(i)) {
			mismatches++;
		}
      }
    }
    Check.eq("apply() weicht nie von positionOf() ab", 0, mismatches);
    // Stripe 0 und Stripe 2 haben Anker, Stripe 1 und 3 nicht.
    Check.eq("definierte LEDs sind die zwei Stripes mit Ankern",
        2 * PER_STRIPE, definedCount);
    Check.eq("undefiniert sind die zwei Stripes ohne Anker",
        2 * PER_STRIPE, mAp.undefinedCount());

    // Stripe 0: extrapoliert sind LED 0..3 und 15..19, das sind 9.
    // Stripe 2: ein einzelner Anker, alles ausser der Ankerled selbst
    // gilt als extrapoliert, das sind 19.
    Check.eq("Zahl der nur extrapolierten LEDs", 9 + 19, mAp.extrapolatedCount());

    // ---- Abdeckungsbericht ----
    String rep = mAp.coverageReport(ap);
    Check.that("Bericht nennt die Zahl der undefinierten LEDs",
        rep.indexOf(String.valueOf(2 * PER_STRIPE)) >= 0);
    Check.that("Bericht nennt die Stripes ohne Anker",
        rep.indexOf("Stripes ohne Anker") >= 0);
    Check.that("Bericht nennt Stripe 1", rep.indexOf("1") >= 0);
    Check.that("Bericht nennt Stripe 3", rep.indexOf("3") >= 0);

    LedAnchorStore full = store();
    LedPositionMap mFull = map();
    for (int st = 0; st < STRIPES; st++) {
      Check.that("Anker am Anfang von Stripe " + st,
          full.set(st * PER_STRIPE, -1f, -1f, NO_CROSSINGS));
      Check.that("Anker am Ende von Stripe " + st,
          full.set(st * PER_STRIPE + PER_STRIPE - 1, 1f, 1f, NO_CROSSINGS));
    }
    mFull.apply(full);
    Check.eq("mit Ankern an allen Enden ist nichts undefiniert",
        0, mFull.undefinedCount());
    Check.eq("und nichts nur extrapoliert", 0, mFull.extrapolatedCount());
    Check.that("Bericht nennt dann keine Stripes ohne Anker",
        mFull.coverageReport(full).indexOf("Stripes ohne Anker") < 0);

    // ---- apply() ist wiederholbar, ohne Reste ----
    LedAnchorStore again = store();
    LedPositionMap mAgain = map();
    Check.that("Anker vor dem ersten apply", again.set(0, -2f, -2f, NO_CROSSINGS));
    mAgain.apply(again);
    Check.eq("ein Stripe mit Anker", 3 * PER_STRIPE, mAgain.undefinedCount());
    Check.that("Anker wieder entfernt", again.remove(0));
    mAgain.apply(again);
    Check.eq("nach dem Entfernen ist alles undefiniert",
        STRIPES * PER_STRIPE, mAgain.undefinedCount());
    Check.that("und keine Position bleibt haengen", !mAgain.isDefined(0));
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedPositionMapTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: method apply(LedAnchorStore)`.

- [ ] **Step 3: Arrays und Bericht einbauen**

In `LedPositionMap.java` die Felder oben bei den vorhandenen ergänzen:

```java
  // Vorgerechnete Positionen. null, solange apply() nicht lief - der heisse
  // Pfad im Transport-Effekt liest ausschliesslich hier, nicht ueber
  // positionOf(), damit pro Impuls und Frame keine Suche im Store anfaellt.
  private float[] xs;
  private float[] ys;
  private boolean[] defined;
  private boolean[] interpolated;
  private int undefined;
  private int extrapolated;
```

Und die Methoden am Ende der Klasse, vor `clamp`:

```java
  // Rechnet alle Positionen einmal durch. Beim Start aus setup() und bei
  // jedem R in den beiden Kalibrierwerkzeugen. Legt die Arrays jedes Mal neu
  // an, damit ein entfernter Anker keine alte Position stehen laesst.
  void apply(LedAnchorStore store) {
    int n = numStripes * numLedsPerStripe;
    xs = new float[n];
    ys = new float[n];
    defined = new boolean[n];
    interpolated = new boolean[n];
    undefined = 0;
    extrapolated = 0;
    float[] out = new float[2];
    for (int i = 0; i < n; i++) {
      if (positionOf(store, i, out)) {
        xs[i] = out[0];
        ys[i] = out[1];
        defined[i] = true;
        interpolated[i] = isInterpolatedAt(store, i);
        if (!interpolated[i]) {
			extrapolated++;
		}
      } else {
        undefined++;
      }
    }
  }

  float x(int ledIndex) {
    return xs == null || !inRange(ledIndex) ? 0f : xs[ledIndex];
  }

  float y(int ledIndex) {
    return ys == null || !inRange(ledIndex) ? 0f : ys[ledIndex];
  }

  boolean isDefined(int ledIndex) {
    return defined != null && inRange(ledIndex) && defined[ledIndex];
  }

  boolean isInterpolated(int ledIndex) {
    return interpolated != null && inRange(ledIndex) && interpolated[ledIndex];
  }

  int undefinedCount() { return undefined; }

  int extrapolatedCount() { return extrapolated; }

  // Fuer die Taste T im Erfassungswerkzeug und die Startmeldung in
  // imPulse.pde. Nennt die Stripes ohne jeden Anker beim Namen - das sind
  // die, an denen noch gar nicht gearbeitet wurde.
  String coverageReport(LedAnchorStore store) {
    StringBuilder sb = new StringBuilder();
    sb.append(undefined).append(" LEDs ohne Position, ")
      .append(extrapolated).append(" nur extrapoliert");
    StringBuilder without = new StringBuilder();
    for (int st = 0; st < numStripes; st++) {
      if (store.anchorsOnStripe(st).isEmpty()) {
        if (without.length() > 0) {
			without.append(' ');
		}
        without.append(st);
      }
    }
    if (without.length() > 0) {
      sb.append("; Stripes ohne Anker: ").append(without);
    }
    return sb.toString();
  }
```

- [ ] **Step 4: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Commit**

```bash
git add LedPositionMap.java test/LedPositionMapTest.java
git commit -m "LedPositionMap: Positionen vorrechnen und Abdeckung berichten"
```

---

### Task 7: Knotenpositionen an `LedNetworkNode`

Der Transport-Effekt schickt bei `/net/hitNode` die Position des Knotens mit, nicht die einer einzelnen LED. Ein Knoten hat zwei LEDs auf zwei Stripes; ist sein Anker gesetzt, sind beide Positionen identisch, ist er noch offen, weichen die interpolierten Werte leicht ab. Der Mittelwert ist dann die ehrlichere Angabe als „die erste LED".

**Achtung:** `LedStripeNetworks.java` benutzt **Tabs**, nicht zwei Leerzeichen. Die Einrückung der Datei fortsetzen, nichts umformatieren.

**Files:**
- Modify: `LedStripeNetworks.java` (Klasse `LedNetworkNode`)
- Modify: `test/ApplyCrossingsTest.java`

**Interfaces:**
- Consumes: `LedPositionMap.apply`, `x`, `y`, `isDefined` aus Aufgabe 6
- Produces:

```java
  // Felder an LedNetworkNode
  public float posX, posY;

  // statische Methode an LedNetworkNode
  public static void applyPositions(LedPositionMap map, ArrayList<LedNetworkNode> nodes);
```

Aufruf ist `LedNetworkNode.applyPositions(map, listOfNodes)` — **nicht** `LedInNetInfo.applyPositions`. `applyCrossings` sitzt aus historischen Gründen an `LedInNetInfo`; die Positionen gehören zu den Knoten. Aufgabe 12 ruft beides hintereinander auf, Aufgabe 14 liest `posX`/`posY`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/ApplyCrossingsTest.java` vor `System.exit(...)` einfügen. Die Datei kennt `LedInNetInfo` und `LedNetworkNode` schon; nur `ArrayList` und `TreeSet` müssen oben importiert sein, was bereits der Fall ist.

```java
    // ---- applyPositions: Mittelwert ueber die LEDs eines Knotens ----
    // Kleine synthetische Geometrie, wie in LedPositionMapTest.
    final int PSTRIPES = 4;
    final int PPER = 20;
    LedInNetInfo[] pInfos = LedInNetInfo.buildNetInfo(PSTRIPES, PPER);
    ArrayList<TreeSet<Integer>> pCross = new ArrayList<TreeSet<Integer>>();
    TreeSet<Integer> pair = new TreeSet<Integer>();
    pair.add(Integer.valueOf(10));            // Stripe 0, LED 10
    pair.add(Integer.valueOf(PPER + 10));     // Stripe 1, LED 10
    pCross.add(pair);
    ArrayList<LedNetworkNode> pNodes = new ArrayList<LedNetworkNode>();
    LedInNetInfo.applyCrossings(pCross, pInfos, pNodes);
    Check.eq("ein Knoten aufgebaut", 1, pNodes.size());

    LedAnchorStore pStore = new LedAnchorStore(PSTRIPES, PPER, 14f, 8f, 0.5f);
    LedPositionMap pMap = new LedPositionMap(PSTRIPES, PPER, 14f, 8f);

    // Noch kein Anker: der Knoten bleibt bei (0,0)
    pMap.apply(pStore);
    LedNetworkNode.applyPositions(pMap, pNodes);
    Check.near("ohne Anker bleibt posX null", 0.0, pNodes.get(0).posX, 1e-4);
    Check.near("ohne Anker bleibt posY null", 0.0, pNodes.get(0).posY, 1e-4);

    // Anker auf der Kreuzung gesetzt: set() verteilt ihn auf beide LEDs,
    // beide Positionen sind identisch, der Mittelwert ist genau der Anker.
    Check.that("Kreuzungsanker gesetzt", pStore.set(10, 2.5f, -1.5f, pCross));
    Check.that("die Partner-LED wurde mitgesetzt", pStore.has(PPER + 10));
    pMap.apply(pStore);
    LedNetworkNode.applyPositions(pMap, pNodes);
    Check.near("posX ist der Anker", 2.5, pNodes.get(0).posX, 1e-4);
    Check.near("posY ist der Anker", -1.5, pNodes.get(0).posY, 1e-4);

    // Weichen die beiden LEDs ab, wird gemittelt. Dafuer den Kreuzungsanker
    // entfernen und die zwei Stripes ueber ihre Enden unterschiedlich
    // aufspannen.
    Check.that("Kreuzungsanker auf Stripe 0 entfernt", pStore.remove(10));
    Check.that("Kreuzungsanker auf Stripe 1 entfernt", pStore.remove(PPER + 10));
    // Stripe 0: LED 0 -> (0,0), LED 20-1 -> (0,0) ... konstant 0
    Check.that("Stripe 0 Anfang", pStore.set(0, 0f, 0f, pCross));
    Check.that("Stripe 0 Ende", pStore.set(PPER - 1, 0f, 0f, pCross));
    // Stripe 1: LED 0 -> (4,2), LED 19 -> (4,2) ... konstant (4,2)
    Check.that("Stripe 1 Anfang", pStore.set(PPER + 0, 4f, 2f, pCross));
    Check.that("Stripe 1 Ende", pStore.set(PPER + PPER - 1, 4f, 2f, pCross));
    pMap.apply(pStore);
    LedNetworkNode.applyPositions(pMap, pNodes);
    Check.near("posX ist der Mittelwert beider Stripes", 2.0, pNodes.get(0).posX, 1e-4);
    Check.near("posY ist der Mittelwert beider Stripes", 1.0, pNodes.get(0).posY, 1e-4);
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh ApplyCrossingsTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: method applyPositions`.

- [ ] **Step 3: Felder und Methode einbauen**

In `LedStripeNetworks.java` in der Klasse `LedNetworkNode` nach `lastActivationTime` (Tabs beibehalten):

```java
	// Draufsicht-Position in Metern, Ursprung Netzmitte. Gesetzt von
	// applyPositions, mitgeschickt an /net/hitNode.
	public float posX = 0;
	public float posY = 0;
```

Und danach, noch innerhalb von `LedNetworkNode`:

```java
	// Setzt fuer jeden Knoten den Mittelwert der Positionen seiner LEDs.
	//
	// Ein Knoten ist EIN physischer Punkt mit zwei LEDs auf zwei Stripes. Ist
	// sein Anker gesetzt, liefert die Map fuer beide denselben Wert und der
	// Mittelwert ist genau dieser Anker. Ist der Anker noch offen, weichen die
	// interpolierten Werte der beteiligten Stripes leicht voneinander ab -
	// dann ist der Mittelwert ehrlicher als der erste Eintrag.
	//
	// Setzt (0,0), wenn keine einzige LED des Knotens eine Position hat.
	public static void applyPositions(LedPositionMap map, ArrayList<LedNetworkNode> nodes) {
		for (LedNetworkNode node : nodes) {
			float sumX = 0;
			float sumY = 0;
			int n = 0;
			for (Integer ledIdx : node.ledIndices) {
				int idx = ledIdx.intValue();
				if (map.isDefined(idx)) {
					sumX += map.x(idx);
					sumY += map.y(idx);
					n++;
				}
			}
			if (n > 0) {
				node.posX = sumX / n;
				node.posY = sumY / n;
			} else {
				node.posX = 0;
				node.posY = 0;
			}
		}
	}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Commit**

```bash
git add LedStripeNetworks.java test/ApplyCrossingsTest.java
git commit -m "LedNetworkNode: Position je Knoten als Mittelwert seiner LEDs"
```

---
# Stufe 2 — Erfassungswerkzeug

Danach lauffähig: Positionen lassen sich vor Ort mit der Maus aufnehmen und nach `data/ledPositions.txt` schreiben.

### Task 8: `LedPositionCalibration` — Arbeitsliste und Navigation

Die Liste der Punkte, die von Hand gesetzt werden müssen, und das Blättern darin.

**Kein `implements runnableLedEffect`.** Das Interface steht in `mixer.java`, das `RemoteControlledFloatParameter` aus `AbstractParameter.java` braucht, und das importiert `oscP5` und `netP5` — nichts davon steht `test/run.sh` zur Verfügung. Nachgeprüft: `javac` gegen `core.jar` allein bricht bei `mixer.java:10` mit `cannot find symbol: class RemoteControlledFloatParameter` ab. Die Klasse hat `drawMe()` und `getName()` mit denselben Namen, nennt das Interface aber nicht. Das kostet nichts, weil `imPulse.pde` `drawMe()` direkt aufruft und nie über den Mixer geht — genau wie bei `NodeCalibration`.

**Files:**
- Create: `LedPositionCalibration.java`
- Create: `test/LedPositionCalibrationTest.java`
- Modify: `test/run.sh:27` (Default-Suitenliste)

**Interfaces:**
- Consumes: `LedAnchorStore` (Aufgabe 2–4), `LedPositionMap` (Aufgabe 5–6), `NodeCrossingStore.crossings()`, `LedNetworkNode` (Aufgabe 7)
- Produces:

```java
class LedPositionCalibration {

  static final float[] STEP_SIZES_M = { 0.01f, 0.05f, 0.25f };
  static final long BLINK_MILLIS = 400;
  static final long CLEAR_ALL_CONFIRM_MIN_MILLIS = 300;
  static final long CLEAR_ALL_CONFIRM_MAX_MILLIS = 5000;
  static final float DIM = 0.06f;

  LedPositionCalibration(LedAnchorStore store,
                         LedPositionMap map,
                         NodeCrossingStore crossingStore,
                         java.util.ArrayList<LedNetworkNode> nodes,
                         int numStripes, int numLedsPerStripe,
                         String filePath,
                         int paneX, int paneY, int paneW, int paneH,
                         float footprintX, float footprintY);

  void    rebuildWorklist();          // auch aus dem Konstruktor gerufen
  int     entryCount();
  int     entryIndex();               // -1 nur bei leerer Liste
  int[]   ledsOfEntry(int entry);     // aufsteigend; Kopie, nicht die Innerei
  boolean entryIsCrossing(int entry); // mehr als eine LED
  boolean entryIsSet(int entry);      // ALLE LEDs des Eintrags haben einen Anker
  int     openCount();
  void    next();                     // klemmt am Ende, kein Umlauf
  void    prev();                     // klemmt am Anfang, kein Umlauf
  boolean nextOpen();                 // false, wenn keiner mehr folgt
  String  lastMessage();
}
```

Diese Signaturen gelten für Aufgabe 9 bis 12 unverändert.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Neue Datei `test/LedPositionCalibrationTest.java`:

```java
import java.util.ArrayList;
import java.util.TreeSet;

public class LedPositionCalibrationTest {

  // Kleine synthetische Geometrie. NICHT gegen data/nodeCrossings.txt
  // pruefen - die Datei waechst waehrend der Kalibrierung.
  static final int STRIPES = 4;
  static final int PER_STRIPE = 20;
  static final float FOOT_X = 14f;
  static final float FOOT_Y = 8f;
  static final float PITCH = 0.5f;
  static final String NO_FILE = "";

  // Pane-Rechteck wie im Sketch, nur mit derselben Rechnung
  static final int PANE_X = 0;
  static final int PANE_Y = 0;
  static final int PANE_W = 525;
  static final int PANE_H = 300;

  static NodeCrossingStore crossings(int[][] pairs) {
    NodeCrossingStore cs = new NodeCrossingStore(STRIPES, PER_STRIPE);
    for (int[] p : pairs) {
      // add() erwartet Stripe und LED getrennt
      cs.add(p[0] / PER_STRIPE, p[0] % PER_STRIPE, p[1] / PER_STRIPE, p[1] % PER_STRIPE);
    }
    return cs;
  }

  static LedPositionCalibration build(NodeCrossingStore cs, LedAnchorStore store) {
    LedPositionMap map = new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
    ArrayList<LedNetworkNode> nodes = new ArrayList<LedNetworkNode>();
    LedInNetInfo[] infos = LedInNetInfo.buildNetInfo(STRIPES, PER_STRIPE);
    LedInNetInfo.applyCrossings(cs.crossings(), infos, nodes);
    return new LedPositionCalibration(store, map, cs, nodes,
        STRIPES, PER_STRIPE, NO_FILE,
        PANE_X, PANE_Y, PANE_W, PANE_H, FOOT_X, FOOT_Y);
  }

  static LedAnchorStore store() {
    return new LedAnchorStore(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y, PITCH);
  }

  public static void main(String[] args) throws Exception {
    // Stripe 0 = 0..19, Stripe 1 = 20..39, Stripe 2 = 40..59, Stripe 3 = 60..79
    // Kreuzung A = {10, 30}, Kreuzung B = {5, 75}
    NodeCrossingStore cs = crossings(new int[][] { { 10, 30 }, { 5, 75 } });
    Check.eq("zwei Kreuzungen aufgenommen", 2, cs.size());

    LedAnchorStore st = store();
    LedPositionCalibration c = build(cs, st);

    // ---- Laenge: 2*Stripes + Kreuzungen = 8 + 2 = 10 ----
    Check.eq("Eintraege sind 2*Stripes + Kreuzungen", 2 * STRIPES + 2, c.entryCount());

    // ---- Reihenfolge nach kleinstem LED-Index ----
    // erwartet: 0, 5(B), 10(A), 19, 20, 39, 40, 59, 60, 79
    int[] expectedFirstLed = { 0, 5, 10, 19, 20, 39, 40, 59, 60, 79 };
    for (int i = 0; i < expectedFirstLed.length; i++) {
      Check.eq("Eintrag " + i + " beginnt bei LED " + expectedFirstLed[i],
          expectedFirstLed[i], c.ledsOfEntry(i)[0]);
    }

    // ---- Kreuzungseintraege tragen beide LEDs, Stripe-Enden eine ----
    Check.that("Eintrag 1 ist eine Kreuzung", c.entryIsCrossing(1));
    Check.eq("Kreuzung B hat zwei LEDs", 2, c.ledsOfEntry(1).length);
    Check.eq("zweite LED von Kreuzung B", 75, c.ledsOfEntry(1)[1]);
    Check.that("Eintrag 2 ist eine Kreuzung", c.entryIsCrossing(2));
    Check.eq("zweite LED von Kreuzung A", 30, c.ledsOfEntry(2)[1]);
    Check.that("Eintrag 0 ist keine Kreuzung", !c.entryIsCrossing(0));
    Check.eq("ein Stripe-Ende hat eine LED", 1, c.ledsOfEntry(0).length);

    // ---- ledsOfEntry gibt eine Kopie, kein Fenster in die Innerei ----
    int[] borrowed = c.ledsOfEntry(1);
    borrowed[0] = -999;
    Check.eq("Aendern der Kopie beruehrt die Liste nicht", 5, c.ledsOfEntry(1)[0]);

    // ---- Anfangszustand und offene Eintraege ----
    Check.eq("Start beim ersten Eintrag", 0, c.entryIndex());
    Check.eq("zu Beginn sind alle offen", 2 * STRIPES + 2, c.openCount());
    Check.that("Eintrag 0 ist offen", !c.entryIsSet(0));

    // ---- Blaettern klemmt an beiden Enden, kein Umlauf ----
    c.prev();
    Check.eq("prev am Anfang bleibt stehen", 0, c.entryIndex());
    c.next();
    Check.eq("next geht vor", 1, c.entryIndex());
    for (int i = 0; i < 50; i++) {
		c.next();
	}
    Check.eq("next klemmt am letzten Eintrag", c.entryCount() - 1, c.entryIndex());
    c.prev();
    Check.eq("prev geht zurueck", c.entryCount() - 2, c.entryIndex());

    // ---- Ein gesetzter Kreuzungsanker schliesst den ganzen Eintrag ----
    // Kreuzung B = {5, 75}: set(5, ...) verteilt auf 75 mit.
    Check.that("Kreuzungsanker gesetzt", st.set(5, 1f, 1f, cs.crossings()));
    Check.that("Partner-LED wurde mitgesetzt", st.has(75));
    Check.that("Eintrag 1 gilt jetzt als gesetzt", c.entryIsSet(1));
    Check.eq("ein Eintrag weniger offen", 2 * STRIPES + 1, c.openCount());

    // ---- Ein halb gesetzter Eintrag gilt als offen ----
    Check.that("Partner-LED einzeln entfernt", st.remove(75));
    Check.that("halb gesetzt gilt als offen", !c.entryIsSet(1));

    // ---- nextOpen ueberspringt gesetzte Eintraege ----
    LedAnchorStore st2 = store();
    LedPositionCalibration c2 = build(cs, st2);
    Check.that("Eintrag 0 setzen", st2.set(0, -6f, -3f, cs.crossings()));
    Check.that("Eintrag 1 setzen", st2.set(5, -5f, -2f, cs.crossings()));
    Check.that("nextOpen findet einen offenen", c2.nextOpen());
    Check.eq("nextOpen springt auf Eintrag 2", 2, c2.entryIndex());

    // alle setzen, dann findet nextOpen keinen mehr
    for (int i = 0; i < c2.entryCount(); i++) {
      st2.set(c2.ledsOfEntry(i)[0], 0f, 0f, cs.crossings());
    }
    Check.eq("nichts mehr offen", 0, c2.openCount());
    Check.that("nextOpen findet keinen offenen mehr", !c2.nextOpen());
    Check.that("die Meldung sagt das auch", c2.lastMessage().length() > 0);

    // ---- Kreuzung auf einem Stripe-Ende verschmilzt mit dessen Eintrag ----
    // Kreuzung {0, 25}: LED 0 ist gleichzeitig Anfang von Stripe 0.
    NodeCrossingStore csEnd = crossings(new int[][] { { 0, 25 } });
    LedPositionCalibration cEnd = build(csEnd, store());
    // 2*4 Enden = 8, davon faellt LED 0 mit der Kreuzung zusammen -> 7
    // eigene Endeintraege, plus 1 Kreuzung = 8
    Check.eq("Kreuzung auf einem Stripe-Ende erzeugt keinen doppelten Eintrag",
        8, cEnd.entryCount());
    Check.that("der Eintrag bei LED 0 ist die Kreuzung", cEnd.entryIsCrossing(0));
    Check.eq("und traegt beide LEDs", 2, cEnd.ledsOfEntry(0).length);

    // ---- Neu aufgenommene Kreuzung erscheint nach rebuildWorklist ----
    LedAnchorStore st3 = store();
    NodeCrossingStore csGrow = crossings(new int[][] { { 10, 30 } });
    LedPositionCalibration c3 = build(csGrow, st3);
    Check.eq("vor der neuen Kreuzung", 2 * STRIPES + 1, c3.entryCount());
    Check.that("neue Kreuzung aufgenommen", csGrow.add(0, 7, 2, 3));
    Check.eq("ohne rebuild unveraendert", 2 * STRIPES + 1, c3.entryCount());
    c3.rebuildWorklist();
    Check.eq("nach rebuild ein Eintrag mehr", 2 * STRIPES + 2, c3.entryCount());
    // LED 7 auf Stripe 0 sortiert zwischen 0 und 10
    Check.eq("die neue Kreuzung sitzt an der richtigen Stelle", 7, c3.ledsOfEntry(1)[0]);
    Check.that("und ist offen", !c3.entryIsSet(1));

    System.exit(Check.report("LedPositionCalibrationTest"));
  }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedPositionCalibrationTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: class LedPositionCalibration`.

- [ ] **Step 3: `LedPositionCalibration.java` anlegen**

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// Erfassung der LED-Positionen mit der Maus. Haelt die Arbeitsliste der von
// Hand zu setzenden Punkte, den Zeiger darauf, die Umrechnung zwischen
// Draufsicht-Flaeche und Metern und die Rueckmeldung im Netz.
//
// Bewusst OHNE "implements runnableLedEffect": das Interface steht in
// mixer.java, das ueber RemoteControlledFloatParameter an oscP5 haengt und
// sich damit nicht von test/run.sh uebersetzen laesst. imPulse.pde ruft
// drawMe() direkt auf und geht nie ueber den Mixer - genau wie bei
// NodeCalibration -, das Interface waere also nur ein Etikett, das die
// Pruefbarkeit kostet.
//
// Ein EINTRAG der Arbeitsliste ist ein physischer Punkt, nicht eine LED. Eine
// Kreuzung ist damit ein Eintrag mit zwei LEDs; ein Klick setzt beide, weil
// LedAnchorStore.set() innerhalb eines Knotens verteilt.
class LedPositionCalibration {

  static final float[] STEP_SIZES_M = { 0.01f, 0.05f, 0.25f };
  static final long BLINK_MILLIS = 400;
  static final long CLEAR_ALL_CONFIRM_MIN_MILLIS = 300;
  static final long CLEAR_ALL_CONFIRM_MAX_MILLIS = 5000;
  static final float DIM = 0.06f;

  private final LedAnchorStore store;
  private final LedPositionMap map;
  private final NodeCrossingStore crossingStore;
  private final ArrayList<LedNetworkNode> nodes;
  private final int numStripes;
  private final int numLedsPerStripe;
  private final String filePath;
  private final int paneX;
  private final int paneY;
  private final int paneW;
  private final int paneH;
  private final float footprintX;
  private final float footprintY;

  private final List<int[]> entries = new ArrayList<int[]>();
  private int current = 0;
  private String message = "";

  LedPositionCalibration(LedAnchorStore store,
                         LedPositionMap map,
                         NodeCrossingStore crossingStore,
                         ArrayList<LedNetworkNode> nodes,
                         int numStripes, int numLedsPerStripe,
                         String filePath,
                         int paneX, int paneY, int paneW, int paneH,
                         float footprintX, float footprintY) {
    this.store = store;
    this.map = map;
    this.crossingStore = crossingStore;
    this.nodes = nodes;
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.filePath = filePath;
    this.paneX = paneX;
    this.paneY = paneY;
    this.paneW = paneW;
    this.paneH = paneH;
    this.footprintX = footprintX;
    this.footprintY = footprintY;
    rebuildWorklist();
  }

  String getName() { return "Positionen"; }

  String lastMessage() { return message; }

  // Baut die Liste der zu setzenden Punkte neu auf: jede Kreuzung ein
  // Eintrag, dazu Anfang und Ende jedes Stripes - ausser diese LED gehoert
  // schon zu einer Kreuzung, dann verschmelzen die beiden Eintraege.
  //
  // Wird aus dem Konstruktor und bei R gerufen, damit waehrend der Sitzung
  // aufgenommene Kreuzungen auftauchen.
  void rebuildWorklist() {
    int keepFirstLed = entries.isEmpty() ? -1 : entries.get(current)[0];

    List<int[]> built = new ArrayList<int[]>();
    Set<Integer> covered = new HashSet<Integer>();
    for (TreeSet<Integer> cluster : crossingStore.crossings()) {
      int[] leds = new int[cluster.size()];
      int k = 0;
      for (Integer idx : cluster) {
        leds[k] = idx.intValue();
        covered.add(idx);
        k++;
      }
      built.add(leds);
    }
    for (int st = 0; st < numStripes; st++) {
      addEndIfFree(built, covered, st * numLedsPerStripe);
      addEndIfFree(built, covered, st * numLedsPerStripe + numLedsPerStripe - 1);
    }
    Collections.sort(built, new Comparator<int[]>() {
      public int compare(int[] a, int[] b) {
        return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
      }
    });

    entries.clear();
    entries.addAll(built);

    // Nach einem Neuaufbau soll derselbe Punkt weiter unter dem Zeiger
    // stehen, nicht ein zufaellig anderer - sonst verliert man beim Druck auf
    // R die Stelle, an der man gerade arbeitet.
    current = 0;
    if (keepFirstLed >= 0) {
      for (int i = 0; i < entries.size(); i++) {
        if (entries.get(i)[0] == keepFirstLed) {
          current = i;
          break;
        }
      }
    }
  }

  private void addEndIfFree(List<int[]> built, Set<Integer> covered, int ledIndex) {
    if (covered.contains(Integer.valueOf(ledIndex))) {
		return;
	}
    built.add(new int[] { ledIndex });
    covered.add(Integer.valueOf(ledIndex));
  }

  int entryCount() { return entries.size(); }

  int entryIndex() { return entries.isEmpty() ? -1 : current; }

  // Kopie, damit ein Aufrufer die Liste nicht von aussen umschreiben kann.
  int[] ledsOfEntry(int entry) {
    if (entry < 0 || entry >= entries.size()) {
		return new int[0];
	}
    int[] src = entries.get(entry);
    int[] copy = new int[src.length];
    System.arraycopy(src, 0, copy, 0, src.length);
    return copy;
  }

  boolean entryIsCrossing(int entry) {
    return entry >= 0 && entry < entries.size() && entries.get(entry).length > 1;
  }

  // Gesetzt heisst: ALLE LEDs des Eintrags haben einen Anker. set() verteilt
  // innerhalb eines Knotens, im Normalfall sind also immer alle oder keine
  // gesetzt. Eine von Hand bearbeitete Positionsdatei kann aber die Haelfte
  // eines Knotens enthalten - der Eintrag gilt dann als offen, und ein Klick
  // setzt beide Seiten wieder zusammen.
  boolean entryIsSet(int entry) {
    if (entry < 0 || entry >= entries.size()) {
		return false;
	}
    for (int led : entries.get(entry)) {
      if (!store.has(led)) {
		return false;
	  }
    }
    return true;
  }

  int openCount() {
    int open = 0;
    for (int i = 0; i < entries.size(); i++) {
      if (!entryIsSet(i)) {
		open++;
	  }
    }
    return open;
  }

  void next() {
    if (current < entries.size() - 1) {
		current++;
	}
    message = describeCurrent();
  }

  void prev() {
    if (current > 0) {
		current--;
	}
    message = describeCurrent();
  }

  // Springt zum naechsten noch offenen Eintrag hinter dem aktuellen. Bleibt
  // stehen und meldet, wenn keiner mehr folgt.
  boolean nextOpen() {
    for (int i = current + 1; i < entries.size(); i++) {
      if (!entryIsSet(i)) {
        current = i;
        message = describeCurrent();
        return true;
      }
    }
    message = "Kein offener Eintrag hinter diesem - " + openCount()
        + " offen insgesamt, mit , zurueckblaettern";
    return false;
  }

  private String describeCurrent() {
    if (entries.isEmpty()) {
		return "Keine Eintraege";
	}
    int[] leds = entries.get(current);
    StringBuilder sb = new StringBuilder();
    sb.append("Eintrag ").append(current + 1).append('/').append(entries.size())
      .append(entryIsCrossing(current) ? " Kreuzung" : " Stripe-Ende").append(" LED");
    for (int led : leds) {
      sb.append(' ').append(led)
        .append(" (Stripe ").append(led / numLedsPerStripe)
        .append(':').append(led % numLedsPerStripe).append(')');
    }
    sb.append(entryIsSet(current) ? " - gesetzt" : " - offen");
    return sb.toString();
  }
}
```

- [ ] **Step 4: Suite in die Default-Liste des Testtreibers eintragen**

In `test/run.sh` Zeile 27 `LedPositionCalibrationTest` anfügen:

```bash
  set -- ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest ApplyCrossingsTest NodeSelectionTest LedAnchorStoreTest LedPositionMapTest LedPositionCalibrationTest
```

- [ ] **Step 5: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 6: Commit**

```bash
git add LedPositionCalibration.java test/LedPositionCalibrationTest.java test/run.sh
git commit -m "LedPositionCalibration: Arbeitsliste der zu setzenden Punkte"
```

---

### Task 9: Umrechnung Draufsicht-Fläche und Meter

Der Klick landet in Pixeln, gespeichert werden Meter. Beide Richtungen liegen in der prüfbaren Klasse, damit das Zeichnen in `imPulse.pde` und die Klickauswertung **dieselbe** Rechnung benutzen — sonst sitzt der gezeichnete Punkt neben der Stelle, die man angeklickt hat.

Y zeigt nach **vorn** und auf dem Schirm nach **oben**: grössere Y sind kleinere Pixel-Y. Das ist die einzige Stelle, an der ein Vorzeichen kippt.

**Files:**
- Modify: `LedPositionCalibration.java`
- Modify: `test/LedPositionCalibrationTest.java`

**Interfaces:**
- Consumes: Konstruktorwerte `paneX/paneY/paneW/paneH/footprintX/footprintY` aus Aufgabe 8
- Produces:

```java
  // false, wenn der Punkt ausserhalb des Rechtecks liegt; out2 bleibt dann
  // unberuehrt. Die Raender gehoeren dazu (einschliesslich).
  boolean paneToWorld(int px, int py, float[] out2);

  // Immer moeglich - Pixelkoordinaten als float, damit imPulse.pde ohne
  // Rundungssprung zeichnen kann.
  void worldToPane(float x, float y, float[] out2);
```

Aufgabe 12 benutzt beide zum Zeichnen und für `mousePressed`/`mouseDragged`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/LedPositionCalibrationTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- Umrechnung Flaeche <-> Meter ----
    // Pane (0,0,525,300) fuer 14 x 8 m. 525:300 == 14:8, keine Verzerrung.
    LedPositionCalibration cP = build(cs, store());
    float[] w = new float[2];
    float[] p = new float[2];
    final double MTOL = 0.03;   // ein Pixel sind 2,67 cm

    // Ecken. Y zeigt nach vorn und auf dem Schirm nach oben:
    // Pixel-Y 0 ist also +4 m, Pixel-Y 300 ist -4 m.
    Check.that("linke obere Ecke ist innen", cP.paneToWorld(PANE_X, PANE_Y, w));
    Check.near("linke obere Ecke x", -7.0, w[0], MTOL);
    Check.near("linke obere Ecke y", 4.0, w[1], MTOL);

    Check.that("rechte untere Ecke ist innen",
        cP.paneToWorld(PANE_X + PANE_W, PANE_Y + PANE_H, w));
    Check.near("rechte untere Ecke x", 7.0, w[0], MTOL);
    Check.near("rechte untere Ecke y", -4.0, w[1], MTOL);

    Check.that("rechte obere Ecke ist innen",
        cP.paneToWorld(PANE_X + PANE_W, PANE_Y, w));
    Check.near("rechte obere Ecke x", 7.0, w[0], MTOL);
    Check.near("rechte obere Ecke y", 4.0, w[1], MTOL);

    Check.that("linke untere Ecke ist innen",
        cP.paneToWorld(PANE_X, PANE_Y + PANE_H, w));
    Check.near("linke untere Ecke x", -7.0, w[0], MTOL);
    Check.near("linke untere Ecke y", -4.0, w[1], MTOL);

    // Mitte
    Check.that("Mitte ist innen",
        cP.paneToWorld(PANE_X + PANE_W / 2, PANE_Y + PANE_H / 2, w));
    Check.near("Mitte x ist der Ursprung", 0.0, w[0], MTOL);
    Check.near("Mitte y ist der Ursprung", 0.0, w[1], MTOL);

    // Ausserhalb wird verworfen, out2 bleibt unberuehrt
    w[0] = 42f;
    w[1] = 42f;
    Check.that("links daneben wird verworfen", !cP.paneToWorld(PANE_X - 1, PANE_Y, w));
    Check.that("rechts daneben wird verworfen",
        !cP.paneToWorld(PANE_X + PANE_W + 1, PANE_Y, w));
    Check.that("darueber wird verworfen", !cP.paneToWorld(PANE_X, PANE_Y - 1, w));
    Check.that("darunter - im HUD - wird verworfen",
        !cP.paneToWorld(PANE_X, PANE_Y + PANE_H + 1, w));
    Check.near("out2 bleibt unberuehrt, x", 42.0, w[0], 1e-6);
    Check.near("out2 bleibt unberuehrt, y", 42.0, w[1], 1e-6);

    // Rundlauf in beiden Richtungen
    float[][] probes = { { 0f, 0f }, { -7f, 4f }, { 7f, -4f },
                         { -3.25f, 1.1f }, { 2.9f, -0.45f }, { 6.99f, 3.99f } };
    for (float[] q : probes) {
      cP.worldToPane(q[0], q[1], p);
      Check.that("Rundlauf: (" + q[0] + "," + q[1] + ") liegt innen",
          cP.paneToWorld((int) (p[0] + 0.5f), (int) (p[1] + 0.5f), w));
      Check.near("Rundlauf x bei " + q[0], q[0], w[0], MTOL);
      Check.near("Rundlauf y bei " + q[1], q[1], w[1], MTOL);
    }

    // worldToPane rechnet die Ecken auf die Rechteckecken
    cP.worldToPane(-7f, 4f, p);
    Check.near("worldToPane linke obere Ecke px", PANE_X, p[0], 0.5);
    Check.near("worldToPane linke obere Ecke py", PANE_Y, p[1], 0.5);
    cP.worldToPane(7f, -4f, p);
    Check.near("worldToPane rechte untere Ecke px", PANE_X + PANE_W, p[0], 0.5);
    Check.near("worldToPane rechte untere Ecke py", PANE_Y + PANE_H, p[1], 0.5);
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedPositionCalibrationTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: method paneToWorld`.

- [ ] **Step 3: Umrechnung einbauen**

In `LedPositionCalibration.java` am Ende der Klasse einfügen:

```java
  // Pixel -> Meter. Liefert false, wenn der Punkt ausserhalb des Rechtecks
  // liegt, damit ein Klick ins HUD keine Position setzt; out2 bleibt dann
  // unberuehrt. Die Raender gehoeren dazu, sonst waere die aeusserste Ecke
  // der Grundflaeche nicht anklickbar.
  boolean paneToWorld(int px, int py, float[] out2) {
    if (px < paneX || px > paneX + paneW || py < paneY || py > paneY + paneH) {
      return false;
    }
    float fx = (float) (px - paneX) / (float) paneW;
    float fy = (float) (py - paneY) / (float) paneH;
    out2[0] = fx * footprintX - footprintX / 2f;
    // Y zeigt nach vorn und auf dem Schirm nach oben - hier kippt das
    // Vorzeichen, und nur hier.
    out2[1] = footprintY / 2f - fy * footprintY;
    return true;
  }

  // Meter -> Pixel, als float. imPulse.pde zeichnet damit ohne
  // Rundungssprung, und der Rundlauf mit paneToWorld bleibt pixelgenau.
  void worldToPane(float x, float y, float[] out2) {
    out2[0] = paneX + (x + footprintX / 2f) / footprintX * paneW;
    out2[1] = paneY + (footprintY / 2f - y) / footprintY * paneH;
  }
```

- [ ] **Step 4: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Commit**

```bash
git add LedPositionCalibration.java test/LedPositionCalibrationTest.java
git commit -m "LedPositionCalibration: Umrechnung Draufsicht-Flaeche und Meter"
```

---

### Task 10: Setzen, Vorschlag, Feinjustierung, Speichern, Verwerfen

Die eigentlichen Befehle des Werkzeugs. Der Vorschlag ist **kein** eigener Rechenweg: er ist `LedPositionMap.positionOf` des Eintrags, also genau das, was die Map ohnehin für diese LED sagt.

**Files:**
- Modify: `LedPositionCalibration.java`
- Modify: `test/LedPositionCalibrationTest.java`

**Interfaces:**
- Consumes: alles aus Aufgabe 8 und 9, `LedAnchorStore.set/remove/clearAll/save/lastWasWarning`, `LedPositionMap.positionOf/apply/coverageReport`, `LedNetworkNode.applyPositions`
- Produces:

```java
  // Anzeigeposition: der Anker, wenn gesetzt, sonst der Vorschlag aus der
  // Map. false, wenn beides fehlt (Stripe ohne jeden Anker).
  boolean displayPosition(float[] out2);

  boolean setCurrent(float x, float y);   // Mausklick und Ziehen
  boolean acceptProposal();               // ENTER
  boolean clearCurrent();                 // BACKSPACE
  boolean nudge(int dxSteps, int dySteps);// Pfeiltasten, in Schritten
  void    cycleStep();                    // F
  float   step();
  boolean save();                         // S
  void    reapply();                      // R
  String  coverageReport();               // T
  boolean requestClearAll(long nowMillis);// L, true wenn ausgefuehrt
  void    abortClearAll();                // jede andere Taste
  boolean mapNeedsApply();                // fuer drawMe in Aufgabe 11
  String  hudText();
```

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/LedPositionCalibrationTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- Setzen, Vorschlag, Loeschen ----
    NodeCrossingStore cs2 = crossings(new int[][] { { 10, 30 } });
    LedAnchorStore stC = store();
    LedPositionCalibration cc = build(cs2, stC);
    float[] d = new float[2];

    // Eintraege: 0, 10(Kreuzung), 19, 20, 39, 40, 59, 60, 79 -> 9
    Check.eq("neun Eintraege", 2 * STRIPES + 1, cc.entryCount());
    Check.eq("Start bei Eintrag 0 (LED 0)", 0, cc.ledsOfEntry(cc.entryIndex())[0]);

    // Ohne jeden Anker auf dem Stripe gibt es keinen Vorschlag
    Check.that("ohne Anker keine Anzeigeposition", !cc.displayPosition(d));
    Check.that("ENTER lehnt ohne Vorschlag ab", !cc.acceptProposal());
    Check.that("und begruendet es", cc.lastMessage().length() > 0);

    // Erster Klick setzt LED 0
    Check.that("Klick setzt den ersten Punkt", cc.setCurrent(-6f, -3f));
    Check.that("jetzt gibt es eine Anzeigeposition", cc.displayPosition(d));
    Check.near("Anzeige ist der gesetzte Anker, x", -6.0, d[0], 1e-4);
    Check.near("Anzeige ist der gesetzte Anker, y", -3.0, d[1], 1e-4);
    Check.that("Eintrag 0 ist gesetzt", cc.entryIsSet(0));

    // Zweiter Punkt auf demselben Stripe: die Kreuzung bei LED 10
    cc.next();
    Check.eq("jetzt bei der Kreuzung", 10, cc.ledsOfEntry(cc.entryIndex())[0]);
    // Vorschlag ist der einzige Anker des Stripes, weil noch keine Richtung
    // bekannt ist
    Check.that("Vorschlag vorhanden", cc.displayPosition(d));
    Check.near("Vorschlag ist der einzige Anker, x", -6.0, d[0], 1e-4);
    Check.that("Klick setzt die Kreuzung", cc.setCurrent(-1f, -1f));
    Check.that("die Partner-LED auf Stripe 1 wurde mitgesetzt", stC.has(30));

    // Dritter Eintrag: LED 19. Jetzt gibt es eine Richtung, der Vorschlag
    // setzt den Vektor von LED 0 -> LED 10 fort.
    // d = ((-1 - -6)/10, (-1 - -3)/10) = (0.5, 0.2) je Index
    // LED 19 -> (-1 + 9*0.5, -1 + 9*0.2) = (3.5, 0.8)
    cc.next();
    Check.eq("jetzt bei LED 19", 19, cc.ledsOfEntry(cc.entryIndex())[0]);
    Check.that("Vorschlag vorhanden", cc.displayPosition(d));
    Check.near("Vorschlag setzt den Vektor fort, x", 3.5, d[0], 1e-3);
    Check.near("Vorschlag setzt den Vektor fort, y", 0.8, d[1], 1e-3);

    // ENTER uebernimmt genau diesen Vorschlag
    Check.that("ENTER uebernimmt den Vorschlag", cc.acceptProposal());
    Check.that("LED 19 ist jetzt ein Anker", stC.has(19));
    Check.near("und zwar auf dem Vorschlagswert", 3.5, stC.x(19), 1e-3);

    // BACKSPACE nimmt den Anker weg, die Anzeige faellt auf den Vorschlag
    Check.that("BACKSPACE loescht den Anker", cc.clearCurrent());
    Check.that("LED 19 ist kein Anker mehr", !stC.has(19));
    Check.that("Anzeige faellt auf den Vorschlag zurueck", cc.displayPosition(d));
    Check.near("Vorschlag ist unveraendert", 3.5, d[0], 1e-3);

    // BACKSPACE auf einer Kreuzung raeumt beide LEDs
    LedAnchorStore stX = store();
    LedPositionCalibration cx = build(cs2, stX);
    cx.next();
    Check.eq("bei der Kreuzung", 10, cx.ledsOfEntry(cx.entryIndex())[0]);
    Check.that("Kreuzung gesetzt", cx.setCurrent(0f, 0f));
    Check.that("beide LEDs gesetzt", stX.has(10) && stX.has(30));
    Check.that("BACKSPACE auf der Kreuzung", cx.clearCurrent());
    Check.that("beide LEDs geloescht", !stX.has(10) && !stX.has(30));

    // ---- Feinjustierung und Schrittweite ----
    Check.near("Startschrittweite", 0.05, cc.step(), 1e-6);
    cc.cycleStep();
    Check.near("F schaltet auf die naechste Schrittweite", 0.25, cc.step(), 1e-6);
    cc.cycleStep();
    Check.near("F laeuft um auf die kleinste", 0.01, cc.step(), 1e-6);
    cc.cycleStep();
    Check.near("und wieder zurueck", 0.05, cc.step(), 1e-6);

    LedAnchorStore stN = store();
    LedPositionCalibration cn = build(cs2, stN);
    Check.that("Punkt setzen", cn.setCurrent(0f, 0f));
    Check.that("nach rechts schieben", cn.nudge(1, 0));
    Check.near("x um eine Schrittweite groesser", 0.05, stN.x(0), 1e-5);
    Check.that("nach oben schieben", cn.nudge(0, 1));
    Check.near("y um eine Schrittweite groesser", 0.05, stN.y(0), 1e-5);
    Check.that("nach links und unten", cn.nudge(-1, -1));
    Check.near("x wieder null", 0.0, stN.x(0), 1e-5);
    Check.near("y wieder null", 0.0, stN.y(0), 1e-5);

    // Pfeiltaste auf einem offenen Eintrag mit Vorschlag setzt ihn dabei
    cn.next();
    cn.next();
    Check.that("dritter Eintrag ist offen", !cn.entryIsSet(cn.entryIndex()));
    Check.that("Pfeiltaste auf einem offenen Eintrag setzt ihn",
        cn.nudge(1, 0) && cn.entryIsSet(cn.entryIndex()));

    // ---- L: zweimal druecken, mit Fenster ----
    LedAnchorStore stL = store();
    LedPositionCalibration cl = build(cs2, stL);
    Check.that("Punkt setzen", cl.setCurrent(1f, 1f));
    Check.eq("ein Anker", 1, stL.size());

    Check.that("erster Druck verwirft nichts", !cl.requestClearAll(10000L));
    Check.eq("Anker steht noch", 1, stL.size());
    Check.that("die Ankuendigung nennt die Anzahl",
        cl.lastMessage().indexOf("1") >= 0);

    Check.that("zweiter Druck nach 100 ms ist Tastenwiederholung",
        !cl.requestClearAll(10100L));
    Check.eq("Anker steht immer noch", 1, stL.size());

    Check.that("zweiter Druck nach 400 ms verwirft", cl.requestClearAll(10400L));
    Check.eq("Liste ist leer", 0, stL.size());

    // Abbruch durch eine andere Taste
    LedAnchorStore stL2 = store();
    LedPositionCalibration cl2 = build(cs2, stL2);
    Check.that("Punkt setzen", cl2.setCurrent(1f, 1f));
    Check.that("erster Druck", !cl2.requestClearAll(20000L));
    cl2.abortClearAll();
    Check.that("nach dem Abbruch ist der naechste Druck wieder der erste",
        !cl2.requestClearAll(20400L));
    Check.eq("nichts verworfen", 1, stL2.size());

    // Zu spaet ist wieder ein erster Druck
    LedAnchorStore stL3 = store();
    LedPositionCalibration cl3 = build(cs2, stL3);
    Check.that("Punkt setzen", cl3.setCurrent(1f, 1f));
    Check.that("erster Druck", !cl3.requestClearAll(30000L));
    Check.that("nach 6 s ist es wieder ein erster Druck",
        !cl3.requestClearAll(36000L));
    Check.eq("nichts verworfen", 1, stL3.size());

    // ---- Abdeckungsbericht und HUD ----
    LedAnchorStore stH = store();
    LedPositionCalibration ch = build(cs2, stH);
    String rep2 = ch.coverageReport();
    Check.that("Bericht nennt undefinierte LEDs", rep2.indexOf("ohne Position") >= 0);

    String hud = ch.hudText();
    Check.that("HUD nennt die Eintragszahl",
        hud.indexOf(String.valueOf(ch.entryCount())) >= 0);
    Check.that("HUD nennt die Schrittweite", hud.indexOf("Schritt") >= 0);
    Check.that("HUD nennt die Tastenbelegung", hud.indexOf("ENTER") >= 0);

    // ---- Speichern und wieder laden ----
    java.io.File posDir = java.nio.file.Files.createTempDirectory("ledpos").toFile();
    java.io.File posFile = new java.io.File(posDir, "ledPositions.txt");
    LedAnchorStore stS = store();
    LedPositionMap mS2 = new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
    ArrayList<LedNetworkNode> nS = new ArrayList<LedNetworkNode>();
    LedInNetInfo[] iS = LedInNetInfo.buildNetInfo(STRIPES, PER_STRIPE);
    LedInNetInfo.applyCrossings(cs2.crossings(), iS, nS);
    LedPositionCalibration csave = new LedPositionCalibration(stS, mS2, cs2, nS,
        STRIPES, PER_STRIPE, posFile.getAbsolutePath(),
        PANE_X, PANE_Y, PANE_W, PANE_H, FOOT_X, FOOT_Y);
    Check.that("Punkt setzen", csave.setCurrent(-2.5f, 1.25f));
    Check.that("S schreibt die Datei", csave.save());
    Check.that("die Datei existiert", posFile.exists());

    LedAnchorStore reread = store();
    reread.load(posFile.getAbsolutePath());
    Check.eq("ein Anker wieder geladen", 1, reread.size());
    Check.near("x kam unveraendert zurueck", -2.5, reread.x(0), 1e-3);
    Check.near("y kam unveraendert zurueck", 1.25, reread.y(0), 1e-3);

    // ---- R rechnet die Map neu ----
    LedAnchorStore stR = store();
    LedPositionMap mR = new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
    ArrayList<LedNetworkNode> nR = new ArrayList<LedNetworkNode>();
    LedInNetInfo[] iR = LedInNetInfo.buildNetInfo(STRIPES, PER_STRIPE);
    LedInNetInfo.applyCrossings(cs2.crossings(), iR, nR);
    LedPositionCalibration cr = new LedPositionCalibration(stR, mR, cs2, nR,
        STRIPES, PER_STRIPE, NO_FILE,
        PANE_X, PANE_Y, PANE_W, PANE_H, FOOT_X, FOOT_Y);
    Check.that("Punkt setzen", cr.setCurrent(2f, 2f));
    cr.reapply();
    Check.that("die Map kennt die Position jetzt", mR.isDefined(0));
    Check.near("und der Knoten seine", 2.0, nR.get(0).posX, 1.0);
    Check.that("nach reapply ist nichts mehr offen anzuwenden", !cr.mapNeedsApply());
    Check.that("ein neuer Klick macht die Map wieder schmutzig",
        cr.setCurrent(2.5f, 2.5f) && cr.mapNeedsApply());
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedPositionCalibrationTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: method displayPosition`.

- [ ] **Step 3: Die Befehle einbauen**

In `LedPositionCalibration.java` die Felder oben ergänzen:

```java
  private int stepIndex = 1;               // Start bei 0.05 m
  private boolean clearAllPending = false;
  private long clearAllArmedAt = 0;
  // Die Map wird nicht bei jedem Frame neu gerechnet, sondern nur wenn sich
  // ein Anker geaendert hat. 18 000 LEDs mal mehrere Baumsuchen bei 40 Hz
  // waere Verschwendung, und die Rueckmeldung im Netz soll trotzdem sofort
  // nach jedem Klick stimmen.
  private boolean mapDirty = true;
```

Und die Methoden am Ende der Klasse:

```java
  float step() { return STEP_SIZES_M[stepIndex]; }

  void cycleStep() {
    stepIndex = (stepIndex + 1) % STEP_SIZES_M.length;
    message = "Schrittweite " + step() + " m";
  }

  boolean mapNeedsApply() { return mapDirty; }

  // Der Anker, wenn dieser Eintrag gesetzt ist, sonst der Vorschlag aus der
  // Map. Beides kommt aus LedPositionMap.positionOf - es gibt keinen zweiten
  // Rechenweg fuer "geschaetzte" Positionen.
  boolean displayPosition(float[] out2) {
    if (entries.isEmpty()) {
		return false;
	}
    return map.positionOf(store, entries.get(current)[0], out2);
  }

  // Setzt die Position des aktuellen Eintrags. set() verteilt sie innerhalb
  // eines Knotens auf alle beteiligten LEDs, ein Klick genuegt also fuer
  // beide Seiten einer Kreuzung.
  boolean setCurrent(float x, float y) {
    if (entries.isEmpty()) {
      message = "Keine Eintraege";
      return false;
    }
    boolean ok = store.set(entries.get(current)[0], x, y, crossingStore.crossings());
    message = store.lastMessage();
    // Die HUD-Zeile kann vor Ort unlesbar sein - Ablehnung und Warnung
    // deshalb zusaetzlich auf die Konsole, wie es NodeCalibration bei ENTER
    // auch tut.
    if (!ok || store.lastWasWarning()) {
      System.out.println("Position: " + message);
    }
    if (ok) {
		mapDirty = true;
	}
    return ok;
  }

  boolean acceptProposal() {
    float[] out = new float[2];
    if (!displayPosition(out)) {
      message = "Kein Vorschlag moeglich - dieser Stripe hat noch keinen Anker";
      System.out.println("Position: " + message);
      return false;
    }
    return setCurrent(out[0], out[1]);
  }

  // Nimmt die Anker ALLER LEDs des Eintrags weg, nicht nur die der ersten -
  // sonst blieb bei einer Kreuzung die halbe Position stehen.
  boolean clearCurrent() {
    if (entries.isEmpty()) {
      message = "Keine Eintraege";
      return false;
    }
    boolean any = false;
    for (int led : entries.get(current)) {
      if (store.remove(led)) {
		any = true;
	  }
    }
    message = any ? store.lastMessage() : "Dieser Eintrag hat keinen Anker";
    if (any) {
		mapDirty = true;
	}
    return any;
  }

  // Verschiebt die Anzeigeposition um Schrittweiten. Steht der Eintrag noch
  // auf einem Vorschlag, wird er dadurch zum Anker - genau das will man,
  // wenn man einen Vorschlag nur ein Stueck nachbessern muss.
  boolean nudge(int dxSteps, int dySteps) {
    float[] out = new float[2];
    if (!displayPosition(out)) {
      message = "Kein Vorschlag moeglich - dieser Stripe hat noch keinen Anker";
      return false;
    }
    return setCurrent(out[0] + dxSteps * step(), out[1] + dySteps * step());
  }

  boolean save() {
    try {
      store.save(filePath);
      message = store.lastMessage();
      return true;
    } catch (java.io.IOException e) {
      message = "Speichern fehlgeschlagen: " + e;
      System.out.println("Position: " + message);
      return false;
    }
  }

  // Rechnet Map und Knotenpositionen neu und uebernimmt sie in die laufende
  // Simulation, ohne Neustart. Baut ausserdem die Arbeitsliste neu auf, damit
  // im Kalibriermodus aufgenommene Kreuzungen auftauchen.
  void reapply() {
    rebuildWorklist();
    map.apply(store);
    LedNetworkNode.applyPositions(map, nodes);
    mapDirty = false;
    message = entries.size() + " Eintraege, " + openCount() + " offen; "
        + map.coverageReport(store);
  }

  String coverageReport() {
    if (mapDirty) {
      map.apply(store);
      LedNetworkNode.applyPositions(map, nodes);
      mapDirty = false;
    }
    String rep = map.coverageReport(store);
    message = rep;
    System.out.println("Abdeckung: " + rep);
    return rep;
  }

  // Verwerfen ALLER Anker, auch der geladenen. Erster Druck kuendigt an, ein
  // zweiter zwischen 300 ms und 5 s danach fuehrt aus. Die Untergrenze wehrt
  // Tastenwiederholung bei gehaltenem L ab; die angekuendigte Bestaetigung
  // wird dabei NICHT erneuert, sonst haelt ein gedruecktes L das Fenster
  // endlos offen.
  boolean requestClearAll(long nowMillis) {
    long sinceArmed = nowMillis - clearAllArmedAt;
    if (clearAllPending && sinceArmed < CLEAR_ALL_CONFIRM_MIN_MILLIS) {
      return false;
    }
    if (clearAllPending && sinceArmed <= CLEAR_ALL_CONFIRM_MAX_MILLIS) {
      store.clearAll();
      message = store.lastMessage();
      System.out.println("Position: " + message);
      clearAllPending = false;
      mapDirty = true;
      return true;
    }
    clearAllPending = true;
    clearAllArmedAt = nowMillis;
    message = "Achtung: " + store.size() + " Positionen werden verworfen (auch geladene) - "
        + "L erneut druecken zum Bestaetigen";
    System.out.println("Position: " + message);
    return false;
  }

  void abortClearAll() { clearAllPending = false; }

  String hudText() {
    float[] out = new float[2];
    boolean known = displayPosition(out);
    String pos = known
        ? String.format(java.util.Locale.US, "x %+6.2f  y %+6.2f m", Float.valueOf(out[0]),
            Float.valueOf(out[1]))
        : "keine Position";
    return String.format(java.util.Locale.US,
        "Eintrag %d/%d  %s  %s  %s%n"
        + "Positionen: %d geladen + %d neu    offen: %d    Schritt: %.2f m%n"
        + "%s%n"
        + "Maus setzen  ENTER Vorschlag  BACKSPACE loeschen  Pfeile feinjustieren  F Schritt%n"
        + ", . blaettern  o naechster offener  S schreiben  R uebernehmen  T Abdeckung  "
        + "L alles verwerfen  P beenden",
        Integer.valueOf(current + 1), Integer.valueOf(entries.size()),
        entryIsCrossing(current) ? "Kreuzung" : "Stripe-Ende",
        entryIsSet(current) ? "gesetzt" : "offen",
        pos,
        Integer.valueOf(store.loadedCount()), Integer.valueOf(store.sessionCount()),
        Integer.valueOf(openCount()), Float.valueOf(step()),
        message);
  }
```

- [ ] **Step 4: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Commit**

```bash
git add LedPositionCalibration.java test/LedPositionCalibrationTest.java
git commit -m "LedPositionCalibration: Setzen, Vorschlag, Feinjustierung, Speichern"
```

---

### Task 11: Rückmeldung im Netz

Am Netz selbst soll zu sehen sein, wo noch Arbeit liegt. Drei Regeln, spätere überschreiben frühere.

**Files:**
- Modify: `LedPositionCalibration.java`
- Modify: `test/LedPositionCalibrationTest.java`

**Interfaces:**
- Consumes: alles aus Aufgabe 8 bis 10
- Produces:

```java
  LedColor[] drawMe();               // ruft drawMe(System.currentTimeMillis())
  LedColor[] drawMe(long nowMillis); // fuer den Test, damit der Blinktakt pruefbar ist
```

`LedColor` ist erlaubt: die Klasse steht in `test/run.sh`s `SOURCES` und `core.jar` liegt beim Übersetzen auf dem Klassenpfad. Nur `oscP5` und `netP5` fehlen dort.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/LedPositionCalibrationTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- Rueckmeldung im Netz ----
    // Kreuzung {10, 30} ist Eintrag 1. Stripe 0 bekommt drei Anker (4, 10, 14),
    // damit dort interpoliert wird; Stripe 1 bekommt ueber die Kreuzung genau
    // einen (30), sodass dort alles ausser dem Anker selbst als extrapoliert
    // gilt; Stripe 2 und 3 bleiben ohne Anker und damit dunkel. So sind alle
    // drei Zustaende der Map in einem Aufbau vertreten.
    LedAnchorStore stD = store();
    LedPositionMap mD = new LedPositionMap(STRIPES, PER_STRIPE, FOOT_X, FOOT_Y);
    ArrayList<LedNetworkNode> nD = new ArrayList<LedNetworkNode>();
    LedInNetInfo[] iD = LedInNetInfo.buildNetInfo(STRIPES, PER_STRIPE);
    LedInNetInfo.applyCrossings(cs2.crossings(), iD, nD);
    LedPositionCalibration cd = new LedPositionCalibration(stD, mD, cs2, nD,
        STRIPES, PER_STRIPE, NO_FILE,
        PANE_X, PANE_Y, PANE_W, PANE_H, FOOT_X, FOOT_Y);

    Check.that("Anker LED 4", stD.set(4, -3f, -1f, cs2.crossings()));
    Check.that("Anker LED 14", stD.set(14, 2f, 1f, cs2.crossings()));
    // Der Kreuzungsanker setzt LED 10 auf Stripe 0 UND LED 30 auf Stripe 1.
    // Ohne ihn haette Stripe 1 gar keinen Anker und die Pruefungen auf rot und
    // blau unten haetten kein Ziel. Die Werte sind so gewaehlt, dass die
    // Weglaengen-Warnung nicht anspringt: 4 -> 10 sind 3,0 m Weg bei 3,42 m
    // Luftlinie (Schwelle 3,5), 10 -> 14 sind 2,0 m Weg bei 1,97 m Luftlinie.
    Check.that("Kreuzungsanker LED 10", stD.set(10, 0.2f, 0.2f, cs2.crossings()));
    Check.that("die Partner-LED auf Stripe 1 ist mitgesetzt", stD.has(30));
    Check.that("keine Weglaengen-Warnung in diesem Aufbau", !stD.lastWasWarning());

    // Zeiger auf Eintrag 0 (LED 0, Stripe-Ende von Stripe 0)
    Check.eq("Zeiger auf Eintrag 0", 0, cd.entryIndex());

    // Blinkphase AN: Vielfaches von 800 ms
    LedColor[] buf = cd.drawMe(0L);
    Check.eq("Puffer hat die Groesse des Netzes", STRIPES * PER_STRIPE, buf.length);
    Check.that("die LED des Eintrags leuchtet in der An-Phase weiss",
        buf[0].x > 0.9f && buf[0].y > 0.9f && buf[0].z > 0.9f);

    // Blinkphase AUS
    LedColor[] bufOff = cd.drawMe(500L);
    Check.that("in der Aus-Phase ist sie nicht weiss",
        !(bufOff[0].x > 0.9f && bufOff[0].y > 0.9f && bufOff[0].z > 0.9f));

    // Stripe 0 traegt die LED des Eintrags -> gruen geglommen. LED 8 liegt
    // zwischen den Ankern 4 und 14, wird aber vom Gruen ueberschrieben.
    Check.that("der Stripe des Eintrags glimmt gruen",
        bufOff[8].y > 0f && bufOff[8].x == 0f && bufOff[8].z == 0f);

    // Stripe 1 hat durch die Kreuzung genau einen Anker (LED 30, oben schon
    // geprueft und seither unveraendert): alles ausser LED 30 gilt als
    // extrapoliert -> rot.
    Check.that("extrapolierte LEDs glimmen rot",
        bufOff[25].x > 0f && bufOff[25].y == 0f && bufOff[25].z == 0f);
    Check.that("der Anker selbst gilt als gestuetzt und glimmt blau",
        bufOff[30].z > 0f && bufOff[30].x == 0f && bufOff[30].y == 0f);

    // Stripe 2 und 3 haben keinen Anker -> dunkel
    Check.that("ohne Anker bleibt es dunkel",
        bufOff[2 * PER_STRIPE + 5].x == 0f
        && bufOff[2 * PER_STRIPE + 5].y == 0f
        && bufOff[2 * PER_STRIPE + 5].z == 0f);

    // Auf der Kreuzung blinken BEIDE LEDs, auf zwei verschiedenen Stripes
    cd.next();
    Check.that("jetzt bei der Kreuzung", cd.entryIsCrossing(cd.entryIndex()));
    LedColor[] bufX = cd.drawMe(0L);
    Check.that("erste LED der Kreuzung blinkt weiss",
        bufX[10].x > 0.9f && bufX[10].y > 0.9f && bufX[10].z > 0.9f);
    Check.that("zweite LED der Kreuzung blinkt ebenfalls weiss",
        bufX[30].x > 0.9f && bufX[30].y > 0.9f && bufX[30].z > 0.9f);

    // Beide beteiligten Stripes glimmen gruen
    LedColor[] bufX2 = cd.drawMe(500L);
    Check.that("Stripe 0 glimmt gruen", bufX2[3].y > 0f && bufX2[3].x == 0f);
    Check.that("Stripe 1 glimmt auch gruen",
        bufX2[PER_STRIPE + 3].y > 0f && bufX2[PER_STRIPE + 3].x == 0f);

    // Alle Helligkeiten bleiben im Bereich 0..1. drawMe(long) liefert immer
    // denselben geteilten Puffer zurueck - bufX ist also laengst von bufX2
    // ueberschrieben. Frisch die An-Phase holen, sonst prueft die Schleife
    // nur noch Aus-Phase-Werte (0 oder DIM) und kann nie rot werden.
    LedColor[] bufOn = cd.drawMe(0L);
    float maxComp = 0f;
    for (int i = 0; i < bufOn.length; i++) {
      maxComp = Math.max(maxComp, Math.max(bufOn[i].x, Math.max(bufOn[i].y, bufOn[i].z)));
    }
    Check.that("keine Komponente ueber 1", maxComp <= 1.0f);
    Check.that("die weisse An-Phase kam tatsaechlich im Puffer an",
        maxComp >= 1.0f - 1e-6f);

    // ---- drawMe() ohne Argument delegiert an drawMe(System.currentTimeMillis()) ----
    Check.eq("no-arg drawMe liefert einen vollen Puffer",
        STRIPES * PER_STRIPE, cd.drawMe().length);
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh LedPositionCalibrationTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: method drawMe(long)`.

- [ ] **Step 3: `drawMe` einbauen**

Das Feld oben bei den anderen ergänzen:

```java
  private final LedColor[] buffer;
```

Im Konstruktor **vor** `rebuildWorklist()` anlegen:

```java
    this.buffer = LedColor.createColorArray(numStripes * numLedsPerStripe);
```

Und die Methoden am Ende der Klasse:

```java
  LedColor[] drawMe() { return drawMe(System.currentTimeMillis()); }

  // Drei Regeln, spaetere ueberschreiben fruehere:
  //   1. jede LED zeigt den Zustand der Map - blau gestuetzt, rot geraten,
  //      dunkel unbekannt
  //   2. jeder Stripe, der eine LED des aktuellen Eintrags traegt, glimmt
  //      gruen; sonst waere er von den anderen nicht zu unterscheiden
  //   3. alle LEDs des aktuellen Eintrags blinken weiss - an einer Kreuzung
  //      markieren damit zwei LEDs denselben physischen Punkt
  LedColor[] drawMe(long nowMillis) {
    if (mapDirty) {
      map.apply(store);
      LedNetworkNode.applyPositions(map, nodes);
      mapDirty = false;
    }

    for (int i = 0; i < buffer.length; i++) {
      if (!map.isDefined(i)) {
        buffer[i].set(0f, 0f, 0f);
      } else if (map.isInterpolated(i)) {
        buffer[i].set(0f, 0f, DIM);
      } else {
        buffer[i].set(DIM, 0f, 0f);
      }
    }

    if (!entries.isEmpty()) {
      int[] leds = entries.get(current);
      for (int led : leds) {
        int base = (led / numLedsPerStripe) * numLedsPerStripe;
        for (int i = 0; i < numLedsPerStripe; i++) {
          buffer[base + i].set(0f, DIM, 0f);
        }
      }
      if (nowMillis % (2 * BLINK_MILLIS) < BLINK_MILLIS) {
        for (int led : leds) {
          buffer[led].set(1f, 1f, 1f);
        }
      }
    }
    return buffer;
  }
```

- [ ] **Step 4: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Commit**

```bash
git add LedPositionCalibration.java test/LedPositionCalibrationTest.java
git commit -m "LedPositionCalibration: Abdeckung am Netz sichtbar machen"
```

---

### Task 12: `imPulse.pde` verdrahten und die Draufsicht-Fläche zeichnen

Die einzige Aufgabe der Stufe, deren Ergebnis kein Test beurteilt. `test/build.sh` fängt Übersetzungsfehler, das Aussehen muss ein Mensch ansehen.

**Files:**
- Modify: `imPulse.pde`
- Create: `data/ledPositions.txt`

**Interfaces:**
- Consumes: alles aus Aufgabe 2 bis 11
- Produces: die Felder `positionMode`, `ledAnchorStore`, `ledPositionMap`, `ledPositionCalibration` und die Konstanten aus dem Abschnitt „Zahlen, wörtlich aus der Spec"

- [ ] **Step 1: Konstanten und Felder ergänzen**

In `imPulse.pde` bei den übrigen Hardware-Konstanten, direkt nach `int numLeds = ...`:

```java
// Draufsicht der Installation, am 2026-07-30 mit dem Betreiber festgelegt.
// Ursprung senkrecht unter der Netzmitte, X nach rechts, Y nach vorn.
// Installationsspezifisch - nicht aendern, ohne dass es um einen konkreten
// Aufbau geht.
float footprintX = 14f;                      // Meter
float footprintY = 8f;                       // Meter
float stripeLengthM = 10f;                   // 2 x 5 m, durchgehend verbunden
float ledPitchM = stripeLengthM / numLedsPerStripe;   // 0.0166667 m

// Draufsicht-Flaeche im Positionsmodus. 525:300 entspricht 14:8 genau, es
// gibt also keine Verzerrung; ein Pixel sind 2,67 cm.
int paneX = 0, paneY = 0, paneW = 525, paneH = 300;

LedAnchorStore ledAnchorStore;
LedPositionMap ledPositionMap;
LedPositionCalibration ledPositionCalibration;
boolean positionMode = false;
```

- [ ] **Step 2: In `setup()` aufbauen**

In `imPulse.pde` direkt **nach** dem vorhandenen `LedInNetInfo.applyCrossings(...)` und **vor** `nodeCalibration = new NodeCalibration(...)`:

```java
  ledAnchorStore = new LedAnchorStore(numStripes, numLedsPerStripe,
      footprintX, footprintY, ledPitchM);
  ledAnchorStore.load(dataPath("ledPositions.txt"));
  System.out.println(ledAnchorStore.lastMessage());
  ledPositionMap = new LedPositionMap(numStripes, numLedsPerStripe, footprintX, footprintY);
  ledPositionMap.apply(ledAnchorStore);
  LedNetworkNode.applyPositions(ledPositionMap, listOfNodes);
  // Eine Warnzeile, wenn Positionen fehlen. Die Show laeuft dann wie bisher,
  // nur ohne Raumbezug - jede Koordinate ist (0,0), also die Netzmitte.
  if (ledPositionMap.undefinedCount() > 0) {
    System.out.println("WARNUNG: " + ledPositionMap.coverageReport(ledAnchorStore));
    System.out.println("WARNUNG: diese LEDs senden (0,0) als Klangposition. "
        + "Positionen mit P aufnehmen, siehe docs/positionen-anleitung.md");
  }
  ledPositionCalibration = new LedPositionCalibration(ledAnchorStore, ledPositionMap,
      crossingStore, listOfNodes, numStripes, numLedsPerStripe,
      dataPath("ledPositions.txt"), paneX, paneY, paneW, paneH, footprintX, footprintY);
```

- [ ] **Step 3: `draw()` erweitern**

Den `if (calibrationMode)`-Block in `draw()` durch diesen ersetzen:

```java
  if (calibrationMode) {
    nodeCalibration.update();
    ledColors = nodeCalibration.drawMe();
  } else if (positionMode) {
    ledColors = ledPositionCalibration.drawMe();
  } else {
    ledColors = mixer.mix();
  }
```

Und den Anzeigeteil nach `drawLedColorsToCanvas()` so:

```java
  drawLedColorsToCanvas();
  if (positionMode) {
    // Im Positionsmodus belegt die Draufsicht-Flaeche den Bereich links, die
    // verkleinerte LED-Vorschau sitzt rechts daneben.
    background(0);
    drawPositionPane();
    image(canvas, 560, 0, 600, 120);
    fill(255);
    text(ledPositionCalibration.hudText(), 10, paneY + paneH + 20);
  } else {
    image(canvas, 0, 0, numLedsPerStripe*2, numStripes*10);
    if (calibrationMode) {
      fill(0);
      noStroke();
      rect(0, numStripes * 10, width, height - numStripes * 10);
      fill(255);
      text(nodeCalibration.hudText(), 10, numStripes * 10 + 20);
    }
  }
```

- [ ] **Step 4: Die Fläche zeichnen**

Neue Funktion in `imPulse.pde`, nach `drawLedColorsToCanvas()`:

```java
// Zeichnet die Draufsicht: Raster, Lautsprecher, gesetzte Anker, den Verlauf
// des aktuellen Stripes und den aktuellen Eintrag. Die Umrechnung kommt aus
// LedPositionCalibration.worldToPane, damit gezeichneter Punkt und
// angeklickte Stelle dieselbe Rechnung benutzen.
void drawPositionPane() {
  float[] p = new float[2];
  float[] q = new float[2];

  noFill();
  stroke(60);
  strokeWeight(1);
  rect(paneX, paneY, paneW, paneH);
  // 1-m-Raster
  for (float mx = -footprintX/2 + 1; mx < footprintX/2; mx += 1) {
    ledPositionCalibration.worldToPane(mx, 0, p);
    line(p[0], paneY, p[0], paneY + paneH);
  }
  for (float my = -footprintY/2 + 1; my < footprintY/2; my += 1) {
    ledPositionCalibration.worldToPane(0, my, p);
    line(paneX, p[1], paneX + paneW, p[1]);
  }

  // Die vier Lautsprecher auf den Seitenmitten
  float[][] speakers = { {0, footprintY/2}, {footprintX/2, 0},
                         {0, -footprintY/2}, {-footprintX/2, 0} };
  noStroke();
  fill(200, 160, 0);
  for (int i = 0; i < speakers.length; i++) {
    ledPositionCalibration.worldToPane(speakers[i][0], speakers[i][1], p);
    rect(p[0] - 4, p[1] - 4, 8, 8);
  }

  // Verlauf des aktuellen Stripes ueber alle seine Anker
  int cur = ledPositionCalibration.entryIndex();
  if (cur >= 0) {
    int firstLed = ledPositionCalibration.ledsOfEntry(cur)[0];
    int stripe = firstLed / numLedsPerStripe;
    stroke(0, 120, 200);
    noFill();
    int prev = -1;
    for (int i = 0; i < numLedsPerStripe; i += 10) {
      int idx = stripe * numLedsPerStripe + i;
      if (!ledPositionMap.isDefined(idx)) { prev = -1; continue; }
      ledPositionCalibration.worldToPane(ledPositionMap.x(idx), ledPositionMap.y(idx), p);
      if (prev >= 0) {
        ledPositionCalibration.worldToPane(ledPositionMap.x(prev), ledPositionMap.y(prev), q);
        line(q[0], q[1], p[0], p[1]);
      }
      prev = idx;
    }
  }

  // Alle gesetzten Anker
  noStroke();
  fill(120);
  for (int e = 0; e < ledPositionCalibration.entryCount(); e++) {
    if (!ledPositionCalibration.entryIsSet(e)) { continue; }
    int led = ledPositionCalibration.ledsOfEntry(e)[0];
    ledPositionCalibration.worldToPane(ledAnchorStore.x(led), ledAnchorStore.y(led), p);
    ellipse(p[0], p[1], 5, 5);
  }

  // Der aktuelle Eintrag: gefuellt wenn gesetzt, hohl wenn nur Vorschlag
  if (ledPositionCalibration.displayPosition(p)) {
    float wx = p[0], wy = p[1];
    ledPositionCalibration.worldToPane(wx, wy, p);
    stroke(255);
    strokeWeight(2);
    if (ledPositionCalibration.entryIsSet(cur)) {
      fill(255);
    } else {
      noFill();
    }
    ellipse(p[0], p[1], 13, 13);
    strokeWeight(1);
  }
}
```

- [ ] **Step 5: Maus und Tasten anschliessen**

Neue Funktionen in `imPulse.pde`:

```java
void mousePressed() { positionClick(); }
void mouseDragged() { positionClick(); }

// Ein Klick oder Ziehen in der Draufsicht-Flaeche setzt die Position des
// aktuellen Eintrags. Klicks ausserhalb - etwa ins HUD - werden verworfen,
// darum kuemmert sich paneToWorld.
void positionClick() {
  if (!positionMode) { return; }
  float[] w = new float[2];
  if (ledPositionCalibration.paneToWorld(mouseX, mouseY, w)) {
    ledPositionCalibration.setCurrent(w[0], w[1]);
  }
}
```

`keyPressed()` erweitern, damit die Pfeiltasten im Positionsmodus ankommen:

```java
void keyPressed() {
  if (calibrationMode && key == CODED) {
    nodeCalibration.handleKeyPressed(keyCode, key);
  } else if (positionMode && key == CODED) {
    // Pfeil hoch bewegt nach vorn, also nach +Y und auf dem Schirm nach oben
    if (keyCode == LEFT)  { ledPositionCalibration.nudge(-1, 0); }
    if (keyCode == RIGHT) { ledPositionCalibration.nudge(1, 0); }
    if (keyCode == UP)    { ledPositionCalibration.nudge(0, 1); }
    if (keyCode == DOWN)  { ledPositionCalibration.nudge(0, -1); }
    ledPositionCalibration.abortClearAll();
  }
}
```

`keyReleased()` erweitern. Der gegenseitige Ausschluss ist wichtig: beide Werkzeuge belegen `,` `.` `S` `R` `F` `L`.

```java
void keyReleased() {
  if (key == 'c' || key == 'C') {
    calibrationMode = !calibrationMode;
    if (calibrationMode) { positionMode = false; }   // beide Modi belegen dieselben Tasten
    println(calibrationMode ? "Kalibriermodus an" : "Kalibriermodus aus");
    nodeCalibration.handleKeyReleased();
    nodeCalibration.setPattern(0);
    return;
  }
  if (key == 'p' || key == 'P') {
    positionMode = !positionMode;
    if (positionMode) { calibrationMode = false; }
    println(positionMode ? "Positionsmodus an" : "Positionsmodus aus");
    ledPositionCalibration.abortClearAll();
    if (positionMode) {
      // Die Kreuzungsliste kann sich im Kalibriermodus geaendert haben
      ledPositionCalibration.reapply();
      println(ledPositionCalibration.lastMessage());
    }
    return;
  }
  if (positionMode) {
    if (key != 'l' && key != 'L') { ledPositionCalibration.abortClearAll(); }
    if (key == ',') { ledPositionCalibration.prev(); }
    else if (key == '.') { ledPositionCalibration.next(); }
    else if (key == 'o' || key == 'O') { ledPositionCalibration.nextOpen(); }
    else if (key == '\n' || key == '\r') { ledPositionCalibration.acceptProposal(); }
    else if (key == 8 || key == 127) { ledPositionCalibration.clearCurrent(); }
    else if (key == 'f' || key == 'F') { ledPositionCalibration.cycleStep(); }
    else if (key == 's' || key == 'S') { ledPositionCalibration.save(); }
    else if (key == 'r' || key == 'R') { ledPositionCalibration.reapply(); }
    else if (key == 't' || key == 'T') { ledPositionCalibration.coverageReport(); }
    else if (key == 'l' || key == 'L') {
      ledPositionCalibration.requestClearAll(System.currentTimeMillis());
    }
    return;
  }
  if (!calibrationMode) { return; }
  if (key == CODED) {
    nodeCalibration.handleKeyReleased();
  } else {
    nodeCalibration.handleCommand(key);
  }
}
```

- [ ] **Step 6: Positionsdatei anlegen**

Neue Datei `data/ledPositions.txt` — nur der Kopfkommentar, damit klar ist, was das Format ist:

```
# LED-Positionen in der Draufsicht. Eine Zeile je Anker:
#   ledIndex  x[m]  y[m]
# Grundflaeche 14 x 8 m, Ursprung senkrecht unter der Netzmitte,
# X nach rechts, Y nach vorn. Aufgenommen mit dem Positionsmodus (Taste P),
# siehe docs/positionen-anleitung.md
```

- [ ] **Step 7: Übersetzung prüfen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

Wenn `test/build.sh` `duplicate method mousePressed` meldet: `imPulse.pde` hatte schon eine — dann die vorhandene erweitern statt eine zweite anzulegen. Vorher prüfen mit `grep -n "void mousePressed\|void mouseDragged\|void keyPressed\|void keyReleased" imPulse.pde`.

- [ ] **Step 8: Commit**

```bash
git add imPulse.pde data/ledPositions.txt
git commit -m "Positionsmodus in den Sketch verdrahten, Draufsicht-Flaeche zeichnen"
```

**Hinweis für den Prüfer:** das Aussehen ist hier nicht automatisch geprüft. Nach dem Commit den Sketch in Processing 3 öffnen, `P` drücken und ansehen: Raster, vier Lautsprechermarken, Ring am aktuellen Eintrag, blinkende LED im Netz. Der Master-Pegel bleibt bei 0.1.

---
# Stufe 3 — OSC und Vierkanal-Klang

Danach lauffähig: die Spatialisierung ist hörbar. Diese Stufe hängt nur an Stufe 1, nicht an Stufe 2 — sie lässt sich mit einer von Hand geschriebenen `data/ledPositions.txt` prüfen, bevor das Werkzeug fertig ist.

### Task 13: `ImpulseOscThrottle` — Sendetakt und Auswahl

`LedNetworkTransportEffect` hängt an `oscP5` und lässt sich von `test/run.sh` nicht übersetzen. Die Spec listet in Abschnitt 9 aber nur die Ambisonic-Kette und die Zeichenarbeit als ungeprüft — die Drosselung soll geprüft sein. Also wandert die Entscheidung in eine eigene, abhängigkeitsfreie Klasse. Der Effekt behält nur das Zusammenbauen und Absenden der Nachricht.

**Files:**
- Create: `ImpulseOscThrottle.java`
- Create: `test/ImpulseOscThrottleTest.java`
- Modify: `test/run.sh:15-19` (SOURCES) und `:27` (Default-Suitenliste)

**Interfaces:**
- Consumes: nichts
- Produces:

```java
class ImpulseOscThrottle {
  boolean due(double nowSeconds, float rateHz);
  int[]   select(float[] energies, int maxCount);
}
```

Aufgabe 15 ruft beide.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Neue Datei `test/ImpulseOscThrottleTest.java`:

```java
public class ImpulseOscThrottleTest {

  public static void main(String[] args) throws Exception {
    // ---- Sendetakt ----
    ImpulseOscThrottle t = new ImpulseOscThrottle();

    // Rate 0 schaltet den Strom ab - der Notausgang waehrend der Show
    Check.that("bei Rate 0 ist nie ein Takt faellig", !t.due(100.0, 0f));
    Check.that("auch spaeter nicht", !t.due(200.0, 0f));
    Check.that("negative Rate ebenfalls nicht", !t.due(300.0, -5f));

    // Der erste Aufruf mit einer Rate ist faellig
    ImpulseOscThrottle t2 = new ImpulseOscThrottle();
    Check.that("erster Aufruf ist faellig", t2.due(100.0, 10f));
    Check.that("unmittelbar danach nicht", !t2.due(100.01, 10f));
    Check.that("kurz vor dem Intervall nicht", !t2.due(100.09, 10f));
    Check.that("genau auf dem Intervall wieder", t2.due(100.10, 10f));
    Check.that("und danach wieder nicht", !t2.due(100.15, 10f));

    // Nach einer langen Pause gibt es EINEN Takt, keinen Schwall
    ImpulseOscThrottle t3 = new ImpulseOscThrottle();
    Check.that("erster Takt", t3.due(0.0, 10f));
    Check.that("nach zehn Sekunden Pause ein Takt", t3.due(10.0, 10f));
    Check.that("aber nicht sofort noch einer", !t3.due(10.01, 10f));

    // Abschalten und wieder einschalten: der naechste Takt kommt sofort
    ImpulseOscThrottle t4 = new ImpulseOscThrottle();
    Check.that("erster Takt", t4.due(0.0, 10f));
    Check.that("abgeschaltet", !t4.due(0.5, 0f));
    Check.that("wieder eingeschaltet ist sofort ein Takt faellig", t4.due(0.51, 10f));

    // Hoehere Rate, kuerzeres Intervall
    ImpulseOscThrottle t5 = new ImpulseOscThrottle();
    Check.that("erster Takt bei 40 Hz", t5.due(0.0, 40f));
    Check.that("nach 20 ms noch nicht", !t5.due(0.020, 40f));
    Check.that("nach 25 ms schon", t5.due(0.025, 40f));

    // ---- Auswahl der energiereichsten ----
    ImpulseOscThrottle sel = new ImpulseOscThrottle();

    Check.eq("maxCount 0 waehlt nichts", 0, sel.select(new float[] { 1f, 2f }, 0).length);
    Check.eq("negatives maxCount waehlt nichts",
        0, sel.select(new float[] { 1f, 2f }, -3).length);
    Check.eq("null waehlt nichts", 0, sel.select(null, 5).length);
    Check.eq("leere Liste waehlt nichts", 0, sel.select(new float[0], 5).length);

    float[] e = { 0.1f, 0.9f, 0.5f, 0.3f, 0.7f };
    int[] top2 = sel.select(e, 2);
    Check.eq("zwei ausgewaehlt", 2, top2.length);
    Check.eq("der energiereichste zuerst", 1, top2[0]);
    Check.eq("dann der zweitstaerkste", 4, top2[1]);

    int[] top3 = sel.select(e, 3);
    Check.eq("drei ausgewaehlt", 3, top3.length);
    Check.eq("dritter ist Index 2", 2, top3[2]);

    // maxCount groesser als die Liste liefert alle, absteigend sortiert
    int[] all = sel.select(e, 99);
    Check.eq("alle ausgewaehlt", 5, all.length);
    Check.eq("Reihenfolge absteigend, Platz 1", 1, all[0]);
    Check.eq("Platz 2", 4, all[1]);
    Check.eq("Platz 3", 2, all[2]);
    Check.eq("Platz 4", 3, all[3]);
    Check.eq("Platz 5", 0, all[4]);

    // Gleichstand: der kleinere Index gewinnt, damit die Auswahl
    // reproduzierbar ist und nicht bei jedem Frame flackert
    float[] tie = { 0.5f, 0.9f, 0.9f, 0.2f };
    int[] tieTop = sel.select(tie, 2);
    Check.eq("bei Gleichstand gewinnt der kleinere Index", 1, tieTop[0]);
    Check.eq("dann der naechste", 2, tieTop[1]);

    // Alle gleich: die Reihenfolge ist die der Indizes
    float[] same = { 1f, 1f, 1f };
    int[] sameSel = sel.select(same, 3);
    Check.eq("alle gleich, erster Index", 0, sameSel[0]);
    Check.eq("alle gleich, zweiter Index", 1, sameSel[1]);
    Check.eq("alle gleich, dritter Index", 2, sameSel[2]);

    // Negative Energien kommen zuletzt, stuerzen aber nicht ab
    float[] neg = { -1f, 0.5f };
    int[] negSel = sel.select(neg, 2);
    Check.eq("positive Energie zuerst", 1, negSel[0]);
    Check.eq("negative danach", 0, negSel[1]);

    // select() liest nur - das Feld des Aufrufers bleibt unveraendert
    float[] untouched = { 0.4f, 0.1f, 0.9f, 0.2f };
    float[] untouchedCopy = untouched.clone();
    sel.select(untouched, 2);
    for (int i = 0; i < untouched.length; i++) {
      Check.near("select() veraendert das Eingabearray nicht an Index " + i,
          untouchedCopy[i], untouched[i], 0.0);
    }

    System.exit(Check.report("ImpulseOscThrottleTest"));
  }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh ImpulseOscThrottleTest`
Expected: FEHLSCHLAG bei der Übersetzung, `cannot find symbol: class ImpulseOscThrottle`.

Hinweis: `run.sh` übersetzt `ImpulseOscThrottle.java` erst, wenn es in `SOURCES` steht — das passiert in Step 4.

- [ ] **Step 3: `ImpulseOscThrottle.java` anlegen**

```java
import java.util.Arrays;
import java.util.Comparator;

// Entscheidet, wann ein Sendetakt fuer /net/impulse faellig ist und welche
// Impulse hineinkommen.
//
// Warum eine eigene Klasse: LedNetworkTransportEffect haengt an oscP5 und
// laesst sich von test/run.sh nicht uebersetzen. Diese Entscheidung soll aber
// geprueft sein - deshalb liegt sie hier, ohne Abhaengigkeit auf processing,
// oscP5 oder netP5. Genau das Muster, aus dem in diesem Projekt die Logik aus
// den Effekten herausgezogen ist.
class ImpulseOscThrottle {

  // NEGATIVE_INFINITY, damit der erste Aufruf faellig ist - ein Nullwert
  // waere bei einer Zeitbasis aus System.currentTimeMillis()/1000 kein
  // brauchbarer Startpunkt.
  private double lastSend = Double.NEGATIVE_INFINITY;

  // true, wenn seit dem letzten Takt mindestens 1/rateHz Sekunden vergangen
  // sind.
  //
  // rateHz <= 0 schaltet den Strom ab und setzt zurueck, damit beim
  // Wiedereinschalten sofort ein Takt kommt statt erst nach einem Intervall.
  //
  // Holt bewusst NICHT nach: nach einer langen Pause - Fenster im
  // Hintergrund, Rechner beschaeftigt - gibt es EINEN Takt, nicht einen
  // Schwall aufgesparter Nachrichten.
  boolean due(double nowSeconds, float rateHz) {
    if (rateHz <= 0f) {
      lastSend = Double.NEGATIVE_INFINITY;
      return false;
    }
    double interval = 1.0 / rateHz;
    if (nowSeconds - lastSend < interval) {
      return false;
    }
    lastSend = nowSeconds;
    return true;
  }

  // Indizes der energiereichsten Impulse, absteigend nach Energie. Bei
  // gleicher Energie gewinnt der kleinere Index - ohne diese Regel waere die
  // Auswahl von der Sortierreihenfolge abhaengig und die Drohnen wuerden bei
  // gleich starken Impulsen von Takt zu Takt flackern.
  int[] select(float[] energies, int maxCount) {
    if (energies == null || maxCount <= 0 || energies.length == 0) {
      return new int[0];
    }
    int n = energies.length;
    int take = maxCount < n ? maxCount : n;
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = Integer.valueOf(i);
    }
    final float[] e = energies;
    Arrays.sort(order, new Comparator<Integer>() {
      public int compare(Integer a, Integer b) {
        float ea = e[a.intValue()];
        float eb = e[b.intValue()];
        if (ea > eb) {
			return -1;
		}
        if (ea < eb) {
			return 1;
		}
        return a.intValue() - b.intValue();
      }
    });
    int[] result = new int[take];
    for (int i = 0; i < take; i++) {
      result[i] = order[i].intValue();
    }
    return result;
  }
}
```

- [ ] **Step 4: Quelldatei und Suite in den Testtreiber eintragen**

In `test/run.sh` bei den anderen Wächtern (nach `LedPositionCalibration.java`):

```bash
[ -f ImpulseOscThrottle.java ] && SOURCES="$SOURCES ImpulseOscThrottle.java"
```

Und in Zeile 27 `ImpulseOscThrottleTest` anfügen:

```bash
  set -- ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest ApplyCrossingsTest NodeSelectionTest LedAnchorStoreTest LedPositionMapTest LedPositionCalibrationTest ImpulseOscThrottleTest
```

- [ ] **Step 5: Tests laufen lassen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 6: Commit**

```bash
git add ImpulseOscThrottle.java test/ImpulseOscThrottleTest.java test/run.sh
git commit -m "ImpulseOscThrottle: Sendetakt und Auswahl der energiereichsten Impulse"
```

---

### Task 14: Impuls-IDs und Koordinaten an `/net/hitNode`

Füllt die drei auskommentierten Zeilen in `LedNetworkTransportEffect.java:310-312`, abzüglich `z`. Und gibt jedem Impuls eine ID, die Aufgabe 15 braucht.

**Achtung, die Falle in dieser Aufgabe:** `TravellingActivationFiller` darf **keine neue ID** bekommen. Filler entstehen bei hoher Geschwindigkeit für jede übersprungene LED und werden am Ende desselben Frames wieder entfernt — würden sie IDs verbrauchen, wäre der Zähler in Tagen durchgelaufen statt in Wochen, und derselbe Impuls hätte in einem Frame mehrere IDs.

**Files:**
- Modify: `LedNetworkTransportEffect.java`

**Interfaces:**
- Consumes: `LedNetworkNode.posX`, `posY` aus Aufgabe 7
- Produces:

```java
  // an TravellingActivation
  final int id;
  // zweiter Konstruktor mit ausdruecklicher ID, fuer den Filler
  TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_, int id_);
  // Filler bekommt die ID des Elternimpulses herein
  TravellingActivationFiller(float ledIdxPos_, int stripeIdx_, float speed_, float energy_, int parentId_);
```

Aufgabe 15 liest `a.id`.

- [ ] **Step 1: Zähler und ID einbauen**

In `LedNetworkTransportEffect.java` bei den Feldern der äusseren Klasse, nach `LinkedList<TravellingActivation> activations = ...`:

```java
  // Fortlaufende ID je Impuls, fuer den Positionsstrom /net/impulse. Steht in
  // der aeusseren Klasse, weil eine innere Klasse in Java 8 keine
  // nichtkonstanten statischen Felder haben darf.
  //
  // Ein Ueberlauf nach 2^31 Impulsen ist hingenommen: bei 1000 neuen Impulsen
  // je Sekunde nach etwa 25 Tagen Dauerbetrieb, und eine Kollision verwirrt
  // kurz eine Drohne auf der Klangseite.
  private int nextImpulseId = 0;
```

`TravellingActivation` umbauen:

```java
  //represents one travelling activation
  public class TravellingActivation {
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_) {
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++);
    }

    // Mit ausdruecklicher ID - nur fuer den Filler, der die ID seines
    // Elternimpulses uebernimmt statt eine neue zu verbrauchen.
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_, int id_) {
      ledIdxPos=ledIdxPos_;
      stripeIdx=stripeIdx_;
      speed=speed_;
      energy=energy_;
      id=id_;
    }

    int getLedIndex() {
      return (int)(ledIdxPos+0.5f); // global led position
    }
    float ledIdxPos; // absolute led position - used for mapping to led buffer
    int stripeIdx; // stripe the activation was created on
    float speed; // [leds/second] also encodes direction in sign
    float energy; // some measure of strength
    final int id; // fortlaufend, fuer /net/impulse
    void setEnergy(float _energy){energy=_energy;}
  }

  //represents fillers needed when high travelling speeds lead to skipping some leds in each frame
  public class TravellingActivationFiller extends TravellingActivation {
    TravellingActivationFiller(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int parentId_) {
      super(ledIdxPos_, stripeIdx_, speed_, energy_, parentId_);
    }
  }
```

- [ ] **Step 2: Die Filler-Aufrufstelle anpassen**

Es gibt genau **eine** Stelle, die einen Filler erzeugt, in `drawMe()`. Vorher finden:

Run: `grep -n "new TravellingActivationFiller" LedNetworkTransportEffect.java`
Expected: eine Zeile.

Sie lautet bisher:

```java
          newActivations.add(new TravellingActivationFiller(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy));
```

Daraus wird:

```java
          newActivations.add(new TravellingActivationFiller(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy, curActivation.id));
```

- [ ] **Step 3: Koordinaten an `/net/hitNode`**

`sendOscMessage` ersetzen:

```java
  private void sendOscMessage(LedNetworkNode hitNode, TravellingActivation curActivation) {
    OscMessage myMessage = new OscMessage("/net/hitNode");
    myMessage.add(hitNode.id);
    myMessage.add(curActivation.energy);
    // Draufsicht-Position des Knotens in Metern, Ursprung Netzmitte. Kein z -
    // das Netz haengt ueber Kopf, vier Lautsprecher in einer Ebene koennen die
    // Hoehe nicht darstellen.
    //
    // Rueckwaertskompatibel: ein Klang-Sketch, der nur msg[1] und msg[2]
    // liest, ignoriert die zwei zusaetzlichen Argumente.
    myMessage.add(hitNode.posX);
    myMessage.add(hitNode.posY);
    oscP5.send(myMessage, remoteLocation);
  }
```

- [ ] **Step 4: Übersetzung prüfen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

`test/build.sh` ist hier die eigentliche Prüfung — `LedNetworkTransportEffect` hängt an oscP5 und wird von `run.sh` nicht übersetzt.

- [ ] **Step 5: Gegenprobe, dass Filler keine IDs verbrauchen**

Run: `grep -n "nextImpulseId" LedNetworkTransportEffect.java`
Expected: genau **zwei** Treffer — die Felddeklaration und der `this(...)`-Aufruf im ersten Konstruktor. Steht `nextImpulseId` an einer dritten Stelle, verbraucht dort etwas eine ID, das keine bekommen soll.

- [ ] **Step 6: Commit**

```bash
git add LedNetworkTransportEffect.java
git commit -m "Impuls-IDs und Knotenkoordinaten an /net/hitNode"
```

---

### Task 15: Positionsstrom `/net/impulse`

**Files:**
- Modify: `LedNetworkTransportEffect.java`
- Modify: `imPulse.pde` (der Effekt-Konstruktor bekommt die Map)

**Interfaces:**
- Consumes: `ImpulseOscThrottle` aus Aufgabe 13, `id` aus Aufgabe 14, `LedPositionMap.x/y` aus Aufgabe 6
- Produces: OSC `/net/impulse <id:int> <x:float> <y:float> <energy:float>` und zwei neue Parameter, die von selbst in `data/remoteSettings.txt` erscheinen

- [ ] **Step 1: Felder und Parameter ergänzen**

In `LedNetworkTransportEffect.java` bei den übrigen Feldern:

```java
  LedPositionMap positionMap;
  final ImpulseOscThrottle impulseThrottle = new ImpulseOscThrottle();
  RemoteControlledFloatParameter impulseOscRate;      // Hz, 0 schaltet ab
  RemoteControlledIntParameter impulseOscMaxCount;    // Obergrenze je Takt
```

Im Konstruktor die Signatur um die Map erweitern und die Parameter anlegen. Die Signatur lautet danach:

```java
  LedNetworkTransportEffect(String _id, int _numLeds, int _nStripes, int _nLedsInStripe,
      LedInNetInfo[] _ledNetInfo, ArrayList <LedNetworkNode> nodes_,
      LedPositionMap _positionMap, OscP5 _oscP5, NetAddress _remoteLocation) {
```

Im Rumpf nach `remoteLocation=_remoteLocation;`:

```java
    positionMap=_positionMap;
```

Und bei den übrigen Parametern:

```java
    // 0 schaltet den Strom ab - der Notausgang, wenn Netz oder Klangrechner
    // waehrend der Show nicht mitkommen. /net/hitNode laeuft davon unberuehrt
    // weiter.
    impulseOscRate = new RemoteControlledFloatParameter("/net/impulse/oscRate", 10f, 0f, 40f);
    impulseOscMaxCount = new RemoteControlledIntParameter("/net/impulse/oscMaxCount", 32, 0, 256);
```

- [ ] **Step 2: Den Strom senden**

In `drawMe()` **nach** der `while (iter.hasNext())`-Zeichenschleife und **vor** `return bufferLedColors;`:

```java
    sendImpulseStream(currentTime);
```

Und die Methode dazu, nach `sendOscMessage`:

```java
  // Gedrosselter Positionsstrom der reisenden Impulse.
  //
  // Wird bewusst NACH der Zeichenschleife gerufen: die hat die Filler im
  // selben Frame ueber iter.remove() wieder entfernt, in activations steht
  // hier also genau ein Eintrag je echtem Impuls. Ohne diese Reihenfolge
  // muesste man Filler von Hand aussortieren und wuerde denselben Impuls
  // mehrfach melden.
  //
  // Kein Todes-Signal: der Strom ist durch oscMaxCount ohnehin lueckenhaft -
  // ein Impuls kann aus der Auswahl fallen, ohne zu sterben. Die Klangseite
  // muss mit stillem Verschwinden umgehen koennen, und dann deckt ihr Timeout
  // auch den echten Tod ab.
  private void sendImpulseStream(double currentTime) {
    if (!impulseThrottle.due(currentTime, impulseOscRate.getValue())) {
      return;
    }
    int n = activations.size();
    if (n == 0) {
      return;
    }
    float[] energies = new float[n];
    TravellingActivation[] flat = new TravellingActivation[n];
    int k = 0;
    for (TravellingActivation a : activations) {
      flat[k] = a;
      energies[k] = a.energy;
      k++;
    }
    int[] chosen = impulseThrottle.select(energies, impulseOscMaxCount.getValue());
    for (int i = 0; i < chosen.length; i++) {
      TravellingActivation a = flat[chosen[i]];
      int ledIndex = a.getLedIndex();
      OscMessage myMessage = new OscMessage("/net/impulse");
      myMessage.add(a.id);
      myMessage.add(positionMap.x(ledIndex));
      myMessage.add(positionMap.y(ledIndex));
      myMessage.add(a.energy);
      oscP5.send(myMessage, remoteLocation);
    }
  }
```

`positionMap.x/y` klemmen Indizes selbst und geben 0 zurück, wenn `apply()` nie lief oder der Index nicht passt — es braucht keine zusätzliche Prüfung.

- [ ] **Step 3: Den Aufruf in `imPulse.pde` nachziehen**

Die vorhandene Zeile

```java
  ledNetworkTransportEffect = new LedNetworkTransportEffect("1", numLeds, numStripes, numLedsPerStripe, ledNetInfo, listOfNodes, oscP5, oscOutput);
```

wird zu

```java
  ledNetworkTransportEffect = new LedNetworkTransportEffect("1", numLeds, numStripes, numLedsPerStripe, ledNetInfo, listOfNodes, ledPositionMap, oscP5, oscOutput);
```

`ledPositionMap` wird in `setup()` weiter oben angelegt (Aufgabe 12, Step 2) — die Reihenfolge stimmt also. Wird Stufe 3 **vor** Stufe 2 umgesetzt, muss der Block aus Aufgabe 12 Step 2 vorgezogen werden, mindestens `ledAnchorStore`, `ledPositionMap` und `apply`.

- [ ] **Step 4: Übersetzung prüfen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0.

- [ ] **Step 5: Gegenprobe, dass die Parameter erscheinen**

`data/remoteSettings.txt` wird bei jedem Start neu geschrieben. Ohne laufenden Sketch lässt sich das nicht prüfen; stattdessen die Registrierung nachsehen:

Run: `grep -n "oscRate\|oscMaxCount" LedNetworkTransportEffect.java`
Expected: je zwei Treffer — Felddeklaration und Anlegen im Konstruktor.

- [ ] **Step 6: Commit**

```bash
git add LedNetworkTransportEffect.java imPulse.pde
git commit -m "Gedrosselter Positionsstrom /net/impulse fuer die reisenden Impulse"
```

---

### Task 16: Vierkanal-Klang in SuperCollider

Ambisonics 2D erster Ordnung mit den **Kern-UGens** `PanB2` und `DecodeB2` — kein Quark, kein sc3-plugins. Erste Ordnung in der Ebene ist genau die Ordnung, die vier Lautsprecher hergeben, und die Höhe ist verworfen.

Für diese Aufgabe gibt es **keine automatische Prüfung**. `test/run.sh` und `test/build.sh` sehen die Datei nicht an. Die Prüfung ist der Testton je Kanal in Step 5.

**Files:**
- Modify: `supercollider/klangnetz_bells.scd`

**Interfaces:**
- Consumes: OSC `/net/hitNode <id> <energy> <x> <y>` (Aufgabe 14) und `/net/impulse <id> <x> <y> <energy>` (Aufgabe 15)
- Produces: vier Hardware-Ausgangskanäle

- [ ] **Step 1: Server auf vier Kanäle und Busse anlegen**

Im Konfigurationsteil oben, nach `~oscListenPort = 8002;`:

```supercollider
// ---- Vierkanal-Ausgang -----------------------------------------------
// numOutputBusChannels wirkt erst beim BOOTEN des Servers. Laeuft schon
// einer, muss er neu gestartet werden - sonst bleiben Kanal 2 und 3 stumm,
// ohne Fehlermeldung.
s.options.numOutputBusChannels = 4;

// Halbe Diagonale der Grundflaeche 14 x 8 m. Ab diesem Radius ist ein Klang
// vollstaendig gerichtet.
~maxRadiusM = 8.062;

// Lautsprecher auf den SEITENMITTEN, nicht in den Ecken:
//   (0, +4) vorn   (+7, 0) rechts   (0, -4) hinten   (-7, 0) links
// Die Zuordnung zu den Hardware-Kanaelen 0..3 macht DecodeB2 und ist in
// Step 5 gemessen, nicht hier behauptet.

// Obergrenze gleichzeitiger Drohnen, als Netz unter dem oscMaxCount der
// Processing-Seite: kommt der Strom aus einer falsch konfigurierten Quelle,
// soll der Klangrechner nicht in die Knie gehen.
~droneLimit = 32;
// Ohne Meldung fuer diese Zeit wird eine Drohne freigegeben. Bei 10 Hz
// Melderate also nach vier ausgefallenen Meldungen. Das ist der EINZIGE
// Freigabemechanismus - Processing schickt kein Todes-Signal, weil der Strom
// durch oscMaxCount ohnehin lueckenhaft ist.
~droneTimeout = 0.4;
```

Innerhalb von `s.waitForBoot({ ... })`, ganz am Anfang:

```supercollider
    // B-Format-Bus: 2D erster Ordnung, drei Kanaele W X Y. Alle Klangobjekte
    // schreiben hier hinein, EIN Decoder-Synth am Ende macht daraus die vier
    // Lautsprecherkanaele.
    ~bformatBus = Bus.audio(s, 3);
    ~voices = Group.new;
```

- [ ] **Step 2: Die Ambisonic-Kette als wiederverwendbaren Block**

Vor den SynthDefs, noch in `waitForBoot`:

```supercollider
    // Aus einer Position in Metern wird B-Format. Zwei Groessen:
    //
    //   azimuth in Halbzyklen, 0 = vorn. Unser System hat X nach rechts und
    //   Y nach vorn, PanB2 zaehlt von vorn - daher x.atan2(y), NICHT
    //   y.atan2(x). Vorzeichen und Nullpunkt sind in Step 5 gemessen.
    //
    //   direct = Richtwirkung. Bei erster Ordnung IST die Richtwirkung das
    //   Verhaeltnis der X/Y-Anteile zum W-Anteil, eine Multiplikation genuegt
    //   also. Bei direct = 0 bleibt nur der omnidirektionale Anteil und der
    //   Klang kommt aus allen vier Boxen - genau richtig fuer einen Knoten
    //   senkrecht ueber der Netzmitte, der nicht aus einer Richtung kommen
    //   soll, sondern von ueberall. Am Rand wird er zur ebenen Welle.
    ~toBformat = { |sig, px, py|
        var azimuth, radius, direct, bf;
        azimuth = px.atan2(py) / pi;
        radius  = px.hypot(py);
        direct  = (radius / ~maxRadiusM).clip(0, 1);
        bf = PanB2.ar(sig, azimuth, 1);
        [bf[0], bf[1] * direct, bf[2] * direct]
    };
```

- [ ] **Step 3: Die drei SynthDefs**

`\glockenBell` behält seine Klangfarbe unverändert — fünf leicht unharmonische Sinus-Teiltöne, kurzer Attack, langer Decay. Ersetzt werden nur `pan`, `Pan2` und `Out.ar(out, sig)`:

```supercollider
    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0, x = 0, y = 0|
        var sig, partials, partialAmps, partialDecays;

        partials      = #[1.0, 2.001, 2.998, 4.204, 5.515];
        partialAmps   = #[1.0, 0.55, 0.30, 0.18, 0.10];
        partialDecays = #[3.2, 2.1, 1.35, 0.85, 0.55];

        sig = Mix.fill(partials.size, { |i|
            var pFreq, pAmp, pDecay, env;
            pFreq  = freq * partials[i];
            pAmp   = partialAmps[i];
            pDecay = partialDecays[i];
            env = EnvGen.kr(
                Env([0, 1, 0], [0.003, pDecay], curve: [\lin, -4]),
                doneAction: if(i == 0) { 2 } { 0 }
            );
            SinOsc.ar(pFreq) * pAmp * env;
        });

        sig = sig * amp;
        // Position je Ton fest - eine Glocke wandert nicht. Kein Lag noetig.
        Out.ar(out, ~toBformat.(sig, x, y));
    }).add;
```

Neu, für die reisenden Impulse:

```supercollider
    // Leiser gehaltener Ton fuer einen reisenden Impuls. Die Klangfarbe ist
    // ein schlichter Startpunkt und ausdruecklich zum Ersetzen gedacht - die
    // Mechanik drumherum ist der Teil, der stimmen muss.
    SynthDef(\impulseDrone, { |freq = 220, amp = 0.08, out = 0, x = 0, y = 0, gate = 1|
        var sig, env, xl, yl;
        // Lag VOR der Umrechnung: ein springender Positionswert darf nicht
        // als Klick durchschlagen. Bei 10 Hz Melderate glaettet 0.1 s die
        // Spruenge zwischen zwei Meldungen.
        xl = Lag.kr(x, 0.1);
        yl = Lag.kr(y, 0.1);
        env = EnvGen.kr(Env.asr(0.05, 1, 0.3, \sin), gate, doneAction: 2);
        sig = LFTri.ar(freq) * 0.5 + SinOsc.ar(freq * 2.01, 0, 0.2);
        sig = LPF.ar(sig, freq * 6);
        sig = sig * amp * env;
        Out.ar(out, ~toBformat.(sig, xl, yl));
    }).add;

    // Genau EIN Decoder am Ende der Kette. orientation wird in Step 5
    // gemessen: 0 fuer "ein Lautsprecher steht genau vorn", was unseren
    // Seitenmitten entspricht.
    SynthDef(\b2Decoder, { |in = 0, orientation = 0, amp = 1|
        var bf = In.ar(in, 3);
        Out.ar(0, DecodeB2.ar(4, bf[0], bf[1], bf[2], orientation) * amp);
    }).add;

    // Nur fuer die Messung in Step 5: Rauschen auf genau einen Kanal.
    SynthDef(\channelTest, { |chan = 0, amp = 0.2|
        var sig = PinkNoise.ar(amp) * EnvGen.kr(Env.linen(0.05, 0.8, 0.15), doneAction: 2);
        Out.ar(chan, sig);
    }).add;
```

- [ ] **Step 4: Decoder starten, Drohnen verwalten, OSC empfangen**

Nach `s.sync;`:

```supercollider
    // Der Decoder MUSS hinter den Stimmen laufen, sonst decodiert er einen
    // Bus, in den sie im selben Block noch nicht geschrieben haben.
    ~decoderSyn = Synth.after(~voices, \b2Decoder, [\in, ~bformatBus.index]);

    ~drones = IdentityDictionary.new;

    // Tonhoehe je Impuls aus seiner ID, auf derselben pentatonischen Leiter
    // wie die Glocken, aber eine Oktave darunter - so liegen die Drohnen
    // unter dem Glockenspiel statt dazwischen.
    ~droneFreq = { |id|
        var idx = id.abs;
        var step = ~pentatonicSteps[idx % ~pentatonicSteps.size];
        var oct = idx.div(~pentatonicSteps.size) % ~numOctaves;
        (~rootMidiNote - 12 + step + (12 * oct)).midicps
    };

    OSCdef(\impulseStream, { |msg|
        var id = msg[1], px = msg[2], py = msg[3], energy = msg[4];
        var entry = ~drones[id];
        var amp = energy.clip(0, 2) * 0.06;
        if (entry.notNil) {
            entry[\synth].set(\x, px, \y, py, \amp, amp);
            entry[\lastSeen] = Main.elapsedTime;
        } {
            if (~drones.size < ~droneLimit) {
                ~drones[id] = (
                    synth: Synth(\impulseDrone, [
                        \freq, ~droneFreq.(id), \x, px, \y, py,
                        \amp, amp, \out, ~bformatBus.index
                    ], ~voices),
                    lastSeen: Main.elapsedTime
                );
            };
        };
    }, '/net/impulse', recvPort: ~oscListenPort);

    // Freigabe allein ueber den Timeout. Ein Impuls, der aus der Auswahl der
    // energiereichsten faellt, verschwindet fuer die Klangseite genauso still
    // wie einer, der stirbt - deshalb genau ein Mechanismus fuer beides.
    ~droneReaper = Routine({
        loop {
            var now = Main.elapsedTime;
            ~drones.keys.asArray.do({ |id|
                var entry = ~drones[id];
                if (entry.notNil and: { (now - entry[\lastSeen]) > ~droneTimeout }) {
                    entry[\synth].set(\gate, 0);
                    ~drones.removeAt(id);
                };
            });
            0.1.wait;
        }
    }).play;
```

Der vorhandene `OSCdef` für `/net/hitNode` bekommt x und y. Die `if (msg.size > n)`-Absicherung lässt ihn auch mit einem älteren Processing-Stand laufen, der noch keine Koordinaten schickt:

```supercollider
        var px = if (msg.size > 3) { msg[3] } { 0 };
        var py = if (msg.size > 4) { msg[4] } { 0 };
```

und beim Anlegen des Synths `\x, px, \y, py, \out, ~bformatBus.index` mitgeben sowie `~voices` als Ziel-Group.

- [ ] **Step 5: Die zwei Unbekannten messen**

Das ist die Prüfung dieser Aufgabe. Grundsatz aus `CLAUDE.md`: *„Massgeblich ist die Messung, nicht die Herleitung."* Dort haben zwei unabhängige Ableitungen aus der Firmware zu einer falschen ArtNet-Bytefolge geführt, und erst die Messung am Aufbau hat es geklärt.

**a) Kanalreihenfolge.** In sclang ausführen:

```supercollider
Routine({ 4.do({ |i| ("Kanal " ++ i).postln; Synth(\channelTest, [\chan, i]); 1.2.wait; }) }).play;
```

Notieren, welche Box bei welcher Nummer klingt, und als Kommentar in die Datei schreiben.

**b) Azimut und `orientation`.** Einen Ton an vier bekannte Positionen legen und hören, aus welcher Richtung er kommt:

```supercollider
Routine({
    [[0, 4, "vorn"], [7, 0, "rechts"], [0, -4, "hinten"], [-7, 0, "links"]].do({ |p|
        p[2].postln;
        Synth(\glockenBell, [\freq, 440, \amp, 0.3, \x, p[0], \y, p[1],
            \out, ~bformatBus.index], ~voices);
        1.5.wait;
    });
}).play;
```

Kommt „rechts" aus der linken Box, ist das Vorzeichen des Azimut umgekehrt: dann `azimuth = px.atan2(py) / pi` durch `azimuth = px.neg.atan2(py) / pi` ersetzen. Ist die Richtung um 45° verdreht, stimmt `orientation` nicht: dann `\orientation, 0.5` am Decoder probieren.

Beides Ergebnis als Kommentar in die Datei, mit Datum.

- [ ] **Step 6: Commit**

```bash
git add supercollider/klangnetz_bells.scd
git commit -m "Vierkanal-Ambisonics mit PanB2/DecodeB2, Drohnen fuer reisende Impulse"
```

---

### Task 17: Anleitung und `CLAUDE.md`

**Files:**
- Create: `docs/positionen-anleitung.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: alles
- Produces: nichts, was Code liest

Markdown, also **echte Umlaute** — die ASCII-Regel gilt nur für `.java` und `.pde`.

- [ ] **Step 1: `docs/positionen-anleitung.md` schreiben**

Im Zuschnitt von `docs/kalibrierung-anleitung.md`: was man vorher wissen muss, das Vorgehen Schritt für Schritt, Gegenprüfen ohne Neustart, Korrigieren, Fallstricke. Inhalt:

- **Vorher:** Kreuzungen müssen aufgenommen sein (`docs/kalibrierung-anleitung.md`), sonst fehlen der Arbeitsliste ihre Einträge. Master-Pegel bleibt bei 0,1.
- **Das Prinzip:** ein Eintrag ist ein physischer Punkt. Er blinkt weiss im Netz — bei einer Kreuzung blinken beide LEDs auf beiden Stripes. Man sieht ihn an, klickt ihn in der Draufsicht-Fläche an, fertig.
- **Der Vorschlag:** ab dem zweiten Punkt eines Stripes zeigt der Ring, wo die Position nach der Fortsetzung des Vektors liegen müsste. Stimmt er, `ENTER`. Stimmt er fast, mit den Pfeiltasten nachschieben (`F` schaltet 1 / 5 / 25 cm). Stimmt er nicht, neu klicken.
- **Reihenfolge:** stripeweise arbeiten, `o` springt zum nächsten offenen Eintrag. Die Kreuzungen eines Stripes stehen zwischen seinen zwei Enden.
- **Farben im Netz:** blau heisst zwischen zwei Ankern, rot heisst nur geraten, dunkel heisst gar keine Position auf diesem Stripe. Rot verschwindet, sobald beide Enden eines Stripes gesetzt sind.
- **Gegenprüfen ohne Neustart:** `R` übernimmt, `T` schreibt den Abdeckungsbericht auf die Konsole.
- **Speichern:** `S`. Atomar, mehrfaches Speichern verdoppelt nichts.
- **Korrigieren:** mit `,`/`.` zum Eintrag blättern, `BACKSPACE` löscht seinen Anker, die Anzeige fällt auf den Vorschlag zurück, neu setzen.
- **Fallstricke:**
  - Die Warnung „Luftlinie länger als der Stripe hergibt" heisst fast immer: falsche Netzseite angeklickt. Die Position wird trotzdem gesetzt — bewusst, damit das Werkzeug sich nicht mitten in der Arbeit blockiert, falls die Annahme über den LED-Abstand vor Ort nicht stimmt.
  - Ändern sich `controllerOctets` oder `numLedsPerStripe`, verschieben sich alle globalen Indizes und die Aufnahme ist hinüber — genau wie bei den Kreuzungen.
  - Kommen später Kreuzungen dazu, bleiben die alten Positionen gültig: der Schlüssel ist der LED-Index, nicht die Knoten-Nummer. Die neuen Kreuzungen erscheinen nach `R` als offene Einträge.
  - `p` und `c` schliessen sich gegenseitig aus, beide belegen `,` `.` `S` `R` `F` `L`.

- [ ] **Step 2: `CLAUDE.md` ergänzen**

Vier Stellen:

1. Neuer Abschnitt **„LED-Positionen und Spatialisierung"** nach „Node-Kalibrierung": Ankerbegriff, `data/ledPositions.txt` mit Format, `LedAnchorStore` / `LedPositionMap` / `LedPositionCalibration` / `ImpulseOscThrottle` und warum die vier processing- und netzfrei sind, Grundfläche 14 × 8 m mit Ursprung und Achsen, Tastenbelegung des Positionsmodus, gegenseitiger Ausschluss mit `c`, Verweis auf `docs/positionen-anleitung.md`.
2. Im Abschnitt zum OSC-Parametersystem die **ausgehenden Adressen** ergänzen: `/net/hitNode <nodeId:int> <energy:float> <x:float> <y:float>` und `/net/impulse <impulseId:int> <x:float> <y:float> <energy:float>`, dazu die zwei neuen Parameter `/net/impulse/oscRate` und `/net/impulse/oscMaxCount` mit dem Hinweis, dass 0 den Strom abschaltet.
3. Im Abschnitt **„Tests"** die vier neuen Suiten aufnehmen: `LedAnchorStoreTest`, `LedPositionMapTest`, `LedPositionCalibrationTest`, `ImpulseOscThrottleTest`.
4. Bei **„Konventionen und Fallstricke"** zwei Punkte:
   - `LedPositionCalibration` nennt bewusst **kein** `implements runnableLedEffect`: das Interface steht in `mixer.java`, das über `RemoteControlledFloatParameter` an `oscP5` hängt, und eine Klasse, die es nennt, lässt sich von `test/run.sh` nicht mehr übersetzen. `imPulse.pde` ruft `drawMe()` ohnehin direkt auf.
   - Keine von der Kreuzungszahl abgeleitete Zahl gehört als Literal in Code oder Test — `data/nodeCrossings.txt` wächst während der Kalibrierung.

Ausserdem im Abschnitt zur Impuls-Simulation ergänzen, dass `TravellingActivation` jetzt eine fortlaufende `id` trägt und `TravellingActivationFiller` die des Elternimpulses übernimmt, statt eine neue zu verbrauchen.

- [ ] **Step 3: Prüfen**

Run: `test/run.sh && test/build.sh`
Expected: alle Suiten bestanden, `Finished.`, beide Status 0. (Nur Dokumentation geändert — die Prüfung soll bestätigen, dass nichts nebenbei kaputtgegangen ist.)

- [ ] **Step 4: Commit**

```bash
git add docs/positionen-anleitung.md CLAUDE.md
git commit -m "Anleitung zum Aufnehmen der LED-Positionen und CLAUDE.md nachziehen"
```

---

## Abschluss

Nach Aufgabe 17 ist die Spec vollständig umgesetzt. Was ausdrücklich **nicht** gebaut wurde und in Spec Abschnitt 7 mit Begründung steht: Kamera-Scan, globale Ausgleichsrechnung über alle Anker, Höhe/Z, Segmentgrenzen innerhalb eines Stripes, `/net/impulseBorn`, Todes-Signal für Impulse, Abstandsdämpfung im Klang.

Zwei Dinge bleiben Handarbeit vor Ort und sind kein Versäumnis der Umsetzung:

| Bereich | Prüfung |
|---|---|
| Aussehen der Draufsicht-Fläche und der LED-Rückmeldung | Sketch in Processing 3 öffnen, `P` drücken, ansehen |
| Kanalreihenfolge und Azimut-Vorzeichen | Testton je Kanal und Ton an vier bekannten Positionen, Aufgabe 16 Step 5 |
