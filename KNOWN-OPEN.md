# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## A geometry key is not a complete identity, and a texture override rides along un-encoded

`GeometryIds` spells every discriminator a request carries except the texture-size override, which
`GeometryFlow.parse` reads off `texWidthOverride` / `texHeightOverride` and stamps onto the entry.
So two requests differing in nothing but that override mint ONE key, and the manifest dedupes them
to whichever registered first - a mesh sampled against the wrong sheet dimensions, with nothing
saying so.

It has not bitten because every override in the corpus travels with a discriminator that already
splits the key: `HumanoidModel#createMesh` ships at 64x64 while `@grow=0.2` and `@grow=0.25` ship at
64x32, so the grow tells them apart and the sheet follows it. Nothing enforces that pairing, and a
version bump that gives one factory two sheets at one deformation would collapse them silently.
Closing it is either encoding the override in the key the way the other seven are, or a stated
reason why a request may carry a fact its key does not name.

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
