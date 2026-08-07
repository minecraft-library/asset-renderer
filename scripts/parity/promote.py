"""``promote-plan`` and ``promote-apply`` - the one act that may write production.

Promotion is deliberate, separate and recorded, never a side effect of a measurement. Every refusal
below is a mechanism for an invariant rather than a policy someone has to remember:

- no ``--reason``, no promotion;
- ``determinism_runs`` below the artifact's floor, no promotion - a value a second independent run
  does not reproduce is not a baseline whatever anyone declares about it;
- ``failed > 0``, no promotion without an explicit ``--allow-partial``, because a partial sweep
  leaves a tree that hashes cleanly minus the missing files;
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
from parity import store as store_mod
from parity.norm import MissingInput, Refused, read_json, sha256_text, canonical_json, write_json

#: Two runs for a render tree; **five** for anything exposed to the Map.copyOf / Set.copyOf
#: class-init salt, because that flap is intermittent and an oracle can pass twice and fail the
#: third time.
FLOORS = {
    "manifest.dump.vanilla": 5,
    "manifest.dump.packs": 5,
}

DEFAULT_FLOOR = 2

CLASSES = ("neutral", "shaped", "moving")


def floor_for(artifact: str) -> int:
    return FLOORS.get(artifact, DEFAULT_FLOOR)


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


def check(root: Path, entries: Sequence[Entry], reason: str, allow_partial: bool = False,
          bootstrap: bool = False, allow_dirty: bool = False) -> None:
    """Every refusal, in one place, before a single production byte is written."""
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
        needed = floor_for(entry.artifact)
        if runs < needed:
            raise Refused(
                f"{entry.artifact} records determinism_runs={runs}, below its floor of {needed}: "
                "a value a second independent run does not reproduce is not a baseline")
        counts = record.get("counts") or {}
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
        if dirty is not False and not allow_dirty:
            raise Refused(
                f"{entry.artifact} records asset_dirty={dirty}: a baseline whose capture cannot be "
                "shown to have run on a committed tree is not re-derivable from any commit, and no "
                "later reading recovers that. Commit the change, re-capture, and promote from the "
                "committed tree. Pass --allow-dirty to record the exception in provenance")


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
        index["artifacts"][entry.artifact] = _index_row(entry, payload, target)
        written.append(entry.artifact)

    index.setdefault("//", "parity.report.oracle-index · regen: ./gradlew parityPromote")
    index.setdefault("artifact", "report.oracle-index")
    index.setdefault("format", 1)
    index.setdefault("key", "artifact")
    index.setdefault("kind", "index")
    write_json(target.path("report.oracle-index"), index)
    return {"promoted": written, "reason": reason, "parity_class": parity_class}


def _index_row(entry: Entry, payload: dict, target: store_mod.WritableStore) -> dict:
    record = payload.get("provenance", {})
    counts = record.get("counts", {})
    row = {
        # Written affirmatively rather than by dropping the key the empty store carries. Both
        # readers of this column - ParityIndexTest and the generated README - ask `baselined` for a
        # boolean, so absence-means-promoted would have made one NPE and the other render every
        # promoted artifact as `**no**`.
        "baselined": True,
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
        row["entries"] = entries_count
    wall = record.get("wall_time_ms")
    if wall is not None:
        row["last_duration_ms"] = wall
    return row
