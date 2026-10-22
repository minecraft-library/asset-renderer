package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.animation.WalkPace;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The movement chains a subject walks a steady wander by, each turning a speed setting into the
 * distance it covers a tick.
 *
 * <p>Every arm starts where vanilla's move control does: the set speed is the float
 * {@code (float) (modifier * attribute)}, the narrowing {@code MoveControl.tick} makes. What follows is
 * the steady state of the arm's own travel - the thrust it adds a tick and the drag it keeps - worked
 * out in closed form, because a steady walk is the fixed point of the tick and nothing about it needs
 * sampling. One shared step then turns the distance into the walk pair through the subject's walk feed.
 *
 * <p>The constants are vanilla's, at the type vanilla states each in: a block's default friction and
 * the air drag on the ground are floats, the water drag a double, a flier's drag a float widened.
 */
enum PaceChain {

    /**
     * Walking a default block. The thrust is the set speed applied to an input of the set speed, and
     * the drag the block's friction times the air's, so the steady distance is {@code s * s / (1 - k)}.
     */
    GROUND {
        @Override
        double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim) {
            return ground(setSpeed(modifier, speedAttribute));
        }
    },

    /**
     * Walking a default block under a smooth swimmer's control, which sets the speed out of water to
     * its own share of the set speed before the ground's thrust and drag apply.
     */
    SMOOTH_ON_LAND {
        @Override
        double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim) {
            return ground(setSpeed(modifier, speedAttribute) * factors(this, swim).outsideWater());
        }
    },

    /**
     * Swimming under a smooth swimmer's control: the input is the set speed, held to a length of one,
     * the thrust the in-water share of it, and the drag the water's.
     */
    SMOOTH_SWIM {
        @Override
        double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim) {
            float set = setSpeed(modifier, speedAttribute);
            float thrust = set * factors(this, swim).inWater();
            return Math.min(set, 1.0) * thrust / (1.0 - WATER_DRAG);
        }
    },

    /**
     * Swimming more than sixteen blocks from home under the turtle's control, whose speed halves to a
     * floor every tick - and thirds to a lower one on a baby - before easing an eighth of the way back
     * to the set speed. At an amble the floors bind, so the steady speed is the ease from the floor.
     */
    TURTLE_SWIM_FAR {
        @Override
        double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim) {
            float set = setSpeed(modifier, speedAttribute);
            float floor = baby ? TURTLE_BABY_FLOOR : TURTLE_FAR_FLOOR;
            float steady = floor + TURTLE_EASE * (set - floor);
            float halved = Math.max(steady / 2.0f, TURTLE_FAR_FLOOR);
            boolean floored = baby ? Math.max(halved / 3.0f, TURTLE_BABY_FLOOR) == TURTLE_BABY_FLOOR : halved == TURTLE_FAR_FLOOR;
            if (!floored)
                throw new ToolingException("A turtle setting of '%s' escapes the speed floor its steady swim is worked out on", set);
            return turtleSwim(steady);
        }
    },

    /**
     * Swimming within sixteen blocks of home under the turtle's control, where the speed does not halve:
     * an adult settles on the set speed itself, and a baby's thirding still floors it before the ease.
     */
    TURTLE_SWIM_NEAR {
        @Override
        double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim) {
            float set = setSpeed(modifier, speedAttribute);
            if (!baby) return turtleSwim(set);
            float steady = TURTLE_BABY_FLOOR + TURTLE_EASE * (set - TURTLE_BABY_FLOOR);
            if (Math.max(steady / 3.0f, TURTLE_BABY_FLOOR) != TURTLE_BABY_FLOOR)
                throw new ToolingException("A baby turtle setting of '%s' escapes the speed floor its steady swim is worked out on", set);
            return turtleSwim(steady);
        }
    },

    /**
     * Flying level on the flying speed. The thrust is the set speed applied to an input of the set
     * speed, and the drag the air's, so the steady distance is {@code s * s / (1 - 0.91)}.
     */
    FLYING {
        @Override
        double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim) {
            double set = setSpeed(modifier, speedAttribute);
            return set * set / (1.0 - AIR_DRAG);
        }
    },

    /**
     * Moving at a velocity a behaviour sets every tick rather than at a walk - the modifier is the
     * distance itself, and the speed attribute is not read.
     */
    VELOCITY {
        @Override
        double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim) {
            return modifier;
        }
    };

    /** A default block's friction times the air's drag, as the float vanilla multiplies them into. */
    private static final float GROUND_DRAG = 0.6f * 0.91f;

    /** The air's drag in flight, the float vanilla widens to scale a flier's motion by. */
    private static final double AIR_DRAG = 0.91f;

    /** The water's drag, the double vanilla scales a swimmer's motion by. */
    private static final double WATER_DRAG = 0.9;

    /** The thrust the turtle's water travel applies its speed through. */
    private static final float TURTLE_THRUST = 0.1f;

    /** The floor the turtle's speed halves to far from home. */
    private static final float TURTLE_FAR_FLOOR = 0.08f;

    /** The floor a baby turtle's speed thirds to. */
    private static final float TURTLE_BABY_FLOOR = 0.06f;

    /** The share of the way back to its set speed the turtle's speed eases a tick. */
    private static final float TURTLE_EASE = 0.125f;

    /**
     * How a distance a tick becomes the walk clock, read off the subject's {@code updateWalkAnimation}.
     *
     * @param declaring the class declaring the feed the subject runs
     * @param gain what the distance a tick is multiplied by into the target speed
     * @param cap the most the target speed reaches
     * @param babyScale what a baby's clock is scaled by as the model reads it
     */
    record WalkFeed(@NotNull String declaring, float gain, float cap, float babyScale) { }

    /**
     * The two shares of its set speed a smooth swimmer moves at.
     *
     * @param inWater the share it swims at
     * @param outsideWater the share it walks at out of water
     */
    record SwimFactors(float inWater, float outsideWater) { }

    /**
     * The steady blocks a tick this arm covers, for one speed modifier and the speed attribute it
     * reads.
     *
     * @param modifier the speed setting a behaviour hands the navigation, or the velocity itself
     * @param speedAttribute the attribute the setting multiplies
     * @param baby whether the side walked is a baby's
     * @param swim the smooth swimmer's two shares, where the arm is a smooth swimmer's
     * @return the distance a steady walk covers a tick
     */
    abstract double distance(double modifier, double speedAttribute, boolean baby, @NotNull Optional<SwimFactors> swim);

    /**
     * The walk pair one steady walk drives: the feed's target over the distance, the swing clamped at
     * one as the model reads it, and the clock at the target - scaled by the feed on a baby.
     *
     * <p>A baby's clock is the one float operation after the narrowing wherever the target is at most
     * one, so its bits are exactly the feed's scale times the swing.
     *
     * @param modifier the speed setting a behaviour hands the navigation, or the velocity itself
     * @param speedAttribute the attribute the setting multiplies
     * @param baby whether the side walked is a baby's
     * @param swim the smooth swimmer's two shares, where the arm is a smooth swimmer's
     * @param feed the subject's walk feed
     * @param basis the inputs the walk is worked out from, as the diagnostics line prints them
     * @return the walk pair
     */
    @NotNull WalkPace pace(
        double modifier, double speedAttribute, boolean baby,
        @NotNull Optional<SwimFactors> swim, @NotNull WalkFeed feed, @NotNull String basis) {

        double target = Math.min(feed.gain() * distance(modifier, speedAttribute, baby, swim), feed.cap());
        float speed = (float) Math.min(target, 1.0);
        float rate = !baby ? (float) target
            : target <= 1.0 ? feed.babyScale() * speed
            : (float) (target * feed.babyScale());
        return new WalkPace(speed, rate, basis);
    }

    /**
     * The speed a move control sets for a speed setting - the setting times the attribute, narrowed.
     *
     * @param modifier the speed setting
     * @param speedAttribute the attribute it multiplies
     * @return the set speed
     */
    static float setSpeed(double modifier, double speedAttribute) {
        return (float) (modifier * speedAttribute);
    }

    /** The steady distance a walk at one set speed covers on a default block. */
    private static double ground(float set) {
        return (double) set * set / (1.0 - GROUND_DRAG);
    }

    /** The steady distance the turtle's water travel covers at one steady speed. */
    private static double turtleSwim(float steady) {
        return (double) steady * TURTLE_THRUST / (1.0 - WATER_DRAG);
    }

    /** The smooth swimmer's shares, which a smooth arm cannot walk without. */
    private static @NotNull SwimFactors factors(@NotNull PaceChain arm, @NotNull Optional<SwimFactors> swim) {
        return swim.orElseThrow(() -> new ToolingException("Chain '%s' walks a smooth swimmer and was handed no swim factors", arm));
    }

}
