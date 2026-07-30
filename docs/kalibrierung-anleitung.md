# Knotenpunkte aufnehmen — Anleitung

Diese Anleitung beschreibt, wie `data/nodeCrossings.txt` von Hand aufgenommen wird: die Liste der Stellen, an denen sich zwei LED-Stripes physisch kreuzen. Der Sketch braucht sie, damit Impulse sich dort aufspalten und Töne triggern.

Geschrieben für die Garbicz-Fassung: 15 Controller, 30 Stripes à 600 LEDs. Die Tastenbelegung im Einzelnen steht in `CLAUDE.md` unter „Node-Kalibrierung"; hier geht es um das Vorgehen.

## Vor dem Anfangen

- **Der Sketch wird aus der Processing-IDE gestartet.** Auf diesem Rechner gibt es kein `processing-java`. `open -a Processing imPulse.pde`, dann Play.
- **Die Datei ist leer.** `data/nodeCrossings.txt` hat 0 Zeilen — du fängst bei null an. Die Aufnahme der vorigen Installation liegt als `data/nodeCrossings_16x720.txt` daneben und ist für die neue Geometrie wertlos, weil sich mit 600 statt 720 LEDs pro Stripe alle Indizes verschoben haben.
- **Der Master-Pegel steht auf 0,1** und ist auf 0,3 gedeckelt. Nicht hochdrehen — die Stripes vertragen keine volle Helligkeit.
- Die Controller müssen erreichbar sein. Kurzprobe: `ping 2.2.2.2`.

## Die Ergonomie, ehrlich vorweg

Du musst die physische Kreuzung **ansehen**, während du den Cursor über die Tastatur bewegst. Das ist der unangenehme Kern der Sache und lässt sich nicht wegdokumentieren. Drei Wege:

1. **Zu zweit** — einer an der Tastatur, einer am Netz, der zuruft. Am schnellsten.
2. **Laptop mitnehmen** und in Sichtweite der Kreuzung aufstellen.
3. **Erst schauen, dann tippen** — Positionen mit dem Auge grob schätzen, eintippen, korrigieren. Funktioniert, weil `F` die Schrittweite auf 100 stellt und man sich so schnell annähert.

Falls sich das als zu mühsam erweist: eine Fernsteuerung der Cursor über OSC wäre eine kleine Ergänzung — dann stehst du mit dem Telefon direkt an der Kreuzung. Das war beim Entwurf eine der Varianten und wurde zurückgestellt, nicht verworfen.

## Eine Kreuzung aufnehmen

`c` schaltet den Kalibriermodus ein. Ab jetzt zeigt die Anlage nur noch die Kalibrierung, nicht mehr die Show.

Du hast zwei Cursor. Jeder besteht aus einem **schwach leuchtenden ganzen Stripe** — damit du ihn im Gewirr überhaupt findest — und einem **hellen Punkt** darauf. Cursor A ist grün, Cursor B rot. Das aktive Cursor steht im HUD unter der Vorschau mit einem Pfeil markiert.

1. **Stripe für Cursor A wählen**: Pfeil hoch/runter. Im Netz leuchtet jetzt ein Stripe schwach grün.
2. **Punkt auf die Kreuzung fahren**: Pfeil links/rechts. Mit `F` die Schrittweite zwischen 1, 10 und 100 umschalten — grob mit 100 annähern, fein mit 1 treffen.
3. **`TAB`** schaltet auf Cursor B.
4. **Stripe für Cursor B wählen**: der zweite Stripe der Kreuzung, leuchtet schwach rot.
5. **Punkt auf dieselbe physische Stelle fahren** wie bei A.
6. **`ENTER`**. Das HUD und die Konsole melden entweder „Node n gesetzt" mit den beiden globalen Indizes, oder den Grund der Ablehnung. Danach steht der aktive Cursor wieder auf A.

Für die nächste Kreuzung bei Schritt 1 weiter. Bleibt einer der beiden Stripes derselbe, kannst du ihn stehen lassen und nur den Punkt verschieben.

### Kreuzung eines Stripes mit sich selbst

Kommt vor, wenn ein Stripe sich selbst überlappt. Dazu beide Cursor auf **denselben** Stripe stellen und die Punkte auf die zwei Stellen fahren. Mindestabstand sind 3 LEDs, sonst wird abgelehnt — das verhindert, dass ein zitternder Cursor eine Kreuzung mit sich selbst erzeugt.

### Wenn `ENTER` ablehnt

Vier Gründe, alle mit Klartext im HUD und auf der Konsole:

- beide Cursor stehen auf derselben LED
- gleicher Stripe, weniger als 3 LEDs Abstand
- das Paar steht schon in der Liste
- ein Index liegt ausserhalb des gültigen Bereichs

## Zwischendurch sichern

`S` schreibt die vollständige Liste nach `data/nodeCrossings.txt`. Das ist gefahrlos beliebig oft möglich: geschrieben wird in eine Nebendatei, die anschliessend über die Zieldatei umbenannt wird — es wird nichts angehängt und nichts verdoppelt. **Mach das nach jeweils ein paar Knoten.**

Speichern nimmt dir nichts aus der Hand: `BACKSPACE` funktioniert danach weiter, alle Kreuzungen dieser Sitzung bleiben zurücknehmbar. Nur denk daran, nach einer Korrektur erneut `S` zu drücken — sonst steht in der Datei noch der Stand von vorher.

## Gegenprüfen, ohne neu zu starten

Das ist der eigentliche Zeitgewinn gegenüber früher:

1. `R` übernimmt die aufgenommenen Kreuzungen sofort in die laufende Simulation.
2. `c` verlässt den Kalibriermodus, die Show läuft wieder.
3. Ein Rohr anschlagen — oder, falls Max/MSP nicht läuft, per OSC an Port 8001 ein `/tube/trigger` mit der Stripe-Nummer senden (1-basiert), alternativ `/net/activateStripe` mit 0-basiertem Index.
4. Zusehen, ob der Impuls sich an der erwarteten Stelle aufspaltet.
5. `c` zurück in die Kalibrierung.

Mit `N` lassen sich die gesetzten Kreuzungen einblenden: **magenta** sind beim Start geladene, **cyan** die in dieser Sitzung neuen. Gut, um den Überblick zu behalten und Doppelaufnahmen zu vermeiden.

## Korrigieren

Zwei Wege, je nachdem wie alt der Fehler ist.

### Die letzte Aufnahme zurücknehmen

**`BACKSPACE`** nimmt die letzte Kreuzung dieser Sitzung zurück, beliebig oft bis zum Anfang der Sitzung. Auch nach `S`.

Wichtig zu wissen: geladene Einträge sind davor **geschützt**. Startest du den Sketch neu, gelten alle bereits gespeicherten Kreuzungen als geladen und `BACKSPACE` fasst sie nicht mehr an. Das ist Absicht — eine mühsam aufgenommene Kalibrierung soll sich nicht mit einer gehaltenen Taste abräumen lassen.

### Eine bestimmte Kreuzung heraussuchen und neu setzen

Dafür sind **`,`** und **`.`** da — damit blätterst du durch die Liste. Der ausgewählte Knoten **blinkt weiss im Netz** (unabhängig davon, ob `N` eingeschaltet ist), das HUD nennt seine Position, seine Indizes und ob er geladen oder neu ist. `,` am Anfang der Liste hebt die Auswahl wieder auf.

Steht der richtige Knoten, drückst du **`X`**:

- der Eintrag wird gelöscht — **auch ein geladener**, und egal wie weit hinten er liegt
- beide Cursor stehen danach auf seinen zwei LEDs

Damit ist das Neusetzen eine Kleinigkeit: Cursor um die paar LEDs nachfahren, die daneben lagen, `ENTER`, `S`. Sass er ganz falsch, lässt du ihn einfach gelöscht.

`X` fragt nicht nach — im Gegensatz zu `L`. Zwei Gründe: du siehst genau den einen Knoten blinken, den es trifft, und die Datei ändert sich erst beim nächsten `S`. Ein Fehlgriff kostet dich also nur die eine Kreuzung, und bis zum nächsten `S` gar nichts.

### Alles verwerfen

Etwa weil die Aufnahme aus einer anderen Geometrie stammt: **`L`** zweimal drücken. Der erste Druck kündigt an und nennt die Anzahl, der zweite muss zwischen 0,3 und 5 Sekunden später kommen. Jede andere Taste bricht ab.

## Am Ende

1. `S` — letzter Stand geschrieben.
2. `wc -l data/nodeCrossings.txt` — die Zeilenzahl muss zur Anzahl der aufgenommenen Knoten passen.
3. Sketch neu starten. Die Konsole meldet beim Laden „n Nodes geladen"; die Zahl muss stimmen und es darf keine Meldung über übersprungene Zeilen kommen.
4. Mit `c`, `N` prüfen, dass alle Knoten magenta erscheinen — also aus der Datei kommen.

## Dateiformat

Eine Zeile je Knoten, darin leerzeichengetrennte **globale** LED-Indizes:

```
412 4358
7200 7203
```

Der globale Index ist `stripe * 600 + ledInStripe`, also 0 bis 17999. Zwei Indizes je Zeile sind der Normalfall; mehr sind erlaubt, falls sich an einer Stelle drei Stripes treffen.

Fehlerhafte Zeilen werden beim Laden gemeldet und übersprungen, nicht als Absturz weitergereicht — eine von Hand verunfallte Datei hindert den Sketch also nicht am Starten.

## Fallstrick, der alles wertlos macht

Ändert sich `controllerOctets` oder `numLedsPerStripe` in `imPulse.pde`, verschieben sich **alle** globalen Indizes und die Aufnahme ist hinüber. Beides also vor der Kalibrierung festlegen, nicht danach. Auch die Reihenfolge der Oktette im Array zählt: sie bestimmt, welcher physische Stripe welche Nummer trägt.
