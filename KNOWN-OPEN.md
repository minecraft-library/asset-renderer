# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## A driven flag channel has no symbolic form, so one clip gate stays unreachable

Every render-state field the pose walk keeps symbolic arrives at render as a number, which is what
let the animation states onto the driven roster without a second mechanism. A bone's VISIBILITY does
not: every flag folds to a literal where the table is written, and there is no channel for one the
fold cannot settle.

`FrogModel.setupAnim` reads `croakAnimationState.isStarted()` to decide whether the croaking body is
drawn at all, so it is the one clip gate the roster cannot carry - driving it leaves the generator a
flag with no literal, and the flow refuses the subject rather than shipping a guess. Every other clip
table in the corpus is selectable.

Whoever takes it is choosing between two things. A symbolic flag channel is a shipped-table shape, a
loader arm and a render-time read of a channel that is documented today as read by nothing, which is
a wider change than one croak is worth on its own. Leaving it is cheap and stays correct; what it
costs is that the roster's claim to carry vanilla's own selectors has exactly one exception, and an
exception nobody has written a reason for reads as an oversight to the next person.

## The posed sweeps render one appearance per entity, so a baby's clips are selectable and ungated

`animation/` and `walk/` enumerate 90 subjects, one per entity and every one an adult, where the
still `entities/` sweep holds 402 including babies and equipment variants. So the clips on a baby
mesh - every `SniffletModel` site, and the baby armadillo's, rabbit's, camel's and axolotl's - are
reachable by a caller and compared against nothing.

That is not a gap in the roster, which carries them, nor in the renderer, which draws them. It is
what the reference set enumerates. Widening `EntityAnimationSweep` would gate them, and the cost is
the obvious one: the two posed sub-trees are 720 references each today, against an enumeration of 402
subjects rather than 90.

Whoever takes it should decide what the posed sweeps are FOR before widening them. Every appearance
is not obviously the answer - a collar colour changes no pose - where every distinct MESH plainly is,
and that is a much smaller widening than the appearance count suggests.

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
