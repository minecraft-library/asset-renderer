"""``report.run-provenance`` - the record that makes a baseline self-identifying (I-21).

Everything here is **read**, never asked for. A field a caller could mistype is a field that will be
mistyped, and the whole point is that a promoted value can say what produced it.

The record lands **inside** the artifact file it describes, at ``<artifact-file>#/provenance``. There
is no ``provenance/`` directory and no sidecar on either side: a value that must accompany an
artifact and can be separated from it is a value that will be separated from it.
"""

from __future__ import annotations

import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Sequence

from parity import VERSION
from parity import manifest as manifest_mod
from parity.norm import LF, canonical_json, read_text, sha256_text

#: Post-consolidation the harness is a directory in this repo, not a sibling repository, so there is
#: exactly one sha and one dirty flag. The spine registers `harness_sha` / `harness_dirty` beside the
#: asset pair, sourced from `git -C ../vanilla-reference-harness`; that path no longer exists and the
#: two values would now be equal by construction, which is I-12's "no value stored twice" broken by
#: identity. One `asset_sha` covers both.
HARNESS_PROPERTIES = "harness/gradle.properties"

#: The reference tree, with the version segment left to be filled from the harness's own
#: ``minecraft_version`` taken to major.minor - the same derivation the build applies to the same
#: property. A literal here survives an MC bump by naming a directory that no longer exists, and an
#: absent tree counts nothing, so the whole per-sub-tree evidence would leave every record with
#: nothing at all saying why.
REFERENCE_ROOT_TEMPLATE = "cache/asset-renderer/vanilla/{version}/references"

REFERENCE_SUBTREES = ("blocks", "entities", "items", "glint", "armor", "players")

#: The artifact whose stored form the reference digest below IS. Named rather than spelled inline,
#: because the two have to be the same manifest for the digest to identify anything: the globs, the
#: exclusions and the entry grammar all come off this id.
REFERENCES_ARTIFACT = "manifest.references"

#: Every key a record can carry, and whether ``gather`` writes it on every call. The suite asserts a
#: real record against this rather than against a list of its own, so all three ways the two can
#: part company fail instead of going unnoticed: a key added to ``gather`` and not here, a key
#: registered here and written by nothing, and a column disagreeing with what a call supplying no
#: argument writes - the last being how an unconditional key quietly becomes a conditional one. That
#: is the mechanism that was missing while three fields sat in the signature and reached no caller.
KEYS = {
    "artifact": True,
    "asset_dirty": True,
    "asset_sha": True,
    "counts": False,
    "determinism_runs": True,
    "flags": True,
    "mc_version": True,
    "mode": False,
    "parity_class": False,
    "producer": True,
    "reason": False,
    "reference_counts": False,
    "reference_manifest_digest": False,
    "root": False,
    "timestamp": True,
    "tool_version": True,
    "wall_time_ms": False,
}


def _git(repo: Path, *args: str) -> str | None:
    """Run a git command, degrading to None with a warning rather than failing the capture."""
    try:
        done = subprocess.run(["git", "-C", str(repo), *args],
                              capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        sys.stderr.write(f"provenance: git unavailable ({error}); recording null\n")
        return None
    if done.returncode != 0:
        sys.stderr.write(f"provenance: git {' '.join(args)} failed; recording null\n")
        return None
    return done.stdout.strip()


def asset_state(repo: Path) -> dict:
    head = _git(repo, "rev-parse", "HEAD")
    status = _git(repo, "status", "--porcelain")
    return {"asset_dirty": bool(status) if status is not None else None, "asset_sha": head}


def dirty_digest(repo: Path) -> str | None:
    """A digest over the working tree's uncommitted diff, so a TREE STATE has one name.

    ``asset_sha`` names a commit and ``asset_dirty`` says only whether something is uncommitted, so
    the pair cannot distinguish two different edits on top of one commit - which is exactly what the
    pre-commit gate has to distinguish, since an already-gated tree that has been edited since must
    re-arm. This is that third value.

    LF-normalized before digesting, for I-1's reason and one more: ``git diff`` renders the worktree's
    own line endings, so on Windows an unchanged file could otherwise digest differently than it did
    on the run that gated it.

    A clean tree digests the empty string rather than answering ``None``, because "nothing is
    uncommitted" is an answer and ``None`` is reserved for "git could not be asked".

    It digests ``status --porcelain`` alongside the diff, because the reach resolution this guards
    reads the untracked-but-unignored set too (``_changed_from_git``) and ``diff HEAD`` cannot see a
    file that has never been added. What remains outside it is an EDIT to a never-added file, and
    that is not a hole: such a file cannot be in the commit, and adding it puts it in ``diff HEAD``.

    :param repo: the repository root
    :return: the digest, or None when git is unavailable
    """
    diff = _git(repo, "diff", "HEAD")
    status = _git(repo, "status", "--porcelain")
    if diff is None or status is None:
        return None
    body = f"{status}\n{diff}".replace("\r\n", LF).replace("\r", LF)
    return sha256_text(body)


def mc_version(repo: Path) -> str | None:
    """``minecraft_version`` out of the harness's own properties, so nothing restates it."""
    path = repo / HARNESS_PROPERTIES
    if not path.is_file():
        sys.stderr.write(f"provenance: no {HARNESS_PROPERTIES}; recording null mc_version\n")
        return None
    for line in read_text(path).splitlines():
        name, _, value = line.partition("=")
        if name.strip() == "minecraft_version":
            return value.strip()
    return None


def reference_root(repo: Path) -> Path | None:
    """The reference tree this repo's harness version names.

    Derived rather than written down, for the reason ``mc_version`` is read rather than restated:
    the version lives in one file and every path built from it has to move when it does.

    :param repo: the repository root
    :return: the tree's path, or None when the harness version cannot be read
    """
    version = mc_version(repo)
    if not version:
        return None
    return repo / REFERENCE_ROOT_TEMPLATE.format(version=".".join(version.split(".")[:2]))


def reference_counts(repo: Path) -> dict:
    """How many references each sub-tree holds - the per-sub-tree evidence a baseline carries.

    An absent tree answers with nothing and **says so on stderr**. Silence there is the failure this
    is guarded against: a record missing its counts and a record whose counts are all zero read the
    same downstream, and neither says the tree was never found.

    :param repo: the repository root
    :return: the PNG count per sub-tree; empty when the tree cannot be found
    """
    root = reference_root(repo)
    if root is None:
        sys.stderr.write("provenance: no harness minecraft_version, so no reference tree can be "
                         "named; recording no reference_counts\n")
        return {}
    if not root.is_dir():
        sys.stderr.write(f"provenance: no reference tree at {root}; recording no reference_counts, "
                         "so this record cannot say which references it measured against\n")
        return {}
    counts = {}
    for name in REFERENCE_SUBTREES:
        subtree = root / name
        if subtree.is_dir():
            counts[name] = sum(1 for _ in subtree.rglob("*.png"))
    return counts


def reference_manifest_digest(root: Path | None) -> str | None:
    """One digest naming the reference SET a number was measured against, instead of 2311 lines.

    Derived from the tree rather than read off a captured manifest file, and both halves of that are
    load-bearing. A routine gate captures no reference manifest at all - a renderer change's plan
    selects the sweeps and not the row that hashes the tree - so a field taken off that file lands
    only on a harness-triggered capture and ties nothing on the change class that produces every
    other number. And the file's own bytes carry a timestamp, so two sweeps measured against one
    tree would name it with two different digests, which is the one thing this field must not do.

    ``provenance`` is out of the digested bytes and the manifest's paths are relative, so what is
    left is exactly the identity of the stored ``manifest.references`` payload: a sweep's digest and
    the store's copy of that row agree when and only when they describe the same reference set.

    An unnamed tree is None with no line, because nothing was asked for. A named one that is not
    there is None **and a warning**: the caller asked to tie this record to a reference set and the
    tie is not being made.

    :param root: the reference tree, or None when the caller names none
    :return: the digest of its manifest, or None
    """
    if root is None:
        return None
    if not root.is_dir():
        sys.stderr.write(f"provenance: no reference tree at {root}; recording no "
                         "reference_manifest_digest, so nothing ties this record to a reference "
                         "set\n")
        return None
    stored = manifest_mod.to_artifact(manifest_mod.build(REFERENCES_ARTIFACT, root))
    return sha256_text(canonical_json(
        {name: value for name, value in stored.items() if name != "provenance"}))


def gather(artifact: str, repo: Path, producer: str = "", mode: str | None = None,
           flags: Sequence[str] = (), runs: int = 0, reason: str = "",
           parity_class: str = "", wall_time_ms: int | None = None,
           counts: dict | None = None, root: str | None = None,
           reference_tree: Path | None = None, now: str | None = None) -> dict:
    record = {
        "artifact": artifact,
        "determinism_runs": runs,
        "flags": _flags(flags),
        "mc_version": mc_version(repo),
        "producer": producer,
        "timestamp": now or datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "tool_version": VERSION,
    }
    record.update(asset_state(repo))
    if mode:
        record["mode"] = mode
    if reason:
        record["reason"] = reason
    if parity_class:
        record["parity_class"] = parity_class
    if wall_time_ms is not None:
        record["wall_time_ms"] = wall_time_ms
    if counts:
        record["counts"] = counts
    if root:
        record["root"] = root
    digest = reference_manifest_digest(reference_tree)
    if digest:
        record["reference_manifest_digest"] = digest
    found = reference_counts(repo)
    if found:
        record["reference_counts"] = found
    return record


def _flags(flags: Sequence[str]) -> dict:
    """Fold ``k=v`` entries into an object, splitting each at its first ``=`` and stripping both
    halves, with the last spelling of a name winning.

    Two sources arrive here and the record does not tell them apart. The build sends one
    ``--flag`` per ``-Dasset.*`` its own daemon holds - read off ``System.getProperties()`` where
    the capture step is registered, which is the same table under the same name test that
    ``forwardAssetProperties`` walks to put those properties on a forked producer. A producer's
    payload then contributes its own ``_flags`` after them, which is how a row records the version
    of a library it emitted through.

    The daemon is the point of collecting them at all: a fork inherits its ``asset.*`` from a
    long-lived one rather than from the command line, so two captures typed identically can
    disagree and this is the only place that difference is written down.

    **It is not an inventory of what a producer carried**, and the two parity roots are where the
    record and the fork part company. The build puts ``asset.parity.root`` and
    ``asset.parity.references`` on every ``Test`` and every ``JavaExec`` itself, after and outside
    the forwarder and from values it resolves for itself; both names begin ``asset.``, so what lands
    here under them is whatever the daemon holds, which is a different question. Neither is a
    reading of the other. The working root takes ``-PparityRoot`` first, a
    ``-Dasset.parity.root`` second and ``cache/parity/current`` third, and only the second of those
    is a system property: under the ``-D`` alone the fork got the value recorded here, under a
    ``-P`` beside it the fork got the ``-P`` value and this holds the one it did not get, and under
    no ``-D`` this holds nothing whatever the fork got. The reference tree is derived from the
    harness's own ``minecraft_version`` and reads no property at all, so an
    ``-Dasset.parity.references`` is recorded here and reaches no fork under any invocation.

    A third case is ``manifest.references``, whose producer is an ``Exec`` of a second Gradle build
    under ``--no-daemon`` that this forwarder never touches: that row records the daemon's set
    beside a producer carrying none of it.

    A ``-Prefharness*`` is outside all of this, being a Gradle project property rather than a system
    one - it reaches no fork and is collected nowhere. The mode a run selected is what a record says
    about that. Read the object as the debug properties this invocation's daemon held, which is what
    two identically typed captures actually part company over.
    """
    out = {}
    for entry in flags:
        name, _, value = entry.partition("=")
        out[name.strip()] = value.strip()
    return out
