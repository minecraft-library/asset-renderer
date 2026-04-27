package lib.minecraft.renderer.tensor;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Internal SIMD fast path for {@link Vector3f#transform}, {@link Vector3f#transformNormal}, and
 * {@link Matrix4f#multiply}, invoked by those methods when {@link SimdSupport#ENABLED} is
 * {@code true} and bypassed for the scalar fallback otherwise.
 * <p>
 * Not part of the public API - all {@code jdk.incubator.vector.*} symbols are confined to this
 * class so a JVM without the incubator module never has to resolve them. The dispatchers
 * reference the methods here only inside an {@code if (SimdSupport.ENABLED)} branch, which the
 * JVM verifies lazily.
 * <p>
 * Every method matches the operand order and accumulation sequence of the corresponding scalar
 * implementation - no fused multiply-add is used, and adds are accumulated left-to-right
 * against the row vectors in the same sequence the scalar code writes them - so results are
 * bit-identical under IEEE-754 round-to-nearest-even. The CRC32-pinned regression tests
 * ({@code ModelEngineParallelismTest}, {@code PortalRendererParallelismTest},
 * {@code FluidRendererParallelismTest}) stay valid whichever path runs.
 *
 * @see Vector3f
 * @see Matrix4f
 * @see SimdSupport
 */
@UtilityClass
class SimdOps {

    /** 4-lane float species used for all matrix-row loads in this class. */
    private static final @NotNull VectorSpecies<Float> SPECIES = FloatVector.SPECIES_128;

    /**
     * Transforms {@code v} by {@code m} as a point ({@code w=1}). Computes the three output
     * components in a single horizontal 4-lane accumulation instead of three independent scalar
     * dot products. Lane 3 ({@code tw}) is computed and discarded - harmless, and avoids a mask
     * load on every invocation.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    static @NotNull Vector3f transform(@NotNull Vector3f v, @NotNull Matrix4f m) {
        FloatVector row1 = rowVector(m.getM11(), m.getM12(), m.getM13(), m.getM14());
        FloatVector row2 = rowVector(m.getM21(), m.getM22(), m.getM23(), m.getM24());
        FloatVector row3 = rowVector(m.getM31(), m.getM32(), m.getM33(), m.getM34());
        FloatVector row4 = rowVector(m.getM41(), m.getM42(), m.getM43(), m.getM44());

        // Per-lane: ((v.x * m{1,j} + v.y * m{2,j}) + v.z * m{3,j}) + m{4,j}. Lane 0 yields tx,
        // lane 1 yields ty, lane 2 yields tz; lane 3 (tw) is discarded.
        FloatVector acc = row1.mul(v.x())
            .add(row2.mul(v.y()))
            .add(row3.mul(v.z()))
            .add(row4);
        return new Vector3f(acc.lane(0), acc.lane(1), acc.lane(2));
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}), ignoring the translation
     * row. Same horizontal-accumulation shape as {@link #transform}, minus the {@code row4}
     * translation term.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    static @NotNull Vector3f transformNormal(@NotNull Vector3f v, @NotNull Matrix4f m) {
        FloatVector row1 = rowVector(m.getM11(), m.getM12(), m.getM13(), m.getM14());
        FloatVector row2 = rowVector(m.getM21(), m.getM22(), m.getM23(), m.getM24());
        FloatVector row3 = rowVector(m.getM31(), m.getM32(), m.getM33(), m.getM34());

        FloatVector acc = row1.mul(v.x())
            .add(row2.mul(v.y()))
            .add(row3.mul(v.z()));
        return new Vector3f(acc.lane(0), acc.lane(1), acc.lane(2));
    }

    /**
     * Returns the product {@code a * b}. Uses row-parallel SIMD: every output row is a linear
     * combination of {@code b}'s rows weighted by the corresponding row of {@code a}. Performs
     * 16 SIMD muls + 12 SIMD adds (each over 4 lanes) versus the scalar 64 muls + 48 adds. Each
     * output lane's accumulation is
     * {@code ((a{i,1} * b{1,j} + a{i,2} * b{2,j}) + a{i,3} * b{3,j}) + a{i,4} * b{4,j}}.
     *
     * @param a the left-hand matrix
     * @param b the right-hand matrix
     * @return the product matrix
     */
    static @NotNull Matrix4f multiply(@NotNull Matrix4f a, @NotNull Matrix4f b) {
        FloatVector bRow1 = rowVector(b.getM11(), b.getM12(), b.getM13(), b.getM14());
        FloatVector bRow2 = rowVector(b.getM21(), b.getM22(), b.getM23(), b.getM24());
        FloatVector bRow3 = rowVector(b.getM31(), b.getM32(), b.getM33(), b.getM34());
        FloatVector bRow4 = rowVector(b.getM41(), b.getM42(), b.getM43(), b.getM44());

        FloatVector cRow1 = bRow1.mul(a.getM11()).add(bRow2.mul(a.getM12())).add(bRow3.mul(a.getM13())).add(bRow4.mul(a.getM14()));
        FloatVector cRow2 = bRow1.mul(a.getM21()).add(bRow2.mul(a.getM22())).add(bRow3.mul(a.getM23())).add(bRow4.mul(a.getM24()));
        FloatVector cRow3 = bRow1.mul(a.getM31()).add(bRow2.mul(a.getM32())).add(bRow3.mul(a.getM33())).add(bRow4.mul(a.getM34()));
        FloatVector cRow4 = bRow1.mul(a.getM41()).add(bRow2.mul(a.getM42())).add(bRow3.mul(a.getM43())).add(bRow4.mul(a.getM44()));

        return new Matrix4f(
            cRow1.lane(0), cRow1.lane(1), cRow1.lane(2), cRow1.lane(3),
            cRow2.lane(0), cRow2.lane(1), cRow2.lane(2), cRow2.lane(3),
            cRow3.lane(0), cRow3.lane(1), cRow3.lane(2), cRow3.lane(3),
            cRow4.lane(0), cRow4.lane(1), cRow4.lane(2), cRow4.lane(3)
        );
    }

    /**
     * Builds a four-lane {@link FloatVector} from four scalar components. The temporary array
     * does not escape the method, so HotSpot escape analysis routinely stack-allocates it -
     * no heap pressure under sustained use.
     */
    private static @NotNull FloatVector rowVector(float a, float b, float c, float d) {
        float[] lanes = { a, b, c, d };
        return FloatVector.fromArray(SPECIES, lanes, 0);
    }

}
