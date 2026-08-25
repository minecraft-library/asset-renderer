"""What the source says about its own parity reach: the ``@Parity`` declarations, read from text.

A declaration joins one compilation unit to one named claim in the blindness map. This module is the
one reader of them, and everything it answers is a pure function of ``(source tree, map)``.

**It reads source and never bytecode, and the usual reason for that is wrong.** A ``SOURCE``-retention
annotation leaves no trace in a class file - but javac still WRITES one for an annotated package
declaration, a 114-byte synthetic ``package-info.class`` whose only attribute is ``SourceFile``,
because it decides whether to emit that file by asking whether the package declaration carries any
annotation at all, before retention is consulted, and the class writer then drops the annotation. A
bytecode reader would therefore open a valid class file, find zero annotations and conclude the
package declares nothing. That is a silent wrong answer over every package carrying a line, which is
the one failure this whole mechanism exists against.

**It lexes and does not strip.** A one-regex comment strip has a live counter-example in this tree: a
loader carries ``renderer/*.json`` inside a printf format string, which opens a phantom comment that
swallows to the next ``*/`` eleven lines later, taking two code lines and a whole javadoc block with
it, silently. :func:`blank` is a four-state lexer that blanks comments and string literals while
preserving newlines and offsets, so a line number is still a line number afterwards and a ``@Parity``
inside a string is invisible to the scan rather than counted.

**Every refusal here has zero instances in the tree**, which is what makes refusing them free. Each
would otherwise produce a plausible wrong answer rather than a crash - a declaration that reads as
though the file said something while the planner plans nothing - so the refusal names the file, the
line, the shape and the fix. The one non-fatal answer is a ``@Parity`` the lexer found inside a
comment or a string: the prose is correct and the reader is simply not counting it, which is the only
thing its author could be wrong about.
"""

from __future__ import annotations

import posixpath
import re
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Iterable, Sequence

from parity.blindness import BLINDNESS_FILE, compile_glob
from parity.norm import Refused, read_json, write_json

#: The source roots declarations are read from, in scan order. Nothing outside them is scanned or
#: derivable. The renderer's own root is first because it is where the library root sits.
SOURCE_ROOTS = ("src/main/java", "parity/src/main/java", "tooling/src/main/java",
                "tooling/src/test/java", "client/src/main/java", "harness/src/client/java")

#: The library's own root package, the one package a ``PACKAGE`` scope is legal on, resolved against
#: the FIRST source root alone. Every other root refuses the narrow arm: a leaf package answers for
#: its tree everywhere, and one library root per source root would invent five more places the arm is
#: legal for no measured need.
LIBRARY_ROOT = "lib/minecraft/renderer"

#: Where the vocabulary itself lives, which is what the constant lists are read out of. Its own build
#: rather than the renderer's - the five types import nothing but ``java.lang.annotation``, so a build
#: that writes a declaration compiles against them without inheriting anything else.
VOCABULARY = f"parity/src/main/java/{LIBRARY_ROOT}/parity"

#: The annotation's own simple name.
ANNOTATION = "Parity"

#: The file whose declaration is its package's rather than a type's.
PACKAGE_INFO = "package-info.java"

#: The members a declaration may carry, against the vocabulary each one takes.
#: The member that marks a type as wiring rather than behaviour. A declaration naming it alone makes
#: no claim about any artifact - it says reach does not compose THROUGH this type - so it is the one
#: shape exempt from the claim-or-as rule. A flag rather than a `Subject`, because a subject says
#: which pipelines a type reaches and this says not to ask; one member cannot answer both.
SEAM_MEMBER = "ignored"

MEMBERS = {"claim": None, "as": None, "mode": "Mode", "scope": "Scope", "subject": "Subject",
           SEAM_MEMBER: None}

#: The modes that narrow, so a declaration of one has to say so at the call site.
NARROWING = ("DEMOTE", "SUPPRESS")

#: How a stored row spells each declared mode.
STORED_MODE = {"SELECT": "select", "DEMOTE": "demote", "SUPPRESS": "suppress"}

_ANNOTATION_AT = re.compile(r"@\s*" + ANNOTATION + r"\b")
_DOTTED_AFTER = re.compile(r"\s*\.\s*(\w+)")
_ENUM_CONSTANT = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*(?=[,;}])", re.MULTILINE)
_TYPE_KEYWORD = re.compile(r"\b(?:@\s*interface|class|interface|enum|record)\b")
_PACKAGE_KEYWORD = re.compile(r"\bpackage\s+([\w.]+)\s*;")
_MEMBER = re.compile(r"^\s*(\w+)\s*=\s*(.+?)\s*$", re.DOTALL)


class DeclarationError(Refused):
    """A declaration the reader refuses rather than resolving to a plausible wrong answer.

    A refusal rather than an error of its own kind, so it reaches the exit code every other
    declined precondition already has and a caller needs no translation layer to tell them apart.
    """

    def __init__(self, path: str, line: int, shape: str, fix: str) -> None:
        self.path = path
        self.line = line
        self.shape = shape
        self.fix = fix
        super().__init__(f"{path}:{line}: {shape} - {fix}")


@dataclass(frozen=True)
class Report:
    """A ``@Parity`` the lexer found somewhere the scan does not count, named rather than refused."""

    path: str
    line: int
    where: str


@dataclass(frozen=True)
class Declaration:
    """One ``@Parity`` on a package declaration or on a top-level type.

    ``written`` is the member names the source spells, before any default is materialised. The
    guard that holds every carrier of one claim to one mode compares what was WRITTEN: a joining
    declaration declines to repeat its claim's subject, so a comparison over materialised defaults
    would have every joiner asserting the claim is about no renderer and refusing the claim it
    belongs to.
    """

    path: str
    line: int
    on: str
    claim: str
    joins: str
    mode: str
    scope: str
    subject: tuple[str, ...]
    written: frozenset[str]
    #: Wiring rather than behaviour: reach does not compose THROUGH this type, and it claims nothing.
    #: Last and defaulted, so every construction that predates the seam reads as what it is.
    ignored: bool = False

    @property
    def trigger_path(self) -> str:
        """The trigger this declaration derives, in the map's own glob grammar."""
        if self.on != "package":
            return self.path
        directory = self.path[: -len(PACKAGE_INFO) - 1]
        return f"{directory}/**" if self.scope == "SUBTREE" else f"{directory}/*"


@dataclass
class Scan:
    """Every declaration the source root carries, and every mention it declined to count."""

    declarations: list[Declaration] = field(default_factory=list)
    reports: list[Report] = field(default_factory=list)

    def by_claim(self) -> dict[str, list[Declaration]]:
        """The declarations of each claim, keyed by slug, in the order the tree was walked.

        The two shapes that make no claim are not among them, and for one reason: a declaration
        carrying no slug would be filed under a claim spelled '' and the map asked to carry a row for
        it - which is every rule whose own ``claim_key`` is empty inheriting all of their paths. A
        seam says reach does not compose through this type and a reach says what a type reaches, and
        both are statements about the graph rather than about the store.
        """
        out: dict[str, list[Declaration]] = {}
        for declaration in self.declarations:
            if declaration.ignored or not declaration.claim:
                continue
            out.setdefault(declaration.claim, []).append(declaration)
        return out

    def seams(self) -> list[Declaration]:
        """The declarations naming a wiring type, which the reach graph reads and the map does not."""
        return [declaration for declaration in self.declarations if declaration.ignored]


def blank(source: str) -> str:
    """Blank every comment and string literal, preserving newlines and every offset.

    Four states and one lookahead. A blanked character becomes a space so that an offset into the
    result is the same offset into the original and a line number survives; a newline inside a block
    comment or a text block stays a newline for the same reason.

    Text blocks are lexed before ordinary strings, because ``\"\"\"`` opens one and its first two
    characters are an empty string literal to any reader that checks ``"`` first.
    """
    out = list(source)
    index = 0
    end = len(source)
    while index < end:
        if source.startswith("//", index):
            while index < end and source[index] != "\n":
                out[index] = " "
                index += 1
        elif source.startswith("/*", index):
            out[index] = out[index + 1] = " "
            index += 2
            while index < end and not source.startswith("*/", index):
                if source[index] != "\n":
                    out[index] = " "
                index += 1
            for _ in range(2):
                if index < end:
                    out[index] = " "
                    index += 1
        elif source.startswith('"""', index):
            for offset in range(3):
                out[index + offset] = " "
            index += 3
            while index < end and not source.startswith('"""', index):
                if source[index] == "\\":
                    out[index] = " "
                    index += 1
                if index < end and source[index] != "\n":
                    out[index] = " "
                index += 1
            for _ in range(3):
                if index < end:
                    out[index] = " "
                    index += 1
        elif source[index] in "\"'":
            quote = source[index]
            out[index] = " "
            index += 1
            while index < end and source[index] != quote:
                if source[index] == "\n":
                    # An unterminated literal. Stopping at the newline keeps one bad line from
                    # blanking the rest of the file, which is the precedent's own defect.
                    break
                if source[index] == "\\":
                    out[index] = " "
                    index += 1
                if index < end and source[index] != "\n":
                    out[index] = " "
                index += 1
            if index < end and source[index] == quote:
                out[index] = " "
                index += 1
        else:
            index += 1
    return "".join(out)


def enum_constants(source: str) -> tuple[str, ...]:
    """The constants an enum declares, read off its own source.

    The vocabularies are read rather than transcribed, so a constant added to one is accepted here
    without this module being edited - the enum file is the definition, and a second copy of it
    would be a second thing to keep true.
    """
    body = blank(source)
    opening = body.find("{")
    return tuple(_ENUM_CONSTANT.findall(body[opening:])) if opening >= 0 else ()


def vocabularies(repo_root: Path) -> dict[str, tuple[str, ...]]:
    """The constants each closed vocabulary declares, read out of the annotation's own package."""
    out: dict[str, tuple[str, ...]] = {}
    for name in ("Mode", "Scope", "Subject"):
        target = repo_root / VOCABULARY / f"{name}.java"
        if not target.is_file():
            raise DeclarationError(f"{VOCABULARY}/{name}.java", 1, "the vocabulary is absent",
                                   "restore the enum the declarations are written against")
        out[name] = enum_constants(target.read_text(encoding="utf-8"))
    return out


def _line_of(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def _split(arguments: str) -> list[str]:
    """The top-level comma-separated arguments, so a braced subject list stays one argument."""
    out: list[str] = []
    depth = 0
    current = ""
    for char in arguments:
        if char in "{(":
            depth += 1
        elif char in "})":
            depth -= 1
        if char == "," and depth == 0:
            out.append(current)
            current = ""
        else:
            current += char
    if current.strip():
        out.append(current)
    return out


def _constant(path: str, line: int, member: str, enum: str, value: str,
              vocabulary: Sequence[str]) -> str:
    """One enum constant, qualified or bare, checked against the member's own vocabulary."""
    text = value.strip()
    qualified = text.split(".")
    if len(qualified) == 2:
        if qualified[0].strip() != enum:
            raise DeclarationError(path, line, f"'{member}' takes a {enum} and reads '{text}'",
                                   f"write {enum}.<constant> or the bare constant")
        text = qualified[1].strip()
    elif len(qualified) != 1:
        raise DeclarationError(path, line, f"'{member}' reads '{value.strip()}'",
                               f"write {enum}.<constant> or the bare constant")
    if text not in vocabulary:
        raise DeclarationError(path, line, f"{enum} declares no constant '{text}'",
                               "write one of: " + ", ".join(vocabulary))
    return text


def _members(path: str, line: int, arguments: str,
             vocabulary: dict[str, tuple[str, ...]]) -> dict[str, object]:
    """The members a declaration spells, each checked against its own grammar."""
    out: dict[str, object] = {}
    for argument in _split(arguments):
        matched = _MEMBER.match(argument)
        if not matched:
            raise DeclarationError(path, line, f"the argument '{argument.strip()}' names no member",
                                   "every argument is '<member> = <value>'")
        name, value = matched.group(1), matched.group(2)
        if name not in MEMBERS:
            raise DeclarationError(path, line, f"'{name}' is not a member of @{ANNOTATION}",
                                   "the members are: " + ", ".join(sorted(MEMBERS)))
        if name in out:
            raise DeclarationError(path, line, f"'{name}' is written twice",
                                   "one value per member")
        if name == "claim":
            literal = re.fullmatch(r'"([^"]*)"', value)
            if not literal:
                raise DeclarationError(path, line, f"'claim' reads '{value}' and not a string",
                                       'write claim = "<slug>"')
            out[name] = literal.group(1)
        elif name == "as":
            reference = re.fullmatch(r"([\w.]+)\s*\.\s*class", value)
            if not reference:
                raise DeclarationError(path, line, f"'as' reads '{value}' and not a class literal",
                                       "write as = <Type>.class")
            out[name] = reference.group(1).rsplit(".", 1)[-1]
        elif name == "subject":
            braced = re.fullmatch(r"\{(.*)}", value, re.DOTALL)
            listed = braced.group(1) if braced else value
            constants = [part for part in _split(listed) if part.strip()]
            out[name] = tuple(
                _constant(path, line, name, "Subject", part, vocabulary["Subject"])
                for part in constants)
        elif name == SEAM_MEMBER:
            # The one boolean member, and the only value worth writing is the one that is not the
            # default: `ignored = false` states the default and reads as though it decided something.
            if value.strip() != "true":
                raise DeclarationError(
                    path, line, f"'{SEAM_MEMBER}' reads '{value}' and not 'true'",
                    f"write {SEAM_MEMBER} = true, or drop the member - false is the default and "
                    "writing it states nothing")
            out[name] = True
        else:
            out[name] = _constant(path, line, name, MEMBERS[name], value, vocabulary[MEMBERS[name]])
    return out


def _target(path: str, line: int, body: str, after: int) -> str:
    """Whether the declaration below this annotation is a package or a type."""
    package = _PACKAGE_KEYWORD.search(body, after)
    kind = _TYPE_KEYWORD.search(body, after)
    if package and (not kind or package.start() < kind.start()):
        return "package"
    if kind:
        return "type"
    raise DeclarationError(path, line, f"the @{ANNOTATION} declares nothing",
                           "put it on a package declaration or on a top-level type")


def parse(path: str, source: str, vocabulary: dict[str, tuple[str, ...]],
          library_root: str = f"{SOURCE_ROOTS[0]}/{LIBRARY_ROOT}") -> tuple[list[Declaration], list[Report]]:
    """Every declaration one compilation unit carries, and every mention it declines to count.

    :param path the compilation unit's repo-relative path
    :param source its text
    :param vocabulary the constants each closed vocabulary declares
    :param library_root the repo-relative directory of the one package a ``PACKAGE`` scope is legal on
    """
    # Before the lexer, because a file whose text does not carry the token anywhere can hold neither
    # a declaration nor a mention, and that is an exact answer rather than a sampling: this is the
    # same pattern the loop below runs, over the same text. It skips the lex on all but a handful of
    # the source root, which is most of what makes the live derivation affordable at plan time.
    if not _ANNOTATION_AT.search(source):
        return [], []

    body = blank(source)
    declarations: list[Declaration] = []
    reports: list[Report] = []
    directory = path.rsplit("/", 1)[0] if "/" in path else ""
    is_package_info = path.rsplit("/", 1)[-1] == PACKAGE_INFO
    is_library_root = directory == library_root

    for found in _ANNOTATION_AT.finditer(source):
        start, after = found.start(), found.end()
        line = _line_of(source, start)
        if not _ANNOTATION_AT.match(body, start):
            reports.append(Report(path, line, "a comment or a string literal"))
            continue
        dotted = _DOTTED_AFTER.match(body, after)
        if dotted:
            raise DeclarationError(
                path, line, f"@{ANNOTATION}.{dotted.group(1)} is the container spelling",
                f"stack two @{ANNOTATION} lines instead")
        if body.count("{", 0, start) - body.count("}", 0, start) != 0:
            raise DeclarationError(
                path, line, f"the @{ANNOTATION} is inside a type body",
                "a nested type resolves to its file's path, which its enclosing declaration "
                "already claims - declare it on the top-level type")

        arguments = ""
        cursor = after
        while cursor < len(body) and body[cursor].isspace():
            cursor += 1
        if cursor < len(body) and body[cursor] == "(":
            depth, index = 0, cursor
            while index < len(body):
                if body[index] == "(":
                    depth += 1
                elif body[index] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                index += 1
            if depth != 0:
                raise DeclarationError(path, line, "the argument list never closes",
                                       "balance the parentheses")
            # Balanced against the blanked text, so a parenthesis inside a string cannot close the
            # list, and read out of the original, because a blanked string literal is spaces.
            arguments = source[cursor + 1:index]
            if "\n" in arguments:
                raise DeclarationError(
                    path, line, "the argument list spans more than one line",
                    "a declaration is one line - drop a member rather than wrapping it")
            after = index + 1

        spelled = _members(path, line, arguments, vocabulary)
        on = _target(path, line, body, after)
        if on == "package" and not is_package_info:
            raise DeclarationError(path, line, "the package declaration is not in " + PACKAGE_INFO,
                                   f"declare a package's claim in its own {PACKAGE_INFO}")
        if is_package_info and _TYPE_KEYWORD.search(body):
            raise DeclarationError(path, line, f"this {PACKAGE_INFO} also declares a type",
                                   "a package doc holds no code, so the two claims cannot be told "
                                   "apart - move the type to its own file")

        claim = str(spelled.get("claim", ""))
        joins = str(spelled.get("as", ""))
        # A seam declaration is the one shape that names neither, because it makes no claim about any
        # artifact. `ignored = true` says this type is WIRING - reach does not compose through it -
        # which is a statement about the graph rather than about the store, and giving it a slug
        # would file it under a claim it does not make.
        seam = bool(spelled.get(SEAM_MEMBER, False))
        if seam and (claim or joins):
            raise DeclarationError(
                path, line, f"the declaration is {SEAM_MEMBER} and also names a claim",
                f"an {SEAM_MEMBER} type makes no claim about an artifact - drop the claim or the "
                f"{SEAM_MEMBER}")
        if seam and spelled.get("subject"):
            raise DeclarationError(
                path, line, f"the declaration is {SEAM_MEMBER} and also names a subject",
                "a subject says which pipelines a type reaches and this says not to ask - drop one")
        # The third shape, and the second that names no claim: a REACH, which is what a type says
        # where the reference graph cannot derive one. A type reached only across a seam, or built by
        # a service loader from a file no constant pool mentions, is reachable from no producer root
        # and answers nothing - so it writes what it reaches here and `reach check` refuses one that
        # writes neither. It is not a claim about an artifact, so giving it a slug would file it
        # under one it does not make.
        subjects = tuple(spelled.get("subject", ()))
        reach_only = bool(subjects) and not (claim or joins)
        if reach_only and on == "package":
            raise DeclarationError(
                path, line, "a package declares a reach and no claim",
                "a reach is what one TYPE the graph cannot derive says about itself; a package says "
                "what its files claim - write a claim, or move the reach onto the type")
        if not seam and not reach_only and bool(claim) == bool(joins):
            shape = "names both a claim and an as" if claim else "names neither a claim nor an as"
            raise DeclarationError(path, line, f"the declaration {shape}",
                                   'write exactly one of claim = "<slug>" or as = <Type>.class, '
                                   f"{SEAM_MEMBER} = true for a wiring type, or subject = {{...}} "
                                   "for a reach the graph cannot derive")
        if "scope" in spelled and on != "package":
            raise DeclarationError(path, line, "'scope' is written on a type",
                                   "scope is read on a package declaration alone - drop it")
        scope = str(spelled.get("scope", "SUBTREE"))
        if scope == "PACKAGE" and not is_library_root:
            raise DeclarationError(
                path, line, "'scope' is PACKAGE outside the library root",
                "a leaf package answers for its tree, so a package added below it inherits what "
                "its parent claims - drop the member")

        declarations.append(Declaration(
            path=path, line=line, on=on, claim=claim, joins=joins,
            mode=str(spelled.get("mode", "SELECT")), scope=scope,
            subject=tuple(spelled.get("subject", ())), ignored=seam,
            written=frozenset(spelled)))
    return declarations, reports


@dataclass(frozen=True)
class Claim:
    """What a stored row says about one claim, which is what a declaration is checked against."""

    key: str
    mode: str
    trigger_paths: tuple[str, ...]
    derived: bool = False


def claims_of(rules: Iterable) -> tuple[Claim, ...]:
    """The claims a rule list carries, keyed by the slug a declaration joins by."""
    return tuple(
        Claim(key=getattr(rule, "claim_key", "") or "", mode=rule.mode,
              trigger_paths=tuple(rule.trigger_paths),
              derived=bool(getattr(rule, "derived", False)))
        for rule in rules)


def scan(repo_root: Path, source_roots: Sequence[str] = SOURCE_ROOTS,
         library_root: str = LIBRARY_ROOT,
         vocabulary: dict[str, tuple[str, ...]] | None = None) -> Scan:
    """Every declaration the source roots carry, with every join resolved to a slug.

    An ``as`` is one level deep and never a chain: it names a type that names a claim, so the
    indirection a reader follows is one hop into another root and stops there. The anchor it lands on
    is looked up across every root at once, because a claim is one thing wherever it is written down.

    The library root is resolved against the FIRST root alone, so ``Scope.PACKAGE`` stays legal on the
    one package it is legal on today and every other root refuses it. A root that does not exist on
    this checkout contributes nothing rather than raising - a source tree is a property of the
    checkout, and refusing one would make the scan depend on which builds happen to be present.

    :param repo_root the repository root every path is relative to
    :param source_roots the trees to walk, relative to it, in scan order
    :param library_root the library's own root package, relative to the first source root
    :param vocabulary the closed vocabularies, read out of the annotation's own package when absent
    """
    vocabulary = vocabulary if vocabulary is not None else vocabularies(repo_root)
    root_directory = posixpath.normpath(f"{source_roots[0]}/{library_root}")
    raw: list[Declaration] = []
    result = Scan()
    for source_root in source_roots:
        root = repo_root / source_root
        if not root.is_dir():
            continue
        for target in sorted(root.rglob("*.java")):
            path = target.relative_to(repo_root).as_posix()
            found, reports = parse(path, target.read_text(encoding="utf-8"), vocabulary,
                                   root_directory)
            raw.extend(found)
            result.reports.extend(reports)

    anchors: dict[str, Declaration] = {}
    for declaration in raw:
        if declaration.on != "type":
            continue
        name = declaration.path.rsplit("/", 1)[-1][: -len(".java")]
        anchors.setdefault(name, declaration)

    for declaration in raw:
        if not declaration.joins:
            result.declarations.append(declaration)
            continue
        anchor = anchors.get(declaration.joins)
        if anchor is None:
            raise DeclarationError(
                declaration.path, declaration.line,
                f"'as' names {declaration.joins}, which carries no declaration",
                f"point at a type that declares a claim, or write claim = \"<slug>\" here")
        if anchor.path == declaration.path:
            raise DeclarationError(declaration.path, declaration.line,
                                   "'as' names this file's own type",
                                   'a declaration joins another type or writes claim = "<slug>"')
        if anchor.joins:
            raise DeclarationError(
                declaration.path, declaration.line,
                f"'as' names {declaration.joins}, which joins by 'as' itself",
                "the indirection is one level - point at the type that names the claim")
        result.declarations.append(
            Declaration(path=declaration.path, line=declaration.line, on=declaration.on,
                        claim=anchor.claim, joins=declaration.joins, mode=declaration.mode,
                        scope=declaration.scope, subject=declaration.subject,
                        ignored=declaration.ignored, written=declaration.written))

    _refuse_two_declarations_of_one_claim(result)
    return result


def _refuse_two_declarations_of_one_claim(result: Scan) -> None:
    """One claim may not reach one path twice - refuse, rather than merging, picking or last-winning."""
    for claim, declarations in result.by_claim().items():
        seen: dict[str, Declaration] = {}
        for declaration in declarations:
            first = seen.get(declaration.path)
            if first is not None:
                raise DeclarationError(
                    declaration.path, declaration.line,
                    f"'{claim}' is declared twice in this file",
                    f"it is already declared at line {first.line} - one declaration per claim")
            seen[declaration.path] = declaration
        packages = [one for one in declarations if one.on == "package"]
        for package in packages:
            pattern = compile_glob(package.trigger_path)
            for other in declarations:
                if other is package or not pattern.match(other.path):
                    continue
                raise DeclarationError(
                    other.path, other.line,
                    f"'{claim}' is already declared by {package.path}",
                    "a package declaration is carried by every file below it, so this line adds "
                    "nothing to the union while reading as though it did - drop it")


def derive(result: Scan) -> dict[str, list[str]]:
    """The trigger paths each claim's declarations derive, sorted, keyed by slug."""
    out: dict[str, list[str]] = {}
    for claim, declarations in result.by_claim().items():
        out[claim] = sorted({one.trigger_path for one in declarations})
    return out


def verify(result: Scan, claims: Sequence[Claim], files: Sequence[str]) -> None:
    """Refuse every declaration the map contradicts or that subtracts from nothing.

    Split from :func:`scan` because these three need the map and the tree beside the source, and
    everything :func:`scan` refuses is answerable from one compilation unit or from the source root.
    """
    stored = {claim.key: claim for claim in claims if claim.key}
    derived = derive(result)
    for claim, declarations in result.by_claim().items():
        row = stored.get(claim)
        if row is None:
            raise DeclarationError(
                declarations[0].path, declarations[0].line,
                f"no rule carries the claim '{claim}'",
                "the file reads as though it said something and plans nothing - coin the row "
                "first, or fix the slug")
        narrowing = row.mode in ("demote", "suppress")
        for declaration in declarations:
            if narrowing and "mode" not in declaration.written:
                raise DeclarationError(
                    declaration.path, declaration.line,
                    f"'{claim}' is a {row.mode} and this declaration does not say so",
                    f"write mode = Mode.{row.mode.upper()} - whether a file takes something back "
                    "is what a reader needs at the call site")
            if narrowing and declaration.on == "package" and "scope" not in declaration.written:
                raise DeclarationError(
                    declaration.path, declaration.line,
                    f"'{claim}' is a {row.mode} and its scope is left at the default",
                    "a wider scope over-plans for a selection and under-plans for a subtraction - "
                    "write the scope this subtraction is meant to reach")
        # A derived claim's demotion subtracts from its OWN selection, the graph having put the
        # artifact there, so it needs no sibling claim on the path to remove anything. That is the
        # shipped shape wherever the graph reaches an artifact a perturbation says cannot move.
        if not narrowing or row.derived:
            continue
        reached = {path for glob in derived[claim] for path in files if compile_glob(glob).match(path)}
        elsewhere = any(
            compile_glob(glob).match(path)
            for other in claims if other.key != claim
            for glob in other.trigger_paths
            for path in reached)
        if reached and not elsewhere:
            raise DeclarationError(
                declarations[0].path, declarations[0].line,
                f"'{claim}' subtracts on paths no other claim reaches",
                "a demotion removes what another claim selected on the same path, so this one "
                "removes nothing while reading at the call site as though it did")


def regenerate(repo_root: Path, store_root: Path, check: bool = False) -> list[str]:
    """Rewrite every rule's ``trigger_paths`` as the sorted union of its two halves.

    The authored half is what no annotation in the main source set can reach - a ``.kts``, a
    ``.py``, a path in the harness build, a test class whose own path is already store state. The
    derived half is what the declarations carrying that rule's ``claim_key`` say. A rule with
    neither derives nothing and comes back with its authored list sorted, which is the whole of what
    "generated" means for it.

    The union is a set and the output is sorted, because a generated array whose order is a property
    of how somebody last edited it cannot be compared byte for byte - which is what the guard over
    this function does. The authored order survives where it is authored.

    :param repo_root the repository root the source tree is scanned from
    :param store_root the store root holding the map
    :param check answer what would move without writing
    :returns: the ids whose ``trigger_paths`` moved, in the order the rules sit in the file
    """
    target = store_root / BLINDNESS_FILE
    payload = read_json(target)
    derived = derive(scan(repo_root))
    moved: list[str] = []
    for row in payload.get("rules", []):
        authored = row.get("authored_paths")
        if authored is None:
            raise DeclarationError(
                target.as_posix(), 1, f"rule {row.get('id')} carries no authored_paths",
                "every rule declares the half no declaration can derive, empty where there is none")
        union = sorted(set(authored) | set(derived.get(row.get("claim_key", ""), ())))
        if union != row.get("trigger_paths"):
            moved.append(row["id"])
            row["trigger_paths"] = union
    if moved and not check:
        write_json(target, payload)
    return moved


def live(rules: Sequence, repo_root: Path) -> list:
    """The rules with every trigger list re-derived from the tree rather than read from the file.

    What the planner resolves against. The checked-in view is the same union computed by the
    generator, so the two agree by construction and a guard holds them to it - but they agree at
    different moments, and that is the whole point. A file that moved in the commit being gated is
    answered correctly by this one and one regeneration late by the other, and the gate that has to
    be right is the one firing on the commit that performs the move.

    A map whose rules carry no ``claim_key`` derives nothing whatever the tree holds, so the scan is
    skipped rather than run for an answer that cannot be used. That is exact rather than an
    optimisation - a resolved claim is never the empty string, so no declaration can join a row that
    names no claim - and it is what lets this be wired in before the first row names one.

    :param rules the rules as the map holds them
    :param repo_root the repository root the source tree is scanned from
    :returns: the same rules, each carrying the live union
    """
    if not any(rule.claim_key for rule in rules):
        return list(rules)
    derived = derive(scan(repo_root))
    return [replace(rule, trigger_paths=tuple(
        sorted(set(rule.authored_paths) | set(derived.get(rule.claim_key, ())))))
        for rule in rules]


def mode_disagreements(result: Scan, claims: Sequence[Claim]) -> list[str]:
    """Carriers of one claim that wrote different modes, or a mode the stored row does not carry.

    Read off what the source WROTE, before any default is materialised: the declarations that join
    a claim decline to repeat its subject, so a comparison over materialised defaults would have
    every joiner asserting the claim is about no renderer and refusing the claim it belongs to.
    """
    stored = {claim.key: claim.mode for claim in claims if claim.key}
    out: list[str] = []
    for claim, declarations in sorted(result.by_claim().items()):
        row = stored.get(claim)
        written = {one.mode for one in declarations if "mode" in one.written}
        if len(written) > 1:
            out.append(f"{claim}: carriers wrote " + ", ".join(sorted(written)))
        for spelled in sorted(written):
            if row is not None and STORED_MODE[spelled] != row:
                out.append(f"{claim}: a carrier wrote {spelled} and the row carries {row}")
    return out
