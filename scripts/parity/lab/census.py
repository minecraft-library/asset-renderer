"""The three-dump join, the subset search and the coplanar contest harvest.

**The method is the valuable part.** A fragment java rejected never logs its texel, so the same row
is rendered three times and the runs are zipped position by position:

===================  ==========================  ==================================
run                  invocation                  what it supplies
===================  ==========================  ==================================
all-pass             ``-Dasset.depth.range=1e12``  every fragment's texel, nothing occluded
raw                  ``-Dasset.depth.range=0``     every fragment's true unrounded depth
landed               unset (the shipped 1000)      java's own verdict
===================  ==========================  ==================================

Vanilla's verdict is then recovered by finding which subset of that inventory reproduces
``vanilla.png`` to within +-1 per channel, the NVIDIA shader-float floor.

Two warnings travel with the method and both cost real time:

- **A debug tag is NOT a unique fragment key.** A bone face reaches a pixel once as the body's
  ``NORMAL`` fragment and again as the aura's ``ADD`` one. The three runs zip on emission
  *position*; reading them by tag cost two rebuilds.
- **A ``SKIP-FILL`` fragment is invisible to all three runs**, so a pixel it decides cannot be
  explained by this method at all. Those land in ``UNEXPLAINED`` rather than being forced.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path

from parity import pixels
from parity.lab import frag as frag_mod
from parity.norm import MissingInput

#: The measured camera-space depth quantum on vanilla's grid at range 1000.
QUANTUM = 6.0e-7

#: Two fragments closer than this in raw depth are treated as coplanar for the contest harvest.
COPLANAR = 2e-6


def join(allpass: Path, raw: Path, landed: Path) -> tuple[dict, int]:
    """Zip the three dumps position by position, dropping any pixel whose runs disagree in shape."""
    one, two, three = (frag_mod.ordered(path) for path in (allpass, raw, landed))
    aligned, misaligned = {}, 0
    for key, entries in one.items():
        other, third = two.get(key), three.get(key)
        if not other or not third or len(entries) != len(other) or len(entries) != len(third):
            misaligned += 1
            continue
        if any(a[1] != b[1] for a, b in zip(entries, other)) or \
           any(a[1] != c[1] for a, c in zip(entries, third)):
            misaligned += 1
            continue
        inventory, meta = [], []
        for index, entry in enumerate(entries):
            if entry[0] != "WRITE":
                continue
            inventory.append({"afterShade": entry[3], "blend": entry[4], "tag": entry[1]})
            meta.append({"drew": third[index][0] == "WRITE", "raw_depth": other[index][2]})
        if inventory:
            aligned[key] = (inventory, meta)
    return aligned, misaligned


def census(allpass: Path, raw: Path, landed: Path, vanilla: Path, java: Path) -> dict:
    """Classify every pixel and harvest the coplanar additive contests."""
    numpy = pixels.numpy_module()
    van = pixels.load_rgba(vanilla)
    jav = pixels.load_rgba(java)
    aligned, misaligned = join(allpass, raw, landed)
    if not aligned:
        raise MissingInput("the three dumps share no aligned pixel; check they are the same subject")

    classes: Counter = Counter()
    contests = []
    for (px, py), (inventory, meta) in sorted(aligned.items()):
        if py >= van.shape[0] or px >= van.shape[1]:
            continue
        if len(inventory) > frag_mod.SUBSET_LIMIT:
            classes["TOO_MANY_FRAGMENTS"] += 1
            continue
        target = (int(jav[py, px][3]), int(van[py, px][0]), int(van[py, px][1]), int(van[py, px][2]))
        found = frag_mod.subsets_reproducing(inventory, target)
        if not found:
            classes["UNEXPLAINED"] += 1
            continue
        classes["explained"] += 1
        # The largest reproducing subset is the one vanilla most plausibly drew.
        hit = max(found, key=len)
        contests.extend(_contests(px, py, inventory, meta, hit))

    return {
        "artifact": "probe.pixel",
        "classes": dict(classes.most_common()),
        "contests": contests,
        "format": 1,
        "kind": "contest-table",
        "misaligned_px": misaligned,
        "totals": {"aligned_px": len(aligned), "contests": len(contests)},
    }


def _contests(px: int, py: int, inventory: list[dict], meta: list[dict],
              hit: tuple[int, ...]) -> list[dict]:
    """Every pair of near-coplanar ADD fragments from different bones, with both verdicts."""
    additive = [index for index, entry in enumerate(inventory) if entry["blend"] == frag_mod.ADD]
    out = []
    for position, first in enumerate(additive):
        for second in additive[position + 1:]:
            if inventory[first]["tag"] == inventory[second]["tag"]:
                continue
            gap = meta[second]["raw_depth"] - meta[first]["raw_depth"]
            if abs(gap) > COPLANAR:
                continue
            tags = tuple(sorted((inventory[first]["tag"], inventory[second]["tag"])))
            out.append({
                "gap_quanta": abs(gap) / QUANTUM,
                "java_both": meta[first]["drew"] and meta[second]["drew"],
                "px": px, "py": py,
                "tag_a": tags[0], "tag_b": tags[1],
                "vanilla_both": (first in hit) and (second in hit),
            })
    return out
