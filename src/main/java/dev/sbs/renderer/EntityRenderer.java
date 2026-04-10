package dev.sbs.renderer;

import dev.sbs.renderer.draw.BlockFace;
import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.draw.GeometryKit;
import dev.sbs.renderer.draw.SkinFace;
import dev.sbs.renderer.engine.IsometricEngine;
import dev.sbs.renderer.engine.ModelEngine;
import dev.sbs.renderer.engine.PerspectiveParams;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.VisibleTriangle;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.math.Matrix4f;
import dev.sbs.renderer.math.Vector2f;
import dev.sbs.renderer.math.Vector3f;
import dev.sbs.renderer.model.Entity;
import dev.sbs.renderer.model.asset.EntityModelData;
import dev.sbs.renderer.options.EntityOptions;
import dev.sbs.renderer.pipeline.HttpFetcher;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.PixelBuffer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Renders player skins and generic entities by dispatching to one of five sub-renderers based on
 * {@link EntityOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;EntityOptions&gt;}:
 * <ul>
 * <li>{@link PlayerFace2D} - 2D crop of the skin's head front face.</li>
 * <li>{@link PlayerSimple2D} - placeholder for {@code PLAYER_PROFILE_2D} and
 * {@code PLAYER_BUST_2D}; currently delegates to {@link PlayerFace2D} until body/arm/leg
 * compositing is wired up.</li>
 * <li>{@link PlayerSkull} - 3D isometric head cube with optional hat overlay.</li>
 * <li>{@link PlayerBust3D} - head + torso boxes rendered via {@link IsometricEngine}.</li>
 * <li>{@link Entity3D} - generic entity model rasterization via {@link ModelEngine}.</li>
 * </ul>
 * Skin resolution lives on the outer class so URL-fetched skins are cached in a single shared
 * map for the lifetime of the renderer instance, regardless of which sub-renderer is called.
 * The cache, HTTP fetcher, and image factory are exposed package-private via {@link Getter} so
 * the inner classes can reach them for their own texture resolution paths.
 */
public final class EntityRenderer implements Renderer<EntityOptions> {

    @Getter(lombok.AccessLevel.PACKAGE) private final @NotNull RendererContext context;
    @Getter(lombok.AccessLevel.PACKAGE) private final @NotNull HttpFetcher fetcher = new HttpFetcher();
    @Getter(lombok.AccessLevel.PACKAGE) private final @NotNull ImageFactory imageFactory = new ImageFactory();
    @Getter(lombok.AccessLevel.PACKAGE) private final @NotNull ConcurrentMap<String, PixelBuffer> skinCache = Concurrent.newMap();

    private final @NotNull PlayerFace2D playerFace2D;
    private final @NotNull PlayerSimple2D playerSimple2D;
    private final @NotNull PlayerSkull playerSkull;
    private final @NotNull PlayerBust3D playerBust3D;
    private final @NotNull Entity3D entity3D;

    public EntityRenderer(@NotNull RendererContext context) {
        this.context = context;
        this.playerFace2D = new PlayerFace2D(this);
        this.playerSimple2D = new PlayerSimple2D(this);
        this.playerSkull = new PlayerSkull(this);
        this.playerBust3D = new PlayerBust3D(this);
        this.entity3D = new Entity3D(this);
    }

    @Override
    public @NotNull ImageData render(@NotNull EntityOptions options) {
        return switch (options.getType()) {
            case PLAYER_FACE_2D -> this.playerFace2D.render(options);
            case PLAYER_PROFILE_2D, PLAYER_BUST_2D -> this.playerSimple2D.render(options);
            case PLAYER_SKULL -> this.playerSkull.render(options);
            case PLAYER_BUST_3D -> this.playerBust3D.render(options);
            case ENTITY_3D -> this.entity3D.render(options);
        };
    }

    /**
     * Resolves the raw skin bytes for a player-type render using the caller's four-way priority
     * chain: raw bytes, absolute URL (fetched via {@link HttpFetcher}), pack-resolved texture
     * id, bundled default Steve. URL-fetched skins are cached in the owning renderer's
     * {@link #skinCache} for the lifetime of the instance so repeated renders of the same
     * player do not hammer Mojang's CDN.
     */
    static @NotNull PixelBuffer resolveSkin(@NotNull EntityRenderer parent, @NotNull EntityOptions options) {
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

    /**
     * 2D player face renderer. Crops the south-facing head region from the skin texture and
     * scales it to the requested output size, optionally overlaying the second-layer hat when
     * the skin dimensions permit.
     */
    @RequiredArgsConstructor
    public static final class PlayerFace2D implements Renderer<EntityOptions> {

        private final @NotNull EntityRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull EntityOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            RasterEngine engine = new RasterEngine(this.parent.context);
            Canvas canvas = engine.createCanvas(options.getOutputSize(), options.getOutputSize());

            PixelBuffer face = SkinFace.HEAD.crop(skin, BlockFace.SOUTH, false);
            canvas.blitScaled(face, 0, 0, options.getOutputSize(), options.getOutputSize());

            if (options.isRenderHat() && skin.getWidth() >= 48 && skin.getHeight() >= 16) {
                PixelBuffer hat = SkinFace.HEAD.crop(skin, BlockFace.SOUTH, true);
                canvas.blitScaled(hat, 0, 0, options.getOutputSize(), options.getOutputSize());
            }

            if (options.isAntiAlias())
                canvas.getBuffer().applyFxaa();

            return RenderEngine.staticFrame(canvas);
        }

    }

    /**
     * Placeholder 2D renderer for the profile and bust variants. Currently delegates to
     * {@link PlayerFace2D} until full body/arm/leg compositing is wired up. Carries its own
     * class so callers and future work can target it directly when specialising either variant.
     */
    @RequiredArgsConstructor
    public static final class PlayerSimple2D implements Renderer<EntityOptions> {

        private final @NotNull EntityRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull EntityOptions options) {
            return this.parent.playerFace2D.render(options);
        }

    }

    /**
     * 3D isometric player skull renderer. Builds a unit head cube from the skin's base-layer
     * head region, optionally stacks an inflated hat cube from the second layer, and rasterizes
     * through an {@link IsometricEngine} with the caller's pitch/yaw/roll.
     */
    @RequiredArgsConstructor
    public static final class PlayerSkull implements Renderer<EntityOptions> {

        private final @NotNull EntityRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull EntityOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            IsometricEngine engine = new IsometricEngine(this.parent.context);
            Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());

            ConcurrentList<VisibleTriangle> triangles = buildHeadCube(skin, false);
            if (options.isRenderHat() && skin.getWidth() >= 64 && skin.getHeight() >= 16)
                triangles.addAll(buildHeadCube(skin, true));

            engine.rasterize(triangles, canvas, PerspectiveParams.NONE,
                options.getPitch(), options.getYaw(), options.getRoll());

            if (options.isAntiAlias())
                canvas.getBuffer().applyFxaa();

            return RenderEngine.staticFrame(canvas);
        }

        /**
         * Builds a 12-triangle unit cube whose six face textures are cropped from the skin's
         * head region (base layer) or hat overlay region (second layer). Uses
         * {@link SkinFace#HEAD}'s {@code cropAll} to produce the six faces in
         * {@link BlockFace} declaration order.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> buildHeadCube(@NotNull PixelBuffer skin, boolean hatLayer) {
            PixelBuffer[] faces = SkinFace.HEAD.cropAll(skin, hatLayer);
            if (hatLayer) {
                // Hat is slightly inflated so it sits above the base head and has visible edges.
                return GeometryKit.box(
                    new Vector3f(-0.52f, -0.52f, -0.52f),
                    new Vector3f(0.52f, 0.52f, 0.52f),
                    faces,
                    ColorKit.WHITE
                );
            }
            return GeometryKit.unitCube(faces, ColorKit.WHITE);
        }

    }

    /**
     * 3D player bust renderer. Composes a head cube on top of a narrower torso box in a shared
     * model-space bounding box, optionally inflated hat on top, and rasterizes through an
     * {@link IsometricEngine} with the caller's pitch/yaw/roll.
     */
    @RequiredArgsConstructor
    public static final class PlayerBust3D implements Renderer<EntityOptions> {

        private final @NotNull EntityRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull EntityOptions options) {
            PixelBuffer skin = resolveSkin(this.parent, options);
            IsometricEngine engine = new IsometricEngine(this.parent.context);
            Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());

            // Head cube at the top plus a torso box directly underneath, both in the same
            // model-space bounding box so the combined bust fits the standard output size.
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

            triangles.addAll(GeometryKit.box(
                new Vector3f(-0.25f, 0.1f, -0.25f),
                new Vector3f(0.25f, 0.6f, 0.25f),
                SkinFace.HEAD.cropAll(skin, false),
                ColorKit.WHITE
            ));

            if (options.isRenderHat() && skin.getWidth() >= 64 && skin.getHeight() >= 16) {
                triangles.addAll(GeometryKit.box(
                    new Vector3f(-0.26f, 0.09f, -0.26f),
                    new Vector3f(0.26f, 0.61f, 0.26f),
                    SkinFace.HEAD.cropAll(skin, true),
                    ColorKit.WHITE
                ));
            }

            triangles.addAll(GeometryKit.box(
                new Vector3f(-0.2f, -0.4f, -0.1f),
                new Vector3f(0.2f, 0.1f, 0.1f),
                SkinFace.TORSO.cropAll(skin, false),
                ColorKit.WHITE
            ));

            engine.rasterize(triangles, canvas, PerspectiveParams.NONE,
                options.getPitch(), options.getYaw(), options.getRoll());

            if (options.isAntiAlias())
                canvas.getBuffer().applyFxaa();

            return RenderEngine.staticFrame(canvas);
        }

    }

    /**
     * Generic entity 3D renderer. Resolves the entity from the renderer context, loads its
     * texture through the options override chain, walks its
     * {@link EntityModelData#getBones() bone/cube} tree to build a triangle list, and
     * rasterizes through a {@link ModelEngine} with a GUI-item perspective. Short-circuits to a
     * blank canvas when any of the lookups fail.
     */
    @RequiredArgsConstructor
    public static final class Entity3D implements Renderer<EntityOptions> {

        private final @NotNull EntityRenderer parent;

        @Override
        public @NotNull ImageData render(@NotNull EntityOptions options) {
            Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());

            if (options.getEntityId().isEmpty())
                return RenderEngine.staticFrame(canvas);

            Optional<Entity> entityLookup = this.parent.context.findEntity(options.getEntityId().get());
            if (entityLookup.isEmpty())
                return RenderEngine.staticFrame(canvas);

            Entity entity = entityLookup.get();
            Optional<PixelBuffer> texture = resolveEntityTexture(entity, options);
            if (texture.isEmpty())
                return RenderEngine.staticFrame(canvas);

            EntityModelData model = entity.getModel();
            if (model.getBones().isEmpty())
                return RenderEngine.staticFrame(canvas);

            ConcurrentList<VisibleTriangle> triangles = buildEntityTriangles(model, texture.get());
            if (triangles.isEmpty())
                return RenderEngine.staticFrame(canvas);

            ModelEngine engine = new ModelEngine(this.parent.context);
            engine.rasterize(triangles, canvas, PerspectiveParams.GUI_ITEM,
                options.getPitch(), options.getYaw(), options.getRoll());

            if (options.isAntiAlias())
                canvas.getBuffer().applyFxaa();

            return RenderEngine.staticFrame(canvas);
        }

        /**
         * Resolves the texture for a non-player entity. Options overrides (raw bytes, URL,
         * texture id) take priority; otherwise the entity's own {@code textureId} is resolved
         * through the active pack stack. Returns empty when no source is available.
         */
        private @NotNull Optional<PixelBuffer> resolveEntityTexture(
            @NotNull Entity entity,
            @NotNull EntityOptions options
        ) {
            if (options.getSkinBytes().isPresent())
                return Optional.of(PixelBuffer.wrap(this.parent.imageFactory.fromByteArray(options.getSkinBytes().get()).toBufferedImage()));

            if (options.getSkinUrl().isPresent()) {
                String url = options.getSkinUrl().get();
                PixelBuffer cached = this.parent.skinCache.get(url);
                if (cached != null) return Optional.of(cached);

                byte[] bytes = this.parent.fetcher.get(url);
                PixelBuffer buffer = PixelBuffer.wrap(this.parent.imageFactory.fromByteArray(bytes).toBufferedImage());
                this.parent.skinCache.put(url, buffer);
                return Optional.of(buffer);
            }

            if (options.getSkinTextureId().isPresent())
                return this.parent.context.resolveTexture(options.getSkinTextureId().get());

            if (entity.getTextureId().isPresent())
                return this.parent.context.resolveTexture(entity.getTextureId().get());

            return Optional.empty();
        }

        /**
         * Walks the model's bones and cubes, emitting two triangles per face of every cube
         * after applying the owning bone's pivot rotation and normalizing the whole model into
         * the engine's {@code [-0.5, 0.5]} unit box. The texture is shared across every
         * triangle.
         * <p>
         * When {@link EntityModelData#isNegateY()} is set, the Y coordinate of every transformed
         * vertex and of the face normal is mirrored about the XZ plane before centring, and
         * each quad's two triangles are emitted with their winding reversed so back-face
         * culling in {@link dev.sbs.renderer.engine.ModelEngine ModelEngine} still matches the
         * visible face. See {@link EntityModelData} for the motivation behind the flag.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> buildEntityTriangles(
            @NotNull EntityModelData model,
            @NotNull PixelBuffer texture
        ) {
            boolean negateY = model.isNegateY();
            ModelBounds bounds = computeModelBounds(model);
            float extent = Math.max(bounds.maxExtent(), 0.001f);
            float scale = 0.9f / extent;
            float cx = (bounds.minX + bounds.maxX) * 0.5f;
            float cy = (bounds.minY + bounds.maxY) * 0.5f;
            float cz = (bounds.minZ + bounds.maxZ) * 0.5f;

            float texW = Math.max(1f, model.getTextureWidth());
            float texH = Math.max(1f, model.getTextureHeight());

            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            int priority = 0;

            for (EntityModelData.Bone bone : model.getBones().values()) {
                Matrix4f boneTransform = buildBoneTransform(bone);

                for (EntityModelData.Cube cube : bone.getCubes()) {
                    float[] origin = cube.getOrigin();
                    float[] size = cube.getSize();
                    float inflate = cube.getInflate();

                    float x0 = origin[0] - inflate;
                    float y0 = origin[1] - inflate;
                    float z0 = origin[2] - inflate;
                    float x1 = origin[0] + size[0] + inflate;
                    float y1 = origin[1] + size[1] + inflate;
                    float z1 = origin[2] + size[2] + inflate;

                    for (BlockFace face : BlockFace.values()) {
                        Vector3f[] corners = face.corners(x0, y0, z0, x1, y1, z1);
                        for (int i = 0; i < 4; i++) {
                            Vector3f transformed = Vector3f.transform(corners[i], boneTransform);
                            float ty = negateY ? -transformed.getY() : transformed.getY();
                            corners[i] = new Vector3f(
                                (transformed.getX() - cx) * scale,
                                (ty - cy) * scale,
                                (transformed.getZ() - cz) * scale
                            );
                        }

                        Vector3f rawNormal = Vector3f.transformNormal(face.normal(), boneTransform);
                        if (negateY)
                            rawNormal = new Vector3f(rawNormal.getX(), -rawNormal.getY(), rawNormal.getZ());
                        Vector3f normal = Vector3f.normalize(rawNormal);
                        Vector2f[] uv = resolveFaceUv(face, cube, size, texW, texH);

                        // After a Y-mirror the signed screen area of each triangle flips sign, so
                        // the rasterizer's back-face cull would discard the faces we actually want
                        // to see. Reverse the vertex order in-place to restore the original
                        // winding handedness.
                        int i1 = negateY ? 2 : 1;
                        int i2 = negateY ? 1 : 2;
                        int j1 = negateY ? 3 : 2;
                        int j2 = negateY ? 2 : 3;

                        triangles.add(new VisibleTriangle(
                            corners[0], corners[i1], corners[i2],
                            uv[0], uv[i1], uv[i2],
                            texture, ColorKit.WHITE,
                            normal, 1f, priority
                        ));
                        triangles.add(new VisibleTriangle(
                            corners[0], corners[j1], corners[j2],
                            uv[0], uv[j1], uv[j2],
                            texture, ColorKit.WHITE,
                            normal, 1f, priority
                        ));
                        priority++;
                    }
                }
            }

            return triangles;
        }

        /**
         * Resolves the UV corners for a single face of a cube. When the cube carries a per-face
         * override for this direction (Bedrock-style {@link EntityModelData.FaceUv}), the
         * explicit pixel rectangle is used and the cube's {@code mirror} flag is applied on
         * top; otherwise the face falls back to
         * {@link BlockFace#defaultUv(int[], float[], float, float, boolean) defaultUv} using
         * the cube's atlas origin and size.
         */
        private static @NotNull Vector2f @NotNull [] resolveFaceUv(
            @NotNull BlockFace face,
            @NotNull EntityModelData.Cube cube,
            float @NotNull [] size,
            float texWidth,
            float texHeight
        ) {
            EntityModelData.FaceUv override = cube.getFaceUv().get(face.direction());
            if (override == null)
                return face.defaultUv(cube.getUv(), size, texWidth, texHeight, cube.isMirror());

            float u0 = override.getUv()[0];
            float v0 = override.getUv()[1];
            float u1 = u0 + override.getUvSize()[0];
            float v1 = v0 + override.getUvSize()[1];
            return BlockFace.uvRect(u0, v0, u1, v1, texWidth, texHeight, cube.isMirror());
        }

        /**
         * Builds a bone's rotation transform around its pivot point. Rotations are applied
         * Z-Y-X order to the vector, matching vanilla entity model conventions. Returns the
         * identity when all three rotation components are zero so the caller can skip the
         * transform work on the hot path.
         */
        private static @NotNull Matrix4f buildBoneTransform(@NotNull EntityModelData.Bone bone) {
            float[] r = bone.getRotation();
            if (r[0] == 0f && r[1] == 0f && r[2] == 0f) return Matrix4f.IDENTITY;

            float[] p = bone.getPivot();
            Matrix4f toPivot = Matrix4f.createTranslation(-p[0], -p[1], -p[2]);
            Matrix4f fromPivot = Matrix4f.createTranslation(p[0], p[1], p[2]);
            Matrix4f rotation = Matrix4f.createRotationZ((float) Math.toRadians(r[2]))
                .multiply(Matrix4f.createRotationY((float) Math.toRadians(r[1])))
                .multiply(Matrix4f.createRotationX((float) Math.toRadians(r[0])));
            return toPivot.multiply(rotation).multiply(fromPivot);
        }

        /**
         * Computes an axis-aligned bounding box over every cube of every bone, with each
         * bone's rotation already applied. Used to derive a uniform scale factor and centre
         * translation so the whole model fits inside the renderer's unit canvas.
         * <p>
         * Honours {@link EntityModelData#isNegateY()} by mirroring transformed cube corners
         * about the XZ plane before the min/max update, so the bounds match the mirrored
         * geometry emitted by {@link #buildEntityTriangles}.
         */
        private static @NotNull ModelBounds computeModelBounds(@NotNull EntityModelData model) {
            boolean negateY = model.isNegateY();
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

            for (EntityModelData.Bone bone : model.getBones().values()) {
                Matrix4f boneTransform = buildBoneTransform(bone);
                for (EntityModelData.Cube cube : bone.getCubes()) {
                    float[] origin = cube.getOrigin();
                    float[] size = cube.getSize();
                    float inflate = cube.getInflate();

                    float[] xs = { origin[0] - inflate, origin[0] + size[0] + inflate };
                    float[] ys = { origin[1] - inflate, origin[1] + size[1] + inflate };
                    float[] zs = { origin[2] - inflate, origin[2] + size[2] + inflate };

                    for (float x : xs) {
                        for (float y : ys) {
                            for (float z : zs) {
                                Vector3f c = Vector3f.transform(new Vector3f(x, y, z), boneTransform);
                                float cy = negateY ? -c.getY() : c.getY();
                                if (c.getX() < minX) minX = c.getX();
                                if (cy < minY) minY = cy;
                                if (c.getZ() < minZ) minZ = c.getZ();
                                if (c.getX() > maxX) maxX = c.getX();
                                if (cy > maxY) maxY = cy;
                                if (c.getZ() > maxZ) maxZ = c.getZ();
                            }
                        }
                    }
                }
            }

            if (minX == Float.POSITIVE_INFINITY)
                return new ModelBounds(0f, 0f, 0f, 0f, 0f, 0f);

            return new ModelBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        /**
         * The axis-aligned bounds of an entity model after per-bone pivot rotation has been
         * applied to every cube corner.
         */
        private record ModelBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

            float maxExtent() {
                return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            }

        }

    }

}
