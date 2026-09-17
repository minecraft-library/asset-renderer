package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import org.jetbrains.annotations.NotNull;

/**
 * The two {@code ModelPart} members a {@code setupAnim} body assigns that decide whether a bone
 * draws rather than where it goes.
 *
 * <p>Apart from {@link PoseChannel} because nothing at render reads one. A channel is a number a
 * caller supplies inputs for at the tick; a flag is settled while the table is written, and which
 * bones a subject rests without is stamped onto the MESH it rests in rather than into a pose. So no
 * shipped table has a word for either of these, and a walk carries them only far enough for the fold
 * to answer what a resting subject draws.
 *
 * <p>Each is only ever assigned, never accumulated, so what a bone reads before anything writes it
 * is a literal rather than a read of itself - {@link #rest} is that literal, and it differs between
 * the two, a part drawing until something hides it and skipping none of its own cubes until
 * something says otherwise.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
public enum BoneFlag {

    /** Whether the bone and its descendants draw at all. */
    VISIBLE("visible", 1),

    /** Whether the bone's own cubes are skipped while its descendants still draw. */
    SKIP_DRAW("skipDraw", 0);

    /** The vanilla {@code ModelPart} field name a {@code putfield} on this flag names. */
    @KeyField
    private final @NotNull String field;

    /** What this flag reads as before anything has written it. */
    private final int rest;

    /**
     * This flag's value before anything has written it, as the expression a read of it answers.
     *
     * <p>An {@code int} literal rather than a float one, because what a flag carries is a boolean
     * and the width it is spelled at is a fact the fold and the writer both read.
     *
     * @return the resting literal
     */
    public @NotNull PoseExpr resting() {
        return new PoseExpr.Constant(this.rest);
    }

}
