package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * The quadruped tier - the head-body-legs-tail vocabulary of four-legged walkers: head, body,
 * four corner legs and tail.
 *
 * <p>Its builder stamps leg pairs front and hind under the mirror sign rule (pitch kept, yaw
 * and roll negated) and drops the tail stance silently where a mesh carries none only under a
 * tolerant install. A whole roster the four names do not fit - a split tail, an eight-legged
 * crawler - belongs to the custom tier rather than a stretched vocabulary, while one part beside
 * them, like a wolf's mane, is reached by its mesh name through {@link PoseBuilder#bone}.
 *
 * <p>Every verb here names anatomy, so a stance lands on the articulation the shipped pose
 * turns for that part - an equine head is a cube under the neck assembly the pose turns as
 * one, and the head verb turns the assembly, snout and mane and ears with it.
 */
@Parity(subject = Subject.ENTITY)
public final class QuadrupedPose {

    private QuadrupedPose() {}

    /**
     * The quadruped builder - selectors and paired-leg stamps over the walker roster, sharing
     * the capture-then-compile tail of every tier.
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
         * Stances one corner leg.
         *
         * @param corner which leg
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder leg(@NotNull Corner corner, @NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance(legOf(corner), PoseScript.AimAxis.DOWN, true, stance);
            return this;
        }

        /**
         * Stances both forelegs in one stamp - the right as authored, the left derived under
         * the mirror sign rule, timeline values included.
         *
         * @param stance the stance lambda, run once for the right foreleg
         * @return this builder
         */
        public @NotNull Builder frontLegs(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.pair("right_front_leg", "left_front_leg", PoseScript.AimAxis.DOWN, stance);
            return this;
        }

        /**
         * Stances both hindlegs in one stamp - the right as authored, the left derived under
         * the mirror sign rule, timeline values included.
         *
         * @param stance the stance lambda, run once for the right hindleg
         * @return this builder
         */
        public @NotNull Builder hindLegs(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.pair("right_hind_leg", "left_hind_leg", PoseScript.AimAxis.DOWN, stance);
            return this;
        }

        /**
         * The mesh name of one corner's leg.
         */
        private static @NotNull String legOf(@NotNull Corner corner) {
            return switch (corner) {
                case FRONT_LEFT -> "left_front_leg";
                case FRONT_RIGHT -> "right_front_leg";
                case HIND_LEFT -> "left_hind_leg";
                case HIND_RIGHT -> "right_hind_leg";
            };
        }

    }

}
