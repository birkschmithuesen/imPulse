# imPulse Garbicz 2026 — ArtNet-Ausgang und Node-Kalibrierung

Branch: `grabicz26` · Datum: 2026-07-29

## 1. Ziel

Zwei Änderungen für die Garbicz-Fassung der Installation:

1. Der ArtNet-Ausgang adressiert die angeschlossenen Pixel2LED-Controller korrekt, statt einen flach durchlaufenden Pixelstrom zu senden. Die Controller werden über eine Liste ihrer IP-Endoktette konfiguriert. Die Ausgaberate ist konstant 40 fps.
2. Die manuelle Node-Kalibrierung wird auf zwei Cursor reduziert, bekommt eine Undo-Funktion und übernimmt Änderungen ohne Neustart.

Beides gehört zusammen: mit 600 statt 720 LEDs pro Stripe verschieben sich alle globalen LED-Indizes, `data/nodeCrossings.txt` wird ungültig und muss neu aufgenommen werden.

## 2. Hardware-Befund

Am 2026-07-29 am aufgebauten Setup abgelesen, nicht angenommen.

Der Rechner hängt über `en14` mit `2.0.0.1 / 255.0.0.0` am Netz. Es antworten 15 Controller. Ihre Web-Interfaces (`http://2.2.2.<x>/setup`) melden ausnahmslos:

| Eigenschaft | Wert |
|---|---|
| Oktette | 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 |
| Start-Universum | Oktett × 100 |
| Outputs | 2 |
| LEDs pro Output | 600 |

Daraus abgeleitet: 30 Stripes à 600 LEDs, 18.000 LEDs, 300 Universen pro Frame.

### Eigenschaften der Firmware, die das Design bestimmen

Ermittelt aus `/Users/macbook/Projekte/_gitHub/Pixel2LED-master/Pixel2LED.ino`.

- **4 Bytes pro LED.** `NUM_CHANNEL_PER_LED = 4` ist als „do not change this" markiert. Ein Universum trägt damit 128 LEDs, nicht 170.
- **Jeder Output beginnt bei DMX-Adresse 1 eines neuen Universums.** Bei 600 LEDs sind das 5 Universen je Output; das fünfte trägt nur 88 echte LEDs, die restlichen 40 Slots sind Reserve.
- **Ausgabe nur auf ArtSync.** Der einzige `FastLED.show()` im Normalbetrieb steht in `Pixel2LED.ino:373` im Zweig `case 0x5200`. Ohne Sync-Paket am Ende jedes Frames bleibt das Netz dunkel. Der bisherige `ArtNetSender` sendet keines — der Betrieb lief über MadMapper, das Syncs erzeugt.
- **Die 40 Reserve-Slots dürfen nicht mit Daten gefüllt werden.** Die Schleife in `Pixel2LED.ino:352` schreibt stets 128 LEDs und prüft nur gegen die Gesamtzahl, nicht gegen die Output-Grenze. Daten in den Reserve-Slots landen in den ersten 40 LEDs des nächsten Outputs.
- **Kein Polling nötig.** `blackOnOpPollTimeOut` und `blackOnOpSyncTimeOut` sind auskommentiert, ArtPoll ist rein diagnostisch.
- **ArtPollReply als Rückkanal.** Auf `OpPoll` antwortet die Firmware mit einem Statusstring der Form `numOuts;2;numUniPOut;5;temp;41.2;fps;39.8;uUniPF;10.0;`. `fps` ist die selbst gemessene Rate eintreffender Syncs, `uUniPF` die geglättete Anzahl empfangener Universen je Frame. Läuft über Ethernet, nicht über USB.

### Warum artnet4j entfällt

Eure Konvention ergibt Port-Adressen bis 2100. ArtNet adressiert 15-bit als `Net(7) : SubNet(4) : Universe(4)`; Universum 1000 heisst Net=3, SubNet=14, Universe=8. `ch.bildspur.artnet.ArtDmxPacket.setUniverse()` maskiert Subnet und Universe auf je 4 Bit und kennt kein Net-Feld — erreichbar wären nur die Adressen 0–255. Die Bibliothek ist auf diesem Rechner ohnehin nicht installiert.

## 3. Teil 1 — ArtNet-Ausgang

### 3.1 Konfiguration

In `imPulse.pde` bei den übrigen Hardware-Konstanten:

```java
int[] controllerOctets = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
int numLedsPerStripe   = 600;
int numStripes         = controllerOctets.length * 2;   // 30
```

Die Reihenfolge im Array bestimmt die Stripe-Nummerierung. Abgeleitete Grössen werden gerechnet, nicht verdrahtet:

```
LEDS_PER_UNIVERSE  = 512 / 4                              = 128
universesPerOutput = ceil(numLedsPerStripe * 4 / 512)     = 5
```

### 3.2 Abbildung

```
Controller k, Oktett o  ->  IP 2.2.2.o,  Start-Universum o*100
  Output j (0,1)        ->  Stripe s = 2k + j
    Universum o*100 + j*universesPerOutput + u,  u = 0..universesPerOutput-1
      DMX-Byte 4*i .. 4*i+3  ->  LED  s*numLedsPerStripe + (u*128 + i)
      i mit u*128 + i >= numLedsPerStripe  ->  vier Nullbytes

Mit den Werten dieser Installation: Universum o*100 + j*5 + u, u = 0..4, LED s*600 + (u*128 + i).
```

Port-Adresse aufgeteilt als `SubUni = addr & 0xFF`, `Net = (addr >> 8) & 0x7F`.

### 3.3 Paketaufbau

ArtDmx, 18 Byte Kopf, Ziel-Port 6454:

| Offset | Inhalt |
|---|---|
| 0–7 | `Art-Net\0` |
| 8–9 | OpCode 0x5000, little-endian |
| 10–11 | ProtVer 0, 14 |
| 12 | Sequence, aufsteigend 1–255 |
| 13 | Physical, 0 |
| 14 | SubUni |
| 15 | Net |
| 16–17 | Länge 512, big-endian |
| 18–529 | 128 × (R, G, B, 0) |

ArtSync, 14 Byte: `Art-Net\0`, OpCode 0x5200 little-endian, ProtVer 0/14, Aux1 0, Aux2 0. Wird nach den 10 DMX-Paketen an dieselbe Controller-IP geschickt.

Pro Frame: 15 × 10 = 300 DMX-Pakete plus 15 Sync-Pakete. Bei 40 fps rund 12.600 Pakete/s und 50 Mbit/s.

### 3.4 Kanalreihenfolge

```java
static final int R_OFFSET = 0, G_OFFSET = 1, B_OFFSET = 2;   // viertes Byte immer 0
```

Der Betrieb sagt R, G, B. Das Lesen der Firmware ergibt B, G, R: die vier DMX-Bytes werden als little-endian `uint32` gelesen und über `CRGB(word)` zerlegt, wodurch Byte 1 auf Blau fällt. Die Praxis entscheidet; die Konstante macht das Drehen zu einer Zeile. Test 2 klärt es endgültig.

### 3.5 Master-Pegel

`ArtNetOutput` hält `masterLevel`, Vorgabe **0.1**, angewandt beim Umrechnen von Fliesskomma auf Byte. Die Stripes ziehen bei Vollweiss mehr Strom, als die Einspeisung hergibt. Der Pegel greift hinter allem — Show, Testbilder, Kalibrierung — und ist damit das einzige Ventil, an dem nichts vorbeikommt. Über `/master/level` per OSC verstellbar.

### 3.6 Takt und Nebenläufigkeit

Der Versand bekommt einen eigenen Thread mit fester 40-Hz-Taktung, weil 300 `send()`-Aufrufe je Frame bei 25 ms Budget zu viel sind, um sie neben Simulation und Preview in `draw()` zu erledigen.

- `draw()` rendert und ruft `output.publish(ledColors)`. Dabei werden die 315 Pakete in den hinteren Puffer geschrieben und die beiden Puffer unter Sperre getauscht.
- Der Sender-Thread verschickt im 25-ms-Takt den jeweils vorderen Puffer. Der Takt läuft über absolute Zeitpunkte (`deadline += 25ms`), nicht über `sleep(25)`, damit sich kein Versatz aufsummiert.
- Der Sender berührt nur den vorderen, `draw()` nur den hinteren Puffer. Ein Frame wird gegebenenfalls doppelt gesendet oder übersprungen — für die Anzeige belanglos, der Takt bleibt konstant.
- `frameRate(40)` bleibt gesetzt, betrifft aber nur noch Simulation und Preview. Der Ausgabetakt hängt nicht mehr daran.

### 3.7 Zuschnitt

`ArtNetOutput` in `StripeHardwareHandler.java` ersetzt `ArtNetSender`. Bauen und Senden sind getrennt:

- `buildFrame(LedColor[]) -> Packet[]` ist rein und deterministisch, ohne Netz. Genau dieser Teil kann falsch sein und ist damit ohne Hardware prüfbar.
- `send(Packet[])` macht nur Ein- und Ausgabe.
- `describeMapping() -> String` gibt die Zuordnungstabelle aus, die beim Start auf der Konsole erscheint.

### 3.8 Voraussetzung am Controller

Im Web-Interface muss „Number of LED/Output" auf 600 stehen, sonst weicht die Offset-Rechnung der Firmware von unserer ab. Die Anzahl Outputs muss ≥ 2 sein. Beides ist am 2026-07-29 an allen 15 Controllern bestätigt.

## 4. Teil 2 — Node-Kalibrierung

### 4.1 Befund am bestehenden Code

Die Aussage, dass Undo fehlt, trifft zu: `manualNodeCrossings` (`LedStripeFullActivationEffect.java:22`) wird ausschliesslich befüllt (`saveCurrentNodeCrossing`, :294), ein Entfernen gibt es nirgends. Drei weitere Fehlerquellen im selben Ablauf:

- `saveNodeCrossingsToFile` (:264) öffnet die Datei im Append-Modus und schreibt jedes Mal die vollständige Liste. Zweimal `s` je Sitzung verdoppelt alle Einträge.
- Ohne Validierung wird ein Cursor im Ausgangszustand `-1` als `stripeIndex*720 - 1` gespeichert. Auf Stripe 0 ergibt das Index `-1`, worauf `loadListOfNodes` (`LedStripeNetworks.java:92`) beim nächsten Start eine `IndexOutOfBoundsException` wirft und der Sketch nicht mehr startet.
- Gespeicherte Nodes wirken erst nach Neustart. Der Effekt ist zudem in `imPulse.pde:118` gar nicht im Mixer registriert.

### 4.2 Zwei Cursor statt sieben Modi

Die sieben Einträge in `StripeChangeMode` beschreiben in Wahrheit zwei Cursor, die abwechselnd bewegt werden; auch die beiden Sonderfälle für Kreuzungen eines Stripes mit sich selbst sind nur „beide Cursor auf demselben Stripe". Das Dropdown entfällt ersatzlos.

`NodeCalibration` ersetzt `LedStripeFullActivationEffect`.

```
Stripe A  [ 3]  LED 412   <- aktiv
Stripe B  [ 7]  LED 158
Nodes: 12 geladen + 3 neu     Schritt: 10
```

| Taste | Wirkung |
|---|---|
| ←/→ | aktiven Cursor um die Schrittweite verschieben |
| ↑/↓ | Stripe des aktiven Cursors wechseln |
| TAB | zwischen Cursor A und B umschalten |
| ENTER | Paar prüfen und übernehmen, danach zurück auf Cursor A |
| BACKSPACE | letztes in dieser Sitzung gesetztes Paar zurücknehmen |
| F | Schrittweite 1 / 10 / 100 |
| S | `data/nodeCrossings.txt` schreiben |
| R | Nodes aus der Liste neu übernehmen, ohne Neustart |
| N | gesetzte Nodes ein-/ausblenden |
| C | Kalibriermodus ein- und ausschalten |

Tastenwiederholung wie bisher: `keyPressed` merkt die Richtung, `draw()` wendet sie alle 30 ms an, `keyReleased` beendet sie.

### 4.3 Darstellung

Beide Cursor-Stripes werden auf ganzer Länge schwach beleuchtet, damit erkennbar ist, welcher Stripe gemeint ist; darauf sitzt je ein heller Punkt, Cursor A grün, Cursor B rot. Mit `N` zusätzlich: geladene Nodes magenta, in dieser Sitzung neu gesetzte cyan.

### 4.4 Zustand und Validierung

Eine einzige Liste `allCrossings`, beim Start aus der Datei gefüllt. `ENTER` hängt an, `BACKSPACE` entfernt — aber nur Einträge ab `initialCount`, damit eine bestehende Kalibrierung nicht versehentlich abgeräumt wird.

`ENTER` weist zurück und meldet den Grund im Fenster, wenn:

- beide Cursor auf derselben LED stehen,
- beide auf demselben Stripe stehen und weniger als 3 LEDs auseinanderliegen,
- das Paar bereits in der Liste steht.

Der globale Index ist `stripe * numLedsPerStripe + led`. Cursor starten bei 0 und werden geklemmt, wodurch der `-1`-Fall entfällt.

### 4.5 Datei und Übernahme

Format unverändert: eine Zeile je Node, darin leerzeichengetrennte globale LED-Indizes.

`S` schreibt die vollständige Liste in eine temporäre Datei und benennt sie anschliessend über `nodeCrossings.txt`. Das erledigt sowohl das Anhängen als auch einen halb geschriebenen Stand bei Absturz.

`R` baut die Node-Struktur neu auf. Dazu bekommt `LedInNetInfo` eine Methode `applyCrossings(List<TreeSet<Integer>>, LedInNetInfo[], ArrayList<LedNetworkNode>)`, die `partOfNode` überall zurücksetzt, die Zielliste leert und neu füllt. Sie wird beim Start und bei `R` benutzt, womit die Doppelung zu `loadListOfNodes` verschwindet. Die Änderung erfolgt an derselben `ArrayList`-Instanz, die Transport- und Node-Effekt halten, sodass beide ohne Umweg mitbekommen.

`loadListOfNodes` prüft künftig jeden gelesenen Index gegen `0 <= idx < nLeds` und überspringt fehlerhafte Zeilen mit einer Meldung, statt beim Start abzustürzen.

### 4.6 Verzahnung mit dem Betrieb

`calibrationMode` in `imPulse.pde`, mit `C` umgeschaltet. Ist er an, geht der Puffer von `NodeCalibration` unter Umgehung des Mixers direkt an den Ausgang; die Kalibriertasten sind nur dann wirksam. Damit stören sich Kalibrierung und Show nicht, und es braucht keine Opacity-Umschalterei.

## 5. Berührte Dateien

| Datei | Änderung |
|---|---|
| `imPulse.pde` | Controller-Liste und Geometrie, `calibrationMode`, Tastenführung, `draw()` gibt an `publish()` ab |
| `StripeHardwareHandler.java` | `ArtNetOutput` ersetzt `ArtNetSender`; `StripeConfigurator` bleibt |
| `LedStripeNetworks.java` | `applyCrossings`, Index-Prüfung in `loadListOfNodes` |
| `LedStripeFullActivationEffect.java` | entfällt zugunsten von `NodeCalibration.java` |
| `data/nodeCrossings.txt` | wird durch die neue Geometrie ungültig, Neuaufnahme vor Ort |
| `test/` | Prüfprogramme zu Test 1, ausserhalb des Sketch-Ordners |

Das Prüfprogramm liegt bewusst nicht im Sketch-Ordner, weil Processing dort jede `.java` mitkompilieren würde. Übersetzt wird gegen `/Applications/Processing.app/Contents/Java/core.jar`.

## 6. Testplan

### Test 1 — ohne fremde Hilfe prüfbar

**1a Byte-genauer Vergleich.** Jede LED bekommt ein aus ihrem globalen Index abgeleitetes Muster. `buildFrame` läuft, und für jedes der 315 Pakete wird geprüft: Kopf korrekt, `SubUni`/`Net` ergeben die erwartete Port-Adresse, Ziel-IP passt zum Controller, jedes LED-Tripel steht am richtigen Offset, das vierte Byte ist 0, die 40 Reserve-Slots je Output sind genullt, und je Controller folgt genau ein Sync-Paket.

**1b Rückwärtsprüfung.** Ein Decoder setzt aus den 315 Paketen den LED-Puffer wieder zusammen und vergleicht ihn mit dem Original. Er benutzt nicht dieselbe Formel wie der Sender und findet damit Lücken und Überlappungen, die 1a durchgehen lassen könnte.

**1c Taktmessung.** Der Sender-Thread stempelt seine Sendezeitpunkte. Über 60 Sekunden werden Mittelwert, Minimum, Maximum und Standardabweichung des Intervalls ausgegeben. Damit ist die geforderte konstante Rate gemessen.

**1d Bestätigung am Empfangsende.** Während der Sender läuft, geht sekündlich ein ArtPoll an jeden Controller; aus dem ArtPollReply werden `fps` und `uUniPF` gelesen. Erwartet werden rund 40 und 10,0. Ein `uUniPF` unter 10 zeigt Paketverluste — die Grösse, vor der das README ab etwa 18 Universen warnt.

### Test 2 — braucht deine Augen

Alle Muster laufen mit `masterLevel = 0.1`. Reihenfolge ist bindend: geht Muster 1 schief, sind die übrigen ohne Aussage.

| # | Muster | Beantwortet |
|---|---|---|
| 1 | Stripes nacheinander weiss, Nummer im Fenster | Stimmt die Zuordnung Stripe → Controller/Output und deren Reihenfolge? |
| 2 | Lauflicht LED 0 → 599 auf einem Stripe | Richtung korrekt, keine Lücke an den Universumsgrenzen 128 / 256 / 384 / 512 |
| 3 | Nur LED 596–599 an | Leuchtet dabei etwas am Anfang des nächsten Outputs, schlagen die 40 Reserve-Slots durch |
| 4 | Flächig Rot, dann Grün, dann Blau | Kanalreihenfolge — hier entscheidet sich R,G,B gegen B,G,R |
| 5 | Flächig weiss, eine Minute | Flackern, Aussetzer, Blackouts unter Dauerlast bei 40 fps |

## 7. Offene Risiken

- **Kanalreihenfolge.** Betrieb und Firmware-Lesart widersprechen sich. Klärt Test 2, Muster 4; die Korrektur ist eine Zeile.
- **Durchsatz.** 12.600 Pakete/s sind gemessen unbestätigt. Klären Test 1c und 1d gemeinsam: 1c zeigt, ob der Sender den Takt hält, 1d, ob alles ankommt.
- **Sync je Controller.** Ob 15 einzeln adressierte Sync-Pakete sauberer laufen als ein Broadcast auf `2.255.255.255`, ist offen. Beide Wege sind vorgesehen, die Entscheidung fällt über `uUniPF` aus Test 1d.
- **Stromaufnahme.** `masterLevel` begrenzt zwar den Ausgang, ersetzt aber keine Messung an der Einspeisung. Muster 5 ist auch dafür da.
