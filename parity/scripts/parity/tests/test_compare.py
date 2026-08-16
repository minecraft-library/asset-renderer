"""The five ordered classes, the mover definition, and the absence of any tolerance."""

from __future__ import annotations

import unittest
from pathlib import Path

from parity import compare, store, sweep
from parity.norm import ComparisonFailed, Refused

DATA = Path(__file__).resolve().parent / "data"

#: Every fixture here carries one, because the join refuses a side that cannot say what produced it
#: and `capture-normalize` stamps one on every artifact it writes. A payload without it is not a
#: narrower fixture, it is a shape no capture can produce.
PROVENANCE = {"producer": "unit-test", "tool_version": "0"}


def artifact(rows: list[dict], artifact_id: str = "sweep.entity") -> dict:
    return {"artifact": artifact_id, "format": 1, "key": "subject", "kind": "sweep-table",
            "provenance": PROVENANCE, "rows": rows}


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

    @staticmethod
    def _expected(**members) -> dict:
        return {"movers": [{"artifact": "sweep.entity", "key": "x", "reason": "priced", **members}]}

    def test_a_mover_that_lands_on_its_registered_value_passes(self):
        result = compare.compare(self.LEFT, self.RIGHT, self._expected(to="2.0000"))
        self.assertTrue(result.movers[0]["expected"])
        self.assertTrue(result.clean())
        compare.raise_on([result])

    def test_a_mover_that_lands_anywhere_else_fails(self):
        """The registration is what the row must move TO, never merely that it may move.

        Read as key membership, an intended +0.2000 landing at 99.9999 counted as expected and the
        gate passed GREEN - which is a tolerance, arriving through the one device that exists so
        there is no tolerance.
        """
        result = compare.compare(self.LEFT, self.RIGHT, self._expected(to="0.2000"))
        self.assertFalse(result.movers[0]["expected"])
        self.assertEqual(result.totals()["unexpected"], 1)
        with self.assertRaises(ComparisonFailed):
            compare.raise_on([result])

    def test_a_registration_naming_no_value_is_refused_rather_than_read_as_a_wildcard(self):
        with self.assertRaises(Refused):
            compare.compare(self.LEFT, self.RIGHT, self._expected())

    def test_a_registration_naming_no_key_is_refused_too(self):
        expected = {"movers": [{"artifact": "sweep.entity", "reason": "priced", "to": "2.0000"}]}
        with self.assertRaises(Refused):
            compare.compare(self.LEFT, self.RIGHT, expected)

    #: One row moving in two of the nine columns a real sweep row carries: the canvas it was widened
    #: to, and a metric regression nobody registered riding along on the same key.
    TWO_FIELDS_LEFT = artifact([{"subject": "x", "mean_argb_delta": "0.2004", "java_w": "32"}])
    TWO_FIELDS_RIGHT = artifact([{"subject": "x", "mean_argb_delta": "99.9999", "java_w": "34"}])

    def test_registering_one_field_of_a_two_field_move_does_not_cover_the_other(self):
        """A row is one key and nine columns, so a registration is not a licence for the row.

        Registering the intended canvas value marked the whole row expected, and the unregistered
        metric move on that same key went GREEN behind it - the expected-diff's own failure mode,
        narrowed to a multi-column row rather than closed.
        """
        for value in ("34", "99.9999"):
            result = compare.compare(self.TWO_FIELDS_LEFT, self.TWO_FIELDS_RIGHT,
                                     self._expected(to=value))
            self.assertFalse(result.movers[0]["expected"], value)
            self.assertEqual(result.totals()["unexpected"], 1, value)
            with self.assertRaises(ComparisonFailed):
                compare.raise_on([result])

    def test_a_row_moving_in_two_fields_is_registered_by_registering_both_values(self):
        """Registration is per-row and additive, so a row moving twice is declared twice."""
        expected = {"movers": [
            {"artifact": "sweep.entity", "key": "x", "reason": "canvas", "to": "34"},
            {"artifact": "sweep.entity", "key": "x", "reason": "metric", "to": "99.9999"}]}
        result = compare.compare(self.TWO_FIELDS_LEFT, self.TWO_FIELDS_RIGHT, expected)
        self.assertTrue(result.movers[0]["expected"])
        self.assertTrue(result.clean())
        compare.raise_on([result])

    def test_a_second_registration_of_one_key_adds_a_value_rather_than_replacing_one(self):
        """The two registrations above are order-free, which is what `additive` has to mean."""
        movers = [{"artifact": "sweep.entity", "key": "x", "reason": "r", "to": "99.9999"},
                  {"artifact": "sweep.entity", "key": "x", "reason": "r", "to": "34"}]
        self.assertTrue(compare.compare(self.TWO_FIELDS_LEFT, self.TWO_FIELDS_RIGHT,
                                        {"movers": movers}).movers[0]["expected"])

    def test_an_unregistered_mover_fails(self):
        result = compare.compare(self.LEFT, self.RIGHT, compare.empty_expected())
        self.assertFalse(result.movers[0]["expected"])
        with self.assertRaises(ComparisonFailed):
            compare.raise_on([result])

    def test_a_registration_for_another_artifact_does_not_count(self):
        expected = {"movers": [{"artifact": "sweep.block", "key": "x", "to": "2.0000"}]}
        self.assertFalse(compare.compare(self.LEFT, self.RIGHT, expected).movers[0]["expected"])

    def test_an_empty_manifest_still_gates(self):
        self.assertEqual(compare.empty_expected()["movers"], [])


class ProvenanceIsRequired(unittest.TestCase):
    """The refusal the spine states at the compare and only the promotion implemented.

    The promotion covers the store, because nothing else writes it. It covers neither side of an A/B
    of two redirected roots, which is the shape the runbook prescribes and which never promotes.
    """

    def test_a_base_without_provenance_is_refused(self):
        left = artifact([{"subject": "x", "mean_argb_delta": "1.0000"}])
        del left["provenance"]
        with self.assertRaises(Refused):
            compare.compare(left, artifact([{"subject": "x", "mean_argb_delta": "1.0000"}]))

    def test_a_current_without_provenance_is_refused(self):
        right = artifact([{"subject": "x", "mean_argb_delta": "1.0000"}])
        del right["provenance"]
        with self.assertRaises(Refused):
            compare.compare(artifact([{"subject": "x", "mean_argb_delta": "1.0000"}]), right)

    def test_an_empty_provenance_object_is_not_one(self):
        left = artifact([{"subject": "x", "mean_argb_delta": "1.0000"}])
        left["provenance"] = {}
        with self.assertRaises(Refused):
            compare.compare(left, artifact([{"subject": "x", "mean_argb_delta": "1.0000"}]))

    def test_the_message_names_the_side_and_the_artifact(self):
        left = artifact([], "sweep.block")
        del left["provenance"]
        with self.assertRaises(Refused) as caught:
            compare.compare(left, artifact([], "sweep.block"))
        self.assertIn("base", str(caught.exception))
        self.assertIn("sweep.block", str(caught.exception))


class Envelope(unittest.TestCase):

    def test_the_join_key_comes_from_the_artifact_never_from_the_kind(self):
        """One caller once keyed the armour report entity_id while its header is subject."""
        left = {"artifact": "sweep.armor", "key": "subject", "kind": "sweep-table",
                "provenance": PROVENANCE,
                "rows": [{"subject": "minecraft__zombie_iron", "mean_argb_delta": "2.4299"}]}
        right = {"artifact": "sweep.armor", "key": "subject", "kind": "sweep-table",
                 "provenance": PROVENANCE,
                 "rows": [{"subject": "minecraft__zombie_iron", "mean_argb_delta": "2.4906"}]}
        self.assertEqual(compare.compare(left, right).movers[0]["key"], "minecraft__zombie_iron")

    def test_an_artifact_without_a_key_is_refused(self):
        with self.assertRaises(ComparisonFailed):
            compare.compare(
                {"artifact": "x", "kind": "sweep-table", "provenance": PROVENANCE, "rows": []},
                {"artifact": "x", "kind": "sweep-table", "provenance": PROVENANCE, "rows": []})

    def test_two_different_artifacts_cannot_be_joined(self):
        with self.assertRaises(ComparisonFailed):
            compare.compare(artifact([], "sweep.entity"), artifact([], "sweep.block"))

    def test_a_manifest_joins_on_path(self):
        left = {"artifact": "manifest.visual", "key": "path", "kind": "manifest",
                "provenance": PROVENANCE, "files": [{"path": "a.png", "sha256": "1"}]}
        right = {"artifact": "manifest.visual", "key": "path", "kind": "manifest",
                 "provenance": PROVENANCE, "files": [{"path": "a.png", "sha256": "2"}]}
        self.assertEqual(compare.compare(left, right).movers[0]["key"], "a.png")


class ManifestLogDigests(unittest.TestCase):
    """A manifest's `logs` entries join the SAME keyspace its files do.

    They have to: a value the gate does not read gates nothing, and the reordered-log-with-an
    -identical-table case is the one the projection was added for.
    """

    @staticmethod
    def _tables(table_digest: str, log_digest: str) -> dict:
        return {"artifact": "manifest.tooling-tables", "key": "path", "kind": "manifest",
                "provenance": PROVENANCE,
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
                "provenance": PROVENANCE, "files": [{"path": "a.png", "sha256": "1"}]}
        self.assertEqual(sorted(compare.side_of(left, "base").rows), ["a.png"])


class ObjectKeyedPayloads(unittest.TestCase):
    """A digest-set and a pin-set key their payload by name; reading only the array shape is a
    FALSE GREEN, not a narrowing - every value can move and the join reports clean."""

    @staticmethod
    def _digests(sha: str) -> dict:
        return {"artifact": "digest.shipped-tables", "format": 1, "key": "name",
                "kind": "digest-set", "provenance": PROVENANCE,
                "digests": {"block_models": {"form": "table-canonical", "regen": "blockModels",
                                             "sha256": sha}}}

    @staticmethod
    def _pins(crc: str) -> dict:
        return {"artifact": "pin.player-crc", "format": 1, "key": "pin_key", "kind": "pin-set",
                "provenance": PROVENANCE,
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
