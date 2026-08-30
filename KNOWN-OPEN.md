# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## Most of the clip tables are dead offline, and the live tail is why they have not been shed

Of `entity_poses.json`'s seventy-four clip tables, fifty-seven are reachable only through `state`
gates and two more are named by no play site at all. Together they are roughly 300 KB of the member's
359, parsed into records on every boot.

**Seven of the fifty-seven now play.** A state-gated site names the render-state field its gate reads,
and a caller selects one member of each group, so `ClipKit` plays the site whose gate the selection
answers - `BAT_FLYING`, `CAMEL_IDLE`, `CAMEL_BABY_IDLE`, `COPPER_GOLEM_IDLE` and both rabbits'
`IDLE_HEAD_TILT` at the default selection, and `BAT_RESTING` for a caller who asks for it. **Fifty
are still dead offline**, their states being ones no roster makes selectable, and that is the number
a shed would be about.

They stay because the tail is not dead the same way: twelve of the fifty are locomotion clips vanilla
fires when the subject actually moves - the axolotl's swim and underwater walk, both rabbits' hop,
both camels' dash, the breeze's slide, the armadillo's roll. **WALK has a reference set now**, so
what those twelve wait on is no longer the harness but a selector: they are gated on animation states
no roster makes selectable, exactly like the other thirty-eight, and a stride alone does not start
one. Shedding now would still be re-adding then. The bat's flight was the thirteenth and is off that
list: it is an idle animation rather than a locomotion one, and it plays today.

**Five sites left this member for the opposite reason** - a walk-driven clip behind a branch a
resting subject decides against, which the fold now settles and drops rather than shipping. Those are
gone from the table rather than dead in it.

Whoever takes it inherits three constraints. The loader throws on a play site naming a table the
file does not carry, so sites and tables move together. A prior decision binds the two unread flag
channels and the three defaults tables - which the emitter still writes and nothing reads - to
whatever commit sheds the clips. And `EntityPoseLoadTest` pins the turtle's egg-belly bone through
a channel map the flag shed empties, so its pins move with the emitter. Closing it is either the
harness stride (WALK gets a reference, the thirteen become measurable, and the genuinely dead rest
shed behind them) or an owner ruling that the offline renderer never plays a state-driven clip.

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
