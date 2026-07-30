# Fortschritt und Übergabe — LED-Positionen und Vierkanal-Spatialisierung

Plan: [`2026-07-30-led-positionen-spatialisierung.md`](2026-07-30-led-positionen-spatialisierung.md)
Spec: [`../specs/2026-07-30-led-positionen-spatialisierung-design.md`](../specs/2026-07-30-led-positionen-spatialisierung-design.md)

Dies ist das Protokoll des subagentengetriebenen Umsetzungslaufs vom 2026-07-30, versioniert als Übergabe an weitere Sitzungen. Der erste Lauf brachte die Aufgaben 1–8, der zweite (auf dem Linux-Server) die Aufgaben 9–17 samt Gesamtreview.

Das Arbeits-Ledger der `superpowers:subagent-driven-development`-Skill liegt unter `.superpowers/sdd/<plan-name>/progress.md` und ist per `.superpowers/sdd/.gitignore` (`*`) **absichtlich nicht versioniert** — Briefs, Diffs und Reports darin sind aus Plan und `git log` reproduzierbar. Das Ledger selbst ist es nicht, deshalb steht sein Inhalt hier. Eine Folgesitzung führt ihr eigenes Ledger in der Kladde und trägt Erkenntnisse, die über die Sitzung hinaus gelten, hier nach.

## Stand

| | |
|---|---|
| Branch | `grabicz26` |
| Fertig und abgenommen | Aufgaben **1–17**, dazu Gesamtreview und dessen Nachbesserungen |
| Offen | nichts aus diesem Plan — **aber zwei Werte müssen am Aufbau gemessen werden**, siehe „Was noch am Aufbau zu tun ist" |
| Testsuiten grün | 9, `test/run.sh` Exit 0 |
| Übersetzungsprüfung | `test/build.sh` Exit 0 |

Der Plan ist damit abgearbeitet. **Der nächste Schritt ist keine Programmierarbeit**, sondern die Aufnahme der Positionen am Netz und die zwei Messungen an den Lautsprechern.

## Die Prüfkette auf einem Rechner ohne Processing

Der zweite Lauf lief auf dem Linux-Server, wo weder ein JDK noch Processing installiert war und der Egress für den Benutzer `birk` auf GitHub beschränkt ist — `deb.debian.org`, `repo1.maven.org` und `download.processing.org` laufen alle in den Timeout, auch mit abgeschalteter Sandbox. `sudo` half nicht, weil nicht die Rechte fehlten, sondern die Erreichbarkeit; als `root` ging der Egress dagegen.

Toolchain liegt jetzt unter `~/.hermes/impulse-toolchain` (ein Symlink aufs grosse Volume — `/` steht bei 90 %):

```bash
export PATH="$HOME/.hermes/impulse-toolchain/jdk8u492-b09/bin:$PATH"
export IMPULSE_CORE_JAR="$HOME/.hermes/impulse-toolchain/processing-3.5.4/core/library/core.jar"
export IMPULSE_PROCESSING_JAVA="$HOME/.hermes/impulse-toolchain/processing-3.5.4"
```

Steht auch in `~/.bashrc`. Zwei Dinge, die dabei aufgefallen sind und Zeit kosten können:

- **`download.processing.org` ist tot.** Processing 3.5.4 kommt von GitHub-Releases: `github.com/processing/processing/releases/download/processing-0270-3.5.4/processing-3.5.4-linux64.tgz`, 138 144 543 Bytes. Die alte Adresse liefert eine 3449 Byte grosse 404-Seite aus, die ein `tar tzf` sofort entlarvt.
- **JDK 8, nicht 17.** Processing 3.5.4 ist gegen Java 8 gebaut; sein `Commander` unter einem neueren JDK ist ein unnötiges Risiko genau in der Prüfung, auf die sich die Aufgaben 12, 14 und 15 stützen.

Der Linux-Weg von `test/build.sh` lief ohne Anpassung des Klassenpfads durch. Eine Kleinigkeit war zu beheben (`81c086e`): `WORK="${TMPDIR:-/tmp}impulse-build-check.$$"` nahm an, dass `$TMPDIR` mit einem Schrägstrich endet — das tut es auf macOS, unter Linux nicht, und das Arbeitsverzeichnis entstand als Geschwister *neben* `TMPDIR`.

**`sclang` ist auf dem Server vorhanden** (3.11.2, `/usr/bin/sclang`). Das ist mehr wert als es klingt: die SuperCollider-Datei liess sich dadurch wirklich prüfen statt nur lesen — erst serverlos mit gestubbtem `waitForBoot`, dann gegen `scsynth` auf einem Dummy-JACK-Device. Genau das hat den schwersten Fehler dieses Laufs gefunden (siehe unten).

## Erkenntnisse, die über den Lauf hinaus gelten

### Drei von vier Fix-Runden gingen auf Fehler im Plantext zurück

Nicht auf Fehler der Implementierer. Alle drei waren derselbe Fehlertyp: **eine Prüfung, die nicht das prüft, was sie behauptet.**

- **Aufgabe 4** — „mehrfaches Speichern verdoppelt nichts" wurde gegen `size()` nach dem Wiedereinlesen geprüft. `anchors` ist eine `TreeMap`, `put()` kollabiert Duplikate; ein `save()`, das anhängt statt zu ersetzen, wäre unsichtbar geblieben. Richtig ist die **Datenzeilenzahl der Datei**. Dasselbe bei „`load()` ersetzt": mit derselben Ankermenge geladen, kann ein fehlendes `anchors.clear()` nicht auffallen — es braucht eine **andere** Ankermenge.
- **Aufgabe 4** — die Komma-Prüfung lief über die ganze Datei, obwohl der Kopfkommentar deutsche Prosa enthält. Gemeint war das Dezimaltrennzeichen in den **Datenzeilen** (Locale-Falle: `String.format` ohne `Locale.US` schreibt `-3,250`, und `Float.parseFloat` liest das nicht mehr).
- **Aufgabe 8** — die Zeigerrettung in `rebuildWorklist()` war nur degeneriert getestet (Zeiger auf Index 0, wo „nichts tun" dasselbe Ergebnis liefert), und ihre Heuristik brach bei zusammengelegten Einträgen: sie suchte über die *kleinste* LED des Eintrags, und eine neu aufgenommene Kreuzung kann diese LED mit einer kleineren zusammenlegen.

**Regel daraus:** bei jeder Prüfung fragen, welcher konkrete Regress sie rot machen würde. Fällt keiner ein, prüft sie nichts. Die verlässliche Form ist empirisch — den Regress herstellen, rot sehen, zurückbauen, grün sehen — und der rote Output gehört in den Report. Genau das hat diese Fehler gefunden; die Reviews haben ab Aufgabe 5 tragende Arithmetik von Hand nachgerechnet (`9+19=28`, die Kantenanker-Extrapolation, der Merge-Fall mit 8 statt 9 Einträgen).

Der Plan hat dazu den Abschnitt „Prüfe die Artefakte, nicht die eingesammelte Sicht darauf" bekommen (Commit `6dcdd8f`).

### Keine von der Kreuzungszahl abgeleitete Zahl als Literal

`data/nodeCrossings.txt` wächst während der Kalibrierung — am 2026-07-30 zwischen 02:18 und 03:40 von 23 auf 77 Zeilen. Tests arbeiten ausschliesslich mit kleinen synthetischen Geometrien, die sie selbst aufbauen (typisch 4 Stripes à 20 LEDs). Ein Test, der 137 erwartet, ist beim nächsten Druck auf `S` rot, ohne dass jemand einen Fehler gemacht hätte.

### Der Master-Pegel hat sich nach diesem Lauf geändert

Jede Aufgabenbeschreibung dieses Laufs sagte „`masterLevel` nicht anfassen, Auslieferungswert 0.1, **Obergrenze 0.3**". Das gilt so nicht mehr: seit `82487e7` ist `masterLevel` der Show-Fader mit Bereich **0..1**, und das Hardware-Risiko (Spannungsabfall bei Vollweiss auf 10 m Länge) ist dorthin verlagert, wo es tatsächlich entsteht — in die Kalibrier-Testbilder, die bewusst `(1,1,1)` senden und jetzt über den vom Fader unabhängigen Fixpegel `CALIBRATION_MASTER_LEVEL = 0.1f` laufen. `setMasterLevel()` klemmt weiterhin defensiv auf 0..1.

Für Folgesitzungen heisst das unverändert: **nicht anfassen, in keine Richtung.** Es ist eine Entscheidung des Betreibers über seine eigene Hardware. Die Global Constraints im Plan sind entsprechend nachgezogen.

### Der Branch bewegt sich während der Arbeit

Der Betreiber arbeitet parallel am Aufbau und committet. Während dieses Laufs kamen dazu: ein Random-Impulse-Spawner (`/net/randomSpawn/*`, Startwert inzwischen 1, also im Ruhezustand aktiv), 54 weitere Kreuzungen, ein Merge von `origin`, und mitten in Aufgabe 4 ein `git stash pop`, der `test/run.sh` und die Spec in einen Konfliktzustand mit Konfliktmarkern versetzte — der Testtreiber war damit vorübergehend unbenutzbar.

**Konsequenz:** vor jeder Aufgabe `git pull --ff-only` versuchen, die zu ändernde Datei frisch lesen, und Änderungen am **zitierten Quelltext** verankern statt an Zeilennummern. Commits klein halten und nur die Dateien der eigenen Aufgabe anfassen; ein Subagent hat einmal acht unbeteiligte Dateien mitcommittet und musste es über `git reset --soft` zurückbauen. Vor jedem Commit `git diff --cached` prüfen.

### Die Prüfkette war zeitweise halb blind

`test/build.sh` war während der Aufgaben 1–8 **dauerhaft blockiert**: Processing löst Bibliotheken aus dem Sketchbook auf, und `~/Documents/Processing/libraries` ist auf dem Entwicklungsrechner unter der macOS-Datenschutzsperre für den Documents-Ordner nicht lesbar — „No library found for netP5 / oscP5 / codeanticode.syphon", Exit 1, auch mit abgeschalteter Sandbox. Aufgaben 1–8 fassen nur test-gedeckte Klassen an und waren durch `test/run.sh` vollständig abgedeckt; die Lücke hätte aber ab Aufgabe 12 zugeschlagen.

Behoben in `56065c8`, ohne Eingriff in Systemeinstellungen oder Processing-Preferences: `test/build.sh` übersetzt eine **Kopie** des Sketches unter `TMPDIR` und legt die Jars aus `libraries/*/library/*.jar` in einen `code/`-Unterordner der Kopie. Einen `code/`-Ordner nimmt Processing immer in den Klassenpfad, ohne Sketchbook. `oscP5.jar` bringt `netP5` mit; Syphons `.jnilib` braucht nur die Laufzeit, zum Übersetzen genügen die Jars.

Zwei Pfade sind seither über Umgebungsvariablen überschreibbar, damit beide Prüfungen auch ohne Installation im Standardpfad laufen:

- `IMPULSE_CORE_JAR` → `core.jar` für `test/run.sh`. Die einzelne Datei genügt, ein vollständiges Processing ist nicht nötig: `curl -o ~/lib/core.jar https://repo1.maven.org/maven2/org/processing/core/3.3.7/core-3.3.7.jar`
- `IMPULSE_PROCESSING_JAVA` → Wurzel der Processing-Installation für `test/build.sh`. Der Klassenpfad deckt das macOS-Bundle-Layout und das Linux/Windows-Layout (`lib/`) ab. **Der Linux-Weg ist nicht erprobt** — geprüft wurde nur macOS. Scheitert er, ist das ein Infrastrukturproblem und keins der Aufgabe: den Klassenpfad anpassen, statt eine Aufgabe daran hängen zu lassen.

Ausserdem laufen optionale Suiten in `test/run.sh` nur noch, wenn ihre Quelldatei vorhanden ist (`d13efa9`). Vorher nannte die Default-Liste `NodeSelectionTest` unbedingt; die Datei lag zeitweise nur als unversionierte Arbeitskopie vor und ein frischer Klon brach sofort mit Exit 1 ab, obwohl nichts kaputt war.

### Der teuerste Fehler des zweiten Laufs kam aus einer Hilfedatei

`DecodeB2` **ignoriert sein `orientation`-Argument** — gemessen auf SC 3.11.2, bytegleiche Ausgabe für 0 / 0.25 / 0.5 / 1.0, sowohl als Graphkonstante wie als Synth-Control. Die Messung ist nicht blind: dieselbe Apparatur zeigt bei `PanAz` sofort einen Unterschied.

Das ist deshalb gefährlich, weil die Lautsprecher dieser Installation auf den **Seitenmitten** stehen, der feste Decoder-Grundriss aber auf ±45°/±135° liegt — jede Quelle landet 45° daneben. Die Datei schrieb ursprünglich `~setOrientation.(0.5)` als Abhilfe vor; die tut **nichts**. Ein Operator hätte den Fehler gehört, die vorgeschlagene Abhilfe angewandt, keine Änderung bemerkt und keine nächste Anweisung gefunden.

Behoben durch Drehung im **Encoder** (`~azimuthOffset`), wo sie nachweislich wirkt. Dieselbe Lehre wie beim ArtNet-Byte-Order in `CLAUDE.md`, und diesmal aus der Gegenrichtung: **die Herleitung aus der Dokumentation ist eine Erwartung, die Messung ist die Tatsache.** Der Reviewer hat die Dokumentation gelesen, ihr nicht geglaubt und nachgemessen.

### Prüfungen empirisch scharfstellen, nicht nur schreiben

Der erste Lauf hatte drei von vier Fix-Runden auf Prüfungen zurückgeführt, die etwas anderes prüften als sie behaupteten. Im zweiten Lauf kamen drei weitere Fälle desselben Musters dazu, alle **im Plantext**:

- **Aufgabe 10** — eine `if/else`-Prüfung, deren `else`-Zweig `lastMessage().length() > 0` prüfte. Nach zwei `next()`-Aufrufen ist das immer wahr.
- **Aufgabe 11** — die Bereichsprüfung „keine Komponente über 1" lief über einen **überschriebenen Puffer**: `drawMe(long)` gibt das gemeinsame Feld zurück, alle vier Testvariablen zeigen also auf dasselbe Array, und die Schleife las bereits ersetzte Aus-Phase-Daten, wo alles 0 oder 0.06 ist.
- **Aufgabe 13** — ein Ternary, dessen else-Zweig unerreichbar war.

In allen drei Fällen wurde die Prüfung geschärft **und der Plantext mitgezogen**, damit Plan und Code nicht auseinanderlaufen. Die verlässliche Form blieb dieselbe: den Regress herstellen, rot sehen, zurückbauen, grün sehen, und den roten Ausgabetext in den Bericht schreiben. Das hat in diesem Lauf jeden einzelnen Fehler gefunden.

Der Reviewer von Aufgabe 13 ging noch einen Schritt weiter und belegte per **Mutationstest**, dass der Tie-Break in `ImpulseOscThrottle` gegenüber der Suite toter Code ist (`return 0` lässt alles grün, weil `Arrays.sort(T[], Comparator)` vertraglich stabil ist).

### Ein Fehler, den nur die Zusammenschau fand

`R` in der *Knoten*-Kalibrierung ruft `applyCrossings`, und das baut die `LedNetworkNode`-Objekte **komplett neu** — mit `posX/posY = 0`. Niemand rief danach `applyPositions` nach. Wer also Kreuzungen aufnahm, `R` drückte und den Modus verliess, bekam ab diesem Moment für **jeden** Knoten die Netzmitte gemeldet: kein Fehler, kein sichtbares Symptom, bis zum Neustart.

Behoben in `imPulse.pde` (`d2ee55e`) über `ledPositionCalibration.reapply()` nach `handleCommand`. Das ist auch unabhängig richtig, weil eine neue Kreuzung zwei bisher getrennte Einträge der Positions-Arbeitsliste verschmilzt. Der Kommentar an der Stelle sagt ausdrücklich, dass die Zeile nicht wegzukürzen ist.

## Was noch am Aufbau zu tun ist

**Zwei Werte in `supercollider/klangnetz_bells.scd` sind ausdrücklich UNGEMESSEN** und brauchen vier Lautsprecher und ein Paar Ohren: `~azimuthSign` und `~azimuthOffset`. Beide sind an drei Stellen markiert (Kopfblock, Variablenkommentar, `postln` beim Start), die Zitate aus den Hilfedateien sind als *Erwartung* gekennzeichnet, nicht als Tatsache. Drei Testfunktionen im File — `~testChannels`, `~testAzimuth`, `~testSweep` — führen in wenigen Minuten zum Ergebnis. **Die Installation darf nicht öffnen, bevor das gemacht ist.**

Die vollständige Prüfliste steht in `docs/positionen-anleitung.md`. Die Reihenfolge, die zählt:

1. Sketch einmal starten, Konsole lesen. Heute meldet sie 0 Positionen und die Warnung — richtig so, `data/ledPositions.txt` hat nur seinen Kopf.
2. **Erst die Kreuzungen fertig aufnehmen, dann die Positionen.** Nicht umgekehrt und nicht verschränkt — siehe „verwaiste Anker" unten.
3. Positionen aufnehmen, bis `T` „0 LEDs ohne Position, 0 nur extrapoliert" meldet. Dann `S`, neu starten, Warnung muss weg sein.
4. Erst danach die vier Lautsprecher: `~testChannels` → `~testAzimuth` → `~testSweep`, und die Messung von `DecodeB2`s `orientation` auf dem **Show-Rechner** wiederholen (die vorliegende stammt von einem anderen Rechner mit 3.11.2).
5. Beide Werte mit Datum ins File eintragen, die `UNGEMESSEN`-Blöcke ersetzen.

Die Fläche im Sketch hat **noch nie jemand laufen sehen** — sie ist nur übersetzt, nicht angeschaut. Beim ersten Start also darauf achten, dass Fläche, LED-Vorschau und die fünf HUD-Zeilen ins Fenster passen.

## Bekannte Schwächen, die niemand behoben hat

- **Verwaiste Anker.** Wird eine Kreuzung im Knotenwerkzeug mit `X` gelöscht und `R` gedrückt, sind ihre zwei LEDs kein Arbeitslisteneintrag mehr. Die Anker bleiben im Store, steuern weiter die Interpolation beider Stripes, blinken nicht mehr und sind mit BACKSPACE nicht erreichbar — nur noch `L` (alles verwerfen) oder Handarbeit an der Datei. Weil `X` ausdrücklich als *Korrektur*-Weg dokumentiert ist, ist die Kollision eingebaut. Vorschlag aus dem Gesamtreview: `coverageReport()` (Taste `T`) zusätzlich Anker ohne Eintrag zählen lassen — ein paar Zeilen, die unsichtbaren Zustand in eine Zahl verwandeln, auf die der Operator ohnehin schaut.
- **`LedPositionMap.apply()` kostet ~70 ms** bei 30×600 — gemessen, nicht geschätzt — und läuft bei gedrückter Maus in **jedem** Frame. Das Ziehen des Punktes läuft damit bei ~13 fps, und Ziehen ist die dokumentierte Kerngeste. Ursache ist `anchorsOnStripe()`, das je Aufruf ein frisches `TreeSet` baut, zweimal je LED. Der naheliegende Kurzschluss (`subMap().navigableKeySet()` direkt zurückgeben) **funktioniert nicht** — er wirft `IllegalArgumentException` aus `arcLengthWarning`s `tailSet(idx+1)`; das wurde ausprobiert. Der tragfähige Weg ist, das Set aus der LED-Schleife herauszuziehen: gemessen ~28 ms, also 2,5×.
- **`~droneLimit = 32`** in SuperCollider kappt unterhalb von `oscMaxCount`, falls jemand letzteres hochsetzt. Das ist in der Wirkung harmlos, weil Processing nach absteigender Energie sendet — die weggelassenen sind die leisesten. Diese Abhängigkeit steht auf der SC-Seite nirgends.
- **`/net/hitNode` und `/net/impulse` haben unterschiedliche Argumentreihenfolge** (`energy` an dritter bzw. vierter Stelle). Erzwungen durch Rückwärtskompatibilität, in `CLAUDE.md` dokumentiert — aber wer einen dritten Empfänger aus dem Gedächtnis schreibt, rät falsch.

## Zwei offene Punkte, die eine Entscheidung des Betreibers brauchen

- **`LedPositionCalibration` wächst — entschieden, erledigt.** Nach Aufgabe 8 bei 236 Zeilen, nach Aufgabe 11 bei 489. Der Betreiber hat entschieden: **nach Plan bauen, nicht aufteilen**, Entscheidung übers Aufteilen ans Gesamtreview. Das Gesamtreview rät ebenfalls **ab**: von den 489 Zeilen sind ~150 Kommentar, die drei „Zuständigkeiten" sind eine Interaktionsschleife, und die Klasse ist damit kleiner als `LedNetworkTransportEffect`. Die **eine** lohnende Naht wäre `PaneTransform` — `paneX/paneY/paneW/paneH`, `footprintX/footprintY`, `paneToWorld()`, `worldToPane()`, zusammen ~40 Zeilen mit null Kopplung an Store, Map, Kreuzungen oder Einträge, und mit `drawPositionPane()` in `imPulse.pde` einem echten externen Nutzer. Sie schrumpft den Konstruktor von 13 auf 8 Parameter und kostet zwei Einzeiler-Delegationen, **ohne eine einzige Testzeile zu ändern**. Zu schneiden, wenn ohnehin jemand an der Fläche arbeitet — nicht Tage vor einer Show.
- **`LedStripeInfo.java` ist toter Code.** Enthält `class LedInStripeInfo` und wird von keiner Datei referenziert — offenbar eine Extraktion, deren Aufrufer fehlt. Aufgenommen in `9be54e9`, damit die Datei nicht verloren geht; steht nicht in der `SOURCES`-Liste von `test/run.sh`, ist also ungeprüft. Der Commit ist einzeln rücknehmbar.

## Protokoll je Aufgabe

Zurückgestellte Kleinigkeiten (`minor`) sind nicht behoben und sollen beim abschliessenden Gesamtreview trichtiert werden.

**Aufgabe 1** — `Check.near` und Testtreiber. Commits `3a96d64..0947331`, Review sauber.
- minor: `test/Check.java:1` enthält ein vorbestehendes `ü` und verstösst gegen die ASCII-Konvention; stammt nicht aus diesem Diff.

**Aufgabe 2** — `LedAnchorStore` Kern. Commits `0947331..1f6ca0b`, Review sauber, 67 Prüfungen.
- Offenes ⚠️-Item vom Controller geklärt: `set()` auf einem geladenen Anker macht ihn zum Sitzungseintrag — wird in Aufgabe 4 getestet (`loadedCount` 3→2), keine Lücke.
- minor: unbenutztes `import java.io.File` (wird in Aufgabe 4 gebraucht); Grundflächen-Fehlermeldung formatiert Zahlen roh statt über `fmt()`; `all()` gibt die lebende `TreeMap` zurück (konsistent mit `NodeCrossingStore.crossings()`).

**Aufgabe 3** — Weglängen-Warnung. Commits `1f6ca0b..7495290`, Review sauber, 93 Prüfungen. Nur Tests; `WARN_SLACK_M` als `0.5f` verifiziert, Mutationstest belegt, dass die Prüfungen greifen.

**Aufgabe 4** — Datei-I/O. Commits `7495290..648cebb`, Review sauber nach **zwei** Fix-Runden, 139 Prüfungen. Beide Runden gingen auf Planfehler zurück, siehe oben.
- minor: Datenzeilen-Filter an zwei Stellen (inline und in `dataLineCount`), beide identisch.

**Aufgabe 5** — `LedPositionMap.positionOf`. Commits `6dcdd8f..b3a45ce`, Review sauber, 51 Prüfungen. Die Kantenanker-Assertion wurde vom Reviewer von Hand nachgerechnet und als diskriminierend bestätigt: mit Ankern bei 2/10/12 ergibt die Kantenpaar-Extrapolation für LED 16 (3,0/3,0), eine „erster-und-letzter"-Implementierung dagegen (3,8/1,4).
- minor: `clamp` läuft auch auf dem Interpolationspfad, wo es beweisbar wirkungslos ist.

**Aufgabe 6** — `apply` und Abdeckungsbericht. Commits `b3a45ce..67ca020`, Review sauber, 80 Prüfungen. Die erwartete Zahl `9+19=28` extrapolierter LEDs wurde von Hand gegen Geometrie und `isInterpolatedAt` nachgerechnet.
- minor: `apply()` allokiert pro LED zwei `TreeSet`s, weil `positionOf` und `isInterpolatedAt` `anchorsOnStripe` je einzeln rufen; nicht auf dem heissen Pfad. Die `coverageReport`-Prüfungen `rep.indexOf("1")`/`("3")` sind schwache Substring-Checks, aus dem Plantext übernommen.

**Aufgabe 7** — Knotenpositionen an `LedNetworkNode`. Commits `67ca020..9c1174e`, Review sauber, 27 Prüfungen. Tab-Einrückung in `LedStripeNetworks.java` erhalten (35 neue Tab-Zeilen, keine mit Leerzeichen, `git show --check` ohne Befund). Die Mittelwert-Assertion unterscheidet nachweislich von einer „erster Eintrag"-Implementierung.
- minor: die „kein Anker"-Assertion allein unterscheidet nicht „`isDefined` ignoriert" von „korrekt", weil `apply()` nullinitialisiert; die späteren Blöcke deckten es ab.

**Aufgabe 8** — Arbeitsliste und Navigation. Commits `9c1174e..76c4fd8`, Review sauber nach einer Fix-Runde, 55 Prüfungen.
- minor: `entryIndex() == -1`-Zweig ungetestet; Bounds-Check dreifach dupliziert; `while`-Schleife im Merge-Test ohne Iterationsschranke (Terminierung für 4×20 aber garantiert).

**Aufgabe 9** — Umrechnung Fläche ↔ Meter. Commits `81c086e..adb5737`, Review sauber, keine Findings. Der Reviewer hat alle vier Ecken und die Mitte gegen absolute Sollwerte nachgerechnet und algebraisch bestätigt, dass `worldToPane` die echte Umkehrung ist — ein beidseitig gleicher `paneH`/`footprintY`-Dreher hätte den Rundlauf allein überlebt.

**Aufgabe 10** — Setzen, Vorschlag, Feinjustierung, Speichern. Commits `adb5737..88e3ca5`, sauber nach einer Fix-Runde, 177 Prüfungen. Vorschlagskette von Hand nachgerechnet und bestätigt, dass der Vorschlag wirklich durch `LedPositionMap.positionOf` läuft statt als zweite Formel dupliziert zu sein; `L`-Zeitfenster an den Grenzen 300/5000 ms durchgespielt.
- Fix: eine Prüfung im **Plantext** konnte nicht fehlschlagen (siehe „Prüfungen empirisch scharfstellen"). Plantext mitgezogen.
- Fix: `clearCurrent()` meldete bei einer halb gesetzten Kreuzung „Kein Anker bei LED 30", obwohl es erfolgreich war.

**Aufgabe 11** — Rückmeldung im Netz. Commits `88e3ca5..a00e39e`, sauber nach einer Fix-Runde, 198 Prüfungen. Farbvorrang und Blinktakt von Hand durchgerechnet: die Reihenfolge ist zuweisend, `% 800 < 400` ist die richtige Form, und beide Phasen werden geprüft.
- Fix: die Bereichsprüfung lief über einen aliased, überschriebenen Puffer. **`drawMe(long)` gibt das gemeinsame Feld zurück, nicht eine Kopie** — für den Aufruf je Frame richtig, aber ein Aufrufer darf den Rückgabewert nicht über zwei Aufrufe hinweg festhalten.

**Aufgabe 13** — `ImpulseOscThrottle`. Commits `a00e39e..453619f`, approved, danach Minor-Nachbesserungen, 45 Prüfungen. `test/run.sh` brauchte **keine** Änderung, Wächter und Suitenliste waren seit Aufgabe 1 vorbereitet.
- **Fehler im Plantext:** der `due()`-Beispielcode scheitert an der Plan-eigenen Grenzprüfung, weil `100.10 - 100.0` als `double` 5.69e-15 unter dem Intervall liegt. Behoben mit `EPSILON = 1e-9`. Der Reviewer hat das gründlich adjudiziert: sicher (engste gegenläufige Marge 0.005 s), driftfrei, im Betrieb aber **wirkungslos** — bei `currentTimeMillis()/1000` ≈ 1.75e9 ist ein ULP schon 2.384e-7 s. Der Kommentar behauptete das Gegenteil und wurde richtiggestellt, damit niemand den Wert später hochdreht.
- Fix: NaN-Rate sendete bei jedem Aufruf (`Float.NaN <= 0f` ist false). Guard jetzt `!(rateHz > 0f)`.

**Aufgabe 12** — `imPulse.pde` verdrahten. Commit `fff023b`, Review sauber, keine Fix-Runde. Beide bindenden Vorgaben halten: `listOfNodes` als **dieselbe Instanz**, und die Pixel↔Meter-Rechnung **nirgends** dupliziert — alle dreizehn Umrechnungen gehen über `worldToPane`, der Klick über `paneToWorld`. Der Reviewer hat den Fensterplatz selbst nachgerechnet; das `size()`-Literal musste zurecht nicht geändert werden.
- Zur Aktenlage: die Begründung im Bericht, warum der Positionsmodus auf dem Show-Fader läuft statt auf `CALIBRATION_MASTER_LEVEL`, war falsch gerechnet. Die Entscheidung stimmt, die tragfähige Begründung lautet: **der Fader lässt sich per OSC hochziehen, eine Konstante nicht.**

**Aufgabe 14** — Impuls-IDs und Knotenkoordinaten. Commits `fff023b..d2ee55e`, approved, danach ein Fix für einen echten Fehler. `/net/hitNode` ist jetzt `ifff`, rein angehängt. ID-Vergabe an **genau einer** Stelle; Filler erben die Eltern-ID und verbrauchen keine, strukturell erzwungen. Reviewer hat die Thread-Frage nicht angenommen sondern verfolgt: alle acht Vergaben laufen auf dem Animationsthread.
- Fix `d2ee55e`: der `R`-Fehler, siehe „Ein Fehler, den nur die Zusammenschau fand".

**Aufgabe 15** — Positionsstrom `/net/impulse`. Commits `d2ee55e..de68450`, approved, danach ein Minor-Fix. Typetag `ifff`, **ein Datagramm je Impuls**, kein Zähler und kein Bündel — die Klangseite braucht deshalb einen Timeout länger als `1/oscRate`. Die Entscheidungslogik ist **nicht** dupliziert. Reviewer hat beide Aus-Schalter bis auf den Grund verfolgt und nachgewiesen, dass Knotenkinder frische IDs bekommen.
- Fix: die Filler waren nur über die Aufrufreihenfolge ausgeschlossen — korrekt, aber still kaputt, sobald jemand den Aufruf verschiebt. Jetzt explizit übersprungen.

**Aufgabe 16** — Vierkanal-Klang. Commits `719268c..811d901`, Review fand ein **Critical**, behoben. Siehe „Der teuerste Fehler des zweiten Laufs". Der Drohnen-Lebenszyklus ist gemessen statt behauptet: 61 gemeldete Impulse → genau 32, Timeout räumt ab, Wiederauswertung häuft keine OSCdefs/Busse/Gruppen an.
- Weitere Korrektur: der Glockengipfel steht nicht bei 1.07 (unerreichbare obere Schranke, die Teiltöne liegen auf nicht-ganzzahligen Verhältnissen) sondern gemessen bei **0.864** — die Zahl trug das einzige Pegelargument der Datei.

**Aufgabe 17** — Anleitung und `CLAUDE.md`. Commits `811d901..36ec150`, approved nach einer Fix-Runde. Der Reviewer hat **jede** konkrete Angabe gegen die Quelle geprüft und bestätigt, auch die drei nebenbei korrigierten Altfehler zu den randomSpawn-Parametern.
- Fix: an einer Kreuzung glimmen **beide** Stripes grün, nicht einer.
- Fix: `docs/kalibrierung-anleitung.md` behauptete weiterhin eine Deckelung des Master-Pegels auf 0,3, die es seit `82487e7` nicht mehr gibt. Wer sich auf eine Deckelung verlässt, die es nicht gibt, irrt in die gefährliche Richtung.

**Gesamtreview** — Urteil „einsatzbereit mit Vorbehalten", keine Critical. Datenfluss über alle drei Schichten durchgezogen, Koordinaten schichtweise geprüft, kein Spiegel und kein 90-Grad-Fehler im Code. Drei Important-Befunde, alle neu und für Einzelreviews unsichtbar; der erste ist behoben, die anderen beiden stehen unter „Bekannte Schwächen".
- Behoben (`1ab6c7f`): **`RemoteControlledIntParameter` schnitt vor dem Mappen ab.** `newMessage.get(0).intValue()` lief **vor** `PApplet.map`, ein normalisierter 0..1-Float-Fader landete also für jeden Wert unter 1.0 auf `minValue`. Bei `/net/impulse/oscMaxCount` heisst das: Drohnen verstummen, `/net/hitNode` läuft weiter, die Oberfläche reagiert — **Stille, die nach funktionierender Software aussieht**. Betraf ebenso `randomSpawnCount` und `randomSpawnEnabled`, und damit direkt das neue `webui/`.
- Ebenfalls behoben: Fläche aus der Fensterecke gerückt (`59ec5c6`, ein Klick zum Fokussieren überschrieb einen Anker), HUD-Reste (`f01a73a`), Startwarnung auch bei „alles extrapoliert" (`c3d6b43`), gehaltenes `L` (`baa05f4`), `size()`-Kommentar (`753dfd7`), eine schwach unterscheidende Prüfung (`532cdf0`).

## Was in diesem Lauf am Plan und an der Spec geändert wurde

- Spec `20e7d10` — Eintragsbegriff (ein Eintrag ist ein **physischer Punkt**, nicht eine LED, also `2*S + C` Einträge statt `2*S + 2*C`), die Interface-Falle (`LedPositionCalibration` darf **kein** `implements runnableLedEffect` nennen, weil `mixer.java` über `RemoteControlledFloatParameter` an `oscP5` hängt und die Klasse damit für `test/run.sh` unübersetzbar würde — nachgeprüft, `javac` gegen `core.jar` allein bricht bei `mixer.java:10` ab), Farbvorrang in `drawMe()` für beide Stripes eines Kreuzungseintrags, und die Ambient-Spawns aus `1e49346`.
- Plan `6dcdd8f` — der Abschnitt „Prüfe die Artefakte, nicht die eingesammelte Sicht darauf".
- Infrastruktur `8ff8ca3`, `d13efa9`, `56065c8` — die drei Testtreiber-Änderungen oben. Nicht aus dem Plan; sie machen die Prüfkette erst überhaupt lauffähig.
