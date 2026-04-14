package dev.sbs.renderer.asset.model;

import com.google.gson.annotations.SerializedName;
import dev.sbs.renderer.geometry.BlockFace;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A minimal bedrock-inspired entity model schema. Java Edition's entity models live in hardcoded
 * client code rather than JSON, so the asset pipeline parses a slim in-repo descriptor per entity
 * that lists its bones and their cube geometry without trying to express every vanilla feature.
 * <p>
 * Used by {@code EntityRenderer.ENTITY_3D} to turn an entity id into a list of cubes that can be
 * fed to the model engine.
 * <p>
 * Bedrock {@code geo.json} and Blockbench exports use a Y-up coordinate system natively, which is
 * what this schema expects. Minecraft Java Edition's {@code ModelPart} code instead uses a
 * Y-down system where positive Y points toward the floor - a vertex at {@code y = -8} is eight
 * units above the entity origin. When an asset is imported from a Java-edition source without
 * being pre-flipped, set {@link #isNegateY()} to {@code true} and the renderer will mirror the
 * whole model about the XZ plane at render time. The mirror is applied after every bone
 * transform so rotations expressed in the source's native coordinate system remain consistent,
 * and triangle winding is reversed in the same pass so back-face culling continues to work.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EntityModelData {

    /** Texture size in pixels, typically {@code 64} or {@code 128}. */
    private int textureWidth = 64;

    /** Texture size in pixels. */
    private int textureHeight = 64;

    /**
     * When {@code true}, the renderer mirrors every transformed vertex and normal about the XZ
     * plane before centring and scaling, and reverses triangle winding so back-face culling is
     * preserved. Set this on models whose source data comes from Minecraft Java Edition's
     * Y-down {@code ModelPart} coordinate system; leave it {@code false} (default) for
     * Bedrock-native Y-up data.
     */
    @SerializedName("negate_y")
    private boolean negateY = false;

    /** The top-level bones keyed by bone name. */
    private @NotNull ConcurrentMap<String, Bone> bones = Concurrent.newMap();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        EntityModelData that = (EntityModelData) o;
        return textureWidth == that.textureWidth
            && textureHeight == that.textureHeight
            && negateY == that.negateY
            && Objects.equals(bones, that.bones);
    }

    @Override
    public int hashCode() {
        return Objects.hash(textureWidth, textureHeight, negateY, bones);
    }

    /**
     * A single bone in an entity model, with an origin, rotation, and one or more cubes.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bone {

        private float @NotNull [] pivot = new float[]{ 0f, 0f, 0f };
        private float @NotNull [] rotation = new float[]{ 0f, 0f, 0f };
        private @NotNull ConcurrentList<Cube> cubes = Concurrent.newList();

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Bone that = (Bone) o;
            return java.util.Arrays.equals(pivot, that.pivot)
                && java.util.Arrays.equals(rotation, that.rotation)
                && Objects.equals(cubes, that.cubes);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(cubes);
            result = 31 * result + java.util.Arrays.hashCode(pivot);
            result = 31 * result + java.util.Arrays.hashCode(rotation);
            return result;
        }

    }

    /**
     * A single cube within a bone. Origin is the cube's minimum corner; size is the cube's extent
     * along each axis in model units. UV is the top-left corner of the cube's texture region.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cube {

        private float @NotNull [] origin = new float[]{ 0f, 0f, 0f };
        private float @NotNull [] size = new float[]{ 1f, 1f, 1f };
        private int @NotNull [] uv = new int[]{ 0, 0 };
        private float inflate = 0f;
        private boolean mirror = false;

        /**
         * Per-face UV overrides keyed by {@link BlockFace#direction()
         * face direction name} ({@code "down"}, {@code "up"}, {@code "north"}, {@code "south"},
         * {@code "west"}, {@code "east"}). When a face has an entry here, the explicit
         * {@link FaceUv#getUv() uv} origin and {@link FaceUv#getUvSize() uvSize} replace the
         * atlas unwrap derived from {@link #getUv()} and {@link #getSize()}. Faces absent from
         * the map fall back to the atlas unwrap so packs can override only the faces they need.
         * <p>
         * Matches the Bedrock {@code geo.json} per-face UV schema used by Blockbench's cube
         * exports, though the container key differs ({@code "face_uv"} here vs {@code "uv"} as
         * an object in the raw geo.json format) so a Gson {@code TypeAdapter} is not required.
         */
        @SerializedName("face_uv")
        private @NotNull ConcurrentMap<String, FaceUv> faceUv = Concurrent.newMap();

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Cube that = (Cube) o;
            return Float.compare(inflate, that.inflate) == 0
                && mirror == that.mirror
                && java.util.Arrays.equals(origin, that.origin)
                && java.util.Arrays.equals(size, that.size)
                && java.util.Arrays.equals(uv, that.uv)
                && Objects.equals(faceUv, that.faceUv);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(inflate, mirror, faceUv);
            result = 31 * result + java.util.Arrays.hashCode(origin);
            result = 31 * result + java.util.Arrays.hashCode(size);
            result = 31 * result + java.util.Arrays.hashCode(uv);
            return result;
        }

    }

    /**
     * An explicit per-face UV rectangle on an entity {@link Cube}. Stored in pixel coordinates
     * on the source texture, matching the Bedrock {@code geo.json} cube face UV schema used by
     * Blockbench exports.
     * <p>
     * {@link #getUv()} is the top-left origin of the rectangle on the texture image;
     * {@link #getUvSize()} is the width and height of the face. Together they describe the
     * sub-rectangle of the texture that should be sampled for this face of the cube.
     */
    @Getter
    @NoArgsConstructor
    public static class FaceUv {

        /** The rectangle's top-left origin on the texture image in pixels ({@code [u, v]}). */
        private float @NotNull [] uv = new float[]{ 0f, 0f };

        /** The rectangle's size on the texture image in pixels ({@code [width, height]}). */
        @SerializedName("uv_size")
        private float @NotNull [] uvSize = new float[]{ 0f, 0f };

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            FaceUv that = (FaceUv) o;
            return java.util.Arrays.equals(uv, that.uv)
                && java.util.Arrays.equals(uvSize, that.uvSize);
        }

        @Override
        public int hashCode() {
            int result = java.util.Arrays.hashCode(uv);
            result = 31 * result + java.util.Arrays.hashCode(uvSize);
            return result;
        }

    }

}
