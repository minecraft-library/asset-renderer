package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.raster.GlintMask;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * {@link GlintOptions#armorDefault(int)} presets, and reproduces vanilla's full UV transform
 * {@code translation(-u, v) . rotateZ(10 degrees) . scale(textureScale)} plus the quadratic
 * {@code GLINT} blend ({@code BlendFunction.GLINT = (SRC_COLOR, ONE, ZERO, ONE)}:
 * {@code out.rgb = src.rgb * src.rgb + dst.rgb}, with {@code src = glint.rgb * GLINT_ALPHA}).
 * <p>
 * The one unavoidable approximation is the source UV: vanilla samples the glint texture through
 * each fragment's <em>atlas</em> sprite coordinate ({@code texCoord0 = TextureMat * UV0}), which is
 * unknowable without the live texture atlas. This kit substitutes the normalized icon coordinate
 * {@code (x / width, y / height)}; {@link GlintOptions#textureScale()} then absorbs the resulting
 * band-frequency difference. The scroll phase (driven purely by the glint time {@code t})
 * reproduces exactly, so a time-aligned animation tracks vanilla even though the spatial pattern
 * carries a residual.
 */
@UtilityClass
public class GlintKit {

    /**
     * Time-to-scroll multiplier from the 26.1 client source. Present in vanilla as
     * {@code TextureTransform.MAX_ENCHANTMENT_GLINT_SPEED_MILLIS = 8.0}.
     */
    public static final double MAX_ENCHANTMENT_GLINT_SPEED_MILLIS = 8.0;

    /**
     * U-axis loop period in milliseconds - {@code 110_000L} modulus in {@code setupGlintTexturing}.
     */
    public static final long VANILLA_U_LOOP_MILLIS = 110_000L;

    /**
     * V-axis loop period in milliseconds - {@code 30_000L} modulus in {@code setupGlintTexturing}.
     */
    public static final long VANILLA_V_LOOP_MILLIS = 30_000L;

    /**
     * Per-type scale factor for {@code GLINT_TEXTURING} (GUI and held item glint).
     */
    public static final float ITEM_SCALE = 8.0f;

    /**
     * Per-type scale factor for {@code ENTITY_GLINT_TEXTURING} (entity-held item glint).
     */
    public static final float ENTITY_ITEM_SCALE = 0.5f;

    /**
     * Per-type scale factor for {@code ARMOR_ENTITY_GLINT_TEXTURING} (armor layer glint).
     */
    public static final float ARMOR_SCALE = 0.16f;

    /**
     * Rotation applied to the glint UV translation - {@code 0.17453292f} radians, ~10 degrees.
     */
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
     * Glint intensity multiplier - vanilla's {@code GlintAlpha} shader uniform. The glint fragment
     * shader emits {@code colour.rgb * (1 - fog) * GlintAlpha} before the quadratic blend; with no
     * fog in the GUI this reduces to {@code colour.rgb * GLINT_ALPHA}.
     * <p>
     * Derived from the client jar, not tuned: {@code GlintAlpha} is not a constant but the player's
     * <b>Glint Strength</b> video option. {@code GameRenderer} snapshots
     * {@code OptionsRenderState.glintStrength} into {@code GlobalSettingsUniform.update}, which writes
     * it to the {@code Globals} UBO's {@code GlintAlpha} slot. The vanilla default
     * ({@code Options.glintStrength}) is {@code 0.75}, which is what the reference harness renders
     * with.
     */
    public static final float GLINT_ALPHA = 0.75f;

    /**
     * Horizontal fraction of the glint texture the offline normalized-icon U spans, the item sprite's
     * width over the items-atlas width ({@code spritePx / atlasWidthPx}).
     * <p>
     * Vanilla samples the glint at {@code texCoord = atlasUV * textureScale}, where {@code atlasUV} is
     * the item sprite's coordinate within the stitched items atlas - a small sub-rect, so
     * {@link #ITEM_SCALE} of {@code 8.0} magnifies only a few glint texels across the icon. Offline
     * there is no atlas, so the kit substitutes the normalized icon coordinate {@code (x/w, y/h)}
     * spanning the whole {@code 0..1} icon, then multiplies each axis by its fraction to recover the
     * sprite's real atlas span (the offset still differs - see the class javadoc - but a standalone
     * animation only reads the span, not the phase).
     * <p>
     * The numerator {@code 16} is the item sprite size in pixels; the denominator {@code 1024} is the
     * 26.1 items-atlas width, measured from the harness ({@code Du = 1/64}). With it,
     * {@code ITEM_SCALE * OFFLINE_ATLAS_UV_FRACTION_U * 128 = 8.0 * (16 / 1024) * 128 = 16}, so the
     * icon footprint spans 16 of the glint's 128 texels in U. These are anisotropic: the items atlas
     * is {@code 1024 x 512}, so {@link #OFFLINE_ATLAS_UV_FRACTION_V} is twice this (32 texels).
     */
    public static final float OFFLINE_ATLAS_UV_FRACTION_U = 16f / 1024f;

    /**
     * Vertical fraction of the glint texture the offline normalized-icon V spans, the item sprite's
     * height over the items-atlas height ({@code spritePx / atlasHeightPx}). The 26.1 items atlas is
     * {@code 1024 x 512} ({@code Dv = 1/32}), so V spans {@code 8.0 * (16 / 512) * 128 = 32} of the
     * glint's 128 texels - twice {@link #OFFLINE_ATLAS_UV_FRACTION_U}. See it for the full rationale.
     */
    public static final float OFFLINE_ATLAS_UV_FRACTION_V = 16f / 512f;

    /**
     * Glint texel alpha (0-255) below which vanilla's glint fragment shader discards the fragment
     * ({@code if (colour.a < 0.1) discard}). The vanilla glint textures are opaque RGB so this
     * never fires, but a paletted resource pack may ship transparency.
     */
    private static final int GLINT_DISCARD_ALPHA_BYTE = 26;

    /**
     * The live atlas sprite's normalized UV sub-rect, the real {@code UV0} source for an exact glint
     * sample in place of the offline normalized-icon approximation.
     *
     * @param u0 left atlas U of the sprite sub-rect
     * @param v0 top atlas V of the sprite sub-rect
     * @param u1 right atlas U of the sprite sub-rect
     * @param v1 bottom atlas V of the sprite sub-rect
     */
    public record SpriteUv(float u0, float v0, float u1, float v1) {}

    /**
     * Configuration for a single glint render pass.
     *
     * @param framesPerSecond the output frame rate
     * @param totalFrames the number of frames to generate for one full loop
     * @param glintTextureId the texture id to sample from the active pack stack
     * @param textureScale the per-type scale factor - see {@link #ITEM_SCALE},
     * {@link #ENTITY_ITEM_SCALE}, {@link #ARMOR_SCALE}
     * @param atlasSampled whether the glinted surface is sampled through the live item texture
     * atlas - {@code true} for GUI and held items (whose model quads carry the sprite's tiny atlas
     * sub-rect as UV0, so {@link #OFFLINE_ATLAS_UV_FRACTION_U} / {@link #OFFLINE_ATLAS_UV_FRACTION_V}
     * apply), {@code false} for worn armor (whose model is sampled through full {@code 0..1} layout
     * UVs, where the atlas fraction must not apply or the glint collapses to a flat tint). Ignored
     * when {@code spriteUv} is present
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
        boolean atlasSampled,
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
                true,
                VANILLA_U_LOOP_MILLIS,
                VANILLA_V_LOOP_MILLIS,
                ColorMath.WHITE
            );
        }

        /**
         * Default armor glint preset at the given frame rate. Uses the vanilla
         * {@link #ARMOR_GLINT_TEXTURE_ID}, {@link #ARMOR_SCALE}, and the 110s/30s loop periods.
         * Not atlas-sampled - worn armor is sampled through full {@code 0..1} model-layout UVs, so
         * the offline atlas fraction does <em>not</em> apply (the raw {@link #ARMOR_SCALE}
         * already spans ~20 of the glint's 128 texels for a scrolling sheen).
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
                false,
                VANILLA_U_LOOP_MILLIS,
                VANILLA_V_LOOP_MILLIS,
                ColorMath.WHITE
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
                true,
                VANILLA_U_LOOP_MILLIS,
                VANILLA_V_LOOP_MILLIS,
                ColorMath.WHITE
            );
        }
    }

    /**
     * Renders an fps-paced glint loop by mapping each output frame to a glint time and delegating to
     * {@link #applyGlintAtTimes}. Frame {@code n} samples glint time
     * {@code round(n * (1000 / framesPerSecond) * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS)}, the wall-clock
     * schedule the live renderers (item / armor / entity) emit for their animated output.
     *
     * @param base the base item texture
     * @param glintTexture the glint scroll texture
     * @param options the glint configuration
     * @return an ordered list of composited frames, one per output frame
     */
    public static @NotNull ConcurrentList<PixelBuffer> applyGlint(
        @NotNull PixelBuffer base,
        @NotNull PixelBuffer glintTexture,
        @NotNull GlintOptions options
    ) {
        return applyGlint(base, glintTexture, options, null);
    }

    /**
     * {@link #applyGlint(PixelBuffer, PixelBuffer, GlintOptions)} that restricts the foil to the
     * pixels marked in {@code glintMask} - e.g. only the armor of a player / entity render rather
     * than the whole opaque silhouette. {@code null} applies the foil to every opaque pixel, the
     * historic behaviour used for whole-subject items and blocks.
     *
     * @param base the base render
     * @param glintTexture the glint scroll texture
     * @param options the glint configuration
     * @param glintMask the per-pixel glint coverage mask, or {@code null} to foil all opaque pixels
     * @return an ordered list of composited frames, one per glint frame
     */
    public static @NotNull ConcurrentList<PixelBuffer> applyGlint(
        @NotNull PixelBuffer base,
        @NotNull PixelBuffer glintTexture,
        @NotNull GlintOptions options,
        @Nullable GlintMask glintMask
    ) {
        double millisPerFrame = 1000.0 / options.framesPerSecond();
        long[] glintTimes = new long[options.totalFrames()];
        for (int frameIndex = 0; frameIndex < glintTimes.length; frameIndex++)
            glintTimes[frameIndex] = Math.round(frameIndex * millisPerFrame * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS);
        return applyGlintAtTimes(base, glintTexture, glintTimes, options, null, glintMask);
    }

    /**
     * Renders one glint frame per supplied glint time, stamping a scrolled copy of the glint texture
     * over a fresh copy of the base for each. The glint time {@code t} is the value vanilla derives
     * as {@code (long)(Util.getMillis() * glintSpeed * MAX_ENCHANTMENT_GLINT_SPEED_MILLIS)}; passing
     * an explicit schedule lets a parity harness drive both this renderer and the live client off the
     * same deterministic times so their animations are phase-aligned.
     *
     * @param base the base item texture
     * @param glintTexture the glint scroll texture
     * @param glintTimes the ordered glint times (vanilla post-{@code glintSpeed} millis), one per frame
     * @param options the glint configuration
     * @return an ordered list of composited frames, one per supplied glint time
     */
    public static @NotNull ConcurrentList<PixelBuffer> applyGlintAtTimes(
        @NotNull PixelBuffer base,
        @NotNull PixelBuffer glintTexture,
        long @NotNull [] glintTimes,
        @NotNull GlintOptions options
    ) {
        return applyGlintAtTimes(base, glintTexture, glintTimes, options, null);
    }

    /**
     * {@link #applyGlintAtTimes(PixelBuffer, PixelBuffer, long[], GlintOptions)} overload that samples
     * {@code UV0} through an explicit atlas sprite-UV rect instead of the offline normalized-icon
     * approximation. The glint-parity harness uses it to replay vanilla's real {@code UV0} through this
     * exact pipeline and confirm every other factor (scroll, rotation, scale, sampling, blend, alpha)
     * is bit-accurate. {@code null} runs the offline approximation - the production path.
     *
     * @param base the base item texture
     * @param glintTexture the glint scroll texture
     * @param glintTimes the ordered glint times (vanilla post-{@code glintSpeed} millis), one per frame
     * @param options the glint configuration
     * @param spriteUv the real atlas sprite-UV rect to sample {@code UV0} through, or {@code null} for
     * the offline approximation
     * @return an ordered list of composited frames, one per supplied glint time
     */
    public static @NotNull ConcurrentList<PixelBuffer> applyGlintAtTimes(
        @NotNull PixelBuffer base,
        @NotNull PixelBuffer glintTexture,
        long @NotNull [] glintTimes,
        @NotNull GlintOptions options,
        @Nullable SpriteUv spriteUv
    ) {
        return applyGlintAtTimes(base, glintTexture, glintTimes, options, spriteUv, null);
    }

    /**
     * Core glint compositor: as
     * {@link #applyGlintAtTimes(PixelBuffer, PixelBuffer, long[], GlintOptions, SpriteUv)} but also
     * restricts the foil to the pixels marked in {@code glintMask} (e.g. only the armor of a player
     * / entity render). {@code null} applies the foil to every opaque pixel.
     *
     * @param base the base render
     * @param glintTexture the glint scroll texture
     * @param glintTimes the ordered glint times (vanilla post-{@code glintSpeed} millis), one per frame
     * @param options the glint configuration
     * @param spriteUv the real atlas sprite-UV rect to sample {@code UV0} through, or {@code null} for
     *     the offline approximation
     * @param glintMask the per-pixel glint coverage mask, or {@code null} to foil all opaque pixels
     * @return an ordered list of composited frames, one per supplied glint time
     */
    public static @NotNull ConcurrentList<PixelBuffer> applyGlintAtTimes(
        @NotNull PixelBuffer base,
        @NotNull PixelBuffer glintTexture,
        long @NotNull [] glintTimes,
        @NotNull GlintOptions options,
        @Nullable SpriteUv spriteUv,
        @Nullable GlintMask glintMask
    ) {
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        PixelBuffer tintedGlint = options.tintArgb() == ColorMath.WHITE
            ? glintTexture
            : ColorMath.tint(glintTexture, options.tintArgb());

        for (long glintTime : glintTimes) {
            PixelBuffer frame = base.copy();
            stampGlint(frame, tintedGlint, glintTime, options, spriteUv, glintMask);
            frames.add(frame);
        }
        return frames;
    }

    /**
     * Composites the glint texture over a buffer for a single glint time, masked by the buffer's own
     * alpha so the glint only shows where the item is opaque. Each opaque pixel samples the glint
     * texture through vanilla's UV transform {@code translation(-u, v) . rotateZ(theta) . scale(s)}
     * applied to the pixel-centre normalized icon coordinate, wrapping {@code GL_REPEAT}-style into
     * the glint texture, then blends with the quadratic {@code GLINT} blend.
     */
    private static void stampGlint(
        @NotNull PixelBuffer target,
        @NotNull PixelBuffer glint,
        long glintTimeMillis,
        @NotNull GlintOptions options,
        @Nullable SpriteUv rect,
        @Nullable GlintMask glintMask
    ) {
        int w = target.width();
        int h = target.height();
        int glintW = glint.width();
        int glintH = glint.height();

        float u = (float) Math.floorMod(glintTimeMillis, options.uLoopMillis()) / options.uLoopMillis();
        float v = (float) Math.floorMod(glintTimeMillis, options.vLoopMillis()) / options.vLoopMillis();
        // Per-axis scale = textureScale folded with the sprite's atlas-span fraction. With a real
        // atlas UV0 (rect present) the rect carries the span, so the raw textureScale is used directly
        // on both axes, exactly as vanilla's TextureMat over the sprite coordinate. Offline, the
        // normalized-icon UV stands in for UV0: an atlas-sampled item folds in the anisotropic items
        // -atlas fractions to span vanilla's real 16 (U) / 32 (V) glint texels, while worn armor
        // sampled through full 0..1 model UVs uses the raw scale on both axes.
        boolean itemAtlas = rect == null && options.atlasSampled();
        float scaleX = itemAtlas ? options.textureScale() * OFFLINE_ATLAS_UV_FRACTION_U : options.textureScale();
        float scaleY = itemAtlas ? options.textureScale() * OFFLINE_ATLAS_UV_FRACTION_V : options.textureScale();
        float cos = (float) Math.cos(ROTATION_RADIANS);
        float sin = (float) Math.sin(ROTATION_RADIANS);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dst = target.getPixel(x, y);
                if (ColorMath.alpha(dst) == 0) continue;
                // Restrict the foil to glinted geometry (e.g. armor): when a mask is supplied, only
                // its marked pixels receive the glint. A null mask foils every opaque pixel, which is
                // correct for whole-subject items and blocks.
                if (glintMask != null && !glintMask.marked(x, y)) continue;

                // UV0 source: the real sprite atlas sub-rect when known (lerped across the icon by the
                // pixel-centre fraction), else the offline pixel-centre normalized icon coordinate
                // standing in for it (textureScale absorbs the band-frequency difference - see the
                // class javadoc).
                float s = (x + 0.5f) / w;
                float t = (y + 0.5f) / h;
                float uv0x = rect == null ? s : rect.u0() + s * (rect.u1() - rect.u0());
                float uv0y = rect == null ? t : rect.v0() + t * (rect.v1() - rect.v0());

                // TextureMat = translation(-u, v) . rotateZ(theta) . scale(scale) applied to
                // (uv0, 0, 1): scale first, rotate in the UV plane, then translate by the scroll.
                float sx = uv0x * scaleX;
                float sy = uv0y * scaleY;
                float rx = cos * sx - sin * sy;
                float ry = sin * sx + cos * sy;
                float tcx = rx - u;
                float tcy = ry + v;

                int glintPixel = sampleBilinearWrapped(glint, tcx * glintW, tcy * glintH);
                if (ColorMath.alpha(glintPixel) < GLINT_DISCARD_ALPHA_BYTE) continue;

                // Pre-fade by GlintAlpha (vanilla's glint shader multiplies by it before the blend),
                // keeping the sampled alpha so a transparent glint texel stays a no-op, then composite
                // with the quadratic additive blend vanilla's GLINT pipeline uses
                // (out.rgb = src.rgb^2 + dst.rgb, dst alpha preserved).
                int faded = ColorMath.pack(
                    ColorMath.alpha(glintPixel),
                    Math.round(ColorMath.red(glintPixel) * GLINT_ALPHA),
                    Math.round(ColorMath.green(glintPixel) * GLINT_ALPHA),
                    Math.round(ColorMath.blue(glintPixel) * GLINT_ALPHA));
                target.setPixel(x, y, ColorMath.blend(faded, dst, BlendMode.QUADRATIC_ADD));
            }
        }
    }

    /**
     * Bilinearly samples the glint texture at the given texel-space coordinate with {@code GL_REPEAT}
     * wrapping, matching vanilla's {@code blur: true} (LINEAR) sampler state on the glint texture.
     * Nearest sampling produced hard texel edges - a glint visibly sharper than vanilla's softly
     * filtered sheen; this interpolates the four neighbouring texels at the sample's pixel centre.
     *
     * @param texture the glint texture
     * @param texelX the sample X in texel units (pre-wrap, pixel-corner space)
     * @param texelY the sample Y in texel units
     * @return the bilinearly-interpolated ARGB sample
     */
    private static int sampleBilinearWrapped(@NotNull PixelBuffer texture, float texelX, float texelY) {
        int w = texture.width();
        int h = texture.height();
        // Shift to texel centres so integer coords land mid-texel, then bilerp toward the next texel.
        float fx = texelX - 0.5f;
        float fy = texelY - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float dx = fx - x0;
        float dy = fy - y0;
        int xa = Math.floorMod(x0, w);
        int xb = Math.floorMod(x0 + 1, w);
        int ya = Math.floorMod(y0, h);
        int yb = Math.floorMod(y0 + 1, h);

        int p00 = texture.getPixel(xa, ya);
        int p10 = texture.getPixel(xb, ya);
        int p01 = texture.getPixel(xa, yb);
        int p11 = texture.getPixel(xb, yb);
        return ColorMath.pack(
            bilerp(ColorMath.alpha(p00), ColorMath.alpha(p10), ColorMath.alpha(p01), ColorMath.alpha(p11), dx, dy),
            bilerp(ColorMath.red(p00), ColorMath.red(p10), ColorMath.red(p01), ColorMath.red(p11), dx, dy),
            bilerp(ColorMath.green(p00), ColorMath.green(p10), ColorMath.green(p01), ColorMath.green(p11), dx, dy),
            bilerp(ColorMath.blue(p00), ColorMath.blue(p10), ColorMath.blue(p01), ColorMath.blue(p11), dx, dy)
        );
    }

    /** Bilinear interpolation of one channel across the four corner texels. */
    private static int bilerp(int c00, int c10, int c01, int c11, float dx, float dy) {
        float top = c00 + (c10 - c00) * dx;
        float bottom = c01 + (c11 - c01) * dx;
        return Math.clamp(Math.round(top + (bottom - top) * dy), 0, 255);
    }

}
