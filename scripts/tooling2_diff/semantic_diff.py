#!/usr/bin/env python3
"""Semantic differ for tooling2 value-parity holds (10-bridge SS7, recommendation (b)).

Compares a legacy tooling JSON against its v2 sibling at three levels:

  L1  payload key / row sets (missing-in-v2, extra-in-v2)
  L2  per-key values on the shared key set, object-member-order insensitive,
      floats compared exactly (any ULP delta is a finding, never noise)
  L3  payload key ORDER (first divergence)

With --axis-rows (entity_models payloads), L1 additionally compares per-family axis rows:
axis presence (<family>.axes.<axis>), the values domain (=<value>), and the option key set
(.options.<opt>) - the S6 variant/option row-set gate.

The two files carry different schemas by design (legacy flat fields vs v2 nodes); L2 deltas
are FINDINGS for the doc-11 SS12 tracker, not automatic failures. Exit code is 1 when L1
reports any miss (the per-session progress gauge), 0 otherwise.

Usage:
  python scripts/tooling2_diff/semantic_diff.py \
    --legacy cache/tooling2-diff/legacy/entity_models.json \
    --v2 src/main/resources/lib/minecraft/renderer/v2/entity_models.json \
    --levels L1,L2,L3 [--payload families] [--max-findings 50]
"""

import argparse
import json
import sys

ENVELOPE_KEYS = {"//", "format", "source_version", "version", "generated_by", "comment"}
MAX_DEFAULT = 50


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def payload_of(doc, override):
    """The payload member: the override when given, else the first dict-valued non-envelope member."""
    if override:
        if override not in doc:
            sys.exit(f"payload key '{override}' not in document")
        return override, doc[override]
    for key, value in doc.items():
        if key in ENVELOPE_KEYS:
            continue
        if isinstance(value, dict):
            return key, value
    sys.exit("no dict-valued payload member found (pass --payload)")


def canon(value):
    """Canonical form for order-insensitive comparison: dict members sorted, arrays kept ordered."""
    if isinstance(value, dict):
        return {key: canon(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [canon(entry) for entry in value]
    return value


def walk_diff(a, b, path, findings, limit):
    """Appends 'path: legacy=<a> v2=<b>' rows for every leaf difference, depth-first."""
    if len(findings) >= limit:
        return
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            if key not in a:
                findings.append(f"{path}.{key}: absent in legacy, v2={compact(b[key])}")
            elif key not in b:
                findings.append(f"{path}.{key}: legacy={compact(a[key])}, absent in v2")
            else:
                walk_diff(a[key], b[key], f"{path}.{key}", findings, limit)
            if len(findings) >= limit:
                return
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            findings.append(f"{path}: array length legacy={len(a)} v2={len(b)}")
            return
        for index, (left, right) in enumerate(zip(a, b)):
            walk_diff(left, right, f"{path}[{index}]", findings, limit)
            if len(findings) >= limit:
                return
    elif type(a) is not type(b) or a != b or repr(a) != repr(b):
        # repr() catches 1 vs 1.0 and float-formatting drift json equality would mask
        findings.append(f"{path}: legacy={compact(a)} v2={compact(b)}")


def compact(value):
    text = json.dumps(value, separators=(",", ":"))
    return text if len(text) <= 120 else text[:117] + "..."


def axis_rows(families):
    """Flattens a families payload into '<family>.axes.<axis>[...]' row keys (the S6 gate rows)."""
    rows = set()
    for family_id, family in families.items():
        if not isinstance(family, dict):
            continue
        for axis, node in family.get("axes", {}).items():
            base = f"{family_id}.axes.{axis}"
            rows.add(base)
            for value in node.get("values", []):
                rows.add(f"{base}={value}")
            for option in node.get("options", {}):
                rows.add(f"{base}.options.{option}")
    return rows


def report_axis_rows(legacy, v2, limit):
    missing = sorted(axis_rows(legacy) - axis_rows(v2))
    extra = sorted(axis_rows(v2) - axis_rows(legacy))
    print(f"\n== L1 axis rows: {len(missing)} missing in v2, {len(extra)} extra in v2 ==")
    for row in missing[:limit]:
        print(f"  MISSING  {row}")
    for row in extra[:limit]:
        print(f"  EXTRA    {row}")
    return len(missing)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--legacy", required=True)
    parser.add_argument("--v2", required=True)
    parser.add_argument("--levels", default="L1,L2,L3")
    parser.add_argument("--payload", default=None, help="payload member name (auto-detected when omitted)")
    parser.add_argument("--axis-rows", action="store_true", help="add the per-family axis row comparison to L1")
    parser.add_argument("--max-findings", type=int, default=MAX_DEFAULT)
    args = parser.parse_args()

    levels = {level.strip().upper() for level in args.levels.split(",")}
    legacy_doc = load(args.legacy)
    v2_doc = load(args.v2)
    legacy_key, legacy = payload_of(legacy_doc, args.payload)
    v2_key, v2 = payload_of(v2_doc, args.payload)
    print(f"payload: legacy '{legacy_key}' ({len(legacy)} keys) vs v2 '{v2_key}' ({len(v2)} keys)")

    l1_misses = 0
    if "L1" in levels:
        missing = sorted(set(legacy) - set(v2))
        extra = sorted(set(v2) - set(legacy))
        l1_misses = len(missing)
        print(f"\n== L1 key sets: {len(missing)} missing in v2, {len(extra)} extra in v2 ==")
        for key in missing[: args.max_findings]:
            print(f"  MISSING  {key}")
        for key in extra[: args.max_findings]:
            print(f"  EXTRA    {key}")
        if args.axis_rows:
            l1_misses += report_axis_rows(legacy, v2, args.max_findings)

    if "L2" in levels:
        shared = [key for key in legacy if key in v2]
        findings = []
        differing = 0
        for key in shared:
            before = len(findings)
            walk_diff(canon(legacy[key]), canon(v2[key]), key, findings, args.max_findings)
            if len(findings) > before:
                differing += 1
        print(f"\n== L2 shared-key values: {differing}/{len(shared)} keys differ "
              f"(showing {min(len(findings), args.max_findings)} findings) ==")
        for line in findings[: args.max_findings]:
            print(f"  {line}")

    if "L3" in levels:
        legacy_order = [key for key in legacy if key in v2]
        v2_order = [key for key in v2 if key in legacy]
        print("\n== L3 shared-key ordering ==")
        if legacy_order == v2_order:
            print("  shared-key order identical")
        else:
            for index, (a, b) in enumerate(zip(legacy_order, v2_order)):
                if a != b:
                    print(f"  first divergence at #{index}: legacy '{a}' vs v2 '{b}'")
                    break

    sys.exit(1 if l1_misses else 0)


if __name__ == "__main__":
    main()
