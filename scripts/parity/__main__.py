"""The four-line bootstrap that makes ``python scripts/parity <command>`` the one invocation form.

Running ``python <dir>`` executes ``<dir>/__main__.py`` with ``<dir>`` itself on ``sys.path``, which
is the wrong entry for a package - sibling modules would import as top-level and every
``from parity.x import y`` would fail. Inserting the **parent** fixes it, needs nothing configured,
and leaves the ``-m`` form resolvable for anyone who already has ``scripts/`` on ``PYTHONPATH``
(the insert is then a harmless duplicate). ``-m parity`` is still not a supported spelling.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from parity.cli import main  # noqa: E402  - after the bootstrap, necessarily

if __name__ == "__main__":
    sys.exit(main())
