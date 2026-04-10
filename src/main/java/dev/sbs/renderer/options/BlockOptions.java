package dev.sbs.renderer.options;

import dev.sbs.renderer.biome.Biome;
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

    /** The supported render types for {@code BlockRenderer}. */
    public enum Type {
        /** Full 3D isometric block, six faces. */
        ISOMETRIC_3D,
        /** A single 2D block face. */
        BLOCK_FACE_2D
    }

    /** The six cardinal block faces, used by {@link Type#BLOCK_FACE_2D}. */
    public enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }

    @lombok.Builder.Default
    private final @NotNull String blockId = "";

    @lombok.Builder.Default
    private final @NotNull Type type = Type.ISOMETRIC_3D;

    @lombok.Builder.Default
    private final @NotNull Face face = Face.NORTH;

    @lombok.Builder.Default
    private final @NotNull Biome biome = Biome.Vanilla.PLAINS;

    @lombok.Builder.Default
    private final float yaw = 0f;

    @lombok.Builder.Default
    private final float pitch = 0f;

    @lombok.Builder.Default
    private final float roll = 0f;

    @lombok.Builder.Default
    private final int outputSize = 256;

    @lombok.Builder.Default
    private final boolean antiAlias = true;

    @lombok.Builder.Default
    private final @NotNull ConcurrentList<String> texturePackIds = Concurrent.newList();

    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    public @NotNull BlockOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull BlockOptions defaults() {
        return builder().build();
    }

}
