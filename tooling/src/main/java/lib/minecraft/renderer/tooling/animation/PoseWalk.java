package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Interp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Walks a model's {@code setupAnim} body into the pose it computes.
 *
 * <p>The chassis owns the operand stack, the slot table and the arithmetic; this owns everything a
 * pose body is actually made of, which is field access and calls. That split is why the walk is
 * short: the interesting half of a pose is which bone a write lands on, and a bone is decided at
 * construction rather than at animation time, so it resolves to a name rather than to a value.
 *
 * <p><b>The reset is the seed, and it is why reading a channel needs no special case.</b> Every
 * body begins by calling up to {@code Model.resetPose}, which restores each bone's authored pose.
 * So a channel read before anything writes it reads that authored value, which this records as a
 * read of the bone rather than as a number - the evaluator seeds it from the mesh. A channel read
 * after a write reads the write. Both fall out of holding one expression per channel and
 * substituting it where it is read.
 *
 * <p>Anything not modelled is refused rather than approximated: an unresolvable bone, a branch, a
 * loop, a call this does not know, a write to the render state or to the model itself. A pose that
 * is quietly missing a term is the one outcome worth failing to avoid, because it renders - it just
 * renders wrongly, and nothing downstream can tell.
 */
@UtilityClass
public final class PoseWalk {

    /** The erased override every model carries beside its typed one; walking it finds no body. */
    private static final @NotNull String ERASED_SETUP_ANIM = "(Ljava/lang/Object;)V";

    /** Where the super chain stops being a pose and becomes the reset. */
    private static final @NotNull List<String> RESET_ROOTS =
        List.of(VanillaSourceClasses.Types.ENTITY_MODEL, VanillaSourceClasses.Types.MODEL);

    private static final @NotNull PoseValue.Opaque OPAQUE = new PoseValue.Opaque();

    /** The arithmetic the chassis routes here, keyed by opcode, at the width the opcode names. */
    private static final @NotNull Map<Integer, PoseOperator> ARITHMETIC = arithmetic();

    /** The calls a pose body makes that are arithmetic by another name. */
    private static final @NotNull Map<String, PoseOperator> CALLS = calls();

    private static final Interp.Domain<PoseValue> DOMAIN = new Interp.Domain<>() {

        @Override
        public @Nullable PoseValue decode(@NotNull AbstractInsnNode node) {
            if (node instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Float value) return num(PoseExpr.Const.of((float) value));
                if (ldc.cst instanceof Double value) return num(PoseExpr.Const.of((double) value));
                if (ldc.cst instanceof Integer value) return num(PoseExpr.Const.of((int) value));
                return null;
            }
            if (node instanceof IntInsnNode push
                && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH))
                return num(PoseExpr.Const.of(push.operand));
            if (!(node instanceof InsnNode)) return null;
            return switch (node.getOpcode()) {
                case Opcodes.FCONST_0 -> num(PoseExpr.Const.of(0f));
                case Opcodes.FCONST_1 -> num(PoseExpr.Const.of(1f));
                case Opcodes.FCONST_2 -> num(PoseExpr.Const.of(2f));
                case Opcodes.DCONST_0 -> num(PoseExpr.Const.of(0d));
                case Opcodes.DCONST_1 -> num(PoseExpr.Const.of(1d));
                case Opcodes.ICONST_M1 -> num(PoseExpr.Const.of(-1));
                case Opcodes.ICONST_0 -> num(PoseExpr.Const.of(0));
                case Opcodes.ICONST_1 -> num(PoseExpr.Const.of(1));
                case Opcodes.ICONST_2 -> num(PoseExpr.Const.of(2));
                case Opcodes.ICONST_3 -> num(PoseExpr.Const.of(3));
                case Opcodes.ICONST_4 -> num(PoseExpr.Const.of(4));
                case Opcodes.ICONST_5 -> num(PoseExpr.Const.of(5));
                default -> null;
            };
        }

        @Override
        public @NotNull PoseValue unknown() {
            return OPAQUE;
        }

        @Override
        public @NotNull PoseValue underflow() {
            return OPAQUE;
        }

        @Override
        public @Nullable PoseValue binary(int opcode, @NotNull PoseValue left, @NotNull PoseValue right) {
            PoseOperator operator = ARITHMETIC.get(opcode);
            if (operator == null || !(left instanceof PoseValue.Num lhs) || !(right instanceof PoseValue.Num rhs))
                return null;
            return num(PoseExpr.Op.of(operator, lhs.expr(), rhs.expr()));
        }

        @Override
        public @Nullable PoseValue unary(int opcode, @NotNull PoseValue operand) {
            PoseOperator operator = ARITHMETIC.get(opcode);
            if (operator == null || !(operand instanceof PoseValue.Num value)) return null;
            return num(PoseExpr.Op.of(operator, value.expr()));
        }

    };

    /**
     * How deep a chain of inlined helpers may go before the walk calls it a runaway.
     *
     * <p>The corpus does not come close: the longest real chain is a leaf reaching its base's
     * {@code setupAnim}, which reaches a helper of its own. The cap exists so a cycle vanilla does
     * not have cannot become a stack overflow here.
     */
    private static final int MAX_INLINE_DEPTH = 8;

    /**
     * One extraction in progress - what the whole walk needs and what the inlined bodies share.
     *
     * <p>The leaf is carried because a virtual call has to resolve against it rather than against
     * whichever class declared the body being walked: two models sharing an inherited
     * {@code setupAnim} reach different overrides through the same instruction.
     */
    private record Context(
        @NotNull ClassNodeCache cache,
        @NotNull String leaf,
        @NotNull PosePartIndex parts,
        @NotNull Interp<PoseValue> stack,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose
    ) {}

    /**
     * Extracts one model's pose.
     *
     * @param cache the open client jar
     * @param modelClass the leaf model's internal name
     * @param diagnostics the scope findings are recorded against
     * @return the pose, or empty when the body holds a shape this does not model
     */
    public static @NotNull Optional<PoseProgram> extract(
        @NotNull ClassNodeCache cache, @NotNull String modelClass, @NotNull Diagnostics diagnostics) {

        MethodNode body = findSetupAnim(cache, modelClass);
        // A model whose whole chain declares only the erased override poses nothing: what it
        // inherits is the reset. That is an empty pose rather than a refusal, and the two have to
        // stay distinguishable or a walk that failed reads as a subject that simply holds still.
        if (body == null) return Optional.of(new PoseProgram(ClassKit.simpleName(modelClass), Map.of()));

        Context context = new Context(cache, modelClass, PosePartIndex.of(cache, modelClass, diagnostics),
            Interp.of(DOMAIN, Interp.OnUnknown.SILENT, Interp.Width.BY_OPERANDS), new LinkedHashMap<>());

        try {
            walkBody(body, context, 0);
        } catch (RuntimeException error) {
            diagnostics.info("%s not extracted: %s", ClassKit.simpleName(modelClass), error.getMessage());
            return Optional.empty();
        }
        return Optional.of(new PoseProgram(ClassKit.simpleName(modelClass), freeze(context.pose())));
    }

    /**
     * Applies every instruction of one body in order.
     *
     * <p>A refusal is a thrown exception rather than a returned flag, because a body reached through
     * three inlined helpers has to abandon all three at once - and because the message is built
     * where the shape was met, which is the only place that knows what it was.
     */
    private static void walkBody(@NotNull MethodNode body, @NotNull Context context, int depth) {
        for (AbstractInsnNode in : AsmWalker.over(body).real().toList()) step(in, context, depth);
    }

    /**
     * Applies one instruction, handing the chassis everything that is not a field or a call.
     */
    private static void step(@NotNull AbstractInsnNode in, @NotNull Context context, int depth) {
        Interp<PoseValue> stack = context.stack();

        if (in instanceof JumpInsnNode || in.getOpcode() == Opcodes.TABLESWITCH || in.getOpcode() == Opcodes.LOOKUPSWITCH)
            throw new IllegalStateException("body branches, which the linear walk does not model");

        switch (in.getOpcode()) {
            case Opcodes.GETFIELD -> readField((FieldInsnNode) in, context);
            case Opcodes.PUTFIELD -> writeField((FieldInsnNode) in, context);
            case Opcodes.GETSTATIC -> stack.push(OPAQUE);
            case Opcodes.AALOAD -> {
                PoseValue index = stack.pop();
                PoseValue array = stack.pop();
                stack.push(element(context.parts(), array, index));
            }
            case Opcodes.CHECKCAST -> { /* a cast changes the type, never the value */ }
            case Opcodes.RETURN -> { /* the body is done */ }
            case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE ->
                call((MethodInsnNode) in, context, depth);
            case Opcodes.IINC, Opcodes.ARRAYLENGTH, Opcodes.AASTORE, Opcodes.NEW, Opcodes.ANEWARRAY ->
                throw new IllegalStateException("body holds a loop or an allocation, which the linear walk does not model");
            default -> stack.step(in);
        }
    }

    /** A field read: a bone, an array of bones, a channel's current value, or an input. */
    private static void readField(@NotNull FieldInsnNode field, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        PosePartIndex parts = context.parts();
        Map<String, Map<PoseChannel, PoseExpr>> pose = context.pose();
        PoseValue receiver = stack.pop();

        if (VanillaSourceClasses.Types.MODEL_PART.equals(field.owner)) {
            PoseChannel channel = PoseChannel.ofField(field.name);
            if (channel == null) throw new IllegalStateException("reads ModelPart." + field.name + ", which is not a channel");
            if (!(receiver instanceof PoseValue.Part part))
                throw new IllegalStateException("reads a channel off a bone it could not name");
            stack.push(num(current(pose, part.bone(), channel)));
            return;
        }
        if (partDesc().equals(field.desc)) {
            String bone = parts.boneOf(field.name);
            if (bone == null && RESET_ROOTS.contains(field.owner))
                // The mesh root, which is a bone only when the mesh names one: most flatten it into
                // several parented at nothing, and what a transform on the container means then is
                // a question for whoever joins this to a mesh.
                throw new IllegalStateException("poses through the mesh root, which this mesh does not name as a bone");
            if (bone == null)
                throw new IllegalStateException("uses part field '" + field.name + "', which no constructor binds");
            stack.push(new PoseValue.Part(bone));
            return;
        }
        if (partArrayDesc().equals(field.desc)) {
            stack.push(new PoseValue.PartArray(field.name));
            return;
        }
        if (field.owner.startsWith(VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE)) {
            stack.push(num(new PoseExpr.Input(field.name)));
            return;
        }
        stack.push(OPAQUE);
    }

    /** A field write. Only a channel of a bone is one; anything else the walk refuses. */
    private static void writeField(@NotNull FieldInsnNode field, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();
        Map<String, Map<PoseChannel, PoseExpr>> pose = context.pose();
        PoseValue value = stack.pop();
        PoseValue receiver = stack.pop();

        if (!VanillaSourceClasses.Types.MODEL_PART.equals(field.owner))
            throw new IllegalStateException("writes " + ClassKit.simpleName(field.owner) + "." + field.name
                + ", so its pose is not a function of its inputs alone");

        PoseChannel channel = PoseChannel.ofField(field.name);
        if (channel == null) throw new IllegalStateException("writes ModelPart." + field.name + ", which is not a channel");
        if (!(receiver instanceof PoseValue.Part part))
            throw new IllegalStateException("writes a channel to a bone it could not name");
        if (!(value instanceof PoseValue.Num written))
            throw new IllegalStateException("writes " + part.bone() + "." + channel.token() + " a value it could not model");

        pose.computeIfAbsent(part.bone(), bone -> new EnumMap<>(PoseChannel.class)).put(channel, written.expr());
    }

    /** One element of an array of bones, which needs the index to have folded to a literal. */
    private static @NotNull PoseValue element(
        @NotNull PosePartIndex parts, @NotNull PoseValue array, @NotNull PoseValue index) {

        if (!(array instanceof PoseValue.PartArray parked)) return OPAQUE;
        if (!(index instanceof PoseValue.Num number) || !(number.expr() instanceof PoseExpr.Const literal))
            throw new IllegalStateException("indexes '" + parked.field() + "' with something that is not a literal");
        String bone = parts.boneOf(parked.field(), (int) literal.value());
        if (bone == null)
            throw new IllegalStateException("indexes '" + parked.field() + "' past what its constructor allocated");
        return new PoseValue.Part(bone);
    }

    /** A call: arithmetic by another name, a bone mutated through a method, the reset, or a body to inline. */
    private static void call(@NotNull MethodInsnNode call, @NotNull Context context, int depth) {
        Interp<PoseValue> stack = context.stack();

        PoseOperator operator = CALLS.get(key(call));
        if (operator != null) {
            List<PoseValue> arguments = stack.popArguments(operator.arity());
            List<PoseExpr> operands = new ArrayList<>(arguments.size());
            for (PoseValue argument : arguments) {
                if (!(argument instanceof PoseValue.Num number))
                    throw new IllegalStateException("calls " + call.name + " on a value it could not model");
                operands.add(number.expr());
            }
            stack.push(num(PoseExpr.Op.of(operator, operands)));
            return;
        }

        if (VanillaSourceClasses.Types.MODEL_PART.equals(call.owner)) {
            partMethod(call, context);
            return;
        }

        if (VanillaSourceClasses.Methods.SETUP_ANIM.equals(call.name) && RESET_ROOTS.contains(call.owner)) {
            // The reset every body opens with. It restores each bone's authored pose, which is
            // exactly what an unwritten channel already reads, so there is nothing to apply.
            stack.popArguments(ClassKit.argTypes(call.desc).length);
            stack.pop();
            return;
        }

        if (isModelLogic(call.owner)) {
            inline(call, context, depth);
            return;
        }

        throw new IllegalStateException("calls " + ClassKit.simpleName(call.owner) + "." + call.name
            + ", which is not a body this walk can enter");
    }

    /**
     * The three {@code ModelPart} methods a pose body reaches. Every other way vanilla offers to
     * move a part - {@code setRotation}, {@code offsetPos}, {@code offsetRotation},
     * {@code translateAndRotate} - is called from nowhere the walk goes, so meeting one is a
     * finding rather than a case to add on spec.
     */
    private static void partMethod(@NotNull MethodInsnNode call, @NotNull Context context) {
        Interp<PoseValue> stack = context.stack();

        if ("setPos".equals(call.name)) {
            List<PoseValue> arguments = stack.popArguments(3);
            PoseValue receiver = stack.pop();
            if (!(receiver instanceof PoseValue.Part part))
                throw new IllegalStateException("moves a bone it could not name");
            PoseChannel[] axes = {PoseChannel.X, PoseChannel.Y, PoseChannel.Z};
            for (int axis = 0; axis < axes.length; axis++) {
                if (!(arguments.get(axis) instanceof PoseValue.Num number))
                    throw new IllegalStateException("moves " + part.bone() + " by a value it could not model");
                write(context, part.bone(), axes[axis], number.expr());
            }
            return;
        }
        if ("resetPose".equals(call.name)) {
            PoseValue receiver = stack.pop();
            if (!(receiver instanceof PoseValue.Part part))
                throw new IllegalStateException("resets a bone it could not name");
            // Back to the authored pose, which is what an untouched channel already reads.
            context.pose().remove(part.bone());
            return;
        }
        if (VanillaSourceClasses.Methods.GET_CHILD.equals(call.name)) {
            // Vanilla usually caches its children in the constructor; two sites look one up while
            // posing instead. The child's own name is the bone's, the table being flat.
            AbstractInsnNode named = AsmWalker.previousReal(call);
            stack.pop();
            stack.pop();
            if (!(named instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String bone))
                throw new IllegalStateException("looks a bone up by a name it could not read");
            stack.push(new PoseValue.Part(bone));
            return;
        }
        throw new IllegalStateException("calls ModelPart." + call.name + ", which is not a way this walk moves a bone");
    }

    /**
     * Walks a called body in place, as though its instructions had been written where the call is.
     *
     * <p>The callee gets fresh locals over the same operand stack, which is what
     * {@link Interp#openSlotFrame} is for: its arguments are stored into the slots it will read them
     * from, and whatever it leaves above the stack depth it started at is its return value.
     *
     * <p>A virtual call resolves against the LEAF rather than against whichever class declared the
     * body being walked, because two models sharing an inherited {@code setupAnim} reach different
     * overrides through the same instruction. A {@code super} call is the exception and resolves
     * against the owner it names, which is the whole point of spelling it that way.
     */
    private static void inline(@NotNull MethodInsnNode call, @NotNull Context context, int depth) {
        if (depth >= MAX_INLINE_DEPTH)
            throw new IllegalStateException("inlines more than " + MAX_INLINE_DEPTH + " helpers deep");

        boolean dispatched = call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE;
        String owner = dispatched ? context.leaf() : call.owner;
        MethodNode target = ClassKit.findMethodInHierarchy(context.cache(), owner, call.name, call.desc);
        if (target == null || target.instructions == null || target.instructions.size() == 0)
            throw new IllegalStateException("calls " + ClassKit.simpleName(call.owner) + "." + call.name
                + ", whose body is not in the jar");

        Interp<PoseValue> stack = context.stack();
        Type[] parameters = ClassKit.argTypes(call.desc);
        List<PoseValue> arguments = stack.popArguments(parameters.length);
        boolean instance = call.getOpcode() != Opcodes.INVOKESTATIC;
        PoseValue receiver = instance ? stack.pop() : null;

        int depthBefore = stack.size();
        stack.openSlotFrame();
        int slot = 0;
        if (receiver != null) stack.store(slot++, receiver);
        for (int index = 0; index < parameters.length; index++) {
            stack.store(slot, arguments.get(index));
            slot += parameters[index].getSize();
        }

        walkBody(target, context, depth + 1);

        PoseValue answered = stack.size() > depthBefore ? stack.pop() : null;
        stack.closeSlotFrame();
        if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
            if (answered == null) throw new IllegalStateException(call.name + " returned nothing this walk could follow");
            stack.push(answered);
        }
    }

    /** Whether a call names a model's own logic, which is a body to walk rather than a fact to know. */
    private static boolean isModelLogic(@NotNull String owner) {
        return owner.startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_ROOT)
            && !owner.startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_GEOM_ROOT);
    }

    /** Records one channel's new value. */
    private static void write(
        @NotNull Context context, @NotNull String bone, @NotNull PoseChannel channel, @NotNull PoseExpr value) {

        context.pose().computeIfAbsent(bone, key -> new EnumMap<>(PoseChannel.class)).put(channel, value);
    }

    /** A channel's value so far - what a write left, or the authored pose the reset restored. */
    private static @NotNull PoseExpr current(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose, @NotNull String bone, @NotNull PoseChannel channel) {

        Map<PoseChannel, PoseExpr> written = pose.get(bone);
        PoseExpr held = written == null ? null : written.get(channel);
        return held != null ? held : new PoseExpr.BoneRead(bone, channel);
    }

    /** The typed {@code setupAnim} nearest the leaf, skipping the erased override beside it. */
    private static @Nullable MethodNode findSetupAnim(@NotNull ClassNodeCache cache, @NotNull String modelClass) {
        String current = modelClass;
        for (int depth = 0; current != null && depth < 8; depth++) {
            ClassNode node = cache.load(current);
            if (node == null) return null;
            for (MethodNode method : node.methods)
                if (VanillaSourceClasses.Methods.SETUP_ANIM.equals(method.name) && !ERASED_SETUP_ANIM.equals(method.desc))
                    return method;
            current = node.superName;
        }
        return null;
    }

    private static @NotNull Map<String, Map<PoseChannel, PoseExpr>> freeze(
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> pose) {

        Map<String, Map<PoseChannel, PoseExpr>> out = new LinkedHashMap<>();
        pose.forEach((bone, channels) -> out.put(bone, Map.copyOf(channels)));
        return Map.copyOf(out);
    }

    private static @NotNull PoseValue num(@NotNull PoseExpr expr) {
        return new PoseValue.Num(expr);
    }

    private static @NotNull String partDesc() {
        return "L" + VanillaSourceClasses.Types.MODEL_PART + ";";
    }

    private static @NotNull String partArrayDesc() {
        return "[L" + VanillaSourceClasses.Types.MODEL_PART + ";";
    }

    private static @NotNull String key(@NotNull MethodInsnNode call) {
        return call.owner + "." + call.name + call.desc;
    }

    private static @NotNull String key(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        return owner + "." + name + desc;
    }

    /** The opcode-to-operator table, which is where the three widths stop being interchangeable. */
    private static @NotNull Map<Integer, PoseOperator> arithmetic() {
        Map<Integer, PoseOperator> out = new LinkedHashMap<>();
        out.put(Opcodes.FADD, PoseOperator.ADD);
        out.put(Opcodes.FSUB, PoseOperator.SUB);
        out.put(Opcodes.FMUL, PoseOperator.MUL);
        out.put(Opcodes.FDIV, PoseOperator.DIV);
        out.put(Opcodes.FREM, PoseOperator.REM);
        out.put(Opcodes.FNEG, PoseOperator.NEG);
        out.put(Opcodes.DADD, PoseOperator.DADD);
        out.put(Opcodes.DSUB, PoseOperator.DSUB);
        out.put(Opcodes.DMUL, PoseOperator.DMUL);
        out.put(Opcodes.DDIV, PoseOperator.DDIV);
        out.put(Opcodes.DNEG, PoseOperator.DNEG);
        out.put(Opcodes.IADD, PoseOperator.IADD);
        out.put(Opcodes.ISUB, PoseOperator.ISUB);
        out.put(Opcodes.IMUL, PoseOperator.IMUL);
        out.put(Opcodes.IDIV, PoseOperator.IDIV);
        out.put(Opcodes.IREM, PoseOperator.IREM);
        out.put(Opcodes.INEG, PoseOperator.INEG);
        out.put(Opcodes.I2F, PoseOperator.I2F);
        out.put(Opcodes.F2D, PoseOperator.F2D);
        out.put(Opcodes.D2F, PoseOperator.D2F);
        out.put(Opcodes.F2I, PoseOperator.F2I);
        return Map.copyOf(out);
    }

    /** The call-to-operator table, keyed on the whole coordinate so a width cannot be mistaken. */
    private static @NotNull Map<String, PoseOperator> calls() {
        String mth = VanillaSourceClasses.Types.MTH;
        String math = VanillaSourceClasses.Types.JAVA_MATH;
        String ease = VanillaSourceClasses.Types.EASE;

        Map<String, PoseOperator> out = new LinkedHashMap<>();
        out.put(key(mth, "sin", "(D)F"), PoseOperator.MTH_SIN);
        out.put(key(mth, "cos", "(D)F"), PoseOperator.MTH_COS);
        out.put(key(mth, "sqrt", "(F)F"), PoseOperator.SQRT);
        out.put(key(mth, "clamp", "(FFF)F"), PoseOperator.CLAMP);
        out.put(key(mth, "lerp", "(FFF)F"), PoseOperator.LERP);
        out.put(key(mth, "inverseLerp", "(FFF)F"), PoseOperator.INVERSE_LERP);
        out.put(key(mth, "rotLerp", "(FFF)F"), PoseOperator.ROT_LERP);
        out.put(key(mth, "rotLerpRad", "(FFF)F"), PoseOperator.ROT_LERP_RAD);
        out.put(key(mth, "wrapDegrees", "(F)F"), PoseOperator.WRAP_DEGREES);
        out.put(key(mth, "triangleWave", "(FF)F"), PoseOperator.TRIANGLE_WAVE);
        out.put(key(mth, "abs", "(I)I"), PoseOperator.IABS);

        out.put(key(math, "min", "(FF)F"), PoseOperator.MIN);
        out.put(key(math, "max", "(FF)F"), PoseOperator.MAX);
        out.put(key(math, "abs", "(F)F"), PoseOperator.ABS);
        out.put(key(math, "abs", "(I)I"), PoseOperator.IABS);
        out.put(key(math, "clamp", "(FFF)F"), PoseOperator.CLAMP);
        out.put(key(math, "sin", "(D)D"), PoseOperator.LIBM_SIN);
        out.put(key(math, "cos", "(D)D"), PoseOperator.LIBM_COS);
        out.put(key(math, "abs", "(D)D"), PoseOperator.LIBM_ABS);
        out.put(key(math, "signum", "(D)D"), PoseOperator.LIBM_SIGNUM);

        out.put(key(ease, "inCirc", "(F)F"), PoseOperator.EASE_IN_CIRC);
        out.put(key(ease, "inQuad", "(F)F"), PoseOperator.EASE_IN_QUAD);
        out.put(key(ease, "outCirc", "(F)F"), PoseOperator.EASE_OUT_CIRC);
        out.put(key(ease, "outCubic", "(F)F"), PoseOperator.EASE_OUT_CUBIC);
        out.put(key(ease, "outQuart", "(F)F"), PoseOperator.EASE_OUT_QUART);
        out.put(key(ease, "inOutSine", "(F)F"), PoseOperator.EASE_IN_OUT_SINE);
        out.put(key(ease, "inOutExpo", "(F)F"), PoseOperator.EASE_IN_OUT_EXPO);
        out.put(key(ease, "inOutElastic", "(F)F"), PoseOperator.EASE_IN_OUT_ELASTIC);
        return Map.copyOf(out);
    }

}
