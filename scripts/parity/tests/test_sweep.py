"""The three column shapes, and the delta found by name rather than by position."""

from __future__ import annotations

import json
import tempfile
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
    """A crashed subject is not a bad subject."""

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


class ExplicitStatusColumn(unittest.TestCase):
    """The writers state the status outright now; the sentinel arms stay for older captures."""

    @staticmethod
    def _table(tmp: Path, header: str, *rows: str) -> sweep.Table:
        path = tmp / "parity-report.tsv"
        path.write_text("\n".join((header,) + rows) + "\n", encoding="utf-8")
        return sweep.read_table(path, "entity")

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def test_an_explicit_failed_is_read_without_any_magic_value(self):
        """The reshaped row carries neither `Infinity` nor `-1`, so only the column can say so."""
        table = self._table(self.tmp, "subject\tmean_argb_delta\tstatus\tdiffering_pixels",
                            "minecraft__ok\t0.5000\tok\t7",
                            "minecraft__crashed\t\tfailed\t")
        self.assertEqual({row.key: row.status for row in table.rows},
                         {"minecraft__ok": sweep.OK, "minecraft__crashed": sweep.FAILED})
        self.assertEqual(sweep.total(table), 0.5)
        self.assertEqual(sweep.buckets(table)["failed"], 1)

    def test_the_declared_status_wins_over_the_sentinel_arms(self):
        table = self._table(self.tmp, "subject\tmean_argb_delta\tstatus\tdiffering_pixels",
                            "minecraft__x\tInfinity\tok\t-1")
        self.assertEqual(table.rows[0].status, sweep.OK)

    def test_an_unreadable_status_falls_back_rather_than_being_trusted(self):
        table = self._table(self.tmp, "subject\tmean_argb_delta\tstatus\tdiffering_pixels",
                            "minecraft__x\tInfinity\t?\t-1")
        self.assertEqual(table.rows[0].status, sweep.FAILED)


class SweepAttribution(unittest.TestCase):

    def test_subject_no_longer_names_a_sweep_because_all_six_write_it(self):
        """Answering `armor` for any of the six would apply the wrong id spelling silently."""
        home = Path(tempfile.mkdtemp())
        path = home / "somewhere.tsv"
        path.write_text("subject\tmean_argb_delta\nminecraft__cow\t0.5000\n", encoding="utf-8")
        self.assertEqual(sweep.read_table(path).sweep, "unknown")

    def test_the_four_spellings_unique_to_one_sweep_still_answer(self):
        home = Path(tempfile.mkdtemp())
        for key, name in (("scope", "player"), ("entity_id", "entity"),
                          ("block_id", "block"), ("item_id", "item")):
            path = home / f"{key}.tsv"
            path.write_text(f"{key}\tmean_argb_delta\nx\t0.5000\n", encoding="utf-8")
            self.assertEqual(sweep.read_table(path).sweep, name, key)


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


class StoredTables(unittest.TestCase):
    """The stored form read back, which is the operand both sweep commands take from a root.

    The refusals are asserted rather than read, because each is the reader being handed a file that
    is there and is not a sweep table - which is what a root holds when an id is re-homed or a
    producer's columns move, and the only shape that reaches this reader at all.
    """

    def setUp(self):
        self.tmp = Path(self.enterContext(tempfile.TemporaryDirectory()))

    def _stored(self, **payload) -> Path:
        path = self.tmp / "stored.json"
        path.write_bytes(json.dumps({"artifact": "sweep.entity", "format": 1,
                                     "kind": "sweep-table", **payload}).encode("utf-8"))
        return path

    def test_each_row_is_keyed_on_the_subject_the_stored_row_carries(self):
        """The key is what joins a row to its opposite number, and nothing else in the payload is."""
        table = sweep.read_stored_table(self._stored(key="subject", rows=[
            {"subject": "minecraft__cow", sweep.DELTA: "0.2000", "status": "ok"},
            {"subject": "minecraft__pig", sweep.DELTA: "1.5000", "status": "ok"}]), "entity")
        self.assertEqual([row.key for row in table.rows], ["minecraft__cow", "minecraft__pig"])
        self.assertNotIn("subject", table.rows[0].values)
        self.assertEqual(table.rows[0].values[sweep.DELTA], "0.2000")

    def test_the_key_column_is_the_one_the_payload_declares(self):
        """`subject` is what all six write today and it is read rather than assumed, so a stored
        artifact keyed any other way reads back keyed that way instead of blank."""
        table = sweep.read_stored_table(self._stored(key="scope", rows=[
            {"scope": "bust", sweep.DELTA: "0.5000", "status": "ok"}]), "player")
        self.assertEqual(table.key_field, "scope")
        self.assertEqual([row.key for row in table.rows], ["bust"])

    def test_an_artifact_that_is_not_a_sweep_table_is_missing_input(self):
        """Rows carrying the delta, so the refusal under test is the kind and not the column: a
        stored artifact of another kind whose rows happen to read like a sweep's is what a re-homed
        id leaves at the path the root resolves."""
        with self.assertRaises(MissingInput):
            sweep.read_stored_table(self._stored(kind="render-manifest", key="path", rows=[
                {"path": "cache/visual/a.png", sweep.DELTA: "0.0000", "status": "ok"}]))

    def test_a_stored_sweep_without_the_delta_column_is_missing_input(self):
        with self.assertRaises(MissingInput):
            sweep.read_stored_table(self._stored(key="subject", rows=[
                {"subject": "minecraft__cow", "status": "ok"}]))

    def test_the_two_readers_answer_with_the_same_columns_for_the_same_data(self):
        """The stored form drops the key out of each row's values, so the column list has to be
        seeded with it: a producer's header line opens with the key and this one has no header at
        all. Seeded empty the reader still answers every row correctly and hands a consumer a
        different table for the same sweep, with the key column silently absent from one of them."""
        header = f"subject\t{sweep.DELTA}\tstatus\n"
        tsv = self.tmp / "parity-report.tsv"
        tsv.write_bytes((header + "minecraft__cow\t0.2000\tok\n").encode("utf-8"))
        stored = sweep.read_stored_table(self._stored(key="subject", rows=[
            {"subject": "minecraft__cow", sweep.DELTA: "0.2000", "status": "ok"}]), "entity")

        self.assertEqual(stored.columns, ("subject", sweep.DELTA, "status"))
        self.assertEqual(stored.columns, sweep.read_table(tsv, "entity").columns)


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

    def test_a_sweeps_own_output_directory(self):
        """What the build actually hands a capture step: the producer's own directory."""
        import shutil
        import tempfile
        home = Path(tempfile.mkdtemp()) / "armor-parity-vanilla"
        home.mkdir(parents=True)
        shutil.copyfile(DATA / "sweep-armor.tsv", home / "parity-report.tsv")
        self.assertEqual(sweep.discover(home), {"armor": home / "parity-report.tsv"})

    def test_a_bare_report_is_not_credited_to_a_sweep_the_directory_does_not_name(self):
        """Attribution is by directory name, so one table cannot answer for six."""
        import shutil
        import tempfile
        home = Path(tempfile.mkdtemp()) / "somewhere-else"
        home.mkdir(parents=True)
        shutil.copyfile(DATA / "sweep-armor.tsv", home / "parity-report.tsv")
        with self.assertRaises(MissingInput):
            sweep.discover(home)


if __name__ == "__main__":
    unittest.main()
