package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

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
 * <p><b>The container is a parent transform, not a bone.</b> A model whose mesh was built around a
 * container poses that container, and the mesh flattens it away and names it nowhere - so it is
 * carried apart from the bones and composes above every bone the mesh holds at top level. It starts
 * at rest rather than at an authored pose, the flattening having already put whatever it held into
 * the bones below it.
 *
 * <p><b>It is an ORDERED list of steps, and each step is a part pose.</b> A step carries up to the
 * six channels a {@code ModelPart} does and is applied the way one is - the translate, then
 * {@code rotationZYX} - so a body that poses a flattened root writes one step and says exactly what
 * it said before. A sequence is what a renderer's own {@code setupRotations} does instead, bracketing
 * {@code mulPose} calls with translates, and there the order is the whole meaning: a translate
 * between two rotations about different axes is not a triple, and no single part pose spells it.
 * A step naming one channel is exactly a single-axis {@code mulPose}, {@code rotationZYX} reducing to
 * it when the other two angles are zero.
 *
 * <p><b>The only figures a shipped pose names are the ones the tick drives.</b> Everything a subject
 * standing still answers about itself was answered where the table was written - the constant an
 * enum member rests holding, what a question of something the render state holds rests at, what a
 * figure its own render state builds it at - so a channel is either a number or a function of
 * elapsed age and the stride, and a caller supplying none of those gets the frame vanilla draws
 * before anything has happened to the subject.
 *
 * @param container the steps the container is composed of, outermost first, each carrying the
 *     expression its written channels hold
 * @param bones the expression each bone channel is written with, by bone name
 * @param clips the authored clips this model plays, in the order it plays them
 * @param refusal why there is no pose here, or empty when the rest is the whole answer
 */
public record EntityPose(
    @NotNull ConcurrentList<Map<PoseChannel, PoseExpr>> container,
    @NotNull ConcurrentMap<String, Map<PoseChannel, PoseExpr>> bones,
    @NotNull ConcurrentList<Clip> clips,
    @NotNull Optional<String> refusal
) {

    /** The pose of a model that poses nothing, which is a real answer rather than a missing one. */
    public static final @NotNull EntityPose NONE = new EntityPose(Concurrent.newUnmodifiableList(),
        Concurrent.newUnmodifiableMap(Map.of()), Concurrent.newUnmodifiableList(), Optional.empty());

    /**
     * One authored clip this model plays, and what it plays it at.
     *
     * <p>The arguments are the reason a play site is recorded beside the clip table rather than left
     * to it: how fast the thing moves and how far are the model's own constants and appear nowhere
     * in the clip, so two models playing one clip at two rates are indistinguishable without them.
     *
     * <p>The table itself is resolved at load rather than looked up at render, so nothing downstream
     * needs the file's global clip index and a play site naming a clip the file does not carry fails
     * where the file is read.
     *
     * <p><b>A state-driven site names the render-state field its gate reads</b>, which is what says
     * WHICH of the several clips a model declares the caller is choosing between. Without it a
     * reader can play all of them, which is a bat that flies and hangs at once, or none, which is
     * the subject nothing has ticked. The field is answered where every other figure is, so a
     * selection needs no vocabulary of its own.
     *
     * @param coordinate the clip coordinate, keyed the way the table's own clip index is
     * @param gate what drives the clip
     * @param state the render-state field the gate reads, empty where the drive is not a state
     * @param arguments what the model plays it at, in declaration order
     * @param clip the authored table this site plays
     */
    public record Clip(
        @NotNull String coordinate,
        @NotNull Gate gate,
        @NotNull String state,
        @NotNull ConcurrentList<PoseExpr> arguments,
        @NotNull PoseClip clip
    ) {}

    /** What drives a clip, which decides what its own time axis is read from. */
    @EnumLookup
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Gate {

        /** Driven by the walk inputs, at the rate and amplitude the arguments carry. */
        WALK("walk"),

        /** Gated behind a named animation state, and carrying the tick it is played at. */
        STATE("state"),

        /** Held at its first frame, unconditionally. */
        STATIC("static");

        /** The lower-case token this drive is spelled with in the shipped table. */
        @KeyField
        private final @NotNull String token;

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
