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

## Whether a near miss the seat derivation refuses is worth recording

The seat derivation takes no diagnostics channel and records nothing when it refuses a follower, so a
chain that misses the residual bound by a hair is indistinguishable from one that was never a
candidate. Its class javadoc argues the tolerance at length and its tolerance field carries the nine
measured shares and the four near misses, and neither says that a near miss goes unrecorded.

The claim that this is blocked mechanically does not hold: the busier of its two production call
sites holds a scope and uses it eleven lines below the derive. What is open is whether the silence is
a choice - a derivation answering a question nobody asked is not an absence worth a line - or an
omission, which cannot be told from the file either way.
