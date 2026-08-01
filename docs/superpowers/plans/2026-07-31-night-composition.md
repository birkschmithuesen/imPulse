# Nacht-Komposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vier zusammenhängende Features für die Nacht-Komposition der
KlangNetz-Installation — Split-Varianz bei Node-Treffern, ein BPM-getakteter
Origin-Sequencer, ein Klangbias nach Netzregion und ein Wind-/Rauschklang für
reisende Impulse.

**Architecture:** Die prüfbare Rechenlogik wandert in drei neue
Processing-freie Klassen (`SplitVariance`, `MusicalClock`, `OriginSequencer`)
nach dem etablierten Muster von `ImpulseOscThrottle`/`ParameterOscillator`; die
Verdrahtung bleibt in `LedNetworkTransportEffect`. Die Klangseite (Features 3
und 4) ist reine SuperCollider-Arbeit in `supercollider/klangnetz_bells.scd`
und hängt nur an einem zusätzlichen, rein additiven fünften OSC-Argument.

**Tech Stack:** Java 8 (Processing-Sketch, flaches Default-Package), oscP5,
SuperCollider 3.11 (`sclang`), Bash-Testrunner ohne Framework, Flask/Python für
das Web-UI.

Entwurf: `docs/superpowers/specs/2026-07-31-night-composition-design.md`.
Brief: `docs/superpowers/specs/2026-07-31-brief-night-composition.md`.

## Global Constraints

- Branch: `feature/night-composition-sequencer`. **Kein Push zum Windows-Laptop,
  kein Merge nach `master` oder `grabicz26`.**
- Alle neuen Effekte defaulten auf **aus** bzw. neutral: `splitSpeedJitter=0`,
  `splitLifetimeJitter=0`, `/net/sequencer/enabled=0`, `travelMix=0`. Ein
  Sketch-Neustart darf nichts ungefragt scharf schalten.
- Neue prüfbare Logik ist **frei von `processing.*`, `oscP5.*` und `netP5.*`** —
  sonst lässt `test/run.sh` sie nicht übersetzen.
- Alle Java-Dateien liegen **flach im Sketch-Ordner**, kein Package, keine
  Unterordner.
- Umlaute in Java-Quelltext und Commit-Messages **umschreiben** (`ae`, `oe`,
  `ue`, `ss`) — der bestehende Code tut das durchgängig. In Markdown und in
  `.scd`-Kommentaren sind Umlaute in Ordnung.
- **Keine von der Kreuzungszahl abgeleitete Zahl als Literal** in Code oder
  Test.
- Testkommando: `test/run.sh` (Umgebungsvariable `IMPULSE_CORE_JAR` ist auf
  diesem Rechner bereits gesetzt). Übersetzungsprüfung des ganzen Sketches:
  `test/build.sh`.
- **Niemals** `test/TimingProbe`, `test/PollProbe` oder `test/PatternProbe`
  laufen lassen — die sprechen die echte Installation an.
- Neue OSC-Parameter tauchen automatisch in `data/remoteSettings.txt` und im
  Web-UI auf; die Datei selbst ist nicht mehr im Repo (`.gitignore`) und wird
  nicht committet.

---

## File Structure

**Neu:**

| Datei | Verantwortung |
|---|---|
| `SplitVariance.java` | Die Jitter-Formel für Split-Kinder. Reine Mathematik, Zufall wird hereingereicht. |
| `MusicalClock.java` | Akkumulierende Beat-Phase aus BPM. Kein Zustand außer Phase und letzter Zeit. |
| `OriginSequencer.java` | Wann feuert welcher Track von welchem Stripe. Kennt keine `TravellingActivation`. |
| `test/SplitVarianceTest.java` | Prüft die Jitter-Formel. |
| `test/MusicalClockTest.java` | Prüft Beat-Akkumulation und Notenwert-Intervalle. |
| `test/OriginSequencerTest.java` | Prüft Feuertakt, `repeatCount`, Override, Wiedereinschalten. |

**Geändert:**

| Datei | Änderung |
|---|---|
| `LedNetworkTransportEffect.java` | `decayScale`-Feld, Split-Jitter, Sequencer-Verdrahtung, Speed im Impulsstrom |
| `supercollider/klangnetz_bells.scd` | Zonen-Bias (Feature 3), Rauschschicht in `\impulseDrone` (Feature 4) |
| `test/run.sh` | drei neue Quellen und drei neue Suiten |
| `webui/server.py` | Gruppierung der Sequencer-Tracks |
| `CLAUDE.md` | neue Klassen, neue Parameter, korrigierter Preset-Hinweis |

---

## Task 1: `SplitVariance` — die Jitter-Formel

**Files:**
- Create: `SplitVariance.java`
- Create: `test/SplitVarianceTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Produces: `static float SplitVariance.jitter(float base, float amount, double random01)`
  — liefert `base * (1 + amount*(random01*2-1))`, nach unten geklemmt auf
  `base * MIN_FACTOR`. `static final float MIN_FACTOR = 0.05f`.

- [ ] **Step 1: Write the failing test**

Create `test/SplitVarianceTest.java`:

```java
public class SplitVarianceTest {

  public static void main(String[] args) throws Exception {
    // amount 0 laesst den Ausgangswert exakt stehen - das ist der
    // Auslieferungszustand beider Split-Parameter, und er muss bitgleich
    // dem bisherigen Verhalten entsprechen, egal welchen Zufall er bekommt
    Check.near("amount 0, Zufall 0", 16.0, SplitVariance.jitter(16f, 0f, 0.0), 1e-6);
    Check.near("amount 0, Zufall 0.5", 16.0, SplitVariance.jitter(16f, 0f, 0.5), 1e-6);
    Check.near("amount 0, Zufall 1", 16.0, SplitVariance.jitter(16f, 0f, 1.0), 1e-6);

    // Zufall 0.5 ist die Mitte des Intervalls und damit neutral,
    // unabhaengig von der Staerke
    Check.near("Zufall 0.5 ist neutral", 16.0, SplitVariance.jitter(16f, 1f, 0.5), 1e-6);
    Check.near("Zufall 0.5 ist auch bei kleiner Staerke neutral",
        16.0, SplitVariance.jitter(16f, 0.2f, 0.5), 1e-6);

    // Die Enden des Zufallsbereichs spannen +-amount auf
    Check.near("Zufall 1 bei Staerke 0.5 gibt das 1.5-fache",
        24.0, SplitVariance.jitter(16f, 0.5f, 1.0), 1e-5);
    Check.near("Zufall 0 bei Staerke 0.5 gibt das 0.5-fache",
        8.0, SplitVariance.jitter(16f, 0.5f, 0.0), 1e-5);

    // Symmetrie um den Ausgangswert: die zwei Enden liegen gleich weit weg
    double hoch = SplitVariance.jitter(100f, 0.3f, 1.0);
    double tief = SplitVariance.jitter(100f, 0.3f, 0.0);
    Check.near("Symmetrie um den Ausgangswert", 100.0, (hoch + tief)/2.0, 1e-4);

    // Untergrenze: bei Staerke 1 und Zufall 0 waere der Faktor exakt 0.
    // Ein Kind mit Speed 0 stuende fuer immer still, eines mit decayScale 0
    // stuerbe nie - zwei unsterbliche Zustaende, die das Netz ueber eine
    // Nacht volllaufen lassen.
    float voll = SplitVariance.jitter(16f, 1f, 0.0);
    Check.that("volle Staerke wird nicht 0", voll > 0f);
    Check.near("sondern auf MIN_FACTOR geklemmt",
        16.0*SplitVariance.MIN_FACTOR, voll, 1e-5);

    // Auch knapp ueber der Grenze wird geklemmt
    Check.near("knapp unterhalb der Grenze ebenfalls geklemmt",
        16.0*SplitVariance.MIN_FACTOR, SplitVariance.jitter(16f, 1f, 0.01), 1e-4);

    // Entartete Eingaben duerfen sich nicht in den LED-Puffer fortpflanzen
    Check.near("NaN-Staerke faellt auf den Ausgangswert zurueck",
        16.0, SplitVariance.jitter(16f, Float.NaN, 0.3), 1e-6);
    Check.near("NaN-Zufall faellt auf den Ausgangswert zurueck",
        16.0, SplitVariance.jitter(16f, 0.5f, Double.NaN), 1e-6);
    Check.near("negative Staerke wirkt wie 0",
        16.0, SplitVariance.jitter(16f, -0.5f, 0.0), 1e-6);
    Check.near("Staerke ueber 1 wird auf 1 geklemmt",
        16.0*SplitVariance.MIN_FACTOR, SplitVariance.jitter(16f, 5f, 0.0), 1e-5);

    // Negative Ausgangswerte kommen vor: speed traegt die Richtung im
    // Vorzeichen, ein rueckwaerts laufendes Kind hat negative Speed.
    // Der Betrag muss genauso streuen, das Vorzeichen erhalten bleiben.
    Check.near("negative Speed behaelt ihr Vorzeichen",
        -24.0, SplitVariance.jitter(-16f, 0.5f, 1.0), 1e-5);
    Check.that("negative Speed wird nicht positiv",
        SplitVariance.jitter(-16f, 1f, 0.0) < 0f);

    // Ausgangswert 0 bleibt 0, ohne NaN
    Check.near("Ausgangswert 0 bleibt 0", 0.0, SplitVariance.jitter(0f, 0.5f, 0.9), 1e-9);

    System.exit(Check.report("SplitVarianceTest"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `test/run.sh SplitVarianceTest`

Expected: FAIL beim Übersetzen mit `cannot find symbol: class SplitVariance`.

- [ ] **Step 3: Write minimal implementation**

Create `SplitVariance.java`:

```java
// Streuung der Kindwerte bei einer Aufspaltung an einem Node.
//
// Warum eine eigene Klasse: LedNetworkTransportEffect haengt an oscP5 und
// laesst sich von test/run.sh nicht uebersetzen. Die Formel soll aber geprueft
// sein - deshalb liegt sie hier, ohne Abhaengigkeit auf processing, oscP5 oder
// netP5. Dasselbe Muster wie ImpulseOscThrottle und ParameterOscillator.
//
// Der Zufall wird HEREINGEREICHT statt hier gezogen. Nur so ist die Formel
// deterministisch pruefbar; der Aufrufer gibt Math.random() hinein.
class SplitVariance {

  // Kleinster erlaubter Faktor. Bei amount=1 und random01=0 waere das Ergebnis
  // sonst exakt 0 - ein Kind mit Speed 0 stuende fuer immer still, ein Kind mit
  // decayScale 0 verloere nie Energie und stuerbe nie. Zwei unsterbliche
  // Zustaende, die das Netz ueber eine Nacht volllaufen lassen, jeweils ohne
  // Fehlermeldung.
  static final float MIN_FACTOR = 0.05f;

  // base * (1 + amount*(random01*2-1)), geklemmt auf mindestens
  // base*MIN_FACTOR.
  //
  // amount ist auf 0..1 geklemmt, random01 wird als 0..1 erwartet. Entartete
  // Eingaben (NaN) liefern den unveraenderten Ausgangswert statt NaN - die
  // Parameter-Range im Sketch schliesst sie ohnehin aus, das hier ist das Netz
  // darunter.
  //
  // Das Vorzeichen von base bleibt erhalten: bei der Geschwindigkeit codiert es
  // die Richtung, ein rueckwaerts laufendes Kind darf durch die Streuung nicht
  // die Richtung wechseln. Deshalb wird der FAKTOR geklemmt, nicht das Ergebnis.
  static float jitter(float base, float amount, double random01) {
    if (Float.isNaN(amount) || Double.isNaN(random01)) {
      return base;
    }
    float a = amount;
    if (a < 0f) {
      a = 0f;
    }
    if (a > 1f) {
      a = 1f;
    }
    double r = random01;
    if (r < 0.0) {
      r = 0.0;
    }
    if (r > 1.0) {
      r = 1.0;
    }
    double factor = 1.0 + a*(r*2.0 - 1.0);
    if (factor < MIN_FACTOR) {
      factor = MIN_FACTOR;
    }
    return (float) (base*factor);
  }
}
```

- [ ] **Step 4: Add the source and the suite to the test runner**

In `test/run.sh`, after the line `[ -f PresetScheduler.java ] && SOURCES="$SOURCES PresetScheduler.java"`, add:

```bash
[ -f SplitVariance.java ] && SOURCES="$SOURCES SplitVariance.java"
```

And in the optional-suite list, change

```bash
  for optional in NodeSelectionTest LedAnchorStoreTest LedPositionMapTest \
                  LedPositionCalibrationTest ImpulseOscThrottleTest \
                  TestPatternsTest PresetStoreTest PresetSchedulerTest \
                  ParameterOscillatorTest; do
```

to

```bash
  for optional in NodeSelectionTest LedAnchorStoreTest LedPositionMapTest \
                  LedPositionCalibrationTest ImpulseOscThrottleTest \
                  TestPatternsTest PresetStoreTest PresetSchedulerTest \
                  ParameterOscillatorTest SplitVarianceTest; do
```

- [ ] **Step 5: Run test to verify it passes**

Run: `test/run.sh SplitVarianceTest`

Expected: `SplitVarianceTest: <n> Pruefungen, alle bestanden` — die Zahl steht
hier bewusst nicht als Literal, sie ändert sich mit jeder ergänzten Prüfung.
Entscheidend ist „alle bestanden" und exit 0.

- [ ] **Step 6: Run the whole suite**

Run: `test/run.sh`

Expected: alle Suiten bestanden, exit 0.

- [ ] **Step 7: Commit**

```bash
git add SplitVariance.java test/SplitVarianceTest.java test/run.sh
git commit -m "SplitVariance: pruefbare Jitter-Formel fuer Split-Kinder

Zufall wird hereingereicht statt hier gezogen, damit die Formel
deterministisch pruefbar ist. Klemmt den Faktor nach unten: bei voller
Staerke waere er sonst exakt 0, und ein Kind mit Speed 0 bzw. decayScale 0
waere unsterblich.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: `decayScale` je Impuls und die zwei Split-Jitter-Parameter

**Files:**
- Modify: `LedNetworkTransportEffect.java`

**Interfaces:**
- Consumes: `SplitVariance.jitter(base, amount, random01)` aus Task 1.
- Produces: Feld `float decayScale` auf `TravellingActivation` (Default `1.0f`),
  Konstruktor `TravellingActivation(float ledIdxPos, int stripeIdx, float speed, float energy, int id, float decayScale)`.
  Zwei neue Parameter `/net/impulse/splitSpeedJitter` und
  `/net/impulse/splitLifetimeJitter` (beide float, Default 0, Range 0..1).

**Warum ein Multiplikator und nicht der absolute Zerfallswert:** heute wirkt
`/net/impulse/lifetime` sofort auf alle lebenden Impulse, und der
Sinus-Randomizer fährt genau diesen Parameter in jedem Frame nach. Ein
absoluter `decayFactor` je Impuls würde den Wert zur Geburt einfrieren — der
Randomizer erreichte nur noch neue Impulse, und der Lifetime-Regler ließe die
lebenden unbeeindruckt. Beides ohne Fehlermeldung. Siehe Entwurf, Feature 1.

- [ ] **Step 1: Feld und Konstruktoren erweitern**

In `LedNetworkTransportEffect.java`, die Klasse `TravellingActivation`
(ab Zeile 244) ersetzen durch:

```java
  //represents one travelling activation
  public class TravellingActivation {
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_) {
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++, 1f);
    }

    // Mit ausdruecklichem decayScale - fuer die Kinder einer Aufspaltung, die
    // ihre Lebensdauer streuen sollen (siehe /net/impulse/splitLifetimeJitter).
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        float decayScale_) {
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++, decayScale_);
    }

    // Mit ausdruecklicher ID - nur fuer den Filler, der die ID seines
    // Elternimpulses uebernimmt statt eine neue zu verbrauchen.
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int id_, float decayScale_) {
      ledIdxPos=ledIdxPos_;
      stripeIdx=stripeIdx_;
      speed=speed_;
      energy=energy_;
      id=id_;
      decayScale=decayScale_;
    }

    int getLedIndex() {
      return (int)(ledIdxPos+0.5f); // global led position
    }
    float ledIdxPos; // absolute led position - used for mapping to led buffer
    int stripeIdx; // stripe the activation was created on
    float speed; // [leds/second] also encodes direction in sign
    float energy; // some measure of strength
    final int id; // fortlaufend, fuer /net/impulse
    // Faktor AUF den globalen impulseLifetime, nicht dessen Ersatz. 1.0 bei
    // jedem normalen Spawn, gestreut nur bei den Kindern einer Aufspaltung.
    //
    // Bewusst ein Multiplikator: mit einem absoluten Zerfallswert je Impuls
    // wuerde jeder Impuls den Wert seiner Geburt einfrieren. Dann erreichte
    // der Sinus-Randomizer (/net/impulse/lifetime/randomize/*) nur noch neu
    // gespawnte Impulse, und ein Operator, der den Lifetime-Regler zieht,
    // saehe die lebenden Impulse unbeeindruckt weiterlaufen - beides ohne
    // Fehlermeldung.
    final float decayScale;
    void setEnergy(float _energy){energy=_energy;}
  }

  //represents fillers needed when high travelling speeds lead to skipping some leds in each frame
  public class TravellingActivationFiller extends TravellingActivation {
    TravellingActivationFiller(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int parentId_, float decayScale_) {
      super(ledIdxPos_, stripeIdx_, speed_, energy_, parentId_, decayScale_);
    }
  }
```

- [ ] **Step 2: Den Zerfall auf den Faktor umstellen**

In `drawMe()` die Zeile

```java
      curActivation.energy -= timeStep*impulseLifetime.getValue();
```

ersetzen durch

```java
      curActivation.energy -= timeStep*impulseLifetime.getValue()*curActivation.decayScale;
```

- [ ] **Step 3: Den Filler-Aufruf nachziehen**

In `drawMe()` die Zeile

```java
          newActivations.add(new TravellingActivationFiller(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy, curActivation.id));
```

ersetzen durch

```java
          newActivations.add(new TravellingActivationFiller(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy, curActivation.id, curActivation.decayScale));
```

- [ ] **Step 4: Die zwei Parameter anlegen**

In `LedNetworkTransportEffect.java` bei den Feldern, direkt nach
`RemoteControlledIntParameter impulseSpeed;` (Zeile 57), einfügen:

```java

  // Streuung der Kindwerte bei einer Aufspaltung an einem Node. Ohne sie
  // bekommt jedes Kind exakt Speed und Lebensdauer des Elternimpulses, die
  // Geschwister sterben synchron und wirken identisch.
  //
  // Beide Auslieferungswerte 0 - das ist exakt das bisherige Verhalten, ein
  // Operator dreht sie bewusst hoch. Unabhaengig voneinander einstellbar.
  RemoteControlledFloatParameter splitSpeedJitter;
  RemoteControlledFloatParameter splitLifetimeJitter;
```

Im Konstruktor, direkt nach der Zeile
`impulseEnergyExponent = new RemoteControlledIntParameter("/net/impulse/energyExponent", 2, 1, 10);`,
einfügen:

```java
    splitSpeedJitter= new RemoteControlledFloatParameter("/net/impulse/splitSpeedJitter", 0f, 0f, 1f);
    splitLifetimeJitter= new RemoteControlledFloatParameter("/net/impulse/splitLifetimeJitter", 0f, 0f, 1f);
```

- [ ] **Step 5: Den Split streuen lassen**

In `activationEncounteredNode()` den Block ab
`float nActivations=hitNode.ledIndices.size();` bis zum schliessenden
`}` der `for`-Schleife über `hitNode.ledIndices` ersetzen durch:

```java
        float nActivations=hitNode.ledIndices.size();
        //energieerhaltende Variante, bewusst nicht aktiv (siehe CLAUDE.md):
        //float childEnergy=curActivation.energy/nActivations/2.0f;
        //curActivation.setEnergy(childEnergy);
        float childEnergy=curActivation.energy;
        // Streuung je Kind, siehe /net/impulse/splitSpeedJitter und
        // /net/impulse/splitLifetimeJitter. Bei beiden Auslieferungswerten 0
        // liefert SplitVariance.jitter() den Ausgangswert unveraendert, das
        // Verhalten ist dann bitgleich dem vorherigen.
        //
        // Gezogen wird JE ZWEIG und je Groesse einzeln - ein gemeinsamer
        // Zufallswert fuer alle Zweige eines Treffers wuerde sie wieder
        // gleichschalten, also genau das nicht loesen, worum es geht.
        float speedJitter=splitSpeedJitter.getValue();
        float lifetimeJitter=splitLifetimeJitter.getValue();
        for (Integer nodeLedIdx : hitNode.ledIndices) {
          LedInNetInfo curLedInfo=ledNetInfo[nodeLedIdx]; //which stripe are we on?

          int jump; // jump one led to avoid activating the same node over and over again
          if (curActivation.speed>0) {
            jump=1;
          } else {
            jump=-1;
          }
          //  activation spreads in boths directions
          int forwPos=nodeLedIdx +jump;
          if (forwPos>0&&forwPos<nLeds) {
            newActivations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex,
                SplitVariance.jitter(curActivation.speed, speedJitter, Math.random()),
                childEnergy,
                SplitVariance.jitter(1f, lifetimeJitter, Math.random())));
          }
          //do not go back the same stripe:
          if (ledNetInfo[nodeLedIdx].stripeIndex!=ledNetInfo[activationLedIdx].stripeIndex || activationLedIdx < nodeLedIdx) {
            int backwPos=nodeLedIdx -jump;
            if (backwPos>0&&backwPos<nLeds) {
              newActivations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex,
                  SplitVariance.jitter(-curActivation.speed, speedJitter, Math.random()),
                  childEnergy,
                  SplitVariance.jitter(1f, lifetimeJitter, Math.random())));
            }
          }
        }
```

- [ ] **Step 6: Übersetzungsprüfung**

Run: `test/build.sh`

Expected: exit 0, keine Fehlerausgabe.

- [ ] **Step 7: Testsuite bleibt grün**

Run: `test/run.sh`

Expected: alle Suiten bestanden.

- [ ] **Step 8: Commit**

```bash
git add LedNetworkTransportEffect.java
git commit -m "Split-Varianz: Speed und Lebensdauer der Kinder streuen

Jede TravellingActivation traegt jetzt einen decayScale - einen FAKTOR auf
den globalen impulseLifetime, nicht dessen Ersatz. Mit einem absoluten
Zerfallswert je Impuls wuerde der Sinus-Randomizer nur noch neu gespawnte
Impulse erreichen und der Lifetime-Regler die lebenden nicht mehr, beides
ohne Fehlermeldung.

Zwei neue Parameter, beide Auslieferungswert 0 (= bisheriges Verhalten):
/net/impulse/splitSpeedJitter und /net/impulse/splitLifetimeJitter.
Gezogen wird je Zweig und je Groesse einzeln - ein gemeinsamer Zufallswert
je Treffer wuerde die Geschwister wieder gleichschalten.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: `MusicalClock` — akkumulierende Beat-Phase

**Files:**
- Create: `MusicalClock.java`
- Create: `test/MusicalClockTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Produces:
  - `void MusicalClock.advance(double nowSeconds, float bpm)` — akkumuliert.
  - `double MusicalClock.beats()` — Position in Beats seit dem ersten `advance()`.
  - `static double MusicalClock.beatsPerNote(int noteValue)` — `4.0/noteValue`.
  - `static float MusicalClock.beatDuration(float bpm)` — `60/bpm`, entartete
    BPM liefern `60/DEFAULT_BPM`.
  - `static final float DEFAULT_BPM = 60f`.

- [ ] **Step 1: Write the failing test**

Create `test/MusicalClockTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `test/run.sh MusicalClockTest`

Expected: FAIL beim Übersetzen mit `cannot find symbol: class MusicalClock`.

- [ ] **Step 3: Write minimal implementation**

Create `MusicalClock.java`:

```java
// Gemeinsame Beat-Phase fuer den Origin-Sequencer.
//
// Ohne Processing, ohne oscP5 und ohne eigene Wanduhr: die Zeit wird
// hereingegeben, damit die Klasse ohne Sketch-Laufzeit pruefbar ist. Gerufen
// wird sie aus drawMe(). Dasselbe Muster wie PresetScheduler.
//
// Die Phase wird AKKUMULIERT, nicht als (now - t0)/beatDuration gerechnet.
// Naiv gerechnet springt die Position bei jeder BPM-Aenderung: verdoppelt der
// Operator das Tempo, verdoppelt sich rueckwirkend auch die seit Sketch-Start
// verstrichene Beat-Zahl, und alle Tracks feuern schlagartig durcheinander.
// Akkumuliert aendert ein Tempowechsel nur die RATE - ein sauberes
// Accelerando statt eines Sprungs.
//
// Kein Reset bei Preset-Wechsel: MusicalClock und PresetScheduler wissen
// bewusst nichts voneinander, Preset-Timing bleibt Sekunden/Minuten.
class MusicalClock {

  static final float DEFAULT_BPM = 60f;

  private double beats = 0.0;

  // NaN heisst "noch nie getickt". Der erste advance() setzt nur den
  // Nullpunkt: die Zeitbasis ist System.currentTimeMillis()/1000, also
  // Groessenordnung 1.7e9 - ohne diese Regel stuende die Uhr beim ersten
  // Frame bei Milliarden von Beats.
  private double lastSeconds = Double.NaN;

  void advance(double nowSeconds, float bpm) {
    if (Double.isNaN(nowSeconds) || Double.isInfinite(nowSeconds)) {
      return;
    }
    if (Double.isNaN(lastSeconds)) {
      lastSeconds = nowSeconds;
      return;
    }
    double elapsed = nowSeconds - lastSeconds;
    lastSeconds = nowSeconds;
    // Ein Ruecksprung der Wanduhr (Zeitumstellung, NTP-Korrektur) darf die
    // Position nicht zurueckziehen - die Tracks haetten dann ihr nextBeat in
    // der Zukunft und schwiegen, bis die Uhr wieder aufgeholt hat.
    if (elapsed <= 0.0) {
      return;
    }
    beats += elapsed/beatDuration(bpm);
  }

  double beats() {
    return beats;
  }

  // Sekunden je Beat. Eine unbrauchbare BPM (0, negativ, NaN) faellt auf
  // DEFAULT_BPM zurueck statt eine Division durch 0 zu erzeugen - die
  // Parameter-Range im Sketch (20..200) schliesst das ohnehin aus, das hier
  // ist das Netz darunter.
  static float beatDuration(float bpm) {
    if (!(bpm > 0f)) { // faengt NaN mit ab, anders als bpm <= 0
      return 60f/DEFAULT_BPM;
    }
    return 60f/bpm;
  }

  // Laenge eines Notenwerts in Beats. 1 = Ganze = vier Beats, 4 = Viertel =
  // ein Beat, 16 = Sechzehntel = ein Viertelbeat.
  static double beatsPerNote(int noteValue) {
    if (noteValue <= 0) {
      return 1.0; // Viertel, wie noteValue 4
    }
    return 4.0/noteValue;
  }
}
```

- [ ] **Step 4: Add source and suite to the runner**

In `test/run.sh`, after the `SplitVariance.java` line, add:

```bash
[ -f MusicalClock.java ] && SOURCES="$SOURCES MusicalClock.java"
```

And append `MusicalClockTest` to the optional-suite list (after `SplitVarianceTest`).

- [ ] **Step 5: Run test to verify it passes**

Run: `test/run.sh MusicalClockTest`

Expected: `MusicalClockTest: <n> Pruefungen, alle bestanden` und exit 0.

- [ ] **Step 6: Commit**

```bash
git add MusicalClock.java test/MusicalClockTest.java test/run.sh
git commit -m "MusicalClock: akkumulierende Beat-Phase statt (now-t0)/beatDuration

Naiv gerechnet springt die Phase bei jeder BPM-Aenderung, weil sich die
seit Sketch-Start verstrichene Beat-Zahl rueckwirkend umrechnet - alle
Tracks feuerten dann schlagartig durcheinander. Akkumuliert aendert ein
Tempowechsel nur die Rate.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: `OriginSequencer` — wann feuert welcher Track von welchem Stripe

**Files:**
- Create: `OriginSequencer.java`
- Create: `test/OriginSequencerTest.java`
- Modify: `test/run.sh`

**Interfaces:**
- Consumes: `MusicalClock.beatsPerNote(int)` aus Task 3.
- Produces:
  - `interface RandomSource { double next(); }` (in `OriginSequencer.java`,
    package-private, eigene Top-Level-Deklaration in derselben Datei — das
    Projekt legt mehrere Klassen je Datei ab, siehe `LedStripeNetworks.java`).
  - `class TrackConfig { boolean enabled; int noteValue; int repeatCount; float energy; float swingJitter; int originStripeOverride; }`
    — reiner Wertbehälter, ebenfalls in `OriginSequencer.java`.
  - `static final int OriginSequencer.TRACK_COUNT = 6`
  - `OriginSequencer(int nStripes)`
  - `int[] OriginSequencer.update(double beats, TrackConfig[] cfg, RandomSource rnd)`
    — liefert die **Track-Indizes**, die in diesem Aufruf feuern, aufsteigend.
  - `int OriginSequencer.originOf(int track)` — der aktuelle Ursprungs-Stripe
    eines Tracks (gültig nach dem `update()`, in dem er gefeuert hat).
  - `static int OriginSequencer.quantizeNoteValue(int raw)` — rastert auf
    1/2/4/8/16, nächstniedrigerer erlaubter Wert.
  - `static final double MIN_INTERVAL_BEATS = 0.05`

- [ ] **Step 1: Write the failing test**

Create `test/OriginSequencerTest.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class OriginSequencerTest {

  // Zufallsquelle mit fest vorgegebener Folge - laeuft die Folge aus, beginnt
  // sie von vorn. Ohne das haengt jede Erwartung an Math.random().
  static class FixedRandom implements RandomSource {
    private final double[] values;
    private int pos = 0;
    FixedRandom(double... values) { this.values = values; }
    public double next() {
      double v = values[pos % values.length];
      pos++;
      return v;
    }
  }

  static TrackConfig off() {
    TrackConfig c = new TrackConfig();
    c.enabled = false;
    c.noteValue = 4;
    c.repeatCount = 3;
    c.energy = 0.6f;
    c.swingJitter = 0f;
    c.originStripeOverride = -1;
    return c;
  }

  static TrackConfig on(int noteValue) {
    TrackConfig c = off();
    c.enabled = true;
    c.noteValue = noteValue;
    return c;
  }

  // Nur Track 0 belegt, alle anderen aus
  static TrackConfig[] single(TrackConfig first) {
    TrackConfig[] cfg = new TrackConfig[OriginSequencer.TRACK_COUNT];
    cfg[0] = first;
    for (int i = 1; i < cfg.length; i++) {
      cfg[i] = off();
    }
    return cfg;
  }

  static boolean fired(int[] tracks, int track) {
    for (int t : tracks) {
      if (t == track) {
        return true;
      }
    }
    return false;
  }

  public static void main(String[] args) throws Exception {
    // ---- Rasterung der Notenwerte ----
    // RemoteControlledIntParameter kann keine Aufzaehlung, die Range ist
    // 1..16. Ein Regler, der auf 5 stehen bleibt, soll sich wie 4 verhalten
    // statt ein krummes Intervall zu erzeugen.
    Check.eq("1 bleibt 1", 1, OriginSequencer.quantizeNoteValue(1));
    Check.eq("2 bleibt 2", 2, OriginSequencer.quantizeNoteValue(2));
    Check.eq("4 bleibt 4", 4, OriginSequencer.quantizeNoteValue(4));
    Check.eq("8 bleibt 8", 8, OriginSequencer.quantizeNoteValue(8));
    Check.eq("16 bleibt 16", 16, OriginSequencer.quantizeNoteValue(16));
    Check.eq("3 rastet auf 2", 2, OriginSequencer.quantizeNoteValue(3));
    Check.eq("5 rastet auf 4", 4, OriginSequencer.quantizeNoteValue(5));
    Check.eq("7 rastet auf 4", 4, OriginSequencer.quantizeNoteValue(7));
    Check.eq("15 rastet auf 8", 8, OriginSequencer.quantizeNoteValue(15));
    Check.eq("0 rastet auf 1", 1, OriginSequencer.quantizeNoteValue(0));
    Check.eq("negativ rastet auf 1", 1, OriginSequencer.quantizeNoteValue(-4));
    Check.eq("ueber 16 rastet auf 16", 16, OriginSequencer.quantizeNoteValue(99));

    // ---- Ein ausgeschalteter Track feuert nie ----
    OriginSequencer s0 = new OriginSequencer(8);
    TrackConfig[] allOff = single(off());
    for (int i = 0; i < 100; i++) {
      Check.eq("ausgeschaltet feuert nichts",
          0, s0.update(i*0.5, allOff, new FixedRandom(0.5)).length);
    }

    // ---- Grundtakt: Viertel bei 4 Beats = vier Treffer ----
    OriginSequencer s1 = new OriginSequencer(8);
    TrackConfig[] viertel = single(on(4));
    // Der allererste update() setzt nur den Nullpunkt und feuert nicht -
    // sonst kaeme beim Einschalten des Sequencers ein Schlag aus dem Nichts,
    // bevor der Operator die Traktparameter gesetzt hat.
    Check.eq("erster Aufruf setzt nur den Nullpunkt",
        0, s1.update(0.0, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach einem halben Beat noch nicht",
        0, s1.update(0.5, viertel, new FixedRandom(0.5)).length);
    int[] beiBeatEins = s1.update(1.0, viertel, new FixedRandom(0.5));
    Check.eq("nach einem Beat feuert genau einer", 1, beiBeatEins.length);
    Check.eq("und zwar Track 0", 0, beiBeatEins[0]);
    Check.eq("derselbe Zeitpunkt feuert nicht noch einmal",
        0, s1.update(1.0, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach zwei Beats wieder",
        1, s1.update(2.0, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach 2.5 nicht", 0, s1.update(2.5, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach drei wieder", 1, s1.update(3.0, viertel, new FixedRandom(0.5)).length);

    // ---- Achtel feuern doppelt so oft wie Viertel ----
    OriginSequencer s2 = new OriginSequencer(8);
    TrackConfig[] achtel = single(on(8));
    s2.update(0.0, achtel, new FixedRandom(0.5));
    int treffer = 0;
    // in kleinen Schritten ueber vier Beats laufen
    for (int i = 1; i <= 400; i++) {
      treffer += s2.update(i*0.01, achtel, new FixedRandom(0.5)).length;
    }
    Check.eq("Achtel feuern acht mal in vier Beats", 8, treffer);

    OriginSequencer s3 = new OriginSequencer(8);
    TrackConfig[] ganze = single(on(1));
    s3.update(0.0, ganze, new FixedRandom(0.5));
    treffer = 0;
    for (int i = 1; i <= 800; i++) {
      treffer += s3.update(i*0.01, ganze, new FixedRandom(0.5)).length;
    }
    Check.eq("Ganze feuern zwei mal in acht Beats", 2, treffer);

    // ---- repeatCount haelt den Ursprung fest ----
    // Der Kern des Features: von demselben Ursprung wiederholt spawnen soll
    // (fast) dieselbe Melodie erzeugen. Ohne das waere jeder Spawn ein
    // Einzelereignis ohne Wiedererkennbarkeit.
    OriginSequencer s4 = new OriginSequencer(8);
    TrackConfig[] rep = single(on(4));
    rep[0].repeatCount = 3;
    // Zufallsfolge: 0.0 -> Stripe 0, 0.5 -> Stripe 4, 0.99 -> Stripe 7
    FixedRandom rnd = new FixedRandom(0.0, 0.5, 0.99);
    s4.update(0.0, rep, rnd);
    List<Integer> ursprungsfolge = new ArrayList<Integer>();
    for (int i = 1; i <= 900; i++) {
      if (s4.update(i*0.01, rep, rnd).length > 0) {
        ursprungsfolge.add(Integer.valueOf(s4.originOf(0)));
      }
    }
    Check.eq("neun Beats bei Vierteln geben neun Treffer", 9, ursprungsfolge.size());
    Check.eq("Treffer 1 zieht den ersten Ursprung", 0, ursprungsfolge.get(0).intValue());
    Check.eq("Treffer 2 bleibt darauf", 0, ursprungsfolge.get(1).intValue());
    Check.eq("Treffer 3 bleibt darauf", 0, ursprungsfolge.get(2).intValue());
    Check.eq("Treffer 4 zieht neu", 4, ursprungsfolge.get(3).intValue());
    Check.eq("Treffer 5 bleibt darauf", 4, ursprungsfolge.get(4).intValue());
    Check.eq("Treffer 6 bleibt darauf", 4, ursprungsfolge.get(5).intValue());
    Check.eq("Treffer 7 zieht neu", 7, ursprungsfolge.get(6).intValue());
    Check.eq("Treffer 8 bleibt darauf", 7, ursprungsfolge.get(7).intValue());
    Check.eq("Treffer 9 bleibt darauf", 7, ursprungsfolge.get(8).intValue());

    // repeatCount 1 zieht bei jedem Treffer neu
    OriginSequencer s5 = new OriginSequencer(8);
    TrackConfig[] rep1 = single(on(4));
    rep1[0].repeatCount = 1;
    FixedRandom rnd1 = new FixedRandom(0.0, 0.5);
    s5.update(0.0, rep1, rnd1);
    List<Integer> folge1 = new ArrayList<Integer>();
    for (int i = 1; i <= 400; i++) {
      if (s5.update(i*0.01, rep1, rnd1).length > 0) {
        folge1.add(Integer.valueOf(s5.originOf(0)));
      }
    }
    Check.eq("vier Treffer", 4, folge1.size());
    Check.eq("Treffer 1", 0, folge1.get(0).intValue());
    Check.eq("Treffer 2 zieht sofort neu", 4, folge1.get(1).intValue());
    Check.eq("Treffer 3 wieder", 0, folge1.get(2).intValue());
    Check.eq("Treffer 4 wieder", 4, folge1.get(3).intValue());

    // ---- originStripeOverride gewinnt immer ----
    OriginSequencer s6 = new OriginSequencer(8);
    TrackConfig[] fix = single(on(4));
    fix[0].originStripeOverride = 5;
    s6.update(0.0, fix, new FixedRandom(0.0, 0.5, 0.99));
    for (int i = 1; i <= 600; i++) {
      if (s6.update(i*0.01, fix, new FixedRandom(0.0, 0.5, 0.99)).length > 0) {
        Check.eq("Override haelt den Ursprung ueber repeatCount hinweg",
            5, s6.originOf(0));
      }
    }
    // Ein Override ausserhalb der Stripe-Zahl darf keinen Index-Fehler geben
    OriginSequencer s7 = new OriginSequencer(8);
    TrackConfig[] zuGross = single(on(4));
    zuGross[0].originStripeOverride = 999;
    s7.update(0.0, zuGross, new FixedRandom(0.5));
    s7.update(1.0, zuGross, new FixedRandom(0.5));
    Check.that("zu grosser Override wird auf die letzte Stripe geklemmt",
        s7.originOf(0) >= 0 && s7.originOf(0) < 8);

    // ---- Wiedereinschalten feuert nicht sofort ----
    // Dieselbe Regel wie PresetScheduler.isDue() und ImpulseOscThrottle.due():
    // nach einer langen Aus-Phase waere sonst sofort ein Intervall verstrichen
    // und es kaeme ein Schlag mitten in die laufende Szene.
    OriginSequencer s8 = new OriginSequencer(8);
    TrackConfig[] an = single(on(4));
    TrackConfig[] aus = single(off());
    s8.update(0.0, an, new FixedRandom(0.5));
    s8.update(1.0, an, new FixedRandom(0.5)); // feuert
    for (int i = 2; i <= 100; i++) { // lange aus
      s8.update(i, aus, new FixedRandom(0.5));
    }
    Check.eq("direkt nach dem Wiedereinschalten feuert nichts",
        0, s8.update(100.5, an, new FixedRandom(0.5)).length);
    Check.eq("erst ein Intervall spaeter",
        1, s8.update(101.5, an, new FixedRandom(0.5)).length);

    // ---- Kein Nachholen nach einem Haenger ----
    // Ein im Hintergrund geparktes Fenster darf beim Zurueckkommen keinen
    // Schwall ausloesen - dieselbe Regel, die ImpulseOscThrottle durchsetzt.
    OriginSequencer s9 = new OriginSequencer(8);
    TrackConfig[] hae = single(on(16));
    s9.update(0.0, hae, new FixedRandom(0.5));
    Check.eq("nach einem Sprung ueber 500 Beats genau ein Treffer, kein Schwall",
        1, s9.update(500.0, hae, new FixedRandom(0.5)).length);
    Check.eq("und danach wieder im Takt",
        0, s9.update(500.1, hae, new FixedRandom(0.5)).length);
    Check.eq("ein Sechzehntel spaeter wieder",
        1, s9.update(500.25, hae, new FixedRandom(0.5)).length);

    // ---- Mehrere Tracks gleichzeitig ----
    OriginSequencer s10 = new OriginSequencer(8);
    TrackConfig[] zwei = single(on(4));
    zwei[1] = on(2); // Halbe
    s10.update(0.0, zwei, new FixedRandom(0.5));
    int[] beiEins = s10.update(1.0, zwei, new FixedRandom(0.5));
    Check.that("bei Beat 1 feuert nur die Viertel", fired(beiEins, 0));
    Check.that("die Halbe noch nicht", !fired(beiEins, 1));
    int[] beiZwei = s10.update(2.0, zwei, new FixedRandom(0.5));
    Check.that("bei Beat 2 feuert die Viertel", fired(beiZwei, 0));
    Check.that("und die Halbe auch", fired(beiZwei, 1));

    // Die zwei Tracks haben unabhaengige Ursprünge
    OriginSequencer s11 = new OriginSequencer(8);
    TrackConfig[] unab = single(on(4));
    unab[1] = on(4);
    s11.update(0.0, unab, new FixedRandom(0.0, 0.99));
    s11.update(1.0, unab, new FixedRandom(0.0, 0.99));
    Check.that("Track 0 und Track 1 ziehen getrennte Ursprünge",
        s11.originOf(0) != s11.originOf(1));

    // ---- swingJitter 0 ist exakt periodisch ----
    OriginSequencer s12 = new OriginSequencer(8);
    TrackConfig[] exakt = single(on(4));
    exakt[0].swingJitter = 0f;
    s12.update(0.0, exakt, new FixedRandom(0.0)); // Zufall am Rand
    Check.eq("bei Jitter 0 aendert der Zufall nichts",
        0, s12.update(0.99, exakt, new FixedRandom(0.0)).length);
    Check.eq("und der Takt sitzt exakt",
        1, s12.update(1.0, exakt, new FixedRandom(0.0)).length);

    // ---- swingJitter verschiebt, ohne das Intervall auf 0 fallen zu lassen ----
    OriginSequencer s13 = new OriginSequencer(8);
    TrackConfig[] swing = single(on(16));
    swing[0].swingJitter = 1f;
    s13.update(0.0, swing, new FixedRandom(0.0)); // Faktor 0 -> Intervall 0
    int treffer13 = 0;
    for (int i = 1; i <= 1000; i++) {
      treffer13 += s13.update(i*0.001, swing, new FixedRandom(0.0)).length;
    }
    // Ohne die Untergrenze waere das Intervall 0 und JEDER der 1000 Aufrufe
    // wuerde feuern - das Netz waere in Sekunden geflutet. MIN_INTERVAL_BEATS
    // deckelt das auf hoechstens einen Treffer je 0.05 Beats.
    Check.that("volles Swing laesst das Intervall nicht auf 0 fallen",
        treffer13 <= (int)(1.0/OriginSequencer.MIN_INTERVAL_BEATS) + 1);
    Check.that("es feuert aber ueberhaupt", treffer13 > 0);

    // ---- Ein Stripe ist immer im gueltigen Bereich ----
    OriginSequencer s14 = new OriginSequencer(3);
    TrackConfig[] eng = single(on(16));
    s14.update(0.0, eng, new FixedRandom(0.0, 0.333, 0.667, 0.999));
    for (int i = 1; i <= 200; i++) {
      if (s14.update(i*0.05, eng, new FixedRandom(0.0, 0.333, 0.667, 0.999)).length > 0) {
        int o = s14.originOf(0);
        Check.that("Ursprung liegt im Bereich 0..nStripes-1", o >= 0 && o < 3);
      }
    }

    System.exit(Check.report("OriginSequencerTest"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `test/run.sh OriginSequencerTest`

Expected: FAIL beim Übersetzen mit `cannot find symbol: class OriginSequencer`.

- [ ] **Step 3: Write minimal implementation**

Create `OriginSequencer.java`:

```java
// Zufallsquelle, die sich im Test durch eine feste Folge ersetzen laesst.
// Im Betrieb ist es Math.random(); ohne diese Naht haengt jede Erwartung
// eines Tests an einem echten Zufallsgenerator.
interface RandomSource {
  double next(); // 0..1
}

// Reiner Wertbehaelter fuer die Einstellungen eines Tracks. Der Sequencer
// haelt ihn NICHT - der Effekt fuellt ihn in jedem Frame aus seinen
// RemoteControlled*Parametern und reicht ihn herein. So kennt der Sequencer
// oscP5 nicht und bleibt pruefbar.
class TrackConfig {
  boolean enabled;
  int noteValue;            // 1/2/4/8/16, wird beim Lesen gerastet
  int repeatCount;          // Zyklen auf demselben Ursprung
  float energy;             // Spawn-Energie, vom Sequencer nur durchgereicht
  float swingJitter;        // 0 = exakt periodisch
  int originStripeOverride; // -1 = zufaellig, sonst fixer Stripe
}

// Entscheidet, welche Tracks in diesem Frame feuern und von welchem Stripe.
//
// Ohne Processing, ohne oscP5 und ohne eigene Wanduhr: die Beat-Position wird
// hereingegeben (aus MusicalClock), damit die Klasse ohne Sketch-Laufzeit
// pruefbar ist. Dasselbe Muster wie PresetScheduler und ImpulseOscThrottle.
//
// Baut ausdruecklich KEINE TravellingActivation - das bleibt im Effekt, der
// die Objekte, die Geschwindigkeit und die Stripe-Laenge kennt. Hier steht nur
// die Zeit- und Auswahllogik.
class OriginSequencer {

  static final int TRACK_COUNT = 6;

  // Die vom Brief erlaubten Notenwerte. RemoteControlledIntParameter kann
  // keine Aufzaehlung, deshalb rastet quantizeNoteValue() beim Lesen.
  private static final int[] NOTE_VALUES = { 1, 2, 4, 8, 16 };

  // Kuerzestes Intervall, das ein Track haben kann. Bei swingJitter = 1 und
  // einem Zufallswert von 0 waere der Faktor exakt 0 - der Track wuerde in
  // JEDEM Frame feuern und das Netz in Sekunden fluten. Dieselbe
  // Vorsichtsmassnahme wie der Mindestabstand in spawnRandomImpulses().
  static final double MIN_INTERVAL_BEATS = 0.05;

  private final int nStripes;
  private final double[] nextBeat = new double[TRACK_COUNT];
  private final int[] repeatsLeft = new int[TRACK_COUNT];
  private final int[] origin = new int[TRACK_COUNT];
  private boolean started = false;

  OriginSequencer(int nStripes_) {
    nStripes = nStripes_ > 0 ? nStripes_ : 1;
  }

  int originOf(int track) {
    if (track < 0 || track >= TRACK_COUNT) {
      return 0;
    }
    return origin[track];
  }

  // Rastet einen beliebigen Reglerwert auf den naechstniedrigeren erlaubten
  // Notenwert. Ein Regler, der auf 5 stehen bleibt, verhaelt sich damit wie
  // 4, statt ein krummes Intervall zu erzeugen.
  static int quantizeNoteValue(int raw) {
    int best = NOTE_VALUES[0];
    for (int i = 0; i < NOTE_VALUES.length; i++) {
      if (NOTE_VALUES[i] <= raw) {
        best = NOTE_VALUES[i];
      }
    }
    return best;
  }

  // Liefert die Indizes der Tracks, die bei dieser Beat-Position feuern,
  // aufsteigend. Leeres Array, wenn keiner faellig ist.
  int[] update(double beats, TrackConfig[] cfg, RandomSource rnd) {
    if (cfg == null || rnd == null || Double.isNaN(beats)) {
      return new int[0];
    }
    // Der allererste Aufruf setzt nur den Nullpunkt. Sonst kaeme beim
    // Einschalten des Sequencers ein Schlag aus dem Nichts, bevor der
    // Operator ueberhaupt seine Trackparameter gesetzt hat.
    if (!started) {
      started = true;
      for (int i = 0; i < TRACK_COUNT; i++) {
        nextBeat[i] = beats + intervalOf(cfg, i, rnd, false);
      }
      return new int[0];
    }

    int[] scratch = new int[TRACK_COUNT];
    int found = 0;
    for (int i = 0; i < TRACK_COUNT && i < cfg.length; i++) {
      TrackConfig c = cfg[i];
      if (c == null || !c.enabled) {
        // Timer mitziehen, damit das Wiedereinschalten nicht sofort feuert -
        // dieselbe Regel wie PresetScheduler.isDue().
        nextBeat[i] = beats + intervalOf(cfg, i, rnd, false);
        continue;
      }
      if (beats < nextBeat[i]) {
        continue;
      }
      double interval = intervalOf(cfg, i, rnd, true);
      // Kein Nachholen: liegt nextBeat nach einem Haenger mehr als ein
      // Intervall zurueck, gibt es EINEN Treffer, keinen Schwall. Ein im
      // Hintergrund geparktes Fenster darf beim Zurueckkommen das Netz nicht
      // fluten - dieselbe Regel, die ImpulseOscThrottle durchsetzt.
      if (beats - nextBeat[i] > interval) {
        nextBeat[i] = beats + interval;
      } else {
        nextBeat[i] += interval;
      }
      advanceOrigin(i, c, rnd);
      scratch[found] = i;
      found++;
    }
    int[] result = new int[found];
    System.arraycopy(scratch, 0, result, 0, found);
    return result;
  }

  // Zieht bei Bedarf einen neuen Ursprung. Ein Override gewinnt immer und
  // ueberspringt die repeatCount-Buchfuehrung - er soll ja gerade fest stehen.
  private void advanceOrigin(int track, TrackConfig c, RandomSource rnd) {
    if (c.originStripeOverride >= 0) {
      int o = c.originStripeOverride;
      if (o >= nStripes) {
        o = nStripes - 1;
      }
      origin[track] = o;
      return;
    }
    if (repeatsLeft[track] <= 0) {
      origin[track] = pickStripe(rnd);
      int rc = c.repeatCount;
      if (rc < 1) {
        rc = 1;
      }
      repeatsLeft[track] = rc;
    }
    repeatsLeft[track]--;
  }

  private int pickStripe(RandomSource rnd) {
    double r = rnd.next();
    if (Double.isNaN(r) || r < 0.0) {
      r = 0.0;
    }
    if (r >= 1.0) {
      r = 0.999999;
    }
    int s = (int) (r*nStripes);
    if (s < 0) {
      s = 0;
    }
    if (s >= nStripes) {
      s = nStripes - 1;
    }
    return s;
  }

  // Intervall dieses Tracks in Beats, gegebenenfalls verjittert.
  //
  // withJitter=false fuer den Nullpunkt und fuer ausgeschaltete Tracks: dort
  // soll kein Zufallswert verbraucht werden, sonst haengt die Ursprungsfolge
  // eines laufenden Tracks davon ab, wieviele Tracks daneben aus sind.
  private double intervalOf(TrackConfig[] cfg, int i, RandomSource rnd, boolean withJitter) {
    TrackConfig c = (i < cfg.length) ? cfg[i] : null;
    int noteValue = (c == null) ? 4 : quantizeNoteValue(c.noteValue);
    double interval = MusicalClock.beatsPerNote(noteValue);
    if (withJitter && c != null && c.swingJitter > 0f) {
      interval = SplitVariance.jitter((float) interval, c.swingJitter, rnd.next());
    }
    if (!(interval >= MIN_INTERVAL_BEATS)) { // faengt NaN mit ab
      interval = MIN_INTERVAL_BEATS;
    }
    return interval;
  }
}
```

- [ ] **Step 4: Add source and suite to the runner**

In `test/run.sh`, after the `MusicalClock.java` line, add:

```bash
[ -f OriginSequencer.java ] && SOURCES="$SOURCES OriginSequencer.java"
```

And append `OriginSequencerTest` to the optional-suite list.

- [ ] **Step 5: Run test to verify it passes**

Run: `test/run.sh OriginSequencerTest`

Expected: alle Prüfungen bestanden.

If a check fails, fix the implementation, not the test — the test encodes the
intended behaviour from the spec.

- [ ] **Step 6: Run the whole suite**

Run: `test/run.sh`

Expected: alle Suiten bestanden.

- [ ] **Step 7: Commit**

```bash
git add OriginSequencer.java test/OriginSequencerTest.java test/run.sh
git commit -m "OriginSequencer: sechs Tracks, repeatCount haelt den Ursprung

Kern des Features: von demselben Ursprung wiederholt spawnen erzeugt
(fast) dieselbe Melodie. Der Track bleibt repeatCount Zyklen auf einem
Zufalls-Stripe stehen, bevor er neu zieht; originStripeOverride setzt ihn
fest.

Baut bewusst keine TravellingActivation - das bleibt im Effekt. Zufall und
Beat-Position werden hereingereicht, damit die Klasse ohne Sketch-Laufzeit
pruefbar ist.

Kein Nachholen nach einem Haenger und kein Sofort-Feuern beim
Wiedereinschalten - dieselben zwei Regeln wie in ImpulseOscThrottle und
PresetScheduler.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Sequencer in den Effekt verdrahten

**Files:**
- Modify: `LedNetworkTransportEffect.java`

**Interfaces:**
- Consumes: `MusicalClock`, `OriginSequencer`, `TrackConfig`, `RandomSource`
  aus Tasks 3 und 4.
- Produces: 38 neue OSC-Adressen (`/net/sequencer/bpm`,
  `/net/sequencer/enabled`, sechsmal `/net/sequencer/track<N>/{enabled,
  noteValue,repeatCount,energy,swingJitter,originStripeOverride}`).

- [ ] **Step 1: Felder anlegen**

In `LedNetworkTransportEffect.java`, nach dem `randomSpawnJitter`-Feld
(Zeile 101), einfügen:

```java

  // Strukturierter Layer neben dem chaotischen randomSpawn: ein BPM-Takt und
  // sechs Tracks, die von wiederkehrenden Urspruengen spawnen. Beide Layer
  // laufen unabhaengig und sind gleichzeitig aktivierbar.
  //
  // Kein /net/sequencer/activeTracks: zwei Schalter fuer dieselbe Sache
  // erzeugen einen stillen Fehlerzustand (Operator schaltet Track 4 ein, es
  // passiert nichts, weil activeTracks=3 ihn abschneidet). enabled je Track
  // ist ausserdem ausdrucksstaerker - jede Teilmenge statt nur ein Praefix.
  // Der grobe Not-Aus ist /net/sequencer/enabled.
  RemoteControlledIntParameter sequencerEnabled;
  RemoteControlledFloatParameter sequencerBpm;
  RemoteControlledIntParameter[] trackEnabled = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledIntParameter[] trackNoteValue = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledIntParameter[] trackRepeatCount = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledFloatParameter[] trackEnergy = new RemoteControlledFloatParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledFloatParameter[] trackSwingJitter = new RemoteControlledFloatParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledIntParameter[] trackOriginOverride = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  final MusicalClock musicalClock = new MusicalClock();
  OriginSequencer originSequencer;
  // Wiederverwendet statt in jedem Frame neu angelegt - drawMe() laeuft mit
  // 40 Hz, und ein Frame soll den Speicherbereiniger nicht beschaeftigen.
  private final TrackConfig[] trackConfigs = new TrackConfig[OriginSequencer.TRACK_COUNT];
  private final RandomSource mathRandom = new RandomSource() {
    public double next() {
      return Math.random();
    }
  };
```

- [ ] **Step 2: Parameter im Konstruktor anlegen**

Im Konstruktor, direkt nach der `randomSpawnJitter`-Zeile, einfügen:

```java

    // Sequencer: global aus im Auslieferungszustand. Die Track-Defaults sind
    // nur der Zustand, den ein Operator vorfindet, wenn er ihn erstmals
    // einschaltet - deshalb zwei laufende Tracks (Ganze und Halbe, ruhig)
    // statt sechs.
    sequencerEnabled= new RemoteControlledIntParameter("/net/sequencer/enabled", 0, 0, 1);
    sequencerBpm= new RemoteControlledFloatParameter("/net/sequencer/bpm", 60f, 20f, 200f);
    originSequencer= new OriginSequencer(nStripes);
    // Ganze, Halbe, Viertel, Achtel, Viertel, Achtel - die ersten zwei an.
    int[] defaultNoteValues = { 1, 2, 4, 8, 4, 8 };
    int[] defaultEnabled = { 1, 1, 0, 0, 0, 0 };
    for (int i=0; i<OriginSequencer.TRACK_COUNT; i++) {
      String base="/net/sequencer/track"+i+"/";
      trackEnabled[i]= new RemoteControlledIntParameter(base+"enabled", defaultEnabled[i], 0, 1);
      // Range 1..16 statt einer Aufzaehlung - RemoteControlledIntParameter
      // kann keine. OriginSequencer.quantizeNoteValue() rastet beim Lesen auf
      // 1/2/4/8/16, ein Regler auf 5 verhaelt sich also wie 4.
      trackNoteValue[i]= new RemoteControlledIntParameter(base+"noteValue", defaultNoteValues[i], 1, 16);
      trackRepeatCount[i]= new RemoteControlledIntParameter(base+"repeatCount", 3, 1, 8);
      trackEnergy[i]= new RemoteControlledFloatParameter(base+"energy", 0.6f, 0f, 1f);
      trackSwingJitter[i]= new RemoteControlledFloatParameter(base+"swingJitter", 0f, 0f, 1f);
      // -1 = zufaelliger Ursprung (Normalfall), sonst fixer Stripe.
      trackOriginOverride[i]= new RemoteControlledIntParameter(base+"originStripeOverride", -1, -1, nStripes-1);
      trackConfigs[i]= new TrackConfig();
    }
```

- [ ] **Step 3: Aus `drawMe()` ticken**

In `drawMe()` die Zeile

```java
    spawnRandomImpulses(currentTime);
```

ersetzen durch

```java
    spawnRandomImpulses(currentTime);
    tickSequencer(currentTime);
```

- [ ] **Step 4: Die Tick-Methode ergänzen**

Direkt nach der Methode `spawnRandomImpulses(double)` einfügen:

```java
  // Strukturierter Spawn-Layer, siehe /net/sequencer/* in CLAUDE.md. Laeuft
  // unabhaengig neben spawnRandomImpulses() - beide Layer sind gleichzeitig
  // aktivierbar, der eine ist der chaotische Ambient-Teppich, der andere die
  // wiedererkennbare Choreografie.
  //
  // Die Uhr laeuft AUCH bei sequencerEnabled=0 weiter: sie ist die gemeinsame
  // Phase, und ein Stillstand waehrend der Aus-Phase machte das
  // Wiedereinschalten von der Dauer der Pause abhaengig.
  private void tickSequencer(double currentTime) {
    musicalClock.advance(currentTime, sequencerBpm.getValue());
    if (sequencerEnabled.getValue() != 1) {
      return;
    }
    for (int i=0; i<OriginSequencer.TRACK_COUNT; i++) {
      TrackConfig c=trackConfigs[i];
      c.enabled=trackEnabled[i].getValue()==1;
      c.noteValue=trackNoteValue[i].getValue();
      c.repeatCount=trackRepeatCount[i].getValue();
      c.energy=trackEnergy[i].getValue();
      c.swingJitter=trackSwingJitter[i].getValue();
      c.originStripeOverride=trackOriginOverride[i].getValue();
    }
    int[] firing=originSequencer.update(musicalClock.beats(), trackConfigs, mathRandom);
    if (firing.length == 0) {
      return;
    }
    // Geschwindigkeit kommt wie beim Ambient-Spawn von impulseSpeed, damit
    // getaktete und zufaellige Impulse gleich schnell wirken. decayScale 1.0:
    // ein gespawnter Impuls folgt dem globalen Lifetime, gestreut wird erst
    // an einer Kreuzung.
    float speed=impulseSpeed.getValue();
    for (int i=0; i<firing.length; i++) {
      int track=firing[i];
      int stripeIdx=originSequencer.originOf(track);
      if (stripeIdx < 0 || stripeIdx >= nStripes) {
        continue;
      }
      activations.add(new TravellingActivation(stripeIdx*nLedsInStripe, stripeIdx,
          speed, trackConfigs[track].energy));
    }
  }
```

- [ ] **Step 5: Übersetzungsprüfung**

Run: `test/build.sh`

Expected: exit 0.

- [ ] **Step 6: Testsuite bleibt grün**

Run: `test/run.sh`

Expected: alle Suiten bestanden.

- [ ] **Step 7: Commit**

```bash
git add LedNetworkTransportEffect.java
git commit -m "Origin-Sequencer im Transport-Effekt verdrahtet

38 neue Adressen unter /net/sequencer/, global aus im
Auslieferungszustand. Laeuft unabhaengig neben spawnRandomImpulses(),
beide Layer sind gleichzeitig aktivierbar.

Die Uhr laeuft auch bei enabled=0 weiter - sie ist die gemeinsame Phase,
und ein Stillstand machte das Wiedereinschalten von der Dauer der Pause
abhaengig.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: `/net/impulse` um die Geschwindigkeit erweitern

**Files:**
- Modify: `LedNetworkTransportEffect.java`

**Interfaces:**
- Produces: neue Signatur
  `/net/impulse <impulseId:int> <x:float> <y:float> <energy:float> <speed:float>`.
  Rein additiv — ein Empfänger, der nur die ersten vier liest, bleibt unberührt.

- [ ] **Step 1: Das fünfte Argument anhängen**

In `sendImpulseStream()` nach der Zeile `myMessage.add(a.energy);` einfügen:

```java
      // Betrag der Geschwindigkeit in LEDs/Sekunde, rein ANGEHAENGT - genau
      // das Muster, mit dem /net/hitNode schon um x/y erweitert wurde: ein
      // Empfaenger, der nur die ersten vier Argumente liest, bleibt unberuehrt.
      //
      // Das Vorzeichen traegt die Richtung und ist fuer die Klangfarbe
      // bedeutungslos, deshalb der Betrag. Die Klangseite koppelt daran die
      // Filterfrequenz des Travel-Sounds (schneller = schaerfer).
      myMessage.add(Math.abs(a.speed));
```

- [ ] **Step 2: Den Methodenkommentar nachziehen**

Im Blockkommentar über `sendImpulseStream()` die erste Zeile

```java
  // Gedrosselter Positionsstrom der reisenden Impulse.
```

ersetzen durch

```java
  // Gedrosselter Positionsstrom der reisenden Impulse:
  //   /net/impulse <id:int> <x:float> <y:float> <energy:float> <speed:float>
  // Das fuenfte Argument ist der BETRAG der Geschwindigkeit in LEDs/Sekunde
  // und kam spaeter dazu - rein angehaengt, siehe unten.
```

- [ ] **Step 3: Übersetzungsprüfung**

Run: `test/build.sh`

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add LedNetworkTransportEffect.java
git commit -m "/net/impulse traegt die Geschwindigkeit als fuenftes Argument

Rein angehaengt, wie seinerzeit x/y bei /net/hitNode - ein Empfaenger, der
nur die ersten vier liest, bleibt unberuehrt. Die Klangseite koppelt daran
die Filterfrequenz des Travel-Sounds.

Betrag statt Vorzeichenwert: das Vorzeichen ist Richtung und fuer die
Klangfarbe bedeutungslos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Klangbias nach Region (SuperCollider)

**Files:**
- Modify: `supercollider/klangnetz_bells.scd`

**Interfaces:**
- Consumes: `px`/`py` aus `/net/hitNode`, bereits vorhanden.
- Produces: `~regionZone.(px, py)` → 0..3, `~regionBias.(px, py)` → Event mit
  `noteOffset`, `brightness`, `detune`. Neuer Registry-Parameter
  `/klangnetz/param/regionBiasAmount`.

**Hinweis:** Diese Datei ist die **einzige** SC-Sound-Datei (siehe CLAUDE.md).
Nicht kopieren, nur hier ändern. Für den SC-Teil gibt es kein Testgerüst; die
Prüfung ist manuell am Gerät.

- [ ] **Step 1: Die Zonen-Tabelle und die Bias-Funktion anlegen**

In `supercollider/klangnetz_bells.scd`, direkt **vor** der Zeile
`// ---- OSC-Sound-Parameter-Registry ---` (bei `~params = IdentityDictionary.new;`,
also vor dem Kommentarblock ab ca. Zeile 316), einfügen:

```supercollider
// ---- Klangbias nach Netzregion ----------------------------------------
// Ziel: ein Knoten soll nicht nur rhythmisch, sondern auch klanglich
// verraten, aus welchem Teil des Netzes er kommt.
//
// ZONIERUNG: vier Quadranten, Zone = (x>=0) + 2*(y>=0). Der Grund ist die
// Lautsprecher-Geometrie: die vier Boxen stehen auf den SEITENMITTEN
// (0,+4) (+7,0) (0,-4) (-7,0), jeder Quadrant liegt also genau zwischen
// zwei Boxen und hat eine eindeutige Richtung im Raum. Ortung und
// Klangfarbe stuetzen sich damit gegenseitig -- man hoert "vorne rechts"
// UND "die helle Farbe", und beides meint dieselbe Netzregion.
//
// Radiale Ringe (Zentrum/Rand) waeren die naheliegende Alternative und
// scheiden genau daran aus: sie korrelieren mit keiner
// Lautsprecherrichtung, und in der Mitte pannt ~toQuad ohnehin auf alle
// vier Boxen gleich (25/25/25/25%) -- die Zone mit dem eigensten Charakter
// laege dort, wo die Ortung am schwaechsten ist.
~regionZone = { |px, py|
    var xi = if (px >= 0) { 1 } { 0 };
    var yi = if (py >= 0) { 1 } { 0 };
    xi + (2 * yi)
};

// Bias je Zone. Der Notenoffset zaehlt in SKALENSTUFEN, nicht Halbtoenen:
// er wird auf noteIndex addiert, bevor ~scaleSteps nachgeschlagen wird. Ein
// Halbtonoffset erzeugte Toene ausserhalb von Phrygisch und zerstoerte den
// vor Ort gewaehlten Modus.
//
// brightness und detune sind die vorhandenen Timbre-Regler (siehe
// ~registerParam unten): brightness skaliert die oberen vier Teiltoene
// (hell/dumpf), detune blendet zwischen unharmonischen und rein
// harmonischen Verhaeltnissen (metallisch/orgelartig).
//
//   Zone 0 = hinten links   tief,  dumpf,     metallisch
//   Zone 1 = hinten rechts  mitte, neutral,   harmonischer
//   Zone 2 = vorn links     hoeher, heller,   metallisch
//   Zone 3 = vorn rechts    hoch,  brillant,  fast rein harmonisch
~regionNoteOffsets = [-2, 0, 2, 4];
~regionBrightness  = [0.65, 1.0, 1.35, 1.7];
~regionDetune      = [1.0, 0.7, 1.0, 0.4];

// Liefert den wirksamen Bias, linear zwischen neutral und vollem Zonenwert
// interpoliert. Bei ~regionBiasAmount = 0 ist das Ergebnis exakt neutral,
// der Klang also bitgleich dem ohne dieses Feature.
~regionBias = { |px, py|
    var zone = ~regionZone.(px, py);
    var amt = ~regionBiasAmount ? 0;
    (
        zone: zone,
        noteOffset: (~regionNoteOffsets[zone] * amt).round.asInteger,
        brightness: 1.0 + ((~regionBrightness[zone] - 1.0) * amt),
        detune: 1.0 + ((~regionDetune[zone] - 1.0) * amt)
    )
};
```

- [ ] **Step 2: Den Parameter registrieren**

Nach der Zeile `~registerParam.(\panSharpness, 1.0, 0.1, 8.0, nil);` einfügen:

```supercollider
// Staerke des Regionen-Klangbias (siehe ~regionBias oben). 0 = aus, der
// Klang ist dann bitgleich dem ohne dieses Feature; 1 = voller Zonenwert.
//
// Ein eigener enabled-Schalter entfaellt bewusst: amount = 0 IST aus, und
// ein stufenloser Regler ist live wertvoller als ein harter Schalter, weil
// sich der Effekt einblenden laesst statt zu springen. Default 0.6 -- der
// Bias ist rein additiv zur bestehenden Klanglogik und kann nichts stumm
// schalten.
//
// Wirkt auf NEUE Toene: brightness/detune sind Start-Argumente der
// Glocken-SynthDef (siehe dort), ein spaeteres .set kaeme nie mehr an.
~registerParam.(\regionBiasAmount, 0.6, 0.0, 1.0, nil);
```

- [ ] **Step 3: Den Glocken-Handler den Bias anwenden lassen**

Im `~netHitNodeOscFunc`-Handler die Variablendeklaration

```supercollider
        var nodeId, energy, px, py, noteIndex, octave, degree, semitoneOffset,
            midiNote, freq, amp, newBell;
```

ersetzen durch

```supercollider
        var nodeId, energy, px, py, noteIndex, octave, degree, semitoneOffset,
            midiNote, freq, amp, newBell, bias;
```

Dann den Block

```supercollider
        // Node-ID auf einen Ton der Tonleiter über mehrere Oktaven abbilden.
        noteIndex = nodeId % ~notesPerOctaveSet;
        octave = noteIndex div: ~scaleSteps.size;
        degree = noteIndex mod: ~scaleSteps.size;
        semitoneOffset = ~scaleSteps[degree] + (octave * 12);
        midiNote = ~rootMidiNote + semitoneOffset;
        freq = midiNote.midicps;
```

ersetzen durch

```supercollider
        // Klangbias nach Netzregion (siehe ~regionBias oben): der Notenoffset
        // wird auf den SKALENINDEX addiert, nicht auf die MIDI-Note -- so
        // bleibt jeder Ton in Phrygisch.
        bias = ~regionBias.(px, py);

        // Node-ID auf einen Ton der Tonleiter über mehrere Oktaven abbilden.
        noteIndex = (nodeId + bias[\noteOffset]) % ~notesPerOctaveSet;
        // Ein negativer Offset kann noteIndex unter 0 druecken; SCs % liefert
        // fuer negative Zahlen bereits einen nichtnegativen Rest, die
        // Absicherung hier ist das Netz darunter.
        noteIndex = noteIndex.abs;
        octave = noteIndex div: ~scaleSteps.size;
        degree = noteIndex mod: ~scaleSteps.size;
        semitoneOffset = ~scaleSteps[degree] + (octave * 12);
        midiNote = ~rootMidiNote + semitoneOffset;
        freq = midiNote.midicps;
```

Dann den Synth-Aufruf

```supercollider
        newBell = Synth(\glockenBell, [
            \freq, freq, \amp, amp, \x, px, \y, py, \out, ~quadBus.index,
            \brightness, ~brightness, \detune, ~detune
        ], ~voices);
```

ersetzen durch

```supercollider
        // brightness/detune sind die globalen Regler MAL dem Zonenfaktor --
        // der Bias verschiebt die Klangfarbe relativ, statt den vor Ort
        // eingestellten Grundwert zu ueberschreiben.
        newBell = Synth(\glockenBell, [
            \freq, freq, \amp, amp, \x, px, \y, py, \out, ~quadBus.index,
            \brightness, (~brightness * bias[\brightness]).clip(0.0, 2.0),
            \detune, (~detune * bias[\detune]).clip(0.0, 1.0)
        ], ~voices);
```

Und die Konsolenausgabe

```supercollider
        ("node % -> % Hz (amp %) bei (%, %) m".format(
            nodeId, freq.round(0.1), amp.round(0.01), px.round(0.01), py.round(0.01)
        )).postln;
```

ersetzen durch

```supercollider
        ("node % -> % Hz (amp %) bei (%, %) m, Zone %".format(
            nodeId, freq.round(0.1), amp.round(0.01), px.round(0.01), py.round(0.01),
            bias[\zone]
        )).postln;
```

- [ ] **Step 4: Den Adressblock im Dateikopf ergänzen**

Im Kommentarblock der Datei, nach dem `panSharpness`-Eintrag (vor
`/klangnetz/param/brightness`), einfügen:

```supercollider
//   /klangnetz/param/regionBiasAmount  0.0 .. 1.0   Default 0.6
//     Staerke des Klangbias nach Netzregion (vier Quadranten, siehe
//     ~regionZone/~regionBias im Code). 0 = aus, der Klang ist dann
//     bitgleich dem ohne dieses Feature. Verschiebt je Zone die Notenwahl
//     (in SKALENSTUFEN, bleibt also in Phrygisch) sowie brightness und
//     detune. Wirkt auf NEUE Toene, wie brightness/detune selbst.
```

- [ ] **Step 5: Syntaxprüfung**

`sclang` ist auf diesem Rechner nicht installiert, und die Datei bootet einen
Audioserver — sie darf hier nicht ausgeführt werden. Prüfe stattdessen von
Hand:

```bash
grep -c '~regionZone\|~regionBias\|regionBiasAmount' supercollider/klangnetz_bells.scd
```

Expected: mindestens 8 Treffer.

Und die Klammerbilanz der Datei (sie muss **ein** einziger `(...)`-Block
bleiben — mehrere Top-Level-Blöcke hängen `sclang -D` auf):

```bash
python3 -c "
import re,sys
s=open('supercollider/klangnetz_bells.scd').read()
s=re.sub(r'//[^\n]*','',s)
s=re.sub(r'\"(\\\\.|[^\"\\\\])*\"','\"\"',s)
for a,b,n in [('(',')','runde'),('[',']','eckige'),('{','}','geschweifte')]:
    print(n, s.count(a)-s.count(b))
"
```

Expected: `runde 0`, `eckige 0`, `geschweifte 0`.

- [ ] **Step 6: Commit**

```bash
git add supercollider/klangnetz_bells.scd
git commit -m "Klangbias nach Netzregion: vier Quadranten

Zonierung ueber die Vorzeichen von x/y. Der Grund ist die
Lautsprecher-Geometrie: die vier Boxen stehen auf den Seitenmitten, jeder
Quadrant liegt also zwischen zwei Boxen und hat eine eindeutige Richtung -
Ortung und Klangfarbe stuetzen sich gegenseitig. Radiale Ringe scheiden
daran aus: sie korrelieren mit keiner Lautsprecherrichtung, und in der
Mitte pannt ~toQuad ohnehin auf alle vier Boxen gleich.

Der Notenoffset zaehlt in Skalenstufen, nicht Halbtoenen - sonst fielen
Toene aus Phrygisch heraus. Timbre ueber die vorhandenen brightness/detune;
die im Brief genannten ~tilt/~decayScale gibt es in dieser Datei nicht.

Ein Regler statt Schalter+Regler: /klangnetz/param/regionBiasAmount, 0 ist
aus und bitgleich dem bisherigen Klang.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: Travel-Sound als Rauschschicht in `\impulseDrone`

**Files:**
- Modify: `supercollider/klangnetz_bells.scd`

**Interfaces:**
- Consumes: das fünfte Argument von `/net/impulse` aus Task 6,
  `~regionBias` aus Task 7.
- Produces: sechs Registry-Parameter `travelMix`, `travelFreqMin`,
  `travelFreqMax`, `travelSpeedRef`, `travelRq`, `travelAmpScale`; erweiterte
  `\impulseDrone`-SynthDef.

**Warum kein zweiter Synth je Impuls:** `\impulseDrone` ist bereits ein Synth
pro Impuls-ID, gebunden über `~drones`, positioniert, energiegekoppelt,
freigegeben über `~droneTimeout` und gedeckelt durch `~droneLimit`. Ein
zweiter Synth bräuchte ein zweites Dictionary, einen zweiten Reaper, ein
zweites Limit und verdoppelte die Stimmenzahl — für ein Feature, dessen Ziel
es ist, den Klangbrei zu vermeiden. Die Rauschschicht erbt so Position, Lag,
Hüllkurve, Timeout und Deckel geschenkt. Der vom Brief geforderte
Lebenszyklus („neu bei jedem Split") ergibt sich dadurch ohne eine Zeile Code:
ein Split-Kind hat eine neue `id`, bekommt also einen neuen Eintrag und einen
neuen Synth.

- [ ] **Step 1: Die sechs Parameter registrieren**

Nach der `~registerParam.(\regionBiasAmount, ...)`-Zeile aus Task 7 einfügen:

```supercollider
// ---- Travel-Sound: Wind-/Rauschschicht der reisenden Impulse ----------
// Setzt auf dem VORHANDENEN gedrosselten Positionsstrom /net/impulse auf
// und wird eine zweite Klangschicht INNERHALB von \impulseDrone -- kein
// zweiter Synth je Impuls. Begruendung: \impulseDrone ist schon ein Synth
// pro Impuls-ID mit Position, Huellkurve, Timeout und ~droneLimit; ein
// zweiter braeuchte ein zweites Dictionary, einen zweiten Reaper und ein
// zweites Limit und verdoppelte die Stimmenzahl -- fuer ein Feature, dessen
// Ziel es ist, Klangbrei zu VERMEIDEN.
//
// Kein eigener Throttle auf der Processing-Seite: oscMaxCount bestimmt, was
// ueberhaupt ueber den Draht geht, und der Deckel, der wirklich gebraucht
// wird, sitzt dort, wo die Rechenlast entsteht -- ~droneLimit, direkt
// darunter.
//
// travelMix ist Schalter und Staerkeregler in einem. Default 0 = reine
// Tondrohne wie bisher, bitgleich.
~registerParam.(\travelMix, 0.0, 0.0, 1.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\travelMix, val);
    }) };
});
// Bandbreite des Rauschbands. Klein = pfeifend/schmal, gross = breites
// Rauschen. Wirkt wie travelMix sofort auf laufende Drohnen -- eine Drohne
// wird ueber Sekunden gehalten, ein Live-Regler daran ergibt hoerbar Sinn.
~registerParam.(\travelRq, 0.35, 0.02, 1.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\travelRq, val);
    }) };
});
// Trimm NUR der Rauschschicht. Es gibt ihn, weil Rauschen bei gleichem
// Pegel lauter wirkt als ein Ton und der Crossfade sonst einen Sprung
// machte.
~registerParam.(\travelAmpScale, 1.0, 0.0, 2.0, { |val|
    ~drones !? { |d| d.keysValuesDo({ |id, entry|
        entry[\synth].set(\travelAmpScale, val);
    }) };
});
// Die drei Frequenz-Parameter wirken erst auf NEUE Drohnen: die
// Speed -> Mittenfrequenz-Umrechnung passiert in sclang beim Anlegen des
// Synths (siehe OSCdef \impulseStream), nicht im SC-Graphen.
~registerParam.(\travelFreqMin,  300.0, 100.0, 2000.0, nil);
~registerParam.(\travelFreqMax, 2500.0, 200.0, 8000.0, nil);
// LEDs/Sekunde, ab der travelFreqMax erreicht ist. Der Arbeitspunkt der
// Installation liegt bei impulseSpeed = 16, der Randomizer faehrt bis 160 --
// 200 laesst also Luft nach oben, ohne dass der uebliche Bereich schon am
// Anschlag steht.
~registerParam.(\travelSpeedRef, 200.0, 10.0, 1500.0, nil);
```

- [ ] **Step 2: Die SynthDef um die Rauschschicht erweitern**

Die komplette `SynthDef(\impulseDrone, ...)` ersetzen durch:

```supercollider
    SynthDef(\impulseDrone, { |freq = 220, amp = 0.08, out = 0, x = 0, y = 0, gate = 1,
            lpfMult = 6, travelMix = 0, travelFreq = 800, travelRq = 0.35,
            travelAmpScale = 1.0|
        var sig, tone, wind, env, xl, yl, ampl, lpfMultLagged, mixLagged,
            rqLagged, windAmpLagged;
        // Lag VOR der Umrechnung: ein springender Positionswert darf nicht
        // als Klick durchschlagen. Bei 10 Hz Melderate glättet 0.1 s die
        // Sprünge zwischen zwei Meldungen.
        xl = Lag.kr(x, 0.1);
        yl = Lag.kr(y, 0.1);
        // amp braucht dieselbe Glättung und aus demselben Grund: die
        // Energie eines Impulses ändert sich von Meldung zu Meldung, und
        // ein Sprung der Lautstärke auf einem gehaltenen Ton knackt genauso
        // wie ein Sprung der Position. Gleiche Zeit wie oben, damit
        // Lautstärke und Ort nicht auseinanderlaufen.
        ampl = Lag.kr(amp, 0.1);
        // lpfMult kommt live per .set vom OSC-Parameter droneLpfMult (siehe
        // ~registerParam-Aufruf oben) — genauso lagged, sonst knackt ein
        // Regler-Sprung mitten im Filterverlauf.
        lpfMultLagged = Lag.kr(lpfMult, 0.1);
        // travelMix/travelRq/travelAmpScale kommen ebenfalls live per .set
        // (siehe die drei onSet-Callbacks bei ~registerParam) und brauchen
        // dieselbe Glättung: ein Sprung im Crossfade oder in der
        // Filterguete knackt auf einem gehaltenen Ton genauso.
        mixLagged = Lag.kr(travelMix, 0.2);
        rqLagged = Lag.kr(travelRq, 0.1);
        windAmpLagged = Lag.kr(travelAmpScale, 0.1);
        env = EnvGen.kr(Env.asr(0.05, 1, 0.3, \sin), gate, doneAction: 2);

        // Tonschicht -- unveraendert der bisherige Klang.
        tone = LFTri.ar(freq) * 0.5 + SinOsc.ar(freq * 2.01, 0, 0.2);
        tone = LPF.ar(tone, freq * lpfMultLagged);

        // Windschicht: bandpassgefiltertes Rauschen. travelFreq kommt als
        // Start-Argument und ist bereits aus der gemeldeten Geschwindigkeit
        // gerechnet (siehe OSCdef \impulseStream) -- ein schneller Impuls
        // zischt heller als ein langsamer.
        //
        // Der Faktor 3 gleicht aus, dass BPF ein schmales Band aus dem
        // Rauschen herausschneidet und das Ergebnis deutlich leiser ist als
        // die Tonschicht; ohne ihn waere der Crossfade ein Absacken.
        wind = BPF.ar(PinkNoise.ar, travelFreq, rqLagged) * 3 * windAmpLagged;

        // Crossfade: 0 = reine Tondrohne (Auslieferungszustand, bitgleich
        // dem bisherigen Klang), 1 = reines Windband.
        sig = XFade2.ar(tone, wind, (mixLagged * 2) - 1);
        sig = sig * ampl * env;
        Out.ar(out, ~toQuad.(sig, xl, yl));
    }).add;
```

- [ ] **Step 3: Den Stream-Handler die Geschwindigkeit lesen lassen**

Die komplette `OSCdef(\impulseStream, ...)` ersetzen durch:

```supercollider
    OSCdef(\impulseStream, { |msg|
        var id = msg[1], px = msg[2], py = msg[3], energy = msg[4];
        // Fuenftes Argument (Betrag der Geschwindigkeit in LEDs/s) kam
        // spaeter dazu. Die Absicherung laesst die Datei auch mit einem
        // aelteren Processing-Stand laufen -- dann klingt jeder Whoosh so,
        // als stuende der Impuls still.
        var speed = if (msg.size > 5) { msg[5].asFloat } { 0 };
        var entry = ~drones[id];
        // energy ist NICHT durch 1 begrenzt: an einer Kreuzung erbt jeder
        // Zweig die volle Energie des Elternimpulses, sie vervielfacht sich
        // also mit jeder Aufspaltung. Deshalb hier clampen.
        var amp = energy.clip(0, 2) * ~droneAmpScale;
        var travelFreq, bias;
        if (entry.notNil) {
            entry[\synth].set(\x, px, \y, py, \amp, amp);
            entry[\lastSeen] = Main.elapsedTime;
        } {
            // Über der Obergrenze wird die Meldung still verworfen. Der
            // Impuls bekommt dann auch bei späteren Meldungen keine Drohne
            // mehr, bis wieder Platz frei ist — das ist gewollt: die Grenze
            // soll den Rechner schützen, nicht Stimmen umschichten.
            if (~drones.size < ~droneLimit) {
                // Speed -> Mittenfrequenz des Windbands, linear zwischen
                // travelFreqMin und travelFreqMax. Einmal hier gerechnet und
                // als Start-Argument uebergeben statt im SC-Graphen: die
                // Geschwindigkeit eines Impulses aendert sich ueber sein
                // Leben kaum, und so bleibt der Graph schlank.
                travelFreq = speed.linlin(0, ~travelSpeedRef,
                    ~travelFreqMin, ~travelFreqMax);
                // Klangbias der HERKUNFTS-Region (siehe ~regionBias): die
                // ERSTE gemeldete Position eines Impulses kommt aus dem Takt
                // direkt nach seiner Entstehung, liegt also nahe an seinem
                // Spawn- bzw. Splitpunkt -- das IST die Herkunftsregion, ohne
                // ein weiteres OSC-Feld und ohne Zustand auf der
                // Processing-Seite. Einmal beim Anlegen gerechnet, damit der
                // Whoosh seine Klangidentitaet ueber seine ganze Lebensdauer
                // behaelt, auch wenn er in eine andere Region fliegt.
                bias = ~regionBias.(px, py);
                travelFreq = (travelFreq * bias[\brightness]).clip(20, 18000);
                ~drones[id] = (
                    synth: Synth(\impulseDrone, [
                        \freq, ~droneFreq.(id), \x, px, \y, py,
                        \amp, amp, \out, ~quadBus.index, \lpfMult, ~droneLpfMult,
                        \travelMix, ~travelMix, \travelFreq, travelFreq,
                        \travelRq, ~travelRq, \travelAmpScale, ~travelAmpScale
                    ], ~voices),
                    lastSeen: Main.elapsedTime
                );
            };
        };
    }, '/net/impulse', recvPort: ~oscListenPort);
```

- [ ] **Step 4: Den Dateikopf nachziehen**

Im Kommentarblock der Datei, direkt nach dem `regionBiasAmount`-Eintrag aus
Task 7, einfügen:

```supercollider
//
//   ---- Travel-Sound (Wind-/Rauschschicht der reisenden Impulse) ----
//   Alle sechs sitzen in \impulseDrone, es gibt KEINEN zweiten Synth je
//   Impuls -- Begruendung bei den ~registerParam-Aufrufen im Code.
//
//   /klangnetz/param/travelMix       0.0 .. 1.0    Default 0.0
//     Crossfade Tondrohne <-> Windband. 0 = bisheriger Klang, bitgleich.
//     Wirkt SOFORT auf laufende Drohnen.
//   /klangnetz/param/travelRq        0.02 .. 1.0   Default 0.35
//     Bandbreite des Rauschbands. Klein = pfeifend, gross = breit.
//     Wirkt SOFORT auf laufende Drohnen.
//   /klangnetz/param/travelAmpScale  0.0 .. 2.0    Default 1.0
//     Trimm nur der Rauschschicht. Wirkt SOFORT.
//   /klangnetz/param/travelFreqMin   100 .. 2000   Default 300
//   /klangnetz/param/travelFreqMax   200 .. 8000   Default 2500
//   /klangnetz/param/travelSpeedRef  10 .. 1500    Default 200
//     Mittenfrequenz des Bands, linear zwischen Min und Max ueber die
//     gemeldete Geschwindigkeit (LEDs/s, fuenftes Argument von
//     /net/impulse). Wirken erst auf NEUE Drohnen -- die Umrechnung
//     passiert in sclang beim Anlegen des Synths.
```

Ausserdem im Kopfblock die Zeile

```supercollider
//   /net/impulse <impulseId:int> <x:float>      <y:float>      <energy:float>
```

ersetzen durch

```supercollider
//   /net/impulse <impulseId:int> <x:float>      <y:float>      <energy:float> <speed:float>
```

- [ ] **Step 5: Klammerbilanz prüfen**

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

- [ ] **Step 6: Commit**

```bash
git add supercollider/klangnetz_bells.scd
git commit -m "Travel-Sound: Windschicht in \\impulseDrone statt zweitem Synth

\\impulseDrone ist bereits ein Synth je Impuls-ID mit Position, Huellkurve,
Timeout und ~droneLimit. Ein zweiter Synth braeuchte ein zweites
Dictionary, einen zweiten Reaper und ein zweites Limit und verdoppelte die
Stimmenzahl - fuer ein Feature, dessen Ziel es ist, Klangbrei zu
vermeiden. Als Schicht erbt der Wind Position, Lag, Huelle, Timeout und
Deckel geschenkt, und der geforderte Lebenszyklus (neue Stimme bei jedem
Split) ergibt sich ohne eine Zeile Code: ein Split-Kind hat eine neue id.

Kein eigener Throttle auf der Processing-Seite: oscMaxCount bestimmt, was
ueber den Draht geht, und der Deckel gegen Ueberlast sitzt schon dort, wo
die Rechenlast entsteht.

Herkunfts-Klangbias aus der ERSTEN gemeldeten Position - die kommt aus dem
Takt direkt nach der Entstehung des Impulses, ist also sein Ursprung, ohne
ein weiteres OSC-Feld.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: Web-UI-Gruppierung der Sequencer-Tracks

**Files:**
- Modify: `webui/server.py`

Ohne diese Änderung schneidet die generische Präfix-Regel nach zwei Segmenten
ab und alle 38 Sequencer-Regler landen in **einer** Gruppe `net/sequencer` —
sechs Tracks à sechs Regler unsortiert untereinander. Dasselbe Problem und
dieselbe Lösung wie beim Impuls-Randomizer.

- [ ] **Step 1: Die Präfixe eintragen**

In `webui/server.py` die Liste

```python
SPLIT_GROUP_PREFIXES: List[Tuple[str, str]] = [
    ("/net/impulse/speed/randomize/", "net/impulse/randomize"),
    ("/net/impulse/lifetime/randomize/", "net/impulse/randomize"),
    ("/net/impulse/color/", "net/impulse/color"),
    ("/net/impulse/fadeOut/", "net/impulse/color"),
]
```

ersetzen durch

```python
SPLIT_GROUP_PREFIXES: List[Tuple[str, str]] = [
    ("/net/impulse/speed/randomize/", "net/impulse/randomize"),
    ("/net/impulse/lifetime/randomize/", "net/impulse/randomize"),
    ("/net/impulse/color/", "net/impulse/color"),
    ("/net/impulse/fadeOut/", "net/impulse/color"),
    # Je Sequencer-Track eine eigene Sektion. Ohne diese sechs Eintraege
    # schneidet die generische Praefix-Regel nach zwei Segmenten ab und alle
    # 36 Track-Regler landen unsortiert in einer einzigen Gruppe.
    ("/net/sequencer/track0/", "net/sequencer/track0"),
    ("/net/sequencer/track1/", "net/sequencer/track1"),
    ("/net/sequencer/track2/", "net/sequencer/track2"),
    ("/net/sequencer/track3/", "net/sequencer/track3"),
    ("/net/sequencer/track4/", "net/sequencer/track4"),
    ("/net/sequencer/track5/", "net/sequencer/track5"),
]
```

- [ ] **Step 2: Die Reihenfolge festlegen**

Die Liste `GROUP_ORDER` — die globalen Sequencer-Regler vor die Tracks, alles
nach `net/randomSpawn`:

```python
GROUP_ORDER = [
    "master",
    "Master",
    "Master/opacity",
    "net/impulse",
    "net/impulse/randomize",
    "net/impulse/color",
    "net/randomSpawn",
    "net/sequencer",
    "net/sequencer/track0",
    "net/sequencer/track1",
    "net/sequencer/track2",
    "net/sequencer/track3",
    "net/sequencer/track4",
    "net/sequencer/track5",
    "net",
    "nodes",
    "nodes/radius",
    "nodes/times",
    "nodes/colors",
]
```

- [ ] **Step 3: Web-UI-Tests laufen lassen**

Run: `python3 webui/test_webui.py`

Expected: alle Prüfungen bestanden.

- [ ] **Step 4: Commit**

```bash
git add webui/server.py
git commit -m "webui: je Sequencer-Track eine eigene Sektion

Ohne die sechs Praefixe schneidet die generische Regel nach zwei Segmenten
ab und alle 36 Track-Regler landen unsortiert in einer Gruppe - dasselbe
Problem und dieselbe Loesung wie beim Impuls-Randomizer.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 10: `CLAUDE.md` nachziehen und Abschlussbericht

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Die Testliste ergänzen**

Im Abschnitt „Tests" der Aufzählung der geprüften Klassen die Zeile

```
`test/run.sh` übersetzt die processing- und netzunabhängigen Klassen (`LedColor`, `ArtNetOutput`, `NodeCrossingStore`, `NodeSelection`, `LedStripeNetworks`, `TestPatterns`, `LedAnchorStore`, `LedPositionMap`, `LedPositionCalibration`, `ImpulseOscThrottle`, `ParameterOscillator`, `PresetStore`, `PresetScheduler`)
```

um `SplitVariance`, `MusicalClock`, `OriginSequencer` erweitern, und der
Suiten-Aufzählung darunter drei Einträge anfügen:

```
- `SplitVarianceTest` — die Jitter-Formel der Split-Kinder: neutraler
  Auslieferungswert, Symmetrie, die Untergrenze gegen unsterbliche Impulse,
  Vorzeichenerhalt bei rückwärts laufenden Kindern
- `MusicalClockTest` — die akkumulierende Beat-Phase: kein Sprung bei
  BPM-Wechsel, Notenwert-Intervalle, entartete BPM, Rücksprung der Wanduhr
- `OriginSequencerTest` — Feuertakt je Notenwert, `repeatCount` hält den
  Ursprung, `originStripeOverride`, kein Sofort-Feuern beim Wiedereinschalten,
  kein Nachholen nach einem Hänger, Rasterung der Notenwerte
```

- [ ] **Step 2: Den Abschnitt „Impuls-Simulation" ergänzen**

Nach dem Absatz über `nodeDeadTime` den Satz über die volle Kindenergie
ergänzen und einen neuen Unterabschnitt einfügen:

```markdown
**Split-Varianz** (`SplitVariance.java`, angewandt in
`activationEncounteredNode()`): jedes Kind einer Aufspaltung kann eine leicht
abweichende Geschwindigkeit und Lebensdauer bekommen, damit Geschwister nicht
synchron sterben und identisch wirken. Zwei unabhängige Parameter, beide
Auslieferungswert **0** (= exakt das vorherige Verhalten):

- `/net/impulse/splitSpeedJitter` (float 0..1) — `childSpeed = speed * (1 + jitter*(rand*2-1))`
- `/net/impulse/splitLifetimeJitter` (float 0..1) — streut den `decayScale` des Kindes

`decayScale` ist ein **Faktor auf** `/net/impulse/lifetime`, nicht dessen
Ersatz. Das ist der Punkt, an dem der Entwurf bewusst vom ursprünglichen
Auftrag abweicht: mit einem absoluten Zerfallswert je Impuls würde jeder
Impuls den Wert seiner Geburt einfrieren, der Sinus-Randomizer
(`/net/impulse/lifetime/randomize/*`) erreichte nur noch neu gespawnte Impulse
und ein Operator, der den Lifetime-Regler zieht, sähe die lebenden Impulse
unbeeindruckt weiterlaufen — beides ohne Fehlermeldung. Normale Spawns tragen
`decayScale = 1.0`, Filler erben den ihres Elternimpulses.

Gezogen wird **je Zweig und je Größe einzeln**: ein gemeinsamer Zufallswert
für alle Zweige eines Treffers würde die Geschwister wieder gleichschalten,
also genau das nicht lösen, worum es geht.

`SplitVariance.jitter()` klemmt den Faktor nach unten auf `MIN_FACTOR = 0.05`.
Bei voller Stärke und einem Zufallswert von 0 wäre er sonst exakt 0 — ein Kind
mit Speed 0 stünde für immer still, eines mit `decayScale` 0 verlöre nie
Energie und stürbe nie. Zwei unsterbliche Zustände, die das Netz über eine
Nacht volllaufen lassen.

**Origin-Sequencer** (`MusicalClock.java`, `OriginSequencer.java`, getickt aus
`drawMe()` über `tickSequencer()`): der strukturierte Spawn-Layer neben dem
chaotischen `randomSpawn`. Beide laufen unabhängig und sind gleichzeitig
aktivierbar. Sechs Tracks feuern auf einem gemeinsamen BPM-Raster, jeder von
einem Ursprungs-Stripe, auf dem er `repeatCount` Zyklen stehen bleibt, bevor er
neu würfelt — von demselben Ursprung wiederholt spawnen erzeugt (fast) dieselbe
Melodie, das ist der Zweck.

- `/net/sequencer/enabled` (int 0/1, Default **0**), `/net/sequencer/bpm`
  (float, 20..200, Default 60)
- je Track N = 0..5 unter `/net/sequencer/track<N>/`: `enabled` (int 0/1,
  Default 1 für Track 0 und 1, sonst 0), `noteValue` (int 1..16, gerastet auf
  1/2/4/8/16), `repeatCount` (int 1..8, Default 3), `energy` (float 0..1,
  Default 0.6), `swingJitter` (float 0..1, Default **0**),
  `originStripeOverride` (int, -1 = zufällig)

Vier Dinge, die man beim Ändern kennen muss:

- **`MusicalClock` akkumuliert**, statt `(now - t0)/beatDuration` zu rechnen.
  Naiv gerechnet springt die Phase bei jeder BPM-Änderung — die seit
  Sketch-Start verstrichene Beat-Zahl rechnet sich rückwirkend um und alle
  Tracks feuern schlagartig durcheinander. Akkumuliert ändert ein Tempowechsel
  nur die Rate.
- **Die Uhr läuft auch bei `enabled=0` weiter.** Sie ist die gemeinsame Phase;
  ein Stillstand während der Aus-Phase machte das Wiedereinschalten von der
  Dauer der Pause abhängig.
- **Es gibt kein `/net/sequencer/activeTracks`.** Zwei Schalter für dieselbe
  Sache erzeugen einen stillen Fehlerzustand: der Operator schaltet Track 4
  ein, es passiert nichts, weil `activeTracks=3` ihn abschneidet — kein Fehler,
  kein Symptom, nur Stille. `enabled` je Track ist außerdem ausdrucksstärker
  (jede Teilmenge statt nur ein Präfix), und der grobe Not-Aus existiert schon.
- **Keine Kopplung an den Preset-Scheduler.** Sequencer-Timing ist BPM und
  Notenwerte, Preset-Timing bleibt Sekunden und Minuten; die zwei Zeitsysteme
  wissen nichts voneinander.
```

- [ ] **Step 3: Die `/net/impulse`-Signatur im OSC-Abschnitt korrigieren**

Die Zeile

```
- `/net/impulse <impulseId:int> <x:float> <y:float> <energy:float>` — gedrosselter Positionsstrom
```

ersetzen durch

```
- `/net/impulse <impulseId:int> <x:float> <y:float> <energy:float> <speed:float>` — gedrosselter Positionsstrom
```

und im selben Absatz ergänzen:

```
Das fünfte Argument ist der **Betrag** der Geschwindigkeit in LEDs/Sekunde und
kam später dazu — rein angehängt, genau wie seinerzeit `x`/`y` bei
`/net/hitNode`: ein Empfänger, der nur die ersten vier liest, bleibt unberührt.
Die Klangseite koppelt daran die Filterfrequenz des Travel-Sounds. Das
Vorzeichen trägt die Richtung und ist für die Klangfarbe bedeutungslos.
```

- [ ] **Step 4: Den Klangseiten-Abschnitt ergänzen**

Im Abschnitt „Klangseite (supercollider/klangnetz_bells.scd)" nach dem Absatz
über den Hall zwei Absätze anfügen:

```markdown
**Klangbias nach Netzregion** (`~regionZone`, `~regionBias`, Parameter
`/klangnetz/param/regionBiasAmount`, Default 0.6): vier Quadranten,
Zone = `(x>=0) + 2*(y>=0)`. Je Zone verschieben sich die Notenwahl sowie
`brightness` und `detune`. Die Zonierung folgt der Lautsprecher-Geometrie —
die vier Boxen stehen auf den Seitenmitten, jeder Quadrant liegt also zwischen
zwei Boxen und hat eine eindeutige Richtung, Ortung und Klangfarbe stützen
sich gegenseitig. Radiale Ringe (Zentrum/Rand) korrelieren mit keiner
Lautsprecherrichtung, und in der Mitte pannt `~toQuad` ohnehin auf alle vier
Boxen gleich — die Zone mit dem eigensten Charakter läge dort, wo die Ortung
am schwächsten ist.

Der Notenoffset zählt in **Skalenstufen**, nicht Halbtönen: er geht auf den
Skalenindex, bevor `~scaleSteps` nachgeschlagen wird, sonst fielen Töne aus
Phrygisch heraus. Ein eigener `enabled`-Schalter entfällt — `amount = 0` ist
aus und klingt bitgleich wie ohne das Feature. `~tilt` und `~decayScale`, die
in älteren Notizen als Timbre-Regler auftauchen, gibt es in dieser Datei
nicht; die vorhandenen Äquivalente sind `brightness` und `detune`.

**Travel-Sound** (`/klangnetz/param/travelMix`, Default **0**, plus
`travelRq`, `travelAmpScale`, `travelFreqMin`, `travelFreqMax`,
`travelSpeedRef`): eine Wind-/Rauschschicht **innerhalb** von `\impulseDrone`,
kein zweiter Synth je Impuls. `\impulseDrone` ist bereits eine Stimme pro
Impuls-ID mit Position, Hüllkurve, `~droneTimeout` und `~droneLimit`; ein
zweiter Synth bräuchte ein zweites Dictionary, einen zweiten Reaper und ein
zweites Limit und verdoppelte die Stimmenzahl — für ein Feature, dessen Ziel
es ist, Klangbrei zu vermeiden. Als Schicht erbt der Wind Position, Lag,
Hülle, Timeout und Deckel geschenkt.

Der geforderte Lebenszyklus („neue Stimme bei jedem Split") ergibt sich
dadurch ohne eine Zeile Code: ein Split-Kind ist eine neue
`TravellingActivation` mit neuer `id`, bekommt also einen neuen
Dictionary-Eintrag und einen neuen Synth, und die Stimme des Elternimpulses
läuft nach `~droneTimeout` aus.

Es gibt bewusst **keinen eigenen Throttle** für den Travel-Sound.
`/net/impulse/oscMaxCount` bestimmt, was überhaupt über den Draht geht; ein
zweiter, kleinerer Audio-Deckel wäre auf der Java-Seite wirkungslos (die
Klangseite kann eine Überzahl selbst ignorieren), ein größerer unerfüllbar
ohne mehr zu senden. Der Deckel, der wirklich gebraucht wird, sitzt dort, wo
die Rechenlast entsteht, und existiert schon: `~droneLimit`.

Der Klangbias der **Herkunfts**-Region wird einmal beim Anlegen des Synths aus
der **ersten** gemeldeten Position gerechnet. Die kommt aus dem Takt direkt
nach der Entstehung des Impulses, liegt also an seinem Spawn- bzw. Splitpunkt
— das ist die Herkunftsregion, ohne ein weiteres OSC-Feld und ohne
Ursprungs-Buchführung auf der Processing-Seite. Der Whoosh behält seine
Klangidentität über seine ganze Lebensdauer, auch wenn er in eine andere
Region fliegt.
```

- [ ] **Step 5: Den Preset-Hinweis korrigieren**

Im Abschnitt „Konventionen und Fallstricke", Unterpunkt
„SuperCollider-Presets", die Formulierung so schärfen, dass klar wird: die
**Java-Seite liegt auf `master`**, nur der SC-Empfänger fehlt. Der bestehende
Text sagt das schon größtenteils richtig; ergänze am Ende des Unterpunkts:

```
  Nachgeprüft am 2026-07-31 (`git ls-tree -r master`): `PresetManager`,
  `PresetStore`, `PresetScheduler`, `data/presets/` und beide Testsuiten
  liegen sehr wohl auf `master` — es fehlt **ausschliesslich** der SC-seitige
  Empfänger. Eine gegenteilige Notiz („das Preset-System liegt nur auf
  `feature/preset-system-v2`") ist falsch und meint diesen Empfänger.
```

- [ ] **Step 6: Den offenen Vorschlag vermerken**

Am Ende des Abschnitts „Impuls-Simulation" anfügen:

```markdown
**Offen, bewusst nicht gebaut:** eine Selbstregulation, die die Spawn-Rate an
die aktuelle Netzauslastung koppelt (viele aktive Impulse → Sequencer- und
RandomSpawn-Rate dämpfen). Vorgeschlagen, aber nie bestätigt; bleibt ein
Vorschlag, kein deaktivierter Stub.
```

- [ ] **Step 7: Gesamtprüfung**

```bash
test/run.sh
```

Expected: alle Suiten bestanden.

```bash
test/build.sh
```

Expected: exit 0.

```bash
python3 webui/test_webui.py
```

Expected: alle Prüfungen bestanden.

- [ ] **Step 8: Commit**

```bash
git add CLAUDE.md
git commit -m "CLAUDE.md: Split-Varianz, Origin-Sequencer, Klangbias, Travel-Sound

Dazu die korrigierte Fassung des Preset-Hinweises: die Java-Seite liegt
sehr wohl auf master, es fehlt ausschliesslich der SC-seitige Empfaenger.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

- [ ] **Step 9: Abschlussbericht**

```bash
git log --oneline master..HEAD
```

Zusammenfassen: was gebaut wurde, welche drei Entscheidungen bei den im Brief
offen gelassenen Punkten getroffen wurden, welche drei Abweichungen vom Brief
bewusst gewählt wurden, und die zwei nicht umgesetzten Punkte
(`loadDamping`, SC-Preset-Empfänger) mit Begründung.

**Kein Push, kein Merge.** Das entscheidet Birk nach eigener Sichtung.

---

## Manuelle Verifikation am Gerät (nicht in dieser Session)

Die vier Features lassen sich nur teilweise automatisiert prüfen. Was Birk vor
Ort nachfahren muss:

1. **Split-Varianz**: `/net/impulse/splitSpeedJitter` auf 0.3 stellen, an
   einem Node beobachten, ob die Zweige auseinanderlaufen statt im Gleichschritt
   zu bleiben. Danach `splitLifetimeJitter` auf 0.4 — die Zweige sollen zu
   verschiedenen Zeitpunkten verlöschen.
2. **Sequencer**: `/net/sequencer/enabled` auf 1, `bpm` auf 60. Track 0 (Ganze)
   soll alle vier Sekunden feuern, Track 1 (Halbe) alle zwei. `bpm` auf 120
   ziehen — das Tempo verdoppelt sich, ohne dass ein Schlag ausfällt oder
   doppelt kommt.
3. **Klangbias**: `/klangnetz/param/regionBiasAmount` von 0 auf 1 ziehen und
   hören, ob Treffer in den vier Quadranten unterschiedlich klingen. Zum
   gezielten Prüfen `/klangnetz/test/noise <x> <y>` gibt es schon.
4. **Travel-Sound**: `/klangnetz/param/travelMix` von 0 auf 1 ziehen. Die
   Drohnen müssen zu Windgeräuschen werden, ohne zu knacken (dafür die
   `Lag.kr` auf `travelMix`). Danach `/net/impulse/speed` erhöhen — die
   Whooshes sollen heller zischen.
