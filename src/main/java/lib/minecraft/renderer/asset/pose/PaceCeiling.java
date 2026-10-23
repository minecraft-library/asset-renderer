package lib.minecraft.renderer.asset.pose;

/**
 * The fastest steady walk vanilla gives a subject at one age, as a stride's walk pair would drive it.
 *
 * @param speed the swing at that pace - the stride's {@code walkAnimationSpeed} hold extent, in (0, 1]
 * @param rate how far the walk clock advances a tick at that pace - the stride's
 *     {@code walkAnimationPos} ramp extent, above zero
 */
public record PaceCeiling(float speed, float rate) {

    /**
     * Constructs a new {@code PaceCeiling}, refusing a pace no walk pair holds.
     *
     * @throws IllegalArgumentException if either value is not finite, the speed lies outside (0, 1], or
     *     the rate is at most zero
     */
    public PaceCeiling {
        if (!(speed > 0f && speed <= 1f) || !(rate > 0f && Float.isFinite(rate))) {
            throw new IllegalArgumentException(String.format(
                "Ceiling swings '%s' and advances '%s' a tick, outside (0, 1] and above zero", speed, rate));
        }
    }

}
