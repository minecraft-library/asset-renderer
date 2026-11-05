"""Whether a capture has to boot the client to refresh the reference tree, or can hash it as it stands.

The harness renders ``idle/`` and ``walk/`` at the entity schedule roster the asset side writes
beside the tree, and each of those sub-trees records the schedule every strip in it was rendered at
in its ``schedule.json``. Every other sub-tree is drawn by a frozen sweep that reads nothing of the
roster. So when the only route from a change into the tree is the roster, whether the tree can move
is a comparison of two things a capture already holds - the roster it has just regenerated and the
schedules the tree records - and finding out by re-rendering the tree costs a client boot.

:func:`refresh` is the plan's half: it reads which rules put ``manifest.references`` in the plan,
and answers :data:`ROSTER` when every one of them reaches the tree through the roster alone and
:data:`BOOT` otherwise. The capture's half is three holds, and the boot is skipped only where the
plan said :data:`ROSTER` and all three agree. :func:`held_to_plan` holds that answer to the tree as
it stands when the capture runs, because a plan is a file that outlives every edit made after it.
:func:`held_to_record` holds what the tree is rendered from but the roster to the record of the last
whole-tree render, because a tree a narrow run, a failed boot or other harness sources last wrote
hashes cleanly over the files nobody refreshed. :func:`check` projects the regenerated roster onto
the two recorded schedules the way the harness reads it. The capture of ``manifest.references`` is
the same step either way, so the stored value is digested in the same form whether the tree was
re-rendered or not, and a capture that hashed it says so in its provenance.

**The record is written around the one run that renders the tree whole.** :func:`inputs` digests
every file a rule that boots the tree triggers on - the harness's sources and wiring, and the two
rosters it reads by path - before a whole, untargeted ``renderVanillaAllReferences`` boots, into
:data:`PENDING` beside the tree; the build renames it to :data:`RECORD` once that boot exits 0, and
every run that writes into the tree deletes :data:`RECORD` before it boots.

**The check refuses rather than guesses.** Anything it cannot certify is a disagreement, and a
disagreement only ever costs the boot the gate would have paid anyway: a roster it cannot read, one of
another format or resolved for another client, a schedule carrying ``coats`` - the posed sweep sizes a
canvas across every coat's schedule and the sidecar records the drawn coat's alone, so nothing on disk
says what the others were rendered at - a roster entry with no strip on disk for a subject the
frozen ``entities/`` sub-tree shows the client draws, and two stems recording one subject.

**Values are compared as the numbers ``json.loads`` parses them to.** Both files spell each value as
the shortest decimal of a Java ``float``, which parses to one double. A spelling that parses to
another double disagrees - which costs a boot where the harness would read the two as one float, and
can never pass a value it reads differently - and an integer and a float of one value, ``0`` and
``0.0``, parse equal and agree.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any, Collection, Sequence

from parity.blindness import Rule, matches
from parity.norm import posix, read_json

#: The row that hashes the whole reference tree, and the one row this module speaks about.
REFERENCES = "manifest.references"

#: The authored rules whose route into the reference tree is the entity schedule roster alone. B47's
#: version pins reach the tree only through the roster the visual classpath writes - the harness is a
#: build of its own whose versions B29 speaks for - and B65's triggers are the roster's own inputs:
#: the two entity tables it is written from and its writer. A derived rule is the third route, read
#: off the rules themselves rather than listed: ``manifest.references`` roots at the resolution the
#: roster is written through, so the reference graph answers it for a type only through that root.
ROSTER_RULES = ("B47", "B65")

#: A capture re-renders the whole reference tree before it hashes it.
BOOT = "boot"

#: A capture regenerates the roster and re-renders the tree only where :func:`check` disagrees.
ROSTER = "roster"

#: Where a capture's verdict is written, under the working root's ``_run/`` and so outside the
#: capture: the build reads it back to decide the boot, and it stays beside the capture it decided.
VERDICT = "schedule-check.json"

#: The record of the last whole-tree render, a sibling of the reference tree rather than a file in
#: it, so the manifest that hashes the tree never reads it.
RECORD = "references-rendered.json"

#: The record a whole-tree render writes of its inputs before it boots, which the build renames to
#: :data:`RECORD` once the boot exits 0.
PENDING = "references-rendered.pending.json"

#: What a capture of ``manifest.references`` records in its provenance where the tree was hashed as
#: it stood rather than rendered.
HASHED = "hashed"

#: The format the roster and the sidecars are both written in, and the one the harness reads.
FORMAT = 1

#: The posed sub-trees, each named as the gait it renders and as the roster's key for that gait.
GAITS = ("idle", "walk")

#: The ages the roster keys an entity's schedules by, in the harness's own reading order.
AGES = ("adult", "baby")

#: The members of a schedule a strip is rendered at, beside its clocks.
FIELDS = ("frames", "ticks_per_frame", "walk_speed", "walk_rate")

#: The sidecar stem's spelling of a baby, which is an age rather than a trait.
BABY_TOKEN = "age=baby"

#: How many disagreements a verdict lists. The count beside them is always whole.
LISTED = 20


def refresh(selectors: Sequence[str], derived: Collection[str]) -> str:
    """How a capture of a plan holding ``manifest.references`` refreshes the tree.

    :param selectors: the fired rules that put ``manifest.references`` in the plan
    :param derived: the ids of the map's derived rules, whose selection the reference graph answers
    :return: :data:`ROSTER` when every selecting rule reaches the tree through the roster alone, and
        :data:`BOOT` when any does not or none is named
    """
    if not selectors:
        return BOOT
    return ROSTER if all(rule in ROSTER_RULES or rule in derived for rule in selectors) else BOOT


def held_to_plan(planned: dict | None, live: dict | None) -> list[str]:
    """Hold the plan's answer to the answer the tree as it stands gives.

    The plan is read back from a file that outlives every edit made after it was written, and a
    capture may follow a plan priced for other paths, or one written in the same invocation after
    the build had already read the last one. So the tree is resolved again from git when the capture
    runs, and the skip stands only where that answer is :data:`ROSTER` and names the very rules the
    plan named - a harness edit or an edit to a roster the harness reads by path made since the plan
    selects a rule that boots, and a change set the plan was never priced for names other rules.

    :param planned: the plan's ``references`` member, or None where the working root holds no plan
        or a plan holding no such row
    :param live: the same answer resolved from the tree as it stands, or None where it holds no
        such row
    :return: the disagreements, empty where the two agree on a roster refresh
    """
    if planned is None:
        return ["the working root holds no plan saying how the capture refreshes the tree"]
    if live is None:
        return [f"the tree as it stands plans no {REFERENCES}, so the plan the capture runs was made "
                "of another tree"]
    if live.get("refresh") != ROSTER:
        return [f"the tree as it stands reaches {REFERENCES} through "
                f"{', '.join(live.get('selected_by') or ()) or 'no rule'}, which boot the client"]
    if live != planned:
        return [f"the plan names {', '.join(planned.get('selected_by') or ()) or 'no rule'} and the "
                f"tree as it stands {', '.join(live.get('selected_by') or ())}, so the plan the "
                "capture runs was made of another tree"]
    return []


def boot_rules(rules: Sequence[Rule]) -> list[Rule]:
    """The rules whose route into the reference tree is not the roster.

    Every rule naming ``manifest.references`` but the roster rules and the derived ones - the
    harness's sources and wiring, and the tooltip and book rosters the harness reads by path. What
    their triggers match is what a whole-tree render reads beside the roster.

    :param rules: the map's rules
    :return: those rules, in map order
    """
    return [rule for rule in rules
            if REFERENCES in rule.sees and not rule.derived and rule.id not in ROSTER_RULES]


def inputs(repo: Path, files: Sequence[str], rules: Sequence[Rule]) -> dict:
    """Digest what a whole-tree render reads beside the roster.

    Every listed file a :func:`boot_rules` trigger matches, by path and raw content - the tree's
    bytes are a function of those files and of the roster, which :func:`check` answers for. Raw
    bytes rather than normalized ones, because both readings are of one checkout on one machine. A
    listed file absent from the worktree is digested as absent rather than skipped.

    :param repo: the repository root
    :param files: the repository's files, repo-relative and POSIX - what git tracks and what it
        would track
    :param rules: the map's rules, live triggers already folded in
    :return: the digest, how many files it covers, and the rules whose triggers chose them
    """
    chosen = boot_rules(rules)
    globs = sorted({glob for rule in chosen for glob in rule.trigger_paths})
    matched = sorted(name for name in set(files) if matches(name, globs))
    digest = hashlib.sha256()
    for name in matched:
        digest.update(name.encode("utf-8") + b"\0")
        try:
            digest.update(hashlib.sha256((repo / name).read_bytes()).digest())
        except OSError:
            digest.update(b"absent")
    return {"files": len(matched), "inputs": digest.hexdigest(),
            "rules": sorted({rule.id for rule in chosen})}


def record(references: str, current: dict) -> dict:
    """The record a whole-tree render writes of its inputs before it boots.

    :param references: the reference tree root, repo-relative
    :param current: what :func:`inputs` answered
    :return: the record
    """
    return {"files": current["files"], "format": FORMAT, "inputs": current["inputs"],
            "kind": "reference-render", "references": posix(references), "rules": current["rules"]}


def held_to_record(tree: Path, current: dict | None) -> list[str]:
    """Hold what the tree is rendered from now to the record of its last whole render.

    :param tree: the reference tree, beside which the record sits
    :param current: what :func:`inputs` answers for the tree as it stands, or None where the
        files could not be listed
    :return: the disagreements, empty where the record stands and its inputs are the current ones
    """
    if current is None:
        return ["the files a whole-tree render reads could not be listed"]
    sealed = tree.parent / RECORD
    if not sealed.is_file():
        return [f"no {RECORD} beside the tree, so nothing says its last write was a whole, untargeted "
                "boot that exited 0: every run writing into the tree deletes it, and only "
                "renderVanillaAllReferences writes it"]
    try:
        payload = read_json(sealed)
    except ValueError as error:
        return [f"{RECORD} does not parse: {error}"]
    if not isinstance(payload, dict) or str(payload.get("format")) != str(FORMAT):
        return [f"{RECORD} is not format {FORMAT}"]
    if payload.get("inputs") != current["inputs"]:
        return [f"the tree was last rendered whole from other inputs than "
                f"{', '.join(current['rules'])} trigger on now"]
    return []


def check(repo: Path, references: str, client_version: str, refusals: Sequence[str] = ()) -> dict:
    """Project the roster beside a reference tree onto the schedules its posed sub-trees recorded.

    Mirrors what the harness reads: the roster's format and the client it was resolved for, compared
    by major.minor; per gait, every adult and baby entry's schedule and each posed trait under it;
    and the stems each sub-tree recorded, one per subject it rendered. An entry with no stem is a
    subject the client does not draw - which is excused only where the frozen ``entities/`` sub-tree
    holds no reference of that entity at that age either, the two sweeps choosing their subjects the
    same way.

    :param repo: the repository root the tree's path is relative to
    :param references: the reference tree root, repo-relative, beside which the roster sits
    :param client_version: the version of the client the harness runs
    :param refusals: what the holds outside the roster already found, listed first
    :return: the verdict - ``agrees`` and the disagreements behind it, the stems each gait recorded
        and the entries excused as drawn by no sweep
    """
    disagreements: list[str] = list(refusals)
    tree = repo / references
    roster_file = tree.parent / "entity-schedule" / "roster.json"
    source_version, expected = _roster(roster_file, client_version, disagreements)
    entities = _entity_references(tree / "entities", disagreements)
    stems: dict[str, int] = {}
    excused: list[str] = []
    for gait in GAITS:
        recorded = _sidecar(tree / gait / "schedule.json", gait, disagreements)
        stems[gait] = len(recorded)
        _compare(gait, expected.get(gait, {}), recorded, entities, disagreements, excused)
    return {
        "agrees": not disagreements,
        "client_version": client_version,
        "count": len(disagreements),
        "disagreements": disagreements[:LISTED],
        "excused": sorted(excused),
        "format": 1,
        "kind": "schedule-check",
        "references": posix(references),
        "roster": posix(roster_file, repo),
        "source_version": source_version,
        "stems": stems,
    }


def _roster(roster_file: Path, client_version: str,
            disagreements: list[str]) -> tuple[Any, dict[str, dict]]:
    """Every schedule the roster names, per gait and keyed by entity, age and trait.

    :param roster_file: the roster
    :param client_version: the version of the client the harness runs
    :param disagreements: where each refusal is appended
    :return: the client the roster was resolved for, and the projected schedules
    """
    roster = _read(roster_file, "entity-schedule/roster.json", disagreements)
    if roster is None:
        return None, {}
    out: dict[str, dict] = {}
    if str(roster.get("format")) != str(FORMAT):
        disagreements.append(f"the roster is format '{roster.get('format')}', and the harness reads "
                             f"format {FORMAT}")
    resolved_for = roster.get("source_version")
    if not isinstance(resolved_for, str) or _major_minor(resolved_for) != _major_minor(client_version):
        disagreements.append(f"the roster was resolved for '{resolved_for}', and the harness runs "
                             f"'{client_version}'")
    subjects = roster.get("subjects")
    if not isinstance(subjects, dict):
        disagreements.append("the roster carries no subjects object")
        return resolved_for, out
    for gait in GAITS:
        out[gait] = {}
    for entity, ages in sorted(subjects.items()):
        if not isinstance(ages, dict):
            disagreements.append(f"the roster's {entity} is not an object")
            continue
        for age in AGES:
            gaits = ages.get(age)
            if gaits is None:
                continue
            for gait in GAITS:
                entry = gaits.get(gait) if isinstance(gaits, dict) else None
                if entry is None:
                    continue
                _entry(entity, age, gait, entry, out[gait], disagreements)
    return resolved_for, out


def _entry(entity: str, age: str, gait: str, entry: Any, into: dict, disagreements: list[str]) -> None:
    """Project one roster entry, and each posed trait it carries, into a gait's schedules.

    :param entity: the entity id
    :param age: the age key
    :param gait: the gait key
    :param entry: the entry as the roster spells it
    :param into: the gait's schedules, keyed by entity, age and trait
    :param disagreements: where each refusal is appended
    """
    where = f"{entity} {age} {gait}"
    if not isinstance(entry, dict):
        disagreements.append(f"the roster's {where} is not an object")
        return
    if entry.get("coats"):
        disagreements.append(f"the roster's {where} carries coats: the posed sweep sizes its canvas "
                             "across every coat's schedule and the sidecar records the drawn coat's "
                             "alone, so nothing on disk says what the others were rendered at")
    into[(entity, age, None)] = _projection(entry, where, disagreements)
    traits = entry.get("traits") or {}
    if not isinstance(traits, dict):
        disagreements.append(f"the roster's {where} traits are not an object")
        return
    for trait, nested in sorted(traits.items()):
        if isinstance(nested, dict) and ("coats" in nested or "traits" in nested):
            disagreements.append(f"the roster's {where} trait {trait} nests coats or traits, which the "
                                 "harness refuses")
        into[(entity, age, trait)] = _projection(nested, f"{where} {trait}", disagreements)


def _sidecar(sidecar: Path, gait: str, disagreements: list[str]) -> dict:
    """The schedule each stem of one posed sub-tree was rendered at, keyed by entity, age and trait.

    :param sidecar: the sub-tree's ``schedule.json``
    :param gait: the gait it records
    :param disagreements: where each refusal is appended
    :return: the projected schedules, each beside the stem that recorded it
    """
    record = _read(sidecar, f"{gait}/schedule.json", disagreements)
    if record is None:
        return {}
    if str(record.get("format")) != str(FORMAT) or not isinstance(record.get("subjects"), dict):
        disagreements.append(f"{gait}/schedule.json is not format {FORMAT}")
        return {}
    out: dict[tuple, tuple] = {}
    for stem, entry in sorted(record["subjects"].items()):
        tokens = [token for token in stem.split("~")[1:] if token != BABY_TOKEN]
        if not isinstance(entry, dict) or len(tokens) > 1:
            disagreements.append(f"{gait}: the stem {stem} names no one subject this check can read")
            continue
        key = (entry.get("entity"), entry.get("age"), tokens[0] if tokens else None)
        # The sidecar keeps every stem a narrowed run did not enumerate, so a stem of an older
        # spelling can sit beside the live one for one subject; which of them the roster is held to
        # would then be a question of which sorts last.
        if key in out:
            disagreements.append(f"{gait}: the stems {out[key][0]} and {stem} both record "
                                 f"{key[0]} {key[1]}" + (f" {key[2]}" if key[2] else ""))
            continue
        out[key] = (stem, _projection(entry, f"{gait} {stem}", disagreements))
    return out


def _compare(gait: str, expected: dict, recorded: dict, entities: list[str] | None,
             disagreements: list[str], excused: list[str]) -> None:
    """Hold one posed sub-tree's recorded schedules to the roster's, both ways.

    :param gait: the gait
    :param expected: the roster's schedules for it
    :param recorded: the sub-tree's, each beside its stem
    :param entities: the frozen ``entities/`` sub-tree's file names, or None where it is absent
    :param disagreements: where each disagreement is appended
    :param excused: where each roster entry no sweep draws is appended
    """
    for key, (stem, schedule) in sorted(recorded.items(), key=lambda item: item[1][0]):
        if key not in expected:
            disagreements.append(f"{gait}: {stem} was rendered and the roster names no schedule for it")
        elif expected[key] != schedule:
            disagreements.append(f"{gait}: {stem} was rendered at {_spelled(schedule)} and the roster "
                                 f"now names {_spelled(expected[key])}")
    drawn = {(entity, age) for entity, age, _ in recorded}
    for key in sorted(set(expected) - set(recorded), key=str):
        entity, age, trait = key
        named = f"{entity} {age}" + (f" {trait}" if trait else "")
        if (entity, age) in drawn:
            disagreements.append(f"{gait}: the roster names {named}, and the sub-tree holds no strip "
                                 "of it")
        elif entities is None or _witnessed(entities, entity, age):
            disagreements.append(f"{gait}: the frozen entities/ shows the client draws {entity} "
                                 f"{age}, and the sub-tree holds no strip of {named}")
        else:
            excused.append(f"{named} {gait}")


def _projection(entry: Any, where: str, disagreements: list[str]) -> tuple | None:
    """The part of a schedule a strip is rendered at: its four members and its clocks.

    :param entry: the schedule as either file spells it
    :param where: whose schedule it is, for a refusal
    :param disagreements: where a refusal is appended
    :return: the projection, or None where a member is missing
    """
    if not isinstance(entry, dict) or any(name not in entry for name in FIELDS):
        disagreements.append(f"{where} is missing one of {', '.join(FIELDS)}")
        return None
    clocks = entry.get("clocks") or {}
    if not isinstance(clocks, dict):
        disagreements.append(f"{where} clocks are not an object")
        return None
    return (tuple(entry[name] for name in FIELDS)
            + (tuple(sorted((name, tuple(values)) for name, values in clocks.items())),))


def _spelled(schedule: tuple | None) -> str:
    """A projection as a disagreement names it."""
    if schedule is None:
        return "no readable schedule"
    members = ", ".join(f"{name} {value}" for name, value in zip(FIELDS, schedule))
    clocks = schedule[len(FIELDS)]
    return members + (f", clocks {', '.join(name for name, _ in clocks)}" if clocks else "")


def _entity_references(directory: Path, disagreements: list[str]) -> list[str] | None:
    """The frozen entity sub-tree's file names, which say which entities the client draws.

    :param directory: the ``entities/`` sub-tree
    :param disagreements: where its absence is appended
    :return: the file names, or None where the sub-tree is absent
    """
    if not directory.is_dir():
        disagreements.append("the reference tree holds no entities/ sub-tree to say which entities "
                             "the client draws")
        return None
    return sorted(path.name for path in directory.iterdir() if path.name.endswith(".png"))


def _witnessed(names: list[str], entity: str, age: str) -> bool:
    """Whether the frozen entity sub-tree holds a reference of an entity at an age.

    A file belongs to the entity when its name is the entity's spelling followed by the extension, a
    coat or an appearance token. A prefix match can only over-answer - an entity whose spelling
    starts another's would read as drawn - and an over-answer is a disagreement, which costs a boot.

    :param names: the sub-tree's file names
    :param entity: the entity id
    :param age: the age key
    :return: whether any file shows the client drawing it at that age
    """
    prefix = entity.replace(":", "__")
    for name in names:
        if not name.startswith(prefix) or name[len(prefix):len(prefix) + 1] not in (".", "_", "~"):
            continue
        baby = BABY_TOKEN in name[:-len(".png")].split("~")[1:]
        if baby == (age == "baby"):
            return True
    return False


def _read(path: Path, what: str, disagreements: list[str]) -> Any:
    """A JSON file, or None with a disagreement where it is absent or does not parse.

    :param path: the file
    :param what: how a disagreement names it
    :param disagreements: where a refusal is appended
    :return: the parsed document, or None
    """
    if not path.is_file():
        disagreements.append(f"no {what} beside the tree")
        return None
    try:
        payload = read_json(path)
    except ValueError as error:
        disagreements.append(f"{what} does not parse: {error}")
        return None
    if not isinstance(payload, dict):
        disagreements.append(f"{what} is not an object")
        return None
    return payload


def _major_minor(version: str) -> str:
    """A version id cut to its first two dot-separated parts, as the harness compares one."""
    parts = version.split(".")
    return version if len(parts) < 2 else f"{parts[0]}.{parts[1]}"
