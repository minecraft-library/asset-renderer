# Procedures

The flows that are not one task, plus the toolkit's exit codes. Hand-written: these are recipes with
no JSON source. Loaded on refusals R3 and R6, and for any A/B.

## The toolkit's six exit codes

The single rendering of this table. `parityCompare` and `parityPromote` fail the Gradle build on any
non-zero code, so this is what a red build means.

| Code | Name | Means |
|---|---|---|
| 0 | OK | The command answered. For `compare`, that means the diff equalled the expected-diff. |
| 1 | DIFFERENCES | A comparison found movers the expected-diff did not register. **This is the only code that means "the gate said no".** |
| 2 | USAGE | argparse rejected the argv. A malformed invocation, never a verdict about the code. |
| 3 | MISSING_INPUT | Something the command had to read was absent or unreadable - a producer directory, a declared manifest member, a baseline under `--bootstrap`. |
| 4 | MISSING_DEPENDENCY | An optional Python package a `lab/` probe needs. Never reachable from the four Gradle tasks. |
| 5 | REFUSED | The command declined to answer: an uncovered path (R1), a promotion with no reason, a promotion below a determinism floor, a working root outside `cache/`. |

The separation of 1 from 3 and 5 is the point, and the corpus conflated them twice. **Do not read a
3 or a 5 as "the gate failed"** - it means the gate could not run, which needs a different fix.

`plan --gate-exit` answers on a different scale and is not part of this table: `0` nothing sees the
change, `10` seen and ungated, `20` already gated for this tree. All three mean the command
succeeded. It is opt-in because `parityPlan` is a Gradle `Exec` and must always exit 0.

## The stash A/B

For a change whose effect you want measured directly rather than against the stored baseline - a
suspected byte-neutral refactor, or pricing a deliberate move before registering it.

```bash
# 1. the AFTER side is the working tree; capture the BEFORE side into a redirected root
git stash push -- src
./gradlew parityCapture -Partifacts=<the plan's SEES set> -PparityRoot=cache/parity/base
git stash pop

# 2. the after side, into the default root
./gradlew parityCapture -Partifacts=<the same set>

# 3. compare the two captures rather than either against the store
./gradlew parityCompare -Pbase=cache/parity/base
```

Three things this gets right that a hand-rolled version does not:

- **The before-side is a redirected root, never a second slot.** A root is a path, guarded to be
  relative and under `cache/`, so it always dies with a `cache/` clean and can never be committed.
- **`git stash push -- src` and not a bare stash.** A bare stash also removes the toolkit and the
  store, so the before-side would be captured by different code than the after-side.
- **The before-side can never be promoted.** A promotion is only ever from the root the compare it
  requires was run against. An A/B before-side is an operand.

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
- **`-Partifacts` is not optional in practice.** A capture root usually holds more than the artifact
  you meant, because a producer finalizes its own capture step wherever it runs.
- **Regenerate both view sets afterwards.** A promotion rewrites `index.json`, and two tracked
  markdown files render from it. `--rerun` is required or the task reports success and writes
  nothing.

## Exercising the pre-commit hook

The hook has no automated test in this repo. Its cases are exercised by feeding it a `PreToolUse`
payload on stdin; it prints the `ask` JSON or nothing, and always exits 0.

```bash
printf '{"tool_name":"Bash","cwd":"<repo>","tool_input":{"command":"git commit -m x"}}' \
  | node .claude/hooks/parity-gate-precommit.js
```

Expected: `ask` for `git commit`, `git -C . commit` and `git add -A && git commit`; silence for
`git add`, for a cwd outside the repo, for a command that merely mentions the words, for a tree whose
SEES set is empty, and for every failure mode - no interpreter, a bogus interpreter, garbage on
stdin, a timeout. **Silence is the failure mode as well as a verdict**, so a hook that has quietly
stopped working looks exactly like a clean tree; re-run the block above after touching it.
