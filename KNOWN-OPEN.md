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

## A gait's row-keyed numbers land on nothing in silence where the mesh HAS the row

`phase(Rank, cycles)` and `gain(Rank, factor)` are numbers on a cycle rather than addresses, so
they resolve nothing and have no empty resolution to report. Where the rank names a row the mesh
does not carry, that is now reported - the reading joins the written bones the mesh does not
declare, so a strict install refuses and names it. Where the rank names a row the mesh DOES carry
and no shape was stamped on it, nothing is reported and nothing can be: the number is correct about
a row that exists, and the only thing wrong is that no shape reached it.

The two shapes that would catch it were considered and put down. Requiring a gain to name a rank
some `step(Rank, ...)` also names is checkable against the script alone, but it cannot see the
unranked shape stated over every row, which is the spelling the verb exists for. Asking instead
whether any shape reached a leg of that row does see it, and it is neither order-dependent nor stuck
in the emission fold: the sequences the fold walks are already total and fixed, and the question is a
set question the roster answers once, before any member is stamped, beside the rule that records a
rank the mesh carries no row for. What holds it down is that the reading would join the written bones
the mesh does not declare, so a strict install would refuse a chain that is correct about a row that
exists - and there is no way for an author to say so short of turning the whole install tolerant.

The same gap covers `plant(share)` beside a shape carrying no excursion, and that half is simpler
than it looks: a plant reshapes triangles into trapezoids and reaches nothing else, so it is inert
exactly when no timeline carries a swing or a bob. That is a fact about the script with no mesh in
it, and it would sit beside the bound the plant share is already held to.

What is open is whether an inert number is worth catching at all. No style in the tree writes a
gait yet, so there is no evidence about how often an author strands one.

## The implicit hat mirror turns on reference identity and only one end of it says so

A hat stance the build copied from the head shares the head's fragment instances, and that sharing is
what the compile reads to tell an implicit hat from an authored one. The copy's own javadoc says it
shares by reference and never says that anything downstream turns on it; the compile's says it detects
the automatic copy and names no producer. The two live in different packages and neither names the
other, so a refactor that rebuilds a stance at either end severs the link with nothing red - the hat
silently stops being mirrored under a tolerant install, and starts being refused by name under a strict
one.

Value equality was considered and put down: it cannot separate what the build made from what the author
wrote, which is the only question being asked. Documenting the contract at the producing end is cheap
and is what the scaling rule beside it already does for its own identity dependency. What is open is
whether a comment is the right instrument at all, or whether the relationship wants to be carried as
data - a flag on the stance saying the build made it - which trades a silent break for a component
every reader has to account for.

## Whether an entry's severity is part of the library's contract

The registrar exposes its diagnostics publicly, so a consumer can count entries of a given severity and
branch on the answer. The class javadoc says nothing recorded is load-bearing, which reads as permission
to reclassify freely; what is actually true is narrower - nothing recorded reaches a shipped byte. Those
are different claims and the second does not license the first.

Pinning the severities was considered: it means an assertion per recording site, twenty-one of them, and
it freezes a vocabulary that is still being written down. Leaving it unstated was considered and is the
status quo, which is how a reclassification can reach a consumer as a behaviour change that no note
calls one. What is open is which of the two the class doc should say, and the answer decides whether a
future severity change is a free edit or a compatibility event.

## A file-mode diagnostics sink is reachable through the registrar and writes nothing

The registrar accepts a file target, the sink knows how to write one, and no production code ever calls
the flush that does it. The registrar is not closeable and has no close, so a consumer who asks for file
output gets an empty result unless they discover the flush themselves, and no javadoc says so. The
generator side has no equivalent hole, because its session flushes on close.

Three fixes were considered and all three are larger than the change that found this: making the
registrar closeable, flushing once per install, and documenting the requirement. What is open is whether
file output is a capability anything wants at all, which decides between wiring it up and removing the
target: it has no production caller and no end-to-end test either way, so nothing in the tree answers
the question.

## Three refusals about what an author wrote are reached only for a bone the mesh declares

The raw lowering skips a raw whose bone the subject does not declare, recording the drop, and only then
checks the expression. Three of the four checks that follow read no mesh at all - an inexact literal for
the declared width, a field read from another style's namespace, an operand count the operator does not
take - so the same authored mistake refuses on one subject and installs in silence on another. That is
the corpus split the compile has been caught on twice in other guises.

Moving the expression check above the drop was considered and is not a pure move: it changes which
refusal an author sees when both faults are present, which is a behaviour decision rather than a
relocation. What is open is whether it is worth repairing at all, given that no test constructs the
case and the fault it hides is one the author would hit on the first subject that carries the bone.

## What a compile rule may read is declarable and when it may run is not

A rule that takes only the script and the diagnostics cannot read a mesh, and the compiler enforces
that much. It cannot express the other half - that a rule about what the author wrote must not be
reached only for an address the subject happens to carry - because that is a property of the call site
and not of the signature. A rule can satisfy the first perfectly and violate the second, and two of the
package's checks do exactly that today.

A marker annotation, a reflection-based architecture test and a three-way type split were all
considered and put down: the first states the property without checking it, the second needs reflection
the test tree does not have, and the third multiplies types to express a scheduling fact. What is open
is whether the convention wants any guard at all, or whether six rules in one place is few enough that
review is the mechanism.

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

## Whether two overlapping blindness globs resolve most-specific-first has not been read

The pose region is claimed twice - once by a subtree glob over the whole package and once by four
package globs over its sub-packages - and the subtree glob matches every path the four do. Both rules
are derived, so each resolves per file to whatever the reach graph answers, and for every file in the
sub-packages that is the empty set; the overlap therefore changes which rules a plan names and not what
it prices.

Reading the toolkit's resolution order was considered and not done, because nothing in this pack turns
on it. What is open is what happens when the two rules ever disagree - which they cannot today, both
being derived - and the question becomes live for any design that moves a type between the four
sub-packages, because the four-glob rule exists precisely so that such a move moves the type's own
answer.
