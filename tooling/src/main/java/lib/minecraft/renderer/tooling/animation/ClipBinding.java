package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

/**
 * One clip a model plays, and the gate deciding when it plays.
 *
 * @param clip the clip coordinate, in the same {@code Class#member} spelling {@link KeyframeClip}
 *     is keyed by
 * @param gate what has to hold for the clip to contribute anything
 */
public record ClipBinding(@NotNull String clip, @NotNull Gate gate) {

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
