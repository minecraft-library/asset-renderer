"""Reach resolution: the glob grammar, the union, and the two post-union passes."""

import unittest

from parity import blindness


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

    def test_blind_drops_anything_another_rule_selected(self):
        """The reason the corpus's own worked example resolves the way it does."""
        reach = blindness.resolve(["a/x.java"], [
            rule("A", ["a/**"], sees=[], blind=["sweep.block"]),
            rule("B", ["a/**"], sees=["sweep.block"]),
        ])
        self.assertEqual(reach.sees, ["sweep.block"])
        self.assertEqual(reach.blind, [])


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


if __name__ == "__main__":
    unittest.main()
