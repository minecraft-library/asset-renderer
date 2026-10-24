# Timelines

Load when touching `Flipbook` and a texture's `.mcmeta` animation, `Timeline`, `SubTickLoop` and
`RasterPass.ContinuousRasterizer`, or the `minecraft:time` clock and `vanilla/SunAngle`. Indexed
from [`CLAUDE.md`](../../CLAUDE.md).

## Texture flipbooks

A texture's `.mcmeta` animation resolves against the strip it plays over into one `Flipbook` - the
frame rectangle, the entry sequence with every deferred duration substituted, the cycle length and
the interpolate flag. The same type owns the pixels: which entry a tick lands on, the crop and the
blend (`frameAt`), and the frame a texture shows at a tick (`atTick`), taken over the context's
`resolveTexture` and `findFlipbook` answers at the call site.

- **The table is pack state, so it is built at LOAD and never at generation.** The frame rectangle is
  vanilla's: a declared side as declared, an undeclared side the strip's own where the other is
  declared, and a square of the strip's shorter side where neither is. The implicit entries are the
  whole frames the strip holds across both axes, numbered row by row, so a pack swapping either the
  sidecar or the PNG swaps the table. It is memoised on `PackStack` on the same `(pack, id)` key the
  decoded pixels take.
- **A strip its frame size does not divide is no texture at all.** Vanilla's sprite loader refuses it
  and draws its missing sprite, so `PackStack` checks a decoded strip against its animation before it
  keeps the pixels and serves one that fails as unreadable - `resolveTexture` answers empty and the
  table empty with it. A strip handed to `Flipbook.of` directly still plays the whole frames it holds,
  and one holding no whole frame resolves to NO flipbook, which is what a caller renders as the strip
  unchanged.
- **`RendererContext.findFlipbook` is a lookup, forwarded like every other.** The index answers the
  table memoised on `PackStack`; a context with no index derives it through `Flipbook.of(animation,
  strip)`. A wrapper that changes a texture's pixels pins `findFlipbook` together with `findMeta`
  and `findAnimation` - `withTextures`, `withTexture`, `withMissingTexture` and `hiding` all do -
  because a forwarded table would pair the delegate's frame rectangle with the wrapper's pixels,
  which for `AtlasRenderer`'s static context, whose substituted strips are single frames,
  re-animates the atlas.
- **The three metadata lookups are absent exactly where `resolveTexture` is.** A texture served with
  no sidecar answers all three empty; a wrapper serving a texture its delegate does not answers its
  metadata empty rather than absent; a texture served with no pixels keeps its sidecar and plays
  nothing, its sidecar and animation answering empty themselves where the sidecar is what does not
  parse. A wrong state is invisible to every render, since both value-less tables draw a still
  frame, so `TextureViewCoherenceTest`, which holds the rule over every context and wrapper, is its
  only witness.
- The derivation asks for the sidecar before the strip, so a texture that ships no animation decodes
  nothing.
- A block's own flipbooks ride `Block.flipbooks()`, resolved at index build over its model, its
  block-entity texture and every variant and multipart apply - over-inclusive across variants by
  design, which only ever lengthens the loop `Flipbook.deriveTickStrip` folds them into. Nothing the
  store hashes renders that AUTO path, `blockFlipbook` being a LOOK driver whose output no manifest
  holds, so a change to it is measured by rendering its four blocks before and after and diffing the
  bytes.

## Sub-tick sampling

`bake` lives on `Timeline`, not `TickTimeline`, and reads each frame's instant off the millisecond
axis; the tick handed to a draw is that instant floored.

- `Timeline.gameTime` returns a `SubTickLoop` over the same span at the same speed. A step's delay is
  shared across its sub-steps, leftover ms to the earliest frames - 3 steps give `17/17/16` - so a
  tick still spans exactly 50 ms. `subTickSteps <= 1 || frameCount <= 1` returns the whole-tick
  schedule.
- `SubTickLoop` is deliberately not a `TickTimeline` - an off-lattice instant has no honest `tickAt`,
  and claiming one breaks the `millisAt(f) == tickAt(f) * 50.0` identity.
- A draw needing the fraction implements `RasterPass.ContinuousRasterizer`; everything else keeps
  `FrameRasterizer`, which keeps `RendererContext.*AtTick` on `int` - a fraction there is anti-parity.
- Use it only where appearance is a continuous function of time; a flipbook bakes duplicates, and the
  portal is the only user.

## Time-driven item icons

`minecraft:time` is a normalized **sun angle**, not a linear day fraction:
`data/minecraft/timeline/day.json` eases one `360 -> 0` pair anchored at noon over 24000 ticks with a
cubic Bezier `[0.362, 0.241, 0.638, 0.759]`, reproduced float-for-float by `vanilla/SunAngle`. A
linear ramp is off by more than two clock faces at sunrise.

- Tick 0 is noon and yields exactly `+0.0f`; a `-0.0f` breaks `gui().atTick(0) == gui()` and costs
  every item `ItemModelDispatch.resolveRenderItem`'s baked fast path and its baked tints.
- `deriveTimeline` counts the time table on the branch the frames are drawn from: the search follows
  the walk at the render's own context - the stack, the display context, the overworld pin below -
  and looks through every child of a `composite`, because vanilla draws them all. A stack whose
  branch holds no table renders a still; the clock counts the overworld table the pin selects.
- `context_dimension` is pinned to `ItemModelContext.DIMENSION_OVERWORLD`: the tree's fallback is the
  Nether/End branch whose `source` is `random`, so unevaluable resolves a different face per render.
  `source` is unparsed today; if it is modelled, this pin keeps the clock working.
- Uniform tick sampling covers 60 of 64 faces - the eased curve lingering near noon and midnight.
