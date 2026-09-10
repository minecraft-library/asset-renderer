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
 */
@Parity(subject = Subject.ENTITY)
public sealed interface LimbSelector permits LimbSelector.Legs {

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

}
