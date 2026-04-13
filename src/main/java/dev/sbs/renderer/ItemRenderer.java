package dev.sbs.renderer;

import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.GeometryKit;
import dev.sbs.renderer.draw.GlintKit;
import dev.sbs.renderer.draw.ItemBarKit;
import dev.sbs.renderer.draw.armor.TrimKit;
import dev.sbs.renderer.engine.ModelEngine;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.model.Item;
import dev.sbs.renderer.model.asset.ModelElement;
import dev.sbs.renderer.model.asset.ModelFace;
import dev.sbs.renderer.model.asset.ModelTransform;
import dev.sbs.renderer.options.ItemOptions;
import dev.sbs.renderer.tensor.Matrix4f;
import dev.sbs.renderer.tensor.Vector3f;
import dev.sbs.renderer.text.MinecraftFont;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.BlendMode;
import dev.simplified.image.ColorMath;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
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
 * glint animation.</li>
 * <li>{@link Held3D} dispatches on whether the item's model provides element boxes - block items
 * build real cubes via {@link GeometryKit#buildFromElements}, flat sprite items fall back to a
 * thin textured slab. Both paths route through {@link ModelEngine} with the item model's
 * {@code thirdperson_righthand} display transform applied.</li>
 * </ul>
 * Shared item lookup plus the glint-finalization tail live as package-private static helpers on
 * this class so both sub-renderers can reach them without duplicating logic.
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

    /**
     * Flat 2D GUI icon renderer. Composes layered sprites ({@code layer0}, {@code layer1}, ...)
     * with optional {@link ItemOptions#getTintColor() tint}, damage bar, stack count, and glint
     * animation.
     */
    @RequiredArgsConstructor
    public static final class Gui2D implements Renderer<ItemOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            Item item = requireItem(this.context, options.getItemId());
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer buffer = engine.createBuffer(options.getOutputSize(), options.getOutputSize());

            // Layered flat-item sprite: layer0, layer1, ... stacked, each tinted per options.
            // Trim overlay layers (matching trims/items/{slot}_trim_{material}) are generated
            // via paletted permutation since vanilla doesn't ship the material-specific PNGs.
            int layerIndex = 0;
            int size = options.getOutputSize();
            int tint = options.getTintColor().orElse(ColorMath.WHITE);
            while (true) {
                String layerKey = "layer" + layerIndex;
                String textureRef = item.getTextures().get(layerKey);
                if (textureRef == null || textureRef.isBlank()) break;

                if (TrimKit.isTrimTexture(textureRef)) {
                    TrimKit.resolveFromTextureRef(engine, textureRef)
                        .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, size, size));
                } else {
                    PixelBuffer layer = engine.resolveTexture(textureRef);
                    if (layerIndex == 0 && tint != ColorMath.WHITE)
                        buffer.blitTinted(layer, 0, 0, tint, BlendMode.MULTIPLY);
                    else
                        buffer.blitScaled(layer, 0, 0, size, size);
                }
                layerIndex++;
            }

            if (options.getTrimSlot().isPresent() && options.getTrimColor().isPresent())
                TrimKit.resolve(engine, options.getTrimSlot().get().getKey(), options.getTrimColor().get().getKey())
                    .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, options.getOutputSize(), options.getOutputSize()));

            if (options.isShowDamageBar())
                ItemBarKit.drawDamageBar(buffer, options.getContext().damage(), item.getMaxDurability());

            if (options.getContext().stackCount() > 1) {
                Canvas canvas = Canvas.wrap(buffer);
                ItemBarKit.drawStackCount(canvas, options.getContext().stackCount(), MinecraftFont.REGULAR);
                canvas.disposeGraphics();
            }

            return finaliseFrames(buffer, engine, options);
        }

    }

    /**
     * Held 3D item renderer. Dispatches on whether the item model supplies element boxes - block
     * items with non-empty element lists build real cubes via {@link GeometryKit#buildFromElements},
     * while flat sprite items fall back to a thin textured slab derived from {@code layer0}.
     * Both branches feed the same {@link ModelEngine#rasterize} overload with the item's
     * {@code thirdperson_righthand} display transform.
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
