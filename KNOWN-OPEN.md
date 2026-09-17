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

`PoseStyle` names no bag at all - `appliesTo` takes the appearance rather than the request carrying
one - so the row type is answerable for a subject whose options are not an entity's, and a player
catalog shipping age-free rows never reaches it either way. What still spells `EntityOptions` is
`StyleCatalog.resolve` and the private `byId` overload behind it, which is where the axis would have
to grow a shape the player bag can answer. `PlayerOptions` carries no appearance today, so that is
the second thing a knob would need and not only the first.

## Twelve face lookups each carry the substitution answer as a bare boolean

`MissingTexture`'s three lookups take a trailing `boolean substituting`, and the twelve call sites in
`BlockRenderer` and `ItemRenderer` each pass it. That the answer travels from the render is forced -
the substitution cannot be keyed on the texture id, because a block model's face load and an
entity's carried-block overlay see the same string and need opposite answers. What is open is the
shape it travels in: a four-argument call ending in a positional boolean.

**Four of this entry's own premises were measured and are wrong. It is re-stated here on what the
tree actually holds, because the decision it asks for cannot be taken on the old ones.**

- *"a reader has to know the callee to know what `true` means"* - no production call site passes a
  literal. All twelve read a named field, a named local, or the option getter; `true` and `false`
  appear only in the two test files. Nobody is reading a bare literal and guessing, so the defect is
  the weaker one of a positional boolean rather than an unreadable one.
- *"a constructor field on the two renderers, reverted once its cost showed"* - a constructor field
  is in the tree today on the block side and six of the seven block sites read it. It was refused
  for the ITEM side alone, where four of the five lookups sit in statics no instance field reaches.
- *"a returned value cannot raise on the caller's behalf, and that rules out a whole family at
  once"* - false. The third lookup returns a function whose body raises when applied, and a test
  asserts exactly that. The sentence is true of an eager carrier and false of a returned lookup, so
  it rules out nothing of the kind.
- *"Both were evaluated against exactly this and neither survived it"* - no commit, branch, stash or
  reflog in this repo carries any of the three shapes the entry says were tried. This entry is their
  only record.

What survives is a taste question with a measured price. A two-constant enum nested inside
`MissingTexture` keeps the arity and makes a mis-threaded polarity a compile error, and it pays only
if the block field and the two item locals are retyped with it - otherwise the three sites reading
the getter inline get longer rather than clearer. Nesting is not optional: a new top-level type under
the engine tree is a path the reach graph has never heard of, and a plan over it refuses outright
until the graph is rebuilt.

**The gate is the reason this is not a free afternoon.** The three types reach ten artifacts, and the
swap is byte-neutral by ARGUMENT rather than by construction - the compiler takes an inverted
constant exactly as it takes an inverted boolean. `check` cannot see a flipped site, because the
renderer-level proof that all twelve substitute lives in the slow suite. So the honest acceptance is
the slow suite read on its own exit code, with the capture bundle as secondary confirmation.
