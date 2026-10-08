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

## The item index names a nested `models/item` file by its file name alone

`ItemIndexBuilder` builds one item per `models/item` file and names it by the last part of the
file's path. Two files in different folders under `models/item` that share a file name therefore
become one item backed by one of them, and the other file is drawn only where an item definition
names it. The hypixel-skyblock sample pack ships two `fine_opal_gem.json` files, one under
`collections/` and one under `slayer/`, and its `fine_opal_gem` item carries only one of them; the
pack shares five file names this way, and the eureka pack shares 105.

An item definition finds the model its walk lands on by the whole model id, as vanilla does, so
each of the pack's two `fine_opal_gem` definitions draws its own file. What stays open is the item
named by the file name alone, which no vanilla stack reaches: vanilla keeps every `models/item` file
directly under the folder, where the file name is the item id, and these packs name each definition
by its whole path, so no definition shares an id with one of these items.

Naming these items by their whole path gives them ids no lookup and no definition uses, and
dropping them leaves a model no definition names drawn under no id at all. Either changes what the
packs dump records.

## Component tests the walk still cannot answer

An item definition tests a stack's components, and the walk answers a `custom_data` test, a test
that a component is present, and a select on `custom_name`, `dyed_color`, `lore` or `item_model`.
Of the components an item holds by default it knows one, `item_model`, which every 26.1 item holds
as its own id. What it still cannot answer:

- **The fourteen other predicate types** vanilla registers - `damage`, `enchantments`, `trim`,
  `potion_contents` and the rest. Each reads item state or registry contents this renderer does not
  model, so each test fails and the walk takes `on_false`.
- **An item's other default components.** Vanilla reads a stack's own components over the ones the
  item holds by default. Beyond `item_model` only the stack's own are known here, so a
  `has_component` test, a presence test or a select on a component the item holds only by default
  reads it as absent.
- **A select on any other component**, which takes its fallback.
- **A special model's field values.** A field the kind requires must be there, but its value is not
  decoded, so a bed whose `part` names neither half loads here where vanilla refuses the definition.

The other default components need a table of every item's defaults, which the game binds only when
it loads its registries; the owner has deferred that table. The rest each need vanilla's own reading
of what they test - a predicate type's value and the state it reads, another component's value, a
special model field's value.

## A refused definition draws a magenta tile in a sheet that would rather drop it

A definition the loader refuses, and a select or range dispatch that falls back to nothing it
declares, draw vanilla's missing item model whatever `substituteMissing` says: both arms draw it and
neither refuses, because it is what vanilla draws rather than a stand-in for something the pack
lacks. An atlas turns the substitution off so that a sheet is short a tile rather than carrying a
magenta square that looks like an asset, and its block pass draws every block through the slot icon.
So an atlas over a pack that breaks a definition - Hypixel+'s `player_head`, which nests past the
JSON reader's limit - still carries a magenta tile for it. Whether the refusing arm should refuse
here is the owner's open decision.

It waits on the planned adoption of `dev.simplified.util.Possible` - absent, empty or present -
across the renderer's lookups, because that is the same three-way split. The walk already carries it
by convention: a branch that names a model; a branch declared empty that draws nothing
(`ItemModelNode.Empty`, `Resolution.NOTHING`, `FrameItem.Nothing`); and a branch that is absent, a
refused definition (`ItemModelTree.isRejected`) or an undeclared fallback (`ItemModelNode.Absent`,
`Resolution.MISSING`, `FrameItem.MissingItemModel`). The adoption as planned has an empty subject
draw a transparent frame on both arms and an absent one - a fluid stand-in, say - keep the missing
picture and refuse; it also sends a node type from a foreign namespace to the missing item model,
which widens what this entry covers. It keeps the refused definition's both-arms drawing as it
stands. When it lands, check whether the walk's absent state answers as an absent subject does -
refusing with `substituteMissing` off - and whether the leaf miss (`FrameItem.MissingModel`, which
refuses) and the refused definition, which vanilla draws as the same model, still differ for a
reason the code states.

## Block icons the block sweep cannot compare

The reference harness loads no resource pack, so a block item a pack roots at a component test, as
Hypixel+ roots 87 of them, has no ground truth.

The harness also draws a reference for each `block_state` case of a block-model icon: `beehive` and
`bee_nest` at `honey_level=5`, and `test_block` at `mode=log`, `fail` and `accept`. The renderer
does not answer a `block_state` select yet. `ItemModelContext.selectValue` leaves it empty, so the
walk takes the select's fallback even for a stack carrying the component, and a full hive draws
empty. Those five references have no renderer-side row: `BlockParitySweep` pairs a reference with a
block id, and their names match none.
