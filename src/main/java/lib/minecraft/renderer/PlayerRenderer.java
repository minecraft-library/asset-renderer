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
import lib.minecraft.renderer.asset.equipment.ArmorForm;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.RasterPass;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.kit.ArmorKit;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.kit.ElytraKit;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.face.SixFaces;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.option.slot.PlayerSlot2D;
import lib.minecraft.renderer.option.slot.PlayerSlot3D;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
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
 * {@link ModelEngine} with a {@link Projection#VANILLA_ISO} pose, with armor as slightly inflated overlapping geometry.</li>
 * </ul>
 * Skin resolution is shared via the outer class, with URL-fetched skins cached for the
 * renderer's lifetime.
 */
public final class PlayerRenderer implements Renderer<PlayerOptions> {

    /**
     * The player's model-to-world facing - a {@code R_Y(180) = diag(-1,1,-1)} yaw flip that turns the
     * humanoid model's {@code +Z} {@code SOUTH} front toward the camera. Applied as a {@link Placement}
     * so the projection stays facing-neutral (see {@link Projection#VANILLA_ISO}): for any projection
     * {@code P}, {@code P.pose() · PLAYER_FACING} presents the front, so the default
     * {@code [30,225,0] · R_Y(180) = [30,45,0]} reproduces the shipped player pose. Byte-identical because
     * the body's block-cardinal face shading is baked per direction (pose-independent) and the total
     * transform is unchanged.
     */
    private static final @NotNull Placement PLAYER_FACING =
        new Placement(Matrix4f.IDENTITY.scale(-1f, 1f, -1f));

    /**
     * Overlay (hat / hood / second layer) outset over the base cube.
     */
    private static final float OVERLAY_INFLATE = 0.01f;

    /**
     * Fraction of the canvas's smaller dimension the 3D silhouette spans after auto-fit. {@code 1.0}
     * fills the canvas to match the entity renderer's {@code OUTPUT_SIZE} fit (which fills the whole
     * canvas at {@code padding = 0}), so a player and an entity render at the same footprint on the same
     * canvas; outset overlays / armor that extend past the body may touch the frame edge, as they do for
     * entities.
     */
    private static final float PLAYER_FILL = 1.0f;

    private final @NotNull RendererContext context;
    private final @NotNull ImageFactory imageFactory = new ImageFactory();

    /**
     * URL-fetched skin and cape textures cached for the renderer's lifetime, keyed by URL (capes use a
     * {@code "cape:"} prefix so they never collide with a skin sharing the same URL).
     */
    private final @NotNull ConcurrentMap<String, PixelBuffer> skinCache = Concurrent.newMap();

    private final @NotNull Skull skull;
    private final @NotNull Bust bust;
    private final @NotNull Full full;

    /**
     * Constructs a player renderer over the given context, wiring up the three per-type sub-renderers.
     *
     * @param context renderer context for texture resolution and engine setup
     */
    public PlayerRenderer(@NotNull RendererContext context) {
        this.context = context;
        this.skull = new Skull(this);
        this.bust = new Bust(this);
        this.full = new Full(this);
    }

    /**
     * Dispatches on {@link PlayerOptions#getType()} to the matching sub-renderer, then composites the
     * result over the caller's background.
     */
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

    /**
     * Resolves the player skin by priority from the {@link PlayerOptions#getSkin() skin} sources:
     * explicit skin bytes &gt; skin URL (fetched via {@link #fetchTexture} and cached for the
     * renderer's lifetime) &gt; skin texture id (resolved against the pack stack) &gt; the default
     * {@code minecraft:entity/steve} skin.
     *
     * @param parent the owning renderer, for its image factory / skin cache / context
     * @param options the render options
     * @return the resolved skin buffer
     * @throws RenderException if the default Steve skin is requested but not registered
     */
    static @NotNull PixelBuffer resolveSkin(@NotNull PlayerRenderer parent, @NotNull PlayerOptions options) {
        if (options.getSkin().getSkin().getBytes().isPresent())
            return parent.imageFactory.fromByteArray(options.getSkin().getSkin().getBytes().get()).toPixelBuffer();

        if (options.getSkin().getSkin().getUrl().isPresent()) {
            String url = options.getSkin().getSkin().getUrl().get();
            return parent.skinCache.computeIfAbsent(url, u -> {
                byte[] bytes = fetchTexture(u);
                return parent.imageFactory.fromByteArray(bytes).toPixelBuffer();
            });
        }

        if (options.getSkin().getSkin().getId().isPresent()) {
            RasterEngine engine = new RasterEngine(parent.context);
            return engine.textures().resolveTexture(options.getSkin().getSkin().getId().get());
        }

        return parent.context.resolveTexture("minecraft:entity/steve")
            .orElseThrow(() -> new RenderException("No default Steve skin registered and no skin supplied"));
    }

    /**
     * Reads a Mojang skin or cape texture by extracting the trailing path segment from the URL
     * (the texture hash) and streaming the PNG bytes through {@link ClientAcquisition#mojang() ClientAcquisition.mojang()}'s
     * {@link MojangContract#downloadTexture(String) downloadTexture}.
     * <p>
     * The URL format is the {@code http://textures.minecraft.net/texture/<hash>} pattern Mojang's
     * session API returns in {@code MojangProperties}; the routing and rate limiting come from
     * the contract's {@link MojangDomain#MINECRAFT_TEXTURES} entry.
     */
    private static byte @NotNull [] fetchTexture(@NotNull String url) {
        String hash = url.substring(url.lastIndexOf('/') + 1);
        try (InputStream stream = ClientAcquisition.mojang().downloadTexture(hash)) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new RenderException(ex, "Failed to fetch texture from '%s'", url);
        }
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
        if (!options.getSkin().isRenderCape()) return Optional.empty();

        if (options.getSkin().getCape().getBytes().isPresent())
            return Optional.of(parent.imageFactory.fromByteArray(options.getSkin().getCape().getBytes().get()).toPixelBuffer());

        if (options.getSkin().getCape().getUrl().isPresent()) {
            String url = options.getSkin().getCape().getUrl().get();
            return Optional.of(parent.skinCache.computeIfAbsent("cape:" + url, ignored -> {
                byte[] bytes = fetchTexture(url);
                return parent.imageFactory.fromByteArray(bytes).toPixelBuffer();
            }));
        }

        if (options.getSkin().getCape().getId().isPresent()) {
            RasterEngine engine = new RasterEngine(parent.context);
            return engine.textures().tryResolveTexture(options.getSkin().getCape().getId().get());
        }

        return Optional.empty();
    }

    /**
     * Resolves the caller-supplied elytra wing texture ({@code SkinOptions.elytra}) using the same
     * source priority chain as the cape, or empty when it supplies no source (the wings then fall back
     * to the wearer's cape or the static elytra skin).
     */
    static @NotNull Optional<PixelBuffer> resolveElytraSource(@NotNull PlayerRenderer parent, @NotNull PlayerOptions options) {
        if (options.getSkin().getElytra().getBytes().isPresent())
            return Optional.of(parent.imageFactory.fromByteArray(options.getSkin().getElytra().getBytes().get()).toPixelBuffer());

        if (options.getSkin().getElytra().getUrl().isPresent()) {
            String url = options.getSkin().getElytra().getUrl().get();
            return Optional.of(parent.skinCache.computeIfAbsent("elytra:" + url, ignored -> {
                byte[] bytes = fetchTexture(url);
                return parent.imageFactory.fromByteArray(bytes).toPixelBuffer();
            }));
        }

        if (options.getSkin().getElytra().getId().isPresent()) {
            RasterEngine engine = new RasterEngine(parent.context);
            return engine.textures().tryResolveTexture(options.getSkin().getElytra().getId().get());
        }

        return Optional.empty();
    }

    /**
     * Appends the back layer for a 3D player scope: the elytra wings when {@code renderElytra}, else the
     * flat cape when {@code renderCape}. An equipped elytra supersedes the cape (matching vanilla) and
     * draws the wearer's cape texture when present - vanilla's {@code use_player_texture}, so a caped
     * player's elytra shows the cape design - degrading to a caller-supplied or static elytra skin.
     */
    private static void appendBackLayer(
        @NotNull PlayerRenderer parent, @NotNull LayerStack<GeometryLayer> stack, @NotNull PlayerOptions options,
        @NotNull ModelEngine engine, @NotNull Box torso
    ) {
        Vector3f torsoMin = new Vector3f(torso.minX(), torso.minY(), torso.minZ());
        Vector3f torsoMax = new Vector3f(torso.maxX(), torso.maxY(), torso.maxZ());
        if (options.getSkin().isRenderElytra()) {
            Optional<PixelBuffer> playerTexture = resolveCape(parent, options).or(() -> resolveElytraSource(parent, options));
            stack.append(PlayerSlot3D.CAPE, sink ->
                sink.addAll(ElytraKit.buildPlayerWings3D(engine.textures(), torsoMin, torsoMax, playerTexture, Optional.empty(), 0)));
            return;
        }
        resolveCape(parent, options).ifPresent(cape ->
            stack.append(PlayerSlot3D.CAPE, sink -> addCape(sink, cape, torsoMin, torsoMax)));
    }

    // ---------------------------------------------------------------------------------------
    // Cape geometry - 10x16x1 pixel box on a 64x32 texture, standard cube UV unwrap at (0,0).
    // ---------------------------------------------------------------------------------------

    /** The cape cube's atlas origin on a cape sheet. */
    private static final @NotNull Vector2f CAPE_UV = Vector2f.ZERO;

    /** The cape cube's extent in texture pixels. */
    private static final @NotNull Vector3f CAPE_SIZE = new Vector3f(10f, 16f, 1f);

    /**
     * The frame the cape's strips are read in, relative to the frame its box is built in.
     *
     * <p><b>This is a reflection, not a rotation, and it is preserved exactly rather than settled.</b>
     * It is the vanilla cube unwrap with the {@code UP} and {@code DOWN} strips transposed and nothing
     * else moved, which is what drops the determinant to {@code -1}. Whether that transposition is
     * deliberate compensation or a latent defect is undecided and needs vanilla's own cape model or a
     * reference render to settle; nothing here is a reason to change it, and the cost of guessing is
     * asymmetric. Dropping the swap moves the two {@code 10x1} slivers - 20 of the cube's 372 texels -
     * while adopting the armour and shield frame instead would move 320 of them and trade the outer
     * design for the inner lining, rendering the cape lining-outward.
     */
    private static final @NotNull Turn CAPE_FRAME = Turn.MIRROR_Y;

    /**
     * Crops the 6 face textures for the cape cube out of a cape texture, through the cube's own atlas
     * unwrap read in the {@link #CAPE_FRAME cape frame}. The cape model is a 10x16x1 box at UV origin
     * (0,0), so the vanilla cube unwrap lays it out as:
     * <pre>
     * y=0:  [1px edge][10px BOTTOM][1px edge][10px TOP]
     * y=1:  [1px WEST][10px NORTH ][1px EAST][10px SOUTH]  (16 rows)
     * </pre>
     * The {@code NORTH} region ({@code x 1..10}) carries the visible cape design and the {@code SOUTH}
     * region ({@code x 12..21}) the plain lining. The cape hangs on the player's back - its {@code -Z}
     * / {@link Face#NORTH NORTH} face points outward, away from the body - so the design lands
     * outward and the lining against the back.
     */
    private static @NotNull SixFaces cropCapeFaces(@NotNull PixelBuffer cape) {
        Unwrap.Atlas unwrap = new Unwrap.Atlas(CAPE_UV, CAPE_SIZE, false);
        return new SixFaces(
            unwrap.crop(cape, CAPE_FRAME.apply(Face.DOWN)),
            unwrap.crop(cape, CAPE_FRAME.apply(Face.UP)),
            unwrap.crop(cape, CAPE_FRAME.apply(Face.NORTH)),
            unwrap.crop(cape, CAPE_FRAME.apply(Face.SOUTH)),
            unwrap.crop(cape, CAPE_FRAME.apply(Face.WEST)),
            unwrap.crop(cape, CAPE_FRAME.apply(Face.EAST))
        );
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
     * A body part plus its 2D layout rectangle, in canvas pixels, for a given {@link PlayerOptions.Type}.
     *
     * @param part the body part to crop and blit
     * @param x left edge of the destination rectangle
     * @param y top edge of the destination rectangle
     * @param w destination width in pixels
     * @param h destination height in pixels
     */
    private record BodyPart2D(@NotNull HumanoidPart part, int x, int y, int w, int h) {}

    /**
     * The 2D layout for the given scope at the given pixel scale and horizontal offset, derived from
     * each part's own pixel box rather than tabulated per scope.
     * <p>
     * The front view lays the lattice out directly: a part's canvas X is how far its left edge sits
     * from the scope's own left edge, and its canvas Y is how far its <em>top</em> edge sits below the
     * scope's top - the one inversion, because the lattice counts Y upward and a canvas counts it
     * down. The rectangle's extent is the part's own.
     */
    private static @NotNull BodyPart2D @NotNull [] layout2D(@NotNull PlayerOptions.Type type, int scale, int offsetX) {
        HumanoidPart[] parts = type.parts();
        BodyPart2D[] layout = new BodyPart2D[parts.length];

        for (int i = 0; i < parts.length; i++) {
            HumanoidPart part = parts[i];
            layout[i] = new BodyPart2D(part,
                offsetX + (part.minPixelX() - type.getMinPixelX()) * scale,
                (type.getMaxPixelY() - part.maxPixelY()) * scale,
                part.pixelWidth() * scale,
                part.pixelHeight() * scale);
        }

        return layout;
    }

    /**
     * Computes the pixel scale and horizontal offset for 2D rendering so the body fills the
     * output canvas height with horizontal centering.
     */
    private static int @NotNull [] scaleAndOffset2D(@NotNull PlayerOptions.Type type, int canvasSize) {
        int scale = canvasSize / type.getBodyHeight();
        int offsetX = (canvasSize - type.getBodyWidth() * scale) / 2;
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
        int size = options.getOutput().getCanvasSize();

        int[] so = scaleAndOffset2D(options.getType(), size);
        BodyPart2D[] parts = layout2D(options.getType(), so[0], so[1]);

        boolean overlay = options.getSkin().isRenderOverlay();
        boolean enchanted = options.getArmor().hasEnchanted();

        // Compose the front-facing body as an ordered ImageLayer stack folded into the raster target;
        // the pass records the single glint mask (recordMask = enchanted), which the ARMOR / trim
        // composites stamp their coverage into so the foil is confined to the armor (not the bare
        // skin). Body-part rectangles tile the canvas without overlap, so the per-pass order matches
        // the per-part draw order.
        return Timeline.Static.ZERO.bake(
            RasterPass.of(size, size, 1, options.getOutput().isAntiAlias(),
                (target, tick) -> {
                LayerStack<ImageLayer> stack = new LayerStack<>();
                stack.append(PlayerSlot2D.SKIN, frame -> {
                    for (BodyPart2D bp : parts)
                        frame.blitScaled(bp.part.crop(skin, Face.SOUTH, false), bp.x, bp.y, bp.w, bp.h);
                });
                if (overlay)
                    stack.append(PlayerSlot2D.OVERLAY, frame -> {
                        for (BodyPart2D bp : parts) {
                            if (hasOverlay(skin))
                                frame.blitScaled(bp.part.crop(skin, Face.SOUTH, true), bp.x, bp.y, bp.w, bp.h);
                            else if (bp.part == HumanoidPart.HEAD && hasHatOverlay(skin))
                                frame.blitScaled(HumanoidPart.HEAD.crop(skin, Face.SOUTH, true), bp.x, bp.y, bp.w, bp.h);
                        }
                    });
                stack.append(PlayerSlot2D.ARMOR, frame -> {
                    for (BodyPart2D bp : parts)
                        compositeArmor2D(frame, bp.part, bp.x, bp.y, bp.w, bp.h, options, engine);
                });
                Layers.foldInto(stack, options.getLayerDecorator(), target);
            })
                .withMask(enchanted)
                .finishing(GlintKit.Foil.armor(engine.textures()::tryResolveTexture, enchanted)));
    }

    /**
     * Composites all armor slots that cover the given body part in {@link ArmorSlot} declaration
     * order - layer-2 leggings first so the chestplate / boots win on overlapping parts (torso, legs).
     */
    private static void compositeArmor2D(
        @NotNull PixelBuffer target,
        @NotNull HumanoidPart part,
        int x, int y, int w, int h,
        @NotNull PlayerOptions options,
        @NotNull RasterEngine engine
    ) {
        for (ArmorSlot slot : ArmorSlot.values()) {
            Optional<ArmorPiece> piece = switch (slot) {
                case HELMET -> options.getArmor().getHelmet();
                case CHESTPLATE -> options.getArmor().getChestplate();
                case LEGGINGS -> options.getArmor().getLeggings();
                case BOOTS -> options.getArmor().getBoots();
            };
            if (piece.isEmpty()) continue;

            boolean partInSlot = false;
            for (HumanoidPart slotPart : ArmorForm.playerParts(slot))
                if (slotPart == part) { partInSlot = true; break; }
            if (!partInSlot) continue;

            ArmorKit.compositeSlot2D(target, part, slot, piece.get(), x, y, w, h,
                Optional.ofNullable(options.getArmor().getItems().get(slot)), engine.textures());
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

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull PlayerOptions options) {
            if (options.getDimension() == PlayerOptions.Dimension.TWO_D)
                return render2D(this.parent, options);
            return render3D(options);
        }

        private @NotNull ImageData render3D(@NotNull PlayerOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            ModelEngine engine = new ModelEngine(this.parent.context, options.getOutput().getProjection().resolve(options.getOutput().getRotation(), options.getOutput().getFacing()).camera(), PLAYER_FACING);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            stack.append(PlayerSlot3D.BODY, sink -> {
                sink.addAll(BlockGeometryKit.unitCube(HumanoidPart.HEAD.cropAll(skin, false), ColorMath.WHITE));
                if (options.getSkin().isRenderOverlay() && hasHatOverlay(skin))
                    sink.addAll(BlockGeometryKit.buildBoxTriangles(
                        new Vector3f(-0.52f, -0.52f, -0.52f),
                        new Vector3f(0.52f, 0.52f, 0.52f),
                        HumanoidPart.HEAD.cropAll(skin, true), ColorMath.WHITE));
            });
            appendArmor(stack, PlayerOptions.Type.SKULL, options, engine);

            Layers.foldInto(stack, options.getGeometryLayerDecorator(), triangles);

            return rasterize3D(engine, triangles, options);
        }

    }

    /**
     * Bust renderer - head, torso and arms, in 2D or 3D.
     */
    @RequiredArgsConstructor
    public static final class Bust implements Renderer<PlayerOptions> {

        private final @NotNull PlayerRenderer parent;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull PlayerOptions options) {
            if (options.getDimension() == PlayerOptions.Dimension.TWO_D)
                return render2D(this.parent, options);
            return render3D(options);
        }

        private @NotNull ImageData render3D(@NotNull PlayerOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            ModelEngine engine = new ModelEngine(this.parent.context, options.getOutput().getProjection().resolve(options.getOutput().getRotation(), options.getOutput().getFacing()).camera(), PLAYER_FACING);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            stack.append(PlayerSlot3D.BODY, sink -> addBody(sink, skin, PlayerOptions.Type.BUST, options));
            appendArmor(stack, PlayerOptions.Type.BUST, options, engine);
            appendBackLayer(this.parent, stack, options, engine,
                PlayerOptions.Type.BUST.boxOf(HumanoidPart.TORSO));

            Layers.foldInto(stack, options.getGeometryLayerDecorator(), triangles);

            return rasterize3D(engine, triangles, options);
        }

    }

    /**
     * Full-body renderer - all six body parts, in 2D or 3D.
     */
    @RequiredArgsConstructor
    public static final class Full implements Renderer<PlayerOptions> {

        private final @NotNull PlayerRenderer parent;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull PlayerOptions options) {
            if (options.getDimension() == PlayerOptions.Dimension.TWO_D)
                return render2D(this.parent, options);
            return render3D(options);
        }

        private @NotNull ImageData render3D(@NotNull PlayerOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            ModelEngine engine = new ModelEngine(this.parent.context, options.getOutput().getProjection().resolve(options.getOutput().getRotation(), options.getOutput().getFacing()).camera(), PLAYER_FACING);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            stack.append(PlayerSlot3D.BODY, sink -> addBody(sink, skin, PlayerOptions.Type.FULL, options));
            appendArmor(stack, PlayerOptions.Type.FULL, options, engine);
            appendBackLayer(this.parent, stack, options, engine,
                PlayerOptions.Type.FULL.boxOf(HumanoidPart.TORSO));

            Layers.foldInto(stack, options.getGeometryLayerDecorator(), triangles);

            return rasterize3D(engine, triangles, options);
        }

    }

    /**
     * Rasterizes the assembled body + armor triangles to a finished image: auto-fits the silhouette
     * to fill the canvas ({@link #PLAYER_FILL}), applies supersampling (SSAA) and optional FXAA,
     * then composites the armor glint. Shared by all three 3D sub-renderers.
     */
    private static @NotNull ImageData rasterize3D(
        @NotNull ModelEngine engine,
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PlayerOptions options
    ) {
        int size = options.getOutput().getCanvasSize();
        boolean enchanted = options.getArmor().hasEnchanted();
        int ssaa = Math.max(1, options.getOutput().getSupersample());
        // The glint mask is recorded at the raster size, then box-downsampled to the output so the
        // foil is confined to the armor (not the bare body) after the SSAA blit.
        // The caller's rotation is composed into the engine's camera pose at construction (above),
        // so the fitted rasterize applies no separate model-spin - EulerRotation.NONE. Default
        // renders leave the base player pose.
        return Timeline.Static.ZERO.bake(
            RasterPass.of(size, size, ssaa, options.getOutput().isAntiAlias(),
                    (target, tick) -> engine.rasterizeFitted(triangles, target, EulerRotation.NONE, PLAYER_FILL))
                .withMask(enchanted)
                .finishing(GlintKit.Foil.armor(engine.textures()::tryResolveTexture, enchanted)));
    }

    /**
     * Adds every part a scope draws, in that scope's own draw order.
     * <p>
     * The single-part {@link PlayerOptions.Type#SKULL} scope does not route through here: its head is
     * a plain unit cube whose overlay carries its own hardcoded inflation and its own, wider
     * sheet-format test, so folding the two together would change what a legacy skin draws.
     */
    private static void addBody(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer skin,
        @NotNull PlayerOptions.Type type,
        @NotNull PlayerOptions options
    ) {
        for (HumanoidPart part : type.parts())
            addBodyPart(triangles, skin, part, type.boxOf(part), options);
    }

    /**
     * Adds a body part's base skin cube and optional overlay to the triangle list.
     */
    private static void addBodyPart(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer skin,
        @NotNull HumanoidPart part,
        @NotNull Box box,
        @NotNull PlayerOptions options
    ) {
        Vector3f min = new Vector3f(box.minX(), box.minY(), box.minZ());
        Vector3f max = new Vector3f(box.maxX(), box.maxY(), box.maxZ());
        triangles.addAll(BlockGeometryKit.buildBoxTriangles(min, max, part.cropAll(skin, false), ColorMath.WHITE));
        if (options.getSkin().isRenderOverlay() && hasOverlay(skin))
            triangles.addAll(BlockGeometryKit.buildBoxTriangles(
                new Vector3f(min.x() - OVERLAY_INFLATE, min.y() - OVERLAY_INFLATE, min.z() - OVERLAY_INFLATE),
                new Vector3f(max.x() + OVERLAY_INFLATE, max.y() + OVERLAY_INFLATE, max.z() + OVERLAY_INFLATE),
                part.cropAll(skin, true), ColorMath.WHITE));
    }

    /**
     * The worn-armor body-part bounds map for a player scope - each part that scope draws, in the box
     * that scope seats it in. Assembled here once rather than inline in each of the three 3D
     * renderers.
     *
     * @param type the player render scope
     * @return the body-part to box map the armor kit inflates around
     */
    private static @NotNull Map<HumanoidPart, Box> armorBoundsFor(@NotNull PlayerOptions.Type type) {
        Map<HumanoidPart, Box> bounds = new EnumMap<>(HumanoidPart.class);
        for (HumanoidPart part : type.parts()) bounds.put(part, type.boxOf(part));
        return bounds;
    }

    /**
     * Appends the worn-armor layer for a player scope: the scope's {@link #armorBoundsFor bounds map}
     * handed to {@link ArmorKit#buildHumanoidArmor3D} with the four equipped slots. Shared by the
     * SKULL / BUST / FULL 3D renderers so the append and armor call live here once.
     *
     * @param stack the geometry layer stack to append the armor layer to
     * @param type the player render scope
     * @param options the render options carrying the equipped armor
     * @param engine the model engine supplying the texture service
     */
    private static void appendArmor(@NotNull LayerStack<GeometryLayer> stack, @NotNull PlayerOptions.Type type,
                                    @NotNull PlayerOptions options, @NotNull ModelEngine engine) {
        stack.append(PlayerSlot3D.ARMOR, sink -> sink.addAll(ArmorKit.buildHumanoidArmor3D(
            armorBoundsFor(type), options.getArmor().equipped(),
            options.getArmor().getItems(), engine.textures())));
    }

}
