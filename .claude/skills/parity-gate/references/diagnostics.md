# Diagnostics

Worked examples of how a mover was diagnosed, and the two version-scoped rosters a diagnosis needs.

**Never load this to decide a verdict.** It is a walkthrough library. The verdict comes from
`parityCompare`; this file is for the question that comes after it - *why* did that row move, and
what shape of cause should I look for.

## The four worked examples

### A block icon that was wrong because two errors cancelled

**Symptom.** Stairs rendered with the riser face missing entirely, and the generic block pose looked
correct.

**What made it findable.** `block/stairs.json` authors `display.gui` at `[30, 135, 0]` where
`block/block.json` is `[30, 225, 0]`, and the default state's `y: 270` rotation is `R_y(+90)`.
`135 + 90 = 225` - the authored override was cancelled back to the generic pose, and the model's lone
`cullface`-less west face became a back face and was never drawn.

**The lesson worth reusing.** Two errors that cancel produce a *plausible* render, so the symptom was
one missing face rather than a wrong pose. The reading that the author baked the `+90` in
deliberately was falsified by finding two other shipped files carrying the same `[30, 135, 0]` and
appearing in no blockstate at all. **Look for a second file that shares the suspicious constant** -
if it appears where the compensating term cannot, the compensation was accidental.

### A canvas sized from the wrong variant

**Symptom.** Every mooshroom row off by a constant vertical offset, both coats.

**What made it findable.** Measuring the reference rather than reasoning about it: vanilla renders
both coats into one `388x564` frame, and the brown coat's content is 14 px taller inside it - top
margin 16 against 2, bottom margin 0 on both. So the frame is sized **once**, from the default
coat, and a taller coat simply reaches further up inside it.

**The lesson worth reusing.** Sizing per coat instead gave every row a 578-tall canvas and moved all
three off the reference. **When a whole family shifts by a constant, suspect the fit rather than the
geometry**, and measure the reference's own margins before changing anything.

### A one-pixel line that was a harness bug

**Symptom.** Six large-shape rows regressed on a detached 1-px vertical line vanilla drew and this
renderer did not - at one row it was the only opaque pixel in its row, 55 px from the tail.

**What made it findable.** The pixel dump put the two fin edges at `23.480406` and `23.48`, agreeing
to a thousandth of a pixel. So it was not geometry. It was an overlay layer inflating a *plane* cube:
the deformation moves the corner positions but not the unwrap, so four collapsing edge faces became
real slivers still carrying a zero-width UV strip.

**The lesson worth reusing.** The harness's own bounds walker already dropped those polygons from the
canvas measurement, so **the harness disagreed with itself** - it measured a mesh it then rendered
differently. When a diff is a thin sliver at a silhouette edge, check whether the two sides agree
about which polygons exist before checking whether they agree about where they are.

### An error concentrated in one pixel column

**Symptom.** 36 rows putting a quarter or more of their total error on the single centre column, up
to 85% on one.

**What made it findable.** Correlating against canvas width parity: 36 of 262 odd-width rows, and
**0 of 136** even-width rows. A symmetric model's front corner projects to exactly `w/2`, which is a
pixel *centre* at odd width and a pixel *boundary* at even width - so at odd width the edge between
two faces passes exactly through a sample point.

**The lesson worth reusing.** It was not the coverage snap (byte-identical across three grid
settings) and not the fit (per-column coverage difference was exactly 0 on three subjects). **A
defect that correlates with a canvas dimension's parity is an alignment artefact, not an arithmetic
one**, and the fix was to remove the tie - both sides round the canvas width up to even - rather than
to break it with a rule.

## The two version-scoped rosters

Both are read off the vanilla client for the Minecraft version in force. They go stale on a version
bump, which is why they are here rather than in a claim anything asserts.

### Renderers that override `setupRotations` (14 in 26.1)

ArmorStand, Cat, Cod, Drowned, Fox, IronGolem, Panda, Phantom, Pufferfish, Salmon, Shulker, Squid,
TropicalFish, and the player's own renderer.

**Only two survive the harness's rest pose**, and that is the fact worth carrying: everything else
sits behind a gate that is false there. The squid translates a net -0.7 blocks as an adult and -0.35
as a baby; the pufferfish translates by an *expression* rather than a literal, so it is latent rather
than armed - its three sizes have three different canvases and each per-subject fit absorbs it.

**A `setupRotations` shift is invisible unless the canvas is group-unioned**, because a translate
applied to both the geometry and the bounds cancels exactly for a subject measured alone.

### Whole-mesh scale models (7 in 26.1)

giant 6.0, husk 1.0625, wither_skeleton 1.2, PolarBear 1.2, HappyGhast 4.0, Ghast 4.5, Guardian 2.35.

These are `LayerDefinition`-time transforms, baked by the tooling and exact - **not** a runtime
override to go looking for. The factor rides the geometry key, so a mesh that carries one says so in
its own name. Two other renderers scale at render time instead (the wither at 2x, the zoglin at about
half when it is a baby), and those are a different mechanism with a different name.

## Where the standing corpus lives

`CLAUDE.md` in the repo root carries the durable findings: the depth contract, the armour shell, the
face vocabulary, the iso pose. Which artifacts see a given change is not one of them - that answer
is `blindness.json`, which `parityPlan` resolves and `references/blindness.md` renders, and where a
rule's claim did come from a section of `CLAUDE.md` the rule cites it by name. This file holds the
*method* - how those were arrived at - and does not restate them.
