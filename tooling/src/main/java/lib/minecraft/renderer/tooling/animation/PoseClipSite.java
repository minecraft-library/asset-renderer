package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
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
 * <p><b>A state-driven site names the field its gate reads, and every other reference argument is
 * absent rather than placeheld.</b> Which animation state a clip sits behind is the one fact that
 * decides whether the clip plays at all, so a site that carried only "it sits behind one" leaves a
 * reader with nothing to ask a caller - and the reader would have to choose between playing every
 * state-driven clip a model declares, which is a pose vanilla never draws, and playing none, which
 * is a subject nothing has ticked. The field is a render-state member like any other, so it is
 * answered where every other figure is.
 *
 * <p><b>A site also keeps the branch it sits inside, and a walk-driven one is why.</b> A state's own
 * gate is the whole of its condition - the field says which clip is running - so a state-driven site
 * inside a branch needs nothing further. A walk-driven gate is the stride itself, which answers for
 * every site at once, so a model that chooses between two walk clips chooses with an ordinary
 * {@code if}: a frog swims or walks on {@code isSwimming}, a copper golem walks or walks-with-an-item
 * on whether either hand is empty. A walk that recorded both arms and dropped the test would ship a
 * model playing both clips over each other, which is a pose vanilla never draws.
 *
 * @param clip the clip coordinate, in the same {@code Class#member} spelling the clip table is keyed by
 * @param drive what decides whether the clip contributes
 * @param state the render-state field the gate reads, empty where the drive is not a state
 * @param arguments the numeric arguments the call passes, in declaration order
 * @param condition what the branches this site sits inside decided, as an expression that is
 *     non-zero exactly where they reach it - {@link #ALWAYS} for a site no branch guards
 */
public record PoseClipSite(
    @NotNull String clip,
    @NotNull Gate drive,
    @NotNull String state,
    @NotNull List<PoseExpr> arguments,
    @NotNull PoseExpr condition
) {

    /** The condition of a site nothing guards, which is what every unbranched call carries. */
    public static final @NotNull PoseExpr ALWAYS = PoseExpr.Const.of(1f);

    /** The condition of a site the fold proved unreachable, which is what drops it from the table. */
    public static final @NotNull PoseExpr NEVER = PoseExpr.Const.of(0f);

    /**
     * This site under one further branch.
     *
     * <p>Nested rather than replaced, so a clip two branches deep carries both - the outer select's
     * false arm is zero, and a zero anywhere in the chain makes the whole condition zero.
     *
     * @param condition what the branch tested
     * @param reached whether this site is on the arm the branch takes
     * @return the site carrying the branch
     */
    public @NotNull PoseClipSite guarded(@NotNull PosePredicate condition, boolean reached) {
        return new PoseClipSite(this.clip, this.drive, this.state, this.arguments,
            reached
                ? new PoseExpr.Select(condition, this.condition, NEVER)
                : new PoseExpr.Select(condition, NEVER, this.condition));
    }

    /**
     * What decides whether a clip contributes.
     *
     * <p>The distinction is the whole reason a pose selector can be built out of these: a clip
     * behind a per-entity animation state contributes nothing to a render that starts no state,
     * which is every render this library takes.
     */
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Gate {

        /** Held at its first frame unconditionally - the only clip kind that always contributes. */
        STATIC("static"),

        /** Driven by the walk inputs, so it contributes exactly as far as the subject is walking. */
        WALK("walk"),

        /**
         * Behind a running animation state - a roar, a dig, a sit, an idle. Vanilla starts one from
         * its own {@code setupAnimationStates}, so which of a model's several is running is a
         * selection over the render-state fields they are held in, and the site names the one it
         * reads.
         */
        STATE("state");

        /** The token this gate is spelled with in the shipped table. */
        private final @NotNull String token;

    }

}
