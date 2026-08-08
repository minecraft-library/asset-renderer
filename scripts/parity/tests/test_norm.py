"""The sole-writer mechanism, and the round-trips that make LF a property rather than a habit."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from parity import norm

PKG = Path(__file__).resolve().parents[1]

#: A module that reaches any of these has gone round the writer. Assembled from fragments so this
#: file does not match its own scan when someone widens the sweep to include tests.
FORBIDDEN = ("open" + "(", ".write_text" + "(", ".write_bytes" + "(",
             "json.dump" + "(", "json.dumps" + "(", "csv.writer" + "(")

PRINT = "print" + "("


class SoleWriter(unittest.TestCase):
    """Discipline is not a mechanism, so this reads the package's own source."""

    def _modules(self):
        for path in PKG.rglob("*.py"):
            if "tests" in path.parts:
                continue
            yield path

    def test_norm_is_the_only_writer(self):
        for path in self._modules():
            if path.name == "norm.py":
                continue  # the writer itself, necessarily
            source = norm.read_text(path)
            for token in FORBIDDEN:
                # pixels.py is exempt for `open(` ALONE, and only because Pillow's decoder is
                # spelled `Image.open`. It is a read; the exemption is as narrow as cli.py's for
                # print, and the next test proves pixels still writes through norm's one door.
                if path.name == "pixels.py" and token == "open" + "(":
                    continue
                self.assertNotIn(token, source, f"{path.name} bypasses norm: {token}")

    def test_pixels_writes_nothing_at_all(self):
        """The exemption above must not become a general one.

        The module reads PNGs and answers whether the optional pair is importable; the one binary
        write in the package is ``norm.write_bytes_raw`` and ``lab.crop`` calls it directly. So the
        `open(` exemption covers a decoder and covers no write, and a write appearing here is a
        second binary door rather than a use of the named one.
        """
        source = norm.read_text(PKG / "pixels.py")
        for token in (".write_text" + "(", ".write_bytes" + "(", "json.dump" + "(", ".save" + "("):
            self.assertNotIn(token, source, f"pixels.py bypasses norm: {token}")

    def test_only_cli_prints(self):
        """cli.py is exempt for print alone - stdout framing is its job - and for nothing else."""
        for path in self._modules():
            if path.name == "cli.py":
                continue
            source = norm.read_text(path)
            self.assertNotIn(PRINT, source, f"{path.name} prints; only cli.py may")

    def test_optional_dependencies_are_confined_to_pixels(self):
        """The gate path is provably stdlib by construction rather than by inspection.

        ``doctor`` still reports both, but it probes by name through ``__import__`` rather than
        importing them, so the boundary holds without costing the report.
        """
        for path in self._modules():
            if path.name == "pixels.py":
                continue
            source = norm.read_text(path)
            for token in ("import numpy", "from PIL", "import PIL"):
                self.assertNotIn(token, source, f"{path.name} imports an optional dependency")


class LineEndings(unittest.TestCase):

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def test_write_text_folds_crlf(self):
        target = norm.write_text(self.dir / "a.txt", "one\r\ntwo\rthree\n")
        raw = target.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertEqual(raw, b"one\ntwo\nthree\n")

    def test_exactly_one_trailing_newline(self):
        for body in ("x", "x\n", "x\n\n\n"):
            target = norm.write_text(self.dir / "b.txt", body)
            self.assertEqual(target.read_bytes(), b"x\n")

    def test_read_text_strips_bom_and_folds(self):
        (self.dir / "c.txt").write_bytes("﻿one\r\ntwo\n".encode("utf-8"))
        self.assertEqual(norm.read_text(self.dir / "c.txt"), "one\ntwo\n")

    def test_round_trip(self):
        for body in ("a\r\nb", "a\nb\n", ""):
            target = norm.write_text(self.dir / "d.txt", body)
            self.assertEqual(norm.read_text(target), body.replace("\r\n", "\n").rstrip("\n") + "\n")

    def test_read_lines_drops_the_trailing_empty(self):
        norm.write_text(self.dir / "e.txt", "one\ntwo\n")
        self.assertEqual(norm.read_lines(self.dir / "e.txt"), ["one", "two"])


class CanonicalJson(unittest.TestCase):

    def test_keys_sort_recursively(self):
        text = norm.canonical_json({"b": 1, "a": {"d": 2, "c": 3}})
        self.assertLess(text.index('"a"'), text.index('"b"'))
        self.assertLess(text.index('"c"'), text.index('"d"'))

    def test_arrays_are_never_reordered(self):
        """An array means the order is semantic."""
        payload = {"bones": ["head", "body", "arm"]}
        self.assertIn('"head",\n    "body",\n    "arm"', norm.canonical_json(payload))

    def test_idempotent(self):
        import json
        once = norm.canonical_json({"b": [3, 1], "a": 2.5})
        self.assertEqual(once, norm.canonical_json(json.loads(once)))

    def test_float_precision_survives(self):
        """Rounding in the writer would destroy a 16-float pose pin; the metric form is fixed()."""
        text = norm.canonical_json({"pose": [1.2345678901234567, 60.0047]})
        self.assertIn("1.2345678901234567", text)
        self.assertIn("60.0047", text)

    def test_non_finite_refuses(self):
        """A failed subject is an explicit status field, never an out-of-band magic value."""
        with self.assertRaises(ValueError):
            norm.canonical_json({"delta": float("inf")})


class Numbers(unittest.TestCase):

    def test_fixed_is_locale_free(self):
        self.assertEqual(norm.fixed(60.00471), "60.0047")
        self.assertEqual(norm.fixed(0.5), "0.5000")
        self.assertNotIn(",", norm.fixed(1234.5))

    def test_fsum_is_order_independent(self):
        import random
        values = [0.1] * 10 + [1e16, -1e16]
        shuffled = list(values)
        random.Random(0).shuffle(shuffled)
        self.assertEqual(norm.fsum(values), norm.fsum(shuffled))


class Paths(unittest.TestCase):

    def test_posix_forces_forward_slashes(self):
        self.assertEqual(norm.posix(Path("a") / "b" / "c.png"), "a/b/c.png")

    def test_posix_relative_to_root(self):
        root = Path("x") / "y"
        self.assertEqual(norm.posix(root / "z" / "w.png", root), "z/w.png")


class Digests(unittest.TestCase):

    def test_sha256_text_is_host_independent(self):
        self.assertEqual(norm.sha256_text("a\r\nb"), norm.sha256_text("a\nb\n"))

    def test_sha256_file_matches_bytes(self):
        target = Path(tempfile.mkdtemp()) / "f.txt"
        norm.write_text(target, "hello")
        self.assertEqual(norm.sha256_file(target), norm.sha256_bytes(b"hello\n"))


if __name__ == "__main__":
    unittest.main()
