"""The full composite chain at a named pixel - the finest-grained thing the lab can answer.

Subject and coordinates are arguments; the original carried both as module constants.
"""

from __future__ import annotations

from pathlib import Path

from parity import pixels
from parity.lab import frag as frag_mod


def inspect(dump: Path, vanilla: Path, java: Path,
            coordinates: list[tuple[int, int]]) -> list[dict]:
    van, jav = pixels.load_rgba(vanilla), pixels.load_rgba(java)
    written, skipped = frag_mod.parse(dump)

    out = []
    for px, py in coordinates:
        vr, vg, vb, va = (int(value) for value in van[py, px])
        jr, jg, jb, ja = (int(value) for value in jav[py, px])
        chain = []
        dst = (0, 0, 0, 0)
        for index, fragment in enumerate(written.get((px, py), [])):
            dst = frag_mod.composite(fragment["afterShade"], fragment["blend"], dst)
            chain.append({
                "afterShade": f"0x{fragment['afterShade']:08X}",
                "blend": fragment["blend"],
                "depth": fragment["depth"],
                "index": index,
                "raw": f"0x{fragment['raw']:08X}",
                "running": f"0x{dst[0]:02X}{dst[1]:02X}{dst[2]:02X}{dst[3]:02X}",
                "shading": fragment["shading"],
                "tag": fragment["tag"],
                "texel": [fragment["tx"], fragment["ty"]],
                "tint": f"0x{fragment['tint']:08X}",
                "uv": [fragment["u"], fragment["v"]],
            })
        out.append({
            "delta": [vr - jr, vg - jg, vb - jb, va - ja],
            "fragments": chain,
            "java": f"0x{ja:02X}{jr:02X}{jg:02X}{jb:02X}",
            "pixel": [px, py],
            "skipped": skipped.get((px, py), []),
            "vanilla": f"0x{va:02X}{vr:02X}{vg:02X}{vb:02X}",
        })
    return out
