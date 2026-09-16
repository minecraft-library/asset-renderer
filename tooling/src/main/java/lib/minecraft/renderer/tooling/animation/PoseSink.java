package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import lib.minecraft.renderer.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The eleven {@code ModelPart} members a {@code setupAnim} body can write, each named by the vanilla
 * field a {@code putfield} spells it with.
 *
 * <p>This is the whole sink vocabulary, measured rather than anticipated: every write in the
 * reachable corpus is a {@code putfield} on one of these, and the four mutating methods vanilla
 * declares beside them - {@code setRotation}, {@code offsetPos}, {@code offsetRotation} and
 * {@code translateAndRotate} - are called from nowhere a pose walk reaches. So a member outside this
 * set is a finding rather than a sink to add quietly.
 *
 * <p>The vanilla field name is what separates this from {@link PoseChannel}, and it is why both
 * exist. A field name is a fact about bytecode - the walk has an instruction and needs the member it
 * names - where a channel is a fact about what a shipped table may say. Nine of these carry the
 * channel they write and take their token from it, so the emitted vocabulary is spelled once, on the
 * side that has to read it back.
 *
 * <p>The other two carry no channel. A flag is only ever assigned, it folds to a literal at
 * generation, and which bones a subject rests without is written onto the mesh rather than into a
 * pose - so nothing at render reads one and no shipped table has a word for it.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
public enum PoseSink {

    /** Sideways offset of the bone's pivot, in model pixels. */
    X("x", PoseChannel.X),

    /** Vertical offset of the bone's pivot, in model pixels on vanilla's y-down axis. */
    Y("y", PoseChannel.Y),

    /** Depth offset of the bone's pivot, in model pixels. */
    Z("z", PoseChannel.Z),

    /** Rotation about the bone's x axis, in radians. */
    X_ROT("xRot", PoseChannel.X_ROT),

    /** Rotation about the bone's y axis, in radians. */
    Y_ROT("yRot", PoseChannel.Y_ROT),

    /** Rotation about the bone's z axis, in radians. */
    Z_ROT("zRot", PoseChannel.Z_ROT),

    /** Multiplier on the bone's x extent, defaulting to one rather than to zero. */
    X_SCALE("xScale", PoseChannel.X_SCALE),

    /** Multiplier on the bone's y extent, defaulting to one rather than to zero. */
    Y_SCALE("yScale", PoseChannel.Y_SCALE),

    /** Multiplier on the bone's z extent, defaulting to one rather than to zero. */
    Z_SCALE("zScale", PoseChannel.Z_SCALE),

    /** Whether the bone and its descendants draw at all. */
    VISIBLE("visible", "visible"),

    /** Whether the bone's own cubes are skipped while its descendants still draw. */
    SKIP_DRAW("skipDraw", "skip_draw");

    /** The vanilla {@code ModelPart} field name a {@code putfield} on this sink names. */
    @KeyField
    private final @NotNull String field;

    /** The token this sink is spelled with where it is written down. */
    private final @NotNull String token;

    /** The shipped channel this sink writes, and nothing for a flag. */
    private final @NotNull Optional<PoseChannel> channel;

    PoseSink(@NotNull String field, @NotNull PoseChannel channel) {
        this.field = field;
        this.token = channel.token();
        this.channel = Optional.of(channel);
    }

    PoseSink(@NotNull String field, @NotNull String token) {
        this.field = field;
        this.token = token;
        this.channel = Optional.empty();
    }

    /**
     * Whether this sink carries a boolean rather than a float.
     *
     * @return {@code true} for the two that write no channel
     */
    public boolean isFlag() {
        return this.channel.isEmpty();
    }

}
