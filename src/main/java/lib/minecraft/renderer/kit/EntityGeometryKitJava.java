package lib.minecraft.renderer.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EntityFace;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lib.minecraft.renderer.tooling.ToolingJavaEntityModels;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Side-by-side sibling of {@link EntityGeometryKit} that operates in Java's native
 * {@code ModelPart}-style coordinate frame instead of Bedrock's. Lives parallel to the bedrock
 * engine so the bedrock-derived pipeline keeps producing its established output unchanged while
 * the Java-derived pipeline (per the research plan at
 * {@code ~/.claude/plans/java-derived-entity-models-research.md}) gets its own clean code path
 * for visual comparison and parity testing.
 * <p>
 * Convention:
 * <ul>
 * <li><b>Y-down, right-handed</b> - matches vanilla Java's {@code PartPose} / {@code ModelPart}
 * authoring (positive Y points toward the entity's feet from its root). Consumes
 * {@link EntityModelData} produced by {@link ToolingJavaEntityModels} which ships in this frame
 * natively (no parse-time conversion).</li>
 * <li>{@link EntityModelData.Cube#getOrigin() Cube origins} are the min corner in absolute
 * entity-root space (Y-down).</li>
 * <li>{@link EntityModelData.Bone#getPivot() Bone pivots} are in absolute entity-root space.</li>
 * <li>Bone transforms are pure rotations about pivots; translation-only bones contribute
 * identity.</li>
 * </ul>
 * The model is auto-centered and uniformly scaled to fit within {@code [-0.5, +0.5]} on the
 * longest axis. Output triangles undergo a single Y-axis flip at the boundary so the shared
 * isometric rasterizer (which expects screen-Y-up) renders them correctly without needing a
 * separate camera setup.
 */
@UtilityClass
public class EntityGeometryKitJava {

    /** Mirrors {@link EntityGeometryKit}'s 90% unit-cube fit so both kits produce comparable scales. */
    private static final float ENTITY_MODEL_FIT_EXTENT = 0.9f;

    /** Lower bound on extent before scaling - guards against zero-cube models producing infinity. */
    private static final float MIN_MODEL_EXTENT = 0.001f;

    /**
     * Per-axis flip / negation switches for the iteration harness in Phase E.6. Each switch
     * defaults to the production setting; override at runtime via {@code -Dentity.flipX=true}
     * etc. so the parity script can sweep combinations without recompiling. The Y-flip and
     * Y-normal-flip are the legacy production defaults (matching the bedrock kit's Y-up
     * screen output). Other knobs default to identity. Read once at class load - changing
     * after the first render is a no-op until JVM restart.
     */
    private static final boolean FLIP_X = Boolean.getBoolean("entity.flipX");
    private static final boolean FLIP_Y = !"false".equalsIgnoreCase(System.getProperty("entity.flipY", "true"));
    private static final boolean FLIP_Z = Boolean.getBoolean("entity.flipZ");
    private static final boolean FLIP_NORMAL_X = Boolean.getBoolean("entity.flipNX");
    private static final boolean FLIP_NORMAL_Y = !"false".equalsIgnoreCase(System.getProperty("entity.flipNY", "true"));
    private static final boolean FLIP_NORMAL_Z = Boolean.getBoolean("entity.flipNZ");
    /** Toggle whether the kit translates cube origins by their bone's pivot. Default true. */
    private static final boolean TRANSLATE_BY_PIVOT = !"false".equalsIgnoreCase(System.getProperty("entity.translateByPivot", "true"));

    /**
     * Convenience overload that auto-computes bounds for a single-layer render.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @return the build result containing triangles and per-bone bounds
     */
    public static @NotNull EntityGeometryKit.BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture
    ) {
        return buildTriangles(model, texture, computeBounds(model), false);
    }

    /**
     * Variant accepting caller-supplied bounds for layered renders that must share one auto-fit
     * across base + overlay meshes.
     */
    public static @NotNull EntityGeometryKit.BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds
    ) {
        return buildTriangles(model, texture, bounds, false);
    }

    /**
     * Full variant tagging every triangle with the supplied {@code emissive} flag for overlay
     * layers that should render full-bright + additive (eyes / glowing spots). Uses the legacy
     * auto-fit scale ({@code 0.9 / bounds.maxExtent}); see the {@code ndcScale} overload for
     * native-resolution rendering aligned to the vanilla harness's pixels-per-block convention.
     */
    public static @NotNull EntityGeometryKit.BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds,
        boolean emissive
    ) {
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildTrianglesWithScale(model, texture, new Vector3f(cx, cy, cz), emissive, ENTITY_MODEL_FIT_EXTENT / extent, 1f);
    }

    /**
     * Native-resolution variant: caller supplies the model-units-to-NDC scale instead of the kit
     * auto-fitting via {@link #ENTITY_MODEL_FIT_EXTENT}. Used by
     * {@link lib.minecraft.renderer.EntityRendererJava} to match the vanilla-reference-harness's
     * fixed {@code pixelsPerBlock} convention so two pipelines render at the same screen scale.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @param bounds caller-supplied bounds (used for the centre anchor only - not for scale)
     * @param emissive whether triangles render full-bright + additive
     * @param ndcScale model-units-to-NDC scale; pre-computed by the renderer from the canvas
     *     dimensions and target pixels-per-block so {@code (vec - centre) * ndcScale} lands in NDC
     * @return the build result containing triangles and per-bone bounds
     */
    public static @NotNull EntityGeometryKit.BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds,
        boolean emissive,
        float ndcScale
    ) {
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildTrianglesWithScale(model, texture, new Vector3f(cx, cy, cz), emissive, ndcScale, 1f);
    }

    /**
     * Native-resolution variant with a per-render model pre-scale. {@code modelScale} multiplies
     * every model vertex before the {@code (vec - centre) * ndcScale} step so vanilla's combined
     * renderer-scale + state-scale chain (e.g. wither's {@code scale(2,2,2)}, giant's
     * {@code state.scale=6}) lands at the harness's submit-time size. Caller-supplied bounds
     * must be the K-scaled bounds (so the centre matches the scaled vertices).
     */
    public static @NotNull EntityGeometryKit.BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds,
        boolean emissive,
        float ndcScale,
        float modelScale
    ) {
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildTrianglesWithScale(model, texture, new Vector3f(cx, cy, cz), emissive, ndcScale, modelScale);
    }

    /**
     * Native-resolution variant taking an explicit model-space centre anchor (replacing the
     * {@code bounds.centre()} default). Used by
     * {@link lib.minecraft.renderer.EntityRendererJava} to centre the silhouette on the canvas
     * by passing the model-space point whose iso projection equals the screen-space silhouette
     * midpoint - the {@code bounds.centre()} default over-pads non-brick-shaped silhouettes.
     */
    public static @NotNull EntityGeometryKit.BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Vector3f modelCentreAnchor,
        boolean emissive,
        float ndcScale,
        float modelScale
    ) {
        return buildTrianglesWithScale(model, texture, modelCentreAnchor, emissive, ndcScale, modelScale);
    }

    private static @NotNull EntityGeometryKit.BuildResult buildTrianglesWithScale(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Vector3f centre,
        boolean emissive,
        float scale,
        float modelScale
    ) {
        Map<String, Matrix4f> chainTransforms = buildChainTransforms(model.getBones());

        float cx = centre.x();
        float cy = centre.y();
        float cz = centre.z();

        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture.height());

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Map<String, Vector3f[]> boneBounds = new HashMap<>();

        for (Map.Entry<String, EntityModelData.Bone> boneEntry : model.getBones().entrySet()) {
            String boneName = boneEntry.getKey();
            EntityModelData.Bone bone = boneEntry.getValue();
            Matrix4f boneChain = chainTransforms.get(boneName);

            float bMinX = Float.POSITIVE_INFINITY, bMinY = Float.POSITIVE_INFINITY, bMinZ = Float.POSITIVE_INFINITY;
            float bMaxX = Float.NEGATIVE_INFINITY, bMaxY = Float.NEGATIVE_INFINITY, bMaxZ = Float.NEGATIVE_INFINITY;

            // Java's PartPose / ModelPart authoring stores cube origins LOCAL to the bone's
            // pivot (the literal addBox(x, y, z, w, h, d) args from createBodyLayer); Bedrock
            // stores them in absolute entity-root space. The Java pipeline's parser produces
            // local origins (to match what bytecode literally encodes); the kit translates by
            // the bone's absolute pivot here so the rendered cubes land in world space.
            // {@link #TRANSLATE_BY_PIVOT} can disable this for the iteration harness.
            Vector3f bonePivot = bone.getPivot();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                float inflate = cube.getInflate();

                float ox = TRANSLATE_BY_PIVOT ? bonePivot.x() + origin.x() : origin.x();
                float oy = TRANSLATE_BY_PIVOT ? bonePivot.y() + origin.y() : origin.y();
                float oz = TRANSLATE_BY_PIVOT ? bonePivot.z() + origin.z() : origin.z();
                Box cubeBounds = new Box(
                    ox - inflate, oy - inflate, oz - inflate,
                    ox + size.x() + inflate, oy + size.y() + inflate, oz + size.z() + inflate
                );

                Matrix4f fullTransform = composeCubeTransform(cube, bone, boneChain);

                boolean cubeCullBackFaces = shouldCullBackFaces(cube, size, texture, texW, texH);

                for (EntityFace face : EntityFace.values()) {
                    Vector3f[] corners = face.corners(cubeBounds);
                    for (int i = 0; i < 4; i++) {
                        Vector3f transformed = Vector3f.transform(corners[i], fullTransform);
                        float nx = (FLIP_X ? -1f : 1f) * (transformed.x() * modelScale - cx) * scale;
                        float ny = (FLIP_Y ? -1f : 1f) * (transformed.y() * modelScale - cy) * scale;
                        float nz = (FLIP_Z ? -1f : 1f) * (transformed.z() * modelScale - cz) * scale;
                        corners[i] = new Vector3f(nx, ny, nz);

                        bMinX = Math.min(bMinX, nx);
                        bMinY = Math.min(bMinY, ny);
                        bMinZ = Math.min(bMinZ, nz);
                        bMaxX = Math.max(bMaxX, nx);
                        bMaxY = Math.max(bMaxY, ny);
                        bMaxZ = Math.max(bMaxZ, nz);
                    }

                    Vector3f rawNormal = Vector3f.normalize(Vector3f.transformNormal(face.normal(), fullTransform));
                    Vector3f normal = new Vector3f(
                        (FLIP_NORMAL_X ? -1f : 1f) * rawNormal.x(),
                        (FLIP_NORMAL_Y ? -1f : 1f) * rawNormal.y(),
                        (FLIP_NORMAL_Z ? -1f : 1f) * rawNormal.z()
                    );
                    // The Y-flip applied to positions above (Y-down model frame -> Y-up screen
                    // frame) leaves UP and DOWN faces visually swapped: maxY-vertices of the cube
                    // (which {@link EntityFace#UP} indexes) now sit at the screen-bottom of the
                    // cube. Resolve UV against the OPPOSITE face for vertical faces so the
                    // texture region matches what's visually at that location.
                    EntityFace uvFace = switch (face) {
                        case UP -> EntityFace.DOWN;
                        case DOWN -> EntityFace.UP;
                        default -> face;
                    };
                    Vector2f[] uv = resolveFaceUv(uvFace, cube, size, texW, texH);

                    // The Y-flip on positions interacts differently with each face's UV mapping:
                    //
                    // <ul>
                    //   <li><b>SIDE faces</b> (NORTH / SOUTH / EAST / WEST): V is along world Y,
                    //       so the position Y-flip mirrors V across the face mid-line. Counteract
                    //       with a V-flip permutation {@code [1, 0, 3, 2]} (TL↔BL, BR↔TR) so
                    //       each visual-position vertex pairs with the right texture corner.</li>
                    //   <li><b>UP / DOWN faces</b>: V is along world Z (not Y) so the Y-flip
                    //       doesn't mirror V. But {@link EntityFace#corners} walks the cube top in
                    //       {@code (NW, SW, SE, NE)} order while vanilla MC's TOP-face UV unwrap
                    //       places its TL at the SE corner - net mismatch is a U-flip across the
                    //       face. Counteract with {@code [3, 2, 1, 0]} (TL↔TR, BL↔BR). Without
                    //       this the top-of-head and back-of-body textures rendered east-west
                    //       flipped (eg. "dark splat that should sit under the hair" appearing
                    //       on the wrong end of the cube top).</li>
                    // </ul>
                    Vector2f[] effUv = (face == EntityFace.UP || face == EntityFace.DOWN)
                        ? new Vector2f[]{ uv[3], uv[2], uv[1], uv[0] }
                        : new Vector2f[]{ uv[1], uv[0], uv[3], uv[2] };

                    // Bake vanilla's {@code Lighting.ENTITY_IN_UI} shade factor (two-directional
                    // Lambertian with ambient floor) into the triangle so the rasterizer applies
                    // it directly without a second per-face lookup. Computed against the post-flip
                    // screen-frame normal because {@link RenderEngine#ENTITY_IN_UI_LIGHT_0} and
                    // {@link RenderEngine#ENTITY_IN_UI_LIGHT_1} are likewise expressed in screen
                    // Y-up - vanilla's source values are Y-down, but we Y-flip both sides of the
                    // dot product (lights and normal) which leaves the result identical and lets
                    // every kit and renderer downstream of this point reason in one consistent
                    // frame. The continuous (rather than per-face-bucketed) result matters for
                    // bones whose rotation produces non-cardinal normals (running zombie legs,
                    // bee leashes) where a {@link BlockFace#fromNormal} approximation would
                    // collapse adjacent faces to the same shade.
                    float shading = RenderEngine.computeEntityInUiLighting(normal);
                    // Natural CCW emission {@code (0, 1, 2)} and {@code (0, 2, 3)}. Total pipeline
                    // chirality: kit FLIP_Y (det -1) × engine_camera (det -1 due to entity iso's
                    // trailing Y-flip for projection-convention compensation) × projection's -y
                    // (det -1) = det -1. Model CCW → screen CW → rasterizer's
                    // {@code signedArea < 0} check correctly classifies these as front-facing.
                    // <p>
                    // The earlier reversed emission {@code (0, 2, 1)} / {@code (0, 3, 2)} was
                    // designed for the OLD block-iso engine_camera (pure rotation det +1) where
                    // the kit's Y-flip alone reversed chirality. With the entity iso's det=-1
                    // engine_camera, the kit's Y-flip no longer needs winding compensation -
                    // emission stays natural CCW.
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[1], corners[2],
                        effUv[0], effUv[1], effUv[2],
                        texture, ColorMath.WHITE,
                        normal, shading,
                        cubeCullBackFaces, emissive
                    ));
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[2], corners[3],
                        effUv[0], effUv[2], effUv[3],
                        texture, ColorMath.WHITE,
                        normal, shading,
                        cubeCullBackFaces, emissive
                    ));
                }
            }

            if (bMinX != Float.POSITIVE_INFINITY)
                boneBounds.put(boneName, new Vector3f[]{
                    new Vector3f(bMinX, bMinY, bMinZ),
                    new Vector3f(bMaxX, bMaxY, bMaxZ)
                });
        }

        return new EntityGeometryKit.BuildResult(triangles, boneBounds);
    }

    /**
     * Computes the AABB of an entity model in the Java-native Y-down frame, after applying each
     * bone's ancestor anchor chain.
     */
    public static @NotNull Box computeBounds(@NotNull EntityModelData model) {
        return computeBounds(model, buildChainTransforms(model.getBones()));
    }

    /**
     * Walks every cube of every bone, projects each cube's 8 corners through the bone chain,
     * the per-render {@code modelScale}, and the supplied screen-space transform, and returns
     * the tight screen-space AABB of the rendered silhouette.
     *
     * <p>Used by {@link lib.minecraft.renderer.EntityRendererJava#render render()} to size the
     * output canvas. The 8-corners-of-the-outer-AABB approach the previous implementation used
     * over-estimates the silhouette for non-brick-shaped entities: the cod's body extends 7
     * pixels in Z while the model's outer Z extent (with tail_fin and head) reaches 15, so the
     * AABB-projection padded the canvas by ~25% beyond what the actual cubes occupy on screen.
     * This walker mirrors the vanilla-reference-harness's per-cube vertex measurement so the
     * two pipelines size their canvases off the same metric.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param screenTransform model-to-screen transform to apply BEFORE bounds accumulation;
     *     callers compose iso rotation + any chirality / flips here so the result lives in
     *     screen space (X = horizontal, Y = vertical, Z = depth - ignored)
     * @param modelScale per-entity scale applied to every cube vertex before the screen
     *     transform; pass 1 when no per-renderer scale is in effect
     * @return tight screen-space bounds; the X and Y extents drive canvas sizing, the Z extent
     *     is depth and not consumed by the canvas-fit math
     */
    public static @NotNull Box computeScreenBounds(
        @NotNull EntityModelData model,
        @NotNull Matrix4f screenTransform,
        float modelScale
    ) {
        Map<String, Matrix4f> chainTransforms = buildChainTransforms(model.getBones());
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (Map.Entry<String, EntityModelData.Bone> entry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            Matrix4f boneChain = chainTransforms.get(entry.getKey());
            Vector3f bonePivot = bone.getPivot();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                float inflate = cube.getInflate();
                Matrix4f cubeTransform = composeCubeTransform(cube, bone, boneChain);

                float ox = TRANSLATE_BY_PIVOT ? bonePivot.x() + origin.x() : origin.x();
                float oy = TRANSLATE_BY_PIVOT ? bonePivot.y() + origin.y() : origin.y();
                float oz = TRANSLATE_BY_PIVOT ? bonePivot.z() + origin.z() : origin.z();
                float[] xs = { ox - inflate, ox + size.x() + inflate };
                float[] ys = { oy - inflate, oy + size.y() + inflate };
                float[] zs = { oz - inflate, oz + size.z() + inflate };

                for (float x : xs) for (float y : ys) for (float z : zs) {
                    Vector3f cubeSpace = Vector3f.transform(new Vector3f(x, y, z), cubeTransform);
                    Vector3f scaled = new Vector3f(cubeSpace.x() * modelScale, cubeSpace.y() * modelScale, cubeSpace.z() * modelScale);
                    Vector3f screen = Vector3f.transform(scaled, screenTransform);
                    if (screen.x() < minX) minX = screen.x();
                    if (screen.x() > maxX) maxX = screen.x();
                    if (screen.y() < minY) minY = screen.y();
                    if (screen.y() > maxY) maxY = screen.y();
                    if (screen.z() < minZ) minZ = screen.z();
                    if (screen.z() > maxZ) maxZ = screen.z();
                }
            }
        }

        if (minX == Float.POSITIVE_INFINITY)
            return new Box(0f, 0f, 0f, 0f, 0f, 0f);

        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Builds a Matrix4f that maps a vertex in the entity's working pixel-unit frame
     * (post-bone-chain, post-pivot-translation, pre-rasterizer) into the entity-fit space
     * shared with {@link #buildTriangles}'s output. The transform is
     * {@code (v - center) * scale} on each axis, with {@link #FLIP_Y} (and {@link #FLIP_X} /
     * {@link #FLIP_Z}) applied.
     *
     * <p>Used by {@link lib.minecraft.renderer.EntityRendererJava} to project block-model
     * overlay triangles (mooshroom mushroom blocks, etc) into the same entity-fit frame the
     * primary entity geometry has been baked into so they render at the correct scale and
     * orientation alongside the entity body.
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Box bounds) {
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        return buildEntityFitMatrix(bounds, ENTITY_MODEL_FIT_EXTENT / extent);
    }

    /**
     * Native-resolution variant of {@link #buildEntityFitMatrix(Box)}: caller supplies the
     * model-units-to-NDC scale so block overlays composed onto an entity's frame match the same
     * scale the renderer used for the entity body (no auto-fit).
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Box bounds, float ndcScale) {
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildEntityFitMatrix(new Vector3f(cx, cy, cz), ndcScale);
    }

    /**
     * Native-resolution variant taking an explicit model-space centre anchor. Used by
     * {@link lib.minecraft.renderer.EntityRendererJava} so block overlays composite at the
     * same silhouette-centred frame the entity body uses (the
     * {@link #buildTriangles(EntityModelData, PixelBuffer, Vector3f, boolean, float, float)
     * Vector3f overload} above).
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Vector3f modelCentre, float ndcScale) {
        Matrix4f translateToCentre = Matrix4f.createTranslation(-modelCentre.x(), -modelCentre.y(), -modelCentre.z());
        Matrix4f scaleAndFlip = Matrix4f.createScale(
            FLIP_X ? -ndcScale : ndcScale,
            FLIP_Y ? -ndcScale : ndcScale,
            FLIP_Z ? -ndcScale : ndcScale
        );
        return translateToCentre.multiply(scaleAndFlip);
    }

    /**
     * Resolves a bone's world transform (the ancestor-chain anchor used internally by
     * {@link #buildTriangles}). Returns identity when the bone is absent. Used by
     * {@link lib.minecraft.renderer.EntityRendererJava} to anchor a block-overlay's transform
     * chain to a specific entity bone (mooshroom's third mushroom which sits on the head).
     */
    public static @NotNull Matrix4f resolveBoneAnchorMatrix(
        @NotNull EntityModelData model,
        @NotNull String boneName
    ) {
        return buildChainTransforms(model.getBones()).getOrDefault(boneName, Matrix4f.IDENTITY);
    }

    /**
     * Returns the bone's pivot point in the entity's working frame for callers that need to
     * pre-translate a block overlay relative to the bone's anchor. {@link Vector3f#ZERO} when
     * the bone is absent.
     */
    public static @NotNull Vector3f resolveBonePivot(
        @NotNull EntityModelData model,
        @NotNull String boneName
    ) {
        EntityModelData.Bone bone = model.getBones().get(boneName);
        return bone != null ? bone.getPivot() : Vector3f.ZERO;
    }

    private static @NotNull Box computeBounds(
        @NotNull EntityModelData model,
        @NotNull Map<String, Matrix4f> chainTransforms
    ) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (Map.Entry<String, EntityModelData.Bone> entry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            Matrix4f boneChain = chainTransforms.get(entry.getKey());
            // Same pivot-translation as in {@link #buildTriangles}.
            Vector3f bonePivot = bone.getPivot();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                float inflate = cube.getInflate();
                Matrix4f fullTransform = composeCubeTransform(cube, bone, boneChain);

                float ox = TRANSLATE_BY_PIVOT ? bonePivot.x() + origin.x() : origin.x();
                float oy = TRANSLATE_BY_PIVOT ? bonePivot.y() + origin.y() : origin.y();
                float oz = TRANSLATE_BY_PIVOT ? bonePivot.z() + origin.z() : origin.z();
                float[] xs = { ox - inflate, ox + size.x() + inflate };
                float[] ys = { oy - inflate, oy + size.y() + inflate };
                float[] zs = { oz - inflate, oz + size.z() + inflate };

                for (float x : xs) for (float y : ys) for (float z : zs) {
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

        if (minX == Float.POSITIVE_INFINITY)
            return new Box(0f, 0f, 0f, 0f, 0f, 0f);

        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Builds the ancestor-anchor chain matrix for every bone. See {@link EntityGeometryKit#buildChainTransforms}. */
    private static @NotNull Map<String, Matrix4f> buildChainTransforms(
        @NotNull Map<String, EntityModelData.Bone> bones
    ) {
        Map<String, Matrix4f> cache = new HashMap<>();
        for (String name : bones.keySet())
            resolveChain(name, bones, cache, new LinkedHashSet<>());
        return cache;
    }

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

    private static @NotNull Matrix4f buildAnchor(@NotNull EntityModelData.Bone bone) {
        EulerRotation rotation = bone.getRotation();
        if (rotation.pitch() == 0f && rotation.yaw() == 0f && rotation.roll() == 0f)
            return Matrix4f.IDENTITY;
        return pivotCenteredRotation(bone.getPivot(), rotation);
    }

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

    private static boolean isZero(@NotNull EulerRotation r) {
        return r.pitch() == 0f && r.yaw() == 0f && r.roll() == 0f;
    }

    /**
     * Java-frame {@code T(-pivot) * R(rotation) * T(+pivot)} matrix.
     * <p>
     * <b>Rotation composition:</b> {@code R = R_X * R_Y * R_Z} in our row-vector convention,
     * so {@code v_row * R} applies X first, then Y, then Z. This mirrors vanilla
     * {@code ModelPart.translateAndRotate} which calls {@code mulPose(Z); mulPose(Y); mulPose(X)}
     * on the column-vector pose stack - the column composite {@code R_Z * R_Y * R_X} also has
     * {@code R_X} innermost (applied first to v_col).
     * <p>
     * <b>Sign convention:</b> Java's {@code +xRot} (pitch) tilts a bone forward, {@code +yRot}
     * (yaw) turns right, {@code +zRot} (roll) rolls right, applied directly with no negation -
     * this is the deliberate inverse of the bedrock-side {@link EntityGeometryKit} convention
     * which negates pitch and roll to reach the same visual from Y-up source data.
     */
    private static @NotNull Matrix4f pivotCenteredRotation(
        @NotNull Vector3f pivot,
        @NotNull EulerRotation rotation
    ) {
        Matrix4f toPivot = Matrix4f.createTranslation(pivot.negate());
        Matrix4f fromPivot = Matrix4f.createTranslation(pivot);
        Matrix4f rot = Matrix4f.createRotationX(rotation.pitchRadians())
            .multiply(Matrix4f.createRotationY(rotation.yawRadians()))
            .multiply(Matrix4f.createRotationZ(rotation.rollRadians()));
        return toPivot.multiply(rot).multiply(fromPivot);
    }

    /** UV resolution - identical to {@link EntityGeometryKit#resolveFaceUv} since UV is frame-agnostic. */
    private static @NotNull Vector2f @NotNull [] resolveFaceUv(
        @NotNull EntityFace face,
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        float texWidth,
        float texHeight
    ) {
        EntityModelData.FaceUv override = cube.getFaceUv().get(face.direction());
        Vector4f rect;
        if (override == null) {
            rect = face.defaultUv(cube.getUv(), size);
        } else {
            Vector2f uv = override.getUv();
            Vector2f uvSize = override.getUvSize();
            rect = new Vector4f(uv.x(), uv.y(), uv.x() + uvSize.x(), uv.y() + uvSize.y());
        }
        return rect.toUvCorners(texWidth, texHeight, 0, cube.isMirror());
    }

    /**
     * Back-face culling heuristic. Plane cubes (any size component equal to zero - e.g.
     * tadpole tail, top fins, warden tendrils) disable culling so both sides render -
     * vanilla treats these as double-sided geometry, and with the entity-iso det=-1 chain
     * one side would otherwise get culled while the other side often samples transparent
     * texture pixels. Solid cubes use the content-based heuristic
     * (identical to {@link EntityGeometryKit#shouldCullBackFaces}).
     */
    private static boolean shouldCullBackFaces(
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        @NotNull PixelBuffer texture,
        float texW,
        float texH
    ) {
        if (size.x() == 0f || size.y() == 0f || size.z() == 0f) return false;
        boolean visibleHasContent =
               uvHasContent(resolveFaceUv(EntityFace.UP, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.NORTH, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.EAST, cube, size, texW, texH), texture);
        if (visibleHasContent) return true;
        boolean hiddenHasContent =
               uvHasContent(resolveFaceUv(EntityFace.DOWN, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.SOUTH, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.WEST, cube, size, texW, texH), texture);
        return !hiddenHasContent;
    }

    private static boolean uvHasContent(
        @NotNull Vector2f @NotNull [] uv,
        @NotNull PixelBuffer texture
    ) {
        int W = texture.width();
        int H = texture.height();
        Vector4f bounds = Vector4f.bounds(uv);
        int x0 = Math.max(0, (int) Math.floor(bounds.x() * W));
        int y0 = Math.max(0, (int) Math.floor(bounds.y() * H));
        int x1 = Math.min(W, (int) Math.ceil(bounds.z() * W));
        int y1 = Math.min(H, (int) Math.ceil(bounds.w() * H));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (ColorMath.alpha(texture.getPixel(x, y)) > 0) return true;
            }
        }
        return false;
    }

    // BuildResult record is reused from EntityGeometryKit so callers can switch kits without
    // type changes; both kits produce identical output shape.

}
