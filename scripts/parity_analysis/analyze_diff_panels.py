#!/usr/bin/env python3
"""
analyze_diff_panels.py

Crunches every entity's diff_panel.png produced by TestEntityParityVanilla and emits
a JSON summary + a human-readable ranked TSV that classifies each entity by failure
signature.

We deliberately do not parse the in-image footer text. Instead we re-derive the same
metrics directly from the cell pixels (signed luma per quadrant, |delta|, coverage
buckets, hue-direction tally on the per-channel cell) so the analysis stays self-
contained even if the panel layout changes.

Cell layout in TestEntityParityVanilla.panelDiff (cols x rows = 3 x 2):
  [0,0] vanilla   [1,0] java        [2,0] |delta| x4
  [0,1] luma+/-   [1,1] RGB+/-      [2,1] coverage (M=vanilla, C=java)

Layout constants come from panelDiff(): gap=8, labelH=18, statsH=96. The cell width
and height are max(vanilla.w, java.w) and max(vanilla.h, java.h) respectively. We
read those by inspecting the vanilla and java cells directly (cell 0 is the vanilla
input, sized vanilla.w x vanilla.h; the panel pads them up to a common cellW x cellH).

Usage:
    python analyze_diff_panels.py [--root cache/visual/entity-parity-vanilla]
                                  [--out scripts/parity_analysis/out]

Writes:
    out/diff_summary.json        - per-entity metrics + classification
    out/diff_ranking.tsv         - sorted by severity (mean |delta| desc)
    out/clusters.json            - grouped by primary failure signature
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional

from PIL import Image


# ----------------------------------------------------------------------------
# Panel layout (mirrors TestEntityParityVanilla.panelDiff)
# ----------------------------------------------------------------------------

GAP = 8
LABEL_H = 18
STATS_H = 96

# Cell positions: column c (0..2), row r (0..1)
def cell_box(cell_w: int, cell_h: int, c: int, r: int) -> tuple[int, int, int, int]:
    x = GAP + c * (cell_w + GAP)
    y = GAP + r * (cell_h + LABEL_H + GAP) + LABEL_H
    return (x, y, x + cell_w, y + cell_h)


# ----------------------------------------------------------------------------
# Heuristics for classification
# ----------------------------------------------------------------------------

@dataclass
class EntityMetrics:
    entity: str
    canvas_w: int
    canvas_h: int
    mean_abs_delta: float           # sum of |dA|+|dR|+|dG|+|dB| / total pixels (0..1020)
    mean_signed_luma: float         # +ve = vanilla brighter than java
    vanilla_px: int
    java_px: int
    both_px: int
    vanilla_only_px: int
    java_only_px: int
    coverage_imbalance: float       # |vanilla_px - java_px| / max(vanilla_px, java_px) ; 0 = same
    silhouette_iou: float           # both_px / (both_px + vanilla_only + java_only)
    q_tl_luma: float
    q_tr_luma: float
    q_bl_luma: float
    q_br_luma: float
    q_tl_abs: float
    q_tr_abs: float
    q_bl_abs: float
    q_br_abs: float
    luma_quadrant_max_abs: float    # max |q| across 4
    luma_lr_asymmetry: float        # |((TL+BL) - (TR+BR))/2|  - lighting bias left vs right
    luma_tb_asymmetry: float        # |((TL+TR) - (BL+BR))/2|  - lighting bias top vs bottom
    luma_diag_split: float          # |((TL+BR) - (TR+BL))/2|  - signs an iso-pose mismatch
    primary_cluster: str
    secondary_clusters: list[str]


def classify(m: EntityMetrics) -> tuple[str, list[str]]:
    """
    Pick the primary failure signature for an entity. Order of precedence is from
    most-blocking (geometry/coverage) to least (lighting tone).
    """
    secondary: list[str] = []
    primary = "matches_or_minor"

    # 1. Coverage failures dominate everything else
    if m.silhouette_iou < 0.50:
        primary = "silhouette_severe"
    elif m.silhouette_iou < 0.80:
        primary = "silhouette_partial"

    # 2. Strong coverage imbalance (size or scale wrong)
    if m.coverage_imbalance > 0.30:
        if primary == "matches_or_minor":
            primary = "scale_or_pose_wrong"
        else:
            secondary.append("scale_or_pose_wrong")
    elif m.coverage_imbalance > 0.10:
        secondary.append("size_drift")

    # 3. Lighting signature: diag-split is the classic iso-pose mismatch (TL+BR vs TR+BL)
    if m.luma_diag_split > 6.0:
        if primary == "matches_or_minor":
            primary = "iso_pose_diag_split"
        else:
            secondary.append("iso_pose_diag_split")

    if m.luma_lr_asymmetry > 6.0 and "iso_pose_diag_split" not in (primary, *secondary):
        if primary == "matches_or_minor":
            primary = "lighting_lr_axis"
        else:
            secondary.append("lighting_lr_axis")

    if m.luma_tb_asymmetry > 6.0 and "lighting_lr_axis" not in (primary, *secondary):
        if primary == "matches_or_minor":
            primary = "lighting_tb_axis"
        else:
            secondary.append("lighting_tb_axis")

    # 4. Uniform brightness bias (everything brighter/dimmer)
    if abs(m.mean_signed_luma) > 8.0 and primary == "matches_or_minor":
        primary = "lighting_global_bias"

    # 5. Generic high delta with nothing else flagged
    if m.mean_abs_delta > 40.0 and primary == "matches_or_minor":
        primary = "high_delta_uncategorized"

    return primary, secondary


# ----------------------------------------------------------------------------
# Pixel walkers
# ----------------------------------------------------------------------------

def analyze_pair(vanilla: Image.Image, java: Image.Image) -> dict:
    """
    Re-derive the metrics directly from cell pixels rather than parsing the panel
    footer text. Mirrors aggregateDiffStats in TestEntityParityVanilla.
    """
    w = min(vanilla.size[0], java.size[0])
    h = min(vanilla.size[1], java.size[1])
    v_data = vanilla.convert("RGBA").load()
    j_data = java.convert("RGBA").load()

    abs_sum = 0
    luma_sum = 0.0
    luma_n = 0
    v_any = j_any = both = v_only = j_only = 0

    # silhouette bbox for quadrant centering
    min_x, max_x = w, -1
    min_y, max_y = h, -1
    for y in range(h):
        for x in range(w):
            va = v_data[x, y][3]
            ja = j_data[x, y][3]
            if va > 0 or ja > 0:
                if x < min_x: min_x = x
                if x > max_x: max_x = x
                if y < min_y: min_y = y
                if y > max_y: max_y = y

    cx = (min_x + max_x) // 2 if min_x < max_x else w // 2
    cy = (min_y + max_y) // 2 if min_y < max_y else h // 2

    q_sum = [0.0] * 4
    q_abs = [0.0] * 4
    q_n = [0] * 4

    for y in range(h):
        for x in range(w):
            vr, vg, vb, va = v_data[x, y]
            jr, jg, jb, ja = j_data[x, y]
            da = abs(va - ja)
            dr = abs(vr - jr)
            dg = abs(vg - jg)
            db = abs(vb - jb)
            abs_sum += da + dr + dg + db
            if va > 0: v_any += 1
            if ja > 0: j_any += 1
            if va > 0 and ja > 0:
                both += 1
                v_l = 0.299 * vr + 0.587 * vg + 0.114 * vb
                j_l = 0.299 * jr + 0.587 * jg + 0.114 * jb
                ld = v_l - j_l
                luma_sum += ld
                luma_n += 1
                q_idx = (0 if y < cy else 2) + (0 if x < cx else 1)
                q_sum[q_idx] += ld
                q_abs[q_idx] += da + dr + dg + db
                q_n[q_idx] += 1
            elif va > 0:
                v_only += 1
            elif ja > 0:
                j_only += 1

    total = w * h
    mean_abs = abs_sum / total if total else 0.0
    mean_luma = luma_sum / luma_n if luma_n else 0.0
    q = [q_sum[i] / q_n[i] if q_n[i] else 0.0 for i in range(4)]
    qa = [q_abs[i] / q_n[i] if q_n[i] else 0.0 for i in range(4)]

    cov_total = max(v_any, j_any)
    cov_imbalance = abs(v_any - j_any) / cov_total if cov_total else 0.0
    iou_denom = both + v_only + j_only
    iou = both / iou_denom if iou_denom else 0.0

    return {
        "canvas_w": w,
        "canvas_h": h,
        "mean_abs_delta": mean_abs,
        "mean_signed_luma": mean_luma,
        "vanilla_px": v_any,
        "java_px": j_any,
        "both_px": both,
        "vanilla_only_px": v_only,
        "java_only_px": j_only,
        "coverage_imbalance": cov_imbalance,
        "silhouette_iou": iou,
        "q_tl_luma": q[0],
        "q_tr_luma": q[1],
        "q_bl_luma": q[2],
        "q_br_luma": q[3],
        "q_tl_abs": qa[0],
        "q_tr_abs": qa[1],
        "q_bl_abs": qa[2],
        "q_br_abs": qa[3],
        "luma_quadrant_max_abs": max(abs(v) for v in q),
        "luma_lr_asymmetry": abs((q[0] + q[2]) - (q[1] + q[3])) / 2.0,
        "luma_tb_asymmetry": abs((q[0] + q[1]) - (q[2] + q[3])) / 2.0,
        "luma_diag_split": abs((q[0] + q[3]) - (q[1] + q[2])) / 2.0,
    }


def process_entity(entity_dir: Path) -> Optional[EntityMetrics]:
    vp = entity_dir / "vanilla.png"
    jp = entity_dir / "java.png"
    if not vp.exists() or not jp.exists():
        return None
    vanilla = Image.open(vp)
    java = Image.open(jp)
    # Pad the smaller to the union canvas size (mirrors padToCanvas in the test).
    cw = max(vanilla.size[0], java.size[0])
    ch = max(vanilla.size[1], java.size[1])
    vanilla_padded = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    java_padded = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    vanilla_padded.paste(vanilla, ((cw - vanilla.size[0]) // 2, (ch - vanilla.size[1]) // 2))
    java_padded.paste(java, ((cw - java.size[0]) // 2, (ch - java.size[1]) // 2))

    metrics_dict = analyze_pair(vanilla_padded, java_padded)
    m = EntityMetrics(
        entity=entity_dir.name,
        primary_cluster="",
        secondary_clusters=[],
        **metrics_dict,
    )
    m.primary_cluster, m.secondary_clusters = classify(m)
    return m


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="cache/visual/entity-parity-vanilla",
                    help="Folder containing one subdir per entity")
    ap.add_argument("--out", default="scripts/parity_analysis/out",
                    help="Output folder for JSON + TSV")
    args = ap.parse_args(argv)

    root = Path(args.root)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    if not root.exists():
        print(f"[err] root dir missing: {root}", file=sys.stderr)
        return 2

    rows: list[EntityMetrics] = []
    dirs = sorted([p for p in root.iterdir() if p.is_dir()])
    print(f"Scanning {len(dirs)} entity dirs under {root}")
    for d in dirs:
        m = process_entity(d)
        if m is None:
            continue
        rows.append(m)
        print(f"  {d.name:40s} mean={m.mean_abs_delta:7.2f}  iou={m.silhouette_iou:.3f}"
              f"  diag_split={m.luma_diag_split:6.2f}  cluster={m.primary_cluster}")

    rows.sort(key=lambda r: r.mean_abs_delta, reverse=True)

    # JSON dump
    (out / "diff_summary.json").write_text(
        json.dumps([asdict(r) for r in rows], indent=2),
        encoding="utf-8",
    )

    # TSV ranking
    header = ("entity\tprimary\tsecondary\tmean_abs\tsigned_luma\tiou\tcov_imb"
              "\tq_tl\tq_tr\tq_bl\tq_br\tdiag_split\tlr\ttb"
              "\tw\th\tboth_px\tv_only\tj_only\n")
    with (out / "diff_ranking.tsv").open("w", encoding="utf-8") as f:
        f.write(header)
        for r in rows:
            f.write(
                f"{r.entity}\t{r.primary_cluster}\t{','.join(r.secondary_clusters)}\t"
                f"{r.mean_abs_delta:.3f}\t{r.mean_signed_luma:+.3f}\t{r.silhouette_iou:.4f}\t{r.coverage_imbalance:.4f}\t"
                f"{r.q_tl_luma:+.2f}\t{r.q_tr_luma:+.2f}\t{r.q_bl_luma:+.2f}\t{r.q_br_luma:+.2f}\t"
                f"{r.luma_diag_split:.2f}\t{r.luma_lr_asymmetry:.2f}\t{r.luma_tb_asymmetry:.2f}\t"
                f"{r.canvas_w}\t{r.canvas_h}\t{r.both_px}\t{r.vanilla_only_px}\t{r.java_only_px}\n"
            )

    # Cluster grouping
    clusters: dict[str, list[str]] = {}
    for r in rows:
        clusters.setdefault(r.primary_cluster, []).append(r.entity)
    cluster_summary = {
        cluster: {
            "count": len(entities),
            "entities": entities,
        }
        for cluster, entities in sorted(clusters.items(), key=lambda kv: -len(kv[1]))
    }
    (out / "clusters.json").write_text(json.dumps(cluster_summary, indent=2), encoding="utf-8")

    print()
    print("Cluster summary:")
    for k, v in cluster_summary.items():
        print(f"  {k:30s} {v['count']:3d} entities")
    print(f"\nWrote {out / 'diff_summary.json'}")
    print(f"Wrote {out / 'diff_ranking.tsv'}")
    print(f"Wrote {out / 'clusters.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
