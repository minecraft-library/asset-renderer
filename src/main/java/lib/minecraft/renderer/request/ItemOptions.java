package lib.minecraft.renderer.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.image.Background;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.bake.texture.BannerKit;
import lib.minecraft.renderer.bake.texture.GlintKit;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.request.slot.ItemSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@link ItemRenderer ItemRenderer} invocation.
 *
 * <p>Covers three output flavours plus the item-side decorations that vanilla composes onto
 * the GUI icon:
 * <ul>
 *   <li><b>2D GUI icon</b> ({@link Type#GUI_2D}) - the inventory tile a caller sees at
 *       {@code 16x16} logical pixels, scaled to {@link OutputOptions#getCanvasSize() canvasSize}.
 *       Supports the full item overlay stack: durability bar, stack count, enchantment glint,
 *       leather dye tint, banner pattern composite, armor trim palette permutation.</li>
 *   <li><b>3D held-item view</b> ({@link Type#HELD_3D}) - the model rendered at the vanilla
 *       {@code display.thirdperson_righthand} pose, its item-definition tree resolved at that
 *       display context. Used by held-item previews.</li>
 *   <li><b>Faithful inventory icon</b> ({@link Type#GUI_ICON}) - what a GUI slot shows, picked per
 *       id: an id the item index carries draws the 2D GUI icon, overlays included, and one it does
 *       not carry but a block backs draws the isometric block render, which carries none of them.
 *       Used by the menu and the atlas for their icons.</li>
 * </ul>
 *
 * <p><b>Vanilla-pattern composition.</b> Banner layers, armor trim, dye colour, and item
 * context inputs ({@link ItemContext}) all flow through to the matching
 * kit's composition step. The renderer itself stays
 * thin - all texture pairing logic lives in
 * {@link BannerKit BannerKit},
 * {@link TrimKit TrimKit}, and
 * {@link GlintKit GlintKit}.
 *
 * @see lib.minecraft.renderer.ItemRenderer
 */
@Getter
@ClassBuilder
public class ItemOptions implements RenderOptions {

    /**
     * Namespaced item id to render, e.g. {@code "minecraft:diamond_sword"}. Empty (default)
     * resolves to no item
     */
    private final @NotNull String itemId = "";

    /**
     * Render type - the {@link Type#GUI_2D 2D GUI icon}, the {@link Type#HELD_3D 3D held-item view}
     * or the {@link Type#GUI_ICON faithful inventory icon}
     */
    private final @NotNull Type type = Type.GUI_2D;

    /**
     * When {@code true}, compose an enchantment glint on top of the rendered item. Superseded by
     * {@link #glintOverride} when that is present. Animated unless {@link #animateGlint} is off.
     */
    private final boolean enchanted = false;

    /**
     * Forces the glint on ({@code true}) or off ({@code false}), overriding the item's intrinsic
     * {@code alwaysGlinted} flag and {@link #enchanted}. Empty leaves the default behaviour. Set to
     * {@code false} to obtain the pre-glint base icon a glint-parity harness composites its own
     * deterministic animated schedule onto.
     */
    private final @NotNull Optional<Boolean> glintOverride = Optional.empty();

    /** The item-icon decoration inputs (tint, trim, leather/potion/firework colour, banner). */
    private final @NotNull DecorationOptions decoration = DecorationOptions.defaults();

    /**
     * Target frame rate for animated output in frames per second; drives glint scroll speed and
     * loop period
     */
    private final int framesPerSecond = 30;

    /**
     * Whether a glinted item (intrinsically-foil or {@link #enchanted}) emits the animated scrolling
     * foil. When {@code false} the renderer composites a single static frame-0 glint instead - the
     * atlas sets this so glinted tiles never promote the whole grid to an animated output.
     */
    private final boolean animateGlint = true;

    /**
     * Whether to render the vanilla-style durability bar when the item has taken damage. On by
     * default; damage level is read from {@link #context}
     */
    private final boolean showDamageBar = true;

    /**
     * Whether a layer or face whose texture no pack supplies draws the generated checkerboard, and an
     * id neither index carries draws the missing-model cube. On by default.
     * <p>
     * Turned off, both raise instead - which is what every renderer outside the block and item paths
     * already does. A caller rendering a batch and catching per subject turns it off to have an
     * unrenderable one dropped rather than drawn.
     * <p>
     * It governs this renderer's own layer and face lookups and its subject lookup, and nothing beyond
     * them. A trim overlay, a banner pattern and an enchantment glint each ask the pack for themselves
     * and skip what it does not supply, so an icon missing one of those is drawn without it either
     * way - untrimmed, or unglinted, rather than refused.
     */
    private final boolean substituteMissing = true;

    /**
     * The default output frame for an item icon - the GUI-item projection
     * ({@link Projection#VANILLA_GUI_ITEM}) with neutral output size, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT =
            OutputOptions.builder().projection(Projection.VANILLA_GUI_ITEM).build();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

    /**
     * Texture-animation timeline for animated item textures (a resource-pack concern - vanilla 26.1
     * ships zero {@code textures/item} sidecars). Defaults to a single static frame
     * ({@link AnimationOptions#defaults()}); item resolution is tick-aware unconditionally, so an
     * animated pack texture shows frame 0 when static instead
     * of the squashed raw strip, and plays its flipbook when the caller opts in with
     * {@code frameCount > 1}.
     */
    private final @NotNull AnimationOptions animation = AnimationOptions.defaults();

    /**
     * Render-time item context used by CIT matching, the damage bar, and the stack-count overlay.
     * Defaults to {@link ItemContext#EMPTY}
     */
    private final @NotNull ItemContext context = ItemContext.EMPTY;

    /**
     * The item-definition evaluation context - the {@code items/*.json} dispatch-tree inputs (display
     * context, trim material, dye colour, clock time, compass angle) resolved at render time.
     * Empty (default) resolves every input neutral at the display context the render type draws,
     * {@link Type#displayContext()}, under which a flat icon reuses the pipeline-baked item
     * byte-for-byte. A present context is used as given, its display context included, so a caller
     * wanting a held render of the inventory model supplies {@link ItemModelContext#gui()}.
     */
    private final @NotNull Optional<ItemModelContext> itemModel = Optional.empty();

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default GUI icon {@link ImageLayer} stack before it runs, letting
     * callers splice custom layers relative to the built-in {@link ItemSlot} slots, or replace
     * the stack entirely. Defaults to {@linkplain UnaryOperator#identity() identity} - the built-in
     * stack unchanged. Consulted for a {@link Type#GUI_2D} or {@link Type#GUI_ICON} render of an id
     * the item index carries, the two drawing through one path; never for {@link Type#HELD_3D}, nor
     * for an id the item index does not carry.
     */
    private final @NotNull UnaryOperator<LayerStack<ImageLayer>> layerDecorator = UnaryOperator.identity();

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
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Type {

        /**
         * 3D view as the item appears when held in a player's hand, at the vanilla
         * {@code display.thirdperson_righthand} pose, with a {@code display_context} select resolved
         * at the same context. A block-backed id draws the block model its item definition names. A
         * tree whose held case is a special renderer, the trident's, draws the item's own indexed
         * model.
         */
        HELD_3D(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND),

        /**
         * 2D flat GUI inventory icon, composed from an item model's layer sprites. An id backing a
         * block and carrying no item model draws the missing square; its inventory icon is
         * {@link #GUI_ICON}'s.
         */
        GUI_2D(ItemModelContext.DISPLAY_CONTEXT_GUI),

        /**
         * Faithful inventory icon - the representation a GUI slot shows, auto-selected per id: a flat
         * {@code item/generated} sprite (through the {@link #GUI_2D} path), a 3D block icon, or a
         * block-entity render (both through the isometric block renderer). Lets a caller request the
         * vanilla inventory look without choosing the geometry mode.
         */
        GUI_ICON(ItemModelContext.DISPLAY_CONTEXT_GUI);

        /**
         * The {@code minecraft:display_context} key this type draws at - the case a
         * {@code display_context} select resolves when the caller supplies no item model context, and
         * for {@link #HELD_3D} the display slot its pose is read from.
         */
        private final @NotNull String displayContext;

    }

}
