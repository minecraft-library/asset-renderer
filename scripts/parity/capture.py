"""``capture-normalize`` and ``capture-index`` - the only way bytes enter the working store.

A TSV goes in and canonical JSON comes out, so the store never holds a TSV and never holds a CRLF.

**The working root is erased before the first artifact of an invocation is written**, with exactly
one exemption. That is single-slot made mechanical: there is no accumulation, no second capture
living beside the first, and nothing to rename. A capture of two artifacts followed by a capture of
one leaves one, and ``compare`` says the other is absent rather than joining a stale copy.

**Once per invocation is what ``_run/OPEN`` carries, and it is load-bearing because a capture step
is one process per artifact.** The build registers a step per row so each can finalize its own
producer, so an erase performed unconditionally by every step leaves only the row that happened to
run last - measured, with both steps reporting success and every stage downstream silent about it,
because ``compare`` and ``promote`` enumerate what the root holds. ``begin`` erases and opens;
a step erases only when no capture is open; ``index`` closes. The erase therefore happens once
however many rows an invocation names.

That ordering is also what keeps the self-captured rows alive. A row whose producer writes it from
inside the test JVM lands *in the working root itself*, so an erase on the far side of that producer
destroys the very file it was about to stamp. The erase is a task ordered before every producer, not
merely before every capture step, which is what puts it on the other side of them.

The exemption is ``_run/expected-diff.json``, because the gate order is ``expect`` ->
``parityCapture`` -> ``parityCompare``: the manifest is written *before* the capture it gates.

``_run/COMPLETE`` is written **last**, after ``_run/_capture.json``. That is what makes a
half-written root detectable, and it is what makes the recorded ``&& diff`` trap - a failed producer
leaving a stale tree that the following diff reports byte-identical - unreachable rather than merely
documented.

**A finished capture is neither joined nor erased.** ``index`` unlinks ``OPEN`` before it writes
``COMPLETE``, so a step reaching a root that carries ``COMPLETE`` and no ``OPEN`` is not part of the
invocation that produced it, and the begin branch above would erase that whole capture to make room
for one row. It refuses instead. Skipping the write would be worse than either: this step's whole
act is the write, and its caller is the next step in a chain rather than a person, so a quiet return
leaves the root a row short and nothing downstream can tell that from a producer that never ran.

The Java-side writer of a self-captured row meets the same marker and *declines*, which is not a
disagreement: there the write is a side effect of a run whose real job is the assertion under it, so
failing would take the assertion down with it. What it must not do is go quiet, and it does not - it
prints, and the build forwards the line to the console.
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

#: Present between ``begin`` and ``index``. A capture step erases only when it is absent, which is
#: what makes the erase once per invocation rather than once per artifact.
OPEN = "OPEN"

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


def begin(root: Path) -> Path:
    """Erase the root and open a capture. The first act of an invocation, before any producer runs.

    Unconditional, and that is what stops a stale artifact joining a completed capture: ``index`` is
    the only writer of ``COMPLETE`` and the only path to it starts here, so a root left open by a
    crashed or hand-run capture is erased rather than accumulated onto.
    """
    wipe(root)
    return _mark_opened(root)


def join_or_begin(root: Path) -> bool:
    """Join the invocation's capture, or begin one when none is open. What a capture step calls.

    Deliberately not named for the marker it tests: ``norm`` is the package's sole writer and its
    scan is a plain substring search, so any identifier whose call site would spell the builtin's
    name followed by a bracket reads as a bypass of it.

    A root carrying ``COMPLETE`` and no ``OPEN`` holds a capture that has already been closed, and
    this step is not one of its rows. Erasing it here is how a hand-run producer's finalizer destroys
    a finished bundle, so the two markers together are the refusal rather than a warning.

    :param root: the working root
    :return: whether it erased
    :raises Refused: if the root holds a capture that has already been closed
    """
    if (root / store_mod.RUN_DIR / OPEN).is_file():
        return False
    if (root / store_mod.RUN_DIR / COMPLETE).is_file():
        raise Refused(
            f"{root} carries _run/{COMPLETE} and no open capture, so this capture step is not part "
            "of the invocation that wrote it; erasing here would destroy a finished capture nobody "
            "asked to replace. Run parityCapture, which erases once before any producer")
    begin(root)
    return True


def _mark_opened(root: Path) -> Path:
    marker = root / store_mod.RUN_DIR / OPEN
    write_text(marker, "")
    return marker


def normalize(artifact: str, source: Path, root: Path, repo: Path, producer: str = "",
              mode: str | None = None, flags: Sequence[str] = (), runs: int | None = None,
              logs: Path | None = None, reference_tree: Path | None = None) -> Path:
    """Read a producer's raw output and write the canonical form at its production-relative path.

    An absent ``runs`` means the artifact's declared floor, which is the value the build has always
    said the toolkit owns because a floor is a property of the artifact rather than of an invocation.
    Defaulting it to zero instead stamped a number ``promote.check`` refuses on every artifact, and
    it refused after the capture had already run - the multi-minute half of the gate.

    ``mode``, ``flags`` and ``reference_tree`` are what make the stamped record say **what produced
    this value** rather than only when. Two captures with identical command lines can disagree,
    because a fork inherits every ``-Dasset.*`` in force from a long-lived daemon; the flags are the
    only place that difference is ever written down. ``reference_tree`` is the ground truth a sweep
    diffed against, named as a directory rather than as a captured manifest so the record carries it
    on every capture and not only on the one that also hashed the tree into the store.
    """
    # Imported at call time rather than at module scope: `promote` reads this module for its root
    # checks, and the floor is the one value that has to travel back the other way.
    from parity import promote as promote_mod
    if runs is None:
        runs = promote_mod.floor_for(artifact)
    target = root / store_mod.path_of(artifact)
    kind, _, name = artifact.partition(".")

    if kind == "sweep":
        payload = _sweep(artifact, name, source)
    elif kind == "manifest":
        # The flow names ARE the artifact's producer task names, so the logs half needs no roster of
        # its own and cannot drift from the row that declares them.
        payload = _manifest(artifact, source, logs,
                            [name for name in producer.split(",") if name])
    elif kind in ("digest", "pin"):
        payload = _self_captured(artifact, source, root, target)
    else:
        raise MissingInput(f"no capture reader for artifact {artifact!r}")

    # A producer's own `_flags` come LAST, so an observed value beats a declared one: only the
    # process that took the measurement knows which version of a dependency the form encodes, and a
    # `--flag` guessed on the build side must not overwrite it.
    payload["provenance"] = provenance_mod.gather(
        artifact, repo, producer=producer, mode=mode, runs=runs,
        flags=[*flags, *payload.pop("_flags", [])],
        counts=payload.pop("_counts", None), root=payload.pop("_root", None),
        reference_tree=reference_tree)
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


def _manifest(artifact: str, source: Path, logs: Path | None = None,
              flows: Sequence[str] = ()) -> dict:
    built = manifest_mod.build(artifact, source)
    payload = manifest_mod.to_artifact(built)
    counts = {"files": len(built.entries)}
    if logs is not None:
        payload["logs"] = manifest_mod.log_digests(logs, flows)
        counts["logs"] = len(payload["logs"])
    payload["_counts"] = counts
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
    payload = read_json(target)
    # Counted here rather than declared by the producer, so the number cannot disagree with what is
    # stored. The other two readers write their own count because they build the payload; this one
    # is handed a finished file, and counting it is the only reading available.
    member = store_mod.rows_member(payload.get("kind", ""))
    entries = payload.get(member) if member else None
    if isinstance(entries, (dict, list)):
        payload["_counts"] = {member: len(entries)}
    return payload


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
    # Closed before it is marked complete, so the next invocation's first step erases rather than
    # accumulating onto a capture that has already been indexed.
    (run / OPEN).unlink(missing_ok=True)
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


def require_unmoved(root: Path) -> None:
    """Refuse a root whose files no longer hash to what its own capture index recorded.

    The compare and the promotion both call it. A root that moved under the index that describes it
    is mixed vintage, and nothing downstream can tell which run produced which byte: the compare
    would report one capture's verdict about bytes another wrote, and the promotion would write them.

    :param root: the working root
    :raises Refused: if the capture never finished, or any recorded file has moved since
    """
    moved = verify_against_index(root)
    if moved:
        raise Refused(
            f"{len(moved)} file(s) under {root} have changed since its capture index was written, "
            f"first {moved[0]}: a root that moved under its own index is mixed vintage, and nothing "
            "downstream can tell which run produced which byte. Re-capture")


def content_digest(root: Path) -> str:
    """The capture index's own digest - one name for the exact tree a finished capture holds.

    Taken over the index rather than over the tree, because the index already carries a digest per
    file and is written once, last but for the marker. It is what lets a later reader say whether a
    report it is holding was written about THIS capture or about the one before it.

    :param root: the working root
    :return: the hex digest of ``_run/_capture.json``
    """
    return sha256_file(root / store_mod.RUN_DIR / CAPTURE_INDEX)
