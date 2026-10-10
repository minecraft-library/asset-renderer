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

## Block icons the block sweep cannot compare

The reference harness loads no resource pack, so a block item a pack roots at a component test, as
Hypixel+ roots 87 of them, has no ground truth.

The harness also draws a reference for each `block_state` case of a block-model icon: `beehive` and
`bee_nest` at `honey_level=5`, and `test_block` at `mode=log`, `fail` and `accept`. The renderer
does not answer a `block_state` select yet. `ItemModelContext.selectValue` leaves it empty, so the
walk takes the select's fallback even for a stack carrying the component, and a full hive draws
empty. Those five references have no renderer-side row: `BlockParitySweep` pairs a reference with a
block id, and their names match none.

## An entity's carried block the index does not know draws nothing and records nothing

`EntityRenderer.buildBlockOverlayTriangles` draws a carried block only where the context's `findBlock`
answers present. It tests `Possible.isEmpty()`, which holds for an absent block and an empty one alike.
A registered block that draws nothing is no miss and rightly draws nothing, but an id the block index
does not know takes the same branch. That makes it the one absent block or item lookup that draws no
missing picture where every other draws the missing model, and it reports nothing either: no line on
stderr and no `Substitution` in the render's result.

Telling the two apart is a `getState()` switch. Drawing the missing cube on the absent arm is a pixel
change with a gate of its own, and recording a stand-in there without drawing one would name a picture
the image does not show, so the drawing and the record move together.
