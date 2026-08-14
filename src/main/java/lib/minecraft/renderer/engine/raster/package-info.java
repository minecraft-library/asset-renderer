/**
 * The rasterizer's data types and 2D coverage math - the pipeline-specific pieces that flow into
 * {@link lib.minecraft.renderer.engine.ModelEngine ModelEngine}, as opposed to the general
 * geometric primitives in {@link lib.minecraft.renderer.face face}.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.raster.VisibleTriangle VisibleTriangle} - the single
 *       draw-list record every {@link lib.minecraft.renderer.engine.kit kit} emits and the engine
 *       consumes: vertex positions, UVs, source texture, tint, normal, shading scalar, and
 *       {@link lib.minecraft.renderer.engine.raster.SurfaceTraits SurfaceTraits}.</li>
 *   <li>{@link lib.minecraft.renderer.engine.raster.SurfaceTraits SurfaceTraits} - the three
 *       behavior flags a builder decides per cube ({@code cullBackFaces}, {@code translucent},
 *       {@code glinted}) plus the pass the triangle was submitted under.</li>
 *   <li>{@link lib.minecraft.renderer.engine.raster.PassDeclaration PassDeclaration} - what vanilla
 *       declares about a whole render pass rather than about one surface drawn through it
 *       ({@code emissive}, {@code blend}, {@code alpha}, {@code writesDepth}, {@code sorted}), shared
 *       by every triangle one build emits.</li>
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
 * restricts the enchantment glint to it) is {@link dev.simplified.image.pixel.PixelMask}, owned by the
 * {@link dev.simplified.image.pixel.PixelBuffer} it covers.
 *
 * @see lib.minecraft.renderer.engine.ModelEngine
 */
package lib.minecraft.renderer.engine.raster;
