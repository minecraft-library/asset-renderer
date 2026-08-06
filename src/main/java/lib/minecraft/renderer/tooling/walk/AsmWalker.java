package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The raw root of a walk - sources, geometry, fold attachment, trace, and the decode,
 * one-hop and instruction-predicate statics, alongside the pattern-decode statics (lambda
 * metafactory targets, concat recipes, static-table reads, integer for-loop detection). Every instruction walk is a
 * descriptor built here and compiled onto one drive loop by its terminal; narrowing (a match
 * stage, {@code where}, a projection) answers {@link Walk}, on which no fold stage exists, so
 * a stateful cell can never sit downstream of a filter. One-hop neighbour reads
 * ({@link #nextReal}, {@link #previousReal}) are expressions, not walks, and stay statics.
 */
public final class AsmWalker extends Walk<AbstractInsnNode> {

    private AsmWalker(@NotNull Descriptor descriptor) {
        super(descriptor);
    }

    // ------------------------------------------------------------------------------------
    // sources - direction and anchor inclusivity are baked into the name
    // ------------------------------------------------------------------------------------

    /**
     * Walks a whole method body forward, raw.
     *
     * @param method the method whose instructions are walked
     * @return the root walk
     */
    public static @NotNull AsmWalker over(@NotNull MethodNode method) {
        return new AsmWalker(new Descriptor(Descriptor.Source.over(method)));
    }

    /**
     * The fused named-method opener: loads the class through the cache and walks the named
     * method's body forward. Either miss is readable before any run via {@link #missing()};
     * collector-ended chains on a missing source answer empty, the find family answers its
     * ordinary miss, and {@code run}-shaped terminals answer {@link Exit#MISSING}.
     *
     * @param cache the per-session cache to consult
     * @param owner the class's JVM internal name
     * @param methodName the method name
     * @return the root walk
     */
    public static @NotNull AsmWalker over(@NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String methodName) {
        ClassNode classNode = cache.load(owner);
        if (classNode == null) return new AsmWalker(new Descriptor(Descriptor.Source.missing(Missing.CLASS)));
        MethodNode method = AsmKit.findMethod(classNode, methodName);
        if (method == null) return new AsmWalker(new Descriptor(Descriptor.Source.missing(Missing.MEMBER)));
        return new AsmWalker(new Descriptor(Descriptor.Source.over(method)));
    }

    /**
     * Walks a class's static initialiser - {@code over(cache, owner, "<clinit>")}.
     *
     * @param cache the per-session cache to consult
     * @param owner the class's JVM internal name
     * @return the root walk
     */
    public static @NotNull AsmWalker clinit(@NotNull ClassNodeCache cache, @NotNull String owner) {
        return over(cache, owner, AsmKit.CLINIT);
    }

    /**
     * Walks forward from the anchor, anchor included. A {@code null} anchor is the empty walk.
     *
     * @param node the anchor, or {@code null} for an empty walk
     * @return the root walk
     */
    public static @NotNull AsmWalker from(@Nullable AbstractInsnNode node) {
        return new AsmWalker(new Descriptor(Descriptor.Source.from(node)));
    }

    /**
     * Walks forward from the anchor, anchor excluded.
     *
     * @param node the anchor
     * @return the root walk
     */
    public static @NotNull AsmWalker after(@NotNull AbstractInsnNode node) {
        return new AsmWalker(new Descriptor(Descriptor.Source.after(node)));
    }

    /**
     * Walks backward from the anchor, anchor excluded - the only backward source.
     *
     * @param node the anchor
     * @return the root walk
     */
    public static @NotNull AsmWalker before(@NotNull AbstractInsnNode node) {
        return new AsmWalker(new Descriptor(Descriptor.Source.before(node)));
    }

    // ------------------------------------------------------------------------------------
    // geometry - root-only, each answering a new descriptor
    // ------------------------------------------------------------------------------------

    /**
     * Yields only nodes with a real opcode. Opt-in, never the default: a raw walk offers
     * labels, frames and line numbers to its stages, and a strict cell is defined over what is
     * offered.
     *
     * @return the filtered walk
     */
    public @NotNull AsmWalker real() {
        return new AsmWalker(this.descriptor.real());
    }

    /**
     * Stops exclusively at the given node, matched by identity on the raw chain - a label
     * sentinel stops a real-only walk, because identity is position and position is geometry.
     *
     * @param sentinel the stop node, or {@code null} for an unbounded walk
     * @return the bounded walk
     */
    public @NotNull AsmWalker until(@Nullable AbstractInsnNode sentinel) {
        return new AsmWalker(this.descriptor.untilNode(sentinel));
    }

    /**
     * Stops exclusively at the first yield the recognizer accepts - the body-checked break. A
     * stop that performs an observable act on the stopping node is a commit, never a bound.
     *
     * @param stop the stop recognizer, tested on yields
     * @return the bounded walk
     */
    public @NotNull AsmWalker until(@NotNull Match<?> stop) {
        return new AsmWalker(this.descriptor.untilMatch(stop));
    }

    /**
     * Caps the walk's yields under the current pseudo policy - real hops on a {@code real()}
     * descriptor, raw hops otherwise. The cap answers {@link Exit#BUDGET} only when it refused
     * a yield that arrived; where a stop and an exhausted budget coincide on one node, the
     * stop wins.
     *
     * @param maxYields the yield budget
     * @return the bounded walk
     */
    public @NotNull AsmWalker limit(int maxYields) {
        return new AsmWalker(this.descriptor.limit(maxYields));
    }

    // ------------------------------------------------------------------------------------
    // match stages - filter and narrow in one step
    // ------------------------------------------------------------------------------------

    private <N extends AbstractInsnNode> @NotNull Walk<N> matched(@NotNull Match<N> match) {
        return new Walk<>(this.descriptor.with(new Descriptor.Narrow(v -> match.matches((AbstractInsnNode) v) ? v : null)));
    }

    /**
     * Narrows to {@code INVOKEVIRTUAL} calls on the given owner and name.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @return the narrowed walk
     */
    public @NotNull Walk<MethodInsnNode> invokeVirtual(@NotNull String owner, @NotNull String name) {
        return matched(Insn.invokeVirtual(owner, name));
    }

    /**
     * Narrows to {@code INVOKESTATIC} calls on the given owner and name.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @return the narrowed walk
     */
    public @NotNull Walk<MethodInsnNode> invokeStatic(@NotNull String owner, @NotNull String name) {
        return matched(Insn.invokeStatic(owner, name));
    }

    /**
     * Narrows to {@code INVOKESTATIC} calls matching owner, name and descriptor.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @param desc the method descriptor
     * @return the narrowed walk
     */
    public @NotNull Walk<MethodInsnNode> invokeStatic(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        return matched(Insn.invokeStatic(owner, name, desc));
    }

    /**
     * Narrows to {@code INVOKESPECIAL} calls on the given owner and name.
     *
     * @param owner the owner's JVM internal name
     * @param name the method name
     * @return the narrowed walk
     */
    public @NotNull Walk<MethodInsnNode> invokeSpecial(@NotNull String owner, @NotNull String name) {
        return matched(Insn.invokeSpecial(owner, name));
    }

    /**
     * Narrows to {@code GETSTATIC} reads on the given owner.
     *
     * @param owner the owner's JVM internal name
     * @return the narrowed walk
     */
    public @NotNull Walk<FieldInsnNode> getStatic(@NotNull String owner) {
        return matched(Insn.getStatic(owner));
    }

    /**
     * Narrows to {@code GETSTATIC} reads of the given field.
     *
     * @param owner the owner's JVM internal name
     * @param name the field name
     * @return the narrowed walk
     */
    public @NotNull Walk<FieldInsnNode> getStatic(@NotNull String owner, @NotNull String name) {
        return matched(Insn.getStatic(owner, name));
    }

    /**
     * Narrows to {@code PUTSTATIC} writes on the given owner.
     *
     * @param owner the owner's JVM internal name
     * @return the narrowed walk
     */
    public @NotNull Walk<FieldInsnNode> putStatic(@NotNull String owner) {
        return matched(Insn.putStatic(owner));
    }

    /**
     * Narrows to {@code PUTSTATIC} writes of the given field.
     *
     * @param owner the owner's JVM internal name
     * @param name the field name
     * @return the narrowed walk
     */
    public @NotNull Walk<FieldInsnNode> putStatic(@NotNull String owner, @NotNull String name) {
        return matched(Insn.putStatic(owner, name));
    }

    /**
     * Narrows to {@code GETFIELD} reads matching field name and descriptor.
     *
     * @param name the field name
     * @param desc the field descriptor
     * @return the narrowed walk
     */
    public @NotNull Walk<FieldInsnNode> getField(@NotNull String name, @NotNull String desc) {
        return matched(Insn.getField(name, desc));
    }

    /**
     * Narrows to {@code PUTFIELD} writes matching owner and field name.
     *
     * @param owner the owner's JVM internal name
     * @param name the field name
     * @return the narrowed walk
     */
    public @NotNull Walk<FieldInsnNode> putField(@NotNull String owner, @NotNull String name) {
        return matched(Insn.putField(owner, name));
    }

    /**
     * Narrows to {@code NEW} instructions whose target starts with the prefix.
     *
     * @param internalNamePrefix the target-type internal-name prefix
     * @return the narrowed walk
     */
    public @NotNull Walk<TypeInsnNode> new_(@NotNull String internalNamePrefix) {
        return matched(Insn.new_(internalNamePrefix));
    }

    /**
     * Narrows to instructions carrying one of the given opcodes.
     *
     * @param ops the accepted opcodes
     * @return the narrowed walk
     */
    public @NotNull Walk<AbstractInsnNode> opcode(int @NotNull ... ops) {
        return matched(Insn.opcode(ops));
    }

    /**
     * The first node the recognizer accepts, typed by the recognizer.
     *
     * @param match the recognizer
     * @return the first matching node, or {@code null}
     */
    public <N extends AbstractInsnNode> @Nullable N first(@NotNull Match<N> match) {
        return matched(match).first();
    }

    // ------------------------------------------------------------------------------------
    // fold stages - the linear relay, root-attached
    // ------------------------------------------------------------------------------------

    /**
     * Attaches an ordered pending list: every yield the decoder answers non-null for is
     * gathered and claimed. The buffer empties at each commit unless retained.
     *
     * @param decoder the per-node decoder
     * @return the gathering walk
     */
    @SuppressWarnings("unchecked")
    public <G> @NotNull GatherWalk<G> gather(@NotNull Function<AbstractInsnNode, @Nullable G> decoder) {
        return new GatherWalk<>(this.descriptor.with(Descriptor.Fold.of(true, (Function<AbstractInsnNode, Object>) decoder)));
    }

    /**
     * Attaches a single sticky cell: every yield the decoder answers non-null for latches,
     * last write winning, and is claimed. The cell empties at each commit unless retained.
     *
     * @param decoder the per-node decoder
     * @return the latching walk
     */
    @SuppressWarnings("unchecked")
    public <G> @NotNull LatchWalk<G> latch(@NotNull Function<AbstractInsnNode, @Nullable G> decoder) {
        return new LatchWalk<>(this.descriptor.with(Descriptor.Fold.of(false, (Function<AbstractInsnNode, Object>) decoder)));
    }

    // ------------------------------------------------------------------------------------
    // declared cells - the multi-cell fold
    // ------------------------------------------------------------------------------------

    /**
     * Attaches a declared cell. A cell with a decoder becomes a consume stage - a non-null
     * decode is pushed and claims the yield; a decoder-less cell is registered for the commit
     * reset without adding a stage.
     *
     * @param cell the cell to attach
     * @return the walk with the cell installed
     */
    public @NotNull AsmWalker feed(@NotNull Cells.Cell<?> cell) {
        return new AsmWalker(this.descriptor.with(new Descriptor.Feed(cell)));
    }

    /**
     * Attaches a recognizer hook - consume-on-hit, in declaration order. Index lookups,
     * per-hit diagnostics and cross-cell couplings live here; a hook writes no walk state
     * outside the chain's own cells.
     *
     * @param match the recognizer
     * @param hook the per-hit action
     * @return the walk with the hook installed
     */
    public <N extends AbstractInsnNode> @NotNull AsmWalker on(@NotNull Match<N> match, @NotNull Consumer<? super N> hook) {
        return new AsmWalker(this.descriptor.with(new Descriptor.On(match, node -> hook.accept(match.type().cast(node)))));
    }

    /**
     * Attaches a declared-cell commit: the action runs against the live cells, then the engine
     * clears every attached cell - or only the subset a following {@link #clearing} names.
     *
     * @param match the commit recognizer
     * @param action the commit action; it reads the cells and writes the site's own sinks
     * @return the walk with the commit installed
     */
    public <N extends AbstractInsnNode> @NotNull AsmWalker commitAt(@NotNull Match<N> match, @NotNull Consumer<? super N> action) {
        return new AsmWalker(this.descriptor.with(new Descriptor.Commit2(match, node -> action.accept(match.type().cast(node)), null)));
    }

    /**
     * Narrows the preceding commit's reset to the named cells - the declared deviation for a
     * site that deliberately leaves cells live across commits.
     *
     * @param subset the cells the commit clears; every other attached cell survives
     * @return the walk with the narrowed reset
     */
    public @NotNull AsmWalker clearing(Cells.Cell<?> @NotNull ... subset) {
        return new AsmWalker(this.descriptor.clearingLastCommit(List.of(subset)));
    }

    /**
     * Runs the walk for its effects and answers how it ended.
     *
     * @return how the walk ended
     */
    public @NotNull Exit run() {
        return Drive.run(this.descriptor, null, new Drive.Sink() {});
    }

    // ------------------------------------------------------------------------------------
    // interpreters and trace
    // ------------------------------------------------------------------------------------

    /**
     * Steps every yield through a linear interpreter. A bounded or filtered walk cannot drive
     * a machine - a bound firing mid-expression would leave it in an unspecified state, so the
     * terminal refuses the combination.
     *
     * @param machine the interpreter to drive
     * @return the walk with the machine installed
     */
    public @NotNull AsmWalker drive(@NotNull Interp<?> machine) {
        return new AsmWalker(this.descriptor.with(new Descriptor.DriveStage(machine)));
    }

    /**
     * Runs the walk under a cursor-returning advance hook. The identity visited set is always
     * armed: a revisited node answers {@link Exit#CYCLE} instead of looping, and a tracer that
     * jumps past an identity sentinel keeps walking - the sentinel binds at the cursor.
     *
     * @param body the advance hook
     * @return how the walk ended
     */
    public @NotNull Exit trace(@NotNull Tracer body) {
        return Drive.run(this.descriptor, body, new Drive.Sink() {});
    }

    /**
     * The find-shaped follower: probes each yield under a traced advance and answers the first
     * non-null probe result.
     *
     * @param probe the per-yield probe
     * @param advance the advance hook
     * @return the first non-null probe answer, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public <R> @Nullable R traceFirst(@NotNull Function<AbstractInsnNode, @Nullable R> probe, @NotNull Tracer advance) {
        Object[] capture = {null};
        Drive.run(this.descriptor, advance, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                R answer = probe.apply((AbstractInsnNode) value);
                if (answer == null) return Drive.Verdict.CONTINUE;
                capture[0] = answer;
                return Drive.Verdict.HALT;
            }
        });
        return (R) capture[0];
    }

    // ------------------------------------------------------------------------------------
    // decode statics - null-tolerant literal reads, shared by stages and probes
    // ------------------------------------------------------------------------------------

    /**
     * Decodes a {@code String} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the string constant, or {@code null}
     */
    public static @Nullable String stringLiteral(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        if (node.getOpcode() == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof String value)
            return value;
        return null;
    }

    /**
     * Decodes an {@code int} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed int constant, or {@code null}
     */
    public static @Nullable Integer intLiteral(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        int opcode = node.getOpcode();

        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
            return opcode - Opcodes.ICONST_0;

        if ((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && node instanceof IntInsnNode intInsn)
            return intInsn.operand;

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value)
            return value;

        return null;
    }

    /**
     * Decodes a {@code float} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed float constant, or {@code null}
     */
    public static @Nullable Float floatLiteral(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        int opcode = node.getOpcode();

        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2)
            return (float) (opcode - Opcodes.FCONST_0);

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Float value)
            return value;

        return null;
    }

    /**
     * Decodes a {@code long} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed long constant, or {@code null}
     */
    public static @Nullable Long longLiteral(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        int opcode = node.getOpcode();

        if (opcode == Opcodes.LCONST_0) return 0L;
        if (opcode == Opcodes.LCONST_1) return 1L;

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Long value)
            return value;

        return null;
    }

    /**
     * Decodes a {@code double} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed double constant, or {@code null}
     */
    public static @Nullable Double doubleLiteral(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        int opcode = node.getOpcode();

        if (opcode == Opcodes.DCONST_0) return 0.0;
        if (opcode == Opcodes.DCONST_1) return 1.0;

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Double value)
            return value;

        return null;
    }

    /**
     * Decodes a boolean literal push - {@code ICONST_0} / {@code ICONST_1} only, deliberately
     * narrower than {@link #intLiteral}: the other {@code ICONST} forms are not JVM boolean
     * literals.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed boolean, or {@code null}
     */
    public static @Nullable Boolean booleanLiteral(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        int opcode = node.getOpcode();
        if (opcode == Opcodes.ICONST_0) return Boolean.FALSE;
        if (opcode == Opcodes.ICONST_1) return Boolean.TRUE;
        return null;
    }

    /**
     * Decodes a {@code Class<?>} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the type constant, or {@code null}
     */
    public static @Nullable Type typeLiteral(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        if (node.getOpcode() == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Type value)
            return value;
        return null;
    }

    // ------------------------------------------------------------------------------------
    // one-hop statics - expressions, not walks
    // ------------------------------------------------------------------------------------

    /**
     * The next instruction with a real opcode, or {@code null} when none follows.
     *
     * @param node the starting instruction, or {@code null}
     * @return the next real instruction, or {@code null}
     */
    public static @Nullable AbstractInsnNode nextReal(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        for (AbstractInsnNode next = node.getNext(); next != null; next = next.getNext())
            if (next.getOpcode() >= 0) return next;
        return null;
    }

    /**
     * The previous instruction with a real opcode, or {@code null} when none precedes.
     *
     * @param node the starting instruction, or {@code null}
     * @return the previous real instruction, or {@code null}
     */
    public static @Nullable AbstractInsnNode previousReal(@Nullable AbstractInsnNode node) {
        if (node == null) return null;
        for (AbstractInsnNode prev = node.getPrevious(); prev != null; prev = prev.getPrevious())
            if (prev.getOpcode() >= 0) return prev;
        return null;
    }

    /**
     * Whether the node is a pseudo-instruction - a label, frame or line-number node with no
     * real opcode.
     *
     * @param node the instruction to test
     * @return {@code true} for a pseudo-instruction
     */
    public static boolean isPseudoNode(@NotNull AbstractInsnNode node) {
        return node.getOpcode() < 0;
    }

    // ------------------------------------------------------------------------------------
    // instruction predicates - expressions, not walks
    // ------------------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code node} is a {@link MethodInsnNode} with the given
     * opcode, owner, and name. Descriptor is ignored - use the descriptor-qualified overload
     * when overloads matter.
     *
     * @param node the instruction to test
     * @param opcode the expected JVM invoke opcode
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} matches
     */
    public static boolean isInvoke(@NotNull AbstractInsnNode node, int opcode, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == opcode
            && node instanceof MethodInsnNode methodInsn
            && methodInsn.owner.equals(owner)
            && methodInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@link MethodInsnNode} matching all four
     * fields (opcode, owner, name, descriptor) exactly.
     *
     * @param node the instruction to test
     * @param opcode the expected JVM invoke opcode
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when {@code node} matches
     */
    public static boolean isInvoke(@NotNull AbstractInsnNode node, int opcode, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return node.getOpcode() == opcode
            && node instanceof MethodInsnNode methodInsn
            && methodInsn.owner.equals(owner)
            && methodInsn.name.equals(name)
            && methodInsn.desc.equals(descriptor);
    }

    /**
     * Specialised {@link #isInvoke(AbstractInsnNode, int, String, String) isInvoke} for
     * {@code INVOKESTATIC}, ignoring descriptor.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} is the matching static invoke
     */
    public static boolean isInvokeStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return isInvoke(node, Opcodes.INVOKESTATIC, owner, name);
    }

    /**
     * Descriptor-qualified variant of {@link #isInvokeStatic(AbstractInsnNode, String, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @param descriptor the expected method descriptor
     * @return {@code true} when {@code node} is the matching static invoke
     */
    public static boolean isInvokeStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name, @NotNull String descriptor) {
        return isInvoke(node, Opcodes.INVOKESTATIC, owner, name, descriptor);
    }

    /**
     * Specialised {@link #isInvoke(AbstractInsnNode, int, String, String) isInvoke} for
     * {@code INVOKEVIRTUAL}, ignoring descriptor.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected method name
     * @return {@code true} when {@code node} is the matching virtual invoke
     */
    public static boolean isInvokeVirtual(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return isInvoke(node, Opcodes.INVOKEVIRTUAL, owner, name);
    }

    /**
     * Returns {@code true} when local-variable {@code slot} is a parameter of {@code method}
     * declared with the given reference type - the test that tells a value loaded by
     * {@code ALOAD slot} apart from a class-local constant. A renderer that takes its layer
     * type as a constructor parameter reads it this way, so the value has to be recovered from
     * the construction site rather than from the class itself.
     *
     * @param method the method owning the slot
     * @param slot the local-variable slot an {@code ALOAD} names
     * @param internalName the expected parameter type's JVM internal name
     * @return {@code true} when the slot is a parameter of that type
     */
    public static boolean isParameterOfType(@NotNull MethodNode method, int slot, @NotNull String internalName) {
        int current = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;   // instance methods hold `this` in slot 0
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            if (current == slot)
                return arg.getSort() == Type.OBJECT && arg.getInternalName().equals(internalName);
            current += arg.getSize();
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code node} is a {@code GETSTATIC} on the given owner class.
     * Field name is ignored - use the name-qualified overload to match a specific field.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @return {@code true} when {@code node} is a GETSTATIC on the given owner
     */
    public static boolean isGetStatic(@NotNull AbstractInsnNode node, @NotNull String owner) {
        return node.getOpcode() == Opcodes.GETSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner);
    }

    /**
     * Name-qualified variant of {@link #isGetStatic(AbstractInsnNode, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when {@code node} is a matching GETSTATIC
     */
    public static boolean isGetStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == Opcodes.GETSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner)
            && fieldInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code PUTSTATIC} on the given owner class.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @return {@code true} when {@code node} is a PUTSTATIC on the given owner
     */
    public static boolean isPutStatic(@NotNull AbstractInsnNode node, @NotNull String owner) {
        return node.getOpcode() == Opcodes.PUTSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner);
    }

    /**
     * Name-qualified variant of {@link #isPutStatic(AbstractInsnNode, String)}.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when {@code node} is a matching PUTSTATIC
     */
    public static boolean isPutStatic(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == Opcodes.PUTSTATIC
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner)
            && fieldInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code PUTFIELD} on the given owner class
     * and field name.
     *
     * @param node the instruction to test
     * @param owner the expected owner's JVM internal name
     * @param name the expected field name
     * @return {@code true} when {@code node} is a matching PUTFIELD
     */
    public static boolean isPutField(@NotNull AbstractInsnNode node, @NotNull String owner, @NotNull String name) {
        return node.getOpcode() == Opcodes.PUTFIELD
            && node instanceof FieldInsnNode fieldInsn
            && fieldInsn.owner.equals(owner)
            && fieldInsn.name.equals(name);
    }

    /**
     * Returns {@code true} when {@code node} is a {@code NEW} whose target type's internal
     * name starts with {@code internalNamePrefix}. Useful for "any subclass of X" matches
     * where only the package matters.
     *
     * @param node the instruction to test
     * @param internalNamePrefix the expected target-type internal-name prefix
     * @return {@code true} when {@code node} is a matching NEW
     */
    public static boolean isNewInstance(@NotNull AbstractInsnNode node, @NotNull String internalNamePrefix) {
        return node.getOpcode() == Opcodes.NEW
            && node instanceof TypeInsnNode typeInsn
            && typeInsn.desc.startsWith(internalNamePrefix);
    }

    /**
     * Returns {@code true} when {@code opcode} does not fall through to the next instruction
     * linearly - a conditional or unconditional jump ({@code IFEQ}..{@code IF_ACMPNE},
     * {@code GOTO}, {@code JSR}, {@code IFNULL}, {@code IFNONNULL}), a switch
     * ({@code TABLESWITCH}, {@code LOOKUPSWITCH}), or a method exit
     * ({@code RETURN} / {@code IRETURN} / {@code LRETURN} / {@code FRETURN} / {@code DRETURN} /
     * {@code ARETURN}, {@code ATHROW}). The exact opcode set a straight-line scan splits on
     * when it wants to stay inside a single basic block.
     *
     * @param opcode the JVM opcode
     * @return whether the opcode terminates a straight-line region
     */
    public static boolean isBranchInsn(int opcode) {
        if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE) return true;
        return opcode == Opcodes.GOTO
            || opcode == Opcodes.JSR
            || opcode == Opcodes.IFNULL
            || opcode == Opcodes.IFNONNULL
            || opcode == Opcodes.TABLESWITCH
            || opcode == Opcodes.LOOKUPSWITCH
            || opcode == Opcodes.RETURN
            || opcode == Opcodes.IRETURN
            || opcode == Opcodes.LRETURN
            || opcode == Opcodes.FRETURN
            || opcode == Opcodes.DRETURN
            || opcode == Opcodes.ARETURN
            || opcode == Opcodes.ATHROW;
    }

    // ------------------------------------------------------------------------------------
    // lambda metafactory decodes
    // ------------------------------------------------------------------------------------

    /**
     * The javac-synthesised static-lambda body prefix ({@code lambda$static$N}) - a
     * stable javac naming convention, NOT a JVM-spec guarantee: a compiler change would
     * surface as missed lambda bodies in the texture / variant-coat walks, so the single
     * declaration lives here.
     */
    public static final @NotNull String LAMBDA_STATIC_PREFIX = "lambda$static$";

    private static final @NotNull String LAMBDA_METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";
    private static final @NotNull String LAMBDA_METAFACTORY_METHOD = "metafactory";
    private static final @NotNull String LAMBDA_ALTMETAFACTORY_METHOD = "altMetafactory";

    /**
     * Returns {@code true} when {@code node} is an {@code INVOKEDYNAMIC} whose bootstrap
     * method is {@code LambdaMetafactory.metafactory} or {@code LambdaMetafactory.altMetafactory} -
     * the two bootstrap methods every {@code javac}-emitted lambda call site routes through.
     * Useful as a precondition before reaching for {@link #extractLambdaHandle} or
     * {@link #resolveLambdaTargetClass}.
     *
     * @param node the instruction to test
     * @return {@code true} when {@code node} is a lambda-metafactory INVOKEDYNAMIC
     */
    public static boolean isLambdaInvokeDynamic(@NotNull AbstractInsnNode node) {
        if (!(node instanceof InvokeDynamicInsnNode indy)) return false;
        Handle bsm = indy.bsm;
        return bsm != null
            && LAMBDA_METAFACTORY_OWNER.equals(bsm.getOwner())
            && (LAMBDA_METAFACTORY_METHOD.equals(bsm.getName()) || LAMBDA_ALTMETAFACTORY_METHOD.equals(bsm.getName()));
    }

    /**
     * Returns the primary target {@link Handle} of a {@code LambdaMetafactory}-built
     * {@code INVOKEDYNAMIC}. Vanilla {@code javac} stores it at {@code bsmArgs[1]} (after the
     * sam-method type at index 0). Returns {@code null} when the shape doesn't match.
     *
     * @param indy the instruction to inspect
     * @return the target handle, or {@code null} when not a lambda metafactory call or bsmArgs is malformed
     */
    public static @Nullable Handle extractLambdaHandle(@NotNull InvokeDynamicInsnNode indy) {
        if (indy.bsmArgs == null || indy.bsmArgs.length < 2) return null;
        if (!(indy.bsmArgs[1] instanceof Handle handle)) return null;
        return handle;
    }

    /**
     * Returns the first bootstrap-method argument of {@code indy} that is a {@link Handle} whose
     * {@link Handle#getName()} equals {@code name}, scanning {@code bsmArgs} in order; {@code null}
     * when none is present. Unlike {@link #extractLambdaHandle} (which reads the positional
     * {@code bsmArgs[1]} lambda target), this matches by handle name, so it suits call sites
     * hunting a specific method reference regardless of its argument position - e.g. a
     * {@code SomeClass::factory} reference carried as a bootstrap argument.
     *
     * @param indy the invokedynamic to inspect
     * @param name the target handle name
     * @return the first matching handle, or {@code null} when none is present
     */
    public static @Nullable Handle findBsmHandleByName(@NotNull InvokeDynamicInsnNode indy, @NotNull String name) {
        if (indy.bsmArgs == null) return null;
        for (Object arg : indy.bsmArgs)
            if (arg instanceof Handle handle && name.equals(handle.getName()))
                return handle;
        return null;
    }

    /**
     * Resolves a {@code LambdaMetafactory}-built {@code INVOKEDYNAMIC} to the JVM internal
     * name of the class the lambda produces. Two patterns are handled:
     * <ul>
     *   <li><b>{@code H_NEWINVOKESPECIAL}</b> - direct constructor reference
     *       ({@code Foo::new}). The lambda body is just {@code new Foo(...)} and the handle
     *       points at {@code Foo.<init>}; this returns {@code Foo}'s internal name.</li>
     *   <li><b>{@code H_INVOKESTATIC}</b> targeting {@code ownerClass} - synthetic lambda
     *       wrapper ({@code () -> new Foo(...)}). The handle points at a
     *       {@code lambda$static$N} method in the enclosing class; this loads that method
     *       and returns the internal name of the first {@code NEW} it executes.</li>
     * </ul>
     * Returns {@code null} for any other shape (different bootstrap, foreign-owner static
     * lambda, missing body, missing {@code NEW} in body).
     *
     * @param indy the instruction to resolve
     * @param ownerClass the class containing the indy (used to look up synthetic lambda bodies)
     * @return the target class's JVM internal name, or {@code null} when unresolved
     */
    public static @Nullable String resolveLambdaTargetClass(@NotNull InvokeDynamicInsnNode indy, @NotNull ClassNode ownerClass) {
        Handle handle = extractLambdaHandle(indy);
        if (handle == null) return null;
        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && AsmKit.INIT.equals(handle.getName()))
            return handle.getOwner();
        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            MethodNode lambda = AsmKit.findMethod(ownerClass, handle.getName(), handle.getDesc());
            if (lambda == null) return null;
            return AsmWalker.over(lambda)
                .firstNotNull(node -> node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW ? type.desc : null);
        }
        return null;
    }

    /**
     * Like {@link #resolveLambdaTargetClass} but also feeds every body node of a synthetic
     * {@code H_INVOKESTATIC} lambda to the supplied {@code visitor}. Callers that need to
     * collect side-channel data (e.g. {@code ModelLayers.X} GETSTATICs the lambda body
     * reads) can accumulate it inside the visitor while still receiving the first-{@code NEW}
     * target as the return value.
     *
     * <p>For {@code H_NEWINVOKESPECIAL} (direct constructor ref) there is no body to walk,
     * so the visitor is never invoked; this method just returns the constructor's owning
     * class.
     *
     * @param indy the instruction to resolve
     * @param ownerClass the class containing the indy
     * @param visitor invoked once per body node when {@code indy} is a static lambda whose owner matches {@code ownerClass}
     * @return the target class's JVM internal name, or {@code null} when unresolved
     */
    public static @Nullable String walkLambdaBody(
        @NotNull InvokeDynamicInsnNode indy,
        @NotNull ClassNode ownerClass,
        @NotNull Consumer<AbstractInsnNode> visitor
    ) {
        Handle handle = extractLambdaHandle(indy);
        if (handle == null) return null;
        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && AsmKit.INIT.equals(handle.getName()))
            return handle.getOwner();
        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            MethodNode lambda = AsmKit.findMethod(ownerClass, handle.getName(), handle.getDesc());
            if (lambda == null) return null;
            AsmWalker body = AsmWalker.over(lambda);
            body.forEach(visitor);
            return body.firstNotNull(node -> node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW ? type.desc : null);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // string concatenation (invokedynamic makeConcatWithConstants)
    // ------------------------------------------------------------------------------------

    /**
     * Internal name of {@code java.lang.invoke.StringConcatFactory}, the bootstrap host of
     * the {@code makeConcatWithConstants} indy used by javac for {@code String + X} concat.
     */
    public static final @NotNull String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";

    /**
     * Placeholder character javac embeds in the {@code makeConcatWithConstants} recipe at each
     * spot where a dynamic argument should be substituted. Defined by JEP 280 / JLS 15.18.1.
     */
    public static final char STRING_CONCAT_DYNAMIC_PLACEHOLDER = '\u0001';

    /**
     * Returns the recipe string of a {@code makeConcatWithConstants} invokedynamic, where the
     * placeholder character {@link #STRING_CONCAT_DYNAMIC_PLACEHOLDER} marks each dynamic-arg
     * substitution point. For {@code "tentacle" + i} javac emits an indy whose recipe is
     * {@code "tentacle"}; for {@code i + "_tentacle"} the recipe is
     * {@code "_tentacle"}. Returns {@code null} when the indy isn't a
     * {@code makeConcatWithConstants} call or {@code bsmArgs[0]} is missing / non-String.
     *
     * <p>Vanilla 26.1 procedural-loop factories use the indy directly inline
     * ({@code GhastModel.createBodyLayer} - {@code "tentacle" + i}) or wrapped in a helper that
     * encapsulates the concat ({@code SquidModel.createTentacleName(I)} -
     * {@code "tentacle" + i}; {@code BlazeModel.getPartName(I)} - {@code "part" + i}). The
     * helper case is resolved by walking the helper body for an inner invokedynamic that
     * matches this signature.
     *
     * @param indy the instruction to inspect
     * @return the recipe string, or {@code null} when the shape doesn't match
     */
    public static @Nullable String resolveStringConcatRecipe(@NotNull InvokeDynamicInsnNode indy) {
        if (!"makeConcatWithConstants".equals(indy.name)) return null;
        if (indy.bsm == null || !STRING_CONCAT_FACTORY.equals(indy.bsm.getOwner())) return null;
        if (indy.bsmArgs == null || indy.bsmArgs.length == 0) return null;
        return indy.bsmArgs[0] instanceof String recipe ? recipe : null;
    }

    /**
     * Walks {@code helper}'s instructions for the first {@code makeConcatWithConstants}
     * invokedynamic and returns its recipe (see {@link #resolveStringConcatRecipe}). Used by
     * the parser to follow {@code invokestatic <Owner>.<helper>(I)Ljava/lang/String;}
     * factories like {@code SquidModel.createTentacleName(I)} to their underlying recipe.
     *
     * @param helper the method whose body holds the indy
     * @return the recipe string, or {@code null} when no matching indy is present
     */
    public static @Nullable String findStringConcatRecipeIn(@NotNull MethodNode helper) {
        return AsmWalker.over(helper)
            .firstNotNull(node -> node instanceof InvokeDynamicInsnNode indy ? resolveStringConcatRecipe(indy) : null);
    }

    // ------------------------------------------------------------------------------------
    // static-table reads - shared walk shapes that absorbed N duplicated scanners
    // ------------------------------------------------------------------------------------

    /**
     * Walks the enum class's {@code <clinit>} for the canonical
     * {@code GETSTATIC <enum_value>; PUTSTATIC <defaultFieldName>} pair and returns the enum
     * value's declared name (uppercase, e.g. {@code "RED"} for
     * {@code MushroomCow$Variant.DEFAULT = RED}). Returns {@code null} when the class is
     * missing from the jar, has no {@code <clinit>}, or has no matching static field
     * initialised by the simple GETSTATIC-then-PUTSTATIC pattern. The field name is a
     * PARAMETER supplied by the calling policy - the walk stays vanilla-blind.
     *
     * <p>Used by texture / variant resolvers that need to recover the canonical zero-state
     * variant from an enum-typed render-state field. The match is owner-strict on both
     * GETSTATIC and PUTSTATIC sides so unrelated static-init reads in the same {@code <clinit>}
     * don't pollute the pending-field running state.
     *
     * @param classNodes the per-session cache to consult / populate
     * @param enumInternalName the variant enum class's JVM internal name
     * @param defaultFieldName the default-holding static field's name (policy-supplied)
     * @return the name of the enum constant the default field is initialised to,
     *     or {@code null} when no match
     */
    public static @Nullable String findEnumDefaultName(@NotNull ClassNodeCache classNodes, @NotNull String enumInternalName, @NotNull String defaultFieldName) {
        return AsmWalker.clinit(classNodes, enumInternalName)
            .latch(in -> in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && enumInternalName.equals(fi.owner) ? fi.name : null)
            .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC
                && enumInternalName.equals(fi.owner)
                && defaultFieldName.equals(fi.name))
            .firstNotNull(CommitWalk.Commit::value);
    }

    /**
     * Reads a static enum-keyed map's {@code <clinit>} construction into a
     * {@code Map<enum-constant-name, decoded value>} - the shape behind the legacy coat /
     * crackiness / markings / oxidation walks. Each map entry pushes its enum key
     * ({@code GETSTATIC <Enum>.<NAME>: L<Enum>;} - an enum-constant read is recognised by its
     * field descriptor matching its owner) followed by the value expression; the FIRST value
     * {@code valueReader} decodes after a key binds to it, and the walk commits at the
     * {@code PUTSTATIC <owner>.<mapField>} that stores the finished map. Returns entries in
     * {@code <clinit>} (enum-declaration) order; empty when the class, its {@code <clinit>},
     * or any binding is missing.
     *
     * @param cache the per-session cache to consult / populate
     * @param owner the class whose {@code <clinit>} builds the map
     * @param mapField the static field the finished map is stored into
     * @param valueReader decodes a candidate value from an instruction, or {@code null}
     * @return enum-constant name to decoded value, in encounter order
     */
    public static <V> @NotNull Map<String, V> readStaticEnumMap(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String mapField,
        @NotNull Function<AbstractInsnNode, @Nullable V> valueReader
    ) {
        return AsmWalker.clinit(cache, owner)
            .until(Insn.putStatic(owner, mapField))
            .latch(node -> node.getOpcode() == Opcodes.GETSTATIC
                && node instanceof FieldInsnNode field
                && field.desc.equals("L" + field.owner + ";") ? field.name : null)
            .commitOn(valueReader)
            .toMapFirstWins();
    }

    /**
     * Per-cache-instance memo for {@link #resolveStaticScalingFactor} (must permit
     * {@code null} values - "resolved to null" is distinct from "not yet walked").
     * Single-threaded per session by tooling convention.
     */
    private static final @NotNull Map<ClassNodeCache, Map<String, Float>> SCALING_MEMO = new WeakHashMap<>();

    /**
     * Resolves a {@code static final} field whose {@code <clinit>} initialiser is a literal
     * single-float factory call - {@code LDC F; INVOKESTATIC <factoryOwner>.<factoryMethod>(F)...;
     * PUTSTATIC <owner>.<field>:<fieldDesc>} - to that {@code F}. Walks the owning class's
     * {@code <clinit>} once and memoises <b>every</b> canonical field it encounters, keyed
     * {@code owner + "." + field}, so sibling fields on the same class resolve without a re-walk;
     * a non-canonical initialiser (indy-backed, arithmetic on {@code F}, compound) memoises
     * {@code null}. The memo's own {@code containsKey} distinguishes "resolved to null" from "not
     * yet walked" and short-circuits before {@link ClassNodeCache#load}, so a hit never touches
     * the jar.
     *
     * <p>Kept vanilla-agnostic: the caller supplies the factory owner / method and the field
     * descriptor (for the mesh-transformer walkers these are {@code MeshTransformer} /
     * {@code "scaling"} / {@code L...MeshTransformer;}). The factory is matched as
     * {@code "(F)" + fieldDesc} - a single {@code float} argument returning the field type.
     * {@link #SCALING_MEMO} is keyed per {@link ClassNodeCache} instance and lives here so the
     * cache stays storage-only; it is weakly keyed so a closed session's entries vanish with
     * its cache.
     *
     * <p>An intervening {@code LDC F} between a {@code scaling(F)} call and its {@code PUTSTATIC}
     * clears the pending scaled value, so a stale factor never binds to a later field. This is a
     * defensive stance: a looser reset would satisfy every shipped {@code <clinit>} equally well,
     * so the stricter reset is adopted unconditionally rather than tuned to the corpus.
     *
     * @param cache the per-session cache to consult / populate (also the memo key)
     * @param owner the field's owning class internal name (memo key + {@code PUTSTATIC} owner match)
     * @param fieldName the static field name being resolved
     * @param factoryOwner the scaling factory's owner internal name
     * @param factoryMethod the scaling factory's method name
     * @param fieldDesc the field's JVM descriptor (also the factory's return type)
     * @return the resolved factor, or {@code null} for a non-canonical / missing initialiser
     */
    public static @Nullable Float resolveStaticScalingFactor(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String fieldName,
        @NotNull String factoryOwner,
        @NotNull String factoryMethod,
        @NotNull String fieldDesc
    ) {
        Map<String, Float> memo = SCALING_MEMO.computeIfAbsent(cache, instance -> new LinkedHashMap<>());
        String key = owner + "." + fieldName;
        if (memo.containsKey(key)) return memo.get(key);

        AsmWalker clinit = AsmWalker.clinit(cache, owner);
        if (clinit.missing() != null) {
            memo.put(key, null);
            return null;
        }

        String factoryDesc = "(F)" + fieldDesc;
        // The strict latch clears on any unrelated instruction so a stale F never binds to a later
        // putstatic; the promoted factor survives no-op-ish instructions so the canonical
        // ldc / invokestatic / putstatic triplet still binds.
        clinit.real()
            .latch(in -> in instanceof LdcInsnNode ldc && ldc.cst instanceof Float f ? f : null)
            .strict()
            .takeAt(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && factoryOwner.equals(mi.owner)
                && factoryMethod.equals(mi.name)
                && factoryDesc.equals(mi.desc)))
            .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC
                && fieldDesc.equals(fi.desc)
                && fi.owner.equals(owner))
            .forEach(commit -> memo.put(owner + "." + commit.node().name, commit.value()));

        memo.putIfAbsent(key, null);
        return memo.get(key);
    }

    // ------------------------------------------------------------------------------------
    // integer for-loop detection
    // ------------------------------------------------------------------------------------

    /**
     * Description of a detected Java {@code for (int i = INIT; i < BOUND; i += STEP)} loop.
     *
     * @param iteratorSlot the JVM local-variable slot that holds the iterator
     * @param initValue the initial value pushed by the {@code ICONST_N} / {@code BIPUSH} /
     *     {@code SIPUSH} / {@code LDC} before the iterator's {@code ISTORE}
     * @param boundExclusive the {@code IF_ICMPGE} comparison value - the loop body executes
     *     while {@code i < boundExclusive}
     * @param step the iterator increment, captured from the body's {@code IINC <slot>, +step}.
     *     Positive for ascending loops; negative loops are not detected
     * @param firstBodyInsn the first real instruction inside the loop body (after the
     *     {@code IF_ICMPGE}); guaranteed non-pseudo
     * @param firstInsnAfterLoop the instruction that follows the loop's exit (the target of
     *     {@code IF_ICMPGE}, after skipping pseudo-instructions); guaranteed non-pseudo
     */
    public record IntForLoop(
        int iteratorSlot,
        int initValue,
        int boundExclusive,
        int step,
        @NotNull AbstractInsnNode firstBodyInsn,
        @NotNull AbstractInsnNode firstInsnAfterLoop
    ) {
        /**
         * Returns the number of times the body executes, or {@code 0} when {@code initValue >=
         * boundExclusive} or {@code step <= 0}.
         */
        public int iterations() {
            if (this.step <= 0 || this.initValue >= this.boundExclusive) return 0;
            return (this.boundExclusive - this.initValue + this.step - 1) / this.step;
        }
    }

    /**
     * Detects the canonical javac {@code for (int i = INIT; i < BOUND; i += STEP)} loop
     * starting at {@code candidateInit}. The expected bytecode shape:
     * <pre>
     *   {@code <numeric literal init>}  ICONST_N / BIPUSH / SIPUSH / LDC int  (candidateInit)
     *   ISTORE &lt;slot&gt;
     * test:
     *   ILOAD &lt;slot&gt;
     *   {@code <numeric literal bound>}
     *   IF_ICMPGE exit
     *   ... body ...
     *   IINC &lt;slot&gt;, +STEP
     *   GOTO test
     * exit:
     * </pre>
     *
     * <p>Returns {@code null} when any part of the pattern doesn't match - different opcode at
     * a slot, non-literal bound, IINC against a different slot, missing GOTO back to the test
     * label, etc. The walk is purely shape-matching: it doesn't validate that the body is
     * well-formed, only that the control-flow scaffold is present.
     *
     * <p>Only the standard test-at-top javac pattern is detected. Test-at-bottom layouts
     * ({@code init; GOTO test; body; INC; test: ILOAD; bound; IF_ICMPLT body}) and
     * non-monotonic loops ({@code i--}, {@code while}, {@code do-while}) return {@code null}.
     *
     * @param candidateInit the instruction to test as the loop's initial-value push
     * @return the loop description, or {@code null} when {@code candidateInit} doesn't open a
     *     for-loop with the expected shape
     */
    public static @Nullable IntForLoop detectIntForLoop(@NotNull AbstractInsnNode candidateInit) {
        if (isPseudoNode(candidateInit)) return null;
        Integer initValue = intLiteral(candidateInit);
        if (initValue == null) return null;

        AbstractInsnNode storeNode = nextReal(candidateInit);
        if (!(storeNode instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ISTORE) return null;
        int slot = store.var;

        // The test label is the first real instruction after the ISTORE.
        AbstractInsnNode testLoad = nextReal(storeNode);
        if (!(testLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD || load.var != slot) return null;

        AbstractInsnNode boundNode = nextReal(testLoad);
        if (boundNode == null) return null;
        Integer boundValue = intLiteral(boundNode);
        if (boundValue == null) return null;

        AbstractInsnNode cmpNode = nextReal(boundNode);
        if (!(cmpNode instanceof JumpInsnNode cmp) || cmp.getOpcode() != Opcodes.IF_ICMPGE) return null;
        LabelNode exitLabel = cmp.label;

        AbstractInsnNode bodyFirst = nextReal(cmpNode);
        if (bodyFirst == null) return null;

        // Walk forward from the body to find the IINC + GOTO that closes the loop. Stop at the
        // exit label or end-of-stream; abort if a different IINC slot or a non-GOTO-back-to-test
        // pattern shows up before then.
        IincInsnNode iinc = AsmWalker.from(bodyFirst).until(exitLabel).firstNotNull(cursor -> {
            if (!(cursor instanceof IincInsnNode candidateIinc) || candidateIinc.var != slot) return null;
            AbstractInsnNode after = nextReal(candidateIinc);
            if (!(after instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO || jump.label != testLoad.getPrevious() && !isLabelOf(jump.label, testLoad)) return null;
            return candidateIinc;
        });
        if (iinc == null) return null;

        AbstractInsnNode firstAfter = nextReal(exitLabel);
        if (firstAfter == null) return null;

        return new IntForLoop(slot, initValue, boundValue, iinc.incr, bodyFirst, firstAfter);
    }

    /**
     * Returns {@code true} when {@code label} is a label node whose immediately-following real
     * instruction is {@code target} - i.e. {@code label} marks the position of {@code target}.
     * Used by {@link #detectIntForLoop} to verify the closing {@code GOTO} jumps back to the
     * loop's test header.
     */
    private static boolean isLabelOf(@Nullable LabelNode label, @NotNull AbstractInsnNode target) {
        return AsmWalker.from(label).real().first() == target;
    }

}
