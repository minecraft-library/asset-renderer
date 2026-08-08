"""The ten round-trip cases, each retiring a specific hardcoding or a specific recorded failure."""

from __future__ import annotations

import unittest
from pathlib import Path

from parity import ids, norm

DATA = Path(__file__).resolve().parent / "data"
REFERENCES = Path(__file__).resolve().parents[3] / "cache/asset-renderer/vanilla/26.1/references"


class RefStem(unittest.TestCase):

    def test_roundtrip_frozen(self):
        """The frozen fixture, so the suite passes on a fresh clone with no reference tree."""
        stems = norm.read_lines(DATA / "ref-stems.txt")
        self.assertGreaterEqual(len(stems), 60)
        for stem in stems:
            self.assertEqual(ids.format_ref_stem(ids.parse_ref_stem(stem)), stem)

    @unittest.skipUnless(REFERENCES.is_dir(), "reference tree absent")
    def test_roundtrip_corpus(self):
        """Every name on disk. It is 2311 names and one unparseable one breaks a join."""
        checked = 0
        for png in REFERENCES.rglob("*.png"):
            stem = png.stem
            if png.parent.name in {"full", "skull"} or stem.startswith("frame_"):
                continue  # player scopes and glint frames are not appearance keys
            self.assertEqual(ids.format_ref_stem(ids.parse_ref_stem(stem)), stem, stem)
            checked += 1
        self.assertGreater(checked, 1000)

    def test_rejects_uppercase(self):
        """Two names differing only in case would collide on a case-insensitive filesystem."""
        with self.assertRaises(ids.IdError) as caught:
            ids.parse_ref_stem("Minecraft__Cow")
        self.assertIn("0", str(caught.exception))

    def test_no_underscore_guessing(self):
        """A silent wrong head split would join two different subjects' rows."""
        self.assertEqual(ids.parse_ref_stem("minecraft__wolf_pale").base, "wolf_pale")
        split = ids.split_head(ids.parse_ref_stem("minecraft__wolf_pale"), {"wolf": {"pale", "ashen"}})
        self.assertEqual((split.base, split.qualifiers), ("wolf", ("pale",)))


class Tokens(unittest.TestCase):

    def test_sorted_as_whole_strings(self):
        """One rule and no table, so byte order and string order agree across the two repos."""
        stem = "minecraft__villager~villager_level=diamond~villager_profession=farmer"
        sid = ids.parse_ref_stem(stem)
        self.assertEqual(sid.tokens, ("villager_level=diamond", "villager_profession=farmer"))
        self.assertEqual(ids.format_ref_stem(sid), stem)

    def test_carried_double_underscore(self):
        """The one place __ means : inside a value."""
        sid = ids.parse_ref_stem("minecraft__enderman~carried=minecraft__grass_block")
        self.assertEqual(ids.axes(sid)["carried"], ["minecraft:grass_block"])
        self.assertEqual(ids.format_ref_stem(sid),
                         "minecraft__enderman~carried=minecraft__grass_block")

    def test_repeatable_axis(self):
        sid = ids.parse_ref_stem("minecraft__player~toggle=cape~toggle=elytra")
        self.assertEqual(ids.axes(sid)["toggle"], ["cape", "elytra"])

    def test_qualifiers_are_never_sorted(self):
        """An over-eager normalization here breaks every armour reference lookup."""
        sid = ids.SubjectId("minecraft", "zombie", ("leather", "baby"))
        self.assertEqual(ids.format_ref_stem(sid), "minecraft__zombie_leather_baby")


class Spellings(unittest.TestCase):

    def test_five_spellings(self):
        block = ids.parse_ref_stem("minecraft__acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.REF_STEM), "minecraft__acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.COLON), "minecraft:acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.SWEEP_DIR), "minecraft_acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.STEM_DIR), "minecraft__acacia_button")

    def test_sweep_key_and_output_dir(self):
        """A table row and a directory join without a per-call-site transform."""
        block = ids.parse_ref_stem("minecraft__acacia_button")
        self.assertEqual(ids.sweep_key(block, "block"), "minecraft:acacia_button")
        self.assertEqual(ids.output_dir(block, "block"), "minecraft_acacia_button")
        entity = ids.parse_ref_stem("minecraft__axolotl_gold")
        self.assertEqual(ids.sweep_key(entity, "entity"), "minecraft__axolotl_gold")
        self.assertEqual(ids.output_dir(entity, "entity"), "minecraft__axolotl_gold")
        glint = ids.parse_ref_stem("minecraft__nether_star")
        self.assertEqual(ids.sweep_key(glint, "glint"), "minecraft:nether_star")
        self.assertEqual(ids.output_dir(glint, "glint"), "minecraft__nether_star")

    def test_parse_as_inverts(self):
        for text, spelling in (
            ("minecraft:acacia_boat", ids.Spelling.COLON),
            ("minecraft_acacia_boat", ids.Spelling.SWEEP_DIR),
            ("minecraft__acacia_boat", ids.Spelling.REF_STEM),
            ("full", ids.Spelling.SCOPE),
        ):
            self.assertEqual(ids.format_as(ids.parse_as(text, spelling), spelling), text)

    def test_reference_file_has_one_spelling(self):
        block = ids.parse_ref_stem("minecraft__acacia_button")
        self.assertEqual(ids.reference_file(block, "block"), "minecraft__acacia_button.png")


class Escaping(unittest.TestCase):

    def test_roundtrip(self):
        for value in ("plain_value.1-2", "kanji漢", "space here", "%"):
            self.assertEqual(ids.unescape(ids.escape(value)), value)

    def test_truncated_escape_raises(self):
        with self.assertRaises(ids.IdError):
            ids.unescape("ab%2")

    def test_uppercase_hex_escape_raises(self):
        with self.assertRaises(ids.IdError):
            ids.unescape("ab%A0cd")


class ArmorStem(unittest.TestCase):

    def test_matches_disk(self):
        """The cross-repo string agreement, which has no other check."""
        self.assertEqual(ids.armor_stem("minecraft:zombie", "leather", dyed=True),
                         "minecraft__zombie_leather-dyeb04030")
        self.assertEqual(ids.armor_stem("minecraft:piglin", "iron"), "minecraft__piglin_iron")
        self.assertEqual(ids.armor_stem("minecraft:zombie", "iron", baby=True),
                         "minecraft__zombie_iron_baby")

    @unittest.skipUnless((REFERENCES / "armor").is_dir(), "armour references absent")
    def test_generated_stems_equal_the_seven_files(self):
        on_disk = {png.stem for png in (REFERENCES / "armor").glob("*.png")}
        generated = {
            ids.armor_stem(entity, material, dyed=dyed, baby=baby)
            for entity in ("minecraft:zombie", "minecraft:piglin")
            for material, dyed in (("iron", False), ("leather", True))
            for baby in (False, True)
        }
        self.assertTrue(on_disk <= generated, f"on disk but not generated: {on_disk - generated}")


if __name__ == "__main__":
    unittest.main()
