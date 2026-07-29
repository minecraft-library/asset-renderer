/**
 * The face vocabulary the geometry kits unwrap cubes through - one direction enum, the two things that
 * vary per direction, the turn that relates two frames, the humanoid part table, and the small
 * face-keyed holder. This package stays free of engine state (no {@code RendererContext}, no rasterizer
 * dependency), so the kits and the tests reuse it without dragging the engine in. The engine-specific
 * pieces live elsewhere: camera-to-screen projection on {@code engine.camera.Lens}, the draw-list IR and
 * 2D coverage math under {@code engine.raster}, lighting / shading under {@code engine.light}.
 *
 * <p><b>The face vocabulary never crosses the JSON boundary.</b> Not one of the tooling files
 * references any type here, in either direction: a face is a derivation over a cube's
 * {@code (uv, size, mirror)} triple, not something the persisted model names. The tooling's entire
 * typed coupling to the renderer domain is a handful of {@code asset} imports.
 *
 * <p><b>Two axes, not two subjects.</b> Minecraft draws one cube face two ways, and the split is
 * <b>not</b> block-versus-entity: what varies is a corner phase and an unwrap, those vary independently
 * of each other and of the subject, and a caller picks one of each. The block-entity path builds with
 * the polygon phase; {@link lib.minecraft.renderer.engine.kit.ShieldKit ShieldKit} - an item path -
 * builds with the bakery phase and textures through the atlas unwrap, and is correct.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.face.Face Face} - the six cardinal directions, each carrying its
 *       normal, the vanilla {@code Lighting.ITEMS_3D} per-face shade scalar, and the per-face columns
 *       the two unwraps read.</li>
 *   <li>{@link lib.minecraft.renderer.face.CornerPhase CornerPhase} - which of a face's four corners a
 *       quad starts at, and the matching UV slot pairing. The two phases split a face on opposite
 *       diagonals and neither is derivable from the other.</li>
 *   <li>{@link lib.minecraft.renderer.face.Unwrap Unwrap} - how a texture rectangle is derived: a block
 *       element's <em>position</em> reflected about 16, or a cube <em>origin</em> offset by a linear
 *       form in the cube's sizes.</li>
 *   <li>{@link lib.minecraft.renderer.face.Turn Turn} - the order-8 diagonal group every frame relation
 *       the renderer performs is a member of.</li>
 *   <li>{@link lib.minecraft.renderer.face.HumanoidPart HumanoidPart} - the six boxes a player model
 *       is built from, and the skin regions their faces read. Its rectangles are the atlas unwrap
 *       under the model-frame turn, so the player skin unwrap and the entity cube unwrap are one
 *       function.</li>
 *   <li>{@link lib.minecraft.renderer.face.SixFaces SixFaces} - small immutable holder of one value
 *       per face, used for per-face texture maps and other small face-keyed tables.</li>
 * </ul>
 *
 * @see lib.minecraft.renderer.face.Face
 * @see lib.minecraft.renderer.face.CornerPhase
 * @see lib.minecraft.renderer.face.Unwrap
 */
package lib.minecraft.renderer.face;
