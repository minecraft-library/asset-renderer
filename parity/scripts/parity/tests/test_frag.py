"""The ``[PX] WRITE`` grammar, held to the line the emitter prints and to the wider one dumps keep.

``DebugChannel.pixelWrite`` writes sixteen tab-separated fields. A dump carrying the after-tint
colour between the tint and the shading has seventeen, and both are read: a fragment from either
width is the same fragment, which is what lets a frozen dump and a fresh one be read by one census.
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity.lab import frag
from parity.norm import MissingInput, write_text

#: One fragment as ``DebugChannel.pixelWrite`` concatenates it: Java's ``Float.toString`` for the
#: depth, the uv and the shading, ``0x%08X`` for every colour, and the blend mode's enum name. The
#: blend is the non-default one, so a parser reading it off the wrong field reads ``NORMAL``, and
#: the out colour is the after-shade one ADDed over a destination already holding ``0xFF202020``,
#: so no two colour fields carry one value and a parser reading one off another's field is caught.
CURRENT = ["[PX]", "WRITE", "3", "7", "0.25", "body/head#north", "0.5", "0.75", "4", "9",
           "0xFF808080", "0xFFFFC0C0", "0.8", "0xFF664D4D", "ADD", "0xFF866D6D"]

#: The same fragment with the after-tint colour in its seventeenth field, between tint and shading.
LEGACY = CURRENT[:12] + ["0xFF806060"] + CURRENT[12:]

#: What either line reads as, transcribed from the fields above rather than from the parser.
FRAGMENT = {"afterShade": 0xFF664D4D, "blend": frag.ADD, "depth": 0.25, "out": 0xFF866D6D,
            "raw": 0xFF808080, "shading": 0.8, "tag": "body/head#north", "tint": 0xFFFFC0C0,
            "tx": 4, "ty": 9, "u": 0.5, "v": 0.75}

#: What an armed dump carries ahead of its fragments, each line as ``DebugChannel`` prints it: the
#: rect, a ``TRI`` line for the tagged triangle, and the fit trace's three lines. None of them names
#: a pixel, and each one leads every dump below so a reader that takes a position off any of them
#: fails.
PREAMBLE = [
    "[PX] dump rect=0,0-15,15",
    "[PX]\tTRI\tbody/head#north\ts0=1.5,2.25\ts1=10.0,2.25\ts2=10.0,12.5"
    "\tp0=-0.25,1.5,0.25\tp1=0.25,1.5,0.25\tp2=0.25,1.0,0.25\tuv0=0.0,0.0\tuv1=0.5,0.0\tuv2=0.5,0.5",
    "[PX]\tFIT\tminecraft:creeper\tminX=-0.5\tmaxX=0.5\tminY=0.0\tmaxY=1.625",
    "[PX]\tBASE-BOUNDS\tminX=-0.5\tmaxX=0.5\tminY=0.0\tmaxY=1.625",
    "[PX]\tOVERLAY-BOUNDS\tref=minecraft:entity/creeper/creeper_armor\tminX=-0.5\tmaxX=0.5"
    "\tminY=0.0\tmaxY=1.625",
]

#: A fragment the depth test rejected at the same pixel once ``CURRENT`` held it, as
#: ``DebugChannel.pixelSkipDepth`` prints it.
SKIP = ["[PX]", "SKIP-DEPTH", "3", "7", "0.75", "body/body#up", "existingDepth=0.25"]


def _dump(*lines: list[str]) -> Path:
    """Writes the preamble and then one ``[PX]`` line per field list into a fresh file.

    :param lines: each line's fields, the marker included
    :return: the dump's path
    """
    path = Path(tempfile.mkdtemp()) / "px.log"
    write_text(path, "".join(line + "\n" for line in PREAMBLE)
               + "".join("\t".join(fields) + "\n" for fields in lines))
    return path


class BothWidthsRead(unittest.TestCase):

    def test_the_emitted_line_reads_field_for_field(self):
        written, skipped = frag.parse(_dump(CURRENT))
        self.assertEqual(written, {(3, 7): [FRAGMENT]})
        self.assertEqual(skipped, {})

    def test_a_seventeen_field_line_reads_as_the_same_fragment(self):
        self.assertEqual(frag.parse(_dump(LEGACY)), frag.parse(_dump(CURRENT)))

    def test_the_emission_order_reads_both_widths_alike(self):
        """The census zips on this rather than on ``parse``, so it has to agree on its own."""
        expected = {(3, 7): [("WRITE", "body/head#north", 0.25, 0xFF664D4D, frag.ADD)]}
        self.assertEqual(frag.ordered(_dump(CURRENT)), expected)
        self.assertEqual(frag.ordered(_dump(LEGACY)), expected)

    def test_a_rejected_fragment_reads_as_a_skip_in_both_readers(self):
        dump = _dump(CURRENT, SKIP)
        self.assertEqual(frag.parse(dump), ({(3, 7): [FRAGMENT]}, {
            (3, 7): [{"depth": 0.75, "stage": "SKIP-DEPTH", "tag": "body/body#up"}]}))
        self.assertEqual(frag.ordered(dump), {(3, 7): [
            ("WRITE", "body/head#north", 0.25, 0xFF664D4D, frag.ADD),
            ("SKIP-DEPTH", "body/body#up", 0.75, None, None)]})

    def test_a_line_of_any_other_width_is_refused_by_its_count(self):
        """Read by position, a line of a third layout would land a colour on the wrong field."""
        with self.assertRaises(MissingInput) as caught:
            frag.parse(_dump(CURRENT[:-1]))
        self.assertIn("15 fields", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
