package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.List;

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
 *
 * <p>A reference the render state holds is the exception and is carried as itself: it is not a
 * number and never becomes one, but a body compares it and questions it, so losing it to
 * {@link Opaque} would lose which thing was compared or asked.
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
     * One constant of an enum, read off the class that declares it.
     *
     * @param type the enum's internal name
     * @param name the constant's own name
     */
    record EnumConstant(@NotNull String type, @NotNull String name) implements PoseValue {}

    /**
     * A named reference the render state holds - the enum saying which arm is swinging, the
     * animation state a clip is gated on, the stack an entity is carrying.
     *
     * <p>Held apart from {@link Num} because it is not a number and cannot be arithmetic on. A pose
     * body does exactly two things with one: compare it against a constant, or ask it a question
     * whose answer is a number. Answering those two is the whole of what this has to support, and
     * both answers name the member it was read from, because which reference was asked is as much
     * of the question as what was asked of it.
     *
     * <p>The member is a PATH rather than a single name, because reaching one of these takes more
     * than one hop: an accessor picking a stack out of a hand is named with the constant it was
     * asked for, and a component read off that stack appends the component's own declared name.
     * Every hop names something declared, so the path stays derived from the corpus rather than
     * invented, and two hands or two components stay two references.
     *
     * @param member the path through the render state this reference was reached by
     * @param type the reference's own internal name
     */
    record StateRef(@NotNull String member, @NotNull String type) implements PoseValue {}

    /**
     * A static field this walk names rather than models - a component key, a registry entry.
     *
     * <p>Apart from {@link EnumConstant}, which is the one static whose own declaration answers
     * questions about it. This one answers none: the whole of what a pose body does with it is hand
     * it to something as the name of what to fetch, so the name is all there is to carry.
     *
     * @param owner the internal name of the class declaring it
     * @param name the field's own name
     */
    record StaticRef(@NotNull String owner, @NotNull String name) implements PoseValue {}

    /**
     * A lambda a call site built, held as the body it will run and whatever it closed over.
     *
     * <p>The interface it was built against is not part of it. What matters at the call that applies
     * one is the body to enter and the operands to enter it with, and the interface method is only
     * how the operands get there - so the apply resolves from THIS value rather than from the
     * interface's own declaration, which no model descends from and which declares no body at all.
     *
     * @param owner the internal name of the class declaring the body
     * @param name the body's own method name
     * @param descriptor the body's descriptor
     * @param captured what the call site closed over, in the order it pushed them
     */
    record Lambda(
        @NotNull String owner,
        @NotNull String name,
        @NotNull String descriptor,
        @NotNull List<PoseValue> captured
    ) implements PoseValue {}

    /**
     * An array of numbers the render state holds, before an index picks an element out of it.
     *
     * <p>Apart from {@link StateRef} because it answers no question and matches no constant: the one
     * thing done with it is index it. Apart from {@link Num} for the same reason a bone array is -
     * an array is not the thing an expression wants, an element of it is.
     *
     * @param member the vanilla render-state member it was read from
     */
    record StateArray(@NotNull String member) implements PoseValue {}

    /**
     * The table javac hides beside a class so a {@code switch} over an enum can be a jump table.
     *
     * <p>Not a fact about the render state at all - it is a fact about a class file, mapping each
     * constant's position to the case number its switch uses, and it exists because the positions
     * themselves are not stable across a recompile of the enum. Carried so that indexing it answers
     * the case rather than an unknown.
     *
     * @param owner the internal name of the class holding the table
     * @param field the table's own field name
     */
    record SwitchMap(@NotNull String owner, @NotNull String field) implements PoseValue {}

    /**
     * A keyframe clip the model's constructor bound to one of its fields.
     *
     * @param coordinate the clip coordinate the clip table is keyed by
     */
    record Clip(@NotNull String coordinate) implements PoseValue {}

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
