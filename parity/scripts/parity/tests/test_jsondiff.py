"""L1, L2 and the level `git diff` cannot express."""

from __future__ import annotations

import unittest
from pathlib import Path

from parity import jsondiff
from parity.norm import ComparisonFailed, read_json

DATA = Path(__file__).resolve().parent / "data"


class Levels(unittest.TestCase):

    def setUp(self):
        self.before = read_json(DATA / "table-before.json")
        self.after = read_json(DATA / "table-after.json")

    def test_L1_reports_the_dropped_key(self):
        found = jsondiff.diff(self.before, self.after)
        self.assertEqual(found.missing, ["delta"])
        self.assertEqual(found.extra, [])

    def test_L2_distinguishes_1_from_1_0(self):
        """repr catches float-formatting drift that JSON equality masks."""
        found = jsondiff.diff(self.before, self.after)
        changed = {row["key"] for row in found.changed}
        self.assertIn("alpha", changed)

    def test_L3_reports_the_first_order_divergence_only(self):
        """The distinction that caught a diagnostics line moving from position 9 to 6 while the
        emitted JSON stayed byte-identical."""
        found = jsondiff.diff(self.before, self.after)
        self.assertEqual(found.order, {"after": "gamma", "before": "beta", "position": 1})

    def test_levels_can_be_narrowed(self):
        found = jsondiff.diff(self.before, self.after, levels=("L1",))
        self.assertEqual(found.missing, ["delta"])
        self.assertEqual(found.changed, [])
        self.assertIsNone(found.order)


class Equality(unittest.TestCase):

    def test_int_and_float_differ(self):
        self.assertTrue(jsondiff.differs(1, 1.0))

    def test_object_member_order_is_ignored_for_values(self):
        self.assertFalse(jsondiff.differs({"a": 1, "b": 2}, {"b": 2, "a": 1}))

    def test_equal_scalars_do_not_differ(self):
        self.assertFalse(jsondiff.differs("x", "x"))
        self.assertFalse(jsondiff.differs(2.5, 2.5))


class GateSignal(unittest.TestCase):
    """A tightening: the original exited 1 on L1 alone and reported L2 deltas as findings."""

    def test_an_L2_value_change_fails(self):
        found = jsondiff.diff({"rows": {"a": 1}}, {"rows": {"a": 2}})
        self.assertTrue(found.gate_fails())
        with self.assertRaises(ComparisonFailed):
            jsondiff.raise_on(found)

    def test_an_L1_miss_fails(self):
        found = jsondiff.diff({"rows": {"a": 1, "b": 2}}, {"rows": {"a": 1}})
        self.assertTrue(found.gate_fails())

    def test_a_pure_reordering_does_NOT_fail(self):
        """L3 reports it; it is not a value change, and the gate is about values."""
        found = jsondiff.diff({"rows": {"a": 1, "b": 2}}, {"rows": {"b": 2, "a": 1}})
        self.assertFalse(found.gate_fails())
        self.assertIsNotNone(found.order)


class Payloads(unittest.TestCase):

    def test_envelope_keys_are_skipped_when_auto_detecting(self):
        payload = jsondiff.payload_of({"//": "x", "format": 1, "rows": {"a": 1}})
        self.assertEqual(payload, {"a": 1})

    def test_an_array_payload_is_keyed_by_its_first_row_key(self):
        rows = [{"block": "stone", "v": 1}, {"block": "dirt", "v": 2}]
        found = jsondiff.diff({"rows": rows}, {"rows": list(reversed(rows))})
        self.assertFalse(found.gate_fails())
        self.assertIsNotNone(found.order)

    def test_an_array_without_a_row_key_falls_back_to_the_index(self):
        found = jsondiff.diff({"rows": [{"v": 1}]}, {"rows": [{"v": 2}]})
        self.assertEqual([row["key"] for row in found.changed], ["0"])

    def test_ignore_keys(self):
        found = jsondiff.diff({"rows": {"a": 1, "t": 9}}, {"rows": {"a": 1, "t": 8}},
                              ignore_keys=("t",))
        self.assertFalse(found.gate_fails())


if __name__ == "__main__":
    unittest.main()
