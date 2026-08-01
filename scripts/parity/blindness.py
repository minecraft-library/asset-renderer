"""Reach resolution: which artifacts can SEE a change, and which are structurally blind to it.

Reach is a pure function of ``(changed paths, blindness.json)``. Nothing here measures anything, and
nothing here decides whether a reach is acceptable - a wide reach is a big plan, never an error. The
one refusal is **UNKNOWN**: a changed path that no rule and no ``no_reach`` glob covers, which means
the map has nothing to say and a bundle built from it could not be sufficient.

Three modes, and the order they apply in is the whole of the arithmetic:

* ``select`` contributes its ``sees`` to the union.
* ``demote`` contributes too, and then removes its ``blind`` set **after** the union has been taken -
  so a tooling change empties every sweep and pin that a *different* rule had selected, which is the
  only way a rule can speak about artifacts it does not itself select.
* ``suppress`` marks an artifact inadmissible outright, whatever selected it.

The post-union ordering is not cosmetic. Taking the demotions inline would make the answer depend on
the order the rules happen to sit in the file.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

from parity.norm import MissingInput, read_json

#: Where the map lives inside a store root.
BLINDNESS_FILE = "blindness.json"


class UnknownReach(Exception):
    """A changed path no rule and no ``no_reach`` glob covers. Refusal R1."""

    def __init__(self, paths: Sequence[str]) -> None:
        self.paths = list(paths)
        super().__init__(
            "no blindness rule covers: " + ", ".join(self.paths)
            + " - add a rule (or a no_reach glob) rather than guessing the reach")


def compile_glob(glob: str) -> re.Pattern[str]:
    """Translate one repo-relative glob to a regex.

    ``**`` spans path segments, ``*`` and ``?`` do not. The Java side compiles the identical grammar,
    because a map whose two readers disagree about what a glob matches is worse than no map: the test
    would pass on a rule the planner never fires.
    """
    out = ["^"]
    index = 0
    while index < len(glob):
        char = glob[index]
        if glob.startswith("**/", index):
            out.append("(?:.*/)?")
            index += 3
        elif glob.startswith("**", index):
            out.append(".*")
            index += 2
        elif char == "*":
            out.append("[^/]*")
            index += 1
        elif char == "?":
            out.append("[^/]")
            index += 1
        else:
            out.append(re.escape(char))
            index += 1
    out.append("$")
    return re.compile("".join(out))


def matches(path: str, globs: Iterable[str]) -> bool:
    """Whether a repo-relative POSIX path matches any of the globs."""
    return any(compile_glob(glob).match(path) for glob in globs)


@dataclass(frozen=True)
class Rule:
    """One row of the map."""

    id: str
    claim: str
    trigger_paths: tuple[str, ...]
    sees: tuple[str, ...]
    blind: tuple[str, ...]
    reason: str
    mode: str
    probe: str
    source: str


@dataclass
class Reach:
    """What a changed set resolves to.

    ``blind`` keeps the rule that called each artifact blind and that rule's reason, so a report of
    what a change cannot be seen by is a lookup rather than a recollection.
    """

    sees: list[str] = field(default_factory=list)
    blind: list[dict] = field(default_factory=list)
    unknown: list[str] = field(default_factory=list)
    fired: list[str] = field(default_factory=list)
    no_reach: list[str] = field(default_factory=list)


def load(store_root: Path) -> tuple[list[Rule], tuple[str, ...]]:
    """Read the map out of a store root.

    :returns: the rules and the ``no_reach`` globs
    """
    target = store_root / BLINDNESS_FILE
    if not target.is_file():
        raise MissingInput(
            f"{target} is absent; parityPlan cannot resolve reach without the blindness map")
    payload = read_json(target)
    rules = [
        Rule(
            id=row["id"],
            claim=row.get("claim", ""),
            trigger_paths=tuple(row.get("trigger_paths", ())),
            sees=tuple(row.get("sees", ())),
            blind=tuple(row.get("blind", ())),
            reason=row.get("reason", ""),
            mode=row.get("mode", "select"),
            probe=row.get("probe", ""),
            source=row.get("source", ""),
        )
        for row in payload.get("rules", [])
    ]
    return rules, tuple(payload.get("no_reach", ()))


def resolve(changed: Sequence[str], rules: Sequence[Rule],
            no_reach: Sequence[str] = ()) -> Reach:
    """Resolve a changed set against the map.

    Every changed path must be covered by some rule or by ``no_reach``; the uncovered ones come back
    in ``unknown`` and the caller decides that is refusal R1. A ``no_reach`` match is covered and
    contributes nothing, which is a different statement from "I do not know".
    """
    reach = Reach()
    fired: list[Rule] = []

    for path in changed:
        hit = False
        for rule in rules:
            if matches(path, rule.trigger_paths):
                hit = True
                if rule not in fired:
                    fired.append(rule)
        if not hit and matches(path, no_reach):
            reach.no_reach.append(path)
            hit = True
        if not hit:
            reach.unknown.append(path)

    seen: list[str] = []
    for rule in fired:
        for artifact in rule.sees:
            if artifact not in seen:
                seen.append(artifact)

    # The two post-union passes, in this order. A demotion removes what a different rule selected, so
    # it cannot run until the union is complete; a suppression outranks both.
    for rule in fired:
        if rule.mode == "demote":
            seen = [artifact for artifact in seen if artifact not in rule.blind]
    suppressed = {artifact for rule in fired if rule.mode == "suppress"
                  for artifact in tuple(rule.sees) + tuple(rule.blind)}
    seen = [artifact for artifact in seen if artifact not in suppressed]

    blind: list[dict] = []
    for rule in fired:
        for artifact in rule.blind:
            if artifact in seen:
                continue
            if any(entry["artifact"] == artifact for entry in blind):
                continue
            blind.append({"artifact": artifact, "reason": rule.reason, "rule": rule.id})

    reach.sees = sorted(seen)
    reach.blind = sorted(blind, key=lambda entry: entry["artifact"])
    reach.fired = [rule.id for rule in fired]
    return reach
