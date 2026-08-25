"""The constant-pool reader, the producer-rooted closure, and what the committed graph is checked on.

The reader gets its own cases because a pool walker's classic defect is silent: ``long`` and
``double`` consume a second slot, and a walker that misses that runs off the end of one class and
reports fewer edges rather than failing.
"""

from __future__ import annotations

import struct
import unittest
from pathlib import Path

from parity import reach
from parity.norm import MissingInput

REPO = Path(__file__).resolve().parents[4]


def _pool_bytes(*entries: bytes) -> bytes:
    """The magic, the version and a constant pool holding the given entries."""
    count = 1
    body = b""
    for entry in entries:
        body += entry
        count += 2 if entry[:1] in (b"\x05", b"\x06") else 1
    return b"\xca\xfe\xba\xbe" + b"\x00\x00\x00\x45" + struct.pack(">H", count) + body


def _pool(*entries: bytes) -> bytes:
    """A class file carrying the given constant-pool entries and nothing else worth reading."""
    return _pool_bytes(*entries)


def _utf8(text: str) -> bytes:
    raw = text.encode("utf-8")
    return b"\x01" + struct.pack(">H", len(raw)) + raw


class ConstantPool(unittest.TestCase):

    def test_it_reads_the_string_entries(self):
        self.assertEqual(reach.utf8_entries(_pool(_utf8("a"), _utf8("bb"))), ["a", "bb"])

    def test_a_long_consumes_two_slots(self):
        """Counted as one, the walk ends a slot early and silently reports fewer edges."""
        data = _pool(_utf8("first"), b"\x05" + b"\x00" * 8, _utf8("second"))
        self.assertEqual(reach.utf8_entries(data), ["first", "second"])

    def test_a_double_consumes_two_slots(self):
        data = _pool(_utf8("first"), b"\x06" + b"\x00" * 8, _utf8("second"))
        self.assertEqual(reach.utf8_entries(data), ["first", "second"])

    def test_a_class_entry_is_stepped_over(self):
        data = _pool(_utf8("kept"), b"\x07\x00\x01", _utf8("also"))
        self.assertEqual(reach.utf8_entries(data), ["kept", "also"])

    def test_a_method_handle_is_three_bytes(self):
        data = _pool(_utf8("kept"), b"\x0f\x00\x00\x01", _utf8("also"))
        self.assertEqual(reach.utf8_entries(data), ["kept", "also"])

    def test_bytes_that_are_not_a_class_file_answer_nothing(self):
        self.assertEqual(reach.utf8_entries(b"not a class file"), [])

    def test_an_unknown_tag_refuses_rather_than_guessing_a_width(self):
        with self.assertRaises(MissingInput):
            reach.utf8_entries(_pool(b"\x63\x00"))


class TheDeclarationSurface(unittest.TestCase):
    """What a class file declares, as against what it merely mentions in a body.

    Driven over hand-built class files because the distinction is a byte layout rather than a
    property of this tree: a header read at the wrong offset still yields plausible strings.
    """

    @staticmethod
    def _file(*, interface: bool, descriptor: str, mentioned: str) -> bytes:
        """A class file declaring one field of the given descriptor and mentioning the other name.

        The mentioned string is an ordinary pool entry outside every declaration, which is where a
        method body's references land.
        """
        pool = [_utf8("Self"), _utf8(descriptor), _utf8(mentioned), _utf8("f"),
                b"\x07" + struct.pack(">H", 1)]                       # CONSTANT_Class -> "Self"
        body = (struct.pack(">H", 0x0200 if interface else 0x0021)    # access_flags
                + struct.pack(">H", 5)                                # this_class -> the class entry
                + struct.pack(">H", 0)                                # super_class, absent
                + struct.pack(">H", 0)                                # interfaces_count
                + struct.pack(">H", 1)                                # fields_count
                + struct.pack(">H", 0) + struct.pack(">H", 4)         # access_flags, name -> "f"
                + struct.pack(">H", 2) + struct.pack(">H", 0)         # descriptor, attributes_count
                + struct.pack(">H", 0)                                # methods_count
                + struct.pack(">H", 0))                               # attributes_count
        return _pool_bytes(*pool) + body

    def test_a_field_descriptor_is_part_of_the_declaration(self):
        surface = reach.signature_surface(
            self._file(interface=False, descriptor="Ldeclared;", mentioned="called"))
        self.assertIn("Ldeclared;", surface.types)

    def test_a_name_outside_every_declaration_is_not(self):
        """Which is where a body's references sit, and the whole of what the split buys."""
        surface = reach.signature_surface(
            self._file(interface=False, descriptor="Ldeclared;", mentioned="called"))
        self.assertNotIn("called", surface.types)

    def test_the_class_and_its_supertypes_are_part_of_it(self):
        surface = reach.signature_surface(
            self._file(interface=False, descriptor="Ldeclared;", mentioned="called"))
        self.assertIn("Self", surface.types)

    def test_an_interface_says_so(self):
        for interface in (True, False):
            with self.subTest(interface=interface):
                self.assertEqual(
                    reach.signature_surface(
                        self._file(interface=interface, descriptor="Lx;", mentioned="y")
                    ).is_interface, interface)

    def test_bytes_that_are_not_a_class_file_declare_nothing(self):
        surface = reach.signature_surface(b"not a class file")
        self.assertEqual((surface.types, surface.is_interface), (frozenset(), False))


class Folding(unittest.TestCase):

    DECLARED = frozenset({"lib/minecraft/renderer/Foo"})

    def test_a_nested_type_folds_onto_its_declaring_type(self):
        self.assertEqual(reach.owning_type("lib/minecraft/renderer/Foo$Bar", self.DECLARED),
                         "lib/minecraft/renderer/Foo")

    def test_an_anonymous_class_folds_too(self):
        self.assertEqual(reach.owning_type("lib/minecraft/renderer/Foo$1", self.DECLARED),
                         "lib/minecraft/renderer/Foo")

    def test_a_type_the_source_tree_does_not_declare_answers_nothing(self):
        self.assertIsNone(reach.owning_type("lib/minecraft/renderer/Absent", self.DECLARED))


class SourcePaths(unittest.TestCase):

    def test_a_main_source_path_becomes_a_binary_name(self):
        self.assertEqual(reach.to_binary("src/main/java/lib/minecraft/renderer/Foo.java"),
                         "lib/minecraft/renderer/Foo")

    def test_a_backslash_path_reads_the_same(self):
        self.assertEqual(reach.to_binary(r"src\main\java\lib\minecraft\renderer\Foo.java"),
                         "lib/minecraft/renderer/Foo")

    def test_a_package_info_is_not_a_type(self):
        self.assertIsNone(reach.to_binary("src/main/java/lib/minecraft/renderer/package-info.java"))

    def test_a_non_java_path_answers_nothing_so_the_map_answers_it(self):
        self.assertIsNone(reach.to_binary("gradle/parity.gradle.kts"))


class AnsweringOnePath(unittest.TestCase):
    """What a derived blindness rule resolves through, and where it declines to answer at all."""

    PAYLOAD = {"types": {"lib/minecraft/renderer/Foo": {"artifacts": ["sweep.entity"]},
                         "lib/minecraft/renderer/Bare": {"artifacts": []}}}

    def test_a_declared_type_answers_its_artifacts(self):
        self.assertEqual(
            reach.answered_by(self.PAYLOAD, "src/main/java/lib/minecraft/renderer/Foo.java"),
            ["sweep.entity"])

    def test_a_type_no_root_reaches_answers_an_empty_list(self):
        """Empty and answered, which is a different thing from unanswerable: the graph walked it."""
        self.assertEqual(
            reach.answered_by(self.PAYLOAD, "src/main/java/lib/minecraft/renderer/Bare.java"), [])

    def test_a_type_the_committed_graph_predates_answers_nothing(self):
        """The refusal a new source file has to produce, rather than a silent empty plan."""
        self.assertIsNone(
            reach.answered_by(self.PAYLOAD, "src/main/java/lib/minecraft/renderer/New.java"))

    def test_a_package_declaration_answers_an_empty_list(self):
        """It declares no type, and what it does carry moves trigger paths rather than a render."""
        self.assertEqual(
            reach.answered_by(self.PAYLOAD,
                              "src/main/java/lib/minecraft/renderer/engine/package-info.java"), [])

    def test_a_package_declaration_outside_a_scanned_root_answers_nothing(self):
        self.assertIsNone(reach.answered_by(self.PAYLOAD, "tooling/src/main/java/package-info.java"))

    def test_javadoc_art_answers_an_empty_list(self):
        """`doc-files` is javadoc's reserved name: javac passes over it and the doclet copies it."""
        self.assertEqual(
            reach.answered_by(self.PAYLOAD,
                              "src/main/java/lib/minecraft/renderer/tensor/doc-files/e.svg"), [])

    def test_anything_else_under_a_source_root_answers_nothing(self):
        """A shipped resource under a source root needs an answer somebody wrote down."""
        self.assertIsNone(
            reach.answered_by(self.PAYLOAD, "src/main/java/lib/minecraft/renderer/table.json"))

    def test_a_path_carrying_no_java_answers_nothing(self):
        self.assertIsNone(reach.answered_by(self.PAYLOAD, "gradle/parity.gradle.kts"))


class Differences(unittest.TestCase):

    @staticmethod
    def _payload(types):
        return {"roots": {}, "types": {name: {"artifacts": arts} for name, arts in types.items()}}

    def test_an_unchanged_map_moves_nothing(self):
        one = self._payload({"a": ["sweep.entity"]})
        self.assertEqual(reach.differences(one, one), [])

    def test_a_widened_reach_is_reported(self):
        moved = reach.differences(self._payload({"a": ["sweep.entity"]}),
                                  self._payload({"a": ["sweep.block", "sweep.entity"]}))
        self.assertEqual(len(moved), 1)
        self.assertIn("->", moved[0])

    def test_an_added_type_is_reported(self):
        moved = reach.differences(self._payload({}), self._payload({"a": ["sweep.entity"]}))
        self.assertEqual(moved, ["+ a: sweep.entity"])

    def test_a_removed_type_is_reported(self):
        moved = reach.differences(self._payload({"a": ["sweep.entity"]}), self._payload({}))
        self.assertEqual(moved, ["- a"])

    def test_a_moved_root_is_reported(self):
        stored = {"roots": {"sweep.entity": ["A"]}, "types": {}}
        derived = {"roots": {"sweep.entity": ["B"]}, "types": {}}
        self.assertEqual(reach.differences(stored, derived), ["~ roots"])


@unittest.skipUnless((REPO / "build" / "classes" / "java" / "main").is_dir(),
                     "needs a compiled tree")
class OverTheRealTree(unittest.TestCase):
    """The properties the import graph got wrong in both directions, over the tree itself."""

    @classmethod
    def setUpClass(cls):
        cls.graph = reach.build(REPO)

    def _artifacts(self, simple: str) -> set[str]:
        name = next(n for n in self.graph.declared if n.rsplit("/", 1)[1] == simple)
        return set(self.graph.artifacts.get(name, frozenset()))

    def test_an_entity_only_kit_reaches_no_item_or_block_sweep(self):
        """The saving. PoseKit plans 15 artifacts under a path prefix and owes the item sweep none."""
        found = self._artifacts("PoseKit")
        self.assertIn("sweep.entity", found)
        self.assertNotIn("sweep.item", found)
        self.assertNotIn("sweep.block", found)
        self.assertNotIn("sweep.menu", found)

    def test_the_block_renderer_owes_the_entity_sweep(self):
        """An entity draws a carried block through it, so a change there is an entity change."""
        self.assertIn("sweep.entity", self._artifacts("BlockRenderer"))

    def test_a_tensor_type_reaches_every_artifact_that_renders(self):
        """Engine-wide, and a full run is what it costs - nothing here tries to talk that down."""
        found = self._artifacts("Vector3f")
        for artifact in ("sweep.entity", "sweep.block", "sweep.item", "sweep.menu", "sweep.player",
                         "manifest.dump.vanilla", "manifest.visual", "pin.block-crc"):
            self.assertIn(artifact, found)

    def test_even_a_tensor_type_cannot_move_a_digest_of_shipped_resources(self):
        """The narrowing that survives at the widest reach there is.

        `digest.shipped-tables` hashes the JSON this build ships and renders nothing, so no geometry
        can move it. Rooting it at the suite that runs its writer would have said the opposite.
        """
        self.assertNotIn("digest.shipped-tables", self._artifacts("Vector3f"))
        self.assertIn("digest.shipped-tables", self.graph.roots)

    def test_a_javadoc_only_reference_is_no_edge(self):
        """RendererContext documents BlockRenderer and does not depend on it.

        That one import is what made every renderer appear to reach every other, and it is the whole
        reason the substrate is bytecode.
        """
        context = "lib/minecraft/renderer/engine/RendererContext"
        self.assertNotIn("lib/minecraft/renderer/BlockRenderer", self.graph.edges.get(context, ()))

    def test_a_menu_type_does_not_reach_the_entity_sweep(self):
        self.assertNotIn("sweep.entity", self._artifacts("MenuScreen"))

    def test_the_wiring_seams_are_declared_and_read(self):
        for simple in ("RendererContext", "PipelineRendererContext", "RenderOptions"):
            name = next(n for n in self.graph.declared if n.rsplit("/", 1)[1] == simple)
            self.assertIn(name, self.graph.ignored)

    def test_reach_does_not_compose_through_a_wiring_seam(self):
        """The collapse this exists to stop.

        `RendererContext` DECLARES an entity lookup, so before the seam a menu sweep reached the
        whole entity surface across it - a declared capability read as an exercised one.
        """
        self.assertNotIn("sweep.menu", self._artifacts("Entity"))
        self.assertNotIn("sweep.entity", self._artifacts("MenuScreen"))

    def test_a_change_TO_a_seam_is_still_seen(self):
        """Outgoing edges only. `RendererContext` ships 21 default bodies beside its abstract
        members, and a change to one of those moves output with no implementor edit to carry it."""
        found = self._artifacts("RendererContext")
        self.assertIn("sweep.entity", found)
        self.assertIn("sweep.menu", found)

    def test_what_a_seam_INTERFACE_calls_survives_its_cut(self):
        """The other half of that sentence, one level down.

        `RendererContext` resolves a redstone tint and a flipbook in DEFAULT bodies, so those two
        types are reached from code with no implementor to carry a change to them - and cutting the
        interface whole answered that nothing at all sees either.
        """
        for simple in ("RedstoneTint", "AnimationKit"):
            found = self._artifacts(simple)
            self.assertIn("sweep.block", found, simple)
            self.assertIn("sweep.entity", found, simple)

    def test_what_a_seam_INTERFACE_declares_does_not(self):
        """The collapse itself: a declared entity lookup is not an exercised one."""
        self.assertNotIn("sweep.menu", self._artifacts("Entity"))

    def test_a_seam_that_is_a_CLASS_is_cut_whole(self):
        """It has no split to make - every reference a class holds is one it makes.

        Measured rather than assumed: cutting the concrete context by its declaration instead takes
        the tree from 29 engine-wide types to 151, which is the collapse the seam exists against.
        """
        name = next(n for n in self.graph.declared
                    if n.rsplit("/", 1)[1] == "PipelineRendererContext")
        self.assertIn(name, self.graph.ignored)
        self.assertEqual(self.graph.edges.get(name, frozenset()), frozenset())


if __name__ == "__main__":
    unittest.main()
