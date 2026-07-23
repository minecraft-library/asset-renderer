package lib.minecraft.refharness.api;

import java.util.Optional;

import net.minecraft.nbt.CompoundTag;

/**
 * What a rendered subject looks like, beyond being an entity of some type.
 *
 * <p>Each axis is expressed the way vanilla itself persists or exposes it, because that is what makes
 * a rendered subject indistinguishable from one the game produced. A coat that vanilla writes to NBT
 * is applied by reconstructing the entity through vanilla's own deserialiser; an age that vanilla
 * exposes as a setter is applied by calling it.
 *
 * @param coat the variant selection, or empty for the type's default appearance
 * @param baby whether the subject is aged down
 */
public record Appearance(Optional<Coat> coat, boolean baby) {

    /** The default appearance - the type as vanilla constructs it, with nothing selected. */
    public static final Appearance DEFAULT = new Appearance(Optional.empty(), false);

    /**
     * Returns this appearance aged down.
     *
     * @return the baby form
     */
    public Appearance asBaby() {
        return new Appearance(coat, true);
    }

    /**
     * Returns the appearance selecting one coat.
     *
     * @param coat the coat to select
     * @return the appearance
     */
    public static Appearance of(Coat coat) {
        return new Appearance(Optional.of(coat), false);
    }

    /**
     * Which cohort this appearance's canvas is sized within.
     *
     * <p>Subjects whose silhouettes are not comparable must not share a canvas: unioning a baby's
     * bounds into its family would grow every adult reference in that family, and unioning an adult's
     * into the babies would leave every baby floating in the middle of an adult-sized frame. Keying
     * the canvas by cohort as well as family keeps each group sized to itself, which is also what
     * makes adding a cohort provably unable to move an existing reference.
     *
     * @return the cohort
     */
    public Cohort cohort() {
        return baby ? Cohort.BABY : Cohort.DEFAULT;
    }

    /** The groups a family's canvases are measured within. */
    public enum Cohort {
        /** Adult subjects at their ordinary size - every reference emitted before the age axis. */
        DEFAULT,
        /** Aged-down subjects, whose mesh is a different shape rather than a smaller one. */
        BABY
    }

    /**
     * A variant selection, held as the NBT vanilla persists it under.
     *
     * <p>Three shapes exist and the difference is vanilla's, not this harness's: a data-driven variant
     * registry keys off a string, a coat enum packed into an integer keys off that integer, and an
     * enum persisted by name keys off its serialized name. All three reach the entity the same way -
     * through the deserialiser a world load runs - so the result is indistinguishable from a
     * server-spawned pick.
     *
     * @param key the NBT key vanilla reads the selection from
     * @param stringValue the string value, when the key holds one
     * @param intValue the integer value, when the key holds one
     * @param name the option name this coat is filed under
     */
    public record Coat(String key, Optional<String> stringValue, Optional<Integer> intValue, String name) {

        /**
         * Returns a coat vanilla persists as a string.
         *
         * @param key the NBT key
         * @param value the persisted value
         * @param name the option name to file it under
         * @return the coat
         */
        public static Coat ofString(String key, String value, String name) {
            return new Coat(key, Optional.of(value), Optional.empty(), name);
        }

        /**
         * Returns a coat vanilla persists as an integer.
         *
         * @param key the NBT key
         * @param value the persisted value
         * @param name the option name to file it under
         * @return the coat
         */
        public static Coat ofInt(String key, int value, String name) {
            return new Coat(key, Optional.empty(), Optional.of(value), name);
        }

        /**
         * Writes this coat into a fresh payload for vanilla's deserialiser.
         *
         * @return the payload
         */
        public CompoundTag toPayload() {
            CompoundTag nbt = new CompoundTag();
            stringValue.ifPresent(value -> nbt.putString(key, value));
            intValue.ifPresent(value -> nbt.putInt(key, value));
            return nbt;
        }
    }
}
