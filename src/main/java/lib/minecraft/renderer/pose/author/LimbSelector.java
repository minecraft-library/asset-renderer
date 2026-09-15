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
     * Whether an address stands on its own or is one side of a pair stamped together.
     *
     * <p>What turns on it is the row one bone paints whole. Such a row carries no side to name, so
     * an address naming one reaches it only where the address speaks for the row rather than for a
     * leg of it - which is the difference between stancing a row and stancing one of its legs, and
     * is not otherwise written down anywhere.
     */
    enum Stamp {

        /**
         * An address written on its own, reaching the legs it names and no others.
         */
        LONE,

        /**
         * The authored side of a pair, which speaks for its whole row where the row is one bone.
         */
        NEAR,

        /**
         * The side derived from the authored one, which is nothing where the row has no far side.
         */
        FAR

    }

    /**
     * Rows of legs, addressed by where they sit on the body rather than by what a mesh calls them.
     *
     * @param rank which row front to back, or empty for every row
     * @param side which side, or empty for both
     * @param reach how far down each leg's own chain the stance carries
     * @param stamp whether this address stands alone or is one side of a pair
     */
    record Legs(@NotNull Optional<Rank> rank, @NotNull Optional<Side> side, @NotNull Reach reach,
                @NotNull Stamp stamp) implements LimbSelector {

        /**
         * Constructs a selector reaching each addressed leg's root alone, standing on its own
         * rather than as one side of a pair.
         *
         * @param rank which row front to back, or empty for every row
         * @param side which side, or empty for both
         */
        public Legs(@NotNull Optional<Rank> rank, @NotNull Optional<Side> side) {
            this(rank, side, Reach.ROOT, Stamp.LONE);
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
