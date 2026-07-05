package lib.minecraft.renderer.options;

import dev.simplified.image.Background;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.ArmorKit;
import lib.minecraft.renderer.engine.kit.TrimKit;
import lib.minecraft.renderer.options.slot.PlayerSlot2D;
import lib.minecraft.renderer.options.slot.PlayerSlot3D;
import lib.minecraft.renderer.options.spec.ArmorOptions;
import lib.minecraft.renderer.options.spec.RenderOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@link PlayerRenderer} invocation.
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
 *       armor and trim layers composited via {@link ArmorKit} and {@link TrimKit}.</li>
 * </ul>
 *
 * <p>Skin input is supplied through one of three sources, tried in priority order:
 * {@link #getSkinBytes() skinBytes} (1), {@link #getSkinUrl() skinUrl} (2), then
 * {@link #getSkinTextureId() skinTextureId} (3); with none present the renderer falls back to the
 * registered {@code minecraft:entity/steve} texture. The {@code skinUrl} path extracts the URL's
 * trailing path segment (the texture hash) and streams the PNG through the
 * {@link Pipeline#mojang() Pipeline.mojang()} proxy. Cape input mirrors this via
 * {@link #getCapeBytes() capeBytes} / {@link #getCapeUrl() capeUrl} /
 * {@link #getCapeTextureId() capeTextureId}, consulted only when {@link #isRenderCape() renderCape}
 * is set.
 *
 * @see lib.minecraft.renderer.PlayerRenderer
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class PlayerOptions implements Option {

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

    /** The worn armor pieces (helmet, chestplate, leggings, boots). */
    @lombok.Builder.Default
    private final @NotNull ArmorOptions armor = ArmorOptions.defaults();

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
     * The default render frame for a player render - neutral output size, {@code VANILLA_ISO}
     * projection, no supersampling and no FXAA.
     */
    public static final @NotNull RenderOptions DEFAULT_RENDER = RenderOptions.defaults();

    /** The shared render frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    @lombok.Builder.Default
    private final @NotNull RenderOptions render = DEFAULT_RENDER;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default 2D {@link ImageLayer} stack (skin, overlay, armor) before it
     * runs, letting callers splice custom layers relative to the {@link PlayerSlot2D} slots.
     * Defaults to {@linkplain UnaryOperator#identity() identity}. Only consulted by the 2D path.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<ImageLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Transform applied to the default 3D {@link GeometryLayer} stack (body, armor, cape) before it
     * runs, letting callers splice custom layers relative to the {@link PlayerSlot3D} slots.
     * Defaults to {@linkplain UnaryOperator#identity() identity}. Only consulted by the 3D path.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<GeometryLayer>> geometryLayerDecorator = UnaryOperator.identity();

    /**
     * A builder pre-populated with this instance's field values, for deriving a variant.
     *
     * @return the seeded builder
     */
    public @NotNull PlayerOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * The default player options - a 3D {@linkplain Type#SKULL skull} with the neutral
     * {@link #getRender() render} frame, overlay layer on, no armor or cape, over a
     * {@linkplain Background#TRANSPARENT transparent} background.
     *
     * @return the default options
     */
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
