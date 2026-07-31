"""The read-only guard on production, and the root that cannot be a long-lived temp directory."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import store
from parity.norm import MissingInput, Refused


class ArtifactPaths(unittest.TestCase):

    def test_kind_prefix_maps_to_a_directory(self):
        self.assertEqual(store.path_of("sweep.entity"), "sweeps/entity.json")
        self.assertEqual(store.path_of("manifest.references"), "manifests/references.json")
        self.assertEqual(store.path_of("digest.shipped-tables"), "digests/shipped-tables.json")
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
    """U6: a long-lived temp root is unrepresentable rather than discouraged."""

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

    def test_there_is_no_slot_api(self):
        """R-g: the root is a path. A two-constant enum is still two slots."""
        self.assertFalse(hasattr(store, "Slot"))
        self.assertFalse(hasattr(store, "ParitySlot"))


class ProductionIsReadOnly(unittest.TestCase):
    """I-7: only parityPromote writes a baseline."""

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


if __name__ == "__main__":
    unittest.main()
