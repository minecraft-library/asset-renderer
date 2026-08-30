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

## The worn shell's coplanar pair falls the wrong way by two ULP of the bone chain

An inflated shell puts a second mesh's boxes through the first's, and the pairs that intersect are
genuinely coplanar - the worn chestplate's torso and arm south faces are one plane. On
`skeleton~armor=iron` at pixel `(128,246)` the two now read two ULP apart with the torso in front,
which is close enough that they quantise onto adjacent window-grid points rather than onto one, so
the torso wins. Vanilla paints the arm's texel there, and vanilla draws `body` after `right_arm`, so
vanilla's torso lost a depth test our torso wins. Two ULP is the model's own vertex rounding: what is
left is the bone chain, not the raster.

**Most of the seam already ties.** Over a rect across the torso/arm seam, 1543 of the 1708 pixels
both faces contest are a true tie - both fragments pass and both draw - and the shell's own emission
order settles them. The residue is the other 165, which the two ULP separates far enough to survive
the grid, and it is not even: 125 fall to the torso and 40 to the arm.

**Draw order is not the lever, and the seam to measure it at is `ShellWalk.of` rather than
`EntityGeometryKit`.** A worn shell's triangles come from `ArmorKit.buildArmor3D` walking
`ShellWalk.parts`, so reordering the geometry kit's bone loop reaches the base mesh and never the
shell - a probe that does only that moves no `~armor=iron` row and means nothing about them.
Reordering the walk itself into the order vanilla's `PartDefinition.bake` iterates its `HashMap`
- `head, right_arm, left_leg, left_arm, right_leg, hat, body` against our declaration order's
`head, hat, body, right_arm, left_arm, right_leg, left_leg` - takes `skeleton~armor=iron` from
`0.2046` to `0.6577` and its differing pixels from 818 to 1727. Order reaches roughly nine hundred
pixels of this one row, and the order already shipped is much the better of the two.

So whoever takes this is asked how closely the per-bone chain composition can be made to land where
vanilla's does, for two bones whose boxes meet at one z. That is a question about the fit: our chain
carries a measured `scale · translate(-centre) · scale(modelScale)` where the harness carries its own
pose, and two matrices that agree mathematically still round a pivot differently. Closing it means
making those agree to the last ULP - or establishing that a two-ULP agreement is the floor and which
side of the grid it falls on is not recoverable.

## Two aggregator rows still carry no duration

`manifest.visual` and `manifest.player-raw` are the last rows whose `last_duration_ms` is unset, so
`parityPlan`'s BUDGET still prints as a floor wherever either is planned. Nothing is wrong with them:
their producers aggregate through `dependsOn` and could not stamp a wall time at all until the build
learned to sum a dependency's, and no capture has run since it did. The next capture of either
records one, and this closes when it has.
