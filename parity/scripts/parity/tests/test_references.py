"""What the reference tree's own drawings have to hold, read off the live tree.

A sub-tree a run adds is measured by nothing until its baseline is promoted - the references manifest
reports every new file as an addition whatever pixels it holds - and a sweep drawing the wrong thing
compiles, boots and exits 0. The cases here state what the client's own drawing makes true of a
sub-tree, so a capture's whole-tree render that draws something else fails the suite the capture runs
behind it. Each skips while the tree lacks the sub-tree it reads.
"""

from __future__ import annotations

import unittest
from pathlib import Path

from parity import pixels, provenance
from parity.norm import sha256_file

REPO = Path(__file__).resolve().parents[4]

#: The live reference tree, or None on a checkout that has never rendered one.
REFERENCE_TREE = next((root for root in [provenance.reference_root(REPO)]
                       if root is not None and root.is_dir()), None)

#: The book references, or None while the tree holds none.
BOOKS = next((books for books in [REFERENCE_TREE / "books" if REFERENCE_TREE is not None else None]
              if books is not None and books.is_dir()), None)

#: Every book reference's size in output px: the 192 x 194 GUI px screen the book is laid out on, at
#: the capture's GUI scale of 2.
BOOK_CANVAS = (384, 388)

#: The bounds of every drawn pixel on every book reference, in output px, left and top inclusive and
#: right and bottom exclusive: the opaque texels of the client's gui/book.png, (20, 1)-(166, 181), blitted
#: at GUI (0, 2) - the screen's own placement at the book's width - at a GUI scale of 2. Every line of a
#: page lies inside the paper.
BOOK_INK = (40, 6, 332, 366)


@unittest.skipUnless(BOOKS is not None, "no book references")
class BookReferences(unittest.TestCase):

    def test_an_empty_page_draws_the_background_alone(self):
        """The visit draws nothing for an empty page with the page-number line left out, so the whole
        frame over one is the frame with the visit left out. A drawing of the page number - the screen's
        click-only flag off, or its state extract called - is the one thing that parts them."""
        self.assertEqual(sha256_file(BOOKS / "empty.png"), sha256_file(BOOKS / "background.png"))

    def test_a_page_stops_at_its_fourteenth_line(self):
        """The screen caps a page at 128 / 9 lines, so sixteen lines draw what the first fourteen do."""
        self.assertEqual(sha256_file(BOOKS / "overflow.png"), sha256_file(BOOKS / "full_page.png"))

    @unittest.skipUnless(pixels.available(), "Pillow/numpy absent")
    def test_every_page_is_the_book_where_the_client_puts_it(self):
        """One canvas size for every page, and the paper at the screen's own placement on each. A
        layout at another width moves the book across the canvas and leaves the canvas as it was."""
        numpy = pixels.numpy_module()
        pages = sorted(BOOKS.glob("*.png"))
        self.assertTrue(pages, "books/ holds no PNG")
        for page in pages:
            with self.subTest(page=page.name):
                rgba = pixels.load_rgba(page)
                height, width = rgba.shape[:2]
                self.assertEqual((width, height), BOOK_CANVAS)
                rows = numpy.flatnonzero(rgba[..., 3].any(axis=1))
                columns = numpy.flatnonzero(rgba[..., 3].any(axis=0))
                self.assertTrue(rows.size, "draws nothing")
                self.assertEqual((int(columns[0]), int(rows[0]), int(columns[-1]) + 1, int(rows[-1]) + 1),
                                 BOOK_INK)


if __name__ == "__main__":
    unittest.main()
