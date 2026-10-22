package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

/**
 * One steady walk as a stride row's walk pair drives it.
 *
 * <p>Vanilla's walk clock eases toward a target speed and settles on it, so a steady walk is two
 * numbers: how far the leg swing reaches and how fast the clock advances. A baby's clock runs
 * faster than its swing reaches - its walk feed scales the clock when the model reads it - so the
 * two are carried apart rather than one standing for both.
 *
 * @param speed the walkAnimationSpeed a model reads, never over 1 - the HOLD extent
 * @param rate how far walkAnimationPos advances a tick as the model reads it - the RAMP extent
 * @param basis the vanilla inputs the walk is worked out from, as the diagnostics line prints them
 */
public record WalkPace(float speed, float rate, @NotNull String basis) { }
