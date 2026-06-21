# scripts/parity_analysis

Developer tooling for the Java-pipeline parity work. Self-contained; reads
asset-renderer's local `cache/visual/entity-parity-vanilla/<entity>/*.png` output
and writes summaries that drive per-entity remediation decisions.

## Scripts

- `analyze_diff_panels.py` - walks every entity dir under
  `cache/visual/entity-parity-vanilla/`, re-derives the signed/abs/coverage metrics
  pixel-by-pixel (does NOT parse the panel image's footer text), and emits
  `cache/parity_analysis/diff_summary.json`,
  `cache/parity_analysis/diff_ranking.tsv`,
  `cache/parity_analysis/clusters.json`.

## Run

```bash
# from project root
python scripts/parity_analysis/analyze_diff_panels.py
# or with custom roots
python scripts/parity_analysis/analyze_diff_panels.py \
    --root cache/visual/entity-parity-vanilla \
    --out cache/parity_analysis
```

Output lands under `cache/parity_analysis/` (gitignored along with the rest of
`cache/`).

Requires Pillow:

```bash
python -m pip install Pillow
```

## Reading the output

`diff_ranking.tsv` columns:

| Column        | Meaning                                                                                                                                                                                                                                  |
|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `entity`      | folder name (e.g. `minecraft_cod`)                                                                                                                                                                                                       |
| `primary`     | classified failure cluster (see below)                                                                                                                                                                                                   |
| `secondary`   | comma-separated extra signals                                                                                                                                                                                                            |
| `mean_abs`    | mean per-pixel `                                                  \|dA\|+\|dR\|+\|dG\|+\|dB\|` (0-1020 scale)                                                                                                                            |
| `signed_luma` | mean signed luma delta; +ve = vanilla brighter than java                                                                                                                                                                                 |
| `iou`         | silhouette intersection-over-union (1.0 = perfect coverage match)                                                                                                                                                                        |
| `cov_imb`     | `                                                                                                             \|vanilla_px - java_px\| / max(...)` ; 0 = same coverage                                                                   |
| `q_tl..q_br`  | per-quadrant signed luma deltas                                                                                                                                                                                                          |
| `diag_split`  | `                                                                                                                                                                      \|((TL+BR) - (TR+BL))/2\|` ; high = iso-pose orientation mismatch |
| `lr` / `tb`   | left-right vs top-bottom signed-luma asymmetry                                                                                                                                                                                           |

## Cluster legend

| Cluster | Trigger                                   | Likely root cause                                                   |
|---|-------------------------------------------|---------------------------------------------------------------------|
| `silhouette_severe` | `iou < 0.50`                              | wrong geometry or wrong canvas, must fix before lighting matters    |
| `silhouette_partial` | `0.50 <= iou < 0.80`                      | partial geometry mismatch (missing layer? wrong pose?)              |
| `scale_or_pose_wrong` | `cov_imbalance > 0.30`                    | scale mismatch (`state.scale`, per-renderer override, family-fit)   |
| `iso_pose_diag_split` | `diag_split > 6`                          | classic iso-rotation mismatch - TL+BR vs TR+BL signed-luma disagree |
| `lighting_lr_axis` | `lr > 6`                                  | lights misaligned on horizontal axis (light0 X component or yaw)    |
| `lighting_tb_axis` | `tb > 6`                                  | lights misaligned vertically (pitch / floor)                        |
| `lighting_global_bias` | `\| mean_signed_luma\| > 8`               | uniform too-bright or too-dim (ambient floor wrong, etc.) |
| `high_delta_uncategorized` | `mean_abs > 40` and nothing above flagged | likely texture mismatch (variant default, biome tint)               |
| `matches_or_minor` | everything else                           | candidate for ACHIEVED_PARITY allowlist (after visual check)        |

Thresholds are intentionally loose so a single entity can have multiple signals;
the `secondary` column shows the rest after `primary` is locked in.
