"""Per-subject render indexing, and the annotation that must not join on the wrong field."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import render
from parity.norm import ComparisonFailed, MissingInput


def tree(root: Path, subjects: dict[str, tuple[str, bytes]], table: bytes | None = None) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    for subject, (name, payload) in subjects.items():
        (root / subject).mkdir(parents=True, exist_ok=True)
        (root / subject / name).write_bytes(payload)
    if table is not None:
        (root / "parity-report.tsv").write_bytes(table)
    return root


class Indexing(unittest.TestCase):

    def setUp(self):
        self.base = Path(tempfile.mkdtemp())

    def test_png_is_preferred_then_gif(self):
        """The gif arm is what covers the animated glint sweep with the same walk."""
        root = tree(self.base / "t", {"a": ("java.png", b"1"), "b": ("java.gif", b"2")})
        found = render.index(root)
        self.assertEqual(sorted(found), ["a", "b"])
        self.assertEqual(found["b"].name, "java.gif")

    def test_a_subject_with_neither_is_skipped(self):
        root = tree(self.base / "u", {"a": ("java.png", b"1")})
        (root / "empty").mkdir()
        self.assertEqual(list(render.index(root)), ["a"])

    def test_a_missing_tree_is_missing_input(self):
        with self.assertRaises(MissingInput):
            render.index(self.base / "nope")


class Diff(unittest.TestCase):

    def setUp(self):
        self.base = Path(tempfile.mkdtemp())
        self.before = tree(self.base / "before", {
            "a": ("java.png", b"same"), "b": ("java.png", b"old"), "c": ("java.png", b"gone")})
        self.after = tree(self.base / "after", {
            "a": ("java.png", b"same"), "b": ("java.png", b"new"), "d": ("java.png", b"fresh")})

    def test_the_symmetric_difference(self):
        verdict = render.diff(self.before, self.after)
        self.assertEqual(verdict.identical, ["a"])
        self.assertEqual([row["subject"] for row in verdict.moved], ["b"])
        self.assertEqual(verdict.dropped, ["c"])
        self.assertEqual(verdict.added, ["d"])
        self.assertFalse(verdict.clean())

    def test_any_mover_drop_or_add_fails_the_gate(self):
        with self.assertRaises(ComparisonFailed):
            render.raise_on(render.diff(self.before, self.after))

    def test_two_identical_trees_are_clean(self):
        verdict = render.diff(self.before, self.before)
        self.assertTrue(verdict.clean())
        render.raise_on(verdict)


class Annotation(unittest.TestCase):
    """Both columns are resolved by header name, so a renamed key column still annotates."""

    TABLE = (b"entity_id\tmean_argb_delta\tdiffering_pixels\n"
             b"b\t0.5000\t5\r\n")
    RENAMED = (b"subject\tdiffering_pixels\tmean_argb_delta\n"
               b"b\t5\t0.7000\r\n")

    def setUp(self):
        self.base = Path(tempfile.mkdtemp())

    def test_a_mover_carries_its_metric(self):
        before = tree(self.base / "b1", {"b": ("java.png", b"old")}, self.TABLE)
        after = tree(self.base / "a1", {"b": ("java.png", b"new")}, self.RENAMED)
        moved = render.diff(before, after).moved
        self.assertEqual(moved[0]["mean_argb_delta"], ["0.5000", "0.7000"])

    def test_a_missing_table_degrades_to_hashes_only(self):
        before = tree(self.base / "b2", {"b": ("java.png", b"old")})
        after = tree(self.base / "a2", {"b": ("java.png", b"new")})
        moved = render.diff(before, after).moved
        self.assertNotIn("mean_argb_delta", moved[0])
        self.assertIn("before", moved[0])


if __name__ == "__main__":
    unittest.main()
