"""Every refusal promotion makes, and the normalization that happens on the way in."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Sequence

from parity import capture, compare, promote, store
from parity.norm import MissingInput, Refused, read_json, read_text, write_json, write_text


def artifact(runs: int = 2, failed: int = 0, delta: str = "1.0000", dirty: bool = False) -> dict:
    return {
        "artifact": "sweep.entity", "format": 1, "key": "subject", "kind": "sweep-table",
        "provenance": {"artifact": "sweep.entity", "asset_dirty": dirty, "asset_sha": "abc123",
                       "counts": {"failed": failed, "rows": 1}, "determinism_runs": runs},
        "rows": [{"subject": "minecraft__cow", "mean_argb_delta": delta, "status": "ok"}],
    }


def digest_set(runs: int = 2) -> dict:
    """A second capturable artifact, of a second kind, for the cases that need two rows at once."""
    return {
        "artifact": "digest.shipped-tables", "format": 1, "key": "name", "kind": "digest-set",
        "digests": {"block_models": {"sha256": "a"}},
        "provenance": {"artifact": "digest.shipped-tables", "asset_dirty": False,
                       "asset_sha": "abc123", "counts": {"digests": 1},
                       "determinism_runs": runs},
    }


#: Every artifact these fixtures capture. The store declares a floor per artifact before any
#: capture of it can be judged, so a fixture store has to be registered as the real one is.
REGISTERED = ("sweep.entity", "manifest.fluid", "manifest.tooling-tables",
              "digest.shipped-tables", "pin.corpus-count")


def registered(*artifacts: str, floor: int = 2, **columns) -> dict:
    """An index envelope declaring a floor, and whatever else is asked for, per artifact."""
    return {"artifacts": {name: {promote.FLOOR_FIELD: floor, **columns} for name in artifacts}}


class Floors(unittest.TestCase):
    """One table, and it is the roster's: the index row carries it and this reads it from there."""

    def test_the_floor_is_the_one_the_index_row_declares(self):
        self.assertEqual(promote.floor_for("manifest.dump.vanilla",
                                           registered("manifest.dump.vanilla", floor=5)), 5)
        self.assertEqual(promote.floor_for("sweep.entity", registered("sweep.entity")), 2)

    def test_an_unregistered_artifact_is_missing_input_rather_than_a_default(self):
        """A default here is a second table, and the two disagreed for the whole store's life."""
        with self.assertRaises(MissingInput) as caught:
            promote.floor_for("sweep.block", registered("sweep.entity"))
        self.assertIn("declares no determinism_floor for 'sweep.block'", str(caught.exception))


class Base(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.root = self.tmp / "run"
        self.store = store.WritableStore(self.tmp / "prod")
        self.store.write("report.oracle-index", registered(*REGISTERED))

    def _capture(self, payload: dict) -> None:
        write_json(self.root / "sweeps" / "entity.json", payload)
        capture.index(self.root)

    def _compared(self, *artifacts: str, missing: Sequence[str] = ()) -> None:
        """Leave behind the report `parityCompare` writes, stamped with THIS capture's digest.

        The two lists are where they really land: an artifact with a baseline is diffed and listed
        under `artifacts`, and one without is looked up, found absent and listed under
        `missing_baseline`. `compare` never puts a baseline-less id in the first list, so a fixture
        that does describes no run and measures nothing.

        :param artifacts: the ids the compare diffed; the captured sweep alone by default
        :param missing: the ids it looked up and found no baseline for
        """
        diffed = artifacts or (() if missing else ("sweep.entity",))
        write_json(self.root / store.RUN_DIR / compare.REPORT, {
            compare.CAPTURE_DIGEST: capture.content_digest(self.root),
            "artifacts": [{"artifact": name} for name in diffed],
            "format": 1, "missing_baseline": list(missing),
            "totals": {"artifacts": len(diffed), "unexpected": 0}})


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
            promote.check(self.root, entries, "", self.store.index())
        self.assertIn("--reason", str(caught.exception))

    def test_determinism_below_the_floor(self):
        # Each of the four below is a per-ENTRY refusal, reached under `bootstrap=True` because the
        # store here is empty - which is also what exempts them from the per-ROOT compare
        # requirement, so no report has to be staged to reach the loop.
        self._capture(artifact(runs=1))
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", self.store.index(), bootstrap=True)
        self.assertIn("determinism_runs=1", str(caught.exception))

    def test_a_partial_run(self):
        self._capture(artifact(failed=3))
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", self.store.index(), bootstrap=True)
        self.assertIn("failed=3", str(caught.exception))

    def test_a_partial_run_passes_with_allow_partial(self):
        self._capture(artifact(failed=3))
        entries = promote.plan(self.root, self.store)
        promote.check(self.root, entries, "why", self.store.index(), allow_partial=True,
                      bootstrap=True)

    def test_a_new_artifact_without_bootstrap(self):
        self._capture(artifact())
        # Without the flag the compare requirement applies, so the fixture has to satisfy it or the
        # refusal under test never gets reached. It is staged where a compare really files an
        # artifact it found no baseline for: listing it under `artifacts` instead would fabricate a
        # report shape `compare` never writes.
        self._compared(missing=("sweep.entity",))
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", self.store.index())
        # The clause, not the word: `--bootstrap` is named by the compare refusal too, so a check
        # for "bootstrap" alone passes on the wrong refusal firing first.
        self.assertIn("has no baseline to replace", str(caught.exception))

    def test_a_NAMED_artifact_with_no_provenance(self):
        """Asking for one that no capture step stamped is an error, where enumerating skips it."""
        self._unstamped()
        entries = promote.plan(self.root, self.store, ["sweep.entity"])
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", self.store.index(), bootstrap=True)
        self.assertIn("provenance", str(caught.exception))

    def test_an_UNSTAMPED_artifact_is_not_enumerated(self):
        """A self-capturing producer writes its file whenever it runs, so a root captured for one
        artifact holds files for others. Crediting this run with them planned to REPLACE a promoted
        digest set with a by-product that differed from it by the private `_flags` key alone."""
        self._unstamped()
        self.assertEqual(promote.plan(self.root, self.store), [])

    def _unstamped(self) -> None:
        payload = artifact()
        payload.pop("provenance")
        write_json(self.root / "sweeps" / "entity.json", payload)
        capture.index(self.root)

    def test_a_root_edited_after_its_capture_index(self):
        """A plan cannot be applied to a root that has since been re-captured."""
        self._capture(artifact())
        entries = promote.plan(self.root, self.store)
        write_json(self.root / "sweeps" / "entity.json", artifact(delta="9.9999"))
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", self.store.index(), bootstrap=True)
        self.assertIn("changed since", str(caught.exception))

    def test_a_root_with_no_COMPLETE(self):
        write_json(self.root / "sweeps" / "entity.json", artifact())
        entries = promote.plan(self.root, self.store)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, entries, "why", self.store.index(), bootstrap=True)
        self.assertIn("COMPLETE", str(caught.exception))


class ACompareMustHaveHappened(Base):
    """A promotion applies a diff someone has been shown, and that was implemented nowhere.

    The design named it as the mechanism that makes promotion mechanical rather than judged, and
    `promote.check`'s only root-integrity gate re-hashed the capture against its own index - which
    says the root has not moved and nothing at all about whether anybody looked at it.
    """

    def setUp(self):
        super().setUp()
        self.store.write("sweep.entity", artifact(delta="2.0000"))
        self._capture(artifact())
        self.entries = promote.plan(self.root, self.store)

    def test_a_capture_its_own_compare_covered_promotes(self):
        """The green path, so every refusal below it is a refusal of something and not of everything.

        Stated rather than counted: the count this sentence used to carry was measured once, and two
        cases were appended below it afterwards.
        """
        self._compared()
        promote.check(self.root, self.entries, "why", self.store.index())

    def test_no_report_at_all(self):
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, self.entries, "why", self.store.index())
        self.assertIn(f"no {store.RUN_DIR}/{compare.REPORT}", str(caught.exception))

    def test_a_report_left_behind_by_an_earlier_capture_of_the_same_slot(self):
        """The working root is one self-overwriting slot, so 'a report is there' is not evidence.

        The report is written first and the root re-captured under it with different bytes - which
        is exactly the shape a second `parityCapture` leaves when the operator forgets to re-compare.
        """
        self._compared()
        stale = read_json(self.root / store.RUN_DIR / compare.REPORT)
        write_json(self.root / "sweeps" / "entity.json", artifact(delta="3.0000"))
        capture.index(self.root)
        write_json(self.root / store.RUN_DIR / compare.REPORT, stale)
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, promote.plan(self.root, self.store), "why", self.store.index())
        self.assertIn("written against a different capture", str(caught.exception))

    def test_a_report_that_did_not_cover_the_artifact_being_written(self):
        """`-Partifacts` scopes compare and promote separately, so one can name what the other did
        not - and an artifact nobody compared is promoted with no comparison ever run, which is the
        whole defect, wearing a report as cover."""
        self._compared("manifest.fluid")
        with self.assertRaises(Refused) as caught:
            promote.check(self.root, self.entries, "why", self.store.index())
        self.assertIn("the compare did not cover sweep.entity", str(caught.exception))

    def test_an_unchanged_entry_is_not_held_to_it(self):
        """`apply` skips an unchanged entry, so it writes no production byte to compare against."""
        self.store.write("sweep.entity", artifact())
        self._compared("manifest.fluid")
        promote.check(self.root, promote.plan(self.root, self.store), "why", self.store.index())

    def test_a_row_the_compare_found_no_baseline_for_is_covered_by_that(self):
        """A compare files a baseline-less row under `missing_baseline` and never under `artifacts`.

        `--base` points a compare at a tree other than the one being promoted into, so a row this
        store holds can be one that compare found nothing to diff against. It LOOKED, which is this
        function's whole question, and reading the one list alone refuses a promotion for not having
        been compared when it was.
        """
        self._compared(missing=("sweep.entity",))
        promote.check(self.root, self.entries, "why", self.store.index())

    def test_bootstrap_is_the_exemption(self):
        """A first baseline has nothing to be diffed against, so the requirement does not apply.

        No compare has run in this root at all, which is the whole invocation the exemption is for:
        the plan is a `new` against a store holding nothing.
        """
        first = store.WritableStore(self.tmp / "first")
        promote.check(self.root, promote.plan(self.root, first), "why", self.store.index(),
                      bootstrap=True)

    def test_the_exemption_is_per_invocation_and_carries_the_rows_beside_the_new_one(self):
        """Asserted because it is the exemption's cost rather than a feature of it.

        The flag is typed because a row has no baseline and it cannot say which row that was, so a
        bootstrap promotion over a whole root writes the already-baselined rows in it with no
        compare either. This plan REPLACES a baseline and no compare has ever run. `--artifacts` is
        what narrows the write to the row the flag was meant for.
        """
        promote.check(self.root, self.entries, "why", self.store.index(), bootstrap=True)


class ADirtyTreeIsNotPromotable(Base):
    """The one refusal whose failure mode cannot be detected after the fact.

    A baseline captured from an uncommitted tree is re-derivable from no commit, and the stored
    value does not say so in any way a later reader can act on. The skill has shipped the rule as a
    decision-table row since the day it landed; `promote.py` never read the field.
    """

    def setUp(self):
        super().setUp()
        self.store.write("sweep.entity", artifact(delta="2.0000"))

    def _check(self, payload: dict, **kwargs) -> None:
        self._capture(payload)
        self._compared()
        promote.check(self.root, promote.plan(self.root, self.store), "why", self.store.index(),
                      **kwargs)

    def test_a_clean_capture_promotes(self):
        self._check(artifact(dirty=False))

    def test_a_dirty_capture_is_refused(self):
        with self.assertRaises(Refused) as caught:
            self._check(artifact(dirty=True))
        self.assertIn("records asset_dirty=True", str(caught.exception))

    def test_a_capture_whose_git_could_not_be_asked_is_refused_too(self):
        """`asset_state` records null when git is unavailable, and null is not evidence of clean."""
        payload = artifact()
        payload["provenance"]["asset_dirty"] = None
        with self.assertRaises(Refused) as caught:
            self._check(payload)
        self.assertIn("records asset_dirty=None", str(caught.exception))

    def test_a_provenance_with_no_asset_dirty_field_is_refused(self):
        payload = artifact()
        payload["provenance"].pop("asset_dirty")
        with self.assertRaises(Refused):
            self._check(payload)

    def test_allow_dirty_is_the_explicit_override(self):
        self._check(artifact(dirty=True), allow_dirty=True)

    def test_the_override_is_recorded_in_the_promoted_provenance(self):
        """An override nothing writes down is indistinguishable from the refusal never firing."""
        self._capture(artifact(dirty=True))
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "r",
                      allow_dirty=True)
        self.assertIs(self.store.read("sweep.entity")["provenance"]["allow_dirty"], True)

    def test_an_ordinary_promotion_records_no_override(self):
        self._capture(artifact())
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "r")
        self.assertNotIn("allow_dirty", self.store.read("sweep.entity")["provenance"])


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
        capture.index(self.root)
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
        """The store holds the last known value, not a history."""
        self._capture(artifact())
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "first")
        write_json(self.root / "sweeps" / "entity.json", artifact(delta="2.0000"))
        capture.index(self.root)
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "second")
        self.assertFalse((self.store.root / "archive").exists())
        self.assertEqual(self.store.read("sweep.entity")["rows"][0]["mean_argb_delta"], "2.0000")


class IndexEntryCount(Base):
    """`entries` is the count under the payload member's OWN name - one rule, every kind."""

    def _promote(self, artifact_id: str, relative: str, payload: dict) -> dict:
        write_json(self.root / relative, payload)
        capture.index(self.root)
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


class TheIndexRowCarriesTheRegistrationAndTheHeadline(Base):
    """What a promotion writes into the row beside the digest, and where each of the two comes from.

    The floor is a registration and the sum is a measurement, and the row is rebuilt field by field
    on every promotion - so anything not written here is dropped. The floor is read off the row this
    one replaces and written back, which is what keeps one table; the sum is lifted out of the
    payload's own derived summary, which is where the fleet sum lives.
    """

    #: Two artifacts declaring DIFFERENT floors. A fixture where every row carries the same number
    #: cannot tell a row's own registration from a literal written into the rebuild - and a literal
    #: is exactly what the second table this replaced was.
    FLOORS = {"sweep.entity": 3, "digest.shipped-tables": 5}

    def setUp(self):
        super().setUp()
        self.store.write("report.oracle-index", {"artifacts": {
            name: {promote.FLOOR_FIELD: floor} for name, floor in self.FLOORS.items()}})

    def _promote(self, relative: str, payload: dict, artifact_id: str = "sweep.entity") -> dict:
        write_json(self.root / relative, payload)
        capture.index(self.root)
        promote.apply(self.root, self.store, promote.plan(self.root, self.store), "r")
        return self.store.index()["artifacts"][artifact_id]

    def test_each_row_carries_the_floor_ITS_OWN_registration_declares(self):
        self.assertEqual(self._promote("sweeps/entity.json", artifact())[promote.FLOOR_FIELD], 3)
        self.assertEqual(self._promote("digests/shipped-tables.json", digest_set(),
                                       "digest.shipped-tables")[promote.FLOOR_FIELD], 5)

    def test_the_fleet_sum_is_lifted_out_of_the_summary(self):
        payload = artifact()
        payload["summary"] = {"buckets": {}, "failed": 0, "rows": 1, "sum": "60.0047"}
        self.assertEqual(self._promote("sweeps/entity.json", payload)["sum"], "60.0047")

    def test_a_payload_with_no_summary_carries_no_sum_column(self):
        """Only the sweeps derive one, so the README falls back to the entry count elsewhere."""
        self.assertNotIn("sum", self._promote("sweeps/entity.json", artifact()))


class APopulationThatMovedIsRefused(Base):
    """`--population-changed` stamped a flag and compared nothing, so it waived nothing.

    A row count that moved is a different covered set, and the tree hashes cleanly either way - so
    the flag recorded an exception to a rule that was never enforced.
    """

    def setUp(self):
        super().setUp()
        self.store.write("report.oracle-index",
                         registered("sweep.entity", floor=2, entries=1))
        self.store.write("sweep.entity", artifact(delta="2.0000"))

    def _check(self, rows: int, **kwargs) -> None:
        payload = artifact()
        payload["provenance"]["counts"]["rows"] = rows
        self._capture(payload)
        self._compared()
        promote.check(self.root, promote.plan(self.root, self.store), "why", self.store.index(),
                      **kwargs)

    def test_an_unchanged_population_promotes(self):
        self._check(1)

    def test_a_shrunk_population_is_refused_and_names_both_numbers(self):
        with self.assertRaises(Refused) as caught:
            self._check(0)
        self.assertIn("captured 0 rows where its baseline holds 1", str(caught.exception))

    def test_a_grown_population_is_refused_too(self):
        """Growth and shrinkage are the same question: the covered set is not the one baselined."""
        with self.assertRaises(Refused):
            self._check(2)

    def test_the_flag_is_what_admits_it(self):
        self._check(0, population_changed=True)

    def test_a_row_with_no_baseline_count_has_nothing_to_have_moved_from(self):
        """A first promotion's population is whatever it captured, and --bootstrap says so."""
        self.store.write("report.oracle-index", registered("sweep.entity", floor=2))
        self._check(99)


if __name__ == "__main__":
    unittest.main()
