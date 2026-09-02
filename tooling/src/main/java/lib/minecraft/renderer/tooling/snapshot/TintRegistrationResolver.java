package lib.minecraft.renderer.tooling.snapshot;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Resolves one committed {@code BlockColors.register(source, blocks)} into a tint target (or a
 * {@code dropped[]} reason) by deriving it from the source factory's bytecode rather than a
 * name map:
 * <ul>
 *   <li><b>colormap target</b> - resolve the source factory's returned inner class and scan its
 *       body for the {@code BiomeColors.getAverage{Grass,Foliage,DryFoliage}Color} call; the
 *       grass-family collapse ({@code grass}/{@code grassBlock}/{@code sugarCane}/
 *       {@code doubleTallGrass}) falls out of the shared {@code getAverageGrassColor} target.</li>
 *   <li><b>constant</b> - the {@code constant(colorInHand[, colorInWorld])} in-hand pick
 *       ({@code TINT_CONSTANT_IN_HAND}).</li>
 *   <li><b>stem</b> - symbolic evaluation of {@code ARGB.color(age*32, 255-age*8, age*4)} at
 *       {@code age=0} (freshly-placed default state = the AGE property min); this derives the
 *       {@code 0xFF00FF00} result without hardcoding the literal.</li>
 *   <li><b>drop</b> - a source with no colormap target and a name in
 *       {@code SnapshotShapePolicies.dynamicSourceDrops()} is recorded as {@code dynamic_source};
 *       an unknown one is a loud {@link Diagnostics#error}.</li>
 * </ul>
 */
@UtilityClass
final class TintRegistrationResolver {

    /**
     * The classification of one committed registration: a tint (target + optional constant) or a
     * drop (a {@code dropped[]} reason), plus the provenance source label ({@code tints[].source}).
     *
     * @param target the tint-target name when a tint, or {@code null} on a drop
     * @param constant the ARGB constant when {@code target == CONSTANT}, else {@code null}
     * @param dropReason the {@code dropped[]} reason when a drop, or {@code null} when a tint
     * @param sourceLabel the factory-name provenance (with the {@code @age=0} note for stem)
     */
    record Resolution(
        @Nullable String target,
        @Nullable Integer constant,
        @Nullable String dropReason,
        @NotNull String sourceLabel
    ) {

        boolean isDrop() {
            return this.dropReason != null;
        }
    }

    /**
     * The {@code @age=0} provenance suffix on the stem source label (the eval-state note).
     */
    private static final @NotNull String STEM_LABEL = "@age=0";

    /**
     * The composed multi-source drop provenance label.
     */
    private static final @NotNull String LIST_OF_LABEL = "List.of";

    /**
     * Classifies the source of one registration.
     *
     * @param cache the session cache
     * @param sourceName the {@code BlockTintSources} factory method name (last one seen)
     * @param inHandConstant the picked in-hand constant when the source is {@code constant}, else {@code null}
     * @param multiSource whether the registration composed multiple sources ({@code List.of} arity {@literal >} 1)
     * @param diagnostics the walk's scope
     * @return the resolution, or {@code null} on a loud unknown-source failure
     */
    static @Nullable Resolution resolve(
        @NotNull ClassNodeCache cache,
        @NotNull String sourceName,
        @Nullable Integer inHandConstant,
        boolean multiSource,
        @NotNull Diagnostics diagnostics
    ) {
        if (multiSource)
            return new Resolution(null, null, SnapshotShapePolicies.multiSourceReason(), LIST_OF_LABEL);

        if (sourceName.equals(VanillaSourceClasses.Methods.CONSTANT)) {
            int value = inHandConstant != null ? inHandConstant : 0;
            return new Resolution(TARGET_CONSTANT, value, null, sourceName);
        }

        String innerClass = resolveSourceClass(cache, sourceName);
        if (innerClass == null) {
            diagnostics.error("tint source '%s' resolves no BlockTintSource inner class", sourceName);
            return null;
        }

        if (sourceName.equals(VanillaSourceClasses.Methods.STEM)) {
            Integer stem = evalStem(cache, innerClass, diagnostics);
            if (stem == null) return null;
            return new Resolution(TARGET_CONSTANT, stem, null, sourceName + STEM_LABEL);
        }

        String target = deriveTarget(cache, innerClass);
        if (target != null)
            return new Resolution(target, null, null, sourceName);

        if (SnapshotShapePolicies.dynamicSourceDrops().contains(sourceName))
            return new Resolution(null, null, SnapshotShapePolicies.REASON_DYNAMIC_SOURCE, sourceName);

        diagnostics.error("tint source '%s' (%s) resolves no colormap target and is not a known dynamic drop",
            sourceName, innerClass);
        return null;
    }

    /**
     * Resolves the {@code BlockTintSource} inner class a factory method instantiates by scanning
     * its body for the first {@code NEW BlockTintSources$N}.
     */
    private static @Nullable String resolveSourceClass(@NotNull ClassNodeCache cache, @NotNull String factoryName) {
        ClassNode sources = cache.load(VanillaSourceClasses.Types.BLOCK_TINT_SOURCES);
        if (sources == null) return null;
        MethodNode factory = ClassKit.findMethod(sources, factoryName);
        if (factory == null) return null;
        return AsmWalker.over(factory)
            .new_(VanillaSourceClasses.Types.BLOCK_TINT_SOURCES)
            .firstNotNull(type -> type.desc);
    }

    /**
     * The tint-target names written into {@code block_tints.json}, spelled as the renderer's own
     * {@code Block.TintTarget} constants. Held as values rather than as that enum because this build
     * does not resolve against the renderer; the agreement is enforced where it matters, at load,
     * where the shipped string is deserialised straight into that enum.
     */
    private static final @NotNull String TARGET_CONSTANT = "CONSTANT";
    private static final @NotNull String TARGET_GRASS = "GRASS";
    private static final @NotNull String TARGET_FOLIAGE = "FOLIAGE";
    private static final @NotNull String TARGET_DRY_FOLIAGE = "DRY_FOLIAGE";

    /**
     * Derives the colormap target by scanning the source inner class's methods for the
     * {@code BiomeColors.getAverage*Color} call that names the colormap it samples.
     */
    private static @Nullable String deriveTarget(@NotNull ClassNodeCache cache, @NotNull String innerClass) {
        ClassNode node = cache.load(innerClass);
        if (node == null) return null;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            String target = AsmWalker.over(method).firstNotNull(in -> {
                if (AsmWalker.isInvokeStatic(in, VanillaSourceClasses.Types.BIOME_COLORS, VanillaSourceClasses.Methods.GET_AVERAGE_GRASS_COLOR))
                    return TARGET_GRASS;
                if (AsmWalker.isInvokeStatic(in, VanillaSourceClasses.Types.BIOME_COLORS, VanillaSourceClasses.Methods.GET_AVERAGE_FOLIAGE_COLOR))
                    return TARGET_FOLIAGE;
                if (AsmWalker.isInvokeStatic(in, VanillaSourceClasses.Types.BIOME_COLORS, VanillaSourceClasses.Methods.GET_AVERAGE_DRY_FOLIAGE_COLOR))
                    return TARGET_DRY_FOLIAGE;
                return null;
            });
            if (target != null) return target;
        }
        return null;
    }

    /**
     * Symbolically evaluates the stem source's {@code color(BlockState)} body with the AGE local
     * bound to 0 (the freshly-placed default state = the AGE property min), following the terminal
     * {@code ARGB.color} call into its own body. Yields {@code 0xFF00FF00} without hardcoding the literal.
     */
    private static @Nullable Integer evalStem(@NotNull ClassNodeCache cache, @NotNull String innerClass, @NotNull Diagnostics diagnostics) {
        ClassNode node = cache.load(innerClass);
        String colorDesc = VanillaSourceClasses.Descs.of("I", VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK_STATE));
        MethodNode color = node == null ? null : ClassKit.findMethod(node, VanillaSourceClasses.Methods.COLOR, colorDesc);
        if (color == null) {
            diagnostics.error("stem source '%s' exposes no color(BlockState) body", innerClass);
            return null;
        }
        // The AGE read is `getValue(AGE); checkcast Integer; intValue; istore <age>`. Bind that
        // local to 0 (the min / freshly-placed default) and evaluate the arithmetic that follows.
        AbstractInsnNode getValue = AsmWalker.over(color).first(in ->
            AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.BLOCK_STATE, VanillaSourceClasses.Methods.GET_VALUE));
        AbstractInsnNode store = AsmWalker.from(getValue).first(Insn.opcode(Opcodes.ISTORE));
        if (store == null) {
            diagnostics.error("stem source '%s' color body has no AGE istore to bind", innerClass);
            return null;
        }
        Map<Integer, Integer> locals = new HashMap<>();
        locals.put(((VarInsnNode) store).var, 0);
        try {
            return evalInt(cache, store.getNext(), locals);
        } catch (RuntimeException ex) {
            diagnostics.error(ex, "stem source '%s' color body is not statically evaluable", innerClass);
            return null;
        }
    }

    /**
     * Interprets a straight-line int expression from {@code start} to its {@code IRETURN},
     * reading {@code ILOAD} from {@code locals} and recursing into {@code INVOKESTATIC} int
     * helpers. Throws on any opcode outside the int arithmetic / bitwise / literal / static-call
     * set (loud failure, never a fallback literal).
     */
    private static int evalInt(@NotNull ClassNodeCache cache, @Nullable AbstractInsnNode start, @NotNull Map<Integer, Integer> locals) {
        Interp<Integer> machine = Interp.of(new IntExpressionDomain(), Interp.OnUnknown.THROW, Interp.Width.BY_OPERANDS);
        locals.forEach(machine::store);
        // The machine owns the stack - literal pushes, the bound-local ILOAD and the arithmetic
        // all land in its step before the dispatch below sees the node. The dispatch recognises
        // what matters to the flow: the unbound-local fault, the INVOKESTATIC recursion, the
        // IRETURN result, and the loud rejection of everything else.
        Integer result = AsmWalker.from(start)
            .drive(machine)
            .firstNotNull(in -> {
                if (AsmWalker.isPseudoNode(in) || AsmWalker.intLiteral(in) != null) return null;
                switch (in.getOpcode()) {
                    case Opcodes.ILOAD -> {
                        if (machine.slot(((VarInsnNode) in).var) == null)
                            throw new IllegalStateException("unbound local");
                    }
                    case Opcodes.IMUL, Opcodes.IADD, Opcodes.ISUB, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR,
                         Opcodes.INEG, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR -> { }
                    case Opcodes.INVOKESTATIC -> {
                        MethodInsnNode call = (MethodInsnNode) in;
                        List<Integer> popped = machine.popArguments(Type.getArgumentTypes(call.desc).length);
                        int[] args = new int[popped.size()];
                        for (int i = 0; i < args.length; i++) args[i] = popped.get(i);
                        machine.push(evalStaticInt(cache, call.owner, call.name, call.desc, args));
                    }
                    case Opcodes.IRETURN -> {
                        return machine.pop();
                    }
                    default -> throw new IllegalStateException("Unhandled opcode " + in.getOpcode() + " in int expression");
                }
                return null;
            });
        if (result == null) throw new IllegalStateException("int expression fell off the end without IRETURN");
        return result;
    }

    /**
     * Evaluates an all-{@code int} static method body with the given argument values (each maps to
     * its slot, all-int so slot index == argument index).
     */
    private static int evalStaticInt(@NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String name, @NotNull String desc, int @NotNull [] args) {
        ClassNode node = cache.require(owner, "int-expression callee");
        MethodNode method = ClassKit.findMethod(node, name, desc);
        if (method == null) throw new IllegalStateException("callee " + owner + "." + name + desc + " not found");
        Map<Integer, Integer> locals = IntStream.range(0, args.length)
            .boxed()
            .collect(Collectors.toMap(slot -> slot, slot -> args[slot]));
        return evalInt(cache, method.instructions.getFirst(), locals);
    }

    /**
     * The int-expression value model: literals via {@link AsmWalker#intLiteral}, the int
     * arithmetic and bitwise set, and a loud {@link IllegalStateException} - never a fallback
     * value - for anything outside it. The decode latches the opcode being stepped so an
     * underflow met while the machine steps an instruction the dispatch rejects reports that
     * opcode, exactly as the rejection arm does, rather than a misleading empty-stack fault.
     */
    private static final class IntExpressionDomain implements Interp.Domain<Integer> {

        /**
         * The unknown placeholder, recognised by identity - a distinct uncached instance no
         * evaluated value can alias. It never survives onto the stack of a completing
         * evaluation, because every arm that could push it also aborts the walk at that node.
         */
        private static final @NotNull Integer UNKNOWN = Integer.valueOf(Integer.MIN_VALUE);

        /** The opcode of the instruction currently being stepped, recorded at decode. */
        private int currentOpcode = -1;

        @Override
        public @Nullable Integer decode(@NotNull AbstractInsnNode node) {
            this.currentOpcode = node.getOpcode();
            return AsmWalker.intLiteral(node);
        }

        @Override
        public @NotNull Integer unknown() {
            return UNKNOWN;
        }

        @Override
        public @NotNull Integer underflow() {
            throw switch (this.currentOpcode) {
                case Opcodes.IMUL, Opcodes.IADD, Opcodes.ISUB, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR,
                     Opcodes.INEG, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
                     Opcodes.INVOKESTATIC, Opcodes.IRETURN ->
                    new IllegalStateException("int stack underflow");
                default -> new IllegalStateException("Unhandled opcode " + this.currentOpcode + " in int expression");
            };
        }

        @Override
        public @Nullable Integer binary(int opcode, @NotNull Integer left, @NotNull Integer right) {
            return switch (opcode) {
                case Opcodes.IMUL -> left * right;
                case Opcodes.IADD -> left + right;
                case Opcodes.ISUB -> left - right;
                case Opcodes.IAND -> left & right;
                case Opcodes.IOR -> left | right;
                case Opcodes.IXOR -> left ^ right;
                case Opcodes.ISHL -> left << right;
                case Opcodes.ISHR -> left >> right;
                case Opcodes.IUSHR -> left >>> right;
                default -> throw new IllegalStateException("Unhandled opcode " + opcode + " in int expression");
            };
        }

        @Override
        public @Nullable Integer unary(int opcode, @NotNull Integer operand) {
            if (opcode == Opcodes.INEG) return -operand;
            throw new IllegalStateException("Unhandled opcode " + opcode + " in int expression");
        }

    }

}
