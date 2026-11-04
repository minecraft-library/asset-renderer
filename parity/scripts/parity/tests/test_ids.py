"""The ten round-trip cases, each retiring a specific hardcoding or a specific recorded failure."""

from __future__ import annotations

import unittest
from pathlib import Path

from parity import ids, norm

DATA = Path(__file__).resolve().parent / "data"
REFERENCES = Path(__file__).resolve().parents[4] / "cache/asset-renderer/vanilla/26.1/references"


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


class Spellings(unittest.TestCase):

    def test_five_spellings(self):
        block = ids.parse_ref_stem("minecraft__acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.REF_STEM), "minecraft__acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.COLON), "minecraft:acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.SWEEP_DIR), "minecraft_acacia_button")
        self.assertEqual(ids.format_as(block, ids.Spelling.STEM_DIR), "minecraft__acacia_button")

    def test_parse_as_inverts(self):
        for text, spelling in (
            ("minecraft:acacia_boat", ids.Spelling.COLON),
            ("minecraft_acacia_boat", ids.Spelling.SWEEP_DIR),
            ("minecraft__acacia_boat", ids.Spelling.REF_STEM),
            ("full", ids.Spelling.SCOPE),
        ):
            self.assertEqual(ids.format_as(ids.parse_as(text, spelling), spelling), text)


if __name__ == "__main__":
    unittest.main()
