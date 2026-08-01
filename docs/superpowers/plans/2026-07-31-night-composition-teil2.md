# Nacht-Komposition Teil 2 — Web-UI-Design-Pass und quantisierte Impuls-Speed

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Feature 5 (bewusster Design-Pass am Web-UI statt generischer
Reglerliste) und Feature 6 (rhythmisch quantisierte Impuls-Speed mit seltenen
Ausreißern, hörbar unterscheidbar im Travel-Sound) aus dem nachgelieferten
Teil des Briefs.

**Architecture:** Feature 6 folgt demselben Muster wie Teil 1 — die
Auswahllogik wandert in eine Processing-freie, geprüfte Klasse
(`SpeedQuantizer`), die Verdrahtung bleibt im Effekt, und **alle** Spawn-Pfade
gehen durch **eine** gemeinsame Methode. Die Hörbarkeit entsteht auf der
SC-Seite durch eine Umstellung der Speed→Frequenz-Abbildung von linear auf
**oktavbasiert mit Rasterung**. Feature 5 lässt das generische Rendering für
alle bisherigen Parameter unangetastet und legt daneben zwei
**Spezial-Sektionen** (Sequencer, Speed-Klassen) sowie eine eigene
SC-Parameter-Sektion, die auf Port 8002 sendet.

**Tech Stack:** wie Teil 1; im Web-UI weiterhin **kein** Framework und **kein**
Build-Schritt (Vanilla JS + CSS, siehe Kopfkommentar in `style.css`).

Brief: `docs/superpowers/specs/2026-07-31-brief-night-composition.md`,
Abschnitte „Feature 5" und „Feature 6".
Teil 1: `docs/superpowers/plans/2026-07-31-night-composition.md`.

## Global Constraints

- Branch `feature/night-composition-sequencer`. **Kein Push, kein Merge.**
- Neue Effekte defaulten auf **aus**: `/net/impulse/speedQuantize/enabled = 0`.
  Bei 0 bekommt jeder Spawn exakt `impulseSpeed` wie heute.
- Neue prüfbare Logik ohne `processing.*`, `oscP5.*`, `netP5.*`.
- Umlaute in Java-Quelltext und Commit-Messages umschreiben.
- Web-UI: kein npm, kein Build. Das **bestehende** Farbschema
  (`:root`-Variablen in `style.css`) wird aufgegriffen und erweitert, nicht
  ersetzt — der Brief verlangt ausdrücklich keinen Stilbruch.
- Tests: `test/run.sh`, `test/build.sh`, `python3 webui/test_webui.py`.
- **Niemals** `TimingProbe`/`PollProbe`/`PatternProbe` starten.

## Entscheidungen, die der Brief offen lässt

**Referenzparameter für Feature 6: `impulseSpeed` selbst, kein neuer.**
Der Brief stellt beides frei. `impulseSpeed` ist bereits der Bezugspunkt der
Zeitbasis-Kopplung (`lifetime`, `nodeDeadTime`, `randomSpawn/interval` ziehen
im Web-UI mit). Ein zweiter Referenzregler daneben hieße: zwei Zahlen, die
beide „die Geschwindigkeit" heißen, und ein Operator, der die falsche zieht,
bekommt entweder keine Wirkung oder eine entkoppelte Zeitbasis. Mit
`impulseSpeed` als 1x-Klasse bleibt die Kopplung genau so gültig wie bisher —
die Multiplikatoren sitzen *darüber*, je Impuls.

**Fünf Klassen: 0.5x, 1x, 2x, 4x, 8x.** Spiegelbild der Notenwerte aus
Feature 2 (Ganze…Sechzehntel sind ebenfalls fünf Stufen im Verhältnis
1:2:4:8:16). 0.5x ist als „langsamer Ausreißer" im Brief ausdrücklich erlaubt
und bekommt Gewicht 0 als Auslieferungswert — vorhanden, aber nicht scharf.

**Hörbarkeit über Oktaven statt linear.** Die in Teil 1 gebaute Abbildung war
`linlin(speed, 0, speedRef, freqMin, freqMax)`. Linear liegen 1x und 2x
dicht beieinander (bei den heutigen Werten 475 Hz gegen 652 Hz) — das ist der
„sanfte lineare Gradient", den der Brief ausdrücklich **nicht** will. Ersetzt
durch `freq = freqBase * 2^(octaves * octavesPerStep)` mit
`octaves = log2(speed/speedRef)`: jede Verdopplung der Geschwindigkeit ist
dann ein fester Intervallsprung, bei `octavesPerStep = 1` genau eine Oktave.
Mit `freqBase = 400` und `speedRef = 16`: 0.5x → 200 Hz, 1x → 400, 2x → 800,
4x → 1600, 8x → 3200. Unverwechselbar.

**Rasterung auf die Klasse (`travelSnap`, Default an).** Mit
`speedQuantize/jitter > 0` streut die tatsächliche Speed um ihre Klasse, und
die Frequenzen würden verschmieren. `travelSnap` rundet den Oktavabstand vor
der Umrechnung auf eine ganze Zahl — damit klingt **jeder** Impuls einer
Klasse auf exakt derselben Tonhöhe. Genau das ist die vom Brief geforderte
„Zuordenbarkeit"; ohne die Rasterung wäre sie vom Jitter abhängig.

**SC-Parameter im Web-UI: eigene Sektion, eigener OSC-Port.** Die
`/klangnetz/param/*`-Adressen laufen **nicht** durch `remoteSettings.txt` —
das ist die Parameterliste von imPulse, SuperCollider hat seine eigene
Registry in der `.scd`. Der Brief lässt hier „pragmatisch lösen" zu. Gebaut
wird eine im Server hinterlegte Tabelle, die die `.scd`-Registry spiegelt, und
ein zweiter `OscSender` auf **Port 8002**. Die Grenze davon steht in der
Sektion selbst: es gibt keinen Rückkanal, die angezeigten Werte sind die
`.scd`-Defaults, bis jemand sie in dieser Sitzung verstellt.

**Live-Feedback der Tracks: berechnetes Raster, ehrlich beschriftet.** Es gibt
keinen OSC-Rückkanal zum Browser, und Port 8002 ist von SuperCollider belegt —
ein echtes „dieser Track feuert jetzt" wäre ein neuer Ausgangsweg im Sketch.
Gebaut wird stattdessen eine Puls-Anzeige aus BPM und Notenwert **im
Browser**, beschriftet als „Raster (berechnet)" mit Tooltip, dass sie die
eigene Uhr des UI zeigt und nicht phasengleich mit dem Sketch läuft. Sie macht
sichtbar, welcher Track dicht und welcher dünn läuft — der eigentliche Nutzen
beim Einrichten. Eine Anzeige, die „feuert jetzt" behauptet, ohne es zu
wissen, wäre schlechter als keine.

---

## File Structure

**Neu:**

| Datei | Verantwortung |
|---|---|
| `SpeedQuantizer.java` | Gewichtete Auswahl einer Speed-Klasse. Reine Rechnung, Zufall wird hereingereicht. |
| `test/SpeedQuantizerTest.java` | Prüft Verteilung, Randfälle, entartete Gewichte. |

**Geändert:**

| Datei | Änderung |
|---|---|
| `LedNetworkTransportEffect.java` | Parameter, `spawnSpeed()`, alle fünf Spawn-Pfade darüber |
| `supercollider/klangnetz_bells.scd` | Oktav-Abbildung statt linear, `travelSnap`, neue Defaults |
| `webui/server.py` | Sequencer-/Speed-Klassen-Metadaten, SC-Registry, `/api/sc` |
| `webui/static/app.js` | Sequencer-Panel, Notenwert-Wähler, Verteilungsbalken, SC-Sektion |
| `webui/static/style.css` | Erweiterung des vorhandenen Schemas |
| `webui/templates/index.html` | Container für die Spezial-Sektionen |
| `webui/test_webui.py` | Tests für die neuen Server-Bausteine |
| `test/run.sh`, `CLAUDE.md`, `webui/README.md` | nachziehen |

---

## Task 1: `SpeedQuantizer` — gewichtete Auswahl einer Speed-Klasse

**Files:**
- Create: `SpeedQuantizer.java`, `test/SpeedQuantizerTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Produces:
  - `static final float[] SpeedQuantizer.MULTIPLIERS = { 0.5f, 1f, 2f, 4f, 8f }`
  - `static final int SpeedQuantizer.NEUTRAL_INDEX = 1` (die 1x-Klasse)
  - `static int SpeedQuantizer.pick(float[] weights, double random01)`
  - `static float SpeedQuantizer.multiplierAt(int index)`

- [ ] **Step 1: Write the failing test**

Create `test/SpeedQuantizerTest.java`:

```java
public class SpeedQuantizerTest {

  public static void main(String[] args) throws Exception {
    // ---- Die Klassen selbst ----
    Check.eq("fuenf Klassen", 5, SpeedQuantizer.MULTIPLIERS.length);
    Check.near("Klasse 0 ist halbe Geschwindigkeit",
        0.5, SpeedQuantizer.multiplierAt(0), 1e-9);
    Check.near("Klasse 1 ist der Normalfall",
        1.0, SpeedQuantizer.multiplierAt(SpeedQuantizer.NEUTRAL_INDEX), 1e-9);
    Check.near("Klasse 4 ist achtfach", 8.0, SpeedQuantizer.multiplierAt(4), 1e-9);
    // Ein Index ausserhalb darf keinen Absturz geben, sondern den Normalfall
    Check.near("Index unter 0 gibt den Normalfall",
        1.0, SpeedQuantizer.multiplierAt(-1), 1e-9);
    Check.near("Index ueber der Liste gibt den Normalfall",
        1.0, SpeedQuantizer.multiplierAt(99), 1e-9);

    // ---- Auswahl ueber die kumulierte Verteilung ----
    // Gewichte 85/10/4/1 auf 1x/2x/4x/8x, 0.5x aus - der Auslieferungsfall.
    float[] w = { 0f, 85f, 10f, 4f, 1f };
    // Summe 100, die Grenzen liegen also bei 0.85 / 0.95 / 0.99 / 1.0
    Check.eq("ganz unten faellt in die erste Klasse mit Gewicht",
        1, SpeedQuantizer.pick(w, 0.0));
    Check.eq("knapp unter der ersten Grenze noch 1x",
        1, SpeedQuantizer.pick(w, 0.849));
    Check.eq("ab der ersten Grenze 2x", 2, SpeedQuantizer.pick(w, 0.851));
    Check.eq("knapp unter der zweiten Grenze noch 2x",
        2, SpeedQuantizer.pick(w, 0.949));
    Check.eq("ab der zweiten Grenze 4x", 4 - 1, SpeedQuantizer.pick(w, 0.951));
    Check.eq("ab der dritten Grenze 8x", 4, SpeedQuantizer.pick(w, 0.995));
    Check.eq("ganz oben die letzte Klasse mit Gewicht",
        4, SpeedQuantizer.pick(w, 0.9999));

    // Eine Klasse mit Gewicht 0 wird nie gewaehlt - sonst waere ein
    // ausgeschalteter Ausreisser doch gelegentlich zu hoeren
    for (int i = 0; i <= 1000; i++) {
      Check.that("Gewicht 0 wird nie gewaehlt",
          SpeedQuantizer.pick(w, i/1000.0) != 0);
    }

    // ---- Die Verteilung stimmt ueber viele Ziehungen ----
    // Gleichverteilte Eingaben muessen die Gewichte reproduzieren.
    int[] counts = new int[5];
    int n = 100000;
    for (int i = 0; i < n; i++) {
      counts[SpeedQuantizer.pick(w, (i + 0.5)/n)]++;
    }
    Check.eq("0.5x kommt nicht vor", 0, counts[0]);
    Check.near("1x trifft die 85 Prozent", 0.85, counts[1]/(double) n, 0.002);
    Check.near("2x trifft die 10 Prozent", 0.10, counts[2]/(double) n, 0.002);
    Check.near("4x trifft die 4 Prozent", 0.04, counts[3]/(double) n, 0.002);
    Check.near("8x trifft das 1 Prozent", 0.01, counts[4]/(double) n, 0.002);

    // ---- Gewichte muessen sich nicht zu 100 summieren ----
    // Der Operator dreht einzelne Regler, ohne den Rest nachzurechnen -
    // normalisiert wird hier, nicht von Hand.
    float[] w2 = { 0f, 3f, 1f, 0f, 0f }; // 75% / 25%
    int einsX = 0;
    for (int i = 0; i < 40000; i++) {
      if (SpeedQuantizer.pick(w2, (i + 0.5)/40000.0) == 1) {
        einsX++;
      }
    }
    Check.near("Gewichte werden normalisiert, nicht als Prozent gelesen",
        0.75, einsX/40000.0, 0.002);

    // ---- Entartete Gewichte ----
    // Alles 0: es muss trotzdem eine Klasse herauskommen, und zwar der
    // Normalfall - sonst stuenden alle Impulse still oder raesten.
    Check.eq("alle Gewichte 0 gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX,
        SpeedQuantizer.pick(new float[] { 0f, 0f, 0f, 0f, 0f }, 0.5));
    Check.eq("null-Array gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX, SpeedQuantizer.pick(null, 0.5));
    Check.eq("zu kurzes Array gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX, SpeedQuantizer.pick(new float[] { 1f }, 0.5));
    // Negative Gewichte gelten als 0, nicht als Abzug von der Summe
    Check.eq("negatives Gewicht wird nicht gewaehlt",
        1, SpeedQuantizer.pick(new float[] { -5f, 1f, 0f, 0f, 0f }, 0.5));
    // NaN darf sich nicht in die Summe fortpflanzen
    Check.eq("NaN-Gewicht gilt als 0",
        1, SpeedQuantizer.pick(new float[] { Float.NaN, 1f, 0f, 0f, 0f }, 0.5));
    Check.eq("NaN-Zufall gibt den Normalfall",
        SpeedQuantizer.NEUTRAL_INDEX, SpeedQuantizer.pick(w, Double.NaN));

    // Zufall ausserhalb 0..1 wird geklemmt statt zu ueberlaufen
    Check.eq("Zufall unter 0 wird geklemmt", 1, SpeedQuantizer.pick(w, -1.0));
    Check.eq("Zufall ueber 1 wird geklemmt", 4, SpeedQuantizer.pick(w, 2.0));

    // Genau eine Klasse mit Gewicht: sie gewinnt immer
    float[] nurVier = { 0f, 0f, 0f, 1f, 0f };
    for (int i = 0; i <= 100; i++) {
      Check.eq("einzige gewichtete Klasse gewinnt immer",
          3, SpeedQuantizer.pick(nurVier, i/100.0));
    }

    System.exit(Check.report("SpeedQuantizerTest"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `test/run.sh SpeedQuantizerTest`
Expected: FAIL, `cannot find symbol: class SpeedQuantizer`.

- [ ] **Step 3: Write minimal implementation**

Create `SpeedQuantizer.java`:

```java
// Waehlt die Geschwindigkeitsklasse eines neu gespawnten Impulses.
//
// Nicht nur WANN gespawnt wird ist rhythmisch (siehe OriginSequencer),
// sondern auch WIE SCHNELL der einzelne Impuls reist: seine Geschwindigkeit
// ist ein rhythmisches Vielfaches der Referenz - Spiegelbild der Notenwerte,
// nur auf der Geschwindigkeit statt auf dem Zeitraster.
//
// Referenz (1x) ist /net/impulse/speed selbst, nicht ein eigener Parameter.
// Sonst gaebe es zwei Regler, die beide "die Geschwindigkeit" heissen, und
// die Zeitbasis-Kopplung (lifetime/nodeDeadTime/randomSpawn-Interval haengen
// an impulseSpeed) haette einen zweiten, unbeteiligten Bezugspunkt.
//
// Warum eine eigene Klasse: dasselbe Muster wie SplitVariance,
// ImpulseOscThrottle und OriginSequencer - LedNetworkTransportEffect haengt
// an oscP5 und laesst sich von test/run.sh nicht uebersetzen, die Rechnung
// soll aber geprueft sein. Der Zufall wird hereingereicht.
class SpeedQuantizer {

  // Fuenf Stufen im Verhaeltnis 1:2:4:8:16 - genau die Abstaende der
  // Notenwerte in OriginSequencer, nur auf der Geschwindigkeit.
  static final float[] MULTIPLIERS = { 0.5f, 1f, 2f, 4f, 8f };

  // Die 1x-Klasse. Fallback bei unbrauchbaren Gewichten: ein Impuls mit der
  // Referenzgeschwindigkeit ist immer ein gueltiger Impuls.
  static final int NEUTRAL_INDEX = 1;

  static float multiplierAt(int index) {
    if (index < 0 || index >= MULTIPLIERS.length) {
      return MULTIPLIERS[NEUTRAL_INDEX];
    }
    return MULTIPLIERS[index];
  }

  // Index der gezogenen Klasse. weights hat ein Gewicht je Klasse; die Summe
  // muss nicht 100 sein und wird hier normalisiert - ein Operator dreht
  // einzelne Regler, ohne den Rest nachzurechnen.
  //
  // Ein Gewicht von 0 wird NIE gewaehlt (auch nicht bei random01 genau auf
  // seiner Grenze): ein ausgeschalteter Ausreisser darf nicht doch
  // gelegentlich zu hoeren sein. Negative Gewichte und NaN gelten als 0 statt
  // die Summe zu verfaelschen.
  static int pick(float[] weights, double random01) {
    if (weights == null || weights.length < MULTIPLIERS.length) {
      return NEUTRAL_INDEX;
    }
    if (Double.isNaN(random01)) {
      return NEUTRAL_INDEX;
    }
    double total = 0.0;
    for (int i = 0; i < MULTIPLIERS.length; i++) {
      double w = weights[i];
      if (w > 0.0) { // faengt NaN und negative Werte mit ab
        total += w;
      }
    }
    if (!(total > 0.0)) {
      return NEUTRAL_INDEX;
    }
    double r = random01;
    if (r < 0.0) {
      r = 0.0;
    }
    if (r > 1.0) {
      r = 1.0;
    }
    double target = r*total;
    double cumulative = 0.0;
    int last = NEUTRAL_INDEX;
    for (int i = 0; i < MULTIPLIERS.length; i++) {
      double w = weights[i];
      if (!(w > 0.0)) {
        continue;
      }
      last = i; // letzte Klasse MIT Gewicht, fuer r == 1.0
      cumulative += w;
      if (target < cumulative) {
        return i;
      }
    }
    return last;
  }
}
```

- [ ] **Step 4: Add source and suite to the runner**

In `test/run.sh`, after the `OriginSequencer.java` line:

```bash
[ -f SpeedQuantizer.java ] && SOURCES="$SOURCES SpeedQuantizer.java"
```

and append `SpeedQuantizerTest` to the optional-suite list.

- [ ] **Step 5: Run test to verify it passes**

Run: `test/run.sh SpeedQuantizerTest` → alle bestanden.
Then `test/run.sh` → alle Suiten bestanden, exit 0.

- [ ] **Step 6: Commit**

```bash
git add SpeedQuantizer.java test/SpeedQuantizerTest.java test/run.sh
git commit -m "SpeedQuantizer: gewichtete Auswahl einer Speed-Klasse

Fuenf Stufen 0.5x/1x/2x/4x/8x - dieselben Abstaende wie die Notenwerte im
Sequencer, nur auf der Geschwindigkeit. Gewichte werden normalisiert, ein
Gewicht von 0 wird nie gewaehlt, entartete Eingaben fallen auf 1x zurueck.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Eine gemeinsame Spawn-Geschwindigkeit für alle Spawn-Pfade

**Files:**
- Modify: `LedNetworkTransportEffect.java`

**Interfaces:**
- Consumes: `SpeedQuantizer`, `SplitVariance` (Teil 1).
- Produces: `private float spawnSpeed()`; sieben neue OSC-Adressen.

Der Brief verlangt ausdrücklich **eine gemeinsame Auswahl-Funktion statt
dreifach duplizierter Logik**. Fünf Aufrufstellen gehen darüber:
`/tube/trigger`, `/net/activateStripe`, `/net/activateNode` (zwei Richtungen),
`spawnRandomImpulses()`, `tickSequencer()`.

- [ ] **Step 1: Felder anlegen**

Nach den `splitLifetimeJitter`-Feldern einfügen:

```java

  // Rhythmisch quantisierte Spawn-Geschwindigkeit: die Geschwindigkeit eines
  // neuen Impulses ist ein Vielfaches von impulseSpeed (der 1x-Klasse), nach
  // Gewichten gezogen. Referenz ist impulseSpeed SELBST, kein eigener
  // Parameter - sonst gaebe es zwei Regler, die beide "die Geschwindigkeit"
  // heissen, und die Zeitbasis-Kopplung (lifetime, nodeDeadTime,
  // randomSpawn/interval haengen an impulseSpeed) haette einen zweiten,
  // unbeteiligten Bezugspunkt.
  //
  // enabled=0 im Auslieferungszustand: dann bekommt jeder Spawn exakt
  // impulseSpeed wie bisher, ohne Ziehung.
  RemoteControlledIntParameter speedQuantizeEnabled;
  RemoteControlledFloatParameter speedQuantizeJitter;
  // Ein Gewicht je Klasse aus SpeedQuantizer.MULTIPLIERS, gleiche Reihenfolge.
  RemoteControlledFloatParameter[] speedClassWeights =
      new RemoteControlledFloatParameter[SpeedQuantizer.MULTIPLIERS.length];
  // Wiederverwendet statt je Spawn neu angelegt - bei dichtem Betrieb spawnen
  // mehrere Impulse je Frame.
  private final float[] weightScratch = new float[SpeedQuantizer.MULTIPLIERS.length];
```

- [ ] **Step 2: Parameter im Konstruktor anlegen**

Nach dem Sequencer-Block einfügen:

```java

    // Auslieferungswerte: aus. Die Gewichte darunter sind der Zustand, den
    // ein Operator vorfindet, wenn er einschaltet - 1x bleibt der weit
    // ueberwiegende Normalfall, ein 8x-Ausreisser ist etwa jeder hundertste.
    speedQuantizeEnabled= new RemoteControlledIntParameter("/net/impulse/speedQuantize/enabled", 0, 0, 1);
    speedQuantizeJitter= new RemoteControlledFloatParameter("/net/impulse/speedQuantize/jitter", 0f, 0f, 1f);
    // Adressnamen ohne Punkt: "0x5" statt "0.5x". Ein Punkt in einer
    // OSC-Adresse ist zwar erlaubt, aber remoteSettings.txt und das Web-UI
    // lesen Adressen als Text und der Punkt liest sich dort wie ein
    // Dezimaltrenner in einem Namen.
    String[] weightNames = { "0x5", "1x", "2x", "4x", "8x" };
    float[] weightDefaults = { 0f, 85f, 10f, 4f, 1f };
    for (int i=0; i<SpeedQuantizer.MULTIPLIERS.length; i++) {
      speedClassWeights[i]= new RemoteControlledFloatParameter(
          "/net/impulse/speedQuantize/weight/"+weightNames[i], weightDefaults[i], 0f, 100f);
    }
```

- [ ] **Step 3: Die gemeinsame Methode ergänzen**

Direkt vor `tickSequencer(double)` einfügen:

```java
  // Geschwindigkeit fuer EINEN neu gespawnten Impuls, inklusive Vorzeichen
  // nach aussen: hier kommt immer ein positiver Betrag heraus, die Richtung
  // setzt der Aufrufer.
  //
  // Der einzige Ort, an dem eine Spawn-Geschwindigkeit entsteht - alle fuenf
  // Spawn-Pfade (Tube-Trigger, activateStripe, activateNode, RandomSpawn,
  // Sequencer) gehen hierdurch. Split-Kinder NICHT: die erben die
  // (schon vervielfachte) Geschwindigkeit ihres Elternimpulses und bekommen
  // obendrauf splitSpeedJitter, siehe activationEncounteredNode().
  private float spawnSpeed() {
    float base=impulseSpeed.getValue();
    if (speedQuantizeEnabled.getValue() != 1) {
      return base;
    }
    for (int i=0; i<speedClassWeights.length; i++) {
      weightScratch[i]=speedClassWeights[i].getValue();
    }
    int cls=SpeedQuantizer.pick(weightScratch, Math.random());
    float speed=base*SpeedQuantizer.multiplierAt(cls);
    // Swing auf der Geschwindigkeit, gleiche Formel und gleicher
    // Auslieferungswert 0 wie ueberall sonst: Choreografie primaer exakt,
    // Jitter ein optionaler Regler obendrauf.
    return SplitVariance.jitter(speed, speedQuantizeJitter.getValue(), Math.random());
  }
```

- [ ] **Step 4: Die fünf Spawn-Pfade umstellen**

In `digestMessage`, `/net/activateNode` — die zwei `impulseSpeed.getValue()`
ersetzen. Beide Richtungen desselben Treffers sollen dieselbe Klasse
bekommen, deshalb **einmal** ziehen:

```java
      int theValue=newMessage.get(0).intValue();
      if (theValue>0&&theValue<nodes.size()) {
        LedNetworkNode activeNode=nodes.get(theValue);
        int nLeds=ledNetInfo.length;
        // Einmal je Kommando gezogen, nicht je Richtung: die zwei Zweige
        // desselben Anstosses sollen zusammengehoeren.
        float cmdSpeed=spawnSpeed();
        for (Integer nodeLedIdx : activeNode.ledIndices) {
          LedInNetInfo curLedInfo=ledNetInfo[nodeLedIdx]; //which stripe are we on?
          //  activation spreads in boths directions
          int forwPos=nodeLedIdx +1;
          if (forwPos>0&&forwPos<nLeds) {
			activations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex, cmdSpeed, 1f ));
		}
          //do not go back the same stripe:
          int backwPos=nodeLedIdx -1;
          if (backwPos>0&&backwPos<nLeds) {
			activations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex, -cmdSpeed, 1f));
		}
        }
      }
```

`/net/activateStripe`:

```java
      int theValue=newMessage.get(0).intValue();
      activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, spawnSpeed(), 1f ));
```

`/tube/trigger`:

```java
      if (theValue<nStripes) {
        activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, spawnSpeed(), energy));
      }
```

In `spawnRandomImpulses()` die Zeile

```java
    float speed=impulseSpeed.getValue(); // bewusst kein eigener Speed-Parameter, siehe Feldkommentar
```

entfernen und in der Schleife darunter je Impuls neu ziehen — bei
`count > 1` soll nicht der ganze Schwung dieselbe Klasse haben:

```java
    for (int stripeIdx : pickDistinctStripes(count)) {
      boolean forward=Math.random() < directionBias;
      // Je Impuls eine eigene Klasse: bei count > 1 soll nicht der ganze
      // Schwung gleich schnell sein.
      float speed=spawnSpeed();
      // "rueckwaerts" beginnt am anderen Ende des Stripes, sonst wuerde der Impuls sofort
      // wieder aus den Bounds fallen (siehe activationIsValid) statt eine sichtbare Strecke zu reisen
      float startPos=forward ? stripeIdx*nLedsInStripe : stripeIdx*nLedsInStripe + (nLedsInStripe-1);
      activations.add(new TravellingActivation(startPos, stripeIdx, forward ? speed : -speed, energy));
    }
```

In `tickSequencer()` die Zeile `float speed=impulseSpeed.getValue();` samt
Kommentar ersetzen und je Track ziehen:

```java
    // Geschwindigkeit je Track einzeln gezogen (spawnSpeed): zwei Tracks, die
    // im selben Beat feuern, sollen nicht zwangslaeufig dieselbe Klasse
    // bekommen. decayScale 1.0 (der Konstruktor ohne ausdruecklichen Wert):
    // ein gespawnter Impuls folgt dem globalen Lifetime, gestreut wird erst
    // an einer Kreuzung.
    for (int i=0; i<firing.length; i++) {
      int track=firing[i];
      int stripeIdx=originSequencer.originOf(track);
      if (stripeIdx < 0 || stripeIdx >= nStripes) {
        continue;
      }
      activations.add(new TravellingActivation(stripeIdx*nLedsInStripe, stripeIdx,
          spawnSpeed(), trackConfigs[track].energy));
    }
```

- [ ] **Step 5: Prüfen, dass keine Spawn-Stelle übrig ist**

```bash
grep -n 'impulseSpeed.getValue()' LedNetworkTransportEffect.java
```

Expected: nur noch **zwei** Treffer — in `spawnSpeed()` selbst und in
`drawMe()` (`float speed=impulseSpeed.getValue();`, dort ungenutztes Altlast-
Lokal; unangetastet lassen, es gehört nicht zum Auftrag).

- [ ] **Step 6: Übersetzen und prüfen**

`test/build.sh` → exit 0. `test/run.sh` → alle Suiten bestanden.

- [ ] **Step 7: Commit**

```bash
git add LedNetworkTransportEffect.java
git commit -m "Spawn-Geschwindigkeit rhythmisch quantisiert

Alle fuenf Spawn-Pfade gehen jetzt durch spawnSpeed() - Tube-Trigger,
activateStripe, activateNode, RandomSpawn und Sequencer. Referenz der
1x-Klasse ist impulseSpeed selbst, damit die Zeitbasis-Kopplung ihren
Bezugspunkt behaelt.

Auslieferungszustand aus: bei enabled=0 bekommt jeder Spawn exakt
impulseSpeed wie bisher.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Travel-Sound hörbar je Speed-Klasse (SuperCollider)

**Files:**
- Modify: `supercollider/klangnetz_bells.scd`

Die lineare Abbildung aus Teil 1 macht 1x und 2x fast ununterscheidbar. Der
Brief verlangt einen „deutlich hörbaren Frequenz-/Tonhöhensprung pro
Speed-Stufe, nicht nur einen sanften linearen Gradienten".

- [ ] **Step 1: Die drei Frequenz-Parameter ersetzen**

Den Block der drei `travelFreq*`/`travelSpeedRef`-Registrierungen ersetzen
durch:

```supercollider
// Speed -> Mittenfrequenz des Windbands, OKTAVBASIERT statt linear.
//
// Linear (die erste Fassung) lagen die Speed-Klassen 1x und 2x dicht
// beieinander -- genau der "sanfte Gradient", der die Klassen NICHT
// unterscheidbar macht. Oktavbasiert ist jede Verdopplung der
// Geschwindigkeit ein fester Intervallsprung:
//
//   octaves = log2(speed / travelSpeedRef)
//   freq    = travelFreqBase * 2^(octaves * travelOctavesPerStep)
//
// Mit den Defaults unten (Basis 400 Hz bei Speed 16):
//   0.5x -> 200 Hz   1x -> 400   2x -> 800   4x -> 1600   8x -> 3200
// Das ist unverwechselbar, und genau das ist der Zweck: beim Hoeren soll
// klar sein, welcher Speed-Klasse ein Impuls angehoert.
~registerParam.(\travelFreqBase, 400.0, 50.0, 4000.0, nil);
// Speed, die auf travelFreqBase abgebildet wird. 16 ist der Arbeitspunkt der
// Installation (/net/impulse/speed) und damit die 1x-Klasse.
~registerParam.(\travelSpeedRef, 16.0, 1.0, 1500.0, nil);
// Oktaven je Verdopplung der Geschwindigkeit. 1.0 = eine Oktave, also
// "doppelt so schnell klingt eine Oktave hoeher". Groesser = noch
// deutlicher getrennt, kleiner = subtiler.
~registerParam.(\travelOctavesPerStep, 1.0, 0.25, 3.0, nil);
// Rasterung auf die Speed-KLASSE (1 = an). Rundet den Oktavabstand vor der
// Umrechnung auf eine ganze Zahl, damit jeder Impuls derselben Klasse auf
// exakt derselben Tonhoehe zischt.
//
// Ohne die Rasterung verschmieren die Klassen, sobald
// /net/impulse/speedQuantize/jitter oder splitSpeedJitter > 0 stehen -- die
// Zuordenbarkeit haenge dann daran, wie hoch der Jitter gerade steht. Mit
// Rasterung ist sie unabhaengig davon.
~registerParam.(\travelSnap, 1.0, 0.0, 1.0, nil);
// Harte Grenzen, damit ein extremer Regler nichts Unhoerbares oder
// Schmerzhaftes erzeugt. Weit genug fuer 0.5x .. 8x bei den Defaults.
~registerParam.(\travelFreqMin,   80.0,  20.0,  2000.0, nil);
~registerParam.(\travelFreqMax, 6000.0, 200.0, 16000.0, nil);
```

- [ ] **Step 2: Die Umrechnung im Stream-Handler ersetzen**

Im `OSCdef(\impulseStream, ...)` den `travelFreq`-Block ersetzen:

```supercollider
                // Speed -> Mittenfrequenz, oktavbasiert (siehe die
                // ~registerParam-Aufrufe oben fuer die Begruendung).
                // speed = 0 (alter Processing-Stand ohne fuenftes Argument)
                // wuerde log2(0) = -inf geben, deshalb der max().
                travelOct = (max(speed, 0.001) / ~travelSpeedRef).log2;
                if (~travelSnap > 0.5) { travelOct = travelOct.round };
                travelFreq = ~travelFreqBase
                    * (2 ** (travelOct * ~travelOctavesPerStep));
                travelFreq = travelFreq.clip(~travelFreqMin, ~travelFreqMax);
```

Die Variablenliste am Kopf des Handlers erweitern:

```supercollider
        var travelFreq, travelOct, bias;
```

Die Zeile, die den Bias auf die Frequenz multipliziert, **bleibt** — der
Regionen-Bias verschiebt die Klangfarbe innerhalb der Klasse:

```supercollider
                travelFreq = (travelFreq * bias[\brightness]).clip(20, 18000);
```

- [ ] **Step 3: Den Dateikopf nachziehen**

Den Travel-Sound-Block im Kopfkommentar so ersetzen, dass die drei alten
Frequenz-Zeilen durch die sechs neuen ersetzt werden:

```supercollider
//   /klangnetz/param/travelFreqBase       50 .. 4000    Default 400
//   /klangnetz/param/travelSpeedRef        1 .. 1500    Default 16
//   /klangnetz/param/travelOctavesPerStep  0.25 .. 3    Default 1.0
//   /klangnetz/param/travelSnap            0 / 1        Default 1
//   /klangnetz/param/travelFreqMin        20 .. 2000    Default 80
//   /klangnetz/param/travelFreqMax       200 .. 16000   Default 6000
//     Mittenfrequenz des Bands, OKTAVBASIERT ueber die gemeldete
//     Geschwindigkeit (LEDs/s, fuenftes Argument von /net/impulse):
//       freq = travelFreqBase * 2^(log2(speed/travelSpeedRef) * OctavesPerStep)
//     Jede Verdopplung der Geschwindigkeit ist damit ein fester
//     Intervallsprung -- bei den Defaults 200/400/800/1600/3200 Hz fuer die
//     Speed-Klassen 0.5x/1x/2x/4x/8x. travelSnap rundet auf die Klasse,
//     damit der Jitter die Zuordenbarkeit nicht verschmiert. Wirken erst
//     auf NEUE Drohnen -- die Umrechnung passiert in sclang beim Anlegen
//     des Synths.
```

- [ ] **Step 4: Klammerbilanz prüfen**

```bash
python3 -c "
import re
s=open('supercollider/klangnetz_bells.scd').read()
s=re.sub(r'//[^\n]*','',s)
s=re.sub(r'\"(\\\\.|[^\"\\\\])*\"','\"\"',s)
for a,b,n in [('(',')','runde'),('[',']','eckige'),('{','}','geschweifte')]:
    print(n, s.count(a)-s.count(b))
"
```

Expected: alle drei `0`.

Zusätzlich die Rechnung von Hand gegenprüfen (reine Python-Arithmetik, kein
sclang nötig):

```bash
python3 -c "
import math
base, ref, per = 400.0, 16.0, 1.0
for m in (0.5, 1, 2, 4, 8):
    speed = 16.0*m
    oct_ = round(math.log2(speed/ref))
    print('%4sx -> %7.1f Hz' % (m, base * 2**(oct_*per)))
"
```

Expected: `200.0 / 400.0 / 800.0 / 1600.0 / 3200.0`.

- [ ] **Step 5: Commit**

```bash
git add supercollider/klangnetz_bells.scd
git commit -m "Travel-Sound: oktavbasierte Speed-Abbildung statt linearer

Linear lagen die Speed-Klassen 1x und 2x dicht beieinander - genau der
sanfte Gradient, der sie NICHT unterscheidbar macht. Oktavbasiert ist jede
Verdopplung ein fester Intervallsprung: 200/400/800/1600/3200 Hz fuer
0.5x/1x/2x/4x/8x.

travelSnap rundet auf die Speed-Klasse, damit Jitter die Zuordenbarkeit
nicht verschmiert - sonst haengt sie daran, wie hoch der Jitter steht.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Server — Metadaten für die Spezial-Sektionen und die SC-Registry

**Files:**
- Modify: `webui/server.py`, `webui/test_webui.py`

**Interfaces:**
- Produces:
  - `NOTE_VALUES: List[Tuple[int, str, str]]` — (Wert, Symbol, Name)
  - `SPEED_CLASSES: List[Tuple[str, str]]` — (Adress-Suffix, Anzeigename)
  - `SC_PARAMS: List[Dict]` — Spiegel der `.scd`-Registry
  - `build_sequencer(values) -> Optional[Dict]`
  - `SEQUENCER_ADDRESSES: Set[str]` — aus dem generischen Rendering entfernt
  - Endpoint `POST /api/sc` → sendet an Port 8002
  - Bootstrap-Feld `"sequencer"`, `"speedClasses"`, `"scParams"`

- [ ] **Step 1: Tabellen ergänzen**

Nach `SPLIT_GROUP_PREFIXES` in `webui/server.py`:

```python
# Notenwerte des Sequencers: Wert, Symbol, Name. Die Symbole sind
# U+1D15D..U+1D161 (MUSICAL SYMBOL WHOLE NOTE .. SIXTEENTH NOTE) mit
# ASCII-Rueckfall im Namen -- nicht jede Windows-Schrift hat die
# SMuFL-Zeichen, deshalb steht der Name immer daneben und nie nur das
# Symbol allein.
NOTE_VALUES: List[Tuple[int, str, str]] = [
    (1, "\U0001D15D", "Ganze"),
    (2, "\U0001D15E", "Halbe"),
    (4, "\U0001D15F", "Viertel"),
    (8, "\U0001D160", "Achtel"),
    (16, "\U0001D161", "Sechzehntel"),
]

# Speed-Klassen aus SpeedQuantizer.MULTIPLIERS, gleiche Reihenfolge.
# Adress-Suffix und Anzeigename; das Suffix spiegelt die Java-Seite
# (Punkt vermieden, siehe Kommentar dort).
SPEED_CLASSES: List[Tuple[str, str]] = [
    ("0x5", "0,5x"),
    ("1x", "1x"),
    ("2x", "2x"),
    ("4x", "4x"),
    ("8x", "8x"),
]
SPEED_WEIGHT_PREFIX = "/net/impulse/speedQuantize/weight/"

# Kurzerklaerungen fuer Parameter, deren Adresse allein nicht verraet, was
# sie tun. Der Brief verlangt das ausdruecklich fuer die Split-Parameter
# ("nicht nur Adresse+Zahl wie aktuell generisch").
DESCRIPTIONS: Dict[str, str] = {
    "/net/impulse/splitSpeedJitter":
        "Streut die Geschwindigkeit der Kinder an einer Kreuzung. "
        "0 = alle Zweige exakt so schnell wie der Elternimpuls.",
    "/net/impulse/splitLifetimeJitter":
        "Streut die Lebensdauer der Kinder an einer Kreuzung, ohne ihre "
        "Helligkeit zu aendern. 0 = Geschwister sterben synchron.",
    "/net/impulse/speedQuantize/enabled":
        "Laesst neue Impulse mit einem rhythmischen Vielfachen von "
        "/net/impulse/speed spawnen statt immer mit genau diesem Wert.",
    "/net/impulse/speedQuantize/jitter":
        "Swing auf der gezogenen Speed-Klasse. 0 = exakt das Vielfache.",
    "/net/sequencer/enabled":
        "Not-Aus fuer alle sechs Tracks. Die Taktuhr laeuft weiter.",
    "/net/sequencer/bpm":
        "Gemeinsames Tempo aller Tracks. Ein Wechsel aendert die Rate, "
        "nicht die Position - kein Sprung.",
}
```

- [ ] **Step 2: Die SC-Registry spiegeln**

Ebenfalls nach den Tabellen:

```python
# Die Sound-Parameter aus supercollider/klangnetz_bells.scd. Sie laufen NICHT
# durch remoteSettings.txt -- das ist die Parameterliste von imPulse, SC hat
# seine eigene Registry (~registerParam in der .scd) und einen eigenen Port.
#
# Diese Tabelle ist eine HANDGEPFLEGTE Kopie davon. Sie kann veralten: wer in
# der .scd einen Parameter ergaenzt, ergaenzt ihn auch hier. Die Alternative
# waere, die .scd zu parsen -- dafuer muesste der Server sclang-Syntax lesen,
# und ein Parser, der bei der naechsten Umformatierung still das Falsche
# liefert, ist schlechter als eine Liste, deren Pflege sichtbar ist.
#
# Es gibt KEINEN Rueckkanal von SuperCollider: die Werte hier sind die
# Defaults aus der .scd, nicht der Live-Zustand. Steht sclang nicht, geht die
# Nachricht ins Leere -- fire-and-forget, wie /sc/preset/load.
SC_OSC_PORT = 8002
SC_PARAM_PREFIX = "/klangnetz/param/"
SC_PARAMS: List[Dict[str, Any]] = [
    {"name": "masterVolume", "default": 1.0, "min": 0.0, "max": 1.5,
     "group": "Master", "description": "Gain nach dem Panning, vor dem Limiter."},
    {"name": "reverbMix", "default": 0.35, "min": 0.0, "max": 1.0,
     "group": "Master", "description": "Trocken/nass des Halls hinter dem Panning."},
    {"name": "reverbRoom", "default": 0.5, "min": 0.0, "max": 1.0,
     "group": "Master", "description": "Gefuehlte Raumgroesse."},
    {"name": "reverbDamp", "default": 0.5, "min": 0.0, "max": 1.0,
     "group": "Master", "description": "Hoehendaempfung im Hallschweif."},
    {"name": "panSharpness", "default": 1.0, "min": 0.1, "max": 8.0,
     "group": "Master", "description": "Schaerfe der Ortung. 1 = Referenz."},
    {"name": "brightness", "default": 1.0, "min": 0.0, "max": 2.0,
     "group": "Glocke", "description": "Amp der oberen vier Teiltoene."},
    {"name": "detune", "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Glocke", "description": "1 = metallisch, 0 = rein harmonisch."},
    {"name": "regionBiasAmount", "default": 0.6, "min": 0.0, "max": 1.0,
     "group": "Glocke", "description":
     "Staerke des Klangbias nach Netzregion (vier Quadranten). 0 = aus."},
    {"name": "droneLpfMult", "default": 6.0, "min": 1.0, "max": 12.0,
     "group": "Travel-Sound", "description": "Filter der Tonschicht der Drohne."},
    {"name": "travelMix", "default": 0.0, "min": 0.0, "max": 1.0,
     "group": "Travel-Sound", "description":
     "Crossfade Tondrohne <-> Windband. 0 = kein Travel-Sound."},
    {"name": "travelRq", "default": 0.35, "min": 0.02, "max": 1.0,
     "group": "Travel-Sound", "description": "Bandbreite. Klein = pfeifend."},
    {"name": "travelAmpScale", "default": 1.0, "min": 0.0, "max": 2.0,
     "group": "Travel-Sound", "description": "Pegel nur der Rauschschicht."},
    {"name": "travelFreqBase", "default": 400.0, "min": 50.0, "max": 4000.0,
     "group": "Travel-Sound", "description": "Frequenz bei der 1x-Speed-Klasse."},
    {"name": "travelSpeedRef", "default": 16.0, "min": 1.0, "max": 1500.0,
     "group": "Travel-Sound", "description": "Speed, die als 1x gilt."},
    {"name": "travelOctavesPerStep", "default": 1.0, "min": 0.25, "max": 3.0,
     "group": "Travel-Sound", "description":
     "Oktaven je Verdopplung der Speed. Groesser = Klassen deutlicher getrennt."},
    {"name": "travelSnap", "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Travel-Sound", "description":
     "Rastet die Frequenz auf die Speed-Klasse, damit Jitter sie nicht verschmiert."},
    {"name": "travelFreqMin", "default": 80.0, "min": 20.0, "max": 2000.0,
     "group": "Travel-Sound", "description": "Untere harte Grenze."},
    {"name": "travelFreqMax", "default": 6000.0, "min": 200.0, "max": 16000.0,
     "group": "Travel-Sound", "description": "Obere harte Grenze."},
]


def sc_param_groups() -> List[Dict[str, Any]]:
    """Die SC-Parameter nach ihrer group gebuendelt, Reihenfolge wie oben."""
    order: List[str] = []
    by_group: Dict[str, List[Dict[str, Any]]] = {}
    for entry in SC_PARAMS:
        group = entry["group"]
        if group not in by_group:
            by_group[group] = []
            order.append(group)
        item = dict(entry)
        item["address"] = SC_PARAM_PREFIX + entry["name"]
        by_group[group].append(item)
    return [{"title": g, "params": by_group[g]} for g in order]
```

- [ ] **Step 3: Sequencer- und Speed-Klassen-Metadaten bauen**

```python
SEQUENCER_PREFIX = "/net/sequencer/"
SEQUENCER_TRACK_COUNT = 6
# Reihenfolge im Track-Panel. noteValue steht bewusst vorn: er bestimmt, wie
# der Track ueberhaupt klingt.
SEQUENCER_TRACK_FIELDS = ["noteValue", "repeatCount", "energy",
                          "swingJitter", "originStripeOverride"]


def build_sequencer(by_address: Dict[str, Parameter]) -> Optional[Dict[str, Any]]:
    """Baut die Beschreibung des Sequencer-Panels, oder None.

    None heisst: dieser imPulse-Stand kennt den Sequencer nicht (aeltere
    remoteSettings.txt). Dann faellt das UI stillschweigend auf das
    generische Rendering zurueck, statt eine leere Sektion zu zeigen.
    """
    bpm = by_address.get(SEQUENCER_PREFIX + "bpm")
    enabled = by_address.get(SEQUENCER_PREFIX + "enabled")
    if bpm is None or enabled is None:
        return None
    tracks = []
    for i in range(SEQUENCER_TRACK_COUNT):
        base = "%strack%d/" % (SEQUENCER_PREFIX, i)
        track_enabled = by_address.get(base + "enabled")
        if track_enabled is None:
            continue
        fields = {}
        for field in SEQUENCER_TRACK_FIELDS:
            param = by_address.get(base + field)
            if param is not None:
                fields[field] = param.as_dict()
        tracks.append({
            "index": i,
            "enabled": track_enabled.as_dict(),
            "fields": fields,
        })
    if not tracks:
        return None
    return {
        "bpm": bpm.as_dict(),
        "enabled": enabled.as_dict(),
        "tracks": tracks,
        "noteValues": [{"value": v, "symbol": s, "name": n}
                       for v, s, n in NOTE_VALUES],
    }


def build_speed_classes(by_address: Dict[str, Parameter]) -> Optional[Dict[str, Any]]:
    """Beschreibung der Speed-Klassen-Sektion, oder None wenn unbekannt."""
    enabled = by_address.get("/net/impulse/speedQuantize/enabled")
    if enabled is None:
        return None
    weights = []
    for suffix, label in SPEED_CLASSES:
        param = by_address.get(SPEED_WEIGHT_PREFIX + suffix)
        if param is None:
            continue
        entry = param.as_dict()
        entry["label"] = label
        weights.append(entry)
    if not weights:
        return None
    jitter = by_address.get("/net/impulse/speedQuantize/jitter")
    return {
        "enabled": enabled.as_dict(),
        "jitter": jitter.as_dict() if jitter is not None else None,
        "weights": weights,
    }


def sequencer_addresses(sequencer: Optional[Dict[str, Any]],
                        speed: Optional[Dict[str, Any]]) -> Set[str]:
    """Adressen, die eine Spezial-Sektion selbst rendert.

    Sie werden aus dem generischen Gruppen-Rendering entfernt, damit kein
    Regler doppelt auf der Seite steht -- zwei Bedienelemente fuer denselben
    Parameter waeren zwei Anzeigen, die auseinanderlaufen koennen.
    """
    taken: Set[str] = set()
    if sequencer:
        taken.add(sequencer["bpm"]["address"])
        taken.add(sequencer["enabled"]["address"])
        for track in sequencer["tracks"]:
            taken.add(track["enabled"]["address"])
            for field in track["fields"].values():
                taken.add(field["address"])
    if speed:
        taken.add(speed["enabled"]["address"])
        if speed.get("jitter"):
            taken.add(speed["jitter"]["address"])
        for weight in speed["weights"]:
            taken.add(weight["address"])
    return taken
```

`Set` und `Optional` in den `typing`-Import aufnehmen, falls nicht vorhanden.

- [ ] **Step 4: In die Payload einhängen**

In der Funktion, die die Bootstrap-/`/api/parameters`-Payload baut
(dort, wo `build_groups(...)` gerufen wird): vor `build_groups` die
Spezial-Sektionen bauen, deren Adressen herausfiltern, und die drei neuen
Felder ergänzen. `DESCRIPTIONS` in `Parameter.as_dict()` einweben, indem
`as_dict` am Ende `"help": DESCRIPTIONS.get(self.address)` ergänzt.

- [ ] **Step 5: Den SC-Endpoint ergänzen**

Neben dem bestehenden `OscSender` einen zweiten für Port 8002 anlegen und:

```python
@app.post("/api/sc")
def api_sc():
    """Einen Sound-Parameter an SuperCollider schicken (Port 8002).

    Eigener Sender, eigener Port: die /klangnetz/param/*-Adressen gehoeren
    zur SC-Registry, nicht zu imPulse. Fire-and-forget, es gibt keinen
    Rueckkanal -- laeuft sclang nicht, merkt das UI es nicht.
    """
    payload = request.get_json(silent=True) or {}
    name = str(payload.get("name", ""))
    known = {p["name"]: p for p in SC_PARAMS}
    entry = known.get(name)
    if entry is None:
        return jsonify({"ok": False, "error": "unbekannter SC-Parameter: %r" % name}), 400
    try:
        value = float(payload.get("value"))
    except (TypeError, ValueError):
        return jsonify({"ok": False, "error": "Wert ist keine Zahl"}), 400
    if value != value:  # NaN
        return jsonify({"ok": False, "error": "Wert ist keine Zahl"}), 400
    value = max(entry["min"], min(entry["max"], value))
    address = SC_PARAM_PREFIX + name
    sc_sender.send(address, value)
    return jsonify({"ok": True, "address": address, "value": value})
```

- [ ] **Step 6: Tests im vorhandenen Stil ergänzen**

An `webui/test_webui.py` anfügen (dem Stil der vorhandenen `unittest`-Klassen
folgen):

```python
class SequencerSectionTest(unittest.TestCase):
    """build_sequencer/build_speed_classes und die Adress-Entnahme."""

    def _params(self):
        lines = [
            "float\t/net/sequencer/bpm\tx\t60\t20\t200",
            "int\t/net/sequencer/enabled\tx\t0\t0\t1",
        ]
        for i in range(6):
            base = "/net/sequencer/track%d/" % i
            lines += [
                "int\t%senabled\tx\t0\t0\t1" % base,
                "int\t%snoteValue\tx\t4\t1\t16" % base,
                "int\t%srepeatCount\tx\t3\t1\t8" % base,
                "float\t%senergy\tx\t0.6\t0\t1" % base,
                "float\t%sswingJitter\tx\t0\t0\t1" % base,
                "int\t%soriginStripeOverride\tx\t-1\t-1\t29" % base,
            ]
        lines += [
            "int\t/net/impulse/speedQuantize/enabled\tx\t0\t0\t1",
            "float\t/net/impulse/speedQuantize/jitter\tx\t0\t0\t1",
        ]
        for suffix, _label in server.SPEED_CLASSES:
            lines.append("float\t%s%s\tx\t0\t0\t100"
                         % (server.SPEED_WEIGHT_PREFIX, suffix))
        parsed = server.parse_settings("\n".join(lines))
        return {p.address: p for p in parsed}

    def test_sequencer_has_all_six_tracks(self):
        seq = server.build_sequencer(self._params())
        self.assertIsNotNone(seq)
        self.assertEqual(len(seq["tracks"]), 6)
        self.assertEqual([t["index"] for t in seq["tracks"]], list(range(6)))

    def test_note_values_carry_symbol_and_name(self):
        seq = server.build_sequencer(self._params())
        values = [n["value"] for n in seq["noteValues"]]
        self.assertEqual(values, [1, 2, 4, 8, 16])
        for note in seq["noteValues"]:
            self.assertTrue(note["symbol"])
            self.assertTrue(note["name"])

    def test_missing_sequencer_yields_none_not_an_empty_panel(self):
        # Aelterer imPulse-Stand: das UI soll auf das generische Rendering
        # zurueckfallen, nicht eine leere Sektion zeigen.
        parsed = server.parse_settings("float\t/net/impulse/speed\tx\t16\t1\t1500")
        by_address = {p.address: p for p in parsed}
        self.assertIsNone(server.build_sequencer(by_address))
        self.assertIsNone(server.build_speed_classes(by_address))

    def test_speed_classes_keep_the_java_order(self):
        speed = server.build_speed_classes(self._params())
        labels = [w["label"] for w in speed["weights"]]
        self.assertEqual(labels, [l for _s, l in server.SPEED_CLASSES])

    def test_special_addresses_are_removed_from_generic_groups(self):
        by_address = self._params()
        seq = server.build_sequencer(by_address)
        speed = server.build_speed_classes(by_address)
        taken = server.sequencer_addresses(seq, speed)
        # Jede Adresse, die eine Spezial-Sektion rendert, muss drin sein -
        # sonst stuende sie doppelt auf der Seite.
        self.assertIn("/net/sequencer/bpm", taken)
        self.assertIn("/net/sequencer/track5/energy", taken)
        self.assertIn("/net/impulse/speedQuantize/weight/8x", taken)
        self.assertEqual(len(taken), 2 + 6*6 + 2 + len(server.SPEED_CLASSES))


class ScParamTest(unittest.TestCase):
    """Die handgepflegte Spiegelung der SC-Registry."""

    def test_every_default_is_inside_its_range(self):
        for entry in server.SC_PARAMS:
            self.assertGreaterEqual(entry["default"], entry["min"], entry["name"])
            self.assertLessEqual(entry["default"], entry["max"], entry["name"])

    def test_names_are_unique(self):
        names = [p["name"] for p in server.SC_PARAMS]
        self.assertEqual(len(names), len(set(names)))

    def test_groups_keep_insertion_order(self):
        groups = server.sc_param_groups()
        self.assertEqual([g["title"] for g in groups],
                         ["Master", "Glocke", "Travel-Sound"])
        for group in groups:
            for param in group["params"]:
                self.assertTrue(param["address"].startswith("/klangnetz/param/"))

    def test_travel_defaults_match_the_scd_file(self):
        """Der haeufigste Fehler ist eine Tabelle, die von der .scd abdriftet."""
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(server.__file__))), "supercollider",
            "klangnetz_bells.scd")
        with open(path, encoding="utf-8") as handle:
            scd = handle.read()
        for entry in server.SC_PARAMS:
            self.assertIn("~registerParam.(\\%s" % entry["name"], scd,
                          "%s fehlt in der .scd" % entry["name"])
```

- [ ] **Step 7: Tests laufen lassen**

`python3 webui/test_webui.py` → alle bestanden.

- [ ] **Step 8: Commit**

```bash
git add webui/server.py webui/test_webui.py
git commit -m "webui-Server: Metadaten fuer Sequencer, Speed-Klassen und SC-Parameter

Die Spezial-Sektionen bekommen strukturierte Daten statt einer flachen
Reglerliste; ihre Adressen fallen aus dem generischen Rendering heraus,
damit kein Parameter zwei Bedienelemente hat.

Die SC-Parameter laufen nicht durch remoteSettings.txt - eigene Tabelle,
eigener Sender auf Port 8002. Ein Test prueft gegen die .scd, dass die
Tabelle nicht abdriftet.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Der Design-Pass — Sequencer-Panel, Notenwerte, Verteilung, SC-Sektion

**Files:**
- Modify: `webui/static/app.js`, `webui/static/style.css`,
  `webui/templates/index.html`

Gestaltungsregeln für diesen Pass (das vorhandene Schema wird erweitert, nicht
ersetzt — der Brief verlangt ausdrücklich keinen Stilbruch):

- Die `:root`-Variablen bleiben, es kommen nur welche dazu
  (`--accent-2` für den Sequencer, `--track-N` für die sechs Spuren,
  `--pulse` für die Rasteranzeige).
- **Hierarchie:** BPM als große Ziffer (`clamp(2.6rem, 7vw, 3.6rem)`,
  tabellarische Ziffern), darunter der Regler, darunter das Track-Raster. Die
  sechs Tracks als Karten in `repeat(auto-fill, minmax(240px, 1fr))`, jede mit
  einem farbigen Rand links, damit sie im Dunkeln auseinanderzuhalten sind.
- **Notenwerte** als segmentierte Schalterleiste mit Symbol **und** Kürzel
  (`𝅗𝅥 1/2`), nicht als Zahl und nicht als Symbol allein — nicht jede
  Windows-Schrift hat U+1D15D..U+1D161.
- Ausgeschaltete Tracks auf `opacity: .55` und ohne Farbrand: der Blick soll
  ohne Lesen finden, was läuft.
- Kein Hover-abhängiges Bedienelement (Touch auf dem Laptop-Trackpad im
  Dunkeln), Mindestgröße für Klickflächen 2 rem.

- [ ] **Step 1: Container in `index.html`**

Zwischen `<p class="status">` und `<section class="presets">`:

```html
  <div id="sequencerHost"></div>
```

und nach `<main id="groups"></main>`:

```html
  <div id="scHost"></div>
```

- [ ] **Step 2: CSS ergänzen**

An `webui/static/style.css` anhängen (die vorhandenen Regeln bleiben
unangetastet):

```css
/* ---------------------------------------------------------------------------
   Sequencer, Speed-Klassen, SC-Parameter.

   Erweitert das Schema oben, ersetzt es nicht: dieselben --bg/--panel/--line,
   dieselben Radien, dieselbe Monospace fuer Zahlen. Neu sind nur die sechs
   Spurfarben und die Rasteranzeige.
   --------------------------------------------------------------------------- */

:root {
  --accent-2: #c07cff;
  --pulse: #ffd166;
  --track-0: #4ea1ff;
  --track-1: #4ec982;
  --track-2: #ffb648;
  --track-3: #ff6b9d;
  --track-4: #c07cff;
  --track-5: #45d6c8;
}

.seq { margin: 1rem 1rem 0; }

.seq-top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem 1.5rem;
  padding: 0.8rem;
  border-bottom: 1px solid var(--line);
}

.seq-bpm { display: flex; align-items: baseline; gap: 0.5rem; }
.seq-bpm-value {
  font: 700 clamp(2.6rem, 7vw, 3.6rem)/1 ui-monospace, "Consolas", monospace;
  font-variant-numeric: tabular-nums;
  color: var(--text);
  letter-spacing: -0.02em;
}
.seq-bpm-unit { font-size: 0.8rem; color: var(--muted); letter-spacing: 0.14em; }

.seq-bpm-slider { flex: 1 1 16rem; min-width: 12rem; display: flex; align-items: center; gap: 0.6rem; }
.seq-bpm-slider input[type=range] { flex: 1 1 auto; accent-color: var(--accent-2); }

/* Der grosse Not-Aus. Deutlich als Schalter erkennbar, nicht als Checkbox
   zwischen dreissig anderen. */
.seq-power {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  min-height: 2.4rem;
  background: var(--panel-hi);
  border: 1px solid var(--line);
  border-radius: 999px;
  cursor: pointer;
  user-select: none;
  font-size: 0.85rem;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.seq-power .dot {
  width: 0.7rem; height: 0.7rem; border-radius: 50%;
  background: var(--line);
  box-shadow: none;
  transition: background 0.15s, box-shadow 0.15s;
}
.seq-power.on { border-color: var(--ok); color: var(--ok); }
.seq-power.on .dot { background: var(--ok); box-shadow: 0 0 0.5rem var(--ok); }
.seq-power input { position: absolute; opacity: 0; pointer-events: none; }

.seq-tracks {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 0.7rem;
  padding: 0.8rem;
}

.track {
  background: var(--panel-hi);
  border: 1px solid var(--line);
  border-left: 3px solid var(--tc, var(--line));
  border-radius: 6px;
  padding: 0.6rem 0.7rem;
  display: grid;
  gap: 0.5rem;
  transition: opacity 0.15s;
}
.track.off { opacity: 0.55; border-left-color: var(--line); }

.track-head { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.track-title {
  font: 0.78rem/1 ui-monospace, "Consolas", monospace;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--tc, var(--muted));
}
.track.off .track-title { color: var(--muted); }

/* Rasteranzeige: die BERECHNETE Taktposition, nicht eine Rueckmeldung aus
   dem Sketch (dafuer gaebe es keinen Kanal, siehe app.js). Deshalb heisst
   sie im UI "Raster" und nicht "feuert". */
.track-pulse {
  width: 0.85rem; height: 0.85rem; border-radius: 50%;
  background: var(--line);
  flex: 0 0 auto;
}
.track-pulse.lit {
  background: var(--pulse);
  box-shadow: 0 0 0.6rem var(--pulse);
}

.track-switch { display: inline-flex; align-items: center; gap: 0.4rem; cursor: pointer; min-height: 1.6rem; }
.track-switch input { width: 1.1rem; height: 1.1rem; accent-color: var(--ok); }

/* Notenwert-Leiste: Symbol UND Kuerzel. Nicht jede Windows-Schrift hat
   U+1D15D..U+1D161, ein Symbol allein waere dort ein leeres Kaestchen. */
.notes { display: flex; gap: 0.2rem; }
.notes button {
  flex: 1 1 0;
  min-width: 0;
  min-height: 2.1rem;
  padding: 0.2rem 0.1rem;
  display: grid;
  gap: 0.05rem;
  place-items: center;
  background: #12151a;
  border: 1px solid var(--line);
  border-radius: 4px;
  cursor: pointer;
  color: var(--muted);
}
.notes button .sym { font-size: 1rem; line-height: 1; }
.notes button .lbl { font-size: 0.6rem; letter-spacing: 0.02em; }
.notes button[aria-pressed=true] {
  background: var(--tc, var(--accent));
  border-color: var(--tc, var(--accent));
  color: #10131a;
  font-weight: 700;
}

.mini { display: grid; gap: 0.3rem; }
.mini-row { display: flex; align-items: center; gap: 0.5rem; }
.mini-row label {
  flex: 0 0 5.4rem;
  font-size: 0.7rem;
  color: var(--muted);
  letter-spacing: 0.03em;
}
.mini-row input[type=range] { flex: 1 1 auto; min-width: 0; height: 1.2rem; accent-color: var(--tc, var(--accent)); }
.mini-row output {
  flex: 0 0 3.2rem;
  text-align: right;
  font: 0.74rem ui-monospace, "Consolas", monospace;
  font-variant-numeric: tabular-nums;
  color: var(--text);
}

/* Speed-Klassen: der Verteilungsbalken macht aus fuenf Zahlen ein Bild. */
.dist {
  display: flex;
  height: 1.6rem;
  border-radius: 4px;
  overflow: hidden;
  border: 1px solid var(--line);
  margin-bottom: 0.5rem;
  background: #12151a;
}
.dist-seg {
  display: grid;
  place-items: center;
  font: 0.66rem ui-monospace, "Consolas", monospace;
  color: #10131a;
  overflow: hidden;
  white-space: nowrap;
  transition: flex-grow 0.2s;
  min-width: 0;
}
.dist-empty { display: grid; place-items: center; width: 100%; color: var(--muted); font-size: 0.72rem; }

.sc { margin: 0 1rem 1rem; }
.sc-note {
  padding: 0.5rem 0.8rem;
  font-size: 0.72rem;
  color: var(--warn);
  border-bottom: 1px solid var(--line);
  background: #1a1712;
}

.help { margin: 0.15rem 0 0; font-size: 0.7rem; color: var(--muted); line-height: 1.35; }

@media (max-width: 600px) {
  .seq, .sc { margin-left: 0.6rem; margin-right: 0.6rem; }
  .seq-tracks { grid-template-columns: 1fr; padding: 0.6rem; }
  .mini-row label { flex-basis: 4.6rem; }
}
```

- [ ] **Step 3: JavaScript — Sequencer-Panel, Speed-Klassen, SC-Sektion**

An `webui/static/app.js` anfügen und aus `render()` aufrufen. Der vollständige
Code steht in Task 5 der Umsetzung; die Bausteine sind:

- `buildSequencer(data)` — baut `#sequencerHost` neu auf.
- `noteBar(track, param)` — die segmentierte Notenwert-Leiste; sendet den
  gerasteten Wert und markiert den aktiven Knopf über `aria-pressed`.
- `miniSlider(labelText, param, initial, onChange)` — kompakter Regler mit
  Beschriftung und `output`.
- `buildSpeedClasses(data)` — Verteilungsbalken plus fünf Gewichtsregler; der
  Balken rechnet in jedem `input` neu und zeigt Prozente.
- `buildScSection(data)` — die SC-Gruppen, jede Änderung geht per
  `POST /api/sc`.
- `startPulseClock()` — ein `requestAnimationFrame`-Ticker, der aus BPM und
  Notenwert die Rasterposition rechnet und die Punkte setzt. Läuft nur, wenn
  der Sequencer eingeschaltet ist; er ist ausdrücklich die Uhr des Browsers
  und nicht die des Sketches.

- [ ] **Step 4: Sichtprüfung**

Der Server lässt sich hier ohne imPulse starten — er liest nur eine Datei:

```bash
cd /home/birk/github/imPulse/webui && python3 - <<'PY'
import server
app = server.app
server.settings_path = server.os.path.abspath('../data/remoteSettings.txt')
PY
```

Praktikabler: die Payload direkt prüfen, ohne Browser:

```bash
cd /home/birk/github/imPulse && python3 -c "
import sys; sys.path.insert(0, 'webui')
import server
params = server.parse_settings(open('data/remoteSettings.txt').read())
by = {p.address: p for p in params}
seq = server.build_sequencer(by)
spd = server.build_speed_classes(by)
print('Tracks:', len(seq['tracks']) if seq else None)
print('Speed-Klassen:', [w['label'] for w in spd['weights']] if spd else None)
print('aus generischem Rendering entfernt:', len(server.sequencer_addresses(seq, spd)))
"
```

`data/remoteSettings.txt` ist nicht im Repo (`.gitignore`) und entsteht erst
beim Start von imPulse. Fehlt sie, diesen Schritt überspringen und stattdessen
`python3 webui/test_webui.py` als Nachweis nehmen — die Suite baut sich ihre
Parameterliste selbst.

- [ ] **Step 5: Tests**

`python3 webui/test_webui.py` → alle bestanden.

- [ ] **Step 6: Commit**

```bash
git add webui/static webui/templates
git commit -m "webui: Design-Pass fuer Sequencer, Speed-Klassen und Sound

BPM als grosse Ziffer mit eigenem Not-Aus-Schalter, darunter sechs
Track-Karten mit eigener Spurfarbe; Notenwerte als segmentierte Leiste mit
Symbol UND Kuerzel, weil nicht jede Windows-Schrift U+1D15D..U+1D161 hat.
Ausgeschaltete Tracks treten optisch zurueck, damit der Blick ohne Lesen
findet, was laeuft.

Die Speed-Klassen bekommen einen Verteilungsbalken - aus fuenf Zahlen wird
ein Bild.

Die Rasteranzeige je Track ist die Uhr des BROWSERS, nicht eine
Rueckmeldung aus dem Sketch: dafuer gibt es keinen Kanal, Port 8002 haelt
SuperCollider. Sie heisst deshalb Raster und nicht feuert.

Erweitert das vorhandene Farbschema, ersetzt es nicht.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: Doku nachziehen und Abschlussbericht

**Files:**
- Modify: `CLAUDE.md`, `webui/README.md`

- [ ] **Step 1: `CLAUDE.md`** — `SpeedQuantizer` in die Testklassen- und
  Suiten-Liste, ein Abschnitt „Quantisierte Spawn-Geschwindigkeit" unter
  „Impuls-Simulation", die Travel-Sound-Passage auf die Oktav-Abbildung
  umschreiben, und im Web-UI-Abschnitt die drei Spezial-Sektionen samt der
  zwei Grenzen (keine SC-Rückmeldung, Raster ist die Browser-Uhr) nennen.

- [ ] **Step 2: `webui/README.md`** — die SC-Sektion und ihren zweiten Port
  8002 dokumentieren, samt dem Hinweis, dass `SC_PARAMS` handgepflegt ist.

- [ ] **Step 3: Gesamtprüfung**

```bash
test/run.sh && test/build.sh && python3 webui/test_webui.py
```

- [ ] **Step 4: Commit und Bericht**

```bash
git log --oneline master..HEAD
```

**Kein Push, kein Merge.**
