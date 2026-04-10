package dev.sbs.renderer;

import dev.sbs.renderer.draw.BlockFace;
import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.engine.ModelEngine;
import dev.sbs.renderer.engine.PerspectiveParams;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.VisibleTriangle;
import dev.sbs.renderer.math.Matrix4f;
import dev.sbs.renderer.math.Vector2f;
import dev.sbs.renderer.math.Vector3f;
import dev.sbs.renderer.model.Entity;
import dev.sbs.renderer.model.asset.EntityModelData;
import dev.sbs.renderer.options.EntityOptions;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Renders non-player entities using their {@link EntityModelData} bone/cube tree. Resolves the
 * entity definition from the {@link RendererContext} by id, loads its texture through the
 * active pack stack, and rasterizes through {@link ModelEngine} with a GUI-item perspective.
 */
@RequiredArgsConstructor
public final class EntityRenderer implements Renderer<EntityOptions> {

    private final @NotNull RendererContext context;

    @Override
    public @NotNull ImageData render(@NotNull EntityOptions options) {
        Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());

        if (options.getEntityId().isEmpty())
            return RenderEngine.staticFrame(canvas);

        Optional<Entity> entityLookup = this.context.findEntity(options.getEntityId().get());
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

        ModelEngine engine = new ModelEngine(this.context);
        engine.rasterize(triangles, canvas, PerspectiveParams.GUI_ITEM,
            options.getPitch(), options.getYaw(), options.getRoll());

        if (options.isAntiAlias())
            canvas.getBuffer().applyFxaa();

        return RenderEngine.staticFrame(canvas);
    }

    /**
     * Resolves the entity texture through the pack stack. An explicit texture id on the options
     * takes priority over the entity definition's own texture id.
     */
    private @NotNull Optional<PixelBuffer> resolveEntityTexture(
        @NotNull Entity entity,
        @NotNull EntityOptions options
    ) {
        if (options.getTextureId().isPresent())
            return this.context.resolveTexture(options.getTextureId().get());

        if (entity.getTextureId().isPresent())
            return this.context.resolveTexture(entity.getTextureId().get());

        return Optional.empty();
    }

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
                            float cy2 = negateY ? -c.getY() : c.getY();
                            if (c.getX() < minX) minX = c.getX();
                            if (cy2 < minY) minY = cy2;
                            if (c.getZ() < minZ) minZ = c.getZ();
                            if (c.getX() > maxX) maxX = c.getX();
                            if (cy2 > maxY) maxY = cy2;
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

    private record ModelBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

        float maxExtent() {
            return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        }

    }

}
