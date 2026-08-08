/**
 * Immutable linear algebra primitives ({@code Vector2f}, {@code Vector3f}, {@code Vector4f},
 * {@code Matrix4f}, {@code Quaternionf}) with optional SIMD acceleration. Built to be a
 * drop-in float-precision replacement for JOML at every call site that compares against the
 * vanilla harness.
 *
 * <p><b>Vanilla parity.</b> Every type matches JOML's algorithms operation-for-operation, not
 * just mathematically. Float arithmetic is non-associative, so the order in which sin / cos
 * values are folded together and the choice of {@code cosFromSin}-via-sqrt vs explicit
 * {@code Math.cos} are part of the contract: a quaternion built here via
 * {@link lib.minecraft.renderer.tensor.Quaternionf#rotationXYZ Quaternionf.rotationXYZ} produces
 * bit-identical floats to {@code new org.joml.Quaternionf().rotationXYZ(...)} in the vanilla
 * harness, and converting it through
 * {@link lib.minecraft.renderer.tensor.Quaternionf#toMatrix4f Quaternionf.toMatrix4f} produces
 * the same {@link lib.minecraft.renderer.tensor.Matrix4f Matrix4f} as
 * {@code new org.joml.Matrix4f().rotation(q)}. The vanilla-reference harness path is the audit
 * tool of choice.
 *
 * <p><b>Convention.</b> Column-vector application (matches JOML / vanilla):
 * <ul>
 *   <li>{@code Matrix4f} stores its sixteen entries in column-major order. The constructor
 *       takes them column-by-column, mirroring vanilla's
 *       {@code new org.joml.Matrix4f(m00, m01, m02, m03, m10, ...)} signature.</li>
 *   <li>A vector is transformed as {@code M * v_col}. In a chain
 *       {@code A.multiply(B).multiply(C)}, {@code C} is innermost and applies to the vector
 *       first, then {@code B}, then {@code A}.</li>
 *   <li>Translation lives in column 4 ({@code get(4, 1)}, {@code get(4, 2)},
 *       {@code get(4, 3)}).</li>
 *   <li>Quaternion conversion follows JOML's
 *       {@code rotationXYZ} = intrinsic {@code R_X; R_Y; R_Z} order; the
 *       {@code rotationZYX(bone)} vs {@code rotationXYZ(gui pose)} distinction is documented
 *       in the project memory because the operation order is opposite despite the name
 *       similarity.</li>
 * </ul>
 *
 * <p><b>Types.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.tensor.Vector2f Vector2f} - immutable
 *       {@code (x, y)} record. Screen-space projections, UV coordinates, texel rectangles.
 *       Carries a Gson {@code TypeAdapter} for JSON deserialization.</li>
 *   <li>{@link lib.minecraft.renderer.tensor.Vector3f Vector3f} - immutable
 *       {@code (x, y, z)} record. Model-space positions, surface normals, scale triples.
 *       {@link lib.minecraft.renderer.tensor.Vector3f#transform Vector3f.transform} /
 *       {@link lib.minecraft.renderer.tensor.Vector3f#transformNormal transformNormal}
 *       silently dispatch to a JDK Vector API implementation when the {@code jdk.incubator.vector}
 *       module is loaded.</li>
 *   <li>{@link lib.minecraft.renderer.tensor.Vector4f Vector4f} - {@code (x, y, z, w)} record
 *       used as a UV rectangle, {@code (x, y)} the min corner and {@code (z, w)} the max, with
 *       face-rotation-/mirror-aware corner expansion. Carries a Gson {@code TypeAdapter}.</li>
 *   <li>{@link lib.minecraft.renderer.tensor.Matrix4f Matrix4f} - immutable column-major 4x4
 *       matrix. Built once per render and reused per-vertex, so {@code Matrix4f} stays a class
 *       rather than the mutable-scratch pattern the per-vertex {@code Vector3f} hot path uses.
 *       {@link lib.minecraft.renderer.tensor.Matrix4f#multiply multiply} silently dispatches
 *       to SIMD.</li>
 *   <li>{@link lib.minecraft.renderer.tensor.Quaternionf Quaternionf} - immutable
 *       {@code (x, y, z, w)} record. Self-contained JOML algorithm port (no JOML dep) used for
 *       the iso rotation matrix in the entity pipeline and the cube-pivot rotation in
 *       {@link lib.minecraft.renderer.engine.kit.EntityGeometryKit EntityGeometryKit}.</li>
 *   <li>{@link lib.minecraft.renderer.tensor.EulerRotation EulerRotation} - immutable
 *       {@code (pitch, yaw, roll)} degree triple, the caller-facing rotation shape every renderer
 *       accepts. Deliberately data-only: it converts to a {@code Quaternionf} / chained matrices at
 *       each call site, where the composition order ({@code rotationXYZ} vs {@code rotationZYX}) is
 *       intentional. Carries a Gson {@code TypeAdapter} serializing {@code [pitch, yaw, roll]},
 *       applied per-field via {@code @JsonAdapter} at the model-JSON sites (entity bone / cube
 *       rotation, {@code display.*} transforms).</li>
 *   <li>{@link lib.minecraft.renderer.tensor.Box Box} - immutable axis-aligned bounding box
 *       storing a min/max corner pair as six floats, with {@code of(...)} factories that fit a
 *       tight AABB around a point list and a {@code maxExtent()} helper. Drives screen-bounds
 *       fitting and canvas sizing.</li>
 * </ul>
 *
 * <p><b>SIMD dispatch.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.tensor.SimdSupport SimdSupport} is the runtime probe.
 *       It does a single {@link java.lang.Class#forName(java.lang.String, boolean, java.lang.ClassLoader) Class.forName} on
 *       {@code jdk.incubator.vector.FloatVector} during class init and caches the result in a
 *       {@code public static final boolean}. The probe is side-effect-free (does not
 *       initialize the probed class) and silently returns {@code false} on any throwable,
 *       including the {@link java.lang.NoClassDefFoundError NoClassDefFoundError} a JVM started without
 *       {@code --add-modules=jdk.incubator.vector} produces. A {@code -Dasset.entity.simd=false}
 *       kill switch exists for A / B precision-hunt baselines.</li>
 *   <li>{@link lib.minecraft.renderer.tensor.SimdOps SimdOps} is the implementation. It
 *       carries every {@code jdk.incubator.*} import in the package, so the JVM never resolves
 *       it when the module is absent and the library degrades silently to scalar fallback.
 *       Public types never reference {@code SimdOps} directly - they read
 *       {@link lib.minecraft.renderer.tensor.SimdSupport#ENABLED SimdSupport.ENABLED} and
 *       dispatch through a static delegate so the bytecode loads cleanly on a stock JDK.</li>
 * </ul>
 *
 * <p><b>JSON adapters.</b> {@code Vector2f}, {@code Vector3f}, and {@code Vector4f} each
 * carry a Gson {@code TypeAdapter}, registered via the
 * {@link lib.minecraft.renderer.pipeline.util.PipelineGsonContributor PipelineGsonContributor} SPI
 * so downstream modules pick them up automatically through {@code GsonSettings.defaults()}.
 *
 * @see lib.minecraft.renderer.tensor.Matrix4f
 * @see lib.minecraft.renderer.tensor.Quaternionf
 * @see lib.minecraft.renderer.tensor.SimdSupport
 */
package lib.minecraft.renderer.tensor;
