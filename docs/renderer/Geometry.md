# Geometry rules

Load when touching `engine/geometry` - `Face`, `CornerPhase`, `Unwrap`, `AxisSigns`, `Box`,
`EulerRotation` - `BoxKit`, `BoneKit`, `HumanoidPart`, a JOML rotation factory, or the order an
entity's corners are rounded in. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

## Faces, corner phase and unwrap

There is one `Face`; what varies per direction lives on `CornerPhase` and `Unwrap`, which vary
independently of each other and of the subject - the block-entity path takes `POLYGON` and
`ShieldKit`, an item path, takes `BAKERY`, both correct.

- `CornerPhase` fixes which corner a quad starts at, and with it the fan's diagonal and the UV slot
  paired with each vertex. Every `BAKERY` index array is a cyclic rotation of its `POLYGON`
  counterpart, never a reversal, so the two split on opposite diagonals and neither derives from the
  other; `BAKERY`'s pairing is the identity by construction.
- The fan is emitted in one place, `BoxKit.addQuad`; `FluidGeometryKit.addNonPlanarTop`
  cannot join, because a sloped top's four corners are not coplanar.
- The CTM grammar is a spelling, not a second direction vocabulary: `CtmRule.faces` is an
  `EnumSet<Face>` and the dialect stops at `CtmParser.parseFaceSet`.

## Frame turns

`engine.geometry.AxisSigns` is the order-8 diagonal group: every frame relation pairs a face with
itself or its own opposite, so each is `diag(+-1, +-1, +-1)` and a ninety-degree turn appears nowhere.
Six elements are in use - `HALF_X` (model to upright frame), `HALF_Z` (the cape's read frame, `HALF_X`
composed with the cape's yaw of `PI`), `MIRROR_Y` (the shading flip an entity's folded stack
is relit through), `MIRROR_Z` (the same relation for the player's upright boxes), `MIRROR_X` (the cube
`mirror` flag's face swap) and `INVERT` (the camera-facing flip both `EntityLighting.shade` and the
block-icon relight take). `NONE` is declared and named nowhere in production.

- `MIRROR_Y` and `MIRROR_Z` are one relation reached from two frames, and the group law is what says
  so: `HALF_X.then(MIRROR_Y) == MIRROR_Z`, by mask xor. So a list's turn is a property of the normal
  it stores rather than of the lighting entry, and the same list is owed a different member on either
  side of a frame change - which is why the turn and the list are one argument in practice and never
  independently chosen.

- An `SO(3)`-only abstraction cannot express it: four relations are reflections, and a mirrored shell
  cube is `HALF_X.then(MIRROR_X)` rather than a ninth relation.
- The face map reads a face's axis off its normal and its opposite off one bit of its ordinal, which
  holds because `Face` declares its constants in opposing pairs - asserted, not assumed.
- Naming the relation does not make divergence unrepresentable; it buys one greppable token with a
  test pinning its value.
- A frame change and a shading flip are two turns, and separating them is what keeps each one
  greppable: `EntityArmorKit.intoModelFrame` applies `HALF_X` to a shell's geometry and stored normal, and
  the `MIRROR_Y` that lights it is the fold's, one argument at one call.
- A face map moves a strip between faces and cannot turn one in its own plane. Read through `HALF_X`,
  a cube's `UP` strip lays its top row on the upright box's min-Z edge where `BAKERY`'s `DOWN` walk lays
  a crop's top row on max Z, so every box built upright from a cube strip reverses `DOWN`'s rows after
  reading it: `HumanoidPart.textures` and `WornBox.Mesh.textures` on a copy of the crop,
  `ShieldKit.addBox` on its v bounds. The cape's yaw also turns its two cap strips half a turn in
  theirs, so `PlayerAssembly.capeTextures` turns those two crops after reading them through `HALF_Z`;
  on the one-row hem that half turn is the same texels as the column reversal `DOWN` owes in that
  frame. A `FaceTextures` supplier writes into no buffer it did not allocate, so the `DOWN` reversal
  and the cape's half turn each hand out a buffer of their own, and a crop that is ever cached or
  shared stays safe to hand out.

## Boxes and unwraps

- Every cube box is formed in vanilla's operand order and `Box.grown` is the one place it happens:
  the corners are `origin - grow` and `(origin + size) + grow`, as `ModelPart$Cube` forms them.
  `((origin - grow) + size) + grow + grow` is algebraically the same and arithmetically not.
- A bone's uniform `scale` multiplies each operand, never the assembled corners, so
  `BoneKit.scaledCubeBounds` scales origin, size and grow separately and `x * 1f == x` stays free.
- Derive each lattice endpoint from its own integer pixel offset, never `origin + extent * u`:
  `n * 0.03f` is one ULP low at `n = 12` and every limb is 12 px, so `HumanoidPart` stores `maxPixelX`,
  `maxPixelY` and `maxPixelZ` rather than a span.
- `HumanoidPart`'s skin rectangles are `Unwrap.Atlas.rect` at the part's atlas origin under
  `AxisSigns.HALF_X`, derived rather than tabulated, and a scope's extent and both layouts follow from the
  union of its parts' boxes, so `PlayerOptions.Type` owns them.
- The skull scope shares that arithmetic and never the constant or the predicate: `0.02f` and
  `OVERLAY_INFLATE` are two calibrations at two u/px scales, and unifying the gate deletes the hat
  layer on every legacy 64x32 skin.
- The 64x32 left-limb fallback is the production armour path, the armour atlas being 64x32, and it
  substitutes a different part's atlas origin, so no permutation of the part's own strip reaches it.
- `Unwrap.Atlas.crop` stays a cropped buffer: `buildBox` addresses each face's `W x H` buffer by the
  full `[0,1]` UV rect, so a sheet plus a rectangle is a sampler change, and the two part company on
  a mirrored cube where the crop reverses the row and a mirrored UV interpolates a descending `u`.
- `Unwrap.Atlas.rect` ignores its own `mirror` flag, because six entity-kit predicates ask what a
  face's own strip holds and the caller applies `AxisSigns.MIRROR_X`; only `crop` applies both.

## JOML factories

The factory name orders the quaternion product; application to `v` is reversed, because
`q . v . q^-1` composes right to left. Vanilla uses both.

| Factory | Product | Applies to `v` | Site |
|---|---|---|---|
| `rotationZYX(z, y, x)` | `q_z . q_y . q_x` | X, then Y, then Z | `BoneKit.applyBoneRotation` |
| `rotationXYZ(x, y, z)` | `q_x . q_y . q_z` | Z, then Y, then X | `Camera.buildGuiDisplayTransform` |

## Decisions that stay closed

- **Do not switch `EulerRotation`'s degrees-to-radians conversion to vanilla's float multiply.**
  `(float) Math.toRadians(value)` is not vanilla's route - `ItemTransform.apply`, both
  `CuboidRotation` forms and `Axis.rotationDegrees` multiply in float by `0.017453292f` - and the
  two differ by one ULP for 66 of the 721 integer degrees in `[-360, 360]`. No 26.1 display or
  element angle is among them: the 40 distinct `display` rotation values across `models/block`
  and `models/item` and the element angles `0`, `+-22.5` and `+-45` convert identically either way.
  Where the renderer converts and vanilla does not - a bone angle, stored in degrees by the
  geometry table and folded back to degrees on a posed channel, where vanilla's part field holds
  the radian - the route in place already renders as vanilla's radians do: forcing the three
  rest radians the degrees lose (the wither's tail `0.83252203f`, the armadillo's ear cubes
  `+-0.0718f`) and every written channel's exact radian together moved no byte of the still,
  idle and walk sweeps of eighteen entities, and the rest radians alone moved none of six 3D
  renders. The float multiply instead loses the goat's nose `0.9599f`, one pixel of its 512-px
  render, and lands 21 of the ender dragon's 217 posed radians on a different float, moving all
  eight of its idle and eight walk frames. One ULP can reach the raw bytes - the dragon's frames
  move on it - so this is a measurement at 26.1 rather than a tolerance, and a version bump
  re-opens it. `EulerRotationVanillaAnglesTest` re-reads the display and element half from the
  client jar's vanilla pack, so a bump that authors an angle the two routes part on fails there.
- **Do not carry a bone's rotation in radians.** A bone holds its rotation as an `EulerRotation` in
  degrees, where vanilla's `ModelPart` holds the float radian, so a radian no float degree converts
  back to is one ULP off - three rest values at 26.1, the wither's tail and the adult armadillo's
  ear cubes. `BoneRestRadianTest` in the tooling suite pins that set against the client jar, so a
  bump that moves it fails there. The measurement under the bullet above covers both the rest and the
  posed half and moved no byte, and degrees keep the shipped geometry table readable; carrying radians would
  retype every reader of a bone's rotation, the generator, the table and the pipeline dump.
- Do not give `Unwrap.Element` and `Unwrap.Atlas` one signature - `Element` reflects a position about
  16 and `Atlas` never receives one, so a merged method ignores an argument.
- Do not add a normalising overload to `Unwrap` - the divisor means three things, so one scale
  argument rescales every block-element UV on an HD pack.
- **Do not build part of an entity's corners in vanilla's float order.** Both sides apply the same
  transforms and round them in a different order, so a corner lands a few float steps from vanilla's,
  and where one sits that close to a midpoint between two steps the card snaps the two apart. Every
  partial port measured gains overall and re-decides those contests both ways: vanilla's rotation
  entries alone, `-1.782` on a fleet sum of `661.266`, take the charged creeper's swirl and cost the
  husk's worn shell up to `0.026`; the canvas offset folded into the matrix first, `-0.116`, takes the
  zombie villager instead; both together, `-0.016`, take neither. So the port is taken whole or not at
  all, and the whole port - what it reaches and what it is worth - is open in [KnownOpen.md](../KnownOpen.md).
