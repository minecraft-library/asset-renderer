package lib.minecraft.renderer.face;

import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.jetbrains.annotations.NotNull;

/**
 * Which of a face's four corners a quad starts at, and which UV slot pairs with each of them.
 * <p>
 * Vanilla winds the four corners of one cube face two different ways depending on which of its own
 * bakers built the quad, and both windings ship. A single choice fixes two downstream facts at once:
 * the diagonal the {@code (0, 1, 2) + (0, 2, 3)} fan splits the quad on, and which UV corner pairs
 * with which vertex. The two live on one receiver because that <b>pairing</b> is the load-bearing part
 * - re-rotating a corner order onto the other phase preserves the position-to-UV correspondence only
 * if the UV order is counter-rotated with it, and getting that wrong moves the fan diagonal rather
 * than failing to compile.
 * <p>
 * Every one of the six {@link #BAKERY} index arrays is a cyclic <em>rotation</em> of its
 * {@link #POLYGON} counterpart, never a reversal, so both phases name the same four points of a face
 * and both wind counter-clockwise seen from the outward normal - but they hand them back in a
 * different order and therefore split on <b>opposite</b> diagonals. Neither is derivable from the
 * other; both are pinned to vanilla.
 * <p>
 * The phase a call site takes is independent of the {@link Unwrap} it takes and of the subject being
 * drawn. The block-entity path builds with {@link #POLYGON}; the shield - an item - builds with
 * {@link #BAKERY} and textures through the atlas unwrap.
 * <p>
 * The 8-corner box layout the index arrays address:
 * <pre>
 * 0: (x0, y0, z0)   4: (x0, y0, z1)
 * 1: (x1, y0, z0)   5: (x1, y0, z1)
 * 2: (x1, y1, z0)   6: (x1, y1, z1)
 * 3: (x0, y1, z0)   7: (x0, y1, z1)
 * </pre>
 */
@RequiredArgsConstructor
public enum CornerPhase {

    /**
     * Vanilla's {@code FaceBakery} winding, which starts at the face's top-left corner seen from the
     * outward normal and walks {@code TL, BL, BR, TR}.
     * <p>
     * Because that order <em>is</em> the order {@link Vector4f#toUvCorners} hands a rectangle's corners
     * back in, this phase's UV pairing is the identity - which is why it was never written down before
     * and why nothing pinned it.
     */
    BAKERY(
        new int[][]{
            { 4, 0, 1, 5 },   // DOWN
            { 3, 7, 6, 2 },   // UP
            { 2, 1, 0, 3 },   // NORTH
            { 7, 4, 5, 6 },   // SOUTH
            { 3, 0, 4, 7 },   // WEST
            { 6, 5, 1, 2 }    // EAST
        },
        new int[][]{
            { 0, 1, 2, 3 },   // DOWN
            { 0, 1, 2, 3 },   // UP
            { 0, 1, 2, 3 },   // NORTH
            { 0, 1, 2, 3 },   // SOUTH
            { 0, 1, 2, 3 },   // WEST
            { 0, 1, 2, 3 }    // EAST
        }
    ),

    /**
     * Vanilla's {@code ModelPart.Cube} polygon winding, extracted from the cube constructor's
     * bytecode, so a cube's screen-space quad triangulation splits along the same diagonal vanilla's
     * GPU rasterizes.
     * <p>
     * Side faces start at the {@code (minY, "front")} corner of the face (or its mirror for
     * back-facing sides) and wind {@code front-top -> back-top -> back-bottom -> front-bottom}; UP
     * starts at {@code (maxX, maxY, minZ)} and DOWN at {@code (maxX, minY, maxZ)}.
     * <p>
     * Five of the six UV slot rows are the same cyclic shift, placing the rectangle's top-right corner
     * on vertex 0 to match the polygon constructor's TR-first walk. UP alone additionally trades the
     * top and bottom rows, because vanilla's cube constructor passes UP's v arguments in inverted order
     * ({@code f3 = v}, {@code f5 = v + d}) and so walks UP's atlas strip backward relative to every
     * other face.
     */
    POLYGON(
        new int[][]{
            { 5, 4, 0, 1 },   // DOWN
            { 2, 3, 7, 6 },   // UP
            { 1, 0, 3, 2 },   // NORTH
            { 4, 5, 6, 7 },   // SOUTH
            { 0, 4, 7, 3 },   // WEST
            { 5, 1, 2, 6 }    // EAST
        },
        new int[][]{
            { 3, 0, 1, 2 },   // DOWN
            { 2, 1, 0, 3 },   // UP
            { 3, 0, 1, 2 },   // NORTH
            { 3, 0, 1, 2 },   // SOUTH
            { 3, 0, 1, 2 },   // WEST
            { 3, 0, 1, 2 }    // EAST
        }
    );

    private final int @NotNull [] @NotNull [] vertexIndices;
    private final int @NotNull [] @NotNull [] uvSlots;

    /**
     * Returns the four vertex indices this phase walks a face's corners in, addressing the canonical
     * 8-corner box laid out in the class javadoc.
     *
     * @param face the face to walk
     * @return the four indices, in emission order
     */
    public int @NotNull [] vertexIndices(@NotNull Face face) {
        return this.vertexIndices[face.ordinal()];
    }

    /**
     * Returns the four corners of a face on the given axis-aligned {@link Box}, in this phase's
     * emission order.
     *
     * @param face the face to walk
     * @param box the bounding box
     * @return the four corner positions, in emission order
     */
    public @NotNull Vector3f @NotNull [] corners(@NotNull Face face, @NotNull Box box) {
        Vector3f[] cornersOfBox = {
            new Vector3f(box.minX(), box.minY(), box.minZ()), new Vector3f(box.maxX(), box.minY(), box.minZ()),
            new Vector3f(box.maxX(), box.maxY(), box.minZ()), new Vector3f(box.minX(), box.maxY(), box.minZ()),
            new Vector3f(box.minX(), box.minY(), box.maxZ()), new Vector3f(box.maxX(), box.minY(), box.maxZ()),
            new Vector3f(box.maxX(), box.maxY(), box.maxZ()), new Vector3f(box.minX(), box.maxY(), box.maxZ())
        };
        int[] indices = this.vertexIndices[face.ordinal()];
        return new Vector3f[]{
            cornersOfBox[indices[0]], cornersOfBox[indices[1]],
            cornersOfBox[indices[2]], cornersOfBox[indices[3]]
        };
    }

    /**
     * Returns which UV corner this phase pairs with each of a face's four vertices - {@code slots[i]}
     * is the index into a {@code (TL, BL, BR, TR)} rectangle that vertex {@code i} reads.
     *
     * @param face the face to pair
     * @return the four slot indices, in emission order
     */
    public int @NotNull [] uvSlots(@NotNull Face face) {
        return this.uvSlots[face.ordinal()];
    }

    /**
     * Reorders four atlas-position-labelled UV corners {@code (TL, BL, BR, TR)} - the shape
     * {@link Vector4f#toUvCorners} returns - into this phase's per-vertex order, so
     * {@code output[i]} is the UV that pairs with the vertex {@code corners(face, box)[i]}.
     *
     * @param face the face being paired
     * @param tlBlBrTr the four UV corners in atlas-position order (top-left, bottom-left,
     *     bottom-right, top-right)
     * @return the four UVs in this phase's per-vertex order
     */
    public @NotNull Vector2f @NotNull [] permuteUv(@NotNull Face face, @NotNull Vector2f @NotNull [] tlBlBrTr) {
        int[] slots = this.uvSlots[face.ordinal()];
        return new Vector2f[]{ tlBlBrTr[slots[0]], tlBlBrTr[slots[1]], tlBlBrTr[slots[2]], tlBlBrTr[slots[3]] };
    }

}
