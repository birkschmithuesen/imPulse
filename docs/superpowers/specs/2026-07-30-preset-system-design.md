# Preset-System für imPulse und SuperCollider

Entwurf, 2026-07-30. Branch `feature/preset-system`.

## Ziel

Viele benannte Presets, die den **kompletten** Parametersatz der Installation
festhalten — Visuals (imPulse) und Klang (SuperCollider). Jedes Preset ist
unter einem Namen speicherbar und abrufbar über drei Wege: per OSC von außen,
beim Start des Sketches, und über einen eingebauten Scheduler, der in
festen Abständen zum nächsten Preset weiterschaltet.

Komplett statt Diff, weil ein Preset ohne Kontext verständlich sein soll: was
genau lief da. Ein Diff braucht immer eine Basis, und die Basis verschiebt
sich mit jeder Code-Änderung.

## Ausgangslage

Die Installation kennt heute 49 fernsteuerbare Parameter-**Adressen**:

| Herkunft | Adressen | Zusammensetzung |
|---|---|---|
| `LedNetworkTransportEffect` | 19 | 19 Einzelwerte (Float/Int) |
| `LedNetworkNodeEffects` | 26 | 8 Einzelwerte + 6 Farbparameter mit je drei Adressen (`/Hue`, `/Sat`, `/Bright`) |
| `Mixer` | 3 | `Master/trace` plus je eine Opacity für die zwei registrierten Effekte |
| `imPulse.pde` | 1 | `/master/level` |

Jeder `RemoteControlled{Float,Int,Color}Parameter` meldet sich im eigenen
Konstruktor beim statischen `OscMessageDistributor` an; eine
Adress-Zuordnung gibt es nicht, jeder Sink filtert selbst.

`data/remoteSettings.txt` wird bei jedem Start aus den registrierten
Parametern neu geschrieben. Die Datei ist damit ein vollständiger
Wertesatz — nur ohne jede Möglichkeit, ihn wieder einzulesen.

In `scenes/hang_drum_slow/` und `scenes/standby/` liegen zwei solche
Wertesätze als Kopie, live an der Installation verifiziert. Sie sind heute
reine Dokumentation: wer die Szene wieder fahren will, muss die Werte per
externem OSC-Sender einzeln nachschicken. Genau diese Lücke schließt das
Preset-System.

Nebenbefund: `data/remoteSettings.txt` im Repo ist älter als der Code (45
statt 51 Zeilen, die sechs `randomSpawn`-Parameter fehlen). Für den Betrieb
belanglos, die Datei wird bei jedem Start überschrieben — aber die
Szenen-Snapshots sind die verlässlichere Referenz für den Parameterumfang.

## Zwei Befunde, die den Entwurf bestimmen

**Die Float-Normalisierung.** `RemoteControlledFloatParameter.digestMessage()`
mappt einen eingehenden `f`-Wert von `0..1` auf `min..max`
(`AbstractParameter.java:109`). Ein gespeicherter Absolutwert lässt sich
also nicht einfach als Float-OSC zurücksenden: `/net/impulse/nodeDeadTime`
(Bereich 0..10) würde bei gespeichertem `1.0` als `10.0` landen. Ints (`i`)
und Farbkanäle (`/Hue`, `/Sat`, `/Bright`) sind dagegen absolut. Der Loader
darf den OSC-Weg für Werte deshalb nicht benutzen.

**Kommandos, die wie Parameter aussehen.** `/net/activateNode` und
`/net/activateStripe` sind keine `RemoteControlled*Parameter`.
`LedNetworkTransportEffect` implementiert `OscMessageSink` selbst
(Zeile 113/114), **feuert sofort** beim Eintreffen einer solchen Nachricht
und schreibt beide Adressen über sein eigenes `writeToStream()` (Zeile 175)
mit in `remoteSettings.txt`. Ein Snapshot über „alles was ein Sink ist"
würde sie mitnehmen, und jedes Preset-Laden würde einen Node zünden.

Daraus folgt: „komplett" heißt *alle Parameter*, nicht *alle Adressen*.

## Architektur

imPulse ist Master. Es gibt genau **einen** Scheduler, und der läuft in
imPulse. Bei jedem Preset-Wechsel wendet imPulse seinen eigenen Satz an und
sendet zusätzlich `/sc/preset/load <name>` an SuperCollider. Licht und Klang
können damit per Konstruktion nicht auseinanderlaufen; SuperCollider braucht
keine eigene Zeitrechnung.

Der Kanal dafür existiert schon: imPulse sendet auf `127.0.0.1:8002`
(`oscOutput` in `imPulse.pde`), dort hört SuperCollider bereits auf
`/net/hitNode`. Es kommt ein zweiter `OSCFunc` auf **derselben Portnummer,
aber neuer Adresse** hinzu. Der bestehende Listener wird nicht angefasst,
und es braucht keinen neuen Port.

Die Weiterleitung ist fire-and-forget UDP. imPulse wartet auf keine Antwort:
läuft sclang nicht, läuft die Visual-Show trotzdem weiter.

### Vier Bausteine in imPulse

Die Aufteilung folgt dem Muster, das bei `NodeCrossingStore` und
`LedAnchorStore` schon steht: die Teile mit echter Logik bleiben frei von
Processing und Netz, damit sie ohne Sketch-Laufzeit prüfbar sind.

**`PresetStore.java`** — Datei- und Formatschicht, ohne Processing, ohne OSC.
Der Konstruktor bekommt den Preset-Ordner als Pfad übergeben (wie
`NodeCrossingStore` seinen Dateipfad), damit die Tests in ein Temp-Verzeichnis
schreiben können.

Ein **Eintrag** ist durchgehend ein `String[]` mit genau sechs Elementen in
der Reihenfolge der Datei: `{typ, adresse, beschreibung, wert, min, max}`.
Dieselbe Form liefert `presetEntries()` und dieselbe Form nimmt `write()` —
es gibt keine zweite Darstellung.

- `List<String> list()` — Preset-Namen aus dem Ordner, alphabetisch sortiert,
  ohne die Endung `.txt`
- `List<String[]> read(String name)` — die Einträge der Datei. Die Spalten
  Beschreibung, min und max werden gelesen und mitgeführt, beim **Anwenden**
  aber ignoriert: es gelten die Grenzen aus dem laufenden Code, damit ältere
  Presets nach einer Bereichsänderung korrekt bleiben.
- `boolean write(String name, List<String[]> entries)` — atomar über
  Temp-Datei und Rename, wie `NodeCrossingStore.save()`. Einträge werden
  **nach Adresse sortiert** geschrieben, nicht in der unsortierten
  `HashSet`-Reihenfolge — sonst sieht jedes Speichern im `git diff` wie eine
  komplette Umschreibung aus.
- `boolean isValidName(String name)` — erlaubt `[a-z0-9_-]`, Länge 1 bis 64.
  Das ist keine Kosmetik: der Name kommt über OSC von außen, ohne die Prüfung
  wäre `/preset/load ../../../etc/passwd` ein Dateizugriff nach Wunsch des
  Absenders.
- `String lastMessage()` — Begründung bei Ablehnung, gleiche Konvention wie
  im Kreuzungsspeicher.

**`PresetScheduler.java`** — reine Zeitlogik, ohne Processing, ohne Thread.
Die Zeit wird hineingegeben statt innen geholt, damit die Klasse ohne
Wanduhr prüfbar ist:

```java
String due(long nowMillis, List<String> names, boolean enabled, float intervalSeconds)
String next(long nowMillis, List<String> names)   // sofort weiter, unabhaengig von Timer und enabled
```

`due()` gibt den nächsten Namen zurück oder `null`, wenn kein Wechsel fällig
ist. `next()` bedient `/preset/next`: es schaltet sofort weiter und setzt den
Timer zurück, auch wenn der Scheduler aus ist.

Die Position wird **über den Namen** geführt, nicht über einen Index — kommt
eine Preset-Datei dazu oder fällt eine weg, verrutscht die Reihenfolge nicht
mitten in der Show.

Daraus folgt das Verhalten bei einem defekten Preset ohne zusätzliche
Buchhaltung: `due()` hat die Position schon auf den zurückgegebenen Namen
gesetzt, bevor der Manager überhaupt zu laden versucht. Scheitert das Laden,
steht die Position auf dem defekten Eintrag, und der nächste Ablauf geht zum
folgenden weiter. Der Scheduler hängt nicht fest.

**`PresetManager.java`** — die Klebeschicht: hält die Registry der
`PresetTarget`s, führt Save und Load aus, protokolliert auf die Konsole,
leitet an SuperCollider weiter. Diese Klasse darf oscP5 kennen und ist
deshalb **nicht** Teil der Unit-Suite.

**`PresetTarget`** — neues Interface in `AbstractParameter.java`:

```java
interface PresetTarget {
  void presetEntries(List<String[]> out);            // je Adresse eine Zeile
  boolean applyPreset(String address, String value); // absolut, geklemmt; true wenn zustaendig
}
```

Implementiert von `RemoteControlledFloatParameter`,
`RemoteControlledIntParameter` und `RemoteControlledColorParameter` — die
Farbvariante liefert drei Zeilen (`/Hue`, `/Sat`, `/Bright`) und nimmt drei
Adressen an. `LedNetworkTransportEffect` implementiert das Interface
**nicht**; seine zwei Kommando-Adressen können deshalb per Konstruktion
nicht in ein Preset geraten. Kein Ausnahmeliste-Pflegen an dieser Stelle.

Registry: eine neue `ArrayList<PresetTarget>` in `OscMessageDistributor`,
gefüllt in denselben drei Konstruktoren, die schon `registerAdress()` rufen.
Das bestehende `allInstances` (HashSet) und `dumpParameterInfo()` bleiben
unverändert.

### Werte anlegen, ohne den OSC-Weg zu benutzen

`applyPreset` setzt den Wert **absolut** und klemmt ihn auf die im Code
stehenden Grenzen. Kein Mapping, keine Normalisierung, kein
Rundungsrauschen: ein Save nach einem Load erzeugt eine byte-identische
Datei.

Die Threading-Regel bleibt gewahrt. Der Befehl `/preset/load` läuft weiter
durch die bestehende `queueMessage()`/`distributeMessages()`-Kette — dort
wird er aber nur *vermerkt*. Gelesen und angewendet wird in `draw()`, direkt
nach `distributeMessages()`, also im Single-Thread. Aus dem
oscP5-Callback-Thread heraus verändert sich nichts.

## Dateiformat

Preset-Dateien haben **exakt das Format von `remoteSettings.txt`**: sechs
tabgetrennte Spalten `typ`, `adresse`, `beschreibung`, `wert`, `min`, `max`.

Das ist der Grund für die Wahl: die beiden vorhandenen, live verifizierten
Szenen-Snapshots werden per Kopie zu ladbaren Presets. Kein Konvertieren,
kein neuer Parser, keine JSON-Abhängigkeit in einer Klasse, die
Processing-frei bleiben muss — und es passt zu `nodeCrossings.txt` und
`LedAnchorStore`, wo das Repo ebenfalls einfachen Text schreibt.

Ort: `data/presets/<name>.txt`.

Beim Laden zählen nur die Spalten `typ`, `adresse` und `wert`. `min` und
`max` stehen zur Nachvollziehbarkeit drin (und für die Formatgleichheit mit
`remoteSettings.txt`), werden aber ignoriert.

## Preset-Inhalt

Enthalten sind alle 49 registrierten `PresetTarget`-Adressen:
`/net/impulse/*`, `/net/randomSpawn/*`, `/nodes/*`, `Master/trace`,
`Master/N/opacity/…` und `/master/level`.

`/master/level` ist bewusst dabei. Ein Preset kann den Show-Fader also bis
1.0 stellen — konsequent zur Freigabe des Bereichs auf 0..1 vom 2026-07-30.
Der Testbild-Fixpegel `CALIBRATION_MASTER_LEVEL` bleibt davon unberührt, und
`ArtNetOutput.setMasterLevel()` klemmt weiterhin defensiv.

`Master/0/opacity/0.Impulse` trägt Index und Effektnamen im Adresspfad
(`mixer.java:26`). Ändert sich die Effekt-Reihenfolge in `setup()`, passen
ältere Presets an dieser Stelle nicht mehr. Das ist keine neue Schwäche,
aber die Preset-Dateien machen sie erstmals sichtbar — der Loader behandelt
es als unbekannte Adresse und meldet es.

Nicht enthalten:

- `/net/activateNode`, `/net/activateStripe` — Kommandos, strukturell
  ausgeschlossen (siehe Architektur). Der Loader kennt diese zwei Adressen
  zusätzlich als „bekannt, aber bewusst ignoriert", damit eine
  handkopierte `remoteSettings.txt` nicht bei jedem Laden zwei Warnungen
  produziert.
- `/preset/scheduler/enabled`, `/preset/scheduler/interval` — Transport,
  nicht Inhalt. Sie sind echte Parameter und würden sonst mitwandern; ein
  versehentlich mit `enabled=0` gespeichertes Preset würde die Installation
  einfrieren. Ausschluss über eine Adressliste im `PresetManager`.
- Die Netz-Topologie (`nodeCrossings.txt`) — das ist Kalibrierung, nicht
  Gestaltung.

## Die drei Ladewege

### Weg 1: OSC

Empfang auf dem bestehenden Port 8001. Ein `PresetCommandSink` implementiert
`OscMessageSink`, läuft also durch die vorhandene Queue und vermerkt nur;
ausgeführt wird am Anfang von `draw()`. Sein `writeToStream()` schreibt
**nichts** — der Sink hält keine Parameter, und `remoteSettings.txt` darf
keine Kommando-Zeilen dazubekommen.

- `/preset/save <name:string>`
- `/preset/load <name:string>`
- `/preset/next` — ohne Argument, schaltet sofort weiter

`/preset/next` ist kein Luxus: ohne diesen Befehl muss man zum Prüfen des
Schedulers zehn Minuten warten.

Pro Frame wird nur der jeweils letzte Befehl ausgeführt. Zwei Loads im
selben Frame sind ein Bedienfehler, kein Wunsch.

`/preset/list` gibt es **nicht**. `ls data/presets/` beantwortet die Frage
von außen, und ein OSC-Rückkanal wäre neu zu bauen — die einzige
Ausgangsadresse ist heute 8002, also SuperCollider. Bestätigungen und
Fehlermeldungen gehen per `println` auf die Konsole, so wie die
Verifikationsskripte sie schon auswerten.

### Weg 2: beim Start

In `setup()`:

```java
String boot = (args != null && args.length > 0) ? args[0] : System.getenv("IMPULSE_PRESET");
```

Beide Wege, weil die Argument-Weitergabe von `processing-java` nicht
zugesichert ist, eine Umgebungsvariable aus einer `.bat` dagegen immer
funktioniert.

Zeitpunkt: **nach** dem Anlegen aller Effekte — vorher sind die Parameter
nicht registriert — und **vor** dem Schreiben von `remoteSettings.txt`. Dann
zeigt diese Datei nach dem Start den wirklich gefahrenen Stand statt der
Code-Defaults.

### Weg 3: Scheduler

In `draw()` mitgetickt, Zeitbasis `System.currentTimeMillis()` wie beim
Transport-Effekt. Kein eigener Thread.

Zwei neue `RemoteControlled*Parameter`, tauchen damit automatisch in
`remoteSettings.txt` auf:

| Adresse | Typ | Default | Bereich |
|---|---|---|---|
| `/preset/scheduler/enabled` | int | 0 | 0..1 |
| `/preset/scheduler/interval` | float | 600 | 5..3600 |

Reihenfolge: alphabetisch nach Dateiname. Die Liste wird bei jedem Wechsel
frisch gelesen, neue Presets wirken also ohne Neustart.

Beim Einschalten wird **nicht** sofort gesprungen — der Timer läuft ab
jetzt, der Wechsel kommt beim ersten Ablauf. Sonst würde ein Klick auf „an"
mitten in einer laufenden Szene hart umschalten. Für sofortiges
Weiterschalten gibt es `/preset/next`.

Default `enabled = 0`: Weg 2 bestimmt die Startszene, und ein automatisch
mitlaufender Wechsler würde sie nach zehn Minuten wegnehmen. (Bei
`randomSpawn` wurde der Default aus dem umgekehrten Grund auf 1 gestellt,
Commit `c309dc0` — dort *ist* der Automatismus der Inhalt.)

## Fehlerverhalten

Leitgedanke: ein defektes Preset darf die Show nicht anhalten.

| Fall | Verhalten |
|---|---|
| Datei fehlt, Name ungültig | Konsolenmeldung, **alle Werte bleiben stehen**. Der Scheduler geht beim nächsten Ablauf zum folgenden Eintrag weiter (siehe `PresetScheduler`), statt am kaputten festzuhängen. |
| Unbekannte Adresse in der Datei | Zeile übersprungen. Alle übersprungenen Adressen werden **gesammelt in einer Meldung** ausgegeben — nicht schweigend, denn genau das war das Argument für „komplett statt Diff". |
| Registrierte Adresse fehlt in der Datei | Parameter bleibt unangetastet, ebenfalls gesammelt gemeldet. |
| Wert nicht parsbar | Zeile übersprungen, gemeldet. |
| Wert außerhalb der Code-Grenzen | Geklemmt, gemeldet. |
| Scheduler an, keine Preset-Dateien | Einmalige Meldung, danach still. |
| sclang läuft nicht | Weiterleitung geht ins Leere, Visual-Show unbeeinflusst. |

## Startbestand

`data/presets/hang_drum_slow.txt` und `data/presets/standby.txt` als Kopien
der zwei vorhandenen Szenen-Snapshots. Damit ist das System ab dem ersten
Start nicht leer, und die zwei live verifizierten Szenen sind erstmals
wirklich abrufbar statt nur dokumentiert.

Beim Kopieren werden die zwei Kommandozeilen entfernt — die Snapshots sind
rohe `remoteSettings.txt`-Kopien und enthalten sie noch.

Die `scenes/`-Ordner bleiben mit ihren READMEs als Herkunftsbeleg liegen.
Ihr Reload-Hinweis („kein Preset-Loader, Werte per `osc_send.py` erneut
senden") wird angepasst, sobald das System steht.

## SuperCollider

### Neue OSC-Adressen

Empfang auf dem bestehenden Port 8002, nur neue Adressen. Der
`/net/hitNode`-`OSCFunc` wird nicht angefasst. Alle neuen `OSCFunc`s liegen
**im selben einzigen `(...)`-Block** — die dokumentierte Parser-Falle bei
`sclang -D` mit mehreren Top-Level-Blöcken bleibt damit unberührt.

| Adresse | Argument | Bereich |
|---|---|---|
| `/sc/scale/steps` | `<int…>`, variable Zahl | Halbtöne 0..11 |
| `/sc/scale/rootMidi` | `<int>` | 24..96 |
| `/sc/scale/octaves` | `<int>` | 1..6 |
| `/sc/amp/min` | `<float>` | 0..1 |
| `/sc/amp/max` | `<float>` | 0..1 |
| `/sc/bell/decayScale` | `<float>` | 0.1..4 |
| `/sc/bell/tilt` | `<float>` | 0.3..3 |
| `/sc/preset/save` | `<name:string>` | — |
| `/sc/preset/load` | `<name:string>` | — |

`/sc/preset/load` hat zwei mögliche Absender: imPulse bei jedem
Preset-Wechsel, und ein Bediener von außen. Eine Adresse, kein Sonderfall.

Alle Werte liegen weiter in `~`-Environment-Variablen, wie schon heute — nur
zusätzlich per `OSCFunc` überschreibbar.

### Änderung an der SynthDef

Zwei neue Argumente, `decayScale = 1.0` und `tilt = 1.0`:

- `pDecay = partialDecays[i] * decayScale`
- `pAmp = partialAmps[i] ** tilt`

Weil `partialAmps[0]` gleich `1.0` ist, bleibt der Grundton bei jedem `tilt`
gleich laut. `tilt` regelt nur, wie viel von den oberen Teiltönen übrig
bleibt: größer als 1 klingt dumpfer, kleiner brillanter. `decayScale`
streckt oder kürzt alle fünf Teilton-Decays gemeinsam und deckt damit die
Achse ab, die „hang drum slow" von „bells" unterscheidet.

Die `#[…]`-Literale der Teilton-Verhältnisse, -Amps und -Decays bleiben
stehen. Kein SynthDef-Rebuild bei Preset-Wechsel, keine Array-Argumente. Die
Werte gehen pro Note aus `~decayScale`/`~tilt` in den `Synth(…)`-Aufruf.

Umsetzungshinweis: `partialAmps[i]` ist eine Zahl, `tilt` ein Control-UGen.
Die Potenz muss in der **Binäroperator-Form** `partialAmps[i] ** tilt`
stehen, damit sclang die Zahl zum UGen befördert; `Float.pow(einUGen)` ist
dafür nicht verlässlich. Am Gerät gegenprüfen.

### Preset-Dateien in SC

`supercollider/presets/<name>.txt`, dasselbe Tab-Format wie in imPulse — ein
Format über beide Hälften.

Ein SC-Preset enthält genau die sieben Klang-Adressen aus der Tabelle oben:
`/sc/scale/steps`, `/sc/scale/rootMidi`, `/sc/scale/octaves`, `/sc/amp/min`,
`/sc/amp/max`, `/sc/bell/decayScale`, `/sc/bell/tilt`. Die beiden
`/sc/preset/*`-Adressen sind Kommandos und stehen nicht drin — dieselbe
Trennung wie visuell.

Eine Formaterweiterung ist nötig, weil die Tonleiter ein Array ist und kein
Skalar: Typ `ints`, Werte kommagetrennt in der Wertspalte.

```
ints	/sc/scale/steps		0,2,4,7,9	0	11
int	/sc/scale/rootMidi		60	24	96
float	/sc/bell/decayScale		1.0	0.1	4.0
```

Lesen und Schreiben direkt in sclang mit `File` und Tab-Split. Kein JSON,
kein zusätzlicher Quark als Abhängigkeit.

Fehlerfall: fehlende Datei oder unbekannte Adresse → `postln`-Warnung, der
Klang bleibt wie er ist. Wie visuell darf der Wechsel halb gelingen, ohne
dass etwas stehenbleibt.

### Startbestand SC

`supercollider/presets/hang_drum_slow.txt` und `standby.txt` mit zunächst
**identischen** Werten, den heutigen Code-Defaults. Die zwei bestehenden
Szenen unterschieden sich klanglich nicht — `klangnetz_bells.scd` war in
beiden Snapshots gleich. Die Dateien sind der Ausgangspunkt zum
Auseinanderentwickeln, nicht schon zwei verschiedene Klänge.

## Prüfung

Zwei neue Suiten, beide Processing- und OSC-frei und damit in `test/run.sh`
lauffähig. Die Parameterklassen selbst importieren oscP5 und stehen der
Suite nicht zur Verfügung; `PresetStore` wird deshalb gegen ein
**Fake-`PresetTarget`** in der Testdatei geprüft.

**`test/PresetStoreTest.java`**

- Save/Load-Rundtrip: geschriebene und wieder gelesene Werte sind identisch
- Ausgabe ist nach Adresse sortiert
- Zweimal speichern verdoppelt nichts (dieselbe Anforderung wie bei
  `NodeCrossingStore`)
- Namensprüfung: leer, Großbuchstaben, `..`, `/`, Überlänge werden abgelehnt,
  jeweils mit Begründung in `lastMessage()`
- Fehlende Datei
- Nicht parsbarer Wert
- Unbekannte Adresse in der Datei
- Registrierte Adresse fehlt in der Datei
- Die zwei Kommando-Adressen werden still ignoriert, nicht gemeldet
- **Klemmung auf die Code-Grenzen statt der Dateigrenzen**: ein Preset mit
  `/master/level 5.0` muss auf 1.0 landen, unabhängig davon, was in den
  min/max-Spalten der Datei steht

**`test/PresetSchedulerTest.java`**

- Aus heißt kein Wechsel
- Erster Wechsel erst nach Ablauf von `interval`
- Einschalten springt nicht sofort
- Weiterschalten in alphabetischer Reihenfolge mit Umlauf
- Position bleibt korrekt, wenn eine Datei dazukommt oder wegfällt
- Leere Liste
- `next()` schaltet unabhängig vom Timer

Beide Suiten kommen in die optionale Liste in `test/run.sh`.
**`PresetManager.java` nicht** — es importiert oscP5 und würde die Suite
brechen.

Dazu die Übersetzungsprüfung `test/build.sh` für `imPulse.pde` und die
geänderten Klassen (Skill `impulse-verify`).

Für SuperCollider gibt es kein Testgerüst im Repo. Der SC-Teil bleibt bei
manueller Prüfung am Gerät (Save → Load → `~`-Werte per `postln`
vergleichen). Das ist eine bewusste Nachweis-Lücke, hier ausdrücklich
benannt.

## Berührte Dateien

Neu:

- `PresetStore.java`, `PresetScheduler.java`, `PresetManager.java`
- `test/PresetStoreTest.java`, `test/PresetSchedulerTest.java`
- `data/presets/hang_drum_slow.txt`, `data/presets/standby.txt`
- `supercollider/presets/hang_drum_slow.txt`, `supercollider/presets/standby.txt`

Geändert:

- `AbstractParameter.java` — Interface `PresetTarget`, geordnete Registry,
  drei Implementierungen
- `imPulse.pde` — Instanzierung, Boot-Load, `draw()`-Tick, Weiterleitung an
  8002
- `supercollider/klangnetz_bells.scd` — SynthDef-Argumente, neue `OSCFunc`s,
  Preset-Lesen/-Schreiben
- `test/run.sh` — zwei Suiten in der optionalen Liste
- `CLAUDE.md`, `README.md` — Preset-System dokumentieren, Reload-Hinweis in
  den `scenes/`-READMEs anpassen

## Bewusst nicht in diesem Wurf

- **Crossfade zwischen Presets.** Der Wechsel ist ein harter Sprung. Ein
  Blend über alle 49 Parameter ist ein eigenes Thema (welche Parameter sind
  überhaupt interpolierbar — Farben ja, `useRemoteCol` nein).
- **Explizite Reihenfolgen-Datei für den Scheduler.** Alphabetisch ist ein
  robuster Default; wer eine Reihenfolge braucht, kann sie über
  Namenspräfixe erzwingen.
- **`/preset/list` mit OSC-Rückkanal.** Braucht eine neue Ausgangsadresse.
- **Mehrere SynthDefs / Instrumente in SC.** Das ist Klanggestaltung, nicht
  Infrastruktur, und lässt sich schlecht vorab spezifizieren.
- **Presets für die Netz-Topologie.** `nodeCrossings.txt` ist Kalibrierung.

## Offene Abhängigkeit

Kein Merge nach `grabicz26` in dieser Runde. Die Arbeit bleibt auf
`feature/preset-system`, isoliert von der parallel im Haupt-Checkout
laufenden Session. Ein Merge setzt eine Verifikation am Gerät voraus:
`test/run.sh`, `test/build.sh`, dann Save/Load/Scheduler am echten Aufbau.
