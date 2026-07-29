package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.RasterPass;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.FaceTextures;
import lib.minecraft.renderer.option.PortalOptions;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.tensor.Box;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.stream.IntStream;

/**
 * Renders vanilla end portal and end gateway blocks by CPU-baking the same parallax star-field
 * shader vanilla ships in {@code assets/minecraft/shaders/core/rendertype_end_portal.fsh}. Both
 * portals share that shader - {@code net.minecraft.client.renderer.RenderPipelines} differs only
 * in the {@code PORTAL_LAYERS} define (15 for end_portal, 16 for end_gateway) - so one renderer
 * covers both variants via a {@link PortalOptions.Portal} parameter.
 * <p>
 * Two sub-renderers are exposed, matching the {@link FluidRenderer} shape:
 * <ul>
 * <li>{@link Isometric3D} - builds geometry via {@link BlockGeometryKit} (full unit cube for
 * {@link PortalOptions.Portal#END_GATEWAY}, thin slab at vanilla's {@code BOTTOM=0.375}/
 * {@code TOP=0.75} for {@link PortalOptions.Portal#END_PORTAL}) and rasterizes through the
 * standard {@code [30, 225, 0]} isometric pose.</li>
 * <li>{@link PortalFace2D} - bakes a single top-face sprite and blits it flat - the atlas tile
 * path and the view a caller would use for an inventory icon.</li>
 * </ul>
 * The parallax loop is transcribed verbatim from the {@code .fsh} (including the {@code COLORS}
 * table, {@code SCALE_TRANSLATE} / per-layer {@code translate} / {@code scale*rotate} matrices,
 * and the {@code textureProj}-style divide-by-w). Animation is driven by the shader's
 * {@code GameTime} uniform, fed in from {@link AnimationOptions#getStartTick()} and advancing by
 * {@link AnimationOptions#getTicksPerFrame()} per output frame; static renders use {@code time = 0}.
 * <p>
 * Scene-aware concerns (fog, additive translucent blending against the underlying block, view-
 * dependent parallax from the observer's camera) are deliberately out of scope - the bake treats
 * each face's own {@code (u, v)} as screen space so the result is camera-independent and
 * identical per tile.
 */
public final class PortalRenderer implements Renderer<PortalOptions> {

    /**
     * Resource id of {@code assets/minecraft/textures/environment/end_sky.png} (Sampler0 in the vanilla shader).
     */
    static final @NotNull String END_SKY_TEXTURE_ID = "minecraft:environment/end_sky";

    /**
     * Resource id of {@code assets/minecraft/textures/entity/end_portal/end_portal.png} (Sampler1 in the vanilla shader).
     */
    static final @NotNull String END_PORTAL_NOISE_TEXTURE_ID = "minecraft:entity/end_portal/end_portal";

    /**
     * Vanilla's {@code GameTime} uniform period - one day cycle in ticks. Matches
     * {@code GlobalSettingsUniform.update}'s {@code (gameTick % 24000L + partialTick) / 24000f}
     * bytecode at offsets 97-116.
     */
    private static final int GAME_TIME_PERIOD_TICKS = 24000;

    /**
     * {@code PORTAL_LAYERS} for {@link PortalOptions.Portal#END_PORTAL} (from {@code RenderPipelines.END_PORTAL}).
     */
    private static final int LAYER_COUNT_END_PORTAL = 15;

    /**
     * {@code PORTAL_LAYERS} for {@link PortalOptions.Portal#END_GATEWAY} (from {@code RenderPipelines.END_GATEWAY}).
     */
    private static final int LAYER_COUNT_END_GATEWAY = 16;

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
     * {@code COLORS[16]} table transcribed from {@code rendertype_end_portal.fsh} at Minecraft 26.1.
     * Indexed by loop iteration {@code i} in the shader's body; entry 0 also scales the base
     * {@code Sampler0} draw.
     */
    private static final float[][] COLORS = {
        { 0.022087f, 0.098399f, 0.110818f },
        { 0.011892f, 0.095924f, 0.089485f },
        { 0.027636f, 0.101689f, 0.100326f },
        { 0.046564f, 0.109883f, 0.114838f },
        { 0.064901f, 0.117696f, 0.097189f },
        { 0.063761f, 0.086895f, 0.123646f },
        { 0.084817f, 0.111994f, 0.166380f },
        { 0.097489f, 0.154120f, 0.091064f },
        { 0.106152f, 0.131144f, 0.195191f },
        { 0.097721f, 0.110188f, 0.187229f },
        { 0.133516f, 0.138278f, 0.148582f },
        { 0.070006f, 0.243332f, 0.235792f },
        { 0.196766f, 0.142899f, 0.214696f },
        { 0.047281f, 0.315338f, 0.321970f },
        { 0.204675f, 0.390010f, 0.302066f },
        { 0.080955f, 0.314821f, 0.661491f }
    };

    // --- end_portal slab dimensions (vanilla TheEndPortalRenderer.BOTTOM / .TOP) ---

    /**
     * End portal slab bottom Y in unit-cube model space. Matches {@code TheEndPortalRenderer.BOTTOM}.
     */
    private static final float END_PORTAL_SLAB_BOTTOM_Y = 0.375f;

    /**
     * End portal slab top Y in unit-cube model space. Matches {@code TheEndPortalRenderer.TOP}.
     */
    private static final float END_PORTAL_SLAB_TOP_Y = 0.75f;

    private final @NotNull Isometric3D isometric3D;
    private final @NotNull PortalFace2D portalFace2D;

    /**
     * Constructs a portal renderer over the given context, wiring up the two sub-renderers.
     *
     * @param context renderer context for texture resolution and engine setup
     */
    public PortalRenderer(@NotNull RendererContext context) {
        this.isometric3D = new Isometric3D(context);
        this.portalFace2D = new PortalFace2D(context);
    }

    /**
     * Dispatches on {@link PortalOptions#getType()} to the 3D isometric or flat 2D sub-renderer, then
     * composites the result over the caller's background.
     */
    @Override
    public @NotNull ImageData render(@NotNull PortalOptions options) {
        ImageData rendered = switch (options.getType()) {
            case ISOMETRIC_3D -> this.isometric3D.render(options);
            case PORTAL_FACE_2D -> this.portalFace2D.render(options);
        };
        return options.getBackground().composite(rendered);
    }

    /**
     * Returns the layer count for the given portal variant. Equivalent to the
     * {@code PORTAL_LAYERS} shader define configured on the vanilla render pipeline.
     *
     * @param portal the portal variant
     * @return the per-pipeline {@code PORTAL_LAYERS} count
     */
    static int layerCount(@NotNull PortalOptions.Portal portal) {
        return portal == PortalOptions.Portal.END_GATEWAY ? LAYER_COUNT_END_GATEWAY : LAYER_COUNT_END_PORTAL;
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
            return Math.floorMod((int) ageInTicks, GAME_TIME_PERIOD_TICKS) / (float) GAME_TIME_PERIOD_TICKS;

        double wrapped = ageInTicks - GAME_TIME_PERIOD_TICKS * Math.floor(ageInTicks / (double) GAME_TIME_PERIOD_TICKS);
        return (float) (wrapped / GAME_TIME_PERIOD_TICKS);
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
    static @NotNull PixelBuffer bakeFace(
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
        final float base0R = COLORS[0][0] * combined0;
        final float base0G = COLORS[0][1] * combined0;
        final float base0B = COLORS[0][2] * combined0;
        final float[] cR = new float[layers];
        final float[] cG = new float[layers];
        final float[] cB = new float[layers];
        for (int i = 0; i < layers; i++) {
            cR[i] = COLORS[i][0] * combined0;
            cG[i] = COLORS[i][1] * combined0;
            cB[i] = COLORS[i][2] * combined0;
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

    /**
     * Applies an optional ARGB tint override to every pixel via channel multiplication. When
     * {@code argbTint} is {@link ColorMath#WHITE} the input is returned unchanged.
     *
     * @param buffer the baked parallax output
     * @param argbTint the tint colour, or {@link ColorMath#WHITE} for no tint
     * @return the tinted buffer (same instance if untinted; a fresh buffer otherwise)
     */
    private static @NotNull PixelBuffer applyTintIfNeeded(@NotNull PixelBuffer buffer, int argbTint) {
        if (argbTint == ColorMath.WHITE) return buffer;
        return ColorMath.tint(buffer, argbTint);
    }

    /**
     * Resolves the tint override for an options pair, defaulting to {@link ColorMath#WHITE} (no
     * tint) when {@link PortalOptions#getTintArgbOverride()} is {@code null}.
     */
    private static int resolveTint(@NotNull PortalOptions options) {
        Integer override = options.getTintArgbOverride();
        return override == null ? ColorMath.WHITE : override;
    }

    /**
     * Computes the number of bridge frames to bake on top of {@link AnimationOptions#getFrameCount}
     * for the seamless-loop crossfade. Returns {@code 0} when the feature is disabled or the
     * animation is too short to blend meaningfully.
     */
    private static int bridgeFrameCount(@NotNull PortalOptions options) {
        int total = options.getAnimation().getFrameCount();
        if (total < 3) return 0;
        float bridge = options.getAnimation().getLoopFadeBridgePct();
        if (bridge <= 0f) return 0;
        return Math.clamp(Math.round(bridge * total), 0, total - 1);
    }

    /**
     * Applies the seamless-loop bridge crossfade in-place. For output frame {@code i in [0, K)}
     * (where {@code K = bridgeFrames}), the frame is blended toward its shifted-continuation
     * partner at {@code frames[i + N]} (the shader's natural continuation past the loop's end),
     * weighted so {@code i=0} is pure partner content and {@code i=K-1} is pure raw content.
     * <p>
     * Both layers in the crossfade are animated, so the fade region never resolves to a static
     * frame - which was the visible artifact of an anchor-based fade. After blending the caller
     * must trim {@code frames} down to the intended {@code N} frames; the extra bridge frames
     * are only needed as blend partners.
     *
     * @param frames full set of {@code N + bridgeFrames} baked frames; mutated in-place
     * @param outputCount the intended {@code N} output frames (the first N entries of frames)
     * @param bridgeFrames the bridge length {@code K} from {@link #bridgeFrameCount}
     */
    private static void applyBridgeCrossfade(
        @NotNull ConcurrentList<PixelBuffer> frames,
        int outputCount,
        int bridgeFrames
    ) {
        if (bridgeFrames <= 0 || bridgeFrames >= outputCount) return;
        if (frames.size() < outputCount + bridgeFrames) return;

        for (int i = 0; i < bridgeFrames; i++) {
            float alpha = (float) i / (float) bridgeFrames;
            PixelBuffer frame = frames.get(i);
            PixelBuffer partner = frames.get(i + outputCount);
            blendTowardPartner(frame, partner, alpha);
        }
    }

    /**
     * Blends {@code frame} in-place toward {@code partner} by weight {@code alpha}: final pixel
     * value is {@code alpha * frame + (1 - alpha) * partner} on every channel including alpha.
     */
    private static void blendTowardPartner(
        @NotNull PixelBuffer frame,
        @NotNull PixelBuffer partner,
        float alpha
    ) {
        final float invAlpha = 1f - alpha;
        final int width = frame.width();
        final int height = frame.height();
        // Row-parallel blend: each y row reads from distinct getPixel offsets and writes to
        // distinct setPixel offsets in `frame`, so concurrent workers cannot alias. `partner`
        // is read-only; `frame` reads and writes the same pixel but only within one row's
        // worker, so there is no cross-thread read-after-write on any pixel.
        IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                int framePixel = frame.getPixel(x, y);
                int partnerPixel = partner.getPixel(x, y);
                int a = Math.clamp((int) (ColorMath.alpha(framePixel) * alpha + ColorMath.alpha(partnerPixel) * invAlpha + 0.5f), 0, 255);
                int r = Math.clamp((int) (ColorMath.red(framePixel)   * alpha + ColorMath.red(partnerPixel)   * invAlpha + 0.5f), 0, 255);
                int g = Math.clamp((int) (ColorMath.green(framePixel) * alpha + ColorMath.green(partnerPixel) * invAlpha + 0.5f), 0, 255);
                int b = Math.clamp((int) (ColorMath.blue(framePixel)  * alpha + ColorMath.blue(partnerPixel)  * invAlpha + 0.5f), 0, 255);
                frame.setPixel(x, y, ColorMath.pack(a, r, g, b));
            }
        });
    }

    /**
     * Trims a frame list to {@code outputCount} entries, disposing the extra bridge frames baked
     * only for the crossfade partner role.
     */
    private static void trimBridgeFrames(
        @NotNull ConcurrentList<PixelBuffer> frames,
        int outputCount
    ) {
        while (frames.size() > outputCount)
            frames.removeLast();
    }

    /**
     * Assembles a portal render. A single static frame at {@link AnimationOptions#getStartTick()} when
     * {@code frameCount <= 1}, otherwise a seamless-loop strip: it bakes {@code frameCount + bridge}
     * frames through the game-time schedule and applies a finish step that
     * {@link #applyBridgeCrossfade crossfades} the loop seam and trims the bridge frames. Both
     * sub-renderers share this loop, differing only in {@code raster}.
     *
     * @param options the render options supplying animation timing
     * @param ssaa the supersample factor for the raster tail ({@code 1} for the flat 2D path)
     * @param antiAlias whether the raster tail applies FXAA
     * @param raster draws one portal frame at an instant - possibly between ticks - into the target buffer
     * @return the finished static frame or animation strip
     */
    private static @NotNull ImageData renderAnimated(
        @NotNull PortalOptions options,
        int ssaa,
        boolean antiAlias,
        @NotNull RasterPass.ContinuousRasterizer raster
    ) {
        int size = options.getOutput().getCanvasSize();
        int startTick = options.getAnimation().getStartTick();
        int ticksPerFrame = options.getAnimation().getTicksPerFrame();
        int outputCount = options.getAnimation().getFrameCount();
        int subTickSteps = Math.max(1, options.getAnimation().getSubTickSteps());
        if (outputCount <= 1)
            return Timeline.gameTime(startTick, outputCount, ticksPerFrame, subTickSteps)
                .bake(RasterPass.of(size, size, ssaa, antiAlias, raster));

        int bridge = bridgeFrameCount(options);
        // The crossfade and the trim count baked frames, and subdividing multiplies those, so both
        // bounds scale with it - the seam then spans the same stretch of game time it always did.
        int bakedCount = outputCount * subTickSteps;
        int bakedBridge = bridge * subTickSteps;
        return Timeline.gameTime(startTick, outputCount + bridge, ticksPerFrame, subTickSteps)
            .bake(RasterPass.of(size, size, ssaa, antiAlias, raster).finishing(
                (frames, timeline) -> {
                    applyBridgeCrossfade(frames, bakedCount, bakedBridge);
                    trimBridgeFrames(frames, bakedCount);
                    return new RasterPass.Finish.Result(frames, timeline);
                }));
    }

    /**
     * Full 3D isometric portal renderer. Builds geometry via {@link BlockGeometryKit} and rasterizes
     * through {@link Projection#VANILLA_ISO}'s standard {@code [30, 225, 0]} pose by default. {@code END_GATEWAY}
     * renders as a unit cube with the baked face on all 6 sides; {@code END_PORTAL} renders as a
     * slab from {@code y = 0.375} to {@code y = 0.75} matching vanilla's
     * {@code TheEndPortalRenderer.BOTTOM} / {@code .TOP}.
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<PortalOptions> {

        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull PortalOptions options) {
            int ssaa = Math.max(1, options.getOutput().getSupersample());
            return renderAnimated(options, ssaa, options.getOutput().isAntiAlias(),
                (target, tick, age) -> rasterizeFrame(options, age, target));
        }

        /**
         * Draws one 3D isometric portal frame at the given game tick into {@code target}: resolves the
         * projection, bakes the parallax shader once at the target (raster) resolution as a screen-space
         * canvas, rasterizes the cube / slab with a white sampler to capture per-face shading, then
         * composes shader &times; shading into {@code target}. The shared {@link RasterPass} tail owns the
         * supersample / FXAA / downscale around this draw, so {@code target} is the hi-res buffer when
         * supersampling.
         *
         * @param options the render options
         * @param tick the vanilla game tick driving the shader's {@code GameTime}
         * @param target the buffer to draw the frame into
         */
        private void rasterizeFrame(@NotNull PortalOptions options, float tick, @NotNull PixelBuffer target) {
            // Resolve the projection once: the caller's rotation is composed onto the base pose, so it
            // poses the camera directly and the rasterize call applies no separate model-spin. Default
            // renders pass EulerRotation.NONE, leaving the base block-icon pose.
            var resolved = options.getOutput().getProjection().resolve(options.getOutput().getRotation(), options.getOutput().getFacing());
            ModelEngine engine = new ModelEngine(this.context, resolved.camera());
            PixelBuffer endSky = engine.textures().resolveTexture(END_SKY_TEXTURE_ID);
            PixelBuffer endPortalNoise = engine.textures().resolveTexture(END_PORTAL_NOISE_TEXTURE_ID);

            // Pass 1: bake the parallax shader once at the target (raster) resolution. This is the
            // "screen-space canvas" - every output pixel that lands on the cube samples this single
            // buffer at its own screen position, not a per-face UV, so adjacent cube edges converge
            // on identical shader output and the starfield is seamless across faces.
            PixelBuffer shaderCanvas = applyTintIfNeeded(
                bakeFace(options.getPortal(), tick, endSky, endPortalNoise, target.width()), resolveTint(options));

            // Pass 2: rasterize the cube with a uniform-white sampler so each pixel's red channel is
            // the per-face shading coefficient * 255, then compose shader * mask into the target. The
            // shading mask is scope-local pooled scratch.
            PixelBuffer white = PixelBuffer.create(1, 1);
            white.setPixel(0, 0, ColorMath.WHITE);
            ConcurrentList<VisibleTriangle> triangles = buildGeometry(options.getPortal(), FaceTextures.uniform(white));
            try (PixelBufferPool.Lease maskLease = PixelBufferPool.acquire(target.width(), target.height())) {
                PixelBuffer shadingMask = maskLease.buffer();
                engine.rasterize(triangles, shadingMask);
                composeShaderMask(target, shadingMask, shaderCanvas, target.width());
            }
        }

        /**
         * Composes the final portal pixel: for every opaque mask pixel, scales the
         * shader-canvas RGB by the mask's red channel (which carries the per-face shading
         * factor from the white-texture rasterize pass) and writes it to {@code out} with
         * the mask's alpha preserved. Background (transparent mask) stays untouched so the
         * hex silhouette reads cleanly on the output PNG.
         */
        private static void composeShaderMask(@NotNull PixelBuffer out, @NotNull PixelBuffer shadingMask, @NotNull PixelBuffer shaderCanvas, int hiRes) {
            for (int y = 0; y < hiRes; y++) {
                for (int x = 0; x < hiRes; x++) {
                    int mask = shadingMask.getPixel(x, y);
                    int maskAlpha = ColorMath.alpha(mask);
                    if (maskAlpha == 0) continue;

                    int shaderPixel = shaderCanvas.getPixel(x, y);
                    float factor = ColorMath.red(mask) / 255f;
                    int r = Math.clamp((int) (ColorMath.red(shaderPixel)   * factor + 0.5f), 0, 255);
                    int g = Math.clamp((int) (ColorMath.green(shaderPixel) * factor + 0.5f), 0, 255);
                    int b = Math.clamp((int) (ColorMath.blue(shaderPixel)  * factor + 0.5f), 0, 255);
                    out.setPixel(x, y, ColorMath.pack(maskAlpha, r, g, b));
                }
            }
        }

        /**
         * Builds the per-portal geometry. END_GATEWAY is a unit cube; END_PORTAL is a thin slab
         * sitting inside the unit cube at vanilla's {@code TheEndPortalRenderer.BOTTOM} /
         * {@code .TOP}. Both share the same per-face sprite.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> buildGeometry(
            @NotNull PortalOptions.Portal portal,
            @NotNull FaceTextures faces
        ) {
            if (portal == PortalOptions.Portal.END_GATEWAY)
                return BlockGeometryKit.unitCube(faces, ColorMath.WHITE);

            // End portal slab: x and z span the full unit range, y clipped to vanilla's [BOTTOM, TOP].
            // Model space is [-0.5, +0.5] per axis (see GeometryKit.unitCube), so the slab's Y
            // offsets are measured from the cube's centre.
            return BlockGeometryKit.buildBox(
                new Box(-0.5f, END_PORTAL_SLAB_BOTTOM_Y - 0.5f, -0.5f, 0.5f, END_PORTAL_SLAB_TOP_Y - 0.5f, 0.5f),
                faces,
                ColorMath.WHITE
            );
        }

    }

    /**
     * Flat top-down portal-face renderer. Bakes a single parallax sprite at the requested output
     * size and blits it straight into an output buffer - the view the atlas uses for a portal
     * tile, and the view a caller would use for an inventory icon if portals were holdable.
     */
    @RequiredArgsConstructor
    public static final class PortalFace2D implements Renderer<PortalOptions> {

        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull PortalOptions options) {
            // Flat 2D bake: no supersample / FXAA (ssaa = 1, antiAlias = false), matching FluidFace2D.
            return renderAnimated(options, 1, false,
                (target, tick, age) -> rasterizeFrame(options, age, target));
        }

        /**
         * Draws one flat portal-face frame at the given game tick into {@code target}: bakes the
         * parallax sprite at the target size, applies the optional tint override, and blits it into
         * {@code target}.
         *
         * @param options the render options
         * @param tick the vanilla game tick driving the shader's {@code GameTime}
         * @param target the buffer to draw the frame into
         */
        private void rasterizeFrame(@NotNull PortalOptions options, float tick, @NotNull PixelBuffer target) {
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer endSky = engine.textures().resolveTexture(END_SKY_TEXTURE_ID);
            PixelBuffer endPortalNoise = engine.textures().resolveTexture(END_PORTAL_NOISE_TEXTURE_ID);

            PixelBuffer baked = applyTintIfNeeded(
                bakeFace(options.getPortal(), tick, endSky, endPortalNoise, target.width()), resolveTint(options));
            target.blitScaled(baked, 0, 0, target.width(), target.height());
        }

    }

}
