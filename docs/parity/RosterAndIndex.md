# The roster and the index

Load when coining or retiring an artifact, editing `index.json`, or reading an UNPRODUCED row or a
baseline's provenance. Indexed from [`parity/CLAUDE.md`](../../parity/CLAUDE.md).

**Coining an artifact is an edit to the roster and to the index, and the index row goes in first.** A
registration in `ParityArtifacts.ALL` owes an `index.json` row carrying the `determinism_floor` -
`parityCapture` refuses without one, because how many runs prove the thing reproducible is otherwise
unanswerable - and that row carries **no `file` member** until a promotion writes the file it would
name. A `file` naming a path nothing has written yet fails `ParityIndexTest`'s citation walk instead,
so the two spellings of "declared but not yet baselined" are not interchangeable.

**Coining a store artifact is an edit to `reach.py` as well.** Every artifact the index's
`artifacts` map holds - the one map a plan names - is either rooted in `ROOTS` or listed in
`UNROOTED` with why no class in this tree can root it, and `paritySelfTest` refuses one that is in
neither. A root is the class that writes the artifact, or, where a build beside this one writes it,
the type here that decides what is written: `manifest.references` roots at `StyleResolution`, the
resolution the schedule roster is written through, while the harness's sources, the two entity tables
and the roster writer reach it through the authored rules B29 and B65. A pointer, source or external
row has no store file and no producer, so it owes neither. A derived rule plans off the graph alone,
and the graph answers an artifact only through its root, so an artifact with none is left out of
every derived plan and the plan does not say so. A row one class writes under a property roots at
that class like any other: `sweep.entity-walk` roots where `sweep.entity-animation` does.

`index.json`'s `sources`, `external` and `pointers` sections are a hand-maintained pointer table that
survives a promotion, so they are edited by hand where the baselined values never are.

## A failed producer

**A failed producer is a result the capture records, not a run it ends.** A capture is driven with
`--continue` by decision - `settings.gradle.kts` sets it for any invocation naming a `parityCapture`
task, because nowhere later works - and a capture step is a finalizer, so it runs after a producer
that failed. It is TOLD which of its producers failed and reads their output anyway: a self-captured
row's writer writes before it asserts, so a suite that went red over the very value being re-based
has still produced a capturable file. Only where there was nothing to read does the row become
**UNPRODUCED**, which `parityCompare` reports and fails on unless `parityExpect -Punproduced`
registered it. So a red suite gates: what it cannot do is let a row whose producer wrote nothing pass
unnoticed. The index row is the first edit of a new artifact rather than the last for the same
reason - a row the store does not carry has no floor, and `parityCapture` refuses without one.

## Provenance reasons

A `provenance.reason` **already in the store** is what exempts a frozen measurement from the writing
rules the repo otherwise binds, and more than one is: they name the capture a baseline was diffed
against, the question that widened it, or the phase that moved the values, in the working note's own
spelling. A reason is a frozen measurement `parityPromote` alone writes, and `ParityIndexTest`
re-derives every index row's digest from the file it names, so a reason moves by re-promoting and
never by an edit - rewriting one to satisfy a writing rule would falsify the record the rule exists to
keep. The next one written still follows the rule.

One repository means one sha, so provenance records `asset_sha` alone - a second would be equal to it
by construction. `provenance.reference_manifest_digest` derives from the reference tree rather than
from a captured file, so re-deriving it and comparing against any sweep's provenance is the cheap
check that a number and its ground truth still name one reference set.
