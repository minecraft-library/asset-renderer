package lib.minecraft.renderer.tooling2.policy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The result of a policy consultation - where to look, a declared fact, or nothing
 * (SPINE 5.5 / decision 7).
 *
 * <p>{@link At} and {@link AtResource} only tell the caller WHERE to look - they re-enter the
 * SAME generic engine at the given coordinate. {@link Value} is terminal: a genuinely
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
     * A declared fact - the ONLY sanctioned hard-coding in tooling2 (decision 8; the SPINE
     * 2.1 roster is the complete inventory).
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
