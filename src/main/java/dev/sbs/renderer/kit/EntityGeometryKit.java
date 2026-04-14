package dev.sbs.renderer.kit;

import dev.sbs.renderer.geometry.BlockFace;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.asset.model.EntityModelData;
import dev.sbs.renderer.tensor.Matrix4f;
import dev.sbs.renderer.tensor.Vector2f;
import dev.sbs.renderer.tensor.Vector3f;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates triangle lists from {@link EntityModelData} bone/cube trees, matching the convention
 * used by {@link GeometryKit} for block elements.
 * <p>
 * The model is auto-centered and scaled to fit within the {@code [-0.5, +0.5]} unit cube so the
 * output meshes are directly compatible with the isometric and model engine rasterizers. Shared
 * by both {@link dev.sbs.renderer.EntityRenderer EntityRenderer} (full entity rendering with
 * armor overlays) and {@link dev.sbs.renderer.BlockRenderer BlockRenderer} (entity model
 * fallback for element-less blocks like shulker boxes and chests).
 */
@UtilityClass
public class EntityGeometryKit {

    /**
     * Builds a triangle list from the bone/cube tree of an entity model, using a single shared
     * texture atlas. The model is centered at the origin and uniformly scaled to fit within a
     * {@code 0.9}-unit extent.
     *
     * @param model the entity model definition
     * @param texture the shared texture atlas for all cubes
     * @return the build result containing triangles and per-bone bounding boxes
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture
    ) {
        boolean negateY = model.isNegateY();
        ModelBounds bounds = computeBounds(model);
        float extent = Math.max(bounds.maxExtent(), 0.001f);
        float scale = 0.9f / extent;
        float cx = (bounds.minX + bounds.maxX) * 0.5f;
        float cy = (bounds.minY + bounds.maxY) * 0.5f;
        float cz = (bounds.minZ + bounds.maxZ) * 0.5f;

        float texW = Math.max(1f, model.getTextureWidth());
        float texH = Math.max(1f, model.getTextureHeight());

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Map<String, Vector3f[]> boneBounds = new HashMap<>();
        int priority = 0;

        for (Map.Entry<String, EntityModelData.Bone> boneEntry : model.getBones().entrySet()) {
            String boneName = boneEntry.getKey();
            EntityModelData.Bone bone = boneEntry.getValue();
            Matrix4f boneTransform = buildBoneTransform(bone);

            float bMinX = Float.POSITIVE_INFINITY, bMinY = Float.POSITIVE_INFINITY, bMinZ = Float.POSITIVE_INFINITY;
            float bMaxX = Float.NEGATIVE_INFINITY, bMaxY = Float.NEGATIVE_INFINITY, bMaxZ = Float.NEGATIVE_INFINITY;

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
                        float ty = negateY ? -transformed.y() : transformed.y();
                        float nx = (transformed.x() - cx) * scale;
                        float ny = (ty - cy) * scale;
                        float nz = (transformed.z() - cz) * scale;
                        corners[i] = new Vector3f(nx, ny, nz);

                        bMinX = Math.min(bMinX, nx);
                        bMinY = Math.min(bMinY, ny);
                        bMinZ = Math.min(bMinZ, nz);
                        bMaxX = Math.max(bMaxX, nx);
                        bMaxY = Math.max(bMaxY, ny);
                        bMaxZ = Math.max(bMaxZ, nz);
                    }

                    Vector3f rawNormal = Vector3f.transformNormal(face.normal(), boneTransform);
                    if (negateY)
                        rawNormal = new Vector3f(rawNormal.x(), -rawNormal.y(), rawNormal.z());
                    Vector3f normal = Vector3f.normalize(rawNormal);
                    Vector2f[] uv = resolveFaceUv(face, cube, size, texW, texH);

                    int i1 = negateY ? 2 : 1;
                    int i2 = negateY ? 1 : 2;
                    int j1 = negateY ? 3 : 2;
                    int j2 = negateY ? 2 : 3;

                    triangles.add(new VisibleTriangle(
                        corners[0], corners[i1], corners[i2],
                        uv[0], uv[i1], uv[i2],
                        texture, ColorMath.WHITE,
                        normal, 1f, priority,
                        true
                    ));
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[j1], corners[j2],
                        uv[0], uv[j1], uv[j2],
                        texture, ColorMath.WHITE,
                        normal, 1f, priority,
                        true
                    ));
                    priority++;
                }
            }

            if (bMinX != Float.POSITIVE_INFINITY)
                boneBounds.put(boneName, new Vector3f[]{
                    new Vector3f(bMinX, bMinY, bMinZ),
                    new Vector3f(bMaxX, bMaxY, bMaxZ)
                });
        }

        return new BuildResult(triangles, boneBounds);
    }

    /**
     * Computes the axis-aligned bounding box of an entity model after applying all bone
     * transforms and Y-negation.
     *
     * @param model the entity model definition
     * @return the model bounds
     */
    public static @NotNull ModelBounds computeBounds(@NotNull EntityModelData model) {
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
                            float cy = negateY ? -c.y() : c.y();
                            minX = Math.min(minX, c.x());
                            minY = Math.min(minY, cy);
                            minZ = Math.min(minZ, c.z());
                            maxX = Math.max(maxX, c.x());
                            maxY = Math.max(maxY, cy);
                            maxZ = Math.max(maxZ, c.z());
                        }
                    }
                }
            }
        }

        if (minX == Float.POSITIVE_INFINITY)
            return new ModelBounds(0f, 0f, 0f, 0f, 0f, 0f);

        return new ModelBounds(minX, minY, minZ, maxX, maxY, maxZ);
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
     * The result of building triangles from an entity model, carrying the triangle list and
     * per-bone bounding boxes used by the armor overlay system.
     *
     * @param triangles the triangle list ready for rasterization
     * @param boneBounds per-bone axis-aligned bounding boxes keyed by bone name
     */
    public record BuildResult(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<String, Vector3f[]> boneBounds
    ) {}

    /**
     * Axis-aligned bounding box of an entity model in its native coordinate space.
     *
     * @param minX the minimum X
     * @param minY the minimum Y
     * @param minZ the minimum Z
     * @param maxX the maximum X
     * @param maxY the maximum Y
     * @param maxZ the maximum Z
     */
    public record ModelBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

        /**
         * Returns the largest extent across all three axes.
         *
         * @return the maximum dimension
         */
        public float maxExtent() {
            return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        }

    }

}
