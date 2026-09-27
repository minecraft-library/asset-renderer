"""Class-level parity reach, derived from the compiled constant pool.

Answers which parity artifacts a changed Java type can move, by walking the reference graph the
compiler emitted rather than the directory the file sits in.

The constant pool is the substrate because an import is not evidence. The javadoc convention
requires importing a ``{@link}`` target rather than inlining an FQN, so documenting a type creates an
import that is not a dependency; measured over this tree, 104 import edges are javadoc-only and 360
real edges carry no import at all, same-package and nested references needing none. Neither error
appears in a class file: javadoc never reaches one, and a same-package call puts a ``CONSTANT_Class``
in the pool exactly as a cross-package one does.

The roots are producers rather than renderers. A renderer is entered by a producer, and ninety types
- every loader, index builder and deserialiser - are referenced by no renderer at all, so rooting at
one leaves them unreachable. Rooting at the producer walks test to renderer to kit, which is why an
edge INTO a renderer is load-bearing here and why the renderer-to-renderer edges that survive are
real: an entity draws a carried block through ``BlockRenderer``.

This module computes and returns. It prints nothing and writes nothing - ``cli`` prints and ``norm``
writes.
"""

from __future__ import annotations

import hashlib
import re
import struct
from collections import defaultdict, deque
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

from parity import declarations as declarations_mod
from parity.blindness import Rule, matches

from .norm import MissingInput

#: The package every edge this module cares about lives under, in binary (slash) form.
PACKAGE = "lib/minecraft/renderer"

#: Source roots holding types that can carry a parity reach, relative to the repo root.
#:
#: The generators are here for the same reason the renderer's test and visual trees are: they are a
#: producer, and what a producer reaches is what its artifact can be moved by. The visual tree holds
#: every sweep and driver. The generators' TEST tree is absent because no artifact roots at a tooling
#: test, and walking it would add edges into the renderer that no producer travels.
SOURCE_ROOTS = ("src/main/java", "src/test/java", "src/visual/java", "tooling/src/main/java")

#: Compiled roots, walked for constant pools. One per source root that carries a producer, in the
#: same order.
CLASS_ROOTS = ("build/classes/java/main", "build/classes/java/test", "build/classes/java/visual",
               "tooling/build/classes/java/main")

#: The committed graph, relative to the ``parity/`` directory.
STORED = "reach.json"

#: Each artifact's producer entry point, by simple type name.
#:
#: A row produced by a whole SUITE is rooted at the class that WRITES it rather than at the suite,
#: which is the same distinction the capture wiring draws: ``test`` runs 1325 tests to write four
#: self-captured rows, and what those rows can be moved by is what their own writer reaches. A suite
#: as a root would be every test class, which answers "everything" and says nothing.
#:
#: One artifact is deliberately absent and answers through the blindness map instead:
#: ``manifest.references`` hashes the harness's reference tree, which is a separate Gradle build
#: reached by shelling into its wrapper, so it has no root in this tree.
#:
#: A root is matched by SIMPLE name, first in sorted binary order, and two top-level names are
#: declared twice now that the generators are scanned - ``PoseExpr`` and ``PosePredicate``, whose
#: renderer copies win the tie by sorting first. Neither of them roots anything.
#: A root added under a name two trees declare would resolve to whichever sorts first rather than
#: being refused, so give one a name only its own tree carries.
ROOTS: dict[str, tuple[str, ...]] = {
    # --- sweeps, each a JavaExec main of its own
    "sweep.entity": ("EntityParitySweep",),
    "sweep.entity-animation": ("EntityAnimationParitySweep",),
    "sweep.block": ("BlockParitySweep",),
    "sweep.item": ("ItemParitySweep",),
    "sweep.glint": ("GlintParitySweep",),
    "sweep.player": ("PlayerParitySweep",),
    "sweep.armor": ("ArmorParitySweep",),
    "sweep.menu": ("MenuParitySweep",),
    # --- render manifests. player-raw aggregates both rescaling sweeps rather than either one.
    "manifest.player-raw": ("PlayerParitySweep", "ArmorParitySweep"),
    "manifest.player-sheets": ("PlayerRenderDriver",),
    "manifest.fluid": ("FluidRenderDriver",),
    "manifest.portal": ("PortalRenderDriver",),
    "manifest.dump.vanilla": ("PipelineParityDump",),
    "manifest.dump.packs": ("PipelineParityDump",),
    # --- the eight visual drivers whose cache/visual sub-tree no other artifact covers
    "manifest.visual": ("BlockRenderDriver", "EntityProjectionsDriver", "EntityRenderDriver",
                        "ItemDayCycleDriver", "ItemRenderDriver", "LoreTooltipDriver",
                        "MenuRenderDriver", "BlockProjectionsDriver"),
    # --- self-captured rows, at their writer rather than at the suite that runs it
    "digest.shipped-tables": ("BundledResourceShaTest",),
    "digest.colormap-lut": ("ClientAcquisitionIntegrationTest",),
    "pin.vanilla-iso-pose": ("VanillaIsoPoseGoldenTest",),
    "pin.kit-corners": ("VanillaEntityTransformGoldenTest",),
    "pin.corpus-count": ("CorpusCountPinTest",),
    "pin.player-crc": ("PlayerRendererFittedGoldenTest",),
    "pin.block-crc": ("BlockRendererRasterPinTest",),
    "pin.portal-crc": ("PortalRendererFrameBakePinTest",),
    "pin.fluid-crc": ("FluidRendererFrameBakePinTest",),
    # --- the eight generator flows, which digest into one manifest. All eight, because the artifact
    # covers every table and each flow writes its own: rooting at one would answer for a renderer
    # type only that flow reaches and call the rest blind.
    "manifest.tooling-tables": ("EntityModelsFlow", "BlockModelsFlow", "BlockDefaultsFlow",
                                "BlockItemsFlow", "BlockTintsFlow", "PotionColorsFlow",
                                "GlintItemsFlow", "ColorMapsFlow"),
}

#: Every demoting claim ``reach check`` holds, mapped to the carriers allowed to reach what it
#: subtracts.
#:
#: A demote takes its ``blind`` list back out of the plan on every path it fires on, whatever put an
#: artifact in that path's union, so a carrier the graph walks to one of those artifacts has it
#: removed from its own plan and the plan says nothing. Every claim-keyed demote that fires on a
#: scanned source path is held here, against a ledger of the carriers that lose an artifact that way
#: by decision. ``reach check`` refuses a carrier that reaches what its demote subtracts and is not
#: listed - a new carrier, or a new edge into one - and a listed path that no longer reaches it or
#: that the demote no longer fires on, so a ledger only shrinks.
#:
#: Two ledgers are empty. ``tooling-blindness`` is true only of code no renderer producer runs: a
#: generator change cannot move a sweep, the sweeps reading the SHIPPED tables, so a renderer caller
#: of ``TableEnvelope`` would put the sweeps it reaches in the envelope's reach and the demote would
#: subtract them from every plan selecting it. ``menu-closure`` is true only while a menu type
#: reaches nothing but the visual manifest and the menu sweep.
#:
#: The other four subtract what the graph reaches, by decision. The three derived ones -
#: ``engine-renders``, ``tensor-math`` and ``face-vocabulary`` - take the two dumps off a type the
#: dump reads or serialises and never renders; ``cit-grammar`` takes the item, armour, entity, menu
#: and player renders off a grammar type those producers reach through a match none of them
#: exercises, since none stacks a pack carrying a CIT rule. The ``harness-*`` claims fire on no
#: scanned source path, so the graph has nothing to check for them and none is held.
HELD_DEMOTES: dict[str, frozenset[str]] = {
    "tooling-blindness": frozenset(),
    "menu-closure": frozenset(),
    "engine-renders": frozenset({
        "src/main/java/lib/minecraft/renderer/diagnostic/Substitutions.java",
        "src/main/java/lib/minecraft/renderer/engine/draw/PassDeclaration.java",
        "src/main/java/lib/minecraft/renderer/engine/frame/RasterPass.java",
        "src/main/java/lib/minecraft/renderer/engine/frame/Timeline.java",
        "src/main/java/lib/minecraft/renderer/engine/geometry/EulerRotation.java",
        "src/main/java/lib/minecraft/renderer/engine/geometry/Face.java",
        "src/main/java/lib/minecraft/renderer/engine/texture/MissingSprite.java",
        "src/main/java/lib/minecraft/renderer/port/MapRendererContext.java",
        "src/main/java/lib/minecraft/renderer/port/RendererContext.java",
    }),
    "tensor-math": frozenset({
        "src/main/java/lib/minecraft/renderer/engine/geometry/EulerRotation.java",
        "src/main/java/lib/minecraft/renderer/engine/pose/VanillaEase.java",
        "src/main/java/lib/minecraft/renderer/engine/pose/VanillaMth.java",
        "src/main/java/lib/minecraft/renderer/math/Matrix4f.java",
        "src/main/java/lib/minecraft/renderer/math/Quaternionf.java",
        "src/main/java/lib/minecraft/renderer/math/SimdOps.java",
        "src/main/java/lib/minecraft/renderer/math/SimdSupport.java",
        "src/main/java/lib/minecraft/renderer/math/Vector2f.java",
        "src/main/java/lib/minecraft/renderer/math/Vector3f.java",
        "src/main/java/lib/minecraft/renderer/math/Vector4f.java",
    }),
    "face-vocabulary": frozenset({
        "src/main/java/lib/minecraft/renderer/engine/geometry/Face.java",
    }),
    "cit-grammar": frozenset({
        "src/main/java/lib/minecraft/renderer/asset/rule/CitRule.java",
        "src/main/java/lib/minecraft/renderer/asset/rule/filter/IntRange.java",
        "src/main/java/lib/minecraft/renderer/asset/rule/filter/IntRanges.java",
        "src/main/java/lib/minecraft/renderer/asset/rule/filter/NbtPath.java",
        "src/main/java/lib/minecraft/renderer/asset/rule/filter/NbtPredicate.java",
        "src/main/java/lib/minecraft/renderer/asset/rule/filter/NbtRule.java",
        "src/main/java/lib/minecraft/renderer/asset/rule/filter/NbtValues.java",
    }),
}

_REFERENCE = re.compile(re.escape(PACKAGE) + r"/[A-Za-z0-9_/$]+")

#: Constant-pool tags whose entry occupies the given number of bytes after the tag.
_FIXED_WIDTH = {7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 15: 3,
                3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4}

#: The two tags that consume a second constant-pool slot, per JVMS 4.4.5.
_TWO_SLOT = (5, 6)

#: ``ACC_INTERFACE``, which is what tells a declaration of capability from a body that calls.
_ACC_INTERFACE = 0x0200


@dataclass(frozen=True)
class Graph:
    """The reference graph of one compiled tree, and what it was derived from."""

    #: Every top-level type declared in the scanned source roots, as a binary name.
    declared: frozenset[str]
    #: The types declared ``Subject.IGNORED``, whose outgoing edges do not compose.
    ignored: frozenset[str]
    #: The types each type references, keyed by the referring type.
    edges: dict[str, frozenset[str]]
    #: The artifacts each type can move, keyed by type.
    artifacts: dict[str, frozenset[str]]
    #: Each artifact's producer roots, as binary names.
    roots: dict[str, tuple[str, ...]]
    #: A digest over the class files the graph was derived from.
    compiled_digest: str


def _pool(data: bytes) -> tuple[list[object], int]:
    """The constant pool, indexed as the class file indexes it, and where the body starts.

    A ``CONSTANT_Class`` is kept as its own name index rather than resolved here, because the header
    below reads its own class, its superclass and its interfaces through exactly that indirection.

    :param data the class file's bytes
    :returns the pool and the offset of ``access_flags``, or an empty pool when it is not a class file
    :throws MissingInput if the pool carries a tag this reader has no width for
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        return [], 0
    count = struct.unpack(">H", data[8:10])[0]
    entries: list[object] = [None] * (count + 1)
    offset, index = 10, 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack(">H", data[offset:offset + 2])[0]
            entries[index] = data[offset + 2:offset + 2 + length].decode("utf-8", "replace")
            offset += 2 + length
        elif tag == 7:
            entries[index] = ("class", struct.unpack(">H", data[offset:offset + 2])[0])
            offset += 2
        elif tag in _FIXED_WIDTH:
            offset += _FIXED_WIDTH[tag]
        elif tag in _TWO_SLOT:
            offset += 8
            index += 1
        else:
            raise MissingInput(f"unknown constant-pool tag '{tag}' at offset '{offset}'")
        index += 1
    return entries, offset


def utf8_entries(data: bytes) -> list[str]:
    """Every ``CONSTANT_Utf8`` entry of a class file, which is where every type name is spelled.

    :param data the class file's bytes
    :returns the pool's string entries, empty when the bytes are not a class file
    :throws MissingInput if the pool carries a tag this reader has no width for
    """
    entries, _ = _pool(data)
    return [entry for entry in entries if isinstance(entry, str)]


@dataclass(frozen=True)
class Surface:
    """What a class file DECLARES, as against what it merely mentions somewhere in its pool.

    The declaration is its own name, its supertypes and every field and method descriptor and generic
    signature - the shape a caller compiles against. Everything else a class file names it names in a
    method BODY, and the difference is what tells a declared capability from an exercised one.
    """

    #: The types the declaration mentions, as they are spelled in the pool.
    types: frozenset[str]
    #: Whether the file declares an interface, whose members are capabilities rather than calls.
    is_interface: bool


def signature_surface(data: bytes) -> Surface:
    """Read one class file's declaration surface.

    :param data the class file's bytes
    """
    entries, offset = _pool(data)
    if not entries:
        return Surface(types=frozenset(), is_interface=False)

    def utf8(index: int) -> str:
        entry = entries[index] if 0 < index < len(entries) else None
        return entry if isinstance(entry, str) else ""

    def class_name(index: int) -> str:
        entry = entries[index] if 0 < index < len(entries) else None
        return utf8(entry[1]) if isinstance(entry, tuple) else ""

    flags = struct.unpack(">H", data[offset:offset + 2])[0]
    found = {class_name(struct.unpack(">H", data[offset + 2:offset + 4])[0]),
             class_name(struct.unpack(">H", data[offset + 4:offset + 6])[0])}
    total = struct.unpack(">H", data[offset + 6:offset + 8])[0]
    position = offset + 8
    for _ in range(total):
        found.add(class_name(struct.unpack(">H", data[position:position + 2])[0]))
        position += 2

    # fields[] then methods[], which share a shape: access_flags, name, descriptor, attributes.
    for _ in range(2):
        members = struct.unpack(">H", data[position:position + 2])[0]
        position += 2
        for _ in range(members):
            found.add(utf8(struct.unpack(">H", data[position + 4:position + 6])[0]))
            attributes = struct.unpack(">H", data[position + 6:position + 8])[0]
            position += 8
            for _ in range(attributes):
                name = utf8(struct.unpack(">H", data[position:position + 2])[0])
                length = struct.unpack(">I", data[position + 2:position + 6])[0]
                if name == "Signature":
                    found.add(utf8(struct.unpack(">H", data[position + 6:position + 8])[0]))
                elif name == "Exceptions":
                    thrown = struct.unpack(">H", data[position + 6:position + 8])[0]
                    for slot in range(thrown):
                        found.add(class_name(struct.unpack(
                            ">H", data[position + 8 + 2 * slot:position + 10 + 2 * slot])[0]))
                position += 6 + length
    return Surface(types=frozenset(text for text in found if text),
                   is_interface=bool(flags & _ACC_INTERFACE))


def source_paths(base: Path) -> list[str]:
    """Every compilation unit in the scanned source roots that declares a type, repo-relative.

    :param base the repository root
    """
    out: list[str] = []
    for source_root in SOURCE_ROOTS:
        root = base / source_root
        if not root.is_dir():
            continue
        out.extend(path.relative_to(base).as_posix() for path in root.rglob("*.java")
                   if path.name != "package-info.java")
    return sorted(out)


def declared_types(base: Path) -> frozenset[str]:
    """Every top-level type in the scanned source roots, as a binary name.

    :param base the repository root
    """
    return frozenset(to_binary(path) for path in source_paths(base))


def owning_type(binary_name: str, declared: frozenset[str]) -> str | None:
    """The top-level type a binary name belongs to, folding nested, anonymous and lambda classes.

    :param binary_name a slash-form name, possibly carrying a ``$`` suffix
    :param declared the types that may be answered
    """
    outer = binary_name.split("$")[0]
    return outer if outer in declared else None


def to_binary(path: str) -> str | None:
    """The binary name of a repo-relative ``.java`` path, or nothing when it is not a scanned one.

    :param path a repo-relative path in either separator
    """
    text = path.replace("\\", "/")
    if not text.endswith(".java") or text.endswith("package-info.java"):
        return None
    for source_root in SOURCE_ROOTS:
        prefix = f"{source_root}/"
        if text.startswith(prefix):
            return text[len(prefix):-len(".java")]
    return None


def _resolve_roots(declared: frozenset[str]) -> dict[str, tuple[str, ...]]:
    """Each artifact's roots as binary names, refusing a root the tree does not declare."""
    by_simple: dict[str, str] = {}
    for name in sorted(declared):
        by_simple.setdefault(name.rsplit("/", 1)[1], name)
    out: dict[str, tuple[str, ...]] = {}
    for artifact, simple_names in ROOTS.items():
        resolved: list[str] = []
        for simple in simple_names:
            found = by_simple.get(simple)
            if found is None:
                raise MissingInput(f"artifact '{artifact}' roots at '{simple}', which is not declared")
            resolved.append(found)
        out[artifact] = tuple(resolved)
    return out


def _edges(base: Path, declared: frozenset[str]) \
        -> tuple[dict[str, frozenset[str]], dict[str, frozenset[str]], frozenset[str], str]:
    """The reference graph, each type's declaration surface, the interfaces, and a tree digest.

    The surface is accumulated over every class file folded onto a type, nested ones included, since
    a nested type's own descriptors are as much a declaration as its outer's. The interface flag is
    read from the TOP-LEVEL file alone: a nested class inside an interface is still a class, and it
    is the outer type that a seam declaration names.
    """
    building: dict[str, set[str]] = defaultdict(set)
    surfaces: dict[str, set[str]] = defaultdict(set)
    interfaces: set[str] = set()
    digest = hashlib.sha256()
    seen = 0
    for class_root in CLASS_ROOTS:
        root = base / class_root
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.class")):
            relative = path.relative_to(root).with_suffix("").as_posix()
            owner = owning_type(relative, declared)
            if owner is None:
                continue
            seen += 1
            data = path.read_bytes()
            digest.update(relative.encode())
            digest.update(hashlib.sha256(data).digest())
            surface = signature_surface(data)
            if relative == owner and surface.is_interface:
                interfaces.add(owner)
            for entry in utf8_entries(data):
                for reference in _REFERENCE.findall(entry):
                    target = owning_type(reference, declared)
                    if target is not None and target != owner:
                        building[owner].add(target)
            for entry in surface.types:
                for reference in _REFERENCE.findall(entry):
                    target = owning_type(reference, declared)
                    if target is not None and target != owner:
                        surfaces[owner].add(target)
    if not seen:
        raise MissingInput("no class files found - run './gradlew compileJava compileTestJava'")
    return ({name: frozenset(targets) for name, targets in building.items()},
            {name: frozenset(targets) for name, targets in surfaces.items()},
            frozenset(interfaces), digest.hexdigest())


def forward(start: str, edges: dict[str, frozenset[str]]) -> set[str]:
    """Every type transitively referenced from ``start``, excluding it.

    :param start the type to walk from
    :param edges the reference graph
    """
    seen: set[str] = set()
    stack = [start]
    while stack:
        for target in edges.get(stack.pop(), ()):
            if target not in seen:
                seen.add(target)
                stack.append(target)
    seen.discard(start)
    return seen


def chain(start: str, goal: str, edges: dict[str, frozenset[str]]) -> list[str] | None:
    """The shortest reference chain from ``start`` to ``goal``, or nothing when unconnected.

    :param start the type to walk from
    :param goal the type to reach
    :param edges the reference graph
    """
    previous: dict[str, str | None] = {start: None}
    queue = deque([start])
    while queue:
        current = queue.popleft()
        if current == goal:
            out: list[str] = []
            node: str | None = current
            while node is not None:
                out.append(node)
                node = previous[node]
            return list(reversed(out))
        for target in sorted(edges.get(current, ())):
            if target not in previous:
                previous[target] = current
                queue.append(target)
    return None


def ignored_types(base: Path, declared: frozenset[str]) -> frozenset[str]:
    """Every type declaring ``ignored = true``, read from source as every declaration is.

    :param base the repository root
    :param declared the types that may be answered
    """
    scan = declarations_mod.scan(base)
    out: set[str] = set()
    for declaration in scan.declarations:
        if not declaration.ignored or declaration.on == "package":
            continue
        binary = to_binary(declaration.path)
        if binary in declared:
            out.add(binary)
    return frozenset(out)


def build(base: Path) -> Graph:
    """Derive the whole reach graph from a compiled tree.

    :param base the repository root
    :throws MissingInput if the tree is not compiled, or a declared root is missing
    """
    declared = declared_types(base)
    edges, surfaces, interfaces, digest = _edges(base, declared)
    ignored = ignored_types(base, declared)
    # Outgoing edges only. Reach stops composing THROUGH a wiring type, and a change TO one is still
    # seen by everything that reaches it - which is what keeps a defaulted interface member honest.
    #
    # What is cut depends on what the seam IS, and the two answers are the same sentence read at two
    # kinds of type. An INTERFACE declares capabilities: its members' descriptors put every type they
    # mention in the pool whether or not anything calls them, which is the collapse, and an abstract
    # member cannot change alone because every implementor moves with it. Its default BODIES are not
    # that - they are code, with no implementor to carry a change, so what they call is kept. A CLASS
    # has no such split: every reference it holds is one it makes, so it is cut whole.
    #
    # Measured. Cutting the interfaces by declaration alone moves two types and no others, each of
    # them reached from a default body; cutting the concrete context that way instead collapses the
    # graph, from 29 engine-wide types to 151.
    edges = {name: (targets - surfaces.get(name, frozenset()) if name in interfaces
                    else frozenset()) if name in ignored else targets
             for name, targets in edges.items()}
    roots = _resolve_roots(declared)
    artifacts: dict[str, set[str]] = defaultdict(set)
    for artifact, entry_points in roots.items():
        touched: set[str] = set()
        for entry in entry_points:
            touched.add(entry)
            touched |= forward(entry, edges)
        for name in touched:
            artifacts[name].add(artifact)
    return Graph(declared=declared, ignored=ignored, edges=edges,
                 artifacts={name: frozenset(found) for name, found in artifacts.items()},
                 roots=roots, compiled_digest=digest)


def of(graph: Graph, paths: list[str]) -> dict[str, list[str]]:
    """The artifacts each given path reaches, keyed by the path as it was given.

    A path that is not a scanned Java source answers nothing rather than an empty reach, because the
    blindness map is what answers it and an empty list here would read as a licensed narrowing.

    :param graph a derived graph
    :param paths repo-relative paths
    """
    out: dict[str, list[str]] = {}
    for path in paths:
        binary = to_binary(path)
        if binary is None or binary not in graph.declared:
            continue
        out[path] = sorted(graph.artifacts.get(binary, frozenset()))
    return out


def answered_by(payload: dict, path: str) -> list[str] | None:
    """What a committed graph says one repo-relative path reaches, or nothing when it cannot answer.

    The reader a derived blindness rule resolves through, and it answers off the COMMITTED file
    rather than off a freshly walked tree. A graph derived at plan time is whatever was last
    compiled, which is the one way this scheduling can be quietly wrong rather than loudly stale;
    ``reach check`` on a verification run is what holds the committed file to the tree instead.

    Two shapes under a source root answer the empty list rather than nothing, each because no class
    file anywhere can reference it. A ``package-info.java`` declares no type, and what it does carry
    - a package's own declaration - moves this map's trigger paths rather than any render. A
    ``doc-files`` directory is javadoc's own reserved name: javac passes over it and the doclet copies
    it verbatim, so what sits there is illustration rather than input. Anything else under a source
    root is refused, a new type and a shipped resource each needing an answer somebody wrote down.

    :param payload: a committed graph, as :func:`to_payload` writes one
    :param path: a repo-relative path in either separator
    """
    text = path.replace("\\", "/")
    if any(text.startswith(f"{root}/") for root in SOURCE_ROOTS) and (
            text.endswith("/package-info.java") or "/doc-files/" in text):
        return []
    binary = to_binary(text)
    if binary is None:
        return None
    row = (payload.get("types") or {}).get(binary)
    return None if row is None else list(row.get("artifacts", ()))


def orphans(graph: Graph) -> list[str]:
    """Every declared type no producer root reaches, which is what a declaration has to answer for.

    :param graph a derived graph
    """
    return sorted(name for name in graph.declared if not graph.artifacts.get(name))


def declared_reach(base: Path, declared: frozenset[str]) -> dict[str, tuple[str, ...]]:
    """Each type's own declared reach, read from source as every declaration is.

    A reach is a declaration of its OWN, naming a subject and no claim. A subject written beside a
    claim decorates that claim - it says which renderers the claim is about - and reading one as a
    reach would take a statement about a blindness rule for a statement about a type. A type
    carrying both writes both, which is two facts rather than one overloaded member.

    :param base the repository root
    :param declared the types that may be answered
    """
    out: dict[str, tuple[str, ...]] = {}
    for declaration in declarations_mod.scan(base).declarations:
        if declaration.on == "package" or declaration.claim or declaration.joins:
            continue
        binary = to_binary(declaration.path)
        if declaration.subject and binary in declared:
            out[binary] = declaration.subject
    return out


def unexplained(base: Path, graph: Graph) -> list[str]:
    """Every LIBRARY type that reaches nothing and says nothing about it.

    A type no producer root reaches answers the empty set, and two very different things look like
    that: a renderer the store holds no artifact for by decision, and a type the graph cannot see an
    edge to - reached only across a wiring seam, or built by a service loader out of a file no
    constant pool mentions. The first is correct and the second is a gate quietly not running, and
    nothing derived can tell them apart, so the type says which and this refuses one that does not.

    Scoped to the library's own source root. A test class is reached by a producer root only when it
    IS one, so every other test in the tree answers nothing by construction, and asking each of them
    to say so would be asking for a declaration per assertion.

    :param base the repository root
    :param graph a derived graph
    """
    explained = declared_reach(base, graph.declared)
    root = base / SOURCE_ROOTS[0]
    return sorted(name for name in orphans(graph)
                  if name not in explained and (root / f"{name}.java").is_file())


def self_demotions(payload: dict, rules: Sequence[Rule], paths: Iterable[str],
                   held: Mapping[str, frozenset[str]] = HELD_DEMOTES) -> list[str]:
    """Every path on which a held demote and its ledger disagree, one line each.

    A carrier is a path the demoting rule fires on, so a type is held whether its own declaration,
    its package's or an authored trigger put it under the rule. What it reaches is the graph's
    answer for that path. A carrier reaching an artifact the demote subtracts is named unless the
    claim's ledger lists it, and a listed path is named once it no longer reaches one or is no
    longer a carrier at all, so the ledger stays exactly the carriers that lose an artifact by
    decision. A path the graph has no row for reaches nothing here, being a type the graph
    predates, which the comparison of the graph against the tree already names.

    A held claim that no demoting rule carries, or that fires on no source path, is refused rather
    than passed: a renamed slug or a moved carrier would otherwise leave this holding nothing and
    reporting that nothing is wrong.

    :param payload a graph, as :func:`to_payload` writes one
    :param rules the map's rules, each carrying the trigger paths the tree derives
    :param paths the repo-relative source paths a rule may fire on
    :param held each held claim, mapped to the carriers allowed to reach what it subtracts
    :returns one line per unlisted carrier reaching what its demote subtracts and per listed path
        that no longer does, sorted, empty when every ledger agrees with the tree
    :throws MissingInput if a held claim is carried by no demoting rule, or fires on no source path
    """
    candidates = sorted(paths)
    out: list[str] = []
    for claim, listed in held.items():
        demoting = [rule for rule in rules if rule.claim_key == claim and rule.mode == "demote"]
        if not demoting:
            raise MissingInput(
                f"the held demote '{claim}' is carried by no demoting rule in the map")
        fired: set[str] = set()
        reaching: set[str] = set()
        for rule in demoting:
            carriers = [path for path in candidates if matches(path, rule.trigger_paths)]
            if not carriers:
                raise MissingInput(
                    f"the held demote '{claim}' ({rule.id}) fires on no scanned source path")
            fired.update(carriers)
            for path in carriers:
                subtracted = sorted(set(answered_by(payload, path) or ()) & set(rule.blind))
                if not subtracted:
                    continue
                reaching.add(path)
                if path not in listed:
                    out.append(f"{path}: reaches {', '.join(subtracted)}, which {rule.id} "
                               f"'{claim}' subtracts, and HELD_DEMOTES does not list it")
        for path in sorted(listed - reaching):
            state = ("reaches nothing that demote subtracts" if path in fired
                     else "is not one of that demote's carriers")
            out.append(f"{path}: HELD_DEMOTES lists it under '{claim}', and it {state}")
    return sorted(out)


def to_payload(graph: Graph) -> dict:
    """The stored form of a graph, for the committed reach file.

    Carries no digest of the tree it came from, deliberately. A class-file digest moves on every
    commit that changes any code at all, so storing it would churn this file on changes that move no
    reach and bury the diffs that do. What guards staleness is the comparison itself: ``check``
    re-derives from a freshly compiled tree and reports the map's own difference.

    :param graph a derived graph
    """
    return {
        "format": 1,
        "kind": "class-reach",
        "ignored": sorted(graph.ignored),
        "roots": {artifact: list(names) for artifact, names in sorted(graph.roots.items())},
        "types": {name: {"artifacts": sorted(graph.artifacts.get(name, frozenset())),
                         "source": "derived"}
                  for name in sorted(graph.declared)},
    }


def differences(stored: dict, derived: dict) -> list[str]:
    """What moved between a committed graph and a freshly derived one, as one line per type.

    :param stored the committed payload
    :param derived the payload just derived
    :returns the moved types, sorted, empty when the two agree
    """
    was = {name: row.get("artifacts", []) for name, row in (stored.get("types") or {}).items()}
    now = {name: row.get("artifacts", []) for name, row in (derived.get("types") or {}).items()}
    moved: list[str] = []
    for name in sorted(set(was) | set(now)):
        if name not in was:
            moved.append(f"+ {name}: {', '.join(now[name]) or '(none)'}")
        elif name not in now:
            moved.append(f"- {name}")
        elif was[name] != now[name]:
            moved.append(f"~ {name}: {', '.join(was[name]) or '(none)'} "
                         f"-> {', '.join(now[name]) or '(none)'}")
    if (stored.get("roots") or {}) != (derived.get("roots") or {}):
        moved.append("~ roots")
    return moved
