"""JSON to Markdown, for every stored kind and for the diff.

Three rules keep it presentable. The header table is always the same columns whatever the sweep, the
mover table is capped so a 400-row diff does not become the report, and there is **no prose**: a
generated sentence reads as a judgement, and the gate does not judge.

The Markdown is a **view**. The JSON is the authority (U3), it is regenerable from it, and nothing
reads the Markdown back.
"""

from __future__ import annotations

from typing import Any

from parity.norm import fixed

#: The full list is in the JSON, which is the authority anyway.
MOVER_CAP = 40


def render(payload: dict, kind: str | None = None) -> str:
    resolved = kind or payload.get("kind") or _infer(payload)
    if resolved == "diff-report":
        return render_diff(payload)
    if resolved == "sweep-table":
        return _render_sweep(payload)
    if resolved == "manifest":
        return _render_manifest(payload)
    if resolved == "index":
        return _render_index(payload)
    return _render_generic(payload)


def _infer(payload: dict) -> str:
    return "diff-report" if "artifacts" in payload and "generated_at" in payload else "generic"


# --- the diff report -----------------------------------------------------------------------------

def render_diff(payload: dict) -> str:
    blocks = []
    for entry in payload.get("artifacts", []):
        blocks.append(_render_one_diff(entry))
    if not blocks:
        blocks.append("_no artifacts compared_")
    return "\n\n".join(blocks)


def _render_one_diff(entry: dict) -> str:
    left, right = entry.get("left", {}), entry.get("right", {})
    totals = entry.get("totals", {})
    lines = [f"# {entry.get('artifact', '?')} - "
             f"{right.get('store', 'current')} vs {left.get('store', 'base')}", ""]

    lines.append("| | rows | sum |")
    lines.append("|---|---:|---:|")
    for side in (left, right):
        total = side.get("sum")
        lines.append(f"| {side.get('store', '?')} | {side.get('rows', 0)} | "
                     f"{fixed(total) if isinstance(total, (int, float)) else '-'} |")
    lines.append("")
    lines.append(f"**moved {totals.get('moved', 0)} "
                 f"({totals.get('expected', 0)} expected, {totals.get('unexpected', 0)} unexpected) "
                 f"- added {totals.get('added', 0)} - dropped {totals.get('dropped', 0)}**")

    movers = entry.get("movers", [])
    if movers:
        lines += ["", "## Movers", "", "| key | class | changed | expected |", "|---|---|---|---|"]
        for mover in movers[:MOVER_CAP]:
            changed = "; ".join(
                f"{name} {_cell(pair[0])} -> {_cell(pair[1])}"
                for name, pair in sorted(mover.get("fields", {}).items())
            )
            lines.append(f"| {mover.get('key', '')} | {mover.get('class', '')} | {changed} | "
                         f"{'yes' if mover.get('expected') else 'no'} |")
        if len(movers) > MOVER_CAP:
            lines.append(f"| ... and {len(movers) - MOVER_CAP} more, see `_run/compare.json` | | | |")

    for name in ("added", "dropped"):
        rows = entry.get(name, [])
        if rows:
            shown = ", ".join(f"`{row}`" for row in rows[:MOVER_CAP])
            more = f" ... and {len(rows) - MOVER_CAP} more" if len(rows) > MOVER_CAP else ""
            lines += ["", f"**{name} ({len(rows)})**: {shown}{more}"]
    return "\n".join(lines)


def _cell(value: Any) -> str:
    return "-" if value is None else str(value)


# --- stored kinds --------------------------------------------------------------------------------

def _render_sweep(payload: dict) -> str:
    rows = payload.get("rows", [])
    lines = [f"# {payload.get('artifact', 'sweep')}", "", f"{len(rows)} rows.", ""]
    if rows:
        columns = [name for name in sorted(rows[0]) if name != "subject"]
        lines.append("| subject | " + " | ".join(columns) + " |")
        lines.append("|---" * (len(columns) + 1) + "|")
        for row in rows[:MOVER_CAP]:
            lines.append(f"| {row.get('subject', '')} | "
                         + " | ".join(_cell(row.get(name)) for name in columns) + " |")
        if len(rows) > MOVER_CAP:
            lines.append(f"| ... and {len(rows) - MOVER_CAP} more | " + " | " * len(columns))
    return "\n".join(lines)


def _render_manifest(payload: dict) -> str:
    files = payload.get("files", [])
    root = payload.get("provenance", {}).get("root", "")
    lines = [f"# {payload.get('artifact', 'manifest')}", "",
             f"{len(files)} files under `{root}`.", ""]
    lines += ["| path | sha256 |", "|---|---|"]
    for entry in files[:MOVER_CAP]:
        lines.append(f"| `{entry.get('path', '')}` | `{entry.get('sha256', '')[:16]}...` |")
    if len(files) > MOVER_CAP:
        lines.append(f"| ... and {len(files) - MOVER_CAP} more | |")
    return "\n".join(lines)


def _render_index(payload: dict) -> str:
    artifacts = payload.get("artifacts", {})
    lines = ["# Parity store index", "", f"{len(artifacts)} artifacts baselined.", "",
             "| artifact | kind | entries | file |", "|---|---|---:|---|"]
    for name in sorted(artifacts):
        entry = artifacts[name]
        lines.append(f"| `{name}` | {entry.get('kind', '')} | {entry.get('entries', '')} | "
                     f"`{entry.get('file', '')}` |")
    return "\n".join(lines)


def _render_generic(payload: dict) -> str:
    lines = [f"# {payload.get('artifact', 'artifact')}", ""]
    for name in sorted(payload):
        if name in ("//", "provenance"):
            continue
        value = payload[name]
        if isinstance(value, (str, int, float, bool)):
            lines.append(f"- **{name}**: {value}")
        elif isinstance(value, list):
            lines.append(f"- **{name}**: {len(value)} entries")
        elif isinstance(value, dict):
            lines.append(f"- **{name}**: {len(value)} keys")
    return "\n".join(lines)
