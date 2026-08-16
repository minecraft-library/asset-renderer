# Procedures

The flows that are not one task, plus the toolkit's exit codes. Hand-written: these are recipes with
no JSON source. Loaded on refusals R3 and R6, and for any A/B.

## The toolkit's six exit codes

The single rendering of this table. `parityExpect`, `parityCompare` and `parityPromote` fail the
Gradle build on any non-zero code, so this is what a red build means.

| Code | Name | Means |
|---|---|---|
| 0 | OK | The command answered. For `compare`, that means the diff equalled the expected-diff. |
| 1 | DIFFERENCES | A comparison found movers the expected-diff did not register. **This is the only code that means "the gate said no".** |
| 2 | USAGE | argparse rejected the argv. A malformed invocation, never a verdict about the code. |
| 3 | MISSING_INPUT | Something the command had to read was absent or unreadable - a producer directory, a declared manifest member, a baseline under `--bootstrap`. |
| 4 | MISSING_DEPENDENCY | An optional Python package a `lab/` probe needs. Never reachable from the Gradle tasks. |
| 5 | REFUSED | The command declined to answer: an uncovered path (refusal R1), a side of a comparison carrying no provenance, an expected-diff registration naming no row or no value, a clear and a registration in one `expect`, a promotion with no reason, a promotion below a determinism floor, a working root outside `cache/`. |

The separation of 1 from 3 and 5 is the point, and the corpus conflated them twice. **Do not read a
3 or a 5 as "the gate failed"** - it means the gate could not run, which needs a different fix.

Four of the causes listed on that row never reach you as a 5 through the task that would raise one,
because the Gradle side refuses them first and a refusal there is an ordinary configuration failure -
**exit 1, no toolkit process, no code from this table**. `parityExpect` rejects `-PexpectEmpty`
given beside a registration, and rejects a registration missing any of `-Partifact -Pkey -Pto
-Preason`; `parityPromote` rejects an absent `-Preason`. Those three are raised once the task graph
is resolved and before any task of it executes, so a malformed invocation costs no producer run. The
fourth is a working root outside `cache/`, which each of the five tasks rejects as it is configured -
earlier still, and on any invocation that so much as realizes one of them to read its description.
The toolkit raises 5 for all four, which is what a hand `python parity/scripts/parity ...` call meets.

`plan --gate-exit` answers on a different scale and is not part of this table: `0` nothing sees the
change, `10` seen and ungated, `20` already gated for this tree. All three mean the command
succeeded. It is opt-in because `parityPlan` is a Gradle `Exec` and must always exit 0. A changed
path no rule covers still refuses with `5` from the table above, before any of the three is reached,
and the pre-commit hook prompts on that as well as on `10` - the map admitting it cannot answer is
the one case the hook exists to notice.

**The verdict `20` is read from is global over the roots, not per-root.** `parityCompare` writes
`_run/last-verdict.json` into whichever root it was given, and the gate reads the newest one under
`cache/parity/*` plus the root it was itself handed, by mtime and then by the greater path where two
land on one stamp. A verdict names a tree state and what
a compare covered, and neither is a property of the root a capture went into - read out of the
default root alone, the promotion below left its verdict where nothing looked, its compare carrying
`-PparityRoot=cache/parity/b` as the determinism pre-flight's does. Newest and not any-that-passes:
a red compare taken after a green one is the state to report.

**Every compare counts, a `-Pbase=` one included.** Both A/B flows below compare this tree against a
capture rather than against the store, and both are gatings - for a generator refactor the
tooling-regen A/B is the only gate there is. The one shape that slips through with them is the
determinism pre-flight: its two roots are captures of *one* tree, so it establishes that a producer
repeats itself and nothing about whether this change moved anything. Run on the tree being committed
and covering the whole plan, it reads as a gating here. Nothing a compare records separates the two
- a capture's provenance names the commit and whether the tree was dirty, never which edit was in it
- so **do not treat the pre-flight as the gate**: it is a precondition, taken before the work.

## The stash A/B

For a change whose effect you want measured directly rather than against the stored baseline - a
suspected byte-neutral refactor, or pricing a deliberate move before registering it.

```bash
# 1. the AFTER side is the working tree; capture the BEFORE side into a redirected root
git stash push -- src
./gradlew parityCapture -Partifacts=<the plan's PLAN set> -PparityRoot=cache/parity/base
git stash pop

# 2. the after side, into the default root
./gradlew parityCapture -Partifacts=<the same set>

# 3. compare the two captures rather than either against the store
./gradlew parityCompare -Pbase=cache/parity/base
```

Four things this gets right that a hand-rolled version does not:

- **The before-side is a redirected root, never a second slot.** A root is a path, guarded to be
  relative and under `cache/`, so it always dies with a `cache/` clean and can never be committed.
- **`git stash push -- src` and not a bare stash.** A bare stash also removes the toolkit and the
  store, so the before-side would be captured by different code than the after-side.
- **Check `git status` first: `git stash` on a clean tree is a SILENT no-op.** It stashes nothing,
  the "before" run measures the change you meant to remove, and `before == after` then reads as
  everything being fine. This is the failure mode that looks most like success in the whole
  procedure.
- **The before-side can never be promoted.** A promotion is only ever from the root the compare it
  requires was run against. An A/B before-side is an operand.

For a change already committed, revert just the touched files instead of stashing, and prove the
restore before believing anything:

```bash
git checkout HEAD~1 -- <the files the commit touched>
./gradlew parityCapture -Partifacts=<the PLAN set> -PparityRoot=cache/parity/base
git checkout HEAD -- <the same files>
git status --short          # MUST be empty before you believe the numbers
```

**A report sitting in `cache/` is output, not a baseline.** `cache/visual/*/parity-report.tsv` is
whatever the last run wrote - it can be days old and from an unrelated commit. Copying one and
calling the copy a "before" measures nothing, and it has cost real time: a two-day-stale item report
made seven glint rows appear to move when measured properly **none of them had**. A before/after
claim needs a "before" produced by code you actually reverted, or a stored baseline whose provenance
you can name.

## The tooling-regen A/B

A generator refactor is the one change class where every sweep is structurally blind (rule B13): the
sweeps read the **shipped** tables, and a refactor does not regenerate them. The only gate is
re-running the flow and comparing emitted bytes.

```bash
# 1. capture the tables at the CLEAN tree, before the first edit
./gradlew parityCapture -Partifacts=manifest.tooling-tables -PparityRoot=cache/parity/base
git restore ':(glob)src/main/resources/lib/minecraft/renderer/*.json'

# 2. make the change, re-run the flows, capture again
./gradlew parityCapture -Partifacts=manifest.tooling-tables

# 3. compare captures, then restore before doing anything else
./gradlew parityCompare -Pbase=cache/parity/base
git restore ':(glob)src/main/resources/lib/minecraft/renderer/*.json'
```

**Compare against a capture taken at the clean tree, not `git diff`.** All eight flows reproduce
their shipped bytes, so a `git diff` is *nearly* the same answer - and the reason to take the capture
anyway is that a run dirties its own tracked table, so the diff conflates "my change moved this byte"
with "this byte was going to move regardless". The capture separates them; nothing else does.

**Diff the flow's diagnostics log as well as its JSON.** A byte-identical table is not the same claim
as an unchanged run: the entity flow once moved an `INFO` line from position 9 to 6 with every
emitted table byte-identical. `manifest.tooling-tables` carries a digest per flow beside its file
digests for exactly this, so the comparison above already covers it.

## The reference refresh

The reference tree is ground truth. It is a **precondition**, run before the work, never a gate on
it - the gate only checks its currency (refusal R6).

```bash
./gradlew renderVanillaAllReferences         # every sweep in ONE client boot, ~152 s
./gradlew parityCapture -Partifacts=manifest.references
./gradlew parityCompare -Partifacts=manifest.references
```

**Always the whole tree, never a narrow task.** `renderVanillaReferences` runs blocks, items,
entities and players and leaves `glint/` and `armor/` holding ground truth recorded by the old code -
which has happened twice. `renderVanillaAllReferences` pays the client boot once and is *cheaper*
than the three narrower tasks run separately.

**A reference that moves on a re-render with your change stashed was stale, not moved.** That is the
check that separates the two, and two consecutive runs agreeing byte for byte is what rules out
flapping.

## The determinism pre-flight

Required once per producer before a digest comparison is admissible at all (refusal R5). A producer
that does not repeat itself cannot be compared to anything.

```bash
./gradlew parityCapture -Partifacts=<id> -PparityRoot=cache/parity/a
./gradlew parityCapture -Partifacts=<id> -Pruns=2 -PparityRoot=cache/parity/b
./gradlew parityCompare -PparityRoot=cache/parity/b -Pbase=cache/parity/a
```

**`-Pruns=N` is recorded, never measured.** It stamps the count into provenance; the two-root compare
above is what actually establishes it. Do not build a loop into `parityCapture` - Gradle runs a task
at most once per invocation, so it would have to fork nested builds.

Two runs for a render tree. **Five** for anything exposed to the `Map.copyOf` / `Set.copyOf`
class-init salt, because that flap is intermittent and an oracle can pass twice and fail the third
time.

## The first promotion of an artifact

```bash
# on a CLEAN tree, at the commit whose code produced the capture
./gradlew parityCompare -PparityRoot=cache/parity/b -Pbootstrap=true
./gradlew parityPromote -PparityRoot=cache/parity/b -Partifacts=<id> -Pbootstrap=true \
  -Pclass=<neutral|shaped|moving> -Preason="<what this value is and what proves it>"
./gradlew test --tests "*ParityViewsTest" -Dasset.parity.regenerateViews=true --rerun
./gradlew test --tests "*ParityReferencesTest" -Dasset.parity.regenerateViews=true --rerun
```

- **Clean tree, or the baseline is not re-derivable from any commit.** A capture that gets promoted
  must run committed code; a promoting phase is therefore two commits, migration then promotion.
  `parityPromote` refuses a capture whose provenance does not record a clean tree, and
  `-PallowDirty=true` is the only way past it - it writes the exception into the promoted value.
- **`-Partifacts` is not optional in practice.** A capture root usually holds more than the artifact
  you meant, because a producer finalizes its own capture step wherever it runs. Here it carries a
  second job: `-Pbootstrap=true` is what exempts a promotion from needing a compare of the capture
  it applies, and the flag is per-invocation, so anything else in the root would be written on the
  same exemption.
- **Regenerate both view sets afterwards.** A promotion rewrites `index.json`, and two tracked
  markdown files render from it. `--rerun` is required or the task reports success and writes
  nothing.

## Compiling the harness

```bash
./gradlew harnessClasses          # both source sets, through the harness's own wrapper
```

The harness is a separate Gradle build, so `./gradlew test` and `paritySelfTest` both pass over a
harness that does not compile. This is the cheap gate on that; the expensive one is a client boot.
`./gradlew check` depends on it, so a verification run already covers it - run it on its own before
a `renderVanilla*` task, which does not.

`clientClasses` is the load-bearing half and the task runs both: Loom's
`splitEnvironmentSourceSets()` puts every harness java file in the **client** source set, so a bare
`classes` reports `:compileJava NO-SOURCE` and succeeds over sources it never read.

## Exercising the pre-commit hook

The hook has no automated test in this repo. Its cases are exercised by feeding it a `PreToolUse`
payload on stdin; it prints the `ask` JSON or nothing, and always exits 0.

```bash
# <repo> is this repo's own path written with FORWARD slashes - W:/Workspace/.../asset-renderer
printf '{"tool_name":"Bash","cwd":"<repo>","tool_input":{"command":"git commit -m x"}}' \
  | node .claude/hooks/parity-gate-precommit.js
printf '{"tool_name":"Bash","tool_input":{"command":"git add -A"}}' \
  | node .claude/hooks/parity-gate-precommit.js
```

The second is the negative control: it must be silent whatever the tree, so a prompt on the first is
attributable to the commit rather than to a hook that answers every payload. It cannot rescue the
other direction - two silences are also what a crashed hook prints. **Silence is the failure mode as
well as a verdict**, so the run worth trusting is one where the first prompts; take it on a tree that
reaches something, and run both after touching the hook.

**Forward slashes in `cwd`, and it is not a style preference.** `printf` reads the `\a` of a Windows
backslash path as BEL and single backslashes are illegal JSON escapes either way, so `JSON.parse`
throws and the hook returns silently - the same observable as a working hook on a clean tree.
Omitting the field, as the second line does, also works: the hook defaults it to its own repo.

`tool_name` decides which payloads the hook answers at all, and the two it answers are `Bash` and
`PowerShell` - the same pair the matcher in `settings.json` registers. A payload naming neither is
dropped; one naming no tool, as a hand-written fixture may, is decided on its command alone.

Which of the four answers comes back is a function of the **tree**, not of the payload, so drive
each by arranging the tree and re-running the first block:

| Tree | Hook |
|---|---|
| Nothing in the change set reaches a stored artifact | silence |
| Artifacts see it and no compare verdict covers this tree | `ask`, naming up to three artifacts and counting the rest |
| A passing `parityCompare` against the store already covered this exact tree | silence |
| A changed path matches no rule and no `no_reach` glob | `ask`, quoting the refusal and the paths |

The last one is reached by creating an untracked file no glob covers - `touch zz-probe.tmp` - and
deleted afterwards. Silence is also every failure mode: no interpreter, a bogus interpreter, garbage
on stdin, a timeout, a cwd outside the repo, and a command that merely mentions the words.

The hook runs its child with `--root cache/parity/hook`, so nothing it does touches the plan
`parityCapture` defaults to. Two limits are worth knowing rather than discovering: it decides a
segment by tokens and the first must be git, so `pwsh -c "git commit ..."` escapes it; and
`git commit` typed into an interactive shell it never sees escapes it too.
