"""Tree digest manifests: build, verify, and the generated ``sha256sum`` view.

Three incompatible line grammars exist on disk today and a naive ``diff`` between two of them
reports every line changed. They collapse here: ``build`` and ``verify`` read all three, and only
grammar A is ever written.

Sorting is **by path, never by digest**. Sorting by digest means one changed section reorders every
line and a manifest diff stops being a per-file diff - which is a behaviour change for the dump
pair, whose producer sorts by whole line.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

from parity.norm import ComparisonFailed, MissingInput, posix, sha256_file, sha256_text, read_lines

#: A render tree is images. The reference tree additionally admits JSON, because
#: ``glint/atlas_uv.json`` is live ground truth that the corpus's own manifest does not cover.
IMAGE_GLOBS = ("*.png", "*.gif", "*.webp")
REFERENCE_GLOBS = IMAGE_GLOBS + ("*.json",)
JSON_GLOBS = ("*.json",)

#: The only glob tuple that names FILES rather than an extension, because the artifact it serves is
#: defined by which of a producer's outputs are renders rather than by their type.
#:
#: A parity sweep that rescales both sides before diffing writes six PNGs per subject, and only these
#: two are what a renderer produced: ``aligned_*.png`` is an AWT nearest-neighbour resample of them,
#: and ``diff.png`` / ``diff_panel.png`` are built from that. Digesting the derived four would fold a
#: JDK-owned computation - AWT's resampler, and the panel's *fonts* - into the artifact, so a mover
#: would stop naming the renderer. ``walk`` passes each pattern to ``rglob``, so a bare file name
#: matches at any depth under a member and nothing else does.
RAW_RENDER_GLOBS = ("java.png", "vanilla.png")

#: Two stale sidecar classes that inflate every walk. Excluded rather than assumed gone, so a tree
#: that still carries them cannot make a first capture look like a mass deletion.
STALE_SIDECARS = ("*.variant", "*.vertices.tsv")

#: For an artifact whose source is a SHARED parent, the sub-directories that are members.
#:
#: An allowlist, never a denylist. ``cache/visual`` holds thousands of images and only these are this
#: artifact's. The rest are the six per-subject diff-panel trees, the three sub-trees that have
#: manifests of their own, and whatever A/B directories a session left behind - and a denylist would
#: have to be extended for each of those, with forgetting silently baking scratch into a baseline.
#: The diff panels are the sharper reason: a sweep run rewrites them, so admitting them would make
#: this artifact move on every sweep and gate nothing.
#: The two artifacts share ``cache/visual`` as their source and cover DISJOINT file sets under it:
#: ``manifest.visual``'s members are exactly the producer directories no other artifact covers, and
#: neither of ``manifest.player-raw``'s two is among them. So the sweep trees excluded above are not
#: merely ungated - one of them is the other artifact's whole population.
#:
#: ``manifest.visual``'s tuple is the other end of ``visualSweepProducers`` in ``build.gradle.kts``,
#: which registers the task writing each directory. ``walk`` raises for a member that is not there,
#: so dropping a producer from the build is loud; ADDING one is not, which is why a Java test asserts
#: the two name the same set rather than leaving the pairing to a comment.
#:
#: Editing this tuple REDEFINES the artifact rather than measuring it again, and the stored baseline
#: goes on describing the membership it was promoted over until the next promotion replaces it. What
#: an added name reaches is the capture's population: its directory's files enter the capture and no
#: baseline row answers them, so ``compare`` has them to report as ``added`` rather than as movers -
#: a RED a promotion clears and a re-render does not, which is why the promotion's recorded reason is
#: where a membership change gets written down. An added name whose directory no producer has written
#: reaches ``walk``'s refusal above instead, and the capture stops short of a comparison.
SUBTREES = {
    "manifest.visual": ("block-render-3d", "entity-projections", "entity-render-3d",
                        "item-day-cycle", "item-render-2d", "lore-tooltip", "menu-render",
                        "projection-smoke"),
    "manifest.player-raw": ("player-parity-vanilla", "armor-parity-vanilla"),
}

#: What an artifact's own producer writes that the artifact is nonetheless not defined as.
#:
#: ``playerRender`` ships eleven sheet groups and this artifact is the **ten offline** ones. The
#: eleventh, ``account``, renders a live account's skin: it is 8 cells and a composite, it is the
#: whole of the 113-against-104 difference, and a render that depends on a skin service cannot be a
#: byte baseline whatever its digest happens to be today.
NOT_MEMBERS = {
    "manifest.player-sheets": ("account/*", "account.png"),
}

DEFAULT_GLOBS = {
    "manifest.references": REFERENCE_GLOBS,
    "manifest.visual": IMAGE_GLOBS,
    "manifest.player-raw": RAW_RENDER_GLOBS,
    "manifest.player-sheets": IMAGE_GLOBS,
    "manifest.fluid": IMAGE_GLOBS,
    "manifest.portal": IMAGE_GLOBS,
    "manifest.dump.vanilla": JSON_GLOBS,
    "manifest.dump.packs": JSON_GLOBS,
    "manifest.tooling-tables": JSON_GLOBS,
}

_LINE = re.compile(r"^([0-9a-f]{64})\s+\*?(?:\./)?(.+)$")

#: One diagnostics line as ``Diagnostics`` emits it. CONSOLE writes ``[SEV] path - message``; FILE
#: puts an ``Instant`` in front of the same triple. The capture group is the triple, which is why
#: one pattern serves both modes.
_DIAGNOSTIC = re.compile(r"^.*?(\[(?:INFO|WARN|ERROR)\] .*)$")


@dataclass(frozen=True)
class Entry:
    path: str
    sha256: str


@dataclass
class Manifest:
    artifact: str
    root: str
    entries: list[Entry]

    def by_path(self) -> dict[str, str]:
        return {entry.path: entry.sha256 for entry in self.entries}


def globs_for(artifact: str, given: Sequence[str] | None = None) -> tuple[str, ...]:
    if given:
        return tuple(given)
    return DEFAULT_GLOBS.get(artifact, IMAGE_GLOBS)


def walk(source: Path, globs: Sequence[str], exclude: Sequence[str] = (),
         subtrees: Sequence[str] = ()) -> list[Path]:
    """Every file under ``source`` matching a glob, or under each named sub-tree when given.

    Walking the members rather than filtering the parent's walk is what keeps the cost proportional
    to the manifest instead of to whatever else shares the directory.
    """
    excluded = tuple(exclude) + STALE_SIDECARS
    roots = [source / name for name in subtrees] if subtrees else [source]
    for root in roots:
        if not root.is_dir():
            raise MissingInput(
                f"{root} is a declared member of this manifest and is not there; its producer did "
                "not run, and a manifest short one member hashes cleanly minus the missing files")
    found: set[Path] = set()
    for root in roots:
        for pattern in globs:
            found.update(root.rglob(pattern))
    kept = [path for path in found if path.is_file()
            and not any(path.match(pattern) for pattern in excluded)]
    return sorted(kept)


def build(artifact: str, source: Path, globs: Sequence[str] | None = None,
          exclude: Sequence[str] = (), subtrees: Sequence[str] | None = None) -> Manifest:
    if not source.is_dir():
        raise MissingInput(f"no tree to hash at {source}")
    members = SUBTREES.get(artifact, ()) if subtrees is None else subtrees
    excluded = tuple(exclude) + NOT_MEMBERS.get(artifact, ())
    entries = [Entry(path=posix(path, source), sha256=sha256_file(path))
               for path in walk(source, globs_for(artifact, globs), excluded, members)]
    entries.sort(key=lambda entry: entry.path)
    return Manifest(artifact=artifact, root=posix(source), entries=entries)


def to_artifact(manifest: Manifest) -> dict:
    """The stored form. ``root`` rides provenance rather than the path text, so one grammar serves
    a 2311-entry reference tree, a ``cache/visual`` tree and a 14-section dump."""
    return {
        "//": f"parity.{manifest.artifact} · regen: ./gradlew parityCapture -Partifacts={manifest.artifact}",
        "artifact": manifest.artifact,
        "files": [{"path": entry.path, "sha256": entry.sha256} for entry in manifest.entries],
        "format": 1,
        "key": "path",
        "kind": "manifest",
        "provenance": {"counts": {"files": len(manifest.entries)}, "root": manifest.root},
    }


# --- the diagnostics-log projection ----------------------------------------------------------------
# A byte-identical table is not the same claim as an unchanged run: the entity flow once moved an
# INFO line from position 9 to 6 with every emitted table byte-identical. The raw log cannot carry
# that claim - `Diagnostics.flush()` timestamps every FILE line and `ToolingPipeline` timestamps the
# filename - so what is stored is this projection of it.

def normalize_log(path: Path) -> str:
    """The comparable projection of one flow's diagnostics log.

    Keeps the ``(severity, path, message)`` triples in recording order, joined with LF, and drops
    everything else - so the projection is stable against anything a producer prints on the same
    stream, and identical whether the flow ran under CONSOLE or under FILE.

    :param path: the flow's log
    :return: the normalized text the digest is taken over
    """
    kept = []
    for line in read_lines(path):
        match = _DIAGNOSTIC.match(line)
        if match:
            kept.append(match.group(1))
    return "\n".join(kept)


def log_digests(logs: Path, flows: Sequence[str]) -> dict[str, str]:
    """A digest per flow over its normalized log, keyed by flow name.

    Every named flow must have a log. An absent one means that flow did not run into this capture,
    and digesting the seven that did is the tree-that-hashes-cleanly-minus-the-missing-file shape
    the whole store is built against.

    Known and accepted: a log is the last run of *its own* flow, so a capture triggered by one
    hand-run flow digests the other seven from whenever they last ran. Every promotion path runs
    all eight, which is the case this artifact is promoted from.

    :param logs: the directory the producer logs are teed into
    :param flows: the flow names, which are the artifact's own producer task names
    :return: flow name to digest, ordered by name
    """
    out = {}
    for flow in flows:
        path = logs / f"producer-{flow}.log"
        if not path.is_file():
            raise MissingInput(
                f"no diagnostics log for flow {flow!r} at {path}; the logs half of a capture needs "
                "every flow's own run, not whichever ones happened to be asked for")
        out[flow] = sha256_text(normalize_log(path))
    return dict(sorted(out.items()))


def from_artifact(payload: dict) -> Manifest:
    return Manifest(
        artifact=payload.get("artifact", ""),
        root=payload.get("provenance", {}).get("root", ""),
        entries=[Entry(path=row["path"], sha256=row["sha256"]) for row in payload.get("files", [])],
    )


# --- the three line grammars ---------------------------------------------------------------------

def parse_lines(path: Path, artifact: str = "", root: str = "") -> Manifest:
    """Read grammar A (``<hex> *./p``), B (``<hex>  ./p``) or C (``<hex> *p``) indifferently."""
    entries = []
    for number, line in enumerate(read_lines(path), start=1):
        if not line.strip():
            continue
        match = _LINE.match(line)
        if not match:
            raise MissingInput(f"{path}:{number} is not a manifest line: {line!r}")
        entries.append(Entry(path=match.group(2).replace("\\", "/"), sha256=match.group(1)))
    entries.sort(key=lambda entry: entry.path)
    return Manifest(artifact=artifact, root=root, entries=entries)


def export_text(manifest: Manifest) -> str:
    """Grammar A: ``<64-hex>`` SP ``*`` ``<path>``, sorted by path, so ``sha256sum -c`` still works."""
    return "\n".join(f"{entry.sha256} *{entry.path}"
                     for entry in sorted(manifest.entries, key=lambda entry: entry.path))


# --- verify ---------------------------------------------------------------------------------------

@dataclass
class Verdict:
    added: list[str]
    missing: list[str]
    differing: list[str]

    def clean(self) -> bool:
        return not (self.added or self.missing or self.differing)

    def as_dict(self) -> dict:
        return {"added": self.added, "differing": self.differing, "missing": self.missing,
                "totals": {"added": len(self.added), "differing": len(self.differing),
                           "missing": len(self.missing)}}


def compare(base: Manifest, current: Manifest) -> Verdict:
    left, right = base.by_path(), current.by_path()
    return Verdict(
        added=sorted(set(right) - set(left)),
        missing=sorted(set(left) - set(right)),
        differing=sorted(path for path in set(left) & set(right) if left[path] != right[path]),
    )


def raise_on(verdict: Verdict) -> None:
    if verdict.clean():
        return
    raise ComparisonFailed(
        f"manifest differs: {len(verdict.added)} added, {len(verdict.missing)} missing, "
        f"{len(verdict.differing)} differing"
    )
