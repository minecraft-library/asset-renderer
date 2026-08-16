"""``report.panel-stats`` - the fourteen numbers that today exist only as glyphs inside a PNG.

**A probe, never a gate.** Its numbers are evidence and can never be cited as a verdict. That is why
it may need pixels at all: nothing that produces or verifies a stored artifact is allowed to.

Two metrics are reported side by side and they are **named apart**, because conflating them is a
recorded defect - one script computed only the second while another calibrated tier thresholds
against it and then compared them to sweep numbers:

``mean_over_white``      0..765,  the shipped metric, comparable to ``mean_argb_delta``
``mean_abs_argb_1020``   0..1020, raw ARGB, comparable to nothing else in the repo

The 0..1020 column survives only because the cluster taxonomy's thresholds are expressed in it. It
is never summed and never stored as a baseline.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from parity import pixels
from parity.norm import MissingInput

#: Per-channel threshold separating a "big" disagreement from the sub-LSB floor. This is the split
#: the ">= 1.0 is a recoverable bug, < 1.0 is the sub-texel floor" rule actually turns on.
BIG = 24


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


def _crop(vanilla: Any, java: Any) -> tuple[Any, Any]:
    """Crop to the overlap, exactly as ``ParityMetrics.compareImages`` takes ``min(w)``/``min(h)``."""
    height = min(vanilla.shape[0], java.shape[0])
    width = min(vanilla.shape[1], java.shape[1])
    return vanilla[:height, :width], java[:height, :width]


def delta_over_white(vanilla: Any, java: Any) -> Any:
    """Per-pixel ``|dR|+|dG|+|dB|`` on the composited-over-white channels."""
    numpy = pixels.numpy_module()
    total = numpy.zeros(vanilla.shape[:2], dtype=numpy.int64)
    for channel in range(3):
        total += numpy.abs(over_white(vanilla[:, :, channel], vanilla[:, :, 3])
                           - over_white(java[:, :, channel], java[:, :, 3]))
    return total


def stats(directory: Path, columns: bool = False, bbox: bool = False) -> dict:
    numpy = pixels.numpy_module()
    vanilla, java = _crop(*_pair(directory))
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
        out["bbox"] = {"java": _bbox(covered_j), "vanilla": _bbox(covered_v)}
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
    fill its canvas would otherwise report three empty quadrants."""
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
        out[name] = {"abs": float(delta[rows, cols].mean()),
                     "signed_luma": float(signed[rows, cols].mean())}
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
