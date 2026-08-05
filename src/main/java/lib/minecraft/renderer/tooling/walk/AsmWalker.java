package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The raw root of a walk - sources, geometry, fold attachment, trace, and the decode and
 * one-hop statics. Every instruction walk is a descriptor built here and compiled onto one
 * drive loop by its terminal; narrowing (a match stage, {@code where}, a projection) answers
 * {@link Walk}, on which no fold stage exists, so a stateful cell can never sit downstream of
 * a filter. One-hop neighbour reads ({@link #nextReal}, {@link #previousReal}) are expressions,
 * not walks, and stay statics.
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
        return node == null ? null : AsmKit.readStringLiteral(node);
    }

    /**
     * Decodes an {@code int} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed int constant, or {@code null}
     */
    public static @Nullable Integer intLiteral(@Nullable AbstractInsnNode node) {
        return node == null ? null : AsmKit.readIntLiteral(node);
    }

    /**
     * Decodes a {@code float} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed float constant, or {@code null}
     */
    public static @Nullable Float floatLiteral(@Nullable AbstractInsnNode node) {
        return node == null ? null : AsmKit.readFloatLiteral(node);
    }

    /**
     * Decodes a {@code long} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed long constant, or {@code null}
     */
    public static @Nullable Long longLiteral(@Nullable AbstractInsnNode node) {
        return node == null ? null : AsmKit.readLongLiteral(node);
    }

    /**
     * Decodes a {@code double} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the boxed double constant, or {@code null}
     */
    public static @Nullable Double doubleLiteral(@Nullable AbstractInsnNode node) {
        return node == null ? null : AsmKit.readDoubleLiteral(node);
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
        return node == null ? null : AsmKit.readBooleanLiteral(node);
    }

    /**
     * Decodes a {@code Class<?>} literal push, or {@code null} for any other node.
     *
     * @param node the instruction to decode, or {@code null}
     * @return the type constant, or {@code null}
     */
    public static @Nullable Type typeLiteral(@Nullable AbstractInsnNode node) {
        return node == null ? null : AsmKit.readTypeLiteral(node);
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
        return AsmKit.nextReal(node);
    }

    /**
     * The previous instruction with a real opcode, or {@code null} when none precedes.
     *
     * @param node the starting instruction, or {@code null}
     * @return the previous real instruction, or {@code null}
     */
    public static @Nullable AbstractInsnNode previousReal(@Nullable AbstractInsnNode node) {
        return AsmKit.previousReal(node);
    }

    /**
     * Whether the node is a pseudo-instruction - a label, frame or line-number node with no
     * real opcode.
     *
     * @param node the instruction to test
     * @return {@code true} for a pseudo-instruction
     */
    public static boolean isPseudoNode(@NotNull AbstractInsnNode node) {
        return AsmKit.isPseudoNode(node);
    }

}
