# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## Four held-pose style rows are measured constant, and their names say they should move

The style emitter measures frog `jump`, bat `rest` and both axolotl `play_dead` selections
(baby clip, adult factor) as constant over the whole period - every clip channel one distinct
keyframe value - so they ship as held poses (`sources: []`, a distinct but motionless render).
Their vanilla names say they animate. Either the vanilla clips genuinely hold a pose and the
selection's motion lives somewhere the walk does not carry, or the offline measurement (clip
constancy, the time-axis rule, or the extraction of those clips) is wrong. Investigate against
the client; if they should move, the fix is in the measurement or the keyframe walk, and the
rows re-emit with real sources.

## PlayerOptions has no style knob, and coining one needs a catalog source for the player

The style axis is a string knob on `EntityOptions` resolved against the entity's shipped catalog.
The player renders through its own pipeline, holds no row in `entity_models.json`, and its sweeps
gauge look rather than bytes - so a style knob on `PlayerOptions` today would be a string with
nothing to resolve against. Deferred deliberately by the owner (2026-09-01), with the axis kept
collision-free: adding the knob later needs a player-side source of catalog rows, and the one
coupling to the entity bag is `PoseStyle.appliesTo(EntityOptions)` - a player catalog either ships
age-free rows, which never call it, or that member grows a shape the player bag can answer.
Everything else on the axis - `resolve`, `frameAt`, the drivers, `PoseKit.frames` - is already
bag-agnostic.

## Whether an adult axolotl can play dead decides the (id, age) pair mechanism

The catalog allows two rows sharing one id with disjoint `age` members - coined solely for the
axolotl's `play_dead` (baby clip row vs adult factor row), with `appliesTo` disambiguating at
resolve. If vanilla adult axolotls never play dead, the adult factor row is a fiction: drop it,
and the per-(id, age) uniqueness relaxation collapses back to plain per-entity id uniqueness.
Check the vanilla behaviour before building anything else on the pair mechanism.

## Twelve face lookups each carry the substitution answer as a bare boolean

`MissingTexture`'s three lookups take a trailing `boolean substituting`, and the twelve call sites
in `BlockRenderer` and `ItemRenderer` each pass it. That the answer travels from the render is
forced - the substitution cannot be keyed on the texture id, because a block model's face load and
an entity's carried-block overlay see the same string and need opposite answers. What is open is the
shape it travels in: `MissingTexture.textureAtTick(this.context, part.texture(), tick,
this.substituting)` is four arguments of which the last reads as a flag rather than as a question
about that texture, and a reader has to know the callee to know what `true` means.

It is not a cost problem, which is the trap in re-opening it. Threading the flag costs **zero**
extra parameters: every item helper already holds `ItemOptions` and the isometric assembly already
holds the render's options, so the answer rides on what was there. Three shapes have been tried and
put down - a constructor field on the two renderers, reverted once its cost showed on the item side;
a per-render record pairing the context with the flag, built and gated green and then walked back as
a top-level type holding two values; and widening the port's own lookups, which moves the ternary to
the call sites rather than removing it.

**A return type cannot take this off the call sites, and that rules out a whole family at once.**
The flag chooses between drawing the checkerboard and raising, and a returned value can report that
a texture is absent but cannot raise on the caller's behalf - so neither a carrier record bundling
the buffer with what is known about it, nor a container distinguishing absent from empty, removes a
single one of the twelve. Both were evaluated against exactly this and neither survived it. Anything
that closes this is on the calling side.
