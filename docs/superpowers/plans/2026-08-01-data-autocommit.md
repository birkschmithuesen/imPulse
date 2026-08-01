# Periodischer Auto-Commit — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Web-UI-Server sichert live editierte Daten-Dateien alle 10 Minuten
durch einen lokalen Git-Commit — nur bei echter Änderung, niemals mit Push.

**Architecture:** Ein flask-freies Modul `webui/autocommit.py` mit reiner,
testbarer Logik (Porcelain-Parser, Pfad-Matching, Commit-Message) plus einem
`AutoCommitter` mit injizierbarem Git-Runner und einem Daemon-Thread-Scheduler.
`webui/server.py` startet ihn beim Hochfahren und reicht seinen Zustand über
`GET /api/autocommit` und den Bootstrap-Block ans UI.

**Tech Stack:** Python 3 Standardbibliothek (`subprocess`, `threading`,
`fnmatch`, `unittest`), Flask nur in `server.py`, Vanilla-JS im UI.

## Global Constraints

- **Kein `git push`, an keiner Stelle.**
- **Kein `git add -A`, `git add --all`, `git add .`, `git commit -a`** — ein
  Grep-Test hält das nach.
- Kein Fehler des Mechanismus darf den Webserver beenden: jede Runde in
  `try/except Exception`, geloggt auf stdout mit Präfix `[autocommit]`.
- Keine Fremdabhängigkeit in `webui/autocommit.py` und
  `webui/test_autocommit.py` — Standardbibliothek allein, wie
  `webui/test_webui.py`.
- Alle Kommentare, Log- und UI-Texte auf Deutsch, ohne Umlaute in
  Bezeichnern; im Fliesstext sind Umlaute üblich (bestehender Stil in
  `server.py`).
- Überwachte Pfade genau diese sieben Muster, in dieser Reihenfolge:
  `data/presets/*.txt`, `supercollider/presets/*.txt`,
  `data/colorPalettes.txt`, `data/energyLevels.txt`, `data/stripeTrees.txt`,
  `data/nodeCrossings.txt`, `data/ledPositions.txt`.
- Vorgabe-Intervall 600 Sekunden, Untergrenze 10 Sekunden.

---

## File Structure

- `webui/autocommit.py` (neu) — die gesamte Auto-Commit-Logik. Kennt weder
  Flask noch das Parameter-Modell.
- `webui/test_autocommit.py` (neu) — Suite dazu.
- `webui/server.py` (ändern) — CLI-Flags, Start des Schedulers, Endpoint,
  Bootstrap.
- `webui/templates/index.html` (ändern) — eine Zeile für die Statusanzeige.
- `webui/static/app.js` (ändern) — Anzeige und 60-s-Poll.
- `webui/static/style.css` (ändern) — Stil der Zeile.
- `webui/README.md`, `CLAUDE.md` (ändern) — Dokumentation.

---

### Task 1: Reine Logik in `webui/autocommit.py`

**Files:**
- Create: `webui/autocommit.py`
- Test: `webui/test_autocommit.py`

**Interfaces:**
- Produces:
  - `WATCHED_PATTERNS: tuple[str, ...]`
  - `PorcelainEntry` — `dataclass(status: str, path: str)`
  - `parse_porcelain(text: str) -> list[PorcelainEntry]` (NUL-getrennte
    Ausgabe von `git status --porcelain -z`)
  - `CONFLICT_STATUSES: frozenset[str]`
  - `conflicted(entries) -> list[str]`
  - `matches_watchlist(path: str, patterns=WATCHED_PATTERNS) -> bool`
  - `build_commit_message(paths: list[str], when: datetime) -> str`
  - `MAX_LISTED: int = 12`
  - `interrupted_operation(names: Iterable[str]) -> Optional[str]`

- [ ] **Step 1: Write the failing tests**

`webui/test_autocommit.py`:

```python
#!/usr/bin/env python3
"""Tests fuer den periodischen Auto-Commit der Live-Daten.

Standardbibliothek allein -- kein Flask, kein laufender Server. Die
Git-Aufrufe werden ueber einen eingesetzten Runner geprueft; nur der
Integrationstest am Ende benutzt ein echtes, temporaeres Repository.

    python3 webui/test_autocommit.py
"""

import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import autocommit  # noqa: E402
from autocommit import (build_commit_message, conflicted,  # noqa: E402
                        interrupted_operation, matches_watchlist,
                        parse_porcelain)


def z(*records):
    """Baut eine NUL-getrennte Porcelain-Ausgabe wie `git status -z`."""
    return "".join(r + "\0" for r in records)


class PorcelainTest(unittest.TestCase):
    def test_reads_status_and_path(self):
        entries = parse_porcelain(z(" M data/presets/random1.txt"))
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0].status, " M")
        self.assertEqual(entries[0].path, "data/presets/random1.txt")

    def test_untracked_file(self):
        entries = parse_porcelain(z("?? data/presets/neu.txt"))
        self.assertEqual(entries[0].status, "??")
        self.assertEqual(entries[0].path, "data/presets/neu.txt")

    def test_rename_uses_the_new_path(self):
        # -z schreibt bei R den NEUEN Pfad zuerst, den alten als eigenen Satz.
        entries = parse_porcelain(z("R  data/presets/neu.txt",
                                    "data/presets/alt.txt"))
        self.assertEqual([e.path for e in entries], ["data/presets/neu.txt"])

    def test_ignores_empty_records(self):
        self.assertEqual(parse_porcelain(""), [])
        self.assertEqual(parse_porcelain("\0\0"), [])

    def test_path_with_spaces(self):
        entries = parse_porcelain(z(" M data/presets/mit leer.txt"))
        self.assertEqual(entries[0].path, "data/presets/mit leer.txt")


class ConflictTest(unittest.TestCase):
    def test_detects_unmerged(self):
        entries = parse_porcelain(z("UU data/presets/a.txt",
                                    " M data/presets/b.txt"))
        self.assertEqual(conflicted(entries), ["data/presets/a.txt"])

    def test_clean_case(self):
        entries = parse_porcelain(z(" M data/presets/b.txt"))
        self.assertEqual(conflicted(entries), [])

    def test_every_conflict_status(self):
        for status in ("DD", "AU", "UD", "UA", "DU", "AA", "UU"):
            entries = parse_porcelain(z("%s data/presets/a.txt" % status))
            self.assertEqual(conflicted(entries), ["data/presets/a.txt"],
                             "Status %s nicht als Konflikt erkannt" % status)


class WatchlistTest(unittest.TestCase):
    def test_preset_matches(self):
        self.assertTrue(matches_watchlist("data/presets/random1.txt"))

    def test_sound_preset_matches(self):
        self.assertTrue(matches_watchlist("supercollider/presets/random1.txt"))

    def test_single_files_match(self):
        for path in ("data/colorPalettes.txt", "data/energyLevels.txt",
                     "data/stripeTrees.txt", "data/nodeCrossings.txt",
                     "data/ledPositions.txt"):
            self.assertTrue(matches_watchlist(path), path)

    def test_boot_snapshot_does_not_match(self):
        self.assertFalse(matches_watchlist("data/remoteSettings.txt"))

    def test_runtime_state_does_not_match(self):
        self.assertFalse(matches_watchlist("data/songStructureState.txt"))

    def test_star_does_not_cross_directories(self):
        self.assertFalse(matches_watchlist("data/presets/unter/ordner.txt"))

    def test_backslashes_are_normalised(self):
        # Git meldet immer Schraegstriche, aber auf Windows kommen Pfade auch
        # aus os.path -- der Vergleich darf daran nicht scheitern.
        self.assertTrue(matches_watchlist("data\\presets\\random1.txt"))


class CommitMessageTest(unittest.TestCase):
    WHEN = datetime.datetime(2026, 8, 1, 17, 45, 0)

    def test_subject_carries_timestamp(self):
        text = build_commit_message(["data/presets/a.txt"], self.WHEN)
        self.assertEqual(text.splitlines()[0],
                         "Auto-Commit: Live-Daten-Sicherung 2026-08-01 17:45")

    def test_body_lists_every_file(self):
        paths = ["data/presets/a.txt", "data/colorPalettes.txt"]
        text = build_commit_message(paths, self.WHEN)
        for path in paths:
            self.assertIn(path, text)

    def test_long_list_is_truncated(self):
        paths = ["data/presets/p%02d.txt" % i for i in range(20)]
        text = build_commit_message(paths, self.WHEN)
        self.assertIn("data/presets/p00.txt", text)
        self.assertNotIn("data/presets/p19.txt", text)
        self.assertIn("und 8 weitere", text)

    def test_says_it_was_not_pushed(self):
        text = build_commit_message(["data/presets/a.txt"], self.WHEN)
        self.assertIn("nicht gepusht", text)

    def test_second_line_is_blank(self):
        text = build_commit_message(["data/presets/a.txt"], self.WHEN)
        self.assertEqual(text.splitlines()[1], "")


class InterruptedOperationTest(unittest.TestCase):
    def test_none_when_quiet(self):
        self.assertIsNone(interrupted_operation(["HEAD", "config", "objects"]))

    def test_each_marker(self):
        for marker in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD",
                       "BISECT_LOG", "rebase-merge", "rebase-apply"):
            self.assertIsNotNone(interrupted_operation(["HEAD", marker]),
                                 "%s nicht erkannt" % marker)

    def test_names_the_marker(self):
        self.assertIn("MERGE_HEAD", interrupted_operation(["MERGE_HEAD"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `python3 webui/test_autocommit.py`
Expected: `ModuleNotFoundError: No module named 'autocommit'`

- [ ] **Step 3: Write `webui/autocommit.py` (nur der reine Teil)**

```python
#!/usr/bin/env python3
"""Periodischer Auto-Commit der live editierten Daten-Dateien.

Presets, Farbpaletten, Energie-Level und die Kalibrierdateien werden im
laufenden Betrieb geaendert -- vom Web-UI, vom Sketch, von Hand. Es sind
git-getrackte Dateien, aber niemand committet sie; nachgezogen wurde das
bisher per Hand. Dieses Modul sichert sie in festem Takt LOKAL.

Bewusst KEIN Push: mehrere Checkouts arbeiten parallel am selben Remote
(Live-Laptop, Test-Deploy, Worktrees). Der Commit ist ein Sicherungsnetz,
das Uebertragen bleibt eine bewusste Einzelentscheidung -- dieselbe Regel,
die CLAUDE.md fuer Merges und Force-Pushes aufstellt.

Das Modul haengt an nichts ausser der Standardbibliothek (kein Flask), damit
die Logik ohne laufenden Server pruefbar ist -- dasselbe Muster wie die
processing-freien Klassen im Sketch.
"""

from __future__ import annotations

import datetime
import fnmatch
import os
from dataclasses import dataclass
from typing import Iterable, List, Optional, Sequence

# ---------------------------------------------------------------------------
# Was ueberwacht wird
#
# Die Trennlinie ist NICHT "wer schreibt die Datei", sondern "wann aendert sie
# sich": aendert sie sich nur, wenn ein Mensch etwas entschieden hat (Preset
# speichern, Palette aendern, S in der Kalibrierung), ist sie Konfiguration
# und gehoert gesichert. Aendert sie sich von selbst, waehrend die Show
# laeuft, ist sie Laufzeitstatus und gehoert NICHT hierher -- sonst entstuende
# alle zehn Minuten ein Commit, also genau die feste Taktung unabhaengig vom
# Zustand, die vermieden werden soll.
#
# Deshalb NICHT auf der Liste:
#   data/remoteSettings.txt      -- gitignored, Boot-Snapshot bei jedem Start
#   data/songStructureState.txt  -- Laufzeitstatus, bei jedem Levelwechsel neu
# ---------------------------------------------------------------------------

WATCHED_PATTERNS = (
    "data/presets/*.txt",
    # Ein Preset ist ein Name und ZWEI Dateien (siehe CLAUDE.md, Klangseite).
    # Nur die Licht-Haelfte zu sichern hiesse: die Szene kommt spaeter optisch
    # zurueck und klanglich nicht.
    "supercollider/presets/*.txt",
    "data/colorPalettes.txt",
    "data/energyLevels.txt",
    "data/stripeTrees.txt",
    # Kalibrierung: aendert sich nur auf Tastendruck S, nie von selbst -- und
    # ihr Verlust waere der teuerste, eine Sitzung am Netz kostet Stunden.
    "data/nodeCrossings.txt",
    "data/ledPositions.txt",
)

# Hoechstens so viele Dateinamen stehen im Commit-Rumpf, der Rest wird gezaehlt.
MAX_LISTED = 12

# Status-Kuerzel aus `git status --porcelain`, die einen Merge-Konflikt
# bedeuten. Bei einem davon wird nicht committet -- in einen laufenden
# manuellen Vorgang greift der Mechanismus nie hinein.
CONFLICT_STATUSES = frozenset(("DD", "AU", "UD", "UA", "DU", "AA", "UU"))

# Dateien/Ordner im .git-Verzeichnis, die einen unterbrochenen Vorgang
# anzeigen.
INTERRUPTION_MARKERS = (
    ("MERGE_HEAD", "ein Merge ist offen"),
    ("CHERRY_PICK_HEAD", "ein Cherry-Pick ist offen"),
    ("REVERT_HEAD", "ein Revert ist offen"),
    ("BISECT_LOG", "eine Bisect-Sitzung laeuft"),
    ("rebase-merge", "ein Rebase laeuft"),
    ("rebase-apply", "ein Rebase laeuft"),
)


@dataclass(frozen=True)
class PorcelainEntry:
    """Eine Zeile aus `git status --porcelain`."""

    status: str
    path: str


def parse_porcelain(text: str) -> List[PorcelainEntry]:
    """Zerlegt die NUL-getrennte Ausgabe von ``git status --porcelain -z``.

    Die -z-Form wird benutzt, weil sie Pfade mit Leerzeichen, Anfuehrungs-
    zeichen und Umlauten woertlich uebergibt statt sie zu escapen -- ein
    Preset-Name wie ``mit leer.txt`` waere in der Standardform zitiert und
    muesste hier wieder ausgepackt werden.

    Bei einer Umbenennung (``R``) schreibt Git zwei Saetze: erst den neuen
    Pfad, dann den alten. Nur der neue interessiert -- der alte wird durch die
    Pathspec des naechsten `git add` ohnehin mitgeloescht.
    """
    entries: List[PorcelainEntry] = []
    records = text.split("\0")
    index = 0
    while index < len(records):
        record = records[index]
        index += 1
        if len(record) < 4:
            continue
        status = record[:2]
        entries.append(PorcelainEntry(status=status, path=record[3:]))
        if status[0] in ("R", "C"):
            index += 1  # der Herkunftspfad, gehoert zum selben Eintrag
    return entries


def conflicted(entries: Iterable[PorcelainEntry]) -> List[str]:
    """Pfade mit Merge-Konflikt."""
    return [e.path for e in entries if e.status in CONFLICT_STATUSES]


def matches_watchlist(path: str,
                      patterns: Sequence[str] = WATCHED_PATTERNS) -> bool:
    """Passt der Pfad auf eines der Muster?

    Segmentweise, damit ``data/presets/*.txt`` nicht versehentlich auch
    ``data/presets/unter/ordner.txt`` einschliesst -- ``fnmatch`` allein laesst
    ``*`` ueber Schraegstriche hinweglaufen.
    """
    parts = path.replace("\\", "/").split("/")
    for pattern in patterns:
        expected = pattern.split("/")
        if len(expected) != len(parts):
            continue
        if all(fnmatch.fnmatchcase(part, want)
               for part, want in zip(parts, expected)):
            return True
    return False


def build_commit_message(paths: Sequence[str],
                         when: datetime.datetime) -> str:
    """Commit-Text aus der Liste der geaenderten Dateien."""
    subject = ("Auto-Commit: Live-Daten-Sicherung %s"
               % when.strftime("%Y-%m-%d %H:%M"))
    listed = list(paths[:MAX_LISTED])
    lines = [subject, ""]
    lines.extend("  %s" % path for path in listed)
    rest = len(paths) - len(listed)
    if rest > 0:
        lines.append("  ... und %d weitere" % rest)
    lines.extend([
        "",
        "Automatisch vom Web-UI-Server gesichert (webui/autocommit.py).",
        "Nur lokal committet, nicht gepusht -- das Uebertragen bleibt eine",
        "bewusste Entscheidung von Hand.",
    ])
    return "\n".join(lines) + "\n"


def interrupted_operation(names: Iterable[str]) -> Optional[str]:
    """Laeuft im .git-Verzeichnis gerade ein manueller Vorgang?

    ``names`` ist der Verzeichnisinhalt des .git-Dir. Liegt einer der Marker
    dort, haelt der Auto-Commit still: in einen offenen Merge oder Rebase
    hineinzucommitten waere ein Eingriff in fremde Handarbeit.
    """
    present = set(names)
    for marker, description in INTERRUPTION_MARKERS:
        if marker in present:
            return "%s (%s)" % (description, marker)
    return None
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `python3 webui/test_autocommit.py`
Expected: alle Tests `ok`

- [ ] **Step 5: Commit**

```bash
git add webui/autocommit.py webui/test_autocommit.py
git commit -m "Auto-Commit: reine Logik (Porcelain, Watchlist, Commit-Message)"
```

---

### Task 2: `AutoCommitter` mit eingesetztem Git-Runner

**Files:**
- Modify: `webui/autocommit.py`
- Test: `webui/test_autocommit.py`

**Interfaces:**
- Consumes: alles aus Task 1.
- Produces:
  - `GitResult` — `dataclass(code: int, out: str, err: str)`
  - `GitError(Exception)`
  - `run_git(repo_root, args, timeout=GIT_TIMEOUT_S) -> GitResult`
  - `AutoCommitResult` — `dataclass(status: str, detail: str, paths: list[str],
    at: float)`; `status` ∈ `"committed" | "clean" | "skipped" | "error"`
  - `AutoCommitter(repo_root, patterns=WATCHED_PATTERNS, runner=run_git,
    clock=time.time, now=datetime.datetime.now)` mit
    `check_and_commit() -> AutoCommitResult`

- [ ] **Step 1: Write the failing tests** (an `webui/test_autocommit.py` anhängen, vor dem `__main__`-Block)

```python
class FakeGit:
    """Ersatz fuer run_git: liefert vorgegebene Antworten, merkt sich Aufrufe."""

    def __init__(self, replies):
        self.replies = dict(replies)
        self.calls = []

    def __call__(self, repo_root, args, timeout=None):
        self.calls.append(list(args))
        for prefix, reply in self.replies.items():
            if list(args)[:len(prefix)] == list(prefix):
                if isinstance(reply, Exception):
                    raise reply
                return reply
        return autocommit.GitResult(0, "", "")


ON_BRANCH = ("symbolic-ref", "-q", "HEAD")
STATUS = ("status", "--porcelain", "-z")


def committer(replies, tmp="/tmp/repo"):
    fake = FakeGit(replies)
    return autocommit.AutoCommitter(tmp, runner=fake,
                                    git_dir_lister=lambda: ["HEAD"]), fake


class AutoCommitterTest(unittest.TestCase):
    def test_clean_tree_does_not_commit(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, "", ""),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "clean")
        self.assertFalse(any(call[0] == "commit" for call in fake.calls))

    def test_change_leads_to_add_and_commit(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, z(" M data/presets/a.txt"), ""),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "committed")
        self.assertEqual(result.paths, ["data/presets/a.txt"])
        add = next(c for c in fake.calls if c[0] == "add")
        commit = next(c for c in fake.calls if c[0] == "commit")
        self.assertEqual(add, ["add", "--", "data/presets/a.txt"])
        self.assertEqual(commit[-2:], ["--", "data/presets/a.txt"])

    def test_commit_carries_a_pathspec_separator(self):
        """Ohne -- am commit zieht ein fremd gestagter Pfad mit in den Commit."""
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, z(" M data/presets/a.txt"), ""),
        })
        auto.check_and_commit()
        commit = next(c for c in fake.calls if c[0] == "commit")
        self.assertIn("--", commit)

    def test_unwatched_paths_are_never_staged(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(
                0, z(" M data/presets/a.txt", " M imPulse.pde"), ""),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.paths, ["data/presets/a.txt"])
        for call in fake.calls:
            self.assertNotIn("imPulse.pde", call)

    def test_conflict_is_skipped(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, z("UU data/presets/a.txt"), ""),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "skipped")
        self.assertFalse(any(call[0] == "commit" for call in fake.calls))

    def test_detached_head_is_skipped(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(1, "", ""),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "skipped")
        self.assertIn("HEAD", result.detail)
        self.assertFalse(any(call[0] == "commit" for call in fake.calls))

    def test_open_merge_is_skipped(self):
        fake = FakeGit({ON_BRANCH: autocommit.GitResult(0, "refs/heads/x", "")})
        auto = autocommit.AutoCommitter(
            "/tmp/repo", runner=fake,
            git_dir_lister=lambda: ["HEAD", "MERGE_HEAD"])
        result = auto.check_and_commit()
        self.assertEqual(result.status, "skipped")
        self.assertIn("Merge", result.detail)

    def test_failing_commit_reports_error_without_raising(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, z(" M data/presets/a.txt"), ""),
            ("commit",): autocommit.GitResult(1, "", "kein Autor gesetzt"),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "error")
        self.assertIn("kein Autor gesetzt", result.detail)

    def test_git_exception_reports_error_without_raising(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitError("git nicht gefunden"),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "error")
        self.assertIn("git nicht gefunden", result.detail)

    def test_status_is_limited_to_the_watched_patterns(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, "", ""),
        })
        auto.check_and_commit()
        status = next(c for c in fake.calls if c[0] == "status")
        self.assertIn("--", status)
        for pattern in autocommit.WATCHED_PATTERNS:
            self.assertIn(pattern, status)
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `python3 webui/test_autocommit.py`
Expected: `AttributeError: module 'autocommit' has no attribute 'GitResult'`

- [ ] **Step 3: `webui/autocommit.py` erweitern**

Zusätzliche Importe oben ergänzen: `import subprocess`, `import time`.

```python
# Ein Git-Aufruf, der laenger braucht, haengt vermutlich an einem Lock oder
# einem Netzlaufwerk -- dann lieber diese Runde verlieren als den Thread.
GIT_TIMEOUT_S = 10.0


class GitError(Exception):
    """Git liess sich gar nicht erst ausfuehren (fehlt, haengt, Timeout)."""


@dataclass(frozen=True)
class GitResult:
    code: int
    out: str
    err: str


def run_git(repo_root: str, args: Sequence[str],
            timeout: float = GIT_TIMEOUT_S) -> GitResult:
    """Ruft git im Repo auf. Wirft GitError nur, wenn git selbst versagt."""
    try:
        done = subprocess.run(
            ["git"] + list(args),
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise GitError("git nicht gefunden: %s" % exc) from exc
    except subprocess.TimeoutExpired as exc:
        raise GitError("git antwortet nicht (%.0f s): git %s"
                       % (timeout, " ".join(args))) from exc
    except OSError as exc:
        raise GitError("git nicht ausfuehrbar: %s" % exc) from exc
    return GitResult(done.returncode,
                     done.stdout.decode("utf-8", "replace"),
                     done.stderr.decode("utf-8", "replace"))


@dataclass(frozen=True)
class AutoCommitResult:
    status: str          # committed | clean | skipped | error
    detail: str
    paths: List[str]
    at: float


class AutoCommitter:
    """Eine Runde: pruefen, und nur bei echter Aenderung lokal committen."""

    def __init__(self, repo_root, patterns=WATCHED_PATTERNS, runner=run_git,
                 clock=time.time, now=datetime.datetime.now,
                 git_dir_lister=None):
        self.repo_root = repo_root
        self.patterns = tuple(patterns)
        self._run = runner
        self._clock = clock
        self._now = now
        self._list_git_dir = git_dir_lister or self._default_git_dir_lister

    def _default_git_dir_lister(self):
        try:
            result = self._run(self.repo_root, ["rev-parse", "--git-dir"])
        except GitError:
            return []
        if result.code != 0:
            return []
        git_dir = result.out.strip()
        if not os.path.isabs(git_dir):
            git_dir = os.path.join(self.repo_root, git_dir)
        try:
            return os.listdir(git_dir)
        except OSError:
            return []

    def check_and_commit(self) -> AutoCommitResult:
        try:
            return self._round()
        except GitError as exc:
            return self._result("error", str(exc), [])
        except Exception as exc:  # nie den Thread reissen lassen
            return self._result("error", "unerwartet: %r" % (exc,), [])

    # -- innere Schritte ----------------------------------------------------

    def _result(self, status, detail, paths):
        return AutoCommitResult(status=status, detail=detail,
                                paths=list(paths), at=self._clock())

    def _round(self) -> AutoCommitResult:
        branch = self._run(self.repo_root, ["symbolic-ref", "-q", "HEAD"])
        if branch.code != 0:
            # Detached HEAD: ein Commit haenge an keinem Branch und waere nach
            # dem naechsten Checkout nur noch im Reflog -- ein Sicherungsnetz,
            # das nicht haelt, ist schlimmer als keins.
            return self._result("skipped", "HEAD haengt an keinem Branch "
                                           "(detached) -- kein Auto-Commit", [])

        busy = interrupted_operation(self._list_git_dir())
        if busy is not None:
            return self._result("skipped", "uebersprungen: %s" % busy, [])

        status = self._run(self.repo_root,
                           ["status", "--porcelain", "-z", "--"]
                           + list(self.patterns))
        if status.code != 0:
            return self._result("error", status.err.strip() or
                                "git status fehlgeschlagen", [])

        entries = parse_porcelain(status.out)
        clash = conflicted(entries)
        if clash:
            return self._result("skipped",
                                "Merge-Konflikt offen: %s" % ", ".join(clash),
                                [])

        paths = sorted({e.path for e in entries
                        if matches_watchlist(e.path, self.patterns)})
        if not paths:
            return self._result("clean", "nichts geaendert", [])

        added = self._run(self.repo_root, ["add", "--"] + paths)
        if added.code != 0:
            return self._result("error", added.err.strip() or
                                "git add fehlgeschlagen", paths)

        message = build_commit_message(paths, self._now())
        committed = self._run(self.repo_root,
                              ["commit", "-m", message, "--"] + paths)
        if committed.code != 0:
            return self._result("error", committed.err.strip() or
                                committed.out.strip() or
                                "git commit fehlgeschlagen", paths)
        return self._result("committed",
                            "%d Datei(en) gesichert" % len(paths), paths)
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `python3 webui/test_autocommit.py`
Expected: alle Tests `ok`

- [ ] **Step 5: Commit**

```bash
git add webui/autocommit.py webui/test_autocommit.py
git commit -m "Auto-Commit: Committer mit eingesetztem Git-Runner"
```

---

### Task 3: Scheduler-Thread

**Files:**
- Modify: `webui/autocommit.py`
- Test: `webui/test_autocommit.py`

**Interfaces:**
- Produces: `MIN_INTERVAL_S = 10.0`, `DEFAULT_INTERVAL_S = 600.0`,
  `AutoCommitScheduler(committer, interval_s=DEFAULT_INTERVAL_S,
  log=print, clock=time.time)` mit `start()`, `stop()`, `run_once()`,
  `status() -> dict`.

- [ ] **Step 1: Write the failing tests**

```python
class SchedulerTest(unittest.TestCase):
    class Boom:
        def check_and_commit(self):
            raise RuntimeError("kaputt")

    class Counting:
        def __init__(self):
            self.runs = 0

        def check_and_commit(self):
            self.runs += 1
            return autocommit.AutoCommitResult("clean", "nichts", [], 1.0)

    def test_exception_does_not_escape(self):
        lines = []
        sched = autocommit.AutoCommitScheduler(self.Boom(), interval_s=10,
                                               log=lines.append)
        sched.run_once()  # darf nicht werfen
        self.assertEqual(sched.status()["lastStatus"], "error")
        self.assertTrue(any("kaputt" in line for line in lines))

    def test_status_before_the_first_round(self):
        sched = autocommit.AutoCommitScheduler(self.Counting(), interval_s=10,
                                               log=lambda _: None)
        state = sched.status()
        self.assertTrue(state["enabled"])
        self.assertEqual(state["intervalSeconds"], 10)
        self.assertIsNone(state["lastRunAt"])
        self.assertIsNone(state["lastCommitAt"])

    def test_status_after_a_commit(self):
        class Committing:
            def check_and_commit(self):
                return autocommit.AutoCommitResult(
                    "committed", "1 Datei(en) gesichert",
                    ["data/presets/a.txt"], 1234.0)

        sched = autocommit.AutoCommitScheduler(Committing(), interval_s=10,
                                               log=lambda _: None)
        sched.run_once()
        state = sched.status()
        self.assertEqual(state["lastStatus"], "committed")
        self.assertEqual(state["lastCommitAt"], 1234.0)
        self.assertEqual(state["lastPaths"], ["data/presets/a.txt"])

    def test_clean_round_does_not_move_the_commit_time(self):
        sched = autocommit.AutoCommitScheduler(self.Counting(), interval_s=10,
                                               log=lambda _: None)
        sched.run_once()
        self.assertIsNone(sched.status()["lastCommitAt"])
        self.assertIsNotNone(sched.status()["lastRunAt"])

    def test_interval_has_a_floor(self):
        sched = autocommit.AutoCommitScheduler(self.Counting(), interval_s=0.1,
                                               log=lambda _: None)
        self.assertEqual(sched.status()["intervalSeconds"],
                         autocommit.MIN_INTERVAL_S)

    def test_start_runs_and_stop_returns_quickly(self):
        counting = self.Counting()
        sched = autocommit.AutoCommitScheduler(counting, interval_s=3600,
                                               log=lambda _: None)
        sched.start()
        for _ in range(200):  # bis zu 2 s auf die erste Runde warten
            if counting.runs:
                break
            time.sleep(0.01)
        sched.stop(timeout=2.0)
        self.assertGreaterEqual(counting.runs, 1)
        self.assertFalse(sched.is_alive())
```

Dafür oben in der Testdatei `import time` ergänzen.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `python3 webui/test_autocommit.py`
Expected: `AttributeError: module 'autocommit' has no attribute 'AutoCommitScheduler'`

- [ ] **Step 3: Scheduler implementieren**

`import threading` oben ergänzen.

```python
DEFAULT_INTERVAL_S = 600.0
# Untergrenze gegen einen Tippfehler in der Konfiguration: ein Intervall von
# 0 wuerde git in einer Dauerschleife aufrufen.
MIN_INTERVAL_S = 10.0


class AutoCommitScheduler:
    """Ruft den Committer in festem Takt in einem Hintergrund-Thread.

    Der Thread ist ein Daemon und faengt JEDE Ausnahme: der Webserver darf an
    dieser Sicherung nie sterben. Ein Fehler wird geloggt und in der
    Statusanzeige sichtbar, die naechste Runde laeuft trotzdem.

    Gewartet wird ueber ein Event statt time.sleep, damit stop() sofort greift
    und nicht bis zu zehn Minuten haengt.
    """

    def __init__(self, committer, interval_s=DEFAULT_INTERVAL_S, log=print,
                 clock=time.time):
        self.committer = committer
        self.interval_s = max(float(interval_s), MIN_INTERVAL_S)
        self._log = log
        self._clock = clock
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None
        self._last: Optional[AutoCommitResult] = None
        self._last_run_at: Optional[float] = None
        self._last_commit_at: Optional[float] = None

    # -- Steuerung ----------------------------------------------------------

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._loop,
                                        name="autocommit", daemon=True)
        self._thread.start()

    def stop(self, timeout: Optional[float] = None) -> None:
        self._stop.set()
        thread, self._thread = self._thread, None
        if thread is not None:
            thread.join(timeout)

    def is_alive(self) -> bool:
        thread = self._thread
        return bool(thread and thread.is_alive())

    # -- Ablauf -------------------------------------------------------------

    def _loop(self) -> None:
        # Erst warten, dann arbeiten: beim Hochfahren des Servers ist der
        # Arbeitsbaum gerade der, den der Operator selbst hinterlassen hat --
        # ein Commit in der ersten Sekunde ueberrascht.
        while not self._stop.wait(self.interval_s):
            self.run_once()

    def run_once(self) -> AutoCommitResult:
        try:
            result = self.committer.check_and_commit()
        except Exception as exc:  # der Committer faengt selbst, dies ist das Netz
            result = AutoCommitResult("error", "unerwartet: %r" % (exc,), [],
                                      self._clock())
        with self._lock:
            self._last = result
            self._last_run_at = result.at
            if result.status == "committed":
                self._last_commit_at = result.at
        if result.status == "committed":
            self._log("[autocommit] %s: %s"
                      % (result.detail, ", ".join(result.paths)))
        elif result.status in ("skipped", "error"):
            self._log("[autocommit] %s -- %s" % (result.status, result.detail))
        return result

    def status(self) -> dict:
        with self._lock:
            last = self._last
            return {
                "enabled": True,
                "intervalSeconds": self.interval_s,
                "lastRunAt": self._last_run_at,
                "lastCommitAt": self._last_commit_at,
                "lastStatus": last.status if last else None,
                "lastDetail": last.detail if last else "",
                "lastPaths": list(last.paths) if last else [],
            }


DISABLED_STATUS = {
    "enabled": False,
    "intervalSeconds": None,
    "lastRunAt": None,
    "lastCommitAt": None,
    "lastStatus": None,
    "lastDetail": "",
    "lastPaths": [],
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `python3 webui/test_autocommit.py`
Expected: alle Tests `ok`

- [ ] **Step 5: Commit**

```bash
git add webui/autocommit.py webui/test_autocommit.py
git commit -m "Auto-Commit: Scheduler-Thread mit Statusanzeige"
```

---

### Task 4: Sicherheitstests — Grep und echtes Git

**Files:**
- Test: `webui/test_autocommit.py`

**Interfaces:** keine neuen.

- [ ] **Step 1: Write the tests**

```python
FORBIDDEN_STAGING = (
    r"add\s+-A\b",
    r"add\s+--all\b",
    r'add["\']?\s*,\s*["\']\.["\']',
    r"add\s+\.\s*$",
    r"commit\s+-a\b",
    r"\bpush\b",
)


class NoBlanketStagingTest(unittest.TestCase):
    """Harte Sicherheitsanforderung, kein Stilwunsch.

    Ein pauschales Staging wuerde fremde, halbfertige Handarbeit in einen
    automatischen Commit ziehen; ein Push wuerde eine Fernwirkung ohne
    Entscheidung ausloesen. Beides darf im Web-UI-Code nicht vorkommen.
    """

    def test_no_blanket_add_and_no_push(self):
        import re
        here = os.path.dirname(os.path.abspath(__file__))
        for name in sorted(os.listdir(here)):
            if not name.endswith(".py"):
                continue
            if name == os.path.basename(__file__):
                continue  # die Muster selbst stehen hier drin
            with open(os.path.join(here, name), encoding="utf-8") as handle:
                text = handle.read()
            for pattern in FORBIDDEN_STAGING:
                self.assertIsNone(
                    re.search(pattern, text, re.MULTILINE),
                    "%s enthaelt ein verbotenes Git-Muster: %s"
                    % (name, pattern))


def git_available():
    import shutil
    return shutil.which("git") is not None


@unittest.skipUnless(git_available(), "git nicht verfuegbar")
class RealRepositoryTest(unittest.TestCase):
    """Der eigentliche Beweis: nur die ueberwachten Pfade landen im Commit."""

    def setUp(self):
        import subprocess
        import tempfile
        self.tmp = tempfile.mkdtemp(prefix="autocommit-test-")
        self.addCleanup(__import__("shutil").rmtree, self.tmp,
                        ignore_errors=True)

        def git(*args):
            done = subprocess.run(["git"] + list(args), cwd=self.tmp,
                                  stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT)
            self.assertEqual(done.returncode, 0, done.stdout.decode())
            return done.stdout.decode()

        self.git = git
        git("init", "-q", "-b", "master")
        git("config", "user.email", "test@example.invalid")
        git("config", "user.name", "Test")
        git("config", "commit.gpgsign", "false")
        os.makedirs(os.path.join(self.tmp, "data", "presets"))
        self.write("data/presets/a.txt", "erste Fassung\n")
        self.write("imPulse.pde", "// Sketch\n")
        git("add", "data/presets/a.txt", "imPulse.pde")
        git("commit", "-q", "-m", "Ausgangsstand")

    def write(self, relative, text):
        path = os.path.join(self.tmp, relative.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)

    def head_files(self):
        return self.git("show", "--name-only", "--pretty=format:", "HEAD").split()

    def test_only_watched_paths_are_committed(self):
        self.write("data/presets/a.txt", "live geaendert\n")
        self.write("imPulse.pde", "// von Hand angefasst\n")
        auto = autocommit.AutoCommitter(self.tmp)
        result = auto.check_and_commit()
        self.assertEqual(result.status, "committed", result.detail)
        self.assertEqual(self.head_files(), ["data/presets/a.txt"])
        # Die fremde Aenderung ist noch da und noch schmutzig:
        dirty = self.git("status", "--porcelain")
        self.assertIn("imPulse.pde", dirty)

    def test_new_preset_is_picked_up(self):
        self.write("data/presets/neu.txt", "frisch\n")
        result = autocommit.AutoCommitter(self.tmp).check_and_commit()
        self.assertEqual(result.status, "committed", result.detail)
        self.assertIn("data/presets/neu.txt", self.head_files())

    def test_second_round_without_changes_commits_nothing(self):
        self.write("data/presets/a.txt", "live geaendert\n")
        auto = autocommit.AutoCommitter(self.tmp)
        self.assertEqual(auto.check_and_commit().status, "committed")
        before = self.git("rev-list", "--count", "HEAD").strip()
        self.assertEqual(auto.check_and_commit().status, "clean")
        self.assertEqual(self.git("rev-list", "--count", "HEAD").strip(), before)

    def test_staged_foreign_change_stays_out_of_the_commit(self):
        self.write("data/presets/a.txt", "live geaendert\n")
        self.write("imPulse.pde", "// bewusst gestaged\n")
        self.git("add", "imPulse.pde")
        result = autocommit.AutoCommitter(self.tmp).check_and_commit()
        self.assertEqual(result.status, "committed", result.detail)
        self.assertEqual(self.head_files(), ["data/presets/a.txt"])
        self.assertIn("imPulse.pde", self.git("status", "--porcelain"))
```

- [ ] **Step 2: Run the tests**

Run: `python3 webui/test_autocommit.py`
Expected: alle Tests `ok` (falls `git` fehlt: `RealRepositoryTest` übersprungen)

- [ ] **Step 3: Commit**

```bash
git add webui/test_autocommit.py
git commit -m "Auto-Commit: Sicherheitstests gegen pauschales Staging und Push"
```

---

### Task 5: Einbau in `webui/server.py`

**Files:**
- Modify: `webui/server.py` (Import-Block ~Z. 16-35, `create_app`, `main` ab Z. 1407)

**Interfaces:**
- Consumes: `autocommit.AutoCommitter`, `autocommit.AutoCommitScheduler`,
  `autocommit.DEFAULT_INTERVAL_S`, `autocommit.DISABLED_STATUS`.
- Produces: `create_app(..., autocommit_scheduler=None)`;
  Route `GET /api/autocommit`; Bootstrap-Schlüssel `"autocommit"`.

- [ ] **Step 1: Modul importieren**

In `webui/server.py` bei den lokalen Importen (nach dem Flask-Block):

```python
import autocommit  # noqa: E402  (liegt neben dieser Datei)
```

Falls `server.py` als Modul importiert wird, ohne dass `webui/` im Pfad steht,
greift dieselbe Konvention wie in `test_webui.py` (`sys.path.insert`); im
Serverbetrieb liegt das Verzeichnis der Datei ohnehin im Pfad.

- [ ] **Step 2: `create_app` um den Parameter erweitern**

Signatur ergänzen um `autocommit_scheduler=None` und in den Bootstrap sowie
eine neue Route einhängen:

```python
    def autocommit_payload() -> Dict[str, Any]:
        if autocommit_scheduler is None:
            return dict(autocommit.DISABLED_STATUS)
        return autocommit_scheduler.status()

    @app.route("/api/autocommit")
    def api_autocommit():
        """Zustand der automatischen Sicherung.

        Nur lesend -- ausgeloest wird ausschliesslich vom Takt, es gibt
        bewusst keinen Knopf, der aus dem Browser heraus Git-Zustand aendert.
        """
        return jsonify(autocommit_payload())
```

Im `index()`-Bootstrap-Dict zusätzlich `"autocommit": autocommit_payload(),`.

- [ ] **Step 3: `main()` um Flags und Start erweitern**

```python
    parser.add_argument("--no-autocommit", action="store_true",
                        default=os.environ.get("IMPULSE_AUTOCOMMIT", "1") == "0",
                        help="die automatische lokale Sicherung der "
                             "Daten-Dateien abschalten")
    parser.add_argument("--autocommit-interval", type=float,
                        default=float(os.environ.get(
                            "IMPULSE_AUTOCOMMIT_INTERVAL",
                            autocommit.DEFAULT_INTERVAL_S)),
                        help="Sekunden zwischen zwei Sicherungslaeufen "
                             "(Vorgabe: %(default)s)")
```

Nach dem Berechnen von `presets_path`:

```python
    scheduler = None
    if not args.no_autocommit:
        scheduler = autocommit.AutoCommitScheduler(
            autocommit.AutoCommitter(REPO_ROOT),
            interval_s=args.autocommit_interval)
        scheduler.start()

    app = create_app(settings_path, args.osc_host, args.osc_port, presets_path,
                     autocommit_scheduler=scheduler)
```

und in den Startmeldungen:

```python
    if scheduler is None:
        print("[webui] Auto-Commit:    aus")
    else:
        print("[webui] Auto-Commit:    alle %.0f s, nur lokal (kein Push)"
              % scheduler.interval_s)
```

- [ ] **Step 4: Prüfen**

Run: `python3 -c "import sys; sys.path.insert(0,'webui'); import server; print(server.main.__doc__ is None)"`
Expected: `True` (der Import läuft durch, Flask fehlt evtl. — dann meldet das
`try/except` im Modul das wie bisher)

Run: `python3 webui/test_webui.py 2>&1 | tail -3`
Expected: `OK`

Run: `python3 webui/server.py --help | grep autocommit`
Expected: beide neuen Optionen erscheinen (nur wenn Flask installiert ist;
sonst überspringen).

- [ ] **Step 5: Commit**

```bash
git add webui/server.py
git commit -m "Web-UI-Server startet den Auto-Commit und meldet seinen Zustand"
```

---

### Task 6: Statusanzeige im Web-UI

**Files:**
- Modify: `webui/templates/index.html` (nach Z. 24, der `status`-Zeile)
- Modify: `webui/static/app.js`
- Modify: `webui/static/style.css`

**Interfaces:**
- Consumes: Bootstrap-Schlüssel `autocommit`, Route `GET /api/autocommit`.

- [ ] **Step 1: HTML**

Nach der bestehenden `<p class="status" id="status">`-Zeile:

```html
  <!-- Zustand der automatischen lokalen Sicherung. Bewusst nur Anzeige:
       ausgeloest wird ausschliesslich vom Takt im Server. -->
  <p class="autocommit" id="autocommit" role="status">&nbsp;</p>
```

- [ ] **Step 2: JS**

In `app.js` ergänzen (und beim Aufbau einmal aufrufen):

```javascript
// --- Automatische Sicherung -------------------------------------------
// Nur Anzeige. Die relative Zeit rechnet der Browser, weil der Server die
// Uhr des Betrachters nicht kennt.

const AUTOCOMMIT_POLL_MS = 60000;
const autocommitLine = document.getElementById('autocommit');

function relativeTime(seconds) {
  if (seconds === null || seconds === undefined) return null;
  const delta = Date.now() / 1000 - seconds;
  if (delta < 90) return 'gerade eben';
  const minutes = Math.round(delta / 60);
  if (minutes < 90) return 'vor ' + minutes + ' Minuten';
  const hours = Math.round(minutes / 60);
  if (hours < 36) return 'vor ' + hours + ' Stunden';
  return 'vor ' + Math.round(hours / 24) + ' Tagen';
}

function renderAutocommit(state) {
  if (!autocommitLine) return;
  if (!state || !state.enabled) {
    autocommitLine.textContent = 'Automatische Sicherung: aus — '
      + 'Aenderungen an Presets und Paletten liegen nur auf diesem Rechner.';
    autocommitLine.dataset.level = 'off';
    return;
  }
  const minutes = Math.round((state.intervalSeconds || 0) / 60);
  const parts = ['Automatische Sicherung: alle ' + minutes + ' min'];
  const last = relativeTime(state.lastCommitAt);
  parts.push(last ? 'zuletzt gesichert ' + last
                  : 'noch nichts zu sichern gewesen');
  if (state.lastStatus === 'error') {
    parts.push('FEHLER: ' + (state.lastDetail || 'unbekannt'));
    autocommitLine.dataset.level = 'error';
  } else if (state.lastStatus === 'skipped') {
    parts.push('uebersprungen: ' + (state.lastDetail || ''));
    autocommitLine.dataset.level = 'warn';
  } else {
    autocommitLine.dataset.level = 'ok';
  }
  autocommitLine.textContent = parts.join(' — ');
  autocommitLine.title = 'Lokaler Git-Commit auf diesem Rechner, kein Push. '
    + 'Zum Uebertragen weiterhin von Hand: git push';
}

async function pollAutocommit() {
  try {
    const response = await fetch('/api/autocommit');
    if (response.ok) renderAutocommit(await response.json());
  } catch (error) {
    /* Anzeige ist Beiwerk; ein Netzfehler darf das UI nicht stoeren. */
  }
}

renderAutocommit(bootstrap.autocommit);
setInterval(pollAutocommit, AUTOCOMMIT_POLL_MS);
```

Der Name des Bootstrap-Objekts ist der, den `app.js` bereits verwendet — vor
dem Einfügen in `app.js` nachsehen und übernehmen.

- [ ] **Step 3: CSS**

```css
/* Automatische Sicherung: eine ruhige Zeile, die nur bei Fehlern auffaellt. */
.autocommit {
  margin: 0 0 12px;
  font-size: 13px;
  opacity: 0.75;
}
.autocommit[data-level="warn"] { opacity: 1; color: #d9a441; }
.autocommit[data-level="error"] { opacity: 1; color: #e06c60; }
.autocommit[data-level="off"] { opacity: 0.6; }
```

(Farbwerte an die vorhandene Palette in `style.css` angleichen.)

- [ ] **Step 4: Prüfen**

Run: `node --check webui/static/app.js`
Expected: keine Ausgabe (falls `node` fehlt: überspringen und stattdessen
`python3 -c "print(open('webui/static/app.js').read().count('renderAutocommit'))"`,
erwartet ≥ 2)

- [ ] **Step 5: Commit**

```bash
git add webui/templates/index.html webui/static/app.js webui/static/style.css
git commit -m "Web-UI: Statuszeile fuer die automatische Sicherung"
```

---

### Task 7: Dokumentation

**Files:**
- Modify: `webui/README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: `webui/README.md`**

Abschnitt „Automatische Sicherung der Live-Daten" ergänzen: was überwacht wird
(die sieben Muster), der Takt, dass **nicht gepusht** wird und wie man es
abschaltet (`--no-autocommit`, `IMPULSE_AUTOCOMMIT=0`,
`--autocommit-interval`). Ausdrücklich: `data/remoteSettings.txt` und
`data/songStructureState.txt` sind bewusst nicht dabei.

- [ ] **Step 2: `CLAUDE.md`**

Unter „Web-UI (webui/)" einen Absatz „Automatische Sicherung der Live-Daten"
mit derselben Aussage plus den drei Dingen, die man beim Ändern kennen muss:
Pathspec auch am `commit`, kein pauschales Staging (Test hält das nach),
Übersprungen bei detached HEAD / offenem Merge. Und der Hinweis auf die zweite
Testsuite `python3 webui/test_autocommit.py`.

- [ ] **Step 3: Beide Suiten laufen lassen**

```bash
python3 webui/test_webui.py 2>&1 | tail -3
python3 webui/test_autocommit.py 2>&1 | tail -3
```
Expected: beide `OK`

- [ ] **Step 4: Commit**

```bash
git add webui/README.md CLAUDE.md
git commit -m "Doku: automatische Sicherung der Live-Daten"
```

---

## Self-Review

- Spec-Abdeckung: Watchlist (Task 1), Änderungserkennung + Commit (Task 2),
  10-Minuten-Takt + Absturzschutz (Task 3), Sicherheitsanforderungen
  (Task 4), Abschaltbarkeit + Serverstart (Task 5), Sichtbarkeit im UI
  (Task 6), Doku inkl. Merge-Hinweis zur Farbpalette (Task 7). Keine Lücke.
- Keine Platzhalter: jeder Code-Schritt zeigt den Code.
- Namen durchgehend gleich: `check_and_commit`, `AutoCommitResult`,
  `AutoCommitScheduler.status()`, Bootstrap-Schlüssel `autocommit`,
  Route `/api/autocommit`.
