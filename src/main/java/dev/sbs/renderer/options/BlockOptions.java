package dev.sbs.renderer.options;

import dev.sbs.renderer.biome.Biome;
import dev.sbs.renderer.draw.BlockFace;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Configures a single {@code BlockRenderer} invocation.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class BlockOptions {

    /** Namespaced block id to render, e.g. {@code "minecraft:stone"} */
    @lombok.Builder.Default
    private final @NotNull String blockId = "";

    /** Render type - isometric 3D or flat 2D face */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.ISOMETRIC_3D;

    /** Block face to render in {@link Type#BLOCK_FACE_2D} mode */
    @lombok.Builder.Default
    private final @NotNull BlockFace face = BlockFace.NORTH;

    /**
     * Blockstate variant properties string (e.g. {@code "facing=south,lit=false"}). When set,
     * the renderer applies the variant's whole-model X/Y rotation before the camera transform.
     * An empty string selects the default variant.
     */
    @lombok.Builder.Default
    private final @NotNull String variant = "";

    /** Biome used for tinting grass, foliage and water textures */
    @lombok.Builder.Default
    private final @NotNull Biome biome = Biome.Vanilla.PLAINS;

    /** Model rotation around the Y axis in degrees */
    @lombok.Builder.Default
    private final float yaw = 0f;

    /** Model rotation around the X axis in degrees */
    @lombok.Builder.Default
    private final float pitch = 0f;

    /** Model rotation around the Z axis in degrees */
    @lombok.Builder.Default
    private final float roll = 0f;

    /** Output image dimensions in pixels (square) */
    @lombok.Builder.Default
    private final int outputSize = 256;

    /** Whether to apply FXAA post-processing */
    @lombok.Builder.Default
    private final boolean antiAlias = true;

    /**
     * Supersample scale factor for isometric 3D rendering. The block is rasterized at
     * {@code outputSize * supersample} resolution, then downsampled for sharper output at small
     * tile sizes. A value of 1 disables supersampling.
     */
    @lombok.Builder.Default
    private final int supersample = 2;

    /** Additional texture pack ids to layer on top of vanilla */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<String> texturePackIds = Concurrent.newList();

    /** Output image format */
    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    public @NotNull BlockOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull BlockOptions defaults() {
        return builder().build();
    }

    /** The supported render types for {@code BlockRenderer}. */
    public enum Type {

        /** Full 3D isometric block, six faces. */
        ISOMETRIC_3D,

        /** A single 2D block face. */
        BLOCK_FACE_2D

    }

}
