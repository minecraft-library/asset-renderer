package lib.minecraft.renderer.tooling.policy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The result of a policy consultation - where to look, a declared fact, or nothing.
 *
 * <p>{@link At} and {@link AtResource} only tell the caller WHERE to look - they re-enter the
 * SAME generic engine at the given coordinate. {@link Dataflow} adds the ordered steps that engine
 * replays from there, drawn from a closed vocabulary. {@link Value} is terminal: a genuinely
 * undetectable fact whose hard-won comment travels as its mandatory provenance string.
 * {@link None} is the default row - generic detection stands.
 */
public sealed interface Navigation {

    /**
     * A bytecode coordinate to re-enter the generic engine at.
     *
     * @param owner the owning class's JVM internal name
     * @param member the member name at the coordinate
     * @param desc the member descriptor, or {@code null} when name alone identifies it
     */
    record At(@NotNull String owner, @NotNull String member, @Nullable String desc) implements Navigation {}

    /**
     * A jar-resource coordinate to re-enter the generic engine at (colormap PNGs, equipment
     * dirs - bytecode coordinates alone don't cover resources).
     *
     * @param entryPath the jar entry path
     */
    record AtResource(@NotNull String entryPath) implements Navigation {}

    /**
     * A bytecode coordinate plus the ordered steps an engine replays from it to recover the fact.
     *
     * <p>Distinct from {@link At}, which names a member one re-walk reproduces the fact at. A trace
     * crosses members - a getter body correlated to a constructor parameter slot, an array ordinal
     * chased from an index - and carries no recovered value, only the recipe, so the fact is
     * re-derived on every run. {@link Trace.Step} is the whole of what a trace may say, and a fact
     * needing a step it does not declare is not a candidate for this arm.
     *
     * @param owner the owning class's JVM internal name the replay anchors at
     * @param member the member name the replay enters at
     * @param trace the ordered steps the replay walks
     */
    record Dataflow(@NotNull String owner, @NotNull String member, @NotNull Trace trace) implements Navigation {}

    /**
     * A declared fact - the ONLY sanctioned hard-coding in tooling.
     *
     * @param value the declared value
     * @param provenance where the fact was won and why it is trusted - MANDATORY
     */
    record Value<T>(@NotNull T value, @NotNull String provenance) implements Navigation {}

    /**
     * No policy applies - generic detection stands.
     */
    record None() implements Navigation {}

}
