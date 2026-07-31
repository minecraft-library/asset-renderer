"""The three column shapes, and the delta found by name rather than by position."""

from __future__ import annotations

import unittest
from pathlib import Path

from parity import sweep
from parity.norm import MissingInput

DATA = Path(__file__).resolve().parent / "data"


class ColumnShapes(unittest.TestCase):

    def test_nine_column_shape(self):
        table = sweep.read_table(DATA / "sweep-entity-a.tsv")
        self.assertEqual(table.key_field, "entity_id")
        self.assertEqual(len(table.rows), 12)
        self.assertEqual(table.rows[0].values[sweep.DELTA], "0.0000")

    def test_five_column_shape_keyed_subject(self):
        table = sweep.read_table(DATA / "sweep-armor.tsv")
        self.assertEqual(table.key_field, "subject")
        self.assertEqual(table.sweep, "armor")

    def test_six_column_glint_shape_has_the_delta_in_column_three(self):
        """The single sharpest regression test in the reader.

        `awk '{s+=$2}'` returns 30 x 11 = 330.0000 on this shape, and 67 recorded uses never caught
        it, because column 2 is `frames`.
        """
        table = sweep.read_table(DATA / "sweep-glint.tsv")
        self.assertEqual(table.columns[1], "frames")
        self.assertEqual(table.columns[2], sweep.DELTA)
        self.assertAlmostEqual(sweep.total(table), 131.7813, places=4)
        by_position = sum(float(row.values["frames"]) for row in table.rows)
        self.assertEqual(by_position, 90.0)
        self.assertNotAlmostEqual(sweep.total(table), by_position, places=4)

    def test_mixed_line_endings_parse(self):
        """LF header, CRLF rows - what every one of the six writers produces today."""
        raw = (DATA / "sweep-entity-a.tsv").read_bytes()
        self.assertIn(b"\r\n", raw)
        table = sweep.read_table(DATA / "sweep-entity-a.tsv")
        for row in table.rows:
            for value in row.values.values():
                self.assertNotIn("\r", value)

    def test_a_table_without_the_delta_column_is_missing_input(self):
        bad = Path(self.enterContext(__import__("tempfile").TemporaryDirectory())) / "x.tsv"
        bad.write_bytes(b"a\tb\n1\t2\n")
        with self.assertRaises(MissingInput):
            sweep.read_table(bad)


class Sentinel(unittest.TestCase):
    """I-27: a crashed subject is not a bad subject."""

    def setUp(self):
        self.table = sweep.read_table(DATA / "sweep-failed.tsv")

    def test_the_sentinel_row_is_status_failed(self):
        statuses = {row.key: row.status for row in self.table.rows}
        self.assertEqual(statuses["minecraft__crashed"], sweep.FAILED)
        self.assertEqual(statuses["minecraft__ok"], sweep.OK)

    def test_it_is_excluded_from_the_sum_rather_than_making_it_inf(self):
        self.assertEqual(sweep.total(self.table), 0.5)

    def test_it_is_counted_separately(self):
        self.assertEqual(sweep.buckets(self.table)["failed"], 1)
        self.assertEqual(sweep.buckets(self.table)["total"], 2)

    def test_it_is_not_counted_in_any_bucket(self):
        counts = sweep.buckets(self.table)
        self.assertEqual(counts["<1.00"], 1)  # the ok row only


class Buckets(unittest.TestCase):

    def test_cumulative_not_bins(self):
        table = sweep.read_table(DATA / "sweep-entity-a.tsv")
        counts = sweep.buckets(table)
        for lower, upper in zip(sweep.BUCKET_EDGES, sweep.BUCKET_EDGES[1:]):
            self.assertLessEqual(counts[f"<{lower:.2f}"], counts[f"<{upper:.2f}"])

    def test_one_label_spelling(self):
        """Retires the entity-vs-block divergence (`<0.5`/`<1` against `<0.50`/`<1.0`)."""
        counts = sweep.buckets(sweep.read_table(DATA / "sweep-entity-a.tsv"))
        self.assertEqual([key for key in counts if key.startswith("<")],
                         ["<0.25", "<0.50", "<0.75", "<1.00"])


class Sums(unittest.TestCase):

    def test_order_independent(self):
        """fsum is exactly rounded, so a re-sorted table sums bit-identically."""
        table = sweep.read_table(DATA / "sweep-entity-a.tsv")
        forward = sweep.total(table)
        table.rows.reverse()
        self.assertEqual(forward, sweep.total(table))


class CanonicalKeys(unittest.TestCase):

    def test_every_sweep_joins_on_the_reference_stem(self):
        """Two sweeps' rows can be joined to each other, which nothing in the repo can do today."""
        glint = sweep.read_table(DATA / "sweep-glint.tsv")
        keys = [sweep.canonical_key(row, "glint") for row in glint.rows]
        self.assertIn("minecraft__nether_star", keys)
        entity = sweep.read_table(DATA / "sweep-entity-a.tsv")
        self.assertIn("minecraft__cow", [sweep.canonical_key(r, "entity") for r in entity.rows])

    def test_player_scopes_are_left_alone(self):
        row = sweep.Row(key="full", values={sweep.DELTA: "1.0"})
        self.assertEqual(sweep.canonical_key(row, "player"), "full")


class Discovery(unittest.TestCase):

    def test_finds_a_capture_layout(self):
        found = sweep.discover(DATA)
        self.assertIn("glint", found)
        self.assertIn("armor", found)

    def test_a_single_file_operand(self):
        found = sweep.discover(DATA / "sweep-glint.tsv")
        self.assertEqual(list(found), ["glint"])

    def test_an_empty_directory_is_missing_input(self):
        import tempfile
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(MissingInput):
                sweep.discover(Path(empty))


if __name__ == "__main__":
    unittest.main()
