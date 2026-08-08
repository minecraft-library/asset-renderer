package lib.minecraft.renderer.asset.pack.rule;

import org.jetbrains.annotations.NotNull;

/**
 * The OptiFine CIT {@code damage=} filter - a list of accepted values or ranges, plus the two grammar
 * modifiers: the trailing {@code %} that reads the damage as a percentage of the item's max
 * durability, and {@code damageMask} which ANDs the raw damage before the range comparison.
 *
 * @param ranges the accepted damage values / ranges
 * @param percent whether the damage is compared as a percentage of max durability
 * @param mask the bitmask ANDed with the damage value before comparison, or {@code 0} for no mask
 */
public record DamageSpec(@NotNull IntRanges ranges, boolean percent, int mask) {

    /**
     * Whether an item's damage satisfies this filter. The mask (when non-zero) is applied first, then
     * the value is scaled to a {@code 0..100} percentage when {@link #percent} is set.
     *
     * @param damage the item's current damage value
     * @param maxDamage the item's max durability, used only for the percentage mode
     * @return {@code true} when the resolved value falls in one of the ranges
     */
    public boolean matches(int damage, int maxDamage) {
        int value = this.mask != 0 ? damage & this.mask : damage;
        int resolved = this.percent ? (maxDamage > 0 ? (int) (100L * value / maxDamage) : 0) : value;
        return this.ranges.contains(resolved);
    }

}
