# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projekt

Processing-Sketch (Java-Modus) für die audiovisuelle Installation *imPulse*: LED-Stripes bilden ein chaotisches Netz, Kontaktmikrofone an Metallrohren erzeugen Lichtimpulse, die entlang der Stripes wandern und sich an Kreuzungen (Nodes) aufspalten, wobei sie Töne triggern.

Signalkette: `Max/MSP --OSC:8001--> Processing --Syphon--> MadMapper --ArtNet--> APA102` und zurück `Processing --OSC:8002--> Max/MSP`. Alternativ sendet Processing direkt per ArtNet (aktuell aktiv, siehe „Ausgabepfade").

## Ausführen

Kein Build-System, keine Tests, kein Linter — reines Processing-Projekt.

- **IDE**: `imPulse.pde` in Processing 3 öffnen, Play. Der Sketch-Ordner **muss** `imPulse` heissen (Processing-Konvention: Ordnername == Name der Haupt-`.pde`).
- **CLI**: `processing-java --sketch=/Users/macbook/Projekte/_gitHub/imPulse --run`

### Bibliotheken

`oscP5`, `controlP5`, `Syphon` liegen als Kopie in `libraries/`, aber Processing löst Bibliotheken **aus dem Sketchbook** (`~/Documents/Processing/libraries`) auf, nicht aus dem Sketch-Ordner. Sie müssen dort per Contribution Manager installiert sein. `artnet4j` (`ch.bildspur.artnet`) ist gar nicht mit eingecheckt und muss ebenfalls installiert werden — ohne sie kompiliert der Sketch nicht.

## Architektur

### Globaler LED-Index

Alles rechnet auf einem flachen Array über alle Stripes: `ledIndex = stripeIndex * numLedsPerStripe + indexInStripe`. Konfiguration steht als Feld in `imPulse.pde` (aktuell `numStripes = 16`, `numLedsPerStripe = 720`). Jeder Effekt hält einen eigenen `LedColor[numLeds]`-Puffer und gibt ihn aus `drawMe()` zurück.

`LedColor` (LedColor.java) erbt von `PVector`; `x/y/z` sind `r/g/b` im Bereich **0..1**. Die Skalierung auf 0..255 passiert erst beim Output (`ArtNetSender.buildPackage`, `drawLedColorsToCanvas`).

### Effekt-Pipeline

`runnableLedEffect` (mixer.java) ist das Interface: `drawMe()` + `getName()`. Der `Mixer` iteriert über alle registrierten Effekte, mischt additiv (`LedAlphaMode.ADD`) mit einer per OSC steuerbaren Opacity pro Effekt und wendet vorab `Master/trace` als Feedback-/Nachleuchtfaktor auf den Ausgabepuffer an.

Neuen Effekt hinzufügen: `TemplateEffect.java` als Vorlage kopieren, in `setup()` instanziieren und mit `mixer.addEffect(...)` registrieren.

Bestehende Effekte:
- `LedNetworkTransportEffect` — die wandernden Impulse (Kern der Installation)
- `LedNetworkNodeEffects` — Darstellung der Nodes (Zustände: firing → inactive → waiting, mit Pulsmodulation)
- `LedStripeFullActivationEffect` — **Kalibrier-Modus**, kein gestalterischer Effekt (siehe unten)

### OSC-Parametersystem (AbstractParameter.java)

Jeder `RemoteControlled{Float,Int,Color}Parameter` registriert sich im Konstruktor selbst beim statischen `OscMessageDistributor`. Es gibt **keine** Adress-Zuordnung: der Distributor schickt jede Nachricht an *alle* Sinks, jeder Sink filtert selbst per `newMessage.checkAddrPattern(...)`.

Threading: `oscEvent()` läuft im oscP5-Thread und ruft nur `queueMessage()` (synchronized). Ausgewertet wird die Queue am Anfang von `draw()` über `distributeMessages()`. Neue Parameter also niemals direkt aus dem OSC-Callback verändern.

`data/remoteSettings.txt` wird bei **jedem Start** aus den registrierten Parametern neu geschrieben (`dumpParameterInfo`) und dient als Konfiguration der Remote-Oberfläche. Ein neuer `RemoteControlled*Parameter` taucht dort automatisch auf.

Eingehende OSC-Adressen: `/tube/trigger` (int Stripe, 1-basiert; optional float Energie), `/net/activateNode` (int), `/net/activateStripe` (int) sowie alle in `remoteSettings.txt` gelisteten Parameteradressen.
Ausgehend: `/net/hitNode` (int nodeId, float energy) an Port 8002.

### Netz-Topologie (LedStripeNetworks.java)

`data/nodeCrossings.txt`: eine Zeile pro Node, darin leerzeichengetrennte **globale LED-Indizes**, die sich physisch kreuzen. `LedInNetInfo.loadListOfNodes()` liest die Datei, baut `LedNetworkNode`-Objekte und setzt bei jeder beteiligten LED `LedInNetInfo.partOfNode`. Über dieses Feld erkennt der Transport-Effekt einen Node-Treffer in O(1).

`buildClusterInfo()` in derselben Datei ist ein unfertiger Alt-Pfad für automatische Cluster-Bildung und wird nicht aufgerufen.

### Impuls-Simulation (LedNetworkTransportEffect.java)

Jeder Impuls ist eine `TravellingActivation` (Position als float, Stripe, Geschwindigkeit inkl. Vorzeichen = Richtung, Energie). Pro Frame wird der Zeitschritt aus `System.currentTimeMillis()` gebildet — die Simulation hängt an der Wanduhr, nicht am Framecount.

Zwei Mechanismen, die man beim Ändern kennen muss:
- **Filler**: Bei hoher Geschwindigkeit überspringt ein Impuls LEDs zwischen zwei Frames. Für die übersprungenen Positionen werden `TravellingActivationFiller` erzeugt, gezeichnet und am Ende desselben Frames wieder entfernt.
- **nodeDeadTime**: Ein Node feuert erst wieder nach `/net/impulse/nodeDeadTime` Sekunden. Ohne diese Totzeit würde ein Impuls denselben Node in aufeinanderfolgenden Frames endlos neu triggern.

Bei einem Node-Treffer erhält jeder Zweig aktuell die **volle** Energie des Elternimpulses (`childEnergy = curActivation.energy`) — ein bewusster Quick-Fix, jede Aufspaltung vervielfacht also die Gesamtenergie. Die auskommentierte Zeile darüber zeigt die energieerhaltende Variante.

### Ausgabepfade

Beide Pfade sind in `setup()`/`draw()` per Kommentar umschaltbar:
1. **ArtNet direkt** (aktiv): `ArtNetSender` unicastet an `ipPrefix + startIP++` (`2.0.0.10` aufwärts), 170 Pixel pro Universe.
2. **Syphon/Spout** (auskommentiert): `canvas` ist ein `PGraphics` mit Breite = LEDs pro Stripe, Höhe = Anzahl Stripes; jedes Pixel ist eine LED. Wird als Textur an MadMapper geschickt (`congress19.mad` ist das zugehörige MadMapper-Projekt, Syphon-Server-Name `Lightstrument`). Windows nutzt Spout, macOS Syphon — die jeweils andere Zeile ist auskommentiert.

`canvas` wird unabhängig davon immer befüllt und als Preview ins Sketch-Fenster gezeichnet.

### Node-Kalibrierung

`LedStripeFullActivationEffect` ist das Werkzeug, um `nodeCrossings.txt` von Hand aufzunehmen: Stripes/LEDs werden per Pfeiltasten durchgefahren, das Dropdown (ControlP5, Callback `dropdown()` in `imPulse.pde`) wählt den Modus aus `StripeChangeMode`. Genauer Ablauf steht im README.

Zwei Punkte, die im README fehlen:
- Der Effekt ist in `setup()` **nicht** im Mixer registriert (`mixer.addEffect(ledStripeFullActivationEffect)` ist auskommentiert) — zum Kalibrieren muss die Zeile aktiviert werden. `changeStripe()` wird in `draw()` trotzdem immer aufgerufen.
- `s` schreibt mit `FileWriter(path, true)` — also **append**. Mehrfaches Speichern in einer Session hängt Nodes doppelt an `data/nodeCrossings.txt` an.

`data/nodeCrossings_35C3.txt` ist die Topologie einer früheren Installation und wird nicht geladen.

## Konventionen und Fallstricke

- **Klassenname ≠ Dateiname**: Alle `.java`-Dateien liegen flach im Sketch-Ordner, Processing kompiliert sie ins Default-Package. Die meisten Klassen sind package-private, mehrere pro Datei (z. B. `StripeConfigurator` + `ArtNetSender` in `StripeHardwareHandler.java`). Beim Suchen nach einer Klasse also nicht auf den Dateinamen verlassen.
- **Nicht initialisierte `PApplet`-Felder**: `Mixer.papplet`, `ArtNetSender.parent`, `TemplateEffect.papplet` usw. werden nie zugewiesen und sind `null`. Über sie werden ausschliesslich *statische* `PApplet`-Helfer aufgerufen (`ceil`, `constrain`, `map`, `str`) — in Java erlaubt. Ein Aufruf einer Instanzmethode über diese Felder wirft sofort eine NPE.
- **Hardware-Konstanten** (`numStripes`, `numLedsPerStripe`, `numStripesPerController`, `ipPrefix`, `startIP`, OSC-Ports) stehen als Felder oben in `imPulse.pde` und sind installationsspezifisch — nicht ändern, ohne dass es um eine konkrete Installation geht.
- **Farbwerte 0..1** durchgängig; Werte > 1 sind erlaubt und werden erst am Output geclampt (`LedColor.clamp()` wird im Mixer bewusst nicht aufgerufen).
- Bekannte offene Punkte stehen als To-Do-Block am Kopf von `imPulse.pde`.
