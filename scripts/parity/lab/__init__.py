"""The ``[PX]`` fragment family - evidence tooling, never a gate.

It needs an instrumented build, it is not reproducible by re-running a sweep, and its outputs are
**probes** rather than baselines. Keeping it in the package rather than in a scratchpad is the whole
point: every one of these capabilities was lost to ``%TEMP%`` at least once, and two of them were
lost twice.

It is a subpackage rather than a sibling directory so it can import ``parity.norm`` and
``parity.ids`` - and therefore write LF and speak one id grammar - while staying off the gate path:
``cli`` registers the ``lab`` group only when ``pixels.available()``, and nothing here can produce a
stored artifact.
"""
