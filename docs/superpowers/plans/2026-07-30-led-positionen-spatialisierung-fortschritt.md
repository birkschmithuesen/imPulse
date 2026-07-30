# Fortschritt und Übergabe — LED-Positionen und Vierkanal-Spatialisierung

Plan: [`2026-07-30-led-positionen-spatialisierung.md`](2026-07-30-led-positionen-spatialisierung.md)
Spec: [`../specs/2026-07-30-led-positionen-spatialisierung-design.md`](../specs/2026-07-30-led-positionen-spatialisierung-design.md)

Dies ist das Protokoll des subagentengetriebenen Umsetzungslaufs vom 2026-07-30 (Aufgaben 1–8), versioniert als Übergabe an weitere Sitzungen.

Das Arbeits-Ledger der `superpowers:subagent-driven-development`-Skill liegt unter `.superpowers/sdd/<plan-name>/progress.md` und ist per `.superpowers/sdd/.gitignore` (`*`) **absichtlich nicht versioniert** — Briefs, Diffs und Reports darin sind aus Plan und `git log` reproduzierbar. Das Ledger selbst ist es nicht, deshalb steht sein Inhalt hier. Eine Folgesitzung führt ihr eigenes Ledger in der Kladde und trägt Erkenntnisse, die über die Sitzung hinaus gelten, hier nach.

## Stand

| | |
|---|---|
| Branch | `grabicz26` |
| Letzter Commit dieses Laufs | `56065c8` |
| Fertig und abgenommen | Aufgaben **1–8** (Stufe 1 vollständig, Stufe 2 begonnen) |
| Offen | Aufgaben **9–17** |
| Testsuiten grün | 8, `test/run.sh` Exit 0 |
| Übersetzungsprüfung | `test/build.sh` Exit 0 (seit `56065c8`, siehe unten) |

Aufgabe 9 ist der nächste Schritt. Die Modellwahl je Aufgabe steht als Tabelle im Plan.

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

## Zwei offene Punkte, die eine Entscheidung des Betreibers brauchen

- **`LedPositionCalibration` wächst.** Nach Aufgabe 8 bei 236 Zeilen, nur für Arbeitsliste und Navigation; die Aufgaben 9–11 legen Flächengeometrie, acht Tastenbefehle und das Rendering in dieselbe Klasse. Zwei Reviewer haben unabhängig „god object" in Aussicht gestellt. In den Reviews 9–11 ausdrücklich darauf achten. Ein Aufteilen wäre eine Abweichung von der Spec und damit nicht von einer Sitzung allein zu entscheiden.
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

## Was in diesem Lauf am Plan und an der Spec geändert wurde

- Spec `20e7d10` — Eintragsbegriff (ein Eintrag ist ein **physischer Punkt**, nicht eine LED, also `2*S + C` Einträge statt `2*S + 2*C`), die Interface-Falle (`LedPositionCalibration` darf **kein** `implements runnableLedEffect` nennen, weil `mixer.java` über `RemoteControlledFloatParameter` an `oscP5` hängt und die Klasse damit für `test/run.sh` unübersetzbar würde — nachgeprüft, `javac` gegen `core.jar` allein bricht bei `mixer.java:10` ab), Farbvorrang in `drawMe()` für beide Stripes eines Kreuzungseintrags, und die Ambient-Spawns aus `1e49346`.
- Plan `6dcdd8f` — der Abschnitt „Prüfe die Artefakte, nicht die eingesammelte Sicht darauf".
- Infrastruktur `8ff8ca3`, `d13efa9`, `56065c8` — die drei Testtreiber-Änderungen oben. Nicht aus dem Plan; sie machen die Prüfkette erst überhaupt lauffähig.
