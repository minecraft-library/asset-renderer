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

## An item model built from elements draws blank as a GUI icon

Some item models are built from 3D elements rather than flat layer sprites. Held, they draw. In a
slot they do not: the slot draws a model's `layer0`, `layer1` and so on, and a model built from
elements has none. So where an item definition picks such a model, `GUI_2D` draws an empty slot for
an id the item index carries and the missing square for a block-backed id it does not, and
`GUI_ICON` keeps the block's own icon where it draws the id as a block and is empty otherwise. Each
such model is reported once through `Substitutions.flatIcon`. Vanilla draws the model itself in the
slot, posed by its `display.gui` and lit as its `gui_light` says.

Drawing it needs a slot path that draws elements, and `gui_light` read into `ModelData`, which does
not hold it today; a new `ModelData` field is a change to what both pipeline dumps record for every
model. Hypixel+ picks models like this from a stack's components, on items the index carries and on
block-backed ones alike.

## A composite draws only its first child

A `minecraft:composite` node draws all of its children, one over another, in vanilla. The walk here
answers one model, the first child that resolves to anything, so the children after it never draw,
in a slot or held, and a model only those later children name draws for no stack. Hypixel+ builds
some of its items this way.

Drawing them needs the walk to answer an ordered list of models rather than one, and both render
paths to draw that list.

## The item index names a nested `models/item` file by its file name alone

`ItemIndexBuilder` builds one item per `models/item` file and names it by the last part of the
file's path. Two files in different folders under `models/item` that share a file name therefore
become one item, the later file winning, and the other file's model is in the index under no name at
all. The hypixel-skyblock sample pack ships two `fine_opal_gem.json` files, one under `collections/`
and one under `slayer/`, and its index row for `fine_opal_gem` carries only one of them; the eureka
pack ships pairs like it too.

Keying the index by the whole path would change what the packs dump records, which is why it is not
folded into another change.

## Component tests the walk still cannot answer

An item definition tests a stack's components, and the walk answers a `custom_data` test, a test
that a component is present, and a select on `custom_name`, `dyed_color` or `lore`. What it still
cannot answer:

- **The fourteen other predicate types** vanilla registers - `damage`, `enchantments`, `trim`,
  `potion_contents` and the rest. Each reads item state or registry contents this renderer does not
  model, so each test fails and the walk takes `on_false`.
- **An item's default components.** Vanilla reads a stack's own components over the ones the item
  holds by default. Only the stack's own are known here, so a `has_component` test, or a presence
  test, of a component the item holds only by default reads as absent.
- **An unknown predicate id.** Vanilla refuses a definition whose predicate names neither a
  predicate type nor a component it knows. Here any such id is read as a presence test, there being
  no list of component ids to check it against.
- **A select on any other component**, which takes its fallback.
- **A special model's own fields**, which are not checked. Hypixel+'s `red_bed` names a bed special
  with no `part`, which vanilla refuses as a whole definition; it loads here.

The default components and the unknown id need a table of every item's default components and the
26.1 component-id list, both deferred by the owner. The rest each need vanilla's own reading of what
they test - a predicate type's value and the state it reads, another component's value, a special
model's fields.

## A plain icon draws the item's `models/item` model, not the one its definition names

With no stack, or a stack that picks no branch, an item with a `models/item/<id>.json` file draws
the model the index built from that file, without walking its definition. Vanilla draws the model
the definition's walk lands on. The two agree for every vanilla item, whose definition lands on its
own `models/item` model or on a special drawn over it. They part only where a pack points an item's
plain branch at another model: the icon keeps the `models/item` model, and the pack's choice draws
only once a context or a stack sends the render down the walk.

## Derived item animation ignores the branch a stack picks

A render that asks for its timing to be derived takes it from the first `minecraft:time` dispatch
anywhere in the item's definition (`ItemModelNode.timeDispatchSteps`), not from the branch the stack
picks. So a stack that picks a branch with a time table of its own, or with none, still animates by
the first table in the definition. "Time-driven item icons" in `RENDERER-RULES.md` requires the
search over every branch, because the clock keeps its table behind a select no icon can answer, so
following the stack's branch instead reverses a written rule. Hypixel+'s clock is such a definition:
a custom-name select ahead of its time tables picks a still calendar icon, so a derived render of a
clock carrying that name bakes a whole day of frames, every one of them the calendar.

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

## The harness's block icons read through a dispatch match the block's default state every time

The reference harness takes a block item's icon from vanilla's own item walk: where
`items/<name>.json` roots at a dispatch whose plain branch names a block model, `BlockIconGeometry`
reads the model through vanilla's `ItemModelResolver` rather than drawing the block's default state.
Only `beehive`, `bee_nest` and `test_block` take that route, and for each the model the walk lands
on is the one the default state draws, so each reference is byte-identical to a draw of the default
state. No reference shows the route drawing anything the default state would not, so nothing yet
proves it on a case where the two differ. The harness loads no resource pack either, so a block item
a pack roots at a component test, as Hypixel+ roots 87 of them, has no ground truth.
