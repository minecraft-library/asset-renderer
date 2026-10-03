# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, `TEXEL-RULES.md`'s, or `tooling/CLAUDE.md`'s. A measurement belongs in
the commit that made it, and in the `reason` recorded with the baseline it moved.

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

## Pack models outside `models/block` and `models/item` are never read

Vanilla reads every model file under a pack's `models/` tree, and an item definition may name any of
them. `ResolvedModels` reads only `models/block` and `models/item`. When an item's definition
resolves to a model anywhere else, the lookup misses and the item renders as its plain item, as
though the definition had named no model - and nothing reports it.

Hypixel+ 0.23.4 for 1.21.8 is built that way: 325 of its 328 item definitions, all of them for
vanilla items, name models under `hplus:skyblock`, `hplus:ui`, `hplus:bedwars` and `hplus:murder` -
5987 references to 4832 distinct models, every one of which the pack ships. No stored parity
artifact renders through it.

None of those references is reached today anyway, for a second reason. Each sits behind a test on
the item's components - a `minecraft:component` condition, or a `select` on the item's custom name or
dye colour - and the dispatch walk treats every such test as failed. So reading the wider tree alone
changes no Hypixel+ render.

Deciding it needs two answers, best taken together: whether the item model lookup reads the whole
`models/` tree as vanilla does, while the item index - one item per `models/item` file - stays on
that subtree; and whether the dispatch walk evaluates component tests, which is what lets a caller
reach those branches at all.

## The entity vertex chain rounds a corner in a different order from vanilla's

Java computes an entity's screen corners with vanilla's transforms but not in vanilla's float order,
so a corner lands a few float steps from vanilla's. That only shows where a corner sits within a few
hundredths of a `1/256` step of a snapping midpoint, where the card snaps vanilla's corner one way and
java's can go the other (`TEXEL-RULES.md`'s *What the renderer does not reproduce*). The difference
has two parts:

- **Constant per subject.** Vanilla folds the canvas offset into the matrix first, so every later
  translate - the renderer's preamble, each bone's offset - rounds at hundreds of pixels, and the
  offset itself is rounded in float; java centres once, at the end. Each subject's bounds walk rounds
  its own way too. Those shift every corner of one subject the same way, which is why most subjects'
  midpoint flips go one way: 11 of the 16 canvas and axis groups with two or more flips.
- **A lever per corner.** Java builds the iso rotation as `rotationXYZ(30°, 225°, 0)` then flips two
  axes; vanilla's is `rotationXYZ(210°, 45°, 0)` then an exact half turn about y. The entries differ in
  their last bits - java's own rounding is about seven tenths of the difference - and the difference
  grows with a corner's distance from the point the canvas is centred on.

The ender dragon shows both at their largest: its canvas is centred between its wing tip and its jaw,
59 model pixels off its own centre line, so the lever adds to the constant and vanilla's corners sit
about `0.024` of a grid step lower in the image on average. A float32 replica of vanilla's chain - its
rotation, the offset folded in first, each translate in JOML's own order with no fused multiply-add -
picks the card's snap for all 450 measured dragon corners where java's own picks 437; on the 13 where
the two chains part, the card sides with vanilla on every one.

Deciding it needs two answers: whether the entity path should build its corners by vanilla's chain,
which replaces java's fit-and-centre step for every entity rather than for the dragon alone; and
whether any stored row depends on these corners enough to pay for it. The other subjects have no
replica yet, and a renderer scale - the elder guardian's `2.35` - re-rounds the rotation entries, so
each needs its own before its flips can be called.
