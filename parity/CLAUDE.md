# asset-renderer parity

The store and the declarations that decide what it can see. Its own Gradle build, the smallest leaf
in the repo: five annotation types under `lib.minecraft.renderer.parity` importing nothing but
`java.lang.annotation`, and the parity toolkit's Python package beside them at `parity/scripts/parity/`.

**Running a gate is the `parity-gate` skill's job**, `.claude/skills/parity-gate/SKILL.md` - the task
sequence, the flags, the decision table and the refusal codes live there, and the runbooks in its
`references/procedures.md`. This file is the mechanics underneath: how a declaration is read, how
reach is resolved, what the roster and the index owe each other, and which edits are store state
without looking like it.

## The build

Every build that writes a `@Parity` declaration includes this one and takes it **`compileOnly`** -
retention is `SOURCE`, so javac drops the descriptor before it writes a class file and no published
artifact carries one. The renderer's test tree is the exception and takes it outright, because a
roster guard reads `Subject.values()` at run time.

The toolkit runs as `python parity/scripts/parity <command>` and is documented in its own
`README.md`. `paritySelfTest` is its suite, and the renderer's `check` schedules it, because a parity
task is otherwise the only thing that pulls it in.

## Declaring reach

**A rule's trigger list is two halves and only one of them is authored.** A rule carrying a
`claim_key` derives its triggers from the `@Parity` declarations naming that slug, and `trigger_paths`
is the sorted union of that with `authored_paths` - so moving a claiming package moves the rule, and
hand-editing a generated array states a reach no declaration makes. `python parity/scripts/parity
triggers` regenerates; `--check` answers what would move. The authored half is what no annotation can
reach: a `.kts`, a `.py`, a resource file, a path in a build with no Java at all.

A declaration is read from **source, never bytecode** - retention is `SOURCE`, and javac still writes
a synthetic `package-info.class` for an annotated package with the annotation dropped, so a bytecode
reader would find zero annotations and conclude the package declares nothing. Six source roots are
scanned: the renderer's, this build's, both of `tooling`'s, `client`'s and the harness's client root.
**`Scope.PACKAGE` is legal on the renderer's library root alone** and refused everywhere else - a leaf
package answers for its tree, so a package added below one inherits what its parent claims.

## The reference graph

**A rule carrying `derived` authors no `sees`; the reference graph answers it per FILE.** Five do -
`engine-renders`, `option-surface`, `asset-layer`, `tensor-math`, `face-vocabulary` - which is why a
pose kit plans five artifacts where the engine it sits in plans seventeen, and why moving a type
between two derived regions carries its reach with it. The graph is `parity/reach.json`, derived from
the **compiled constant pool** and committed: an import is not evidence, the javadoc convention
requiring a `{@link}` target be imported, and a same-package call needs no import at all. It is
regenerated with `python parity/scripts/parity reach build` over a compiled tree and held to the tree
by `parityReachCheck` on `check`; `plan` reads the committed file, so a stale graph is a loud
difference rather than a quiet mis-schedule, and a `.java` path it has never heard of is a refusal.

A rule keeps its `blind` list either way. That says what an artifact OBSERVES, which no reference
graph can answer: the dump reaches a face and a vector because both are serialised, and perturbing
either leaves every dump file byte-identical - so `face-vocabulary` and `tensor-math` subtract it as
`engine-renders` always has. A derived claim's demotion subtracts from its OWN selection, which is
what makes it legal with no sibling claim on the path.

## Cutting a seam

**A wiring seam is cut by what it is.** `@Parity(ignored = true)` stops reach composing THROUGH a
type, and an INTERFACE is cut by its declaration alone - its members' descriptors name every type
they mention whether or not anything calls them, which is the collapse - while what its DEFAULT
BODIES call is kept, those having no implementor to carry a change. A CLASS is cut whole: every
reference it holds is one it makes. Measured - cutting the concrete pipeline context by declaration
instead takes the tree from 29 engine-wide types to 151.

**A library type that reaches nothing declares what it reaches.** Two different things answer the
empty set - a renderer this store holds no artifact for, and a type reached across a seam or built by
a service loader out of a file no constant pool mentions - and `reach check` refuses one that says
neither. Nineteen carry `@Parity(subject = {...})` with no claim, which is its own declaration shape:
a subject written beside a claim decorates that claim, so only the claimless form answers for a type,
and reading a claim's decoration as one would explain an orphan nobody had looked at.

## The roster and the index

**Coining an artifact is an edit to the roster and to the index, and the index row goes in first.** A
registration in `ParityArtifacts.ALL` owes an `index.json` row carrying the `determinism_floor` -
`parityCapture` refuses without one, because how many runs prove the thing reproducible is otherwise
unanswerable - and that row carries **no `file` member** until a promotion writes the file it would
name. A `file` naming a path nothing has written yet fails `ParityIndexTest`'s citation walk instead,
so the two spellings of "declared but not yet baselined" are not interchangeable.

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

## Store state that does not look like store state

**A test class's own name and path are store state.** `index.json` homes fourteen rows at a
`sources[*].test_class` or `external[*].home` FQN and `ParityIndexTest` resolves each against the
source tree; `blindness.json` announces ten test paths verbatim as `B38` trigger paths and
`BlindnessMapTest` asserts every announced trigger path is a tracked path. So renaming or moving one of
those files is a promote, not a rename, and the cheapest way to find out is to grep both files for the
class before touching it. Five files go further and pin a LINE NUMBER: `ParityIndexTest`'s
`everyLinesCitationBracketsItsRoster` requires a cited range to open on the line carrying its anchor, so
in `HumanoidArmorRosterTest`, `HumanoidPartCropTest`, `TestArmorParityVanilla`, `TestGlintParityVanilla`
and `TestPlayerParityVanilla` every edit above the anchor - a javadoc line included - must be
line-count neutral. Rewriting a store row to satisfy a naming rule falsifies the record the rule exists
to keep; the name is the thing that gives way.

**The repository's own rules are store state too.** `blindness.json`'s `source` column cites headings
of the root `CLAUDE.md` by name, `BlindnessMapTest` holds every citation to a heading that file still
carries, and `gradle/parity.gradle.kts` declares that file as a task input for the same reason. The
same test asserts the root file names no artifact id: a statement of what a gate sees has to name the
gate, so an id there grows a second home for what this map is supposed to be the only home of.

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

[asset-renderer/CLAUDE.md]: ../CLAUDE.md
