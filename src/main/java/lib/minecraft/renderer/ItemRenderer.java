package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.asset.pack.item.ItemModelNode;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.GlintPolicy;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.LightingFrame;
import lib.minecraft.renderer.engine.compose.RasterPass;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.kit.BannerKit;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.engine.kit.ItemStackKit;
import lib.minecraft.renderer.engine.kit.ShieldKit;
import lib.minecraft.renderer.engine.kit.TrimKit;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.face.FaceTextures;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.ItemModelContext;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.SunAngle;
import lib.minecraft.renderer.option.slot.ItemSlot;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.option.spec.ItemDecoration;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.text.font.MinecraftFont;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

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
 * {@link ModelEngine} with the item model's {@code thirdperson_righthand} display transform applied.</li>
 * <li>{@link GuiIcon} renders the faithful inventory icon by index membership: an id with a flat
 * item entry through {@link Gui2D}, a block-backed id with no flat icon (plain blocks and
 * block-entities alike) through the isometric {@link BlockRenderer}. It adds no rendering of its own -
 * both branches reuse an existing renderer.</li>
 * </ul>
 * Shared item lookup, the glint-finalization tail, and the per-layer tint resolution live as
 * package-private static helpers on this class so the sub-renderers can reach them without
 * duplicating logic.
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
     * Looks up an item by id in the renderer context, throwing a descriptive
     * {@link RenderException} when the item is missing.
     */
    static @NotNull Item requireItem(@NotNull RendererContext context, @NotNull String itemId) {
        return context.findItem(itemId)
            .orElseThrow(() -> new RenderException("No item registered for id '%s'", itemId));
    }

    /**
     * Builds the per-render item resolver both render paths draw their frames through: maps an
     * animation tick onto the item to render at it, memoized so a schedule that revisits an evaluation
     * context resolves it once.
     * <p>
     * The item is per-frame because a dispatch tree can branch on world time - a clock resolves a
     * different face on each frame of an animated bake. The memo is per-render and discarded with it:
     * the CIT walk and pack state a resolution depends on are the render's own. Keying on the whole
     * evaluation context needs no assumption about which part of a resolution a tick can move, and
     * bounds the memo either way - one entry for a still, one per distinct sampled instant for a
     * time-driven strip.
     *
     * @param context the renderer context supplying pack / model / texture lookups
     * @param options the item render options
     * @param cit the render's single CIT walk result
     * @param animation the animation this render actually bakes, already derived
     * @return the item to render at an animation tick
     */
    static @NotNull IntFunction<Item> frameItems(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull CitResult cit,
        @NotNull AnimationOptions animation
    ) {
        ItemModelContext modelContext = options.getItemModel();
        // Only a game-time schedule moves the world clock between frames; a texture strip indexes a
        // flipbook, which leaves the tree's evaluation context - and so the resolved model - alone.
        boolean worldTime = animation.getSchedule() == AnimationOptions.Schedule.GAME_TIME;
        Map<ItemModelContext, Item> resolved = new ConcurrentHashMap<>();
        // Frames raster in parallel, so the memo is concurrent and its resolver stays pure.
        return tick -> resolved.computeIfAbsent(
            worldTime ? modelContext.atTick(tick) : modelContext,
            at -> resolveRenderItem(context, options, cit, at));
    }

    /**
     * Resolves the animation a render actually bakes. The caller's own timing is used as given, unless
     * it opts into derivation - in which case an item whose model tree branches on world time has its
     * timing derived from that tree: one frame per step the tree's own dispatch table resolves, spread
     * evenly across a day and played back as game time. It lets a caller ask for an item to be animated
     * without knowing which items are time-driven or how many faces they ship.
     * <p>
     * An item with nothing to animate keeps the caller's timing untouched, so requesting derivation on
     * a plain item costs it nothing and leaves it a still.
     *
     * @param context the renderer context supplying the item's dispatch tree
     * @param options the item render options
     * @return the animation timing to bake
     */
    static @NotNull AnimationOptions itemAnimation(@NotNull RendererContext context, @NotNull ItemOptions options) {
        AnimationOptions animation = options.getAnimation();
        if (!animation.isDeriveTimeline()) return animation;

        OptionalInt steps = context.findItemTree(options.getItemId())
            .map(tree -> tree.root().timeDispatchSteps())
            .orElseGet(OptionalInt::empty);
        if (steps.isEmpty()) return animation;

        // A day divided among the tree's steps. Floored at one tick so a table finer than the day is
        // long still advances rather than sampling the same instant every frame.
        int frames = steps.getAsInt();
        return animation.mutate()
            .frameCount(frames)
            .ticksPerFrame(Math.max(1, SunAngle.TICKS_PER_DAY / frames))
            .schedule(AnimationOptions.Schedule.GAME_TIME)
            .build();
    }

    /**
     * Resolves the effective item to render, applying an {@link ItemModelContext} and the CIT model
     * override on top of the pipeline-baked item.
     * <p>
     * The neutral {@link ItemModelContext#gui()} context with no CIT model override takes the fast
     * path - the pipeline-baked item verbatim, byte-identical to a pre-tree render. Otherwise the
     * item's dispatch tree is re-walked against the given context, then a
     * present {@code cit.model()} replaces the resolved model id (the OptiFine override-the-final-model
     * join); the resolved model id is materialised back into an {@link Item} by reusing
     * the already-built index entry for that model (its geometry + textures), carrying the walked
     * branch's tints. Falls back to the baked item when the tree is absent or the branch resolves to
     * nothing.
     */
    static @NotNull Item resolveRenderItem(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull CitResult cit,
        @NotNull ItemModelContext modelContext
    ) {
        Item baked = requireItem(context, options.getItemId());
        if (modelContext.isNeutral() && cit.model().isEmpty()) return baked;

        ItemModelNode.Resolution resolution = context.findItemTree(options.getItemId())
            .map(tree -> tree.resolve(modelContext))
            .orElse(null);
        // A special leaf maps onto an existing hardcoded / block-entity render path (parse-and-hold);
        // an unknown special kind is diagnosed and dropped. Either way the baked
        // item - already served by its own path - is returned.
        if (resolution != null && resolution.modelId().isEmpty() && resolution.special().isPresent()) {
            resolution.special().get().resolveOrDrop();
            return baked;
        }
        // CIT whole-model override wins over the tree-resolved model.
        boolean fromCit = cit.model().isPresent();
        String modelId = cit.model().map(ResourceId::id)
            .orElseGet(() -> resolution != null ? resolution.modelId().orElse(null) : null);
        if (modelId == null) return baked;

        // Resolve the model id to its ModelData by FULL id (collision-free - a basename collapse would
        // map minecraft:optifine/cit/diamond_sword onto the vanilla diamond_sword). A CIT override that
        // is not a resolvable item model (e.g. an optifine/cit/ path outside models/item/) misses and
        // is diagnosed rather than silently rendering the wrong model.
        Optional<ModelData> model = context.findItemModel(modelId);
        if (model.isEmpty()) {
            if (fromCit)
                System.err.printf("CIT model override '%s' for item '%s' is not a resolvable item model - rendering the base item%n",
                    modelId, options.getItemId());
            return baked;
        }

        ModelData resolved = model.get();
        List<LayerTint> tints = resolution != null ? resolution.tints() : baked.tints();
        HashMap<String, String> sprites = new HashMap<>();
        resolved.getTextures().forEach((slot, texture) -> sprites.put(slot, texture.sprite()));
        return new Item(baked.id(), resolved, Concurrent.adoptMap(sprites),
            baked.maxDurability(), tints, baked.alwaysGlinted());
    }

    /**
     * Builds the item enchantment-glint finish, deriving the glint flag
     * ({@code glintOverride}, else the item's always-glinted flag or {@code enchanted}) the GUI and
     * held-item paths share, then applying the CIT-derived {@link GlintPolicy}: a
     * {@link GlintPolicy.Suppressed} decision forces no glint ({@code useGlint=false}); a
     * {@link GlintPolicy.Replaced} decision swaps the glint texture id (a matched
     * {@code type=enchantment} rule) while leaving whether the item glints to its own flag; a
     * {@link GlintPolicy.Default} decision is vanilla behaviour.
     */
    static @NotNull GlintKit.Foil itemGlint(
        @NotNull Textures textures, @NotNull Item item, @NotNull ItemOptions options, @NotNull GlintPolicy glint
    ) {
        boolean glinted = options.getGlintOverride().orElse(item.alwaysGlinted() || options.isEnchanted());
        return switch (glint) {
            case GlintPolicy.Suppressed ignored ->
                GlintKit.Foil.item(textures::tryResolveTexture, false, options.isAnimateGlint(), options.getFramesPerSecond());
            case GlintPolicy.Replaced replaced ->
                GlintKit.Foil.itemReplaced(textures::tryResolveTexture, glinted, options.isAnimateGlint(), options.getFramesPerSecond(), replaced.texture().id());
            case GlintPolicy.Default ignored ->
                GlintKit.Foil.item(textures::tryResolveTexture, glinted, options.isAnimateGlint(), options.getFramesPerSecond());
        };
    }

    /**
     * Model-space minimum bound (block units) for the wide, square face of the flat-sprite item
     * slab. Reused for both the X and Y minimum corners so the sprite is a {@code 0.9}-block
     * square.
     */
    private static final float FLAT_ITEM_SLAB_MIN_X = -0.45f;

    /**
     * Model-space maximum bound (block units) for the wide, square face of the flat-sprite item
     * slab. Reused for both the X and Y maximum corners so the sprite is a {@code 0.9}-block
     * square.
     */
    private static final float FLAT_ITEM_SLAB_MAX_X = 0.45f;

    /**
     * Model-space minimum-Z bound (block units) - the thin depth of the flat-sprite slab.
     */
    private static final float FLAT_ITEM_SLAB_MIN_Z = -0.02f;

    /**
     * Model-space maximum-Z bound (block units) - the thin depth of the flat-sprite slab.
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
     * the vanilla reference. The shield's silhouette is the same size as vanilla's
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
     * {@link ItemDecoration#getBannerLayers()} layer blitted as a tinted grayscale mask.
     * {@link ItemDecoration#getBaseDye()} drives the field colour - white when absent. Shields
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
        DyeColor baseDye = options.getDecoration().getBaseDye().orElse(DyeColor.Vanilla.WHITE);
        BannerKit.Variant variant = itemId.equals(SHIELD_ITEM_ID)
            ? BannerKit.Variant.SHIELD_ITEM
            : BannerKit.Variant.BANNER_ITEM;

        PixelBuffer composite = BannerKit.composite2D(engine, baseDye.argb(), options.getDecoration().getBannerLayers(), variant);
        buffer.blitScaled(composite, 0, 0, options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize());
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
        DyeColor baseDye = options.getDecoration().getBaseDye().orElse(DyeColor.Vanilla.WHITE);
        boolean isShield = itemId.equals(SHIELD_ITEM_ID);
        BannerKit.Variant variant = isShield
            ? BannerKit.Variant.SHIELD_BLOCK_3D
            : BannerKit.Variant.BANNER_BLOCK_3D;

        PixelBuffer composite = BannerKit.composite2D(engine.textures(), baseDye.argb(), options.getDecoration().getBannerLayers(), variant);

        return BlockGeometryKit.buildBox(
            new Vector3f(FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_Z),
            new Vector3f(FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_Z),
            FaceTextures.uniform(composite),
            ColorMath.WHITE
        );
    }

    /**
     * Renders the plain {@code minecraft:shield} item as its vanilla 3D {@code ShieldModel} into
     * {@code buffer}. Mirrors the block-icon path ({@link BlockRenderer}'s
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
     * @param tick the animation tick the shield base texture is sampled at
     */
    static void renderShield3D(
        @NotNull RendererContext context,
        @NotNull PixelBuffer buffer,
        @NotNull ItemOptions options,
        int tick
    ) {
        ModelEngine engine = new ModelEngine(context, Camera.fromPose(SHIELD_GUI_ROTATION, SHIELD_PERSPECTIVE));
        PixelBuffer texture = engine.textures().resolveTextureAtTick(SHIELD_NOPATTERN_TEXTURE_ID, tick);
        ConcurrentList<VisibleTriangle> triangles = ShieldKit.buildShield3D(texture);
        triangles = ShieldKit.relightShield(triangles, LightingFrame.tracking(SHIELD_GUI_ROTATION));

        Matrix4f modelTransform = Matrix4f.IDENTITY.translate(
            SHIELD_ALIGN_OFFSET.x(), SHIELD_ALIGN_OFFSET.y(), SHIELD_ALIGN_OFFSET.z());
        engine.rasterize(triangles, buffer, modelTransform);
    }

    /**
     * Resolves the effective ARGB tint for {@code layerN} of an item. When the item carries a
     * {@link LayerTint} for that layer (from its definition's {@code model.tints[]}), the colour
     * resolves from the matching render-option override, else the JSON default:
     * <ul>
     * <li>{@link LayerTint.Dye} - {@link ItemDecoration#getLeatherColor()} → {@link ItemDecoration#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Potion} - {@link ItemDecoration#getPotionColor()} → the first
     * {@link ItemContext#potionEffects() potion effect}'s colour via
     * {@link RendererContext#findPotionEffectColor(String)} → {@link ItemDecoration#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Firework} - {@link ItemDecoration#getFireworkColor()} → {@link ItemDecoration#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Constant} - the fixed value.</li>
     * </ul>
     * When the item has no tint for the layer, falls back to the vanilla {@code item/generated}
     * convention: the caller's {@link ItemDecoration#getTintColor()} applies to the tintindex-0 slot
     * ({@link #tintIndexForLayer(Item, int)}), every other layer renders untinted. Returns
     * {@link ColorMath#WHITE} for an untinted layer.
     */
    static int resolveLayerTint(
        @NotNull RendererContext context,
        @NotNull Item item,
        int layerIndex,
        @NotNull ItemOptions options
    ) {
        List<LayerTint> tints = item.tints();
        if (layerIndex < tints.size()) {
            return switch (tints.get(layerIndex)) {
                case LayerTint.Dye dye ->
                    options.getDecoration().getLeatherColor().or(options.getDecoration()::getTintColor).orElse(dye.defaultColor());
                case LayerTint.Potion potion ->
                    options.getDecoration().getPotionColor()
                        .or(() -> options.getContext().potionEffects().stream().findFirst().flatMap(context::findPotionEffectColor))
                        .or(options.getDecoration()::getTintColor).orElse(potion.defaultColor());
                case LayerTint.Firework firework ->
                    options.getDecoration().getFireworkColor().or(options.getDecoration()::getTintColor).orElse(firework.defaultColor());
                case LayerTint.Constant constant -> constant.argb();
            };
        }
        int tint = options.getDecoration().getTintColor().orElse(ColorMath.WHITE);
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
        @NotNull ItemOptions options,
        @NotNull CitResult cit,
        int tick
    ) {
        String layer0Ref = cit.textureFor("layer0").map(ResourceId::id).orElse(item.textures().get("layer0"));
        if (layer0Ref == null || layer0Ref.isBlank())
            throw new RenderException("Item '%s' has no elements and no layer0 - nothing to render in Held3D path", item.id().id());
        PixelBuffer base = engine.textures().resolveTextureAtTick(layer0Ref, tick);
        PixelBuffer composite = PixelBuffer.create(base.width(), base.height());

        int layerIndex = 0;
        while (true) {
            String layerKey = LAYER_TEXTURE_PREFIX + layerIndex;
            String textureRef = cit.textureFor(layerKey).map(ResourceId::id).orElse(item.textures().get(layerKey));
            if (textureRef == null || textureRef.isBlank()) break;
            PixelBuffer layer = engine.textures().resolveTextureAtTick(textureRef, tick);
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
        ConcurrentList<ModelElement> elements = item.model().getElements();
        if (elements.isEmpty()) {
            // Vanilla item/generated convention: layer N has tintindex N.
            return layerIndex;
        }

        ConcurrentMap<String, ModelTexture> variables = item.model().getTextures();
        String layerKey = LAYER_TEXTURE_PREFIX + layerIndex;
        ModelTexture layerTexture = variables.get(layerKey);
        String layerRef = layerTexture == null ? null : layerTexture.sprite();
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
     * present, otherwise the caller's {@link ItemDecoration#getTintColor()} on the tintindex-0 slot.
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
        @NotNull ItemOptions options,
        @NotNull CitResult cit,
        int tick
    ) {
        int size = options.getOutput().getCanvasSize();
        // The CIT walk ran once per render (shared with the glint decision); each layer resolves against
        // the result (layer0 -> texture, layerN -> texture.<name>), falling back to the model-bound id.
        // The empty-context vanilla path yields CitResult.NONE, so every layer passes through unchanged.
        int layerIndex = 0;
        while (true) {
            String layerKey = LAYER_TEXTURE_PREFIX + layerIndex;
            String textureRef = cit.textureFor(layerKey).map(ResourceId::id).orElse(item.textures().get(layerKey));
            if (textureRef == null || textureRef.isBlank()) break;

            if (TrimKit.isTrimTexture(textureRef)) {
                TrimKit.resolveFromTextureRef(engine, textureRef)
                    .ifPresent(trim -> buffer.blitScaled(trim, 0, 0, size, size));
            } else {
                PixelBuffer layer = engine.resolveTextureAtTick(textureRef, tick);
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

        /**
         * The renderer context supplying pack / model / texture lookups.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            RasterEngine engine = new RasterEngine(this.context);

            // One CIT walk per render, shared by the layer stack (texture overrides) and the glint tail
            // (its GlintPolicy). The empty-context vanilla path yields CitResult.NONE, so both stay
            // vanilla-identical. The CIT walk reads no clock, so it is hoisted; the item is not, because
            // a dispatch tree can branch on world time. Resolve it AFTER the CIT walk so a CIT model
            // override can replace the tree-resolved model; the neutral context + no override yields the
            // baked item.
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            AnimationOptions anim = itemAnimation(this.context, options);
            IntFunction<Item> itemAt = frameItems(this.context, options, cit, anim);

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
            return Timeline.schedule(anim).bake(
                RasterPass.of(size, size, 1, options.getOutput().isAntiAlias(),
                        (target, tick) -> Layers.foldInto(
                            buildGuiLayers(new LayerContext(this.context, engine.textures(), itemAt.apply(tick), options, cit), tick),
                            options.getLayerDecorator(), target))
                    .finishing(itemGlint(engine.textures(), itemAt.apply(0), options, cit.glint())));
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

            if (options.getItemId().equals(SHIELD_ITEM_ID))
                stack.append(ItemSlot.BASE, frame -> renderShield3D(ctx.context(), frame, options, tick));
            else if (isBannerOrShield(options.getItemId()))
                stack.append(ItemSlot.BASE, frame ->
                    renderBannerOrShield(ctx.textures(), frame, options.getItemId(), options));
            else
                stack.append(ItemSlot.BASE, frame ->
                    renderStandardLayers(ctx.context(), ctx.textures(), frame, ctx.item(), options, ctx.cit(), tick));

            if (options.getDecoration().getTrimSlot().isPresent() && options.getDecoration().getTrimColor().isPresent())
                stack.append(ItemSlot.TRIM, frame ->
                    TrimKit.resolve(ctx.textures(), options.getDecoration().getTrimSlot().get().getKey(), options.getDecoration().getTrimColor().get().getKey())
                        .ifPresent(trim -> frame.blitScaled(trim, 0, 0, options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize())));

            if (options.isShowDamageBar())
                stack.append(ItemSlot.DAMAGE_BAR, frame ->
                    ItemStackKit.drawDamageBar(frame, options.getContext().damage(), ctx.item().maxDurability()));

            if (options.getContext().stackCount() > 1)
                stack.append(ItemSlot.STACK_COUNT, frame ->
                    ItemStackKit.drawStackCount(frame, options.getContext().stackCount(), MinecraftFont.REGULAR));

            return stack;
        }

        /**
         * Per-frame state passed to every {@link ImageLayer} in the 2D item composite stack. Built by
         * {@link #render} for each frame it bakes - every field but the resolved item is the render's
         * own and identical across frames - and read by {@link #buildGuiLayers}.
         *
         * @param context renderer context for texture and override resolution
         * @param textures texture-resolution service for layer texture lookups
         * @param item resolved item definition being rendered
         * @param options caller-supplied item render options
         * @param cit the render's single CIT walk result, shared by every base-layer pass
         */
        private record LayerContext(
            @NotNull RendererContext context,
            @NotNull Textures textures,
            @NotNull Item item,
            @NotNull ItemOptions options,
            @NotNull CitResult cit
        ) { }

    }

    /**
     * Held 3D item renderer. Dispatches on whether the item model supplies element boxes - block
     * items with non-empty element lists build real cubes via {@link BlockGeometryKit#buildFromElements},
     * while flat sprite items fall back to a thin textured slab derived from {@code layer0}.
     * Both branches feed the same {@link ModelEngine#rasterize} overload with the item's
     * {@code thirdperson_righthand} display transform.
     * <p>
     * Banner and shield items route through {@link ItemRenderer#buildBannerOrShield3D} so the
     * HELD_3D view shows the composited pattern stack: both fall back to a thin textured slab whose
     * six faces carry the freshly composited banner / shield texture, mirroring the flat-sprite
     * fallback used for other item kinds.
     * <p>
     * Flat-sprite items composite their (tinted) layer stack into a native-size
     * {@link PixelBuffer} via {@link ItemRenderer#composeTintedLayers} and feed the result into the
     * thin-Z-slab path, so the held view reflects the same per-layer tint as the GUI icon.
     */
    public static final class Held3D implements Renderer<ItemOptions> {

        /**
         * The renderer context supplying pack / model / texture lookups.
         */
        private final @NotNull RendererContext context;

        /**
         * The pack-aware texture-resolution service bound once to {@link #context}, shared by the
         * flat-slab layer composite and the glint tail.
         */
        private final @NotNull Textures textures;

        /**
         * Constructs the held-3D sub-renderer bound to the given context.
         *
         * @param context the renderer context supplying pack / model / texture lookups
         */
        public Held3D(@NotNull RendererContext context) {
            this.context = context;
            this.textures = new Textures(context);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull ItemOptions options) {
            // Identity-pose camera carrying only the projection's lens: the held-item pose lives
            // entirely in the model's display transform (applied as the modelTransform below), so the
            // camera pose stays identity and only the rotation-independent lens comes from resolve().
            Camera camera = Camera.identity(options.getOutput().getProjection().resolve(EulerRotation.NONE, options.getOutput().getFacing()).camera().lens());
            int tint = options.getDecoration().getTintColor().orElse(ColorMath.WHITE);

            // One CIT walk per render, shared by the flat-slab layer composite and the glint tail; it
            // reads no clock, so it is hoisted. The geometry build moves INSIDE the raster callback
            // (fluid pattern) and the ModelEngine is rebuilt per frame for thread-safe parallel strip
            // baking. Vanilla ships no item sidecars, so a default render (frameCount = 1) resolves at
            // tick 0 - byte-identical.
            CitResult cit = this.context.resolveItemTextureOverride(options.getContext());
            AnimationOptions anim = itemAnimation(this.context, options);
            IntFunction<Item> itemAt = frameItems(this.context, options, cit, anim);

            // Build the schedule UNCONDITIONALLY (the FluidRenderer pattern): frameCount=1 yields a single static
            // frame sampled at anim.getStartTick() (staticFrame would hardcode tick 0). Default
            // (startTick=0, frameCount=1) is byte-identical.
            int size = options.getOutput().getCanvasSize();
            int ssaa = Math.max(1, options.getOutput().getSupersample());
            return Timeline.schedule(anim).bake(
                RasterPass.of(size, size, ssaa, options.getOutput().isAntiAlias(), (target, tick) -> {
                    // The display pose is read off the frame's own model: a tree that swaps models
                    // between frames can swap their authored poses with them.
                    Item item = itemAt.apply(tick);
                    ModelEngine engine = new ModelEngine(this.context, camera);
                    engine.rasterize(buildTrianglesAtTick(engine, item, options, cit, tint, tick), target,
                        resolveDisplayTransform(item, DISPLAY_SLOT_HELD_3D));
                }).finishing(itemGlint(this.textures, itemAt.apply(0), options, cit.glint())));
        }

        /**
         * Builds the held-item triangles at animation {@code tick}: banner / shield via the pattern
         * composite, an element-model item's cubes with its face textures sampled at {@code tick}
         * (held block items and any custom item whose model JSON supplies {@code elements}), or a
         * flat-sprite item's thin Z-slab carrying its (tinted) {@code layer0..N} composite resolved at
         * {@code tick}. {@link #composeTintedLayers} folds in each layer's {@code LayerTint} (leather
         * dye, potion colour, firework colour) and the caller's {@code tintColor} so the held view
         * carries the same colour as the GUI icon (degenerate no-elements-and-no-layer0 cases throw
         * inside it). Called once per frame from the raster callback so an animated pack
         * texture rebuilds per frame.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildTrianglesAtTick(
            @NotNull ModelEngine engine, @NotNull Item item, @NotNull ItemOptions options, @NotNull CitResult cit, int tint, int tick
        ) {
            if (isBannerOrShield(options.getItemId()))
                return buildBannerOrShield3D(engine, options.getItemId(), options);
            if (!item.model().getElements().isEmpty()) {
                Map<String, PixelBuffer> faceTextures = loadFaceTextures(engine, item, tick);
                var forceRefs = Textures.resolveForceTranslucentRefs(
                    item.model().getElements(), item.model().getTextures());
                return BlockGeometryKit.buildFromElements(item.model().getElements(), faceTextures, tint, tint, forceRefs);
            }
            PixelBuffer texture = composeTintedLayers(this.context, engine, item, options, cit, tick);
            return BlockGeometryKit.buildBox(
                new Vector3f(FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_X, FLAT_ITEM_SLAB_MIN_Z),
                new Vector3f(FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_X, FLAT_ITEM_SLAB_MAX_Z),
                FaceTextures.uniform(texture),
                ColorMath.WHITE
            );
        }

        /**
         * Walks the item model's element face texture references, dereferences {@code #var}
         * chains against the model's texture bindings, and loads each unique resolved id into a
         * {@link PixelBuffer} sampled at animation {@code tick}. The returned map is keyed by the
         * original face reference string (including any leading {@code #}), which matches what
         * {@link BlockGeometryKit#buildFromElements} expects.
         */
        private static @NotNull Map<String, PixelBuffer> loadFaceTextures(
            @NotNull ModelEngine engine,
            @NotNull Item item,
            int tick
        ) {
            return Textures.loadElementFaceTextures(
                item.model().getElements(), item.model().getTextures(),
                id -> Optional.of(engine.textures().resolveTextureAtTick(id, tick)));
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
                    transform.getTranslationX() / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK,
                    transform.getTranslationY() / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK,
                    transform.getTranslationZ() / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK
                );
        }

    }

    /**
     * Faithful inventory-icon renderer ({@link ItemOptions.Type#GUI_ICON}): the representation a GUI
     * slot shows, routed by index membership rather than rendered anew. An id with a flat item entry
     * renders through the shared {@link Gui2D} path unchanged; an id absent from the item index but
     * backing a block (plain blocks and block-entities alike) renders through the isometric
     * {@link BlockRenderer}, which already distinguishes a plain block model from a
     * {@code BlockEntityRenderer} pose; an id backing neither raises {@link RenderException}.
     * <p>
     * The mode adds no rendering of its own - both branches reuse an existing renderer - so a
     * flat-sprite icon is byte-identical to {@link ItemOptions.Type#GUI_2D} and a block-backed icon to
     * the isometric block render at the same output frame.
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
            throw new RenderException("No item or block registered for id '%s'", options.getItemId());
        }

        /**
         * Adapts item options into the {@link BlockOptions} the block branch renders: the output size
         * and anti-aliasing knobs carried onto the neutral iso output frame (default projection /
         * facing / rotation) so the block honours its authored {@code display.gui} pose - the vanilla
         * inventory look - rather than the item icon's {@code VANILLA_GUI_ITEM} projection. Renders on
         * a transparent background so the caller composites its own background once.
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
                .background(Background.TRANSPARENT)
                .build();
        }

    }

}
