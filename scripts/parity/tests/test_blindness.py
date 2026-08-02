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
        """R6 costs a whole-client re-render, and prose cannot move a reference byte."""
        reach = blindness.resolve(["harness/CLAUDE.md"], self.rules, self.no_reach)
        self.assertEqual(reach.sees, [])
        self.assertEqual(reach.no_reach, ["harness/CLAUDE.md"])

    def test_a_harness_renderer_still_reaches_the_reference_tree(self):
        reach = blindness.resolve(
            ["harness/src/client/java/EntityFrameRenderer.java"], self.rules, self.no_reach)
        self.assertIn("manifest.references", reach.sees)

    def test_the_self_capture_writers_are_not_resolved_as_emitting_nothing(self):
        """R10's claim - the test tree asserts rather than emits - is false for these two."""
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
                         [["R14"]])

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


if __name__ == "__main__":
    unittest.main()
