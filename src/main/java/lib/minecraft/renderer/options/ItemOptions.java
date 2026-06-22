package lib.minecraft.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.appearance.ArmorTrim;
import lib.minecraft.renderer.appearance.BannerLayer;
import lib.minecraft.renderer.appearance.DyeColor;
import lib.minecraft.renderer.kit.BannerKit;
import lib.minecraft.renderer.kit.GlintKit;
import lib.minecraft.renderer.kit.TrimKit;
import lib.minecraft.renderer.pipeline.pack.ItemContext;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

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
 *       {@link #getRotation rotation}). Used by atlas tools and held-item previews.</li>
 * </ul>
 *
 * <p><b>Vanilla-pattern composition.</b> Banner layers, armor trim, dye colour, and item
 * context inputs ({@link ItemContext}) all flow through to the matching
 * {@link lib.minecraft.renderer.kit kit} composition step. The renderer itself stays
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
     * Namespaced item id to render, e.g. {@code "minecraft:diamond_sword"}
     */
    @lombok.Builder.Default
    private final @NotNull String itemId = "";

    /**
     * Render type - 2D GUI icon or 3D held-item view
     */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.GUI_2D;

    /**
     * When true, compose an animated enchantment glint on top of the rendered item.
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
     * Optional ARGB tint applied to colour-overlay items (leather armour, spawn eggs).
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
     * Override colour for leather armour pieces.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> leatherColor = Optional.empty();

    /**
     * Override colour for potion contents.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> potionColor = Optional.empty();

    /**
     * Override colour for firework stars.
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
     * Target frame rate for animated output; drives glint scroll speed and loop period.
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
     * Whether to render the vanilla-style durability bar when the item has taken damage.
     */
    @lombok.Builder.Default
    private final boolean showDamageBar = true;

    /**
     * Output image dimensions in pixels (square)
     */
    @lombok.Builder.Default
    private final int outputSize = Renderer.DEFAULT_OUTPUT_SIZE;

    /**
     * The render-time item context used by CIT matching, the damage bar, and stack count overlay.
     */
    @lombok.Builder.Default
    private final @NotNull ItemContext context = ItemContext.EMPTY;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    public @NotNull ItemOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull ItemOptions defaults() {
        return builder().build();
    }

    /**
     * The supported render types for {@code ItemRenderer}.
     */
    public enum Type {

        /**
         * 3D view as the item appears when held in a player's hand.
         */
        HELD_3D,

        /**
         * 2D flat GUI icon.
         */
        GUI_2D

    }

}
