"""Reach resolution: which artifacts can SEE a change, and which are structurally blind to it.

Reach is a pure function of ``(changed paths, blindness.json)``. Nothing here measures anything, and
nothing here decides whether a reach is acceptable - a wide reach is a big plan, never an error. The
one refusal is **UNKNOWN**: a changed path that no rule and no ``no_reach`` glob covers, which means
the map has nothing to say and a bundle built from it could not be sufficient.

Reach is resolved **per changed path** and then unioned, and that order is load-bearing. Each file is
reached by the rules that trigger on it, so adding a file to the set adds its answer and subtracts
from no other's: a reader that reaches nothing, committed beside a writer that reaches a bundle, must
still plan the writer's bundle. Resolving the whole set at once let the reader's demotion subtract
the writer's answer, and a commit pairing the two is the ordinary shape of work in a package holding
both - so the plan came back empty exactly where it was needed.

Within one path :func:`_resolve_path` runs three passes, in this order:

1. **Union** - each fired rule contributes its ``sees``, its mode included.
2. **Demote** - each fired ``demote`` rule removes its own ``blind`` set from that union, taking out
   what a different rule selected on this same path along with its own contribution.
3. **Suppress** - each fired ``suppress`` rule removes its ``sees`` and its ``blind`` together, the
   pass that outranks the other two.

Taking the demotions inline rather than after the union would make the answer depend on the order the
rules happen to sit in the file.

Neither removal pass reads a ``select`` rule's ``blind`` list, so that list subtracts nothing and is a
statement the plan prints; shipped ``select`` rules do carry one naming artifacts outside their own
``sees``, B10 and B23 among them. What a claim comes to therefore depends on whether the claiming
rule and the selecting rule fire on the SAME path or on different paths, and one pair of rules
answers both ways over one change set:

* ``BlindnessMapTest.java`` alone fires B37 (``select``) and B39 (``demote``, B37's list) on one
  path. Pass 2 empties the union: ``sees`` is ``[]`` and every artifact on that list is reported
  blind with an empty ``selected_by``.
* That file beside ``SelfCapture.java`` fires B39 on the first path alone. The second path resolves
  to B37's list and the union carries it: ``sees`` holds all of it and each blind row reads
  ``selected_by=['B37']``.
* A ``select`` rule's claim resolves by the same arithmetic from the other side. On
  ``BlockGeometryKit.java``, B10 claims ``sweep.block`` blind while B19 selects it on that path, so it
  is in ``sees`` and its row reads ``selected_by=['B19']``; on ``PlayerRenderer.java``, B9 claims
  ``sweep.player`` and no fired rule selects it, so it is absent from ``sees`` and its row carries an
  empty ``selected_by``.

The declaration is reported either way, carrying the rules that overruled it where any did and an
empty list where none did. Dropping it instead answered "nothing is blind here" for rules whose entire
content was one blind line, which is a wrong answer rather than a quiet one.
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
    what a change cannot be seen by is a lookup rather than a recollection. Each entry also carries
    ``selected_by``: the other fired rules whose ``sees`` put the artifact in the bundle anyway. That
    list is empty for an uncontested declaration and non-empty for a **contradiction**, which is a
    thing the plan has to say out loud rather than a row to drop - a claim of blindness that no plan
    can ever print is a claim nobody can check.
    """

    sees: list[str] = field(default_factory=list)
    blind: list[dict] = field(default_factory=list)
    unknown: list[str] = field(default_factory=list)
    fired: list[str] = field(default_factory=list)
    no_reach: list[str] = field(default_factory=list)


def load(store_root: Path) -> tuple[list[Rule], tuple[str, ...]]:
    """Read the map out of a store root.

    A ``no_reach`` entry is an OBJECT carrying the same two mandatory fields every rule has - a
    ``reason`` stating a mechanism and a ``probe`` that would falsify it - because this list is where
    every awkward path goes and a bare glob records no decision at all. Only the glob half reaches
    :func:`resolve`; the other two are read by the map's own test and by the rendered view.

    A bare string is REFUSED rather than accepted as its own glob. Tolerating both shapes is how the
    fields get dropped again one entry at a time.

    :returns: the rules and the ``no_reach`` globs
    """
    target = store_root / BLINDNESS_FILE
    if not target.is_file():
        raise MissingInput(
            f"{target} is absent; parityPlan cannot resolve reach without the blindness map")
    payload = read_json(target)
    no_reach: list[str] = []
    for entry in payload.get("no_reach", []):
        if not isinstance(entry, dict):
            raise MissingInput(
                f"{target} has a no_reach entry that is not an object: {entry!r}"
                " - each one needs a glob, a reason and a probe")
        no_reach.append(entry["glob"])
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
    return rules, tuple(no_reach)


def _resolve_path(hits: Sequence[Rule]) -> list[str]:
    """What one changed path resolves to, given the rules that fired on it.

    The union first, then the two post-union passes in order. A demotion removes what a different
    rule selected **for this same path**, so it cannot run until that path's union is complete; a
    suppression outranks both. Scoping it to the path is what keeps the answer monotone in the change
    set: a rule speaks about the files it triggers on and about no others.
    """
    seen: list[str] = []
    for rule in hits:
        for artifact in rule.sees:
            if artifact not in seen:
                seen.append(artifact)

    for rule in hits:
        if rule.mode == "demote":
            seen = [artifact for artifact in seen if artifact not in rule.blind]
    suppressed = {artifact for rule in hits if rule.mode == "suppress"
                  for artifact in tuple(rule.sees) + tuple(rule.blind)}
    return [artifact for artifact in seen if artifact not in suppressed]


def resolve(changed: Sequence[str], rules: Sequence[Rule],
            no_reach: Sequence[str] = ()) -> Reach:
    """Resolve a changed set against the map.

    Every changed path must be covered by some rule or by ``no_reach``; the uncovered ones come back
    in ``unknown`` and the caller decides that is refusal R1. A ``no_reach`` match is covered and
    contributes nothing, which is a different statement from "I do not know".

    Each path is resolved on its own and the answers are unioned, so a demotion cannot reach out of
    its own triggers and cancel what another file in the same commit genuinely moves.
    """
    reach = Reach()
    fired: list[Rule] = []
    per_path: list[tuple[str, list[Rule]]] = []

    for path in changed:
        hits = [rule for rule in rules if matches(path, rule.trigger_paths)]
        for rule in hits:
            if rule not in fired:
                fired.append(rule)
        if hits:
            per_path.append((path, hits))
        elif matches(path, no_reach):
            reach.no_reach.append(path)
        else:
            reach.unknown.append(path)

    seen: list[str] = []
    # Which rules actually PUT each surviving artifact in the bundle, rather than which ones merely
    # name it: a rule whose selection its own path demoted away has not selected anything, and saying
    # it did would name the wrong rule in the contradiction the plan prints.
    selectors: dict[str, list[str]] = {}
    for _, hits in per_path:
        for artifact in _resolve_path(hits):
            if artifact not in seen:
                seen.append(artifact)
            for rule in hits:
                if artifact in rule.sees and rule.id not in selectors.setdefault(artifact, []):
                    selectors[artifact].append(rule.id)

    blind: list[dict] = []
    for rule in fired:
        for artifact in rule.blind:
            # Which OTHER rules put it in the bundle regardless. The shipped map forbids a rule from
            # naming one artifact in both lists and a guard over that file holds it to it, but this
            # resolver answers for any rule list it is handed - so the exclusion is applied here
            # rather than inherited from a precondition one caller happens to satisfy. A row reading
            # "claimed blind, selected by <the claimant>" sends a reader to the rule that made the
            # claim to find out what overruled it.
            selected_by = [rid for rid in selectors.get(artifact, ()) if rid != rule.id]
            # An uncontested declaration is one fact about the artifact, so the first rule to make it
            # carries it. A contradicted one is a fact about the PAIR, so each claiming rule keeps its
            # own row: collapsing them would hide which rule's claim the bundle overrules.
            duplicate = any(entry["artifact"] == artifact
                            and (entry["rule"] == rule.id if selected_by else not entry["selected_by"])
                            for entry in blind)
            if duplicate:
                continue
            blind.append({"artifact": artifact, "reason": rule.reason, "rule": rule.id,
                          "selected_by": selected_by})

    reach.sees = sorted(seen)
    # By artifact, then by the claiming rule. The tie-break is what makes the printed order a property
    # of what the map SAYS rather than of how it is laid out: rows arrive in ``fired`` order, which is
    # the order the rules sit in the file and no order at all over their ids, so two rules claiming one
    # artifact would otherwise print in whichever sequence somebody last inserted them in.
    reach.blind = sorted(blind, key=lambda entry: (entry["artifact"], entry["rule"]))
    reach.fired = [rule.id for rule in fired]
    return reach
