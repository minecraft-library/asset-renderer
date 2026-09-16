package lib.minecraft.renderer.tooling.animation;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PosePredicate;

import lib.minecraft.renderer.pose.PoseOperator;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

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
     * A single-precision literal.
     *
     * @param value the value
     * @return the literal
     */
    static @NotNull PoseExpr.Const constant(float value) {
        return new PoseExpr.Const(value, PoseOperator.Width.FLOAT);
    }

    /**
     * A double-precision literal.
     *
     * @param value the value
     * @return the literal
     */
    static @NotNull PoseExpr.Const constant(double value) {
        return new PoseExpr.Const(value, PoseOperator.Width.DOUBLE);
    }

    /**
     * An integral literal.
     *
     * @param value the value
     * @return the literal
     */
    static @NotNull PoseExpr.Const constant(int value) {
        return new PoseExpr.Const(value, PoseOperator.Width.INT);
    }

    /**
     * Builds an operation, folding it where every operand is already a literal.
     *
     * <p>The fold calls the same method the renderer will, on the same values, so folding here and
     * evaluating there answer the same bits. That is the only reason folding is safe at all - an
     * algebraically equal shortcut would not be.
     *
     * <p>It is built HERE rather than on the arm it builds, and that is the whole reason this lives
     * beside the walk instead of beside the shape. A reader must not fold: the shared table's
     * numbering describes the graph that was emitted, so a reader collapsing an operation would
     * resolve a reference to a node the table does not describe. Folding is what the walk does on
     * the way to a table, never what anyone does on the way back from one.
     *
     * @param operator what is applied
     * @param operands the operands, in declaration order
     * @return the folded literal, or the unfolded operation
     * @throws IllegalArgumentException if the operand count is not the operator's arity
     */
    static @NotNull PoseExpr operation(@NotNull PoseOperator operator, @NotNull List<PoseExpr> operands) {
        if (operands.size() != operator.arity())
            throw new IllegalArgumentException(
                "'" + operator.token() + "' takes " + operator.arity() + " operand(s), got " + operands.size());

        double[] values = new double[operands.size()];
        for (int index = 0; index < values.length; index++) {
            if (!(operands.get(index) instanceof PoseExpr.Const literal))
                return new PoseExpr.Op(operator, Concurrent.newUnmodifiableList(operands));
            values[index] = literal.value();
        }
        return new PoseExpr.Const(operator.apply(values), operator.width());
    }

    /**
     * Builds an operation from operands given inline.
     *
     * @param operator what is applied
     * @param operands the operands, in declaration order
     * @return the folded literal, or the unfolded operation
     */
    static @NotNull PoseExpr operation(@NotNull PoseOperator operator, @NotNull PoseExpr @NotNull ... operands) {
        return operation(operator, List.of(operands));
    }

    /**
     * Builds a comparison, deciding it where both operands are already literals.
     *
     * @param comparison how the two are compared
     * @param left the left operand
     * @param right the right operand
     * @return the decided constant, or the undecided comparison
     */
    static @NotNull PosePredicate comparing(
        @NotNull PosePredicate.Comparison comparison, @NotNull PoseExpr left, @NotNull PoseExpr right) {

        if (left instanceof PoseExpr.Const lhs && right instanceof PoseExpr.Const rhs)
            return settled(comparison.test(lhs.value(), rhs.value()));
        return new PosePredicate(comparison, left, right);
    }

    /**
     * A predicate that is already decided, spelled as the comparison that says so.
     *
     * <p>A shipped predicate compares two numbers and has no arm for an answer already known, so a
     * decision is carried as a comparison of one literal against itself - equal for true, unequal for
     * false. The reader evaluates it correctly without knowing it was a decision, and the fold reads
     * it back through {@link #answered}.
     *
     * @param value what the predicate answers
     * @return the predicate answering it
     */
    static @NotNull PosePredicate settled(boolean value) {
        return new PosePredicate(
            value ? PosePredicate.Comparison.EQ : PosePredicate.Comparison.NE, constant(0f), constant(0f));
    }

    /**
     * What a predicate answers where both its operands are already literals.
     *
     * @param predicate the predicate
     * @return the answer, or empty where the tick still reaches one of the operands
     */
    static @NotNull Optional<Boolean> answered(@NotNull PosePredicate predicate) {
        if (predicate.left() instanceof PoseExpr.Const left
            && predicate.right() instanceof PoseExpr.Const right)
            return Optional.of(predicate.comparison().test(left.value(), right.value()));
        return Optional.empty();
    }

    /**
     * A figure read as the condition it stands for, which is how a boolean reaches a comparison.
     *
     * <p>A question about which constant a member rests at, or about whether a reference is there,
     * answers as a number. Nothing compares it to anything else, so it is compared against zero the
     * way a render-state boolean is - which is what keeps one grammar rather than two.
     *
     * @param expr the figure
     * @return the condition it holds where the figure is not zero
     */
    static @NotNull PosePredicate truthy(@NotNull PoseExpr expr) {
        return comparing(PosePredicate.Comparison.NE, expr, constant(0f));
    }

    /**
     * The negation of a predicate, taken on the comparison rather than wrapped around it.
     *
     * <p>Every comparison has its complement in the same roster - equal against unequal, less against
     * greater-or-equal, less-or-equal against greater - so a negation is a different comparison of the
     * same two operands and never a shape of its own.
     *
     * @param predicate what is negated
     * @return the negated predicate
     */
    static @NotNull PosePredicate negating(@NotNull PosePredicate predicate) {
        PosePredicate.Comparison flipped = switch (predicate.comparison()) {
            case EQ -> PosePredicate.Comparison.NE;
            case NE -> PosePredicate.Comparison.EQ;
            case LT -> PosePredicate.Comparison.GE;
            case GE -> PosePredicate.Comparison.LT;
            case LE -> PosePredicate.Comparison.GT;
            case GT -> PosePredicate.Comparison.LE;
        };
        return new PosePredicate(flipped, predicate.left(), predicate.right());
    }

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
     * Three numbers a pose body computes with as one thing.
     *
     * <p>The one value type a pose body allocates. It is carried whole rather than as three numbers
     * because a body hands it about, asks it for its own arithmetic and reads components back off
     * it - so what it is has to survive the round trip, and the components are what an expression
     * eventually wants rather than what the body is holding.
     *
     * @param x the first component
     * @param y the second component
     * @param z the third component
     */
    record Vector(@NotNull PoseExpr x, @NotNull PoseExpr y, @NotNull PoseExpr z) implements PoseValue {}

    /**
     * A place an object has been made but not yet built, between the allocation and its constructor.
     *
     * <p>Vanilla's own shape for building one is to allocate, duplicate the reference, push the
     * arguments and call the constructor - which consumes ONE of the two references and leaves the
     * other standing as the finished object. So the reference has to be recognisable in every place
     * it reached, and the {@code at} is what makes two of them apart: a body that allocates while
     * already holding an unbuilt one would otherwise finish the wrong one.
     *
     * @param type the internal name of what is being built
     * @param at which allocation of this walk it is
     */
    record Fresh(@NotNull String type, int at) implements PoseValue {}

    /**
     * The container a model is built around, for a mesh that flattened it away.
     *
     * <p>Apart from {@link Part} because it is not a bone and never becomes one: the mesh names its
     * children at top level and names it nowhere, so a channel written through it belongs to all of
     * them rather than to any. Carried so that the write can be read as the move it is - what it
     * shifts its children by is exactly what each of them is owed.
     */
    record MeshRoot() implements PoseValue {}

    /**
     * Anything the model does not describe - a receiver, a render state, an item stack. Recognised
     * by identity, so the interpreter can tell it from a value that merely happens to be unknown.
     */
    record Opaque() implements PoseValue {}

}
