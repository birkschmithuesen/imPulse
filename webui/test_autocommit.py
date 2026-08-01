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
import time
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

    def test_rename_does_not_swallow_the_next_entry(self):
        entries = parse_porcelain(z("R  data/presets/neu.txt",
                                    "data/presets/alt.txt",
                                    " M data/colorPalettes.txt"))
        self.assertEqual([e.path for e in entries],
                         ["data/presets/neu.txt", "data/colorPalettes.txt"])

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

    def test_sketch_sources_do_not_match(self):
        self.assertFalse(matches_watchlist("imPulse.pde"))
        self.assertFalse(matches_watchlist("webui/server.py"))

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
