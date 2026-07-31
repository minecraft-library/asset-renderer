"""The sole writer.

Every byte the package writes passes through this module: LF only, UTF-8 with no BOM, exactly one
trailing newline, recursively key-sorted JSON, a locale-free number form, and POSIX separators
inside file content whatever the host OS.

Two details are load-bearing and easy to get wrong.

``write_text`` encodes and calls ``Path.write_bytes``. ``open(..., "w")`` defaults to
``newline=None``, which translates every LF to ``os.linesep`` - that is how the corpus produced a
uniformly-CRLF ranking file where the Java writer produces a mixed one, and how a tracked SVG
acquired CRLF. Writing bytes removes the failure mode rather than configuring around it.

``read_text`` folds on the way in as well as out. That is what makes the toolkit indifferent to the
six sweeps' mixed LF-header/CRLF-body tables, to a baseline copied on Windows, and to a file that
has been through a stash round trip.
"""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

LF = "\n"
ENCODING = "utf-8"

_BOM = "﻿"


# --- the typed errors the exit-code table is built from -------------------------------------------
#
# These live here because this is the package's only module that imports nothing from the package,
# so any module can raise one without a cycle. `cli.main` is the sole translator - no module calls
# sys.exit - which is what makes the six-code table enforceable rather than conventional.

class ParityError(Exception):
    """Base for every typed failure the CLI maps to an exit code."""


class ComparisonFailed(ParityError):
    """Exit 1. The comparison succeeded and the answer is that they differ. The gate signal."""


class MissingInput(ParityError):
    """Exit 3. A named path or artifact is absent, so the comparison could not be attempted."""


class MissingDependency(ParityError):
    """Exit 4. An optional import is absent and the command needs it."""


class Refused(ParityError):
    """Exit 5. A precondition failed and the command declined on purpose."""


# --- reading -----------------------------------------------------------------------------------

def read_text(path: Path) -> str:
    """Decode UTF-8 (a BOM is tolerated and stripped) and fold CRLF and lone CR to LF."""
    return _fold(path.read_bytes().decode(ENCODING))


def read_lines(path: Path) -> list[str]:
    """``read_text`` split on LF, with the single trailing empty element dropped."""
    text = read_text(path)
    lines = text.split(LF)
    if lines and lines[-1] == "":
        lines.pop()
    return lines


def read_json(path: Path) -> Any:
    """``read_text`` then ``json.loads`` - never ``json.load`` of a handle, because the decode is ours."""
    return json.loads(read_text(path))


def _fold(text: str) -> str:
    if text.startswith(_BOM):
        text = text[len(_BOM):]
    return text.replace("\r\n", LF).replace("\r", LF)


# --- writing -----------------------------------------------------------------------------------

def write_text(path: Path, text: str) -> Path:
    """The only text write in the package.

    Folds to LF, strips a BOM, guarantees exactly one trailing LF, creates parent directories, and
    writes bytes so that no universal-newline translation can occur.
    """
    body = _fold(text).rstrip(LF) + LF
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body.encode(ENCODING))
    return path


def write_lines(path: Path, lines: Iterable[str]) -> Path:
    return write_text(path, LF.join(lines))


def write_json(path: Path, obj: Any) -> Path:
    return write_text(path, canonical_json(obj))


def write_bytes_raw(path: Path, data: bytes) -> Path:
    """The one named binary door.

    Binary payloads have no line endings, so folding them would corrupt them. This exists once, is
    named for what it is, and cannot be reached for text - ``pixels.save_png`` is its only caller.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


# --- canonical form ------------------------------------------------------------------------------

def canonical_json(obj: Any) -> str:
    """Two-space indented, recursively key-sorted, UTF-8, no trailing whitespace.

    Objects are key-sorted recursively; **arrays are never reordered** (I-4), because an array means
    the order is semantic - a map whose iteration order carries meaning is emitted as an array of
    entries for exactly this reason.

    Floats render through Python's shortest-round-trip repr, which is exact and locale-free.
    **This is deliberately not ``fixed``.** Rounding every float to four places in the canonical
    writer would be right for a sweep metric and destructive for a pin: ``pin.vanilla-iso-pose`` is
    16 floats of a pose matrix and ``pin.kit-corners`` 24 of a fixture's corners, and four decimal
    places does not hold either. ``fixed`` is the form a metric is *produced* in - a sweep table
    already spells its deltas to four places, so reading one back and re-emitting it is byte-stable
    without the writer rounding anything.

    A non-finite float raises rather than being emitted. JSON has no ``Infinity``, and I-27 requires
    a failed subject to be carried as an explicit status field rather than as an out-of-band magic
    value - so the refusal is what forces the caller to model it.
    """
    return json.dumps(
        obj,
        allow_nan=False,
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
        separators=(",", ": "),
    )


def fixed(value: float, places: int = 4) -> str:
    """The store's number form: locale-free fixed point.

    ``str.format`` has no locale, so I-3 is satisfied by construction rather than by remembering to
    pass ``Locale.ROOT``.
    """
    return f"{value:.{places}f}"


def exact(value: float) -> str:
    """``repr`` - the form the L2 comparison uses, so ``1`` and ``1.0`` stay distinguishable."""
    return repr(value)


def fsum(values: Iterable[float]) -> float:
    """``math.fsum``: exactly rounded and order-independent, so a re-sorted table sums the same."""
    return math.fsum(values)


# --- paths -------------------------------------------------------------------------------------

def posix(path: Path | str, root: Path | None = None) -> str:
    """A path as it appears INSIDE a file: forward slashes, no drive letter, no ``./`` prefix."""
    candidate = Path(path)
    if root is not None:
        candidate = candidate.relative_to(root)
    return PurePosixPath(*candidate.parts).as_posix()


# --- digests -----------------------------------------------------------------------------------

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path, chunk: int = 1 << 20) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            block = handle.read(chunk)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def sha256_text(text: str) -> str:
    """sha256 over the NORMALIZED bytes, so a digest is host-independent."""
    return sha256_bytes((_fold(text).rstrip(LF) + LF).encode(ENCODING))
