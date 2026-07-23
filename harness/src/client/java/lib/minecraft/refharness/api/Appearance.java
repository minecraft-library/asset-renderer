package lib.minecraft.refharness.api;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a rendered subject looks like, beyond being an entity of some type.
 *
 * <p>Each axis is expressed the way vanilla itself persists or exposes it, because that is what makes
 * a rendered subject indistinguishable from one the game produced. A coat that vanilla writes to NBT
 * is applied by reconstructing the entity through vanilla's own deserialiser; an age that vanilla
 * exposes as a setter is applied by calling it.
 *
 * <p>The coat and the age are held as their own components rather than as traits because both are
 * older than the trait list and both are asked about directly - the coat names the reference, and the
 * age decides which mesh the measurement pass resolves. Everything else is a trait.
 *
 * @param coat the variant selection, or empty for the type's default appearance
 * @param baby whether the subject is aged down
 * @param traits the remaining selections, ordered so one appearance has exactly one spelling
 */
public record Appearance(Optional<Coat> coat, boolean baby, List<Trait> traits) {

    /** The default appearance - the type as vanilla constructs it, with nothing selected. */
    public static final Appearance DEFAULT = new Appearance(Optional.empty(), false, List.of());

    /**
     * Orders the traits by the text they are spelled with, so an appearance assembled in any order
     * has one canonical form - the same rule {@link RefKey} orders its tokens by.
     */
    public Appearance {
        List<Trait> ordered = new ArrayList<>(traits);
        ordered.sort((a, b) -> a.token().compareTo(b.token()));
        traits = List.copyOf(ordered);
    }

    /**
     * Returns this appearance aged down.
     *
     * @return the baby form
     */
    public Appearance asBaby() {
        return new Appearance(coat, true, traits);
    }

    /**
     * Returns this appearance carrying one more selection.
     *
     * @param trait the selection to add
     * @return the extended appearance
     */
    public Appearance with(Trait trait) {
        List<Trait> extended = new ArrayList<>(traits);
        extended.add(trait);
        return new Appearance(coat, baby, extended);
    }

    /**
     * Returns the appearance selecting one coat.
     *
     * @param coat the coat to select
     * @return the appearance
     */
    public static Appearance of(Coat coat) {
        return new Appearance(Optional.of(coat), false, List.of());
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
     * <p>Every trait earns its own cohort rather than only the ones expected to change the
     * silhouette. A trait that leaves the geometry alone lands the same canvas its cohort would have
     * had anyway, so the split costs one measurement and nothing else - while guessing "this one is
     * texture-only" wrongly crops the render against a canvas measured without it.
     *
     * @return the cohort
     */
    public Cohort cohort() {
        StringBuilder name = new StringBuilder(baby ? "baby" : "default");
        for (Trait trait : traits) name.append('+').append(trait.token());
        return new Cohort(name.toString());
    }

    /**
     * One appearance selection, as the reference name spells it.
     *
     * @param axis the axis being selected
     * @param value the option selected
     */
    public record Trait(String axis, String value) {

        /** The {@code axis=value} text this trait is named and ordered by. */
        public String token() {
            return axis + "=" + value;
        }
    }

    /**
     * A group of subjects whose silhouettes are measured together.
     *
     * @param name what the group is, spelled so two appearances selecting the same thing agree
     */
    public record Cohort(String name) {

        /** Adult subjects at their ordinary size - every reference emitted before the age axis. */
        public static final Cohort DEFAULT = new Cohort("default");

        /** Aged-down subjects, whose mesh is a different shape rather than a smaller one. */
        public static final Cohort BABY = new Cohort("baby");

        /**
         * The cohort this one is measured on top of, when the model is one whose canvas takes in its
         * siblings at all.
         *
         * <p>A trait cohort builds on the plain adults and a baby's trait cohort on the plain babies,
         * which is what keeps a selection's canvas at least as large as the subject it selects on.
         *
         * @return the cohort to seed from, or empty for a cohort that seeds from nothing
         */
        public Optional<Cohort> base() {
            if (equals(DEFAULT)) return Optional.empty();
            if (equals(BABY)) return Optional.of(DEFAULT);
            return Optional.of(name.startsWith("baby") ? BABY : DEFAULT);
        }
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
     * <p>A coat is a list of tags rather than one, because a panda's coat is two: vanilla renders the
     * gene only when the visible and the hidden one agree, so selecting a coat means writing both.
     *
     * @param name the option name this coat is filed under
     * @param tags the tags that select it, in the order they are written
     */
    public record Coat(String name, List<Tag> tags) {

        /** Canonicalises the tag list so a coat is immutable and comparable by value. */
        public Coat {
            tags = List.copyOf(tags);
        }

        /**
         * Returns a coat vanilla persists as a string.
         *
         * @param key the NBT key
         * @param value the persisted value
         * @param name the option name to file it under
         * @return the coat
         */
        public static Coat ofString(String key, String value, String name) {
            return new Coat(name, List.of(new Tag(key, Optional.of(value), Optional.empty())));
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
            return new Coat(name, List.of(new Tag(key, Optional.empty(), Optional.of(value))));
        }

        /**
         * Returns this coat writing one more string tag.
         *
         * @param key the NBT key
         * @param value the persisted value
         * @return the extended coat
         */
        public Coat and(String key, String value) {
            List<Tag> extended = new ArrayList<>(tags);
            extended.add(new Tag(key, Optional.of(value), Optional.empty()));
            return new Coat(name, extended);
        }

        /**
         * Writes this coat into a fresh payload for vanilla's deserialiser.
         *
         * @return the payload
         */
        public CompoundTag toPayload() {
            CompoundTag nbt = new CompoundTag();
            for (Tag tag : tags) {
                tag.stringValue().ifPresent(value -> nbt.putString(tag.key(), value));
                tag.intValue().ifPresent(value -> nbt.putInt(tag.key(), value));
            }
            return nbt;
        }

        /**
         * One persisted assignment.
         *
         * @param key the NBT key vanilla reads the selection from
         * @param stringValue the string value, when the key holds one
         * @param intValue the integer value, when the key holds one
         */
        public record Tag(String key, Optional<String> stringValue, Optional<Integer> intValue) {}
    }
}
