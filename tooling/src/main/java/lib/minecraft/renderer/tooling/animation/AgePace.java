package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

/**
 * One age's two walks - the amble a stride row drives and the fastest walk the subject reaches.
 *
 * @param wander the ambling pace the stride row drives
 * @param ceiling the fastest steady walk the subject's own behaviours reach, never under the wander
 */
public record AgePace(@NotNull WalkPace wander, @NotNull WalkPace ceiling) { }
