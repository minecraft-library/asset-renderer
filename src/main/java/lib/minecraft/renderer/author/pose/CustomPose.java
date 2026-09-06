package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * The custom tier - raw bone names for every roster the humanoid and quadruped vocabularies do
 * not fit: a fused arm pair, a wing pair, an eight-legged crawler, a head shell inside a shell.
 *
 * <p>Its builder addresses bones as the mesh names them and carries the one escape hatch of the
 * authoring surface: a raw expression graph replacing a channel whole. Every {@code bone}
 * stance carries the limb-length aim convention - an aim solve treats the bone as resting
 * pointing down its length and takes the quarter-turn pitch offset; a bone that aims its facing
 * direction wants the humanoid or quadruped head selector instead.
 */
@Parity(subject = Subject.ENTITY)
public final class CustomPose {

    private CustomPose() {}

    /**
     * The custom builder - raw bone stances and the raw expression hatch, sharing the
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
         * Stances one bone by its mesh name, under the limb-length aim convention.
         *
         * @param bone the bone name, as the mesh names it
         * @param stance the stance lambda
         * @return this builder
         */
        public @NotNull Builder bone(@NotNull String bone, @NotNull UnaryOperator<LimbStance> stance) {
            this.capture.stance(bone, PoseScript.AimAxis.DOWN, stance);
            return this;
        }

        /**
         * Splices a raw expression graph over one channel - the escape hatch for shapes the
         * verbs cannot spell, like a stance gated on a live render-state field. The graph
         * rides by reference and replaces the channel whole at compile, under the compiler's
         * own interning and checks; sharing a node instance across expressions is how shared
         * subtrees are spelled, since a graph is never copied or expanded.
         *
         * @param bone the bone the expression writes
         * @param channel the channel it replaces
         * @param raw the expression graph
         * @return this builder
         */
        public @NotNull Builder expr(@NotNull String bone, @NotNull PoseChannel channel, @NotNull PoseExpr raw) {
            this.capture.raw(bone, channel, raw);
            return this;
        }

    }

}
