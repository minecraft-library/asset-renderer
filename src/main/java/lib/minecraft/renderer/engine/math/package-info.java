/**
 * Float-precision linear algebra and the vector-unit path it computes through - the immutable
 * {@code Vector2f}, {@code Vector3f}, {@code Vector4f}, {@code Matrix4f} and {@code Quaternionf}
 * primitives, with optional SIMD acceleration. Built to be a drop-in float-precision replacement for
 * JOML at every call site that compares against the vanilla harness. A type whose operations are not
 * defined on numbers alone does not belong here.
 *
 * <p><b>Vanilla parity.</b> Every type matches JOML's algorithms operation-for-operation, not
 * just mathematically. Float arithmetic is non-associative, so the order in which sin / cos
 * values are folded together and the choice of {@code cosFromSin}-via-sqrt vs explicit
 * {@code Math.cos} are part of the contract: a quaternion built here via
 * {@link lib.minecraft.renderer.engine.math.Quaternionf#rotationXYZ Quaternionf.rotationXYZ} produces
 * bit-identical floats to {@code new org.joml.Quaternionf().rotationXYZ(...)} in the vanilla
 * harness, and converting it through
 * {@link lib.minecraft.renderer.engine.math.Quaternionf#toMatrix4f Quaternionf.toMatrix4f} produces
 * the same {@link lib.minecraft.renderer.engine.math.Matrix4f Matrix4f} as
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
 *       {@code rotationXYZ} = intrinsic {@code R_X; R_Y; R_Z} order. The
 *       {@code rotationZYX} a bone rotates by and the {@code rotationXYZ} a gui pose rotates by
 *       apply their axes in opposite orders despite the similar names, so the one a call site
 *       names is part of its contract.</li>
 * </ul>
 *
 * <p><b>Types.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.math.Vector2f Vector2f} - immutable
 *       {@code (x, y)} record. Screen-space projections, UV coordinates, texel rectangles.</li>
 *   <li>{@link lib.minecraft.renderer.engine.math.Vector3f Vector3f} - immutable
 *       {@code (x, y, z)} record. Model-space positions, surface normals, scale triples.
 *       {@link lib.minecraft.renderer.engine.math.Vector3f#transform Vector3f.transform} /
 *       {@link lib.minecraft.renderer.engine.math.Vector3f#transformNormal transformNormal}
 *       silently dispatch to a JDK Vector API implementation when the {@code jdk.incubator.vector}
 *       module is loaded.</li>
 *   <li>{@link lib.minecraft.renderer.engine.math.Vector4f Vector4f} - {@code (x, y, z, w)} record
 *       used as a UV rectangle, {@code (x, y)} the min corner and {@code (z, w)} the max, with
 *       face-rotation-/mirror-aware corner expansion.</li>
 *   <li>{@link lib.minecraft.renderer.engine.math.Matrix4f Matrix4f} - immutable column-major 4x4
 *       matrix. Built once per render and reused per-vertex, so {@code Matrix4f} stays a class
 *       rather than the mutable-scratch pattern the per-vertex {@code Vector3f} hot path uses.
 *       {@link lib.minecraft.renderer.engine.math.Matrix4f#multiply multiply} silently dispatches
 *       to SIMD.</li>
 *   <li>{@link lib.minecraft.renderer.engine.math.Quaternionf Quaternionf} - immutable
 *       {@code (x, y, z, w)} record. Self-contained JOML algorithm port (no JOML dep) used for
 *       the iso rotation matrix a {@link lib.minecraft.renderer.engine.camera.Camera Camera} is posed
 *       by and the bone and cube-pivot rotations in
 *       {@link lib.minecraft.renderer.bake.mesh.BoneKit BoneKit}.</li>
 * </ul>
 *
 * <p><b>SIMD dispatch.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.engine.math.SimdSupport SimdSupport} is the runtime probe.
 *       It does a single {@link java.lang.Class#forName(java.lang.String, boolean, java.lang.ClassLoader) Class.forName} on
 *       {@code jdk.incubator.vector.FloatVector} during class init and caches the result in a
 *       {@code static final boolean}. The probe is side-effect-free (does not
 *       initialize the probed class) and silently returns {@code false} on any throwable,
 *       including the {@link java.lang.NoClassDefFoundError NoClassDefFoundError} a JVM started without
 *       {@code --add-modules=jdk.incubator.vector} produces. A {@code -Dasset.entity.simd=false}
 *       kill switch exists for A / B precision-hunt baselines.</li>
 *   <li>{@link lib.minecraft.renderer.engine.math.SimdOps SimdOps} is the implementation. It
 *       carries every {@code jdk.incubator.*} import in the package, so the JVM never resolves
 *       it when the module is absent and the library degrades silently to scalar fallback.
 *       Public types call into {@code SimdOps} only inside a branch on
 *       {@link lib.minecraft.renderer.engine.math.SimdSupport#ENABLED SimdSupport.ENABLED}, so the
 *       bytecode loads cleanly on a stock JDK.</li>
 * </ul>
 *
 * <p><b>JSON adapters.</b> {@code Vector2f}, {@code Vector3f}, and {@code Vector4f} are read by the
 * Gson adapters in {@link lib.minecraft.renderer.content.json content.json}, registered via the
 * {@link lib.minecraft.renderer.content.json.RendererGsonContributor RendererGsonContributor} SPI
 * so downstream modules pick them up automatically through {@code GsonSettings.defaults()}.
 *
 * <p><b>Parity.</b> This math is under every vertex the engine projects, so it reaches every render,
 * and two golden pins hold sixteen and twenty-four exact floats through it - an arithmetic change
 * fails them before any sum has moved. That is the one region where an answer per file is still an
 * engine-wide answer, and nothing here tries to talk it down. What is answered for the package is
 * the demotion - a dump holds serialised vectors, so it can be reached from here and still cannot
 * move, the dump never projecting a vertex.
 *
 * @see lib.minecraft.renderer.engine.math.Matrix4f
 * @see lib.minecraft.renderer.engine.math.Quaternionf
 * @see lib.minecraft.renderer.engine.math.SimdSupport
 */
@Parity(claim = "tensor-math", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.math;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
