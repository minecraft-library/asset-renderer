package lib.minecraft.renderer.bake.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.PortalOptions;
import lib.minecraft.renderer.vanilla.PortalPalette;
import org.jetbrains.annotations.NotNull;

import java.util.stream.IntStream;

/**
 * CPU transcription of the parallax star-field fragment shader vanilla ships in
 * {@code assets/minecraft/shaders/core/rendertype_end_portal.fsh}, baked one face at a time into a
 * pixel buffer.
 * <p>
 * The loop follows the {@code .fsh} verbatim - the {@link PortalPalette#COLORS} table, the
 * {@code SCALE_TRANSLATE} / per-layer {@code translate} / {@code scale*rotate} matrices, and the
 * {@code textureProj}-style divide-by-w. The constants below are this bake's own coefficients:
 * they trade the GPU's viewport resolution for a fixed sprite size and are read by no table.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class PortalBake {

    /**
     * Internal per-output-pixel supersampling factor applied inside {@link #bakeFace}. Each
     * output pixel box-averages {@code PARALLAX_SUPERSAMPLE x PARALLAX_SUPERSAMPLE} shader
     * evaluations at sub-pixel centres - {@code 4} sharpens the per-texel star edges that
     * appear square under the UV_SCALE zoom by averaging {@code 16} sub-samples per pixel.
     */
    private static final int PARALLAX_SUPERSAMPLE = 4;

    /**
     * Compresses the per-layer spatial scan so a fixed output size resolves each parallax star
     * across multiple pixels instead of a sub-pixel speck. Vanilla runs the shader on the GPU
     * at the user's viewport resolution - a full-screen portal on a {@code 2560px} display gets
     * {@code ~0.4} noise-texel stride per screen pixel, so stars show up as clean {@code 2-3px}
     * features. Our CPU bake at {@code 512} with {@code UV_SCALE = 1} instead has stride
     * {@code ~2.1} texels per pixel - every star fits inside one (sub-pixel) output cell and
     * visually reads as "tiny".
     * <p>
     * Setting {@code UV_SCALE < 1} shrinks the face's slice of the full parallax canvas, so
     * each noise texel now spans multiple output pixels. Crucially the time-driven {@code ty}
     * translation is scaled by the same factor in {@link #bakeFace} - that way the pattern
     * traverses the face at the same pixels-per-tick rate as the unscaled bake, so the
     * animation speed the user is used to stays put while the stars actually become visible as
     * moving pixels instead of single-frame sparkles.
     * <p>
     * {@code 0.3} widens each noise texel to {@code ~3.3px} at {@code 512} output - small
     * enough that stars still read as discrete pixels, large enough to track as they drift.
     * Matches the density the wiki's {@code 300px} gateway GIF shows after its higher-res
     * screenshot was downsampled.
     */
    private static final float UV_SCALE = 0.3f;

    /**
     * Per-layer additive rotation (degrees) folded into the shader's base {@code angle} before
     * trig evaluation. Rotates each layer's noise grid (and therefore its motion direction) CCW
     * on screen by the given amount. Vanilla's {@code (L² * 4321 + L * 9) * 2°} is preserved as
     * the base; this array just nudges specific layers.
     * <p>
     * Using the angle offset rather than a simple {@code ty}-sign flip matters for {@code 180°}
     * reversals: negating {@code ty} flips the flow direction but leaves the rotation matrix
     * (and therefore the layer's implied "streak" orientation) unchanged, which reads as stars
     * moving backwards along their grain. A full {@code 180°} offset mirrors both the rotation
     * axis and the motion vector, so streaks move naturally along their own axis.
     * <ul>
     *   <li>Indices 13 + 14 (layers 14 + 15, the teal/cyan-green stars): {@code 180°} - flips
     *       them from bottom-to-top drift to top-to-bottom, including their streak axis.</li>
     *   <li>Index 15 (layer 16, pure-blue end_gateway layer, {@code B=0.66}): {@code 30°} -
     *       rotates its default right-to-left motion CCW so blue stars drift down-and-left
     *       instead of straight-left, giving a visible third direction alongside the flipped
     *       teal layers.</li>
     * </ul>
     */
    private static final float[] LAYER_ANGLE_OFFSET_DEG = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 180f, 180f, 30f
    };

    /**
     * Returns the layer count for the given portal variant. Equivalent to the
     * {@code PORTAL_LAYERS} shader define configured on the vanilla render pipeline.
     *
     * @param portal the portal variant
     * @return the per-pipeline {@code PORTAL_LAYERS} count
     */
    private static int layerCount(@NotNull PortalOptions.Portal portal) {
        return portal == PortalOptions.Portal.END_GATEWAY
            ? PortalPalette.LAYER_COUNT_END_GATEWAY
            : PortalPalette.LAYER_COUNT_END_PORTAL;
    }

    /**
     * Wraps a game-time age into the shader's {@code GameTime} uniform - the day fraction in
     * {@code [0, 1)}. Vanilla adds the partial tick after the wrap and before the divide, so an age
     * between ticks lands between two whole-tick uniforms rather than snapping to one.
     * <p>
     * A whole tick takes the integer path it always took. The general formula agrees with it
     * mathematically, but not necessarily in the last bit of the float, and an unsubdivided bake must
     * stay exactly where it was.
     */
    private static float gameTimeUniform(float ageInTicks) {
        if (ageInTicks == Math.rint(ageInTicks) && Math.abs(ageInTicks) <= Integer.MAX_VALUE)
            return Math.floorMod((int) ageInTicks, PortalPalette.GAME_TIME_PERIOD_TICKS) / (float) PortalPalette.GAME_TIME_PERIOD_TICKS;

        double wrapped = ageInTicks - PortalPalette.GAME_TIME_PERIOD_TICKS * Math.floor(ageInTicks / (double) PortalPalette.GAME_TIME_PERIOD_TICKS);
        return (float) (wrapped / PortalPalette.GAME_TIME_PERIOD_TICKS);
    }

    /**
     * Bakes one face of parallax star-field output at the given game-time tick into a fresh
     * pixel buffer, matching the body of {@code rendertype_end_portal.fsh} per-pixel.
     * <p>
     * The shader samples {@code Sampler0} (end_sky) scaled by {@code COLORS[0]}, then accumulates
     * {@code PORTAL_LAYERS} further samples of {@code Sampler1} (end_portal noise) through
     * per-layer transforms composed in {@code end_portal_layer(float layer)}. Fog and alpha are
     * dropped; the atlas tile is opaque.
     * <p>
     * The {@code gameTick} argument is converted internally to vanilla's {@code GameTime}
     * uniform value via {@code (tick % 24000) / 24000f} - matching
     * {@code GlobalSettingsUniform.update}'s formula.
     *
     * @param portal the portal variant - drives layer count
     * @param gameTick the vanilla game tick; converted to the shader's {@code [0, 1)}
     *                 {@code GameTime} uniform value internally
     * @param endSky Sampler0 - {@code environment/end_sky}
     * @param endPortalNoise Sampler1 - {@code entity/end_portal/end_portal}
     * @param size output sprite edge length in pixels
     * @return a freshly allocated {@code size x size} ARGB buffer with the baked face
     */
    public static @NotNull PixelBuffer bakeFace(
        @NotNull PortalOptions.Portal portal,
        float gameTick,
        @NotNull PixelBuffer endSky,
        @NotNull PixelBuffer endPortalNoise,
        int size
    ) {
        float time = gameTimeUniform(gameTick);
        int layers = layerCount(portal);
        PixelBuffer buffer = PixelBuffer.create(size, size);
        int ssaa = PARALLAX_SUPERSAMPLE;
        float invSsaaGrid = 1f / (size * ssaa);
        float invSampleCount = 1f / (ssaa * ssaa);

        // Per-layer shader constants hoisted out of the pixel loop. The layer transform reduces
        // to `(u, v) -> (a*(sxc*u - sxs*v) + bx, a*(sxs*u + sxc*v) + by)` where the coefficients
        // depend only on layer index and time - at 512x512 SSAA=4 this avoids 63M Math.cos/sin
        // calls per frame.
        float[] layerSxc = new float[layers];
        float[] layerSxs = new float[layers];
        float[] layerBx = new float[layers];
        float[] layerBy = new float[layers];
        for (int i = 0; i < layers; i++) {
            float layer = i + 1;
            float s = (4.5f - layer / 4f) * 2f;
            float angleDeg = (layer * layer * 4321f + layer * 9f) * 2f + LAYER_ANGLE_OFFSET_DEG[i];
            float angle = (float) Math.toRadians(angleDeg);
            float c = (float) Math.cos(angle);
            float si = (float) Math.sin(angle);
            float tx = 17f / layer;
            float ty = (2f + layer / 1.5f) * (time * 1.5f);
            // Composed (scale*rotate) -> translate -> SCALE_TRANSLATE, all pre-folded against
            // the 0.5 scale + 0.25 post-translate of SCALE_TRANSLATE:
            //   out_u = 0.5 * [s*(c*u - si*v) + tx] + 0.25
            //         = (0.5*s)*c*u  -  (0.5*s)*si*v  +  (0.5*tx + 0.25)
            // layerSxc / layerSxs absorb the 0.5*s factor so the inner loop is pure mul+add.
            // UV_SCALE multiplies both the spatial scale (halfS) and the time-driven ty - the
            // first shrinks the face's noise-UV slice so stars visibly span multiple pixels,
            // the second keeps the pattern's pixels-per-tick motion identical to the unscaled
            // bake. The static per-layer tx offset is unscaled; it controls initial layer
            // position and scaling it would shift which noise region each layer starts from
            // without a visible benefit.
            float halfS = 0.5f * s * UV_SCALE;
            layerSxc[i] = halfS * c;
            layerSxs[i] = halfS * si;
            layerBx[i] = 0.5f * tx + 0.25f;
            layerBy[i] = 0.5f * ty * UV_SCALE + 0.25f;
        }

        // Pre-scale every COLORS[i] by invSampleCount AND by 1/255 so the SSAA box-filter mean,
        // the byte scale factor, and the byte-to-float normalisation all fold into a single
        // pre-combined coefficient - the inner loop adds raw byte values weighted directly.
        final float inv255 = 1f / 255f;
        final float combined0 = invSampleCount * inv255;
        final float base0R = PortalPalette.COLORS[0][0] * combined0;
        final float base0G = PortalPalette.COLORS[0][1] * combined0;
        final float base0B = PortalPalette.COLORS[0][2] * combined0;
        final float[] cR = new float[layers];
        final float[] cG = new float[layers];
        final float[] cB = new float[layers];
        for (int i = 0; i < layers; i++) {
            cR[i] = PortalPalette.COLORS[i][0] * combined0;
            cG[i] = PortalPalette.COLORS[i][1] * combined0;
            cB[i] = PortalPalette.COLORS[i][2] * combined0;
        }

        // Row-parallel bake: each py row writes to a disjoint pixel range in `buffer` so
        // concurrent setPixel calls never alias. All captured arrays are read-only from here
        // on. ForkJoin's parallel terminal op establishes happens-before when the outer call
        // returns, making the filled buffer safely publishable to the caller.
        final int finalSize = size;
        final int finalSsaa = ssaa;
        final float finalInvSsaaGrid = invSsaaGrid;
        final int finalLayers = layers;
        IntStream.range(0, size).parallel().forEach(py -> {
            for (int px = 0; px < finalSize; px++) {
                float rAcc = 0f, gAcc = 0f, bAcc = 0f;

                // Box-filter integral under this output pixel: ssaa x ssaa shader evaluations at
                // sub-pixel centres. Each sub-sample re-runs the full Sampler0 base + per-layer
                // Sampler1 accumulation, matching vanilla's per-fragment output; the outer mean
                // reproduces the starfield look vanilla's high-res fragment stage produces before
                // window downsample.
                for (int sy = 0; sy < finalSsaa; sy++) {
                    for (int sx = 0; sx < finalSsaa; sx++) {
                        // Input texProj0 treats the face's own (u, v) as screen space, matching
                        // the divide-by-w semantics of textureProj with w = 1.
                        float u = (px * finalSsaa + sx + 0.5f) * finalInvSsaaGrid;
                        float v = (py * finalSsaa + sy + 0.5f) * finalInvSsaaGrid;

                        // Base layer: Sampler0 * COLORS[0].
                        int baseArgb = sampleRepeat(endSky, u, v);
                        rAcc += ColorMath.red(baseArgb)   * base0R;
                        gAcc += ColorMath.green(baseArgb) * base0G;
                        bAcc += ColorMath.blue(baseArgb)  * base0B;

                        // Parallax layers: Sampler1 * COLORS[i], transformed by end_portal_layer(i+1).
                        for (int i = 0; i < finalLayers; i++) {
                            float outU = layerSxc[i] * u - layerSxs[i] * v + layerBx[i];
                            float outV = layerSxs[i] * u + layerSxc[i] * v + layerBy[i];
                            int sampled = sampleRepeat(endPortalNoise, outU, outV);
                            rAcc += ColorMath.red(sampled)   * cR[i];
                            gAcc += ColorMath.green(sampled) * cG[i];
                            bAcc += ColorMath.blue(sampled)  * cB[i];
                        }
                    }
                }

                int r = Math.clamp((int) (rAcc * 255f + 0.5f), 0, 255);
                int g = Math.clamp((int) (gAcc * 255f + 0.5f), 0, 255);
                int b = Math.clamp((int) (bAcc * 255f + 0.5f), 0, 255);
                buffer.setPixel(px, py, ColorMath.pack(0xFF, r, g, b));
            }
        });

        return buffer;
    }

    /**
     * Samples a texture with GL_REPEAT semantics. UV values outside {@code [0, 1]} wrap modulo 1
     * so the parallax scan always hits a valid texel regardless of how far the per-layer transform
     * has drifted.
     *
     * @param buffer the source texture
     * @param u the u coordinate (no clamp - any real value is valid)
     * @param v the v coordinate (no clamp - any real value is valid)
     * @return the ARGB texel at the wrapped coordinates
     */
    private static int sampleRepeat(@NotNull PixelBuffer buffer, float u, float v) {
        int w = buffer.width();
        int h = buffer.height();
        // Wrap into [0, 1) using IEEE-safe modulo; negative inputs are valid.
        float uw = u - (float) Math.floor(u);
        float vw = v - (float) Math.floor(v);
        int tx = Math.clamp((int) (uw * w), 0, w - 1);
        int ty = Math.clamp((int) (vw * h), 0, h - 1);
        return buffer.getPixel(tx, ty);
    }

}
