package dev.sbs.renderer;

import dev.sbs.renderer.engine.IsometricEngine;
import dev.sbs.renderer.engine.ModelEngine;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.kit.GeometryKit;
import dev.sbs.renderer.kit.GlintKit;
import dev.sbs.renderer.kit.ItemBarKit;
import dev.sbs.renderer.kit.TrimKit;
import dev.sbs.renderer.asset.Entity;
import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.binding.DyeColor;
import dev.sbs.renderer.asset.model.ModelElement;
import dev.sbs.renderer.asset.model.ModelFace;
import dev.sbs.renderer.asset.model.ModelTransform;
import dev.sbs.renderer.kit.BannerKit;
import dev.sbs.renderer.kit.EntityGeometryKit;
import dev.sbs.renderer.options.ItemOptions;
import dev.sbs.renderer.tensor.Matrix4f;
import dev.sbs.renderer.tensor.Vector3f;
import dev.sbs.renderer.text.font.MinecraftFont;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
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
 * glint animation. Items carrying an {@link Item.Overlay} route through a per-kind helper that
 * honours the vanilla base + overlay + tint composition (leather armor, potions, spawn eggs,
 * firework stars, tipped arrows); items without an overlay fall through to the standard
 * layered-sprite path that respects per-face {@code tintindex}.</li>
 * <li>{@link Held3D} dispatches on whether the item's model provides element boxes - block items
 * build real cubes via {@link GeometryKit#buildFromElements}, flat sprite items fall back to a
 * thin textured slab. Both paths route through {@link ModelEngine} with the item model's
 * {@code thirdperson_righthand} display transform applied.</li>
 * </ul>
 * Shared item lookup, the glint-finalization tail, and per-kind overlay helpers live as
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
        return switch (options.getType()) {
            case GUI_2D -> this.gui2D.render(options);
            case HELD_3D -> this.held3D.render(options);
        };
    }

    /**
     * Looks up an item by id in the renderer context, throwing a descriptive
     * {@link RendererException} when the item is missing.
     */
    static @NotNull Item requireItem(@NotNull RendererContext context, @NotNull String itemId) {
        return context.findItem(itemId)
            .orElseThrow(() -> new RendererException("No item registered for id '%s'", itemId));
    }

    /** The "water" potion colour - used as the fallback when no potion effect is supplied. */
    private static final int DEFAULT_POTION_ARGB = 0xFF385DC6;

    /** Item id suffix that flags a banner: {@code minecraft:white_banner}, etc. */
    private static final @NotNull String BANNER_SUFFIX = "_banner";

    /** The sole shield item id. */
    private static final @NotNull String SHIELD_ITEM_ID = "minecraft:shield";

    /**
     * Returns {@code true} when the item id is a banner or shield, which get composited through
     * {@link BannerKit} rather than the standard layered-sprite or overlay paths.
     */
    static boolean isBannerOrShield(@NotNull String itemId) {
        return itemId.equals(SHIELD_ITEM_ID) || itemId.endsWith(BANNER_SUFFIX);
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
        @NotNull TextureEngine engine,
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
     * into the 3D held-item render path. Banners route through the
     * {@code minecraft:banner} block-entity model (single flag bone) when the context knows it,
     * so the HELD_3D view shows proper 3D flag geometry rather than a flat sprite. Shields - and
     * banners when the block-entity model is absent - fall back to the existing thin-Z-slab
     * treatment so callers still get a 3D frame with the correct pattern stack applied.
     *
     * @param context the renderer context used to resolve the banner entity model
     * @param engine the model engine that also serves as the {@link TextureEngine} for pattern
     *     resolution
     * @param itemId the item id (used to pick the banner vs. shield atlas variant)
     * @param options the render options carrying {@code baseDye} + {@code bannerLayers}
     * @return the list of triangles ready for rasterisation
     */
    static @NotNull ConcurrentList<VisibleTriangle> buildBannerOrShield3D(
        @NotNull RendererContext context,
        @NotNull ModelEngine engine,
        @NotNull String itemId,
        @NotNull ItemOptions options
    ) {
        DyeColor baseDye = options.getBaseDye().orElse(DyeColor.Vanilla.WHITE);
        boolean isShield = itemId.equals(SHIELD_ITEM_ID);
        BannerKit.Variant variant = isShield
            ? BannerKit.Variant.SHIELD_BLOCK_3D
            : BannerKit.Variant.BANNER_BLOCK_3D;

        PixelBuffer composite = BannerKit.composite2D(engine, baseDye, options.getBannerLayers(), variant);

        // Banners get real 3D geometry when the block-entity model is registered; shields and
        // banners-without-model fall back to a thin Z-slab using the composited texture so the
        // HELD_3D view still reflects the pattern stack. Using the composited texture for all
        // six slab faces mirrors the flat-sprite fallback already used for other item kinds.
        if (!isShield) {
            Optional<Entity> bannerEntity = context.findEntity("minecraft:banner");
            if (bannerEntity.isPresent()) {
                return EntityGeometryKit.buildTriangles(bannerEntity.get().getModel(), composite).triangles();
            }
        }

        PixelBuffer[] faces = new PixelBuffer[]{ composite, composite, composite, composite, composite, composite };
        return GeometryKit.box(
            new Vector3f(-0.45f, -0.45f, -0.02f),
            new Vector3f(0.45f, 0.45f, 0.02f),
            faces,
            ColorMath.WHITE
        );
    }

    /**
     * Builds a native-sized composite of a leather-armor item: base hide layer untinted, overlay
     * dye layer tinted via {@link BlendMode#MULTIPLY}. Tint precedence is
     * {@link ItemOptions#getLeatherColor()} → {@link ItemOptions#getTintColor()} →
     * {@link Item.Overlay.Leather#defaultColor()} ({@code #A06540}). Returns a fresh
     * {@link PixelBuffer} at the base texture's native dimensions, so the 2D path can scale it
     * up while the 3D path can feed it directly into {@link GeometryKit#box} as the face texture.
     */
    static @NotNull PixelBuffer composeLeatherOverlay(
        @NotNull TextureEngine engine,
        @NotNull Item.Overlay.Leather overlay,
        @NotNull ItemOptions options
    ) {
        int tint = options.getLeatherColor()
            .or(options::getTintColor)
            .orElse(overlay.defaultColor());

        PixelBuffer base = engine.resolveTexture(overlay.baseTexture());
        PixelBuffer dye = engine.resolveTexture(overlay.overlayTexture());
        PixelBuffer composite = PixelBuffer.create(base.width(), base.height());
        composite.blit(base, 0, 0);
        composite.blitTinted(dye, 0, 0, tint, BlendMode.MULTIPLY);
        return composite;
    }

    /**
     * Composites a leather-armor item onto {@code buffer} by scaling the native-size composite
     * produced by {@link #composeLeatherOverlay} up to {@link ItemOptions#getOutputSize()}.
     */
    static @NotNull PixelBuffer renderLeatherOverlay(
        @NotNull RasterEngine engine,
        @NotNull PixelBuffer buffer,
        @NotNull Item.Overlay.Leather overlay,
        @NotNull ItemOptions options
    ) {
        PixelBuffer composite = composeLeatherOverlay(engine, overlay, options);
        int size = options.getOutputSize();
        buffer.blitScaled(composite, 0, 0, size, size);
        return buffer;
    }

    /**
     * Builds a native-sized composite of a firework star: body untinted, center overlay tinted
     * via {@link BlendMode#MULTIPLY}. Tint precedence is {@link ItemOptions#getFireworkColor()}
     * → {@link ItemOptions#getTintColor()} → {@link Item.Overlay.Firework#defaultColor()}
     * ({@code #808080}, a visible placeholder). Vanilla firework star colours come from NBT;
     * callers resolve {@code Fireworks.Explosions[0].Colors[0]} and pass it as
     * {@code fireworkColor}.
     */
    static @NotNull PixelBuffer composeFireworkStar(
        @NotNull TextureEngine engine,
        @NotNull Item.Overlay.Firework overlay,
        @NotNull ItemOptions options
    ) {
        int tint = options.getFireworkColor()
            .or(options::getTintColor)
            .orElse(overlay.defaultColor());

        PixelBuffer base = engine.resolveTexture(overlay.baseTexture());
        PixelBuffer center = engine.resolveTexture(overlay.overlayTexture());
        PixelBuffer composite = PixelBuffer.create(base.width(), base.height());
        composite.blit(base, 0, 0);
        composite.blitTinted(center, 0, 0, tint, BlendMode.MULTIPLY);
        return composite;
    }

    /**
     * Composites a firework star onto {@code buffer} by scaling the native-size composite
     * produced by {@link #composeFireworkStar} up to {@link ItemOptions#getOutputSize()}.
     */
    static @NotNull PixelBuffer renderFireworkStar(
        @NotNull RasterEngine engine,
        @NotNull PixelBuffer buffer,
        @NotNull Item.Overlay.Firework overlay,
        @NotNull ItemOptions options
    ) {
        PixelBuffer composite = composeFireworkStar(engine, overlay, options);
        int size = options.getOutputSize();
        buffer.blitScaled(composite, 0, 0, size, size);
        return buffer;
    }

    /**
     * Builds a native-sized composite of a potion-shaped item: base untinted, overlay tinted
     * via {@link BlendMode#MULTIPLY}. Tint precedence is {@link ItemOptions#getPotionColor()}
     * → the first potion effect in
     * {@link dev.sbs.renderer.pipeline.pack.ItemContext#potionEffects()} resolved via
     * {@link RendererContext#potionEffectColor(String)} → {@link ItemOptions#getTintColor()} →
     * the water fallback ({@code #385DC6}).
     */
    static @NotNull PixelBuffer composePotionOverlay(
        @NotNull RendererContext context,
        @NotNull TextureEngine engine,
        @NotNull String baseTexture,
        @NotNull String overlayTexture,
        @NotNull ItemOptions options
    ) {
        int tint = options.getPotionColor()
            .or(() -> options.getContext().potionEffects().stream()
                .findFirst()
                .flatMap(context::potionEffectColor))
            .or(options::getTintColor)
            .orElse(DEFAULT_POTION_ARGB);

        PixelBuffer base = engine.resolveTexture(baseTexture);
        PixelBuffer liquid = engine.resolveTexture(overlayTexture);
        PixelBuffer composite = PixelBuffer.create(base.width(), base.height());
        composite.blit(base, 0, 0);
        composite.blitTinted(liquid, 0, 0, tint, BlendMode.MULTIPLY);
        return composite;
    }

    /**
     * Dispatches to the matching {@code compose*} helper for the given {@link Item.Overlay}
     * kind, returning a native-sized composite suitable for the thin-Z-slab HELD_3D path.
     * Mirrors the {@link Gui2D} overlay switch so 2D and 3D paths share the same composition
     * semantics.
     */
    static @NotNull PixelBuffer composeOverlayTexture(
        @NotNull RendererContext context,
        @NotNull TextureEngine engine,
        @NotNull Item.Overlay overlay,
        @NotNull ItemOptions options
    ) {
        return switch (overlay) {
            case Item.Overlay.Leather leather ->
                composeLeatherOverlay(engine, leather, options);
            case Item.Overlay.Potion potion ->
                composePotionOverlay(context, engine, potion.baseTexture(), potion.overlayTexture(), options);
            case Item.Overlay.TippedArrow tippedArrow ->
                composePotionOverlay(context, engine, tippedArrow.baseTexture(), tippedArrow.overlayTexture(), options);
            case Item.Overlay.Firework firework ->
                composeFireworkStar(engine, firework, options);
        };
    }

    /**
     * Composites a potion-shaped item onto {@code buffer} by scaling the native-size composite
     * produced by {@link #composePotionOverlay} up to {@link ItemOptions#getOutputSize()}.
     */
    static @NotNull PixelBuffer renderPotionOverlay(
        @NotNull RendererContext context,
        @NotNull RasterEngine engine,
        @NotNull PixelBuffer buffer,
        @NotNull String baseTexture,
        @NotNull String overlayTexture,
        @NotNull ItemOptions options
    ) {
        PixelBuffer composite = composePotionOverlay(context, engine, baseTexture, overlayTexture, options);
        int size = options.getOutputSize();
        buffer.blitScaled(composite, 0, 0, size, size);
        return buffer;
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
        String layerKey = "layer" + layerIndex;
        String layerRef = variables.get(layerKey);
        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String faceRef = face.getTexture();
                if (faceRef.equals("#" + layerKey) || faceRef.equals(layerRef)) {
                    return face.getTintIndex();
                }
                String resolved = TextureEngine.dereferenceVariable(faceRef, variables);
                if (layerRef != null && resolved.equals(layerRef)) {
                    return face.getTintIndex();
                }
            }
        }
        return -1;
    }

    /**
     * Renders the standard layered-sprite path for an item without an {@link Item.Overlay}. Each
     * {@code layerN} texture is composited in order, with {@link ItemOptions#getTintColor()}
     * applied only to layers whose {@link #tintIndexForLayer(Item, int) tintindex} is {@code 0} -
     * the primary tintable slot by vanilla convention. Trim overlay textures are resolved via
     * {@link TrimKit#resolveFromTextureRef} so the renderer doesn't depend on material-specific
     * PNGs being shipped in the pack.
     */
    static void renderStandardLayers(
        @NotNull RasterEngine engine,
        @NotNull PixelBuffer buffer,
        @NotNull Item item,
        @NotNull ItemOptions options
    ) {
        int size = options.getOutputSize();
        int tint = options.getTintColor().orElse(ColorMath.WHITE);
        int layerIndex = 0;
        while (true) {
            String layerKey = "layer" + layerIndex;
            String textureRef = item.getTextures().get(layerKey);
            if (textureRef == null || textureRef.isBlank()) break;

            if (TrimKit.isTrimTexture(textureRef)) {
                TrimKit.resolveFromTextureRef(engine, textureRef)
                    .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, size, size));
            } else {
                PixelBuffer layer = engine.resolveTexture(textureRef);
                int layerTintIndex = tintIndexForLayer(item, layerIndex);
                if (tint != ColorMath.WHITE && layerTintIndex == 0) {
                    buffer.blitTinted(layer, 0, 0, tint, BlendMode.MULTIPLY);
                } else {
                    buffer.blitScaled(layer, 0, 0, size, size);
                }
            }
            layerIndex++;
        }
    }

    /**
     * Flat 2D GUI icon renderer. Composes layered sprites ({@code layer0}, {@code layer1}, ...)
     * with optional {@link ItemOptions#getTintColor() tint}, damage bar, stack count, and glint
     * animation. Items carrying an {@link Item.Overlay} route through the matching per-kind
     * helper instead of the standard layer loop.
     */
    @RequiredArgsConstructor
    public static final class Gui2D implements Renderer<ItemOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Item item = requireItem(this.context, options.getItemId());
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer buffer = engine.createBuffer(options.getOutputSize(), options.getOutputSize());

            Optional<Item.Overlay> overlay = item.getOverlay();
            if (overlay.isPresent()) {
                switch (overlay.get()) {
                    case Item.Overlay.Leather leather ->
                        renderLeatherOverlay(engine, buffer, leather, options);
                    case Item.Overlay.Potion potion ->
                        renderPotionOverlay(this.context, engine, buffer, potion.baseTexture(), potion.overlayTexture(), options);
                    case Item.Overlay.TippedArrow tippedArrow ->
                        renderPotionOverlay(this.context, engine, buffer, tippedArrow.baseTexture(), tippedArrow.overlayTexture(), options);
                    case Item.Overlay.Firework firework ->
                        renderFireworkStar(engine, buffer, firework, options);
                }
            } else if (isBannerOrShield(options.getItemId())) {
                renderBannerOrShield(engine, buffer, options.getItemId(), options);
            } else {
                renderStandardLayers(engine, buffer, item, options);
            }

            if (options.getTrimSlot().isPresent() && options.getTrimColor().isPresent())
                TrimKit.resolve(engine, options.getTrimSlot().get().getKey(), options.getTrimColor().get().getKey())
                    .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, options.getOutputSize(), options.getOutputSize()));

            if (options.isShowDamageBar())
                ItemBarKit.drawDamageBar(buffer, options.getContext().damage(), item.getMaxDurability());

            if (options.getContext().stackCount() > 1)
                ItemBarKit.drawStackCount(buffer, options.getContext().stackCount(), MinecraftFont.REGULAR);

            return engine.finaliseWithGlint(buffer, options.isEnchanted(), GlintKit.GlintOptions.itemDefault(options.getFramesPerSecond()));
        }

    }

    /**
     * Held 3D item renderer. Dispatches on whether the item model supplies element boxes - block
     * items with non-empty element lists build real cubes via {@link GeometryKit#buildFromElements},
     * while flat sprite items fall back to a thin textured slab derived from {@code layer0}.
     * Both branches feed the same {@link ModelEngine#rasterize} overload with the item's
     * {@code thirdperson_righthand} display transform.
     * <p>
     * Banner and shield items route through {@link ItemRenderer#buildBannerOrShield3D} so the
     * HELD_3D view shows the composited pattern stack - banners get the real flag geometry from
     * the {@code minecraft:banner} block-entity model when it is registered, and shields fall
     * back to a thin slab using the composited shield texture.
     * <p>
     * Overlay items (leather, potion, tipped arrow, firework) composite their base + tinted
     * overlay into a native-size {@link PixelBuffer} via the shared {@code compose*} helpers
     * and feed the result into the same thin-Z-slab path as plain flat sprites, so the held
     * view reflects the correct tint without a separate 3D composition stage.
     */
    @RequiredArgsConstructor
    public static final class Held3D implements Renderer<ItemOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Item item = requireItem(this.context, options.getItemId());

            ModelEngine engine = new ModelEngine(this.context);
            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
            int tint = options.getTintColor().orElse(ColorMath.WHITE);

            ConcurrentList<VisibleTriangle> triangles;
            if (item.getOverlay().isPresent()) {
                PixelBuffer overlayTexture = composeOverlayTexture(this.context, engine, item.getOverlay().get(), options);
                PixelBuffer[] faces = new PixelBuffer[]{ overlayTexture, overlayTexture, overlayTexture, overlayTexture, overlayTexture, overlayTexture };
                triangles = GeometryKit.box(
                    new Vector3f(-0.45f, -0.45f, -0.02f),
                    new Vector3f(0.45f, 0.45f, 0.02f),
                    faces,
                    ColorMath.WHITE
                );
            } else if (isBannerOrShield(options.getItemId())) {
                triangles = buildBannerOrShield3D(this.context, engine, options.getItemId(), options);
            } else if (!item.getModel().getElements().isEmpty()) {
                // Element-based path - held block items and any custom item whose model JSON
                // supplies 'elements'. The element bounds and face bindings are fully resolved
                // at pipeline time.
                Map<String, PixelBuffer> faceTextures = loadFaceTextures(engine, item);
                triangles = GeometryKit.buildFromElements(item.getModel().getElements(), faceTextures, tint);
            } else {
                // Flat sprite fallback - layer0 rendered as a thin Z-axis slab. Matches the
                // previous behaviour for item/generated and item/handheld parented items.
                String layerRef = item.getTextures().get("layer0");
                if (layerRef == null || layerRef.isBlank()) {
                    // Degenerate: no element model and no layer0. Fall back to the GUI path so
                    // we still emit a sensible frame rather than throwing.
                    return new Gui2D(this.context).render(options);
                }

                PixelBuffer texture = engine.resolveTexture(layerRef);
                PixelBuffer[] faces = new PixelBuffer[]{ texture, texture, texture, texture, texture, texture };
                triangles = GeometryKit.box(
                    new Vector3f(-0.45f, -0.45f, -0.02f),
                    new Vector3f(0.45f, 0.45f, 0.02f),
                    faces,
                    tint
                );
            }

            Matrix4f displayTransform = resolveDisplayTransform(item, "thirdperson_righthand");
            engine.rasterize(triangles, buffer, PerspectiveParams.GUI_ITEM, displayTransform);

            return engine.finaliseWithGlint(buffer, options.isEnchanted(), GlintKit.GlintOptions.itemDefault(options.getFramesPerSecond()));
        }

        /**
         * Walks the item model's element face texture references, dereferences {@code #var}
         * chains against the model's texture bindings, and loads each unique resolved id into a
         * {@link PixelBuffer}. The returned map is keyed by the original face reference string
         * (including any leading {@code #}), which matches what
         * {@link GeometryKit#buildFromElements} expects.
         */
        private static @NotNull Map<String, PixelBuffer> loadFaceTextures(
            @NotNull ModelEngine engine,
            @NotNull Item item
        ) {
            Map<String, PixelBuffer> result = new HashMap<>();
            ConcurrentMap<String, String> variables = item.getModel().getTextures();
            for (ModelElement element : item.getModel().getElements()) {
                for (ModelFace face : element.getFaces().values()) {
                    String ref = face.getTexture();
                    if (ref.isBlank() || result.containsKey(ref)) continue;
                    String resolvedId = TextureEngine.dereferenceVariable(ref, variables);
                    if (resolvedId.startsWith("#")) continue;
                    result.put(ref, engine.resolveTexture(resolvedId));
                }
            }
            return result;
        }

        /**
         * Resolves the item model's display transform for the given slot (e.g.
         * {@code thirdperson_righthand}) into a {@link Matrix4f}. Falls back to the identity
         * when the slot is not defined, which matches vanilla's behaviour for items with no
         * display metadata.
         * <p>
         * Rotation composition matches vanilla's
         * {@code PoseStack.mulPose(Quaternionf.rotationXYZ(...))} call. The JOML quaternion
         * {@code q_x * q_y * q_z} applies rotations innermost-first when transforming a
         * vector, so the equivalent column-vector matrix is {@code R_x * R_y * R_z} and under
         * this codebase's row-vector convention the correct composition is the transpose,
         * {@code R_z^T * R_y^T * R_x^T} - built here via
         * {@link IsometricEngine#buildGuiDisplayTransform(float, float, float)}.
         */
        private static @NotNull Matrix4f resolveDisplayTransform(@NotNull Item item, @NotNull String slot) {
            ModelTransform transform = item.getModel().getDisplay().get(slot);
            if (transform == null) return Matrix4f.IDENTITY;

            Matrix4f scale = Matrix4f.createScale(transform.getScaleX(), transform.getScaleY(), transform.getScaleZ());
            Matrix4f rotation = IsometricEngine.buildGuiDisplayTransform(
                transform.getRotationX(), transform.getRotationY(), transform.getRotationZ());
            // Vanilla display transforms use sub-unit translation values in {@code /16} space;
            // apply them to the model vertex positions directly since our unit cube is already
            // normalized.
            Matrix4f translation = Matrix4f.createTranslation(
                transform.getTranslationX() / 16f,
                transform.getTranslationY() / 16f,
                transform.getTranslationZ() / 16f
            );
            return scale.multiply(rotation).multiply(translation);
        }

    }

}
