"""The read-only guard on production, and the root that cannot be a long-lived temp directory."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import store
from parity.norm import MissingInput, Refused, write_json


class ArtifactPaths(unittest.TestCase):

    def test_kind_prefix_maps_to_a_directory(self):
        self.assertEqual(store.path_of("sweep.entity"), "sweeps/entity.json")
        self.assertEqual(store.path_of("manifest.references"), "manifests/references.json")
        self.assertEqual(store.path_of("pin.player-crc"), "pins/player-crc.json")

    def test_a_qualifier_becomes_a_hyphen(self):
        """An artifact id is also a file stem, so it stays filesystem-safe on Windows."""
        self.assertEqual(store.path_of("manifest.dump.vanilla"), "manifests/dump-vanilla.json")
        self.assertEqual(store.path_of("manifest.dump.packs"), "manifests/dump-packs.json")

    def test_the_two_root_files(self):
        self.assertEqual(store.path_of("report.oracle-index"), "index.json")
        self.assertEqual(store.path_of("roster.blindness-rules"), "blindness.json")

    def test_an_unknown_kind_is_missing_input_not_a_guess(self):
        with self.assertRaises(MissingInput):
            store.path_of("probe.pixel")


class WorkingRoot(unittest.TestCase):
    """A long-lived temp root is unrepresentable rather than discouraged."""

    def setUp(self):
        self.base = Path(tempfile.mkdtemp())

    def test_default_is_the_one_literal(self):
        self.assertEqual(store.resolve_working(None, self.base), self.base / "cache/parity/current")

    def test_relative_under_cache_is_accepted(self):
        resolved = store.resolve_working("cache/parity/base", self.base)
        self.assertEqual(resolved, self.base / "cache/parity/base")

    def test_absolute_is_refused(self):
        with self.assertRaises(Refused):
            store.resolve_working(str(self.base / "cache" / "parity"), self.base)

    def test_outside_cache_is_refused(self):
        for candidate in ("notes/parity", "build/parity", "../cache/parity"):
            with self.assertRaises(Refused):
                store.resolve_working(candidate, self.base)

    def test_a_root_spelled_under_cache_that_leaves_it_is_refused(self):
        """A capture opens by erasing its root, so the root is held to where it lands."""
        for candidate in ("cache/..", "cache/../..", "cache/a/../../x", "cache/../cache/parity",
                          "cache/parity/../base"):
            with self.subTest(candidate), self.assertRaises(Refused):
                store.resolve_working(candidate, self.base)

    def test_cache_itself_is_refused(self):
        """Its children are every cache the repository keeps, not one capture."""
        (self.base / "cache").mkdir()
        for candidate in ("cache", "cache/", "cache/."):
            with self.subTest(candidate), self.assertRaises(Refused):
                store.resolve_working(candidate, self.base)

    def test_a_root_that_does_not_exist_yet_is_accepted(self):
        """The first capture of a checkout makes its own root, and so does a redirected one."""
        self.assertFalse((self.base / "cache").exists())
        self.assertEqual(store.resolve_working("cache/parity/a/b", self.base),
                         self.base / "cache/parity/a/b")

    def test_a_repository_reached_through_a_link_still_resolves(self):
        """Both sides are resolved, so a checkout under a linked directory is not refused for it."""
        real = self.base / "real"
        real.mkdir()
        linked = self._link(self.base / "linked", real)
        self.assertEqual(store.resolve_working("cache/parity/base", linked),
                         linked / "cache/parity/base")
        self.assertEqual(store.resolve_working(None, linked), linked / "cache/parity/current")

    def test_a_linked_cache_is_refused(self):
        """The erase may reach the checkout's own cache directory and nowhere a link says that
        name leads - the default root included, since the link moves it too."""
        self._link(self.base / "cache", Path(tempfile.mkdtemp()))
        for candidate in ("cache/parity/base", None):
            with self.subTest(candidate), self.assertRaises(Refused):
                store.resolve_working(candidate, self.base)

    def test_a_cache_linked_back_onto_the_repository_is_refused(self):
        self._link(self.base / "cache", self.base)
        with self.assertRaises(Refused):
            store.resolve_working("cache/src", self.base)

    def test_a_link_under_cache_that_leaves_it_is_refused(self):
        (self.base / "cache").mkdir()
        self._link(self.base / "cache" / "out", Path(tempfile.mkdtemp()))
        for candidate in ("cache/out", "cache/out/parity"):
            with self.subTest(candidate), self.assertRaises(Refused):
                store.resolve_working(candidate, self.base)

    def test_a_link_under_cache_that_stays_inside_it_is_accepted(self):
        (self.base / "cache" / "parity").mkdir(parents=True)
        self._link(self.base / "cache" / "alias", self.base / "cache" / "parity")
        self.assertEqual(store.resolve_working("cache/alias/base", self.base),
                         self.base / "cache/alias/base")

    def _link(self, link: Path, target: Path) -> Path:
        """Make a directory link, or skip the case where the platform will not make one."""
        try:
            link.symlink_to(target, target_is_directory=True)
        except (OSError, NotImplementedError) as failure:
            self.skipTest(f"cannot make a directory link: {failure}")
        return link


class ProductionIsReadOnly(unittest.TestCase):
    """Only parityPromote writes a baseline."""

    def setUp(self):
        self.base = Path(tempfile.mkdtemp())

    def test_write_raises(self):
        view = store.production(None, self.base)
        with self.assertRaises(Refused):
            view.write("sweep.entity", {"rows": []})

    def test_writable_view_is_a_separate_type(self):
        self.assertIsInstance(store.working(None, self.base), store.WritableStore)
        self.assertNotIsInstance(store.production(None, self.base), store.WritableStore)

    def test_reading_an_absent_artifact_is_missing_input(self):
        with self.assertRaises(MissingInput):
            store.production(None, self.base).read("sweep.entity")

    def test_index_of_an_empty_store_is_an_envelope_not_a_failure(self):
        index = store.production(None, self.base).index()
        self.assertEqual(index["artifacts"], {})
        self.assertEqual(index["kind"], "index")

    def test_contains_detects_a_path_inside_production(self):
        view = store.production(None, self.base)
        self.assertTrue(view.contains(view.root / "sweeps" / "entity.json"))
        self.assertFalse(view.contains(self.base / "cache" / "x.json"))


class TheReservedDirectoryIsNotACapture(unittest.TestCase):
    """What a bare ``promote-plan`` enumerates, which is a capture and not a run's reports."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        write_json(self.root / "sweeps" / "entity.json",
                   {"artifact": "sweep.entity", "kind": "sweep-table", "rows": [],
                    "provenance": {"asset_sha": "0" * 40}})
        # Three of the run reports name an artifact of their own, so a filter on "does this envelope
        # look like an artifact" would admit them. The one that matters is the plan: it carries no
        # provenance, so admitting it makes a bare promote-plan see an uncaptured row and refuse an
        # invocation that is entirely well formed.
        write_json(self.root / store.RUN_DIR / "plan.json",
                   {"artifact": "report.plan", "kind": "plan", "sees": []})
        write_json(self.root / store.RUN_DIR / "compare.json",
                   {"artifact": "report.movers", "kind": "compare", "results": []})

    def test_only_the_capture_is_enumerated(self):
        self.assertEqual(store.artifact_files(self.root), [("sweep.entity", True)])


if __name__ == "__main__":
    unittest.main()
