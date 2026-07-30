# imPulse — LED-Positionen und Vierkanal-Spatialisierung

Branch: `grabicz26` · Datum: 2026-07-30

## 1. Ziel

Der Klang der Installation soll auf vier Lautsprecher spatialisiert werden. Dafür braucht jede LED eine ungefähre Position in der Draufsicht, und jedes klangauslösende Ereignis muss seine Koordinaten über OSC mitschicken.

Drei Teile:

1. **Positionen erfassen und ableiten.** Von Hand gesetzte Ankerpunkte, dazwischen interpoliert. Ein prüfbarer Java-Kern plus ein Erfassungswerkzeug mit Maus im Sketch-Fenster.
2. **Koordinaten über OSC senden.** `/net/hitNode` wird um x und y erweitert, neu dazu ein gedrosselter Positionsstrom `/net/impulse` für die reisenden Impulse.
3. **Vierkanal-Klang.** `supercollider/klangnetz_bells.scd` ersetzt `Pan2` mit Zufalls-Pan durch eine objektbasierte Ambisonic-Kette erster Ordnung, 2D.

Die auskommentierten Zeilen in `LedNetworkTransportEffect.java:310-312` (`myMessage.add(hitNode.position.x/y/z)`) zeigen, dass die Idee beim Bau schon angelegt war. `LedNetworkNode` hat bis heute kein Positionsfeld; dieser Entwurf füllt die Lücke — ohne `z`, siehe 2.2.

### 1.1 Umfang und Reihenfolge der Umsetzung

Das ist mehr, als in einen Umsetzungsschritt gehört. Die drei Teile hängen aber über ein gemeinsames Dateiformat und dieselbe Map zusammen und werden deshalb in **einer** Spezifikation beschrieben statt in drei. Für die Umsetzung sind sie in dieser Reihenfolge zu staffeln, jede Stufe für sich abgeschlossen und lauffähig:

| Stufe | Inhalt | Danach lauffähig |
|---|---|---|
| 1 | Positionskern und Tests (Abschnitt 3, 9) | Datei kann gelesen und geschrieben werden, Positionen aller LEDs stehen; noch kein Werkzeug, noch kein Klang |
| 2 | Erfassungswerkzeug (Abschnitt 4) | Positionen lassen sich vor Ort aufnehmen und in `data/ledPositions.txt` schreiben |
| 3 | OSC und Vierkanal-Klang (Abschnitt 5, 6) | Spatialisierung hörbar |

Stufe 3 hängt nur an Stufe 1, nicht an Stufe 2 — sie lässt sich mit einer von Hand geschriebenen Positionsdatei prüfen, bevor das Werkzeug fertig ist.

## 2. Befund und Vorgaben des Aufbaus

Am 2026-07-30 mit dem Betreiber der Installation festgelegt, nicht angenommen.

| Grösse | Wert |
|---|---|
| Aufbau | Netz über Kopf, Publikum darunter |
| Grundfläche in der Draufsicht | 14 m (X) × 8 m (Y) |
| Ursprung | senkrecht unter der Netzmitte, X nach rechts, Y nach vorn |
| Lautsprecher | vier, auf den **Seitenmitten**: (0, +4), (+7, 0), (0, −4), (−7, 0) |
| Stripes | 30, je 600 LEDs auf 10 m → LED-Abstand 16,667 mm |
| Stripe-Aufbau | die zwei 5-m-Stücke eines Outputs sind **durchgehend** verbunden, LED 299 und 300 liegen physisch nebeneinander |
| Kreuzungen | **wächst laufend**, siehe 2.3 |

### 2.1 Warum Anker und nicht nur Knoten

Interpoliert werden kann nur zwischen zwei bekannten Punkten **auf demselben Stripe**. Solange die Kalibrierung nicht abgeschlossen ist, hat ein Teil der Stripes keine oder nur eine Kreuzung, und vor der ersten sowie hinter der letzten Kreuzung eines Stripes fehlt ohnehin die zweite Stützstelle. Beim Stand von 02:18 (23 Kreuzungen) hatten 30 Stripes im Mittel weniger als zwei Kreuzungen; auch beim Stand von 03:40 (77 Kreuzungen) hat der schwächste Stripe nur zwei.

Deshalb ist die Ankermenge: **alle Kreuzungs-LEDs plus LED 0 und LED 599 jedes Stripes.**

Eine Kreuzung ist ein einzelner physischer Punkt mit zwei LEDs auf zwei verschiedenen Stripes. Eine gesetzte Position gilt damit für **alle** LEDs dieses Knotens — das ist keine Schätzung, sondern eine Tatsache der Geometrie. Jeder gesetzte Knoten liefert also sofort eine Stützstelle auf einem zweiten Stripe.

Wären die zwei 5-m-Stücke getrennt im Netz verlegt, lägen zwischen LED 299 und 300 ein physischer Sprung und es bräuchte Segmentgrenzen sowie vier Anker je Stripe. Nach 2 ist das nicht der Fall; Segmentgrenzen werden **nicht** gebaut.

### 2.3 Alle abgeleiteten Zahlen werden gerechnet, nicht verdrahtet

`data/nodeCrossings.txt` **wächst während der Kalibrierung**. Am 2026-07-30 um 02:18 standen 23 Kreuzungen darin, um 03:40 waren es 77. Keine Zahl, die von der Kreuzungszahl abhängt, darf im Code oder in einem Test als Konstante stehen — weder die Länge der Arbeitsliste noch die Zahl der Anker.

Es gilt, mit `C` als Zahl der Kreuzungen und `S = 30` Stripes:

```
LED-Anker gesamt          = 2*S + 2*C          (jede Kreuzung hat genau zwei LEDs)
davon von Hand zu klicken =   2*S + C          (die Partner-LED schliesst sich mit)
Arbeitslisten-Eintraege   = 2*S + 2*C          (einer je LED-Anker, siehe 4.1)
```

Bei C = 77 sind das 214 Anker, 137 Klicks. Bei C = 23 waren es 106 Anker und 83 Klicks. Beide Zahlen stehen hier nur als Beispiel; verlässlich ist die Formel.

Für die Tests heisst das: **kleine synthetische Vorgaben**, keine Prüfung gegen `data/nodeCrossings.txt`. Ein Test, der 214 erwartet, ist beim nächsten `S` im Kalibriermodus rot.

Geprüft: alle Zeilen der Datei haben genau **zwei** Indizes, und keine Kreuzungs-LED liegt auf einem Stripe-Ende (LED 0 oder 599). Der Kern muss mit beidem trotzdem umgehen — mehr als zwei Indizes je Zeile erlaubt `NodeCrossingStore.load()` ausdrücklich, und fällt eine Kreuzung auf ein Stripe-Ende, darf der Eintrag in der Arbeitsliste nur **einmal** vorkommen.

### 2.2 Was verworfen wird

- **Höhe / Z.** Bei einem Netz über Kopf trägt die Höhe zum Vierkanal-Klang nichts bei; vier Lautsprecher in einer Ebene können sie nicht darstellen. Alle Positionen sind 2D. Das ist der Grund, weshalb 2D erster Ordnung in Teil 3 die passende Ambisonic-Ordnung ist.
- **Kamera-gestützte Erfassung.** Ausdrücklich als nächster Schritt festgehalten, siehe 7.
- **Globale Ausgleichsrechnung über alle Anker.** Siehe 7.

## 3. Teil 1 — Positionskern

Zwei neue Klassen ohne Processing- und Netzabhängigkeit, nach dem Vorbild von `NodeCrossingStore`, `NodeSelection` und `ArtNetOutput`: die Logik ist damit vollständig über `test/run.sh` prüfbar.

### 3.1 Konfiguration

Neue Felder in `imPulse.pde`, bei den übrigen installationsspezifischen Konstanten:

```java
float footprintX       = 14f;   // Draufsicht-Ausdehnung in Metern, X nach rechts
float footprintY       =  8f;   // Y nach vorn
float stripeLengthM    = 10f;   // physische Laenge eines Stripes (2 x 5 m, durchgehend)
```

Abgeleitet, nicht verdrahtet:

```
ledPitchM   = stripeLengthM / numLedsPerStripe          = 0.016667 m
maxRadiusM  = sqrt((footprintX/2)^2 + (footprintY/2)^2) = 8.062 m
```

### 3.2 `LedAnchorStore.java`

Hält die von Hand gesetzten Punkte: globaler LED-Index → (x, y) in Metern.

```java
class LedAnchorStore {
  LedAnchorStore(int numStripes, int numLedsPerStripe,
                 float footprintX, float footprintY, float ledPitchM);

  // Die Kreuzungsliste wird bei jedem set() hereingegeben, damit der Store
  // ohne eigene Kenntnis der Topologie auskommt und Aenderungen an
  // nodeCrossings.txt zur Laufzeit mitbekommt.
  boolean set(int ledIndex, float x, float y, List<TreeSet<Integer>> crossings);
  boolean remove(int ledIndex);
  void    clearAll();

  boolean has(int ledIndex);
  float   x(int ledIndex);          // nur gueltig wenn has()
  float   y(int ledIndex);
  SortedSet<Integer> anchorsOnStripe(int stripeIndex);   // aufsteigende LED-Indizes

  int     size();                   // Anzahl gesetzter LED-Anker
  int     loadedCount();
  int     sessionCount();
  String  lastMessage();

  void    load(String path);
  void    save(String path) throws IOException;
}
```

**Knoten-Verteilung.** Liegt `ledIndex` in einer Kreuzung, setzt `set()` die Position für **alle** LEDs dieser Kreuzung, nicht nur für die angegebene. Die Meldung nennt die Zahl der mitgesetzten LEDs.

**Validierung.** Zwei Stufen, mit unterschiedlicher Härte:

| Prüfung | Verhalten |
|---|---|
| `ledIndex` ausserhalb 0..numLeds−1 | **abgelehnt** |
| x ausserhalb ±footprintX/2, y ausserhalb ±footprintY/2 | **abgelehnt** |
| Luftlinie zu einem Nachbaranker auf demselben Stripe grösser als die Weglänge entlang des Stripes plus 0,5 m | **Warnung**, Position wird gesetzt |

Die Weglänge zwischen zwei Ankern derselben Stripes ist `|i − j| · ledPitchM`. Die Luftlinie kann physikalisch nie länger sein. Geprüft wird nur gegen die zwei unmittelbaren Nachbaranker, nicht gegen alle.

Diese Prüfung **lehnt nicht ab**, anders als die Kreuzungsvalidierung in `NodeCrossingStore`. Grund: sie hängt an zwei Annahmen — 16,667 mm LED-Abstand und ein durchgehender Strang. Stimmt eine davon vor Ort nicht, wäre ein hartes Nein ein Werkzeug, das sich mitten in der Arbeit selbst blockiert. Die Warnung erscheint im HUD **und** auf der Konsole, wie es die abgelehnte Kreuzung heute auch tut.

Der Schwellwert ist absolut (0,5 m), nicht prozentual: er soll den Fehlklick auf die falsche Netzseite fangen, und ein Prozentwert wäre bei kurzen Abständen unbrauchbar streng.

**Datei `data/ledPositions.txt`.** Eine Zeile pro LED-Anker, `#` beginnt einen Kommentar. Geschrieben wird die vollständige Liste in eine Nebendatei, die anschliessend umbenannt wird — kein Anhängen, damit mehrfaches Speichern nichts verdoppelt und ein Absturz keinen halben Stand hinterlässt. Wie `NodeCrossingStore.save()`.

```
# ledIndex  x[m]  y[m]  --  Grundflaeche 14 x 8 m, Ursprung Netzmitte,
#                          X nach rechts, Y nach vorn
1834  -3.250   1.100
7412   2.900  -0.450
```

Fehlerhafte Zeilen werden auf der Konsole gemeldet und übersprungen, nicht als Absturz beim nächsten Start weitergereicht — dasselbe Verhalten wie `NodeCrossingStore.load()`.

Der Schlüssel ist bewusst der **globale LED-Index**, nicht die Knoten-Nummer. Damit bleiben alle Positionen gültig, wenn `nodeCrossings.txt` sich ändert: eine physische LED wandert nicht, wenn eine Kreuzung nachgetragen oder korrigiert wird. Bei Knoten-Nummern würde `removeAt()` alle folgenden Positionen verschieben. Ein neu aufgenommener Knoten erscheint einfach als noch offener Anker in der Arbeitsliste.

`loadedCount` verschiebt sich bei `remove()` eines geladenen Eintrags mit — dieselbe Falle wie in `NodeCrossingStore.removeAt()`, sonst zählte ein Sitzungseintrag als geladen.

### 3.3 `LedPositionMap.java`

Rechnet aus den Ankern die Position jeder der 18 000 LEDs.

```java
class LedPositionMap {
  LedPositionMap(int numStripes, int numLedsPerStripe);

  // Position einer einzelnen LED, direkt aus dem Store gerechnet.
  // Fuer einen gesetzten Anker ist das der Anker selbst, fuer eine
  // ungesetzte LED das Interpolations- bzw. Extrapolationsergebnis.
  boolean positionOf(LedAnchorStore store, int ledIndex, float[] out2);

  // Einmalig alle Positionen in zwei float[numLeds] schreiben, fuer den
  // heissen Pfad im Transport-Effekt.
  void apply(LedAnchorStore store);
  float x(int ledIndex);
  float y(int ledIndex);
  boolean isDefined(int ledIndex);
  boolean isInterpolated(int ledIndex);   // false = nur extrapoliert

  int undefinedCount();
  int extrapolatedCount();
  String coverageReport(int numStripes);  // welche Stripes ohne Anker
}
```

**Regeln je Stripe**, angewandt auf die aufsteigend sortierten Anker dieses Stripes:

| Lage der LED | Ergebnis |
|---|---|
| genau auf einem Anker | der Anker |
| zwischen zwei Ankern | linear über den Index-Abstand, `isInterpolated = true` |
| vor dem ersten / hinter dem letzten Anker | Fortsetzung des Vektors der zwei dem jeweiligen Rand nächstliegenden Anker, `isInterpolated = false` |
| nur ein Anker auf dem Stripe | dieser Punkt für alle LEDs des Stripes, `isInterpolated = false` |
| kein Anker auf dem Stripe | undefiniert, wird gezählt |

Die Extrapolation ist: Richtung `d = (p_b − p_a) / (i_b − i_a)` aus den zwei Ankern am betreffenden Rand — hinter dem Ende also aus den letzten zwei, vor dem Anfang aus den ersten zwei —, Ergebnis `p_b + d · (i − i_b)`. Bei fünf Ankern zählen für die Extrapolation hinter dem letzten nur Anker vier und fünf, nicht eins und fünf.

**Der Vorschlag *ist* das Ergebnis der Map.** `positionOf()` liefert für einen noch nicht gesetzten Anker genau die Vektor-Fortsetzung, die das Erfassungswerkzeug als Vorschlag anzeigt. Es gibt keine zweite Rechnung und keine zweite Wahrheit; die Tests der Interpolation decken die Vorschlagsqualität mit ab.

`apply()` schreibt die Ergebnisse einmalig in zwei `float[numLeds]`, damit der Transport-Effekt in O(1) zugreift statt pro Impuls und Frame zu suchen — dasselbe Muster wie `LedInNetInfo.partOfNode`.

### 3.4 Knotenpositionen

`LedNetworkNode` in `LedStripeNetworks.java` bekommt zwei Felder:

```java
public float posX, posY;    // Draufsicht-Position in Metern, gesetzt von applyPositions
```

Neu daneben:

```java
public static void applyPositions(LedPositionMap map, ArrayList<LedNetworkNode> nodes);
```

Setzt für jeden Knoten den **Mittelwert** der Positionen seiner LEDs. Bei korrekt gesetztem Anker sind sie identisch; ist der Anker noch offen, weichen die interpolierten Werte der beteiligten Stripes leicht voneinander ab und der Mittelwert ist die ehrlichere Angabe als der erste Eintrag.

Aufgerufen aus `setup()` und aus beiden Kalibrierwerkzeugen bei `R`, jeweils direkt nach `applyCrossings` bzw. `apply`.

## 4. Teil 1b — Erfassungswerkzeug

Ein eigener Modus, mit `p`/`P` an und aus, **gegenseitig ausschliessend** mit dem Kalibriermodus: `p` schaltet `calibrationMode` aus, `c` schaltet `positionMode` aus. Ohne das kollidieren die Tastenbelegungen, die sich beide auf `,` `.` `S` `R` `F` `L` stützen.

Neue Klasse `LedPositionCalibration.java`, `implements runnableLedEffect`, **nicht** über `mixer.addEffect(...)` registriert — `draw()` ruft `drawMe()` direkt auf und ersetzt damit die Mixer-Ausgabe für diesen Frame, genau wie `NodeCalibration` es tut.

Arbeitsteilung wie beim bestehenden HUD: die Klasse hält Zustand und Logik processing-frei (Arbeitsliste, aktueller Eintrag, Schrittweite, Umrechnung Fläche ↔ Meter, `hudText()`) und liefert über `drawMe()` den LED-Puffer. Das Zeichnen der Draufsicht-Fläche macht `imPulse.pde`, so wie es heute den HUD-Text zeichnet.

### 4.1 Arbeitsliste

**Ein Eintrag je LED-Anker**, sortiert nach Stripe und darin nach Index in Stripe: pro Stripe LED 0, dann die Kreuzungs-LEDs dieses Stripes aufsteigend, dann LED 599. Fällt eine Kreuzung genau auf LED 0 oder 599, steht sie nur **einmal** in der Liste.

Eine Kreuzung erscheint damit **zweimal** in der Liste — einmal auf jedem beteiligten Stripe. Das ist Absicht: arbeitest du Stripe 7 ab, willst du alle seine Anker in Reihenfolge sehen, auch die schon bekannten, weil sich daran die Form des Strangs prüfen lässt. Nur einer der beiden Einträge braucht einen Klick; beim zweiten meldet das HUD „bereits bekannt" und man blättert weiter.

Die Länge ist `2*numStripes + Summe der LEDs aller Kreuzungen`, siehe 2.3 — sie wird gerechnet, nie verdrahtet, und die Liste wird bei `R` neu gebaut, damit während der Sitzung aufgenommene Kreuzungen auftauchen.

Die Sortierung nach Stripe ist ebenfalls Absicht: sobald zwei Punkte eines Stripes stehen, sind alle weiteren vorgeschlagen.

```java
class LedPositionCalibration implements runnableLedEffect {
  int    entryCount();
  int    entryIndex();                    // aktueller Eintrag, 0-basiert
  int    ledIndexOfEntry(int i);
  int    openCount();                     // noch nicht gesetzte Eintraege
  boolean currentIsSet();
  void   proposalOf(float[] out2);        // = LedPositionMap.positionOf des Eintrags
  ...
}
```

### 4.2 Bedienung

| Taste | Wirkung |
|---|---|
| `p` / `P` | Modus an und aus |
| `,` / `.` | vorheriger / nächster Eintrag der Arbeitsliste |
| `o` | zum nächsten noch **offenen** Eintrag springen |
| Mausklick / Ziehen in der Fläche | Position setzen |
| ENTER | den angezeigten **Vorschlag** unverändert als Anker übernehmen |
| Pfeiltasten | Position feinjustieren |
| `F` | Schrittweite der Pfeiltasten durchschalten: 1 cm / 5 cm / 25 cm |
| BACKSPACE | Anker dieses Eintrags löschen; die Anzeige fällt auf den Vorschlag zurück |
| `S` | `data/ledPositions.txt` schreiben |
| `T` | Abdeckungsbericht auf die Konsole: Stripes ohne Anker, Zahl der nur extrapolierten LEDs |
| `R` | `apply` und `applyPositions` neu rechnen und in den Transport-Effekt übernehmen, ohne Neustart |
| `L` `L` | alles verwerfen, auch die geladenen Anker |

`.` bleibt am Listenende stehen, kein Umlauf. `L` verlangt dieselbe ausdrückliche Bestätigung wie in `NodeCalibration`: erster Druck kündigt die Anzahl an, ein zweiter Druck zwischen 300 ms und 5 s danach führt aus, jede andere Taste verwirft die Ankündigung. Die Untergrenze wehrt Tastenwiederholung bei gehaltenem `L` ab.

Anders als bei `NodeCalibration` gibt es kein Gegenstück zu `X`: das Löschen betrifft hier immer den *aktuellen Eintrag der Arbeitsliste*, und der ist über `,`/`.` frei wählbar. BACKSPACE und X fallen damit zusammen.

Mausklicks **ausserhalb** der Fläche werden verworfen, damit ein Klick ins HUD keine Position setzt.

### 4.3 Rückmeldung im Netz

`drawMe()` färbt den Puffer:

In dieser Reihenfolge, spätere Regeln überschreiben frühere:

1. **Alle Stripes ausser dem aktuellen** zeigen den Zustand der Map: schwach **blau**, wo `isInterpolated` gilt, schwach **rot**, wo die Position nur durch Fortsetzung des Vektors geraten ist, **dunkel**, wo sie undefiniert ist.
2. **Der Stripe des aktuellen Eintrags** glimmt über die ganze Länge schwach **grün**, damit er im Netz auffindbar ist — die Map-Farben treten hier zurück, sonst wäre er nicht von den anderen zu unterscheiden.
3. **Die LED des aktuellen Eintrags blinkt weiss**, 400 ms an, 400 ms aus, wie die Auswahl in `NodeCalibration`.

Damit ist am Netz selbst zu sehen, wo noch Arbeit liegt: rot verschwindet, sobald beide Enden eines Stripes gesetzt sind. Wo die Abdeckung des aktuellen Stripes stehen bleibt, sagt der Bericht auf `T`.

Alle Helligkeiten laufen wie alles andere über den Master-Pegel des Senders (`0.06` als Grundwert für das Glimmen, wie `NodeCalibration.DIM`).

### 4.4 Anzeige im Fenster

Das Fenster bleibt bei `size(1400, 450, P3D)`. Im Positionsmodus wird der Bereich oberhalb des HUD (y = 0 bis 300) neu belegt:

- **Draufsicht-Fläche links**, Rechteck (0, 0, 525, 300). 525 × 300 px für 14 × 8 m sind 2,67 cm je Pixel in beiden Achsen — das Verhältnis 525:300 entspricht 14:8 genau, es gibt keine Verzerrung. Inhalt: Raster mit 1-m-Linien, Rahmen, die vier Lautsprecher als Marken auf den Seitenmitten, alle gesetzten Anker als kleine Punkte, der aktuelle Eintrag als Ring — **gefüllt** wenn gesetzt, **hohl** wenn nur Vorschlag. Dazu der interpolierte Verlauf des aktuellen Stripes als Polylinie: daran ist auf einen Blick zu erkennen, ob der Strang plausibel durchs Netz läuft oder quer durch die Mitte springt.
- Liegt eine Datei `data/topView.png` im Datenordner, wird sie unter das Raster gelegt. Fehlt sie, bleibt es beim Raster — die Datei ist optional und nicht Teil der Lieferung.
- **LED-Vorschau rechts**, `image(canvas, 560, 0, 600, 120)`, verkleinert. Zeigt, welcher Stripe gerade glimmt.
- **Fortschritt** darunter ab y = 140: Zahl der gesetzten und offenen Einträge, aktueller Eintrag mit Stripe und LED-Index, ob er zu einer Kreuzung gehört.
- **HUD** wie gehabt ab y = 300, mit Cursorstand in Metern, Schrittweite, letzter Meldung und Tastenbelegung.

Die Umrechnung Fläche ↔ Meter liegt als `paneToWorld` / `worldToPane` in der processing-freien Klasse, damit Zeichnen und Klickauswertung dieselbe Rechnung benutzen und beide Richtungen prüfbar sind.

## 5. Teil 2 — OSC

Beides ausgehend an Port 8002, unverändertes Ziel `oscOutput` in `imPulse.pde`.

```
/net/hitNode  <nodeId:int>    <energy:float>  <x:float>  <y:float>
/net/impulse  <impulseId:int> <x:float>       <y:float>  <energy:float>
```

### 5.1 `/net/hitNode`

Füllt die drei auskommentierten Zeilen in `LedNetworkTransportEffect.java:310-312`, abzüglich `z`. x und y kommen aus `hitNode.posX/posY`, also dem Mittelwert über die LEDs des Knotens.

Die Erweiterung ist **rückwärtskompatibel**: der heutige `OSCdef` in `klangnetz_bells.scd` liest `msg[1]` und `msg[2]` und ignoriert weitere Argumente. Ein alter Klang-Sketch läuft unverändert weiter.

### 5.2 `/net/impulse`

Der gedrosselte Positionsstrom der reisenden Impulse.

`TravellingActivation` bekommt ein `final int id` aus einem Zähler der Effekt-Instanz. `TravellingActivationFiller` erbt die ID des Elternimpulses — Filler sind Zeichenhilfen und werden im selben Frame wieder entfernt, tauchen also im Strom nicht auf.

Kinder an einem Knoten bekommen **neue** IDs. Der Elternimpuls stirbt dort tatsächlich (`activationEncounteredNode` gibt `true` zurück und der Aufrufer trägt ihn nicht wieder ein), also hört die Klangseite einen Klang enden und mehrere neue beginnen. Das entspricht dem Vorgang.

Zwei neue Parameter, die von selbst in `remoteSettings.txt` auftauchen:

```java
new RemoteControlledFloatParameter("/net/impulse/oscRate",     10f, 0f, 40f);
new RemoteControlledIntParameter  ("/net/impulse/oscMaxCount", 32,  0,  256);
```

- `oscRate` in Hz. **0 schaltet den Strom ab** — der Notausgang, wenn Netz oder Klangrechner während der Show nicht mitkommen.
- `oscMaxCount` begrenzt die Zahl gemeldeter Impulse je Takt. Bei mehr lebenden Impulsen gewinnen die **energiereichsten**.

Gesendet wird **am Ende von `drawMe()`**, nachdem die Zeichenschleife die Filler über `iter.remove()` entfernt hat. Zu diesem Zeitpunkt steht in `activations` genau ein Eintrag je echtem Impuls, und es braucht keine Sonderfallbehandlung für Filler. Der Takt wird aus der bereits vorhandenen Wanduhr-Zeitbasis gebildet, nicht aus dem Framecount — wie die ganze Simulation.

Positionen kommen aus `LedPositionMap.x/y(ledIndex)`, also aus den vorgerechneten Arrays.

**Kein Todes-Signal.** Der Strom ist durch `oscMaxCount` ohnehin lückenhaft: ein Impuls kann aus der Auswahl fallen, ohne zu sterben. Die Klangseite muss mit stillem Verschwinden umgehen können, und dann deckt der Timeout dort auch den echten Tod ab. Ein zusätzliches Signal wäre eine zweite Wahrheit über dieselbe Sache, die nichts absichert.

**`/net/impulseBorn` wird nicht gebaut.** Die Geburt eines Impulses am Rohr ist durch die erste Meldung des Stroms abgedeckt, und der Anschlag am Rohr selbst ist Max/MSP bekannt.

## 6. Teil 3 — Vierkanal-Klang in SuperCollider

`supercollider/klangnetz_bells.scd`. Ambisonics 2D erster Ordnung mit **Kern-UGens**, ohne Quark und ohne sc3-plugins: `PanB2` encodiert, `DecodeB2` decodiert. Erste Ordnung in der Ebene ist genau die Ordnung, die vier Lautsprecher hergeben, und die Höhe ist nach 2.2 verworfen — ATK oder sc-hoa wären Fremdabhängigkeiten für Fähigkeiten, die hier bewusst weggelassen sind.

### 6.1 Kette

Objektbasiert: jeder Klang ist ein Objekt mit Position, das in einen gemeinsamen B-Format-Bus encodiert; **ein** Decoder-Synth am Ende macht daraus die vier Lautsprecherkanäle.

```supercollider
s.options.numOutputBusChannels = 4;

~bformatBus = Bus.audio(s, 3);                    // 2D erster Ordnung: W X Y
~voices     = Group.new;                          // alle Klangobjekte
~decoderSyn = Synth.after(~voices, \b2Decoder);   // genau einmal
```

Die Reihenfolge muss über `Synth.after` erzwungen werden, sonst decodiert der Decoder einen Bus, in den die Stimmen im selben Block noch nicht geschrieben haben.

`numOutputBusChannels` wirkt erst beim Booten des Servers. Die Zuweisung gehört also **vor** `s.waitForBoot`, und läuft schon ein Server, muss er neu gestartet werden — sonst bleiben die Kanäle 2 und 3 stumm, ohne Fehlermeldung. Das passt zu den Betriebshinweisen, die am Kopf der Datei schon zum headless-Betrieb stehen.

`\b2Decoder` liest `In.ar(~bformatBus, 3)` und gibt `DecodeB2.ar(4, w, x, y, orientation)` auf die Hardware-Ausgänge 0 bis 3.

### 6.2 Position im Klangobjekt

Jedes Klangobjekt encodiert selbst:

```supercollider
// azimuth in Halbzyklen: 0 = vorn. Unser System hat X nach rechts und
// Y nach vorn, daher atan2(x, y) - nicht atan2(y, x).
azimuth   = atan2(xLag, yLag) / pi;
radius    = hypot(xLag, yLag);
direct    = (radius / ~maxRadiusM).clip(0, 1);    // 0 = Netzmitte

#w, x, y  = PanB2.ar(sig, azimuth, 1);
x = x * direct;                                   // Richtwirkung erster Ordnung
y = y * direct;                                   // ist das Verhaeltnis X,Y zu W
Out.ar(~bformatBus, [w, x, y]);
```

Die Multiplikation von X und Y bei unverändertem W ist die Diffusität: bei `direct = 0` bleibt nur der omnidirektionale Anteil und der Klang kommt aus allen vier Boxen, bei `direct = 1` ist er eine ebene Welle aus einer Richtung. Das ist die eigentliche Übersetzung von „Position" in „Raumklang" bei einem Netz über Kopf — ein Knoten senkrecht über der Mitte soll nicht aus einer Richtung kommen, sondern von überall.

`~maxRadiusM = 8.062` (halbe Diagonale von 14 × 8 m).

Bei den Drohnen liegt `Lag.kr` (0,1 s) auf x und y **vor** der Umrechnung, damit ein springender Positionswert nicht als Klick durchschlägt. Bei den Glocken ist die Position je Ton fest und die Rechnung läuft einmal beim Anlegen.

**Keine Abstandsdämpfung.** Ein Knoten am Netzrand ist weiter von der Mitte entfernt, soll aber nicht leiser sein; die Lautstärke kommt allein aus `energy`. Bewusste Entscheidung, keine Auslassung.

### 6.3 Die zwei SynthDefs

**`\glockenBell`** bleibt in seiner Klangfarbe unverändert (fünf leicht unharmonische Sinus-Teiltöne, kurzer Attack, langer Decay). Ersetzt werden `pan`, `Pan2` und `Out.ar(out, ...)` durch `x`, `y` und die Kette aus 6.2. Die Zufalls-Panoramisierung entfällt — sie war der Platzhalter für genau diese Positionen.

**`\impulseDrone`** ist neu: ein leiser gehaltener Ton für einen reisenden Impuls, Lautstärke nach `energy`, gehalten über ein `gate` mit 0,3 s Release. Die Tonhöhe wird aus der Impuls-ID auf die bestehende pentatonische Leiter abgebildet, damit die Drohnen zu den Glocken passen. Die Klangfarbe ist ein schlichter Startpunkt und ausdrücklich zum Ersetzen gedacht — die Mechanik drumherum ist der Teil, der stimmen muss.

### 6.4 Verwaltung der Drohnen

sclang hält `~drones` als `IdentityDictionary`: Impuls-ID → `(synth: …, lastSeen: …)`.

- `/net/impulse` mit unbekannter ID → neuen Synth in `~voices` anlegen.
- Mit bekannter ID → `.set(\x, x, \y, y, \amp, …)` und `lastSeen` erneuern.
- Eine `Routine` prüft alle **0,1 s** und gibt jeden Synth frei, dessen `lastSeen` länger als **0,4 s** zurückliegt — bei 10 Hz Melderate also nach vier ausgefallenen Meldungen.

**Dieser Timeout ist der einzige Freigabemechanismus**, siehe 5.2.

Zusätzlich eine Obergrenze von 32 gleichzeitigen Drohnen in sclang als Netz unter dem `oscMaxCount` der Processing-Seite: kommt der Strom aus einer falsch konfigurierten Quelle, soll der Klangrechner nicht in die Knie gehen.

### 6.5 Zwei Werte, die gemessen werden müssen

Nicht aus der Dokumentation abgeleitet, sondern vor Ort mit einem Testton je Kanal zu prüfen und das Ergebnis als Kommentar in die Datei zu schreiben:

1. **Die Kanalreihenfolge von `DecodeB2`** und der Wert des `orientation`-Arguments für „ein Lautsprecher steht genau vorn". Daran hängt die Verkabelung der vier Boxen.
2. **Das Vorzeichen und der Nullpunkt des `azimuth`-Arguments von `PanB2`** — ob positiv nach rechts oder nach links zählt.

Das folgt demselben Grundsatz, unter dem in `CLAUDE.md` die ArtNet-Bytefolge steht: *„Massgeblich ist die Messung, nicht die Herleitung."* Dort haben zwei unabhängige Ableitungen aus der Firmware zu einem falschen Ergebnis geführt, und erst die Messung am Aufbau hat es geklärt.

Praktisches Vorgehen: `\b2Decoder` bekommt vorübergehend einen Testmodus, der ein Rauschen nacheinander auf die Kanäle 0 bis 3 legt und die Nummer auf der Konsole ausgibt. Notieren, welche Box klingt.

## 7. Was dieser Entwurf nicht enthält

Bewusst weggelassen, mit Grund, damit es nicht als Versehen wiederkommt:

- **Kamera-gestützte Erfassung.** Kamera mittig unter dem Netz, senkrecht nach oben; je Anker eine kurze LED-Strecke einschalten, Bild greifen, hellsten Fleck suchen. Der Reiz liegt nicht in der Ersparnis bei ein- bis zweihundert Ankern, sondern darin, dass man dann nicht auf Anker beschränkt ist: jede zehnte LED jedes Stripes abzufahren sind 1800 Aufnahmen, bei vier je Sekunde eine Viertelstunde, und danach ist die Interpolation praktisch exakt. Der Preis wäre dunkler Raum, feste Belichtung, ein Fischauge mit einzumessender Verzerrung, die `processing.video`-Bibliothek und ein Bildverarbeitungsteil, der sich ohne Hardware kaum prüfen lässt. Der Kern aus Abschnitt 3 weiss nicht, woher die Anker kommen — ein Kamera-Scan wäre ein zweiter Schreiber in dieselbe Datei, ohne Umbau.
- **Globale Ausgleichsrechnung über alle Anker.** Ein gesetzter Punkt könnte automatisch auf eine plausible Position rutschen, wenn man die Weglängen aller Stripe-Abschnitte gleichzeitig als Nebenbedingung auflöst; jeder Abschnitt eine Kette mit Höchstlänge, die Knoten verbinden die Ketten, und eine Federrelaxation konvergiert da zuverlässig. Der Nebeneffekt macht es beim Aufnehmen aber unangenehm: der eigene Klick wird zur weichen Empfehlung, und vorher gesetzte Punkte wandern unter der Hand weg. Der Vektor-Vorschlag aus 3.3 holt den grössten Teil des Nutzens ab.
- **Höhe / Z.** Siehe 2.2.
- **Segmentgrenzen innerhalb eines Stripes.** Siehe 2.1.
- **`/net/impulseBorn` und ein Todes-Signal für Impulse.** Siehe 5.2.
- **Abstandsdämpfung im Klang.** Siehe 6.2.

## 8. Fehlerfälle

| Fall | Verhalten |
|---|---|
| `data/ledPositions.txt` fehlt | leere Ankerliste, alle Positionen undefiniert, jede gesendete Koordinate (0, 0). **Eine** Warnzeile beim Start mit der Zahl der undefinierten LEDs. Die Show läuft wie heute, nur ohne Raumbezug — kein Absturz, kein Sonderpfad. |
| Anker ausserhalb der Grundfläche | abgelehnt mit Begründung, HUD und Konsole |
| Weglänge um mehr als 0,5 m überschritten | Warnung, Position wird gesetzt |
| Kaputte Zeile in `ledPositions.txt` | gemeldet und übersprungen |
| `nodeCrossings.txt` nachträglich geändert | Positionen bleiben gültig, neue Knoten erscheinen als offene Einträge in der Arbeitsliste |
| Stripe ohne jeden Anker | seine LEDs bleiben undefiniert und senden (0, 0); `T` und die Startmeldung nennen ihn |
| Impuls-ID-Überlauf nach 2³¹ Impulsen | bei 1000 neuen Impulsen je Sekunde nach etwa 25 Tagen Dauerbetrieb; eine Kollision verwirrt kurz eine Drohne. Hingenommen. |
| Mausklick ausserhalb der Draufsicht-Fläche | verworfen |
| `oscRate` auf 0 | Strom abgeschaltet, `/net/hitNode` läuft weiter |

## 9. Tests

Drei neue Suiten in `test/run.sh`, dessen Übersetzungsliste um `LedAnchorStore.java`, `LedPositionMap.java` und `LedPositionCalibration.java` erweitert wird. Alle drei Klassen sind processing- und netzfrei; `LedStripeNetworks.java` steht schon in der Liste.

**`LedAnchorStoreTest`**

- `ledIndex` ausserhalb des Bereichs wird abgelehnt, mit Meldung
- x oder y ausserhalb der Grundfläche wird abgelehnt, an allen vier Rändern
- Knoten-Verteilung: `set()` auf eine LED einer Kreuzung setzt alle LEDs dieser Kreuzung auf denselben Wert
- `set()` auf eine LED, die in keiner Kreuzung steht, setzt genau eine
- Weglängen-Warnung schlägt bei 0,6 m Überschreitung an und bei 0,4 m nicht; die Position ist in **beiden** Fällen gesetzt
- `remove()`, `clearAll()`, `anchorsOnStripe()` in aufsteigender Ordnung
- Datei-Rundlauf: schreiben, neu laden, identische Anker
- kaputte und leere Zeilen sowie Kommentarzeilen werden übersprungen, gültige Zeilen derselben Datei geladen
- mehrfaches `save()` verdoppelt nichts
- `loadedCount` verschiebt sich, wenn ein geladener Anker gelöscht wird

**`LedPositionMapTest`**

- Interpolation: zwei Anker auf einem Stripe, die LED genau dazwischen liegt auf der Mitte der Verbindung
- Interpolation bei ungleichen Index-Abständen ist proportional zum Index, nicht zur Reihenfolge
- Extrapolation hinter dem letzten Anker setzt den Vektor fort, in der richtigen Richtung, und ebenso vor dem ersten
- ein Anker auf dem Stripe → alle LEDs dieses Stripes liegen auf diesem Punkt, `isInterpolated` ist überall false
- kein Anker → `isDefined` false, `undefinedCount` stimmt
- `isInterpolated` ist true zwischen den äussersten Ankern und false ausserhalb
- Gegenprobe zum Werkzeug: `positionOf` für einen **ungesetzten** Anker liefert genau den Wert, den `LedPositionCalibration.proposalOf` anzeigt
- `apply()` schreibt Arrays, die für jede LED mit `positionOf` übereinstimmen
- Anker auf verschiedenen Stripes beeinflussen sich nicht

**`LedPositionCalibrationTest`**

- Länge und Reihenfolge der Arbeitsliste an einer **kleinen synthetischen Vorgabe** (etwa 4 Stripes, 2 Kreuzungen → 2·4 + 2·2 = 12 Einträge), sortiert nach Stripe und Index. Nicht gegen `data/nodeCrossings.txt` prüfen, siehe 2.3
- eine Kreuzung genau auf LED 0 eines Stripes erzeugt nur einen Eintrag, keinen doppelten
- eine neu hinzugefügte Kreuzung erscheint nach dem Neuaufbau der Liste als offener Eintrag an der richtigen Stelle
- `o` springt zum nächsten offenen Eintrag und bleibt stehen, wenn keiner mehr folgt
- `,` am Listenanfang und `.` am Listenende bleiben stehen, kein Umlauf
- Rundlauf `worldToPane` → `paneToWorld` auf Pixelgenauigkeit, an allen vier Ecken und in der Mitte
- Klicks ausserhalb der Fläche setzen keine Position
- Setzen einer Knoten-Position schliesst den Eintrag auf dem Partner-Stripe mit
- die Doppelbestätigung von `L`: erster Druck verwirft nichts, zweiter Druck nach 400 ms verwirft, zweiter Druck nach 100 ms verwirft nicht, eine Taste dazwischen bricht ab

Nicht durch die Suite gedeckt und darum ausdrücklich Handarbeit vor Ort: die Ambisonic-Kette in SuperCollider (Testton je Kanal, siehe 6.5), das Zeichnen der Draufsicht-Fläche und das Aussehen der Rückmeldung im Netz.

## 10. Betroffene Dateien

**Neu**

- `LedAnchorStore.java`
- `LedPositionMap.java`
- `LedPositionCalibration.java`
- `test/LedAnchorStoreTest.java`, `test/LedPositionMapTest.java`, `test/LedPositionCalibrationTest.java`
- `data/ledPositions.txt` — anfangs nur die Kopfzeilen als Kommentar
- `docs/positionen-anleitung.md` — Handlungsanleitung für die Aufnahme, im Zuschnitt von `docs/kalibrierung-anleitung.md`: Vorgehen Schritt für Schritt, Gegenprüfen ohne Neustart, Korrigieren, Fallstricke

**Geändert**

- `imPulse.pde` — Felder aus 3.1, `positionMode`, `mousePressed`/`mouseDragged`, Zeichnen der Draufsicht-Fläche, Verdrahtung von Store und Map, Startmeldung bei fehlenden Positionen, gegenseitiger Ausschluss von `c` und `p`
- `LedStripeNetworks.java` — `posX`/`posY` an `LedNetworkNode`, `applyPositions`
- `LedNetworkTransportEffect.java` — `id` an `TravellingActivation`, x und y an `/net/hitNode`, Strom `/net/impulse`, zwei neue Parameter
- `supercollider/klangnetz_bells.scd` — Ambisonic-Kette, `\impulseDrone`, Drohnenverwaltung
- `test/run.sh` — drei Klassen in der Übersetzungsliste, drei Suiten in der Default-Auswahl
- `CLAUDE.md` — Abschnitt zu Positionen und Spatialisierung, Erweiterung der OSC-Liste, Tastenbelegung des neuen Modus, Verweis auf die neue Anleitung
