package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.asset.rule.ItemContext;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.compose.GlintStage;
import lib.minecraft.renderer.engine.compose.ImageLayer;
import lib.minecraft.renderer.engine.compose.ImageLayerContext;
import lib.minecraft.renderer.engine.compose.LayerStack;
import lib.minecraft.renderer.engine.kit.BannerKit;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.kit.ItemStackKit;
import lib.minecraft.renderer.engine.kit.ShieldKit;
import lib.minecraft.renderer.engine.kit.TrimKit;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.face.SixFaces;
import lib.minecraft.renderer.options.ItemOptions;
import lib.minecraft.renderer.request.DyeColor;
import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.text.font.MinecraftFont;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders an {@link Item} as either a flat 2D GUI icon or a held 3D view by dispatching to one
 * of two sub-renderers based on {@link ItemOptions#getType()}.
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
 * {@link ModelEngine} with the item model's {@code thirdperson_righthand} display transform applied.</li>
 * </ul>
 * Shared item lookup, the glint-finalization tail, and the per-layer tint resolution live as
 * package-private static helpers on this class so both sub-renderers can reach them without
 * duplicating logic.
 */
public final class ItemRenderer implements Renderer<ItemOptions> {

    private final @NotNull Gui2D gui2D;
    private final @NotNull Held3D held3D;

    public ItemRenderer(@NotNull RendererContext context) {
        this.gui2D = new Gui2D(context);
        this.held3D = new Held3D(context);
    }

    @Override
    public @NotNull ImageData render(@NotNull ItemOptions options) {
        ImageData rendered = switch (options.getType()) {
            case GUI_2D -> this.gui2D.render(options);
            case HELD_3D -> this.held3D.render(options);
        };
        return options.getBackground().composite(rendered);
    }

    /**
     * Looks up an item by id in the renderer context, throwing a descriptive
     * {@link RenderException} when the item is missing.
     */
    static @NotNull Item requireItem(@NotNull RendererContext context, @NotNull String itemId) {
        return context.findItem(itemId)
            .orElseThrow(() -> new RenderException("No item registered for id '%s'", itemId));
    }

    /**
     * Resolves the item glint flag and composites the animated foil onto the finished 2D buffer.
     * Shared terminal step of the GUI and held-item paths, which derive the glint identically.
     */
    static @NotNull ImageData finalize2DItem(
        @NotNull Textures engine, @NotNull PixelBuffer buffer,
        @NotNull Item item, @NotNull ItemOptions options
    ) {
        return GlintStage.forItem(engine::tryResolveTexture, buffer, item, options);
    }

    /**
     * Model-space minimum-X bound for the flat-sprite item Z-axis slab.
     */
    private static final float FLAT_ITEM_SLAB_MIN_X = -0.45f;

    /**
     * Model-space maximum-X bound for the flat-sprite item Z-axis slab.
     */
    private static final float FLAT_ITEM_SLAB_MAX_X = 0.45f;

    /**
     * Model-space minimum-Z bound - the thin side of the flat sprite slab.
     */
    private static final float FLAT_ITEM_SLAB_MIN_Z = -0.02f;

    /**
     * Model-space maximum-Z bound - the thin side of the flat sprite slab.
     */
    private static final float FLAT_ITEM_SLAB_MAX_Z = 0.02f;

    /**
     * Prefix for multi-layer item texture keys ({@code layer0}, {@code layer1}, ...).
     */
    private static final @NotNull String LAYER_TEXTURE_PREFIX = "layer";

    /**
     * Item model display slot for the 3D held-item pose (vanilla {@code thirdperson_righthand}).
     */
    private static final @NotNull String DISPLAY_SLOT_HELD_3D = "thirdperson_righthand";

    /**
     * Item id suffix that flags a banner: {@code minecraft:white_banner}, etc.
     */
    private static final @NotNull String BANNER_ITEM_SUFFIX = "_banner";

    /**
     * The sole shield item id.
     */
    private static final @NotNull String SHIELD_ITEM_ID = "minecraft:shield";

    /**
     * The shield's plain (no banner pattern) base texture id - the atlas the 3D
     * {@code ShieldModel} samples for an undyed shield.
     */
    private static final @NotNull String SHIELD_NOPATTERN_TEXTURE_ID = "minecraft:entity/shield/shield_base_nopattern";

    /**
     * The shield item model's {@code display.gui} rotation ({@code [15, -25, -5]} pitch/yaw/roll).
     */
    private static final @NotNull EulerRotation SHIELD_GUI_ROTATION = new EulerRotation(15f, -25f, -5f);

    /**
     * The shield item model's {@code display.gui} scale ({@code 0.65}).
     */
    private static final float SHIELD_GUI_DISPLAY_SCALE = 0.65f;

    /**
     * Model-space translation (block units) that aligns the rendered shield's silhouette 1:1 with
     * the vanilla reference. The shield's silhouette is byte-identical in size to vanilla's
     * (270x489 px at the parity render size) but the vanilla GUI item pipeline seats the model
     * origin off-centre relative to this renderer's centre-on-origin projection; this offset is
     * {@code R^T} (the inverse {@code display.gui} rotation) applied to the measured
     * {@code (-29, -9)} px screen offset, so {@code camera * translate(offset)} reproduces it as a
     * pure post-rotation screen shift.
     */
    private static final @NotNull Vector3f SHIELD_ALIGN_OFFSET = new Vector3f(-0.0839f, 0.0189f, 0.0305f);

    /**
     * Pure-orthographic projection for the GUI shield render. The projection scale is the shield
     * item model's {@code display.gui} scale ({@code 0.65}), mirroring how the block-icon path
     * folds {@code block/block.json}'s {@code 0.625} {@code display.gui.scale} into
     * {@link Lens#ISOMETRIC_BLOCK}.
     */
    private static final @NotNull Lens SHIELD_PERSPECTIVE = Lens.orthographic(SHIELD_GUI_DISPLAY_SCALE);

    /**
     * Returns {@code true} when the item id is a banner or shield, which get composited through
     * {@link BannerKit} rather than the standard layered-sprite or overlay paths.
     */
    static boolean isBannerOrShield(@NotNull String itemId) {
        return itemId.equals(SHIELD_ITEM_ID) || itemId.endsWith(BANNER_ITEM_SUFFIX);
    }

    /**
     * Composites a banner or shield item onto {@code buffer}: dye-coloured field, then each
     * {@link ItemOptions#getBannerLayers()} layer blitted as a tinted grayscale mask.
     * {@link ItemOptions#getBaseDye()} drives the field colour - white when absent. Shields
     * route through the {@code entity/shield/} atlas; banners through {@code entity/banner/}.
     *
     * @param engine the texture engine for pattern resolution
     * @param buffer the output pixel buffer
     * @param itemId the item id (used to pick the banner vs. shield atlas variant)
     * @param options the render options carrying {@code baseDye} + {@code bannerLayers}
     * @return the composited buffer
     */
    static @NotNull PixelBuffer renderBannerOrShield(
        @NotNull Textures engine,
        @NotNull PixelBuffer buffer,
        @NotNull String itemId,
        @NotNull ItemOptions options
    ) {
        DyeColor baseDye = options.getBaseDye().orElse(DyeColor.Vanilla.WHITE);
        BannerKit.Variant variant = itemId.equals(SHIELD_ITEM_ID)
            ? BannerKit.Variant.SHIELD_ITEM
            : BannerKit.Variant.BANNER_ITEM;

        PixelBuffer composite = BannerKit.composite2D(engine, baseDye, options.getBannerLayers(), variant);
        buffer.blitScaled(composite, 0, 0, options.getOutputSize(), options.getOutputSize());
        return buffer;
    }

    /**
     * Composites a fresh banner / shield texture via {@link BannerKit#composite2D} and folds it
     * into the 3D held-item render path. Banners and shields both fall back to a thin-Z-slab using
     * the composited texture so the HELD_3D view reflects the pattern stack. Using the composited
     * texture for all six slab faces mirrors the flat-sprite fallback already used for other item
     * kinds.
     *
     * @param engine the model engine that also serves as the {@link Textures} for pattern
     *     resolution
     * @param itemId the item id (used to pick the banner vs. shield atlas variant)
     * @param options the render options carrying {@code baseDye} + {@code bannerLayers}
     * @return the list of triangles ready for rasterisation
     */
    static @NotNull ConcurrentList<VisibleTriangle> buildBannerOrShield3D(
        @NotNull ModelEngine engine,
        @NotNull String itemId,
        @NotNull ItemOptions options
    ) {
        DyeColor baseDye = options.getBaseDye().orElse(DyeColor.Vanilla.WHITE);
        boolean isShield = itemId.equals(SHIELD_ITEM_ID);
        BannerKit.Variant variant = isShield
            ? BannerKit.Variant.SHIELD_BLOCK_3D
            : BannerKit.Variant.BANNER_BLOCK_3D;

        PixelBuffer composite = BannerKit.composite2D(engine.textures(), baseDye, options.getBannerLayers(), variant);

        return BlockGeometryKit.buildBoxTriangles(
            new Vector3f(FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_Z),
            new Vector3f(FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_Z),
            SixFaces.uniform(composite),
            ColorMath.WHITE
        );
    }

    /**
     * Renders the plain {@code minecraft:shield} item as its vanilla 3D {@code ShieldModel} into
     * {@code buffer}. Mirrors the block-icon path ({@link lib.minecraft.renderer.BlockRenderer}'s
     * {@code Isometric3D}): {@link ShieldKit} builds the plate + handle geometry, the
     * {@code display.gui} pose drives a {@code T * R * S} model transform (translation, then the
     * {@code [15, -25, -5]} rotation, then the {@code 0.65} scale - vanilla's
     * {@code ItemTransform.apply} order), and {@link Shading#relightForItems3d} re-shades each
     * face with vanilla's {@code Lighting.ITEMS_3D} Lambertian. Rendered through an identity-camera
     * {@link ModelEngine} so the pose lives entirely in the model transform.
     *
     * @param context the renderer context for texture resolution
     * @param buffer the output buffer (the freshly created GUI buffer the shared tail consumes)
     * @param options the render options (unused beyond the buffer for the plain shield)
     */
    static void renderShield3D(
        @NotNull RendererContext context,
        @NotNull PixelBuffer buffer,
        @NotNull ItemOptions options
    ) {
        ModelEngine engine = new ModelEngine(context, Camera.fromPose(SHIELD_GUI_ROTATION, SHIELD_PERSPECTIVE));
        PixelBuffer texture = engine.textures().resolveTexture(SHIELD_NOPATTERN_TEXTURE_ID);
        ConcurrentList<VisibleTriangle> triangles = ShieldKit.buildShield3D(texture);
        triangles = ShieldKit.relightShield(triangles, SHIELD_GUI_ROTATION);

        Matrix4f modelTransform = Matrix4f.IDENTITY.translate(
            SHIELD_ALIGN_OFFSET.x(), SHIELD_ALIGN_OFFSET.y(), SHIELD_ALIGN_OFFSET.z());
        engine.rasterize(triangles, buffer, modelTransform);
    }

    /**
     * Resolves the effective ARGB tint for {@code layerN} of an item. When the item carries a
     * {@link LayerTint} for that layer (from its definition's {@code model.tints[]}), the colour
     * resolves from the matching render-option override, else the JSON default:
     * <ul>
     * <li>{@link LayerTint.Dye} - {@link ItemOptions#getLeatherColor()} → {@link ItemOptions#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Potion} - {@link ItemOptions#getPotionColor()} → the first
     * {@link ItemContext#potionEffects() potion effect}'s colour via
     * {@link RendererContext#findPotionEffectColor(String)} → {@link ItemOptions#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Firework} - {@link ItemOptions#getFireworkColor()} → {@link ItemOptions#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Constant} - the fixed value.</li>
     * </ul>
     * When the item has no tint for the layer, falls back to the vanilla {@code item/generated}
     * convention: the caller's {@link ItemOptions#getTintColor()} applies to the tintindex-0 slot
     * ({@link #tintIndexForLayer(Item, int)}), every other layer renders untinted. Returns
     * {@link ColorMath#WHITE} for an untinted layer.
     */
    static int resolveLayerTint(
        @NotNull RendererContext context,
        @NotNull Item item,
        int layerIndex,
        @NotNull ItemOptions options
    ) {
        List<LayerTint> tints = item.getTints();
        if (layerIndex < tints.size()) {
            return switch (tints.get(layerIndex)) {
                case LayerTint.Dye dye ->
                    options.getLeatherColor().or(options::getTintColor).orElse(dye.defaultColor());
                case LayerTint.Potion potion ->
                    options.getPotionColor()
                        .or(() -> options.getContext().potionEffects().stream().findFirst().flatMap(context::findPotionEffectColor))
                        .or(options::getTintColor).orElse(potion.defaultColor());
                case LayerTint.Firework firework ->
                    options.getFireworkColor().or(options::getTintColor).orElse(firework.defaultColor());
                case LayerTint.Constant constant -> constant.argb();
            };
        }
        int tint = options.getTintColor().orElse(ColorMath.WHITE);
        return tint != ColorMath.WHITE && tintIndexForLayer(item, layerIndex) == 0 ? tint : ColorMath.WHITE;
    }

    /**
     * Composites an item's {@code layerN} sprites into a native-resolution {@link PixelBuffer},
     * multiplying each layer's {@link #resolveLayerTint resolved tint} in. Used by the HELD_3D
     * flat-slab path so tinted items (leather armour, potions, firework stars) carry their colour
     * onto the 3D slab the same way the GUI path tints them.
     */
    static @NotNull PixelBuffer composeTintedLayers(
        @NotNull RendererContext context,
        @NotNull ModelEngine engine,
        @NotNull Item item,
        @NotNull ItemOptions options
    ) {
        String layer0Ref = engine.textures().resolveLayer0(item, options);
        if (layer0Ref == null || layer0Ref.isBlank())
            throw new RenderException("Item '%s' has no elements and no layer0 - nothing to render in Held3D path", item.getId().id());
        PixelBuffer base = engine.textures().resolveTexture(layer0Ref);
        PixelBuffer composite = PixelBuffer.create(base.width(), base.height());

        int layerIndex = 0;
        while (true) {
            String textureRef = layerIndex == 0 ? layer0Ref : item.getTextures().get(LAYER_TEXTURE_PREFIX + layerIndex);
            if (textureRef == null || textureRef.isBlank()) break;
            PixelBuffer layer = engine.textures().resolveTexture(textureRef);
            int color = resolveLayerTint(context, item, layerIndex, options);
            // ColorMath.tint returns a multiplied copy (alpha preserved); blit composites it
            // source-over so layer0 lands cleanly even when the composite is still empty.
            PixelBuffer drawable = color != ColorMath.WHITE ? ColorMath.tint(layer, color) : layer;
            composite.blit(drawable, 0, 0);
            layerIndex++;
        }
        return composite;
    }

    /**
     * Looks up the {@code tintindex} that applies to {@code layerN} for a flat item. Prefers the
     * model-declared tintindex on any element face whose texture reference resolves to the layer,
     * falling back to the vanilla {@code item/generated} convention ({@code layerN} has tintindex
     * {@code N}) when the resolved model has no elements - which is the common case for flat
     * items.
     *
     * @param item the item being rendered
     * @param layerIndex the layer index being rendered
     * @return the tintindex for the layer, or {@code -1} when the layer should render untinted
     */
    static int tintIndexForLayer(@NotNull Item item, int layerIndex) {
        ConcurrentList<ModelElement> elements = item.getModel().getElements();
        if (elements.isEmpty()) {
            // Vanilla item/generated convention: layer N has tintindex N.
            return layerIndex;
        }

        ConcurrentMap<String, String> variables = item.getModel().getTextures();
        String layerKey = LAYER_TEXTURE_PREFIX + layerIndex;
        String layerRef = variables.get(layerKey);
        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String faceRef = face.getTexture();
                if (faceRef.equals("#" + layerKey) || faceRef.equals(layerRef))
                    return face.getTintIndex();

                String resolved = Textures.resolveTextureReference(faceRef, variables);
                if (resolved.equals(layerRef))
                    return face.getTintIndex();
            }
        }
        return -1;
    }

    /**
     * Renders the standard layered-sprite path for an item. Each {@code layerN} texture is
     * composited in order, multiplying in the layer's {@link #resolveLayerTint resolved tint} -
     * the item-definition {@link LayerTint} (leather dye, potion colour, firework colour) when
     * present, otherwise the caller's {@link ItemOptions#getTintColor()} on the tintindex-0 slot.
     * A tinted layer is multiplied at its native resolution then scaled up, so the tint covers the
     * full icon rather than a corner. Trim overlay textures are resolved via
     * {@link TrimKit#resolveFromTextureRef} so the renderer doesn't depend on material-specific
     * PNGs being shipped in the pack.
     */
    static void renderStandardLayers(
        @NotNull RendererContext context,
        @NotNull Textures engine,
        @NotNull PixelBuffer buffer,
        @NotNull Item item,
        @NotNull ItemOptions options
    ) {
        int size = options.getOutputSize();
        int layerIndex = 0;
        while (true) {
            String layerKey = LAYER_TEXTURE_PREFIX + layerIndex;
            // CIT replaces only layer0; layer1+ pass through unchanged. resolveLayer0 returns the
            // model-bound id when no rule matches or the context is empty, matching the existing
            // textures-map lookup.
            String textureRef = layerIndex == 0
                ? engine.resolveLayer0(item, options)
                : item.getTextures().get(layerKey);
            if (textureRef == null || textureRef.isBlank()) break;

            if (TrimKit.isTrimTexture(textureRef)) {
                TrimKit.resolveFromTextureRef(engine, textureRef)
                    .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, size, size));
            } else {
                PixelBuffer layer = engine.resolveTexture(textureRef);
                int color = resolveLayerTint(context, item, layerIndex, options);
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
     * with per-layer {@link #resolveLayerTint tint}, damage bar, stack count, and glint animation.
     * The shield routes through {@link #renderShield3D} and banners through
     * {@link #renderBannerOrShield} instead of the standard layer loop.
     */
    @RequiredArgsConstructor
    public static final class Gui2D implements Renderer<ItemOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Item item = requireItem(this.context, options.getItemId());
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer buffer = engine.createBuffer(options.getOutputSize(), options.getOutputSize());

            // Compose the icon as an ordered ImageLayer stack (base sprite/banner/shield, then the
            // trim, damage-bar, and stack-count decorations) so callers can splice their own passes
            // in via ItemOptions.layerDecorator. The terminal glint is the finalisation step, not a
            // layer, because it expands the single buffer into one or many animation frames.
            ImageLayerContext ctx = new ImageLayerContext(this.context, engine.textures(), item, options);
            LayerStack<ImageLayer> stack = options.getLayerDecorator().apply(buildGuiLayers(ctx));
            for (ImageLayer layer : stack.ordered()) layer.apply(buffer);

            return finalize2DItem(engine.textures(), buffer, item, options);
        }

        /**
         * Builds the default GUI icon layer stack in vanilla pass order: a base sprite/banner/shield
         * layer, then the conditional trim, damage-bar, and stack-count decorations. Each layer is the
         * verbatim pass that previously ran inline in {@link #render}, capturing the render {@code ctx}.
         */
        private static @NotNull LayerStack<ImageLayer> buildGuiLayers(@NotNull ImageLayerContext ctx) {
            ItemOptions options = ctx.options();
            LayerStack<ImageLayer> stack = new LayerStack<>();

            if (options.getItemId().equals(SHIELD_ITEM_ID))
                stack.append(ItemOptions.Slot.BASE, frame -> renderShield3D(ctx.context(), frame, options));
            else if (isBannerOrShield(options.getItemId()))
                stack.append(ItemOptions.Slot.BASE, frame ->
                    renderBannerOrShield(ctx.textures(), frame, options.getItemId(), options));
            else
                stack.append(ItemOptions.Slot.BASE, frame ->
                    renderStandardLayers(ctx.context(), ctx.textures(), frame, ctx.item(), options));

            if (options.getTrimSlot().isPresent() && options.getTrimColor().isPresent())
                stack.append(ItemOptions.Slot.TRIM, frame ->
                    TrimKit.resolve(ctx.textures(), options.getTrimSlot().get().getKey(), options.getTrimColor().get().getKey())
                        .ifPresent(trim -> frame.blitScaled(trim, 0, 0, options.getOutputSize(), options.getOutputSize())));

            if (options.isShowDamageBar())
                stack.append(ItemOptions.Slot.DAMAGE_BAR, frame ->
                    ItemStackKit.drawDamageBar(frame, options.getContext().damage(), ctx.item().getMaxDurability()));

            if (options.getContext().stackCount() > 1)
                stack.append(ItemOptions.Slot.STACK_COUNT, frame ->
                    ItemStackKit.drawStackCount(frame, options.getContext().stackCount(), MinecraftFont.REGULAR));

            return stack;
        }

    }

    /**
     * Held 3D item renderer. Dispatches on whether the item model supplies element boxes - block
     * items with non-empty element lists build real cubes via {@link BlockGeometryKit#buildFromElements},
     * while flat sprite items fall back to a thin textured slab derived from {@code layer0}.
     * Both branches feed the same {@link ModelEngine#rasterize} overload with the item's
     * {@code thirdperson_righthand} display transform.
     * <p>
     * Banner and shield items route through {@link ItemRenderer#buildBannerOrShield3D} so the
     * HELD_3D view shows the composited pattern stack - banners get the real flag geometry from
     * the {@code minecraft:banner} block-entity model when it is registered, and shields fall
     * back to a thin slab using the composited shield texture.
     * <p>
     * Flat-sprite items composite their (tinted) layer stack into a native-size
     * {@link PixelBuffer} via {@link ItemRenderer#composeTintedLayers} and feed the result into the
     * thin-Z-slab path, so the held view reflects the same per-layer tint as the GUI icon.
     */
    @RequiredArgsConstructor
    public static final class Held3D implements Renderer<ItemOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Item item = requireItem(this.context, options.getItemId());

            // Identity-pose camera carrying only the projection's lens: the held-item pose lives
            // entirely in the model's display transform (applied as the modelTransform below), so the
            // camera pose stays identity and only the rotation-independent lens comes from resolve().
            ModelEngine engine = new ModelEngine(this.context, Camera.identity(options.getProjection().resolve().camera().lens()));
            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
            int tint = options.getTintColor().orElse(ColorMath.WHITE);

            ConcurrentList<VisibleTriangle> triangles;
            if (isBannerOrShield(options.getItemId())) {
                triangles = buildBannerOrShield3D(engine, options.getItemId(), options);
            } else if (!item.getModel().getElements().isEmpty()) {
                // Element-based path - held block items and any custom item whose model JSON
                // supplies 'elements'. The element bounds and face bindings are fully resolved
                // at pipeline time.
                Map<String, PixelBuffer> faceTextures = loadFaceTextures(engine, item);
                triangles = BlockGeometryKit.buildFromElements(item.getModel().getElements(), faceTextures, tint);
            } else {
                // Flat sprite fallback - composite the (tinted) layer stack into one texture and
                // render it as a thin Z-axis slab. composeTintedLayers folds in each layer's
                // LayerTint (leather dye, potion colour, firework colour) and the caller's
                // tintColor, so the held view carries the same colour as the GUI icon. Degenerate
                // cases (no elements AND no layer0) throw inside composeTintedLayers.
                PixelBuffer texture = composeTintedLayers(this.context, engine, item, options);
                triangles = BlockGeometryKit.buildBoxTriangles(
                    new Vector3f(FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_Z),
                    new Vector3f(FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_Z),
                    SixFaces.uniform(texture),
                    ColorMath.WHITE
                );
            }

            Matrix4f displayTransform = resolveDisplayTransform(item, DISPLAY_SLOT_HELD_3D);
            engine.rasterize(triangles, buffer, displayTransform);

            return finalize2DItem(engine.textures(), buffer, item, options);
        }

        /**
         * Walks the item model's element face texture references, dereferences {@code #var}
         * chains against the model's texture bindings, and loads each unique resolved id into a
         * {@link PixelBuffer}. The returned map is keyed by the original face reference string
         * (including any leading {@code #}), which matches what
         * {@link BlockGeometryKit#buildFromElements} expects.
         */
        private static @NotNull Map<String, PixelBuffer> loadFaceTextures(
            @NotNull ModelEngine engine,
            @NotNull Item item
        ) {
            return Textures.loadElementFaceTextures(
                item.getModel().getElements(), item.getModel().getTextures(),
                id -> Optional.of(engine.textures().resolveTexture(id)));
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
            ModelTransform transform = item.getModel().getDisplay().get(slot);
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
                    transform.getTranslationX() / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK,
                    transform.getTranslationY() / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK,
                    transform.getTranslationZ() / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK
                );
        }

    }

}
