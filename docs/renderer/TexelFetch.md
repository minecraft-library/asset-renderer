# Texel rules

Load when touching the texel fetch in `engine/raster` - `Rasterizer`'s per-pixel fetch,
`RasterMath.texelOf` and `exactTexelOf`, `faceTexel` and `lastTexel` - the `1/256` corner snap as it
feeds interpolation, its grid and its tie rule, or a pass's texture wrap. Reading the harness's
texture-coordinate probe is [TexCoordProbe.md](TexCoordProbe.md), and the depth half of the raster
contract is [Depth.md](Depth.md). Indexed from [`CLAUDE.md`](../../CLAUDE.md).

How a texture coordinate becomes a texel: what the reference GPU does to a triangle's texture
coordinates between its corners and its sampler, what the renderer reproduces of that and how, and
what it deliberately does not. The numbers kept here are the ones a rule, a closed decision or the
card's described behaviour rests on.

## What the reference GPU does

The reference is the harness's card, an NVIDIA RTX 3090, drawing the vanilla client. What follows is
that card's arithmetic as the texture-coordinate probe reads it. No graphics API specifies it - a
different card is free to round differently - and the last bits it leaves are what decide a
coordinate sitting exactly on a texel boundary.

**A triangle's coordinate is a plane the card sets up once and reads per pixel.** In order:

1. Each corner's window position snaps to the `1/256` pixel grid, rounded to nearest, in the GL
   window frame (y up). Coverage and interpolation are both set up from the snapped corners.
2. The setup, once per triangle per attribute:
   - The anchor is the corner opposite the longest edge, measured as `max(|dx|, |dy|)`; a tie goes to
     the smaller window x, then y. It is geometric, so the order the corners arrive in changes
     nothing.
   - The doubled area comes exactly off the snapped corners and is cut toward zero to a float. Its
     reciprocal comes from a hardware table, within one float step of `1/x` but not the rounded
     quotient. Its layout is known only as a fit to 1576 measured values - 128 segments on the top
     seven fraction bits, a quadratic within each, the result truncated.
   - The four position deltas from the anchor, times that reciprocal, are cut toward zero; these are
     the gradients of the two non-anchor barycentrics. The attribute differences from the anchor are
     rounded to nearest. Each plane gradient is the exact sum of two products of those, rounded to
     nearest on a grid 25 bits below the larger product's exponent, then cut toward zero.
   - The plane's value is carried exactly from ONE corner of the triangle to the corner of the 4x4
     pixel block holding the triangle's lowest corner (smallest window y, then x), and cut toward
     zero once. Which corner it is carried from is chosen per triangle, by screen position: the corner
     first in Z-order of its whole-pixel window position is that corner for 92.8% of the plane values
     the probe pins, and the rest sit on corner pairs along a `-1/2` slope that the card splits both
     ways for identical shapes.
3. Each pixel reads the plane at the centre of its 2x2 pixel block, adds a half-pixel step along each
   axis taken with the gradients truncated to that same 25-bit grid, sums the terms exactly, and cuts
   toward zero once.
4. The divide by `w` that perspective correction applies is the identity under the harness's
   orthographic lens.
5. The point sampler takes the floor of the coordinate times the sheet's size, exactly, and repeats
   outside the sheet.

**Every rounding that lands a plane term or the per-pixel sum in a float cuts toward zero** - only the
corner snap, the attribute differences and the gradients' 25-bit grid round to nearest, and none of
those three is the last step - which is the one fact the renderer's fetch rule rests on. A coordinate
whose exact value is a texel boundary lands a few float steps under it - 1 to 3 at 3780 of the 3951
boundary pixels the probe reads across the still, idle and walk sweeps - and the sampler reads the
texel below. Where none of the cuts loses anything it lands exactly on the boundary, and the sampler
reads the texel above: the other 171. None lands above a boundary.

Replayed in full, the setup above calls 3950 of those 3951 boundaries right. The one it misses is a
corner the card snaps to the other `1/256` step (see *What the renderer does not reproduce*).

## The fetch

- **A coordinate exactly on a texel boundary reads the texel below it** - `RasterMath.texelOf`,
  `ceil(x) - 1` rather than `floor(x)`: `10.3 -> 10`, `10.0 -> 9`, `0.0 -> -1`. That is where the
  card's cuts land almost every such value. The two forms differ only on a boundary, and the snapped
  corners put a coordinate on one wherever a face's edge or a texel edge runs through pixel centres.
- **Where a triangle's three corners sit on whole texels on an axis, that axis reads the exact
  coordinate** off the coverage walk's integer edge values, `RasterMath.exactTexelOf`: the barycentric
  weights at a sample are exactly `e12 / denom`, `e20 / denom` and `e01 / denom`, so the coordinate in
  texels is exactly `(e12 * t0 + e20 * t1 + e01 * t2) / denom`, and `floorDiv(numerator - 1, denom)` is
  its `ceil - 1`. A float evaluation lands a step either side of a boundary it sits exactly on, so it
  cannot decide one. The two axes are decided apart. An axis whose corners are off whole texels, and
  both axes under a perspective lens, read the float coordinate through `texelOf`: a perspective lens
  re-weights by `1/w`, which the screen-linear edge values do not carry.
- At a face's first texel the texel below is outside the face - padding, or a neighbouring part of
  the skin - and that is what vanilla draws.
- **The fetch wraps below the sheet's first texel, as vanilla's repeating sampler does, and holds at the
  face's last texel above** - both in `Rasterizer.faceTexel`. The upper bound is the renderer's guard
  rather than the sampler's: `Rasterizer.lastTexel`, `clamp(ceil(cMax * size) - 1, 0, size - 1)` over
  the face's highest corner coordinate `cMax` and the sheet's `size` on that axis, which the exact
  coordinate never passes and the float fallback can by a step. Rounding up and stepping back keeps a
  rectangle ending part-way through a texel apart from one ending on a whole texel: `24.0 -> 23` keeps
  the last texel inside, `23.7 -> 23` keeps the texel it reaches into.
- **Every pass takes its texel by the one rule; a scrolled pass differs only in its bound.** It samples
  where its render type translated it to, which the authored rectangle does not name, so the face's
  bound has nothing to say and the sheet wraps on both sides instead (`floorMod`); a clamp at the
  sheet's edge would smear the scrolled texture's last column. It is told apart by the PASS rather than
  by the coordinate - `PassDeclaration.wrapsTexture`, true exactly when the pass declares a
  `texture_scroll`. Inferring it from "the coordinate ran past `1`" is what does not work: a block's own
  geometry does that, the decorated pot's sherds and one water flow frame authoring a rectangle whose
  upper corner rounds a texel beyond, and wrapping those reads from the opposite edge. The offset
  itself arrives already wrapped, taken into the render type's argument as vanilla takes it -
  [Entity model form](EntityModelForm.md) has why.
- A perspective lens interpolates the coordinate perspective-correctly - each corner's weight
  re-weighted by its inverse clip-`w`, then normalized - and turns the exact read off. Those two are
  everything the `perspectiveCorrect` flag forks; depth reads one plane under every lens.

## The corner snap, as the texel sees it

- **The snapped corners decide every texel edge that runs through pixel centres.** Under the iso pose
  a texel edge on an axis-aligned face is a line of slope exactly `1/2`, so one passing within a
  rounding of one pixel centre passes within a rounding of every second one along it, and the texel
  each of those fragments reads is settled by where the snap leaves the corners. Rounding to the
  card's grid (`Rasterizer.snapToCoverageGrid`, at `SUBPIXEL_PRECISION`, `256` unless a probe overrides
  it with `-Dasset.snap.grid`) settles them as the card does; any other grid moves the corners by a
  different amount and can settle a whole staircase the other way.
- **The texture coordinate interpolates from the snapped corners**, as coverage and depth do, because
  the interior texel edges follow them. Depth's half is the depth contract in [Depth.md](Depth.md); the
  snap moves which samples are covered, which texel they read and the depth they compare as one.
- The snap rounds half up in image space, `Math.round(v * 256)`. A corner java computes exactly on a
  `1/512` midpoint is not a midpoint in vanilla's own vertex arithmetic, so no tie rule on java's
  value reaches the card's choice there (see below).

## What the renderer does not reproduce

Each of these is a property of the card, or of where vanilla's corners land, that java's exact values
do not carry. Modelling the card's setup is a closed decision below; computing the corners in
vanilla's order is one in [Geometry.md](Geometry.md).

- **The boundaries the card keeps exactly on the boundary.** Labelled by colour, 421 of the 4706
  boundary pixels the still, idle, walk and block sweeps hold at 26.1. Whether a boundary lands on or
  a step under is decided by which corner the card carried the plane's value from, the bits its
  reciprocal table returned, and which 4x4 block the triangle starts in - none of which the exact
  coordinate carries. The same boundary can go both ways on the two triangles of one quad.
- **A corner on a snapping midpoint.** Java computes an entity's corners with vanilla's transforms but
  not in vanilla's float order, so a corner lands a few float steps from vanilla's. That matters only
  where it sits within a few hundredths of a `1/256` step of the midpoint between two: there the card
  snaps vanilla's corner one way and java's can go the other, moving one corner by one step, which
  changes coverage only at pixel centres that close to an edge and the texel only at boundary pixels,
  but tilts the depth plane across the whole triangle and can decide a coplanar contest the other way
  from the card. It decides two stored rows that way: `creeper~charged=true` loses the energy swirl's
  doubling over its hind legs on 803 pixels, one `right_hind_leg` corner sitting within a hundredth of
  a step of a midpoint, and `zombie_villager~armor=iron` gives part of a shell leg's `south` face to
  the other leg from the one vanilla shows, two `right_leg` corners sitting exactly on one. 3.85% of randomly drawn visible triangles carry such a corner, and the one probed boundary the
  card's model misses is a sniffer beak corner of this kind. No tie rule on java's value reaches it,
  and computing the corners in vanilla's order reaches about seven in ten: the rest turn on the card's
  own rounding of vanilla's corner.
- **On a face seen nearly edge-on, the corners decide the texel; the interpolation does not.** Across a
  face `w` pixels wide carrying `S` texels the gradient is `S / w` texels a pixel, and every rounding in
  the setup is relative, so the card's error grows as `1 / w` too - but on any face wide enough to
  cover a column of pixel centres it stays within about `1e-4` of a texel, and it reaches a whole
  texel only on slivers a few millionths of a pixel wide, which cover almost no pixel centres. What
  moves whole texels on a thin face is where its corners land: one `1/256` step of one corner moves
  the exact coordinate by up to `S / (256 w)` texels, 2 for an 8-texel span at `1/64` px, so a thin
  face inherits the midpoint item above at that magnification. A disagreement of many texels down a
  thin face's column is usually a different surface instead: in the salmon's walk the card draws the
  front body's side face down a 69-pixel column where java draws the tail's end cap, 13 sheet rows
  apart.

## Decisions that stay closed

- **Do not model the reference card's interpolation setup.** The setup in *What the reference GPU
  does* can be replayed in plain double arithmetic, bit for bit with an exact-arithmetic reference,
  and it moves 64 stored sweep rows better and 12 worse at 26.1, every one by thousandths. It is one
  card's arithmetic, so matching it matches the harness's card rather than Minecraft; two of its
  parts are fitted rather than known - the reciprocal table right on 95.2% of values outside its fit,
  the carrying corner on 92.8% of pinned plane values - so every subject added later meets that miss
  rate; and a replay of it in the fetch costs the raster micro-benchmark 0.469 ms a render against
  0.159.
- **Do not read an exact boundary any other way.** Reading it as the texel above is wrong at about nine
  boundaries in ten. Evaluating the coordinate in single precision to see which side it lands trades
  rows both ways - 29 better and 16 worse - and so does consulting the card's model only at exact
  boundaries, 5 and 2.
- Do not round the texel coordinate to `1/256` of a texel before taking the texel, though the D3D11
  sampler spec describes exactly that. The reference card floors the interpolated float: rounding
  first moves 1629 of the 2234 stored sweep rows the wrong way at 26.1 and 24 the right way, every
  tropical fish by about `+1.1`.
- Do not snap corners to any grid but the card's `1/256` - a grid tuned to the fleet settles a whole
  staircase of texel edges the other way from vanilla wherever its rounding and the hardware's part.
  No snap at all is the same error: at 26.1 it moves 302 stored sweep rows the wrong way and 32 the
  right way. So is interpolating the texture coordinate from the unsnapped corners while coverage
  stays snapped: it moves the same 302 and 32.
- Do not change the snap's tie rule - round half to even moves 13 rows better and 14 worse, and half up
  in the GL frame 15 and 22, because the card's choice on a java midpoint is decided by vanilla's own
  vertex arithmetic rather than by a rule.
