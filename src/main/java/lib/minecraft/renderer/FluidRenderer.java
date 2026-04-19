package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.TextureEngine;
import lib.minecraft.renderer.geometry.Biome;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.FluidGeometryKit;
import lib.minecraft.renderer.options.FluidOptions;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.stream.IntStream;

/**
 * Renders vanilla fluids (water, lava) as either a full 3D isometric cube or a flat top-down
 * source-face icon by dispatching to one of two sub-renderers based on {@link FluidOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;FluidOptions&gt;}:
 * <ul>
 * <li>{@link Isometric3D} uses an {@link IsometricEngine} in its {@code standard} pose - fluids
 * carry no {@code display.gui} transform of their own - and builds a 1x1x1 cube via
 * {@link FluidGeometryKit}. Sloped tops, flow-UV rotation, and animation are all supported
 * through the options object.</li>
 * <li>{@link FluidFace2D} blits the still texture as a flat tinted quad - the view a caller would
 * use if fluids were holdable as inventory items.</li>
 * </ul>
 * Shared texture ids, the vanilla default water ARGB, and the biome / override tint resolver live
 * as package-private static helpers on this class so both sub-renderers can reach them without
 * duplicating logic.
 * <p>
 * Scene-aware concerns - neighbor-based corner-height interpolation, flow-direction derivation,
 * bottom-face culling, water-overlay sides against transparent neighbors - are deliberately out
 * of scope. {@link FluidOptions} accepts precomputed values so the renderer stays scene-agnostic.
 */
public final class FluidRenderer implements Renderer<FluidOptions> {

    static final @NotNull String WATER_STILL_TEXTURE_ID = "minecraft:block/water_still";
    static final @NotNull String WATER_FLOW_TEXTURE_ID = "minecraft:block/water_flow";
    static final @NotNull String LAVA_STILL_TEXTURE_ID = "minecraft:block/lava_still";
    static final @NotNull String LAVA_FLOW_TEXTURE_ID = "minecraft:block/lava_flow";

    /** One vanilla tick is 50 ms - used to convert {@code ticksPerFrame} into a GIF delay. */
    private static final int MILLIS_PER_TICK = 50;

    private final @NotNull Isometric3D isometric3D;
    private final @NotNull FluidFace2D fluidFace2D;

    public FluidRenderer(@NotNull RendererContext context) {
        this.isometric3D = new Isometric3D(context);
        this.fluidFace2D = new FluidFace2D(context);
    }

    @Override
    public @NotNull ImageData render(@NotNull FluidOptions options) {
        return switch (options.getType()) {
            case ISOMETRIC_3D -> this.isometric3D.render(options);
            case FLUID_FACE_2D -> this.fluidFace2D.render(options);
        };
    }

    /**
     * Returns the still-frame texture id for the given fluid.
     *
     * @param fluid the fluid variant
     * @return the namespaced still texture id
     */
    static @NotNull String stillTextureId(@NotNull FluidOptions.Fluid fluid) {
        return fluid == FluidOptions.Fluid.WATER ? WATER_STILL_TEXTURE_ID : LAVA_STILL_TEXTURE_ID;
    }

    /**
     * Returns the flow-frame texture id for the given fluid.
     *
     * @param fluid the fluid variant
     * @return the namespaced flow texture id
     */
    static @NotNull String flowTextureId(@NotNull FluidOptions.Fluid fluid) {
        return fluid == FluidOptions.Fluid.WATER ? WATER_FLOW_TEXTURE_ID : LAVA_FLOW_TEXTURE_ID;
    }

    /**
     * Resolves the ARGB tint applied to fluid geometry.
     * <p>
     * Lava is never tinted - it returns {@link ColorMath#WHITE}. Water consults, in priority
     * order: the caller-supplied {@link FluidOptions#getWaterTintArgbOverride()}, then the
     * biome's water tint via {@link TextureEngine#sampleBiomeTint} using
     * {@link Biome.TintTarget#WATER} (which falls back to the engine-level default when the
     * biome carries no {@code water_color} override).
     *
     * @param context the renderer context
     * @param options the fluid options
     * @return the ARGB tint
     */
    static int resolveFluidTint(@NotNull RendererContext context, @NotNull FluidOptions options) {
        if (options.getFluid() == FluidOptions.Fluid.LAVA)
            return ColorMath.WHITE;
        if (options.getWaterTintArgbOverride() != null)
            return options.getWaterTintArgbOverride();
        return new TextureEngine(context).sampleBiomeTint(Biome.TintTarget.WATER, options.getBiome());
    }

    /**
     * Full 3D isometric fluid cube renderer. Builds triangles via {@link FluidGeometryKit}, then
     * rasterizes through {@link IsometricEngine}'s standard {@code [30, 225, 0]} pose.
     * Animation is driven by {@link FluidOptions#getFrameCount()} - single-frame renders return
     * a static image, multi-frame renders return an animated image with per-frame delay of
     * {@code ticksPerFrame * 50ms}.
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<FluidOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull FluidOptions options) {
            if (options.getFrameCount() <= 1)
                return RenderEngine.staticFrame(renderFrame(options, options.getStartTick()));

            // Frame-parallel bake: renderFrame constructs its own IsometricEngine, TextureEngine,
            // triangle list, and output PixelBuffer per invocation; context is the only shared
            // reference and it is read-only. mapToObj().toList() preserves encounter order so
            // the resulting ConcurrentList stays tick-ordered for GIF/WebP playback.
            ConcurrentList<PixelBuffer> frames = Concurrent.newList();
            frames.addAll(IntStream.range(0, options.getFrameCount()).parallel()
                .mapToObj(f -> renderFrame(options, options.getStartTick() + f * options.getTicksPerFrame()))
                .toList());
            return RenderEngine.output(frames, options.getTicksPerFrame() * MILLIS_PER_TICK);
        }

        private @NotNull PixelBuffer renderFrame(@NotNull FluidOptions options, int tick) {
            IsometricEngine engine = IsometricEngine.standard(this.context);
            TextureEngine textures = new TextureEngine(this.context);
            PixelBuffer still = textures.resolveTextureAtTick(stillTextureId(options.getFluid()), tick);
            PixelBuffer flow = textures.resolveTextureAtTick(flowTextureId(options.getFluid()), tick);
            int tint = resolveFluidTint(this.context, options);

            ConcurrentList<VisibleTriangle> triangles = FluidGeometryKit.buildFluidCube(
                options.getCornerHeights(), still, flow, options.getFlowAngleRadians(), tint);

            int ssaa = Math.max(1, options.getSupersample());
            if (ssaa > 1) {
                int hiRes = options.getOutputSize() * ssaa;
                try (PixelBufferPool.Lease lease = PixelBufferPool.acquire(hiRes, hiRes)) {
                    PixelBuffer buffer = lease.buffer();
                    engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, options.getRotation());
                    if (options.isAntiAlias()) buffer.applyFxaa();
                    PixelBuffer output = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
                    output.blitScaled(buffer, 0, 0, options.getOutputSize(), options.getOutputSize());
                    return output;
                }
            }

            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
            engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, options.getRotation());
            if (options.isAntiAlias()) buffer.applyFxaa();
            return buffer;
        }

    }

    /**
     * Flat top-down source-face fluid renderer. Blits the still texture scaled to the output
     * size and multiplies it by the fluid tint - the view a caller would use for an inventory
     * icon if fluids were holdable.
     */
    @RequiredArgsConstructor
    public static final class FluidFace2D implements Renderer<FluidOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull FluidOptions options) {
            if (options.getFrameCount() <= 1)
                return RenderEngine.staticFrame(renderFrame(options, options.getStartTick()));

            // Frame-parallel bake: each tick constructs its own RasterEngine + output buffer.
            // mapToObj().toList() preserves encounter order for the animation strip.
            ConcurrentList<PixelBuffer> frames = Concurrent.newList();
            frames.addAll(IntStream.range(0, options.getFrameCount()).parallel()
                .mapToObj(f -> renderFrame(options, options.getStartTick() + f * options.getTicksPerFrame()))
                .toList());
            return RenderEngine.output(frames, options.getTicksPerFrame() * MILLIS_PER_TICK);
        }

        private @NotNull PixelBuffer renderFrame(@NotNull FluidOptions options, int tick) {
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer buffer = engine.createBuffer(options.getOutputSize(), options.getOutputSize());
            PixelBuffer still = engine.resolveTextureAtTick(stillTextureId(options.getFluid()), tick);
            int tint = resolveFluidTint(this.context, options);
            PixelBuffer tinted = ColorMath.tint(still, tint);
            buffer.blitScaled(tinted, 0, 0, options.getOutputSize(), options.getOutputSize());
            return buffer;
        }

    }

}
