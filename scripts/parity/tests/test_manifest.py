"""The three line grammars collapsing to one, and a verify that names what moved."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import manifest
from parity.norm import ComparisonFailed, MissingInput, write_text

DATA = Path(__file__).resolve().parent / "data"


class LineGrammars(unittest.TestCase):
    """A naive diff between two of these reports every line changed; here they read identically."""

    def test_all_three_read_to_the_same_entries(self):
        parsed = [manifest.parse_lines(DATA / f"manifest-{letter}.sha256") for letter in "abc"]
        first = parsed[0].by_path()
        self.assertEqual(first, {"blocks/one.png": "a" * 64, "blocks/two.png": "b" * 64})
        for other in parsed[1:]:
            self.assertEqual(other.by_path(), first)

    def test_only_grammar_a_is_written(self):
        parsed = manifest.parse_lines(DATA / "manifest-b.sha256")
        text = manifest.export_text(parsed)
        self.assertEqual(text.splitlines()[0], f"{'a' * 64} *blocks/one.png")
        self.assertNotIn("./", text)

    def test_export_sorts_by_path_never_by_digest(self):
        """Sorting by digest means one changed section reorders every line."""
        entries = [manifest.Entry("z.png", "0" * 64), manifest.Entry("a.png", "f" * 64)]
        text = manifest.export_text(manifest.Manifest("m", "root", entries))
        self.assertLess(text.index("a.png"), text.index("z.png"))

    def test_a_non_manifest_line_is_missing_input_not_a_silent_skip(self):
        from parity.norm import MissingInput
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "bad.sha256"
            write_text(bad, "not a manifest line")
            with self.assertRaises(MissingInput):
                manifest.parse_lines(bad)


class Build(unittest.TestCase):

    def setUp(self):
        self.tree = Path(tempfile.mkdtemp())
        for name in ("a.png", "b.gif", "c.webp", "d.txt"):
            (self.tree / name).write_bytes(name.encode())
        (self.tree / "sub").mkdir()
        (self.tree / "sub" / "e.png").write_bytes(b"e")

    def test_default_glob_is_images_and_recurses(self):
        built = manifest.build("manifest.visual", self.tree)
        self.assertEqual([entry.path for entry in built.entries],
                         ["a.png", "b.gif", "c.webp", "sub/e.png"])

    def test_reference_glob_admits_json(self):
        (self.tree / "atlas_uv.json").write_bytes(b"{}")
        built = manifest.build("manifest.references", self.tree)
        self.assertIn("atlas_uv.json", [entry.path for entry in built.entries])

    def test_paths_are_posix_relative_to_the_declared_root(self):
        built = manifest.build("manifest.visual", self.tree)
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
        payload = manifest.to_artifact(manifest.build("manifest.visual", self.tree))
        self.assertEqual(payload["key"], "path")
        self.assertEqual(payload["kind"], "manifest")

    def test_round_trips_through_the_stored_form(self):
        built = manifest.build("manifest.visual", self.tree)
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
        """I-20: a set that hashes cleanly minus the missing member is the false green."""
        self._log("a", "[INFO] flow - m\n")
        with self.assertRaises(MissingInput) as caught:
            manifest.log_digests(self.logs, ["a", "b"])
        self.assertIn("'b'", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
