"""The parity toolkit - one writer, one store root, one id grammar.

Invoked as ``python parity/scripts/parity <command>``. See ``README.md`` for the command table
and the six exit codes.

The interpreter floor is checked here rather than at first use, so a too-old interpreter fails
with a sentence naming its own version instead of a SyntaxError or an AttributeError several
frames deep. ``datetime.UTC`` is the member that actually sets the floor: every provenance record
carries an ISO-8601 UTC timestamp and ``datetime.utcnow()`` is deprecated.
"""

from __future__ import annotations

import sys

VERSION = "1.0.0"

MIN_PYTHON = (3, 11)

if sys.version_info < MIN_PYTHON:
    _running = ".".join(str(part) for part in sys.version_info[:3])
    _wanted = ".".join(str(part) for part in MIN_PYTHON)
    sys.stderr.write(f"parity requires Python >= {_wanted} (running {_running})\n")
    raise SystemExit(5)

from parity.store import PRODUCTION, WORKING  # noqa: E402  - after the interpreter check, deliberately

__all__ = ["PRODUCTION", "VERSION", "WORKING"]
