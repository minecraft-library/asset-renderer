"""The three-level semantic JSON differ.

L3 is the level ``git diff`` cannot express and it is the one that matters: it separates a
*reordering* from a *value change*. That is the distinction that caught the ``entityModels``
diagnostics line moving from position 9 to 6 while the emitted JSON stayed byte-identical.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from parity.norm import ComparisonFailed, read_json

#: Skipped when auto-detecting which member carries the payload.
ENVELOPE_KEYS = ("//", "format", "source_version", "version", "generated_by", "comment")

#: The first present of these names a row inside an array payload; else the index; else the element.
ROW_KEYS = ("block", "effect", "type", "id")

LEVELS = ("L1", "L2", "L3")


@dataclass
class Findings:
    missing: list[str] = field(default_factory=list)
    extra: list[str] = field(default_factory=list)
    changed: list[dict] = field(default_factory=list)
    order: dict | None = None

    def as_dict(self) -> dict:
        out: dict[str, Any] = {
            "L1": {"extra": self.extra, "missing": self.missing},
            "L2": {"changed": self.changed},
            "totals": {"changed": len(self.changed), "extra": len(self.extra),
                       "missing": len(self.missing)},
        }
        out["L3"] = {"first_divergence": self.order} if self.order else {"first_divergence": None}
        return out

    def gate_fails(self) -> bool:
        """Exit 1 on an L1 miss **or** an L2 value change.

        A tightening of the original, which exited 1 on L1 alone and reported L2 as findings. On
        the gate path a changed value is a failure.
        """
        return bool(self.missing or self.extra or self.changed)


def payload_of(document: Any, key: str | None = None) -> Any:
    if key is not None:
        return document.get(key) if isinstance(document, dict) else document
    if not isinstance(document, dict):
        return document
    candidates = [name for name in document if name not in ENVELOPE_KEYS]
    if len(candidates) == 1:
        return document[candidates[0]]
    return document


def row_key(element: Any, index: int) -> str:
    if isinstance(element, dict):
        for name in ROW_KEYS:
            if name in element:
                return str(element[name])
        return str(index)
    return str(element)


def _index(payload: Any) -> dict[str, Any]:
    if isinstance(payload, dict):
        return dict(payload)
    if isinstance(payload, list):
        return {row_key(element, position): element for position, element in enumerate(payload)}
    return {"": payload}


def _order(payload: Any) -> list[str]:
    if isinstance(payload, dict):
        return list(payload)
    if isinstance(payload, list):
        return [row_key(element, position) for position, element in enumerate(payload)]
    return [""]


def differs(left: Any, right: Any) -> bool:
    """``repr`` catches ``1`` against ``1.0`` and float-formatting drift that JSON equality masks.

    The original was one line - ``type(a) is not type(b) or a != b or repr(a) != repr(b)`` - and
    that line is **order-sensitive for containers**: ``repr`` of a dict prints its insertion order,
    so a nested object whose members were merely reordered compares as a value change. L2 is defined
    as ignoring object member order, and L3 exists precisely to report a reordering, so the one-liner
    would have made every reordering fail the gate twice and under the wrong level.

    Recursing keeps both properties: equality decides containers, ``repr`` decides scalars, so
    ``1`` against ``1.0`` is still caught at any depth.
    """
    if type(left) is not type(right):
        return True
    if isinstance(left, dict):
        return set(left) != set(right) or any(differs(left[key], right[key]) for key in left)
    if isinstance(left, list):
        return len(left) != len(right) or any(differs(one, other)
                                              for one, other in zip(left, right))
    return left != right or repr(left) != repr(right)


def diff(before: Any, after: Any, levels: tuple[str, ...] = LEVELS,
         payload_key: str | None = None, ignore_keys: tuple[str, ...] = ()) -> Findings:
    left = _index(payload_of(before, payload_key))
    right = _index(payload_of(after, payload_key))
    for name in ignore_keys:
        left.pop(name, None)
        right.pop(name, None)

    found = Findings()
    if "L1" in levels:
        found.missing = sorted(set(left) - set(right))
        found.extra = sorted(set(right) - set(left))
    if "L2" in levels:
        for name in sorted(set(left) & set(right)):
            if differs(left[name], right[name]):
                found.changed.append({"after": _render(right[name]), "before": _render(left[name]),
                                      "key": name})
    if "L3" in levels:
        found.order = _first_order_divergence(
            _order(payload_of(before, payload_key)), _order(payload_of(after, payload_key)))
    return found


def _render(value: Any) -> Any:
    """Scalars stay scalars; a whole sub-document is reported by repr so a row stays one line."""
    return value if isinstance(value, (str, int, float, bool)) or value is None else repr(value)


def _first_order_divergence(left: list[str], right: list[str]) -> dict | None:
    """First divergence only - the whole point is *where* the order first parts, not how much."""
    shared = [name for name in left if name in set(right)]
    shared_right = [name for name in right if name in set(left)]
    for position, (one, other) in enumerate(zip(shared, shared_right)):
        if one != other:
            return {"after": other, "before": one, "position": position}
    return None


def diff_files(before: Path, after: Path, **kwargs) -> Findings:
    return diff(read_json(before), read_json(after), **kwargs)


def raise_on(found: Findings) -> None:
    if found.gate_fails():
        raise ComparisonFailed(
            f"semantic diff: {len(found.missing)} missing, {len(found.extra)} extra, "
            f"{len(found.changed)} changed"
        )
