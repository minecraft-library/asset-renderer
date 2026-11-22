"""The two roots, the artifact-id to path mapping, and the read-only guard on production.

The roots are written once. This module holds the package's only literal spelling of each, so a
later source-set move is one line here (and one in ``build.gradle.kts``, and one in
``ParityStore.java``).

A measurement never writes production. That is a *view* rather than a rule anyone has to remember:
``production()`` hands back a store whose ``write`` raises, and only ``promote-apply`` constructs
the writable one.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from parity.norm import MissingInput, Refused, read_json, write_json

#: The tracked production store - the last known value of every artifact. One literal, one place.
PRODUCTION = "src/test/resources/lib/minecraft/renderer/parity"

#: The gitignored working root a measurement writes into. Single-slot and self-overwriting.
WORKING = "cache/parity/current"

#: The one reserved directory inside a working root. Everything that is not an artifact lives here,
#: and ``parityPromote`` copies the complement and ignores this.
RUN_DIR = "_run"

#: The kind prefix of an artifact id maps to a directory; the rest of the id becomes the file stem.
_KIND_DIR = {
    "sweep": "sweeps",
    "manifest": "manifests",
    "pin": "pins",
}

#: Two artifacts sit at the store root under names that predate the id grammar and are read by name.
_ROOT_FILES = {
    "report.oracle-index": "index.json",
    "roster.blindness-rules": "blindness.json",
}

#: Which member of an envelope carries its entries, by the envelope's own ``kind``. Keyed by kind
#: rather than by the id's prefix because the two key spaces are different (``pin`` against
#: ``pin-set``), and it lives here rather than in ``compare`` because it is a structural fact about
#: the format that three modules need: ``compare`` joins on it, ``capture`` counts it, and
#: ``promote`` reads that count into the index's display column.
#:
#: One of the four members is an OBJECT keyed by the entry's own name; three are arrays of rows.
ROWS_MEMBER = {
    "sweep-table": "rows",
    "manifest": "files",
    "pin-set": "values",
    "blindness-roster": "rules",
}


def rows_member(kind: str) -> str | None:
    """The payload member an envelope of this kind carries its entries in, or None if unknown."""
    return ROWS_MEMBER.get(kind)


def repo_root() -> Path:
    """The repo containing ``parity/scripts/parity/``, so the working directory never matters."""
    return Path(__file__).resolve().parents[3]


def artifact_files(root: Path) -> list[tuple[str, bool]]:
    """Every artifact file under a root, each with whether a capture step STAMPED it.

    The two are different sets and conflating them is a live hazard. A self-capturing producer
    writes its file whenever it runs, so a capture naming one pin leaves the suite's other pins in
    the root as well - written by the same ``parityPinTest`` invocation, named by no capture step,
    and therefore carrying no provenance and still carrying the private ``_flags`` key that
    ``capture-normalize`` pops. Enumerating the root by filename alone credits this run with
    capturing them: measured, where a bare ``promote-plan`` on a pins root planned to **replace** a
    promoted digest set with the unstamped by-product, differing from it by ``_flags`` alone.

    Provenance is the mark, because ``capture-normalize`` writes one on every artifact it stamps and
    a producer writing its own file writes none. It is evidence of a capture step rather than proof
    of one: ``manifest build`` writes a manifest into the root straight from ``cli``, carrying the
    counts-and-root object ``manifest.to_artifact`` builds, and that reads as stamped here. Naming an
    unstamped artifact explicitly still refuses in ``promote.check`` - an enumeration skips what was
    not captured, a request for it does not.
    """
    found = []
    for path in sorted(root.rglob("*.json")):
        if RUN_DIR in path.parts:
            continue
        try:
            payload = read_json(path)
        except ValueError:
            continue
        if isinstance(payload, dict) and payload.get("artifact"):
            found.append((payload["artifact"], bool(payload.get("provenance"))))
    return found


def path_of(artifact_id: str) -> str:
    """Map an artifact id to its store-relative path.

    ``sweep.entity`` -> ``sweeps/entity.json``; ``manifest.dump.vanilla`` ->
    ``manifests/dump-vanilla.json``. Dots separate the kind from the name; inside the name they
    become hyphens, because an artifact id is also a file stem and has to stay filesystem-safe.
    """
    if artifact_id in _ROOT_FILES:
        return _ROOT_FILES[artifact_id]
    kind, _, rest = artifact_id.partition(".")
    if not rest or kind not in _KIND_DIR:
        raise MissingInput(f"no store path for artifact id {artifact_id!r}")
    return f"{_KIND_DIR[kind]}/{rest.replace('.', '-')}.json"


def resolve_working(root: str | None = None, base: Path | None = None) -> Path:
    """Resolve and guard a working root.

    The guard is a path-prefix rule rather than a gitignore test: a root that must be **relative**
    and **under ``cache/``** always dies with a ``cache/`` clean and can never be committed, which
    is what makes long-lived temp output unrepresentable rather than merely discouraged. What is
    given up deliberately is a redirect off the working tree - ``%TEMP%``, another drive, any
    absolute path - and a research pack that wanted one gets ``cache/parity/<name>`` instead.

    **Under ``cache/`` says where the root lands, not how it is spelled.** A capture opens by
    erasing every child of this root, so the root is the whole of what a capture may destroy. Two
    holds, in order: a parent reference is refused wherever it stands, and what is left is resolved
    through every link on disk and has to land strictly inside the repository's own ``cache``
    directory - see :func:`_inside_cache`. The second is what refuses ``cache`` itself, whose
    children are every cache the repository keeps rather than one capture. The default root takes
    it too, since a linked ``cache`` moves the default like any other.

    :param root: the root as it was given, or None for the default
    :param base: the repository to resolve against, or None for the one holding this package
    :return: the root, joined onto the repository as it was spelled
    :raises Refused: if the root is absolute, is not spelled under ``cache/``, carries a parent
        reference, or lands anywhere but strictly inside the repository's ``cache`` directory
    """
    home = base if base is not None else repo_root()
    if root is None:
        return _inside_cache(home, home / WORKING, WORKING)
    candidate = Path(root)
    if candidate.is_absolute():
        raise Refused(f"working root must be relative and under cache/, got absolute {root!r}")
    parts = candidate.parts
    if not parts or parts[0] != "cache":
        raise Refused(f"working root must be under cache/, got {root!r}")
    if ".." in parts:
        raise Refused(f"working root must not carry a parent reference, got {root!r}")
    return _inside_cache(home, home / candidate, root)


def _inside_cache(home: Path, target: Path, given: str) -> Path:
    """Answer ``target``, refused unless it lands strictly inside the repository's ``cache``.

    Both sides are resolved, so a link anywhere along the root is followed to where the erase would
    really happen, and a repository that is itself reached through a link still compares equal to
    itself. ``cache`` is joined on by name rather than resolved: the directory an erase may reach is
    the one in the checkout, and a ``cache`` that is a link resolves to somewhere that directory is
    not.

    :param home: the repository the root is resolved against
    :param target: the root joined onto the repository
    :param given: the root as it was spelled, for the refusal
    :return: ``target``, as it was handed in
    :raises Refused: if it does not resolve strictly inside ``<home>/cache``
    """
    try:
        cache = home.resolve() / "cache"
        landed = target.resolve()
    except (OSError, RuntimeError) as failure:
        raise Refused(f"working root {given!r} cannot be resolved: {failure}") from failure
    if cache not in landed.parents:
        raise Refused(
            f"working root must land strictly inside {cache}, and {given!r} resolves to {landed}; "
            "a capture erases its root, so cache/ itself and a root a link carries out of it are "
            "both refused")
    return target


def resolve_store(store: str | None = None, base: Path | None = None) -> Path:
    home = base if base is not None else repo_root()
    return home / PRODUCTION if store is None else Path(store)


class ReadOnlyStore:
    """A store that can be read and never written. The view production is always opened through."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def path(self, artifact_id: str) -> Path:
        return self.root / path_of(artifact_id)

    def exists(self, artifact_id: str) -> bool:
        return self.path(artifact_id).is_file()

    def read(self, artifact_id: str) -> Any:
        target = self.path(artifact_id)
        if not target.is_file():
            raise MissingInput(f"{artifact_id} is absent from {self.root}")
        return read_json(target)

    def index(self) -> dict:
        """``index.json``, or an empty envelope when the store has not been promoted into yet."""
        target = self.path("report.oracle-index")
        if not target.is_file():
            return {"artifact": "report.oracle-index", "artifacts": {}, "format": 1,
                    "key": "artifact", "kind": "index"}
        return read_json(target)

    def write(self, artifact_id: str, obj: Any) -> Path:
        raise Refused(
            f"refusing to write {artifact_id} into the production store; "
            "only parityPromote writes a baseline"
        )

    def contains(self, path: Path) -> bool:
        """Whether a path lies inside this store - the check ``--out`` is refused by."""
        try:
            path.resolve().relative_to(self.root.resolve())
        except ValueError:
            return False
        return True


class WritableStore(ReadOnlyStore):
    """Constructed by ``promote-apply`` alone, and only after a plan validates."""

    def write(self, artifact_id: str, obj: Any) -> Path:
        return write_json(self.path(artifact_id), obj)


def production(store: str | None = None, base: Path | None = None) -> ReadOnlyStore:
    return ReadOnlyStore(resolve_store(store, base))


def working(root: str | None = None, base: Path | None = None) -> WritableStore:
    """The working root is writable by every command - it is output, never a baseline."""
    return WritableStore(resolve_working(root, base))
