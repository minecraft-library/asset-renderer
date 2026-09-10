package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Which limbs a stance asks a mesh for, resolved against the target row rather than typed.
 *
 * <p>The leg vocabulary is a naming convention and not an anatomy. A turtle's and an axolotl's
 * flippers are legs because their meshes call them legs, and a dolphin's identically shaped
 * {@code left_fin} is not. No geometric predicate separates the two, and none is attempted.
 *
 * <p>An indexed family is the same bargain with the anatomy dropped entirely: it claims only that
 * a mesh spells a set of bones as one word and a running number, which is what lets the count be
 * the mesh's answer. It says nothing about how those bones are arranged, because the meshes that
 * share a spelling do not share an arrangement - one mesh's {@code tail0} chain hangs each bone
 * off the one before it while another's are siblings, and a stance that suited either would be
 * wrong on the other.
 */
@Parity(subject = Subject.ENTITY)
public sealed interface LimbSelector permits LimbSelector.Legs, LimbSelector.Family {

    /**
     * How a diagnostics line names this selector.
     *
     * @return the reading, in the author's own terms
     */
    @NotNull String reading();

    /**
     * Rows of legs, addressed by where they sit on the body rather than by what a mesh calls them.
     *
     * @param rank which row front to back, or empty for every row
     * @param side which side, or empty for both
     * @param reach how far down each leg's own chain the stance carries
     * @param derived whether this half was mirrored from an authored one rather than authored
     */
    record Legs(@NotNull Optional<Rank> rank, @NotNull Optional<Side> side, @NotNull Reach reach,
                boolean derived) implements LimbSelector {

        /**
         * Constructs a selector reaching each addressed leg's root alone, authored rather than
         * derived.
         *
         * @param rank which row front to back, or empty for every row
         * @param side which side, or empty for both
         */
        public Legs(@NotNull Optional<Rank> rank, @NotNull Optional<Side> side) {
            this(rank, side, Reach.ROOT, false);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String reading() {
            String rows = this.rank.map(Enum::name).orElse("every row");
            String sides = this.side.map(Enum::name).orElse("both sides");
            return rows + " " + sides + " " + this.reach.name();
        }

    }

    /**
     * An indexed family - every bone a mesh spells as the stem alone or as the stem followed by a
     * number, in numeric order, however many of them it declares.
     *
     * <p>The number may follow the stem directly or across an underscore, so one stem reaches a
     * mesh counting from zero without a separator and one counting from one with it. A name the
     * stem only begins is no member: a stem of {@code tail} reaches {@code tail} and
     * {@code tail0}, and neither {@code tail_base} nor {@code tail_fin}.
     *
     * @param stem the name every member begins with
     */
    record Family(@NotNull String stem) implements LimbSelector {

        /** {@inheritDoc} */
        @Override
        public @NotNull String reading() {
            return this.stem + " family";
        }

    }

}
