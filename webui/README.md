# imPulse Web-UI

Schlanke Weboberflaeche, um die OSC-Parameter des laufenden imPulse-Sketches
live zu aendern — im Browser, ueber Tailscale (`http://100.94.47.6:8080`) oder
im lokalen LAN.

Der Server laeuft **auf derselben Maschine wie imPulse** (Windows-Laptop),
bindet auf `0.0.0.0:8080` und schickt die Aenderungen per OSC an
`127.0.0.1:8001` — also genau dorthin, wo `oscP5` im Sketch lauscht.

```
Browser  --HTTP:8080-->  webui/server.py  --OSC:8001-->  imPulse (Processing)
```

## Was das UI anzeigt

Die Parameterliste ist **nicht** im Code verdrahtet, sondern wird bei jedem
Seitenaufruf aus `data/remoteSettings.txt` gelesen. Diese Datei schreibt
imPulse bei **jedem Start** aus den registrierten `RemoteControlled*Parameter`
neu (`OscMessageDistributor.dumpParameterInfo`). Ein neuer Parameter im Sketch
taucht damit ohne Codeaenderung hier auf; ein Klick auf **Neu laden** liest die
Datei nach einem imPulse-Neustart sofort neu ein.

- Float-Parameter: Regler + Zahlenfeld mit dem Bereich aus der Datei
- Int-Parameter: dito, ganzzahlig; `0/1`-Parameter (z. B.
  `/net/randomSpawn/enabled`) als Schalter
- Farbparameter: in `remoteSettings.txt` stehen sie als drei Float-Zeilen
  `<basis>/Hue`, `<basis>/Sat`, `<basis>/Bright` — das UI erkennt das Tripel
  und zeigt zusaetzlich einen Farbwaehler, der alle drei gemeinsam setzt
- Gruppierung nach Adress-Praefix (alles unter `/net/impulse` zusammen, alles
  unter `/net/randomSpawn` zusammen usw.)
- Trigger-Parameter (`/net/activateNode`, `/net/activateStripe` — Einmal-
  Aktionen, keine Regler-Werte) bekommen ein eigenes Widget: Zahlenfeld +
  **Ausloesen**-Knopf, statt eines Sliders, der einen falschen „gehaltenen
  Zustand" suggerieren wuerde
- `oscMaxCount` und `energyExponent` (Setup-/Sicherheitsparameter, keine
  Alltags-Regler) stehen in einer eigenen, per Default eingeklappten
  **Advanced**-Sektion, statt zwischen Farbe und Speed

Vollstaendige Range-/Sections-Analyse aller OSC-Parameter:
`docs/webui-parameter-review-2026-07-30.md`.

### Acht Themen-Tabs

Statt einer langen Liste liegen die Regler auf acht Tabs: **Mixer**, **Sound
Design**, **Spawn-Verhalten**, **Noten-Verhalten**, **Tonleiter**,
**Impuls-Verhalten**, **Farben**, **Song-Struktur**. Welche Adresse in
welchen Tab gehoert, entscheiden `TAB_RULES` und `TAB_PRIMARY` in
`server.py` — dort ist es pruefbar, und `test_webui.py` stellt sicher,
dass **jeder** Regler in genau einem Tab landet und keiner doppelt. Oben je
Tab die kuratierten Regler, darunter ein eingeklapptes `Erweitert` mit dem
Rest.

**Der Tonleiter-Tab traegt keine generischen Regler.** Sein einziger Inhalt
ist die Melodie-Zuordnung (siehe eigener Abschnitt unten) — deshalb keine
Zeile in `TAB_RULES`, `TAB_PRIMARY[TAB_SCALE]` bleibt leer. Liefert der
Server keine `melody`-Daten (aelterer imPulse-Stand ohne `/net/melody/*`),
faellt der Tab komplett weg, statt leer dazustehen — dieselbe Regel wie
vorher beim `hidden`-Attribut der Sektion, nur eine Ebene hoeher
(`build_tabs()` in `server.py`).

**Seit 2026-08-02 stehen Presets und Melodie-Zuordnung IN den Tabs**, nicht
mehr fest ueber der Tab-Leiste: Presets ganz oben im Mixer-Tab (vor den
kuratierten Reglern), Melodie-Zuordnung als einziger Inhalt des
Tonleiter-Tabs. Vorher zwangen beide Sektionen jeden Aufruf, an ihnen
vorbeizuscrollen, bevor ueberhaupt ein Tuning-Regler sichtbar wurde. Ihr
Markup liegt trotzdem weiterhin in `templates/index.html`, jetzt in einem
`<div id="parked" hidden>`-Abstellplatz — `buildTabs()` in `app.js` haengt
die beiden `<section>`-Elemente nur um, statt sie neu zu bauen: `app.js`
verdrahtet seine Listener beim Laden an feste IDs (`presetSelect`,
`melodyRecompute` usw.), und dieselben Elemente muessen bei jedem
Tab-Neuaufbau (`tabPanelsEl.innerHTML = ''`) wieder in den Abstellplatz
zurueck, statt aus dem Dokument zu fallen.

Drei Dinge, die man beim Aendern kennen muss:

- **Die Reihenfolge der Regeln zaehlt, die erste passende gewinnt.**
  `/net/impulse/speedQuantize/` muss **vor** `/net/impulse/` stehen, sonst
  zoege die Physik-Regel die Speed-Klassen an sich; genauso muessen
  `/net/impulse/color/`, `/net/impulse/fadeOut/` und `/nodes/colors/` vor
  `/net/impulse/` bzw. `/nodes/` stehen, sonst landen die Farben wieder im
  Impuls-Tab.
- **`buildTabs()` baut ALLE Panels und schaltet nur die Sichtbarkeit um.**
  Die Regler tragen sich beim Bauen in die flache `controls`-Map ein, und
  ueber genau die laufen das Preset-Laden und der `applied`/`echoed`-
  Ruecklauf. Ein Regler auf einem nie geoeffneten Tab stuende sonst nicht in
  der Map und wuerde von einem Preset still nicht angezeigt.
- **Eine Gruppe geht als GANZES in einen Tab**, bestimmt von der Adresse
  ihres ersten Reglers. Eine Farbkarte traegt aber keine eigene Adresse,
  sondern drei darunter (`<basis>/Hue|Sat|Bright`) — `build_tabs()` nimmt
  fuer sie deshalb ausdruecklich die Hue-Adresse. Ohne diesen Zweig hat eine
  Gruppe aus lauter Farbkarten gar keine Adresse und faellt aus **jedem**
  Tab heraus; genau das war mit `/nodes/colors` passiert (18 Werte, sechs
  Karten, im UI unerreichbar, ohne Fehlermeldung). Ein Test haelt fuer jede
  Gruppe fest, dass sie in einem Tab ankommt.

Der Farben-Tab traegt als einziger das `expanded`-Flag (`TAB_EXPANDED`): er
hat keine kuratierte Auswahl, weil `TAB_PRIMARY` auf Adressen arbeitet und
bei Farbkarten nicht greift — ohne das Flag bestuende der ganze Tab aus einem
zugeklappten `Erweitert`.

### Fuenf Spezial-Sektionen

Fuenf Bereiche bekommen ein handgebautes Bedienfeld statt generischer Regler —
38 Sequencer-Parameter als flache Liste waeren unbedienbar. Der Server
liefert dafuer Struktur (`build_sequencer`, `build_speed_classes`,
`sc_param_groups`), das Aussehen macht `static/app.js`.

- **Sequencer**: BPM als grosse Ziffer mit eigenem Not-Aus, darunter sechs
  Track-Karten mit je eigener Spurfarbe. Notenwerte als Knopfleiste mit
  Symbol **und** Kuerzel (`1/4`) — nicht jede Windows-Schrift hat
  U+1D15D..U+1D161, ein Symbol allein waere dort ein leeres Kaestchen.
  `originStripeOverride` zeigt `-1` als „zufall" statt als Zahl.
  `originTreeFilter` ist ein **Auswahlbalken** mit fuenf Klartext-Zustaenden
  (`alle`/`vorn`/`hinten`/`rechts`/`links`), kein 0..4-Schieber: „0 =
  zufaellig" ist an einem Zahlenregler nicht zu erraten. Bewusst auch kein
  Schalter-plus-Dropdown — das waeren zwei Bedienelemente fuer einen
  Parameter mit fuenf gleichrangigen Zustaenden, dazu ein verborgener
  „zuletzt gewaehlter Baum", der nach einem Preset-Laden gegenueber dem
  Sketch falsch stehen kann. Der OSC-Wertebereich bleibt unveraendert
  `int 0..4`. Unter der Spurenreihe steht die Erklaerung dazu (`TREE_HELP`
  in `server.py`, weil sie eine Aussage ueber `OriginSequencer` trifft und
  dort pruefbar ist), und je Track erscheint eine Warnzeile **nur dann**,
  wenn ein gesetzter `originStripeOverride` den Filter gerade aushebelt.
- **Palette**: siehe unten, eigener Abschnitt.
- **Speed-Klassen**: die fuenf Gewichte aus
  `/net/impulse/speedQuantize/weight/*` plus ein Verteilungsbalken, der sie
  normiert als Prozente zeigt. Die Gewichte selbst muessen sich nicht auf 100
  summieren — normalisiert wird auf der Java-Seite (`SpeedQuantizer.pick`).
- **Sound (SuperCollider)**: siehe unten, eigener Port.
- **Mitschnitt**: der Aufnahme-Knopf ganz oben im Sound-Tab, siehe unten,
  eigener Abschnitt.

Palette und Mitschnitt sind als einzige **bedingungslos** da: sie haengen an
keiner Adresse aus `remoteSettings.txt` und koennen deshalb — anders als
Sequencer und Speed-Klassen — nicht daran scheitern, dass ein aelterer
imPulse-Stand ihre Parameter noch nicht schreibt.

Die Adressen der uebrigen Sektionen nimmt `sequencer_addresses()` aus dem
generischen Rendering heraus, die der Melodie-Sektion (siehe unten)
`melody_addresses()`. Ohne das stuende jeder Regler zweimal auf der Seite —
zwei Bedienelemente fuer denselben Parameter, die auseinanderlaufen
koennen.

### Melodie-Zuordnung: eine Sektion, in der Regler nichts tun

Im Tab **Tonleiter**, oben, sitzt **Melodie-Zuordnung** mit den vier
Werten `/net/melody/{mode,startNode,rootMidiNote,numOctaves}` und einem
Knopf. Sie ist die einzige Stelle im UI, an der das **Verstellen eines
Feldes nichts sendet**.

Der Grund steht im Konzept (`docs/superpowers/specs/2026-08-01-...`,
Abschnitte 9 und 9b): die vier Werte sind keine Klangregler, sie beschreiben,
wie die Zuordnung beim naechsten Mal **gerechnet** wird. Erst
`/net/melody/recompute` fuehrt sie aus — und zwar alle vier zusammen, als
**ein** Vorgang. Drei getrennte Trigger rechneten beim Verstellen von dreien
dreimal, davon zweimal mit einem halb gesetzten Zustand; und `numOctaves`
aendert den Modulo-Teiler der Oktavfaltung selbst, eine mit dem alten Wert
gerechnete Zuordnung ist danach nicht transponiert, sondern falsch.

Vier Dinge, die man beim Aendern kennen muss:

- **Die Reihenfolge im Endpunkt ist der Punkt.** `/api/melody/recompute`
  setzt erst alle vier Werte ueber denselben `apply_value()`-Weg wie jeder
  andere Regler, **dann** geht das Kommando raus. Umgekehrt rechnete imPulse
  mit dem alten Stand. Eine Endpunkt-Probe haelt die Reihenfolge nach.
- **Die Bestaetigung ist Pflicht und gilt fuer einen Druck.** Der Knopf ist
  gesperrt, bis das Haekchen sitzt, und das Haekchen faellt danach wieder weg
  — auch nach einem Fehler. Dieselbe Ueberlegung wie bei der Taste `L` in den
  zwei Kalibriermodi: die Aktion ueberschreibt `data/nodeMelody_<modus>.txt`
  samt von Hand korrigierter Zeilen und laesst sich nicht zuruecknehmen.
- **Es gibt keine Erfolgsmeldung von imPulse.** Kein Rueckkanal — die
  Statuszeile sagt deshalb, was *gesendet* wurde und wo das Ergebnis steht
  (Konsole von imPulse, `data/nodeMelody_<modus>.txt`), nicht „fertig".
  Anders als beim Preset-Speichern, wo eine Datei erscheint, auf die sich
  warten laesst.
- **Der Modus ist ein Dropdown, kein Zahlenfeld.** `MELODY_MODE_NAMES` in
  `server.py` ist die dritte Kopie der Modusliste (neben `MelodyModes.ALL`
  und `~melodyModes` in der `.scd`); ein Test vergleicht sie mit der
  Java-Datei, damit das Dropdown nicht den falschen Namen zu einer Nummer
  zeigt.

`/net/melody/startNode` hat auf der **Sound**-Seite bewusst **kein**
Gegenstueck: SuperCollider rechnet nichts neu, der Regler waere dort
wirkungslos und wanderte trotzdem in jedes Sound-Preset. `melodyMode`,
`melodyRootMidiNote` und `melodyNumOctaves` gibt es dagegen auch dort — beim
Laden einer Zuordnungsdatei gewinnt allerdings deren Kopf.

**Die Rasteranzeige je Track ist die Uhr des Browsers.** Sie rechnet aus BPM
und Notenwert, in welchem *Abstand* ein Track feuert, und ist ausdruecklich
**keine** Rueckmeldung aus imPulse: dafuer gaebe es keinen Kanal, der Sketch
sendet nur an Port 8002 und dort hoert SuperCollider. Ihre Phase kann
gegenueber dem Sketch beliebig verschoben sein. Deshalb heisst sie „Raster"
und behauptet nirgends „feuert jetzt". Nuetzlich ist sie trotzdem: beim
Einrichten sieht man auf einen Blick, welcher Track dicht und welcher duenn
laeuft.

### Sound-Parameter gehen an einen zweiten Port (8002)

Die `/klangnetz/param/*`-Adressen laufen **nicht** durch
`remoteSettings.txt` — das ist die Parameterliste von imPulse. SuperCollider
hat seine eigene Registry (`~registerParam` in
`supercollider/klangnetz_bells.scd`) und hoert auf Port **8002**. Das UI hat
dafuer einen zweiten `OscSender` und den Endpoint `POST /api/sc`.

Zwei Dinge, die man dabei kennen muss:

- **`SC_PARAMS` in `server.py` ist eine handgepflegte Kopie der Registry.**
  Wer in der `.scd` einen Parameter ergaenzt, ergaenzt ihn auch dort. Zwei
  Tests in `test_webui.py` vergleichen die Tabelle in **beide** Richtungen
  mit der Datei, damit sie nicht still abdriftet. Die Alternative waere ein
  Parser fuer sclang-Syntax — der bei der naechsten Umformatierung
  unbemerkt das Falsche liefert.
- **Es gibt keinen Rueckkanal.** Die angezeigten Werte sind die Defaults aus
  der `.scd`, nicht der Live-Zustand von sclang; laeuft sclang nicht, bleibt
  eine Aenderung wirkungslos, ohne dass das UI es merkt. Die Sektion sagt das
  selbst in ihrem Warnhinweis.

Jede Aenderung geht **sofort** raus (erste Bewegung direkt, danach hoechstens
alle 150 ms eine Nachricht, der letzte Wert in jedem Fall) — kein Speichern-Knopf.

## Vierkanal-Mitschnitt (der einzige Rueckkanal im UI)

Ganz oben im Tab **Sound** sitzt der Knopf **Mitschnitt (4 Kanaele)**. Er
schneidet den fertigen Vierkanal-Ausgang von SuperCollider als
`recordings/klangnetz_<zeitstempel>.wav` mit (WAV/int24, Samplerate des
Interfaces). Geschrieben wird die Datei **ausschliesslich von sclang** —
dieses UI schickt drei Datagramme. Details zur Aufnahme selbst:
`supercollider/klangnetz_bells.README.md`.

Oben, weil ein Mitschnitt der erste und der letzte Griff eines Drehtages ist
— nichts, das man unter „Erweitert" sucht, waehrend die Kamera laeuft.

**Vier Endpoints**, jeder schickt genau eine argumentlose OSC-Nachricht an
sclang auf Port **8002**:

| Route | OSC an 8002 |
|---|---|
| `GET /api/record/status`   | `/klangnetz/record/query` (aendert nichts) |
| `POST /api/record/start`   | `/klangnetz/record/start` |
| `POST /api/record/stop`    | `/klangnetz/record/stop` |
| `POST /api/record/toggle`  | `/klangnetz/record/toggle` |

Vier Routen statt einer mit Aktion im Body: eine Adresse pro Kommando ist im
Log und in der Browser-Konsole direkt lesbar, und ein Tippfehler wird zu einem
404 statt zu einem stillen Nichtstun. Argumentlose Nachrichten kann der
eingebaute Encoder seit diesem Feature auch (`build_osc_message(addr, None)`
schreibt den Typtag `,`) — es sind Befehle, keine Werte.

**Anders als alles andere hier ist das nicht fire-and-forget.** sclang meldet
seinen Zustand als `/klangnetz/record/status <0|1> <pfad>` zurueck, und
`RecordStatusListener` in `server.py` hoert dafuer auf
**`127.0.0.1:8003`** (`--record-status-port`, muss zu `~recordStatusPort` in
der `.scd` passen). Zwei Tests halten fest, dass Adressen und Ports auf beiden
Seiten uebereinstimmen.

Der Rueckkanal ist hier kein Luxus. Ein Regler, den man geschickt hat, steht
danach — bei einem Aufnahme-Knopf reicht das nicht: sein Zustand kann von
woanders geaendert werden (Skript, IDE, zweiter Browser), und ein Knopf, der
nur zeigt, was zuletzt geklickt wurde, waere im Zweifel eine Aufnahme, die gar
nicht laeuft. Genau der Fehler, der beim Dreh erst in der Nachbearbeitung
auffaellt.

Daraus folgt der Rest:

- **Der gemeldete Zustand kommt immer von sclang, nie vom Kommando.** Ein
  zweites `/start` bei laufender Aufnahme wird dort ignoriert und meldet
  trotzdem „laeuft" — der Knopf zeigt danach die Wahrheit, nicht das, was der
  Klick gemeint hat.
- **Jeder Endpoint wartet bis zu 400 ms** (`RECORD_STATUS_TIMEOUT_S`) auf die
  Antwort und meldet sonst `answered: false`. Beides laeuft ueber Loopback;
  kommt in 400 ms nichts, laeuft sclang nicht. Lieber ein ehrliches „kein
  Kontakt" als ein Knopf, der Erfolg behauptet.
- **Frisch oder alt** unterscheidet ein Zaehler, nicht ein Wertvergleich: der
  Endpoint merkt sich den Stand vor dem Senden und wartet auf eine Erhoehung.
  Zweimal „laeuft nicht" hintereinander saehe sonst aus wie keine Antwort.
- **Drei Zustaende**, in Farbe *und* Form unterschieden (Bedienung im
  Dunkeln): *laeuft* (roter Punkt, pulsierend), *bereit* (hohler Punkt),
  *unbekannt* (kein Kontakt). Bei **unbekannt** schickt ein Klick `toggle`
  statt `start`/`stop` — raten waere schlechter, und verliert das UI den
  Kontakt waehrend einer Aufnahme, bringt ein Klick sie so trotzdem zu Ende.
- **`known` ist nicht `recording`.** „noch nie gehoert" und „laeuft gerade
  nicht" sind zwei verschiedene Dinge und werden verschieden angezeigt.
- **Ein fehlgeschlagenes Bind auf 8003 verhindert den Start des UI nicht.**
  Dann bleibt der Zustand „unbekannt", der Knopf sendet trotzdem und sagt es
  dazu. Haeufigster Fall: ein zweites, vergessenes `server.py` auf derselben
  Maschine.
- **Polling alle 2 s**, aber nie waehrend eines laufenden Kommandos — dessen
  Antwort ist die frischere, und zwei Abfragen koennten sich in der Anzeige
  ueberholen. Genau **ein** Timer, auch nach mehrfachem „Neu laden".

## Presets

Im Mixer-Tab, ganz oben ueber den kuratierten Reglern, sitzt die Sektion
**Presets**: ein Dropdown mit
allen vorhandenen Presets plus **Laden**, darunter ein Textfeld plus
**Speichern**.

Ein Preset ist ein kompletter Wertesatz aller fernsteuerbaren Parameter, abgelegt
als `data/presets/<name>.txt` — **im selben Format wie `remoteSettings.txt`**.
Geschrieben wird der Ordner ausschliesslich von imPulse (`PresetStore.java`);
das Web-UI **liest** ihn nur.

- **Liste**: kommt vom Dateisystem, nicht per OSC. Der Server laeuft auf
  derselben Maschine wie imPulse und sieht den Ordner direkt — ein OSC-
  Rueckkanal waere neu zu bauen (die einzige Ausgangsadresse des Sketches ist
  Port 8002, also SuperCollider). Aktualisiert wird beim Seitenaufruf und nach
  jedem Speichern.
- **Laden** schickt `/preset/load <name>` und zieht zusaetzlich die Regler nach:
  der Server liest dieselbe Datei mit demselben Parser wie `remoteSettings.txt`
  und schickt die Werte in der Antwort zurueck. Die Regler werden still gesetzt,
  loesen also kein zweites OSC aus — das Anwenden macht imPulse selbst.
  Geklemmt wird dabei auf die Grenzen aus `remoteSettings.txt`, nicht auf die
  aus der Preset-Datei (dieselbe Regel wie `PresetStore.applyPreset()`).
- **Speichern** schickt `/preset/save <name>` und wartet bis zu **1 Sekunde**
  darauf, dass die Datei erscheint — der Sketch schreibt sie erst im naechsten
  `draw()`-Durchlauf. Bleibt sie aus, kommt eine klare Fehlermeldung
  („laeuft der Sketch?") statt eines stillen Erfolgs. Ein vorhandenes Preset
  wird **ohne Rueckfrage ueberschrieben**; die Statuszeile sagt es hinterher.
- **Namen**: nur `a-z`, `0-9`, Unterstrich und Bindestrich, 1 bis 64 Zeichen —
  wortgleich zu `PresetStore.isValidName()` in Java. Das UI und der Server
  pruefen das vorab, die Autoritaet bleibt Java (dort geht es um
  Pfad-Traversal). Grossbuchstaben sind ausgeschlossen, weil `Standby` und
  `standby` auf Windows dieselbe Datei waeren.

Zwei Dinge meldet die Statuszeile beim Laden, statt sie zu verschlucken:
Adressen aus dem Preset, die `remoteSettings.txt` gar nicht kennt (Preset und
Dump aus verschiedenen Codestaenden), und Werte ausserhalb der im UI verengten
Range (siehe `UI_RANGE_OVERRIDES`) — dort klemmt der Regler sichtbar und soll
nicht behaupten, der Sketch fahre den angezeigten Wert.

**Kein Loeschen**: ein Preset wird man los, indem man die Datei auf dem Laptop
von Hand loescht. Bewusst nicht im UI, weil sonst das Web-UI in einen Ordner
schreiben wuerde, der imPulse gehoert.

Der Knopf **Neu laden** oben rechts hat damit nichts zu tun — der liest
`remoteSettings.txt` neu ein, also die Parameter-*Definitionen*.

## Farbpalette

Im Tab **Farben** sitzt oben die Sektion **Palette**: eine kleine Sammlung
wiederverwendbarer Lieblingsfarben. Dieselbe Reihe steht als Swatches unter
**jeder** Farbwaehler-Karte — ein Klick setzt Hue/Sat/Bright genau dieser
Karte, statt sie jedes Mal von Hand einzustellen.

Ein Preset ist das ausdruecklich **nicht**: die Palette haelt nur Farbwerte,
nicht welche Karte welche Farbe traegt. Was wo steht, halten weiterhin die
Presets fest.

- **Datei**: `data/colorPalettes.txt`, Format wie `data/stripeTrees.txt` —
  Tab-getrennte Spalten `name<TAB>hue<TAB>sat<TAB>bright`, die drei Werte in
  `0..1`, `#` leitet einen Kommentar ein. Von Hand editierbar. Bei doppeltem
  Namen gewinnt die **letzte** Zeile, aus demselben Grund wie bei
  `stripeTrees.txt`: die natuerliche Handkorrektur ist eine angehaengte
  Zeile am Ende, „erste gewinnt" wuerde sie still verschlucken.
- **Server-seitig, nicht im localStorage.** Sie soll einen Neustart
  ueberleben und auf jedem Geraet dieselbe sein — genau das meint „eine
  Palette, die von allen gewaehlt werden kann". Eine fehlende Datei ist kein
  Fehler, sondern eine leere Palette; sie entsteht beim ersten Speichern.
- **Endpoints**: `GET /api/palette` und `POST /api/palette`. Der POST traegt
  die **komplette** Liste, der Server ersetzt die Datei durch genau das.
  Kein Hinzufuegen/Entfernen einzelner Eintraege: das waeren zwei Wege auf
  dieselbe Datei, die auseinanderlaufen koennen, und die Reihenfolge muesste
  trotzdem vom Browser kommen. **Preis: zwei gleichzeitig offene Browser
  ueberschreiben sich gegenseitig.** Bei einer Installation mit einem
  Operator ist das der richtige Tausch.
- **Anders als bei den Presets geht kein OSC raus.** Die Palette ist reine
  UI-Sache, imPulse kennt sie nicht. Was ein Swatch-Klick sendet, sind die
  ganz normalen `<basis>/Hue|Sat|Bright`-Nachrichten.
- **Hoechstens 24 Farben** (`PALETTE_MAX_ENTRIES`) — die Swatch-Reihe steht
  unter jeder Farbkarte, ab ein paar Dutzend waere sie hoeher als die Karte.
  Namen hoechstens 32 Zeichen, ohne Tabulator, Zeilenumbruch und fuehrendes
  `#`: die wuerden die Datei beim naechsten Lesen zerlegen.
- **Pfad** ueber `--palette` bzw. `IMPULSE_PALETTE` verschiebbar, Vorgabe
  `colorPalettes.txt` neben `--settings`.
- **Fehler beim Speichern rollen den lokalen Zustand zurueck.** Sonst zeigte
  das UI eine Farbe, die in der Datei nicht steht, und der naechste Neustart
  schluckte sie kommentarlos.

„Farbe uebernehmen" nimmt die **zuletzt angefasste** Farbkarte. Ohne diese
Regel muesste der Knopf raten, welche der sieben Karten gemeint ist; deshalb
meldet er auch, wenn noch keine angefasst wurde, statt still nichts zu tun.

## Automatische Sicherung der Live-Daten

Presets, Farbpaletten und Kalibrierdateien werden im laufenden Betrieb
geaendert, aber niemand committet sie — nachgezogen wurde das bisher von Hand
(„X-Preset vom Live-Betrieb nachgezogen" in der Historie). Wird ein Rechner
neu aufgesetzt, bevor jemand daran denkt, ist die Live-Arbeit weg.

Der Server bringt dafuer einen Hintergrund-Thread mit (`webui/autocommit.py`):
alle **10 Minuten** prueft er die unten stehenden Pfade und macht einen
**lokalen Git-Commit** — aber nur, wenn sich tatsaechlich etwas geaendert hat.
Kein leerer Commit, keine feste Taktung unabhaengig vom Zustand.

**Gepusht wird nicht.** Der Commit bleibt im Checkout des jeweiligen Rechners;
uebertragen wird von Hand mit `git push`, wenn Birk das fuer richtig haelt.
Mehrere Checkouts arbeiten parallel am selben Remote (Live-Laptop,
Test-Deploy, Worktrees) — ein automatischer Push waere eine Fernwirkung ohne
Entscheidung. Dieselbe Regel, die CLAUDE.md fuer Merges und Force-Pushes
aufstellt.

Ueberwacht wird genau das:

| Pfad | warum |
|---|---|
| `data/presets/*.txt` | vom UI ausgeloest, vom Sketch geschrieben |
| `supercollider/presets/*.txt` | ein Preset ist **ein Name, zwei Dateien** — nur die Licht-Haelfte zu sichern hiesse, die Szene kaeme spaeter optisch zurueck und klanglich nicht |
| `data/colorPalettes.txt` | im UI editierbar |
| `data/energyLevels.txt` | im UI editierbar |
| `data/stripeTrees.txt` | Best-Guess, von Hand korrigiert |
| `data/nodeCrossings.txt` | Node-Kalibrierung, nur auf Tastendruck `S` geschrieben |
| `data/ledPositions.txt` | Positions-Kalibrierung, ebenfalls nur auf `S` |

Ein Muster, das in diesem Checkout auf nichts passt, ist kein Fehler — die
Farbpaletten und die Energie-Level kommen erst mit ihren Feature-Branches.

**Bewusst nicht dabei:** `data/remoteSettings.txt` (steht in `.gitignore`,
Boot-Snapshot bei jedem Sketch-Start) und `data/songStructureState.txt` (wird
vom Sketch bei jedem Levelwechsel neu geschrieben). Die Trennlinie ist nicht,
*wer* die Datei schreibt, sondern *wann sie sich aendert*: aendert sie sich nur,
wenn ein Mensch etwas entschieden hat, ist sie Konfiguration; aendert sie sich
von selbst waehrend der Show, ist sie Laufzeitstatus und gehoert nicht in einen
Commit.

**Uebersprungen wird**, ohne einzugreifen und ohne den Server zu stoeren:

- ein offener Merge, Rebase, Cherry-Pick, Revert oder Bisect (erkannt an
  `MERGE_HEAD` und Geschwistern im `.git`-Verzeichnis)
- ein Merge-Konflikt in einer der ueberwachten Dateien
- ein **detached HEAD** — ein Commit ohne Branch waere nach dem naechsten
  Checkout nur noch im Reflog, also ein Sicherungsnetz, das nicht haelt
- jeder Git-Fehler: wird geloggt und in der Statuszeile angezeigt, der
  naechste Versuch laeuft in 10 Minuten

Sichtbar ist der Zustand in der Zeile unter der Statusleiste („Automatische
Sicherung: alle 10 min lokal committet (kein Push) — zuletzt gesichert vor
3 Minuten"), maschinenlesbar unter `GET /api/autocommit`. Nur lesend: es gibt
bewusst keinen Knopf, der aus dem Browser heraus Git-Zustand aendert.

Abschalten fuer ein Entwickler-Checkout:

```bash
python3 server.py --no-autocommit
IMPULSE_AUTOCOMMIT=0 python3 server.py
python3 server.py --autocommit-interval 3600   # oder nur seltener
```

## Normalisierung (der Fallstrick)

`RemoteControlledFloatParameter.digestMessage` mappt eingehende Floats selbst:

```java
theValue = PApplet.constrain(PApplet.map(theValue, 0, 1, minValue, maxValue), minValue, maxValue);
```

Ein Float muss also **auf 0..1 normalisiert** gesendet werden
(`(wert - min) / (max - min)`), nicht als Rohwert — sonst landet z. B. eine
gesendete `5.0` fuer `/nodes/times/recover` (Bereich 0..10) geklemmt bei `10`.
Das erledigt `Parameter.normalize()` in `server.py`.

Int-Parameter gehen dagegen **unveraendert als Ganzzahl** raus: die
Float-Variante von `RemoteControlledIntParameter.digestMessage` ruft
`intValue()` auf dem Float auf und verstuemmelt den Wert dadurch, bevor
gemappt wird. Ein Float darf an einen Int-Parameter also nie geschickt werden.

Nebenbei: `Master/trace` und `Master/0/opacity/0.Impulse` sind in `mixer.java`
**ohne** fuehrenden Schraegstrich registriert. `python-osc` lehnt solche
Adressen ab, deshalb hat `server.py` einen eigenen, minimalen OSC-Encoder, der
fuer genau diese Adressen einspringt (byte-identisch fuer alle uebrigen, siehe
`test_webui.py`).

## Speed-Kopplung

Aendert man `/net/impulse/speed`, skaliert das UI drei weitere Parameter mit:

| Parameter                        | Referenz bei Speed 160 | Skalierung    |
|----------------------------------|------------------------|---------------|
| `/net/impulse/lifetime`          | 0.2                    | proportional  |
| `/net/impulse/nodeDeadTime`      | 1.0                    | invers        |
| `/net/randomSpawn/interval`      | 3.0                    | invers        |

Mit `faktor = neuer_speed / 160`: proportional heisst `referenz * faktor`,
invers `referenz / faktor`. Ergebnisse werden auf den in
`remoteSettings.txt` genannten Bereich geklemmt; das UI zeigt die tatsaechlich
gesendeten Werte an (die Regler springen sichtbar nach), es wird nichts blind
verschickt. Parameter, die in der Datei fehlen (z. B. `/net/randomSpawn/*` in
einem alten Dump), werden uebersprungen und in der Statuszeile genannt.

Die Kopplung laesst sich oben rechts per **Speed-Kopplung aktiv** abschalten,
wenn Speed mal isoliert geaendert werden soll (die Einstellung merkt sich der
Browser).

> Hinweis: Der Referenzpunkt oben stammt aus dem Brief bzw. `tune_speed.py`.
> Die Konstruktor-Defaults in `LedNetworkTransportEffect.java` stehen aktuell
> auf einem anderen Arbeitspunkt (Speed 16, `lifetime` 0.02,
> `nodeDeadTime` 5.0, `randomSpawn/interval` 30.0).
> `/net/impulse/lifetime` hiess bis 2026-07-31 `/net/impulse/energyDecayfactor`;
> das damalige zweite `/net/impulse/energyDecay` war im Sketch wirkungslos und
> ist ersatzlos entfallen.
> Wer stattdessen diesen Arbeitspunkt koppeln will, aendert nur den Block
> `SPEED_REFERENCE` / `SPEED_COUPLED` oben in `server.py`.

## Installation und Start

Voraussetzung: Python 3.8+ (Windows: „Add python.exe to PATH" beim Installieren
anhaken). Kein Node, kein npm, kein Build-Schritt.

### Windows

```bat
cd C:\Users\birk\imPulse\webui
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python server.py
```

### macOS / Linux

```bash
cd ~/github/imPulse/webui
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python server.py
```

Danach im Browser: `http://100.94.47.6:8080` (Tailscale) oder
`http://<LAN-IP>:8080`. Beim Start ausserhalb des Repos den Pfad zur
Parameterdatei mitgeben.

### Optionen

| Option / Umgebungsvariable                | Vorgabe                     |
|-------------------------------------------|-----------------------------|
| `--settings` / `IMPULSE_SETTINGS`         | `<repo>/data/remoteSettings.txt` |
| `--presets` / `IMPULSE_PRESETS`           | `presets/` neben `--settings`    |
| `--palette` / `IMPULSE_PALETTE`           | `colorPalettes.txt` neben `--settings` |
| `--state` / `IMPULSE_SONG_STATE`          | `songStructureState.txt` neben `--settings` |
| `--osc-host` / `IMPULSE_OSC_HOST`         | `127.0.0.1`                 |
| `--osc-port` / `IMPULSE_OSC_PORT`         | `8001`                      |
| `--host` / `IMPULSE_WEBUI_HOST`           | `0.0.0.0`                   |
| `--port` / `IMPULSE_WEBUI_PORT`           | `8080`                      |
| `--no-autocommit` / `IMPULSE_AUTOCOMMIT=0` | an (siehe „Automatische Sicherung") |
| `--autocommit-interval` / `IMPULSE_AUTOCOMMIT_INTERVAL` | `600` Sekunden |
| `--record-status-port` / `IMPULSE_RECORD_STATUS_PORT` | `8003` (nur `127.0.0.1`) |

`--record-status-port` ist der UDP-Port, auf dem
`/klangnetz/record/status` von sclang erwartet wird — er muss zu
`~recordStatusPort` in `supercollider/klangnetz_bells.scd` passen und
braucht **keine** Firewall-Freigabe: gebunden wird nur auf `127.0.0.1`.

Beispiel:

```bat
.venv\Scripts\python server.py --settings "C:\Users\birk\imPulse\data\remoteSettings.txt" --port 8080
```

### Windows-Firewall

Beim ersten Start fragt Windows nach der Freigabe von Python — fuer das
lokale LAN zulassen. Ueber Tailscale ist keine Freigabe noetig, weil der
Tailscale-Adapter als privates Netz gilt. Falls der Dialog weggeklickt wurde:

```bat
netsh advfirewall firewall add rule name="imPulse WebUI 8080" dir=in action=allow protocol=TCP localport=8080
```

## Als Scheduled Task registrieren

Analog zum bestehenden `ImpulseRun`-Task. `run_webui.bat` liegt neben
`server.py`, aktiviert die venv, startet `server.py` und schreibt alles nach
`webui_run.log` im selben Ordner.

```bat
schtasks /Create /TN WebUiRun /TR "C:\Users\birk\imPulse\webui\run_webui.bat" /SC ONCE /ST 00:00 /RU birk /IT /RL HIGHEST /F
```

Starten, Status pruefen, wieder beenden:

```bat
schtasks /Run /TN WebUiRun
schtasks /Query /TN WebUiRun
schtasks /End /TN WebUiRun
```

Den Pfad in `/TR` an den tatsaechlichen Ablageort des Repos anpassen. `/SC ONCE
/ST 00:00` heisst: der Task laeuft nicht von selbst los, sondern nur auf
`schtasks /Run` — genau wie bei `ImpulseRun`. Die Ausgabe steht danach in
`webui\webui_run.log`.

## Kein Auth

Bewusst ohne Anmeldung und ohne HTTPS: erreichbar ist der Server nur im Tailnet
und im lokalen LAN, beides vertraute Netze. Kein Port ist nach aussen
freigegeben. Wenn der Laptop je in einem fremden Netz haengt, sollte der Server
nicht laufen bzw. mit `--host 127.0.0.1` gestartet werden.

## Tests

`test_webui.py` prueft das, was schiefgehen kann, ohne Flask, ohne python-osc
und ohne laufende Installation: Parsen von `remoteSettings.txt`,
Normalisierung Float/Int, Gruppierung inkl. Farbtripel, die Speed-Kopplung
samt Klemmung und den OSC-Encoder byteweise (inklusive String-Argument fuer
die Preset-Kommandos).

Dazu die Preset-Logik: die Namensregel als Spiegel von
`PresetStore.isValidName()`, das Auflisten des Ordners (Sortierung, `.txt` ab,
ungueltige Namen uebergangen), die Uebernahme einer Preset-Datei in die
Regleranzeige (Klemmung auf die `remoteSettings.txt`-Range, unbekannte
Adressen, Werte ausserhalb der UI-Range) und das Warten auf die von imPulse
geschriebene Datei.

Dazu die Spezial-Sektionen: dass der Sequencer alle sechs Tracks mit allen
Feldern liefert, dass ein aelterer imPulse-Stand `None` statt einer leeren
Sektion ergibt, dass kein Parameter doppelt gerendert wird (an einem echten
Snapshot geprueft) und dass `SC_PARAMS` in beide Richtungen zur `.scd` passt.

Dazu die Tab-Zuordnung: sechs Tabs in der festgelegten Reihenfolge, jede
Adresse in genau einem Tab, die Farb-Adressen in „Farben" und die
Physik-Adressen weiterhin in „Impuls-Verhalten" (die Farb-Regeln stehen vor
den allgemeinen, greifen sie zu breit, leert sich der Physik-Tab lautlos),
und dass **keine** Gruppe aus allen Tabs herausfaellt.

Dazu der Mitschnitt: der OSC-Encoder **und der neue Decoder** byteweise
(Nachricht ohne Argument, Rundlauf mit int/float/String, nicht unterstuetzter
Typ als Fehler statt als falscher Wert), die vier Endpoints gegen ein
**gefaktes sclang** an einem freien Port (Start meldet den Zustand aus der
Antwort, zweites Start oeffnet keine zweite Datei, Stop behaelt den fertigen
Dateinamen, Stop ohne Aufnahme ist kein Fehler, ein fremdes Datagramm aendert
nichts, eine alte Antwort gilt nicht als frische, Schweigen ergibt
„unbekannt"), die Lage der Sektion (Sound-Tab, erste Sektion) und der
Abgleich mit der `.scd`: alle vier Kommandoadressen, die Statusadresse und
**beide** Ports stehen dort genauso.

Dazu die Palette: Parser (Kommentare, kaputte Zeilen, Klemmung auf `0..1`,
doppelter Name gewinnt zuletzt), das Dateiformat (Tabulatoren, Dezimalpunkt),
atomares Schreiben ohne Temp-Rest, die Validierung dessen, was aus dem
Browser kommt (kein Tabulator im Namen, keine NaN, Obergrenze, leere Liste
erlaubt) und die beiden Endpoints. Die Endpoint-Tests brauchen als einzige
Flask und werden sonst uebersprungen — wie schon der python-osc-Gegentest.

Dazu die Melodie-Sektion: alle vier Felder in der vorgesehenen Reihenfolge,
die Obergrenze von `startNode` aus dem Dump statt fest verdrahtet (sie haengt
an der Kreuzungszahl), ein aelterer Stand ohne `/net/melody/*` blendet die
Sektion aus, keine Melodie-Adresse landet zusaetzlich in einem Tab, die
Modusnamen decken sich mit `MelodyModes.java`, und die vier Adressen stehen
in `PresetStore.EXCLUDED`.

```bash
python3 webui/test_webui.py
```

Die automatische Sicherung hat eine **eigene** Suite, ebenfalls ohne
Fremdabhaengigkeiten:

```bash
python3 webui/test_autocommit.py
```

Sie prueft den Porcelain-Parser, das Pfad-Matching, die Commit-Message und die
Ablauflogik gegen einen eingesetzten Git-Runner (ohne echten Prozess) — und
dazu zwei Dinge, die nicht verhandelbar sind:

- **Ein Grep ueber alle `webui/*.py`**, dass nirgends `git add -A`,
  `git add .`, `git commit -a` oder ein `git push` steht. Mit Gegenprobe, dass
  die Muster tatsaechlich anschlagen — ein Test, der nie ausloest, prueft
  nichts.
- **Ein Integrationstest gegen ein echtes, temporaeres Repository**: eine
  geaenderte Preset-Datei *und* eine geaenderte, nicht ueberwachte Datei, die
  obendrein von Hand gestaged ist. Erwartet wird genau ein Commit, der nur die
  Preset-Datei enthaelt, waehrend die fremde Aenderung danach noch schmutzig
  ist. Der Grep prueft die Schreibweise, dieser das Verhalten.

Fehlt `git` auf dem Rechner, wird der Integrationsteil uebersprungen statt zu
scheitern.

**Fuer die UI-Schicht selbst gibt es kein Testgeruest im Repo.** `webui/` soll
ohne Node/npm auskommen (siehe oben), und ein jsdom-Test wuerde genau das
einfuehren. Das Rendering des Sequencer-Panels wurde einmalig headless mit
jsdom gegengeprueft — der Test ist bewusst **nicht** aufgenommen. Wer daran
etwas aendert, prueft im Browser nach.

Eine Ausnahme gibt es, weil sie ohne Fremdabhaengigkeit geht:
`MarkupWiringTest` liest `static/app.js` und `templates/index.html` als Text
und prueft, dass jedes `getElementById()` ein `id=` im Markup findet. Das
faengt den haeufigsten Verdrahtungsfehler — ein Handle, das `null` ist, weil
das Markup nicht mitgezogen wurde. Im Browser faellt das sonst erst als
stumme Seite auf, weil `app.js` seine Listener beim Laden anhaengt.
