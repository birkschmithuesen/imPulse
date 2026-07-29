# imPulse Garbicz 2026 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der ArtNet-Ausgang bedient 15 Pixel2LED-Controller mit konstant 40 fps über eine Liste von IP-Endoktetten, und die Node-Kalibrierung wird auf zwei Cursor mit Undo und Neuladen ohne Neustart umgebaut.

**Architecture:** `ArtNetOutput` trennt reines Paketbauen (`buildFrame`, ohne Netz und ohne Processing-Laufzeit) vom Senden in einem eigenen Thread mit fester 25-ms-Taktung. Dadurch ist die fehleranfällige Adress- und Offset-Rechnung ohne Hardware prüfbar. Die Kalibrierung wird in `NodeCrossingStore` (Liste, Validierung, Datei — reines Java) und `NodeCalibration` (Darstellung, Cursor, Tasten) zerlegt, sodass auch hier die Logik testbar ist.

**Tech Stack:** Processing 3 (Java-Modus), `java.net.DatagramSocket`, `processing.core` aus `/Applications/Processing.app/Contents/Java/core.jar`. Kein Testframework — die Tests sind normale Java-Programme mit `main()`, gestartet über `test/run.sh`.

## Global Constraints

- Branch: `grabicz26`. Spezifikation: `docs/superpowers/specs/2026-07-29-grabicz26-artnet-und-kalibrierung-design.md`.
- Sprache in Kommentaren und Konsolenausgaben: Deutsch. Bezeichner im Code: Englisch, wie im bestehenden Sketch.
- **Kein Testcode im Sketch-Ordner.** Processing kompiliert jede `.java` im Sketch-Ordner mit. Tests liegen ausschliesslich unter `test/`.
- **Keine neuen Processing-Bibliotheken.** `artnet4j` entfällt ersatzlos. Auf diesem Rechner ist gar keine Contributed Library installiert.
- `ArtNetOutput.java` und `NodeCrossingStore.java` dürfen **nur** gegen `core.jar` und die Java-Standardbibliothek übersetzen — kein `oscP5`, kein `controlP5`. Sonst lassen sie sich nicht testen.
- Master-Pegel im Auslieferungszustand: `0.1`. Die Stripes vertragen keine volle Helligkeit.
- Ausgaberate: exakt 25 ms Periode (40 fps).
- Feste Werte dieser Installation: Oktette `{2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21}`, 600 LEDs pro Stripe, 2 Outputs pro Controller, 30 Stripes, 18.000 LEDs, 5 Universen pro Output, 128 LEDs pro Universum, 165 Pakete pro Frame.
- Commit nach jedem Task. Commit-Nachrichten deutsch, ohne Präfix-Konvention (der bestehende Verlauf hat keine).

---

## Dateistruktur

| Datei | Verantwortung |
|---|---|
| `ArtNetOutput.java` (neu) | Adressrechnung, Paketbau, Sender-Thread, Master-Pegel. Nur `core.jar`. |
| `NodeCrossingStore.java` (neu) | Liste der Kreuzungen, Validierung, Undo, Laden und atomares Schreiben. Nur Standard-Java. |
| `NodeCalibration.java` (neu) | Kalibriereffekt: zwei Cursor, Darstellung, Tastenverarbeitung, Testbilder. |
| `StripeHardwareHandler.java` (ändern) | `ArtNetSender` und die `oscP5`/`netP5`-Importe entfernen. `StripeConfigurator` bleibt. |
| `LedStripeNetworks.java` (ändern) | `applyCrossings` ergänzen, Index-Prüfung beim Laden. |
| `imPulse.pde` (ändern) | Geometrie, Controller-Liste, `calibrationMode`, Tastenführung, `publish()` statt `sendToLeds()`. |
| `LedStripeFullActivationEffect.java` (löschen) | Ersetzt durch `NodeCalibration.java`. |
| `test/Check.java` (neu) | Minimale Prüfhilfen und Fehlerzähler. |
| `test/ArtNetOutputTest.java` (neu) | Test 1a — byte-genau. |
| `test/ArtNetDecoder.java` (neu) | Unabhängiger Decoder für Test 1b. |
| `test/ArtNetDecoderTest.java` (neu) | Test 1b — Rückwärtsprüfung. |
| `test/NodeCrossingStoreTest.java` (neu) | Validierung, Undo, Datei-Rundlauf. |
| `test/TimingProbe.java` (neu) | Test 1c — Taktmessung. |
| `test/PollProbe.java` (neu) | Test 1d — ArtPollReply auslesen. |
| `test/run.sh` (neu) | Übersetzt und startet alle Tests. |

---

### Task 0: Processing-Bibliotheken bereitstellen

Auf diesem Rechner ist **keine einzige** Processing-Bibliothek installiert — `/Users/macbook/Documents/Processing` enthält kein `libraries`-Verzeichnis. Der Sketch lässt sich damit gar nicht starten, und jeder Schritt „Sketch starten" in den folgenden Tasks würde scheitern. Die benötigten JARs liegen aber im Repo unter `libraries/`.

**Files:** keine Änderung am Repo. Nur das Sketchbook wird befüllt.

**Interfaces:** keine.

- [ ] **Step 1: Ausgangslage bestätigen**

```bash
ls /Users/macbook/Documents/Processing/libraries 2>&1
```

Expected: „No such file or directory" — bestätigt, dass hier tatsächlich nichts installiert ist.

- [ ] **Step 2: Bibliotheken ins Sketchbook kopieren**

`oscP5.jar` enthält auch das Paket `netP5`, eine getrennte Installation ist also nicht nötig. Syphon wird gebraucht, weil `imPulse.pde` `codeanticode.syphon.*` importiert, auch wenn der Server auskommentiert ist.

```bash
mkdir -p /Users/macbook/Documents/Processing/libraries
cp -R libraries/oscP5 libraries/controlP5 libraries/Syphon \
      /Users/macbook/Documents/Processing/libraries/
ls /Users/macbook/Documents/Processing/libraries
```

Expected: `Syphon  controlP5  oscP5`

- [ ] **Step 3: Prüfen, dass der unveränderte Sketch startet**

Es gibt in dieser Installation **kein** `processing-java` — unter `/Applications/Processing.app/Contents/MacOS/` liegt nur `Processing`. Der Sketch wird deshalb in allen folgenden Tasks aus der IDE gestartet:

```bash
open -a Processing /Users/macbook/Projekte/_gitHub/imPulse/imPulse.pde
```

Dann in der IDE auf Play.

Expected: Die Übersetzung schlägt fehl, und zwar **ausschliesslich** mit einer Meldung zu `ch.bildspur.artnet` („The package ch.bildspur.artnet does not exist" oder ähnlich). artnet4j ist nicht installiert und wird bewusst auch nicht nachinstalliert — Task 5 entfernt den Import ersatzlos.

Das ist der eigentliche Prüfpunkt: erscheint **keine** Meldung zu `oscP5`, `netP5`, `controlP5` oder `codeanticode.syphon`, sind diese drei Bibliotheken korrekt gefunden worden. Kommt doch eine, ist der Kopiervorgang fehlgeschlagen — dann die Ordnerstruktur prüfen, erwartet wird `libraries/oscP5/library/oscP5.jar`.

Ab Task 5 übersetzt der Sketch dann vollständig.

- [ ] **Step 4: Kein Commit**

Das Sketchbook liegt ausserhalb des Repos. Es gibt hier nichts zu committen.

---

### Task 1: Testgerüst und Adressrechnung

**Files:**
- Create: `test/Check.java`, `test/run.sh`, `test/ArtNetOutputTest.java`
- Create: `ArtNetOutput.java`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `LedColor` aus `LedColor.java` (Felder `x`, `y`, `z` als r/g/b von 0..1, `LedColor.createColorArray(int)`).
- Produces: `ArtNetOutput(int[] octets, int numLedsPerStripe)`, `int numStripes()`, `int universesPerOutput()`, `int packetsPerFrame()`, `int portAddress(int controllerIndex, int output, int universeInOutput)`, `String targetIp(int controllerIndex)`, `String describeMapping()`. Konstanten `ARTNET_PORT=6454`, `CHANNELS_PER_LED=4`, `LEDS_PER_UNIVERSE=128`, `OUTPUTS_PER_CONTROLLER=2`, `DMX_HEADER_LEN=18`, `DMX_PACKET_LEN=530`, `SYNC_PACKET_LEN=14`, `R_OFFSET=0`, `G_OFFSET=1`, `B_OFFSET=2`. Ausserdem `Check.eq`, `Check.that`, `Check.report` für alle weiteren Tests.

- [ ] **Step 1: Prüfhilfe anlegen**

`test/Check.java`:

```java
// Minimale Prüfhilfen. Kein Testframework installiert, deshalb von Hand.
class Check {
  static int checks = 0;
  static int failures = 0;

  static void eq(String what, long expected, long actual) {
    checks++;
    if (expected != actual) {
      failures++;
      if (failures <= 20) {
        System.out.println("  FEHLER " + what + ": erwartet " + expected + ", war " + actual);
      }
    }
  }

  static void eq(String what, String expected, String actual) {
    checks++;
    if (!expected.equals(actual)) {
      failures++;
      if (failures <= 20) {
        System.out.println("  FEHLER " + what + ": erwartet \"" + expected + "\", war \"" + actual + "\"");
      }
    }
  }

  static void that(String what, boolean condition) {
    checks++;
    if (!condition) {
      failures++;
      if (failures <= 20) {
        System.out.println("  FEHLER " + what);
      }
    }
  }

  static int report(String suite) {
    if (failures == 0) {
      System.out.println(suite + ": " + checks + " Pruefungen, alle bestanden");
      return 0;
    }
    System.out.println(suite + ": " + checks + " Pruefungen, " + failures + " Fehler");
    return 1;
  }
}
```

- [ ] **Step 2: Testlauf-Skript anlegen**

`test/run.sh`:

```bash
#!/usr/bin/env bash
# Uebersetzt die netz- und processingunabhaengigen Klassen samt Tests und startet sie.
set -euo pipefail
cd "$(dirname "$0")/.."

CORE=/Applications/Processing.app/Contents/Java/core.jar
if [ ! -f "$CORE" ]; then
  echo "core.jar nicht gefunden unter $CORE" >&2
  exit 1
fi

rm -rf build
mkdir -p build

SOURCES="LedColor.java ArtNetOutput.java"
[ -f NodeCrossingStore.java ] && SOURCES="$SOURCES NodeCrossingStore.java"
[ -f LedStripeNetworks.java ] && SOURCES="$SOURCES LedStripeNetworks.java"

javac -nowarn -cp "$CORE" -d build $SOURCES test/*.java

status=0
for t in "$@"; do
  echo "== $t"
  java -cp "build:$CORE" "$t" || status=1
done
exit $status
```

Ausführbar machen: `chmod +x test/run.sh`

- [ ] **Step 3: Build-Verzeichnis ignorieren**

An `.gitignore` anhängen:

```
/build/
```

- [ ] **Step 4: Den fehlschlagenden Test schreiben**

`test/ArtNetOutputTest.java`:

```java
// Test 1a - Adressrechnung. Der Paketbau kommt in Task 2 dazu.
public class ArtNetOutputTest {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  public static void main(String[] args) {
    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);

    Check.eq("Anzahl Stripes", 30, out.numStripes());
    Check.eq("Universen je Output", 5, out.universesPerOutput());
    // 15 Controller * (2 Outputs * 5 Universen) + 15 Sync
    Check.eq("Pakete je Frame", 165, out.packetsPerFrame());

    // Konvention: Start-Universum = Oktett * 100
    Check.eq("Controller 0, Output 0, Universum 0", 200, out.portAddress(0, 0, 0));
    Check.eq("Controller 0, Output 0, Universum 4", 204, out.portAddress(0, 0, 4));
    Check.eq("Controller 0, Output 1, Universum 0", 205, out.portAddress(0, 1, 0));
    Check.eq("Controller 0, Output 1, Universum 4", 209, out.portAddress(0, 1, 4));
    // Oktett 10 -> 1000, der Fall den artnet4j nicht adressieren konnte
    Check.eq("Controller 5 (Oktett 10)", 1000, out.portAddress(5, 0, 0));
    Check.eq("Controller 14 (Oktett 21)", 2109, out.portAddress(14, 1, 4));

    Check.eq("Ziel-IP Controller 0", "2.2.2.2", out.targetIp(0));
    Check.eq("Ziel-IP Controller 5", "2.2.2.10", out.targetIp(5));

    // Die Zuordnungstabelle nennt jeden Stripe genau einmal
    String table = out.describeMapping();
    for (int s = 0; s < 30; s++) {
      Check.that("Tabelle nennt Stripe " + s, table.contains("Stripe " + s + " "));
    }

    System.exit(Check.report("ArtNetOutputTest"));
  }
}
```

- [ ] **Step 5: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh ArtNetOutputTest`
Expected: FAIL — `javac` bricht ab mit „cannot find symbol: class ArtNetOutput".

- [ ] **Step 6: Minimale Umsetzung**

`ArtNetOutput.java`:

```java
// Sendet die LED-Farben als Art-Net an die Pixel2LED-Controller.
//
// Die Firmware adressiert jede LED mit vier Bytes, ein Universum traegt also
// 128 LEDs. Jeder Output beginnt bei DMX-Adresse 1 eines neuen Universums;
// bei 600 LEDs je Output sind das fuenf Universen, von denen das letzte nur
// 88 echte LEDs traegt. Die uebrigen 40 Slots muessen genullt bleiben, sonst
// schreibt die Firmware sie in die ersten LEDs des naechsten Outputs.
//
// Diese Klasse haengt bewusst nur an LedColor und der Java-Standardbibliothek,
// damit der Paketbau ohne Processing-Laufzeit geprueft werden kann.
class ArtNetOutput {

  static final int ARTNET_PORT = 6454;
  static final int CHANNELS_PER_LED = 4;
  static final int LEDS_PER_UNIVERSE = 512 / CHANNELS_PER_LED;   // 128
  static final int OUTPUTS_PER_CONTROLLER = 2;
  static final int DMX_HEADER_LEN = 18;
  static final int DMX_PACKET_LEN = DMX_HEADER_LEN + 512;        // 530
  static final int SYNC_PACKET_LEN = 14;

  // Kanalreihenfolge im DMX-Paket. Das vierte Byte je LED bleibt immer 0.
  // Kommen die Farben vertauscht an, hier drehen: R=2, G=1, B=0.
  static final int R_OFFSET = 0;
  static final int G_OFFSET = 1;
  static final int B_OFFSET = 2;

  final int[] octets;
  final int numLedsPerStripe;
  final int universesPerOutput;

  ArtNetOutput(int[] octets, int numLedsPerStripe) {
    this.octets = octets;
    this.numLedsPerStripe = numLedsPerStripe;
    int channels = numLedsPerStripe * CHANNELS_PER_LED;
    this.universesPerOutput = (channels + 511) / 512;   // aufgerundet
  }

  int numStripes() {
    return octets.length * OUTPUTS_PER_CONTROLLER;
  }

  int universesPerOutput() {
    return universesPerOutput;
  }

  int packetsPerFrame() {
    // je Controller alle Universen plus ein Sync-Paket
    return octets.length * (OUTPUTS_PER_CONTROLLER * universesPerOutput + 1);
  }

  int portAddress(int controllerIndex, int output, int universeInOutput) {
    return octets[controllerIndex] * 100 + output * universesPerOutput + universeInOutput;
  }

  String targetIp(int controllerIndex) {
    return "2.2.2." + octets[controllerIndex];
  }

  // Zuordnungstabelle fuer die Konsole. Gegen das Web-Interface der Controller
  // pruefbar, bevor irgendetwas angeschlossen ist.
  String describeMapping() {
    StringBuilder sb = new StringBuilder();
    sb.append("Art-Net Zuordnung: ").append(octets.length).append(" Controller, ")
      .append(numStripes()).append(" Stripes, ")
      .append(numStripes() * numLedsPerStripe).append(" LEDs, ")
      .append(packetsPerFrame()).append(" Pakete je Frame\n");
    for (int k = 0; k < octets.length; k++) {
      for (int j = 0; j < OUTPUTS_PER_CONTROLLER; j++) {
        int stripe = k * OUTPUTS_PER_CONTROLLER + j;
        int firstLed = stripe * numLedsPerStripe;
        sb.append(String.format(
            "  %-9s Output %d  Universen %d-%d  Stripe %-3d LED %d-%d%n",
            targetIp(k), j,
            portAddress(k, j, 0), portAddress(k, j, universesPerOutput - 1),
            stripe, firstLed, firstLed + numLedsPerStripe - 1));
      }
    }
    return sb.toString();
  }
}
```

- [ ] **Step 7: Test laufen lassen und Erfolg bestätigen**

Run: `test/run.sh ArtNetOutputTest`
Expected: PASS — „ArtNetOutputTest: N Pruefungen, alle bestanden"

- [ ] **Step 8: Zuordnungstabelle sichten**

Ein kurzes Programm ist dafür nicht nötig — hänge testweise `System.out.print(out.describeMapping());` vor `System.exit(...)` in `ArtNetOutputTest`, lasse den Test laufen und vergleiche die ersten Zeilen mit den in der Spezifikation abgelesenen Werten:

```
  2.2.2.2   Output 0  Universen 200-204  Stripe 0   LED 0-599
  2.2.2.2   Output 1  Universen 205-209  Stripe 1   LED 600-1199
  2.2.2.4   Output 0  Universen 400-404  Stripe 2   LED 1200-1799
```

Danach die Zeile wieder entfernen.

- [ ] **Step 9: Commit**

```bash
git add .gitignore ArtNetOutput.java test/
git commit -m "ArtNet: Adressrechnung und Testgeruest

Start-Universum = Oktett * 100, fuenf Universen je Output.
Tests laufen als normale Java-Programme gegen core.jar, ausserhalb
des Sketch-Ordners, damit Processing sie nicht mitkompiliert."
```

---

### Task 2: ArtDmx-Paketbau, byte-genau (Test 1a)

**Files:**
- Modify: `ArtNetOutput.java`
- Modify: `test/ArtNetOutputTest.java`

**Interfaces:**
- Consumes: alles aus Task 1.
- Produces: `ArtNetOutput.Frame` mit den öffentlichen Feldern `String[] targets`, `byte[][] data`, `int[] lengths`; `Frame newFrame()`, `void buildFrame(LedColor[] ledColors, Frame frame)`, `void setMasterLevel(float level)`, `float getMasterLevel()`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `test/ArtNetOutputTest.java` vor `System.exit(...)` einfügen:

```java
    // ---- Test 1a: Paketbau byte-genau ----
    // Master-Pegel auf 1, sonst wird alles mit 0.1 skaliert und die
    // erwarteten Bytes stimmen nicht.
    out.setMasterLevel(1f);

    int numLeds = out.numStripes() * LEDS_PER_STRIPE;
    LedColor[] colors = LedColor.createColorArray(numLeds);
    // Muster: jede LED traegt ihren globalen Index, aufgeteilt auf drei Kanaele.
    for (int i = 0; i < numLeds; i++) {
      colors[i].x = ((i) % 251) / 255f;
      colors[i].y = ((i / 251) % 241) / 255f;
      colors[i].z = ((i / 60541) % 239) / 255f;
    }

    ArtNetOutput.Frame frame = out.newFrame();
    out.buildFrame(colors, frame);

    Check.eq("Paketanzahl im Frame", 165, frame.data.length);

    int p = 0;
    for (int k = 0; k < OCTETS.length; k++) {
      for (int j = 0; j < 2; j++) {
        int stripe = k * 2 + j;
        for (int u = 0; u < 5; u++) {
          byte[] buf = frame.data[p];
          String where = "Controller " + k + " Output " + j + " Universum " + u;

          Check.eq(where + " Laenge", 530, frame.lengths[p]);
          Check.eq(where + " Ziel", out.targetIp(k), frame.targets[p]);

          Check.that(where + " Kennung", buf[0] == 'A' && buf[1] == 'r' && buf[2] == 't'
              && buf[3] == '-' && buf[4] == 'N' && buf[5] == 'e' && buf[6] == 't' && buf[7] == 0);
          Check.eq(where + " OpCode lo", 0x00, buf[8] & 0xFF);
          Check.eq(where + " OpCode hi", 0x50, buf[9] & 0xFF);
          Check.eq(where + " ProtVer hi", 0, buf[10] & 0xFF);
          Check.eq(where + " ProtVer lo", 14, buf[11] & 0xFF);
          Check.eq(where + " Physical", 0, buf[13] & 0xFF);

          int addr = out.portAddress(k, j, u);
          Check.eq(where + " SubUni", addr & 0xFF, buf[14] & 0xFF);
          Check.eq(where + " Net", (addr >> 8) & 0x7F, buf[15] & 0xFF);
          Check.eq(where + " Laenge hi", 0x02, buf[16] & 0xFF);
          Check.eq(where + " Laenge lo", 0x00, buf[17] & 0xFF);

          for (int i = 0; i < 128; i++) {
            int inStripe = u * 128 + i;
            int o = 18 + i * 4;
            Check.eq(where + " Byte 4 bei LED " + i, 0, buf[o + 3] & 0xFF);
            if (inStripe < LEDS_PER_STRIPE) {
              LedColor c = colors[stripe * LEDS_PER_STRIPE + inStripe];
              Check.eq(where + " R bei LED " + i, Math.round(c.x * 255f), buf[o + 0] & 0xFF);
              Check.eq(where + " G bei LED " + i, Math.round(c.y * 255f), buf[o + 1] & 0xFF);
              Check.eq(where + " B bei LED " + i, Math.round(c.z * 255f), buf[o + 2] & 0xFF);
            } else {
              // die 40 Reserve-Slots des letzten Universums je Output
              Check.eq(where + " Reserve R bei LED " + i, 0, buf[o + 0] & 0xFF);
              Check.eq(where + " Reserve G bei LED " + i, 0, buf[o + 1] & 0xFF);
              Check.eq(where + " Reserve B bei LED " + i, 0, buf[o + 2] & 0xFF);
            }
          }
          p++;
        }
      }
      // nach den zehn Universen genau ein Sync-Paket an denselben Controller
      byte[] sync = frame.data[p];
      Check.eq("Sync Laenge Controller " + k, 14, frame.lengths[p]);
      Check.eq("Sync Ziel Controller " + k, out.targetIp(k), frame.targets[p]);
      Check.eq("Sync OpCode lo", 0x00, sync[8] & 0xFF);
      Check.eq("Sync OpCode hi", 0x52, sync[9] & 0xFF);
      Check.eq("Sync ProtVer lo", 14, sync[11] & 0xFF);
      p++;
    }
    Check.eq("alle Pakete geprueft", 165, p);

    // Master-Pegel wirkt
    out.setMasterLevel(0.5f);
    LedColor[] white = LedColor.createColorArray(numLeds);
    for (int i = 0; i < numLeds; i++) { white[i].x = 1f; white[i].y = 1f; white[i].z = 1f; }
    out.buildFrame(white, frame);
    Check.eq("Master-Pegel 0.5 auf Weiss", 128, frame.data[0][18] & 0xFF);
    out.setMasterLevel(1f);
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh ArtNetOutputTest`
Expected: FAIL — `javac` bricht ab mit „cannot find symbol: class Frame" bzw. „method newFrame()".

- [ ] **Step 3: Umsetzung**

In `ArtNetOutput.java` ergänzen — Feld neben die übrigen:

```java
  private float masterLevel = 0.1f;   // Sicherheitsventil, siehe setMasterLevel
  private int sequence = 1;
```

und die folgenden Methoden und die innere Klasse:

```java
  // Ein fertig gebauter Frame. Ziel, Bytes und Nutzlaenge je Paket.
  static class Frame {
    final String[] targets;
    final byte[][] data;
    final int[] lengths;

    Frame(String[] targets, byte[][] data, int[] lengths) {
      this.targets = targets;
      this.data = data;
      this.lengths = lengths;
    }
  }

  // Legt die Puffer an und traegt Ziele und Laengen ein. Die aendern sich
  // waehrend des Betriebs nicht, nur die Nutzdaten.
  Frame newFrame() {
    int n = packetsPerFrame();
    String[] targets = new String[n];
    byte[][] data = new byte[n][];
    int[] lengths = new int[n];
    int p = 0;
    for (int k = 0; k < octets.length; k++) {
      for (int j = 0; j < OUTPUTS_PER_CONTROLLER; j++) {
        for (int u = 0; u < universesPerOutput; u++) {
          targets[p] = targetIp(k);
          data[p] = new byte[DMX_PACKET_LEN];
          lengths[p] = DMX_PACKET_LEN;
          p++;
        }
      }
      targets[p] = targetIp(k);
      data[p] = new byte[SYNC_PACKET_LEN];
      lengths[p] = SYNC_PACKET_LEN;
      writeSyncHeader(data[p]);
      p++;
    }
    return new Frame(targets, data, lengths);
  }

  // Der Pegel begrenzt die Ausgabe hinter allem - Show, Testbilder,
  // Kalibrierung. Die Stripes ziehen bei voller Helligkeit mehr Strom, als
  // die Einspeisung hergibt.
  void setMasterLevel(float level) {
    if (level < 0f) level = 0f;
    if (level > 1f) level = 1f;
    masterLevel = level;
  }

  float getMasterLevel() {
    return masterLevel;
  }

  void buildFrame(LedColor[] ledColors, Frame frame) {
    int p = 0;
    for (int k = 0; k < octets.length; k++) {
      for (int j = 0; j < OUTPUTS_PER_CONTROLLER; j++) {
        int stripeBase = (k * OUTPUTS_PER_CONTROLLER + j) * numLedsPerStripe;
        for (int u = 0; u < universesPerOutput; u++) {
          byte[] buf = frame.data[p];
          writeDmxHeader(buf, sequence, portAddress(k, j, u));
          int firstInStripe = u * LEDS_PER_UNIVERSE;
          for (int i = 0; i < LEDS_PER_UNIVERSE; i++) {
            int inStripe = firstInStripe + i;
            int o = DMX_HEADER_LEN + i * CHANNELS_PER_LED;
            if (inStripe < numLedsPerStripe) {
              LedColor c = ledColors[stripeBase + inStripe];
              buf[o + R_OFFSET] = level(c.x);
              buf[o + G_OFFSET] = level(c.y);
              buf[o + B_OFFSET] = level(c.z);
            } else {
              buf[o + 0] = 0;
              buf[o + 1] = 0;
              buf[o + 2] = 0;
            }
            buf[o + 3] = 0;
          }
          p++;
        }
      }
      p++;   // Sync-Paket, der Kopf steht schon aus newFrame()
    }
    sequence = (sequence % 255) + 1;
  }

  private byte level(float value) {
    int b = Math.round(value * masterLevel * 255f);
    if (b < 0) b = 0;
    if (b > 255) b = 255;
    return (byte) b;
  }

  private static void writeArtNetId(byte[] p) {
    p[0] = 'A'; p[1] = 'r'; p[2] = 't'; p[3] = '-';
    p[4] = 'N'; p[5] = 'e'; p[6] = 't'; p[7] = 0;
  }

  // Alle Pakete eines Frames tragen dieselbe Sequenznummer. Die Firmware
  // wertet sie nicht aus.
  private static void writeDmxHeader(byte[] p, int sequence, int portAddress) {
    writeArtNetId(p);
    p[8] = 0x00; p[9] = 0x50;            // OpDmx 0x5000, little-endian
    p[10] = 0; p[11] = 14;               // ProtVer
    p[12] = (byte) sequence;
    p[13] = 0;                           // Physical
    p[14] = (byte) (portAddress & 0xFF);          // SubUni
    p[15] = (byte) ((portAddress >> 8) & 0x7F);   // Net
    p[16] = 0x02; p[17] = 0x00;          // Laenge 512, big-endian
  }

  private static void writeSyncHeader(byte[] p) {
    writeArtNetId(p);
    p[8] = 0x00; p[9] = 0x52;            // OpSync 0x5200, little-endian
    p[10] = 0; p[11] = 14;
    p[12] = 0; p[13] = 0;                // Aux1, Aux2
  }
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `test/run.sh ArtNetOutputTest`
Expected: PASS — deutlich über 100.000 Prüfungen, alle bestanden.

- [ ] **Step 5: Commit**

```bash
git add ArtNetOutput.java test/ArtNetOutputTest.java
git commit -m "ArtNet: Paketbau mit byte-genauem Test

165 Pakete je Frame, 150 ArtDmx plus 15 ArtSync. Die 40 Reserve-Slots
im letzten Universum jedes Outputs werden genullt, sonst schreibt die
Firmware sie in die ersten LEDs des naechsten Outputs."
```

---

### Task 3: Rückwärtsdecoder (Test 1b)

Der Decoder rechnet die Abbildung aus der Sicht der Firmware nach, nicht mit der Formel des Senders. Dadurch fallen Lücken und Überlappungen auf, die Task 2 durchgehen lässt, weil er dieselbe Formel prüft, die er erzeugt.

**Files:**
- Create: `test/ArtNetDecoder.java`, `test/ArtNetDecoderTest.java`

**Interfaces:**
- Consumes: `ArtNetOutput.Frame`, `ArtNetOutput.newFrame()`, `buildFrame()`, `setMasterLevel()`.
- Produces: `ArtNetDecoder.decode(ArtNetOutput.Frame frame, int[] octets, int numLedsPerStripe, int outputsPerController) -> int[][]` — je LED ein `int[]{r,g,b}` oder `null`, wenn keine Daten ankamen; wirft `IllegalStateException` bei doppelt beschriebenen LEDs.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`test/ArtNetDecoderTest.java`:

```java
// Test 1b - der Decoder setzt den LED-Puffer aus den Paketen wieder zusammen.
public class ArtNetDecoderTest {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  public static void main(String[] args) {
    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);
    out.setMasterLevel(1f);

    int numLeds = out.numStripes() * LEDS_PER_STRIPE;
    LedColor[] colors = LedColor.createColorArray(numLeds);
    for (int i = 0; i < numLeds; i++) {
      colors[i].x = ((i) % 251) / 255f;
      colors[i].y = ((i / 251) % 241) / 255f;
      colors[i].z = ((i / 60541) % 239) / 255f;
    }

    ArtNetOutput.Frame frame = out.newFrame();
    out.buildFrame(colors, frame);

    int[][] decoded = ArtNetDecoder.decode(frame, OCTETS, LEDS_PER_STRIPE, 2);

    Check.eq("Anzahl rekonstruierter LEDs", numLeds, decoded.length);
    for (int i = 0; i < numLeds; i++) {
      if (decoded[i] == null) {
        Check.that("LED " + i + " hat Daten bekommen", false);
        continue;
      }
      Check.eq("LED " + i + " R", Math.round(colors[i].x * 255f), decoded[i][0]);
      Check.eq("LED " + i + " G", Math.round(colors[i].y * 255f), decoded[i][1]);
      Check.eq("LED " + i + " B", Math.round(colors[i].z * 255f), decoded[i][2]);
    }

    System.exit(Check.report("ArtNetDecoderTest"));
  }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh ArtNetDecoderTest`
Expected: FAIL — „cannot find symbol: class ArtNetDecoder".

- [ ] **Step 3: Umsetzung**

`test/ArtNetDecoder.java`:

```java
// Rechnet die Abbildung aus Sicht des Controllers nach: aus SubUni und Net
// wird die Port-Adresse gebildet, daraus ueber das Start-Universum der Port,
// daraus Output und Universum innerhalb des Outputs. Bewusst ein anderer
// Rechenweg als im Sender.
class ArtNetDecoder {

  static int[][] decode(ArtNetOutput.Frame frame, int[] octets,
                        int numLedsPerStripe, int outputsPerController) {
    int channels = numLedsPerStripe * ArtNetOutput.CHANNELS_PER_LED;
    int universesPerOutput = (channels + 511) / 512;
    int numStripes = octets.length * outputsPerController;
    int[][] result = new int[numStripes * numLedsPerStripe][];

    int syncCount = 0;

    for (int p = 0; p < frame.data.length; p++) {
      byte[] buf = frame.data[p];
      int opCode = (buf[8] & 0xFF) | ((buf[9] & 0xFF) << 8);

      if (opCode == 0x5200) {
        syncCount++;
        continue;
      }
      if (opCode != 0x5000) {
        throw new IllegalStateException("Unbekannter OpCode " + opCode + " in Paket " + p);
      }

      int portAddress = (buf[14] & 0xFF) | ((buf[15] & 0x7F) << 8);

      // welcher Controller? ueber das Ziel, nicht ueber die Adresse
      int controller = -1;
      for (int k = 0; k < octets.length; k++) {
        if (frame.targets[p].equals("2.2.2." + octets[k])) { controller = k; break; }
      }
      if (controller < 0) {
        throw new IllegalStateException("Ziel " + frame.targets[p] + " gehoert zu keinem Controller");
      }

      int port = portAddress - octets[controller] * 100;
      if (port < 0 || port >= outputsPerController * universesPerOutput) {
        throw new IllegalStateException("Port " + port + " ausserhalb des Bereichs bei Paket " + p);
      }

      int output = port / universesPerOutput;
      int universeInOutput = port % universesPerOutput;
      int stripe = controller * outputsPerController + output;

      for (int i = 0; i < ArtNetOutput.LEDS_PER_UNIVERSE; i++) {
        int inStripe = universeInOutput * ArtNetOutput.LEDS_PER_UNIVERSE + i;
        if (inStripe >= numLedsPerStripe) {
          continue;   // Reserve-Slots, tragen keine Nutzdaten
        }
        int o = ArtNetOutput.DMX_HEADER_LEN + i * ArtNetOutput.CHANNELS_PER_LED;
        int led = stripe * numLedsPerStripe + inStripe;
        if (result[led] != null) {
          throw new IllegalStateException("LED " + led + " wurde doppelt beschrieben");
        }
        result[led] = new int[] {
            buf[o + ArtNetOutput.R_OFFSET] & 0xFF,
            buf[o + ArtNetOutput.G_OFFSET] & 0xFF,
            buf[o + ArtNetOutput.B_OFFSET] & 0xFF
        };
      }
    }

    if (syncCount != octets.length) {
      throw new IllegalStateException("Erwartet " + octets.length + " Sync-Pakete, gezaehlt " + syncCount);
    }
    return result;
  }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `test/run.sh ArtNetOutputTest ArtNetDecoderTest`
Expected: PASS für beide.

- [ ] **Step 5: Commit**

```bash
git add test/ArtNetDecoder.java test/ArtNetDecoderTest.java
git commit -m "ArtNet: Rueckwaertsdecoder als unabhaengige Gegenprobe

Rechnet die Abbildung aus Sicht des Controllers nach und meldet
doppelt beschriebene oder gar nicht erreichte LEDs."
```

---

### Task 4: Sender-Thread mit festem 40-Hz-Takt (Test 1c)

**Files:**
- Modify: `ArtNetOutput.java`
- Create: `test/TimingProbe.java`

**Interfaces:**
- Consumes: alles aus Task 2.
- Produces: `void start()`, `void stop()`, `void publish(LedColor[] ledColors)`, `long[] intervalStatsNanos()` mit `{Anzahl, Mittelwert, Minimum, Maximum, Standardabweichung}` in Nanosekunden, `void setSyncBroadcast(boolean on)`.

- [ ] **Step 1: Umsetzung — Dreifachpufferung und Taktschleife**

In `ArtNetOutput.java` ergänzen:

```java
  // ---- Versand ----
  //
  // Drei Puffer: einer wird gebaut, einer wartet, einer wird gesendet. Die
  // Referenzen werden nur unter der Sperre getauscht, ausserhalb beruehrt
  // draw() nur buildBuf und der Sender nur sendBuf. Damit kein Frame beim
  // Senden ueberschrieben wird.
  private final Object lock = new Object();
  private Frame buildBuf, readyBuf, sendBuf;
  private boolean hasNew = false;

  private java.net.DatagramSocket socket;
  private Thread sender;
  private volatile boolean running = false;
  private boolean syncBroadcast = false;

  private static final long PERIOD_NANOS = 25_000_000L;   // 40 fps

  // Taktmessung fuer Test 1c
  private final Object statsLock = new Object();
  private long lastSendNanos = 0;
  private long intervalCount = 0;
  private double intervalSum = 0, intervalSumSq = 0;
  private long intervalMin = Long.MAX_VALUE, intervalMax = 0;

  void start() {
    if (running) return;
    buildBuf = newFrame();
    readyBuf = newFrame();
    sendBuf = newFrame();
    try {
      socket = new java.net.DatagramSocket();
    } catch (java.net.SocketException e) {
      throw new RuntimeException("Art-Net-Socket liess sich nicht oeffnen", e);
    }
    running = true;
    sender = new Thread(new Runnable() {
      public void run() { runSendLoop(); }
    }, "artnet-sender");
    sender.setDaemon(true);
    sender.start();
  }

  void stop() {
    running = false;
    if (sender != null) {
      try { sender.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      sender = null;
    }
    if (socket != null) { socket.close(); socket = null; }
  }

  // Sync einzeln an jeden Controller oder als Broadcast. Welches sauberer
  // laeuft, entscheidet die Messung von uUniPF aus Test 1d.
  void setSyncBroadcast(boolean on) {
    syncBroadcast = on;
  }

  // Aus draw() aufgerufen. Baut ausserhalb der Sperre und tauscht nur die
  // Referenz darin.
  void publish(LedColor[] ledColors) {
    if (!running) return;
    buildFrame(ledColors, buildBuf);
    synchronized (lock) {
      Frame t = buildBuf; buildBuf = readyBuf; readyBuf = t;
      hasNew = true;
    }
  }

  private Frame takeFrame() {
    synchronized (lock) {
      if (hasNew) {
        Frame t = sendBuf; sendBuf = readyBuf; readyBuf = t;
        hasNew = false;
      }
      return sendBuf;
    }
  }

  private void runSendLoop() {
    // Absolute Zeitpunkte statt sleep(25), damit sich kein Versatz aufsummiert.
    long deadline = System.nanoTime();
    while (running) {
      deadline += PERIOD_NANOS;
      try {
        sendFrame(takeFrame());
      } catch (Exception e) {
        System.err.println("Art-Net-Versand fehlgeschlagen: " + e);
      }
      long wait = deadline - System.nanoTime();
      if (wait > 0) {
        try {
          Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      } else {
        // zu spaet - Takt neu aufsetzen statt hinterherzuhetzen
        deadline = System.nanoTime();
      }
    }
  }

  private void sendFrame(Frame frame) throws java.io.IOException {
    long now = System.nanoTime();
    synchronized (statsLock) {
      if (lastSendNanos != 0) {
        long d = now - lastSendNanos;
        intervalCount++;
        intervalSum += d;
        intervalSumSq += (double) d * (double) d;
        if (d < intervalMin) intervalMin = d;
        if (d > intervalMax) intervalMax = d;
      }
      lastSendNanos = now;
    }

    for (int p = 0; p < frame.data.length; p++) {
      boolean isSync = frame.lengths[p] == SYNC_PACKET_LEN;
      String host = (isSync && syncBroadcast) ? "2.255.255.255" : frame.targets[p];
      java.net.InetAddress addr = java.net.InetAddress.getByName(host);
      if (isSync && syncBroadcast) {
        socket.setBroadcast(true);
      }
      socket.send(new java.net.DatagramPacket(frame.data[p], frame.lengths[p], addr, ARTNET_PORT));
    }
  }

  // {Anzahl, Mittelwert, Minimum, Maximum, Standardabweichung} in Nanosekunden
  long[] intervalStatsNanos() {
    synchronized (statsLock) {
      if (intervalCount == 0) return new long[] { 0, 0, 0, 0, 0 };
      double mean = intervalSum / intervalCount;
      double variance = intervalSumSq / intervalCount - mean * mean;
      if (variance < 0) variance = 0;
      return new long[] { intervalCount, (long) mean, intervalMin, intervalMax, (long) Math.sqrt(variance) };
    }
  }

  void resetIntervalStats() {
    synchronized (statsLock) {
      lastSendNanos = 0; intervalCount = 0; intervalSum = 0; intervalSumSq = 0;
      intervalMin = Long.MAX_VALUE; intervalMax = 0;
    }
  }
```

- [ ] **Step 2: Taktmessung schreiben**

`test/TimingProbe.java`:

```java
// Test 1c - misst, ob der Sender den 25-ms-Takt haelt. Sendet echte Pakete
// ans Netz; die Controller nehmen sie an, auch ohne angeschlossene Stripes.
public class TimingProbe {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  public static void main(String[] args) throws Exception {
    int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;

    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);
    out.setMasterLevel(0.1f);
    out.start();

    int numLeds = out.numStripes() * LEDS_PER_STRIPE;
    LedColor[] colors = LedColor.createColorArray(numLeds);
    for (int i = 0; i < numLeds; i++) { colors[i].x = 0.5f; colors[i].y = 0.5f; colors[i].z = 0.5f; }

    // eine Sekunde einschwingen lassen, dann messen
    long warmupEnd = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < warmupEnd) { out.publish(colors); Thread.sleep(25); }
    out.resetIntervalStats();

    long end = System.currentTimeMillis() + seconds * 1000L;
    while (System.currentTimeMillis() < end) { out.publish(colors); Thread.sleep(25); }

    long[] s = out.intervalStatsNanos();
    out.stop();

    System.out.printf("Intervalle: %d%n", s[0]);
    System.out.printf("Mittelwert: %.3f ms  (Soll 25.000)%n", s[1] / 1e6);
    System.out.printf("Minimum:    %.3f ms%n", s[2] / 1e6);
    System.out.printf("Maximum:    %.3f ms%n", s[3] / 1e6);
    System.out.printf("Streuung:   %.3f ms%n", s[4] / 1e6);

    Check.that("Mittelwert innerhalb 24.5-25.5 ms", s[1] > 24_500_000L && s[1] < 25_500_000L);
    Check.that("Maximum unter 40 ms", s[3] < 40_000_000L);
    Check.that("Streuung unter 3 ms", s[4] < 3_000_000L);
    System.exit(Check.report("TimingProbe"));
  }
}
```

- [ ] **Step 3: Erst kurz laufen lassen**

**Achtung:** `TimingProbe` sendet echte Pakete ans Netz. Sind die Stripes angeschlossen, leuchtet die Installation dabei flächig — bei Master-Pegel 0.1 und halber Helligkeit also rund 5 %. Das ist gewollt und unbedenklich, sollte aber niemanden überraschen.

Run: `test/run.sh ArtNetOutputTest ArtNetDecoderTest` — beide müssen weiterhin bestehen.
Dann: `test/run.sh TimingProbe` mit Vorgabe 60 Sekunden.

Hinweis: `TimingProbe` erwartet als Argument die Dauer, `run.sh` reicht keine Argumente durch. Für einen kürzeren Lauf direkt starten:

```bash
java -cp "build:/Applications/Processing.app/Contents/Java/core.jar" TimingProbe 10
```

Expected: Mittelwert nahe 25.000 ms, Streuung deutlich unter 3 ms.

- [ ] **Step 4: Volle 60 Sekunden messen**

```bash
java -cp "build:/Applications/Processing.app/Contents/Java/core.jar" TimingProbe 60
```

Expected: PASS. Die Zahlen im Commit festhalten.

- [ ] **Step 5: Commit**

```bash
git add ArtNetOutput.java test/TimingProbe.java
git commit -m "ArtNet: Sender-Thread mit festem 25-ms-Takt

Dreifachpufferung, damit draw() und Sender sich nie denselben Frame
teilen. Takt ueber absolute Zeitpunkte, damit sich kein Versatz
aufsummiert. Gemessen ueber 60 Sekunden: <Zahlen einsetzen>."
```

---

### Task 5: Einbau in den Sketch

**Files:**
- Modify: `imPulse.pde`
- Modify: `StripeHardwareHandler.java`
- Modify: `LedNetworkTransportEffect.java:241`

**Interfaces:**
- Consumes: `ArtNetOutput` vollständig.
- Produces: globale Felder `controllerOctets`, `numLedsPerStripe`, `numStripes`, `artNetOutput` in `imPulse.pde`; OSC-Adresse `/master/level`.

- [ ] **Step 1: `ArtNetSender` entfernen**

In `StripeHardwareHandler.java` die gesamte Klasse `ArtNetSender` (ab `class ArtNetSender {` bis zur schliessenden Klammer der Datei) löschen, ebenso die Kopfzeilen `import netP5.*;`, `import oscP5.*;` und `import ch.bildspur.artnet.*;`. `import processing.core.PApplet;` und die Klasse `StripeConfigurator` bleiben.

- [ ] **Step 2: Geometrie und Sender in `imPulse.pde`**

`import ch.bildspur.artnet.*;` entfernen. Den Konfigurationsblock ersetzen:

```java
// ip -configuration of Art-Net-Interface
String ipPrefix = "2.0.0.";
int startIP = 10;
ArtNetSender artNetSender;
```

durch:

```java
// Pixel2LED-Controller: nur die letzten Oktette. IP ist 2.2.2.<oktett>,
// das Start-Universum nach Konvention <oktett>*100. Die Reihenfolge im
// Array bestimmt die Stripe-Nummerierung: Controller k bedient die
// Stripes 2k (Output 1) und 2k+1 (Output 2).
int[] controllerOctets = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
ArtNetOutput artNetOutput;
RemoteControlledFloatParameter masterLevel;
```

und die Stripe-Konfiguration:

```java
int numStripes = 16;
int numLedsPerStripe = 720;
int numStripesPerController = 16;
```

durch:

```java
int numLedsPerStripe = 600;                                   // 2 x 5 m je Output
int numStripes = controllerOctets.length * ArtNetOutput.OUTPUTS_PER_CONTROLLER;
```

Die Zeile `int numStripesPerController = 16;` entfällt; `numLeds` bleibt.

- [ ] **Step 3: `setup()` anpassen**

`frameRate(44);` wird zu `frameRate(40);`.

Ersetze:

```java
  stripeConfiguration = new StripeConfigurator(numStripes, numLedsPerStripe, numStripesPerController);
```

durch:

```java
  stripeConfiguration = new StripeConfigurator(numStripes, numLedsPerStripe);
```

Ersetze:

```java
  artNetSender = new ArtNetSender(stripeConfiguration, ipPrefix, startIP);
```

durch:

```java
  artNetOutput = new ArtNetOutput(controllerOctets, numLedsPerStripe);
  System.out.print(artNetOutput.describeMapping());
  masterLevel = new RemoteControlledFloatParameter("/master/level", 0.1f, 0f, 1f);
  artNetOutput.start();
```

- [ ] **Step 4: `draw()` anpassen**

Ersetze `artNetSender.sendToLeds(ledColors);` durch:

```java
  artNetOutput.setMasterLevel(masterLevel.getValue());
  artNetOutput.publish(ledColors);
```

Die Zeile `ledStripeFullActivationEffect.changeStripe();` bleibt vorerst stehen; Task 9 ersetzt sie.

- [ ] **Step 5: Farbtabelle gegen 30 Stripes absichern**

`LedNetworkTransportEffect.java:241` benutzt `stripeColorMapping[ledNetInfo[curLedIndex].stripeIndex]`, die Tabelle hat aber nur acht Einträge. Bei 30 Stripes läuft das über, sobald `/net/impulse/color/useRemoteCol` auf 0 steht. Ersetze:

```java
        LedColor col = stripeColorMapping[ledNetInfo[curLedIndex].stripeIndex]; //color lookup made for 8 outputs
```

durch:

```java
        // Tabelle hat acht Eintraege, es gibt aber 30 Stripes
        LedColor col = stripeColorMapping[ledNetInfo[curLedIndex].stripeIndex % stripeColorMapping.length];
```

- [ ] **Step 6: Sketch starten und Zuordnungstabelle prüfen**

```bash
open -a Processing /Users/macbook/Projekte/_gitHub/imPulse/imPulse.pde
```

Dann in der IDE auf Play. Ein `processing-java` gibt es in dieser Installation nicht.

Expected: Beim Start erscheint die Zuordnungstabelle mit 30 Zeilen. Der Sketch läuft ohne Ausnahme. **Achtung:** `data/nodeCrossings.txt` enthält noch Indizes der alten 720er-Geometrie — solange dort Werte über 17999 stehen, kann `loadListOfNodes` aussteigen. Ist das der Fall, die Datei vorübergehend leeren (`: > data/nodeCrossings.txt`); Task 8 macht das Laden robust.

- [ ] **Step 7: Commit**

```bash
git add imPulse.pde StripeHardwareHandler.java LedNetworkTransportEffect.java data/nodeCrossings.txt
git commit -m "ArtNet: neuen Sender einbauen, Geometrie auf 30 x 600

15 Controller, 30 Stripes, 18.000 LEDs, 40 fps. ArtNetSender und die
artnet4j-Abhaengigkeit entfallen. Master-Pegel per /master/level,
Vorgabe 0.1. Farbtabelle gegen mehr als acht Stripes abgesichert."
```

---

### Task 6: ArtPoll-Sonde (Test 1d)

**Files:**
- Create: `test/PollProbe.java`

**Interfaces:**
- Consumes: nichts aus dem Sketch. Eigenständiges Programm.
- Produces: keine, reine Messung.

- [ ] **Step 1: Sonde schreiben**

`test/PollProbe.java`:

```java
// Test 1d - fragt die Controller nach ihrem eigenen Befinden.
//
// Auf OpPoll antwortet die Firmware mit einem ArtPollReply, in dessen
// NodeReport ein Statusstring der Form
//   numOuts;2;numUniPOut;5;temp;41.2;fps;39.8;uUniPF;10.0;
// steht. fps ist die vom Controller gemessene Rate eintreffender Sync-Pakete,
// uUniPF die geglaettete Anzahl empfangener Universen je Frame. Erwartet
// werden rund 40 und 10.0, waehrend der Sketch oder TimingProbe laeuft.
public class PollProbe {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int ARTNET_PORT = 6454;

  public static void main(String[] args) throws Exception {
    java.net.DatagramSocket socket = new java.net.DatagramSocket();
    socket.setSoTimeout(1500);

    byte[] poll = new byte[14];
    poll[0] = 'A'; poll[1] = 'r'; poll[2] = 't'; poll[3] = '-';
    poll[4] = 'N'; poll[5] = 'e'; poll[6] = 't'; poll[7] = 0;
    poll[8] = 0x00; poll[9] = 0x20;   // OpPoll 0x2000, little-endian
    poll[10] = 0; poll[11] = 14;      // ProtVer
    poll[12] = 0x02;                  // TalkToMe: antworte bei Aenderung
    poll[13] = 0;                     // Priority

    for (int octet : OCTETS) {
      String ip = "2.2.2." + octet;
      java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
      socket.send(new java.net.DatagramPacket(poll, poll.length, addr, ARTNET_PORT));

      byte[] in = new byte[600];
      java.net.DatagramPacket reply = new java.net.DatagramPacket(in, in.length);
      try {
        socket.receive(reply);
      } catch (java.net.SocketTimeoutException e) {
        System.out.printf("%-9s keine Antwort%n", ip);
        Check.that(ip + " antwortet", false);
        continue;
      }

      String text = new String(in, 0, reply.getLength(), "ISO-8859-1");
      int start = text.indexOf("numOuts;");
      if (start < 0) {
        System.out.printf("%-9s Antwort ohne Statusbericht%n", ip);
        Check.that(ip + " liefert Statusbericht", false);
        continue;
      }
      int end = text.indexOf('\0', start);
      String report = end > start ? text.substring(start, end) : text.substring(start);

      double fps = field(report, "fps");
      double uni = field(report, "uUniPF");
      System.out.printf("%-9s %s%n", ip, report);

      Check.that(ip + " meldet 38-42 fps", fps >= 38.0 && fps <= 42.0);
      Check.that(ip + " meldet 9.5-10.5 Universen je Frame", uni >= 9.5 && uni <= 10.5);
    }

    socket.close();
    System.exit(Check.report("PollProbe"));
  }

  static double field(String report, String name) {
    int i = report.indexOf(name + ";");
    if (i < 0) return -1;
    int from = i + name.length() + 1;
    int to = report.indexOf(';', from);
    if (to < 0) to = report.length();
    try {
      return Double.parseDouble(report.substring(from, to));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
```

- [ ] **Step 2: Ohne laufenden Sender messen**

```bash
test/run.sh > /dev/null
java -cp "build:/Applications/Processing.app/Contents/Java/core.jar" PollProbe
```

Expected: Die Controller antworten, aber `fps` und `uUniPF` sind 0 oder veraltet — es sendet ja niemand. Der Test schlägt fehl, und genau das belegt, dass die Werte tatsächlich vom Datenstrom abhängen und nicht fest verdrahtet sind.

- [ ] **Step 3: Mit laufendem Sender messen**

In einem Terminal:

```bash
java -cp "build:/Applications/Processing.app/Contents/Java/core.jar" TimingProbe 60
```

Parallel in einem zweiten:

```bash
java -cp "build:/Applications/Processing.app/Contents/Java/core.jar" PollProbe
```

Expected: PASS — alle 15 Controller melden rund 40 fps und `uUniPF` nahe 10.0.

Meldet ein Controller weniger als 10, gehen Universen verloren. Dann in `ArtNetOutput` `setSyncBroadcast(true)` versuchen und erneut messen; hilft auch das nicht, ist die Ursache Paketverlust und gehört als Befund festgehalten, nicht überdeckt.

- [ ] **Step 4: Commit**

```bash
git add test/PollProbe.java
git commit -m "ArtNet: ArtPoll-Sonde als Gegenprobe am Empfangsende

Liest fps und uUniPF aus dem ArtPollReply. Damit ist pruefbar, ob
tatsaechlich alle zehn Universen je Controller ankommen.
Messung: <Zahlen einsetzen>."
```

---

### Task 7: NodeCrossingStore

**Files:**
- Create: `NodeCrossingStore.java`
- Create: `test/NodeCrossingStoreTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces: `NodeCrossingStore(int numStripes, int numLedsPerStripe)`; `boolean add(int stripeA, int ledA, int stripeB, int ledB)`; `String lastMessage()`; `boolean undo()`; `int size()`; `int loadedCount()`; `int sessionCount()`; `java.util.List<java.util.TreeSet<Integer>> crossings()`; `void load(String path)`; `void save(String path) throws java.io.IOException`; Konstante `MIN_SAME_STRIPE_DISTANCE = 3`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`test/NodeCrossingStoreTest.java`:

```java
import java.io.File;
import java.nio.file.Files;

public class NodeCrossingStoreTest {
  static final int STRIPES = 30;
  static final int PER_STRIPE = 600;

  public static void main(String[] args) throws Exception {
    // ---- Validierung ----
    NodeCrossingStore s = new NodeCrossingStore(STRIPES, PER_STRIPE);

    Check.that("gueltiges Paar auf zwei Stripes", s.add(3, 412, 7, 158));
    Check.eq("nach erstem Paar", 1, s.size());

    Check.that("dieselbe LED wird abgewiesen", !s.add(3, 412, 3, 412));
    Check.that("Meldung nennt den Grund", s.lastMessage().length() > 0);

    Check.that("gleicher Stripe, zu nah, wird abgewiesen", !s.add(5, 100, 5, 102));
    Check.that("gleicher Stripe, weit genug, wird angenommen", s.add(5, 100, 5, 103));
    Check.eq("nach dem gueltigen Paar auf einem Stripe", 2, s.size());

    Check.that("Duplikat wird abgewiesen", !s.add(3, 412, 7, 158));
    Check.that("Duplikat auch in umgekehrter Reihenfolge", !s.add(7, 158, 3, 412));
    Check.eq("Groesse nach Duplikaten unveraendert", 2, s.size());

    // ---- Undo ----
    Check.that("Undo nimmt das letzte Paar zurueck", s.undo());
    Check.eq("nach Undo", 1, s.size());
    Check.that("Undo bis zum Anfang", s.undo());
    Check.eq("Liste leer", 0, s.size());
    Check.that("Undo auf leerer Liste tut nichts", !s.undo());

    // ---- Datei-Rundlauf ----
    File dir = Files.createTempDirectory("crossings").toFile();
    File file = new File(dir, "nodeCrossings.txt");

    NodeCrossingStore w = new NodeCrossingStore(STRIPES, PER_STRIPE);
    w.add(0, 10, 1, 20);
    w.add(2, 30, 3, 40);
    w.save(file.getAbsolutePath());

    NodeCrossingStore r = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r.load(file.getAbsolutePath());
    Check.eq("geladene Anzahl", 2, r.size());
    Check.eq("als geladen gezaehlt", 2, r.loadedCount());
    Check.eq("keine neuen in dieser Sitzung", 0, r.sessionCount());

    // globale Indizes: Stripe 0 LED 10 -> 10, Stripe 1 LED 20 -> 620
    Check.that("erstes Paar enthaelt 10", r.crossings().get(0).contains(10));
    Check.that("erstes Paar enthaelt 620", r.crossings().get(0).contains(620));

    // Undo darf geladene Eintraege nicht anfassen
    Check.that("Undo greift nicht auf geladene Eintraege", !r.undo());
    Check.eq("geladene Eintraege unveraendert", 2, r.size());

    // Zweimal speichern verdoppelt nichts
    r.add(4, 50, 5, 60);
    r.save(file.getAbsolutePath());
    r.save(file.getAbsolutePath());
    NodeCrossingStore r2 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r2.load(file.getAbsolutePath());
    Check.eq("nach zweimal Speichern", 3, r2.size());

    // ---- fehlerhafte Datei ----
    File bad = new File(dir, "kaputt.txt");
    Files.write(bad.toPath(), "10 620\n-1 5\n99999999 3\nnichts\n30 640\n".getBytes("UTF-8"));
    NodeCrossingStore r3 = new NodeCrossingStore(STRIPES, PER_STRIPE);
    r3.load(bad.getAbsolutePath());
    Check.eq("nur die zwei gueltigen Zeilen", 2, r3.size());

    System.exit(Check.report("NodeCrossingStoreTest"));
  }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh NodeCrossingStoreTest`
Expected: FAIL — „cannot find symbol: class NodeCrossingStore".

- [ ] **Step 3: Umsetzung**

`NodeCrossingStore.java`:

```java
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

// Haelt die Liste der Kreuzungen und kuemmert sich um Validierung, Undo und
// die Datei. Bewusst ohne Processing- und Netzabhaengigkeit, damit die Logik
// pruefbar ist - hier lagen die Fehler der alten Kalibrierung.
class NodeCrossingStore {

  // Zwei Cursor auf demselben Stripe muessen mindestens so weit auseinander
  // liegen, sonst ist es keine Kreuzung sondern ein Zittern des Cursors.
  static final int MIN_SAME_STRIPE_DISTANCE = 3;

  private final int numStripes;
  private final int numLedsPerStripe;
  private final List<TreeSet<Integer>> crossings = new ArrayList<TreeSet<Integer>>();
  private int loadedCount = 0;
  private String message = "";

  NodeCrossingStore(int numStripes, int numLedsPerStripe) {
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
  }

  int size() { return crossings.size(); }
  int loadedCount() { return loadedCount; }
  int sessionCount() { return crossings.size() - loadedCount; }
  String lastMessage() { return message; }
  List<TreeSet<Integer>> crossings() { return crossings; }

  private int globalIndex(int stripe, int led) {
    return stripe * numLedsPerStripe + led;
  }

  // Nimmt das Paar auf, wenn es plausibel ist. Sonst false und der Grund
  // steht in lastMessage().
  boolean add(int stripeA, int ledA, int stripeB, int ledB) {
    if (stripeA < 0 || stripeA >= numStripes || stripeB < 0 || stripeB >= numStripes) {
      message = "Stripe ausserhalb des Bereichs";
      return false;
    }
    if (ledA < 0 || ledA >= numLedsPerStripe || ledB < 0 || ledB >= numLedsPerStripe) {
      message = "LED ausserhalb des Bereichs";
      return false;
    }
    if (stripeA == stripeB) {
      if (Math.abs(ledA - ledB) < MIN_SAME_STRIPE_DISTANCE) {
        message = "Auf demselben Stripe muessen mindestens "
            + MIN_SAME_STRIPE_DISTANCE + " LEDs dazwischen liegen";
        return false;
      }
    }
    int a = globalIndex(stripeA, ledA);
    int b = globalIndex(stripeB, ledB);
    if (a == b) {
      message = "Beide Cursor stehen auf derselben LED";
      return false;
    }
    TreeSet<Integer> pair = new TreeSet<Integer>();
    pair.add(a);
    pair.add(b);
    for (TreeSet<Integer> existing : crossings) {
      if (existing.equals(pair)) {
        message = "Dieses Paar steht schon in der Liste";
        return false;
      }
    }
    crossings.add(pair);
    message = "Node " + crossings.size() + " gesetzt: " + a + " + " + b;
    return true;
  }

  // Nimmt nur zurueck, was in dieser Sitzung dazugekommen ist. Eine bestehende
  // Kalibrierung soll sich nicht versehentlich abraeumen lassen.
  boolean undo() {
    if (crossings.size() <= loadedCount) {
      message = "Nichts zurueckzunehmen";
      return false;
    }
    TreeSet<Integer> removed = crossings.remove(crossings.size() - 1);
    message = "Zurueckgenommen: " + removed;
    return true;
  }

  // Liest die Datei. Fehlerhafte Zeilen werden gemeldet und uebersprungen,
  // nicht als Absturz beim naechsten Start weitergereicht.
  void load(String path) {
    crossings.clear();
    loadedCount = 0;
    int maxIndex = numStripes * numLedsPerStripe;
    File file = new File(path);
    if (!file.exists()) {
      message = "Keine Datei " + path + ", starte mit leerer Liste";
      return;
    }
    BufferedReader reader = null;
    int lineNo = 0;
    int skipped = 0;
    try {
      reader = new BufferedReader(new FileReader(file));
      String line = reader.readLine();
      while (line != null) {
        lineNo++;
        String trimmed = line.trim();
        if (trimmed.length() > 0) {
          TreeSet<Integer> pair = new TreeSet<Integer>();
          boolean ok = true;
          for (String token : trimmed.split("\\s+")) {
            int idx;
            try {
              idx = Integer.parseInt(token);
            } catch (NumberFormatException e) {
              System.out.println("Zeile " + lineNo + " uebersprungen: \"" + token + "\" ist keine Zahl");
              ok = false;
              break;
            }
            if (idx < 0 || idx >= maxIndex) {
              System.out.println("Zeile " + lineNo + " uebersprungen: Index " + idx
                  + " liegt ausserhalb von 0.." + (maxIndex - 1));
              ok = false;
              break;
            }
            pair.add(idx);
          }
          if (ok && pair.size() >= 2) {
            crossings.add(pair);
          } else {
            skipped++;
          }
        }
        line = reader.readLine();
      }
    } catch (IOException e) {
      System.out.println("Datei konnte nicht gelesen werden: " + e);
    } finally {
      if (reader != null) { try { reader.close(); } catch (IOException e) { } }
    }
    loadedCount = crossings.size();
    message = crossings.size() + " Nodes geladen"
        + (skipped > 0 ? ", " + skipped + " Zeilen uebersprungen" : "");
  }

  // Schreibt die vollstaendige Liste in eine Nebendatei und benennt sie
  // anschliessend um. Damit gibt es weder doppelte Eintraege durch Anhaengen
  // noch einen halb geschriebenen Stand bei Absturz.
  void save(String path) throws IOException {
    File target = new File(path);
    File tmp = new File(path + ".tmp");
    PrintWriter writer = new PrintWriter(tmp, "UTF-8");
    try {
      for (TreeSet<Integer> pair : crossings) {
        StringBuilder sb = new StringBuilder();
        for (Integer idx : pair) {
          if (sb.length() > 0) sb.append(' ');
          sb.append(idx);
        }
        writer.println(sb.toString());
      }
    } finally {
      writer.close();
    }
    java.nio.file.Files.move(tmp.toPath(), target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    loadedCount = crossings.size();
    message = crossings.size() + " Nodes nach " + target.getName() + " geschrieben";
  }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `test/run.sh NodeCrossingStoreTest`
Expected: PASS.

- [ ] **Step 5: Alle bisherigen Tests laufen lassen**

Run: `test/run.sh ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest`
Expected: alle drei bestehen.

- [ ] **Step 6: Commit**

```bash
git add NodeCrossingStore.java test/NodeCrossingStoreTest.java
git commit -m "Kalibrierung: Speicher fuer Kreuzungen mit Validierung und Undo

Behebt die drei Fehlerquellen der alten Fassung: kein Undo, Anhaengen
statt Ersetzen beim Speichern, und ungeprueft uebernommene Indizes, die
beim naechsten Start zum Absturz fuehrten. Geladene Eintraege sind gegen
Undo geschuetzt."
```

---

### Task 8: Nodes ohne Neustart übernehmen

**Files:**
- Modify: `LedStripeNetworks.java`
- Create: `test/ApplyCrossingsTest.java`

**Interfaces:**
- Consumes: `NodeCrossingStore.crossings()`.
- Produces: `static void LedInNetInfo.applyCrossings(java.util.List<java.util.TreeSet<Integer>> crossings, LedInNetInfo[] ledNetInfos, java.util.ArrayList<LedNetworkNode> target)`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`test/ApplyCrossingsTest.java`:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class ApplyCrossingsTest {
  public static void main(String[] args) {
    int stripes = 4;
    int perStripe = 100;
    LedInNetInfo[] info = LedInNetInfo.buildNetInfo(stripes, perStripe);
    ArrayList<LedNetworkNode> nodes = new ArrayList<LedNetworkNode>();

    List<TreeSet<Integer>> a = new ArrayList<TreeSet<Integer>>();
    a.add(pair(10, 150));
    a.add(pair(20, 250));
    LedInNetInfo.applyCrossings(a, info, nodes);

    Check.eq("zwei Nodes", 2, nodes.size());
    Check.eq("Node 0 hat id 0", 0, nodes.get(0).id);
    Check.eq("Node 1 hat id 1", 1, nodes.get(1).id);
    Check.that("LED 10 gehoert zu Node 0", info[10].partOfNode == nodes.get(0));
    Check.that("LED 150 gehoert zu Node 0", info[150].partOfNode == nodes.get(0));
    Check.that("LED 20 gehoert zu Node 1", info[20].partOfNode == nodes.get(1));
    Check.that("LED 11 gehoert zu keinem Node", info[11].partOfNode == null);

    // Erneutes Anwenden mit weniger Kreuzungen muss die alte Zuordnung loeschen
    ArrayList<LedNetworkNode> sameList = nodes;
    List<TreeSet<Integer>> b = new ArrayList<TreeSet<Integer>>();
    b.add(pair(30, 350));
    LedInNetInfo.applyCrossings(b, info, sameList);

    Check.eq("jetzt ein Node", 1, sameList.size());
    Check.that("dieselbe Listeninstanz", sameList == nodes);
    Check.that("LED 10 ist wieder frei", info[10].partOfNode == null);
    Check.that("LED 150 ist wieder frei", info[150].partOfNode == null);
    Check.that("LED 30 gehoert zum neuen Node", info[30].partOfNode == sameList.get(0));

    System.exit(Check.report("ApplyCrossingsTest"));
  }

  static TreeSet<Integer> pair(int a, int b) {
    TreeSet<Integer> s = new TreeSet<Integer>();
    s.add(a); s.add(b);
    return s;
  }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `test/run.sh ApplyCrossingsTest`
Expected: FAIL — „cannot find symbol: method applyCrossings".

- [ ] **Step 3: Umsetzung**

In `LedStripeNetworks.java` innerhalb der Klasse `LedInNetInfo` ergänzen:

```java
	// Baut die Node-Struktur aus einer Liste von Kreuzungen neu auf. Wird beim
	// Start und beim Neuladen waehrend der Kalibrierung benutzt.
	//
	// Die Zielliste wird in-place geaendert, weil LedNetworkTransportEffect und
	// LedNetworkNodeEffects dieselbe Instanz halten und die Aenderung sonst nicht
	// mitbekaemen.
	public static void applyCrossings(java.util.List<TreeSet<Integer>> crossings,
			LedInNetInfo[] ledNetInfos, ArrayList<LedNetworkNode> target) {
		// alte Zuordnung vollstaendig loeschen, sonst bleiben LEDs an
		// zurueckgenommenen Nodes haengen
		for (int i = 0; i < ledNetInfos.length; i++) {
			ledNetInfos[i].partOfNode = null;
		}
		target.clear();

		int nodeId = 0;
		for (TreeSet<Integer> cluster : crossings) {
			LedNetworkNode node = new LedNetworkNode(nodeId, new TreeSet<Integer>(cluster));
			target.add(node);
			for (Integer ledIdx : node.ledIndices) {
				if (ledIdx >= 0 && ledIdx < ledNetInfos.length) {
					ledNetInfos[ledIdx].partOfNode = node;
				}
			}
			nodeId++;
		}
		System.out.println(target.size() + " Nodes uebernommen");
	}
```

Ausserdem die alte `loadListOfNodes` und `buildClusterInfo` entfernen — `NodeCrossingStore.load()` und `applyCrossings` ersetzen beide. Damit verschwindet auch die Stelle, an der ein Index von `-1` zum Absturz führte.

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `test/run.sh ApplyCrossingsTest`
Expected: PASS.

- [ ] **Step 5: `setup()` umstellen**

In `imPulse.pde` ersetze:

```java
  listOfNodes = LedInNetInfo.loadListOfNodes(dataPath("nodeCrossings.txt"), ledNetInfo);
```

durch:

```java
  crossingStore = new NodeCrossingStore(numStripes, numLedsPerStripe);
  crossingStore.load(dataPath("nodeCrossings.txt"));
  System.out.println(crossingStore.lastMessage());
  listOfNodes = new ArrayList<LedNetworkNode>();
  LedInNetInfo.applyCrossings(crossingStore.crossings(), ledNetInfo, listOfNodes);
```

und ergänze bei den globalen Feldern:

```java
NodeCrossingStore crossingStore;
```

- [ ] **Step 6: Sketch starten**

Expected: Der Sketch startet auch mit einer `nodeCrossings.txt` aus der alten Geometrie — ungültige Zeilen werden gemeldet und übersprungen statt zum Absturz zu führen.

- [ ] **Step 7: Commit**

```bash
git add LedStripeNetworks.java imPulse.pde test/ApplyCrossingsTest.java
git commit -m "Kalibrierung: Nodes lassen sich zur Laufzeit neu uebernehmen

applyCrossings baut die Struktur in-place neu auf, sodass Transport- und
Node-Effekt dieselbe Liste weiterbenutzen. loadListOfNodes und das nie
aufgerufene buildClusterInfo entfallen."
```

---

### Task 9: NodeCalibration

**Files:**
- Create: `NodeCalibration.java`
- Delete: `LedStripeFullActivationEffect.java`
- Modify: `imPulse.pde`

**Interfaces:**
- Consumes: `NodeCrossingStore`, `LedInNetInfo.applyCrossings`, `runnableLedEffect` aus `mixer.java`.
- Produces: `NodeCalibration(NodeCrossingStore store, LedInNetInfo[] ledNetInfo, ArrayList<LedNetworkNode> nodes, int numStripes, int numLedsPerStripe, String filePath)`; `LedColor[] drawMe()`; `String getName()`; `void handleKeyPressed(int keyCode, char key)`; `void handleKeyReleased()`; `void update()`; `String hudText()`.

- [ ] **Step 1: Effekt schreiben**

`NodeCalibration.java`:

```java
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;

// Manuelles Aufnehmen der Kreuzungen. Zwei Cursor, die abwechselnd bewegt
// werden - die alte Fassung hatte dafuer sieben Modi in einem Dropdown,
// obwohl es immer nur diese zwei Cursor waren.
//
// Beide Cursor-Stripes werden auf ganzer Laenge schwach beleuchtet, damit
// erkennbar ist welcher Stripe gemeint ist; darauf sitzt je ein heller Punkt.
public class NodeCalibration implements runnableLedEffect {

  private static final int[] STEP_SIZES = { 1, 10, 100 };
  private static final long REPEAT_MILLIS = 30;
  private static final float DIM = 0.06f;

  private final NodeCrossingStore store;
  private final LedInNetInfo[] ledNetInfo;
  private final ArrayList<LedNetworkNode> nodes;
  private final int numStripes;
  private final int numLedsPerStripe;
  private final String filePath;
  private final LedColor[] buffer;

  private final int[] cursorStripe = { 0, 1 };
  private final int[] cursorLed = { 0, 0 };
  private int active = 0;
  private int stepIndex = 1;
  private boolean showNodes = false;

  private int heldLed = 0;      // -1, 0, +1
  private int heldStripe = 0;
  private long lastRepeat = 0;
  private String message = "";

  NodeCalibration(NodeCrossingStore store, LedInNetInfo[] ledNetInfo,
                  ArrayList<LedNetworkNode> nodes, int numStripes,
                  int numLedsPerStripe, String filePath) {
    this.store = store;
    this.ledNetInfo = ledNetInfo;
    this.nodes = nodes;
    this.numStripes = numStripes;
    this.numLedsPerStripe = numLedsPerStripe;
    this.filePath = filePath;
    this.buffer = LedColor.createColorArray(numStripes * numLedsPerStripe);
    if (numStripes > 1) cursorStripe[1] = 1;
  }

  public String getName() { return "Kalibrierung"; }

  private int step() { return STEP_SIZES[stepIndex]; }

  private int globalIndex(int cursor) {
    return cursorStripe[cursor] * numLedsPerStripe + cursorLed[cursor];
  }

  // Aus draw() aufgerufen, solange eine Pfeiltaste gehalten wird.
  void update() {
    if (heldLed == 0 && heldStripe == 0) return;
    long now = System.currentTimeMillis();
    if (now - lastRepeat < REPEAT_MILLIS) return;
    lastRepeat = now;

    if (heldLed != 0) {
      int v = cursorLed[active] + heldLed * step();
      if (v < 0) v = 0;
      if (v > numLedsPerStripe - 1) v = numLedsPerStripe - 1;
      cursorLed[active] = v;
    }
    if (heldStripe != 0) {
      int v = cursorStripe[active] + heldStripe;
      if (v < 0) v = 0;
      if (v > numStripes - 1) v = numStripes - 1;
      cursorStripe[active] = v;
    }
  }

  // Pfeiltasten. Entspricht PConstants.LEFT/UP/RIGHT/DOWN, hier benannt,
  // weil diese Klasse nicht von PApplet erbt.
  private static final int KEY_LEFT = 37;
  private static final int KEY_UP = 38;
  private static final int KEY_RIGHT = 39;
  private static final int KEY_DOWN = 40;

  void handleKeyPressed(int keyCode, char key) {
    if (keyCode == KEY_LEFT) { heldLed = -1; }
    else if (keyCode == KEY_RIGHT) { heldLed = 1; }
    else if (keyCode == KEY_UP) { heldStripe = -1; }
    else if (keyCode == KEY_DOWN) { heldStripe = 1; }
  }

  void handleKeyReleased() {
    heldLed = 0;
    heldStripe = 0;
  }

  // Rueckgabe true, wenn die Taste verarbeitet wurde.
  boolean handleCommand(char key) {
    if (key == '\t') {
      active = 1 - active;
      message = "Cursor " + (active == 0 ? "A" : "B") + " aktiv";
      return true;
    }
    if (key == '\n' || key == '\r') {
      store.add(cursorStripe[0], cursorLed[0], cursorStripe[1], cursorLed[1]);
      message = store.lastMessage();
      active = 0;
      return true;
    }
    if (key == 8 || key == 127) {   // Backspace bzw. Delete
      store.undo();
      message = store.lastMessage();
      return true;
    }
    if (key == 'f' || key == 'F') {
      stepIndex = (stepIndex + 1) % STEP_SIZES.length;
      message = "Schrittweite " + step();
      return true;
    }
    if (key == 's' || key == 'S') {
      try {
        store.save(filePath);
        message = store.lastMessage();
      } catch (IOException e) {
        message = "Speichern fehlgeschlagen: " + e;
      }
      return true;
    }
    if (key == 'r' || key == 'R') {
      LedInNetInfo.applyCrossings(store.crossings(), ledNetInfo, nodes);
      message = nodes.size() + " Nodes uebernommen, ohne Neustart";
      return true;
    }
    if (key == 'n' || key == 'N') {
      showNodes = !showNodes;
      message = showNodes ? "Nodes eingeblendet" : "Nodes ausgeblendet";
      return true;
    }
    return false;
  }

  String hudText() {
    return String.format(
        "Stripe A [%2d] LED %3d%s    Stripe B [%2d] LED %3d%s    "
        + "Nodes: %d geladen + %d neu    Schritt: %d%n%s",
        cursorStripe[0], cursorLed[0], active == 0 ? " <-" : "  ",
        cursorStripe[1], cursorLed[1], active == 1 ? " <-" : "  ",
        store.loadedCount(), store.sessionCount(), step(), message);
  }

  public LedColor[] drawMe() {
    LedColor.set(buffer, new LedColor(0, 0, 0));

    // beide Cursor-Stripes schwach beleuchten, damit sie im Netz auffindbar sind
    dimStripe(cursorStripe[0], 0f, DIM, 0f);
    dimStripe(cursorStripe[1], DIM, 0f, 0f);

    if (showNodes) {
      java.util.List<TreeSet<Integer>> all = store.crossings();
      for (int i = 0; i < all.size(); i++) {
        boolean fromSession = i >= store.loadedCount();
        for (Integer idx : all.get(i)) {
          if (idx >= 0 && idx < buffer.length) {
            if (fromSession) buffer[idx].set(new LedColor(0, 1, 1));   // neu: cyan
            else buffer[idx].set(new LedColor(1, 0, 1));               // geladen: magenta
          }
        }
      }
    }

    // die Cursor zuletzt, damit sie nichts verdeckt
    buffer[globalIndex(1)].set(new LedColor(1, 0, 0));
    buffer[globalIndex(0)].set(new LedColor(0, 1, 0));
    return buffer;
  }

  private void dimStripe(int stripe, float r, float g, float b) {
    int base = stripe * numLedsPerStripe;
    for (int i = 0; i < numLedsPerStripe; i++) {
      buffer[base + i].set(new LedColor(r, g, b));
    }
  }
}
```

- [ ] **Step 2: Alten Effekt entfernen**

```bash
git rm LedStripeFullActivationEffect.java
```

- [ ] **Step 3: `imPulse.pde` umstellen**

Entferne die Felder `stripeInfos`, `ledStripeFullActivationEffect`, `stripeChangeMode`, das Enum `StripeChangeMode`, das Feld `cp5` samt `import controlP5.*;`, die Methode `dropdown(int)` sowie den ControlP5-Block am Ende von `setup()`. Entferne die Zeile `stripeInfos = stripeConfiguration.builtStripeInfo();` und die Erzeugung von `ledStripeFullActivationEffect`.

Ergänze bei den globalen Feldern:

```java
NodeCalibration nodeCalibration;
boolean calibrationMode = false;
```

In `setup()` nach `applyCrossings`:

```java
  nodeCalibration = new NodeCalibration(crossingStore, ledNetInfo, listOfNodes,
      numStripes, numLedsPerStripe, dataPath("nodeCrossings.txt"));
```

In `draw()` ersetze:

```java
  ledColors=mixer.mix();
```

durch:

```java
  if (calibrationMode) {
    nodeCalibration.update();
    ledColors = nodeCalibration.drawMe();
  } else {
    ledColors = mixer.mix();
  }
```

und entferne `ledStripeFullActivationEffect.changeStripe();`. Nach `image(canvas, ...)` einfügen:

```java
  if (calibrationMode) {
    fill(255);
    text(nodeCalibration.hudText(), 10, numStripes * 10 + 20);
  }
```

Ersetze `keyPressed()` und `keyReleased()` vollständig durch:

```java
void keyPressed() {
  if (calibrationMode && key == CODED) {
    nodeCalibration.handleKeyPressed(keyCode, key);
  }
}

void keyReleased() {
  if (key == 'c' || key == 'C') {
    calibrationMode = !calibrationMode;
    println(calibrationMode ? "Kalibriermodus an" : "Kalibriermodus aus");
    return;
  }
  if (!calibrationMode) {
    return;
  }
  if (key == CODED) {
    nodeCalibration.handleKeyReleased();
  } else {
    nodeCalibration.handleCommand(key);
  }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `test/run.sh ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest ApplyCrossingsTest`
Expected: alle vier bestehen.

- [ ] **Step 5: Sketch starten und den Ablauf durchspielen**

Sketch starten, `c` drücken. Erwartet: Anzeige der beiden Cursor. Dann der Reihe nach prüfen — jeweils an der Anzeige, ohne Hardware:

- Pfeile links/rechts bewegen Cursor A, hoch/runter wechseln den Stripe
- `TAB` schaltet auf Cursor B, die Pfeile wirken jetzt dort
- `f` schaltet die Schrittweite 1 → 10 → 100 → 1
- `ENTER` erhöht „neu" um eins; nochmals `ENTER` an derselben Stelle wird mit „Dieses Paar steht schon in der Liste" abgewiesen
- beide Cursor auf denselben Stripe, zwei LEDs Abstand, `ENTER` → Abweisung mit Begründung
- `BACKSPACE` verringert „neu" um eins, bis „Nichts zurueckzunehmen"
- `n` blendet gesetzte Nodes ein
- `s` schreibt die Datei; zweimal `s` hintereinander darf die Zeilenzahl nicht verdoppeln (`wc -l data/nodeCrossings.txt`)
- `r` meldet die übernommene Anzahl
- `c` verlässt den Modus, die Show läuft weiter

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Kalibrierung: zwei Cursor statt sieben Dropdown-Modi

TAB wechselt den Cursor, ENTER speichert, BACKSPACE nimmt zurueck,
R uebernimmt ohne Neustart. Dropdown und ControlP5 entfallen.
LedStripeFullActivationEffect wird durch NodeCalibration ersetzt."
```

---

### Task 10: Testbilder für Test 2

**Files:**
- Modify: `NodeCalibration.java`
- Modify: `imPulse.pde`

**Interfaces:**
- Consumes: alles aus Task 9.
- Produces: `void setPattern(int pattern)`, `int pattern()` in `NodeCalibration`; Tasten `0`–`5`.

- [ ] **Step 1: Testbilder ergänzen**

In `NodeCalibration.java` ergänzen:

```java
  // Testbilder fuer die Abnahme am Aufbau. 0 = Kalibrierung, 1..5 siehe
  // Abschnitt 6 der Spezifikation. Alle laufen mit dem Master-Pegel des
  // Senders, die Stripes vertragen keine volle Helligkeit.
  private int pattern = 0;
  private int patternStripe = 0;
  private int patternLed = 0;
  private long patternLastStep = 0;

  void setPattern(int p) {
    pattern = p;
    patternStripe = 0;
    patternLed = 0;
    message = pattern == 0 ? "Kalibrierung" : "Testbild " + pattern;
  }

  int pattern() { return pattern; }

  private boolean drawPattern() {
    if (pattern == 0) return false;
    LedColor.set(buffer, new LedColor(0, 0, 0));
    long now = System.currentTimeMillis();

    if (pattern == 1) {
      // ein Stripe nach dem anderen, eine Sekunde je Stripe
      if (now - patternLastStep > 1000) {
        patternLastStep = now;
        patternStripe = (patternStripe + 1) % numStripes;
      }
      dimStripe(patternStripe, 1f, 1f, 1f);
      message = "Testbild 1 - Stripe " + patternStripe;

    } else if (pattern == 2) {
      // Lauflicht ueber einen Stripe, deckt die Universumsgrenzen ab
      if (now - patternLastStep > 20) {
        patternLastStep = now;
        patternLed = (patternLed + 1) % numLedsPerStripe;
      }
      int base = cursorStripe[0] * numLedsPerStripe;
      for (int i = 0; i < 4 && patternLed + i < numLedsPerStripe; i++) {
        buffer[base + patternLed + i].set(new LedColor(1, 1, 1));
      }
      message = "Testbild 2 - Stripe " + cursorStripe[0] + " LED " + patternLed
          + "  (Grenzen bei 128 256 384 512)";

    } else if (pattern == 3) {
      // nur die letzten vier LEDs jedes Stripes - leuchtet dabei der Anfang
      // des naechsten Outputs, schlagen die Reserve-Slots durch
      for (int s = 0; s < numStripes; s++) {
        int base = s * numLedsPerStripe;
        for (int i = numLedsPerStripe - 4; i < numLedsPerStripe; i++) {
          buffer[base + i].set(new LedColor(1, 1, 1));
        }
      }
      message = "Testbild 3 - nur LED " + (numLedsPerStripe - 4) + ".."
          + (numLedsPerStripe - 1) + ". Leuchtet sonst etwas, ist es Reserve-Durchschlag";

    } else if (pattern == 4) {
      // Rot, Gruen, Blau im Wechsel, je zwei Sekunden
      int phase = (int) ((now / 2000) % 3);
      LedColor c = phase == 0 ? new LedColor(1, 0, 0)
                 : phase == 1 ? new LedColor(0, 1, 0) : new LedColor(0, 0, 1);
      LedColor.set(buffer, c);
      message = "Testbild 4 - " + (phase == 0 ? "Rot" : phase == 1 ? "Gruen" : "Blau");

    } else if (pattern == 5) {
      LedColor.set(buffer, new LedColor(1, 1, 1));
      message = "Testbild 5 - flaechig weiss";
    }
    return true;
  }
```

und in `drawMe()` ganz am Anfang einfügen:

```java
    if (drawPattern()) {
      return buffer;
    }
```

Ausserdem in `handleCommand` vor dem abschliessenden `return false;`:

```java
    if (key >= '0' && key <= '5') {
      setPattern(key - '0');
      return true;
    }
```

- [ ] **Step 2: Tastenbelegung in der Anzeige ergänzen**

In `hudText()` die Formatzeichenkette um eine Zeile erweitern:

```java
        + "Nodes: %d geladen + %d neu    Schritt: %d%n%s%n"
        + "TAB Cursor  ENTER speichern  BACKSPACE zurueck  F Schritt  "
        + "S schreiben  R uebernehmen  N Nodes  0-5 Testbild  C beenden",
```

- [ ] **Step 3: Tests laufen lassen**

Run: `test/run.sh ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest ApplyCrossingsTest`
Expected: alle bestehen.

- [ ] **Step 4: Test 2 gemeinsam durchführen**

Sketch starten, `c` für den Kalibriermodus, dann die Muster der Reihe nach mit `1` bis `5`. Reihenfolge einhalten — geht Muster 1 schief, sind die übrigen ohne Aussage. Vor dem Start prüfen, dass `/master/level` bei 0.1 steht.

Jedes Ergebnis festhalten:

| Muster | Erwartung | Ergebnis |
|---|---|---|
| 1 | Stripes leuchten in der Reihenfolge der Controller-Liste, je einer pro Sekunde | |
| 2 | Lauflicht ohne Lücke, besonders bei LED 128, 256, 384, 512 | |
| 3 | Nur je vier LEDs am Stripe-Ende. Leuchtet der Anfang eines anderen Stripes mit, schlagen die Reserve-Slots durch | |
| 4 | Rot ist rot, Grün ist grün, Blau ist blau | |
| 5 | Eine Minute stabil, kein Flackern, kein Blackout | |

Bei Muster 4: kommen die Farben vertauscht, in `ArtNetOutput.java` `R_OFFSET = 2` und `B_OFFSET = 0` setzen und erneut prüfen.

- [ ] **Step 5: Commit**

```bash
git add NodeCalibration.java imPulse.pde
git commit -m "Testbilder fuer die Abnahme am Aufbau

Fuenf Muster auf den Tasten 0-5: Stripe-Zuordnung, Lauflicht ueber die
Universumsgrenzen, Reserve-Durchschlag, Kanalreihenfolge, Dauerlast.
Alle unter dem Master-Pegel des Senders.
Ergebnisse: <eintragen>."
```

---

## Nach Abschluss

- `CLAUDE.md` gehört aktualisiert: Geometrie (30 × 600 statt 16 × 720), `ArtNetOutput` statt `ArtNetSender`, ArtSync-Pflicht, `NodeCalibration` statt `LedStripeFullActivationEffect`, `test/run.sh`, kein artnet4j mehr.
- `data/nodeCrossings.txt` wird vor Ort neu aufgenommen. Die alte Datei aus der 720er-Geometrie ist wertlos.
- Offen und nur an der Hardware entscheidbar: Kanalreihenfolge (Muster 4) und ob Sync einzeln oder als Broadcast sauberer läuft (`setSyncBroadcast`, gemessen über `uUniPF` in `PollProbe`).
