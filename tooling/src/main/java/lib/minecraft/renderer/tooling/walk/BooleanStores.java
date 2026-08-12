package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Boolean-store decoding - turns the value expression before a {@code :Z} store into a
 * {@link BooleanStore} ({@link ConstantStore} / {@link FieldStore}) carrying a
 * {@link Polarity}, folding the {@code javac} compiled-{@code !flag} branch shape into one
 * {@code POSITIVE} / {@code NEGATIVE} discriminator.
 */
public final class BooleanStores {

    private BooleanStores() {}

    /**
     * Relationship between a decoded boolean r-value and the field it reads. {@link #POSITIVE}:
     * the value equals the field ({@code x = flag}). {@link #NEGATIVE}: the value is the field's
     * compiled negation ({@code x = !flag}), which javac emits as an
     * {@code IF..; ICONST; GOTO; ICONST} two-constant select.
     */
    public enum Polarity { POSITIVE, NEGATIVE }

    /**
     * A boolean r-value decoded from the instructions preceding a boolean store
     * ({@code PUTFIELD <...>:Z} or similar). Sealed over the shapes {@code javac} emits: a
     * compile-time literal ({@link ConstantStore}) or a read of an object's {@code :Z} field,
     * direct or compiled-negated ({@link FieldStore}). {@link #valueStart()} is the earliest
     * instruction of the value expression, so a caller reaches the store target with a single
     * {@code previousReal(store.valueStart())} - no re-derivation of the decoded shape. Purely
     * structural: it names no owner, field, or method as meaningful; the caller applies its own
     * semantic guards (which owner is the model, which flag name gates, and so on).
     */
    public sealed interface BooleanStore permits ConstantStore, FieldStore {

        /**
         * The lowest-address instruction of the decoded value expression - the node a caller
         * walks back from ({@code previousReal(valueStart())}) to reach the store target.
         *
         * @return the earliest instruction of the value expression
         */
        @NotNull AbstractInsnNode valueStart();
    }

    /**
     * A boolean r-value that is a compile-time literal ({@code ICONST_0} / {@code ICONST_1}).
     *
     * @param value the literal value ({@code false} = {@code ICONST_0}, {@code true} = {@code ICONST_1})
     * @param valueStart the {@code ICONST} node
     */
    public record ConstantStore(boolean value, @NotNull AbstractInsnNode valueStart) implements BooleanStore {}

    /**
     * A boolean r-value read from an object's {@code :Z} field: {@code <receiver>; GETFIELD f:Z}
     * for {@link Polarity#POSITIVE}, or that read wrapped in {@code javac}'s compiled-negation
     * select ({@code GETFIELD f:Z; IF{EQ,NE}; ICONST; GOTO; ICONST}) for {@link Polarity#NEGATIVE}.
     * A {@code NEGATIVE} result is returned only when the branch is a genuine boolean select whose
     * two constants form a distinct {@code 0}/{@code 1} pair; a non-{@code 0/1} or equal-constant
     * branch decodes to {@link ConstantStore} instead.
     *
     * @param field the {@code GETFIELD} reading the flag ({@code owner} / {@code name}, {@code desc == "Z"})
     * @param receiver the instruction pushing the flag's receiver (the node before {@code GETFIELD})
     * @param polarity {@code POSITIVE} for a direct read, {@code NEGATIVE} for the compiled {@code !flag} select
     * @param valueAtFieldFalse the boolean produced when the flag is {@code false} - always
     *     {@code false} for {@code POSITIVE}; the branch-resolved constant for {@code NEGATIVE}
     *     ({@code cond == IFNE ? fallConst : branchConst})
     * @param valueStart the receiver load (earliest instruction of the value expression)
     */
    public record FieldStore(
        @NotNull FieldInsnNode field,
        @NotNull AbstractInsnNode receiver,
        @NotNull Polarity polarity,
        boolean valueAtFieldFalse,
        @NotNull AbstractInsnNode valueStart
    ) implements BooleanStore {}

    /**
     * Decodes the boolean r-value produced by {@code valueInsn}, the real instruction
     * immediately preceding a boolean store ({@code previousReal(putfield)}; the caller has
     * already validated the {@code :Z} store). Recognises the three {@code javac} boolean-store
     * shapes:
     * <ul>
     *   <li>{@code ICONST_0/1} - {@link ConstantStore}</li>
     *   <li>{@code <recv>; GETFIELD f:Z} - {@link FieldStore} {@link Polarity#POSITIVE}</li>
     *   <li>{@code <recv>; GETFIELD f:Z; IF{EQ,NE}; ICONST; GOTO; ICONST} (compiled {@code !f},
     *       distinct {@code 0}/{@code 1} select) - {@link FieldStore} {@link Polarity#NEGATIVE}</li>
     * </ul>
     * Disambiguation of the trailing {@code ICONST}: when its {@code previousReal} is a
     * {@code GOTO} the {@code NEGATIVE} select is attempted; if that decode fails (constants not a
     * distinct {@code 0}/{@code 1} pair, missing {@code IF}, non-{@code :Z} field) the node falls
     * back to {@link ConstantStore}. Returns {@code null} when no shape matches.
     *
     * @param valueInsn the value-producing instruction immediately before a boolean store
     * @return the decoded store, or {@code null} when the shape is unrecognised
     */
    public static @Nullable BooleanStore decodeBooleanStore(@NotNull AbstractInsnNode valueInsn) {
        Boolean literal = AsmWalker.booleanLiteral(valueInsn);
        if (literal != null) {
            AbstractInsnNode prev = AsmWalker.previousReal(valueInsn);
            if (prev != null && prev.getOpcode() == Opcodes.GOTO) {
                FieldStore negated = decodeNegatedBranch(valueInsn, literal);
                if (negated != null) return negated;
            }
            return new ConstantStore(literal, valueInsn);
        }
        if (valueInsn.getOpcode() == Opcodes.GETFIELD
            && valueInsn instanceof FieldInsnNode field
            && "Z".equals(field.desc)) {
            AbstractInsnNode receiver = AsmWalker.previousReal(valueInsn);
            if (receiver == null) return null;
            return new FieldStore(field, receiver, Polarity.POSITIVE, false, receiver);
        }
        return null;
    }

    /**
     * Attempts to decode the compiled {@code !flag} select tail whose branch-target constant is
     * {@code branchConstNode} (the {@code ICONST} whose {@code previousReal} is the closing
     * {@code GOTO}). Reads backward {@code GOTO; ICONST(fall); IF{EQ,NE}; GETFIELD f:Z; <receiver>}
     * and returns the {@link FieldStore}, or {@code null} when the shape is not a genuine
     * {@code GETFIELD f:Z} negation with a distinct {@code 0}/{@code 1} constant pair.
     * {@code branchValue} is {@code branchConstNode}'s already-decoded boolean.
     */
    private static @Nullable FieldStore decodeNegatedBranch(@NotNull AbstractInsnNode branchConstNode, boolean branchValue) {
        AbstractInsnNode gotoInsn = AsmWalker.previousReal(branchConstNode);
        if (gotoInsn == null || gotoInsn.getOpcode() != Opcodes.GOTO) return null;
        AbstractInsnNode fallNode = AsmWalker.previousReal(gotoInsn);
        if (fallNode == null) return null;
        Boolean fallValue = AsmWalker.booleanLiteral(fallNode);
        if (fallValue == null || fallValue.booleanValue() == branchValue) return null;
        AbstractInsnNode condInsn = AsmWalker.previousReal(fallNode);
        if (condInsn == null) return null;
        int cond = condInsn.getOpcode();
        if (cond != Opcodes.IFEQ && cond != Opcodes.IFNE) return null;
        AbstractInsnNode fieldInsn = AsmWalker.previousReal(condInsn);
        if (fieldInsn == null
            || fieldInsn.getOpcode() != Opcodes.GETFIELD
            || !(fieldInsn instanceof FieldInsnNode field)
            || !"Z".equals(field.desc)) return null;
        AbstractInsnNode receiver = AsmWalker.previousReal(fieldInsn);
        if (receiver == null) return null;
        boolean valueAtFieldFalse = cond == Opcodes.IFNE ? fallValue : branchValue;
        return new FieldStore(field, receiver, Polarity.NEGATIVE, valueAtFieldFalse, receiver);
    }

}
