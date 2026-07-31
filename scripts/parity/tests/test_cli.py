"""The exit-code table, the stdout contract, and the spellings that must stay deleted."""

from __future__ import annotations

import contextlib
import io
import json
import unittest

from parity import cli


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


if __name__ == "__main__":
    unittest.main()
