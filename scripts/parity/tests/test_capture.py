"""The wipe, its one exemption, and COMPLETE written last."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import capture, store
from parity.norm import MissingInput, Refused, read_json, write_json, write_text

DATA = Path(__file__).resolve().parent / "data"
REPO = Path(__file__).resolve().parents[3]


class Wipe(unittest.TestCase):
    """U6 made mechanical: there is no accumulation and nothing to rename."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp()) / "run"
        write_json(self.root / "sweeps" / "entity.json", {"artifact": "sweep.entity"})
        write_json(self.root / "manifests" / "fluid.json", {"artifact": "manifest.fluid"})
        write_text(self.root / store.RUN_DIR / "COMPLETE", "")
        write_json(self.root / store.RUN_DIR / "compare.json", {"stale": True})

    def test_it_erases_the_artifact_tree(self):
        capture.wipe(self.root)
        self.assertFalse((self.root / "sweeps").exists())
        self.assertFalse((self.root / "manifests").exists())

    def test_it_erases_COMPLETE_so_a_half_written_root_is_detectable(self):
        capture.wipe(self.root)
        self.assertFalse((self.root / store.RUN_DIR / "COMPLETE").exists())

    def test_the_expected_diff_manifest_survives(self):
        """The gate order is expect -> capture -> compare, so it is written BEFORE the capture."""
        write_json(self.root / store.RUN_DIR / capture.EXEMPT, {"movers": [{"key": "x"}]})
        capture.wipe(self.root)
        survivor = self.root / store.RUN_DIR / capture.EXEMPT
        self.assertTrue(survivor.is_file())
        self.assertEqual(len(read_json(survivor)["movers"]), 1)

    def test_nothing_else_under_run_survives(self):
        capture.wipe(self.root)
        self.assertFalse((self.root / store.RUN_DIR / "compare.json").exists())

    def test_a_missing_root_is_created_rather_than_failing(self):
        fresh = Path(tempfile.mkdtemp()) / "new"
        capture.wipe(fresh)
        self.assertTrue(fresh.is_dir())


class OncePerInvocation(unittest.TestCase):
    """The erase happens once per capture invocation, however many rows that invocation names.

    The build registers one capture step per artifact so each can finalize its own producer, which
    makes a step one process per row. Erasing unconditionally in every step left only the row that
    ran last, and nothing said so: both steps reported success, and `compare` and `promote`
    enumerate what the root holds rather than what was asked for.
    """

    def setUp(self):
        self.root = Path(tempfile.mkdtemp()) / "run"

    def _artifact(self, name):
        write_json(self.root / "manifests" / f"{name}.json", {"artifact": f"manifest.{name}"})

    def test_a_second_step_of_one_invocation_does_not_erase_the_first(self):
        """The measured failure, in one test."""
        capture.begin(self.root)
        self._artifact("fluid")
        self.assertFalse(capture.join_or_begin(self.root))
        self._artifact("portal")
        self.assertTrue((self.root / "manifests" / "fluid.json").is_file())
        self.assertTrue((self.root / "manifests" / "portal.json").is_file())

    def test_a_step_on_a_closed_root_erases_so_single_slot_survives(self):
        """A hand-run producer is one step and no begin, and it must still leave exactly one."""
        capture.begin(self.root)
        self._artifact("fluid")
        capture.index(self.root)
        self.assertTrue(capture.join_or_begin(self.root))
        self.assertFalse((self.root / "manifests" / "fluid.json").exists())

    def test_begin_erases_even_when_a_capture_is_already_open(self):
        """A crashed invocation leaves OPEN behind; the next one must not build on it."""
        capture.begin(self.root)
        self._artifact("fluid")
        capture.begin(self.root)
        self.assertFalse((self.root / "manifests" / "fluid.json").exists())

    def test_index_closes_the_capture(self):
        capture.begin(self.root)
        capture.index(self.root)
        self.assertFalse((self.root / store.RUN_DIR / capture.OPEN).exists())
        self.assertTrue((self.root / store.RUN_DIR / capture.COMPLETE).is_file())

    def test_the_expected_diff_manifest_survives_begin(self):
        write_json(self.root / store.RUN_DIR / capture.EXEMPT, {"movers": [{"key": "x"}]})
        capture.begin(self.root)
        survivor = self.root / store.RUN_DIR / capture.EXEMPT
        self.assertTrue(survivor.is_file())
        self.assertEqual(len(read_json(survivor)["movers"]), 1)

    def test_the_open_marker_is_not_indexed_as_an_artifact(self):
        capture.begin(self.root)
        self._artifact("fluid")
        capture.index(self.root)
        recorded = read_json(self.root / store.RUN_DIR / "_capture.json")
        self.assertEqual([entry["path"] for entry in recorded["files"]], ["manifests/fluid.json"])


class Normalize(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp()) / "run"

    def test_a_tsv_goes_in_and_canonical_json_comes_out(self):
        """The store never holds a TSV and never holds a CRLF.

        `sweep-armor.tsv` is the operand rather than the entity pair, because the entity fixtures
        are the A/B pair `sweep-entity-a.tsv` / `-b.tsv` and neither is the discovery name.
        """
        source = (DATA / "sweep-armor.tsv").read_bytes()
        self.assertIn(b"\r\n", source)  # the fixture really is the mixed form
        capture.normalize("sweep.armor", DATA, self.root, REPO, producer="x", runs=2)
        target = self.root / "sweeps" / "armor.json"
        self.assertNotIn(b"\r", target.read_bytes())
        payload = read_json(target)
        self.assertEqual(payload["kind"], "sweep-table")
        self.assertEqual(payload["key"], "subject")

    def test_the_delta_column_is_resolved_by_name_for_glint(self):
        capture.normalize("sweep.glint", DATA, self.root, REPO)
        rows = read_json(self.root / "sweeps" / "glint.json")["rows"]
        self.assertEqual(rows[0]["frames"], "30")
        self.assertIn("mean_argb_delta", rows[0])

    def test_provenance_rides_inside_the_artifact(self):
        capture.normalize("sweep.armor", DATA, self.root, REPO, producer="armorParityVanilla")
        payload = read_json(self.root / "sweeps" / "armor.json")
        self.assertEqual(payload["provenance"]["producer"], "armorParityVanilla")
        self.assertFalse((self.root / "provenance").exists())

    def test_an_unknown_artifact_kind_has_no_reader(self):
        with self.assertRaises(MissingInput):
            capture.normalize("roster.blindness-rules", DATA, self.root, REPO)

    def test_a_self_captured_row_must_name_the_working_root(self):
        with self.assertRaises(MissingInput) as caught:
            capture.normalize("pin.player-crc", DATA, self.root, REPO)
        self.assertIn("self-captured", str(caught.exception))

    def test_a_self_captured_row_absent_from_the_root_is_a_failure(self):
        """The backstop for a filtered test run, which no onlyIf can see."""
        with self.assertRaises(MissingInput) as caught:
            capture.normalize("pin.player-crc", self.root, self.root, REPO)
        self.assertIn("not written by its producer", str(caught.exception))

    def test_a_self_captured_row_present_is_validated_and_stamped(self):
        write_json(self.root / "pins" / "player-crc.json",
                   {"artifact": "pin.player-crc", "key": "pin_key", "kind": "pin-set", "pins": []})
        capture.normalize("pin.player-crc", self.root, self.root, REPO, runs=2)
        self.assertIn("provenance", read_json(self.root / "pins" / "player-crc.json"))


class Index(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp()) / "run"
        write_json(self.root / "sweeps" / "entity.json", {"artifact": "sweep.entity"})

    def test_COMPLETE_is_written_after_the_index(self):
        capture.index(self.root)
        marker = self.root / store.RUN_DIR / "COMPLETE"
        recorded = self.root / store.RUN_DIR / "_capture.json"
        self.assertTrue(marker.is_file())
        self.assertLessEqual(recorded.stat().st_mtime, marker.stat().st_mtime)

    def test_it_records_a_digest_per_file(self):
        capture.index(self.root)
        recorded = read_json(self.root / store.RUN_DIR / "_capture.json")
        self.assertEqual([entry["path"] for entry in recorded["files"]], ["sweeps/entity.json"])
        self.assertEqual(len(recorded["files"][0]["sha256"]), 64)

    def test_run_files_are_not_indexed_as_artifacts(self):
        capture.index(self.root)
        recorded = read_json(self.root / store.RUN_DIR / "_capture.json")
        self.assertNotIn("_run/_capture.json", [entry["path"] for entry in recorded["files"]])

    def test_require_complete_refuses_a_root_without_it(self):
        with self.assertRaises(Refused) as caught:
            capture.require_complete(self.root)
        self.assertIn("COMPLETE", str(caught.exception))

    def test_verify_against_index_notices_an_edit(self):
        capture.index(self.root)
        self.assertEqual(capture.verify_against_index(self.root), [])
        write_json(self.root / "sweeps" / "entity.json", {"artifact": "sweep.entity", "x": 1})
        self.assertEqual(capture.verify_against_index(self.root), ["sweeps/entity.json"])


if __name__ == "__main__":
    unittest.main()
