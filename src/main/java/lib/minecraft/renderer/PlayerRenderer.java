package lib.minecraft.renderer;

import api.simplified.mojang.MojangContract;
import api.simplified.mojang.request.MojangDomain;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.appearance.ArmorPiece;
import lib.minecraft.renderer.appearance.ArmorTrim;
import lib.minecraft.renderer.engine.FinalizeStage;
import lib.minecraft.renderer.engine.GlintMask;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.SixFaces;
import lib.minecraft.renderer.geometry.SkinFace;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.ArmorKit;
import lib.minecraft.renderer.kit.BlockGeometryKit;
import lib.minecraft.renderer.kit.GlintKit;
import lib.minecraft.renderer.layer.GeometryLayer;
import lib.minecraft.renderer.layer.ImageLayer;
import lib.minecraft.renderer.layer.LayerStack;
import lib.minecraft.renderer.layer.Player3DLayerSlot;
import lib.minecraft.renderer.layer.PlayerLayerSlot;
import lib.minecraft.renderer.options.PlayerOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
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

    // ---------------------------------------------------------------------------------------
    // 3D body-part bounding cubes per render type.
    // ---------------------------------------------------------------------------------------

    /**
     * Skull: unit head cube.
     */
    private static final Vector3f SKULL_HEAD_MIN = new Vector3f(-0.5f, -0.5f, -0.5f);
    private static final Vector3f SKULL_HEAD_MAX = new Vector3f(0.5f, 0.5f, 0.5f);

    /**
     * Bust: head + torso + arms at the same vanilla proportions as the {@link #FULL_HEAD_MIN full
     * body}'s upper half (head 8x8x8, torso 8x12x4, arms 4x12x4 in MC pixels). The head is the same
     * width as the torso - the earlier bust used an oversized 0.5 head cube against a 0.4-wide
     * torso, which rendered "bobble-head" large. The auto-fit step centres + scales this group to
     * the canvas, so only the relative proportions matter here.
     */
    private static final Vector3f BUST_HEAD_MIN = new Vector3f(-0.12f, 0.24f, -0.12f);
    private static final Vector3f BUST_HEAD_MAX = new Vector3f(0.12f, 0.48f, 0.12f);
    private static final Vector3f BUST_TORSO_MIN = new Vector3f(-0.12f, -0.12f, -0.06f);
    private static final Vector3f BUST_TORSO_MAX = new Vector3f(0.12f, 0.24f, 0.06f);
    private static final Vector3f BUST_R_ARM_MIN = new Vector3f(-0.24f, -0.12f, -0.06f);
    private static final Vector3f BUST_R_ARM_MAX = new Vector3f(-0.12f, 0.24f, 0.06f);
    private static final Vector3f BUST_L_ARM_MIN = new Vector3f(0.12f, -0.12f, -0.06f);
    private static final Vector3f BUST_L_ARM_MAX = new Vector3f(0.24f, 0.24f, 0.06f);

    /**
     * Full body: 1 MC pixel = 1/32 model unit, centred vertically.
     */
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

    /**
     * Overlay (hat / hood / second layer) outset over the base cube.
     */
    private static final float OVERLAY_INFLATE = 0.01f;

    /**
     * Fraction of the canvas's smaller dimension the 3D silhouette spans after auto-fit. Leaves a
     * thin margin so rotated poses and outset overlays / armor stay inside the frame.
     */
    private static final float PLAYER_FILL = 0.9f;

    private final @NotNull RendererContext context;
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
        ImageData rendered = switch (options.getType()) {
            case SKULL -> this.skull.render(options);
            case BUST -> this.bust.render(options);
            case FULL -> this.full.render(options);
        };
        return options.getBackground().composite(rendered);
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
                byte[] bytes = fetchTexture(u);
                return PixelBuffer.wrap(parent.imageFactory.fromByteArray(bytes).toBufferedImage());
            });
        }

        if (options.getSkinTextureId().isPresent()) {
            RasterEngine engine = new RasterEngine(parent.context);
            return engine.resolveTexture(options.getSkinTextureId().get());
        }

        return parent.context.resolveTexture("minecraft:entity/steve")
            .orElseThrow(() -> new RenderException("No default Steve skin registered and no skin supplied"));
    }

    /**
     * Reads a Mojang skin or cape texture by extracting the trailing path segment from the URL
     * (the texture hash) and streaming the PNG bytes through {@link Pipeline#mojang() Pipeline.mojang()}'s
     * {@link MojangContract#downloadTexture(String) downloadTexture}.
     * <p>
     * The URL format is the {@code http://textures.minecraft.net/texture/<hash>} pattern Mojang's
     * session API returns in {@code MojangProperties}; the routing and rate limiting come from
     * the contract's {@link MojangDomain#MINECRAFT_TEXTURES} entry.
     */
    private static byte @NotNull [] fetchTexture(@NotNull String url) {
        String hash = url.substring(url.lastIndexOf('/') + 1);
        try (InputStream stream = Pipeline.mojang().downloadTexture(hash)) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new RenderException(ex, "Failed to fetch texture from '%s'", url);
        }
    }

    /**
     * Whether any of the four armor slots carries an enchanted piece.
     */
    private static boolean hasEnchantedArmor(@NotNull PlayerOptions options) {
        return ArmorKit.hasEnchantedArmor(
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots()
        );
    }

    /**
     * Whether the skin is wide enough to have overlay layers.
     */
    private static boolean hasOverlay(@NotNull PixelBuffer skin) {
        return skin.width() >= 64 && skin.height() >= 64;
    }

    /**
     * Whether the skin is wide enough to have hat overlay (smaller threshold than full overlay).
     */
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
                byte[] bytes = fetchTexture(url);
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
     * y=1:  [1px edge][10px OUTER][1px edge][10px INNER]  (16 rows)
     * </pre>
     * The {@code OUTER} region ({@code x 1..10}) carries the visible cape design and the
     * {@code INNER} region ({@code x 12..21}) the plain lining. The cape hangs on the player's back
     * (its {@code -Z} / {@link BlockFace#NORTH NORTH} face points outward, away from the body), so
     * the design is mapped to {@code NORTH} and the lining to the body-facing {@code SOUTH}. Vanilla
     * achieves the same by rotating the cloak cuboid 180&deg; about Y; here the geometry stays
     * axis-aligned and the two broad faces are assigned directly.
     */
    private static @NotNull SixFaces cropCapeFaces(@NotNull PixelBuffer cape) {
        return new SixFaces(
            cropRect(cape, 11, 0, 10, 1),  // DOWN
            cropRect(cape,  1, 0, 10, 1),  // UP
            cropRect(cape,  1, 1, 10, 16), // NORTH - outer design (faces outward / rear camera)
            cropRect(cape, 12, 1, 10, 16), // SOUTH - inner lining (against the back)
            cropRect(cape,  0, 1,  1, 16), // WEST
            cropRect(cape, 11, 1,  1, 16)  // EAST
        );
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
        // The cape hangs on the player's back (the north / -Z torso face), the side the iso
        // block-icon pose presents to the camera.
        float capeBack = torsoMin.z();

        Vector3f capeMin = new Vector3f(cx - capeW / 2f, capeTop - capeH, capeBack - capeD);
        Vector3f capeMax = new Vector3f(cx + capeW / 2f, capeTop, capeBack);

        SixFaces faces = cropCapeFaces(capeTexture);
        triangles.addAll(BlockGeometryKit.buildBoxTriangles(capeMin, capeMax, faces, ColorMath.WHITE));
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
        BodyPart2D[] parts = layout2D(options.getType(), so[0], so[1]);

        boolean overlay = options.isRenderOverlay();
        // Glint only the armor, not the bare skin: when enchanted, the armor / trim composites stamp
        // their coverage into this mask and the foil is confined to it. Null when nothing glints.
        boolean enchanted = hasEnchantedArmor(options);
        GlintMask glintMask = enchanted ? new GlintMask(options.getOutputSize(), options.getOutputSize()) : null;

        // Compose the front-facing body as an ordered ImageLayer stack so the skin / overlay / armor
        // passes are governed by slot order (and extensible via PlayerOptions.layerDecorator) rather
        // than interleaved per body part. Body-part rectangles tile the canvas without overlap, so the
        // per-pass order is equivalent to the historic per-part order.
        LayerStack<ImageLayer> stack = new LayerStack<>();
        stack.append(PlayerLayerSlot.SKIN, frame -> {
            for (BodyPart2D bp : parts)
                frame.blitScaled(bp.part.crop(skin, BlockFace.SOUTH, false), bp.x, bp.y, bp.w, bp.h);
        });
        if (overlay)
            stack.append(PlayerLayerSlot.OVERLAY, frame -> {
                for (BodyPart2D bp : parts) {
                    if (hasOverlay(skin))
                        frame.blitScaled(bp.part.crop(skin, BlockFace.SOUTH, true), bp.x, bp.y, bp.w, bp.h);
                    else if (bp.part == SkinFace.HEAD && hasHatOverlay(skin))
                        frame.blitScaled(SkinFace.HEAD.crop(skin, BlockFace.SOUTH, true), bp.x, bp.y, bp.w, bp.h);
                }
            });
        stack.append(PlayerLayerSlot.ARMOR, frame -> {
            for (BodyPart2D bp : parts)
                compositeArmor2D(frame, bp.part, bp.x, bp.y, bp.w, bp.h, options, engine, glintMask);
        });

        for (ImageLayer layer : options.getLayerDecorator().apply(stack).ordered())
            layer.apply(buffer);

        if (options.isAntiAlias())
            buffer.applyFxaa();

        return engine.finaliseWithGlint(buffer, enchanted, GlintKit.GlintOptions.armorDefault(30), glintMask);
    }

    /**
     * Composites all armor slots that cover the given body part in {@link ArmorTrim.Slot} declaration
     * order - layer-2 leggings first so the chestplate / boots win on overlapping parts (torso, legs).
     */
    private static void compositeArmor2D(
        @NotNull PixelBuffer target,
        @NotNull SkinFace part,
        int x, int y, int w, int h,
        @NotNull PlayerOptions options,
        @NotNull RasterEngine engine,
        @Nullable GlintMask glintMask
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

            ArmorKit.compositeSlot2D(target, part, slot, piece.get(), x, y, w, h, engine, glintMask);
        }
    }

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
            IsometricEngine engine = IsometricEngine.forPlayerIcon(this.parent.context);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            stack.append(Player3DLayerSlot.BODY, sink -> {
                sink.addAll(BlockGeometryKit.unitCube(SkinFace.HEAD.cropAll(skin, false), ColorMath.WHITE));
                if (options.isRenderOverlay() && hasHatOverlay(skin))
                    sink.addAll(BlockGeometryKit.buildBoxTriangles(
                        new Vector3f(-0.52f, -0.52f, -0.52f),
                        new Vector3f(0.52f, 0.52f, 0.52f),
                        SkinFace.HEAD.cropAll(skin, true), ColorMath.WHITE));
            });
            stack.append(Player3DLayerSlot.ARMOR, sink -> {
                Map<SkinFace, Vector3f[]> bp = new EnumMap<>(SkinFace.class);
                bp.put(SkinFace.HEAD, new Vector3f[]{ SKULL_HEAD_MIN, SKULL_HEAD_MAX });
                sink.addAll(ArmorKit.buildHumanoidArmor3D(bp,
                    options.getHelmet(), options.getChestplate(),
                    options.getLeggings(), options.getBoots(), engine));
            });

            for (GeometryLayer layer : options.getGeometryLayerDecorator().apply(stack).ordered())
                layer.contribute(triangles);

            return rasterize3D(engine, triangles, options);
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
            IsometricEngine engine = IsometricEngine.forPlayerIcon(this.parent.context);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            stack.append(Player3DLayerSlot.BODY, sink -> {
                addBodyPart(sink, skin, SkinFace.HEAD, BUST_HEAD_MIN, BUST_HEAD_MAX, options);
                addBodyPart(sink, skin, SkinFace.TORSO, BUST_TORSO_MIN, BUST_TORSO_MAX, options);
                addBodyPart(sink, skin, SkinFace.RIGHT_ARM, BUST_R_ARM_MIN, BUST_R_ARM_MAX, options);
                addBodyPart(sink, skin, SkinFace.LEFT_ARM, BUST_L_ARM_MIN, BUST_L_ARM_MAX, options);
            });
            stack.append(Player3DLayerSlot.ARMOR, sink -> {
                Map<SkinFace, Vector3f[]> bp = new EnumMap<>(SkinFace.class);
                bp.put(SkinFace.HEAD, new Vector3f[]{ BUST_HEAD_MIN, BUST_HEAD_MAX });
                bp.put(SkinFace.TORSO, new Vector3f[]{ BUST_TORSO_MIN, BUST_TORSO_MAX });
                bp.put(SkinFace.RIGHT_ARM, new Vector3f[]{ BUST_R_ARM_MIN, BUST_R_ARM_MAX });
                bp.put(SkinFace.LEFT_ARM, new Vector3f[]{ BUST_L_ARM_MIN, BUST_L_ARM_MAX });
                sink.addAll(ArmorKit.buildHumanoidArmor3D(bp,
                    options.getHelmet(), options.getChestplate(),
                    options.getLeggings(), options.getBoots(), engine));
            });
            resolveCape(this.parent, options)
                .ifPresent(cape -> stack.append(Player3DLayerSlot.CAPE,
                    sink -> addCape(sink, cape, BUST_TORSO_MIN, BUST_TORSO_MAX)));

            for (GeometryLayer layer : options.getGeometryLayerDecorator().apply(stack).ordered())
                layer.contribute(triangles);

            return rasterize3D(engine, triangles, options);
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
            IsometricEngine engine = IsometricEngine.forPlayerIcon(this.parent.context);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            stack.append(Player3DLayerSlot.BODY, sink -> {
                addBodyPart(sink, skin, SkinFace.HEAD, FULL_HEAD_MIN, FULL_HEAD_MAX, options);
                addBodyPart(sink, skin, SkinFace.TORSO, FULL_TORSO_MIN, FULL_TORSO_MAX, options);
                addBodyPart(sink, skin, SkinFace.RIGHT_ARM, FULL_R_ARM_MIN, FULL_R_ARM_MAX, options);
                addBodyPart(sink, skin, SkinFace.LEFT_ARM, FULL_L_ARM_MIN, FULL_L_ARM_MAX, options);
                addBodyPart(sink, skin, SkinFace.RIGHT_LEG, FULL_R_LEG_MIN, FULL_R_LEG_MAX, options);
                addBodyPart(sink, skin, SkinFace.LEFT_LEG, FULL_L_LEG_MIN, FULL_L_LEG_MAX, options);
            });
            stack.append(Player3DLayerSlot.ARMOR, sink -> {
                Map<SkinFace, Vector3f[]> bp = new EnumMap<>(SkinFace.class);
                bp.put(SkinFace.HEAD, new Vector3f[]{ FULL_HEAD_MIN, FULL_HEAD_MAX });
                bp.put(SkinFace.TORSO, new Vector3f[]{ FULL_TORSO_MIN, FULL_TORSO_MAX });
                bp.put(SkinFace.RIGHT_ARM, new Vector3f[]{ FULL_R_ARM_MIN, FULL_R_ARM_MAX });
                bp.put(SkinFace.LEFT_ARM, new Vector3f[]{ FULL_L_ARM_MIN, FULL_L_ARM_MAX });
                bp.put(SkinFace.RIGHT_LEG, new Vector3f[]{ FULL_R_LEG_MIN, FULL_R_LEG_MAX });
                bp.put(SkinFace.LEFT_LEG, new Vector3f[]{ FULL_L_LEG_MIN, FULL_L_LEG_MAX });
                sink.addAll(ArmorKit.buildHumanoidArmor3D(bp,
                    options.getHelmet(), options.getChestplate(),
                    options.getLeggings(), options.getBoots(), engine));
            });
            resolveCape(this.parent, options)
                .ifPresent(cape -> stack.append(Player3DLayerSlot.CAPE,
                    sink -> addCape(sink, cape, FULL_TORSO_MIN, FULL_TORSO_MAX)));

            for (GeometryLayer layer : options.getGeometryLayerDecorator().apply(stack).ordered())
                layer.contribute(triangles);

            return rasterize3D(engine, triangles, options);
        }

    }

    /**
     * Rasterizes the assembled body + armor triangles to a finished image: auto-fits the silhouette
     * to fill the canvas ({@link #PLAYER_FILL}), applies supersampling (SSAA) and optional FXAA,
     * then composites the armor glint. Shared by all three 3D sub-renderers.
     */
    private static @NotNull ImageData rasterize3D(
        @NotNull IsometricEngine engine,
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PlayerOptions options
    ) {
        int size = options.getOutputSize();
        boolean enchanted = hasEnchantedArmor(options);
        GlintKit.GlintOptions glint = GlintKit.GlintOptions.armorDefault(30);
        int ssaa = Math.max(1, options.getSupersample());
        // The glint mask is recorded at the raster size, then box-downsampled to the output so the
        // foil is confined to the armor (not the bare body) after the SSAA blit.
        return FinalizeStage.run(size, size, ssaa, options.isAntiAlias(), enchanted,
            (target, mask) -> engine.rasterizeFitted(triangles, target, PerspectiveParams.NONE, options.getRotation(), PLAYER_FILL, mask),
            (buffer, mask) -> engine.finaliseWithGlint(buffer, enchanted, glint, mask));
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
        triangles.addAll(BlockGeometryKit.buildBoxTriangles(min, max, part.cropAll(skin, false), ColorMath.WHITE));
        if (options.isRenderOverlay() && hasOverlay(skin))
            triangles.addAll(BlockGeometryKit.buildBoxTriangles(
                new Vector3f(min.x() - OVERLAY_INFLATE, min.y() - OVERLAY_INFLATE, min.z() - OVERLAY_INFLATE),
                new Vector3f(max.x() + OVERLAY_INFLATE, max.y() + OVERLAY_INFLATE, max.z() + OVERLAY_INFLATE),
                part.cropAll(skin, true), ColorMath.WHITE));
    }

}
