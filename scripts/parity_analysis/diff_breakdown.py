#!/usr/bin/env python3
"""Per-entity parity diff breakdown for notes/entity-parity.md.

Splits a rendered entity's mean-delta (compositeDiff-over-white, the same metric
TestEntityParityVanilla reports) into vanilla-only / java-only / both-color
contributions, with a big(>24/chan) vs sub-LSB split, so a coverage/centering
bug (breeze-class, fixable) can be told apart from a pure shading floor.

Usage: python3 scripts/parity_analysis/diff_breakdown.py minecraft_drowned [minecraft_goat ...]
Reads cache/visual/entity-parity-vanilla/<id>/{java,vanilla}.png (written by entityParityVanilla).
"""
import sys
import numpy as np
from PIL import Image


def over_white(c, a):
    return (c * a + 255 * (255 - a) + 127) // 255


def analyze(eid):
    d = f"cache/visual/entity-parity-vanilla/{eid}"
    try:
        j = np.asarray(Image.open(f"{d}/java.png").convert("RGBA")).astype(int)
        v = np.asarray(Image.open(f"{d}/vanilla.png").convert("RGBA")).astype(int)
    except Exception as e:
        print(f"{eid}: ERR {e}")
        return
    h = min(j.shape[0], v.shape[0])
    w = min(j.shape[1], v.shape[1])
    j, v = j[:h, :w], v[:h, :w]
    tot = h * w
    diff = sum(abs(over_white(v[:, :, c], v[:, :, 3]) - over_white(j[:, :, c], j[:, :, 3])) for c in range(3))
    va, ja = v[:, :, 3] > 0, j[:, :, 3] > 0
    both, vonly, jonly = va & ja, va & ~ja, ja & ~va
    s = int(diff.sum())
    if s == 0:
        print(f"{eid}: perfect")
        return
    big = (diff[both] > 24)
    print(
        f"{eid:24s} mean={s/tot:.3f} | vanilla-only {int(vonly.sum()):4d}px {100*diff[vonly].sum()/s:4.1f}% "
        f"| java-only {int(jonly.sum()):4d}px {100*diff[jonly].sum()/s:4.1f}% "
        f"| both-color {100*diff[both].sum()/s:4.1f}% (big>24: {int(big.sum())}px {100*diff[both][big].sum()/s:.0f}%)"
    )


if __name__ == "__main__":
    for e in sys.argv[1:]:
        analyze(e)
