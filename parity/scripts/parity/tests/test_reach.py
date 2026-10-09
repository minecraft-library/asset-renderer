"""The constant-pool reader, the producer-rooted closure, and what the committed graph is checked on.

The reader gets its own cases because a pool walker's classic defect is silent: ``long`` and
``double`` consume a second slot, and a walker that misses that runs off the end of one class and
reports fewer edges rather than failing.
"""

from __future__ import annotations

import struct
import unittest
from pathlib import Path

from parity import blindness, declarations, reach, store
from parity.norm import MissingInput, read_json

REPO = Path(__file__).resolve().parents[4]

#: The one renderer type the tooling demote fires on, by a declaration of its own.
ENVELOPE = "src/main/java/lib/minecraft/renderer/content/table/TableEnvelope.java"


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


class ThePermitsList(unittest.TestCase):
    """A sealed type's permits list is no reach edge, and every other naming of a permitted subtype is.

    Driven over hand-built class files because the distinction is which structure references a class
    entry, and one entry serves both the listing and the code that uses the subtype. The walk carries
    the pool reader's classic defect too: an instruction read at the wrong width loses step and
    reports fewer uses, so the switch-and-wide case is the one that pins it.
    """

    SEALED = "lib/minecraft/renderer/Sealed"
    LISTED = "lib/minecraft/renderer/Listed"
    OUTER = "lib/minecraft/renderer/Outer"

    #: The pool every fixture opens with, so each index below is fixed; a case appends its own.
    POOL = (_utf8(SEALED), b"\x07\x00\x01",                   # 1, 2: this class
            _utf8(LISTED), b"\x07\x00\x03",                   # 3, 4: the subtype the listing names
            _utf8("java/lang/Object"), b"\x07\x00\x05",       # 5, 6: the superclass
            _utf8("m"), _utf8("()V"), _utf8("Code"),          # 7, 8, 9: the one method
            _utf8("PermittedSubclasses"), _utf8("StackMapTable"),
            _utf8("BootstrapMethods"), _utf8("InnerClasses"))  # 10, 11, 12, 13
    THIS, LISTED_NAME, SUB, OBJECT, NAME, VOID, CODE = 2, 3, 4, 6, 7, 8, 9
    PERMITTED, FRAMES, BOOTSTRAPS, INNER = 10, 11, 12, 13
    #: The index the first appended entry takes.
    NEXT = 14

    @staticmethod
    def _attribute(name: int, body: bytes) -> bytes:
        return struct.pack(">HI", name, len(body)) + body

    @classmethod
    def _file(cls, *, extra=(), code=b"\xb1", catch=(), frames=None, descriptor=VOID,
              permits=(SUB,), attributes=()) -> bytes:
        """A sealed class declaring one method and listing the given class entries.

        The pool is ``POOL`` then ``extra``; the method's body is ``code``, an exception table
        catching each of ``catch`` and, given ``frames``, a ``StackMapTable`` of that body. No
        ``permits`` writes no listing at all, and ``attributes`` are further class attributes,
        each whole.
        """
        handlers = b"".join(struct.pack(">HHHH", 0, len(code), 0, caught) for caught in catch)
        inside = [] if frames is None else [cls._attribute(cls.FRAMES, frames)]
        body = (struct.pack(">HHI", 2, 1, len(code)) + code       # max_stack, max_locals, length
                + struct.pack(">H", len(catch)) + handlers
                + struct.pack(">H", len(inside)) + b"".join(inside))
        method = (struct.pack(">HHHH", 0x0001, cls.NAME, descriptor, 1)
                  + cls._attribute(cls.CODE, body))
        tail = list(attributes)
        if permits is not None:
            tail.insert(0, cls._attribute(cls.PERMITTED, struct.pack(
                f">H{len(permits)}H", len(permits), *permits)))
        return (_pool_bytes(*cls.POOL, *extra)
                + struct.pack(">HHHH", 0x0421, cls.THIS, cls.OBJECT, 0)  # flags, this, super, none
                + struct.pack(">HH", 0, 1) + method                     # no field, one method
                + struct.pack(">H", len(tail)) + b"".join(tail))

    def _member_reference(self, owner: int, at: int = NEXT) -> tuple[bytes, ...]:
        """A ``NameAndType`` taking index ``at``, then a ``Methodref`` on ``owner`` through it."""
        return (b"\x0c" + struct.pack(">HH", self.NAME, self.VOID),
                b"\x0a" + struct.pack(">HH", owner, at))

    def test_a_type_only_the_listing_names_is_no_edge(self):
        """And the file keeps every other string, its own name among them.

        An operand that only equals the entry's index is no use of it, which is what a walk buys
        over a search for the index's two bytes.
        """
        sipush = b"\x11" + struct.pack(">H", self.SUB) + b"\xb1"
        for case, code in (("an empty body", b"\xb1"), ("a sipush of the index", sipush)):
            with self.subTest(case=case):
                found = reach.edge_strings(self._file(code=code))
                self.assertNotIn(self.LISTED, found)
                self.assertIn(self.SEALED, found)

    def test_a_file_without_a_listing_reads_every_string(self):
        """A class entry nothing references is read there, being how an inlined constant shows."""
        data = self._file(permits=None)
        self.assertEqual(reach.edge_strings(data), reach.utf8_entries(data))
        self.assertIn(self.LISTED, reach.edge_strings(data))

    def test_a_listed_type_a_member_reference_names_stays_an_edge(self):
        data = self._file(extra=self._member_reference(self.SUB))
        self.assertIn(self.LISTED, reach.edge_strings(data))

    def test_a_listed_type_an_instruction_names_stays_an_edge(self):
        sub = struct.pack(">H", self.SUB)
        for opcode, code in (("new", b"\xbb" + sub), ("anewarray", b"\xbd" + sub),
                             ("checkcast", b"\xc0" + sub), ("instanceof", b"\xc1" + sub),
                             ("multianewarray", b"\xc5" + sub + b"\x01"),
                             ("ldc", b"\x12" + bytes([self.SUB])), ("ldc_w", b"\x13" + sub)):
            with self.subTest(opcode=opcode):
                self.assertIn(self.LISTED, reach.edge_strings(self._file(code=code + b"\xb1")))

    def test_a_listed_type_a_catch_frame_or_bootstrap_names_stays_an_edge(self):
        sub = struct.pack(">H", self.SUB)
        # One frame, same_locals_1_stack_item at delta 0, its stack item an Object of the subtype.
        frames = struct.pack(">HBB", 1, 64, 7) + sub
        # One bootstrap method, its handle unread, its one argument the subtype.
        bootstrap = self._attribute(self.BOOTSTRAPS, struct.pack(">HHH", 1, 0, 1) + sub)
        for site, data in (("catch type", self._file(catch=(self.SUB,))),
                           ("stack-map entry", self._file(frames=frames)),
                           ("bootstrap argument", self._file(attributes=(bootstrap,)))):
            with self.subTest(site=site):
                self.assertIn(self.LISTED, reach.edge_strings(data))

    def test_a_listed_type_a_descriptor_names_stays_an_edge(self):
        """Its class entry is the listing's alone, and the descriptor is a string of its own."""
        returns = f"()L{self.LISTED};"
        found = reach.edge_strings(self._file(extra=(_utf8(returns),), descriptor=self.NEXT))
        self.assertIn(returns, found)
        self.assertNotIn(self.LISTED, found)

    def test_a_string_constant_sharing_the_name_keeps_it(self):
        """A ``String`` spelling the binary name points at the very entry the class entry does."""
        string = b"\x08" + struct.pack(">H", self.LISTED_NAME)
        self.assertIn(self.LISTED, reach.edge_strings(self._file(extra=(string,))))

    def test_a_listed_nested_type_takes_its_outer_name_with_it(self):
        """javac adds the outer's class entry only to write the nested type's InnerClasses entry."""
        nested, outer = self.NEXT + 1, self.NEXT + 3
        pool = (_utf8(f"{self.OUTER}$Nested"), b"\x07" + struct.pack(">H", self.NEXT),
                _utf8(self.OUTER), b"\x07" + struct.pack(">H", self.NEXT + 2),
                _utf8("Nested"))
        entry = self._attribute(self.INNER, struct.pack(">5H", 1, nested, outer, self.NEXT + 4,
                                                        0x0019))
        for case, extra, kept in (("nothing else names the outer", pool, False),
                                  ("a member reference names the outer",
                                   pool + self._member_reference(outer, self.NEXT + len(pool)),
                                   True)):
            with self.subTest(case=case):
                found = reach.edge_strings(
                    self._file(extra=extra, permits=(nested,), attributes=(entry,)))
                self.assertNotIn(f"{self.OUTER}$Nested", found)
                self.assertEqual(self.OUTER in found, kept)

    def test_the_walk_steps_over_switch_padding_and_wide(self):
        """Each is read with a width that moves with where it sits or what it widens.

        Every offset and key below is non-zero, so a walk that misses a switch's padding or sizes
        ``wide`` as an ordinary instruction reads one of them as an opcode or a case count and
        either refuses or walks past the ``instanceof`` - never back into step by luck.
        """
        code = (b"\x04"                                                  # 0: iconst_1
                + b"\xaa\x00\x00" + struct.pack(">5i", 23, 0, 1, 23, 23)  # 1: tableswitch, 2 pad
                + b"\x04"                                                # 24: iconst_1
                + b"\xab\x00\x00" + struct.pack(">4i", 19, 1, 7, 19)     # 25: lookupswitch, 2 pad
                + b"\xc4\x84" + struct.pack(">Hh", 1, -251)              # 44: wide iinc
                + b"\xc1" + struct.pack(">H", self.SUB)                  # 50: instanceof
                + b"\xb1")                                               # 53: return
        self.assertIn(self.LISTED, reach.edge_strings(self._file(code=code)))

    def test_an_opcode_with_no_width_refuses(self):
        """Rather than guessing one and walking on out of step."""
        with self.assertRaises(MissingInput):
            reach.edge_strings(self._file(code=b"\xcb\xb1"))


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
        """The generators' TEST tree, which is the one root of a scanned build left unscanned.

        Sharper than a wholly foreign path: its sibling ``tooling/src/main/java`` IS scanned, so this
        pins the boundary where it actually falls rather than where a directory name suggests it.
        """
        self.assertIsNone(reach.answered_by(self.PAYLOAD, "tooling/src/test/java/package-info.java"))

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


class TheRootsOverTheIndex(unittest.TestCase):
    """Every artifact the store index's ``artifacts`` map holds is rooted, or records why it is not.

    A derived rule plans off the committed graph, and the graph answers an artifact only through its
    root, so an indexed artifact with neither a root nor a recorded reason is left out of every
    derived plan and the plan says nothing about the loss. Read off the committed index rather than
    the capture roster, because coining an artifact writes its index row first, and off that one map
    because it is the one a plan names: the pointer, source and external maps register nothing a
    capture writes.
    """

    @classmethod
    def setUpClass(cls):
        cls.indexed = set(store.production(None, REPO).read("report.oracle-index")["artifacts"])

    def test_every_indexed_artifact_has_a_root_or_a_reason(self):
        missing = sorted(self.indexed - set(reach.ROOTS) - set(reach.UNROOTED))
        self.assertEqual(missing, [],
                         "indexed with neither a root in ROOTS nor a reason in UNROOTED")

    def test_no_artifact_is_both_rooted_and_unrooted(self):
        self.assertEqual(sorted(set(reach.ROOTS) & set(reach.UNROOTED)), [])

    def test_every_rooted_or_unrooted_artifact_is_indexed(self):
        """So a retired artifact takes its root or its reason with it.

        And the population: an index read as empty fails here rather than passing the first case.
        """
        self.assertEqual(sorted((set(reach.ROOTS) | set(reach.UNROOTED)) - self.indexed), [])

    def test_every_reason_says_something(self):
        for artifact, reason in reach.UNROOTED.items():
            with self.subTest(artifact=artifact):
                self.assertTrue(reason.strip())

    def test_the_walk_row_roots_where_the_animation_row_does(self):
        """One class run at a gait, so what either row can be moved by is what that class reaches."""
        self.assertEqual(reach.ROOTS.get("sweep.entity-walk"),
                         reach.ROOTS["sweep.entity-animation"])


class HeldDemotes(unittest.TestCase):
    """A held demote's carriers reach nothing it subtracts unless its ledger lists them.

    A demote takes its blind list back out of the plan on every path it fires on, so a carrier whose
    own reach holds one of those artifacts has it removed from the one plan that needed it, and the
    plan prints nothing about the loss. The ledger names the carriers that lose an artifact that way
    by decision, and the check names every path on which the ledger and the graph disagree.
    """

    FLOW = "tooling/src/main/java/lib/minecraft/renderer/tooling/ColorMapsFlow.java"
    RENDERER = "src/main/java/lib/minecraft/renderer/BlockRenderer.java"
    PATHS = (ENVELOPE, FLOW, RENDERER)

    #: The tooling demote alone with an empty ledger, which is how the shipped map holds it.
    HELD = {"tooling-blindness": frozenset()}

    @staticmethod
    def _rule(claim_key, triggers, blind, mode="demote", rid="B13"):
        return blindness.Rule(id=rid, claim="c", trigger_paths=tuple(triggers), sees=(),
                              blind=tuple(blind), reason="r", mode=mode, probe="p", source="s",
                              claim_key=claim_key)

    def _tooling(self, mode="demote"):
        return self._rule("tooling-blindness",
                          [ENVELOPE, "tooling/src/main/java/lib/minecraft/renderer/tooling/**"],
                          ["sweep.block", "sweep.entity"], mode=mode)

    @staticmethod
    def _payload(envelope, flow=("manifest.tooling-tables",)):
        return {"types": {
            "lib/minecraft/renderer/content/table/TableEnvelope": {"artifacts": list(envelope)},
            "lib/minecraft/renderer/tooling/ColorMapsFlow": {"artifacts": list(flow)},
            "lib/minecraft/renderer/BlockRenderer": {"artifacts": ["sweep.block", "sweep.entity"]}}}

    def test_a_carrier_reaching_only_what_the_demote_keeps_holds(self):
        """And a path the demote does not fire on is not held, whatever it reaches."""
        self.assertEqual(reach.self_demotions(self._payload(["manifest.tooling-tables"]),
                                              [self._tooling()], self.PATHS, self.HELD), [])

    def test_a_renderer_caller_of_the_envelope_is_named(self):
        """The case the check exists for: a renderer producer walks to the envelope, and the demote
        would take that producer's sweep back out of every plan the envelope is in."""
        found = reach.self_demotions(self._payload(["manifest.tooling-tables", "sweep.block"]),
                                     [self._tooling()], self.PATHS, self.HELD)
        self.assertEqual(len(found), 1, found)
        self.assertTrue(found[0].startswith(ENVELOPE), found)
        self.assertIn("sweep.block", found[0])
        self.assertNotIn("sweep.entity", found[0])
        self.assertIn("tooling-blindness", found[0])

    def test_a_carrier_a_package_declaration_puts_under_the_demote_is_held_too(self):
        found = reach.self_demotions(
            self._payload(["manifest.tooling-tables"], flow=("manifest.tooling-tables",
                                                             "sweep.entity")),
            [self._tooling()], self.PATHS, self.HELD)
        self.assertEqual(len(found), 1, found)
        self.assertTrue(found[0].startswith(self.FLOW), found)

    def test_a_demote_that_is_not_held_is_not_checked(self):
        """A claim the ledger map does not name is not read, whatever its carriers reach."""
        other = self._rule("unheld-claim", ["src/main/java/lib/minecraft/renderer/*"],
                           ["sweep.block"], rid="B99")
        self.assertEqual(reach.self_demotions(self._payload(["manifest.tooling-tables"]),
                                              [self._tooling(), other], self.PATHS, self.HELD),
                         [])

    def test_a_listed_carrier_reaching_what_the_demote_subtracts_holds(self):
        """The ledger is the decision: a carrier it lists may lose what the demote takes back."""
        self.assertEqual(
            reach.self_demotions(self._payload(["manifest.tooling-tables", "sweep.block"]),
                                 [self._tooling()], self.PATHS,
                                 {"tooling-blindness": frozenset({ENVELOPE})}), [])

    def test_an_unlisted_carrier_is_named_beside_a_listed_one(self):
        """A ledger is no licence for its claim: a new carrier or a new edge is still refused."""
        found = reach.self_demotions(
            self._payload(["manifest.tooling-tables", "sweep.block"],
                          flow=("manifest.tooling-tables", "sweep.entity")),
            [self._tooling()], self.PATHS, {"tooling-blindness": frozenset({ENVELOPE})})
        self.assertEqual(len(found), 1, found)
        self.assertTrue(found[0].startswith(self.FLOW), found)
        self.assertIn("sweep.entity", found[0])
        self.assertIn("does not list it", found[0])

    def test_a_listed_carrier_that_reaches_nothing_the_demote_subtracts_is_named(self):
        """So a ledger only shrinks: the entry whose edge was cut comes off with it."""
        found = reach.self_demotions(self._payload(["manifest.tooling-tables"]),
                                     [self._tooling()], self.PATHS,
                                     {"tooling-blindness": frozenset({ENVELOPE})})
        self.assertEqual(len(found), 1, found)
        self.assertTrue(found[0].startswith(ENVELOPE), found)
        self.assertIn("reaches nothing", found[0])

    def test_a_listed_path_the_demote_does_not_fire_on_is_named(self):
        """An entry the claim no longer reaches holds nothing, whatever the path itself reaches.

        The renderer reaches both artifacts the fixture's demote subtracts and the demote does not
        fire on it, so reaching one is not what makes a path a carrier.
        """
        found = reach.self_demotions(self._payload(["manifest.tooling-tables"]),
                                     [self._tooling()], self.PATHS,
                                     {"tooling-blindness": frozenset({self.RENDERER})})
        self.assertEqual(len(found), 1, found)
        self.assertTrue(found[0].startswith(self.RENDERER), found)
        self.assertIn("is not one of", found[0])

    def test_a_carrier_the_graph_predates_answers_nothing_here(self):
        """The comparison beside this one names a type the graph has no row for."""
        payload = self._payload(["manifest.tooling-tables"])
        del payload["types"]["lib/minecraft/renderer/content/table/TableEnvelope"]
        self.assertEqual(
            reach.self_demotions(payload, [self._tooling()], self.PATHS, self.HELD), [])

    def test_a_held_claim_no_demote_rule_carries_is_refused(self):
        """A renamed claim or a changed mode would otherwise leave the check holding nothing."""
        with self.assertRaises(MissingInput):
            reach.self_demotions(self._payload(["manifest.tooling-tables"]),
                                 [self._tooling(mode="select")], self.PATHS, self.HELD)

    def test_a_held_demote_firing_on_no_source_path_is_refused(self):
        with self.assertRaises(MissingInput):
            reach.self_demotions(self._payload(["manifest.tooling-tables"]), [self._tooling()],
                                 [self.RENDERER], self.HELD)


class TheHeldDemotesOverTheShippedTree(unittest.TestCase):
    """The shipped map, its triggers derived from the tree, beside the committed graph."""

    @classmethod
    def setUpClass(cls):
        rules, _ = blindness.load(REPO / store.PRODUCTION)
        cls.rules = declarations.live(rules, REPO)
        cls.payload = read_json(REPO / "parity" / reach.STORED)

    def test_every_held_demote_agrees_with_its_ledger(self):
        """No unlisted carrier reaches what its demote subtracts, and no listed path has stopped."""
        self.assertEqual(
            reach.self_demotions(self.payload, self.rules, reach.source_paths(REPO)), [])

    def test_the_envelope_is_held(self):
        """The population, so the case above is not vacuously true of a claim nothing carries."""
        self.assertIn("tooling-blindness", reach.HELD_DEMOTES)
        (rule,) = [one for one in self.rules if one.claim_key == "tooling-blindness"]
        self.assertEqual(rule.mode, "demote")
        self.assertTrue(blindness.matches(ENVELOPE, rule.trigger_paths))
        self.assertIn(ENVELOPE, reach.source_paths(REPO))
        self.assertIsNotNone(reach.answered_by(self.payload, ENVELOPE))

    def test_every_demote_firing_on_a_source_path_is_held(self):
        """The claims the ledger is kept for, so a demote coined without an entry fails here.

        A claim-keyed demote firing on no scanned source path leaves the graph no row to check, and
        a demote with no claim_key cannot be named by one, so neither is expected in the map.
        """
        paths = reach.source_paths(REPO)
        firing = {rule.claim_key for rule in self.rules
                  if rule.mode == "demote" and rule.claim_key
                  and any(blindness.matches(path, rule.trigger_paths) for path in paths)}
        self.assertEqual(sorted(firing), sorted(reach.HELD_DEMOTES))


@unittest.skipUnless(all((REPO / root).is_dir() for root in reach.CLASS_ROOTS),
                     "needs every class root compiled")
class OverTheRealTree(unittest.TestCase):
    """The properties the import graph got wrong in both directions, over the tree itself.

    Skipped unless every class root is compiled, because ``reach.build`` passes over a missing root
    rather than refusing it: over a partly compiled tree - a fresh worktree after ``./gradlew :test``,
    which never compiles the generators - these cases would judge a graph missing every edge out of
    the absent roots, and fail on a type whose only reach runs through one.
    """

    @classmethod
    def setUpClass(cls):
        cls.graph = reach.build(REPO)

    def _binary(self, simple: str) -> str:
        return next(n for n in self.graph.declared if n.rsplit("/", 1)[1] == simple)

    def _artifacts(self, simple: str) -> set[str]:
        return set(self.graph.artifacts.get(self._binary(simple), frozenset()))

    def test_an_entity_only_kit_reaches_no_item_or_block_sweep(self):
        """The saving. PosePlayer answers six artifacts and owes the item sweep none."""
        found = self._artifacts("PosePlayer")
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
        context = "lib/minecraft/renderer/content/index/RendererContext"
        self.assertIn(context, self.graph.declared)
        self.assertNotIn("lib/minecraft/renderer/BlockRenderer", self.graph.edges.get(context, ()))

    def test_a_menu_type_does_not_reach_the_entity_sweep(self):
        self.assertNotIn("sweep.entity", self._artifacts("ScreenMetrics"))

    def test_the_wiring_seams_are_declared_and_read(self):
        for simple in ("RendererContext", "IndexedRendererContext",
                       "MapRendererContext", "RenderOptions"):
            name = next(n for n in self.graph.declared if n.rsplit("/", 1)[1] == simple)
            self.assertIn(name, self.graph.ignored)

    def test_reach_does_not_compose_through_a_wiring_seam(self):
        """The collapse this exists to stop.

        `RendererContext` DECLARES an entity lookup, so before the seam a menu sweep reached the
        whole entity surface across it - a declared capability read as an exercised one.
        """
        self.assertNotIn("sweep.menu", self._artifacts("Entity"))
        self.assertNotIn("sweep.entity", self._artifacts("ScreenMetrics"))

    def test_a_change_TO_a_seam_is_still_seen(self):
        """Outgoing edges only. `RendererContext` ships 21 default bodies beside its abstract
        members, and a change to one of those moves output with no implementor edit to carry it."""
        found = self._artifacts("RendererContext")
        self.assertIn("sweep.entity", found)
        self.assertIn("sweep.menu", found)

    def test_what_a_seam_INTERFACE_calls_survives_its_cut(self):
        """The other half of that sentence, one level down.

        `RendererContext` resolves a redstone tint in a DEFAULT body, so the type is reached from
        code with no implementor to carry a change to it - and cutting the interface whole answered
        that nothing at all sees it.
        """
        found = self._artifacts("RedstoneTint")
        self.assertIn("sweep.block", found)
        self.assertIn("sweep.entity", found)

    def test_a_frame_at_a_tick_is_seen_by_the_render_that_samples_it(self):
        """What keeps the flipbook out of the cut's blind spot: the context holds no derived default.

        `findFlipbook` returns a `Flipbook`, so the type is on the interface's declaration surface and
        a default body sampling it would lose its edge to the cut. The frame at a tick is
        `Flipbook.atTick` at the call site instead, so the fluid, which draws every frame through it,
        names the flipbook itself and the graph sees the edge.
        """
        self.assertIn("manifest.fluid", self._artifacts("Flipbook"))
        self.assertIn("pin.fluid-crc", self._artifacts("Flipbook"))

    def test_what_a_seam_INTERFACE_declares_does_not(self):
        """The collapse itself: a declared entity lookup is not an exercised one."""
        self.assertNotIn("sweep.menu", self._artifacts("Entity"))

    def test_no_library_type_reaches_nothing_without_saying_so(self):
        """The live guarantee, and the whole of what a reach declaration is for.

        Two very different things answer the empty set - a renderer this store holds no artifact for,
        and a type reached by an edge the graph cannot see, across a wiring seam or out of a service
        file. The first is correct and the second is a gate quietly not running.
        """
        self.assertEqual(reach.unexplained(REPO, self.graph), [])

    def test_a_reach_declaration_is_what_explains_one(self):
        """And the population, so the case above is not vacuously true of a tree with no orphans."""
        explained = reach.declared_reach(REPO, self.graph.declared)
        orphans = {name for name in reach.orphans(self.graph)}
        named = sorted(n.rsplit("/", 1)[1] for n in explained if n in orphans)
        self.assertIn("LayoutRenderer", named)
        self.assertIn("RendererGsonContributor", named)

    def test_a_subject_beside_a_claim_is_not_a_reach(self):
        """It says which renderers that CLAIM is about, which is a different statement.

        Read as a reach, a claim's own decoration would explain an orphan nobody had looked at.
        """
        menu = next(n for n in self.graph.declared if n.rsplit("/", 1)[1] == "MenuRenderer")
        self.assertNotIn(menu, reach.declared_reach(REPO, self.graph.declared))

    def test_a_seam_that_is_a_CLASS_is_cut_whole(self):
        """It has no split to make - every reference a class holds is one it makes.

        Measured rather than assumed: cutting the concrete context by its declaration instead takes
        the tree from 29 engine-wide types to 151, which is the collapse the seam exists against.
        """
        for simple in ("IndexedRendererContext", "MapRendererContext"):
            name = next(n for n in self.graph.declared if n.rsplit("/", 1)[1] == simple)
            self.assertIn(name, self.graph.ignored, simple)
            self.assertEqual(self.graph.edges.get(name, frozenset()), frozenset(), simple)

    def test_a_sealed_supertype_does_not_reach_what_it_permits(self):
        """`ContentException` names `ColorMapException` in its permits list and nowhere else.

        Read as an edge, the listing made every producer reaching the supertype reach the subtype,
        which only the colormap loader throws.
        """
        sealed = self._binary("ContentException")
        self.assertNotIn(self._binary("ColorMapException"), self.graph.edges.get(sealed, ()))
        found = self._artifacts("ColorMapException")
        self.assertIn("digest.colormap-lut", found)
        self.assertNotIn("sweep.block", found)

    def test_a_permitted_subtype_keeps_what_reaches_it(self):
        """The population, so the case above is not vacuous over a graph that lost every subtype.

        `PoseNode` names `PoseExpr` in its permits list alone, and the entity sweep still reaches
        `PoseExpr` through the renderer's pose player.
        """
        sealed = self._binary("PoseNode")
        self.assertNotIn(self._binary("PoseExpr"), self.graph.edges.get(sealed, ()))
        self.assertIn("sweep.entity", self._artifacts("PoseExpr"))


if __name__ == "__main__":
    unittest.main()
