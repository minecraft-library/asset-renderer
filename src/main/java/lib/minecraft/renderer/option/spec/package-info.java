/**
 * Sub-builders and value types used by the renderer {@code *Options} implementations in
 * {@link lib.minecraft.renderer.option option} - the concerns a caller composes into a render but
 * that are shared across renderers or too detailed to inline in the top-level {@code *Options} bag.
 * <ul>
 *   <li><b>Shared sub-option bags</b> - {@code OutputOptions} (render frame), {@code ArmorOptions}
 *       (worn armor), {@code SkinOptions} / {@code TextureOptions} (player skin / cape sources),
 *       {@code AnimationOptions}. Each is a small {@code @ClassBuilder} value nested into a renderer's
 *       options so a concern shared by several renderers is declared once instead of re-spelled per
 *       renderer.</li>
 *   <li><b>Caller-supplied value types the options reference</b> -
 *       {@link lib.minecraft.renderer.option.spec.DyeColor DyeColor} (the dye palette),
 *       {@link lib.minecraft.renderer.option.spec.BannerLayer BannerLayer}, the equipped-armor
 *       vocabulary ({@code ArmorMaterial} / {@code ArmorPiece} / {@code ArmorTrim}), and
 *       {@code ItemDecoration}.</li>
 * </ul>
 */
package lib.minecraft.renderer.option.spec;
