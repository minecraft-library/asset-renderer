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

### Whole-mesh scale geometries (26 in 26.1)

The roster is every geometry in `entity_geometry.json` whose bones all carry one `scale` other than
1, so re-reading the table at a version bump regenerates it. The armour stand's two aged-down meshes
(`@baby=`) scale their bones unevenly and are not on it.

These are `LayerDefinition`-time transforms, baked by the tooling and exact - **not** a runtime
override to go looking for. **Read the factor off the bones, not the key.** A key spells only the
factor `LayerDefinitions.createRoots` applies where it registers the layer; a factory that scales
its own result says nothing in the key.

- **17 keys carry `@scaled=<F>`**, the registration's factor: giant 6.0, husk 1.0625, wither
  skeleton 1.2, cave spider 0.7, cat 0.8, the horse's body, armour and saddle 1.1, the small and
  large salmon 0.5 and 1.5, the villager, witch and illagers 0.9375, and the baby happy ghast 0.2375.
- **9 keys carry none**, because the factory scales itself: polar bear 1.2, ghast 4.5, happy ghast
  and its harness 4.0, elder guardian 2.35, and the donkey's and mule's body and saddle at 0.87 and
  0.92 - a factor that factory takes as its argument, which the key spells as `@fparam=` instead.

Where both apply they multiply: the baby happy ghast's bones carry 0.95, its factory's 4.0 times its
registration's 0.2375.

Five renderers scale at render time instead, in their own `scale` override, and none of it is in a
geometry key or on a bone:

- **The wither at 2.0 and the slime at 0.999**, the constant factors `entity_models.json` carries as
  the entity's `render.scale`. The wither's is 2.0 less `invulnerableTicks` / 440 while it is
  invulnerable, and the slime's 0.999 comes with a translate of (0, 0.001, 0).
- **The slime and the magma cube by size**, carried as `axes.size.options.<size>.scale` - 2.0 for
  medium and 4.0 for large, small being 1. Both also squish by `squish`, which is 0 at rest.
- **The phantom by `1 + 0.15 * size`**, followed by a translate of (0, 1.3125, 0.1875). The scale is
  1 at size 0, and `entity_models.json` carries neither.
- **The creeper by its swell**, which is the identity at rest (`swelling` 0).

## Reading a probe

The entry runbook - the panel, the scoped re-run, the pixel dump, the `javap` lookup - is in
`RENDERER-RULES.md`'s *Debugging a mismatch*. These are the traps in reading what those produce.

- `DebugChannel.pixelWrite` logs a colour write and the **candidate** depth, never the stored one, so
  a `WRITE` line says nothing about what the buffer held; add a temporary probe when that is the
  question.
- Check the canvas width a dumped `idx` implies before comparing two lines - one subject's appearances
  have different canvases and therefore different depth arrays.
- Armour triangles carry a `debugTag` only when `-Dasset.entity.pixel.dump` is armed. Without it they
  log `tag=null` and `pixelTriangle` skips them entirely.
- Read a suspected tint fault per channel, never through luma - a hue error cancels in mean signed
  luma while mean absolute delta stays high.
- `java-only = 0` with `vanilla-only > 0` is a strict-subset silhouette, and reads as dropped faces.
- Two byte-identical references name one appearance, so an axis you added is not being selected.
- A `[PX]` dump agreeing with the reference to `0.001` px rules geometry out and points at coverage or
  the texel fetch.
- **A transparent animated GIF needs `FrameDisposal.RESTORE_TO_BACKGROUND` per frame, and diagnose a
  smeared strip there before believing it.** `ImageFrame.of(pixels, delayMs)` leaves the disposal at
  `NONE`, which a decoder reads as "leave the previous frame standing" - right for an opaque strip and
  wrong for every transparent one, because the subject is drawn on nothing and each frame shows
  through to the one before. It accumulates: measured on the bee, the pixels a decoded frame covered
  that its own PNG did not ran `0, 638, 2205, 4246, 6101, 7306, 7669, 7575` over eight frames. The
  PNGs beside it are correct, so it survives a look at them and reads as the pose smearing. The
  production path still carries the default - `FrameCompositor` and `Timeline` both build frames
  through the two-argument form - so a caller writing an animated render as a transparent GIF gets
  this, and the three GIFs the visual drivers produce and the store hashes come from there.

## Where the standing corpus lives

`RENDERER-RULES.md` in the repo root carries the durable findings: the depth contract, the armour
shell, the face vocabulary, the iso pose. Which artifacts see a given change is not one of them -
that answer is `blindness.json`, which `parityPlan` resolves and `references/blindness.md` renders,
and where a rule's claim did come from a section of `RENDERER-RULES.md` or of `CLAUDE.md` the rule
cites it by name. This file holds the *method* - how those were arrived at - and does not restate
them.
