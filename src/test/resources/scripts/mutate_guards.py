"""Breaks what each guard claims to catch, runs the tests that name it, and restores the tree.

A guard is evidence only once it has been seen to go red for the thing it claims to catch. This
driver breaks that thing on purpose, one mutant at a time, runs the scoped tests that should catch
it, and reports RED or SURVIVED - with each failure's first message line and, for a Hamcrest
assertion, its ``but:`` line, which is what tells red for the right reason from a fixture that
broke.

    python src/test/resources/scripts/mutate_guards.py --spec SPEC.json

The spec is session input and belongs in a scratch directory, not in the repository:

    {"mutants": [
      {"id": "home-walk-matches-nothing",
       "why": "the hand-authored file walk matches nothing, so the guard holds vacuously",
       "task": ":test",
       "tests": ["*BlindnessMapTest"],
       "edits": [{"path": "src/test/java/lib/minecraft/renderer/guard/BlindnessMapTest.java",
                  "find": ".filter(path -> path.startsWith(home))",
                  "replace": ".filter(path -> false)"}],
       "expect": "RED"}]}

``task`` is ``:test`` or ``:tooling:test``, qualified because an unqualified ``--tests`` filter
applies to every Test task and fails on the one holding no match. A mutant's edits apply together,
in order, so a guard and the pin beside it can be mutated in step. Each ``find`` must occur exactly
``count`` times in its file at the point its edit applies, and every occurrence is replaced;
``count`` is optional and 1 when absent, so one edit can break a phrase everywhere it is pinned.
The whole spec is checked before anything is written.

How a run goes:

- The repository is the git checkout holding this script, so the working directory never matters
  and the copy inside a linked worktree mutates that worktree and nothing else. A fresh worktree
  has no ``cache/``, so a class that reads the cached client jar abandons there.
- Every spec'd path must be one git tracks under exactly that spelling and be clean in
  ``git status``, or the run refuses to start, because a clean tracked file is one ``git restore``
  recovers whatever happens to the driver. Status alone would pass an ignored or mis-cased path,
  since it prints nothing for either. ``--allow-untracked`` admits a path git does not track, which
  is how a test input under ``cache/`` - a texture pack read by path - is mutated. Such a file is
  restored by its bytes like any other, but nothing in git recovers it if the driver dies: delete it
  and let its producer write it again. The vanilla pack is not such an input: it is read out of the
  client jar into memory, so no file under ``cache/`` stands in for a vanilla model.
- Every distinct task and test set runs once over the unmutated tree first. A control that is not
  green voids every RED after it, so it ends the run.
- Per mutant: write the edits, then run the task with ``--tests`` per glob, ``--rerun``,
  ``--no-configuration-cache`` and ``-PparityRoot=cache/parity/mutants``, so a self-captured row
  lands in a scratch root rather than the working one. That task's JUnit XML is erased before the
  run and read after it: a failing testcase is RED, none is SURVIVED, and no report at all - a
  mutant that does not compile - is NO REPORT.
- A file is restored only when it still holds the bytes this run wrote. Anything else means it was
  edited during the run, so the driver leaves it, names it and stops rather than erase that edit.
  The restore runs on an exception or Ctrl-C as well. Every spec'd file must hold its starting
  bytes again before each mutant is written and once more after the last, so an edit made to any
  of them during a run, whether that mutant wrote the file or not, stops the driver and marks what
  ran last as measured over it.

SURVIVED is a verdict owed rather than a pass: the mutant is equivalent, or the guard has a gap.
Exit status is 0 when every mutant is RED, 1 on any SURVIVED or NO REPORT, 2 when the spec, an
untracked or dirty file or the control run stops it before the first mutant, 3 when a file changed
under it, 4 when an unexpected error ends it, and 130 on Ctrl-C.

``--command`` runs another program in place of the Gradle wrapper and hands it the same arguments,
which is how the restore path is exercised without a build.
"""

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

RESULTS = {":test": "build/test-results/test", ":tooling:test": "tooling/build/test-results/test"}
PARITY_ROOT = "cache/parity/mutants"
OUTCOMES = ("RED", "SURVIVED", "NO REPORT")


class Stop(Exception):
    """Ends the run with an exit status, after whatever restoring is owed."""

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def git(cwd, *args):
    return subprocess.run(["git", "--literal-pathspecs", *args], cwd=cwd, capture_output=True,
                          text=True, check=True).stdout


def shape_errors(mutant, index, seen):
    """Lists what is wrong with one mutant's shape, before any file is read."""
    if not isinstance(mutant, dict):
        return [f"mutant {index} is not an object"]
    label = mutant.get("id")
    errors = []
    if not isinstance(label, str) or not label:
        errors.append(f"mutant {index} has no id")
        label = f"mutant {index}"
    elif label in seen:
        errors.append(f"id '{label}' is used twice")
    seen.add(label)
    if not isinstance(mutant.get("why"), str) or not mutant["why"]:
        errors.append(f"{label}: no 'why'")
    if mutant.get("task") not in RESULTS:
        errors.append(f"{label}: task must be one of {sorted(RESULTS)}")
    tests = mutant.get("tests")
    if not isinstance(tests, list) or not tests or not all(isinstance(t, str) and t for t in tests):
        errors.append(f"{label}: 'tests' must be a non-empty list of globs")
    if mutant.get("expect") != "RED":
        errors.append(f"{label}: 'expect' must be \"RED\"")
    edits = mutant.get("edits")
    if not isinstance(edits, list) or not edits:
        return errors + [f"{label}: 'edits' must be a non-empty list"]
    for edit in edits:
        if not isinstance(edit, dict) or not all(isinstance(edit.get(k), str)
                                                 for k in ("path", "find", "replace")):
            errors.append(f"{label}: every edit needs string 'path', 'find' and 'replace'")
        elif not edit["find"]:
            errors.append(f"{label}: an edit to {edit['path']} has an empty 'find'")
        elif type(edit.get("count", 1)) is not int or edit.get("count", 1) < 1:
            errors.append(f"{label}: an edit to {edit['path']} has a 'count' that is not a "
                          "positive integer")
        elif Path(edit["path"]).is_absolute() or ".." in Path(edit["path"]).parts:
            errors.append(f"{label}: '{edit['path']}' is not a path inside the repository")
    return errors


def load(spec, root, allow_untracked=False):
    """Reads and checks the spec, returning its mutants and each spec'd file's starting bytes.

    Each mutant gains ``mutated``, the bytes every file it touches holds once its edits apply,
    computed here from the starting bytes so a spec that cannot apply fails before any write.
    ``allow_untracked`` admits a spec'd path git does not track.
    """
    try:
        mutants = json.loads(Path(spec).read_text(encoding="utf-8"))["mutants"]
    except (OSError, ValueError, KeyError, TypeError) as failure:
        raise Stop(2, f"cannot read the spec '{spec}': {failure!r}")
    if not isinstance(mutants, list) or not mutants:
        raise Stop(2, "the spec names no mutants")
    seen = set()
    errors = [e for i, m in enumerate(mutants, 1) for e in shape_errors(m, i, seen)]
    if errors:
        raise Stop(2, "the spec is malformed:\n  " + "\n  ".join(errors))
    # Spelled the way git lists a path, so ./a/b and a/b - and a\b on Windows - are one file.
    for m in mutants:
        for edit in m["edits"]:
            edit["path"] = Path(edit["path"]).as_posix()
    paths = sorted({edit["path"] for m in mutants for edit in m["edits"]})
    missing = [p for p in paths if not (root / p).is_file()]
    if missing:
        raise Stop(2, "spec'd files that do not exist: " + ", ".join(missing))
    tracked = set(git(root, "ls-files", "-z", "--", *paths).split("\0"))
    untracked = [p for p in paths if p not in tracked]
    if untracked and not allow_untracked:
        raise Stop(2, "spec'd files git does not track under that spelling, so git restore could "
                      "not recover them (--allow-untracked admits them): " + ", ".join(untracked))
    dirty = git(root, "status", "--porcelain", "--", *paths).splitlines()
    if dirty:
        raise Stop(2, "spec'd files are not clean, so a crash could not be recovered with git "
                      "restore - commit them first:\n  " + "\n  ".join(dirty))
    originals = {p: (root / p).read_bytes() for p in paths}
    for m in mutants:
        mutated = {}
        for edit in m["edits"]:
            body = mutated.get(edit["path"], originals[edit["path"]])
            find = edit["find"].encode("utf-8")
            want, found = edit.get("count", 1), body.count(find)
            if found != want:
                errors.append(f"{m['id']}: 'find' occurs {found} times in {edit['path']}, not "
                              f"{want}: {edit['find'][:80]!r}")
                continue
            mutated[edit["path"]] = body.replace(find, edit["replace"].encode("utf-8"))
        errors += [f"{m['id']}: its edits leave {p} unchanged"
                   for p, body in mutated.items() if body == originals[p]]
        m["mutated"] = mutated
    if errors:
        raise Stop(2, "the spec does not apply to the tree:\n  " + "\n  ".join(errors))
    return mutants, originals


def run_task(root, launcher, task, tests):
    """Runs one task over the given test globs.

    Returns the failures as (test, first message line, ``but:`` line) - None when the task wrote
    no report at all - the number of testcases that ran rather than skipped, and the output's tail.
    The task's own reports are erased first: one left by an earlier run would otherwise be read as
    this one's, and that is the one way the driver lies toward SURVIVED.
    """
    results = root / RESULTS[task]
    for stale in results.glob("*.xml"):
        stale.unlink()
    argv = [*launcher, task, *[arg for glob in tests for arg in ("--tests", glob)], "--rerun",
            "-q", "--no-configuration-cache", "-PparityRoot=" + PARITY_ROOT]
    done = subprocess.run(argv, cwd=root, capture_output=True, text=True, errors="replace")
    tail = "\n".join((done.stdout + done.stderr).strip().splitlines()[-15:])
    tail = f"exit {done.returncode}\n{tail}"
    reports = sorted(results.glob("*.xml"))
    if not reports:
        return None, 0, tail
    failures, ran = [], 0
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            ran += case.find("skipped") is None
            for bad in [*case.iter("failure"), *case.iter("error")]:
                text = bad.get("message") or bad.text or ""
                lines = [re.sub(r"\s+", " ", line).strip() for line in text.splitlines()]
                lines = [line for line in lines if line]
                witness = next((line[:200] for line in lines if line.startswith("but:")), None)
                test = case.get("classname", "").rsplit(".", 1)[-1] + "." + case.get("name", "")
                failures.append((test, lines[0][:200] if lines else "(no message)", witness))
    return failures, ran, tail


def control(root, launcher, mutants):
    """Runs every distinct task and test set over the unmutated tree, stopping unless all pass."""
    for task, tests in sorted({(m["task"], tuple(sorted(set(m["tests"])))) for m in mutants}):
        failures, ran, tail = run_task(root, launcher, task, list(tests))
        if failures is None or failures or not ran:
            found = "wrote no report" if failures is None else (
                f"{len(failures)} failing" if failures else "ran no testcase")
            raise Stop(2, f"control {task} {' '.join(tests)} {found}, so no RED after it would "
                          f"mean anything\n{report(failures or [])}{tail}")
        print(f"control  {task} {' '.join(tests)}: green, {ran} testcases")


def report(failures):
    out = ""
    for test, first, witness in failures:
        out += f"      {test}: {first}\n" + (f"        {witness}\n" if witness else "")
    return out


def read(path):
    """Answers a file's bytes, or None when it cannot be read."""
    try:
        return path.read_bytes()
    except OSError:
        return None


def changed(root, originals):
    """Names each spec'd file that no longer holds its starting bytes."""
    return [path for path, body in originals.items() if read(root / path) != body]


def restore(root, written, originals):
    """Puts back each file a mutant wrote, but only while it still holds the mutant's bytes.

    A file already holding its starting bytes needs nothing. One holding anything else was edited
    during the run, so it is left as it is and named, because restoring would erase that edit.
    """
    refused = []
    for path, body in written.items():
        current = read(root / path)
        if current == body:
            (root / path).write_bytes(originals[path])
        elif current != originals[path]:
            refused.append(path)
    if refused:
        raise Stop(3, "changed during the run, so left as found instead of restored: "
                      + ", ".join(refused) + " - git diff shows the mutant and that edit together")


def mutate(root, launcher, mutants, originals):
    """Applies, runs and restores each mutant in turn, and returns the count per outcome."""
    tally = dict.fromkeys(OUTCOMES, 0)
    for index, m in enumerate(mutants, 1):
        moved = changed(root, originals)
        if moved:
            raise Stop(3, f"{', '.join(moved)} changed during the run, so what ran last ran over "
                          f"that edit, and mutant '{m['id']}' and the ones after it did not run")
        written = {}
        try:
            for path, body in m["mutated"].items():
                written[path] = body
                (root / path).write_bytes(body)
            failures, ran, tail = run_task(root, launcher, m["task"], m["tests"])
        finally:
            restore(root, written, originals)
        outcome = "NO REPORT" if failures is None else "RED" if failures else "SURVIVED"
        tally[outcome] += 1
        print(f"[{index}/{len(mutants)}] {outcome:<9} {m['id']} - {m['why']}", flush=True)
        if outcome == "RED":
            print(report(failures), end="")
        elif outcome == "SURVIVED":
            print(f"      {ran} testcases passed; a verdict is owed - equivalent mutant, or a gap")
        else:
            print("      " + tail.replace("\n", "\n      "))
    return tally


def main():
    parser = argparse.ArgumentParser(description="Breaks what each guard claims to catch, runs "
                                                 "the tests that name it, and restores the tree.")
    parser.add_argument("--spec", required=True, help="the mutant spec, a JSON file")
    parser.add_argument("--command", help="a program run in place of the Gradle wrapper and handed "
                                          "its arguments, split like a POSIX shell command")
    parser.add_argument("--allow-untracked", action="store_true",
                        help="admit spec'd paths git does not track, such as a texture pack "
                             "under cache/; restored by bytes, recoverable only by its "
                             "producer")
    args = parser.parse_args()
    # Line-buffered, so a STOPPED on stderr lands after the progress it follows in a captured log.
    sys.stdout.reconfigure(line_buffering=True)
    root, originals = None, {}
    try:
        root = Path(git(Path(__file__).resolve().parent, "rev-parse", "--show-toplevel").strip())
        wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        launcher = shlex.split(args.command) if args.command else [str(wrapper)]
        mutants, originals = load(args.spec, root, args.allow_untracked)
        control(root, launcher, mutants)
        tally = mutate(root, launcher, mutants, originals)
    except Stop as stop:
        print(f"STOPPED: {stop}", file=sys.stderr)
        return stop.status
    except KeyboardInterrupt:
        print("INTERRUPTED: what this run had written was restored", file=sys.stderr)
        return 130
    except Exception:
        # A status of its own, because Python's for an uncaught error is 1, which reads as SURVIVED.
        traceback.print_exc()
        left = changed(root, originals)
        print("CRASHED: " + ("spec'd files that no longer hold their starting bytes, which git "
                             "diff shows: " + ", ".join(left) if left else
                             "every spec'd file holds its starting bytes"), file=sys.stderr)
        return 4
    print("  ".join(f"{outcome} {tally[outcome]}" for outcome in OUTCOMES))
    left = changed(root, originals)
    if left:
        print(f"STOPPED: {', '.join(left)} changed during the last mutant's run, so its verdict "
              "was measured over that edit", file=sys.stderr)
        return 3
    print("every spec'd file holds its starting bytes again")
    return 0 if tally["SURVIVED"] == tally["NO REPORT"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
