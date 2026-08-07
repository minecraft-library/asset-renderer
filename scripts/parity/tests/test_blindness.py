"""Reach resolution: the glob grammar, the union, and the two post-union passes."""

import re
import tempfile
import unittest
from pathlib import Path

from parity import blindness
from parity.norm import MissingInput, write_json


def rule(rid, triggers, sees=(), blind=(), mode="select"):
    return blindness.Rule(id=rid, claim="c", trigger_paths=tuple(triggers), sees=tuple(sees),
                          blind=tuple(blind), reason="r", mode=mode, probe="p", source="s")


class GlobGrammar(unittest.TestCase):
    """The grammar is written twice, here and in Java. These are the cases they must agree on."""

    def test_star_does_not_span_segments(self):
        self.assertTrue(blindness.matches("a/b.java", ["a/*.java"]))
        self.assertFalse(blindness.matches("a/b/c.java", ["a/*.java"]))

    def test_double_star_spans_segments(self):
        self.assertTrue(blindness.matches("a/b/c/d.java", ["a/**"]))
        self.assertTrue(blindness.matches("a/b.java", ["a/**"]))

    def test_double_star_mid_pattern_matches_zero_segments(self):
        self.assertTrue(blindness.matches("a/Foo.java", ["a/**/Foo.java"]))
        self.assertTrue(blindness.matches("a/b/c/Foo.java", ["a/**/Foo.java"]))

    def test_double_star_glued_to_a_name(self):
        # `pipeline/**Catharsis*.java` has to reach a nested CatharsisConfig.
        self.assertTrue(blindness.matches("p/pack/CatharsisConfig.java", ["p/**Catharsis*.java"]))

    def test_a_dot_is_literal(self):
        self.assertFalse(blindness.matches("axjava", ["a.java"]))


class Union(unittest.TestCase):

    def test_sees_is_the_union_of_every_fired_rule(self):
        reach = blindness.resolve(["a/x.java"], [rule("A", ["a/**"], sees=["sweep.block"]),
                                                 rule("B", ["a/**"], sees=["sweep.item"])])
        self.assertEqual(reach.sees, ["sweep.block", "sweep.item"])

    def test_a_rule_that_does_not_match_contributes_nothing(self):
        reach = blindness.resolve(["a/x.java"], [rule("A", ["b/**"], sees=["sweep.block"])])
        self.assertEqual(reach.sees, [])
        self.assertEqual(reach.fired, [])

    def test_a_select_rules_blind_list_does_not_subtract(self):
        """The reason the corpus's own worked example resolves the way it does."""
        reach = blindness.resolve(["a/x.java"], [
            rule("A", ["a/**"], sees=[], blind=["sweep.block"]),
            rule("B", ["a/**"], sees=["sweep.block"]),
        ])
        self.assertEqual(reach.sees, ["sweep.block"])

    def test_an_overruled_claim_is_reported_rather_than_dropped(self):
        """The bundle is unchanged; what changes is the ANSWER to 'what is blind here'.

        Dropping it made a rule whose entire content is one blind line print no blind line at all,
        which is a wrong answer rather than a quiet one.
        """
        reach = blindness.resolve(["a/x.java"], [
            rule("A", ["a/**"], sees=[], blind=["sweep.block"]),
            rule("B", ["a/**"], sees=["sweep.block"]),
        ])
        self.assertEqual(reach.blind, [{"artifact": "sweep.block", "reason": "r", "rule": "A",
                                        "selected_by": ["B"]}])

    def test_an_uncontested_claim_names_no_selector(self):
        """Empty rather than absent, so a reader never has to tell the two states apart by key."""
        reach = blindness.resolve(["a/x.java"], [
            rule("A", ["a/**"], sees=["sweep.item"], blind=["sweep.block"]),
        ])
        self.assertEqual(reach.blind, [{"artifact": "sweep.block", "reason": "r", "rule": "A",
                                        "selected_by": []}])

    def test_each_overruled_claimant_keeps_its_own_row(self):
        """The contradiction is a fact about the PAIR, so collapsing them hides one rule's claim."""
        reach = blindness.resolve(["a/x.java"], [
            rule("A", ["a/**"], sees=[], blind=["sweep.block"]),
            rule("B", ["a/**"], sees=[], blind=["sweep.block"]),
            rule("C", ["a/**"], sees=["sweep.block"]),
        ])
        self.assertEqual([(e["rule"], e["selected_by"]) for e in reach.blind],
                         [("A", ["C"]), ("B", ["C"])])

    def test_two_claimants_on_one_artifact_print_in_rule_order(self):
        """Handed over in descending id order, which is the shape a map's file order can have.

        Rows are built in ``fired`` order - the order the rules sit in the file, which is no order at
        all over their ids - so without the tie-break the printed sequence is a property of where
        somebody last inserted a rule rather than of what the map says.
        """
        reach = blindness.resolve(["a/x.java"], [
            rule("Z", ["a/**"], sees=[], blind=["sweep.block"]),
            rule("A", ["a/**"], sees=[], blind=["sweep.block"]),
            rule("SEL", ["a/**"], sees=["sweep.block"]),
        ])
        self.assertEqual([e["rule"] for e in reach.blind], ["A", "Z"])

    def test_a_rule_naming_one_artifact_both_ways_never_cites_itself(self):
        """The shipped map forbids this and a guard over that file holds it to it; this takes any list.

        A row reading "claimed blind, selected by <the claimant>" sends a reader to the rule that made
        the claim to find out what overruled it, so the claimant is excluded here rather than assumed
        away by a precondition one caller happens to satisfy.
        """
        reach = blindness.resolve(["a/x.java"], [
            rule("A", ["a/**"], sees=["sweep.block"], blind=["sweep.block"]),
            rule("B", ["a/**"], sees=["sweep.block"]),
        ])
        self.assertEqual([(e["rule"], e["selected_by"]) for e in reach.blind], [("A", ["B"])])

    def test_an_uncontested_claim_is_reported_once_across_rules(self):
        """Two rules agreeing that nothing sees it is one fact about the artifact, not two."""
        reach = blindness.resolve(["a/x.java"], [
            rule("A", ["a/**"], sees=["sweep.item"], blind=["sweep.block"]),
            rule("B", ["a/**"], sees=["sweep.item"], blind=["sweep.block"]),
        ])
        self.assertEqual([e["rule"] for e in reach.blind], ["A"])

    def test_a_demotion_that_lands_is_not_an_overruled_claim(self):
        """It removed the artifact, so naming the rule that had selected it would be false."""
        reach = blindness.resolve(["a/x.java"], [
            rule("SEL", ["a/**"], sees=["sweep.block"]),
            rule("DEM", ["a/**"], blind=["sweep.block"], mode="demote"),
        ])
        self.assertEqual(reach.sees, [])
        self.assertEqual([e["selected_by"] for e in reach.blind], [[]])


class PostUnionPasses(unittest.TestCase):

    def test_demote_removes_what_another_rule_selected(self):
        reach = blindness.resolve(["a/x.java"], [
            rule("SEL", ["a/**"], sees=["sweep.block", "sweep.item"]),
            rule("DEM", ["a/**"], sees=[], blind=["sweep.block"], mode="demote"),
        ])
        self.assertEqual(reach.sees, ["sweep.item"])
        self.assertEqual([entry["artifact"] for entry in reach.blind], ["sweep.block"])

    def test_demotion_is_order_independent(self):
        """Taking a demotion inline would make the answer depend on where the rule sits in the file."""
        rules = [rule("DEM", ["a/**"], blind=["sweep.block"], mode="demote"),
                 rule("SEL", ["a/**"], sees=["sweep.block", "sweep.item"])]
        self.assertEqual(blindness.resolve(["a/x.java"], rules).sees, ["sweep.item"])
        self.assertEqual(blindness.resolve(["a/x.java"], list(reversed(rules))).sees, ["sweep.item"])

    def test_suppress_outranks_a_selection(self):
        reach = blindness.resolve(["a/x.java"], [
            rule("SEL", ["a/**"], sees=["sweep.block"]),
            rule("SUP", ["a/**"], blind=["sweep.block"], mode="suppress"),
        ])
        self.assertEqual(reach.sees, [])

    def test_a_suppression_removes_what_its_own_sees_names(self):
        """Both of its lists are inadmissible, which is the whole difference from a demotion.

        A demotion contributes its ``sees`` and takes away its ``blind``; a suppression takes away
        both, so naming an artifact anywhere on a suppress rule is what makes it inadmissible. Read
        only the ``blind`` half and this rule quietly turns into a selection of the very thing it
        suppresses.
        """
        reach = blindness.resolve(["a/x.java"], [
            rule("SEL", ["a/**"], sees=["sweep.block"]),
            rule("SUP", ["a/**"], sees=["sweep.block"], mode="suppress"),
        ])
        self.assertEqual(reach.sees, [])

    def test_a_demotion_answers_for_its_own_paths_and_no_others(self):
        """The reason resolution is per-path: reach has to be monotone in the change set.

        `b/y.java` is genuinely reached by SEL and nothing about it is demoted. Resolving the set as
        one union let DEM cancel it from across the commit, so committing a blind file beside a
        reaching one planned nothing at all - the ordinary shape of work in a package holding both.
        """
        rules = [rule("SEL", ["a/**", "b/**"], sees=["sweep.block"]),
                 rule("DEM", ["a/**"], blind=["sweep.block"], mode="demote")]
        self.assertEqual(blindness.resolve(["a/x.java"], rules).sees, [])
        self.assertEqual(blindness.resolve(["b/y.java"], rules).sees, ["sweep.block"])
        self.assertEqual(blindness.resolve(["a/x.java", "b/y.java"], rules).sees, ["sweep.block"])

    def test_a_suppression_answers_for_its_own_paths_and_no_others(self):
        """Suppression outranks within a path; it is still a statement about the paths it triggers on."""
        rules = [rule("SEL", ["a/**", "b/**"], sees=["sweep.block"]),
                 rule("SUP", ["a/**"], blind=["sweep.block"], mode="suppress")]
        self.assertEqual(blindness.resolve(["a/x.java"], rules).sees, [])
        self.assertEqual(blindness.resolve(["a/x.java", "b/y.java"], rules).sees, ["sweep.block"])

    def test_the_selector_named_is_the_one_that_survived_its_own_path(self):
        """A rule whose every firing path demoted its selection away selected nothing to report.

        ``LOST`` names the artifact and fires only where the demotion also fires, so on its own path
        it put nothing in the bundle; ``KEPT`` is why the artifact is there at all. Naming both would
        send a reader to the rule whose selection this very demotion cancelled.

        It is also the shape an operator meets most: the claimant here is a ``demote`` rule, whose
        ``blind`` list DOES subtract - on the paths it triggers on, which are not the path the
        selection came from. A marked row is not a statement about the claiming rule's mode.
        """
        reach = blindness.resolve(["a/x.java", "b/y.java"], [
            rule("LOST", ["a/**"], sees=["sweep.block"]),
            rule("KEPT", ["b/**"], sees=["sweep.block"]),
            rule("DEM", ["a/**"], blind=["sweep.block"], mode="demote"),
        ])
        self.assertEqual(reach.sees, ["sweep.block"])
        self.assertEqual([(e["rule"], e["selected_by"]) for e in reach.blind], [("DEM", ["KEPT"])])


class Coverage(unittest.TestCase):

    def test_an_uncovered_path_is_unknown(self):
        reach = blindness.resolve(["z/x.java"], [rule("A", ["a/**"], sees=["sweep.block"])])
        self.assertEqual(reach.unknown, ["z/x.java"])

    def test_no_reach_covers_without_contributing(self):
        """Covered and reaching nothing is a different answer from 'I do not know'."""
        reach = blindness.resolve(["z/package-info.java"], [rule("A", ["a/**"])],
                                  no_reach=["z/**/package-info.java", "z/package-info.java"])
        self.assertEqual(reach.unknown, [])
        self.assertEqual(reach.no_reach, ["z/package-info.java"])
        self.assertEqual(reach.sees, [])

    def test_a_rule_wins_over_no_reach(self):
        reach = blindness.resolve(["a/x.java"], [rule("A", ["a/**"], sees=["sweep.block"])],
                                  no_reach=["a/**"])
        self.assertEqual(reach.sees, ["sweep.block"])
        self.assertEqual(reach.no_reach, [])


class NoReachShape(unittest.TestCase):
    """``no_reach`` is the list every awkward path goes into, so its entries have to say why."""

    def _load(self, no_reach):
        root = Path(tempfile.mkdtemp())
        write_json(root / blindness.BLINDNESS_FILE, {"rules": [], "no_reach": no_reach})
        return blindness.load(root)

    def test_the_glob_is_read_out_of_the_object(self):
        rules, globs = self._load([{"glob": "docs/**", "reason": "r", "probe": "p"}])
        self.assertEqual(globs, ("docs/**",))

    def test_a_bare_glob_is_refused(self):
        """Accepting both shapes is how the mandatory fields get dropped one entry at a time."""
        with self.assertRaises(MissingInput):
            self._load(["docs/**"])


class TheShippedMap(unittest.TestCase):
    """Against the real map, so the resolver and the file are checked together."""

    def setUp(self):
        from parity import store
        self.rules, self.no_reach = blindness.load(store.repo_root() / store.PRODUCTION)

    def test_every_mode_is_known(self):
        self.assertEqual({r.mode for r in self.rules} - {"select", "demote", "suppress"}, set())

    def test_the_box_builder_selects_the_armour_and_player_gates(self):
        reach = blindness.resolve(
            ["src/main/java/lib/minecraft/renderer/engine/kit/BlockGeometryKit.java"],
            self.rules, self.no_reach)
        for artifact in ("sweep.entity", "sweep.armor", "pin.player-crc", "manifest.player-sheets"):
            self.assertIn(artifact, reach.sees)

    def test_a_tooling_change_empties_every_sweep(self):
        reach = blindness.resolve(
            ["src/main/java/lib/minecraft/renderer/tooling/entity/EntityBoneResolver.java"],
            self.rules, self.no_reach)
        self.assertEqual([a for a in reach.sees if a.startswith("sweep.")], [])
        self.assertIn("manifest.tooling-tables", reach.sees)

    def test_an_engine_change_demotes_both_dump_manifests(self):
        reach = blindness.resolve(
            ["src/main/java/lib/minecraft/renderer/engine/ModelEngine.java"],
            self.rules, self.no_reach)
        self.assertEqual([a for a in reach.sees if a.startswith("manifest.dump.")], [])

    def test_an_engine_change_reaches_the_renders_only_the_engine_produces(self):
        """The glint is an engine-composited overlay and manifest.visual hashes what the mains draw.

        Both were unreachable from every rule governing render code, so an engine edit answered that
        nothing rendered saw it.
        """
        reach = blindness.resolve(
            ["src/main/java/lib/minecraft/renderer/engine/ModelEngine.java"],
            self.rules, self.no_reach)
        for artifact in ("sweep.glint", "manifest.visual", "pin.block-crc", "pin.fluid-crc",
                         "pin.portal-crc"):
            self.assertIn(artifact, reach.sees)

    def test_a_markdown_file_under_the_harness_reaches_nothing(self):
        """B29 costs a whole-client re-render, and prose cannot move a reference byte."""
        reach = blindness.resolve(["harness/CLAUDE.md"], self.rules, self.no_reach)
        self.assertEqual(reach.sees, [])
        self.assertEqual(reach.no_reach, ["harness/CLAUDE.md"])

    def test_a_harness_renderer_still_reaches_the_reference_tree(self):
        reach = blindness.resolve(
            ["harness/src/client/java/EntityFrameRenderer.java"], self.rules, self.no_reach)
        self.assertIn("manifest.references", reach.sees)

    def test_the_self_capture_writers_are_not_resolved_as_emitting_nothing(self):
        """B33's claim - the test tree asserts rather than emits - is false for these two."""
        for path in ("src/test/java/lib/minecraft/renderer/parity/SelfCapture.java",
                     "src/test/java/lib/minecraft/renderer/pipeline/dump/PipelineParityDump.java"):
            reach = blindness.resolve([path], self.rules, self.no_reach)
            self.assertIn("manifest.dump.vanilla", reach.sees, path)
            self.assertIn("digest.shipped-tables", reach.sees, path)

    def test_a_reader_in_a_write_path_package_is_demoted_back_to_nothing(self):
        """The other half of taking those packages whole: what they hold that emits nothing.

        Asserted beside the writers rather than instead of them - the pair is what says the rule
        discriminates, where either alone passes on a rule that answers the same thing for both.
        """
        for path in ("src/test/java/lib/minecraft/renderer/parity/BlindnessMapTest.java",
                     "src/test/java/lib/minecraft/renderer/parity/ParityViews.java"):
            self.assertEqual(blindness.resolve([path], self.rules, self.no_reach).sees, [], path)

    def test_a_reader_committed_beside_a_writer_does_not_cancel_the_writer(self):
        """The shape a single-path case cannot see, and the shape this effort's own phases commit in.

        Both files sit in the demoting rule's package, so resolving the commit as one union let the
        reader's demotion take the writer's whole bundle away and the plan came back empty. The reader
        is still declared blind, and the plan prints that as a contradiction rather than dropping it.
        """
        writer = "src/test/java/lib/minecraft/renderer/parity/PinSet.java"
        reader = "src/test/java/lib/minecraft/renderer/parity/ParityReferences.java"
        alone = blindness.resolve([writer], self.rules, self.no_reach).sees
        self.assertIn("pin.player-crc", alone)
        self.assertEqual(blindness.resolve([reader], self.rules, self.no_reach).sees, [])
        together = blindness.resolve([writer, reader], self.rules, self.no_reach)
        self.assertEqual(together.sees, alone)
        self.assertEqual([e["selected_by"] for e in together.blind if e["artifact"] == "pin.player-crc"],
                         [["B37"]])

    def test_every_test_that_declares_a_self_captured_artifact_reaches_it(self):
        """The writer is one mechanism; the VALUE is declared once per artifact, elsewhere.

        Discovered from the source rather than listed here, so a NINTH declaration in a class no rule
        names fails this instead of resolving to nothing the way all eight of these once did. The
        constant is what SelfCapture is handed, so its file is where that artifact's value is decided.
        """
        declarations = self._artifact_declarations()
        # The population, before the reach: a scan that found nothing would satisfy the loop below,
        # and the store's own self-captured rows are the list it has to have found.
        self.assertEqual({artifact for _, artifact in declarations}, self._self_captured_rows())
        for path, artifact in sorted(declarations):
            reach = blindness.resolve([path], self.rules, self.no_reach)
            self.assertIn(artifact, reach.sees, path)

    @staticmethod
    def _artifact_declarations() -> set[tuple[str, str]]:
        """Every ``(path, artifact)`` a test source names in an ``*ARTIFACT`` constant."""
        from parity import store
        root = store.repo_root()
        found = set()
        for source in sorted((root / "src/test/java").rglob("*.java")):
            for artifact in re.findall(r'ARTIFACT\s*=\s*"([^"]+)"',
                                       source.read_text(encoding="utf-8")):
                found.add((source.relative_to(root).as_posix(), artifact))
        return found

    @staticmethod
    def _self_captured_rows() -> set[str]:
        """The store rows no producer directory holds, which are exactly the pin and digest sets."""
        import json
        from parity import store
        index = json.loads(
            (store.repo_root() / store.PRODUCTION / "index.json").read_text(encoding="utf-8"))
        return {name for name, row in index["artifacts"].items()
                if row["kind"] in ("pin-set", "digest-set")}


class TheTwoIdNamespaces(unittest.TestCase):
    """A reach rule is a ``B<n>`` and a refusal is an ``R<n>``, and no prose may swap them.

    They collided once - the map's rules were ``R<n>`` too, so one skill held two ``R14``s meaning
    unrelated things - and the rules were renamed to clear it. A blanket rename is how the same
    defect comes back pointing the other way: this module is where the two namespaces meet, its
    uncovered-path exception IS refusal R1, and the docstring describing that same condition was
    swept along with the rules and cited a rule about the option surface instead. A reader following
    it into the skill's table finds no such refusal and into the rendered map finds the wrong
    subject, which is the whole cost of an id - it is a thing you can look up.
    """

    #: Where the gate's prose lives and therefore where either id can be spelled: the toolkit, the
    #: skill, the store's Java (which renders two of the skill's reference files), and the build file
    #: the tasks each refusal comes out of are registered in.
    SURFACES = ("scripts/parity", ".claude/skills/parity-gate",
                "src/test/java/lib/minecraft/renderer/parity", "build.gradle.kts")

    #: The suffixes a surface is walked for, which is the second operand of the same scan: dropping
    #: one stops reading a whole language's worth of prose and leaves every case below green.
    SUFFIXES = ("*.py", "*.md", "*.java", "*.kts")

    #: One file per surface whose citation is a durable property of that file rather than today's
    #: total, so a surface or a suffix dropped from the scan fails on the thing it stopped reading.
    #: `blindness.py`'s uncovered-path exception IS refusal R1's implementation, `procedures.md` is
    #: the runbook telling an operator what to do about one, and `ParityReferences` renders the
    #: sentence the skill's own map carries.
    REACHED = ("scripts/parity/blindness.py",
               ".claude/skills/parity-gate/references/procedures.md",
               "src/test/java/lib/minecraft/renderer/parity/ParityReferences.java")

    #: How the skill's decision table spells a refusal, which is the only declaration of the set.
    DECLARES = re.compile(r"Refuse \((R\d+)\)")

    #: One id: any letter-then-digit token rather than ``R\d+``, because a citation spelling the
    #: OTHER namespace is the thing being looked for and a pattern narrowed to this one would not
    #: see it. That holds wherever the id sits, so this is what reads the head of a citation and
    #: what reads every id after it.
    ID = r"[A-Za-z]+\d+[a-z]?"

    #: What joins two ids of one citation, in three arms. The bare ``and`` of ``R3 and R6`` is the
    #: only one written anywhere the scan reads today, so the bare comma of ``R3, R6`` and the
    #: comma-then-``and`` of ``R3, R6, and R1`` are driven only where a case writes one between two
    #: ids - the roster below, whose content is read back before it is looped, and the case pinning
    #: the whitespace each bound here accepts. Each arm is load-bearing:
    #: comma-then-``and`` is reached by neither of the others, the comma leaving ``and`` sitting
    #: where the next id has to be and ``and`` never getting past the comma. Their ORDER is not
    #: load-bearing: an arm that matches and is then followed by something that is not an id is
    #: backtracked out of and the next arm tried. ``and`` is a word on both sides, each bound
    #: carrying its own half: without the right one the middle of ``android2`` is a join, its tail
    #: the id that has to follow one, and the word is swept into the clause for ``CITED`` to read
    #: back out whole. Without the left one a word running flush out of an id joins, which takes
    #: ``R11aand`` to reach because ``ID`` spends its optional trailing letter on the ``a`` of any
    #: ``and`` written straight after a digit.
    JOINS = r"\s*(?:,\s*and\b|,|\band\b)\s*"

    #: A whole citation - the word, the head id, and every joined id after it - captured as one
    #: clause that ``CITED`` is then run over. Not one group per id: ``findall`` over a group
    #: repeated by ``*`` returns only that group's LAST repetition, so ``refusals R3 and R6 and R1``
    #: would report ``R3`` and ``R1`` and silently drop the middle. The clause stops at the first
    #: thing that is neither a join nor an id, which is what keeps a bare ``R9`` in the next
    #: sentence out of it.
    CITES = re.compile(rf"[Rr]efusals?\s+({ID}(?:{JOINS}{ID})*)")

    #: Every id inside a clause ``CITES`` matched. Run over the clause and never over a whole file,
    #: so a token that is not in citation position is not read as one.
    CITED = re.compile(ID)

    #: How a list citation is written, each form paired with how many of the three ids it names.
    #: Formats rather than finished citations, because the scan below reads this file: the word
    #: glued to an id is a citation wherever it appears, so spelling one that names a rule id here
    #: would make the corpus case report this file. Interpolating the ids at run time is what lets
    #: the defect be exercised without being committed. Between them the four forms spell one
    #: representative of each of ``JOINS``'s three arms - ``", "``, ``" and "`` and ``", and "`` -
    #: and not every string ``JOINS`` accepts around them, its two outer bounds and the one inside
    #: the comma-then-word arm each taking any run of whitespace or none. Which three joins the
    #: forms spell is asserted where they are looped rather than left to be read off the table, and
    #: that assertion pins the join set exactly - a form spelling a different run of whitespace
    #: cannot join this roster without widening it first, so what the bounds accept is driven by its
    #: own case instead.
    LIST_FORMS = (("{a}, {b}", 2), ("{a} and {b}", 2), ("{a}, {b} and {c}", 3),
                  ("{a}, {b}, and {c}", 3))

    #: The three ids each form above is filled with, one triple per namespace. Two triples because
    #: a blanket rename is what puts a rule id into a citation, and it lands wherever the rename's
    #: regex reached - a list whose head is still a refusal and whose tail is now a rule id is the
    #: shape that produces. Three ids because the widest form names three, and distinct ids because
    #: a matcher answering one id per clause reads as correct against a repeated one. The second
    #: triple's tail names rules of the shipped map, so each is a real id that really is not a
    #: refusal, and ``B11a`` is the map's suffixed spelling, which the head-position pattern already
    #: had to accept. Read back for each of those properties where they are looped.
    DRIVEN_IDS = (("R3", "R6", "R1"), ("R3", "B24", "B11a"))

    def setUp(self):
        from parity import store
        self.root = store.repo_root()
        self.production = self.root / store.PRODUCTION
        skill = (self.root / ".claude/skills/parity-gate/SKILL.md").read_text(encoding="utf-8")
        self.declared = set(self.DECLARES.findall(skill))

    def test_the_skill_declares_the_refusal_namespace(self):
        """The operand everything below is compared against, so an unparsed table cannot pass."""
        self.assertTrue(self.declared, "SKILL.md's decision table declares no `Refuse (R<n>)` row")

    def test_no_rule_of_the_map_is_also_a_refusal_id(self):
        """The collision itself, which is what the rename was for and what a rename back restores."""
        rules, _ = blindness.load(self.production)
        self.assertEqual(sorted({rule.id for rule in rules} & self.declared), [])

    def test_every_refusal_citation_names_a_declared_refusal(self):
        """A cited id has to resolve where the citation sends the reader, and only one table has it.

        Scoped to the citation form rather than to bare ids on purpose: this file is itself inside
        the scanned surface and spells ids that cite nothing - the synthetic fixtures every case
        below is driven with, some of which no table declares - so "every id under these roots is a
        refusal" is false of the corpus and would have to be fitted with exceptions until it
        stopped saying anything.
        """
        wrong = [f"{path}: refusal {cited}" for path, cited in self._citations()
                 if cited not in self.declared]
        self.assertEqual(wrong, [])

    def test_the_scan_reaches_every_surface_that_cites_one(self):
        """Otherwise a scan that read nothing satisfies the case above by finding no counterexample.

        Named files rather than a count, and one per surface rather than one in total: the case
        above is only as wide as what the walk opened, and a root or a suffix quietly dropped from
        it reads exactly like a corpus that cites nothing.
        """
        # The roster before the reach, and by what it names rather than by being non-empty: a bare
        # non-emptiness check catches the emptying and misses the narrowing, and narrowed to one
        # file this case goes green while the surface and the language the other two stood for go
        # unread - after which dropping either from the walk reads green too.
        self.assertEqual(sorted(path.rsplit(".", 1)[1] for path in self.REACHED),
                         ["java", "md", "py"], "one file per language the walk opens")
        walked = [surface for surface in self.SURFACES
                  if "." not in surface.rsplit("/", 1)[-1]]
        self.assertEqual([surface for surface in walked
                          if any(path.startswith(surface + "/") for path in self.REACHED)],
                         walked, "and one under every surface it opens as a directory")
        cited = {path for path, _ in self._citations()}
        self.assertEqual([path for path in self.REACHED if path not in cited], [])

    def test_a_list_citation_names_every_id_in_it_and_not_only_its_head(self):
        """The form the corpus already writes, read at its head alone until this case existed.

        `procedures.md` opens by citing two refusals in one clause, and so does the comment on
        ``CITES``. A head-only pattern validates the first and never looks at the second, which is
        the namespace collision this class exists over, reachable in the exact syntax the files
        being scanned use.
        """
        # BOTH operands of the loop, read back before it, for the reason either is written down at
        # all: the loop asserts exactly what its operands spell, so emptied it asserts nothing, and
        # narrowed - to one form, one triple, or fewer ids - it quietly stops covering the join
        # spelling or the namespace that was dropped while reading green. Neither is checked for
        # being non-empty, which catches the emptying and misses the narrowing. The joins are read
        # back out of the forms, so what is named here is what the loop spells.
        self.assertEqual(sorted({join for form, _ in self.LIST_FORMS
                                 for join in re.split(r"\{[abc]}", form)[1:-1]}),
                         [" and ", ", ", ", and "],
                         "the roster must spell one representative of each join arm")
        rules = {rule.id for rule in blindness.load(self.production)[0]}
        self.assertEqual(len(self.DRIVEN_IDS), 2, "one triple per namespace")
        refusals, crossing = self.DRIVEN_IDS
        self.assertEqual([len(ids) for ids in self.DRIVEN_IDS], [3, 3],
                         "a triple fills the widest form, which names three")
        self.assertEqual([len(set(ids)) for ids in self.DRIVEN_IDS], [3, 3],
                         "and names them distinctly, or one id per clause reads as correct")
        self.assertEqual(sorted(set(refusals) - self.declared), [],
                         "the first triple is a citation right the whole way along")
        self.assertIn(crossing[0], self.declared,
                      "the second is one a rename left right at its head")
        self.assertEqual(sorted(set(crossing[1:]) & self.declared), [], "and wrong after it")
        self.assertEqual(sorted(set(crossing[1:]) - rules), [],
                         "naming rules of the shipped map rather than ids nothing declares")
        self.assertTrue(any(cited[-1].isalpha() for cited in crossing),
                        "one of them suffixed, a spelling head position already had to accept")
        visited = []
        for ids in self.DRIVEN_IDS:
            for form, named in self.LIST_FORMS:
                text = "refusals " + form.format(a=ids[0], b=ids[1], c=ids[2])
                self.assertEqual(self._cited(text), list(ids[:named]), text)
                visited.append((ids, form))
        # What everything above cannot say: that the loop read the operands it was handed. They pin
        # what each SPELLS, and a loop whose iterable is replaced by an empty literal still passes
        # every one of them while asserting nothing itself. What is compared here is the pairs the
        # body saw rather than how many times it ran, because a count is satisfied by an iterable
        # padded back to length - one triple repeated runs eight times and reads the namespace it
        # dropped nowhere.
        self.assertEqual(visited, [(ids, form) for ids in self.DRIVEN_IDS
                                   for form, _ in self.LIST_FORMS],
                         "the loop must visit every form of every triple")

    def test_a_file_is_read_past_the_first_citation_in_it(self):
        """Clause position, which is the same blindness one level out from list position.

        No other case here hands the matcher more than one clause, so a matcher truncated to the
        first clause it finds reads all of them back unchanged, while the corpus scan then validates
        each file's opening citation and nothing after it. Two of the three files the reach roster
        names write more than one clause apiece, and the runbook first names the determinism refusal
        after its opening one, so an id cited only later is exactly what such a scan stops seeing.
        The reach roster cannot catch that: every file it names still yields its first clause.
        """
        text = ("Loaded on refusals {a} and {b}. The store rejects a promotion on "
                "refusal {c}.".format(a="R3", b="R6", c="R5"))
        self.assertEqual(self._cited(text), ["R3", "R6", "R5"])

    def test_a_join_is_a_word_and_never_the_middle_or_the_tail_of_one(self):
        """The two word bounds on ``and``, one assertion each, driven by nothing else.

        Neither shape is written anywhere the scan reads - the corpus reads out identically with
        both bounds dropped - so either can go, together or one at a time, with every other case in
        this class green. The left one takes the second shape to reach because ``ID`` spends its
        optional trailing letter on the ``a`` wherever ``and`` follows a digit directly, which
        leaves the join tried one character in and failing on the ``n`` for a reason that has
        nothing to do with a bound.

        Interpolated for the reason the list forms are: each is a citation where it stands, and the
        second names an id no table declares, which the corpus case would report out of this file.
        """
        self.assertEqual(self._cited("refusal {a} android2".format(a="R1")), ["R1"],
                         "the middle of a word is not a join")
        self.assertEqual(self._cited("refusals {a}and {b}".format(a="R11a", b="R2")), ["R11a"],
                         "a word running flush out of an id is not a join")

    def test_a_join_needs_no_whitespace_of_its_own(self):
        """The two bounds the roster only ever spells with a space in, and therefore cannot pin.

        A form spells its join with a space after it, and the one comma-then-word form spells a
        space between the comma and the word, so tightening either of those bounds to one-or-more
        reads the whole roster back unchanged and leaves the rest of this class green while the scan
        stops reading a citation that was typed without one. The bound BEFORE a join is already
        reached - a join opening on a comma has no space in front of it, so tightening that one
        fails the roster on the spot.
        """
        self.assertEqual(self._cited("refusals {a},{b}".format(a="R3", b="R6")), ["R3", "R6"],
                         "a bare comma needs no space after it")
        self.assertEqual(self._cited("refusals {a},and {b}".format(a="R3", b="R6")), ["R3", "R6"],
                         "nor does the comma of the comma-then-word arm")

    def test_a_citation_ends_where_its_ids_do(self):
        """The other half of taking a clause: it must not run on into the prose after the list.

        The last assertion is the one a widened clause fails: `R9` is id-shaped and no table
        declares it, and a clause reaching past its final join reads it out of the sentence after.
        The first three are the single-id and no-id forms the corpus writes, so the widening cannot
        be bought back by narrowing those instead.
        """
        self.assertEqual(self._cited("Refusal {a}.".format(a="R1")), ["R1"])
        self.assertEqual(self._cited("refusal R1 stops the walk"), ["R1"], "a following word")
        self.assertEqual(self._cited("the refusal is what forces the caller"), [], "no id at all")
        # The runbook's own opening line, plus a sentence after it that carries an id nothing
        # cited: a clause that runs past its last join reports that id as one too.
        self.assertEqual(self._cited("Loaded on refusals {a} and {b}, and for any A/B. The bare "
                                     "id R9 is not one.".format(a="R3", b="R6")),
                         ["R3", "R6"], "a trailing sentence")

    def test_the_operands_no_citation_can_reach_are_declared(self):
        """The two the case above cannot hold, because nothing under either cites a refusal today.

        A task registration names what a refusal comes out of and never the refusal, so the build
        file carries none - and no `.kts` file anywhere does. Both are still where the next one
        would land, so a drop of either is a real loss of reach and is asserted directly.
        """
        self.assertIn("build.gradle.kts", self.SURFACES)
        self.assertIn("*.kts", self.SUFFIXES)

    def _cited(self, text: str) -> list[str]:
        """Every id ``text`` cites as a refusal, in the order it spells them.

        The whole matcher, so the synthetic cases above drive what the corpus scan below runs.

        :param text: any prose
        :return: each cited id, one entry per position in a list citation
        """
        return [cited for clause in self.CITES.findall(text) for cited in self.CITED.findall(clause)]

    def _citations(self) -> list[tuple[str, str]]:
        """Every ``(path, id)`` a surface cites as a refusal, in path order."""
        found = []
        for surface in self.SURFACES:
            base = self.root / surface
            sources = [base] if base.is_file() else sorted(
                path for suffix in self.SUFFIXES for path in base.rglob(suffix))
            for source in sources:
                path = source.relative_to(self.root).as_posix()
                found.extend((path, cited)
                             for cited in self._cited(source.read_text(encoding="utf-8")))
        return sorted(found)


if __name__ == "__main__":
    unittest.main()
