package lib.minecraft.renderer.asset.model;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.simplified.annotations.AllArgsConstructor;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NoArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.engine.kit.EntityGeometryKit;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * A minimal entity model schema produced by the Java-derived entity-models pipeline
 * ({@code ToolingEntityModels} bytecode walk of the vanilla client jar). Lists each entity's
 * bones and their cube geometry without trying to express every vanilla feature.
 * <p>
 * Used by the entity geometry kit's triangle builders to turn an entity id into a list of cubes
 * that can be fed to the rasterizer.
 * <p>
 * The canonical coordinate convention is vanilla Java's native frame: Y-down, right-handed. A
 * {@link Bone#getPivot() bone pivot} is <b>parent-relative</b> (its offset from the parent bone,
 * matching vanilla {@code PartPose.offset}); {@link Cube#getOrigin() cube origin} and
 * {@link Cube#getPivot() cube pivot} are <b>bone-local</b> (relative to the owning bone's pivot).
 * A root bone's pivot is measured from the entity root.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class EntityModelData {

    /**
     * The texture atlas dimensions (width, height in pixels) that cube UVs are resolved against,
     * typically {@code [64, 64]} or {@code [128, 64]}, carried as the geometry dialect's
     * {@code texture_size:[w, h]} array.
     */
    @SerializedName("texture_size")
    private @NotNull TextureSize textureSize = TextureSize.DEFAULT;

    /**
     * The GUI-facing yaw in degrees this model's inventory render applies before rasterization.
     * Mirrors the per-type transformation each vanilla {@code BlockEntityRenderer} applies in
     * {@code BlockEntityWithoutLevelRenderer.renderByItem}; e.g. {@code ChestRenderer} reads the
     * chest's default {@code FACING = NORTH} and yaws {@code -NORTH.toYRot() = 180}.
     * <p>
     * Read only on the entity path, where {@code EntityRenderer} folds it into the camera Euler
     * yaw. Block entities do NOT store their yaw here - it lives on the parallel
     * {@code Block.Entity.BoneModel.inventoryYRotation}, applied about block centre by the block
     * presentation. Same name, two homes, two frames (entity camera-yaw vs block presentation-yaw);
     * on the block path this field stays at its zero default.
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

    /**
     * The texture atlas width in pixels - delegates to {@link #textureSize}, kept so the many call
     * sites reading the scalar dimensions keep compiling.
     *
     * @return the atlas width
     */
    public int getTextureWidth() {
        return this.textureSize.width();
    }

    /**
     * The texture atlas height in pixels - delegates to {@link #textureSize}.
     *
     * @return the atlas height
     */
    public int getTextureHeight() {
        return this.textureSize.height();
    }

    /**
     * The one factor this whole mesh was flattened at, or {@code 1f} where its bones do not share one.
     *
     * <p>A {@code MeshTransformer.scaling(F)} rides vanilla's root, so every descendant is positioned
     * and drawn through it. The tooling dissolves that root: each pivot below it arrives multiplied by
     * {@code F} and each bone carries {@code F} for its own cubes, which reproduces the same geometry
     * with nothing propagating. A value in the MODEL's own units therefore has to cross the factor to
     * land in this mesh - which is what an elder guardian's pose places its spikes with, vanilla
     * assigning a bone position of {@code 3.68} to a part its root then draws at 2.35 times the size.
     *
     * <p>Answered only where every bone agrees, which is what tells a flattened whole-mesh scale from
     * a bone's own {@link Bone#getScale() scale}: the first reaches the entire tree and the second
     * reaches one bone, whose pivot vanilla has already scaled itself. An aged-down mesh carries a
     * factor per subtree rather than one and answers nothing here.
     *
     * @return the shared factor, or {@code 1f} where there is none
     */
    public float getFlattenedScale() {
        float shared = 1f;
        boolean first = true;
        for (Bone bone : this.bones.values()) {
            if (first) shared = bone.getScale();
            else if (shared != bone.getScale()) return 1f;
            first = false;
        }
        return shared;
    }

    /**
     * A single bone in an entity model, with a parent-relative pivot, rotation, and zero or more
     * cubes. Matching vanilla {@code ModelPart}, the {@link #pivot} is the bone's offset from its
     * {@link #parent} and the point about which {@link #rotation} is applied; cube origins are
     * bone-local and the bone chain translates by the pivot before drawing them.
     * <p>
     * When {@link #parent} is non-null the bone follows its parent's full anchor chain at
     * render time - every ancestor's pivot-centred rotation is composed in root-down order, so a
     * rotation on this bone (or any ancestor) swings the whole subtree. Rotation-less intermediate
     * bones contribute identity so they do not displace the subtree. A bone with no cubes is a
     * pure pose-only container (a group anchor for its children).
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Bone {

        /** No displacement on any axis - what every bone a mesh is loaded with stands at. */
        private static final @NotNull Vector3f UNIT_SCALE = new Vector3f(1f, 1f, 1f);

        /**
         * The bone's parent-relative offset - the anchor point (in the parent bone's frame) about
         * which this bone's {@link #rotation} is applied. Measured from the entity root for a root
         * bone ({@link #parent} {@code == null}).
         */
        private @NotNull Vector3f pivot = Vector3f.ZERO;

        /**
         * The bone's rotation about its {@link #pivot}, in the parent's frame - the vanilla
         * {@code PartPose} rest rotation, and that alone. Propagates through the ancestor anchor
         * chain so descendant bones swing along with this bone.
         *
         * <p>What a model does to its bones at animation time is held apart, on
         * {@link lib.minecraft.renderer.asset.Entity#pose() Entity.pose} - a rotation here is the
         * pose every bone is put back to before any of that is applied, which is what makes it the
         * value an unwritten channel reads.
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
         * @see EntityGeometryKit
         */
        private float scale = 1f;

        /**
         * The cubes this bone owns, in declared order.
         */
        private @NotNull ConcurrentList<Cube> cubes = Concurrent.newList();

        /**
         * The parent bone's name, or {@code null} for a root bone. Resolved at render time
         * against the owning {@link EntityModelData}'s bone map to build the transform chain.
         */
        @SerializedName("parent")
        private @Nullable String parent = null;

        /**
         * The per-axis scale a clip displaces this bone by at one instant, resting at one on every
         * axis. Composed into the ancestor chain as vanilla's {@code T * R * S}, so it reaches this
         * bone's own cubes AND every descendant.
         *
         * <p><b>Held apart from {@link #scale}, which is a different fact.</b> That one is the
         * whole-mesh factor the tooling already flattened onto every bone of the mesh, so it is
         * applied to a cube's own operands and deliberately does NOT propagate - propagating it
         * would apply it once per level of the chain. This one is written at render time by
         * something that never saw the flattening, so it has to propagate the way vanilla's
         * {@code PoseStack.scale} does.
         *
         * <p>The two never meet: <b>no bone any shipped clip scales carries a baked factor</b>, all
         * 278 that do being untouched by one, so a clip's displacement lands on a bone resting at
         * one and the factor here is exactly {@code 1 + delta} rather than a ratio that would have
         * to be divided out.
         */
        private transient @NotNull Vector3f poseScale = UNIT_SCALE;

        /**
         * Constructs a bone standing at no clip displacement, which is every bone a mesh is loaded
         * with - only a posed frame writes one.
         *
         * @param pivot the parent-relative anchor its rotation is applied about
         * @param rotation its rotation about that anchor, in the parent's frame
         * @param bindPoseRotation its static rest-pose rotation, reaching its own cubes alone
         * @param scale the whole-mesh factor already flattened onto it
         * @param cubes the cubes it owns, in declared order
         * @param parent the parent bone's name, or {@code null} for a root bone
         */
        public Bone(
            @NotNull Vector3f pivot, @NotNull EulerRotation rotation,
            @NotNull EulerRotation bindPoseRotation, float scale,
            @NotNull ConcurrentList<Cube> cubes, @Nullable String parent) {

            this(pivot, rotation, bindPoseRotation, scale, cubes, parent, UNIT_SCALE);
        }

        /**
         * Whether a clip displaces this bone's scale at all.
         *
         * @return {@code true} when any axis stands away from one
         */
        public boolean isPoseScaled() {
            return !UNIT_SCALE.equals(this.poseScale);
        }

    }

    /**
     * A single cube within a bone. {@link #origin} is the cube's minimum corner in bone-local
     * space (relative to the owning bone's pivot), exactly as authored in our JSON schema;
     * {@link #size} is the cube's extent along each axis in model units; {@link #uv} is the
     * top-left corner of the cube's texture region on the shared atlas.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Cube {

        /**
         * The cube's minimum corner in bone-local space (relative to the owning bone's pivot).
         */
        private @NotNull Vector3f origin = Vector3f.ZERO;

        /**
         * The cube's extent along each axis in model units.
         */
        private @NotNull Vector3f size = new Vector3f(1f, 1f, 1f);

        /**
         * The top-left corner of the cube's texture region on the shared atlas.
         */
        private @NotNull Vector2f uv = Vector2f.ZERO;

        /**
         * Per-axis outward expansion applied to every face in model units; {@link Vector3f#ZERO}
         * leaves the cube at its authored size. Deserialises from the geometry {@code grow} key - a
         * scalar {@code g} broadcasts to {@code (g, g, g)}, an {@code [x, y, z]} array (an asymmetric
         * vanilla {@code CubeDeformation}) is read per-axis. The kit expands the cube's corner box by
         * this, leaving the {@code size}-derived UV footprint untouched (vanilla {@code CubeDeformation}
         * grows vertices, not the sampled texture rectangle).
         */
        @SerializedName("grow")
        @JsonAdapter(GrowAdapter.class)
        private @NotNull Vector3f grow = Vector3f.ZERO;

        /**
         * Whether the cube's texture UVs are mirrored left-to-right.
         */
        private boolean mirror = false;

        /**
         * The cube's rotation pivot in bone-local space, matching {@link #origin}'s
         * coordinate space. Our JSON schema lets individual cubes carry their own
         * {@code pivot}/{@code rotation} pair - used by the 1.21 cow/pig variants to author
         * body cubes vertically and then tilt them into the standard horizontal pose without
         * affecting other cubes in the same bone. When a cube's JSON omits {@code pivot} the
         * parser fills it from the owning bone's pivot, matching the
         * convention that a cube-rotation-without-pivot anchors on the bone. Ignored when
         * {@link #rotation} is zero.
         */
        private @NotNull Vector3f pivot = Vector3f.ZERO;

        /**
         * The cube's own rotation about {@link #pivot}, or {@link EulerRotation#NONE} when unrotated.
         */
        @JsonAdapter(EulerRotation.Adapter.class)
        private @NotNull EulerRotation rotation = EulerRotation.NONE;

        /**
         * Per-face UV overrides keyed by {@link Face#direction()
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

        /**
         * Reads the geometry {@code grow} value into the {@link #grow} vector: a scalar {@code g}
         * broadcasts to {@code (g, g, g)}, an {@code [x, y, z]} array is read per-axis. Writes the
         * uniform form back as a scalar so a round-trip stays byte-compatible with the tooling emit.
         */
        public static final class GrowAdapter extends TypeAdapter<Vector3f> {

            @Override
            public void write(@NotNull JsonWriter out, Vector3f value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                if (value.x() == value.y() && value.y() == value.z()) {
                    out.value(value.x());
                    return;
                }
                out.beginArray();
                out.value(value.x());
                out.value(value.y());
                out.value(value.z());
                out.endArray();
            }

            @Override
            public @NotNull Vector3f read(@NotNull JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return Vector3f.ZERO;
                }
                if (in.peek() == JsonToken.BEGIN_ARRAY) {
                    in.beginArray();
                    float x = (float) in.nextDouble();
                    float y = (float) in.nextDouble();
                    float z = (float) in.nextDouble();
                    in.endArray();
                    return new Vector3f(x, y, z);
                }
                float g = (float) in.nextDouble();
                return new Vector3f(g, g, g);
            }
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
    @EqualsAndHashCode
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

    }

}
