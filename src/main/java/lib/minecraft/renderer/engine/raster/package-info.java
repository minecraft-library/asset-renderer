/**
 * Turning one draw list into one pixel buffer - the triangle rasterizer, and the two bodies of
 * arithmetic whose one reader it is.
 *
 * <p>{@link lib.minecraft.renderer.engine.raster.Rasterizer Rasterizer} draws a list of
 * {@link lib.minecraft.renderer.engine.draw.VisibleTriangle VisibleTriangle}s into a
 * {@link dev.simplified.image.pixel.PixelBuffer PixelBuffer} through the
 * {@link lib.minecraft.renderer.engine.camera.Camera Camera} and
 * {@link lib.minecraft.renderer.engine.camera.Placement Placement} it is built with: barycentric
 * coverage with a {@code 1/256} fixed-point edge test, an {@code OpenGL}-style top-left fill rule, a
 * {@code 1/400} sub-pixel coverage snap, a tiled parallel raster path, depth buffering, a
 * painter's-algorithm coplanar tie-break, and a back-to-front sort for translucent triangles and
 * {@code sortOnUpload} passes. Two-sided geometry opts out of culling via {@code cullBackFaces=false},
 * a pass vanilla registers with its depth write disabled lets nested layers accumulate against the
 * opaque depth behind them, and vertex transforms dispatch to SIMD when the JDK Vector API module is
 * loaded.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.raster.RasterMath RasterMath} - 2D coverage math:
 *       barycentric coordinates, the {@code 1/256} fixed-point sub-pixel grid, and the
 *       {@code EdgeCoefficients} that drive the Pineda incremental edge functions / top-left fill
 *       rule the inner loop walks per pixel.</li>
 *   <li>{@link lib.minecraft.renderer.engine.raster.DepthMath DepthMath} - depth math: the window-depth
 *       grid vanilla resolves a fragment against, the {@code GL_LEQUAL} test taken over it, and the
 *       unsnapped-plane re-read that keeps the coverage snap from moving depth.</li>
 * </ul>
 * <p>
 * The per-pixel coverage mask a render records (marking glinted geometry so the foil compositor
 * restricts the enchantment glint to it) is {@link dev.simplified.image.pixel.PixelMask PixelMask}, owned by
 * the {@link dev.simplified.image.pixel.PixelBuffer PixelBuffer} it covers.
 *
 * <p>A type no render reaches does not belong here, and neither does one whose code, imports and
 * javadoc aside, names a Minecraft subject - a block, an item, an entity, a {@code minecraft:} id or
 * a vanilla class - or one that imports from {@code vanilla}, {@code asset}, {@code request},
 * {@code port}, {@code content}, {@code bake} or the root package. A draw-list type
 * other packages read is {@link lib.minecraft.renderer.engine.draw engine.draw}'s, not this one's.
 *
 * <p><b>Parity.</b> Everything here is part of a render, and the pipeline dump serialises loaded data
 * without calling a renderer, so the package declares the {@code engine-renders} claim as a demotion
 * of the dump's verdict on a change made here.
 *
 * @see lib.minecraft.renderer.engine.raster.Rasterizer
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.raster;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
