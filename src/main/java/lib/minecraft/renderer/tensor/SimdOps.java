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
 * Reads {@link Matrix4f}'s column-major {@code float[16]} storage directly via
 * {@code FloatVector.fromArray(SPECIES, m.m, col*4)} - one aligned load per column, no temp
 * {@code float[4]} marshalling. Every method matches the operand order and accumulation
 * sequence of the corresponding scalar implementation - no fused multiply-add is used, and adds
 * are accumulated left-to-right against the column vectors in the same sequence the scalar code
 * writes them - so results are bit-identical under IEEE-754 round-to-nearest-even. The
 * CRC32-pinned regression tests ({@code ModelEngineParallelismTest},
 * {@code PortalRendererParallelismTest}, {@code FluidRendererParallelismTest}) stay valid
 * whichever path runs.
 *
 * @see Vector3f
 * @see Matrix4f
 * @see SimdSupport
 */
@UtilityClass
class SimdOps {

    /** 4-lane float species used for all matrix-column loads in this class. */
    private static final @NotNull VectorSpecies<Float> SPECIES = FloatVector.SPECIES_128;

    /**
     * Transforms {@code v} by {@code m} as a point ({@code w=1}) under the column-vector
     * convention {@code m * v_col}. Loads the four columns of {@code m} directly from its
     * column-major storage and computes the three output components in a single horizontal
     * 4-lane accumulation. Lane 3 ({@code tw}) is computed and discarded - harmless, and avoids
     * a mask load on every invocation.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    static @NotNull Vector3f transform(@NotNull Vector3f v, @NotNull Matrix4f m) {
        float[] s = m.m;
        FloatVector col1 = FloatVector.fromArray(SPECIES, s, 0);
        FloatVector col2 = FloatVector.fromArray(SPECIES, s, 4);
        FloatVector col3 = FloatVector.fromArray(SPECIES, s, 8);
        FloatVector col4 = FloatVector.fromArray(SPECIES, s, 12);

        // Per-lane: ((m{i,1} * v.x + m{i,2} * v.y) + m{i,3} * v.z) + m{i,4}. Lane 0 yields tx,
        // lane 1 yields ty, lane 2 yields tz; lane 3 (tw) is discarded.
        FloatVector acc = col1.mul(v.x())
            .add(col2.mul(v.y()))
            .add(col3.mul(v.z()))
            .add(col4);
        return new Vector3f(acc.lane(0), acc.lane(1), acc.lane(2));
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}) under the column-vector
     * convention {@code m * v_col}, ignoring the translation column. Same horizontal-
     * accumulation shape as {@link #transform}, minus the {@code col4} translation term.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    static @NotNull Vector3f transformNormal(@NotNull Vector3f v, @NotNull Matrix4f m) {
        float[] s = m.m;
        FloatVector col1 = FloatVector.fromArray(SPECIES, s, 0);
        FloatVector col2 = FloatVector.fromArray(SPECIES, s, 4);
        FloatVector col3 = FloatVector.fromArray(SPECIES, s, 8);

        FloatVector acc = col1.mul(v.x())
            .add(col2.mul(v.y()))
            .add(col3.mul(v.z()));
        return new Vector3f(acc.lane(0), acc.lane(1), acc.lane(2));
    }

    /**
     * Returns the product {@code a * b}. Computes each column of {@code c = a * b} as a linear
     * combination of {@code a}'s columns weighted by the corresponding column of {@code b}:
     * {@code c_col_j = sum_k a_col_k * b[k, j]}. Stores results back into a fresh
     * column-major {@code float[16]} via {@code FloatVector.intoArray}, producing a
     * {@link Matrix4f} without re-marshalling sixteen scalars through the public constructor.
     *
     * @param a the left-hand matrix
     * @param b the right-hand matrix
     * @return the product matrix
     */
    static @NotNull Matrix4f multiply(@NotNull Matrix4f a, @NotNull Matrix4f b) {
        float[] as = a.m;
        float[] bs = b.m;
        FloatVector aCol1 = FloatVector.fromArray(SPECIES, as, 0);
        FloatVector aCol2 = FloatVector.fromArray(SPECIES, as, 4);
        FloatVector aCol3 = FloatVector.fromArray(SPECIES, as, 8);
        FloatVector aCol4 = FloatVector.fromArray(SPECIES, as, 12);

        float[] r = new float[16];
        for (int j = 0; j < 4; j++) {
            int colStart = j * 4;
            // c_col_j = b[1,j]*a_col_1 + b[2,j]*a_col_2 + b[3,j]*a_col_3 + b[4,j]*a_col_4
            // (column-major: b[r, c] = bs[(c-1)*4 + (r-1)], so b[k+1, j+1] = bs[j*4 + k]).
            FloatVector cCol = aCol1.mul(bs[colStart])
                .add(aCol2.mul(bs[colStart + 1]))
                .add(aCol3.mul(bs[colStart + 2]))
                .add(aCol4.mul(bs[colStart + 3]));
            cCol.intoArray(r, colStart);
        }
        return new Matrix4f(r);
    }

}
