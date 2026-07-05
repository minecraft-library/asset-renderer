/**
 * Composable sub-option value objects shared across the renderer {@code *Options} bags - the render
 * frame ({@code RenderOptions}), worn armor ({@code ArmorOptions}), and player skin / cape sources
 * ({@code SkinOptions}, {@code TextureOptions}). Each is a small {@code @Builder} value type a caller
 * nests into a renderer's options, so a concern shared by several renderers is declared once instead
 * of re-spelled per renderer.
 */
package lib.minecraft.renderer.option.spec;
