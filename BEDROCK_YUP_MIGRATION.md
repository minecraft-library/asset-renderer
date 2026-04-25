# Bedrock Y-up Native Migration Plan

Goal: collapse the mob-entity pipeline's Bedrock→Java Y-flip, so geometry stays
in Bedrock's authored space (Y-up, absolute pivots, pass-through rotations) end
to end. Retires the rotation-sign ambiguity, simplifies the parser, unblocks the
bone-hierarchy math, and brings our stored `entity_geometry.json` into 1:1
correspondence with the Mojang `bedrock-samples` source.

## Scope confirmation

Two separate `YAxis` enums live in the tree. Only ONE is in scope:

- **IN SCOPE:** `lib.minecraft.renderer.asset.model.EntityModelData.YAxis`
  - Populated by `ToolingEntityModels.Parser.buildModel` (always `DOWN`).
  - Read only by `EntityGeometryKit.resolveFaceUv`.
  - Carried through the runtime on every mob-entity model.

- **OUT OF SCOPE:** `lib.minecraft.renderer.tooling.blockentity.YAxis`
  - Used only inside `ToolingBlockEntities` + `SourceDiscovery` during ASM
    extraction from the Java client jar.
  - Already handled at tooling time; downstream block-entity JSON is emitted as
    Y-up block-element format (`from`/`to` in 0-16 range).
  - `BlockEntityLoader`, `BlockRenderer`, and every downstream consumer are
    already Y-up.

**The block model pipeline (`block.json` elements) is already Y-up by Java
convention and was never affected by our Y-flip.** Nothing there changes.

## Files that change

### Core pipeline

1. `src/main/java/lib/minecraft/renderer/tooling/ToolingEntityModels.java`
   - `Parser.buildModel`:
     - Remove `javaPivot = { pivot.x, -pivot.y, pivot.z }`; store the authored
       `pivot` directly.
     - Remove the Y-flipping cube-origin formula. Replace:
       ```
       javaOrigin.y = -(origin.y - pivot.y) - size.y
       ```
       with plain bone-local subtraction:
       ```
       localOrigin.y = origin.y - pivot.y
       ```
     - Remove the Y-flip on cube pivots: `-(cubePivotRaw.y - pivot.y)` becomes
       `cubePivotRaw.y - pivot.y`.
     - Drop the `EntityModelData.YAxis.DOWN` argument from the final
       `new EntityModelData(...)` call.
   - Net: ~25 LOC removed, comment on the parser changes from "convert Bedrock
     to Java conventions" to "relativise cube origins to their owning bone".

2. `src/main/java/lib/minecraft/renderer/asset/model/EntityModelData.java`
   - Delete the `YAxis` enum and the `yAxis` field.
   - Delete the AllArgs / field comments referencing it.
   - Update `equals`/`hashCode` to drop the field.

3. `src/main/java/lib/minecraft/renderer/kit/EntityGeometryKit.java`
   - `buildTriangles` inner loop:
     - Remove `float ty = -transformed.y();`.
     - Vertex emit becomes the direct `transformed.y()` - no negation.
     - Remove the normal Y-negation
       `new Vector3f(rawNormal.x(), -rawNormal.y(), rawNormal.z())`.
     - Revert the reversed triangle winding: use `(corners[0], corners[1],
       corners[2])` + `(corners[0], corners[2], corners[3])` instead of the
       currently-swapped vertex order that compensates for the Y-flip.
   - `computeBounds`:
     - Remove `float cy = -c.y();`. Use `c.y()` directly.
   - `resolveFaceUv`:
     - Remove the `YAxis == DOWN` branch that swaps `UP <-> DOWN` atlas slots.
     - Remove the side-face UV row swap
       `return new Vector2f[]{ uv[1], uv[0], uv[3], uv[2] };`; return
       `uv` directly (`{uv[0], uv[1], uv[2], uv[3]}`).
     - Simplify the method signature: drop the `YAxis` parameter.
   - `buildAnchorFromChain` / `resolveChain` / `buildOwnTransform`:
     - Simplify now that pivots are in Bedrock-world coordinates.
     - Re-derive the chain math cleanly: for a bone B with parent P,
       `chain(B) = T(-P.pivot) * R(P) * T(P.pivot) * own(B)` (in whatever row/column
       order my matrix class uses). No reflection conjugation needed.
     - The `buildAnchorFromChain` formula that currently extracts
       "rotation-only" from a parent chain and loses translation should be
       replaced with a clean recursive compose.
   - Net: ~30 LOC removed + the hierarchy math becomes correct.

4. `src/main/java/lib/minecraft/renderer/tooling/ToolingEntityModels.java`
   - `Parser.buildModel` no longer sign-flips cube pivots' Y. The bone-pivot
     default on missing cube pivots works as-is (no change).
   - Rotation sign stays pass-through for both bone and cube (Bedrock-native).

### Runtime glue

5. `src/main/java/lib/minecraft/renderer/EntityRenderer.java`
   - No change expected. `inventoryYRotation` rotates around Y-axis which is
     invariant under the Y reflection, so the composition in
     `EntityRenderer.render` stays correct.
   - Sanity-check that `ArmorKit.buildEntityArmor3D` / its `boneBounds` input
     don't assume a Y-down frame (see `EntityGeometryKit.BuildResult`).

6. `src/main/java/lib/minecraft/renderer/kit/ArmorKit.java`
   - Uses `boneBounds` from the `BuildResult`. These bounds are now in Y-up.
     Verify the armor overlay positioning code doesn't silently assume Y-down.
     Likely a non-issue (armor is derived from entity bone positions, which
     both get flipped together), but worth an eyeball pass.

### Regenerated data

7. `src/main/resources/lib/minecraft/renderer/entity_geometry.json`
   - Regenerate via `./gradlew :asset-renderer:entityModels`. Every bone pivot's
     Y sign flips back to positive; every cube `origin.y` flips; the
     `y_axis` top-level field disappears.
   - The file will become much easier to diff against Mojang's source.

8. `src/main/resources/lib/minecraft/renderer/entity_models_overrides.json`
   - Every `bone_overrides` entry with an explicit `pivot` field needs its Y
     component sign-flipped back. Known entries:
     - `minecraft:wolf` `upperBody.pivot [-1, -10, -3]` -> `[-1, 14, -3]` or
       whatever Bedrock-authored value (cross-check against `wolf.geo.json`).
     - `minecraft:wolf` `body.pivot` (if I set one) — re-derive.
     - `minecraft:fox` `body.pivot [0, -8, -6]` -> `[0, 16, -6]`.
     - Any other per-entity pivot override.
   - Rotation overrides stay the same (pass-through convention, Y-axis
     rotations unchanged).

### Tests

9. `src/test/java/lib/minecraft/renderer/asset/model/EntityModelDataTest.java`
   - Remove assertions on `yAxis` field.
   - If any assertions check cube `origin.y` after parse, update them to the
     new (un-flipped) values.

10. `src/test/java/lib/minecraft/renderer/tooling/blockentity/*` tests
    - Check `SourceDiscoveryTest` / `TintDiscoveryTest` for incidental
      references to the removed `EntityModelData.YAxis`. Neither should break
      (they use `tooling.blockentity.YAxis`, a different enum).

## Migration order

Bite-sized, testable steps:

1. **Strip stale bone_overrides first.** With Mojang's geometry correct, many
   overrides are now double-applying. Prune to the minimum set: texture_id
   overrides for color-variant picks, `inventory_y_rotation` per-entity, and
   any genuinely-still-needed pivot patches. Regenerate + render to confirm
   which entities still need human help vs. Mojang-provided pose.

2. **Gate: verify fox + cat + dolphin on an isolated hierarchy fix.** Before
   touching the Y-flip, patch `buildAnchorFromChain` to stop losing parent
   translations (the bug that floats fox's head). This confirms the hierarchy
   bug is separate from the Y-flip scope and makes a clean regression baseline.

3. **Parser: stop flipping.**
   - Change `Parser.buildModel` to keep Bedrock pivots / origins as-authored
     (still subtract bone pivot for cube origins to land them in bone-local
     space; just no Y sign flip).
   - Keep emitting `YAxis.DOWN` for now (we'll delete the field in step 5).
   - Regenerate `entity_geometry.json`. Every entity currently working should
     break, every rotation sign should need the opposite; this is expected and
     we're about to flip the runtime to match.

4. **Runtime: stop flipping back.**
   - `EntityGeometryKit` drops the `ty = -transformed.y()` flip and the
     triangle-winding reversal.
   - `resolveFaceUv` drops the side-face UV row swap and the YAxis atlas-slot
     branch.
   - Render. Expect things to match the pre-step-3 state but with cleaner math.
     Fox/cat/dolphin/etc. that were working should stay working; the ones
     already broken on hierarchy should be unchanged.

5. **Delete the YAxis enum + field.** Remove `EntityModelData.YAxis`, the
   `yAxis` field, the `resolveFaceUv` `YAxis` parameter, and the `0f, bones`
   / `YAxis.DOWN, 0f, bones` constructor arguments. Update the test suite.

6. **Fix the overrides file.** Y-flip the explicit pivot values in
   `entity_models_overrides.json` so they address the new Bedrock-native
   coordinate frame.

7. **Full sweep + commit.** `./gradlew test` + `./gradlew testEntity` + spot
   check 10 representative entities (humanoid, quadruped-Y-up-body,
   quadruped-Y-down-body, fish, flying, block-entity-reused-as-mob if any).

## What this does NOT change

- `BlockRenderer`, `FluidRenderer`, `PortalRenderer` — all block-model path.
- `ToolingBlockEntities`, `BlockEntityLoader`, `block_entities.json` - already
  Y-up.
- `BlockModelData`, `ModelElement` - Y-up block-element format.
- The iso camera + `PerspectiveParams.ISOMETRIC_BLOCK` - already consumes Y-up
  triangles (that's why we flip right before emitting them today).
- `inventory_y_rotation` semantics - Y-axis yaw is invariant under Y-reflection.

## Risk inventory

- **Block-entity <-> mob-entity interop.** A few block entities historically
  went through `EntityModelData` (chest family via its `YAxis.UP` case). Check
  whether any current code path loads a `block_entities.json`-derived model
  into an `EntityModelData` instance. If so, that path needs its Bedrock/Java
  convention aligned at the load point. Audit: grep for `EntityModelData` uses
  outside the mob-entity path.

- **Armor overlay placement.** `ArmorKit.buildEntityArmor3D` positions
  helmet/chestplate/leggings/boots by the `boneBounds` from
  `EntityGeometryKit`. Bounds will now be in Y-up. If armor positioning maths
  assume Y-down (pivoting armor around a "head" bound with Y-min at the top),
  armor renders will invert. Manual check with a humanoid wearing diamond
  armor (e.g. `testEntity -PentityId=minecraft:zombie` with armor options).

- **Rotation sign convention regressions.** The current `bone_overrides`
  entries were tuned empirically against the Y-down frame. Even if most work
  in Y-up, any entry that accidentally encoded a sign-flip-derived value will
  need re-verification.

- **`EntityModelDataTest` fixtures.** Any hand-written fixture that calls the
  constructor with explicit Y-coords needs updating. Probably 2-3 tests.

## Expected win

- ~70-90 LOC removed across `ToolingEntityModels`, `EntityGeometryKit`,
  `EntityModelData`.
- `YAxis` enum + field retired.
- One whole category of rotation-sign bugs retired: Bedrock `[90, 0, 0]` means
  standard R_x(+90°), no per-axis flip, no conjugation debate.
- `buildAnchorFromChain` becomes a short clean compose instead of a
  reflection-aware extraction with a known translation bug.
- `entity_geometry.json` becomes 1:1 diffable against Mojang's
  `bedrock-samples` geometry files, which makes future Mojang-version bumps
  almost trivial.
- Every future entity added to Bedrock (new Mojang release) should "just work"
  without us hand-deriving rotations from `javap` on the client jar.

## Deferred items

Independent of this migration but related to the residual mob-entity issues:

- Bat texture_id override (Mojang's path points at
  `textures/entity/bat/bat.png` but the bat is black-on-missing in Java 1.21+
  because Java split into `bat_normal`/`bat_berserk`; needs a texture override).
- Rabbit head cube `mirror: true` UV bug - orthogonal to Y-flip; live in
  `BlockFace.defaultUv`'s mirror branch.
- Sheep wool two-layer rendering - orthogonal; requires supporting two
  textures on one model or re-rendering with a wool overlay pass.
- Creeper charged layering - same two-layer problem.
