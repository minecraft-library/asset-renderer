/**
 * Geometric primitives and math used by every kit and engine in the module. Engine-coupled
 * projection ({@code projectPerspective}, {@code applyShading}) lives on
 * {@link RenderEngine RenderEngine}; this package stays free of
 * engine state so kit code, tooling, and tests can reuse the math without dragging in
 * {@code RendererContext}.
 *
 * <p><b>Face enums.</b> Three distinct enums cover the three contexts in which a Minecraft
 * asset cube is unwrapped. Each one carries the per-face data the corresponding kit needs
 * (vertex order, UV layout, normals, lighting) so call sites can index into a single source of
 * truth instead of branching on direction:
 * <ul>
 *   <li>{@link BlockFace} - block-model UV unwrap with the
 *       vanilla {@code AmbientOcclusionFace} lighting scalar baked in. Used by
 *       {@link BlockGeometryKit BlockGeometryKit}.</li>
 *   <li>{@link EntityFace} - entity-cube UV unwrap (strip
 *       layout, per-face polygon vertex slot permutation). Used by
 *       {@link EntityGeometryKit EntityGeometryKit}. Lighting is
 *       computed per-vertex from the surface normal rather than per-face.</li>
 *   <li>{@link SkinFace} - 64x64 player-skin atlas UV layout
 *       used by both the skin texture and the matching base-armor atlas. Used by
 *       {@link PlayerRenderer PlayerRenderer} and
 *       {@link ArmorKit ArmorKit}.</li>
 *   <li>{@link SixFaces} - small immutable holder of one
 *       value per face, used for per-face texture id maps, tint indices, and other small
 *       face-keyed tables.</li>
 * </ul>
 *
 * <p><b>Primitive math.</b>
 * <ul>
 *   <li>{@link ProjectionMath} - 2D rasterization math:
 *       barycentric coordinates, signed triangle areas, the {@code 1/256} fixed-point sub-pixel
 *       sample grid, and the {@code EdgeCoefficients} record that drives Pineda incremental
 *       edge functions in the {@link ModelEngine ModelEngine}
 *       inner loop.</li>
 *   <li>{@link Box} - immutable axis-aligned bounding box,
 *       replacement for the ad-hoc {@code (Vector3f min, Vector3f max)} pairs that previously
 *       lived in several packages. Carries point-list AABB fitting and box arithmetic.</li>
 *   <li>{@link EulerRotation} - immutable
 *       pitch / yaw / roll triple, the parameter shape every renderer accepts for caller-side
 *       rotation. Internal use converts to a
 *       {@link Quaternionf Quaternionf} or chained rotation
 *       matrices.</li>
 *   <li>{@link PerspectiveParams} - perspective vs orthographic
 *       blend (vanilla item icons are orthographic, blocks use a slight perspective tilt).
 *       Passed to every {@code rasterize} call.</li>
 *   <li>{@link Biome} - the four cardinal biome categories
 *       ({@code PLAINS}, {@code SWAMP}, {@code DARK_OAK}, {@code NETHER}, etc.) plus the
 *       fallback that pack overrides key against.</li>
 * </ul>
 *
 * <p><b>Triangle record.</b>
 * {@link VisibleTriangle VisibleTriangle} is the single
 * record every {@link lib.minecraft.renderer.kit kit} emits and every
 * {@link ModelEngine ModelEngine} consumes - vertex positions,
 * UV coordinates, source texture, tint, normal, shading scalar, plus three behavior flags
 * ({@code cullBackFaces}, {@code emissive}, {@code translucent}) and an optional
 * {@code debugTag} for per-pixel trace dumps.
 *
 * @see lib.minecraft.renderer.geometry.VisibleTriangle
 * @see lib.minecraft.renderer.geometry.ProjectionMath
 * @see lib.minecraft.renderer.engine.RenderEngine
 */
package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.geometry.Biome;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EntityFace;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.ProjectionMath;
import lib.minecraft.renderer.geometry.SixFaces;
import lib.minecraft.renderer.geometry.SkinFace;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.ArmorKit;
import lib.minecraft.renderer.kit.BlockGeometryKit;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.tensor.Quaternionf;
