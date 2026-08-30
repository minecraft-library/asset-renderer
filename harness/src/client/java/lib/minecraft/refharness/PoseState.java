package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link Gait} the render currently happening is at - the one piece of harness state that moves
 * during a run.
 *
 * <p>It is the same shape as {@code GlintClock.overrideT} and {@code GuiTarget.override}: a plain
 * holder the runner arms before a sweep and every reader consults per render, so a redirect is inert
 * for the frames that are not asking for it. Read on the render thread and written on it, between
 * sweeps rather than during one.
 *
 * <p><b>What this replaces is why a whole-tree render used to boot three times.</b> The freezes and
 * the gait were {@code static final} fields resolved from system properties at class init, so a run
 * that posed one subject posed every subject and the frozen and posed sets could not share a client.
 * Nothing about the mixins required that - both of {@code SkipSetupAnimMixin}'s redirects branch per
 * render - so the boot was being paid for a value's storage class.
 */
@UtilityClass
public final class PoseState {

    /**
     * Where a run starts, which is what the mode properties asked for. A single-mode run never moves
     * it and behaves exactly as it did when this was two final booleans.
     */
    private static @NotNull Gait current = HarnessConfig.WALKING ? Gait.WALK
        : HarnessConfig.ANIMATED ? Gait.IDLE
        : Gait.BIND;

    /**
     * The gait the render currently happening is at.
     *
     * @return the armed gait
     */
    public static @NotNull Gait current() {
        return current;
    }

    /**
     * Whether the render currently happening draws the mesh its model poses.
     *
     * @return true when {@code setupAnim} runs
     */
    public static boolean posed() {
        return current.posed();
    }

    /**
     * Whether the render currently happening drives a stride.
     *
     * @return true when the subject walks
     */
    public static boolean walking() {
        return current.walking();
    }

    /**
     * Arms the gait the next sweep renders at.
     *
     * <p>Called by the runner between sweeps and by nothing else. A sweep that asks for the gait
     * already armed is the ordinary case and costs nothing.
     *
     * @param gait the gait to render at
     */
    public static void arm(@NotNull Gait gait) {
        current = gait;
    }

}
