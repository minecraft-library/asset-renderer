package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Interp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads vanilla's keyframe clip tables out of the {@code <clinit>} of every class under the
 * animation definitions package.
 *
 * <p>The dialect is the easy one: a builder chain of literals with no arithmetic, no branches and
 * no loops, so the whole walk is a linear pass over an operand stack. What the chassis does not
 * model - object construction, array filling, the enum-like constants and the calls themselves -
 * is handled here, which is the division {@link Interp} is built around.
 *
 * <p>Unit conversion is folded at this end. Each of the three vector factories applies a different
 * transform, and applying it here means a channel's target is the only thing the renderer has to
 * dispatch on, with none of the three constants crossing the build boundary.
 *
 * <p>An unrecognised shape is a {@link Diagnostics} error and a skipped class, never a partial
 * clip: a table missing a channel reads as a pose rather than as a failure.
 */
@UtilityClass
public final class KeyframeDefinitionParser {

    /** Vanilla's float degrees-to-radians factor, applied at its own single precision. */
    private static final float DEGREES_TO_RADIANS = 0.017453292F;

    private static final @NotNull AnimationValue.Opaque OPAQUE = new AnimationValue.Opaque();
    private static final @NotNull AnimationValue.Uninit UNINIT = new AnimationValue.Uninit();

    /** Literals only - the tables carry no arithmetic, so an operation reaching the domain is a finding. */
    private static final Interp.Domain<AnimationValue> DOMAIN = new Interp.Domain<>() {

        @Override
        public @Nullable AnimationValue decode(@NotNull AbstractInsnNode node) {
            if (node instanceof LdcInsnNode ldc)
                return ldc.cst instanceof Number || ldc.cst instanceof String ? new AnimationValue.Lit(ldc.cst) : null;
            if (node instanceof IntInsnNode push
                && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH))
                return new AnimationValue.Lit(push.operand);
            if (!(node instanceof InsnNode)) return null;
            return switch (node.getOpcode()) {
                case Opcodes.FCONST_0 -> new AnimationValue.Lit(0f);
                case Opcodes.FCONST_1 -> new AnimationValue.Lit(1f);
                case Opcodes.FCONST_2 -> new AnimationValue.Lit(2f);
                case Opcodes.DCONST_0 -> new AnimationValue.Lit(0d);
                case Opcodes.DCONST_1 -> new AnimationValue.Lit(1d);
                case Opcodes.ICONST_M1 -> new AnimationValue.Lit(-1);
                case Opcodes.ICONST_0 -> new AnimationValue.Lit(0);
                case Opcodes.ICONST_1 -> new AnimationValue.Lit(1);
                case Opcodes.ICONST_2 -> new AnimationValue.Lit(2);
                case Opcodes.ICONST_3 -> new AnimationValue.Lit(3);
                case Opcodes.ICONST_4 -> new AnimationValue.Lit(4);
                case Opcodes.ICONST_5 -> new AnimationValue.Lit(5);
                default -> null;
            };
        }

        @Override
        public @NotNull AnimationValue unknown() {
            return OPAQUE;
        }

        @Override
        public @NotNull AnimationValue underflow() {
            return OPAQUE;
        }

        @Override
        public @Nullable AnimationValue binary(int opcode, @NotNull AnimationValue left, @NotNull AnimationValue right) {
            return null;
        }

        @Override
        public @Nullable AnimationValue unary(int opcode, @NotNull AnimationValue operand) {
            return null;
        }

    };

    /**
     * Parses every clip declared under the animation definitions package.
     *
     * @param cache the open client jar
     * @param diagnostics the scope errors are recorded against
     * @return every clip that parsed, in class-listing then declaration order
     */
    public static @NotNull List<KeyframeClip> parseAll(@NotNull ClassNodeCache cache, @NotNull Diagnostics diagnostics) {
        List<KeyframeClip> clips = new ArrayList<>();
        for (String entry : cache.list(VanillaSourceClasses.Types.ANIMATION_DEFINITIONS_ROOT, ".class")) {
            String owner = entry.substring(0, entry.length() - ".class".length());
            if (owner.endsWith("package-info")) continue;
            clips.addAll(parse(cache, owner, diagnostics.child(owner.substring(owner.lastIndexOf('/') + 1))));
        }
        return List.copyOf(clips);
    }

    /**
     * Parses one definitions class.
     *
     * @param cache the open client jar
     * @param owner the class's internal name
     * @param diagnostics the scope errors are recorded against
     * @return the clips this class declares, or an empty list when the walk found a shape it does
     *     not model
     */
    private static @NotNull List<KeyframeClip> parse(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull Diagnostics diagnostics) {

        Interp<AnimationValue> stack = Interp.of(DOMAIN, Interp.OnUnknown.SILENT, Interp.Width.BY_OPERANDS);
        List<KeyframeClip> clips = new ArrayList<>();
        boolean[] failed = {false};

        AsmWalker.clinit(cache, owner).real().forEach(in -> {
            if (failed[0]) return;
            try {
                step(in, owner, stack, clips);
            } catch (RuntimeException error) {
                failed[0] = true;
                diagnostics.error(error, "clip table walk stopped at %s", describe(in));
            }
        });
        return failed[0] ? List.of() : clips;
    }

    /** Applies one instruction, handing the chassis everything that is not construction or a call. */
    private static void step(
        @NotNull AbstractInsnNode in, @NotNull String owner,
        @NotNull Interp<AnimationValue> stack, @NotNull List<KeyframeClip> clips) {

        switch (in.getOpcode()) {
            case Opcodes.NEW -> stack.push(UNINIT);
            case Opcodes.ANEWARRAY -> stack.push(AnimationValue.Arr.sized(intOf(stack.pop())));
            case Opcodes.AASTORE -> {
                AnimationValue value = stack.pop();
                int index = intOf(stack.pop());
                AnimationValue target = stack.pop();
                if (!(target instanceof AnimationValue.Arr array) || !(value instanceof AnimationValue.Frame frame))
                    throw new IllegalStateException("array store outside a keyframe array");
                array.frames().set(index, frame);
            }
            case Opcodes.GETSTATIC -> {
                FieldInsnNode field = (FieldInsnNode) in;
                stack.push(new AnimationValue.Sym(field.owner, field.name));
            }
            case Opcodes.PUTSTATIC -> {
                FieldInsnNode field = (FieldInsnNode) in;
                if (!(stack.pop() instanceof AnimationValue.Clip clip))
                    throw new IllegalStateException("static field '" + field.name + "' is assigned something that is not a clip");
                clips.add(clip.seal(owner, field.name));
            }
            case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL -> call((MethodInsnNode) in, stack);
            default -> stack.step(in);
        }
    }

    /** Applies one call's stack effect, by owner and name; anything else is a finding. */
    private static void call(@NotNull MethodInsnNode call, @NotNull Interp<AnimationValue> stack) {
        String owner = call.owner;
        String name = call.name;

        if (VanillaSourceClasses.Types.KEYFRAME_ANIMATIONS.equals(owner)) {
            List<AnimationValue> args = stack.popArguments(3);
            stack.push(vector(name, args));
            return;
        }
        if (VanillaSourceClasses.Types.KEYFRAME.equals(owner)) {
            List<AnimationValue> args = stack.popArguments(3);
            stack.pop();
            stack.pop();
            if (!(args.get(1) instanceof AnimationValue.Vec vector)
                || !(args.get(2) instanceof AnimationValue.Sym interpolation))
                throw new IllegalStateException("keyframe built from something other than a vector and an interpolation");
            stack.push(new AnimationValue.Frame(floatOf(args.getFirst()), vector, lower(interpolation.name())));
            return;
        }
        if (VanillaSourceClasses.Types.ANIMATION_CHANNEL.equals(owner)) {
            List<AnimationValue> args = stack.popArguments(2);
            stack.pop();
            stack.pop();
            if (!(args.getFirst() instanceof AnimationValue.Sym target) || !(args.get(1) instanceof AnimationValue.Arr array))
                throw new IllegalStateException("channel built from something other than a target and a keyframe array");
            List<AnimationValue.Frame> frames = new ArrayList<>();
            for (AnimationValue.Frame frame : array.frames()) {
                if (frame == null) throw new IllegalStateException("keyframe array has a slot nothing stored into");
                frames.add(frame);
            }
            stack.push(new AnimationValue.Chan(lower(target.name()), frames));
            return;
        }
        if (VanillaSourceClasses.Types.ANIMATION_DEFINITION_BUILDER.equals(owner)) {
            builder(name, stack);
            return;
        }
        throw new IllegalStateException("call to '" + owner + "." + name + "' has no place in a clip table");
    }

    /** The builder chain: a static opener, two chained mutators and a terminal that answers the clip. */
    private static void builder(@NotNull String name, @NotNull Interp<AnimationValue> stack) {
        switch (name) {
            case VanillaSourceClasses.Methods.WITH_LENGTH -> stack.push(new AnimationValue.Clip(floatOf(stack.pop())));
            case VanillaSourceClasses.Methods.LOOPING -> {
                AnimationValue.Clip clip = clipOf(stack.pop());
                clip.markLooping();
                stack.push(clip);
            }
            case VanillaSourceClasses.Methods.ADD_ANIMATION -> {
                List<AnimationValue> args = stack.popArguments(2);
                AnimationValue.Clip clip = clipOf(stack.pop());
                if (!(args.getFirst() instanceof AnimationValue.Lit bone) || bone.asText() == null
                    || !(args.get(1) instanceof AnimationValue.Chan channel))
                    throw new IllegalStateException("addAnimation called with something other than a bone name and a channel");
                clip.add(bone.asText(), channel);
                stack.push(clip);
            }
            case VanillaSourceClasses.Methods.BUILD -> stack.push(clipOf(stack.pop()));
            default -> throw new IllegalStateException("builder method '" + name + "' has no place in a clip table");
        }
    }

    /**
     * Folds one of the three vector factories, each of which converts differently: a rotation is
     * authored in degrees, a position is authored y-up against a y-down accumulator, and a scale
     * is authored as a multiplier against an accumulator that adds, so vanilla stores it minus one
     * and does so in double before narrowing.
     */
    private static @NotNull AnimationValue.Vec vector(@NotNull String factory, @NotNull List<AnimationValue> args) {
        return switch (factory) {
            case VanillaSourceClasses.Methods.DEGREE_VEC -> new AnimationValue.Vec(
                floatOf(args.get(0)) * DEGREES_TO_RADIANS,
                floatOf(args.get(1)) * DEGREES_TO_RADIANS,
                floatOf(args.get(2)) * DEGREES_TO_RADIANS);
            case VanillaSourceClasses.Methods.POS_VEC -> new AnimationValue.Vec(
                floatOf(args.get(0)), -floatOf(args.get(1)), floatOf(args.get(2)));
            case VanillaSourceClasses.Methods.SCALE_VEC -> new AnimationValue.Vec(
                (float) (doubleOf(args.get(0)) - 1.0),
                (float) (doubleOf(args.get(1)) - 1.0),
                (float) (doubleOf(args.get(2)) - 1.0));
            default -> throw new IllegalStateException("'" + factory + "' is not one of the three keyframe vector factories");
        };
    }

    private static @NotNull AnimationValue.Clip clipOf(@NotNull AnimationValue value) {
        if (value instanceof AnimationValue.Clip clip) return clip;
        throw new IllegalStateException("builder chain reached something that is not a clip");
    }

    private static float floatOf(@NotNull AnimationValue value) {
        if (value instanceof AnimationValue.Lit literal) return literal.asFloat();
        throw new IllegalStateException("expected a numeric literal");
    }

    private static double doubleOf(@NotNull AnimationValue value) {
        if (value instanceof AnimationValue.Lit literal) return literal.asDouble();
        throw new IllegalStateException("expected a numeric literal");
    }

    private static int intOf(@NotNull AnimationValue value) {
        if (value instanceof AnimationValue.Lit literal) return (int) literal.asFloat();
        throw new IllegalStateException("expected an integer literal");
    }

    /** Vanilla names these constants in upper case; the shipped tables spell every token lower. */
    private static @NotNull String lower(@NotNull String constant) {
        return constant.toLowerCase(java.util.Locale.ROOT);
    }

    private static @NotNull String describe(@NotNull AbstractInsnNode in) {
        if (in instanceof MethodInsnNode call) return call.owner + "." + call.name + call.desc;
        if (in instanceof FieldInsnNode field) return field.owner + "." + field.name;
        return "opcode " + in.getOpcode();
    }

}
