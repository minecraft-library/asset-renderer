package lib.minecraft.renderer.asset.pose;

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
 * <p><b>A state silhouette is evidence the model carries beside its pose, and nothing at render
 * reads it.</b> Vanilla's {@code setupAnim} branches on questions of the render state a resting
 * subject answers one way - whether a wolf sits, which pose a parrot holds - and the shipped
 * channels hold the arm the resting subject takes. Each other arm is folded once more at rest with
 * that one answer flipped, and the bones it places away from the resting row are carried under
 * the answer that reaches them: a sitting wolf's lowered body, and the tail and hind legs placed
 * by hand where that body carries them. The runtime evaluates the pose exactly as written; the
 * silhouettes are read by pose authoring alone, beside the mesh, for what they show about which
 * parts vanilla moves together.
 *
 * @param container the steps the container is composed of, outermost first, each carrying the
 *     expression its written channels hold
 * @param bones the expression each bone channel is written with, by bone name
 * @param clips the authored clips this model plays, in the order it plays them
 * @param refusal why there is no pose here, or empty when the rest is the whole answer
 * @param states the resting silhouette of each state branch this model poses, keyed by the
 *     render-state answer that reaches it as {@code member=value} - empty for a model branching
 *     on nothing a state can flip
 */
public record EntityPose(
    @NotNull ConcurrentList<Map<PoseChannel, PoseExpr>> container,
    @NotNull ConcurrentMap<String, Map<PoseChannel, PoseExpr>> bones,
    @NotNull ConcurrentList<Clip> clips,
    @NotNull Optional<String> refusal,
    @NotNull ConcurrentMap<String, Silhouette> states
) {

    /** The pose of a model that poses nothing, which is a real answer rather than a missing one. */
    public static final @NotNull EntityPose NONE = new EntityPose(Concurrent.newUnmodifiableList(),
        Concurrent.newUnmodifiableMap(Map.of()), Concurrent.newUnmodifiableList(), Optional.empty());

    /**
     * Constructs a pose carrying no state silhouettes.
     *
     * @param container the steps the container is composed of, outermost first
     * @param bones the expression each bone channel is written with, by bone name
     * @param clips the authored clips this model plays, in the order it plays them
     * @param refusal why there is no pose here, or empty when the rest is the whole answer
     */
    public EntityPose(
        @NotNull ConcurrentList<Map<PoseChannel, PoseExpr>> container,
        @NotNull ConcurrentMap<String, Map<PoseChannel, PoseExpr>> bones,
        @NotNull ConcurrentList<Clip> clips,
        @NotNull Optional<String> refusal) {

        this(container, bones, clips, refusal, Concurrent.newUnmodifiableLinkedMap());
    }

    /**
     * Where a model's bones stand in one state it poses - the resting silhouette of one branch of
     * its {@code setupAnim}, holding only what that branch places away from the resting row.
     *
     * <p>Every channel is spelled at rest: a figure the tick drives is folded at what it rests at,
     * so a channel that is the stride plus a tuck in that state carries the tuck alone, and a
     * placement relative to the authored pivot keeps the read of that pivot. Evaluated against a
     * mesh with every figure resting, it answers where the bone stands.
     *
     * @param bones the position and rotation channels each placed bone holds in this state, by
     *     bone name - a bone the state leaves where the resting row leaves it is absent
     */
    public record Silhouette(
        @NotNull ConcurrentMap<String, Map<PoseChannel, PoseExpr>> bones
    ) {}

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
     * @param drive what drives the clip, which decides what its own time axis is read from
     * @param field the render-state field a selection reads, present exactly where the drive is
     *     {@link MotionSource#SELECT}
     * @param arguments what the model plays it at, in declaration order
     * @param clip the authored table this site plays
     */
    public record Clip(
        @NotNull String coordinate,
        @NotNull MotionSource drive,
        @NotNull Optional<String> field,
        @NotNull ConcurrentList<PoseExpr> arguments,
        @NotNull PoseClip clip
    ) {}

    /**
     * Whether this is a pose rather than a record of why there is not one.
     *
     * @return {@code true} when the bones and clips are the whole answer
     */
    public boolean isReadable() {
        return this.refusal.isEmpty();
    }

}
