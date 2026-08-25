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

from parity import declarations as declarations_mod

from .norm import MissingInput

#: The package every edge this module cares about lives under, in binary (slash) form.
PACKAGE = "lib/minecraft/renderer"

#: Source roots holding types that can carry a parity reach, relative to the repo root.
SOURCE_ROOTS = ("src/main/java", "src/test/java")

#: Compiled roots, walked for constant pools.
CLASS_ROOTS = ("build/classes/java/main", "build/classes/java/test")

#: The committed graph, relative to the ``parity/`` directory.
STORED = "reach.json"

#: Each artifact's producer entry point, by simple type name.
#:
#: A row produced by a whole SUITE is rooted at the class that WRITES it rather than at the suite,
#: which is the same distinction the capture wiring draws: ``test`` runs 1325 tests to write four
#: self-captured rows, and what those rows can be moved by is what their own writer reaches. A suite
#: as a root would be every test class, which answers "everything" and says nothing.
#:
#: Two artifacts are deliberately absent and answer through the blindness map instead. Neither has a
#: root in this tree: ``manifest.references`` hashes the harness's reference tree, which is a
#: separate Gradle build reached by shelling into its wrapper, and ``manifest.tooling-tables`` is the
#: eight generator flows, which are another build again and on no classpath here.
ROOTS: dict[str, tuple[str, ...]] = {
    # --- sweeps, each a JavaExec main of its own
    "sweep.entity": ("TestEntityParityVanilla",),
    "sweep.entity-animation": ("TestEntityAnimationParityVanilla",),
    "sweep.block": ("TestBlockParityVanilla",),
    "sweep.item": ("TestItemParityVanilla",),
    "sweep.glint": ("TestGlintParityVanilla",),
    "sweep.player": ("TestPlayerParityVanilla",),
    "sweep.armor": ("TestArmorParityVanilla",),
    "sweep.menu": ("TestMenuParityVanilla",),
    # --- render manifests. player-raw aggregates both rescaling sweeps rather than either one.
    "manifest.player-raw": ("TestPlayerParityVanilla", "TestArmorParityVanilla"),
    "manifest.player-sheets": ("TestPlayerRender",),
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
    "pin.vanilla-iso-pose": ("VanillaEntityTransformGoldenTest",),
    "pin.kit-corners": ("VanillaEntityTransformGoldenTest",),
    "pin.corpus-count": ("CorpusCountPinTest",),
    "pin.player-crc": ("PlayerRendererFittedGoldenTest",),
    "pin.block-crc": ("BlockRendererRasterPinTest",),
    "pin.portal-crc": ("PortalRendererFrameBakePinTest",),
    "pin.fluid-crc": ("FluidRendererFrameBakePinTest",),
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


def declared_types(base: Path) -> frozenset[str]:
    """Every top-level type in the scanned source roots, as a binary name.

    :param base the repository root
    """
    out: set[str] = set()
    for source_root in SOURCE_ROOTS:
        root = base / source_root
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            if path.name != "package-info.java":
                out.add(path.relative_to(root).with_suffix("").as_posix())
    return frozenset(out)


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
    # Measured. Cutting the interfaces by declaration alone moves two types and no others, both of
    # them reached from a default body and both previously answering that nothing sees them; cutting
    # the concrete context that way instead re-collapses the graph, from 29 engine-wide types to 151.
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
