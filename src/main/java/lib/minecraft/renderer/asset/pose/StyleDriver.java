package lib.minecraft.renderer.asset.pose;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * One render-state field a style drives - the wave its value travels, and the range it travels
 * between.
 *
 * <p>A driver is the whole of how a style answers one field: a swept scalar rises from what it
 * rests at to what it reaches and returns, a cycling phase wraps at its period, a held one-hot
 * stands at its extent, and a ramp climbs with the tick itself. Whatever a style does not drive
 * rests, so an undriven field is answered with zero rather than by a driver of its own.
 *
 * <p><b>The swept and cycling arithmetic is the excursion arithmetic the reference set is rendered
 * against</b>, so its answers are bit-stable: a floor-mod phase over the period, a triangle or
 * wrapping travel, and the rest plus the travel's share of the range, every narrowing a
 * {@code float}.
 *
 * @param field the render-state name a shipped pose reads - {@code tentacleAngle},
 *     {@code croakAnimationState}, {@code walkAnimationPos}
 * @param wave how the value travels across one period
 * @param rest what the field holds at tick zero, and the near end of a swept travel
 * @param extent the far end of the travel - the whole of a held one-hot, and the per-tick slope of
 *     a ramp
 * @param group the exclusion group this driver's field belongs to, under which one row's driver
 *     stands in for another's; empty for an ungrouped field
 */
public record StyleDriver(
    @NotNull String field,
    @NotNull Wave wave,
    float rest,
    float extent,
    @NotNull Optional<String> group
) {

    /** How a driven value travels between what it rests at and what it reaches. */
    public enum Wave {

        /** Held at the extent - a selected one-hot. */
        HOLD,

        /** The tick times the extent - elapsed age, and the stride phase. */
        RAMP,

        /** Out and back, so a strip begins and ends at rest. */
        SWEEP,

        /** A phase, wrapping at its period. */
        CYCLE
    }

    /**
     * What this driver's field holds at one tick.
     *
     * @param tick the tick being posed
     * @param periodTicks the ticks one whole excursion spans
     * @return its value at that tick
     */
    public float at(int tick, int periodTicks) {
        return switch (this.wave) {
            case HOLD -> this.extent;
            case RAMP -> tick * this.extent;
            case SWEEP, CYCLE -> {
                float phase = Math.floorMod(tick, periodTicks) / (float) periodTicks;
                float travel = this.wave == Wave.CYCLE ? phase : 1f - Math.abs(2f * phase - 1f);
                yield this.rest + (this.extent - this.rest) * travel;
            }
        };
    }

}
