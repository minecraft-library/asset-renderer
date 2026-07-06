/**
 * The rendering engine: rasterizers plus the composed subsystems that turn the abstract geometry
 * and texture data produced by {@link lib.minecraft.renderer.engine.kit kits} and the
 * {@link lib.minecraft.renderer.pipeline pipeline} into pixels.
 *
 * <p><b>Rasterizers.</b> Two engines draw into a {@link dev.simplified.image.pixel.PixelBuffer}:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.ModelEngine ModelEngine} - the 3D triangle rasterizer:
 *       barycentric coverage with a {@code 1/256} fixed-point edge test, an {@code OpenGL}-style
 *       top-left fill rule, a {@code 1/400} sub-pixel coverage snap, a tiled parallel raster path,
 *       depth buffering, painter's-algorithm coplanar tie-break, and a back-to-front sort for
 *       translucent triangles. Two-sided geometry via {@code cullBackFaces=false}, an emissive
 *       depth-skip for nested translucent overlays, and SIMD-dispatched vertex transforms when the
 *       JDK Vector API module is loaded.</li>
 *   <li>{@link lib.minecraft.renderer.engine.RasterEngine RasterEngine} - the 2D blitter (buffer
 *       allocation, blits, blends) for every flat renderer
 *       ({@link lib.minecraft.renderer.MenuRenderer MenuRenderer},
 *       {@link lib.minecraft.renderer.TextRenderer TextRenderer}, {@code FluidFace2D},
 *       {@code PortalFace2D}).</li>
 * </ul>
 *
 * <p><b>Composed subsystems.</b> Rather than an inheritance tower, each engine <em>composes</em>
 * single-responsibility subsystems, each with its own subpackage:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.camera.Camera camera} - the complete view + projection
 *       (baked {@code display.*} pose, {@code Lens}, and the lighting-pose Euler) a
 *       {@code ModelEngine} renders through; the pose is applied after the caller's model transform
 *       and the model-to-world {@link lib.minecraft.renderer.engine.camera.Placement Placement}. The
 *       named vanilla cameras are assembled by
 *       {@link lib.minecraft.renderer.engine.camera.Projection Projection}.</li>
 *   <li>{@link lib.minecraft.renderer.engine.light.Lighting light} - the vanilla-parity inventory
 *       lighting ({@code Lighting}) and shade application / block-icon relighting
 *       ({@code Shading}).</li>
 *   <li>{@link lib.minecraft.renderer.engine.texture.Textures texture} - the pack-aware texture
 *       resolution service each engine holds (and vends via {@code textures()}).</li>
 *   <li>{@link lib.minecraft.renderer.engine.raster raster} - the rasterizer's draw-list IR
 *       ({@code VisibleTriangle} / {@code SurfaceTraits}), the 2D coverage math
 *       ({@code RasterMath}), and the glint coverage mask.</li>
 * </ul>
 * Camera-to-screen projection ({@link lib.minecraft.renderer.engine.camera.Lens Lens})
 * sits with {@code Camera} in the camera subpackage; the general geometric primitives split across
 * {@link lib.minecraft.renderer.face face} (face enums) and
 * {@link lib.minecraft.renderer.tensor tensor} ({@code Box}, {@code EulerRotation});
 * frame-building lives with the post stages in
 * {@link lib.minecraft.renderer.engine.compose compose}.
 *
 * <p><b>Resource-provider port.</b>
 * {@link lib.minecraft.renderer.engine.RendererContext RendererContext} is the read-only view of
 * active texture packs, biome colormaps, model repositories, banner / item / entity registries, and
 * OptiFine-pack rule lists - the seam the {@link lib.minecraft.renderer.pipeline pipeline} fills and
 * every engine consumes. The interface uses two naming prefixes for {@code Optional}-returning
 * lookups:
 * <ul>
 *   <li><b>{@code findX(...)}</b> - direct keyed lookup, O(1)-ish, returns
 *       {@code Optional.empty()} when the key is unknown.</li>
 *   <li><b>{@code resolveX(...)}</b> - derived or transformative lookup (walks rule lists,
 *       decodes files off disk, combines multiple arguments). Returns
 *       {@code Optional.empty()} when no rule matches.</li>
 * </ul>
 * Bulk-iteration accessors that return {@code ConcurrentList} use bare names
 * ({@code knownBlockIds}, {@code knownItemIds}, etc.) and provide empty defaults so test stubs
 * only override what they care about.
 *
 * <p><b>Vanilla parity.</b> The triangle rasterizer reproduces vanilla's CPU-side vertex chain
 * bit-for-bit at the per-vertex level (verified by {@code [PX] TRI} per-vertex dumps against
 * the {@code vanilla-reference-harness} sibling repository) and applies hardware-style
 * conventions at the per-pixel level - {@code 1/256} fixed-point edge functions, top-left
 * fill, {@code 1/400} coverage snap. The snap is documented at length on
 * {@link lib.minecraft.renderer.engine.ModelEngine ModelEngine}; it is the deterministic cheap
 * workaround for hardware-specific GPU coverage that cannot be bit-reproduced in software at
 * any reasonable cost.
 *
 * @see lib.minecraft.renderer.engine.ModelEngine
 * @see lib.minecraft.renderer.engine.RendererContext
 * @see lib.minecraft.renderer.engine.raster.RasterMath
 */
package lib.minecraft.renderer.engine;
