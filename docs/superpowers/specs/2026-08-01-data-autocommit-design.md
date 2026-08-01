# Periodischer Auto-Commit für live editierte Daten-Dateien

Entwurf, 2026-08-01. Branch `feature/data-autocommit` (Worktree
`~/github/imPulse-autocommit`, abgezweigt von `master`).

## Problem

Presets, Farbpaletten und Energie-Level werden im laufenden Betrieb vom
Web-UI-Server bzw. vom Sketch direkt im Dateisystem geändert. Es sind
git-getrackte Dateien, aber niemand committet sie. Nachgezogen wurde bisher von
Hand (Git-Historie: „random1-Preset vom Live-Betrieb nachgezogen"). Wird ein
Rechner neu aufgesetzt, bevor jemand daran denkt, ist die Live-Arbeit weg.

## Entscheidung in einem Satz

Ein Hintergrund-Thread im ohnehin laufenden Web-UI-Server committet alle
10 Minuten **lokal** — aber nur, wenn sich unter einer festen Liste von Pfaden
tatsächlich etwas geändert hat, und **niemals mit Push**.

## Umfang der Überwachung

Überwacht werden (Pathspecs relativ zur Repo-Wurzel; ein Pfad, den es in
diesem Checkout nicht gibt, ist kein Fehler, sondern der Normalfall — die
Feature-Branches sind noch nicht alle in `master`):

| Pfad | Begründung |
| --- | --- |
| `data/presets/*.txt` | vom Web-UI ausgelöst, vom Sketch geschrieben — der Kernfall |
| `supercollider/presets/*.txt` | ein Preset ist **ein Name, zwei Dateien** (CLAUDE.md, „Klangseite"). Nur die Licht-Hälfte zu sichern hiesse, die Szene käme später optisch zurück und klanglich nicht — genau der Fehlerfall, den `PresetManager.forwardToSound()` schon einmal verhindert |
| `data/colorPalettes.txt` | im Web-UI editierbar (kommt mit `feature/webui-colors-and-tree-toggle`) |
| `data/energyLevels.txt` | im Web-UI editierbar (kommt mit `feature/song-structure-dramaturgie`) |
| `data/stripeTrees.txt` | „automatisch erzeugter Best-Guess, von Birk von Hand korrigiert" (CLAUDE.md) — editierte Konfiguration |
| `data/nodeCrossings.txt` | Node-Kalibrierung, nur auf Tastendruck `S` geschrieben |
| `data/ledPositions.txt` | Positionskalibrierung, ebenfalls nur auf `S` |

Die zwei Kalibrierdateien stehen bewusst mit auf der Liste: sie ändern sich nur
auf eine ausdrückliche Operator-Aktion hin (nie periodisch, also kein
Commit-Geflacker), und ihr Verlust wäre der teuerste von allen — eine
Kalibriersitzung am Netz kostet Stunden.

**Nicht überwacht:**

- `data/remoteSettings.txt` — steht in `.gitignore`. Boot-Snapshot, wird von
  imPulse bei jedem Start neu geschrieben. `git add` auf einen ignorierten Pfad
  schlägt ausserdem fehl.
- `data/songStructureState.txt` — in **keinem** Branch getrackt und laut Brief
  vom Sketch bei jedem Levelwechsel neu geschrieben. Das ist Laufzeitstatus,
  keine Konfiguration; auf der Liste erzeugte er alle 10 Minuten einen Commit
  und damit genau die feste Taktung unabhängig vom Zustand, die ausgeschlossen
  werden sollte.

Die Trennlinie lautet also: **wer die Datei schreibt, entscheidet nicht — wann
sie sich ändert, entscheidet.** Ändert sie sich nur, wenn ein Mensch etwas
entschieden hat (Preset speichern, Palette ändern, `S` in der Kalibrierung),
ist sie Konfiguration. Ändert sie sich von selbst, während die Show läuft, ist
sie Status.

## Aufbau

Neues Modul `webui/autocommit.py`, **ohne** Flask-Abhängigkeit — dasselbe
Muster wie die processing-freien Java-Klassen im Sketch: die prüfbare Logik
hängt an nichts, was eine Laufzeitumgebung braucht.

Reine Funktionen (ohne Prozessaufruf, direkt testbar):

- `parse_porcelain(text) -> list[PorcelainEntry]` — zerlegt die Ausgabe von
  `git status --porcelain -z` bzw. `--porcelain`. Muss Umbenennungen
  (`R  alt -> neu`), in Anführungszeichen gesetzte Pfade mit Sonderzeichen und
  unversionierte Dateien (`??`) beherrschen.
- `conflicted(entries) -> list[str]` — Einträge mit Konflikt-Status
  (`UU AA DD AU UA DU UD`).
- `matches_watchlist(path, patterns) -> bool` — `fnmatch` je Pfadsegment,
  damit `data/presets/*.txt` nicht versehentlich auf
  `data/presets/unter/ordner/x.txt` passt.
- `build_commit_message(paths, when) -> str` — Betreff
  `Auto-Commit: Live-Daten-Sicherung <YYYY-MM-DD HH:MM>`, darunter die
  Dateiliste (ab `MAX_LISTED = 12` gekürzt mit „… und N weitere"), zuletzt
  eine Zeile, die sagt, dass der Commit automatisch und **nicht gepusht** ist.
- `interrupted_operation(git_dir_entries) -> str | None` — erkennt einen
  laufenden manuellen Git-Vorgang an `MERGE_HEAD`, `CHERRY_PICK_HEAD`,
  `REVERT_HEAD`, `BISECT_LOG`, `rebase-merge/`, `rebase-apply/`.

Zustandsbehaftet:

- `AutoCommitter(repo_root, patterns, runner=...)` — `check_and_commit()`
  liefert ein `AutoCommitResult` (`status` ∈ `committed | clean | skipped |
  error`, `paths`, `detail`, `at`). Der `runner` ist ein injizierbarer
  Callable, damit die Tests ohne echten Git-Prozess auskommen; die
  Voreinstellung ruft `subprocess.run` mit Timeout auf.
- `AutoCommitScheduler(committer, interval_s, clock)` — Daemon-Thread, wartet
  mit `threading.Event.wait()` (damit `stop()` sofort greift), jede Runde in
  `try/except Exception`. Hält das letzte Ergebnis und die letzte Laufzeit
  hinter einem `Lock` für die Statusanzeige bereit.

## Ablauf einer Runde

1. `git rev-parse --git-dir` — kein Git-Checkout? Einmal melden, Thread endet.
2. `git symbolic-ref -q HEAD` — detached HEAD? **überspringen**. Ein Commit
   dort hinge an keinem Branch und wäre nach dem nächsten Checkout nur noch
   über das Reflog erreichbar — ein Sicherungsnetz, das nicht hält, ist
   schlimmer als keins, weil die Statusanzeige „gesichert" behauptet.
3. Verzeichnisinhalt des `.git`-Dir prüfen → `interrupted_operation`.
   Laufender Merge/Rebase/Cherry-Pick: **überspringen**, nie in einen
   manuellen Vorgang hineingreifen.
4. `git status --porcelain -z --untracked-files=normal -- <patterns>` — nur die
   überwachten Pfade. Konflikt-Status darunter: **überspringen**.
5. Nichts geändert: `clean`, kein Commit.
6. Sonst `git add -- <konkrete Pfade>` und
   `git commit --no-verify=nein -m <msg> -- <dieselben Pfade>`.

Zu Schritt 6, zwei bewusste Details:

- **Die Pfade sind die aus der Porcelain-Ausgabe, nicht die Muster.** Damit
  steht in der Commit-Message genau das, was drin ist.
- **Die Pathspec steht auch am `commit`.** In dieser Form committet Git den
  Arbeitsbaum-Zustand *nur* dieser Pfade und ignoriert alles, was der Operator
  sonst gerade gestaged hat. Ohne sie würde eine halbfertige Handarbeit im
  Index in den Auto-Commit gezogen — ein Auto-Commit, der fremde Änderungen
  mitnimmt, ist genau der Vertrauensbruch, der das Feature unbrauchbar macht.
- **`git add -A` und `git add .` kommen im Code nicht vor**, und ein Test
  greppt darauf. Das ist eine harte Anforderung, keine Konvention.
- Commit-Hooks werden **nicht** umgangen (`--no-verify` wird nicht gesetzt):
  scheitert ein Hook, ist das ein `error`-Ergebnis in der Statusanzeige und
  eine Meldung im Log, kein stiller Sonderweg.

## Konfiguration

| Schalter | Umgebungsvariable | Vorgabe |
| --- | --- | --- |
| `--no-autocommit` | `IMPULSE_AUTOCOMMIT=0` | an |
| `--autocommit-interval <s>` | `IMPULSE_AUTOCOMMIT_INTERVAL` | 600 |

Voreinstellung ist **an**: der Auslöser des Features ist ein Rechner, an dem
niemand daran denkt. Ein Entwickler-Checkout, der das nicht will, schaltet es
ab — das ist der seltenere Fall und der, in dem jemand hinschaut.

## Sichtbarkeit im Web-UI

- `GET /api/autocommit` liefert `{enabled, intervalSeconds, lastRunAt,
  lastCommitAt, lastStatus, lastDetail, lastPaths}`. Zeiten als
  Unix-Sekunden, die Formulierung „vor 3 Minuten" macht der Browser — der
  Server kennt die Uhr des Betrachters nicht.
- Der Bootstrap-Block der Seite enthält denselben Stand, damit die Zeile schon
  beim ersten Rendern stimmt; danach pollt `app.js` alle 60 s.
- Angezeigt wird eine Zeile unter der bestehenden Statusleiste:
  „Automatische Sicherung: alle 10 min, zuletzt vor 3 Minuten" mit dem Zusatz
  „lokaler Commit, kein Push — zum Übertragen weiterhin `git push` von Hand".
  Bei `enabled=false`: „Automatische Sicherung: aus". Bei `error`: die
  Fehlermeldung im Klartext, denn ein stiller Fehler wäre hier das Schlimmste.

**Offener Punkt für den Merge:** der Hinweistext bei der Farbpalette („liegt
nur auf diesem Rechner") existiert auf diesem von `master` abgezweigten Branch
noch nicht — die Palette kommt erst mit `feature/webui-colors-and-tree-toggle`
bzw. `feature/webui-friendly-labels`. Wer die Branches zusammenführt, zieht
den Satz dort auf „wird alle 10 Minuten lokal gesichert, gepusht wird von
Hand" nach. Hier steht die Aussage in der allgemeinen Statuszeile und im
`webui/README.md`.

## Tests

Eigene Suite `webui/test_autocommit.py`, Standardbibliothek allein, nach dem
Muster von `webui/test_webui.py`:

- **Porcelain-Parser**: geänderte, neue, gelöschte, umbenannte und in
  Anführungszeichen stehende Pfade; Konflikt-Status wird erkannt.
- **Watchlist-Matching**: `data/presets/x.txt` passt, `data/presets/a/b.txt`
  passt nicht, `data/remoteSettings.txt` passt nicht.
- **Commit-Message**: eine Datei, mehrere Dateien, Kürzung über `MAX_LISTED`,
  Zeitstempel im Betreff, der Nicht-Push-Hinweis im Rumpf.
- **`interrupted_operation`**: jeder der sechs Marker, und der Normalfall.
- **`AutoCommitter` mit falschem Runner**: sauberer Baum → kein Commit;
  Änderungen → genau zwei Aufrufe mit `--` und expliziten Pfaden; Konflikt →
  `skipped`; detached HEAD → `skipped`; Git-Fehler → `error` **ohne**
  Exception; Timeout → `error` ohne Exception.
- **Scheduler**: ein Committer, der wirft, bringt den Thread nicht um; `stop()`
  beendet ihn ohne das volle Intervall abzuwarten.
- **Grep-Test (harte Sicherheitsanforderung)**: keine Datei unter `webui/`
  enthält `add -A`, `add --all`, `add .` oder `commit -a`.
- **Integrationstest gegen echtes Git** (übersprungen, wenn `git` fehlt): ein
  temporäres Repo, eine geänderte Preset-Datei **und** eine geänderte, nicht
  überwachte Datei. Erwartung: genau ein neuer Commit, er enthält die
  Preset-Datei und die andere Datei ist danach **weiterhin schmutzig**. Das ist
  der eigentliche Beweis für „niemals pauschales Staging" — der Grep-Test
  prüft nur die Schreibweise, dieser das Verhalten.

## Bewusst nicht gebaut

- **Kein automatischer Push.** Mehrere Checkouts arbeiten parallel am selben
  Remote (Windows-Laptop, Hetzner-Test-Deploy, mehrere Worktrees); ein
  automatischer Push wäre eine Fernwirkung ohne Entscheidung. Analog zur
  Branch-Konvention in CLAUDE.md, die Merge und Force-Push ausdrücklich als
  Einzelentscheidung führt.
- **Kein „Jetzt sichern"-Knopf** im UI. Der Auftrag ist Sichtbarkeit; ein
  Knopf, der aus dem Browser heraus Git-Zustand ändert, ist eine zweite
  Auslöseart mit eigenen Fehlerfällen und ohne Anlass.
- **Keine Commit-Zusammenfassung über mehrere Runden** (Amend/Squash). Ein
  Amend ändert bereits geschriebene Historie; ein Sicherungsnetz darf
  ausschliesslich anhängen.
