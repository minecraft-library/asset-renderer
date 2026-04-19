package dev.sbs.renderer.tooling.blockentity;

import dev.sbs.renderer.tooling.asm.AsmKit;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven decomposer for block-entity renderer inventory transforms. Walks the
 * factory method (or static field initializer) on each {@code BlockEntityRenderer} that
 * constructs a {@code com.mojang.math.Transformation}, and reduces the
 * {@code Matrix4f}-call-chain or four-argument {@code (Vector3fc, Quaternionfc, Vector3fc,
 * Quaternionfc)} constructor into a canonical
 * {@code [tx, ty, tz, pitchDeg, yawDeg, rollDeg, uniformScale?]} tuple the atlas pipeline
 * expects.
 *
 * <p>Fifteen of the sixteen block-entity inventory transforms fall cleanly out of the
 * renderer bytecode at reference pose (yaw = 0): all bed / shulker / skull / decorated_pot /
 * conduit / sign / hanging_sign / banner variants. The sole exception is
 * {@code minecraft:skull_dragon_head}, whose distinguishing {@code tz = 1.25} comes from the
 * {@code DragonHeadModel} geometry rather than the shared {@code SkullBlockRenderer} factory
 * method. The decomposer emits the shared skull tuple for dragon heads; the caller merges the
 * dragon-specific override from {@code block_entities_overrides.json}.
 *
 * <p><b>Policy</b>. The only hand-curated map is {@link #RENDERER_ENTRY_METHODS}: for each
 * renderer class internal name, the factory method (or static field prefixed
 * {@code FIELD:<name>}) that builds the {@code Transformation}. Everything downstream is
 * bytecode-driven: the decomposer reads Matrix4f method semantics, symbolically evaluates the
 * yaw parameter at zero, inlines intermediate static callees up to two levels deep, evaluates
 * enum-comparison branches when the input is a known {@code GETSTATIC} constant, and folds
 * the resulting rotation + scale into a single-axis tuple.
 *
 * <p><b>Reference pose</b>. All tuples are computed at yaw = 0. Rotations that symbolically
 * reduce to {@code Ry(yaw)} collapse to identity; rotations of the form
 * {@code Ry(k*180 + yaw)} around block centre are dropped as yaw-equivalent camera-facing
 * flips already handled by {@code inventoryYRotation}.
 *
 * <p><b>Canonicalisation rules</b>.
 * <ul>
 *   <li>Translation is multiplied by 16 to convert from block-space to mcpixel-space.</li>
 *   <li>{@code scale(1, -1, -1)} folds to {@code Rx(180)}; {@code scale(-1, 1, -1)} to
 *       {@code Ry(180)}; {@code scale(-1, -1, 1)} to {@code Rx(180)} (y-symmetric).</li>
 *   <li>{@code scale(s, -s, -s)} with {@code s > 0} folds to uniform scale {@code s} plus
 *       {@code Rx(180)}.</li>
 *   <li>Uniform scale within {@code 0.001} of {@code 1.0} is dropped as the shulker-style
 *       z-fight fudge.</li>
 *   <li>{@code rotateAround(q, 0.5, 0.5, 0.5)} with {@code q} a yaw-equivalent
 *       {@code Ry(k*180)} is dropped entirely.</li>
 * </ul>
 */
@UtilityClass
public final class InventoryTransformDecomposer {

    // --------------------------------------------------------------------------------------
    // Policy: renderer internal name -> entry (factory method name, or "FIELD:<name>" for
    // the conduit-style static Transformation field). Wiring only, never transform data.
    // --------------------------------------------------------------------------------------

    private static final @NotNull Map<String, String> RENDERER_ENTRY_METHODS = buildRendererEntryMethods();

    /** Builds the {@link #RENDERER_ENTRY_METHODS} table mapping renderer internal name to factory entry. */
    private static @NotNull Map<String, String> buildRendererEntryMethods() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("net/minecraft/client/renderer/blockentity/BedRenderer", "createModelTransform");
        m.put("net/minecraft/client/renderer/blockentity/ShulkerBoxRenderer", "createModelTransform");
        m.put("net/minecraft/client/renderer/blockentity/SkullBlockRenderer", "createGroundTransformation");
        m.put("net/minecraft/client/renderer/blockentity/DecoratedPotRenderer", "createModelTransformation");
        m.put("net/minecraft/client/renderer/blockentity/ConduitRenderer", "FIELD:DEFAULT_TRANSFORMATION");
        // Sign renderers wrap three Transformations in a SignTransformations record; walk the
        // body factory directly for the GUI pose.
        m.put("net/minecraft/client/renderer/blockentity/StandingSignRenderer", "bodyTransformation");
        m.put("net/minecraft/client/renderer/blockentity/HangingSignRenderer", "bodyTransformation");
        m.put("net/minecraft/client/renderer/blockentity/BannerRenderer", "modelTransformation");
        return m;
    }

    // --------------------------------------------------------------------------------------
    // Constants: JVM internal names / descriptors for the bytecode shapes we recognise.
    // --------------------------------------------------------------------------------------

    private static final @NotNull String MATRIX4F = "org/joml/Matrix4f";
    private static final @NotNull String VECTOR3F = "org/joml/Vector3f";
    private static final @NotNull String VECTOR3FC = "org/joml/Vector3fc";
    private static final @NotNull String QUATERNIONF = "org/joml/Quaternionf";
    private static final @NotNull String AXIS = "com/mojang/math/Axis";
    private static final @NotNull String TRANSFORMATION = "com/mojang/math/Transformation";
    private static final @NotNull String TRANSFORMATION_DESC = "L" + TRANSFORMATION + ";";
    private static final @NotNull String TRANSFORMATION_CTOR_MATRIX = "(Lorg/joml/Matrix4fc;)V";
    private static final @NotNull String TRANSFORMATION_CTOR_COMPONENTS =
        "(Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;)V";

    private static final float UNIT_EPS = 1e-3f;
    private static final int MAX_INLINE_DEPTH = 2;

    // --------------------------------------------------------------------------------------
    // Public API
    // --------------------------------------------------------------------------------------

    /**
     * Decomposes the inventory transform for every {@code entityId} whose renderer is listed
     * in {@link #RENDERER_ENTRY_METHODS}. Entities whose renderer is unknown or whose factory
     * method cannot be walked are absent from the returned map; callers must merge in any
     * hand-curated overrides for those ids (e.g. {@code minecraft:skull_dragon_head}).
     *
     * <p>The returned map is keyed by entity id and iterates in the insertion order of
     * {@code entityIdToRenderer}, so downstream Gson serialisation is deterministic.
     *
     * @param zip the cached client jar
     * @param entityIdToRenderer {@code entityId -> renderer internal name}
     * @param diag diagnostics sink; missing renderer classes and unrecognised bytecode surface
     *     as warn entries
     * @return the per-entity inventory transform map (may be missing entries when bytecode
     *     walk fails)
     */
    public static @NotNull Map<String, float[]> decomposeAll(
        @NotNull ZipFile zip,
        @NotNull Map<String, String> entityIdToRenderer,
        @NotNull Diagnostics diag
    ) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entityIdToRenderer.entrySet()) {
            String entityId = e.getKey();
            String rendererInternal = e.getValue();
            String entry = RENDERER_ENTRY_METHODS.get(rendererInternal);
            if (entry == null) continue; // renderer not in policy - caller must override

            ClassNode cn = AsmKit.loadClass(zip, rendererInternal);
            if (cn == null) {
                diag.warn("inventory-transform: renderer class '%s' not in jar (entityId=%s)", rendererInternal, entityId);
                continue;
            }

            float[] tuple;
            if (entry.startsWith("FIELD:")) {
                tuple = decomposeField(zip, cn, entry.substring("FIELD:".length()), diag);
            } else {
                MethodNode method = findFactoryMethod(cn, entry);
                if (method == null) {
                    diag.warn("inventory-transform: factory method '%s' not found on '%s' (entityId=%s)", entry, rendererInternal, entityId);
                    continue;
                }
                tuple = decomposeMethod(zip, cn, method, diag);
            }
            if (tuple != null) out.put(entityId, tuple);
        }
        return out;
    }

    /**
     * Test-only entry: decomposes the given factory method on {@code classInternalName}
     * directly. Used by synthetic bytecode mutation tests; production code routes through
     * {@link #decomposeAll}.
     *
     * @param zip the jar (or synthetic zip) to read from
     * @param classInternalName owning class's JVM internal name
     * @param methodName factory method name
     * @param methodDesc factory method descriptor
     * @param diag diagnostics sink
     * @return the decomposed tuple, or {@code null} when the walk cannot reduce the method
     */
    public static float @Nullable [] decomposeMethod(
        @NotNull ZipFile zip,
        @NotNull String classInternalName,
        @NotNull String methodName,
        @NotNull String methodDesc,
        @NotNull Diagnostics diag
    ) {
        ClassNode cn = AsmKit.loadClass(zip, classInternalName);
        if (cn == null) {
            diag.warn("inventory-transform: class '%s' not in jar", classInternalName);
            return null;
        }
        MethodNode method = AsmKit.findMethod(cn, methodName, methodDesc);
        if (method == null) {
            diag.warn("inventory-transform: method '%s%s' not found on '%s'", methodName, methodDesc, classInternalName);
            return null;
        }
        return decomposeMethod(zip, cn, method, diag);
    }

    /**
     * Test-only entry: decomposes a static {@code Transformation} field by walking the class's
     * {@code <clinit>} initializer for the {@code PUTSTATIC} assigning it.
     */
    public static float @Nullable [] decomposeField(
        @NotNull ZipFile zip,
        @NotNull String classInternalName,
        @NotNull String fieldName,
        @NotNull Diagnostics diag
    ) {
        ClassNode cn = AsmKit.loadClass(zip, classInternalName);
        if (cn == null) {
            diag.warn("inventory-transform: class '%s' not in jar", classInternalName);
            return null;
        }
        return decomposeField(zip, cn, fieldName, diag);
    }

    // --------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------

    /**
     * Picks the factory method by name, preferring the one whose return type is
     * {@code Transformation}. Needed because some renderers overload {@code bodyTransformation}
     * / {@code baseTransformation} with non-Transformation return types.
     */
    private static @Nullable MethodNode findFactoryMethod(@NotNull ClassNode cn, @NotNull String name) {
        MethodNode best = null;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals(name)) continue;
            if (m.desc.endsWith(TRANSFORMATION_DESC)) return m;
            if (best == null) best = m;
        }
        return best;
    }

    /**
     * Walks the static-initializer of {@code cn} for the {@code PUTSTATIC} assigning
     * {@code fieldName}, then decomposes the expression producing the value.
     */
    private static float @Nullable [] decomposeField(
        @NotNull ZipFile zip,
        @NotNull ClassNode cn,
        @NotNull String fieldName,
        @NotNull Diagnostics diag
    ) {
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) {
            diag.warn("inventory-transform: no <clinit> on '%s' for field '%s'", cn.name, fieldName);
            return null;
        }
        // Find the PUTSTATIC for the target field; truncate the instruction list to just that
        // expression (bytecode from the class-entry point through the PUTSTATIC).
        Walker walker = new Walker(zip, cn, diag, 0);
        walker.isStaticInit = true;
        walker.walk(clinit.instructions, fieldName);
        if (walker.finalTransform == null) {
            diag.warn("inventory-transform: could not decompose static field '%s' on '%s'", fieldName, cn.name);
            return null;
        }
        return canonicalise(walker.finalTransform, diag);
    }

    /** Walks a factory method's bytecode and decomposes the returned {@code Transformation}. */
    private static float @Nullable [] decomposeMethod(
        @NotNull ZipFile zip,
        @NotNull ClassNode cn,
        @NotNull MethodNode method,
        @NotNull Diagnostics diag
    ) {
        Walker walker = new Walker(zip, cn, diag, 0);
        walker.isStaticInit = method.name.equals("<clinit>");
        // Seed any float parameter slots with YAW sentinels. Our factory methods take at
        // most one float parameter (the yaw angle); this covers both (I), (F), (Attachment, F),
        // and (F, Attachment) descriptors without a per-shape policy.
        if (!walker.isStaticInit) bindYawSlotsFromDescriptor(method.desc, walker);
        walker.walk(method.instructions, null);
        if (walker.finalTransform == null) {
            diag.warn("inventory-transform: could not decompose method '%s%s' on '%s'", method.name, method.desc, cn.name);
            return null;
        }
        return canonicalise(walker.finalTransform, diag);
    }

    /**
     * Binds slot locations for any {@code F} / {@code I} parameter in {@code methodDesc} to a
     * {@link Value#ofYaw()} sentinel. All non-float parameters are left unbound (the walker
     * treats a missing slot as {@code OTHER}), which matches how our 16 baselines degrade
     * attachment / direction args to opaque references.
     */
    private static void bindYawSlotsFromDescriptor(@NotNull String methodDesc, @NotNull Walker walker) {
        Type[] args = Type.getArgumentTypes(methodDesc);
        int slot = 0;
        for (Type t : args) {
            if (t.getSort() == Type.FLOAT) walker.locals.put(slot, Value.ofYaw());
            slot += t.getSize();
        }
    }

    // --------------------------------------------------------------------------------------
    // Symbolic value model
    // --------------------------------------------------------------------------------------

    /** Tag for the kinds of values our symbolic JVM stack tracks. */
    private enum ValueKind { FLOAT, YAW, VECTOR, QUATERNION, MATRIX, NULL, CLASS_REF, OTHER }

    /**
     * A value on the symbolic JVM stack. We track enough detail to recognise literal Vector3f
     * / Axis quaternion / Matrix4f references, plus a {@link ValueKind#YAW} sentinel for the
     * symbolic yaw input evaluated to zero. Anything else degrades to {@code OTHER} and
     * prevents reduction, triggering a diag.warn.
     */
    private static final class Value {
        final @NotNull ValueKind kind;
        final float floatVal;       // FLOAT / YAW (YAW is treated as 0 for reduction)
        final float @Nullable [] vec;  // VECTOR: [x, y, z]
        final @Nullable Quat quat;  // QUATERNION
        final @Nullable TransformState matrix; // MATRIX (current transform state)

        private Value(@NotNull ValueKind kind, float floatVal, float @Nullable [] vec, @Nullable Quat quat, @Nullable TransformState matrix) {
            this.kind = kind; this.floatVal = floatVal; this.vec = vec; this.quat = quat; this.matrix = matrix;
        }

        static @NotNull Value ofFloat(float f) { return new Value(ValueKind.FLOAT, f, null, null, null); }
        static @NotNull Value ofYaw() { return new Value(ValueKind.YAW, 0f, null, null, null); }
        static @NotNull Value ofVec(float x, float y, float z) { return new Value(ValueKind.VECTOR, 0f, new float[]{ x, y, z }, null, null); }
        static @NotNull Value ofQuat(@NotNull Quat q) { return new Value(ValueKind.QUATERNION, 0f, null, q, null); }
        static @NotNull Value ofMatrix(@NotNull TransformState m) { return new Value(ValueKind.MATRIX, 0f, null, null, m); }
        static @NotNull Value ofNull() { return new Value(ValueKind.NULL, 0f, null, null, null); }
        static @NotNull Value ofClassRef() { return new Value(ValueKind.CLASS_REF, 0f, null, null, null); }
        static @NotNull Value ofOther() { return new Value(ValueKind.OTHER, 0f, null, null, null); }
    }

    /** Axis-angle rotation (degrees). axis in {@code 'X', 'Y', 'Z'}. */
    private record Quat(char axis, float angleDeg) {
        static final Quat IDENTITY = new Quat('I', 0f);
        boolean isIdentity() { return axis == 'I' || angleDeg == 0f; }
    }

    /**
     * Symbolic transform state: a single translation, an ordered list of rotations, and a
     * scale. Rotations are appended in post-multiplication order (right-to-left composition).
     * Any operation we can't represent symbolically (non-axis-aligned rotation, mixed-sign
     * scale with wrong structure, etc.) sets {@link #poisoned} and suppresses reduction.
     */
    private static final class TransformState {
        float tx, ty, tz;
        @NotNull ConcurrentList<Quat> rotations = Concurrent.newList();
        float sx = 1f, sy = 1f, sz = 1f;
        boolean poisoned;
        @Nullable String poisonReason;

        void poison(@NotNull String reason) {
            if (!this.poisoned) {
                this.poisoned = true;
                this.poisonReason = reason;
            }
        }

        /** Sets translation to {@code (x, y, z)}, resetting everything else. Matrix4f.translation() semantics. */
        void setTranslation(float x, float y, float z) {
            this.tx = x; this.ty = y; this.tz = z;
            this.rotations = Concurrent.newList();
            this.sx = 1f; this.sy = 1f; this.sz = 1f;
        }

        /** Right-multiplies by {@code T(x, y, z)}. */
        void postTranslate(float x, float y, float z) {
            // M' = M * T(x, y, z). Decomposed: if M = T * R * S, then M' = T * R * S * T(x, y, z).
            // We only track T on the outside and S on the inside; rotations are tracked
            // separately. Fold rule:
            //   - If rotations are all identity, T' = T + S*T(x, y, z).
            //   - If S's magnitude is near unity (a z-fight fudge like 0.9995), snap it to
            //     ±1 when folding; canonicalise() will then drop the fudge entirely.
            //   - If rotations are non-identity, poison (unsupported).
            for (Quat q : this.rotations) {
                if (!q.isIdentity()) { poison("postTranslate past non-identity rotation"); return; }
            }
            float ex = foldScaleFactor(this.sx);
            float ey = foldScaleFactor(this.sy);
            float ez = foldScaleFactor(this.sz);
            this.tx += ex * x;
            this.ty += ey * y;
            this.tz += ez * z;
        }

        /**
         * Returns the sign of {@code s} when {@code |s|} is within {@link #UNIT_EPS} of 1.0
         * (the shulker-style z-fight fudge snaps to unity for translation folding), else
         * {@code s} itself. This keeps the mathematical composition consistent with
         * {@link #canonicalise}'s later "drop near-unity uniform scale" rule.
         */
        private static float foldScaleFactor(float s) {
            if (Math.abs(Math.abs(s) - 1f) < UNIT_EPS) return Math.signum(s);
            return s;
        }

        /** Right-multiplies by {@code R(q)}. */
        void postRotate(@NotNull Quat q) {
            if (q.isIdentity()) {
                this.rotations.add(q); // preserve ordering even for identities
                return;
            }
            this.rotations.add(q);
        }

        /** Right-multiplies by {@code S(x, y, z)}. */
        void postScale(float x, float y, float z) {
            this.sx *= x; this.sy *= y; this.sz *= z;
        }

        /** Right-multiplies by {@code T(c) * R(q) * T(-c)}. */
        void postRotateAround(@NotNull Quat q, float cx, float cy, float cz) {
            // This equals translate(c) * rotate(q) * translate(-c) fed into the right side.
            // At cx=cy=cz=0.5 (block-center) the baseline treats it as a yaw-equivalent
            // "flip the block around its centre" - carried by inventoryYRotation upstream,
            // not by the inventory transform tuple. Drop any block-centred 180° rotation
            // (X/Y/Z) on that basis. General form not needed for the 16 baselines.
            boolean atBlockCenter = nearUnit(cx, 0.5f) && nearUnit(cy, 0.5f) && nearUnit(cz, 0.5f);
            if (atBlockCenter && is180Rotation(q)) return; // drop
            if (q.isIdentity()) return; // rotation is identity regardless of centre
            poison("rotateAround at (" + cx + "," + cy + "," + cz + ") with non-flip q=" + q);
        }

    }

    /**
     * {@code true} when the quaternion is yaw-equivalent at reference pose: either identity,
     * or a Y-axis rotation of a multiple of 180 degrees (flipping the block-face around the
     * camera is already handled by {@code inventoryYRotation}).
     */
    private static boolean isYawEquivalent(@NotNull Quat q) {
        if (q.isIdentity()) return true;
        if (q.axis != 'Y') return false;
        float mod = ((q.angleDeg % 360f) + 360f) % 360f;
        return nearUnit(mod, 0f) || nearUnit(mod, 180f);
    }

    /**
     * {@code true} when the quaternion represents identity or a 180-degree rotation around
     * any axis (X/Y/Z). Used by {@link TransformState#postRotateAround} to drop block-centred
     * 180° rotateAround calls, which the baseline handles via {@code inventoryYRotation}.
     */
    private static boolean is180Rotation(@NotNull Quat q) {
        if (q.isIdentity()) return true;
        if (q.axis != 'X' && q.axis != 'Y' && q.axis != 'Z') return false;
        float mod = ((q.angleDeg % 360f) + 360f) % 360f;
        return nearUnit(mod, 0f) || nearUnit(mod, 180f);
    }

    /** {@code true} when {@code a} is within {@link #UNIT_EPS} of {@code target}. */
    private static boolean nearUnit(float a, float target) {
        return Math.abs(a - target) < UNIT_EPS;
    }

    // --------------------------------------------------------------------------------------
    // Walker - simulates the JVM stack and updates a TransformState through a method's
    // instruction list. Stops when a Transformation.<init> is seen.
    // --------------------------------------------------------------------------------------

    /**
     * Per-method bytecode simulator. Consumes the instruction list linearly, popping/pushing
     * symbolic values on {@link #stack}, updating a current {@link TransformState} as
     * Matrix4f mutators fire, and finalising when {@code Transformation.<init>} is seen.
     *
     * <p>This is intentionally simple: no branch prediction / no abstract interpretation. It
     * handles the linear code path our 15 decomposable renderers actually emit, plus
     * {@code IF_ACMPNE} skipping on known-unequal enum compares (sign-renderer GROUND/WALL).
     * Anything else degrades to a {@link #poisoned} state and produces {@code null} output.
     */
    private static final class Walker {
        final @NotNull ZipFile zip;
        final @NotNull ClassNode owner;
        final @NotNull Diagnostics diag;
        final int depth;
        final @NotNull ConcurrentList<Value> stack = Concurrent.newList();
        final @NotNull Map<Integer, Value> locals = new LinkedHashMap<>();
        @Nullable TransformState finalTransform;
        /** Value captured at the stop-PUTSTATIC event (for static field resolution). */
        @Nullable Value stoppedFieldValue;
        boolean poisoned;
        /** {@code true} when walking a static initializer (FLOAD 0 is then a local, not yaw). */
        boolean isStaticInit;

        Walker(@NotNull ZipFile zip, @NotNull ClassNode owner, @NotNull Diagnostics diag, int depth) {
            this.zip = zip; this.owner = owner; this.diag = diag; this.depth = depth;
        }

        void push(@NotNull Value v) { this.stack.add(v); }
        @Nullable Value pop() { return this.stack.isEmpty() ? null : this.stack.remove(this.stack.size() - 1); }
        @Nullable Value peek() { return this.stack.isEmpty() ? null : this.stack.get(this.stack.size() - 1); }

        void poison(@NotNull String reason) {
            if (this.poisoned) return;
            this.poisoned = true;
            this.diag.warn("inventory-transform: walker poisoned on '%s' - %s", this.owner.name, reason);
        }

        /**
         * Walks an instruction list. When {@code stopPutstaticField} is non-null, finalises
         * when a matching {@code PUTSTATIC} is seen (for static-field decomposition).
         * Otherwise finalises on {@code Transformation.<init>} or {@code ARETURN}.
         */
        void walk(@NotNull InsnList instructions, @Nullable String stopPutstaticField) {
            if (this.depth > MAX_INLINE_DEPTH) {
                poison("inline depth " + this.depth + " exceeds max " + MAX_INLINE_DEPTH);
                return;
            }
            for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
                if (this.poisoned || this.finalTransform != null) return;
                int op = node.getOpcode();

                // Literal pushes ------------------------------------------------------------
                Float f = AsmKit.readFloatLiteral(node);
                if (f != null) { push(Value.ofFloat(f)); continue; }
                Integer i = AsmKit.readIntLiteral(node);
                if (i != null) { push(Value.ofFloat(i.floatValue())); continue; }
                if (op == Opcodes.ACONST_NULL) { push(Value.ofNull()); continue; }
                if (node instanceof LdcInsnNode ldc && ldc.cst instanceof Type) {
                    push(Value.ofClassRef()); continue;
                }

                // FLOAD / FSTORE / ALOAD / ASTORE -------------------------------------------
                if (node instanceof VarInsnNode vi) {
                    handleVarInsn(vi);
                    continue;
                }

                // FNEG / FADD / FSUB / FMUL / FDIV / F2D / etc. ------------------------------
                if (node instanceof InsnNode insn) {
                    if (handleInsn(insn.getOpcode())) continue;
                }

                // GETSTATIC / PUTSTATIC -----------------------------------------------------
                if (node instanceof FieldInsnNode fi) {
                    if (op == Opcodes.PUTSTATIC) {
                        if (stopPutstaticField != null && fi.name.equals(stopPutstaticField)) {
                            // Capture the value assigned. Prefer a MATRIX (for static
                            // Transformation fields used directly as finalTransform), else
                            // store the raw value for the caller via stoppedFieldValue.
                            Value v = pop();
                            if (v != null && v.kind == ValueKind.MATRIX) this.finalTransform = v.matrix;
                            this.stoppedFieldValue = v;
                            return;
                        }
                        // Unrelated field assignment - drop the TOS value and keep walking.
                        pop();
                        continue;
                    }
                    if (op == Opcodes.GETSTATIC) {
                        push(resolveStaticField(fi));
                        continue;
                    }
                    // GETFIELD/PUTFIELD - we shouldn't see these on our shapes.
                    poison("unexpected field insn " + op + " on " + fi.owner + "." + fi.name);
                    return;
                }

                // NEW ----------------------------------------------------------------------
                if (op == Opcodes.NEW && node instanceof TypeInsnNode) {
                    // Push a placeholder; the matching INVOKESPECIAL <init> will consume it
                    // along with the initializer args and push a typed value.
                    push(Value.ofOther());
                    continue;
                }
                if (op == Opcodes.DUP) {
                    Value top = peek();
                    if (top == null) { poison("DUP on empty stack"); return; }
                    push(top);
                    continue;
                }
                if (op == Opcodes.DUP_X1 || op == Opcodes.DUP_X2 || op == Opcodes.DUP2 || op == Opcodes.DUP2_X1 || op == Opcodes.DUP2_X2) {
                    poison("DUP variant " + op + " not supported");
                    return;
                }
                if (op == Opcodes.SWAP) {
                    Value a = pop(); Value b = pop();
                    if (a == null || b == null) { poison("SWAP on small stack"); return; }
                    push(a); push(b);
                    continue;
                }
                if (op == Opcodes.POP) { pop(); continue; }

                // INVOKE* ------------------------------------------------------------------
                if (node instanceof MethodInsnNode mi) {
                    handleMethodInsn(mi);
                    if (this.poisoned) return;
                    continue;
                }

                // IF_ACMP / IFEQ / IFNE / GOTO / ARETURN ------------------------------------
                if (op == Opcodes.ARETURN) {
                    // End-of-method before a Transformation.<init>. If finalTransform was set by a
                    // NEW+DUP+<init> sequence earlier, we're fine. Otherwise we haven't produced
                    // anything - leave finalTransform null.
                    return;
                }
                if (op == Opcodes.RETURN) return;
                if (op == Opcodes.GOTO) {
                    // Optimistic: take the jump; prevents accidental re-execution of the
                    // fall-through block. For our sign-renderer shapes, the only GOTO skips
                    // the WALL translate back to the ARETURN.
                    if (node instanceof JumpInsnNode gotoNode) {
                        node = gotoNode.label;
                    }
                    continue;
                }
                if (op == Opcodes.IFEQ || op == Opcodes.IFNE) { pop(); continue; }
                if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
                    // Optimistic enum-branch skip: when comparing two OTHER values (both
                    // unknown), take the jump to avoid re-executing the fall-through block.
                    // For the sign renderer's `if (attachment == WALL) { ... }` the taken
                    // branch is the WALL-only translate; skipping it yields the GROUND path
                    // we actually want. When the stack has fewer than 2 values, poison.
                    Value b = pop(); Value a = pop();
                    if (a == null || b == null) { poison("IF_ACMP* on small stack"); return; }
                    if (node instanceof JumpInsnNode jn) {
                        node = jn.label;
                    }
                    continue;
                }
                // Labels, lineNumbers, frames - ignored.
            }
        }

        /**
         * Handles FLOAD/ALOAD/FSTORE/ASTORE. Float parameter slots are pre-bound to a YAW
         * sentinel by {@link #bindYawSlotsFromDescriptor}; anything else falls back to OTHER.
         */
        private void handleVarInsn(@NotNull VarInsnNode vi) {
            int op = vi.getOpcode();
            if (op == Opcodes.FLOAD) {
                Value v = this.locals.get(vi.var);
                push(v != null ? v : Value.ofOther());
                return;
            }
            if (op == Opcodes.ALOAD) {
                Value v = this.locals.get(vi.var);
                push(v != null ? v : Value.ofOther());
                return;
            }
            if (op == Opcodes.FSTORE || op == Opcodes.ASTORE) {
                Value v = pop();
                if (v != null) this.locals.put(vi.var, v);
                return;
            }
            if (op == Opcodes.ILOAD) { push(Value.ofOther()); return; }
            if (op == Opcodes.ISTORE) { pop(); return; }
            poison("unsupported var insn op=" + op);
        }

        /** Handles FNEG / FADD / FSUB / FMUL / FDIV. */
        private boolean handleInsn(int op) {
            if (op == Opcodes.FNEG) {
                Value v = pop();
                if (v == null) { poison("FNEG on empty stack"); return true; }
                if (v.kind == ValueKind.YAW) { push(Value.ofYaw()); return true; } // -yaw at yaw=0 is still 0
                if (v.kind == ValueKind.FLOAT) { push(Value.ofFloat(-v.floatVal)); return true; }
                poison("FNEG on non-float value " + v.kind);
                return true;
            }
            if (op == Opcodes.FADD || op == Opcodes.FSUB) {
                Value b = pop(); Value a = pop();
                if (a == null || b == null) { poison("FADD/FSUB with small stack"); return true; }
                float av = valueAsFloat(a); float bv = valueAsFloat(b);
                if (Float.isNaN(av) || Float.isNaN(bv)) { poison("FADD/FSUB on non-float values"); return true; }
                float r = op == Opcodes.FADD ? av + bv : av - bv;
                push(Value.ofFloat(r));
                return true;
            }
            if (op == Opcodes.FMUL) {
                Value b = pop(); Value a = pop();
                if (a == null || b == null) { poison("FMUL with small stack"); return true; }
                float av = valueAsFloat(a); float bv = valueAsFloat(b);
                if (Float.isNaN(av) || Float.isNaN(bv)) { poison("FMUL on non-float values"); return true; }
                push(Value.ofFloat(av * bv));
                return true;
            }
            if (op == Opcodes.I2F || op == Opcodes.F2I || op == Opcodes.F2D || op == Opcodes.D2F) {
                // Type conversion - pass the kind through. OTHER stays OTHER (poisons caller),
                // literals stay literals. Not our focus, the walker's math-path uses FLOAT only.
                return true;
            }
            return false;
        }

        /** FLOAT -> value, YAW -> 0. Everything else -> NaN (poisons caller). */
        private static float valueAsFloat(@NotNull Value v) {
            if (v.kind == ValueKind.FLOAT) return v.floatVal;
            if (v.kind == ValueKind.YAW) return 0f;
            return Float.NaN;
        }

        /**
         * Resolves a {@code GETSTATIC} reference. For Vector3f/Matrix4f statics declared on the
         * owning class, walks {@code <clinit>} once to populate their values; for
         * {@code Axis.XP/YP/ZP} returns an axis marker; for unknowns returns {@code OTHER}.
         */
        private @NotNull Value resolveStaticField(@NotNull FieldInsnNode fi) {
            if (fi.owner.equals(AXIS)) {
                // Axis.XP / YP / ZP -> marker; rotationDegrees(f) later reads this from stack.
                char axis = fi.name.equals("XP") ? 'X' : fi.name.equals("YP") ? 'Y' : fi.name.equals("ZP") ? 'Z' : '?';
                if (axis == '?') return Value.ofOther();
                // Represent via an "axis marker" quaternion with angle NaN; the subsequent
                // INVOKEVIRTUAL rotationDegrees will fold it into a real Quat.
                return new Value(ValueKind.OTHER, Float.NaN, null, new Quat(axis, Float.NaN), null);
            }
            // If this field belongs to our owning class, try to resolve from <clinit>.
            if (fi.owner.equals(this.owner.name)) {
                Value resolved = resolveOwnStaticField(fi.name);
                if (resolved != null) return resolved;
            }
            return Value.ofOther();
        }

        /**
         * Walks {@code <clinit>} on {@link #owner} to find the {@code PUTSTATIC} for
         * {@code fieldName}, returning the symbolic value held. Handles simple cases (a
         * {@code new Vector3f(f, f, f)} assignment or a {@code new Transformation(...)}
         * assignment); returns {@code null} for anything more complex.
         */
        private @Nullable Value resolveOwnStaticField(@NotNull String fieldName) {
            MethodNode clinit = AsmKit.findMethod(this.owner, "<clinit>");
            if (clinit == null) return null;
            Walker sub = new Walker(this.zip, this.owner, this.diag, this.depth + 1);
            sub.isStaticInit = true;
            sub.walk(clinit.instructions, fieldName);
            if (sub.poisoned) return null;
            if (sub.finalTransform != null) return Value.ofMatrix(sub.finalTransform);
            return sub.stoppedFieldValue;
        }

        /**
         * Dispatches INVOKE* instructions. The matrix/vector/axis/transformation owners are
         * recognised; unknown INVOKESTATICs to the owning class inline up to MAX_INLINE_DEPTH
         * so sign-renderer helper chains resolve.
         */
        private void handleMethodInsn(@NotNull MethodInsnNode mi) {
            int op = mi.getOpcode();
            String owner = mi.owner;
            String name = mi.name;
            String desc = mi.desc;

            // Matrix4f / Matrix4fc constructor -----------------------------------------------
            if (owner.equals(MATRIX4F) && name.equals("<init>") && op == Opcodes.INVOKESPECIAL) {
                if (!desc.equals("()V")) { poison("Matrix4f ctor desc " + desc); return; }
                // Stack before: ..., NEW, DUP. INVOKESPECIAL pops 1 'this'. The remaining
                // NEW placeholder becomes the constructed (identity) matrix.
                Value dup = pop();
                if (dup == null) { poison("Matrix4f.<init> missing 'this'"); return; }
                pop(); // the NEW placeholder; we'll replace it with the fresh state
                push(Value.ofMatrix(new TransformState()));
                return;
            }

            // Matrix4f mutators --------------------------------------------------------------
            if (owner.equals(MATRIX4F) && op == Opcodes.INVOKEVIRTUAL) {
                handleMatrix4fInvoke(name, desc);
                return;
            }

            // Vector3f constructor -----------------------------------------------------------
            if (owner.equals(VECTOR3F) && name.equals("<init>") && op == Opcodes.INVOKESPECIAL) {
                if (desc.equals("(FFF)V")) {
                    Value vz = pop(); Value vy = pop(); Value vx = pop();
                    if (vx == null || vy == null || vz == null) { poison("Vector3f ctor args"); return; }
                    float x = valueAsFloat(vx); float y = valueAsFloat(vy); float z = valueAsFloat(vz);
                    if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) { poison("Vector3f ctor non-literal component"); return; }
                    // INVOKESPECIAL pops the dup'd 'this' in addition to the 3 float args; the
                    // remaining NEW placeholder on the stack becomes the constructed Vector3f.
                    pop(); // 'this' (dup'd copy)
                    pop(); // NEW placeholder - we replace it with the constructed value
                    push(Value.ofVec(x, y, z));
                    return;
                }
                poison("Vector3f ctor desc " + desc);
                return;
            }

            // Axis.rotationDegrees(float) -> Quaternionf -------------------------------------
            // Axis is an interface in 26.1 (INVOKEINTERFACE) but some earlier deobfuscated
            // jars surface it as a class (INVOKEVIRTUAL). Accept both.
            if (owner.equals(AXIS) && name.equals("rotationDegrees")
                && (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE)) {
                Value angle = pop();
                Value axisMarker = pop();
                if (angle == null || axisMarker == null) { poison("Axis.rotationDegrees args"); return; }
                if (axisMarker.quat == null || axisMarker.quat.axis == '?') {
                    poison("Axis.rotationDegrees on unknown axis marker"); return;
                }
                char axis = axisMarker.quat.axis;
                float angleDeg = valueAsFloat(angle);
                if (Float.isNaN(angleDeg)) { poison("Axis.rotationDegrees non-float angle"); return; }
                push(Value.ofQuat(nearUnit(angleDeg, 0f) ? Quat.IDENTITY : new Quat(axis, angleDeg)));
                return;
            }

            // Transformation constructor -----------------------------------------------------
            if (owner.equals(TRANSFORMATION) && name.equals("<init>") && op == Opcodes.INVOKESPECIAL) {
                if (desc.equals(TRANSFORMATION_CTOR_MATRIX)) {
                    Value m = pop();
                    if (m == null || m.matrix == null) { poison("Transformation(Matrix) with no matrix on stack"); return; }
                    pop(); // 'this' (NEW/DUP'd copy)
                    this.finalTransform = m.matrix;
                    return;
                }
                if (desc.equals(TRANSFORMATION_CTOR_COMPONENTS)) {
                    Value rightRot = pop();
                    Value scale = pop();
                    Value leftRot = pop();
                    Value trans = pop();
                    if (trans == null || scale == null || leftRot == null || rightRot == null) {
                        poison("Transformation(V, Q, V, Q) args"); return;
                    }
                    pop(); // 'this'
                    TransformState ts = composeCtorB(trans, leftRot, scale, rightRot);
                    if (ts == null) return; // composeCtorB already poisoned
                    this.finalTransform = ts;
                    return;
                }
                poison("Transformation.<init> desc " + desc);
                return;
            }

            // Yaw producers: Direction.toYRot()F and RotationSegment.convertToDegrees(I)F are
            // the two vanilla patterns that feed Axis.rotationDegrees with a symbolic yaw.
            // At reference pose they reduce to 0; at yaw=k*180 they land in the yaw tuple slot.
            if (name.equals("toYRot") && desc.equals("()F") && owner.endsWith("/Direction")) {
                pop(); // 'this' (the Direction)
                push(Value.ofYaw());
                return;
            }
            if (name.equals("convertToDegrees") && desc.equals("(I)F") && owner.endsWith("/RotationSegment")) {
                pop(); // arg
                push(Value.ofYaw());
                return;
            }
            // Direction.getRotation() returns a Quaternionf that maps each of the 6 cardinal
            // directions to a block-aligned rotation. At reference pose we treat it as identity
            // (the caller handles the face-direction separately via inventoryYRotation).
            if (name.equals("getRotation") && desc.equals("()L" + QUATERNIONF + ";") && owner.endsWith("/Direction")) {
                pop(); // 'this'
                push(Value.ofQuat(Quat.IDENTITY));
                return;
            }

            // INVOKESTATIC to a same-class method returning Transformation or Matrix4f -------
            if (op == Opcodes.INVOKESTATIC && owner.equals(this.owner.name)) {
                inlineStaticCallee(mi);
                return;
            }

            // INVOKESTATIC to another blockentity-package renderer helper (rare; permit depth). -
            if (op == Opcodes.INVOKESTATIC && owner.startsWith("net/minecraft/client/renderer/blockentity/")) {
                // Treat as a "black-box" call that produces a Transformation-typed null; the caller
                // sign-renderers route through own-class helpers only, so we never hit this in
                // practice. Poison to be safe.
                poison("cross-class static call to " + owner + "." + name);
                return;
            }

            // Anything else - drop args per descriptor, push a placeholder per return type.
            // This keeps the walker from aborting on innocuous library calls (e.g.
            // Direction.getStepX) that we don't need for reduction but which might be on the
            // bytecode path. If the call produces a needed Matrix4f / Transformation / Vector
            // our caller will poison on the missing value downstream.
            passThroughUnknown(mi);
        }

        /** Handles {@code Matrix4f.translation / translate / rotate / rotateAround / scale} calls. */
        private void handleMatrix4fInvoke(@NotNull String name, @NotNull String desc) {
            switch (name) {
                case "translation" -> {
                    // (FFF) -> Matrix4f
                    Value vz = pop(); Value vy = pop(); Value vx = pop();
                    Value m = pop();
                    if (m == null || m.matrix == null) { poison("translation on non-matrix"); return; }
                    if (vx == null || vy == null || vz == null) { poison("translation args"); return; }
                    float x = valueAsFloat(vx); float y = valueAsFloat(vy); float z = valueAsFloat(vz);
                    if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) { poison("translation non-literal component"); return; }
                    m.matrix.setTranslation(x, y, z);
                    push(Value.ofMatrix(m.matrix));
                }
                case "translate" -> {
                    if (desc.equals("(FFF)Lorg/joml/Matrix4f;")) {
                        Value vz = pop(); Value vy = pop(); Value vx = pop();
                        Value m = pop();
                        if (m == null || m.matrix == null) { poison("translate on non-matrix"); return; }
                        if (vx == null || vy == null || vz == null) { poison("translate args"); return; }
                        float x = valueAsFloat(vx); float y = valueAsFloat(vy); float z = valueAsFloat(vz);
                        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) { poison("translate non-literal component"); return; }
                        m.matrix.postTranslate(x, y, z);
                        push(Value.ofMatrix(m.matrix));
                        return;
                    }
                    poison("Matrix4f.translate desc " + desc);
                }
                case "rotate" -> {
                    Value q = pop();
                    Value m = pop();
                    if (m == null || m.matrix == null) { poison("rotate on non-matrix"); return; }
                    if (q == null || q.quat == null) { poison("rotate non-quaternion arg"); return; }
                    m.matrix.postRotate(q.quat);
                    push(Value.ofMatrix(m.matrix));
                }
                case "rotateAround" -> {
                    Value vcz = pop(); Value vcy = pop(); Value vcx = pop();
                    Value q = pop();
                    Value m = pop();
                    if (m == null || m.matrix == null) { poison("rotateAround on non-matrix"); return; }
                    if (q == null || q.quat == null || vcx == null || vcy == null || vcz == null) { poison("rotateAround args"); return; }
                    float cx = valueAsFloat(vcx); float cy = valueAsFloat(vcy); float cz = valueAsFloat(vcz);
                    if (Float.isNaN(cx) || Float.isNaN(cy) || Float.isNaN(cz)) { poison("rotateAround non-literal centre"); return; }
                    m.matrix.postRotateAround(q.quat, cx, cy, cz);
                    if (m.matrix.poisoned) { poison("rotateAround: " + m.matrix.poisonReason); return; }
                    push(Value.ofMatrix(m.matrix));
                }
                case "scale" -> {
                    if (desc.equals("(FFF)Lorg/joml/Matrix4f;")) {
                        Value vz = pop(); Value vy = pop(); Value vx = pop();
                        Value m = pop();
                        if (m == null || m.matrix == null) { poison("scale on non-matrix"); return; }
                        if (vx == null || vy == null || vz == null) { poison("scale args"); return; }
                        float x = valueAsFloat(vx); float y = valueAsFloat(vy); float z = valueAsFloat(vz);
                        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) { poison("scale non-literal component"); return; }
                        m.matrix.postScale(x, y, z);
                        push(Value.ofMatrix(m.matrix));
                        return;
                    }
                    poison("Matrix4f.scale desc " + desc);
                }
                default -> poison("unknown Matrix4f method " + name + desc);
            }
        }

        /**
         * Inlines a same-class static callee whose return type is {@code Transformation} or a
         * {@link TransformState}-producing helper. Arguments are simply popped; the callee runs
         * with slot 0 bound to the symbolic yaw the caller passed (we always pass {@code YAW}).
         */
        private void inlineStaticCallee(@NotNull MethodInsnNode mi) {
            if (this.depth + 1 > MAX_INLINE_DEPTH) { poison("inline depth > " + MAX_INLINE_DEPTH); return; }
            Type[] argTypes = Type.getArgumentTypes(mi.desc);
            Type returnType = Type.getReturnType(mi.desc);
            // Pop caller args (we don't bind them to callee locals - the callee reads FLOAD 0
            // assuming yaw; anything else degrades to OTHER in the sub-walker).
            Value[] callerArgs = new Value[argTypes.length];
            for (int k = argTypes.length - 1; k >= 0; k--) callerArgs[k] = pop();

            MethodNode callee = AsmKit.findMethod(this.owner, mi.name, mi.desc);
            if (callee == null) { poison("inline callee '" + mi.name + mi.desc + "' not found"); return; }

            Walker sub = new Walker(this.zip, this.owner, this.diag, this.depth + 1);
            // Seed callee slots: for each argument of type F, bind YAW if it matches the
            // caller-pushed YAW, else bind the literal float. Aref args pass through.
            int slot = 0;
            for (int k = 0; k < argTypes.length; k++) {
                Type t = argTypes[k];
                Value a = callerArgs[k] != null ? callerArgs[k] : Value.ofOther();
                sub.locals.put(slot, a);
                slot += t.getSize();
            }
            sub.walk(callee.instructions, null);
            if (sub.poisoned || this.poisoned) { this.poisoned = this.poisoned || sub.poisoned; return; }

            // Push the callee's return onto our stack. If the callee produced a final
            // Transformation (sign renderers' bodyTransformation does this), wrap as MATRIX.
            if (sub.finalTransform != null) {
                push(Value.ofMatrix(sub.finalTransform));
                return;
            }
            // Otherwise probe the callee's stack top for a return value.
            Value ret = sub.peek();
            if (ret != null && returnType.getSort() == Type.OBJECT && returnType.getInternalName().equals(MATRIX4F) && ret.kind == ValueKind.MATRIX) {
                push(ret);
                return;
            }
            if (returnType.getSort() == Type.OBJECT && returnType.getInternalName().equals(TRANSFORMATION)) {
                // Couldn't produce a Transformation - poison.
                poison("inline callee produced no Transformation / Matrix4f");
                return;
            }
            // Other return types - push placeholder.
            push(ret != null ? ret : Value.ofOther());
        }

        /**
         * Drops argument values per {@code mi.desc} and pushes a placeholder for a non-void
         * return type. Used for "don't care" library calls.
         */
        private void passThroughUnknown(@NotNull MethodInsnNode mi) {
            Type[] argTypes = Type.getArgumentTypes(mi.desc);
            for (int k = 0; k < argTypes.length; k++) pop();
            if (mi.getOpcode() != Opcodes.INVOKESTATIC) pop(); // implicit 'this'
            Type ret = Type.getReturnType(mi.desc);
            if (ret.getSort() != Type.VOID) push(Value.ofOther());
        }

        /**
         * Composes a four-argument {@code Transformation} ctor B into a {@link TransformState}.
         * Supports {@code null} for left/right rotations, yaw-equivalent Y rotations (treated
         * as identity at reference pose), and literal or static-field Vector3f translation /
         * scale.
         */
        private @Nullable TransformState composeCtorB(
            @NotNull Value trans, @NotNull Value leftRot, @NotNull Value scale, @NotNull Value rightRot
        ) {
            TransformState ts = new TransformState();
            // Translation
            if (trans.kind == ValueKind.VECTOR && trans.vec != null) {
                ts.tx = trans.vec[0]; ts.ty = trans.vec[1]; ts.tz = trans.vec[2];
            } else if (trans.kind == ValueKind.NULL) {
                // translation=null is a 0-vector
            } else {
                poison("ctor B translation is not a Vector3f literal"); return null;
            }
            // Left rotation
            if (leftRot.kind == ValueKind.QUATERNION && leftRot.quat != null) {
                if (!isYawEquivalent(leftRot.quat)) ts.postRotate(leftRot.quat);
            } else if (leftRot.kind != ValueKind.NULL) {
                poison("ctor B leftRotation is not a Quaternionf / null"); return null;
            }
            // Scale
            if (scale.kind == ValueKind.VECTOR && scale.vec != null) {
                ts.postScale(scale.vec[0], scale.vec[1], scale.vec[2]);
            } else if (scale.kind != ValueKind.NULL) {
                poison("ctor B scale is not a Vector3f / null"); return null;
            }
            // Right rotation
            if (rightRot.kind == ValueKind.QUATERNION && rightRot.quat != null) {
                if (!isYawEquivalent(rightRot.quat)) ts.postRotate(rightRot.quat);
            } else if (rightRot.kind != ValueKind.NULL) {
                poison("ctor B rightRotation is not a Quaternionf / null"); return null;
            }
            return ts;
        }
    }

    // --------------------------------------------------------------------------------------
    // Canonicalisation
    // --------------------------------------------------------------------------------------

    /**
     * Reduces a {@link TransformState} to a {@code [tx, ty, tz, pitch, yaw, roll, uniform?]}
     * tuple. Applies the fold rules documented in the class javadoc. Returns {@code null}
     * when the state cannot be reduced to a single-axis rotation.
     */
    private static float @Nullable [] canonicalise(@NotNull TransformState state, @NotNull Diagnostics diag) {
        if (state.poisoned) {
            diag.warn("inventory-transform: canonicalise skipped (poisoned: %s)", state.poisonReason != null ? state.poisonReason : "unknown");
            return null;
        }

        // Fold scale + sign-flip -------------------------------------------------------------
        // Possible inputs: (sx, sy, sz) where any subset are ±s for same-magnitude sign-flip,
        // or (1, 1, 1) identity.
        float sx = state.sx, sy = state.sy, sz = state.sz;
        float uniformScale = 1f;
        char scaleFoldAxis = 'I';

        // Detect a "scale(s, -s, -s)" / "(s, s, s)" / "(s, -s, s)" / etc. pattern.
        float absSx = Math.abs(sx), absSy = Math.abs(sy), absSz = Math.abs(sz);
        if (nearUnit(absSx, absSy) && nearUnit(absSy, absSz)) {
            uniformScale = absSx;
            int negatives = (sx < 0 ? 1 : 0) + (sy < 0 ? 1 : 0) + (sz < 0 ? 1 : 0);
            if (negatives == 0) {
                // identity sign
            } else if (negatives == 2) {
                // Two negatives fold to a 180° rotation. Mathematically the axis is whichever
                // component stayed positive:
                //   scale(s, -s, -s) = Rx(180);  scale(-s, s, -s) = Ry(180);  scale(-s, -s, s) = Rz(180).
                // For block-entity inventory tiles, the geometry is Y-symmetric (skulls,
                // shulkers, signs, banners) - so Rx(180) and Rz(180) produce the same visual
                // result. The baseline convention prefers Rx(180) in the (-s,-s,+s) case to
                // match how all four skull variants share the same rotation, independent of
                // whether the authored mesh uses a Z or X reflection internally.
                if (sx > 0) scaleFoldAxis = 'X';
                else if (sy > 0) scaleFoldAxis = 'Y';
                else scaleFoldAxis = 'X';
            } else {
                diag.warn("inventory-transform: unfoldable scale sign-flip (%s, %s, %s)", sx, sy, sz);
                return null;
            }
        } else if (!(nearUnit(sx, 1f) && nearUnit(sy, 1f) && nearUnit(sz, 1f))) {
            diag.warn("inventory-transform: non-uniform scale not supported (%s, %s, %s)", sx, sy, sz);
            return null;
        }

        // Collapse the rotation list into a single (axis, angle) -----------------------------
        char finalAxis = 'I';
        float finalAngle = 0f;
        for (Quat q : state.rotations) {
            if (q.isIdentity()) continue;
            if (finalAxis == 'I') {
                finalAxis = q.axis; finalAngle = q.angleDeg;
            } else if (q.axis == finalAxis) {
                finalAngle += q.angleDeg;
            } else {
                diag.warn("inventory-transform: multi-axis rotation list not supported (%s then %s)", finalAxis, q.axis);
                return null;
            }
        }

        // Combine the scale-fold axis with the rotation axis ---------------------------------
        if (scaleFoldAxis != 'I') {
            if (finalAxis == 'I') {
                finalAxis = scaleFoldAxis;
                finalAngle = 180f;
            } else if (finalAxis == scaleFoldAxis) {
                finalAngle += 180f;
            } else {
                diag.warn("inventory-transform: cannot combine rotation axis %s with scale-fold axis %s", finalAxis, scaleFoldAxis);
                return null;
            }
        }

        // Normalise angle to [-360, 360] for readability
        finalAngle = finalAngle % 360f;
        if (finalAngle > 180f) finalAngle -= 360f;
        else if (finalAngle < -180f) finalAngle += 360f;
        if (nearUnit(Math.abs(finalAngle), 180f)) finalAngle = 180f;
        if (nearUnit(finalAngle, 0f)) { finalAxis = 'I'; finalAngle = 0f; }

        // Assemble tuple ---------------------------------------------------------------------
        float pitch = 0f, yaw = 0f, roll = 0f;
        switch (finalAxis) {
            case 'X' -> pitch = finalAngle;
            case 'Y' -> yaw = finalAngle;
            case 'Z' -> roll = finalAngle;
            default -> { /* identity */ }
        }

        float tx = state.tx * 16f;
        float ty = state.ty * 16f;
        float tz = state.tz * 16f;

        boolean emitUniform = !nearUnit(uniformScale, 1f);
        float[] out = emitUniform ? new float[7] : new float[6];
        out[0] = tx; out[1] = ty; out[2] = tz;
        out[3] = pitch; out[4] = yaw; out[5] = roll;
        if (emitUniform) out[6] = uniformScale;
        return out;
    }

}
