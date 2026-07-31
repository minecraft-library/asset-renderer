"""Explain a region: the smallest set of java fragments to drop that reproduces vanilla.

The subject and the region are arguments. The original hardcoded a subject constant, which is why
it could only ever answer one question.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path

from parity import pixels
from parity.lab import frag as frag_mod

#: Below this per-channel difference a pixel is the sub-texel floor rather than a finding.
THRESHOLD = 8

TOLERANCE = 1


def explain(dump: Path, vanilla: Path, java: Path, region: tuple[int, int, int, int],
            threshold: int = THRESHOLD, tol: int = TOLERANCE) -> dict:
    numpy = pixels.numpy_module()
    van, jav = pixels.load_rgba(vanilla), pixels.load_rgba(java)
    written, _ = frag_mod.parse(dump)
    x0, y0, x1, y1 = region

    classes: Counter = Counter()
    dropped_tags: Counter = Counter()
    examples: dict[str, list] = {}
    for py in range(y0, min(y1 + 1, van.shape[0])):
        for px in range(x0, min(x1 + 1, van.shape[1])):
            if int(numpy.abs(van[py, px] - jav[py, px]).max()) < threshold:
                continue
            fragments = written.get((px, py), [])
            target = (int(jav[py, px][3]), int(van[py, px][0]),
                      int(van[py, px][1]), int(van[py, px][2]))
            found = frag_mod.subsets_reproducing(fragments, target, tol)
            if not found:
                name = "UNEXPLAINED"
            else:
                # The smallest DROP set is the largest keep set.
                keep = max(found, key=len)
                drop = tuple(i for i in range(len(fragments)) if i not in keep)
                if not drop:
                    name = f"tol-only(+-{tol})"
                else:
                    name = f"drop{len(drop)}" + _gap_suffix(fragments, drop, keep)
                    dropped_tags[tuple(fragments[i]["tag"] for i in drop)] += 1
            classes[name] += 1
            examples.setdefault(name, []).append([px, py])

    total = sum(classes.values())
    return {
        "classes": [{"class": name, "count": count, "examples": examples[name][:3],
                     "share": 100.0 * count / max(total, 1)}
                    for name, count in classes.most_common()],
        "dropped_tag_sets": [{"count": count, "tags": list(tags)}
                             for tags, count in dropped_tags.most_common(6)],
        "region": {"x0": x0, "x1": x1, "y0": y0, "y1": y1},
        "threshold": threshold,
        "tolerance": tol,
        "totals": {"differing": total},
    }


def _gap_suffix(fragments: list[dict], drop: tuple[int, ...], keep: tuple[int, ...]) -> str:
    """Whether the dropped fragment was an exact depth tie or lost by a measurable gap.

    A tie is decided by draw order and a gap is decided by the depth planes, so the two are
    different findings entirely.
    """
    if not drop:
        return ""
    first = fragments[drop[0]]["depth"]
    gaps = [abs(fragments[index]["depth"] - first) for index in keep]
    if not gaps:
        return ""
    smallest = min(gaps)
    return "-TIE" if smallest == 0.0 else f"-gap{smallest:.0e}"
