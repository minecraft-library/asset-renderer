"""The toolkit's own suite, run by ``python scripts/parity selftest``.

``unittest`` rather than pytest on purpose: the gate tool's suite must run on a bare interpreter,
because a developer who cannot run the tests will run the tool anyway. Fast and offline - no Gradle,
no rendering, no network. Every test that wants a corpus takes it from ``data/`` first and only
*additionally* checks the live tree when it happens to be present.
"""
