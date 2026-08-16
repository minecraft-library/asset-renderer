"""The whole optional-dependency surface.

Nothing else in the package may ``import numpy`` or ``from PIL import ...``, and ``test_norm``
asserts it by reading the package's own source - so "the gate path is stdlib-only" is a checked
claim rather than an inspection. That includes ``lab/``, which is inside the package: it reaches
numpy and Pillow through :func:`numpy_module` and :func:`image_module` here.

Why Pillow rather than a stdlib PNG decoder: the corpus already contains 121 commands importing
``struct`` and 5 importing ``zlib`` for hand-rolled IDAT parsing, and writing a fourth partial PNG
decoder into the gate tool is exactly the failure this package exists to end. Why not a hard
dependency: that would put a ``pip install`` between a developer and a commit gate. Optional gets
both.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from parity.norm import MissingDependency

INSTALL = "python -m pip install pillow numpy"

_probe: tuple[Any, Any] | None = None
_probed = False


def _load() -> tuple[Any, Any] | None:
    """One guarded import, cached, so ``available()`` is cheap to ask repeatedly."""
    global _probe, _probed
    if _probed:
        return _probe
    _probed = True
    try:
        import numpy
        from PIL import Image
    except ImportError:
        _probe = None
    else:
        _probe = (numpy, Image)
    return _probe


def available() -> bool:
    return _load() is not None


def missing() -> list[str]:
    """Which of the two is absent, so the message names it rather than saying 'a dependency'."""
    if available():
        return []
    absent = []
    for name in ("numpy", "PIL"):
        try:
            __import__(name)
        except ImportError:
            absent.append("pillow" if name == "PIL" else name)
    return absent


def require(command: str = "this command") -> tuple[Any, Any]:
    found = _load()
    if found is None:
        raise MissingDependency(f"{command} needs Pillow and numpy "
                                f"({', '.join(missing())} missing): {INSTALL}")
    return found


def numpy_module() -> Any:
    return require()[0]


def image_module() -> Any:
    return require()[1]


def load_rgba(path: Path) -> Any:
    """An ``int32`` HxWx4 array, so channel arithmetic never wraps a uint8."""
    numpy, image = require("reading a PNG")
    if not path.is_file():
        from parity.norm import MissingInput
        raise MissingInput(f"no image at {path}")
    with image.open(path) as handle:
        return numpy.asarray(handle.convert("RGBA")).astype(numpy.int32)

