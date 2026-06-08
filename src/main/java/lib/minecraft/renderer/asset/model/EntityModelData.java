package lib.minecraft.renderer.asset.model;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A minimal entity model schema produced by the Java-derived entity-models pipeline
 * ({@code ToolingEntityModels} bytecode walk of the vanilla client jar). Lists each entity's
 * bones and their cube geometry without trying to express every vanilla feature.
 * <p>
 * Used by {@link EntityGeometryKit}'s triangle builders to turn an entity id into a list of cubes
 * that can be fed to the rasterizer.
 * <p>
 * The canonical coordinate convention is vanilla Java's native frame: Y-down, right-handed, with
 * every position field - {@link Bone#getPivot() bone pivot}, {@link Cube#getOrigin() cube origin},
 * {@link Cube#getPivot() cube pivot} - stored in absolute entity-root space.
 *
 * @see EntityGeometryKit
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EntityModelData {

    /**
     * Texture size in pixels, typically {@code 64} or {@code 128}.
     */
    private int textureWidth = 64;

    /**
     * Texture size in pixels.
     */
    private int textureHeight = 64;

    /**
     * The pitch/yaw/roll this model's inventory render should apply before rasterization, in
     * degrees. Mirrors the per-type transformation each vanilla {@code BlockEntityRenderer}
     * applies in {@code BlockEntityWithoutLevelRenderer.renderByItem}; e.g. {@code ChestRenderer}
     * reads the chest's default {@code FACING = NORTH} and yaws {@code -NORTH.toYRot() = 180}.
     * A stored value of {@code 180} therefore reflects chest-family inventory rendering. Models
     * that don't need a bespoke pose (shulker, sign, bed, banner, conduit, hanging sign) leave
     * this at its zero default.
     */
    @SerializedName("inventory_y_rotation")
    private float inventoryYRotation = 0f;

    /**
     * The top-level bones keyed by bone name. Backed by {@link ConcurrentLinkedMap} so iteration
     * preserves JSON author order: render priority assigned per bone during triangle assembly
     * determines which face wins at tied depth (e.g. chest body SOUTH vs lid SOUTH are coplanar
     * at z=15, and the JSON orders bottom/lid/lock so body paints first and its shadow-row
     * pixels survive the lid's overwrite at the overlap seam).
     */
    private @NotNull ConcurrentLinkedMap<String, Bone> bones = Concurrent.newLinkedMap();

    /**
     * Whether vanilla renders this entity through a back-face-culling render type
     * ({@code RenderTypes.entityCutoutCull}) rather than the no-cull default ({@code entityCutout}).
     * The tooling detects it from the model class constructor's render-type function. When set, the
     * geometry kit culls back faces on every cube - including zero-thickness plane cubes, whose two
     * coincident sides would otherwise both draw and let the depth tie-break pick the away side (the
     * bat ear's brown outer winning over its pink inner under vanilla-matching LEQUAL). With culling
     * on, the rasterizer's winding test keeps only the camera-facing side, matching vanilla.
     */
    private boolean cull = false;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        EntityModelData that = (EntityModelData) o;
        return textureWidth == that.textureWidth
            && textureHeight == that.textureHeight
            && Float.compare(inventoryYRotation, that.inventoryYRotation) == 0
            && cull == that.cull
            && Objects.equals(bones, that.bones);
    }

    @Override
    public int hashCode() {
        return Objects.hash(textureWidth, textureHeight, inventoryYRotation, bones, cull);
    }

    /**
     * A single bone in an entity model, with a pivot, rotation, and one or more cubes. The
     * {@link #pivot} is the absolute entity-root point about which {@link #rotation} is applied.
     * Unlike Java {@code ModelPart}, the pivot does not translate cube vertices - cube origins
     * are already in entity-root space, and a bone with no rotation contributes the identity
     * transform.
     * <p>
     * When {@link #parent} is non-null the bone follows its parent's full anchor chain at
     * render time - every ancestor's pivot-centred rotation is composed in root-down order.
     * Rotation-less intermediate bones contribute identity so they do not displace the subtree.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bone {

        private @NotNull Vector3f pivot = Vector3f.ZERO;

        /**
         * The bone's dynamic pose rotation - animated at runtime in vanilla's
         * {@code setupAnim} step. Propagates through the ancestor anchor chain so descendant
         * bones swing along with this bone.
         */
        @JsonAdapter(EulerRotation.Adapter.class)
        private @NotNull EulerRotation rotation = EulerRotation.NONE;

        /**
         * The bone's static rest-pose rotation. Applies to this bone's <b>own</b> cubes only -
         * unlike {@link #rotation} it does NOT propagate through the ancestor chain to
         * descendants. Vanilla v1.8 quadrupeds use this to lay their body cube horizontal
         * ({@code [90, 0, 0]}) while keeping legs upright on the floor. Semantically equivalent
         * to a per-cube rotation around the bone's pivot, applied uniformly to every cube the
         * bone owns.
         */
        @JsonAdapter(EulerRotation.Adapter.class)
        @SerializedName("bind_pose_rotation")
        private @NotNull EulerRotation bindPoseRotation = EulerRotation.NONE;

        /**
         * Uniform multiplier applied to this bone's own cube vertices after positioning at
         * {@link #pivot} and before the cubes are emitted to the kit. Defaults to {@code 1f}
         * - the identity. Captures both vanilla {@code PartPose.scaled(F)} (per-bone) and
         * {@code MeshTransformer.scaling(F)} (whole-layer) which write {@code F} into the
         * underlying {@code PartPose.scale} field; {@code ModelPart.render} consumes that via
         * {@code poseStack.scale(...)} AFTER positioning the bone at its pivot and BEFORE
         * rendering the cube list. Unlike a JSON-bake onto {@link Cube#getOrigin() cube origin}
         * or {@link Cube#getSize() size}, this post-positioning scale does not affect UV
         * resolution (cube UV regions stay tied to the authored {@code size} value), matching
         * vanilla's per-vertex scale semantics.
         *
         * @see lib.minecraft.renderer.kit.EntityGeometryKit
         */
        private float scale = 1f;

        private @NotNull ConcurrentList<Cube> cubes = Concurrent.newList();

        /**
         * The parent bone's name, or {@code null} for a root bone. Resolved at render time
         * against the owning {@link EntityModelData}'s bone map to build the transform chain.
         */
        @SerializedName("parent")
        private @Nullable String parent = null;

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Bone that = (Bone) o;
            return Objects.equals(pivot, that.pivot)
                && Objects.equals(rotation, that.rotation)
                && Objects.equals(bindPoseRotation, that.bindPoseRotation)
                && Float.compare(scale, that.scale) == 0
                && Objects.equals(cubes, that.cubes)
                && Objects.equals(parent, that.parent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pivot, cubes, rotation, bindPoseRotation, scale, parent);
        }

    }

    /**
     * A single cube within a bone. {@link #origin} is the cube's minimum corner in absolute
     * entity-root space, exactly as authored in our JSON schema; {@link #size} is the cube's
     * extent along each axis in model units; {@link #uv} is the top-left corner of the cube's
     * texture region on the shared atlas.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cube {

        private @NotNull Vector3f origin = Vector3f.ZERO;
        private @NotNull Vector3f size = new Vector3f(1f, 1f, 1f);
        private @NotNull Vector2f uv = Vector2f.ZERO;
        private float inflate = 0f;
        private boolean mirror = false;

        /**
         * The cube's rotation pivot in absolute entity-root space, matching {@link #origin}'s
         * coordinate space. Our JSON schema lets individual cubes carry their own
         * {@code pivot}/{@code rotation} pair - used by the 1.21 cow/pig variants to author
         * body cubes vertically and then tilt them into the standard horizontal pose without
         * affecting other cubes in the same bone. When a cube's JSON omits {@code pivot} the
         * parser fills it from the owning bone's pivot, matching the
         * convention that a cube-rotation-without-pivot anchors on the bone. Ignored when
         * {@link #rotation} is zero.
         */
        private @NotNull Vector3f pivot = Vector3f.ZERO;

        @JsonAdapter(EulerRotation.Adapter.class)
        private @NotNull EulerRotation rotation = EulerRotation.NONE;

        /**
         * Per-face UV overrides keyed by {@link BlockFace#direction()
         * face direction name} ({@code "down"}, {@code "up"}, {@code "north"}, {@code "south"},
         * {@code "west"}, {@code "east"}). When a face has an entry here, the explicit
         * {@link FaceUv#getUv() uv} origin and {@link FaceUv#getUvSize() uvSize} replace the
         * atlas unwrap derived from {@link #getUv()} and {@link #getSize()}. Faces absent from
         * the map fall back to the atlas unwrap so packs can override only the faces they need.
         * <p>
         * Matches our per-face UV schema used by Blockbench's cube exports,
         * though the container key differs ({@code "face_uv"} here vs {@code "uv"} as an
         * object in the raw schema) so a Gson {@code TypeAdapter} is not
         * required.
         */
        @SerializedName("face_uv")
        private @NotNull ConcurrentMap<String, FaceUv> faceUv = Concurrent.newMap();

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Cube that = (Cube) o;
            return Float.compare(inflate, that.inflate) == 0
                && mirror == that.mirror
                && Objects.equals(origin, that.origin)
                && Objects.equals(size, that.size)
                && Objects.equals(uv, that.uv)
                && Objects.equals(pivot, that.pivot)
                && Objects.equals(rotation, that.rotation)
                && Objects.equals(faceUv, that.faceUv);
        }

        @Override
        public int hashCode() {
            return Objects.hash(origin, size, uv, inflate, mirror, pivot, rotation, faceUv);
        }

    }

    /**
     * An explicit per-face UV rectangle on an entity {@link Cube}. Stored in pixel coordinates
     * on the source texture, matching our per-face UV schema used by
     * Blockbench exports.
     * <p>
     * {@link #getUv()} is the top-left origin of the rectangle on the texture image;
     * {@link #getUvSize()} is the width and height of the face. Together they describe the
     * sub-rectangle of the texture that should be sampled for this face of the cube.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FaceUv {

        /**
         * The rectangle's top-left origin on the texture image in pixels ({@code [u, v]}).
         */
        private @NotNull Vector2f uv = Vector2f.ZERO;

        /**
         * The rectangle's size on the texture image in pixels ({@code [width, height]}).
         */
        @SerializedName("uv_size")
        private @NotNull Vector2f uvSize = Vector2f.ZERO;

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            FaceUv that = (FaceUv) o;
            return Objects.equals(uv, that.uv)
                && Objects.equals(uvSize, that.uvSize);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uv, uvSize);
        }

    }

}
