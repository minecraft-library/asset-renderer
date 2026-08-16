"""The ``[PX]`` dump parser and the compositing replay.

The 17-field ``WRITE`` grammar goes over unchanged. The replay tracks the ``image`` library's
``BlendMode``, and it accepts **both** spellings of the additive mode: the enum was renamed
``ADDITIVE`` -> ``ADD`` when the composition was promoted into that library, so a dump taken before
the rename and one taken after are both readable and both normalize to ``ADD``.

A ``SKIP-FILL`` fragment is invisible to every dump, which is a limit of the instrument rather than
of this parser - it is recorded here because it has cost time twice.
"""

from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from parity.norm import read_lines

MARKER = "[PX]\t"

NORMAL, ADD, REPLACE = "NORMAL", "ADD", "REPLACE"

#: The pre-rename spelling, still present in every dump frozen before the promotion.
_BLEND_ALIASES = {"ADDITIVE": ADD, "ADD": ADD, "REPLACE": REPLACE, "NORMAL": NORMAL}


def blend_of(token: str) -> str:
    return _BLEND_ALIASES.get(token.strip().upper(), NORMAL)


def parse(path: Path) -> tuple[dict, dict]:
    """``(px, py) -> [fragment]`` in emission order, plus the depth-skipped ones."""
    written: dict[tuple[int, int], list[dict]] = defaultdict(list)
    skipped: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for line in read_lines(path):
        if not line.startswith(MARKER):
            continue
        parts = line.split("\t")
        stage = parts[1]
        key = (int(parts[2]), int(parts[3]))
        if stage == "WRITE":
            written[key].append({
                "afterShade": int(parts[14], 16), "afterTint": int(parts[12], 16),
                "blend": blend_of(parts[15]), "depth": float(parts[4]),
                "out": int(parts[16], 16), "raw": int(parts[10], 16),
                "shading": float(parts[13]), "tag": parts[5], "tint": int(parts[11], 16),
                "tx": int(parts[8]), "ty": int(parts[9]),
                "u": float(parts[6]), "v": float(parts[7]),
            })
        elif stage in ("SKIP-DEPTH", "SKIP-ALPHA"):
            skipped[key].append({"depth": float(parts[4]), "stage": stage, "tag": parts[5]})
    return dict(written), dict(skipped)


def ordered(path: Path) -> dict:
    """Every stage in emission order, which is what a three-dump join zips on.

    **A debug tag is not a unique key** - a bone face reaches a pixel once as the body's NORMAL
    fragment and again as the aura's ADD one - so position, not tag, is the join key. Reading it the
    other way cost two rebuilds.
    """
    out: dict[tuple[int, int], list[tuple]] = defaultdict(list)
    for line in read_lines(path):
        if not line.startswith(MARKER):
            continue
        parts = line.split("\t")
        stage = parts[1]
        key = (int(parts[2]), int(parts[3]))
        if stage == "WRITE":
            out[key].append((stage, parts[5], float(parts[4]), int(parts[14], 16),
                             blend_of(parts[15])))
        elif stage in ("SKIP-DEPTH", "SKIP-ALPHA"):
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
