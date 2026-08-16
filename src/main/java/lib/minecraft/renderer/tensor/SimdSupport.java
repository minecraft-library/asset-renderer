package lib.minecraft.renderer.tensor;

import dev.simplified.annotations.UtilityClass;

/**
 * Runtime probe for the JDK incubator Vector API ({@code jdk.incubator.vector}).
 * <p>
 * Detection runs once during class initialization via {@link Class#forName} on
 * {@code jdk.incubator.vector.FloatVector} and is cached in {@link #ENABLED}. The probe is
 * side-effect-free - it does not initialize the probed class - and silently returns
 * {@code false} on any throwable, including {@link NoClassDefFoundError} from a JVM that
 * was started without {@code --add-modules=jdk.incubator.vector}.
 * <p>
 * Public tensor classes ({@link Vector3f}, {@link Matrix4f}) read this flag inside their
 * hot-path methods and dispatch to {@link SimdOps} when it is {@code true}. The dispatcher
 * class itself carries zero {@code jdk.incubator.*} imports, so the JVM never resolves the
 * SIMD class when the module is absent and the library degrades silently to the scalar
 * fallback.
 *
 * @see Vector3f#transform
 * @see Matrix4f#multiply
 */
@UtilityClass
final class SimdSupport {

    /**
     * Whether {@code jdk.incubator.vector.FloatVector} is resolvable on this JVM AND the user
     * has not explicitly disabled SIMD dispatch via {@code -Dasset.entity.simd=false}. The kill
     * switch exists for precision-hunt baselines that want to A/B SIMD vs scalar without
     * changing the JVM module flags.
     */
    static final boolean ENABLED = detect() && !"false".equalsIgnoreCase(System.getProperty("asset.entity.simd", "true"));

    /**
     * Probes for the incubator Vector API via a non-initializing {@link Class#forName}. Returns
     * {@code false} on any throwable ({@link NoClassDefFoundError} on a JVM lacking the module,
     * or any other resolution failure) rather than propagating.
     *
     * @return {@code true} when {@code jdk.incubator.vector.FloatVector} resolves on this JVM
     */
    private static boolean detect() {
        try {
            Class.forName("jdk.incubator.vector.FloatVector", false, SimdSupport.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

}
