# Sweep architecture

Load when touching `HarnessMode`, `HarnessConfig`, `RefHarnessRenderer`, `api.Sweep` and
`api.SweepRunner`, `pip.PipTarget`, how the posed sweeps share a boot with the frozen ones, or what a
run refuses before its first sweep. Adding a sweep is in
[`harness/CLAUDE.md`](../../harness/CLAUDE.md).

Every sweep but the two posed ones freezes `setupAnim` and is ground truth for the mesh as authored; the two posed sweeps turn that freeze off and are ground truth for the mesh its model poses. **A sweep declares its `Gait` and `PoseState` holds the armed one**, so the freezes are a property of the sweep rather than of the JVM - which is what they always could have been, both `SkipSetupAnimMixin` redirects branching per render. **The two posed sweeps are one sweep at two gaits** - `EntityAnimationSweep` takes its gait as a constructor argument and writes `idle/` or `walk/` from it, each sub-tree answering the gait's own name so the map is one spelling from the enum constant to the directory. A freeze is armed per sweep off `PoseState` rather than read once per JVM, and `HarnessMode.resolve` orders a run `BIND` before `IDLE` before `WALK` - which is a correctness requirement, not tidiness: a freeze SKIPS `setupAnim` rather than undoing it, so a posed sweep ahead of a frozen one would leave every bone it touched posed and the frozen sub-trees would record that.

`RefHarnessRenderer` advances `HarnessConfig.RENDERS_PER_TICK` sweep steps per client tick after a 60-tick warmup - **8 by default, and the tick clock is the run's largest single cost**. A client ticks twenty times a second whatever the machine can draw, so 4435 references priced at one a tick measured 3m 28s. `PipTarget` never closes a colour texture a canvas resize replaces while a copy may still be in flight from it: the replaced texture is retired and closed `max(8, 2 x RENDERS_PER_TICK)` renders later, so the pacing is a throughput knob rather than a correctness fence. Measured on the whole tree, every reading byte-identical to the stored tree: 8 -> 1m 25s, 16 -> 52s, and at both every sub-tree of more than a hundred references renders at the cap the value sets, 160 and 320 a second, so on the machine the tree is rendered on the tick bounds the run at either value. The default stays at 8 because a value past it is measured on that machine only, and a GPU retiring fewer copies a tick than the value asks for moves the wait from the tick to the copies. A run's sweeps come from `HarnessMode` - `FULL` is block → item → entity → player, `EVERY` is those four plus glint, armor, menus, tooltips and books; `GLINT`, `PLAYERS`, `ARMOR`, `MENUS`, `TOOLTIPS`, `BOOKS`, `ANIMATION`, `WALK`, `PITCH_ROLL`, `PACK_SOURCES`, `PACK_ICONS` and `PACK_TOOLTIPS` each run one sweep alone. A run names its modes through `refharness.modes`, the one spelling of a reference mode, and naming two selects both, in gait order. Before its first sweep exists, `RefHarnessRenderer.start` refuses a client that does not draw as vanilla's own: the built-in programmer-art or high-contrast pack selected, which redraw vanilla's textures; a pack from the client's resource-pack folder that the run did not ask for, which for a run naming no pack is any; or high contrast, the forced Unicode font or the Japanese glyph variants on, or a language other than `en_us`. Each is read and never set, so only a hand launch leaves one changed. The check is the same for every mode, the probes and the modes that draw no text included, because the selection and the options are the shared run directory's state rather than any mode's, and a list of the modes each reaches would be one more statement a new sweep could be left out of. A refused run writes no file, where a sweep's own refusal would come after every earlier sweep of the boot had written its sub-tree. **`FULL` is the odd name** - `EVERY` is the one that renders the whole reference tree, and the gap between them is where stale ground truth accumulated twice. `ANIMATION` and `WALK` share `EntityAnimationSweep`, which takes its `Gait` as a constructor argument and answers both `outputDir()` and `gait()` from it - so two instances are two sub-trees where a static read used to make them two runs.

Every sweep implements `api.Sweep` (`outputDir` / `enumerate` / `key` / `canvas` / `render`, plus the `prepare` / `beforeSubject` / `afterSweep` hooks) and is driven by `api.SweepRunner`, which owns the work index, the tally, the completion latch and the per-tick pacing. **Every sweep renders `HarnessConfig.RENDERS_PER_TICK` subjects per tick**, entity variants included - the read-back is async, and a replaced texture is retired rather than closed so several copies can be in flight at once.

Drawing goes through `pip.PipTarget`: one offscreen `RGBA8 + DEPTH32` target per renderer, `copyTextureToBuffer` → `NativeImage` → PNG. **The render thread copies a frame and moves on**: the copy's callback flips the mapped rows into the image a row at a time and hands it to a small pool that encodes the PNG, with the same encoder, so an encode never holds the next draw. A full queue encodes on the thread handing it over, and `RefHarnessClient`'s exit hook waits the pool out before it reads the failed writes. Measured on the whole tree in two scratch boots, both byte-identical to the stored tree, the render span is 61.5 s. The target is allocated only once a renderer has committed to drawing (decline before draw), and its GPU textures are deliberately leaked at end-of-sweep rather than closed into an in-flight callback.

## The posed sub-trees

- **The two posed sub-trees are one sweep at two gaits**, the `WALK` mode selecting the
  second, and the asset side is one driver handed the `walk` gait. Each names its own gait -
  `idle/` and `walk/` on the harness side, `idle` and `walk` as the driver's argument - so the two
  sides map a sub-tree by one spelling. Neither doubles the subjects: what a gait changes is the walk each
  subject is armed at and so the strip it takes.
- **The posed sub-trees are rendered at the roster `entityScheduleRoster` writes** beside the tree,
  which every posed reference run writes first: each subject's frames, step, walk and clock values,
  resolved through the code the asset side's own render runs. The harness refuses a subject the
  roster does not name, and each sub-tree records what it rendered in its `schedule.json`, which the
  two animation sweeps hold to their own resolution.
- **That record is what lets a capture skip the boot.** Every frozen sub-tree reads nothing of the
  roster, so where a gate's only route into the tree is the roster, the capture regenerates it and
  `parityScheduleCheck` projects it onto both `schedule.json`s the way `ScheduleRoster` reads it - the
  format, the client it was resolved for by major.minor, every schedule's frames, step, walk speed,
  walk-clock rate and clocks, and each posed trait as a subject of its own. The boot runs only where
  that disagrees. A schedule carrying `coats` is refused outright, because the canvas is measured
  across every coat's schedule and the sidecar records the drawn coat's alone; so are two stems
  recording one subject; and a roster entry with no strip is excused only where `entities/` holds no
  reference of that entity at that age either, the two sweeps choosing their subjects the same way.
  A run naming no targets enumerates every subject the roster names, so its sweep drops every sidecar
  entry it did not enumerate; a targeted run keeps the entries of the subjects it filtered out.
- **The projection alone says nothing about where the tree came from**, so two holds stand beside
  it, and the boot runs where either disagrees. The check resolves the change again from git when
  the capture runs and refuses unless the tree as it stands still reaches `manifest.references`
  through the roster alone, by the rules the plan named - the plan is a file, and it outlives every
  edit made after it. And it refuses unless `references-rendered.json`, beside the tree, records a
  whole, untargeted boot that exited 0 from the harness inputs the tree holds now: the trigger paths
  of every rule that boots the tree, digested before the boot and sealed after it. Every asset-side
  run that writes into the tree deletes the record before it boots, and only
  `renderVanillaAllReferences` writes it.
