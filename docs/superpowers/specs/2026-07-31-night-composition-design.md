# Nacht-Komposition: Split-Varianz, Origin-Sequencer, Klangbias, Travel-Sound

Entwurf zum Brief `2026-07-31-brief-night-composition.md`. Der Brief hat die
Architektur weitgehend festgelegt; dieses Dokument hält die Detailentscheidungen
fest, die er ausdrücklich offen gelassen hat („eigenes Urteil nutzen"), samt
Begründung — und die drei Stellen, an denen der Entwurf bewusst vom Brief
abweicht.

Branch: `feature/night-composition-sequencer`. Kein Push zum Show-Laptop, kein
Merge nach `master`/`grabicz26`.

## Korrektur am Brief: Feature 2b stimmt nur zur Hälfte

Der Brief behauptet, auf `master`/`grabicz26` existiere **kein**
`PresetManager`/`PresetStore`/`PresetScheduler`. Geprüft (`git ls-tree -r master`):

- **Die Java-Seite des Preset-Systems liegt auf `master`** — alle drei Klassen,
  `data/presets/` mit vier Presets, `supercollider/presets/` mit zwei, sowie
  `test/PresetStoreTest.java` und `test/PresetSchedulerTest.java`. `imPulse.pde`
  instanziiert den `PresetManager` und ruft ihn aus `draw()`.
- **Richtig ist nur die zweite Hälfte:** `supercollider/klangnetz_bells.scd` hat
  keinen `/sc/preset/load`-Handler (`grep -c 'sc/preset'` = 0). `PresetManager`
  sendet die Adresse weiterhin an 8002, dort hört niemand zu.

Der zu vermerkende offene Punkt lautet also nicht „Preset-System fehlt", sondern:
**der SC-seitige Preset-Empfänger fehlt; er stand in Commit `e50cd38` und ging
verloren, als am 2026-07-31 die Laptop-Kopie zur kanonischen Datei wurde.** Die
Dateien unter `supercollider/presets/` sind dadurch tot. Das ist genau, was
CLAUDE.md unter „Konventionen und Fallstricke" bereits beschreibt. Nicht
Auftrag dieser Session — nur vermerken.

## Feature 1: Split-Varianz bei Node-Treffern

### Abweichung vom Brief: `decayScale` (Faktor) statt `decayFactor` (Absolutwert)

Der Brief verlangt ein Feld `decayFactor` auf `TravellingActivation`, das den
globalen `impulseLifetime` **ersetzt**: `energy -= timeStep * a.decayFactor`.

Das hätte eine stille Nebenwirkung. Heute wirkt eine Änderung an
`/net/impulse/lifetime` **sofort auf alle lebenden Impulse**, und der
Sinus-Randomizer (`/net/impulse/lifetime/randomize/*`) fährt genau diesen
Parameter in jedem Frame nach. Mit einem absoluten `decayFactor` pro Impuls
würde jeder Impuls den Wert einfrieren, der zu seiner Geburt galt — der
Randomizer könnte nur noch neu gespawnte Impulse beeinflussen, und ein
Operator, der den Lifetime-Regler zieht, sähe die lebenden Impulse
unbeeindruckt weiterlaufen. Beides ohne Fehlermeldung.

Deshalb hält die Aktivierung stattdessen einen **Multiplikator**:

```
energy -= timeStep * impulseLifetime.getValue() * a.decayScale
```

- Normale Spawns (Tube-Trigger, RandomSpawn, Sequencer, `/net/activate*`):
  `decayScale = 1.0` → bitgleich das heutige Verhalten, globale Steuerung und
  Randomizer wirken unverändert auf alle Impulse.
- Split-Kinder: `decayScale = 1 + jitter*(rand*2-1)` → streuende Lebensdauer.
- Filler: erben den `decayScale` ihres Elternimpulses, wie schon die `id`.

Das erfüllt die Absicht des Briefs (Geschwister sterben nicht mehr synchron),
ohne die globale Live-Kontrolle zu verlieren.

### Parameter

| Adresse | Typ | Default | Range |
|---|---|---|---|
| `/net/impulse/splitSpeedJitter` | float | **0** | 0..1 |
| `/net/impulse/splitLifetimeJitter` | float | **0** | 0..1 |

Beide Default 0 = exakt heutiges Verhalten beim ersten Deploy.

### Testbarkeit

Die Jitter-Formel wandert in eine eigene Processing-freie Klasse
`SplitVariance` — dasselbe Muster, aus dem in diesem Projekt schon
`ImpulseOscThrottle` und `ParameterOscillator` herausgezogen sind.
`activationEncounteredNode()` selbst bleibt, wo es ist (es hängt an
`ledNetInfo`, `nodes` und `oscP5`); prüfbar wird die Rechnung, nicht die
Verdrahtung.

`SplitVariance.jitter(base, amount, rand01)` bekommt den Zufall
**hereingereicht** statt `Math.random()` selbst zu rufen — nur so ist die
Formel deterministisch prüfbar. Sie klemmt das Ergebnis nach unten auf einen
kleinen positiven Wert: bei `amount = 1` und `rand01 = 0` wäre der Faktor
sonst exakt 0, ein Kind mit Speed 0 stünde für immer still und ein Kind mit
`decayScale` 0 stürbe nie — zwei unsterbliche Zustände, die das Netz über eine
Nacht volllaufen lassen.

## Feature 2: Origin-Sequencer

### `MusicalClock`: akkumulierende Phase, nicht `(now - start) / beatDuration`

Der Brief sagt „gemeinsame Phase seit einem festen Nullpunkt". Naiv gerechnet
(`beats = (now - t0) / beatDuration(bpm)`) springt die Phase bei jeder
BPM-Änderung: verdoppelt der Operator das Tempo, verdoppelt sich rückwirkend
auch die seit Sketch-Start verstrichene Beat-Zahl, und alle Tracks feuern
schlagartig durcheinander.

`MusicalClock` akkumuliert deshalb inkrementell:

```
beats += (now - lastNow) / beatDuration(bpm)
```

Eine BPM-Änderung ändert damit nur die **Rate**, nie die Position — ein
Tempowechsel mitten in der Show ist ein sauberes Accelerando statt eines
Sprungs. Die Klasse ist ohne Processing, ohne oscP5 und ohne eigene Wanduhr
(Zeit wird hereingegeben), also über `test/run.sh` prüfbar — genau der Stil von
`PresetScheduler`.

Kein Reset bei Preset-Wechsel, wie im Brief gefordert: `MusicalClock` und
`PresetScheduler` wissen nichts voneinander.

| Adresse | Typ | Default | Range |
|---|---|---|---|
| `/net/sequencer/bpm` | float | 60 | 20..200 |
| `/net/sequencer/enabled` | int | **0** | 0/1 |

### Entscheidung: `activeTracks` entfällt, nur `enabled` je Track

Der Brief schlägt beides vor (`/net/sequencer/activeTracks` als grobe Zahl,
`track<N>/enabled` als Feinschalter) und stellt es ausdrücklich frei, das zu
vereinfachen. **Entscheidung: `activeTracks` wird nicht gebaut.**

Begründung:

1. **Zwei Schalter für dieselbe Sache erzeugen einen stillen Fehlerzustand.**
   Der Operator schaltet Track 4 auf `enabled=1`, es passiert nichts, weil
   `activeTracks=3` ihn abschneidet. Kein Fehler, kein Symptom, nur Stille —
   dieselbe Klasse von Falle, vor der CLAUDE.md beim Int-Parameter-Bug schon
   warnt („Stille, die wie funktionierende Software aussieht").
2. **`enabled` ist strikt ausdrucksstärker.** `activeTracks` kann nur ein
   Präfix der Trackliste aktivieren (1..N); mit sechs Einzelschaltern ist jede
   Teilmenge erreichbar, also auch „nur die Halben und die Sechzehntel".
3. **Der grobe Not-Aus existiert schon:** `/net/sequencer/enabled` schaltet
   alle sechs Tracks auf einen Schlag ab.

Kosten: kein einzelner Fader zum „Ausdünnen". Im Web-UI sind das sechs Klicks
statt einem — vertretbar gegenüber einem Schalter, der einen anderen Schalter
unwirksam macht.

### Tracks

Sechs Tracks (`OriginSequencer.TRACK_COUNT = 6`), Parameter unter
`/net/sequencer/track<N>/` mit N = 0..5:

| Suffix | Typ | Default | Range | Bedeutung |
|---|---|---|---|---|
| `enabled` | int | Track 0,1: **1**; 2–5: **0** | 0/1 | Track feuert |
| `noteValue` | int | 1, 2, 4, 8, 4, 8 | 1..16 | Notenwert; Intervall = `4/noteValue` Beats |
| `repeatCount` | int | 3 | 1..8 | Zyklen auf demselben Ursprung, dann neu würfeln |
| `energy` | float | 0.6 | 0..1 | Spawn-Energie je Impuls |
| `swingJitter` | float | **0** | 0..1 | Verjitterung des Track-Intervalls |
| `originStripeOverride` | int | **-1** | -1..nStripes-1 | -1 = zufälliger Ursprung, sonst fixer Stripe |

Das sind 36 Track-Parameter plus zwei globale. Der globale Schalter steht auf
0, die Track-Defaults sind also nur der Zustand, den ein Operator vorfindet,
wenn er den Sequencer erstmals einschaltet — deshalb zwei laufende Tracks
(Ganze und Halbe, ruhig) statt sechs.

`noteValue` erlaubt der Brief nur als 1/2/4/8/16. Der Parameter hat Range
1..16, weil `RemoteControlledIntParameter` keine Aufzählung kann; die
Rasterung auf die fünf erlaubten Werte macht
`OriginSequencer.quantizeNoteValue()` beim Lesen (nächstniedrigerer erlaubter
Wert). Ein Web-UI-Regler, der auf 5 stehen bleibt, verhält sich damit wie 4,
statt ein krummes Intervall zu erzeugen.

`originStripeOverride` ist im Brief nur „nice to have" — es kostet einen
Parameter und eine Zeile und macht den kuratierten Modus möglich, also ist es
drin.

### Track-Logik

Pro Track hält `OriginSequencer` drei Zustandswerte: `nextBeat`, `repeatsLeft`,
`originStripe`.

- **Intervall** in Beats: `4.0/noteValue`, multipliziert mit dem
  Swing-Faktor `1 + swingJitter*(rand*2-1)`, nach unten geklemmt (kein
  Nullintervall durch Jitter — dieselbe Vorsichtsmaßnahme wie beim bestehenden
  `randomSpawnJitter`).
- **Ausgeschaltet**: `nextBeat` läuft mit (`nextBeat = beats + interval`),
  damit das Wiedereinschalten nicht sofort feuert. Dieselbe Regel wie
  `PresetScheduler.isDue()` und `ImpulseOscThrottle.due()`.
- **Kein Nachholen**: liegt `nextBeat` nach einem Hänger mehr als ein Intervall
  zurück, wird es auf `beats + interval` gesetzt statt mehrfach zu feuern. Ein
  im Hintergrund geparktes Fenster darf beim Zurückkommen keinen Schwall
  auslösen — dieselbe Regel, die `ImpulseOscThrottle` schon durchsetzt.
- **Ursprung**: beim ersten Feuern und immer, wenn `repeatsLeft` auf 0 fällt,
  wird ein neuer Zufalls-Stripe gezogen und `repeatsLeft = repeatCount` gesetzt.
  Steht `originStripeOverride >= 0`, gewinnt der immer.

Der Zufall kommt über ein winziges Interface `RandomSource { double next(); }`
herein, damit der Test eine feste Folge vorgeben kann. Im Betrieb ist es
`Math::random`.

`OriginSequencer.update(...)` gibt die Stripe-Indizes zurück, die in diesem
Frame feuern, plus deren Energie. Es baut **selbst keine
`TravellingActivation`** — das bleibt im Effekt, der die Objekte und die
Geschwindigkeit kennt. Damit hängt die Klasse an nichts als der
Java-Standardbibliothek und ist prüfbar.

Gespawnt wird wie in `spawnRandomImpulses()`: am Stripe-Anfang, vorwärts, mit
`impulseSpeed` als Geschwindigkeit (kein eigener Speed-Parameter, gleiche
Begründung wie dort) und `decayScale = 1.0`. Danach läuft alles durch dieselbe
bestehende Node-Kollisions-/Decay-/Render-Pipeline.

Getickt wird aus `drawMe()`, direkt nach `spawnRandomImpulses(currentTime)` —
beide Layer laufen unabhängig nebeneinander.

## Feature 3: Klangbias nach Herkunfts-Region

### Entscheidung zur Zonierung: vier Quadranten

Zur Wahl standen Quadranten (X/Y-Vorzeichen), radiale Ringe (Zentrum/Rand) und
Längsstreifen entlang X. **Entscheidung: vier Quadranten**, Zone =
`(x >= 0 ? 1 : 0) + (y >= 0 ? 2 : 0)`, also 0 = hinten-links, 1 = hinten-rechts,
2 = vorn-links, 3 = vorn-rechts.

Begründung — das entscheidende Argument ist die Lautsprecher-Geometrie: die
vier Boxen stehen auf den **Seitenmitten** von (0,+4), (+7,0), (0,−4), (−7,0).
Jeder Quadrant liegt damit genau zwischen zwei Boxen, und jede Zone hat eine
eindeutige Richtung im Raum. Ortung und Klangfarbe stützen sich gegenseitig:
Birk hört „vorne rechts" **und** „die helle Farbe" und beides meint dieselbe
Netzregion. Genau das ist das Ziel „wiedererkennbare Klangidentität pro Region".

Radiale Ringe scheiden daran aus: „Zentrum" und „Rand" korrelieren mit gar
keiner Lautsprecherrichtung — der Bias wäre hörbar, aber nicht verortbar, und
in der Mitte pannt `~toQuad` ohnehin auf alle vier Boxen gleich (25/25/25/25%),
die Zone mit dem eigensten Charakter läge also genau dort, wo die Ortung am
schwächsten ist. Längsstreifen entlang X werfen die Y-Achse weg und damit die
Hälfte der Fläche eines 14×8-m-Netzes.

Vier Zonen sind außerdem die Zahl, bei der ein Hörer die Zuordnung noch
auswendig behält.

### Abweichung vom Brief: `brightness`/`detune` statt `~tilt`/`~decayScale`

Der Brief nennt `~tilt` (Teilton-Exponent) und `~decayScale` als Timbre-Regler.
**Beide existieren in `klangnetz_bells.scd` nicht** — CLAUDE.md sagt das auch
ausdrücklich („`decayScale`/`tilt` gibt es in der SynthDef derzeit nicht").
Was es gibt, ist funktional dasselbe:

- `~brightness` (0..2) skaliert die oberen vier Teiltöne der Glocke, der
  Grundton bleibt Referenz → hell/dumpf, exakt die Rolle von `tilt`.
- `~detune` (0..1) blendet zwischen unharmonischen und rein harmonischen
  Teiltonverhältnissen → metallisch/orgelartig.

Der Zonen-Bias fährt diese zwei. `~decayScale` wird **nicht** nachgebaut: die
Decay-Zeiten stehen als `#[...]`-Literal in der SynthDef und ein neuer
Multiplikator dort wäre ein Eingriff in den vor Ort getunten Glockenklang, für
den es keinen Auftrag gibt.

### Bias je Zone

| Zone | Lage | Notenoffset (Skalenstufen) | brightness | detune |
|---|---|---|---|---|
| 0 | hinten links | −2 | 0.65 | 1.0 |
| 1 | hinten rechts | 0 | 1.0 | 0.7 |
| 2 | vorn links | +2 | 1.35 | 1.0 |
| 3 | vorn rechts | +4 | 1.7 | 0.4 |

Der Notenoffset zählt in **Skalenstufen**, nicht Halbtönen — er wird auf
`noteIndex` addiert, bevor `~scaleSteps` nachgeschlagen wird. Ein Halbtonoffset
würde Töne außerhalb von Phrygisch erzeugen und den vor Ort gewählten Modus
zerstören.

`amount` (0..1) interpoliert linear zwischen neutral und vollem Bias:
`offset = round(zoneOffset * amount)`, `brightness = 1 + (zoneBright-1)*amount`.
Bei `amount = 0` ist der Klang bitgleich der heutige.

### Parameter — Abweichung vom Brief: `/klangnetz/param/` statt `/sc/regionBias/`

Der Brief schlägt `/sc/regionBias/enabled` und `/sc/regionBias/amount` vor
(„z.B."). Die Datei hat aber bereits eine Parameter-Registry
(`~registerParam`), die jeden Eintrag automatisch mit Default vorbelegt, auf
seine Range klemmt, einen OSCdef anlegt und im Kopf der Datei dokumentiert
ist. Ein zweites, paralleles Adress-Schema mit eigenem Handler wäre eine
zweite Wahrheit für dieselbe Sache. Deshalb:

| Adresse | Default | Range |
|---|---|---|
| `/klangnetz/param/regionBiasAmount` | **0.6** | 0..1 |

Ein separater `enabled`-Schalter entfällt: `amount = 0` **ist** aus, und ein
stufenloser Regler ist live wertvoller als ein harter Schalter, weil sich der
Effekt einblenden lässt, statt zu springen. Default 0.6 folgt dem Brief
(„sanfter Default an", der Bias ist rein additiv und kann nichts stumm
schalten).

## Feature 4: Travel-Sound

### Befund: Es gibt bereits eine Stimme je reisendem Impuls

`klangnetz_bells.scd` fährt schon `\impulseDrone` — einen Synth pro Impuls-ID
aus `/net/impulse`, gebunden über `~drones`, positioniert per `~toQuad`,
amplitudengekoppelt an `energy`, freigegeben über `~droneTimeout` und gedeckelt
durch `~droneLimit = 32`. Das ist exakt die Mechanik, die Feature 4 beschreibt
— nur die Klangfarbe ist ein Dreieck-/Sinus-Ton statt Rauschen. Der
SynthDef-Kommentar sagt sogar „Die Klangfarbe ist ein schlichter Startpunkt und
ausdrücklich zum Ersetzen gedacht — die Mechanik drumherum ist der Teil, der
stimmen muss."

**Entscheidung: der Travel-Sound wird eine zweite Klangschicht *innerhalb* von
`\impulseDrone`, kein zweiter Synth je Impuls.** Ein zweiter Synth bräuchte
ein zweites Dictionary, einen zweiten Reaper, ein zweites Limit und würde die
Stimmenzahl verdoppeln — für ein Feature, dessen erklärtes Ziel es ist, den
Klangbrei zu **vermeiden**. Ein Bandpass-Rauschen im selben Synth kostet drei
UGens und erbt Position, Lag, Hüllkurve, Timeout und Deckel geschenkt.

Der Lebenszyklus, den der Brief fordert („neu bei jedem Split"), ergibt sich
dadurch von selbst und ohne eine Zeile Code: ein Split-Kind ist eine neue
`TravellingActivation` mit neuer `id`, bekommt also einen neuen
Dictionary-Eintrag und einen neuen Synth; der Elternimpuls hört auf, im Strom
gemeldet zu werden, und der Reaper gibt seine Stimme nach `~droneTimeout` frei.

### Entscheidung zum Throttle: kein neuer Parameter

Der Brief stellt frei, einen eigenen `impulseAudioMaxCount` getrennt von
`/net/impulse/oscMaxCount` einzuführen. **Entscheidung: nein.**

Begründung: `oscMaxCount` bestimmt, was überhaupt über den Draht geht. Ein
zweiter, kleinerer Audio-Deckel wäre auf der Java-Seite wirkungslos — die
Klangseite kann ohnehin nur klingen lassen, was sie empfängt, und kann eine
Überzahl selbst ignorieren. Ein zweiter, größerer Deckel wäre unerfüllbar, ohne
mehr zu senden; dann ist `oscMaxCount` hochzudrehen der direkte Weg. Der
Deckel, der wirklich gebraucht wird, sitzt dort, wo die Rechenlast entsteht,
und existiert bereits: `~droneLimit = 32` in SuperCollider, unabhängig von der
Java-Seite gesetzt und im Kommentar ausdrücklich als „Netz unter dem
oscMaxCount der Processing-Seite" beschrieben. Ein dritter Zahlenwert wäre eine
dritte Stelle, die zu den zwei anderen konsistent gehalten werden müsste.

### Abweichung vom Brief: der Ein/Aus-Schalter liegt in SuperCollider

Der Brief nennt `/net/travelSound/enabled` — eine `/net/`-Adresse, also
imPulse. Dort gäbe es aber nichts zu schalten: den Strom gibt es schon, und ob
aus seinen Meldungen Rauschen wird, entscheidet allein die Klangseite. Ein
imPulse-Parameter müsste seinen Zustand erst nach SC melden — ein neuer
Ausgangsweg für null Nutzen. Der Regler liegt deshalb in der SC-Registry.

### Speed-Kopplung: `/net/impulse` bekommt ein fünftes Argument

Die Filterfrequenz soll an die Geschwindigkeit koppeln, aber
`/net/impulse <id> <x> <y> <energy>` trägt keine Geschwindigkeit. Zwei Wege:
SC leitet sie aus Positionsdifferenzen ab (verrauscht, braucht Zustand je ID,
und die Positionen sind bereits `Lag`-geglättet), oder imPulse hängt sie an.

**Entscheidung: anhängen.** Das ist genau das Muster, mit dem `/net/hitNode`
schon um `x`/`y` erweitert wurde — rein additiv, ein Empfänger, der nur die
ersten vier Argumente liest, bleibt unberührt. Gesendet wird
`Math.abs(a.speed)` in LEDs/Sekunde; das Vorzeichen ist Richtung und für die
Klangfarbe bedeutungslos.

Neue Signatur:
`/net/impulse <impulseId:int> <x:float> <y:float> <energy:float> <speed:float>`

SC liest `msg[5]` mit Nullwert-Absicherung, läuft also auch gegen einen älteren
Processing-Stand.

### Regional-Bias im Travel-Sound: Ursprung = erste gemeldete Position

Der Brief will, dass der Whoosh den Timbre-Bias seiner **Herkunfts**-Region
trägt. Der Strom meldet aber die *aktuelle* Position. Statt einen sechsten
Wert (Ursprungskoordinate) mitzuschicken und dafür in Java je Impuls den
Ursprung zu merken: SC berechnet den Zonen-Bias **einmal, beim Anlegen des
Synths, aus der ersten gemeldeten Position** und übergibt ihn als
Start-Argument.

Die erste Meldung eines Impulses kommt aus dem Takt direkt nach seiner
Entstehung, liegt also nahe an seinem Spawn- bzw. Splitpunkt — das *ist* die
Herkunftsregion, ohne ein neues Feld und ohne Zustand auf der Java-Seite. Der
Whoosh behält seine Klangidentität über seine ganze Lebensdauer, auch wenn er
in eine andere Region fliegt. Das ist das im Brief gewünschte Verhalten.

### Parameter (alle in der SC-Registry)

| Adresse | Default | Range | Wirkung |
|---|---|---|---|
| `/klangnetz/param/travelMix` | **0** | 0..1 | 0 = Tondrohne wie heute, 1 = reines Windband |
| `/klangnetz/param/travelFreqMin` | 300 | 100..2000 | Bandpass-Mitte bei Speed 0 |
| `/klangnetz/param/travelFreqMax` | 2500 | 200..8000 | Bandpass-Mitte bei `travelSpeedRef` |
| `/klangnetz/param/travelSpeedRef` | 200 | 10..1500 | LEDs/s, ab der `travelFreqMax` erreicht ist |
| `/klangnetz/param/travelRq` | 0.35 | 0.02..1 | Bandbreite; klein = pfeifend, groß = breit |
| `/klangnetz/param/travelAmpScale` | 1.0 | 0..2 | Trimm nur der Rauschschicht |

`travelMix` ist Schalter und Stärkeregler in einem; **Default 0 = heutiges
Verhalten bitgleich**, wie der Brief es für den Auslieferungszustand verlangt.
`travelAmpScale` gibt es, weil Rauschen bei gleichem Pegel lauter wirkt als ein
Ton und der Crossfade sonst einen Sprung machte.

`travelMix` und `travelRq` wirken auf **laufende** Drohnen (per `onSet`-Callback
über `~drones`, wie `droneLpfMult`) — eine Drohne wird über Sekunden gehalten,
ein Live-Regler daran ergibt hörbar Sinn. Die Frequenz-Ranges wirken erst auf
neue Drohnen, weil die Speed→Frequenz-Umrechnung beim Anlegen des Synths
passiert.

## Nicht gebaut

- **Keine 12h-Makrodramaturgie.** Von Birk explizit abgelehnt, auch nicht als
  deaktivierter Stub.
- **Keine Kopplung MusicalClock ↔ PresetScheduler.** Beide Zeitsysteme bleiben
  getrennt.
- **`/net/sequencer/activeTracks`** — siehe Begründung oben.
- **`/net/sequencer/loadDamping`** (Selbstregulation nach Netzauslastung) — im
  Brief ausdrücklich Nicht-Kernscope und von Birk nie bestätigt. Bleibt als
  offener Vorschlag in der Doku.
- **Der SC-seitige `/sc/preset/load`-Empfänger** — eigener Scope, siehe
  Korrektur oben.

## Tests

Neu, alle Processing-frei und in `test/run.sh` aufzunehmen:

- `SplitVarianceTest` — Jitter-Formel: `amount = 0` liefert exakt den
  Ausgangswert, Symmetrie um den Ausgangswert, Einhaltung der Untergrenze,
  entartete Eingaben (NaN, negativ).
- `MusicalClockTest` — Beat-Akkumulation, kein Sprung bei BPM-Wechsel,
  Intervall je Notenwert, entartete BPM (0, negativ, NaN).
- `OriginSequencerTest` — Feuertakt je Notenwert, `repeatCount` hält den
  Ursprung, Neuwürfeln danach, `originStripeOverride` gewinnt, ausgeschalteter
  Track feuert beim Wiedereinschalten nicht sofort, kein Nachholen nach einem
  Hänger, `quantizeNoteValue()`.

Für die SC-Seite gibt es kein Testgerüst im Repo (CLAUDE.md: „dort gilt
manuelle Prüfung am Gerät"). Der Java-Teil von Feature 1 und die
Stream-Erweiterung werden zusätzlich über `test/build.sh` auf Übersetzbarkeit
geprüft.

## Reihenfolge der Umsetzung

1. `SplitVariance` + Test, Feature 1 im Effekt, `decayScale` durchziehen.
2. `MusicalClock` + `OriginSequencer` + Tests.
3. Sequencer in `LedNetworkTransportEffect` verdrahten, Parameter anlegen.
4. `/net/impulse` um Speed erweitern.
5. `klangnetz_bells.scd`: Zonen-Bias (Feature 3).
6. `klangnetz_bells.scd`: Rauschschicht in `\impulseDrone` (Feature 4).
7. Web-UI-Gruppierung, `CLAUDE.md`, Abschlussbericht.
