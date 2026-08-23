package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.face.CornerPhase;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared relative bone-hierarchy composition - vanilla's {@code ModelPart.translateAndRotate} chain
 * math in vanilla's native Y-down frame, frame-agnostic and reusable. Both {@link EntityGeometryKit}
 * and {@link BlockGeometryKit} compose a relative bone tree (parent {@literal ->} child
 * {@code base * T(pivot) * R}) through this one implementation rather than duplicating the fluent
 * {@link Matrix4f#translate} / {@link Matrix4f#rotate} sequence, whose float result is
 * op-order-sensitive.
 * <p>
 * This class holds the shared bone/cube geometry primitives both kits drive: the per-bone
 * ancestor-anchor matrices ({@link #buildChainTransforms}), the per-cube composed transform
 * ({@link #composeCubeTransform}), the scaled-inflate cube bounds ({@link #scaledCubeBounds}), and
 * the atlas-UV unwrap + plane-degeneracy rules ({@link #resolvePolygonUv} /
 * {@link #isDegeneratePlaneFace}). Each consumer supplies its own frame-specific emit stage (the
 * entity fit + Y-flip + entity lighting, or the block {@code /16 - 0.5} normalization + inventory
 * shade); nothing frame-specific lives here.
 */
@UtilityClass
public class BoneKit {

    /**
     * Builds the ancestor-anchor chain matrix for every bone starting from identity. Thin wrapper
     * over {@link #buildChainTransformsFrom} with {@link Matrix4f#IDENTITY} as the base.
     *
     * @param bones the model's bones keyed by name
     * @return each bone's ancestor-anchor chain matrix keyed by bone name
     */
    public static @NotNull Map<String, Matrix4f> buildChainTransforms(
        @NotNull Map<String, EntityModelData.Bone> bones
    ) {
        return buildChainTransformsFrom(Matrix4f.IDENTITY, bones);
    }

    /**
     * Builds the ancestor-anchor chain matrix for every bone starting from a non-identity base
     * matrix (typically the kit-fit chain). Each bone's chain is built by replaying its ancestor
     * pivot-centred rotations as fluent {@link Matrix4f#translate} + {@link Matrix4f#rotate}
     * post-multiplies on top of {@code base}, matching vanilla's {@code PoseStack} chain
     * bit-for-bit. Eliminates the {@code base.multiply(boneChain)} step at the per-cube loop that
     * drifts 1-4 ULPs versus the fluent path.
     *
     * @param base the base matrix each bone's chain builds on (identity, or the kit-fit matrix)
     * @param bones the model's bones keyed by name
     * @return each bone's ancestor-anchor chain matrix keyed by bone name
     */
    public static @NotNull Map<String, Matrix4f> buildChainTransformsFrom(
        @NotNull Matrix4f base,
        @NotNull Map<String, EntityModelData.Bone> bones
    ) {
        Map<String, Matrix4f> cache = new HashMap<>();
        for (String name : bones.keySet())
            resolveChainFrom(name, bones, cache, new LinkedHashSet<>(), base);
        return cache;
    }

    /**
     * Builds one named bone's ancestor-anchor chain matrix, for the callers that want a single bone
     * rather than the whole map. Walks that bone's parent chain alone through the same recursion
     * {@link #buildChainTransforms} drives every bone through, in the same operand order, so the two
     * agree bit for bit on any bone forest - a chain is a function of its own ancestors, and the
     * map's memoization only skips recomputation rather than changing a value. The one case they can
     * part is a parent <b>cycle</b>, where the map's answer depends on which member the bone
     * iteration reached first and this walk always breaks at the bone asked for; vanilla-derived
     * geometry is a tree, so nothing shipped can tell them apart.
     *
     * @param bones the model's bones keyed by name
     * @param name the bone whose chain to build
     * @return the bone's ancestor-anchor chain matrix, or {@link Matrix4f#IDENTITY} when absent
     */
    public static @NotNull Matrix4f buildChainTransform(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull String name
    ) {
        return resolveChainFrom(name, bones, new HashMap<>(), new LinkedHashSet<>(), Matrix4f.IDENTITY);
    }

    /**
     * Builds one bone's chain matrix starting from {@code root} via fluent {@link Matrix4f#translate}
     * / {@link Matrix4f#rotate} ops. Recurses into the parent chain first, then applies this bone's
     * pivot-centred rotation on top; memoizes into {@code cache}. Self-parenting, missing-parent,
     * and cyclic references degrade to the bone's own rotation on {@code root}.
     *
     * @param name the bone whose chain to resolve
     * @param bones the model's bones keyed by name
     * @param cache the memoization cache of already-resolved chains
     * @param visiting the current recursion path, guarding against parent cycles
     * @param root the base matrix the chain builds on (identity, or the kit-fit matrix)
     * @return the bone's ancestor-anchor chain matrix built on {@code root}
     */
    private static @NotNull Matrix4f resolveChainFrom(
        @NotNull String name,
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull Map<String, Matrix4f> cache,
        @NotNull Set<String> visiting,
        @NotNull Matrix4f root
    ) {
        Matrix4f cached = cache.get(name);
        if (cached != null) return cached;
        EntityModelData.Bone bone = bones.get(name);
        if (bone == null) return root;
        if (visiting.contains(name)) return applyBonePose(root, bone);
        visiting.add(name);

        String parent = bone.getParent();
        Matrix4f base;
        if (parent == null || parent.equals(name) || !bones.containsKey(parent)) {
            base = root;
        } else {
            base = resolveChainFrom(parent, bones, cache, visiting, root);
        }
        Matrix4f composed = applyBonePose(base, bone);

        visiting.remove(name);
        cache.put(name, composed);
        return composed;
    }

    /**
     * Composes the full cube-to-working-frame transform: the bone's ancestor chain, then the bone's
     * bind-pose rotation, then the cube's own rotation, each applied pivot-centred. When neither the
     * cube nor the bind pose rotates, returns {@code boneChain} unchanged so rotation-free cubes
     * carry zero extra rounding.
     *
     * @param cube the cube whose transform to compose
     * @param bone the owning bone (source of pivot and bind-pose rotation)
     * @param boneChain the bone's pre-resolved ancestor-anchor chain
     * @return the composed cube transform in the working frame
     */
    public static @NotNull Matrix4f composeCubeTransform(
        @NotNull EntityModelData.Cube cube,
        @NotNull EntityModelData.Bone bone,
        @NotNull Matrix4f boneChain
    ) {
        EulerRotation cubeRot = cube.getRotation();
        EulerRotation bindPose = bone.getBindPoseRotation();
        boolean hasCube = !isZero(cubeRot);
        boolean hasBind = !isZero(bindPose);
        if (!hasCube && !hasBind) return boneChain;

        // Cube rotation applies first to the vertex, then the bone's bind pose, then the bone
        // chain. Each fluent post-multiply mirrors vanilla's PoseStack.translate/mulPose/translate
        // sequence, so the chain composes as `boneChain * bindPose * cubeRot` with cubeRot
        // innermost (rightmost) on a column vector while staying bit-identical to JOML.
        // <p>
        // bindPose uses the BONE pivot in BONE-LOCAL coords (vanilla applies bind around the
        // bone's local frame, same as the bone's own rotation); cube rotation uses the CUBE's
        // bone-local pivot anchor. Both go through {@link #applyCubePivotCenteredRotation}
        // (T(+p)*R*T(-p) shape) because they rotate around an anchor while the surrounding
        // chain is already in bone-local frame.
        Matrix4f acc = boneChain;
        if (hasBind) acc = applyCubePivotCenteredRotation(acc, bone.getPivot(), bindPose);
        if (hasCube) acc = applyCubePivotCenteredRotation(acc, cube.getPivot(), cubeRot);
        return acc;
    }

    /**
     * Tests whether a rotation is the identity (all three Euler angles exactly zero), letting
     * callers skip quaternion construction and matrix ops for translation-only bones/cubes.
     *
     * @param r the rotation to test
     * @return {@code true} when pitch, yaw, and roll are all zero
     */
    private static boolean isZero(@NotNull EulerRotation r) {
        return r.pitch() == 0f && r.yaw() == 0f && r.roll() == 0f;
    }

    /**
     * Returns {@code base * T(pivot) * R} - vanilla's bone-level PoseStack shape (no un-translate).
     * Matches {@code pose.translate(pivot); pose.mulPose(quat)} bit-for-bit.
     * <p>
     * Used for the bone hierarchy chain where cube origins are stored in BONE-LOCAL coordinates
     * (relative to the bone's own pivot, matching vanilla {@code ModelPart.Cube}'s
     * {@code posX1..posZ2} bone-local fields). The pre-translate by bone pivot happens once inside
     * this method (the fluent {@code .translate(p)} call). The rotation is built from a
     * {@link Quaternionf#rotationZYX} quaternion so the result is bit-identical to vanilla's
     * {@code mulPose(new Quaternionf().rotationZYX(zRot, yRot, xRot))} (pitch X first, then yaw Y,
     * then roll Z), with no negation since the frame is vanilla Java's native Y-down.
     *
     * @param base the chain matrix to post-multiply onto
     * @param pivot the bone pivot in the parent frame (skipped when zero)
     * @param rotation the bone rotation (skipped when identity)
     * @return {@code base * T(pivot) * R}, or {@code base} unchanged when both are trivial
     */
    /**
     * The bone's own step of the chain: its pivot, its rotation, then what a clip scales it by.
     *
     * <p>{@code T * R * S}, which is vanilla's order in {@code ModelPart.translateAndRotate} - the
     * scale goes on the stack AFTER the rotation, so it reaches this bone's cubes and every
     * descendant's alike. That propagation is the whole reason it belongs here rather than beside
     * the uniform factor {@link #scaledCubeBounds} applies: that one the tooling already flattened
     * onto every bone of its mesh, so putting it on the chain would apply it once per level.
     *
     * <p>Skipped whole when the bone stands at no displacement, which is every bone of every mesh
     * that is not being posed - so a still render composes the matrix it always did.
     *
     * @param base the chain matrix to post-multiply onto
     * @param bone the bone whose step this is
     * @return the chain with this bone's step applied
     */
    private static @NotNull Matrix4f applyBonePose(
        @NotNull Matrix4f base, @NotNull EntityModelData.Bone bone) {

        Matrix4f chain = applyBoneRotation(base, bone.getPivot(), bone.getRotation());
        if (!bone.isPoseScaled()) return chain;
        Vector3f scale = bone.getPoseScale();
        return chain.scale(scale.x(), scale.y(), scale.z());
    }

    private static @NotNull Matrix4f applyBoneRotation(
        @NotNull Matrix4f base,
        @NotNull Vector3f pivot,
        @NotNull EulerRotation rotation
    ) {
        boolean hasPivot = pivot.x() != 0f || pivot.y() != 0f || pivot.z() != 0f;
        boolean hasRot = !isZero(rotation);
        if (!hasPivot && !hasRot) return base;
        Matrix4f chain = hasPivot ? base.translate(pivot.x(), pivot.y(), pivot.z()) : base;
        if (hasRot) {
            Quaternionf quat = Quaternionf.rotationZYX(
                rotation.rollRadians(), rotation.yawRadians(), rotation.pitchRadians()
            );
            chain = chain.rotate(quat);
        }
        return chain;
    }

    /**
     * Returns {@code base * T(+pivot) * R * T(-pivot)} - cube-level pivot-centred rotation shape,
     * where the cube rotates around its own anchor point in the bone's frame. Used by the cube-level
     * rotation in {@link #composeCubeTransform} (donkey/mule ears, etc.) where the cube has its own
     * rotation independent of the bone's rotation.
     * <p>
     * Cube pivots are in BONE-LOCAL coordinates (relative to the bone's own pivot), matching the
     * vanilla convention. With bone chain {@code T(p)*R_bone} (vanilla shape) and cube applied as
     * {@code T(+cp)*R_cube*T(-cp)} on top, the composed transform applied to a bone-local cube vertex
     * {@code v_local} produces {@code R_bone * (R_cube * (v_local - cp) + cp) + p} - matching
     * vanilla's bone hierarchy + cube pivot semantics exactly.
     *
     * @param base the chain matrix to post-multiply onto
     * @param pivot the rotation anchor in bone-local coordinates
     * @param rotation the rotation to apply about {@code pivot} (skipped when identity)
     * @return {@code base * T(+pivot) * R * T(-pivot)}, or {@code base} unchanged when
     *     {@code rotation} is identity
     */
    private static @NotNull Matrix4f applyCubePivotCenteredRotation(
        @NotNull Matrix4f base,
        @NotNull Vector3f pivot,
        @NotNull EulerRotation rotation
    ) {
        if (isZero(rotation)) return base;
        Quaternionf quat = Quaternionf.rotationZYX(
            rotation.rollRadians(), rotation.yawRadians(), rotation.pitchRadians()
        );
        return base
            .translate(pivot.x(), pivot.y(), pivot.z())
            .rotate(quat)
            .translate(-pivot.x(), -pivot.y(), -pivot.z());
    }

    /**
     * Computes a cube's axis-aligned bounds in bone-local pixel space: the cube origin, size and grow
     * each scaled by the owning bone's uniform {@code scale}, assembled by {@link Box#grown} in
     * vanilla's own operand order. This method owns the <em>scaling</em> - per operand, so the identity
     * scale cannot round - and {@link Box#grown} owns the growth, which is what keeps this walk and a
     * worn shell's rows on one expression. Shared by both kits' per-cube emit loops (the block
     * {@code /16 - 0.5} normalization and the entity fit / measure passes). The grow expands the corner
     * box only; the {@code size}-derived UV footprint is untouched
     * ({@link EntityModelData.Cube#getGrow()}). A scalar {@code inflate} degenerates to an equal grow
     * on all three axes.
     *
     * @param scale the owning bone's uniform scale (vanilla {@code PartPose.scaled} /
     *     {@code MeshTransformer.scaling})
     * @param cube the cube whose bounds to compute
     * @return the scaled, grown cube bounds in bone-local coordinates
     */
    public static @NotNull Box scaledCubeBounds(float scale, @NotNull EntityModelData.Cube cube) {
        return Box.grown(
            cube.getOrigin().multiply(scale),
            cube.getSize().multiply(scale),
            cube.getGrow().multiply(scale));
    }

    /**
     * Resolves the raw four-corner UV rectangle for one cube face in atlas-position order
     * ({@code TL, BL, BR, TR}). Uses the cube's per-face UV override when present, otherwise
     * derives the rectangle from the atlas layout via {@link Unwrap.Atlas#rect}. Forwards the
     * cube's {@code mirror} flag to {@link Vector4f#toUvCorners} for the U-flip.
     *
     * @param face the geometric face being resolved
     * @param cube the cube whose UV is being resolved
     * @param size the cube's size vector
     * @param texWidth the texture width
     * @param texHeight the texture height
     * @return the four UV corners in atlas-position order (top-left, bottom-left, bottom-right,
     *     top-right)
     */
    static @NotNull Vector2f @NotNull [] resolveFaceUv(
        @NotNull Face face,
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        float texWidth,
        float texHeight
    ) {
        EntityModelData.FaceUv override = cube.getFaceUv().get(face.direction());
        Vector4f rect;
        if (override == null) {
            rect = new Unwrap.Atlas(cube.getUv(), size, cube.isMirror()).rect(face);
        } else {
            Vector2f uv = override.getUv();
            Vector2f uvSize = override.getUvSize();
            rect = new Vector4f(uv.x(), uv.y(), uv.x() + uvSize.x(), uv.y() + uvSize.y());
        }
        return rect.toUvCorners(texWidth, texHeight, 0, cube.isMirror());
    }

    /**
     * Resolves the per-vertex UV array for one polygon, including mirror handling and the
     * vanilla-spec slot permutation. The output is indexed in the kit's corner order
     * ({@link CornerPhase#POLYGON}) so each {@code corners[i]} pairs with the UV vanilla's
     * cube ctor assigns to the same world-space vertex.
     * <p>
     * For {@code cube.isMirror()} cubes, vanilla's {@code ModelPart.Cube} ctor swaps the cube's
     * {@code x} and {@code maxX} variables before building the 8 vertices, which has the net
     * effect of swapping which UV strip is applied to the cube's +X vs -X face (vanilla's WEST
     * polygon UV ends up on the +X face, EAST polygon UV on the -X face). The polygon ctor also
     * reverses each polygon's vertex array, which U-flips every face's UV mapping. Both effects
     * are replicated for {@code mirror=true} cubes via {@link Turn#MIRROR_X} and the
     * {@link Vector4f#toUvCorners} mirror flag inside {@link #resolveFaceUv}.
     * <p>
     * The per-face slot permutation maps {@link #resolveFaceUv}'s {@code (TL, BL, BR, TR)}
     * output to the (max-u, top-v)-first ordering vanilla's {@code Polygon} ctor produces. For
     * non-UP faces, vanilla's vertex 0 lands in the TR slot; for UP, it lands in BR because the
     * polygon ctor's {@code f3 / f5} parameters are V-inverted on the atlas strip. The exact
     * slot mapping per face lives on {@link CornerPhase#uvSlots} and is applied via
     * {@link CornerPhase#permuteUv} so both kits share the same source of truth.
     * <p>
     * Independent of the kit's permanent Y-flip on positions: that flip changes where vertices project to
     * screen, but each vertex's vanilla-spec UV is unchanged.
     *
     * @param face the geometric face being rendered
     * @param cube the cube whose UV is being resolved
     * @param size the cube's size vector
     * @param texWidth the texture width
     * @param texHeight the texture height
     * @return the four per-vertex UVs in the kit's corner order
     */
    public static @NotNull Vector2f @NotNull [] resolvePolygonUv(
        @NotNull Face face,
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        float texWidth,
        float texHeight
    ) {
        Face strip = cube.isMirror() ? Turn.MIRROR_X.apply(face) : face;
        Vector2f[] uv = resolveFaceUv(strip, cube, size, texWidth, texHeight);
        return CornerPhase.POLYGON.permuteUv(face, uv);
    }

    /**
     * Tests whether a plane cube's face polygon is degenerate - its 4 vertices collapse to 2
     * distinct points because the face's plane normal lies along the cube's zero-extent axis.
     * <p>
     * E.g. for a vertical-plane top_fin ({@code size.x=0}), the UP/DOWN/NORTH/SOUTH faces all
     * collapse - only WEST/EAST have full area. Vanilla emits these polygons too but the GPU
     * rasterizer drops them at 0-area; ours rasterizes a thin line worth a few pixels due to FP
     * error in the barycentric inside-test, then paints wrong-shade artifact pixels (cod top_fin
     * UP painted x=133-135 strip at shade 1.0 over the body's WEST shade 0.45). Caller uses this
     * predicate to skip emitting these triangles entirely.
     *
     * <p>
     * The test is on the two axes the face SPANS rather than on the first zero extent found, so a cube
     * flat on two axes answers for all six faces. A {@code (0, 0, 8)} cube is a line along Z: reading
     * only the first zero would call its WEST and EAST faces full-area, when a face spanning Y and Z
     * has no more area than one spanning X and Y. No shipped cube has two zero extents, so this differs
     * from a first-zero reading on nothing the renderer draws today.
     *
     * @param size the cube's size vector
     * @param face the geometric face being rendered
     * @return {@code true} if the polygon collapses to a line; {@code false} when the face has
     *     full plane area
     */
    public static boolean isDegeneratePlaneFace(@NotNull Vector3f size, @NotNull Face face) {
        int axis = face.axis();
        if (axis != 0 && size.x() == 0f) return true;
        if (axis != 1 && size.y() == 0f) return true;
        return axis != 2 && size.z() == 0f;
    }

    /**
     * Returns whether any texel under a face's UV rectangle is partially transparent
     * ({@code 0 < alpha < 255}) - the signal vanilla uses to route a surface to a translucent
     * (back-to-front sorted) pass rather than the plain emission-order rasterization opaque and
     * pure-cutout ({@code alpha == 0}) faces take. Shared by both kits' per-face translucent
     * detection: it walks the {@code [floor(min), ceil(max))} texel box of the UV corners' bounding
     * rectangle ({@link Vector4f#bounds}) and stops at the first partial-alpha sample.
     *
     * @param uv the face's four UV corners in {@code [0, 1]} space
     * @param texture the texture the face samples
     * @return {@code true} when any covered texel has partial alpha
     */
    public static boolean faceHasPartialAlpha(@NotNull Vector2f @NotNull [] uv, @NotNull PixelBuffer texture) {
        int w = texture.width();
        int h = texture.height();
        Vector4f bounds = Vector4f.bounds(uv);
        int x0 = Math.max(0, (int) Math.floor(bounds.x() * w));
        int y0 = Math.max(0, (int) Math.floor(bounds.y() * h));
        int x1 = Math.min(w, (int) Math.ceil(bounds.z() * w));
        int y1 = Math.min(h, (int) Math.ceil(bounds.w() * h));
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int a = ColorMath.alpha(texture.getPixel(x, y));
                if (a > 0 && a < 255) return true;
            }
        return false;
    }

}
