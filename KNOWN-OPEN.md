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

## Whether an adult axolotl can play dead decides the (id, age) pair mechanism

The catalog allows two rows sharing one id with disjoint `age` members - coined solely for the
axolotl's `play_dead` (baby clip row vs adult factor row), with `appliesTo` disambiguating at
resolve. If vanilla adult axolotls never play dead, the adult factor row is a fiction: drop it,
and the per-(id, age) uniqueness relaxation collapses back to plain per-entity id uniqueness.
Check the vanilla behaviour before building anything else on the pair mechanism.

## The pose presets and the animation knobs are one question answered in four places

**Stated as a goal by the owner, and deliberately not built yet.** The intended end state is two base
states and nothing else:

- **`BIND` (static)** - no animation, one PNG.
- **`ANIMATED`** - every selector and knob merged into one statement, "the output moves in some way".

What is there instead is four vocabularies that grew in the order the problems arrived, and each one
answers a different half of the same question:

- `EntityOptions.PoseMode` - `BIND` / `IDLE` / `WALK`, which decides which figures stop resting.
- `IdleFigure` - a scalar its own vanilla arithmetic bounds, swept across one strip.
- `IdleState` - a one-hot over a selector, chosen per group.
- `AnimationOptions` - which was built to describe the OUTPUT FILE, seed tick, frame count,
  ticks-per-frame and playback schedule, and now also carries the two idle maps.

`PoseMode` predates `IdleFigure`, `IdleFigure` predates `IdleState`, and the animation options
predate all three and were about a GIF rather than about an entity. **The ender dragon is the example
that makes it plain**: it has `BIND`, `IDLE` and `WALK`; its `IDLE` moves because an `IdleFigure`
drives its wing phase, and its `WALK` moves because a preset stops two other figures resting. Both
are the same request - *show it animated doing X* - and the work of saying so is spread across a
preset enum, a scalar roster and an options bag.

**`IdleState` growing entity-specific members is a symptom rather than the disease**, and it stays as
it is until this is answered. A one-hot over a vanilla selector is the right shape for what it does;
what is wrong is that it sits beside three other ways of saying the same kind of thing.

**Sequencing is the whole point and it is deliberate: coverage first, unification second.** A design
drawn now would be drawn against the subjects that happen to animate today, and the sample is still
growing - the walk gate landed a reference set, the walk-clip exclusion landed after it, and the
subjects a stride reaches are only now measurable. Whoever takes this should take it when most
entities animate, not before, because the shape of the answer depends on what the roster of animated
behaviours actually turns out to be.
