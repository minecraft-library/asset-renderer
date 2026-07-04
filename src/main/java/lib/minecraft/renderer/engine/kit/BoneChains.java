package lib.minecraft.renderer.engine.kit;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
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
 * This class produces only the per-bone ancestor-anchor matrices ({@link #buildChainTransforms}) and
 * the per-cube composed transform ({@link #composeCubeTransform}). Each consumer supplies its own
 * frame-specific emit stage (the entity fit + Y-flip + entity lighting, or the block
 * {@code /16 - 0.5} normalization + inventory shade); nothing frame-specific lives here.
 */
@UtilityClass
public class BoneChains {

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
        if (visiting.contains(name)) return applyBoneRotation(root, bone.getPivot(), bone.getRotation());
        visiting.add(name);

        String parent = bone.getParent();
        Matrix4f base;
        if (parent == null || parent.equals(name) || !bones.containsKey(parent)) {
            base = root;
        } else {
            base = resolveChainFrom(parent, bones, cache, visiting, root);
        }
        Matrix4f composed = applyBoneRotation(base, bone.getPivot(), bone.getRotation());

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

}
