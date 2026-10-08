package lib.minecraft.renderer.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.image.Background;
import lib.minecraft.nbt.tag.CompoundTag;
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

    /**
     * The item-icon decoration inputs (tint, trim, leather/potion/firework colour, banner).
     */
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
     * Whether a layer or face whose texture no pack supplies, or whose texture file cannot be decoded,
     * or that names a texture reference resolving nowhere, draws the generated checkerboard, and an id
     * neither index knows, or a model an item definition's leaf names and no pack ships, draws the
     * missing model. On by default.
     * <p>
     * Turned off, each raises instead. A caller rendering a batch and catching per subject turns it off
     * to have an unrenderable one dropped rather than drawn.
     * <p>
     * It governs every texture this render reads - its layers and faces, and a trim overlay, a banner
     * pattern and an enchantment glint drawn on it - its subject lookup and its leaf model lookup. A
     * definition the loader refused, a {@code select} or {@code range_dispatch} that falls back to
     * nothing it declares, and a node whose type sits in a mod's namespace draw vanilla's missing item
     * model on either arm, since that is what vanilla draws for them rather than a stand-in for
     * something the pack lacks. An item whose definition is {@code minecraft:empty} or names a model
     * that declares nothing to draw, and a registered item or block that draws nothing, such as air,
     * draw no model on either arm, since that is what vanilla draws for them.
     */
    private final boolean substituteMissing = true;

    /**
     * The default output frame for an item icon - the GUI-item projection
     * ({@link Projection#VANILLA_GUI_ITEM}) with neutral output size, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT =
            OutputOptions.builder().projection(Projection.VANILLA_GUI_ITEM).build();

    /**
     * The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA.
     */
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
     * The item stack this render draws - a Minecraft 26.1 stack, {@link ItemContext#ofStack} - read by
     * CIT matching, the item definition's component tests, the dye tint, the damage bar and the
     * stack-count overlay. Defaults to {@link ItemContext#EMPTY}
     */
    private final @NotNull ItemContext context = ItemContext.EMPTY;

    /**
     * The item-definition evaluation context - the {@code items/*.json} dispatch-tree inputs (display
     * context, trim material, clock time, compass angle, the stack's components) resolved at render
     * time. Empty (default) resolves every input neutral at the display context the render type draws,
     * {@link Type#displayContext()}, under which a flat icon whose definition lands on its indexed
     * item's own model reuses that pipeline-baked item byte-for-byte. A present context is used as
     * given, its display context included, so a caller wanting a held render of the inventory model
     * supplies {@link ItemModelContext#gui()}.
     * <p>
     * Either way the walk reads the {@link #context} stack's components, and its item id, wherever this
     * context carries none of its own, so a caller supplying one only to set {@code using_item} still
     * walks its stack; a context's own components and item id win.
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
     * the item index carries, or of one whose item definition chooses the frame it draws, the two
     * drawing through one path; never for {@link Type#HELD_3D}, nor for an id drawn as its block's
     * own icon or as the missing square.
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
     * The item stack's component patch this render reads: the {@link #itemModel} context's own where it
     * carries one, else the {@link #context} stack's.
     * <p>
     * The dispatch walk and the {@code minecraft:dye} tint source read this one patch, so a caller
     * supplying a stack only through an item-model context's components tints from it as well.
     *
     * @return the component patch, empty where neither input carries one
     */
    public @NotNull Optional<CompoundTag> components() {
        return this.itemModel.flatMap(ItemModelContext::components).or(this.context::components);
    }

    /**
     * Resolves the item-definition evaluation context a render walks its dispatch tree at: the
     * {@link #itemModel} context where one was supplied, else every input neutral at the display
     * context the drawing type resolves at. Either one reads the {@link #components() component patch}
     * this render reads, and the {@link #context} stack's item id wherever it carries none of its own,
     * so a context supplied for another input still walks the stack, and a context's own components
     * and item id win.
     *
     * @param drawn the render type whose display context an absent context takes
     * @return the evaluation context the render resolves its item at
     */
    public @NotNull ItemModelContext itemModelAt(@NotNull Type drawn) {
        ItemModelContext supplied = this.itemModel
            .orElseGet(() -> ItemModelContext.gui().withDisplayContext(drawn.displayContext()));
        ItemModelContext patched = this.components().map(supplied::withComponents).orElse(supplied);

        return patched.itemId().isPresent() || this.context.itemId().isBlank() ? patched : patched.withItemId(this.context.itemId());
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
         * 2D GUI inventory icon, composed from an item model's layer sprites, or drawn from its
         * elements where it declares them. An id backing a block and carrying no item model draws the
         * missing square unless its item definition chooses the frame - a model the stack selects, or
         * a stand-in; its inventory icon is {@link #GUI_ICON}'s.
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
