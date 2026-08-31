package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * What carries a subject's movement, and so which gait a caller asking for movement is asking for.
 *
 * <p>A subject is built and never added to a world, so what moves it is whatever its shipped pose
 * draws from the figures a render answers - never what its AI would have done. Three of these already
 * move where elapsed age is the only figure that stops resting, one needs the stride a gait carries,
 * and two do not move under either, for reasons worth telling apart.
 *
 * <p><b>It is read off the table rather than declared beside it.</b> Every member is decided by
 * evaluating the subject's own poses across one excursion and asking what varied, so a table that
 * changes what a subject writes changes this with it and no roster goes stale. That also means a
 * member is a property of the shipped tables and the excursions in force, not of the entity: a caller
 * who flattens an excursion has a subject that genuinely no longer moves, and this says so.
 *
 * <p><b>Only the first thing that moves is named.</b> A subject whose channels vary is {@link #LIVE}
 * whether or not it also plays a clip, and one whose geometry holds still is {@link #SCROLL} before it
 * is {@link #STRIDE}, because the question this answers is which gait to ask for rather than an
 * inventory of everything the subject does.
 *
 * <p><b>Parity.</b> Read by the entity pose runtime alone, and only where a caller asks for movement
 * without naming a gait - every render that names one reaches the same poses by the same path, so
 * nothing the reference set holds is measured through this.
 */
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(subject = Subject.ENTITY)
public enum MotionSource {

    /**
     * Written channels that vary as elapsed age advances - a head that bobs, a tail that sways.
     *
     * <p>The largest member of the roster, and the one a gait adds nothing to: what drives it is the
     * clock, which every moving preset answers the same way.
     */
    LIVE(EntityOptions.PoseMode.IDLE, true),

    /**
     * A clip the model plays, displacing bones its written channels leave alone.
     *
     * <p>Told apart from {@link #LIVE} because a subject can carry one without the other - a bat
     * writes no bone outside its two clips and would read as still if only channels were asked.
     */
    CLIP(EntityOptions.PoseMode.IDLE, true),

    /**
     * A pass scrolling its texture across the sheet while the geometry holds still.
     *
     * <p>The one member that is not a pose at all: nothing about the mesh moves, so a caller reading
     * bone positions sees a frozen subject and the render still animates.
     */
    SCROLL(EntityOptions.PoseMode.IDLE, true),

    /**
     * Everything the tick would drive rides a stride figure, which rests at zero until a gait names it.
     *
     * <p>The reason a standing subject can look inert and animate the moment it walks: its channels
     * are written and correct, and both figures they read answer their resting zero. This is the whole
     * of what {@link EntityOptions.PoseMode#WALK} exists to reach.
     */
    STRIDE(EntityOptions.PoseMode.WALK, true),

    /**
     * Still under either gait, because the pose reads a figure no render here answers.
     *
     * <p>Held apart from {@link #INERT} because the two want different things done about them: this
     * carries an animation with no driver, which is a figure to add, where an inert subject carries no
     * animation at all and is complete as it stands.
     *
     * <p><b>Movement a subject's RENDERER carries rather than its model is {@link #INERT} here</b> - a
     * slime scaled by a squish only a ticking world fills writes nothing in its model, so no shipped
     * table names the figure and there is nothing for this to read. What this names is a table that
     * asks for a figure, which is why a new one arriving is reported rather than quietly still.
     */
    TICKED(EntityOptions.PoseMode.IDLE, false),

    /**
     * Still, and nothing the shipped tables carry would change it.
     *
     * <p>An armour stand is one. Asking for movement is answered honestly with the resting preset,
     * because there is no gait under which this subject's own model does anything else.
     */
    INERT(EntityOptions.PoseMode.IDLE, false);

    /**
     * The preset that moves a subject this carries, which is the least one that reaches its movement.
     *
     * <p>{@link EntityOptions.PoseMode#IDLE} for everything but a stride, including the two that do
     * not move: a subject nothing drives is posed at the resting preset rather than walked, because
     * walking one that holds still offline would invent a gait its own table never asked for.
     */
    private final EntityOptions.@NotNull PoseMode gait;

    /**
     * Whether asking for movement actually produces any, so a caller can tell a still render from a
     * broken one.
     */
    private final boolean animates;

}
