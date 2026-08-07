"""The generated sha256sum view, and a verify that names what moved."""

from __future__ import annotations

import shutil
import tempfile
import unittest
from pathlib import Path

from parity import manifest
from parity.norm import ComparisonFailed, MissingInput, write_text


class ExportedView(unittest.TestCase):
    """The one line form the package writes, and the one nothing reads back."""

    def test_the_written_line_is_hex_space_star_path(self):
        entries = [manifest.Entry("blocks/one.png", "a" * 64)]
        text = manifest.export_text(manifest.Manifest("m", "root", entries))
        self.assertEqual(text, f"{'a' * 64} *blocks/one.png")

    def test_export_sorts_by_path_never_by_digest(self):
        """Sorting by digest means one changed section reorders every line."""
        entries = [manifest.Entry("z.png", "0" * 64), manifest.Entry("a.png", "f" * 64)]
        text = manifest.export_text(manifest.Manifest("m", "root", entries))
        self.assertLess(text.index("a.png"), text.index("z.png"))


class Build(unittest.TestCase):
    """The default image glob and the path form.

    These name `manifest.fluid` rather than `manifest.visual` because their subject is the glob, and
    `manifest.visual` declares member sub-directories a bare fixture tree does not have.
    """

    def setUp(self):
        self.tree = Path(tempfile.mkdtemp())
        for name in ("a.png", "b.gif", "c.webp", "d.txt"):
            (self.tree / name).write_bytes(name.encode())
        (self.tree / "sub").mkdir()
        (self.tree / "sub" / "e.png").write_bytes(b"e")

    def test_default_glob_is_images_and_recurses(self):
        built = manifest.build("manifest.fluid", self.tree)
        self.assertEqual([entry.path for entry in built.entries],
                         ["a.png", "b.gif", "c.webp", "sub/e.png"])

    def test_reference_glob_admits_json(self):
        (self.tree / "atlas_uv.json").write_bytes(b"{}")
        built = manifest.build("manifest.references", self.tree)
        self.assertIn("atlas_uv.json", [entry.path for entry in built.entries])

    def test_paths_are_posix_relative_to_the_declared_root(self):
        built = manifest.build("manifest.fluid", self.tree)
        self.assertTrue(all("\\" not in entry.path for entry in built.entries))
        self.assertEqual(manifest.to_artifact(built)["provenance"]["root"],
                         built.root)

    def test_stale_sidecars_are_excluded_without_being_asked(self):
        (self.tree / "x.variant").write_bytes(b"x")
        (self.tree / "y.vertices.tsv").write_bytes(b"y")
        built = manifest.build("manifest.references", self.tree, globs=("*",))
        paths = [entry.path for entry in built.entries]
        self.assertNotIn("x.variant", paths)
        self.assertNotIn("y.vertices.tsv", paths)

    def test_the_stored_form_keys_on_path(self):
        payload = manifest.to_artifact(manifest.build("manifest.fluid", self.tree))
        self.assertEqual(payload["key"], "path")
        self.assertEqual(payload["kind"], "manifest")

    def test_round_trips_through_the_stored_form(self):
        built = manifest.build("manifest.fluid", self.tree)
        self.assertEqual(manifest.from_artifact(manifest.to_artifact(built)).by_path(),
                         built.by_path())


class Verify(unittest.TestCase):

    def _manifest(self, entries):
        return manifest.Manifest("manifest.visual", "root",
                                 [manifest.Entry(path, digest) for path, digest in entries])

    def test_names_added_missing_and_differing_separately(self):
        base = self._manifest([("a.png", "1" * 64), ("b.png", "2" * 64), ("c.png", "3" * 64)])
        current = self._manifest([("a.png", "1" * 64), ("b.png", "9" * 64), ("d.png", "4" * 64)])
        verdict = manifest.compare(base, current)
        self.assertEqual(verdict.added, ["d.png"])
        self.assertEqual(verdict.missing, ["c.png"])
        self.assertEqual(verdict.differing, ["b.png"])
        self.assertFalse(verdict.clean())

    def test_an_identical_tree_is_clean(self):
        base = self._manifest([("a.png", "1" * 64)])
        self.assertTrue(manifest.compare(base, base).clean())
        manifest.raise_on(manifest.compare(base, base))

    def test_any_of_the_three_fails_the_gate(self):
        base = self._manifest([("a.png", "1" * 64)])
        current = self._manifest([("a.png", "2" * 64)])
        with self.assertRaises(ComparisonFailed):
            manifest.raise_on(manifest.compare(base, current))


class MemberSubtrees(unittest.TestCase):
    """An artifact whose source is a shared parent takes only its declared members.

    cache/visual holds thousands of images and only manifest.visual's members are its own. The rest
    include the per-subject diff panels, which a sweep run rewrites - so admitting them would make the
    artifact move on every sweep and gate nothing.

    The fixture writes one file per DECLARED member rather than a typed list of names: a third copy
    of the membership is a third thing to keep in step, and one that disagreed would leave this suite
    green over the very drift it is here to catch.
    """

    MEMBERS = manifest.SUBTREES["manifest.visual"]

    #: What the fixture writes under the shared parent that is NOT a member, each for a different
    #: reason: a per-subject diff panel a sweep rewrites, a sub-tree with a manifest of its own, and
    #: a directory an A/B left behind.
    OUTSIDERS = ("entity-parity-vanilla/minecraft__cow/diff.png", "player-render/sheet.png",
                 "p8-before/scratch.png")

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        for name in self.MEMBERS:
            write_text(self.root / name / "a.png", name)
        for path in self.OUTSIDERS:
            write_text(self.root / path, path)

    def test_only_the_declared_members_are_hashed(self):
        built = manifest.build("manifest.visual", self.root)
        self.assertEqual(len(built.entries), len(self.MEMBERS))
        self.assertTrue(all("/" in entry.path for entry in built.entries))

    def test_a_diff_panel_tree_is_not_a_member(self):
        panel = "entity-parity-vanilla/minecraft__cow/diff.png"
        # Named here and written by setUp from the tuple, so the two can part company: a path the
        # fixture never wrote is absent from every manifest ever built, which would leave this check
        # vacuous - and the allowlist it is here to prove would read as a denylist just as green.
        self.assertIn(panel, self.OUTSIDERS)
        self.assertNotIn(panel, manifest.build("manifest.visual", self.root).by_path())

    def test_a_subtree_with_its_own_manifest_is_not_a_member(self):
        sheet = "player-render/sheet.png"
        self.assertIn(sheet, self.OUTSIDERS)  # or the check below is vacuous, as above
        self.assertNotIn(sheet, manifest.build("manifest.visual", self.root).by_path())

    def test_scratch_left_behind_by_an_ab_is_not_a_member(self):
        """The whole reason it is an allowlist: nobody has to remember to exclude this."""
        scratch = "p8-before/scratch.png"
        self.assertIn(scratch, self.OUTSIDERS)  # or the check below is vacuous, as above
        self.assertNotIn(scratch, manifest.build("manifest.visual", self.root).by_path())

    def test_a_missing_member_is_a_failure_rather_than_a_smaller_manifest(self):
        shutil.rmtree(self.root / "menu-render")
        with self.assertRaises(MissingInput) as caught:
            manifest.build("manifest.visual", self.root)
        self.assertIn("menu-render", str(caught.exception))

    def test_an_artifact_with_no_declared_members_walks_the_whole_tree(self):
        """Everything the fixture wrote, members and outsiders alike - the contrast with the above."""
        whole = len(manifest.build("manifest.fluid", self.root).entries)
        self.assertEqual(whole, len(self.MEMBERS) + len(self.OUTSIDERS))
        # Both counts are derived from the same tuples, so the sum alone holds however few non-members
        # the fixture writes - including none, which would leave this check vacuous by agreeing with
        # the member-only build. Strictly greater is the contrast the test is named for, and it is the
        # one form that says the member list SUBTRACTS rather than merely being read.
        self.assertGreater(whole, len(manifest.build("manifest.visual", self.root).entries))


class NonMembers(unittest.TestCase):
    """A producer can write more than its artifact is defined as."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        for group in ("core-matrix", "toggles", "trims"):
            write_text(self.root / group / "cell.png", group)
            write_text(self.root / f"{group}.png", f"{group} composite")
        write_text(self.root / "account" / "cell.png", "a live account's skin")
        write_text(self.root / "account.png", "account composite")

    def test_the_network_only_group_is_not_a_member(self):
        """It is 8 cells and a composite of the 113-against-104 difference, and it is not offline."""
        paths = manifest.build("manifest.player-sheets", self.root).by_path()
        self.assertEqual(len(paths), 6)
        self.assertNotIn("account/cell.png", paths)
        self.assertNotIn("account.png", paths)

    def test_the_offline_groups_are_all_kept(self):
        paths = manifest.build("manifest.player-sheets", self.root).by_path()
        self.assertIn("core-matrix/cell.png", paths)
        self.assertIn("trims.png", paths)


class RawRenderPair(unittest.TestCase):
    """The one artifact whose membership is decided by file NAME rather than by extension.

    A sweep that rescales both sides before diffing writes six PNGs per subject and only two of them
    are what a renderer produced. The other four are an AWT resample and two things built from it, so
    digesting them would fold a JDK-owned computation into the artifact and a mover would stop naming
    the renderer.
    """

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        for member, subjects in (("player-parity-vanilla", ("full", "skull")),
                                 ("armor-parity-vanilla", ("minecraft__zombie_iron",))):
            for subject in subjects:
                for name in ("vanilla.png", "java.png", "aligned_vanilla.png", "aligned_java.png",
                             "diff.png", "diff_panel.png"):
                    write_text(self.root / member / subject / name, f"{member}/{subject}/{name}")
            write_text(self.root / member / "parity-report.tsv", "subject\tmean_argb_delta\n")
        # manifest.visual's own population, which this artifact must not reach into and vice versa.
        # Every one of them, because that artifact refuses a member it cannot find - which is the
        # property the disjointness check below would otherwise trip over rather than test.
        for member in manifest.SUBTREES["manifest.visual"]:
            write_text(self.root / member / "java.png", "a different artifact's member")

    def test_both_members_are_hashed_and_only_the_raw_pair(self):
        paths = manifest.build("manifest.player-raw", self.root).by_path()
        self.assertEqual(sorted(paths), [
            "armor-parity-vanilla/minecraft__zombie_iron/java.png",
            "armor-parity-vanilla/minecraft__zombie_iron/vanilla.png",
            "player-parity-vanilla/full/java.png",
            "player-parity-vanilla/full/vanilla.png",
            "player-parity-vanilla/skull/java.png",
            "player-parity-vanilla/skull/vanilla.png",
        ])

    def test_the_rescaled_pair_is_not_a_member(self):
        """It is the LOOK gauge's operand, and alignToBox resamples both sides to produce it."""
        paths = manifest.build("manifest.player-raw", self.root).by_path()
        self.assertNotIn("player-parity-vanilla/full/aligned_vanilla.png", paths)
        self.assertNotIn("player-parity-vanilla/full/aligned_java.png", paths)

    def test_neither_diagnostic_is_a_member(self):
        paths = manifest.build("manifest.player-raw", self.root).by_path()
        self.assertNotIn("player-parity-vanilla/full/diff.png", paths)
        self.assertNotIn("player-parity-vanilla/full/diff_panel.png", paths)

    def test_the_other_visual_artifacts_population_is_not_reached(self):
        """The two share cache/visual as a source and their member sets are disjoint."""
        self.assertNotIn("block-render-3d/java.png",
                         manifest.build("manifest.player-raw", self.root).by_path())
        self.assertNotIn("player-parity-vanilla/full/java.png",
                         manifest.build("manifest.visual", self.root).by_path())

    def test_a_missing_member_is_a_failure_rather_than_half_the_pairs(self):
        """One sweep run and not the other is exactly the case the aggregator producer prevents."""
        shutil.rmtree(self.root / "armor-parity-vanilla")
        with self.assertRaises(MissingInput) as caught:
            manifest.build("manifest.player-raw", self.root)
        self.assertIn("armor-parity-vanilla", str(caught.exception))


class DiagnosticsLogProjection(unittest.TestCase):
    """A byte-identical table is not the same claim as an unchanged run.

    The entity flow once moved an INFO line from position 9 to 6 with every emitted table byte for
    byte identical, which is the move this projection exists to catch.
    """

    def setUp(self):
        self.logs = Path(tempfile.mkdtemp())

    def _log(self, flow, text):
        write_text(self.logs / f"producer-{flow}.log", text)

    def test_the_console_form_is_kept_verbatim(self):
        self._log("blockItems", "[INFO] blockItems - walked 3 aliases\n")
        self.assertEqual(manifest.normalize_log(self.logs / "producer-blockItems.log"),
                         "[INFO] blockItems - walked 3 aliases")

    def test_the_file_form_normalizes_onto_the_console_form(self):
        """FILE timestamps every line, so the raw log can never be byte-compared."""
        self._log("a", "[INFO] flow/x - m\n")
        self._log("b", "2026-07-31T09:12:13.456789Z [INFO] flow/x - m\n")
        self.assertEqual(manifest.normalize_log(self.logs / "producer-a.log"),
                         manifest.normalize_log(self.logs / "producer-b.log"))

    def test_a_non_diagnostic_line_is_dropped(self):
        self._log("a", "Downloading client jar...\n[INFO] flow - m\nDone in 4s\n")
        self.assertEqual(manifest.normalize_log(self.logs / "producer-a.log"), "[INFO] flow - m")

    def test_reordering_two_lines_moves_the_digest(self):
        """The whole point: same lines, same table, different run."""
        self._log("a", "[INFO] flow - one\n[INFO] flow - two\n")
        self._log("b", "[INFO] flow - two\n[INFO] flow - one\n")
        digests = manifest.log_digests(self.logs, ["a", "b"])
        self.assertNotEqual(digests["a"], digests["b"])

    def test_it_is_keyed_by_flow_and_ordered_by_name(self):
        for flow in ("zeta", "alpha"):
            self._log(flow, f"[INFO] {flow} - m\n")
        self.assertEqual(list(manifest.log_digests(self.logs, ["zeta", "alpha"])), ["alpha", "zeta"])

    def test_a_flow_with_no_log_is_a_failure_rather_than_seven_of_eight(self):
        """A set that hashes cleanly minus the missing member is the false green."""
        self._log("a", "[INFO] flow - m\n")
        with self.assertRaises(MissingInput) as caught:
            manifest.log_digests(self.logs, ["a", "b"])
        self.assertIn("'b'", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
