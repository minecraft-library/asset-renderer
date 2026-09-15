package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The humanoid tier - the canonical seven-bone vocabulary of bipeds: head, hat, body, both arms
 * and both legs.
 *
 * <p>Its builder is the richest of the three tiers: paired-limb stamps and mirror verbs under
 * the mirror sign rule (pitch kept, yaw and roll negated), whole-silhouette {@link Preset}
 * stamps, and hat auto-mirroring - a {@code head} write lands on {@code hat} too unless
 * {@code hat} is authored itself, and the hat write drops silently on hatless meshes. Rosters
 * the seven names do not fit - a fused arm pair, a wing, an extra head shell - belong to the
 * custom tier rather than a stretched vocabulary, while one part beside the seven is reached by
 * its mesh name through {@link PoseBuilder#bone}.
 */
@Parity(subject = Subject.ENTITY)
public final class HumanoidPose {

    private HumanoidPose() {}

    /**
     * The humanoid builder - selectors, pair stamps, mirrors and presets over the canonical
     * seven, sharing the capture-then-compile tail of every tier.
     */
    public static final class Builder extends PoseBuilder<Builder> {

        /**
         * The limb pairs {@link #flip()} crosses, each direction its own entry.
         */
        private static final @NotNull Map<String, String> FLIP_PAIRS = Map.of(
            "right_arm", "left_arm", "left_arm", "right_arm",
            "right_leg", "left_leg", "left_leg", "right_leg"
        );

        Builder(@NotNull String styleId) {
            super(styleId);
        }

        /** {@inheritDoc} */
        @Override
        @NotNull Builder self() {
            return this;
        }

        /**
         * Stances the head - the write lands on the hat too, through the same captured values,
         * unless {@link #hat} is authored itself.
         *
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder head(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance("head", PoseScript.AimAxis.FACING, true, stance);
            return this;
        }

        /**
         * Stances the hat shell directly, claiming it from the head's auto-mirror.
         *
         * <p>A hat spelled here is an address the author wrote, so a mesh declaring no hat
         * records it as reaching nothing and a strict install refuses on it by name. The head's
         * automatic copy is the half that drops quietly, because nobody wrote it.
         *
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder hat(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance("hat", PoseScript.AimAxis.FACING, stance);
            return this;
        }

        /**
         * Stances the torso - the bone the mesh names {@code body}.
         *
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder torso(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance("body", PoseScript.AimAxis.DOWN, true, stance);
            return this;
        }

        /**
         * Stances one arm.
         *
         * @param side which arm
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder arm(@NotNull Side side, @NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance(armOf(side), PoseScript.AimAxis.DOWN, true, stance);
            return this;
        }

        /**
         * Stances one leg.
         *
         * @param side which leg
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder leg(@NotNull Side side, @NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance(legOf(side), PoseScript.AimAxis.DOWN, true, stance);
            return this;
        }

        /**
         * Stances both arms in one stamp - the right as authored, the left derived under the
         * mirror sign rule, timeline values included, so the pair moves as mirror images.
         *
         * @param stance the stance lambda, run once for the right arm
         * @return this builder
         */
        public @NotNull Builder arms(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.pair("right_arm", "left_arm", PoseScript.AimAxis.DOWN, stance);
            return this;
        }

        /**
         * Stances both legs in one stamp - the right as authored, the left derived under the
         * mirror sign rule, timeline values included.
         *
         * @param stance the stance lambda, run once for the right leg
         * @return this builder
         */
        public @NotNull Builder legs(@NotNull UnaryOperator<LimbStance> stance) {
            this.capture.pair("right_leg", "left_leg", PoseScript.AimAxis.DOWN, stance);
            return this;
        }

        /**
         * Copies the source arm's stances so far onto the other arm under the mirror sign
         * rule; the source arm is untouched, and later source stances do not follow.
         *
         * @param source the arm whose stances are copied
         * @return this builder
         */
        public @NotNull Builder mirrorArms(@NotNull Side source) {
            this.capture.mirror(armOf(source), armOf(opposite(source)));
            return this;
        }

        /**
         * Copies the source leg's stances so far onto the other leg under the mirror sign
         * rule; the source leg is untouched, and later source stances do not follow.
         *
         * @param source the leg whose stances are copied
         * @return this builder
         */
        public @NotNull Builder mirrorLegs(@NotNull Side source) {
            this.capture.mirror(legOf(source), legOf(opposite(source)));
            return this;
        }

        /**
         * Turns everything authored so far into its mirror image in one stroke - the arm and
         * leg pairs swap sides under the mirror sign rule, and the centred stances (head, hat,
         * torso and container steps) mirror in place; verbs after the flip are unaffected.
         *
         * @return this builder
         */
        public @NotNull Builder flip() {
            this.capture.flip(FLIP_PAIRS);
            return this;
        }

        /**
         * Stamps a whole silhouette - all six limb rotation triples land as absolute writes,
         * and every verb after the stamp adjusts one limb of it, a later absolute write on a
         * channel superseding the preset's.
         *
         * @param preset the silhouette to stamp
         * @return this builder
         */
        public @NotNull Builder preset(@NotNull Preset preset) {
            this.triple("head", PoseScript.AimAxis.FACING, preset.head());
            this.triple("body", PoseScript.AimAxis.DOWN, preset.body());
            this.triple("right_arm", PoseScript.AimAxis.DOWN, preset.rightArm());
            this.triple("left_arm", PoseScript.AimAxis.DOWN, preset.leftArm());
            this.triple("right_leg", PoseScript.AimAxis.DOWN, preset.rightLeg());
            this.triple("left_leg", PoseScript.AimAxis.DOWN, preset.leftLeg());
            return this;
        }

        /**
         * Stamps the head's captured stances onto the hat shell when no hat stance claimed
         * it, so the two move as one piece by default.
         *
         * @param capture the capture about to snapshot
         */
        @Override
        void finishCapture(@NotNull PoseScript.Capture capture) {
            if (!capture.stanced("hat"))
                capture.copy("head", "hat");
        }

        /**
         * The mesh name of one side's arm.
         */
        private static @NotNull String armOf(@NotNull Side side) {
            return side == Side.RIGHT ? "right_arm" : "left_arm";
        }

        /**
         * The mesh name of one side's leg.
         */
        private static @NotNull String legOf(@NotNull Side side) {
            return side == Side.RIGHT ? "right_leg" : "left_leg";
        }

        /**
         * The other side of a mirrored pair.
         */
        private static @NotNull Side opposite(@NotNull Side side) {
            return side == Side.RIGHT ? Side.LEFT : Side.RIGHT;
        }

        /**
         * Stamps one limb's absolute rotation triple.
         */
        private void triple(@NotNull String bone, @NotNull PoseScript.AimAxis axis, @NotNull Preset.Triple triple) {
            this.capture.stance(bone, axis, true, stance ->
                stance.rotate(triple.pitchDegrees(), triple.yawDegrees(), triple.rollDegrees()));
        }

    }

}
