# Sweep architecture

Load when touching `HarnessMode`, `HarnessConfig`, `api.Sweep` and `api.SweepRunner`,
`pip.PipTarget`, or how the posed sweeps share a boot with the frozen ones. Adding a sweep is in
[`harness/CLAUDE.md`](../../harness/CLAUDE.md).

Seven freeze `setupAnim` and are ground truth for the mesh as authored; the two posed sweeps turn that freeze off and are ground truth for the mesh its model poses. **A sweep declares its `Gait` and `PoseState` holds the armed one**, so the freezes are a property of the sweep rather than of the JVM - which is what they always could have been, both `SkipSetupAnimMixin` redirects branching per render. **The two posed sweeps are one sweep at two gaits** - `EntityAnimationSweep` takes its gait as a constructor argument and writes `idle/` or `walk/` from it, each sub-tree answering the gait's own name so the map is one spelling from the enum constant to the directory. A freeze is armed per sweep off `PoseState` rather than read once per JVM, and `HarnessMode.resolve` orders a run `BIND` before `IDLE` before `WALK` - which is a correctness requirement, not tidiness: a freeze SKIPS `setupAnim` rather than undoing it, so a posed sweep ahead of a frozen one would leave every bone it touched posed and the frozen sub-trees would record that.

`RefHarnessRenderer` advances `HarnessConfig.RENDERS_PER_TICK` sweep steps per client tick after a 60-tick warmup - **8 by default, and it was effectively 1 for the harness's whole life, which was the run's largest single cost**. A client ticks twenty times a second whatever the machine can draw, so 4435 references priced at one a tick is the 3m 28s below. What held it at one was `PipTarget` CLOSING its colour texture on a canvas resize: a copy still in flight could meet a released texture, and only a tick being far longer than a copy kept it from happening. A replaced texture is retired and closed `max(8, 2 x RENDERS_PER_TICK)` renders later instead, so the pacing is a throughput knob rather than a correctness fence. Measured on the whole tree, every reading byte-identical: 1 -> 3m 28s, 4 -> 1m 13s, 16 -> 1m 7s, 32 -> 1m 8s. A run's sweeps come from `HarnessMode` - `FULL` is block → item → entity → player, `EVERY` is those four plus glint, armor and menus; `GLINT`, `PLAYERS`, `ARMOR`, `MENUS`, `ANIMATION`, `WALK`, `PITCH_ROLL`, `DEPTH_QUANTUM`, `PACK_SOURCES` and `PACK_ICONS` each run one sweep alone. Setting two mode properties selects both, in gait order. **`FULL` is the odd name** - `EVERY` is the one that renders the whole reference tree, and the gap between them is where stale ground truth accumulated twice. `ANIMATION` and `WALK` share `EntityAnimationSweep`, which takes its `Gait` as a constructor argument and answers both `outputDir()` and `gait()` from it - so two instances are two sub-trees where a static read used to make them two runs.

Every sweep implements `api.Sweep` (`outputDir` / `enumerate` / `key` / `canvas` / `render`, plus the `prepare` / `beforeSubject` / `afterSweep` hooks) and is driven by `api.SweepRunner`, which owns the work index, the tally, the completion latch and the per-tick pacing. **Every sweep renders `HarnessConfig.RENDERS_PER_TICK` subjects per tick**, entity variants included - the read-back is async, and a replaced texture is retired rather than closed so several copies can be in flight at once.

Drawing goes through `pip.PipTarget`: one offscreen `RGBA8 + DEPTH32` target per renderer, `copyTextureToBuffer` → `NativeImage` → PNG. The target is allocated only once a renderer has committed to drawing (decline before draw), and its GPU textures are deliberately leaked at end-of-sweep rather than closed into an in-flight callback.

## The posed sub-trees

- **The two posed sub-trees are one sweep at two gaits**, `-Drefharness.walking=true` selecting the
  second, and the asset side is one driver at `asset.parity.gait=walk`. Each names its own gait -
  `idle/` and `walk/` on the harness side, `idle` and `walk` on the property - so the two sides map a
  sub-tree by one spelling. Neither doubles the subjects: what a gait changes is the walk each
  subject is armed at and so the strip it takes.
- **The posed sub-trees are rendered at the roster `entityScheduleRoster` writes** beside the tree,
  which every posed reference run writes first: each subject's frames, step, walk and clock values,
  resolved through the code the asset side's own render runs. The harness refuses a subject the
  roster does not name, and each sub-tree records what it rendered in its `schedule.json`, which the
  two animation sweeps hold to their own resolution.
