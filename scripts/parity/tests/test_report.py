"""The Markdown view: byte-stable, regenerable, and carrying no prose."""

from __future__ import annotations

import unittest

from parity import compare, report


def diff_payload(movers: int = 1) -> dict:
    result = compare.compare(
        {"artifact": "sweep.entity", "key": "subject", "kind": "sweep-table",
         "rows": [{"subject": f"s{n}", "mean_argb_delta": "1.0000"} for n in range(movers)]},
        {"artifact": "sweep.entity", "key": "subject", "kind": "sweep-table",
         "rows": [{"subject": f"s{n}", "mean_argb_delta": "2.0000"} for n in range(movers)]},
    )
    return compare.to_report([result])


class Stability(unittest.TestCase):

    def test_render_is_idempotent(self):
        payload = diff_payload()
        self.assertEqual(report.render_diff(payload), report.render_diff(payload))

    def test_no_prose_is_generated(self):
        """A generated sentence reads as a judgement, and the gate does not judge."""
        text = report.render_diff(diff_payload())
        for word in ("regression", "improved", "worse", "better", "looks", "probably", "should"):
            self.assertNotIn(word, text.lower())

    def test_the_header_table_is_always_the_same_columns(self):
        text = report.render_diff(diff_payload())
        self.assertIn("| | rows | sum |", text)


class MoverCap(unittest.TestCase):
    """A 400-row diff must not become the report; the JSON is the authority."""

    def test_capped_with_a_pointer_to_the_json(self):
        import re
        text = report.render_diff(diff_payload(report.MOVER_CAP + 5))
        self.assertIn("and 5 more, see `_run/compare.json`", text)
        rows = [line for line in text.splitlines() if re.match(r"^\| s\d+ \|", line)]
        self.assertEqual(len(rows), report.MOVER_CAP)

    def test_under_the_cap_nothing_is_elided(self):
        text = report.render_diff(diff_payload(3))
        self.assertNotIn("more, see", text)


class Kinds(unittest.TestCase):

    def test_a_sweep_table_renders(self):
        text = report.render({"artifact": "sweep.entity", "kind": "sweep-table",
                              "rows": [{"subject": "a", "mean_argb_delta": "1.0000"}]})
        self.assertIn("sweep.entity", text)
        self.assertIn("| subject |", text)

    def test_a_manifest_renders_with_its_root(self):
        text = report.render({"artifact": "manifest.visual", "kind": "manifest",
                              "files": [{"path": "a.png", "sha256": "f" * 64}],
                              "provenance": {"root": "cache/visual"}})
        self.assertIn("cache/visual", text)
        self.assertIn("1 files", text)

    def test_an_index_renders(self):
        text = report.render({"artifact": "report.oracle-index", "kind": "index",
                              "artifacts": {"sweep.entity": {"kind": "sweep-table",
                                                             "entries": 402,
                                                             "file": "sweeps/entity.json"}}})
        self.assertIn("sweep.entity", text)
        self.assertIn("402", text)

    def test_an_unknown_kind_still_renders_something(self):
        text = report.render({"artifact": "x", "kind": "mystery", "rows": [1, 2, 3]})
        self.assertIn("x", text)


if __name__ == "__main__":
    unittest.main()
