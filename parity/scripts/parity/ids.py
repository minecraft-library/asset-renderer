"""The only place in the toolkit that knows about ``__``, ``_``, ``:``, ``~``, ``=``, ``%`` and
``-dye%06x``.

There are five spellings of one subject across the six sweeps and nothing in the repo could join an
entity row to a block row to a glint row without a per-sweep transform. The canonical form here is
the **reference stem** - the harness's own file name without ``.png`` - because it is the only
spelling produced by one method, round-trip-tested against the live corpus, and already the entity
table's column value. Every other spelling is a transform of it, computed here and nowhere else.

**In the toolkit**, and the qualifier is the whole of the claim. Java spells a separator itself in
several places - the appearance codec's namespace constant, the vanilla prefix the block, entity and
item sweep mains each declare, the ``":"``-to-``"__"`` replace in the armour sweep's stem and in the
glint sweep's, and the same replace in the harness's own glint sweep - and nothing cross-checks any
of them against these constants. That is bounded rather than unguarded in one direction: a drifted
Java prefix makes a sweep look for a reference file that is not there and fail loudly, where a drift
here makes two rows fail to join.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Mapping

NAMESPACE_SEPARATOR = "__"
TOKEN_SEPARATOR = "~"
AXIS_SEPARATOR = "="
ESCAPE = "%"

UNRESERVED = frozenset("abcdefghijklmnopqrstuvwxyz0123456789_.-")

LEATHER_DYE_RGB = 0xB04030

REPEATABLE_AXES = frozenset({"toggle", "equip"})


class IdError(ValueError):
    """A stem the grammar rejects. Carries the offending character and its index where relevant."""


class Spelling(Enum):
    """The five spellings, measured off the tree rather than designed."""

    REF_STEM = "ref_stem"      # minecraft__axolotl_gold      entity table column, every ref file
    COLON = "colon"            # minecraft:acacia_button      block / item / glint table column
    SWEEP_DIR = "sweep_dir"    # minecraft_acacia_button      block / item output dir, ONE underscore
    STEM_DIR = "stem_dir"      # minecraft__nether_star       entity / armour / glint output dir
    SCOPE = "scope"            # full | skull                 the player sweep, no namespace


_SWEEP_KEY = {
    "entity": Spelling.REF_STEM,
    "armor": Spelling.REF_STEM,
    "block": Spelling.COLON,
    "item": Spelling.COLON,
    "glint": Spelling.COLON,
    "player": Spelling.SCOPE,
}

_SWEEP_DIR = {
    "entity": Spelling.STEM_DIR,
    "armor": Spelling.STEM_DIR,
    "glint": Spelling.STEM_DIR,
    "block": Spelling.SWEEP_DIR,
    "item": Spelling.SWEEP_DIR,
    "player": Spelling.SCOPE,
}


@dataclass(frozen=True, order=True)
class SubjectId:
    """One rendered thing.

    ``qualifiers`` keep list order and are never sorted - the armour sweep relies on it (material,
    then ``baby``). ``tokens`` are always sorted bytewise by the whole ``axis=value`` text, which is
    one rule with no table, so byte order and string order agree and the two repos cannot drift.
    """

    namespace: str | None
    base: str
    qualifiers: tuple[str, ...] = ()
    tokens: tuple[str, ...] = ()


# --- the appearance-key grammar (spine 4.2) ------------------------------------------------------

def parse_ref_stem(stem: str) -> SubjectId:
    """Parse a reference stem.

    The head is **not** split into base and qualifiers: ``minecraft__wolf_pale`` is entity ``wolf``
    in coat ``pale`` or an entity named ``wolf_pale``, and only the loaded entity index can say
    which. Python holds no index, so the whole path becomes ``base`` and ``split_head`` is offered
    to a caller that has a family map. Every gate-path use is a join between two files that spell
    the head identically, so the ambiguity never has to be resolved for a comparison - saying so
    explicitly is what stops someone adding a guess.
    """
    for index, char in enumerate(stem):
        if "A" <= char <= "Z":
            raise IdError(f"uppercase {char!r} at {index}")

    head, _, tail = stem.partition(TOKEN_SEPARATOR)
    tokens = tuple(sorted(tail.split(TOKEN_SEPARATOR))) if tail else ()
    for token in tokens:
        if AXIS_SEPARATOR not in token:
            raise IdError(f"token {token!r} has no {AXIS_SEPARATOR!r}")

    namespace: str | None = None
    if NAMESPACE_SEPARATOR in head:
        namespace, _, head = head.partition(NAMESPACE_SEPARATOR)

    return SubjectId(namespace=namespace, base=head, tokens=tokens)


def format_ref_stem(sid: SubjectId) -> str:
    name = "" if sid.namespace is None else f"{sid.namespace}{NAMESPACE_SEPARATOR}"
    name += sid.base
    for qualifier in sid.qualifiers:
        name += f"_{qualifier}"
    for token in sorted(sid.tokens):
        name += f"{TOKEN_SEPARATOR}{token}"
    return name


def axes(sid: SubjectId) -> dict[str, list[str]]:
    """Tokens split at the FIRST ``=`` only, since a value may contain one.

    ``__`` stands in for ``:`` inside a value too, which is what makes
    ``minecraft__enderman~carried=minecraft__grass_block`` legal.
    """
    out: dict[str, list[str]] = {}
    for token in sid.tokens:
        axis, _, value = token.partition(AXIS_SEPARATOR)
        out.setdefault(axis, []).append(value.replace(NAMESPACE_SEPARATOR, ":"))
    return out


def split_head(sid: SubjectId, families: Mapping[str, set[str]]) -> SubjectId:
    """Split ``base`` into base plus one qualifier using a caller-supplied family map.

    The longest family whose remainder is one of its own declared options wins, matching the asset
    side's resolution against the loaded entity index.
    """
    best: tuple[str, str] | None = None
    for family, options in families.items():
        prefix = f"{family}_"
        if not sid.base.startswith(prefix):
            continue
        remainder = sid.base[len(prefix):]
        if remainder in options and (best is None or len(family) > len(best[0])):
            best = (family, remainder)
    if best is None:
        return sid
    return SubjectId(sid.namespace, best[0], (best[1],), sid.tokens)


def escape(value: str) -> str:
    out = []
    for char in value:
        if char in UNRESERVED:
            out.append(char)
        else:
            out.extend(f"{ESCAPE}{byte:02x}" for byte in char.encode("utf-8"))
    return "".join(out)


def unescape(value: str) -> str:
    out = bytearray()
    index = 0
    while index < len(value):
        char = value[index]
        if char != ESCAPE:
            out.extend(char.encode("utf-8"))
            index += 1
            continue
        pair = value[index + 1:index + 3]
        if len(pair) != 2:
            raise IdError(f"truncated escape at {index}")
        if any(digit not in "0123456789abcdef" for digit in pair):
            raise IdError(f"non-lowercase-hex escape {ESCAPE}{pair} at {index}")
        out.append(int(pair, 16))
        index += 3
    return out.decode("utf-8")


# --- the five spellings ---------------------------------------------------------------------------

def format_as(sid: SubjectId, spelling: Spelling) -> str:
    if spelling is Spelling.REF_STEM:
        return format_ref_stem(sid)
    if spelling is Spelling.STEM_DIR:
        return format_ref_stem(sid)
    if spelling is Spelling.SCOPE:
        return sid.base
    stem = format_ref_stem(sid)
    if sid.namespace is None:
        return stem
    separator = ":" if spelling is Spelling.COLON else "_"
    return stem.replace(f"{sid.namespace}{NAMESPACE_SEPARATOR}", f"{sid.namespace}{separator}", 1)


def parse_as(text: str, spelling: Spelling) -> SubjectId:
    if spelling is Spelling.SCOPE:
        return SubjectId(namespace=None, base=text)
    if spelling is Spelling.COLON:
        namespace, sep, rest = text.partition(":")
        if not sep:
            return parse_ref_stem(text)
        return parse_ref_stem(f"{namespace}{NAMESPACE_SEPARATOR}{rest}")
    if spelling is Spelling.SWEEP_DIR:
        namespace, sep, rest = text.partition("_")
        if not sep:
            return parse_ref_stem(text)
        return parse_ref_stem(f"{namespace}{NAMESPACE_SEPARATOR}{rest}")
    return parse_ref_stem(text)


def sweep_key(sid: SubjectId, sweep: str) -> str:
    """The spelling that sweep's own table uses, so a row and a directory join without a transform."""
    return format_as(sid, _SWEEP_KEY[sweep])


def output_dir(sid: SubjectId, sweep: str) -> str:
    """block / item take ONE underscore; entity / armour / glint take two; player is a bare scope."""
    return format_as(sid, _SWEEP_DIR[sweep])


def reference_file(sid: SubjectId, sweep: str = "entity") -> str:
    """Always the ref stem plus ``.png`` - the reference tree has one spelling."""
    return f"{format_ref_stem(sid)}.png"


# --- the armour stem, a third grammar that stays one ------------------------------------------------

def dye_suffix(rgb: int = LEATHER_DYE_RGB) -> str:
    return f"-dye{rgb:06x}"


def armor_stem(entity_id: str, material: str, dyed: bool = False, baby: bool = False) -> str:
    """Byte-for-byte the harness ``ArmorSweep``'s string; the two must agree or the sweep finds no
    reference.

    ``-dye%06x`` is **not** an appearance-codec token - the codec's axis is ``armor_dye`` - and the
    material rides the head rather than an ``~armor=`` token, which is why the armour sweep and the
    entity sweep cannot read each other's names.
    """
    stem = entity_id.replace(":", NAMESPACE_SEPARATOR) + "_" + material
    if dyed:
        stem += dye_suffix()
    if baby:
        stem += "_baby"
    return stem
