package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.bake.armor.ElytraKit;
import lib.minecraft.renderer.bake.armor.PlayerArmorKit;
import lib.minecraft.renderer.bake.armor.PlayerSprite;
import lib.minecraft.renderer.bake.mesh.PlayerAssembly;
import lib.minecraft.renderer.content.client.SkinFetch;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.camera.View;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.PlayerOptions;
import lib.minecraft.renderer.request.slot.PlayerSlot3D;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import org.jetbrains.annotations.NotNull;

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
 * {@link Rasterizer} with a {@link Projection#VANILLA_ISO} pose, with armor as slightly inflated overlapping geometry.</li>
 * </ul>
 * Skin resolution is shared via the outer class, with URL-fetched skins cached for the
 * renderer's lifetime.
 */
@Parity(claim = "player-geometry", subject = Subject.PLAYER)
public final class PlayerRenderer implements Renderer<PlayerOptions> {

    /**
     * The player's model-to-world facing - a {@code R_Y(180) = diag(-1,1,-1)} yaw flip that turns the
     * humanoid model's {@code +Z} {@code SOUTH} front toward the camera. Applied as a {@link Placement}
     * so the projection stays facing-neutral (see {@link Projection#VANILLA_ISO}): for any projection
     * {@code P}, {@code P.pose() · PLAYER_FACING} presents the front, so the default
     * {@code [30,225,0] · R_Y(180) = [30,45,0]} is the pose a player is lit and rasterized at.
     */
    private static final @NotNull Placement PLAYER_FACING =
        new Placement(Matrix4f.IDENTITY.scale(-1f, 1f, -1f));

    /**
     * The offset from a camera pose to the {@code Lighting.ENTITY_IN_UI} frame that lights geometry
     * presented at it - {@code [pitch + 180, yaw - 180, roll]}, which is the camera's own model-to-view
     * rotation behind vanilla's GUI screen-Y flip. Composing it onto {@link View#lighting()} is what
     * makes the light follow the camera through a caller's rotation and {@code ViewMirror}: both sides are
     * built from the one pose {@link Projection#resolve} reflected, so they cannot come apart. The
     * shipped {@code VANILLA_ISO} camera {@code [30,225,0]} lands on {@code [210,45,0]}, both addends
     * exact.
     * <p>
     * Roll passes through unchanged and is not modelled downstream - {@link Lighting#resolveEntity}
     * builds its chain from pitch and yaw alone - so a rolled render is lit at its unrolled frame.
     */
    private static final @NotNull EulerRotation LIGHT_FRAME_FROM_CAMERA = new EulerRotation(180f, -180f, 0f);

    /**
     * Overlay outset over the head cube in the <b>skull</b> scope's frame, which is a different scale
     * entirely - {@code 0.125} model units per skin pixel against the body lattice's {@code 0.03}.
     * This is {@code 0.16} Minecraft pixels where the body scopes' own overlay outset is {@code 0.67}
     * of one, so the two are not one constant and must not be unified.
     */
    private static final float SKULL_OVERLAY_INFLATE = 0.02f;

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
     * explicit skin bytes &gt; skin URL (fetched via {@link SkinFetch#fetchTexture} and cached for the
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
                byte[] bytes = SkinFetch.fetchTexture(u);
                return parent.imageFactory.fromByteArray(bytes).toPixelBuffer();
            });
        }

        if (options.getSkin().getSkin().getId().isPresent()) {
            String skinId = options.getSkin().getSkin().getId().get();
            return parent.context.resolveTexture(skinId)
                .orElseThrow(() -> new RenderException("No texture registered for id '%s'", skinId));
        }

        return parent.context.resolveTexture("minecraft:entity/steve")
            .orElseThrow(() -> new RenderException("No default Steve skin registered and no skin supplied"));
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
                byte[] bytes = SkinFetch.fetchTexture(url);
                return parent.imageFactory.fromByteArray(bytes).toPixelBuffer();
            }));
        }

        if (options.getSkin().getCape().getId().isPresent()) {
            return parent.context.resolveTexture(options.getSkin().getCape().getId().get());
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
                byte[] bytes = SkinFetch.fetchTexture(url);
                return parent.imageFactory.fromByteArray(bytes).toPixelBuffer();
            }));
        }

        if (options.getSkin().getElytra().getId().isPresent()) {
            return parent.context.resolveTexture(options.getSkin().getElytra().getId().get());
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
        @NotNull Rasterizer engine, @NotNull Box torso
    ) {
        Vector3f torsoMin = new Vector3f(torso.minX(), torso.minY(), torso.minZ());
        Vector3f torsoMax = new Vector3f(torso.maxX(), torso.maxY(), torso.maxZ());
        if (options.getSkin().isRenderElytra()) {
            Optional<PixelBuffer> playerTexture = resolveCape(parent, options).or(() -> resolveElytraSource(parent, options));
            stack.append(PlayerSlot3D.CAPE, sink ->
                sink.addAll(ElytraKit.buildPlayerWings3D(parent.context, torsoMin, torsoMax, playerTexture, Optional.empty(), 0)));
            return;
        }
        resolveCape(parent, options).ifPresent(cape ->
            stack.append(PlayerSlot3D.CAPE, sink -> PlayerAssembly.addCape(sink, cape, torsoMin, torsoMax)));
    }

    // ---------------------------------------------------------------------------------------
    // 2D - the flat front-facing composite.
    // ---------------------------------------------------------------------------------------

    /**
     * Renders a 2D front-facing composite for any body type, over this renderer's resolved skin.
     */
    private static @NotNull ImageData render2D(
        @NotNull PlayerRenderer parent,
        @NotNull PlayerOptions options
    ) {
        return PlayerSprite.render2D(resolveSkin(parent, options), options, parent.context);
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
            View view = playerView(options);
            Rasterizer engine = playerEngine(this.parent, view);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            // The skull scope's head box IS the unit cube: HEAD is 8 px on every axis, so
            // centred(0.125f) is 8 * 0.5f * 0.125f = exactly +-0.5f in binary32, and the overlay's
            // 0.5f + 0.02f is exactly the 0.52f the two literals used to spell. Drawing both from the
            // scope's own box is therefore bit-identical and says where the numbers come from.
            // The gate stays hasHatOverlay, which accepts a legacy 64x32 skin that the body scopes'
            // hasOverlay rejects; unifying the two would delete the hat layer on every such skin.
            Box head = PlayerOptions.Type.SKULL.lattice().boxOf(HumanoidPart.HEAD);
            stack.append(PlayerSlot3D.BODY, sink -> {
                sink.addAll(BoxKit.buildBox(head, HumanoidPart.HEAD.textures(skin, false), ColorMath.WHITE));
                if (options.getSkin().isRenderOverlay() && PlayerAssembly.hasHatOverlay(skin))
                    sink.addAll(BoxKit.buildBox(
                        head.expand(SKULL_OVERLAY_INFLATE),
                        HumanoidPart.HEAD.textures(skin, true), ColorMath.WHITE));
            });
            PlayerArmorKit.appendArmor(stack, PlayerOptions.Type.SKULL, options, this.parent.context);

            Layers.foldInto(stack, options.getGeometryLayerDecorator(), triangles);

            return PlayerAssembly.rasterize3D(
                engine, PlayerAssembly.relight(triangles, playerLighting(view)), options, this.parent.context);
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
            return renderScope3D(this.parent, options, PlayerOptions.Type.BUST);
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
            return renderScope3D(this.parent, options, PlayerOptions.Type.FULL);
        }

    }

    /**
     * Resolves the caller's projection against their model rotation and view facing. One resolution
     * serves both the engine and the lighting, so the camera and the light it is read against are
     * always the same pose.
     */
    private static @NotNull View playerView(@NotNull PlayerOptions options) {
        return options.getOutput().getProjection()
            .resolve(options.getOutput().getRotation(), options.getOutput().getFacing());
    }

    /**
     * Builds the engine every 3D player scope rasterizes through - the resolved camera, placed by
     * {@link #PLAYER_FACING}.
     */
    private static @NotNull Rasterizer playerEngine(@NotNull PlayerRenderer parent, @NotNull View view) {
        return new Rasterizer(view.camera(), PLAYER_FACING);
    }

    /**
     * The {@code Lighting.ENTITY_IN_UI} frame a player presented through {@code view} is lit at -
     * the view's own camera-tracking frame carried onto the light by {@link #LIGHT_FRAME_FROM_CAMERA}.
     */
    private static @NotNull LightingFrame playerLighting(@NotNull View view) {
        return view.lighting().rotated(LIGHT_FRAME_FROM_CAMERA);
    }

    /**
     * Renders the body-plus-armour form of a multi-part scope - the scope's own body parts, its armour,
     * and the back layer seated on its torso box.
     * <p>
     * {@link PlayerOptions.Type#BUST} and {@link PlayerOptions.Type#FULL} differ in nothing but the scope
     * token, which is why it is a parameter here rather than two bodies that have to be kept in step.
     * {@link PlayerOptions.Type#SKULL} deliberately does not route through this: it draws one box with its
     * own wider-gated hat overlay, and it seats no back layer.
     */
    private static @NotNull ImageData renderScope3D(
        @NotNull PlayerRenderer parent,
        @NotNull PlayerOptions options,
        @NotNull PlayerOptions.Type type
    ) {
        PixelBuffer skin = resolveSkin(parent, options);
        View view = playerView(options);
        Rasterizer engine = playerEngine(parent, view);
        return PlayerAssembly.renderScope3D(skin, engine, options, type, stack -> {
                PlayerArmorKit.appendArmor(stack, type, options, parent.context);
                appendBackLayer(parent, stack, options, engine, type.lattice().boxOf(HumanoidPart.TORSO));
            }, playerLighting(view), parent.context);
    }

}
