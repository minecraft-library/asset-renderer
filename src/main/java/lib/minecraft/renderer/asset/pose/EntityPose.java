package lib.minecraft.renderer.asset.pose;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one model does to its bones before it is drawn - an expression per channel it writes, and
 * the authored clips it plays.
 *
 * <p>One expression per channel and no ordering anywhere, because the order is already inside the
 * expressions. Vanilla resets every bone before posing any of them, so a channel read before it is
 * written reads the authored pose and one read after reads the write; substituting each read where
 * it happened leaves nothing for a sequence to say. A bone posed from another bone's freshly
 * written angle therefore carries that angle outright rather than a reference to it.
 *
 * <p><b>An empty pose and an unreadable one are different facts.</b> A model can genuinely pose
 * nothing - several inherit only the reset - and that is a subject which holds still. A model whose
 * pose could not be read holds still too, and is wrong. {@link #refusal} is what keeps the two
 * apart; anything that treats an absent pose as a still one has to consult it first.
 *
 * @param bones the expression each bone channel is written with, by bone name
 * @param clips the authored clips this model plays, in the order it plays them
 * @param refusal why there is no pose here, or empty when {@link #bones} is the whole answer
 */
public record EntityPose(
    @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones,
    @NotNull List<Clip> clips,
    @NotNull Optional<String> refusal
) {

    /** The pose of a model that poses nothing, which is a real answer rather than a missing one. */
    public static final @NotNull EntityPose NONE = new EntityPose(Map.of(), List.of(), Optional.empty());

    /**
     * One authored clip this model plays, and what it plays it at.
     *
     * <p>The arguments are the reason a play site is recorded beside the clip table rather than left
     * to it: how fast the thing moves and how far are the model's own constants and appear nowhere
     * in the clip, so two models playing one clip at two rates are indistinguishable without them.
     *
     * @param coordinate the clip coordinate, keyed the way the table's own clip index is
     * @param gate what drives the clip
     * @param arguments what the model plays it at, in declaration order
     */
    public record Clip(
        @NotNull String coordinate,
        @NotNull Gate gate,
        @NotNull List<PoseExpr> arguments
    ) {}

    /** What drives a clip, which decides what its own time axis is read from. */
    public enum Gate {

        /** Driven by the walk inputs, at the rate and amplitude the arguments carry. */
        WALK("walk"),

        /** Gated behind a named animation state, and carrying the tick it is played at. */
        STATE("state"),

        /** Held at its first frame, unconditionally. */
        STATIC("static");

        private final @NotNull String token;

        Gate(@NotNull String token) {
            this.token = token;
        }

        /**
         * The token this drive is spelled with in the shipped table.
         *
         * @return the lower-case token
         */
        public @NotNull String token() {
            return this.token;
        }

        /**
         * Resolves the drive a token names.
         *
         * @param token the token to resolve
         * @return the drive, or empty when no drive is spelled that way
         */
        public static @NotNull Optional<Gate> ofToken(@NotNull String token) {
            for (Gate gate : values())
                if (gate.token.equals(token)) return Optional.of(gate);
            return Optional.empty();
        }

    }

    /**
     * Whether this is a pose rather than a record of why there is not one.
     *
     * @return {@code true} when the bones and clips are the whole answer
     */
    public boolean isReadable() {
        return this.refusal.isEmpty();
    }

}
