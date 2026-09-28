# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## PlayerOptions has no style knob, and coining one needs a catalog source for the player

The style axis is a string knob on `EntityOptions` resolved against the entity's shipped catalog.
The player renders through its own pipeline, holds no row in `entity_models.json`, and its sweeps
gauge look rather than bytes - so a style knob on `PlayerOptions` today would be a string with
nothing to resolve against. Deferred deliberately by the owner (2026-09-01), with the axis kept
collision-free: adding the knob later needs a player-side source of catalog rows.

`PoseStyle` names no bag at all - `AppearanceOptions.applies` asks the appearance rather than the
request carrying one - so the row type is answerable for a subject whose options are not an
entity's, and a player catalog shipping age-free rows never reaches it either way. What still spells
`EntityOptions` is the caller: `StyleCatalog.resolve` and the `byId` overload behind it take a
predicate and name no bag; `EntityRenderer` supplies it as `options.getAppearance()::applies`.

**That shape is not an `AppearanceOptions` on `PlayerOptions`.** `AppearanceOptions` stays
entity-specific by decision, so the player does not gain one. What the three bags share is an
appearance concern nothing abstracts yet, and organising that is its own job, deliberately not
attached to this entry - a knob coined by widening the player bag to look like an entity's would be
settling that question by accident.

A styled player exists on the entity path, and it is not the source the knob needs.
`author.install.PlayerRig` synthesizes a `minecraft:player` entity row - the wide-arm humanoid mesh
copied off the shipped zombie row, under `StyleCatalog.BIND_ONLY` and `EntityPose.NONE` - that takes
installed styles through `EntityRenderer`. It sits at tier 17.4 in `TierOrderTest`'s order and
`PlayerRenderer` sits at the root's 16, where an import runs only to a strictly lower tier, so
`PlayerRenderer` may not name it; and `PlayerRenderer` carries no style machinery at all, importing
nothing from `asset.pose`, `bake.pose`, `engine.pose` or `author`.

Taking the knob needs four things: a style knob on `PlayerOptions`; a player catalog source below
tier 16; style resolution the player bag can answer; and `PlayerRenderer` taught to pose. The third
is the smallest of them: `StyleCatalog.resolve` takes the style id, a predicate saying whether a row
applies to the subject, and the subject id its refusal names, so the player bag has only to supply
the predicate. Supplying it is the appearance question the three bags share, though, so taking the
knob settles the abstraction this entry keeps separate by the back door. It reaches the player
sweeps, which are LOOK gauges rather than byte gates, and the entity pose path.

## A named module waits on module names in nine upstream JARs

A consumer on the classpath reads every public type, so it faces every package that holds one. Only
a JPMS `exports` hides a whole package, and only from a consumer that is itself modular. Dropping
`public` from the types nobody outside their package names hides none: every package that holds a
type keeps one another package names, bar `author.audit` and `author.install`, whose types are the
entry points a consumer audits and installs styles through. Parked by the owner.

The blocker is upstream. The nine JitPack libraries the build takes on `api` - six from
simplified-dev, one from simplified-api, two from minecraft-library - carry no `module-info` and no
`Automatic-Module-Name`, so each is an automatic module named off its file name, and a JitPack pin
is a commit sha that is not read as a version. A sha opening with a digit leaves a name segment that
is not a Java identifier, so no name derives at all; one opening with a letter derives a name that
carries the sha and changes with every pin bump. The pins in force derive no name for seven of the
nine and a sha-bearing name for the other two. A named module cannot resolve on the module path
until each of the nine publishes an `Automatic-Module-Name` or its own `module-info` - a JitPack
build and a pin bump apiece.

Past that, `module-info.java` owes more than its `exports`, whose set is a signature-closure probe
nobody has run over the packages the README's usage section names:

- `provides dev.simplified.gson.GsonContributor with
  lib.minecraft.renderer.content.json.RendererGsonContributor`, because a named module ignores
  `META-INF/services` and a modular consumer would otherwise lose the registered adapters silently;
- `opens ... to com.google.gson` for every package Gson reflects into - `asset.mesh`,
  `asset.model` and every `content` package that decodes JSON at least;
- `requires static` for the annotation processor's vocabulary, for the `@Parity` vocabulary, whose
  JAR needs a module name of its own, and for `jdk.incubator.vector`, which keeps `SimdSupport`'s
  scalar fallback and a consumer owing no flag;
- a `blindness.json` rule or `no_reach` entry covering the file, since the plan refuses a path no
  rule covers.

Nothing in the suite runs on the module path, so a missing `opens` - an
`InaccessibleObjectException` at run time - or a `provides` that drifts goes unseen without a
module-path consumer that renders something.

## harness/CLAUDE.md says humanoid armour has no LayerType and falls back to the body texture

The *Unworn equipment layers must pad no bounds* bullet at `harness/CLAUDE.md:176` lists humanoid
armour with wool and the mooshroom body as a layer with no `LayerType`, one that "genuinely has no
equipment texture and still falls back to the body texture". Neither half holds for the armour.
`EntityBoundsWalker.reflectLayerType` answers `HUMANOID_BABY` for a baby and `HUMANOID` otherwise
for any `HumanoidArmorLayer` (`EntityBoundsWalker.java:1210-1218`), so `equipmentTexture` resolves
the worn piece's own sheet through `EquipmentClientInfo` (`:1138-1160`) and the shell is measured
through it. When no sheet resolves, the non-null `LayerType` makes `walkLayerExtents` skip the
layer outright (`:294`) - the unworn-layer rule the same bullet opens with - and the body-texture
fallback is never reached.

The bullet misleads whoever is chasing an armoured reference's canvas: it sends them to the body
texture, where the measurement reads the armour sheet. It settles when the bullet drops humanoid
armour from the no-`LayerType` list and names it beside the wings as a layer whose type the walker
supplies itself - by age for the armour, from `HARDCODED_LAYER_TYPES` for the wings.

## EntityOverlayFitTest fits wings on a villager, which no vanilla renderer draws

`EntityOverlayFitTest.babyElytraFitsItsCanvas` and `adultElytraFitsItsCanvas` each put the elytra on
`minecraft:villager` (`EntityOverlayFitTest.java:115`, `:126`) and assert the canvas leaves no slack
around the wings. Vanilla constructs a `WingsLayer` in three renderers only - `HumanoidMobRenderer`,
`ArmorStandRenderer` and `AvatarRenderer` - and `VillagerRenderer`, an `AgeableMobRenderer`, adds
`CustomHeadLayer`, `VillagerProfessionLayer` and `CrossedArmsItemLayer` and no wings. So the two
villager cases hold the fit of an appearance no client produces. The class javadoc already says the
villager has no reference "because vanilla draws no wings on one", and keeps it anyway.

The renderer does draw it: the `WINGS` feature in `EntityRenderer` (`EntityRenderer.java:608-617`)
gates on `AppearanceOptions.isElytra()` alone and seats the wings on any `body` bone, while
`AppearanceOptions`' own field javadoc calls the knob "only meaningful for the humanoid roster that
can equip a chest item". The test therefore pins behaviour on an input outside the documented
domain, and a villager case failing would say nothing about any render vanilla can be compared to.

It settles when the villager cases give way to a wearer vanilla draws wings on - a zombie villager
keeps a villager-shaped head and has a baby form - so every elytra case is one a harness reference
could be taken for.

## The harness pixel dump walks layers the bounds walk places or skips differently

`EntityBoundsWalker.dumpTrianglesIfRequested` - the `[PX] TRI` trace armed by `-PentityPixelDump`
(`entity.pixel.dump`) - says it walks "every active layer (matching the bounds walker's
coverage)" (`EntityBoundsWalker.java:1300-1301`). Its layer loop (`:1340-1350`) runs each active
layer's models straight through the body's pose stack, and the bounds walk in `walkLayerExtents`
does two things it does not:

- **The wings' back shift.** Vanilla's `WingsLayer.submit` translates by `(0, 0, 0.125)` before it
  submits the mesh, and `walkLayerExtents` takes that shift with it (`:297-303`, `:324`,
  `WINGS_BACK_SHIFT` at `:807`). The dump does not, so on an elytra reference it emits the wings an
  eighth of a block forward of where they are measured and drawn - and the asset side's `[PX]` trace
  carries the shift in the mesh itself (`ElytraMesh.BACK_OFFSET`), so the two traces disagree about
  the wings before either renderer is at fault.
- **The unworn-layer skip.** `walkLayerExtents` skips a layer that declares a `LayerType` and
  resolves no equipment texture (`:283-296`) - the saddle layer on a horse wearing body armour. The
  dump emits that layer's mesh, which vanilla does not draw.

Neither moves a reference: the dump returns at `:1314` when the property is unset, and only a run
that sets it reads either path. What it costs is the trace's one job - telling chain drift from
rasterizer coverage - on exactly the subjects whose layers are placed or skipped conditionally.
It settles when the dump and the bounds walk share one layer loop, so the pose a layer is walked in
and whether it is walked at all are decided once.

## The panel probe crops a canvas-mismatched pair where the sweep pads it

`panel stats` promises to be the shipped metric computed a second time, and on a pair whose
canvases differ it is not. The sweeps that reconcile two canvas sizes - block, entity, entity
animation and walk, item - pad both renders onto the union canvas, each CENTRED at
`((cw - w) / 2, (ch - h) / 2)` (`ParityMetrics.padToCanvas`,
`src/visual/java/lib/minecraft/renderer/store/diff/ParityMetrics.java:124-136`), and take the mean
over that whole union (`EntityParitySweep.java:226-240`). They write the UNPADDED renders as
`vanilla.png` and `java.png` (`:231-232`). `panel._crop` (`parity/scripts/parity/panel.py:45-49`)
reads those two files and crops both to the top-left `min(w) x min(h)`, so it compares a different
pixel set over a different count; its docstring's "exactly as `ParityMetrics.compareImages`" is true
of `compareImages` alone and not of what every padding sweep hands it.

Over the entity tree the cache holds at HEAD (405 subjects), the probe agrees with the table's
`mean_argb_delta` on the 404 equal-canvas subjects and disagrees on the one mismatched one,
`minecraft__zombie~age=baby~elytra=true` (java 190x267, vanilla 200x259): 128.1885 against the
table's 109.4416. Padding both sides centred onto 200x267 in numpy reproduces 109.4416, and all 405.
The bbox the probe reports is taken on the same crop, so on a mismatched pair it clips the larger
side - the case its docstring names it the back-solve for.

`AgreesWithTheJavaOnRealRenders` (`parity/scripts/parity/tests/test_panel.py:108-137`) checks the
first ten subjects, in sorted name order, that the cached table has a row for. On a full run those
are ten equal-canvas subjects from `allay` to `axolotl_cyan`, so it passes and never exercises a
mismatch; after a scoped run, whose table holds only the scoped rows, the ten can include a
mismatched subject and `paritySelfTest` - which `check` and every parity task run - fails on a
correct Java value.

It settles when `panel.stats` pads the way the sweeps do, and the agreement test deliberately
includes the table's mismatched rows rather than whichever ten sort first.

## No test walks the item-texture override with a CIT rule in the stack

`IndexedRendererContext.resolveItemTextureOverride`
(`src/main/java/lib/minecraft/renderer/content/index/IndexedRendererContext.java:236-246`) walks the
stack's CIT rules, skipping every rule whose type is not `CitType.ITEM` (`:241`), and returns the
first match's output with the glint grafted on. `ItemRenderer` is the one renderer that calls it
(`ItemRenderer.java:245`, `:406`), and no test calls it directly. Every item render in the suite
runs against a vanilla stack, whose rule set is empty - the client jar carries no `optifine/` tree -
so the loop body never runs. The one test that builds a context with CIT rules,
`IndexedRendererContextArmorOverrideTest`, calls only `resolveArmorTextureOverride`;
`CitRuleMatchTest` calls `ItemContext.matches` and `RuleLookupGlintTest` the glint lookup, neither
through the item walk. So an edit to the walk - its type test, its first-match order, its glint
graft - fails no case in `./gradlew test` or `slowTest`.

B64 in `blindness.json` says the opposite in one place. Its `source` records that the 18 fast-suite
cases that went red under an inverted `ItemContext.matches` walk a CIT rule "through the item
override, the armour override, the glint lookup or the match itself"; no committed case walks the
first of those. The same `source` later says the item walk was reached only by a probe case outside
the committed suite, and its `reason` lists `IndexedRendererContextArmorOverrideTest` and
`RuleLookupGlintTest` as the grammar's own gate beside the four parser and match tests, neither of
which reaches the item walk.

It settles when a fast-suite case resolves an item override against a stack carrying an item rule
and a rule of another type, and B64's `source` and `reason` name the cases that walk it.

## Toolkit comments state counts and examples the tree does not bear

Several comments in `parity/scripts/parity` argue from a number, a path or a column order that the
tree does not bear. The arguments hold; the evidence they cite does not.

- `reach.py:59-62`, above `ROOTS`: "`test` runs 1325 tests to write four self-captured rows".
  `test` writes eight - `digest.shipped-tables` and the seven pins
  (`src/visual/java/lib/minecraft/renderer/store/ParityArtifacts.java:154`, `:159-165`) - and the
  fast suite is past 2000 tests.
- `blindness.py:9-12`: a derived rule's one glob answering per class is illustrated with "under
  `engine/**` a pose kit reaches the entity sweeps where a model engine reaches every render". No
  rule fires on `engine/**`: B19 carries nine engine sub-package globs, none of them `engine/pose`,
  and names `bake/pose`'s `ClipPlayer` and `PosePlayer` verbatim; no type in either package is a
  kit. A glob that does answer two ways is B19's `src/main/java/lib/minecraft/renderer/*`, where
  `FluidRenderer` plans 2 artifacts and `Renderer` 17. `:15-16`'s "the two dump manifests still
  fall off an engine change" does not hold for `engine/pose`, where eight of the eleven types plan
  both dumps.
- `blindness.py:60-70`, the worked examples of the removal passes. B37 does not fire on
  `BlindnessMapTest.java` (its triggers are the `store/**` and `dump/**` trees; the file is under
  `guard/`), so that path's empty `sees` comes from B39 alone. No `GeometryKit.java` exists; a
  file where B10 claims `sweep.block` blind and B19 selects it is `engine/mesh/BoxKit.java`. On
  `PlayerRenderer.java` B9 does not merely claim `sweep.player` - it is in B9's `sees`, and the plan
  selects it; `bake/texture/TrimKit.java`, where B23 claims `sweep.block` and no fired rule selects
  it, is the example the bullet describes.
- `sweep.py:3-6`: "`mean_argb_delta` is column 3 in `sweep.glint` because column 2 is `frames`".
  `GlintParitySweep` writes `subject, mean_argb_delta, status, frames, ...`
  (`src/visual/java/lib/minecraft/renderer/sweep/GlintParitySweep.java:193-194`); the shape with
  `frames` second exists only in the fixture `tests/data/sweep-glint.tsv`, which is what keeps the
  by-name reader honest.
- "the six sweeps" at `sweep.py:172`, `norm.py:15` and `ids.py:4`: `sweep.SWEEPS` holds nine, and
  nine `*-parity-vanilla` trees write a `parity-report.tsv`.
- `promote.py:414-418`: the count-member rule "reproduces all fourteen promoted rows exactly", and
  `manifest.tooling-tables` "still reads 10 where the gate joins 18". The index holds 29 artifact
  rows, and the tooling-tables payload carries 11 `files` and 8 `logs`, so its row reads 11.

It settles when each comment either states what the tree holds or drops the number, preferring the
second wherever the number is one the next change moves.
