package lib.minecraft.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.geometry.Biome;
import lib.minecraft.renderer.geometry.EulerRotation;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

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
public class FluidOptions {

    /** The fluid to render - drives texture selection and tintability. */
    @lombok.Builder.Default
    private final @NotNull Fluid fluid = Fluid.WATER;

    /** Render type - isometric 3D cube or flat 2D source-face icon. */
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

    /** Biome used for tinting water. Ignored for lava. Routed through {@code Biome.TintTarget.WATER}. */
    @lombok.Builder.Default
    private final @NotNull Biome biome = Biome.Vanilla.PLAINS;

    /**
     * Explicit ARGB water tint override. When non-null, bypasses biome lookup entirely. Useful
     * for scene renderers that want to colour water from a non-standard source (potion effects,
     * coloured-water packs). {@code null} falls through to the biome-based resolver. Ignored for
     * lava.
     */
    private final @Nullable Integer waterTintArgbOverride;

    /** Model rotation applied before the camera transform, in degrees. */
    @lombok.Builder.Default
    private final @NotNull EulerRotation rotation = EulerRotation.NONE;

    /** Output image dimensions in pixels (square). */
    @lombok.Builder.Default
    private final int outputSize = Renderer.DEFAULT_OUTPUT_SIZE;

    /** Whether to apply FXAA post-processing. No-op on the 2D face path. */
    @lombok.Builder.Default
    private final boolean antiAlias = true;

    /**
     * Supersample scale factor for isometric 3D rendering. The cube is rasterized at
     * {@code outputSize * supersample} resolution, then downsampled for sharper output. A value
     * of 1 disables supersampling. No-op on the 2D face path.
     */
    @lombok.Builder.Default
    private final int supersample = 2;

    /** Additional texture pack ids to layer on top of vanilla. */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<String> texturePackIds = Concurrent.newList();

    /** Output image format. */
    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    /** Animation seed tick. Frame 0 samples the still/flow strip at this tick. */
    @lombok.Builder.Default
    private final int startTick = 0;

    /**
     * Number of output frames. {@code 1} produces a static image; {@code >1} produces an
     * animated image with {@link #ticksPerFrame} ticks between successive frames.
     */
    @lombok.Builder.Default
    private final int frameCount = 1;

    /** Ticks between successive output frames. One vanilla tick is {@code 50 ms}. */
    @lombok.Builder.Default
    private final int ticksPerFrame = 1;

    public @NotNull FluidOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull FluidOptions defaults() {
        return builder().build();
    }

    /** The fluid variants supported by {@code FluidRenderer}. */
    public enum Fluid {

        /** Vanilla water - biome-tinted, uses {@code block/water_still} + {@code block/water_flow}. */
        WATER,

        /** Vanilla lava - untinted, uses {@code block/lava_still} + {@code block/lava_flow}. */
        LAVA

    }

    /** The supported render types for {@code FluidRenderer}. */
    public enum Type {

        /** Full 3D isometric fluid cube with corner heights, still/flow textures, and optional flow direction. */
        ISOMETRIC_3D,

        /** Flat top-down source-face icon - what the fluid would look like as an inventory item. */
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

        /** Full-height corners (all 1.0) - a flat-topped source-block cube. */
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
