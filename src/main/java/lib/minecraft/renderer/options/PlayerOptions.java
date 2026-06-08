package lib.minecraft.renderer.options;

import dev.simplified.image.Background;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.appearance.ArmorPiece;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.kit.ArmorKit;
import lib.minecraft.renderer.kit.TrimKit;
import lib.minecraft.renderer.pipeline.Pipeline;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configures a single {@link PlayerRenderer PlayerRenderer} invocation.
 *
 * <p>Three body scopes select what portion of the player model renders:
 * <ul>
 *   <li><b>{@link Type#SKULL}</b> - head cube only.</li>
 *   <li><b>{@link Type#BUST}</b> - head + torso + arms.</li>
 *   <li><b>{@link Type#FULL}</b> - full body.</li>
 * </ul>
 *
 * <p>Two perspectives select how the body is presented:
 * <ul>
 *   <li><b>{@link Dimension#TWO_D}</b> - flat orthographic view derived from the skin atlas.</li>
 *   <li><b>{@link Dimension#THREE_D}</b> - the vanilla {@code display.gui} pose with optional
 *       armor and trim layers composited via
 *       {@link ArmorKit ArmorKit} and
 *       {@link TrimKit TrimKit}.</li>
 * </ul>
 *
 * <p>Skin input is supplied at render time through one of {@link #getSkinBytes skinBytes},
 * {@link #getSkinUrl skinUrl}, or {@link #getSkinTextureId skinTextureId}; the {@code skinUrl}
 * path is resolved via the
 * {@link Pipeline#mojang() Pipeline.mojang()} proxy when the
 * URL points at a Mojang skin texture.
 *
 * @see lib.minecraft.renderer.PlayerRenderer
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class PlayerOptions {

    /**
     * Which body parts to include in the render
     */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.SKULL;

    /**
     * Whether to produce a 2D composite or 3D isometric render
     */
    @lombok.Builder.Default
    private final @NotNull Dimension dimension = Dimension.THREE_D;

    /**
     * Raw PNG bytes of a player skin (priority 1 when resolving a skin).
     */
    @lombok.Builder.Default
    private final @NotNull Optional<byte[]> skinBytes = Optional.empty();

    /**
     * Absolute URL to a player skin PNG (priority 2, streamed through {@code MojangContract.downloadTexture}).
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> skinUrl = Optional.empty();

    /**
     * Texture id resolvable through the active pack stack (priority 3).
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> skinTextureId = Optional.empty();

    /**
     * Helmet armor piece to render on the head.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> helmet = Optional.empty();

    /**
     * Chestplate armor piece to render on the torso and arms.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> chestplate = Optional.empty();

    /**
     * Leggings armor piece to render on the waist and legs.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> leggings = Optional.empty();

    /**
     * Boots armor piece to render on the feet.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> boots = Optional.empty();

    /**
     * Whether to render the player cape behind the torso (3D bust and full only).
     */
    @lombok.Builder.Default
    private final boolean renderCape = false;

    /**
     * Raw PNG bytes of the 64x32 cape texture. Ignored when {@link #renderCape} is false.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<byte[]> capeBytes = Optional.empty();

    /**
     * Absolute URL to a cape texture PNG. Ignored when {@link #renderCape} is false.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> capeUrl = Optional.empty();

    /**
     * Pack-resolvable cape texture id. Ignored when {@link #renderCape} is false.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> capeTextureId = Optional.empty();

    /**
     * Whether to render the second skin layer (hat, jacket, sleeves, trousers).
     */
    @lombok.Builder.Default
    private final boolean renderOverlay = true;

    /**
     * Output image dimensions in pixels (square)
     */
    @lombok.Builder.Default
    private final int outputSize = Renderer.DEFAULT_OUTPUT_SIZE;

    /**
     * Model rotation applied before the camera transform, in degrees
     */
    @lombok.Builder.Default
    private final @NotNull EulerRotation rotation = EulerRotation.NONE;

    /**
     * Whether to apply FXAA post-processing after the main render pass.
     */
    @lombok.Builder.Default
    private final boolean antiAlias = true;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    public @NotNull PlayerOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull PlayerOptions defaults() {
        return builder().build();
    }

    /**
     * Which body parts to render.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Type {

        /**
         * Head only.
         */
        SKULL (8,  8),

        /**
         * Head, torso and arms.
         */
        BUST  (20, 16),

        /**
         * Full body - head, torso, arms and legs.
         */
        FULL  (32, 16);

        /**
         * Pixel height of the 2D body composite, before output scaling.
         */
        private final int bodyHeight;

        /**
         * Pixel width of the 2D body composite, before output scaling.
         */
        private final int bodyWidth;

    }

    /**
     * Whether to produce a 2D front-facing composite or a 3D isometric render.
     */
    public enum Dimension {

        /**
         * Front-facing 2D sprite composite.
         */
        TWO_D,

        /**
         * Isometric 3D rasterization.
         */
        THREE_D

    }

}
