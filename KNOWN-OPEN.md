# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## The two diagnostics sinks are one sink, and the only place to share it is published

`Diagnostics` and the generator's `Diagnostics` are 78 stripped code lines each and agree on
every one of them, once each side's name for the type and for the root factory's parameter is folded
together. What genuinely differs is the exception policy alone - neither build can name the other's
throwable - and a mirror test now holds all of that, so the pair no longer drifts unseen. What is
open is whether to stop maintaining two.

**The obstruction is where a shared sink could live.** The builds share exactly one module,
`client/`, and the renderer takes it `api(...)`, so a sink moved there joins the published JAR - a
one-way door for a type that is a diagnostics channel and nothing a consumer asked for. It is also
conditional on the package: a path in `lib.minecraft.renderer.client` plans four ids and three
captures, and a path anywhere else under `client/` refuses the plan outright until a blindness rule
is coined for it. The other shared leaf cannot host it at all - `parity/` is `compileOnly`
source-retention annotations and carries no runtime class.

**And a shared sink needs an exception policy neither build has.** The renderer raises
`IllegalStateException` and `UncheckedIOException`; the generator raises its own `ToolingException`,
which is deliberately not under the renderer's root so a batch renderer's skip-and-continue cannot
swallow it. A third policy would have to be invented for the shared type and then read correctly by
both, which is the part no line count surfaces and the part worth thinking about before anything
moves.

Take it the next time a `tooling/**` change is being gated anyway, when the flow re-run it owes is
already being paid for. Not on its own.

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
