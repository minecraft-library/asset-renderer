package lib.minecraft.renderer.bake.gui;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.slot.TextSlot;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Optional;

/**
 * Tooltip chrome - lays out the box round a text block and contributes the BACKGROUND and BORDER layers
 * over it.
 * <p>
 * A chrome first lays out its {@link ChromeBox} round the text ({@link #box}) - the canvas and the text
 * block's place on it, in Minecraft pixels - then contributes its layers over that canvas
 * ({@link #contribute}). The sprite variant's canvas ends at the first pixel its sprites draw; the
 * procedural variant pads the text by the request's padding.
 * <p>
 * The chrome owns only {@link TextSlot#BACKGROUND} and {@link TextSlot#BORDER}; the {@link TextSlot#TEXT}
 * slot stays owned by the text renderer, so both {@link Vanilla} variants slot into the same
 * {@link LayerStack} and the caller's layer-decorator splices keep working. A request names its chrome
 * by {@link ChromeStyle}, which {@link #of(ChromeStyle)} resolves to a variant. The sprite variant reads
 * its sprite pair through the pack stack, resolved into a {@link ChromeSprites} by the text renderer
 * from the context it holds; the procedural variant is context-free.
 *
 * @see lib.minecraft.renderer.TextRenderer
 */
@Parity(claim = "text-closure", mode = Mode.DEMOTE)
public sealed interface TooltipChrome permits TooltipChrome.Vanilla {

    /**
     * Returns the chrome a style selects.
     *
     * @param style the style a request names
     * @return the vanilla chrome of that style's name
     */
    static @NotNull TooltipChrome of(@NotNull ChromeStyle style) {
        return switch (style) {
            case SPRITE -> Vanilla.SPRITE;
            case PROCEDURAL -> Vanilla.PROCEDURAL;
        };
    }

    /**
     * Lays out the box this chrome draws round a text block - the canvas, ending at the first pixel the
     * chrome draws and never cutting the text, and the text block's place on it.
     *
     * @param textWidthMcPx the text block's width in mcPixels
     * @param textHeightMcPx the text block's height in mcPixels
     * @param sprites the resolved chrome sprites + scaling sidecars, empty for context-free renders
     * @param options the render options (padding, alpha overrides)
     * @return the canvas and the text block's place on it, in whole mcPixels
     * @throws RenderException if the chrome draws sprites and none were resolved
     */
    @NotNull ChromeBox box(int textWidthMcPx, int textHeightMcPx, @NotNull Optional<ChromeSprites> sprites,
                           @NotNull TextOptions options);

    /**
     * Contributes the chrome layers into the text layer stack, over the canvas {@link #box} laid out.
     * The TEXT slot stays owned by the text renderer; the chrome only fills BACKGROUND and BORDER.
     *
     * @param stack the frame's layer stack (TextSlot-keyed)
     * @param box the box {@link #box} laid out - the canvas and the text block's place on it
     * @param sprites the resolved chrome sprites + scaling sidecars, empty for context-free renders
     * @param options the render options (alpha overrides, style)
     * @throws RenderException if the chrome draws sprites and none were resolved
     */
    void contribute(@NotNull LayerStack<ImageLayer> stack, @NotNull ChromeBox box,
                    @NotNull Optional<ChromeSprites> sprites, @NotNull TextOptions options);

    /**
     * The vanilla chrome variants: sprite-backed nine-slice, or the legacy procedural fill + gradient ring.
     */
    enum Vanilla implements TooltipChrome {

        /**
         * Nine-slice {@code tooltip/background} + {@code tooltip/frame} sprites, resolved through the pack stack.
         */
        SPRITE,
        /**
         * Legacy procedural fill + gradient ring (the pre-sprite constants), context-free.
         */
        PROCEDURAL;

        /**
         * The margin the client blits both tooltip sprites over round the text, in mcPixels -
         * {@code TooltipRenderUtil.extractTooltipBackground} blitting them at {@code x - 3 - 9} over
         * {@code w + 24}. Both sprites are nine-sliced over that whole rect, so a pack's box drawn wider
         * than vanilla's is never cut, and the canvas holds only what they draw over it.
         */
        private static final int SPRITE_MARGIN_MCPX = 12;

        /**
         * Vanilla tooltip background RGB - full ARGB {@code 0xF0100010}; the alpha {@code 0xF0} comes from
         * {@link TextOptions#getBackgroundAlpha()} at composite time.
         */
        private static final int VANILLA_TOOLTIP_BG_RGB = 0x100010;

        /**
         * Vanilla tooltip border gradient top RGB - full ARGB {@code 0x505000FF}.
         */
        private static final int VANILLA_TOOLTIP_BORDER_TOP_RGB = 0x5000FF;

        /**
         * Vanilla tooltip border gradient bottom RGB - full ARGB {@code 0x5028007F}.
         */
        private static final int VANILLA_TOOLTIP_BORDER_BOTTOM_RGB = 0x28007F;

        /** {@inheritDoc} */
        @Override
        public @NotNull ChromeBox box(int textWidthMcPx, int textHeightMcPx, @NotNull Optional<ChromeSprites> sprites,
                                     @NotNull TextOptions options) {
            return switch (this) {
                case PROCEDURAL -> proceduralBox(textWidthMcPx, textHeightMcPx, options);
                case SPRITE -> spriteBox(textWidthMcPx, textHeightMcPx, required(sprites), options);
            };
        }

        /** {@inheritDoc} */
        @Override
        public void contribute(@NotNull LayerStack<ImageLayer> stack, @NotNull ChromeBox box,
                               @NotNull Optional<ChromeSprites> sprites, @NotNull TextOptions options) {
            switch (this) {
                case PROCEDURAL -> contributeProcedural(stack, options);
                case SPRITE -> contributeSprite(stack, box, required(sprites), options);
            }
        }

        /**
         * Lays out the procedural chrome's box: the text padded by the request's padding on every side,
         * which its fill paints to the edge.
         *
         * @param textW the text block's width in mcPixels
         * @param textH the text block's height in mcPixels
         * @param options the render options carrying the padding
         * @return the padded canvas with the text block inset by the padding
         */
        private static @NotNull ChromeBox proceduralBox(int textW, int textH, @NotNull TextOptions options) {
            int padding = options.getPadding();
            return new ChromeBox(textW + 2 * padding, textH + 2 * padding, padding, padding, textW, textH);
        }

        /**
         * Lays out the sprite chrome's box: the bounds each sprite draws over the rect the client hands
         * them, the text plus {@link #SPRITE_MARGIN_MCPX} a side, rounded out to whole mcPixels and joined
         * with the text block, so the canvas ends at the first pixel either sprite draws and never cuts the
         * text. A sprite the request's alpha clears draws nothing and does not count.
         *
         * @param textW the text block's width in mcPixels
         * @param textH the text block's height in mcPixels
         * @param sprites the resolved sprite pair
         * @param options the render options carrying the alpha overrides
         * @return the canvas the sprites draw to, with the text block at the client's content origin
         * relative to both sprites
         */
        private static @NotNull ChromeBox spriteBox(int textW, int textH, @NotNull ChromeSprites sprites, @NotNull TextOptions options) {
            int rectW = textW + 2 * SPRITE_MARGIN_MCPX;
            int rectH = textH + 2 * SPRITE_MARGIN_MCPX;
            int left = SPRITE_MARGIN_MCPX;
            int top = SPRITE_MARGIN_MCPX;
            int right = SPRITE_MARGIN_MCPX + textW;
            int bottom = SPRITE_MARGIN_MCPX + textH;
            List<Optional<Rectangle2D>> drawn = List.of(
                NineSliceKit.extent(sprites.background(), sprites.backgroundScaling(), rectW, rectH, backgroundMultiplier(options)),
                NineSliceKit.extent(sprites.frame(), sprites.frameScaling(), rectW, rectH, borderMultiplier(options)));
            for (Optional<Rectangle2D> extent : drawn) {
                if (extent.isEmpty()) continue;
                Rectangle2D bounds = extent.get();
                left = Math.min(left, (int) Math.floor(bounds.getMinX()));
                top = Math.min(top, (int) Math.floor(bounds.getMinY()));
                right = Math.max(right, (int) Math.ceil(bounds.getMaxX()));
                bottom = Math.max(bottom, (int) Math.ceil(bounds.getMaxY()));
            }
            return new ChromeBox(right - left, bottom - top, SPRITE_MARGIN_MCPX - left, SPRITE_MARGIN_MCPX - top, textW, textH);
        }

        /**
         * The legacy procedural chrome: a full-canvas background fill and the per-row gradient ring, with
         * both alphas taken straight from the options - byte-identical to the pre-sprite renderer.
         */
        private static void contributeProcedural(@NotNull LayerStack<ImageLayer> stack, @NotNull TextOptions options) {
            int bgArgb = (Math.clamp(options.getBackgroundAlpha(), 0, 255) << 24) | VANILLA_TOOLTIP_BG_RGB;
            int borderAlpha = Math.clamp(options.getBorderAlpha(), 0, 255);
            stack.append(TextSlot.BACKGROUND, frame -> frame.fill(bgArgb));
            stack.append(TextSlot.BORDER, frame -> drawGradientBorder(frame, frame.width(), frame.height(), borderAlpha));
        }

        /**
         * The sprite chrome: nine-slices the {@code tooltip/background} then {@code tooltip/frame} sprites
         * over the rect the client hands them - the text plus {@link #SPRITE_MARGIN_MCPX} a side, placed
         * round the box's text block - and the canvas holds what they draw. The sprite box lays its text
         * block out in whole Minecraft pixels, the client's own rect for both sprites. The option alphas
         * become multipliers on each texel's alpha relative to the baked sprite alphas (240 / 80), applied
         * before the texel blends, so default options draw the sprites as authored and a lowered alpha
         * fades them proportionally.
         */
        private static void contributeSprite(@NotNull LayerStack<ImageLayer> stack, @NotNull ChromeBox box,
                                             @NotNull ChromeSprites sprites, @NotNull TextOptions options) {
            int x = Math.round(box.textX()) - SPRITE_MARGIN_MCPX;
            int y = Math.round(box.textY()) - SPRITE_MARGIN_MCPX;
            int w = Math.round(box.textWidth()) + 2 * SPRITE_MARGIN_MCPX;
            int h = Math.round(box.textHeight()) + 2 * SPRITE_MARGIN_MCPX;
            float bgMul = backgroundMultiplier(options);
            float borderMul = borderMultiplier(options);
            stack.append(TextSlot.BACKGROUND, frame -> NineSliceKit.draw(frame, sprites.background(), sprites.backgroundScaling(),
                x, y, w, h, bgMul));
            stack.append(TextSlot.BORDER, frame -> NineSliceKit.draw(frame, sprites.frame(), sprites.frameScaling(),
                x, y, w, h, borderMul));
        }

        /**
         * Answers the sprite pair a sprite chrome draws. Only a render holding no context resolves none:
         * a context resolves a pair for every style, a sprite no pack supplies standing in as the missing
         * texture.
         *
         * @param sprites the pair resolved for the render
         * @return the pair
         * @throws RenderException if the render resolved no pair
         */
        private static @NotNull ChromeSprites required(@NotNull Optional<ChromeSprites> sprites) {
            return sprites.orElseThrow(() -> new RenderException(
                "Vanilla.SPRITE tooltip chrome requires resolved sprites; the text renderer needs a RendererContext"));
        }

        /**
         * Answers the multiplier on each background texel's alpha - the request's background alpha
         * relative to the background sprite's baked {@link TextOptions#VANILLA_TOOLTIP_BG_ALPHA}.
         *
         * @param options the render options
         * @return the background multiplier, {@code 1.0} at the default alpha
         */
        private static float backgroundMultiplier(@NotNull TextOptions options) {
            return options.getBackgroundAlpha() / (float) TextOptions.VANILLA_TOOLTIP_BG_ALPHA;
        }

        /**
         * Answers the multiplier on each frame texel's alpha - the request's border alpha relative to the
         * frame sprite's baked {@link TextOptions#VANILLA_TOOLTIP_BORDER_ALPHA}.
         *
         * @param options the render options
         * @return the border multiplier, {@code 1.0} at the default alpha
         */
        private static float borderMultiplier(@NotNull TextOptions options) {
            return options.getBorderAlpha() / (float) TextOptions.VANILLA_TOOLTIP_BORDER_ALPHA;
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
     * The canvas a text block is drawn on and the block's place on it - the box a chrome lays out round
     * the text, or the bounds of what the lines draw for text drawn with no chrome. Every value is in
     * Minecraft pixels as a {@code float}, as the client's {@code Font} places text, and is fractional
     * where the canvas edge falls inside a Minecraft pixel, as a chat render's can.
     *
     * @param width the canvas width in mcPixels
     * @param height the canvas height in mcPixels
     * @param textX the text block's left edge on the canvas, in mcPixels
     * @param textY the text block's top edge on the canvas, in mcPixels
     * @param textWidth the text block's width in mcPixels
     * @param textHeight the text block's height in mcPixels
     */
    record ChromeBox(float width, float height, float textX, float textY, float textWidth, float textHeight) {}

    /**
     * A resolved chrome sprite pair plus each sprite's scaling metadata, resolved once per render by the
     * text renderer through the {@code RendererContext} it holds (the sidecar travels with the texture).
     *
     * @param backgroundId the background sprite texture id
     * @param background the decoded background sprite, or the missing texture
     * @param backgroundScaling the background sprite's {@code gui.scaling} sidecar
     * @param frameId the frame sprite texture id
     * @param frame the decoded frame sprite, or the missing texture
     * @param frameScaling the frame sprite's {@code gui.scaling} sidecar
     */
    record ChromeSprites(@NotNull ResourceId backgroundId, @NotNull PixelBuffer background,
                         @NotNull MCMeta.GuiScaling backgroundScaling,
                         @NotNull ResourceId frameId, @NotNull PixelBuffer frame,
                         @NotNull MCMeta.GuiScaling frameScaling) {

        /**
         * Resolves the tooltip sprite pair for an optional style key through the pack stack, pairing each
         * sprite with the {@code gui.scaling} it draws with.
         *
         * <p>An empty style resolves the default {@code minecraft:tooltip/background} +
         * {@code tooltip/frame} pair; a style {@code ns:path} resolves the per-item
         * {@code ns:tooltip/<path>_background} + {@code _frame} pair (the {@code minecraft:tooltip_style}
         * component, 24w36a). Each sprite is read through the context's
         * {@link RendererContext#withMissingTexture() missing-texture wrapper}, so a sprite no selected
         * pack ships, one that cannot be read - an image over the decode budget included - or one whose
         * {@code gui} section the client's codec refuses, is the missing texture, which the wrapper
         * reports through the context as a stand-in, as the client's atlas answers its missing sprite for
         * any GUI sprite it does not hold. Each sprite is read on its own, so where one of the pair is the
         * missing texture the pack's other sprite is still drawn.
         *
         * @param context the renderer context resolving textures + sidecars through the pack stack
         * @param style the tooltip style key, empty for the default pair
         * @return the resolved sprite pair, the missing texture standing in for a sprite no pack supplies
         */
        public static @NotNull ChromeSprites resolve(@NotNull RendererContext context, @NotNull Optional<ResourceId> style) {
            ResourceId backgroundId = spriteId(style, "background");
            ResourceId frameId = spriteId(style, "frame");
            // The GUI atlas holds no sprite whose gui section its codec refuses, so such a sprite is served
            // with no pixels.
            RendererContext textures = context
                .withTextures(id -> refused(context, id) ? Possible.empty() : Possible.absent())
                .withMissingTexture();
            // Tick zero, because a pack shipping an animated tooltip sprite pins to frame 0 rather than
            // nine-slicing the whole flipbook strip. Sampling it is the context's own job; asking for the
            // strip and sampling it here is the same operations in the same order, spelled twice.
            PixelBuffer background =
                Flipbook.atTick(textures.resolveTexture(backgroundId.id()), textures.findFlipbook(backgroundId.id()), 0).get();
            PixelBuffer frame =
                Flipbook.atTick(textures.resolveTexture(frameId.id()), textures.findFlipbook(frameId.id()), 0).get();
            return new ChromeSprites(
                backgroundId, background, scaling(context, backgroundId, background),
                frameId, frame, scaling(context, frameId, frame));
        }

        /**
         * Answers whether a sprite's sidecar parses and carries a {@code gui} section the client's codec
         * refuses - a sprite the client's GUI atlas does not hold.
         *
         * @param context the renderer context the sidecar is read through
         * @param textureId the sprite's texture id
         * @return {@code true} where the sidecar's {@code gui} section is refused
         */
        private static boolean refused(@NotNull RendererContext context, @NotNull String textureId) {
            return context.findMeta(textureId)
                .map(meta -> meta.gui().getState() == Possible.State.EMPTY)
                .orElse(false);
        }

        /**
         * Answers the {@code gui.scaling} a sprite draws with - the one a sprite read from a pack carries in
         * its sidecar, or {@link MCMeta.GuiScaling#DEFAULT} where it declares none. The missing texture
         * carries none and stretches, as the client's missing sprite does whatever sidecar an unreadable
         * file ships, so the sidecar of the id it stands in for is never read.
         *
         * @param context the renderer context the sidecar is read through
         * @param spriteId the sprite's id
         * @param sprite the pixels drawn for the sprite
         * @return the sprite's scaling
         */
        private static @NotNull MCMeta.GuiScaling scaling(@NotNull RendererContext context, @NotNull ResourceId spriteId,
                                                          @NotNull PixelBuffer sprite) {
            if (MissingSprite.isSprite(sprite)) return MCMeta.GuiScaling.DEFAULT;
            return context.findMeta(spriteId.id())
                .flatMap(MCMeta::gui)
                .orElse(MCMeta.GuiScaling.DEFAULT);
        }

        /**
         * Resolves the sprite pair for an item, reading its {@code minecraft:tooltip_style} component off
         * the render-time {@link ItemContext} and feeding the style key into {@link #resolve}. An item
         * carrying no style resolves the default pair; an item carrying a style whose sprites no pack
         * ships draws the missing texture for each of them. Pack-content-gated - vanilla items ship no
         * {@code tooltip_style} in the default render paths, so this returns the default pair for them.
         *
         * @param context the renderer context resolving textures + sidecars through the pack stack
         * @param item the render-time item context carrying the component surface
         * @return the resolved sprite pair, the missing texture standing in for a sprite no pack supplies
         */
        public static @NotNull ChromeSprites resolveForItem(@NotNull RendererContext context, @NotNull ItemContext item) {
            return resolve(context, styleOf(item));
        }

        /**
         * Reads an item's {@code minecraft:tooltip_style} component from the components surface
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
         * an empty style, else {@code <ns>:gui/sprites/tooltip/<path>_<part>} for a style {@code ns:path}.
         */
        private static @NotNull ResourceId spriteId(@NotNull Optional<ResourceId> style, @NotNull String part) {
            return style
                .map(key -> new ResourceId(key.namespace(), "gui/sprites/tooltip/" + key.name() + "_" + part))
                .orElseGet(() -> new ResourceId(ResourceId.DEFAULT_NAMESPACE, "gui/sprites/tooltip/" + part));
        }
    }

}
