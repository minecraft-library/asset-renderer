"""The argparse tree, the global options, and the one place an exception becomes an exit code.

No module calls ``sys.exit``. Each raises a typed error from ``norm`` and ``main`` translates it,
so the six-code table is enforceable rather than conventional - and the separation that matters,
between "the answer is no" (1) and "I could not look" (3, 5), is structural.

This is the only module allowed to ``print``: stdout framing is its job. It still writes every file
through ``norm``.
"""

from __future__ import annotations

import argparse
import sys
import unittest
from pathlib import Path
from typing import Any, Callable

from parity import VERSION
from parity import compare as compare_mod
from parity import ids as ids_mod
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
    fixed,
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
                        help="the repo containing scripts/parity (default: derived, so cwd never matters)")
    parser.add_argument("--root", default=None, metavar="DIR",
                        help=f"the WORKING root (default: {store_mod.WORKING}); must be relative and under cache/")
    parser.add_argument("--store", default=None, metavar="DIR",
                        help=f"the production store (default: {store_mod.PRODUCTION})")
    parser.add_argument("--format", choices=("text", "json"), default="text",
                        help="stdout form; stderr always carries progress")
    parser.add_argument("--out", default=None, metavar="FILE",
                        help="write the command's primary artifact here instead of stdout")
    parser.add_argument("-q", "--quiet", action="store_true",
                        help="suppress progress on stderr; never affects stdout")
    return parser


def _bases(args: argparse.Namespace) -> Path:
    return Path(args.repo_root).resolve() if args.repo_root else store_mod.repo_root()


def _emit(args: argparse.Namespace, text: str, payload: Any = None) -> None:
    """stdout carries the answer and nothing else; ``--out`` redirects it through ``norm``."""
    body = canonical_json(payload) if args.format == "json" and payload is not None else text
    if args.out:
        target = Path(args.out)
        if store_mod.production(args.store, _bases(args)).contains(target):
            raise Refused(f"--out would write inside the production store: {args.out}")
        write_text(target, body)
        print(f"wrote {target}")
        return
    print(body)


def _progress(args: argparse.Namespace, message: str) -> None:
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
    """Resolve the requested sweeps from either a raw producer directory or the working root."""
    if args.source:
        found = sweep_mod.discover(Path(args.source))
    else:
        root = store_mod.working(args.root, _bases(args))
        found = {}
        for name in sweep_mod.SWEEPS:
            candidate = root.path(f"sweep.{name}")
            if candidate.is_file():
                found[name] = candidate
        if not found:
            raise MissingInput(f"no sweep artifacts under {root.root}; pass --from to read a producer tree")
    wanted = args.sweeps or [name for name in sweep_mod.SWEEPS if name in found]
    unknown = [name for name in wanted if name not in sweep_mod.SWEEPS]
    if unknown:
        raise MissingInput(f"unknown sweep(s) {unknown}; known: {list(sweep_mod.SWEEPS)}")
    missing = [name for name in wanted if name not in found]
    if missing:
        raise MissingInput(f"no table for sweep(s) {missing}")
    return [(name, sweep_mod.read_table(found[name], name)) for name in wanted]


def _cmd_sum(args: argparse.Namespace) -> int:
    rows = sweep_mod.summarise(_tables(args))
    lines = [f"{row['sweep']:<7} {row['rows']:>5} rows   sum {fixed(row['sum']):>12}"
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
        write_text(Path(args.out), manifest_mod.export_text(stored))
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
        for name in args.files:
            path = Path(name)
            if "shipped-tables" in path.name:
                raise Refused(
                    f"{path.name} is digested under the table-canonical form "
                    "(a Gson reparse-and-compact), not this one; a digest taken under one is "
                    "meaningless under the other"
                )
            text = canonical_json(read_json(path))
            write_text(Path(args.out) if args.out else path, text)
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
    wanted = [name.strip() for name in args.artifacts.split(",")] if args.artifacts else None
    if not wanted:
        wanted = sorted(_artifacts_in(root.root))
    if not wanted:
        raise MissingInput(f"no artifacts under {root.root}; name them with --artifacts")

    expected = compare_mod.load_expected(
        Path(args.expected) if args.expected else root.root / store_mod.RUN_DIR / "expected-diff.json")
    results = []
    for artifact in wanted:
        results.append(compare_mod.compare(_load(args, artifact, args.base), root.read(artifact),
                                           expected))
    payload = compare_mod.to_report(results)
    run = root.root / store_mod.RUN_DIR
    write_json(run / "compare.json", payload)
    write_text(run / "compare.md", report_mod.render_diff(payload))
    _emit(args, report_mod.render_diff(payload), payload)
    compare_mod.raise_on(results)
    return OK


def _artifacts_in(root: Path) -> list[str]:
    found = []
    for path in sorted(root.rglob("*.json")):
        if store_mod.RUN_DIR in path.parts:
            continue
        try:
            payload = read_json(path)
        except ValueError:
            continue
        if isinstance(payload, dict) and payload.get("artifact"):
            found.append(payload["artifact"])
    return found


def _cmd_report(args: argparse.Namespace) -> int:
    if not args.out:
        raise Refused("report render needs --out")
    payload = read_json(Path(args.input))
    write_text(Path(args.out), report_mod.render(payload, args.kind))
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
        reader.add_argument("--base", default=None, metavar="DIR")
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
    export_parser.add_argument("--grammar", default="a", choices=("a",))
    export_parser.add_argument("--base", default=None, metavar="DIR")
    table["manifest"] = _cmd_manifest

    js = subparsers.add_parser("json", help="canonicalize or semantically diff JSON")
    js_sub = js.add_subparsers(dest="json_command", required=True)
    canon = js_sub.add_parser("canonicalize")
    canon.add_argument("files", nargs="+", metavar="FILE")
    canon.add_argument("--in-place", action="store_true")
    semantic = js_sub.add_parser("semantic-diff")
    semantic.add_argument("--before", required=True)
    semantic.add_argument("--after", required=True)
    semantic.add_argument("--levels", default=None, help="comma list of L1,L2,L3")
    semantic.add_argument("--payload", default=None, metavar="KEY")
    semantic.add_argument("--axis-rows", action="store_true")
    semantic.add_argument("--ignore-keys", default=None)
    semantic.add_argument("--max-findings", type=int, default=40)
    table["json"] = _cmd_json

    rb = subparsers.add_parser("render-bytes", help="per-subject rendered-byte diff of two trees")
    rb_sub = rb.add_subparsers(dest="render_command", required=True)
    rb_diff = rb_sub.add_parser("diff")
    rb_diff.add_argument("--before", required=True, metavar="DIR")
    rb_diff.add_argument("--after", required=True, metavar="DIR")
    rb_diff.add_argument("--artifact", action="append", default=None, metavar="NAME")
    rb_diff.add_argument("--name", default=None, help="comma list, default java.png,java.gif")
    table["render-bytes"] = _cmd_render_bytes

    cmp_parser = subparsers.add_parser("compare", help="join two stored artifacts; THE gate")
    cmp_parser.add_argument("--artifacts", default=None, help="one comma list of artifact ids")
    cmp_parser.add_argument("--base", default=None, metavar="DIR")
    cmp_parser.add_argument("--expected", default=None, metavar="FILE")
    cmp_parser.add_argument("--include-stale", action="store_true")
    cmp_parser.add_argument("--bootstrap", action="store_true")
    table["compare"] = _cmd_compare

    rep = subparsers.add_parser("report", help="render a stored artifact or a diff as Markdown")
    rep_sub = rep.add_subparsers(dest="report_command", required=True)
    rep_render = rep_sub.add_parser("render")
    rep_render.add_argument("--in", dest="input", required=True, metavar="FILE")
    rep_render.add_argument("--kind", default=None)
    table["report"] = _cmd_report

    return table


def build_parser() -> tuple[argparse.ArgumentParser, dict[str, Command]]:
    """The command parser. The globals are shown in its help and consumed before it runs."""
    parser = argparse.ArgumentParser(
        prog="python scripts/parity",
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
