"""Predictor comparison over a harvested contest table.

**The operand is canonical JSON, not a pickle.** The original defaulted to a ``contests3.pkl`` while
its own producer wrote v4, so it could not run even with its inputs present. JSON also makes a
contest table diffable and normalizable like everything else the package touches.

This is how a research question was closed, not how a commit is gated: it prices what a rule would
buy against what java already does, and the honest answer it produced was that no constant rule
recovers the gap.
"""

from __future__ import annotations

from pathlib import Path

from parity.norm import MissingInput, read_json


def compare(contests: Path) -> dict:
    payload = read_json(contests)
    rows = payload.get("contests", payload if isinstance(payload, list) else [])
    if not rows:
        raise MissingInput(f"{contests} carries no contests")

    vanilla = [bool(row["vanilla_both"]) for row in rows]
    java = [bool(row["java_both"]) for row in rows]
    gaps = [float(row["gap_quanta"]) for row in rows]
    count = len(rows)

    pairs: dict[tuple[str, str], list[int]] = {}
    for index, row in enumerate(rows):
        pairs.setdefault((row["tag_a"], row["tag_b"]), []).append(index)

    return {
        "format": 1,
        "kind": "predictor-comparison",
        "pairs": _pairs(pairs, vanilla, java, gaps),
        "predictors": _predictors(vanilla, java, gaps, pairs),
        "residue_bands": _bands(vanilla, gaps),
        "totals": {"contests": count,
                   "agree": 100.0 * _mean(v == j for v, j in zip(vanilla, java)),
                   "java_both": 100.0 * _mean(java),
                   "vanilla_both": 100.0 * _mean(vanilla)},
    }


def _mean(values) -> float:
    materialised = list(values)
    return sum(1 for value in materialised if value) / len(materialised) if materialised else 0.0


def _pairs(pairs, vanilla, java, gaps) -> list[dict]:
    out = []
    for (tag_a, tag_b), indexes in sorted(pairs.items(), key=lambda item: -len(item[1])):
        ordered = sorted(gaps[i] for i in indexes)
        middle = ordered[len(ordered) // 2] if ordered else 0.0
        out.append({
            "agree": 100.0 * _mean(vanilla[i] == java[i] for i in indexes),
            "java_both": 100.0 * _mean(java[i] for i in indexes),
            "median_gap_quanta": middle,
            "px": len(indexes),
            "tag_a": tag_a, "tag_b": tag_b,
            "vanilla_both": 100.0 * _mean(vanilla[i] for i in indexes),
        })
    return out


def _predictors(vanilla, java, gaps, pairs) -> dict:
    best_accuracy, best_threshold = 0.0, 0.0
    for candidate in sorted({round(gap, 4) for gap in gaps}):
        accuracy = _mean((gap < candidate) == truth for gap, truth in zip(gaps, vanilla))
        if accuracy > best_accuracy:
            best_accuracy, best_threshold = accuracy, candidate
    # The per-pair oracle is the best constant answer for each pair, which no single rule can
    # deliver - it is the ceiling, quoted so a proposed rule can be priced against it.
    oracle = sum(max(sum(1 for i in indexes if vanilla[i]),
                     sum(1 for i in indexes if not vanilla[i]))
                 for indexes in pairs.values())
    return {
        "best_threshold": {"accuracy": 100.0 * best_accuracy, "threshold_quanta": best_threshold},
        "constant_always_both": 100.0 * _mean(vanilla),
        "constant_never_both": 100.0 * (1.0 - _mean(vanilla)),
        "java_as_landed": 100.0 * _mean(v == j for v, j in zip(vanilla, java)),
        "per_pair_oracle": 100.0 * oracle / len(vanilla) if vanilla else 0.0,
    }


def _bands(vanilla, gaps) -> list[dict]:
    out = []
    for low, high in ((0, 0.02), (0.02, 0.1), (0.1, 0.25), (0.25, 0.5), (0.5, 1.0), (1.0, 99)):
        picked = [truth for truth, gap in zip(vanilla, gaps) if low <= gap < high]
        if picked:
            out.append({"from": low, "n": len(picked), "to": high,
                        "vanilla_both": 100.0 * _mean(picked)})
    return out
