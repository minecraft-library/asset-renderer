package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.NineSliceKit;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.option.slot.TextSlot;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.rule.ItemContext;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Themable tooltip chrome - contributes the BACKGROUND and BORDER layers around the text box.
 * <p>
 * The chrome owns only {@link TextSlot#BACKGROUND} and {@link TextSlot#BORDER}; the {@link TextSlot#TEXT}
 * slot stays owned by the text renderer, so both {@link Vanilla} variants and any custom theme slot into
 * the same {@link LayerStack} and the caller's layer-decorator splices keep working. The sprite variant
 * reads its sprite pair through the pack stack (resolved by the caller into a {@link ChromeSprites}); the
 * procedural variant is context-free.
 *
 * @see lib.minecraft.renderer.TextRenderer
 */
public interface TooltipChrome {

    /**
     * The canvas-edge-to-glyph padding this chrome wants, in mcPixels.
     *
     * @param options the render options, for chromes whose padding is caller-overridable
     * @return the padding in mcPixels
     */
    int paddingMcPx(@NotNull TextOptions options);

    /**
     * Contributes the chrome layers into the text layer stack. The TEXT slot stays owned by the text
     * renderer; the chrome only fills BACKGROUND and BORDER.
     *
     * @param stack the frame's layer stack (TextSlot-keyed)
     * @param box the canvas geometry - width/height in output px plus the mcPixel scale
     * @param sprites the resolved chrome sprites + scaling sidecars, empty for context-free renders
     * @param options the render options (alpha overrides, style)
     */
    void contribute(@NotNull LayerStack<ImageLayer> stack, @NotNull ChromeBox box,
                    @NotNull Optional<ChromeSprites> sprites, @NotNull TextOptions options);

    /** The vanilla chrome variants: sprite-backed nine-slice, or the legacy procedural fill + gradient ring. */
    enum Vanilla implements TooltipChrome {

        /** Nine-slice {@code tooltip/background} + {@code tooltip/frame} sprites, resolved through the pack stack. */
        SPRITE,
        /** Legacy procedural fill + gradient ring (the pre-sprite constants), context-free. */
        PROCEDURAL;

        /** The sprite chrome's fixed canvas-edge-to-glyph padding, in mcPixels (vanilla's effective 4). */
        private static final int SPRITE_PADDING_MCPX = 4;

        /**
         * The mcPixel inflation the vanilla client applies around the content box before blitting both
         * tooltip sprites, from {@code TooltipRenderUtil.renderTooltipBackground} ({@code x - 3 - 9}): the
         * sprites' transparent margins bleed off-canvas so the background fill lands flush to the canvas
         * edge and the frame ring one mcPixel inside it.
         */
        private static final int VANILLA_CONTENT_INFLATE_MCPX = 12;

        /**
         * Vanilla tooltip background RGB - full ARGB {@code 0xF0100010}; the alpha {@code 0xF0} comes from
         * {@link TextOptions#getBackgroundAlpha()} at composite time.
         */
        private static final int VANILLA_TOOLTIP_BG_RGB = 0x100010;

        /** Vanilla tooltip border gradient top RGB - full ARGB {@code 0x505000FF}. */
        private static final int VANILLA_TOOLTIP_BORDER_TOP_RGB = 0x5000FF;

        /** Vanilla tooltip border gradient bottom RGB - full ARGB {@code 0x5028007F}. */
        private static final int VANILLA_TOOLTIP_BORDER_BOTTOM_RGB = 0x28007F;

        /** {@inheritDoc} */
        @Override
        public int paddingMcPx(@NotNull TextOptions options) {
            return this == SPRITE ? SPRITE_PADDING_MCPX : options.getPadding();
        }

        /** {@inheritDoc} */
        @Override
        public void contribute(@NotNull LayerStack<ImageLayer> stack, @NotNull ChromeBox box,
                               @NotNull Optional<ChromeSprites> sprites, @NotNull TextOptions options) {
            switch (this) {
                case PROCEDURAL -> contributeProcedural(stack, box, options);
                case SPRITE -> contributeSprite(stack, box, sprites, options);
            }
        }

        /**
         * The legacy procedural chrome: a full-canvas background fill and the per-row gradient ring, with
         * both alphas taken straight from the options - byte-identical to the pre-sprite renderer.
         */
        private static void contributeProcedural(@NotNull LayerStack<ImageLayer> stack, @NotNull ChromeBox box, @NotNull TextOptions options) {
            int bgArgb = (Math.clamp(options.getBackgroundAlpha(), 0, 255) << 24) | VANILLA_TOOLTIP_BG_RGB;
            int borderAlpha = Math.clamp(options.getBorderAlpha(), 0, 255);
            stack.append(TextSlot.BACKGROUND, frame -> frame.fill(bgArgb));
            stack.append(TextSlot.BORDER, frame -> drawGradientBorder(frame, box.width(), box.height(), borderAlpha));
        }

        /**
         * The sprite chrome: nine-slice the {@code tooltip/background} then {@code tooltip/frame} sprites
         * over the content box inflated by the vanilla margin, so their transparent margins bleed
         * off-canvas. The option alphas become multipliers relative to the baked sprite alphas (240 / 80),
         * so default options leave the sprite bytes untouched and a lowered alpha fades proportionally.
         */
        private static void contributeSprite(@NotNull LayerStack<ImageLayer> stack, @NotNull ChromeBox box,
                                             @NotNull Optional<ChromeSprites> maybeSprites, @NotNull TextOptions options) {
            ChromeSprites sprites = maybeSprites.orElseThrow(() -> new RenderException(
                "Vanilla.SPRITE tooltip chrome requires resolved sprites; the caller must resolve ChromeSprites before rendering"));
            float bgMul = options.getBackgroundAlpha() / (float) TextOptions.VANILLA_TOOLTIP_BG_ALPHA;
            float borderMul = options.getBorderAlpha() / (float) TextOptions.VANILLA_TOOLTIP_BORDER_ALPHA;
            stack.append(TextSlot.BACKGROUND, frame -> blitSprite(frame, box, sprites.background(), sprites.backgroundScaling(), bgMul));
            stack.append(TextSlot.BORDER, frame -> blitSprite(frame, box, sprites.frame(), sprites.frameScaling(), borderMul));
        }

        /** Nine-slices one chrome sprite over the vanilla-inflated content rect. */
        private static void blitSprite(@NotNull PixelBuffer frame, @NotNull ChromeBox box,
                                       @NotNull PixelBuffer sprite, @NotNull MCMeta.GuiScaling scaling, float alphaMul) {
            int px = box.pxScale();
            int padPx = SPRITE_PADDING_MCPX * px;
            int inflate = VANILLA_CONTENT_INFLATE_MCPX * px;
            int blitX = padPx - inflate;
            int blitY = padPx - inflate;
            int blitW = box.width() - 2 * padPx + 2 * inflate;
            int blitH = box.height() - 2 * padPx + 2 * inflate;
            NineSliceKit.draw(frame, sprite, scaling, blitX, blitY, blitW, blitH, px, alphaMul);
        }

        /**
         * Draws the vanilla procedural tooltip border - a 1-mcPixel ring inset 1 mcPixel from the canvas
         * edge, vertically gradient from the top RGB to the bottom RGB with the endpoint colours on the
         * horizontal strokes and the interpolation interior on the vertical edges.
         */
        private static void drawGradientBorder(@NotNull PixelBuffer buffer, int w, int h, int alpha) {
            int inset = MinecraftFont.MC_PIXEL_SCALE;   // border is inset 1 mcPixel from edge
            int stroke = MinecraftFont.MC_PIXEL_SCALE;  // border stroke is 1 mcPixel thick
            int topY0 = inset;
            int topY1 = inset + stroke;
            int botY0 = h - inset - stroke;
            int botY1 = h - inset;
            int leftX0 = inset;
            int leftX1 = inset + stroke;
            int rightX0 = w - inset - stroke;
            int rightX1 = w - inset;

            int innerTop = topY1;
            int innerBottom = botY0;
            int innerSpan = Math.max(1, innerBottom - 1 - innerTop); // rows in gradient interior

            // Top stroke - solid top color
            int topArgb = (alpha << 24) | VANILLA_TOOLTIP_BORDER_TOP_RGB;
            for (int y = topY0; y < topY1; y++)
                for (int x = leftX0; x < rightX1; x++)
                    buffer.setPixel(x, y, topArgb);

            // Bottom stroke - solid bottom color
            int botArgb = (alpha << 24) | VANILLA_TOOLTIP_BORDER_BOTTOM_RGB;
            for (int y = botY0; y < botY1; y++)
                for (int x = leftX0; x < rightX1; x++)
                    buffer.setPixel(x, y, botArgb);

            // Left and right vertical edges - interpolate between top and bottom colors
            for (int y = innerTop; y < innerBottom; y++) {
                int argb = lerpArgb(topArgb, botArgb, y - innerTop, innerSpan);
                for (int x = leftX0; x < leftX1; x++)
                    buffer.setPixel(x, y, argb);
                for (int x = rightX0; x < rightX1; x++)
                    buffer.setPixel(x, y, argb);
            }
        }

        /**
         * Linearly interpolates between two packed ARGB colors in straight (non-premultiplied) space.
         * Returns {@code from} when {@code t == 0} and {@code to} when {@code t == steps}.
         */
        private static int lerpArgb(int from, int to, int t, int steps) {
            int a = lerp(ColorMath.alpha(from), ColorMath.alpha(to), t, steps);
            int r = lerp(ColorMath.red(from), ColorMath.red(to), t, steps);
            int g = lerp(ColorMath.green(from), ColorMath.green(to), t, steps);
            int b = lerp(ColorMath.blue(from), ColorMath.blue(to), t, steps);
            return ColorMath.pack(a, r, g, b);
        }

        /**
         * Linearly interpolates a single 8-bit colour channel. Returns {@code from} at {@code t == 0} and
         * {@code to} at {@code t == steps}.
         */
        private static int lerp(int from, int to, int t, int steps) {
            return from + ((to - from) * t) / steps;
        }
    }

    /**
     * The canvas geometry handed to {@link #contribute}: the output-pixel dimensions and the mcPixel scale.
     *
     * @param width the canvas width in output px
     * @param height the canvas height in output px
     * @param pxScale the output px per mcPixel ({@code MinecraftFont.MC_PIXEL_SCALE})
     */
    record ChromeBox(int width, int height, int pxScale) {}

    /**
     * A resolved chrome sprite pair plus each sprite's scaling metadata, resolved once per render by the
     * entry point that owns a {@code RendererContext} (the sidecar travels with the texture).
     *
     * @param backgroundId the background sprite texture id
     * @param background the decoded background sprite
     * @param backgroundScaling the background sprite's {@code gui.scaling} sidecar
     * @param frameId the frame sprite texture id
     * @param frame the decoded frame sprite
     * @param frameScaling the frame sprite's {@code gui.scaling} sidecar
     */
    record ChromeSprites(@NotNull ResourceId backgroundId, @NotNull PixelBuffer background,
                         @NotNull MCMeta.GuiScaling backgroundScaling,
                         @NotNull ResourceId frameId, @NotNull PixelBuffer frame,
                         @NotNull MCMeta.GuiScaling frameScaling) {

        /**
         * The {@code gui.scaling} a pack-overridden sprite without an mcmeta inherits - the spec default
         * of {@code stretch}, NOT vanilla's nine_slice (the sidecar travels with the winning texture).
         */
        private static final @NotNull MCMeta.GuiScaling STRETCH_DEFAULT = new MCMeta.GuiScaling(
            MCMeta.GuiScaling.Type.STRETCH, -1, -1, new MCMeta.GuiScaling.Border(0, 0, 0, 0), false);

        /**
         * Resolves the tooltip sprite pair for an optional style key through the pack stack, pairing each
         * decoded sprite with its {@code gui.scaling} sidecar (the sidecar travels with the winning
         * texture; a sprite shipped without one inherits {@code stretch}).
         *
         * <p>A {@code null} style resolves the default {@code minecraft:tooltip/background} +
         * {@code tooltip/frame} pair; a style {@code ns:path} resolves the per-item
         * {@code ns:tooltip/<path>_background} + {@code _frame} pair (the {@code minecraft:tooltip_style}
         * component, 24w36a). When either sprite is unresolved the pair DROPS - empty with a loud
         * diagnostic and no fallback to the default pair (decision 14; deviates from the client, which
         * falls back, because a headless render with an explicit style key is an authored input).
         *
         * @param context the renderer context resolving textures + sidecars through the pack stack
         * @param style the tooltip style key, or {@code null} for the default pair
         * @return the resolved sprite pair, or empty when either sprite is missing
         */
        public static @NotNull Optional<ChromeSprites> resolve(@NotNull RendererContext context, @Nullable ResourceId style) {
            ResourceId backgroundId = spriteId(style, "background");
            ResourceId frameId = spriteId(style, "frame");
            Optional<PixelBuffer> background = context.resolveTexture(backgroundId.id());
            Optional<PixelBuffer> frame = context.resolveTexture(frameId.id());
            if (background.isEmpty() || frame.isEmpty()) {
                System.err.printf("Tooltip chrome: %s sprite pair unresolved (%s%s / %s%s); dropping chrome, no fallback%n",
                    style == null ? "default" : "style '" + style + "'",
                    backgroundId, background.isEmpty() ? " MISSING" : "",
                    frameId, frame.isEmpty() ? " MISSING" : "");
                return Optional.empty();
            }
            return Optional.of(new ChromeSprites(
                backgroundId, background.get(), context.findGuiScaling(backgroundId.id()).orElse(STRETCH_DEFAULT),
                frameId, frame.get(), context.findGuiScaling(frameId.id()).orElse(STRETCH_DEFAULT)));
        }

        /**
         * Resolves the sprite pair for an item, reading its {@code minecraft:tooltip_style} component off
         * the render-time {@link ItemContext} and feeding the style key into {@link #resolve}. An item
         * carrying no style resolves the default pair; an item carrying a style whose sprites are missing
         * DROPs (empty + diagnostic, decision 14). Pack-content-gated - vanilla items ship no
         * {@code tooltip_style} in the default render paths, so this returns the default pair for them.
         *
         * @param context the renderer context resolving textures + sidecars through the pack stack
         * @param item the render-time item context carrying the component surface
         * @return the resolved sprite pair, or empty when the item's style sprites are missing
         */
        public static @NotNull Optional<ChromeSprites> resolveForItem(@NotNull RendererContext context, @NotNull ItemContext item) {
            return resolve(context, styleOf(item).orElse(null));
        }

        /**
         * Reads an item's {@code minecraft:tooltip_style} component from the phase-3 components surface
         * ({@code effectiveNbt -> components -> minecraft:tooltip_style}), returning the style key as a
         * resource id. Empty when the component is absent, not a string, or blank.
         *
         * @param item the render-time item context
         * @return the tooltip style key, or empty when the item declares none
         */
        public static @NotNull Optional<ResourceId> styleOf(@NotNull ItemContext item) {
            Tag<?> componentsTag = item.effectiveNbt().get("components");
            if (!(componentsTag instanceof CompoundTag components)) return Optional.empty();
            Tag<?> styleTag = components.get("minecraft:tooltip_style");
            if (!(styleTag instanceof StringTag style)) return Optional.empty();
            String value = style.getValue();
            return value.isBlank() ? Optional.empty() : Optional.of(ResourceId.parse(value));
        }

        /**
         * Builds a tooltip sprite texture id: the default {@code minecraft:gui/sprites/tooltip/<part>} for
         * a null style, else {@code <ns>:gui/sprites/tooltip/<path>_<part>} for a style {@code ns:path}.
         */
        private static @NotNull ResourceId spriteId(@Nullable ResourceId style, @NotNull String part) {
            if (style == null) return new ResourceId(ResourceId.DEFAULT_NAMESPACE, "gui/sprites/tooltip/" + part);
            return new ResourceId(style.namespace(), "gui/sprites/tooltip/" + style.name() + "_" + part);
        }
    }

}
