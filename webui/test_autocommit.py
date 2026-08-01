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
        self.assertFalse(any(call[0] == "commit" for call in fake.calls))

    def test_failing_commit_reports_error_without_raising(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, z(" M data/presets/a.txt"), ""),
            ("commit",): autocommit.GitResult(1, "", "kein Autor gesetzt"),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "error")
        self.assertIn("kein Autor gesetzt", result.detail)

    def test_failing_add_reports_error_and_does_not_commit(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(0, z(" M data/presets/a.txt"), ""),
            ("add",): autocommit.GitResult(128, "", "Pfad ist ignoriert"),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.status, "error")
        self.assertFalse(any(call[0] == "commit" for call in fake.calls))

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

    def test_paths_are_deduplicated_and_sorted(self):
        auto, fake = committer({
            ON_BRANCH: autocommit.GitResult(0, "refs/heads/master\n", ""),
            STATUS: autocommit.GitResult(
                0, z(" M data/stripeTrees.txt", "?? data/presets/a.txt"), ""),
        })
        result = auto.check_and_commit()
        self.assertEqual(result.paths,
                         ["data/presets/a.txt", "data/stripeTrees.txt"])


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

    def test_disabled_status_has_the_same_keys(self):
        sched = autocommit.AutoCommitScheduler(self.Counting(), interval_s=10,
                                               log=lambda _: None)
        self.assertEqual(set(autocommit.DISABLED_STATUS),
                         set(sched.status()))
        self.assertFalse(autocommit.DISABLED_STATUS["enabled"])

    def test_first_round_does_not_run_immediately(self):
        """Beim Hochfahren ist der Arbeitsbaum der, den der Operator gerade
        hinterlassen hat -- ein Commit in der ersten Sekunde ueberrascht."""
        counting = self.Counting()
        sched = autocommit.AutoCommitScheduler(counting, interval_s=600,
                                               log=lambda _: None)
        sched.start()
        self.addCleanup(sched.stop, 2.0)
        time.sleep(0.2)
        self.assertEqual(counting.runs, 0)
        self.assertTrue(sched.is_alive())

    def test_stop_returns_long_before_the_interval(self):
        """Gewartet wird ueber ein Event, nicht ueber sleep -- sonst haenge
        das Beenden des Servers bis zu zehn Minuten."""
        sched = autocommit.AutoCommitScheduler(self.Counting(),
                                               interval_s=600,
                                               log=lambda _: None)
        sched.start()
        started = time.time()
        sched.stop(timeout=5.0)
        self.assertLess(time.time() - started, 2.0)
        self.assertFalse(sched.is_alive())

    def test_start_is_idempotent(self):
        sched = autocommit.AutoCommitScheduler(self.Counting(), interval_s=600,
                                               log=lambda _: None)
        sched.start()
        self.addCleanup(sched.stop, 2.0)
        first = sched._thread
        sched.start()
        self.assertIs(sched._thread, first)


if __name__ == "__main__":
    unittest.main(verbosity=2)
