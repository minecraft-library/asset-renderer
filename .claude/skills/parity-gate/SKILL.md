---
name: parity-gate
description: Gate a change against the parity store immediately before a commit. Auto-invoked when the next act is a commit ("commit this", "land this", "ready to commit", "gate this", "run the gate", "is this byte-neutral", "did anything move", "re-baseline", "promote the baseline") AND the working tree touches src/main/java/lib/minecraft/renderer/**, src/test/java/lib/minecraft/renderer/**, tooling/**, client/**, src/main/resources/lib/minecraft/renderer/*.json, src/main/resources/META-INF/services/**, gradle/**, build.gradle.kts, parity/scripts/parity/manifest.py or harness/**. Resolves which artifacts in the parity store can SEE the change and which are structurally BLIND, runs the cheapest sufficient bundle via parityPlan / parityCapture / parityCompare, and reports moved rows against the last known baseline. Do NOT invoke mid-edit, mid-diagnosis, for a scoped single-subject sweep (-PentityId / -PblockId / -PitemId), for a reference re-render, or for a docs-only / notes-only / CLAUDE.md-only commit.
auto_invoke: true
tags: [parity, gate, baseline, verification, pre-commit, asset-renderer]
---

# parity-gate

The single entry point for parity gating. Resolves reach, runs the cheapest sufficient bundle,
compares against the store, and reports movers. Decide from the JSON; explain from the markdown.

## When to invoke

All three must hold:

1. **The next act is a commit**, or a phase of per-mover commits has just finished. Said out loud, or
   a `git commit` is about to run. A phase that commits each mover separately gates once at the end,
   against `master..HEAD` rather than against a dirty tree - see `-Pchanged` below.
2. **The tree touches a trigger path** - `src/main/java/lib/minecraft/renderer/**`,
   `src/test/java/lib/minecraft/renderer/**`, `tooling/**`, `client/**`,
   `src/main/resources/lib/minecraft/renderer/*.json`,
   `src/main/resources/META-INF/services/**`, `gradle/**`, `build.gradle.kts`,
   `parity/scripts/parity/manifest.py`, or
   `harness/**` - the same list the frontmatter carries, and a `BlindnessMapTest` case holds the two
   to each other and to the map. It is a coarse prefix list and not an exact one: every path some
   rule gives a non-empty `sees` is under one of these, and the converse does not hold - a path
   under one of these can reach nothing. The build file and the manifest module are announced for
   what they declare rather than for being build and toolkit files: the member list that separates
   the two `cache/visual` manifests is typed in exactly those two, so editing either redefines what
   a manifest holds with no producer having run, and the rest of `parity/scripts/parity/**` reaches
   nothing and is not announced. The root build file is announced for a second thing it declares, the
   dependency set: a version is a statement about which code a producer runs rather than about which
   producer runs, so bumping a pin moves rendered bytes with no line of this repo's source having
   changed, and the version catalog under `gradle/**` says the same thing about the third-party half.
   The map holds the authoritative answer; `parityPlan` resolves from it and
   never from this paragraph.
3. **No verdict already exists for this tree state.** `_run/last-verdict.json` - written by
   `parityCompare` into whichever root it ran in - records the tree it measured, what the compare
   covered and what the compare found. The newest of them answers, over every root directly under
   `cache/parity/` plus the one root the invocation was itself handed, which need not be one of
   those; a `cache/` clean re-arms the gate.

Also invoke for: "re-baseline" / "promote the baseline", which enters at the promote step, and "prove
this is deterministic", which enters at the determinism pre-flight.

The repo-local `PreToolUse` hook covers the same moment from the other side: it fires on
`git commit`, asks `python parity/scripts/parity plan --gate-exit` the same question, and emits one
`ask` when the answer is "seen and ungated" and one when the map refuses a changed path no rule covers.
It never denies and it fails open, so its silence is never evidence. It is an attention mechanism,
not the gate.

## What this gate does

- Maps changed paths to artifacts via `blindness.json` - **SEES**, **BLIND**, **UNKNOWN**.
- Runs only the artifacts in PLAN, cheapest first. PLAN is SEES narrowed to what the store holds a
  file for. What that drops splits again, on whether a **verdict** can report a move in it rather
  than on whether a capture writes it: **COVERED** is a row field of a file the plan already
  captures, so that capture writes it, the compare joins it, and nothing more is owed; **MANUAL** is
  the rest, and each row names the act that measures it - `capture <id>` when its value is a joined
  field of a store file this plan left out, `read it there` when nothing gates it. `read it there`
  covers two cases: a home no capture writes at all (a test class, a path outside the store), and a
  node written into a captured file that the compare never reads - every `#/provenance/...` target
  and every `#/summary/...` one, since `compare.side_of` joins the kind's rows member and a
  manifest's `logs` and nothing else.
- Compares the capture against the store path-for-path and reports `moved=` per artifact.
- Asserts `diff == expected-diff` (empty by default), never "the sum held".
- Always prints what is blind and why.

It does not judge pixels, re-derive a sum, or decide whether a mover is acceptable.

## Standard invocation

```bash
./gradlew parityPlan                    # SEES / BLIND / PLAN / COVERED / MANUAL / cost; writes _run/plan.json
./gradlew parityExpect -PexpectEmpty    # clears the expected-diff, which survives the capture wipe
./gradlew parityCapture                 # runs the plan's PLAN set into cache/parity/current/
./gradlew parityCompare                 # the verdict; the only task that can fail
```

`parityExpect` runs before the capture and the compare because the manifest it writes is the
compare's other input, and it is the one file a capture does not erase - so a previous change's
registration is still in force until this clears it. For a change that intends to move rows,
register each one instead, naming the value it must land on:

```bash
./gradlew parityExpect -PexpectEmpty
./gradlew parityExpect -Partifact=sweep.entity -Pkey=minecraft__cow_temperate -Pto=0.2004 \
    -Preason="buildBox operand order"
```

A registration is per-row and additive; `-PexpectEmpty` is what starts a fresh one, and the two
cannot be given in one invocation - a clear and a registration are two orders and one would be
dropped. A registration with no `-Pto` is refused rather than accepted as "this row may move".

**A row whose producer is expected to FAIL is registered the other way**, by artifact and reason and
with no key or value:

```bash
./gradlew parityExpect -Partifact=digest.shipped-tables -Punproduced \
    -Preason="its writer asserts on the value being re-based"
```

A capture step is a finalizer, so it runs after a producer that failed; where one did, it records
that instead of reading output nothing wrote, and the compare reports the row as **UNPRODUCED** and
fails unless it was registered. `-Punproduced` cannot be given `-Pkey` or `-Pto` - what is wrong with
such a row is that it has no value - and it needs `-Preason`, because a producer nobody expected to
fail is the finding rather than a state to wave through.

**`-Pkey` is the row's key exactly as the stored artifact spells it**, and nothing checks that it is
one. A registration whose key matches no moved row is written, counted in the `N mover(s)
registered` line and then never consulted, so a near miss reads as success and the row it meant
still goes RED. Read the key out of the baseline rather than reconstructing it: `sweep.entity` keys
the cow rows `minecraft__cow_cold`, `minecraft__cow_temperate`, `minecraft__cow_temperate~age=baby`
and `minecraft__cow_warm`, and carries no `minecraft__cow`.

**A registration covers one moved value, not the row.** A row can move in more than one column at
once: a `sweep.entity`, `sweep.block` or `sweep.item` row carries nine comparable columns beside the
key (`differing_pixels`, `java_coverage`, `java_h`, `java_w`, `mean_argb_delta`, `status`,
`vanilla_coverage`, `vanilla_h`, `vanilla_w`), the player and armour rows five, a glint row six. A
row is GREEN only when *every* column it moved landed on a value registered for that row. So a row
that widens its canvas and moves its metric is two registrations on the same `-Pkey`; registering
only the canvas leaves the metric move RED, which is the point. A registered row landing anywhere its
registrations do not name is RED like any unregistered mover.

Then, only on an announced, priced re-baseline:

```bash
./gradlew parityPromote -Preason="buildBox operand order, +0.0004 over 8 rows"
```

Never prefix a task with `:asset-renderer:` - this repo is its own Gradle root and the prefix
cannot resolve (34 recorded failures).

If the plan's budget exceeds **110s**, run `parityCapture` in the background; the default shell
budget is 120s and a full bundle exceeds it. The budget is the sum of what each planned artifact's
producers took the last time that artifact was **promoted**, so a row promoted before the build
started measuring wall time contributes nothing.

**Read the parenthetical before the number.** The line says which of three states the bundle is in,
and only one of them is a cost. `BUDGET 0 ms  (no artifact in this plan has a recorded duration)`
means nothing in the bundle is measured. `BUDGET N ms  (k of m artifacts carry a duration, so this
is a floor and not the cost)` means the number is real for `k` artifacts and silent about the rest,
which is the dangerous reading: a bundle whose measured half is a two-second table and whose
unmeasured half boots the client prints comfortably under the 110s rule. A bare `BUDGET N ms` is the
whole cost. In the first two states, read the producer list instead.

## Common flags

- `-Partifacts=<comma list|alias>` - **optional**, and read by `parityCapture`, `parityCompare` and
  `parityPromote`. An alias expands on all three, so the same token scopes a capture and the compare
  and promotion that follow it. The other two behaviours below are `parityCapture`'s alone: it is the
  task that turns each id into a producer to run, so it is the one that reads the plan when the flag
  is absent and the one that refuses an id no capture row answers. A bare `parityCompare` compares
  every artifact the root holds, and a bare `parityPromote` plans every one of them.

  Absent, `parityCapture` reads `_run/plan.json`'s PLAN
  set; absent with no plan, it throws with the full id list and says to run `parityPlan` first.
  Present, it overrides the plan. Prefer narrowing the *change*. Naming an artifact the store holds
  no file for - anything the plan printed under COVERED or MANUAL - still throws `unknown artifact
  id`, which is the refusal working rather than a gap. A COVERED id is already inside the capture
  that writes its container. A MANUAL row names its own answer instead: a `capture <id>` row is
  asking for that **container**, which is a store file with a row of its own, and a `read it there`
  row has no container to name - either nothing captures it, or nothing compares it once captured.
- `-PparityRoot=cache/parity/base` - capture the A/B before-side into a redirected root instead of
  `cache/parity/current/`. Used with `git stash push -- src`; see `references/procedures.md`. The
  root is a path, must be relative and under `cache/`, and there is no slot name.
- `-Pbase=cache/parity/base` on `parityCompare` - compare against that redirected root rather than
  against the store.
- `-Pexpected=<file>` on `parityCompare` - assert the diff against that expected-diff manifest
  instead of the one `parityExpect` writes into `_run/expected-diff.json`. The registrations are the
  same shape either way; this only says which file holds them.
- `-Pchanged=<paths>` on `parityPlan` - a comma list of repo-relative paths to resolve reach for,
  instead of the paths git reports changed. It plans a change that is not in the tree; it does not
  narrow one that is. It is also how an ALREADY-COMMITTED change is planned: a phase landed as one
  commit per mover leaves a clean tree, so git reports nothing changed and a bare `parityPlan`
  resolves an empty change set. Hand it the branch's own diff -
  `-Pchanged="$(git diff --name-only master..HEAD | paste -sd, -)"` - and gate once at the end of the
  phase. A clean tree is what a promotion needs anyway (R4), so this ordering costs nothing.
- `-Pformat=json` on `parityPlan` - print the plan as JSON rather than as the SEES / BLIND / PLAN /
  BUDGET block. `_run/plan.json` is written either way, so this is for reading, not for producing.
- `-Pruns=N` on `parityCapture` - **recorded, never measured.** It stamps how many runs the operator
  is claiming agreed; the measurement is two captures into two roots compared against each other.
  Two for a render tree, five where the `Map.copyOf` salt can reach. Absent, each artifact is
  stamped with its own declared floor, so the determinism refusal is not what a bare capture trips
  on and a claim of fewer runs than the floor has to be typed. It is not the only refusal a
  promotion makes: read the table below before assuming a capture is promotable.
- `-Pbootstrap=true` - the first capture of an artifact has no baseline, so `MISSING_BASELINE` is the
  expected state; this is the only thing in the design that turns it into a pass. It does not lower a
  determinism floor and it does not excuse a dirty tree. It **is** the exemption from the
  compare requirement, because a first baseline has nothing to be diffed against - and the flag is
  per-invocation where a missing baseline is per-artifact, so it exempts every row promoted beside
  the new one. Give the promotion `-Partifacts` naming the new row, and nothing else rides it.
- `-PallowDirty=true` on `parityPromote` - promote a capture taken from an uncommitted tree, and
  record the exception in the promoted provenance. Reach for it only when the alternative is worse:
  the baseline is then re-derivable from no commit, and nothing read later recovers that.
- `-Ppopulation=changed` on `parityPromote` - the answer to the refusal below on a row count that
  moved, and the only one. It says the new covered set is intended, and records the exception in the
  promoted provenance. It waives nothing else: the digests still have to have been compared, and a
  row whose value moved is still a mover.
- `-Pclass={neutral,shaped,moving}` on `parityPromote` - defaults to `moving`, because forgetting it
  cannot then understate a change.
- `-PtoolingOut=<dir>` on any of the eight generator flows - where that flow writes its table,
  forwarded through to the tooling build. Defaults there to this project's resource tree, which is
  what makes a flow run dirty tracked files and is the signal `manifest.tooling-tables` reads. Point
  it at a scratch directory to take an A/B without touching the tree: the clean side into one, the
  changed side into another, then diff. A capture must not pass it - the artifact's source is the
  resource tree, so a redirected run would leave the shipped bytes unregenerated and the gate green
  over nothing.
- `-Preason=<text>` - mandatory on `parityPromote`, and on a `parityExpect` that registers a row.
- `-Punproduced` on `parityExpect` - registers a row whose producer is expected to fail, rather than
  a mover. Takes `-Partifact` and `-Preason` and refuses `-Pkey` or `-Pto`.
- `-PpythonExe=<path>` - which interpreter runs the toolkit, when the one this build resolves off
  `PATH` is the wrong one. `PARITY_PYTHON` in the environment does the same. Not a gate knob: it is
  the escape for a machine where the toolkit will not start at all.

There is no dry-run flag: `parityPlan` runs nothing and prints the plan and the budget, and Gradle
owns `--dry-run` for itself.

## Decision rules

| Situation | Behavior |
|---|---|
| SEES non-empty, 0 movers, no expected-diff | GREEN. Commit. |
| Movers == the registered expected-diff, every moved column of every row on a value registered for that row | GREEN. Commit; promote in the same commit. |
| A registered row moved in a column no registration of its names | RED. A `-Pto` is what one column must land on, never a licence for the row. Register the second value too, or fix the second move. |
| Movers != expected-diff | RED. Report per-row before/after. Do not re-baseline to make it pass. |
| A planned row is UNPRODUCED and no registration names it | RED. The producer failed, so the row has no value and the rest of the bundle is a verdict about a narrower set than was planned. Fix the producer, or register it with `-Punproduced`. |
| A mover on an artifact a rule called BLIND | RED, escalated separately. The map is wrong or the change is wider than its paths. Fix the rule; never register it as expected. **Unless the plan printed that line as `claimed blind, selected by <rule>`** - reach resolves one changed path at a time, so a `blind` list subtracts only on the paths its own rule triggers on and a `select` rule's subtracts on none at all. The named rule's `sees` put the artifact in the bundle regardless, whether it fired on the same path or on another in the change set, and the claiming rule's `mode` does not change that. The mover is ordinary; judge it by the rows above. |
| Sum unchanged but `moved > 0` | RED. A sum can hold while rows cancel. |
| COVERED non-empty | Nothing owed. The capture that writes each container writes that value with it, and the compare joins that node, so a move in it is already a mover on the container. |
| MANUAL row saying `capture <id>` | Widen the capture: add `<id>` to `-Partifacts` and gate it like any other row. Do not read it at the location beside it - that is the last **promoted** value, so it reports a stale baseline as a finding. |
| MANUAL row saying `read it there` | Read it at the location the plan printed beside it, and say what you found. No verdict reports it: either no capture writes it, or a capture writes it into a node the compare does not join. Widening `-Partifacts` does not help - reading is the only answer. |
| A changed path matches no rule and no `no_reach` glob | Refuse (R1). Add the rule or declare `no_reach`. |
| SEES empty, and one of the fired rules declares `sees: []` | Proceed on that rule's own terms, which its `reason` states: print the rule id and that reason, do what it says, and report the answer. Several name another gate - `paritySelfTest` for the toolkit, `./gradlew test` for the test tree and for a hand-edited baseline, the resolved argv of the tasks a build edit rewires - and naming one makes it the thing to run. A reason that names none has already said what an empty `sees` means: no artifact this store holds answers, so say so and gate nothing. |
| SEES empty, and no fired rule declares `sees: []` | Refuse (R2). No rule fired on any changed path at all, R1 above having already refused the paths no `no_reach` entry excuses, so the plan listed every one of them under `NO REACH` and the map answered ahead of the run rather than as a finding that no artifact this store holds moves. Gate nothing, and report that list. If the change does move something, the excuse absorbing its path is what is wrong - replace it with a rule, and name it. |
| Baseline missing | Refuse (R3). Bootstrap: prove determinism, capture clean, promote. |
| Promote from a dirty tree | Refuse (R4). `parityPromote` refuses it, naming the recorded `asset_dirty`. Land the change and re-capture; `-PallowDirty=true` records the exception instead. |
| `determinism_runs` below floor | Refuse (R5). |
| References stale or partial | Refuse (R6). Print `./gradlew renderVanillaAllReferences` and stop. Refreshing the oracle is the operator's call, taken before the work, never a side effect of gating the change measured against it. |
| Capture partial / producer non-zero / count mismatch | Refuse (R7). |
| Promote what this capture's compare did not cover | Refuse (R8). `parityPromote` requires `_run/compare.json` stamped with this capture's digest and naming every artifact it would write, so run `parityCompare` between the capture and the promotion and widen its `-Partifacts` to match. `-Pbootstrap=true` is the one exemption - a first baseline has nothing to be diffed against - and it exempts the whole invocation, so narrow that promotion with `-Partifacts`. |
| Promote a capture holding a different number of rows than its baseline | Refuse (R9). A population that moved is a different covered set, and the tree hashes cleanly either way, so nothing else catches it. Say the new set is intended with `-Ppopulation=changed`, which records the exception; a row with no baseline has nothing to have moved from and is passed over. |

**A phase that promotes is two commits, not one.** The migration lands first, because a capture that
gets promoted must run committed code or its provenance records `asset_dirty: true` and the baseline
is not re-derivable from any commit - which `parityPromote` refuses rather than leaves to procedure.
A phase that promotes nothing is one commit.

**Scope a promotion with `-Partifacts`.** A capture root often holds more than the artifact a phase
declared, because a producer finalizes its own capture step wherever it runs; a bare `parityPromote`
would re-stamp artifacts the phase never measured.

## Failure reporting

The verdict block has fixed sections and is written to `cache/parity/current/_run/compare.md`.
Quote that file; never retype a number out of it.

- **MOVED** - per artifact: row count, sum before -> after, then each moved row with its
  before and after value and any canvas change.
- **BLIND** - every artifact a reader might expect, with the rule id and its mechanism. Printed
  even when empty.
- **PROVENANCE** - the repo sha and dirty flag, MC version, the reference-manifest digest and
  sub-tree counts, and the determinism run counts in force. There is one sha: the harness is a
  directory in this repo, so a second one would be equal by construction.
- **NEXT** - the one action to take.

## Skip when

- Mid-edit or mid-exploration. `compileJava`, `compileTestJava` and `test` while iterating are
  normal work, not a gate.
- Diagnosis: `entityParityVanilla -PentityId=`, `entityRender3D`, `-Dasset.entity.pixel.dump`,
  reading `diff_panel.png`, `javap` in `cache/dragon-extract/`. A scoped sweep writes one row
  into a report the store would read as a 401-row regression.
- A reference re-render. That is a precondition run *before* the work; this gate only checks its
  currency.
- A docs-only, `notes/`-only or `CLAUDE.md`-only commit.
- `jmh` - use `jmh-regression-gate`.
- The user says "skip the gate" or "I already gated this".

## Why not just diff

Three reasons a hand-rolled compare gets this wrong, each measured in this repo:

- **Five subject-id spellings across six sweeps**, and glint puts `mean_argb_delta` in column 3.
  The canonical `awk '{s+=$2}'` is silently wrong there.
- **A held sum is not zero movers.** Two rows moving `+0.0668` and `-0.0424` net to `+0.0044`
  over 402 rows and read as noise.
- **Green is not evidence unless the gate can see the change.** The block and item sums are
  structurally blind to the box builder; every gate in the repo is blind to a `tooling/` change.
  A diff cannot tell you that. `blindness.json` can.

## Output stability

- Every stored and captured file is LF, UTF-8, no BOM, one trailing newline, `Locale.ROOT`.
- Artifacts run cheapest-first; movers sort by artifact id then by subject id, so two runs of
  one comparison print identical bytes.
- The verdict is a file first and stdout second.
- Exit codes: `0` passed. Non-zero means the gate did not pass; see `references/procedures.md` for
  the six-code table.

## Cross-reference

- `references/artifacts.md` - every artifact in `index.json`, its producer, its determinism floor
  and its cost. Also the tasks that carry no artifact id and why. **Generated.**
- `references/blindness.md` - the reach rules in prose. Load when a rule is being *questioned*; the
  decision always comes from `blindness.json` via `parityPlan`. **Generated.**
- `references/procedures.md` - the stash A/B, the tooling-regen A/B, the reference refresh, the
  determinism pre-flight, the promotion shape, and the toolkit's six exit codes.
- `references/determinism.md` - what reproduces over how many runs, and what never can.
- `references/diagnostics.md` - the worked diagnostic examples and the version-scoped rosters. Load
  when diagnosing a mover, never to decide a verdict.
- `tooling-flow-gate` is the other half for a `tooling/**` change. Every artifact this store holds is
  BLIND to a generator refactor (`B13`) because it reads the shipped JSON, so re-running the flow is
  the measurement and `manifest.tooling-tables` only confirms the digest of those bytes afterwards.
  Run that gate first; a green verdict here alone says nothing about a tooling change.
- `gradle-verify-gate` covers `compileJava` + `test` alone; this gate supersedes it before a
  commit that touches a trigger path. `jmh-regression-gate` is the benchmark equivalent and is
  never part of a parity bundle.

## Invariants

- The skill runs exactly the five Gradle tasks in group `parity` and no others. No `awk`, no
  `join`, no `xargs sha256sum`, no `python -c`.
- The skill never writes the production store. Only `parityPromote` does.
- The skill never edits source, never stashes, never commits.
- A `cache/` path is never cited as an expected value - anything under `cache/` is output.
- The verdict always names what is blind.
- No count of artifacts or rules is ever written down here. It lives in `index.json` and in the
  generated references, which cannot go stale.
