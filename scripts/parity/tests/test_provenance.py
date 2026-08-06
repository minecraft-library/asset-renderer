"""The record that makes a baseline self-identifying, and its graceful degradation."""

from __future__ import annotations

import contextlib
import inspect
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from parity import capture, provenance
from parity.norm import (canonical_json, read_json, sha256_file, sha256_text, write_json,
                         write_text)

REPO = Path(__file__).resolve().parents[3]

#: Where `parity` is importable from, for a case whose subject is a fresh interpreter's environment.
TOOLKIT = Path(__file__).resolve().parents[2]

#: An edit for the cases whose subject is how git's bytes are decoded. Both characters are outside
#: ASCII and their UTF-8 bytes are one character each under UTF-8 and two and three under a cp1252
#: default, so a diff carrying them differs in LENGTH between the two readings and not only in what
#: it spells. The ellipsis is the character that put this in front of the gate: it is the one the
#: pre-commit hook truncates its own prompt with.
ACCENTED = "café …"

#: The live reference tree, or None on a checkout that has never rendered one.
REFERENCE_TREE = next((root for root in [provenance.reference_root(REPO)]
                       if root is not None and root.is_dir()), None)

#: The keywords ``capture.normalize`` - the only path that writes an artifact's provenance - hands
#: ``gather``. Stated once and asserted against both sides below, because a keyword the signature
#: accepts and the capture never passes is exactly how three fields reached 24 promoted baselines
#: absent while a test that passed them by hand stayed green.
CAPTURE_KEYWORDS = ("producer", "mode", "runs", "flags", "counts", "root", "reference_tree")


def as_capture_gathers(**overrides) -> dict:
    """A record gathered the way ``capture.normalize`` gathers one.

    :param overrides: what to change about that call shape
    :return: the gathered record
    """
    call = {"producer": "entityParityVanilla", "mode": None, "runs": 2, "flags": (),
            "counts": None, "root": None, "reference_tree": None}
    call.update(overrides)
    return provenance.gather("sweep.entity", REPO, **call)


class TheCallShape(unittest.TestCase):

    def test_gather_accepts_every_keyword_the_capture_passes(self):
        parameters = inspect.signature(provenance.gather).parameters
        for name in CAPTURE_KEYWORDS:
            self.assertIn(name, parameters, name)

    def test_the_capture_passes_every_keyword_this_suite_gathers_with(self):
        source = inspect.getsource(capture.normalize)
        for name in CAPTURE_KEYWORDS:
            self.assertIn(f"{name}=", source, name)


class Registry(unittest.TestCase):
    """The key set, asserted against the registry rather than against a list of the suite's own.

    What this replaces asserted nine of the registered keys, on a call shape no production caller
    uses - so it was blind to `mode`, `reference_manifest_digest` and `wall_time_ms` being absent
    from every real capture, which is how all three shipped unnoticed.
    """

    def test_a_capture_carries_every_unconditional_key_and_nothing_unregistered(self):
        record = as_capture_gathers()
        self.assertLessEqual(set(record), set(provenance.KEYS),
                             "keys gather writes that the registry does not declare")
        for name, always in provenance.KEYS.items():
            if always:
                self.assertIn(name, record, name)

    def test_the_always_column_is_exactly_what_a_bare_call_writes(self):
        """The third direction, and the one the two around it cannot see.

        Both of those read ``always`` as a strengthening: one asserts every unconditional key is
        present, the other every conditional key is present on a record supplying every argument. A
        key demoted True -> False therefore moves from the first check to the second and both stay
        green, while a later change stopping ``gather`` from writing it unconditionally becomes
        invisible - the shape the registry exists to close. So the column is pinned against the
        narrowest call there is, on a repo carrying none of the inputs a conditional key comes off,
        where the answer is an equality rather than a containment.
        """
        with contextlib.redirect_stderr(io.StringIO()):
            bare = provenance.gather("sweep.entity", Path(tempfile.mkdtemp()))
        self.assertEqual(set(bare),
                         {name for name, always in provenance.KEYS.items() if always},
                         "the keys a call supplying nothing writes are the registry's unconditional "
                         "ones, in both directions")

    def test_every_conditional_key_is_one_something_can_produce(self):
        """A registered key nothing can write would pass the assertion above by staying absent."""
        tree = Path(tempfile.mkdtemp()) / "references"
        write_text(tree / "blocks" / "stone" / "vanilla.png", "x")
        record = as_capture_gathers(mode="EVERY", flags=("asset.depth.range=1000",),
                                    counts={"rows": 1}, root="cache/visual",
                                    reference_tree=tree)
        # `reason`, `parity_class` and `wall_time_ms` are gather's own arguments that no capture
        # supplies - the promotion writes the first two onto the record afterwards - so they are
        # exercised on their own call rather than smuggled into the capture's shape.
        record.update(provenance.gather("sweep.entity", REPO, reason="r", parity_class="moving",
                                        wall_time_ms=1))
        # Both directions on the widest record there is. The subset is what catches a key gather
        # writes and the registry does not declare, and it can only be asked of a record that
        # supplies every argument - a bare one omits the conditional keys and passes either way.
        self.assertLessEqual(set(record), set(provenance.KEYS),
                             "keys gather writes that the registry does not declare")
        wanted = {name for name, always in provenance.KEYS.items() if not always}
        root = provenance.reference_root(REPO)
        if root is None or not root.is_dir():
            # The one conditional key that comes off the working tree rather than an argument.
            wanted.discard("reference_counts")
        for name in sorted(wanted):
            self.assertIn(name, record, name)


class Fields(unittest.TestCase):

    def setUp(self):
        self.record = as_capture_gathers(mode="FULL", flags=("asset.depth.range=1000",))

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

    def test_an_unnameable_reference_tree_says_so_on_stderr(self):
        """The arm for a repo whose harness version cannot be read, which names no tree at all."""
        with contextlib.redirect_stderr(io.StringIO()) as err:
            provenance.reference_counts(self.empty)
        self.assertIn("no harness minecraft_version", err.getvalue())

    def test_a_reference_tree_that_is_not_there_says_so_on_stderr(self):
        """An MC bump's own shape: the version reads, and the tree it names does not exist.

        This is the arm that matters, and the sibling above cannot reach it - a repo with no harness
        properties stops one step earlier. A bump moves the derived path to a directory nothing has
        rendered yet, and a silent {} there and a tree of zero references read the same downstream.
        """
        write_text(self.empty / provenance.HARNESS_PROPERTIES, "minecraft_version=27.1.0\n")
        with contextlib.redirect_stderr(io.StringIO()) as err:
            counts = provenance.reference_counts(self.empty)
        self.assertEqual(counts, {})
        self.assertIn(f"no reference tree at {self.empty / 'cache/asset-renderer/vanilla/27.1/references'}",
                      err.getvalue())
        self.assertIn("reference_counts", err.getvalue())

    def test_a_named_reference_tree_that_is_absent_says_so_on_stderr(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            provenance.reference_manifest_digest(self.empty / "references")
        self.assertIn("reference_manifest_digest", err.getvalue())

    def test_naming_no_reference_tree_at_all_is_silent(self):
        """Nothing was asked for, so there is nothing to report as not done."""
        with contextlib.redirect_stderr(io.StringIO()) as err:
            provenance.reference_manifest_digest(None)
        self.assertEqual(err.getvalue(), "")

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

    def _edited_tree(self) -> Path:
        """A one-commit repo carrying a non-ASCII uncommitted edit, which is what makes a codec show.

        :return: the repo's path
        """
        edited = Path(tempfile.mkdtemp())
        subprocess.run(["git", "init", "-q"], cwd=edited, check=True)
        write_text(edited / "seed.txt", "plain\n")
        subprocess.run(["git", "add", "-A"], cwd=edited, check=True)
        subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "s"],
                       cwd=edited, check=True)
        write_text(edited / "seed.txt", f"{ACCENTED}\n")
        return edited

    def test_git_output_is_decoded_as_utf8_and_not_through_whatever_codec_is_ambient(self):
        """WHICH codec, which the stability case below cannot see.

        Two readings that both ignore the environment agree with each other whatever they decode
        to, so a stable wrong codec satisfies that case exactly as well as the right one: cp1252
        written in here answers one digest per tree just as faithfully. What separates them is what
        the bytes decode TO. The text this repository tracks is UTF-8 and git hands a diff over as
        bytes, so any other reading digests a string that exists nowhere but in the reader.
        """
        diff = provenance._git(self._edited_tree(), "diff", "HEAD")
        self.assertIn(ACCENTED, diff)

    def test_a_value_carries_none_of_the_newline_git_ends_its_output_with(self):
        """Nothing downstream strips one, so a stray newline would ride into every stored value.

        `asset_sha` is written into a capture's provenance as it comes back and compared for equality
        against another capture's, and a promotion refuses on that comparison. Asserted by shape
        rather than against a second call, which would carry the same newline and agree.
        """
        sha = provenance._git(self._edited_tree(), "rev-parse", "HEAD")
        self.assertRegex(sha, r"\A[0-9a-f]{40}\Z")

    def test_it_is_stable_across_the_encoding_the_interpreter_was_started_with(self):
        """One tree, one digest, whatever `PYTHONUTF8` was when the process started.

        The digest is over what a diff DECODES to, so reading git's bytes through the ambient locale
        makes it a function of the caller's environment: a non-ASCII byte is one character under
        UTF-8 mode and two or three under a cp1252 default. The pre-commit hook starts its child
        with that variable set and nothing else does, so the two never agreed on a tree whose diff
        carried an accented letter - and 'already gated' is decided by comparing exactly these.
        """
        edited = self._edited_tree()
        answers = set()
        for mode in ("0", "1"):
            done = subprocess.run(
                [sys.executable, "-c",
                 "import sys; from pathlib import Path; sys.path.insert(0, sys.argv[1]); "
                 "from parity import provenance; print(provenance.dirty_digest(Path(sys.argv[2])))",
                 str(TOOLKIT), str(edited)],
                capture_output=True, text=True, env={**os.environ, "PYTHONUTF8": mode})
            self.assertEqual(done.returncode, 0, done.stderr)
            answers.add(done.stdout.strip())
        self.assertEqual(len(answers), 1, answers)


class ReferenceManifestDigest(unittest.TestCase):
    """A row identifies a reference set in one field instead of carrying 2311 lines."""

    def setUp(self):
        self.tree = Path(tempfile.mkdtemp()) / "references"
        write_text(self.tree / "blocks" / "stone" / "vanilla.png", "one")
        write_text(self.tree / "entities" / "cow" / "vanilla.png", "two")

    def test_absent_is_none_not_a_throw(self):
        self.assertIsNone(provenance.reference_manifest_digest(self.tree / "nope"))

    def test_it_is_the_identity_of_the_stored_manifest_of_that_tree(self):
        """The tie the field exists to make, asserted against the artifact rather than restated.

        A capture of ``manifest.references`` over the same tree writes a payload whose content -
        provenance out - is what this digests. So a sweep's stamped value and the store's copy of
        that row name the same reference set, and a reader can check the two against each other
        without the sweep's own capture having had to hash the tree into the store.
        """
        stored = capture.normalize(provenance.REFERENCES_ARTIFACT, self.tree,
                                   Path(tempfile.mkdtemp()), REPO)
        payload = read_json(stored)
        content = {name: value for name, value in payload.items() if name != "provenance"}
        self.assertEqual(provenance.reference_manifest_digest(self.tree),
                         sha256_text(canonical_json(content)))

    def test_it_does_not_move_when_only_the_capture_does(self):
        """The manifest FILE carries a timestamp, so its own sha names the capture and not the set.

        Two sweeps measured against one tree have to name it with one value or the field ties
        nothing, which is what reading the digest off the captured file gave: the timestamp alone
        moves that sha on every capture, over a reference set nobody touched.
        """
        stored = capture.normalize(provenance.REFERENCES_ARTIFACT, self.tree,
                                   Path(tempfile.mkdtemp()), REPO)
        payload = read_json(stored)
        self.assertIn("timestamp", payload["provenance"])
        before, derived = sha256_file(stored), provenance.reference_manifest_digest(self.tree)
        payload["provenance"]["timestamp"] = "1999-01-01T00:00:00Z"
        write_json(stored, payload)
        self.assertNotEqual(sha256_file(stored), before)
        self.assertEqual(provenance.reference_manifest_digest(self.tree), derived)

    def test_a_changed_reference_moves_it(self):
        before = provenance.reference_manifest_digest(self.tree)
        write_text(self.tree / "blocks" / "stone" / "vanilla.png", "edited")
        self.assertNotEqual(provenance.reference_manifest_digest(self.tree), before)

    def test_it_does_not_depend_on_where_the_tree_sits(self):
        """The paths in the manifest are relative to the tree, so a redirected root is the same set."""
        elsewhere = Path(tempfile.mkdtemp()) / "somewhere" / "references"
        shutil.copytree(self.tree, elsewhere)
        self.assertEqual(provenance.reference_manifest_digest(elsewhere),
                         provenance.reference_manifest_digest(self.tree))


class ReferenceCounts(unittest.TestCase):

    def test_the_root_is_derived_from_the_harness_version_and_not_written_down(self):
        """The build derives the same path from the same property; a literal here goes stale on an
        MC bump by naming a directory nothing creates, and an absent tree counts nothing."""
        self.assertEqual(provenance.reference_root(REPO),
                         REPO / "cache/asset-renderer/vanilla/26.1/references")

    def test_an_unreadable_harness_version_names_no_root_rather_than_a_wrong_one(self):
        self.assertIsNone(provenance.reference_root(Path(tempfile.mkdtemp())))

    @unittest.skipUnless(REFERENCE_TREE is not None, "reference tree absent")
    def test_the_live_tree_counts_per_subtree(self):
        counts = provenance.reference_counts(REPO)
        self.assertEqual(counts.get("entities"), 402)
        self.assertEqual(counts.get("armor"), 7)
        self.assertEqual(counts.get("players"), 2)
        self.assertEqual(sum(counts.values()), 2311)


if __name__ == "__main__":
    unittest.main()
