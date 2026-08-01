# Baum-Origin-Filter und Web-UI-Tab-Umbau

Entwurf zu Feature 7 und 8 des Briefs
`2026-07-31-brief-night-composition.md`. Birk ist in dieser Session nicht
erreichbar; alle im Brief als „eigenes Urteil" offen gelassenen Punkte sind
hier entschieden und begründet.

Branch: `feature/night-composition-sequencer`. Kein Push, kein Merge.

## Feature 7: Baum-Origin-Filter

### Datenlage (geprüft)

`data/stripeTrees.txt` liegt vor und ist versioniert: 30 Datenzeilen,
`stripeIndex <TAB> baum <TAB> confidence <TAB> distanceMeters`, Kommentarkopf
mit `#`. Verteilung: vorn 8, hinten 8, rechts 7, links 7 — jeder Baum hat
Stripes, kein Filter läuft ins Leere.

### Entscheidung: eigene Klasse `StripeTreeStore`

Parsen **und** Filtern in einer Processing-freien Klasse, wie
`NodeCrossingStore` und `LedAnchorStore`. Der Brief verlangt das ausdrücklich.
Die Klasse hält die Zuordnung und beantwortet „alle Stripe-Indizes für Baum X"
aus einem **beim Laden einmal gebauten** Array — der Sequencer fragt in jedem
Frame, da darf nichts allokiert oder gesucht werden.

### Entscheidung: Nummerierung und Benennung

`0 = kein Filter`, `1 = vorn`, `2 = hinten`, `3 = rechts`, `4 = links` — genau
die Reihenfolge aus dem Brief. Im Code stehen die Namen als
`StripeTreeStore.TREE_NAMES`, nicht als nackte Zahlen; das Web-UI zeigt
Klartext. Die Zahl ist nur die OSC-Darstellung, weil
`RemoteControlledIntParameter` keine Aufzählung kann — dasselbe Muster wie
`noteValue` beim Sequencer.

### Entscheidung: der Pool wird hereingereicht, nicht nachgeschlagen

`OriginSequencer` bekommt **keine** Kenntnis von Bäumen. `TrackConfig`
bekommt ein Feld `int[] originPool` (`null` = alle Stripes), das der Effekt in
`tickSequencer()` aus dem Store füllt; `pickStripe()` zieht daraus.

Begründung: der Sequencer entscheidet *wann* und *von wo*, der Store weiß
*welche Stripes zu welchem Baum gehören*. Würde der Sequencer den Store
kennen, hinge seine Testbarkeit an einer Datei. So bleibt er eine reine
Zeitlogik, und beide Klassen sind einzeln prüfbar.

Das Feld hält eine **Referenz** auf das im Store gecachte Array, keine Kopie —
`tickSequencer()` läuft mit 40 Hz.

### Entscheidung: Vorrangregel (aus dem Brief, hier präzisiert)

In `advanceOrigin()`:

1. `originStripeOverride >= 0` → dieser Stripe, Baum-Filter wird ignoriert.
2. sonst `originTreeFilter` 1..4 → zufällig aus dem Pool dieses Baums.
3. sonst → zufällig aus allen Stripes (heutiges Verhalten).

Der Filter greift **auch beim Nachwürfeln** nach Ablauf von `repeatCount` —
das verlangt der Brief ausdrücklich, und es ergibt sich von selbst, weil
`pickStripe()` der einzige Ort ist, an dem ein Ursprung entsteht.

### Entscheidung: leerer Pool fällt auf „kein Filter" zurück

Hat ein Baum keine Stripes (verkorkste Datei, Tippfehler in einer Baumspalte),
liefert der Store `null` statt eines leeren Arrays, und der Track spawnt wie
ohne Filter. Die Alternative — der Track schweigt — wäre genau der stille
Fehlerzustand, vor dem dieses Projekt an mehreren Stellen warnt: der Operator
schaltet einen Filter ein, nichts passiert, keine Meldung. Gemeldet wird
**beim Laden**, nicht in `drawMe()`; eine Warnung mit 40 Hz wäre unlesbar.

### Entscheidung: fehlende Datei ist kein Abbruch

Wie bei `ledPositions.txt`: eine `WARNUNG`-Zeile in `setup()`, danach läuft die
Show ohne Filter weiter. Ein Baum-Filter ist Gestaltung, keine
Betriebsvoraussetzung.

### Entscheidung: bei doppeltem Stripe gewinnt die LETZTE Zeile

Der Brief sagt ausdrücklich, dass Birk die Datei später von Hand korrigiert.
Die natürliche Handkorrektur ist eine angehängte Zeile am Ende. „Letzte
gewinnt" macht genau das richtig; „erste gewinnt" würde eine Korrektur still
verschlucken. Die Überschreibung wird gemeldet.

`confidence` und `distanceMeters` werden gelesen, aber **nicht ausgewertet** —
der Brief ist da eindeutig: die Unsicherheit ist eine Doku-Markierung für
Birk, kein Laufzeitverhalten. Der Store meldet die Zahl der unsicheren
Zuordnungen beim Laden, damit sie nicht in Vergessenheit gerät.

### Parameter

| Adresse | Typ | Default | Range |
|---|---|---|---|
| `/net/sequencer/track<N>/originTreeFilter` | int | **0** | 0..4 |

Default 0 = heutiges Verhalten, wie jeder neue Parameter in dieser Reihe.

### Tests

`StripeTreeStoreTest`: Parsen (Kommentare, Leerzeilen, Tabs), unbekannter
Baumname wird gemeldet und übersprungen, Index außerhalb 0..nStripes-1
abgelehnt, doppelter Stripe → letzte Zeile gewinnt, `stripesFor` liefert
aufsteigend sortierte Indizes, `stripesFor(0)` und unbekannte Filterwerte
liefern `null`, leerer Baum liefert `null`, fehlende Datei ist kein Absturz,
`confidence` beeinflusst das Ergebnis nicht.

`OriginSequencerTest` bekommt Fälle für den Pool: Ziehung bleibt im Pool, ein
Pool mit einem Element liefert immer denselben Stripe, `null`-Pool verhält
sich wie bisher, Override schlägt Pool.

## Feature 8: Web-UI-Tab-Umbau

### Entscheidung: Tabs sind Server-Daten, Rendering ist Client

`server.py` bekommt eine Tabelle `TABS`: je Tab eine Id, ein Titel, die
Adress-Zuordnung und die kuratierte Liste der „wichtigen" Adressen. `app.js`
baut daraus die Leiste und die Panels.

Begründung: die Zuordnung „welcher Parameter gehört in welchen Tab" ist eine
inhaltliche Entscheidung und gehört dorthin, wo sie testbar ist — die
bestehende Suite `test_webui.py` kann dann prüfen, dass **jede** Adresse aus
`remoteSettings.txt` genau einem Tab zugeordnet ist. Im JS wäre das nur durch
einen jsdom-Test prüfbar, den das Projekt bewusst nicht hat.

### Entscheidung: alle Tabs werden gebaut, umgeschaltet wird die Sichtbarkeit

Der Brief verlangt, dass Preset-Laden und der `applied`/`echoed`-Rücklauf auch
Regler auf **inaktiven** Tabs erreichen. Beides läuft über die flache
`controls`-Map (`address -> handle`). Also: **alle** Panels werden beim
`render()` vollständig gebaut und tragen sich in `controls` ein; der
Tab-Wechsel setzt nur `hidden` auf den Panels. Kein Neu-Rendern beim Wechsel,
kein Lazy-Building — sonst wäre ein Regler auf einem nie geöffneten Tab nicht
in der Map, und ein Preset würde ihn still nicht anzeigen.

Das ist die eine Stelle, an der ein naheliegender Entwurf (Panels erst bei
Bedarf bauen) einen stillen Fehler erzeugt hätte.

### Entscheidung: Tab-Zuordnung

Nach dem Brief, mit meiner Auflösung der Restfälle:

| Tab | Inhalt |
|---|---|
| **Mixer** | `/master/level`, `Master/*/opacity/*`, `Master/trace`, SC: `masterVolume`, `bellVolume`, `droneVolume`, `reverbMix/Room/Damp` |
| **Sound Design** | SC: `brightness`, `detune`, `regionBiasAmount`, `panSharpness`, `droneLpfMult`, alle `travel*` |
| **Spawn-Verhalten** | Sequencer-Panel (inkl. `originTreeFilter`), `/net/randomSpawn/*`, `/net/activateNode`, `/net/activateStripe` |
| **Noten-Verhalten** | Speed-Klassen-Panel (`speedQuantize/*`) |
| **Impuls-Verhalten** | `/net/impulse/speed`, `lifetime`, `nodeDeadTime`, `split*Jitter`, `*/randomize/*`, Impuls-Farbe, `fadeOut`, Node-Parameter, Advanced |

Restfälle, die der Brief nicht nennt:

- **`panSharpness`** → Sound Design. Es ist Klangcharakteristik (Ortungsschärfe),
  keine Lautstärke.
- **Impuls-Farbe und `fadeOut`** → Impuls-Verhalten. Sie beschreiben, wie ein
  Impuls nach dem Spawn aussieht, gehören also zur Schicht „nach dem Spawn".
  Ein eigener Optik-Tab wäre ein sechster, den der Brief nicht will.
- **Node-Parameter** (`nodes/*`) → Impuls-Verhalten, aus demselben Grund.
- **Trigger** (`activateNode`/`activateStripe`) → Spawn-Verhalten: sie *sind*
  ein Spawn-Auslöser.
- **Advanced-Gruppe** (`oscMaxCount`, `energyExponent`) → Impuls-Verhalten,
  im dortigen „Erweitert"-Bereich.

### Entscheidung: was „wichtig" ist

Faustregel des Briefs: was Birk in dieser Session live gefahren hat. Daraus:

- Mixer: `/master/level`, `masterVolume`, `bellVolume`, `droneVolume`,
  `reverbMix` — der Rest (Room/Damp, Opacity, trace) unter Erweitert.
- Sound Design: `travelMix`, `brightness`, `detune`, `regionBiasAmount`,
  `travelRq`, `travelGrainRatio` — die restlichen `travel*` sind Feintuning.
- Spawn: das Sequencer-Panel steht ohnehin oben; von RandomSpawn sind
  `enabled`, `interval`, `energy`, `count` wichtig, `directionBias`/`jitter`
  Erweitert.
- Noten: das Speed-Klassen-Panel ist die ganze Sektion, kein Erweitert nötig.
- Impuls: `speed`, `lifetime`, `nodeDeadTime`, `splitSpeedJitter`,
  `splitLifetimeJitter` oben; Randomizer, Farbe, Nodes, Advanced unten.

### Entscheidung: Presets und Statuszeile bleiben außerhalb der Tabs

Sie gelten für alle Tabs. Ein Preset-Feld, das nur auf einem Tab sichtbar ist,
wäre eine Falle. Kopfzeile, Statuszeile und Preset-Sektion bleiben also über
der Tab-Leiste stehen.

### Entscheidung: der aktive Tab überlebt „Neu laden"

`localStorage`, wie schon bei der Speed-Kopplung. Nach einem
`remoteSettings.txt`-Neueinlesen mitten in der Show soll der Operator dort
stehen, wo er war.

## Nicht gebaut

- **Keine Unter-Tabs im Sound Design.** Der Brief lässt „Tabs oder klar
  getrennte Abschnitte" frei; zwei Abschnitte („Glocke", „Drohne/Travel")
  reichen und ersparen eine zweite Navigationsebene.
- **Keine Änderung an der Rasteranzeige, dem Verteilungsbalken oder den
  bestehenden Widgets.** Der Brief verlangt Wiederverwendung, nicht Neubau.
