#!/usr/bin/env python3
"""
iterate_entity.py

Per-entity iteration helper. Runs `./gradlew :asset-renderer:entityParityVanilla
-PentityId=...` for one or more entities, then evaluates each against its target
tier. Exit code 0 if every requested entity meets its tier, 1 otherwise. Designed
to be the inner loop for the parity-fix workflow:

    1. make a code change
    2. python scripts/parity_analysis/iterate_entity.py minecraft:cod minecraft:salmon
    3. if green -> commit + advance; if red -> inspect diff_panel.png, hypothesize, repeat

Tier defaults come from the table inside the script. Override per-entity with
`--tier T1` or via the `--tier-overrides` JSON map.

Reads each entity's just-rendered PNG pair pixel-by-pixel and computes the same
metrics as analyze_diff_panels.py, so two scripts cannot drift out of sync about
the win conditions.
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

# Reuse the metrics + classifier from the panel analyser.
sys.path.insert(0, str(Path(__file__).parent))
from analyze_diff_panels import process_entity, EntityMetrics  # noqa: E402


# ----------------------------------------------------------------------------
# Tier thresholds (keep in sync with JAVA_PIPELINE_RESEARCH.md)
# ----------------------------------------------------------------------------

@dataclass
class Tier:
    name: str
    mean_abs_max: float
    iou_min: float
    cov_imb_max: float
    quadrant_abs_max: float          # |q_xx| < this for every quadrant

    def evaluate(self, m: EntityMetrics) -> tuple[bool, list[str]]:
        violations: list[str] = []
        if m.mean_abs_delta >= self.mean_abs_max:
            violations.append(f"mean_abs {m.mean_abs_delta:.2f} >= {self.mean_abs_max}")
        if m.silhouette_iou < self.iou_min:
            violations.append(f"iou {m.silhouette_iou:.4f} < {self.iou_min}")
        if m.coverage_imbalance >= self.cov_imb_max:
            violations.append(f"cov_imbalance {m.coverage_imbalance:.4f} >= {self.cov_imb_max}")
        for label, value in (("tl", m.q_tl_luma), ("tr", m.q_tr_luma), ("bl", m.q_bl_luma), ("br", m.q_br_luma)):
            if abs(value) >= self.quadrant_abs_max:
                violations.append(f"q_{label} |{value:+.2f}| >= {self.quadrant_abs_max}")
        return (len(violations) == 0, violations)


TIERS: dict[str, Tier] = {
    "T1": Tier("T1", mean_abs_max=0.001, iou_min=1.0,  cov_imb_max=1e-6, quadrant_abs_max=0.001),
    "T2": Tier("T2", mean_abs_max=10.0,  iou_min=0.98, cov_imb_max=0.02, quadrant_abs_max=4.0),
    "T3": Tier("T3", mean_abs_max=50.0,  iou_min=0.92, cov_imb_max=0.05, quadrant_abs_max=20.0),
}

DEFAULT_ENTITY_TIERS: dict[str, str] = {
    "minecraft:cod": "T2",
    "minecraft:salmon": "T2",
    "minecraft:tadpole": "T2",
    "minecraft:magma_cube": "T2",
    "minecraft:endermite": "T2",
    "minecraft:silverfish": "T2",
    "minecraft:zombie": "T2",
    "minecraft:cow_cold": "T2",
    "minecraft:cow_warm": "T2",
    "minecraft:chicken_cold": "T2",
    "minecraft:chicken_warm": "T2",
    # everything else defaults to T3 until promoted
}

OUT_ROOT = Path("cache/visual/entity-parity-vanilla")


# ----------------------------------------------------------------------------
# Gradle invoker
# ----------------------------------------------------------------------------

def run_gradle(entities: list[str], gradle_cmd: list[str], dry_run: bool) -> int:
    if not entities:
        return 0
    args = list(gradle_cmd) + [
        ":asset-renderer:entityParityVanilla",
        f"-PentityId={','.join(entities)}",
        "--console=plain",
    ]
    print("$ " + " ".join(shlex.quote(a) for a in args))
    if dry_run:
        return 0
    p = subprocess.run(args)
    return p.returncode


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("entities", nargs="+",
                    help='entity ids like minecraft:cod')
    ap.add_argument("--tier", help="override tier (T1/T2/T3) for ALL entities")
    ap.add_argument("--tier-overrides", help="JSON map {entity: tier} for per-entity override")
    ap.add_argument("--skip-render", action="store_true",
                    help="skip the gradle render step (just re-evaluate existing PNGs)")
    ap.add_argument("--dry-run", action="store_true",
                    help="print the gradle command without running")
    ap.add_argument("--gradle", default=None,
                    help='gradle launcher (default: ./gradlew on POSIX, ".\\gradlew.bat" on Windows)')
    args = ap.parse_args(argv)

    overrides: dict[str, str] = {}
    overrides.update(DEFAULT_ENTITY_TIERS)
    if args.tier_overrides:
        overrides.update(json.loads(args.tier_overrides))
    if args.tier:
        for e in args.entities:
            overrides[e] = args.tier

    # Pick gradle launcher
    if args.gradle is None:
        # Hardcode the POSIX one - on Windows the bash launcher works via Git Bash
        gradle = ["./gradlew"]
    else:
        gradle = shlex.split(args.gradle)

    # Render
    if not args.skip_render:
        rc = run_gradle(args.entities, gradle, args.dry_run)
        if rc != 0:
            print(f"[err] gradle exited {rc}", file=sys.stderr)
            return rc

    all_pass = True
    for ent in args.entities:
        tier_name = overrides.get(ent, "T3")
        tier = TIERS[tier_name]
        safe = ent.replace(":", "_")
        ent_dir = OUT_ROOT / safe
        if not ent_dir.is_dir():
            print(f"[fail] {ent:32s} {tier_name}  NO RENDER OUTPUT at {ent_dir}")
            all_pass = False
            continue
        m = process_entity(ent_dir)
        if m is None:
            print(f"[fail] {ent:32s} {tier_name}  vanilla.png or java.png missing")
            all_pass = False
            continue
        ok, vios = tier.evaluate(m)
        tag = "[pass]" if ok else "[fail]"
        print(f"{tag} {ent:32s} {tier_name}  mean={m.mean_abs_delta:7.2f}  iou={m.silhouette_iou:.4f}  "
              f"q=(TL{m.q_tl_luma:+5.2f} TR{m.q_tr_luma:+5.2f} BL{m.q_bl_luma:+5.2f} BR{m.q_br_luma:+5.2f})  "
              f"cluster={m.primary_cluster}")
        if not ok:
            for v in vios:
                print(f"         - {v}")
            all_pass = False

    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
