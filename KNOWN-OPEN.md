# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## A variant family would lose its size and shape axes, and nothing would say so

`EntityIndexBuilder.buildVariantRow` builds every coat with `largeShape`, `sizeModels` and
`sizeScales` hardcoded empty, and `readDefinition` then takes the family row's axes off one of those
coats - `Entity.Axes baseAxes = base.axes()` - carrying the three empties up with them. The size and
shape builders are only ever called on the plain-family branch, so a family declaring a variant axis
gets none of them.

`sizeDefault` does not travel that path: it is read from the raw family, so it survives. That is what
makes the failure silent rather than loud. The subject would carry a declared default size with no
alternates behind it, `resolve` would answer the base mesh for every size selected, and
`AppearanceCodec` would go on suppressing and writing `~size=` tokens against a default whose
alternatives resolve to nothing - so the appearance key would name sizes the render cannot produce.

No subject reaches it today: the variant families and the five size families and the one shape family
do not intersect, checked over all ninety. It is one vanilla release away from being real - a coat
axis on the pufferfish, or a size axis on any of the fourteen coated families - and the tables would
regenerate clean, every suite would stay green, and the size axis would simply stop working.

Closing it is either wiring the three through `VariantContext` the way `babyModel` and `babyPose`
already travel, which makes it correct by construction, or a generation-time refusal for a family
declaring a variant axis beside a size or shape one, which makes it loud. The first is cheap and this
entry exists because nobody has taken it, not because it is hard.

## Most of the clip tables are dead offline, and the live tail is why they have not been shed

Of `entity_poses.json`'s seventy-four clip tables, fifty-seven are reachable only through `state`
gates - started by something that has not happened to a subject standing still, so `ClipKit`
answers null for them in every render mode - and two more are named by no play site at all. Together
they are roughly 300 KB of the member's 359, parsed into records on every boot. They stay because
the tail is not dead the same way: thirteen of the fifty-seven are locomotion clips vanilla fires
when the subject actually moves - the axolotl's swim and underwater walk, both rabbits' hop, the
bat's flight, both camels' dash, the breeze's slide, the armadillo's roll - which is exactly what a
WALK gate against a genuinely striding reference needs, and WALK has no vanilla reference until the
harness drives a stride. Shedding now would be re-adding then.

Whoever takes it inherits three constraints. The loader throws on a play site naming a table the
file does not carry, so sites and tables move together. A prior decision binds the two unread flag
channels and the three defaults tables - which the emitter still writes and nothing reads - to
whatever commit sheds the clips. And `EntityPoseLoadTest` pins the turtle's egg-belly bone through
a channel map the flag shed empties, so its pins move with the emitter. Closing it is either the
harness stride (WALK gets a reference, the thirteen become measurable, and the genuinely dead rest
shed behind them) or an owner ruling that the offline renderer never plays a state-driven clip.

## Two aggregator rows still carry no duration

`manifest.visual` and `manifest.player-raw` are the last rows whose `last_duration_ms` is unset, so
`parityPlan`'s BUDGET still prints as a floor wherever either is planned. Nothing is wrong with them:
their producers aggregate through `dependsOn` and could not stamp a wall time at all until the build
learned to sum a dependency's, and no capture has run since it did. The next capture of either
records one, and this closes when it has.
