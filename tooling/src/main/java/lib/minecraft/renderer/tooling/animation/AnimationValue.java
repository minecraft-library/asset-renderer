package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The value model the keyframe-table interpreter runs over - one arm per thing a definition
 * class's {@code <clinit>} puts on the operand stack.
 *
 * <p>The tables are pure construction: a builder chain, three vector factories, an array of
 * keyframes and a pair of enum-like constants, with no arithmetic and no control flow anywhere.
 * So the arms are the shapes rather than a numeric lattice, and the only thing resembling a
 * computation is the unit conversion each vector factory applies, which is folded here at its
 * own precision rather than carried to the renderer.
 */
public sealed interface AnimationValue {

    /** A literal the interpreter decoded from an instruction. */
    record Lit(@NotNull Object value) implements AnimationValue {

        /**
         * Reads this literal as a float, widening an integer and narrowing a double the way the
         * call site's descriptor would.
         *
         * @return the literal as a float
         */
        public float asFloat() {
            return this.value instanceof Number number ? number.floatValue() : 0f;
        }

        /**
         * Reads this literal as a double, so a factory taking doubles folds at its own width.
         *
         * @return the literal as a double
         */
        public double asDouble() {
            return this.value instanceof Number number ? number.doubleValue() : 0d;
        }

        /**
         * Reads this literal as a string.
         *
         * @return the literal text, or {@code null} when it is not a string
         */
        public @Nullable String asText() {
            return this.value instanceof String text ? text : null;
        }

    }

    /**
     * A constant read off a {@code getstatic} - a channel target or an interpolation, named by
     * the field rather than resolved, because the two enums are the whole vocabulary and a name
     * outside it has to fail loudly rather than decode to a neighbour.
     *
     * @param owner the internal name of the declaring class
     * @param name the field name
     */
    record Sym(@NotNull String owner, @NotNull String name) implements AnimationValue {}

    /**
     * A vector one of the three {@code KeyframeAnimations} factories produced, already in the
     * units the target accumulates in.
     *
     * @param x the first component
     * @param y the second component
     * @param z the third component
     */
    record Vec(float x, float y, float z) implements AnimationValue {}

    /**
     * One keyframe.
     *
     * @param timeSeconds the keyframe's position along the clip
     * @param vector the value at that position
     * @param interpolation the curve reaching this keyframe from the one before it
     */
    record Frame(float timeSeconds, @NotNull Vec vector, @NotNull String interpolation) implements AnimationValue {}

    /**
     * A fixed-length keyframe array under construction, filled by {@code aastore} in index order.
     *
     * @param frames the slots, {@code null} until stored
     */
    record Arr(@NotNull List<@Nullable Frame> frames) implements AnimationValue {

        /**
         * Creates an array of a fixed length with every slot empty.
         *
         * @param length the declared length
         * @return the empty array value
         */
        static @NotNull Arr sized(int length) {
            List<Frame> slots = new ArrayList<>(Math.max(length, 0));
            for (int index = 0; index < length; index++) slots.add(null);
            return new Arr(slots);
        }

    }

    /**
     * One bone's channel - a target and the frames along it.
     *
     * @param target the accumulator the channel writes through
     * @param frames the keyframes in declaration order
     */
    record Chan(@NotNull String target, @NotNull List<Frame> frames) implements AnimationValue {}

    /**
     * A clip under construction. Mutable because the builder chain is a sequence of calls that
     * each answer the same builder, and modelling that as a fresh value per call would lose the
     * identity {@code addAnimation} relies on.
     */
    final class Clip implements AnimationValue {

        private float lengthSeconds;
        private boolean looping;
        private final @NotNull List<KeyframeClip.BoneChannel> channels = new ArrayList<>();

        Clip(float lengthSeconds) {
            this.lengthSeconds = lengthSeconds;
        }

        void markLooping() {
            this.looping = true;
        }

        void add(@NotNull String bone, @NotNull Chan channel) {
            this.channels.add(new KeyframeClip.BoneChannel(bone, channel.target(), List.copyOf(channel.frames())));
        }

        /**
         * Seals this clip under the name its {@code putstatic} gives it.
         *
         * @param owner the internal name of the declaring class
         * @param field the constant's field name
         * @return the finished clip
         */
        @NotNull KeyframeClip seal(@NotNull String owner, @NotNull String field) {
            return new KeyframeClip(owner, field, this.lengthSeconds, this.looping, List.copyOf(this.channels));
        }

    }

    /** The placeholder for anything the model does not describe, recognised by identity. */
    record Opaque() implements AnimationValue {}

    /** The uninitialised reference a {@code new} leaves for its constructor to replace. */
    record Uninit() implements AnimationValue {}

}
