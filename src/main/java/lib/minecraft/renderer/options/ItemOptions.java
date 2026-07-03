package lib.minecraft.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.asset.rule.ItemContext;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.ImageLayer;
import lib.minecraft.renderer.engine.compose.LayerSlot;
import lib.minecraft.renderer.engine.compose.LayerStack;
import lib.minecraft.renderer.engine.kit.BannerKit;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.engine.kit.TrimKit;
import lib.minecraft.renderer.request.ArmorTrim;
import lib.minecraft.renderer.request.BannerLayer;
import lib.minecraft.renderer.request.DyeColor;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@link ItemRenderer ItemRenderer} invocation.
 *
 * <p>Covers two output flavours plus the item-side decorations that vanilla composes onto
 * the GUI icon:
 * <ul>
 *   <li><b>2D GUI icon</b> - the inventory tile a caller sees at {@code 16x16} logical
 *       pixels, scaled to {@link #getOutputSize outputSize}. Supports the full item overlay
 *       stack: durability bar, stack count, enchantment glint, leather dye tint, banner
 *       pattern composite, armor trim palette permutation.</li>
 *   <li><b>3D held-item view</b> - the model rendered at the vanilla
 *       {@code display.thirdperson_righthand} pose (or a caller-supplied
 *       rotation). Used by atlas tools and held-item previews.</li>
 * </ul>
 *
 * <p><b>Vanilla-pattern composition.</b> Banner layers, armor trim, dye colour, and item
 * context inputs ({@link ItemContext}) all flow through to the matching
 * {@link lib.minecraft.renderer.engine.kit kit} composition step. The renderer itself stays
 * thin - all texture pairing logic lives in
 * {@link BannerKit BannerKit},
 * {@link TrimKit TrimKit}, and
 * {@link GlintKit GlintKit}.
 *
 * @see lib.minecraft.renderer.ItemRenderer
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class ItemOptions {

    /**
     * Namespaced item id to render, e.g. {@code "minecraft:diamond_sword"}. Empty (default)
     * resolves to no item
     */
    @lombok.Builder.Default
    private final @NotNull String itemId = "";

    /**
     * Render type - 2D GUI icon or 3D held-item view
     */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.GUI_2D;

    /**
     * When {@code true}, compose an enchantment glint on top of the rendered item. Superseded by
     * {@link #glintOverride} when that is present. Animated unless {@link #animateGlint} is off.
     */
    @lombok.Builder.Default
    private final boolean enchanted = false;

    /**
     * Forces the glint on ({@code true}) or off ({@code false}), overriding the item's intrinsic
     * {@code alwaysGlinted} flag and {@link #enchanted}. Empty leaves the default behaviour. Set to
     * {@code false} to obtain the pre-glint base icon a glint-parity harness composites its own
     * deterministic animated schedule onto.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Boolean> glintOverride = Optional.empty();

    /**
     * Optional ARGB tint applied to colour-overlay items (leather armour, spawn eggs). Empty
     * (default) uses the item's intrinsic tint.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> tintColor = Optional.empty();

    /**
     * The armor slot whose trim pattern to composite on top of the base item layers. When both
     * this and {@link #trimColor} are present, the renderer resolves the trim texture via
     * paletted permutation and composites it as an overlay.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorTrim.Slot> trimSlot = Optional.empty();

    /**
     * The trim colour that selects the palette for the trim overlay. Use the {@code _DARKER}
     * variants when the trim material matches the armor material (e.g.
     * {@link ArmorTrim.Color#IRON_DARKER} for an iron trim on iron armor) so the pattern stays
     * visible. Ignored when {@link #trimSlot} is absent.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorTrim.Color> trimColor = Optional.empty();

    /**
     * ARGB override colour for the leather-armour tint layer. Empty (default) uses vanilla's
     * default leather colour.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> leatherColor = Optional.empty();

    /**
     * ARGB override colour for potion contents (the liquid overlay). Empty (default) uses the
     * effect's registered colour.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> potionColor = Optional.empty();

    /**
     * ARGB override colour for firework stars. Empty (default) uses the star's own colour.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> fireworkColor = Optional.empty();

    /**
     * The base dye colour (banner field / shield base) for banner and shield items. Defaults
     * to white when absent.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<DyeColor> baseDye = Optional.empty();

    /**
     * Ordered list of pattern layers composited on top of the base dye for banner and shield
     * items. Empty for plain banners / shields.
     */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<BannerLayer> bannerLayers = Concurrent.newList();

    /**
     * Target frame rate for animated output in frames per second; drives glint scroll speed and
     * loop period
     */
    @lombok.Builder.Default
    private final int framesPerSecond = 30;

    /**
     * Whether a glinted item (intrinsically-foil or {@link #enchanted}) emits the animated scrolling
     * foil. When {@code false} the renderer composites a single static frame-0 glint instead - the
     * atlas sets this so glinted tiles never promote the whole grid to an animated output.
     */
    @lombok.Builder.Default
    private final boolean animateGlint = true;

    /**
     * Whether to render the vanilla-style durability bar when the item has taken damage. On by
     * default; damage level is read from {@link #context}
     */
    @lombok.Builder.Default
    private final boolean showDamageBar = true;

    /**
     * Output image dimensions in pixels (square), defaulting to {@link Renderer#DEFAULT_OUTPUT_SIZE}
     */
    @lombok.Builder.Default
    private final int outputSize = Renderer.DEFAULT_OUTPUT_SIZE;

    /**
     * Render-time item context used by CIT matching, the damage bar, and the stack-count overlay.
     * Defaults to {@link ItemContext#EMPTY}
     */
    @lombok.Builder.Default
    private final @NotNull ItemContext context = ItemContext.EMPTY;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default GUI icon {@link ImageLayer} stack before it runs, letting
     * callers splice custom layers relative to the built-in {@link Slot} slots, or replace
     * the stack entirely. Defaults to {@linkplain UnaryOperator#identity() identity} - the built-in
     * stack unchanged. Only consulted for {@link Type#GUI_2D} renders.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<ImageLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Graphical projection for the {@link Type#HELD_3D} render. Defaults to
     * {@link Projection#VANILLA_GUI_ITEM} - byte-identical to the shipped render. The held-item
     * pose itself comes from the model's {@code thirdperson_righthand} display transform; this
     * projection supplies the camera lens (orthographic flatten). Selecting another re-poses the
     * lens; not consulted by the {@link Type#GUI_2D} path.
     */
    @lombok.Builder.Default
    private final @NotNull Projection projection = Projection.VANILLA_GUI_ITEM;

    /**
     * View-facing reflection applied to the {@link #getProjection() projection}. Defaults to
     * {@link Facing#DEFAULT} (no reflection). For the 3D held-item path the pose comes from the model's
     * {@code display} transform, so only an {@linkplain lib.minecraft.renderer.engine.camera.Lens.Kind#OBLIQUE
     * oblique} lens's depth-shear is affected - inert for the default perspective GUI item. Not consulted
     * by the {@link Type#GUI_2D} path.
     */
    @lombok.Builder.Default
    private final @NotNull Facing facing = Facing.DEFAULT;

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull ItemOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default options
     */
    public static @NotNull ItemOptions defaults() {
        return builder().build();
    }

    /**
     * Render-order slots for the 2D GUI icon {@code ImageLayer} stack: base sprite/banner/shield,
     * then trim, damage bar, and stack-count decorations.
     */
    public enum Slot implements LayerSlot {

        /** Base sprite/layer stack, or the shield / banner dispatch. */
        BASE,
        /** Armor-trim overlay composited over the base. */
        TRIM,
        /** Durability damage bar. */
        DAMAGE_BAR,
        /** Stack-count badge. */
        STACK_COUNT;

        /** {@inheritDoc} */
        @Override
        public int order() {
            return ordinal();
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String id() {
            return name();
        }
    }

    /**
     * The supported render types for {@link ItemRenderer}.
     */
    public enum Type {

        /**
         * 3D view as the item appears when held in a player's hand, at the vanilla
         * {@code display.thirdperson_righthand} pose.
         */
        HELD_3D,

        /**
         * 2D flat GUI inventory icon.
         */
        GUI_2D

    }

}
