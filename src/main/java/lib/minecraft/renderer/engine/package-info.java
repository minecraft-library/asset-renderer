/**
 * Rendering engines that turn the abstract geometry and texture data produced by
 * {@link lib.minecraft.renderer.kit kits} and {@link lib.minecraft.renderer.pipeline pipeline}
 * into pixels.
 *
 * <p><b>Layered design.</b> Each engine extends the previous one and adds exactly one
 * capability, so a renderer reaches for the smallest engine that meets its needs:
 * <ol>
 *   <li>{@link RenderEngine} - the baseline interface plus
 *       static helpers ({@code projectPerspective}, {@code applyShading},
 *       {@code computeInventoryLighting}, output building). Helpers live as {@code static} so
 *       every concrete engine can reach them without an instance. Instance state begins on
 *       {@link TextureEngine TextureEngine}.</li>
 *   <li>{@link TextureEngine} - adds pack-aware texture
 *       resolution, biome tint sampling, glint compositing, and animation frame extraction. The
 *       baseline for any renderer that needs to read textures.</li>
 *   <li>{@link RasterEngine} - adds 2D drawing primitives
 *       (buffer creation, blits, blends). Used by every 2D renderer
 *       ({@link MenuRenderer MenuRenderer},
 *       {@link TextRenderer TextRenderer},
 *       {@code FluidFace2D}, {@code PortalFace2D}).</li>
 *   <li>{@link ModelEngine} - adds the 3D triangle rasterizer:
 *       barycentric coverage with a {@code 1/256} fixed-point edge test, an
 *       {@code OpenGL}-style top-left fill rule, a {@code 1/400} sub-pixel coverage snap, a
 *       tiled parallel raster path, depth buffering, painter's algorithm coplanar tie-break,
 *       and a back-to-front sort for translucent triangles. Implements two-sided geometry via
 *       {@code cullBackFaces=false}, an emissive depth-skip for nested translucent overlays,
 *       and SIMD-dispatched vertex transforms when the JDK Vector API module is loaded.</li>
 *   <li>{@link IsometricEngine} - a {@code ModelEngine} subclass
 *       whose camera transform is a named vanilla {@code display.gui} pose. Use
 *       {@link IsometricEngine#standard standard()} for the
 *       canonical {@code [30, 225, 0]} block-icon view or
 *       {@link IsometricEngine#withGuiPose(RendererContext, EulerRotation) withGuiPose(pose)}
 *       for per-model overrides.</li>
 * </ol>
 *
 * <p><b>Ambient context.</b>
 * {@link RendererContext RendererContext} is the read-only
 * view of active texture packs, biome colormaps, model repositories, banner / item / entity
 * registries, and OptiFine-pack rule lists. Every engine is constructed against one. The
 * interface uses two naming prefixes for {@code Optional}-returning lookups:
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
 * {@link ModelEngine ModelEngine}; it is the deterministic cheap
 * workaround for hardware-specific GPU coverage that cannot be bit-reproduced in software at
 * any reasonable cost.
 *
 * @see lib.minecraft.renderer.engine.ModelEngine
 * @see lib.minecraft.renderer.engine.IsometricEngine
 * @see lib.minecraft.renderer.engine.RendererContext
 * @see lib.minecraft.renderer.geometry.ProjectionMath
 */
package lib.minecraft.renderer.engine;

import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.TextureEngine;
import lib.minecraft.renderer.geometry.EulerRotation;
