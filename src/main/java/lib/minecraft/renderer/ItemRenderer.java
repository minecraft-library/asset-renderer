package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.bake.mesh.ShieldKit;
import lib.minecraft.renderer.bake.texture.BannerKit;
import lib.minecraft.renderer.bake.texture.ItemTint;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.geometry.ModelUnits;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.mesh.MissingMesh;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Quaternionf;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.port.answer.CitResult;
import lib.minecraft.renderer.request.AnimationOptions;
import lib.minecraft.renderer.request.BlockOptions;
import lib.minecraft.renderer.request.DecorationOptions;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.request.OutputOptions;
import lib.minecraft.renderer.screen.ItemStackKit;
import lib.minecraft.renderer.slot.ItemSlot;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Renders an {@link Item} as a flat 2D GUI icon, a held 3D view, or the faithful inventory icon by
 * dispatching on {@link ItemOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;ItemOptions&gt;}:
 * <ul>
 * <li>{@link Gui2D} composes layered flat sprites with optional damage bar, stack count, and
 * glint animation. Each {@code layerN} is multiplied by its
 * {@link LayerTint} (leather dye, potion colour, firework colour - from the item definition's
 * {@code model.tints[]}) or the caller's {@code tintColor}; the shield routes through a 3D
 * {@link ShieldKit} render and banners through {@link BannerKit}.</li>
 * <li>{@link Held3D} dispatches on whether the item's model provides element boxes - block items
 * build real cubes via {@link BlockGeometryKit#buildFromElements}, flat sprite items composite
 * their tinted layer stack onto a thin textured slab. Both paths route through
 * {@link Rasterizer} with the item model's {@code thirdperson_righthand} display transform applied.</li>
 * <li>{@link GuiIcon} renders the faithful inventory icon by index membership: an id with a flat
 * item entry through {@link Gui2D}, a block-backed id with no flat icon (plain blocks and
 * block-entities alike) through the isometric {@link BlockRenderer}. It adds no rendering of its own -
 * both branches reuse an existing renderer.</li>
 * </ul>
 * Which item a frame draws is {@link ItemModelDispatch}'s answer, the colour its layers carry is
 * {@link ItemTint}'s, and both sub-renderers ask the same pair, so the two paths agree on an item
 * without either owning the lookup.
 */
public final class ItemRenderer implements Renderer<ItemOptions> {

    /**
     * The flat 2D GUI icon sub-renderer.
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
     * Answers what an item render draws for an id neither index carries, or refuses where the caller
     * turned the substitution off.
     * <p>
     * All three entry points decide that here, so the flag is read in one place. Both the picture and
     * the noun stay the caller's: a slot's flat square differs from a held cube, and the faithful icon
     * looked in both indexes where the other two looked in one, so it says so.
     *
     * @param options the caller's options, supplying the id and the substitution flag
     * @param subject the noun naming what was looked for, as the refusal words it
     * @param drawn the picture to draw where the substitution is on
     * @return the drawn picture
     * @throws RenderException where the caller turned the substitution off
     */
    static @NotNull ImageData missingItem(
        @NotNull ItemOptions options, @NotNull String subject, @NotNull Supplier<ImageData> drawn) {
        if (!options.isSubstituteMissing())
            throw new RenderException("No %s registered for id '%s'", subject, options.getItemId());

        MissingMesh.reportSubstitution(options.getItemId());
        return drawn.get();
    }

    /**
     * Item model display slot for the 3D held-item pose (vanilla {@code thirdperson_righthand}).
     */
    private static final @NotNull String DISPLAY_SLOT_HELD_3D = "thirdperson_righthand";

    /**
     * Renders the standard layered-sprite path for an item. Each {@code layerN} texture is
     * composited in order, multiplying in the layer's {@link ItemTint#resolveLayerTint resolved tint} -
     * the item-definition {@link LayerTint} (leather dye, potion colour, firework colour) when
     * present, otherwise the caller's {@link DecorationOptions#getTintColor()} on the tintindex-0 slot.
     * A tinted layer is multiplied at its native resolution then scaled up, so the tint covers the
     * full icon rather than a corner. Trim overlay textures are resolved via
     * {@link TrimKit#resolveFromTextureRef} so the renderer doesn't depend on material-specific
     * PNGs being shipped in the pack.
     */
    static void renderStandardLayers(
        @NotNull RendererContext context,
        @NotNull PixelBuffer buffer,
        @NotNull Item item,
        @NotNull ItemOptions options,
        @NotNull CitResult cit,
        int tick
    ) {
        // Only the layer lookup below substitutes. The trim overlay resolves against the port itself,
        // where a palette the pack ships no file for is synthesised and an absent one is skipped rather
        // than drawn or refused - which is what leaves the icon untrimmed instead of checkered.
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
                    .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, size, size));
            } else {
                PixelBuffer layer = textures.requireTextureAtTick(textureRef, tick);
                int color = ItemTint.resolveLayerTint(context, item, layerIndex, options);
                // ColorMath.tint multiplies each texel by the colour (preserving alpha) and returns
                // a fresh buffer, then blitScaled composites it over the prior layers - unlike
                // blitTinted, which blends against the destination and would blank an empty buffer.
                PixelBuffer drawable = color != ColorMath.WHITE ? ColorMath.tint(layer, color) : layer;
                buffer.blitScaled(drawable, 0, 0, size, size);
            }
            layerIndex++;
        }
    }

    /**
     * Flat 2D GUI icon renderer. Composes layered sprites ({@code layer0}, {@code layer1}, ...)
     * with per-layer {@link ItemTint#resolveLayerTint tint}, damage bar, stack count, and glint
     * animation. The shield routes through {@link ShieldKit#renderShield3D} and banners through
     * {@link BannerKit#renderBannerOrShield} instead of the standard layer loop.
     */
    @RequiredArgsConstructor
    public static final class Gui2D implements Renderer<ItemOptions> {

        /**
         * The renderer context supplying pack / model / texture lookups.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            // An id neither index carries has no layer stack to compose, no CIT walk to hoist and no
            // glint policy to finish with, so it draws the checkerboard filling the slot.
            return this.context.findItem(options.getItemId())
                .map(baked -> compose(baked, options))
                .orElseGet(() -> missingItem(options, "item",
                    () -> Timeline.still(MissingMesh.icon(options.getOutput().getCanvasSize()))));
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
            // baked item.
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            AnimationOptions anim = ItemModelDispatch.itemAnimation(this.context, options);
            IntFunction<Item> itemAt = ItemModelDispatch.frameItems(this.context, options, cit, anim, baked);

            // Compose the icon as an ordered ImageLayer stack (base sprite/banner/shield, then the
            // trim, damage-bar, and stack-count decorations) so callers can splice their own passes in
            // via ItemOptions.layerDecorator, folded into the raster target. The glint finish is the
            // finalisation step, not a layer, because it expands the buffer into one or many frames.
            // A GUI icon is a flat sprite blit, so no supersample (ssaa = 1); FXAA stays opt-in. Vanilla
            // ships zero item sidecars, so frameCount defaults to 1 and every layer resolves at tick 0 -
            // byte-identical; a pack opting in with frameCount > 1 plays the flipbook per frame.
            // Build the schedule UNCONDITIONALLY (the FluidRenderer pattern): frameCount=1 yields a single static
            // frame sampled at anim.getStartTick() (staticFrame would hardcode tick 0). Default
            // (startTick=0, frameCount=1) is byte-identical.
            int size = options.getOutput().getCanvasSize();
            // The glint finish spans the whole strip rather than one frame, and the only thing it reads
            // off the item is a registry flag every branch of a tree carries alike, so it binds to the
            // first frame's item.
            return anim.timeline().bake(
                RasterPass.of(size, size, 1, options.getOutput().isAntiAlias(),
                        (target, tick) -> Layers.foldInto(
                            buildGuiLayers(new LayerContext(this.context, itemAt.apply(tick), options, cit), tick),
                            options.getLayerDecorator(), target))
                    .finishing(ItemTint.itemGlint(this.context, itemAt.apply(0), options, cit.glint())));
        }

        /**
         * Builds the default GUI icon layer stack in vanilla pass order: a base sprite/banner/shield
         * layer, then the conditional trim, damage-bar, and stack-count decorations. Each layer is the
         * verbatim pass that previously ran inline in {@link #render}, capturing the render {@code ctx}
         * and the frame {@code tick} (so the base layer resolves its textures at that tick).
         */
        private static @NotNull LayerStack<ImageLayer> buildGuiLayers(@NotNull LayerContext ctx, int tick) {
            ItemOptions options = ctx.options();
            LayerStack<ImageLayer> stack = new LayerStack<>();

            if (options.getItemId().equals(BannerKit.SHIELD_ITEM_ID))
                stack.append(ItemSlot.BASE, frame -> ShieldKit.renderShield3D(ctx.context(), frame, options, tick));
            else if (BannerKit.isBannerOrShield(options.getItemId()))
                stack.append(ItemSlot.BASE, frame ->
                    BannerKit.renderBannerOrShield(ctx.context(), frame, options.getItemId(), options));
            else
                stack.append(ItemSlot.BASE, frame ->
                    renderStandardLayers(ctx.context(), frame, ctx.item(), options, ctx.cit(), tick));

            if (options.getDecoration().getTrimSlot().isPresent() && options.getDecoration().getTrimColor().isPresent())
                stack.append(ItemSlot.TRIM, frame ->
                    TrimKit.resolve(ctx.context(), options.getDecoration().getTrimSlot().get().getKey(), options.getDecoration().getTrimColor().get().getKey())
                        .ifPresent(trim -> frame.blitScaled(trim, 0, 0, options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize())));

            if (options.isShowDamageBar())
                stack.append(ItemSlot.DAMAGE_BAR, frame ->
                    ItemStackKit.drawDamageBar(frame, options.getContext().damage(), ctx.item().maxDurability()));

            if (options.getContext().stackCount() > 1)
                stack.append(ItemSlot.STACK_COUNT, frame ->
                    ItemStackKit.drawStackCount(frame, options.getContext().stackCount(), MinecraftFont.Vanilla.REGULAR));

            return stack;
        }

        /**
         * Per-frame state passed to every {@link ImageLayer} in the 2D item composite stack. Built by
         * {@link #render} for each frame it bakes - every field but the resolved item is the render's
         * own and identical across frames - and read by {@link #buildGuiLayers}.
         *
         * @param context renderer context for texture and override resolution
         * @param item resolved item definition being rendered
         * @param options caller-supplied item render options, read for what an absent texture means
         * @param cit the render's single CIT walk result, shared by every base-layer pass
         */
        private record LayerContext(
            @NotNull RendererContext context,
            @NotNull Item item,
            @NotNull ItemOptions options,
            @NotNull CitResult cit
        ) { }

    }

    /**
     * Held 3D item renderer. Dispatches on whether the item model supplies element boxes - block
     * items with non-empty element lists build real cubes via {@link BlockGeometryKit#buildFromElements},
     * while flat sprite items fall back to a thin textured slab derived from {@code layer0}.
     * Both branches feed the same {@link Rasterizer#rasterize} overload with the item's
     * {@code thirdperson_righthand} display transform.
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
            return this.context.findItem(options.getItemId())
                .map(baked -> heldOf(baked, options))
                .orElseGet(() -> missingItem(options, "item", () -> missingCube(this.context, options)));
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
            return ItemModelDispatch.itemAnimation(context, options).timeline().bake(
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
            // Identity-pose camera carrying only the projection's lens: the held-item pose lives
            // entirely in the model's display transform (applied as the modelTransform below), so the
            // camera pose stays identity and only the rotation-independent lens comes from resolve().
            Camera camera = Camera.identity(options.getOutput().getProjection().resolve(EulerRotation.NONE, options.getOutput().getFacing()).camera().lens());
            int tint = options.getDecoration().getTintColor().orElse(ColorMath.WHITE);

            // One CIT walk per render, shared by the flat-slab layer composite and the glint tail; it
            // reads no clock, so it is hoisted. The geometry build moves INSIDE the raster callback
            // (fluid pattern) and the Rasterizer is rebuilt per frame for thread-safe parallel strip
            // baking. Vanilla ships no item sidecars, so a default render (frameCount = 1) resolves at
            // tick 0 - byte-identical.
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            AnimationOptions anim = ItemModelDispatch.itemAnimation(this.context, options);
            IntFunction<Item> itemAt = ItemModelDispatch.frameItems(this.context, options, cit, anim, baked);

            // Build the schedule UNCONDITIONALLY (the FluidRenderer pattern): frameCount=1 yields a single static
            // frame sampled at anim.getStartTick() (staticFrame would hardcode tick 0). Default
            // (startTick=0, frameCount=1) is byte-identical.
            int size = options.getOutput().getCanvasSize();
            int ssaa = options.getOutput().getSupersample();
            return anim.timeline().bake(
                RasterPass.of(size, size, ssaa, options.getOutput().isAntiAlias(), (target, tick) -> {
                    // The display pose is read off the frame's own model: a tree that swaps models
                    // between frames can swap their authored poses with them.
                    Item item = itemAt.apply(tick);
                    Rasterizer engine = new Rasterizer(camera);
                    engine.rasterize(buildTrianglesAtTick(this.context, item, options, cit, tint, tick), target,
                        resolveDisplayTransform(item, DISPLAY_SLOT_HELD_3D));
                }).finishing(ItemTint.itemGlint(this.context, itemAt.apply(0), options, cit.glint())));
        }

        /**
         * Builds the held-item triangles at animation {@code tick}: banner / shield via the pattern
         * composite, an element-model item's cubes with its face textures sampled at {@code tick}
         * (held block items and any custom item whose model JSON supplies {@code elements}), or a
         * flat-sprite item's thin Z-slab carrying its (tinted) {@code layer0..N} composite resolved at
         * {@code tick}. {@link ItemTint#composeTintedLayers} folds in each layer's {@code LayerTint} (leather
         * dye, potion colour, firework colour) and the caller's {@code tintColor} so the held view
         * carries the same colour as the GUI icon (degenerate no-elements-and-no-layer0 cases throw
         * inside it). Called once per frame from the raster callback so an animated pack
         * texture rebuilds per frame.
         *
         * @param context the renderer context every texture this frame reads is resolved against
         * @param item the item this frame resolved to
         * @param options the caller's options, read for what an absent texture means
         * @param cit the render's single CIT walk result
         * @param tint the caller's tint, applied to an element model's faces
         * @param tick the animation tick this frame draws at
         * @return the frame's triangles
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildTrianglesAtTick(
            @NotNull RendererContext context, @NotNull Item item, @NotNull ItemOptions options, @NotNull CitResult cit, int tint, int tick
        ) {
            if (BannerKit.isBannerOrShield(options.getItemId()))
                return ShieldKit.buildBannerOrShield3D(context, options.getItemId(), options);
            if (!item.model().getElements().isEmpty()) {
                // The map is keyed by the original face reference string (including any leading
                // {@code #}), which is what BlockGeometryKit#buildFromElements expects.
                // Both arms are total - one draws the checkerboard, the other raises - so the resolver
                // answers present for every ref and the walk never drops a face.
                RendererContext textures = options.isSubstituteMissing()
                    ? context.withMissingTexture()
                    : context;
                ConcurrentMap<String, PixelBuffer> faceTextures = item.model().loadElementFaceTextures(
                    textureId -> Optional.of(textures.requireTextureAtTick(textureId, tick)));
                var forceRefs = item.model().resolveForceTranslucentRefs();
                return BlockGeometryKit.buildFromElements(item.model().getElements(), faceTextures, tint, tint, forceRefs);
            }
            PixelBuffer texture = ItemTint.composeTintedLayers(context, item, options, cit, tick);
            return BoxKit.buildBox(
                ShieldKit.FLAT_ITEM_SLAB,
                FaceTextures.uniform(texture),
                ColorMath.WHITE
            );
        }

        /**
         * Resolves the item model's display transform for the given slot (e.g.
         * {@code thirdperson_righthand}) into a {@link Matrix4f}. Falls back to the identity
         * when the slot is not defined, which matches vanilla's behaviour for items with no
         * display metadata.
         * <p>
         * Applied to a row vector in the order <b>scale, then rotate, then translate</b>, which
         * is what vanilla produces for {@code poseStack.scale(); poseStack.mulPose(rXYZ);
         * poseStack.translate();}. Column-vector composition: rightmost (translation) applies
         * first to a vertex, then rotation, then scale - matching the PoseStack op sequence.
         */
        private static @NotNull Matrix4f resolveDisplayTransform(@NotNull Item item, @NotNull String slot) {
            ModelTransform transform = item.model().getDisplay().get(slot);
            if (transform == null) return Matrix4f.IDENTITY;

            EulerRotation angles = transform.getRotation();
            // Vanilla display transforms use sub-unit translation values in {@code /16} space;
            // apply them to the model vertex positions directly since our unit cube is already
            // normalized. Composed via the fluent scale/rotate/translate path (bit-identical to
            // vanilla's PoseStack; the createX().multiply(...) form drifts 1-4 ULPs per entry) -
            // IDENTITY * S * R * T applies translation to the vertex first, then rotation, then scale.
            return Matrix4f.IDENTITY
                .scale(transform.getScaleX(), transform.getScaleY(), transform.getScaleZ())
                .rotate(Quaternionf.rotationXYZ(angles.pitchRadians(), angles.yawRadians(), angles.rollRadians()))
                .translate(
                    transform.getTranslationX() / ModelUnits.PIXELS_PER_BLOCK,
                    transform.getTranslationY() / ModelUnits.PIXELS_PER_BLOCK,
                    transform.getTranslationZ() / ModelUnits.PIXELS_PER_BLOCK
                );
        }

    }

    /**
     * Faithful inventory-icon renderer ({@link ItemOptions.Type#GUI_ICON}): the representation a GUI
     * slot shows, routed by index membership rather than rendered anew. An id with a flat item entry
     * renders through the shared {@link Gui2D} path unchanged; an id absent from the item index but
     * backing a block (plain blocks and block-entities alike) renders through the isometric
     * {@link BlockRenderer}, which already distinguishes a plain block model from a
     * {@code BlockEntityRenderer} pose; an id backing neither draws the square
     * {@link MissingMesh#icon(int)} builds.
     * <p>
     * Neither routed branch adds any rendering of its own, so a flat-sprite icon is byte-identical to
     * {@link ItemOptions.Type#GUI_2D} and a block-backed icon to the isometric block render at the
     * same output frame. The unrouted one is a flat square rather than a posed cube because a slot
     * showing the missing model applies no rotation to it, so exactly one face is seen square-on.
     */
    public static final class GuiIcon implements Renderer<ItemOptions> {

        /**
         * Renderer context supplying the item / block index lookups that pick the branch.
         */
        private final @NotNull RendererContext context;

        /**
         * The shared flat 2D sub-renderer the flat-sprite branch delegates to.
         */
        private final @NotNull Gui2D gui2D;

        /**
         * The isometric block renderer the block / block-entity branch delegates to, built from the
         * shared context.
         */
        private final @NotNull BlockRenderer blockRenderer;

        /**
         * Constructs the faithful-icon sub-renderer, reusing the owning {@link ItemRenderer}'s flat 2D
         * sub-renderer and building an isometric {@link BlockRenderer} from the shared context.
         *
         * @param context the renderer context supplying pack / model / texture lookups
         * @param gui2D the shared flat 2D GUI icon sub-renderer
         */
        public GuiIcon(@NotNull RendererContext context, @NotNull Gui2D gui2D) {
            this.context = context;
            this.gui2D = gui2D;
            this.blockRenderer = new BlockRenderer(context);
        }

        /**
         * Renders the faithful inventory icon: the {@link Gui2D} flat sprite for an item-index id,
         * else the isometric {@link BlockRenderer} for a block-backed id. The block delegate renders
         * on a transparent background so {@link ItemRenderer#render} composites the caller's own
         * background exactly once.
         *
         * @param options the item render options
         * @return the faithful inventory icon, before the shared background composite
         */
        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            if (this.context.findItem(options.getItemId()).isPresent())
                return this.gui2D.render(options);
            if (this.context.findBlock(options.getItemId()).isPresent())
                return this.blockRenderer.render(adaptToBlock(options));
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
