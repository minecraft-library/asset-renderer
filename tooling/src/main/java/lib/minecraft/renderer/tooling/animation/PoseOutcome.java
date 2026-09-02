package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

/**
 * What asking for one model's pose answered - the pose, or why there is not one.
 *
 * <p>Two arms rather than a pose-or-nothing, because a refusal has a reason and the reason is a
 * value. Left as a log line it could only be scraped back, which would make the shipped table a
 * function of a message's formatting; carried as a value it is simply written down.
 *
 * <p>An empty pose is {@link Extracted}, not {@link Refused}. A model whose whole chain declares
 * only the erased override poses nothing at all, and that is a fact about the model - the two have
 * to stay apart or a walk that failed reads as a subject that holds still.
 */
public sealed interface PoseOutcome {

    /**
     * A pose, however empty.
     *
     * @param program what the model poses
     */
    record Extracted(@NotNull PoseProgram program) implements PoseOutcome {}

    /**
     * No pose, and what stopped it.
     *
     * @param reason what the walk met, in its own words
     */
    record Refused(@NotNull String reason) implements PoseOutcome {}

}
