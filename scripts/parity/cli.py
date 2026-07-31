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
from parity import capture as capture_mod
from parity import compare as compare_mod
from parity import ids as ids_mod
from parity import promote as promote_mod
from parity import provenance as provenance_mod
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
    # Refuse a root that never finished: a stale tree reported as agreement is the recorded
    # `&& diff` trap, and COMPLETE being written last is what makes it detectable.
    capture_mod.require_complete(root.root)

    wanted = [name.strip() for name in args.artifacts.split(",")] if args.artifacts else None
    if not wanted:
        wanted = sorted(_artifacts_in(root.root))
    if not wanted:
        raise MissingInput(f"no artifacts under {root.root}; name them with --artifacts")

    expected = compare_mod.load_expected(
        Path(args.expected) if args.expected else root.root / store_mod.RUN_DIR / "expected-diff.json")
    results = []
    missing = []
    for artifact in wanted:
        try:
            base_payload = _load(args, artifact, args.base)
        except MissingInput:
            missing.append(artifact)
            continue
        results.append(compare_mod.compare(base_payload, root.read(artifact), expected))

    payload = compare_mod.to_report(results)
    payload["missing_baseline"] = missing
    if args.bootstrap:
        payload["bootstrap"] = True
    if args.include_stale:
        payload["include_stale"] = True
    run = root.root / store_mod.RUN_DIR
    write_json(run / "compare.json", payload)
    write_text(run / "compare.md", report_mod.render_diff(payload))
    write_json(run / "last-verdict.json", {
        "artifacts": [result.artifact for result in results],
        "missing_baseline": missing,
        "unexpected": payload["totals"]["unexpected"],
    })
    text = report_mod.render_diff(payload)
    if missing:
        text += f"\n\n**MISSING_BASELINE**: {', '.join(missing)}"
    _emit(args, text, payload)

    if missing and not args.bootstrap:
        raise ComparisonFailed(
            f"MISSING_BASELINE for {', '.join(missing)}: nothing to compare against. "
            "The first capture of an artifact is compared with --bootstrap")
    compare_mod.raise_on(results)
    return OK


def _cmd_capture_normalize(args: argparse.Namespace) -> int:
    repo = _bases(args)
    root = store_mod.working(args.root, repo).root
    sources = args.source
    if len(sources) not in (1, len(args.artifact)):
        raise Refused(f"give one --source, or one per --artifact ({len(args.artifact)})")
    capture_mod.wipe(root)
    written = []
    for position, artifact in enumerate(args.artifact):
        source = Path(sources[0] if len(sources) == 1 else sources[position])
        target = capture_mod.normalize(artifact, source, root, repo, producer=args.producer or "",
                                       mode=args.mode, flags=args.flag or (), runs=args.runs)
        written.append({"artifact": artifact, "path": str(target)})
    _emit(args, "\n".join(f"captured {row['artifact']} -> {row['path']}" for row in written),
          {"captured": written})
    return OK


def _cmd_capture_index(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args)).root
    marker = capture_mod.index(root, producers=(args.producer or "").split(",") if args.producer else (),
                               flags=args.flag or (), runs=args.runs)
    _emit(args, f"capture complete: {marker}", {"complete": str(marker)})
    return OK


def _cmd_expect(args: argparse.Namespace) -> int:
    root = store_mod.working(args.root, _bases(args)).root
    target = root / store_mod.RUN_DIR / "expected-diff.json"
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

    promote_mod.check(root, entries, args.reason or "", args.allow_partial, args.bootstrap)
    result = promote_mod.apply(root, target, entries, args.reason,
                               parity_class=args.parity_class,
                               allow_partial=args.allow_partial,
                               population_changed=args.population_changed)
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
        write_json(Path(args.out) if args.out else probes / "contests.json", payload)
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
                        Path(args.out or "crop.png"), args.zoom)
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

    cap = subparsers.add_parser("capture-normalize",
                                help="read a producer's raw output into the working root as canonical JSON")
    cap.add_argument("--artifact", action="append", required=True)
    cap.add_argument("--source", action="append", required=True, metavar="PATH")
    cap.add_argument("--producer", default=None, help="one comma list of producing task names")
    cap.add_argument("--flag", action="append", default=None, metavar="k=v")
    cap.add_argument("--runs", type=int, default=0, help="how many runs AGREED, a measurement")
    cap.add_argument("--mode", default=None)
    table["capture-normalize"] = _cmd_capture_normalize

    idx = subparsers.add_parser("capture-index", help="write _run/_capture.json then COMPLETE last")
    idx.add_argument("--producer", default=None)
    idx.add_argument("--flag", action="append", default=None, metavar="k=v")
    idx.add_argument("--runs", type=int, default=0)
    table["capture-index"] = _cmd_capture_index

    exp = subparsers.add_parser("expect", help="register the movers a phase intends (I-15)")
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
