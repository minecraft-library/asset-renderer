"""The five ordered classes, the mover definition, and the absence of any tolerance."""

from __future__ import annotations

import unittest
from pathlib import Path

from parity import compare, store, sweep
from parity.norm import ComparisonFailed

DATA = Path(__file__).resolve().parent / "data"


def artifact(rows: list[dict], artifact_id: str = "sweep.entity") -> dict:
    return {"artifact": artifact_id, "format": 1, "key": "subject", "kind": "sweep-table",
            "rows": rows}


def from_fixture(name: str) -> dict:
    table = sweep.read_table(DATA / name, "entity")
    return artifact(sweep.to_rows(table))


class Classification(unittest.TestCase):
    """Every difference lands in exactly one class, and a row matching several takes the first."""

    def test_the_five_classes_partition(self):
        left = from_fixture("sweep-entity-a.tsv")
        right = from_fixture("sweep-entity-b.tsv")
        result = compare.compare(left, right)
        totals = result.totals()
        self.assertEqual(totals["added"], 1)
        self.assertEqual(totals["dropped"], 1)
        self.assertEqual(totals["moved"], totals["canvas"] + totals["metric"] + totals["status"])

    def test_the_added_and_dropped_keys(self):
        result = compare.compare(from_fixture("sweep-entity-a.tsv"),
                                 from_fixture("sweep-entity-b.tsv"))
        self.assertEqual(result.added, ["minecraft__warden"])
        self.assertEqual(result.dropped, ["minecraft__cod"])

    def test_a_canvas_move_outranks_the_metric_and_keeps_it_in_also(self):
        """A canvas move re-samples every pixel on that row, so it is what a reader must know first."""
        left = artifact([{"subject": "x", "mean_argb_delta": "1.0000", "java_w": "100"}])
        right = artifact([{"subject": "x", "mean_argb_delta": "2.0000", "java_w": "101"}])
        mover = compare.compare(left, right).movers[0]
        self.assertEqual(mover["class"], "canvas")
        self.assertEqual(mover["also"], ["metric"])

    def test_a_status_change_outranks_everything(self):
        left = artifact([{"subject": "x", "status": "ok", "mean_argb_delta": "1.0000"}])
        right = artifact([{"subject": "x", "status": "failed", "mean_argb_delta": "2.0000"}])
        self.assertEqual(compare.compare(left, right).movers[0]["class"], "status")


class MoverDefinition(unittest.TestCase):

    def test_a_held_metric_with_a_moved_canvas_is_still_a_mover(self):
        """Calling this unchanged is how a canvas change hides."""
        left = artifact([{"subject": "x", "mean_argb_delta": "1.0000", "java_w": "317"}])
        right = artifact([{"subject": "x", "mean_argb_delta": "1.0000", "java_w": "318"}])
        result = compare.compare(left, right)
        self.assertEqual(result.totals()["moved"], 1)
        self.assertEqual(result.movers[0]["class"], "canvas")

    def test_a_held_sum_with_cancelling_rows_still_reports_movers(self):
        """The accept criterion is zero movers, never 'the sum held'."""
        left = artifact([{"subject": "a", "mean_argb_delta": "1.0000"},
                         {"subject": "b", "mean_argb_delta": "3.0000"}])
        right = artifact([{"subject": "a", "mean_argb_delta": "2.0000"},
                          {"subject": "b", "mean_argb_delta": "2.0000"}])
        result = compare.compare(left, right)
        self.assertEqual(result.left.sum(), result.right.sum())
        self.assertEqual(result.totals()["moved"], 2)
        self.assertFalse(result.clean())

    def test_a_one_row_table_against_a_full_one_reports_drops_not_a_held_sum(self):
        """A scoped -PentityId run once overwrote a full report and was frozen as a 1-row baseline."""
        left = from_fixture("sweep-entity-a.tsv")
        right = artifact([left["rows"][0]])
        result = compare.compare(left, right)
        self.assertEqual(len(result.dropped), 11)
        self.assertFalse(result.clean())

    def test_identical_artifacts_are_clean(self):
        left = from_fixture("sweep-entity-a.tsv")
        self.assertTrue(compare.compare(left, left).clean())
        compare.raise_on([compare.compare(left, left)])


class NoTolerance(unittest.TestCase):
    """No epsilon, no relative tolerance, no rounding before compare, on any artifact."""

    def test_a_last_digit_move_is_a_mover(self):
        left = artifact([{"subject": "x", "mean_argb_delta": "1.0000"}])
        right = artifact([{"subject": "x", "mean_argb_delta": "1.0001"}])
        self.assertEqual(compare.compare(left, right).totals()["moved"], 1)

    def test_no_tolerance_flag_exists_anywhere(self):
        """If it does not exist it cannot be reached for at 2am."""
        self.assertFalse(any("toler" in name.lower() for name in dir(compare)))


class ExpectedDiff(unittest.TestCase):
    """The device that replaces a tolerance: diff == manifest, not diff == empty."""

    LEFT = artifact([{"subject": "x", "mean_argb_delta": "1.0000"},
                     {"subject": "y", "mean_argb_delta": "1.0000"}])
    RIGHT = artifact([{"subject": "x", "mean_argb_delta": "2.0000"},
                      {"subject": "y", "mean_argb_delta": "1.0000"}])

    def test_a_registered_mover_passes(self):
        expected = {"movers": [{"artifact": "sweep.entity", "key": "x", "reason": "priced"}]}
        result = compare.compare(self.LEFT, self.RIGHT, expected)
        self.assertTrue(result.movers[0]["expected"])
        self.assertTrue(result.clean())
        compare.raise_on([result])

    def test_an_unregistered_mover_fails(self):
        result = compare.compare(self.LEFT, self.RIGHT, compare.empty_expected())
        self.assertFalse(result.movers[0]["expected"])
        with self.assertRaises(ComparisonFailed):
            compare.raise_on([result])

    def test_a_registration_for_another_artifact_does_not_count(self):
        expected = {"movers": [{"artifact": "sweep.block", "key": "x"}]}
        self.assertFalse(compare.compare(self.LEFT, self.RIGHT, expected).movers[0]["expected"])

    def test_an_empty_manifest_still_gates(self):
        self.assertEqual(compare.empty_expected()["movers"], [])


class Envelope(unittest.TestCase):

    def test_the_join_key_comes_from_the_artifact_never_from_the_kind(self):
        """One caller once keyed the armour report entity_id while its header is subject."""
        left = {"artifact": "sweep.armor", "key": "subject", "kind": "sweep-table",
                "rows": [{"subject": "minecraft__zombie_iron", "mean_argb_delta": "2.4299"}]}
        right = {"artifact": "sweep.armor", "key": "subject", "kind": "sweep-table",
                 "rows": [{"subject": "minecraft__zombie_iron", "mean_argb_delta": "2.4906"}]}
        self.assertEqual(compare.compare(left, right).movers[0]["key"], "minecraft__zombie_iron")

    def test_an_artifact_without_a_key_is_refused(self):
        with self.assertRaises(ComparisonFailed):
            compare.compare({"artifact": "x", "kind": "sweep-table", "rows": []},
                            {"artifact": "x", "kind": "sweep-table", "rows": []})

    def test_two_different_artifacts_cannot_be_joined(self):
        with self.assertRaises(ComparisonFailed):
            compare.compare(artifact([], "sweep.entity"), artifact([], "sweep.block"))

    def test_a_manifest_joins_on_path(self):
        left = {"artifact": "manifest.visual", "key": "path", "kind": "manifest",
                "files": [{"path": "a.png", "sha256": "1"}]}
        right = {"artifact": "manifest.visual", "key": "path", "kind": "manifest",
                 "files": [{"path": "a.png", "sha256": "2"}]}
        self.assertEqual(compare.compare(left, right).movers[0]["key"], "a.png")


class ManifestLogDigests(unittest.TestCase):
    """A manifest's `logs` entries join the SAME keyspace its files do.

    They have to: a value the gate does not read gates nothing, and the reordered-log-with-an
    -identical-table case is the one the projection was added for.
    """

    @staticmethod
    def _tables(table_digest: str, log_digest: str) -> dict:
        return {"artifact": "manifest.tooling-tables", "key": "path", "kind": "manifest",
                "files": [{"path": "block_items.json", "sha256": table_digest}],
                "logs": {"blockItems": log_digest}}

    def test_a_moved_log_digest_is_a_mover(self):
        result = compare.compare(self._tables("1", "a"), self._tables("1", "b"))
        self.assertEqual([mover["key"] for mover in result.movers], ["logs/blockItems"])
        self.assertFalse(result.clean())

    def test_an_unchanged_pair_is_clean(self):
        self.assertTrue(compare.compare(self._tables("1", "a"), self._tables("1", "a")).clean())

    def test_the_log_rows_do_not_collide_with_the_file_rows(self):
        result = compare.compare(self._tables("1", "a"), self._tables("1", "a"))
        self.assertEqual(sorted(result.left.rows), ["block_items.json", "logs/blockItems"])

    def test_a_manifest_without_logs_is_untouched(self):
        left = {"artifact": "manifest.fluid", "key": "path", "kind": "manifest",
                "files": [{"path": "a.png", "sha256": "1"}]}
        self.assertEqual(sorted(compare.side_of(left, "base").rows), ["a.png"])


class ObjectKeyedPayloads(unittest.TestCase):
    """A digest-set and a pin-set key their payload by name; reading only the array shape is a
    FALSE GREEN, not a narrowing - every value can move and the join reports clean."""

    @staticmethod
    def _digests(sha: str) -> dict:
        return {"artifact": "digest.shipped-tables", "format": 1, "key": "name",
                "kind": "digest-set",
                "digests": {"block_models": {"form": "table-canonical", "regen": "blockModels",
                                             "sha256": sha}}}

    @staticmethod
    def _pins(crc: str) -> dict:
        return {"artifact": "pin.player-crc", "format": 1, "key": "pin_key", "kind": "pin-set",
                "values": {"full_vanilla_iso": {"crc32": crc, "subject": "Type.FULL",
                                                "type": "crc32"}}}

    def test_a_moved_digest_is_a_mover(self):
        result = compare.compare(self._digests("aaaa"), self._digests("bbbb"))
        self.assertEqual([mover["key"] for mover in result.movers], ["block_models"])
        self.assertEqual(result.movers[0]["fields"], {"sha256": ["aaaa", "bbbb"]})
        self.assertFalse(result.clean())

    def test_an_unchanged_digest_set_is_clean(self):
        self.assertTrue(compare.compare(self._digests("aaaa"), self._digests("aaaa")).clean())

    def test_a_dropped_digest_is_a_drop_rather_than_an_empty_join(self):
        right = self._digests("aaaa")
        right["digests"] = {}
        result = compare.compare(self._digests("aaaa"), right)
        self.assertEqual(result.dropped, ["block_models"])
        self.assertFalse(result.clean())

    def test_the_map_key_becomes_the_row_key_rather_than_being_read_out_of_the_entry(self):
        """An object-keyed payload states the key once, in the map, so the join injects it."""
        side = compare.side_of(self._digests("aaaa"), "base")
        self.assertEqual(side.rows["block_models"]["name"], "block_models")

    def test_a_pin_set_joins_on_its_values_member(self):
        result = compare.compare(self._pins("0x11111111"), self._pins("0x22222222"))
        self.assertEqual([mover["key"] for mover in result.movers], ["full_vanilla_iso"])

    def test_the_pin_set_member_is_the_one_the_java_reader_asks_for(self):
        """`Pins.payload` reads `values`; a member named anything else stores what nothing reads."""
        self.assertEqual(store.rows_member("pin-set"), "values")


class ShippedTablesAgreement(unittest.TestCase):
    """The one cross-artifact check: the covered SET is comparable where the digests are not."""

    @staticmethod
    def _digests(*names: str) -> dict:
        return {"artifact": "digest.shipped-tables", "format": 1, "key": "name",
                "kind": "digest-set",
                "digests": {name: {"form": "table-canonical", "sha256": "a"} for name in names}}

    @staticmethod
    def _tables(*paths: str) -> dict:
        return {"artifact": "manifest.tooling-tables", "format": 1, "key": "path",
                "kind": "manifest",
                "files": [{"path": path, "sha256": "b"} for path in paths]}

    def test_the_ten_agree(self):
        names = ("block_defaults", "block_geometry", "block_items", "block_models", "block_tints",
                 "color_maps", "entity_geometry", "entity_models", "glint_items", "potion_colors")
        self.assertEqual(compare.shipped_tables_agreement(
            self._digests(*names), self._tables(*(f"{name}.json" for name in names))), [])

    def test_it_fires_on_a_name_in_the_manifest_and_not_the_digest_set(self):
        """A flow that started emitting an eleventh table only one walk picked up."""
        self.assertEqual(compare.shipped_tables_agreement(
            self._digests("block_models"), self._tables("block_models.json", "new_table.json")),
            ["new_table"])

    def test_it_fires_on_a_name_in_the_digest_set_and_not_the_manifest(self):
        self.assertEqual(compare.shipped_tables_agreement(
            self._digests("block_models", "dropped"), self._tables("block_models.json")),
            ["dropped"])

    def test_it_does_NOT_fire_on_two_different_digests_for_one_name(self):
        """The two are taken over different canonical forms, so a value rule would fail for ever."""
        digests = self._digests("block_models")
        digests["digests"]["block_models"]["sha256"] = "ffff"
        self.assertEqual(compare.shipped_tables_agreement(digests, self._tables("block_models.json")),
                         [])

    def test_a_nested_table_is_a_disagreement_rather_than_a_match(self):
        """The manifest rglobs and the test lists one level, which is what gives this something to
        find; the directory component therefore stays on."""
        self.assertEqual(compare.shipped_tables_agreement(
            self._digests("block_models"), self._tables("sub/block_models.json")),
            ["block_models", "sub/block_models"])

    def test_only_a_trailing_json_comes_off(self):
        self.assertEqual(compare.shipped_tables_agreement(
            self._digests("a.json.keep"), self._tables("a.json.keep.json")), [])


if __name__ == "__main__":
    unittest.main()
