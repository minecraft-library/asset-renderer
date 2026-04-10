package dev.sbs.renderer;

import dev.sbs.renderer.draw.BlockFace;
import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.draw.GeometryKit;
import dev.sbs.renderer.draw.GlintKit;
import dev.sbs.renderer.draw.SkinFace;
import dev.sbs.renderer.draw.armor.ArmorKit;
import dev.sbs.renderer.draw.armor.ArmorPiece;
import dev.sbs.renderer.engine.IsometricEngine;
import dev.sbs.renderer.engine.PerspectiveParams;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.VisibleTriangle;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.math.Vector3f;
import dev.sbs.renderer.options.PlayerOptions;
import dev.sbs.renderer.pipeline.HttpFetcher;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.PixelBuffer;
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
            PixelBuffer cached = parent.skinCache.get(url);
            if (cached != null) return cached;

            byte[] bytes = parent.fetcher.get(url);
            PixelBuffer buffer = PixelBuffer.wrap(parent.imageFactory.fromByteArray(bytes).toBufferedImage());
            parent.skinCache.put(url, buffer);
            return buffer;
        }

        if (options.getSkinTextureId().isPresent()) {
            RasterEngine engine = new RasterEngine(parent.context);
            return engine.resolveTexture(options.getSkinTextureId().get());
        }

        return parent.context.resolveTexture("minecraft:entity/steve")
            .orElseThrow(() -> new RendererException("No default Steve skin registered and no skin supplied"));
    }

    static @NotNull ImageData finaliseWithGlint(
        @NotNull Canvas canvas,
        @NotNull PlayerRenderer parent,
        @NotNull PlayerOptions options
    ) {
        if (!ArmorKit.hasEnchantedArmor(
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots()))
            return RenderEngine.staticFrame(canvas);

        IsometricEngine engine = new IsometricEngine(parent.context);
        GlintKit.GlintOptions glintOptions = GlintKit.GlintOptions.armorDefault(30);
        Optional<PixelBuffer> glintTexture = engine.tryResolveTexture(glintOptions.glintTextureId());
        if (glintTexture.isEmpty())
            return RenderEngine.staticFrame(canvas);

        ConcurrentList<PixelBuffer> frames = GlintKit.apply(canvas.getBuffer(), glintTexture.get(), glintOptions);
        int frameDelayMs = Math.max(1, Math.round(1000f / 30f));
        return RenderEngine.output(frames, frameDelayMs);
    }

    /** Whether the skin is wide enough to have overlay layers. */
    private static boolean hasOverlay(@NotNull PixelBuffer skin) {
        return skin.getWidth() >= 64 && skin.getHeight() >= 64;
    }

    /** Whether the skin is wide enough to have hat overlay (smaller threshold than full overlay). */
    private static boolean hasHatOverlay(@NotNull PixelBuffer skin) {
        return skin.getWidth() >= 48 && skin.getHeight() >= 16;
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
        int bodyHeight = switch (type) {
            case SKULL -> 8;
            case BUST -> 20;
            case FULL -> 32;
        };
        int bodyWidth = switch (type) {
            case SKULL -> 8;
            case BUST, FULL -> 16;
        };
        int scale = outputSize / bodyHeight;
        int offsetX = (outputSize - bodyWidth * scale) / 2;
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
        Canvas canvas = engine.createCanvas(options.getOutputSize(), options.getOutputSize());

        int[] so = scaleAndOffset2D(options.getType(), options.getOutputSize());
        int scale = so[0];
        int offsetX = so[1];
        BodyPart2D[] parts = layout2D(options.getType(), scale, offsetX);

        boolean overlay = options.isRenderOverlay();

        for (BodyPart2D bp : parts) {
            // Base skin
            PixelBuffer face = bp.part.crop(skin, BlockFace.SOUTH, false);
            canvas.blitScaled(face, bp.x, bp.y, bp.w, bp.h);

            // Overlay
            if (overlay && hasOverlay(skin)) {
                PixelBuffer overlayFace = bp.part.crop(skin, BlockFace.SOUTH, true);
                canvas.blitScaled(overlayFace, bp.x, bp.y, bp.w, bp.h);
            } else if (overlay && bp.part == SkinFace.HEAD && hasHatOverlay(skin)) {
                PixelBuffer hat = SkinFace.HEAD.crop(skin, BlockFace.SOUTH, true);
                canvas.blitScaled(hat, bp.x, bp.y, bp.w, bp.h);
            }

            // Armor + trim for this body part
            Optional<ArmorPiece> piece = armorForPart(bp.part, options);
            ArmorKit.compositeSlot2D(canvas, bp.part, piece, bp.x, bp.y, bp.w, bp.h, engine);
        }

        if (options.isAntiAlias())
            canvas.getBuffer().applyFxaa();

        return finaliseWithGlint(canvas, parent, options);
    }

    /**
     * Returns the armor piece that covers the given body part, if any.
     */
    private static @NotNull Optional<ArmorPiece> armorForPart(
        @NotNull SkinFace part,
        @NotNull PlayerOptions options
    ) {
        return switch (part) {
            case HEAD -> options.getHelmet();
            case TORSO -> options.getChestplate().or(options::getLeggings);
            case RIGHT_ARM, LEFT_ARM -> options.getChestplate();
            case RIGHT_LEG, LEFT_LEG -> options.getBoots().or(options::getLeggings);
        };
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
            IsometricEngine engine = new IsometricEngine(this.parent.context);
            Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());

            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            triangles.addAll(GeometryKit.unitCube(SkinFace.HEAD.cropAll(skin, false), ColorKit.WHITE));
            if (options.isRenderOverlay() && hasHatOverlay(skin))
                triangles.addAll(GeometryKit.box(
                    new Vector3f(-0.52f, -0.52f, -0.52f),
                    new Vector3f(0.52f, 0.52f, 0.52f),
                    SkinFace.HEAD.cropAll(skin, true), ColorKit.WHITE));

            Map<SkinFace, Vector3f[]> bp = new EnumMap<>(SkinFace.class);
            bp.put(SkinFace.HEAD, new Vector3f[]{ SKULL_HEAD_MIN, SKULL_HEAD_MAX });
            triangles.addAll(ArmorKit.buildHumanoidArmor3D(bp,
                options.getHelmet(), options.getChestplate(),
                options.getLeggings(), options.getBoots(), engine));

            engine.rasterize(triangles, canvas, PerspectiveParams.NONE,
                options.getPitch(), options.getYaw(), options.getRoll());
            if (options.isAntiAlias()) canvas.getBuffer().applyFxaa();
            return finaliseWithGlint(canvas, this.parent, options);
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
            IsometricEngine engine = new IsometricEngine(this.parent.context);
            Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());
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

            engine.rasterize(triangles, canvas, PerspectiveParams.NONE,
                options.getPitch(), options.getYaw(), options.getRoll());
            if (options.isAntiAlias()) canvas.getBuffer().applyFxaa();
            return finaliseWithGlint(canvas, this.parent, options);
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
            IsometricEngine engine = new IsometricEngine(this.parent.context);
            Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());
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

            engine.rasterize(triangles, canvas, PerspectiveParams.NONE,
                options.getPitch(), options.getYaw(), options.getRoll());
            if (options.isAntiAlias()) canvas.getBuffer().applyFxaa();
            return finaliseWithGlint(canvas, this.parent, options);
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
        triangles.addAll(GeometryKit.box(min, max, part.cropAll(skin, false), ColorKit.WHITE));
        if (options.isRenderOverlay() && hasOverlay(skin))
            triangles.addAll(GeometryKit.box(
                new Vector3f(min.getX() - OVERLAY_INFLATE, min.getY() - OVERLAY_INFLATE, min.getZ() - OVERLAY_INFLATE),
                new Vector3f(max.getX() + OVERLAY_INFLATE, max.getY() + OVERLAY_INFLATE, max.getZ() + OVERLAY_INFLATE),
                part.cropAll(skin, true), ColorKit.WHITE));
    }

}
