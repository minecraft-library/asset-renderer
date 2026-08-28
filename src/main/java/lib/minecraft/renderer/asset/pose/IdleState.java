package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * A state a subject is IN, carried across several render-state factors as a smoothed one-hot.
 *
 * <p>Vanilla decides one member of an enum and ramps a factor per member toward "am I the one", so
 * the factors are an encoding of a single choice rather than several sliders. An axolotl's is the
 * corpus's first: {@code tickAdultAnimations} picks one of {@code PLAYING_DEAD}, {@code IN_WATER},
 * {@code ON_GROUND} and {@code IN_AIR}, then ticks three animators on {@code state == <member>}.
 *
 * <p><b>Which is why moving one of them across a range is wrong.</b> Two factors at a half each is a
 * value vanilla only ever produces during the ten-tick crossfade between two states, so a strip that
 * swept them would render a transition rather than a fuller rest - and driving two to one at once is
 * a pose the one-hot can never reach. A caller SELECTS instead, and the selected member answers one
 * while every other answers zero.
 *
 * <p><b>Selecting is also how the animation is turned off.</b> {@link #IN_AIR} names no factor, so
 * choosing it rests all of them, which is exactly the never-ticked subject this exists to move away
 * from.
 *
 * <p><b>Not a kind of {@link IdleFigure}, though the two are answered side by side.</b> A figure is
 * a function of the tick and carries no notion of a selection; a state is a function of a selection
 * and carries no notion of the tick. They share only that the runtime resolves both by render-state
 * field name, which is a dispatch rather than a behaviour - held under one interface each ignored
 * the other's parameter.
 *
 * <p>The vocabulary is vanilla's own rather than a coinage: these are its enum's members, so a
 * version that adds one adds it here rather than reshaping anything. The same shape is what a
 * clip-driven subject needs - which of several {@code AnimationState} clips is playing is one choice
 * over an enum too - so this is the seam that group grows from.
 *
 * <p><b>Parity.</b> Read by the entity pose runtime alone, so an entity is the whole of what it
 * moves. A caller that selects other than the default has left the reference set behind, which is a
 * property of that render rather than of this roster.
 */
@EnumLookup
@RequiredArgsConstructor
@Getter(style = NamingStyle.FLUENT)
@Parity(subject = Subject.ENTITY)
public enum IdleState {

    /**
     * An axolotl rolled onto its back.
     *
     * <p>In the same one-hot as the other three despite its animation being layered on top of their
     * blend rather than being one of its weights - playing dead excludes being in water and being on
     * the ground, so it is a place in the selector and not a fifth slider.
     */
    PLAYING_DEAD("playingDeadFactor"),

    /**
     * An axolotl in water, which is what an idle one selects.
     *
     * <p>The default because the subject is aquatic: with this selected its model weights hovering
     * and swimming and rests the two ground blends, so the one continuous factor it carries has
     * something to blend BETWEEN.
     */
    IN_WATER("inWaterFactor"),

    /** An axolotl on a surface, which weights its crawl and its lie-still blends. */
    ON_GROUND("onGroundFactor"),

    /**
     * An axolotl in neither, which every factor rests through.
     *
     * <p>Named with the empty token because a state that drives no factor is still a member a caller
     * may choose, and choosing it is how an idle render asks for the subject to hold still. No
     * render-state field is spelled that way, so it collides with nothing the lookup answers for.
     */
    IN_AIR("");

    /** Which member an idle render selects where a caller names none. */
    public static final @NotNull IdleState DEFAULT = IN_WATER;

    /** The render-state factor this member ramps, or empty where the member drives none. */
    @KeyField
    private final @NotNull String field;

    /**
     * What one factor holds when a given member is the selected one.
     *
     * <p>A one-hot and nothing else: the selected member's own factor answers one and every other
     * answers zero. There is no ramp, because a ramp is the crossfade between two states and an idle
     * subject is already in the one it is in - which is also why this takes no tick.
     *
     * @param selected the member the caller selected
     * @return one where this factor belongs to the selected member, zero otherwise
     */
    public float when(@NotNull IdleState selected) {
        return selected == this ? 1f : 0f;
    }

}
