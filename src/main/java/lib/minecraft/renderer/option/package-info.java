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
 */
package lib.minecraft.renderer.option;
