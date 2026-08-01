#!/usr/bin/env python3
"""Periodischer Auto-Commit der live editierten Daten-Dateien.

Presets, Farbpaletten, Energie-Level und die Kalibrierdateien werden im
laufenden Betrieb geaendert -- vom Web-UI, vom Sketch, von Hand. Es sind
git-getrackte Dateien, aber niemand committet sie; nachgezogen wurde das
bisher per Hand (Git-Historie: "X-Preset vom Live-Betrieb nachgezogen").
Wird ein Rechner neu aufgesetzt, bevor jemand daran denkt, ist die
Live-Arbeit weg. Dieses Modul sichert sie in festem Takt LOKAL.

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
import subprocess
import threading
import time
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
#
# Ein Muster, das in diesem Checkout auf nichts passt, ist kein Fehler: die
# Farbpaletten und die Energie-Level kommen erst mit ihren Feature-Branches.
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

    Bei einer Umbenennung (``R``) oder Kopie (``C``) schreibt Git zwei Saetze:
    erst den neuen Pfad, dann den alten. Nur der neue interessiert -- der alte
    wird durch die Pathspec des naechsten `git add` ohnehin mit erfasst. Der
    Herkunftssatz muss aber uebersprungen werden, sonst zaehlte er als eigener
    Eintrag.
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
