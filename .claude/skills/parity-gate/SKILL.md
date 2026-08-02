---
name: parity-gate
description: Gate a change against the parity store immediately before a commit. Auto-invoked when the next act is a commit ("commit this", "land this", "ready to commit", "gate this", "run the gate", "is this byte-neutral", "did anything move", "re-baseline", "promote the baseline") AND the working tree touches src/main/java/lib/minecraft/renderer/**, src/test/java/lib/minecraft/renderer/**, src/main/resources/lib/minecraft/renderer/*.json, build.gradle.kts or harness/**. Resolves which artifacts in the parity store can SEE the change and which are structurally BLIND, runs the cheapest sufficient bundle via parityPlan / parityCapture / parityCompare, and reports moved rows against the last known baseline. Do NOT invoke mid-edit, mid-diagnosis, for a scoped single-subject sweep (-PentityId / -PblockId / -PitemId), for a reference re-render, or for a docs-only / notes-only / CLAUDE.md-only commit.
auto_invoke: true
tags: [parity, gate, baseline, verification, pre-commit, asset-renderer]
---

# parity-gate

The single entry point for parity gating. Resolves reach, runs the cheapest sufficient bundle,
compares against the store, and reports movers. Decide from the JSON; explain from the markdown.

## When to invoke

All three must hold:

1. **The next act is a commit.** Said out loud, or a `git commit` is about to run.
2. **The tree touches a trigger path** - `src/main/java/lib/minecraft/renderer/**`,
   `src/test/java/lib/minecraft/renderer/**`, `src/main/resources/lib/minecraft/renderer/*.json`,
   `build.gradle.kts`, `scripts/parity/**`, or `harness/**`. The map holds the authoritative list;
   `parityPlan` answers from it and never from this paragraph.
3. **No verdict already exists for this tree state.** `_run/last-verdict.json` - written by
   `parityCompare`, carrying `asset_sha`, `asset_dirty_digest` and `artifacts[]` - records which
   bytes were gated. A `cache/` clean re-arms the gate.

Also invoke for: "re-baseline" / "promote the baseline", which enters at the promote step, and "prove
this is deterministic", which enters at the determinism pre-flight.

The repo-local `PreToolUse` hook covers the same moment from the other side: it fires on
`git commit`, asks `python scripts/parity plan --gate-exit` the same question, and emits one `ask`
when the answer is "seen and ungated". It never denies and it fails open. It is an attention
mechanism, not the gate.

## What this gate does

- Maps changed paths to artifacts via `blindness.json` - **SEES**, **BLIND**, **UNKNOWN**.
- Runs only the artifacts in SEES, cheapest first.
- Compares the capture against the store path-for-path and reports `moved=` per artifact.
- Asserts `diff == expected-diff` (empty by default), never "the sum held".
- Always prints what is blind and why.

It does not judge pixels, re-derive a sum, or decide whether a mover is acceptable.

## Standard invocation

```bash
./gradlew parityPlan                    # SEES / BLIND / cost; writes _run/plan.json
./gradlew parityCapture                 # runs the plan's SEES set into cache/parity/current/
./gradlew parityCompare                 # the verdict; the only task that can fail
```

Then, only on an announced, priced re-baseline:

```bash
./gradlew parityPromote -Preason="phase 6: buildBox operand order, +0.0004 over 8 rows"
```

Never prefix a task with `:asset-renderer:` - this repo is its own Gradle root and the prefix
cannot resolve (34 recorded failures).

If the plan's budget exceeds **110s**, run `parityCapture` in the background; the default shell
budget is 120s and a full bundle exceeds it. A budget of `0 ms` means no artifact in the plan has a
recorded duration yet, not that the bundle is free - read the producer list instead.

## Common flags

- `-Partifacts=<comma list|alias>` - **optional.** Absent, the task reads `_run/plan.json`'s SEES
  set; absent with no plan, it throws with the full id list and says to run `parityPlan` first.
  Present, it overrides the plan. Prefer narrowing the *change*.
- `-PparityRoot=cache/parity/base` - capture the A/B before-side into a redirected root instead of
  `cache/parity/current/`. Used with `git stash push -- src`; see `references/procedures.md`. The
  root is a path, must be relative and under `cache/`, and there is no slot name.
- `-Pbase=cache/parity/base` on `parityCompare` - compare against that redirected root rather than
  against the store.
- `-Pruns=N` on `parityCapture` - **recorded, never measured.** It stamps how many runs the operator
  is claiming agreed; the measurement is two captures into two roots compared against each other.
  Two for a render tree, five where the `Map.copyOf` salt can reach.
- `-Pbootstrap=true` - the first capture of an artifact has no baseline, so `MISSING_BASELINE` is the
  expected state; this is the only thing in the design that turns it into a pass. It does not lower a
  determinism floor.
- `-Pclass={neutral,shaped,moving}` on `parityPromote` - defaults to `moving`, because forgetting it
  cannot then understate a change.
- `-Preason=<text>` - mandatory on `parityPromote`.

There is no dry-run flag: `parityPlan` runs nothing and prints the plan and the budget, and Gradle
owns `--dry-run` for itself.

## Decision rules

| Situation | Behavior |
|---|---|
| SEES non-empty, 0 movers, no expected-diff | GREEN. Commit. |
| Movers == the registered expected-diff | GREEN. Commit; promote in the same commit. |
| Movers != expected-diff | RED. Report per-row before/after. Do not re-baseline to make it pass. |
| A mover on an artifact a rule called BLIND | RED, escalated separately. The map is wrong or the change is wider than its paths. Fix the rule; never register it as expected. |
| Sum unchanged but `moved > 0` | RED. A sum can hold while rows cancel. |
| A changed path matches no rule | Refuse (R1). Add the rule or declare `no_reach`. |
| SEES empty | Refuse (R2). Build a gate first, or drop the change. |
| Baseline missing | Refuse (R3). Bootstrap: prove determinism, capture clean, promote. |
| Promote from a dirty tree | Refuse (R4). |
| `determinism_runs` below floor | Refuse (R5). |
| References stale or partial | Refuse (R6). Run `renderVanillaAllReferences` - the whole tree, never a narrow task. |
| Capture partial / producer non-zero / count mismatch | Refuse (R7). |

**A phase that promotes is two commits, not one.** The migration lands first, because a capture that
gets promoted must run committed code or its provenance records `asset_dirty: true` and the baseline
is not re-derivable from any commit. A phase that promotes nothing is one commit.

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
- `gradle-verify-gate` covers `compileJava` + `test` alone; this gate supersedes it before a
  commit that touches a trigger path. `jmh-regression-gate` is the benchmark equivalent and is
  never part of a parity bundle.

## Invariants

- The skill runs exactly four Gradle tasks and no others. No `awk`, no `join`, no
  `xargs sha256sum`, no `python -c`.
- The skill never writes the production store. Only `parityPromote` does.
- The skill never edits source, never stashes, never commits.
- A `cache/` path is never cited as an expected value - anything under `cache/` is output.
- The verdict always names what is blind.
- No count of artifacts or rules is ever written down here. It lives in `index.json` and in the
  generated references, which cannot go stale.
