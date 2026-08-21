package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

/**
 * The value model the {@code setupAnim} interpreter runs over - one arm per thing a pose body puts
 * on the operand stack.
 *
 * <p>A pose body pushes two kinds of thing and the split is what makes the walk tractable: numbers,
 * which are symbolic because they depend on inputs nothing offline supplies, and references, which
 * are concrete because a bone is decided at construction. So an expression is carried where a
 * running client would carry a float, and a bone name is carried where it would carry a pointer.
 *
 * <p>Everything else is {@link Opaque}. That is not a failure on its own - a pose body loads
 * {@code this} and its render state constantly and never asks either of them anything a field
 * instruction has not already answered - but an opaque value reaching a sink is, because a bone
 * that cannot be named cannot be posed.
 */
public sealed interface PoseValue {

    /**
     * A number, held as the expression that computes it.
     *
     * @param expr what the number is
     */
    record Num(@NotNull PoseExpr expr) implements PoseValue {}

    /**
     * A resolved bone reference.
     *
     * @param bone the geometry bone name
     */
    record Part(@NotNull String bone) implements PoseValue {}

    /**
     * A reference to one of a model's arrays of bones, before an index picks an element out of it.
     *
     * @param field the model's array-field name
     */
    record PartArray(@NotNull String field) implements PoseValue {}

    /**
     * The result of a three-way compare that did not fold, holding what it was comparing.
     *
     * <p>A float test is two instructions in bytecode - the compare, then a jump on its sign - and
     * the jump is where the interesting question is asked. Carrying the operands across that gap is
     * what lets the branch say which two things it turned on; collapsing the compare to an unknown
     * would leave the branch knowing only that it could not decide.
     *
     * @param left the left operand
     * @param right the right operand
     */
    record Comparison(@NotNull PoseExpr left, @NotNull PoseExpr right) implements PoseValue {}

    /**
     * Anything the model does not describe - a receiver, a render state, an item stack. Recognised
     * by identity, so the interpreter can tell it from a value that merely happens to be unknown.
     */
    record Opaque() implements PoseValue {}

}
