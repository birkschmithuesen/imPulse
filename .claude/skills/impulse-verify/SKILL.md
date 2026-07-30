---
name: impulse-verify
description: Use when verifying any change to the imPulse sketch - before claiming code compiles, before committing, or when asked to run tests. Covers the unit test suites, the headless compile check for imPulse.pde, and which scripts must never be run because they drive the real installation.
---

# imPulse prüfen

Dieses Projekt ist ein Processing-Sketch ohne Build-System, hat aber zwei
vollwertige Prüfungen. Beide sind schnell und sprechen die Installation
**nicht** an. Nutze sie, statt eine Übersetzung oder einen grünen Test zu
behaupten.

## Die zwei Prüfungen

| Befehl | Prüft | Dauer |
|---|---|---|
| `test/run.sh` | die processing- und netzfreien Klassen, alle Suiten | wenige Sekunden |
| `test/build.sh` | den **kompletten** Sketch einschliesslich `imPulse.pde`, headless | ~10 s |

Beide zusammen sind die Freigabe für einen Commit:

```bash
test/run.sh && test/build.sh
```

Exit 0 aus beiden heisst: Tests grün und der Sketch übersetzt. Alles andere ist
ein Fehlschlag und wird als solcher berichtet, mit der Ausgabe.

## `test/run.sh` — Unit-Tests

Übersetzt die bewusst abhängigkeitsfreien Klassen (`LedColor`,
`ArtNetOutput`, `NodeCrossingStore`, `NodeSelection`, `LedStripeNetworks`,
`TestPatterns` und was in der `SOURCES`-Liste noch dazukommt) gegen `core.jar`
und startet die Suiten aus `test/`.

- Ohne Argumente laufen **alle** Suiten der Default-Liste (Zeile 27 des
  Skripts).
- Eine einzelne Suite: `test/run.sh NodeCrossingStoreTest`
- Es gibt **kein** Testframework. Die Prüfhilfe ist `test/Check.java` von Hand:
  `Check.eq`, `Check.that`, `Check.near` (Fliesskomma mit Toleranz), am Ende
  `System.exit(Check.report("SuiteName"))`.
- Eine neue Klasse wird nur geprüft, wenn sie in der `SOURCES`-Liste steht,
  und eine neue Suite nur, wenn sie in der Default-Liste steht. Beides von
  Hand eintragen.

Damit eine Klasse hier prüfbar bleibt, darf sie **nichts** aus `processing.*`,
`oscP5.*` oder `netP5.*` benutzen. Das ist der Grund, weshalb die Logik in
diesem Projekt konsequent aus den Effekten herausgezogen ist.

## `test/build.sh` — Übersetzungsprüfung des Sketches

Fängt genau die Fehler, die `run.sh` nicht sehen kann: alles in `imPulse.pde`
und in den Klassen, die an oscP5 oder Syphon hängen.

```
$ test/build.sh
Finished.

$ test/build.sh          # mit einem Tippfehler in der .pde
expecting SEMI, found 'oscP5'
imPulse.pde:81:4:81:4: Syntax error, maybe a missing semicolon?
```

Wissenswertes:

- `processing-java` ist auf diesem Rechner **nicht** installiert. Das Skript
  ruft stattdessen `processing.mode.java.Commander` aus `pde.jar` direkt auf —
  genau das, was der `processing-java`-Wrapper tut. Die Klassenpfad-Rezeptur
  stammt aus Processings eigenem Werkzeug „Install processing-java"
  (`processing/app/tools/InstallCommander.class`).
- Der Sketch wird nur **gelesen**. Alles Erzeugte landet unter `TMPDIR` und
  wird danach gelöscht; der Arbeitsbaum bleibt unberührt und eine parallel
  laufende Processing-IDE wird nicht gestört.
- Der Sketch wird **nicht gestartet**. Es geht nichts ans Netz, die LEDs
  bleiben unangetastet.
- Die Bibliotheken `oscP5` und `Syphon` löst Processing aus dem Sketchbook
  (`~/Documents/Processing/libraries`), nicht aus `libraries/` im Repo. Schlägt
  die Übersetzung mit einer fehlenden Bibliothek fehl, ist das der Grund.

## Was niemals gestartet wird

Drei Skripte in `test/` sprechen die **echte Installation** an und sind
deshalb nicht Teil der Default-Suite:

- `test/run.sh TimingProbe` — sendet 40 Hz ans echte Netz
- `test/run.sh PollProbe` — fragt die Controller per ArtPoll ab
- `test/run.sh PatternProbe` — speist Testbilder in die Stripes

**Nicht aufrufen**, auch nicht „nur zum Prüfen". Nur auf ausdrückliche
Aufforderung und einzeln.

## Konventionen, die eine Prüfung nicht fängt

- **Quellen sind reines ASCII.** Jede `.java` und `.pde` in diesem Projekt
  enthält kein Nicht-ASCII-Zeichen; deutsche Kommentare werden transliteriert
  (`ue oe ae ss`): „uebernommen", „geloescht", „waehrend". **Markdown**
  (`docs/`, `CLAUDE.md`, `README.md`) verwendet dagegen echte Umlaute.
- **Einrückung**: 2 Leerzeichen in neueren Dateien, Tabs in
  `LedStripeNetworks.java`. Beim Ändern die Einrückung der Datei fortsetzen,
  nicht umformatieren.
- **`masterLevel` nicht anfassen.** Auslieferungswert 0.1, Obergrenze 0.3 —
  eine Sicherheitsanforderung gegen Spannungsabfall auf den 10-m-Stripes,
  keine Einstellung.
- **Farbwerte sind 0..1** und werden erst am Ausgang geklemmt.
