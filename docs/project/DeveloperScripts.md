# Developer scripts

Load when running, adding or moving a developer script. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

`parity/scripts/parity/` is the parity toolkit, run as `python parity/scripts/parity <command>` and
documented in its own `README.md`. Every other developer script lives in
`src/test/resources/scripts/`, which `processTestResources` excludes whole, so one is neither a
fixture nor a shipped resource. There are three:

- `euler_reference_svg.py` regenerates the SVG in `EulerRotation`'s javadoc.
- `chrome_slicer_corpus.py` draws the chrome fixtures and `manifest.json` that
  `ChromeSlicerCorpusTest` reads; `--check` compares them with the tracked corpus and writes
  nothing.
- `mutate_guards.py` applies each mutant in a spec file, runs the tests that should catch it and
  restores the file, which is how a guard is shown to go red.

Each is named by a `no_reach` entry of its own in `blindness.json` and listed in
`BlindnessMapTest.ABSORBABLE_BY_AN_EXCUSE`, so a fourth owes both.
