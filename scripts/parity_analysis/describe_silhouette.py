#!/usr/bin/env python3
"""
describe_silhouette.py - quick diagnostic to print per-row/per-column opaque-pixel counts
of an entity's vanilla.png vs java.png. Use when the diff metrics are bad but you can't
view the images directly; the row/column profiles reveal whether the entity is flipped,
rotated, or just offset.

Usage:
    python scripts/parity_analysis/describe_silhouette.py <entity-dir>
e.g.:
    python scripts/parity_analysis/describe_silhouette.py cache/visual/entity-parity-vanilla/minecraft_cod
"""
import sys
from pathlib import Path
from PIL import Image


def row_col_profile(img: Image.Image, sample_rows: int = 20, sample_cols: int = 20) -> dict:
    w, h = img.size
    px = img.convert("RGBA").load()
    row_counts = [0] * h
    col_counts = [0] * w
    for y in range(h):
        for x in range(w):
            if px[x, y][3] > 0:
                row_counts[y] += 1
                col_counts[x] += 1
    # Subsample for printing
    rows_sampled = [(int(i * h / sample_rows), row_counts[int(i * h / sample_rows)])
                    for i in range(sample_rows)]
    cols_sampled = [(int(i * w / sample_cols), col_counts[int(i * w / sample_cols)])
                    for i in range(sample_cols)]
    # Bounding box
    nonzero_y = [y for y, c in enumerate(row_counts) if c > 0]
    nonzero_x = [x for x, c in enumerate(col_counts) if c > 0]
    if not nonzero_y:
        return {"w": w, "h": h, "bbox": None, "rows": rows_sampled, "cols": cols_sampled}
    return {
        "w": w,
        "h": h,
        "bbox": (min(nonzero_x), min(nonzero_y), max(nonzero_x), max(nonzero_y)),
        "rows": rows_sampled,
        "cols": cols_sampled,
    }


def main():
    entity_dir = Path(sys.argv[1])
    v_path = entity_dir / "vanilla.png"
    j_path = entity_dir / "java.png"
    if not v_path.exists() or not j_path.exists():
        print(f"missing PNGs in {entity_dir}")
        return 1
    v = Image.open(v_path)
    j = Image.open(j_path)
    vp = row_col_profile(v)
    jp = row_col_profile(j)

    print(f"== {entity_dir.name} ==")
    print(f"vanilla {vp['w']}x{vp['h']}  bbox={vp['bbox']}")
    print(f"java    {jp['w']}x{jp['h']}  bbox={jp['bbox']}")
    print()
    print(f"{'y':>4}  {'vanilla':>10}  {'java':>10}")
    for (vy, vc), (jy, jc) in zip(vp["rows"], jp["rows"]):
        bar_v = "█" * min(40, vc // 4)
        bar_j = "█" * min(40, jc // 4)
        print(f"{vy:>4}  v={vc:5d}{bar_v:<40s}  j={jc:5d}{bar_j}")
    print()
    print(f"{'x':>4}  {'vanilla':>10}  {'java':>10}")
    for (vx, vc), (jx, jc) in zip(vp["cols"], jp["cols"]):
        bar_v = "█" * min(40, vc // 4)
        bar_j = "█" * min(40, jc // 4)
        print(f"{vx:>4}  v={vc:5d}{bar_v:<40s}  j={jc:5d}{bar_j}")


if __name__ == "__main__":
    main()
