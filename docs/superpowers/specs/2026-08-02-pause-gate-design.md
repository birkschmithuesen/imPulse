# Ruhemomente: PauseGate

Umsetzung des Konzepts aus dem Chat (Birk, 2026-08-02): eine dritte, zu
Sequencer und RandomSpawn orthogonale Ebene, die beiden Spawn-Layern
gelegentlich den Mund verbietet, statt selbst Impulse zu erzeugen.

Branch: `feature/pause-gate` (Basis `master`, wo `OriginSequencer`/
`SongStructureDirector` bereits liegen — `grabicz26` hat diese Klassen noch
nicht). Kein Push zum Show-Laptop, kein Merge nach `master`/`grabicz26`.

## Warum eine eigene Klasse statt Erweiterung von Sequencer/RandomSpawn

Eine Pause ist kein dritter Spawn-Typ, sondern eine Maskierung der zwei
bestehenden. `PauseGate` baut selbst keine `TravellingActivation` und kennt
keine Stripes — dasselbe Trennungsprinzip wie `OriginSequencer` und
`SongStructureDirector`: reine Zeit-/Entscheidungslogik, ohne Processing,
ohne oscP5, ohne eigene Wanduhr, Zeit und Zufall werden hereingegeben. Damit
bleibt sie über `test/run.sh` prüfbar (`PauseGateTest`, 673 Prüfungen).

## Mechanik

- Tickt auf `musicalClock.beats()` — derselben Uhr wie der Sequencer. Pausen
  sind damit taktgenau, nicht sekundenbasiert; ein Operator, der in Takten
  denkt (wie beim Notenwert-Raster), bekommt dieselbe Einheit.
- Alle `checkIntervalBars` Takte ein Wurf: mit `probability` startet eine
  Pause, Länge gleichverteilt zwischen `lengthMinBars`/`lengthMaxBars` —
  exakt das Muster von `SongStructureDirector.drawDwell()`, nur eine kleinere
  Zeitskala (Takte statt Minuten).
- **Schon fliegende Impulse laufen unbeeindruckt weiter, splitten, klingen
  aus.** Der Gate greift nur an der Spawn-Quelle: `tickSequencer()` und
  `spawnRandomImpulses()` fragen `pauseGate.blocksSequencer()` /
  `blocksRandomSpawn()` ab, bevor sie einen neuen Treffer erzeugen. Kein
  harter Stop, sondern: keine neuen Anfänge — musikalisch ein Ausklingen
  statt eines Schnitts.
- **Kein Nachholen und kein Zeit-Nachlaufen während einer Pause**, dieselbe
  Vorsichtsregel wie überall sonst im Repo (`ImpulseOscThrottle`,
  `OriginSequencer`, `PresetScheduler`): `tickSequencer()` überspringt bei
  `blocksSequencer()` das komplette Update, `spawnRandomImpulses()` lässt
  `lastRandomSpawnTime` stehen. Direkt nach Pausenende ist also kein
  Intervall schon "verstrichen" — der normale Rhythmus setzt sauber fort,
  statt mit einem Schwall nachzuholen.
- **Während einer laufenden Pause wird nicht neu gewürfelt.** Eine zweite
  Pause kann eine laufende nicht überschreiben oder verlängern — verhindert
  in `PauseGate.tick()` durch einen frühen Return, sobald `paused` schon
  gilt.

## Zwei unabhängige Ziel-Schalter statt einem globalen

`affectsSequencer` und `affectsRandomSpawn` (beide Default an) erlauben:
nur den Sequencer aussetzen (strukturierte Stille, RandomSpawn läuft als
Ambient-Rauschen weiter) oder beide zusammen für echte Totenstille. Das ist
der einzige nicht-triviale Design-Punkt aus dem ursprünglichen Konzept — kein
dritter Modus, nur zwei orthogonale Bits.

## Parameter

| Adresse | Typ | Default | Range |
|---|---|---|---|
| `/net/pause/enabled` | int | **0** | 0/1 |
| `/net/pause/checkIntervalBars` | float | 8 | 1..64 |
| `/net/pause/probability` | float | 0.25 | 0..1 |
| `/net/pause/lengthMinBars` | float | 2 | 0.5..32 |
| `/net/pause/lengthMaxBars` | float | 6 | 0.5..32 |
| `/net/pause/affectsSequencer` | int | 1 | 0/1 |
| `/net/pause/affectsRandomSpawn` | int | 1 | 0/1 |

`enabled` Default 0: dieselbe Regel wie `SongStructureConfig.withDefaults()`
— eine neue Ebene darf eine laufende Show nicht ohne Zutun stumm schalten.
Bei Faelligkeit ohne Treffer (`roll >= probability`) wird beim nächsten
`checkIntervalBars`-Vielfachen erneut gewürfelt, kein Nachlauf.

## Verdrahtung in `LedNetworkTransportEffect`

`drawMe()` schreibt `musicalClock.advance()` jetzt **vor** beiden Spawn-Layern
fort (vorgezogen aus `tickSequencer()`, dort nur noch gelesen — kein
doppeltes `advance()` mehr im selben Frame), liest die Pause-Parameter über
`updatePauseGateConfig()` in den wiederverwendeten `PauseGateConfig` und
tickt den Gate einmal — noch vor `spawnRandomImpulses()` und
`tickSequencer()`, damit beide dieselbe Entscheidung für diesen Frame sehen:

```
applyRandomizers(currentTime);
musicalClock.advance(currentTime, sequencerBpm.getValue());
updatePauseGateConfig();
pauseGate.tick(musicalClock.beats(), pauseGateConfig, mathRandom);

spawnRandomImpulses(currentTime);   // fragt pauseGate.blocksRandomSpawn()
tickSequencer(currentTime);         // fragt pauseGate.blocksSequencer()
releasePendingSplits();
```

`mathRandom` (das schon existierende `RandomSource`-Wrapper um
`Math.random()`) wird wiederverwendet — kein zweiter Zufallspfad.

## Nicht gebaut

- **Keine Kopplung an `SongStructureDirector`.** Ein "ruhiges" Energie-Level
  könnte künftig automatisch `probability` hochziehen — bewusst nicht Teil
  dieser Änderung, wie bei den anderen Ebenen bleiben Zeit-/Zufallssysteme
  hier getrennt.
- **Kein separater Pause-Sound-Hook auf der SC-Seite.** Die Pause wirkt rein
  über Ausbleiben neuer Impulse; ob/wie `klangnetz_bells.scd` auf Stille
  reagiert (z. B. Drohnen-Timeout laeuft ohnehin ab), ist unverändert.

## Tests

`test/PauseGateTest.java`, 673 Prüfungen: `enabled=false` nie pausiert,
Auslieferungswerte, Nullpunkt-Tick feuert nicht, `probability=1`/`=0` an den
Grenzen, unabhängige `affects*`-Schalter, kein Neuwürfeln während laufender
Pause, vertauschte min/max-Länge stürzt nicht ab, Wiedereinschalten feuert
nicht sofort, `NaN`/`null`-Eingaben robust. Volle Suite (`test/run.sh`, 25
Suiten) und `test/build.sh` (kompletter Sketch inkl. `imPulse.pde`) grün.
