package lib.minecraft.renderer.option;

/**
 * Marker implemented by every top-level renderer options type - the upper bound of
 * {@link lib.minecraft.renderer.Renderer Renderer}'s type parameter.
 * <p>
 * Carries no members; it exists so {@code Renderer<O extends Option>} accepts only genuine options
 * bags and rejects arbitrary types. Sub-option value objects composed into an options bag
 * ({@code RenderOptions}, {@code ArmorOptions}, and siblings) are not options themselves and do not
 * implement it.
 */
public interface Option {
}
