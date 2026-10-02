"""Authors the chrome slicer corpus that ChromeSlicerCorpusTest reads.

Every fixture is drawn from a six-colour palette and geometric primitives, so its intended
nine-slice and its resize ground truth - corner rects, motifs with their anchor and count, periods,
monotone bands - come from the drawing code rather than from a decomposition read back off the
slicer. Each fixture's row in manifest.json carries that truth beside the border, kind and
stretch_inner it declares in vanilla's own ``gui.scaling`` shape, and the manifest's ``steps`` map
names the fixtures that exercise each detector step. The test holds every step to a fixture and
every fixture to a step, so a new fixture is a function here, its call in ``author``, and its
``steps`` row.

    python src/test/resources/scripts/chrome_slicer_corpus.py [--out DIR] [--check]

With no option it rewrites the tracked corpus, src/test/resources/lib/minecraft/renderer/chrome/,
located from the git checkout holding this script, so the working directory never matters.
``--out`` writes the same set into another directory, created if absent. ``--check`` writes nothing:
it authors the corpus in memory and compares it with the one on disk - the tracked corpus, or
``--out`` - by file set, decoded RGBA pixels and every manifest field except ``bytes``, and exits 1
on a difference. PNG bytes are compared as well and reported without failing the check, because
they are a property of the Pillow and zlib build that encoded them, and the test reads neither them
nor ``bytes``.
"""

import argparse
import io
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image

CORPUS = Path("src/test/resources/lib/minecraft/renderer/chrome")
MANIFEST_NOTE = "chrome slicer corpus - fixtures plus the resize ground truth authored with them"

# (file name, image, PNG bytes, manifest row) per fixture, in corpus order
FIXTURES = []

# a fixed six-colour palette so a diff of two outputs is legible
INK = (16, 16, 24, 255)
LIT = (232, 226, 198, 255)
MID = (122, 110, 84, 255)
DIM = (64, 58, 44, 255)
ACC = (198, 74, 58, 255)
ALT = (74, 168, 92, 255)
NIL = (0, 0, 0, 0)

# Which fixture exercises which detector step. Every step the slicer implements has a row, so a
# step that loses its fixture loses its only evidence.
STEPS = {
    "control - a bevel with no feature anywhere": "a01",
    "four band depths held independently": "a06, a18",
    "corner art no single edge scan measures": "a02, a07, a14, a17",
    "longestPeriodicRun, MIN_REPEATS restatements": "a05, a09, a16",
    "both seed orders crossed, scored by interior area": "a23",
    "equally long runs are candidates, not one winner": "a29",
    "band depth sanity - no depth past half its dimension": "a10, a11",
    "stage 1, exact tiling at p <= n/3": "a05, a09, a16",
    "stage 2, modal tile": "a02, a03, a15",
    "stage 2, band-start and band-end tile candidates": "a24",
    "atomic fallback, p = 0": "a13, a08",
    "deviation runs merged across a gap < CONFORM_GAP": "a25",
    "anchor by mirror twin": "a28",
    "anchor CENTER, self-mirrored on the band midpoint": "a04, a23, a30, a31",
    "anchor by nearest end, START + k": "a26",
    "tile index taken outside every feature": "a28",
    "two co-existing periods - the declared model limit": "a27",
    "interior SOLID": "a20",
    "interior TILE": "a19",
    "interior GRADIENT along one axis": "a08",
    "interior STRETCH": "a13",
    "zero-depth band": "a12",
    "minimum size, a feature wider than the target": "a11",
    "degenerate 1 px and 1x1": "a21, a22, a31",
}


def new(w, h, fill=NIL):
    return Image.new("RGBA", (w, h), fill)


def rect(px, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = c


def frame(px, w, h, c, inset=0):
    rect(px, inset, inset, w - 1 - inset, inset, c)
    rect(px, inset, h - 1 - inset, w - 1 - inset, h - 1 - inset, c)
    rect(px, inset, inset, inset, h - 1 - inset, c)
    rect(px, w - 1 - inset, inset, w - 1 - inset, h - 1 - inset, c)


def emit(name, im, border=None, kind="nine_slice", stretch_inner=None, note="", target=None,
         corner=None, motifs=None, periods=None, monotone=None, step=""):
    """Encodes one fixture and records its manifest row.

    ``border``, ``kind`` and ``stretch_inner`` are the declaration, which the test reads off the
    row. ``corner``, ``motifs``, ``periods`` and ``monotone`` are the resize ground truth - authored
    here from the generator's own geometry, never read back off a slicer. They are what the resize
    tier asserts; the declaration alone asserts nothing about a resize.
    """
    buffer = io.BytesIO()
    im.save(buffer, format="PNG", optimize=True)
    data = buffer.getvalue()
    if corner is None and isinstance(border, int):
        corner = [border] * 4
    elif corner is None and isinstance(border, dict):
        corner = [border["left"], border["top"], border["right"], border["bottom"]]
    FIXTURES.append((name + ".png", im, data, {
        "name": name + ".png", "size": "%dx%d" % im.size, "bytes": len(data),
        "border": border, "kind": kind, "stretch_inner": stretch_inner,
        "targets": target or [], "note": note, "step": step,
        "resize": {"corner": corner, "motifs": motifs or [], "periods": periods or {},
                   "monotone": monotone or []},
    }))


def bevel():
    """A01 - plain 3 px bevel, the vanilla chest chrome reduced to its arithmetic."""
    w = h = 24
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 1, LIT)
    rect(px, 1, 1, 1, h - 2, LIT)
    rect(px, 2, h - 2, w - 2, h - 2, DIM)
    rect(px, w - 2, 2, w - 2, h - 2, DIM)
    emit("a01_plain_bevel", im, border=3, note="symmetric 3 px bevel, constant middle",
         target=[[24, 24], [176, 222], [40, 24], [24, 200]])


def chamfer():
    """A02 - 4 px staircase chamfer at every corner."""
    w = h = 32
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 1, LIT)
    rect(px, 1, 1, 1, h - 2, LIT)
    for i in range(4):
        for (cx, cy) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
            sx = 1 if cx == 0 else -1
            sy = 1 if cy == 0 else -1
            for j in range(4 - i):
                px[cx + sx * j, cy + sy * i] = NIL
            px[cx + sx * (4 - i), cy + sy * i] = INK
    emit("a02_chamfer_corner", im, border=6, note="4 px diagonal staircase, alpha cut outside it",
         target=[[32, 32], [200, 60], [60, 200]])


def ornate():
    """A03 - a 10 px corner glyph in a second colour, edge line in a first."""
    w = h = 40
    im = new(w, h)
    px = im.load()
    frame(px, w, h, MID)
    for (cx, cy, sx, sy) in ((0, 0, 1, 1), (w - 1, 0, -1, 1), (0, h - 1, 1, -1), (w - 1, h - 1, -1, -1)):
        for j in range(10):
            px[cx + sx * j, cy] = ACC
            px[cx, cy + sy * j] = ACC
        for j in range(4, 8):
            px[cx + sx * j, cy + sy * 3] = ACC
            px[cx + sx * 3, cy + sy * j] = ACC
        px[cx + sx * 3, cy + sy * 3] = LIT
        px[cx + sx * 6, cy + sy * 6] = LIT
    emit("a03_ornate_corner", im, border=12, note="10 px two-colour corner glyph, 1 px edge line",
         target=[[40, 40], [180, 40], [40, 180], [26, 26]])


def centred_top():
    """A04 - a centred crest on the top edge, a shape a nine-slice cannot place."""
    w, h = 64, 24
    im = new(w, h, DIM)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 2, MID)
    cx = w // 2
    for i in range(7):
        rect(px, cx - i, 0 + (6 - i), cx + i, 6 - i, ACC)
    px[cx, 0] = LIT
    emit("a04_centred_top_ornament", im, border=8,
         note="7 px tall crest centred on the top edge; no nine-slice cell can hold it",
         target=[[64, 24], [128, 24], [200, 24], [30, 24]],
         step="anchor CENTER, self-mirrored and on the band midpoint",
         motifs=[{"edge": "TOP", "off": 26 - 8, "width": 13, "anchor": "CENTER", "count": 1}])


def stud_period():
    """A05 - a 7 px stud period in a 100 px sprite; 100 is not a multiple of 7."""
    w, h = 100, 20
    im = new(w, h, DIM)
    px = im.load()
    frame(px, w, h, INK)
    for x in range(0, w):
        if x % 7 == 3:
            rect(px, x, 1, x, 4, ACC)
            rect(px, x, h - 5, x, h - 2, ACC)
    for y in range(0, h):
        if y % 7 == 3:
            rect(px, 1, y, 4, y, ACC)
            rect(px, w - 5, y, w - 2, y, ACC)
    emit("a05_stud_period_7_of_100", im, border=6,
         note="stud every 7 px; 100 % 7 == 2 so the sprite itself already truncates a stud",
         target=[[100, 20], [107, 20], [103, 20], [176, 20]],
         step="stage-1 exact tiling at a period the band length does not divide",
         periods={"TOP": 7, "BOTTOM": 7})


def asymmetric():
    """A06 - 9 px of art on the left, 2 px on the right, 5 px top, 1 px bottom."""
    w, h = 48, 32
    im = new(w, h, MID)
    px = im.load()
    rect(px, 0, 0, 8, h - 1, DIM)
    rect(px, 0, 0, 2, h - 1, INK)
    rect(px, 4, 0, 4, h - 1, ACC)
    rect(px, w - 2, 0, w - 1, h - 1, INK)
    rect(px, 0, 0, w - 1, 4, LIT)
    rect(px, 0, 0, w - 1, 1, INK)
    rect(px, 0, h - 1, w - 1, h - 1, INK)
    emit("a06_asymmetric_sides", im, border={"left": 9, "top": 5, "right": 2, "bottom": 1},
         note="left art 9 px, right 2 px, top 5 px, bottom 1 px; no square border reproduces it",
         target=[[48, 32], [200, 32], [48, 120], [14, 8]])


def straddle():
    """A07 - a diamond centred exactly on the top-left corner, half of it off-sprite."""
    w = h = 36
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    for dy in range(-8, 9):
        for dx in range(-8, 9):
            if abs(dx) + abs(dy) <= 8:
                x, y = 6 + dx, 6 + dy
                if 0 <= x < w and 0 <= y < h:
                    px[x, y] = ACC if abs(dx) + abs(dy) > 4 else LIT
    emit("a07_corner_straddle", im, border=14,
         note="a 17 px diamond centred at (6,6); its lower-right lobe reaches into the edge cells",
         target=[[36, 36], [120, 36], [36, 120], [24, 24]])


def gradient_len():
    """A08 - the border colour ramps top to bottom along the left and right edges."""
    w, h = 32, 64
    im = new(w, h)
    px = im.load()
    for y in range(h):
        t = y / (h - 1)
        c = (int(48 + 180 * t), int(24 + 60 * t), int(96 + 120 * (1 - t)), 255)
        rect(px, 0, y, 2, y, c)
        rect(px, w - 3, y, w - 1, y, c)
    rect(px, 0, 0, w - 1, 2, (48, 24, 216, 255))
    rect(px, 0, h - 3, w - 1, h - 1, (228, 84, 96, 255))
    rect(px, 3, 3, w - 4, h - 4, (20, 20, 28, 200))
    emit("a08_gradient_along_length", im, border=3, stretch_inner=True,
         note="vertical ramp #3018D8 -> #E45460 down both side edges; a repeated row flattens it",
         target=[[32, 64], [32, 200], [32, 20], [120, 64]],
         step="period-0 band: a ramp has no repeat, and tiling it restarts the ramp",
         monotone=["LEFT", "RIGHT"])


def tall_repeat():
    """A09 - a 12 px repeat unit on a 4 px deep border."""
    w, h = 96, 48
    im = new(w, h, DIM)
    px = im.load()
    frame(px, w, h, INK)
    # Each wave stays inside its own band. The two axes colliding in a corner cell is a16's
    # subject, and mixing it in here would make this fixture assert two things at once.
    for x in range(4, w - 4):
        phase = x % 12
        depth = (1, 2, 3, 4, 3, 2, 1, 1, 1, 1, 1, 1)[phase]
        rect(px, x, 1, x, depth, ACC)
        rect(px, x, h - 1 - depth, x, h - 2, ACC)
    for y in range(4, h - 4):
        phase = y % 12
        depth = (1, 2, 3, 4, 3, 2, 1, 1, 1, 1, 1, 1)[phase]
        rect(px, 1, y, depth, y, ACC)
        rect(px, w - 1 - depth, y, w - 2, y, ACC)
    emit("a09_repeat_taller_than_border", im, border=4,
         note="wave of period 12 whose crest is 4 px deep; the repeat unit is 3x the border depth",
         target=[[96, 48], [100, 48], [180, 48], [48, 180]],
         step="stage-1 exact tiling where the repeat unit is deeper than one slice",
         periods={"TOP": 12, "BOTTOM": 12, "LEFT": 12, "RIGHT": 12})


def whole_sprite_border():
    """A10 - declared border leaves a 1 px middle in each axis."""
    w = h = 15
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, h - 2, LIT)
    rect(px, 2, 2, w - 3, h - 3, ACC)
    rect(px, 3, 3, w - 4, h - 4, DIM)
    rect(px, 4, 4, w - 5, h - 5, MID)
    rect(px, 5, 5, w - 6, h - 6, INK)
    rect(px, 6, 6, w - 7, h - 7, LIT)
    rect(px, 7, 7, 7, 7, ACC)
    emit("a10_border_leaves_one_px", im, border=7,
         note="15 px sprite, border 7, middle exactly 1 px; six concentric rings",
         target=[[15, 15], [15, 90], [90, 15], [8, 8], [200, 200]])


def over_constrained():
    """A11 - declared border sum exceeds the sprite, as defrosted's slider_handle does at draw."""
    w, h = 40, 40
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, 24, 24, ACC)
    rect(px, w - 25, h - 25, w - 2, h - 2, LIT)
    emit("a11_over_constrained_border", im, border=25,
         note="border 25 + 25 = 50 > 40; every target smaller than 50 px overlaps two corners",
         target=[[40, 40], [8, 20], [50, 50], [200, 200]],
         step="a declaration vanilla's own validate refuses, so there is nothing to honour",
         corner=None)


def zero_side():
    """A12 - bottom border zero, as vanilla's widget/tab declares."""
    w, h = 40, 24
    im = new(w, h)
    px = im.load()
    rect(px, 0, 0, w - 1, h - 1, (0, 0, 0, 219))
    rect(px, 0, 0, w - 1, 0, (0, 0, 0, 191))
    rect(px, 0, 0, 0, h - 1, (0, 0, 0, 191))
    rect(px, w - 1, 0, w - 1, h - 1, (0, 0, 0, 191))
    rect(px, 1, 1, w - 2, 1, (255, 255, 255, 51))
    rect(px, 1, 1, 1, h - 1, (255, 255, 255, 51))
    emit("a12_zero_bottom_border", im, border={"left": 2, "top": 2, "right": 2, "bottom": 0},
         note="bottom edge and both bottom corners are zero-height cells",
         target=[[40, 24], [130, 24], [20, 24], [40, 60]])


def noise_interior():
    """A13 - per-pixel dither inside a 3 px border, as vanilla's widget/button carries."""
    w, h = 100, 20
    im = new(w, h, MID)
    px = im.load()
    seed = 0x2545F491
    for y in range(h):
        for x in range(w):
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
            v = 96 + (seed >> 16) % 24
            px[x, y] = (v, v, v, 255)
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 1, LIT)
    rect(px, 1, h - 2, w - 2, h - 2, DIM)
    emit("a13_noise_interior", im, border=3,
         note="no two columns are equal; a border inferred from where the art stops finds nothing",
         target=[[100, 20], [200, 20], [40, 20], [100, 60]])


def inset_margin():
    """A14 - 6 px of fully transparent margin before the art starts."""
    w = h = 40
    im = new(w, h)
    px = im.load()
    frame(px, w, h, MID, inset=6)
    frame(px, w, h, ACC, inset=7)
    rect(px, 8, 8, w - 9, h - 9, (16, 16, 24, 216))
    emit("a14_transparent_inset_margin", im, border=9,
         note="edge columns 0..5 are alpha 0 and identical, so a first-difference scan answers 0",
         target=[[40, 40], [160, 40], [40, 160], [20, 20]])


def alpha_graded():
    """A15 - opaque corners fading to a 72 percent edge line, as FurSky's frames do."""
    w = h = 48
    im = new(w, h)
    px = im.load()
    edge = (41, 89, 120)
    for x in range(w):
        d = min(x, w - 1 - x)
        a = 255 if d < 6 else (184 if d > 11 else 255 - (d - 5) * 12)
        px[x, 0] = edge + (a,)
        px[x, h - 1] = edge + (a,)
    for y in range(h):
        d = min(y, h - 1 - y)
        a = 255 if d < 6 else (184 if d > 11 else 255 - (d - 5) * 12)
        px[0, y] = edge + (a,)
        px[w - 1, y] = edge + (a,)
    for (cx, cy, sx, sy) in ((0, 0, 1, 1), (w - 1, 0, -1, 1), (0, h - 1, 1, -1), (w - 1, h - 1, -1, -1)):
        for j in range(5):
            px[cx + sx * j, cy] = (186, 148, 81, 255)
            px[cx, cy + sy * j] = (186, 148, 81, 255)
    emit("a15_alpha_graded_edge", im, border=14, stretch_inner=True,
         note="alpha ramps 255 -> 184 over columns 5..12; a repeated edge column hard-cuts the fade",
         target=[[48, 48], [200, 48], [48, 200], [28, 28]])


def diagonal_hatch():
    """A16 - a 45 degree hatch crossing the whole border, aligned to no axis."""
    w = h = 64
    im = new(w, h, DIM)
    px = im.load()
    for y in range(h):
        for x in range(w):
            if min(x, y, w - 1 - x, h - 1 - y) < 8 and (x + y) % 6 < 3:
                px[x, y] = ACC
    frame(px, w, h, INK)
    rect(px, 8, 8, w - 9, h - 9, MID)
    emit("a16_diagonal_hatch_border", im, border=8,
         note="hatch phase is (x+y) % 6; horizontal and vertical repeats disagree at every corner",
         target=[[64, 64], [64, 70], [130, 64], [64, 130]],
         step="one period per band, where the two axes disagree about phase",
         periods={"TOP": 6, "BOTTOM": 6, "LEFT": 6, "RIGHT": 6})


def double_ring():
    """A17 - two nested rings so the correct border is 5, not the 1 a first-difference scan finds."""
    w = h = 40
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    frame(px, w, h, MID, inset=1)
    frame(px, w, h, LIT, inset=2)
    frame(px, w, h, MID, inset=3)
    frame(px, w, h, ACC, inset=4)
    emit("a17_double_ring", im, border=5,
         note="rings at inset 0, 2 and 4; a scan stopping at the first constant column answers 1",
         target=[[40, 40], [160, 40], [40, 160], [12, 12]])


def corner_l_shape():
    """A18 - corner art 16 px along x but only 3 px along y."""
    w, h = 64, 32
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    for (x0, sx) in ((0, 1), (w - 1, -1)):
        for (y0, sy) in ((0, 1), (h - 1, -1)):
            for j in range(16):
                px[x0 + sx * j, y0 + sy * 1] = ACC
            for j in range(3):
                px[x0 + sx * 1, y0 + sy * j] = LIT
    emit("a18_l_shaped_corner", im, border={"left": 16, "top": 3, "right": 16, "bottom": 3},
         note="the corner cell must be 16 wide and 3 tall; one square border cannot hold it",
         target=[[64, 32], [200, 32], [64, 120], [30, 8]])


def tile_not_dividing():
    """A19 - a tile sprite whose 13 px period divides no realistic target."""
    w, h = 13, 13
    im = new(w, h, DIM)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 3, 3, 9, 9, ACC)
    px[6, 6] = LIT
    emit("a19_tile_13", im, border=None, kind="tile",
         note="13 px tile; every target below clips the last column and row mid-motif",
         target=[[13, 13], [176, 222], [100, 100], [7, 7]])


def flat_fill():
    """A20 - a single colour at 64 px square; any border reproduces it."""
    im = new(64, 64, (58, 52, 70, 255))
    emit("a20_flat_fill", im, border=4,
         note="one colour; the slicer must not invent a border from an image that has none",
         target=[[64, 64], [3, 3], [400, 60]])


def one_pixel():
    """A21 - the degenerate 1x1 and 3x3 pair."""
    im = new(1, 1, ACC)
    emit("a21_one_pixel", im, border=0,
         note="1x1 with border 0; every cell but the centre is empty", target=[[1, 1], [40, 40]])
    im = new(3, 3, MID)
    px = im.load()
    frame(px, 3, 3, INK)
    emit("a22_three_px_ring", im, border=1,
         note="3x3 with border 1; the middle is a single pixel", target=[[3, 3], [176, 222], [2, 2]])


def notch_into_interior():
    """A23 - a centred notch hanging past the border, so the two seed orders disagree."""
    w, h = 128, 64
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 2, DIM)
    rect(px, 1, h - 3, w - 2, h - 2, DIM)
    rect(px, 58, 0, 69, 8, ACC)                        # 12 wide, 6 px past a 3 px border
    emit("a23_notch_into_interior", im, border={"left": 3, "top": 9, "right": 3, "bottom": 3},
         note="a 12 px centred notch reaching 6 px past the top border into the interior",
         target=[[128, 64], [256, 64], [128, 128], [40, 64]],
         step="band depth from both seed orders, scored by interior area",
         motifs=[{"edge": "TOP", "off": 55, "width": 12, "anchor": "CENTER", "count": 1}])


def majority_edge_feature():
    """A24 - one feature covering two thirds of its band, so the modal slice is the feature."""
    w, h = 96, 32
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 4, DIM)
    rect(px, 18, 0, 77, 4, ACC)                        # 60 of the band's 90 slices
    emit("a24_majority_edge_feature", im, border={"left": 3, "top": 5, "right": 3, "bottom": 3},
         note="the per-residue modal slice is the feature's, not the background's",
         target=[[96, 32], [192, 32], [300, 32], [40, 32]],
         step="stage-2 tile candidates: modal versus band-start versus band-end",
         motifs=[{"edge": "TOP", "off": 15, "width": 60, "anchor": "CENTER", "count": 1}])


def gutter_merge():
    """A25 - three deviating runs separated by 7 conforming slices, as the crafting table has."""
    w, h = 128, 32
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 2, DIM)
    # Three runs of unequal width so no single period explains them, 50 slices of a 122 slice
    # band so the background stays modal, and 7 conforming slices between - one short of the gap.
    for x0, x1, c in ((39, 52, ACC), (60, 69, LIT), (77, 88, ALT)):
        rect(px, x0, 0, x1, 2, c)
    emit("a25_gutter_merge", im, border=3,
         note="three runs of 14, 10 and 12 slices with 7-slice conforming gutters; the gap merges them",
         target=[[128, 32], [200, 32], [256, 32], [90, 32]],
         step="deviation runs merged across a gap shorter than CONFORM_GAP",
         motifs=[{"edge": "TOP", "off": 36, "width": 50, "anchor": "CENTER", "count": 1}])


def two_centres():
    """A26 - one feature at the true centre and one a third along, which is undecidable."""
    w, h = 120, 32
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 2, DIM)
    rect(px, 57, 0, 62, 2, ACC)                        # band-local 54, the true centre
    rect(px, 37, 0, 40, 2, ALT)                        # band-local 34, a third along
    emit("a26_two_centres", im, border=3,
         note="one centred feature and one at a third; one sample cannot tell START+34 from a third",
         target=[[120, 32], [240, 32], [180, 32], [60, 32]],
         step="anchor fallback to the nearer end when a feature is neither mirrored nor centred",
         motifs=[{"edge": "TOP", "off": 54, "width": 6, "anchor": "CENTER", "count": 1},
                 {"edge": "TOP", "off": 34, "width": 4, "anchor": "UNDECIDED", "count": 1}])


def dual_period():
    """A27 - a band carrying two co-existing periods, which the model cannot hold."""
    w, h = 64, 92
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, 2, h - 2, DIM)
    rect(px, w - 3, 1, w - 2, h - 2, DIM)
    for y in range(6, 86, 4):
        rect(px, 0, y, 2, y + 1, ACC)                  # period 4 along the left band
    for y in range(9, 86, 23):
        rect(px, 0, y, 2, y + 2, ALT)                  # period 23, co-existing
    emit("a27_dual_period", im, border=3,
         note="dashes every 4 px and tabs every 23 px in one band; lcm 92 exceeds the search bound",
         target=[[64, 92], [64, 184], [64, 138], [64, 46]],
         step="one period per band - the declared model limit",
         motifs=[{"edge": "LEFT", "off": 6, "width": 3, "anchor": "PERIODIC", "count": 4,
                  "expect_fail": "the period-23 tab is reported as anchored features, not a period"}])


def corner_bracket():
    """A28 - an L bracket wrapping two corners, and a band that opens with a feature."""
    w, h = 96, 64
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 2, DIM)
    rect(px, 1, h - 3, w - 2, h - 2, DIM)
    # The two brackets take different colours so a census can tell them apart; they are still a
    # mirror twin, because the twin rule compares spans and never pixels.
    rect(px, 0, 0, 13, 2, ACC); rect(px, 0, 0, 2, 13, ACC)
    rect(px, w - 14, 0, w - 1, 2, ALT); rect(px, w - 3, 0, w - 1, 13, ALT)
    emit("a28_corner_bracket", im, border=3,
         note="the bracket is a corner rect plus a START feature in two bands, adjacent at any size",
         target=[[96, 64], [192, 64], [96, 128], [40, 40]],
         step="mirror-twin anchoring, and a tile index taken from outside every feature",
         motifs=[{"edge": "TOP", "off": 0, "width": 11, "anchor": "START", "count": 1},
                 {"edge": "TOP", "off": 79, "width": 11, "anchor": "END", "count": 1},
                 {"edge": "LEFT", "off": 0, "width": 11, "anchor": "START", "count": 1},
                 {"edge": "RIGHT", "off": 0, "width": 11, "anchor": "START", "count": 1}])


def twin_lattice():
    """A29 - two equally long lattices of one period, only one of which is the interior."""
    w, h = 96, 96
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    for y in range(8, 40):
        if y % 8 < 2:
            rect(px, 3, y, w - 4, y, DIM)              # lattice A, rows 8..39
    for y in range(56, 88):
        if y % 8 < 2:
            rect(px, 3, y, w - 4, y, LIT)              # lattice B, rows 56..87, same period
    emit("a29_twin_lattice", im, border=3,
         note="two period-8 row lattices of 32 rows each; the tie is real and area breaks it",
         target=[[96, 96], [192, 96], [96, 192], [48, 48]],
         step="equally long periodic runs are candidates, not one winner")


def odd_centre():
    """A30 - odd sprite width against an even feature width."""
    w, h = 97, 32
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 1, 1, w - 2, 1, DIM)
    rect(px, 46, 0, 49, 1, ACC)
    emit("a30_odd_centre", im, border=2,
         note="a 4 px feature in a 93 slice band; both centring parities are wrong at once",
         target=[[97, 32], [194, 32], [151, 32], [51, 32]],
         step="CENTER placement carries a signed nudge off the left-biased floor",
         motifs=[{"edge": "TOP", "off": 44, "width": 4, "anchor": "CENTER", "count": 1}])


def one_px_border():
    """A31 - 1 px bands with a centred feature inside them."""
    w, h = 80, 40
    im = new(w, h, MID)
    px = im.load()
    frame(px, w, h, INK)
    rect(px, 38, 0, 41, 0, ACC)
    emit("a31_one_px_border", im, border=1,
         note="1 px deep bands and 1x1 corner rects, with a 4 px centred feature in the top band",
         target=[[80, 40], [160, 40], [80, 80], [20, 20]],
         step="a band one pixel deep, and 1x1 corner rects",
         motifs=[{"edge": "TOP", "off": 37, "width": 4, "anchor": "CENTER", "count": 1}])


def author():
    """Draws every fixture once, in corpus order, and returns the manifest describing them."""
    FIXTURES.clear()
    for fn in (bevel, chamfer, ornate, centred_top, stud_period, asymmetric, straddle,
               gradient_len, tall_repeat, whole_sprite_border, over_constrained, zero_side,
               noise_interior, inset_margin, alpha_graded, diagonal_hatch, double_ring,
               corner_l_shape, tile_not_dividing, flat_fill, one_pixel,
               notch_into_interior, majority_edge_feature, gutter_merge, two_centres,
               dual_period, corner_bracket, twin_lattice, odd_centre, one_px_border):
        fn()
    return {"//": MANIFEST_NOTE, "fixtures": [row for *_, row in FIXTURES], "steps": STEPS}


def manifest_text(manifest):
    return json.dumps(manifest, indent=2) + "\n"


def write(out, manifest):
    """Writes every fixture and the manifest into ``out``."""
    out.mkdir(parents=True, exist_ok=True)
    for name, _, data, _ in FIXTURES:
        (out / name).write_bytes(data)
    # newline="\n" so Python does not translate to the platform separator: on Windows the default
    # would put CRLF into a file whose blob is LF.
    (out / "manifest.json").write_text(manifest_text(manifest), encoding="utf-8", newline="\n")
    for m in manifest["fixtures"]:
        print("%-32s %-8s %5d B  border=%-34s %s" % (
            m["name"], m["size"], m["bytes"], json.dumps(m["border"]), m["note"]))
    print("%d files, %d bytes of PNG, written to %s" % (
        len(FIXTURES), sum(len(data) for _, _, data, _ in FIXTURES), out))


def without_bytes(manifest):
    rows = [{k: v for k, v in row.items() if k != "bytes"} for row in manifest.get("fixtures", [])]
    return {**manifest, "fixtures": rows}


def check(out, manifest):
    """Compares the corpus authored in memory with the one in ``out`` and returns each difference.

    Decoded pixels and the manifest minus ``bytes`` are the gate. PNG and manifest bytes are
    printed beside them and never fail it.
    """
    authored = {name: (im, data) for name, im, data, _ in FIXTURES}
    on_disk = {p.name for p in out.glob("*.png")}
    problems = ["%s: authored here and absent from disk" % n
                for n in sorted(authored.keys() - on_disk)]
    problems += ["%s: on disk and authored by nothing here" % n
                 for n in sorted(on_disk - authored.keys())]
    same_bytes = 0
    for name in sorted(authored.keys() & on_disk):
        im, data = authored[name]
        stored = (out / name).read_bytes()
        same_bytes += stored == data
        with Image.open(io.BytesIO(stored)) as decoded:
            pixels = decoded.convert("RGBA")
        if pixels.size != im.size or pixels.tobytes() != im.convert("RGBA").tobytes():
            problems.append("%s: decoded pixels differ" % name)
    path = out / "manifest.json"
    if not path.is_file():
        problems.append("manifest.json: absent from %s" % out)
        print("PNG bytes identical: %d of %d" % (same_bytes, len(authored)))
        return problems
    text = path.read_bytes()
    want, have = without_bytes(manifest), without_bytes(json.loads(text))
    for key in sorted((want.keys() | have.keys()) - {"fixtures"}):
        if want.get(key) != have.get(key):
            problems.append("manifest.json: '%s' differs" % key)
    want_rows = {row["name"]: row for row in want["fixtures"]}
    have_rows = {row.get("name"): row for row in have["fixtures"]}
    differing = [n for n in sorted(want_rows.keys() | have_rows.keys(), key=str)
                 if want_rows.get(n) != have_rows.get(n)]
    problems += ["manifest.json: the row for %s differs" % n for n in differing]
    if not differing and want["fixtures"] != have["fixtures"]:
        problems.append("manifest.json: the rows are in a different order")
    print("PNG bytes identical: %d of %d; manifest bytes identical: %s" % (
        same_bytes, len(authored), text == manifest_text(manifest).encode("utf-8")))
    return problems


def main():
    parser = argparse.ArgumentParser(description="Authors the chrome slicer corpus.")
    parser.add_argument("--out", type=Path,
                        help="corpus directory (default: the tracked corpus in this checkout)")
    parser.add_argument("--check", action="store_true",
                        help="compare against the corpus on disk instead of writing it")
    args = parser.parse_args()
    if args.out is None:
        root = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True,
                              text=True, check=True, cwd=Path(__file__).resolve().parent)
        args.out = Path(root.stdout.strip()) / CORPUS
    manifest = author()
    named = set()
    for v in STEPS.values():
        named.update(s.strip() for s in v.split(","))
    have = {name[:3] for name, *_ in FIXTURES}
    print("%d detector steps, %d fixtures named, %d fixtures unnamed by any step"
          % (len(STEPS), len(named), len(have - named)))
    if not args.check:
        write(args.out, manifest)
        return 0
    problems = check(args.out, manifest)
    for problem in problems:
        print("DIFFERS " + problem)
    verdict = "FAIL, %d differences" % len(problems) if problems else "PASS"
    print("check %s: %s" % (args.out, verdict))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
