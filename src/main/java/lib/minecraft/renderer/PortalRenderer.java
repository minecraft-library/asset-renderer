package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.bake.texture.PortalBake;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.AnimationOptions;
import lib.minecraft.renderer.request.PortalOptions;
import lib.minecraft.renderer.vanilla.PortalPalette;
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
 * The parallax loop lives in {@link PortalBake}, transcribed verbatim from the {@code .fsh} over the
 * {@link PortalPalette} table. Animation is driven by the shader's
 * {@code GameTime} uniform, fed in from {@link AnimationOptions#getStartTick()} and advancing by
 * {@link AnimationOptions#getTicksPerFrame()} per output frame; static renders use {@code time = 0}.
 * <p>
 * Scene-aware concerns (fog, additive translucent blending against the underlying block, view-
 * dependent parallax from the observer's camera) are deliberately out of scope - the bake treats
 * each face's own {@code (u, v)} as screen space so the result is camera-independent and
 * identical per tile.
 */
public final class PortalRenderer implements Renderer<PortalOptions> {

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
        float bridge = options.getLoopFadeBridgePct();
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
        int subTickSteps = Math.max(1, options.getSubTickSteps());
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
     * One of the shader's source textures, refusing one no pack supplies - the portal draws nothing
     * without both.
     *
     * @param context the context the texture resolves through
     * @param textureId the namespaced texture id
     * @return the texture
     * @throws RenderException if no pack supplies the texture
     */
    private static @NotNull PixelBuffer requireTexture(@NotNull RendererContext context, @NotNull String textureId) {
        return context.resolveTexture(textureId)
            .orElseThrow(() -> new RenderException("No texture registered for id '%s'", textureId));
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
            int ssaa = options.getOutput().getSupersample();
            Scene scene = Scene.of(this.context, options);
            return renderAnimated(options, ssaa, options.getOutput().isAntiAlias(),
                (target, tick, age) -> rasterizeFrame(options, scene, age, target));
        }

        /**
         * The half of a portal render that does not vary between its frames: the engine posed for the
         * whole animation, the two parallax source textures and the cube / slab geometry. Each is a
         * function of the render options alone, so one scene is resolved in {@link #render} and
         * captured by the per-frame callback, leaving that callback holding only the parallax bake -
         * the sole reader of the tick.
         *
         * @param engine the engine posed by the caller's projection
         * @param endSky {@code Sampler0} - {@code environment/end_sky}
         * @param endPortalNoise {@code Sampler1} - {@code entity/end_portal/end_portal}
         * @param triangles the cube or slab geometry, sampled from a uniform-white texture
         */
        private record Scene(
            @NotNull Rasterizer engine,
            @NotNull PixelBuffer endSky,
            @NotNull PixelBuffer endPortalNoise,
            @NotNull ConcurrentList<VisibleTriangle> triangles
        ) {

            /**
             * Resolves the scene every frame of one render draws from. The projection resolve composes
             * the caller's rotation onto the base pose, so it poses the camera directly and the
             * rasterize call applies no separate model-spin; default renders pass
             * {@code EulerRotation.NONE}, leaving the base block-icon pose. The white sampler is
             * written once here and only read afterwards, so one instance serves every frame.
             *
             * @param context the renderer context textures resolve through
             * @param options the render options
             * @return the scene the render's frames share
             */
            static @NotNull Scene of(@NotNull RendererContext context, @NotNull PortalOptions options) {
                var resolved = options.getOutput().getProjection().resolve(
                    options.getOutput().getRotation(), options.getOutput().getFacing());
                Rasterizer engine = new Rasterizer(resolved.camera());

                PixelBuffer white = PixelBuffer.create(1, 1);
                white.setPixel(0, 0, ColorMath.WHITE);

                return new Scene(
                    engine,
                    requireTexture(context, PortalPalette.END_SKY_TEXTURE_ID),
                    requireTexture(context, PortalPalette.END_PORTAL_NOISE_TEXTURE_ID),
                    buildGeometry(options.getPortal(), FaceTextures.uniform(white)));
            }

        }

        /**
         * Draws one 3D isometric portal frame at the given game tick into {@code target}: bakes the
         * parallax shader at the target (raster) resolution as a screen-space canvas, rasterizes the
         * scene's cube / slab with its white sampler to capture per-face shading, then composes
         * shader &times; shading into {@code target}. The shared {@link RasterPass} tail owns the
         * supersample / FXAA / downscale around this draw, so {@code target} is the hi-res buffer when
         * supersampling.
         *
         * @param options the render options
         * @param scene the frame-invariant state resolved once per render
         * @param tick the vanilla game tick driving the shader's {@code GameTime}
         * @param target the buffer to draw the frame into
         */
        private static void rasterizeFrame(
            @NotNull PortalOptions options,
            @NotNull Scene scene,
            float tick,
            @NotNull PixelBuffer target
        ) {
            // Pass 1: bake the parallax shader once at the target (raster) resolution. This is the
            // "screen-space canvas" - every output pixel that lands on the cube samples this single
            // buffer at its own screen position, not a per-face UV, so adjacent cube edges converge
            // on identical shader output and the starfield is seamless across faces.
            PixelBuffer shaderCanvas = applyTintIfNeeded(
                PortalBake.bakeFace(options.getPortal(), tick, scene.endSky(), scene.endPortalNoise(), target.width()), resolveTint(options));

            // Pass 2: rasterize the cube with a uniform-white sampler so each pixel's red channel is
            // the per-face shading coefficient * 255, then compose shader * mask into the target. The
            // shading mask is scope-local pooled scratch.
            try (PixelBufferPool.Lease maskLease = PixelBufferPool.acquire(target.width(), target.height())) {
                PixelBuffer shadingMask = maskLease.buffer();
                scene.engine().rasterize(scene.triangles(), shadingMask);
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
                return BoxKit.unitCube(faces, ColorMath.WHITE);

            // End portal slab: x and z span the full unit range, y clipped to vanilla's [BOTTOM, TOP].
            // Model space is [-0.5, +0.5] per axis (see BoxKit.unitCube), so the slab's Y
            // offsets are measured from the cube's centre.
            return BoxKit.buildBox(
                new Box(-0.5f, PortalPalette.END_PORTAL_SLAB_BOTTOM_Y - 0.5f, -0.5f, 0.5f, PortalPalette.END_PORTAL_SLAB_TOP_Y - 0.5f, 0.5f),
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
            Scene scene = Scene.of(this.context);
            return renderAnimated(options, 1, false,
                (target, tick, age) -> rasterizeFrame(options, scene, age, target));
        }

        /**
         * The half of a flat portal render that does not vary between its frames: the two parallax
         * source textures, neither a function of the tick. Resolved once in {@link #render} and
         * captured by the per-frame callback, leaving that callback holding only the parallax bake.
         *
         * @param endSky {@code Sampler0} - {@code environment/end_sky}
         * @param endPortalNoise {@code Sampler1} - {@code entity/end_portal/end_portal}
         */
        private record Scene(@NotNull PixelBuffer endSky, @NotNull PixelBuffer endPortalNoise) {

            /**
             * Resolves the scene every frame of one render draws from.
             *
             * @param context the renderer context textures resolve through
             * @return the scene the render's frames share
             */
            static @NotNull Scene of(@NotNull RendererContext context) {
                return new Scene(
                    requireTexture(context, PortalPalette.END_SKY_TEXTURE_ID),
                    requireTexture(context, PortalPalette.END_PORTAL_NOISE_TEXTURE_ID));
            }

        }

        /**
         * Draws one flat portal-face frame at the given game tick into {@code target}: bakes the
         * parallax sprite at the target size, applies the optional tint override, and blits it into
         * {@code target}.
         *
         * @param options the render options
         * @param scene the frame-invariant state resolved once per render
         * @param tick the vanilla game tick driving the shader's {@code GameTime}
         * @param target the buffer to draw the frame into
         */
        private static void rasterizeFrame(
            @NotNull PortalOptions options,
            @NotNull Scene scene,
            float tick,
            @NotNull PixelBuffer target
        ) {
            PixelBuffer baked = applyTintIfNeeded(
                PortalBake.bakeFace(options.getPortal(), tick, scene.endSky(), scene.endPortalNoise(), target.width()), resolveTint(options));
            target.blitScaled(baked, 0, 0, target.width(), target.height());
        }

    }

}
