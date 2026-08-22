package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.mixin.FreezeAnimationStateMixin;
import lib.minecraft.refharness.sweep.EntityAnimationSweep;

/**
 * Holds the tick a harness render is posed at, so an animated reference is a function of the
 * schedule rather than of how long the client has been running.
 *
 * <p>Vanilla drives every idle animation off {@code EntityRenderState.ageInTicks}, which a live
 * render fills from the entity's own age and a partial tick. {@link FreezeAnimationStateMixin}
 * overwrites that field on every harness render and reads {@link #ageInTicks()} for what to put
 * there, so one place decides it: zero on a frozen run, and the tick
 * {@link EntityAnimationSweep} armed on an animated one.
 *
 * <p>The same holder pattern as {@link GlintClock} - the sweep arms it before each frame and the
 * mixin reads it during that frame - and {@code volatile} for the same reason, the value being
 * written where the sweep steps and read inside a separately compiled injection.
 *
 * <p><b>What varies with the tick is elapsed age and nothing else.</b> An offline subject stands
 * still, so every other animation driver the freeze pins stays pinned: it walks at no speed, swings
 * at nothing and has died no ticks ago. That is the frame the asset-renderer poses at too, so the
 * two sides read one schedule the same way.
 */
@UtilityClass
public final class AnimationClock {

    /** The tick the frame being rendered is posed at, armed before each render on an animated run. */
    public static volatile int tick;

    /**
     * Returns the elapsed age a render state is stamped with.
     *
     * @return the armed tick when this run animates, and zero when it freezes
     */
    public static float ageInTicks() {
        return HarnessConfig.ANIMATED ? tick : 0.0f;
    }
}
