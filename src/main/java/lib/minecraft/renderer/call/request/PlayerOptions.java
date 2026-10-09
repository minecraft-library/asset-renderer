package lib.minecraft.renderer.call.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.image.Background;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.bake.armor.ArmorKit;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.call.slot.PlayerSlot2D;
import lib.minecraft.renderer.call.slot.PlayerSlot3D;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.mesh.PlayerLattice;
import org.jetbrains.annotations.NotNull;

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
 * <p>Skin and cape input is supplied through the {@link #getSkin() skin} {@link SkinOptions}, whose
 * skin and cape are each a three-source {@link TextureOptions} tried in priority order - raw PNG
 * bytes (1), an absolute URL (2), then a pack-resolvable texture id (3). With no skin source present
 * the renderer falls back to the wide-arm Steve texture, {@code minecraft:entity/player/wide/steve}.
 * The URL path extracts the URL's trailing path segment (the texture hash) and streams the PNG through the
 * {@link ClientAcquisition#mojang() ClientAcquisition.mojang()} proxy. The cape is consulted only when the skin's
 * {@code renderCape} toggle is set.
 *
 * @see lib.minecraft.renderer.PlayerRenderer
 */
@Parity(as = PlayerRenderer.class)
@Getter
@ClassBuilder
public class PlayerOptions implements RenderOptions {

    /**
     * Which body parts to include in the render
     */
    private final @NotNull Type type = Type.SKULL;

    /**
     * Whether to produce a 2D composite or 3D isometric render
     */
    private final @NotNull Dimension dimension = Dimension.THREE_D;

    /** The skin + cape texture sources and their render toggles. */
    private final @NotNull SkinOptions skin = SkinOptions.defaults();

    /** The worn armor pieces (helmet, chestplate, leggings, boots). */
    private final @NotNull ArmorOptions armor = ArmorOptions.defaults();

    /**
     * The default output frame for a player render - neutral output size, {@code VANILLA_ISO}
     * projection, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT = OutputOptions.defaults();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default 2D {@link ImageLayer} stack (skin, overlay, armor) before it
     * runs, letting callers splice custom layers relative to the {@link PlayerSlot2D} slots.
     * Defaults to {@linkplain UnaryOperator#identity() identity}. Only consulted by the 2D path.
     */
    private final @NotNull UnaryOperator<LayerStack<ImageLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Transform applied to the default 3D {@link GeometryLayer} stack (body, armor, cape) before it
     * runs, letting callers splice custom layers relative to the {@link PlayerSlot3D} slots.
     * Defaults to {@linkplain UnaryOperator#identity() identity}. Only consulted by the 3D path.
     */
    private final @NotNull UnaryOperator<LayerStack<GeometryLayer>> geometryLayerDecorator = UnaryOperator.identity();

    /**
     * The default player options - a 3D {@linkplain Type#SKULL skull} with the neutral
     * {@link #getOutput() render} frame, overlay layer on, no armor or cape, over a
     * {@linkplain Background#TRANSPARENT transparent} background.
     *
     * @return the default options
     */
    public static @NotNull PlayerOptions defaults() {
        return builder().build();
    }

    /**
     * Which body parts to render, and the vanilla lattice they are seated in.
     *
     * <p>Each scope names the {@link PlayerLattice} its parts, scale and boxes are read from -
     * {@link #SKULL} is one 8x8 head, {@link #BUST} is 20 by 16 from the torso's floor to the head's
     * ceiling, {@link #FULL} the 32 by 16 of the whole vanilla body.
     */
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Type {

        /**
         * Head only, centred on the origin.
         */
        SKULL(PlayerLattice.SKULL),

        /**
         * Head, torso and arms.
         */
        BUST(PlayerLattice.BUST),

        /**
         * Full body - head, torso, arms and legs.
         */
        FULL(PlayerLattice.FULL);

        /** The vanilla pixel lattice this scope is seated in. */
        private final @NotNull PlayerLattice lattice;

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
