"""The sweep-table reader, and the one implementation of the fleet sum and the buckets.

Three column shapes, one reader. The delta is resolved **by header name** rather than by position,
which is the whole point: ``mean_argb_delta`` is column 3 in ``sweep.glint`` because column 2 is
``frames``, so the corpus's canonical ``awk '{s+=$2}'`` returns ``330.0000`` there - 30 frames times
11 subjects - and 67 recorded uses never caught it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from parity import ids as ids_mod
from parity.norm import MissingInput, fixed, fsum, read_json, read_lines

#: Spine 4.3. These and only these.
SWEEPS = ("entity", "block", "item", "player", "armor", "glint")

DELTA = "mean_argb_delta"

#: The canvas quartet, whose movement is its own comparison class because it re-samples every pixel.
CANVAS = ("java_w", "java_h", "vanilla_w", "vanilla_h")

#: Cumulative, not histogram bins: each count includes every lower one.
BUCKET_EDGES = (0.25, 0.5, 0.75, 1.0)

#: A crashed subject is not a bad subject - it fails every bucket test identically - so it is
#: carried as an explicit status rather than as an out-of-band magic value.
OK = "ok"
FAILED = "failed"

_SENTINEL_TEXTS = {"infinity", "-infinity", "nan"}


@dataclass(frozen=True)
class Row:
    """One subject. Values stay as their **stored text**, which is what the mover join compares."""

    key: str
    values: dict[str, str]
    status: str = OK

    def delta(self) -> float | None:
        text = self.values.get(DELTA, "")
        if self.status == FAILED:
            return None
        try:
            return float(text)
        except ValueError:
            return None


@dataclass
class Table:
    sweep: str
    key_field: str
    columns: tuple[str, ...]
    rows: list[Row] = field(default_factory=list)

    def failed(self) -> int:
        return sum(1 for row in self.rows if row.status == FAILED)


def read_table(path: Path, sweep: str | None = None) -> Table:
    """Read any of the three column shapes.

    ``read_lines`` folds the mixed LF-header/CRLF-body form every sweep writes today, so the reader
    is indifferent to it and nothing downstream has to strip a stray CR.
    """
    if not path.is_file():
        raise MissingInput(f"no sweep table at {path}")
    lines = read_lines(path)
    if not lines:
        raise MissingInput(f"empty sweep table at {path}")
    columns = tuple(lines[0].split("\t"))
    if DELTA not in columns:
        raise MissingInput(f"{path} has no {DELTA} column; found {columns}")
    key_field = columns[0]
    resolved = sweep or _sweep_of(path, key_field)

    rows: list[Row] = []
    for line in lines[1:]:
        if not line.strip():
            continue
        cells = line.split("\t")
        values = dict(zip(columns, cells))
        rows.append(Row(key=cells[0], values=values, status=_status(values)))
    return Table(sweep=resolved, key_field=key_field, columns=columns, rows=rows)


def read_stored_table(path: Path, sweep: str | None = None) -> Table:
    """Read a captured or promoted sweep artifact back as a table.

    The stored form is the TSV after ``to_rows``: one object per subject under ``rows``, keyed on the
    canonical stem, with every other column carried across as its own stored text. So the same
    ``Row`` reads it - the delta is still the text the producer printed, and the status is still the
    explicit column. What differs is only where the columns come from, and they are taken from the
    rows themselves rather than from a header line, because the stored form has none.

    Without this the two commands defined over a sweep could read a raw producer directory and not
    the root every other command works against: ``read_table`` is a TSV reader and a stored artifact
    handed to it fails on its first character.

    There is no absent-file arm, unlike ``read_table``'s: the caller resolves which sweeps a root
    holds by testing each candidate, so a path that is not there is a sweep the command was never
    asked about rather than an operand this has to refuse.

    :param path: the stored sweep artifact
    :param sweep: the sweep's name, when the caller already knows it
    :return: the table
    :raises MissingInput: if the file is not a sweep table, or carries no delta column
    """
    payload = read_json(path)
    if payload.get("kind") != "sweep-table":
        raise MissingInput(f"{path} is kind {payload.get('kind')!r}, not a sweep-table")
    key_field = payload.get("key", "subject")
    stored = payload.get("rows") or []
    columns: list[str] = [key_field]
    rows: list[Row] = []
    for entry in stored:
        values = {name: str(value) for name, value in entry.items() if name != key_field}
        for name in values:
            if name not in columns:
                columns.append(name)
        rows.append(Row(key=str(entry.get(key_field, "")), values=values, status=_status(values)))
    if DELTA not in columns:
        raise MissingInput(f"{path} has no {DELTA} column; found {tuple(columns)}")
    resolved = sweep or _sweep_of(path, key_field)
    return Table(sweep=resolved, key_field=key_field, columns=tuple(columns), rows=rows)


def _status(values: dict[str, str]) -> str:
    """An explicit column when the table has one, else the two magic values it replaced.

    The writers state the status outright now. The sentinel arms stay because an older capture is
    still a valid operand - a frozen table, a `cache/` tree from before the reshape - and a reader
    that could not parse one would make every such comparison unavailable rather than merely old.
    """
    declared = values.get("status", "").strip().lower()
    if declared in (OK, FAILED):
        return declared
    if values.get(DELTA, "").strip().lower() in _SENTINEL_TEXTS:
        return FAILED
    if values.get("differing_pixels", "").strip() == "-1":
        return FAILED
    return OK


def _sweep_of(path: Path, key_field: str) -> str:
    """Name the sweep from the path when it can, else from the key column's own spelling.

    The key-column fallback answers for the four spellings that were unique to one sweep before the
    writers were given a shared shape. **`subject` is deliberately not among them**: all six write it
    now, so it identifies nothing, and answering `armor` for any of them would silently apply the
    wrong id spelling in `canonical_key`. `unknown` degrades safely - the stem parse fails and the
    row keeps its own key.
    """
    stem = path.stem
    for name in SWEEPS:
        if stem == f"sweep-{name}" or path.parent.name == f"{name}-parity-vanilla":
            return name
    return {"scope": "player", "entity_id": "entity",
            "block_id": "block", "item_id": "item"}.get(key_field, "unknown")


def discover(source: Path) -> dict[str, Path]:
    """Find sweep tables under any layout a producer or a capture leaves behind.

    ``cache/visual/<sweep>-parity-vanilla/parity-report.tsv`` is what the six sweeps write;
    ``cache/p0/sweep-<sweep>.tsv`` is what a capture of them looks like. Accepting both is what lets
    ``--from`` name either without a second flag.

    A sweep's OWN output directory is a third layout, and it is the one the build uses: the artifact
    table points each sweep row at its producer's own directory, which is one level below the
    ``cache/visual`` the second form is written relative to. It is attributed by DIRECTORY NAME
    rather than accepted bare, so a lone ``parity-report.tsv`` is credited to the sweep that wrote
    it instead of to whichever name the loop happens to be on.
    """
    if source.is_file():
        table = read_table(source)
        return {table.sweep: source}
    found: dict[str, Path] = {}
    for name in SWEEPS:
        candidates = [source / f"sweep-{name}.tsv",
                      source / f"{name}-parity-vanilla" / "parity-report.tsv",
                      source / f"{name}.tsv"]
        if source.name == f"{name}-parity-vanilla":
            candidates.append(source / "parity-report.tsv")
        for candidate in candidates:
            if candidate.is_file():
                found[name] = candidate
                break
    if not found:
        raise MissingInput(f"no sweep tables under {source}")
    return found


def total(table: Table) -> float:
    """``math.fsum``: exactly rounded and order-independent, so a re-sorted table sums the same.

    A sequential ``+=`` is not, and the sweeps sort by delta - so one row moving past another can
    change the last digit of a sum that is supposed to have held.
    """
    return fsum(row.delta() or 0.0 for row in table.rows if row.status == OK)


def buckets(table: Table) -> dict[str, int]:
    counts = {f"<{edge:.2f}": 0 for edge in BUCKET_EDGES}
    for row in table.rows:
        delta = row.delta()
        if delta is None:
            continue
        for edge in BUCKET_EDGES:
            if delta < edge:
                counts[f"<{edge:.2f}"] += 1
    counts["total"] = len(table.rows)
    counts["failed"] = table.failed()
    return counts


def canonical_key(row: Row, sweep: str) -> str:
    """Every row joins on the canonical reference stem, whatever spelling its own table prints.

    That is what lets two sweeps' rows be joined to each other, which nothing in the repo can do.
    """
    if sweep == "player":
        return row.key
    try:
        return ids_mod.format_ref_stem(ids_mod.parse_as(row.key, _spelling(sweep)))
    except ids_mod.IdError:
        return row.key


def _spelling(sweep: str) -> ids_mod.Spelling:
    return ids_mod.Spelling.COLON if sweep in ("block", "item", "glint") else ids_mod.Spelling.REF_STEM


def to_rows(table: Table) -> list[dict]:
    """The stored payload: one object per subject, keyed by the canonical stem under ``subject``."""
    out = []
    for row in table.rows:
        payload = {"subject": canonical_key(row, table.sweep), "status": row.status}
        payload.update({name: value for name, value in row.values.items() if name != table.key_field})
        out.append(payload)
    out.sort(key=lambda item: item["subject"])
    return out


def summary(table: Table) -> dict:
    """The derived object a captured sweep carries beside its rows.

    ``sum`` is the store's metric form - fixed at four places, as a string - because every delta in
    the same file is the text its producer printed at that scale, and a float here would be the one
    number in the artifact spelled a different way. It is also what makes the value byte-stable: a
    binary float's shortest repr moves with the last bit of an ``fsum`` over a thousand rows.

    :param table: the table
    :return: the buckets, the failure count, the row count and the fleet sum
    """
    return {"buckets": buckets(table), "failed": table.failed(), "rows": len(table.rows),
            "sum": fixed(total(table))}


def summarise(tables: Iterable[tuple[str, Table]]) -> list[dict]:
    return [{**summary(table), "sweep": name} for name, table in tables]
