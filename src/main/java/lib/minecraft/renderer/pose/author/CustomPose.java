package lib.minecraft.renderer.pose.author;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import org.jetbrains.annotations.NotNull;

/**
 * The custom tier - raw bone names for every roster the humanoid and legged vocabularies do
 * not fit: a fused arm pair, a wing pair, an eight-legged crawler, a head shell inside a shell.
 *
 * <p>It names no anatomy at all - a chain here addresses every part through the shared
 * {@link PoseBuilder#bone} escape, as the mesh names it - and carries the one escape hatch of the
 * authoring surface besides: a raw expression graph replacing a channel whole under the style.
 */
@UtilityClass
@Parity(subject = Subject.ENTITY)
public final class CustomPose {

    /**
     * The custom builder - the raw expression hatch over the shared bone and tail verbs of every
     * tier.
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
         * Splices a raw expression graph over one channel - the escape hatch for shapes the
         * verbs cannot spell, like a stance gated on a live render-state field. The graph
         * rides by reference and replaces the channel whole at compile, under the compiler's
         * own interning and checks; sharing a node instance across expressions is how shared
         * subtrees are spelled, since a graph is never copied or expanded.
         *
         * <p>The splice is gated on the style, as every other lowering is: under any other style
         * of the row the channel answers what it held, and a live gate the graph spells for
         * itself composes with that one rather than replacing it.
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
