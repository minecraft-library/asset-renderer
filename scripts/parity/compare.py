"""The key-joined A/B of two stored artifacts, and the five ordered classes.

**A mover is a shared key whose value differs in any field, not merely in the metric.**
``mean_argb_delta`` holding while ``java_w`` changed is a mover, and calling it unchanged is how a
canvas change hides. The accept criterion is zero movers, never "the sum held" - a sum can hold
while rows cancel, and the corpus has that exact case on record.

**There is no tolerance.** No epsilon, no relative tolerance, no rounding before compare, on any
artifact, at any time. Every fitted tolerance in this codebase was later deleted, and one in the
*gate* would be worse than any of them because it decides what counts as evidence rather than what
gets drawn. The device that replaces it is the expected-diff manifest: a phase that intends to move
rows registers them, and the gate asserts ``diff == manifest`` rather than ``diff == empty``. That
is stricter in both directions - an unintended 0.0001 move fails, and an intended +35.32 move passes
without weakening anything.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from parity import store as store_mod
from parity.norm import ComparisonFailed, fsum, read_json
from parity.sweep import CANVAS

#: Ordered. A row matching more than one is reported under the FIRST, and carries the rest in
#: ``also``, so the counts partition and the detail loses nothing. A canvas move that also moved the
#: metric is a canvas move, because that is what a reader has to know first.
CLASSES = ("added", "dropped", "status", "canvas", "metric")

_ENVELOPE = {"//", "artifact", "format", "key", "kind", "provenance"}


@dataclass
class Side:
    artifact: str
    key: str
    rows: dict[str, dict]
    label: str = ""

    def sum(self) -> float | None:
        values = []
        for row in self.rows.values():
            if row.get("status") == "failed":
                continue
            try:
                values.append(float(row.get("mean_argb_delta", "")))
            except (TypeError, ValueError):
                return None
        return fsum(values) if values else None


@dataclass
class Result:
    artifact: str
    left: Side
    right: Side
    movers: list[dict] = field(default_factory=list)
    added: list[str] = field(default_factory=list)
    dropped: list[str] = field(default_factory=list)

    def totals(self) -> dict:
        counted = {name: 0 for name in CLASSES}
        expected = 0
        for mover in self.movers:
            counted[mover["class"]] += 1
            if mover.get("expected"):
                expected += 1
        return {
            "added": len(self.added),
            "canvas": counted["canvas"],
            "dropped": len(self.dropped),
            "expected": expected,
            "metric": counted["metric"],
            "moved": len(self.movers),
            "shared": len(set(self.left.rows) & set(self.right.rows)),
            "status": counted["status"],
            "unexpected": len(self.movers) - expected,
        }

    def clean(self) -> bool:
        totals = self.totals()
        return totals["unexpected"] == 0 and not self.added and not self.dropped


def side_of(payload: dict, label: str) -> Side:
    """The key comes from the envelope's ``key``, written by capture-normalize from the source's
    own header - never inferred from ``kind``.

    That was a real corpus failure: one caller keyed the armour report ``entity_id`` while its
    header is ``subject``, and only one caller in 3,283 recorded commands read the header off the
    file.
    """
    key = payload.get("key")
    if not key:
        raise ComparisonFailed(f"{payload.get('artifact', label)} carries no envelope key")
    member = store_mod.rows_member(payload.get("kind", ""))
    if member is None:
        member = next((name for name, value in payload.items()
                       if name not in _ENVELOPE and isinstance(value, list)), None)
    rows = _rows(payload.get(member) if member else None, key)
    # A manifest may carry a second payload key, `logs`: an object of flow name to digest over that
    # flow's normalized diagnostics log. Its entries join the SAME keyspace under a `logs/` prefix
    # rather than sitting beside the comparison, because a stored value the gate does not read is
    # the false green this store exists against - and a reordered log with a byte-identical table is
    # exactly the move it is there to catch.
    for name, digest in sorted((payload.get("logs") or {}).items()):
        rows[f"logs/{name}"] = {key: f"logs/{name}", "sha256": digest}
    return Side(artifact=payload.get("artifact", ""), key=key, label=label, rows=rows)


def _rows(payload_member: Any, key: str) -> dict[str, dict]:
    """Key a payload member, whichever of the two shapes a kind spells it in.

    A sweep, a manifest and the blindness roster carry an ARRAY of rows, each stating its own key.
    A digest-set and a pin-set carry an OBJECT keyed by the entry's own name, because both are read
    by a JUnit test that asks for one entry by key and an array would make every reader scan. The
    key is injected into the entry so the two shapes join identically and `classify` never sees the
    key itself as a moved field.

    Reading only the array shape is not a narrowing, it is a **false green**: every value of a
    digest-set could move and the join would report zero rows on both sides, zero movers and clean.
    Measured, on exactly the payload P11 stores.
    """
    if isinstance(payload_member, dict):
        return {str(name): {**entry, key: str(name)}
                for name, entry in payload_member.items() if isinstance(entry, dict)}
    return {str(row[key]): row for row in (payload_member or []) if key in row}


def classify(before: dict, after: dict) -> tuple[str, list[str], dict]:
    """Return the first matching class, the others, and the fields that moved."""
    fields = {name: [before.get(name), after.get(name)]
              for name in sorted(set(before) | set(after))
              if before.get(name) != after.get(name)}
    if not fields:
        return "", [], {}
    matched = []
    if "status" in fields:
        matched.append("status")
    if any(name in fields for name in CANVAS):
        matched.append("canvas")
    if any(name not in CANVAS and name != "status" for name in fields):
        matched.append("metric")
    ordered = [name for name in CLASSES if name in matched]
    return ordered[0], ordered[1:], fields


def compare(left_payload: dict, right_payload: dict, expected: dict | None = None) -> Result:
    left = side_of(left_payload, "base")
    right = side_of(right_payload, "current")
    if left.artifact and right.artifact and left.artifact != right.artifact:
        raise ComparisonFailed(f"cannot join {left.artifact} to {right.artifact}")

    result = Result(artifact=right.artifact or left.artifact, left=left, right=right)
    result.added = sorted(set(right.rows) - set(left.rows))
    result.dropped = sorted(set(left.rows) - set(right.rows))

    registered = _registered(expected, result.artifact)
    for key in sorted(set(left.rows) & set(right.rows)):
        kind, also, fields = classify(left.rows[key], right.rows[key])
        if not kind:
            continue
        result.movers.append({
            "also": also,
            "class": kind,
            "expected": key in registered,
            "fields": fields,
            "key": key,
        })
    return result


def _registered(expected: dict | None, artifact: str) -> set[str]:
    if not expected:
        return set()
    return {row["key"] for row in expected.get("movers", []) if row.get("artifact") == artifact}


def to_report(results: list[Result], generated_at: str = "") -> dict:
    """``_run/compare.json`` - the authority. The Markdown view is generated from it and never
    the other way round."""
    return {
        "artifacts": [
            {
                "added": result.added,
                "artifact": result.artifact,
                "dropped": result.dropped,
                "left": _summary(result.left),
                "movers": result.movers,
                "right": _summary(result.right),
                "totals": result.totals(),
            }
            for result in results
        ],
        "format": 1,
        "generated_at": generated_at,
        "totals": {
            "artifacts": len(results),
            "unexpected": sum(result.totals()["unexpected"] for result in results),
        },
    }


def _summary(side: Side) -> dict:
    out = {"artifact": side.artifact, "rows": len(side.rows), "store": side.label}
    total = side.sum()
    if total is not None:
        out["sum"] = total
    return out


def raise_on(results: list[Result]) -> None:
    """The gate signal. An unexpected mover, an add or a drop fails; a registered mover does not."""
    bad = [result for result in results if not result.clean()]
    if not bad:
        return
    parts = []
    for result in bad:
        totals = result.totals()
        parts.append(f"{result.artifact}: {totals['unexpected']} unexpected, "
                     f"{totals['added']} added, {totals['dropped']} dropped")
    raise ComparisonFailed("; ".join(parts))


def load_expected(path: Path | None) -> dict | None:
    return read_json(path) if path and path.is_file() else None


def empty_expected() -> dict:
    """``expect --empty`` writes this, which is what makes the gate ``diff == manifest`` rather
    than ``diff == empty`` even when the manifest is empty (I-15)."""
    return {
        "//": "parity.report.expected-diff · regen: python scripts/parity expect --empty",
        "artifact": "report.expected-diff",
        "format": 1,
        "kind": "expected-diff",
        "movers": [],
    }
