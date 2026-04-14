package dev.sbs.renderer.options;

import dev.sbs.renderer.asset.binding.ArmorPiece;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configures a single {@code PlayerRenderer} invocation. Supports three body scopes (skull,
 * bust, full body) in both 2D front-facing and 3D isometric dimensions.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class PlayerOptions {

    /** Which body parts to include in the render */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.SKULL;

    /** Whether to produce a 2D composite or 3D isometric render */
    @lombok.Builder.Default
    private final @NotNull Dimension dimension = Dimension.THREE_D;

    /** Raw PNG bytes of a player skin (priority 1 when resolving a skin). */
    @lombok.Builder.Default
    private final @NotNull Optional<byte[]> skinBytes = Optional.empty();

    /** Absolute URL to a player skin PNG (priority 2, fetched through {@code HttpFetcher}). */
    @lombok.Builder.Default
    private final @NotNull Optional<String> skinUrl = Optional.empty();

    /** Texture id resolvable through the active pack stack (priority 3). */
    @lombok.Builder.Default
    private final @NotNull Optional<String> skinTextureId = Optional.empty();

    /** Helmet armor piece to render on the head. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> helmet = Optional.empty();

    /** Chestplate armor piece to render on the torso and arms. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> chestplate = Optional.empty();

    /** Leggings armor piece to render on the waist and legs. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> leggings = Optional.empty();

    /** Boots armor piece to render on the feet. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> boots = Optional.empty();

    /** Whether to render the player cape behind the torso (3D bust and full only). */
    @lombok.Builder.Default
    private final boolean renderCape = false;

    /** Raw PNG bytes of the 64x32 cape texture. Ignored when {@link #renderCape} is false. */
    @lombok.Builder.Default
    private final @NotNull Optional<byte[]> capeBytes = Optional.empty();

    /** Absolute URL to a cape texture PNG. Ignored when {@link #renderCape} is false. */
    @lombok.Builder.Default
    private final @NotNull Optional<String> capeUrl = Optional.empty();

    /** Pack-resolvable cape texture id. Ignored when {@link #renderCape} is false. */
    @lombok.Builder.Default
    private final @NotNull Optional<String> capeTextureId = Optional.empty();

    /** Whether to render the second skin layer (hat, jacket, sleeves, trousers). */
    @lombok.Builder.Default
    private final boolean renderOverlay = true;

    /** Output image dimensions in pixels (square) */
    @lombok.Builder.Default
    private final int outputSize = 256;

    /** Model rotation around the Y axis in degrees */
    @lombok.Builder.Default
    private final float yaw = 0f;

    /** Model rotation around the X axis in degrees */
    @lombok.Builder.Default
    private final float pitch = 0f;

    /** Model rotation around the Z axis in degrees */
    @lombok.Builder.Default
    private final float roll = 0f;

    /** Whether to apply FXAA post-processing after the main render pass. */
    @lombok.Builder.Default
    private final boolean antiAlias = true;

    /** Output image format */
    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    public @NotNull PlayerOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull PlayerOptions defaults() {
        return builder().build();
    }

    /** Which body parts to render. */
    public enum Type {

        /** Head only. */
        SKULL,

        /** Head, torso and arms. */
        BUST,

        /** Full body - head, torso, arms and legs. */
        FULL

    }

    /** Whether to produce a 2D front-facing composite or a 3D isometric render. */
    public enum Dimension {

        /** Front-facing 2D sprite composite. */
        TWO_D,

        /** Isometric 3D rasterization. */
        THREE_D

    }

}
