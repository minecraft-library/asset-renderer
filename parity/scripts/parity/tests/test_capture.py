"""The wipe, its one exemption, and COMPLETE written last."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import capture, promote, store, sweep
from parity.norm import MissingInput, Refused, read_json, write_json, write_text
from parity.norm import fixed as norm_fixed

DATA = Path(__file__).resolve().parent / "data"
REPO = Path(__file__).resolve().parents[4]


class Wipe(unittest.TestCase):
    """Single-slot made mechanical: there is no accumulation and nothing to rename."""

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

    def test_a_step_on_a_closed_root_refuses_rather_than_erasing_it(self):
        """A hand-run producer is one step and no begin, and the root it lands in may already hold
        a finished capture: `index` unlinks OPEN before it writes COMPLETE, so the begin branch is
        the one a lone step takes. Erasing there destroys a bundle nobody asked to replace."""
        capture.begin(self.root)
        self._artifact("fluid")
        capture.index(self.root)
        with self.assertRaises(Refused) as caught:
            capture.join_or_begin(self.root)
        self.assertIn(capture.COMPLETE, str(caught.exception))
        self.assertTrue((self.root / "manifests" / "fluid.json").is_file())

    def test_begin_still_erases_a_closed_root_so_single_slot_survives(self):
        """The erase is `capture-begin`'s act and stays unconditional: one root, one capture, and
        the next invocation replaces the previous whether or not it finished."""
        capture.begin(self.root)
        self._artifact("fluid")
        capture.index(self.root)
        capture.begin(self.root)
        self.assertFalse((self.root / "manifests" / "fluid.json").exists())
        self.assertFalse((self.root / store.RUN_DIR / capture.COMPLETE).exists())

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

    def test_a_captured_sweep_carries_the_summary_it_derives(self):
        """The fleet sum, the buckets, the row count and the failure count, written once.

        The same arithmetic `parity sum` and `parity buckets` print, and printing was the whole of
        it: no stored sweep carried one, so four registered pointers into `#/summary` resolved
        nowhere and the index row a promotion writes had nothing to lift.
        """
        capture.normalize("sweep.armor", DATA, self.root, REPO, runs=2)
        payload = read_json(self.root / "sweeps" / "armor.json")
        table = sweep.read_table(DATA / "sweep-armor.tsv", "armor")
        self.assertEqual(payload["summary"], {
            "buckets": sweep.buckets(table), "failed": table.failed(),
            "rows": len(payload["rows"]), "sum": norm_fixed(sweep.total(table))})

    def test_the_summary_sum_is_the_metric_form_every_delta_beside_it_uses(self):
        """A binary float's shortest repr moves with the last bit of an fsum over a thousand rows,
        and it would be the one number in the file spelled unlike its neighbours."""
        capture.normalize("sweep.armor", DATA, self.root, REPO, runs=2)
        summary = read_json(self.root / "sweeps" / "armor.json")["summary"]
        self.assertRegex(summary["sum"], r"^-?\d+\.\d{4}$")

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
                   {"artifact": "pin.player-crc", "key": "pin_key", "kind": "pin-set",
                    "values": {}})
        capture.normalize("pin.player-crc", self.root, self.root, REPO, runs=2)
        self.assertIn("provenance", read_json(self.root / "pins" / "player-crc.json"))


class RunsDefaultsToTheFloor(unittest.TestCase):
    """An absent `--runs` stamps the artifact's floor, which is what the build says this side owns.

    Defaulting it to zero instead stamped a number below every floor, so the standard invocation -
    a bare `parityCapture`, which passes no `-Pruns` - captured a bundle `promote.check` then
    refused. The refusal landed after the capture had run, which for a full bundle is the whole cost
    of the gate.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "run"

    def _runs(self, artifact_id: str, floors: dict[str, int] | None = None, **kwargs) -> int:
        """Stamp one artifact against a store declaring the given floors, and read the number back.

        The store is a fixture rather than the shipped one, because what is under test is which
        table answers: two artifacts of one kind with different floors cannot both be a literal, and
        the shipped roster gives every digest set the same number.
        """
        declared = floors or {artifact_id: 2}
        write_json(self.repo / store.PRODUCTION / "index.json",
                   {"artifacts": {name: {promote.FLOOR_FIELD: floor}
                                  for name, floor in declared.items()}})
        write_json(self.root / store.path_of(artifact_id),
                   {"artifact": artifact_id, "key": "name", "kind": "digest-set",
                    "digests": {"block_models": {"sha256": "a"}}})
        capture.normalize(artifact_id, self.root, self.root, self.repo, **kwargs)
        return read_json(self.root / store.path_of(artifact_id))["provenance"]["determinism_runs"]

    def test_an_absent_runs_stamps_the_declared_floor(self):
        self.assertEqual(self._runs("digest.shipped-tables"), 2)

    def test_the_default_is_the_ARTIFACT_s_floor_and_not_the_common_one(self):
        floors = {"digest.colormap-lut": 5, "digest.shipped-tables": 2}
        self.assertEqual(self._runs("digest.colormap-lut", floors), 5)
        self.assertEqual(self._runs("digest.shipped-tables", floors), 2)

    def test_an_unregistered_artifact_refuses_rather_than_defaulting(self):
        """A default here is a second table, and the shipped pair disagreed for the store's life."""
        with self.assertRaises(MissingInput):
            self._runs("digest.colormap-lut", {"digest.shipped-tables": 2})

    def test_an_explicit_runs_is_never_overwritten_by_the_floor(self):
        self.assertEqual(self._runs("digest.colormap-lut", runs=7), 7)

    def test_an_explicit_zero_stays_zero_so_the_default_is_absence_and_not_falsehood(self):
        """A measured zero is a claim someone made, and it must still be refused at promote."""
        self.assertEqual(self._runs("digest.colormap-lut", runs=0), 0)


class SelfCapturedPayload(unittest.TestCase):
    """What a producer may declare in the file it self-captures, and what is counted for it."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp()) / "run"

    def _write(self, **extra) -> None:
        write_json(self.root / "digests" / "shipped-tables.json",
                   {"artifact": "digest.shipped-tables", "key": "name", "kind": "digest-set",
                    "digests": {"block_models": {"form": "table-canonical", "sha256": "a"},
                                "glint_items": {"form": "table-canonical", "sha256": "b"}},
                    **extra})

    def _capture(self, **kwargs) -> dict:
        capture.normalize("digest.shipped-tables", self.root, self.root, REPO, **kwargs)
        return read_json(self.root / "digests" / "shipped-tables.json")

    def test_the_entries_are_counted_rather_than_declared(self):
        """Counted from the finished file, so the number cannot disagree with what is stored."""
        self._write()
        self.assertEqual(self._capture()["provenance"]["counts"], {"digests": 2})

    def test_a_producer_declared_flag_reaches_provenance(self):
        """The only channel for a value only the measuring process knows."""
        self._write(_flags=["gson=2.13.2"])
        self.assertEqual(self._capture()["provenance"]["flags"], {"gson": "2.13.2"})

    def test_the_declared_flag_is_not_stored_as_payload(self):
        self._write(_flags=["gson=2.13.2"])
        self.assertNotIn("_flags", self._capture())

    def test_an_observed_flag_beats_a_declared_one(self):
        """A `--flag` guessed on the build side must not overwrite what the producer measured."""
        self._write(_flags=["gson=2.13.2"])
        self.assertEqual(self._capture(flags=["gson=2.11.0"])["provenance"]["flags"]["gson"],
                         "2.13.2")

    def test_a_payload_with_no_countable_member_records_no_counts(self):
        write_json(self.root / "pins" / "player-crc.json",
                   {"artifact": "pin.player-crc", "key": "pin_key", "kind": "pin-set"})
        capture.normalize("pin.player-crc", self.root, self.root, REPO)
        self.assertNotIn("counts", read_json(self.root / "pins" / "player-crc.json")["provenance"])


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

    def test_it_records_the_tree_and_nothing_the_build_never_sent(self):
        """Four fields were structurally empty on every capture and read by nothing anywhere: the
        build's argv sent no producer and no flag, the timestamp had no command-line spelling at
        all, and the run count is answered per artifact by the provenance object that measured it."""
        capture.index(self.root)
        recorded = read_json(self.root / store.RUN_DIR / "_capture.json")
        self.assertEqual(sorted(recorded), ["artifacts", "files", "format", "kind"])

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

    def test_require_unmoved_passes_an_untouched_root_and_refuses_a_moved_one(self):
        """The shared refusal behind both readers of a finished capture."""
        capture.index(self.root)
        capture.require_unmoved(self.root)
        write_json(self.root / "sweeps" / "entity.json", {"artifact": "sweep.entity", "x": 1})
        with self.assertRaises(Refused) as caught:
            capture.require_unmoved(self.root)
        self.assertIn("changed since", str(caught.exception))
        self.assertIn("sweeps/entity.json", str(caught.exception))

    def test_the_content_digest_names_the_capture_and_moves_with_it(self):
        """One name for the tree a finished capture holds, so a later reader can say WHICH capture
        a report it is holding was written about. Two captures of different bytes into one slot are
        the case that has to differ - the root's path is the same on both."""
        capture.index(self.root)
        first = capture.content_digest(self.root)
        self.assertEqual(len(first), 64)
        self.assertEqual(first, capture.content_digest(self.root))
        capture.begin(self.root)
        write_json(self.root / "sweeps" / "entity.json", {"artifact": "sweep.entity", "x": 1})
        capture.index(self.root)
        self.assertNotEqual(capture.content_digest(self.root), first)


if __name__ == "__main__":
    unittest.main()
