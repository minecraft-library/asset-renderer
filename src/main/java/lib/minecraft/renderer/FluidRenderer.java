package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.Finalize;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.FluidGeometryKit;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.option.slot.FluidSlot;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Renders vanilla fluids (water, lava) as either a full 3D isometric cube or a flat top-down
 * source-face icon by dispatching to one of two sub-renderers based on {@link FluidOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;FluidOptions&gt;}:
 * <ul>
 * <li>{@link Isometric3D} uses a {@link ModelEngine} in its block-icon pose - fluids
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

    /** Namespaced still-frame texture id for water (source-face / top texture). */
    static final @NotNull String WATER_STILL_TEXTURE_ID = "minecraft:block/water_still";
    /** Namespaced flow-frame texture id for water (side / sloped-top texture). */
    static final @NotNull String WATER_FLOW_TEXTURE_ID = "minecraft:block/water_flow";
    /** Namespaced still-frame texture id for lava (source-face / top texture). */
    static final @NotNull String LAVA_STILL_TEXTURE_ID = "minecraft:block/lava_still";
    /** Namespaced flow-frame texture id for lava (side / sloped-top texture). */
    static final @NotNull String LAVA_FLOW_TEXTURE_ID = "minecraft:block/lava_flow";

    /**
     * Duration of one vanilla tick in milliseconds - used to convert {@code ticksPerFrame} into a
     * per-frame animation delay.
     */
    private static final int MILLIS_PER_TICK = 50;

    /** Sub-renderer for the full 3D isometric cube path ({@link FluidOptions.Type#ISOMETRIC_3D}). */
    private final @NotNull Isometric3D isometric3D;
    /** Sub-renderer for the flat still-face path ({@link FluidOptions.Type#FLUID_FACE_2D}). */
    private final @NotNull FluidFace2D fluidFace2D;

    /**
     * Constructs a new {@code FluidRenderer} bound to the given context, eagerly creating both
     * sub-renderers so a caller can dispatch either render type without re-instantiation.
     *
     * @param context the render context supplying texture and biome-tint lookups
     */
    public FluidRenderer(@NotNull RendererContext context) {
        this.isometric3D = new Isometric3D(context);
        this.fluidFace2D = new FluidFace2D(context);
    }

    /**
     * Renders the fluid, dispatching to the isometric cube or flat-face sub-renderer per
     * {@link FluidOptions#getType()}, then composites the result over the options background.
     *
     * @param options the fluid options
     * @return the rendered image composited over {@link FluidOptions#getBackground()}
     */
    @Override
    public @NotNull ImageData render(@NotNull FluidOptions options) {
        ImageData rendered = switch (options.getType()) {
            case ISOMETRIC_3D -> this.isometric3D.render(options);
            case FLUID_FACE_2D -> this.fluidFace2D.render(options);
        };
        return options.getBackground().composite(rendered);
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
     * biome's water tint via {@link Textures#sampleBiomeTint} using
     * {@link Block.TintTarget#WATER} (which falls back to the engine-level default when the
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
        return new Textures(context).sampleBiomeTint(Block.TintTarget.WATER, options.getBiome());
    }

    /**
     * Full 3D isometric fluid cube renderer. Builds triangles via {@link FluidGeometryKit}, then
     * rasterizes through {@link Projection#VANILLA_ISO}'s {@code [30, 225, 0]} pose by default.
     * Animation is driven by {@link AnimationOptions#getFrameCount()} - single-frame renders return
     * a static image, multi-frame renders return an animated image with per-frame delay of
     * {@code ticksPerFrame * 50ms}.
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<FluidOptions> {

        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull FluidOptions options) {
            // rasterizeFrame constructs its own engine, textures, and triangle list per invocation;
            // context is the only shared reference and it is read-only, so Finalize bakes every frame
            // in parallel. The per-tick build MUST stay inside the rasterizer callback (capturing it
            // once would freeze the animation on frame 0's textures).
            int ssaa = Math.max(1, options.getOutput().getSupersample());
            return Finalize.render(
                Finalize.FinalizeSpec.animated(options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize(), ssaa, options.getOutput().isAntiAlias(),
                    options.getAnimation().getFrameCount(), options.getAnimation().getStartTick(), options.getAnimation().getTicksPerFrame(),
                    options.getAnimation().getTicksPerFrame() * MILLIS_PER_TICK),
                (target, mask, tick) -> rasterizeFrame(options, tick, target));
        }

        /**
         * Bakes a single animation frame at {@code tick} into {@code target}: resolves the projection,
         * samples the still and flow textures, builds the tinted fluid cube through
         * {@link FluidGeometryKit}, and rasterizes it. The supersample / FXAA / downscale tail is the
         * shared {@link Finalize}.
         */
        private void rasterizeFrame(@NotNull FluidOptions options, int tick, @NotNull PixelBuffer target) {
            // Resolve the projection once: the caller's rotation is composed onto the base pose, so it
            // poses the camera directly and the rasterize call applies no separate model-spin. Default
            // renders pass EulerRotation.NONE, leaving the byte-identical base block-icon pose.
            var resolved = options.getOutput().getProjection().resolve(options.getOutput().getRotation(), options.getOutput().getFacing());
            ModelEngine engine = new ModelEngine(this.context, resolved);
            Textures textures = new Textures(this.context);
            PixelBuffer still = textures.resolveTextureAtTick(stillTextureId(options.getFluid()), tick);
            PixelBuffer flow = textures.resolveTextureAtTick(flowTextureId(options.getFluid()), tick);
            int tint = resolveFluidTint(this.context, options);

            // Single built-in contributor (the cube), expressed as a GeometryLayer so fluid uses the
            // same stack-driven ordering as every other renderer and callers can splice extra layers.
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            LayerStack<GeometryLayer> stack = new LayerStack<>();
            stack.append(FluidSlot.CUBE, sink -> sink.addAll(FluidGeometryKit.buildFluidCube(
                options.getCornerHeights(), still, flow, options.getFlowAngleRadians(), tint)));
            Layers.foldInto(stack, options.getLayerDecorator(), triangles);

            engine.rasterize(triangles, target);
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

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull FluidOptions options) {
            // Each tick constructs its own RasterEngine, so Finalize bakes frames in parallel. Flat 2D
            // blit: no supersample / FXAA (ssaa = 1, antiAlias = false).
            return Finalize.render(
                Finalize.FinalizeSpec.animated(options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize(), 1, false,
                    options.getAnimation().getFrameCount(), options.getAnimation().getStartTick(), options.getAnimation().getTicksPerFrame(),
                    options.getAnimation().getTicksPerFrame() * MILLIS_PER_TICK),
                (target, mask, tick) -> rasterizeFrame(options, tick, target));
        }

        /**
         * Bakes a single animation frame at {@code tick} into {@code target}: samples the still texture,
         * multiplies it by the fluid tint, and blits it scaled to fill the buffer.
         */
        private void rasterizeFrame(@NotNull FluidOptions options, int tick, @NotNull PixelBuffer target) {
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer still = engine.textures().resolveTextureAtTick(stillTextureId(options.getFluid()), tick);
            int tint = resolveFluidTint(this.context, options);
            PixelBuffer tinted = ColorMath.tint(still, tint);
            target.blitScaled(tinted, 0, 0, options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize());
        }

    }

}
