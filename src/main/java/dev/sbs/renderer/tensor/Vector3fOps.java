package dev.sbs.renderer.tensor;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * SIMD-accelerated equivalents of {@link Vector3f#transform} and
 * {@link Vector3f#transformNormal} that use the JDK incubator Vector API
 * ({@link FloatVector#SPECIES_128}, 4-lane) to compute the three output components in a single
 * horizontal accumulation instead of three independent scalar dot products.
 * <p>
 * The operation order matches the scalar implementation exactly - no fused multiply-add is
 * used, and adds are accumulated left-to-right against the row vectors in the same sequence the
 * scalar code writes them - so results are bit-identical per IEEE-754 round-to-nearest-even.
 * This is deliberate: the CRC32-pinned regression tests
 * ({@code ModelEngineParallelismTest}, {@code PortalRendererParallelismTest},
 * {@code FluidRendererParallelismTest}) stay valid after this class is wired in.
 * <p>
 * Two flavours of each operation are exposed:
 * <ul>
 * <li>Allocating variants ({@link #transform}, {@link #transformNormal}) return a new
 *     {@link Vector3f} per call. Used by cold callers where allocation is negligible.</li>
 * <li>Out-param variants ({@link #transformInto}, {@link #transformNormalInto}) write the
 *     result into a caller-supplied scratch vector. Used by per-vertex hot loops to avoid
 *     allocating a {@link Vector3f} on every transform.</li>
 * </ul>
 *
 * @see Vector3f
 * @see Matrix4fOps
 */
@UtilityClass
public class Vector3fOps {

    /** 4-lane float species used for all matrix-row loads in this class. */
    private static final @NotNull VectorSpecies<Float> SPECIES = FloatVector.SPECIES_128;

    /**
     * Transforms {@code v} by {@code m} as a point ({@code w=1}) and returns a new
     * {@link Vector3f}. Bit-identical to {@link Vector3f#transform(Vector3f, Matrix4f)}.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    public static @NotNull Vector3f transform(@NotNull Vector3f v, @NotNull Matrix4f m) {
        Vector3f out = new Vector3f();
        transformInto(v, m, out);
        return out;
    }

    /**
     * Transforms {@code v} by {@code m} as a point ({@code w=1}), writing the result into
     * {@code out}.
     * <p>
     * Per-lane: {@code ((v.x * m{1,j} + v.y * m{2,j}) + v.z * m{3,j}) + m{4,j}}. Lane 0 yields
     * tx, lane 1 yields ty, lane 2 yields tz; lane 3 (tw) is computed and discarded - harmless,
     * and avoids a mask load on every invocation.
     * <p>
     * {@code v} and {@code out} may be the same instance - all reads from {@code v} complete
     * before the final {@link Vector3f#set} writes to {@code out}.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @param out the vector that receives the transformed components
     */
    public static void transformInto(@NotNull Vector3f v, @NotNull Matrix4f m, @NotNull Vector3f out) {
        FloatVector row1 = rowVector(m.getM11(), m.getM12(), m.getM13(), m.getM14());
        FloatVector row2 = rowVector(m.getM21(), m.getM22(), m.getM23(), m.getM24());
        FloatVector row3 = rowVector(m.getM31(), m.getM32(), m.getM33(), m.getM34());
        FloatVector row4 = rowVector(m.getM41(), m.getM42(), m.getM43(), m.getM44());

        FloatVector acc = row1.mul(v.x())
            .add(row2.mul(v.y()))
            .add(row3.mul(v.z()))
            .add(row4);
        out.set(acc.lane(0), acc.lane(1), acc.lane(2));
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}) and returns a new
     * {@link Vector3f}. Ignores the translation row of {@code m}; bit-identical to
     * {@link Vector3f#transformNormal(Vector3f, Matrix4f)}.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    public static @NotNull Vector3f transformNormal(@NotNull Vector3f v, @NotNull Matrix4f m) {
        Vector3f out = new Vector3f();
        transformNormalInto(v, m, out);
        return out;
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}), writing the result into
     * {@code out}. Ignores the translation row of {@code m}.
     * <p>
     * {@code v} and {@code out} may be the same instance.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @param out the vector that receives the transformed direction
     */
    public static void transformNormalInto(@NotNull Vector3f v, @NotNull Matrix4f m, @NotNull Vector3f out) {
        FloatVector row1 = rowVector(m.getM11(), m.getM12(), m.getM13(), m.getM14());
        FloatVector row2 = rowVector(m.getM21(), m.getM22(), m.getM23(), m.getM24());
        FloatVector row3 = rowVector(m.getM31(), m.getM32(), m.getM33(), m.getM34());

        FloatVector acc = row1.mul(v.x())
            .add(row2.mul(v.y()))
            .add(row3.mul(v.z()));
        out.set(acc.lane(0), acc.lane(1), acc.lane(2));
    }

    /**
     * Builds a four-lane {@link FloatVector} from four scalar components. The temporary array
     * does not escape the method, so HotSpot escape analysis routinely stack-allocates it -
     * no heap pressure under sustained use. Kept as a separate helper so the inline SIMD
     * arithmetic above stays readable.
     */
    private static @NotNull FloatVector rowVector(float a, float b, float c, float d) {
        float[] lanes = { a, b, c, d };
        return FloatVector.fromArray(SPECIES, lanes, 0);
    }

}
