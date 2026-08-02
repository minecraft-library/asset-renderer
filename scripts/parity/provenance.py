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
from parity.norm import LF, read_text, sha256_file, sha256_text

#: Post-consolidation the harness is a directory in this repo, not a sibling repository, so there is
#: exactly one sha and one dirty flag. The spine registers `harness_sha` / `harness_dirty` beside the
#: asset pair, sourced from `git -C ../vanilla-reference-harness`; that path no longer exists and the
#: two values would now be equal by construction, which is I-12's "no value stored twice" broken by
#: identity. One `asset_sha` covers both.
HARNESS_PROPERTIES = "harness/gradle.properties"

REFERENCE_ROOT = "cache/asset-renderer/vanilla/26.1/references"

REFERENCE_SUBTREES = ("blocks", "entities", "items", "glint", "armor", "players")


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


def reference_counts(repo: Path) -> dict:
    root = repo / REFERENCE_ROOT
    if not root.is_dir():
        return {}
    counts = {}
    for name in REFERENCE_SUBTREES:
        subtree = root / name
        if subtree.is_dir():
            counts[name] = sum(1 for _ in subtree.rglob("*.png"))
    return counts


def manifest_digest(path: Path | None) -> str | None:
    """A single digest **of the manifest file**, so a row identifies a reference set in one field
    instead of carrying 2311 lines."""
    return sha256_file(path) if path and path.is_file() else None


def gather(artifact: str, repo: Path, producer: str = "", mode: str | None = None,
           flags: Sequence[str] = (), runs: int = 0, reason: str = "",
           parity_class: str = "", wall_time_ms: int | None = None,
           counts: dict | None = None, root: str | None = None,
           reference_manifest: Path | None = None, now: str | None = None) -> dict:
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
    digest = manifest_digest(reference_manifest)
    if digest:
        record["reference_manifest_digest"] = digest
    found = reference_counts(repo)
    if found:
        record["reference_counts"] = found
    return record


def _flags(flags: Sequence[str]) -> dict:
    """``--flag k=v``, repeatable: every ``-Dasset.*`` and ``-Prefharness*`` in force."""
    out = {}
    for entry in flags:
        name, _, value = entry.partition("=")
        out[name.strip()] = value.strip()
    return out
