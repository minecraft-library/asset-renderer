package lib.minecraft.renderer.tooling.policy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The ordered steps a {@link Navigation.Dataflow} replay walks, drawn from a closed vocabulary.
 *
 * <p>A replay holds two things - a CURSOR, the member it stands in and the instruction it reads
 * from, and a RUNNING VALUE - and each step reads one, writes one, or both, so a trace is a
 * pipeline rather than a script. The three {@code Read} steps and {@link Step.MapParamSlot} answer
 * the running value; the {@code Follow} steps move the cursor and leave the value standing; and
 * {@link Step.IndexArrayInit} is the one step that reads the value to decide where the cursor goes.
 * The value the last step leaves is what the replay recovers.
 *
 * <p>The vocabulary is a CEILING, not a starting point. {@link Step} is sealed over eight records
 * and declares no functional member anywhere, so arbitrary logic cannot enter as a step, and no
 * step carries a bytecode node, an instruction or an ASM type, so a policy declaring a whole trace
 * still names nothing of the fetch surface. A fact needing a step this hierarchy does not declare
 * is not a trace at all and stays a {@link Navigation.Value}.
 *
 * @param steps the steps in replay order, at least one
 */
public record Trace(@NotNull List<Step> steps) {

    /** Copies the step list, so a declared trace cannot be rewritten through the caller's reference. */
    public Trace {
        steps = List.copyOf(steps);
    }

    /**
     * Builds a trace from steps given in replay order.
     *
     * @param steps the steps in replay order
     * @return the trace over them
     */
    public static @NotNull Trace of(Step @NotNull ... steps) {
        return new Trace(List.of(steps));
    }

    /** One replay step - a cursor move, or a read at the cursor. */
    public sealed interface Step {

        /** A read of the first {@code int} literal at or after the cursor. */
        record ReadIntLiteral() implements Step {}

        /** A read of the first {@code String} literal at or after the cursor. */
        record ReadStringLiteral() implements Step {}

        /** A read of the first class literal at or after the cursor, as a JVM internal name. */
        record ReadTypeLiteral() implements Step {}

        /**
         * A hop from the entered member's read of a static field to that field's binding in its
         * owner's static initialiser. The read is what names the owning class, so a member that
         * reads no such field fails the replay rather than guessing an owner.
         *
         * @param name the static field's name
         */
        record FollowPutStatic(@NotNull String name) implements Step {}

        /**
         * A hop to an instance-field read in the entered member.
         *
         * @param name the field's name, or {@code null} to take the member's sole field read
         */
        record FollowGetField(@Nullable String name) implements Step {}

        /**
         * A hop to one element initialiser of the array construction the cursor's binding site
         * consumes, the running value naming the element's ordinal. The cursor lands on the first
         * instruction of that element's value expression, which a following read answers with.
         */
        record IndexArrayInit() implements Step {}

        /**
         * The constructor parameter index the field at the cursor is assigned from, read off the
         * variable load feeding the assignment and the constructor's own descriptor.
         */
        record MapParamSlot() implements Step {}

        /**
         * A hop into the callee of a named invoke at or after the cursor. The invoke is what names
         * the callee's owner, so a trace crosses classes without declaring a second coordinate.
         *
         * @param member the callee's name
         * @param desc the callee's descriptor, or {@code null} when the name alone identifies it
         */
        record FollowInvoke(@NotNull String member, @Nullable String desc) implements Step {}

    }

}
