package lib.minecraft.renderer.engine.geometry;

import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.math.Vector4f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * How the texture rectangle for one face of a cube is derived.
 * <p>
 * Minecraft unwraps a cube two ways and both ship. A block model element references an independent
 * texture per face, so every face samples the full rectangle projected onto its own cross-section - the
 * derivation reflects the element's <em>position</em> about 16. An entity cube shares one image across
 * all six faces, so each face reads a strip offset from the cube's own <em>origin</em> by a linear form
 * in the cube's sizes. Neither is expressible in the other: the first never sees a size origin and the
 * second never receives a position.
 * <p>
 * Each arm carries only its own subject - an element's bounds, or a cube's origin, size and mirror flag
 * - and answers {@link #rect} for any {@link Face}, so a call site names the unwrap it wants rather than
 * converting between two vocabularies. Which arm a call site takes is independent of the
 * {@link CornerPhase} it takes.
 * <p>
 * <b>There is no single normalising overload</b>, deliberately. The divisor that turns a pixel rectangle
 * into normalized UVs means three different things in this renderer - {@code 16} for a block element
 * regardless of the sprite's own resolution, the shield's own texture size, and an entity model's
 * <em>declared</em> texture size - so each arm returns its rectangle in its own pixel space and the
 * caller normalizes where it knows which space that is. One method taking a scale that means three
 * things would silently rescale every block-element UV on any high-resolution resource pack.
 */
@Parity(claim = "face-vocabulary", mode = Mode.DEMOTE)
public sealed interface Unwrap permits Unwrap.Element, Unwrap.Atlas {

    /**
     * Returns the texture rectangle this unwrap gives the named face, as
     * {@code (uMin, vMin, uMax, vMax)} in this unwrap's own pixel space.
     *
     * @param face the face to unwrap
     * @return the rectangle, in pixels
     */
    @NotNull Vector4f rect(@NotNull Face face);

    /**
     * The block-model element unwrap, reproducing vanilla's {@code FaceBakery.defaultFaceUV}.
     * <p>
     * Every face samples the full {@code [0, 16]} rectangle projected onto its cross-section of the
     * element's bounds, with U or V read as {@code 16 - value} on the faces that invert. Callers compose
     * {@link Vector4f#toUvCorners(float, float, int, boolean)} with
     * {@link ModelUnits#PIXELS_PER_BLOCK} on the result to obtain normalized
     * per-vertex corners.
     *
     * @param bounds the element bounds in 0-16 space
     */
    record Element(@NotNull Box bounds) implements Unwrap {

        @Override
        public @NotNull Vector4f rect(@NotNull Face face) {
            int uAxis = face.widthAxis();
            int vAxis = face.heightAxis();
            float fromU = axisComponent(this.bounds, uAxis, false);
            float toU = axisComponent(this.bounds, uAxis, true);
            float fromV = axisComponent(this.bounds, vAxis, false);
            float toV = axisComponent(this.bounds, vAxis, true);
            float u0 = face.elementUInverted() ? ModelUnits.PIXELS_PER_BLOCK - toU : fromU;
            float u1 = face.elementUInverted() ? ModelUnits.PIXELS_PER_BLOCK - fromU : toU;
            float v0 = face.elementVInverted() ? ModelUnits.PIXELS_PER_BLOCK - toV : fromV;
            float v1 = face.elementVInverted() ? ModelUnits.PIXELS_PER_BLOCK - fromV : toV;
            return new Vector4f(u0, v0, u1, v1);
        }

        private static float axisComponent(@NotNull Box box, int axis, boolean max) {
            return switch (axis) {
                case 0 -> max ? box.maxX() : box.minX();
                case 1 -> max ? box.maxY() : box.minY();
                default -> max ? box.maxZ() : box.minZ();
            };
        }

    }

    /**
     * The entity-cube atlas unwrap, reproducing the polygon UV layout of vanilla's
     * {@code net.minecraft.client.model.geom.ModelPart.Cube}.
     * <p>
     * Vanilla lays out the strip with bottom (DOWN polygon) and top (UP polygon) in a first row sized
     * {@code sx x sz}, then west, north, east, south in a second row sized {@code sz, sx, sz, sx} wide
     * by {@code sy} tall - reading left-to-right that is {@code LEFT, FRONT, RIGHT, BACK}:
     * <pre>
     *        +-------+--------+
     *        |  BOT  |  TOP   |                       row 1: height sz
     * +------+-------+--------+-------+
     * | WEST | NORTH |  EAST  | SOUTH |               row 2: height sy
     * +------+-------+--------+-------+
     * </pre>
     * The U offset comes from the face's own two coefficients ({@code uOff = uSx * sx + uSz * sz}); the
     * V offset is {@code sz} on the four side faces and zero on the two the top row holds, which is the
     * whole of it - the {@code sy} dimension never contributes to an atlas offset, because vertical
     * extent on the strip is always expressed either in terms of {@code sz} (top row) or as the face's
     * own height.
     * <p>
     * <b>{@link #rect} returns the unmirrored rectangle</b>, on a mirrored cube as much as on any other.
     * A mirrored cube reads the opposite side's strip, which is {@link AxisSigns#MIRROR_X} applied to the
     * face, and callers apply that themselves because several of them need the unmirrored rectangle:
     * the entity kit's cull and translucency predicates ask what a face's <em>own</em> strip contains.
     * {@link #read} answers what one face paints, so it applies both the strip swap and the
     * right-to-left read itself.
     *
     * @param origin the cube's texture origin in pixels on the source image
     * @param size the cube's extent along each axis in model units
     * @param mirror whether the cube's unwrap is mirrored left to right
     */
    record Atlas(@NotNull Vector2f origin, @NotNull Vector3f size, boolean mirror) implements Unwrap {

        @Override
        public @NotNull Vector4f rect(@NotNull Face face) {
            float uOff = face.atlasUSxCoef() * this.size.x() + face.atlasUSzCoef() * this.size.z();
            float vOff = face.axis() == 1 ? 0f : this.size.z();

            float u0 = this.origin.x() + uOff;
            float u1 = u0 + this.size.get(face.widthAxis());
            float v0 = this.origin.y() + vOff;
            float v1 = v0 + this.size.get(face.heightAxis());

            return new Vector4f(u0, v0, u1, v1);
        }

        /**
         * Returns the rectangle one face of this cube reads, as the coordinate at the face's left edge,
         * at its top, at its right and at its bottom, in this unwrap's own pixel space.
         * <p>
         * On an unmirrored cube that is {@link #rect}. A mirrored cube reads the opposite side's strip
         * on the two side faces and reads every strip right to left, so its left and right coordinates
         * come back swapped - the pair a mirrored polygon interpolates descending. A strip need not be a
         * whole number of texels - the baby armour shell's waist is {@code 5.9 x 2 x 2.9} - and the
         * rectangle keeps it fractional, as the client's polygon does.
         *
         * @param face the model-frame face to read
         * @return the face's left, top, right and bottom coordinates, in pixels
         */
        public @NotNull Vector4f read(@NotNull Face face) {
            if (!this.mirror) return rect(face);

            Vector4f strip = rect(AxisSigns.MIRROR_X.apply(face));
            return new Vector4f(strip.z(), strip.y(), strip.x(), strip.w());
        }

    }

}
