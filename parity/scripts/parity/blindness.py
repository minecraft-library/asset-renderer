"""Reach resolution: which artifacts can SEE a change, and which are structurally blind to it.

Reach is a pure function of ``(changed paths, blindness.json)`` and, for a rule that declares one,
the committed reference graph. Nothing here measures anything, and nothing here decides whether a
reach is acceptable - a wide reach is a big plan, never an error. The one refusal is **UNKNOWN**: a
changed path that no rule and no ``no_reach`` glob covers, which means the map has nothing to say and
a bundle built from it could not be sufficient.

A rule carrying ``derived`` authors no ``sees``. Its selection is the reference graph's answer for
the path that fired it, so one glob over a package answers per CLASS rather than per directory: the
whole of ``engine/**`` no longer reaches every render because one file in it does. The rule keeps
everything else it has - its ``blind`` list, its ``reason``, its ``probe`` - because those state what
an artifact OBSERVES, which is a different question from which code a change touches and one no
reference graph can answer. That is why a ``demote`` rule can be derived on one half and authored on
the other, and why the two dump manifests still fall off an engine change.

The graph reaches this module as a callable rather than as a file, which is what keeps the resolution
above independent of how a graph is stored. A path it cannot answer for is a **refusal**, never an
empty selection: an unanswerable path is either a source file the committed graph predates or one
carrying no Java at all, and both would otherwise read as a licensed narrowing.

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

**No shipped rule names an artifact on a suppress rule**, so pass 3 removes nothing from any answer
the map gives today: the one suppression declares both lists empty, and correctly - the value it
speaks for is registered as no artifact, so there is nothing for it to name. The pass is kept for
the case where a rule does have to outrank a selection, and ``test_blindness`` pins that the shipped
map has none, so a rule acquiring one moves this paragraph rather than landing silently.

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
from functools import lru_cache
from pathlib import Path
from typing import Callable, Iterable, Sequence

from parity.norm import MissingInput, read_json

#: Where the map lives inside a store root.
BLINDNESS_FILE = "blindness.json"

#: What a derived rule's selection is read from: one repo-relative path to the artifacts it reaches,
#: or nothing when the graph cannot speak for that path at all.
DerivedReach = Callable[[str], Sequence[str] | None]


class UnknownReach(Exception):
    """A changed path no rule and no ``no_reach`` glob covers. Refusal R1."""

    def __init__(self, paths: Sequence[str]) -> None:
        self.paths = list(paths)
        super().__init__(
            "no blindness rule covers: " + ", ".join(self.paths)
            + " - add a rule (or a no_reach glob) rather than guessing the reach")


@lru_cache(maxsize=None)
def compile_glob(glob: str) -> re.Pattern[str]:
    """Translate one repo-relative glob to a regex.

    ``**`` spans path segments, ``*`` and ``?`` do not. The Java side compiles the identical grammar,
    because a map whose two readers disagree about what a glob matches is worse than no map: the test
    would pass on a rule the planner never fires.

    Cached because it is pure and the callers are quadratic in the worst case - resolving a change
    set walks every rule's globs for every path, and a scan of the source tree walks them again for
    every file. The compiled pattern is immutable, so the cache hands out one object rather than a
    copy.
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
    """One row of the map.

    ``trigger_paths`` is where the claim applies and is the operand of every reader here and in
    Java. It is generated: the sorted union of ``authored_paths``, the half no annotation can reach,
    with every path the declarations carrying this row's ``claim_key`` derive from the source tree.
    A rule with no ``claim_key`` derives nothing, so its two lists are one list.

    ``derived`` says the reference graph answers this rule's selection, per path, and ``sees`` is
    empty because there is nothing left for it to author. The two are exclusive rather than layered:
    an authored list beside a derived one is two answers to one question, and whichever the resolver
    picked the other would go on being read as a statement of what the rule reaches.
    """

    id: str
    claim: str
    trigger_paths: tuple[str, ...]
    sees: tuple[str, ...]
    blind: tuple[str, ...]
    reason: str
    mode: str
    probe: str
    source: str
    claim_key: str = ""
    authored_paths: tuple[str, ...] = ()
    derived: bool = False


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
    rules: list[Rule] = []
    for row in payload.get("rules", []):
        if row.get("derived") and row.get("sees"):
            raise MissingInput(
                f"{target} rule '{row['id']}' is derived and also authors a sees list"
                " - a derived rule's selection is the reference graph's answer, so an authored"
                " one beside it is a second statement of what the rule reaches")
        rules.append(Rule(
            id=row["id"],
            claim=row.get("claim", ""),
            trigger_paths=tuple(row.get("trigger_paths", ())),
            sees=tuple(row.get("sees", ())),
            blind=tuple(row.get("blind", ())),
            reason=row.get("reason", ""),
            mode=row.get("mode", "select"),
            probe=row.get("probe", ""),
            source=row.get("source", ""),
            claim_key=row.get("claim_key", ""),
            authored_paths=tuple(row.get("authored_paths", ())),
            derived=bool(row.get("derived", False)),
        ))
    return rules, tuple(no_reach)


def _selection(rule: Rule, derived: Sequence[str]) -> Sequence[str]:
    """What one rule puts in the bundle on a path: the graph's answer, or its own authored list.

    One function rather than the same conditional at each site, because the union and the record of
    which rule selected what have to read the rule the same way. Reading the authored list in one of
    them and the graph's answer in the other prints a contradiction against a rule that never made
    the claim.

    :param rule: the rule that fired
    :param derived: the graph's answer for the path it fired on
    """
    return derived if rule.derived else rule.sees


def _resolve_path(hits: Sequence[Rule], derived: Sequence[str] = ()) -> list[str]:
    """What one changed path resolves to, given the rules that fired on it.

    The union first, then the two post-union passes in order. A demotion removes what a different
    rule selected **for this same path**, so it cannot run until that path's union is complete; a
    suppression outranks both. Scoping it to the path is what keeps the answer monotone in the change
    set: a rule speaks about the files it triggers on and about no others.

    :param hits: the rules whose triggers match the path
    :param derived: the graph's answer for that path, which every derived rule among them selects
    """
    seen: list[str] = []
    for rule in hits:
        for artifact in _selection(rule, derived):
            if artifact not in seen:
                seen.append(artifact)

    for rule in hits:
        if rule.mode == "demote":
            seen = [artifact for artifact in seen if artifact not in rule.blind]
    suppressed = {artifact for rule in hits if rule.mode == "suppress"
                  for artifact in tuple(rule.sees) + tuple(rule.blind)}
    return [artifact for artifact in seen if artifact not in suppressed]


def _derived_for(path: str, hits: Sequence[Rule], derived: DerivedReach | None) -> tuple[str, ...]:
    """The graph's answer for one path, or a refusal naming what would produce one.

    Refused rather than answered empty. A derived rule fires on a package, and a path under one the
    graph cannot speak for is either a source file the committed graph predates or a file carrying no
    Java at all - so an empty answer would be indistinguishable from a class that really reaches
    nothing, and the whole change would plan narrower than the truth with nothing said about it.

    :param path: the changed path
    :param hits: the rules whose triggers match it
    :param derived: what answers a derived rule's selection, absent when no caller supplied one
    :throws MissingInput: if a derived rule fired and the graph cannot answer for the path
    """
    claiming = [rule.id for rule in hits if rule.derived]
    if not claiming:
        return ()
    found = derived(path) if derived is not None else None
    if found is None:
        raise MissingInput(
            f"'{path}' fires {', '.join(claiming)}, whose reach the reference graph answers, and no"
            " graph answers for it. A Java file the committed graph predates needs"
            " './gradlew compileJava compileTestJava' and then"
            " 'python parity/scripts/parity reach build'; a path carrying no Java needs a rule that"
            " authors its own sees")
    return tuple(found)


def resolve(changed: Sequence[str], rules: Sequence[Rule], no_reach: Sequence[str] = (),
            derived: DerivedReach | None = None) -> Reach:
    """Resolve a changed set against the map.

    Every changed path must be covered by some rule or by ``no_reach``; the uncovered ones come back
    in ``unknown`` and the caller decides that is refusal R1. A ``no_reach`` match is covered and
    contributes nothing, which is a different statement from "I do not know".

    Each path is resolved on its own and the answers are unioned, so a demotion cannot reach out of
    its own triggers and cancel what another file in the same commit genuinely moves. The graph is
    asked once per path for the same reason: every derived rule that fires on a path selects the same
    answer, that answer being a property of the file rather than of which rule reached it.

    :param changed: the changed paths, repo-relative and POSIX
    :param rules: the map's rules
    :param no_reach: the globs that cover a path without giving it reach
    :param derived: what answers a derived rule's selection
    :throws MissingInput: if a derived rule fired on a path no graph answers for
    """
    reach = Reach()
    fired: list[Rule] = []
    per_path: list[tuple[str, list[Rule], tuple[str, ...]]] = []

    for path in changed:
        hits = [rule for rule in rules if matches(path, rule.trigger_paths)]
        for rule in hits:
            if rule not in fired:
                fired.append(rule)
        if hits:
            per_path.append((path, hits, _derived_for(path, hits, derived)))
        elif matches(path, no_reach):
            reach.no_reach.append(path)
        else:
            reach.unknown.append(path)

    seen: list[str] = []
    # Which rules actually PUT each surviving artifact in the bundle, rather than which ones merely
    # name it: a rule whose selection its own path demoted away has not selected anything, and saying
    # it did would name the wrong rule in the contradiction the plan prints.
    selectors: dict[str, list[str]] = {}
    for _, hits, answer in per_path:
        for artifact in _resolve_path(hits, answer):
            if artifact not in seen:
                seen.append(artifact)
            for rule in hits:
                if (artifact in _selection(rule, answer)
                        and rule.id not in selectors.setdefault(artifact, [])):
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
