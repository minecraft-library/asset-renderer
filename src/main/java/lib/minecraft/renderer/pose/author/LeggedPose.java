package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The legged tier - the head-body-legs-tail vocabulary of walkers: head, body, legs addressed by
 * the row they sit in and the side they sit on, and tail.
 *
 * <p>Its builder stamps both legs of a row in one call under the mirror sign rule (pitch kept, yaw
 * and roll negated) and drops the tail stance silently where a mesh carries none only under a
 * tolerant install. A whole roster this vocabulary does not fit - a split tail, an eight-legged
 * crawler - belongs to the custom tier rather than a stretched one, while one part beside it,
 * like a wolf's mane, is reached by its mesh name through {@link PoseBuilder#bone}.
 *
 * <p>Every verb here names anatomy, so a stance lands on the articulation the shipped pose
 * turns for that part - an equine head is a cube under the neck assembly the pose turns as
 * one, and the head verb turns the assembly, snout and mane and ears with it.
 */
@Parity(subject = Subject.ENTITY)
public final class LeggedPose {

    private LeggedPose() {}

    /**
     * The legged builder - selectors and paired-leg stamps over the walker roster, sharing the
     * capture-then-compile tail of every tier.
     */
    public static final class Builder extends PoseBuilder<Builder> {

        Builder(@NotNull String styleId) {
            super(styleId);
        }

        /** {@inheritDoc} */
        @Override
        @NotNull Builder self() {
            return this;
        }

        /**
         * Stances the head.
         *
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder head(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance("head", PoseScript.AimAxis.FACING, true, stance);
            return this;
        }

        /**
         * Stances the body.
         *
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder body(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance("body", PoseScript.AimAxis.DOWN, true, stance);
            return this;
        }

        /**
         * Stances the tail; a strict install refuses where a mesh names no {@code tail}, a
         * tolerant one drops the stance and poses the rest.
         *
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder tail(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance("tail", PoseScript.AimAxis.DOWN, true, stance);
            return this;
        }

        /**
         * Stances one leg, addressed by its row and its side.
         *
         * @param rank which row front to back
         * @param side which side of that row
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder leg(@NotNull Rank rank, @NotNull Side side,
                                    @NotNull UnaryOperator<LimbStance> stance) {
            this.capture.selected(
                new LimbSelector.Legs(Optional.of(rank), Optional.of(side)),
                PoseScript.AimAxis.DOWN, true, Mirror.SIGNED, stance);
            return this;
        }

        /**
         * Stances both legs of one row in one stamp - the right as authored, the left derived
         * under the mirror sign rule, timeline values included.
         *
         * @param rank which row front to back
         * @param stance the stance lambda, run once for the right leg of the row
         * @return this builder
         */
        public @NotNull Builder legs(@NotNull Rank rank, @NotNull UnaryOperator<LimbStance> stance) {
            this.capture.selectedPair(
                new LimbSelector.Legs(Optional.of(rank), Optional.of(Side.RIGHT)),
                new LimbSelector.Legs(Optional.of(rank), Optional.of(Side.LEFT), Reach.ROOT, true),
                PoseScript.AimAxis.DOWN, Mirror.SIGNED, stance);
            return this;
        }

        /**
         * Walks every leg the mesh carries through one cycle.
         *
         * <p>A gait states its shape once and the rows it lands on are the mesh's answer, so one
         * chain walks a two-legged strider, a four-legged wolf and an eight-legged crawler.
         *
         * @param gait the cycle lambda
         * @return this builder
         */
        public @NotNull Builder gait(@NotNull UnaryOperator<Gait> gait) {
            Gait cycle = new Gait();
            gait.apply(cycle);
            cycle.captured(this.capture);
            return this;
        }

    }

}
