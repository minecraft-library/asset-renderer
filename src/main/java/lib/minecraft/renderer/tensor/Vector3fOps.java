package lib.minecraft.renderer.tensor;

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
 * Callers outside the per-vertex hot path (scene setup, diagnostic tools) should keep using
 * {@link Vector3f#transform} / {@link Vector3f#transformNormal} - the SIMD overhead per call
 * swamps the benefit when transforms happen once or twice per render.
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
     * {@link Vector3f}. Bit-identical to {@link Vector3f#transform(Vector3f, Matrix4f)}; faster
     * on vertex-heavy workloads where the method is called thousands of times per render.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    public static @NotNull Vector3f transform(@NotNull Vector3f v, @NotNull Matrix4f m) {
        FloatVector row1 = rowVector(m.getM11(), m.getM12(), m.getM13(), m.getM14());
        FloatVector row2 = rowVector(m.getM21(), m.getM22(), m.getM23(), m.getM24());
        FloatVector row3 = rowVector(m.getM31(), m.getM32(), m.getM33(), m.getM34());
        FloatVector row4 = rowVector(m.getM41(), m.getM42(), m.getM43(), m.getM44());

        // Per-lane: ((v.x * m{1,j} + v.y * m{2,j}) + v.z * m{3,j}) + m{4,j}. Lane 0 yields tx,
        // lane 1 yields ty, lane 2 yields tz; lane 3 (tw) is computed and discarded - harmless,
        // and avoids a mask load on every invocation.
        FloatVector acc = row1.mul(v.x())
            .add(row2.mul(v.y()))
            .add(row3.mul(v.z()))
            .add(row4);
        return new Vector3f(acc.lane(0), acc.lane(1), acc.lane(2));
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}), ignoring the translation
     * row. Bit-identical to {@link Vector3f#transformNormal(Vector3f, Matrix4f)}.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    public static @NotNull Vector3f transformNormal(@NotNull Vector3f v, @NotNull Matrix4f m) {
        FloatVector row1 = rowVector(m.getM11(), m.getM12(), m.getM13(), m.getM14());
        FloatVector row2 = rowVector(m.getM21(), m.getM22(), m.getM23(), m.getM24());
        FloatVector row3 = rowVector(m.getM31(), m.getM32(), m.getM33(), m.getM34());

        FloatVector acc = row1.mul(v.x())
            .add(row2.mul(v.y()))
            .add(row3.mul(v.z()));
        return new Vector3f(acc.lane(0), acc.lane(1), acc.lane(2));
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
