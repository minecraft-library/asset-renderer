package dev.sbs.renderer.options;

import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configures a single {@code EntityRenderer} invocation. Covers both 2D and 3D player views plus
 * generic entity rendering.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class EntityOptions {

    /** The supported render types for {@code EntityRenderer}. */
    public enum Type {
        /** A 3D isometric player skull tile. */
        PLAYER_SKULL,
        /** A 3D isometric bust (head + torso + arms). */
        PLAYER_BUST_3D,
        /** A 2D front-facing bust. */
        PLAYER_BUST_2D,
        /** A 2D front-facing full-body profile. */
        PLAYER_PROFILE_2D,
        /** A 2D front-facing face crop (head front only). */
        PLAYER_FACE_2D,
        /** A 3D rendering of a non-player entity using its {@code EntityModelData}. */
        ENTITY_3D
    }

    @lombok.Builder.Default
    private final @NotNull Type type = Type.PLAYER_FACE_2D;

    /** Raw PNG bytes of a player skin (priority 1 when resolving a skin). */
    @lombok.Builder.Default
    private final @NotNull Optional<byte[]> skinBytes = Optional.empty();

    /** Absolute URL to a player skin PNG (priority 2, fetched through {@code HttpFetcher}). */
    @lombok.Builder.Default
    private final @NotNull Optional<String> skinUrl = Optional.empty();

    /** Texture id resolvable through the active pack stack (priority 3). */
    @lombok.Builder.Default
    private final @NotNull Optional<String> skinTextureId = Optional.empty();

    /** Entity id for {@code ENTITY_3D} mode, e.g. {@code "minecraft:zombie"}. */
    @lombok.Builder.Default
    private final @NotNull Optional<String> entityId = Optional.empty();

    @lombok.Builder.Default
    private final int outputSize = 256;

    @lombok.Builder.Default
    private final float yaw = 0f;

    @lombok.Builder.Default
    private final float pitch = 0f;

    @lombok.Builder.Default
    private final float roll = 0f;

    /** Whether to render the second skin layer (hat overlay) on the head. */
    @lombok.Builder.Default
    private final boolean renderHat = true;

    /** Whether to apply FXAA post-processing after the main render pass. */
    @lombok.Builder.Default
    private final boolean antiAlias = true;

    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    /**
     * Returns a builder initialized with this options instance's values for modification.
     *
     * @return a new builder
     */
    public @NotNull EntityOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Returns the default options.
     *
     * @return a new default options instance
     */
    public static @NotNull EntityOptions defaults() {
        return builder().build();
    }

}
