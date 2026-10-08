package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelData.GuiLight;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.bake.gui.ItemStackKit;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.bake.mesh.ShieldKit;
import lib.minecraft.renderer.bake.texture.BannerKit;
import lib.minecraft.renderer.bake.texture.GlintKit;
import lib.minecraft.renderer.bake.texture.ItemTint;
import lib.minecraft.renderer.bake.texture.TextureRefusal;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.GlintPolicy;
import lib.minecraft.renderer.content.index.ItemModelDispatch.FrameItem;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.diagnostic.Substitutions;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.draw.DrawPart;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.mesh.MissingMesh;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.request.AnimationOptions;
import lib.minecraft.renderer.request.BlockOptions;
import lib.minecraft.renderer.request.DecorationOptions;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.request.OutputOptions;
import lib.minecraft.renderer.request.slot.ItemSlot;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Renders an {@link Item} as a 2D GUI icon, a held 3D view, or the faithful inventory icon by
 * dispatching on {@link ItemOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;ItemOptions&gt;}:
 * <ul>
 * <li>{@link Gui2D} composes layered flat sprites with optional damage bar, stack count, and
 * glint animation. Each {@code layerN} is multiplied by its
 * {@link LayerTint} (leather dye, potion colour, firework colour - from the item definition's
 * {@code model.tints[]}) or the caller's {@code tintColor}; a model built from elements draws those
 * elements, posed by its {@code display.gui} and lit as its {@code gui_light} says; the shield routes
 * through a 3D {@link ShieldKit} render and banners through {@link BannerKit}.</li>
 * <li>{@link Held3D} draws an item-index id from its item model - element boxes built through
 * {@link BlockGeometryKit#buildFromElements} where the model declares them, a thin textured slab
 * carrying the tinted layer stack otherwise - and a block-backed id whose item definition names its
 * block model from that model's elements, or the model its stack chooses. Every branch routes
 * through {@link Rasterizer} with the drawn model's {@code thirdperson_righthand} display transform
 * applied, a composite's layers each at its own model's and all of them in one depth pass.</li>
 * <li>{@link GuiIcon} renders the faithful inventory icon by index membership: an id with a flat
 * item entry through {@link Gui2D}, a block-backed id with no flat icon (plain blocks and
 * block-entities alike) through the isometric {@link BlockRenderer}, whose faces take the item
 * definition's tints where the icon is the block's own model. Both branches reuse an existing
 * renderer.</li>
 * </ul>
 * What a frame draws is {@link ItemModelDispatch}'s answer, a {@link FrameItem}: the model the item
 * definition names, the missing model for a leaf naming one no pack ships, vanilla's missing item model
 * for a definition the loader refused, nothing for an empty branch, or each of those a
 * {@code composite} lands on, one over another in order. The colour its layers carry is
 * {@link ItemTint}'s, and both sub-renderers ask the same pair, so the two paths agree on an item
 * without either owning the lookup. The item stack the caller hands over in
 * {@link ItemOptions#getContext()} reaches every one of them - CIT, the dispatch walk and the dye tint.
 */
public final class ItemRenderer implements Renderer<ItemOptions> {

    /**
     * The 2D GUI icon sub-renderer.
     */
    private final @NotNull Gui2D gui2D;

    /**
     * The held 3D item sub-renderer.
     */
    private final @NotNull Held3D held3D;

    /**
     * The faithful inventory-icon sub-renderer ({@link ItemOptions.Type#GUI_ICON}).
     */
    private final @NotNull GuiIcon guiIcon;

    /**
     * Constructs a new {@code ItemRenderer} bound to the given renderer context, eagerly building the
     * three sub-renderers so each {@link #render} call is a plain dispatch.
     *
     * @param context the renderer context supplying pack / model / texture lookups
     */
    public ItemRenderer(@NotNull RendererContext context) {
        this.gui2D = new Gui2D(context);
        this.held3D = new Held3D(context);
        this.guiIcon = new GuiIcon(context, this.gui2D);
    }

    /**
     * Dispatches to the {@link Gui2D}, {@link Held3D}, or {@link GuiIcon} sub-renderer keyed by
     * {@link ItemOptions#getType()}, then composites the result over the options'
     * {@link ItemOptions#getBackground() background}.
     *
     * @param options the item render options
     * @return the rendered icon, composited over the requested background
     */
    @Override
    public @NotNull ImageData render(@NotNull ItemOptions options) {
        ImageData rendered = switch (options.getType()) {
            case GUI_2D -> this.gui2D.render(options);
            case HELD_3D -> this.held3D.render(options);
            case GUI_ICON -> this.guiIcon.render(options);
        };
        return options.getBackground().composite(rendered);
    }

    /**
     * Answers what an item render draws for an id neither index knows, or refuses where the caller
     * turned the substitution off. An id an index knows as one that draws nothing, such as air, never
     * reaches it: it draws the empty frame on either arm, which is what vanilla draws for it.
     * <p>
     * All three entry points decide that here, and a leaf naming a model no pack ships decides it at
     * {@link #missingItem(ItemOptions, FrameItem.MissingModel, Supplier)}; both read the flag through
     * {@link #substitute}, so the subject and leaf lookups read it in one place. Both the picture and
     * the noun stay the caller's: a slot's flat square differs from a held cube, and the flat icon
     * looks in the item index alone where the held view and the faithful icon look in both, so each
     * names what it looked for.
     *
     * @param options the caller's options, supplying the id and the substitution flag
     * @param subject the noun naming what was looked for, as the refusal words it
     * @param drawn the picture to draw where the substitution is on
     * @param <T> the picture's type
     * @return the drawn picture
     * @throws RenderException where the caller turned the substitution off
     */
    static <T> @NotNull T missingItem(
        @NotNull ItemOptions options, @NotNull String subject, @NotNull Supplier<T> drawn) {
        return substitute(options,
            () -> new RenderException("No %s registered for id '%s'", subject, options.getItemId()),
            () -> Substitutions.model(options.getItemId()), drawn);
    }

    /**
     * Answers what a frame draws whose item definition's leaf names a model no pack ships - the
     * missing model, reported once per model id - or refuses where the caller turned the substitution
     * off. A time-driven definition can miss on one frame and not another, and any frame that misses
     * refuses the render.
     *
     * @param options the caller's options, supplying the item id and the substitution flag
     * @param miss the frame whose leaf missed
     * @param drawn the picture to draw where the substitution is on
     * @param <T> the picture's type
     * @return the drawn picture
     * @throws RenderException where the caller turned the substitution off
     */
    static <T> @NotNull T missingItem(
        @NotNull ItemOptions options, @NotNull FrameItem.MissingModel miss, @NotNull Supplier<T> drawn) {
        return substitute(options,
            () -> new RenderException("No model registered for id '%s' (named by item '%s')", miss.modelId(), options.getItemId()),
            () -> Substitutions.leafModel(miss.modelId(), options.getItemId()), drawn);
    }

    /**
     * Reads the substitution flag for the item lookups: draws and reports where it is on, refuses
     * where the caller turned it off.
     *
     * @param options the caller's options, supplying the substitution flag
     * @param refusal the exception the refusing arm raises
     * @param report the report the substituting arm makes
     * @param drawn the picture the substituting arm draws
     * @param <T> the picture's type
     * @return the drawn picture
     */
    private static <T> @NotNull T substitute(
        @NotNull ItemOptions options, @NotNull Supplier<RenderException> refusal,
        @NotNull Runnable report, @NotNull Supplier<T> drawn
    ) {
        if (!options.isSubstituteMissing()) throw refusal.get();

        report.run();
        return drawn.get();
    }

    /**
     * Builds the glint finish a render's strip ends on, bound to its first frame: the frame's item and
     * the CIT decision where vanilla draws the stack's glint over that frame, and no glint where it
     * sets no foil - over its missing item model and an empty branch.
     *
     * @param context the renderer context the glint texture resolves against
     * @param frame the strip's first frame
     * @param options the caller's options, supplying the glint override, enchantment and timing
     * @param cit the render's single CIT walk result
     * @return the glint finish
     */
    private static @NotNull GlintKit.Foil frameGlint(
        @NotNull RendererContext context, @NotNull FrameItem frame, @NotNull ItemOptions options, @NotNull CitResult cit) {
        return ItemTint.itemGlint(context, frame.item(), options, frame.glints() ? cit.glint() : GlintPolicy.SUPPRESSED);
    }

    /**
     * Calculates the item-definition tints a block-backed id's own block model takes, walked at the
     * display context the drawing type resolves at and indexed by tintindex - what vanilla's item
     * model calculates before it gathers its quads, in every display context alike. Where the
     * definition declares none, the caller's {@link DecorationOptions#getTintColor()} stands at index
     * 0, by {@link ItemTint#layerTints(RendererContext, ConcurrentList, ItemOptions)}.
     * <p>
     * The walk proceeds without the stack, its components and its item id alike. The block's own model
     * is what an id draws where the stack chooses no branch, which walks alike without it, and where
     * the branch it chooses is one the block route stands in for - a special leaf - so that branch's
     * tints belong to a model not drawn.
     *
     * @param context the renderer context the tree and the tints resolve against
     * @param options the caller's options, supplying the id, the evaluation context and the overrides
     * @param drawn the render type whose display context an absent evaluation context takes
     * @return the calculated tints, empty where neither the definition nor the caller names one
     */
    static int @NotNull [] definitionTints(
        @NotNull RendererContext context, @NotNull ItemOptions options, ItemOptions.@NotNull Type drawn) {
        ConcurrentList<LayerTint> tints = context.findItemTree(options.getItemId())
            .map(tree -> options.itemModelAt(drawn).withoutComponents().resolve(tree).tints())
            .orElseGet(Concurrent::newUnmodifiableList);
        return ItemTint.layerTints(context, tints, options);
    }

    /**
     * Renders the standard layered-sprite path for an item. Each {@code layerN} texture is
     * composited in order, multiplying in the layer's {@link ItemTint#resolveLayerTint resolved tint} -
     * the item-definition {@link LayerTint} (leather dye, potion colour, firework colour) when
     * present, otherwise the caller's {@link DecorationOptions#getTintColor()} on the tintindex-0 slot.
     * A tinted layer is multiplied at its native resolution then scaled up, so the tint covers the
     * full icon rather than a corner. Trim overlay textures are resolved via
     * {@link TrimKit#resolveFromTextureRef} so the renderer doesn't depend on material-specific
     * PNGs being shipped in the pack.
     * <p>
     * Each layer takes the shade the slot's light gives the face of vanilla's generated slab that points
     * at the viewer, through {@link #slotLit}: {@link GuiLight#FRONT}'s {@code ITEMS_FLAT} lights it in
     * full, so the tint alone applies, and {@link GuiLight#SIDE}'s {@code ITEMS_3D} shades it to
     * {@link Shading#ITEMS_3D_FACING}, folded into the tint in one rounding as vanilla's vertex colour
     * folds them.
     *
     * @param context the renderer context every layer texture is resolved against
     * @param buffer the slot's base-layer buffer
     * @param item the item the frame draws
     * @param options the caller's options, read for what an absent texture means and for the tint
     * @param cit the render's single CIT walk result
     * @param light the light the slot binds for the whole frame
     * @param tick the animation tick the layer textures are sampled at
     */
    static void renderStandardLayers(
        @NotNull RendererContext context,
        @NotNull PixelBuffer buffer,
        @NotNull Item item,
        @NotNull ItemOptions options,
        @NotNull CitResult cit,
        @NotNull GuiLight light,
        int tick
    ) {
        // Only the layer lookup below substitutes. The trim overlay resolves against the context itself,
        // where a palette the pack ships no file for is synthesised and an absent or unreadable one is
        // skipped rather than drawn or refused - which is what leaves the icon untrimmed instead of
        // checkered.
        RendererContext textures = options.isSubstituteMissing()
            ? context.withMissingTexture()
            : context;
        int size = options.getOutput().getCanvasSize();
        // The CIT walk ran once per render (shared with the glint decision); each layer resolves against
        // the result (layer0 -> texture, layerN -> texture.<name>), falling back to the model-bound id.
        // The empty-context vanilla path yields CitResult.NONE, so every layer passes through unchanged.
        int layerIndex = 0;
        while (true) {
            String layerKey = ItemTint.LAYER_TEXTURE_PREFIX + layerIndex;
            String textureRef = cit.textureFor(layerKey).map(ResourceId::id).orElse(item.textures().get(layerKey));
            if (textureRef == null || textureRef.isBlank()) break;

            if (TrimKit.isTrimTexture(textureRef)) {
                TrimKit.resolveFromTextureRef(context, textureRef)
                    .map(trim -> slotLit(trim, ColorMath.WHITE, light))
                    .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, size, size));
            } else {
                PixelBuffer layer = TextureRefusal.require(
                    Flipbook.atTick(textures.resolveTexture(textureRef), textures.findFlipbook(textureRef), tick), textureRef);
                int color = ItemTint.resolveLayerTint(context, item, layerIndex, options);
                buffer.blitScaled(slotLit(layer, color, light), 0, 0, size, size);
            }
            layerIndex++;
        }
    }

    /**
     * Lights one layer of a slot's sprites as the slot's light lights the face of vanilla's generated
     * slab that points at the viewer: {@link GuiLight#FRONT}'s {@code ITEMS_FLAT} in full, so the tint
     * alone applies, and {@link GuiLight#SIDE}'s {@code ITEMS_3D} at {@link Shading#ITEMS_3D_FACING},
     * folded into the tint in one rounding.
     *
     * @param layer the layer's texels at their native size
     * @param tint the layer's tint, {@link ColorMath#WHITE} for none
     * @param light the light the slot binds for the whole frame
     * @return the lit layer, the layer itself where the light leaves it as it is
     */
    private static @NotNull PixelBuffer slotLit(@NotNull PixelBuffer layer, int tint, @NotNull GuiLight light) {
        // Front light leaves the face the viewer sees at full strength, so the tint alone applies:
        // ColorMath.tint multiplies each texel by the colour (preserving alpha) and returns a fresh
        // buffer, which the caller's blitScaled composites over the prior layers - unlike blitTinted,
        // which blends against the destination and would blank an empty buffer.
        return switch (light) {
            case FRONT -> tint != ColorMath.WHITE ? ColorMath.tint(layer, tint) : layer;
            case SIDE -> Shading.apply(layer, tint, Shading.ITEMS_3D_FACING);
        };
    }

    /**
     * Builds an element model's cubes with its face textures sampled at {@code tick}, each face's
     * {@code tintindex} picking the colour it carries.
     *
     * @param context the renderer context every face texture is resolved against
     * @param model the model whose elements are built
     * @param options the caller's options, read for what an absent texture means
     * @param tint the colour each face carries, picked by its tintindex
     * @param tick the animation tick the face textures are sampled at
     * @return the model's triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> elementTriangles(
        @NotNull RendererContext context, @NotNull ModelData model, @NotNull ItemOptions options,
        @NotNull BlockGeometryKit.FaceTint tint, int tick
    ) {
        // The map is keyed by the original face reference string (including any leading
        // {@code #}), which is what BlockGeometryKit#buildFromElements expects.
        // Both arms are total - one draws the checkerboard, the other raises - so the resolver
        // answers present for every ref and the walk never drops a face.
        RendererContext textures = options.isSubstituteMissing()
            ? context.withMissingTexture()
            : context;
        ConcurrentMap<String, PixelBuffer> faceTextures = model.loadElementFaceTextures(
            textureId -> Optional.of(TextureRefusal.require(
                Flipbook.atTick(textures.resolveTexture(textureId), textures.findFlipbook(textureId), tick), textureId)));
        var forceRefs = model.resolveForceTranslucentRefs();
        return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures,
            new BlockGeometryKit.ElementBuildParams(tint, 0, 0, false, forceRefs, BlockGeometryKit.FaceTextureResolver.NONE));
    }

    /**
     * Builds an item's own element cubes with its face textures sampled at {@code tick}, each face
     * taking the colour its {@code tintindex} names in the item's own tints, through
     * {@link ItemTint#layerTints}.
     *
     * @param context the renderer context every face texture and tint is resolved against
     * @param item the item whose model's elements are built
     * @param options the caller's options, read for what an absent texture means and for the tint
     * @param tick the animation tick the face textures are sampled at
     * @return the model's triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> elementTriangles(
        @NotNull RendererContext context, @NotNull Item item, @NotNull ItemOptions options, int tick
    ) {
        return elementTriangles(context, item.model(), options,
            BlockGeometryKit.FaceTint.layers(ItemTint.layerTints(context, item.tints(), options)), tick);
    }

    /**
     * 2D GUI icon renderer. Composes layered sprites ({@code layer0}, {@code layer1}, ...)
     * with per-layer {@link ItemTint#resolveLayerTint tint}, damage bar, stack count, and glint
     * animation. The shield routes through {@link ShieldKit#renderShield3D} and banners through
     * {@link BannerKit#renderBannerOrShield} instead of the standard layer loop. It draws an id the
     * item index carries; a block-backed id the index does not carry draws the missing square, its
     * inventory icon being {@link GuiIcon}'s, unless its item definition decides the frame - a model
     * the stack chooses, the layers a composite lands on, or a stand-in. An item the game registers
     * that draws nothing, such as air, draws nothing beneath the decorations the request names.
     * <p>
     * A frame draws its {@link FrameItem}: a model's layers, the missing square for a leaf naming a
     * model no pack ships and for vanilla's missing item model, nothing for an empty branch, a
     * definition rooted at {@code minecraft:empty} or a model that declares nothing to draw, or each of
     * those a composite lands on, stacked in paint order, with the trim, damage bar and stack count
     * drawn over each alike.
     * <p>
     * A model built from elements draws those elements, posed by the model's own {@code display.gui} -
     * unturned where it declares none - and lit by the entry the slot binds for the whole stack, which
     * its first layer's {@link GuiLight} names: vanilla's {@code ITEMS_3D} for {@link GuiLight#SIDE},
     * its {@code ITEMS_FLAT} for {@link GuiLight#FRONT}. A {@code layer0} bound beside the elements
     * draws nothing, as vanilla takes a model's shape from the nearest file up its chain to declare
     * one. A flat layer's sprites take the shade the slot's light gives a face pointing at the viewer:
     * in full under {@code ITEMS_FLAT}, about half under {@code ITEMS_3D}.
     * <p>
     * A composite with a layer built from elements draws every layer in one depth pass, as vanilla's
     * slot draws a whole stack: an element layer at its own {@code display.gui}, a flat layer as its
     * sprites on the front face of vanilla's generated slab, and either missing model as the missing
     * cube, so a layer hides the parts of another it stands in front of, whichever was drawn first. A
     * composite of sprites alone stacks them in paint order, which is what one pass gives sprites that
     * share a plane.
     */
    @RequiredArgsConstructor
    public static final class Gui2D implements Renderer<ItemOptions> {

        /**
         * The camera a composite's one depth pass draws through: no turn and a unit orthographic lens,
         * each part's own {@code display.gui} riding its model transform, which is the camera
         * {@link Camera#fromTransform} builds for a lone model with the transform moved into the part.
         */
        private static final @NotNull Camera SLOT_CAMERA = Camera.identity(Lens.orthographic(1f));

        /**
         * The slab vanilla's item model generator bakes a flat layer into, half a pixel either side of
         * the model's centre, whose front face carries the layer's sprites.
         */
        private static final @NotNull Box SPRITE_SLAB = new Box(-0.5f, -0.5f, -1f / 32f, 0.5f, 0.5f, 1f / 32f);

        /**
         * The renderer context supplying pack / model / texture lookups.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Possible<Item> indexed = this.context.findItem(options.getItemId());
            if (indexed.isPresent()) return compose(indexed.get(), options);

            // An id the item index holds no row for - a block-backed one included, whose icon is
            // GUI_ICON's - has no layer stack to compose unless its definition decides the frame.
            Optional<FrameItem> chosen = ItemModelDispatch.definitionItem(
                this.context, options, options.itemModelAt(ItemOptions.Type.GUI_2D));
            if (chosen.isPresent()) return compose(chosen.get(), options);

            // Otherwise a registered item that draws nothing - air - draws the empty slot and the
            // decorations the request names, as a definition's empty branch does, and an id the index
            // does not know draws the checkerboard filling the slot.
            if (indexed.getState() == Possible.State.EMPTY)
                return compose(FrameItem.Nothing.of(options.getItemId()), options);

            return missingItem(options, "item",
                () -> Timeline.still(MissingMesh.icon(options.getOutput().getCanvasSize())));
        }

        /**
         * Composes the icon for a resolved item: the CIT walk, the per-frame item resolver, and the
         * layer stack each frame folds.
         *
         * @param baked the pipeline-baked item every frame starts from
         * @param options the caller's options
         * @return the composed icon, before the shared background composite
         */
        private @NotNull ImageData compose(@NotNull Item baked, @NotNull ItemOptions options) {
            // One CIT walk per render, shared by the layer stack (texture overrides) and the glint tail
            // (its GlintPolicy). The empty-context vanilla path yields CitResult.NONE, so both stay
            // vanilla-identical. The CIT walk reads no clock, so it is hoisted; the item is not, because
            // a dispatch tree can branch on world time. Resolve it AFTER the CIT walk so a CIT model
            // override can replace the tree-resolved model; the neutral context + no override yields the
            // baked item wherever the walk lands on the baked item's own model.
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            ItemModelContext modelContext = options.itemModelAt(ItemOptions.Type.GUI_2D);
            AnimationOptions anim = ItemModelDispatch.itemAnimation(this.context, options, modelContext);
            return compose(options, cit, anim, ItemModelDispatch.frameItems(
                this.context, options, modelContext, cit, anim, baked));
        }

        /**
         * Composes the icon for a frame an item definition chose once for the whole render - an id the
         * item index does not carry draws it on every frame.
         *
         * @param chosen the frame the definition chose
         * @param options the caller's options
         * @return the composed icon, before the shared background composite
         */
        private @NotNull ImageData compose(@NotNull FrameItem chosen, @NotNull ItemOptions options) {
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            AnimationOptions anim = ItemModelDispatch.itemAnimation(this.context, options, options.itemModelAt(ItemOptions.Type.GUI_2D));
            return compose(options, cit, anim, tick -> chosen);
        }

        /**
         * Composes the icon from a per-frame resolver: the layer stack each frame folds and the glint
         * finish the strip ends on.
         *
         * @param options the caller's options
         * @param cit the render's single CIT walk result
         * @param anim the animation the render bakes
         * @param itemAt what the frame at an animation tick draws
         * @return the composed icon, before the shared background composite
         */
        private @NotNull ImageData compose(
            @NotNull ItemOptions options, @NotNull CitResult cit, @NotNull AnimationOptions anim,
            @NotNull IntFunction<FrameItem> itemAt
        ) {
            // Compose the icon as an ordered ImageLayer stack (base sprite/banner/shield/elements, then
            // the trim, damage-bar, and stack-count decorations) so callers can splice their own passes
            // in via ItemOptions.layerDecorator, folded into the raster target. The glint finish is the
            // finalisation step, not a layer, because it expands the buffer into one or many frames.
            // A GUI icon draws at the canvas's own resolution, its sprites blitted and its shield and
            // element geometry rasterized alike, so no supersample (ssaa = 1); FXAA stays opt-in. Vanilla
            // ships zero item sidecars, so frameCount defaults to 1 and every layer resolves at tick 0 -
            // byte-identical; a pack opting in with frameCount > 1 plays the flipbook per frame.
            // Build the schedule UNCONDITIONALLY (the FluidRenderer pattern): frameCount=1 yields a single static
            // frame sampled at anim.getStartTick() (staticFrame would hardcode tick 0). Default
            // (startTick=0, frameCount=1) is byte-identical.
            int size = options.getOutput().getCanvasSize();
            // The glint finish spans the whole strip rather than one frame, and the only things it reads
            // off the frame are a registry flag every branch of a tree carries alike and whether the
            // frame takes a glint at all, so it binds to the first frame.
            return anim.timeline().bake(
                RasterPass.of(size, size, 1, options.getOutput().isAntiAlias(),
                        (target, tick) -> Layers.foldInto(
                            buildGuiLayers(new LayerContext(this.context, itemAt.apply(tick), options, cit), tick),
                            options.getLayerDecorator(), target))
                    .finishing(frameGlint(this.context, itemAt.apply(0), options, cit)));
        }

        /**
         * Builds the default GUI icon layer stack in vanilla pass order: a base layer, then the
         * conditional trim, damage-bar, and stack-count decorations. The base layer is what the frame
         * draws, through {@link #appendBase} - capturing the render {@code ctx}, the light the frame's
         * first layer names and the frame {@code tick} (so the base layer resolves its textures at that
         * tick).
         */
        private static @NotNull LayerStack<ImageLayer> buildGuiLayers(@NotNull LayerContext ctx, int tick) {
            ItemOptions options = ctx.options();
            int size = options.getOutput().getCanvasSize();
            LayerStack<ImageLayer> stack = new LayerStack<>();

            appendBase(stack, ctx, ctx.frame(), ctx.frame().guiLight().orElse(GuiLight.SIDE), tick);

            if (options.getDecoration().getTrimSlot().isPresent() && options.getDecoration().getTrimColor().isPresent())
                stack.append(ItemSlot.TRIM, frame ->
                    TrimKit.resolve(ctx.context(), options.getDecoration().getTrimSlot().get().getKey(), options.getDecoration().getTrimColor().get().getKey())
                        .ifPresent(trim -> frame.blitScaled(trim, 0, 0, size, size)));

            if (options.isShowDamageBar())
                stack.append(ItemSlot.DAMAGE_BAR, frame ->
                    ItemStackKit.drawDamageBar(frame, options.getContext().damage(), ctx.frame().item().maxDurability()));

            if (options.getContext().stackCount() > 1)
                stack.append(ItemSlot.STACK_COUNT, frame ->
                    ItemStackKit.drawStackCount(frame, options.getContext().stackCount(), MinecraftFont.Vanilla.REGULAR));

            return stack;
        }

        /**
         * Appends the base layer a frame draws: the shield, banner, elements or sprites of a drawn
         * model, the missing square for either missing model, and no layer for an empty branch. A
         * composite with a layer built from elements appends one base layer drawing all of its layers in
         * one depth pass; a composite of sprites alone appends each layer's own, in paint order, so a
         * later layer draws over the ones before it.
         *
         * @param stack the layer stack being built
         * @param ctx the render's per-frame state
         * @param frame the frame, or one layer of a composite frame
         * @param light the light the slot binds for the whole frame
         * @param tick the animation tick the frame draws at
         */
        private static void appendBase(
            @NotNull LayerStack<ImageLayer> stack, @NotNull LayerContext ctx, @NotNull FrameItem frame,
            @NotNull GuiLight light, int tick
        ) {
            ItemOptions options = ctx.options();
            int size = options.getOutput().getCanvasSize();

            switch (frame) {
                case FrameItem.Drawn drawn -> {
                    if (options.getItemId().equals(BannerKit.SHIELD_ITEM_ID))
                        stack.append(ItemSlot.BASE, buffer -> ShieldKit.renderShield3D(ctx.context(), buffer, options, tick));
                    else if (BannerKit.isBannerOrShield(options.getItemId()))
                        stack.append(ItemSlot.BASE, buffer ->
                            BannerKit.renderBannerOrShield(ctx.context(), buffer, options.getItemId(), options));
                    else if (!drawn.item().model().getElements().isEmpty())
                        stack.append(ItemSlot.BASE, buffer -> renderElements(ctx.context(), buffer, drawn.item(), options, light, tick));
                    else
                        stack.append(ItemSlot.BASE, buffer ->
                            renderStandardLayers(ctx.context(), buffer, drawn.item(), options, ctx.cit(), light, tick));
                }
                case FrameItem.MissingModel missing -> stack.append(ItemSlot.BASE, buffer ->
                    buffer.blit(missingItem(options, missing, () -> MissingMesh.icon(size)), 0, 0));
                case FrameItem.MissingItemModel ignored -> stack.append(ItemSlot.BASE, buffer ->
                    buffer.blit(MissingMesh.icon(size), 0, 0));
                case FrameItem.Nothing ignored -> { }
                // The shield and the banners draw through their own kits, which no shared pass can take.
                case FrameItem.Composite composite when !BannerKit.isBannerOrShield(options.getItemId()) && composite.hasElements() ->
                    stack.append(ItemSlot.BASE, buffer -> {
                        ConcurrentList<DrawPart> draws = slotDraws(ctx, composite, light, tick);
                        if (!draws.isEmpty()) new Rasterizer(SLOT_CAMERA).rasterizeAll(draws, buffer);
                    });
                case FrameItem.Composite composite -> composite.layers().forEach(layer -> appendBase(stack, ctx, layer, light, tick));
            }
        }

        /**
         * Builds the parts one depth pass draws for a slot frame, in paint order, each through the model
         * transform its own {@code display.gui} gives it under {@link #SLOT_CAMERA}: a model built from
         * elements as its relit cubes, a flat model as its sprites on {@link #SPRITE_SLAB}'s front face,
         * either missing model as the missing cube at the identity, nothing for an empty branch, and each
         * layer's own parts for a composite.
         * <p>
         * A sprite face carries the shade {@link #renderStandardLayers} gives the layer stack and the
         * missing cube the unshaded checkerboard the missing square draws, so both are relit under
         * {@code ITEMS_FLAT}, which leaves a face pointing at the viewer as it is.
         *
         * @param ctx the render's per-frame state
         * @param frame the frame, or one layer of a composite frame
         * @param light the light the slot binds for the whole frame
         * @param tick the animation tick the frame draws at
         * @return the frame's parts, in draw order
         */
        private static @NotNull ConcurrentList<DrawPart> slotDraws(
            @NotNull LayerContext ctx, @NotNull FrameItem frame, @NotNull GuiLight light, int tick
        ) {
            ItemOptions options = ctx.options();
            return switch (frame) {
                case FrameItem.Drawn drawn when !drawn.item().model().getElements().isEmpty() -> {
                    ModelTransform gui = guiTransform(drawn.item().model());
                    yield Concurrent.newUnmodifiableList(new DrawPart(
                        litElements(ctx.context(), drawn.item(), options, light, gui, tick), gui.toMatrix()));
                }
                case FrameItem.Drawn drawn -> {
                    ModelTransform gui = guiTransform(drawn.item().model());
                    PixelBuffer sprites = PixelBuffer.create(options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize());
                    renderStandardLayers(ctx.context(), sprites, drawn.item(), options, ctx.cit(), light, tick);
                    yield Concurrent.newUnmodifiableList(new DrawPart(
                        unshaded(BoxKit.buildBox(SPRITE_SLAB, FaceTextures.uniform(sprites), ColorMath.WHITE), gui),
                        gui.toMatrix()));
                }
                case FrameItem.MissingModel missing -> Concurrent.newUnmodifiableList(new DrawPart(
                    unshaded(missingItem(options, missing, MissingMesh::cube), ModelTransform.IDENTITY), Matrix4f.IDENTITY));
                case FrameItem.MissingItemModel ignored -> Concurrent.newUnmodifiableList(new DrawPart(
                    unshaded(MissingMesh.cube(), ModelTransform.IDENTITY), Matrix4f.IDENTITY));
                case FrameItem.Nothing ignored -> Concurrent.newUnmodifiableList();
                case FrameItem.Composite composite -> composite.layers()
                    .stream()
                    .flatMap(layer -> slotDraws(ctx, layer, light, tick).stream())
                    .collect(Concurrent.toUnmodifiableList());
            };
        }

        /**
         * Relights triangles under {@code ITEMS_FLAT} in the frame a display transform turns them to,
         * which leaves a face pointing at the viewer at the colour its texture carries.
         *
         * @param triangles the triangles to relight
         * @param gui the display transform they are posed by
         * @return the relit triangles
         */
        private static @NotNull ConcurrentList<VisibleTriangle> unshaded(
            @NotNull ConcurrentList<VisibleTriangle> triangles, @NotNull ModelTransform gui
        ) {
            return Shading.relightForItemsFlat(triangles, LightingFrame.tracking(gui.getRotation()), true);
        }

        /**
         * Draws a model built from elements into a slot: its cubes, each face taking the colour its
         * tintindex names in the item's own tints, posed by the model's {@code display.gui} - the
         * identity where it declares none, so a full cube's south face fills the slot, as vanilla's
         * does - and relit under the slot's light, {@link Shading#relightForItems3d} for
         * {@link GuiLight#SIDE} and {@link Shading#relightForItemsFlat} for {@link GuiLight#FRONT}.
         *
         * @param context the renderer context every face texture is resolved against
         * @param buffer the slot's base-layer buffer
         * @param item the item the frame draws, whose model declares elements
         * @param options the caller's options, read for what an absent texture means and for the tint
         * @param light the light the slot binds for the whole frame
         * @param tick the animation tick the face textures are sampled at
         */
        private static void renderElements(
            @NotNull RendererContext context, @NotNull PixelBuffer buffer, @NotNull Item item,
            @NotNull ItemOptions options, @NotNull GuiLight light, int tick
        ) {
            ModelTransform gui = guiTransform(item.model());
            Camera camera = Camera.fromTransform(
                gui.getRotation(),
                new Vector3f(gui.getTranslationX(), gui.getTranslationY(), gui.getTranslationZ()),
                new Vector3f(gui.getScaleX(), gui.getScaleY(), gui.getScaleZ())
            );
            new Rasterizer(camera).rasterize(litElements(context, item, options, light, gui, tick), buffer);
        }

        /**
         * Answers a model's {@code display.gui}, or the identity where it declares none.
         *
         * @param model the model whose display is read
         * @return the slot's display transform
         */
        private static @NotNull ModelTransform guiTransform(@NotNull ModelData model) {
            return model.getDisplay()
                .getOptional(ItemOptions.Type.GUI_2D.displayContext())
                .orElse(ModelTransform.IDENTITY);
        }

        /**
         * Builds a model's element cubes and relights them under the slot's light, in the frame its
         * {@code display.gui} turns them to: {@link Shading#relightForItems3d} for {@link GuiLight#SIDE}
         * and {@link Shading#relightForItemsFlat} for {@link GuiLight#FRONT}.
         *
         * @param context the renderer context every face texture is resolved against
         * @param item the item the frame draws, whose model declares elements
         * @param options the caller's options, read for what an absent texture means and for the tint
         * @param light the light the slot binds for the whole frame
         * @param gui the model's display transform
         * @param tick the animation tick the face textures are sampled at
         * @return the relit triangles
         */
        private static @NotNull ConcurrentList<VisibleTriangle> litElements(
            @NotNull RendererContext context, @NotNull Item item, @NotNull ItemOptions options,
            @NotNull GuiLight light, @NotNull ModelTransform gui, int tick
        ) {
            LightingFrame lighting = LightingFrame.tracking(gui.getRotation());
            ConcurrentList<VisibleTriangle> triangles = elementTriangles(context, item, options, tick);
            return switch (light) {
                case FRONT -> Shading.relightForItemsFlat(triangles, lighting, true);
                case SIDE -> Shading.relightForItems3d(triangles, lighting, true);
            };
        }

        /**
         * Per-frame state passed to every {@link ImageLayer} in the 2D item composite stack. Built by
         * {@link #render} for each frame it bakes - every field but the frame is the render's own and
         * identical across frames - and read by {@link #buildGuiLayers}.
         *
         * @param context renderer context for texture and override resolution
         * @param frame what the frame draws
         * @param options caller-supplied item render options, read for what an absent texture means
         * @param cit the render's single CIT walk result, shared by every base-layer pass
         */
        private record LayerContext(
            @NotNull RendererContext context,
            @NotNull FrameItem frame,
            @NotNull ItemOptions options,
            @NotNull CitResult cit
        ) { }

    }

    /**
     * Held 3D item renderer. An id the item index carries draws its item model: element boxes through
     * {@link BlockGeometryKit#buildFromElements} where the model declares them, else a thin textured
     * slab derived from {@code layer0}. An id the item index does not carry draws what its item
     * definition decides where it decides - the model the stack's components choose, flat or element
     * alike, every layer of a composite its walk passes through, vanilla's missing item model for a
     * definition the loader refused, or nothing for one rooted at {@code minecraft:empty} - and
     * otherwise the block model its definition's neutral branch names, where the block's
     * {@link Block#modelIcon()} holds. An id neither index draws that either knows as one that draws
     * nothing - air, or a block such as {@code cave_air} with no item - draws nothing. The rest take
     * the missing-model cube: a block entity, and a definition whose neutral branch is neither one
     * block model nor a composite of models, such as a special. Every branch rasterizes at the drawn
     * model's {@code thirdperson_righthand} display transform, a frame's parts through
     * {@link Rasterizer#rasterizeAll}.
     * <p>
     * A frame whose leaf names a model no pack ships draws the missing cube at the identity transform,
     * as does vanilla's missing item model, and an empty branch draws nothing, as does a leaf or a CIT
     * model override naming a model that declares nothing to draw. A composite draws each of its layers
     * so, each at its own model's display transform, and all of them in one depth pass, so a layer hides
     * the parts of another it stands in front of whichever was drawn first.
     * <p>
     * A face built from elements, of an item model or of a block model, takes the colour its
     * tintindex names in {@link ItemRenderer#definitionTints the item definition's tints}; a face at
     * no tintindex takes none. Where the definition declares no tint, the caller's
     * {@link DecorationOptions#getTintColor()} stands at tintindex 0, the slot a flat sprite's
     * {@code layer0} takes it in.
     * <p>
     * An id the item index carries resolves its item-definition tree at
     * {@code thirdperson_righthand} unless the caller supplies a context, so a
     * {@code display_context} select draws its held case.
     * <p>
     * Banner and shield items route through {@link ShieldKit#buildBannerOrShield3D} so the
     * HELD_3D view shows the composited pattern stack: both fall back to a thin textured slab whose
     * six faces carry the freshly composited banner / shield texture, mirroring the flat-sprite
     * fallback used for other item kinds.
     * <p>
     * Flat-sprite items composite their (tinted) layer stack into a native-size
     * {@link PixelBuffer} via {@link ItemTint#composeTintedLayers} and feed the result into the
     * thin-Z-slab path, so the held view reflects the same per-layer tint as the GUI icon.
     */
    public static final class Held3D implements Renderer<ItemOptions> {

        /**
         * The renderer context supplying pack / model / texture lookups.
         */
        private final @NotNull RendererContext context;

        /**
         * Constructs the held-3D sub-renderer bound to the given context.
         *
         * @param context the renderer context supplying pack / model / texture lookups
         */
        public Held3D(@NotNull RendererContext context) {
            this.context = context;
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Possible<Item> item = this.context.findItem(options.getItemId());
            if (item.isPresent())
                return heldOf(item.get(), options);

            // An id the item index does not carry draws what its definition decides where it decides -
            // a refused definition, a branch the stack chooses, or a composite - whatever the model's
            // shape.
            Optional<FrameItem> chosen = ItemModelDispatch.definitionItem(
                this.context, options, options.itemModelAt(ItemOptions.Type.HELD_3D));
            if (chosen.isPresent())
                return heldOf(options, tick -> chosen.get());

            // Otherwise it holds the block model its item definition's neutral branch names, which is
            // the block's own model exactly where modelIcon holds: a select on a block state drawing its
            // fallback counts, as beehive's does. A block entity, and a definition whose neutral branch
            // is neither one block model nor a composite of models - a special, say - name no model this
            // path draws, and take the missing cube.
            Possible<Block> block = this.context.findBlock(options.getItemId());
            if (block.isPresent() && block.get().modelIcon())
                return heldBlockOf(block.get(), options);

            // Neither index draws it, and one of them knows it draws nothing - air, or a block such as
            // cave_air with no item: held, that is nothing at all.
            if (!block.isPresent() && (item.getState() == Possible.State.EMPTY || block.getState() == Possible.State.EMPTY))
                return heldOf(options, tick -> FrameItem.Nothing.of(options.getItemId()));

            return missingItem(options, block.isPresent() ? "item" : "item or block",
                () -> missingCube(this.context, options));
        }

        /**
         * Draws the missing-model cube at the identity transform.
         * <p>
         * The camera is the resolving path's own, which hard-codes {@link EulerRotation#NONE} because
         * the held pose lives in the model's display transform - and a missing model has none, so the
         * cube sits where an absent display slot would have put it.
         *
         * @param context the render context the cube rasterizes through
         * @param options the caller's options, supplying the output frame and the timing
         * @return the cube seen square-on
         */
        private static @NotNull ImageData missingCube(
            @NotNull RendererContext context, @NotNull ItemOptions options) {
            OutputOptions output = options.getOutput();
            Camera missing = Camera.identity(output.getProjection().resolve(EulerRotation.NONE, output.getFacing()).camera().lens());
            int canvas = output.getCanvasSize();
            return ItemModelDispatch.itemAnimation(context, options, options.itemModelAt(ItemOptions.Type.HELD_3D)).timeline().bake(
                RasterPass.of(canvas, canvas, output.getSupersample(), output.isAntiAlias(), (target, tick) ->
                    new Rasterizer(missing).rasterize(MissingMesh.cube(), target, Matrix4f.IDENTITY)));
        }

        /**
         * Renders a resolved item held: the CIT walk, the per-frame item resolver, and the geometry
         * each frame builds at its own tick.
         *
         * @param baked the pipeline-baked item every frame starts from
         * @param options the caller's options
         * @return the held render, before the shared background composite
         */
        private @NotNull ImageData heldOf(@NotNull Item baked, @NotNull ItemOptions options) {
            // One CIT walk per render, shared by the per-frame resolver, the flat-slab layer composite and
            // the glint tail; it reads no clock, so it is hoisted.
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            ItemModelContext modelContext = options.itemModelAt(ItemOptions.Type.HELD_3D);
            AnimationOptions anim = ItemModelDispatch.itemAnimation(this.context, options, modelContext);
            return heldOf(options, cit, anim, ItemModelDispatch.frameItems(
                this.context, options, modelContext, cit, anim, baked));
        }

        /**
         * Renders a frame resolver held, with the render's own CIT walk and animation - the path a frame
         * an item definition chose once for the whole render takes.
         *
         * @param options the caller's options
         * @param itemAt what the frame at an animation tick draws
         * @return the held render, before the shared background composite
         */
        private @NotNull ImageData heldOf(@NotNull ItemOptions options, @NotNull IntFunction<FrameItem> itemAt) {
            return heldOf(options, this.context.resolveItemTextureOverride(options.getContext()),
                ItemModelDispatch.itemAnimation(this.context, options, options.itemModelAt(ItemOptions.Type.HELD_3D)), itemAt);
        }

        /**
         * Renders a frame resolver held: the geometry each frame builds at its own tick, and the glint
         * finish the strip ends on.
         *
         * @param options the caller's options
         * @param cit the render's single CIT walk result
         * @param anim the animation the render bakes
         * @param itemAt what the frame at an animation tick draws
         * @return the held render, before the shared background composite
         */
        private @NotNull ImageData heldOf(
            @NotNull ItemOptions options, @NotNull CitResult cit, @NotNull AnimationOptions anim,
            @NotNull IntFunction<FrameItem> itemAt
        ) {
            // Identity-pose camera carrying only the projection's lens: the held-item pose lives
            // entirely in the model's display transform (applied as the modelTransform below), so the
            // camera pose stays identity and only the rotation-independent lens comes from resolve().
            Camera camera = Camera.identity(options.getOutput().getProjection().resolve(EulerRotation.NONE, options.getOutput().getFacing()).camera().lens());

            // The geometry build moves INSIDE the raster callback (fluid pattern) and the Rasterizer is
            // rebuilt per frame for thread-safe parallel strip baking. Vanilla ships no item sidecars, so
            // a default render (frameCount = 1) resolves at tick 0 - byte-identical.
            // Build the schedule UNCONDITIONALLY (the FluidRenderer pattern): frameCount=1 yields a single static
            // frame sampled at anim.getStartTick() (staticFrame would hardcode tick 0). Default
            // (startTick=0, frameCount=1) is byte-identical.
            int size = options.getOutput().getCanvasSize();
            int ssaa = options.getOutput().getSupersample();
            return anim.timeline().bake(
                RasterPass.of(size, size, ssaa, options.getOutput().isAntiAlias(), (target, tick) -> {
                    // A composite's layers share the frame's one depth pass, so every part is built
                    // before any of it is drawn.
                    ConcurrentList<DrawPart> draws = heldDraws(itemAt.apply(tick), options, cit, tick);
                    if (!draws.isEmpty()) new Rasterizer(camera).rasterizeAll(draws, target);
                }).finishing(frameGlint(this.context, itemAt.apply(0), options, cit)));
        }

        /**
         * Builds the parts a held frame draws: a drawn model's triangles at its own display pose, the
         * missing cube for either missing model, nothing for an empty branch, and for a composite each
         * of its layers' parts, in paint order.
         * <p>
         * The display pose is read off the frame's own model: a tree that swaps models between frames
         * can swap their authored poses with them, and a composite's layers each take their own. Either
         * missing model has no display, so it sits at the identity an absent slot resolves to.
         *
         * @param frame the frame, or one layer of a composite frame
         * @param options the caller's options
         * @param cit the render's single CIT walk result
         * @param tick the animation tick the frame draws at
         * @return the frame's parts, in draw order
         */
        private @NotNull ConcurrentList<DrawPart> heldDraws(
            @NotNull FrameItem frame, @NotNull ItemOptions options, @NotNull CitResult cit, int tick
        ) {
            return switch (frame) {
                case FrameItem.Drawn drawn -> Concurrent.newUnmodifiableList(new DrawPart(
                    buildTrianglesAtTick(this.context, drawn.item(), options, cit, tick), heldDisplay(drawn.item().model())));
                case FrameItem.MissingModel missing -> Concurrent.newUnmodifiableList(
                    new DrawPart(missingItem(options, missing, MissingMesh::cube), Matrix4f.IDENTITY));
                case FrameItem.MissingItemModel ignored -> Concurrent.newUnmodifiableList(
                    new DrawPart(MissingMesh.cube(), Matrix4f.IDENTITY));
                case FrameItem.Nothing ignored -> Concurrent.newUnmodifiableList();
                case FrameItem.Composite composite -> composite.layers()
                    .stream()
                    .flatMap(layer -> heldDraws(layer, options, cit, tick).stream())
                    .collect(Concurrent.toUnmodifiableList());
            };
        }

        /**
         * Renders a block-backed id held: the block model its item definition names, built from that
         * model's elements with each tinted face coloured by the definition tint its tintindex names,
         * and posed by the model's {@code thirdperson_righthand} display transform.
         *
         * @param block the block whose own model the item definition names
         * @param options the caller's options
         * @return the held render, before the shared background composite
         */
        private @NotNull ImageData heldBlockOf(@NotNull Block block, @NotNull ItemOptions options) {
            Camera camera = Camera.identity(options.getOutput().getProjection().resolve(EulerRotation.NONE, options.getOutput().getFacing()).camera().lens());
            ModelData model = block.model();
            // A held item takes its tints from its item definition, calculated once before its quads
            // are gathered and picked per face by tintindex; the block's own tint source, which the
            // placed and the carried block take, is not consulted.
            BlockGeometryKit.FaceTint tint = BlockGeometryKit.FaceTint.layers(
                definitionTints(this.context, options, ItemOptions.Type.HELD_3D));
            Matrix4f display = heldDisplay(model);
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            AnimationOptions anim = ItemModelDispatch.itemAnimation(this.context, options, options.itemModelAt(ItemOptions.Type.HELD_3D));
            int size = options.getOutput().getCanvasSize();
            // No block item is foil of itself, so only the caller's enchantment or override glints it.
            return anim.timeline().bake(
                RasterPass.of(size, size, options.getOutput().getSupersample(), options.getOutput().isAntiAlias(), (target, tick) ->
                        new Rasterizer(camera).rasterize(
                            elementTriangles(this.context, model, options, tint, tick), target, display))
                    .finishing(ItemTint.itemGlint(this.context, false, options, cit.glint())));
        }

        /**
         * Builds the held-item triangles at animation {@code tick}: banner / shield via the pattern
         * composite, an element-model item's cubes with its face textures sampled at {@code tick}
         * (any item model declaring {@code elements}), or a
         * flat-sprite item's thin Z-slab carrying its (tinted) {@code layer0..N} composite resolved at
         * {@code tick}. An element face takes the frame item's own tint its tintindex names, through
         * {@link ItemTint#layerTints}, since a tree can swap tints between frames.
         * {@link ItemTint#composeTintedLayers} folds in each layer's {@code LayerTint} (leather
         * dye, potion colour, firework colour) and the caller's {@code tintColor} so the held view
         * carries the same colour as the GUI icon (degenerate no-elements-and-no-layer0 cases throw
         * inside it). Called once per frame from the raster callback so an animated pack
         * texture rebuilds per frame.
         *
         * @param context the renderer context every texture this frame reads is resolved against
         * @param item the item this frame resolved to
         * @param options the caller's options, read for what an absent texture means and the overrides
         * @param cit the render's single CIT walk result
         * @param tick the animation tick this frame draws at
         * @return the frame's triangles
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildTrianglesAtTick(
            @NotNull RendererContext context, @NotNull Item item, @NotNull ItemOptions options, @NotNull CitResult cit, int tick
        ) {
            if (BannerKit.isBannerOrShield(options.getItemId()))
                return ShieldKit.buildBannerOrShield3D(context, options.getItemId(), options);
            if (!item.model().getElements().isEmpty())
                return elementTriangles(context, item, options, tick);
            PixelBuffer texture = ItemTint.composeTintedLayers(context, item, options, cit, tick);
            return BoxKit.buildBox(
                ShieldKit.FLAT_ITEM_SLAB,
                FaceTextures.uniform(texture),
                ColorMath.WHITE
            );
        }

        /**
         * Resolves the held pose a model declares - its {@code thirdperson_righthand} display
         * transform - into a {@link Matrix4f}. Falls back to the identity when the model's display
         * declares no such slot, which matches vanilla for a model with no display metadata: vanilla
         * then applies only its centring translate, which the geometry's own centring stands in for.
         *
         * @param model the model whose display transforms are read
         * @return the slot's display matrix, or the identity when the model declares none
         */
        static @NotNull Matrix4f heldDisplay(@NotNull ModelData model) {
            ModelTransform transform = model.getDisplay().get(ItemOptions.Type.HELD_3D.displayContext());
            if (transform == null) return Matrix4f.IDENTITY;

            return transform.toMatrix();
        }

    }

    /**
     * Faithful inventory-icon renderer ({@link ItemOptions.Type#GUI_ICON}): the representation a GUI
     * slot shows, routed by index membership rather than rendered anew. An id the item index carries
     * renders through the shared {@link Gui2D} path unchanged; an id backing a block (plain blocks and
     * block-entities alike) renders through the isometric {@link BlockRenderer}, which already
     * distinguishes a plain block model from a {@code BlockEntityRenderer} pose, where the item index
     * lacks it or carries it with a model that declares {@code elements} - an item model whose
     * geometry comes from a block parent, whose icon is the block's own; an id backing neither draws
     * the square {@link MissingMesh#icon(int)} builds, unless either index knows it as one that draws
     * nothing - air, or a block such as {@code cave_air} with no item - which draws the empty slot.
     * <p>
     * An id routed to the block draws what its item definition decides where it decides, through the
     * {@link Gui2D} path: a model the stack's components choose - flat, an element item model or a
     * block model alike - a model the walk lands on in place of an indexed id's own, the layers a
     * composite lands on, the missing square for a leaf naming a model no pack ships and for vanilla's
     * missing item model, or nothing for an empty branch and for a definition rooted at
     * {@code minecraft:empty}. The block's own icon is drawn only where the definition leaves the frame
     * to it, and it keeps the block-style lighting every block icon takes whatever its model's
     * {@code gui_light} names.
     * <p>
     * A flat-sprite icon is byte-identical to {@link ItemOptions.Type#GUI_2D}. A block-backed icon is
     * the isometric block render at the same output frame, except that where the block's
     * {@link Block#modelIcon()} holds, its faces take {@link ItemRenderer#definitionTints the item
     * definition's tints} per tintindex instead of the options' biome, as vanilla tints a slot icon
     * from its item definition. The two agree wherever the definition's tints equal the block's own:
     * stone, {@code red_bed}, birch and spruce leaves. The unrouted one is a flat square rather than a
     * posed cube because a slot showing the missing model applies no rotation to it, so exactly one
     * face is seen square-on.
     */
    public static final class GuiIcon implements Renderer<ItemOptions> {

        /**
         * Renderer context supplying the item / block index lookups that pick the branch.
         */
        private final @NotNull RendererContext context;

        /**
         * The shared 2D sub-renderer the item-index and definition branches delegate to.
         */
        private final @NotNull Gui2D gui2D;

        /**
         * The isometric block renderer the block / block-entity branch delegates to, built from the
         * shared context.
         */
        private final @NotNull BlockRenderer blockRenderer;

        /**
         * Constructs the faithful-icon sub-renderer, reusing the owning {@link ItemRenderer}'s 2D
         * sub-renderer and building an isometric {@link BlockRenderer} from the shared context.
         *
         * @param context the renderer context supplying pack / model / texture lookups
         * @param gui2D the shared 2D GUI icon sub-renderer
         */
        public GuiIcon(@NotNull RendererContext context, @NotNull Gui2D gui2D) {
            this.context = context;
            this.gui2D = gui2D;
            this.blockRenderer = new BlockRenderer(context);
        }

        /**
         * Renders the faithful inventory icon: the {@link Gui2D} icon for an item-index id, unless a
         * block backs it and its model declares elements, and for any id whose item definition decides
         * the frame; else the isometric {@link BlockRenderer} for a block-backed id, its faces tinted by
         * the item definition's tints, and the empty slot for an id either index knows as one that
         * draws nothing. The block delegate renders on a transparent background so
         * {@link ItemRenderer#render} composites the caller's own background exactly once.
         *
         * @param options the item render options
         * @return the faithful inventory icon, before the shared background composite
         */
        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Possible<Item> item = this.context.findItem(options.getItemId());
            Possible<Block> block = this.context.findBlock(options.getItemId());
            if (item.isPresent() && !(block.isPresent() && !item.get().model().getElements().isEmpty()))
                return this.gui2D.render(options);

            // The definition decides where it refused to load, the stack chooses its branch, the walk
            // passes through a composite, or an indexed id's walk lands off its indexed model. An indexed
            // id resolves that per frame through the slot path; one the index does not carry draws the
            // chosen frame on every frame.
            Optional<FrameItem> chosen = ItemModelDispatch.definitionItem(
                this.context, options, options.itemModelAt(ItemOptions.Type.GUI_ICON));
            if (chosen.isPresent())
                return item.isPresent() ? this.gui2D.render(options) : this.gui2D.compose(chosen.get(), options);

            if (block.isPresent())
                return this.blockRenderer.renderIcon(adaptToBlock(options),
                    definitionTints(this.context, options, ItemOptions.Type.GUI_ICON));

            // Neither index draws it, and one of them knows it draws nothing - air, or a block such as
            // cave_air with no item - so the slot is empty beneath the decorations the request names.
            if (item.getState() == Possible.State.EMPTY || block.getState() == Possible.State.EMPTY)
                return this.gui2D.compose(FrameItem.Nothing.of(options.getItemId()), options);

            return missingItem(options, "item or block",
                () -> Timeline.still(MissingMesh.icon(options.getOutput().getCanvasSize())));
        }

        /**
         * Adapts item options into the {@link BlockOptions} the block branch renders: the output size
         * and anti-aliasing knobs carried onto the neutral iso output frame (default projection /
         * facing / rotation) so the block honours its authored {@code display.gui} pose - the vanilla
         * inventory look - rather than the item icon's {@code VANILLA_GUI_ITEM} projection. Renders on
         * a transparent background so the caller composites its own background once.
         * <p>
         * Every field the block branch is to honour is named here by hand, and one left out is not a
         * compile error - the builder seeds it from its own default instead, so the block render
         * silently answers for something the caller did not ask for. That is what
         * {@link ItemOptions#isSubstituteMissing()} is copied for, and it is the reason a block-backed
         * id is worth a test row of its own rather than an item-backed one standing in for it.
         *
         * @param options the item render options
         * @return the block options for the isometric block render
         */
        private static @NotNull BlockOptions adaptToBlock(@NotNull ItemOptions options) {
            OutputOptions itemOutput = options.getOutput();
            return BlockOptions.builder()
                .blockId(options.getItemId())
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder()
                    .canvasSize(itemOutput.getCanvasSize())
                    .supersample(itemOutput.getSupersample())
                    .antiAlias(itemOutput.isAntiAlias())
                    .build())
                .animation(options.getAnimation())
                .substituteMissing(options.isSubstituteMissing())
                .background(Background.TRANSPARENT)
                .build();
        }

    }

}
