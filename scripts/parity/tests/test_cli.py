"""The exit-code table, the stdout contract, and the spellings that must stay deleted."""

from __future__ import annotations

import contextlib
import io
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from parity import capture, cli, pixels, promote, provenance, store
from parity.norm import read_json, read_text, write_json, write_text

DATA = Path(__file__).resolve().parent / "data"


def register(store_root: Path, *artifacts: str, floor: int = 1, **columns) -> None:
    """Declare a floor per artifact in a fixture store's index, and whatever else is asked for.

    The store is where an artifact's determinism floor is declared, so a fixture store carrying no
    index answers nothing about one and every capture and promotion of it refuses. It is the
    registration half of coining an artifact, and it happens before the first capture.

    :param store_root: the fixture production store
    :param artifacts: the ids to declare
    :param floor: the floor to declare for each
    :param columns: any further row columns, given to every one of them
    """
    write_json(store_root / "index.json", {"artifacts": {
        name: {promote.FLOOR_FIELD: floor, **columns} for name in artifacts}})


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
        """A merge that resurrects one of these is visible rather than silently accepted.

        `--include-stale` is here rather than in the parser because the refusal it overrode - an
        artifact captured before the inputs it was taken from - was never built, so the flag
        advertised a defence that did not exist and admitted every stale artifact anyway.
        """
        for flag in ("--slot", "--store-root", "--production", "--against", "--expect",
                     "--determinism-runs", "--dry-run", "--include-stale"):
            code, _, _ = run(["doctor", flag, "x"])
            self.assertEqual(code, cli.USAGE, flag)

    def test_the_retired_per_command_flags_are_rejected_where_they_used_to_be_typed(self):
        """Each was registered and read by no code path, and one named the existing default.

        Typed at the subcommand that took it, because that is the only place a subcommand option is
        a usage error at all - a global sweep answers USAGE for every one of them whether or not the
        parser still registers it. The pair on `sum` and `buckets` is the shape that hides best:
        `--base` parsed, bound a namespace attribute and pointed at a store neither command reads.
        """
        repo = Path(tempfile.mkdtemp())
        for argv in (["json", "canonicalize", "x.json", "--in-place"],
                     ["json", "semantic-diff", "--before", "a", "--after", "b", "--axis-rows"],
                     ["manifest", "export", "--artifact", "sweep.entity", "--grammar", "a"],
                     ["render-bytes", "diff", "--before", "a", "--after", "b",
                      "--artifact", "java.png"],
                     ["sum", "--base", "x"], ["buckets", "--base", "x"],
                     ["capture-index", "--producer", "test"],
                     ["capture-index", "--flag", "a=b"], ["capture-index", "--runs", "2"]):
            code, _, _ = run(["--repo-root", str(repo), "--root", "cache/parity/current",
                              "--quiet", *argv])
            self.assertEqual(code, cli.USAGE, argv)

    def test_the_compare_parser_rejects_the_deleted_staleness_flag(self):
        """The per-command half of the check above, which that one cannot make.

        `doctor --include-stale` is a usage error whether or not `compare` accepts the flag, so a
        subcommand option has to be typed at the subcommand that used to take it. Nothing else here
        would notice it being registered again.
        """
        repo = Path(tempfile.mkdtemp())
        code, _, _ = run(["--repo-root", str(repo), "--root", "cache/parity/current", "--quiet",
                          "compare", "--include-stale"])
        self.assertEqual(code, cli.USAGE)

    def test_version(self):
        code, out, _ = run(["--version"])
        self.assertEqual(code, 0)
        self.assertIn("parity", out)


class SumAndBucketsReadTheWorkingRoot(unittest.TestCase):
    """Both commands are defined over the store and neither could read one.

    With no `--from` they resolve `sweeps/<name>.json` out of the working root and handed that JSON
    to the TSV reader, which fails on its first character - so both exited 3 against a fully
    populated root and the only operand that worked was a raw producer directory.

    Both operand trees are driven here, because which reader answers is decided by which was named
    and the producer tree is the branch that already worked: a second reader wired to the wrong
    branch trades one broken operand for the other. The named-sweep refusals go with them - the
    operands are what a root and a producer tree have in common, and each refusal is the answer to a
    different mistake.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        write_json(self.root / "sweeps" / "entity.json", self._stored())

    @staticmethod
    def _stored(subject: str = "minecraft__cow", artifact_id: str = "sweep.entity") -> dict:
        return {"artifact": artifact_id, "format": 1, "key": "subject", "kind": "sweep-table",
                "rows": [{"subject": subject, "mean_argb_delta": "0.2000", "status": "ok"},
                         {"subject": "minecraft__pig", "mean_argb_delta": "1.5000", "status": "ok"},
                         {"subject": "minecraft__bat", "mean_argb_delta": "", "status": "failed"}]}

    def _run(self, *argv: str) -> tuple[int, str]:
        code, out, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                              "--format", "json", *argv])
        return code, out + err

    def test_sum_reads_the_stored_rows(self):
        code, out = self._run("sum")
        self.assertEqual(code, cli.OK, out)
        self.assertEqual(json.loads(out)["sums"],
                         [{"buckets": {"<0.25": 1, "<0.50": 1, "<0.75": 1, "<1.00": 1,
                                       "failed": 1, "total": 3},
                           "failed": 1, "rows": 3, "sum": "1.7000", "sweep": "entity"}])

    def test_buckets_reads_the_stored_rows(self):
        code, out = self._run("buckets")
        self.assertEqual(code, cli.OK, out)
        self.assertEqual(json.loads(out)["buckets"][0]["buckets"]["<0.25"], 1)

    def test_a_root_holding_no_sweep_says_so_rather_than_reading_a_TSV(self):
        code, out = self._run("sum")  # primed, then emptied below
        self.assertEqual(code, cli.OK, out)
        (self.root / "sweeps" / "entity.json").unlink()
        self.assertEqual(self._run("sum")[0], cli.MISSING_INPUT)

    def test_a_named_sweep_is_the_only_one_read(self):
        """The operand is the whole point of naming one: a bare command answers for the tree, and
        this one is what an operator types to ask about a single sweep of six."""
        write_json(self.root / "sweeps" / "block.json",
                   self._stored("minecraft__stone", "sweep.block"))
        code, out = self._run("sum", "block")
        self.assertEqual(code, cli.OK, out)
        self.assertEqual([row["sweep"] for row in json.loads(out)["sums"]], ["block"])

    def test_a_name_outside_the_six_is_refused_as_a_name_and_not_as_an_absence(self):
        """A typo and an uncaptured sweep are two different answers, and the roster is printed with
        the first: folded into the second, `entities` reads as a sweep this root has yet to capture
        and an operator goes looking for the run that would write it."""
        code, out = self._run("sum", "entities")
        self.assertEqual(code, cli.MISSING_INPUT, out)
        self.assertIn("unknown sweep(s) ['entities']", out)

    def test_one_of_the_six_this_root_does_not_hold_is_refused_by_name(self):
        code, out = self._run("sum", "block")
        self.assertEqual(code, cli.MISSING_INPUT, out)
        self.assertIn("no table for sweep(s) ['block']", out)

    def test_the_from_operand_is_read_as_a_producers_TSV(self):
        """`--from` names the pre-store form and the root names the canonical JSON, so the reader is
        chosen by which was given. One reader for both cannot work - each fails on the other's first
        character - and this is the branch that was the only one that ever worked."""
        producer = self.repo / "producer" / "entity-parity-vanilla" / "parity-report.tsv"
        producer.parent.mkdir(parents=True)
        producer.write_bytes(b"subject\tmean_argb_delta\tstatus\nminecraft__cow\t0.2000\tok\n")
        code, out = self._run("sum", "--from", str(self.repo / "producer"))
        self.assertEqual(code, cli.OK, out)
        self.assertEqual(json.loads(out)["sums"][0]["sum"], "0.2000")


class CanonicalizeRefusesOneDestinationForManyOperands(unittest.TestCase):
    """`--out` resolves inside the operand loop, so N-1 results were discarded and N reported."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        for name, payload in (("a.json", {"b": 2, "a": 1}), ("b.json", {"z": 9})):
            write_json(self.tmp / name, payload)

    def _canonicalize(self, *argv: str) -> tuple[int, str]:
        code, out, err = run(["--quiet", *argv])
        return code, out + err

    def test_two_operands_and_one_out_is_refused_before_anything_is_written(self):
        target = self.tmp / "joined.json"
        code, text = self._canonicalize("--out", str(target), "json", "canonicalize",
                                        str(self.tmp / "a.json"), str(self.tmp / "b.json"))
        self.assertEqual(code, cli.REFUSED, text)
        self.assertIn("one destination holds one result", text)
        self.assertFalse(target.exists())

    def test_one_operand_and_one_out_still_writes(self):
        target = self.tmp / "one.json"
        code, text = self._canonicalize("--out", str(target), "json", "canonicalize",
                                        str(self.tmp / "a.json"))
        self.assertEqual(code, cli.OK, text)
        self.assertEqual(read_json(target), {"a": 1, "b": 2})

    def test_two_operands_and_no_out_canonicalizes_both_in_place(self):
        code, text = self._canonicalize("json", "canonicalize",
                                        str(self.tmp / "a.json"), str(self.tmp / "b.json"))
        self.assertEqual(code, cli.OK, text)
        self.assertEqual(read_text(self.tmp / "a.json").splitlines()[1].strip(), '"a": 1,')


class ProgressGoesToStderrAndQuietSuppressesIt(unittest.TestCase):
    """`--quiet` is documented as suppressing progress and is what the pre-commit hook passes.

    Nothing called the writer, so no command emitted a line and the flag suppressed nothing. This is
    the capture leg of the documented mechanism, and the class below is the compare and the
    promotion. A capture is driven from a fixture of its own because it is the one of the three
    whose operand is a producer's raw output rather than the stored form.
    """

    ARTIFACT = "digest.shipped-tables"

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        register(self.repo / store.PRODUCTION, self.ARTIFACT)

    def _capture(self, *argv: str) -> tuple[str, str]:
        run(["--repo-root", str(self.repo), "--root", "cache/parity/current", "capture-begin"])
        write_json(self.root / store.path_of(self.ARTIFACT),
                   {"artifact": self.ARTIFACT, "format": 1, "key": "name", "kind": "digest-set",
                    "digests": {"block_models": {"sha256": "a"}}})
        code, out, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                              *argv, "capture-normalize", "--artifact", self.ARTIFACT,
                              "--source", str(self.root)])
        self.assertEqual(code, cli.OK, err)
        return out, err

    def test_it_names_the_artifact_on_stderr(self):
        out, err = self._capture()
        self.assertIn(f"capture 1/1 {self.ARTIFACT}", err.splitlines())
        self.assertNotIn("capture 1/1", out)

    def test_quiet_suppresses_it(self):
        self.assertNotIn(f"capture 1/1 {self.ARTIFACT}", self._capture("--quiet")[1])

    def test_quiet_leaves_a_warning_where_it_is(self):
        """The flag names progress and nothing else. A provenance field that could not be read is a
        warning about the record being written, and hiding it under a verbosity flag is how a
        capture stamps a null nobody was told about."""
        warning = "recording null mc_version"
        self.assertIn(warning, self._capture()[1])
        self.assertIn(warning, self._capture("--quiet")[1])


class TheCompareAndThePromotionEmitProgressToo(unittest.TestCase):
    """The other two commands that loop over a set, and the two an operator waits on.

    The documented mechanism is a line per artifact from `capture-normalize`, `compare` and
    `promote-apply`. Pinning one of the three leaves the other two call sites deletable with every
    suite green, which is the shape of the finding that put the writer here in the first place: a
    documented behaviour with no producer. The root holds two artifacts rather than one, because
    `1/1` says nothing about the counter that tells an operator how much of a bundle is left, and
    one of the two moved so the promotion has both of its actions to name.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        self.store = self.repo / "store"
        register(self.store, "manifest.fluid", "sweep.entity", floor=2)
        capture.begin(self.root)
        write_json(self.root / "sweeps" / "entity.json", self._sweep("1.0000"))
        write_json(self.store / "sweeps" / "entity.json", self._sweep("2.0000"))
        for under in (self.root, self.store):
            write_json(under / "manifests" / "fluid.json", self._manifest())
        capture.index(self.root)

    def _run(self, *argv: str) -> tuple[int, str, str]:
        return run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                    "--store", str(self.store), *argv])

    @staticmethod
    def _lines(stream: str, command: str) -> list[str]:
        return [line for line in stream.splitlines() if line.startswith(command + " ")]

    def test_the_compare_names_every_artifact_it_reads(self):
        code, out, err = self._run("compare")
        self.assertEqual(code, cli.DIFFERENCES, err)
        self.assertEqual(["compare 1/2 manifest.fluid", "compare 2/2 sweep.entity"],
                         self._lines(err, "compare"))
        self.assertEqual([], self._lines(out, "compare"))

    def test_quiet_suppresses_the_compare_lines(self):
        self.assertEqual([], self._lines(self._run("--quiet", "compare")[2], "compare"))

    def test_the_promotion_names_every_artifact_and_what_it_would_do(self):
        self._run("compare")
        code, out, err = self._run("promote-apply", "--reason", "probe")
        self.assertEqual(code, cli.OK, err)
        self.assertEqual(["promote 1/2 manifest.fluid (unchanged)",
                          "promote 2/2 sweep.entity (replace)"], self._lines(err, "promote"))
        self.assertEqual([], self._lines(out, "promote"))

    def test_quiet_suppresses_the_promotion_lines(self):
        self._run("compare")
        self.assertEqual([], self._lines(
            self._run("--quiet", "promote-apply", "--reason", "probe")[2], "promote"))

    @staticmethod
    def _sweep(delta: str) -> dict:
        """One sweep row, distinguishable by the delta it carries."""
        return {"artifact": "sweep.entity", "format": 1, "key": "subject", "kind": "sweep-table",
                "provenance": {"asset_dirty": False, "counts": {"failed": 0, "rows": 1},
                               "determinism_runs": 2},
                "rows": [{"subject": "minecraft__cow", "mean_argb_delta": delta}]}

    @staticmethod
    def _manifest() -> dict:
        """One manifest row, written identically on both sides so its entry stays `unchanged`."""
        return {"artifact": "manifest.fluid", "files": [{"path": "a.gif", "sha256": "0" * 64}],
                "format": 1, "key": "path", "kind": "manifest",
                "provenance": {"asset_dirty": False, "counts": {"failed": 0, "files": 1},
                               "determinism_runs": 2, "root": "cache/x"}}


class TheWallTimeReachesTheStampedRecord(unittest.TestCase):
    """The plan's budget, the cost column and the background-the-capture rule all read this field.

    `provenance.gather` accepted it from the first commit and no caller passed one, so the budget
    summed absent keys, every cost cell rendered a dash and the rule that says to background a
    capture above 110 s could never fire.
    """

    ARTIFACT = "digest.shipped-tables"

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        register(self.repo / store.PRODUCTION, self.ARTIFACT)

    def _provenance(self, *argv: str) -> dict:
        run(["--repo-root", str(self.repo), "--root", "cache/parity/current", "capture-begin"])
        write_json(self.root / store.path_of(self.ARTIFACT),
                   {"artifact": self.ARTIFACT, "format": 1, "key": "name", "kind": "digest-set",
                    "digests": {"block_models": {"sha256": "a"}}})
        code, _, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                            "--quiet", "capture-normalize", "--artifact", self.ARTIFACT,
                            "--source", str(self.root), *argv])
        self.assertEqual(code, cli.OK, err)
        return read_json(self.root / store.path_of(self.ARTIFACT))["provenance"]

    def test_it_is_stamped_as_the_number_the_build_measured(self):
        self.assertEqual(self._provenance("--wall-time", "152000")["wall_time_ms"], 152000)

    def test_an_absent_one_stamps_no_key_rather_than_a_zero(self):
        """A zero would be summed by the budget as a free artifact, which is a wrong answer where
        an omitted key is the honest 'nobody measured this'."""
        self.assertNotIn("wall_time_ms", self._provenance())


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


class OutNeverWritesProduction(unittest.TestCase):
    """`--out` is refused inside the production store, on every command that honours it.

    The guard existed on one path - the one that redirects stdout - and every command that builds a
    body of its own and writes it reached a baseline through a bare write. Nothing went red because
    the design listed the coverage for this as shipped and no such case was ever written, which is
    precisely how the unguarded siblings landed.

    The roster below is typed out, which is what went stale before, so it does not carry the whole
    claim on its own: a case beside it reads `cli.py` and fails on any line answering its scan -
    a `Path(` construction beside the attribute, or the attribute bound to a name - outside the one
    function that guards it. The roster proves the guard fires on every command it names; the scan
    reads the two shapes a new writer's own path would be spelled in, and neither the write-throughs
    the roster below names nor a bypass that never spells the attribute at all.

    Every command is driven through `main`, so what is asserted is the exit code and the directory,
    not a predicate in isolation: `ReadOnlyStore.contains` is not what any of them went through.
    """

    #: The two shapes the scan answers for, which is what it can see and not the closed set of ways
    #: `--out` becomes a path: a construction anywhere on the line that names the attribute, and the
    #: attribute bound to a name, which puts the construction one line further on where nothing
    #: looking for `args.out` reaches it. A presence test on it is neither. Both arms are
    #: approximations in both directions, and the roster below is where each way they miss is named.
    ARMS = (r"Path\(", r"=\s*args\.out\b")

    #: The arms as the one pattern the predicate runs, so narrowing either is narrowing the scan.
    BUILDS_A_PATH = re.compile("|".join(ARMS))

    #: Lines the predicate has to answer for, each paired with the answer. Written here rather than
    #: read out of `cli.py`, because the file holds no binding: that arm was added for a shape that
    #: had not landed yet and nothing in the corpus can demonstrate it, so narrowing the pattern
    #: back to the construction alone left every case green and the bypass unwatched again. The rows
    #: answering False carry as much as the ones answering True: the three write-throughs are real
    #: bypasses this scan does not see - which is what the writer roster below is for, driving every
    #: command through `main` whatever its lines are spelled like - and a false row is also what
    #: stops a pattern matching every line from satisfying the true ones. The commented construction
    #: is the scan erring the other way, flagging a line that names the attribute nowhere but a
    #: comment, which costs a reader a look and never a missed bypass.
    LINE_SHAPES = (("    target = Path(args.out)", True),
                   ("    target = args.out", True),
                   ("    out = args.out  # bound now, constructed later", True),
                   ("    target = Path(default)  # never args.out", True),
                   ("    if args.out:", False),
                   ("    target = Path(default)", False),
                   ("    write_text(args.out, body)", False),
                   ("    target = self.root / args.out", False),
                   ('    with open(args.out, "w") as fh:', False))

    #: Every command that resolves `--out` into a path, by the name a failure has to print to be
    #: diagnosable. `doctor` redirects its answer through `_emit`; the rest build their own body.
    #: The two `lab` writers are here only when Pillow and numpy are, because the group is not
    #: registered at all without them - and the rest must stay measurable on a bare interpreter.
    def _writers(self) -> dict[str, list[str]]:
        writers = {
            "doctor": ["doctor"],
            "manifest export": ["manifest", "export", "--artifact", "manifest.fluid"],
            "json canonicalize": ["json", "canonicalize", str(self.source)],
            "report render": ["report", "render", "--in", str(self.source)],
        }
        if pixels.available():
            writers["lab census"] = [
                "lab", "census", "--allpass", str(self.dump), "--raw", str(self.dump),
                "--landed", str(self.dump), "--vanilla", str(self.vanilla), "--java", str(self.java)]
            writers["lab crop"] = [
                "lab", "crop", "--vanilla", str(self.vanilla), "--java", str(self.java),
                "--region", "0,0,1,1"]
        return writers

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.store = self.repo / "store"
        self.source = self.repo / "source.json"
        write_json(self.source, {"artifact": "sweep.entity", "format": 1, "key": "subject",
                                 "kind": "sweep-table", "rows": []})
        write_json(self.store / "manifests" / "fluid.json",
                   {"artifact": "manifest.fluid", "format": 1, "key": "path", "kind": "manifest",
                    "files": [{"path": "a.gif", "sha256": "0" * 64}],
                    "provenance": {"root": "cache/x"}})
        # The lab pair reads pixels and a `[PX]` dump. One WRITE fragment over the 2x2 fixture is
        # the smallest input either accepts, and the three dumps a census joins are the same file:
        # it zips them position by position, so one row three times aligns with itself.
        self.vanilla, self.java = self.repo / "vanilla.png", self.repo / "java.png"
        for target, name in ((self.vanilla, "vanilla"), (self.java, "java")):
            target.write_bytes((DATA / f"pixels-2x2-{name}.png").read_bytes())
        self.dump = self.repo / "px.log"
        write_text(self.dump, "\t".join(
            ["[PX]", "WRITE", "0", "0", "0.5", "tag_a", "0.0", "0.0", "0", "0", "FFFFFFFF",
             "FFFFFFFF", "FFFFFFFF", "1.0", "FFFF0000", "NORMAL", "FFFF0000"]) + "\n")

    def test_no_command_builds_its_own_path_out_of_out(self):
        """The roster above cannot name a writer nobody added to it, and that is how this landed.

        `--out` is a global, so the parser cannot be asked who honours it. What can be asked is
        which lines answer the scan, and two shapes do: a construction over the attribute, which is
        the edit each unguarded sibling made, and a binding of it to a name, which is the same edit
        spread over two lines and invisible to a scan for the first alone. A write-through that does
        neither - the attribute handed straight to a writer, joined under a root or opened - is not
        answered at all, and is what the roster of commands below is there to catch instead.
        """
        source = Path(cli.__file__).read_text(encoding="utf-8")
        constructions = [line.strip() for line in source.splitlines()
                         if self._builds_a_path(line)]
        self.assertEqual(constructions, ["target = Path(args.out)"],
                         "a command building its own path out of --out bypasses the guard")

    def test_the_scan_answers_for_a_binding_and_not_only_a_construction(self):
        """`cli.py` holds one of the two shapes, so the corpus cannot say what the other does.

        The case above reads a file where every honoured `--out` goes through one construction, and
        it stays green whether the binding arm is there or not. That is the arm added for the edit
        that spreads a construction over two lines, which is invisible to a scan for `Path(` and is
        how an unguarded writer would land next. Driven against typed lines, because no line in the
        corpus tells the widened pattern and the narrow one apart.
        """
        for line, builds in self.LINE_SHAPES:
            self.assertEqual(self._builds_a_path(line), builds, line)

    def test_every_arm_of_the_scan_has_a_line_the_roster_would_lose_it_over(self):
        """The roster is the whole driver of both arms, so it has to be asked whether it drives them.

        The case above loops it and asserts nothing when it is empty, and asserts nothing about one
        arm when it holds only rows the other already answers - which is the state the corpus is in
        and the reason the roster is typed out at all. Either way the pattern can then be narrowed
        back to one arm with both suites green, which is the edit this asserts against directly: the
        arm is dropped, the predicate re-run over the roster, and some line has to answer
        differently for the arm to have been carrying anything.
        """
        for dropped in self.ARMS:
            kept = tuple(arm for arm in self.ARMS if arm != dropped)
            self.assertTrue([line for line, _ in self.LINE_SHAPES
                             if self._builds_a_path(line) != self._answers(line, kept)],
                            f"no line of the roster answers differently without {dropped}")

    def _builds_a_path(self, line: str) -> bool:
        """Whether one source line turns `--out` into a path, which is the whole scan predicate.

        :param line: one line of source, indentation and comment included
        :return: whether it names the attribute and either constructs over it or binds it
        """
        return "args.out" in line and self.BUILDS_A_PATH.search(line) is not None

    @staticmethod
    def _answers(line: str, arms: tuple[str, ...]) -> bool:
        """The same predicate over a narrowed set of arms, which is the mutation being guarded.

        :param line: one line of source, indentation and comment included
        :param arms: the arms to run, an empty set answering no line
        :return: whether it names the attribute and any of ``arms`` matches it
        """
        return "args.out" in line and any(re.search(arm, line) for arm in arms)

    def _run(self, argv: list[str], out: Path) -> tuple[int, str]:
        code, _, err = run(["--repo-root", str(self.repo), "--store", str(self.store),
                            "--out", str(out)] + argv)
        return code, err

    def test_the_roster_names_writers_to_drive(self):
        """Every case below loops over it, so an emptied roster passes all four with nothing run.

        The optional pair is asserted against the condition that admits it rather than against a
        total: on an interpreter without Pillow the group is not registered at all, and a roster
        naming a command the parser does not have fails for the wrong reason.
        """
        writers = self._writers()
        self.assertTrue(writers, "the roster names no writer, so every case over it is vacuous")
        self.assertEqual("lab crop" in writers, pixels.available())

    def test_every_writer_refuses_a_path_inside_the_store(self):
        target = self.store / "sweeps" / "entity.json"
        for name, argv in self._writers().items():
            code, err = self._run(argv, target)
            self.assertEqual(code, cli.REFUSED, f"{name} did not refuse")
            self.assertIn("--out would write inside the production store", err, name)

    def test_no_refused_writer_leaves_a_byte_behind(self):
        """The exit code alone would pass on a command that wrote the file and then refused."""
        target = self.store / "ZZZ.json"
        for name, argv in self._writers().items():
            self._run(argv, target)
            self.assertFalse(target.exists(), f"{name} wrote into the production store")

    def test_a_store_root_file_is_inside_it_too(self):
        """`index.json` and `blindness.json` sit at the root rather than in a kind directory."""
        for name, argv in self._writers().items():
            code, _ = self._run(argv, self.store / "index.json")
            self.assertEqual(code, cli.REFUSED, name)

    def test_every_writer_still_writes_outside_the_store(self):
        """The refusal is about where, so a guard that refused everything would pass the tests
        above and break every command."""
        for name, argv in self._writers().items():
            target = self.repo / "elsewhere" / f"{name.replace(' ', '-')}.txt"
            code, err = self._run(argv, target)
            self.assertEqual(code, cli.OK, f"{name}: {err}")
            self.assertTrue(target.is_file(), name)


class ManifestBuildWritesIntoTheCapture(unittest.TestCase):
    """The one artifact write that reaches a capture without passing through `capture`.

    `cli` resolves the working root and writes the artifact at its own store path, outside `_run/`,
    so the capture index lists it and the enumeration reads it as stamped - the counts-and-root
    object `manifest.to_artifact` builds is a `provenance` member like any other. Three shipped
    paragraphs describe where a captured byte comes from, and each of them named this write wrongly
    once, so what it does is read back here rather than described: routing it through `capture`, into
    `_run/` or behind `--out` are all defensible changes and any of them falsifies those paragraphs
    again.
    """

    ARTIFACT = "manifest.references"

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        tree = self.repo / "tree"
        write_text(tree / "blocks" / "stone" / "vanilla.png", "one")
        code, _, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                            "--quiet", "manifest", "build", "--artifact", self.ARTIFACT,
                            "--source", str(tree)])
        self.assertEqual(code, cli.OK, err)

    def test_the_root_holds_the_artifact_at_its_store_path_and_nothing_else(self):
        """The whole listing, so a copy left in `_run/` beside it is a different answer."""
        written = sorted(path.relative_to(self.root).as_posix()
                         for path in self.root.rglob("*") if path.is_file())
        self.assertEqual(written, [store.path_of(self.ARTIFACT)])

    def test_the_enumeration_reads_it_as_a_captured_and_stamped_row(self):
        self.assertEqual(store.artifact_files(self.root), [(self.ARTIFACT, True)])

    def test_the_capture_index_carries_it(self):
        capture.index(self.root)
        recorded = read_json(self.root / store.RUN_DIR / capture.CAPTURE_INDEX)
        self.assertEqual([entry["path"] for entry in recorded["files"]],
                         [store.path_of(self.ARTIFACT)])
        self.assertEqual(recorded["artifacts"], [self.ARTIFACT])


class CompareRefusesAMixedVintageRoot(unittest.TestCase):
    """A finished capture whose files moved under it, and the digest that names which capture it is.

    A self-capturing producer rewrites its file whenever its own suite runs, so a bare `./gradlew
    test` after a capture leaves a root that is part one run and part the next while COMPLETE still
    describes the first. `provenance` is outside the compared payload, so every number the verdict
    prints is unchanged by that - the compare said nothing at all.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        self.store = self.repo / "store"
        write_json(self.root / "sweeps" / "entity.json",
                   {"artifact": "sweep.entity", "format": 1, "key": "subject",
                    "kind": "sweep-table", "provenance": {"determinism_runs": 2},
                    "rows": [{"subject": "minecraft__cow", "mean_argb_delta": "1.0000"}]})
        capture.index(self.root)

    def _compare(self) -> tuple[int, str]:
        code, _, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                            "--store", str(self.store), "--format", "json",
                            "compare", "--bootstrap"])
        return code, err

    def _report(self) -> dict:
        return json.loads((self.root / store.RUN_DIR / "compare.json").read_text(encoding="utf-8"))

    def test_an_untouched_root_compares(self):
        self.assertEqual(self._compare()[0], cli.OK)

    def test_a_rewritten_artifact_refuses_rather_than_reporting_agreement(self):
        write_json(self.root / "sweeps" / "entity.json",
                   {"artifact": "sweep.entity", "format": 1, "key": "subject",
                    "kind": "sweep-table", "provenance": {"determinism_runs": 2},
                    "rows": [{"subject": "minecraft__cow", "mean_argb_delta": "9.9999"}]})
        code, err = self._compare()
        self.assertEqual(code, cli.REFUSED)
        self.assertIn("changed since its capture index was written", err)

    def test_the_report_names_the_capture_it_was_written_about(self):
        """What a promotion reads to tell this capture's compare from the one before it."""
        self._compare()
        self.assertEqual(self._report()["capture_digest"], capture.content_digest(self.root))

    def test_a_second_capture_moves_the_stamp(self):
        self._compare()
        first = self._report()["capture_digest"]
        capture.begin(self.root)
        write_json(self.root / "sweeps" / "entity.json",
                   {"artifact": "sweep.entity", "format": 1, "key": "subject",
                    "kind": "sweep-table", "provenance": {"determinism_runs": 2},
                    "rows": [{"subject": "minecraft__cow", "mean_argb_delta": "2.0000"}]})
        capture.index(self.root)
        self._compare()
        self.assertNotEqual(self._report()["capture_digest"], first)


class AFirstPromotionReadsTheRefusalThatFits(unittest.TestCase):
    """The compare-coverage refusal must not shadow the one that names `--bootstrap`.

    Driven end to end through `main` rather than over a hand-written report, because the defect was
    a disagreement between what `compare` writes and what `promote` reads: a baseline-less artifact
    goes under `missing_baseline` and never under `artifacts`, so a coverage check reading the one
    list refused a first promotion for not having been compared - in front of the accurate refusal,
    and offering two remedies that cannot exist. No widening of `-Partifacts` puts a row with no
    baseline into the list it was reading.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        self.store = self.repo / "store"
        register(self.store, "sweep.entity", "manifest.fluid", floor=2)
        capture.begin(self.root)
        write_json(self.root / "sweeps" / "entity.json",
                   {"artifact": "sweep.entity", "format": 1, "key": "subject",
                    "kind": "sweep-table",
                    "provenance": {"asset_dirty": False, "counts": {"failed": 0, "rows": 1},
                                   "determinism_runs": 2},
                    "rows": [{"subject": "minecraft__cow", "mean_argb_delta": "1.0000"}]})
        capture.index(self.root)

    def _run(self, *argv: str) -> tuple[int, str]:
        code, out, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                              "--store", str(self.store), *argv])
        return code, out + err

    def test_the_compare_files_it_under_missing_baseline_and_never_under_artifacts(self):
        """The premise of everything below, asserted rather than assumed."""
        self.assertEqual(self._run("compare")[0], cli.DIFFERENCES)
        report = json.loads((self.root / store.RUN_DIR / "compare.json").read_text(encoding="utf-8"))
        self.assertEqual(report["artifacts"], [])
        self.assertEqual(report["missing_baseline"], ["sweep.entity"])

    def test_the_promotion_names_the_absent_baseline_rather_than_an_absent_compare(self):
        self._run("compare")
        code, text = self._run("promote-apply", "--reason", "probe")
        self.assertEqual(code, cli.REFUSED)
        self.assertIn("sweep.entity has no baseline to replace", text)
        self.assertNotIn("the compare did not cover", text)

    def test_an_artifact_the_compare_never_looked_at_still_refuses_for_coverage(self):
        """The coverage refusal is not being disabled - only stopped from answering for a case it
        cannot describe. A compare scoped away from the row leaves it in neither list."""
        self._run("compare", "--artifacts", "manifest.fluid")
        code, text = self._run("promote-apply", "--reason", "probe")
        self.assertEqual(code, cli.REFUSED)
        self.assertIn("the compare did not cover sweep.entity", text)

    def test_bootstrap_promotes_the_first_capture(self):
        """The green path, so the two refusals above are not vacuously true."""
        self.assertEqual(self._run("compare", "--bootstrap")[0], cli.OK)
        code, text = self._run("promote-apply", "--reason", "probe", "--bootstrap")
        self.assertEqual(code, cli.OK, text)
        self.assertIn("promoted 1: sweep.entity", text)

    def test_bootstrap_promotes_a_first_capture_with_no_compare_run_at_all(self):
        """The exemption, end to end: a first baseline has nothing to be diffed against.

        It differs from the green path above by one command not having been run, and the store is
        read back because an exit code alone says nothing about what was written.
        """
        code, text = self._run("promote-apply", "--reason", "probe", "--bootstrap")
        self.assertEqual(code, cli.OK, text)
        self.assertTrue((self.store / "sweeps" / "entity.json").is_file())

    def test_bootstrap_carries_the_baselined_rows_beside_the_new_one(self):
        """The exemption's cost, asserted rather than left to be discovered.

        One row is new - which is why the flag is on the command line - and the row beside it has a
        baseline and moved. The flag is per-invocation and cannot say which row it was typed for, so
        the neighbour is written with no diff ever shown. `--artifacts` is what narrows that, and
        the stored bytes are what says which way it went.
        """
        self._fluid(self.store, "0" * 64)
        self._fluid(self.root, "1" * 64)
        capture.index(self.root)

        code, text = self._run("promote-apply", "--reason", "probe", "--bootstrap")

        self.assertEqual(code, cli.OK, text)
        stored = json.loads((self.store / "manifests" / "fluid.json").read_text(encoding="utf-8"))
        self.assertEqual(stored["files"][0]["sha256"], "1" * 64)

    def test_narrowing_the_promotion_leaves_the_row_the_flag_was_not_typed_for(self):
        """`--artifacts` is the answer to the case above, so the answer is asserted beside it."""
        self._fluid(self.store, "0" * 64)
        self._fluid(self.root, "1" * 64)
        capture.index(self.root)

        code, text = self._run("promote-apply", "--reason", "probe", "--bootstrap",
                               "--artifacts", "sweep.entity")

        self.assertEqual(code, cli.OK, text)
        stored = json.loads((self.store / "manifests" / "fluid.json").read_text(encoding="utf-8"))
        self.assertEqual(stored["files"][0]["sha256"], "0" * 64)

    @staticmethod
    def _fluid(under: Path, digest: str) -> None:
        """One manifest row, distinguishable by the digest it carries."""
        write_json(under / "manifests" / "fluid.json",
                   {"artifact": "manifest.fluid", "files": [{"path": "a.gif", "sha256": digest}],
                    "format": 1, "key": "path", "kind": "manifest",
                    "provenance": {"asset_dirty": False, "counts": {"failed": 0, "files": 1},
                                   "determinism_runs": 2, "root": "cache/x"}})


class TheEntryPointDefaultsRunsToTheFloor(unittest.TestCase):
    """An absent `--runs` stamps the floor at the CLI, which is the side the build relies on.

    The build forwards `--runs` only when `-Pruns` was given, and says in the same breath that an
    absent one means the artifact's declared floor "which the toolkit owns". So the default that has
    to exist is the ENTRY POINT's: the standard invocation is a bare `parityCapture`, and with
    `--runs` declared `default=0` that stamped a number `promote.check` refuses on every artifact -
    after the capture had run, which for a full bundle is the multi-minute half of the gate.

    `capture.normalize`'s own default is asserted beside its floor lookup in `test_capture.py`. That
    is the library, and a library default an argparse default overwrites before it is reached is not
    the one the build gets.
    """

    ARTIFACT = "digest.shipped-tables"

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        register(self.repo / store.PRODUCTION, self.ARTIFACT)

    def _stamp(self, *argv: str, from_store: str | None = None) -> int:
        """Drive a capture through `main` in the order the build does, and read what it stamped.

        Begin, producer, normalize - the erase is its own command precisely so that a step landing
        after it does not repeat one. The source is the working root itself, which is how a
        self-captured row is spelled: the producer wrote the file from inside its own test JVM and
        the capture step validates it where it stands. Nothing else about the artifact matters here.
        """
        self._invoke("capture-begin")
        write_json(self.root / store.path_of(self.ARTIFACT),
                   {"artifact": self.ARTIFACT, "format": 1, "key": "name", "kind": "digest-set",
                    "digests": {"block_models": {"sha256": "a"}}})
        self._invoke("capture-normalize", "--artifact", self.ARTIFACT,
                     "--source", str(self.root), *argv, from_store=from_store)
        stamped = json.loads(
            (self.root / store.path_of(self.ARTIFACT)).read_text(encoding="utf-8"))
        return stamped["provenance"]["determinism_runs"]

    def _invoke(self, *argv: str, from_store: str | None = None) -> None:
        override = ["--store", from_store] if from_store else []
        code, _, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                            *override, *argv])
        self.assertEqual(code, cli.OK, err)

    def test_a_bare_capture_stamps_the_artifacts_floor(self):
        """Compared against the floor rather than against a literal, because the refusal one command
        along reads the same function: a number below it is a capture the gate then throws away."""
        self.assertEqual(self._stamp(), promote.floor_for(
            self.ARTIFACT, store.production(None, self.repo).index()))

    def test_an_explicit_runs_reaches_the_stamp_unchanged(self):
        """A measurement the build did take must not be replaced by the floor it may exceed."""
        self.assertEqual(self._stamp("--runs", "7"), 7)

    def test_an_explicit_zero_is_a_measurement_and_survives(self):
        """The default is absence, not falsehood: a declared zero is a claim, and promote refuses
        it. Collapsing the two would make `-Pruns=0` mean the floor and hide a failed rerun."""
        self.assertEqual(self._stamp("--runs", "0"), 0)

    def test_the_floor_is_read_out_of_the_store_the_invocation_names(self):
        """`--store` says which store answers, and the floor became a column of one. Read off the
        default production store regardless, a capture into a redirected store is stamped with a
        number that store never declared. The flag is the only way the two can ever differ, so
        honouring it here is the whole of what makes a fixture store answer for its own rows."""
        elsewhere = self.repo / "elsewhere"
        register(elsewhere, self.ARTIFACT, floor=5)
        self.assertEqual(self._stamp(from_store=str(elsewhere)), 5)


class TheCaptureStampsWhatProducedTheValue(unittest.TestCase):
    """The three conditions a capture records beside the value, driven through `main` end to end.

    Each is a hop the build makes and the entry point has to complete: an argument the parser
    accepts and hands nowhere lands nothing in the file, which is exactly how `mode`, `flags` and
    `reference_manifest_digest` sat in the signature and reached 24 promoted baselines absent. Read
    off the stamped file rather than off the source of the function that writes it, because the
    defect being guarded against is a call that compiles and drops its argument.
    """

    ARTIFACT = "digest.shipped-tables"

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        register(self.repo / store.PRODUCTION, self.ARTIFACT)

    def _invoke(self, *argv: str) -> None:
        code, _, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current", *argv])
        self.assertEqual(code, cli.OK, err)

    def _provenance(self, *argv: str) -> dict:
        self._invoke("capture-begin")
        write_json(self.root / store.path_of(self.ARTIFACT),
                   {"artifact": self.ARTIFACT, "format": 1, "key": "name", "kind": "digest-set",
                    "digests": {"block_models": {"sha256": "a"}}})
        self._invoke("capture-normalize", "--artifact", self.ARTIFACT,
                     "--source", str(self.root), *argv)
        return json.loads(
            (self.root / store.path_of(self.ARTIFACT)).read_text(encoding="utf-8"))["provenance"]

    def test_the_mode_reaches_the_stamped_record(self):
        self.assertEqual(self._provenance("--mode", "EVERY")["mode"], "EVERY")

    def test_an_absent_mode_is_an_omitted_field_rather_than_an_empty_one(self):
        """A record saying nothing about the mode and one claiming an unnamed mode differ."""
        self.assertNotIn("mode", self._provenance())

    def test_every_flag_reaches_the_stamped_record(self):
        """Two, because one would pass on an implementation that keeps only the last."""
        self.assertEqual(
            self._provenance("--flag", "asset.depth.range=1000", "--flag", "asset.snap.grid=400")
            ["flags"],
            {"asset.depth.range": "1000", "asset.snap.grid": "400"})

    def test_the_reference_tree_is_stamped_as_the_digest_of_its_manifest(self):
        # Outside the working root, which the capture that reads it erases first.
        tree = self.repo / "references"
        write_text(tree / "blocks" / "stone" / "vanilla.png", "one")
        self.assertEqual(self._provenance("--reference-tree", str(tree))
                         ["reference_manifest_digest"],
                         provenance.reference_manifest_digest(tree))

    def test_an_absent_reference_tree_stamps_no_digest_rather_than_a_wrong_one(self):
        self.assertNotIn("reference_manifest_digest",
                         self._provenance("--reference-tree", str(self.root / "nowhere")))


class AllowDirtyIsReachableAndRecorded(unittest.TestCase):
    """The dirty-tree refusal's override, driven the only way an operator can reach it.

    `promote.check` and `promote.apply` each take the flag, and the CLI has to hand it to BOTH. Drop
    it from the first and the documented override refuses anyway, so `-PallowDirty=true` is a flag
    the skill and the runbook both describe and nothing honours. Drop it from the second and the
    promotion succeeds while the store records nothing - which is worse, because an override nothing
    writes down cannot be told apart from the refusal never having fired.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        self.store = self.repo / "store"
        register(self.store, "sweep.entity", floor=2)
        write_json(self.store / "sweeps" / "entity.json", self._sweep("2.0000", dirty=False))
        capture.begin(self.root)
        write_json(self.root / "sweeps" / "entity.json", self._sweep("1.0000", dirty=True))
        capture.index(self.root)
        self.assertEqual(self._run("compare")[0], cli.DIFFERENCES)

    def _run(self, *argv: str) -> tuple[int, str]:
        code, out, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                              "--store", str(self.store), *argv])
        return code, out + err

    def _stored(self) -> dict:
        return json.loads((self.store / "sweeps" / "entity.json").read_text(encoding="utf-8"))

    def test_a_dirty_capture_refuses_through_the_cli(self):
        """The precondition for the two below, and the state an operator actually meets."""
        code, text = self._run("promote-apply", "--reason", "probe")
        self.assertEqual(code, cli.REFUSED)
        self.assertIn("records asset_dirty=True", text)
        self.assertEqual(self._stored()["rows"][0]["mean_argb_delta"], "2.0000")

    def test_the_flag_reaches_the_refusal_and_the_promotion_goes_through(self):
        """`--allow-dirty` not reaching `check` leaves the refusal firing under the flag that names
        it, so the override is unreachable from any command line and the baseline never moves."""
        code, text = self._run("promote-apply", "--reason", "probe", "--allow-dirty")
        self.assertEqual(code, cli.OK, text)
        self.assertIn("promoted 1: sweep.entity", text)
        self.assertEqual(self._stored()["rows"][0]["mean_argb_delta"], "1.0000")

    def test_the_override_is_written_into_the_promoted_provenance(self):
        """Asserted on the stored bytes: the flag not reaching `apply` promotes just the same and
        leaves a baseline that reads exactly like one taken from a committed tree."""
        self._run("promote-apply", "--reason", "probe", "--allow-dirty")
        self.assertIs(self._stored()["provenance"]["allow_dirty"], True)

    def test_a_promotion_that_needed_no_override_records_none(self):
        """So the field means what it says: present is the exception, absent is the ordinary act."""
        write_json(self.root / "sweeps" / "entity.json", self._sweep("1.0000", dirty=False))
        capture.index(self.root)
        self.assertEqual(self._run("compare")[0], cli.DIFFERENCES)
        self.assertEqual(self._run("promote-apply", "--reason", "probe")[0], cli.OK)
        self.assertNotIn("allow_dirty", self._stored()["provenance"])

    @staticmethod
    def _sweep(delta: str, dirty: bool) -> dict:
        return {"artifact": "sweep.entity", "format": 1, "key": "subject", "kind": "sweep-table",
                "provenance": {"asset_dirty": dirty, "asset_sha": "abc123",
                               "counts": {"failed": 0, "rows": 1}, "determinism_runs": 2},
                "rows": [{"subject": "minecraft__cow", "mean_argb_delta": delta, "status": "ok"}]}


class ThePopulationWaiverReachesTheRefusalItAnswers(unittest.TestCase):
    """The row-count refusal's override, driven the only way an operator can reach it.

    The refusal itself is exercised against `promote.check` directly, and that says nothing about
    whether the flag arrives: forwarded as a literal, every one of those cases stays green while
    `-Ppopulation=changed` - which the refusal's own message prescribes, and which the runbook calls
    the only answer - refuses anyway, after the multi-minute capture that earned it.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.root = self.repo / "cache" / "parity" / "current"
        self.store = self.repo / "store"
        register(self.store, "sweep.entity", floor=2, entries=1)
        write_json(self.store / "sweeps" / "entity.json", self._sweep("2.0000"))
        capture.begin(self.root)
        write_json(self.root / "sweeps" / "entity.json", self._sweep("1.0000", "minecraft__pig"))
        capture.index(self.root)
        self._run("compare")

    def _run(self, *argv: str) -> tuple[int, str]:
        code, out, err = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                              "--store", str(self.store), "--quiet", *argv])
        return code, out + err

    def _stored(self) -> dict:
        return json.loads((self.store / "sweeps" / "entity.json").read_text(encoding="utf-8"))

    def test_a_moved_population_refuses_through_the_cli(self):
        """The precondition for the two below, and the state an operator actually meets."""
        code, text = self._run("promote-apply", "--reason", "probe")
        self.assertEqual(code, cli.REFUSED, text)
        self.assertIn("captured 2 rows where its baseline holds 1", text)
        self.assertEqual(len(self._stored()["rows"]), 1)

    def test_the_flag_reaches_the_refusal_and_the_promotion_goes_through(self):
        code, text = self._run("promote-apply", "--reason", "probe", "--population-changed")
        self.assertEqual(code, cli.OK, text)
        self.assertEqual(len(self._stored()["rows"]), 2)

    def test_the_waiver_is_written_into_the_promoted_provenance(self):
        """A waiver nothing writes down cannot be told apart from the refusal never firing."""
        self._run("promote-apply", "--reason", "probe", "--population-changed")
        self.assertIs(self._stored()["provenance"]["population_changed"], True)

    @staticmethod
    def _sweep(delta: str, *extra: str) -> dict:
        rows = [{"subject": "minecraft__cow", "mean_argb_delta": delta, "status": "ok"}]
        rows += [{"subject": name, "mean_argb_delta": delta, "status": "ok"} for name in extra]
        return {"artifact": "sweep.entity", "format": 1, "key": "subject", "kind": "sweep-table",
                "provenance": {"asset_dirty": False, "asset_sha": "abc123",
                               "counts": {"failed": 0, "rows": len(rows)},
                               "determinism_runs": 2},
                "rows": rows}


class PlanSplit(unittest.TestCase):
    """`sees` answers reach; `plan` selects a capture; the rest splits again on who GATES it.

    A rule names an artifact whenever the change really moves it, and three of the four homes hold
    artifacts the store keeps no file of its own for. Handing one to `parityCapture` refuses the whole
    invocation as Gradle resolves that task's dependencies while it builds the graph, so the plan
    carries the store half.

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
        """The defect this split exists for: every other id refuses parityCapture as it resolves."""
        _, _, payload = self._plan()
        self.assertEqual(payload["plan"], ["manifest.tooling-tables", "sweep.entity"])

    def test_the_plan_line_prints_the_capture_set_and_not_the_reach_answer(self):
        """The one printed line a human copies into `-Partifacts`, and the one the skill and the
        procedures both name as where PLAN is read off.

        The whole line, because every id it drops is still printed elsewhere on the same page: SEES
        names all of them a few lines above, so a check that the line CONTAINS the plan passes just
        as well on a line that also names `pin.armor-span` - which is the id `parityCapture` refuses
        as it resolves its own dependencies, and the entire defect this split exists to fix. The
        count is inside the assertion for the same reason.
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

    def test_a_measured_budget_prints_the_number_alone(self):
        _, out, _ = self._plan()
        self.assertIn("BUDGET 12 ms", out.splitlines())

    def test_a_zero_budget_explains_itself_by_the_column_and_not_by_the_promotions(self):
        """The two readings of a zero, and only one of them is ever true here.

        The rows below are baselined and carry no duration, which is the state every plan an
        operator runs was printed in: a promotion is what fills the column, so a parenthetical
        blaming an empty store sends a reader looking for a promotion that already happened. The
        number itself is right under either reading, and nothing else in the block contradicts the
        sentence, so the string is the whole of what an operator is told.
        """
        promoted = {name: {key: value for key, value in row.items() if key != "last_duration_ms"}
                    for name, row in self.INDEX["artifacts"].items()}
        write_json(self.store / "index.json", {**self.INDEX, "artifacts": promoted})
        _, out, payload = self._plan()

        self.assertTrue(all(row["baselined"] for row in promoted.values()))
        self.assertEqual(payload["budget_ms"], 0)
        self.assertIn("BUDGET 0 ms  (no artifact in this plan has a recorded duration)",
                      out.splitlines())

    def test_a_partly_measured_budget_says_it_is_a_floor(self):
        """The reading that costs something, and the one a zero-or-nothing caveat cannot express.

        Both planned artifacts carry a duration in the fixture, so the printed number is the cost.
        Drop one of them and the sum is still a number, still correct about what it summed, and now
        missing a whole artifact's producers - which is exactly the shape of a bundle whose cheap
        half is measured and whose expensive half boots the client. Keyed off the count rather than
        off the sum, because a measured bundle and a half-measured one can print the same total.
        """
        artifacts = {name: ({key: value for key, value in row.items() if key != "last_duration_ms"}
                            if name == "manifest.tooling-tables" else row)
                     for name, row in self.INDEX["artifacts"].items()}
        write_json(self.store / "index.json", {**self.INDEX, "artifacts": artifacts})
        _, out, payload = self._plan()

        self.assertEqual(payload["plan"], ["manifest.tooling-tables", "sweep.entity"])
        self.assertEqual(payload["budget_ms"], 7)
        self.assertEqual(payload["budget_measured"], 1)
        self.assertIn("BUDGET 7 ms  (1 of 2 artifacts carry a duration, so this is a floor "
                      "and not the cost)", out.splitlines())

    def test_a_fully_measured_budget_counts_itself_measured(self):
        """The companion to the floor case: the count is what tells the two apart, so it is asserted
        on the state that prints no parenthetical as well as on the one that does."""
        _, _, payload = self._plan()
        self.assertEqual(payload["budget_measured"], len(payload["plan"]))

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
        self.assertEqual(joined, ["report.diagnostics-log", "report.failure-rows"])
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


class ExpectRegistration(unittest.TestCase):
    """The expected-diff's only writer, which `parityExpect` is the sanctioned way to reach.

    The manifest is what makes the gate `diff == the registration` rather than `diff == empty`, and
    it underpins the one verdict row that passes with movers in it. Until the task existed the
    documented flow could not produce one at all.
    """

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.manifest = (self.repo / "cache" / "parity" / "current" / store.RUN_DIR
                         / "expected-diff.json")

    def _expect(self, *argv: str) -> int:
        return run(["--repo-root", str(self.repo), "--root", "cache/parity/current", "--quiet",
                    "expect", *argv])[0]

    def test_empty_writes_a_manifest_that_registers_nothing(self):
        self.assertEqual(self._expect("--empty"), cli.OK)
        self.assertEqual(read_json(self.manifest)["movers"], [])

    def test_a_registration_carries_the_row_and_the_value_it_must_land_on(self):
        self.assertEqual(self._expect("--artifact", "sweep.entity", "--key", "minecraft__cow",
                                      "--to", "0.2004", "--reason", "operand order"), cli.OK)
        self.assertEqual(read_json(self.manifest)["movers"],
                         [{"artifact": "sweep.entity", "key": "minecraft__cow",
                           "reason": "operand order", "to": "0.2004"}])

    def test_a_second_registration_lands_beside_the_first(self):
        self._expect("--artifact", "sweep.entity", "--key", "a", "--to", "1", "--reason", "r")
        self._expect("--artifact", "sweep.entity", "--key", "b", "--to", "2", "--reason", "r")
        self.assertEqual([row["key"] for row in read_json(self.manifest)["movers"]], ["a", "b"])

    def test_empty_clears_what_a_previous_change_registered(self):
        """The manifest survives the capture wipe, so nothing else ever clears it."""
        self._expect("--artifact", "sweep.entity", "--key", "a", "--to", "1", "--reason", "r")
        self._expect("--empty")
        self.assertEqual(read_json(self.manifest)["movers"], [])

    def test_a_command_naming_nothing_at_all_is_refused_rather_than_read_as_a_clear(self):
        """The backstop under `parityExpect`, which forwards what it was given and nothing else.

        A task that filled an incomplete registration out with `--empty` reported success and
        ERASED the registration a previous invocation left - the one outcome worse than refusing,
        and reachable whenever the task's own refusal missed the invocation.
        """
        self._expect("--artifact", "sweep.entity", "--key", "a", "--to", "1", "--reason", "r")
        self.assertEqual(self._expect(), cli.REFUSED)
        self.assertEqual([row["key"] for row in read_json(self.manifest)["movers"]], ["a"])

    def test_a_registration_naming_no_value_is_refused_and_writes_nothing(self):
        self.assertEqual(
            self._expect("--artifact", "sweep.entity", "--key", "a", "--reason", "r"), cli.REFUSED)
        self.assertFalse(self.manifest.exists())

    def test_a_clear_given_alongside_a_registration_is_refused_rather_than_taken(self):
        """Two orders in one command, and taking either drops the other silently.

        The one dropped is the registration, so the command that named a row and the value it must
        land on reported zero movers registered and exited 0 - a task whose whole subject is
        refusing an under-specified registration accepting a contradictory one.
        """
        self._expect("--artifact", "sweep.entity", "--key", "a", "--to", "1", "--reason", "r")
        self.assertEqual(
            self._expect("--empty", "--artifact", "sweep.entity", "--key", "b", "--to", "2",
                         "--reason", "r"), cli.REFUSED)
        self.assertEqual([row["key"] for row in read_json(self.manifest)["movers"]], ["a"])

    def test_a_clear_is_refused_beside_any_one_member_of_a_registration(self):
        """Every member on its own, because the refusal's operand is the whole four.

        Handed all four at once the refusal is satisfied by any ONE of them being in the operand,
        so three members can sit outside every assertion there is: narrowed to `--artifact` alone
        it stays green, and `expect --empty --key a --to 1 --reason r` then clears the previous
        change's registration and exits 0 - the silent drop the refusal exists against, reached
        through the refusal itself.
        """
        for member, value in (("--artifact", "sweep.entity"), ("--key", "b"), ("--to", "2"),
                              ("--reason", "r")):
            with self.subTest(member=member):
                self._expect("--empty")
                self._expect("--artifact", "sweep.entity", "--key", "a", "--to", "1",
                             "--reason", "r")
                self.assertEqual(self._expect("--empty", member, value), cli.REFUSED)
                self.assertEqual([row["key"] for row in read_json(self.manifest)["movers"]], ["a"])


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

    #: A whole second on the clock a stamp is read off, for the cases whose subject is ordering.
    #: Both sides are stamped rather than written in sequence, because two consecutive writes here
    #: land on ONE ``st_mtime_ns`` - which is what makes the tiebreak beside the stamp load-bearing.
    SECOND = 1_800_000_000_000_000_000

    def _plan(self, *changed: str, root: str = "cache/parity/current") -> int:
        argv = ["--repo-root", str(self.repo), "--root", root,
                "--store", str(self.store), "--quiet", "plan", "--gate-exit"]
        for path in changed:
            argv += ["--changed", path]
        return run(argv)[0]

    def _record_verdict(self, artifacts: list[str], root: str = "cache/parity/current",
                        at_ns: int | None = None, **overrides) -> None:
        """Write a verdict into one working root.

        :param artifacts: what the compare covered
        :param root: the working root it landed in, relative to the repo - the promotion procedure's
            own variable, and not always the one the gate was invoked with
        :param at_ns: the mtime to stamp, in nanoseconds, for a case whose subject is which of two
            verdicts is newer; absent, the write's own time stands
        :param overrides: verdict fields to replace
        """
        payload = {"artifacts": artifacts,
                   "asset_dirty_digest": provenance.dirty_digest(self.repo),
                   "asset_sha": provenance.asset_state(self.repo)["asset_sha"]}
        payload.update(overrides)
        target = self.repo / root / store.RUN_DIR / "last-verdict.json"
        write_json(target, payload)
        if at_ns is not None:
            os.utime(target, ns=(at_ns, at_ns))

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

    def test_10_when_the_verdict_recorded_unexpected_movers(self):
        """A compare that found movers nobody registered recorded a failure, not a gating.

        The hook is the only automatic detector in the loop, so it going quiet after exactly the run
        whose answer was RED is the worst state available to it.
        """
        self._record_verdict(["sweep.entity"], unexpected=1)
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def test_10_when_the_verdict_found_no_baseline_for_something_it_looked_at(self):
        self._record_verdict(["sweep.entity"], missing_baseline=["sweep.block"])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def test_20_when_the_verdict_recorded_a_compare_that_passed(self):
        self._record_verdict(["sweep.entity"], unexpected=0, missing_baseline=[])
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_20_when_the_verdict_was_written_into_a_root_this_invocation_was_not_given(self):
        """A verdict names a TREE STATE, so the root its capture went into does not own it.

        Read out of the given root alone, it was read out of the default one - and the promotion
        procedure's compare carries `-PparityRoot=cache/parity/b`, so the hardest-gated commit in
        the corpus is the one shape that answered "never gated".
        """
        self._record_verdict(["sweep.entity"], root="cache/parity/b")
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_20_when_the_only_verdict_is_in_the_root_this_invocation_named_and_no_other(self):
        """A working root is any relative path under `cache/`, so the given one need not be scanned.

        The scan is over `cache/parity/*` and this root is outside it, which leaves the root the
        invocation was actually handed as the only place the verdict can be found. Dropped from the
        candidates, a toolkit run pointed anywhere else answers "never gated" about its own compare.
        """
        self._record_verdict(["sweep.entity"], root="cache/scratch")
        self.assertEqual(self._plan("src/Main.java", root="cache/scratch"),
                         cli.GATE_ALREADY_GATED)

    def test_10_when_the_newest_verdict_across_the_roots_is_the_failing_one(self):
        """Newest wins, not any-that-passes: a red compare after a green one is the state to report."""
        self._record_verdict(["sweep.entity"], root="cache/parity/b", at_ns=self.SECOND)
        self._record_verdict(["sweep.entity"], root="cache/parity/current",
                             at_ns=self.SECOND + 1_000_000_000, unexpected=1)
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_SEES_UNGATED)

    def test_20_when_the_newest_verdict_across_the_roots_is_the_passing_one(self):
        """And the same ordering the other way round, which no per-root reading answers.

        The failing verdict is in the root the invocation was given and the passing one is not, so a
        rule preferring the given root, or the first root found, answers 10 here.
        """
        self._record_verdict(["sweep.entity"], root="cache/parity/current", at_ns=self.SECOND,
                             unexpected=1)
        self._record_verdict(["sweep.entity"], root="cache/parity/b",
                             at_ns=self.SECOND + 1_000_000_000)
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_20_when_one_of_two_verdicts_stamped_alike_is_the_greater_path(self):
        """Two verdicts on one stamp are ordered by path, and WHICH one that picks is pinned here.

        Two consecutive writes on this filesystem land on one `st_mtime_ns`, so an equal-stamp pair
        is the ordinary case rather than the exotic one. Which of them wins is arbitrary; that the
        stamp is not the whole key is not. The candidates are enumerated in path order, so a key
        that leaves this pair equal answers with the LESSER path - the failing verdict below - and
        the gate reports 10 over a tree the compare that came after it passed.
        """
        self._record_verdict(["sweep.entity"], root="cache/parity/a", at_ns=self.SECOND,
                             unexpected=1)
        self._record_verdict(["sweep.entity"], root="cache/parity/current", at_ns=self.SECOND)
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_20_when_the_newer_verdict_is_newer_by_less_than_a_stamp_read_in_seconds(self):
        """The stamp is read in NANOSECONDS, and the pair below is what that buys.

        These two are 100 ns apart - one tick of the filesystem's own clock, and less than the
        spacing of the floats a seconds-valued stamp lands on at this magnitude, so read that way
        they are equal and the tiebreak answers with the older one instead.
        """
        self._record_verdict(["sweep.entity"], root="cache/parity/a", at_ns=self.SECOND + 100)
        self._record_verdict(["sweep.entity"], root="cache/parity/current", at_ns=self.SECOND,
                             unexpected=1)
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def _capture(self, root: str) -> Path:
        """One stamped artifact in a working root, which is what a real compare needs on each side.

        :param root: the working root, relative to the repo
        :return: its absolute path
        """
        path = self.repo / root
        write_json(path / "sweeps" / "entity.json",
                   {"artifact": "sweep.entity", "format": 1, "key": "subject",
                    "kind": "sweep-table", "provenance": {"determinism_runs": 2},
                    "rows": [{"subject": "minecraft__cow", "mean_argb_delta": "1.0000"}]})
        capture.index(path)
        return path

    def test_20_when_the_compare_that_covered_this_tree_put_a_capture_on_the_other_side(self):
        """An A/B is a gating, and for one whole change class it is the only gate there is.

        The stash A/B and the tooling-regen A/B both compare this tree against a capture rather than
        against the store, and a generator refactor has nothing else: every sweep reads the SHIPPED
        tables, which a refactor does not regenerate. Counted as a measurement instead, the hook
        prompts on every commit of that class and its silence stops meaning anything there. Driven
        through a real compare rather than a hand-written verdict, because what is being pinned is
        that the two invocation shapes reach the same answer.
        """
        self._capture("cache/parity/current")
        other = self._capture("cache/parity/a")
        self.assertEqual(run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                              "--store", str(self.store), "--format", "json",
                              "compare", "--base", str(other)])[0], cli.OK)
        self.assertEqual(self._plan("src/Main.java"), cli.GATE_ALREADY_GATED)

    def test_the_flag_is_opt_in_so_parityPlan_still_exits_zero(self):
        """parityPlan is a Gradle Exec: a plan that answered 10 on reach would fail every build."""
        code, _, _ = run(["--repo-root", str(self.repo), "--root", "cache/parity/current",
                          "--store", str(self.store), "--quiet", "plan",
                          "--changed", "src/Main.java"])
        self.assertEqual(code, cli.OK)


if __name__ == "__main__":
    unittest.main()
