# Brief: Nacht-Komposition — Split-Varianz, Origin-Sequencer, Klangbias, Travel-Sound

Kontext: KlangNetz-Installation läuft 12h+ Zyklen, aktuell fühlt sich das
Verhalten repetitiv/zufällig statt musikalisch nachvollziehbar an. Ziel: vier
zusammenhängende Features, alle nach dem etablierten `RemoteControlled*Parameter`-
Muster (siehe CLAUDE.md „OSC-Parametersystem"), jeder Parameter live tunbar,
Defaults konservativ (neue/potentiell störende Effekte defaulten auf `enabled=0`
bzw. minimale Werte), damit ein Sketch-Neustart nichts ungefragt scharf schaltet.

Lies zuerst `CLAUDE.md` im Repo-Root komplett (Architektur, Effekt-Pipeline,
OSC-Parametersystem, Preset-System, Impuls-Simulation) — nicht raten, die
Doku ist aktuell und maßgeblich. Arbeite auf Branch
`feature/night-composition-sequencer` (von `master` abgezweigt, bereits
gepusht). Bitte die komplette superpowers `brainstorming` → `writing-plans` →
Umsetzung-Kette durchlaufen, da hier mehrere Architektur-Entscheidungen mit
Tradeoffs drinstecken (nicht nur ein simpler Bugfix).

## Feature 1: Split-Varianz (Speed + Lifetime) bei Node-Treffern

**Ist-Zustand** (`LedNetworkTransportEffect.activationEncounteredNode()`):
Beim Split an einem Node bekommt jedes Kind exakt `curActivation.speed` (1:1,
kein Zufall) und `childEnergy = curActivation.energy` (volle Energie, kein
Split der Energie) — dadurch sterben Geschwister-Impulse synchron und wirken
identisch.

**Gewünschtes Verhalten:**
- Jedes Kind bekommt eine leicht unterschiedliche Speed:
  `childSpeed = curActivation.speed * (1 + jitter*(random*2-1))`, analog dem
  bestehenden Jitter-Muster in `spawnRandomImpulses()`
  (`jitterFactor = 1f + jitter*(Math.random()*2.0-1.0)`). Neuer Parameter:
  `/net/impulse/splitSpeedJitter` (float 0..1, Default 0 — aus/neutral, damit
  bestehendes Verhalten beim ersten Deploy unverändert bleibt, Operator schaltet
  bewusst zu).
- Jedes Kind bekommt eine **eigene Decay-Rate statt der globalen**, damit
  Lebensdauer streut, ohne die Lautstärke/Helligkeit (Energie) an sich zu
  verändern. Das erfordert: `TravellingActivation` bekommt ein neues Feld
  `decayFactor` (statt in `drawMe()` immer den globalen
  `impulseDecayFactor.getValue()` zu lesen — der Konstruktor-Aufruf für neue
  Impulse übernimmt jetzt einen expliziten decayFactor-Wert, normale/nicht
  gesplittete Neuspawns nehmen weiterhin den globalen Wert 1:1). Beim Split:
  `childDecayFactor = impulseDecayFactor.getValue() * (1 + jitter*(random*2-1))`.
  Neuer Parameter: `/net/impulse/splitLifetimeJitter` (float 0..1, Default 0).
- Beide Jitter-Parameter unabhängig voneinander einstellbar, beide Default 0
  (=identisches Verhalten wie heute, bis der Operator sie hochdreht).
- Bitte auch der bestehenden Testsuite (`test/run.sh`) ein Pendant hinzufügen,
  falls die Split-Logik prozess-frei testbar gemacht werden kann (aktuell ist
  `activationEncounteredNode` an das Processing-Objekt gebunden — wenn eine
  saubere Extraktion in eine testbare Helper-Funktion möglich ist ohne die
  bestehende Architektur zu verbiegen, gerne, sonst manuelle Verifikation
  dokumentieren).

## Feature 2: Origin-Sequencer mit musikalischem BPM-Takt

**Problem:** `spawnRandomImpulses()` zieht bei jedem Event eine komplett neue
zufällige Stripe-Auswahl (`pickDistinctStripes()`) — kein Gedächtnis, jeder
Spawn ist ein Einzelereignis ohne musikalische Wiedererkennbarkeit. Ziel: von
demselben Ursprung wiederholt spawnen erzeugt (fast) dieselbe Melodie
(Knotenpunkt-Kombination) — das soll strukturiert nutzbar werden, OHNE dass
Birk eine komplette Sequenz von Hand schreibt.

**Architektur — neue, Processing-freie Klasse `MusicalClock`:**
- Globaler `/net/sequencer/bpm` Parameter (float, Range **20–200**, sinnvoller
  Default z.B. 60).
- `beatDuration = 60.0 / bpm` Sekunden.
- Hält eine gemeinsame Phase seit einem festen Nullpunkt (Sketch-Start reicht
  als Nullpunkt — **kein** Reset-Kommando bei Preset-Wechsel nötig, das ist
  explizit NICHT gewünscht: Preset-Fades/-Scheduler bleiben komplett
  eigenständig in Sekunden/Minuten, siehe Feature 2b unten — keine Kopplung
  zwischen MusicalClock und PresetScheduler).
- Testbar wie `PresetScheduler` (siehe `feature/preset-system-v2` Branch als
  Referenzmuster für Processing-freie, testgedeckte Scheduler-Logik — bei
  Bedarf `git show feature/preset-system-v2:PresetScheduler.java` als Vorbild
  ansehen, NICHT diesen Branch mergen, nur als Stilvorbild).

**Architektur — neue Klasse `OriginSequencer` mit mehreren Tracks:**
- **6 Tracks** parallel (Default-Anzahl, als Konstante — gerne aber
  `/net/sequencer/activeTracks` (int 1..6) als Parameter, der bestimmt wie
  viele der 6 konfigurierten Tracks gerade aktiv/hörbar sind, Rest pausiert).
- Pro Track (Index 0..5), alle Parameter unter `/net/sequencer/track<N>/...`:
  - `noteValue` (int, erlaubte Werte 1/2/4/8/16 = Ganze/Halbe/Viertel/Achtel/
    Sechzehntel) — Intervall dieses Tracks = `beatDuration * (4.0/noteValue)`.
  - `originStripe` — Startpunkt. **Rotierend-zufälliger Modus als
    Default-Verhalten**: Track zieht bei Erschöpfung eines `repeatCount`
    einen neuen Zufalls-Ursprung, bleibt aber für `repeatCount` Zyklen darauf
    stehen (Parameter `repeatCount`, int 1..8, Default z.B. 3 — "2-3x vom
    selben Ursprung feuern" war Birks expliziter Wunsch). Ein **fix
    kuratierter Modus** (Ursprung manuell statt zufällig gesetzt) ist NICE TO
    HAVE, nicht Kernanforderung — wenn zeitlich machbar, Parameter
    `originStripeOverride` (int, -1 = aus/zufällig, sonst fixer Stripe-Index)
    ergänzen, sonst als offener Punkt in der Doku vermerken.
  - `energy` (float 0..1) — eigene Spawn-Energie je Track.
  - `swingJitter` (float 0..1, Default 0) — **Swing/Jitter ist erwünscht,
    aber Default AUS** (Birk: Choreografie soll primär exakt getaktet sein,
    Jitter ist ein optionaler Live-Regler obendrauf). Gleicher Mechanismus
    wie der bestehende `randomSpawnJitter`
    (`jitterFactor = 1 + jitter*rand(-1,1)`), angewendet auf das Track-Intervall.
  - `enabled` (int 0/1) — Track einzeln an/aus, unabhängig von `activeTracks`
    (activeTracks ist die grobe Zahl, `enabled` der Feinschalter pro Track —
    falls das redundant wirkt, mit eigenem Urteil vereinfachen und in der
    Doku begründen, Hauptsache live nachvollziehbar für den Operator).
- **Globaler Ein/Aus-Schalter** `/net/sequencer/enabled` (int 0/1, Default 0)
  — läuft komplett unabhängig neben dem bestehenden `randomSpawn`-Mechanismus
  (der bleibt als "chaotischer Ambient-Layer" bestehen, der Sequencer ist der
  "strukturierte Layer", beide gleichzeitig aktivierbar).
- Jeder Track-Tick spawnt genau wie `spawnRandomImpulses()` es heute für eine
  einzelne Stripe tut (`new TravellingActivation(...)` an
  `originStripe`-Position), läuft danach durch dieselbe bestehende Node-
  Kollisions-/Energie-Decay-/Render-Pipeline — **keine Parallel-Implementierung
  der Transport-Logik**, nur der Spawn-Trigger-Mechanismus ist neu.
- Ticked aus `drawMe()` heraus, parallel zu (nicht ersetzend) `spawnRandomImpulses()`.

## Feature 2b: SC-Preset-Wechsel funktioniert nicht auf diesem Branch — bitte vermerken, NICHT selbst reparieren

Verifiziert (Hermes, vor diesem Brief): auf `grabicz26`/`master` existiert
**kein** `PresetManager`/`PresetStore`/`PresetScheduler` und `klangnetz_bells.scd`
hat **keinen** `/sc/preset/load`-Handler — das komplette Preset-System liegt
nur auf dem separaten, nie gemergten Branch `feature/preset-system-v2`. Das ist
**kein Auftrag für diese Session** — bitte NICHT versuchen, das Preset-System
hier nachzuziehen oder zu mergen (eigener Scope, eigene Entscheidung von Birk
nötig). Nur in der finalen Doku/im PR-Beschreibungstext als bekannten,
offenen Punkt vermerken: "SC-seitiges Preset-Laden fehlt auf diesem Branch,
liegt fertig auf `feature/preset-system-v2`, noch nicht gemergt."

## Feature 3: Klangbias nach Herkunfts-Region (SuperCollider-Seite)

**Ziel:** Jeder Ursprung soll nicht nur rhythmisch (via Sequencer), sondern
auch klanglich wiedererkennbar sein — Nodes aus unterschiedlichen Netz-
Regionen sollen unterschiedlich klingen (Tonhöhen-Bias und/oder Timbre-Bias).

**Datenlage:** `sendOscMessage()` in `LedNetworkTransportEffect.java` sendet
bei jedem Node-Treffer bereits `/net/hitNode <nodeId> <energy> <posX> <posY>`
an SuperCollider (Port 8002) — `posX/posY` sind Draufsicht-Meter-Koordinaten,
Ursprung Netzmitte. Diese Daten sind SC-seitig also schon im
`~netHitNodeOscFunc`-Handler in `supercollider/klangnetz_bells.scd` verfügbar,
imPulse muss NICHTS Neues senden.

**Umsetzung (SuperCollider-Seite, `klangnetz_bells.scd`):**
- Kleine Mapping-Funktion, die `posX/posY` auf einen Notenoffset und/oder
  Timbre-Offset abbildet. Empfehlung: **diskrete Zonen statt stufenlos**
  (z.B. 3-4 Regionen des Netzes mit je eigenem Charakter) — einfacher
  nachvollziehbar für Birk beim Live-Hören als ein stufenloser Gradient.
  Konkrete Zonierung (z.B. Quadranten um den Ursprung, oder radial
  Zentrum/Rand) nach eigenem Urteil wählen und in der Doku begründen — es
  gibt hier keine vorgegebene exakte Formel von Birk, nur das Ziel
  "wiedererkennbare Klangidentität pro Region".
- Tonhöhen-Bias: Offset auf `~rootMidiNote`/die Notenwahl je nach Zone.
- Timbre-Bias: Variation von `~tilt` (Teilton-Exponent, hell/dumpf) und/oder
  `~decayScale` je nach Zone.
- Als OSC-Parameter exponieren, damit der Effekt live an/ausschaltbar und
  in seiner Stärke tunbar ist, z.B. `/sc/regionBias/enabled` (0/1, Default 1
  — hier reicht ein sanfter Default an, da rein additiv zur bestehenden
  Klanglogik, kein Risiko eines Show-Blackouts) und
  `/sc/regionBias/amount` (float 0..1, wie stark der Bias wirkt, 0=aus).

## Feature 4: Travel-Sound (Windgeräusch/Rauschen während der Impuls-Bewegung)

**Ziel:** Der wandernde Impuls selbst (nicht nur der Node-Treffer) soll einen
Sound erzeugen — ein Rausch-/Windgeräusch, das mit der Bewegung durch die
Leitung mitläuft.

**Architektur-Entscheidungen (von Birk via Hermes bestätigt, alle Hermes-
Vorschläge übernommen):**
- **Kontinuierliche Positions-Kopplung** (nicht nur ein einmalig getriggerter
  Sound bei Spawn): imPulse hat bereits einen gedrosselten Positions-Stream
  `sendImpulseStream()` (`/net/impulse <id> <posX> <posY> <energy>` an Port
  8002, aktuell throttled auf die energiereichsten N Impulse via
  `impulseThrottle.select(...)`/`impulseOscMaxCount`). Der Travel-Sound soll
  auf **denselben, bereits vorhandenen throttled Stream** aufsetzen — KEIN
  neuer, ungethrottelter Stream (Begründung: bei vielen gleichzeitigen
  Impulsen durch Splits sonst potenziell 20-50+ gleichzeitige Klangquellen,
  Klangbrei statt einzeln wahrnehmbarem Rauschen). Falls der aktuelle
  Throttle-Mechanismus (`impulseOscMaxCount`) für dieses Feature zu grob ist
  (z.B. weil er für die Node-Hit-Anzeige gedacht war, nicht fürs
  kontinuierliche Audio-Feintuning), eigenes Urteil nutzen ob ein separater,
  eigener Throttle-Parameter für den Travel-Sound-Stream sinnvoller ist
  (z.B. `impulseAudioMaxCount` getrennt von der bestehenden Anzeige-Drosselung)
  — bitte kurz in der finalen Doku begründen, welche Wahl getroffen wurde.
- **SC-seitig**: Ein Synth pro aktivem (gestreamtem) Impuls, gebunden an
  dessen `id` aus dem Stream. Gefilterte Noise (Bandpass), Filter-
  Cutoff/Frequenz an Speed gekoppelt (schneller Impuls = höherer/schärferer
  "Wind"), Amplitude an `energy` gekoppelt, Panning/Position über
  `posX/posY` genau wie beim Node-Hit-Sound (Whoosh soll im Raum mit dem
  visuell wandernden Impuls mitziehen).
- **Voice-Lebenszyklus: neu bei jedem Split**, NICHT eine durchgängige Voice,
  die über Splits hinweg weiterlebt. Jedes Split-Kind ist ein neuer,
  eigenständiger Impuls (passt zum bestehenden Split-Konzept) und bekommt
  einen neuen Whoosh-Synth; der alte Synth des Elternimpulses endet, wenn der
  Elternimpuls selbst als Objekt endet (Split-Punkt).
- **Regional-Klangbias-Kopplung** (aus Feature 3): der Travel-Sound soll den
  Timbre-Bias seines Ursprungs mittragen (z.B. Filterfarbe je nach
  Herkunfts-Region), falls das ohne unangemessenen Mehraufwand machbar ist —
  sonst als "Phase 2"/Erweiterungsmöglichkeit in der Doku vermerken statt zu
  erzwingen.
- **Parametrisierung** (alle als OSC-Parameter, Default AUS):
  - `/net/travelSound/enabled` (int 0/1, Default 0 — potenziell dauerhaft
    hörbarer neuer Klanglayer, defensiv aus lassen bis Operator bewusst
    zuschaltet)
  - Filterfrequenz-Range, Amplitude-Range als sinnvoll erscheinende
    zusätzliche Tuning-Parameter (nach Vorbild der Bell-Presets, die auch
    Min/Max-Ranges exponieren) — eigenes Urteil für sinnvolle Namen/Defaults,
    Birk hat hier keine exakten Werte vorgegeben.

## Nicht gewünscht (explizit abgelehnt, NICHT umsetzen)

- **Keine 12h-Makrodramaturgie-Hüllkurve** (z.B. ein langsamer Sinus/LFO über
  Stunden auf einen Parameter) — Birk hat diesen Hermes-Vorschlag explizit
  abgelehnt ("überzeugt mich nicht, hinten anstellen"). Nicht bauen, auch
  nicht als deaktivierten Stub.
- Keine Kopplung zwischen `MusicalClock`/Sequencer-BPM und dem bestehenden
  Preset-Scheduler/-Fade — beide Zeit-Systeme bleiben komplett getrennt
  (Preset-Timing bleibt Sekunden/Minuten, Sequencer-Timing ist BPM/Notenwerte).

## Offene Erweiterung, optional falls Zeit reicht (nicht Kernscope)

- Organische Selbstregulation: Spawn-Rate an aktuelle Netzauslastung koppeln
  (viele aktive Impulse → Sequencer/RandomSpawn-Rate dämpfen). War ein
  Hermes-Zusatzvorschlag, von Birk nicht explizit bestätigt oder abgelehnt —
  wenn Zeit reicht, als optionalen, defaultmäßig deaktivierten Mechanismus
  ergänzen (`/net/sequencer/loadDamping` o.ä.), sonst weglassen und in der
  Doku als offenen Vorschlag vermerken statt zu implementieren.

## Feature 5: Web-UI erweitern — alle neuen Parameter bedienbar, gutes Design (nachgeliefert, Birk 2026-07-31)

**Ziel:** Das bestehende Web-UI (`webui/`, Flask, liest `remoteSettings.txt`
und baut Regler automatisch daraus) MUSS die neuen Parameter aus Feature 1
(Split-Jitter), Feature 2 (Sequencer: BPM, activeTracks, pro-Track-Parameter),
Feature 3 (Klangbias-Parameter, falls als eigene `/sc/...`-OSC-Parameter mit
`RemoteControlled*`-Äquivalent auf SC-Seite exponiert — prüfen ob das
bestehende `remoteSettings.txt`-Muster SC-Parameter überhaupt schon abdeckt,
sonst pragmatisch lösen) und Feature 4 (Travel-Sound) sauber bedienbar machen.
Das automatische Auslesen aus `remoteSettings.txt` sollte die neuen
`RemoteControlled*Parameter` bereits automatisch aufnehmen (kein Sketch-Code
nötig) — aber bei 6 Sequencer-Tracks x mehreren Parametern pro Track kommt
eine erhebliche Menge neuer Regler dazu, die ohne UI-Arbeit unübersichtlich
würde. Deshalb explizit als eigenständiges Feature, nicht nebenbei:

- **Neue, eigene UI-Sektion "Sequencer"**: BPM-Master-Regler prominent oben,
  darunter die 6 Tracks klar gruppiert/visuell abgegrenzt (z.B. als Karten
  oder Akkordeon je Track: enabled-Toggle, noteValue als Notenwert-Auswahl
  mit Symbolen/Bezeichnung (Ganze/Halbe/Viertel/Achtel/Sechzehntel) statt
  nackter Zahl, originStripe/repeatCount, energy, swingJitter). activeTracks
  ggf. redundant zu den einzelnen enabled-Toggles — im Zweifel EINE klare
  UI-Lösung wählen (z.B. nur die einzelnen Track-Toggles zeigen, activeTracks
  als Hintergrund-Parameter weglassen aus der UI falls das die Sequencer-
  Implementierung ohnehin schon vereinfacht hat, siehe Feature 2 Redundanz-
  Hinweis).
- **Neue Sektion "Split-Verhalten"**: splitSpeedJitter, splitLifetimeJitter
  mit kurzer Erklärung/Tooltip was sie tun (nicht nur Adresse+Zahl wie
  aktuell generisch).
- **Neue Sektion "Travel-Sound"**: enabled-Toggle prominent, Filterfrequenz-/
  Amplitude-Ranges darunter.
- **Klangbias-Region-Parameter** (`/sc/regionBias/...`) mit eigener Sektion,
  falls SC-Parameter technisch überhaupt in `remoteSettings.txt` abgebildet
  werden — falls SC-seitige Parameter aktuell NICHT durchs bestehende
  imPulse-`remoteSettings.txt`-System laufen (separate Systeme!), pragmatisch
  im Web-UI eine eigene, einfache Sektion dafür anlegen (kann auch ein
  separates kleines OSC-Sende-Formular sein, muss nicht zwingend das
  automatische Parsing-Muster nutzen wenn das architektonisch nicht passt —
  eigenes Urteil, aber nicht einfach weglassen).

**Design-Anspruch — ausdrücklich hervorgehoben (Birk: "richtig schönes, cooles
GUI", "gutes Design", "Energie reinstecken, dass das cool zu bedienen ist"):**
Das ist NICHT nur "Regler generisch aus Datei rendern" wie bisher, sondern ein
bewusster Design-Pass für genau diese neue Sequencer/Sound-Oberfläche:
- Klare visuelle Hierarchie (Master-BPM groß/prominent, Tracks darunter klar
  gruppiert, nicht eine lange flache Liste von 30+ gleich aussehenden Reglern).
- Sinnvolle Icons/Symbole für Notenwerte (♩ ♪ etc. oder vergleichbare visuelle
  Kennzeichnung) statt nackter Zahlen 1/2/4/8/16.
- Ansprechendes, dunkles/für Live-Bedienung im Dunkeln geeignetes Farbschema
  passend zur restlichen Installation (falls das bestehende Web-UI schon ein
  Farbschema hat, das aufgreifen/konsistent erweitern statt einen Stilbruch
  zu erzeugen — bestehendes `webui/`-Design zuerst ansehen).
- Live-Feedback wo sinnvoll (z.B. optisch andeuten, welcher Track gerade
  "dran" ist / feuert, falls technisch ohne unangemessenen Aufwand machbar
  über die schon bestehende Update-Mechanik der Seite — nice-to-have, nicht
  Kernanforderung, wenn's den Rahmen sprengt weglassen und in Doku vermerken).
- Gerne die `visual-analysis`/`popular-web-designs`/`claude-design`-artigen
  Prinzipien anwenden falls es hilft (Claude Code muss diese Hermes-Skills
  nicht kennen, aber das Ziel ist ein durchdachtes, nicht generisches UI —
  eigenes gutes Urteil zu Typografie/Spacing/Kontrast einbringen).

Dieses Feature ist **gleichwertig zu Features 1-4**, nicht optional — bitte
in denselben Umsetzungs-Durchlauf integrieren, eigener Commit oder Commit-
Serie dafür.

## Feature 6: Impuls-Speed rhythmisch quantisiert an den Master-Takt, mit seltenen Speed-Ausreißern (nachgeliefert, Birk 2026-07-31)

**Kernidee:** Nicht nur WANN gespawnt wird soll rhythmisch/BPM-getaktet sein
(Feature 2), sondern auch WIE SCHNELL der einzelne Impuls reist. Aktuell ist
`impulseSpeed` ein einziger globaler Wert für alle Impulse (siehe Feature 1 —
Split-Kinder übernehmen ihn 1:1). Neu: die Speed eines gespawnten Impulses
soll selbst ein rhythmisches Vielfaches/Bruchteil eines Master-Speed-Werts
sein, mit einer bewussten Wahrscheinlichkeitsverteilung für Ausreißer.

**Verhalten:**
- Es gibt einen **Master-Speed-Grundwert** (Referenzpunkt = eine Viertelnote-
  Geschwindigkeit auf dem globalen `impulseSpeed`-Parameter — ODER als eigener
  neuer Referenz-Parameter, falls das sauberer von `impulseSpeed` selbst
  getrennt werden sollte, damit die bestehende Zeitbasis-Kopplung
  [`energyDecay`/`nodeDeadTime`/`randomSpawnInterval`, siehe CLAUDE.md
  „Zeitbasis-Kopplung"] nicht durcheinanderkommt — eigenes Urteil, aber bitte
  in der Doku klar benennen welcher Parameter die Referenz ist).
- **Standardfall** (hohe Wahrscheinlichkeit): Impuls spawnt mit dieser
  Referenz-Speed (die "Viertelnote"-Geschwindigkeit).
- **Seltener Fall** (konfigurierbare, niedrige Wahrscheinlichkeit): Impuls
  spawnt mit einem rhythmischen Vielfachen der Referenz-Speed — z.B. 2x, 4x,
  8x so schnell (oder auch langsamer, z.B. 1/2x) — spiegelbildlich zu den
  Notenwerten aus Feature 2 (Ganze/Halbe/Viertel/Achtel/Sechzehntel als
  Speed-Multiplikator-Stufen, nicht nur als Zeit-Intervall-Stufen). Beispiel
  aus Birks Beschreibung: von z.B. 12 gespawnten Impulsen ist einer deutlich
  schneller (4x oder 8x) als der Rest — bewusst geraten selten/besonders,
  kein 50/50.
- **Parametrisierung:** pro Speed-Multiplikator-Stufe (z.B. 1x/2x/4x/8x,
  ggf. auch 0.5x als "langsamer" Ausreißer) ein Wahrscheinlichkeits-Gewicht,
  Summe/Normalisierung so wählen dass 1x der weit überwiegende Normalfall
  bleibt (Birk nennt keine exakten Zahlen — sinnvolle Defaults setzen, z.B.
  1x=85%, 2x=10%, 4x=4%, 8x=1%, und als OSC-Parameter tunbar machen, nicht
  hart kodieren).
- **Jitter/Swing bleibt zusätzlich erhalten** — die quantisierte
  Speed-Vielfache bekommt zusätzlich denselben optionalen Jitter-Mechanismus
  wie in Feature 1/2 (`speedQuantizeJitter` o.ä., Default 0), damit auch
  hier "Choreografie primär exakt, Swing optional" gilt.
- **Kopplung an Travel-Sound (Feature 4) — WICHTIG, das ist der eigentliche
  Zweck laut Birk:** Der Travel-Sound-Charakter MUSS erkennbar mit der
  tatsächlichen Speed des jeweiligen Impulses variieren (nicht nur generisch
  "Filterfrequenz an Speed gekoppelt" wie in Feature 4 schon grob
  beschrieben, sondern hier nochmal als Ziel geschärft): ein 4x/8x-schneller
  Ausreißer-Impuls MUSS sich im Klang klar und eindeutig von den
  Standard-Speed-Impulsen unterscheiden lassen — Birks Ziel ist explizit
  **Zuordenbarkeit**: beim Hören soll klar sein, welcher Klang zu welchem
  (schnellen/langsamen) Impuls gehört. Das ist keine neue Kopplung, sondern
  eine Verschärfung der Anforderung an die in Feature 4 bereits vorgesehene
  Speed→Filterfrequenz-Kopplung: bitte sicherstellen, dass der hörbare
  Unterschied bei den Speed-Multiplikator-Stufen (1x/2x/4x/8x) deutlich und
  nicht nur marginal ist (z.B. deutlich hörbarer Frequenz-/Tonhöhensprung
  pro Speed-Stufe, nicht nur ein sanfter linearer Gradient).
- Gilt für alle Spawn-Quellen (Sequencer-Tracks aus Feature 2, `randomSpawn`,
  `/tube/trigger`) — sinnvollerweise als gemeinsame Speed-Auswahl-Funktion
  implementieren, die von allen Spawn-Pfaden genutzt wird, statt dreifach
  Logik zu duplizieren.
- Split-Verhalten (Feature 1) bleibt wie dort beschrieben on top: ein bereits
  mit z.B. 4x gespawnter Impuls durchläuft beim Split zusätzlich noch
  `splitSpeedJitter` auf seiner (schon vervielfachten) Speed.

Bitte auch für Feature 6 die neuen Parameter im Web-UI (Feature 5) sichtbar/
tunbar machen: Wahrscheinlichkeits-Gewichte pro Speed-Stufe als eigene kleine
Sektion, gerne mit visueller Verteilungs-Vorschau falls das ohne großen
Aufwand machbar ist (nice-to-have, nicht Kernanforderung).

## Feature 7: Baum-Origin-Filter für Sequencer-Tracks (nachgeliefert, Birk 2026-08-01)

**Ziel:** Bisher kann ein Sequencer-Track per `originStripeOverride` auf einen
festen Stripe eingeschränkt werden (-1 = zufällig unter allen 30 Stripes).
Birk will zusätzlich auf Baum-Ebene filtern können: "nur von diesem einen
Baum" oder "nur vom nächsten Baum" — es gibt 4 physische Bäume, an denen
Gruppen von Stripes beginnen.

**Datengrundlage (bereits vorbereitet):** `data/stripeTrees.txt` — neue Datei,
Format `stripeIndex<TAB>baum<TAB>confidence<TAB>distanceMeters`, `baum` einer
von `vorn|hinten|rechts|links`. Automatisch per Nächster-Nachbar aus
`data/ledPositions.txt` generiert (erster LED-Anker jedes Stripes ↔ vier
berechnete Baumpositionen). **Enthält bewusst Best-Guess-Zuordnungen** — 8 von
30 Stripes sind mit `confidence=unsicher` markiert (Distanz > 1.5m zum
zugeordneten Baum). Birk korrigiert diese Datei später von Hand — das Feature
MUSS die Datei zur Laufzeit lesbar/austauschbar halten, keine Zuordnung fest
im Java-Code verdrahten.

**Gewünschtes Verhalten:**
1. Neuer Parameter je Sequencer-Track: `/net/sequencer/track<N>/originTreeFilter`
   — Werte: 0 = kein Filter (aktuelles Verhalten, alle Stripes bzw.
   `originStripeOverride` greift wie bisher), 1..4 = nur Stripes zulassen,
   die in `stripeTrees.txt` dem jeweiligen Baum zugeordnet sind (feste
   Reihenfolge z.B. 1=vorn, 2=hinten, 3=rechts, 4=links — im Code klar
   benannt, nicht nur nummeriert, Web-UI zeigt Klartext-Labels).
2. Zusammenspiel mit dem bestehenden `originStripeOverride`: wenn
   `originStripeOverride >= 0` gesetzt ist, hat das Vorrang (expliziter
   Stripe schlägt Baum-Filter) — `originTreeFilter` wirkt nur, wenn
   `originStripeOverride == -1` UND schränkt dann den Pool der zufällig
   wählbaren Stripes auf die des gewählten Baums ein, statt aus allen 30 zu
   ziehen. Wenn ein Baum-Filter aktiv ist UND der Track laut
   "rotierend mit Gedächtnis"-Logik (siehe `OriginSequencer`, Modus 2 aus
   Feature 2) einen neuen Ursprung zieht, muss die Ziehung aus dem gefilterten
   Pool erfolgen.
3. `stripeTrees.txt` einmalig beim Sketch-Start einlesen (analog
   `LedAnchorStore`/`data/ledPositions.txt` Muster), Zeilen mit
   `confidence=unsicher` genauso behandeln wie sichere — die Unsicherheit ist
   nur eine Doku-Markierung für Birk, kein Laufzeitverhalten-Unterschied.
   Kommentarzeilen (`#`) überspringen.
4. Testbarkeit: eine reine Java-Klasse (analog `LedAnchorStore`,
   `NodeCrossingStore`) für das Parsen von `stripeTrees.txt` + die
   Filter-Logik ("gib mir alle Stripe-Indizes für Baum X"), mit eigener
   Testsuite nach demselben Muster wie die bestehenden (`test/run.sh`
   erweitern, siehe CLAUDE.md „Tests"-Abschnitt für das exakte Muster).

## Feature 8: Web-UI — kompletter Tab-Umbau (nachgeliefert, Birk 2026-08-01)

**Ist-Zustand:** Der Sequencer-Bereich ist bereits gut designt (Feature 5),
aber der Rest der Oberfläche ist eine unstrukturierte, generische Liste aller
`remoteSettings.txt`- und SC-Parameter — für einen Live-Operator zu
unübersichtlich, "zu viel auf einmal".

**Gewünschte Struktur — 5 Tabs, in dieser Reihenfolge:**

1. **Mixer** — der Schnellzugriff-Tab. Gesamthelligkeit (Master/opacity-
   Parameter), Gesamtlautstärke (`/master/level` bzw. das SC-Äquivalent
   `masterVolume`), Bell-Volume, Drone-Volume, Reverb (Mix/Room/Damp). Alles,
   was ein Operator während einer laufenden Show am häufigsten anfasst.
2. **Sound Design** — Klangcharakteristik, NICHT Lautstärke (die ist im
   Mixer). Unter-Struktur (Tabs oder klar getrennte Abschnitte) pro Synth:
   - Glocken (Bell): brightness, detune, regionBiasAmount
   - Drohne/Travel: droneLpfMult, travelFreqBase/Rq/GrainRatio/AmpScale,
     travelSpeedRef, travelOctavesPerStep, travelSnap, travelFreqMin/Max
3. **Spawn-Verhalten** — wo/wann ein Impuls entsteht: der bestehende
   Sequencer-Bereich (BPM, 6 Tracks mit Enabled/NoteValue/RepeatCount/Energy/
   SwingJitter/OriginStripeOverride, plus NEU: originTreeFilter aus Feature 7)
   sowie RandomSpawn (enabled/count/interval/jitter/energy/directionBias) —
   beide Spawn-Mechanismen gehören konzeptionell zusammen (Birk: "wie
   gespawnt wird, also Sequencer oder Randomizer").
4. **Noten-Verhalten** — die musikalisch-diskrete Schicht: SpeedQuantize
   komplett (enabled, jitter, die 5 Gewichte 0.5x/1x/2x/4x/8x) — bewusst
   getrennt von Tab 5, weil das quantisierte, "notenartige" Geschwindigkeits-
   klassen sind, kein kontinuierlicher Physik-Parameter.
5. **Impuls-Verhalten (Physik)** — die kontinuierliche Simulation NACH dem
   Spawn: impulseSpeed (Grundwert), impulseLifetime + dessen Sinus-Randomizer
   (enabled/min/max/period), nodeDeadTime, splitSpeedJitter,
   splitLifetimeJitter, speed/randomize/* (falls vom Speed-Grundwert
   getrennt, siehe bestehende Parameterliste in `remoteSettings.txt`).

**Innerhalb jedes Tabs:** oben eine kuratierte Auswahl der wichtigsten/
meistgenutzten Regler direkt sichtbar, darunter ein eingeklappter
"Erweitert"-Bereich (wie das bestehende Muster bei der `Advanced`-Gruppe,
`<details>`/`<summary>`) für alle übrigen Parameter desselben Themenbereichs.
Eigenes Urteil, welche Parameter je Tab "wichtig" sind — Faustregel: alles,
was Birk in dieser Session tatsächlich live per OSC verändert hat (siehe
Session-Historie: bellVolume, droneVolume, masterVolume, travelMix,
brightness, detune, sequencer/bpm, sequencer/trackN/energy, randomSpawn/*)
gehört oben hin.

**Technische Vorgabe:** Vanilla JS wie bisher (kein Framework/Build-Schritt,
siehe bestehende `app.js`-Kommentare), Tab-Umschaltung rein clientseitig
(keine Route/Reload nötig), bestehendes Preset-Laden/-Speichern und die
Live-Werte-Synchronisation (`applied`/`echoed`-Mechanismus) MÜSSEN über alle
Tabs hinweg weiter funktionieren — ein Preset kann Werte auf mehreren Tabs
gleichzeitig setzen, die Regler auf inaktiven Tabs müssen trotzdem im
Hintergrund aktualisiert werden (nicht erst beim Tab-Wechsel neu rendern).
Bestehende Farbwähler/Trigger/Toggle-Widgets aus `app.js` wiederverwenden,
nicht neu erfinden.

## Deliverables

1. Alle vier Features (1, 2, 3, 4) implementiert nach obigem Muster.
2. `CLAUDE.md` aktualisiert (neue Klassen, neue OSC-Parameter-Tabelle analog
   dem bestehenden `randomSpawn`-Abschnitt, Feature-2b-Hinweis zum fehlenden
   SC-Preset-Handling auf diesem Branch).
3. Bestehende Testsuite (`test/run.sh`) bleibt grün; neue Processing-freie
   Logik (MusicalClock, OriginSequencer-Kernlogik ohne Processing-Bindung)
   nach Möglichkeit mit eigenen Tests, analog `PresetSchedulerTest`-Stil vom
   Referenz-Branch.
4. Commits auf `feature/night-composition-sequencer`, **kein Push zum
   Windows-Laptop, kein Merge nach `master`/`grabicz26`** — das entscheidet
   Birk nach eigener Sichtung/Testen. Am Ende: `git log --oneline` der neuen
   Commits + kurze Zusammenfassung was gebaut wurde und was (falls vorhanden)
   bewusst nicht/nur teilweise umgesetzt wurde.
