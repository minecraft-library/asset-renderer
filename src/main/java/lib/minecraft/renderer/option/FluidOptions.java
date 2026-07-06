package lib.minecraft.renderer.option;

import dev.simplified.image.Background;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.option.slot.FluidSlot;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.engine.texture.Biome;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@code FluidRenderer} invocation.
 * <p>
 * Unlike {@code BlockOptions} there is no model lookup - fluids are rendered from two synthetic
 * textures ({@code block/water_still} + {@code block/water_flow}, or the lava equivalents) and a
 * small set of geometry parameters. Neighbor-aware features (corner-height interpolation,
 * flow-direction derivation, bottom-face culling) are the caller's concern - {@code FluidOptions}
 * accepts precomputed values so the renderer stays scene-agnostic.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class FluidOptions implements RenderOptions {

    /**
     * The fluid to render - drives texture selection and tintability. Defaults to
     * {@link Fluid#WATER}.
     */
    @lombok.Builder.Default
    private final @NotNull Fluid fluid = Fluid.WATER;

    /**
     * Render type - isometric 3D cube or flat 2D source-face icon.
     */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.ISOMETRIC_3D;

    /**
     * Four corner heights for the top face, NW/NE/SE/SW in block space {@code [0, 1]}. Defaults
     * to {@link CornerHeights#FULL} (all 1.0 - a flat-topped full cube).
     * <p>
     * Vanilla fluid rendering derives these from the block's {@code level} property and its
     * neighbors' levels. That computation lives in the caller; {@code FluidRenderer} just
     * consumes the precomputed values.
     */
    @lombok.Builder.Default
    private final @NotNull CornerHeights cornerHeights = CornerHeights.FULL;

    /**
     * Flow direction in radians. When present, side faces use the flow texture with UVs rotated
     * by this angle; when empty, side faces use the still texture (the source-block /
     * no-net-flow case).
     * <p>
     * Vanilla derives this from the gradient of neighbor fluid heights - again caller's concern.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Float> flowAngleRadians = Optional.empty();

    /**
     * Biome used for tinting water, routed through {@code Block.TintTarget.WATER}. Ignored for
     * lava. Defaults to {@link Biome.Vanilla#PLAINS}.
     */
    @lombok.Builder.Default
    private final @NotNull Biome biome = Biome.Vanilla.PLAINS;

    /**
     * Explicit ARGB water tint override. When non-null, bypasses biome lookup entirely. Useful
     * for scene renderers that want to colour water from a non-standard source (potion effects,
     * coloured-water packs). {@code null} falls through to the biome-based resolver. Ignored for
     * lava.
     */
    private final @Nullable Integer waterTintArgbOverride;

    /**
     * The default output frame for a fluid render - neutral output size, {@code VANILLA_ISO}
     * projection, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT = OutputOptions.defaults();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    @lombok.Builder.Default
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

    /**
     * The default animation timing for a fluid render - a static frame (seed tick 0, 1 frame,
     * 1 tick per frame).
     */
    public static final @NotNull AnimationOptions DEFAULT_ANIMATION = AnimationOptions.defaults();

    /** The animation timing (seed tick, frame count, ticks per frame). */
    @lombok.Builder.Default
    private final @NotNull AnimationOptions animation = DEFAULT_ANIMATION;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default {@link GeometryLayer} stack (the fluid cube) before it runs,
     * letting callers splice custom layers relative to the {@link FluidSlot} slot. Defaults to
     * {@linkplain UnaryOperator#identity() identity}. Only consulted by the 3D isometric path.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<GeometryLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull FluidOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default options
     */
    public static @NotNull FluidOptions defaults() {
        return builder().build();
    }

    /**
     * The fluid variants supported by {@code FluidRenderer}.
     */
    public enum Fluid {

        /**
         * Vanilla water - biome-tinted, uses {@code block/water_still} + {@code block/water_flow}.
         */
        WATER,

        /**
         * Vanilla lava - untinted, uses {@code block/lava_still} + {@code block/lava_flow}.
         */
        LAVA

    }

    /**
     * The supported render types for {@code FluidRenderer}.
     */
    public enum Type {

        /**
         * Full 3D isometric fluid cube with corner heights, still/flow textures, and optional flow direction.
         */
        ISOMETRIC_3D,

        /**
         * Flat top-down source-face icon - what the fluid would look like as an inventory item.
         */
        FLUID_FACE_2D

    }

    /**
     * Four per-corner top-face heights in block space {@code [0, 1]}. Order is NW/NE/SE/SW,
     * corresponding to the four top corners of a 1x1x1 cube aligned to the vanilla north-east
     * compass convention.
     *
     * @param nw north-west corner height
     * @param ne north-east corner height
     * @param se south-east corner height
     * @param sw south-west corner height
     */
    public record CornerHeights(float nw, float ne, float se, float sw) {

        /**
         * Full-height corners (all 1.0) - a flat-topped source-block cube.
         */
        public static final @NotNull CornerHeights FULL = new CornerHeights(1f, 1f, 1f, 1f);

        /**
         * Creates a flat-topped {@code CornerHeights} with every corner at the given height.
         *
         * @param height the uniform corner height
         * @return a new {@code CornerHeights}
         */
        public static @NotNull CornerHeights uniform(float height) {
            return new CornerHeights(height, height, height, height);
        }

    }

}
