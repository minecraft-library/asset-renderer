"""The panel probes - what a sweep row's panel shows, read back as numbers.

**A probe, never a gate.** Its numbers are evidence and can never be cited as a verdict. That is why
it may need pixels at all: nothing that produces or verifies a stored artifact is allowed to.

Three readings, one per ``panel`` subcommand:

``stats``    ``report.panel-stats``, fourteen numbers that otherwise exist only as glyphs in a PNG
``peek``     a row's worst pixels, where each sits on every canvas, and the dump region around them
``frames``   an animated row frame by frame - its subject holds ``vanilla/`` and ``java/`` trees of
             ``frame_NNN.png`` rather than one pair, so ``stats`` reads none of it

Two metrics are reported side by side and they are **named apart**, because conflating them is a
recorded defect - one script computed only the second while another calibrated tier thresholds
against it and then compared them to sweep numbers:

``mean_over_white``      0..765,  the shipped metric, comparable to ``mean_argb_delta``
``mean_abs_argb_1020``   0..1020, raw ARGB, comparable to nothing else in the repo

The 0..1020 column survives only because the cluster taxonomy's thresholds are expressed in it. It
is never summed and never stored as a baseline.

Both renders are centred on their union canvas before any number but the content box is taken, as
the sweeps pad them, so a pair whose canvases differ reads the value its sweep row carries - and an
animated row's per-frame mean is the one its sweep averaged.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from parity import pixels
from parity.norm import MissingInput

#: Per-channel threshold separating a "big" disagreement from the sub-LSB floor. This is the split
#: the ">= 1.0 is a recoverable bug, < 1.0 is the sub-texel floor" rule actually turns on.
BIG = 24

#: A pixel is in a row's tail when its over-white delta exceeds this. One step on each of the three
#: channels is 3, the floor below which two shader-float implementations cannot be told apart.
TAIL = 6

#: How far ``peek`` looks for a colour around a pixel: the square it searches is
#: ``2 * RADIUS + 1`` wide.
RADIUS = 4

#: The farthest ``peek`` looks at all. The neighbourhoods are read as one ``top x 4 x side x side``
#: array, which this keeps to a few megabytes at the largest ``top``.
RADIUS_LIMIT = 16

#: How many pixels ``peek`` reports unless asked for another count, and the most it reports at all.
TOP = 20
TOP_LIMIT = 200

#: One frame of an animated sweep, as both animated sweeps name it.
_FRAME = re.compile(r"frame_(\d+)\.png")


def over_white(channel: Any, alpha: Any) -> Any:
    """``(c*a + 255*(255-a) + 127) // 255`` - byte-for-byte ``ParityMetrics.compositeOverWhite``.

    Integer floor division matches Java's ``int`` division because both operands are non-negative.
    """
    return (channel * alpha + 255 * (255 - alpha) + 127) // 255


def _pair(directory: Path) -> tuple[Any, Any]:
    vanilla, java = directory / "vanilla.png", directory / "java.png"
    if not (vanilla.is_file() and java.is_file()):
        raise MissingInput(f"{directory} has no vanilla.png / java.png pair")
    return pixels.load_rgba(vanilla), pixels.load_rgba(java)


def _origin(image: Any, height: int, width: int) -> tuple[int, int]:
    """The ``(top, left)`` at which ``_pad`` puts the image on a ``height x width`` canvas.

    Floor division matches Java's ``int`` division because both operands are non-negative.
    """
    return (height - image.shape[0]) // 2, (width - image.shape[1]) // 2


def _place(image: Any, top: int, left: int, height: int, width: int) -> Any:
    """The image with its top-left at ``(top, left)`` on a transparent ``height x width`` canvas."""
    numpy = pixels.numpy_module()
    canvas = numpy.zeros((height, width, 4), dtype=image.dtype)
    canvas[top:top + image.shape[0], left:left + image.shape[1]] = image
    return canvas


def _pad(vanilla: Any, java: Any) -> tuple[Any, Any]:
    """Centre both sides on their union canvas, as ``ParityMetrics.padToCanvas`` does before
    ``compareImages`` takes the mean over the whole of it.

    Each side lands at its ``_origin`` on a transparent canvas, and a side already the size of the
    union is returned unmoved, which is ``padToCanvas``'s fast path.
    """
    height = max(vanilla.shape[0], java.shape[0])
    width = max(vanilla.shape[1], java.shape[1])
    padded = []
    for image in (vanilla, java):
        if image.shape[:2] == (height, width):
            padded.append(image)
            continue
        padded.append(_place(image, *_origin(image, height, width), height, width))
    return padded[0], padded[1]


def delta_over_white(vanilla: Any, java: Any) -> Any:
    """Per-pixel ``|dR|+|dG|+|dB|`` on the composited-over-white channels."""
    numpy = pixels.numpy_module()
    total = numpy.zeros(vanilla.shape[:2], dtype=numpy.int64)
    for channel in range(3):
        total += numpy.abs(over_white(vanilla[:, :, channel], vanilla[:, :, 3])
                           - over_white(java[:, :, channel], java[:, :, 3]))
    return total


def stats(directory: Path, columns: bool = False, bbox: bool = False) -> dict:
    """Every number over the pair padded onto its union canvas, which is what the sweep's mean is
    taken over. ``canvases`` keeps each side's own size, and the content box is taken on that side's
    own canvas, where its renderer put the content."""
    numpy = pixels.numpy_module()
    vanilla_own, java_own = _pair(directory)
    vanilla, java = _pad(vanilla_own, java_own)
    height, width = vanilla.shape[0], vanilla.shape[1]
    count = height * width

    delta = delta_over_white(vanilla, java)
    raw = numpy.abs(vanilla - java).sum(axis=2)

    covered_v = vanilla[:, :, 3] > 0
    covered_j = java[:, :, 3] > 0
    both = covered_v & covered_j
    vanilla_only = covered_v & ~covered_j
    java_only = covered_j & ~covered_v

    mass = int(delta.sum())
    out = {
        "subject": directory.name,
        "canvas": {"height": height, "width": width},
        "canvases": {
            "java": {"height": int(java_own.shape[0]), "width": int(java_own.shape[1])},
            "vanilla": {"height": int(vanilla_own.shape[0]), "width": int(vanilla_own.shape[1])},
        },
        "mean_over_white": float(delta.sum()) / count,
        "mean_abs_argb_1020": float(raw.sum()) / count,
        "mean_signed_luma": float((_luma(vanilla) - _luma(java)).mean()),
        "differing_pixels": int((delta > 0).sum()),
        "coverage": {
            "both": int(both.sum()),
            "java": int(covered_j.sum()),
            "java_only": int(java_only.sum()),
            "vanilla": int(covered_v.sum()),
            "vanilla_only": int(vanilla_only.sum()),
        },
        "attribution": _attribution(delta, both, vanilla_only, java_only, mass),
        "silhouette": _silhouette(covered_v, covered_j),
        "quadrants": _quadrants(vanilla, java, delta, covered_v | covered_j),
    }
    if columns:
        out["columns"] = _columns(raw, width)
    if bbox:
        out["bbox"] = {"java": _bbox(java_own[:, :, 3] > 0),
                       "vanilla": _bbox(vanilla_own[:, :, 3] > 0)}
    return out


def _luma(image: Any) -> Any:
    """Rec.601 over the composited channels, so a transparent pixel reads as white rather than 0."""
    numpy = pixels.numpy_module()
    red = over_white(image[:, :, 0], image[:, :, 3]).astype(numpy.float64)
    green = over_white(image[:, :, 1], image[:, :, 3]).astype(numpy.float64)
    blue = over_white(image[:, :, 2], image[:, :, 3]).astype(numpy.float64)
    return 0.299 * red + 0.587 * green + 0.114 * blue


def _attribution(delta: Any, both: Any, vanilla_only: Any, java_only: Any, mass: int) -> dict:
    """Where the mass sits: a coverage or centring fault reads completely differently from a
    shading floor, and this is the split that tells them apart."""
    if mass == 0:
        return {"both_colour": 0.0, "big_mass": 0.0, "big_pixels": 0,
                "java_only": 0.0, "total": 0, "vanilla_only": 0.0}
    big = delta[both] > BIG
    return {
        "both_colour": 100.0 * float(delta[both].sum()) / mass,
        "big_mass": 100.0 * float(delta[both][big].sum()) / mass,
        "big_pixels": int(big.sum()),
        "java_only": 100.0 * float(delta[java_only].sum()) / mass,
        "java_only_px": int(java_only.sum()),
        "total": mass,
        "vanilla_only": 100.0 * float(delta[vanilla_only].sum()) / mass,
        "vanilla_only_px": int(vanilla_only.sum()),
    }


def _silhouette(covered_v: Any, covered_j: Any) -> dict:
    union = int((covered_v | covered_j).sum())
    intersection = int((covered_v & covered_j).sum())
    vanilla, java = int(covered_v.sum()), int(covered_j.sum())
    return {
        "coverage_imbalance": 0.0 if union == 0 else abs(vanilla - java) / union,
        "intersection": intersection,
        "iou": 0.0 if union == 0 else intersection / union,
        "union": union,
    }


def _quadrants(vanilla: Any, java: Any, delta: Any, covered: Any) -> dict:
    """Split at the **silhouette bbox centre**, not the canvas centre - a subject that does not
    fill its canvas would otherwise report three empty quadrants.

    A silhouette one row or one column thick against the canvas edge leaves a quadrant holding no
    pixel at all, which reports 0 rather than the NaN an empty mean answers."""
    numpy = pixels.numpy_module()
    box = _bbox(covered)
    if box is None:
        return {}
    mid_y = (box["y0"] + box["y1"] + 1) // 2
    mid_x = (box["x0"] + box["x1"] + 1) // 2
    signed = _luma(vanilla) - _luma(java)
    out = {}
    for name, rows, cols in (("tl", slice(None, mid_y), slice(None, mid_x)),
                             ("tr", slice(None, mid_y), slice(mid_x, None)),
                             ("bl", slice(mid_y, None), slice(None, mid_x)),
                             ("br", slice(mid_y, None), slice(mid_x, None))):
        quadrant = delta[rows, cols]
        out[name] = {"abs": float(quadrant.mean()) if quadrant.size else 0.0,
                     "signed_luma": float(signed[rows, cols].mean()) if quadrant.size else 0.0}
    left = out["tl"]["abs"] + out["bl"]["abs"]
    right = out["tr"]["abs"] + out["br"]["abs"]
    top = out["tl"]["abs"] + out["tr"]["abs"]
    bottom = out["bl"]["abs"] + out["br"]["abs"]
    out["asymmetry"] = {
        "diagonal": abs((out["tl"]["abs"] + out["br"]["abs"])
                        - (out["tr"]["abs"] + out["bl"]["abs"])),
        "left_right": abs(left - right),
        "top_bottom": abs(top - bottom),
    }
    return out


def _columns(raw: Any, width: int) -> dict:
    """The centre-column share and the width parity, in the shape the frozen probe TSVs use.

    For a left-right symmetric subject the fit puts the symmetry axis at exactly ``w / 2``. At an
    odd width that is a pixel **centre** - a sample point - and at an even width it is a boundary no
    sample can land on. That difference was worth 3.27% of the whole entity corpus's error.
    """
    per_column = raw.sum(axis=0)
    total = int(per_column.sum())
    if total == 0:
        return {"total": 0}
    if width % 2 == 1:
        centre = width // 2
    else:
        # At even width the axis is the boundary between the two middle columns; the heavier of the
        # pair is the honest comparison against the odd case's single column.
        centre = width // 2 if per_column[width // 2] >= per_column[width // 2 - 1] else width // 2 - 1
    return {
        "centre_column": int(centre),
        "centre_mass": int(per_column[centre]),
        "centre_share": int(per_column[centre]) / total,
        "peak_column": int(per_column.argmax()),
        "total": total,
        "width_parity": "odd" if width % 2 else "even",
    }


def _bbox(mask: Any) -> dict | None:
    """The painted-content box, which against the canvas is the canvas-mismatch back-solve: it
    decides java-versus-harness from the PNG with no re-render."""
    numpy = pixels.numpy_module()
    rows = numpy.any(mask, axis=1)
    cols = numpy.any(mask, axis=0)
    if not rows.any() or not cols.any():
        return None
    y0, y1 = int(numpy.argmax(rows)), int(len(rows) - 1 - numpy.argmax(rows[::-1]))
    x0, x1 = int(numpy.argmax(cols)), int(len(cols) - 1 - numpy.argmax(cols[::-1]))
    return {"height": y1 - y0 + 1, "width": x1 - x0 + 1, "x0": x0, "x1": x1, "y0": y0, "y1": y1}


def walk(source: Path, subjects: list[str] | None = None, columns: bool = False,
         bbox: bool = False) -> list[dict]:
    if not source.is_dir():
        raise MissingInput(f"no sweep output tree at {source}")
    wanted = set(subjects or [])
    out = []
    for child in sorted(source.iterdir()):
        if not child.is_dir() or (wanted and child.name not in wanted):
            continue
        if not ((child / "vanilla.png").is_file() and (child / "java.png").is_file()):
            continue
        out.append(stats(child, columns=columns, bbox=bbox))
    if not out:
        raise MissingInput(f"no subject with a vanilla/java pair under {source}")
    return out


def peek(directory: Path, top: int = TOP, tail: int = TAIL, radius: int = RADIUS) -> dict:
    """A row's worst pixels, worst first, and where each one sits on every canvas it is read on.

    Each pixel carries its coordinate on the union canvas the sweep scores and on each side's own
    canvas, ``None`` where it falls in that side's padding. The pixel dump reads java's own frame,
    which a centred pad moves on a row whose canvases differ, so the union coordinate alone can name
    the wrong pixels; ``region`` boxes the reported pixels on all three.

    A pixel exactly one side covers is ``coverage``. Any other is ``displaced`` when either side's
    colour appears within ``radius`` of it on the other side - a real colour in the wrong place,
    which is what a texel sampled one over looks like - and ``invented`` when neither does, which
    points at shade, tint or blend rather than at sampling.

    :param directory: the subject directory holding ``vanilla.png`` and ``java.png``
    :param top: how many of the tail's pixels to report
    :param tail: the over-white delta a pixel has to exceed to be in the tail
    :param radius: how far to look for each colour on the other side
    :return: the canvases, the tail count, the pixels and the region around them
    """
    numpy = pixels.numpy_module()
    own = dict(zip(("vanilla", "java"), _pair(directory)))
    vanilla, java = _pad(own["vanilla"], own["java"])
    height, width = vanilla.shape[0], vanilla.shape[1]
    origins = {side: _origin(image, height, width) for side, image in own.items()}

    delta = delta_over_white(vanilla, java)
    ys, xs = numpy.nonzero(delta > tail)
    tail_pixels = len(ys)
    order = numpy.argsort(-delta[ys, xs], kind="stable")[:top]
    ys, xs = ys[order], xs[order]
    java_in_vanilla = _near(vanilla, java[ys, xs], ys, xs, radius)
    vanilla_in_java = _near(java, vanilla[ys, xs], ys, xs, radius)
    coverage = (vanilla[ys, xs, 3] > 0) != (java[ys, xs, 3] > 0)

    found = []
    for index in range(len(ys)):
        y, x = int(ys[index]), int(xs[index])
        near = (None, None) if coverage[index] else (bool(java_in_vanilla[index]),
                                                      bool(vanilla_in_java[index]))
        found.append({
            "delta": int(delta[y, x]),
            "java": _own(x, y, origins["java"], own["java"]),
            "java_colour_in_vanilla": near[0],
            "java_rgba": [int(value) for value in java[y, x]],
            "union": [x, y],
            "vanilla": _own(x, y, origins["vanilla"], own["vanilla"]),
            "vanilla_colour_in_java": near[1],
            "vanilla_rgba": [int(value) for value in vanilla[y, x]],
            "verdict": "coverage" if coverage[index] else "displaced" if any(near) else "invented",
        })
    return {
        "canvas": {"height": height, "width": width},
        "canvases": {side: {"height": int(image.shape[0]), "width": int(image.shape[1])}
                     for side, image in own.items()},
        "pixels": found,
        "radius": radius,
        "region": {
            "java": _region(xs, ys, origins["java"], own["java"].shape),
            "union": _region(xs, ys, (0, 0), vanilla.shape),
            "vanilla": _region(xs, ys, origins["vanilla"], own["vanilla"].shape),
        },
        "subject": directory.name,
        "tail": {"pixels": tail_pixels, "threshold": tail},
    }


def peek_walk(source: Path, subjects: list[str], top: int = TOP, tail: int = TAIL,
              radius: int = RADIUS) -> list[dict]:
    if not source.is_dir():
        raise MissingInput(f"no sweep output tree at {source}")
    return [peek(source / subject, top, tail, radius) for subject in subjects]


def _near(image: Any, colours: Any, ys: Any, xs: Any, radius: int) -> Any:
    """Whether each colour appears anywhere in the ``2 * radius + 1`` square of ``image`` centred on
    its own pixel, every pixel at once.

    The image is bordered with ``-1``, a value no channel holds, so a square reaching past the
    canvas edge matches nothing there; the window view then puts the square centred on ``(y, x)`` of
    the unbordered image at index ``[y, x]``.
    """
    numpy = pixels.numpy_module()
    side = 2 * radius + 1
    bordered = numpy.pad(image, ((radius, radius), (radius, radius), (0, 0)), constant_values=-1)
    windows = numpy.lib.stride_tricks.sliding_window_view(bordered, (side, side), axis=(0, 1))
    matches = (windows[ys, xs] == colours[:, :, None, None]).all(axis=1)
    return matches.any(axis=(1, 2))


def _own(x: int, y: int, origin: tuple[int, int], image: Any) -> list[int] | None:
    """A union-canvas pixel on one side's own canvas, ``None`` where it lies in that side's pad."""
    top, left = origin
    own_x, own_y = x - left, y - top
    if 0 <= own_x < image.shape[1] and 0 <= own_y < image.shape[0]:
        return [own_x, own_y]
    return None


def _region(xs: Any, ys: Any, origin: tuple[int, int], shape: tuple[int, ...]) -> list[int] | None:
    """The inclusive ``[x0, y0, x1, y1]`` box round the pixels on a canvas whose top-left sits at
    ``origin`` on the union, clipped to that canvas - the form the pixel dump and ``lab`` read - or
    ``None`` when no pixel was reported or none of the box lands on it."""
    if len(xs) == 0:
        return None
    top, left = origin
    x0, x1 = max(int(xs.min()) - left, 0), min(int(xs.max()) - left, shape[1] - 1)
    y0, y1 = max(int(ys.min()) - top, 0), min(int(ys.max()) - top, shape[0] - 1)
    return [x0, y0, x1, y1] if x0 <= x1 and y0 <= y1 else None


def frames(directory: Path, after: Path | None = None) -> dict:
    """An animated row frame by frame, and the four numbers its sweep row carries.

    Each frame pair is padded and scored as the sweep scores it, so ``mean_argb_delta``,
    ``worst_delta``, ``worst_frame`` and ``frame_spread`` read what the row does. Below the mean,
    each frame says how many pixels differ, how many of those both sides cover and still differ by
    more than one step on some channel - with their box on the union canvas, their largest step and
    which way java's brightness leans on them - and how many pixels only one side covers.

    ``after`` is a second sweep output tree whose ``<subject>/java/`` frames are scored against the
    same vanilla: each frame then adds its own mean, the pixels that moved between the two java
    renders with their box on the same union canvas, and the delta those pixels carried before and
    after.

    :param directory: the subject directory holding ``vanilla/`` and ``java/`` frame trees
    :param after: a sweep output tree holding the second java render, or ``None``
    :return: the row's four numbers, the per-frame readings and, given ``after``, its four numbers
    :raises MissingInput: if the two sides, or the second java render, hold different frames
    """
    vanilla_frames = _frame_files(directory / "vanilla")
    java_frames = _frame_files(directory / "java")
    if not vanilla_frames or sorted(vanilla_frames) != sorted(java_frames):
        raise MissingInput(f"{directory} holds vanilla frames {sorted(vanilla_frames)} and java "
                           f"frames {sorted(java_frames)}; a row is scored over one set")
    after_frames = None
    if after is not None:
        after_frames = _frame_files(after / directory.name / "java")
        if sorted(after_frames) != sorted(java_frames):
            raise MissingInput(f"{after / directory.name / 'java'} holds frames "
                               f"{sorted(after_frames)}, not the {sorted(java_frames)} it is "
                               f"scored beside")

    rows = []
    for number in sorted(vanilla_frames):
        vanilla_own = pixels.load_rgba(vanilla_frames[number])
        java_own = pixels.load_rgba(java_frames[number])
        row = {"frame": number, **_frame(vanilla_own, java_own)}
        if after_frames is not None:
            row["after"] = _moved(vanilla_own, java_own, pixels.load_rgba(after_frames[number]))
        rows.append(row)
    numbers = [row["frame"] for row in rows]
    out = {"frames": rows, "subject": directory.name,
           **_row([row["mean_over_white"] for row in rows], numbers)}
    if after_frames is not None:
        out["after"] = _row([row["after"]["mean_over_white"] for row in rows], numbers)
    return out


def frames_walk(source: Path, subjects: list[str] | None = None,
                after: Path | None = None) -> list[dict]:
    if not source.is_dir():
        raise MissingInput(f"no sweep output tree at {source}")
    if after is not None and not after.is_dir():
        raise MissingInput(f"no second sweep output tree at {after}")
    wanted = set(subjects or [])
    out = []
    for child in sorted(source.iterdir()):
        if not child.is_dir() or (wanted and child.name not in wanted):
            continue
        if not ((child / "vanilla").is_dir() and (child / "java").is_dir()):
            continue
        out.append(frames(child, after))
    if not out:
        raise MissingInput(f"no subject with vanilla/ and java/ frame trees under {source}")
    return out


def _frame_files(directory: Path) -> dict[int, Path]:
    """Each ``frame_NNN.png`` in the directory, by its frame number."""
    if not directory.is_dir():
        return {}
    return {int(match.group(1)): path for path in directory.iterdir()
            if (match := _FRAME.fullmatch(path.name))}


def _frame(vanilla_own: Any, java_own: Any) -> dict:
    """One frame pair's readings, every one taken on the union canvas the sweep scores."""
    numpy = pixels.numpy_module()
    vanilla, java = _pad(vanilla_own, java_own)
    delta = delta_over_white(vanilla, java)
    covered_v, covered_j = vanilla[:, :, 3] > 0, java[:, :, 3] > 0
    lift = _over_white_rgb(java) - _over_white_rgb(vanilla)
    step = numpy.abs(lift).max(axis=2)
    brightness = lift.sum(axis=2)
    beyond = (step > 1) & covered_v & covered_j
    return {
        "beyond_one_step": {
            "bbox": _bbox(beyond),
            "java_brighter": int((brightness[beyond] > 0).sum()),
            "java_darker": int((brightness[beyond] < 0).sum()),
            "max_step": int(step[beyond].max()) if beyond.any() else 0,
            "pixels": int(beyond.sum()),
        },
        "differing_pixels": int((delta > 0).sum()),
        "mean_over_white": float(delta.sum()) / (delta.shape[0] * delta.shape[1]),
        "silhouette_mismatch": int((covered_v != covered_j).sum()),
    }


def _moved(vanilla_own: Any, java_own: Any, after_own: Any) -> dict:
    """The second java render against the first and against vanilla.

    Each java render is scored on its own pad with vanilla, as its sweep scores it, and keeps the
    offset from vanilla that pad gives it: centred on the union of all three, vanilla can sit a pixel
    off from where either pair's pad puts it, and the shift would read as a difference no sweep
    scored. The moved pixels are read on the first pair's union canvas, grown to hold the second
    render at its offset, and their box is on that first canvas - the one the frame's other readings
    use - running past its edges where the second render paints outside it.
    """
    vanilla, java = _pad(vanilla_own, java_own)
    scored = delta_over_white(*_pad(vanilla_own, after_own))
    height, width = vanilla.shape[:2]
    # The second render's top-left on the first canvas: vanilla's there, moved by the second
    # render's offset from vanilla on their own pad.
    vanilla_y, vanilla_x = _origin(vanilla_own, height, width)
    (pair_y, pair_x), (after_y, after_x) = (_origin(side, *scored.shape)
                                            for side in (vanilla_own, after_own))
    after_top, after_left = vanilla_y + after_y - pair_y, vanilla_x + after_x - pair_x
    top, left = min(after_top, 0), min(after_left, 0)
    grown = (max(height, after_top + after_own.shape[0]) - top,
             max(width, after_left + after_own.shape[1]) - left)
    vanilla, java = (_place(side, -top, -left, *grown) for side in (vanilla, java))
    after = _place(after_own, after_top - top, after_left - left, *grown)

    moved = delta_over_white(java, after) > 0
    box = _bbox(moved)
    if box is not None:
        box.update(x0=box["x0"] + left, x1=box["x1"] + left, y0=box["y0"] + top, y1=box["y1"] + top)
    return {
        "mean_over_white": float(scored.sum()) / (scored.shape[0] * scored.shape[1]),
        "moved_bbox": box,
        "moved_delta_after": int(delta_over_white(vanilla, after)[moved].sum()),
        "moved_delta_before": int(delta_over_white(vanilla, java)[moved].sum()),
        "moved_pixels": int(moved.sum()),
    }


def _row(means: list[float], numbers: list[int]) -> dict:
    """The four numbers an animated sweep row carries, accumulated in frame order as the sweep's own
    loop does rather than through ``sum``, whose compensated total can differ in the last bit."""
    total = 0.0
    for mean in means:
        total += mean
    worst = max(range(len(means)), key=means.__getitem__)
    return {"frame_spread": means[worst] - min(means), "mean_argb_delta": total / len(means),
            "worst_delta": means[worst], "worst_frame": numbers[worst]}


def _over_white_rgb(image: Any) -> Any:
    """The three composited-over-white channels as one ``H x W x 3`` array."""
    numpy = pixels.numpy_module()
    return numpy.stack([over_white(image[:, :, channel], image[:, :, 3]) for channel in range(3)],
                       axis=2)
