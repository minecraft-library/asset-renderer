/**
 * The rasterizer's data types and 2D coverage math - the pipeline-specific pieces that flow into
 * {@link lib.minecraft.renderer.engine.ModelEngine ModelEngine}, as opposed to the general
 * geometric primitives in {@link lib.minecraft.renderer.geometry geometry}.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.raster.VisibleTriangle VisibleTriangle} - the single
 *       draw-list record every {@link lib.minecraft.renderer.engine.kit kit} emits and the engine
 *       consumes: vertex positions, UVs, source texture, tint, normal, shading scalar, and
 *       {@link lib.minecraft.renderer.engine.raster.SurfaceTraits SurfaceTraits}.</li>
 *   <li>{@link lib.minecraft.renderer.engine.raster.SurfaceTraits SurfaceTraits} - per-triangle
 *       behavior flags ({@code cullBackFaces}, {@code emissive}, {@code translucent},
 *       {@code glinted}).</li>
 *   <li>{@link lib.minecraft.renderer.engine.raster.RasterMath RasterMath} - 2D coverage math:
 *       barycentric coordinates, the {@code 1/256} fixed-point sub-pixel grid, and the
 *       {@code EdgeCoefficients} that drive the Pineda incremental edge functions / top-left fill
 *       rule the inner loop walks per pixel.</li>
 *   <li>{@link lib.minecraft.renderer.engine.raster.GlintMask GlintMask} - per-pixel coverage mask
 *       marking glinted geometry so the foil compositor restricts the enchantment glint to it.</li>
 * </ul>
 *
 * @see lib.minecraft.renderer.engine.ModelEngine
 */
package lib.minecraft.renderer.engine.raster;
