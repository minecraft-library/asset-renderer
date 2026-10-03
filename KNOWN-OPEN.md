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
