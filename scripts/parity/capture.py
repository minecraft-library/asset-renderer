"""``capture-normalize`` and ``capture-index`` - the only way bytes enter the working store.

A TSV goes in and canonical JSON comes out, so the store never holds a TSV and never holds a CRLF.

**The working root is erased before the first artifact of an invocation is written**, with exactly
one exemption. That is single-slot made mechanical: there is no accumulation, no second capture
living beside the first, and nothing to rename. A capture of two artifacts followed by a capture of
one leaves one, and ``compare`` says the other is absent rather than joining a stale copy.

The exemption is ``_run/expected-diff.json``, because the gate order is ``expect`` ->
``parityCapture`` -> ``parityCompare``: the manifest is written *before* the capture it gates.

``_run/COMPLETE`` is written **last**, after ``_run/_capture.json``. That is what makes a
half-written root detectable, and it is what makes the recorded ``&& diff`` trap - a failed producer
leaving a stale tree that the following diff reports byte-identical - unreachable rather than merely
documented.
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Sequence

from parity import manifest as manifest_mod
from parity import provenance as provenance_mod
from parity import store as store_mod
from parity import sweep as sweep_mod
from parity.norm import MissingInput, Refused, read_json, sha256_file, write_json, write_text

#: Survives the wipe. One name, stated at the wipe rather than kept as a list that goes stale.
EXEMPT = "expected-diff.json"

COMPLETE = "COMPLETE"

CAPTURE_INDEX = "_capture.json"


def wipe(root: Path) -> None:
    """Erase everything under the root except the one exempt file."""
    if not root.exists():
        root.mkdir(parents=True, exist_ok=True)
        return
    keep = root / store_mod.RUN_DIR / EXEMPT
    kept = keep.read_bytes() if keep.is_file() else None
    for child in sorted(root.iterdir()):
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()
    if kept is not None:
        write_text(keep, kept.decode("utf-8"))


def normalize(artifact: str, source: Path, root: Path, repo: Path, producer: str = "",
              mode: str | None = None, flags: Sequence[str] = (), runs: int = 0) -> Path:
    """Read a producer's raw output and write the canonical form at its production-relative path."""
    target = root / store_mod.path_of(artifact)
    kind, _, name = artifact.partition(".")

    if kind == "sweep":
        payload = _sweep(artifact, name, source)
    elif kind == "manifest":
        payload = _manifest(artifact, source)
    elif kind in ("digest", "pin"):
        payload = _self_captured(artifact, source, root, target)
    else:
        raise MissingInput(f"no capture reader for artifact {artifact!r}")

    payload["provenance"] = provenance_mod.gather(
        artifact, repo, producer=producer, mode=mode, flags=flags, runs=runs,
        counts=payload.pop("_counts", None), root=payload.pop("_root", None))
    write_json(target, payload)
    return target


def _sweep(artifact: str, name: str, source: Path) -> dict:
    found = sweep_mod.discover(source)
    if name not in found:
        raise MissingInput(f"no table for {artifact} under {source}")
    table = sweep_mod.read_table(found[name], name)
    rows = sweep_mod.to_rows(table)
    return {
        "//": f"parity.{artifact} · regen: ./gradlew parityCapture -Partifacts={artifact}",
        "artifact": artifact,
        "format": 1,
        "key": "subject",
        "kind": "sweep-table",
        "rows": rows,
        "_counts": {"failed": table.failed(), "rows": len(rows)},
    }


def _manifest(artifact: str, source: Path) -> dict:
    built = manifest_mod.build(artifact, source)
    payload = manifest_mod.to_artifact(built)
    payload["_counts"] = {"files": len(built.entries)}
    payload["_root"] = built.root
    payload.pop("provenance", None)
    return payload


def _self_captured(artifact: str, source: Path, root: Path, target: Path) -> dict:
    """A row whose producer writes it from inside the test JVM.

    ``--source`` naming the working root itself means self-captured: the already-canonical file is
    validated where it stands and stamped, and its **absence is a failure** rather than an empty
    capture - which is the backstop for a filtered test run, since ``--tests`` is a command-line
    option rather than a property and no ``onlyIf`` can see it.
    """
    if source.resolve() != root.resolve():
        raise MissingInput(
            f"{artifact} is self-captured; --source must name the working root, got {source}")
    if not target.is_file():
        raise MissingInput(
            f"{artifact} was not written by its producer at {target}; a filtered test run that "
            "never reached it captures nothing rather than promoting a stale value")
    return read_json(target)


def index(root: Path, producers: Sequence[str] = (), flags: Sequence[str] = (),
          runs: int = 0, timestamp: str | None = None) -> Path:
    """Write ``_run/_capture.json``, then ``_run/COMPLETE`` last."""
    run = root / store_mod.RUN_DIR
    files = []
    for path in sorted(root.rglob("*.json")):
        if store_mod.RUN_DIR in path.parts:
            continue
        files.append({"path": path.relative_to(root).as_posix(), "sha256": sha256_file(path)})
    payload = {
        "artifacts": [read_json(root / entry["path"]).get("artifact", "") for entry in files],
        "determinism_runs": runs,
        "files": files,
        "flags": list(flags),
        "format": 1,
        "kind": "capture-index",
        "producers": list(producers),
        "timestamp": timestamp or "",
    }
    write_json(run / CAPTURE_INDEX, payload)
    write_text(run / COMPLETE, "")  # last, always
    return run / COMPLETE


def require_complete(root: Path) -> dict:
    """``compare`` and ``promote-apply`` both refuse a root that never finished."""
    marker = root / store_mod.RUN_DIR / COMPLETE
    if not marker.is_file():
        raise Refused(
            f"{root} carries no _run/COMPLETE: the capture did not finish, so anything read from "
            "it would be a stale tree reported as agreement")
    return read_json(root / store_mod.RUN_DIR / CAPTURE_INDEX)


def verify_against_index(root: Path) -> list[str]:
    """Re-hash every file the index recorded; a disagreement means the root moved since."""
    recorded = require_complete(root)
    moved = []
    for entry in recorded.get("files", []):
        path = root / entry["path"]
        if not path.is_file() or sha256_file(path) != entry["sha256"]:
            moved.append(entry["path"])
    return moved
