package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.bake.armor.ElytraKit;
import lib.minecraft.renderer.bake.armor.PlayerArmorKit;
import lib.minecraft.renderer.bake.armor.PlayerSprite;
import lib.minecraft.renderer.bake.mesh.PlayerAssembly;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.slot.PlayerSlot3D;
import lib.minecraft.renderer.content.client.SkinFetch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.camera.View;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

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
 * renderer's lifetime, the {@link FetchedTextures#CAPACITY} most recently read of them.
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

    /**
     * The default wide-arm Steve skin a player supplying no skin source wears.
     */
    private static final @NotNull String STEVE_SKIN_ID = "minecraft:entity/player/wide/steve";

    private final @NotNull RendererContext context;

    /**
     * URL-fetched skin, cape and elytra textures cached for the renderer's lifetime. The renderer each
     * render builds shares this one, so a texture is fetched once per renderer a caller holds, for as
     * long as the cache keeps it.
     */
    private final @NotNull FetchedTextures skinCache;

    private final @NotNull Skull skull;
    private final @NotNull Bust bust;
    private final @NotNull Full full;

    /**
     * Constructs a player renderer over the given context, wiring up the three per-type sub-renderers.
     *
     * @param context renderer context for texture resolution and engine setup
     */
    public PlayerRenderer(@NotNull RendererContext context) {
        this(context, SkinFetch::fetchTexture);
    }

    /**
     * Constructs a player renderer over the given context that reads a URL-sourced texture through the
     * given fetch, so a test draws a URL skin without reaching the texture server.
     *
     * @param context renderer context for texture resolution and engine setup
     * @param fetch reads the PNG bytes a texture URL serves
     */
    PlayerRenderer(@NotNull RendererContext context, @NotNull Function<String, byte[]> fetch) {
        this(context, new FetchedTextures(fetch));
    }

    /**
     * Constructs a player renderer over the given context that caches fetched textures in the given
     * cache, so the renderer a render builds shares it with the one the caller holds.
     *
     * @param context renderer context for texture resolution and engine setup
     * @param skinCache the cache of URL-fetched textures
     */
    private PlayerRenderer(@NotNull RendererContext context, @NotNull FetchedTextures skinCache) {
        this.context = context;
        this.skinCache = skinCache;
        this.skull = new Skull(this);
        this.bust = new Bust(this);
        this.full = new Full(this);
    }

    /**
     * Dispatches on {@link PlayerOptions#getType()} to the matching sub-renderer, then composites the
     * result over the caller's background. The draw runs on a renderer built over a context recording
     * every stand-in it draws, sharing this renderer's skin cache.
     *
     * @param options the player options
     * @return the rendered player composited over the caller's background, and every stand-in drawn in it
     */
    @Override
    public @NotNull RenderResult render(@NotNull PlayerOptions options) {
        return this.context.record(context -> new PlayerRenderer(context, this.skinCache).draw(options));
    }

    /**
     * Draws the image {@link #render} answers, through this renderer's own context.
     *
     * @param options the player options
     * @return the rendered player composited over the caller's background
     */
    @NotNull ImageData draw(@NotNull PlayerOptions options) {
        ImageData rendered = switch (options.getType()) {
            case SKULL -> this.skull.draw(options);
            case BUST -> this.bust.draw(options);
            case FULL -> this.full.draw(options);
        };
        return options.getBackground().composite(rendered);
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers.
    // ---------------------------------------------------------------------------------------

    /**
     * The context a render's textures resolve against - the owning renderer's context behind its
     * missing-texture wrapper, which answers a texture no pack supplies, or that cannot be read, with
     * the checkerboard.
     *
     * @param parent the owning renderer, for its context
     * @return the context every texture the render reads resolves against
     */
    private static @NotNull RendererContext textures(@NotNull PlayerRenderer parent) {
        return parent.context.withMissingTexture();
    }

    /**
     * Resolves the player skin by priority from the {@link PlayerOptions#getSkin() skin} sources:
     * explicit skin bytes &gt; skin URL (fetched via {@link SkinFetch#fetchTexture} and cached under the
     * texture hash it names, see {@link FetchedTextures}) &gt; skin texture id (resolved against the pack stack) &gt; the default
     * wide-arm Steve skin, {@code minecraft:entity/player/wide/steve}. A skin read by id, the default
     * included, that no pack supplies or that cannot be read is the checkerboard. Every part reads
     * its faces as regions of the whole skin sheet, normalised by the layout the skin declares, so the
     * checkerboard lands where vanilla's normalised UVs land on it.
     *
     * @param parent the owning renderer, for its image factory / skin cache / context
     * @param options the render options
     * @return the resolved skin, with the sampler it is read through - its sidecar's for one read by id,
     *     nearest and repeating for one from bytes or a URL, which carries no sidecar
     */
    static @NotNull SampledTexture resolveSkin(@NotNull PlayerRenderer parent, @NotNull PlayerOptions options) {
        if (options.getSkin().getSkin().getBytes().isPresent())
            return SampledTexture.of(ImageFactory.fromByteArray(options.getSkin().getSkin().getBytes().get()).toPixelBuffer());

        if (options.getSkin().getSkin().getUrl().isPresent()) {
            String url = options.getSkin().getSkin().getUrl().get();
            return SampledTexture.of(parent.skinCache.read("skin", url));
        }

        String skinId = options.getSkin().getSkin().getId().orElse(STEVE_SKIN_ID);
        return textures(parent).resolvePlainTexture(skinId).get();
    }

    /**
     * Resolves the cape texture using the same priority chain as skins. Returns empty when
     * {@code renderCape} is false or no texture source is supplied. A cape named by id that no pack
     * supplies, or that cannot be read, is the checkerboard - never passed over for the elytra source.
     *
     * @param parent the owning renderer, for its image factory / skin cache / context
     * @param options the render options
     * @return the cape texture, with the sampler it is read through - its sidecar's for one read by id,
     *     nearest and repeating for one from bytes or a URL, which carries no sidecar - or empty when the
     *     player wears no cape
     */
    static @NotNull Optional<SampledTexture> resolveCape(@NotNull PlayerRenderer parent, @NotNull PlayerOptions options) {
        if (!options.getSkin().isRenderCape()) return Optional.empty();

        if (options.getSkin().getCape().getBytes().isPresent())
            return Optional.of(SampledTexture.of(ImageFactory.fromByteArray(options.getSkin().getCape().getBytes().get()).toPixelBuffer()));

        if (options.getSkin().getCape().getUrl().isPresent()) {
            String url = options.getSkin().getCape().getUrl().get();
            return Optional.of(SampledTexture.of(parent.skinCache.read("cape", url)));
        }

        if (options.getSkin().getCape().getId().isPresent()) {
            String capeId = options.getSkin().getCape().getId().get();
            return Optional.of(textures(parent).resolvePlainTexture(capeId).get());
        }

        return Optional.empty();
    }

    /**
     * Resolves the caller-supplied elytra wing texture ({@code SkinOptions.elytra}) using the same
     * source priority chain as the cape, or empty when it supplies no source - the wings then take the
     * static elytra skin wherever no cape is worn either. A source named by id that no pack supplies, or
     * that cannot be read, is the checkerboard.
     *
     * @param parent the owning renderer, for its image factory / skin cache / context
     * @param options the render options
     * @return the elytra source texture, with the sampler it is read through - its sidecar's for one read
     *     by id, nearest and repeating for one from bytes or a URL, which carries no sidecar - or empty when
     *     the skin options supply none
     */
    static @NotNull Optional<SampledTexture> resolveElytraSource(@NotNull PlayerRenderer parent, @NotNull PlayerOptions options) {
        if (options.getSkin().getElytra().getBytes().isPresent())
            return Optional.of(SampledTexture.of(ImageFactory.fromByteArray(options.getSkin().getElytra().getBytes().get()).toPixelBuffer()));

        if (options.getSkin().getElytra().getUrl().isPresent()) {
            String url = options.getSkin().getElytra().getUrl().get();
            return Optional.of(SampledTexture.of(parent.skinCache.read("elytra", url)));
        }

        if (options.getSkin().getElytra().getId().isPresent()) {
            String elytraId = options.getSkin().getElytra().getId().get();
            return Optional.of(textures(parent).resolvePlainTexture(elytraId).get());
        }

        return Optional.empty();
    }

    /**
     * Appends the back layer for a 3D player scope: the elytra wings when {@code renderElytra}, else the
     * flat cape when {@code renderCape}. An equipped elytra supersedes the cape (matching vanilla) and
     * draws the wearer's cape texture when one is worn - vanilla's {@code use_player_texture}, so a caped
     * player's elytra shows the cape design - degrading to a caller-supplied or static elytra skin where
     * none is. A cape named by id is worn whether or not a pack supplies it, so one no pack ships draws
     * the checkerboard on the cape and on the wings.
     */
    private static void appendBackLayer(
        @NotNull PlayerRenderer parent, @NotNull RendererContext textures, @NotNull LayerStack<GeometryLayer> stack,
        @NotNull PlayerOptions options, @NotNull Rasterizer engine, @NotNull Box torso
    ) {
        Vector3f torsoMin = new Vector3f(torso.minX(), torso.minY(), torso.minZ());
        Vector3f torsoMax = new Vector3f(torso.maxX(), torso.maxY(), torso.maxZ());
        if (options.getSkin().isRenderElytra()) {
            Optional<SampledTexture> playerTexture = resolveCape(parent, options).or(() -> resolveElytraSource(parent, options));
            stack.append(PlayerSlot3D.CAPE, sink ->
                sink.addAll(ElytraKit.buildPlayerWings3D(textures, torsoMin, torsoMax, playerTexture, Optional.empty(), 0)));
            return;
        }
        resolveCape(parent, options).ifPresent(cape ->
            stack.append(PlayerSlot3D.CAPE, sink -> PlayerAssembly.addCape(sink, cape, torsoMin, torsoMax)));
    }

    // ---------------------------------------------------------------------------------------
    // 2D - the flat front-facing composite.
    // ---------------------------------------------------------------------------------------

    /**
     * Renders a 2D front-facing composite for any body type, over this renderer's resolved skin. The
     * composite crops its parts out of the skin, so the stand-in sprite is laid across the square
     * skin sheet first, landing where vanilla's normalised UVs land on its own.
     */
    private static @NotNull ImageData render2D(
        @NotNull PlayerRenderer parent,
        @NotNull PlayerOptions options
    ) {
        PixelBuffer skin = MissingSprite.stretchedTo(resolveSkin(parent, options).pixels(),
            HumanoidPart.SKIN_SIZE, HumanoidPart.SKIN_SIZE);
        return PlayerSprite.render2D(skin, options, textures(parent));
    }


    // ---------------------------------------------------------------------------------------
    // Sub-renderers.
    // ---------------------------------------------------------------------------------------

    /**
     * Skull renderer - head only, in 2D or 3D.
     */
    @RequiredArgsConstructor
    public static final class Skull {

        private final @NotNull PlayerRenderer parent;

        /**
         * Draws the head, flat or posed as the options' dimension asks.
         *
         * @param options the player options
         * @return the rendered head, before the background composite
         */
        @NotNull ImageData draw(@NotNull PlayerOptions options) {
            if (options.getDimension() == PlayerOptions.Dimension.TWO_D)
                return render2D(this.parent, options);
            return render3D(options);
        }

        private @NotNull ImageData render3D(@NotNull PlayerOptions options) {
            SampledTexture skin = resolveSkin(this.parent, options);
            View view = playerView(options);
            Rasterizer engine = playerEngine(this.parent, view);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            LayerStack<GeometryLayer> stack = new LayerStack<>();
            // The skull scope's head box IS the unit cube: HEAD is 8 px on every axis, so
            // centred(0.125f) is 8 * 0.5f * 0.125f = exactly +-0.5f in binary32, and the overlay's
            // 0.5f + 0.02f is exactly the 0.52f the two literals used to spell. Drawing both from the
            // scope's own box is therefore bit-identical and says where the numbers come from.
            // The hat takes no layout gate: both skin layouts carry it, the legacy one included, where
            // the body scopes' hasOverlay gate decides the five overlays only the square layout holds.
            Box head = PlayerOptions.Type.SKULL.lattice().boxOf(HumanoidPart.HEAD);
            stack.append(PlayerSlot3D.BODY, sink -> {
                sink.addAll(BoxKit.buildBox(head,
                    HumanoidPart.HEAD.skinTextures(skin, false), ColorMath.WHITE));
                if (options.getSkin().isRenderOverlay())
                    sink.addAll(BoxKit.buildBox(
                        head.expand(SKULL_OVERLAY_INFLATE),
                        HumanoidPart.HEAD.skinTextures(skin, true), ColorMath.WHITE));
            });
            RendererContext textures = textures(this.parent);
            PlayerArmorKit.appendArmor(stack, PlayerOptions.Type.SKULL, options, textures);

            Layers.foldInto(stack, options.getGeometryLayerDecorator(), triangles);

            return PlayerAssembly.rasterize3D(
                engine, PlayerAssembly.relight(triangles, playerLighting(view)), options, textures);
        }

    }

    /**
     * Bust renderer - head, torso and arms, in 2D or 3D.
     */
    @RequiredArgsConstructor
    public static final class Bust {

        private final @NotNull PlayerRenderer parent;

        /**
         * Draws the head, torso and arms, flat or posed as the options' dimension asks.
         *
         * @param options the player options
         * @return the rendered bust, before the background composite
         */
        @NotNull ImageData draw(@NotNull PlayerOptions options) {
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
    public static final class Full {

        private final @NotNull PlayerRenderer parent;

        /**
         * Draws all six body parts, flat or posed as the options' dimension asks.
         *
         * @param options the player options
         * @return the rendered body, before the background composite
         */
        @NotNull ImageData draw(@NotNull PlayerOptions options) {
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
     * own hat overlay, drawn over either skin layout, and it seats no back layer.
     */
    private static @NotNull ImageData renderScope3D(
        @NotNull PlayerRenderer parent,
        @NotNull PlayerOptions options,
        @NotNull PlayerOptions.Type type
    ) {
        SampledTexture skin = resolveSkin(parent, options);
        View view = playerView(options);
        Rasterizer engine = playerEngine(parent, view);
        RendererContext textures = textures(parent);
        return PlayerAssembly.renderScope3D(skin, engine, options, type, stack -> {
                PlayerArmorKit.appendArmor(stack, type, options, textures);
                appendBackLayer(parent, textures, stack, options, engine, type.lattice().boxOf(HumanoidPart.TORSO));
            }, playerLighting(view), textures);
    }

    // ---------------------------------------------------------------------------------------
    // Fetched textures.
    // ---------------------------------------------------------------------------------------

    /**
     * The textures a renderer has fetched by URL, each kept under what was fetched rather than under how
     * the request spelled it: the kind of sheet it is read as and the texture hash its URL names, which
     * is all of the URL {@link SkinFetch#fetchTexture} reads. However many ways a request spells the URL
     * of one texture, it is fetched and held once. The kind keeps a skin, a cape and an elytra sheet
     * naming one hash apart.
     * <p>
     * The cache holds the {@link #CAPACITY} sheets read most recently and lets the one read longest ago
     * go for each one past that, so what a renderer holds does not grow with the number of textures it
     * has been asked for. A sheet let go is fetched again when it is next asked for.
     * <p>
     * A sheet is fetched outside the cache's lock, by the first render to ask for it; a render asking
     * for the same sheet meanwhile waits for that fetch instead of making its own, and one asking for
     * another sheet is not held up. A fetch that fails is not kept, so the next render asks again.
     */
    static final class FetchedTextures {

        /**
         * The most sheets the cache holds. A skin is 64 by 64 pixels, so a full cache of them is about
         * 16 MiB.
         */
        static final int CAPACITY = 1024;

        /** Reads the PNG bytes a texture URL serves. */
        private final @NotNull Function<String, byte[]> fetch;

        /** The sheets held, least recently read first, each as the fetch that answers it. */
        private final @NotNull Map<String, CompletableFuture<PixelBuffer>> sheets = new LinkedHashMap<>(16, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<PixelBuffer>> eldest) {
                return size() > CAPACITY;
            }

        };

        /**
         * Constructs an empty cache reading through the given fetch.
         *
         * @param fetch reads the PNG bytes a texture URL serves
         */
        FetchedTextures(@NotNull Function<String, byte[]> fetch) {
            this.fetch = fetch;
        }

        /**
         * Answers the sheet a URL names, fetching and decoding it where the cache does not hold it.
         *
         * @param kind the kind of sheet the texture is read as - {@code skin}, {@code cape} or
         *     {@code elytra}
         * @param url the texture URL
         * @return the decoded sheet
         */
        @NotNull PixelBuffer read(@NotNull String kind, @NotNull String url) {
            String key = kind + ':' + SkinFetch.textureHash(url);
            CompletableFuture<PixelBuffer> sheet;
            boolean fetching = false;

            synchronized (this.sheets) {
                sheet = this.sheets.get(key);
                if (sheet == null) {
                    sheet = new CompletableFuture<>();
                    this.sheets.put(key, sheet);
                    fetching = true;
                }
            }

            if (fetching) {
                try {
                    sheet.complete(ImageFactory.fromByteArray(this.fetch.apply(url)).toPixelBuffer());
                } catch (Throwable failure) {
                    synchronized (this.sheets) {
                        this.sheets.remove(key, sheet);
                    }

                    sheet.completeExceptionally(failure);
                    throw failure;
                }
            }

            try {
                return sheet.join();
            } catch (CompletionException failed) {
                if (failed.getCause() instanceof RuntimeException cause) throw cause;
                throw failed;
            }
        }

    }

}
