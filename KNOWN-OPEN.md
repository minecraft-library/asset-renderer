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
gates and six more are named by no play site at all. Together they are roughly 300 KB of the member's
359, parsed into records on every boot.

**Seven of the fifty-seven now play.** A state-gated site names the render-state field its gate reads,
and a caller selects one member of each group, so `ClipKit` plays the site whose gate the selection
answers - `BAT_FLYING`, `CAMEL_IDLE`, `CAMEL_BABY_IDLE`, `COPPER_GOLEM_IDLE` and both rabbits'
`IDLE_HEAD_TILT` at the default selection, and `BAT_RESTING` for a caller who asks for it. **Fifty
are still dead offline**, their states being ones no roster makes selectable; with the six a play
site no longer names, a shed would be about fifty-six.

They stay because the tail is not dead the same way: twelve of the fifty are locomotion clips vanilla
fires when the subject actually moves - the axolotl's swim and underwater walk, both rabbits' hop,
both camels' dash, the breeze's slide, the armadillo's roll.

**WALK has a reference set, and it did not make those twelve measurable.** Vanilla's own `walk/`
references are byte-identical to its `animation/` references on all eight frames for `axolotl_lucy`,
`rabbit_brown` and `breeze` - 29 of the 90 subjects are identical between the two gaits, and those
three are among them. So the walking client starts none of those animation states either, and a
selector on this side alone would animate a subject vanilla holds still, which is a regression rather
than coverage. What the twelve wait on is the harness after all: an arm per state, and the reference
re-render a harness change owes.

**The armadillo and the two camels look like counter-evidence and are not.** Their references do
differ between the gaits, because each carries a second clip a `walk` gate reaches - `ARMADILLO_WALK`,
`CAMEL_WALK` - which the stride starts and which plays today. Ten tables are reached by a walk gate
and every one of them plays; the twelve are reached by a `state` gate alone. Which gate a site
carries is what decides whether a gait reaches its clip, and reading a subject's delta rather than
its gates hides that.

The mechanism is not the missing part either. `IdleFigures.play` starts one animation state where its
own member is the selected one and stops it otherwise, and four mixins drive it already, so an arm is
a roster line on both sides and a three-line mixin in the harness. Shedding now would still be
re-adding then. The bat's flight was the thirteenth and is off that list: it is an idle animation
rather than a locomotion one, and it plays today.

**Five sites left this member for the opposite reason** - a walk-driven clip behind a branch a
resting subject decides against, which the fold now settles and drops rather than shipping. Those are
gone from the table rather than dead in it, and four of the six tables a play site no longer names
are theirs.

Whoever takes it inherits three constraints. The loader throws on a play site naming a table the
file does not carry, so sites and tables move together. A prior decision binds the two unread flag
channels and the three defaults tables - which the emitter still writes and nothing reads - to
whatever commit sheds the clips. And `EntityPoseLoadTest` pins the turtle's egg-belly bone through
a channel map the flag shed empties, so its pins move with the emitter. Closing it is either an arm
per locomotion state on the harness side - which makes the twelve measurable and lets the genuinely
dead rest shed behind them - or an owner ruling that the offline renderer never plays a state-driven
clip.

## The entity shade that is one channel step high is the vertex grid's price, and two subjects want different prices

On a shaded entity surface this side is essentially never DARKER than vanilla. Counting only pixels
where every differing channel is exactly one step high and none is low:

| sweep | total | that field | share |
|---|---:|---:|---:|
| entity, still | 20.9373 | 9.4171 | 45% |
| entity, idle | 3.1497 | 1.9608 | 62% |
| block | 129.8392 | 14.5908 | 11% |
| item | 128.9575 | 0.1991 | 0.2% |

**It is what `Shading.onVanillaVertexGrid` costs, and the cost is worth paying.** Skipping that pack
restores the subjects it hurts to the value each held before the grid landed - `evoker` `0.1262` to
`0.0013`, `enderman` `0.1134` to `0.0056`, `zombie_horse` `0.0785` to `0.0136`, `pillager` `0.0843`
to `0.0395` - and takes `goat` from `0.0819` to `0.7067`. Over the ninety, the shipped camera-frame
pack is `3.1497`, no pack at all is `10.3894`, and packing in the kit frame instead is `12.1058`.
It is decided on a knife edge: a west face's camera-frame x is `-89.80` of a step, `0.197` from
turning over.

**What is open is that vanilla's factor is not a function of the face normal.** Reading a factor out
of vanilla's own bytes, where each texel and output pair pins it to `[(o - 0.5) / t, (o + 0.5) / t)`,
a goat's west-facing body needs a factor at or above `0.45` - its texel `250` is written `113` across
315,792 pixels - and a pillager's west-facing body and arm need one below it, their texel `30` written
`13` across 15,080. **The two faces carry the same normal**: taken as a cross product off the emitted
triangles, both are `(0.707107, 0.353553, -0.612372)` to six places. No factor satisfies both, under
this side's association or vanilla's; the two constraints are exact ties at `0.45` and they flip
together, never apart. Nor does any tie rule - half-up answers the goat and not the pillager, half-even
and half-down answer neither.

That is why every global lever fails, and each of these is measured, so do not re-run them. The first
light's `y` calibration is a sharp optimum where it sits, and so is the whole `x` by `y` grid around
it, the `x` never having been fitted at all. An `x` offset of `0.0005` makes EVERY measured face on
the pillager exactly right and takes it from `0.0843` to `0.0309`, better than it has ever been, while
taking the goat from `0.0819` to `0.4404` on that one 315,792-pixel constraint. Rounding rather than
truncating, unpacking on `128` rather than `127`, and adding a half before the floor each answer one
face and break another. Quantising in vanilla's association rather than this one produces identical
bytes in float32. A drift onto a neighbouring texel is out, a subject's texture carrying no colour near
enough to the sampled one to yield vanilla's bytes at this side's factor. The second light stays
clamped off either way, so no discontinuity is being crossed. Vanilla's own pack, read out of the
client jar, already matches `Shading.packAsSnormByte` - an `f2i` truncation of `clamp(c, -1, 1) * 127` -
and `PoseStack$Pose` renormalises only when a scale is non-uniform in magnitude, which an entity's
never is.

**The normal is not where it goes wrong, and that is provable rather than suspected.** A packed
normal is a lattice point, so a face's factor can only take discrete values - a tilt small enough to
leave a silhouette unmoved never leaves its integer bin, west's x having to travel `0.0016` to cross.
Solving the camera-frame light out of four measured lattice points reproduces all four to eight
places, and enumerating every integer triple within ten of west then yields NO factor inside the
pillager's bracket at all. The goat's bracket, by contrast, holds the shipped lattice point exactly,
and every one of its eight measured surfaces - body, nose, four legs, head, horn - reads 100% there.
So one subject's shade is a packed normal's and the other's cannot be any packed normal's, which
takes the normal, the lights and the grid out of it together.

**What is left is a second multiplicative term on the fragment that this side does not model**, since
what the bracket measures is really `output / texel` rather than a shade. A vertex colour that is not
pure white, a layer submitted twice, a lightmap sample, or a render type bound per subject would each
show up exactly this way. That is where to look, and normals are not.

The illager mesh is baked at `0.9375` where the goat carries no scale, but that is a geometry-time
scale and leaves a normal cardinal, so it is not the answer either. Worth knowing while looking: the
emitter stores bone rotations in degrees round-tripped through float, so vanilla's `55` is written
`54.99822` and its `22.5` is written `22.500051`, and a quadruped's body rotation is baked into cube
positions rather than stored at all - the goat carries no body rotation where the illager carries its
arms at `-42.971836`, which is `-0.75` radians exactly.

Read a bracket with `-Dasset.entity.pixel.dump=x0,y0,x1,y1`, whose `WRITE` records carry the face,
the texel, the tint, the factor and the output. Three traps. Group by face AND factor: grouping by the
face's direction alone merges bones carrying different factors and yields an inverted interval. Score
a factor by how many constraints it satisfies rather than by intersecting them, because an intersection
over ten thousand pixels is destroyed by the handful where vanilla painted a neighbouring face, and
reads as a contradiction that is not there. And re-run the sweep at its defaults before pairing a dump
against the PNGs, because an A/B leaves the java frames from whichever arm ran last and the pairing
then drops every pixel in silence.
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
