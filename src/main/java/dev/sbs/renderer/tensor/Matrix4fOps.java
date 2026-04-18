package dev.sbs.renderer.tensor;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * SIMD-accelerated equivalent of {@link Matrix4f#multiply} that uses the JDK incubator Vector
 * API ({@link FloatVector#SPECIES_128}, 4-lane) to compute one output row per SIMD
 * accumulation.
 * <p>
 * Matrix multiplication maps naturally to row-parallel SIMD: every output row is a linear
 * combination of the right-hand matrix's rows, weighted by the corresponding row of the
 * left-hand matrix. Scalar {@link Matrix4f#multiply} performs 16 output elements x 4 mul-adds
 * each = 64 muls + 48 adds; this variant performs 16 SIMD muls + 12 SIMD adds, each operating
 * on four lanes in parallel.
 * <p>
 * Operation order matches scalar exactly: every output lane's accumulation is
 * {@code ((a{i,1} * b{1,j} + a{i,2} * b{2,j}) + a{i,3} * b{3,j}) + a{i,4} * b{4,j}} - identical
 * operands and identical rounding points to scalar multiply, so the result is bit-identical
 * under IEEE-754 round-to-nearest-even.
 *
 * @see Matrix4f
 * @see Vector3fOps
 */
@UtilityClass
public class Matrix4fOps {

    /** 4-lane float species used for all matrix-row loads in this class. */
    private static final @NotNull VectorSpecies<Float> SPECIES = FloatVector.SPECIES_128;

    /**
     * Returns the product {@code a * b}. Bit-identical to {@link Matrix4f#multiply(Matrix4f)}.
     *
     * @param a the left-hand matrix
     * @param b the right-hand matrix
     * @return the product matrix
     */
    public static @NotNull Matrix4f multiply(@NotNull Matrix4f a, @NotNull Matrix4f b) {
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
