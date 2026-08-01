"""Every refusal promotion makes, and the normalization that happens on the way in."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import capture, promote, store
from parity.norm import MissingInput, Refused, read_json, read_text, write_json, write_text


def artifact(runs: int = 2, failed: int = 0, delta: str = "1.0000") -> dict:
    return {
        "artifact": "sweep.entity", "format": 1, "key": "subject", "kind": "sweep-table",
        "provenance": {"artifact": "sweep.entity", "counts": {"failed": failed, "rows": 1},
                       "determinism_runs": runs, "asset_sha": "abc123"},
        "rows": [{"subject": "minecraft__cow", "mean_argb_delta": delta, "status": "ok"}],
    }


class Floors(unittest.TestCase):

    def test_the_salt_exposed_pair_needs_five(self):
        """Intermittent, so an oracle can pass twice and fail the third time."""
        self.assertEqual(promote.floor_for("manifest.dump.vanilla"), 5)
        self.assertEqual(promote.floor_for("manifest.dump.packs"), 5)

    def test_everything_else_needs_two(self):
        self.assertEqual(promote.floor_for("sweep.entity"), 2)
        self.assertEqual(promote.floor_for("manifest.fluid"), 2)


class Base(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.root = self.tmp / "run"
        self.store = store.WritableStore(self.tmp / "prod")

    def _capture(self, payload: dict) -> None:
        write_json(self.root / "sweeps" / "entity.json", payload)
        capture.index(self.root, runs=payload["provenance"]["determinism_runs"])


class Plan(unittest.TestCase, ):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.root = self.tmp / "run"
        self.store = store.WritableStore(self.tmp / "prod")
        write_json(self.root / "sweeps" / "entity.json", artifact())

    def test_a_first_capture_is_new(self):
        entries = promote.plan(self.root, self.store)
        self.assertEqual([(e.artifact, e.action) for e in entries], [("sweep.entity", "new")])

    def test_an_identical_payload_is_unchanged(self):
        self.store.write("sweep.entity", artifact())
        self.assertEqual(promote.plan(self.root, self.store)[0].action, "unchanged")

    def test_provenance_alone_does_not_make_a_replace(self):
        """It differs on every capture, so it cannot be what decides whether to promote."""
        other = artifact()
        other["provenance"]["timestamp"] = "2020-01-01T00:00:00Z"
        self.store.write("sweep.entity", other)
        self.assertEqual(promote.plan(self.root, self.store)[0].action, "unchanged")

    def test_a_moved_row_is_a_replace_carrying_its_mover_count(self):
        self.store.write("sweep.entity", artifact(delta="2.0000"))
        entry = promote.plan(self.root, self.store)[0]
        self.assertEqual((entry.action, entry.movers), ("replace", 1))

    def test_it_writes_nothing_outside_the_working_root(self):
        before = sorted(p.name for p in (self.tmp / "prod").rglob("*")) if (self.tmp / "prod").exists() else []
        promote.plan(self.root, self.store)
        after = sorted(p.name for p in (self.tmp / "prod").rglob("*")) if (self.tmp / "prod").exists() else []
        self.assertEqual(before, after)

    def test_an_artifact_absent_from_the_root_is_missing_input(self):
        with self.assertRaises(MissingInput):
            promote.plan(self.root, self.store, ["sweep.block"])


class Refusals(Base):

    def test_no_reason(self):
        self._capture(artifact())
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "")
        self.assertIn("--reason", str(caught.exception))

    def test_determinism_below_the_floor(self):
        self._capture(artifact(runs=1))
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", bootstrap=True)
        self.assertIn("determinism_runs=1", str(caught.exception))

    def test_a_partial_run(self):
        self._capture(artifact(failed=3))
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", bootstrap=True)
        self.assertIn("failed=3", str(caught.exception))

    def test_a_partial_run_passes_with_allow_partial(self):
        self._capture(artifact(failed=3))
        entries = promote.plan(self.root, self.store)
        promote.check(self.root, entries, "why", allow_partial=True, bootstrap=True)

    def test_a_new_artifact_without_bootstrap(self):
        self._capture(artifact())
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why")
        self.assertIn("bootstrap", str(caught.exception))

    def test_an_artifact_with_no_provenance(self):
        payload = artifact()
        payload.pop("provenance")
        write_json(self.root / "sweeps" / "entity.json", payload)
        capture.index(self.root)
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", bootstrap=True)
        self.assertIn("provenance", str(caught.exception))

    def test_a_root_edited_after_its_capture_index(self):
        """A plan cannot be applied to a root that has since been re-captured."""
        self._capture(artifact())
        entries = promote.plan(self.root, self.store)
        write_json(self.root / "sweeps" / "entity.json", artifact(delta="9.9999"))
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", bootstrap=True)
        self.assertIn("changed since", str(caught.exception))

    def test_a_root_with_no_COMPLETE(self):
        write_json(self.root / "sweeps" / "entity.json", artifact())
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", bootstrap=True)
        self.assertIn("COMPLETE", str(caught.exception))


class Apply(Base):

    def test_it_writes_provenance_inside_and_updates_the_index_in_one_act(self):
        self._capture(artifact())
        entries = promote.plan(self.root, self.store)
        promote.apply(self.root, self.store, entries, "because", parity_class="neutral")
        stored = self.store.read("sweep.entity")
        self.assertEqual(stored["provenance"]["reason"], "because")
        self.assertEqual(stored["provenance"]["parity_class"], "neutral")
        index = self.store.index()
        self.assertEqual(index["artifacts"]["sweep.entity"]["entries"], 1)
        self.assertEqual(index["artifacts"]["sweep.entity"]["file"], "sweeps/entity.json")

    def test_a_promoted_row_says_it_is_baselined(self):
        """Affirmatively, not by dropping the key the empty store carries.

        Both readers of the column ask it for a boolean - the index test and the generated README -
        so absence-means-promoted made one throw and the other render every promoted artifact as
        not baselined.
        """
        self._capture(artifact())
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "r")
        self.assertIs(self.store.index()["artifacts"]["sweep.entity"]["baselined"], True)

    def test_there_is_no_provenance_directory_on_either_side(self):
        self._capture(artifact())
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "r")
        self.assertFalse((self.store.root / "provenance").exists())
        self.assertFalse((self.root / "provenance").exists())

    def test_a_crlf_capture_is_normalized_on_the_way_in(self):
        """Why the copy is Python and not a Kotlin copy { }: a byte copy carries CRLF into the store."""
        write_text(self.root / "sweeps" / "entity.json", "{}")
        raw = read_text(self.root / "sweeps" / "entity.json")
        write_json(self.root / "sweeps" / "entity.json", artifact())
        (self.root / "sweeps" / "entity.json").write_bytes(
            (self.root / "sweeps" / "entity.json").read_bytes().replace(b"\n", b"\r\n"))
        capture.index(self.root, runs=2)
        entries = promote.plan(self.root, self.store)
        promote.apply(self.root, self.store, entries, "r")
        self.assertNotIn(b"\r", self.store.path("sweep.entity").read_bytes())

    def test_an_unchanged_entry_is_not_rewritten(self):
        self._capture(artifact())
        self.store.write("sweep.entity", artifact())
        entries = promote.plan(self.root, self.store)
        result = promote.apply(self.root, self.store, entries, "r")
        self.assertEqual(result["promoted"], [])

    def test_class_defaults_to_moving_so_forgetting_cannot_understate(self):
        self._capture(artifact())
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "r")
        self.assertEqual(self.store.read("sweep.entity")["provenance"]["parity_class"], "moving")

    def test_no_archive_copy_is_kept(self):
        """U4: the store holds the last known value, not a history."""
        self._capture(artifact())
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "first")
        write_json(self.root / "sweeps" / "entity.json", artifact(delta="2.0000"))
        capture.index(self.root, runs=2)
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "second")
        self.assertFalse((self.store.root / "archive").exists())
        self.assertEqual(self.store.read("sweep.entity")["rows"][0]["mean_argb_delta"], "2.0000")


class IndexEntryCount(Base):
    """`entries` is the count under the payload member's OWN name - one rule, every kind."""

    def _promote(self, artifact_id: str, relative: str, payload: dict) -> dict:
        write_json(self.root / relative, payload)
        capture.index(self.root, runs=2)
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "r")
        return self.store.index()["artifacts"][artifact_id]

    def test_a_manifest_counts_its_files(self):
        row = self._promote("manifest.fluid", "manifests/fluid.json", {
            "artifact": "manifest.fluid", "format": 1, "key": "path", "kind": "manifest",
            "files": [{"path": "a.gif", "sha256": "1"}, {"path": "b.gif", "sha256": "2"}],
            "provenance": {"counts": {"files": 2}, "determinism_runs": 2}})
        self.assertEqual(row["entries"], 2)

    def test_a_manifest_with_logs_still_counts_only_the_primary_payload(self):
        """Accepted: `manifest.tooling-tables` reads 10 where the gate joins 18."""
        row = self._promote("manifest.tooling-tables", "manifests/tooling-tables.json", {
            "artifact": "manifest.tooling-tables", "format": 1, "key": "path", "kind": "manifest",
            "files": [{"path": "a.json", "sha256": "1"}],
            "logs": {"blockItems": "d", "blockTints": "e"},
            "provenance": {"counts": {"files": 1, "logs": 2}, "determinism_runs": 2}})
        self.assertEqual(row["entries"], 1)

    def test_a_digest_set_counts_its_digests(self):
        row = self._promote("digest.shipped-tables", "digests/shipped-tables.json", {
            "artifact": "digest.shipped-tables", "format": 1, "key": "name", "kind": "digest-set",
            "digests": {"block_models": {"sha256": "a"}, "glint_items": {"sha256": "b"}},
            "provenance": {"counts": {"digests": 2}, "determinism_runs": 2}})
        self.assertEqual(row["entries"], 2)

    def test_a_pin_set_counts_its_values(self):
        row = self._promote("pin.corpus-count", "pins/corpus-count.json", {
            "artifact": "pin.corpus-count", "format": 1, "key": "pin_key", "kind": "pin-set",
            "values": {"glint_items": {"count": 7, "type": "int"}},
            "provenance": {"counts": {"values": 1}, "determinism_runs": 2}})
        self.assertEqual(row["entries"], 1)


if __name__ == "__main__":
    unittest.main()
