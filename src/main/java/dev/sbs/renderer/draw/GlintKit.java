package dev.sbs.renderer.draw;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Generates animated enchantment glint frames by scrolling a glint texture over a base image and
 * additively compositing the result.
 * <p>
 * Constants and scroll math are verified against the MC 26.1 deobfuscated client source
 * ({@code net.minecraft.client.renderer.rendertype.TextureTransform.setupGlintTexturing(float)}
 * and {@code RenderTypes} glint pipeline registrations):
 * <pre>{@code
 * // vanilla setupGlintTexturing, reconstructed from javap disassembly
 * long t = (long)(Util.getMillis() * glintSpeed * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS);
 * float u = (float)(t % 110_000L) / 110_000f;   // U loop = 110s
 * float v = (float)(t %  30_000L) /  30_000f;   // V loop =  30s
 * Matrix4f mat = new Matrix4f()
 *     .translation(-u, v, 0f)
 *     .rotateZ(0.17453292f)  // ~10 degrees
 *     .scale(scale);         // 8.0 (GUI/held item), 0.5 (entity held), 0.16 (armor layer)
 * }</pre>
 * Vanilla has three distinct glint render pipelines wired to two distinct textures:
 * <ul>
 * <li>{@code GLINT} / {@code GLINT_TRANSLUCENT} / {@code ENTITY_GLINT} -
 * {@code minecraft:textures/misc/enchanted_glint_item.png}</li>
 * <li>{@code ARMOR_ENTITY_GLINT} -
 * {@code minecraft:textures/misc/enchanted_glint_armor.png}</li>
 * </ul>
 * This helper mirrors the texture split via {@link GlintOptions#itemDefault(int)} and
 * {@link GlintOptions#armorDefault(int)} presets. The rasterization step preserves the vanilla
 * translation but skips the 10-degree rotation and per-type scale multiplier - those are display
 * transforms that would require UV-mapped model rasterization to reproduce exactly. For offline
 * 2D icon rendering the translation-only scroll is a visually close approximation.
 * <p>
 * Vanilla also uses a quadratic additive blend ({@code BlendFunction.GLINT = (SRC_COLOR, ONE,
 * ZERO, ONE)}: {@code out.rgb = src.rgb * src.rgb + dst.rgb}). This kit uses the linear
 * {@link BlendMode#ADD} for simplicity; the visual difference is a slightly brighter glint in
 * bright glint-texture regions.
 */
@UtilityClass
public class GlintKit {

    /**
     * Time-to-scroll multiplier from the 26.1 client source. Present in vanilla as
     * {@code TextureTransform.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS = 8.0}.
     */
    public static final double MAX_ENCHANTMENT_GLINT_SPEED_MILLIS = 8.0;

    /** U-axis loop period in milliseconds - {@code 110_000L} modulus in {@code setupGlintTexturing}. */
    public static final long VANILLA_U_LOOP_MILLIS = 110_000L;

    /** V-axis loop period in milliseconds - {@code 30_000L} modulus in {@code setupGlintTexturing}. */
    public static final long VANILLA_V_LOOP_MILLIS = 30_000L;

    /** Per-type scale factor for {@code GLINT_TEXTURING} (GUI and held item glint). */
    public static final float ITEM_SCALE = 8.0f;

    /** Per-type scale factor for {@code ENTITY_GLINT_TEXTURING} (entity-held item glint). */
    public static final float ENTITY_ITEM_SCALE = 0.5f;

    /** Per-type scale factor for {@code ARMOR_ENTITY_GLINT_TEXTURING} (armor layer glint). */
    public static final float ARMOR_SCALE = 0.16f;

    /** Rotation applied to the glint UV translation - {@code 0.17453292f} radians, ~10 degrees. */
    public static final float ROTATION_RADIANS = 0.17453292f;

    /**
     * Resolved texture id for the vanilla enchanted item glint - sampled by
     * {@code GLINT}, {@code GLINT_TRANSLUCENT}, and {@code ENTITY_GLINT} render types.
     */
    public static final @NotNull String ITEM_GLINT_TEXTURE_ID = "minecraft:misc/enchanted_glint_item";

    /**
     * Resolved texture id for the vanilla enchanted armor glint - sampled by
     * {@code ARMOR_ENTITY_GLINT} render type.
     */
    public static final @NotNull String ARMOR_GLINT_TEXTURE_ID = "minecraft:misc/enchanted_glint_armor";

    /**
     * Configuration for a single glint render pass.
     *
     * @param framesPerSecond the output frame rate
     * @param totalFrames the number of frames to generate for one full loop
     * @param glintTextureId the texture id to sample from the active pack stack
     * @param textureScale the per-type scale factor - see {@link #ITEM_SCALE},
     * {@link #ENTITY_ITEM_SCALE}, {@link #ARMOR_SCALE}
     * @param uLoopMillis the U-axis loop period in milliseconds
     * @param vLoopMillis the V-axis loop period in milliseconds
     * @param tintArgb an optional ARGB pre-multiplier applied to the glint texture before
     * compositing - {@code 0xFFFFFFFF} for no tint. Vanilla does not tint the glint texture
     * directly; the in-game {@code GlintAlpha} shader uniform and additive blend state produce
     * the translucent look. Callers that want a pre-rendered glint matching a specific visual
     * tone can override.
     */
    public record GlintOptions(
        int framesPerSecond,
        int totalFrames,
        @NotNull String glintTextureId,
        float textureScale,
        long uLoopMillis,
        long vLoopMillis,
        int tintArgb
    ) {

        /**
         * Default item glint preset at the given frame rate. Uses the vanilla
         * {@link #ITEM_GLINT_TEXTURE_ID}, {@link #ITEM_SCALE}, and the 110s/30s vanilla loop
         * periods. Total frames default to {@code framesPerSecond * 2} for a 2-second pre-rendered
         * loop (the full vanilla loop is 330 seconds and impractical to bake).
         *
         * @param framesPerSecond the output frame rate
         * @return the item glint preset
         */
        public static @NotNull GlintOptions itemDefault(int framesPerSecond) {
            return new GlintOptions(
                framesPerSecond,
                framesPerSecond * 2,
                ITEM_GLINT_TEXTURE_ID,
                ITEM_SCALE,
                VANILLA_U_LOOP_MILLIS,
                VANILLA_V_LOOP_MILLIS,
                0xFFFFFFFF
            );
        }

        /**
         * Default armor glint preset at the given frame rate. Uses the vanilla
         * {@link #ARMOR_GLINT_TEXTURE_ID}, {@link #ARMOR_SCALE}, and the 110s/30s loop periods.
         *
         * @param framesPerSecond the output frame rate
         * @return the armor glint preset
         */
        public static @NotNull GlintOptions armorDefault(int framesPerSecond) {
            return new GlintOptions(
                framesPerSecond,
                framesPerSecond * 2,
                ARMOR_GLINT_TEXTURE_ID,
                ARMOR_SCALE,
                VANILLA_U_LOOP_MILLIS,
                VANILLA_V_LOOP_MILLIS,
                0xFFFFFFFF
            );
        }

        /**
         * Default entity-held item glint preset at the given frame rate. Uses the item texture
         * but with {@link #ENTITY_ITEM_SCALE} instead of {@link #ITEM_SCALE}, matching vanilla's
         * {@code ENTITY_GLINT} render type.
         *
         * @param framesPerSecond the output frame rate
         * @return the entity-held item glint preset
         */
        public static @NotNull GlintOptions entityItemDefault(int framesPerSecond) {
            return new GlintOptions(
                framesPerSecond,
                framesPerSecond * 2,
                ITEM_GLINT_TEXTURE_ID,
                ENTITY_ITEM_SCALE,
                VANILLA_U_LOOP_MILLIS,
                VANILLA_V_LOOP_MILLIS,
                0xFFFFFFFF
            );
        }
    }

    /**
     * Renders a list of glint frames by stamping the given base image repeatedly and compositing a
     * scrolled copy of the glint texture on top with additive blending.
     * <p>
     * The per-frame scroll offset is computed from vanilla's time-to-UV formula:
     * <pre>{@code
     * long t = Math.round((double)frameIndex * 1000.0 * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS / framesPerSecond);
     * float u = (float)(t % uLoopMillis) / uLoopMillis;
     * float v = (float)(t % vLoopMillis) / vLoopMillis;
     * }</pre>
     * Translated into texture-pixel space by multiplying by the glint texture dimensions, then
     * negating the U axis to match vanilla's {@code translation(-u, v, 0)}.
     *
     * @param base the base item texture
     * @param glintTexture the glint scroll texture
     * @param options the glint configuration
     * @return an ordered list of composited frames, one per output frame
     */
    public static @NotNull ConcurrentList<PixelBuffer> apply(
        @NotNull PixelBuffer base,
        @NotNull PixelBuffer glintTexture,
        @NotNull GlintOptions options
    ) {
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        int baseWidth = base.getWidth();
        int baseHeight = base.getHeight();
        int glintW = glintTexture.getWidth();
        int glintH = glintTexture.getHeight();

        PixelBuffer tintedGlint = options.tintArgb() == 0xFFFFFFFF
            ? glintTexture
            : ColorKit.tint(glintTexture, options.tintArgb());

        double millisPerFrame = 1000.0 / options.framesPerSecond();

        for (int frameIndex = 0; frameIndex < options.totalFrames(); frameIndex++) {
            long t = Math.round(frameIndex * millisPerFrame * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS);
            float u = (float) (Math.floorMod(t, options.uLoopMillis())) / options.uLoopMillis();
            float v = (float) (Math.floorMod(t, options.vLoopMillis())) / options.vLoopMillis();

            // Vanilla translates by (-u, v, 0). We mirror that sign convention here so the scroll
            // direction matches the in-game glint.
            float scrollX = -u * glintW;
            float scrollY = v * glintH;

            Canvas canvas = Canvas.of(baseWidth, baseHeight);
            canvas.blit(base, 0, 0);
            stampGlint(canvas, tintedGlint, scrollX, scrollY, base);
            frames.add(canvas.getBuffer());
        }

        return frames;
    }

    /**
     * Overlays the glint texture on a canvas at the given scroll offset, masked by the base
     * image's alpha so the glint only shows where the item is opaque. Sampling wraps around the
     * glint texture dimensions (GL_REPEAT equivalent), matching the vanilla texture sampler state.
     */
    private static void stampGlint(
        @NotNull Canvas canvas,
        @NotNull PixelBuffer glint,
        float scrollX,
        float scrollY,
        @NotNull PixelBuffer mask
    ) {
        int canvasW = canvas.width();
        int canvasH = canvas.height();
        int glintW = glint.getWidth();
        int glintH = glint.getHeight();

        int scrollXInt = Math.floorMod(Math.round(scrollX), glintW);
        int scrollYInt = Math.floorMod(Math.round(scrollY), glintH);

        for (int y = 0; y < canvasH; y++) {
            int maskY = (y * mask.getHeight()) / canvasH;
            int glintY = (y + scrollYInt) % glintH;
            for (int x = 0; x < canvasW; x++) {
                int maskPixel = mask.getPixel((x * mask.getWidth()) / canvasW, maskY);
                if (ColorKit.alpha(maskPixel) == 0) continue;

                int glintX = (x + scrollXInt) % glintW;
                int glintPixel = glint.getPixel(glintX, glintY);
                if (ColorKit.alpha(glintPixel) == 0) continue;

                int current = canvas.getBuffer().getPixel(x, y);
                canvas.getBuffer().setPixel(x, y, ColorKit.blend(glintPixel, current, BlendMode.ADD));
            }
        }
    }

}
