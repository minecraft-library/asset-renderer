package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One place a model applies a keyframe clip while posing itself.
 *
 * <p>The clip's own channels are not here - they are in the clip table, extracted once and shared by
 * every model that plays them. What is here is what the clip table cannot say: which clip, under
 * which of the three drives, and with what arguments.
 *
 * <p><b>The arguments are the reason this exists at all.</b> A walk-driven clip is applied as
 * {@code applyWalk(pos, speed, maxSpeed, scale)}, and the last two are the model's own constants -
 * how fast the clip runs against the walk, and how far it swings. They live in the model's bytecode
 * and nowhere in the clip, so a walk that treated a play site as nothing to record would drop the
 * timing and the amplitude while looking like it had lost nothing.
 *
 * <p>Reference arguments are absent rather than placeheld. The animation state a state-driven clip
 * is gated on is not a number and nothing offline evaluates it; the drive already says the clip sits
 * behind one, so carrying an unreadable operand would add no fact.
 *
 * @param clip the clip coordinate, in the same {@code Class#member} spelling the clip table is keyed by
 * @param drive what decides whether the clip contributes
 * @param arguments the numeric arguments the call passes, in declaration order
 */
public record PoseClipSite(
    @NotNull String clip,
    @NotNull Gate drive,
    @NotNull List<PoseExpr> arguments
) {

    /**
     * What decides whether a clip contributes.
     *
     * <p>The distinction is the whole reason a pose selector can be built out of these: a clip
     * behind a per-entity animation state contributes nothing to a render that starts no state,
     * which is every render this library takes.
     */
    public enum Gate {

        /** Held at its first frame unconditionally - the only clip kind that always contributes. */
        STATIC("static"),

        /** Driven by the walk inputs, so it contributes exactly as far as the subject is walking. */
        WALK("walk"),

        /**
         * Behind a running animation state - a roar, a dig, a sit. Nothing offline starts one, so
         * these are inert here and are carried to say so rather than to be played.
         */
        STATE("state");

        private final @NotNull String token;

        Gate(@NotNull String token) {
            this.token = token;
        }

        /**
         * The token this gate is spelled with in the shipped table.
         *
         * @return the lower-case token
         */
        public @NotNull String token() {
            return this.token;
        }

    }

}
