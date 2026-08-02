"""The record that makes a baseline self-identifying, and its graceful degradation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import provenance
from parity.norm import sha256_file, write_text

REPO = Path(__file__).resolve().parents[3]


class Fields(unittest.TestCase):

    def setUp(self):
        self.record = provenance.gather("sweep.entity", REPO, producer="entityParityVanilla",
                                        runs=2, mode="FULL", flags=("asset.depth.range=1000",))

    def test_every_registered_key_is_present(self):
        for name in ("artifact", "asset_dirty", "asset_sha", "determinism_runs", "flags",
                     "mc_version", "producer", "timestamp", "tool_version"):
            self.assertIn(name, self.record, name)

    def test_there_is_no_harness_sha(self):
        """Post-consolidation the harness is a directory in this repo, so the two values would be
        equal by construction - I-12's 'no value stored twice' broken by identity."""
        self.assertNotIn("harness_sha", self.record)
        self.assertNotIn("harness_dirty", self.record)

    def test_flags_parse_to_an_object(self):
        self.assertEqual(self.record["flags"], {"asset.depth.range": "1000"})

    def test_runs_is_recorded_as_given(self):
        """It is how many runs AGREED - a measurement, not the number a caller asked for."""
        self.assertEqual(self.record["determinism_runs"], 2)

    def test_the_timestamp_is_utc_iso8601(self):
        self.assertRegex(self.record["timestamp"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

    def test_mc_version_is_read_from_the_harness_properties(self):
        self.assertEqual(self.record["mc_version"], "26.1.2")


class Degradation(unittest.TestCase):
    """A missing repo degrades to nulls with a warning rather than failing the capture."""

    def setUp(self):
        self.empty = Path(tempfile.mkdtemp())

    def test_no_harness_properties_gives_a_null_mc_version(self):
        self.assertIsNone(provenance.mc_version(self.empty))

    def test_no_reference_tree_gives_empty_counts(self):
        self.assertEqual(provenance.reference_counts(self.empty), {})

    def test_gather_still_produces_a_record(self):
        record = provenance.gather("sweep.entity", self.empty, producer="x")
        self.assertEqual(record["artifact"], "sweep.entity")
        self.assertIsNone(record["mc_version"])


class DirtyDigest(unittest.TestCase):
    """The third value, because a sha and a boolean cannot tell two edits on one commit apart."""

    def test_a_real_repo_answers_a_digest(self):
        digest = provenance.dirty_digest(REPO)
        self.assertIsNotNone(digest)
        self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_it_is_stable_across_calls(self):
        """It gates whether a tree has already been gated, so an unstable answer re-arms for ever."""
        self.assertEqual(provenance.dirty_digest(REPO), provenance.dirty_digest(REPO))

    def test_no_repo_answers_none_rather_than_the_empty_digest(self):
        """None is 'git could not be asked'; the empty digest is 'nothing is uncommitted'. A caller
        that conflated them would read an unreadable git as a clean tree."""
        self.assertIsNone(provenance.dirty_digest(Path(tempfile.mkdtemp())))


class ManifestDigest(unittest.TestCase):
    """A row identifies a reference set in one field instead of carrying 2311 lines."""

    def test_it_is_the_sha_of_the_manifest_file(self):
        target = Path(tempfile.mkdtemp()) / "refs.sha256"
        write_text(target, f"{'a' * 64} *one.png")
        self.assertEqual(provenance.manifest_digest(target), sha256_file(target))

    def test_absent_is_none_not_a_throw(self):
        self.assertIsNone(provenance.manifest_digest(Path(tempfile.mkdtemp()) / "nope"))


class ReferenceCounts(unittest.TestCase):

    @unittest.skipUnless((REPO / provenance.REFERENCE_ROOT).is_dir(), "reference tree absent")
    def test_the_live_tree_counts_per_subtree(self):
        counts = provenance.reference_counts(REPO)
        self.assertEqual(counts.get("entities"), 402)
        self.assertEqual(counts.get("armor"), 7)
        self.assertEqual(counts.get("players"), 2)
        self.assertEqual(sum(counts.values()), 2311)


if __name__ == "__main__":
    unittest.main()
