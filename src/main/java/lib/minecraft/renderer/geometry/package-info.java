/**
 * The shared geometric primitives every layer reuses - the face-unwrap enums plus the {@code Box}
 * and {@code EulerRotation} value types that {@code options}, {@code asset}, {@code tooling}, and
 * the renderers all bind. This package stays free of engine state (no {@code RendererContext}, no
 * rasterizer dependency) so kit code, tooling, and tests reuse it freely. The engine-specific pieces
 * live elsewhere: camera-to-screen projection on {@code engine.camera.Projection}, the draw-list IR
 * and 2D coverage math under {@code engine.raster}, lighting / shading under {@code engine.light}.
 *
 * <p><b>Face enums.</b> Three distinct enums cover the three contexts in which a Minecraft
 * asset cube is unwrapped. Each one carries the per-face data the corresponding kit needs
 * (vertex order, UV layout, normals, lighting) so call sites can index into a single source of
 * truth instead of branching on direction:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.geometry.BlockFace BlockFace} - block-model UV unwrap with the
 *       vanilla {@code AmbientOcclusionFace} lighting scalar baked in. Used by
 *       {@link lib.minecraft.renderer.engine.kit.BlockGeometryKit BlockGeometryKit}.</li>
 *   <li>{@link lib.minecraft.renderer.geometry.EntityFace EntityFace} - entity-cube UV unwrap (strip
 *       layout, per-face polygon vertex slot permutation). Used by
 *       {@link lib.minecraft.renderer.engine.kit.EntityGeometryKit EntityGeometryKit}. Lighting is
 *       computed per-vertex from the surface normal rather than per-face.</li>
 *   <li>{@link lib.minecraft.renderer.geometry.SkinFace SkinFace} - 64x64 player-skin atlas UV layout
 *       used by both the skin texture and the matching base-armor atlas. Used by
 *       {@link lib.minecraft.renderer.PlayerRenderer PlayerRenderer} and
 *       {@link lib.minecraft.renderer.engine.kit.ArmorKit ArmorKit}.</li>
 *   <li>{@link lib.minecraft.renderer.geometry.SixFaces SixFaces} - small immutable holder of one
 *       value per face, used for per-face texture id maps, tint indices, and other small
 *       face-keyed tables.</li>
 * </ul>
 *
 * <p><b>Primitives.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.geometry.Box Box} - immutable axis-aligned bounding box,
 *       replacement for the ad-hoc {@code (Vector3f min, Vector3f max)} pairs that previously
 *       lived in several packages. Carries point-list AABB fitting and box arithmetic.</li>
 *   <li>{@link lib.minecraft.renderer.geometry.EulerRotation EulerRotation} - immutable
 *       pitch / yaw / roll triple, the parameter shape every renderer accepts for caller-side
 *       rotation. Internal use converts to a
 *       {@link lib.minecraft.renderer.tensor.Quaternionf Quaternionf} or chained rotation
 *       matrices.</li>
 * </ul>
 *
 * @see lib.minecraft.renderer.geometry.Box
 * @see lib.minecraft.renderer.geometry.EulerRotation
 */
package lib.minecraft.renderer.geometry;
