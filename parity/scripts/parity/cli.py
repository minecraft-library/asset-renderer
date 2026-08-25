"""The argparse tree, the global options, and the one place an exception becomes an exit code.

No module calls ``sys.exit``. Each raises a typed error from ``norm`` and ``main`` translates it,
so the six-code table is enforceable rather than conventional - and the separation that matters,
between "the answer is no" (1) and "I could not look" (3, 5), is structural.

This is the only module allowed to ``print``: stdout framing is its job. It still writes every file
through ``norm``.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import unittest
from pathlib import Path
from typing import Any, Callable, Sequence

from parity import VERSION
from parity import blindness as blindness_mod
from parity import capture as capture_mod
from parity import compare as compare_mod
from parity import declarations as declarations_mod
from parity import ids as ids_mod
from parity import promote as promote_mod
from parity import provenance as provenance_mod
from parity import reach as reach_mod
from parity import jsondiff as jsondiff_mod
from parity import manifest as manifest_mod
from parity import render as render_mod
from parity import report as report_mod
from parity import store as store_mod
from parity import sweep as sweep_mod
from parity.norm import (
    ComparisonFailed,
    MissingDependency,
    MissingInput,
    Refused,
    canonical_json,
    read_json,
    write_json,
    write_text,
)

OK = 0
DIFFERENCES = 1
USAGE = 2
MISSING_INPUT = 3
MISSING_DEPENDENCY = 4
REFUSED = 5

_EXIT_FOR = [
    (ComparisonFailed, DIFFERENCES),
    (MissingInput, MISSING_INPUT),
    (MissingDependency, MISSING_DEPENDENCY),
    (Refused, REFUSED),
]


# --- global options --------------------------------------------------------------------------------

def _globals() -> argparse.ArgumentParser:
    """The six globals, parsed by a pre-pass over the whole argv.

    They are deliberately **not** a `parents=` entry on the subparsers. argparse parses a
    subcommand into a fresh namespace and copies every attribute back over the main one, so a
    global carried by both parsers is decided by whichever ran last: `--root` and `--format` were
    silently discarded when they appeared *before* the subcommand, which is the side Gradle and the
    skill both write. `SUPPRESS` does not fix it reliably across versions either.

    A pre-pass sees the whole argv, so a global is honoured on either side by construction, and the
    command parser never has to know the globals exist. It is safe here because no subcommand option
    shares a name with one - and a future one that did would be a name collision worth refusing
    anyway.
    """
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--repo-root", default=None, metavar="DIR",
                        help="the repo containing parity/scripts/parity"
                             " (default: derived, so cwd never matters)")
    parser.add_argument("--root", default=None, metavar="DIR",
                        help=f"the WORKING root (default: {store_mod.WORKING}); must be relative and under cache/")
    parser.add_argument("--store", default=None, metavar="DIR",
                        help=f"the production store (default: {store_mod.PRODUCTION})")
    parser.add_argument("--format", choices=("text", "json"), default="text",
                        help="stdout form; progress and warnings go to stderr, never here")
    parser.add_argument("--out", default=None, metavar="FILE",
                        help="write the command's primary artifact here instead of stdout")
    parser.add_argument("-q", "--quiet", action="store_true",
                        help="suppress progress on stderr; never affects stdout")
    return parser


def _bases(args: argparse.Namespace) -> Path:
    return Path(args.repo_root).resolve() if args.repo_root else store_mod.repo_root()


def _out_path(args: argparse.Namespace) -> Path:
    """``--out`` as a path, refused when it lands inside the production store.

    **Every writer of ``--out`` resolves it here**, and that is the whole mechanism. The guard used
    to sit inside ``_emit``, which is the redirect-stdout path alone, so every command that builds a
    body of its own and writes it reached a baseline through a bare write and nothing went red.
    Stating the rule rather than the roster is deliberate: a roster of the commands is what went
    stale last time, and what it was stale against was a count. The line below is the one that turns
    ``args.out`` into a path, and ``OutNeverWritesProduction`` reads this file and fails on any other
    line that either constructs one over it or binds it to a name - the second because a name is a
    construction one line further on, out of reach of anything looking for the attribute. A presence
    test on ``args.out`` is neither and stays invisible. What the scan cannot see is a bypass that
    never spells the attribute at all, and nothing in the parser can be asked who honours ``--out``,
    which is why the roster beside it drives every writer through ``main``. Only ``promote-apply``
    writes production.

    :param args: the parsed namespace, whose ``out`` is set
    :return: the path to write
    :raises Refused: if the path lies inside the production store
    """
    target = Path(args.out)
    if store_mod.production(args.store, _bases(args)).contains(target):
        raise Refused(f"--out would write inside the production store: {args.out}")
    return target


def _emit(args: argparse.Namespace, text: str, payload: Any = None) -> None:
    """stdout carries the answer and nothing else; ``--out`` redirects it through ``norm``."""
    body = canonical_json(payload) if args.format == "json" and payload is not None else text
    if args.out:
        target = _out_path(args)
        write_text(target, body)
        print(f"wrote {target}")
        return
    print(body)


def _progress(args: argparse.Namespace, message: str) -> None:
    """Write one progress line to stderr, unless ``--quiet``.

    stdout carries the answer, so a line saying which artifact is being worked on cannot go there.
    The three long commands emit one per artifact: a capture, a compare and a promotion each loop
    over a set a human named as an alias and cannot enumerate, and a run that prints nothing until
    it finishes is one nobody can tell from a hang. ``--quiet`` is what the pre-commit hook passes,
    and until these calls existed it suppressed nothing.

    :param args: the parsed namespace
    :param message: the line, without its newline
    """
    if not args.quiet:
        sys.stderr.write(message + "\n")


# --- commands ----------------------------------------------------------------------------------

def _cmd_doctor(args: argparse.Namespace) -> int:
    running = ".".join(str(part) for part in sys.version_info[:3])
    optional = {}
    for name in ("PIL", "numpy"):
        try:
            module = __import__(name)
            optional[name] = getattr(module, "__version__", "present")
        except ImportError:
            optional[name] = "absent"
    payload = {
        "interpreter": sys.executable,
        "optional": optional,
        "production_store": str(store_mod.resolve_store(args.store, _bases(args))),
        "python": running,
        "version": VERSION,
        "working_root": str(store_mod.resolve_working(args.root, _bases(args))),
    }
    lines = [f"parity {VERSION} on Python {running}", f"  interpreter     {payload['interpreter']}",
             f"  production      {payload['production_store']}",
             f"  working root    {payload['working_root']}"]
    for name, found in sorted(optional.items()):
        lines.append(f"  {name:<15} {found}")
    if any(found == "absent" for found in optional.values()):
        lines.append("  install the optional pair with: python -m pip install pillow numpy")
    _emit(args, "\n".join(lines), payload)
    return OK


def _cmd_ids(args: argparse.Namespace) -> int:
    if args.ids_command == "parse":
        sid = ids_mod.parse_ref_stem(args.stem)
        payload = {
            "axes": ids_mod.axes(sid),
            "base": sid.base,
            "namespace": sid.namespace,
            "qualifiers": list(sid.qualifiers),
            "ref_stem": ids_mod.format_ref_stem(sid),
            "tokens": list(sid.tokens),
        }
        _emit(args, payload["ref_stem"], payload)
        return OK
    sid = ids_mod.parse_ref_stem(args.stem)
    spelling = ids_mod.Spelling(args.spelling)
    formatted = ids_mod.format_as(sid, spelling)
    _emit(args, formatted, {"formatted": formatted, "spelling": spelling.value})
    return OK


def _cmd_triggers(args: argparse.Namespace) -> int:
    """Regenerate every rule's ``trigger_paths`` from its authored half and the source tree.

    The one writer of a generated member of the map. ``--check`` answers what would move and writes
    nothing, which is what the guard over this runs: a hand-edited generated array states a reach no
    declaration declares, and the next run of this command would revert it in silence.
    """
    base = _bases(args)
    moved = declarations_mod.regenerate(base, store_mod.resolve_store(args.store, base),
                                        check=args.check)
    verb = "would move" if args.check else "regenerated"
    text = (f"triggers: {verb} {len(moved)} rule(s)"
            + (" - " + ", ".join(moved) if moved else ""))
    _emit(args, text, {"moved": moved, "checked": bool(args.check)})
    return DIFFERENCES if moved and args.check else OK


def _cmd_reach(args: argparse.Namespace) -> int:
    """Answer which artifacts a changed Java type can move, off the compiled constant pool.

    Four questions over one derived graph: ``of`` is what a plan asks, ``why`` prints the reference
    chain behind an answer so a wide one can be argued with rather than worked around, ``orphans``
    names the types no producer root reaches - the ones a declaration has to answer for - and
    ``build`` writes the graph for the committed file.
    """
    base = _bases(args)
    graph = reach_mod.build(base)
    if args.reach_command in ("build", "check"):
        target = base / "parity" / reach_mod.STORED
        if args.reach_command == "build" and getattr(args, "target", None):
            target = Path(args.target)
        derived = reach_mod.to_payload(graph)
        if args.reach_command == "build":
            write_json(target, derived)
            _emit(args, f"reach: wrote {target} - {len(graph.declared)} types",
                  {"types": len(graph.declared)})
            return OK
        if not target.is_file():
            raise MissingInput(f"no committed reach graph at {target} - run 'parity reach build'")
        moved = reach_mod.differences(read_json(target), derived)
        verb = "would move" if moved else "agrees with the tree"
        _emit(args, f"reach: {verb}" + (f" {len(moved)} type(s)\n" + "\n".join(moved) if moved else ""),
              {"moved": moved})
        return DIFFERENCES if moved else OK
    if args.reach_command == "orphans":
        found = reach_mod.orphans(graph)
        _emit(args, f"reach: {len(found)} type(s) no producer root reaches\n" + "\n".join(found),
              {"orphans": found})
        return OK
    if args.reach_command == "why":
        binary = reach_mod.to_binary(args.path)
        if binary is None or binary not in graph.declared:
            raise MissingInput(f"'{args.path}' is not a scanned Java source path")
        if args.artifact not in graph.roots:
            raise MissingInput(f"'{args.artifact}' has no producer root in the reach table")
        for entry in graph.roots[args.artifact]:
            found = reach_mod.chain(entry, binary, graph.edges)
            if found:
                names = [part.rsplit("/", 1)[1] for part in found]
                _emit(args, " -> ".join(names), {"chain": found})
                return OK
        raise MissingInput(f"no reference chain from '{args.artifact}' to '{args.path}'")
    answers = reach_mod.of(graph, args.path)
    lines = [f"{path}: {', '.join(found) if found else '(none derived)'}"
             for path, found in answers.items()]
    _emit(args, "\n".join(lines) or "reach: no scanned Java source path given", answers)
    return OK


def _cmd_selftest(args: argparse.Namespace) -> int:
    tests_dir = Path(__file__).resolve().parent / "tests"
    if not tests_dir.is_dir():
        raise MissingInput(f"no tests package at {tests_dir}")
    loader = unittest.defaultTestLoader
    suite = loader.discover(str(tests_dir), top_level_dir=str(tests_dir.parents[1]))
    if args.pattern:
        suite = _filter(suite, args.pattern)
    runner = unittest.TextTestRunner(stream=sys.stderr, verbosity=1 if args.quiet else 2)
    result = runner.run(suite)
    # The skip count is printed because an all-skipped run must not read as a green one.
    print(f"selftest: ran {result.testsRun}, failures {len(result.failures)}, "
          f"errors {len(result.errors)}, skipped {len(result.skipped)}")
    return OK if result.wasSuccessful() else DIFFERENCES


def _filter(suite: unittest.TestSuite, pattern: str) -> unittest.TestSuite:
    kept = unittest.TestSuite()
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            kept.addTest(_filter(item, pattern))
        elif pattern in item.id():
            kept.addTest(item)
    return kept


def _tables(args: argparse.Namespace) -> list[tuple[str, Any]]:
    """Resolve the requested sweeps from either a raw producer directory or the working root.

    The two operands are two file formats and the reader is chosen by which one was named, because
    they are not interchangeable: ``--from`` names a producer tree of TSVs and the root holds the
    canonical JSON a capture wrote. Handing a stored artifact to the TSV reader is what these two
    commands did against a fully populated root, and it failed on the file's first character - so
    the documented capability was unreachable and only the pre-store form worked.
    """
    if args.source:
        found = sweep_mod.discover(Path(args.source))
        return [(name, sweep_mod.read_table(found[name], name))
                for name in _wanted_sweeps(args, found)]
    root = store_mod.working(args.root, _bases(args))
    found = {}
    for name in sweep_mod.SWEEPS:
        candidate = root.path(f"sweep.{name}")
        if candidate.is_file():
            found[name] = candidate
    if not found:
        raise MissingInput(f"no sweep artifacts under {root.root}; pass --from to read a producer tree")
    return [(name, sweep_mod.read_stored_table(found[name], name))
            for name in _wanted_sweeps(args, found)]


def _wanted_sweeps(args: argparse.Namespace, found: dict[str, Path]) -> list[str]:
    """Which of the discovered sweeps the operands name, refusing a name nothing answers.

    Two refusals rather than one, because a typo and an absence are two different answers: a name
    outside the six is a name no producer will ever write, where one of the six the operand tree
    does not hold is a table that has not been captured yet. Folded together, the first reads as the
    second and sends an operator looking for a sweep that does not exist.

    An empty operand list is every sweep the tree holds, which is the bare command's meaning.

    :param args: the parsed namespace, whose ``sweeps`` carries the named operands
    :param found: the sweeps the operand tree holds, by name
    :return: the names to read, in the order given
    :raises MissingInput: on a name outside the six, or one of the six nothing here answers
    """
    wanted = args.sweeps or [name for name in sweep_mod.SWEEPS if name in found]
    unknown = [name for name in wanted if name not in sweep_mod.SWEEPS]
    if unknown:
        raise MissingInput(f"unknown sweep(s) {unknown}; known: {list(sweep_mod.SWEEPS)}")
    missing = [name for name in wanted if name not in found]
    if missing:
        raise MissingInput(f"no table for sweep(s) {missing}")
    return wanted


def _cmd_sum(args: argparse.Namespace) -> int:
    rows = sweep_mod.summarise(_tables(args))
    lines = [f"{row['sweep']:<7} {row['rows']:>5} rows   sum {row['sum']:>12}"
             f"   failed {row['failed']}" for row in rows]
    _emit(args, "\n".join(lines), {"sums": rows})
    return OK


def _cmd_buckets(args: argparse.Namespace) -> int:
    rows = sweep_mod.summarise(_tables(args))
    lines = []
    for row in rows:
        counts = row["buckets"]
        edges = " ".join(f"<{edge:.2f} {counts[f'<{edge:.2f}']:<5}" for edge in sweep_mod.BUCKET_EDGES)
        lines.append(f"{row['sweep']:<7} {edges}  total {counts['total']:<6} failed {counts['failed']}")
    _emit(args, "\n".join(lines), {"buckets": rows})
    return OK


def _cmd_manifest(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args))
    if args.manifest_command == "build":
        built = manifest_mod.build(args.artifact, Path(args.source), args.glob, args.exclude or ())
        target = root.path(args.artifact)
        write_json(target, manifest_mod.to_artifact(built))
        _emit(args, f"{args.artifact}: {len(built.entries)} files -> {target}",
              {"artifact": args.artifact, "entries": len(built.entries), "path": str(target)})
        return OK

    stored = manifest_mod.from_artifact(_load(args, args.artifact, args.base))
    if args.manifest_command == "export":
        if not args.out:
            raise Refused("manifest export needs --out FILE")
        write_text(_out_path(args), manifest_mod.export_text(stored))
        print(f"wrote {args.out}")
        return OK

    source = Path(args.source) if args.source else Path(stored.root)
    current = manifest_mod.build(args.artifact, source)
    verdict = manifest_mod.compare(stored, current)
    _emit(args, _verdict_text(args.artifact, verdict), verdict.as_dict())
    manifest_mod.raise_on(verdict)
    return OK


def _verdict_text(artifact: str, verdict: manifest_mod.Verdict) -> str:
    lines = [f"{artifact}: {len(verdict.added)} added, {len(verdict.missing)} missing, "
             f"{len(verdict.differing)} differing"]
    for name, rows in (("added", verdict.added), ("missing", verdict.missing),
                       ("differing", verdict.differing)):
        for path in rows[:20]:
            lines.append(f"  {name:<10} {path}")
        if len(rows) > 20:
            lines.append(f"  {name:<10} ... and {len(rows) - 20} more")
    return "\n".join(lines)


def _load(args: argparse.Namespace, artifact: str, base: str | None) -> dict:
    """A stored artifact from the named side: --base if given, else the production store."""
    view = (store_mod.ReadOnlyStore(Path(base)) if base
            else store_mod.production(args.store, _bases(args)))
    return view.read(artifact)


def _cmd_json(args: argparse.Namespace) -> int:
    if args.json_command == "canonicalize":
        # One destination cannot hold N results. The write below resolves `--out` inside the loop,
        # so with several operands the last one overwrote the rest while the loop printed a
        # `canonicalized <path>` line for every one of them - N successes reported, one file on disk
        # and no error anywhere.
        if args.out and len(args.files) > 1:
            raise Refused(
                f"json canonicalize was given --out and {len(args.files)} files: one destination "
                "holds one result, so all but the last would be discarded while every one of them "
                "reported success. Canonicalize them in place, or one at a time")
        for name in args.files:
            path = Path(name)
            if "shipped-tables" in path.name:
                raise Refused(
                    f"{path.name} is digested under the table-canonical form "
                    "(a Gson reparse-and-compact), not this one; a digest taken under one is "
                    "meaningless under the other"
                )
            text = canonical_json(read_json(path))
            write_text(_out_path(args) if args.out else path, text)
            print(f"canonicalized {path}")
        return OK

    found = jsondiff_mod.diff_files(
        Path(args.before), Path(args.after),
        levels=tuple(args.levels.split(",")) if args.levels else jsondiff_mod.LEVELS,
        payload_key=args.payload,
        ignore_keys=tuple(args.ignore_keys.split(",")) if args.ignore_keys else (),
    )
    payload = found.as_dict()
    lines = [f"L1 missing {len(found.missing)}  extra {len(found.extra)}",
             f"L2 changed {len(found.changed)}"]
    for row in found.changed[:args.max_findings]:
        lines.append(f"  {row['key']}: {row['before']!r} -> {row['after']!r}")
    lines.append(f"L3 {found.order or 'no order divergence'}")
    _emit(args, "\n".join(lines), payload)
    jsondiff_mod.raise_on(found)
    return OK


def _cmd_render_bytes(args: argparse.Namespace) -> int:
    names = tuple(args.name.split(",")) if args.name else render_mod.RENDER_NAMES
    verdict = render_mod.diff(Path(args.before), Path(args.after), names)
    lines = [f"identical {len(verdict.identical)}  moved {len(verdict.moved)}  "
             f"dropped {len(verdict.dropped)}  added {len(verdict.added)}"]
    for mover in verdict.moved[:40]:
        note = mover.get("mean_argb_delta")
        suffix = f"   mean_argb_delta {note[0]} -> {note[1]}" if note else ""
        lines.append(f"  moved  {mover['subject']}{suffix}")
    _emit(args, "\n".join(lines), verdict.as_dict())
    render_mod.raise_on(verdict)
    return OK


def _cmd_compare(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args))
    # Refuse a root that never finished: a stale tree reported as agreement is the recorded
    # `&& diff` trap, and COMPLETE being written last is what makes it detectable. Then refuse one
    # that MOVED after it finished, which is the same trap one step along: a self-capturing producer
    # rewrites its file whenever its suite runs, so a root can be part this capture and part the run
    # after it while COMPLETE still describes the first. `provenance` is outside the compared
    # payload, so the rewrite is invisible to every number the verdict prints.
    capture_mod.require_unmoved(root.root)

    wanted = [name.strip() for name in args.artifacts.split(",")] if args.artifacts else None
    if not wanted:
        wanted = sorted(_artifacts_in(root.root))
    if not wanted:
        raise MissingInput(f"no artifacts under {root.root}; name them with --artifacts")

    expected = compare_mod.load_expected(
        Path(args.expected) if args.expected else root.root / store_mod.RUN_DIR / "expected-diff.json")
    results = []
    missing = []
    for position, artifact in enumerate(wanted, start=1):
        _progress(args, f"compare {position}/{len(wanted)} {artifact}")
        try:
            base_payload = _load(args, artifact, args.base)
        except MissingInput:
            missing.append(artifact)
            continue
        results.append(compare_mod.compare(base_payload, root.read(artifact), expected))

    payload = compare_mod.to_report(results)
    # The capture this report is about, so a promotion can tell it from the report an earlier
    # capture left in the same single slot. Without it "a compare.json exists" is satisfied by any
    # compare ever run into this root, which is not evidence about the bytes being promoted.
    payload[compare_mod.CAPTURE_DIGEST] = capture_mod.content_digest(root.root)
    payload["missing_baseline"] = missing
    # Named rather than dropped: an artifact file in the root that this run did not capture is worth
    # a reader knowing about, and silence would read as "the root held nothing else".
    not_captured = _not_captured_in(root.root)
    if not_captured:
        payload["not_captured"] = not_captured
    agreement = _shipped_tables_agreement(args, root)
    payload["checks"] = {compare_mod.AGREEMENT_CHECK: agreement}
    if args.bootstrap:
        payload["bootstrap"] = True
    run = root.root / store_mod.RUN_DIR
    write_json(run / compare_mod.REPORT, payload)
    write_text(run / "compare.md", report_mod.render_diff(payload))
    # What a compare has to say about the tree it just measured: the fields that name that TREE
    # STATE, what the compare covered, and what it found. `plan --gate-exit` reads them to answer
    # "has this exact tree already been gated"; nothing else does, and nothing is written here that
    # it does not read. It is written into the working root and never into the store, because a
    # compare is a measurement - so a `cache/` clean re-arms the gate, which is the correct
    # degradation: the evidence for "already gated" is cache, and losing it costs one re-run rather
    # than a wrong answer.
    write_json(run / "last-verdict.json", {
        "artifacts": [result.artifact for result in results],
        "asset_dirty_digest": provenance_mod.dirty_digest(_bases(args)),
        "asset_sha": provenance_mod.asset_state(_bases(args))["asset_sha"],
        "missing_baseline": missing,
        "unexpected": payload["totals"]["unexpected"],
    })
    text = report_mod.render_diff(payload)
    if missing:
        text += f"\n\n**MISSING_BASELINE**: {', '.join(missing)}"
    if not_captured:
        text += (f"\n\n**not captured by this run** (in the root, stamped by no capture step): "
                 f"{', '.join(not_captured)}")
    text += "\n\n" + report_mod.render_checks(payload["checks"])
    _emit(args, text, payload)

    if missing and not args.bootstrap:
        raise ComparisonFailed(
            f"MISSING_BASELINE for {', '.join(missing)}: nothing to compare against. "
            "The first capture of an artifact is compared with --bootstrap")
    # Before the mover gate, because it is a POPULATION check like MISSING_BASELINE rather than a
    # value one: if the two artifacts disagree about which tables exist, the per-row comparison over
    # them is answering a question about a covered set that is already wrong.
    if agreement["status"] == "disagreed":
        raise ComparisonFailed(
            f"{compare_mod.AGREEMENT_CHECK} on digest.shipped-tables: "
            f"{', '.join(agreement['disagreements'])} "
            f"{'is' if len(agreement['disagreements']) == 1 else 'are'} covered by one of "
            f"{' / '.join(compare_mod.AGREEMENT_ARTIFACTS)} and not the other. The two digests are "
            "over different canonical forms and are never comparable value for value; the covered "
            "set is, and a table a flow added or dropped shows up exactly here")
    compare_mod.raise_on(results)
    return OK


def _shipped_tables_agreement(args: argparse.Namespace, root: store_mod.ReadOnlyStore) -> dict:
    """Resolve both operands and run the cross-artifact covered-set check.

    Each operand is taken from the working root when the root holds it and from the base otherwise,
    which is the state the store would be in **after** this capture were promoted - the thing a gate
    is actually asserting about. A capture naming only one of the two is the common case
    (``-Partifacts=digests``), and reading the other's last known value is what keeps the check
    evaluable there instead of quietly skipping on nearly every run.
    """
    payloads = {}
    absent = []
    for artifact in compare_mod.AGREEMENT_ARTIFACTS:
        for read in (lambda name=artifact: root.read(name),
                     lambda name=artifact: _load(args, name, args.base)):
            try:
                payloads[artifact] = read()
                break
            except (MissingInput, ValueError):
                continue
        if artifact not in payloads:
            absent.append(artifact)
    if absent:
        # Reported rather than silently omitted: a check that is never evaluated and never says so
        # is indistinguishable from one that passes.
        return {"absent": absent, "status": "not_evaluated"}
    disagreements = compare_mod.shipped_tables_agreement(*(payloads[name]
                                                           for name in compare_mod.AGREEMENT_ARTIFACTS))
    return {"disagreements": disagreements,
            "status": "disagreed" if disagreements else "agreed"}


def _cmd_capture_begin(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args)).root
    marker = capture_mod.begin(root)
    _emit(args, f"capture open: {marker}", {"open": str(marker)})
    return OK


def _cmd_capture_normalize(args: argparse.Namespace) -> int:
    repo = _bases(args)
    root = store_mod.working(args.root, repo).root
    sources = args.source
    if len(sources) not in (1, len(args.artifact)):
        raise Refused(f"give one --source, or one per --artifact ({len(args.artifact)})")
    # Conditional: a step is one process per artifact, so erasing here unconditionally leaves only
    # whichever row ran last. `capture-begin` is what erases when an invocation names several.
    capture_mod.join_or_begin(root)
    written = []
    for position, artifact in enumerate(args.artifact):
        source = Path(sources[0] if len(sources) == 1 else sources[position])
        _progress(args, f"capture {position + 1}/{len(args.artifact)} {artifact}")
        target = capture_mod.normalize(artifact, source, root, repo, producer=args.producer or "",
                                       mode=args.mode, flags=args.flag or (), runs=args.runs,
                                       logs=Path(args.logs) if args.logs else None,
                                       reference_tree=(Path(args.reference_tree)
                                                       if args.reference_tree else None),
                                       wall_time_ms=args.wall_time, store=args.store)
        written.append({"artifact": artifact, "path": str(target)})
    _emit(args, "\n".join(f"captured {row['artifact']} -> {row['path']}" for row in written),
          {"captured": written})
    return OK


def _cmd_capture_index(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args)).root
    marker = capture_mod.index(root)
    _emit(args, f"capture complete: {marker}", {"complete": str(marker)})
    return OK


def _derived_reach(base: Path, rules: Sequence[blindness_mod.Rule]) \
        -> blindness_mod.DerivedReach | None:
    """What answers a derived rule's selection, or nothing when the map has no derived rule.

    The committed graph, read once. Loading it only when some rule wants it is what keeps a map with
    none of them resolvable on a tree that has never run ``reach build`` - the file is a guarded
    artifact rather than a precondition of planning at all.

    :param base: the repository root
    :param rules: the map's rules, live triggers already folded in
    """
    if not any(rule.derived for rule in rules):
        return None
    payload = read_json(base / "parity" / reach_mod.STORED)
    return lambda path: reach_mod.answered_by(payload, path)


def _cmd_plan(args: argparse.Namespace) -> int:
    """Resolve what the working tree's change can be seen by. Measures nothing.

    It fails on exactly three things: an unreadable map, a usage error, and a changed path no rule
    covers. It never fails on reach - a wide reach is a big plan, and refusing one would be refusing
    to answer the question it was asked.

    It answers three ways. ``sees`` is what the change moves; ``plan`` is the part of it a capture
    writes a file for; ``covered`` is the part with no file of its own whose value the plan's own
    capture both writes and is compared on; ``manual`` is what is left, and each of its rows carries
    the act that would measure it - widening the capture to a container this plan missed, or reading
    a value no verdict anywhere reports.
    """
    base = _bases(args)
    root = store_mod.working(args.root, base).root
    rules, no_reach = blindness_mod.load(store_mod.resolve_store(args.store, base))
    # Re-derived from the tree rather than taken from the file, so a declaration that moved in the
    # commit being planned is answered by the plan that gates that commit rather than by the next
    # regeneration. The two halves agree by construction and a guard holds them to it.
    rules = declarations_mod.live(rules, base)

    changed = list(args.changed or [])
    if not changed or args.changed_from_git:
        changed = sorted(set(changed) | set(_changed_from_git(base)))
    reach = blindness_mod.resolve(changed, rules, no_reach, _derived_reach(base, rules))
    if reach.unknown:
        raise Refused(str(blindness_mod.UnknownReach(reach.unknown)))

    index = store_mod.production(args.store, base).index()
    plan, covered, manual = _split_reach(reach.sees, index)
    durations = [index.get(_STORE_MAP, {}).get(artifact, {}).get("last_duration_ms")
                 for artifact in plan]
    measured = [int(value) for value in durations if value is not None]
    budget = sum(measured)
    payload = {
        "//": "parity.report.plan · regen: ./gradlew parityPlan",
        "artifact": "report.plan",
        "blind": reach.blind,
        "budget_ms": budget,
        # How much of that number is real. A reader with the sum alone cannot tell a measured bundle
        # from one where most of the cost is missing, and the two call for opposite decisions.
        "budget_measured": len(measured),
        "changed": sorted(changed),
        # `sees` answers reach and `plan` selects a capture, which are two different sets: every
        # artifact the change moves, against the ones the store keeps a file for and a producer can
        # rewrite. `covered` and `manual` are the rest of `sees`, split by whether this plan's own
        # capture already GATES it - writes the value and is compared on it - so the three partition
        # the reach answer. A truncation nobody names reads as "that was all of it", and a remainder
        # nobody splits sends a reader to redo the capture's work by hand. Each `manual` row carries
        # an `action`, because that remainder is two kinds: one a wider capture reaches and one only
        # a human can.
        "covered": covered,
        "format": 1,
        "kind": "plan",
        "manual": manual,
        "plan": plan,
        "rules_fired": reach.fired,
        "sees": reach.sees,
    }
    write_json(root / store_mod.RUN_DIR / "plan.json", payload)

    lines = [f"SEES   ({len(reach.sees)}): " + (", ".join(reach.sees) or "(none)")]
    for entry in reach.blind:
        # A declaration another rule's `sees` overrules is printed as the contradiction it is rather
        # than dropped. The artifact still runs and is still compared - the bundle is unchanged - so
        # what the marker corrects is the ANSWER to "what is blind here", which used to come back
        # empty for a rule whose entire content was one blind line.
        overruled = (" claimed blind, selected by " + ", ".join(entry["selected_by"]) + " -"
                     if entry.get("selected_by") else "")
        lines.append(f"BLIND  {entry['artifact']} [{entry['rule']}]{overruled} {entry['reason']}")
    if reach.no_reach:
        lines.append("NO REACH: " + ", ".join(reach.no_reach))
    lines.append(f"PLAN   ({len(plan)}): " + (", ".join(plan) or "(none)"))
    planned = set(plan)
    if covered:
        lines.append(f"COVERED ({len(covered)}): no capture row of its own; each one's value is a "
                     "joined field of a file this plan already captures, so that capture writes it "
                     "and the compare reports a move in it")
        for entry in covered:
            rides = ", ".join(name for name in entry["containers"] if name in planned)
            lines.append(f"  {entry['artifact']} [{entry['home']}] {entry['where']} <- {rides}")
    if manual:
        lines.append(f"MANUAL ({len(manual)}): reached by this change and gated by nothing this "
                     "plan captures. A row saying `capture` names a file the store does keep, whose "
                     "compare joins the field, and this plan missed - so widening the capture to it "
                     "is the act that measures it; a row saying `read` is read where it lives, "
                     "because no verdict anywhere reports it")
        for entry in manual:
            act = (f"capture {','.join(entry['containers'])}"
                   if entry["action"] == _CAPTURE else "read it there")
            where = f" {entry['where']}" if entry["where"] else ""
            lines.append(f"  {entry['artifact']} [{entry['home']}]{where} - {act}")
    lines.append(f"BUDGET {budget} ms{_budget_caveat(len(measured), len(plan))}")
    # The standing verdict, on every plan rather than only under --gate-exit. The predicate is the
    # hook's own, so the two cannot drift; what differs is that this one REPORTS. A plan is a Gradle
    # Exec and a non-zero exit fails the build, so answering "already gated" in the exit code would
    # fail every plan of an ungated change - which is why the hook's flag exists at all.
    #
    # It is printed because the alternative is what the cost was: a capture is cheaper to re-run than
    # to reason about whether the last one still stands, so it got re-run. Whoever reads a plan is
    # about to spend the budget on the line above it, and this is the line that says they need not.
    gated = _gate_exit(args, base, root, reach, plan) if reach.sees else OK
    payload["already_gated"] = gated == GATE_ALREADY_GATED
    if gated == GATE_ALREADY_GATED:
        lines.append("VERDICT already gated - a passing compare covers this exact tree state and "
                     "this whole plan. Nothing here needs running; quote _run/compare.md. Editing "
                     "any tracked file re-arms it.")
    _emit(args, "\n".join(lines), payload)
    return _gate_exit(args, base, root, reach, plan) if args.gate_exit else OK


def _budget_caveat(measured: int, planned: int) -> str:
    """What the BUDGET line says beside its number, which depends on how much of the number is real.

    An artifact contributes to the budget only once a promotion has recorded how long its producers
    took, so a bundle is in one of three states. With none of them recorded the sum is zero and says
    nothing; with all of them it is the cost; and with some of them it is a **floor** that looks
    exactly like a cost, which is the state that needs saying out loud.

    That middle state could not arise while nothing wrote the column and every plan read ``0 ms``,
    which is why the line used to key its parenthetical off the sum being zero. It has been reachable
    since the first artifact was promoted carrying a duration, and it is the reading that costs
    something: a bundle whose measured half is cheap and whose unmeasured half boots the client reads
    as comfortably under the rule that says to background it.

    :param measured: how many of the plan's artifacts carry a recorded duration
    :param planned: how many artifacts the plan runs
    :return: the parenthetical to append, or an empty string when the number stands on its own
    """
    if not measured:
        return "  (no artifact in this plan has a recorded duration)"
    if measured < planned:
        return (f"  ({measured} of {planned} artifacts carry a duration, so this is a floor "
                "and not the cost)")
    return ""


#: The map of the index that registers an artifact the production store keeps a FILE for - the one
#: home a capture can write into, and therefore the one a plan may name.
_STORE_MAP = "artifacts"

#: Where the index registers an artifact whose home is not the store, and the field each of those
#: maps carries that says where its value is read. These three and ``artifacts`` ARE the four
#: artifact homes and a Java test holds them to it, so the split is read out of the store rather than
#: kept as a second copy of the taxonomy here.
_ELSEWHERE = {"pointers": "pointer", "external": "home", "sources": "test_class"}

#: The one map above whose field names a place INSIDE the store - a JSON pointer into another
#: artifact's file - so a capture can reach its value at all. ``external`` names a path outside the
#: store and ``sources`` a Java class, and their fields are free text in their own grammar: one that
#: happens to read as a store path is a coincidence, not a container, so only this map's targets are
#: ever matched against one.
_CONTAINED = "pointers"

#: The two acts a MANUAL row can ask for. ``capture`` names a container the store keeps a file for,
#: whose compare joins the node this row points at, and which this plan left out - so widening
#: ``-Partifacts`` to it is the act that measures the row. ``read`` is everything else, and is the
#: only one a human answers by hand: a home no capture writes, and equally a node no compare joins,
#: because a capture that cannot report a move on it does not measure it. The same word carried in
#: the payload and printed on the line, so a reader and a script agree on which.
_CAPTURE = "capture"
_READ = "read"

#: A ``<name>`` in a pointer's target stands for a family of container files rather than one:
#: ``sweeps/<sweep>.json`` is any sweep table, ``<artifact>`` is any artifact's own envelope. The
#: group is captured so a split keeps the placeholders alongside the literals.
_POINTER_WILDCARD = re.compile(r"(<[^>]*>)")


def _split_reach(sees: list[str], index: dict) -> tuple[list[str], list[dict], list[dict]]:
    """Split a reach answer into what a capture runs, what its verdict already covers, and the rest.

    ``sees`` answers reach: an artifact belongs in it whenever the change really does move that
    artifact, whatever home it lives in. A pointer into another artifact's file and a pin whose home
    is Java source both move for a real reason and neither has a capture row, so handing one to
    ``parityCapture`` refuses the whole invocation as Gradle resolves that task's dependencies while
    it builds the graph - which is what killed the documented plan-then-capture flow for every change
    under ``tooling/**``, ``pipeline/**`` or ``TrimKit``.

    The store's own ``artifacts`` map is the registry the split is taken against rather than a set
    spelled here, because that map is exactly what the roster registers as store-homed and one test
    relates the two; a Python copy would be a third spelling to keep in step.

    **Dropping from the plan is not the same as being uncovered**, but being written is not being
    gated either. A pointer whose target is a row field lands in the joined keyspace, so the capture
    that writes that file writes the value and the compare reports a move in it like any other row -
    ``report.diagnostics-log`` is the ``logs`` object of ``manifest.tooling-tables``, produced and
    diffed on every plan that names that manifest, and telling a reader to go and read that by hand
    would be telling them to redo work the capture already did.

    A pointer at a node the join never reads is the opposite case wearing the same clothes. Its
    container is captured, its value is written, and ``compare`` builds its rows out of the kind's
    rows member and ``logs`` alone - so a summary object, and every ``provenance`` field, is stored
    and never diffed. No widening of ``-Partifacts`` makes a verdict speak about it, which is why
    ``compare.joins`` decides containment here rather than the file half of the target alone.

    **What is left is therefore two kinds**, and each row says which. A pointer the compare does join
    on, whose container this plan missed, is measured by adding that container to the capture - so
    the row names it rather than sending a reader to open the store's own copy, which holds the last
    PROMOTED value and would report a stale baseline as a finding. Everything else - a pin homed in
    Java source, a value homed outside the store, a node no compare joins - is read where it lives,
    because reading it is the only answer there is.

    An index registering nothing has never been promoted into and can narrow nothing, so the reach
    answer stands whole. That direction is deliberate: a plan emptied by an unreadable registry reads
    downstream as "there was nothing to capture", where an unnarrowed one still refuses loudly.

    :param sees: the resolved reach, in its own order
    :param index: the production store's index envelope
    :return: the plan, the rows a plan member's own compare gates, and the rows nothing in the plan
        gates, each of the last carrying the act that would measure it
    """
    stored = index.get(_STORE_MAP) or {}
    if not stored:
        return list(sees), [], []
    plan = [artifact for artifact in sees if artifact in stored]
    planned = set(plan)
    covered: list[dict] = []
    manual: list[dict] = []
    for artifact in sees:
        if artifact in stored:
            continue
        row = _homed_elsewhere(artifact, index, stored)
        if planned.intersection(row["containers"]):
            covered.append(row)
        else:
            manual.append({**row, "action": _CAPTURE if row["containers"] else _READ})
    return plan, covered, manual


def _homed_elsewhere(artifact: str, index: dict, stored: dict) -> dict:
    """Locate one reached artifact the store keeps no file of its own for.

    :param artifact: the artifact id
    :param index: the production store's index envelope
    :param stored: the index's store-homed map, whose keys the containers are drawn from
    :return: its id, which map registers it, where its value is read, and which stored artifacts'
        captures gate it
    """
    for name, field in _ELSEWHERE.items():
        entry = index.get(name, {}).get(artifact)
        if entry is None:
            continue
        where = str(entry.get(field, ""))
        containers = _pointer_containers(where, stored) if name == _CONTAINED else []
        return {"artifact": artifact, "containers": containers, "home": name, "where": where}
    # Unregistered is reachable only through a store whose index and roster have parted company, and
    # saying so beats printing an empty home as though the lookup had succeeded.
    return {"artifact": artifact, "containers": [], "home": "unregistered", "where": ""}


def _pointer_containers(target: str, stored: dict) -> list[str]:
    """Which stored artifacts' captures GATE the value a pointer names.

    A target is ``<store-relative file>#<json pointer>`` and both halves are load-bearing. The file
    half is MATCHED against each stored artifact's own path rather than looked up, because it may
    name a family instead of one file and a placeholder there is the registration saying so. It is
    matched whole: a target naming part of a path - a stem without its extension, a directory - names
    a file the store does not keep, and crediting the file it is a prefix of would answer for a value
    that registration never pointed at.

    The pointer half then asks ``compare.joins``, because a container whose compare never reads that
    node writes the value and reports nothing about it. Being written is not being gated, and only a
    container that is both is worth naming: a plan that offered the other one would send a reader to
    run a capture whose verdict cannot move for the row they asked about.

    :param target: the pointer's registered target
    :param stored: the index's store-homed map
    :return: the container ids, sorted; empty when no stored file both carries the value and is
        joined on it
    """
    container, _, pointer = target.partition("#")
    member = pointer.strip("/").split("/")[0]
    return [artifact for artifact in _pointer_files(container, stored)
            if compare_mod.joins(str((stored[artifact] or {}).get("kind", "")), member)]


def _pointer_files(container: str, stored: dict) -> list[str]:
    """Which stored artifacts the file half of a pointer target names.

    :param container: the target's file half, a store-relative path with any placeholders left in
    :param stored: the index's store-homed map
    :return: the artifact ids whose own path the half names, sorted
    """
    pattern = re.compile("".join(
        ".+" if piece.startswith("<") else re.escape(piece)
        for piece in _POINTER_WILDCARD.split(container)))
    found = []
    for artifact in stored:
        try:
            path = store_mod.path_of(artifact)
        except MissingInput:
            # The naming rule answers for a kind prefix it knows; an id it does not is registered in
            # the store map without a path, which is a registry defect rather than a container.
            continue
        if pattern.fullmatch(path):
            found.append(artifact)
    return sorted(found)


#: `plan --gate-exit`'s tri-state, which is a REPORT rather than a failure - hence codes of its own
#: rather than the six in the table above. 10 and 20 both mean the command succeeded.
GATE_SEES_UNGATED = 10
GATE_ALREADY_GATED = 20


def _gate_exit(args: argparse.Namespace, base: Path, root: Path,
               reach: blindness_mod.Reach, plan: list[str]) -> int:
    """Answer the pre-commit hook's question in the exit code.

    Three answers: ``OK`` when no artifact can see the change, ``GATE_ALREADY_GATED`` when a passing
    compare verdict already covers this exact tree state, and ``GATE_SEES_UNGATED`` when artifacts
    see it and no such verdict exists. A verdict that found unexpected movers, or that found no
    baseline for something it was asked about, is a failure this tree carries rather than a gating
    of it.

    **The verdict is read globally over the working roots**, which ``_newest_verdict`` decides.

    **Every compare counts, a ``--base`` one included.** The stash A/B and the tooling-regen A/B put
    a capture on the other side rather than the store, and both are gatings in their own right - for
    a generator refactor the tooling-regen A/B is the only gate there is, every sweep being blind to
    it by construction. What that admits is the determinism pre-flight, whose two roots are captures
    of ONE tree and which therefore establishes reproducibility rather than neutrality: run on the
    tree being committed, over artifacts covering the whole plan, it reads here as a gating. Nothing
    a compare records tells the two apart - a capture's provenance names the commit and whether the
    tree was dirty, never which edit was in it - so this is a stated hole rather than a decision. It
    is bounded on both sides: the pre-flight is a precondition taken before the work, and both A/B
    flows end in the commit this gate is for.

    **It is opt-in, and that is not a style choice.** ``parityPlan`` is a Gradle ``Exec``, so a
    non-zero exit fails the build - a plan that answered 10 whenever reach was non-empty would fail
    the build on every real change, which is the opposite of what a plan is for. The flag is what
    lets one reach implementation serve a task that must always succeed and a hook that must answer
    in three states.

    :param args: the parsed namespace
    :param base: the repo root
    :param root: the working root
    :param reach: the resolved reach, which decides whether there is anything to gate at all
    :param plan: the capture set drawn from it, which is what a verdict is measured against
    :return: the exit code
    """
    if not reach.sees:
        return OK
    verdict = _newest_verdict(base, root)
    if verdict is None:
        return GATE_SEES_UNGATED
    try:
        recorded = read_json(verdict)
    except ValueError:
        return GATE_SEES_UNGATED
    sha = provenance_mod.asset_state(base)["asset_sha"]
    digest = provenance_mod.dirty_digest(base)
    # An unreadable git is not evidence of having been gated. Both sides must be present AND equal,
    # so a null on either side re-arms rather than short-circuits.
    if sha is None or digest is None:
        return GATE_SEES_UNGATED
    if recorded.get("asset_sha") != sha or recorded.get("asset_dirty_digest") != digest:
        return GATE_SEES_UNGATED
    # The tree matches, but a verdict that covered fewer artifacts than this plan needs has not
    # gated the difference. Against the PLAN, because a verdict records what a compare covered and a
    # compare covers what a capture produced: an id no capture produces can never appear there, so
    # comparing the reach answer makes the subset unsatisfiable and the hook asks on every commit
    # touching a path whose reach names one - which is the silence this predicate exists to earn.
    # Subset, never equality: a WIDER previous compare still covers this one.
    if not set(plan) <= set(recorded.get("artifacts", [])):
        return GATE_SEES_UNGATED
    # The tree matches and the compare covered the plan, and the compare said no. A verdict carrying
    # unexpected movers, or naming an artifact it found no baseline for, records a failure rather
    # than a gating - and this is the one automatic detector in the loop, so its going quiet after
    # exactly the run whose answer was RED is the worst state it can be in. Both fields are written
    # by every compare; until here nothing read either.
    if int(recorded.get("unexpected") or 0) > 0 or recorded.get("missing_baseline"):
        return GATE_SEES_UNGATED
    return GATE_ALREADY_GATED


def _newest_verdict(base: Path, root: Path) -> Path | None:
    """The most recent compare verdict, over the roots searched rather than out of one.

    **A verdict names a tree state and what a compare covered, and neither is a property of the root
    the capture went into.** Read out of one root, it was read out of the default one - which the
    promotion procedure does not use: its compare carries ``-PparityRoot=cache/parity/b``, as the
    determinism pre-flight's does, so a promotion left its verdict where nothing looked and the gate
    answered "never gated" on the commit that was gated hardest. Every directory **directly** under
    ``cache/parity/`` is therefore searched, and the root this invocation was given is searched
    beside them - a working root is any relative path under ``cache/``, so it need not be one of
    them, and a nested one such as ``cache/parity/x/y`` is reached only by being the root given.

    The newest wins, over candidates enumerated in path order, by ``st_mtime_ns`` and then by the
    greater path. The stamp alone does not order them: two writes here can land on one
    ``st_mtime_ns``, and a pair a key leaves equal is answered by whichever the enumeration yielded
    first. With the path beside it the key is a total order, so the answer is a function of which
    roots hold a verdict and of nothing else. Two verdicts about one tree are two compares of it and
    the later one is the one that stands - a red compare after a green one is the state to report,
    and taking any matching verdict instead would let the green one it superseded answer for it.

    :param base: the repo root
    :param root: the working root this invocation was given
    :return: the verdict file, or None when nothing searched holds one
    """
    candidates = {root / store_mod.RUN_DIR / "last-verdict.json"}
    candidates.update((base / store_mod.WORKING).parent
                      .glob(f"*/{store_mod.RUN_DIR}/last-verdict.json"))
    # Enumerated in path order rather than in a set's own: `PurePath.__hash__` hashes the case-folded
    # string, str hashing is seed-randomized per process, and a two-element set therefore hands its
    # members over in an order that differs between two runs over one tree. The key below orders any
    # two distinct paths on its own and so cannot see this - what it buys is that a reading of that
    # key which does NOT order some pair still answers the same thing twice, rather than flapping in
    # the one place where two answers over one tree is the whole failure being designed out.
    found = sorted(path for path in candidates if path.is_file())
    if not found:
        return None
    return max(found, key=lambda path: (path.stat().st_mtime_ns, str(path)))


def _changed_from_git(base: Path) -> list[str]:
    """The working tree's changed set: tracked modifications plus untracked, unignored files."""
    changed: list[str] = []
    for command in (["git", "diff", "--name-only", "HEAD"],
                    ["git", "ls-files", "--others", "--exclude-standard"]):
        result = subprocess.run(command, cwd=base, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            raise MissingInput(f"{' '.join(command)} failed: {result.stderr.strip()}")
        changed.extend(line.strip().replace("\\", "/") for line in result.stdout.splitlines()
                       if line.strip())
    return changed


def _cmd_expect(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args)).root
    target = root / store_mod.RUN_DIR / "expected-diff.json"
    # A clear and a registration are two different orders. Taking either silently drops the other,
    # and the one that would be dropped here is the registration - so a command that named a row and
    # a value would report "0 mover(s) registered" and exit 0.
    named = [flag for flag, value in (("--artifact", args.artifact), ("--key", args.key),
                                      ("--to", args.to), ("--reason", args.reason)) if value]
    if args.empty and named:
        raise Refused(f"expect was given --empty and {', '.join(named)} together: a clear and a "
                      "registration are two different orders, and one of them would be silently "
                      "dropped. Clear first, then register")
    payload = compare_mod.load_expected(target) or compare_mod.empty_expected() \
        if not args.empty else compare_mod.empty_expected()
    if not args.empty:
        if not (args.artifact and args.key and args.to and args.reason):
            raise Refused("expect needs --artifact, --key, --to and --reason, or --empty")
        payload["movers"].append({"artifact": args.artifact, "key": args.key,
                                  "reason": args.reason, "to": args.to})
    write_json(target, payload)
    _emit(args, f"{len(payload['movers'])} mover(s) registered -> {target}", payload)
    return OK


def _cmd_provenance(args: argparse.Namespace) -> int:
    record = provenance_mod.gather(args.artifact, _bases(args), producer=args.producer,
                                   mode=args.mode, flags=args.flag or (), runs=args.runs,
                                   reason=args.reason or "")
    _emit(args, canonical_json(record), record)
    return OK


def _cmd_promote_plan(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args)).root
    base = store_mod.production(args.store, _bases(args))
    entries = promote_mod.plan(root, base, _wanted(args))
    payload = promote_mod.to_report(entries)
    run = root / store_mod.RUN_DIR
    write_json(run / "promote.json", payload)
    write_text(run / "promote.md", report_mod.render(payload, "promotion-plan"))
    _emit(args, _plan_text(payload), payload)
    return OK


def _cmd_promote_apply(args: argparse.Namespace) -> int:
    repo = _bases(args)
    root = store_mod.working(args.root, repo).root
    target = store_mod.WritableStore(store_mod.resolve_store(args.store, repo))
    entries = promote_mod.plan(root, target, _wanted(args))

    # Re-run the plan and re-write the report BEFORE applying, so the human report exists whether
    # or not promote-plan was run separately.
    payload = promote_mod.to_report(entries)
    run = root / store_mod.RUN_DIR
    write_json(run / "promote.json", payload)
    write_text(run / "promote.md", report_mod.render(payload, "promotion-plan"))

    promote_mod.check(root, entries, args.reason or "", target.index(), args.allow_partial,
                      args.bootstrap, args.allow_dirty, args.population_changed, repo=_bases(args))
    for position, entry in enumerate(entries, start=1):
        _progress(args, f"promote {position}/{len(entries)} {entry.artifact} ({entry.action})")
    result = promote_mod.apply(root, target, entries, args.reason,
                               parity_class=args.parity_class,
                               allow_partial=args.allow_partial,
                               population_changed=args.population_changed,
                               allow_dirty=args.allow_dirty)
    _emit(args, f"promoted {len(result['promoted'])}: {', '.join(result['promoted']) or 'nothing'}",
          {**payload, "applied": result})
    return OK


def _cmd_panel(args: argparse.Namespace) -> int:
    from parity import panel as panel_mod
    rows = panel_mod.walk(Path(args.source), args.subject, columns=args.columns, bbox=args.bbox)
    payload = {"format": 1, "kind": "panel-stats", "subjects": rows}
    if not args.out:
        # A probe writes under _run/probes/, which is never promoted.
        target = store_mod.working(args.root, _bases(args)).root / store_mod.RUN_DIR / "probes" / "panel-stats.json"
        write_json(target, payload)
    lines = []
    for row in rows:
        attribution = row["attribution"]
        lines.append(
            f"{row['subject']}\n"
            f"  mean_over_white     {row['mean_over_white']:.4f}   (0..765, comparable to mean_argb_delta)\n"
            f"  mean_abs_argb_1020  {row['mean_abs_argb_1020']:.4f}   (0..1020, comparable to nothing else)\n"
            f"  mean_signed_luma    {row['mean_signed_luma']:+.4f}\n"
            f"  differing px        {row['differing_pixels']}\n"
            f"  coverage            v {row['coverage']['vanilla']}  j {row['coverage']['java']}  "
            f"both {row['coverage']['both']}  v-only {row['coverage']['vanilla_only']}  "
            f"j-only {row['coverage']['java_only']}\n"
            f"  silhouette          iou {row['silhouette']['iou']:.4f}  "
            f"imbalance {row['silhouette']['coverage_imbalance']:.4f}\n"
            f"  attribution         vanilla-only {attribution['vanilla_only']:.1f}%  "
            f"java-only {attribution['java_only']:.1f}%  both {attribution['both_colour']:.1f}% "
            f"(big>{panel_mod.BIG}: {attribution['big_pixels']}px {attribution['big_mass']:.0f}%)")
        if row.get("columns"):
            column = row["columns"]
            lines.append(f"  centre column       {column['centre_column']} "
                         f"share {column['centre_share']:.4f} ({column['width_parity']} width)")
        if row.get("bbox"):
            lines.append(f"  content bbox        vanilla {row['bbox']['vanilla']}  "
                         f"java {row['bbox']['java']}")
    _emit(args, "\n".join(lines), payload)
    return OK


def _cmd_lab(args: argparse.Namespace) -> int:
    # Resolved before the optional imports and before a pixel is read. The two lab writers below are
    # the expensive ones in the toolkit - a census joins three whole-frame dumps - and a refusal
    # earned after that work is a refusal that costs what it refuses. It also puts the guard in
    # front of the numpy/Pillow import, so where a command may write is answerable without them.
    target = _out_path(args) if args.out else None

    from parity import panel  # noqa: F401  - proves the optional pair is importable before use
    from parity.lab import census as census_mod
    from parity.lab import crop as crop_mod
    from parity.lab import explain as explain_mod
    from parity.lab import predict as predict_mod
    from parity.lab import px as px_mod

    probes = store_mod.working(args.root, _bases(args)).root / store_mod.RUN_DIR / "probes"
    if args.lab_command == "census":
        payload = census_mod.census(Path(args.allpass), Path(args.raw), Path(args.landed),
                                    Path(args.vanilla), Path(args.java))
        write_json(target or probes / "contests.json", payload)
        _emit(args, f"aligned {payload['totals']['aligned_px']} px, "
                    f"{payload['totals']['contests']} contests, "
                    f"misaligned {payload['misaligned_px']}; classes {payload['classes']}", payload)
        return OK
    if args.lab_command == "explain":
        region = tuple(int(part) for part in args.region.split(","))
        payload = explain_mod.explain(Path(args.dump), Path(args.vanilla), Path(args.java),
                                      region, args.threshold, args.tol)
        lines = [f"region {payload['region']}  differing {payload['totals']['differing']}"]
        for row in payload["classes"]:
            lines.append(f"  {row['class']:22s} {row['count']:6d} ({row['share']:5.1f}%)  "
                         f"eg {row['examples']}")
        _emit(args, "\n".join(lines), payload)
        return OK
    if args.lab_command == "predict":
        payload = predict_mod.compare(Path(args.contests))
        lines = ["predicting vanilla's verdict on these contests:"]
        for name, value in sorted(payload["predictors"].items()):
            shown = value if not isinstance(value, dict) else value["accuracy"]
            lines.append(f"  {name:30s} {shown:5.1f}%")
        _emit(args, "\n".join(lines), payload)
        return OK
    if args.lab_command == "px":
        coordinates = [tuple(int(part) for part in pair.split(",")) for pair in args.pixel]
        payload = px_mod.inspect(Path(args.dump), Path(args.vanilla), Path(args.java), coordinates)
        lines = []
        for row in payload:
            lines.append(f"=== {row['pixel']}  vanilla={row['vanilla']}  java={row['java']}  "
                         f"d={row['delta']}")
            for entry in row["fragments"]:
                lines.append(f"   [{entry['index']}] {entry['tag']:26s} d={entry['depth']:+.8f} "
                             f"{entry['blend']:8s} -> {entry['running']}")
        _emit(args, "\n".join(lines), {"pixels": payload})
        return OK
    region = tuple(int(part) for part in args.region.split(","))
    out = crop_mod.crop(Path(args.vanilla), Path(args.java), region,
                        target or Path("crop.png"), args.zoom)
    print(f"wrote {out}  vanilla | java | |delta|x4")
    return OK


def _wanted(args: argparse.Namespace) -> list[str] | None:
    return [name.strip() for name in args.artifacts.split(",")] if args.artifacts else None


def _plan_text(payload: dict) -> str:
    totals = payload["totals"]
    lines = [f"new {totals['new']}  replace {totals['replace']}  unchanged {totals['unchanged']}"]
    for entry in payload["entries"]:
        note = f"  ({entry['movers']} movers)" if entry["action"] == "replace" else ""
        lines.append(f"  {entry['action']:<10} {entry['artifact']}{note}")
    return "\n".join(lines)


def _artifacts_in(root: Path) -> list[str]:
    """Every artifact this root CAPTURED, which is not every artifact file in it."""
    return [artifact for artifact, stamped in store_mod.artifact_files(root) if stamped]


def _not_captured_in(root: Path) -> list[str]:
    """Artifact files a producer left in the root that no capture step stamped."""
    return sorted(artifact for artifact, stamped in store_mod.artifact_files(root) if not stamped)


def _cmd_report(args: argparse.Namespace) -> int:
    if not args.out:
        raise Refused("report render needs --out")
    payload = read_json(Path(args.input))
    write_text(_out_path(args), report_mod.render(payload, args.kind))
    print(f"wrote {args.out}")
    return OK


Command = Callable[[argparse.Namespace], int]


def _register(subparsers: Any) -> dict[str, Command]:
    """Commands land here as their phases add them; the frame does not change."""
    table: dict[str, Command] = {}

    subparsers.add_parser("doctor", help="report the interpreter, the roots and the optional imports")
    table["doctor"] = _cmd_doctor

    ids_parser = subparsers.add_parser("ids", help="parse or format a subject id")
    ids_sub = ids_parser.add_subparsers(dest="ids_command", required=True)
    parse_parser = ids_sub.add_parser("parse", help="parse a reference stem")
    parse_parser.add_argument("stem")
    format_parser = ids_sub.add_parser("format", help="format a stem in one spelling")
    format_parser.add_argument("stem")
    format_parser.add_argument("--spelling", required=True,
                               choices=[spelling.value for spelling in ids_mod.Spelling])
    table["ids"] = _cmd_ids

    triggers_parser = subparsers.add_parser(
        "triggers", help="regenerate every rule's trigger_paths from the source tree")
    triggers_parser.add_argument("--check", action="store_true",
                                 help="answer what would move and write nothing")
    table["triggers"] = _cmd_triggers

    reach_parser = subparsers.add_parser(
        "reach", help="which artifacts a changed Java type can move, off the constant pool")
    reach_sub = reach_parser.add_subparsers(dest="reach_command", required=True)
    reach_of = reach_sub.add_parser("of", help="the artifacts the given paths reach")
    reach_of.add_argument("path", nargs="+")
    reach_why = reach_sub.add_parser("why", help="the reference chain behind one answer")
    reach_why.add_argument("path")
    reach_why.add_argument("artifact")
    reach_sub.add_parser("orphans", help="types no producer root reaches")
    reach_sub.add_parser("check", help="what would move in the committed graph; writes nothing")
    reach_build = reach_sub.add_parser("build", help="write the derived graph")
    reach_build.add_argument("target", nargs="?", default=None)
    table["reach"] = _cmd_reach

    selftest_parser = subparsers.add_parser("selftest", help="run the toolkit's own unittest suite")
    selftest_parser.add_argument("-k", dest="pattern", default=None, metavar="PATTERN")
    table["selftest"] = _cmd_selftest

    for name, helptext in (("sum", "the fleet sum per sweep"),
                           ("buckets", "cumulative bucket counts per sweep")):
        reader = subparsers.add_parser(name, help=helptext)
        reader.add_argument("sweeps", nargs="*", metavar="SWEEP",
                            help=f"one or more of {' '.join(sweep_mod.SWEEPS)}")
        # --from reads a RAW producer directory and survives here and on buckets alone, which is
        # why it is not a global: every other command joins stored artifacts.
        reader.add_argument("--from", dest="source", default=None, metavar="PATH")
    table["sum"] = _cmd_sum
    table["buckets"] = _cmd_buckets

    man = subparsers.add_parser("manifest", help="build, verify or export a tree digest manifest")
    man_sub = man.add_subparsers(dest="manifest_command", required=True)
    build_parser = man_sub.add_parser("build")
    build_parser.add_argument("--artifact", required=True)
    build_parser.add_argument("--source", required=True, metavar="DIR")
    build_parser.add_argument("--glob", action="append", default=None, metavar="PAT")
    build_parser.add_argument("--exclude", action="append", default=None, metavar="PAT")
    verify_parser = man_sub.add_parser("verify")
    verify_parser.add_argument("--artifact", required=True)
    verify_parser.add_argument("--source", default=None, metavar="DIR")
    verify_parser.add_argument("--base", default=None, metavar="DIR")
    export_parser = man_sub.add_parser("export")
    export_parser.add_argument("--artifact", required=True)
    export_parser.add_argument("--base", default=None, metavar="DIR")
    table["manifest"] = _cmd_manifest

    js = subparsers.add_parser("json", help="canonicalize or semantically diff JSON")
    js_sub = js.add_subparsers(dest="json_command", required=True)
    canon = js_sub.add_parser("canonicalize")
    canon.add_argument("files", nargs="+", metavar="FILE")
    semantic = js_sub.add_parser("semantic-diff")
    semantic.add_argument("--before", required=True)
    semantic.add_argument("--after", required=True)
    semantic.add_argument("--levels", default=None, help="comma list of L1,L2,L3")
    semantic.add_argument("--payload", default=None, metavar="KEY")
    semantic.add_argument("--ignore-keys", default=None)
    semantic.add_argument("--max-findings", type=int, default=40)
    table["json"] = _cmd_json

    rb = subparsers.add_parser("render-bytes", help="per-subject rendered-byte diff of two trees")
    rb_sub = rb.add_subparsers(dest="render_command", required=True)
    rb_diff = rb_sub.add_parser("diff")
    rb_diff.add_argument("--before", required=True, metavar="DIR")
    rb_diff.add_argument("--after", required=True, metavar="DIR")
    rb_diff.add_argument("--name", default=None, help="comma list, default java.png,java.gif")
    table["render-bytes"] = _cmd_render_bytes

    cmp_parser = subparsers.add_parser("compare", help="join two stored artifacts; THE gate")
    cmp_parser.add_argument("--artifacts", default=None, help="one comma list of artifact ids")
    cmp_parser.add_argument("--base", default=None, metavar="DIR")
    cmp_parser.add_argument("--expected", default=None, metavar="FILE")
    # `--include-stale` was registered here and stamped one field nothing read or rendered. It was
    # the escape hatch for a refusal - an artifact captured before the inputs it was taken from -
    # that exists nowhere in this package, so a stale artifact was admitted always rather than only
    # under the flag. Dropped rather than left advertising a defence that was never built; the
    # deleted-spellings roster is what makes a resurrection visible instead of silently accepted.
    cmp_parser.add_argument("--bootstrap", action="store_true")
    table["compare"] = _cmd_compare

    rep = subparsers.add_parser("report", help="render a stored artifact or a diff as Markdown")
    rep_sub = rep.add_subparsers(dest="report_command", required=True)
    rep_render = rep_sub.add_parser("render")
    rep_render.add_argument("--in", dest="input", required=True, metavar="FILE")
    rep_render.add_argument("--kind", default=None)
    table["report"] = _cmd_report

    subparsers.add_parser("capture-begin",
                          help="erase the working root and open a capture; the erase happens here, once")
    table["capture-begin"] = _cmd_capture_begin

    cap = subparsers.add_parser("capture-normalize",
                                help="read a producer's raw output into the working root as canonical JSON")
    cap.add_argument("--artifact", action="append", required=True)
    cap.add_argument("--source", action="append", required=True, metavar="PATH")
    cap.add_argument("--producer", default=None, help="one comma list of producing task names")
    cap.add_argument("--flag", action="append", default=None, metavar="k=v")
    cap.add_argument("--runs", type=int, default=None,
                     help="how many runs AGREED, a measurement; absent means the artifact's "
                          "declared floor, which is a property of the artifact and not of a build")
    cap.add_argument("--logs", default=None, metavar="DIR",
                     help="digest each producer's normalized diagnostics log into the manifest's "
                          "`logs` key; a byte-identical table is not an unchanged run")
    cap.add_argument("--mode", default=None, metavar="NAME[,NAME]",
                     help="which harness render mode wrote the reference tree in the invocation "
                          "this capture belongs to - the difference between ground truth refreshed "
                          "in part and refreshed whole. A comma-joined set when one invocation ran "
                          "more than one, and absent when it refreshed nothing")
    cap.add_argument("--wall-time", dest="wall_time", type=int, default=None, metavar="MS",
                     help="how long this row's producers took, measured by the build. It is what "
                          "the plan's budget sums and what the skill's background-the-capture rule "
                          "reads; absent, the record carries no duration rather than a zero")
    cap.add_argument("--reference-tree", default=None, metavar="DIR",
                     help="the reference set this artifact was measured against, digested through "
                          "its manifest into one provenance field; absent, the record cannot say "
                          "which ground truth produced its numbers")
    table["capture-normalize"] = _cmd_capture_normalize

    subparsers.add_parser("capture-index", help="write _run/_capture.json then COMPLETE last")
    table["capture-index"] = _cmd_capture_index

    pln = subparsers.add_parser("plan", help="resolve which artifacts can SEE the working tree's change")
    pln.add_argument("--changed-from-git", action="store_true", dest="changed_from_git")
    pln.add_argument("--changed", action="append", default=None, metavar="PATH")
    pln.add_argument("--gate-exit", action="store_true", dest="gate_exit",
                     help=f"answer in the exit code: 0 nothing sees it, {GATE_SEES_UNGATED} seen and "
                          f"ungated, {GATE_ALREADY_GATED} already gated for this tree. For the "
                          "pre-commit hook; parityPlan must always exit 0, so it is opt-in")
    table["plan"] = _cmd_plan

    exp = subparsers.add_parser("expect", help="register the movers a phase intends")
    exp.add_argument("--empty", action="store_true")
    exp.add_argument("--artifact", default=None)
    exp.add_argument("--key", default=None, help="keyed the way that artifact's envelope key names")
    exp.add_argument("--to", default=None)
    exp.add_argument("--reason", default=None)
    table["expect"] = _cmd_expect

    prov = subparsers.add_parser("provenance", help="gather a run-provenance record")
    prov_sub = prov.add_subparsers(dest="provenance_command", required=True)
    prov_gather = prov_sub.add_parser("gather")
    prov_gather.add_argument("--artifact", required=True)
    prov_gather.add_argument("--producer", required=True)
    prov_gather.add_argument("--mode", default=None)
    prov_gather.add_argument("--flag", action="append", default=None, metavar="k=v")
    prov_gather.add_argument("--runs", type=int, default=0)
    prov_gather.add_argument("--reason", default=None)
    table["provenance"] = _cmd_provenance

    pplan = subparsers.add_parser("promote-plan", help="read-only: what promoting would change")
    pplan.add_argument("--artifacts", default=None)
    pplan.add_argument("--base", default=None, metavar="DIR")
    table["promote-plan"] = _cmd_promote_plan

    papply = subparsers.add_parser("promote-apply",
                                   help="THE only writer of production; requires --reason")
    papply.add_argument("--reason", default=None, required=False)
    papply.add_argument("--artifacts", default=None)
    papply.add_argument("--class", dest="parity_class", default="moving", choices=promote_mod.CLASSES,
                        help="defaults to moving, because forgetting it cannot then understate a change")
    papply.add_argument("--population-changed", action="store_true")
    papply.add_argument("--allow-partial", action="store_true")
    papply.add_argument("--allow-dirty", action="store_true", dest="allow_dirty",
                        help="promote a capture taken from an uncommitted tree, recording the "
                             "exception in provenance; the baseline is then re-derivable from no "
                             "commit and nothing later can recover that")
    papply.add_argument("--bootstrap", action="store_true")
    table["promote-apply"] = _cmd_promote_apply

    # panel stats is always registered and exits 4 when the optional pair is absent, because a
    # command that vanishes is indistinguishable from one that was never spelled right.
    pan = subparsers.add_parser("panel", help="re-derive the panel statistics (a PROBE, never a gate)")
    pan_sub = pan.add_subparsers(dest="panel_command", required=True)
    pan_stats = pan_sub.add_parser("stats")
    pan_stats.add_argument("--source", required=True, metavar="DIR")
    pan_stats.add_argument("--subject", action="append", default=None, metavar="ID")
    pan_stats.add_argument("--columns", action="store_true", help="per-column profile + centre share")
    pan_stats.add_argument("--bbox", action="store_true", help="canvas-vs-content back-solve")
    table["panel"] = _cmd_panel

    # The lab group is registered ONLY when the optional pair is importable, so `lab --help` on a
    # bare interpreter says what is missing rather than offering commands that cannot run.
    from parity import pixels as pixels_mod
    if pixels_mod.available():
        lab = subparsers.add_parser("lab", help="the [PX] fragment family (probes, never a gate)")
        lab_sub = lab.add_subparsers(dest="lab_command", required=True)

        lab_census = lab_sub.add_parser("census", help="the three-dump join and contest harvest")
        for name in ("allpass", "raw", "landed", "vanilla", "java"):
            lab_census.add_argument(f"--{name}", required=True)

        lab_explain = lab_sub.add_parser("explain", help="smallest fragment set to drop")
        lab_explain.add_argument("--dump", required=True)
        lab_explain.add_argument("--vanilla", required=True)
        lab_explain.add_argument("--java", required=True)
        lab_explain.add_argument("--region", required=True, metavar="x0,y0,x1,y1")
        lab_explain.add_argument("--threshold", type=int, default=8)
        lab_explain.add_argument("--tol", type=int, default=1)

        lab_predict = lab_sub.add_parser("predict", help="predictor comparison over a contest table")
        lab_predict.add_argument("--contests", required=True, metavar="FILE")

        lab_px = lab_sub.add_parser("px", help="the full composite chain at one pixel")
        lab_px.add_argument("--dump", required=True)
        lab_px.add_argument("--vanilla", required=True)
        lab_px.add_argument("--java", required=True)
        lab_px.add_argument("--pixel", action="append", required=True, metavar="x,y")

        lab_crop = lab_sub.add_parser("crop", help="the zoomed side-by-side LOOK image")
        lab_crop.add_argument("--vanilla", required=True)
        lab_crop.add_argument("--java", required=True)
        lab_crop.add_argument("--region", required=True, metavar="x0,y0,x1,y1")
        lab_crop.add_argument("--zoom", type=int, default=8)
        table["lab"] = _cmd_lab

    return table


def build_parser() -> tuple[argparse.ArgumentParser, dict[str, Command]]:
    """The command parser. The globals are shown in its help and consumed before it runs."""
    parser = argparse.ArgumentParser(
        prog="python parity/scripts/parity",
        description="The parity toolkit: one writer, one store root, one id grammar.",
        parents=[_globals()],
    )
    parser.add_argument("--version", action="version", version=f"parity {VERSION}")
    subparsers = parser.add_subparsers(dest="command", required=True)
    return parser, _register(subparsers)


def main(argv: list[str] | None = None) -> int:
    supplied = list(sys.argv[1:] if argv is None else argv)
    # The globals come off the whole argv first, so they are honoured on either side of the
    # subcommand and the command parser never sees them.
    options, rest = _globals().parse_known_args(supplied)
    parser, table = build_parser()
    args = parser.parse_args(rest)
    for key, value in vars(options).items():
        setattr(args, key, value)
    try:
        return table[args.command](args)
    except Exception as error:  # noqa: BLE001 - the translation point, by design
        for kind, code in _EXIT_FOR:
            if isinstance(error, kind):
                sys.stderr.write(f"{error}\n")
                return code
        raise
