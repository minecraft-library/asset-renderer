"""The ``[PX]`` dump parser and the compositing replay.

A ``WRITE`` line is read in either of two widths, told apart by its field count.
``DebugChannel.pixelWrite`` writes 16 fields - the marker, the stage, ``px``, ``py``, depth, tag,
``u``, ``v``, ``tx``, ``ty``, raw, tint, shading, after-shade, blend and out. A 17-field line
carries the after-tint colour between tint and shading. Every other field sits at the same place
from the front or from the back in both, and the after-tint colour is not carried, so a fragment
reads the same from either width.

Only the ``WRITE``, ``SKIP-DEPTH`` and ``SKIP-ALPHA`` lines are read, and a line's stage is checked
before its position is: a ``TRI`` line for every tagged triangle whenever the rect is armed, and the
fit trace's ``FIT``, ``BASE-BOUNDS`` and ``OVERLAY-BOUNDS``, have no pixel position to read.

The replay tracks the ``image`` library's ``BlendMode``, and it accepts **both** spellings of the
additive mode: the enum was renamed ``ADDITIVE`` -> ``ADD`` when the composition was promoted into
that library, so a dump taken before the rename and one taken after are both readable and both
normalize to ``ADD``.

A ``SKIP-FILL`` fragment is invisible to every dump, which is a limit of the instrument rather than
of this parser - it is recorded here because it has cost time twice.
"""

from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from parity.norm import MissingInput, read_lines

MARKER = "[PX]\t"

#: The two stages a rejected fragment logs under with its position, its depth and its tag.
_SKIPS = ("SKIP-DEPTH", "SKIP-ALPHA")

NORMAL, ADD, REPLACE = "NORMAL", "ADD", "REPLACE"

#: The pre-rename spelling, still present in every dump frozen before the promotion.
_BLEND_ALIASES = {"ADDITIVE": ADD, "ADD": ADD, "REPLACE": REPLACE, "NORMAL": NORMAL}

#: Where ``shading`` sits in a ``WRITE`` line, keyed by the line's field count. Raw and tint come
#: before it and after-shade, blend and out after it at the same offsets in both widths.
_SHADING_AT = {16: 12, 17: 13}


def blend_of(token: str) -> str:
    return _BLEND_ALIASES.get(token.strip().upper(), NORMAL)


def _write(parts: list[str]) -> dict:
    """One ``WRITE`` line's fragment, in whichever width it was written.

    :param parts: the line split on tabs, the marker included
    :return: the fragment, keyed by field name
    :raises MissingInput: if the line has neither of the two widths
    """
    shading = _SHADING_AT.get(len(parts))
    if shading is None:
        raise MissingInput(f"a [PX] WRITE line has {len(parts)} fields, and the two layouts read "
                           f"here have {' or '.join(str(width) for width in _SHADING_AT)}: {parts}")
    return {
        "afterShade": int(parts[shading + 1], 16), "blend": blend_of(parts[shading + 2]),
        "depth": float(parts[4]), "out": int(parts[shading + 3], 16), "raw": int(parts[10], 16),
        "shading": float(parts[shading]), "tag": parts[5], "tint": int(parts[11], 16),
        "tx": int(parts[8]), "ty": int(parts[9]),
        "u": float(parts[6]), "v": float(parts[7]),
    }


def _fragments(path: Path) -> list[tuple[str, tuple[int, int], list[str]]]:
    """Each ``WRITE`` and skip line of a dump as ``(stage, (px, py), parts)``, in emission order.

    :param path: the dump
    :return: the lines, every other ``[PX]`` line passed over before its position is read
    """
    out = []
    for line in read_lines(path):
        if not line.startswith(MARKER):
            continue
        parts = line.split("\t")
        if parts[1] == "WRITE" or parts[1] in _SKIPS:
            out.append((parts[1], (int(parts[2]), int(parts[3])), parts))
    return out


def parse(path: Path) -> tuple[dict, dict]:
    """``(px, py) -> [fragment]`` in emission order, plus the depth-skipped ones."""
    written: dict[tuple[int, int], list[dict]] = defaultdict(list)
    skipped: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for stage, key, parts in _fragments(path):
        if stage == "WRITE":
            written[key].append(_write(parts))
        else:
            skipped[key].append({"depth": float(parts[4]), "stage": stage, "tag": parts[5]})
    return dict(written), dict(skipped)


def ordered(path: Path) -> dict:
    """Every stage in emission order, which is what a three-dump join zips on.

    **A debug tag is not a unique key** - a bone face reaches a pixel once as the body's NORMAL
    fragment and again as the aura's ADD one - so position, not tag, is the join key. Reading it the
    other way cost two rebuilds.
    """
    out: dict[tuple[int, int], list[tuple]] = defaultdict(list)
    for stage, key, parts in _fragments(path):
        if stage == "WRITE":
            fragment = _write(parts)
            out[key].append((stage, fragment["tag"], fragment["depth"], fragment["afterShade"],
                             fragment["blend"]))
        else:
            out[key].append((stage, parts[5], float(parts[4]), None, None))
    return dict(out)


def argb(packed: int) -> tuple[int, int, int, int]:
    return ((packed >> 24) & 0xFF, (packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF)


def composite(source: int, blend: str, dst: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    """Replay one fragment onto ``dst`` as ``(a, r, g, b)``."""
    sa, sr, sg, sb = argb(source)
    da, dr, dg, db = dst
    if blend == ADD:
        return (min(255, da + sa), min(255, dr + sr), min(255, dg + sg), min(255, db + sb))
    if blend == REPLACE:
        return (sa, sr, sg, sb)
    if sa == 255:
        return (sa, sr, sg, sb)
    alpha = sa / 255.0
    return (max(sa, da),
            round(sr * alpha + dr * (1 - alpha)),
            round(sg * alpha + dg * (1 - alpha)),
            round(sb * alpha + db * (1 - alpha)))


def replay(fragments: list[dict], keep: list[bool] | None = None) -> tuple[int, int, int, int]:
    dst = (0, 0, 0, 0)
    for index, fragment in enumerate(fragments):
        if keep is None or keep[index]:
            dst = composite(fragment["afterShade"], fragment["blend"], dst)
    return dst


def near(one: tuple, other: tuple, tol: int = 1) -> bool:
    """Within the NVIDIA shader-float floor, which is where the sub-1.0 parity floor comes from."""
    return all(abs(int(a) - int(b)) <= tol for a, b in zip(one, other))


#: The ``2^n`` brute force is only tractable below this, and a pixel with more fragments than this
#: is reported rather than searched.
SUBSET_LIMIT = 13


def subsets_reproducing(fragments: list[dict], target: tuple, tol: int = 1) -> list[tuple[int, ...]]:
    """Every subset of the fragments whose replay lands within ``tol`` of ``target``."""
    count = len(fragments)
    if count > SUBSET_LIMIT:
        return []
    found = []
    for mask in range(1 << count):
        keep = [(mask >> index) & 1 == 1 for index in range(count)]
        if near(replay(fragments, keep), target):
            found.append(tuple(index for index in range(count) if keep[index]))
    return found
