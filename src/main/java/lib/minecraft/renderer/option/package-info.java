/**
 * Renderer options - one {@code *Options} bag per renderer (the argument to
 * {@link lib.minecraft.renderer.Renderer#render Renderer.render}), each implementing the
 * {@link lib.minecraft.renderer.option.RenderOptions RenderOptions} marker that bounds
 * {@link lib.minecraft.renderer.Renderer Renderer}'s type parameter.
 *
 * <p><b>Charter.</b> This package level holds {@code RenderOptions} implementations only; two
 * sub-packages carry the rest of the option vocabulary:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.option.spec spec} - the sub-builders and value types the
 *       {@code *Options} bags nest or reference (output frame, worn armor, player skin / cape
 *       sources, the dye palette, banner layers, ...).</li>
 *   <li>{@link lib.minecraft.renderer.option.slot slot} - the per-renderer {@code LayerSlot}
 *       implementations that key layer composition.</li>
 * </ul>
 *
 * <p>Anything at this level that is not a {@code RenderOptions} implementation
 * ({@link lib.minecraft.renderer.option.EntityAppearance EntityAppearance},
 * {@link lib.minecraft.renderer.option.TintAxis TintAxis},
 * {@link lib.minecraft.renderer.option.TropicalFishPattern TropicalFishPattern},
 * {@link lib.minecraft.renderer.option.Age Age}) is entity-appearance vocabulary that also lives
 * at this level.
 *
 * <p>The one further category is a renderer's own output vocabulary, where a bag's counterpart is a
 * value the caller receives rather than supplies:
 * {@link lib.minecraft.renderer.option.AtlasSidecar AtlasSidecar} and
 * {@link lib.minecraft.renderer.option.AtlasTile AtlasTile} describe the grid an
 * {@link lib.minecraft.renderer.AtlasRenderer AtlasRenderer} run composed, and sit beside
 * {@link lib.minecraft.renderer.option.AtlasOptions AtlasOptions} because what a caller asks an
 * atlas for and what it gets back read as one vocabulary.
 */
package lib.minecraft.renderer.option;
