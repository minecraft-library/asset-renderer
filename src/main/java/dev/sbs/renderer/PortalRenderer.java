package dev.sbs.renderer;

import dev.sbs.renderer.engine.IsometricEngine;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.kit.GeometryKit;
import dev.sbs.renderer.options.PortalOptions;
import dev.sbs.renderer.tensor.Vector3f;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Renders vanilla end portal and end gateway blocks by CPU-baking the same parallax star-field
 * shader vanilla ships in {@code assets/minecraft/shaders/core/rendertype_end_portal.fsh}. Both
 * portals share that shader - {@link net.minecraft.client.renderer.RenderPipelines} differs only
 * in the {@code PORTAL_LAYERS} define (15 for end_portal, 16 for end_gateway) - so one renderer
 * covers both variants via a {@link PortalOptions.Portal} parameter.
 * <p>
 * Two sub-renderers are exposed, matching the {@link FluidRenderer} shape:
 * <ul>
 * <li>{@link Isometric3D} - builds geometry via {@link GeometryKit} (full unit cube for
 * {@link PortalOptions.Portal#END_GATEWAY}, thin slab at vanilla's {@code BOTTOM=0.375}/
 * {@code TOP=0.75} for {@link PortalOptions.Portal#END_PORTAL}) and rasterizes through the
 * standard {@code [30, 225, 0]} isometric pose.</li>
 * <li>{@link PortalFace2D} - bakes a single top-face sprite and blits it flat - the atlas tile
 * path and the view a caller would use for an inventory icon.</li>
 * </ul>
 * The parallax loop is transcribed verbatim from the {@code .fsh} (including the {@code COLORS}
 * table, {@code SCALE_TRANSLATE} / per-layer {@code translate} / {@code scale*rotate} matrices,
 * and the {@code textureProj}-style divide-by-w). Animation is driven by the shader's
 * {@code GameTime} uniform, fed in from {@link PortalOptions#getStartTick()} and advancing by
 * {@link PortalOptions#getTicksPerFrame()} per output frame; static renders use {@code time = 0}.
 * <p>
 * Scene-aware concerns (fog, additive translucent blending against the underlying block, view-
 * dependent parallax from the observer's camera) are deliberately out of scope - the bake treats
 * each face's own {@code (u, v)} as screen space so the result is camera-independent and
 * identical per tile.
 */
public final class PortalRenderer implements Renderer<PortalOptions> {

    /** Resource id of {@code assets/minecraft/textures/environment/end_sky.png} (Sampler0 in the vanilla shader). */
    static final @NotNull String END_SKY_TEXTURE_ID = "minecraft:environment/end_sky";

    /** Resource id of {@code assets/minecraft/textures/entity/end_portal/end_portal.png} (Sampler1 in the vanilla shader). */
    static final @NotNull String END_PORTAL_NOISE_TEXTURE_ID = "minecraft:entity/end_portal/end_portal";

    /**
     * Fixed real-time playback delay per output frame, in milliseconds. {@code 50ms = 20 FPS}.
     * Decoupled from {@link PortalOptions#getTicksPerFrame()} so the animation speed knob
     * doesn't stretch the GIF's wall-clock playback length; the shader's {@code GameTime} is a
     * continuous day-cycle fraction so coupling sim rate to playback rate has no natural
     * semantics (unlike {@link FluidRenderer}'s tick-aligned animation strip).
     */
    private static final int FRAME_DELAY_MS = 50;

    /**
     * Vanilla's {@code GameTime} uniform period - one day cycle in ticks. Matches
     * {@code GlobalSettingsUniform.update}'s {@code (gameTick % 24000L + partialTick) / 24000f}
     * bytecode at offsets 97-116.
     */
    private static final int GAME_TIME_PERIOD_TICKS = 24000;

    /** {@code PORTAL_LAYERS} for {@link PortalOptions.Portal#END_PORTAL} (from {@code RenderPipelines.END_PORTAL}). */
    private static final int LAYER_COUNT_END_PORTAL = 15;

    /** {@code PORTAL_LAYERS} for {@link PortalOptions.Portal#END_GATEWAY} (from {@code RenderPipelines.END_GATEWAY}). */
    private static final int LAYER_COUNT_END_GATEWAY = 16;

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

    /** End portal slab bottom Y in unit-cube model space. Matches {@code TheEndPortalRenderer.BOTTOM}. */
    private static final float END_PORTAL_SLAB_BOTTOM_Y = 0.375f;

    /** End portal slab top Y in unit-cube model space. Matches {@code TheEndPortalRenderer.TOP}. */
    private static final float END_PORTAL_SLAB_TOP_Y = 0.75f;

    private final @NotNull Isometric3D isometric3D;
    private final @NotNull PortalFace2D portalFace2D;

    public PortalRenderer(@NotNull RendererContext context) {
        this.isometric3D = new Isometric3D(context);
        this.portalFace2D = new PortalFace2D(context);
    }

    @Override
    public @NotNull ImageData render(@NotNull PortalOptions options) {
        return switch (options.getType()) {
            case ISOMETRIC_3D -> this.isometric3D.render(options);
            case PORTAL_FACE_2D -> this.portalFace2D.render(options);
        };
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
     * Bakes one face of parallax star-field output at the given game-time tick into a fresh
     * pixel buffer, matching the body of {@code rendertype_end_portal.fsh} per-pixel.
     * <p>
     * The shader samples {@code Sampler0} (end_sky) scaled by {@code COLORS[0]}, then accumulates
     * {@code PORTAL_LAYERS} further samples of {@code Sampler1} (end_portal noise) through
     * per-layer transforms composed in {@code end_portal_layer(float layer)}. Fog and alpha are
     * dropped; the atlas tile is opaque.
     *
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
        int gameTick,
        @NotNull PixelBuffer endSky,
        @NotNull PixelBuffer endPortalNoise,
        int size
    ) {
        float time = Math.floorMod(gameTick, GAME_TIME_PERIOD_TICKS) / (float) GAME_TIME_PERIOD_TICKS;
        int layers = layerCount(portal);
        PixelBuffer buffer = PixelBuffer.create(size, size);
        float invSize = 1f / size;

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                // Input texProj0 treats the face's own (u, v) as screen space, matching the
                // divide-by-w semantics of textureProj with w = 1.
                float u = (px + 0.5f) * invSize;
                float v = (py + 0.5f) * invSize;

                // Base layer: Sampler0 * COLORS[0].
                float rAcc = 0f, gAcc = 0f, bAcc = 0f;
                int baseArgb = sampleRepeat(endSky, u, v);
                rAcc += (ColorMath.red(baseArgb)   / 255f) * COLORS[0][0];
                gAcc += (ColorMath.green(baseArgb) / 255f) * COLORS[0][1];
                bAcc += (ColorMath.blue(baseArgb)  / 255f) * COLORS[0][2];

                // Parallax layers: Sampler1 * COLORS[i], transformed by end_portal_layer(i+1).
                for (int i = 0; i < layers; i++) {
                    float layer = i + 1;
                    float[] uv = endPortalLayerUv(u, v, layer, time);
                    int sampled = sampleRepeat(endPortalNoise, uv[0], uv[1]);
                    rAcc += (ColorMath.red(sampled)   / 255f) * COLORS[i][0];
                    gAcc += (ColorMath.green(sampled) / 255f) * COLORS[i][1];
                    bAcc += (ColorMath.blue(sampled)  / 255f) * COLORS[i][2];
                }

                int r = Math.clamp((int) (rAcc * 255f + 0.5f), 0, 255);
                int g = Math.clamp((int) (gAcc * 255f + 0.5f), 0, 255);
                int b = Math.clamp((int) (bAcc * 255f + 0.5f), 0, 255);
                buffer.setPixel(px, py, ColorMath.pack(0xFF, r, g, b));
            }
        }

        return buffer;
    }

    /**
     * Computes the sample UV for one parallax layer, matching vanilla's
     * {@code texProj0 * end_portal_layer(layer)} expression from {@code rendertype_end_portal.fsh}
     * under the {@code v * M} row-vector convention GLSL uses for {@code vec * mat}.
     * <p>
     * GLSL stores {@code mat4(a, b, c, d, e, f, g, h, ...)} column-major: the first four
     * constants form column 0. So the translation values Mojang writes in the fourth position
     * of each source-code line ({@code 0.25} in {@code SCALE_TRANSLATE}, {@code 17/layer} / {@code ty}
     * in {@code translate}) land in row 3 of the standard-form matrix - the correct spot for
     * {@code v * M} translation. Combined with {@code v * A * B * C = ((v * A) * B) * C}, the
     * layer transform applies in order: {@code mat4(scale * rotate)}, then {@code translate},
     * then {@code SCALE_TRANSLATE}.
     *
     * @param u the face-space u coordinate in {@code [0, 1]}
     * @param v the face-space v coordinate in {@code [0, 1]}
     * @param layer the {@code float} layer index, starting at {@code 1.0}
     * @param time the {@code GameTime} uniform value - a {@code [0, 1)} day-cycle fraction
     * @return {@code {outU, outV}} - sample UV into the noise texture (values may fall outside
     *         {@code [0, 1]}; the caller applies GL_REPEAT wrapping)
     */
    private static float[] endPortalLayerUv(float u, float v, float layer, float time) {
        // Step 1: (u, v, 0, 1) * mat4(scale * rotate). rotate literal mat2(cos, -sin, sin, cos)
        // fills columns (cos, -sin) + (sin, cos), giving standard-form rotate = | cos   sin |
        //                                                                       | -sin  cos |
        // scale (diagonal mat2(s)) times rotate = | s*cos    s*sin  |
        //                                         | -s*sin   s*cos  |
        // For v * M: (v*M)[col] = sum_k v[k] * M[k][col]
        //   (v*M)[0] = u * s*cos + v * (-s*sin) = s * (cos*u - sin*v)
        //   (v*M)[1] = u * s*sin + v *  s*cos   = s * (sin*u + cos*v)
        //   (v*M)[3] = 1 (identity passthrough on w)
        float s = (4.5f - layer / 4f) * 2f;
        float angle = (float) Math.toRadians((layer * layer * 4321f + layer * 9f) * 2f);
        float c = (float) Math.cos(angle);
        float si = (float) Math.sin(angle);
        float t1x = s * (c * u - si * v);
        float t1y = s * (si * u + c * v);
        float t1w = 1f;

        // Step 2: t1 * translate. Mojang writes 17/L and ty in the fourth position of columns
        // 0 and 1; column-fill puts them in row 3 of the standard matrix:
        //   | 1     0    0   0 |
        //   | 0     1    0   0 |
        //   | 0     0    1   0 |
        //   | 17/L  ty   0   1 |
        // For v * M:
        //   (v*M)[0] = t1x + t1w * (17/L)
        //   (v*M)[1] = t1y + t1w * ty
        //   (v*M)[3] = t1w  (identity w passthrough)
        float tx = 17f / layer;
        float ty = (2f + layer / 1.5f) * (time * 1.5f);
        float t2x = t1x + t1w * tx;
        float t2y = t1y + t1w * ty;
        float t2w = t1w;

        // Step 3: t2 * SCALE_TRANSLATE. Same column-fill convention puts 0.25s in row 3:
        //   | 0.5    0     0  0 |
        //   | 0      0.5   0  0 |
        //   | 0      0     1  0 |
        //   | 0.25   0.25  0  1 |
        // For v * M:
        //   (v*M)[0] = 0.5 * t2x + 0.25 * t2w
        //   (v*M)[1] = 0.5 * t2y + 0.25 * t2w
        //   (v*M)[3] = t2w
        float t3x = 0.5f * t2x + 0.25f * t2w;
        float t3y = 0.5f * t2y + 0.25f * t2w;
        float t3w = t2w;

        // textureProj: sample at (t3x / t3w, t3y / t3w). With unit-w input t3w = 1 throughout
        // (all three matrices have identity on w), so the divide is trivial; guarded for safety.
        float inv = t3w == 0f ? 1f : 1f / t3w;
        return new float[] { t3x * inv, t3y * inv };
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
     * Full 3D isometric portal renderer. Builds geometry via {@link GeometryKit} and rasterizes
     * through {@link IsometricEngine}'s standard {@code [30, 225, 0]} pose. {@code END_GATEWAY}
     * renders as a unit cube with the baked face on all 6 sides; {@code END_PORTAL} renders as a
     * slab from {@code y = 0.375} to {@code y = 0.75} matching vanilla's
     * {@code TheEndPortalRenderer.BOTTOM} / {@code .TOP}.
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<PortalOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull PortalOptions options) {
            if (options.getFrameCount() <= 1)
                return RenderEngine.staticFrame(renderFrame(options, options.getStartTick()));

            ConcurrentList<PixelBuffer> frames = Concurrent.newList();
            for (int f = 0; f < options.getFrameCount(); f++) {
                int tick = options.getStartTick() + f * options.getTicksPerFrame();
                frames.add(renderFrame(options, tick));
            }
            return RenderEngine.output(frames, FRAME_DELAY_MS);
        }

        private @NotNull PixelBuffer renderFrame(@NotNull PortalOptions options, int tick) {
            IsometricEngine engine = IsometricEngine.standard(this.context);
            TextureEngine textures = new TextureEngine(this.context);
            PixelBuffer endSky = textures.resolveTexture(END_SKY_TEXTURE_ID);
            PixelBuffer endPortalNoise = textures.resolveTexture(END_PORTAL_NOISE_TEXTURE_ID);

            int ssaa = Math.max(1, options.getSupersample());
            int hiRes = options.getOutputSize() * ssaa;
            int faceSize = Math.max(16, hiRes);

            PixelBuffer baked = bakeFace(options.getPortal(), tick, endSky, endPortalNoise, faceSize);
            baked = applyTintIfNeeded(baked, resolveTint(options));

            PixelBuffer[] faces = new PixelBuffer[] { baked, baked, baked, baked, baked, baked };
            ConcurrentList<VisibleTriangle> triangles = buildGeometry(options.getPortal(), faces);

            PixelBuffer buffer = PixelBuffer.create(hiRes, hiRes);
            engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, options.getRotation());

            if (options.isAntiAlias())
                buffer.applyFxaa();

            if (ssaa > 1) {
                PixelBuffer output = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
                output.blitScaled(buffer, 0, 0, options.getOutputSize(), options.getOutputSize());
                return output;
            }

            return buffer;
        }

        /**
         * Builds the per-portal geometry. END_GATEWAY is a unit cube; END_PORTAL is a thin slab
         * sitting inside the unit cube at vanilla's {@code TheEndPortalRenderer.BOTTOM} /
         * {@code .TOP}. Both share the same per-face sprite.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> buildGeometry(
            @NotNull PortalOptions.Portal portal,
            @NotNull PixelBuffer @NotNull [] faces
        ) {
            if (portal == PortalOptions.Portal.END_GATEWAY)
                return GeometryKit.unitCube(faces, ColorMath.WHITE);

            // End portal slab: x and z span the full unit range, y clipped to vanilla's [BOTTOM, TOP].
            // Model space is [-0.5, +0.5] per axis (see GeometryKit.unitCube), so the slab's Y
            // offsets are measured from the cube's centre.
            return GeometryKit.box(
                new Vector3f(-0.5f, END_PORTAL_SLAB_BOTTOM_Y - 0.5f, -0.5f),
                new Vector3f(0.5f, END_PORTAL_SLAB_TOP_Y - 0.5f, 0.5f),
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

        @Override
        public @NotNull ImageData render(@NotNull PortalOptions options) {
            if (options.getFrameCount() <= 1)
                return RenderEngine.staticFrame(renderFrame(options, options.getStartTick()));

            ConcurrentList<PixelBuffer> frames = Concurrent.newList();
            for (int f = 0; f < options.getFrameCount(); f++) {
                int tick = options.getStartTick() + f * options.getTicksPerFrame();
                frames.add(renderFrame(options, tick));
            }
            return RenderEngine.output(frames, FRAME_DELAY_MS);
        }

        private @NotNull PixelBuffer renderFrame(@NotNull PortalOptions options, int tick) {
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer endSky = engine.resolveTexture(END_SKY_TEXTURE_ID);
            PixelBuffer endPortalNoise = engine.resolveTexture(END_PORTAL_NOISE_TEXTURE_ID);

            PixelBuffer baked = bakeFace(options.getPortal(), tick, endSky, endPortalNoise, options.getOutputSize());
            return applyTintIfNeeded(baked, resolveTint(options));
        }

    }

}
