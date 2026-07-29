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
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
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
import lib.minecraft.renderer.face.FaceTextures;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.option.PlayerOptions.Type.BodyPart2D;
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
import java.util.List;
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
     * Overlay (hat / hood / second layer) outset over the base cube, in the body scopes' frame.
     */
    private static final float OVERLAY_INFLATE = 0.01f;

    /**
     * Overlay outset over the head cube in the <b>skull</b> scope's frame, which is a different scale
     * entirely - {@code 0.125} model units per skin pixel against the body lattice's {@code 0.03}.
     * This is {@code 0.16} Minecraft pixels where {@link #OVERLAY_INFLATE} is {@code 0.67} of one, so
     * the two are not one constant and must not be unified.
     */
    private static final float SKULL_OVERLAY_INFLATE = 0.02f;

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
     * Reads each face of the cape cube out of a cape texture, through the cube's own atlas unwrap in
     * the {@link #CAPE_FRAME cape frame}. The cape model is a 10x16x1 box at UV origin (0,0), so the
     * vanilla cube unwrap lays it out as:
     * <pre>
     * y=0:  [1px edge][10px BOTTOM][1px edge][10px TOP]
     * y=1:  [1px WEST][10px NORTH ][1px EAST][10px SOUTH]  (16 rows)
     * </pre>
     * The {@code NORTH} region ({@code x 1..10}) carries the visible cape design and the {@code SOUTH}
     * region ({@code x 12..21}) the plain lining. The cape hangs on the player's back - its {@code -Z}
     * / {@link Face#NORTH NORTH} face points outward, away from the body - so the design lands
     * outward and the lining against the back.
     */
    private static @NotNull FaceTextures capeTextures(@NotNull PixelBuffer cape) {
        Unwrap.Atlas unwrap = new Unwrap.Atlas(CAPE_UV, CAPE_SIZE, false);
        return face -> unwrap.crop(cape, CAPE_FRAME.apply(face));
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

        Box cape = new Box(cx - capeW / 2f, capeTop - capeH, capeBack - capeD, cx + capeW / 2f, capeTop, capeBack);

        triangles.addAll(BlockGeometryKit.buildBox(cape, capeTextures(capeTexture), ColorMath.WHITE));
    }

    // ---------------------------------------------------------------------------------------
    // 2D helpers - composite front-facing body parts + armor onto a canvas.
    // ---------------------------------------------------------------------------------------

    /**
     * Blits one already-cropped face into the canvas rectangle its layout row names.
     */
    private static void blitPart(
        @NotNull PixelBuffer frame, @NotNull BodyPart2D row, @NotNull PixelBuffer face) {
        frame.blitScaled(face, row.x(), row.y(), row.w(), row.h());
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

        List<BodyPart2D> parts = options.getType().layout2D(size);

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
                    for (BodyPart2D row : parts)
                        blitPart(frame, row, row.part().crop(skin, Face.SOUTH, false));
                });
                if (overlay)
                    stack.append(PlayerSlot2D.OVERLAY, frame -> {
                        // The head's hat layer is the one overlay a legacy sheet still carries, and it
                        // is drawn from the same rectangle at the same crop - so the wider test only
                        // decides whether the head is reached, never what it draws.
                        for (BodyPart2D row : parts)
                            if (hasOverlay(skin)
                                || (row.part() == HumanoidPart.HEAD && hasHatOverlay(skin)))
                                blitPart(frame, row, row.part().crop(skin, Face.SOUTH, true));
                    });
                stack.append(PlayerSlot2D.ARMOR, frame -> compositeArmor2D(frame, parts, options, engine));
                Layers.foldInto(stack, options.getLayerDecorator(), target);
            })
                .withMask(enchanted)
                .finishing(GlintKit.Foil.armor(engine.textures()::tryResolveTexture, enchanted)));
    }

    /**
     * Composites the whole 2D armour pass - every equipped slot over every body part that slot covers,
     * in {@link ArmorSlot} declaration order.
     *
     * <p><b>The slot is the outer loop, and that is what makes the composite order unconditional.</b>
     * Iterating parts outermost also paints correctly, but only because the six part rectangles tile the
     * canvas without overlap: all fifteen pairs are disjoint, the head sitting above the torso and arms
     * on Y and the two legs beside each other on X. With the slot outermost a later slot paints over an
     * earlier one whatever the rectangles do, which is the contract {@link ArmorSlot}'s declaration
     * order states - layer-2 leggings first, so the chestplate wins on the torso and the boots on the
     * lower legs.
     *
     * <p>{@code equipped()} holds only worn pieces and iterates its {@code EnumMap} in ordinal - so
     * declaration - order, so no slot is tested for absence and none is drawn out of turn.
     */
    private static void compositeArmor2D(
        @NotNull PixelBuffer target,
        @NotNull List<BodyPart2D> parts,
        @NotNull PlayerOptions options,
        @NotNull RasterEngine engine
    ) {
        for (Map.Entry<ArmorSlot, ArmorPiece> entry : options.getArmor().equipped().entrySet()) {
            ArmorSlot slot = entry.getKey();
            Optional<ItemContext> item = Optional.ofNullable(options.getArmor().getItems().get(slot));

            for (BodyPart2D row : parts)
                if (ArmorForm.playerSlots(row.part()).contains(slot))
                    ArmorKit.compositeSlot2D(target, row, slot, entry.getValue(), item, engine.textures());
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
            // The skull scope's head box IS the unit cube: HEAD is 8 px on every axis, so
            // centred(0.125f) is 8 * 0.5f * 0.125f = exactly +-0.5f in binary32, and the overlay's
            // 0.5f + 0.02f is exactly the 0.52f the two literals used to spell. Drawing both from the
            // scope's own box is therefore bit-identical and says where the numbers come from.
            // The gate stays hasHatOverlay, which accepts a legacy 64x32 skin that the body scopes'
            // hasOverlay rejects; unifying the two would delete the hat layer on every such skin.
            Box head = PlayerOptions.Type.SKULL.boxOf(HumanoidPart.HEAD);
            stack.append(PlayerSlot3D.BODY, sink -> {
                sink.addAll(BlockGeometryKit.buildBox(head, HumanoidPart.HEAD.textures(skin, false), ColorMath.WHITE));
                if (options.getSkin().isRenderOverlay() && hasHatOverlay(skin))
                    sink.addAll(BlockGeometryKit.buildBox(
                        head.expand(SKULL_OVERLAY_INFLATE),
                        HumanoidPart.HEAD.textures(skin, true), ColorMath.WHITE));
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
        triangles.addAll(BlockGeometryKit.buildBox(box, part.textures(skin, false), ColorMath.WHITE));
        if (options.getSkin().isRenderOverlay() && hasOverlay(skin))
            triangles.addAll(BlockGeometryKit.buildBox(
                box.expand(OVERLAY_INFLATE), part.textures(skin, true), ColorMath.WHITE));
    }

    /**
     * Appends the worn-armor layer for a player scope: the scope's own
     * {@link PlayerOptions.Type#boxes() part boxes} handed to {@link ArmorKit#buildHumanoidArmor3D}
     * with the four equipped slots. Shared by the SKULL / BUST / FULL 3D renderers so the append and
     * armor call live here once.
     *
     * @param stack the geometry layer stack to append the armor layer to
     * @param type the player render scope
     * @param options the render options carrying the equipped armor
     * @param engine the model engine supplying the texture service
     */
    private static void appendArmor(@NotNull LayerStack<GeometryLayer> stack, @NotNull PlayerOptions.Type type,
                                    @NotNull PlayerOptions options, @NotNull ModelEngine engine) {
        stack.append(PlayerSlot3D.ARMOR, sink -> sink.addAll(ArmorKit.buildHumanoidArmor3D(
            type.boxes(), options.getArmor().equipped(),
            options.getArmor().getItems(), engine.textures())));
    }

}
