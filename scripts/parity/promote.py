"""``promote-plan`` and ``promote-apply`` - the one act that may write production.

Promotion is deliberate, separate and recorded, never a side effect of a measurement. Every refusal
below is a mechanism for an invariant rather than a policy someone has to remember:

- no ``--reason``, no promotion (I-8);
- ``determinism_runs`` below the artifact's floor, no promotion - a value a second independent run
  does not reproduce is not a baseline whatever anyone declares about it (I-13);
- ``failed > 0``, no promotion without an explicit ``--allow-partial``, because a partial sweep
  leaves a tree that hashes cleanly minus the missing files (I-20);
- a root whose digests disagree with its own capture index, no promotion, so a plan cannot be
  applied to a root that has since been re-captured.
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
    found = []
    for path in sorted(root.rglob("*.json")):
        if store_mod.RUN_DIR in path.parts:
            continue
        payload = read_json(path)
        if isinstance(payload, dict) and payload.get("artifact"):
            found.append(payload["artifact"])
    return found


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
          bootstrap: bool = False) -> None:
    """Every refusal, in one place, before a single production byte is written."""
    if not reason.strip():
        raise Refused("promote-apply requires --reason: a promotion is a recorded act (I-8)")

    moved = capture_mod.verify_against_index(root)
    if moved:
        raise Refused(
            f"the working root has changed since its capture index was written ({len(moved)} file(s), "
            f"first {moved[0]}); re-capture rather than promoting a root that moved under the plan")

    for entry in entries:
        payload = read_json(root / entry.path)
        record = payload.get("provenance") or {}
        if not record:
            raise Refused(f"{entry.artifact} carries no provenance object; a promoted artifact "
                          "without one is unrepresentable (I-8)")
        runs = record.get("determinism_runs") or 0
        needed = floor_for(entry.artifact)
        if runs < needed:
            raise Refused(
                f"{entry.artifact} records determinism_runs={runs}, below its floor of {needed}: "
                "a value a second independent run does not reproduce is not a baseline (I-13)")
        counts = record.get("counts") or {}
        if counts.get("failed", 0) and not allow_partial:
            raise Refused(
                f"{entry.artifact} records failed={counts['failed']}; a partial run leaves a tree "
                "that hashes cleanly minus the missing files (I-20). Pass --allow-partial to record "
                "the exception in provenance")
        if entry.action == "new" and not bootstrap:
            raise Refused(
                f"{entry.artifact} has no baseline to replace; the first promotion of an artifact "
                "is --bootstrap, and it is refused for anything below its determinism floor")


def apply(root: Path, target: store_mod.WritableStore, entries: Sequence[Entry], reason: str,
          parity_class: str = "moving", allow_partial: bool = False,
          population_changed: bool = False) -> dict:
    """Copy through ``norm``, so a hand-edited CRLF capture is normalized on the way in.

    That is why the copy is Python rather than a Kotlin ``copy { }``: a byte copy would carry a CRLF
    straight into the store and I-1 would hold only by luck.
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
        "file": entry.path,
        "kind": payload.get("kind", ""),
        "promoted_at": record.get("asset_sha") or "",
        "sha256": sha256_text(canonical_json(payload)),
    }
    entries_count = counts.get("rows") or counts.get("files")
    if entries_count is not None:
        row["entries"] = entries_count
    wall = record.get("wall_time_ms")
    if wall is not None:
        row["last_duration_ms"] = wall
    return row
