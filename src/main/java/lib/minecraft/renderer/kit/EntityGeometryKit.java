package lib.minecraft.renderer.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Generates triangle lists from {@link EntityModelData} bone/cube trees using Mojang Bedrock's
 * native coordinate convention - the same frame authored in {@code .geo.json} and stored verbatim
 * by {@link ToolingEntityModels ToolingEntityModels}.
 * <p>
 * Convention:
 * <ul>
 * <li>Y-up, right-handed.</li>
 * <li>{@link EntityModelData.Cube#getOrigin() Cube origins} are the min corner in absolute
 * entity-root space - Bedrock does not pre-relativise cubes into bone-local space; the owning
 * bone's {@link EntityModelData.Bone#getPivot() pivot} is purely the rotation anchor.</li>
 * <li>{@link EntityModelData.Bone#getPivot() Bone pivots} are in absolute entity-root space.</li>
 * <li>A bone's transform is only its rotation about its pivot - translation-only bones
 * contribute the identity. Children follow by composing their ancestors' pivot-centred rotations
 * in root-down order, so rotation-less intermediate bones never displace the subtree.</li>
 * </ul>
 * The model is auto-centered and uniformly scaled to fit within {@code [-0.5, +0.5]} on the
 * longest axis so the output meshes drop directly into the isometric and model engine
 * rasterizers. Shared by {@link EntityRenderer EntityRenderer}
 * (entities with armor overlays) and {@link BlockRenderer BlockRenderer}
 * (entity-model fallback for element-less blocks like shulker boxes and chests).
 */
@UtilityClass
public class EntityGeometryKit {

    /**
     * Uniform fit extent every entity model is rescaled to - fills 90% of the unit cube so
     * outstretched limbs, capes, and horn extensions keep a small margin before clipping the
     * iso tile edges.
     */
    private static final float ENTITY_MODEL_FIT_EXTENT = 0.9f;

    /**
     * Lower bound on the measured model extent before scaling. Guards against division by zero
     * on degenerate (empty-bone or zero-cube) models that would otherwise produce an infinite
     * scale factor.
     */
    private static final float MIN_MODEL_EXTENT = 0.001f;

    /**
     * Builds a triangle list from the bone/cube tree of an entity model, using a single shared
     * texture atlas. The model is centered at the origin and uniformly scaled to fit within
     * {@link #ENTITY_MODEL_FIT_EXTENT}.
     *
     * @param model the entity model definition
     * @param texture the shared texture atlas for all cubes
     * @return the build result containing triangles and per-bone bounding boxes
     */
    public static @NotNull BuildResult buildTriangles(@NotNull EntityModelData model, @NotNull PixelBuffer texture) {
        Map<String, Matrix4f> chainTransforms = buildChainTransforms(model.getBones());

        ModelBounds bounds = computeBounds(model, chainTransforms);
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        float scale = ENTITY_MODEL_FIT_EXTENT / extent;
        float cx = (bounds.minX + bounds.maxX) * 0.5f;
        float cy = (bounds.minY + bounds.maxY) * 0.5f;
        float cz = (bounds.minZ + bounds.maxZ) * 0.5f;

        // UV pixel addresses in Bedrock geo.json are authored in the declared texture_width /
        // texture_height pixel grid. When the Bedrock pack ships an HD PNG that is a uniform
        // upscale of the declared dims (ghast: declared 64x32, PNG 128x64; happy_ghast: 64x64
        // vs 128x128) or a lower-res downscale (zombie_horse: declared 128x128, PNG 64x64),
        // the content is still laid out in the declared grid and normalising by actual PNG
        // extent collapses every face to a fraction of the texture. Declared dims are the
        // coordinate space the authored UVs live in; actual PNG dims are just the sample
        // resolution. Fallback to actual extent guards against malformed models that shipped
        // without declared dims.
        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture.height());

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Map<String, Vector3f[]> boneBounds = new HashMap<>();

        // bones is a ConcurrentLinkedMap so entrySet() preserves JSON insertion order. The
        // rasterizer respects that order for coplanar-face resolution (e.g. chest body SOUTH,
        // lid SOUTH, and lock's back face all at z=15 - body bone iterated first ends up in the
        // triangle list first, and its pixels survive the epsilon-tolerant depth test).
        for (Map.Entry<String, EntityModelData.Bone> boneEntry : model.getBones().entrySet()) {
            String boneName = boneEntry.getKey();
            EntityModelData.Bone bone = boneEntry.getValue();
            Matrix4f boneChain = chainTransforms.get(boneName);

            float bMinX = Float.POSITIVE_INFINITY, bMinY = Float.POSITIVE_INFINITY, bMinZ = Float.POSITIVE_INFINITY;
            float bMaxX = Float.NEGATIVE_INFINITY, bMaxY = Float.NEGATIVE_INFINITY, bMaxZ = Float.NEGATIVE_INFINITY;

            for (EntityModelData.Cube cube : bone.getCubes()) {
                float[] origin = cube.getOrigin();
                float[] size = cube.getSize();
                float inflate = cube.getInflate();

                // Cube vertices are in absolute entity-root space (Bedrock convention) - the min
                // corner at (origin) and the max at (origin + size), inflated outward by inflate
                // on each axis for armor-layer-style padding.
                Box cubeBounds = new Box(
                    origin[0] - inflate, origin[1] - inflate, origin[2] - inflate,
                    origin[0] + size[0] + inflate, origin[1] + size[1] + inflate, origin[2] + size[2] + inflate
                );

                // Row-vector order, innermost first:
                //   cubeAnchor  : T(-cube.pivot) * R(cube.rotation) * T(+cube.pivot)
                //   bindPose    : T(-bone.pivot) * R(bone.bindPose) * T(+bone.pivot)  (does NOT propagate to children)
                //   boneChain   : anchor(bone.rotation) * anchor(parent) * ...         (propagates)
                Matrix4f fullTransform = composeCubeTransform(cube, bone, boneChain);

                boolean cubeCullBackFaces = shouldCullBackFaces(cube, size, texture, texW, texH);

                for (BlockFace face : BlockFace.values()) {
                    Vector3f[] corners = face.corners(cubeBounds);
                    for (int i = 0; i < 4; i++) {
                        Vector3f transformed = Vector3f.transform(corners[i], fullTransform);
                        float nx = (transformed.x() - cx) * scale;
                        float ny = (transformed.y() - cy) * scale;
                        float nz = (transformed.z() - cz) * scale;
                        corners[i] = new Vector3f(nx, ny, nz);

                        bMinX = Math.min(bMinX, nx);
                        bMinY = Math.min(bMinY, ny);
                        bMinZ = Math.min(bMinZ, nz);
                        bMaxX = Math.max(bMaxX, nx);
                        bMaxY = Math.max(bMaxY, ny);
                        bMaxZ = Math.max(bMaxZ, nz);
                    }

                    Vector3f normal = Vector3f.normalize(Vector3f.transformNormal(face.normal(), fullTransform));
                    Vector2f[] uv = resolveFaceUv(face, cube, size, texW, texH);

                    triangles.add(new VisibleTriangle(
                        corners[0], corners[1], corners[2],
                        uv[0], uv[1], uv[2],
                        texture, ColorMath.WHITE,
                        normal, 1f,
                        cubeCullBackFaces
                    ));
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[2], corners[3],
                        uv[0], uv[2], uv[3],
                        texture, ColorMath.WHITE,
                        normal, 1f,
                        cubeCullBackFaces
                    ));
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
     * Computes the axis-aligned bounding box of an entity model after applying each bone's
     * ancestor anchor chain, in the Bedrock-native Y-up frame.
     *
     * @param model the entity model definition
     * @return the model bounds
     */
    public static @NotNull ModelBounds computeBounds(@NotNull EntityModelData model) {
        return computeBounds(model, buildChainTransforms(model.getBones()));
    }

    /**
     * Internal variant that reuses an already-built chain cache so {@link #buildTriangles} and
     * {@link #computeBounds(EntityModelData)} don't rebuild the same matrices back-to-back.
     */
    private static @NotNull ModelBounds computeBounds(
        @NotNull EntityModelData model,
        @NotNull Map<String, Matrix4f> chainTransforms
    ) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (Map.Entry<String, EntityModelData.Bone> entry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            Matrix4f boneChain = chainTransforms.get(entry.getKey());
            for (EntityModelData.Cube cube : bone.getCubes()) {
                float[] origin = cube.getOrigin();
                float[] size = cube.getSize();
                float inflate = cube.getInflate();
                Matrix4f fullTransform = composeCubeTransform(cube, bone, boneChain);

                float[] xs = { origin[0] - inflate, origin[0] + size[0] + inflate };
                float[] ys = { origin[1] - inflate, origin[1] + size[1] + inflate };
                float[] zs = { origin[2] - inflate, origin[2] + size[2] + inflate };

                for (float x : xs) {
                    for (float y : ys) {
                        for (float z : zs) {
                            Vector3f c = Vector3f.transform(new Vector3f(x, y, z), fullTransform);
                            minX = Math.min(minX, c.x());
                            minY = Math.min(minY, c.y());
                            minZ = Math.min(minZ, c.z());
                            maxX = Math.max(maxX, c.x());
                            maxY = Math.max(maxY, c.y());
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

    /**
     * Builds the ancestor-anchor chain matrix for every bone. For a bone {@code B} with parent
     * chain {@code (P, GP, ...)} the row-vector transform is
     * {@code v * anchor(B) * anchor(P) * anchor(GP) * ...} where
     * {@code anchor(X) = T(-X.pivot) * R_X * T(+X.pivot)} rotates in place about the authored
     * absolute pivot and reduces to the identity when {@code R_X} is zero.
     * <p>
     * Bedrock stores cube origins in absolute entity-root space, so no {@code T(pivot)} factor
     * is needed for placement - the chain is pure rotation. A translation-only bone contributes
     * identity and does not move its descendants.
     * <p>
     * Results are cached so each bone's chain is constructed once per render pass even when
     * many children share a deep ancestry.
     */
    private static @NotNull Map<String, Matrix4f> buildChainTransforms(
        @NotNull Map<String, EntityModelData.Bone> bones
    ) {
        Map<String, Matrix4f> cache = new HashMap<>();
        for (String name : bones.keySet())
            resolveChain(name, bones, cache, new LinkedHashSet<>());
        return cache;
    }

    /**
     * Returns the composed anchor chain {@code anchor(B) * anchor(P) * anchor(GP) * ...} for
     * {@code name}. The {@code visiting} set guards against reference cycles in malformed
     * geometries; when a cycle is detected the bone falls back to its own anchor (identity
     * hierarchy above) so the rest of the model still renders.
     */
    private static @NotNull Matrix4f resolveChain(
        @NotNull String name,
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull Map<String, Matrix4f> cache,
        @NotNull Set<String> visiting
    ) {
        Matrix4f cached = cache.get(name);
        if (cached != null) return cached;
        EntityModelData.Bone bone = bones.get(name);
        if (bone == null) return Matrix4f.IDENTITY;
        if (visiting.contains(name)) return buildAnchor(bone);
        visiting.add(name);

        Matrix4f own = buildAnchor(bone);
        String parent = bone.getParent();
        Matrix4f composed;
        if (parent == null || parent.equals(name) || !bones.containsKey(parent)) {
            composed = own;
        } else {
            Matrix4f parentChain = resolveChain(parent, bones, cache, visiting);
            composed = own.multiply(parentChain);
        }

        visiting.remove(name);
        cache.put(name, composed);
        return composed;
    }

    /**
     * Bone anchor: {@code T(-pivot) * R(rotation) * T(+pivot)} under row-vector convention -
     * a pure rotate-in-place about the bone's absolute entity-root pivot. When the rotation is
     * zero this reduces to the identity, so translation-only parents do not displace children.
     */
    private static @NotNull Matrix4f buildAnchor(@NotNull EntityModelData.Bone bone) {
        EulerRotation rotation = bone.getRotation();
        if (rotation.pitch() == 0f && rotation.yaw() == 0f && rotation.roll() == 0f)
            return Matrix4f.IDENTITY;

        return pivotCenteredRotation(bone.getPivot(), rotation);
    }

    /**
     * Composes the full per-cube transform. Row-vector order applied left-to-right:
     * <pre>
     *   v' = v * cubeAnchor * bindPoseAnchor * boneChain
     * </pre>
     * where
     * <ul>
     *   <li>{@code cubeAnchor = T(-cube.pivot) * R(cube.rotation) * T(+cube.pivot)} - the
     *       cube's own 1.12+ rotation about its own absolute pivot. Identity when the cube has
     *       no rotation.</li>
     *   <li>{@code bindPoseAnchor = T(-bone.pivot) * R(bone.bindPose) * T(+bone.pivot)} - the
     *       bone's static rest-pose rotation about its own pivot. Applies only to this bone's
     *       own cubes; does <b>not</b> propagate through the ancestor chain to descendants
     *       (legs keep their vertical authoring while the body cube tilts horizontal).</li>
     *   <li>{@code boneChain} - precomputed product of {@code anchor(bone.rotation) *
     *       anchor(parent.rotation) * ...} from {@link #buildChainTransforms}, the hierarchy
     *       rotations that do propagate.</li>
     * </ul>
     * Zero-rotation factors short-circuit to identity so legacy 1.8 geometries stay on the
     * fast path.
     */
    private static @NotNull Matrix4f composeCubeTransform(
        @NotNull EntityModelData.Cube cube,
        @NotNull EntityModelData.Bone bone,
        @NotNull Matrix4f boneChain
    ) {
        EulerRotation cubeRot = cube.getRotation();
        EulerRotation bindPose = bone.getBindPoseRotation();
        boolean hasCube = !isZero(cubeRot);
        boolean hasBind = !isZero(bindPose);
        if (!hasCube && !hasBind) return boneChain;

        Matrix4f acc = boneChain;
        if (hasBind) acc = pivotCenteredRotation(bone.getPivot(), bindPose).multiply(acc);
        if (hasCube) acc = pivotCenteredRotation(cube.getPivot(), cubeRot).multiply(acc);
        return acc;
    }

    /** Tiny helper - three-axis zero check shared by cube/bone/bind-pose rotation fast paths. */
    private static boolean isZero(@NotNull EulerRotation r) {
        return r.pitch() == 0f && r.yaw() == 0f && r.roll() == 0f;
    }

    /**
     * Builds the {@code T(-pivot) * R(rotation) * T(+pivot)} row-vector matrix for rotating in
     * place about an absolute pivot. Rotation composition order is Z-Y-X (row-vector
     * {@code R_Z * R_Y * R_X}), matching Bedrock and Blockbench's intrinsic XYZ convention.
     * <p>
     * <b>Sign convention:</b> Bedrock's positive pitch tilts a bone <i>forward</i> (top moves
     * toward the entity's front face, which is -Z in Minecraft's default facing), and positive
     * roll rolls to the bone's <i>right</i>. Those directions are the <i>negative</i> rotations
     * around X and Z under the right-hand rule in a Y-up frame - so the pitch and roll angles
     * are negated when building their matrices. Yaw (Y axis) follows the right-hand rule
     * directly and passes through unchanged. This is the only sign flip in the entire pipeline;
     * once applied here, {@code [pitch, yaw, roll]} in the authored {@code .geo.json} maps to
     * the visual pose Bedrock renders natively, with no per-field or per-bone tuning needed.
     */
    private static @NotNull Matrix4f pivotCenteredRotation(
        float @NotNull [] pivot,
        @NotNull EulerRotation rotation
    ) {
        Matrix4f toPivot = Matrix4f.createTranslation(-pivot[0], -pivot[1], -pivot[2]);
        Matrix4f fromPivot = Matrix4f.createTranslation(pivot[0], pivot[1], pivot[2]);
        Matrix4f rot = Matrix4f.createRotationZ(-rotation.rollRadians())
            .multiply(Matrix4f.createRotationY(rotation.yawRadians()))
            .multiply(Matrix4f.createRotationX(-rotation.pitchRadians()));
        return toPivot.multiply(rot).multiply(fromPivot);
    }

    /**
     * Resolves the four UV corners for one cube face. Delegates the atlas unwrap to
     * {@link BlockFace#defaultUv(int[], float[], float, float, boolean)} for Bedrock's strip
     * layout; a per-face override from {@link EntityModelData.Cube#getFaceUv()} (Bedrock 1.12+
     * explicit {@code cube.uv} object form) bypasses the unwrap entirely and uses the authored
     * rectangle directly.
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
     * Decides whether this cube's triangles should opt into back-face culling. Bedrock entity
     * textures occasionally author all the cube's renderable content on faces that are
     * back-facing in our standard iso pose {@code [pitch=30, yaw=225]} - the chicken leg cube
     * is the canonical example, with its foot-bottom 3x3 patch on DOWN and its leg silhouette
     * on SOUTH, while TOP/NORTH/EAST UV rects are entirely empty. With culling on, the empty
     * front faces don't write to the depth buffer (the rasterizer skips depth on transparent
     * samples) but the back faces are dropped before they can render either, so the cube
     * renders as nothing visible. Disabling culling for cubes in that shape lets the back
     * faces pass through unobstructed and the foot/silhouette appear at their natural cube
     * positions.
     * <p>
     * Rule: cull when any iso-visible face has opaque content (the normal case - back faces
     * lose the depth test to the front faces anyway, so culling is just a perf win). Don't
     * cull when all iso-visible faces are empty AND any iso-hidden face has content. When
     * everything is empty, default back to culling (no behavioural difference).
     * <p>
     * The iso-visible face set ({@link BlockFace#UP}, {@link BlockFace#NORTH},
     * {@link BlockFace#EAST}) is hardcoded for the standard block-iso pose used by
     * {@code EntityRenderer}. If the iso pose ever changes, revisit.
     */
    private static boolean shouldCullBackFaces(
        @NotNull EntityModelData.Cube cube,
        float @NotNull [] size,
        @NotNull PixelBuffer texture,
        float texW,
        float texH
    ) {
        boolean visibleHasContent =
               uvHasContent(resolveFaceUv(BlockFace.UP, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(BlockFace.NORTH, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(BlockFace.EAST, cube, size, texW, texH), texture);
        if (visibleHasContent) return true;
        boolean hiddenHasContent =
               uvHasContent(resolveFaceUv(BlockFace.DOWN, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(BlockFace.SOUTH, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(BlockFace.WEST, cube, size, texW, texH), texture);
        return !hiddenHasContent;
    }

    /**
     * Returns {@code true} when the texture has at least one fully or partially opaque pixel
     * inside the bounding rectangle of the supplied UV corners. Used by
     * {@link #shouldCullBackFaces} to detect the chicken-class case where the cube's
     * iso-visible faces are entirely transparent. UVs are in {@code [0, 1]} normalised space;
     * the helper expands them back to integer pixel bounds for the loop.
     */
    private static boolean uvHasContent(
        @NotNull Vector2f @NotNull [] uv,
        @NotNull PixelBuffer texture
    ) {
        int W = texture.width();
        int H = texture.height();
        float u0 = Math.min(Math.min(uv[0].x(), uv[1].x()), Math.min(uv[2].x(), uv[3].x())) * W;
        float v0 = Math.min(Math.min(uv[0].y(), uv[1].y()), Math.min(uv[2].y(), uv[3].y())) * H;
        float u1 = Math.max(Math.max(uv[0].x(), uv[1].x()), Math.max(uv[2].x(), uv[3].x())) * W;
        float v1 = Math.max(Math.max(uv[0].y(), uv[1].y()), Math.max(uv[2].y(), uv[3].y())) * H;
        int x0 = Math.max(0, (int) Math.floor(u0));
        int y0 = Math.max(0, (int) Math.floor(v0));
        int x1 = Math.min(W, (int) Math.ceil(u1));
        int y1 = Math.min(H, (int) Math.ceil(v1));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (ColorMath.alpha(texture.getPixel(x, y)) > 0) return true;
            }
        }
        return false;
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
