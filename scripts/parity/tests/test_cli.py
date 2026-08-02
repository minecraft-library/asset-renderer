"""The exit-code table, the stdout contract, and the spellings that must stay deleted."""

from __future__ import annotations

import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from parity import capture, cli, provenance, store
from parity.norm import write_json, write_text


def run(argv: list[str]) -> tuple[int, str, str]:
    """Invoke main and capture the two streams separately - the contract is that they differ."""
    out, err = io.StringIO(), io.StringIO()
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = cli.main(argv)
    except SystemExit as exit_:  # argparse's own path
        code = int(exit_.code or 0)
    return code, out.getvalue(), err.getvalue()


class ExitCodes(unittest.TestCase):
    """The separation of 1 from 3 and 5 is the point.

    The corpus conflated them twice: one script always exited 1 for a reason having nothing to do
    with parity, and another returned 1 from main() but was called bare, so it always exited 0 and
    could gate nothing.
    """

    def test_0_ok(self):
        code, _, _ = run(["doctor"])
        self.assertEqual(code, cli.OK)

    def test_2_usage_on_an_unknown_command(self):
        code, _, _ = run(["no-such-command"])
        self.assertEqual(code, cli.USAGE)

    def test_2_usage_on_an_unparseable_flag(self):
        code, _, _ = run(["doctor", "--no-such-flag"])
        self.assertEqual(code, cli.USAGE)

    def test_5_refused_on_an_absolute_root(self):
        code, _, err = run(["--root", "/tmp/parity", "doctor"])
        self.assertEqual(code, cli.REFUSED)
        self.assertIn("cache/", err)

    def test_5_refused_on_a_root_outside_cache(self):
        code, _, _ = run(["--root", "notes/parity", "doctor"])
        self.assertEqual(code, cli.REFUSED)

    def test_the_table_has_six_codes_and_no_more(self):
        codes = {cli.OK, cli.DIFFERENCES, cli.USAGE, cli.MISSING_INPUT,
                 cli.MISSING_DEPENDENCY, cli.REFUSED}
        self.assertEqual(codes, {0, 1, 2, 3, 4, 5})


class StdoutContract(unittest.TestCase):

    def test_json_format_emits_one_document_and_nothing_else(self):
        code, out, _ = run(["--format", "json", "ids", "parse", "minecraft__cow"])
        self.assertEqual(code, cli.OK)
        self.assertEqual(json.loads(out)["ref_stem"], "minecraft__cow")

    def test_text_format_answers_with_the_answer(self):
        stem = "minecraft__villager~villager_level=diamond~villager_profession=farmer"
        code, out, _ = run(["ids", "parse", stem])
        self.assertEqual(code, cli.OK)
        self.assertEqual(out.strip(), stem)

    def test_progress_goes_to_stderr_only(self):
        _, out, _ = run(["--format", "json", "doctor"])
        json.loads(out)  # parses, so no progress line leaked into stdout


class GlobalOptions(unittest.TestCase):

    COMMANDS = (["doctor"], ["ids", "parse", "minecraft__cow"], ["selftest", "-k", "nothing"])

    def test_every_command_accepts_root_on_either_side(self):
        for argv in self.COMMANDS:
            self.assertNotEqual(run(["--root", "cache/parity/base"] + argv)[0], cli.USAGE, argv)
            self.assertNotEqual(run(argv + ["--root", "cache/parity/base"])[0], cli.USAGE, argv)

    def test_deleted_spellings_are_rejected(self):
        """A merge that resurrects one of these is visible rather than silently accepted."""
        for flag in ("--slot", "--store-root", "--production", "--against", "--expect",
                     "--determinism-runs", "--dry-run"):
            code, _, _ = run(["doctor", flag, "x"])
            self.assertEqual(code, cli.USAGE, flag)

    def test_version(self):
        code, out, _ = run(["--version"])
        self.assertEqual(code, 0)
        self.assertIn("parity", out)


class ShippedTablesAgreementThroughTheCli(unittest.TestCase):
    """Which COPY of each operand the check reads, and that it says so when it read neither."""

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        self.store = self.repo / "store"

    @staticmethod
    def _digests(*names: str) -> dict:
        return {"artifact": "digest.shipped-tables", "format": 1, "key": "name",
                "kind": "digest-set", "provenance": {"determinism_runs": 2},
                "digests": {name: {"form": "table-canonical", "sha256": "a"} for name in names}}

    @staticmethod
    def _tables(*names: str) -> dict:
        return {"artifact": "manifest.tooling-tables", "format": 1, "key": "path",
                "kind": "manifest", "provenance": {"determinism_runs": 2},
                "files": [{"path": f"{name}.json", "sha256": "b"} for name in names]}

    def _capture(self, *payloads: dict) -> None:
        for payload in payloads:
            write_json(self.root / store.path_of(payload["artifact"]), payload)
        capture.index(self.root)

    def _compare(self) -> tuple[int, dict]:
        code, out, _ = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                            "--store", str(self.store), "--format", "json",
                            "compare", "--bootstrap"])
        return code, json.loads(out)

    def test_both_in_the_root_and_agreeing(self):
        self._capture(self._digests("block_models"), self._tables("block_models"))
        code, payload = self._compare()
        self.assertEqual(code, cli.OK)
        self.assertEqual(payload["checks"]["shipped-tables-agreement"]["status"], "agreed")

    def test_both_in_the_root_and_disagreeing_fails(self):
        self._capture(self._digests("block_models"), self._tables("block_models", "eleventh"))
        code, payload = self._compare()
        self.assertEqual(code, cli.DIFFERENCES)
        verdict = payload["checks"]["shipped-tables-agreement"]
        self.assertEqual(verdict["status"], "disagreed")
        self.assertEqual(verdict["disagreements"], ["eleventh"])

    def test_the_operand_the_capture_did_not_name_is_read_from_the_base(self):
        """`-Partifacts=digests` is the common case; without the fallback the check never runs."""
        store.WritableStore(self.store).write("manifest.tooling-tables",
                                              self._tables("block_models", "eleventh"))
        self._capture(self._digests("block_models"))
        code, payload = self._compare()
        self.assertEqual(code, cli.DIFFERENCES)
        self.assertEqual(payload["checks"]["shipped-tables-agreement"]["disagreements"],
                         ["eleventh"])

    def test_an_unstamped_by_product_is_named_rather_than_compared(self):
        """`-Partifacts=digests` runs the suites, which write the PINS into the root too - stamped
        by no capture step. Comparing one credits this run with capturing it."""
        write_json(self.root / store.path_of("pin.player-crc"),
                   {"artifact": "pin.player-crc", "format": 1, "key": "pin_key", "kind": "pin-set",
                    "values": {"full_vanilla_iso": {"crc32": "0x1", "type": "crc32"}}})
        self._capture(self._digests("block_models"), self._tables("block_models"))
        code, payload = self._compare()
        self.assertEqual(code, cli.OK)
        self.assertEqual(payload["not_captured"], ["pin.player-crc"])
        self.assertNotIn("pin.player-crc", [entry["artifact"] for entry in payload["artifacts"]])

    def test_neither_side_holds_an_operand_says_so_rather_than_passing(self):
        """A check that is silent when it did not run cannot be told from one that passed."""
        self._capture(self._digests("block_models"))
        code, payload = self._compare()
        self.assertEqual(code, cli.OK)
        verdict = payload["checks"]["shipped-tables-agreement"]
        self.assertEqual(verdict["status"], "not_evaluated")
        self.assertEqual(verdict["absent"], ["manifest.tooling-tables"])


class PlanSplit(unittest.TestCase):
    """`sees` answers reach; `plan` selects a capture; the rest splits again on who GATES it.

    A rule names an artifact whenever the change really moves it, and three of the four homes hold
    artifacts the store keeps no file of its own for. Handing one to `parityCapture` refuses the whole
    invocation at configuration time, so the plan carries the store half.

    The remainder is not one thing, and it does not split on being written. A pointer at a ROW field
    lands in the keyspace `compare.side_of` joins, so the capture that writes that file writes the
    value and the verdict reports a move in it - telling a reader to go and read that by hand would
    be telling them to redo the capture's work. A pointer at a node the join never reads is written
    by the same capture and reported by no verdict at all, and a pin whose home is Java source has no
    carrier of any kind; both really are manual.

    What is left over splits once more, and each row says which it is. A pointer the compare joins
    on, whose container this plan missed, is measured by widening the capture to that container, and
    the row names it: the location it would otherwise send a reader to is the store's own copy, which
    holds the last PROMOTED value and would report a stale baseline as a finding. Everything else is
    read where it lives, because reading it is the only answer there is.
    """

    RULE = {"artifact": "roster.blindness-rules", "format": 1, "key": "id",
            "kind": "blindness-roster", "no_reach": [],
            "rules": [{"id": "T1", "claim": "c", "mode": "select", "probe": "p", "reason": "r",
                       "source": "s", "blind": [], "trigger_paths": ["src/**"],
                       "sees": ["sweep.entity", "manifest.tooling-tables", "report.diagnostics-log",
                                "report.panel-stats", "report.sum", "report.wall-time",
                                "report.glint-frames", "report.plan", "pin.armor-span"]}]}

    #: `sweep.glint` and `sweep.block` are registered and deliberately outside the rule's reach, so a
    #: pointer into either one's file has a container that exists and is not in the plan - the only
    #: shape that distinguishes "gated by this capture" from "gated by a capture nobody is running".
    #: Three sweeps rather than two, so a family pointer's carriers outnumber the one the plan runs
    #: and a line naming all of them is a different line.
    #:
    #: Every row carries its `kind`, as the promoted index does, because that is the operand deciding
    #: whether a pointer's own node is one the compare joins: a summary object and a provenance field
    #: are written into the same file as the rows and read by nothing the verdict looks at.
    INDEX = {"artifact": "report.oracle-index", "format": 1, "key": "artifact", "kind": "index",
             "artifacts": {
                 "sweep.entity": {"baselined": True, "kind": "sweep-table", "last_duration_ms": 7},
                 "sweep.glint": {"baselined": True, "kind": "sweep-table", "last_duration_ms": 900},
                 "manifest.tooling-tables": {"baselined": True, "kind": "manifest",
                                             "last_duration_ms": 5},
                 "sweep.block": {"baselined": True, "kind": "sweep-table",
                                 "last_duration_ms": 400}},
             "pointers": {
                 "report.diagnostics-log": {"pointer": "manifests/tooling-tables.json#/logs"},
                 "report.panel-stats": {"pointer": "sweeps/<sweep>.json#/rows/<n>/panel"},
                 "report.sum": {"pointer": "sweeps/<sweep>.json#/summary/sum"},
                 "report.wall-time": {"pointer": "<artifact>#/provenance/wall_time_ms"},
                 "report.glint-frames": {"pointer": "sweeps/glint.json#/rows/<n>/frames_delta"}},
             "external": {"report.plan": {"home": "<working root>/_run/plan.json"}},
             "sources": {"pin.armor-span": {"test_class": "lib.minecraft.renderer.engine.kit.ArmorKitTest"}}}

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.store = self.repo / "store"
        write_json(self.store / "blindness.json", self.RULE)
        write_json(self.store / "index.json", self.INDEX)

    def _plan(self, *extra: str) -> tuple[int, str, dict]:
        code, out, _ = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                            "--store", str(self.store), "--quiet", "plan",
                            "--changed", "src/Main.java", *extra])
        payload = json.loads(
            (self.repo / "cache" / "parity" / "current" / store.RUN_DIR / "plan.json").read_text())
        return code, out, payload

    def _row(self, payload: dict, bucket: str, artifact: str) -> dict:
        """The one row of a bucket naming an artifact, so a test asserts a field rather than a list.

        Failing rather than raising, and naming the bucket the split actually chose: every test here
        is about a misclassification, so which of the three it landed in is the answer being asked
        for and a bare lookup error would withhold it.
        """
        found = [row for row in payload[bucket] if row["artifact"] == artifact]
        self.assertEqual(len(found), 1, f"{artifact} is not the one row of `{bucket}`; the split "
                                        f"accounted for it under {self._buckets(payload, artifact)}")
        return found[0]

    @staticmethod
    def _buckets(payload: dict, artifact: str) -> list[str]:
        """Which of the three keys account for an artifact, which is always exactly one of them."""
        return [name for name in ("plan", "covered", "manual")
                if artifact in [row if isinstance(row, str) else row["artifact"]
                                for row in payload[name]]]

    def test_sees_keeps_the_whole_reach_answer(self):
        _, _, payload = self._plan()
        self.assertEqual(payload["sees"],
                         ["manifest.tooling-tables", "pin.armor-span", "report.diagnostics-log",
                          "report.glint-frames", "report.panel-stats", "report.plan", "report.sum",
                          "report.wall-time", "sweep.entity"])

    def test_plan_keeps_only_what_the_store_holds_a_file_for(self):
        """The defect this split exists for: every other id refuses parityCapture at configuration."""
        _, _, payload = self._plan()
        self.assertEqual(payload["plan"], ["manifest.tooling-tables", "sweep.entity"])

    def test_the_plan_line_prints_the_capture_set_and_not_the_reach_answer(self):
        """The one printed line a human copies into `-Partifacts`, and the one the skill and the
        procedures both name as where PLAN is read off.

        The whole line, because every id it drops is still printed elsewhere on the same page: SEES
        names all of them a few lines above, so a check that the line CONTAINS the plan passes just
        as well on a line that also names `pin.armor-span` - which is the id `parityCapture` refuses
        at configuration time, and the entire defect this split exists to fix. The count is inside
        the assertion for the same reason.
        """
        _, out, _ = self._plan()
        self.assertIn("PLAN   (2): manifest.tooling-tables, sweep.entity", out.splitlines())

    def test_a_pointer_whose_container_is_in_the_plan_is_covered(self):
        """A pointer into a file the plan captures, at a node the compare joins: the capture writes
        `logs` and a sweep's rows, and the verdict reports a move in either.

        Both rows keep every container the store holds, not the planned one, because what a row
        carries and what this particular plan runs are two different questions.
        """
        _, _, payload = self._plan()
        self.assertEqual(payload["covered"], [
            {"artifact": "report.diagnostics-log", "containers": ["manifest.tooling-tables"],
             "home": "pointers", "where": "manifests/tooling-tables.json#/logs"},
            {"artifact": "report.panel-stats",
             "containers": ["sweep.block", "sweep.entity", "sweep.glint"],
             "home": "pointers", "where": "sweeps/<sweep>.json#/rows/<n>/panel"},
        ])

    def test_a_placeholder_names_the_family_rather_than_one_file(self):
        """`sweeps/<sweep>.json` is any sweep table, so every registered sweep carries the field."""
        _, _, payload = self._plan()
        self.assertEqual(self._row(payload, "covered", "report.panel-stats")["containers"],
                         ["sweep.block", "sweep.entity", "sweep.glint"])

    def test_a_target_naming_part_of_a_path_names_no_container(self):
        """The file half is a whole store-relative path, matched whole.

        `sweeps/entity` is the artifact's stem and not its file, so it names a file the store does
        not keep. Crediting `sweep.entity` for it - which a match anchored only at the start does -
        answers for a value that registration never pointed at, and answers COVERED, the one verdict
        that tells a reader nothing more is owed.
        """
        index = json.loads(json.dumps(self.INDEX))
        index["pointers"]["report.panel-stats"] = {"pointer": "sweeps/entity#/rows/<n>/panel"}
        write_json(self.store / "index.json", index)
        _, _, payload = self._plan()
        self.assertEqual(self._row(payload, "manual", "report.panel-stats"),
                         {"action": "read", "artifact": "report.panel-stats", "containers": [],
                          "home": "pointers", "where": "sweeps/entity#/rows/<n>/panel"})

    def test_a_covered_row_names_only_the_carrier_this_plan_runs(self):
        """Two of `report.panel-stats`'s three carriers are outside the plan and must not be printed.

        The whole line rather than a prefix of it: a row that also named `sweep.block` and
        `sweep.glint` still starts with the same text, and would send a reader to a capture nobody
        is running.
        """
        _, out, _ = self._plan()
        self.assertIn("  report.panel-stats [pointers] sweeps/<sweep>.json#/rows/<n>/panel"
                      " <- sweep.entity", out.splitlines())

    def _repoint(self, target: str, extra: dict | None = None) -> tuple[str, dict]:
        """Re-register `report.panel-stats` at a target, with any store rows the target needs.

        The one pointer every target-grammar test moves, because it is the registered shape - one
        placeholder standing for a family - and each of these asks what that grammar admits. The
        answer is read off the bucket as well as the container list: a target that over-matches
        lands on a planned carrier and reads COVERED, which is the verdict that tells a reader
        nothing more is owed.

        :param target: the pointer target to register
        :param extra: store-homed rows to add, each carrying the `kind` its join is decided on
        :return: the plan's printed text and its payload
        """
        index = json.loads(json.dumps(self.INDEX))
        index["pointers"]["report.panel-stats"] = {"pointer": target}
        index["artifacts"].update(extra or {})
        write_json(self.store / "index.json", index)
        _, out, payload = self._plan()
        return out, payload

    def test_a_literal_between_two_placeholders_is_part_of_the_path(self):
        """Two placeholders are two names, and what sits between them is path the target named.

        A wildcard that spans from the first `<` to the last `>` swallows that literal, and the
        target then matches every sweep table instead of the one family member whose file really
        carries a `-`. One of those is in the plan, so the row silently becomes COVERED.
        """
        out, payload = self._repoint(
            "sweeps/<sweep>-<part>.json#/rows/<n>/panel",
            {"sweep.entity.rows": {"baselined": True, "kind": "sweep-table", "last_duration_ms": 3}})
        self.assertEqual(self._row(payload, "manual", "report.panel-stats")["containers"],
                         ["sweep.entity.rows"])
        self.assertIn("  report.panel-stats [pointers] sweeps/<sweep>-<part>.json#/rows/<n>/panel"
                      " - capture sweep.entity.rows", out.splitlines())

    def test_a_placeholder_stands_for_a_name_and_never_for_nothing(self):
        """`sweeps/<prefix>entity.json` is the family of prefixed entity sweeps, and the store's own
        `sweeps/entity.json` is not a member of it - the prefix is the thing being stood in for.

        An expansion that also matches the empty string credits that file anyway, and it is the one
        the plan captures, so the row reads as gated by a capture that never carried it.
        """
        _, payload = self._repoint("sweeps/<prefix>entity.json#/rows/<n>/panel")
        row = self._row(payload, "manual", "report.panel-stats")
        self.assertEqual(row["containers"], [])
        self.assertEqual(row["action"], "read")

    def test_a_literal_path_segment_is_matched_as_text_and_not_as_a_pattern(self):
        """An id spells its name with dots where its file spells hyphens, so a target written in the
        id's own spelling names a file the store does not keep.

        That is the realistic authoring slip, and it is exactly the one an unescaped literal hides:
        `.` then matches the `-` and the target is credited with the very artifact whose name it got
        wrong.
        """
        _, payload = self._repoint(
            "manifests/dump.vanilla.json#/files/<n>",
            {"manifest.dump.vanilla": {"baselined": True, "kind": "manifest",
                                       "last_duration_ms": 4}})
        row = self._row(payload, "manual", "report.panel-stats")
        self.assertEqual(row["containers"], [])
        self.assertEqual(row["action"], "read")

    def test_a_row_several_captures_could_measure_names_every_one_of_them(self):
        """A family target is carried by each member, and which member moved is not known here.

        Naming one of them sends a reader to a capture that may not carry the row at all, and the
        line reads as the whole answer either way. The plan is narrowed to the manifest so all three
        sweeps sit outside it, which is the only shape where the difference is visible.
        """
        rule = json.loads(json.dumps(self.RULE))
        rule["rules"][0]["sees"] = ["manifest.tooling-tables", "report.panel-stats"]
        write_json(self.store / "blindness.json", rule)
        _, out, _ = self._plan()
        self.assertIn("  report.panel-stats [pointers] sweeps/<sweep>.json#/rows/<n>/panel"
                      " - capture sweep.block,sweep.entity,sweep.glint", out.splitlines())

    def test_a_container_list_is_ordered_by_id_and_not_by_the_index_it_came_from(self):
        """Order in a written artifact is a determinism property: plan.json is read and diffed.

        The index is re-written here with its sweeps out of id order, which is a form the store's
        own writer never emits because it key-sorts everything it writes. That is exactly why it is
        worth writing by hand: the order this list comes back in must be its own and not one it
        borrowed from the file it was read out of.
        """
        scrambled = json.loads(json.dumps(self.INDEX))
        scrambled["artifacts"] = {name: scrambled["artifacts"][name] for name in
                                  ("sweep.glint", "sweep.entity", "manifest.tooling-tables",
                                   "sweep.block")}
        write_text(self.store / "index.json", json.dumps(scrambled, indent=2))
        _, _, payload = self._plan()
        self.assertEqual(self._row(payload, "covered", "report.panel-stats")["containers"],
                         ["sweep.block", "sweep.entity", "sweep.glint"])

    def test_what_no_plan_member_gates_says_where_it_lives_and_what_would_measure_it(self):
        _, _, payload = self._plan()
        self.assertEqual(payload["manual"], [
            {"action": "read", "artifact": "pin.armor-span", "containers": [], "home": "sources",
             "where": "lib.minecraft.renderer.engine.kit.ArmorKitTest"},
            {"action": "capture", "artifact": "report.glint-frames", "containers": ["sweep.glint"],
             "home": "pointers", "where": "sweeps/glint.json#/rows/<n>/frames_delta"},
            {"action": "read", "artifact": "report.plan", "containers": [], "home": "external",
             "where": "<working root>/_run/plan.json"},
            {"action": "read", "artifact": "report.sum", "containers": [], "home": "pointers",
             "where": "sweeps/<sweep>.json#/summary/sum"},
            {"action": "read", "artifact": "report.wall-time", "containers": [], "home": "pointers",
             "where": "<artifact>#/provenance/wall_time_ms"},
        ])

    def test_a_pointer_at_a_node_the_compare_skips_is_never_covered(self):
        """`provenance` is written onto every captured file and named in the compare's envelope
        exclusion, so no verdict can ever report a move in it.

        `manifest.tooling-tables` and `sweep.entity` are both planned and both carry a provenance
        object, so the container test alone puts this row in COVERED - which says a capture measures
        a value nothing reads. The action is `read` rather than `capture` for the same reason: no
        widening of `-Partifacts` makes a verdict speak about it.
        """
        _, _, payload = self._plan()
        self.assertNotIn("report.wall-time", [row["artifact"] for row in payload["covered"]])
        self.assertEqual(self._row(payload, "manual", "report.wall-time")["action"], "read")

    def test_a_pointer_at_a_member_no_kind_names_is_never_covered(self):
        """The other half of the same property, and the one that is not an envelope key.

        `compare.side_of` builds its rows out of the kind's own rows member and `logs`, so a summary
        object beside them is written into the captured file and read by nothing the verdict looks
        at. Being stored is not being gated.
        """
        _, _, payload = self._plan()
        self.assertNotIn("report.sum", [row["artifact"] for row in payload["covered"]])
        self.assertEqual(self._row(payload, "manual", "report.sum")["action"], "read")

    def test_only_a_pointer_target_is_matched_against_a_store_path(self):
        """Which map an id is registered in decides whether its location is a store path at all.

        `external` holds a filesystem path and `sources` a Java class, each free text in its own
        grammar, and neither can name a container however it reads. Both are spelled here to collide
        with a pointer target on purpose - the collision is the whole test, because the text is not
        evidence about the map it came from, and the hazard is real: the working root mirrors the
        store's own layout file for file, so a home naming a place inside one is spelled exactly like
        a target naming a place inside the other. A home credited with a container it cannot have
        reads as COVERED, which tells a reader a capture measures a value nothing writes.
        """
        index = json.loads(json.dumps(self.INDEX))
        index["external"]["report.plan"] = {"home": "sweeps/<sweep>.json#/rows/<n>/panel"}
        index["sources"]["pin.armor-span"] = {"test_class": "manifests/tooling-tables.json#/logs"}
        write_json(self.store / "index.json", index)
        _, _, payload = self._plan()
        self.assertEqual([row["artifact"] for row in payload["covered"]],
                         ["report.diagnostics-log", "report.panel-stats"])
        for artifact in ("report.plan", "pin.armor-span"):
            self.assertEqual(self._row(payload, "manual", artifact)["containers"], [])
            self.assertEqual(self._row(payload, "manual", artifact)["action"], "read")

    def test_a_pointer_nothing_in_the_plan_carries_names_the_capture_that_would(self):
        """Its home is a STORE file holding the last promoted value, so reading it there reports a
        stale baseline as a finding; widening the capture to the container is what measures it."""
        _, out, _ = self._plan()
        self.assertIn("  report.glint-frames [pointers] sweeps/glint.json#/rows/<n>/frames_delta"
                      " - capture sweep.glint", out.splitlines())

    def test_a_home_no_capture_can_write_is_read_where_it_lives(self):
        """The other kind, and the only one a human answers by hand."""
        _, out, _ = self._plan()
        self.assertIn("  pin.armor-span [sources] lib.minecraft.renderer.engine.kit.ArmorKitTest"
                      " - read it there", out.splitlines())

    def test_the_three_lists_partition_the_reach_answer(self):
        """Nothing is dropped and nothing is counted twice; the plan accounts for all of `sees`."""
        _, _, payload = self._plan()
        accounted = payload["plan"] + [row["artifact"] for row in payload["covered"]] \
            + [row["artifact"] for row in payload["manual"]]
        self.assertEqual(sorted(accounted), payload["sees"])
        self.assertEqual(len(set(accounted)), len(accounted))

    def test_the_remainder_is_printed_rather_than_silent(self):
        """A truncation nobody names reads as 'that was all of it'."""
        _, out, _ = self._plan()
        self.assertIn("COVERED (2)", out)
        self.assertIn("MANUAL (5)", out)
        self.assertIn("pin.armor-span [sources]", out)

    def test_a_gated_pointer_is_never_reported_as_manual(self):
        """A row the verdict already reports told the reader to go and redo the capture's work."""
        _, out, payload = self._plan()
        self.assertNotIn("report.diagnostics-log",
                         out.split("MANUAL")[1] if "MANUAL" in out else "")
        self.assertNotIn("report.diagnostics-log", [row["artifact"] for row in payload["manual"]])

    def test_the_budget_is_what_the_plan_costs(self):
        """Two sweeps are registered and unplanned, so their 1300 ms must not enter the total."""
        _, _, payload = self._plan()
        self.assertEqual(payload["budget_ms"], 12)

    def test_an_index_that_registers_nothing_narrows_nothing(self):
        """Fail wide, never narrow: an empty plan reads downstream as nothing to capture."""
        write_json(self.store / "index.json", {**self.INDEX, "artifacts": {}})
        _, _, payload = self._plan()
        self.assertEqual(payload["plan"], payload["sees"])
        self.assertEqual(payload["covered"], [])
        self.assertEqual(payload["manual"], [])

    def test_an_id_no_map_registers_says_so_rather_than_printing_an_empty_home(self):
        rule = json.loads(json.dumps(self.RULE))
        rule["rules"][0]["sees"] = ["sweep.entity", "report.nowhere"]
        write_json(self.store / "blindness.json", rule)
        _, _, payload = self._plan()
        self.assertEqual(payload["manual"], [
            {"action": "read", "artifact": "report.nowhere", "containers": [],
             "home": "unregistered", "where": ""}])


class BlindLines(unittest.TestCase):
    """The printed answer to "what is blind here", which is the only place a reader meets it.

    The resolver keeps a claim another rule's `sees` overrules instead of dropping it, and the whole
    value of that is one printed marker. So the line is asserted WHOLE and by equality: a check that
    the printout mentions the artifact passes just as well on the shape this replaced, where a rule
    whose entire content was one blind line printed nothing at all.

    Two lines rather than one, because the marker is what the two states differ by and a test holding
    only the marked one cannot tell "always marked" from "marked when overruled".
    """

    UNCONTESTED = "The dump serialises loaded data and never renders."
    OVERRULED = "PipelineParityDump never calls a renderer entry point."

    #: T1 claims both artifacts blind. T2 and T3 select one of them on the same path, so that claim is
    #: overruled and the other is not - the two states side by side under one rule, which is the pair
    #: the marker has to tell apart. Two selectors, so the join between their ids is pinned as well.
    RULE = {"artifact": "roster.blindness-rules", "format": 1, "key": "id",
            "kind": "blindness-roster", "no_reach": [],
            "rules": [{"id": "T1", "claim": "c", "mode": "select", "probe": "p",
                       "reason": OVERRULED, "source": "s", "sees": [],
                       "blind": ["sweep.entity", "sweep.block"], "trigger_paths": ["src/**"]},
                      {"id": "T2", "claim": "c", "mode": "select", "probe": "p",
                       "reason": UNCONTESTED, "source": "s", "sees": ["sweep.entity"], "blind": [],
                       "trigger_paths": ["src/**"]},
                      {"id": "T3", "claim": "c", "mode": "select", "probe": "p",
                       "reason": UNCONTESTED, "source": "s", "sees": ["sweep.entity"], "blind": [],
                       "trigger_paths": ["src/**"]}]}

    INDEX = {"artifact": "report.oracle-index", "format": 1, "key": "artifact", "kind": "index",
             "artifacts": {"sweep.entity": {"baselined": True, "kind": "sweep-table"}},
             "pointers": {}, "external": {}, "sources": {}}

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.store = self.repo / "store"
        write_json(self.store / "blindness.json", self.RULE)
        write_json(self.store / "index.json", self.INDEX)

    def _lines(self) -> list[str]:
        _, out, _ = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                         "--store", str(self.store), "--quiet", "plan",
                         "--changed", "src/Main.java"])
        return out.splitlines()

    def test_an_overruled_claim_prints_the_contradiction_and_the_rules_that_made_it(self):
        self.assertIn(f"BLIND  sweep.entity [T1] claimed blind, selected by T2, T3 - {self.OVERRULED}",
                      self._lines())

    def test_an_uncontested_claim_prints_the_reason_and_no_marker(self):
        """The marker is the exception, so a reader meets it only where there is a contradiction."""
        self.assertIn(f"BLIND  sweep.block [T1] {self.OVERRULED}", self._lines())

    def test_the_overruled_artifact_is_still_in_the_bundle(self):
        """Reporting rather than demoting: what the marker corrects is the ANSWER, not the plan."""
        _, _, _ = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                       "--store", str(self.store), "--quiet", "plan", "--changed", "src/Main.java"])
        payload = json.loads(
            (self.repo / "cache" / "parity" / "current" / store.RUN_DIR / "plan.json").read_text())
        self.assertEqual(payload["plan"], ["sweep.entity"])


class RegisteredPointers(unittest.TestCase):
    """The store's own pointer registry, resolved against the join the compare actually performs.

    Read out of the promoted index rather than restated, because the claim being checked is about
    THOSE entries. A pointer is not carried by a container because a capture writes that file - it is
    carried when a verdict can report a move in the node, and `compare.side_of` reaches the kind's
    own rows member and `logs` and nothing else. A target under `summary` or under `provenance` is
    written by the same capture and read by no comparison, and telling a reader it is covered says a
    gate speaks for a value no gate reads.
    """

    def setUp(self):
        home = store.repo_root() / store.PRODUCTION
        index = json.loads((home / "index.json").read_text(encoding="utf-8"))
        self.stored = index["artifacts"]
        self.targets = {name: entry["pointer"] for name, entry in index["pointers"].items()}

    def _carried(self, artifact: str) -> list[str]:
        return cli._pointer_containers(self.targets[artifact], self.stored)

    def _under(self, member: str) -> list[str]:
        """The registered pointers whose target's first JSON-pointer segment is this member."""
        return sorted(name for name, target in self.targets.items()
                      if target.partition("#")[2].strip("/").split("/")[0] == member)

    def test_a_pointer_under_provenance_is_carried_by_nothing(self):
        """`provenance` is on every captured file and in the compare's envelope exclusion.

        Every one of these resolves to a container the store really keeps - `<artifact>` matches all
        of them - so the file half alone would call each one covered on any plan at all.
        """
        under = self._under("provenance")
        self.assertNotEqual(under, [], "the registry has no provenance-targeted pointer left, so "
                                       "this check has no operand and needs a different shape")
        for artifact in under:
            self.assertEqual(self._carried(artifact), [], artifact)

    def test_the_registry_splits_into_what_a_verdict_reports_and_what_it_does_not(self):
        """The whole registry, so a new entry has to be classified rather than defaulting.

        The joined half is the rows member of a sweep and a manifest's log digests; the rest is the
        two members no join reads. Both halves are named because either one being wrong is a wrong
        answer: a joined pointer in the second half sends a reader to redo the capture's work, and an
        unjoined one in the first tells them nothing is owed.
        """
        joined = sorted(name for name in self.targets if self._carried(name))
        self.assertEqual(joined, ["report.canvas-mismatch", "report.diagnostics-log",
                                  "report.failure-rows", "report.glint-frames",
                                  "report.panel-stats"])
        self.assertEqual(sorted(set(self.targets) - set(joined)),
                         ["report.buckets", "report.coverage-gaps", "report.harness-sweep-counts",
                          "report.run-provenance", "report.sum", "report.wall-time",
                          "report.worst-list"])

    def test_the_unjoined_half_still_names_a_file_the_store_keeps(self):
        """What separates the two halves is the join and not the file, so say so with the file.

        Each of these resolves to at least one real container by its path, and is still carried by
        none of them. Without this the split above would also pass on a registry whose second half
        pointed at files that simply do not exist.
        """
        for artifact in sorted(name for name in self.targets if not self._carried(name)):
            head = self.targets[artifact].partition("#")[0]
            self.assertNotEqual(cli._pointer_files(head, self.stored), [], artifact)


class GateExit(unittest.TestCase):
    """`plan --gate-exit`'s tri-state, which is the pre-commit hook's entire predicate.

    A real git repository rather than a mock, because the branch that matters is the one that
    SILENCES the hook: if it answered 'already gated' wrongly the gate would simply stop firing, and
    a stubbed sha would not have exercised the comparison that decides it.
    """

    RULE = {"artifact": "roster.blindness-rules", "format": 1, "key": "id",
            "kind": "blindness-roster",
            "no_reach": [{"glob": "docs/**", "reason": "r", "probe": "p"}],
            "rules": [{"id": "T1", "claim": "c", "mode": "select", "probe": "p", "reason": "r",
                       "source": "s", "sees": ["sweep.entity"], "blind": [],
                       "trigger_paths": ["src/**"]}]}

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.store = self.repo / "store"
        write_json(self.store / "blindness.json", self.RULE)
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)
        write_text(self.repo / "seed.txt", "seed\n")
        # The working root is under cache/ and cache/ is ignored, exactly as in the real repo. That
        # is load-bearing rather than tidy: `git status --porcelain` lists untracked files, so
        # without it WRITING the verdict would change the very digest the verdict records, and the
        # gate could never answer "already gated" even once.
        write_text(self.repo / ".gitignore", "cache/\nstore/\n")
        subprocess.run(["git", "add", "-A"], cwd=self.repo, check=True)
        subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t",
                        "commit", "-qm", "seed"], cwd=self.repo, check=True)

    def _plan(self, *changed: str) -> int:
        argv = ["--repo-root", str(self.repo), "--root", "cache/parity/current",
                "--store", str(self.store), "--quiet", "plan", "--gate-exit"]
        for path in changed:
            argv += ["--changed", path]
        return run(argv)[0]

    def _record_verdict(self, artifacts: list[str], **overrides) -> None:
        payload = {"artifacts": artifacts,
                   "asset_dirty_digest": provenance.dirty_digest(self.repo),
                   "asset_sha": provenance.asset_state(self.repo)["asset_sha"]}
        payload.update(overrides)
        write_json(self.repo / "cache" / "parity" / "current" / store.RUN_DIR
                   / "last-verdict.json", payload)

    def test_0_when_nothing_sees_the_change(self):
        self.assertEqual(self._plan("docs/readme.md"), cli.OK)

    def test_10_when_seen_and_no_verdict_exists(self):
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def test_20_when_a_verdict_covers_this_exact_tree(self):
        self._record_verdict(["sweep.entity"])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_10_again_once_the_tree_moves(self):
        """The whole reason the digest exists: same commit, different bytes."""
        self._record_verdict(["sweep.entity"])
        write_text(self.repo / "seed.txt", "edited\n")
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def test_10_when_the_verdict_covered_fewer_artifacts_than_the_plan_needs(self):
        self._record_verdict(["sweep.block"])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def test_20_when_the_verdict_covered_more(self):
        """Subset, never equality - a wider previous compare still covers this one."""
        self._record_verdict(["sweep.block", "sweep.entity"])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_10_when_the_recorded_sha_is_null(self):
        """An unreadable git is not evidence of having been gated; it re-arms."""
        self._record_verdict(["sweep.entity"], asset_sha=None)
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def _reach_wider_than_the_plan(self, home: dict) -> None:
        """Re-arm the store so the change's reach names an id no capture can ever produce.

        :param home: the index maps registering that id, which decide where its value lives
        """
        rule = json.loads(json.dumps(self.RULE))
        rule["rules"][0]["sees"] = ["sweep.entity", "the.other-one"]
        write_json(self.store / "blindness.json", rule)
        write_json(self.store / "index.json",
                   {"artifact": "report.oracle-index", "format": 1, "key": "artifact",
                    "kind": "index", "artifacts": {"sweep.entity": {"baselined": True}}, **home})

    def test_20_when_the_verdict_covers_the_plan_and_the_reach_names_more(self):
        """A verdict records what a COMPARE covered, and a compare covers what a capture produced.

        Measured against reach instead, the subset can never hold once reach names an id no producer
        writes - and the hook then asks on every commit touching such a path, forever, which is the
        one property that stops it firing again on a tree already gated.
        """
        self._reach_wider_than_the_plan(
            {"pointers": {"the.other-one": {"pointer": "sweeps/entity.json#/summary/sum"}}})
        self._record_verdict(["sweep.entity"])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_20_when_the_reach_names_something_a_human_reads_by_hand(self):
        """The same, for the home that can never be carried by any capture at all."""
        self._reach_wider_than_the_plan(
            {"sources": {"the.other-one": {"test_class": "lib.minecraft.renderer.KitTest"}}})
        self._record_verdict(["sweep.entity"])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_10_when_the_verdict_misses_a_plan_member_even_so(self):
        """Narrowing to the plan does not weaken it: every capturable id must still be covered."""
        self._reach_wider_than_the_plan(
            {"pointers": {"the.other-one": {"pointer": "sweeps/entity.json#/summary/sum"}}})
        self._record_verdict(["sweep.block"])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def test_the_flag_is_opt_in_so_parityPlan_still_exits_zero(self):
        """parityPlan is a Gradle Exec: a plan that answered 10 on reach would fail every build."""
        code, _, _ = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                          "--store", str(self.store), "--quiet", "plan",
                          "--changed", "src/Main.java"])
        self.assertEqual(code, cli.OK)


if __name__ == "__main__":
    unittest.main()
