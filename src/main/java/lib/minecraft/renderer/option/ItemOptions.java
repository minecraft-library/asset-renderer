package lib.minecraft.renderer.option;

import dev.simplified.image.Background;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.asset.rule.ItemContext;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.BannerKit;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.engine.kit.TrimKit;
import lib.minecraft.renderer.option.slot.ItemSlot;
import lib.minecraft.renderer.option.spec.ItemDecoration;
import lib.minecraft.renderer.option.spec.OutputOptions;
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
 *       pixels, scaled to {@link OutputOptions#getCanvasSize() canvasSize}. Supports the full item overlay
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
public class ItemOptions implements RenderOptions {

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

    /** The item-icon decoration inputs (tint, trim, leather/potion/firework colour, banner). */
    @lombok.Builder.Default
    private final @NotNull ItemDecoration decoration = ItemDecoration.defaults();

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
     * The default output frame for an item icon - the GUI-item projection
     * ({@link Projection#VANILLA_GUI_ITEM}) with neutral output size, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT =
            OutputOptions.builder().projection(Projection.VANILLA_GUI_ITEM).build();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    @lombok.Builder.Default
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

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
     * callers splice custom layers relative to the built-in {@link ItemSlot} slots, or replace
     * the stack entirely. Defaults to {@linkplain UnaryOperator#identity() identity} - the built-in
     * stack unchanged. Only consulted for {@link Type#GUI_2D} renders.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<ImageLayer>> layerDecorator = UnaryOperator.identity();

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
