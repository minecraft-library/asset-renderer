package lib.minecraft.renderer.tooling.snapshot;

import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

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
final class TintRegistrationResolver {

    private TintRegistrationResolver() {
    }

    /**
     * The classification of one committed registration: a tint (target + optional constant) or a
     * drop (a {@code dropped[]} reason), plus the provenance source label ({@code tints[].source}).
     *
     * @param target the {@code Block.TintTarget} name when a tint, or {@code null} on a drop
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

    /** The {@code @age=0} provenance suffix on the stem source label (the eval-state note). */
    private static final @NotNull String STEM_LABEL = "@age=0";

    /** The composed multi-source drop provenance label. */
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
            return new Resolution(Block.TintTarget.CONSTANT.name(), value, null, sourceName);
        }

        String innerClass = resolveSourceClass(cache, sourceName);
        if (innerClass == null) {
            diagnostics.error("tint source '%s' resolves no BlockTintSource inner class", sourceName);
            return null;
        }

        if (sourceName.equals(VanillaSourceClasses.Methods.STEM)) {
            Integer stem = evalStem(cache, innerClass, diagnostics);
            if (stem == null) return null;
            return new Resolution(Block.TintTarget.CONSTANT.name(), stem, null, sourceName + STEM_LABEL);
        }

        Block.TintTarget target = deriveTarget(cache, innerClass);
        if (target != null)
            return new Resolution(target.name(), null, null, sourceName);

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
        MethodNode factory = AsmKit.findMethod(sources, factoryName);
        if (factory == null) return null;
        for (AbstractInsnNode in : factory.instructions)
            if (in.getOpcode() == Opcodes.NEW
                && in instanceof TypeInsnNode type
                && type.desc.startsWith(VanillaSourceClasses.Types.BLOCK_TINT_SOURCES))
                return type.desc;
        return null;
    }

    /**
     * Derives the colormap target by scanning the source inner class's methods for the
     * {@code BiomeColors.getAverage*Color} call that names the colormap it samples.
     */
    private static @Nullable Block.TintTarget deriveTarget(@NotNull ClassNodeCache cache, @NotNull String innerClass) {
        ClassNode node = cache.load(innerClass);
        if (node == null) return null;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode in : method.instructions) {
                if (AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.BIOME_COLORS, VanillaSourceClasses.Methods.GET_AVERAGE_GRASS_COLOR))
                    return Block.TintTarget.GRASS;
                if (AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.BIOME_COLORS, VanillaSourceClasses.Methods.GET_AVERAGE_FOLIAGE_COLOR))
                    return Block.TintTarget.FOLIAGE;
                if (AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.BIOME_COLORS, VanillaSourceClasses.Methods.GET_AVERAGE_DRY_FOLIAGE_COLOR))
                    return Block.TintTarget.DRY_FOLIAGE;
            }
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
        MethodNode color = node == null ? null : AsmKit.findMethod(node, VanillaSourceClasses.Methods.COLOR, colorDesc);
        if (color == null) {
            diagnostics.error("stem source '%s' exposes no color(BlockState) body", innerClass);
            return null;
        }
        // The AGE read is `getValue(AGE); checkcast Integer; intValue; istore <age>`. Bind that
        // local to 0 (the min / freshly-placed default) and evaluate the arithmetic that follows.
        AbstractInsnNode getValue = null;
        for (AbstractInsnNode in : color.instructions)
            if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.BLOCK_STATE, VanillaSourceClasses.Methods.GET_VALUE)) {
                getValue = in;
                break;
            }
        AbstractInsnNode store = getValue;
        while (store != null && store.getOpcode() != Opcodes.ISTORE) store = store.getNext();
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
        Deque<Integer> stack = new ArrayDeque<>();
        for (AbstractInsnNode in = start; in != null; in = in.getNext()) {
            if (AsmKit.isPseudoNode(in)) continue;
            Integer literal = AsmKit.readIntLiteral(in);
            if (literal != null) {
                stack.push(literal);
                continue;
            }
            switch (in.getOpcode()) {
                case Opcodes.ILOAD -> stack.push(require(locals.get(((VarInsnNode) in).var), "unbound local"));
                case Opcodes.IMUL -> stack.push(pop(stack) * pop(stack));
                case Opcodes.IADD -> stack.push(pop(stack) + pop(stack));
                case Opcodes.ISUB -> { int b = pop(stack); stack.push(pop(stack) - b); }
                case Opcodes.IAND -> stack.push(pop(stack) & pop(stack));
                case Opcodes.IOR -> stack.push(pop(stack) | pop(stack));
                case Opcodes.IXOR -> stack.push(pop(stack) ^ pop(stack));
                case Opcodes.INEG -> stack.push(-pop(stack));
                case Opcodes.ISHL -> { int b = pop(stack); stack.push(pop(stack) << b); }
                case Opcodes.ISHR -> { int b = pop(stack); stack.push(pop(stack) >> b); }
                case Opcodes.IUSHR -> { int b = pop(stack); stack.push(pop(stack) >>> b); }
                case Opcodes.INVOKESTATIC -> {
                    MethodInsnNode call = (MethodInsnNode) in;
                    int arity = Type.getArgumentTypes(call.desc).length;
                    int[] args = new int[arity];
                    for (int i = arity - 1; i >= 0; i--) args[i] = pop(stack);
                    stack.push(evalStaticInt(cache, call.owner, call.name, call.desc, args));
                }
                case Opcodes.IRETURN -> { return pop(stack); }
                default -> throw new IllegalStateException("Unhandled opcode " + in.getOpcode() + " in int expression");
            }
        }
        throw new IllegalStateException("int expression fell off the end without IRETURN");
    }

    /**
     * Evaluates an all-{@code int} static method body with the given argument values (each maps to
     * its slot, all-int so slot index == argument index).
     */
    private static int evalStaticInt(@NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String name, @NotNull String desc, int @NotNull [] args) {
        ClassNode node = cache.require(owner, "int-expression callee");
        MethodNode method = AsmKit.findMethod(node, name, desc);
        if (method == null) throw new IllegalStateException("callee " + owner + "." + name + desc + " not found");
        Map<Integer, Integer> locals = new HashMap<>();
        for (int i = 0; i < args.length; i++) locals.put(i, args[i]);
        return evalInt(cache, method.instructions.getFirst(), locals);
    }

    private static int pop(@NotNull Deque<Integer> stack) {
        Integer top = stack.poll();
        if (top == null) throw new IllegalStateException("int stack underflow");
        return top;
    }

    private static int require(@Nullable Integer value, @NotNull String what) {
        if (value == null) throw new IllegalStateException(what);
        return value;
    }

}
