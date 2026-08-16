"""Per-subject rendered-byte differ over two sweep-output trees.

The generic byte-neutrality gate the corpus asks for over and over - hash the player contact sheets,
hash ``fluidRenderer``, hash ``portalRenderer`` - in its committed form, replacing the hand-run
stash-A/B recipe's ``join -j2 | awk '$2!=$3'`` tail.

For a tree that is not per-subject output, ``manifest build`` plus ``manifest verify --base`` is the
right shape instead; this exists for the ``<subject>/java.png`` directory layout specifically.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

from parity.norm import ComparisonFailed, MissingInput, sha256_file
from parity.sweep import DELTA, read_table

#: The first of these that exists is the subject's render, so the animated glint sweep is covered
#: by the same walk as the five static ones.
RENDER_NAMES = ("java.png", "java.gif")


@dataclass
class Verdict:
    identical: list[str] = field(default_factory=list)
    moved: list[dict] = field(default_factory=list)
    dropped: list[str] = field(default_factory=list)
    added: list[str] = field(default_factory=list)

    def clean(self) -> bool:
        return not (self.moved or self.dropped or self.added)

    def as_dict(self) -> dict:
        return {"added": self.added, "dropped": self.dropped, "moved": self.moved,
                "totals": {"added": len(self.added), "dropped": len(self.dropped),
                           "identical": len(self.identical), "moved": len(self.moved)}}


def index(tree: Path, names: Sequence[str] = RENDER_NAMES) -> dict[str, Path]:
    """Subject directory -> its render file, taking the first name that exists."""
    if not tree.is_dir():
        raise MissingInput(f"no render tree at {tree}")
    found: dict[str, Path] = {}
    for child in sorted(tree.iterdir()):
        if not child.is_dir():
            continue
        for name in names:
            candidate = child / name
            if candidate.is_file():
                found[child.name] = candidate
                break
    return found


def annotations(tree: Path) -> dict[str, str]:
    """Read the tree's own table, if it has one, so a mover carries its metric.

    Both the subject column and the delta column are resolved **by header name**, so a writer that
    renames its key column annotates correctly instead of joining on the wrong field.
    """
    table_path = tree / "parity-report.tsv"
    if not table_path.is_file():
        return {}
    table = read_table(table_path)
    return {row.key: row.values.get(DELTA, "") for row in table.rows}


def diff(before: Path, after: Path, names: Sequence[str] = RENDER_NAMES) -> Verdict:
    left, right = index(before, names), index(after, names)
    left_notes, right_notes = annotations(before), annotations(after)

    verdict = Verdict(
        dropped=sorted(set(left) - set(right)),
        added=sorted(set(right) - set(left)),
    )
    for subject in sorted(set(left) & set(right)):
        one, other = sha256_file(left[subject]), sha256_file(right[subject])
        if one == other:
            verdict.identical.append(subject)
            continue
        row = {"after": other, "before": one, "subject": subject}
        if subject in left_notes or subject in right_notes:
            row["mean_argb_delta"] = [left_notes.get(subject, ""), right_notes.get(subject, "")]
        verdict.moved.append(row)
    return verdict


def raise_on(verdict: Verdict) -> None:
    if verdict.clean():
        return
    raise ComparisonFailed(
        f"rendered bytes differ: {len(verdict.moved)} moved, {len(verdict.dropped)} dropped, "
        f"{len(verdict.added)} added"
    )
