package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Exit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code inventory.transform} symbolic executor, cache-fed. Interprets a renderer's
 * GUI-{@code Transformation} factory bytecode into the
 * {@code [tx, ty, tz, pitchDeg, yawDeg, rollDeg, uniformScale?]} tuple at the reference pose
 * (yaw = 0, translation x 16 mcpixel). Poison-on-unknown emits nothing rather than garbage.
 *
 * <p>Models a {@code PoseStack} / {@code Matrix4f} as an accumulated {@code T . R . S}
 * ({@link State}): {@code Matrix4f.translation} OVERWRITES the whole matrix, {@code translate}
 * post-composes (folding into the outer translation only past pure-scale), {@code rotate} /
 * {@code rotateAround} append rotations (a block-centred 180 {@code rotateAround} is DROPPED - it
 * is the camera flip the {@code inventory.y_rotation} already carries), {@code scale} multiplies
 * the inner scale. {@code canonicalise} folds a uniform two-negative scale into a 180 rotation
 * ({@code scale(1,-1,-1) = Rx180}, {@code (-s,-s,+s) -> Rx180} by Y-symmetry) and normalises.
 * Same-class factory helpers ({@code baseTransformation}) inline (depth-bounded); the standing
 * sign's attachment branch is evaluated against the seeded attachment enum.
 */
final class TransformWalker {

    private static final int MAX_INLINE_DEPTH = 2;
    /** The canonicalisation tolerance - declared as {@link BlockTransformPolicies#CANONICALISE}. */
    private static final float UNIT_EPS = BlockTransformPolicies.canonicalUnitEps();
    private static final float MCPIXEL = 16f;

    private static final @NotNull String MATRIX4F = "org/joml/Matrix4f";
    private static final @NotNull String TRANSFORMATION = "com/mojang/math/Transformation";
    private static final @NotNull String VECTOR3F = "org/joml/Vector3f";
    private static final @NotNull String TRANSFORMATION_CTOR_MATRIX = "(Lorg/joml/Matrix4fc;)V";
    private static final @NotNull String TRANSFORMATION_CTOR_COMPONENTS =
        "(Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;)V";
    private static final @NotNull String VEC3_PARAMS = "(FFF)";
    private static final @NotNull String QUAT_PARAM = "(Lorg/joml/Quaternionfc;)";
    private static final @NotNull String QUAT_VEC3_PARAMS = "(Lorg/joml/Quaternionfc;FFF)";
    private static final @NotNull String ATTACHMENT_SUFFIX = "Attachment";
    private static final @NotNull String WALL_CONSTANT = "WALL";
    private static final @NotNull String FLOOR_CONSTANT = "FLOOR";

    private final @NotNull ClassNodeCache cache;

    TransformWalker(@NotNull ClassNodeCache cache) {
        this.cache = cache;
    }

    /**
     * Decomposes a renderer's GUI transform into the inventory tuple, or {@code null} when the
     * factory is unknown / unmodellable.
     *
     * @param rendererClass the renderer's JVM internal name
     * @param entry a method name, or {@code FIELD:<field>} for a static transform
     * @param attachment the standing-sign attachment constant to seed ({@code WALL} / {@code FLOOR}), or {@code null}
     * @return the {@code [tx, ty, tz, pitch, yaw, roll, scale?]} tuple, or {@code null}
     */
    float @Nullable [] decompose(@NotNull String rendererClass, @NotNull String entry, @Nullable String attachment) {
        ClassNode cn = this.cache.load(rendererClass);
        if (cn == null) return null;

        if (entry.startsWith(BlockTransformPolicies.FIELD_ENTRY_PREFIX)) {
            String field = entry.substring(BlockTransformPolicies.FIELD_ENTRY_PREFIX.length());
            MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
            if (clinit == null) return null;
            Frame frame = new Frame(0);
            frame.run(cn, clinit, field);
            // Only the Transformation live at the stop field's PUTSTATIC counts - a <clinit> may
            // construct other Transformations before (or instead of) the target field's.
            return frame.stopReached && frame.finalTransform != null ? canonicalise(frame.finalTransform) : null;
        }

        MethodNode method = findTransformMethod(cn, entry);
        if (method == null) return null;
        Frame frame = new Frame(0);
        frame.seedParameters(method, attachment);
        frame.run(cn, method, null);
        return frame.finalTransform == null ? null : canonicalise(frame.finalTransform);
    }

    /** The {@code Transformation}-returning overload of {@code name} (else the first of that name). */
    private static @Nullable MethodNode findTransformMethod(@NotNull ClassNode cn, @NotNull String name) {
        MethodNode fallback = null;
        for (MethodNode method : cn.methods)
            if (method.name.equals(name)) {
                if (AsmKit.descriptorReturns(method.desc, TRANSFORMATION)) return method;
                if (fallback == null) fallback = method;
            }
        return fallback;
    }

    // ------------------------------------------------------------------------------------
    // symbolic execution frame
    // ------------------------------------------------------------------------------------

    /** One method activation: an operand stack of {@link Val}s, seeded locals, and the depth guard. */
    private final class Frame {

        private final @NotNull Deque<Val> stack = new ArrayDeque<>();
        private final @NotNull Map<Integer, Val> locals = new HashMap<>();
        private final int depth;
        private @Nullable State finalTransform;
        private @Nullable Val returnValue;
        private boolean poisoned;
        private boolean stopReached;

        private Frame(int depth) {
            this.depth = depth;
        }

        /** Seeds float params with the reference yaw and any attachment-typed param with {@code attachment}. */
        private void seedParameters(@NotNull MethodNode method, @Nullable String attachment) {
            int slot = 0;
            for (Type arg : Type.getArgumentTypes(method.desc)) {
                if (arg.getSort() == Type.FLOAT) this.locals.put(slot, Val.yaw());
                else if (attachment != null && arg.getInternalName().endsWith(ATTACHMENT_SUFFIX))
                    this.locals.put(slot, Val.enumConst(attachment));
                slot += arg.getSize();
            }
        }

        /**
         * Runs the method body, following branches; stops at ARETURN, poison, or
         * {@code stopField}'s PUTSTATIC. A revisited instruction aborts loudly - vanilla
         * transform bodies never jump backward.
         */
        private void run(@NotNull ClassNode owner, @NotNull MethodNode method, @Nullable String stopField) {
            if (this.depth > MAX_INLINE_DEPTH) {
                this.poisoned = true;
                return;
            }
            Exit exit = AsmWalker.over(method).trace(in -> {
                AbstractInsnNode jump = step(owner, in, stopField);
                if (this.returnValue != null || this.stopReached || this.poisoned) return null;
                return jump != null ? jump : in.getNext();
            });
            if (exit == Exit.CYCLE) {
                throw new ToolingException(
                    "Transform walk of '%s.%s' revisited an instruction - a backward jump is not decomposable",
                    owner.name, method.name);
            }
        }

        /** Executes one instruction; returns a jump target when it diverts control, else {@code null}. */
        private @Nullable AbstractInsnNode step(@NotNull ClassNode owner, @NotNull AbstractInsnNode in, @Nullable String stopField) {
            int op = in.getOpcode();
            Float literal = AsmKit.readFloatLiteral(in);
            if (literal != null) {
                push(Val.number(literal));
                return null;
            }
            switch (op) {
                case Opcodes.NEW -> push(Val.other());
                case Opcodes.DUP -> push(peek());
                case Opcodes.ACONST_NULL -> push(Val.nul());
                case Opcodes.POP -> pop();
                case Opcodes.FNEG -> push(neg(pop()));
                case Opcodes.FADD -> push(Val.number(popFloat() + popFloat()));
                case Opcodes.FSUB -> {
                    float subtrahend = popFloat();
                    push(Val.number(popFloat() - subtrahend));
                }
                case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH -> push(Val.other());
                case Opcodes.FLOAD, Opcodes.ALOAD, Opcodes.ILOAD -> push(this.locals.getOrDefault(((VarInsnNode) in).var, Val.other()));
                case Opcodes.FSTORE, Opcodes.ASTORE, Opcodes.ISTORE -> this.locals.put(((VarInsnNode) in).var, pop());
                case Opcodes.GETSTATIC -> getStatic((FieldInsnNode) in);
                case Opcodes.PUTSTATIC -> {
                    if (((FieldInsnNode) in).name.equals(stopField)) {
                        this.stopReached = true;
                        return null;
                    }
                    pop();
                }
                case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                    return branch((JumpInsnNode) in, op);
                }
                case Opcodes.GOTO -> {
                    return ((JumpInsnNode) in).label;
                }
                case Opcodes.ARETURN -> this.returnValue = this.stack.isEmpty() ? Val.other() : pop();
                case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL ->
                    invoke(owner, (MethodInsnNode) in);
                default -> { /* pseudo-nodes, labels, frames - no stack effect */ }
            }
            return null;
        }

        /** Evaluates an {@code IF_ACMP} on two enum values (else poisons); returns the jump target when taken. */
        private @Nullable AbstractInsnNode branch(@NotNull JumpInsnNode in, int op) {
            Val rhs = pop();
            Val lhs = pop();
            if (lhs.kind != Kind.ENUM || rhs.kind != Kind.ENUM) {
                this.poisoned = true;
                return null;
            }
            boolean equal = lhs.text.equals(rhs.text);
            boolean take = (op == Opcodes.IF_ACMPEQ) == equal;
            return take ? in.label : null;
        }

        private void getStatic(@NotNull FieldInsnNode field) {
            if (field.owner.equals(VanillaSourceClasses.Types.MATH_AXIS)) {
                // XP / YP / ZP -> X / Y / Z; the N constants (XN...) carry a negated rotation sense.
                push(Val.axis(field.name.charAt(0), field.name.length() > 1 && field.name.charAt(1) == 'N'));
                return;
            }
            if (field.name.endsWith(WALL_CONSTANT) || field.name.equals(FLOOR_CONSTANT) || field.owner.endsWith(ATTACHMENT_SUFFIX)) {
                push(Val.enumConst(field.name));
                return;
            }
            if (field.desc.equals("Lorg/joml/Vector3fc;") || field.desc.equals("Lorg/joml/Vector3f;")) {
                float[] vec = resolveStaticVector(field.owner, field.name);
                push(vec == null ? Val.other() : Val.vector(vec));
                return;
            }
            push(Val.other());
        }

        private void invoke(@NotNull ClassNode owner, @NotNull MethodInsnNode call) {
            if (call.owner.equals(MATRIX4F)) {
                matrixInvoke(call);
                return;
            }
            if (call.owner.equals(TRANSFORMATION) && AsmKit.INIT.equals(call.name)) {
                transformationInit(call);
                return;
            }
            if (call.owner.equals(VECTOR3F) && AsmKit.INIT.equals(call.name) && call.desc.equals("(FFF)V")) {
                float[] vec = popVec3();
                pop();                       // the invokespecial receiver ref
                pop();                       // the NEW placeholder underneath the dup
                push(Val.vector(vec));
                return;
            }
            if (call.owner.equals(VanillaSourceClasses.Types.MATH_AXIS) && "rotationDegrees".equals(call.name)) {
                float angle = popFloat();
                Val axis = pop();
                if (axis.kind == Kind.AXIS) push(Val.quat(axis.axis, axis.number * angle));   // number = +-1 sense
                else push(Val.quat('X', angle));
                return;
            }
            if (call.owner.equals(VanillaSourceClasses.Types.DIRECTION) && "toYRot".equals(call.name)) {
                pop();
                push(Val.yaw());
                return;
            }
            if (call.owner.equals(VanillaSourceClasses.Types.DIRECTION) && "getRotation".equals(call.name)) {
                pop();
                push(Val.quat('I', 0f));
                return;
            }
            if (call.owner.equals(VanillaSourceClasses.Types.ROTATION_SEGMENT) && "convertToDegrees".equals(call.name)) {
                pop();
                push(Val.yaw());
                return;
            }
            if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(owner.name)) {
                inlineCallee(owner, call);
                return;
            }
            passThrough(call);
        }

        /**
         * Applies a {@code Matrix4f} instance op to the receiver {@link State} on the stack. Ops
         * match by name AND parameter shape; anything else (matrix-arg overloads, {@code mul}, the
         * {@code Vector3fc} translate) passes through per descriptor - the OTHER it pushes poisons
         * the next {@code popMatrix} rather than silently mis-popping the stack.
         */
        private void matrixInvoke(@NotNull MethodInsnNode call) {
            if (AsmKit.INIT.equals(call.name)) {
                pop();                       // the invokespecial receiver ref
                pop();                       // the NEW placeholder underneath the dup
                push(Val.matrix(new State()));
                return;
            }
            String name = call.name;
            if (call.desc.startsWith(VEC3_PARAMS)
                && ("translation".equals(name) || "translate".equals(name) || "scale".equals(name))) {
                float[] v = popVec3();
                State state = popMatrix();
                if (state != null)
                    switch (name) {
                        case "translation" -> state.setTranslation(v);
                        case "translate" -> state.postTranslate(v);
                        default -> state.postScale(v);
                    }
                push(Val.matrix(state));
                return;
            }
            if ("rotate".equals(name) && call.desc.startsWith(QUAT_PARAM)) {
                Val q = pop();
                State state = popMatrix();
                if (state != null && q.kind == Kind.QUAT) state.postRotate(q.axis, q.angle);
                push(Val.matrix(state));
                return;
            }
            if ("rotateAround".equals(name) && call.desc.startsWith(QUAT_VEC3_PARAMS)) {
                float[] c = popVec3();
                Val q = pop();
                State state = popMatrix();
                if (state != null) state.postRotateAround(q, c);
                push(Val.matrix(state));
                return;
            }
            passThrough(call);
        }

        /** Consumes a {@code Transformation.<init>} - matrix form or the 4-component form - into {@link #finalTransform}. */
        private void transformationInit(@NotNull MethodInsnNode call) {
            if (call.desc.equals(TRANSFORMATION_CTOR_MATRIX)) {
                State state = popMatrix();
                pop();                       // the Transformation receiver ref
                this.finalTransform = state;
                return;
            }
            if (call.desc.equals(TRANSFORMATION_CTOR_COMPONENTS)) {
                Val rightRot = pop();
                Val scale = pop();
                Val leftRot = pop();
                Val translation = pop();
                pop();                       // the Transformation receiver ref
                this.finalTransform = composeComponents(translation, leftRot, scale, rightRot);
                return;
            }
            for (int i = 0; i < Type.getArgumentTypes(call.desc).length + 1; i++) pop();
        }

        /** Inlines a same-class static factory ({@code baseTransformation}); pushes its returned value. */
        private void inlineCallee(@NotNull ClassNode owner, @NotNull MethodInsnNode call) {
            MethodNode callee = AsmKit.findMethod(owner, call.name, call.desc);
            if (callee == null) {
                passThrough(call);
                return;
            }
            Type[] args = Type.getArgumentTypes(call.desc);
            Val[] popped = new Val[args.length];
            for (int i = args.length - 1; i >= 0; i--) popped[i] = pop();

            Frame sub = new Frame(this.depth + 1);
            int slot = 0;
            for (int i = 0; i < args.length; i++) {
                sub.locals.put(slot, popped[i]);
                slot += args[i].getSize();
            }
            sub.run(owner, callee, null);
            if (sub.poisoned) {
                this.poisoned = true;
                return;
            }
            if (sub.finalTransform != null) push(Val.matrix(sub.finalTransform));
            else if (sub.returnValue != null) push(sub.returnValue);
            else push(Val.other());
        }

        /** A foreign call: drops its args (+ receiver for instance calls) and pushes a placeholder for a non-void return. */
        private void passThrough(@NotNull MethodInsnNode call) {
            int pops = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
            for (int i = 0; i < pops; i++) pop();
            if (!Type.getReturnType(call.desc).equals(Type.VOID_TYPE)) push(Val.other());
        }

        /** Assembles the 4-component {@code Transformation} ctor into a {@link State}. */
        private @NotNull State composeComponents(@NotNull Val translation, @NotNull Val leftRot, @NotNull Val scale, @NotNull Val rightRot) {
            State state = new State();
            if (translation.kind == Kind.VECTOR) state.setTranslation(translation.vec);
            else if (translation.kind != Kind.NULL) state.poisoned = true;
            applyComponentRotation(state, leftRot);
            if (scale.kind == Kind.VECTOR) state.postScale(scale.vec);
            else if (scale.kind != Kind.NULL) state.poisoned = true;
            applyComponentRotation(state, rightRot);
            return state;
        }

        private void applyComponentRotation(@NotNull State state, @NotNull Val rot) {
            if (rot.kind == Kind.QUAT && !state.isYawEquivalent(rot.axis, rot.angle)) state.postRotate(rot.axis, rot.angle);
            else if (rot.kind != Kind.QUAT && rot.kind != Kind.NULL) state.poisoned = true;
        }

        private void push(@NotNull Val value) {
            this.stack.push(value);
        }

        private @NotNull Val pop() {
            return this.stack.isEmpty() ? Val.other() : this.stack.pop();
        }

        private @NotNull Val peek() {
            return this.stack.isEmpty() ? Val.other() : this.stack.peek();
        }

        private float popFloat() {
            Val v = pop();
            if (v.kind == Kind.NUMBER) return v.number;
            if (v.kind == Kind.YAW) return 0f;
            this.poisoned = true;
            return 0f;
        }

        private float @NotNull [] popVec3() {
            float z = popFloat();
            float y = popFloat();
            float x = popFloat();
            return new float[]{x, y, z};
        }

        private @Nullable State popMatrix() {
            Val v = pop();
            if (v.kind == Kind.MATRIX) return v.matrix;
            this.poisoned = true;
            return null;
        }

        private @NotNull Val neg(@NotNull Val v) {
            if (v.kind == Kind.NUMBER) return Val.number(-v.number);
            if (v.kind == Kind.YAW) return v;   // -0 reference yaw stays 0
            this.poisoned = true;
            return v;
        }
    }

    /** Resolves a static {@code Vector3f} field to its {@code (x, y, z)} via the owner's {@code <clinit>}. */
    private float @Nullable [] resolveStaticVector(@NotNull String owner, @NotNull String fieldName) {
        MethodNode clinit = AsmKit.findClinit(this.cache, owner);
        if (clinit == null) return null;
        List<Float> pending = new ArrayList<>();
        for (AbstractInsnNode in : clinit.instructions) {
            if (in.getOpcode() == Opcodes.NEW && in instanceof TypeInsnNode type && type.desc.equals(VECTOR3F)) {
                pending.clear();
                continue;
            }
            Float literal = AsmKit.readFloatLiteral(in);
            if (literal != null) {
                pending.add(literal);
                continue;
            }
            if (AsmKit.isPutStatic(in, owner, fieldName) && pending.size() >= 3)
                return new float[]{pending.getFirst(), pending.get(1), pending.get(2)};
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // canonicalise
    // ------------------------------------------------------------------------------------

    /** Folds a {@link State} into the {@code [tx, ty, tz, pitch, yaw, roll, scale?]} tuple, or {@code null} on poison. */
    private static float @Nullable [] canonicalise(@NotNull State state) {
        if (state.poisoned) return null;

        float absX = Math.abs(state.sx);
        float absY = Math.abs(state.sy);
        float absZ = Math.abs(state.sz);
        float uniform = 1f;
        char scaleFoldAxis = 'I';
        if (nearUnit(absX, absY) && nearUnit(absY, absZ)) {
            uniform = absX;
            int negatives = (state.sx < 0 ? 1 : 0) + (state.sy < 0 ? 1 : 0) + (state.sz < 0 ? 1 : 0);
            if (negatives == 2) scaleFoldAxis = state.sx > 0 ? 'X' : state.sy > 0 ? 'Y' : 'X';
            else if (negatives != 0) return null;
        } else if (!(nearUnit(absX, 1f) && nearUnit(absY, 1f) && nearUnit(absZ, 1f))) {
            return null;
        }

        char axis = 'I';
        float angle = 0f;
        for (Quat q : state.rotations) {
            if (q.isIdentity()) continue;
            if (axis == 'I') {
                axis = q.axis;
                angle = q.angle;
            } else if (axis == q.axis) {
                angle += q.angle;
            } else {
                return null;
            }
        }
        if (scaleFoldAxis != 'I') {
            if (axis == 'I') { axis = scaleFoldAxis; angle = 180f; }
            else if (axis == scaleFoldAxis) angle += 180f;
            else return null;
        }

        angle = ((angle % 360f) + 360f) % 360f;
        if (angle > 180f) angle -= 360f;
        if (nearUnit(Math.abs(angle), 180f)) angle = 180f;
        if (nearUnit(angle, 0f)) { axis = 'I'; angle = 0f; }

        float pitch = axis == 'X' ? angle : 0f;
        float yaw = axis == 'Y' ? angle : 0f;
        float roll = axis == 'Z' ? angle : 0f;
        float tx = state.tx * MCPIXEL;
        float ty = state.ty * MCPIXEL;
        float tz = state.tz * MCPIXEL;

        return nearUnit(uniform, 1f)
            ? new float[]{tx, ty, tz, pitch, yaw, roll}
            : new float[]{tx, ty, tz, pitch, yaw, roll, uniform};
    }

    private static boolean nearUnit(float a, float target) {
        return Math.abs(a - target) < UNIT_EPS;
    }

    // ------------------------------------------------------------------------------------
    // symbolic value + accumulated transform
    // ------------------------------------------------------------------------------------

    private enum Kind { NUMBER, YAW, MATRIX, QUAT, AXIS, VECTOR, NULL, ENUM, OTHER }

    /** One operand-stack value - a tagged union of the shapes the transform bytecode pushes. */
    private record Val(@NotNull Kind kind, float number, char axis, float angle,
                       @Nullable State matrix, float @Nullable [] vec, @Nullable String text) {

        private static @NotNull Val number(float f) { return new Val(Kind.NUMBER, f, 'I', 0f, null, null, null); }
        private static @NotNull Val yaw() { return new Val(Kind.YAW, 0f, 'I', 0f, null, null, null); }
        private static @NotNull Val matrix(@Nullable State state) { return new Val(Kind.MATRIX, 0f, 'I', 0f, state, null, null); }
        private static @NotNull Val quat(char axis, float angle) { return new Val(Kind.QUAT, 0f, axis, angle, null, null, null); }
        private static @NotNull Val axis(char axis, boolean negated) { return new Val(Kind.AXIS, negated ? -1f : 1f, axis, 0f, null, null, null); }
        private static @NotNull Val vector(float @NotNull [] vec) { return new Val(Kind.VECTOR, 0f, 'I', 0f, null, vec, null); }
        private static @NotNull Val nul() { return new Val(Kind.NULL, 0f, 'I', 0f, null, null, null); }
        private static @NotNull Val enumConst(@NotNull String name) { return new Val(Kind.ENUM, 0f, 'I', 0f, null, null, name); }
        private static @NotNull Val other() { return new Val(Kind.OTHER, 0f, 'I', 0f, null, null, null); }
    }

    /** A single-axis rotation ({@code axis in {X, Y, Z, I}}; {@code I} = identity). */
    private record Quat(char axis, float angle) {
        private boolean isIdentity() { return this.axis == 'I' || this.angle == 0f; }
    }

    /** The accumulated {@code T . R . S}: outer translation, appended rotations, inner per-axis scale. */
    private static final class State {

        private float tx;
        private float ty;
        private float tz;
        private final @NotNull List<Quat> rotations = new ArrayList<>();
        private float sx = 1f;
        private float sy = 1f;
        private float sz = 1f;
        private boolean poisoned;

        /** {@code Matrix4f.translation}: replaces the whole matrix (OVERWRITE, not compose). */
        private void setTranslation(float @NotNull [] t) {
            this.tx = t[0];
            this.ty = t[1];
            this.tz = t[2];
            this.rotations.clear();
            this.sx = this.sy = this.sz = 1f;
        }

        /** {@code Matrix4f.translate}: post-composes; folds into the outer translation only past pure scale. */
        private void postTranslate(float @NotNull [] t) {
            for (Quat q : this.rotations)
                if (!q.isIdentity()) { this.poisoned = true; return; }
            this.tx += foldScale(this.sx) * t[0];
            this.ty += foldScale(this.sy) * t[1];
            this.tz += foldScale(this.sz) * t[2];
        }

        private void postScale(float @NotNull [] s) {
            this.sx *= s[0];
            this.sy *= s[1];
            this.sz *= s[2];
        }

        private void postRotate(char axis, float angle) {
            this.rotations.add(new Quat(axis, angle));
        }

        /** {@code Matrix4f.rotateAround}: a block-centred 180 is the camera flip (dropped); identity is a no-op; else poison. */
        private void postRotateAround(@NotNull Val q, float @NotNull [] c) {
            boolean blockCentre = near(c[0], 0.5f) && near(c[1], 0.5f) && near(c[2], 0.5f);
            if (q.kind != Kind.QUAT) { this.poisoned = true; return; }
            if (blockCentre && is180(q.axis, q.angle)) return;
            if (isIdentityRotation(q.axis, q.angle)) return;
            this.poisoned = true;
        }

        private boolean isYawEquivalent(char axis, float angle) {
            return isIdentityRotation(axis, angle) || (axis == 'Y' && near(Math.abs(angle) % 180f, 0f));
        }

        private static boolean isIdentityRotation(char axis, float angle) {
            return axis == 'I' || angle == 0f;
        }

        private static boolean is180(char axis, float angle) {
            return isIdentityRotation(axis, angle) || near(Math.abs(angle) % 360f, 180f);
        }

        private static float foldScale(float s) {
            return Math.abs(Math.abs(s) - 1f) < UNIT_EPS ? Math.signum(s) : s;
        }

        private static boolean near(float a, float target) {
            return Math.abs(a - target) < UNIT_EPS;
        }
    }

}
