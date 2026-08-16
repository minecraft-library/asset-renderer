package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.image.Background;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.ArmorKit;
import lib.minecraft.renderer.engine.kit.TrimKit;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.option.slot.PlayerSlot2D;
import lib.minecraft.renderer.option.slot.PlayerSlot3D;
import lib.minecraft.renderer.option.spec.ArmorOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.option.spec.SkinOptions;
import lib.minecraft.renderer.option.spec.TextureOptions;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.tensor.Box;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * the renderer falls back to the registered {@code minecraft:entity/steve} texture. The URL path
 * extracts the URL's trailing path segment (the texture hash) and streams the PNG through the
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
     * Which body parts to render, and the frame they are seated in.
     * <p>
     * A scope names its own parts, in the order they are drawn, and the scale one skin pixel spans in
     * its frame. Its pixel extent is then the union of those parts' own boxes rather than a second
     * statement of the same numbers - {@link #SKULL} is one 8x8 head, {@link #BUST} is 20 by 16 from
     * the torso's floor to the head's ceiling, {@link #FULL} the 32 by 16 of the whole vanilla body.
     * <p>
     * <p>Both layouts fall out of the same two inputs and neither is tabulated: {@link #boxes} seats
     * each part in this scope's own model frame for the 3D render, and {@link #layout2D} places each
     * part's canvas rectangle for the 2D one. A scope's four union integers and each part's own pixel
     * box are all either of them reads, which is why they are answered here rather than at a renderer.
     * <p>
     * {@link #SKULL} is the one scope that {@link HumanoidPart#centred centres} its part instead of
     * seating it in the body lattice, which is why it also carries a different scale: an 8-pixel head
     * spanning one unit is {@code 0.125} per pixel against the body's {@code 0.03}.
     */
    public enum Type {

        /**
         * Head only, centred on the origin.
         */
        SKULL(0.125f, true, HumanoidPart.HEAD),

        /**
         * Head, torso and arms.
         */
        BUST(0.03f, false,
            HumanoidPart.HEAD, HumanoidPart.TORSO, HumanoidPart.RIGHT_ARM, HumanoidPart.LEFT_ARM),

        /**
         * Full body - head, torso, arms and legs.
         */
        FULL(0.03f, false,
            HumanoidPart.HEAD, HumanoidPart.TORSO, HumanoidPart.RIGHT_ARM, HumanoidPart.LEFT_ARM,
            HumanoidPart.RIGHT_LEG, HumanoidPart.LEFT_LEG);

        /**
         * The model units one skin pixel spans in this scope's frame.
         */
        private final float unitsPerPixel;

        /**
         * Whether this scope centres its part on the origin rather than seating it in the body
         * lattice.
         */
        private final boolean centred;

        /**
         * The parts this scope draws, in draw order.
         */
        @Getter(style = NamingStyle.FLUENT)
        private final @NotNull List<HumanoidPart> parts;

        private final int minPixelX;
        private final int maxPixelX;
        private final int minPixelY;
        private final int maxPixelY;

        /**
         * Each part this scope draws, in the box this scope seats it in - the same answers
         * {@link #boxOf} gives, in an {@link EnumMap} so the iteration order is ordinal order.
         *
         * <p>Tabulated once per constant rather than assembled per render, because a scope's boxes
         * are a function of the scope alone.
         */
        @Getter(style = NamingStyle.FLUENT)
        private final @NotNull Map<HumanoidPart, Box> boxes;

        Type(float unitsPerPixel, boolean centred, @NotNull HumanoidPart @NotNull ... parts) {
            this.unitsPerPixel = unitsPerPixel;
            this.centred = centred;
            this.parts = List.of(parts);

            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (HumanoidPart part : parts) {
                minX = Math.min(minX, part.minPixelX());
                maxX = Math.max(maxX, part.maxPixelX());
                minY = Math.min(minY, part.minPixelY());
                maxY = Math.max(maxY, part.maxPixelY());
            }
            this.minPixelX = minX;
            this.maxPixelX = maxX;
            this.minPixelY = minY;
            this.maxPixelY = maxY;

            Map<HumanoidPart, Box> seated = new EnumMap<>(HumanoidPart.class);
            for (HumanoidPart part : parts) seated.put(part, boxOf(part));
            this.boxes = Collections.unmodifiableMap(seated);
        }

        /**
         * This scope's box for one of its parts - centred on the origin for a scope that draws a part
         * alone, seated in the body lattice otherwise.
         *
         * @param part the part to place
         * @return the part's box in this scope's frame
         */
        public @NotNull Box boxOf(@NotNull HumanoidPart part) {
            return this.centred ? part.centred(this.unitsPerPixel) : part.box(this.unitsPerPixel);
        }

        /**
         * This scope's 2D front-facing layout on a square canvas - each part it draws, and the canvas
         * rectangle that part is blitted into.
         *
         * <p>The scale fills the canvas height with the scope's own pixel height and the body is
         * centred horizontally. The front view then lays the lattice out directly: a part's canvas X is
         * how far its left edge sits from the scope's own left edge, and its canvas Y is how far its
         * <em>top</em> edge sits below the scope's top - the one inversion, because the lattice counts
         * Y upward and a canvas counts it down. The rectangle's extent is the part's own.
         *
         * <p>Answered here because it reads nothing but this scope's four union integers and each
         * part's own pixel box, which is the same table {@link #boxes} is derived from.
         *
         * @param canvasSize the square canvas edge in pixels
         * @return the parts in draw order, each with its canvas rectangle
         */
        public @NotNull List<BodyPart2D> layout2D(int canvasSize) {
            int scale = canvasSize / bodyHeight();
            int offsetX = (canvasSize - bodyWidth() * scale) / 2;
            List<BodyPart2D> layout = new ArrayList<>(this.parts.size());

            for (HumanoidPart part : this.parts)
                layout.add(new BodyPart2D(part,
                    offsetX + (part.minPixelX() - this.minPixelX) * scale,
                    (this.maxPixelY - part.maxPixelY()) * scale,
                    part.pixelWidth() * scale,
                    part.pixelHeight() * scale));

            return List.copyOf(layout);
        }

        /**
         * Pixel width of the 2D body composite, before output scaling - the union pixel width of this
         * scope's parts.
         */
        private int bodyWidth() {
            return this.maxPixelX - this.minPixelX;
        }

        /**
         * Pixel height of the 2D body composite, before output scaling - the union pixel height of this
         * scope's parts.
         */
        private int bodyHeight() {
            return this.maxPixelY - this.minPixelY;
        }

        /**
         * One body part and the canvas rectangle it is drawn in, in output pixels with Y counted
         * downward.
         *
         * <p>The five travel together at every site that draws the 2D composite - the skin pass, the
         * overlay pass, and each armour slot's sheet and trim - so they travel as one value rather than
         * as a part plus four loose integers.
         *
         * @param part the body part to crop and blit
         * @param x left edge of the destination rectangle
         * @param y top edge of the destination rectangle
         * @param w destination width in pixels
         * @param h destination height in pixels
         */
        public record BodyPart2D(@NotNull HumanoidPart part, int x, int y, int w, int h) {}

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
