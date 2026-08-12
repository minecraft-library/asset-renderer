"""The toolkit README's two tables and the two sentences beside them, held to what they describe.

Both tables were written once and never re-derived: the Layout table named five of the sixteen
modules with nothing saying it was partial, and there was no command reference at all, so thirteen of
the twenty registered commands appeared in no task, no skill file and no document - only in
``--help``. A table that is a subset of the truth and does not say so reads exactly like a complete
one.

Neither table is generated, because both carry a sentence per row that no docstring line can stand
in for. What is checked is the population: the names in the first column, against the parser and
against the directory listing.

The prose around the command table makes two claims of the same kind and they are checked the same
way - which commands run themselves, out of the build and the hook that run them, and which command
a bare interpreter offers and cannot run, out of the parser with the optional pair taken away.
"""

from __future__ import annotations

import re
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from parity import cli, pixels
from parity.norm import read_text

README = Path(__file__).resolve().parents[1] / "README.md"

#: The repository root, which is where the toolkit's two callers live.
_REPO = README.parents[2]

#: One markdown table body row's first cell, when that cell is a single inline code span.
_ROW = re.compile(r"^\| `([^`]+)` \|")

#: An inline code span holding a bare lower-case token, which is how the prose names a command.
_SPAN = re.compile(r"`([a-z][a-z-]*)`")

#: The head of a toolkit argv the build assembles as a list, which is the command.
_ARGV_HEAD = re.compile(r"buildList \{\s*add\(\"([a-z-]+)\"\)")

#: The same, where the argv is short enough to be passed as plain arguments.
_DIRECT_ARGV = re.compile(r"parityToolkit\(\"([a-z-]+)\"")

#: Every site that assembles one either way. Read back against the two patterns above, because a
#: command reached through a third form is one they would leave out of the derived set silently.
_ARGV_SITE = re.compile(r"parityToolkit\(\*buildList \{|parityToolkit\(\"|argv\.set\(buildList \{")

#: The hook's one invocation, a JS array literal whose head is the interpreter's own argument.
_HOOK_ARGV = re.compile(r"\['scripts/parity',([^\]]*)\]")

#: The one command whose registration is conditional, so a bare interpreter has nineteen and not
#: twenty. It is documented either way - a reader without the optional pair has to be able to find
#: out that the command exists and what it needs.
OPTIONAL_COMMAND = "lab"

#: The one command registered whatever is importable and unable to run without the optional pair.
OFFERED_WITHOUT_ITS_DEPENDENCIES = "panel"

#: The entry points of ``pixels`` that raise when the pair is absent. The two it also offers -
#: ``available`` and ``missing`` - answer either way, which is what lets ``cli`` ask one before
#: deciding whether to register the optional command group.
_NEEDS_THE_PAIR = re.compile(r"\.(require|numpy_module|image_module|load_rgba)\(")


def _section(heading: str) -> list[str]:
    """The first-column code spans of the table under one heading.

    :param heading: the heading line, ``##`` included
    :return: the names, in table order
    """
    text = read_text(README)
    start = text.index(heading)
    end = text.find("\n## ", start + len(heading))
    body = text[start:end if end != -1 else len(text)]
    return [match.group(1) for match in (_ROW.match(line) for line in body.splitlines()) if match]


def _paragraph(opening: str) -> str:
    """One paragraph of the README, named by the words it starts with.

    :param opening: the paragraph's first words, which must occur once
    :return: the paragraph, up to the blank line after it
    """
    text = read_text(README)
    assert text.count(opening) == 1, f"{opening!r} opens {text.count(opening)} paragraphs"
    start = text.index(opening)
    end = text.find("\n\n", start)
    return text[start:end if end != -1 else len(text)]


class TheCommandTable(unittest.TestCase):

    def setUp(self):
        self.documented = _section("## Commands")
        self.registered = sorted(cli.build_parser()[1])

    def test_it_names_every_command_the_parser_registers(self):
        optional = set() if pixels.available() else {OPTIONAL_COMMAND}
        self.assertEqual(sorted(set(self.documented) - optional), self.registered)

    def test_it_names_nothing_the_parser_does_not(self):
        self.assertEqual(sorted(set(self.documented) - set(self.registered) - {OPTIONAL_COMMAND}),
                         [])

    def test_no_command_is_listed_twice(self):
        self.assertEqual(len(self.documented), len(set(self.documented)))


class TheLayoutTable(unittest.TestCase):

    def setUp(self):
        self.documented = _section("## Layout")
        self.modules = sorted(path.name for path in README.parent.glob("*.py")
                              if not path.name.startswith("__"))

    def test_it_names_every_module_in_the_package(self):
        self.assertEqual(sorted(name for name in self.documented if name.endswith(".py")),
                         self.modules)

    def test_it_names_the_two_sub_packages_as_well(self):
        """Both hold code this table is a reader's map of, and one of them holds six modules."""
        for directory in ("lab/", "tests/"):
            self.assertIn(directory, self.documented)
            self.assertTrue((README.parent / directory.rstrip("/")).is_dir())


class TheAutomationNote(unittest.TestCase):
    """Which commands run themselves, against the build scripts and the hook that run them."""

    def setUp(self):
        # Every build script, not the root alone: the build is split by concern and the toolkit
        # invocations live in the parity one, so reading the root would find no command at all.
        scripts = [_REPO / "build.gradle.kts"]
        scripts += sorted((_REPO / "gradle").glob("*.gradle.kts"))
        self.build = "\n".join(read_text(script) for script in scripts)
        self.hook = read_text(_REPO / ".claude/hooks/parity-gate-precommit.js")
        self.heads = _ARGV_HEAD.findall(self.build) + _DIRECT_ARGV.findall(self.build)

    def test_the_scan_reads_every_argv_the_build_assembles(self):
        """Otherwise the sentence is compared against whichever sites the two patterns happen to read.

        A site missed reads as a command the build does not invoke, which is exactly the shape of
        the defect below: a sentence listing seven of the eight.
        """
        self.assertEqual(len(self.heads), len(_ARGV_SITE.findall(self.build)))

    def test_it_names_every_command_the_build_or_the_hook_invokes(self):
        hook = _HOOK_ARGV.search(self.hook)
        self.assertIsNotNone(hook, "the hook spells no `scripts/parity` argv")
        invoked = set(self.heads) | (set(re.findall(r"'([a-z-]+)'", hook.group(1)))
                                     & set(cli.build_parser()[1]))
        named = set(_SPAN.findall(_paragraph("The build invokes")))
        self.assertEqual(sorted(named), sorted(invoked))


class TheProgressNote(unittest.TestCase):
    """Which commands write a progress line, against the handlers that call the writer.

    The writer had no caller at all, so the sentence described a behaviour nothing produced and
    ``-q`` suppressed nothing. Each named command now emits, and ``test_cli`` drives all three to
    say so; what is checked here is the other direction, which those cannot - that the sentence
    names the handlers that call the writer and no others, so a fourth caller cannot arrive
    undocumented and a name cannot outlive the call it describes.
    """

    def setUp(self):
        self.named = set(_SPAN.findall(_paragraph("**stdout carries the answer")))

    @staticmethod
    def _callers() -> set[str]:
        """The commands whose handler calls the progress writer, read off the module.

        The command is the handler's own name with its underscores read back as hyphens, which is
        the spelling the parser registers - ``_cmd_capture_normalize`` is ``capture-normalize``.
        """
        callers, handler = set(), None
        for line in read_text(Path(cli.__file__)).splitlines():
            if line.startswith("def "):
                named = re.match(r"def _cmd_(\w+)\(", line)
                handler = named.group(1).replace("_", "-") if named else None
            elif handler and "_progress(" in line:
                callers.add(handler)
        return callers

    def test_the_scan_finds_a_caller_at_all(self):
        """Otherwise the case below holds over two empty sets, which is the state it was filed in."""
        self.assertNotEqual(self._callers(), set())

    def test_the_sentence_names_the_handlers_that_call_the_writer(self):
        self.assertEqual(sorted(self.named), sorted(self._callers()))

    def test_every_command_it_names_is_one_the_parser_registers(self):
        self.assertEqual(sorted(self.named - set(cli.build_parser()[1])), [])


class TheOptionalDependencyNote(unittest.TestCase):
    """What a bare interpreter loses and what it keeps but cannot run, taken off the parser."""

    def setUp(self):
        self.sentence = _paragraph("`lab` is registered")

    def _registered(self, available: bool) -> set[str]:
        """The command names the parser registers, with the optional pair present or absent.

        :param available: whether Pillow and numpy import
        :return: the registered names
        """
        with mock.patch.object(pixels, "_load", return_value=(None, None) if available else None):
            return set(cli.build_parser()[1])

    def test_the_conditional_registration_is_the_one_the_note_names(self):
        lost = self._registered(True) - self._registered(False)
        self.assertEqual(sorted(lost), [OPTIONAL_COMMAND])

    def test_the_note_names_both_and_nothing_else(self):
        commands = set(cli.build_parser()[1])
        self.assertEqual(sorted(set(_SPAN.findall(self.sentence)) & commands),
                         sorted([OPTIONAL_COMMAND, OFFERED_WITHOUT_ITS_DEPENDENCIES]))

    def test_the_offered_command_is_registered_bare_and_refuses_at_its_first_png(self):
        """The claim end to end: registered without the pair, and a typed refusal rather than a crash.

        Driven against a real subject directory, because the refusal has to come from the command
        doing its work and not from it finding nothing to do. The two PNGs are empty - the dependency
        is required before the bytes are read, which is the whole point of requiring it there.
        """
        self.assertIn(OFFERED_WITHOUT_ITS_DEPENDENCIES, self._registered(False))
        with tempfile.TemporaryDirectory() as scratch:
            subject = Path(scratch) / "sweep" / "minecraft__cow"
            subject.mkdir(parents=True)
            for name in ("vanilla.png", "java.png"):
                (subject / name).write_bytes(b"")
            with mock.patch.object(pixels, "_load", return_value=None):
                code = cli.main([OFFERED_WITHOUT_ITS_DEPENDENCIES, "stats",
                                 "--source", str(subject.parent)])
        self.assertEqual(code, cli.MISSING_DEPENDENCY)

    def test_one_module_outside_pixels_needs_the_pair_to_do_its_work(self):
        """What makes `panel` the one: every other module asks whether the pair is there, or not at all.

        The distinction is the whole of "every other command is stdlib-only": ``cli`` imports
        ``pixels`` too, and what it asks it is ``available()``, which answers on a bare interpreter
        and is how the conditional registration above is decided.
        """
        needs = sorted(path.name for path in README.parent.glob("*.py")
                       if path.name != "pixels.py" and _NEEDS_THE_PAIR.search(read_text(path)))
        self.assertEqual(needs, [OFFERED_WITHOUT_ITS_DEPENDENCIES + ".py"])


if __name__ == "__main__":
    unittest.main()
