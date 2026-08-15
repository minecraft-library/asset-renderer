"""Reading ``@Parity`` out of source: the lexer, the refusals, and what a clean tree derives.

One fixture file per refused shape, under ``data/declarations``. Each refusal has zero instances in
the tree, which is what makes refusing it free - and what makes a fixture the only thing keeping it
from quietly becoming a wrong answer at some later edit. The fixtures are real Java a person can
open; nothing compiles them, because they live under ``scripts/``.
"""

import tempfile
import unittest
from pathlib import Path

from parity import blindness, declarations
from parity.declarations import Claim, DeclarationError
from parity.norm import write_json

FIXTURES = Path(__file__).resolve().parent / "data" / "declarations"
REPO = Path(__file__).resolve().parents[3]

#: The shipped vocabularies, so a fixture is parsed against the constants the tree really declares.
VOCABULARY = declarations.vocabularies(REPO)


def scan(name):
    """One fixture tree, whose library root is its ``root`` package."""
    return declarations.scan(FIXTURES / name, source_root=".", library_root="root",
                             vocabulary=VOCABULARY)


def refusal(case, name):
    """The refusal a fixture produces, for a case that means to read its message."""
    with case.assertRaises(DeclarationError) as raised:
        scan(name)
    return raised.exception


class Lexer(unittest.TestCase):
    """Blanking comments and strings without moving a single offset."""

    def test_offsets_and_lines_are_preserved(self):
        source = 'a\n// b\n/* c\nd */\n"e"\n'
        blanked = declarations.blank(source)
        self.assertEqual(len(blanked), len(source))
        self.assertEqual(blanked.count("\n"), source.count("\n"))

    def test_a_line_comment_is_blanked_to_its_newline(self):
        self.assertEqual(declarations.blank("x // @Parity\ny"), "x" + " " * 11 + "\ny")

    def test_a_block_comment_spanning_lines_keeps_its_newlines(self):
        self.assertEqual(declarations.blank("/* a\nb */x"), " " * 4 + "\n" + " " * 4 + "x")

    def test_a_star_slash_inside_a_string_opens_no_comment(self):
        """The live counter-example: a printf format carrying `renderer/*.json`."""
        source = 'String f = "renderer/*.json override";\nint keep = 1;\n'
        blanked = declarations.blank(source)
        self.assertIn("int keep = 1;", blanked)
        self.assertNotIn("renderer", blanked)

    def test_an_escaped_quote_does_not_close_a_string(self):
        self.assertNotIn("kept", declarations.blank('"a \\" kept"'))

    def test_a_text_block_is_blanked_whole(self):
        source = 'var t = """\n@Parity(claim = "x")\n""";\nint keep = 1;\n'
        blanked = declarations.blank(source)
        self.assertNotIn("@Parity", blanked)
        self.assertIn("int keep = 1;", blanked)

    def test_a_char_literal_is_blanked(self):
        self.assertNotIn("'", declarations.blank("char c = '\"';\n"))

    def test_an_unterminated_literal_stops_at_the_newline(self):
        """One bad line does not blank the rest of the file, which is the precedent's own defect."""
        self.assertIn("int keep = 1;", declarations.blank('String s = "oops\nint keep = 1;\n'))


class Vocabularies(unittest.TestCase):
    """The closed vocabularies are read off the enums rather than transcribed."""

    def test_the_three_are_read_from_their_own_sources(self):
        self.assertEqual(VOCABULARY["Mode"], ("SELECT", "DEMOTE", "SUPPRESS"))
        self.assertEqual(VOCABULARY["Scope"], ("PACKAGE", "SUBTREE"))
        self.assertIn("MENU", VOCABULARY["Subject"])

    def test_the_subject_roster_is_every_renderer(self):
        shipped = sorted(
            path.name[: -len("Renderer.java")].upper()
            for path in (REPO / declarations.SOURCE_ROOT / declarations.LIBRARY_ROOT).glob("*Renderer.java")
            if path.name != "Renderer.java")
        self.assertEqual(sorted(VOCABULARY["Subject"]), shipped)


class ProseIsReportedAndNotCounted(unittest.TestCase):
    """The one non-fatal answer, and the one that will actually fire."""

    def test_a_parity_in_a_javadoc_or_a_string_is_named_rather_than_refused(self):
        result = scan("in-prose")
        self.assertEqual(result.declarations, [])
        self.assertEqual([one.line for one in result.reports], [4, 10])
        self.assertEqual({one.where for one in result.reports}, {"a comment or a string literal"})


class TheJoin(unittest.TestCase):
    """Exactly one of `claim` and `as`, and the indirection is one level deep."""

    def test_both_is_refused(self):
        self.assertIn("names both a claim and an as", refusal(self, "both-joins").shape)

    def test_neither_is_refused(self):
        self.assertIn("names neither a claim nor an as", refusal(self, "neither-join").shape)

    def test_an_as_naming_a_joiner_is_refused(self):
        self.assertIn("joins by 'as' itself", refusal(self, "as-chain").shape)

    def test_an_as_naming_its_own_type_is_refused(self):
        self.assertIn("names this file's own type", refusal(self, "as-self").shape)

    def test_an_as_naming_an_undeclared_type_is_refused(self):
        self.assertIn("carries no declaration", refusal(self, "as-unknown").shape)


class TheTarget(unittest.TestCase):
    """A package declaration or a top-level type, and nothing else."""

    def test_a_nested_type_is_refused(self):
        self.assertIn("inside a type body", refusal(self, "nested-type").shape)

    def test_a_declaration_with_nothing_below_it_is_refused(self):
        self.assertIn("declares nothing", refusal(self, "declares-nothing").shape)

    def test_a_package_declaration_outside_package_info_is_refused(self):
        self.assertIn("not in package-info.java",
                      refusal(self, "package-not-in-package-info").shape)

    def test_a_package_info_that_also_declares_a_type_is_refused(self):
        self.assertIn("also declares a type", refusal(self, "package-info-with-type").shape)


class TheArgumentList(unittest.TestCase):
    """One line, balanced, and nothing outside the vocabulary."""

    def test_the_container_spelling_is_refused(self):
        exception = refusal(self, "container")
        self.assertIn("container spelling", exception.shape)
        self.assertIn("stack two @Parity lines", exception.fix)

    def test_a_wrapped_argument_list_is_refused(self):
        self.assertIn("spans more than one line", refusal(self, "multi-line").shape)

    def test_an_unbalanced_argument_list_is_refused(self):
        self.assertIn("never closes", refusal(self, "unbalanced").shape)

    def test_an_unknown_member_is_refused(self):
        self.assertIn("not a member", refusal(self, "unknown-member").shape)

    def test_an_unknown_enum_constant_is_refused(self):
        self.assertIn("declares no constant 'HIDE'", refusal(self, "unknown-constant").shape)


class TheScope(unittest.TestCase):
    """Read on a package declaration alone, and narrow on the library root alone."""

    def test_a_scope_on_a_type_is_refused(self):
        self.assertIn("'scope' is written on a type", refusal(self, "scope-on-type").shape)

    def test_a_package_scope_off_the_library_root_is_refused(self):
        self.assertIn("PACKAGE outside the library root",
                      refusal(self, "scope-package-off-root").shape)


class OneClaimReachesOnePathOnce(unittest.TestCase):
    """Refuse rather than merging, picking or last-winning."""

    def test_two_declarations_of_one_claim_in_one_file_are_refused(self):
        self.assertIn("declared twice in this file", refusal(self, "twice-in-one-file").shape)

    def test_a_package_and_a_type_inside_it_are_refused(self):
        exception = refusal(self, "package-and-type-inside")
        self.assertIn("already declared by", exception.shape)
        self.assertIn("adds nothing to the union", exception.fix)


class AgainstTheMap(unittest.TestCase):
    """The three refusals that need the stored row beside the source."""

    def test_a_slug_no_rule_carries_is_refused(self):
        with self.assertRaises(DeclarationError) as raised:
            declarations.verify(scan("unknown-slug"),
                                [Claim("a-claim", "select", ("root/**",))], ["root/Unheard.java"])
        self.assertIn("no rule carries the claim 'no-such-claim'", raised.exception.shape)

    def test_a_narrowing_declaration_that_omits_its_mode_is_refused(self):
        with self.assertRaises(DeclarationError) as raised:
            declarations.verify(scan("narrowing-no-mode"),
                                [Claim("narrow", "demote", ("root/**",))],
                                ["root/package-info.java"])
        self.assertIn("does not say so", raised.exception.shape)

    def test_a_narrowing_package_that_leaves_its_scope_at_the_default_is_refused(self):
        with self.assertRaises(DeclarationError) as raised:
            declarations.verify(scan("narrowing-no-scope"),
                                [Claim("narrow", "demote", ("root/**",))],
                                ["root/package-info.java"])
        self.assertIn("scope is left at the default", raised.exception.shape)

    def test_a_subtraction_no_other_claim_reaches_is_refused(self):
        with self.assertRaises(DeclarationError) as raised:
            declarations.verify(scan("subtraction-from-nothing"),
                                [Claim("narrow", "demote", ("root/Lonely.java",))],
                                ["root/Lonely.java"])
        self.assertIn("subtracts on paths no other claim reaches", raised.exception.shape)

    def test_a_subtraction_another_claim_reaches_is_accepted(self):
        declarations.verify(scan("subtraction-from-nothing"),
                            [Claim("narrow", "demote", ("root/Lonely.java",)),
                             Claim("wide", "select", ("root/**",))],
                            ["root/Lonely.java"])


class ACleanTree(unittest.TestCase):
    """What the reader answers when nothing is wrong, which is the whole of its output."""

    def setUp(self):
        self.result = scan("clean")

    def test_a_package_declaration_derives_its_subtree(self):
        self.assertEqual(declarations.derive(self.result)["a-claim"], ["root/**"])

    def test_a_type_declaration_derives_its_own_path(self):
        self.assertEqual(declarations.derive(self.result)["b-claim"],
                         ["root/Anchor.java", "root/Joiner.java", "root/below/Deep.java"])

    def test_a_join_by_as_resolves_to_the_anchors_slug(self):
        joiner = next(one for one in self.result.declarations if one.path.endswith("Joiner.java"))
        self.assertEqual(joiner.claim, "b-claim")
        self.assertEqual(joiner.joins, "Anchor")

    def test_a_joining_declaration_writes_no_subject_and_the_anchor_does(self):
        by_path = {one.path: one for one in self.result.declarations}
        self.assertEqual(by_path["root/Anchor.java"].subject, ("MENU",))
        self.assertEqual(by_path["root/Joiner.java"].subject, ())
        self.assertNotIn("subject", by_path["root/Joiner.java"].written)

    def test_a_package_scope_derives_the_packages_own_files(self):
        """The narrow arm has one legal carrier in the tree, so it is exercised on the property."""
        narrow = declarations.Declaration(
            path="root/package-info.java", line=1, on="package", claim="a-claim", joins="",
            mode="SELECT", scope="PACKAGE", subject=(), written=frozenset({"claim", "scope"}))
        self.assertEqual(narrow.trigger_path, "root/*")


class ModeAgreement(unittest.TestCase):
    """Read off what the source wrote, before any default is materialised."""

    def test_a_claim_whose_carriers_agree_reports_nothing(self):
        self.assertEqual(
            declarations.mode_disagreements(scan("clean"),
                                            [Claim("a-claim", "select", ()),
                                             Claim("b-claim", "select", ())]),
            [])

    def test_a_carrier_writing_a_mode_the_row_does_not_carry_is_reported(self):
        self.assertEqual(
            declarations.mode_disagreements(scan("clean"),
                                            [Claim("a-claim", "select", ()),
                                             Claim("b-claim", "demote", ())]),
            ["b-claim: a carrier wrote SELECT and the row carries demote"])

    def test_a_joiner_declining_to_repeat_a_subject_is_not_a_disagreement(self):
        """The comparison runs on what was written, so an unwritten member asserts nothing."""
        result = scan("clean")
        joiner = next(one for one in result.declarations if one.path.endswith("Joiner.java"))
        self.assertNotIn("mode", joiner.written)
        self.assertEqual(declarations.mode_disagreements(result, [Claim("b-claim", "select", ())]),
                         [])


class TheShippedTree(unittest.TestCase):
    """The reader against the source root it is really for."""

    def test_the_vocabulary_package_declares_itself(self):
        result = declarations.scan(REPO)
        self.assertEqual(declarations.derive(result),
                         {"parity-vocabulary": [f"{declarations.VOCABULARY}/**"]})

    def test_nothing_in_the_tree_is_a_mention_the_reader_declines(self):
        self.assertEqual(declarations.scan(REPO).reports, [])


class TheShippedMap(unittest.TestCase):
    """The two halves of every trigger list, against the tree and against the rows."""

    STORE = REPO / "src/test/resources/lib/minecraft/renderer/parity"

    def rules(self):
        return blindness.load(self.STORE)[0]

    def test_the_generated_triggers_reproduce_from_the_tree(self):
        """A hand-edited generated array states a reach no declaration declares, and the next run
        of the generator reverts it in silence."""
        self.assertEqual(declarations.regenerate(REPO, self.STORE, check=True), [])

    def test_every_declaration_of_a_claim_agrees_on_its_mode(self):
        """Two carriers of one claim writing different modes, or one writing a mode the row does
        not carry, leaves a reader at the call site reading a mode the claim no longer has."""
        self.assertEqual(
            declarations.mode_disagreements(declarations.scan(REPO),
                                            declarations.claims_of(self.rules())),
            [])

    def test_every_rule_declares_the_half_no_declaration_can_derive(self):
        """The generator refuses a rule without one rather than reading an absent list as empty,
        which would silently generate away every path that rule authored."""
        self.assertTrue(all(isinstance(rule.authored_paths, tuple) for rule in self.rules()))
        self.assertTrue(any(rule.authored_paths for rule in self.rules()))

    def test_a_rule_missing_its_authored_half_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Path(directory)
            write_json(store / "blindness.json",
                       {"artifact": "roster.blindness-rules", "format": 1, "key": "id",
                        "kind": "blindness-roster", "no_reach": [],
                        "rules": [{"id": "B1", "claim": "c", "mode": "select", "probe": "p",
                                   "reason": "r", "sees": [], "blind": [], "source": "s",
                                   "trigger_paths": ["a/**"]}]})
            with self.assertRaises(DeclarationError) as raised:
                declarations.regenerate(REPO, store, check=True)
        self.assertIn("carries no authored_paths", raised.exception.shape)

    def test_every_trigger_list_is_the_sorted_union_of_its_two_halves(self):
        derived = declarations.derive(declarations.scan(REPO))
        for rule in self.rules():
            self.assertEqual(
                list(rule.trigger_paths),
                sorted(set(rule.authored_paths) | set(derived.get(rule.claim_key, ()))),
                rule.id)


if __name__ == "__main__":
    unittest.main()
