"""Reach resolution: the glob grammar, the union, and the two post-union passes."""

import re
import tempfile
import unittest
from pathlib import Path

from parity import blindness
from parity.norm import MissingInput, write_json


def rule(rid, triggers, sees=(), blind=(), mode="select", derived=False):
    return blindness.Rule(id=rid, claim="c", trigger_paths=tuple(triggers), sees=tuple(sees),
                          blind=tuple(blind), reason="r", mode=mode, probe="p", source="s",
                          derived=derived)


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


class DerivedSelection(unittest.TestCase):
    """A rule whose selection the reference graph answers, per path rather than per glob."""

    #: What a graph answers for two files under one glob, which is the whole point of deriving: the
    #: directory they share stops being what decides either answer.
    GRAPH = {"a/wide.java": ["sweep.block", "sweep.item"], "a/narrow.java": ["sweep.item"]}

    def answer(self, path):
        return self.GRAPH.get(path)

    def test_a_derived_rule_selects_what_the_graph_answers(self):
        reach = blindness.resolve(["a/wide.java"], [rule("A", ["a/**"], derived=True)],
                                  derived=self.answer)
        self.assertEqual(reach.sees, ["sweep.block", "sweep.item"])

    def test_two_files_under_one_glob_answer_separately(self):
        """The mechanism, stated as a difference: an authored `sees` cannot tell these two apart."""
        rules = [rule("A", ["a/**"], derived=True)]
        self.assertEqual(blindness.resolve(["a/narrow.java"], rules, derived=self.answer).sees,
                         ["sweep.item"])
        self.assertEqual(blindness.resolve(["a/wide.java", "a/narrow.java"], rules,
                                           derived=self.answer).sees,
                         ["sweep.block", "sweep.item"])

    def test_a_derived_rule_keeps_demoting_its_own_blind_list(self):
        """The half no graph can answer: what an artifact OBSERVES, rather than what code moved.

        The shipped case is the dump pair falling off an engine change - a byte-identical dump proves
        the render inputs are identical and says nothing about a render, which is a claim about the
        artifact and not about the reference graph.
        """
        reach = blindness.resolve(["a/wide.java"], [
            rule("A", ["a/**"], blind=["sweep.block"], mode="demote", derived=True),
        ], derived=self.answer)
        self.assertEqual(reach.sees, ["sweep.item"])
        self.assertEqual([entry["artifact"] for entry in reach.blind], ["sweep.block"])

    def test_the_selector_named_is_the_derived_rule_that_answered(self):
        """A contradiction has to name the rule that really put the artifact in the bundle.

        Read the authored list to decide who selected what and a derived rule never appears, so the
        plan prints "claimed blind, selected by" nothing at all over a bundle that carries it.
        """
        reach = blindness.resolve(["a/wide.java"], [
            rule("A", ["a/**"], derived=True),
            rule("B", ["a/**"], blind=["sweep.block"]),
        ], derived=self.answer)
        self.assertEqual([(e["rule"], e["selected_by"]) for e in reach.blind],
                         [("B", ["A"])])

    def test_a_path_the_graph_cannot_answer_is_refused(self):
        """Never an empty selection: it is indistinguishable from a class that reaches nothing."""
        with self.assertRaises(MissingInput):
            blindness.resolve(["a/unknown.java"], [rule("A", ["a/**"], derived=True)],
                              derived=self.answer)

    def test_a_derived_rule_with_no_graph_at_all_is_refused(self):
        """A caller that forgot the graph would otherwise plan every derived rule as empty."""
        with self.assertRaises(MissingInput):
            blindness.resolve(["a/wide.java"], [rule("A", ["a/**"], derived=True)])

    def test_an_authored_rule_needs_no_graph(self):
        """The map is resolvable without one until some rule asks, which is what keeps it optional."""
        reach = blindness.resolve(["a/wide.java"], [rule("A", ["a/**"], sees=["sweep.block"])])
        self.assertEqual(reach.sees, ["sweep.block"])


class ADeletedPathReadsTheCommittedMap(unittest.TestCase):
    """A path the change deletes is answered by the map and graph as the commit before it held them.

    The working map below has lost the deleted file's trigger and the working graph its row, which
    is what regenerating either over the tree the deletion left does.
    """

    GONE = "a/gone.java"
    KEPT = "a/kept.java"

    def committed(self, *rules, graph=None):
        answers = graph if graph is not None else {self.GONE: ["sweep.entity"],
                                                   self.KEPT: ["sweep.block"]}
        return blindness.Committed(paths=frozenset({self.GONE}), rules=tuple(rules),
                                   derived=answers.get)

    def test_the_committed_triggers_and_graph_answer_for_it(self):
        reach = blindness.resolve(
            [self.GONE], [rule("A", [self.KEPT], derived=True)], derived={}.get,
            committed=self.committed(rule("A", [self.GONE, self.KEPT], derived=True)))
        self.assertEqual((reach.sees, reach.unknown, reach.fired), (["sweep.entity"], [], ["A"]))

    def test_a_surviving_path_is_answered_by_the_working_map(self):
        """`kept` reaches `sweep.block` in the committed graph, `sweep.item` in the working one."""
        reach = blindness.resolve(
            [self.GONE, self.KEPT], [rule("A", [self.KEPT], derived=True)],
            derived={self.KEPT: ["sweep.item"]}.get,
            committed=self.committed(rule("A", [self.GONE, self.KEPT], derived=True)))
        self.assertEqual(reach.sees, ["sweep.entity", "sweep.item"])

    def test_one_rule_in_both_statements_is_fired_once(self):
        """The two statements of rule A differ in their triggers, and are still one rule."""
        reach = blindness.resolve(
            [self.GONE, self.KEPT], [rule("A", [self.KEPT], derived=True)],
            derived={self.KEPT: ["sweep.item"]}.get,
            committed=self.committed(rule("A", [self.GONE, self.KEPT], derived=True)))
        self.assertEqual(reach.fired, ["A"])

    def test_the_committed_map_still_refuses_a_path_it_never_covered(self):
        """The committed map answers for the path; it is not a licence to cover it."""
        reach = blindness.resolve(
            [self.GONE], [rule("A", ["a/**"], sees=["sweep.block"])],
            committed=self.committed(rule("B", ["b/**"], sees=["sweep.item"])))
        self.assertEqual(reach.unknown, [self.GONE])

    def test_a_path_the_committed_graph_cannot_answer_is_refused(self):
        with self.assertRaises(MissingInput):
            blindness.resolve(
                [self.GONE], [], committed=self.committed(rule("A", [self.GONE], derived=True),
                                                          graph={}))


class DerivedShape(unittest.TestCase):
    """The two spellings of a selection are exclusive, and the loader is where that is held."""

    def _load(self, row):
        root = Path(tempfile.mkdtemp())
        write_json(root / blindness.BLINDNESS_FILE, {"rules": [row], "no_reach": []})
        return blindness.load(root)

    def test_a_derived_rule_authoring_a_sees_is_refused(self):
        with self.assertRaises(MissingInput):
            self._load({"id": "A", "trigger_paths": ["a/**"], "sees": ["sweep.block"],
                        "derived": True})

    def test_a_derived_rule_carries_an_empty_sees(self):
        rules, _ = self._load({"id": "A", "trigger_paths": ["a/**"], "sees": [], "derived": True})
        self.assertEqual((rules[0].derived, rules[0].sees), (True, ()))

    def test_a_rule_declaring_nothing_is_not_derived(self):
        rules, _ = self._load({"id": "A", "trigger_paths": ["a/**"], "sees": ["sweep.block"]})
        self.assertFalse(rules[0].derived)


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
        import json
        from parity import reach, store
        root = store.repo_root()
        self.rules, self.no_reach = blindness.load(root / store.PRODUCTION)
        graph = json.loads((root / "parity" / reach.STORED).read_text(encoding="utf-8"))
        self.derived = lambda path: reach.answered_by(graph, path)

    def _reach(self, changed):
        """The shipped map and the committed graph, which is the pair the planner resolves through."""
        return blindness.resolve(changed, self.rules, self.no_reach, self.derived)

    def test_the_box_builder_selects_the_armour_and_player_gates(self):
        """The gates of the rule the box builder's declaration joins, and the dump demoted for it: the
        demotion fires on ``engine/mesh/**`` and the dump never renders."""
        reach = self._reach(["src/main/java/lib/minecraft/renderer/engine/mesh/BoxKit.java"])
        for artifact in ("sweep.entity", "sweep.armor", "pin.player-crc", "manifest.player-sheets"):
            self.assertIn(artifact, reach.sees)
        self.assertEqual([a for a in reach.sees if a.startswith("manifest.dump.")], [])

    def test_a_tooling_change_empties_every_sweep(self):
        reach = self._reach(
            ["tooling/src/main/java/lib/minecraft/renderer/tooling/entity/EntityBoneResolver.java"])
        self.assertEqual([a for a in reach.sees if a.startswith("sweep.")], [])
        self.assertIn("manifest.tooling-tables", reach.sees)

    def test_an_engine_change_demotes_both_dump_manifests(self):
        reach = self._reach(["src/main/java/lib/minecraft/renderer/engine/raster/Rasterizer.java"])
        self.assertEqual([a for a in reach.sees if a.startswith("manifest.dump.")], [])

    def test_an_engine_change_reaches_the_renders_only_the_engine_produces(self):
        """The glint is an engine-composited overlay and manifest.visual hashes what the mains draw.

        Both were unreachable from every rule governing render code, so an engine edit answered that
        nothing rendered saw it.
        """
        reach = self._reach(["src/main/java/lib/minecraft/renderer/engine/raster/Rasterizer.java"])
        for artifact in ("sweep.glint", "manifest.visual", "pin.block-crc", "pin.fluid-crc",
                         "pin.portal-crc"):
            self.assertIn(artifact, reach.sees)

    def test_a_markdown_file_under_the_harness_reaches_nothing(self):
        """B29 costs a whole-client re-render, and prose cannot move a reference byte."""
        reach = self._reach(["harness/CLAUDE.md"])
        self.assertEqual(reach.sees, [])
        self.assertEqual(reach.no_reach, ["harness/CLAUDE.md"])

    def test_a_harness_renderer_still_reaches_the_reference_tree(self):
        """A path that EXISTS, because the trigger it has to match is derived from the tree."""
        reach = self._reach(
            ["harness/src/client/java/lib/minecraft/refharness/frame/EntityFrameRenderer.java"])
        self.assertIn("manifest.references", reach.sees)

    def test_the_pack_tooltip_rows_are_planned_by_the_paths_that_reach_them_and_no_others(self):
        """The pack run's drawings and their sweep, each resolved against its neighbours.

        The pack-source probe's selection block writes the pack run's handshake, so it reaches those
        two and nothing else stored, where the other probes reach nothing; the tooltip sweep's
        harness side and the roster reach both; a CIT grammar type reaches neither tooltip sweep, the
        pack shipping no CIT rule; and the root build file, whose pins the pack run never reads,
        reaches the sweep and not the drawings.
        """
        drawings, pack_sweep = "manifest.pack-tooltips", "sweep.tooltip-hypixel-skyblock"
        sweeps = "harness/src/client/java/lib/minecraft/refharness/sweep/"
        with self.subTest(path="PackSourceSweep.java"):
            self.assertEqual(sorted(self._reach([sweeps + "PackSourceSweep.java"]).sees),
                             [drawings, pack_sweep])
        with self.subTest(path="PitchRollSweep.java"):
            self.assertEqual(self._reach([sweeps + "PitchRollSweep.java"]).sees, [])
        for path in (sweeps + "TooltipSweep.java",
                     "src/visual/resources/lib/minecraft/renderer/sweep/tooltip-roster.json"):
            with self.subTest(path=path):
                sees = self._reach([path]).sees
                self.assertIn(drawings, sees)
                self.assertIn(pack_sweep, sees)
        with self.subTest(path="CitRule.java"):
            sees = self._reach(["src/main/java/lib/minecraft/renderer/asset/rule/CitRule.java"]).sees
            self.assertNotIn("sweep.tooltip", sees)
            self.assertNotIn(pack_sweep, sees)
        with self.subTest(path="build.gradle.kts"):
            sees = self._reach(["build.gradle.kts"]).sees
            self.assertIn(pack_sweep, sees)
            self.assertNotIn(drawings, sees)

    def test_the_self_capture_writers_are_not_resolved_as_emitting_nothing(self):
        """B33's claim - the test tree asserts rather than emits - is false for these two."""
        for path in ("src/visual/java/lib/minecraft/renderer/store/SelfCapture.java",
                     "src/visual/java/lib/minecraft/renderer/dump/PipelineParityDump.java"):
            reach = self._reach([path])
            self.assertIn("manifest.dump.vanilla", reach.sees, path)
            self.assertIn("digest.shipped-tables", reach.sees, path)

    def test_a_reader_in_a_write_path_package_is_demoted_back_to_nothing(self):
        """The other half of taking those packages whole: what they hold that emits nothing.

        Asserted beside the writers rather than instead of them - the pair is what says the rule
        discriminates, where either alone passes on a rule that answers the same thing for both.
        """
        for path in ("src/test/java/lib/minecraft/renderer/guard/BlindnessMapTest.java",
                     "src/visual/java/lib/minecraft/renderer/store/view/ParityViews.java"):
            self.assertEqual(self._reach([path]).sees, [], path)

    def test_a_reader_committed_beside_a_writer_does_not_cancel_the_writer(self):
        """The shape a single-path case cannot see, and the shape this effort's own phases commit in.

        Both files sit in the demoting rule's package, so resolving the commit as one union let the
        reader's demotion take the writer's whole bundle away and the plan came back empty. The reader
        is still declared blind, and the plan prints that as a contradiction rather than dropping it.
        """
        writer = "src/visual/java/lib/minecraft/renderer/store/PinSet.java"
        reader = "src/visual/java/lib/minecraft/renderer/store/view/ParityReferences.java"
        alone = self._reach([writer]).sees
        self.assertIn("pin.player-crc", alone)
        self.assertEqual(self._reach([reader]).sees, [])
        together = self._reach([writer, reader])
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
            reach = self._reach([path])
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
    #: skill, the store's Java in both source sets it spans (the visual set's rendering, which
    #: renders two of the skill's reference files, and the test set's guards and store suites), and
    #: the build scripts the tasks each refusal comes out of are registered in - the root one and
    #: the parity one the split moved those registrations into.
    SURFACES = ("parity/scripts/parity", ".claude/skills/parity-gate",
                "src/visual/java/lib/minecraft/renderer/store",
                "src/test/java/lib/minecraft/renderer/guard",
                "src/test/java/lib/minecraft/renderer/store", "build.gradle.kts",
                "gradle/parity.gradle.kts")

    #: The suffixes a surface is walked for, which is the second operand of the same scan: dropping
    #: one stops reading a whole language's worth of prose and leaves every case below green.
    SUFFIXES = ("*.py", "*.md", "*.java", "*.kts")

    #: Three files, one per language a citation is written in - Python, Markdown and Java - and one
    #: under each directory surface the walk opens outside ``QUIET``; the two build scripts are
    #: surfaces of one file each and are held to no citation. Each is named because its citation is a
    #: durable property of that file rather than today's total, so dropping one of those three
    #: suffixes or directories from the scan fails on the thing it stopped reading. `blindness.py`'s
    #: uncovered-path exception IS refusal R1's implementation, `procedures.md` is the runbook
    #: telling an operator what to do about one, and `ParityReferences` renders the sentence the
    #: skill's own map carries.
    REACHED = ("parity/scripts/parity/blindness.py",
               ".claude/skills/parity-gate/references/procedures.md",
               "src/visual/java/lib/minecraft/renderer/store/view/ParityReferences.java")

    #: The directory surfaces no file of which is a citation today, so neither holds a file for
    #: ``REACHED`` to name. The guards spell a refusal id only the way the skill's own table does,
    #: as a declaration or a roster row, and pin each one they spell against ``SKILL.md``
    #: themselves; the store's suites spell none. Both are walked so the next citation written in
    #: either is read, and neither is held to a file in ``REACHED``.
    QUIET = ("src/test/java/lib/minecraft/renderer/guard",
             "src/test/java/lib/minecraft/renderer/store")

    #: How the skill's decision table spells a refusal, which is the only declaration of the set.
    DECLARES = re.compile(r"Refuse \((R\d+)\)")

    #: One id: any letter-then-digit token rather than ``R\d+``, because a citation spelling the
    #: OTHER namespace is the thing being looked for and a pattern narrowed to this one would not
    #: see it. That holds wherever the id sits, so this is what reads the head of a citation and
    #: what reads every id after it.
    ID = r"[A-Za-z]+\d+[a-z]?"

    #: What joins two ids of one citation, in three arms. The bare ``and`` of ``R3 and R6`` is the
    #: only one written anywhere the scan reads today; the bare comma of ``R3, R6`` and the
    #: comma-then-``and`` of ``R3, R6, and R1`` are the other two spellings a list citation takes.
    #: Each arm is load-bearing:
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
        the scanned surface and spells ids that cite nothing - the examples its own comments write -
        so "every id under these roots is a refusal" is false of the corpus and would have to be
        fitted with exceptions until it stopped saying anything.
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
                  if "." not in surface.rsplit("/", 1)[-1] and surface not in self.QUIET]
        self.assertEqual([surface for surface in walked
                          if any(path.startswith(surface + "/") for path in self.REACHED)],
                         walked, "and one under every directory surface it opens, bar the quiet")
        cited = {path for path, _ in self._citations()}
        self.assertEqual([path for path in self.REACHED if path not in cited], [])

    def test_every_directory_surface_the_walk_opens_is_there(self):
        """A walk of a directory that is not there reads exactly like a quiet one.

        A directory surface is opened with ``rglob``, which answers nothing for a directory that
        does not exist, so renaming or moving one leaves its roster entry walking nothing. Outside
        ``QUIET`` the reach case still catches that, as its file in ``REACHED`` going unread, but a
        quiet directory is held to no file there - so either quiet one drops out of the scan with
        every other case green. Asserted over every directory surface rather than the quiet two, so
        a walked one missing is reported as missing rather than as an unread file.
        """
        directories = [surface for surface in self.SURFACES
                       if "." not in surface.rsplit("/", 1)[-1]]
        self.assertEqual([surface for surface in self.QUIET if surface not in directories], [],
                         "the quiet roster among them, or this case stops reaching it")
        self.assertEqual([surface for surface in directories if not (self.root / surface).is_dir()],
                         [], "every directory surface names a directory on disk")

    def test_every_file_surface_the_walk_reads_is_there(self):
        """A file surface that is not there is walked as nothing, the way a missing directory is.

        ``_citations`` reads a surface as one file only where ``is_file()`` holds and opens anything
        else with ``rglob``, which answers nothing for a path that does not exist - so a build script
        renamed or moved with its roster entry left behind drops out of the scan with every other case
        green. Neither build script cites a refusal, so ``REACHED`` holds no file under either. A
        surface is a file where its last segment carries a dot, the split the directory case draws
        the other way.
        """
        files = [surface for surface in self.SURFACES if "." in surface.rsplit("/", 1)[-1]]
        self.assertEqual([surface for surface in files if not (self.root / surface).is_file()],
                         [], "every file surface names a file on disk")

    def _cited(self, text: str) -> list[str]:
        """Every id ``text`` cites as a refusal, in the order it spells them.

        The whole matcher, which the corpus scan below runs over every file it opens.

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
