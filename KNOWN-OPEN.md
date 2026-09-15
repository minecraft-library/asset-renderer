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

## What a compile rule may read is declarable and when it may run is not

A rule that takes only the script and the diagnostics cannot read a mesh, and the compiler enforces
that much. It cannot express the other half - that a rule about what the author wrote must not be
reached only for an address the subject happens to carry - because that is a property of the call
site and not of the signature. A rule can satisfy the first perfectly and violate the second.

**The two that do are the double-wave refusal and the duplicate-keyframe refusal.** The first claims
a channel's wave slot inside the verb fold, which a limb reaches only through a stance whose address
the mesh answers. The second reads the accumulated keyframes, which only a placed bone fills. Both
are facts about verbs the author wrote beside each other, and both go unsaid on a subject answering
the address with nothing.

Neither is a hoist, and the measurement that says so is why this stays open. **Both are gated by a
drop, and a tolerant install exists to absorb that drop** - so a check moved above it refuses on
both install paths, and an address the row does not declare stops dropping quietly. Two sentences of
the authoring builder's own javadoc promise that it does. Closing either violation is therefore a
change to what a tolerant install means rather than a relocation, and it falsifies shipped
documentation on its way past.

**And neither fix closes its own split.** A wave rule keyed per stance misses the same address
written across two stances - four spellings, every one of them decidable from the script alone -
and keying on the address together with whether it is anatomical is what closes those. The keyframe
half carries a residue that cannot be closed from where it sits at all: the fold measures frame
times against the TARGET ROW's catalog period, so one chain refuses on a row of one period and
installs clean on a row of another. Reading the script's own window instead is exactly what the
closure rule a few lines away already does, and taking it here buys a refusal of chains that are
correct on the row they land on.

A marker annotation and a three-way type split were considered and put down: the first states the
property without checking it, the second multiplies types to express a scheduling fact. Reflection
was put down for a reason that does not hold - the pose test tree has none, but a source-scanning
test needs none either and this tree already runs one. What no instrument reaches is the open half:
whether a refusal's predicate is a fact about the script is a reading of the predicate, and no
signature, annotation or scan exposes a reading. So what is open is whether the convention wants any
guard at all, or whether a handful of rules in one place is few enough that review is the mechanism.

## A compile hands back the parent of the scope it recorded into

The compile writes every one of its lines into a child scope opened for the purpose, and the result it
returns exposes the parent. Reading the parent reaches the child's entries as well, so nothing is lost
today - but a caller asking the result what the compile said is answered by a scope that also holds
whatever else was recorded under the same root.

Returning the child instead was considered and is not a pure move: it changes what the registrar and
the audit see, which makes it a behaviour decision rather than a tidy-up. What is open is which of the
two a caller is entitled to, and the answer is worth writing down either way, because the field it
would change is read exactly twice in a seventeen-hundred-line class and a reader has no way to tell
the choice from an accident.

## A sixth arm on the sealed expression type would be skipped in silence by five walks

The expression type's own javadoc names its five arms as a count something depends on. Five walks over
it carry a default arm and so opt out of exhaustiveness checking: a sixth record would compile clean
and be silently skipped by all five, which means the driven-field scan stops descending under the new
node and the interner pools it without pooling its children. Four other walks would force the author to
think, because they are exhaustive with no default.

Landing a rule that forbids the default arm was considered and put down for ordering rather than merit:
every one of the five sites is in one family of walks, and the unification that collapses that family
deletes all five, so writing the rule first writes lines the unification then removes. What is open is
whether this package expects a sixth arm at all, and what a guard against a change nobody is planning
is worth - the five arms have not changed since the type was written, which is evidence for both
readings and settles neither.

## Nothing measures what rebuilding a roster per stance costs

The roster is derived inside a loop over stances, twice, and each derivation walks the whole mesh's
chain transforms plus work quadratic in the leg count. The far cheaper interner pool beside it IS
cached per entity. The seat derivation is quadratic in bones times states and is cached nowhere, and it
cannot be cached per mesh the way the roster could, because it takes the pose and the install rewrites
the pose.

Caching the roster per mesh was considered and is held for the commit that measures it, which is the
honest order: nobody has run a bench or a counter, so the cost is a shape rather than a number. What is
open is whether it matters at the sizes this actually runs at - an install is a handful of stances over
a mesh of tens of bones, and a cache is a field and an invalidation question.

## Three types in one package record an absence three different ways

One writes free prose into a record component that a caller reads back; one records nothing at all when
it refuses, with no diagnostics channel reaching it; one funnels every absence through the shared
recorder. None of the three knows about the others, and the middle one's class javadoc argues its
tolerance at length without ever saying that a near miss goes unrecorded.

Routing the first through the recorder was considered and is blocked mechanically - it is derived by a
static with no scope to record into, called from a site that has none either. Routing the second through
it hits the same wall. What is open is whether the silence is a choice or an omission, which cannot be
told from the file, and whether the prose-in-a-component shape should exist at all: rewording it makes
a test that asserts over its substrings vacuously true, and the test stays green.
