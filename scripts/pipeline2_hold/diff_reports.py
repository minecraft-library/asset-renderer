#!/usr/bin/env python3
"""Diff two parity-sweep output directories for the tooling2 bridge render hold (S13).

The four parity sweeps (entityParityVanilla / blockParityVanilla / itemParityVanilla /
glintParityVanilla) each write one sub-directory per subject holding ``java.png`` (the
asset-renderer output), ``vanilla.png`` (the harness ground truth, identical across runs) and a
top-level ``parity-report.tsv`` ranked by mean ARGB delta.

The bridge hold renders each sweep twice - unflagged (baseline, reads the checked-in legacy
resources) and flagged (``-Dasset.tooling2.bridge=true``, reads bridge output materialized from the
v2 resources). Because the two inputs are canonical-SHA equal for the fully-derivable files, the
asset-renderer output must be byte-identical per subject; any ``java.png`` that changed is a
determinism finding (flag plumbing, load-order) to LOOK at, NOT a render regression. The entity
sweep is the exception: entity_models is v2-superior by design (recorded divergences), so some
entity subjects legitimately move or drop out - those are characterised, not failed.

Usage:
    python scripts/tooling2_parity_hold/diff_reports.py <baseline-dir> <flagged-dir>

Exit code is 0 when every shared subject's java.png is byte-identical, 1 otherwise (movers, drops,
or adds) so the hold can gate on it; the report is printed either way.
"""
import csv
import hashlib
import sys
from pathlib import Path


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _java_artifact(subject_dir: Path) -> Path:
    """The subject's asset-renderer output: java.png (static sweeps) or java.gif (animated glint)."""
    for name in ("java.png", "java.gif"):
        candidate = subject_dir / name
        if candidate.exists():
            return candidate
    return None


def _index(root: Path) -> dict:
    """Map subject dir name -> sha256 of its java render artifact (java.png or java.gif)."""
    out = {}
    for subject_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        artifact = _java_artifact(subject_dir)
        if artifact is not None:
            out[subject_dir.name] = _sha(artifact)
    return out


def _mean_deltas(root: Path) -> dict:
    """Map subject id -> mean_argb_delta from the sweep's parity-report.tsv (empty when absent)."""
    tsv = root / "parity-report.tsv"
    if not tsv.exists():
        return {}
    out = {}
    with tsv.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        id_col = reader.fieldnames[0] if reader.fieldnames else None
        for row in reader:
            key = row.get(id_col)
            value = row.get("mean_argb_delta")
            if key is not None and value is not None:
                out[key] = value
    return out


def _subject_to_report_id(subject: str) -> str:
    """The per-subject dir name is the report id with ':' / '/' flattened to '_'; keep both forms."""
    return subject


def main(argv: list) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    baseline, flagged = Path(argv[1]), Path(argv[2])
    for root in (baseline, flagged):
        if not root.is_dir():
            print(f"ERROR: not a directory: {root}")
            return 2

    base_idx, flag_idx = _index(baseline), _index(flagged)
    base_ids, flag_ids = set(base_idx), set(flag_idx)
    shared = base_ids & flag_ids

    movers = sorted(s for s in shared if base_idx[s] != flag_idx[s])
    dropped = sorted(base_ids - flag_ids)   # rendered unflagged, absent flagged
    added = sorted(flag_ids - base_ids)     # rendered flagged, absent unflagged
    identical = len(shared) - len(movers)

    base_delta, flag_delta = _mean_deltas(baseline), _mean_deltas(flagged)

    print(f"baseline : {baseline}  ({len(base_ids)} subjects)")
    print(f"flagged  : {flagged}  ({len(flag_ids)} subjects)")
    print(f"shared   : {len(shared)}  |  java.png identical: {identical}  |  movers: {len(movers)}")
    print(f"dropped (baseline-only): {len(dropped)}  |  added (flagged-only): {len(added)}")

    if movers:
        print("\nMOVERS (java.png changed flagged vs unflagged - LOOK before diagnosing):")
        for s in movers:
            bd, fd = base_delta.get(s, "?"), flag_delta.get(s, "?")
            print(f"    {s:<44} mean_argb_delta base={bd} flagged={fd}")
    if dropped:
        print("\nDROPPED under the flag (subject rendered unflagged but not flagged):")
        for s in dropped:
            print(f"    {s}")
    if added:
        print("\nADDED under the flag (subject rendered flagged but not unflagged):")
        for s in added:
            print(f"    {s}")

    clean = not movers and not dropped and not added
    print("\nRESULT:", "CLEAN - zero per-subject delta" if clean
          else "DELTAS PRESENT - characterise each above (v2-superior/expected vs determinism bug)")
    return 0 if clean else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
