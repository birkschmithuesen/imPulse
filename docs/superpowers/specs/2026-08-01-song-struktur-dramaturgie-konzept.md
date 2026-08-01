# Song-Struktur-Dramaturgie über lange Zeiträume (Konzept, kein Code)

Entwurf, 2026-08-01. Branch `docs/song-structure-concept`. Auftrag von Birk:
eine große kompositorische Dramaturgie über eine ganze Nacht / mehrere Stunden,
basierend auf den bestehenden Presets, in Energie-Leveln gedacht, mit
gewichtetem Zufall statt festem Intro-Refrain-Outro-Loop — dynamische und
entspannte, dramatische und entspannte Parts sollen sich abwechseln.

Dieses Dokument ist **reines Konzept**. Es wird nichts implementiert, keine
Klasse, kein Parameter, kein Preset angelegt. Ziel ist eine Entscheidungs­
grundlage für Birk, mit konkreten (aber ausdrücklich vorläufigen)
Beispielwerten.

## Ausgangslage: was der Code heute schon kann

Gelesen: `PresetStore.java`, `PresetScheduler.java`,
`docs/superpowers/specs/2026-07-30-preset-system-design.md`, der
Preset-Abschnitt in `CLAUDE.md` (Zeile 451ff.), sowie zwei Beispiel-Presets
(`random1.txt`, `standby.txt`).

**`PresetScheduler.java` ist reine Zeit- und Reihenfolgelogik, kein
Entscheider.** Er kennt drei Operationen:

- `isDue(nowMillis, enabled, intervalSeconds)` — ist der Wechsel jetzt fällig?
- `advance(nowMillis, names)` — geht zum **nächsten Namen in der übergebenen
  Liste** weiter (Position wird über den Namen geführt, nicht über einen
  Index — robust gegen Dateien, die dazukommen oder wegfallen)
- `noteLoaded(name, nowMillis)` — vermerkt, was gerade läuft, und setzt den
  Timer

Der Scheduler kennt **weder Energie noch Dramaturgie noch Zufall.** Er läuft
strikt alphabetisch durch `data/presets/*.txt` (`PresetManager` liefert die
Namensliste, `CLAUDE.md`: „Reihenfolge alphabetisch nach Dateiname"), mit
einem einzigen festen Intervall (`/preset/scheduler/interval`, Default 600s
= 10 min, Bereich 5..3600s). Das ist exakt der „feste Loop", den der Auftrag
ablösen soll — nicht weil er schlecht gebaut ist, sondern weil er für eine
12h-Dauerinstallation zu vorhersehbar ist: nach spätestens `n × 10 min` (n =
Anzahl Presets) wiederholt sich die Reihenfolge exakt.

**Ein Preset ist ein kompletter Wertesatz** aller ~50 fernsteuerbaren
Adressen (`/net/impulse/*`, `/net/randomSpawn/*`, `/net/sequencer/*`,
`/nodes/*`, `Master/*`, `/master/level`), gespeichert als
`data/presets/<name>.txt`, sechs Tab-Spalten. Es gibt **keine Metadaten-Spalte
und keine Preset-Metadatei** — jedes Preset ist reine Parameterliste, keine
Energie- oder Stimmungsangabe irgendwo vermerkt.

## 1. Energie-Level-Klassifikation

### Schema: vier Level

An Musik-/DJ-Set-Praxis orientiert (Spannungsbogen, nicht Playlist-Kategorie),
bewusst **vier** statt drei oder fünf Stufen:

| Level | Charakter | Analogon |
|---|---|---|
| **ruhig** | wenig Bewegung, lange Lebensdauern, kaum Split-Aktivität, dunkler/wärmer | Ambient-Intro, Break, Outro |
| **mittel** | moderate Dichte, spürbarer Puls, aber kein Drängen | Aufbau, Groove-Plateau |
| **dynamisch** | hohe Spawn-Rate, kurze Lebensdauern, viel Bewegung im Netz | treibender Refrain |
| **dramatisch** | Maximalausschlag: hohe Energie, schnelle Sequenzen, intensive Farben/Helligkeit | Drop, Klimax |

Drei Level (nur ruhig/mittel/dynamisch) wären zu grob für den vom Auftrag
explizit verlangten Kontrast „dramatisch vs. entspannt" — es braucht einen
klar abgesetzten Höhepunkt, der sich von einem bloß „aktiven" Zustand
unterscheidet. Fünf oder mehr Level (z. B. zusätzlich „aufbauend"/
„abklingend" als eigene Zustände) würden die Übergangsmatrix unnötig
komplex machen, ohne dass aktuell genug unterschiedliche Presets existieren,
um sie zu füllen (siehe Abschnitt 6). Vier ist der pragmatische Kompromiss:
genug Auflösung für einen echten Spannungsbogen, wenig genug, um die Matrix
von Hand pflegbar zu halten.

### Automatisch ableiten oder manuell taggen?

**Empfehlung: manuelles Tagging über eine neue, separate Metadatei — nicht
automatische Ableitung aus den Preset-Werten.**

Abwägung:

**Automatische Ableitung** (z. B. Score aus `/net/randomSpawn/energy` +
`/net/randomSpawn/count` + `/net/sequencer/bpm` + Sequencer-`enabled` +
inverser `/net/impulse/lifetime`, gewichtet summiert, dann in vier Bänder
geschnitten):

- Vorteil: kein manueller Pflegeaufwand, neue Presets sind sofort
  klassifiziert, keine Diskrepanz zwischen „was der Score sagt" und „was der
  Operator eingetragen hat" möglich.
- Nachteil: ~50 Parameter tragen zur wahrgenommenen Energie sehr
  unterschiedlich stark bei, und nicht linear. Ein Beispiel aus den
  gelesenen Presets: `standby.txt` hat `/net/randomSpawn/enabled = 0` (Spawn
  komplett aus) und wirkt entsprechend ruhig — aber `random1.txt` hat
  `/net/randomSpawn/energy = 0.15` (niedrig) bei gleichzeitig aktivem
  6-Track-Sequencer mit `bpm = 34` und einem Track mit
  `swingJitter = 1.0` und `energy = 0.98`. Ob das „dynamisch" oder „mittel"
  ist, hängt vom Zusammenspiel der Parameter ab, nicht von einem einzelnen
  Wert — eine Gewichtungsformel wäre reine Vermutung, bis sie am Gerät gegen
  das tatsächliche Hör-/Seherlebnis kalibriert ist. Farbwerte (`/nodes/colors/*`,
  `/net/impulse/color/*`) tragen zur *wahrgenommenen* Dramatik ebenfalls bei
  (satte, helle Farben wirken intensiver), sind aber in einer rein
  numerischen Formel schwer zu fassen.
- Ergebnis wäre eine Blackbox, die bei jeder Preset-Änderung neu evaluiert
  werden müsste, und die Birk vermutlich öfter korrigieren als bestätigen
  würde.

**Manuelles Tagging:**

- Vorteil: Energie-Level ist eine **künstlerische** Einschätzung, keine
  technische Messung — genau wie ein DJ sein Set nach Gefühl staffelt, nicht
  nach BPM-Zahl allein. Birk (oder wer Presets anlegt) hört/sieht das Preset
  einmal am Gerät und vergibt das Level — das ist der natürliche Moment
  dafür, direkt beim Preset-Design.
  Passt auch strukturell zum bestehenden System: Presets werden schon heute
  bewusst benannt (`hang_drum_slow`, `standby`) und die Namen tragen bereits
  intuitive Stimmungsinformation, ohne dass ein Automatismus dahintersteht.
- Nachteil: zusätzlicher Pflegeschritt, vergessliche Operator:innen könnten
  ein neues Preset ohne Level anlegen.
  Abgefedert durch einen klar definierten Fallback (siehe unten).

**Konkreter Vorschlag für die Ablage** (Konzept, keine Implementierung):
eine neue, separate Datei `data/presets/energyLevels.txt`, analog zum Stil
von `data/stripeTrees.txt` (Klartext, kommentierbar) — **nicht** eine neue
Spalte in den bestehenden Preset-`.txt`-Dateien, weil das Preset-Dateiformat
bewusst 1:1 dem Format von `remoteSettings.txt` entspricht (siehe
Preset-System-Design, Abschnitt „Dateiformat") und eine siebte Spalte diese
Übereinstimmung bräche, ohne dass Save/Load-Snapshots davon wüssten.

```
# data/presets/energyLevels.txt — Preset-Name -> Energie-Level
# Level: ruhig | mittel | dynamisch | dramatisch
standby           ruhig
hang_drum_slow     ruhig
hang_blue          mittel
hang_drum_fast     dynamisch
random1            dynamisch
nachvollziehbar    mittel
```

Fehlt ein Preset in dieser Datei (neu angelegt, nicht getaggt), Fallback:
**mittel** — nicht ruhig (würde einen unklassifizierten dramatischen Moment
fälschlich dämpfen) und nicht dramatisch (würde ihn fälschlich verschärfen).
Zusätzlich eine Konsolenmeldung analog zum bestehenden Fehlerverhalten-Muster
(„defektes Preset darf die Show nicht anhalten, aber die Lücke wird
gemeldet").

## 2. Übergangs-Logik: gewichtete Zustandsmaschine statt Loop

Kernidee: statt einer festen Reihenfolge über alle Presets läuft eine
**Markov-Kette über die vier Energie-Level**. Bei jedem fälligen Wechsel wird
zunächst das **nächste Level** gewürfelt (gewichtet nach dem aktuellen Level),
danach **innerhalb** dieses Levels zufällig ein Preset gewählt (uniform, oder
mit „zuletzt gespielt vermeiden" — siehe Abschnitt 6).

Die explizite Anti-Monotonie-Regel: **nach einem hochenergetischen Level ist
ein Rückgang zu ruhig/mittel bevorzugt gewichtet, nicht garantiert.** Reine
Gleichverteilung (25 % auf jedes Level, immer) würde im Erwartungswert zwar
auch abwechseln, aber genauso oft zwei dramatische Level direkt
hintereinander erlauben wie einen Rückgang — das ist genau das „monotone"
Verhalten, das der Auftrag ausschließt. Die Matrix muss also **auf der
Diagonale zu hochenergetischen Leveln hin klein** sein.

### Vorschlag: Übergangs-Wahrscheinlichkeitsmatrix (Startwerte, nicht endgültig)

Zeile = aktuelles Level, Spalten = Wahrscheinlichkeit für das nächste Level:

| von \ nach | ruhig | mittel | dynamisch | dramatisch |
|---|---|---|---|---|
| **ruhig** | 20 % | 40 % | 30 % | 10 % |
| **mittel** | 25 % | 30 % | 30 % | 15 % |
| **dynamisch** | 35 % | 30 % | 20 % | 15 % |
| **dramatisch** | 60 % | 25 % | 10 % | 5 % |

Begründung der Struktur (nicht der exakten Zahlen — die sind ein
Startpunkt zum Ausprobieren am Gerät):

- **Von dramatisch aus**: die vom Auftrag wörtlich verlangte Regel — nach
  einem Höhepunkt kommt überwiegend Entspannung (60 % ruhig, 25 % mittel =
  85 % Rückgang), ein sofortiges zweites dramatisches Level ist mit 5 % die
  seltenste aller Optionen in der ganzen Matrix, aber nicht unmöglich (ein DJ
  fährt gelegentlich auch zwei Drops hintereinander, wenn's passt).
- **Von dynamisch aus**: ebenfalls Tendenz Richtung ruhig/mittel (65 %
  zusammen), aber weniger stark erzwungen als von dramatisch — ein
  dynamisches Level darf sich auch zum dramatischen steigern (15 %), das ist
  der natürliche Spannungsaufbau.
- **Von ruhig aus**: keine Verpflichtung, sofort hochzufahren — ruhig kann
  ruhig bleiben (20 %) oder sich über mittel/dynamisch aufbauen. Direkt zu
  dramatisch springen (10 %) ist selten, aber möglich — ein abrupter,
  überraschender Einstieg ist ein legitimes dramaturgisches Mittel, sollte
  aber die Ausnahme bleiben.
- **Von mittel aus**: die ausgeglichenste Zeile — mittel ist der
  „neutrale" Zustand, von dem aus alle Richtungen ähnlich offen sind, mit
  leichtem Bias Richtung dynamisch/dramatisch als Trend nach oben.

Jede Zeile summiert sich auf 100 %. Die Selbstübergänge (Diagonale:
20/30/20/5) sind bewusst nicht null — ein Level darf sich auch mit einem
anderen Preset **desselben** Levels fortsetzen (Preset-Wechsel innerhalb
eines Spannungsplateaus, ohne die Stimmung zu kippen).

## 3. Zeitliche Dimension: Spannungsbögen statt Gleichtakt

Nicht ein festes Intervall für alle Level (wie heute), sondern **je
Energie-Level eine eigene Verweildauer-Verteilung** — analog zu einem
langen Set, in dem ruhige Passagen sich Zeit nehmen dürfen und ein
Höhepunkt kurz und intensiv bleibt, bevor er abklingt.

Vorschlag (Minuten, Startwerte):

| Level | Verweildauer | Charakter |
|---|---|---|
| ruhig | 15–35 min | lange, atmende Passage |
| mittel | 10–20 min | Übergangs-/Aufbauplateau |
| dynamisch | 6–14 min | spürbar kürzer, treibend |
| dramatisch | 3–8 min | kurz und intensiv, kein Dauerzustand |

Statt fester Werte eine **Gleichverteilung innerhalb der Spanne** je Level
(einfachste Verteilung, die schon den gewünschten Effekt erzielt: Streuung
ohne Häufungspunkt). Eine Glockenkurve (Normalverteilung um einen Mittelwert)
wäre „musikalischer" (die meisten Abschnitte nahe am Mittelwert, seltene
Ausreißer nach oben/unten), ist aber eine Verfeinerung, die sich später ohne
Strukturänderung nachrüsten lässt — für den Konzeptstart reicht die
Gleichverteilung, sie macht die erste Kalibrierung am Gerät nachvollziehbarer
(„Level X dauert *irgendwo zwischen* a und b Minuten" ist leichter zu prüfen
als eine Glockenkurve).

Wichtig: das ist eine **komplett neue Zeitachse**, unabhängig vom
bestehenden `/preset/scheduler/interval` (das heutige feste Sekunden-Intervall
für den alphabetischen Loop). Die neue Schicht ersetzt dessen Rolle
funktional, aber der Parameter selbst bliebe unangetastet (siehe Abschnitt 4)
— genau das Muster, das schon beim Sequencer/`MusicalClock` gewählt wurde:
„Keine Kopplung an den Preset-Scheduler […] die zwei Zeitsysteme wissen
nichts voneinander" (CLAUDE.md, Zeile 337–339). Die neue Dramaturgie-Schicht
wird analog dazu ein drittes, eigenständiges Zeitsystem.

## 4. Technischer Bauplan (Konzept/Pseudocode, keine Implementierung)

### Wo das ansetzt

`PresetScheduler` bleibt unverändert: er entscheidet weiterhin nur **wann**
ein Wechsel fällig ist (`isDue`) und vollzieht **das Laden/Faden zu einem
vorgegebenen Namen** (`advance`, `noteLoaded`). Er kennt keine Namensliste
aus eigenem Antrieb — die kommt von außen (`PresetManager`, der heute
`PresetStore.list()` alphabetisch reinreicht).

Die neue Schicht — Arbeitstitel `SongStructureDirector` — sitzt **oberhalb**
von `PresetScheduler`, an genau der Stelle, an der `PresetManager` heute
`PresetStore.list()` aufruft und der Reihe nach durchreicht. Sie ersetzt
nicht den Scheduler, sondern die **Namensquelle**, die er bedient.

```
// Konzept-Pseudocode, keine echte Signatur

class SongStructureDirector {

  EnergyLevel currentLevel
  TransitionMatrix matrix       // 4x4, live veraenderbar
  DwellTimeRanges dwellRanges   // je Level eine [min,max]-Spanne
  EnergyLevelTagging tagging    // liest energyLevels.txt, Fallback "mittel"
  RandomSource rand             // hereingereicht, wie bei SpeedQuantizer/SplitVariance

  // Ersetzt den festen Sekunden-Intervall-Vergleich aus PresetScheduler.isDue()
  boolean isDue(nowMillis, levelStartMillis) {
    dwellMillis = dwellRanges.sample(currentLevel, rand)  // gewuerfelt BEI Levelstart, nicht bei jedem Frame
    return nowMillis - levelStartMillis >= dwellMillis
  }

  // Liefert den naechsten Preset-Namen, wenn isDue() true war
  String nextPreset(List<String> allPresetNames) {
    nextLevel = matrix.sample(currentLevel, rand)          // gewichteter Wuerfelwurf ueber die Zeile
    candidates = tagging.presetsForLevel(nextLevel, allPresetNames)
    chosen = pickWithinLevel(candidates, rand)              // uniform, optional "nicht das zuletzt gespielte"
    currentLevel = nextLevel
    return chosen
  }
}
```

Zusammenspiel mit dem bestehenden Scheduler (`PresetManager.draw()`-Tick,
konzeptuell):

```
if (songStructureDirector.enabled) {
  if (songStructureDirector.isDue(now, levelStartMillis)) {
    name = songStructureDirector.nextPreset(presetStore.list())
    presetScheduler.advance(now, singletonListOf(name))   // Scheduler wechselt NUR zu diesem einen Namen
    levelStartMillis = now
  }
} else {
  // heutiges Verhalten unveraendert: presetScheduler.isDue()/advance() mit
  // der vollen alphabetischen Liste
}
```

`PresetScheduler.advance(names)` nimmt schon heute eine **Liste** entgegen
und geht zum nächsten Namen relativ zu `current` weiter — für den
Director-Fall reicht es, ihm eine Liste mit genau einem Eintrag zu geben
(dem gewürfelten Namen), dann „wechselt" der Scheduler dorthin, unabhängig
von der internen Umlauflogik. Das ist eine Verwendung, für die
`PresetScheduler` nicht gebaut wurde, aber seine Signatur erlaubt es ohne
Änderung — zu prüfen, ob das so sauber genug ist oder ob `advance()` eine
zweite, explizitere Methode bräuchte (`jumpTo(name)`), ist eine
Detailentscheidung für die Umsetzungsphase, nicht für dieses Konzept.

### Live-Parametrisierung (OSC/Web-UI)

Analog zum bestehenden `RemoteControlled*Parameter`-Muster: die
Übergangsmatrix und die Zeitverteilungen sollen **keine Code-Konstanten**
sein, sondern live vom Operator verstellbar — genau wie heute schon
`/preset/scheduler/interval` ein `RemoteControlledFloatParameter` ist.

Denkbare Adressen (Konzept, nicht final):

```
/songStructure/enabled                      int   0/1
/songStructure/matrix/<von>/<nach>          float 0..1   (16 Adressen, oder 4x4 als eigener Parametertyp)
/songStructure/dwell/<level>/min            float Minuten
/songStructure/dwell/<level>/max            float Minuten
```

16 Einzeladressen für eine 4x4-Matrix wären viele neue `RemoteControlled*`-
Instanzen (mehr als das gesamte heutige Preset-System an Adressen hat) — ob
das über Einzelparameter geht oder eine kompaktere Darstellung braucht (z. B.
ein Web-UI-Editor, der eine Matrix als Ganzes sendet/lädt, ähnlich wie
`data/presets/*.txt` als Datei bearbeitet wird statt Wert für Wert), ist eine
technische Abwägung für die Umsetzung — für dieses Konzept reicht die
Feststellung: **live veränderbar, nicht hart codiert**, nach dem etablierten
Muster (jeder Parameter meldet sich selbst beim `OscMessageDistributor` an,
landet automatisch in `remoteSettings.txt` und ist damit auch Teil eines
Presets — was hier vermutlich unerwünscht ist: die Song-Struktur-Parameter
sollten wie `/preset/scheduler/*` von `PresetStore.EXCLUDED` ausgenommen
werden, sonst könnte ein geladenes Preset die Dramaturgie-Konfiguration
überschreiben).

## 5. Beispiel-Durchlauf (simuliert, ~2,5 Stunden, Startwerte aus Abschnitt 2/3)

Start: `mittel` (neutraler Einstieg), Würfelwurf nach jeweiligem Dwell-Ende:

| # | Uhrzeit | Level | Dwell | Preset (Beispielname) | gewürfelter Übergang |
|---|---|---|---|---|---|
| 1 | 22:00 | mittel | 17 min | hang_blue | Start |
| 2 | 22:17 | dynamisch | 9 min | random1 | mittel→dynamisch (30 %) |
| 3 | 22:26 | dramatisch | 5 min | hang_drum_fast | dynamisch→dramatisch (15 %) |
| 4 | 22:31 | ruhig | 28 min | standby | dramatisch→ruhig (60 %) |
| 5 | 22:59 | mittel | 12 min | nachvollziehbar | ruhig→mittel (40 %) |
| 6 | 23:11 | dynamisch | 11 min | random1 | mittel→dynamisch (30 %) |
| 7 | 23:22 | ruhig | 22 min | hang_drum_slow | dynamisch→ruhig (35 %) |
| 8 | 23:44 | dramatisch | 4 min | hang_drum_fast | ruhig→dramatisch (10 %, seltener Ausreißer) |
| 9 | 23:48 | ruhig | 31 min | standby | dramatisch→ruhig (60 %) |

Endzeit: ca. 00:19 Uhr, 8 Übergänge über ~2h19min.

Was die Regel sichtbar macht:

- **Kein einziges Mal zwei dramatische Level direkt hintereinander** — #3
  und #8 sind die beiden dramatischen Abschnitte, dazwischen liegen jeweils
  mehrere ruhigere Level, nie ein unmittelbarer Rücksprung.
- Nach #3 (dramatisch) folgt konsequent #4 (ruhig) — die 60-%-Regel greift
  im Beispiel genau wie vorgesehen, ist aber keine Zwangsläufigkeit: der
  seltene Sprung ruhig→dramatisch bei #7→#8 zeigt, dass die Matrix
  Überraschungen erlaubt, ohne die Grundtendenz zu brechen.
- Sichtbarer Spannungsbogen in der Dwell-Spalte: ruhige Abschnitte (#4, #7,
  #9) sind mit 22–31 min deutlich länger als die dramatischen (#3, #8, 4–5
  min) — genau das im Auftrag angelegte Bild eines DJ-Sets mit
  unterschiedlich langen Passagen, nicht ein Gleichtakt.

(Diese Tabelle ist eine Illustration der Mechanik anhand der
Beispielmatrix/-verteilungen, keine echte Simulation mit einem
Zufallsgenerator — für eine belastbare Prüfung der Matrix vor dem
Live-Einsatz empfiehlt sich später ein einfaches Offline-Skript, das z. B.
10 000 Übergänge zieht und die Häufigkeit von Level-Wiederholungen und
Level-Sequenzen auswertet, analog zum Test-Stil von `SpeedQuantizerTest`
— „die gewichtete Auswahl […] die Verteilung über 100 000 Ziehungen".)

## 6. Offene Fragen für Birk

1. **Wie viele Presets pro Energie-Level, um Wiederholung nicht spürbar zu
   machen?** Aktuell liegen 6 Presets in `data/presets/`, unklassifiziert.
   Mit vier Leveln reichen im ungünstigsten Fall 1–2 Presets pro Level für
   den Start, aber bei einer 12h-Nacht mit Dwell-Zeiten von wenigen Minuten
   bis knapp einer halben Stunde ergeben sich realistisch 20–40 Wechsel
   pro Nacht — ein Level mit nur einem Preset würde bei jedem Besuch dieses
   Levels exakt gleich klingen/aussehen. Faustregel-Vorschlag: mindestens
   3–4 Presets je Level für eine 12h-Show, damit sich innerhalb eines
   Levels noch etwas ändert. Wie viele neue Presets pro Level Birk anlegen
   will/kann, ist seine Entscheidung.
2. **Auswahl innerhalb eines Levels: uniform zufällig, oder „zuletzt
   gespieltes vermeiden"?** Bei wenigen Presets pro Level (siehe Frage 1)
   fällt eine unmittelbare Wiederholung desselben Presets sonst schneller
   auf als bei den heutigen Level-übergreifenden Übergängen. Eine einfache
   Regel („nicht dasselbe wie beim letzten Mal in diesem Level") wäre eine
   naheliegende Ergänzung, aber eine bewusste Entscheidung, kein
   Selbstläufer.
3. **Tageszeit-Komponente: ja oder bewusst nicht?** Der Auftrag erwähnt sie
   nicht, aber die Formulierung „über eine ganze Nacht" legt die Frage nahe:
   soll die Matrix z. B. gegen 3–5 Uhr systematisch Richtung ruhig
   verschoben werden (echter Nacht-Bogen: Anfang bewegter, Ende ruhiger),
   oder soll die Dramaturgie bewusst **nicht** an die Uhrzeit gekoppelt
   sein, damit die Installation auch tagsüber / bei einer kürzeren
   Session ohne Sondercode funktioniert? Eine Kopplung wäre eine zweite,
   zeitabhängige Matrix-Variante oder ein Uhrzeit-Gewicht, das die
   Basis-Matrix modifiziert — technisch möglich, aber eine bewusste
   Erweiterung, kein Teil dieses Grundkonzepts.
4. **Publikumsinteraktion:** im gelesenen `CLAUDE.md` und im Repo-Umfeld
   findet sich **keine** Erwähnung einer Publikums-Interaktions-Schicht,
   die auf die Dramaturgie zurückwirken könnte (kein Sensor-Input, der
   Levelwahl beeinflusst; die Kontaktmikrofone lösen Impulse aus, sind aber
   Teil des laufenden Bildes, kein Dramaturgie-Trigger). Falls Birk eine
   Interaktion vorhat (z. B. viel Publikumsaktivität am Netz drückt die
   Wahrscheinlichkeit Richtung dynamisch/dramatisch hoch), ist das ein
   separates, noch unspezifiziertes Feature — zu klären, ob und wie stark
   es in die Übergangsmatrix eingreifen soll, oder ob es bewusst getrennt
   bleibt (analog zur bereits getroffenen Entscheidung „keine Kopplung an
   den Preset-Scheduler" beim Sequencer).
5. **Manuelles Override während der Show:** wenn ein Operator z. B. per
   `/preset/load` oder `/preset/next` manuell eingreift (beide OSC-Befehle
   existieren schon), soll der Director das als „Levelwechsel" werten und
   seinen internen Zustand (`currentLevel`, `levelStartMillis`) darauf
   synchronisieren, oder soll ein manueller Load den Automatik-Modus
   pausieren/beenden? Ohne Klärung könnte ein manueller Eingriff sofort vom
   nächsten gewürfelten Wechsel überschrieben werden, was den Eingriff
   sinnlos macht.
6. **Startlevel beim Hochfahren der Installation:** fest (z. B. immer
   `ruhig` als sanfter Einstieg, analog zum heutigen `/preset/scheduler`-
   Verhalten „springt beim Einschalten nicht sofort"), oder zufällig aus
   der Matrix? Eine feste Wahl (z. B. `ruhig`) wäre die naheliegende
   Ergänzung zum bestehenden Boot-Preset-Mechanismus (`IMPULSE_PRESET`),
   aber explizit zu entscheiden.

## Abgrenzung: was dieses Konzept bewusst nicht anfasst

- Keine Änderung an `PresetScheduler.java`, `PresetStore.java` oder
  `PresetManager.java` — reines Aufsatz-Konzept.
- Keine Integration mit Sequencer/`MusicalClock`/`SpeedQuantizer`/
  Baum-Origin-Filter — laut Auftrag eigenständige, parallele Features, hier
  nicht behandelt.
- Kein Crossfade zwischen Presets — das war schon im ursprünglichen
  Preset-System-Design bewusst ausgeklammert („Der Wechsel ist ein harter
  Sprung […] ein eigenes Thema") und bleibt es hier.
- Keine SuperCollider-seitige Betrachtung — die Song-Struktur wählt nur
  imPulse-Presets; da jeder Wechsel wie gehabt `/sc/preset/load` an SC
  weiterreicht (sofern der dortige Empfänger je wiederhergestellt wird, siehe
  offener Punkt in `2026-07-31-night-composition-design.md`), folgt der Klang
  automatisch mit, ohne eigene Dramaturgie-Logik.
