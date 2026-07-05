/**
 * Renderer options - one {@code *Options} bag per renderer (the argument to
 * {@link lib.minecraft.renderer.Renderer#render Renderer.render}), each implementing the
 * {@link lib.minecraft.renderer.option.RenderOptions RenderOptions} marker that bounds
 * {@link lib.minecraft.renderer.Renderer Renderer}'s type parameter.
 * <p>
 * The composable sub-option value objects the bags nest - the output frame, worn armor, and player
 * skin / cape sources - live in {@link lib.minecraft.renderer.option.spec spec}; the per-renderer
 * layer-composition slot vocabularies live in {@link lib.minecraft.renderer.option.slot slot}.
 */
package lib.minecraft.renderer.option;
