/**
 * The face vocabulary the geometry kits unwrap cubes through - the two per-face tables, the turn that
 * relates their frames, the humanoid part table, and the small face-keyed holder. This package stays
 * free of engine state (no {@code RendererContext}, no rasterizer dependency), so the kits and the
 * tests reuse it without dragging the engine in. The engine-specific pieces live elsewhere:
 * camera-to-screen projection on {@code engine.camera.Lens}, the draw-list IR and 2D coverage math
 * under {@code engine.raster}, lighting / shading under {@code engine.light}.
 *
 * <p><b>The face vocabulary never crosses the JSON boundary.</b> Not one of the tooling files
 * references any type here, in either direction: a face is a derivation over a cube's
 * {@code (uv, size, mirror)} triple, not something the persisted model names. The tooling's entire
 * typed coupling to the renderer domain is a handful of {@code asset} imports.
 *
 * <p><b>Face enums.</b> Two enums cover the two ways a Minecraft asset cube is unwrapped. Each
 * carries the per-face data its kit needs - vertex order, UV layout, normals, lighting - so call
 * sites index one source of truth instead of branching on direction. <b>The split is not
 * block-versus-entity</b>: what each enum fuses is a corner phase and an unwrap, and those vary
 * independently, so a caller picks one of each. The block-entity path iterates
 * {@link lib.minecraft.renderer.face.EntityFace EntityFace}, and
 * {@link lib.minecraft.renderer.engine.kit.ShieldKit ShieldKit} - an item path - takes its geometry
 * from the block face and its UV rectangle from the entity face in one loop, and is correct.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.face.BlockFace BlockFace} - block-model UV unwrap, which
 *       reflects an element's <em>position</em> about 16, with the vanilla {@code Lighting.ITEMS_3D}
 *       per-face shade scalar baked in.</li>
 *   <li>{@link lib.minecraft.renderer.face.EntityFace EntityFace} - entity-cube UV unwrap, which
 *       offsets a per-cube <em>origin</em> by a linear form in the cube's sizes, plus the per-face
 *       polygon vertex slot permutation. Lighting is computed per-vertex from the surface normal
 *       rather than per-face.</li>
 *   <li>{@link lib.minecraft.renderer.face.Turn Turn} - the order-8 diagonal group every frame
 *       relation between the two is a member of.</li>
 *   <li>{@link lib.minecraft.renderer.face.HumanoidPart HumanoidPart} - the six boxes a player model
 *       is built from, and the skin regions their faces read. Its rectangles are the entity unwrap
 *       under the model-frame turn, so the player skin unwrap and the entity cube unwrap are one
 *       function.</li>
 *   <li>{@link lib.minecraft.renderer.face.SixFaces SixFaces} - small immutable holder of one value
 *       per face, used for per-face texture maps and other small face-keyed tables.</li>
 * </ul>
 *
 * @see lib.minecraft.renderer.face.BlockFace
 * @see lib.minecraft.renderer.face.EntityFace
 */
package lib.minecraft.renderer.face;
