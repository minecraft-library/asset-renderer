package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import lib.minecraft.renderer.tooling.walk.Interp;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Reads a renderer's own {@code setupRotations} into the container steps it puts every mesh it
 * submits under.
 *
 * <p><b>Self-refusing, and that is the whole safety property.</b> A body is read WHOLE or not at
 * all: every instruction has to fall inside a closed grammar - the render state's own float and
 * boolean fields, float and double arithmetic, {@code Mth.sin} and {@code Mth.cos}, a
 * {@code PoseStack} translate, a {@code mulPose} of one of the three positive axes turned by
 * degrees, the delegation to the base carrying at most a literal addend folded into its body
 * rotation, and a {@code rotateAround} about the direction a resting subject attaches at - and
 * anything else refuses the renderer rather than emitting the part that was understood. A
 * transform read in half is a subject placed somewhere vanilla never puts it, which renders and
 * looks deliberate.
 *
 * <p><b>An enum member a body reads is settled here, never shipped.</b> The shipped vocabulary
 * answers every constant question where the generator knows the subject, so a {@code Direction}
 * field resolves at the read to the constant the subjects drawn by this renderer rest holding,
 * and what that decides is which steps exist rather than what a channel holds: the identity
 * rotation turns nothing whatever the pivot, so the shulker's whole {@code rotateAround} folds
 * away at rest. A member no constant answers, or one whose resolved rotation is not the
 * identity, refuses the body instead of guessing.
 *
 * <p><b>The world transform crosses a frame to reach the container.</b> Vanilla runs
 * {@code setupRotations} OUTSIDE its own {@code scale(-1, -1, 1)}, where a container sits inside
 * it, so what a step holds is {@code M . <world step> . M} at {@code M = diag(-1, -1, 1)} - x and y
 * negate and z is kept, for a translate and for an Euler angle alike, {@code M} being a half turn
 * about z rather than a reflection. A translate also crosses units: a {@code PoseStack} moves in
 * BLOCKS where a pivot is in model pixels.
 *
 * <p><b>The base delegation emits nothing and is still required.</b> At the frozen pose the base
 * declaration is exactly one turn about y, which this renderer applies as the subject's facing
 * rather than as geometry - so following it would apply it twice. A body that never runs it is
 * composing around a different base and is refused instead of read against this one. A step
 * emitted BEFORE the delegation has to be about y alone, which is what makes its position in the
 * sequence immaterial: a turn about y and a translate along y both commute with the base's turn,
 * and nothing else does.
 *
 * <p><b>A conditional block is structural rather than a fork.</b> The one branch shape the corpus
 * carries is a forward jump over a contiguous run of statements, so what the block does becomes a
 * choice per value: a step inside one writes each channel as a select between its value and that
 * channel's identity, and a local the block assigns is reconciled at the block's end against what
 * it held before. A backward jump, a nested one, an unconditional one, or a branch met with a
 * half-built expression on the stack all refuse.
 */
final class RenderTransformWalk {

    /** What a {@code PoseStack} translate is expressed in, against the model pixels a pivot is in. */
    private static final float MODEL_UNITS_PER_BLOCK = 16f;

    /** Vanilla's own degrees-to-radians factor, as {@code Axis.rotationDegrees} applies it. */
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180d);

    /** The {@code PoseStack.translate} overload vanilla poses a subject with. */
    private static final @NotNull String TRANSLATE_DESC = "(FFF)V";

    /** The {@code PoseStack.mulPose} overload every axis turn arrives through. */
    private static final @NotNull String MUL_POSE_DESC = "(Lorg/joml/Quaternionfc;)V";

    /** The {@code Axis.rotationDegrees} descriptor, which is what makes the angle degrees. */
    private static final @NotNull String ROTATION_DEGREES_DESC = "(F)Lorg/joml/Quaternionf;";

    /** The {@code Mth} trigonometry descriptor - the sampled table, never the JDK's own. */
    private static final @NotNull String TRIGONOMETRY_DESC = "(D)F";

    /** {@code PoseStack.mulPose}, which is the only way a turn reaches the stack here. */
    private static final @NotNull String MUL_POSE = "mulPose";

    /** The {@code PoseStack.rotateAround} overload a pivoted turn arrives through. */
    private static final @NotNull String ROTATE_AROUND_DESC = "(Lorg/joml/Quaternionfc;FFF)V";

    /** {@code PoseStack.rotateAround}, the one pivoted turn the grammar covers. */
    private static final @NotNull String ROTATE_AROUND = "rotateAround";

    /** {@code Direction.getOpposite}, mapped through the enum's own opposing pairs. */
    private static final @NotNull String GET_OPPOSITE = "getOpposite";

    /** {@code Direction.getRotation}, which marks a resolved direction rather than building a value. */
    private static final @NotNull String GET_ROTATION = "getRotation";

    /** The descriptor a {@code Direction} render-state field carries. */
    private static final @NotNull String DIRECTION_DESC =
        "L" + VanillaSourceClasses.Types.DIRECTION + ";";

    /** The descriptor a boolean render-state field carries. */
    private static final @NotNull String BOOLEAN_DESC = "Z";

    /** The descriptor a float render-state field carries. */
    private static final @NotNull String FLOAT_DESC = "F";

    /**
     * Each {@code Direction} constant's opposite - the three opposing pairs the enum declares,
     * the same pairing {@code getOpposite} resolves through its 3D data index.
     */
    private static final @NotNull Map<String, String> OPPOSITE = Map.of(
        "DOWN", "UP", "UP", "DOWN", "NORTH", "SOUTH", "SOUTH", "NORTH", "WEST", "EAST", "EAST", "WEST");

    /**
     * The one constant whose {@code getRotation} is the identity quaternion - the UP arm builds a
     * bare {@code Quaternionf} where every other arm turns it.
     */
    private static final @NotNull String IDENTITY_ROTATION = "UP";

    /** The one reference the walk names, standing for what an unmodelled value would be. */
    private static final @NotNull Object UNKNOWN = new Object();

    /** The body-rotation argument, bound so an addend folded into the delegation can be read. */
    private static final @NotNull Object BODY_ROT = new Object();

    /** The two references the body is handed, which nothing but their own members may be read of. */
    private enum Ref { STATE, STACK }

    /** One of the three positive axes, named by the channel a turn about it writes. */
    private record AxisRef(@NotNull PoseChannel channel) {}

    /** A turn built and not yet applied, in the radians the axis was turned by. */
    private record QuatRef(@NotNull PoseChannel channel, @NotNull PoseExpr radians) {}

    /**
     * A direction the render state rests holding, already settled to its constant; {@code rotation}
     * once {@code getRotation} has marked it.
     */
    private record DirectionRef(@NotNull String constant, boolean rotation) {}

    /**
     * A conditional block being walked - what decides it, where it ends, and what the machine held
     * before it opened.
     */
    private record Block(
        @NotNull PosePredicate runs,
        @NotNull AbstractInsnNode last,
        @NotNull Interp.Snapshot<Object> before
    ) {}

    private final @NotNull String renderer;
    private final @NotNull MethodNode body;
    private final @NotNull String stateType;
    private final @NotNull BiFunction<String, String, Optional<String>> resting;
    private final @NotNull List<Map<PoseChannel, PoseExpr>> steps = new ArrayList<>();
    private final @NotNull Interp<Object> machine =
        Interp.of(new Domain(), Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_FLOAT);

    private @Nullable String refusal;
    private @Nullable Block block;
    private @Nullable AbstractInsnNode returned;
    private @Nullable AbstractInsnNode lastReal;
    private boolean delegated;

    private RenderTransformWalk(
        @NotNull String renderer, @NotNull MethodNode body,
        @NotNull BiFunction<String, String, Optional<String>> resting) {

        this.renderer = renderer;
        this.body = body;
        // The state class the body reads its members of, which is what a resting constant is
        // resolved against - the typed override names the renderer's own, the bridge the base's.
        this.stateType = ClassKit.argTypes(body.desc)[0].getInternalName();
        this.resting = resting;
    }

    /**
     * The transform a subject's renderer composes, or why it could not be read.
     *
     * <p>The declaration is looked up the renderer's own hierarchy, so a subclass that overrides
     * nothing answers with what it inherits - a glow squid composes a squid's transform. It is keyed
     * by the SUBJECT's renderer either way, because that is the name the model table carries and the
     * only one a reader can join on.
     *
     * @param cache the class source
     * @param renderer the subject's renderer class, by internal name
     * @param resting which constant a render-state member rests holding, by state class and member
     * @return the transform, or {@code null} when the renderer composes none at all
     */
    static @Nullable RenderTransform read(
        @NotNull ClassNodeCache cache, @NotNull String renderer,
        @NotNull BiFunction<String, String, Optional<String>> resting) {

        ClassNode declaring = declarer(cache, renderer);
        if (declaring == null) return null;
        MethodNode body = primary(declaring);
        if (body == null) return null;

        String name = ClassKit.simpleName(renderer);
        RenderTransform read = new RenderTransformWalk(name, body, resting).walk();
        // A body that composes nothing beyond the base is a renderer with no transform rather than
        // one whose transform is empty, so it gets no row at all and reads as the subject's default.
        return read.isReadable() && read.steps().isEmpty() ? null : read;
    }

    /**
     * The first class up the renderer's chain declaring {@code setupRotations}, stopping short of
     * the base every override overrides.
     */
    private static @Nullable ClassNode declarer(@NotNull ClassNodeCache cache, @NotNull String renderer) {
        String current = renderer;
        while (current != null
            && !ClassKit.OBJECT_INTERNAL.equals(current)
            && !VanillaSourceClasses.Types.LIVING_ENTITY_RENDERER.equals(current)) {
            ClassNode node = cache.load(current);
            if (node == null) return null;
            for (MethodNode method : node.methods)
                if (VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(method.name)) return node;
            current = node.superName;
        }
        return null;
    }

    /**
     * The declaration carrying the body, preferring the one taking the renderer's OWN render state.
     *
     * <p>A renderer that narrows the state declares two: the body, and a bridge that casts and
     * forwards. Reading the bridge would find a checkcast and refuse, which is a refusal about
     * javac rather than about vanilla.
     */
    private static @Nullable MethodNode primary(@NotNull ClassNode declaring) {
        MethodNode bridge = null;
        for (MethodNode method : declaring.methods) {
            if (!VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(method.name)) continue;
            if (method.desc == null || !method.desc.endsWith("FF)V")) continue;
            Type[] arguments = ClassKit.argTypes(method.desc);
            if (arguments.length == 4 && arguments[0].getSort() == Type.OBJECT
                && VanillaSourceClasses.Types.LIVING_ENTITY_RENDER_STATE.equals(arguments[0].getInternalName())) {
                bridge = method;
                continue;
            }
            return method;
        }
        return bridge;
    }

    // ------------------------------------------------------------------------------------
    // the walk
    // ------------------------------------------------------------------------------------

    /** Runs the body through the machine and answers what it composed. */
    private @NotNull RenderTransform walk() {
        // The two references the body is handed, and the body rotation, bound so an addend folded
        // into the delegation can be read off it. Everything else - this, the render scale - is
        // left unbound, so a read of one arrives as the unmodelled value and refuses wherever it
        // is used rather than resolving to a confident zero. The slots are fixed by the shape
        // primary() accepts: (state, stack, bodyRot, scale) on an instance method.
        this.machine.store(1, Ref.STATE);
        this.machine.store(2, Ref.STACK);
        this.machine.store(3, BODY_ROT);
        AsmWalker.over(this.body).drive(this.machine)
            .on(Insn.of(AbstractInsnNode.class, node -> true), this::visit)
            .run();

        if (this.refusal == null && this.block != null) refuse("opens a branch it never closes");
        if (this.refusal == null && !this.delegated) refuse("never runs the base rotation");
        if (this.refusal == null && this.returned != this.lastReal) refuse("returns before its own end");
        return this.refusal != null
            ? RenderTransform.refused(this.renderer, this.refusal)
            : RenderTransform.of(this.renderer, this.steps);
    }

    /** One instruction: the grammar's own dispatch, then the block close it may complete. */
    private void visit(@NotNull AbstractInsnNode node) {
        if (this.refusal != null || node.getOpcode() < 0) return;
        this.lastReal = node;
        dispatch(node);
        if (this.refusal == null && this.block != null && node == this.block.last()) close();
    }

    /**
     * The closed grammar, as a switch over what the instruction is.
     *
     * <p>The arms that do nothing are the ones the machine has already applied - a load, a store, an
     * arithmetic operation. They are named rather than left to a default so that the default is a
     * refusal.
     */
    private void dispatch(@NotNull AbstractInsnNode node) {
        switch (node.getOpcode()) {
            case Opcodes.ALOAD, Opcodes.FLOAD, Opcodes.ILOAD, Opcodes.DLOAD,
                 Opcodes.ASTORE, Opcodes.FSTORE, Opcodes.ISTORE, Opcodes.DSTORE,
                 Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FNEG,
                 Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DNEG,
                 Opcodes.F2D, Opcodes.D2F, Opcodes.I2F, Opcodes.I2D, Opcodes.F2I -> { }
            case Opcodes.LDC, Opcodes.BIPUSH, Opcodes.SIPUSH,
                 Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
                 Opcodes.DCONST_0, Opcodes.DCONST_1,
                 Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                 Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> requireNumber(node);
            case Opcodes.GETSTATIC -> readAxis((FieldInsnNode) node);
            case Opcodes.GETFIELD -> readInput((FieldInsnNode) node);
            case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL,
                 Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> call((MethodInsnNode) node);
            case Opcodes.IFEQ, Opcodes.IFNE -> open((JumpInsnNode) node);
            case Opcodes.RETURN -> {
                if (this.returned != null) refuse("returns before its own end");
                this.returned = node;
            }
            default -> refuse("runs opcode %d, which the grammar does not cover", node.getOpcode());
        }
    }

    /** A constant push the machine modelled, refusing the ones that push something else. */
    private void requireNumber(@NotNull AbstractInsnNode node) {
        if (AsmWalker.floatLiteral(node) == null
            && AsmWalker.intLiteral(node) == null
            && AsmWalker.doubleLiteral(node) == null)
            refuse("pushes a constant that is not a number");
    }

    /** One of the three positive axes, which is the whole axis vocabulary. */
    private void readAxis(@NotNull FieldInsnNode field) {
        if (!VanillaSourceClasses.Types.MATH_AXIS.equals(field.owner)) {
            refuse("reads the static '%s.%s'", ClassKit.simpleName(field.owner), field.name);
            return;
        }
        PoseChannel channel = switch (field.name) {
            case "XP" -> PoseChannel.X_ROT;
            case "YP" -> PoseChannel.Y_ROT;
            case "ZP" -> PoseChannel.Z_ROT;
            default -> null;
        };
        if (channel == null) refuse("turns about 'Axis.%s'", field.name);
        else this.machine.push(new AxisRef(channel));
    }

    /** A field of the render state, which is the only reference a member may be read of. */
    private void readInput(@NotNull FieldInsnNode field) {
        if (this.machine.pop() != Ref.STATE) {
            refuse("reads '%s' of something other than the render state", field.name);
            return;
        }
        // An enum member is settled to the constant the subjects rest holding, never shipped - the
        // shipped vocabulary answers every constant question where the generator knows the subject.
        if (DIRECTION_DESC.equals(field.desc)) {
            Optional<String> constant = this.resting.apply(this.stateType, field.name);
            if (constant.isEmpty()) refuse("reads '%s', which rests at no constant this can answer", field.name);
            else this.machine.push(new DirectionRef(constant.get(), false));
            return;
        }
        if (!FLOAT_DESC.equals(field.desc) && !BOOLEAN_DESC.equals(field.desc)) {
            refuse("reads '%s', which is a '%s' rather than a number", field.name, field.desc);
            return;
        }
        this.machine.push(new PoseExpr.Input(field.name));
    }

    /** The five calls the grammar knows, and a refusal naming any other. */
    private void call(@NotNull MethodInsnNode invoke) {
        if (VanillaSourceClasses.Types.POSE_STACK.equals(invoke.owner)
            && VanillaSourceClasses.Methods.TRANSLATE.equals(invoke.name)
            && TRANSLATE_DESC.equals(invoke.desc)) {
            translate();
            return;
        }
        if (VanillaSourceClasses.Types.POSE_STACK.equals(invoke.owner)
            && MUL_POSE.equals(invoke.name) && MUL_POSE_DESC.equals(invoke.desc)) {
            turn();
            return;
        }
        if (VanillaSourceClasses.Types.MATH_AXIS.equals(invoke.owner)
            && VanillaSourceClasses.Methods.ROTATION_DEGREES.equals(invoke.name)
            && ROTATION_DEGREES_DESC.equals(invoke.desc)) {
            degrees();
            return;
        }
        if (VanillaSourceClasses.Types.MTH.equals(invoke.owner) && TRIGONOMETRY_DESC.equals(invoke.desc)
            && ("sin".equals(invoke.name) || "cos".equals(invoke.name))) {
            trigonometry("sin".equals(invoke.name) ? PoseOperator.MTH_SIN : PoseOperator.MTH_COS);
            return;
        }
        if (VanillaSourceClasses.Types.DIRECTION.equals(invoke.owner) && GET_OPPOSITE.equals(invoke.name)) {
            opposite();
            return;
        }
        if (VanillaSourceClasses.Types.DIRECTION.equals(invoke.owner) && GET_ROTATION.equals(invoke.name)) {
            rotation();
            return;
        }
        if (VanillaSourceClasses.Types.POSE_STACK.equals(invoke.owner)
            && ROTATE_AROUND.equals(invoke.name) && ROTATE_AROUND_DESC.equals(invoke.desc)) {
            rotateAround();
            return;
        }
        if (invoke.getOpcode() == Opcodes.INVOKESPECIAL
            && VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(invoke.name)) {
            delegate(invoke);
            return;
        }
        refuse("calls '%s.%s'", ClassKit.simpleName(invoke.owner), invoke.name);
    }

    /** {@code Direction.getOpposite}, mapped through the enum's own opposing pairs. */
    private void opposite() {
        Object direction = this.machine.pop();
        String opposed = direction instanceof DirectionRef held && !held.rotation()
            ? OPPOSITE.get(held.constant()) : null;
        if (opposed == null) refuse("takes the opposite of a direction it could not read");
        else this.machine.push(new DirectionRef(opposed, false));
    }

    /** {@code Direction.getRotation}, which marks the settled direction rather than building a value. */
    private void rotation() {
        Object direction = this.machine.pop();
        if (!(direction instanceof DirectionRef held) || held.rotation())
            refuse("builds a rotation from a direction it could not read");
        else this.machine.push(new DirectionRef(held.constant(), true));
    }

    /**
     * {@code PoseStack.rotateAround}, which the corpus reaches only about the direction a resting
     * subject attaches at.
     *
     * <p>The identity rotation turns nothing whatever the pivot, so the whole call folds away; any
     * other resolved rotation is refused rather than spelled, no subject in the corpus resting at
     * one - a table of turns nothing consults would be asserted rather than measured.
     */
    private void rotateAround() {
        Object z = this.machine.pop();
        Object y = this.machine.pop();
        Object x = this.machine.pop();
        Object quaternion = this.machine.pop();
        if (this.machine.pop() != Ref.STACK
            || !(quaternion instanceof DirectionRef held) || !held.rotation()
            || !(x instanceof PoseExpr) || !(y instanceof PoseExpr) || !(z instanceof PoseExpr)) {
            refuse("turns about a pivot by a rotation it could not read");
            return;
        }
        if (!IDENTITY_ROTATION.equals(held.constant()))
            refuse("turns about the '%s' rotation, which does not rest at the identity", held.constant());
    }

    /** {@code Axis.rotationDegrees}, which is where an angle stops being degrees. */
    private void degrees() {
        Object angle = this.machine.pop();
        Object axis = this.machine.pop();
        if (!(axis instanceof AxisRef turned) || !(angle instanceof PoseExpr expression)) {
            refuse("turns by an angle it could not read");
            return;
        }
        this.machine.push(new QuatRef(turned.channel(),
            PoseExpr.Op.of(PoseOperator.MUL, expression, PoseExpr.Const.of(DEGREES_TO_RADIANS))));
    }

    /** {@code Mth.sin} or {@code Mth.cos} - the sampled table, which is not the JDK's. */
    private void trigonometry(@NotNull PoseOperator operator) {
        Object operand = this.machine.pop();
        if (!(operand instanceof PoseExpr expression)) refuse("samples '%s' of a value it could not read", operator.token());
        else this.machine.push(PoseExpr.Op.of(operator, expression));
    }

    /** {@code PoseStack.translate}, in blocks about the world axes. */
    private void translate() {
        Object z = this.machine.pop();
        Object y = this.machine.pop();
        Object x = this.machine.pop();
        if (this.machine.pop() != Ref.STACK
            || !(x instanceof PoseExpr along) || !(y instanceof PoseExpr up) || !(z instanceof PoseExpr out)) {
            refuse("translates by a distance it could not read");
            return;
        }
        Map<PoseChannel, PoseExpr> step = new EnumMap<>(PoseChannel.class);
        place(step, PoseChannel.X, along);
        place(step, PoseChannel.Y, up);
        place(step, PoseChannel.Z, out);
        // A translate by nothing at all is not a step, and the corpus writes one: two of the fish
        // move along one axis and spell the other two as literal zeroes.
        if (!step.isEmpty()) emit(step);
    }

    /** One component of a translate, dropped when the body moves nothing along that axis. */
    private void place(
        @NotNull Map<PoseChannel, PoseExpr> step, @NotNull PoseChannel channel, @NotNull PoseExpr blocks) {

        if (blocks.constantValue().orElse(Double.NaN) == 0d) return;
        step.put(channel, crossed(channel,
            PoseExpr.Op.of(PoseOperator.MUL, blocks, PoseExpr.Const.of(MODEL_UNITS_PER_BLOCK))));
    }

    /** {@code PoseStack.mulPose}, which is where a built turn is applied. */
    private void turn() {
        Object quaternion = this.machine.pop();
        if (this.machine.pop() != Ref.STACK || !(quaternion instanceof QuatRef applied)) {
            refuse("applies a turn it could not read");
            return;
        }
        Map<PoseChannel, PoseExpr> step = new EnumMap<>(PoseChannel.class);
        step.put(applied.channel(), crossed(applied.channel(), applied.radians()));
        emit(step);
    }

    /**
     * The base declaration, which bounds what may precede it and carries at most an addend.
     *
     * <p>The base turns about y by the body rotation it is handed, in degrees, and applies it as
     * the subject's facing - so an addend folded into that argument is the same turn a
     * {@code mulPose} about y before the delegation would be, and it is emitted as that step. The
     * shulker's {@code + 180f} is the corpus's one instance. A body rotation that is neither
     * passed through nor a literal addend refuses the body.
     */
    private void delegate(@NotNull MethodInsnNode invoke) {
        if (this.delegated) {
            refuse("runs the base rotation twice");
            return;
        }
        for (Map<PoseChannel, PoseExpr> step : this.steps)
            for (PoseChannel channel : step.keySet())
                if (channel != PoseChannel.Y && channel != PoseChannel.Y_ROT) {
                    refuse("moves '%s' before the base rotation, which does not commute with it",
                        channel.token());
                    return;
                }
        // The arguments in reverse push order: scale, the body rotation, the stack, the state, this.
        this.machine.pop();
        Object body = this.machine.pop();
        this.machine.pop();
        this.machine.pop();
        this.machine.pop();
        if (body instanceof QuatRef addend) {
            Map<PoseChannel, PoseExpr> step = new EnumMap<>(PoseChannel.class);
            step.put(addend.channel(), crossed(addend.channel(), addend.radians()));
            emit(step);
        } else if (body != BODY_ROT) {
            refuse("delegates a body rotation it could not read");
            return;
        }
        this.delegated = true;
    }

    // ------------------------------------------------------------------------------------
    // conditional blocks
    // ------------------------------------------------------------------------------------

    /** Opens a forward jump over a contiguous block, refusing every other branch shape. */
    private void open(@NotNull JumpInsnNode jump) {
        if (this.block != null) {
            refuse("branches inside a branch");
            return;
        }
        if (!this.machine.isEmpty()) {
            refuse("branches with a half-built value on the stack");
            return;
        }
        AbstractInsnNode read = AsmWalker.previousReal(jump);
        if (!(read instanceof FieldInsnNode field) || read.getOpcode() != Opcodes.GETFIELD
            || !BOOLEAN_DESC.equals(field.desc)) {
            refuse("branches on something other than a boolean the render state carries");
            return;
        }
        AbstractInsnNode target = AsmWalker.nextReal(jump.label);
        AbstractInsnNode last = AsmWalker.previousReal(target);
        if (target == null || last == null
            || this.body.instructions.indexOf(target) <= this.body.instructions.indexOf(jump)) {
            refuse("branches backwards or out of its own body");
            return;
        }
        // The jump is taken on the condition the block does NOT run, so what is recorded is its
        // opposite: an IFEQ leaves the block for a zero, which is to say it runs on anything else.
        PosePredicate runs = PosePredicate.Compare.of(
            jump.getOpcode() == Opcodes.IFEQ ? PosePredicate.Comparison.NE : PosePredicate.Comparison.EQ,
            new PoseExpr.Input(field.name), PoseExpr.Const.of(0));
        this.block = new Block(runs, last, this.machine.snapshot());
    }

    /**
     * Closes a block, reconciling every local it changed into a choice between the two arms.
     *
     * <p>A local the block binds for the first time is left alone: it cannot be read after the
     * block, definite assignment being what says so, and giving it an arm would invent a value for a
     * path nothing takes.
     */
    private void close() {
        Block open = Objects.requireNonNull(this.block);
        this.block = null;
        if (!this.machine.isEmpty()) {
            refuse("leaves a half-built value on the stack at the end of a branch");
            return;
        }
        for (Map.Entry<Integer, Object> slot : open.before().slots().entrySet()) {
            Object now = this.machine.slot(slot.getKey());
            if (Objects.equals(now, slot.getValue())) continue;
            if (!(now instanceof PoseExpr taken) || !(slot.getValue() instanceof PoseExpr left)) {
                refuse("assigns a value it could not read inside a branch");
                return;
            }
            this.machine.store(slot.getKey(), new PoseExpr.Select(open.runs(), taken, left));
        }
    }

    /**
     * Records one step, guarded by the block it sits in.
     *
     * <p>A step inside a block is applied unconditionally and each of its channels chooses: a
     * channel that resolves to its own identity is a part pose that moves nothing, which is what
     * turns a conditional STATEMENT back into a value.
     */
    private void emit(@NotNull Map<PoseChannel, PoseExpr> step) {
        if (this.block == null) {
            this.steps.add(Map.copyOf(step));
            return;
        }
        Map<PoseChannel, PoseExpr> guarded = new EnumMap<>(PoseChannel.class);
        step.forEach((channel, value) ->
            guarded.put(channel, new PoseExpr.Select(this.block.runs(), value, PoseExpr.Const.of(0f))));
        this.steps.add(Map.copyOf(guarded));
    }

    /**
     * One channel's value in the frame the container sits in.
     *
     * <p>{@code M = diag(-1, -1, 1)} is a half turn about z rather than a reflection, so conjugating
     * by it turns the x and y axes around and leaves z where it is - which negates a translate along
     * x or y and an angle about them, and leaves the z pair alone.
     */
    private static @NotNull PoseExpr crossed(@NotNull PoseChannel channel, @NotNull PoseExpr value) {
        return switch (channel) {
            case X, Y, X_ROT, Y_ROT -> PoseExpr.Op.of(PoseOperator.NEG, value);
            default -> value;
        };
    }

    /** Records the first refusal, which is the one that describes what actually stopped the walk. */
    private void refuse(@NotNull String reason, Object @NotNull ... arguments) {
        if (this.refusal == null) this.refusal = String.format(reason, arguments);
    }

    // ------------------------------------------------------------------------------------
    // the value model
    // ------------------------------------------------------------------------------------

    /**
     * What the machine's values mean here - a literal at the width it was pushed, the arithmetic
     * that survives, and one reference for everything else.
     *
     * <p>An inner class rather than a static one so the addend arm can refuse: the reader recovers
     * a folded addend's degrees by division, and only here are the degrees still known to check
     * that against.
     */
    private final class Domain implements Interp.Domain<Object> {

        @Override
        public @Nullable Object decode(@NotNull AbstractInsnNode node) {
            Float single = AsmWalker.floatLiteral(node);
            if (single != null) return PoseExpr.Const.of((float) single);
            Double wide = AsmWalker.doubleLiteral(node);
            if (wide != null) return PoseExpr.Const.of((double) wide);
            Integer whole = AsmWalker.intLiteral(node);
            return whole == null ? null : PoseExpr.Const.of((int) whole);
        }

        @Override
        public @NotNull Object unknown() {
            return UNKNOWN;
        }

        @Override
        public @NotNull Object underflow() {
            return UNKNOWN;
        }

        @Override
        public @Nullable Object binary(int opcode, @NotNull Object left, @NotNull Object right) {
            // An addend folded into the body rotation - vanilla writes bodyRot + 180f - is the
            // turn about y the base applies as the subject's facing, in the degrees it takes, so
            // it becomes the built-and-unapplied turn the delegation emits as a step. The reader
            // recovers the degrees by dividing the folded radians back out, and float division
            // does not invert every float multiply, so a value the round trip moves is refused
            // here, where the degrees are still known, rather than shipped an ulp wrong.
            if (opcode == Opcodes.FADD && (left == BODY_ROT || right == BODY_ROT)) {
                Object other = left == BODY_ROT ? right : left;
                if (!(other instanceof PoseExpr.Const literal)) return null;
                float degrees = (float) literal.value();
                PoseExpr radians = PoseExpr.Op.of(PoseOperator.MUL,
                    literal, PoseExpr.Const.of(DEGREES_TO_RADIANS));
                double folded = radians.constantValue().orElse(Double.NaN);
                if ((float) folded / DEGREES_TO_RADIANS != degrees) {
                    refuse("folds '%s' into the body rotation, which the reader cannot recover", degrees);
                    return null;
                }
                return new QuatRef(PoseChannel.Y_ROT, radians);
            }
            PoseOperator operator = switch (opcode) {
                case Opcodes.FADD -> PoseOperator.ADD;
                case Opcodes.FSUB -> PoseOperator.SUB;
                case Opcodes.FMUL -> PoseOperator.MUL;
                case Opcodes.FDIV -> PoseOperator.DIV;
                case Opcodes.DADD -> PoseOperator.DADD;
                case Opcodes.DSUB -> PoseOperator.DSUB;
                case Opcodes.DMUL -> PoseOperator.DMUL;
                case Opcodes.DDIV -> PoseOperator.DDIV;
                default -> null;
            };
            if (operator == null || !(left instanceof PoseExpr lhs) || !(right instanceof PoseExpr rhs)) return null;
            return PoseExpr.Op.of(operator, lhs, rhs);
        }

        @Override
        public @Nullable Object unary(int opcode, @NotNull Object operand) {
            PoseOperator operator = switch (opcode) {
                case Opcodes.FNEG -> PoseOperator.NEG;
                case Opcodes.DNEG -> PoseOperator.DNEG;
                case Opcodes.F2D -> PoseOperator.F2D;
                case Opcodes.D2F -> PoseOperator.D2F;
                case Opcodes.I2F -> PoseOperator.I2F;
                case Opcodes.I2D -> PoseOperator.I2D;
                case Opcodes.F2I -> PoseOperator.F2I;
                default -> null;
            };
            if (operator == null || !(operand instanceof PoseExpr value)) return null;
            return PoseExpr.Op.of(operator, value);
        }

    }

}
