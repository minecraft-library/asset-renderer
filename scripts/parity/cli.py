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
from parity import ids as ids_mod
from parity import store as store_mod
from parity.norm import (
    ComparisonFailed,
    MissingDependency,
    MissingInput,
    Refused,
    canonical_json,
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
