package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Interp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Node {@code overlays[].texture_scroll} - what a layer's render type translates its texture by,
 * per tick.
 *
 * <p>A property of the render TYPE rather than of the mesh: vanilla builds the offset into the
 * pipeline the layer submits through, so it moves where the pass samples and leaves the geometry
 * exactly where the layer put it. The breeze's wind is the corpus's one visible instance and its
 * silhouette is identical across every frame on both sides, which is the same statement read off the
 * pixels.
 *
 * <p><b>Read as a RATE, because the three sites are one shape.</b> Every factory taking an offset
 * takes {@code (ageInTicks * k) % 1} on each axis, so the constant is the whole of what varies and
 * the wrap is the type's own. That is read out of the arithmetic rather than fitted from an
 * evaluation: the machine carries the age as a value of its own, and a multiply of the age by a
 * literal is the only thing that becomes a rate.
 *
 * <p>Anything outside that shape answers nothing rather than a guess. A pass that scrolls by
 * something else would render an animation nobody authored, which looks deliberate.
 */
final class EntityTextureScrollResolver {

    /** The descriptor of every {@code RenderTypes} factory that takes a texture offset. */
    private static final @NotNull String OFFSET_FACTORY_DESC =
        "(L" + VanillaSourceClasses.Types.IDENTIFIER + ";FF)L" + VanillaSourceClasses.Types.RENDER_TYPE + ";";

    /** The render-state field every offset is accumulated from. */
    private static final @NotNull String AGE_IN_TICKS = "ageInTicks";

    /** The method a layer submits its pass through, which is where the factory is called. */
    private static final @NotNull String SUBMIT = "submit";

    /** What the machine pushes for the elapsed age, recognised by identity. */
    private static final @NotNull Object AGE = new Object();

    /** What the machine pushes where it cannot model a value, recognised by identity. */
    private static final @NotNull Object UNKNOWN = new Object();

    /** How deep a scroll expression may inline a helper, which the corpus needs one level of. */
    private static final int INLINE_DEPTH = 2;

    /** The age multiplied by a constant - the one shape a scroll is written in. */
    private record Rate(float perTick) {}

    private final @NotNull ClassNodeCache cache;

    EntityTextureScrollResolver(@NotNull ClassNodeCache cache) {
        this.cache = cache;
    }

    /**
     * The {@code texture_scroll} node one layer's pass carries, or {@code null} when it scrolls
     * nothing.
     *
     * <p>Read off the layer's own {@code submit}, walking up its hierarchy: a subclass that overrides
     * nothing submits through the declaration it inherits, which is where both energy-swirl layers
     * live. A zero pair is no scroll and answers {@code null}, so a factory called with the offset
     * every other pass would carry emits no member.
     *
     * @param layerClass the layer class the roster site names
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve(@NotNull String layerClass) {
        MethodNode submit = declaredSubmit(layerClass);
        if (submit == null) return null;

        Interp<Object> machine = Interp.of(new Domain(), Interp.OnUnknown.SILENT, Interp.Width.FLOAT_AS_FLOAT);
        float[] scroll = AsmWalker.over(submit)
            .drive(machine)
            .firstNotNull(node -> read(layerClass, machine, node));
        if (scroll == null || (scroll[0] == 0f && scroll[1] == 0f)) return null;
        return JsonTree.object().put("u", scroll[0]).put("v", scroll[1]);
    }

    /**
     * One instruction of a {@code submit} body: the age read, an inlined helper, and the factory
     * whose two float arguments are the answer.
     *
     * <p>Everything else is left to the machine, which models the arithmetic and the locals and
     * pushes its own unmodelled value for the rest - so a value this cannot follow arrives at the
     * factory as something that is not a rate and answers nothing there.
     */
    private float @Nullable [] read(
        @NotNull String layerClass, @NotNull Interp<Object> machine, @NotNull AbstractInsnNode node) {

        switch (node.getOpcode()) {
            case Opcodes.GETSTATIC -> machine.push(UNKNOWN);
            case Opcodes.GETFIELD -> {
                machine.pop();
                machine.push(node instanceof FieldInsnNode read && AGE_IN_TICKS.equals(read.name)
                    ? AGE : UNKNOWN);
            }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL -> {
                if (!(node instanceof MethodInsnNode call)) return null;
                inline(layerClass, machine, call);
            }
            case Opcodes.INVOKESTATIC -> {
                if (!(node instanceof MethodInsnNode call)
                    || !VanillaSourceClasses.Types.RENDER_TYPES.equals(call.owner)
                    || !OFFSET_FACTORY_DESC.equals(call.desc)) return null;
                Object down = machine.pop();
                Object along = machine.pop();
                machine.pop();
                Float u = rateOf(along);
                Float v = rateOf(down);
                return u == null || v == null ? null : new float[]{u, v};
            }
            default -> { }
        }
        return null;
    }

    /**
     * Runs a helper the body calls in a frame of its own, so a scroll written through one reads the
     * same as a scroll written inline.
     *
     * <p>Both energy-swirl layers spell their own rate that way, and the method they call is abstract
     * on the class that submits - so it is resolved against the LAYER rather than against the owner
     * the instruction names, which is what makes a creeper's rate its own and not a wither's.
     */
    private void inline(
        @NotNull String layerClass, @NotNull Interp<Object> machine, @NotNull MethodInsnNode call) {

        int arity = ClassKit.argTypes(call.desc).length;
        Object[] arguments = new Object[arity];
        for (int at = arity - 1; at >= 0; at--) arguments[at] = machine.pop();
        machine.pop();

        MethodNode body = call.desc.endsWith(")F") ? declared(layerClass, call.name, call.desc) : null;
        if (body == null) {
            if (!call.desc.endsWith(")V")) machine.push(UNKNOWN);
            return;
        }

        Interp<Object> frame = machine.child(INLINE_DEPTH);
        int slot = 1;
        for (Object argument : arguments) {
            frame.store(slot, argument);
            slot++;
        }
        Object[] answer = {UNKNOWN};
        AsmWalker.over(body).drive(frame).firstNotNull(node -> {
            if (node.getOpcode() != Opcodes.FRETURN) return null;
            answer[0] = frame.peek();
            return Boolean.TRUE;
        });
        machine.push(answer[0]);
    }

    /** The rate a factory argument carries, or {@code null} when it is not one. */
    private static @Nullable Float rateOf(@NotNull Object value) {
        if (value instanceof Rate rate) return rate.perTick();
        // A literal zero is a factory told to translate along that axis by nothing, which is a rate
        // of nothing rather than a value this failed to read.
        return value instanceof Float literal && literal == 0f ? 0f : null;
    }

    /** The {@code submit} taking the layer's own render state, looked up the layer's hierarchy. */
    private @Nullable MethodNode declaredSubmit(@NotNull String layerClass) {
        MethodNode[] found = {null};
        ClassKit.walkSuperChain(this.cache, layerClass, node -> {
            if (found[0] != null) return;
            for (MethodNode method : node.methods)
                if (SUBMIT.equals(method.name) && method.desc != null && method.desc.endsWith("FF)V")
                    && !isBridge(node, method)) {
                    found[0] = method;
                    return;
                }
        });
        return found[0];
    }

    /**
     * Whether a {@code submit} is the narrowing bridge javac writes rather than the body.
     *
     * <p>The bridge casts its state and forwards, so reading it would meet a checkcast and a call to
     * the very method being looked for. It is told apart by taking the BASE render state where the
     * body takes the layer's own.
     */
    private static boolean isBridge(@NotNull ClassNode owner, @NotNull MethodNode method) {
        for (var argument : ClassKit.argTypes(method.desc))
            if (argument.getSort() == org.objectweb.asm.Type.OBJECT
                && VanillaSourceClasses.Types.ENTITY_RENDER_STATE.equals(argument.getInternalName()))
                return owner.methods.stream().anyMatch(other -> other != method
                    && SUBMIT.equals(other.name) && other.desc != null && other.desc.endsWith("FF)V"));
        return false;
    }

    /** One method declared on the layer or anywhere up its chain. */
    private @Nullable MethodNode declared(
        @NotNull String layerClass, @NotNull String name, @NotNull String desc) {

        MethodNode[] found = {null};
        ClassKit.walkSuperChain(this.cache, layerClass, node -> {
            if (found[0] == null) found[0] = ClassKit.findMethod(node, name, desc);
        });
        return found[0];
    }

    /**
     * The value model: float literals, the age carried as a value of its own, and the one operation
     * that turns the two into a rate.
     */
    private static final class Domain implements Interp.Domain<Object> {

        @Override
        public @Nullable Object decode(@NotNull AbstractInsnNode node) {
            return AsmWalker.floatLiteral(node);
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
            // The age times a constant IS the rate, whichever side the constant is written on.
            if (opcode == Opcodes.FMUL && left == AGE && right instanceof Float perTick)
                return new Rate(perTick);
            if (opcode == Opcodes.FMUL && right == AGE && left instanceof Float perTick)
                return new Rate(perTick);
            // The wrap the factory is handed is the wrap a reader applies at the fetch, so a rate
            // carries through it unchanged rather than becoming a number.
            if (opcode == Opcodes.FREM && left instanceof Rate rate
                && right instanceof Float turn && turn == 1f) return rate;
            if (left instanceof Float lhs && right instanceof Float rhs) return switch (opcode) {
                case Opcodes.FMUL -> lhs * rhs;
                case Opcodes.FADD -> lhs + rhs;
                case Opcodes.FSUB -> lhs - rhs;
                default -> null;
            };
            return null;
        }

        @Override
        public @Nullable Object unary(int opcode, @NotNull Object operand) {
            return null;
        }

    }

}
