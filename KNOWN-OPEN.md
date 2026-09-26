# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## PlayerOptions has no style knob, and coining one needs a catalog source for the player

The style axis is a string knob on `EntityOptions` resolved against the entity's shipped catalog.
The player renders through its own pipeline, holds no row in `entity_models.json`, and its sweeps
gauge look rather than bytes - so a style knob on `PlayerOptions` today would be a string with
nothing to resolve against. Deferred deliberately by the owner (2026-09-01), with the axis kept
collision-free: adding the knob later needs a player-side source of catalog rows.

`PoseStyle` names no bag at all - `appliesTo` takes the appearance rather than the request carrying
one - so the row type is answerable for a subject whose options are not an entity's, and a player
catalog shipping age-free rows never reaches it either way. What still spells `EntityOptions` is
`StyleSelection.resolve` and the `byId` overload behind it, which is where the axis would have
to grow a shape the player bag can answer.

**That shape is not an `AppearanceOptions` on `PlayerOptions`.** `AppearanceOptions` stays
entity-specific by decision, so the player does not gain one. What the three bags share is an
appearance concern nothing abstracts yet, and organising that is its own job, deliberately not
attached to this entry - a knob coined by widening the player bag to look like an entity's would be
settling that question by accident.

## The generator tree is one cycle, and nothing orders it

The renderer's packages are held to a tier order by `guard/TierOrderTest`, and the generators under
`tooling` are held only to the ceiling that order puts on them: a generator names the renderer up to
the content index and nothing above it. Among themselves the fourteen `tooling` packages are not
ordered, and measured they are one strongly connected component - `tooling`, `animation`, `asm`,
`block`, `blockentity`, `colormap`, `entity`, `geometry`, `index`, `interp`, `item`, `policy`, `run`
and `walk` each reach every other through some chain of imports, counting code references only.

What is open is whether they should be ordered at all. The build is on no published classpath and
nothing outside it imports one of its packages, so a cycle here costs a reader rather than a
consumer; ordering it would mean a member move per back edge, and the walk DSL, the interpreter and
the policy SPI are where most of them sit. Taking it means writing the order down beside the
renderer's and extending the test to it; declining it means saying so in `tooling/CLAUDE.md` so the
next reader does not assume the order the package names suggest.

## The reach graph cannot see the fluid render sample a flipbook

`FluidRenderer` draws its still and flowing textures through `RendererContext.requireTextureAtTick`,
whose default body finds the texture's `Flipbook` and takes the frame `Flipbook.frameAt` answers for
the tick. The reach graph cuts `RendererContext` by declaration - `findFlipbook` returns a
`Flipbook`, so the type is on the interface's declaration surface - and that cut removes the default
body's edge along with the declared one. Nothing else on the fluid path names `Flipbook`, `MCMeta`
or `FormatRange`, so `parity/reach.json` answers neither `manifest.fluid` nor `pin.fluid-crc` for
them, and a plan for a change to the frame arithmetic does not schedule the fluid render. The CRC
pin still runs in `./gradlew test` whatever the plan says; the manifest is what a plan leaves out.
The toolkit's own suite asserts the right answer as an expected failure in
`parity/scripts/parity/tests/test_reach.py`, so closing this turns it into an unexpected success
that fails the suite until the marker comes off.

What is open is where the answer is written. `parity/scripts/parity/reach.py` could keep a default
body's edge to a type the declaration surface also names, which widens every interface the graph
cuts and has to be measured across the whole tree first. Or a rule could author the two artifacts
for the three files, which states a reach the graph cannot derive and has to be kept by hand.
