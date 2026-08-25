"""``promote-plan`` and ``promote-apply`` - the one act that may write production.

Promotion is deliberate, separate and recorded, never a side effect of a measurement. Every refusal
below is a mechanism for an invariant rather than a policy someone has to remember:

- no ``--reason``, no promotion;
- ``determinism_runs`` below the artifact's floor, no promotion - a value a second independent run
  does not reproduce is not a baseline whatever anyone declares about it;
- ``failed > 0``, no promotion without an explicit ``--allow-partial``, because a partial sweep
  leaves a tree that hashes cleanly minus the missing files;
- an entry count that disagrees with the baseline's, no promotion without an explicit
  ``--population-changed``, because a covered set that moved is a different question from a value
  that moved and the tree hashes cleanly either way;
- a root whose digests disagree with its own capture index, no promotion, so a plan cannot be
  applied to a root that has since been re-captured;
- no compare of this capture covering this artifact, no promotion, so a promotion can only ever
  apply a diff a human has been shown. ``--bootstrap`` is its one exemption, because a first
  baseline has nothing to be diffed against - and the exemption is per-INVOCATION, so a bootstrap
  promotion of a whole root carries the already-baselined rows beside the new one;
- a capture taken from an uncommitted tree, no promotion without an explicit ``--allow-dirty``. It
  is the only refusal here whose failure mode cannot be detected afterwards: a baseline whose
  capture cannot be shown to have run on a committed tree is not re-derivable from any commit, and
  nothing in the stored value says so.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from parity import capture as capture_mod
from parity import compare as compare_mod
from parity import provenance as provenance_mod
from parity import store as store_mod
from parity.norm import MissingInput, Refused, read_json, sha256_text, canonical_json, write_json

CLASSES = ("neutral", "shaped", "moving")

#: The index-row field carrying how many runs a first promotion of that artifact performs.
FLOOR_FIELD = "determinism_floor"

#: The index-row field carrying how many entries the promoted file holds, which is what a capture's
#: own count is compared against.
ENTRIES_FIELD = "entries"


def floor_for(artifact: str, index: dict) -> int:
    """How many runs prove this artifact reproducible, read off its row in the store's index.

    **One table, and it is the roster's.** ``ParityArtifacts`` declares the floor per artifact, the
    index row carries it, a promotion writes it back, and this reads it. A second table here would
    be a copy that disagreed silently, which it did: nine artifacts published a floor of 1 and were
    refused below 2, so the published number was unreachable and the refusal came after the capture
    that earned it.

    :param artifact: the artifact id
    :param index: the production store's index envelope
    :return: the declared floor
    :raises MissingInput: if the index registers no floor for it
    """
    row = (index.get("artifacts") or {}).get(artifact) or {}
    declared = row.get(FLOOR_FIELD)
    if declared is None:
        raise MissingInput(
            f"the store's index declares no {FLOOR_FIELD} for {artifact!r}, so how many runs prove "
            "it reproducible is unanswerable. Coining an artifact is an edit to ParityArtifacts and "
            "to the store's index, and ParityIndexTest relates the two")
    return int(declared)


@dataclass
class Entry:
    artifact: str
    action: str          # new | replace | unchanged
    path: str
    movers: int = 0


def plan(root: Path, base: store_mod.ReadOnlyStore,
         artifacts: Sequence[str] | None = None) -> list[Entry]:
    """Read-only. Walks the working root path-for-path against the base store.

    Each ``replace`` carries its mover count, so the plan reads as *what promoting this would
    change* rather than merely *which files differ*.
    """
    entries = []
    for artifact in sorted(artifacts or _artifacts_under(root)):
        relative = store_mod.path_of(artifact)
        current = root / relative
        if not current.is_file():
            raise MissingInput(f"{artifact} is not in the working root at {current}")
        if not (base.root / relative).is_file():
            entries.append(Entry(artifact, "new", relative))
            continue
        left, right = base.read(artifact), read_json(current)
        if _same(left, right):
            entries.append(Entry(artifact, "unchanged", relative))
            continue
        moved = 0
        try:
            moved = compare_mod.compare(left, right).totals()["moved"]
        except Exception:  # noqa: BLE001 - a kind with no join still promotes, just uncounted
            moved = -1
        entries.append(Entry(artifact, "replace", relative, moved))
    return entries


def _same(left: dict, right: dict) -> bool:
    """Provenance is expected to differ on every capture, so it is not what decides a replace."""
    return sha256_text(canonical_json(_without_provenance(left))) == \
        sha256_text(canonical_json(_without_provenance(right)))


def _without_provenance(payload: dict) -> dict:
    return {name: value for name, value in payload.items() if name != "provenance"}


def _artifacts_under(root: Path) -> list[str]:
    """Every artifact this root CAPTURED. An unstamped file a producer left is not one of them."""
    return [artifact for artifact, stamped in store_mod.artifact_files(root) if stamped]


def to_report(entries: Sequence[Entry]) -> dict:
    return {
        "artifact": "report.promotion-plan",
        "entries": [{"action": entry.action, "artifact": entry.artifact,
                     "movers": entry.movers, "path": entry.path} for entry in entries],
        "format": 1,
        "kind": "promotion-plan",
        "totals": {action: sum(1 for entry in entries if entry.action == action)
                   for action in ("new", "replace", "unchanged")},
    }


def check(root: Path, entries: Sequence[Entry], reason: str, index: dict,
          allow_partial: bool = False, bootstrap: bool = False, allow_dirty: bool = False,
          population_changed: bool = False, repo: Path | None = None) -> None:
    """Every refusal, in one place, before a single production byte is written.

    :param root: the working root
    :param entries: the promotion plan
    :param reason: the ``--reason`` text
    :param index: the production store's index envelope, which declares each artifact's floor and
        holds the entry count its last baseline was taken over
    :param allow_partial: whether a capture recording failures may be promoted
    :param bootstrap: whether this invocation establishes first baselines
    :param allow_dirty: whether a capture taken from an uncommitted tree may be promoted
    :param population_changed: whether an entry count moving from the baseline's is intended
    :param repo: the repository root, for reading whether a dirty capture's content is now committed;
        absent, that reading is unavailable and a dirty capture refuses as it always did
    :raises Refused: on any of the refusals above
    """
    if not reason.strip():
        raise Refused("promote-apply requires --reason: a promotion is a recorded act")

    capture_mod.require_unmoved(root)
    # `--bootstrap` is the one exemption, and it is per-INVOCATION where the thing it excuses is
    # per-ARTIFACT: the flag is typed because a row has no baseline, and it cannot say which row
    # that was. So a bootstrap promotion over a whole root writes the already-baselined rows beside
    # the new one with no compare either, and `--artifacts` is what narrows the write to the row the
    # flag was meant for. The refusal one loop below still fires per row, so nothing is promoted as
    # a first baseline that already had one.
    if not bootstrap:
        _require_compare(root, entries)

    for entry in entries:
        payload = read_json(root / entry.path)
        record = payload.get("provenance") or {}
        if not record:
            raise Refused(f"{entry.artifact} carries no provenance object; a promoted artifact "
                          "without one is unrepresentable")
        runs = record.get("determinism_runs") or 0
        needed = floor_for(entry.artifact, index)
        if runs < needed:
            raise Refused(
                f"{entry.artifact} records determinism_runs={runs}, below its floor of {needed}: "
                "a value a second independent run does not reproduce is not a baseline")
        counts = record.get("counts") or {}
        _require_population(entry, payload, counts, index, population_changed)
        if counts.get("failed", 0) and not allow_partial:
            raise Refused(
                f"{entry.artifact} records failed={counts['failed']}; a partial run leaves a tree "
                "that hashes cleanly minus the missing files. Pass --allow-partial to record "
                "the exception in provenance")
        if entry.action == "new" and not bootstrap:
            raise Refused(
                f"{entry.artifact} has no baseline to replace; the first promotion of an artifact "
                "is --bootstrap, and it is refused for anything below its determinism floor")
        dirty = record.get("asset_dirty")
        if dirty is not False and not allow_dirty and not _content_is_now_committed(record, repo):
            raise Refused(
                f"{entry.artifact} records asset_dirty={dirty} and the content it measured is not "
                "the content of the current tree, so this baseline is not re-derivable from any "
                "commit and no later reading recovers that. Gate, then commit WITHOUT further "
                "edits, then promote - the capture is re-read rather than re-run. Pass "
                "--allow-dirty to record the exception in provenance")


def _content_is_now_committed(record: dict, repo: Path | None) -> bool:
    """Whether the content a dirty capture measured is the content of the tree as it stands, clean.

    What R4 needs is that a baseline be re-derivable from a commit - not that the capture ran after
    one. Those came apart because ``asset_dirty`` names an *ordering*, so the only way to satisfy it
    was to commit and capture a second time, at the cost of the whole bundle. A capture now records
    the content it read, and committing that content changes none of it.

    Both halves are required. The tree must be clean, or "committed" is not yet true of it; and the
    digests must match, or this is some other content that merely happens to be committed.

    :param record: the capture's provenance
    :param repo: the repository root, or nothing when it cannot be read
    :return: whether the dirty-tree refusal is satisfied by content instead of by ordering
    """
    if repo is None:
        return False
    captured = record.get("asset_content_digest")
    if not captured:
        return False
    state = provenance_mod.asset_state(repo)
    if state.get("asset_dirty") is not False:
        return False
    return captured == provenance_mod.content_digest(repo)


def _require_population(entry: Entry, payload: dict, counts: dict, index: dict,
                        population_changed: bool) -> None:
    """Refuse a promotion whose capture holds a different number of entries than its baseline.

    ``--population-changed`` is what says the move is intended, and until this it stamped a flag and
    compared nothing - so the waiver recorded an exception to a rule nothing enforced. What it now
    waives is this: a row count that moved is a different covered set, and a baseline replaced over
    one is a tree that hashes cleanly while holding fewer subjects than the number it is quoted at.

    The comparison is against the index's own ``entries`` column, which a promotion writes from the
    same capture's counts, so the two are the same measurement one baseline apart. A row with no
    baseline has nothing to have moved from and is passed over - a first promotion's population is
    whatever it captured, and ``--bootstrap`` is the flag that says so.

    :param entry: the plan entry
    :param payload: the captured artifact
    :param counts: its provenance counts
    :param index: the production store's index envelope
    :param population_changed: whether the move is intended
    :raises Refused: if the counts disagree and the waiver was not given
    """
    member = store_mod.rows_member(payload.get("kind", ""))
    captured = counts.get(member) if member else None
    row = (index.get("artifacts") or {}).get(entry.artifact) or {}
    recorded = row.get(ENTRIES_FIELD)
    if captured is None or recorded is None or captured == recorded or population_changed:
        return
    raise Refused(
        f"{entry.artifact} captured {captured} {member} where its baseline holds {recorded}: a "
        "population that moved is a different covered set, and the tree hashes cleanly either way. "
        "Pass --population-changed to record the exception in provenance")


def _require_compare(root: Path, entries: Sequence[Entry]) -> None:
    """Refuse a promotion no compare of this capture has shown a human.

    The report has to be **this** capture's. A working root is single-slot and self-overwriting, so
    the report an earlier capture left in it names the same artifacts and says nothing whatever
    about the bytes about to be written; the capture index's own digest is stamped into the report
    for exactly that, and read back here.

    It also has to cover every artifact the promotion would write. A compare scoped to one row is no
    comparison at all of the others, and ``-Partifacts`` scopes the two commands separately. An
    ``unchanged`` entry writes no production byte, so it is not held to it.

    **A row the compare found no baseline for is covered.** It looks the row up, finds nothing to
    diff against and files it under ``missing_baseline`` rather than under ``artifacts``, which is
    where ``--base`` pointed at another tree puts a row this store does hold. Reading the one list
    alone refuses that promotion for not having been compared when it was: what this function asks
    is whether the compare LOOKED, and there it did.

    ``--bootstrap`` never reaches here - the caller is the one place that decides an exemption, so a
    first baseline is answered by not asking rather than by an arm inside the question.

    :param root: the working root
    :param entries: the promotion plan
    :raises Refused: if no report is there, if it was written against another capture, or if it does
        not cover an artifact this promotion would write
    """
    report = root / store_mod.RUN_DIR / compare_mod.REPORT
    if not report.is_file():
        raise Refused(
            f"no {store_mod.RUN_DIR}/{compare_mod.REPORT} under {root}: a promotion applies a diff "
            "someone has been shown, so parityCompare runs first. A first baseline has nothing to "
            "be diffed against and is exempt under --bootstrap, which exempts every row in the "
            "same invocation - narrow one with --artifacts")
    payload = read_json(report)
    taken = capture_mod.content_digest(root)
    if payload.get(compare_mod.CAPTURE_DIGEST) != taken:
        raise Refused(
            f"{store_mod.RUN_DIR}/{compare_mod.REPORT} was written against a different capture "
            f"({payload.get(compare_mod.CAPTURE_DIGEST)} against {taken}); the working root is one "
            "slot, so re-run parityCompare over the capture being promoted")
    compared = {row.get("artifact") for row in payload.get("artifacts") or []}
    compared |= set(payload.get("missing_baseline") or [])
    uncompared = sorted(entry.artifact for entry in entries
                        if entry.action != "unchanged" and entry.artifact not in compared)
    if uncompared:
        raise Refused(
            f"the compare did not cover {', '.join(uncompared)}, which this promotion would write; "
            "widen parityCompare's -Partifacts to them, or narrow the promotion to what was compared")


def apply(root: Path, target: store_mod.WritableStore, entries: Sequence[Entry], reason: str,
          parity_class: str = "moving", allow_partial: bool = False,
          population_changed: bool = False, allow_dirty: bool = False) -> dict:
    """Copy through ``norm``, so a hand-edited CRLF capture is normalized on the way in.

    That is why the copy is Python rather than a Kotlin ``copy { }``: a byte copy would carry a CRLF
    straight into the store, and its one byte form - LF, UTF-8 without a BOM, one trailing newline -
    would hold only by luck.

    An ``unchanged`` entry is skipped before its file is even read: no artifact byte is written for
    it and ``index["artifacts"]`` is only assigned inside the same loop, so the row it already had -
    ``promoted_at`` included - is carried through from the store as it stood. Widening a promotion
    past the rows that moved therefore rewrites nothing extra. ``plan`` classifies on a
    provenance-stripped digest, so a re-capture of an artifact whose value did not move is
    ``unchanged`` even though its provenance records a different run.
    """
    index = target.index()
    written = []
    for entry in entries:
        if entry.action == "unchanged":
            continue
        payload = read_json(root / entry.path)
        record = payload.setdefault("provenance", {})
        record["reason"] = reason
        record["parity_class"] = parity_class
        if allow_partial:
            record["allow_partial"] = True
        if allow_dirty:
            record["allow_dirty"] = True
        if population_changed:
            record["population_changed"] = True
        target.write(entry.artifact, payload)
        index["artifacts"][entry.artifact] = _index_row(entry, payload,
                                                        floor_for(entry.artifact, index))
        written.append(entry.artifact)

    index.setdefault("//", "parity.report.oracle-index · regen: ./gradlew parityPromote")
    index.setdefault("artifact", "report.oracle-index")
    index.setdefault("format", 1)
    index.setdefault("key", "artifact")
    index.setdefault("kind", "index")
    write_json(target.path("report.oracle-index"), index)
    return {"promoted": written, "reason": reason, "parity_class": parity_class}


def _index_row(entry: Entry, payload: dict, floor: int) -> dict:
    """Rebuild one artifact's index row from the payload being promoted.

    The row is built field by field rather than merged over the one it replaces, so anything a row
    is to carry has to be written here. The floor arrives as an argument for exactly that reason:
    it is a registration rather than a measurement, so it is read off the row this one replaces and
    written back, which is what keeps the value in one place while the row is rebuilt.

    :param entry: the plan entry
    :param payload: the captured artifact being written
    :param floor: the artifact's declared determinism floor
    :return: the row
    """
    record = payload.get("provenance", {})
    counts = record.get("counts", {})
    row = {
        # Written affirmatively rather than by dropping the key the empty store carries. Both
        # readers of this column - ParityIndexTest and the generated README - ask `baselined` for a
        # boolean, so absence-means-promoted would have made one NPE and the other render every
        # promoted artifact as `**no**`.
        "baselined": True,
        FLOOR_FIELD: floor,
        "file": entry.path,
        "kind": payload.get("kind", ""),
        "promoted_at": record.get("asset_sha") or "",
        "sha256": sha256_text(canonical_json(payload)),
    }
    # The count under the payload member's OWN name, which is the one every writer already spells:
    # a sweep records `rows`, a manifest `files`, a self-captured row `digests` or `values`. So this
    # is one rule rather than a list of count keys that a new kind has to be added to - and it
    # reproduces all fourteen promoted rows exactly. `manifest.tooling-tables` still reads 10 where
    # the gate joins 18, because `logs` is the second payload key and `entries` is the primary one.
    member = store_mod.rows_member(payload.get("kind", ""))
    entries_count = counts.get(member) if member else None
    if entries_count is not None:
        row[ENTRIES_FIELD] = entries_count
    wall = record.get("wall_time_ms")
    if wall is not None:
        row["last_duration_ms"] = wall
    # The headline a reader wants first, lifted out of the payload's own derived summary rather than
    # recomputed here: a sweep's fleet sum is the number every phase of this store reported progress
    # in, and it lived on a terminal alone. Only the sweeps carry one, so the column is absent
    # everywhere else and the README falls back to the entry count for those.
    headline = (payload.get("summary") or {}).get("sum")
    if headline is not None:
        row["sum"] = headline
    return row
