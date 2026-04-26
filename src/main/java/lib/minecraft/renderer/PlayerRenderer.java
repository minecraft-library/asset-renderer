package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.binding.ArmorPiece;
import lib.minecraft.renderer.asset.binding.ArmorTrim;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.SkinFace;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.ArmorKit;
import lib.minecraft.renderer.kit.BlockModelGeometryKit;
import lib.minecraft.renderer.kit.GlintKit;
import lib.minecraft.renderer.options.PlayerOptions;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Renders player models in three body scopes ({@link PlayerOptions.Type#SKULL SKULL},
 * {@link PlayerOptions.Type#BUST BUST}, {@link PlayerOptions.Type#FULL FULL}) and two
 * dimensions ({@link PlayerOptions.Dimension#TWO_D TWO_D},
 * {@link PlayerOptions.Dimension#THREE_D THREE_D}), with optional armor, trim overlays, and
 * enchantment glint.
 * <p>
 * The three sub-renderers ({@link Skull}, {@link Bust}, {@link Full}) each handle both 2D and
 * 3D internally:
 * <ul>
 * <li><b>2D</b> composites the front-facing (south) crop of each visible body part, layering
 * base skin, overlay, armor, and trim as scaled sprites on a flat canvas.</li>
 * <li><b>3D</b> builds cubes for each visible body part and rasterizes through
 * {@link IsometricEngine}, with armor as slightly inflated overlapping geometry.</li>
 * </ul>
 * Skin resolution is shared via the outer class, with URL-fetched skins cached for the
 * renderer's lifetime.
 */
public final class PlayerRenderer implements Renderer<PlayerOptions> {

    private final @NotNull RendererContext context;
    private final @NotNull HttpFetcher fetcher = new HttpFetcher();
    private final @NotNull ImageFactory imageFactory = new ImageFactory();
    private final @NotNull ConcurrentMap<String, PixelBuffer> skinCache = Concurrent.newMap();

    private final @NotNull Skull skull;
    private final @NotNull Bust bust;
    private final @NotNull Full full;

    public PlayerRenderer(@NotNull RendererContext context) {
        this.context = context;
        this.skull = new Skull(this);
        this.bust = new Bust(this);
        this.full = new Full(this);
    }

    @Override
    public @NotNull ImageData render(@NotNull PlayerOptions options) {
        return switch (options.getType()) {
            case SKULL -> this.skull.render(options);
            case BUST -> this.bust.render(options);
            case FULL -> this.full.render(options);
        };
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers.
    // ---------------------------------------------------------------------------------------

    static @NotNull PixelBuffer resolveSkin(@NotNull PlayerRenderer parent, @NotNull PlayerOptions options) {
        if (options.getSkinBytes().isPresent())
            return PixelBuffer.wrap(parent.imageFactory.fromByteArray(options.getSkinBytes().get()).toBufferedImage());

        if (options.getSkinUrl().isPresent()) {
            String url = options.getSkinUrl().get();
            return parent.skinCache.computeIfAbsent(url, u -> {
                byte[] bytes = parent.fetcher.get(u);
                return PixelBuffer.wrap(parent.imageFactory.fromByteArray(bytes).toBufferedImage());
            });
        }

        if (options.getSkinTextureId().isPresent()) {
            RasterEngine engine = new RasterEngine(parent.context);
            return engine.resolveTexture(options.getSkinTextureId().get());
        }

        return parent.context.resolveTexture("minecraft:entity/steve")
            .orElseThrow(() -> new RendererException("No default Steve skin registered and no skin supplied"));
    }

    /** Whether any of the four armor slots carries an enchanted piece. */
    private static boolean hasEnchantedArmor(@NotNull PlayerOptions options) {
        return ArmorKit.hasEnchantedArmor(
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots()
        );
    }

    /** Whether the skin is wide enough to have overlay layers. */
    private static boolean hasOverlay(@NotNull PixelBuffer skin) {
        return skin.width() >= 64 && skin.height() >= 64;
    }

    /** Whether the skin is wide enough to have hat overlay (smaller threshold than full overlay). */
    private static boolean hasHatOverlay(@NotNull PixelBuffer skin) {
        return skin.width() >= 48 && skin.height() >= 16;
    }

    /**
     * Resolves the cape texture using the same priority chain as skins. Returns empty when
     * {@code renderCape} is false or no texture source is available.
     */
    static @NotNull Optional<PixelBuffer> resolveCape(@NotNull PlayerRenderer parent, @NotNull PlayerOptions options) {
        if (!options.isRenderCape()) return Optional.empty();

        if (options.getCapeBytes().isPresent())
            return Optional.of(PixelBuffer.wrap(parent.imageFactory.fromByteArray(options.getCapeBytes().get()).toBufferedImage()));

        if (options.getCapeUrl().isPresent()) {
            String url = options.getCapeUrl().get();
            return Optional.of(parent.skinCache.computeIfAbsent("cape:" + url, ignored -> {
                byte[] bytes = parent.fetcher.get(url);
                return PixelBuffer.wrap(parent.imageFactory.fromByteArray(bytes).toBufferedImage());
            }));
        }

        if (options.getCapeTextureId().isPresent()) {
            RasterEngine engine = new RasterEngine(parent.context);
            return engine.tryResolveTexture(options.getCapeTextureId().get());
        }

        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------
    // Cape geometry - 10x16x1 pixel box on a 64x32 texture, standard cube UV unwrap at (0,0).
    // ---------------------------------------------------------------------------------------

    /**
     * Crops the 6 face textures for the cape cube from a 64x32 cape texture. The cape model
     * is a 10x16x1 box with UV origin (0,0), following the standard Minecraft cube unwrap:
     * <pre>
     * y=0:  [1px pad][10px TOP][1px pad][10px BOTTOM]
     * y=1:  [1px WEST][10px SOUTH][1px EAST][10px NORTH]  (16 rows)
     * </pre>
     */
    private static @NotNull PixelBuffer @NotNull [] cropCapeFaces(@NotNull PixelBuffer cape) {
        PixelBuffer[] faces = new PixelBuffer[6];
        faces[BlockFace.DOWN.ordinal()] = cropRect(cape, 11, 0, 10, 1);
        faces[BlockFace.UP.ordinal()] = cropRect(cape, 1, 0, 10, 1);
        faces[BlockFace.NORTH.ordinal()] = cropRect(cape, 12, 1, 10, 16);
        faces[BlockFace.SOUTH.ordinal()] = cropRect(cape, 1, 1, 10, 16);
        faces[BlockFace.WEST.ordinal()] = cropRect(cape, 0, 1, 1, 16);
        faces[BlockFace.EAST.ordinal()] = cropRect(cape, 11, 1, 1, 16);
        return faces;
    }

    private static @NotNull PixelBuffer cropRect(@NotNull PixelBuffer source, int x, int y, int w, int h) {
        int[] pixels = new int[w * h];
        for (int dy = 0; dy < h; dy++)
            for (int dx = 0; dx < w; dx++) {
                int sx = x + dx, sy = y + dy;
                if (sx < source.width() && sy < source.height())
                    pixels[dy * w + dx] = source.getPixel(sx, sy);
            }
        return PixelBuffer.of(pixels, w, h);
    }

    /**
     * Builds cape triangles as a thin box positioned behind and below the torso top edge.
     * The cape width and height are proportional to the torso dimensions.
     */
    private static void addCape(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer capeTexture,
        @NotNull Vector3f torsoMin,
        @NotNull Vector3f torsoMax
    ) {
        float torsoW = torsoMax.x() - torsoMin.x();
        float torsoH = torsoMax.y() - torsoMin.y();
        float capeW = torsoW * 10f / 8f;
        float capeH = torsoH * 16f / 12f;
        float capeD = torsoW * 1f / 8f;

        float cx = (torsoMin.x() + torsoMax.x()) / 2f;
        float capeTop = torsoMax.y();
        float capeBack = torsoMax.z();

        Vector3f capeMin = new Vector3f(cx - capeW / 2f, capeTop - capeH, capeBack);
        Vector3f capeMax = new Vector3f(cx + capeW / 2f, capeTop, capeBack + capeD);

        PixelBuffer[] faces = cropCapeFaces(capeTexture);
        triangles.addAll(BlockModelGeometryKit.box(capeMin, capeMax, faces, ColorMath.WHITE));
    }

    // ---------------------------------------------------------------------------------------
    // 2D helpers - composite front-facing body parts + armor onto a canvas.
    // ---------------------------------------------------------------------------------------

    /**
     * The body parts and their 2D layout data for each {@link PlayerOptions.Type}.
     */
    private record BodyPart2D(@NotNull SkinFace part, int x, int y, int w, int h) {}

    /**
     * Returns the 2D layout for the given type at the given pixel scale and horizontal offset.
     * Coordinates are in pixels relative to the canvas origin.
     */
    private static @NotNull BodyPart2D @NotNull [] layout2D(@NotNull PlayerOptions.Type type, int scale, int offsetX) {
        return switch (type) {
            case SKULL -> new BodyPart2D[]{
                new BodyPart2D(SkinFace.HEAD, offsetX, 0, 8 * scale, 8 * scale)
            };
            case BUST -> new BodyPart2D[]{
                new BodyPart2D(SkinFace.HEAD, offsetX + 4 * scale, 0, 8 * scale, 8 * scale),
                new BodyPart2D(SkinFace.TORSO, offsetX + 4 * scale, 8 * scale, 8 * scale, 12 * scale),
                new BodyPart2D(SkinFace.RIGHT_ARM, offsetX, 8 * scale, 4 * scale, 12 * scale),
                new BodyPart2D(SkinFace.LEFT_ARM, offsetX + 12 * scale, 8 * scale, 4 * scale, 12 * scale)
            };
            case FULL -> new BodyPart2D[]{
                new BodyPart2D(SkinFace.HEAD, offsetX + 4 * scale, 0, 8 * scale, 8 * scale),
                new BodyPart2D(SkinFace.TORSO, offsetX + 4 * scale, 8 * scale, 8 * scale, 12 * scale),
                new BodyPart2D(SkinFace.RIGHT_ARM, offsetX, 8 * scale, 4 * scale, 12 * scale),
                new BodyPart2D(SkinFace.LEFT_ARM, offsetX + 12 * scale, 8 * scale, 4 * scale, 12 * scale),
                new BodyPart2D(SkinFace.RIGHT_LEG, offsetX + 4 * scale, 20 * scale, 4 * scale, 12 * scale),
                new BodyPart2D(SkinFace.LEFT_LEG, offsetX + 8 * scale, 20 * scale, 4 * scale, 12 * scale)
            };
        };
    }

    /**
     * Computes the pixel scale and horizontal offset for 2D rendering so the body fills the
     * output canvas height with horizontal centering.
     */
    private static int @NotNull [] scaleAndOffset2D(@NotNull PlayerOptions.Type type, int outputSize) {
        int scale = outputSize / type.getBodyHeight();
        int offsetX = (outputSize - type.getBodyWidth() * scale) / 2;
        return new int[]{ scale, offsetX };
    }

    /**
     * Renders a 2D front-facing composite for any body type.
     */
    private static @NotNull ImageData render2D(
        @NotNull PlayerRenderer parent,
        @NotNull PlayerOptions options
    ) {
        PixelBuffer skin = resolveSkin(parent, options);
        RasterEngine engine = new RasterEngine(parent.context);
        PixelBuffer buffer = engine.createBuffer(options.getOutputSize(), options.getOutputSize());

        int[] so = scaleAndOffset2D(options.getType(), options.getOutputSize());
        int scale = so[0];
        int offsetX = so[1];
        BodyPart2D[] parts = layout2D(options.getType(), scale, offsetX);

        boolean overlay = options.isRenderOverlay();

        for (BodyPart2D bp : parts) {
            // Base skin
            PixelBuffer face = bp.part.crop(skin, BlockFace.SOUTH, false);
            buffer.blitScaled(face, bp.x, bp.y, bp.w, bp.h);

            // Overlay
            if (overlay && hasOverlay(skin)) {
                PixelBuffer overlayFace = bp.part.crop(skin, BlockFace.SOUTH, true);
                buffer.blitScaled(overlayFace, bp.x, bp.y, bp.w, bp.h);
            } else if (overlay && bp.part == SkinFace.HEAD && hasHatOverlay(skin)) {
                PixelBuffer hat = SkinFace.HEAD.crop(skin, BlockFace.SOUTH, true);
                buffer.blitScaled(hat, bp.x, bp.y, bp.w, bp.h);
            }

            // Armor + trim for this body part (each slot that covers this part gets composited)
            compositeArmor2D(buffer, bp.part, bp.x, bp.y, bp.w, bp.h, options, engine);
        }

        if (options.isAntiAlias())
            buffer.applyFxaa();

        return engine.finaliseWithGlint(buffer, hasEnchantedArmor(options), GlintKit.GlintOptions.armorDefault(30));
    }

    /**
     * Composites all armor slots that cover the given body part in the correct layer order.
     * Leggings are drawn before chestplate/boots so the outer layer wins on overlapping parts
     * (torso, legs).
     */
    private static void compositeArmor2D(
        @NotNull PixelBuffer target,
        @NotNull SkinFace part,
        int x, int y, int w, int h,
        @NotNull PlayerOptions options,
        @NotNull RasterEngine engine
    ) {
        for (ArmorTrim.Slot slot : ArmorTrim.Slot.values()) {
            Optional<ArmorPiece> piece = switch (slot) {
                case HELMET -> options.getHelmet();
                case CHESTPLATE -> options.getChestplate();
                case LEGGINGS -> options.getLeggings();
                case BOOTS -> options.getBoots();
            };
            if (piece.isEmpty()) continue;

            boolean partInSlot = false;
            for (SkinFace slotPart : ArmorKit.partsForSlot(slot))
                if (slotPart == part) { partInSlot = true; break; }
            if (!partInSlot) continue;

            ArmorKit.compositeSlot2D(target, part, slot, piece.get(), x, y, w, h, engine);
        }
    }

    // ---------------------------------------------------------------------------------------
    // 3D helpers - body part positions for each type.
    // ---------------------------------------------------------------------------------------

    // Skull: unit head cube.
    private static final Vector3f SKULL_HEAD_MIN = new Vector3f(-0.5f, -0.5f, -0.5f);
    private static final Vector3f SKULL_HEAD_MAX = new Vector3f(0.5f, 0.5f, 0.5f);

    // Bust: head + torso, matching the original PlayerBust3D proportions.
    private static final Vector3f BUST_HEAD_MIN = new Vector3f(-0.25f, 0.1f, -0.25f);
    private static final Vector3f BUST_HEAD_MAX = new Vector3f(0.25f, 0.6f, 0.25f);
    private static final Vector3f BUST_TORSO_MIN = new Vector3f(-0.2f, -0.4f, -0.1f);
    private static final Vector3f BUST_TORSO_MAX = new Vector3f(0.2f, 0.1f, 0.1f);
    private static final Vector3f BUST_R_ARM_MIN = new Vector3f(-0.33f, -0.4f, -0.1f);
    private static final Vector3f BUST_R_ARM_MAX = new Vector3f(-0.2f, 0.1f, 0.1f);
    private static final Vector3f BUST_L_ARM_MIN = new Vector3f(0.2f, -0.4f, -0.1f);
    private static final Vector3f BUST_L_ARM_MAX = new Vector3f(0.33f, 0.1f, 0.1f);

    // Full body: 1 MC pixel = 1/32 model unit, centred vertically.
    private static final Vector3f FULL_HEAD_MIN = new Vector3f(-0.12f, 0.24f, -0.12f);
    private static final Vector3f FULL_HEAD_MAX = new Vector3f(0.12f, 0.48f, 0.12f);
    private static final Vector3f FULL_TORSO_MIN = new Vector3f(-0.12f, -0.12f, -0.06f);
    private static final Vector3f FULL_TORSO_MAX = new Vector3f(0.12f, 0.24f, 0.06f);
    private static final Vector3f FULL_R_ARM_MIN = new Vector3f(-0.24f, -0.12f, -0.06f);
    private static final Vector3f FULL_R_ARM_MAX = new Vector3f(-0.12f, 0.24f, 0.06f);
    private static final Vector3f FULL_L_ARM_MIN = new Vector3f(0.12f, -0.12f, -0.06f);
    private static final Vector3f FULL_L_ARM_MAX = new Vector3f(0.24f, 0.24f, 0.06f);
    private static final Vector3f FULL_R_LEG_MIN = new Vector3f(-0.12f, -0.48f, -0.06f);
    private static final Vector3f FULL_R_LEG_MAX = new Vector3f(0.0f, -0.12f, 0.06f);
    private static final Vector3f FULL_L_LEG_MIN = new Vector3f(0.0f, -0.48f, -0.06f);
    private static final Vector3f FULL_L_LEG_MAX = new Vector3f(0.12f, -0.12f, 0.06f);

    private static final float OVERLAY_INFLATE = 0.01f;

    // ---------------------------------------------------------------------------------------
    // Sub-renderers.
    // ---------------------------------------------------------------------------------------

    /**
     * Skull renderer - head only, in 2D or 3D.
     */
    @RequiredArgsConstructor
    public static final class Skull implements Renderer<PlayerOptions> {

        private final @NotNull PlayerRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull PlayerOptions options) {
            if (options.getDimension() == PlayerOptions.Dimension.TWO_D)
                return render2D(this.parent, options);
            return render3D(options);
        }

        private @NotNull ImageData render3D(@NotNull PlayerOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            IsometricEngine engine = IsometricEngine.standard(this.parent.context);
            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());

            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            triangles.addAll(BlockModelGeometryKit.unitCube(SkinFace.HEAD.cropAll(skin, false), ColorMath.WHITE));
            if (options.isRenderOverlay() && hasHatOverlay(skin))
                triangles.addAll(BlockModelGeometryKit.box(
                    new Vector3f(-0.52f, -0.52f, -0.52f),
                    new Vector3f(0.52f, 0.52f, 0.52f),
                    SkinFace.HEAD.cropAll(skin, true), ColorMath.WHITE));

            Map<SkinFace, Vector3f[]> bp = new EnumMap<>(SkinFace.class);
            bp.put(SkinFace.HEAD, new Vector3f[]{ SKULL_HEAD_MIN, SKULL_HEAD_MAX });
            triangles.addAll(ArmorKit.buildHumanoidArmor3D(bp,
                options.getHelmet(), options.getChestplate(),
                options.getLeggings(), options.getBoots(), engine));

            engine.rasterize(triangles, buffer, PerspectiveParams.NONE, options.getRotation());
            if (options.isAntiAlias()) buffer.applyFxaa();
            return engine.finaliseWithGlint(buffer, hasEnchantedArmor(options), GlintKit.GlintOptions.armorDefault(30));
        }

    }

    /**
     * Bust renderer - head, torso and arms, in 2D or 3D.
     */
    @RequiredArgsConstructor
    public static final class Bust implements Renderer<PlayerOptions> {

        private final @NotNull PlayerRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull PlayerOptions options) {
            if (options.getDimension() == PlayerOptions.Dimension.TWO_D)
                return render2D(this.parent, options);
            return render3D(options);
        }

        private @NotNull ImageData render3D(@NotNull PlayerOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            IsometricEngine engine = IsometricEngine.standard(this.parent.context);
            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            addBodyPart(triangles, skin, SkinFace.HEAD, BUST_HEAD_MIN, BUST_HEAD_MAX, options);
            addBodyPart(triangles, skin, SkinFace.TORSO, BUST_TORSO_MIN, BUST_TORSO_MAX, options);
            addBodyPart(triangles, skin, SkinFace.RIGHT_ARM, BUST_R_ARM_MIN, BUST_R_ARM_MAX, options);
            addBodyPart(triangles, skin, SkinFace.LEFT_ARM, BUST_L_ARM_MIN, BUST_L_ARM_MAX, options);

            Map<SkinFace, Vector3f[]> bp = new EnumMap<>(SkinFace.class);
            bp.put(SkinFace.HEAD, new Vector3f[]{ BUST_HEAD_MIN, BUST_HEAD_MAX });
            bp.put(SkinFace.TORSO, new Vector3f[]{ BUST_TORSO_MIN, BUST_TORSO_MAX });
            bp.put(SkinFace.RIGHT_ARM, new Vector3f[]{ BUST_R_ARM_MIN, BUST_R_ARM_MAX });
            bp.put(SkinFace.LEFT_ARM, new Vector3f[]{ BUST_L_ARM_MIN, BUST_L_ARM_MAX });
            triangles.addAll(ArmorKit.buildHumanoidArmor3D(bp,
                options.getHelmet(), options.getChestplate(),
                options.getLeggings(), options.getBoots(), engine));

            resolveCape(this.parent, options)
                .ifPresent(cape -> addCape(triangles, cape, BUST_TORSO_MIN, BUST_TORSO_MAX));

            engine.rasterize(triangles, buffer, PerspectiveParams.NONE, options.getRotation());
            if (options.isAntiAlias()) buffer.applyFxaa();
            return engine.finaliseWithGlint(buffer, hasEnchantedArmor(options), GlintKit.GlintOptions.armorDefault(30));
        }

    }

    /**
     * Full-body renderer - all six body parts, in 2D or 3D.
     */
    @RequiredArgsConstructor
    public static final class Full implements Renderer<PlayerOptions> {

        private final @NotNull PlayerRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull PlayerOptions options) {
            if (options.getDimension() == PlayerOptions.Dimension.TWO_D)
                return render2D(this.parent, options);
            return render3D(options);
        }

        private @NotNull ImageData render3D(@NotNull PlayerOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            IsometricEngine engine = IsometricEngine.standard(this.parent.context);
            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            addBodyPart(triangles, skin, SkinFace.HEAD, FULL_HEAD_MIN, FULL_HEAD_MAX, options);
            addBodyPart(triangles, skin, SkinFace.TORSO, FULL_TORSO_MIN, FULL_TORSO_MAX, options);
            addBodyPart(triangles, skin, SkinFace.RIGHT_ARM, FULL_R_ARM_MIN, FULL_R_ARM_MAX, options);
            addBodyPart(triangles, skin, SkinFace.LEFT_ARM, FULL_L_ARM_MIN, FULL_L_ARM_MAX, options);
            addBodyPart(triangles, skin, SkinFace.RIGHT_LEG, FULL_R_LEG_MIN, FULL_R_LEG_MAX, options);
            addBodyPart(triangles, skin, SkinFace.LEFT_LEG, FULL_L_LEG_MIN, FULL_L_LEG_MAX, options);

            Map<SkinFace, Vector3f[]> bp = new EnumMap<>(SkinFace.class);
            bp.put(SkinFace.HEAD, new Vector3f[]{ FULL_HEAD_MIN, FULL_HEAD_MAX });
            bp.put(SkinFace.TORSO, new Vector3f[]{ FULL_TORSO_MIN, FULL_TORSO_MAX });
            bp.put(SkinFace.RIGHT_ARM, new Vector3f[]{ FULL_R_ARM_MIN, FULL_R_ARM_MAX });
            bp.put(SkinFace.LEFT_ARM, new Vector3f[]{ FULL_L_ARM_MIN, FULL_L_ARM_MAX });
            bp.put(SkinFace.RIGHT_LEG, new Vector3f[]{ FULL_R_LEG_MIN, FULL_R_LEG_MAX });
            bp.put(SkinFace.LEFT_LEG, new Vector3f[]{ FULL_L_LEG_MIN, FULL_L_LEG_MAX });
            triangles.addAll(ArmorKit.buildHumanoidArmor3D(bp,
                options.getHelmet(), options.getChestplate(),
                options.getLeggings(), options.getBoots(), engine));

            resolveCape(this.parent, options)
                .ifPresent(cape -> addCape(triangles, cape, FULL_TORSO_MIN, FULL_TORSO_MAX));

            engine.rasterize(triangles, buffer, PerspectiveParams.NONE, options.getRotation());
            if (options.isAntiAlias()) buffer.applyFxaa();
            return engine.finaliseWithGlint(buffer, hasEnchantedArmor(options), GlintKit.GlintOptions.armorDefault(30));
        }

    }

    /**
     * Adds a body part's base skin cube and optional overlay to the triangle list.
     */
    private static void addBodyPart(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer skin,
        @NotNull SkinFace part,
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull PlayerOptions options
    ) {
        triangles.addAll(BlockModelGeometryKit.box(min, max, part.cropAll(skin, false), ColorMath.WHITE));
        if (options.isRenderOverlay() && hasOverlay(skin))
            triangles.addAll(BlockModelGeometryKit.box(
                new Vector3f(min.x() - OVERLAY_INFLATE, min.y() - OVERLAY_INFLATE, min.z() - OVERLAY_INFLATE),
                new Vector3f(max.x() + OVERLAY_INFLATE, max.y() + OVERLAY_INFLATE, max.z() + OVERLAY_INFLATE),
                part.cropAll(skin, true), ColorMath.WHITE));
    }

}
