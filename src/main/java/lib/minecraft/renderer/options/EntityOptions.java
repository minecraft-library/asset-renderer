package lib.minecraft.renderer.options;

import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.asset.binding.ArmorPiece;
import lib.minecraft.renderer.geometry.EulerRotation;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configures a single {@code EntityRenderer} invocation for non-player entities. The entity is
 * resolved by {@code entityId} through the active {@code RendererContext} and rendered as a 3D
 * model via its {@code EntityModelData} bone/cube tree.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class EntityOptions {

    /** Entity id for lookup, e.g. {@code "minecraft:zombie"}. */
    @lombok.Builder.Default
    private final @NotNull Optional<String> entityId = Optional.empty();

    /** Optional texture id override, resolvable through the active pack stack. */
    @lombok.Builder.Default
    private final @NotNull Optional<String> textureId = Optional.empty();

    /** Helmet armor piece to render on humanoid entities. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> helmet = Optional.empty();

    /** Chestplate armor piece to render on humanoid entities. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> chestplate = Optional.empty();

    /** Leggings armor piece to render on humanoid entities. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> leggings = Optional.empty();

    /** Boots armor piece to render on humanoid entities. */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> boots = Optional.empty();

    /** Output image dimensions in pixels (square) */
    @lombok.Builder.Default
    private final int outputSize = Renderer.DEFAULT_OUTPUT_SIZE;

    /** Model rotation applied before the camera transform, in degrees */
    @lombok.Builder.Default
    private final @NotNull EulerRotation rotation = EulerRotation.NONE;

    /** Whether to apply FXAA post-processing after the main render pass. */
    @lombok.Builder.Default
    private final boolean antiAlias = true;

    /** Output image format */
    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    public @NotNull EntityOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull EntityOptions defaults() {
        return builder().build();
    }

}
