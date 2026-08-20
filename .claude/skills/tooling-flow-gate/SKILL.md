---
name: tooling-flow-gate
description: Gate a tooling/** change by re-running the flows it owns and diffing emitted bytes against the committed tables. Auto-invoked when the next act is a commit AND the working tree touches tooling/** or client/**. Every renderer parity artifact is structurally BLIND to a generator change - it reads the SHIPPED JSON a refactor does not regenerate - so re-running the flow IS the measurement. Runs the owning flow, diffs the golden, diffs the diagnostics log order-independently, and runs the shipped-table digest. Do NOT invoke for a renderer-source change (that is parity-gate), mid-edit, or for a docs-only commit.
auto_invoke: true
tags: [tooling, gate, generators, pre-commit, byte-parity, asset-renderer]
---

# tooling-flow-gate

The gate for a change in the `tooling/` build. Re-runs what the change owns, compares emitted
bytes and the diagnostics log against what is committed, and reports what moved.

## Why this exists separately from parity-gate

`parityPlan` over a `tooling/**` change resolves SEES to `manifest.tooling-tables` and
`report.diagnostics-log` and calls every sweep, pin and digest **BLIND** under rule `B13`, whose own
text says it: they all read the SHIPPED JSON, which a generator refactor does not regenerate, so a
green sweep is no evidence about a change here. `manifest.tooling-tables` closes half the loop - it
digests the tables and each flow's log - but it reads them where they sit. Regenerating them is what
this gate does, and it is the only thing that can.

Run this first. Then run `parity-gate`, which confirms the store's digest of those same bytes did
not move. A green `parity-gate` alone, on a tooling change, means nothing.

## The four steps

1. **Re-run the owning flow** through the tooling wrapper (never the renderer's).
2. **Diff the golden** - `git status --porcelain -- src/main/resources/`. Empty is byte-equal.
3. **Diff the diagnostics log** against a baseline, **sorted** (see the order trap).
4. **Run the shipped-table digest** - `./gradlew test --tests '*BundledResourceShaTest*'` at the
   renderer root.

```bash
# from the renderer root
cd tooling && ./gradlew <flow> -q --rerun-tasks > /tmp/gate.log 2>&1; echo "EXIT=$?"
cd .. && git status --porcelain -- src/main/resources/          # empty = byte-equal
grep -E "^\[.*<flow>" <baseline>.log | sort > /tmp/a.s
grep -E "^\[.*<flow>" /tmp/gate.log  | sort > /tmp/b.s
diff /tmp/a.s /tmp/b.s && echo "LOG IDENTICAL"
./gradlew test --tests '*BundledResourceShaTest*' -q
```

`--rerun-tasks` is not optional. A flow whose inputs Gradle considers unchanged is UP-TO-DATE and
writes nothing, so the diff passes over a change that was never executed.

## Flow to golden

Eight flows, eleven tables - `entityModels` writes three of them. `./gradlew generateTables` runs
every flow; `-Pflows=a,b` runs a subset and refuses a name that is not one of the eight.

| flow | golden |
|---|---|
| `entityModels` | `entity_models.json`, `entity_geometry.json` |
| `blockModels` | `block_models.json`, `block_geometry.json` |
| `blockDefaults` | `block_defaults.json` |
| `blockItems` | `block_items.json` |
| `blockTints` | `block_tints.json` |
| `potionColors` | `potion_colors.json` |
| `glintItems` | `glint_items.json` |
| `colorMaps` | `color_maps.json` |

A change to the shared kernel (`kernel/**`, `walk/**`, `policy/**`) owns **all eight**: run
`generateTables`. A change under one flow's package owns that flow, and the geometry parser feeds
both `entityModels` and `blockModels`.

## The baseline

The baseline is the log from **the committed tree before the first edit**, not a capture taken
mid-change. Take it once at the start of an effort and keep it:

```bash
git stash push -- tooling    # if edits are already in the tree
cd tooling && ./gradlew generateTables -q --rerun-tasks > /tmp/baseline.log 2>&1
cd .. && git status --porcelain    # MUST be clean - proves the tree reproduces itself
git stash pop
```

That clean-tree run is also the precondition the whole gate rests on: **a tree that does not
reproduce its own committed tables cannot gate anything**. Prove it once per effort before trusting
a single diff. Two runs of `generateTables --rerun-tasks` must also produce an identical log; a log
that flaps run to run is not a gate input.

## Traps

- **Log line order follows task invocation order, not content.** Running `entityModels blockModels`
  puts blockModels' lines after entityModels', where a `generateTables` baseline has them first. An
  unsorted diff reports every line as moved. **Always sort both sides.** This has produced a false
  positive in practice.
- **A byte-identical table is not an unchanged run.** The tooling-tables manifest digests each
  flow's log by `(severity, path, message)`, so a reworded diagnostic moves a digest with no table
  byte changing. Diff the log every time, not just when the table moves.
- **Never `-PtoolingOut` for the landing diff.** It redirects the whole emitted set, so the tracked
  bytes are never regenerated and the gate passes over nothing. `-PtoolingOut=<scratch>` is for an
  A/B against a clean tree, and a parity capture taken over a redirected run gates unregenerated
  bytes.
- **Never `--configuration-cache`.** It silently drops `-Dasset.*` system properties with no
  warning, so a measurement taken under it is measuring different inputs.
- **A flow writes to the tracked resource tree by default.** That is the signal, not a problem. A
  failed run can leave a partial table on disk - check `git status` before concluding anything about
  the next run.
- **A flow that aborts mid-write.** Every walk should raise ahead of `session.write`; if a table is
  short rather than absent after a failure, that ordering is what is wrong, not the gate.

## Verdict

- **Golden empty + log identical + digest green** -> byte-neutral. Commit.
- **Golden moved** -> the change is not byte-neutral. That is the finding. Do not commit it as
  neutral, and do not regenerate a "new baseline" to make it quiet - re-running the flow is what
  produced the moved bytes, so a re-baseline just records them as intended.
- **Golden empty but log moved** -> a real change with no table effect. Say so; it still moves the
  store's log digest, so `parity-gate` will report it.
- **Flow exits non-zero** -> read the exception before anything else. A loud re-entry firing is the
  tripwire working, and it usually names a coordinate the jar no longer holds.

## Also run the tooling suite

`cd tooling && ./gradlew test -q` is this build's own suite and does not run from the renderer's
`test`. The renderer's `check` schedules it as `toolingTest`, so `./gradlew check` at the root is
the cheap way to catch a tooling change that does not compile.

## Skip when

- The change is renderer source, resources or harness - that is `parity-gate`.
- Mid-edit or mid-diagnosis.
- A docs-only, `notes/`-only or `CLAUDE.md`-only commit.
