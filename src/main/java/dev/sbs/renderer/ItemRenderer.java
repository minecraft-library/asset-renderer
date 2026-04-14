package dev.sbs.renderer;

import dev.sbs.renderer.engine.ModelEngine;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.kit.GeometryKit;
import dev.sbs.renderer.kit.GlintKit;
import dev.sbs.renderer.kit.ItemBarKit;
import dev.sbs.renderer.kit.TrimKit;
import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.model.ModelElement;
import dev.sbs.renderer.asset.model.ModelFace;
import dev.sbs.renderer.asset.model.ModelTransform;
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

    /**
     * Wraps a finished buffer into an {@link ImageData} with optional glint animation. Both the
     * GUI and held paths share this tail: if the item is not enchanted, or the active pack stack
     * has no glint texture, the buffer is returned as a single-frame static image; otherwise a
     * scrolling glint overlay is composed and the frames are emitted at
     * {@link ItemOptions#getFramesPerSecond()}.
     */
    static @NotNull ImageData finaliseFrames(
        @NotNull PixelBuffer buffer,
        @NotNull TextureEngine engine,
        @NotNull ItemOptions options
    ) {
        if (!options.isEnchanted())
            return RenderEngine.staticFrame(buffer);

        GlintKit.GlintOptions glintOptions = GlintKit.GlintOptions.itemDefault(options.getFramesPerSecond());
        Optional<PixelBuffer> glintTexture = engine.tryResolveTexture(glintOptions.glintTextureId());
        if (glintTexture.isEmpty())
            return RenderEngine.staticFrame(buffer);

        ConcurrentList<PixelBuffer> glintFrames = GlintKit.apply(buffer, glintTexture.get(), glintOptions);
        int frameDelayMs = Math.max(1, Math.round(1000f / options.getFramesPerSecond()));
        return RenderEngine.output(glintFrames, frameDelayMs);
    }

    /** The "water" potion colour - used as the fallback when no potion effect is supplied. */
    private static final int DEFAULT_POTION_ARGB = 0xFF385DC6;

    /**
     * Composites a leather-armor item onto {@code buffer}: base hide layer untinted, overlay dye
     * layer tinted via {@link BlendMode#MULTIPLY}. Tint precedence is
     * {@link ItemOptions#getLeatherColor()} → {@link ItemOptions#getTintColor()} →
     * {@link Item.Overlay.Leather#defaultColor()} ({@code #A06540}). Returns the composited buffer
     * for callers that need to reuse the face texture in the 3D path; callers rendering to
     * {@code buffer} directly may ignore the return value.
     */
    static @NotNull PixelBuffer renderLeatherOverlay(
        @NotNull RasterEngine engine,
        @NotNull PixelBuffer buffer,
        @NotNull Item.Overlay.Leather overlay,
        @NotNull ItemOptions options
    ) {
        int size = options.getOutputSize();
        int tint = options.getLeatherColor()
            .or(options::getTintColor)
            .orElse(overlay.defaultColor());

        PixelBuffer base = engine.resolveTexture(overlay.baseTexture());
        PixelBuffer dye = engine.resolveTexture(overlay.overlayTexture());
        buffer.blitScaled(base, 0, 0, size, size);
        buffer.blitTinted(dye, 0, 0, tint, BlendMode.MULTIPLY);
        return buffer;
    }

    /**
     * Composites a potion-shaped item onto {@code buffer}: base bottle / shaft untinted, overlay
     * liquid / head tinted via {@link BlendMode#MULTIPLY}. Tint precedence is
     * {@link ItemOptions#getPotionColor()} → the first potion effect in
     * {@link dev.sbs.renderer.pipeline.pack.ItemContext#potionEffects()} resolved via
     * {@link RendererContext#potionEffectColor(String)} → {@link ItemOptions#getTintColor()} →
     * the water fallback ({@code #385DC6}).
     *
     * @param context the renderer context used to resolve potion effect colours
     * @param engine the raster engine for texture resolution
     * @param buffer the output pixel buffer
     * @param baseTexture the bottle / shaft base texture id
     * @param overlayTexture the liquid / head overlay texture id
     * @param options the render options carrying potion colour precedence sources
     * @return the composited buffer
     */
    static @NotNull PixelBuffer renderPotionOverlay(
        @NotNull RendererContext context,
        @NotNull RasterEngine engine,
        @NotNull PixelBuffer buffer,
        @NotNull String baseTexture,
        @NotNull String overlayTexture,
        @NotNull ItemOptions options
    ) {
        int size = options.getOutputSize();
        int tint = options.getPotionColor()
            .or(() -> options.getContext().potionEffects().stream()
                .findFirst()
                .flatMap(context::potionEffectColor))
            .or(options::getTintColor)
            .orElse(DEFAULT_POTION_ARGB);

        PixelBuffer base = engine.resolveTexture(baseTexture);
        PixelBuffer liquid = engine.resolveTexture(overlayTexture);
        buffer.blitScaled(base, 0, 0, size, size);
        buffer.blitTinted(liquid, 0, 0, tint, BlendMode.MULTIPLY);
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
                Item.Overlay value = overlay.get();
                if (value instanceof Item.Overlay.Leather leather) {
                    renderLeatherOverlay(engine, buffer, leather, options);
                } else if (value instanceof Item.Overlay.Potion potion) {
                    renderPotionOverlay(this.context, engine, buffer, potion.baseTexture(), potion.overlayTexture(), options);
                } else if (value instanceof Item.Overlay.TippedArrow tippedArrow) {
                    renderPotionOverlay(this.context, engine, buffer, tippedArrow.baseTexture(), tippedArrow.overlayTexture(), options);
                } else {
                    renderStandardLayers(engine, buffer, item, options);
                }
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

            return finaliseFrames(buffer, engine, options);
        }

    }

    /**
     * Held 3D item renderer. Dispatches on whether the item model supplies element boxes - block
     * items with non-empty element lists build real cubes via {@link GeometryKit#buildFromElements},
     * while flat sprite items fall back to a thin textured slab derived from {@code layer0}.
     * Both branches feed the same {@link ModelEngine#rasterize} overload with the item's
     * {@code thirdperson_righthand} display transform.
     * <p>
     * Overlay items fall back to the GUI path for now - the 3D overlay composition lands in a
     * later phase.
     */
    @RequiredArgsConstructor
    public static final class Held3D implements Renderer<ItemOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Item item = requireItem(this.context, options.getItemId());

            // Overlay items composite via the 2D helper for now; 3D overlay support arrives later.
            if (item.getOverlay().isPresent())
                return new Gui2D(this.context).render(options);

            ModelEngine engine = new ModelEngine(this.context);
            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
            int tint = options.getTintColor().orElse(ColorMath.WHITE);

            ConcurrentList<VisibleTriangle> triangles;
            if (!item.getModel().getElements().isEmpty()) {
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

            return finaliseFrames(buffer, engine, options);
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
         */
        private static @NotNull Matrix4f resolveDisplayTransform(@NotNull Item item, @NotNull String slot) {
            ModelTransform transform = item.getModel().getDisplay().get(slot);
            if (transform == null) return Matrix4f.IDENTITY;

            Matrix4f scale = Matrix4f.createScale(transform.getScaleX(), transform.getScaleY(), transform.getScaleZ());
            Matrix4f rotation = Matrix4f.createRotationY((float) Math.toRadians(transform.getRotationY()))
                .multiply(Matrix4f.createRotationX((float) Math.toRadians(transform.getRotationX())))
                .multiply(Matrix4f.createRotationZ((float) Math.toRadians(transform.getRotationZ())));
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
